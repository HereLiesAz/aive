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

## A test that ends in `runBlocking` returns Unit

A JUnit 4 test method must return void. `fun test() = runBlocking { ... }` takes the type of its last expression, so a test ending in `assertIs`, `assertNotNull` or any other value-returning call fails with `InvalidTestClassError` and runs nothing. I made this mistake twice.

- Write `fun test(): Unit = runBlocking { ... }` for every test that uses `runBlocking`, as the existing tests do.
