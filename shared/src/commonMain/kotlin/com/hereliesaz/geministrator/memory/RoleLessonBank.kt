package com.hereliesaz.geministrator.memory

// A specialist role's lessons live in a memory bank of their own, scoped to that role, with the generic role's bank as its
// lineage parent. It is the existing bank and lineage mechanism (one bank per workflow id), keyed by a role id; no new store.

/** Prefix of the bank id a role's lessons are kept under. */
const val ROLE_BANK_PREFIX: String = "role:"

/** The label every banked lesson carries, so a role's lessons can be read back from its bank. */
const val ROLE_LESSON_LABEL: String = "Lesson"

/** The bank id for [roleId]'s lessons; blank for a blank id. */
fun roleBankId(roleId: String): String = roleId.trim().takeIf { it.isNotEmpty() }?.let { ROLE_BANK_PREFIX + it }.orEmpty()

/**
 * One deliberate bank request per non-blank lesson line (each line already carries its source and fetch date), scoped to the
 * role's own bank. Empty for a blank role id.
 */
fun roleLessonRequests(roleId: String, lessons: List<String>, nowEpochMillis: Long): List<MemoryBankRequest> {
    val bank = roleBankId(roleId)
    if (bank.isEmpty()) return emptyList()
    return lessons.map { it.trim() }.filter { it.isNotEmpty() }.distinct().map { line ->
        MemoryBankRequest(
            sourceSessionId = bank,
            text = line,
            label = ROLE_LESSON_LABEL,
            sourceKind = MemorySourceKind.AgentNote,
            scope = MemoryBankScope(workflowRunId = bank, roleId = roleId.trim()),
            bankedAtEpochMillis = nowEpochMillis,
        )
    }
}

/** The lesson lines in [snapshot] (a role bank's lineage view: its own and its generic parent's), each once, oldest first. */
fun roleLessonsIn(snapshot: MemorySnapshot): List<String> =
    snapshot.episodes.sortedBy { it.createdAtEpochMillis }.flatMap { it.chunks }
        .filter { it.label == ROLE_LESSON_LABEL }.map { it.text }.distinct()
