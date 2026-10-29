package com.hereliesaz.geministrator.memory.procedural

import app.cash.sqldelight.async.coroutines.awaitAsList
import app.cash.sqldelight.async.coroutines.awaitAsOneOrNull
import app.cash.sqldelight.db.SqlDriver
import com.hereliesaz.geministrator.memory.memoryBankKey
import com.hereliesaz.geministrator.memory.procedural.db.ProcedureDatabase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json

class ProcedureStoreCorruptionException(message: String, cause: Throwable) : IllegalStateException(message, cause)

/**
 * Durable, workflow-owned procedural catalog. The database is separate from contextual GRIP;
 * it stores no executable source, privileged grants or unredacted command output.
 *
 * Every mutation validates the same invariants as the in-memory store inside a SQL transaction.
 * The revision is read from SQLite on every operation, rather than cached, to prevent stale
 * writers from overwriting a reopened or concurrently accessed bank.
 */
class SqlProcedureStore(
    val workflowId: String,
    driver: SqlDriver,
    private val json: Json = Json { encodeDefaults = true; ignoreUnknownKeys = false },
) : ProcedureStore {
    private val database = ProcedureDatabase(driver)
    private val queries = database.procedureQueries
    private val mutex = Mutex()

    init {
        require(memoryBankKey(workflowId) == workflowId) { "A procedure bank requires a workflow id" }
    }

    override suspend fun read(): ProcedureSnapshot = mutex.withLock { readUnlocked() }

    override suspend fun commit(expectedRevision: Long, mutation: ProcedureMutation): Boolean =
        mutex.withLock {
            var committed = false
            database.transaction {
                val current = readUnlocked()
                if (current.revision != expectedRevision) return@transaction

                // Reuse common validation, including unique immutable versions, receipt IDs,
                // workflow isolation and referring only to definitions in this bank.
                val validated = InMemoryProcedureStore(workflowId, current)
                check(validated.commit(expectedRevision, mutation))
                val next = validated.read()

                mutation.definitionsToAdd.forEach { definition ->
                    queries.insertDefinition(
                        "${definition.ref.name}@${definition.ref.version}",
                        json.encodeToString(ProcedureDefinition.serializer(), definition),
                    )
                }
                mutation.evidenceToAdd.forEach { evidence ->
                    queries.insertEvidence(
                        evidence.id,
                        "${evidence.procedure.name}@${evidence.procedure.version}",
                        evidence.receiptId,
                        json.encodeToString(ProcedureEvidence.serializer(), evidence),
                    )
                }
                mutation.tracesToAdd.forEach { trace ->
                    queries.insertTrace(
                        trace.receiptKey,
                        json.encodeToString(ProcedureTrace.serializer(), trace),
                    )
                }
                queries.setRevision(next.revision.toString())
                committed = true
            }
            committed
        }

    private suspend fun readUnlocked(): ProcedureSnapshot {
        try {
            val snapshot = ProcedureSnapshot(
                revision = queries.revision().awaitAsOneOrNull()?.toLong() ?: 0L,
                definitions = queries.allDefinitions().awaitAsList().map {
                    json.decodeFromString(ProcedureDefinition.serializer(), it)
                },
                evidence = queries.allEvidence().awaitAsList().map {
                    json.decodeFromString(ProcedureEvidence.serializer(), it)
                },
                traces = queries.allTraces().awaitAsList().map {
                    json.decodeFromString(ProcedureTrace.serializer(), it)
                },
            )
            // Decode without trust: an invalid, foreign or dangling record makes the bank
            // unreadable instead of silently activating or filtering part of the catalog.
            InMemoryProcedureStore(workflowId, snapshot)
            return snapshot
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            throw ProcedureStoreCorruptionException("Procedural-memory database is unreadable", failure)
        }
    }
}
