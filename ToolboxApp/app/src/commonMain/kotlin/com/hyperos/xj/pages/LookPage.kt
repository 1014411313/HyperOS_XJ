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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hyperos.xj.runtime.DeviceStatus
import com.hyperos.xj.runtime.Engine
import com.hyperos.xj.runtime.ToolboxRuntimeBridge
import com.hyperos.xj.runtime.decodeBackgroundImage
import com.hyperos.xj.runtime.pickBackgroundImageBytes
import com.hyperos.xj.ui.AccentPalette
import com.hyperos.xj.ui.ActionButton
import com.hyperos.xj.ui.AppearanceSettings
import com.hyperos.xj.ui.ChipSelector
import com.hyperos.xj.ui.GlassCard
import com.hyperos.xj.ui.GlassChip
import com.hyperos.xj.ui.GlassMode
import com.hyperos.xj.ui.RearScreenPreview
import com.hyperos.xj.ui.SectionLabel
import com.hyperos.xj.ui.SliderField
import com.hyperos.xj.ui.SwatchPalette
import com.hyperos.xj.ui.ToggleLine
import com.hyperos.xj.ui.XJ
import com.hyperos.xj.ui.accentColor
import com.kyant.backdrop.Backdrop
import kotlinx.coroutines.launch

private val themeOptions = listOf("极光浅色", "全息流体", "暗黑霓虹", "幻彩极光", "赛博霓虹", "熔岩鎏金", "冰川琉璃", "月海流银")
private val rearFields = listOf("温度", "电量", "电压", "电流", "功率", "状态", "设计容量", "满充容量", "健康度", "循环", "伪装", "协议", "剩余容量", "电池寿命")

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
    val scope = rememberCoroutineScope()

    LaunchedEffect(status.rearScreen, rearTouched) {
        if (!rearTouched) rearEnabled = status.rearScreen
    }

    Column(
        Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(bottom = 108.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        GlassCard(backdrop, Modifier.fillMaxWidth()) {
            SectionLabel("主题预设")
            ThemeSwatchRow(appearance.themeIndex) { onAppearanceChange(appearance.copy(themeIndex = it)) }
            Spacer(Modifier.height(10.dp))
            ChipSelector(
                backdrop = backdrop,
                title = "预设名称",
                options = themeOptions,
                selectedIndex = appearance.themeIndex,
                onSelect = { onAppearanceChange(appearance.copy(themeIndex = it)) }
            )
        }

        GlassCard(backdrop, Modifier.fillMaxWidth()) {
            ToggleLine(
                backdrop,
                "背屏电池卡片",
                "支持背屏机型投放电池卡片",
                rearEnabled,
                onCheckedChange = {
                    rearTouched = true
                    rearEnabled = it
                    scope.launch { bridge.exec(Engine.rear(it)) }
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
                    animate = !appearance.smoothMode
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
                onSelect = { rearFont = it }
            )
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
            SliderField(backdrop, "卡片扭曲度", "${appearance.cardDistortion.toInt()}", 0f..60f, { appearance.cardDistortion }, { onAppearanceChange(appearance.copy(cardDistortion = it)) })
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
                                    onAppearanceChange(appearance.copy(bgImage = bitmap))
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
                        onClick = { onAppearanceChange(appearance.copy(bgImage = null)) },
                        modifier = Modifier.fillMaxWidth(),
                        secondary = true
                    )
                }
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
