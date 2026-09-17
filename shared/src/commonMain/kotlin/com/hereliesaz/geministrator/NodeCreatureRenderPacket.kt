package com.hereliesaz.geministrator

internal const val NODE_CREATURE_PACKET_VERSION: Int = 1
private const val NODE_CREATURE_PACKET_HEADER_BYTES: Int = 20
private const val NODE_CREATURE_TRIANGLE_BYTES: Int = 32
private const val NODE_CREATURE_EDGE_BYTES: Int = 24
private const val NODE_CREATURE_TERMINAL_BYTES: Int = 8
private const val NODE_CREATURE_MAX_PACKET_ITEMS: Int = 100_000

internal enum class NodeCreatureMaterial {
    Body,
    Accent,
    Eye,
    Limb,
    Terminal,
}

internal data class NodeCreaturePoint(
    val x: Float,
    val y: Float,
)

internal data class NodeCreatureTriangle(
    val material: NodeCreatureMaterial,
    val shade: Int,
    val depth: Float,
    val a: NodeCreaturePoint,
    val b: NodeCreaturePoint,
    val c: NodeCreaturePoint,
)

internal data class NodeCreatureEdge(
    val depth: Float,
    val weight: Float,
    val from: NodeCreaturePoint,
    val to: NodeCreaturePoint,
)

internal data class NodeCreatureRenderPacket(
    val version: Int,
    val triangles: List<NodeCreatureTriangle>,
    val silhouetteEdges: List<NodeCreatureEdge>,
    val terminalAnchors: List<NodeCreaturePoint>,
)

/**
 * Decodes the renderer-neutral `HNCR` packet emitted by the Rust node-creature engine.
 *
 * Creature generation, animation, projection and terminal selection remain native/WASM concerns.
 * Kotlin only receives the already-projected vector scene needed to integrate that output with the
 * workflow UI.
 */
internal object NodeCreatureRenderPacketDecoder {
    fun decode(bytes: ByteArray): NodeCreatureRenderPacket {
        require(bytes.size >= NODE_CREATURE_PACKET_HEADER_BYTES) {
            "Node-creature packet is shorter than its $NODE_CREATURE_PACKET_HEADER_BYTES-byte header."
        }
        require(
            bytes[0] == 'H'.code.toByte() &&
                bytes[1] == 'N'.code.toByte() &&
                bytes[2] == 'C'.code.toByte() &&
                bytes[3] == 'R'.code.toByte(),
        ) { "Node-creature packet has an invalid magic header." }

        val reader = LittleEndianReader(bytes, 4)
        val version = reader.u16()
        require(version == NODE_CREATURE_PACKET_VERSION) {
            "Unsupported node-creature packet version $version; expected $NODE_CREATURE_PACKET_VERSION."
        }
        reader.u16() // reserved
        val triangleCount = reader.count("triangle")
        val edgeCount = reader.count("edge")
        val terminalCount = reader.count("terminal")

        val requiredSize = NODE_CREATURE_PACKET_HEADER_BYTES.toLong() +
            triangleCount.toLong() * NODE_CREATURE_TRIANGLE_BYTES +
            edgeCount.toLong() * NODE_CREATURE_EDGE_BYTES +
            terminalCount.toLong() * NODE_CREATURE_TERMINAL_BYTES
        require(requiredSize == bytes.size.toLong()) {
            "Node-creature packet size mismatch: header requires $requiredSize bytes, received ${bytes.size}."
        }

        val triangles = List(triangleCount) {
            val materialCode = reader.u8()
            val material = NodeCreatureMaterial.entries.getOrNull(materialCode)
                ?: throw IllegalArgumentException("Unknown node-creature material code $materialCode.")
            val shade = reader.u8()
            require(shade in 0..2) { "Unknown node-creature shade level $shade." }
            reader.u16() // reserved
            NodeCreatureTriangle(
                material = material,
                shade = shade,
                depth = reader.f32(),
                a = reader.point(),
                b = reader.point(),
                c = reader.point(),
            )
        }

        val edges = List(edgeCount) {
            NodeCreatureEdge(
                depth = reader.f32(),
                weight = reader.f32(),
                from = reader.point(),
                to = reader.point(),
            )
        }

        val terminals = List(terminalCount) { reader.point() }
        require(reader.position == bytes.size) { "Node-creature packet contains trailing bytes." }

        return NodeCreatureRenderPacket(
            version = version,
            triangles = triangles,
            silhouetteEdges = edges,
            terminalAnchors = terminals,
        )
    }

    private fun LittleEndianReader.count(kind: String): Int {
        val value = u32()
        require(value <= NODE_CREATURE_MAX_PACKET_ITEMS.toLong()) {
            "Node-creature $kind count $value exceeds the safety limit $NODE_CREATURE_MAX_PACKET_ITEMS."
        }
        return value.toInt()
    }
}

private class LittleEndianReader(
    private val bytes: ByteArray,
    start: Int,
) {
    var position: Int = start
        private set

    fun u8(): Int {
        requireAvailable(1)
        return bytes[position++].toInt() and 0xFF
    }

    fun u16(): Int {
        requireAvailable(2)
        val result = (bytes[position].toInt() and 0xFF) or
            ((bytes[position + 1].toInt() and 0xFF) shl 8)
        position += 2
        return result
    }

    fun u32(): Long {
        requireAvailable(4)
        val result = (bytes[position].toLong() and 0xFF) or
            ((bytes[position + 1].toLong() and 0xFF) shl 8) or
            ((bytes[position + 2].toLong() and 0xFF) shl 16) or
            ((bytes[position + 3].toLong() and 0xFF) shl 24)
        position += 4
        return result
    }

    fun f32(): Float = Float.fromBits(u32().toInt())

    fun point(): NodeCreaturePoint = NodeCreaturePoint(f32(), f32())

    private fun requireAvailable(count: Int) {
        require(position + count <= bytes.size) { "Node-creature packet ended unexpectedly." }
    }
}
