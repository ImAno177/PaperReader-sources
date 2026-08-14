package dev.paperreader.extensions.sources.common

import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Binder
import android.os.Bundle
import android.os.IBinder
import dev.paperreader.extensions.api.ExtensionFailure
import dev.paperreader.extensions.api.ExtensionFailureCode
import dev.paperreader.extensions.api.ExtensionPayloadValidator
import dev.paperreader.extensions.api.IPaperSourceCallback
import dev.paperreader.extensions.api.IPaperSourceService
import dev.paperreader.extensions.api.PaperExtensionContract
import dev.paperreader.extensions.api.SourceExtensionDescriptor
import dev.paperreader.extensions.api.SourceGetPaperRequest
import dev.paperreader.extensions.api.SourcePaperResponse
import dev.paperreader.extensions.api.SourceSearchPage
import dev.paperreader.extensions.api.SourceSearchRequest
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URI
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

abstract class PaperSourceService : Service() {
    protected abstract val descriptor: SourceExtensionDescriptor
    protected abstract val hostSignerSha256: String
    protected abstract val allowedHosts: Set<String>

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val jobs = ConcurrentHashMap<String, Job>()
    private val connections = ConcurrentHashMap<String, HttpURLConnection>()
    private val rateGate = Mutex()
    private var nextRequestAtMillis = 0L

    protected abstract suspend fun searchSource(request: SourceSearchRequest): SourceSearchPage
    protected abstract suspend fun getPaperSource(request: SourceGetPaperRequest): SourcePaperResponse

    private val binder = object : IPaperSourceService.Stub() {
        override fun getDescriptor(): Bundle {
            requirePaperReaderCaller()
            return this@PaperSourceService.descriptor.toBundle()
        }

        override fun search(request: Bundle, callback: IPaperSourceCallback) {
            requirePaperReaderCaller()
            decode(request, callback, SourceSearchRequest::fromBundle)?.let { decoded ->
                submit(decoded.requestId, callback) { searchSource(decoded).toBundle() }
            }
        }

        override fun getPaper(request: Bundle, callback: IPaperSourceCallback) {
            requirePaperReaderCaller()
            decode(request, callback, SourceGetPaperRequest::fromBundle)?.let { decoded ->
                submit(decoded.requestId, callback) { getPaperSource(decoded).toBundle() }
            }
        }

        override fun cancel(requestId: String) {
            requirePaperReaderCaller()
            connections.remove(requestId)?.disconnect()
            jobs.remove(requestId)?.cancel()
        }
    }

    final override fun onBind(intent: Intent?): IBinder? =
        binder.takeIf { intent?.action == PaperExtensionContract.SOURCE_SERVICE_ACTION }

    final override fun onDestroy() {
        connections.values.forEach(HttpURLConnection::disconnect)
        scope.cancel()
        super.onDestroy()
    }

    protected suspend fun get(requestId: String, rawUrl: String, accept: String = "application/json"): String {
        awaitRateGate()
        return withContext(Dispatchers.IO) {
            val uri = URI(rawUrl)
            require(uri.scheme == "https" && uri.host?.lowercase() in allowedHosts) { "Unexpected source URL" }
            val connection = uri.toURL().openConnection() as HttpURLConnection
            connections[requestId] = connection
            try {
                connection.requestMethod = "GET"
                connection.connectTimeout = CONNECT_TIMEOUT_MILLIS
                connection.readTimeout = READ_TIMEOUT_MILLIS
                connection.instanceFollowRedirects = false
                connection.setRequestProperty("Accept", accept)
                connection.setRequestProperty("User-Agent", USER_AGENT)
                when (val status = connection.responseCode) {
                    404 -> throw SourceNotFoundException()
                    429 -> throw SourceRateLimitedException(parseRetryAfter(connection.getHeaderField("Retry-After")))
                    in 200..299 -> readBounded(connection)
                    else -> throw SourceUnavailableException("Provider returned HTTP $status")
                }
            } finally {
                connections.remove(requestId, connection)
                connection.disconnect()
            }
        }
    }

    private suspend fun awaitRateGate() = rateGate.withLock {
        val waitMillis = (nextRequestAtMillis - System.currentTimeMillis()).coerceAtLeast(0)
        if (waitMillis > 0) delay(waitMillis)
        nextRequestAtMillis = System.currentTimeMillis() + descriptor.minimumRequestIntervalMillis
    }

    private fun readBounded(connection: HttpURLConnection): String {
        val declaredLength = connection.contentLengthLong
        require(declaredLength <= MAX_RESPONSE_BYTES || declaredLength < 0) { "Provider response is too large" }
        val output = ByteArrayOutputStream()
        connection.inputStream.use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                require(output.size() + count <= MAX_RESPONSE_BYTES) { "Provider response is too large" }
                output.write(buffer, 0, count)
            }
        }
        return output.toString(StandardCharsets.UTF_8.name())
    }

    private fun submit(requestId: String, callback: IPaperSourceCallback, block: suspend () -> Bundle) {
        val job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                val response = block()
                ExtensionPayloadValidator.requireBinderSafe(response)
                callback.onSuccess(response)
            } catch (_: CancellationException) {
                runCatching {
                    callback.onFailure(
                        ExtensionFailure(requestId, ExtensionFailureCode.CANCELLED, "Request cancelled").toBundle(),
                    )
                }
            } catch (error: Exception) {
                runCatching { callback.onFailure(error.toFailure(requestId).toBundle()) }
            } finally {
                jobs.remove(requestId)
            }
        }
        if (jobs.putIfAbsent(requestId, job) == null) {
            job.start()
        } else {
            job.cancel()
            runCatching {
                callback.onFailure(
                    ExtensionFailure(
                        requestId,
                        ExtensionFailureCode.INVALID_REQUEST,
                        "Duplicate request ID",
                    ).toBundle(),
                )
            }
        }
    }

    private fun <T> decode(request: Bundle, callback: IPaperSourceCallback, block: (Bundle) -> T): T? =
        try {
            block(request)
        } catch (error: Exception) {
            runCatching {
                callback.onFailure(
                    ExtensionFailure(
                        requestId = "invalid-request",
                        code = ExtensionFailureCode.INVALID_REQUEST,
                        message = error.safeMessage("Invalid request"),
                    ).toBundle(),
                )
            }
            null
        }

    private fun requirePaperReaderCaller() {
        val packages = packageManager.getPackagesForUid(Binder.getCallingUid()).orEmpty()
        require(HOST_PACKAGE in packages) { "Caller is not PaperReader" }
        require(hostSignerSha256.matches(Regex("[0-9a-fA-F]{64}"))) { "Host signer is not configured" }
        require(
            packageManager.hasSigningCertificate(
                HOST_PACKAGE,
                hostSignerSha256.hexToBytes(),
                PackageManager.CERT_INPUT_SHA256,
            ),
        ) { "PaperReader signer is not trusted" }
    }

    private fun Exception.toFailure(requestId: String): ExtensionFailure = when (this) {
        is SourceRateLimitedException -> ExtensionFailure(
            requestId,
            ExtensionFailureCode.RATE_LIMITED,
            "Provider rate limited the request",
            retryAfterMillis,
        )
        is SourceUnavailableException -> ExtensionFailure(
            requestId,
            ExtensionFailureCode.UNAVAILABLE,
            safeMessage("Provider is unavailable"),
        )
        is IllegalArgumentException -> ExtensionFailure(
            requestId,
            ExtensionFailureCode.INVALID_RESPONSE,
            safeMessage("Provider returned an invalid response"),
        )
        else -> ExtensionFailure(
            requestId,
            ExtensionFailureCode.UNAVAILABLE,
            safeMessage("Provider is unavailable"),
        )
    }

    private fun Exception.safeMessage(fallback: String): String =
        message?.trim()?.take(512)?.takeIf(String::isNotBlank) ?: fallback

    private fun parseRetryAfter(value: String?): Long? {
        value ?: return null
        value.toLongOrNull()?.let { return it.coerceAtLeast(0).times(1_000) }
        return runCatching {
            (ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli() -
                Instant.now().toEpochMilli()).coerceAtLeast(0)
        }.getOrNull()
    }

    private fun String.hexToBytes(): ByteArray = chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    protected class SourceNotFoundException : RuntimeException()
    protected class SourceRateLimitedException(val retryAfterMillis: Long?) : RuntimeException()
    protected class SourceUnavailableException(message: String) : RuntimeException(message)

    private companion object {
        const val HOST_PACKAGE = "dev.paperreader.app"
        const val USER_AGENT = "PaperReader-sources/0.1 (Android)"
        const val CONNECT_TIMEOUT_MILLIS = 10_000
        const val READ_TIMEOUT_MILLIS = 20_000
        const val MAX_RESPONSE_BYTES = 2 * 1024 * 1024
    }
}
