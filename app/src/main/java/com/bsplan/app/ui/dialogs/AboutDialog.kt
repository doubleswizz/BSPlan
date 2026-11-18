package com.bsplan.app.ui.dialogs

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Icon
import androidx.compose.material.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.bsplan.app.BuildConfig
import com.bsplan.app.R
import com.bsplan.app.ui.noRippleClickable
import com.bsplan.app.ui.theme.EInkTheme
import com.bsplan.app.update.AppUpdateGate

/**
 * About dialog: displays the app version and Check for Updates action.
 */
@Composable
fun AboutDialog(
    onDismiss: () -> Unit
) {
    val dialogShape = RoundedCornerShape(EInkTheme.ModernDialog.cornerRadius)
    val versionLabel = "Version ${BuildConfig.VERSION_NAME}"

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
                    .fillMaxWidth(EInkTheme.ModernDialog.widthFraction)
                    .widthIn(max = EInkTheme.PopupOverlay.wideWidth)
                    .background(EInkTheme.ModernDialog.background, dialogShape)
                    .border(
                        EInkTheme.ModernDialog.borderWidth,
                        EInkTheme.ModernDialog.borderColor,
                        dialogShape
                    )
            ) {
                AboutHeader(
                    versionLabel = versionLabel,
                    onDismiss = onDismiss
                )
            }
        }
    }
}

@Composable
internal fun AboutHeader(
    versionLabel: String,
    onDismiss: () -> Unit,
    appName: String = stringResource(R.string.app_name)  // AboutHeaderRenderTest has no app resources
) {
    val context = LocalContext.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                text = appName,
                fontSize = EInkTheme.Typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = EInkTheme.Colors.primary
            )
            Text(
                text = versionLabel,
                fontSize = EInkTheme.Typography.bodyMedium,
                color = EInkTheme.Colors.gray
            )
        }

        CheckForUpdatesButton(
            checking = AppUpdateGate.state is AppUpdateGate.State.Checking,
            onClick = { AppUpdateGate.checkNow(context) }
        )

        Box(
            modifier = Modifier
                .size(EInkTheme.Global.touchTarget)
                .noRippleClickable { onDismiss() },
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.Close,
                contentDescription = "Close",
                tint = EInkTheme.Colors.darkGray,
                modifier = Modifier.size(EInkTheme.Global.iconSize)
            )
        }
    }
}

/**
 * About → Check for Updates (Oct 2026; a small underlined link before): the app's primary button with
 * the update icon. The answer opens in the update popup (update/AppUpdateGate). While GitHub is asked
 * the button turns outlined, says "Checking…" and ignores taps.
 */
@Composable
internal fun CheckForUpdatesButton(checking: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(EInkTheme.ModernDialog.PrimaryButton.cornerRadius)
    val contentColor = if (checking) EInkTheme.Colors.primary else EInkTheme.ModernDialog.PrimaryButton.textColor
    Row(
        modifier = Modifier
            .height(EInkTheme.Button.height)
            .background(
                if (checking) EInkTheme.ModernDialog.background else EInkTheme.ModernDialog.PrimaryButton.background,
                shape
            )
            .border(
                EInkTheme.ModernDialog.borderWidth,
                if (checking) EInkTheme.Colors.borderStrong else EInkTheme.ModernDialog.PrimaryButton.background,
                shape
            )
            .noRippleClickable(enabled = !checking) { onClick() }
            .padding(horizontal = EInkTheme.Button.horizontalPadding),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(EInkTheme.Global.spacing)
    ) {
        Icon(
            imageVector = Icons.Default.SystemUpdate,
            contentDescription = null,
            tint = contentColor,
            modifier = Modifier.size(EInkTheme.Global.iconSizeSmall)
        )
        Text(
            text = if (checking) "Checking…" else "Check for Updates",
            fontSize = EInkTheme.Button.fontSize,
            fontWeight = FontWeight.Bold,
            color = contentColor,
            maxLines = 1,
            softWrap = false
        )
    }
}
