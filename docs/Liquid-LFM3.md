# Liquid AI LFM3 in The Aive

What is known about running Liquid AI's LFM3 models here, what is built, and what is not. Checked 2026-11-22 against the
model cards and Liquid's API docs; nothing was run on a device.

## The models

| Model | What | On-device file (LiteRT-LM) | Size | Context |
| --- | --- | --- | --- | --- |
| `LiquidAI/LFM3-1.7B` | text, 16 languages | `litert-community/LFM3-1.7B-LiteRT`, `LFM3-1.7B_mixed_int4_ekv2048.litertlm` | 1,441,620,928 B | 2,048 (a 4,096 file exists) |
| `LiquidAI/LFM3-VL-2B` | text + one image | `litert-community/LFM3-VL-2B-LiteRT`, `LFM3-VL-2B_int4.litertlm` | 2,071,658,496 B | 2,048 |

Both need LiteRT-LM 0.19 or newer (the app is on 0.19.1) and are licensed under the LFM Open License v1.0, which does not allow
commercial use by an organisation with annual revenue of 10 million US dollars or more. Both think in a `<think>` block by
default. Liquid says they are not meant for agentic coding or complex multi-step reasoning. Known runtime problems (from the
cards): one `Engine` per conversation (LiteRT-LM issue 3165: a short-convolution state carries over), a failed oversize message
poisons the conversation, and the mixed int4 text file sometimes writes a stray `</think>` even with thinking off.

## The chat format (built: `providers/lfm`)

ChatML-shaped, with the model's own tags:

~~~
<|im_start|>system
Be brief.

<tools>
{"name": "get_weather", "description": "...", "parameters": {...}}
</tools><|im_end|>
<|im_start|>user
Weather in Paris?<|im_end|>
<|im_start|>assistant
<think>
~~~

The model answers a tool request with XML (Liquid: "a superset of qwen3_coder"):

~~~
<tool_call>
<function=get_weather>
<parameter=city>
Paris
</parameter>
</function>
</tool_call>
~~~

and the result goes back as a `tool` turn holding `<tool_response>\n...\n</tool_response>`.

`LfmChatPrompt` writes this exactly as the model's `chat_template.jinja` does (`LfmChatPromptTest` pins it against output of
that template run through Jinja, nine cases). `LfmToolCallParser` reads the model's output: thought, text and calls, tolerant of
cut-off calls and a stray `</think>`, with `argumentsJson` typing the all-text values by the tool's JSON Schema.

## The Shop packages (built: `docs/azphalt-packages/lfm3-1-7b`, `lfm3-vl-2b`)

Two `kind: "asset"` packages, each one remote `.litertlm` pinned by Hugging Face commit, exact size and SHA-256 (the Hub's
LFS ids), with the licence file shipped and its terms in `modelLicense`. `tools/azp/build.mjs` packs and verifies them like
the workflow packages (unsigned; signing is central). The installer (Android) accepts a `model` asset with
`requirements.runtime: "litert-lm"` or a `*.litertlm` file, checks the `LITERTLM` header and registers the backend
`litertlm-android`; the install pill waits for the keeper to accept a licence that is not freely commercial.

Not done here: publishing the packages to `azphalt.store` (the Shop list is remote, outside this repository, and signing is
central), and installing on a device. The installer change is Android-only and could not be compiled here (no Android SDK);
CI compiles it.

## A creature's mind (built)

A store-installed LFM3 model can be linked as a provider and chosen as a creature's mind. Nothing about it is special-cased in
the run loop: it is a `ToolCallingTextGenerationApi` (`LfmLocalChatApi`), so `TextLlmProvider` and the shared `McpToolLoop` drive it
like any hosted model.

- **Install (Android):** after the package is verified and registered, `InstalledLocalLlms` records the model (`providerId`
  `local:<packageId>`, the `.litertlm` path, and its `LfmModelProfile` read from the package's `params` and `requirements`:
  `contextTokens`, `thinking`, `minRamMB`, `imageInput`). Only a package declaring `chatFormat: "lfm3"` and `toolFormat: "lfm3-xml"`
  qualifies. Removing the package removes the provider.
- **A round:** the prompt is written with `LfmChatPrompt` (the tools as a `<tools>` block, earlier calls and their `<tool_response>`
  results replayed from the rounds), run through `LiteRtLmTextGenerator.generateRaw` (the prompt as is, stopped at `<|im_end|>`,
  refused rather than truncated when it cannot fit the profile's context), and read back with `LfmToolCallParser`; arguments are typed
  from the tool's JSON Schema. A failed tool goes back as `Error: ...`; the loop's last word offers no tools.
- **Cost:** none per token (`billsPerToken` false); token counts are estimates, since the engine reports none.
- **Memory:** one loaded engine per model file, shared by every conversation and released after two idle minutes; the generator
  refuses to load when the free memory is under the profile's `minRamMB`.
- **Where it shows:** `ProviderCatalog` lists it as "<name> (on this device)", keyless, never chosen automatically (the keeper links
  it on purpose).

Still not done: image input for `lfm3-vl-2b` (the profile records `imageInput`; the text path ignores images), running on desktop
(no LiteRT-LM runtime is lent there, so the provider is listed only where the Android installer registered it), and a run on a device.
The Android pieces (installer registration, `LocalLiteRtLmHost` in `AndroidMemoryLayerRuntime`) are compiled by CI only.

## Liquid's hosted API (built: the "Liquid AI" source)

`https://api.liquid.ai/openai/v1`, OpenAI chat-completions compatible, bearer key from `console.liquid.ai/dashboard/api-keys`.
Models: `lfm3-1.7b`, `lfm3-vl-2b`, `lfm3-omni-2b`, `lfm3-audio-2b`, `lfm2.5-24b-a2b`; `d1.2` on a separate decisions endpoint.
Per million tokens: `lfm3-1.7b` 0.08 in / 0.20 out; `lfm3-vl-2b` 0.10 in / 0.50 out. Standard endpoints may keep and train on
prompts. No keyless use. Whether the server turns the XML into `tool_calls` is unverified.

Built as a keyed `HostedLlmProviderSpec` (`liquid`, default `lfm3-1.7b`) over the shared OpenAI-compatible chat API, so tool calls
go through the server's `tool_calls` if it produces them. It cannot read the XML itself, so if the server returns the model's raw
`<tool_call>` text instead, that text reaches the creature unread (to be checked against a live key). Linked with a key from the
provider screen, or `LIQUID_API_KEY` on desktop and headless. Its price is not on the price list (no price was read from Liquid's own
page this session), so a dollar cap stops a run that spends on it, as for every unpriced hosted model.

