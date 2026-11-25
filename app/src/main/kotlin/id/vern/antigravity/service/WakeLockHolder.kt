package id.vern.antigravity.service

import android.content.Context
import android.os.PowerManager
import android.util.Log
import java.util.concurrent.ConcurrentHashMap

/**
 * Manages the primary service Partial WakeLock plus optional tagged WakeLocks
 * acquired via [id.vern.antigravity.gateway.SystemGatewayServer], with automatic
 * cleanup of expired/unheld locks to prevent memory leaks.
 */
class WakeLockHolder {

    companion object {
        private const val TAG = "WakeLockHolder"
        private const val LOCK_TAG = "id.vern.antigravity:wakelock"
        private const val DEFAULT_TIMEOUT_MS = 10 * 60 * 1000L
        private const val MAX_TAGGED_TIMEOUT_MS = 60 * 60 * 1000L
    }

    private var wakeLock: PowerManager.WakeLock? = null
    private val taggedLocks = ConcurrentHashMap<String, PowerManager.WakeLock>()

    @Synchronized
    fun acquire(context: Context, timeoutMs: Long = DEFAULT_TIMEOUT_MS) {
        cleanupExpired()
        try {
            val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
            if (pm != null) {
                if (wakeLock == null) {
                    wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, LOCK_TAG).apply {
                        setReferenceCounted(false)
                    }
                }
                wakeLock?.let {
                    if (it.isHeld) it.release()
                    it.acquire(timeoutMs)
                }
                Log.i(TAG, "Partial WakeLock acquired (timeout=${timeoutMs}ms)")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to acquire WakeLock: ${e.message}")
        }
    }

    @Synchronized
    fun release() {
        try {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
                Log.i(TAG, "Partial WakeLock released")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to release WakeLock: ${e.message}")
        } finally {
            wakeLock = null
        }
    }

    fun acquireTagged(context: Context, tag: String, timeoutMs: Long = DEFAULT_TIMEOUT_MS): Boolean {
        cleanupExpired()
        val safeTag = tag.ifBlank { "agy_task" }.take(48)
        val clampedTimeout = if (timeoutMs <= 0L) DEFAULT_TIMEOUT_MS else timeoutMs.coerceIn(1_000L, MAX_TAGGED_TIMEOUT_MS)
        return try {
            val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return false
            val lock = taggedLocks.compute(safeTag) { key, existing ->
                if (existing != null && existing.isHeld) {
                    existing
                } else {
                    pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$LOCK_TAG:$key").apply {
                        setReferenceCounted(false)
                    }
                }
            } ?: return false
            if (lock.isHeld) {
                lock.release()
            }
            lock.acquire(clampedTimeout)
            val held = lock.isHeld
            if (!held) {
                taggedLocks.remove(safeTag, lock)
            }
            held
        } catch (e: Exception) {
            Log.w(TAG, "Failed to acquire tagged WakeLock ($safeTag): ${e.message}")
            false
        }
    }

    fun releaseTagged(tag: String): Boolean {
        cleanupExpired()
        val safeTag = tag.ifBlank { "agy_task" }.take(48)
        val lock = taggedLocks.remove(safeTag)
        if (lock != null) {
            try {
                if (lock.isHeld) {
                    lock.release()
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to release tagged WakeLock ($safeTag): ${e.message}")
            }
        }
        return false
    }

    fun isTaggedHeld(tag: String): Boolean {
        cleanupExpired()
        val safeTag = tag.ifBlank { "agy_task" }.take(48)
        val lock = taggedLocks[safeTag]
        val held = lock != null && lock.isHeld
        if (lock != null && !held) {
            taggedLocks.remove(safeTag, lock)
        }
        return held
    }

    fun cleanupExpired() {
        val it = taggedLocks.entries.iterator()
        while (it.hasNext()) {
            val entry = it.next()
            val lock = entry.value
            if (!lock.isHeld) {
                it.remove()
            }
        }
    }

    @Synchronized
    fun releaseAll() {
        release()
        for (key in taggedLocks.keys.toList()) {
            val lock = taggedLocks.remove(key)
            try {
                if (lock?.isHeld == true) {
                    lock.release()
                }
            } catch (_: Exception) {}
        }
        taggedLocks.clear()
    }

    val isHeld: Boolean
        get() {
            cleanupExpired()
            return wakeLock?.isHeld == true || taggedLocks.values.any { it.isHeld }
        }
}
