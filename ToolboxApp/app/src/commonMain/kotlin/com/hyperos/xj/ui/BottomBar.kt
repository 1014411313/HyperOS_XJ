package com.hyperos.xj.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hyperos.xj.ui.components.LiquidBottomTab
import com.hyperos.xj.ui.components.LiquidBottomTabs
import com.kyant.backdrop.Backdrop

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
                    if (appearance.hideBarText) {
                        Box(
                            Modifier
                                .size(if (active) 7.dp else 5.dp)
                                .clip(CircleShape)
                                .background(
                                    if (active) accent else XJ.TextSecondary.copy(alpha = 0.6f)
                                )
                        )
                    } else {
                        BasicText(
                            text = label,
                            style = TextStyle(
                                color = if (active) accent else XJ.TextSecondary,
                                fontSize = 13.sp,
                                fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal
                            )
                        )
                    }
                }
            }
        }
    }
}
