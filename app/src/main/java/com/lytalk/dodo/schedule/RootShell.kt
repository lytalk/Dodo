package com.lytalk.dodo.schedule

import java.util.concurrent.TimeUnit

object RootShell {
    private val packageNamePattern = Regex("^[a-zA-Z][a-zA-Z0-9_]*(\\.[a-zA-Z][a-zA-Z0-9_]*)+$")

    fun hasRoot(): Boolean {
        val result = exec("id")
        return result.ok && result.output.contains("uid=0")
    }

    fun tap(x: Int, y: Int): Boolean {
        if (x !in 0..4096 || y !in 0..8192) return false
        return exec("input tap $x $y").ok
    }

    fun forceStop(packageName: String): String? {
        if (!packageNamePattern.matches(packageName)) return "应用包名无效"
        val result = exec("am force-stop $packageName")
        if (result.ok) return null
        return if (result.output.contains("uid=0") || result.output.isBlank()) {
            "Root 未授权，请到权限页申请"
        } else {
            result.output
        }
    }

    private fun exec(command: String): ShellResult {
        var missing = ShellResult(false, "找不到 su，请确认手机已 root")
        for (bin in listOf("su", "/system/bin/su", "/system/xbin/su", "/sbin/su", "/debug_ramdisk/su")) {
            try {
                val process = ProcessBuilder(bin, "-c", command)
                    .redirectErrorStream(true)
                    .start()
                val output = process.inputStream.bufferedReader().use { it.readText() }.trim()
                if (!process.waitFor(15, TimeUnit.SECONDS)) {
                    process.destroy()
                    return ShellResult(false, "Root 命令超时")
                }
                return if (process.exitValue() == 0) {
                    ShellResult(true, output)
                } else {
                    ShellResult(false, output.ifBlank { "su 退出码 ${process.exitValue()}" })
                }
            } catch (_: java.io.IOException) {
                continue
            } catch (error: Exception) {
                return ShellResult(false, error.message ?: "无法执行 su")
            }
        }
        return missing
    }

    private data class ShellResult(val ok: Boolean, val output: String)
}
