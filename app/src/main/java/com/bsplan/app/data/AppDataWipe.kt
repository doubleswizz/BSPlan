package com.bsplan.app.data

import android.app.ActivityManager
import android.content.Context
import android.util.Log
import com.bsplan.app.data.datastore.GlobalAppSettings
import com.bsplan.app.data.db.KvProxy
import com.bsplan.app.sleepscreen.SleepAgenda

/**
 * Data & Backup → Wipe All Data (Oct 2026). It used to empty a memory cache only and still say
 * "All data wiped". Now Android erases everything BSPlan keeps on the tablet: the database,
 * settings, sign-ins, scheduled work and alarms. The app process ends.
 *
 * Files in shared folders (exports, backups, daily notes) are the user's and stay; the dialog says so.
 * The sleep-screen image is the exception: it shows today's agenda on the lock screen, so it goes first.
 */
object AppDataWipe {
    private const val TAG = "AppDataWipe"

    /** Erases BSPlan's data; on success the app closes. False when Android refused, so the caller can say so. */
    fun wipe(context: Context): Boolean {
        val app = context.applicationContext
        return run(
            beforeClear = {
                // Off in the settings first: if the wipe fails and App info opens, MainActivity.onStop
                // would otherwise draw a new sleep image (SleepAgenda.onAppBackground)
                runCatching {
                    KvProxy(app).setAppSettings(GlobalAppSettings.current.copy(sleepScreenAgendaEnabled = false))
                    SleepAgenda.setEnabled(app, false)
                }.onFailure { Log.w(TAG, "Sleep image not withdrawn: ${it.javaClass.simpleName}") }
            },
            clear = {
                runCatching {
                    app.getSystemService(ActivityManager::class.java)?.clearApplicationUserData() == true
                }.getOrDefault(false)
            }
        )
    }

    internal fun run(beforeClear: () -> Unit, clear: () -> Boolean): Boolean {
        beforeClear()
        val cleared = clear()
        if (!cleared) Log.w(TAG, "Android did not clear the app data")
        return cleared
    }
}
