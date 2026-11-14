package com.boox.day.data.db

import android.app.Application
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class PendingPastDueTasksTest {

    private lateinit var db: AppDatabase
    private lateinit var sql: SupportSQLiteDatabase
    private lateinit var taskDao: TaskDao

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        sql = db.openHelper.writableDatabase
        taskDao = db.taskDao()
        insertPage("page-1")
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun getPendingPastDueTasksFlowFiltersCorrectly() = runBlocking {
        // 1. Included: undated past-day typed task
        insertTask(id = "past-undated", dayDate = "2026-11-10", dueDay = null, completed = 0, typedText = "Mail contract", rowIndex = 0)
        // 2. Included: task with past explicit dueDay
        insertTask(id = "past-due", dayDate = "2026-11-13", dueDay = "2026-11-09", completed = 0, typedText = "Pay rent", rowIndex = 0)
        // 3. Excluded: completed past task
        insertTask(id = "past-done", dayDate = "2026-11-08", dueDay = "2026-11-08", completed = 1, typedText = "Completed item", rowIndex = 0)
        // 4. Excluded: empty/blank past slot
        insertTask(id = "past-empty", dayDate = "2026-11-08", dueDay = null, completed = 0, typedText = null, rowIndex = 1)
        // 5. Excluded: past-day task whose dueDay was pushed to future
        insertTask(id = "pushed-future", dayDate = "2026-11-08", dueDay = "2026-11-20", completed = 0, typedText = "Future deadline", rowIndex = 2)
        // 6. Excluded: task due today
        insertTask(id = "due-today", dayDate = "2026-11-13", dueDay = "2026-11-13", completed = 0, typedText = "Today task", rowIndex = 1)
        // 7. Excluded: subtask even if past due
        insertTask(id = "subtask-past", dayDate = "2026-11-09", dueDay = "2026-11-09", completed = 0, typedText = "Subtask", parentId = "past-undated", rowIndex = 0)

        val pending = taskDao.getPendingPastDueTasksFlow("2026-11-13").first()
        assertEquals(listOf("past-due", "past-undated"), pending.map { it.id })
    }

    private fun insertPage(id: String) {
        sql.execSQL(
            "INSERT INTO Page (id, scroll, notebookId, background, backgroundType, parentFolderId, linkedEventId, createdAt, updatedAt) " +
                "VALUES (?, 0, NULL, 'blank', 'native', NULL, NULL, 1, 1)",
            arrayOf<Any?>(id)
        )
    }

    private fun insertTask(
        id: String,
        dayDate: String,
        dueDay: String?,
        completed: Int,
        typedText: String?,
        parentId: String? = null,
        rowIndex: Int = 0
    ) {
        sql.execSQL(
            "INSERT INTO task (id, dayDate, taskPageIndex, rowIndex, pageId, isCompleted, priority, typedText, " +
                "parentTaskId, dueDay, migrationCount, createdAt, updatedAt) VALUES (?, ?, 0, ?, 'page-1', ?, '', ?, ?, ?, 0, 1, 1)",
            arrayOf<Any?>(id, dayDate, rowIndex, completed, typedText, parentId, dueDay)
        )
    }
}
