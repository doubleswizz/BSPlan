package com.bsplan.app.ocr

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Why reading handwriting for search is waiting right now.
 */
object IndexingStatus {

    private val _waitingFor = MutableStateFlow<String?>(null)

    /** The problem the last read stopped on, or null once a page was read. */
    val waitingFor: StateFlow<String?> = _waitingFor.asStateFlow()

    fun onWaiting(reason: String) {
        _waitingFor.value = reason
    }

    fun onPageRead() {
        _waitingFor.value = null
    }
}
