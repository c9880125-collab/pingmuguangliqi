package com.refreshrate.control.util

import android.os.SystemClock
import android.util.Log
import com.refreshrate.control.model.DisplayMode
import java.io.BufferedReader
import java.io.DataOutputStream
import java.io.InputStreamReader
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantLock
import kotlin.math.abs
import kotlin.math.roundToInt

object RootUtils {
    private const val TAG = "RootUtils"
    private val RECORD_PATTERN = Regex("""id=(\d+),\s*width=(\d+),\s*height=(\d+),\s*fps=([\d.]+)""")
    private val NUMBER_PATTERN = Regex("""-?\d+(?:\.\d+)?""")

    // Strict transition engine: one request at a time, newest request cancels the older one.
    private const val STEP_MAX_ATTEMPTS = 4
    private const val STEP_POLL_MS = 220L
    private const val STEP_APPLY_TIMEOUT_MS = 3_200L
    private const val STEP_STABLE_SAMPLES = 2
    private const val STEP_SETTLE_MS = 650L
    private const val FINAL_STABLE_MS = 900L
    private val transitionLock = ReentrantLock(true)
    private val transitionGeneration = AtomicLong(0)

    data class RootCommandResult(
        val ok: Boolean,
        val exitCode: Int,
        val stdout: String,
        val stderr: String,
        val label: String
    )

    data class DisplayState(
        val peakHz: Int?,
        val minHz: Int?,
        val userHz: Int?,
        val miuiHz: Int?,
        val preferredHz: Int?,
        val activeModeId: Int?,
        val activeWidth: Int?,
        val activeHeight: Int?,
        val activeHz: Int?,
        val physicalHz: Int?,
        val driverHz: Int?,
        val renderedFps: Int?,
        val raw: String
    ) {
        fun hasRefreshEvidence(): Boolean {
            return driverHz != null || physicalHz != null || activeHz != null || preferredHz != null || userHz != null ||
                peakHz != null || minHz != null || miuiHz != null
        }

        fun hasHighRateContradiction(targetHz: Int): Boolean {
            val tolerance = maxOf(6, targetHz / 20)
            return renderedFps != null && renderedFps > targetHz + tolerance
        }

        fun matchesTarget(targetHz: Int): Boolean {
            if (hasHighRateContradiction(targetHz)) return false
            if (activeHz != null) return hzMatches(activeHz, targetHz)
            if (driverHz != null) return hzMatches(driverHz, targetHz)
            if (physicalHz != null) return hzMatches(physicalHz, targetHz)
            if (preferredHz != null) return hzMatches(preferredHz, targetHz)
            val settings = listOfNotNull(userHz, peakHz, minHz, miuiHz)
            return settings.isNotEmpty() && settings.all { hzMatches(it, targetHz) }
        }

        /**
         * The selected display mode is more reliable than instantaneous panel/rendered FPS
         * on LTPO devices. Settings values are intentionally not accepted as proof that a
         * transition really happened.
         */
        fun selectedHz(): Int? = activeHz ?: driverHz ?: physicalHz

        fun matchesAppliedMode(mode: DisplayMode): Boolean {
            if (activeModeId != null && mode.modeId > 0 && activeModeId == mode.modeId) return true
            if (activeHz != null) return hzMatches(activeHz, mode.rateInt)
            if (driverHz != null) return hzMatches(driverHz, mode.rateInt)
            if (physicalHz != null) return hzMatches(physicalHz, mode.rateInt)
            return false
        }

        fun matchesAppliedHz(targetHz: Int): Boolean {
            if (activeHz != null) return hzMatches(activeHz, targetHz)
            if (driverHz != null) return hzMatches(driverHz, targetHz)
            if (physicalHz != null) return hzMatches(physicalHz, targetHz)
            return false
        }

        fun summary(): String {
            val activeRes = if (activeWidth != null && activeHeight != null) {
                "${activeWidth}x${activeHeight}"
            } else {
                "?"
            }
            return "driver=${driverHz ?: "?"}Hz rendered=${renderedFps ?: "?"}fps " +
                "physical=${physicalHz ?: "?"}Hz active=${activeHz ?: "?"}Hz " +
                "res=$activeRes modeId=${activeModeId ?: "?"} " +
                "preferred=${preferredHz ?: "?"} peak=${peakHz ?: "?"} min=${minHz ?: "?"} " +
                "user=${userHz ?: "?"} miui=${miuiHz ?: "?"}"
        }
    }

    fun isRootAvailable(): Boolean {
        return try {
            val process = Runtime.getRuntime().exec("su")
            val stdin = DataOutputStream(process.outputStream)
            stdin.writeBytes("echo RootOK\nexit\n")
            stdin.flush()
            process.waitFor()
            val out = BufferedReader(InputStreamReader(process.inputStream)).readText().trim()
            "RootOK" == out
        } catch (e: Exception) {
            false
        }
    }

    fun execRoot(script: String): Boolean {
        return execRootDetailed(script).ok
    }

    fun execRootDetailed(script: String, label: String = firstCommand(script)): RootCommandResult {
        return try {
            val process = Runtime.getRuntime().exec("su")
            val stdin = DataOutputStream(process.outputStream)
            stdin.writeBytes("$script\nexit\n")
            stdin.flush()
            val exitCode = process.waitFor()
            val out = BufferedReader(InputStreamReader(process.inputStream)).readText().trim()
            val err = BufferedReader(InputStreamReader(process.errorStream)).readText().trim()
            val result = RootCommandResult(exitCode == 0, exitCode, out, err, label)
            logRootResult(result)
            result
        } catch (e: Exception) {
            Log.e(TAG, "execRoot failed: ${e.message}")
            RuntimeLog.appendGlobal(TAG, "ROOT exception label=$label error=${e.message}")
            RootCommandResult(false, -1, "", e.message ?: "unknown", label)
        }
    }

    fun execRootForOutput(script: String, log: Boolean = false, label: String = firstCommand(script)): String {
        return try {
            val process = Runtime.getRuntime().exec("su")
            val stdin = DataOutputStream(process.outputStream)
            stdin.writeBytes("$script\nexit\n")
            stdin.flush()
            val exitCode = process.waitFor()
            val out = BufferedReader(InputStreamReader(process.inputStream)).readText().trim()
            val err = BufferedReader(InputStreamReader(process.errorStream)).readText().trim()
            if (log || exitCode != 0 || err.isNotBlank()) {
                logRootResult(RootCommandResult(exitCode == 0, exitCode, out, err, label))
            }
            out
        } catch (e: Exception) {
            Log.e(TAG, "execRootForOutput failed: ${e.message}")
            RuntimeLog.appendGlobal(TAG, "ROOT output exception label=$label error=${e.message}")
            ""
        }
    }

    fun scanModesFromDumpsys(): List<DisplayMode> {
        val output = execRootForOutput("dumpsys display | grep 'DisplayModeRecord'", label = "scanModes")
        if (output.isBlank()) return emptyList()

        val modes = mutableListOf<DisplayMode>()
        for (line in output.lines()) {
            val match = RECORD_PATTERN.find(line) ?: continue
            val id = match.groupValues[1].toIntOrNull() ?: continue
            val w = match.groupValues[2].toIntOrNull() ?: continue
            val h = match.groupValues[3].toIntOrNull() ?: continue
            val fps = match.groupValues[4].toFloatOrNull()?.roundToInt() ?: continue
            if (fps in 30..300) {
                val dm = DisplayMode(w, h, fps.toFloat(), id)
                dm.sfIndex = id - 1
                modes.add(dm)
            }
        }
        return modes.sortedBy { it.rateInt }
    }

    fun setRate(mode: DisplayMode?, targetHz: Int): Boolean {
        return if (mode != null) {
            requestDisplayMode(mode, increasing = true, useSurfaceFlingerFallback = false, resetPreferred = false).ok
        } else {
            applyRateSettings(targetHz, increasing = true).ok
        }
    }

    fun setRateDown(mode: DisplayMode?, targetHz: Int): Boolean {
        return if (mode != null) {
            requestDisplayMode(mode, increasing = false, useSurfaceFlingerFallback = false, resetPreferred = false).ok
        } else {
            applyRateSettings(targetHz, increasing = false).ok
        }
    }

    fun setPreferredMode(width: Int, height: Int, hz: Int): Boolean {
        if (width <= 0 || height <= 0 || hz <= 0) return false
        RuntimeLog.appendGlobal(TAG, "SWITCH setPreferredMode ${width}x$height@${hz}Hz")
        val script = preferredModeCommand(width, height, hz)
        return execRootDetailed(script, "setPreferred:${width}x$height@${hz}Hz").ok
    }

    fun setDisplayMode(width: Int, height: Int, hz: Int, sfIndex: Int): Boolean {
        val mode = DisplayMode(width, height, hz.toFloat(), sfIndex + 1).apply {
            this.sfIndex = sfIndex
        }
        return requestDisplayMode(
            mode = mode,
            increasing = true,
            useSurfaceFlingerFallback = sfIndex >= 0,
            resetPreferred = false
        ).ok
    }

    fun readDisplayState(): DisplayState {

        val output = execRootForOutput(displayStateScript(), label = "displayState")
        return parseDisplayState(output)
    }

    fun readDisplaySnapshot(): String {
        return execRootForOutput(displayStateScript(), label = "displaySnapshot").lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .joinToString(" | ")
            .take(1800)
    }

    fun getTopPackageFromWindow(): String? {
        val output = execRootForOutput(
            "dumpsys window 2>/dev/null | grep -E 'mCurrentFocus|mFocusedApp' | head -n 3",
            label = "topPackage"
        )
        if (output.isBlank()) return null
        val packagePattern = Regex("([a-zA-Z0-9_]+\\.)+[a-zA-Z0-9_]+")
        return packagePattern.findAll(output)
            .map { it.value }
            .firstOrNull { pkg ->
                pkg != "com.android.systemui" && pkg != "android" && !pkg.startsWith("com.android.server")
            }
    }

    fun clearDisplayMode(): Boolean {
        return execRootDetailed("cmd display clear-user-preferred-display-mode", "clearDisplayMode").ok
    }

    fun restoreAdaptive(minHz: Int, maxHz: Int): Boolean {
        val script = buildString {
            appendLine("cmd display clear-user-preferred-display-mode")
            if (minHz > 0) appendLine("settings put system min_refresh_rate ${minHz}.0")
            if (maxHz > 0) appendLine("settings put system peak_refresh_rate ${maxHz}.0")
        }
        return execRootDetailed(script.trimEnd(), "restoreAdaptive min=$minHz max=$maxHz").ok
    }

    fun setNativeRefreshOverlay(on: Boolean): Boolean {
        val valInt = if (on) 1 else 0
        return execRootDetailed("service call SurfaceFlinger 1034 i32 $valInt", "nativeRefreshOverlay=$on").ok
    }

    fun steppedSwitch(
        targetMode: DisplayMode,
        allModes: List<DisplayMode>,
        currentHz: Int,
        isCancelled: () -> Boolean = { false }
    ): Boolean {
        return switchRefreshRate(targetMode, allModes, currentHz, isCancelled)
    }

    /**
     * Strict, acknowledged, step-by-step transition.
     *
     * The old implementation sent every command 800 ms apart whether Android had actually
     * entered the requested mode or not. On busy devices one asynchronous request could still
     * be pending when the next one arrived, so an intermediate rate was silently skipped.
     *
     * This implementation never advances until the exact step is observed for consecutive
     * samples. A failed step is retried with progressively stronger fallbacks. If a step still
     * cannot be observed, the transition stops and returns through the already confirmed modes
     * instead of pretending success.
     */
    fun switchRefreshRate(
        targetMode: DisplayMode,
        allModes: List<DisplayMode>,
        currentHz: Int,
        isCancelled: () -> Boolean = { false }
    ): Boolean {
        val requestGeneration = transitionGeneration.incrementAndGet()
        transitionLock.lock()
        try {
            val cancelled = {
                isCancelled() || requestGeneration != transitionGeneration.get()
            }
            if (cancelled()) {
                RuntimeLog.appendGlobal(TAG, "STRICT cancelled before start gen=$requestGeneration")
                return false
            }

            val modes = normalizeTransitionModes(allModes, targetMode)
            if (modes.isEmpty()) {
                RuntimeLog.appendGlobal(TAG, "STRICT no modes for ${targetMode.resolutionLabel}")
                return false
            }

            val initialState = readDisplayState()
            val sourceMode = resolveSourceMode(modes, targetMode, initialState, currentHz)
            val sourceHz = sourceMode?.rateInt
                ?: initialState.selectedHz()
                ?: currentHz.takeIf { it > 0 }
                ?: targetMode.rateInt
            val exactTarget = modes.firstOrNull {
                it.rateInt == targetMode.rateInt &&
                    it.width == targetMode.width &&
                    it.height == targetMode.height
            } ?: targetMode
            val route = buildTransitionRoute(modes, sourceHz, exactTarget)

            RuntimeLog.appendGlobal(
                TAG,
                "STRICT start gen=$requestGeneration source=${sourceHz}Hz " +
                    "target=${exactTarget.rateInt}Hz route=${route.map { it.rateInt }} " +
                    "state={${initialState.summary()}}"
            )

            val completed = mutableListOf<DisplayMode>()
            for ((index, step) in route.withIndex()) {
                if (cancelled()) {
                    RuntimeLog.appendGlobal(TAG, "STRICT cancelled before ${step.rateInt}Hz")
                    return false
                }
                val previousHz = completed.lastOrNull()?.rateInt ?: sourceHz
                val confirmed = applyAndConfirmStep(
                    mode = step,
                    increasing = step.rateInt >= previousHz,
                    isCancelled = cancelled,
                    finalStep = index == route.lastIndex
                )
                if (!confirmed) {
                    RuntimeLog.appendGlobal(
                        TAG,
                        "STRICT abort failed=${step.rateInt}Hz completed=${completed.map { it.rateInt }}"
                    )
                    rollbackConfirmedPath(
                        sourceMode = sourceMode,
                        completed = completed,
                        allModes = modes,
                        isCancelled = cancelled
                    )
                    return false
                }
                completed += step
            }

            if (cancelled()) return false
            sleepChecked(FINAL_STABLE_MS, cancelled)
            val finalState = readDisplayState()
            if (!finalState.matchesAppliedMode(exactTarget)) {
                RuntimeLog.appendGlobal(
                    TAG,
                    "STRICT final drift target=${exactTarget.rateInt}Hz state={${finalState.summary()}}"
                )
                val recovered = applyAndConfirmStep(
                    mode = exactTarget,
                    increasing = exactTarget.rateInt >= sourceHz,
                    isCancelled = cancelled,
                    finalStep = true
                )
                if (!recovered) {
                    rollbackConfirmedPath(sourceMode, completed, modes, cancelled)
                    return false
                }
            }

            RuntimeLog.appendGlobal(
                TAG,
                "STRICT success target=${exactTarget.rateInt}Hz state={${readDisplayState().summary()}}"
            )
            return true
        } finally {
            transitionLock.unlock()
        }
    }

    fun steppedDecrease(
        allModes: List<DisplayMode>,
        currentHz: Int,
        targetHz: Int,
        isCancelled: () -> Boolean = { false },
        targetMode: DisplayMode? = null
    ): Boolean {
        val exactTarget = targetMode
            ?: allModes.filter { it.rateInt == targetHz }.maxByOrNull { it.width * it.height }
            ?: return false
        return switchRefreshRate(exactTarget, allModes, currentHz, isCancelled)
    }

    private fun normalizeTransitionModes(
        allModes: List<DisplayMode>,
        targetMode: DisplayMode
    ): List<DisplayMode> {
        val freshModes = allModes
            .filter { it.width == targetMode.width && it.height == targetMode.height }
            .filter { it.rateInt in 30..300 }
            .groupBy { it.rateInt }
            .map { (_, candidates) ->
                candidates.minByOrNull { if (it.modeId > 0) it.modeId else Int.MAX_VALUE }!!
            }
            .toMutableList()

        // Only use the UI object's mode when the freshly scanned list truly lacks that rate.
        // This prevents a stale modeId/SF index from being preferred after display reconfiguration.
        if (freshModes.none { it.rateInt == targetMode.rateInt }) {
            freshModes += targetMode
        }
        return freshModes.sortedBy { it.rateInt }
    }

    private fun resolveSourceMode(
        modes: List<DisplayMode>,
        targetMode: DisplayMode,
        state: DisplayState,
        apiCurrentHz: Int
    ): DisplayMode? {
        state.activeModeId?.let { id ->
            modes.firstOrNull { it.modeId == id }?.let { return it }
        }
        val selectedHz = state.activeHz
            ?: apiCurrentHz.takeIf { it > 0 }
            ?: state.driverHz
            ?: state.physicalHz
        return selectedHz?.let { hz ->
            modes.minByOrNull { abs(it.rateInt - hz) }
        } ?: modes.minByOrNull { abs(it.rateInt - targetMode.rateInt) }
    }

    private fun buildTransitionRoute(
        modes: List<DisplayMode>,
        sourceHz: Int,
        targetMode: DisplayMode
    ): List<DisplayMode> {
        val targetHz = targetMode.rateInt
        val route = when {
            sourceHz < targetHz -> modes.filter { it.rateInt > sourceHz && it.rateInt <= targetHz }
            sourceHz > targetHz -> modes.filter { it.rateInt < sourceHz && it.rateInt >= targetHz }
                .sortedByDescending { it.rateInt }
            else -> listOf(targetMode)
        }.toMutableList()

        if (route.none { it.rateInt == targetHz }) {
            route += targetMode
        }
        return route.distinctBy { it.rateInt }
    }

    private fun applyAndConfirmStep(
        mode: DisplayMode,
        increasing: Boolean,
        isCancelled: () -> Boolean,
        finalStep: Boolean
    ): Boolean {
        for (attempt in 1..STEP_MAX_ATTEMPTS) {
            if (isCancelled()) return false

            // SurfaceFlinger binder transaction numbers are private and vary by Android build.
            // Use the stable DisplayManager command first; only use SF as a fallback.
            val useSfFallback = attempt >= 2
            val resetPreferred = attempt >= 3
            val result = requestDisplayMode(
                mode = mode,
                increasing = increasing,
                useSurfaceFlingerFallback = useSfFallback,
                resetPreferred = resetPreferred
            )
            RuntimeLog.appendGlobal(
                TAG,
                "STRICT request step=${mode.rateInt}Hz attempt=$attempt commandOk=${result.ok} " +
                    "sfFallback=$useSfFallback reset=$resetPreferred"
            )

            val confirmed = awaitExactMode(
                mode = mode,
                timeoutMs = STEP_APPLY_TIMEOUT_MS + (attempt - 1) * 700L,
                isCancelled = isCancelled
            )
            if (confirmed) {
                val settle = if (finalStep) STEP_SETTLE_MS + 250L else STEP_SETTLE_MS
                if (!sleepChecked(settle, isCancelled)) return false
                val afterSettle = readDisplayState()
                if (afterSettle.matchesAppliedMode(mode)) {
                    RuntimeLog.appendGlobal(
                        TAG,
                        "STRICT confirmed step=${mode.rateInt}Hz attempt=$attempt " +
                            "state={${afterSettle.summary()}}"
                    )
                    return true
                }
                RuntimeLog.appendGlobal(
                    TAG,
                    "STRICT unstable step=${mode.rateInt}Hz attempt=$attempt " +
                        "state={${afterSettle.summary()}}"
                )
            } else {
                RuntimeLog.appendGlobal(
                    TAG,
                    "STRICT timeout step=${mode.rateInt}Hz attempt=$attempt " +
                        "state={${readDisplayState().summary()}}"
                )
            }
        }
        return false
    }

    private fun awaitExactMode(
        mode: DisplayMode,
        timeoutMs: Long,
        isCancelled: () -> Boolean
    ): Boolean {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        var stableSamples = 0
        var lastObserved: Int? = null
        while (SystemClock.elapsedRealtime() < deadline) {
            if (!sleepChecked(STEP_POLL_MS, isCancelled)) return false
            val state = readDisplayState()
            val matched = state.matchesAppliedMode(mode)
            stableSamples = if (matched) stableSamples + 1 else 0
            val observed = state.selectedHz()
            if (observed != lastObserved || matched) {
                RuntimeLog.appendGlobal(
                    TAG,
                    "STRICT poll target=${mode.rateInt}Hz observed=${observed ?: "?"}Hz " +
                        "activeMode=${state.activeModeId ?: "?"} stable=$stableSamples"
                )
                lastObserved = observed
            }
            if (stableSamples >= STEP_STABLE_SAMPLES) return true
        }
        return false
    }

    private fun rollbackConfirmedPath(
        sourceMode: DisplayMode?,
        completed: List<DisplayMode>,
        allModes: List<DisplayMode>,
        isCancelled: () -> Boolean
    ) {
        if (sourceMode == null || isCancelled()) return
        val current = completed.lastOrNull()?.rateInt ?: readDisplayState().selectedHz() ?: return
        val rollbackRoute = buildTransitionRoute(allModes, current, sourceMode)
        RuntimeLog.appendGlobal(
            TAG,
            "STRICT rollback source=${sourceMode.rateInt}Hz route=${rollbackRoute.map { it.rateInt }}"
        )
        var previousHz = current
        for (step in rollbackRoute) {
            if (isCancelled()) return
            applyAndConfirmStep(
                mode = step,
                increasing = step.rateInt >= previousHz,
                isCancelled = isCancelled,
                finalStep = step.rateInt == sourceMode.rateInt
            )
            previousHz = step.rateInt
        }
    }

    private fun requestDisplayMode(
        mode: DisplayMode,
        increasing: Boolean,
        useSurfaceFlingerFallback: Boolean,
        resetPreferred: Boolean
    ): RootCommandResult {
        val script = buildString {
            if (resetPreferred) {
                appendLine("cmd display clear-user-preferred-display-mode 0 2>/dev/null || cmd display clear-user-preferred-display-mode 2>/dev/null")
            }
            appendLine(preferredModeCommand(mode.width, mode.height, mode.rateInt))
            if (increasing) {
                appendLine("settings put system peak_refresh_rate ${mode.rateInt}.0")
                appendLine("settings put system user_refresh_rate ${mode.rateInt}")
                appendLine("settings put secure miui_refresh_rate ${mode.rateInt}")
                appendLine("settings put system min_refresh_rate ${mode.rateInt}.0")
            } else {
                appendLine("settings put system min_refresh_rate ${mode.rateInt}.0")
                appendLine("settings put system user_refresh_rate ${mode.rateInt}")
                appendLine("settings put secure miui_refresh_rate ${mode.rateInt}")
                appendLine("settings put system peak_refresh_rate ${mode.rateInt}.0")
            }
            appendLine("settings put system thermal_limit_refresh_rate ${mode.rateInt} 2>/dev/null || true")
            if (useSurfaceFlingerFallback && mode.sfIndex >= 0) {
                appendLine("service call SurfaceFlinger 1035 i32 ${mode.sfIndex} >/dev/null 2>&1 || true")
            }
        }.trimEnd()
        return execRootDetailed(
            script,
            "strictMode:${mode.resolutionLabel}@${mode.rateInt}Hz attemptSf=$useSurfaceFlingerFallback"
        )
    }

    private fun preferredModeCommand(width: Int, height: Int, hz: Int): String {
        // Android 14+ accepts an optional display id. Fall back for older/vendor implementations.
        return "cmd display set-user-preferred-display-mode $width $height ${hz}.0 0 2>/dev/null || " +
            "cmd display set-user-preferred-display-mode $width $height ${hz}.0 2>/dev/null"
    }

    private fun applyRateSettings(targetHz: Int, increasing: Boolean): RootCommandResult {
        val script = buildString {
            if (increasing) {
                appendLine("settings put system peak_refresh_rate ${targetHz}.0")
                appendLine("settings put system user_refresh_rate $targetHz")
                appendLine("settings put secure miui_refresh_rate $targetHz")
                appendLine("settings put system min_refresh_rate ${targetHz}.0")
            } else {
                appendLine("settings put system min_refresh_rate ${targetHz}.0")
                appendLine("settings put system user_refresh_rate $targetHz")
                appendLine("settings put secure miui_refresh_rate $targetHz")
                appendLine("settings put system peak_refresh_rate ${targetHz}.0")
            }
            appendLine("settings put system thermal_limit_refresh_rate $targetHz 2>/dev/null || true")
        }.trimEnd()
        return execRootDetailed(script, "rateSettings:${targetHz}Hz increasing=$increasing")
    }

    private fun sleepChecked(durationMs: Long, isCancelled: () -> Boolean): Boolean {
        val deadline = SystemClock.elapsedRealtime() + durationMs
        while (SystemClock.elapsedRealtime() < deadline) {
            if (isCancelled()) return false
            val remaining = deadline - SystemClock.elapsedRealtime()
            try {
                Thread.sleep(minOf(100L, remaining.coerceAtLeast(1L)))
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return false
            }
        }
        return !isCancelled()
    }

    fun findBestTargetForHz(allModes: List<DisplayMode>, currentMode: DisplayMode?, targetHz: Int): DisplayMode? {
        if (allModes.isEmpty()) return null
        val sameResolution = if (currentMode != null) {
            allModes.filter { it.width == currentMode.width && it.height == currentMode.height }
        } else {
            emptyList()
        }
        val candidates = sameResolution.ifEmpty { allModes }
        return candidates
            .filter { it.rateInt <= targetHz }
            .maxByOrNull { it.rateInt }
            ?: candidates.minByOrNull { abs(it.rateInt - targetHz) }
    }

    fun findBestTargetForMode(allModes: List<DisplayMode>, mode: DisplayMode): DisplayMode? {
        if (allModes.isEmpty()) return null
        val sameResolution = allModes.filter { it.width == mode.width && it.height == mode.height }
        return sameResolution.firstOrNull { it.rateInt == mode.rateInt }
            ?: sameResolution.minByOrNull { abs(it.rateInt - mode.rateInt) }
            ?: allModes.minByOrNull { abs(it.rateInt - mode.rateInt) }
    }

    private fun parseDisplayState(output: String): DisplayState {
        val activeModeId = Regex("""mActiveModeId=(\d+)""").find(output)?.groupValues?.get(1)?.toIntOrNull()
            ?: Regex("""activeModeId=(\d+)""").find(output)?.groupValues?.get(1)?.toIntOrNull()
        val records = RECORD_PATTERN.findAll(output).associate { match ->
            val id = match.groupValues[1].toInt()
            id to ModeRecord(
                match.groupValues[2].toInt(),
                match.groupValues[3].toInt(),
                match.groupValues[4].toFloat().roundToInt()
            )
        }
        val activeRecord = activeModeId?.let { records[it] }
        val activeHz = activeRecord?.hz
            ?: Regex("""mRefreshRate=([\d.]+)""").find(output)?.groupValues?.get(1)?.toFloatOrNull()?.roundToInt()
            ?: Regex("""refreshRate=([\d.]+)""").find(output)?.groupValues?.get(1)?.toFloatOrNull()?.roundToInt()

        return DisplayState(
            peakHz = parseSimpleHz(output, "peak"),
            minHz = parseSimpleHz(output, "min"),
            userHz = parseSimpleHz(output, "user"),
            miuiHz = parseSimpleHz(output, "miui"),
            preferredHz = parsePreferredHz(output),
            activeModeId = activeModeId,
            activeWidth = activeRecord?.width,
            activeHeight = activeRecord?.height,
            activeHz = activeHz,
            physicalHz = parsePhysicalHz(output),
            driverHz = parseNodeHz(output, "driverNode="),
            renderedFps = parseNodeHz(output, "renderedNode="),
            raw = output
        )
    }

    private fun parseSimpleHz(output: String, key: String): Int? {
        val value = output.lineSequence()
            .firstOrNull { it.startsWith("$key=") }
            ?.substringAfter("=")
            ?.trim()
            ?: return null
        return NUMBER_PATTERN.find(value)?.value?.toFloatOrNull()?.roundToInt()
    }

    private fun parsePreferredHz(output: String): Int? {
        val line = output.lineSequence().firstOrNull { it.startsWith("preferred=") } ?: return null
        Regex("""User preferred display mode:\s+\d+\s+\d+\s+([\d.]+)""")
            .find(line)
            ?.groupValues
            ?.get(1)
            ?.toFloatOrNull()
            ?.roundToInt()
            ?.let { return it }
        return Regex("""([\d.]+)\s*Hz""").find(line)?.groupValues?.get(1)?.toFloatOrNull()?.roundToInt()
    }

    private fun parsePhysicalHz(output: String): Int? {
        val periodNs = output.lineSequence()
            .firstOrNull { it.startsWith("sfPeriodNs=") }
            ?.substringAfter("=")
            ?.trim()
            ?.toLongOrNull()
            ?: return null
        if (periodNs !in 3_000_000L..50_000_000L) return null
        return (1_000_000_000.0 / periodNs.toDouble()).roundToInt()
    }

    private fun parseNodeHz(output: String, prefix: String): Int? {
        val line = output.lineSequence().firstOrNull { it.startsWith(prefix) } ?: return null
        val values = NUMBER_PATTERN.findAll(line.substringAfter(":"))
            .mapNotNull { it.value.toFloatOrNull() }
            .toList()
        val direct = values.firstOrNull { it in 30f..300f }
        if (direct != null) return direct.roundToInt()
        val scaled = values.firstOrNull { it in 3000f..30000f }
        return scaled?.div(100f)?.roundToInt()
    }

    private fun displayStateScript(): String {
        return """
            echo peak=${'$'}(settings get system peak_refresh_rate 2>/dev/null)
            echo min=${'$'}(settings get system min_refresh_rate 2>/dev/null)
            echo user=${'$'}(settings get system user_refresh_rate 2>/dev/null)
            echo miui=${'$'}(settings get secure miui_refresh_rate 2>/dev/null)
            echo preferred=${'$'}(cmd display get-user-preferred-display-mode 2>/dev/null)
            echo sfPeriodNs=${'$'}(dumpsys SurfaceFlinger --latency 2>/dev/null | head -n 1)
            for f in /sys/class/drm/*/dynamic_fps /sys/class/drm/*/current_fps /sys/class/graphics/fb*/dynamic_fps /sys/class/graphics/fb*/current_fps; do
                if [ -r "${'$'}f" ]; then echo driverNode=${'$'}f:${'$'}(cat "${'$'}f" 2>/dev/null | head -n 1); fi
            done
            for f in /sys/class/drm/*/measured_fps /sys/class/graphics/fb*/measured_fps; do
                if [ -r "${'$'}f" ]; then echo renderedNode=${'$'}f:${'$'}(cat "${'$'}f" 2>/dev/null | head -n 1); fi
            done
            dumpsys display 2>/dev/null | grep -E 'mActiveMode|activeMode|DisplayModeRecord|mModeId|mRefreshRate|refreshRate' | head -n 80
            dumpsys SurfaceFlinger 2>/dev/null | grep -iE 'refresh.?rate|vsync.*period|active.*config|active.*mode' | head -n 30
        """.trimIndent()
    }

    private fun logRootResult(result: RootCommandResult) {
        val out = shorten(result.stdout)
        val err = shorten(result.stderr)
        if (!result.ok) {
            Log.e(TAG, "execRoot FAILED: exitCode=${result.exitCode}, err=$err, label=${result.label}")
        }
        val detail = buildString {
            append("ROOT label=${result.label} ok=${result.ok} exit=${result.exitCode}")
            if (out.isNotBlank()) append(" out=$out")
            if (err.isNotBlank()) append(" err=$err")
        }
        RuntimeLog.appendGlobal(TAG, detail)
    }

    private fun firstCommand(script: String): String {
        return script.lineSequence().firstOrNull { it.isNotBlank() }?.trim().orEmpty()
    }

    private fun shorten(value: String, limit: Int = 220): String {
        val compact = value.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.joinToString(" | ")
        return if (compact.length <= limit) compact else compact.take(limit) + "..."
    }

    private data class ModeRecord(val width: Int, val height: Int, val hz: Int)
}

private fun hzMatches(currentHz: Int, targetHz: Int): Boolean {
    return currentHz > 0 && abs(currentHz - targetHz) <= 1
}
