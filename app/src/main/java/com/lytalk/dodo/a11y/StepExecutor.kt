package com.lytalk.dodo.a11y

import android.accessibilityservice.AccessibilityService
import android.app.KeyguardManager
import android.content.Intent
import android.provider.Settings
import com.lytalk.dodo.data.StepEntity
import com.lytalk.dodo.data.StepPayloads
import com.lytalk.dodo.data.StepTypes
import com.lytalk.dodo.schedule.RootShell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject

class StepExecutor(private val service: AutoAccessibilityService) {
    suspend fun run(steps: List<StepEntity>, task: com.lytalk.dodo.data.TaskEntity): String {
        if (steps.isEmpty()) return "没有可执行的步骤"
        val retries = task.retryCount.coerceIn(0, 5)
        var lastError = "执行失败"
        for (attempt in 0..retries) {
            val error = runOnce(steps)
            if (error == null) {
                return if (attempt == 0) "成功" else "成功（第 ${attempt + 1} 次执行）"
            }
            lastError = error
            val cleanupError = runCleanup(task, steps)
            if (attempt < retries) {
                delay(800)
                continue
            }
            val retryNote = if (retries > 0) "（已重试 $retries 次）" else ""
            val cleanupNote = cleanupError?.let { "；清理失败：$it" }.orEmpty()
            return "$lastError$retryNote$cleanupNote"
        }
        return lastError
    }

    private suspend fun runOnce(steps: List<StepEntity>): String? {
        val actions = NodeActions(service)
        steps.forEachIndexed { index, step ->
            val error = execute(actions, step)
            if (error != null) return "第 ${index + 1} 步失败：$error"
        }
        return null
    }

    private suspend fun runCleanup(task: com.lytalk.dodo.data.TaskEntity, steps: List<StepEntity>): String? {
        return when (task.cleanupType) {
            com.lytalk.dodo.data.CleanupTypes.HOME -> {
                if (service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)) null else "无法回到桌面"
            }
            com.lytalk.dodo.data.CleanupTypes.CLOSE_LAUNCHED -> closeLaunched(steps)
            com.lytalk.dodo.data.CleanupTypes.STEP -> {
                if (task.cleanupStepType.isBlank()) return "没有设置兜底步骤"
                execute(
                    NodeActions(service),
                    StepEntity(taskId = 0, position = 0, type = task.cleanupStepType, payload = task.cleanupPayload),
                )
            }
            else -> null
        }
    }

    private suspend fun closeLaunched(steps: List<StepEntity>): String? {
        val packages = steps.mapNotNull { step ->
            if (step.type != StepTypes.LAUNCH_APP) return@mapNotNull null
            StepPayloads.read(step.payload).optString("packageName").takeIf { it.isNotBlank() }
        }.distinct()
        if (packages.isEmpty()) return "任务里没有打开应用的步骤"
        service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
        delay(300)
        val errors = packages.mapNotNull { pkg ->
            withContext(Dispatchers.IO) { RootShell.forceStop(pkg) }?.let { "$pkg：$it" }
        }
        return errors.takeIf { it.isNotEmpty() }?.joinToString("；")
    }

    private suspend fun execute(actions: NodeActions, step: StepEntity): String? {
        val json = StepPayloads.read(step.payload)
        return when (step.type) {
            StepTypes.LAUNCH_APP -> launch(json.optString("packageName"))
            StepTypes.CLICK_TEXT -> {
                val text = json.optString("text")
                if (text.isBlank()) return "没有填写要点击的文字"
                if (searchAndClick(actions) { actions.clickText(text) }) null else "在整个界面里找不到文字：$text"
            }
            StepTypes.CLICK_ID -> {
                val viewId = json.optString("viewId")
                if (viewId.isBlank()) return "没有填写控件 id"
                if (searchAndClick(actions) { actions.clickViewId(viewId) }) null else "在整个界面里找不到控件：$viewId"
            }
            StepTypes.CLICK_TARGET -> clickTarget(actions, json)
            StepTypes.CLOSE_APP -> closeApp(json.optString("packageName"), json.optString("label"))
            StepTypes.UNLOCK -> unlock(json)
            StepTypes.INPUT_TEXT -> {
                val text = json.optString("text")
                val targetText = json.optString("targetText")
                val targetViewId = json.optString("targetViewId")
                val ok = poll(8_000) { actions.setText(text, targetText, targetViewId) }
                if (ok) null else "找不到可输入的位置"
            }
            StepTypes.WAIT -> {
                delay(json.optLong("millis", 500).coerceIn(0, 120_000))
                null
            }
            StepTypes.WAIT_FOR_TEXT -> {
                val text = json.optString("text")
                if (text.isBlank()) return "没有填写要等待的文字"
                val timeout = json.optLong("timeoutMs", 10_000).coerceIn(1_000, 60_000)
                if (poll(timeout) { actions.hasText(text) }) null else "在 ${timeout / 1000} 秒内没有出现「$text」"
            }
            StepTypes.BACK -> {
                if (service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)) null
                else "无法按返回"
            }
            else -> "未知步骤"
        }
    }

    private suspend fun unlock(json: JSONObject): String? {
        com.lytalk.dodo.schedule.WakeActivity.finishIfUp()
        delay(400)
        if (!isLockScreenShowing()) return null
        val strokes = json.optJSONArray("strokes") ?: return "还没有录制解锁动作"
        if (strokes.length() == 0) return "还没有录制解锁动作"
        val metrics = service.resources.displayMetrics
        for (index in 0 until strokes.length()) {
            val stroke = strokes.optJSONObject(index) ?: continue
            delay(stroke.optLong("delayBefore").coerceIn(0, 5_000))
            val pointsJson = stroke.optJSONArray("points") ?: continue
            val points = mutableListOf<Pair<Float, Float>>()
            var duration = 40L
            for (pointIndex in 0 until pointsJson.length()) {
                val point = pointsJson.optJSONObject(pointIndex) ?: continue
                points += (point.optDouble("x") * metrics.widthPixels).toFloat() to
                    (point.optDouble("y") * metrics.heightPixels).toFloat()
                duration = maxOf(duration, point.optLong("t"))
            }
            if (points.isEmpty()) continue
            val played = service.playStroke(points, duration.coerceIn(40, 10_000))
            if (!played) return "解锁动作没有送出去"
        }
        val unlocked = poll(5_000) {
            !service.getSystemService(KeyguardManager::class.java).isKeyguardLocked
        }
        return if (unlocked) null else "解锁动作已做完，屏幕仍是锁定状态"
    }

    private fun isLockScreenShowing(): Boolean {
        val keyguard = service.getSystemService(KeyguardManager::class.java)
        if (!keyguard.isKeyguardLocked && !keyguard.isDeviceLocked) return false
        val root = service.rootInActiveWindow
        val pkg = root?.packageName?.toString().orEmpty()
        if (root != null) runCatching { root.recycle() }
        if (pkg.isEmpty() || pkg == service.packageName) return keyguard.isKeyguardLocked || keyguard.isDeviceLocked
        return pkg == "com.android.systemui" || pkg.contains("keyguard", ignoreCase = true) || pkg == "android"
    }

    private suspend fun clickTarget(actions: NodeActions, json: JSONObject): String? {
        val viewId = json.optString("viewId")
        val text = json.optString("text")
        val description = json.optString("description")
        val x = json.optDouble("xFraction", -1.0)
        val y = json.optDouble("yFraction", -1.0)
        if (viewId.isBlank() && text.isBlank() && description.isBlank() && x >= 0 && y >= 0) {
            return tapAt(x, y)
        }
        val found = searchAndClick(actions) {
            (viewId.isNotBlank() && actions.clickViewId(viewId)) ||
                (text.isNotBlank() && actions.clickText(text)) ||
                (description.isNotBlank() && description != text && actions.clickText(description))
        }
        if (found) return null
        if (x < 0 || y < 0) {
            return if (viewId.isNotBlank()) "在整个界面里找不到控件：$viewId" else "没有可用的控件信息"
        }
        return tapAt(x, y)
    }

    private suspend fun tapAt(x: Double, y: Double): String? {
        val metrics = service.resources.displayMetrics
        val tapped = service.tap((x * metrics.widthPixels).toFloat(), (y * metrics.heightPixels).toFloat())
        return if (tapped) null else "按这个位置点击失败"
    }

    private suspend fun closeApp(packageName: String, label: String): String? {
        if (packageName.isBlank()) return "没有选择要关闭的应用"
        if (packageName == service.packageName) return "不能关闭本应用"
        if (service.foregroundPackage() == packageName) {
            service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
            delay(300)
        }
        return withContext(Dispatchers.IO) {
            RootShell.forceStop(packageName) ?: return@withContext null
        }?.let { "无法关闭${label.ifBlank { packageName }}：$it" }
    }

    private suspend fun searchAndClick(actions: NodeActions, attempt: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + 18_000
        val warmup = System.currentTimeMillis() + 2_000
        while (System.currentTimeMillis() < warmup) {
            if (runCatching(attempt).getOrDefault(false)) return true
            delay(300)
        }
        for (step in 0 until 8) {
            if (!actions.scrollLargest(forward = false)) break
            delay(180)
            if (runCatching(attempt).getOrDefault(false)) return true
        }
        while (System.currentTimeMillis() < deadline) {
            if (runCatching(attempt).getOrDefault(false)) return true
            if (!actions.scrollLargest(forward = true)) break
            delay(280)
        }
        return runCatching(attempt).getOrDefault(false)
    }

    private suspend fun launch(packageName: String): String? {
        if (packageName.isBlank()) return "没有选择应用"
        val intent = service.packageManager.getLaunchIntentForPackage(packageName)?.apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        } ?: return "找不到可启动的应用"
        try {
            service.startActivity(intent)
        } catch (_: Exception) {
            return launchHint(packageName, "系统拒绝打开应用")
        }
        val appeared = poll(10_000) { service.foregroundPackage() == packageName }
        return if (appeared) null else launchHint(packageName, "等待应用出现超时")
    }

    private fun launchHint(packageName: String, reason: String): String {
        val hint = if (Settings.canDrawOverlays(service)) {
            "请确认 MIUI 的「后台弹出界面」已对本应用打开"
        } else {
            "请开启「显示在其他应用上层」，并在 MIUI 打开「后台弹出界面」"
        }
        return "$reason（$packageName）。$hint"
    }

    private suspend fun poll(timeoutMs: Long, block: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (true) {
            if (runCatching(block).getOrDefault(false)) return true
            if (System.currentTimeMillis() >= deadline) return false
            delay(300)
        }
    }
}
