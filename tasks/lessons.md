# Lessons

## Defaults are not rules

When the keeper describes a design as "should", it is a default with room for exceptions. I turned "a role's tasks are done by the creature that already has that role" into a hard rule and proposed an engine lock to enforce it, which would have serialised work the workflow means to run in parallel (Mixture-of-Agents proposers, a deliberate second review).

- Before adding an enforcement mechanism (a lock, a validation, a limit), ask what the legitimate exception looks like and whether the rule can be a projection or a default instead.
- Prefer the structure the workflow already has (here: dependency order) over a new constraint.
