package dev.paperreader.extensions.sources.common

import org.junit.Assert.assertEquals
import org.junit.Test

class SourceRateLimitPolicyTest {
    @Test
    fun `user agent identifies the provider and installed version`() {
        assertEquals(
            "PaperReader-arxiv/0.1.3 (Android; +https://github.com/ImAno177/PaperReader-sources)",
            buildSourceUserAgent("arxiv", "0.1.3"),
        )
    }

    @Test
    fun `user agent remains a valid product token when version metadata is missing`() {
        assertEquals(
            "PaperReader-community_source/unknown (Android; +https://github.com/ImAno177/PaperReader-sources)",
            buildSourceUserAgent("community source", null),
        )
    }

    @Test
    fun `missing retry after uses bounded exponential cooldown`() {
        val policy = SourceRateLimitPolicy(baseMillis = 60_000, maximumMillis = 240_000)

        assertEquals(60_000, policy.nextBackoffMillis())
        assertEquals(120_000, policy.nextBackoffMillis())
        assertEquals(240_000, policy.nextBackoffMillis())
        assertEquals(240_000, policy.nextBackoffMillis())
    }

    @Test
    fun `successful request resets the cooldown strike`() {
        val policy = SourceRateLimitPolicy(baseMillis = 15_000, maximumMillis = 60_000)

        assertEquals(15_000, policy.nextBackoffMillis())
        assertEquals(30_000, policy.nextBackoffMillis())
        policy.reset()
        assertEquals(15_000, policy.nextBackoffMillis())
    }
}
