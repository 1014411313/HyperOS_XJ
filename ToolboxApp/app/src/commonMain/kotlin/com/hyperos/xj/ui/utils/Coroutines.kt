package com.hyperos.xj.ui.utils

import kotlinx.coroutines.delay

internal suspend fun awaitFrame() {
    delay(1000L / 60L)
}
