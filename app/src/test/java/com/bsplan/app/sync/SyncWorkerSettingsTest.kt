package com.bsplan.app.sync

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.workDataOf
import com.bsplan.app.APP_SETTINGS_KEY
import com.bsplan.app.data.datastore.AppSettings
import com.bsplan.app.data.datastore.GlobalAppSettings
import com.bsplan.app.data.db.APP_DATABASE_FILE_NAME
import com.bsplan.app.data.db.AppDatabase
import com.bsplan.app.data.db.KvProxy
import com.bsplan.app.data.getDbDir
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * The offline-edit push (SyncWorker) often runs with no screen open. Then MainActivity hasn't loaded
 * the settings and GlobalAppSettings holds defaults (Private Mode): until Oct 2026 the worker read
 * those and skipped, so edits made offline reached Google only once BSPlan was opened again.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class SyncWorkerSettingsTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private var settingsWereLoaded = false
    private lateinit var settingsBefore: AppSettings

    @Before
    fun setUp() {
        deleteDatabaseFiles()
        settingsWereLoaded = GlobalAppSettings.loaded
        settingsBefore = GlobalAppSettings.current
    }

    @After
    fun tearDown() {
        GlobalAppSettings.update(settingsBefore)
        GlobalAppSettings.loaded = settingsWereLoaded
        AppDatabase.closeDatabase()
        deleteDatabaseFiles()
    }

    @Test
    fun `with no screen open the push reads the saved Cloud Sync mode`() = runBlocking {
        KvProxy(context).setKv(
            APP_SETTINGS_KEY,
            AppSettings(version = 1, syncMode = AppSettings.SyncMode.HYBRID),
            AppSettings.serializer()
        )
        // No screen has loaded the settings: the in-memory copy holds the defaults
        GlobalAppSettings.update(AppSettings(version = 1))
        GlobalAppSettings.loaded = false

        val result = TestListenableWorkerBuilder<SyncWorker>(context)
            .setInputData(workDataOf(SyncWorker.KEY_SYNC_TYPE to SyncWorker.SYNC_TYPE_PUSH_ONLY))
            .build()
            .doWork()

        // Past the mode check; it stops only because the test has no connected account
        assertEquals(
            "Skipped: no connected accounts",
            (result as ListenableWorker.Result.Success).outputData.getString(SyncWorker.KEY_RESULT_MESSAGE)
        )
    }

    private fun deleteDatabaseFiles() {
        val file = File(getDbDir(context), APP_DATABASE_FILE_NAME)
        listOf("", "-wal", "-shm", "-journal").forEach { File(file.path + it).delete() }
    }
}
