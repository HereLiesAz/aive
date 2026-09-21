package com.hereliesaz.aive

import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import com.hereliesaz.geministrator.domain.RoleSurface
import com.hereliesaz.geministrator.domain.SpreadsheetFormat
import com.hereliesaz.geministrator.domain.SpreadsheetSource
import com.hereliesaz.geministrator.domain.SqlDatabaseSource
import com.hereliesaz.geministrator.workflow.AiveRoleSurfaceEnvelope
import com.hereliesaz.geministrator.workflow.AiveSurfaceMutation
import com.hereliesaz.geministrator.workflow.AiveSurfaceMutationOperation
import com.hereliesaz.geministrator.workflow.AiveSurfaceTable
import com.hereliesaz.geministrator.workflow.RoleSurfaceIntegration
import com.hereliesaz.geministrator.workflow.writableSurface
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal class AndroidRoleSurfaceIntegration(
    context: Context,
    private val httpClient: HttpClient,
) : RoleSurfaceIntegration {
    private val appContext = context.applicationContext
    private val spreadsheetRoot = File(appContext.filesDir, "role-surfaces/spreadsheets")
    private val mutationPrefs = appContext.getSharedPreferences("aive_role_surface_mutations", Context.MODE_PRIVATE)

    override fun supports(surface: RoleSurface): Boolean =
        surface is RoleSurface.Spreadsheet || surface is RoleSurface.Sql

    override suspend fun resolve(surface: RoleSurface): AiveRoleSurfaceEnvelope = when (surface) {
        is RoleSurface.Spreadsheet -> resolveSpreadsheet(surface)
        is RoleSurface.Sql -> resolveSql(surface)
        is RoleSurface.Flowchart -> error("Flowcharts are resolved by the common role-surface runtime")
    }

    override suspend fun apply(
        surface: RoleSurface,
        mutation: AiveSurfaceMutation,
        mutationKey: String,
    ) {
        require(surface.writableSurface()) { "Surface ${surface.alias} is read-only" }
        if (mutationPrefs.getBoolean(mutationKey, false)) return
        when (surface) {
            is RoleSurface.Spreadsheet -> applySpreadsheet(surface, mutation)
            is RoleSurface.Sql -> applySql(surface, mutation)
            is RoleSurface.Flowchart -> error("Flowchart surfaces are read-only")
        }
        check(mutationPrefs.edit().putBoolean(mutationKey, true).commit()) {
            "Surface mutation was applied but replay marker could not be persisted"
        }
    }

    private suspend fun resolveSpreadsheet(
        surface: RoleSurface.Spreadsheet,
    ): AiveRoleSurfaceEnvelope = withContext(Dispatchers.IO) {
        val format = resolveSpreadsheetFormat(surface)
        val text = readSpreadsheetText(surface)
        require(text.encodeToByteArray().size <= MAX_SPREADSHEET_BYTES) {
            "Spreadsheet surface ${surface.alias} exceeds ${MAX_SPREADSHEET_BYTES / 1024 / 1024} MiB"
        }
        val table = parseDelimited(
            text = text,
            delimiter = if (format == SpreadsheetFormat.Tsv) '\t' else ',',
            firstRowHeaders = surface.firstRowHeaders,
            maxRows = surface.maxRows,
        )
        AiveRoleSurfaceEnvelope(
            alias = surface.alias,
            kind = "spreadsheet",
            writable = surface.writableSurface(),
            table = table,
            metadata = mapOf(
                "format" to format.name.lowercase(),
                "source" to surface.source.surfaceSourceLabel(),
            ),
        )
    }

    private suspend fun resolveSql(
        surface: RoleSurface.Sql,
    ): AiveRoleSurfaceEnvelope = withContext(Dispatchers.IO) {
        val database = openSqlite(surface.source, writable = false)
        try {
            val cursor = database.rawQuery(surface.query, null)
            cursor.use {
                val columns = it.columnNames.toList()
                val rows = mutableListOf<Map<String, String?>>()
                var truncated = false
                while (it.moveToNext()) {
                    if (rows.size >= surface.maxRows) {
                        truncated = true
                        break
                    }
                    rows += columns.mapIndexed { index, name ->
                        name to it.cellAsString(index)
                    }.toMap(linkedMapOf())
                }
                AiveRoleSurfaceEnvelope(
                    alias = surface.alias,
                    kind = "sql",
                    writable = surface.writableSurface(),
                    table = AiveSurfaceTable(
                        columns = columns,
                        rows = rows,
                        truncated = truncated,
                    ),
                    metadata = mapOf(
                        "dialect" to surface.dialect.name.lowercase(),
                        "source" to surface.source.surfaceSourceLabel(),
                        "query" to surface.query,
                    ),
                )
            }
        } finally {
            database.close()
        }
    }

    private suspend fun applySpreadsheet(
        surface: RoleSurface.Spreadsheet,
        mutation: AiveSurfaceMutation,
    ) = withContext(Dispatchers.IO) {
        require(mutation.operation in setOf(
            AiveSurfaceMutationOperation.AppendRows,
            AiveSurfaceMutationOperation.ReplaceRows,
        )) {
            "Spreadsheet surface ${surface.alias} only accepts AppendRows or ReplaceRows mutations"
        }
        require(mutation.rows.size <= MAX_MUTATION_ROWS) {
            "Spreadsheet mutation exceeds $MAX_MUTATION_ROWS rows"
        }

        val format = resolveSpreadsheetFormat(surface)
        val delimiter = if (format == SpreadsheetFormat.Tsv) '\t' else ','
        val existing = if (mutation.operation == AiveSurfaceMutationOperation.AppendRows) {
            parseDelimited(
                text = readSpreadsheetText(surface),
                delimiter = delimiter,
                firstRowHeaders = surface.firstRowHeaders,
                maxRows = MAX_STORED_ROWS,
            )
        } else {
            AiveSurfaceTable(emptyList())
        }
        val allRows = existing.rows + mutation.rows
        require(allRows.size <= MAX_STORED_ROWS) {
            "Spreadsheet surface ${surface.alias} exceeds the $MAX_STORED_ROWS row storage limit"
        }
        val columns = buildColumns(existing.columns, allRows)
        val serialized = serializeDelimited(columns, allRows, delimiter, surface.firstRowHeaders)
        require(serialized.encodeToByteArray().size <= MAX_SPREADSHEET_BYTES) {
            "Spreadsheet surface ${surface.alias} exceeds ${MAX_SPREADSHEET_BYTES / 1024 / 1024} MiB after mutation"
        }
        writeSpreadsheetText(surface, serialized)
    }

    private suspend fun applySql(
        surface: RoleSurface.Sql,
        mutation: AiveSurfaceMutation,
    ) = withContext(Dispatchers.IO) {
        require(mutation.operation == AiveSurfaceMutationOperation.ExecuteSql) {
            "SQL surface ${surface.alias} only accepts ExecuteSql mutations"
        }
        val statement = requireNotNull(mutation.sql)?.trim().orEmpty()
        require(statement.isNotEmpty()) { "SQL mutation for ${surface.alias} is blank" }
        require(statement.length <= MAX_SQL_MUTATION_CHARS) {
            "SQL mutation exceeds $MAX_SQL_MUTATION_CHARS characters"
        }
        require(surface.source is SqlDatabaseSource.AppDatabase) {
            "Only app-owned SQLite databases may be mutated"
        }
        val database = openSqlite(surface.source, writable = true)
        try {
            database.beginTransaction()
            try {
                database.execSQL(statement)
                database.setTransactionSuccessful()
            } finally {
                database.endTransaction()
            }
        } finally {
            database.close()
        }
    }

    private suspend fun readSpreadsheetText(surface: RoleSurface.Spreadsheet): String =
        when (val source = surface.source) {
            is SpreadsheetSource.AppFile -> withContext(Dispatchers.IO) {
                val file = spreadsheetFile(source.name, surface)
                if (file.exists()) file.readText() else ""
            }
            is SpreadsheetSource.Inline -> source.text
            is SpreadsheetSource.DocumentUri -> withContext(Dispatchers.IO) {
                val uri = Uri.parse(source.uri)
                appContext.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                    ?: error("Unable to open spreadsheet document URI")
            }
            is SpreadsheetSource.Https -> {
                require(source.url.startsWith("https://", ignoreCase = true)) {
                    "Spreadsheet network sources must use HTTPS"
                }
                httpClient.get(source.url).bodyAsText()
            }
        }

    private fun writeSpreadsheetText(
        surface: RoleSurface.Spreadsheet,
        text: String,
    ) {
        when (val source = surface.source) {
            is SpreadsheetSource.AppFile -> {
                val file = spreadsheetFile(source.name, surface)
                file.parentFile?.mkdirs()
                file.writeText(text)
            }
            is SpreadsheetSource.DocumentUri -> {
                val uri = Uri.parse(source.uri)
                val output = appContext.contentResolver.openOutputStream(uri, "wt")
                    ?: error("Unable to open spreadsheet document URI for writing")
                output.bufferedWriter().use { it.write(text) }
            }
            is SpreadsheetSource.Inline,
            is SpreadsheetSource.Https,
            -> error("Spreadsheet source ${source::class.simpleName} is read-only")
        }
    }

    private fun resolveSpreadsheetFormat(surface: RoleSurface.Spreadsheet): SpreadsheetFormat =
        when (surface.format) {
            SpreadsheetFormat.Csv -> SpreadsheetFormat.Csv
            SpreadsheetFormat.Tsv -> SpreadsheetFormat.Tsv
            SpreadsheetFormat.Auto -> when (val source = surface.source) {
                is SpreadsheetSource.AppFile ->
                    if (source.name.endsWith(".tsv", ignoreCase = true)) SpreadsheetFormat.Tsv else SpreadsheetFormat.Csv
                is SpreadsheetSource.DocumentUri ->
                    if (source.uri.substringBefore('?').endsWith(".tsv", ignoreCase = true)) SpreadsheetFormat.Tsv else SpreadsheetFormat.Csv
                is SpreadsheetSource.Https ->
                    if (source.url.substringBefore('?').endsWith(".tsv", ignoreCase = true)) SpreadsheetFormat.Tsv else SpreadsheetFormat.Csv
                is SpreadsheetSource.Inline ->
                    if (source.text.lineSequence().firstOrNull().orEmpty().count { it == '\t' } >
                        source.text.lineSequence().firstOrNull().orEmpty().count { it == ',' }
                    ) SpreadsheetFormat.Tsv else SpreadsheetFormat.Csv
            }
        }

    private fun spreadsheetFile(
        name: String,
        surface: RoleSurface.Spreadsheet,
    ): File {
        val safe = safeFileName(name)
        val extension = when (resolveSpreadsheetFormat(surface)) {
            SpreadsheetFormat.Tsv -> ".tsv"
            SpreadsheetFormat.Csv,
            SpreadsheetFormat.Auto,
            -> ".csv"
        }
        val fileName = if (safe.endsWith(".csv", true) || safe.endsWith(".tsv", true)) safe else safe + extension
        return File(spreadsheetRoot, fileName)
    }

    private fun openSqlite(
        source: SqlDatabaseSource,
        writable: Boolean,
    ): SQLiteDatabase = when (source) {
        is SqlDatabaseSource.AppDatabase -> {
            val name = safeFileName(source.name).let {
                if (it.endsWith(".db", true) || it.endsWith(".sqlite", true)) it else "$it.db"
            }
            if (writable) {
                appContext.openOrCreateDatabase(name, Context.MODE_PRIVATE, null)
            } else {
                val file = appContext.getDatabasePath(name)
                if (!file.exists()) {
                    appContext.openOrCreateDatabase(name, Context.MODE_PRIVATE, null).close()
                }
                SQLiteDatabase.openDatabase(file.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
            }
        }
        is SqlDatabaseSource.DocumentUri -> {
            require(!writable) { "Document-URI SQLite databases are read-only" }
            val temp = File.createTempFile("aive-sql-", ".db", appContext.cacheDir)
            appContext.contentResolver.openInputStream(Uri.parse(source.uri)).use { input ->
                requireNotNull(input) { "Unable to open SQLite document URI" }
                temp.outputStream().use(input::copyTo)
            }
            SQLiteDatabase.openDatabase(
                temp.absolutePath,
                null,
                SQLiteDatabase.OPEN_READONLY or SQLiteDatabase.NO_LOCALIZED_COLLATORS,
            )
        }
    }

    private fun Cursor.cellAsString(index: Int): String? = when (getType(index)) {
        Cursor.FIELD_TYPE_NULL -> null
        Cursor.FIELD_TYPE_BLOB -> getBlob(index).joinToString("") { byte -> "%02x".format(byte) }
        else -> getString(index)
    }

    private fun parseDelimited(
        text: String,
        delimiter: Char,
        firstRowHeaders: Boolean,
        maxRows: Int,
    ): AiveSurfaceTable {
        if (text.isBlank()) return AiveSurfaceTable(emptyList())
        val parsed = mutableListOf<List<String>>()
        var row = mutableListOf<String>()
        val cell = StringBuilder()
        var quoted = false
        var index = 0
        while (index < text.length) {
            val char = text[index]
            when {
                char == '"' && quoted && index + 1 < text.length && text[index + 1] == '"' -> {
                    cell.append('"')
                    index += 1
                }
                char == '"' -> quoted = !quoted
                char == delimiter && !quoted -> {
                    row += cell.toString()
                    cell.clear()
                }
                (char == '\n' || char == '\r') && !quoted -> {
                    if (char == '\r' && index + 1 < text.length && text[index + 1] == '\n') index += 1
                    row += cell.toString()
                    cell.clear()
                    parsed += row
                    row = mutableListOf()
                    if (parsed.size > maxRows + if (firstRowHeaders) 1 else 0) break
                }
                else -> cell.append(char)
            }
            index += 1
        }
        if (cell.isNotEmpty() || row.isNotEmpty()) {
            row += cell.toString()
            parsed += row
        }

        if (parsed.isEmpty()) return AiveSurfaceTable(emptyList())
        val rawColumns = if (firstRowHeaders) {
            parsed.first()
        } else {
            val count = parsed.maxOfOrNull(List<String>::size) ?: 0
            (1..count).map { "column_$it" }
        }
        val columns = uniqueColumns(rawColumns)
        val dataRows = if (firstRowHeaders) parsed.drop(1) else parsed
        val truncated = dataRows.size > maxRows
        val rows = dataRows.take(maxRows).map { values ->
            columns.mapIndexed { columnIndex, column ->
                column to values.getOrNull(columnIndex)
            }.toMap(linkedMapOf())
        }
        return AiveSurfaceTable(columns, rows, truncated)
    }

    private fun uniqueColumns(raw: List<String>): List<String> {
        val seen = linkedMapOf<String, Int>()
        return raw.mapIndexed { index, value ->
            val base = value.trim().ifBlank { "column_${index + 1}" }
            val count = (seen[base] ?: 0) + 1
            seen[base] = count
            if (count == 1) base else "${base}_$count"
        }
    }

    private fun buildColumns(
        existing: List<String>,
        rows: List<Map<String, String?>>,
    ): List<String> = buildList {
        addAll(existing)
        rows.forEach { row ->
            row.keys.forEach { key ->
                if (key !in this) add(key)
            }
        }
    }

    private fun serializeDelimited(
        columns: List<String>,
        rows: List<Map<String, String?>>,
        delimiter: Char,
        firstRowHeaders: Boolean,
    ): String = buildString {
        if (firstRowHeaders && columns.isNotEmpty()) {
            append(columns.joinToString(delimiter.toString()) { escapeCell(it, delimiter) })
            append('\n')
        }
        rows.forEachIndexed { index, row ->
            append(columns.joinToString(delimiter.toString()) { column ->
                escapeCell(row[column].orEmpty(), delimiter)
            })
            if (index != rows.lastIndex) append('\n')
        }
    }

    private fun escapeCell(value: String, delimiter: Char): String =
        if (value.any { it == delimiter || it == '"' || it == '\n' || it == '\r' }) {
            """ + value.replace(""", """") + """
        } else {
            value
        }

    private fun SpreadsheetSource.surfaceSourceLabel(): String = when (this) {
        is SpreadsheetSource.AppFile -> "app-file"
        is SpreadsheetSource.Inline -> "inline"
        is SpreadsheetSource.DocumentUri -> "document-uri"
        is SpreadsheetSource.Https -> "https"
    }

    private fun SqlDatabaseSource.surfaceSourceLabel(): String = when (this) {
        is SqlDatabaseSource.AppDatabase -> "app-database"
        is SqlDatabaseSource.DocumentUri -> "document-uri"
    }

    private fun safeFileName(value: String): String =
        value.trim()
            .replace(Regex("""[^A-Za-z0-9._-]"""), "_")
            .take(160)
            .ifBlank { "surface" }

    private companion object {
        const val MAX_SPREADSHEET_BYTES = 5 * 1024 * 1024
        const val MAX_STORED_ROWS = 10_000
        const val MAX_MUTATION_ROWS = 2_000
        const val MAX_SQL_MUTATION_CHARS = 100_000
    }
}
