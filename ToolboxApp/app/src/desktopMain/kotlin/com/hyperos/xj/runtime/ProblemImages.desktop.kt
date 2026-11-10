package com.hyperos.xj.runtime

actual suspend fun pickProblemImages(maxCount: Int): List<String> = emptyList()

actual fun problemImagesDir(): String = ""

actual fun clearProblemImages(): Int = 0
