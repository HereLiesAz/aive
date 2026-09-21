package com.hereliesaz.geministrator.workflow

import com.hereliesaz.geministrator.domain.RoleSurface
import com.hereliesaz.geministrator.domain.SpreadsheetFormat
import com.hereliesaz.geministrator.domain.SpreadsheetSource
import com.hereliesaz.geministrator.domain.SqlDatabaseSource
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull

class RoleSurfaceRuntimeTest {
    @Test
    fun resolvesSpreadsheetSqlAndFlowchartTogether() = runBlocking<Unit> {
        val fake = RecordingSurfaceIntegration()
        val runtime = RoleSurfaceRuntimeRegistry(listOf(fake))
        val surfaces = listOf(
            RoleSurface.Spreadsheet(
                alias = "sheet",
                source = SpreadsheetSource.AppFile("data.csv"),
                format = SpreadsheetFormat.Csv,
                writable = true,
            ),
            RoleSurface.Sql(
                alias = "db",
                source = SqlDatabaseSource.AppDatabase("data.db"),
                query = "SELECT * FROM things",
                writable = true,
            ),
            RoleSurface.Flowchart(
                alias = "flow",
                source = "flowchart TD\nA --> B",
            ),
        )

        val resolved = runtime.resolve(surfaces)

        assertEquals(listOf("sheet", "db", "flow"), resolved.map { it.alias })
        assertEquals("spreadsheet", resolved[0].kind)
        assertEquals("sql", resolved[1].kind)
        assertNotNull(resolved[2].flowchart)
    }

    @Test
    fun routesMutationsByAliasAndExecutionKey() = runBlocking {
        val fake = RecordingSurfaceIntegration()
        val runtime = RoleSurfaceRuntimeRegistry(listOf(fake))
        val surface = RoleSurface.Spreadsheet(
            alias = "sheet",
            source = SpreadsheetSource.AppFile("data.csv"),
            writable = true,
        )

        runtime.apply(
            surfaces = listOf(surface),
            mutations = listOf(
                AiveSurfaceMutation(
                    alias = "sheet",
                    operation = AiveSurfaceMutationOperation.AppendRows,
                    rows = listOf(mapOf("name" to "Ada")),
                ),
            ),
            executionKey = "task:1",
        )

        assertEquals("task:1:0", fake.lastMutationKey)
        assertEquals("sheet", fake.lastMutation?.alias)
    }

    @Test
    fun readOnlyRemoteSpreadsheetRejectsWrites() = runBlocking<Unit> {
        val runtime = RoleSurfaceRuntimeRegistry(listOf(RecordingSurfaceIntegration()))
        val surface = RoleSurface.Spreadsheet(
            alias = "remote",
            source = SpreadsheetSource.Https("https://example.test/data.csv"),
            writable = true,
        )

        assertFailsWith<IllegalArgumentException> {
            runtime.apply(
                surfaces = listOf(surface),
                mutations = listOf(
                    AiveSurfaceMutation(
                        alias = "remote",
                        operation = AiveSurfaceMutationOperation.ReplaceRows,
                    ),
                ),
                executionKey = "task:1",
            )
        }
    }
}

private class RecordingSurfaceIntegration : RoleSurfaceIntegration {
    var lastMutation: AiveSurfaceMutation? = null
    var lastMutationKey: String? = null

    override fun supports(surface: RoleSurface): Boolean =
        surface is RoleSurface.Spreadsheet || surface is RoleSurface.Sql

    override suspend fun resolve(surface: RoleSurface): AiveRoleSurfaceEnvelope = when (surface) {
        is RoleSurface.Spreadsheet -> AiveRoleSurfaceEnvelope(
            alias = surface.alias,
            kind = "spreadsheet",
            writable = surface.writable,
            table = AiveSurfaceTable(listOf("name"), listOf(mapOf("name" to "Ada"))),
        )
        is RoleSurface.Sql -> AiveRoleSurfaceEnvelope(
            alias = surface.alias,
            kind = "sql",
            writable = surface.writable,
            table = AiveSurfaceTable(listOf("id"), listOf(mapOf("id" to "1"))),
        )
        is RoleSurface.Flowchart -> error("Flowchart is handled by the registry")
    }

    override suspend fun apply(
        surface: RoleSurface,
        mutation: AiveSurfaceMutation,
        mutationKey: String,
    ) {
        lastMutation = mutation
        lastMutationKey = mutationKey
    }
}
