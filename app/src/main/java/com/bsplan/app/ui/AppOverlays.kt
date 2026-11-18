package com.bsplan.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.window.PopupPositionProvider
import com.bsplan.app.meetings.MeetingPrepHost
import com.bsplan.app.meetings.MeetingPrepUi
import com.bsplan.app.share.ShareHost
import com.bsplan.app.share.ShareUi
import com.bsplan.app.ui.dialogs.QuickWriteHost
import com.bsplan.app.ui.dialogs.QuickWriteUi
import com.bsplan.app.week.WeekHost
import com.bsplan.app.week.WeekUi

/**
 * Overlays that MainScreen only knows as one line (Nov 2026): the pen goes off while any is open,
 * and new overlays are added here, not to MainScreen.
 */
object AppOverlays {
    val isOpen: Boolean get() = WeekUi.isOpen || MeetingPrepUi.isOpen || ShareUi.isOpen || QuickWriteUi.isOpen
}

@Composable
fun AppOverlaysHost() {
    WeekHost()
    MeetingPrepHost()
    ShareHost()
    QuickWriteHost()
}

/**
 * The middle of the screen. Alignment.Center means the middle of the parent, which for something inside
 * a scrolling or low card put popups at the bottom of the screen.
 */
object ScreenCentre : PopupPositionProvider {
    override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection, popupContentSize: IntSize) =
        IntOffset((windowSize.width - popupContentSize.width) / 2, (windowSize.height - popupContentSize.height) / 2)
}

/** The whole window, for a full-page overlay. */
object ScreenTopLeft : PopupPositionProvider {
    override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection, popupContentSize: IntSize) =
        IntOffset(0, 0)
}
