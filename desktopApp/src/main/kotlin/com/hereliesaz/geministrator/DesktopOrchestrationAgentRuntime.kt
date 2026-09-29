package com.hereliesaz.geministrator

import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer
import com.hereliesaz.geministrator.memory.DesktopOrtMemorySessionManager
import com.hereliesaz.geministrator.memory.MemoryMicroAgentModelSpec
import com.hereliesaz.geministrator.orchestration.OrchestrationAgentRuntime
import com.hereliesaz.geministrator.orchestration.OrchestrationPacket
import com.hereliesaz.geministrator.orchestration.OrchestrationPlan
import com.hereliesaz.geministrator.orchestration.currentLaunchProgressSink
import com.hereliesaz.geministrator.orchestration.validateAgainst
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

/**
 * Optional on-device workflow planner for desktop, running the fine-tuned planner model with ONNX
 * Runtime. Used only once the user installs the model from Settings; plan repair is left to the
 * linked LLM. Ported from the retired Android planner.
 */
internal class DesktopOrchestrationAgentRuntime(
    private val installer: DesktopPlannerModelInstaller,
) : OrchestrationAgentRuntime, AutoCloseable {
    private val json = Json { ignoreUnknownKeys = true }
    @Volatile
    private var sessionManager: DesktopOrtMemorySessionManager? = null
    private val generator = DesktopOrtCausalGenerator()

    override suspend fun plan(packet: OrchestrationPacket): OrchestrationPlan = withContext(Dispatchers.Default) {
        val progress = currentLaunchProgressSink()
        val installed = installer.installed() ?: error("The local planner model is not installed")
        progress("Loading local planner model…")
        val prepared = sessionManager().sessionFor(
            installed.onnxModel.absolutePath,
            MemoryMicroAgentModelSpec(
                modelId = DesktopPlannerModel.RUNTIME_ARTIFACT_ID,
                quantization = DesktopPlannerModel.QUANTIZATION,
            ),
        )
        HuggingFaceTokenizer.newInstance(installed.root.toPath()).use { tokenizer ->
            val generated = generator.generate(
                session = prepared.session,
                tokenizer = tokenizer,
                modelRoot = installed.root,
                prompt = buildPrompt(packet),
                maxNewTokens = MAX_NEW_TOKENS,
                progress = progress,
                writingLabel = "Writing plan",
            )
            progress("Decoding plan…")
            prepared.captureExecutionProfile()
            decodePlan(generated).validateAgainst(packet)
        }
    }

    override suspend fun repair(packet: OrchestrationPacket): OrchestrationPlan =
        throw UnsupportedOperationException("Plan repair runs on the linked LLM")

    private fun buildPrompt(packet: OrchestrationPacket): String {
        val schema = """{"steps":[{"id":"step-id","name":"short name","objective":"bounded worker objective","roleId":"available-role-id","dependsOn":["prior-step-id"],"requiresHumanApproval":false}]}"""
        return buildString {
            append("<|im_start|>system\n")
            append("You are Haive's bounded orchestration control model. ")
            append("Create an executable dependency-correct DAG for the objective. Use only availableAgents role ids.")
            append(" Return exactly one JSON object and no markdown or prose. Output schema: ")
            append(schema)
            append("<|im_end|>\n<|im_start|>user\n")
            append(json.encodeToString(OrchestrationPacket.serializer(), packet))
            append("<|im_end|>\n<|im_start|>assistant\n")
        }
    }

    private fun decodePlan(generated: String): OrchestrationPlan {
        val start = generated.indexOf('{')
        val end = generated.lastIndexOf('}')
        require(start >= 0 && end > start) { "Local planner did not return a JSON object" }
        return json.decodeFromString(OrchestrationPlan.serializer(), generated.substring(start, end + 1))
    }

    private fun sessionManager(): DesktopOrtMemorySessionManager = synchronized(this) {
        sessionManager ?: DesktopOrtMemorySessionManager().also { sessionManager = it }
    }

    /** Closes loaded model sessions so the files can be deleted; the next plan reloads them. */
    fun releaseModel() = synchronized(this) {
        sessionManager?.close()
        sessionManager = null
    }

    override fun close() = releaseModel()

    private companion object {
        const val MAX_NEW_TOKENS = 768
    }
}
