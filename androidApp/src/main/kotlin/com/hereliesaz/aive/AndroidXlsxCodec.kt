package com.hereliesaz.aive

import com.hereliesaz.geministrator.workflow.AiveSurfaceTable
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory

internal object AndroidXlsxCodec {
    fun parse(
        bytes: ByteArray,
        firstRowHeaders: Boolean,
        maxRows: Int,
    ): AiveSurfaceTable {
        require(bytes.size <= MAX_XLSX_BYTES) { "XLSX file exceeds ${MAX_XLSX_BYTES / 1024 / 1024} MiB" }
        val entries = unzip(bytes)
        val sharedStrings = entries["xl/sharedStrings.xml"]?.let(::parseSharedStrings).orEmpty()
        val sheetPath = firstWorksheetPath(entries)
        val sheet = requireNotNull(entries[sheetPath]) { "XLSX contains no readable worksheet" }
        val rows = parseSheet(sheet, sharedStrings)
        if (rows.isEmpty()) return AiveSurfaceTable(emptyList())

        val width = rows.maxOfOrNull { it.keys.maxOrNull()?.plus(1) ?: 0 } ?: 0
        val headerValues = if (firstRowHeaders) {
            val first = rows.first()
            (0 until width).map { index -> first[index].orEmpty() }
        } else {
            (1..width).map { "column_$it" }
        }
        val columns = uniqueColumns(headerValues)
        val dataRows = if (firstRowHeaders) rows.drop(1) else rows
        val truncated = dataRows.size > maxRows
        val mapped = dataRows.take(maxRows).map { row ->
            columns.mapIndexed { index, name -> name to row[index] }.toMap(linkedMapOf())
        }
        return AiveSurfaceTable(columns, mapped, truncated)
    }

    fun serialize(
        columns: List<String>,
        rows: List<Map<String, String?>>,
        firstRowHeaders: Boolean,
    ): ByteArray {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            writeEntry(zip, "[Content_Types].xml", contentTypes())
            writeEntry(zip, "_rels/.rels", rootRelationships())
            writeEntry(zip, "xl/workbook.xml", workbook())
            writeEntry(zip, "xl/_rels/workbook.xml.rels", workbookRelationships())
            writeEntry(zip, "xl/worksheets/sheet1.xml", worksheetXml(columns, rows, firstRowHeaders))
        }
        return output.toByteArray().also {
            require(it.size <= MAX_XLSX_BYTES) { "Serialized XLSX exceeds ${MAX_XLSX_BYTES / 1024 / 1024} MiB" }
        }
    }

    private fun unzip(bytes: ByteArray): Map<String, ByteArray> = buildMap {
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (!entry.isDirectory) {
                    require(entry.size < 0 || entry.size <= MAX_ENTRY_BYTES) {
                        "XLSX entry ${entry.name} is too large"
                    }
                    val data = zip.readBytes()
                    require(data.size <= MAX_ENTRY_BYTES) { "XLSX entry ${entry.name} is too large" }
                    put(entry.name, data)
                }
                zip.closeEntry()
            }
        }
    }

    private fun firstWorksheetPath(entries: Map<String, ByteArray>): String {
        val workbook = entries["xl/workbook.xml"] ?: return "xl/worksheets/sheet1.xml"
        val rels = entries["xl/_rels/workbook.xml.rels"] ?: return "xl/worksheets/sheet1.xml"
        val relationshipId = parseFirstSheetRelationshipId(workbook) ?: return "xl/worksheets/sheet1.xml"
        val target = parseRelationshipTarget(rels, relationshipId) ?: return "xl/worksheets/sheet1.xml"
        return if (target.startsWith("/")) target.removePrefix("/") else "xl/" + target.removePrefix("./")
    }

    private fun parseFirstSheetRelationshipId(bytes: ByteArray): String? {
        val parser = parser(bytes)
        while (parser.eventType != XmlPullParser.END_DOCUMENT) {
            if (parser.eventType == XmlPullParser.START_TAG && parser.name == "sheet") {
                for (index in 0 until parser.attributeCount) {
                    val name = parser.getAttributeName(index)
                    if (name == "id" || name == "r:id") return parser.getAttributeValue(index)
                }
            }
            parser.next()
        }
        return null
    }

    private fun parseRelationshipTarget(bytes: ByteArray, id: String): String? {
        val parser = parser(bytes)
        while (parser.eventType != XmlPullParser.END_DOCUMENT) {
            if (parser.eventType == XmlPullParser.START_TAG && parser.name == "Relationship") {
                val currentId = parser.getAttributeValue(null, "Id")
                if (currentId == id) return parser.getAttributeValue(null, "Target")
            }
            parser.next()
        }
        return null
    }

    private fun parseSharedStrings(bytes: ByteArray): List<String> {
        val parser = parser(bytes)
        val strings = mutableListOf<String>()
        var insideItem = false
        val current = StringBuilder()
        while (parser.eventType != XmlPullParser.END_DOCUMENT) {
            when (parser.eventType) {
                XmlPullParser.START_TAG -> if (parser.name == "si") {
                    insideItem = true
                    current.clear()
                }
                XmlPullParser.TEXT -> if (insideItem) current.append(parser.text)
                XmlPullParser.END_TAG -> if (parser.name == "si" && insideItem) {
                    strings += current.toString()
                    insideItem = false
                }
            }
            parser.next()
        }
        return strings
    }

    private fun parseSheet(
        bytes: ByteArray,
        sharedStrings: List<String>,
    ): List<Map<Int, String?>> {
        val parser = parser(bytes)
        val rows = mutableListOf<Map<Int, String?>>()
        var currentRow = linkedMapOf<Int, String?>()
        var currentCellRef: String? = null
        var currentType: String? = null
        var captureValue = false
        var value = StringBuilder()

        while (parser.eventType != XmlPullParser.END_DOCUMENT) {
            when (parser.eventType) {
                XmlPullParser.START_TAG -> when (parser.name) {
                    "row" -> currentRow = linkedMapOf()
                    "c" -> {
                        currentCellRef = parser.getAttributeValue(null, "r")
                        currentType = parser.getAttributeValue(null, "t")
                    }
                    "v", "t" -> {
                        captureValue = true
                        value = StringBuilder()
                    }
                }
                XmlPullParser.TEXT -> if (captureValue) value.append(parser.text)
                XmlPullParser.END_TAG -> when (parser.name) {
                    "v", "t" -> {
                        captureValue = false
                        val column = currentCellRef?.let(::columnIndexFromReference) ?: currentRow.size
                        val raw = value.toString()
                        currentRow[column] = when (currentType) {
                            "s" -> raw.toIntOrNull()?.let(sharedStrings::getOrNull)
                            else -> raw
                        }
                    }
                    "row" -> rows += currentRow
                }
            }
            parser.next()
        }
        return rows
    }

    private fun parser(bytes: ByteArray): XmlPullParser =
        XmlPullParserFactory.newInstance().newPullParser().apply {
            setInput(ByteArrayInputStream(bytes), "UTF-8")
        }

    private fun columnIndexFromReference(reference: String): Int {
        var value = 0
        reference.takeWhile(Char::isLetter).uppercase().forEach { char ->
            value = value * 26 + (char.code - 'A'.code + 1)
        }
        return (value - 1).coerceAtLeast(0)
    }

    private fun columnName(index: Int): String {
        var value = index + 1
        val result = StringBuilder()
        while (value > 0) {
            val remainder = (value - 1) % 26
            result.append(('A'.code + remainder).toChar())
            value = (value - 1) / 26
        }
        return result.reverse().toString()
    }

    private fun worksheetXml(
        columns: List<String>,
        rows: List<Map<String, String?>>,
        firstRowHeaders: Boolean,
    ): String = buildString {
        append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""")
        append("""<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><sheetData>""")
        var rowNumber = 1
        fun appendRow(values: List<String?>) {
            append("<row r=\"").append(rowNumber).append("\">")
            values.forEachIndexed { index, cell ->
                if (cell != null) {
                    append("<c r=\"")
                        .append(columnName(index))
                        .append(rowNumber)
                        .append("\" t=\"inlineStr\"><is><t xml:space=\"preserve\">")
                        .append(xmlEscape(cell))
                        .append("</t></is></c>")
                }
            }
            append("</row>")
            rowNumber += 1
        }
        if (firstRowHeaders && columns.isNotEmpty()) appendRow(columns)
        rows.forEach { row -> appendRow(columns.map { row[it] }) }
        append("</sheetData></worksheet>")
    }

    private fun contentTypes(): String =
        """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""" +
            """<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">""" +
            """<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>""" +
            """<Default Extension="xml" ContentType="application/xml"/>""" +
            """<Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>""" +
            """<Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>""" +
            """</Types>"""

    private fun rootRelationships(): String =
        """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""" +
            """<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">""" +
            """<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/>""" +
            """</Relationships>"""

    private fun workbook(): String =
        """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""" +
            """<workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">""" +
            """<sheets><sheet name="Sheet1" sheetId="1" r:id="rId1"/></sheets></workbook>"""

    private fun workbookRelationships(): String =
        """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""" +
            """<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">""" +
            """<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/>""" +
            """</Relationships>"""

    private fun writeEntry(zip: ZipOutputStream, name: String, text: String) {
        zip.putNextEntry(ZipEntry(name))
        zip.write(text.encodeToByteArray())
        zip.closeEntry()
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

    private fun xmlEscape(value: String): String = buildString(value.length) {
        value.forEach { char ->
            when (char) {
                '&' -> append("&amp;")
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                '"' -> append("&quot;")
                '\'' -> append("&apos;")
                else -> append(char)
            }
        }
    }

    private const val MAX_XLSX_BYTES = 20 * 1024 * 1024
    private const val MAX_ENTRY_BYTES = 12 * 1024 * 1024
}
