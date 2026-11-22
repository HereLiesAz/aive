package com.hereliesaz.geministrator.providers.lfm

/**
 * What LiquidAI/LFM3-1.7B's own `chat_template.jinja` renders for the conversations in [LfmChatPromptTest], taken from the
 * template run through Jinja 3.1 with Hugging Face's `tojson` (`json.dumps(x, ensure_ascii=False)`); the start token is
 * empty. Regenerate by running the template, not by editing these.
 */
internal object LfmChatPromptGolden {
    val PLAIN_THINK_ON: String = "<|im_start|>system\nBe brief.<|im_end|>\n<|im_start|>user\nHi<|im_end|>\n<|im_start|>assistant\n<think>"
    val PLAIN_THINK_OFF: String = "<|im_start|>user\nHi<|im_end|>\n<|im_start|>assistant\n<think></think>"
    val TOOLS: String = "<|im_start|>system\nBe brief.\n\n<tools>\n{\"name\": \"get_weather\", \"description\": \"Current weather in a city.\", \"parameters\": {\"type\": \"object\", \"properties\": {\"city\": {\"type\": \"string\", \"description\": \"City name\"}, \"days\": {\"type\": \"integer\"}}, \"required\": [\"city\"]}}\n</tools><|im_end|>\n<|im_start|>user\nWeather in Paris?<|im_end|>\n<|im_start|>assistant\n<think></think>"
    val TOOLS_NO_SYSTEM: String = "<|im_start|>system\n<tools>\n{\"name\": \"get_weather\", \"description\": \"Current weather in a city.\", \"parameters\": {\"type\": \"object\", \"properties\": {\"city\": {\"type\": \"string\", \"description\": \"City name\"}, \"days\": {\"type\": \"integer\"}}, \"required\": [\"city\"]}}\n</tools><|im_end|>\n<|im_start|>user\nWeather in Paris?<|im_end|>\n<|im_start|>assistant\n<think>"
    val TOOL_ROUND_TRIP: String = "<|im_start|>system\n<tools>\n{\"name\": \"get_weather\", \"description\": \"Current weather in a city.\", \"parameters\": {\"type\": \"object\", \"properties\": {\"city\": {\"type\": \"string\", \"description\": \"City name\"}, \"days\": {\"type\": \"integer\"}}, \"required\": [\"city\"]}}\n</tools><|im_end|>\n<|im_start|>user\nWeather in Paris?<|im_end|>\n<|im_start|>assistant\n<think>I should call the tool.</think><tool_call>\n<function=get_weather>\n<parameter=city>\nParis\n</parameter>\n<parameter=days>\n3\n</parameter>\n<parameter=metric>\ntrue\n</parameter>\n<parameter=note>\nnull\n</parameter>\n<parameter=tags>\n[\"a\", \"b\"]\n</parameter>\n<parameter=opts>\n{\"k\": 1}\n</parameter>\n<parameter=text>\nline1\nline2\n</parameter>\n</function>\n</tool_call><|im_end|>\n<|im_start|>tool\n<tool_response>\nParis: 18 C, light rain\n</tool_response><|im_end|>\n<|im_start|>assistant\n<think>"
    val MULTI_TURN: String = "<|im_start|>user\nQ1<|im_end|>\n<|im_start|>assistant\n<think></think>A1<|im_end|>\n<|im_start|>user\nQ2<|im_end|>\n<|im_start|>assistant\n<think></think>tail<|im_end|>\n<|im_start|>user\nQ3<|im_end|>\n<|im_start|>assistant\n<think></think>"
    val ASSISTANT_AFTER_USER_NO_THINK: String = "<|im_start|>user\nQ1<|im_end|>\n<|im_start|>assistant\n<think></think>A1<|im_end|>\n<|im_start|>tool\n<tool_response>\nr1\nr2\n</tool_response><|im_end|>\n<|im_start|>assistant\n<think>"
    val PRESERVE: String = "<|im_start|>user\nQ1<|im_end|>\n<|im_start|>assistant\n<think>T</think>A1<|im_end|>\n<|im_start|>user\nQ2<|im_end|>\n<|im_start|>assistant\n<think>"
    val TWO_CALLS: String = "<|im_start|>user\nx<|im_end|>\n<|im_start|>assistant\n<think></think><tool_call>\n<function=a>\n</function>\n</tool_call>\n\n<tool_call>\n<function=b>\n<parameter=q>\nz\n</parameter>\n</function>\n</tool_call><|im_end|>\n<|im_start|>assistant\n<think>"
}
