package dev.paperreader.extensions.sources.common

import org.junit.Assert.assertEquals
import org.junit.Test

class SourceRateLimitPolicyTest {
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
