package com.workbreaktimer.app

import kotlin.system.exitProcess

/**
 * Runs inside a process Shizuku starts with shell (ADB) privileges, which is the only reason
 * the airplane-mode command below is allowed. The app never instantiates this class itself;
 * Shizuku loads it from the APK by name.
 */
class AirplaneUserService : IAirplaneService.Stub() {

    override fun destroy() {
        exitProcess(0)
    }

    override fun setAirplaneMode(enabled: Boolean): String? = try {
        val process = ProcessBuilder(
            "cmd", "connectivity", "airplane-mode", if (enabled) "enable" else "disable"
        ).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText().trim()
        val code = process.waitFor()
        if (code == 0) null else "команда вернула код $code" + if (output.isNotEmpty()) ": $output" else ""
    } catch (e: Exception) {
        "команда не запустилась: ${e.message}"
    }
}
