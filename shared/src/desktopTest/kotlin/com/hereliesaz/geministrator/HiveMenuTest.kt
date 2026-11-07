package com.hereliesaz.geministrator

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.use
import com.hereliesaz.geministrator.domain.BuiltInRoles
import com.hereliesaz.geministrator.domain.TaskDefinition
import com.hereliesaz.geministrator.domain.TaskDefinitionId
import com.hereliesaz.geministrator.domain.TaskExecutor
import com.hereliesaz.geministrator.domain.WorkflowDefinition
import com.hereliesaz.geministrator.domain.WorkflowDefinitionId
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** A long press on a hive in the district opens its options; Delete hive asks first (docs/Keepers-Room.md, Hive options). */
class HiveMenuTest {
    private val out = File("build/hive-screens").apply { mkdirs() }

    private val hive = WorkflowDefinition(
        id = WorkflowDefinitionId("feature-delivery"),
        name = "Feature delivery",
        tasks = listOf(
            TaskDefinition(
                id = TaskDefinitionId("build"),
                name = "Implement",
                objective = "Implement",
                roleId = BuiltInRoles.ImplementationEngineer.id,
                executor = TaskExecutor.RoleAgent(BuiltInRoles.ImplementationEngineer.id),
            ),
        ),
    )

    private fun scene(content: @androidx.compose.runtime.Composable () -> Unit) =
        ImageComposeScene(width = 900, height = 1400, density = Density(2f)) {
            LoadAzphaltFonts()
            Box(Modifier.fillMaxSize().background(Azphalt.currentGround.page).padding(12.dp)) { content() }
        }

    @Test
    fun aLongPressOnAHiveOpensItsOptionsAndAShortOneStillOpensTheHive() {
        var entered = 0
        scene {
            DistrictScreen(
                onLoadDefinitions = { listOf(hive) },
                onLoadRuns = { emptyList() },
                onEnterNeighbourhood = { entered++ },
                onImportAgent = {},
                hiveDeletion = { HiveDeletionActions(check = { HiveDeleteVerdict.Allowed }, delete = { HiveDeleteVerdict.Allowed }) },
                modifier = Modifier.fillMaxSize(),
            )
        }.use { scene ->
            var now = 0L
            fun advance(ms: Int) { repeat(ms / 50) { now += 50_000_000L; scene.render(now) } }
            advance(1_000)
            val cell = Offset(200f, 560f)
            // A tap enters the hive, as it always did.
            scene.sendPointerEvent(PointerEventType.Press, cell)
            advance(100)
            scene.sendPointerEvent(PointerEventType.Release, cell)
            advance(200)
            assertEquals(1, entered, "A short press still opens the hive")

            // Held, it opens the options instead of entering the hive.
            scene.sendPointerEvent(PointerEventType.Press, cell)
            advance(2_000)
            val image = run { advance(300); scene.render(now + 50_000_000L) }
            scene.sendPointerEvent(PointerEventType.Release, cell)
            assertEquals(1, entered, "A long press does not also open the hive")
            val bytes = image.encodeToData()!!.bytes
            assertTrue(bytes.size > 10_000)
            File(out, "40-hive-options.png").writeBytes(bytes)
        }
    }

    @Test
    fun deleteIsCheckedFirstAndNothingIsDeletedUntilConfirmed() {
        var checks = 0
        var deletes = 0
        scene {
            HiveDeleteRequest(
                name = "Feature delivery",
                creatures = 1,
                actions = HiveDeletionActions(
                    check = { checks++; HiveDeleteVerdict.Allowed },
                    delete = { deletes++; HiveDeleteVerdict.Allowed },
                ),
                onFinished = {},
            )
        }.use { scene ->
            var now = 0L
            repeat(30) { now += 50_000_000L; scene.render(now) }
            assertEquals(1, checks, "Asked first")
            assertEquals(0, deletes, "and not deleted until the keeper confirms")
        }
    }

    @Test
    fun aRefusedDeleteSaysWhyAndDeletesNothing() {
        var deletes = 0
        var finished: Boolean? = null
        scene {
            HiveDeleteRequest(
                name = "Feature delivery",
                creatures = 1,
                actions = HiveDeletionActions(
                    check = { HiveDeleteVerdict.Refused(HiveDeleteRefusal.LiveRun) },
                    delete = { deletes++; HiveDeleteVerdict.Allowed },
                ),
                onFinished = { finished = it },
            )
        }.use { scene ->
            var now = 0L
            repeat(30) { now += 50_000_000L; scene.render(now) }
            assertEquals(0, deletes)
            assertEquals(null, finished, "The reason stays up until it is read")
            File(out, "41-hive-delete-refused.png").writeBytes(scene.render(now + 50_000_000L).encodeToData()!!.bytes)
        }
    }
}
