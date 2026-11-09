package com.hereliesaz.geministrator.workflow.schedule

import com.hereliesaz.geministrator.domain.ContextProvenance

/**
 * Where a scheduled run's objective comes from. The keeper's own objective text is keeper-written. One
 * followed by the swarm's evidence (SELF_IMPROVEMENT.md) also carries text an earlier model wrote, so the
 * run's objective is external content to the context-taint rule: the evidence cannot steer a step that
 * could change something without a person approving it.
 */
fun WorkflowSchedule.objectiveProvenance(): ContextProvenance =
    if (withEvidence) ContextProvenance.ExternalContent else ContextProvenance.KeeperWritten
