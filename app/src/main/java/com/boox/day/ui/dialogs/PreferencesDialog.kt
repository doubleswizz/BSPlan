package com.boox.day.ui.dialogs

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.ButtonDefaults
import androidx.compose.material.Icon
import androidx.compose.material.OutlinedButton
import androidx.compose.material.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.boox.day.data.datastore.AppSettings
import com.boox.day.data.datastore.GlobalAppSettings
import com.boox.day.data.db.KvProxy
import com.boox.day.ocr.DigitalInkModelManager
import com.boox.day.ui.components.EInkToggle
import com.boox.day.ui.components.IntervalChip
import com.boox.day.ui.components.RadioOption
import com.boox.day.ui.components.ScrollableWithIndicator
import com.boox.day.ui.components.SettingCard
import com.boox.day.ui.theme.EInkTheme
import kotlinx.coroutines.delay

/**
 * Preferences Dialog - Consolidates display/format settings.
 *
 * Contents:
 * - Display Section: Date Format, Time Format
 * - Task Sync & Tips
 * - Handwriting Recognition
 *
 * E-Ink optimized: Uses Popup (not Dialog), EInkTheme constants.
 */
@Composable
fun PreferencesDialog(
    onDismiss: () -> Unit,
    // Date/Time Format
    dateFormat: AppSettings.DateFormat = AppSettings.DateFormat.MDY,
    onDateFormatChanged: (AppSettings.DateFormat) -> Unit = {},
    timeFormat: AppSettings.TimeFormat = AppSettings.TimeFormat.TwelveHour,
    onTimeFormatChanged: (AppSettings.TimeFormat) -> Unit = {},
    // Tips feature
    tipsEnabled: Boolean = true,
    onTipsEnabledChanged: (Boolean) -> Unit = {},
    tipInterval: AppSettings.TipInterval = AppSettings.TipInterval.EVERY_HOUR,
    onTipIntervalChanged: (AppSettings.TipInterval) -> Unit = {}
) {
    val context = LocalContext.current

    // Oct 2026 (plan B3): opened from the Settings dot, Preferences shows Task Sync when it needs the user
    val taskSyncInView = remember { androidx.compose.foundation.relocation.BringIntoViewRequester() }
    LaunchedEffect(Unit) {
        if (com.boox.day.ui.attention.SettingsJump.consume(com.boox.day.ui.attention.SettingsPlace.PREFERENCES)) {
            delay(150)  // laid out first
            if (com.boox.day.tasksync.TaskSync.attentionReason(context) != null) taskSyncInView.bringIntoView()
        }
    }
    // E-Ink: Use Popup instead of Dialog to avoid system scrim/dimming
    Popup(
        alignment = Alignment.Center,
        onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = true)
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth(EInkTheme.PanelDialog.widthFraction)  // Oct 2026: was 0.85f
                    .fillMaxHeight(EInkTheme.PanelDialog.heightFraction)  // was 0.75f
                    .background(EInkTheme.PopupOverlay.background, RoundedCornerShape(EInkTheme.ModernDialog.cornerRadius))
                    .border(EInkTheme.PopupOverlay.borderWidth, EInkTheme.PopupOverlay.borderColor, RoundedCornerShape(EInkTheme.ModernDialog.cornerRadius))
                    .padding(EInkTheme.PopupOverlay.padding)
            ) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(EInkTheme.Global.spacingSmall)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Settings,
                            contentDescription = "Preferences",
                            modifier = Modifier.size(24.dp),
                            tint = EInkTheme.Colors.primary
                        )
                        Text(
                            text = "Preferences",
                            fontSize = EInkTheme.PopupOverlay.titleFontSize,
                            fontWeight = FontWeight.Bold,
                            color = EInkTheme.PopupOverlay.titleColor
                        )
                    }

                    OutlinedButton(
                        onClick = onDismiss,
                        modifier = Modifier.height(EInkTheme.Button.height),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = EInkTheme.Button.textColor
                        ),
                        border = BorderStroke(EInkTheme.Button.borderWidth, EInkTheme.Button.borderColor),
                        shape = RoundedCornerShape(EInkTheme.ModernDialog.PrimaryButton.cornerRadius)
                    ) {
                        Text(
                            text = "Close",
                            fontSize = EInkTheme.Typography.button,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }

                Spacer(modifier = Modifier.height(EInkTheme.Global.paddingMedium))

                // Content
                ScrollableWithIndicator(
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(EInkTheme.Global.paddingSmall)
                    ) {
                        // Date Format Setting
                        SettingCard(
                            title = "Date Format",
                            description = "Choose how dates are displayed"
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(EInkTheme.Global.paddingLarge)
                            ) {
                                RadioOption(
                                    label = "MM/DD/YYYY",
                                    isSelected = dateFormat == AppSettings.DateFormat.MDY,
                                    onClick = { onDateFormatChanged(AppSettings.DateFormat.MDY) }
                                )
                                RadioOption(
                                    label = "DD/MM/YYYY",
                                    isSelected = dateFormat == AppSettings.DateFormat.DMY,
                                    onClick = { onDateFormatChanged(AppSettings.DateFormat.DMY) }
                                )
                            }
                        }

                        // Time Format Setting
                        SettingCard(
                            title = "Time Format",
                            description = "Choose how times are displayed"
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(EInkTheme.Global.paddingLarge)
                            ) {
                                RadioOption(
                                    label = "12-hour",
                                    isSelected = timeFormat == AppSettings.TimeFormat.TwelveHour,
                                    onClick = { onTimeFormatChanged(AppSettings.TimeFormat.TwelveHour) }
                                )
                                RadioOption(
                                    label = "24-hour",
                                    isSelected = timeFormat == AppSettings.TimeFormat.TwentyFourHour,
                                    onClick = { onTimeFormatChanged(AppSettings.TimeFormat.TwentyFourHour) }
                                )
                            }
                        }

                        // Tasks kept in step with Google Tasks (Oct 2026, tasksync/; builds with task sync only)
                        com.boox.day.ui.components.TaskSyncSetting(
                            Modifier.bringIntoViewRequester(taskSyncInView)
                        )

                        // Show Tips Setting
                        SettingCard(
                            title = "Show Tips",
                            description = "Display helpful tips about app features periodically"
                        ) {
                            Column(
                                verticalArrangement = Arrangement.spacedBy(EInkTheme.Global.spacingSmall)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = if (tipsEnabled) "Enabled" else "Disabled",
                                        fontSize = EInkTheme.Typography.bodyMedium,
                                        color = EInkTheme.Colors.darkGray
                                    )
                                    EInkToggle(
                                        checked = tipsEnabled,
                                        onCheckedChange = onTipsEnabledChanged
                                    )
                                }

                                // Interval picker - only shown when tips are enabled
                                if (tipsEnabled) {
                                    Spacer(modifier = Modifier.height(EInkTheme.Global.paddingSmall))
                                    Text(
                                        text = "Tip Frequency",
                                        fontSize = EInkTheme.Typography.bodyMedium,
                                        fontWeight = FontWeight.Medium,
                                        color = EInkTheme.Colors.primary
                                    )
                                    Spacer(modifier = Modifier.height(EInkTheme.Global.spacingSmall))
                                    // All chips on single row with equal distribution (90% width)
                                    Row(
                                        modifier = Modifier.fillMaxWidth(0.9f),
                                        horizontalArrangement = Arrangement.spacedBy(EInkTheme.Global.spacingSmall)
                                    ) {
                                        IntervalChip(
                                            interval = AppSettings.TipInterval.EVERY_15_MINUTES,
                                            isSelected = tipInterval == AppSettings.TipInterval.EVERY_15_MINUTES,
                                            onClick = { onTipIntervalChanged(AppSettings.TipInterval.EVERY_15_MINUTES) },
                                            modifier = Modifier.weight(1f)
                                        )
                                        IntervalChip(
                                            interval = AppSettings.TipInterval.EVERY_30_MINUTES,
                                            isSelected = tipInterval == AppSettings.TipInterval.EVERY_30_MINUTES,
                                            onClick = { onTipIntervalChanged(AppSettings.TipInterval.EVERY_30_MINUTES) },
                                            modifier = Modifier.weight(1f)
                                        )
                                        IntervalChip(
                                            interval = AppSettings.TipInterval.EVERY_HOUR,
                                            isSelected = tipInterval == AppSettings.TipInterval.EVERY_HOUR,
                                            onClick = { onTipIntervalChanged(AppSettings.TipInterval.EVERY_HOUR) },
                                            modifier = Modifier.weight(1f)
                                        )
                                        IntervalChip(
                                            interval = AppSettings.TipInterval.EVERY_4_HOURS,
                                            isSelected = tipInterval == AppSettings.TipInterval.EVERY_4_HOURS,
                                            onClick = { onTipIntervalChanged(AppSettings.TipInterval.EVERY_4_HOURS) },
                                            modifier = Modifier.weight(1f)
                                        )
                                        IntervalChip(
                                            interval = AppSettings.TipInterval.ONCE_A_DAY,
                                            isSelected = tipInterval == AppSettings.TipInterval.ONCE_A_DAY,
                                            onClick = { onTipIntervalChanged(AppSettings.TipInterval.ONCE_A_DAY) },
                                            modifier = Modifier.weight(1f)
                                        )
                                    }
                                }
                            }
                        }

                        // ═══════════════════════════════════════════════════════════════
                        // HANDWRITING RECOGNITION SECTION
                        // ═══════════════════════════════════════════════════════════════

                        Spacer(modifier = Modifier.height(EInkTheme.Global.paddingMedium))

                        Text(
                            text = "Handwriting Recognition",
                            fontSize = EInkTheme.Preferences.sectionHeaderFontSize,
                            fontWeight = FontWeight.Bold,
                            color = EInkTheme.Preferences.sectionHeaderColor
                        )

                        Spacer(modifier = Modifier.height(EInkTheme.Global.paddingSmall))

                        // Handwriting Language Status
                        HandwritingRecognitionSection()
                    }
                }
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════════════
// HANDWRITING RECOGNITION SECTION
// ═══════════════════════════════════════════════════════════════════════════════

/**
 * Handwriting Recognition section - manages handwriting language selection.
 * Shows active language with "Change" button that opens Language Pack Manager.
 * Supports 5 languages: English (Full), Spanish/French/German/Chinese (Basic).
 */
@Composable
private fun HandwritingRecognitionSection() {
    val context = LocalContext.current
    val activeLanguage by DigitalInkModelManager.activeLanguage.collectAsState()
    val languageStates by DigitalInkModelManager.languageStates.collectAsState()
    var showLanguageManager by remember { mutableStateOf(false) }

    val activeLanguageInfo = languageStates.find { it.isActive }

    SettingCard(
        title = "Task Handwriting",
        description = "Recognizes handwritten text on Task pages for search and suggestions"
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(EInkTheme.Global.spacingSmall)
        ) {
            // Active language display
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        "Active Language",
                        fontSize = EInkTheme.Preferences.subLabelFontSize,
                        color = EInkTheme.Colors.gray
                    )
                    Text(
                        activeLanguageInfo?.displayName ?: "Loading...",
                        fontSize = EInkTheme.Typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        color = EInkTheme.Colors.primary
                    )
                }
                OutlinedButton(
                    onClick = { showLanguageManager = true },
                    modifier = Modifier.height(EInkTheme.Button.height),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = EInkTheme.Colors.primary
                    ),
                    border = BorderStroke(
                        EInkTheme.Button.borderWidth,
                        EInkTheme.Button.borderColor
                    ),
                    shape = RoundedCornerShape(EInkTheme.ModernDialog.PrimaryButton.cornerRadius)
                ) {
                    Text(
                        "Change",
                        fontSize = EInkTheme.Typography.button,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }
    }

    // Language Pack Manager Popup
    if (showLanguageManager) {
        LanguagePackManagerPopup(
            onDismiss = { showLanguageManager = false },
            onLanguageSelected = { languageTag ->
                // Persist language selection to settings
                KvProxy(context).setAppSettings(
                    GlobalAppSettings.current.copy(handwritingLanguage = languageTag)
                )
                // Popup remains open so user can see updated active state
            }
        )
    }
}
