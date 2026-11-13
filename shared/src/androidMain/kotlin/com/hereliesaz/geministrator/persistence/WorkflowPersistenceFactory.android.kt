package com.hereliesaz.geministrator.persistence

import android.content.Context
import app.cash.sqldelight.async.coroutines.synchronous
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import com.hereliesaz.geministrator.persistence.db.WorkflowDatabase
import com.hereliesaz.geministrator.prompts.RememberedTextSettings
import com.russhwolf.settings.SharedPreferencesSettings
import java.io.File
import kotlinx.coroutines.Dispatchers

/**
 * Supplies the application context the Android workflow database needs. Call [install] before
 * anything touches [WorkflowPersistenceFactory.durable] (MainActivity does, first thing).
 */
object AndroidWorkflowPersistence {
    @Volatile
    private var context: Context? = null

    fun install(context: Context) {
        this.context = context.applicationContext
        // Remembered text lives in its own preferences file so the backup rules can leave it out.
        // The MCP server list and the trace exporter's OTLP headers likewise, so backup can leave them out.
        if (PrivateSettings.separate == null) {
            PrivateSettings.separate = SharedPreferencesSettings(
                this.context!!.getSharedPreferences(PRIVATE_PREFERENCES, Context.MODE_PRIVATE),
            )
        }
        if (RememberedTextSettings.separate == null) {
            RememberedTextSettings.separate = SharedPreferencesSettings(
                this.context!!.getSharedPreferences(REMEMBERED_TEXT_PREFERENCES, Context.MODE_PRIVATE),
            )
        }
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
            // The driver is synchronous: keep its disk work off the main thread.
            dispatcher = Dispatchers.IO,
        )
    }

    const val DATABASE_NAME: String = "aive-workflows.db"

    /** The preferences file (`aive_remembered_text.xml`) the backup rules exclude. */
    const val REMEMBERED_TEXT_PREFERENCES: String = "aive_remembered_text"
    const val PRIVATE_PREFERENCES: String = "aive_private_settings"
}

/** Workflow state in the app's aive-workflows.db; the earlier SharedPreferences store moves in on first use. */
internal actual fun openDurableWorkflowPersistence(): DurableWorkflowPersistence = AndroidWorkflowPersistence.open()
