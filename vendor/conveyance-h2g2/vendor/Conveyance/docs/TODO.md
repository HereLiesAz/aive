# Conveyance TODO

This is the finite cleanup and strengthening pass that remains after the current philosophy/core-semantics work. The manifesto remains canon; this list exists to keep implementation and documentation aligned with it.

## Current principle

> An element is defined by what it does.
> An Act is ranked by what it does for the interaction.
> Neither is defined by what somebody hoped it would be.

May our elements be worthy people indeed.

## Framework completion pass

### 1. Heroic hierarchy cleanup

- [x] Replace the old `HeroicSaturation` concept with `HeroOfTheHill`.
- [x] Keep Act emphasis declarative and functional: `Heroic → Primary → Secondary → Tertiary → Supporting`.
- [x] Resolve presentation from the visible Act set without mutating the Act, Employment, or UI state.
- [x] When two or more visible Acts declare `Heroic`, demote the presented Act hierarchy one rung.
- [x] Keep the boundary explicit: this hierarchy belongs to Acts and their function in the interaction, not Employment, element rank, or screen state.

### 2. Remove stale Heroic/no-quota documentation

- [x] Update `RULES-AND-OPTOUTS.md`.
- [x] Update `CONVEYANCE-FRAMEWORK.md`.
- [x] Update `GETTING-STARTED.md`.
- [x] Update the core README.
- [x] Update the Compose README.
- [x] Use the document-outline model consistently: Heroic as the singular title, then Primary/H1, Secondary/H2, Tertiary/H3, Supporting as the floor.

### 3. Test the actual demotion ladder

- [x] One Heroic leaves the declared hierarchy unchanged.
- [x] `Heroic → Primary` under competing Heroics.
- [x] `Primary → Secondary`.
- [x] `Secondary → Tertiary`.
- [x] `Tertiary → Supporting`.
- [x] `Supporting → Supporting`.
- [x] Prove that resolution does not mutate the declared Act emphasis.

### 4. Make resolved Act emphasis usable in Compose

- [x] Expose screen-relative resolved emphasis through the Compose runtime.
- [x] Verify there is no framework styling path that reads raw `act.emphasis` directly instead of going through resolution when screen-relative presentation is required.
- [ ] As shipped expressive composables/themes begin mapping emphasis to presentation, make that mapping consume resolved emphasis by construction rather than by convention.

### 5. Deepen dynamic linter object mapping

- [x] Insert a semantic layer between raw jobs and SDK component names.
- [x] Derive behavioral roles such as `ActionSource`, `ProgressReporter`, `CompletionReporter`, `Interruptible`, `StatusReporter`, `IdentityCarrier`, `GateResolver`, `Destination`, `Locator`, `Navigator`, and `GroupContainer`.
- [x] Derive `Destination` from actual Act consequence targets rather than naming heuristics.
- [x] Derive `Job.Receive` when an Element is genuinely the target of an Act.
- [x] Prevent `Offer` recommendations from collapsing two distinct offered Acts into one lifecycle.
- [x] Preserve provable Act lifecycle membership in `AuditElement`.
- [x] Infer lifecycle membership automatically for named fragments composed inside an `Offer`/`ActScope`; do not add a redundant `forAct = ...` parameter.
- [x] Require proven shared lifecycle evidence before recommending fragmented progress/completion/interrupt fragments as one `Offer`.
- [ ] Learn more provable Act-to-fragment relationships for detached reporters when the runtime can genuinely observe them.
- [ ] Add stronger relational recipes beyond simple role-set matching where the graph can prove identity, target, gate, containment, or lifecycle relationships.

### 6. Build out the SDK capability catalog

`Offer`, `Form`, `Collection`, and `Places` are only the beginning. Each Conveyance construction should describe the behaviors and relations it genuinely subsumes so recommendations come from the SDK itself rather than linter folklore.

- [ ] Expand the recipe/catalog coverage to the rest of the shipped Conveyance composables.
- [ ] Keep recipes semantic: observed capabilities → behavioral pattern → available SDK construction.
- [ ] Encode relational requirements where a construction depends on one identity/lifecycle rather than merely a union of jobs.
- [ ] Keep custom/legacy raw-job recipes possible without letting them weaken the standard semantic recipes.

### 7. Final documentation audit against the manifesto

Do one final audit rather than another wholesale rewrite. Search for prescriptive language such as `exactly`, `only`, `never`, `must`, quotas, fixed channel meanings, and similar absolute claims. Classify each occurrence as one of:

- [ ] genuinely generative rule;
- [ ] model invariant;
- [ ] reference-binding choice;
- [ ] accidental AI authoritarianism.

Delete the last category. Preserve strong constraints that provoke invention, and pair legitimate exceptions with named semantic exceptions rather than generic suppression.

## After this pass

When the items above are complete, treat the framework philosophy/core-semantics pass as complete and return to richer implementation work:

- [ ] richer Conscience inference;
- [ ] stronger relational findings;
- [ ] more precise composable replacement recommendations;
- [ ] runtime evidence that lets the linter learn more truth without guessing.
