package com.hyperos.xj.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hyperos.xj.ui.components.LiquidSlider
import com.hyperos.xj.ui.components.LiquidToggle
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.BackdropEffectScope
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.colorControls
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.shadow.Shadow
import com.kyant.shapes.Capsule
import com.kyant.shapes.RoundedRectangle

enum class GlassMode { Liquid, Frosted }

val LocalGlassMode = androidx.compose.runtime.staticCompositionLocalOf { GlassMode.Liquid }

fun BackdropEffectScope.glassEffects(
    mode: GlassMode,
    blurRadius: Float,
    lensHeight: Float,
    lensAmount: Float,
    chromaticAberration: Boolean = false,
    depthEffect: Boolean = false
) {
    when (mode) {
        GlassMode.Liquid -> {
            vibrancy()
            blur(blurRadius)
            lens(lensHeight, lensAmount, depthEffect = depthEffect, chromaticAberration = chromaticAberration)
        }
        GlassMode.Frosted -> {
            colorControls(saturation = 0.82f)
            blur(blurRadius)
            lens(lensHeight, lensAmount, chromaticAberration = chromaticAberration)
        }
    }
}

@Composable
fun GlassCard(
    backdrop: Backdrop,
    modifier: Modifier = Modifier,
    padding: Dp = 16.dp,
    content: @Composable ColumnScope.() -> Unit
) {
    val mode = LocalGlassMode.current
    val appearance = LocalAppearance.current
    Column(
        modifier
            .drawBackdrop(
                backdrop = backdrop,
                shape = { RoundedRectangle(appearance.cardCorner.dp) },
                effects = {
                    when (mode) {
                        GlassMode.Liquid -> {
                            vibrancy()
                            blur(appearance.cardBlur.coerceIn(0f, 44f).dp.toPx())
                            lens(
                                20f.dp.toPx(),
                                appearance.cardDistortion.coerceIn(0f, 60f).dp.toPx()
                            )
                        }
                        GlassMode.Frosted -> glassEffects(mode, 38f.dp.toPx(), 10f.dp.toPx(), 20f.dp.toPx())
                    }
                },
                shadow = {
                    Shadow(
                        radius = 18f.dp,
                        color = Color.Black.copy(alpha = 0.24f)
                    )
                },
                onDrawSurface = {
                    val corner = appearance.cardCorner.dp.toPx()
                        .coerceIn(0f, size.minDimension / 2f)
                    val roundedCorner = CornerRadius(corner, corner)
                    drawRoundRect(
                        Color.White.copy(
                            alpha = if (mode == GlassMode.Liquid) {
                                if (appearance.cardTexture) appearance.cardAlpha else 0.03f
                            } else {
                                0.16f
                            }
                        ),
                        cornerRadius = roundedCorner
                    )
                    drawRoundRect(
                        brush = Brush.verticalGradient(
                            0f to Color.White.copy(alpha = 0.14f),
                            1f to Color.Transparent,
                            startY = 0f,
                            endY = size.height * 0.28f
                        ),
                        cornerRadius = roundedCorner
                    )
                    drawRoundRect(
                        color = Color.White.copy(alpha = 0.07f),
                        topLeft = Offset(0.5f, 0.5f),
                        size = Size(size.width - 1f, size.height - 1f),
                        cornerRadius = roundedCorner,
                        style = Stroke(width = 1f)
                    )
                }
            )
            .padding(padding),
        content = content
    )
}

@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    BasicText(
        text = text,
        modifier = modifier.padding(bottom = 6.dp),
        style = TextStyle(
            color = XJ.TextSecondary,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
        )
    )
}

@Composable
fun Pill(text: String, modifier: Modifier = Modifier) {
    val mode = LocalGlassMode.current
    BasicText(
        text = text,
        modifier = modifier
            .clip(Capsule())
            .background(Color.White.copy(alpha = if (mode == GlassMode.Liquid) 0.10f else 0.16f))
            .padding(horizontal = 12.dp, vertical = 6.dp),
        style = TextStyle(color = XJ.TextPrimary, fontSize = 12.sp, fontWeight = FontWeight.Medium)
    )
}

@Composable
fun GlassChip(
    backdrop: Backdrop,
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val mode = LocalGlassMode.current
    val accent = LocalAccentColor.current
    BasicText(
        text = label,
        modifier = modifier
            .drawBackdrop(
                backdrop = backdrop,
                shape = { Capsule() },
                effects = {
                    when (mode) {
                        GlassMode.Liquid -> glassEffects(mode, 8f.dp.toPx(), 8f.dp.toPx(), 16f.dp.toPx())
                        GlassMode.Frosted -> glassEffects(mode, 14f.dp.toPx(), 4f.dp.toPx(), 8f.dp.toPx())
                    }
                },
                shadow = null,
                onDrawSurface = {
                    drawRect(
                        if (selected) accent.copy(alpha = 0.82f)
                        else Color.White.copy(alpha = if (mode == GlassMode.Liquid) 0.08f else 0.13f)
                    )
                }
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 13.dp, vertical = 8.dp),
        style = TextStyle(
            color = if (selected) Color.White else XJ.TextPrimary,
            fontSize = 13.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal
        )
    )
}

@Composable
fun ChipSelector(
    backdrop: Backdrop,
    title: String,
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier) {
        BasicText(
            text = title,
            style = TextStyle(color = XJ.TextSecondary, fontSize = 12.sp)
        )
        Spacer(Modifier.height(8.dp))
        Row(
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            options.forEachIndexed { index, label ->
                GlassChip(
                    backdrop = backdrop,
                    label = label,
                    selected = selectedIndex == index,
                    onClick = { onSelect(index) }
                )
            }
        }
    }
}

@Composable
fun CollapsibleCard(
    backdrop: Backdrop,
    title: String,
    subtitle: String? = null,
    initiallyExpanded: Boolean = true,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    var expanded by remember { mutableStateOf(initiallyExpanded) }
    val accent = LocalAccentColor.current
    GlassCard(backdrop, modifier) {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded },
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                BasicText(
                    text = title,
                    style = TextStyle(color = XJ.TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                )
                if (subtitle != null) {
                    BasicText(
                        text = subtitle,
                        style = TextStyle(color = XJ.TextSecondary, fontSize = 11.sp)
                    )
                }
            }
            BasicText(
                text = if (expanded) "收起" else "展开",
                style = TextStyle(color = accent, fontSize = 12.sp)
            )
        }
        if (expanded) {
            Spacer(Modifier.height(10.dp))
            content()
        }
    }
}

@Composable
fun SwitchRow(
    backdrop: Backdrop,
    title: String,
    subtitle: String? = null,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    GlassCard(backdrop, modifier) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                BasicText(
                    text = title,
                    style = TextStyle(color = XJ.TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                )
                if (subtitle != null) {
                    Spacer(Modifier.height(2.dp))
                    BasicText(
                        text = subtitle,
                        style = TextStyle(color = XJ.TextSecondary, fontSize = 11.sp)
                    )
                }
            }
            Spacer(Modifier.width(12.dp))
            LiquidToggle(
                selected = { checked },
                onSelect = onCheckedChange,
                backdrop = backdrop
            )
        }
    }
}

@Composable
fun ToggleLine(
    backdrop: Backdrop,
    title: String,
    subtitle: String? = null,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier.fillMaxWidth().padding(vertical = 5.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            BasicText(
                text = title,
                style = TextStyle(color = XJ.TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            )
            if (subtitle != null) {
                BasicText(
                    text = subtitle,
                    style = TextStyle(color = XJ.TextSecondary, fontSize = 11.sp)
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        LiquidToggle(
            selected = { checked },
            onSelect = onCheckedChange,
            backdrop = backdrop
        )
    }
}

@Composable
fun ValueRow(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    valueColor: Color = XJ.TextPrimary
) {
    Row(
        modifier.fillMaxWidth().padding(vertical = 5.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        BasicText(
            text = label,
            modifier = Modifier.weight(1f),
            style = TextStyle(color = XJ.TextSecondary, fontSize = 13.sp)
        )
        Spacer(Modifier.width(8.dp))
        BasicText(
            text = value,
            modifier = Modifier.weight(1f),
            style = TextStyle(
                color = valueColor,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.End
            )
        )
    }
}

@Composable
fun InfoRow(label: String, value: String) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        BasicText(
            text = label,
            modifier = Modifier.weight(1f),
            style = TextStyle(color = XJ.TextSecondary, fontSize = 13.sp)
        )
        Spacer(Modifier.width(8.dp))
        BasicText(
            text = value,
            modifier = Modifier.weight(1f),
            style = TextStyle(
                color = XJ.TextPrimary,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.End
            )
        )
    }
}

@Composable
private fun MetricCell(value: String, label: String, modifier: Modifier = Modifier) {
    val mode = LocalGlassMode.current
    Column(
        modifier
            .clip(RoundedCornerShape(12.dp))
            .background(Color.White.copy(alpha = if (mode == GlassMode.Liquid) 0.06f else 0.10f))
            .padding(vertical = 10.dp, horizontal = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        BasicText(
            text = value,
            style = TextStyle(color = XJ.TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.Bold)
        )
        Spacer(Modifier.height(3.dp))
        BasicText(
            text = label,
            style = TextStyle(color = XJ.TextSecondary, fontSize = 10.sp)
        )
    }
}

@Composable
fun GlassInput(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    numeric: Boolean = false
) {
    val mode = LocalGlassMode.current
    Box(
        modifier
            .clip(RoundedCornerShape(12.dp))
            .background(Color.White.copy(alpha = if (mode == GlassMode.Liquid) 0.08f else 0.13f))
            .padding(horizontal = 12.dp, vertical = 10.dp)
    ) {
        if (value.isEmpty()) {
            BasicText(
                text = placeholder,
                style = TextStyle(color = XJ.TextSecondary, fontSize = 13.sp)
            )
        }
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.fillMaxWidth(),
            textStyle = TextStyle(color = XJ.TextPrimary, fontSize = 13.sp),
            singleLine = true,
            keyboardOptions = KeyboardOptions(
                keyboardType = if (numeric) KeyboardType.Number else KeyboardType.Text
            )
        )
    }
}

@Composable
fun ActionButton(
    backdrop: Backdrop,
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    danger: Boolean = false,
    secondary: Boolean = false
) {
    val mode = LocalGlassMode.current
    val accent = LocalAccentColor.current
    BasicText(
        text = text,
        modifier = modifier
            .drawBackdrop(
                backdrop = backdrop,
                shape = { Capsule() },
                effects = {
                    when (mode) {
                        GlassMode.Liquid -> glassEffects(mode, 8f.dp.toPx(), 10f.dp.toPx(), 20f.dp.toPx())
                        GlassMode.Frosted -> glassEffects(mode, 14f.dp.toPx(), 5f.dp.toPx(), 10f.dp.toPx())
                    }
                },
                shadow = {
                    Shadow(
                        radius = 8f.dp,
                        color = Color.Black.copy(alpha = 0.14f)
                    )
                },
                onDrawSurface = {
                    drawRect(
                        when {
                            danger -> XJ.Danger.copy(alpha = 0.88f)
                            secondary -> Color.White.copy(alpha = if (mode == GlassMode.Liquid) 0.10f else 0.16f)
                            else -> accent.copy(alpha = 0.86f)
                        }
                    )
                }
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 12.dp),
        style = TextStyle(
            color = if (secondary) XJ.TextPrimary else Color.White,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center
        )
    )
}

@Composable
fun SliderField(
    backdrop: Backdrop,
    title: String,
    valueText: String,
    valueRange: ClosedFloatingPointRange<Float>,
    value: () -> Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier
) {
    val accent = LocalAccentColor.current
    Column(modifier) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            BasicText(
                text = title,
                style = TextStyle(color = XJ.TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            )
            BasicText(
                text = valueText,
                style = TextStyle(color = accent, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            )
        }
        Spacer(Modifier.height(16.dp))
        LiquidSlider(
            value = value,
            onValueChange = onValueChange,
            valueRange = valueRange,
            visibilityThreshold = if (valueRange.endInclusive - valueRange.start > 10f) 1f else 0.01f,
            backdrop = backdrop
        )
    }
}

@Composable
fun MetricGrid(
    items: List<Pair<String, String>>,
    columns: Int = 3,
    modifier: Modifier = Modifier
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items.chunked(columns).forEach { rowItems ->
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                rowItems.forEach { (value, label) ->
                    MetricCell(value, label, Modifier.weight(1f))
                }
                repeat(columns - rowItems.size) {
                    Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
fun EnergyRing(
    progress: Float,
    centerText: String,
    subText: String,
    modifier: Modifier = Modifier,
    size: Dp = 176.dp
) {
    Box(modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(size)) {
            val stroke = 12.dp.toPx()
            val inset = stroke / 2f
            drawArc(
                color = Color.White.copy(alpha = 0.10f),
                startAngle = -90f,
                sweepAngle = 360f,
                useCenter = false,
                topLeft = Offset(inset, inset),
                size = Size(this.size.width - stroke, this.size.height - stroke),
                style = Stroke(width = stroke, cap = StrokeCap.Round)
            )
            drawArc(
                brush = Brush.sweepGradient(
                    listOf(XJ.Accent, XJ.AccentSoft, XJ.Accent)
                ),
                startAngle = -90f,
                sweepAngle = 360f * progress.coerceIn(0f, 1f),
                useCenter = false,
                topLeft = Offset(inset, inset),
                size = Size(this.size.width - stroke, this.size.height - stroke),
                style = Stroke(width = stroke, cap = StrokeCap.Round)
            )
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            BasicText(
                text = centerText,
                style = TextStyle(color = XJ.TextPrimary, fontSize = 34.sp, fontWeight = FontWeight.Bold)
            )
            BasicText(
                text = subText,
                style = TextStyle(color = XJ.TextSecondary, fontSize = 12.sp)
            )
        }
    }
}
