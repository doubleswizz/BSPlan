package com.bsplan.app.data.datastore

import android.content.Context
import com.bsplan.app.data.AppRepository
import com.bsplan.app.data.db.Kv
import com.bsplan.app.editor.state.Mode
import com.bsplan.app.editor.utils.Eraser
import com.bsplan.app.editor.utils.NamedSettings
import com.bsplan.app.editor.utils.Pen
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

const val persistVersion = 2

object EditorSettingCacheManager {

    @Serializable
    data class EditorSettings(
        val version: Int = persistVersion,
        val isToolbarOpen: Boolean,
        val pen: Pen,
        val eraser: Eraser? = Eraser.PEN,
        val penSettings: NamedSettings,
        val mode: Mode
    )

    /**
     * PERFORMANCE FIX: Load editor settings asynchronously.
     * Previously blocked main thread for 10-20ms during startup.
     * Now loads in background - canvas uses defaults until settings arrive (~50ms).
     */
    fun init(context: Context) {
        val appContext = context.applicationContext
        persistScope.launch {
            try {
                val settingsJson = AppRepository(appContext).kvRepository.get("EDITOR_SETTINGS")
                if (settingsJson != null) {
                    val settings = Json.decodeFromString<EditorSettings>(settingsJson.value)
                    if (settings.version == persistVersion) {
                        setEditorSettings(appContext, settings, false)
                    }
                }
            } catch (e: Exception) {
                // Silently fail - will use default pen/eraser settings
                // This is acceptable because user can always change settings
            }
        }
    }

    // Background scope for persisting editor settings without blocking UI.
    private val persistScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var pendingPersistJob: Job? = null

    private fun schedulePersist(context: Context, settings: EditorSettings) {
        // Always use applicationContext to avoid leaking Activity
        val appContext = context.applicationContext

        // Cancel any in-flight persist so rapid changes coalesce
        pendingPersistJob?.cancel()
        pendingPersistJob = persistScope.launch {
            // Small debounce window to avoid writing on every tiny change
            delay(300)
            val settingsJson = Json.encodeToString(settings)
            AppRepository(appContext).kvRepository.set(Kv("EDITOR_SETTINGS", settingsJson))
        }
    }

    private var editorSettings: EditorSettings? = null
    fun getEditorSettings(): EditorSettings? {
        return editorSettings
    }

    fun setEditorSettings(
        context: Context,
        newEditorSettings: EditorSettings,
        shouldPersist: Boolean = true
    ) {
        editorSettings = newEditorSettings
        if (shouldPersist) {
            schedulePersist(context, newEditorSettings)
        }
    }
}
