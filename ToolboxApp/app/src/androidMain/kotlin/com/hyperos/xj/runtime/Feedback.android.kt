package com.hyperos.xj.runtime

import android.content.Context
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

private const val FEEDBACK_REPO = "1014411313/HyperOS_XJ"
private const val FEEDBACK_PREFS = "xj_feedback"

private val appContext: Context? by lazy {
    runCatching {
        Class.forName("android.app.ActivityThread")
            .getMethod("currentApplication")
            .invoke(null) as? Context
    }.getOrNull()
}

actual fun loadFeedbackToken(): String =
    appContext?.getSharedPreferences(FEEDBACK_PREFS, Context.MODE_PRIVATE)
        ?.getString("token", "").orEmpty()

actual fun saveFeedbackToken(token: String) {
    appContext?.getSharedPreferences(FEEDBACK_PREFS, Context.MODE_PRIVATE)
        ?.edit()?.putString("token", token.trim())?.apply()
}

/** 未配置 token 时回退到 feedback_endpoint.json 里的中转地址（可选，普通用法不需要）。 */
actual suspend fun feedbackEndpoint(): String? = withContext(Dispatchers.IO) {
    for (src in listOf(
        "https://cdn.jsdelivr.net/gh/$FEEDBACK_REPO@master/feedback_endpoint.json",
        "https://gitee.com/xiaomirezhale/HyperOS_XJ/raw/master/feedback_endpoint.json",
        "https://raw.githubusercontent.com/$FEEDBACK_REPO/master/feedback_endpoint.json",
    )) {
        val body = runCatching { httpGet(src, 8000, 12000) }.getOrNull() ?: continue
        val url = Regex("\"url\"\\s*:\\s*\"([^\"]*)\"").find(body)?.groupValues?.get(1)
        if (!url.isNullOrBlank()) return@withContext url.trim()
    }
    null
}

actual suspend fun uploadDiagnosticPackage(
    filePath: String,
    note: String,
    device: String,
): String? = withContext(Dispatchers.IO) {
    val file = File(filePath)
    if (!file.isFile) return@withContext null

    // 公开通道优先：任何用户都不需要 token，靠 feedback_endpoint.json 里的中转地址
    val endpoint = feedbackEndpoint()
    if (endpoint != null) {
        uploadViaWorker(endpoint, file, note, device)?.let { return@withContext it }
    }

    // 兜底：本地配了 token 就直传 GitHub（作者自用/中转不可用时）
    val token = loadFeedbackToken()
    if (token.isNotBlank()) return@withContext uploadViaGithub(file, note, device, token)
    null
}

// 主用法：App 直接调 GitHub Contents API，不需要任何中转服务
private fun uploadViaGithub(file: File, note: String, device: String, token: String): String? {
    val stamp = java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US).format(java.util.Date())
    val safe = file.name.replace(Regex("[^\\w.\\-]"), "_")
    val path = "feedback/${stamp}_$safe"
    val meta = "机型: $device\n上传: $stamp\n\n问题描述:\n${note.ifBlank { "（未填写）" }}\n"

    fun put(p: String, bytes: ByteArray): Boolean {
        val conn = URL("https://api.github.com/repos/$FEEDBACK_REPO/contents/$p").openConnection() as HttpURLConnection
        return runCatching {
            conn.requestMethod = "PUT"
            conn.doOutput = true
            conn.connectTimeout = 15000
            conn.readTimeout = 90000
            conn.setRequestProperty("Authorization", "Bearer $token")
            conn.setRequestProperty("Accept", "application/vnd.github+json")
            conn.setRequestProperty("User-Agent", "xj-app-feedback")
            conn.setRequestProperty("Content-Type", "application/json")
            val payload = """{"message":"feedback: $p","content":"${Base64.encodeToString(bytes, Base64.NO_WRAP)}"}"""
            conn.outputStream.use { it.write(payload.toByteArray()) }
            val code = conn.responseCode
            (if (code in 200..299) conn.inputStream else conn.errorStream)?.use { it.readBytes() }
            code in 200..299
        }.also { conn.disconnect() }.getOrDefault(false)
    }

    if (!put(path, file.readBytes())) return null
    put(path.replace(Regex("\\.[^.]+$"), "") + ".txt", meta.toByteArray())
    return path
}

private fun uploadViaWorker(endpoint: String, file: File, note: String, device: String): String? {
    return runCatching {
        val boundary = "----xj" + System.currentTimeMillis()
        val conn = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 15000
            readTimeout = 90000
            setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
        }
        conn.outputStream.use { out ->
            fun field(name: String, value: String) {
                out.write("--$boundary\r\nContent-Disposition: form-data; name=\"$name\"\r\n\r\n$value\r\n".toByteArray())
            }
            field("note", note)
            field("device", device)
            out.write(
                ("--$boundary\r\nContent-Disposition: form-data; name=\"file\"; filename=\"${file.name}\"\r\n" +
                    "Content-Type: application/octet-stream\r\n\r\n").toByteArray()
            )
            file.inputStream().use { it.copyTo(out) }
            out.write("\r\n--$boundary--\r\n".toByteArray())
        }
        val code = conn.responseCode
        val body = (if (code in 200..299) conn.inputStream else conn.errorStream)
            ?.bufferedReader()?.use { it.readText() }.orEmpty()
        conn.disconnect()
        if (code !in 200..299) null
        else Regex("\"file\"\\s*:\\s*\"([^\"]+)\"").find(body)?.groupValues?.get(1) ?: "feedback/"
    }.getOrNull()
}

private fun httpGet(url: String, connectTimeoutMs: Int, readTimeoutMs: Int): String {
    val conn = (URL(url).openConnection() as HttpURLConnection).apply {
        requestMethod = "GET"
        connectTimeout = connectTimeoutMs
        readTimeout = readTimeoutMs
    }
    return try {
        conn.inputStream.bufferedReader().use { it.readText() }
    } finally {
        conn.disconnect()
    }
}
