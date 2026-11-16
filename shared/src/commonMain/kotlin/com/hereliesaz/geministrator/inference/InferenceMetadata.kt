package com.hereliesaz.geministrator.inference

/** Durable artifact metadata key linking an artifact to the concrete inference invocation that emitted it. */
const val INFERENCE_INVOCATION_ID_METADATA_KEY: String = "aive.inferenceInvocationId"

/** The invocation id recorded in artifact [metadata]. */
fun inferenceInvocationIdOf(metadata: Map<String, String>): String? =
    metadata[INFERENCE_INVOCATION_ID_METADATA_KEY]
        ?.takeIf(String::isNotBlank)
