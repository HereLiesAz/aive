package com.hereliesaz.geministrator.template.presets

import com.hereliesaz.geministrator.domain.ArtifactKind
import com.hereliesaz.geministrator.domain.PortSpec
import com.hereliesaz.geministrator.domain.PortType
import com.hereliesaz.geministrator.template.BuiltInTemplates
import com.hereliesaz.geministrator.template.StepTemplate
import com.hereliesaz.geministrator.workflow.StepOutputValidators

/* Starting templates of one group of the catalog (docs/Step-Templates.md, "The starting templates"). Data only, made by [PresetKit]. */
internal object WritingForPeoplePresets {
    val EmailDraft: StepTemplate = PresetKit.make(
        BuiltInTemplates.WriteDocument, "email-draft", "worker",
        listOf(PortSpec("purpose", PortType.Text, true), PortSpec("recipient", PortType.Text, false), PortSpec("facts", PortType.Text, false)),
        PresetKit.out("email", ArtifactKind.Research, "text/plain", "email-draft/v1", "email-marked"),
        "Draft the message for the purpose given, for the keeper to read and send. Use only the facts given. Begin with a `Subject:` line, then the message, in at most 4000 characters. Use only what the inputs give: invent no fact, figure or source, and do not claim to have sent, saved or published anything. Hand back the result and nothing else.",
        20_000L,
    )

    val NegotiationReplyDraft: StepTemplate = PresetKit.make(
        BuiltInTemplates.WriteDocument, "negotiation-reply-draft", "worker",
        listOf(PortSpec("their_message", PortType.Text, true), PortSpec("position", PortType.Text, true)),
        PresetKit.out("reply", ArtifactKind.Research, "text/plain", "negotiation-reply-draft/v1", "email-marked"),
        "Draft a reply to their message that states what is wanted, what can be given and what is not open, in the position given. Concede nothing the position does not allow. Begin with a `Subject:` line, then the message, in at most 4000 characters. Use only what the inputs give: invent no fact, figure or source, and do not claim to have sent, saved or published anything. Hand back the result and nothing else.",
        20_000L,
    )

    val PressRelease: StepTemplate = PresetKit.make(
        BuiltInTemplates.WriteDocument, "press-release", "worker",
        listOf(PortSpec("facts", PortType.Text, true), PortSpec("audience", PortType.Text, false)),
        PresetKit.out("release", ArtifactKind.Research, "text/markdown", "press-release/v1", "sections-press-release"),
        "Draft an announcement for journalists from the facts supplied. Add no quote, name or figure the facts do not give. Give it these headings, in this order: `## Headline`, `## Body`, `## About`. Use only what the inputs give: invent no fact, figure or source, and do not claim to have sent, saved or published anything. Hand back the result and nothing else.",
        20_000L,
    )

    val SocialPostSet: StepTemplate = PresetKit.make(
        BuiltInTemplates.TextToTable, "social-post-set", "worker",
        listOf(PortSpec("message", PortType.Text, true), PortSpec("places", PortType.Text, true)),
        PresetKit.out("posts", ArtifactKind.Research, "text/csv", "social-post-set/v1", "columns-posts"),
        "Draft one short post for each place named, fit to that place's usual length and tone. One row per place. Hand back comma-separated text: a header row with exactly these columns: place, text, then one row per item. Quote any cell that holds a comma. Use only what the inputs give: invent no fact, figure or source, and do not claim to have sent, saved or published anything. Hand back the result and nothing else.",
        20_000L,
    )

    val PolicyDraft: StepTemplate = PresetKit.make(
        BuiltInTemplates.WriteDocument, "policy-draft", "worker",
        listOf(PortSpec("rules", PortType.Text, true), PortSpec("facts", PortType.Text, false)),
        PresetKit.out("policy", ArtifactKind.Research, "text/markdown", "policy-draft/v1", "policy-marked"),
        "Draft a policy from the rules given. Begin with the line `DRAFT for review: not legal, tax, medical or financial advice.` Add no rule the keeper did not give. Then give it at least two headed sections. Use only what the inputs give: invent no fact, figure or source, and do not claim to have sent, saved or published anything. Hand back the result and nothing else.",
        20_000L,
    )

    val all: List<StepTemplate> = listOf(EmailDraft, NegotiationReplyDraft, PressRelease, SocialPostSet, PolicyDraft)
}
