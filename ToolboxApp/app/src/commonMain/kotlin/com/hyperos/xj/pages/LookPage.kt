package com.hyperos.xj.pages

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.findRootCoordinates
import com.hyperos.xj.runtime.LocalAppVisible
import com.hyperos.xj.runtime.LocalPreviewVisible
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hyperos.xj.runtime.DeviceStatus
import com.hyperos.xj.runtime.Engine
import com.hyperos.xj.runtime.ToolboxRuntimeBridge
import com.hyperos.xj.runtime.decodeBackgroundImage
import com.hyperos.xj.runtime.isGifBytes
import com.hyperos.xj.runtime.pickBackgroundGif
import com.hyperos.xj.runtime.pickBackgroundImageBytes
import com.hyperos.xj.runtime.pickBackgroundVideo
import com.hyperos.xj.runtime.readRearCardBgImageBytes
import com.hyperos.xj.runtime.rearBgGifPreviewPath
import com.hyperos.xj.runtime.rearDefaultBgGifPreviewPath
import com.hyperos.xj.runtime.saveAppBackgroundImage
import com.hyperos.xj.runtime.writeRearBackgroundImage
import com.hyperos.xj.ui.ActionButton
import com.hyperos.xj.ui.AppearanceSettings
import com.hyperos.xj.ui.ChipSelector
import com.hyperos.xj.ui.CollapsibleCard
import com.hyperos.xj.ui.GlassCard
import com.hyperos.xj.ui.GlassChip
import com.hyperos.xj.ui.GlassMode
import com.hyperos.xj.ui.RearScreenPreview
import com.hyperos.xj.ui.SectionLabel
import com.hyperos.xj.ui.SliderField
import com.hyperos.xj.ui.ToggleLine
import com.hyperos.xj.ui.LocalPageInsets
import com.hyperos.xj.ui.PageHeader
import com.hyperos.xj.ui.XJ
import com.hyperos.xj.ui.accentColor
import com.hyperos.xj.ui.showToast
import com.kyant.backdrop.Backdrop
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

private val rearFields = listOf("温度", "电量", "电压", "电流", "功率", "状态", "设计容量", "满充容量", "健康度", "循环", "伪装", "协议", "剩余容量", "电池寿命")
private val rearFieldIds = listOf("temp", "soc", "volt", "cur", "power", "status", "design", "full", "health", "cycle", "fake", "proto", "rm", "soh")
private val rearConfPaths = listOf(
    "/storage/emulated/0/Android/data/com.hyperos.xj/files/rear.conf",
    "/data/local/tmp/xj_rear.conf",
)
private const val rearBgDefaultEnginePath = "/data/local/tmp/xj_rear_bg_default.gif"

@Composable
fun LookPage(
    backdrop: Backdrop,
    bridge: ToolboxRuntimeBridge,
    status: DeviceStatus,
    appearance: AppearanceSettings,
    onAppearanceChange: (AppearanceSettings) -> Unit
) {
    var rearEnabled by remember { mutableStateOf(status.rearScreen) }
    var rearTouched by remember { mutableStateOf(false) }
    var rearSelected by remember { mutableStateOf(setOf(0, 1, 2, 3)) }
    var rearFont by remember { mutableIntStateOf(1) }
    var rearCardBg by remember { mutableStateOf<String?>(null) }
    var rearCardBgImage by remember { mutableStateOf<ImageBitmap?>(null) }
    var rearCardBgGifPath by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val appVisible = LocalAppVisible.current
    var previewInWindow by remember { mutableStateOf(false) }
    val reportPreview = LocalPreviewVisible.current
    LaunchedEffect(appVisible, previewInWindow) { reportPreview(appVisible && previewInWindow) }
    DisposableEffect(Unit) { onDispose { reportPreview(false) } }
    // 图片选择异步返回后组合早已重组，必须读最新快照，否则会覆盖期间调过的 bgBlur 等字段
    val currentAppearance by rememberUpdatedState(appearance)

    fun pushRearConfig(selected: Set<Int>, font: Int, accent: Color) {
        scope.launch {
            val result = bridge.execResult(Engine.rearConfig(fieldsValue(selected), fontValue(font), accentHex(accent)))
            showToast(if (result.success) "背屏参数已推送" else "失败：背屏参数推送")
        }
    }

    LaunchedEffect(status.rearScreen, rearTouched) {
        if (!rearTouched) rearEnabled = status.rearScreen
    }

    // 无背屏机型也要能在预览里看到背景（与 bug-044 一致），不按 rearScreenSupported 跳过
    LaunchedEffect(bridge) {
        rearCardBg = readRearCardBg(bridge)
        val currentBg = rearCardBg
        if (currentBg?.startsWith("image:") == true) {
            // 副本可能因清数据/换机丢失：按背景类型选定补铺源，默认图与用户图分文件，不能混用（bug-070）；
            // PNG 背景不能回退 GIF 副本（那是旧动图）
            val gifPath = when {
                currentBg == "image:$rearBgDefaultEnginePath" -> rearDefaultBgGifPreviewPath(true)
                currentBg.endsWith(".gif") -> rearBgGifPreviewPath(true)
                else -> null
            }
            if (gifPath != null) {
                rearCardBgGifPath = gifPath
                rearCardBgImage = null
            } else {
                rearCardBgImage = readRearCardBgImageBytes()?.let { decodeBackgroundImage(it) }
            }
        } else if (currentBg == null) {
            // bg 为空即默认状态：预览必须回落内置默认动图，不画背景就是黑屏（bug-070）
            rearCardBgGifPath = rearDefaultBgGifPreviewPath(true)
            rearCardBgImage = null
        }
    }

    Column(
        Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(
                start = 18.dp,
                end = 18.dp,
                top = LocalPageInsets.current.top,
                bottom = LocalPageInsets.current.bottom
            ),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        PageHeader(status)
        CollapsibleCard(backdrop, title = "主题颜色", initiallyExpanded = true, modifier = Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                BasicText("调色板", style = TextStyle(color = XJ.TextSecondary, fontSize = 12.sp))
                Spacer(Modifier.weight(1f))
                Box(
                    Modifier
                        .size(24.dp)
                        .clip(CircleShape)
                        .background(appearance.accentColor())
                        .border(2.dp, Color.White, CircleShape)
                )
            }
            Spacer(Modifier.height(10.dp))
            ThemeWheel(backdrop, appearance.accentColor()) { color ->
                onAppearanceChange(appearance.copy(themeColorArgb = color.toArgb().toLong() and 0xFFFFFFFFL))
                pushRearConfig(rearSelected, rearFont, color)
            }
        }

        CollapsibleCard(
            backdrop = backdrop,
            title = "背屏",
            subtitle = if (status.rearScreenSupported) "支持背屏机型投放电池卡片" else "本机无背屏：以下仅作预览，换机型后生效",
            initiallyExpanded = false,
            modifier = Modifier.fillMaxWidth()
        ) {
            ToggleLine(
                backdrop,
                "背屏电池卡片",
                "支持背屏机型投放电池卡片",
                rearEnabled,
                onCheckedChange = {
                    rearTouched = true
                    rearEnabled = it
                    scope.launch {
                        val result = bridge.execResult(Engine.rear(it))
                        showToast(
                            when {
                                !result.success -> "失败：背屏电池卡片"
                                it -> "背屏电池卡片 已开启"
                                else -> "背屏电池卡片 已关闭"
                            }
                        )
                    }
                }
            )
            Spacer(Modifier.height(10.dp))
            if (rearEnabled) {
                DisposableEffect(Unit) { onDispose { previewInWindow = false } }
                RearScreenPreview(
                    accent = appearance.accentColor(),
                    status = status,
                    fields = rearSelected.toList(),
                    modifier = Modifier.fillMaxWidth().onGloballyPositioned { coordinates ->
                        val bounds = coordinates.boundsInWindow()
                        val root = coordinates.findRootCoordinates().boundsInWindow()
                        previewInWindow = bounds.width > 0 && bounds.height > 0 && bounds.overlaps(root)
                    },
                    animate = appVisible && previewInWindow,
                    fontScale = when (rearFont) { 0 -> 0.85f; 2 -> 1.2f; else -> 1f },
                    bgColor = rearCardBg ?: "",
                    bgImage = rearCardBgImage,
                    bgGifPath = rearCardBgGifPath
                )
                Spacer(Modifier.height(10.dp))
            }
            BasicText("四角显示项，最多选 4 项", style = TextStyle(color = XJ.TextSecondary, fontSize = 11.sp))
            Spacer(Modifier.height(6.dp))
            Row(
                Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(7.dp)
            ) {
                rearFields.forEachIndexed { index, label ->
                    GlassChip(
                        backdrop = backdrop,
                        label = label,
                        selected = index in rearSelected,
                        onClick = {
                            rearSelected =
                                if (index in rearSelected) rearSelected - index
                                else if (rearSelected.size < 4) rearSelected + index
                                else rearSelected
                            pushRearConfig(rearSelected, rearFont, appearance.accentColor())
                        }
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
            ChipSelector(
                backdrop = backdrop,
                title = "背屏字号",
                options = listOf("小", "中", "大"),
                selectedIndex = rearFont,
                onSelect = {
                    rearFont = it
                    pushRearConfig(rearSelected, it, appearance.accentColor())
                }
            )
            Spacer(Modifier.height(14.dp))
            BasicText("卡片背景，从相册选择图片", style = TextStyle(color = XJ.TextSecondary, fontSize = 11.sp))
            Spacer(Modifier.height(6.dp))
            ActionButton(
                backdrop = backdrop,
                text = if (rearCardBg?.startsWith("image:") == true) "更换卡片图片" else "选择卡片图片",
                onClick = {
                    scope.launch {
                        pickBackgroundImageBytes()?.let { bytes ->
                            val enginePath = writeRearBackgroundImage(bytes)
                            if (enginePath != null) {
                                val value = "image:$enginePath"
                                rearCardBg = value
                                if (isGifBytes(bytes)) {
                                    rearCardBgGifPath = rearBgGifPreviewPath(false)
                                    rearCardBgImage = null
                                } else {
                                    rearCardBgGifPath = null
                                    rearCardBgImage = decodeBackgroundImage(bytes)
                                }
                                val result = bridge.execResult(Engine.rearSetBg(value))
                                showToast(if (result.success) "卡片背景已设置" else "失败：设置卡片背景")
                            }
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth()
            )
            if (rearCardBg != null) {
                Spacer(Modifier.height(8.dp))
                ActionButton(
                    backdrop = backdrop,
                    text = "恢复默认背景",
                    onClick = {
                        rearCardBg = null
                        rearCardBgImage = null
                        rearCardBgGifPath = null
                        scope.launch {
                            // 立即把预览拉回默认动图，不等下次进页
                            rearCardBgGifPath = rearDefaultBgGifPreviewPath(true)
                            val result = bridge.execResult(Engine.rearSetBg(""))
                            showToast(if (result.success) "已恢复默认背景" else "失败：恢复默认背景")
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    secondary = true
                )
            }
        }

        CollapsibleCard(backdrop, title = "玻璃与动效", initiallyExpanded = false, modifier = Modifier.fillMaxWidth()) {
            ChipSelector(
                backdrop = backdrop,
                title = "玻璃材质",
                options = listOf("液态玻璃", "毛玻璃"),
                selectedIndex = if (appearance.glassMode == GlassMode.Liquid) 0 else 1,
                onSelect = { onAppearanceChange(appearance.copy(glassMode = if (it == 0) GlassMode.Liquid else GlassMode.Frosted)) }
            )
        }

        CollapsibleCard(backdrop, title = "缩放与卡片", initiallyExpanded = false, modifier = Modifier.fillMaxWidth()) {
            SliderField(backdrop, "界面字体缩放", fmt(appearance.fontScale), 0.8f..1.5f, { appearance.fontScale }, { onAppearanceChange(appearance.copy(fontScale = it)) })
            Spacer(Modifier.height(12.dp))
            SliderField(backdrop, "卡片圆角", "${appearance.cardCorner.toInt()}px", 0f..40f, { appearance.cardCorner }, { onAppearanceChange(appearance.copy(cardCorner = it)) })
            if (appearance.glassMode == GlassMode.Liquid) {
                Spacer(Modifier.height(12.dp))
                SliderField(backdrop, "卡片模糊度", "${appearance.cardBlur.toInt()}", 0f..44f, { appearance.cardBlur }, { onAppearanceChange(appearance.copy(cardBlur = it)) })
                Spacer(Modifier.height(12.dp))
                SliderField(backdrop, "卡片扭曲度", "${appearance.cardDistortion.toInt()}", 0f..150f, { appearance.cardDistortion }, { onAppearanceChange(appearance.copy(cardDistortion = it)) })
            }
            if (appearance.glassMode == GlassMode.Frosted) {
                Spacer(Modifier.height(12.dp))
                SliderField(backdrop, "毛玻璃模糊度", "${appearance.frostedBlur.toInt()}", 0f..60f, { appearance.frostedBlur }, { onAppearanceChange(appearance.copy(frostedBlur = it)) })
                Spacer(Modifier.height(12.dp))
                SliderField(backdrop, "毛玻璃折射强度", "${appearance.frostedRefract.toInt()}", 0f..40f, { appearance.frostedRefract }, { onAppearanceChange(appearance.copy(frostedRefract = it)) })
            }
        }

        CollapsibleCard(backdrop, title = "底栏", initiallyExpanded = false, modifier = Modifier.fillMaxWidth()) {
            ToggleLine(backdrop, "隐藏底栏文字", null, appearance.hideBarText, onCheckedChange = { onAppearanceChange(appearance.copy(hideBarText = it)) })
            Spacer(Modifier.height(12.dp))
            SliderField(backdrop, "底栏宽度", "${appearance.barWidth.toInt()}%", 48f..100f, { appearance.barWidth }, { onAppearanceChange(appearance.copy(barWidth = it)) })
            Spacer(Modifier.height(12.dp))
            SliderField(backdrop, "底栏高度", "${appearance.barHeight.toInt()}px", 44f..84f, { appearance.barHeight }, { onAppearanceChange(appearance.copy(barHeight = it)) })
            Spacer(Modifier.height(12.dp))
            SliderField(backdrop, "底栏透明度", fmt(appearance.barAlpha), 0.04f..0.6f, { appearance.barAlpha }, { onAppearanceChange(appearance.copy(barAlpha = it)) })
            Spacer(Modifier.height(12.dp))
            SliderField(backdrop, "底栏模糊度", "${appearance.barBlur.toInt()}", 0f..20f, { appearance.barBlur }, { onAppearanceChange(appearance.copy(barBlur = it)) })
        }

        CollapsibleCard(backdrop, title = "背景", initiallyExpanded = false, modifier = Modifier.fillMaxWidth()) {
            Spacer(Modifier.height(12.dp))
            ActionButton(
                backdrop = backdrop,
                text = if (appearance.bgImage != null) "更换相册图片" else "选择相册图片",
                onClick = {
                    scope.launch {
                        pickBackgroundImageBytes()?.let { bytes ->
                            decodeBackgroundImage(bytes)?.let { bitmap ->
                                onAppearanceChange(currentAppearance.copy(bgImage = bitmap, customBg = true, bgKind = "image", bgPath = ""))
                                // 落盘：重启后 bgImage（内存位图）消失，靠 bgPath 继续显示用户图
                                val saved = saveAppBackgroundImage(bitmap)
                                if (saved != null) {
                                    onAppearanceChange(currentAppearance.copy(bgImage = bitmap, customBg = true, bgKind = "image", bgPath = saved))
                                }
                            }
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(12.dp))
            ActionButton(
                backdrop = backdrop,
                text = if (appearance.bgKind == "gif") "更换 GIF 动图" else "选择 GIF 动图",
                onClick = {
                    scope.launch {
                        pickBackgroundGif()?.let { path ->
                            onAppearanceChange(currentAppearance.copy(bgKind = "gif", bgPath = path, customBg = false))
                            showToast("GIF 背景已设置")
                        } ?: showToast("失败：未选择 GIF")
                    }
                },
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(12.dp))
            ActionButton(
                backdrop = backdrop,
                text = if (appearance.bgKind == "video") "更换背景视频" else "选择背景视频",
                onClick = {
                    scope.launch {
                        pickBackgroundVideo()?.let { path ->
                            onAppearanceChange(currentAppearance.copy(bgKind = "video", bgPath = path, customBg = false))
                            showToast("视频背景已设置")
                        } ?: showToast("失败：未选择视频")
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                secondary = true
            )
            Spacer(Modifier.height(12.dp))
            ActionButton(
                backdrop = backdrop,
                text = "恢复默认背景",
                onClick = {
                    onAppearanceChange(currentAppearance.copy(bgImage = null, customBg = true, bgKind = "image", bgPath = ""))
                    showToast("已恢复默认背景")
                },
                modifier = Modifier.fillMaxWidth(),
                secondary = true
            )
            Spacer(Modifier.height(12.dp))
            SliderField(backdrop, "背景模糊度", "${appearance.bgBlur.toInt()}", 0f..20f, { appearance.bgBlur }, { onAppearanceChange(appearance.copy(bgBlur = it)) })
        }

        GlassCard(backdrop, Modifier.fillMaxWidth()) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                BasicText("恢复默认外观", style = TextStyle(color = XJ.TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Medium))
                BasicText("重置全部设置", style = TextStyle(color = XJ.TextSecondary, fontSize = 11.sp))
            }
            Spacer(Modifier.height(10.dp))
            ActionButton(backdrop, "全局恢复默认", { onAppearanceChange(AppearanceSettings()) }, Modifier.fillMaxWidth(), secondary = true)
        }
    }
}

@Composable
private fun ThemeWheel(backdrop: Backdrop, current: Color, onPick: (Color) -> Unit) {
    val hsv = rgbToHsv(current)
    var hue by remember { mutableFloatStateOf(hsv[0]) }
    var sat by remember { mutableFloatStateOf(hsv[1]) }
    var value by remember { mutableFloatStateOf(hsv[2].coerceIn(0.2f, 1f)) }
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Canvas(
            Modifier
                .size(180.dp)
                .pointerInput(Unit) {
                    detectTapGestures { pos ->
                        val radius = size.width / 2f
                        val dx = pos.x - radius
                        val dy = pos.y - radius
                        sat = (kotlin.math.hypot(dx.toDouble(), dy.toDouble()).toFloat() / radius).coerceIn(0f, 1f)
                        var h = Math.toDegrees(kotlin.math.atan2(dy.toDouble(), dx.toDouble())).toFloat()
                        if (h < 0f) h += 360f
                        hue = h % 360f
                        onPick(hsvColor(hue, sat, value))
                    }
                }
        ) {
            val radius = size.minDimension / 2f
            drawCircle(
                brush = Brush.sweepGradient(List(361) { hsvColor(it.toFloat(), 1f, 1f) }),
                radius = radius
            )
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(Color.White, Color.Transparent),
                    center = center,
                    radius = radius
                ),
                radius = radius
            )
        }
        Spacer(Modifier.height(10.dp))
        SliderField(backdrop, "颜色明度", fmt(value), 0.2f..1f, { value }, { v ->
            value = v
            onPick(hsvColor(hue, sat, v))
        })
    }
}

private fun hsvColor(h: Float, s: Float, v: Float): Color {
    val c = v * s
    val x = c * (1f - kotlin.math.abs((h / 60f) % 2f - 1f))
    val m = v - c
    val (r, g, b) = when {
        h < 60f -> Triple(c, x, 0f)
        h < 120f -> Triple(x, c, 0f)
        h < 180f -> Triple(0f, c, x)
        h < 240f -> Triple(0f, x, c)
        h < 300f -> Triple(x, 0f, c)
        else -> Triple(c, 0f, x)
    }
    return Color((r + m).coerceIn(0f, 1f), (g + m).coerceIn(0f, 1f), (b + m).coerceIn(0f, 1f))
}

private fun rgbToHsv(color: Color): FloatArray {
    val r = color.red
    val g = color.green
    val b = color.blue
    val max = maxOf(r, g, b)
    val min = minOf(r, g, b)
    val d = max - min
    var h = when {
        d == 0f -> 0f
        max == r -> 60f * (((g - b) / d) % 6f)
        max == g -> 60f * (((b - r) / d) + 2f)
        else -> 60f * (((r - g) / d) + 4f)
    }
    if (h < 0f) h += 360f
    return floatArrayOf(h % 360f, if (max == 0f) 0f else d / max, max)
}

private fun fmt(value: Float): String {
    val scaled = (value * 100f).toInt()
    return "${scaled / 100}.${scaled % 100}"
}

private fun fieldsValue(selected: Set<Int>): String =
    selected.toList().map { rearFieldIds[it] }.joinToString(",")

private fun fontValue(font: Int): String = when (font) { 0 -> "0.85"; 2 -> "1.2"; else -> "1.0" }

private fun accentHex(color: Color): String {
    fun channel(value: Float): String = (value.coerceIn(0f, 1f) * 255f).roundToInt().toString(16).padStart(2, '0')
    return "#" + channel(color.red) + channel(color.green) + channel(color.blue)
}

private fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

private suspend fun readRearCardBg(bridge: ToolboxRuntimeBridge): String? {
    for (path in rearConfPaths) {
        val text = runCatching { bridge.exec("cat ${shellQuote(path)} 2>/dev/null") }.getOrNull() ?: continue
        val bg = text.lineSequence()
            .map { it.trim() }
            .firstOrNull { it.startsWith("bg=") }
            ?.removePrefix("bg=")
            ?.trim()
        if (!bg.isNullOrEmpty()) return bg
    }
    return null
}
