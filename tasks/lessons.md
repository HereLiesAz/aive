# Lessons

## Defaults are not rules

When the keeper describes a design as "should", it is a default with room for exceptions. I turned "a role's tasks are done by the creature that already has that role" into a hard rule and proposed an engine lock to enforce it, which would have serialised work the workflow means to run in parallel (Mixture-of-Agents proposers, a deliberate second review).

- Before adding an enforcement mechanism (a lock, a validation, a limit), ask what the legitimate exception looks like and whether the rule can be a projection or a default instead.
- Prefer the structure the workflow already has (here: dependency order) over a new constraint.

## Reply on the thread, then resolve

I fixed review findings, pushed, and resolved the threads without writing on them. One summary comment on the PR covered some, and the rest got nothing. The reviewer (and the keeper) could not see, from the thread, that it was read, what changed, or in which commit.

- For every review thread I act on: reply on that thread first (the verdict, the commit, how it was checked, and what was not checked), then resolve it. Resolving alone is not an answer.
- A summary comment on the PR does not replace the replies.
- Do this in the same pass as the fix, not as a later cleanup.

## Keep moving

When a piece of work is done, start the next one without asking. A decision I would have asked about gets a sensible default, written into the design note as an open decision, and is fixed later if the keeper disagrees. Stopping to ask whether to continue costs more than a default that turns out wrong.

- Waiting on CI or a reviewer is not the end of a turn. Set the check-in, then start the next item while it runs. I ended turns to wait and was told to keep going; do not end a turn while an unbuilt item remains.

## A test that ends in `runBlocking` returns Unit

A JUnit 4 test method must return void. `fun test() = runBlocking { ... }` takes the type of its last expression, so a test ending in `assertIs`, `assertNotNull` or any other value-returning call fails with `InvalidTestClassError` and runs nothing. I made this mistake twice.

- Write `fun test(): Unit = runBlocking { ... }` for every test that uses `runBlocking`, as the existing tests do.

## A test base can satisfy the rule it is meant to test

My test for a proposed repository step passed on the first run because its base workflow already held a human-approval task, which counts as an approval ancestor. The test showed nothing. Before trusting a test of a guard, make the base fail the guard first: change the base until the unguarded case is refused, then add the fix.

## "Waits on data" is not a reason not to build

I listed five items as blocked ("waits on a model using it", "waits on hundreds of ladder runs") and stopped, calling it responsible. The keeper had said to build it, and that if it is wrong we will fix it when we find out. The point of building is to be able to find out.

- Never decline a requested item because the evidence for it does not exist yet. Build a default version.
- Build it so it can be observed: record what it did as a receipt or a count, put the switch and the numbers where the keeper can see them, and write down in the design note what result would show it is wrong.
- Keep it off by default where it records anything new, and keep it informational where it could mislead. Do not leave it unbuilt.

## Compile every target before pushing common code

I wrote `toSortedMap()` in commonMain, twice, in two PRs. It compiles on the JVM and does not exist in Kotlin common, so the web build (`:shared:compileKotlinJs`) failed, and the first one was already merged into main. Desktop tests passing says nothing about JS or Wasm. I had even written "JS and Wasm compilation not checked locally" in a PR body and left it at that.

- Before pushing any change under `commonMain`, run `./gradlew :shared:compileKotlinJs :shared:compileKotlinWasmJs` (without `--offline`: the web dependencies are not cached; the network allows them).
- JVM-only calls to avoid in common code: `toSortedMap`, `toSortedSet`, `String.format`, anything under `java.`. Sort entries and rebuild an ordered map with `associate`.
- A "not checked locally" line in a PR body is a task still to do, not a disclosure that finishes it.


## One callback must not carry two meanings

The creature sheet came back on any tap after it was closed. The field's "tap bare ground" called `onTaskSelected(selectedId)`, which the shell uses to toggle selection, but the shell's `selectCreature` also reads "the id whose panel was set aside" as "open it again". Two intents, one callback: ground taps reopened the panel. It had a doc sentence promising the opposite ("tapping bare ground lets it go") and no test.

- When a callback is reused for a new gesture, ask what else the receiver does with the same arguments.
- Give a gesture its own callback (here `LocalReleaseCreature`), and write the promise in the docs as a test or a screenshot step, not only a sentence.
- When a keeper reports a bug in a flow that a doc sentence describes, the sentence was not verified: check the sentence, not only the code.

## A saved edit is not a live edit

A creature's job saved from its panel was written to the library and then written over about once a second by the runtime's own copy of the definition, while the panel said it "takes effect from its next run". Where state is held twice (a library, a running copy), say which one a save changes, and route it through the owner.

## One task's fault is that task's failure, not the batch's

`dispatchReadyTasks` created sessions for every ready task in one batch, and a provider's refusal to start one (HTTP 402) was thrown out of the whole batch and out of `cycle()`, which the runtime retried every few seconds without end; a role missing from the collection did the same through `requireNotNull`. A fault that belongs to one task must fail that task through its own failure policy and leave the others running.

- Catch the expected failure type (`ManagedSessionFailure`) per task and route it through `failTaskBeforeStart`; keep rethrowing programming errors and cancellation so the rollback still cancels the sessions that did start.
- A status that is only a stop on the way to a decision (Failed, before the failure policy runs) must not be read as settled by readiness: failure handlers waited for nothing, then ran while the task was being retried. Pass such tasks as `undecidedFailures`.
- Count retries by cause, not by `attempt`: a hold that restarts work (`heldAttempts`, `failedProviders`) bumps `attempt` but is not a retry of failed work.
