package com.hereliesaz.geministrator.queen

private val markup = setOf('`', '*', '_', '~', '#', '[', ']', '<', '>', '|', '\\')
private val link = Regex("""(?i)(https?://|www\.)\S+""")

private fun hidden(c: Char): Boolean {
    val n = c.code
    return n in 0x200B..0x200F || n in 0x202A..0x202E || n in 0x2060..0x2064 || n in 0x2066..0x2069 || n == 0xFEFF
}

/**
 * Text from the run made safe to show on a line of the queen's: one line, control and hidden
 * direction characters gone, markup characters and links neutralised, capped at [max] with an ellipsis.
 */
internal fun queenText(raw: String, max: Int): String {
    val flat = buildString {
        for (c in link.replace(raw, "link")) {
            when {
                hidden(c) -> Unit
                c.code < 0x20 || c.code in 0x7F..0x9F || c in markup -> append(' ')
                else -> append(c)
            }
        }
    }.trim().replace(Regex("\\s+"), " ")
    if (flat.length <= max) return flat
    var cut = flat.take((max - 1).coerceAtLeast(0))
    if (cut.isNotEmpty() && cut.last().isHighSurrogate()) cut = cut.dropLast(1)
    return cut.trimEnd() + "…"
}

/** A name or other short value used inside a sentence. */
internal fun queenSlot(raw: String, max: Int = 40): String = queenText(raw, max)

/** A quoted line: longer than a slot, still one safe line. */
internal fun queenQuoteText(raw: String, max: Int): String = queenText(raw, max)

/** A stable id from the state a note stands for: 64-bit FNV-1a, no clock, no randomness. */
internal fun queenNoteId(vararg parts: String): String {
    var h = 0xcbf29ce484222325uL
    for (c in parts.joinToString("|")) {
        h = (h xor c.code.toULong()) * 0x100000001b3uL
    }
    return "q" + h.toString(16)
}
