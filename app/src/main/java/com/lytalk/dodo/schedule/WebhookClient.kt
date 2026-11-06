package com.lytalk.dodo.schedule

import com.lytalk.dodo.data.TaskEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object WebhookClient {
    suspend fun post(url: String, task: TaskEntity, result: String): String? {
        val target = url.trim()
        if (target.isEmpty()) return null
        return withContext(Dispatchers.IO) {
            var connection: HttpURLConnection? = null
            try {
                connection = (URL(target).openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = 10_000
                    readTimeout = 10_000
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json; charset=utf-8")
                }
                val body = if (isWeCom(target)) weComBody(task, result) else genericBody(task, result)
                connection.outputStream.use { stream ->
                    stream.write(body.toByteArray(Charsets.UTF_8))
                }
                val code = connection.responseCode
                val responseText = readResponse(connection, code)
                if (code !in 200..299) {
                    "企业微信返回 $code ${responseText.take(80)}"
                } else {
                    val json = runCatching { JSONObject(responseText) }.getOrNull()
                    val err = json?.optInt("errcode", 0) ?: 0
                    if (json != null && json.has("errcode") && err != 0) {
                        json.optString("errmsg").ifBlank { "errcode $err" }
                    } else {
                        null
                    }
                }
            } catch (error: Exception) {
                "企业微信发送失败：${error.message ?: error.javaClass.simpleName}"
            } finally {
                connection?.disconnect()
            }
        }
    }

    private fun isWeCom(url: String): Boolean = url.contains("qyapi.weixin.qq.com")

    private fun weComBody(task: TaskEntity, result: String): String {
        val time = SimpleDateFormat("M月d日 HH:mm", Locale.CHINA).format(Date())
        val content = "嘟嘟「${task.name}」\n结果：$result\n时间：$time"
        return JSONObject()
            .put("msgtype", "text")
            .put("text", JSONObject().put("content", content))
            .toString()
    }

    private fun genericBody(task: TaskEntity, result: String): String =
        JSONObject()
            .put("taskId", task.id)
            .put("name", task.name)
            .put("success", result == "成功")
            .put("result", result)
            .put("finishedAt", System.currentTimeMillis())
            .toString()

    private fun readResponse(connection: HttpURLConnection, code: Int): String {
        val stream = if (code in 200..299) connection.inputStream else connection.errorStream
        return stream?.bufferedReader()?.use { it.readText() }.orEmpty()
    }
}
