package com.manzili.hai

import com.manzili.hai.ai.AiRoutingPolicy
import org.junit.Assert.assertTrue
import org.junit.Test

class AiRoutingPolicyTest {
    @Test fun strongerNamesRankAheadOfSmallerFallbacks() {
        assertTrue(AiRoutingPolicy.strength("vendor/model-ultra") > AiRoutingPolicy.strength("vendor/model-mini"))
        assertTrue(AiRoutingPolicy.strength("vendor/model-70b") > AiRoutingPolicy.strength("vendor/model-8b"))
    }

    @Test fun openRouterFreeRouterRemainsHighPriorityFallback() {
        assertTrue(AiRoutingPolicy.strength("openrouter/free") >= 150)
    }

    @Test fun quotaTimeoutAndServerErrorsAreRetryable() {
        assertTrue(AiRoutingPolicy.retryableHttp(402))
        assertTrue(AiRoutingPolicy.retryableHttp(408))
        assertTrue(AiRoutingPolicy.retryableHttp(429))
        assertTrue(AiRoutingPolicy.retryableHttp(503))
    }
}
