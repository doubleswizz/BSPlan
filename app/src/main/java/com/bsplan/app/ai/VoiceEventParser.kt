package com.bsplan.app.ai

import com.bsplan.app.data.model.ParsedEventData
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * On-device parser for converting transcribed speech into structured calendar event fields.
 */
object VoiceEventParser {

    /**
     * Parse transcribed speech on-device into [ParsedEventData].
     */
    suspend fun parseTranscribedText(
        transcribedText: String,
        currentDate: LocalDate = LocalDate.now(),
        @Suppress("UNUSED_PARAMETER") onStageChange: (String) -> Unit = {}
    ): ParsedEventData {
        val raw = transcribedText.trim()
        if (raw.isBlank()) {
            return ParsedEventData.empty("No speech detected")
        }

        var working = raw
        var targetDate = currentDate
        var inferredDate = false

        when {
            Regex("""\btoday\b""", RegexOption.IGNORE_CASE).containsMatchIn(working) -> {
                targetDate = currentDate
                working = working.replace(Regex("""\btoday\b""", RegexOption.IGNORE_CASE), " ")
                inferredDate = true
            }
            Regex("""\btomorrow\b""", RegexOption.IGNORE_CASE).containsMatchIn(working) -> {
                targetDate = currentDate.plusDays(1)
                working = working.replace(Regex("""\btomorrow\b""", RegexOption.IGNORE_CASE), " ")
                inferredDate = true
            }
            else -> {
                val weekday = parseWeekday(working, currentDate)
                if (weekday != null) {
                    targetDate = weekday.first
                    working = working.replace(weekday.second, " ")
                    inferredDate = true
                }
            }
        }

        var startHour = 9
        var startMinute = 0
        var endHour = 10
        var endMinute = 0
        var durationMinutes = 60
        var isAllDay = true

        val rangeRegex = Regex(
            """\b(?:from\s+)?(\d{1,2})(?::(\d{2}))?\s*([aApP]\.?[mM]\.?)?\s*(?:-|–|\bto\b)\s*(\d{1,2})(?::(\d{2}))?\s*([aApP]\.?[mM]\.?)\b"""
        )
        val rangeMatch = rangeRegex.find(working)
        if (rangeMatch != null) {
            val h1 = rangeMatch.groupValues[1].toIntOrNull() ?: 9
            val m1 = (rangeMatch.groupValues[2].toIntOrNull() ?: 0).coerceIn(0, 59)
            val mer1 = rangeMatch.groupValues[3].replace(".", "").lowercase(Locale.US)
            val h2 = rangeMatch.groupValues[4].toIntOrNull() ?: 10
            val m2 = (rangeMatch.groupValues[5].toIntOrNull() ?: 0).coerceIn(0, 59)
            val mer2 = rangeMatch.groupValues[6].replace(".", "").lowercase(Locale.US)
            startHour = to24Hour(h1, mer1.ifEmpty { mer2 })
            startMinute = m1
            endHour = to24Hour(h2, mer2)
            endMinute = m2
            durationMinutes = ((endHour * 60 + endMinute) - (startHour * 60 + startMinute)).coerceAtLeast(15)
            isAllDay = false
            working = working.removeRange(rangeMatch.range)
        } else {
            val singleRegex = Regex("""(?:\bat\s+)?\b(\d{1,2})(?::(\d{2}))?\s*([aApP]\.?[mM]\.?)\b""")
            val singleMatch = singleRegex.find(working)
            if (singleMatch != null) {
                val h = singleMatch.groupValues[1].toIntOrNull() ?: 9
                val m = (singleMatch.groupValues[2].toIntOrNull() ?: 0).coerceIn(0, 59)
                val mer = singleMatch.groupValues[3].replace(".", "").lowercase(Locale.US)
                startHour = to24Hour(h, mer)
                startMinute = m
                val endTotal = (startHour * 60 + startMinute + 60) % 1440
                endHour = endTotal / 60
                endMinute = endTotal % 60
                durationMinutes = 60
                isAllDay = false
                working = working.removeRange(singleMatch.range)
            }
        }

        val cleanTitle = working
            .replace(Regex("""\s+"""), " ")
            .trim(' ', '-', ',', '.', '@')
            .replaceFirstChar { it.uppercaseChar() }
            .ifBlank { raw.replaceFirstChar { it.uppercaseChar() } }

        return ParsedEventData(
            title = cleanTitle,
            date = targetDate.format(DateTimeFormatter.ISO_LOCAL_DATE),
            startHour = startHour,
            startMinute = startMinute,
            endHour = endHour,
            endMinute = endMinute,
            durationMinutes = durationMinutes,
            isAllDay = isAllDay,
            confidence = if (!isAllDay || inferredDate) 0.85f else 0.7f,
            originalIntent = raw
        )
    }

    private fun parseWeekday(text: String, from: LocalDate): Pair<LocalDate, Regex>? {
        val days = mapOf(
            "monday" to DayOfWeek.MONDAY,
            "tuesday" to DayOfWeek.TUESDAY,
            "wednesday" to DayOfWeek.WEDNESDAY,
            "thursday" to DayOfWeek.THURSDAY,
            "friday" to DayOfWeek.FRIDAY,
            "saturday" to DayOfWeek.SATURDAY,
            "sunday" to DayOfWeek.SUNDAY
        )
        for ((name, dow) in days) {
            val regex = Regex("""\b(?:this\s+|next\s+|on\s+)?$name\b""", RegexOption.IGNORE_CASE)
            if (regex.containsMatchIn(text)) {
                var candidate = from.plusDays(1)
                while (candidate.dayOfWeek != dow) {
                    candidate = candidate.plusDays(1)
                }
                return candidate to regex
            }
        }
        return null
    }

    private fun to24Hour(hour: Int, meridiem: String): Int {
        val h = hour.coerceIn(0, 23)
        return when {
            meridiem.startsWith("p") && h < 12 -> h + 12
            meridiem.startsWith("a") && h == 12 -> 0
            else -> h
        }
    }
}
