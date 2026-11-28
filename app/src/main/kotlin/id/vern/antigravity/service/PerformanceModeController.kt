package id.vern.antigravity.service

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.os.Process
import android.system.Os
import android.util.Log
import id.vern.antigravity.executor.AcpSessionPool
import id.vern.antigravity.gateway.ShizukuConfig
import id.vern.antigravity.gateway.ShizukuManager
import id.vern.antigravity.nativebridge.CpuTopologyInfo
import id.vern.antigravity.nativebridge.NativeEngineStatsSnapshot
import id.vern.antigravity.nativebridge.NativeProcessStats
import id.vern.antigravity.nativebridge.RustTurboBridge
import id.vern.antigravity.util.AppLogger
import id.vern.antigravity.util.OemBackgroundProfile
import id.vern.antigravity.util.PermissionHelper
import id.vern.antigravity.viewmodel.AntigravityViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

data class ShizukuTuningReadback(
    val shizukuAvailable: Boolean = false,
    val phantomProcsDisabled: Boolean? = null,
    val maxPhantomProcesses: Int? = null,
    val dozeWhitelisted: Boolean? = null,
    val runAnyInBackgroundAllowed: Boolean? = null,
    val standbyBucket: Int? = null,
    val appliedAtMs: Long = 0L,
    val errorSummary: String? = null
) {
    val phantomOk: Boolean
        get() = phantomProcsDisabled == true || (maxPhantomProcesses != null && maxPhantomProcesses >= 1000)

    val standbyActive: Boolean
        get() = standbyBucket != null && standbyBucket <= 10

    val satisfiedCount: Int
        get() = listOf(
            phantomOk,
            dozeWhitelisted == true,
            runAnyInBackgroundAllowed == true,
            standbyActive
        ).count { it }
}

data class ThermalGuardSnapshot(
    val batteryTempC: Float? = null,
    val thermalStatus: Int = 0,
    val isCoolingDown: Boolean = false
)

data class PerformanceStatusSnapshot(
    val enabled: Boolean = false,
    val effectiveActive: Boolean = false,
    val cpuTopology: CpuTopologyInfo = CpuTopologyInfo(),
    val acpPid: Int? = null,
    val acpUptimeSec: Long = 0L,
    val acpStats: NativeProcessStats? = null,
    val acpPinnedThreads: Int = 0,
    val daemonPid: Int? = null,
    val daemonStats: NativeProcessStats? = null,
    val daemonPinnedThreads: Int = 0,
    val affinityDeferredByCpuset: Boolean = false,
    val shizukuTuning: ShizukuTuningReadback = ShizukuTuningReadback(),
    val thermal: ThermalGuardSnapshot = ThermalGuardSnapshot(),
    val nativeEngineStats: NativeEngineStatsSnapshot = RustTurboBridge.statsSnapshot(),
    val oemProfile: OemBackgroundProfile = PermissionHelper.detectOemBackgroundProfile()
)

/**
 * Coordinates opt-in Performance Mode (default OFF, root-free):
 * - Always-warm ACP daemon (skips 6m idle reap while active and thermally safe)
 * - Safe slice-renewed WakeLock + WifiLock via CoreServerService
 * - Shizuku system tuning using dynamic `context.packageName` with real readback verification
 * - Multi-thread big-core cluster CPU pinning via Rust JNI (`freq > min_freq`) for `agy acp` and `agy-daemon`
 * - Dual thermal guard (battery >= 43.0°C / hysteresis 39.5°C, or Android Thermal HAL >= SEVERE)
 */
object PerformanceModeController {
    private const val TAG = "PerformanceMode"
    const val KEY_PERFORMANCE_MODE = "ag_performance_mode"

    const val BATTERY_THERMAL_TRIGGER_C = 43.0f
    const val BATTERY_THERMAL_RESUME_C = 39.5f
    const val THERMAL_STATUS_SEVERE_LEVEL = 3 // PowerManager.THERMAL_STATUS_SEVERE

    private val pkgRegex = Regex("^[a-zA-Z0-9._]+$")

    @Volatile
    private var cachedTopology: CpuTopologyInfo? = null

    @Volatile
    private var isCoolingDown: Boolean = false

    @Volatile
    private var lastAcpPinnedThreads: Int = 0

    @Volatile
    private var lastDaemonPinnedThreads: Int = 0

    @Volatile
    private var lastAffinityDeferredByCpuset: Boolean = false

    @Volatile
    private var lastShizukuReadback: ShizukuTuningReadback = ShizukuTuningReadback()

    @Volatile
    private var enabledCached: Boolean = false

    @Volatile
    private var appContextRef: Context? = null

    private val mutableStatus = MutableStateFlow(PerformanceStatusSnapshot())
    val status: StateFlow<PerformanceStatusSnapshot> = mutableStatus.asStateFlow()

    init {
        AcpSessionPool.onWarmProcessReady = { pid ->
            onWarmAcpProcessSpawned(pid)
        }
    }

    fun isEnabled(context: Context): Boolean {
        val appCtx = context.applicationContext
        appContextRef = appCtx
        return try {
            val enabled = appCtx
                .getSharedPreferences(AntigravityViewModel.PREFS_NAME, Context.MODE_PRIVATE)
                .getBoolean(KEY_PERFORMANCE_MODE, false)
            enabledCached = enabled
            enabled
        } catch (_: Exception) {
            false
        }
    }

    fun isEffectivelyActive(context: Context): Boolean = isEnabled(context) && !isCoolingDown

    fun isThermallyCoolingDown(): Boolean = isCoolingDown

    fun getCpuTopology(): CpuTopologyInfo {
        cachedTopology?.let { if (it.totalCpus > 0) return it }
        val detected = RustTurboBridge.detectCpuTopology()
        if (detected.totalCpus > 0) {
            cachedTopology = detected
        }
        return detected
    }

    fun setEnabled(context: Context, enabled: Boolean): PerformanceStatusSnapshot {
        val appCtx = context.applicationContext
        appContextRef = appCtx
        enabledCached = enabled
        appCtx.getSharedPreferences(AntigravityViewModel.PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_PERFORMANCE_MODE, enabled)
            .apply()

        if (!enabled) {
            isCoolingDown = false
            AcpSessionPool.setKeepWarmIndefinitely(false)
            unpinManagedProcesses(appCtx)
            CoreServerService.setPerformanceMode(appCtx, active = false)
            AppLogger.d(TAG, "Performance Mode disabled; restored normal idle reap & CPU affinity")
            return refreshStatus(appCtx, includeShizukuReadback = false)
        }

        val thermal = evaluateAndUpdateThermal(appCtx)
        val effective = !thermal.isCoolingDown
        AcpSessionPool.setKeepWarmIndefinitely(effective)
        CoreServerService.setPerformanceMode(appCtx, active = effective)

        if (effective) {
            applyBigCoreAffinity(appCtx)
        } else {
            unpinManagedProcesses(appCtx)
        }

        applyShizukuTuningAndReadback(appCtx)
        AppLogger.d(
            TAG,
            "Performance Mode enabled (effective=$effective, bigCores=${getCpuTopology().formatClusterSummary()}, shizuku=${lastShizukuReadback.shizukuAvailable})"
        )
        return refreshStatus(appCtx, includeShizukuReadback = false)
    }

    /**
     * Called on app startup, foreground resume, or BOOT_COMPLETED to re-apply settings
     * that reset across reboots or when Android moves the app back to top-app cpuset.
     */
    fun syncOnStartupOrResume(
        context: Context,
        reapplyShizuku: Boolean = false
    ): PerformanceStatusSnapshot {
        val appCtx = context.applicationContext
        val enabled = isEnabled(appCtx)
        if (!enabled) {
            AcpSessionPool.setKeepWarmIndefinitely(false)
            return refreshStatus(appCtx, includeShizukuReadback = false)
        }

        val thermal = evaluateAndUpdateThermal(appCtx)
        val effective = !thermal.isCoolingDown
        AcpSessionPool.setKeepWarmIndefinitely(effective)
        CoreServerService.setPerformanceMode(appCtx, active = effective)

        if (effective) {
            applyBigCoreAffinity(appCtx)
        } else {
            unpinManagedProcesses(appCtx)
        }

        if (reapplyShizuku) {
            applyShizukuTuningAndReadback(appCtx)
        }

        return refreshStatus(appCtx, includeShizukuReadback = false)
    }

    /**
     * Periodic thermal guard tick called by CoreServerService while Performance Mode is enabled.
     * Returns true if cooling state transitioned.
     */
    fun tickThermalGuard(context: Context): Boolean {
        val appCtx = context.applicationContext
        if (!isEnabled(appCtx)) {
            if (isCoolingDown) {
                isCoolingDown = false
                refreshStatus(appCtx, includeShizukuReadback = false)
            }
            return false
        }

        val wasCooling = isCoolingDown
        val thermal = evaluateAndUpdateThermal(appCtx)
        if (thermal.isCoolingDown != wasCooling) {
            val effective = !thermal.isCoolingDown
            AcpSessionPool.setKeepWarmIndefinitely(effective)
            CoreServerService.setPerformanceMode(appCtx, active = effective)
            if (effective) {
                applyBigCoreAffinity(appCtx)
                AppLogger.d(
                    TAG,
                    "Thermal guard recovered (${thermal.batteryTempC ?: -1f}°C); re-enabled Performance Mode pinning & keep-alive"
                )
            } else {
                unpinManagedProcesses(appCtx)
                AppLogger.e(
                    TAG,
                    "Thermal guard triggered (battery=${thermal.batteryTempC ?: -1f}°C, status=${thermal.thermalStatus}); unpinned CPUs & released continuous keep-alive"
                )
            }
            refreshStatus(appCtx, includeShizukuReadback = false)
            return true
        }
        return false
    }

    fun refreshStatus(
        context: Context,
        includeShizukuReadback: Boolean = false
    ): PerformanceStatusSnapshot {
        val appCtx = context.applicationContext
        val enabled = isEnabled(appCtx)
        val thermal = readThermalSnapshot(appCtx)
        val topology = getCpuTopology()

        if (includeShizukuReadback) {
            readbackShizukuTuning(appCtx)
        }

        val acpPid = AcpSessionPool.getWarmDaemonPid()
        val acpUptimeSec = AcpSessionPool.getWarmDaemonUptimeMs() / 1000L
        val acpStats = acpPid?.let { RustTurboBridge.readProcessStats(it) }

        val daemonPid = readDaemonPid(appCtx.filesDir)
        val daemonStats = daemonPid?.let { RustTurboBridge.readProcessStats(it) }

        val snap = PerformanceStatusSnapshot(
            enabled = enabled,
            effectiveActive = enabled && !thermal.isCoolingDown,
            cpuTopology = topology,
            acpPid = acpPid,
            acpUptimeSec = acpUptimeSec,
            acpStats = acpStats,
            acpPinnedThreads = if (acpPid != null && enabled && !thermal.isCoolingDown) lastAcpPinnedThreads else 0,
            daemonPid = daemonPid,
            daemonStats = daemonStats,
            daemonPinnedThreads = if (daemonPid != null && enabled && !thermal.isCoolingDown) lastDaemonPinnedThreads else 0,
            affinityDeferredByCpuset = lastAffinityDeferredByCpuset,
            shizukuTuning = lastShizukuReadback,
            thermal = thermal,
            nativeEngineStats = RustTurboBridge.statsSnapshot(),
            oemProfile = PermissionHelper.detectOemBackgroundProfile()
        )
        mutableStatus.value = snap
        return snap
    }

    fun applyShizukuTuningAndReadback(context: Context): ShizukuTuningReadback {
        val appCtx = context.applicationContext
        val pkg = appCtx.packageName
        if (!isSafePackageName(pkg)) {
            val bad = ShizukuTuningReadback(shizukuAvailable = false, errorSummary = "Invalid package name")
            lastShizukuReadback = bad
            return bad
        }

        val shizukuCfg = ShizukuConfig.load(appCtx)
        if (!shizukuCfg.enabled) {
            val off = ShizukuTuningReadback(shizukuAvailable = false, errorSummary = "Shizuku disabled")
            lastShizukuReadback = off
            return off
        }

        val shizukuSnap = ShizukuManager.inspectStatus(appCtx)
        if (!shizukuSnap.isReady) {
            val unavail = ShizukuTuningReadback(
                shizukuAvailable = false,
                errorSummary = "Shizuku ${shizukuSnap.state.name.lowercase()}"
            )
            lastShizukuReadback = unavail
            return unavail
        }

        val errors = mutableListOf<String>()
        for ((label, cmd) in buildShizukuApplyCommands(pkg)) {
            val res = ShizukuManager.executePrivileged(appCtx, cmd, timeoutMs = 8_000L)
            if (!res.ok) {
                val detail = res.error ?: res.stderr.trim().ifBlank { "exit=${res.exitCode}" }
                errors.add("$label: ${detail.take(80)}")
                Log.w(TAG, "Shizuku tune '$label' failed: $detail")
            }
        }

        val readback = readbackShizukuTuning(appCtx)
        val merged = readback.copy(
            appliedAtMs = System.currentTimeMillis(),
            errorSummary = errors.takeIf { it.isNotEmpty() }?.joinToString("; ")
        )
        lastShizukuReadback = merged
        return merged
    }

    fun readbackShizukuTuning(context: Context): ShizukuTuningReadback {
        val appCtx = context.applicationContext
        val pkg = appCtx.packageName
        if (!isSafePackageName(pkg)) {
            return ShizukuTuningReadback(shizukuAvailable = false)
        }
        val shizukuCfg = ShizukuConfig.load(appCtx)
        if (!shizukuCfg.enabled || !ShizukuManager.inspectStatus(appCtx).isReady) {
            val unavail = ShizukuTuningReadback(shizukuAvailable = false)
            lastShizukuReadback = unavail
            return unavail
        }
        val res = ShizukuManager.executePrivileged(
            appCtx,
            buildShizukuReadbackCommand(pkg),
            timeoutMs = 8_000L
        )
        val parsed = if (res.ok || res.stdout.isNotBlank()) {
            parseShizukuReadback(res.stdout, pkg).copy(
                shizukuAvailable = true,
                appliedAtMs = lastShizukuReadback.appliedAtMs
            )
        } else {
            ShizukuTuningReadback(
                shizukuAvailable = false,
                errorSummary = res.error ?: res.stderr.trim().ifBlank { "readback failed" }
            )
        }
        lastShizukuReadback = parsed
        return parsed
    }

    fun onShizukuBinderReady(context: Context) {
        val appCtx = context.applicationContext
        if (!isEnabled(appCtx)) return
        if (lastShizukuReadback.appliedAtMs == 0L) {
            applyShizukuTuningAndReadback(appCtx)
            refreshStatus(appCtx, includeShizukuReadback = false)
        }
    }

    private fun onWarmAcpProcessSpawned(pid: Int) {
        if (pid <= 0) return
        val topo = getCpuTopology()
        if (!topo.hasBigCluster) return
        val isEnabledNow = enabledCached || (appContextRef?.let { isEnabled(it) } == true)
        if (isEnabledNow && !isCoolingDown) {
            val count = RustTurboBridge.setProcessAffinity(pid, topo.bigCoresMask)
            lastAcpPinnedThreads = count.coerceAtLeast(0)
            lastAffinityDeferredByCpuset = (count == 0)
            Log.i(TAG, "Pinned warm agy acp (pid=$pid) to ${topo.formatClusterSummary()} (threads=$count)")
        }
    }

    fun applyBigCoreAffinity(context: Context) {
        val topo = getCpuTopology()
        if (!topo.hasBigCluster) {
            lastAcpPinnedThreads = 0
            lastDaemonPinnedThreads = 0
            lastAffinityDeferredByCpuset = false
            return
        }
        var deferred = false
        val acpPid = AcpSessionPool.getWarmDaemonPid()
        if (acpPid != null && acpPid > 0) {
            val count = RustTurboBridge.setProcessAffinity(acpPid, topo.bigCoresMask)
            lastAcpPinnedThreads = count.coerceAtLeast(0)
            if (count == 0) deferred = true
        } else {
            lastAcpPinnedThreads = 0
        }

        val daemonPid = readDaemonPid(context.applicationContext.filesDir)
        if (daemonPid != null && daemonPid > 0) {
            val count = RustTurboBridge.setProcessAffinity(daemonPid, topo.bigCoresMask)
            lastDaemonPinnedThreads = count.coerceAtLeast(0)
            if (count == 0) deferred = true
        } else {
            lastDaemonPinnedThreads = 0
        }
        lastAffinityDeferredByCpuset = deferred
    }

    fun unpinManagedProcesses(context: Context) {
        val topo = getCpuTopology()
        val allMask = topo.allCoresMask
        lastAcpPinnedThreads = 0
        lastDaemonPinnedThreads = 0
        lastAffinityDeferredByCpuset = false
        if (allMask <= 0L) return

        val handledPids = HashSet<Int>()
        AcpSessionPool.getWarmDaemonPid()?.let { pid ->
            if (pid > 0) {
                RustTurboBridge.setProcessAffinity(pid, allMask)
                handledPids.add(pid)
            }
        }
        readDaemonPid(context.applicationContext.filesDir)?.let { pid ->
            if (pid > 0) {
                RustTurboBridge.setProcessAffinity(pid, allMask)
                handledPids.add(pid)
            }
        }
        // Unpin any child processes (bash, MCP servers, subagents) of our UID that inherited big-core pinning
        try {
            val myPid = Process.myPid()
            val myUid = Process.myUid()
            val procEntries = File("/proc").listFiles() ?: emptyArray()
            for (entry in procEntries) {
                val pid = entry.name.toIntOrNull() ?: continue
                if (pid <= 0 || pid == myPid || handledPids.contains(pid)) continue
                val uid = runCatching { Os.stat(entry.absolutePath).st_uid }.getOrDefault(-1)
                if (uid != myUid) continue
                val curMask = RustTurboBridge.getProcessAffinity(pid)
                if (curMask > 0L && curMask != allMask) {
                    RustTurboBridge.setProcessAffinity(pid, allMask)
                }
            }
        } catch (_: Exception) {}
    }

    private fun evaluateAndUpdateThermal(context: Context): ThermalGuardSnapshot {
        val batteryTemp = readBatteryTemperatureC(context)
        val thermalStatus = readSystemThermalStatus(context)
        val nextCooling = evaluateThermalCoolingState(isCoolingDown, batteryTemp, thermalStatus)
        isCoolingDown = nextCooling
        return ThermalGuardSnapshot(
            batteryTempC = batteryTemp,
            thermalStatus = thermalStatus,
            isCoolingDown = nextCooling
        )
    }

    private fun readThermalSnapshot(context: Context): ThermalGuardSnapshot {
        val batteryTemp = readBatteryTemperatureC(context)
        val thermalStatus = readSystemThermalStatus(context)
        return ThermalGuardSnapshot(
            batteryTempC = batteryTemp,
            thermalStatus = thermalStatus,
            isCoolingDown = isCoolingDown
        )
    }

    private fun readBatteryTemperatureC(context: Context): Float? {
        return try {
            val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
                ?: return null
            val tenths = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
            if (tenths == Int.MIN_VALUE || tenths < -200 || tenths > 1200) null else tenths / 10.0f
        } catch (_: Exception) {
            null
        }
    }

    private fun readSystemThermalStatus(context: Context): Int {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
                pm?.currentThermalStatus ?: 0
            } else {
                0
            }
        } catch (_: Exception) {
            0
        }
    }

    fun readDaemonPid(filesDir: File): Int? {
        return try {
            val pidFile = File(filesDir, "home/.gemini/antigravity-app/daemon/daemon.pid")
            if (!pidFile.isFile) return null
            val pid = pidFile.readText(Charsets.UTF_8).trim().toIntOrNull() ?: return null
            if (pid <= 0) return null
            val cmdlineFile = File("/proc/$pid/cmdline")
            if (!cmdlineFile.isFile) return null
            val cmdline = cmdlineFile.readText(Charsets.UTF_8).replace('\u0000', ' ')
            if (CoreServerService.isDaemonCmdline(cmdline)) pid else null
        } catch (_: Exception) {
            null
        }
    }

    internal fun isSafePackageName(pkg: String): Boolean =
        pkg.isNotBlank() && pkg.length <= 128 && pkgRegex.matches(pkg)

    internal fun evaluateThermalCoolingState(
        currentlyCooling: Boolean,
        batteryTempC: Float?,
        thermalStatus: Int
    ): Boolean {
        val overTemp = (batteryTempC != null && batteryTempC >= BATTERY_THERMAL_TRIGGER_C) ||
            thermalStatus >= THERMAL_STATUS_SEVERE_LEVEL
        if (overTemp) return true
        if (!currentlyCooling) return false
        val cooledDown = (batteryTempC == null || batteryTempC <= BATTERY_THERMAL_RESUME_C) &&
            thermalStatus <= 1
        return !cooledDown
    }

    internal fun buildShizukuApplyCommands(packageName: String): List<Pair<String, String>> {
        require(isSafePackageName(packageName)) { "Unsafe packageName: $packageName" }
        return listOf(
            "phantom_monitor" to "settings put global settings_enable_monitor_phantom_procs false",
            "phantom_max" to "device_config set_sync_disabled_for_tests persistent; device_config put activity_manager max_phantom_processes 2147483647",
            "doze_whitelist" to "cmd deviceidle whitelist +$packageName",
            "appops_bg" to "cmd appops set $packageName RUN_ANY_IN_BACKGROUND allow",
            "standby_bucket" to "am set-standby-bucket $packageName active"
        )
    }

    internal fun buildShizukuReadbackCommand(packageName: String): String {
        require(isSafePackageName(packageName)) { "Unsafe packageName: $packageName" }
        return listOf(
            "echo '===PHANTOM_SETTING==='",
            "settings get global settings_enable_monitor_phantom_procs 2>/dev/null || true",
            "echo '===PHANTOM_MAX==='",
            "dumpsys activity settings 2>/dev/null | grep -i max_phantom_processes | head -n 2 || true",
            "echo '===DOZE==='",
            "(cmd deviceidle whitelist 2>/dev/null || dumpsys deviceidle whitelist 2>/dev/null) | grep -F ',$packageName,' || true",
            "echo '===APPOPS==='",
            "cmd appops get $packageName RUN_ANY_IN_BACKGROUND 2>/dev/null || true",
            "echo '===BUCKET==='",
            "am get-standby-bucket $packageName 2>/dev/null || true"
        ).joinToString("; ")
    }

    internal fun parseShizukuReadback(raw: String, packageName: String): ShizukuTuningReadback {
        val sections = mutableMapOf<String, StringBuilder>()
        var currentKey: String? = null
        for (line in raw.lines()) {
            val trimmed = line.trim()
            if (trimmed.startsWith("===") && trimmed.endsWith("===") && trimmed.length > 6) {
                currentKey = trimmed.removePrefix("===").removeSuffix("===").trim()
                sections.putIfAbsent(currentKey, StringBuilder())
            } else if (currentKey != null) {
                val sb = sections[currentKey]!!
                if (sb.isNotEmpty()) sb.append('\n')
                sb.append(line)
            }
        }

        val phantomSettingRaw = sections["PHANTOM_SETTING"]?.toString()?.trim()?.lowercase().orEmpty()
        val phantomDisabled = when (phantomSettingRaw) {
            "false", "0" -> true
            "true", "1" -> false
            else -> null
        }

        val phantomMaxRaw = sections["PHANTOM_MAX"]?.toString().orEmpty()
        val maxPhantomMatch = Regex("""max_phantom_processes\s*=\s*(\d+)""", RegexOption.IGNORE_CASE)
            .find(phantomMaxRaw)
        val maxPhantom = maxPhantomMatch?.groupValues?.getOrNull(1)?.toIntOrNull()

        val dozeRaw = sections["DOZE"]?.toString().orEmpty()
        val dozeWhitelisted = if (sections.containsKey("DOZE")) {
            dozeRaw.contains(",$packageName,") || dozeRaw.trim().endsWith(",$packageName")
        } else {
            null
        }

        val appopsRaw = sections["APPOPS"]?.toString()?.lowercase().orEmpty()
        val runAnyAllowed = when {
            appopsRaw.contains("run_any_in_background: allow") -> true
            appopsRaw.contains("run_any_in_background: ignore") || appopsRaw.contains("run_any_in_background: deny") -> false
            appopsRaw.contains("default mode: allow") && !appopsRaw.contains("ignore") && !appopsRaw.contains("deny") -> true
            else -> null
        }

        val bucketRaw = sections["BUCKET"]?.toString()?.trim().orEmpty()
        val bucketVal = Regex("""\b(\d+)\b""").find(bucketRaw)?.groupValues?.getOrNull(1)?.toIntOrNull()

        return ShizukuTuningReadback(
            shizukuAvailable = true,
            phantomProcsDisabled = phantomDisabled,
            maxPhantomProcesses = maxPhantom,
            dozeWhitelisted = dozeWhitelisted,
            runAnyInBackgroundAllowed = runAnyAllowed,
            standbyBucket = bucketVal
        )
    }
}
