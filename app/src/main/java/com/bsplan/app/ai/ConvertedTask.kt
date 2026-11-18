package com.bsplan.app.ai

import com.google.gson.annotations.SerializedName

/**
 * A task item converted from notes or action items.
 */
data class ConvertedTask(
    @SerializedName("text") val text: String,
    @SerializedName("suggestedPriority") val suggestedPriority: String = "",
    @SerializedName("reason") val reason: String = "",
    @SerializedName("confidence") val confidence: Float = 0.5f,
    @SerializedName("suggestedTime") val suggestedTime: String? = null,
    @SerializedName("relatedEventId") val relatedEventId: String? = null,
    val isSelected: Boolean = true,
    val targetDateOverride: String? = null,
    val dueDateOverride: Long? = null
)

/**
 * Result of adding converted tasks to a date.
 */
data class TasksAddedResult(
    val tasksAdded: Int,
    val pagesUsed: Int,
    val targetDates: Set<String>,
    val latestPageIndexByDate: Map<String, Int> = emptyMap(),
    val taskIds: List<String> = emptyList()
)
