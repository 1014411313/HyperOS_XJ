package com.hyperos.xj

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicText
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hyperos.xj.pages.AboutPage
import com.hyperos.xj.pages.BatteryPage
import com.hyperos.xj.pages.HomePage
import com.hyperos.xj.pages.LookPage
import com.hyperos.xj.pages.ThermalPage
import com.hyperos.xj.runtime.AppearanceStore
import com.hyperos.xj.runtime.DeviceStatus
import com.hyperos.xj.runtime.createToolboxBridge
import com.hyperos.xj.ui.BottomBar
import com.hyperos.xj.ui.LocalAccentColor
import com.hyperos.xj.ui.LocalAppearance
import com.hyperos.xj.ui.LocalGlassMode
import com.hyperos.xj.ui.XJ
import com.hyperos.xj.ui.accentColor
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
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
            delay(2000L)
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
        LocalGlassMode provides appearance.glassMode
    ) {
        Box(Modifier.fillMaxSize()) {
            AppBackground(Modifier.fillMaxSize().layerBackdrop(backdrop))

            Column(
                Modifier
                    .fillMaxSize()
                    .systemBarsPadding()
                    .padding(horizontal = 18.dp)
            ) {
                Spacer(Modifier.height(12.dp))
                Header(selectedTab, status)
                Spacer(Modifier.height(14.dp))

                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxWidth()
                ) {
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

            BottomBar(
                selectedIndex = selectedTab,
                labels = tabLabels,
                onSelect = { selectedTab = it },
                backdrop = backdrop,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(horizontal = 18.dp, vertical = 12.dp)
            )
        }
    }
}

@Composable
private fun Header(selectedTab: Int, status: DeviceStatus) {
    val accent = LocalAccentColor.current
    val pageTitle = when (selectedTab) {
        0 -> "HyperOS - 动态温控·解容"
        1 -> "温控"
        2 -> "电池"
        3 -> "显示外观"
        else -> "关于"
    }
    val pageSubtitle = when (selectedTab) {
        0 -> "监控入口 + 充电功率 + 设备信息"
        1 -> "档位 / 充电 / 游戏 / 旁路"
        2 -> "解容 / DTBO / 循环 / 电压"
        3 -> "主题 / 背屏卡片 / 玻璃 / 缩放 / 底栏 / 背景"
        else -> "作者 / 版本 / 帮助 / 维护"
    }
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            BasicText(
                text = pageTitle,
                style = TextStyle(color = XJ.TextPrimary, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            )
            BasicText(
                text = pageSubtitle,
                style = TextStyle(color = XJ.TextSecondary, fontSize = 11.sp)
            )
        }
        Spacer(Modifier.size(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(if (status.charging) accent else XJ.Warm)
            )
            Spacer(Modifier.size(6.dp))
            BasicText(
                text = status.status,
                style = TextStyle(color = XJ.TextPrimary, fontSize = 11.sp)
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
