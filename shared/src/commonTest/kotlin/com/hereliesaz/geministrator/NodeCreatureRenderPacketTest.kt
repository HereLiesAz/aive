package com.hereliesaz.geministrator

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class NodeCreatureRenderPacketTest {
    @Test
    fun decodesVersionedProjectedGeometryAndTerminalAnchors() {
        val packet = buildPacket(
            triangles = listOf(
                EncodedTriangle(
                    material = 2,
                    shade = 1,
                    depth = 0.25f,
                    points = listOf(
                        NodeCreaturePoint(-0.5f, 0.25f),
                        NodeCreaturePoint(0.75f, 0.5f),
                        NodeCreaturePoint(0.1f, -0.9f),
                    ),
                ),
            ),
            edges = listOf(
                EncodedEdge(
                    depth = -0.1f,
                    weight = 2.5f,
                    from = NodeCreaturePoint(-0.5f, 0f),
                    to = NodeCreaturePoint(0.8f, 0.2f),
                ),
            ),
            terminals = listOf(
                NodeCreaturePoint(-1f, 0f),
                NodeCreaturePoint(0f, -1f),
                NodeCreaturePoint(1f, 0f),
            ),
        )

        val decoded = NodeCreatureRenderPacketDecoder.decode(packet)

        assertEquals(NODE_CREATURE_PACKET_VERSION, decoded.version)
        assertEquals(1, decoded.triangles.size)
        assertEquals(NodeCreatureMaterial.Eye, decoded.triangles.single().material)
        assertEquals(1, decoded.triangles.single().shade)
        assertEquals(1, decoded.silhouetteEdges.size)
        assertEquals(2.5f, decoded.silhouetteEdges.single().weight)
        assertEquals(3, decoded.terminalAnchors.size)
        assertEquals(NodeCreaturePoint(1f, 0f), decoded.terminalAnchors.last())
    }

    @Test
    fun rejectsWrongMagicAndUnsupportedVersions() {
        val valid = buildPacket(emptyList(), emptyList(), emptyList())
        val wrongMagic = valid.copyOf().also { it[0] = 'X'.code.toByte() }
        val wrongVersion = valid.copyOf().also {
            it[4] = 2
            it[5] = 0
        }

        assertFailsWith<IllegalArgumentException> { NodeCreatureRenderPacketDecoder.decode(wrongMagic) }
        assertFailsWith<IllegalArgumentException> { NodeCreatureRenderPacketDecoder.decode(wrongVersion) }
    }

    @Test
    fun rejectsPacketsWhoseDeclaredGeometryDoesNotFitPayload() {
        val packet = buildPacket(emptyList(), emptyList(), emptyList()).copyOf().also {
            it[8] = 1 // claim one 32-byte triangle without providing it
        }

        val failure = assertFailsWith<IllegalArgumentException> {
            NodeCreatureRenderPacketDecoder.decode(packet)
        }
        assertTrue(failure.message.orEmpty().contains("size mismatch"))
    }

    private data class EncodedTriangle(
        val material: Int,
        val shade: Int,
        val depth: Float,
        val points: List<NodeCreaturePoint>,
    )

    private data class EncodedEdge(
        val depth: Float,
        val weight: Float,
        val from: NodeCreaturePoint,
        val to: NodeCreaturePoint,
    )

    private fun buildPacket(
        triangles: List<EncodedTriangle>,
        edges: List<EncodedEdge>,
        terminals: List<NodeCreaturePoint>,
    ): ByteArray = buildList<Byte> {
        add('H'.code.toByte())
        add('N'.code.toByte())
        add('C'.code.toByte())
        add('R'.code.toByte())
        putU16(NODE_CREATURE_PACKET_VERSION)
        putU16(0)
        putU32(triangles.size)
        putU32(edges.size)
        putU32(terminals.size)
        triangles.forEach { triangle ->
            add(triangle.material.toByte())
            add(triangle.shade.toByte())
            putU16(0)
            putF32(triangle.depth)
            triangle.points.forEach { point -> putPoint(point) }
        }
        edges.forEach { edge ->
            putF32(edge.depth)
            putF32(edge.weight)
            putPoint(edge.from)
            putPoint(edge.to)
        }
        terminals.forEach { point -> putPoint(point) }
    }.toByteArray()

    private fun MutableList<Byte>.putPoint(point: NodeCreaturePoint) {
        putF32(point.x)
        putF32(point.y)
    }

    private fun MutableList<Byte>.putU16(value: Int) {
        add((value and 0xFF).toByte())
        add(((value ushr 8) and 0xFF).toByte())
    }

    private fun MutableList<Byte>.putU32(value: Int) {
        repeat(4) { shift -> add(((value ushr (shift * 8)) and 0xFF).toByte()) }
    }

    private fun MutableList<Byte>.putF32(value: Float) = putU32(value.toBits())
}
