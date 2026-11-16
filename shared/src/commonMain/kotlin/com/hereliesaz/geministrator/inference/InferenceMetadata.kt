package com.hereliesaz.geministrator.inference

/** Durable artifact metadata key linking an artifact to the concrete inference invocation that emitted it. */
const val INFERENCE_INVOCATION_ID_METADATA_KEY: String = "aive.inferenceInvocationId"

/** Legacy input key: older builds stored the invocation id under this metadata key; read-side only. */
private const val LEGACY_INFERENCE_INVOCATION_ID_METADATA_KEY: String = "haive.inferenceInvocationId"

/** The invocation id recorded in artifact [metadata], current key first, then the legacy key. */
fun inferenceInvocationIdOf(metadata: Map<String, String>): String? =
    (metadata[INFERENCE_INVOCATION_ID_METADATA_KEY] ?: metadata[LEGACY_INFERENCE_INVOCATION_ID_METADATA_KEY])
        ?.takeIf(String::isNotBlank)
