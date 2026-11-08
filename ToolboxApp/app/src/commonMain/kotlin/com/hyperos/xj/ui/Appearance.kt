package com.hyperos.xj.ui

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap

val AccentPalette = listOf(
    Color(0xFFF59E0B),
    Color(0xFFEC4899),
    Color(0xFF38BDF8),
    Color(0xFF00E5FF),
    Color(0xFF00FF88),
    Color(0xFFFFAA00),
    Color(0xFF166F91),
    Color(0xFFB9E8EA),
)

val SwatchPalette = listOf(
    Color(0xFF3AA6FF),
    Color(0xFF4EE1C0),
    Color(0xFFFF9F45),
    Color(0xFFFF5C7A),
    Color(0xFFB28CFF),
    Color(0xFFE7E9EE),
)

data class AppearanceSettings(
    val themeIndex: Int = 0,
    val glassMode: GlassMode = GlassMode.Liquid,
    val fontScale: Float = 1f,
    val cardScale: Float = 1f,
    val cardCorner: Float = 25f,
    val cardAlpha: Float = 0f,
    val cardBlur: Float = 2f,
    val cardDistortion: Float = 121f,
    val cardTexture: Boolean = true,
    val smoothMode: Boolean = false,
    val hideBarText: Boolean = false,
    val barWidth: Float = 84f,
    val barHeight: Float = 60f,
    val barAlpha: Float = 0.25f,
    val barSolid: Float = 0f,
    val barColorIndex: Int = 0,
    val barBlur: Float = 3f,
    val wallpaperIndex: Int = 0,
    val bgBlur: Float = 2f,
    val customBg: Boolean = false,
    val bgImage: ImageBitmap? = null,
    val bgKind: String = "image",   // wallpaper / image / gif / video
    val bgPath: String = "",        // 空 = 用 APK 内置默认背景；否则是 gif/video/图片的绝对路径
)

val LocalAppearance = staticCompositionLocalOf { AppearanceSettings() }

val LocalAccentColor = staticCompositionLocalOf { AccentPalette[0] }

fun AppearanceSettings.accentColor(): Color =
    AccentPalette[themeIndex.coerceIn(0, AccentPalette.size - 1)]
