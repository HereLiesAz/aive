package com.hereliesaz.geministrator.persistence

import android.content.Context
import app.cash.sqldelight.async.coroutines.synchronous
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import com.hereliesaz.geministrator.persistence.db.WorkflowDatabase
import java.io.File

/**
 * Supplies the application context the Android workflow database needs. Call [install] before
 * anything touches [WorkflowPersistenceFactory.durable] (MainActivity does, first thing).
 */
object AndroidWorkflowPersistence {
    @Volatile
    private var context: Context? = null

    fun install(context: Context) {
        this.context = context.applicationContext
    }

    internal fun open(): DurableWorkflowPersistence {
        val appContext = checkNotNull(context) {
            "AndroidWorkflowPersistence.install(context) must run before workflow persistence is used"
        }
        return SqlWorkflowPersistence(
            driver = AndroidSqliteDriver(WorkflowDatabase.Schema.synchronous(), appContext, DATABASE_NAME),
            migrateFrom = LegacySettingsMigration(SettingsWorkflowPersistence.createDefault()) { exported ->
                // App-private; the user's own data, kept until they clear app data or uninstall.
                File(appContext.filesDir, "workflows-settings-backup-${System.currentTimeMillis()}.json").writeText(exported)
            },
        )
    }

    const val DATABASE_NAME: String = "aive-workflows.db"
}

/** Workflow state in the app's aive-workflows.db; the earlier SharedPreferences store moves in on first use. */
internal actual fun openDurableWorkflowPersistence(): DurableWorkflowPersistence = AndroidWorkflowPersistence.open()
