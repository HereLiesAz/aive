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

## Not built yet

- A `.litertlm` cannot be installed from the Shop today as `litert` or `task` (the installer checks for a TFLite header);
  `model` installs it unchecked. The Shop list comes from `azphalt.store`, outside this repository.
- Nothing runs a store-installed model: the LiteRT-LM generator is fixed to Qwen2.5-0.5B (template, 1,280-token context).
- The on-device provider is a small-task helper, not a selectable creature mind.

## Liquid's hosted API

`https://api.liquid.ai/openai/v1`, OpenAI chat-completions compatible, bearer key from `console.liquid.ai/dashboard/api-keys`.
Models: `lfm3-1.7b`, `lfm3-vl-2b`, `lfm3-omni-2b`, `lfm3-audio-2b`, `lfm2.5-24b-a2b`; `d1.2` on a separate decisions endpoint.
Per million tokens: `lfm3-1.7b` 0.08 in / 0.20 out; `lfm3-vl-2b` 0.10 in / 0.50 out. Standard endpoints may keep and train on
prompts. No keyless use. Whether the server turns the XML into `tool_calls` is unverified.
