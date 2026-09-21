package com.hereliesaz.aive

import android.content.Context
import androidx.javascriptengine.JavaScriptSandbox
import com.hereliesaz.geministrator.domain.ArtifactId
import com.hereliesaz.geministrator.domain.ArtifactKind
import com.hereliesaz.geministrator.domain.ArtifactRef
import com.hereliesaz.geministrator.domain.ScriptLanguage
import com.hereliesaz.geministrator.domain.ScriptRunner
import com.hereliesaz.geministrator.domain.TaskExecutor
import com.hereliesaz.geministrator.domain.TaskRunStatus
import com.hereliesaz.geministrator.workflow.AiveScriptResult
import com.hereliesaz.geministrator.workflow.AiveTaskEnvelope
import com.hereliesaz.geministrator.workflow.TaskExecutorContext
import com.hereliesaz.geministrator.workflow.TaskExecutorExecution
import com.hereliesaz.geministrator.workflow.TaskExecutorIntegration
import com.hereliesaz.geministrator.workflow.toAiveTaskEnvelope
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json

internal class AndroidJavaScriptExecutorIntegration(
    context: Context,
) : TaskExecutorIntegration {
    override val orchestrationToolId: String = "local-javascript"

    private val appContext = context.applicationContext
    private val mutex = Mutex()
    private val json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = true
    }

    override fun supports(executor: TaskExecutor): Boolean =
        executor is TaskExecutor.Script &&
            executor.language == ScriptLanguage.JavaScript &&
            executor.runner is ScriptRunner.LocalSandbox &&
            JavaScriptSandbox.isSupported()

    override suspend fun dispatch(context: TaskExecutorContext): TaskExecutorExecution = mutex.withLock {
        val executor = context.executor as? TaskExecutor.Script
            ?: error("Local JavaScript integration requires a script executor")
        require(executor.language == ScriptLanguage.JavaScript) {
            "Local sandbox currently supports JavaScript only"
        }
        require(executor.runner is ScriptRunner.LocalSandbox) {
            "Script is not configured for the local sandbox"
        }
        require(JavaScriptSandbox.isSupported()) {
            "Android JavaScript sandbox is unavailable on this device"
        }

        val envelope = json.encodeToString(
            AiveTaskEnvelope.serializer(),
            context.toAiveTaskEnvelope(),
        )
        val envelopeLiteral = json.encodeToString(String.serializer(), envelope)
        val wrapped = buildString {
            append("(function(){")
            append("const aive=Object.freeze(JSON.parse(")
            append(envelopeLiteral)
            append("));")
            append("const __aiveResult=(function(aive){\n")
            append(executor.source)
            append("\n})(aive);")
            append("return JSON.stringify(__aiveResult===undefined?{status:'completed'}:__aiveResult);")
            append("})()")
        }

        val sandbox = JavaScriptSandbox.createConnectedInstanceAsync(appContext).await()
        try {
            val isolate = sandbox.createIsolate()
            try {
                val raw = isolate.evaluateJavaScriptAsync(wrapped).await()
                parseResult(raw, context)
            } finally {
                isolate.close()
            }
        } finally {
            sandbox.close()
        }
    }

    override suspend fun reconcile(context: TaskExecutorContext): TaskExecutorExecution =
        TaskExecutorExecution(
            status = TaskRunStatus.Completed,
            externalRunId = context.taskRun.externalRunId,
            artifacts = context.taskRun.artifacts,
            progress = 1f,
            progressMessage = context.taskRun.progressMessage ?: "Local JavaScript completed",
        )

    private fun parseResult(
        raw: String,
        context: TaskExecutorContext,
    ): TaskExecutorExecution {
        val result = runCatching {
            json.decodeFromString(AiveScriptResult.serializer(), raw)
        }.getOrElse {
            AiveScriptResult(
                status = "completed",
                output = raw.takeIf(String::isNotBlank),
            )
        }

        val artifacts = buildList {
            result.artifacts.forEachIndexed { index, artifact ->
                require(artifact.uri != null || artifact.textContent != null) {
                    "Script artifact ${artifact.label} must return uri or textContent"
                }
                add(
                    ArtifactRef(
                        id = ArtifactId(
                            "${context.taskRun.id.value}:script:${context.taskRun.attempt}:$index",
                        ),
                        kind = artifactKind(artifact.kind),
                        taskRunId = context.taskRun.id,
                        label = artifact.label,
                        uri = artifact.uri,
                        textContent = artifact.textContent,
                        mediaType = artifact.mediaType,
                        metadata = artifact.metadata,
                        createdAtEpochMillis = context.nowEpochMillis,
                    ),
                )
            }
            result.output
                ?.takeIf(String::isNotBlank)
                ?.let { output ->
                    add(
                        ArtifactRef(
                            id = ArtifactId(
                                "${context.taskRun.id.value}:script:${context.taskRun.attempt}:output",
                            ),
                            kind = ArtifactKind.CommandOutput,
                            taskRunId = context.taskRun.id,
                            label = "Script output",
                            textContent = output,
                            mediaType = "text/plain",
                            createdAtEpochMillis = context.nowEpochMillis,
                        ),
                    )
                }
        }
        val failed = result.status.equals("failed", ignoreCase = true) ||
            result.status.equals("error", ignoreCase = true)
        return TaskExecutorExecution(
            status = if (failed) TaskRunStatus.Failed else TaskRunStatus.Completed,
            externalRunId = "local-js:${context.taskRun.id.value}:${context.taskRun.attempt}",
            artifacts = artifacts,
            progress = if (failed) null else 1f,
            progressMessage = result.message ?: if (failed) "Local JavaScript failed" else "Local JavaScript completed",
        )
    }

    private fun artifactKind(raw: String): ArtifactKind =
        ArtifactKind.entries.firstOrNull { it.name.equals(raw, ignoreCase = true) }
            ?: ArtifactKind.CommandOutput
}
