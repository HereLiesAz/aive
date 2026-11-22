package com.hereliesaz.geministrator.inference

/**
 * The `.litertlm` container LiteRT-LM runs: a file that starts with the eight bytes `LITERTLM`, then holds the model, its
 * tokenizer, its chat template and its metadata as sections. It is not a TFLite flatbuffer (those carry `TFL3` at byte 4), so
 * the installer's `litert` and `task` checks refuse it; a store package that carries one declares `requirements.runtime =
 * "litert-lm"` (or names the file `*.litertlm`) and is installed through [looksLikeContainer].
 */
object LiteRtLmContainer {
    /** The runtime id a package's `requirements.runtime` names. */
    const val RUNTIME_ID: String = "litert-lm"

    const val FILE_EXTENSION: String = "litertlm"

    private val MAGIC: ByteArray = "LITERTLM".encodeToByteArray()

    /** Whether [header], the first bytes of a file, starts like a LiteRT-LM container. */
    fun looksLikeContainer(header: ByteArray): Boolean =
        header.size >= MAGIC.size && MAGIC.indices.all { header[it] == MAGIC[it] }

    /** Whether a package's declared runtime names LiteRT-LM (`litert-lm`, or `litertlm`). */
    fun isRuntime(declared: String?): Boolean =
        declared?.trim()?.lowercase()?.replace("-", "") == "litertlm"
}
