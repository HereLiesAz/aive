package com.hereliesaz.geministrator.workflow

object DelimitedTableCodec {
    fun parse(
        text: String,
        delimiter: Char,
        firstRowHeaders: Boolean,
        maxRows: Int,
    ): AiveSurfaceTable {
        require(maxRows >= 1) { "maxRows must be positive" }
        if (text.isBlank()) return AiveSurfaceTable(emptyList())

        val parsed = mutableListOf<List<String>>()
        var row = mutableListOf<String>()
        val cell = StringBuilder()
        var quoted = false
        var index = 0
        val physicalLimit = maxRows + if (firstRowHeaders) 1 else 0

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
                    if (char == '\r' && index + 1 < text.length && text[index + 1] == '\n') {
                        index += 1
                    }
                    row += cell.toString()
                    cell.clear()
                    parsed += row
                    row = mutableListOf()
                    if (parsed.size > physicalLimit) break
                }
                else -> cell.append(char)
            }
            index += 1
        }

        require(!quoted) { "Delimited spreadsheet contains an unterminated quoted cell" }
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

    fun serialize(
        columns: List<String>,
        rows: List<Map<String, String?>>,
        delimiter: Char,
        firstRowHeaders: Boolean,
    ): String = buildString {
        if (firstRowHeaders && columns.isNotEmpty()) {
            append(columns.joinToString(delimiter.toString()) { escapeCell(it, delimiter) })
            if (rows.isNotEmpty()) append('\n')
        }
        rows.forEachIndexed { index, row ->
            append(columns.joinToString(delimiter.toString()) { column ->
                escapeCell(row[column].orEmpty(), delimiter)
            })
            if (index != rows.lastIndex) append('\n')
        }
    }

    fun columnsFor(
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

    private fun uniqueColumns(raw: List<String>): List<String> {
        val seen = linkedMapOf<String, Int>()
        return raw.mapIndexed { index, value ->
            val base = value.trim().ifBlank { "column_${index + 1}" }
            val count = (seen[base] ?: 0) + 1
            seen[base] = count
            if (count == 1) base else "${base}_$count"
        }
    }

    private fun escapeCell(value: String, delimiter: Char): String =
        if (value.any { it == delimiter || it == '"' || it == '\n' || it == '\r' }) {
            "\"" + value.replace("\"", "\"\"") + "\""
        } else {
            value
        }
}
