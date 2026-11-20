package com.hyperos.xj.runtime

import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.staticCompositionLocalOf

object AppVisibility {
    val visible = mutableStateOf(false)
    val statusRevision = mutableIntStateOf(0)
}

val LocalAppVisible = staticCompositionLocalOf { true }
val LocalPreviewVisible = staticCompositionLocalOf<(Boolean) -> Unit> { {} }

@Composable
expect fun appVisible(): Boolean
