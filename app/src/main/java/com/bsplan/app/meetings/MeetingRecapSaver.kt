package com.bsplan.app.meetings

import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint

/** Text layout & pagination helpers for shared notes pages. */
object MeetingRecapSaver {
    internal const val MARGIN = 50f
    internal const val FONT_SIZE = 37f

    /** Splits [text] into page-sized parts at line ends; measured with [fits]. */
    internal fun pagesOf(text: String, fits: (String) -> Boolean): List<String> {
        val pages = mutableListOf<String>()
        var current = ""
        for (line in text.lines()) {
            var rest = line
            while (true) {
                val next = if (current.isEmpty()) rest else "$current\n$rest"
                if (fits(next)) {
                    current = next
                    break
                }
                if (current.isNotEmpty()) {
                    pages += current
                    current = ""
                    continue
                }
                // One line taller than a page, as shared web text can be
                val cut = cutAt(rest, fits)
                pages += rest.take(cut).trimEnd()
                rest = rest.drop(cut).trimStart()
                if (rest.isEmpty()) break
            }
        }
        if (current.isNotBlank()) pages += current
        return pages
    }

    /** How much of [line] fits, ending at a word when there is one. */
    private fun cutAt(line: String, fits: (String) -> Boolean): Int {
        var low = 1
        var high = line.length
        while (low < high) {
            val middle = (low + high + 1) / 2
            if (fits(line.take(middle))) low = middle else high = middle - 1
        }
        val space = line.lastIndexOf(' ', low)
        return if (space > 0) space + 1 else low
    }

    /** Pixel height of [text] wrapped into [width]. */
    internal fun height(text: String, width: Float, size: Float = FONT_SIZE): Float {
        val paint = TextPaint(TextPaint.ANTI_ALIAS_FLAG).apply { textSize = size }
        return StaticLayout.Builder.obtain(text, 0, text.length, paint, width.toInt().coerceAtLeast(1))
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .build().height.toFloat()
    }
}
