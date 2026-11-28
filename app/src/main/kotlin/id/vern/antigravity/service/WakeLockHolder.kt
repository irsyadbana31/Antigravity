package id.vern.antigravity.service

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Build
import android.os.PowerManager
import android.util.Log
import java.util.concurrent.ConcurrentHashMap

/**
 * Manages the primary Partial WakeLock, optional WifiLock (to keep Wi-Fi active
 * during screen-off streaming), and tagged WakeLocks acquired via
 * [id.vern.antigravity.gateway.SystemGatewayServer], with atomic timeout renewal
 * (no release gap) and automatic cleanup of expired locks.
 */
class WakeLockHolder(
    private val lockTag: String = LOCK_TAG
) {

    companion object {
        private const val TAG = "WakeLockHolder"
        const val LOCK_TAG = "id.vern.antigravity:wakelock"
        private const val WIFI_LOCK_TAG = "id.vern.antigravity:wifilock"
        private const val DEFAULT_TIMEOUT_MS = 10 * 60 * 1000L
        private const val MAX_TAGGED_TIMEOUT_MS = 60 * 60 * 1000L

        internal fun resolveLockTag(packageName: String, rawTag: String): String {
            if (packageName.isNotBlank() &&
                packageName != "id.vern.antigravity" &&
                rawTag.startsWith("id.vern.antigravity:")
            ) {
                return "$packageName:${rawTag.removePrefix("id.vern.antigravity:")}"
            }
            return rawTag
        }

        internal fun resolveWifiLockTag(packageName: String): String =
            if (packageName.isNotBlank()) "$packageName:wifilock" else WIFI_LOCK_TAG
    }

    private data class TaggedEntry(
        val lock: PowerManager.WakeLock,
        val wantsWifi: Boolean
    )

    @Volatile
    private var wakeLock: PowerManager.WakeLock? = null
    @Volatile
    private var effectiveLockTag: String = lockTag
    @Volatile
    private var primaryWantsWifi: Boolean = false
    @Volatile
    private var wifiLock: WifiManager.WifiLock? = null
    private val taggedLocks = ConcurrentHashMap<String, TaggedEntry>()

    @Synchronized
    fun acquire(
        context: Context,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
        withWifiLock: Boolean = false
    ) {
        cleanupExpiredLocked()
        val safeTimeout = timeoutMs.coerceAtLeast(1_000L)
        try {
            val appCtx = context.applicationContext
            val resolvedTag = resolveLockTag(appCtx.packageName, lockTag)
            effectiveLockTag = resolvedTag
            val pm = appCtx.getSystemService(Context.POWER_SERVICE) as? PowerManager
            if (pm != null) {
                if (wakeLock == null) {
                    wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, resolvedTag).apply {
                        setReferenceCounted(false)
                    }
                }
                val wasHeld = wakeLock?.isHeld == true
                // Non-reference-counted acquire(timeout) atomically extends the timeout
                // without dropping the lock in between.
                wakeLock?.acquire(safeTimeout)
                if (!wasHeld) {
                    Log.i(TAG, "Partial WakeLock acquired [$resolvedTag] (timeout=${safeTimeout}ms, wifi=$withWifiLock)")
                }
            }
            primaryWantsWifi = withWifiLock
            syncWifiLockLocked(appCtx)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to acquire WakeLock: ${e.message}")
        }
    }

    @Synchronized
    fun release() {
        try {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
                Log.i(TAG, "Partial WakeLock released [$effectiveLockTag]")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to release WakeLock: ${e.message}")
        } finally {
            wakeLock = null
            primaryWantsWifi = false
            syncWifiLockLocked(null)
        }
    }

    @Synchronized
    fun acquireTagged(
        context: Context,
        tag: String,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
        withWifiLock: Boolean = true
    ): Boolean {
        cleanupExpiredLocked()
        val safeTag = tag.ifBlank { "agy_task" }.take(48)
        val clampedTimeout = if (timeoutMs <= 0L) DEFAULT_TIMEOUT_MS else timeoutMs.coerceIn(1_000L, MAX_TAGGED_TIMEOUT_MS)
        return try {
            val appCtx = context.applicationContext
            val resolvedBaseTag = resolveLockTag(appCtx.packageName, lockTag)
            val pm = appCtx.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return false
            val existing = taggedLocks[safeTag]
            val lock = existing?.lock ?: pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$resolvedBaseTag:$safeTag").apply {
                setReferenceCounted(false)
            }
            // Atomic renew without release gap
            lock.acquire(clampedTimeout)
            val held = lock.isHeld
            if (held) {
                taggedLocks[safeTag] = TaggedEntry(lock, withWifiLock)
            } else {
                taggedLocks.remove(safeTag)
            }
            syncWifiLockLocked(appCtx)
            held
        } catch (e: Exception) {
            Log.w(TAG, "Failed to acquire tagged WakeLock ($safeTag): ${e.message}")
            false
        }
    }

    @Synchronized
    fun releaseTagged(tag: String): Boolean {
        cleanupExpiredLocked()
        val safeTag = tag.ifBlank { "agy_task" }.take(48)
        val entry = taggedLocks.remove(safeTag)
        if (entry != null) {
            try {
                if (entry.lock.isHeld) {
                    entry.lock.release()
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to release tagged WakeLock ($safeTag): ${e.message}")
            }
        }
        syncWifiLockLocked(null)
        return entry != null
    }

    @Synchronized
    fun isTaggedHeld(tag: String): Boolean {
        cleanupExpiredLocked()
        val safeTag = tag.ifBlank { "agy_task" }.take(48)
        val entry = taggedLocks[safeTag]
        val held = entry != null && entry.lock.isHeld
        if (entry != null && !held) {
            taggedLocks.remove(safeTag, entry)
        }
        return held
    }

    @Synchronized
    fun cleanupExpired() {
        cleanupExpiredLocked()
    }

    private fun cleanupExpiredLocked() {
        var removedAny = false
        val it = taggedLocks.entries.iterator()
        while (it.hasNext()) {
            val entry = it.next()
            if (!entry.value.lock.isHeld) {
                it.remove()
                removedAny = true
            }
        }
        if (removedAny) {
            syncWifiLockLocked(null)
        }
    }

    private fun syncWifiLockLocked(appContext: Context?) {
        val shouldHoldWifi = (primaryWantsWifi && wakeLock?.isHeld == true) ||
            taggedLocks.values.any { it.wantsWifi && it.lock.isHeld }
        if (shouldHoldWifi) {
            if (wifiLock?.isHeld == true) return
            val ctx = appContext ?: return
            try {
                val wm = ctx.getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return
                val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    WifiManager.WIFI_MODE_FULL_LOW_LATENCY
                } else {
                    @Suppress("DEPRECATION")
                    WifiManager.WIFI_MODE_FULL_HIGH_PERF
                }
                if (wifiLock == null) {
                    wifiLock = wm.createWifiLock(mode, resolveWifiLockTag(ctx.packageName)).apply {
                        setReferenceCounted(false)
                    }
                }
                if (wifiLock?.isHeld != true) {
                    wifiLock?.acquire()
                    Log.i(TAG, "WifiLock acquired (mode=$mode)")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to acquire WifiLock: ${e.message}")
            }
        } else {
            releaseWifiLockLocked()
        }
    }

    private fun releaseWifiLockLocked() {
        try {
            if (wifiLock?.isHeld == true) {
                wifiLock?.release()
                Log.i(TAG, "WifiLock released")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to release WifiLock: ${e.message}")
        } finally {
            wifiLock = null
        }
    }

    @Synchronized
    fun releaseAll() {
        release()
        for (key in taggedLocks.keys.toList()) {
            val entry = taggedLocks.remove(key)
            try {
                if (entry?.lock?.isHeld == true) {
                    entry.lock.release()
                }
            } catch (_: Exception) {}
        }
        taggedLocks.clear()
        releaseWifiLockLocked()
    }

    val isPrimaryHeld: Boolean
        @Synchronized get() = wakeLock?.isHeld == true

    val isWifiLockHeld: Boolean
        @Synchronized get() = wifiLock?.isHeld == true

    val activeTaggedCount: Int
        @Synchronized get() {
            cleanupExpiredLocked()
            return taggedLocks.size
        }

    val isHeld: Boolean
        @Synchronized get() {
            cleanupExpiredLocked()
            return wakeLock?.isHeld == true || taggedLocks.values.any { it.lock.isHeld }
        }
}
