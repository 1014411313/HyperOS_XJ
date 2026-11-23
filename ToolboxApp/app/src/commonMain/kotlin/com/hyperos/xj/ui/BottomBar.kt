package com.hyperos.xj.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hyperos.xj.ui.components.LiquidBottomTab
import com.hyperos.xj.ui.components.LiquidBottomTabs
import com.kyant.backdrop.Backdrop

@Composable
private fun TabIcon(index: Int, active: Boolean, accent: Color) {
    val tint = if (active) accent else XJ.TextSecondary
    Canvas(Modifier.size(18.dp)) {
        val sw = 1.6.dp.toPx()
        val cx = size.width / 2f
        val cy = size.height / 2f
        when (index) {
            0 -> {
                val roof = Path().apply {
                    moveTo(size.width * 0.12f, size.height * 0.52f)
                    lineTo(cx, size.height * 0.16f)
                    lineTo(size.width * 0.88f, size.height * 0.52f)
                }
                drawPath(roof, tint, style = Stroke(sw, join = StrokeJoin.Round))
                drawRoundRect(
                    tint,
                    topLeft = Offset(size.width * 0.22f, size.height * 0.48f),
                    size = Size(size.width * 0.56f, size.height * 0.36f),
                    cornerRadius = CornerRadius(1.5.dp.toPx()),
                    style = Stroke(sw)
                )
            }
            1 -> {
                drawRoundRect(
                    tint,
                    topLeft = Offset(cx - size.width * 0.12f, size.height * 0.12f),
                    size = Size(size.width * 0.24f, size.height * 0.58f),
                    cornerRadius = CornerRadius(size.width * 0.12f),
                    style = Stroke(sw)
                )
                drawCircle(tint, radius = size.width * 0.19f, center = Offset(cx, size.height * 0.78f), style = Stroke(sw))
            }
            2 -> {
                drawRoundRect(
                    tint,
                    topLeft = Offset(size.width * 0.1f, size.height * 0.3f),
                    size = Size(size.width * 0.74f, size.height * 0.4f),
                    cornerRadius = CornerRadius(2.dp.toPx()),
                    style = Stroke(sw)
                )
                drawRect(tint, topLeft = Offset(size.width * 0.86f, size.height * 0.42f), size = Size(size.width * 0.08f, size.height * 0.16f))
            }
            3 -> {
                drawCircle(tint, radius = size.width * 0.38f, center = Offset(cx, cy), style = Stroke(sw))
                listOf(
                    Offset(cx - size.width * 0.14f, cy - size.height * 0.08f),
                    Offset(cx + size.width * 0.08f, cy - size.height * 0.14f),
                    Offset(cx + size.width * 0.14f, cy + size.height * 0.08f)
                ).forEach { drawCircle(tint, radius = 1.4.dp.toPx(), center = it) }
            }
            else -> {
                drawCircle(tint, radius = size.width * 0.4f, center = Offset(cx, cy), style = Stroke(sw))
                drawCircle(tint, radius = 1.2.dp.toPx(), center = Offset(cx, size.height * 0.34f))
                drawLine(tint, Offset(cx, size.height * 0.46f), Offset(cx, size.height * 0.7f), sw)
            }
        }
    }
}

@Composable
fun BottomBar(
    selectedIndex: Int,
    labels: List<String>,
    onSelect: (Int) -> Unit,
    backdrop: Backdrop,
    modifier: Modifier = Modifier
) {
    val appearance = LocalAppearance.current
    val accent = LocalAccentColor.current
    val widthFraction = appearance.barWidth.coerceIn(48f, 100f) / 100f
    val containerColor = Color.White.copy(alpha = appearance.barAlpha.coerceIn(0.04f, 0.6f))
    val solidColor = SwatchPalette[appearance.barColorIndex.coerceIn(0, SwatchPalette.size - 1)]
    val selectedIndexState = rememberUpdatedState(selectedIndex)
    val selectedIndexProvider = remember { { selectedIndexState.value } }

    Box(modifier.fillMaxWidth(), contentAlignment = Alignment.BottomCenter) {
        LiquidBottomTabs(
            selectedTabIndex = selectedIndexProvider,
            onTabSelected = onSelect,
            backdrop = backdrop,
            tabsCount = labels.size,
            modifier = Modifier.fillMaxWidth(widthFraction),
            accentColor = accent,
            containerColor = containerColor,
            panelHeight = appearance.barHeight.coerceIn(44f, 84f).dp,
            blurRadius = (appearance.barBlur.coerceIn(0f, 20f) + 6f).dp,
            solidColor = solidColor,
            solidAlpha = appearance.barSolid.coerceIn(0f, 1f)
        ) {
            labels.forEachIndexed { index, label ->
                val active = index == selectedIndex
                LiquidBottomTab(onClick = { onSelect(index) }) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        TabIcon(index, active, accent)
                        if (!appearance.hideBarText) {
                            Spacer(Modifier.height(3.dp))
                            BasicText(
                                text = label,
                                style = TextStyle(
                                    color = if (active) accent else XJ.TextSecondary,
                                    fontSize = 12.sp,
                                    fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal
                                )
                            )
                        }
                    }
                }
            }
        }
    }
}
