package com.hereliesaz.geministrator.workflow

import com.hereliesaz.geministrator.domain.TaskExecutor
import com.hereliesaz.geministrator.persistence.SettingsWorkflowPersistence
import kotlin.test.Test
import kotlin.test.assertContains
import kotlinx.serialization.encodeToString

class ExternalServiceSerializationTest {
    @Test
    fun externalServiceUsesStableStorePackageDiscriminator() {
        val encoded = SettingsWorkflowPersistence.defaultJson.encodeToString<TaskExecutor>(
            TaskExecutor.ExternalService(
                service = "azphalt-node-character-rig-pipeline",
                operation = "preflight|package|1.0.0",
            ),
        )
        assertContains(
            encoded,
            "\"type\":\"com.hereliesaz.geministrator.domain.TaskExecutor.ExternalService\"",
        )
    }
}
