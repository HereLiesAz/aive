package com.hereliesaz.geministrator.memory

/*
 * Compact lookup structures for the language resources, so WordNet and the tagger cost a few MB of
 * heap rather than tens: keys are stored as 64-bit FNV-1a hashes in open-addressing tables (a
 * false hit needs a 64-bit collision), strings live in one ASCII byte blob, and lists are slices
 * of one flat IntArray.
 */

internal fun fnv64(value: String): Long {
    var hash = -0x340d631b7bdddcdbL // FNV-1a offset basis 0xcbf29ce484222325
    for (char in value) {
        hash = hash xor char.code.toLong()
        hash *= 0x100000001b3L
    }
    // 0 marks an empty slot.
    return if (hash == 0L) 1L else hash
}

/** Open-addressing map from 64-bit key hash to a non-negative Int. */
internal class LongIntTable(expected: Int) {
    private val capacity = Integer.highestPowerOfTwoAbove(expected + expected / 3)
    private val keys = LongArray(capacity)
    private val values = IntArray(capacity)
    var size: Int = 0
        private set

    fun put(key: Long, value: Int) {
        var slot = (key xor (key ushr 32)).toInt() and (capacity - 1)
        while (keys[slot] != 0L && keys[slot] != key) slot = (slot + 1) and (capacity - 1)
        if (keys[slot] == 0L) size++
        keys[slot] = key
        values[slot] = value
    }

    /** The value for [key], or -1. */
    fun get(key: Long): Int {
        var slot = (key xor (key ushr 32)).toInt() and (capacity - 1)
        while (true) {
            val stored = keys[slot]
            if (stored == 0L) return -1
            if (stored == key) return values[slot]
            slot = (slot + 1) and (capacity - 1)
        }
    }

    private object Integer {
        fun highestPowerOfTwoAbove(value: Int): Int {
            var power = 16
            while (power < value) power = power shl 1
            return power
        }
    }
}

/** Growable IntArray whose slices are addressed by (start, end) offsets. */
internal class IntSlices {
    private var data = IntArray(1024)
    private var size = 0
    private var offsets = IntArray(1024)
    private var count = 0

    /** Appends one list; returns its index. */
    fun add(values: IntArray): Int {
        if (size + values.size > data.size) data = data.copyOf(maxOf(data.size * 2, size + values.size))
        values.copyInto(data, size)
        size += values.size
        if (count + 2 > offsets.size) offsets = offsets.copyOf(offsets.size * 2)
        offsets[count] = size // end of this slice; start is the previous end
        count++
        return count - 1
    }

    fun get(index: Int): IntArray {
        val start = if (index == 0) 0 else offsets[index - 1]
        return data.copyOfRange(start, offsets[index])
    }

    fun trim(): IntSlices = apply {
        data = data.copyOf(size)
        offsets = offsets.copyOf(count)
    }
}

/** ASCII strings in one byte array. */
internal class StringBlob {
    private var data = ByteArray(1 shl 16)
    private var size = 0
    private var ends = IntArray(1024)
    private var count = 0

    fun add(value: String): Int {
        if (size + value.length > data.size) data = data.copyOf(maxOf(data.size * 2, size + value.length))
        for (char in value) data[size++] = char.code.toByte()
        if (count + 1 > ends.size) ends = ends.copyOf(ends.size * 2)
        ends[count++] = size
        return count - 1
    }

    fun get(index: Int): String {
        val start = if (index == 0) 0 else ends[index - 1]
        return data.decodeToString(start, ends[index])
    }

    fun trim(): StringBlob = apply {
        data = data.copyOf(size)
        ends = ends.copyOf(count)
    }
}
