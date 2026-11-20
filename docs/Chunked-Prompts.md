# Chunked prompts

Some sources take only so many characters in one message (Google AI Mode: 1,800) but keep a conversation. For those a long prompt can go in parts, as a person would paste a long text into a chat. `providers/llm/.../ChunkedPrompt.kt` does it.

**When it helps.** Only where the limit is per message and the source remembers what came before (a chat session, a browser thread). A source whose API is stateless and replays the whole exchange on every call (the hosted REST text APIs here) gains nothing: each call carries every part again, so the window is the same. For those, a prompt that is too long fails over to another source (`ErrorKind.InputTooLong`, `ProviderFailover`).

**How it goes.**

1. A prompt that fits goes as it is, with no wrapping.
2. A longer one is cut by `PromptChunker.split` into pieces of at most `maxMessageChars` minus a fixed wrapper, at a blank line, else a line break, else a full stop, else a space, and only then inside a word; never inside a surrogate pair. Joined, the pieces are the prompt exactly.
3. The first message says the request comes in N parts and asks for the single word CONTINUE after each part except the last. Every part is headed "Part i of N"; the last says it is the last, so the source answers.
4. After each part but the last the reply must be a continue (the word, bare or with a little around it). Anything else gets one nudge ("Part i of N was received. Reply with only the word CONTINUE."); if that fails, the prompt fails with a message naming the part. It is never guessed at.
5. More than `ChunkedPrompt.DEFAULT_MAX_PARTS` (20) parts is refused before anything is sent.

**Plugging a source in.** Implement `ConversationChannel` (`send(message): reply`, the source keeping the conversation) and wrap it: `ChunkingTextGenerationApi(open = { channel }, maxMessageChars = …)` is a `TextGenerationApi` that opens a fresh conversation per call.

**Google AI Mode takes a long request as an attached file instead.** `GoogleAiModeApi` does not chunk: over 1,800 characters it opens the blank AI Mode page, attaches the request as `request.md` (`AiModePage.attachDocument`), types a short question pointing at it (`typeAndSend`) and reads the answer as usual. The Android WebView does the attach by answering the page's file chooser with a file in this app's private cache, deleted when the view closes. Where the view cannot attach, or the page does not take the file, the error still says "takes at most 1800", so the request fails over to another source and is never cut or sent another way. The controls are found by the page's own labels and are not verified against the live page.

**The chunker is not wired to anything yet.** Google AI Mode cannot use it today: its browser view only opens a page for one question and reads its text, with no way to type a follow-up. It would need that added on each platform first (docs/A2A.md and the Google AI Mode notes in `GoogleAiMode.kt`). The keeper asked for the chunker to exist for sources that need it.
