package com.hyperos.xj.runtime

actual suspend fun feedbackEndpoint(): String? = null

actual suspend fun uploadDiagnosticPackage(filePath: String, note: String, device: String): String? = null
