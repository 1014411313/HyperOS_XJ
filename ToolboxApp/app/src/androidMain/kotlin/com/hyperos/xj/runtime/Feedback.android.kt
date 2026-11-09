package com.hyperos.xj.runtime

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

// 与 update.sh 同源的地址列表：jsDelivr → Gitee 镜像 → GitHub raw
private val ENDPOINT_SOURCES = listOf(
    "https://cdn.jsdelivr.net/gh/1014411313/HyperOS_XJ@master/feedback_endpoint.json",
    "https://gitee.com/xiaomirezhale/HyperOS_XJ/raw/master/feedback_endpoint.json",
    "https://raw.githubusercontent.com/1014411313/HyperOS_XJ/master/feedback_endpoint.json",
)

actual suspend fun feedbackEndpoint(): String? = withContext(Dispatchers.IO) {
    for (src in ENDPOINT_SOURCES) {
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
    val endpoint = feedbackEndpoint() ?: return@withContext null
    val file = File(filePath)
    if (!file.isFile) return@withContext null
    runCatching {
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
        else Regex("\"url\"\\s*:\\s*\"([^\"]+)\"").find(body)?.groupValues?.get(1) ?: "已上传"
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
