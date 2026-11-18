package com.bsplan.app.editor.ui.toolbar

import com.bsplan.app.ui.LocalNestedPopupOffset
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.Divider
import androidx.compose.material.Icon
import androidx.compose.material.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Summarize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.bsplan.app.R
import com.bsplan.app.ui.convertDpToPixel
import com.bsplan.app.ui.theme.EInkTheme

/**
 * AI Tools toolbar button with dropdown menu.
 *
 * BEHAVIOR:
 * - Single-tap: Button becomes dark AND dropdown menu appears
 * - Mutually exclusive with other mode buttons
 *
 * HANDWRITING RECOGNITION:
 * - "Recognize Handwriting" in menu: Page-level cloud LLM recognition (entire page)
 * - Lasso selection recognition: On-device Digital Ink (fast, for selected strokes)
 */
@Composable
fun AiToolbarButton(
    hasPageContent: Boolean,
    onRecognizePage: () -> Unit,
    onVoiceInput: () -> Unit,
    pageId: String? = null,
    onMenuOpenChange: ((Boolean) -> Unit)? = null,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    isActive: Boolean = false,
    onActivate: () -> Unit = {},
    isOpen: Boolean = false,
    onOpenChange: ((Boolean) -> Unit)? = null
) {
    val context = LocalContext.current

    // Use external state if provided, otherwise internal
    var internalMenuOpen by remember { mutableStateOf(false) }
    val isMenuOpen = if (onOpenChange != null) isOpen else internalMenuOpen
    val setMenuOpen: (Boolean) -> Unit = { value ->
        if (onOpenChange != null) {
            onOpenChange(value)
        } else {
            internalMenuOpen = value
        }
    }

    if (onMenuOpenChange != null) {
        LaunchedEffect(isMenuOpen) {
            onMenuOpenChange(isMenuOpen)
        }
    }

    Box(modifier = modifier) {
        // Always show AI sparkles icon with dropdown menu
        ToolbarButton(
            iconId = R.drawable.ai_sparkles_text,
            contentDescription = "AI Tools",
            isSelected = isActive && enabled,  // Don't show active state when disabled
            enabled = enabled,
            hasDropdown = true,
            iconSize = 36.dp,  // 50% larger than default 24dp
            iconPadding = 1,   // Reduce padding to give icon room to grow
            onSelect = {
                if (enabled) {
                    onActivate()  // Set this as active button
                    setMenuOpen(true)  // Show dropdown immediately
                }
            }
        )

        // Only show popup when enabled AND menu is open
        if (enabled && isMenuOpen) {
            Popup(
                offset = IntOffset(0, convertDpToPixel(EInkTheme.PopupMenu.offsetY, context).toInt()) + LocalNestedPopupOffset.current,
                onDismissRequest = { setMenuOpen(false) },
                properties = PopupProperties(focusable = false),  // Non-focusable allows taps to reach other buttons
                alignment = Alignment.TopStart
            ) {
                Column(
                    modifier = Modifier
                        .background(EInkTheme.PopupMenu.background)
                        .border(EInkTheme.PopupMenu.borderWidth, EInkTheme.PopupMenu.borderColor)
                        .width(EInkTheme.PopupMenu.width)
                ) {
                    AiMenuItems(
                        hasPageContent = hasPageContent,
                        onRecognizePage = onRecognizePage,
                        onVoiceInput = onVoiceInput,
                        pageId = pageId,
                        onDone = { setMenuOpen(false) }
                    )
                }
            }
        }
    }
}

/**
 * The Memos toolbar items shared by the toolbar dropdown and toolbar menu.
 */
@Composable
fun AiMenuItems(
    hasPageContent: Boolean,
    onRecognizePage: () -> Unit,
    onVoiceInput: () -> Unit,
    @Suppress("UNUSED_PARAMETER") pageId: String? = null,
    onDone: () -> Unit
) {
    // Voice to Text
    AiMenuItem(
        label = "Voice to Text",
        icon = R.drawable.speech_to_text_40px,
        onClick = {
            onDone()
            onVoiceInput()
        }
    )

    Divider(color = EInkTheme.Divider.subtleColor, thickness = EInkTheme.Divider.subtleThickness)

    // Recognize Handwriting - On-device Digital Ink recognition
    AiMenuItem(
        label = "Recognize Handwriting",
        icon = R.drawable.convert_to_text,
        enabled = hasPageContent,
        onClick = {
            onDone()
            onRecognizePage()
        }
    )
}

@Composable
private fun AiMenuItem(
    label: String,
    icon: Int,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    val contentColor = if (enabled) EInkTheme.MenuItem.textColor else EInkTheme.MenuItem.textColorDisabled

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(EInkTheme.MenuItem.height)
            .clickable(enabled = enabled) { onClick() }
            .padding(horizontal = EInkTheme.MenuItem.horizontalPadding),
        horizontalArrangement = Arrangement.spacedBy(EInkTheme.MenuItem.iconTextSpacing),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            painter = painterResource(id = icon),
            contentDescription = label,
            modifier = Modifier.size(EInkTheme.MenuItem.iconSize),
            tint = contentColor
        )
        Text(
            text = label,
            fontSize = EInkTheme.MenuItem.fontSize,
            color = contentColor
        )
    }
}

@Composable
private fun AiMenuItemVector(
    label: String,
    icon: ImageVector,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    val contentColor = if (enabled) EInkTheme.MenuItem.textColor else EInkTheme.MenuItem.textColorDisabled

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(EInkTheme.MenuItem.height)
            .clickable(enabled = enabled) { onClick() }
            .padding(horizontal = EInkTheme.MenuItem.horizontalPadding),
        horizontalArrangement = Arrangement.spacedBy(EInkTheme.MenuItem.iconTextSpacing),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            modifier = Modifier.size(EInkTheme.MenuItem.iconSize),
            tint = contentColor
        )
        Text(
            text = label,
            fontSize = EInkTheme.MenuItem.fontSize,
            color = contentColor
        )
    }
}

@Composable
private fun AiMenuItemText(
    label: String,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    val contentColor = if (enabled) EInkTheme.MenuItem.textColor else EInkTheme.MenuItem.textColorDisabled

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(EInkTheme.MenuItem.height)
            .clickable(enabled = enabled) { onClick() }
            .padding(horizontal = EInkTheme.MenuItem.horizontalPadding),
        contentAlignment = Alignment.CenterStart
    ) {
        Text(
            text = label,
            fontSize = EInkTheme.MenuItem.fontSize,
            color = contentColor
        )
    }
}
