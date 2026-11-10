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
 * Persistent PDF-planner-style month index rail along the left edge of the screen.
 *
 * Follows E-Ink UI/UX rules:
 * - Zero animations, no ripples (`noRippleClickable`)
 * - High contrast inverted black/white state for the active month
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
                val bgColor = if (isSelected) EInkTheme.Colors.primary else EInkTheme.Colors.background
                val textColor = if (isSelected) EInkTheme.Colors.white else EInkTheme.Colors.primary

                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                ) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .background(bgColor)
                            .noRippleClickable {
                                DrawCanvas.forceUnfreeze()
                                onMonthSelected(index)
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = label,
                            fontSize = EInkTheme.Typography.bodyLarge,
                            fontWeight = if (isSelected) FontWeight.ExtraBold else FontWeight.SemiBold,
                            color = textColor,
                            textAlign = TextAlign.Center,
                            maxLines = 1
                        )
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

