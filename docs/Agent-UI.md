# Agent questions: declarative UI

An agent can ask the keeper for more than text: a choice, a confirmation, a short value, with a table to decide from. It sends a declarative request shaped like [A2UI](https://a2ui.org) v0.8 (a flat list of components that refer to each other by id), and the host draws it only from its own fixed catalog of Azphalt controls. The request is data: nothing in it is executed, fetched, or rendered as HTML, a link or an image.

Source: `shared/.../agentui/AgentUi.kt` (parsing, validation, answers, the prompt), `AgentQuestionPanel.kt` (drawing), `ProviderBackedManagedSessionGateway` and `WorkflowEngine.reconcile` (the wait).

## How an agent is told

Every task request to an agent the keeper can reply to mid-task (its provider declares `AgentCapability.Messaging`) carries a prompt-context block, **Asking the keeper**, beside its role (`AgentUi.promptInstruction`). It names the subset below, the catalog id `aive.keeper.v1` and the shape of the answer. Agents that cannot be replied to are not told, so they do not stop to ask.

## The request

One message whose text holds a fenced block tagged `a2ui` (or that is itself the JSON). Text outside the fence is shown above the form.

~~~text
I need a decision before deploying.
```a2ui
{"surfaceUpdate": {"surfaceId": "deploy", "components": [
  {"id": "root", "component": {"Column": {"children": {"explicitList": ["plan", "yes", "no"]}}}},
  {"id": "plan", "component": {"MultipleChoice": {"selections": {"literalArray": []}, "maxAllowedSelections": 1,
    "options": [{"label": {"literalString": "Small"}, "value": "small"}, {"label": {"literalString": "Large"}, "value": "large"}]}}},
  {"id": "yes", "component": {"Button": {"child": "yes-label", "primary": true, "action": {"name": "confirm"}}}},
  {"id": "yes-label", "component": {"Text": {"text": {"literalString": "Deploy"}}}},
  {"id": "no", "component": {"Button": {"child": "no-label", "action": {"name": "decline"}}}},
  {"id": "no-label", "component": {"Text": {"text": {"literalString": "Not now"}}}}]}}
{"beginRendering": {"surfaceId": "deploy", "root": "root", "catalogId": "aive.keeper.v1"}}
```
~~~

## The accepted subset

| A2UI | Drawn as | Accepted properties |
|---|---|---|
| `Text` | plain text | `text` (literal), `usageHint` h1–h5, caption, body |
| `Column`, `Row` | a stack, a scrolling row | `children.explicitList`, `alignment`, `distribution` |
| `MultipleChoice` | pills; a single choice when `maxAllowedSelections` is 1 | `selections`, `options` (label, value), `maxAllowedSelections`, `variant`, `filterable` |
| `TextField` | a short text or number field | `label` (literal), `text`, `textFieldType` `shortText` or `number` |
| `Button` | a pill: a confirm or other answer | `child` (a Text), `primary`, `action.name` |
| `Table` (host extension) | a read-only table | `columns.literalArray`, `rows[].literalArray` |

Messages: `surfaceUpdate` and `beginRendering` only (one surface). The root defaults to `root`. A question with no Button gets the host's own **Send answer** (action `submit`).

Rejected, and then the message is shown as plain text with a plain answer field:

- any other component (`Image`, `Video`, `AudioPlayer`, `Icon`, `Modal`, `Tabs`, `Card`, `List`, `CheckBox`, `Slider`, `DateTimeInput`, …) or property (`url`, `action.context`, `weight`, …), or another message (`dataModelUpdate`, `deleteSurface`);
- a shown string with no `literalString` (there is no data model), `textFieldType` `obscured`, `longText` or `date`, a `catalogId` other than `aive.keeper.v1`;
- more than 16,000 characters in the message, more than 64 components, JSON nested deeper than 12, containers nested deeper than 4, strings over 2,000 characters, more than 24 options, 8 table columns or 50 rows;
- a missing, repeated, unreachable or cyclic id reference, or a Button whose child is not a Text.

## The wait

The gateway keeps an agent's latest question for its session; the engine copies it to the task (`TaskRun.keeperQuestion`). A running task with a question waits on you, under the same definition as a plan to approve, an escalation and a nested gate (`creatureNeedsYou`): the Inbox badge counts it (a new question is a new unread item), the hive's Owner attention card shows it, and the creature opens on **Needs**, where the question is drawn. A task that completes, fails or is retried drops its question.

## The answer

The keeper's answer goes back through the session's ordinary messaging (`answerKeeperQuestion`), as an A2UI `userAction`:

~~~text
The keeper answered your question (A2UI userAction):
{"userAction":{"name":"confirm","surfaceId":"deploy","sourceComponentId":"yes","timestamp":"2026-11-02T10:00:00Z","context":{"plan":["small"]}}}
~~~

`context` holds every input by component id: a choice as a list of option values, a short text as a string, a number as a number (or `null` when left empty). A question shown as plain text is answered with `The keeper answered your question:` and the typed text. Once the session accepts the answer the question is marked answered (`TaskRun.answeredKeeperQuestion`), so a provider replaying its history after a restart does not ask it again. An answer to a question that has since changed is refused.
