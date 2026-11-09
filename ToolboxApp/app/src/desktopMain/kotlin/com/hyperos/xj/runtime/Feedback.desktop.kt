package com.hyperos.xj.runtime

actual suspend fun feedbackEndpoint(): String? = null

actual suspend fun uploadDiagnosticPackage(filePath: String, note: String, device: String): String? = null

actual fun loadFeedbackToken(): String = ""

actual fun saveFeedbackToken(token: String) = Unit
