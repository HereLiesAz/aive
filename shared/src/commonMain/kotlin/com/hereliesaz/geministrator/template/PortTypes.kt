package com.hereliesaz.geministrator.template

import com.hereliesaz.geministrator.domain.OutputSpec
import com.hereliesaz.geministrator.domain.PortType
import com.hereliesaz.geministrator.workflow.StepOutputValidators

/** Which [PortType] an output is, and which outputs an input port of a given type takes (docs/Step-Templates.md, "Kinds of output"). */
object PortTypes {
    /** The kind of output [spec] makes: by its compiled check first, then by its media type. */
    fun of(spec: OutputSpec): PortType = when (spec.validatorId) {
        StepOutputValidators.MERMAID_FLOWCHART, StepOutputValidators.MERMAID_DIAGRAM -> PortType.Mermaid
        StepOutputValidators.JSON_OBJECT, StepOutputValidators.JSON_VALID -> PortType.Json
        StepOutputValidators.DIFF_UNIFIED -> PortType.Diff
        StepOutputValidators.CHECKLIST -> PortType.Checklist
        StepOutputValidators.PLAN_STEPS -> PortType.Plan
        StepOutputValidators.SCRIPT_SCENES -> PortType.Script
        "sections-report" -> PortType.Report
        "media-brief" -> PortType.MediaBrief
        else -> when (spec.mediaType.substringBefore(';').trim().lowercase()) {
            "text/markdown" -> PortType.Markdown
            "text/csv" -> PortType.Table
            "text/html" -> PortType.Html
            "application/json" -> PortType.Json
            "text/vnd.mermaid" -> PortType.Mermaid
            "text/x-diff" -> PortType.Diff
            else -> PortType.Text
        }
    }

    private val markdownLike = setOf(PortType.Markdown, PortType.Plan, PortType.Checklist, PortType.Script, PortType.Report, PortType.MediaBrief)

    /** True when an input port of type [port] takes an output of type [produced]. [PortType.Any] takes all; [PortType.Text] takes any text. */
    fun accepts(port: PortType, produced: PortType): Boolean = when {
        port == PortType.Any || port == PortType.Text || port == produced -> true
        port == PortType.Markdown -> produced in markdownLike
        port == PortType.Research -> produced in markdownLike || produced == PortType.Table
        else -> false
    }
}
