package com.hyperos.xj.runtime

actual fun loadFeedbackToken(): String = ""

actual fun saveFeedbackToken(token: String) = Unit

actual suspend fun feedbackEndpoint(): String? = null

actual suspend fun uploadDiagnosticPackage(filePath: String, note: String, device: String): FeedbackUpload =
    FeedbackUpload.Failed("桌面端不支持上传")

actual fun shareDiagnosticPackage(filePath: String): Boolean = false
