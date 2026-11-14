package com.hereliesaz.geministrator.template.presets

import com.hereliesaz.geministrator.domain.ArtifactKind
import com.hereliesaz.geministrator.domain.PortSpec
import com.hereliesaz.geministrator.domain.PortType
import com.hereliesaz.geministrator.template.BuiltInTemplates
import com.hereliesaz.geministrator.template.StepTemplate
import com.hereliesaz.geministrator.workflow.StepOutputValidators

/* Starting templates of one group of the catalog (docs/Step-Templates.md, "The starting templates"). Data only, made by [PresetKit]. */
internal object DataAndTablesPresets {
    val CsvFromText: StepTemplate = PresetKit.make(
        BuiltInTemplates.TextToTable, "csv-from-text", "worker",
        listOf(PortSpec("text", PortType.Text, true), PortSpec("columns", PortType.Text, false)),
        PresetKit.out("table", ArtifactKind.Research, "text/csv", "csv-from-text/v1", StepOutputValidators.TABLE_CSV_FILLED),
        "Turn the text into a table with a header row. If columns are named, use exactly those. Hand back comma-separated text: a header row naming every column, then one row per item. Quote any cell that holds a comma. Use only what the inputs give: invent no fact, figure or source, and do not claim to have sent, saved or published anything. Hand back the result and nothing else.",
        20_000L,
    )

    val DataCleanup: StepTemplate = PresetKit.make(
        BuiltInTemplates.TextToTable, "data-cleanup", "worker",
        listOf(PortSpec("table", PortType.Table, true), PortSpec("rules", PortType.Text, true)),
        PresetKit.out("cleaned", ArtifactKind.Research, "text/csv", "data-cleanup/v1", StepOutputValidators.TABLE_CSV_FILLED),
        "Fix the problems the rules name in the table, keeping its columns. Remove duplicates only if the rules say so. Never add a row. Add a final column `fix` saying what you changed in each row, or leave it empty. Hand back comma-separated text: a header row naming every column, then one row per item. Quote any cell that holds a comma. Use only what the inputs give: invent no fact, figure or source, and do not claim to have sent, saved or published anything. Hand back the result and nothing else.",
        20_000L,
    )

    val ExtractFields: StepTemplate = PresetKit.make(
        BuiltInTemplates.TextToTable, "extract-fields", "worker",
        listOf(PortSpec("text", PortType.Text, true), PortSpec("fields", PortType.Text, true)),
        PresetKit.out("fields", ArtifactKind.Research, "text/csv", "extract-fields/v1", StepOutputValidators.TABLE_CSV_FILLED),
        "Pull the named fields out of the text into rows, one row per record. Leave a cell empty when the text does not give the field; guess nothing. Hand back comma-separated text: a header row naming every column, then one row per item. Quote any cell that holds a comma. Use only what the inputs give: invent no fact, figure or source, and do not claim to have sent, saved or published anything. Hand back the result and nothing else.",
        20_000L,
    )

    val SpreadsheetWithFormulas: StepTemplate = PresetKit.make(
        BuiltInTemplates.TextToTable, "spreadsheet-with-formulas", "worker",
        listOf(PortSpec("brief", PortType.Text, true), PortSpec("data", PortType.Table, false)),
        PresetKit.out("sheet", ArtifactKind.Research, "text/csv", "spreadsheet-with-formulas/v1", StepOutputValidators.TABLE_CSV_FILLED),
        "Lay out a table for the brief, with formulas written as text (for example `=SUM(B2:B9)`) for the keeper to open in a sheet. Evaluate nothing yourself. Hand back comma-separated text: a header row naming every column, then one row per item. Quote any cell that holds a comma. Use only what the inputs give: invent no fact, figure or source, and do not claim to have sent, saved or published anything. Hand back the result and nothing else.",
        20_000L,
    )

    val DataCheckReport: StepTemplate = PresetKit.make(
        BuiltInTemplates.CheckWork, "data-check-report", "reviewer",
        listOf(PortSpec("table", PortType.Table, true), PortSpec("rules", PortType.Text, true)),
        PresetKit.out("verdict", ArtifactKind.Verification, "text/plain", "data-check-report/v1", StepOutputValidators.VERDICT),
        "List every row of the table that breaks the rules given. Begin with `VERDICT: approve` (no row breaks them) or `VERDICT: reject`, then one line per broken row naming the row and the rule. Use only what the inputs give: invent no fact, figure or source, and do not claim to have sent, saved or published anything. Hand back the result and nothing else.",
        20_000L,
    )

    val all: List<StepTemplate> = listOf(CsvFromText, DataCleanup, ExtractFields, SpreadsheetWithFormulas, DataCheckReport)
}
