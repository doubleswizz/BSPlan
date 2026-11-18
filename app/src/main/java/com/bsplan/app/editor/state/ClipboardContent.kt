package com.bsplan.app.editor.state

import com.bsplan.app.data.db.Image
import com.bsplan.app.data.db.Stroke

data class ClipboardContent(
    val strokes: List<Stroke> = emptyList(),
    val images: List<Image> = emptyList(),
)