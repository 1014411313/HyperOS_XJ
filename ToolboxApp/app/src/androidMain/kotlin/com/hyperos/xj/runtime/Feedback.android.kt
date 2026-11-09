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

// 内置默认中转地址：即使读不到 feedback_endpoint.json（缓存未刷新/网络受限）也能上传
private const val DEFAULT_ENDPOINT = "https://xj-feedback.1014411313.workers.dev"

private val ENDPOINT_SOURCES = listOf(
    "https://cdn.jsdelivr.net/gh/$FEEDBACK_REPO@master/feedback_endpoint.json",
    "https://gitee.com/xiaomirezhale/HyperOS_XJ/raw/master/feedback_endpoint.json",
    "https://raw.githubusercontent.com/$FEEDBACK_REPO/master/feedback_endpoint.json",
)

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

actual suspend fun feedbackEndpoint(): String? = withContext(Dispatchers.IO) {
    for (src in ENDPOINT_SOURCES) {
        val body = runCatching { httpGet(src, 8000, 12000) }.getOrNull() ?: continue
        val url = Regex("\"url\"\\s*:\\s*\"([^\"]+)\"").find(body)?.groupValues?.get(1)
        if (!url.isNullOrBlank()) return@withContext url.trim()
    }
    DEFAULT_ENDPOINT
}

actual suspend fun uploadDiagnosticPackage(
    filePath: String,
    note: String,
    device: String,
): FeedbackUpload = withContext(Dispatchers.IO) {
    val file = File(filePath)
    if (!file.isFile) return@withContext FeedbackUpload.Failed("找不到诊断包：$filePath")

    // 公开通道优先：任何用户都不需要 token
    val endpoint = feedbackEndpoint()
    if (!endpoint.isNullOrBlank()) {
        when (val r = postToWorker(endpoint, file, note, device)) {
            is WorkerResult.Ok -> return@withContext FeedbackUpload.Ok(r.path)
            is WorkerResult.Fail -> {
                val token = loadFeedbackToken()
                if (token.isBlank()) {
                    return@withContext FeedbackUpload.Failed("中转上传失败：${r.reason}")
                }
            }
        }
    }

    val token = loadFeedbackToken()
    if (token.isBlank()) return@withContext FeedbackUpload.Failed("未配置上传通道")
    when (val r = putToGithub(file, note, device, token)) {
        is WorkerResult.Ok -> FeedbackUpload.Ok(r.path)
        is WorkerResult.Fail -> FeedbackUpload.Failed("直传失败：${r.reason}")
    }
}

private sealed interface WorkerResult {
    data class Ok(val path: String) : WorkerResult
    data class Fail(val reason: String) : WorkerResult
}

private fun postToWorker(endpoint: String, file: File, note: String, device: String): WorkerResult {
    return try {
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
        if (code !in 200..299) {
            WorkerResult.Fail("HTTP $code ${body.take(120)}")
        } else {
            val path = Regex("\"file\"\\s*:\\s*\"([^\"]+)\"").find(body)?.groupValues?.get(1)
            WorkerResult.Ok(path ?: "feedback/")
        }
    } catch (t: Throwable) {
        WorkerResult.Fail(t.javaClass.simpleName + " " + (t.message ?: ""))
    }
}

/** 兜底：本地配了 token 就直接调 GitHub Contents API。 */
private fun putToGithub(file: File, note: String, device: String, token: String): WorkerResult {
    val stamp = java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US).format(java.util.Date())
    val safe = file.name.replace(Regex("[^\\w.\\-]"), "_")
    val path = "feedback/${stamp}_$safe"
    val meta = "机型: $device\n上传: $stamp\n\n问题描述:\n${note.ifBlank { "（未填写）" }}\n"

    fun put(p: String, bytes: ByteArray): String? {
        val conn = URL("https://api.github.com/repos/$FEEDBACK_REPO/contents/$p").openConnection() as HttpURLConnection
        return try {
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
            val body = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code in 200..299) null else "HTTP $code ${body.take(120)}"
        } catch (t: Throwable) {
            t.javaClass.simpleName + " " + (t.message ?: "")
        } finally {
            conn.disconnect()
        }
    }

    put(path, file.readBytes())?.let { return WorkerResult.Fail(it) }
    put(path.replace(Regex("\\.[^.]+$"), "") + ".txt", meta.toByteArray())
        ?.let { return WorkerResult.Fail("描述文件：$it") }
    return WorkerResult.Ok(path)
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

/** 复制到 files/share 再用 FileProvider 分享：/sdcard/Download 不在 App 可直接分享的范围内。 */
actual fun shareDiagnosticPackage(filePath: String): Boolean {
    val ctx = AndroidBackgroundPicker.appContextOrNull() ?: return false
    return runCatching {
        val src = File(filePath)
        if (!src.isFile) return false
        val dir = File(ctx.filesDir, "share").apply { mkdirs() }
        val dst = File(dir, src.name)
        src.copyTo(dst, overwrite = true)
        val uri = androidx.core.content.FileProvider.getUriForFile(
            ctx, ctx.packageName + ".fileprovider", dst
        )
        val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
            type = "application/octet-stream"
            putExtra(android.content.Intent.EXTRA_STREAM, uri)
            putExtra(android.content.Intent.EXTRA_SUBJECT, "诊断包 " + dst.name)
            addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val chooser = android.content.Intent.createChooser(intent, "发送诊断包").apply {
            addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        ctx.startActivity(chooser)
        true
    }.getOrDefault(false)
}
