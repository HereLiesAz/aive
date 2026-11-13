# Azphalt templates

Every Azphalt package, built-in swarm and role starts from a template, or is listed here with the reason it does not yet. This page is the audit, the rule and the gap list. The mechanism is in [Step templates](Step-Templates.md).

## The rule

- **A role has a role template.** The merged template catalog makes a role template (kind role, id, version 1, generation 1, no parent, locked identity, capabilities, authorities and way of running) for every role the device has installed: the built-in roles, the Nanny, the node-character roles and every role a package installs. The Nanny improves it over generations by the same mechanism as a step template. Nothing is stored per role at install; the record is the role as installed, and a revision is stored beside it.
- **A producing step starts from a template.** A step that makes something carries a contract naming its starting template (`write-document`, `check-work`, `text-to-table` or one of the presets below), the typed output that template hands back, and its inputs. The criteria a reviewer checks stay the step's own acceptance criteria.
- **A starting-point contract changes nothing about how the step runs.** The contract is marked `startingPoint`: the step keeps the run's whole toolbox, is not held for a missing input, and is not failed on its output. The template is a starting point the Nanny can read and improve, and a step made from a template in Edit is still enforced as before. Saved swarms and package files with no contract load as they did; a contract written before the flag existed is an enforced one.
- **A step that fits no starting template is a gap.** It is listed in `StepTemplateAssignments.gaps` with what it needs. There are none today: the 26 steps the audit listed are covered (see Gaps closed). A person's approval step makes nothing and needs neither.
- **A starting point declares no effect.** A step that began at an effectful template (`change-code`, the media templates) carries `effects: None` in its contract, so it is not held for approval and the run's rules for it are unchanged; the approval floor and the single attempt belong to a step made from the template in Edit.
- **A new package cannot skip this.** `StepTemplateCoverageTest` reads every package under `docs/azphalt-packages` and every built-in swarm and fails when a step has no template and no gap entry, when a gap entry has no step, or when a role has no role template. A package that changes its files needs its version raised and its manifest digests recomputed (`npm run build` checks them); signing is done at release and was not touched.

## Counts

| | Before | After |
|---|---|---|
| Role template records | every installed role (built-in, Nanny, node-character, package), by construction; the default catalog left out the Nanny | all of them; the default catalog adds the Nanny; tested for 128 role ids |
| Package steps with a starting template | 0 of 101 | 101 of 101 (the 11 gaps closed by `change-code`, the media, `localize-content` and `make-slides` templates) |
| Built-in swarm steps with a starting template | 0 of 68 (10 more are approval steps) | 68 of 68 (the 15 gaps closed by `change-code`, `render-image`, `render-video` and the fixed-format templates) |
| Swarms | 20 built-in (18 in the coding suite, the short film, the starter) and 66 workflow packages | the same |

Built-in roles: 18 in the built-in list and the Nanny, and 6 node-character roles installed when a swarm needs them. Packages: 78 in all, 66 workflow packages (66 swarms, 101 steps) and 12 role packages, which carry 111 role files for 104 role ids.

## Roles

Built-in role ids: `orchestrator`, `product-manager`, `researcher`, `architect`, `epa-representative`, `ux-designer`, `implementation-engineer`, `crash-test-dummy`, `qa-engineer`, `planner`, `reviewer`, `trainer`, `worker`, `adversarial-reviewer`, `code-reviewer`, `recovery-engineer`, `release-engineer`, `antagonist`, `hall-monitor`. Each has a role template (generation 1).

Node-character role ids: `node-character-pipeline-orchestrator`, `node-character-designer`, `node-character-rigging-anatomist`, `node-character-image-operator`, `node-character-quality-inspector`, `node-character-asset-registrar`. Each has a role template once installed; the swarm that needs them installs them first.

Package role ids (104 ids from 111 role files). Every one has a role template once its package is installed.

| Role id | Shipped by | Role template |
|---|---|---|
| `accessibility-auditor` | `accessibility-audit` | yes |
| `agent-red-teamer` | `agentic-ai-threat-model` | yes |
| `agent-security-architect` | `agent-security-architect`, `agentic-ai-threat-model` | yes |
| `ai-evaluation-scientist` | `ai-system-evaluation` | yes |
| `book-editor` | `long-form-book-production` | yes |
| `brand-strategist` | `brand-identity-system` | yes |
| `campaign-strategist` | `campaign-planning` | yes |
| `character-consistency-reviewer` | `character-sheet-generator` | yes |
| `character-design-briefer` | `character-sheet-generator` | yes |
| `competitive-analyst` | `competitive-intelligence` | yes |
| `concept-art-reviewer` | `sketch-to-concept-art` | yes |
| `concept-brief-analyst` | `sketch-to-concept-art` | yes |
| `content-strategist` | `content-strategy` | yes |
| `continuity-supervisor` | `continuity-supervisor` | yes |
| `contract-reviewer` | `contract-issue-spotting` | yes |
| `corporate-investigator` | `corporate-investigator` | yes |
| `crisis-communicator` | `crisis-communications` | yes |
| `curriculum-architect` | `curriculum-design` | yes |
| `data-investigator` | `data-analysis-investigation`, `data-investigator` | yes |
| `data-story-editor` | `data-storytelling` | yes |
| `decision-analyst` | `decision-analysis` | yes |
| `deep-research-lead` | `deep-research-report` | yes |
| `deep-researcher` | `deep-researcher` | yes |
| `documentary-producer` | `documentary-production` | yes |
| `event-producer` | `event-production` | yes |
| `evidence-skeptic` | `deep-research-report`, `evidence-skeptic` | yes |
| `evidence-synthesis-lead` | `evidence-synthesis` | yes |
| `execution-secretary` | `meeting-to-execution` | yes |
| `exhibition-producer` | `exhibition-production` | yes |
| `experimental-methodologist` | `experimental-design` | yes |
| `failure-diagnostician` | `failure-diagnostician`, `root-cause-repair` | yes |
| `feature-engineer` | `feature-delivery` | yes |
| `feature-reviewer` | `feature-delivery`, `feature-reviewer` | yes |
| `film-continuity-supervisor` | `ai-feature-film-production` | yes |
| `film-showrunner` | `ai-feature-film-production` | yes |
| `financial-model-auditor` | `financial-model-qa` | yes |
| `firefly-character-sheet-artist` | `character-sheet-generator` | yes |
| `firefly-concept-artist` | `sketch-to-concept-art` | yes |
| `firefly-storyboard-artist` | `idea-to-storyboard-to-video` | yes |
| `firefly-video-animator` | `idea-to-storyboard-to-video` | yes |
| `game-director` | `game-vertical-slice` | yes |
| `geolocation-analyst` | `geolocation-analyst` | yes |
| `grant-strategist` | `grant-application` | yes |
| `graphic-novel-continuity-editor` | `ai-graphic-novel-production` | yes |
| `graphic-novel-director` | `ai-graphic-novel-production` | yes |
| `identity-director` | `brand-identity-system` | yes |
| `incident-commander` | `incident-commander`, `production-incident-response` | yes |
| `incident-investigator` | `production-incident-response` | yes |
| `interaction-designer` | `ux-research-to-design` | yes |
| `investigative-reporter` | `investigative-journalism` | yes |
| `knowledge-system-evaluator` | `rag-knowledge-evaluation` | yes |
| `launch-strategist` | `product-launch` | yes |
| `learning-coach` | `adaptive-tutoring` | yes |
| `localization-lead` | `localization-release` | yes |
| `manuscript-continuity-editor` | `long-form-book-production` | yes |
| `methods-reviewer` | `evidence-synthesis` | yes |
| `negotiation-planner` | `negotiation-preparation` | yes |
| `operations-designer` | `process-redesign` | yes |
| `osint-attribution-skeptic` | `osint-passive-web-infrastructure` | yes |
| `osint-change-auditor` | `osint-geospatial-change` | yes |
| `osint-chronology-auditor` | `osint-event-chronology` | yes |
| `osint-corporate-investigator` | `osint-corporate-ownership` | yes |
| `osint-earth-observation-analyst` | `osint-geospatial-change` | yes |
| `osint-entity-resolution-auditor` | `osint-corporate-ownership` | yes |
| `osint-geolocation-analyst` | `osint-media-geolocation` | yes |
| `osint-infrastructure-analyst` | `osint-passive-web-infrastructure` | yes |
| `osint-location-skeptic` | `osint-media-geolocation` | yes |
| `osint-mobility-analyst` | `osint-maritime-aviation` | yes |
| `osint-provenance-analyst` | `osint-source-verification` | yes |
| `osint-risk-researcher` | `osint-sanctions-procurement` | yes |
| `osint-screening-auditor` | `osint-sanctions-procurement` | yes |
| `osint-timeline-analyst` | `osint-event-chronology` | yes |
| `osint-track-auditor` | `osint-maritime-aviation` | yes |
| `osint-verification-editor` | `osint-source-verification` | yes |
| `osint-verifier` | `osint-verifier` | yes |
| `paperplanes-geometry-engineer` | `paperplanes-stratified-reconstruction` | yes |
| `paperplanes-layer-engineer` | `paperplanes-stratified-reconstruction` | yes |
| `paperplanes-occlusion-architect` | `paperplanes-stratified-reconstruction` | yes |
| `paperplanes-scene-director` | `paperplanes-stratified-reconstruction` | yes |
| `paperplanes-visual-qc` | `paperplanes-stratified-reconstruction` | yes |
| `playtest-analyst` | `game-vertical-slice` | yes |
| `podcast-fact-editor` | `podcast-production` | yes |
| `podcast-producer` | `podcast-production` | yes |
| `presentation-editor` | `presentation-production` | yes |
| `pricing-researcher` | `pricing-packaging-research` | yes |
| `privacy-risk-analyst` | `privacy-impact-assessment` | yes |
| `process-engineer` | `sop-process-engineering` | yes |
| `production-final-reviewer` | `idea-to-storyboard-to-video` | yes |
| `proposal-lead` | `rfp-response` | yes |
| `qualitative-synthesist` | `interview-synthesis` | yes |
| `release-producer` | `music-release` | yes |
| `repair-verifier` | `root-cause-repair` | yes |
| `review-scientist` | `scientific-literature-review` | yes |
| `scenario-planner` | `scenario-planning` | yes |
| `search-strategist` | `search-visibility` | yes |
| `selection-designer` | `structured-hiring` | yes |
| `statistical-skeptic` | `data-analysis-investigation` | yes |
| `storyboard-scriptwriter` | `idea-to-storyboard-to-video` | yes |
| `supplier-analyst` | `supplier-due-diligence` | yes |
| `support-escalation-lead` | `customer-support-escalation` | yes |
| `survey-methodologist` | `survey-research` | yes |
| `technical-writer` | `technical-documentation` | yes |
| `ux-researcher` | `ux-research-to-design`, `ux-researcher` | yes |
| `vendor-evaluator` | `vendor-evaluation` | yes |

**Finding: seven role ids are shipped by two packages with different instructions** (`agent-security-architect`, `data-investigator`, `evidence-skeptic`, `failure-diagnostician`, `feature-reviewer`, `incident-commander`, `ux-researcher`). The role template is by id, so there is one record for each, and which wording it starts from depends on which package installed first. The two wordings should be reconciled by their author; this audit did not change role content. The test lists them, so a new collision shows up.

## Built-in swarms

Keys are `<swarm id>/<step id>`. The short film and the self-improvement loop are listed with the coding suite because they ship in the app. A person's approval step is listed as an approval and needs no template.

| Swarm | Step | Starting template |
|---|---|---|
| `suite-feature-delivery` | `requirements` | write-document |
| `suite-feature-delivery` | `design` | write-document |
| `suite-feature-delivery` | `challenge` | check-work |
| `suite-feature-delivery` | `implement` | change-code |
| `suite-feature-delivery` | `verify` | check-work |
| `suite-feature-delivery` | `code-review` | check-work |
| `suite-feature-delivery` | `release-approval` | approval step, needs none |
| `suite-bug-fix` | `reproduce` | write-document |
| `suite-bug-fix` | `diagnose` | write-document |
| `suite-bug-fix` | `fix` | change-code |
| `suite-bug-fix` | `regression-test` | change-code |
| `suite-bug-fix` | `verify` | check-work |
| `suite-bug-fix` | `code-review` | check-work |
| `suite-bug-fix` | `release-approval` | approval step, needs none |
| `suite-safe-refactor` | `characterize` | change-code |
| `suite-safe-refactor` | `plan` | write-document |
| `suite-safe-refactor` | `refactor` | change-code |
| `suite-safe-refactor` | `verify` | check-work |
| `suite-safe-refactor` | `code-review` | check-work |
| `suite-safe-refactor` | `release-approval` | approval step, needs none |
| `suite-test-coverage` | `survey` | write-document |
| `suite-test-coverage` | `write-tests` | change-code |
| `suite-test-coverage` | `verify` | check-work |
| `suite-test-coverage` | `code-review` | check-work |
| `suite-test-coverage` | `release-approval` | approval step, needs none |
| `suite-dependency-update` | `audit` | write-document |
| `suite-dependency-update` | `update` | change-code |
| `suite-dependency-update` | `verify` | check-work |
| `suite-dependency-update` | `code-review` | check-work |
| `suite-dependency-update` | `release-approval` | approval step, needs none |
| `suite-docs-sync` | `find-drift` | write-document |
| `suite-docs-sync` | `update-docs` | change-code |
| `suite-docs-sync` | `code-review` | check-work |
| `suite-docs-sync` | `release-approval` | approval step, needs none |
| `suite-release-prep` | `changelog` | write-document |
| `suite-release-prep` | `checks` | check-work |
| `suite-release-prep` | `code-review` | check-work |
| `suite-release-prep` | `release-approval` | approval step, needs none |
| `suite-incident-hotfix` | `diagnose` | write-document |
| `suite-incident-hotfix` | `hotfix` | change-code |
| `suite-incident-hotfix` | `verify` | check-work |
| `suite-incident-hotfix` | `rollback-plan` | write-document |
| `suite-incident-hotfix` | `release-approval` | approval step, needs none |
| `suite-code-review` | `correctness` | check-work |
| `suite-code-review` | `risk` | check-work |
| `suite-code-review` | `tests` | check-work |
| `suite-code-review` | `summary` | write-document |
| `suite-security-audit` | `threat-model` | write-document |
| `suite-security-audit` | `hunt` | check-work |
| `suite-security-audit` | `triage` | check-work |
| `suite-security-audit` | `report` | write-document |
| `suite-codebase-map` | `survey` | write-document |
| `suite-codebase-map` | `architecture` | write-document |
| `suite-codebase-map` | `guide` | write-document |
| `suite-nightly-dependency-audit` | `audit` | write-document |
| `suite-nightly-dependency-audit` | `report` | write-document |
| `suite-daily-ci-triage` | `collect` | write-document |
| `suite-daily-ci-triage` | `diagnose` | write-document |
| `suite-daily-ci-triage` | `report` | write-document |
| `suite-weekly-test-health` | `assess` | write-document |
| `suite-weekly-test-health` | `report` | write-document |
| `suite-weekly-security-scan` | `scan` | check-work |
| `suite-weekly-security-scan` | `report` | write-document |
| `suite-weekly-docs-drift` | `compare` | write-document |
| `suite-weekly-docs-drift` | `report` | write-document |
| `suite-weekly-tech-debt` | `survey` | write-document |
| `suite-weekly-tech-debt` | `report` | write-document |
| `short-film` | `concept` | write-document |
| `short-film` | `storyboard` | fixed-format-result |
| `short-film` | `style-sheet` | capped-text-result |
| `short-film` | `frames` | render-image |
| `short-film` | `frame-review` | fixed-format-result |
| `short-film` | `assemble` | render-video |
| `short-film` | `approve-film` | approval step, needs none |
| `starter` | `implementation` | change-code |
| `starter` | `verification` | check-work |
| `starter` | `review` | check-work |
| `starter` | `release-approval` | approval step, needs none |
| `suite-self-improvement` | all six steps | exempt: system-owned |

Not listed per step, with the reason:

- **The self-improvement loop** (`suite-self-improvement`). Its steps hand back the fixed proposal line grammar the improvement parser reads, and its integrity check compares the loop's structure. It is exempt in the test. `fixed-format-result` now exists, but the loop's output is a line grammar a JSON-object check does not read, so it stays exempt.
- **Swarms the queen builds at run time** (training, the dispute test, node-character generation, and the swarm she plans from a hive). Their steps are generated by code with fixed result formats (`LESSON:`, `RULING:`, `GENERIC:` lines, assets). A swarm she plans can already use the starting templates by id; those steps are enforced. The generated ones could start from `fixed-format-result` and the media templates; none is assigned yet.
- **The tutorial.** It keeps using the generic built-in roles and has no prewritten lessons; the queen and her creatures generate them each run. The Adaptive Tutoring package is listed below.

## Azphalt packages

All 66 workflow packages. A role package carries no steps; its roles are in the table above and its files were not changed.

| Package | Step | Role | Starting template |
|---|---|---|---|
| `accessibility-audit` | 1. Accessibility Auditor | `accessibility-auditor` | check-work |
| `adaptive-tutoring` | 1. Survey the subject | `learning-coach` | write-document |
| `adaptive-tutoring` | 2. Draft the curriculum | `learning-coach` | write-document |
| `adaptive-tutoring` | 3. Teach the lessons | `learning-coach` | write-document |
| `agentic-ai-threat-model` | 1. Agent Security Architect | `agent-security-architect` | write-document |
| `agentic-ai-threat-model` | 2. Agent Red Teamer | `agent-red-teamer` | check-work |
| `ai-feature-film-production` | 1. Film Showrunner | `film-showrunner` | write-document |
| `ai-feature-film-production` | 2. Film Continuity Supervisor | `film-continuity-supervisor` | check-work |
| `ai-graphic-novel-production` | 1. Graphic Novel Director | `graphic-novel-director` | write-document |
| `ai-graphic-novel-production` | 2. Graphic Novel Continuity Editor | `graphic-novel-continuity-editor` | check-work |
| `ai-system-evaluation` | 1. AI Evaluation Scientist | `ai-evaluation-scientist` | write-document |
| `brand-identity-system` | 1. Brand Strategist | `brand-strategist` | write-document |
| `brand-identity-system` | 2. Identity Director | `identity-director` | write-document |
| `campaign-planning` | 1. Campaign Strategist | `campaign-strategist` | write-document |
| `character-sheet-generator` | 1. Character Design Briefer | `character-design-briefer` | write-document |
| `character-sheet-generator` | 2. Firefly Character Sheet Render | `firefly-character-sheet-artist` | render-image |
| `character-sheet-generator` | 3. Character Consistency Reviewer | `character-consistency-reviewer` | check-work |
| `competitive-intelligence` | 1. Competitive Analyst | `competitive-analyst` | write-document |
| `content-strategy` | 1. Content Strategist | `content-strategist` | write-document |
| `contract-issue-spotting` | 1. Contract Reviewer | `contract-reviewer` | write-document |
| `crisis-communications` | 1. Crisis Communicator | `crisis-communicator` | write-document |
| `curriculum-design` | 1. Curriculum Architect | `curriculum-architect` | write-document |
| `customer-support-escalation` | 1. Support Escalation Lead | `support-escalation-lead` | write-document |
| `data-analysis-investigation` | 1. Data Investigator | `data-investigator` | write-document |
| `data-analysis-investigation` | 2. Statistical Skeptic | `statistical-skeptic` | check-work |
| `data-storytelling` | 1. Data Story Editor | `data-story-editor` | write-document |
| `decision-analysis` | 1. Decision Analyst | `decision-analyst` | write-document |
| `deep-research-report` | 1. Deep Research Lead | `deep-research-lead` | write-document |
| `deep-research-report` | 2. Evidence Skeptic | `evidence-skeptic` | check-work |
| `documentary-production` | 1. Documentary Producer | `documentary-producer` | write-document |
| `event-production` | 1. Event Producer | `event-producer` | write-document |
| `evidence-synthesis` | 1. Evidence Synthesis Lead | `evidence-synthesis-lead` | write-document |
| `evidence-synthesis` | 2. Methods Reviewer | `methods-reviewer` | check-work |
| `exhibition-production` | 1. Exhibition Producer | `exhibition-producer` | write-document |
| `experimental-design` | 1. Experimental Methodologist | `experimental-methodologist` | write-document |
| `feature-delivery` | 1. Feature Engineer | `feature-engineer` | change-code |
| `feature-delivery` | 2. Feature Reviewer | `feature-reviewer` | check-work |
| `financial-model-qa` | 1. Financial Model Auditor | `financial-model-auditor` | check-work |
| `game-vertical-slice` | 1. Game Director | `game-director` | write-document |
| `game-vertical-slice` | 2. Playtest Analyst | `playtest-analyst` | check-work |
| `grant-application` | 1. Grant Strategist | `grant-strategist` | write-document |
| `idea-to-storyboard-to-video` | 1. Storyboard Scriptwriter | `storyboard-scriptwriter` | write-document |
| `idea-to-storyboard-to-video` | 2. Firefly Storyboard Frames | `firefly-storyboard-artist` | render-image |
| `idea-to-storyboard-to-video` | 3. Firefly Video Animation | `firefly-video-animator` | render-video |
| `idea-to-storyboard-to-video` | 4. Production Final Reviewer | `production-final-reviewer` | check-work |
| `interview-synthesis` | 1. Qualitative Synthesist | `qualitative-synthesist` | write-document |
| `investigative-journalism` | 1. Investigative Reporter | `investigative-reporter` | write-document |
| `localization-release` | 1. Localization Lead | `localization-lead` | localize-content |
| `long-form-book-production` | 1. Book Editor | `book-editor` | write-document |
| `long-form-book-production` | 2. Manuscript Continuity Editor | `manuscript-continuity-editor` | check-work |
| `meeting-to-execution` | 1. Execution Secretary | `execution-secretary` | text-to-table |
| `music-release` | 1. Release Producer | `release-producer` | write-document |
| `negotiation-preparation` | 1. Negotiation Planner | `negotiation-planner` | write-document |
| `osint-corporate-ownership` | 1. OSINT Corporate Investigator | `osint-corporate-investigator` | write-document |
| `osint-corporate-ownership` | 2. OSINT Entity Resolution Auditor | `osint-entity-resolution-auditor` | check-work |
| `osint-event-chronology` | 1. OSINT Timeline Analyst | `osint-timeline-analyst` | write-document |
| `osint-event-chronology` | 2. OSINT Chronology Auditor | `osint-chronology-auditor` | check-work |
| `osint-geospatial-change` | 1. OSINT Earth Observation Analyst | `osint-earth-observation-analyst` | write-document |
| `osint-geospatial-change` | 2. OSINT Change Auditor | `osint-change-auditor` | check-work |
| `osint-maritime-aviation` | 1. OSINT Mobility Analyst | `osint-mobility-analyst` | write-document |
| `osint-maritime-aviation` | 2. OSINT Track Auditor | `osint-track-auditor` | check-work |
| `osint-media-geolocation` | 1. OSINT Geolocation Analyst | `osint-geolocation-analyst` | write-document |
| `osint-media-geolocation` | 2. OSINT Location Skeptic | `osint-location-skeptic` | check-work |
| `osint-passive-web-infrastructure` | 1. OSINT Infrastructure Analyst | `osint-infrastructure-analyst` | write-document |
| `osint-passive-web-infrastructure` | 2. OSINT Attribution Skeptic | `osint-attribution-skeptic` | check-work |
| `osint-sanctions-procurement` | 1. OSINT Risk Researcher | `osint-risk-researcher` | write-document |
| `osint-sanctions-procurement` | 2. OSINT Screening Auditor | `osint-screening-auditor` | check-work |
| `osint-source-verification` | 1. OSINT Provenance Analyst | `osint-provenance-analyst` | write-document |
| `osint-source-verification` | 2. OSINT Verification Editor | `osint-verification-editor` | check-work |
| `paperplanes-stratified-reconstruction` | 1. PaperPlanes Scene Director | `paperplanes-scene-director` | write-document |
| `paperplanes-stratified-reconstruction` | 2. PaperPlanes Geometry Engineer | `paperplanes-geometry-engineer` | compose-media |
| `paperplanes-stratified-reconstruction` | 3. PaperPlanes Occlusion Architect | `paperplanes-occlusion-architect` | compose-media |
| `paperplanes-stratified-reconstruction` | 4. PaperPlanes Layer Engineer | `paperplanes-layer-engineer` | compose-media |
| `paperplanes-stratified-reconstruction` | 5. PaperPlanes Visual QC | `paperplanes-visual-qc` | check-work |
| `podcast-production` | 1. Podcast Producer | `podcast-producer` | edit-audio |
| `podcast-production` | 2. Podcast Fact Editor | `podcast-fact-editor` | check-work |
| `presentation-production` | 1. Presentation Editor | `presentation-editor` | make-slides |
| `pricing-packaging-research` | 1. Pricing Researcher | `pricing-researcher` | write-document |
| `privacy-impact-assessment` | 1. Privacy Risk Analyst | `privacy-risk-analyst` | write-document |
| `process-redesign` | 1. Operations Designer | `operations-designer` | write-document |
| `product-launch` | 1. Launch Strategist | `launch-strategist` | write-document |
| `production-incident-response` | 1. Incident Commander | `incident-commander` | write-document |
| `production-incident-response` | 2. Incident Investigator | `incident-investigator` | write-document |
| `rag-knowledge-evaluation` | 1. Knowledge System Evaluator | `knowledge-system-evaluator` | check-work |
| `rfp-response` | 1. Proposal Lead | `proposal-lead` | write-document |
| `root-cause-repair` | 1. Failure Diagnostician | `failure-diagnostician` | write-document |
| `root-cause-repair` | 2. Repair Verifier | `repair-verifier` | check-work |
| `scenario-planning` | 1. Scenario Planner | `scenario-planner` | write-document |
| `scientific-literature-review` | 1. Review Scientist | `review-scientist` | write-document |
| `search-visibility` | 1. Search Strategist | `search-strategist` | write-document |
| `sketch-to-concept-art` | 1. Concept Brief Analyst | `concept-brief-analyst` | write-document |
| `sketch-to-concept-art` | 2. Firefly Concept Render | `firefly-concept-artist` | render-image |
| `sketch-to-concept-art` | 3. Concept Art Reviewer | `concept-art-reviewer` | check-work |
| `sop-process-engineering` | 1. Process Engineer | `process-engineer` | write-document |
| `structured-hiring` | 1. Selection Designer | `selection-designer` | write-document |
| `supplier-due-diligence` | 1. Supplier Analyst | `supplier-analyst` | write-document |
| `survey-research` | 1. Survey Methodologist | `survey-methodologist` | write-document |
| `technical-documentation` | 1. Technical Writer | `technical-writer` | write-document |
| `ux-research-to-design` | 1. UX Researcher | `ux-researcher` | write-document |
| `ux-research-to-design` | 2. Interaction Designer | `interaction-designer` | write-document |
| `vendor-evaluation` | 1. Vendor Evaluator | `vendor-evaluator` | write-document |

## Gaps closed

The audit listed 26 steps that fit none of the six starting templates. Each now has a preset (`template/StepPresets.kt`), made from a built-in template by changing data only: generic role, typed inputs, one tool or none, typed output with a compiled check, declared effects, a token allowance (carried, not yet enforced) and a single attempt where it changes something. Each step carries a `startingPoint` contract naming its preset. `StepTemplateAssignments.gaps` is empty and `StepTemplateCoverageTest` (`theTwentySixFormerGapsAreCoveredAndGuarded`) fails if any of the 26 loses its contract.

| Template | Made from | Effect and approval | Hands back | Steps |
|---|---|---|---|---|
| `change-code` | prepare files for a repository | writes a repository branch; asks first, once | `BRANCH:` and `FILES:` | 11: `suite-feature-delivery/implement`, `suite-bug-fix/fix`, `suite-bug-fix/regression-test`, `suite-safe-refactor/characterize`, `suite-safe-refactor/refactor`, `suite-test-coverage/write-tests`, `suite-dependency-update/update`, `suite-docs-sync/update-docs`, `suite-incident-hotfix/hotfix`, `starter/implementation`, `feature-delivery/feature-delivery-task-1` |
| `render-image` | change something in a connected tool | changes a connected tool (an image tool); asks first, once | `TARGET:` and `CHANGED:` | 4: `short-film/frames`, `character-sheet-generator/character-sheet-generator-task-2`, `idea-to-storyboard-to-video/idea-to-storyboard-to-video-task-2`, `sketch-to-concept-art/sketch-to-concept-art-task-2` |
| `render-video` | change something in a connected tool | as above, a video tool | `TARGET:` and `CHANGED:` | 2: `short-film/assemble`, `idea-to-storyboard-to-video/idea-to-storyboard-to-video-task-3` |
| `compose-media` | change something in a connected tool | as above; works on a copy | `TARGET:` and `CHANGED:` | 3: `paperplanes-stratified-reconstruction/paperplanes-stratified-reconstruction-task-2`, `-task-3`, `-task-4` |
| `edit-audio` | change something in a connected tool | as above; works on a copy, never the original | `TARGET:` and `CHANGED:` | 1: `podcast-production/podcast-production-task-1` |
| `make-slides` | change something in a connected tool | as above, a slides tool | `TARGET:` and `CHANGED:` | 1: `presentation-production/presentation-production-task-1` |
| `localize-content` | turn text into a table | changes nothing | a table `key,source,translation`, every column named | 1: `localization-release/localization-release-task-1` |
| `fixed-format-result` | write a document | changes nothing | one JSON object | 2: `short-film/storyboard`, `short-film/frame-review` |
| `capped-text-result` | write a document | changes nothing | one block of plain text, at most 20,000 characters | 1: `short-film/style-sheet` |

Total: 26 steps, 11 in packages and 15 in built-in swarms. The check on a result is the existing one for its shape: `localize-content` cannot see that a string was missed (it checks the table, not the strings), `fixed-format-result` checks that the output is one JSON object and not the fields the brief names, and `capped-text-result` holds the output to the general text cap, not to the film's own style-sheet length. Several of these steps are run by an external service (the frames, the assemble step, the Firefly steps) and not by a creature, so the contract there is a starting point for the Nanny to read and nothing more.

Two weaker fits, assigned and not listed as gaps:

- Steps that read the linked repository (a survey of the code, an audit of dependencies, a collection of CI runs) start from `write-document`. A `read-repository` template that names its tools would fit them better; today the read-from-a-connected-tool template needs one named tool and would narrow what they may use.
- Verification steps that run tests start from `check-work`, whose wording says to judge only. A `run-and-report-checks` template would fit them better.

## Packages left unchanged

- The 12 role packages: they hold roles only, which have role templates without a change to their files.
- Signing was not touched. The packages in this repository are packed unsigned and signed at release. The 64 packages that changed have their version raised by one patch and their manifest digests recomputed, which `npm run build` re-checks; the 8 packages that carry the former gaps (11 steps) were raised by one more patch with their digests recomputed.

## How to add one

A new package or built-in swarm: give each producing step a `contract` with `startingPoint` true for the template it starts from (see any package under `docs/azphalt-packages`), or, if no template fits, add its `<swarm id>/<step id>` to `StepTemplateAssignments.gaps` with a new `StepGap` entry saying what it needs. Run `StepTemplateCoverageTest`.
