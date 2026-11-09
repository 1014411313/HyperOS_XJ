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
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
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
import com.hyperos.xj.runtime.loadAppBackgroundImage
import com.hyperos.xj.runtime.loadBuiltinAppBackground
import com.hyperos.xj.runtime.loadLaunchFlags
import com.hyperos.xj.runtime.saveLaunchFlags
import com.hyperos.xj.ui.AppMediaBackground
import com.hyperos.xj.ui.BottomBar
import com.hyperos.xj.ui.LaunchPermissionDialog
import com.hyperos.xj.ui.LaunchPermissionDialog
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
import com.kyant.backdrop.effects.blur
import kotlinx.coroutines.delay

private val tabLabels = listOf("主页", "温控", "电池", "外观", "关于")

@Composable
fun App() {
    val backdrop = rememberLayerBackdrop()
    val bridge = remember { createToolboxBridge() }
    var selectedTab by remember { mutableIntStateOf(0) }
    var status by remember { mutableStateOf(DeviceStatus()) }
    var appearance by remember { mutableStateOf(AppearanceStore.load()) }
    var showLaunchPerms by remember { mutableStateOf(!loadLaunchFlags().dismissed) }

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
    // 字体缩放拖动时若每帧改 LocalDensity，整页会不断重排、滑块失去跟手（表现为拉不动）；停下来再应用
    var appliedFontScale by remember { mutableStateOf(appearance.fontScale) }
    LaunchedEffect(appearance.fontScale) {
        delay(320L)
        appliedFontScale = appearance.fontScale
    }
    val scaledDensity = Density(
        density = baseDensity.density,
        fontScale = baseDensity.fontScale * appliedFontScale.coerceIn(0.7f, 1.6f)
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
                    // 顶部预留：标题略高于模糊层底部但不被糊住（滑动时仍自然穿入）
                    top = statusTop + 16.dp,
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

            // 顶部渐变模糊：直接对「整层背景的模糊副本」做 alpha 遮罩，只在顶部露出。
            // 不能用局部矩形的 drawBackdrop——矩形边界会让 blur 采样 clamp，永远在边界留一条细线。
            // 背景是静态图/壁纸时才开（全屏模糊每帧重算，GIF/视频会卡）。
            if (appearance.bgKind == "image" || appearance.bgKind == "wallpaper") {
                Box(
                    Modifier
                        .fillMaxSize()
                        .blur(32.dp)
                        .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                        .drawWithContent {
                            drawContent()
                            drawRect(
                                brush = Brush.verticalGradient(
                                    0f to Color.White,
                                    0.045f to Color.White.copy(alpha = 0.55f),
                                    0.10f to Color.White.copy(alpha = 0f)
                                ),
                                blendMode = BlendMode.DstIn
                            )
                        }
                ) {
                    AppBackground(Modifier.fillMaxSize())
                }
            }
            // 单独的暗化渐变（不带模糊，所以无边界）：抬高状态栏图标与下方文字的对比度
            Box(
                Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .height(statusTop + 22.dp)
                    .background(
                        Brush.verticalGradient(
                            0f to Color.Black.copy(alpha = 0.30f),
                            0.6f to Color.Black.copy(alpha = 0.10f),
                            1f to Color.Transparent
                        )
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

            if (showLaunchPerms) {
                LaunchPermissionDialog(onDismiss = { noMore ->
                    if (noMore) saveLaunchFlags(loadLaunchFlags().copy(dismissed = true))
                    showLaunchPerms = false
                })
            }
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

    // 刚选完的相册图（内存位图）优先：bgKind=image + bgPath 为空时不能回落到内置插画，否则用户选完看不到自己的图
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

    // 内置/文件图片背景：bgPath 为空用 APK 内置插画（首次安装即生效），否则读文件
    if (appearance.bgKind == "image") {
        val imagePath = appearance.bgPath
        var fileBitmap by remember(imagePath) { mutableStateOf<ImageBitmap?>(null) }
        LaunchedEffect(imagePath) {
            fileBitmap = if (imagePath.isEmpty()) {
                loadBuiltinAppBackground()
            } else {
                loadAppBackgroundImage(imagePath)
            }
        }
        val bitmap = fileBitmap
        if (bitmap != null) {
            Box(modifier.blur(appearance.bgBlur.coerceIn(0f, 20f).dp)) {
                Image(
                    bitmap = bitmap,
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
