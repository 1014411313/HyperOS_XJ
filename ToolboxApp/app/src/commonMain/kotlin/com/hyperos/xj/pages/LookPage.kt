package com.hyperos.xj.pages

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
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
import com.hyperos.xj.runtime.writeRearBackgroundImage
import com.hyperos.xj.ui.AccentPalette
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
import com.hyperos.xj.ui.SwatchPalette
import com.hyperos.xj.ui.ToggleLine
import com.hyperos.xj.ui.LocalPageInsets
import com.hyperos.xj.ui.XJ
import com.hyperos.xj.ui.accentColor
import com.hyperos.xj.ui.showToast
import com.kyant.backdrop.Backdrop
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

private val themeOptions = listOf("极光浅色", "全息流体", "暗黑霓虹", "幻彩极光", "赛博霓虹", "熔岩鎏金", "冰川琉璃", "月海流银")
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
                top = LocalPageInsets.current.top,
                bottom = LocalPageInsets.current.bottom
            ),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        GlassCard(backdrop, Modifier.fillMaxWidth()) {
            SectionLabel("主题预设")
            ThemeSwatchRow(appearance.themeIndex) {
                onAppearanceChange(appearance.copy(themeIndex = it))
                pushRearConfig(rearSelected, rearFont, AccentPalette[it.coerceIn(0, AccentPalette.size - 1)])
            }
            Spacer(Modifier.height(10.dp))
            ChipSelector(
                backdrop = backdrop,
                title = "预设名称",
                options = themeOptions,
                selectedIndex = appearance.themeIndex,
                onSelect = {
                    onAppearanceChange(appearance.copy(themeIndex = it))
                    pushRearConfig(rearSelected, rearFont, AccentPalette[it.coerceIn(0, AccentPalette.size - 1)])
                }
            )
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
                RearScreenPreview(
                    accent = appearance.accentColor(),
                    status = status,
                    fields = rearSelected.toList(),
                    modifier = Modifier.fillMaxWidth(),
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

        GlassCard(backdrop, Modifier.fillMaxWidth()) {
            SectionLabel("玻璃与动效")
            ChipSelector(
                backdrop = backdrop,
                title = "玻璃材质",
                options = listOf("液态玻璃", "毛玻璃"),
                selectedIndex = if (appearance.glassMode == GlassMode.Liquid) 0 else 1,
                onSelect = { onAppearanceChange(appearance.copy(glassMode = if (it == 0) GlassMode.Liquid else GlassMode.Frosted)) }
            )
            ToggleLine(backdrop, "流畅模式", "减少光环与数值动效", appearance.smoothMode, onCheckedChange = { onAppearanceChange(appearance.copy(smoothMode = it)) })
        }

        GlassCard(backdrop, Modifier.fillMaxWidth()) {
            SectionLabel("缩放与卡片")
            SliderField(backdrop, "界面字体缩放", fmt(appearance.fontScale), 0.8f..1.5f, { appearance.fontScale }, { onAppearanceChange(appearance.copy(fontScale = it)) })
            Spacer(Modifier.height(12.dp))
            SliderField(backdrop, "卡片圆角", "${appearance.cardCorner.toInt()}px", 0f..40f, { appearance.cardCorner }, { onAppearanceChange(appearance.copy(cardCorner = it)) })
            Spacer(Modifier.height(12.dp))
            SliderField(backdrop, "卡片模糊度", "${appearance.cardBlur.toInt()}", 0f..44f, { appearance.cardBlur }, { onAppearanceChange(appearance.copy(cardBlur = it)) })
            Spacer(Modifier.height(12.dp))
            SliderField(backdrop, "卡片扭曲度", "${appearance.cardDistortion.toInt()}", 0f..150f, { appearance.cardDistortion }, { onAppearanceChange(appearance.copy(cardDistortion = it)) })
            Spacer(Modifier.height(12.dp))
            ToggleLine(backdrop, "玻璃卡片质感", null, appearance.cardTexture, onCheckedChange = { onAppearanceChange(appearance.copy(cardTexture = it)) })
            SliderField(backdrop, "卡片透明度", fmt(appearance.cardAlpha), 0f..0.5f, { appearance.cardAlpha }, { onAppearanceChange(appearance.copy(cardAlpha = it)) })
        }

        GlassCard(backdrop, Modifier.fillMaxWidth()) {
            SectionLabel("底栏")
            ToggleLine(backdrop, "隐藏底栏文字", null, appearance.hideBarText, onCheckedChange = { onAppearanceChange(appearance.copy(hideBarText = it)) })
            SliderField(backdrop, "底栏宽度", "${appearance.barWidth.toInt()}%", 48f..100f, { appearance.barWidth }, { onAppearanceChange(appearance.copy(barWidth = it)) })
            SliderField(backdrop, "底栏高度", "${appearance.barHeight.toInt()}px", 44f..84f, { appearance.barHeight }, { onAppearanceChange(appearance.copy(barHeight = it)) })
            SliderField(backdrop, "底栏透明度", fmt(appearance.barAlpha), 0.04f..0.6f, { appearance.barAlpha }, { onAppearanceChange(appearance.copy(barAlpha = it)) })
            SliderField(backdrop, "底栏模糊度", "${appearance.barBlur.toInt()}", 0f..20f, { appearance.barBlur }, { onAppearanceChange(appearance.copy(barBlur = it)) })
            SliderField(backdrop, "底栏纯色背景度", fmt(appearance.barSolid), 0f..1f, { appearance.barSolid }, { onAppearanceChange(appearance.copy(barSolid = it)) })
            Spacer(Modifier.height(8.dp))
            BasicText("底栏纯色背景颜色", style = TextStyle(color = XJ.TextSecondary, fontSize = 12.sp))
            Spacer(Modifier.height(6.dp))
            SwatchRow(SwatchPalette, appearance.barColorIndex) { onAppearanceChange(appearance.copy(barColorIndex = it)) }
        }

        GlassCard(backdrop, Modifier.fillMaxWidth()) {
            SectionLabel("背景")
            ToggleLine(backdrop, "自定义背景", "从相册选择一张图片", appearance.customBg, onCheckedChange = { onAppearanceChange(appearance.copy(customBg = it)) })
            if (!appearance.customBg) {
                Spacer(Modifier.height(6.dp))
                Row(
                    Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    listOf("青柠凝露", "冰蓝凝露", "紫晶凝露", "琥珀凝露").forEachIndexed { index, label ->
                        GlassChip(
                            backdrop = backdrop,
                            label = label,
                            selected = appearance.wallpaperIndex == index,
                            onClick = { onAppearanceChange(appearance.copy(wallpaperIndex = index)) }
                        )
                    }
                }
            } else {
                Spacer(Modifier.height(12.dp))
                ActionButton(
                    backdrop = backdrop,
                    text = if (appearance.bgImage != null) "更换相册图片" else "选择相册图片",
                    onClick = {
                        scope.launch {
                            pickBackgroundImageBytes()?.let { bytes ->
                                decodeBackgroundImage(bytes)?.let { bitmap ->
                                    onAppearanceChange(currentAppearance.copy(bgImage = bitmap))
                                }
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                )
                if (appearance.bgImage != null) {
                    Spacer(Modifier.height(8.dp))
                    ActionButton(
                        backdrop = backdrop,
                        text = "清除图片",
                        onClick = { onAppearanceChange(currentAppearance.copy(bgImage = null)) },
                        modifier = Modifier.fillMaxWidth(),
                        secondary = true
                    )
                }
            }
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
            Spacer(Modifier.height(8.dp))
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
            if (appearance.bgKind == "gif" || appearance.bgKind == "video") {
                Spacer(Modifier.height(8.dp))
                ActionButton(
                    backdrop = backdrop,
                    text = "清除动态背景",
                    onClick = {
                        onAppearanceChange(currentAppearance.copy(bgKind = "wallpaper", bgPath = ""))
                        showToast("已回到内置壁纸")
                    },
                    modifier = Modifier.fillMaxWidth(),
                    secondary = true
                )
            }
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
private fun ThemeSwatchRow(selectedIndex: Int, onSelect: (Int) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        AccentPalette.forEachIndexed { index, color ->
            Box(
                Modifier
                    .size(28.dp)
                    .clip(CircleShape)
                    .background(color)
                    .border(
                        width = if (selectedIndex == index) 3.dp else 0.dp,
                        color = Color.White,
                        shape = CircleShape
                    )
                    .clickable { onSelect(index) }
            )
        }
    }
}

@Composable
private fun SwatchRow(
    colors: List<Color>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit
) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        colors.forEachIndexed { index, color ->
            Box(
                Modifier
                    .size(28.dp)
                    .clip(CircleShape)
                    .background(color)
                    .border(
                        width = if (selectedIndex == index) 3.dp else 0.dp,
                        color = Color.White,
                        shape = CircleShape
                    )
                    .clickable { onSelect(index) }
            )
        }
    }
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
