package com.lytalk.dodo.data

import org.json.JSONArray
import org.json.JSONObject

object CleanupTypes {
    const val NONE = "NONE"
    const val HOME = "HOME"
    const val CLOSE_LAUNCHED = "CLOSE_LAUNCHED"
    const val STEP = "STEP"
}

object StepTypes {
    const val LAUNCH_APP = "LAUNCH_APP"
    const val CLICK_TEXT = "CLICK_TEXT"
    const val CLICK_ID = "CLICK_ID"
    const val CLICK_TARGET = "CLICK_TARGET"
    const val CLOSE_APP = "CLOSE_APP"
    const val UNLOCK = "UNLOCK"
    const val INPUT_TEXT = "INPUT_TEXT"
    const val WAIT = "WAIT"
    const val WAIT_FOR_TEXT = "WAIT_FOR_TEXT"
    const val BACK = "BACK"
}

object StepPayloads {
    fun launch(packageName: String, label: String): String =
        JSONObject().put("packageName", packageName).put("label", label).toString()

    fun clickText(text: String): String =
        JSONObject().put("text", text).put("contains", true).toString()

    fun clickId(viewId: String): String =
        JSONObject().put("viewId", viewId).toString()

    fun clickPosition(xFraction: Float, yFraction: Float, label: String): String = JSONObject()
        .put("xFraction", xFraction.toDouble())
        .put("yFraction", yFraction.toDouble())
        .put("label", label)
        .toString()

    fun clickTarget(
        viewId: String,
        text: String,
        description: String,
        xFraction: Float,
        yFraction: Float,
    ): String = JSONObject()
        .put("viewId", viewId)
        .put("text", text)
        .put("description", description)
        .put("xFraction", xFraction.toDouble())
        .put("yFraction", yFraction.toDouble())
        .toString()

    fun closeApp(packageName: String, label: String): String =
        JSONObject().put("packageName", packageName).put("label", label).toString()

    fun unlock(strokes: JSONArray): String =
        JSONObject().put("strokes", strokes).toString()

    fun input(text: String, targetText: String, targetViewId: String): String =
        JSONObject()
            .put("text", text)
            .put("targetText", targetText)
            .put("targetViewId", targetViewId)
            .toString()

    fun wait(millis: Long): String =
        JSONObject().put("millis", millis).toString()

    fun waitForText(text: String, timeoutMs: Long): String =
        JSONObject().put("text", text).put("timeoutMs", timeoutMs).toString()

    fun back(): String = "{}"

    fun read(payload: String): JSONObject =
        try {
            JSONObject(payload.ifBlank { "{}" })
        } catch (_: Exception) {
            JSONObject()
        }
}

fun stepSummary(type: String, payload: String): String {
    val json = StepPayloads.read(payload)
    return when (type) {
        StepTypes.LAUNCH_APP -> "打开应用：${json.optString("label").ifBlank { json.optString("packageName") }}"
        StepTypes.CLICK_TEXT -> "点击文字：${json.optString("text")}"
        StepTypes.CLICK_ID -> "点击控件：${json.optString("viewId")}"
        StepTypes.CLICK_TARGET -> clickTargetSummary(json)
        StepTypes.CLOSE_APP -> "关闭应用：${json.optString("label").ifBlank { json.optString("packageName") }}"
        StepTypes.UNLOCK -> "解锁：${json.optJSONArray("strokes")?.length() ?: 0} 段操作"
        StepTypes.INPUT_TEXT -> {
            val target = json.optString("targetViewId").ifBlank { json.optString("targetText") }
            if (target.isBlank()) "输入：${json.optString("text")}" else "在「$target」输入：${json.optString("text")}"
        }
        StepTypes.WAIT -> "等待 ${json.optLong("millis")} 毫秒"
        StepTypes.WAIT_FOR_TEXT -> "等到出现「${json.optString("text")}」（${json.optLong("timeoutMs") / 1000} 秒）"
        StepTypes.BACK -> "按返回"
        else -> type
    }
}

private fun clickTargetSummary(json: JSONObject): String {
    val viewId = json.optString("viewId")
    val text = json.optString("text").ifBlank { json.optString("description") }
    val x = json.optDouble("xFraction", -1.0)
    val y = json.optDouble("yFraction", -1.0)
    if (viewId.isBlank() && text.isBlank() && x >= 0 && y >= 0) {
        val place = "位置 ${(x * 100).toInt()}%, ${(y * 100).toInt()}%"
        val label = json.optString("label")
        return if (label.isBlank()) "点击$place" else "点击$place（$label）"
    }
    return when {
        viewId.isNotBlank() -> "点击控件：$viewId"
        text.isNotBlank() -> "点击控件：「$text」"
        else -> "点击位置"
    }
}
