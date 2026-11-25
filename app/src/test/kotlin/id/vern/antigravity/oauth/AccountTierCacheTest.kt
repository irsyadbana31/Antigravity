package id.vern.antigravity.oauth

import id.vern.antigravity.data.CodeAssistTier
import id.vern.antigravity.data.TierInfo
import id.vern.antigravity.service.CloudCodeQuotaService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class AccountTierCacheTest {

    @Before
    fun setUp() {
        CloudCodeQuotaService.invalidateTierCache()
    }

    @Test
    fun updateTierInList_skipsUnchangedAndUpdatesChangedTier() {
        val accounts = mutableListOf(
            StoredAccount(
                id = "user@example.com",
                email = "user@example.com",
                name = "User",
                tokenJson = "{}",
                tier = "PRO"
            )
        )

        // Same tier (case-insensitive) -> no-op
        assertFalse(AccountManager.updateTierInList(accounts, "USER@example.com", "pro"))
        assertEquals("PRO", accounts[0].tier)

        // Different tier -> updated and returns true
        assertTrue(AccountManager.updateTierInList(accounts, "user@example.com", "ultra"))
        assertEquals("ULTRA", accounts[0].tier)

        // Unknown email -> returns false
        assertFalse(AccountManager.updateTierInList(accounts, "other@example.com", "PRO"))
    }

    @Test
    fun cloudCodeQuotaService_tierCacheRespectsTtlEmailAndInvalidation() {
        val tier = CodeAssistTier(
            currentTier = TierInfo(id = "pro", name = "Google AI Pro"),
            paidTier = TierInfo(id = "pro", name = "Google AI Pro")
        )
        val t0 = 1_000_000L

        CloudCodeQuotaService.putCachedTier("Test@Example.com", tier, nowMs = t0)

        // Hit within TTL (case-insensitive email)
        assertNotNull(CloudCodeQuotaService.getCachedTier("test@example.com", nowMs = t0 + 10_000L))

        // Miss for another email
        assertNull(CloudCodeQuotaService.getCachedTier("other@example.com", nowMs = t0 + 10_000L))

        // Miss after TTL expires
        assertNull(
            CloudCodeQuotaService.getCachedTier(
                "test@example.com",
                nowMs = t0 + CloudCodeQuotaService.TIER_CACHE_TTL_MS + 1L
            )
        )

        // Explicit invalidation clears cache immediately
        CloudCodeQuotaService.invalidateTierCache()
        assertNull(CloudCodeQuotaService.getCachedTier("test@example.com", nowMs = t0))
    }
}
