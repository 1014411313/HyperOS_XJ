package com.hyperos.xj

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.statusBars
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.hyperos.xj.pages.AboutPage
import com.hyperos.xj.pages.BatteryPage
import com.hyperos.xj.pages.HomePage
import com.hyperos.xj.pages.LookPage
import com.hyperos.xj.pages.ThermalPage
import com.hyperos.xj.runtime.AppearanceStore
import com.hyperos.xj.runtime.DeviceStatus
import com.hyperos.xj.runtime.createToolboxBridge
import com.hyperos.xj.ui.AppMediaBackground
import com.hyperos.xj.ui.BottomBar
import com.hyperos.xj.ui.LocalAccentColor
import com.hyperos.xj.ui.LocalAppearance
import com.hyperos.xj.ui.LocalGlassMode
import com.hyperos.xj.ui.LocalPageInsets
import com.hyperos.xj.ui.LocalSelectedTab
import com.hyperos.xj.ui.PageInsets
import com.hyperos.xj.ui.XJ
import com.hyperos.xj.ui.accentColor
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.shapes.RoundedRectangle
import kotlinx.coroutines.delay

private val tabLabels = listOf("主页", "温控", "电池", "外观", "关于")

@Composable
fun App() {
    val backdrop = rememberLayerBackdrop()
    val bridge = remember { createToolboxBridge() }
    var selectedTab by remember { mutableIntStateOf(0) }
    var status by remember { mutableStateOf(DeviceStatus()) }
    var appearance by remember { mutableStateOf(AppearanceStore.load()) }

    LaunchedEffect(Unit) {
        while (true) {
            status = bridge.readStatus()
            delay(700L)
        }
    }

    LaunchedEffect(appearance) {
        AppearanceStore.save(appearance)
    }

    val baseDensity = LocalDensity.current
    val scaledDensity = Density(
        density = baseDensity.density,
        fontScale = baseDensity.fontScale * appearance.fontScale.coerceIn(0.7f, 1.6f)
    )

    CompositionLocalProvider(
        LocalDensity provides scaledDensity,
        LocalAppearance provides appearance,
        LocalAccentColor provides appearance.accentColor(),
        LocalGlassMode provides appearance.glassMode,
        LocalSelectedTab provides selectedTab
    ) {
        val density = LocalDensity.current
        // 页面滚动区铺满全屏，标题区在页面内容里随卡片一起滚走；底栏悬浮但不再加独立分割带。
        var bottomHostPx by remember { mutableIntStateOf(0) }
        val bottomInset = if (bottomHostPx > 0) with(density) { bottomHostPx.toDp() } else 96.dp
        val statusTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
        val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

        Box(Modifier.fillMaxSize()) {
            AppBackground(Modifier.fillMaxSize().layerBackdrop(backdrop))

            CompositionLocalProvider(
                LocalPageInsets provides PageInsets(
                    // 顶部预留 = 状态栏 + 渐变模糊覆盖高度：标题初始位置落在模糊层下沿，不被打糊；滑动时仍会自然穿入
                    top = statusTop + 44.dp,
                    bottom = bottomInset + navBottom + 6.dp
                )
            ) {
                Box(Modifier.fillMaxSize()) {
                    when (selectedTab) {
                        0 -> HomePage(backdrop, bridge, status)
                        1 -> ThermalPage(backdrop, bridge, status)
                        2 -> BatteryPage(backdrop, bridge, status)
                        3 -> LookPage(
                            backdrop = backdrop,
                            bridge = bridge,
                            status = status,
                            appearance = appearance,
                            onAppearanceChange = { appearance = it }
                        )
                        else -> AboutPage(backdrop, bridge, status)
                    }
                }
            }

            // 状态栏无痕渐变模糊：两层不同半径叠加，覆盖高度大于状态栏，靠 alpha 渐变淡出；
            // 不在状态栏下沿切断模糊，所以不会出现「一道线 + 直接模糊」的硬边。
            Box(
                Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .height(statusTop + 44.dp)
                    .drawBackdrop(
                        backdrop = backdrop,
                        shape = { RoundedRectangle(0.dp) },
                        effects = { blur(16.dp.toPx()) },
                        onDrawSurface = {
                            drawRect(
                                Brush.verticalGradient(
                                    0f to Color.Black.copy(alpha = 0.26f),
                                    0.55f to Color.Black.copy(alpha = 0.10f),
                                    1f to Color.Transparent
                                )
                            )
                        }
                    )
            )
            Box(
                Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .height(statusTop + 10.dp)
                    .drawBackdrop(
                        backdrop = backdrop,
                        shape = { RoundedRectangle(0.dp) },
                        effects = { blur(28.dp.toPx()) },
                        onDrawSurface = {
                            drawRect(
                                Brush.verticalGradient(
                                    0f to Color.Black.copy(alpha = 0.34f),
                                    0.7f to Color.Black.copy(alpha = 0.12f),
                                    1f to Color.Transparent
                                )
                            )
                        }
                    )
            )

            BottomBar(
                selectedIndex = selectedTab,
                labels = tabLabels,
                onSelect = { selectedTab = it },
                backdrop = backdrop,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .onSizeChanged { bottomHostPx = it.height }
                    .padding(horizontal = 18.dp, vertical = 12.dp)
            )
        }
    }
}

private val wallpaperPalette = listOf(
    listOf(Color(0xFF0B1020), Color(0xFF12213B), Color(0xFF0A1324)),
    listOf(Color(0xFF061424), Color(0xFF10314A), Color(0xFF071426)),
    listOf(Color(0xFF14101F), Color(0xFF271A3D), Color(0xFF100D1D)),
    listOf(Color(0xFF1C1208), Color(0xFF3A2410), Color(0xFF160E07)),
)

@Composable
private fun AppBackground(modifier: Modifier = Modifier) {
    val appearance = LocalAppearance.current
    val accent = LocalAccentColor.current
    val bgImage = appearance.bgImage

    // GIF / 视频背景：壁纸作为底层兜底，媒体文件缺失或解码失败时自然透出，不会黑屏
    if (appearance.bgKind == "gif" || appearance.bgKind == "video") {
        val base = wallpaperPalette[appearance.wallpaperIndex.coerceIn(0, wallpaperPalette.size - 1)]
        Box(modifier.background(Brush.verticalGradient(base))) {
            AppMediaBackground(
                kind = appearance.bgKind,
                path = appearance.bgPath,
                blurDp = appearance.bgBlur,
                modifier = Modifier.fillMaxSize()
            )
        }
        return
    }

    if (appearance.customBg && bgImage != null) {
        Box(modifier.blur(appearance.bgBlur.coerceIn(0f, 20f).dp)) {
            Image(
                bitmap = bgImage,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.22f))
            )
        }
        return
    }

    val base = wallpaperPalette[appearance.wallpaperIndex.coerceIn(0, wallpaperPalette.size - 1)]
    Box(
        modifier.blur(appearance.bgBlur.coerceIn(0f, 20f).dp)
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Brush.verticalGradient(base))
        )
        Canvas(Modifier.fillMaxSize()) {
            drawCircle(
                brush = Brush.radialGradient(
                    listOf(accent.copy(alpha = 0.55f), Color.Transparent)
                ),
                radius = size.minDimension * 0.55f,
                center = Offset(size.width * 0.88f, size.height * 0.12f)
            )
            drawCircle(
                brush = Brush.radialGradient(
                    listOf(accent.copy(alpha = 0.32f), Color.Transparent)
                ),
                radius = size.minDimension * 0.5f,
                center = Offset(size.width * 0.08f, size.height * 0.42f)
            )
            drawCircle(
                brush = Brush.radialGradient(
                    listOf(XJ.Warm.copy(alpha = 0.34f), Color.Transparent)
                ),
                radius = size.minDimension * 0.5f,
                center = Offset(size.width * 0.95f, size.height * 0.72f)
            )
            drawCircle(
                brush = Brush.radialGradient(
                    listOf(Color(0xFFB28CFF).copy(alpha = 0.32f), Color.Transparent)
                ),
                radius = size.minDimension * 0.45f,
                center = Offset(size.width * 0.18f, size.height * 0.88f)
            )
        }
    }
}
