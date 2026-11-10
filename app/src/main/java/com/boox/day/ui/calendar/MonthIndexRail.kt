package com.boox.day.ui.calendar

import android.view.MotionEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.boox.day.editor.DrawCanvas
import com.boox.day.ui.noRippleClickable
import com.boox.day.ui.theme.EInkTheme

/**
 * Width of the persistent left-edge JAN–DEC month index rail.
 */
val MONTH_INDEX_RAIL_WIDTH: Dp = 52.dp

private val MONTH_LABELS = listOf(
    "JAN", "FEB", "MAR", "APR", "MAY", "JUN",
    "JUL", "AUG", "SEP", "OCT", "NOV", "DEC"
)

/**
 * Kaleido 3 color palette for each month tab (0 = JAN .. 11 = DEC).
 * Rich, saturated colors so the active tab and side color stripes pop on Color E-Ink.
 */
val MONTH_ACCENT_COLORS = listOf(
    Color(0xFF1E3A8A), // JAN - Deep Navy
    Color(0xFFD32F2F), // FEB - Crimson Red
    Color(0xFF0D9488), // MAR - Teal
    Color(0xFF10B981), // APR - Emerald Green
    Color(0xFF2563EB), // MAY - Royal Blue
    Color(0xFFD97706), // JUN - Warm Amber
    Color(0xFFDC2626), // JUL - Bright Crimson
    Color(0xFFEA580C), // AUG - Burnt Orange
    Color(0xFF4F46E5), // SEP - Indigo
    Color(0xFF9333EA), // OCT - Purple
    Color(0xFFB45309), // NOV - Autumn Gold
    Color(0xFF047857)  // DEC - Pine Green
)

val MONTH_TINT_COLORS = listOf(
    Color(0xFFDBEAFE), // JAN
    Color(0xFFFEE2E2), // FEB
    Color(0xFFCCFBF1), // MAR
    Color(0xFFD1FAE5), // APR
    Color(0xFFDBEAFE), // MAY
    Color(0xFFFEF3C7), // JUN
    Color(0xFFFEE2E2), // JUL
    Color(0xFFFFEDD5), // AUG
    Color(0xFFE0E7FF), // SEP
    Color(0xFFF3E8FF), // OCT
    Color(0xFFFEF3C7), // NOV
    Color(0xFFD1FAE5)  // DEC
)

/**
 * Persistent PDF-planner-style month index rail along the left edge of the screen.
 *
 * Follows E-Ink UI/UX rules:
 * - Zero animations, no ripples (`noRippleClickable`)
 * - Vibrant Kaleido 3 color tabs: active month has a solid saturated fill with white text,
 *   inactive months have a crisp 6dp colored index stripe and soft pastel tint.
 * - Calls `DrawCanvas.forceUnfreeze()` on touch down so taps respond immediately after stylus writing
 *
 * @param selectedMonth Currently active month (0 = January .. 11 = December)
 * @param onMonthSelected Callback invoked when a month tab (0..11) is tapped
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun MonthIndexRail(
    selectedMonth: Int,
    onMonthSelected: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .width(MONTH_INDEX_RAIL_WIDTH)
            .fillMaxHeight()
            .background(EInkTheme.Colors.background)
            .pointerInteropFilter { event ->
                if (event.action == MotionEvent.ACTION_DOWN) {
                    DrawCanvas.forceUnfreeze()
                }
                false
            }
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
        ) {
            MONTH_LABELS.forEachIndexed { index, label ->
                val isSelected = index == selectedMonth
                val accentColor = MONTH_ACCENT_COLORS.getOrElse(index) { EInkTheme.Colors.primary }
                val tintColor = MONTH_TINT_COLORS.getOrElse(index) { EInkTheme.Colors.background }
                val bgColor = if (isSelected) accentColor else tintColor
                val textColor = if (isSelected) EInkTheme.Colors.white else Color(0xFF0F172A)

                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .background(bgColor)
                            .noRippleClickable {
                                DrawCanvas.forceUnfreeze()
                                onMonthSelected(index)
                            },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Left color index bar
                        Box(
                            modifier = Modifier
                                .width(if (isSelected) 8.dp else 6.dp)
                                .fillMaxHeight()
                                .background(if (isSelected) Color(0xFF0F172A) else accentColor)
                        )
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight(),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = label,
                                fontSize = EInkTheme.Typography.bodyLarge,
                                fontWeight = if (isSelected) FontWeight.ExtraBold else FontWeight.Bold,
                                color = textColor,
                                textAlign = TextAlign.Center,
                                maxLines = 1
                            )
                        }
                    }

                    // Horizontal divider between month tabs
                    if (index < MONTH_LABELS.lastIndex) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(1.dp)
                                .background(EInkTheme.Colors.borderStrong)
                        )
                    }
                }
            }
        }

        // Solid right border separating the month rail from the planner page
        Box(
            modifier = Modifier
                .width(EInkTheme.Global.borderWidth)
                .fillMaxHeight()
                .background(EInkTheme.Colors.borderStrong)
        )
    }
}

/**
 * Root layout wrapper that places the persistent left-edge [MonthIndexRail] alongside the main planner content.
 * Extracted into its own composable to keep MainScreen's root lambda well below ART's dex instruction limit.
 */
@Composable
fun PlannerRootWithMonthRail(
    selectedMonth: Int,
    onMonthSelected: (Int) -> Unit,
    content: @Composable () -> Unit
) {
    Row(modifier = Modifier.fillMaxSize()) {
        MonthIndexRail(
            selectedMonth = selectedMonth,
            onMonthSelected = onMonthSelected
        )
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
        ) {
            content()
        }
    }
}

