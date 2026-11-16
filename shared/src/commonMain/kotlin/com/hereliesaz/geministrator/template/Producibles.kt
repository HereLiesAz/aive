package com.hereliesaz.geministrator.template

/*
 * The catalog of what a swarm can be asked to make (docs/Step-Templates.md, "The starting templates"): 66 producibles in 13
 * groups, and the kinds of output no producible of the 66 covers. Each names the template that makes it ([templateId], in
 * BuiltInTemplates.byId), the role that suits it, and its review: the criteria a reviewer is given, what code already checked,
 * and what is left to the reviewer. Data only; nothing here calls a model.
 */
data class Producible(
    val id: String,
    val name: String,
    val group: String,
    val templateId: String,
    /** The kind of role that suits it (a wording, not a role id: the role templates are separate). */
    val suggestedRole: String,
    /** True when a reviewer can usefully review it; false with [notReviewableReason] and [instead]. */
    val reviewable: Boolean,
    val criteria: List<String>,
    val alreadyChecked: String,
    val leftToReviewer: String,
    val notReviewableReason: String? = null,
    val instead: String? = null,
)

data class ProducibleGroup(val id: String, val title: String, val members: List<String>)

object Producibles {
    val groups: List<ProducibleGroup> = listOf(
        ProducibleGroup("documents", "Documents and briefs", listOf("brief", "report", "summary", "one-page-explainer", "faq", "glossary", "translation")),
        ProducibleGroup("plans-and-records", "Plans and records", listOf("plan", "timeline", "meeting-notes", "decision-memo", "risk-register")),
        ProducibleGroup("research-and-evidence", "Research and evidence", listOf("research-brief", "source-list", "claim-check", "comparison-table")),
        ProducibleGroup("checks-and-edits", "Checks, critiques and edits", listOf("check-work", "critique", "proofread", "accessibility-check", "clarifying-questions")),
        ProducibleGroup("data-and-tables", "Data and tables", listOf("csv-from-text", "data-cleanup", "extract-fields", "spreadsheet-with-formulas", "data-check-report")),
        ProducibleGroup("pages-and-diagrams", "Pages, diagrams and design", listOf("landing-page", "dashboard-page", "diagram-flowchart", "design-tokens")),
        ProducibleGroup("writing-for-people", "Writing for other people", listOf("email-draft", "negotiation-reply-draft", "press-release", "social-post-set", "policy-draft")),
        ProducibleGroup("story-and-media-text", "Story and film text", listOf("short-film-outline", "script", "storyboard-text", "shot-list")),
        ProducibleGroup("software-writing", "Software documents and reports", listOf("spec", "api-doc", "adr", "changelog-entry", "release-notes", "pr-description", "bug-report", "repro-steps")),
        ProducibleGroup("software-changes", "Software changes and tests (change a repository branch)", listOf("test-plan", "test-cases", "code-change-on-branch", "tests-on-branch")),
        ProducibleGroup("connected-tools", "Connected tools (read, and gated changes)", listOf("read-connected-source", "list-from-connected-source", "change-connected-source", "update-connected-document", "append-rows-to-connected-sheet", "remove-from-connected-tool")),
        ProducibleGroup("swarm-plans-and-teaching", "The swarm's own plan and the tutorial", listOf("swarm-plan", "tutorial-lesson", "tutorial-check-in", "queen-reworded-step")),
        ProducibleGroup("training-and-improvement", "Training and improvement", listOf("practice-result", "training-report", "specialist-lessons", "nanny-finding", "revision-proposal")),
        ProducibleGroup("output-kinds", "Other kinds of output", listOf("checklist", "patch-draft", "image-brief", "audio-brief", "video-brief")),
    )

    /** The 66 of the catalog. */
    val catalog: List<Producible> = listOf(
        Producible(
            "brief", "Brief", "documents", "brief", "writer", true,
            listOf("States one goal in a sentence.", "Names who it is for.", "Lists limits that can be checked.", "Has a 'done when' that a stranger could test.", "Adds no requirement the goal did not imply."),
            "at least two headings",
            "Whether each limit is checkable, and whether anything was added or left out.",
        ),
        Producible(
            "report", "Report", "documents", "report", "writer", true,
            listOf("Answers the question the brief asks.", "Each finding points to evidence in the material given.", "Says what is not known or weakly supported.", "Nothing is stated that the material does not support."),
            "headings present, length within limit",
            "Whether findings follow from the evidence; whether anything is invented.",
        ),
        Producible(
            "summary", "Summary", "documents", "summary", "summariser", true,
            listOf("Keeps every decision, number and name the reader needs.", "Adds nothing not in the material.", "Is clearly shorter than the material.", "Reads without the original."),
            "non-empty, one heading",
            "Whether anything essential was dropped or anything added.",
        ),
        Producible(
            "one-page-explainer", "One-page explainer", "documents", "one-page-explainer", "writer", true,
            listOf("Starts with what the idea is, in one plain sentence.", "Uses terms the reader knows or explains them.", "Has an example.", "Stays within one page.", "Claims only what the material or common knowledge supports."),
            "headings, length cap",
            "Whether a newcomer would understand it; whether the example fits.",
        ),
        Producible(
            "faq", "Questions and answers", "documents", "faq", "librarian", true,
            listOf("Each question is one a reader would really ask.", "Each answer is short and answered from the material.", "No question repeats another.", "An unanswerable question is marked, not guessed."),
            "at least two headings",
            "Whether questions are real and answers faithful.",
        ),
        Producible(
            "glossary", "Glossary", "documents", "glossary", "librarian", true,
            listOf("Every term the material relies on has an entry.", "Definitions match how the material uses the word.", "No term appears twice.", "Entries are short and plain."),
            "parses, header on every column, at least one row",
            "Coverage of terms and fidelity of definitions.",
        ),
        Producible(
            "translation", "Translation", "documents", "translation", "translator", true,
            listOf("Says what the source says, nothing more or less.", "Names, code and quotations are unchanged.", "Same headings and lists in the same order.", "Reads naturally in the target language.", "Phrases not translated with confidence are listed."),
            "non-empty, has a heading (when the source had one)",
            "Meaning, tone and naturalness. A reviewer can judge language quality only if its Source reads that language; say so when it cannot.",
        ),
        Producible(
            "plan", "Plan", "plans-and-records", "plan", "planner", true,
            listOf("Every step produces something that can be named.", "Each step says what it waits for; no step waits on itself or in a circle.", "The steps together reach the goal.", "No step does work its neighbour already does.", "Limits are respected."),
            "headings present",
            "Whether the steps cover the goal and the order is sound.",
        ),
        Producible(
            "timeline", "Timeline", "plans-and-records", "timeline", "scheduler", true,
            listOf("Every item given appears once.", "No item starts before what it waits for ends.", "Dates are in one format.", "Limits given are met or the miss is stated."),
            "parses, headers filled",
            "Whether dates and dependencies are consistent. A dates-in-order check could be added later.",
        ),
        Producible(
            "meeting-notes", "Meeting notes", "plans-and-records", "meeting-notes", "archivist", true,
            listOf("Decisions are kept apart from discussion.", "Each action has an owner or says 'not stated'.", "Nothing is attributed to someone who did not say it.", "Open questions are listed."),
            "headings present",
            "Faithfulness to the notes; no invented owners or dates.",
        ),
        Producible(
            "decision-memo", "Decision memo", "plans-and-records", "decision-memo", "analyst", true,
            listOf("The decision is stated as a question, with a deadline if there is one.", "Each option is described fairly.", "The recommendation follows from the reasons.", "Risks of the recommendation are named.", "Facts come from the material."),
            "headings",
            "Whether options are fair and the recommendation follows.",
        ),
        Producible(
            "risk-register", "Risk register", "plans-and-records", "risk-register", "risk-assessor", true,
            listOf("Each risk comes from the plan or material.", "Each has an early signal.", "Impact is stated in plain words, not a made-up number.", "Each has something that reduces it.", "Obvious risks are not missing."),
            "parses, headers filled",
            "Whether risks are real and complete.",
        ),
        Producible(
            "research-brief", "Research brief", "research-and-evidence", "research-brief", "researcher", true,
            listOf("Answers the question asked.", "Each claim names its source and date.", "Says where sources disagree.", "States what could not be found.", "Does not rely on one source for a contested claim."),
            "headings present",
            "Source quality and whether claims match what the sources say.",
        ),
        Producible(
            "source-list", "Source list", "research-and-evidence", "source-list", "source-finder", true,
            listOf("Each source is relevant to the question.", "Each has a fetch date.", "The note says what the source offers, accurately.", "No source appears twice."),
            "parses, headers filled",
            "Relevance and that notes match the sources. A reviewer without web access cannot confirm a source exists; its criteria say 'as far as can be told'.",
        ),
        Producible(
            "claim-check", "Claim check", "research-and-evidence", "claim-check", "fact-checker", true,
            listOf("Every claim of consequence in the work is listed.", "'Supported' is only used when a quoted line shows it.", "Contradictions are marked, not smoothed over.", "Nothing is checked against material outside the sources."),
            "parses, headers filled",
            "Whether claims were missed or a quote does not show what it is said to show.",
        ),
        Producible(
            "comparison-table", "Comparison table", "research-and-evidence", "comparison-table", "analyst", true,
            listOf("Every option and every named point appears.", "Each cell comes from the material or says 'not stated'.", "Options are described on the same footing.", "No cell hides a disagreement in the material."),
            "parses, headers filled",
            "Whether cells are faithful and fair.",
        ),
        Producible(
            "check-work", "Check of work", "checks-and-edits", "check-work", "reviewer", false,
            listOf(),
            "",
            "",
            notReviewableReason = "A verdict is a review. Reviewing it again never ends, and a wrong verdict is already answered by the keeper's decision and, in training, by the dispute path.",
            instead = "The keeper reads it in Messages. A second check-work may be added by the keeper, not by default.",
        ),
        Producible(
            "critique", "Critique", "checks-and-edits", "critique", "reviewer", true,
            listOf("Each finding says where, what fails and what follows.", "A finding that cannot be shown is left out.", "What was checked and found sound is listed.", "No praise or summary stands in for a finding."),
            "heading present",
            "Whether findings are true. Evidence is required; the reviewer re-checks the quoted lines.",
        ),
        Producible(
            "proofread", "Proofread", "checks-and-edits", "proofread", "editor", true,
            listOf("Meaning is unchanged.", "Each change is listed with a reason.", "No fact was added.", "Voice is kept unless the task asked to change it."),
            "one heading",
            "Whether meaning was preserved.",
        ),
        Producible(
            "accessibility-check", "Accessibility check", "checks-and-edits", "accessibility-check", "accessibility-checker", true,
            listOf("Every image has a text alternative.", "Headings run in order, with one main heading.", "Controls have labels.", "Text and background colours in the styles are not close.", "Each finding quotes the line."),
            "VERDICT line",
            "Colour contrast and label sense, which need reading, not parsing.",
        ),
        Producible(
            "clarifying-questions", "Questions for the keeper", "checks-and-edits", "clarifying-questions", "clarifier", false,
            listOf(),
            "",
            "",
            notReviewableReason = "Cheap, short and read by the keeper directly; a reviewer adds a step and a cost for no gain.",
            instead = "The keeper answers or ignores each question in Messages.",
        ),
        Producible(
            "csv-from-text", "Table from text", "data-and-tables", "csv-from-text", "data-wrangler", true,
            listOf("One row per item in the text.", "Columns are exactly those named, when named.", "Nothing in the text is dropped without a note.", "Values are copied, not guessed."),
            "parses, header, rows",
            "Fidelity of rows to the text.",
        ),
        Producible(
            "data-cleanup", "Data cleanup", "data-and-tables", "data-cleanup", "data-wrangler", true,
            listOf("Only the fixes the rules name were made.", "No row was lost without being listed.", "Columns are unchanged.", "Duplicates are judged by the rule stated, not a guess."),
            "parses, headers filled",
            "Whether the fixes match the rules and nothing else changed.",
        ),
        Producible(
            "extract-fields", "Fields from text", "data-and-tables", "extract-fields", "data-wrangler", true,
            listOf("Columns are the fields named.", "Each value appears in the text.", "A missing value is empty, not invented."),
            "parses, headers filled",
            "Whether each value is really in the text.",
        ),
        Producible(
            "spreadsheet-with-formulas", "Sheet layout with formulas", "data-and-tables", "spreadsheet-with-formulas", "spreadsheet-builder", true,
            listOf("Columns match the brief.", "Formula cells are listed in a note.", "Formulas refer to cells that exist.", "No result of a formula is stated as fact."),
            "parses, headers filled",
            "Whether formulas do what the brief says. Not evaluated by code.",
        ),
        Producible(
            "data-check-report", "Data check report", "data-and-tables", "data-check-report", "data-checker", true,
            listOf("Every rule is applied to every row.", "Each broken row names row, rule and value.", "No row is flagged without a rule it breaks."),
            "VERDICT line",
            "Whether rules were applied correctly. Row counting is code-checkable later.",
        ),
        Producible(
            "landing-page", "Landing page", "pages-and-diagrams", "landing-page", "page-designer", true,
            listOf("One main heading says what it is.", "The next step for the reader is clear.", "Uses only the content given.", "Images have text alternatives.", "Works without scripts."),
            "HTML shape, no external scripts, styles or addresses",
            "Clarity and fit to the brief; layout is judged from the markup, not rendered.",
        ),
        Producible(
            "dashboard-page", "Dashboard page", "pages-and-diagrams", "dashboard-page", "page-designer", true,
            listOf("Every figure shown is in the data given.", "Each figure names its source.", "Labels say what is measured and the unit.", "No figure is rounded in a way that misleads."),
            "HTML shape, no external addresses",
            "Figures copied correctly; a code check could confirm every number appears in the input.",
        ),
        Producible(
            "diagram-flowchart", "Flowchart", "pages-and-diagrams", "diagram-flowchart", "diagrammer", true,
            listOf("Every step in the description is a box.", "Arrows follow the order in the description.", "Each label is short and used once.", "Nothing is drawn that the description does not say."),
            "it parses",
            "Whether it matches the description.",
        ),
        Producible(
            "design-tokens", "Design tokens", "pages-and-diagrams", "design-tokens", "page-designer", true,
            listOf("Every value is a valid colour, size or spacing.", "Names say what the token is for.", "Values are consistent with the page given, when one is.", "Text and background pairs are not close in colour."),
            "parses as JSON",
            "Whether values suit the brief.",
        ),
        Producible(
            "email-draft", "Email draft", "writing-for-people", "email-draft", "email-drafter", true,
            listOf("Does what the purpose says.", "Uses only the facts given.", "Tone suits the recipient.", "Has a clear ask or next step.", "Says nothing that cannot be taken back, such as a promise or a date nobody gave."),
            "non-empty, within 20,000 characters",
            "Tone, and whether any statement goes beyond the facts.",
        ),
        Producible(
            "negotiation-reply-draft", "Negotiating reply", "writing-for-people", "negotiation-reply-draft", "email-drafter", true,
            listOf("States what is wanted.", "States what can be given and what is not open.", "Gives nothing the position did not allow.", "Does not bluff or claim facts not in the position.", "Stays civil and short."),
            "non-empty",
            "Whether it goes beyond the position; tone.",
        ),
        Producible(
            "press-release", "Press release", "writing-for-people", "press-release", "comms-drafter", true,
            listOf("Headline says what happened.", "Every fact comes from the facts given.", "No invented quotation or figure.", "Places for a person to confirm are marked."),
            "headings",
            "Whether anything was added to the facts.",
        ),
        Producible(
            "social-post-set", "Set of posts", "writing-for-people", "social-post-set", "comms-drafter", true,
            listOf("One post per place named.", "Each says the message and no more.", "Each respects that place's length.", "No claim beyond the message."),
            "parses, headers filled",
            "Tone and length per place.",
        ),
        Producible(
            "policy-draft", "Policy draft", "writing-for-people", "policy-draft", "policy-drafter", true,
            listOf("Begins with the draft-not-advice line.", "Purpose, coverage, requirements, prohibitions and exceptions are all present.", "Every rule comes from the rules given.", "Places a person must confirm are marked."),
            "headings",
            "Whether any rule was invented.",
        ),
        Producible(
            "short-film-outline", "Short film outline", "story-and-media-text", "short-film-outline", "scriptwriter", true,
            listOf("Idea fits in one sentence.", "Beats are in order and each changes something.", "Within the length asked.", "Names and facts stay the same."),
            "headings",
            "Story sense.",
        ),
        Producible(
            "script", "Script", "story-and-media-text", "script", "scriptwriter", true,
            listOf("Covers every beat of the outline.", "Each scene says what is seen and heard.", "Names and facts match across scenes."),
            "headings",
            "Continuity and fit to the outline.",
        ),
        Producible(
            "storyboard-text", "Storyboard in words", "story-and-media-text", "storyboard-text", "storyboarder", true,
            listOf("Every scene has a shot.", "Order is kept.", "Each shot says frame, camera and length.", "Spoken lines are copied from the script."),
            "parses, headers filled",
            "Coverage and continuity.",
        ),
        Producible(
            "shot-list", "Shot list", "story-and-media-text", "shot-list", "storyboarder", true,
            listOf("One row per shot of the storyboard.", "Order is the same.", "Every shot has a length."),
            "parses, headers filled",
            "Whether any shot was dropped or merged.",
        ),
        Producible(
            "spec", "Specification", "software-writing", "spec", "technical-writer", true,
            listOf("Each requirement can be tested.", "Out of scope is stated.", "Terms are used one way.", "Nothing contradicts the material given."),
            "headings",
            "Testability and contradictions.",
        ),
        Producible(
            "api-doc", "Interface document", "software-writing", "api-doc", "technical-writer", true,
            listOf("Names match the source exactly.", "Every input and output is described.", "Limits and errors stated in the source are included.", "Anything unconfirmed is marked."),
            "headings",
            "Whether names and limits match the source; a reviewer needs the source to check.",
        ),
        Producible(
            "adr", "Decision record", "software-writing", "adr", "archivist", true,
            listOf("The decision is one clear statement.", "Context says why it was needed.", "Alternatives considered are named.", "Consequences include the costs."),
            "headings",
            "Whether it is honest about costs.",
        ),
        Producible(
            "changelog-entry", "Changelog entry", "software-writing", "changelog-entry", "release-notes-writer", true,
            listOf("Every change in the list appears once.", "Each is stated as the list states it.", "Breaking changes are called out."),
            "heading",
            "Whether any effect is claimed that the list does not give.",
        ),
        Producible(
            "release-notes", "Release notes", "software-writing", "release-notes", "release-notes-writer", true,
            listOf("Every change is covered.", "Written for the reader named.", "'Action needed' is present or says none.", "No claim beyond the change list."),
            "headings",
            "Faithfulness to the list.",
        ),
        Producible(
            "pr-description", "Pull request description", "software-writing", "pr-description", "release-notes-writer", true,
            listOf("Says what changed and why.", "Says how to check it.", "Names risks or things not done.", "Every statement is true of the change."),
            "headings",
            "Whether it matches the actual change; a reviewer needs the change to compare.",
        ),
        Producible(
            "bug-report", "Bug report", "software-writing", "bug-report", "bug-reporter", true,
            listOf("Expected and actual are both stated.", "Steps are numbered and in order.", "Seen and suspected are kept apart.", "Does not claim reproduction without a result showing it."),
            "headings",
            "Whether steps would reproduce it.",
        ),
        Producible(
            "repro-steps", "Steps to reproduce", "software-writing", "repro-steps", "bug-reporter", true,
            listOf("Starts from a stated starting point.", "Each step is one action.", "States what should be seen at the end."),
            "heading",
            "Whether another person could follow it.",
        ),
        Producible(
            "test-plan", "Test plan", "software-changes", "test-plan", "test-writer", true,
            listOf("Every requirement in the spec has a case.", "Each case states the pass criterion.", "Edge cases are listed.", "Does not weaken a stated requirement."),
            "headings",
            "Coverage of the spec.",
        ),
        Producible(
            "test-cases", "Test cases", "software-changes", "test-cases", "test-writer", true,
            listOf("Each requirement has at least one case.", "Each case has an expected result that can be seen.", "Cases do not depend on each other unless stated."),
            "parses, headers filled",
            "Coverage and whether expected results are checkable.",
        ),
        Producible(
            "code-change-on-branch", "Code change on a branch", "software-changes", "change-code", "code-implementer", true,
            listOf("Does what the task says and nothing else.", "Touches only files the task allows.", "Tests or checks named in the task pass.", "No keys, passwords or generated files added.", "Each commit says what it changes."),
            "a commit exists on a branch",
            "Correctness and scope; code review is the existing Code reviewer's work.",
        ),
        Producible(
            "tests-on-branch", "Tests on a branch", "software-changes", "tests-on-branch", "test-writer", true,
            listOf("Each test checks one requirement.", "No existing test was weakened or removed.", "Tests fail when the behaviour is wrong.", "Names say what is checked."),
            "a commit exists",
            "Whether tests would catch the bug they are meant to catch.",
        ),
        Producible(
            "read-connected-source", "Read from a connected tool", "connected-tools", "read-connected-source", "connected-reader", false,
            listOf(),
            "",
            "",
            notReviewableReason = "A reviewer without the tool cannot re-read the source, so it could judge wording but not truth.",
            instead = "The keeper's consent on the call and the named call in the output. If faithfulness matters, a second read by another creature and a check-work that compares the two.",
        ),
        Producible(
            "list-from-connected-source", "List from a connected tool", "connected-tools", "list-connected-source", "connected-reader", true,
            listOf("Rows are what the tool returned.", "Columns are those asked for.", "Nothing was added or dropped; truncation is stated.", "The call made is named."),
            "parses, headers filled",
            "Whether rows match what the tool returned; the reviewer sees only the output, so a spot-check needs the raw return.",
        ),
        Producible(
            "change-connected-source", "Change in a connected tool", "connected-tools", "change-connected-source", "connected-editor", false,
            listOf(),
            "",
            "",
            notReviewableReason = "The state of the connected tool is the truth and a reviewer cannot see it.",
            instead = "Read the target back with a read step afterwards and compare; the plan-gate approval is the keeper's review before the change.",
        ),
        Producible(
            "update-connected-document", "Update a connected document", "connected-tools", "update-connected-document", "connected-editor", true,
            listOf("The content to write was checked before the change.", "The change is made to the named part only."),
            "TARGET and CHANGED lines",
            "Review the CONTENT before the step (a check-work on the content), not the result.",
        ),
        Producible(
            "append-rows-to-connected-sheet", "Append rows to a connected sheet", "connected-tools", "append-rows-to-connected-sheet", "connected-editor", true,
            listOf("Rows are those given, in order.", "Appended to the named sheet only.", "Nothing existing was changed."),
            "TARGET and CHANGED lines",
            "Review the ROWS before the step with the data check report; verify after with a read.",
        ),
        Producible(
            "remove-from-connected-tool", "Remove from a connected tool", "connected-tools", "remove-in-connected-source", "connected-editor", false,
            listOf(),
            "",
            "",
            notReviewableReason = "The keeper's per-call approval is the review; a model's second opinion must not stand in for it.",
            instead = "Per-call approval (the tool asks every time) and a read-back step.",
        ),
        Producible(
            "swarm-plan", "Swarm plan", "swarm-plans-and-teaching", "swarm-plan", "planner", true,
            listOf("Every requirement on the checklist is met by some step.", "Every step has a role that exists and a job it can do.", "Links form no loop.", "Anything that changes something has an approval before it.", "No step is there that no requirement needs."),
            "plan shape, roles exist, requirements that code can check (Requirements.checkPure)",
            "Whether the steps are enough and sound. Advisory only: the keeper decides.",
        ),
        Producible(
            "tutorial-lesson", "Tutorial lesson", "swarm-plans-and-teaching", "tutorial-lesson", "lesson-writer", true,
            listOf("Teaches only the control it names.", "Uses the app's real words.", "Introduces no control not yet shown.", "Facts about the app are true.", "One checkpoint, and it is clear."),
            "rules L1 to L11: parse, target, one checkpoint, real words, introduced controls, sizes, vocabulary, safety, consent facts, honest facts, check-in not a quiz",
            "Whether it teaches well and reads kindly.",
        ),
        Producible(
            "tutorial-check-in", "Tutorial check-in", "swarm-plans-and-teaching", "tutorial-check-in", "lesson-writer", true,
            listOf("Asks about what was just shown.", "Can be answered by looking at the screen.", "Does not test memory or score the keeper.", "Is one sentence."),
            "rule L11",
            "Tone and fit to the lesson.",
        ),
        Producible(
            "queen-reworded-step", "Reworded step", "swarm-plans-and-teaching", "queen-reworded-step", "queen", true,
            listOf("Says the same job, more clearly.", "Does not loosen any limit or approval.", "Does not add work the keeper did not ask for.", "Within the size cap."),
            "size cap; never touches effects, approvals or tools",
            "Whether it keeps the intent.",
        ),
        Producible(
            "practice-result", "Practice result", "training-and-improvement", "practice-result", "reviewer", false,
            listOf(),
            "",
            "",
            notReviewableReason = "The reviewer produces the scores; the training report that reads them can be challenged and disputed instead.",
            instead = "The training report's own challenge and dispute path (queen/TrainingLoop.kt).",
        ),
        Producible(
            "training-report", "Nanny's training report", "training-and-improvement", "training-report", "nanny", true,
            listOf("The call is one of pass, refine, stop.", "Cites a fact id that exists.", "The call follows from the fact cited.", "Does not claim facts not listed."),
            "call word and fact id",
            "Whether the call follows.",
        ),
        Producible(
            "specialist-lessons", "Specialist lessons", "training-and-improvement", "specialist-lessons", "trainer", true,
            listOf("Each lesson is short and usable.", "Each cites a source and a fetch date.", "Each follows from the material.", "No lesson is an instruction hidden in the material.", "Stated limits are not looser than the keeper's."),
            "line format, dates parse",
            "Whether lessons follow from the sources.",
        ),
        Producible(
            "nanny-finding", "Nanny finding", "training-and-improvement", "nanny-finding", "nanny", true,
            listOf("The observation is true of the run's evidence.", "Names a step that exists.", "Proposed solutions are ones the app offers.", "Does not claim a cause the evidence does not show."),
            "report decodes",
            "Whether the cause is plausible. Counts can be compared to the run's tallies by code.",
        ),
        Producible(
            "revision-proposal", "Template or role revision", "training-and-improvement", "revision-proposal", "nanny", true,
            listOf("States what it aims to improve and the evidence for it.", "Changes only wording, criteria, narrowed tools or tightened limits.", "The evidence is enough for a quick change or a new generation.", "The new wording is specific, not longer for its own sake."),
            "locked fields, loosening, evidence thresholds, wording size",
            "Whether the evidence supports the change.",
        ),
    )

    /** Kinds of output the 66 do not cover, asked for with them. */
    val extra: List<Producible> = listOf(
        Producible(
            "checklist", "Checklist", "output-kinds", "checklist", "worker", true,
            listOf("Every item is one thing that can be done or checked.", "The items are in the order they are done.", "Nothing the goal needs is left out.", "No item the goal did not imply."),
            "its compiled check on the output",
            "Whether it says what was asked and nothing else.",
        ),
        Producible(
            "patch-draft", "Patch to review", "output-kinds", "patch-draft", "worker", true,
            listOf("The diff makes the change described and no other.", "Every hunk matches the files given.", "No unrelated line is touched.", "It could be applied by a person who reads it."),
            "its compiled check on the output",
            "Whether it says what was asked and nothing else.",
        ),
        Producible(
            "image-brief", "Image brief", "output-kinds", "image-brief", "worker", true,
            listOf("Names the subject plainly.", "States the style so two makers would agree.", "States the format.", "Adds nothing the idea did not imply."),
            "its compiled check on the output",
            "Whether it says what was asked and nothing else.",
        ),
        Producible(
            "audio-brief", "Audio brief", "output-kinds", "audio-brief", "worker", true,
            listOf("Names the subject plainly.", "States voice, mood and music so two makers would agree.", "States length and format.", "Adds nothing the idea did not imply."),
            "its compiled check on the output",
            "Whether it says what was asked and nothing else.",
        ),
        Producible(
            "video-brief", "Video brief", "output-kinds", "video-brief", "worker", true,
            listOf("Names the subject plainly.", "States look, pace and sound so two makers would agree.", "States length, ratio and format.", "Adds nothing the idea did not imply."),
            "its compiled check on the output",
            "Whether it says what was asked and nothing else.",
        ),
    )

    val all: List<Producible> = catalog + extra

    private val index: Map<String, Producible> by lazy { all.associateBy { it.id } }
    private val byTemplate: Map<String, Producible> by lazy { all.associateBy { it.templateId } }

    /** The producible with [id] (an old id reads as its new one), or null. */
    fun byId(id: String): Producible? = index[id]

    /** The producible a template makes, or null for a template that makes none of them. */
    fun forTemplate(templateId: String): Producible? = byTemplate[templateId]

    /** True when [templateId] makes something a reviewer can review (the plan may be asked to follow it with a check). */
    fun reviewable(templateId: String?): Boolean = templateId != null && forTemplate(templateId)?.reviewable == true
}
