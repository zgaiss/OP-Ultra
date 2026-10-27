package com.shilapi.xcertplay.airplay

/**
 * Minimal Apple binary property list (bplist00) codec for the CarPlay control channel.
 *
 * It covers the subset the stack emits and reads: dictionaries, arrays, ASCII and UTF-16
 * strings, raw data, non-negative integers, 32/64-bit reals, and booleans. Dictionary keys are
 * serialized in insertion order, matching the reference implementation this stack targets.
 */
object BplistCodec {
    private val magic = "bplist00".toByteArray(Charsets.US_ASCII)

    fun decode(bytes: ByteArray): Any? {
        require(bytes.size >= 40) { "bplist: too short" }
        require(bytes.copyOfRange(0, magic.size).contentEquals(magic)) { "bplist: bad magic" }

        require(bytes.size <= MAX_BYTES) { "bplist: byte limit exceeded" }
        return Decoder(bytes).decode()
    }

    fun encode(root: Any?): ByteArray {
        val nodes = ArrayList<Node>()

        fun add(value: Any?): Int {
            val index = nodes.size
            nodes.add(Leaf(ByteArray(0)))
            when (value) {
                is Boolean -> nodes[index] = Leaf(byteArrayOf(if (value) 0x09 else 0x08))
                is java.math.BigInteger -> {
                    require(value.signum() >= 0) { "bplist: negative integers are not supported" }
                    require(value.bitLength() <= 64) { "bplist: integer exceeds 64 bits" }
                    nodes[index] = Leaf(encodeUnsignedInt(value))
                }
                is Byte, is Short, is Int, is Long -> {
                    val number = (value as Number).toLong()
                    nodes[index] = if (number >= 0) {
                        Leaf(encodeInt(number))
                    } else {
                        Leaf(byteArrayOf(0x23) + bigEndian(number.toDouble().toRawBits(), 8))
                    }
                }
                is Float, is Double -> {
                    val number = (value as Number).toDouble()
                    nodes[index] = Leaf(byteArrayOf(0x23) + bigEndian(number.toRawBits(), 8))
                }
                is String -> nodes[index] = Leaf(encodeString(value))
                is ByteArray -> nodes[index] = Leaf(marker(0x4, value.size) + value)
                is List<*> -> {
                    val refs = value.map { add(it) }.toIntArray()
                    nodes[index] = Container(marker(0xa, value.size), refs)
                }
                is Map<*, *> -> {
                    val entries = value.entries.toList()
                    val keyRefs = entries.map { add(it.key.toString()) }.toIntArray()
                    val valueRefs = entries.map { add(it.value) }.toIntArray()
                    nodes[index] = Container(marker(0xd, entries.size), keyRefs + valueRefs)
                }
                else -> throw IllegalArgumentException("bplist: unsupported value $value")
            }
            return index
        }

        val topIndex = add(root)
        val refSize = when {
            nodes.size > 0xffff -> 4
            nodes.size > 0xff -> 2
            else -> 1
        }

        val parts = ArrayList<ByteArray>(nodes.size + 2)
        parts.add(magic)
        var cursor = magic.size
        val offsets = ArrayList<Int>(nodes.size)
        for (node in nodes) {
            offsets.add(cursor)
            val serialized = when (node) {
                is Leaf -> node.body
                is Container -> node.head + refBytes(node.refs, refSize)
            }
            parts.add(serialized)
            cursor += serialized.size
        }

        val offsetTableOffset = cursor
        val offsetSize = when {
            cursor > 0xffff -> 4
            cursor > 0xff -> 2
            else -> 1
        }
        for (offset in offsets) parts.add(bigEndian(offset.toLong(), offsetSize))

        val trailer = ByteArray(32)
        trailer[6] = offsetSize.toByte()
        trailer[7] = refSize.toByte()
        writeBigEndian(trailer, 8, nodes.size.toLong(), 8)
        writeBigEndian(trailer, 16, topIndex.toLong(), 8)
        writeBigEndian(trailer, 24, offsetTableOffset.toLong(), 8)
        parts.add(trailer)
        return concatBytes(*parts.toTypedArray())
    }

    private const val MAX_BYTES = 16 * 1024 * 1024
    private const val MAX_OBJECTS = 65_536
    private const val MAX_DEPTH = 64
    private const val MAX_REFERENCES = 262_144

    /** One bounded decoder per document; cached references avoid exponential graph expansion. */
    private class Decoder(private val bytes: ByteArray) {
        private val trailer = bytes.size - 32
        private val offsetSize = bytes[trailer + 6].toInt() and 255
        private val refSize = bytes[trailer + 7].toInt() and 255
        private val objectCount = readBigEndianLong(bytes, (trailer + 8).toLong(), 8)
        private val topObject = readBigEndianLong(bytes, (trailer + 16).toLong(), 8)
        private val table = readBigEndianLong(bytes, (trailer + 24).toLong(), 8)
        private lateinit var offsets: LongArray
        private lateinit var state: ByteArray
        private lateinit var values: Array<Any?>
        private var remainingReferences = MAX_REFERENCES

        fun decode(): Any? {
            require(offsetSize in 1..8 && refSize in 1..8) { "bplist: invalid integer width" }
            require(objectCount in 1..MAX_OBJECTS.toLong()) { "bplist: object count limit" }
            require(topObject in 0 until objectCount) { "bplist: invalid root reference" }
            require(table >= 8 && table <= trailer && objectCount <= (trailer - table) / offsetSize) {
                "bplist: invalid offset table"
            }
            offsets = LongArray(objectCount.toInt()) { index ->
                readBigEndianLong(bytes, table + index.toLong() * offsetSize, offsetSize).also {
                    require(it >= 8 && it < table) { "bplist: invalid object offset" }
                }
            }
            state = ByteArray(offsets.size)
            values = arrayOfNulls(offsets.size)
            return read(topObject.toInt(), 0)
        }

        private fun read(index: Int, depth: Int): Any? {
            require(index in offsets.indices && depth <= MAX_DEPTH) { "bplist: reference/depth limit" }
            require(state[index] != 1.toByte()) { "bplist: cyclic reference" }
            if (state[index] == 2.toByte()) return values[index]
            state[index] = 1
            var position = offsets[index].toInt()
            val marker = bytes[position++].toInt() and 255
            val type = marker ushr 4
            val nibble = marker and 15
            fun span(count: Long) {
                require(count >= 0 && position.toLong() <= table && count <= table - position) {
                    "bplist: truncated object"
                }
            }
            fun count(): Int {
                if (nibble != 15) return nibble
                span(1)
                val sizeMarker = bytes[position++].toInt() and 255
                require(sizeMarker ushr 4 == 1 && sizeMarker and 15 <= 3) { "bplist: invalid size marker" }
                val width = 1 shl (sizeMarker and 15)
                span(width.toLong())
                val value = readBigEndianLong(bytes, position.toLong(), width)
                position += width
                require(value in 0..Int.MAX_VALUE.toLong()) { "bplist: count limit" }
                return value.toInt()
            }
            fun reference(offset: Long): Int {
                val ref = readBigEndianLong(bytes, offset, refSize)
                require(ref in 0 until objectCount) { "bplist: invalid reference" }
                return ref.toInt()
            }
            val result: Any? = when (type) {
                0 -> when (nibble) {
                    8 -> false
                    9 -> true
                    else -> throw IllegalArgumentException("bplist: unsupported primitive")
                }
                1, 2 -> {
                    require(nibble <= 3) { "bplist: invalid number width" }
                    val width = 1 shl nibble
                    span(width.toLong())
                    val bits = readBigEndianLong(bytes, position.toLong(), width)
                    if (type == 1) bits else when (width) {
                        4 -> Float.fromBits(bits.toInt())
                        8 -> Double.fromBits(bits)
                        else -> throw IllegalArgumentException("bplist: invalid real width")
                    }
                }
                4, 5, 6 -> {
                    val count = count()
                    val length = count.toLong() * if (type == 6) 2 else 1
                    span(length)
                    when (type) {
                        4 -> bytes.copyOfRange(position, position + length.toInt())
                        5 -> String(bytes, position, length.toInt(), Charsets.US_ASCII)
                        else -> String(bytes, position, length.toInt(), Charsets.UTF_16BE)
                    }
                }
                10, 13 -> {
                    val count = count()
                    val refs = count.toLong() * if (type == 13) 2 else 1
                    require(refs <= remainingReferences) { "bplist: reference budget exceeded" }
                    remainingReferences -= refs.toInt()
                    span(refs * refSize)
                    if (type == 10) {
                        ArrayList<Any?>(count).apply {
                            repeat(count) { add(read(reference(position + it.toLong() * refSize), depth + 1)) }
                        }
                    } else {
                        LinkedHashMap<String, Any?>(count).apply {
                            repeat(count) { i ->
                                val key = read(reference(position + i.toLong() * refSize), depth + 1)
                                val value = read(reference(position + (count + i).toLong() * refSize), depth + 1)
                                put(key.toString(), value)
                            }
                        }
                    }
                }
                else -> throw IllegalArgumentException("bplist: unsupported object type $type")
            }
            values[index] = result
            state[index] = 2
            return result
        }
    }

    private fun encodeString(value: String): ByteArray {
        val ascii = value.all { it.code <= 0x7f }
        return if (ascii) {
            marker(0x5, value.length) + value.toByteArray(Charsets.US_ASCII)
        } else {
            marker(0x6, value.length) + value.toByteArray(Charsets.UTF_16BE)
        }
    }

    private fun encodeInt(number: Long): ByteArray {
        val size = when {
            number > 0xffffffffL -> 8
            number > 0xffffL -> 4
            number > 0xffL -> 2
            else -> 1
        }
        val log = when (size) {
            8 -> 3
            4 -> 2
            2 -> 1
            else -> 0
        }
        return byteArrayOf((0x10 or log).toByte()) + bigEndian(number, size)
    }

    private fun encodeUnsignedInt(number: java.math.BigInteger): ByteArray {
        val size = when {
            number.bitLength() > 32 -> 8
            number.bitLength() > 16 -> 4
            number.bitLength() > 8 -> 2
            else -> 1
        }
        val log = when (size) {
            8 -> 3
            4 -> 2
            2 -> 1
            else -> 0
        }
        val raw = number.toByteArray()
        return byteArrayOf((0x10 or log).toByte()) + raw.copyOfRange(raw.size - size, raw.size)
    }

    private fun marker(type: Int, count: Int): ByteArray {
        if (count < 0x0f) return byteArrayOf(((type shl 4) or count).toByte())
        val size = when {
            count > 0xffff -> 4
            count > 0xff -> 2
            else -> 1
        }
        val log = when (size) {
            4 -> 2
            2 -> 1
            else -> 0
        }
        return byteArrayOf(((type shl 4) or 0x0f).toByte(), (0x10 or log).toByte()) +
            bigEndian(count.toLong(), size)
    }

    private fun refBytes(refs: IntArray, refSize: Int): ByteArray {
        val output = ByteArray(refs.size * refSize)
        for (index in refs.indices) writeBigEndian(output, index * refSize, refs[index].toLong(), refSize)
        return output
    }

    private fun bigEndian(value: Long, size: Int): ByteArray {
        val output = ByteArray(size)
        writeBigEndian(output, 0, value, size)
        return output
    }

    private fun writeBigEndian(target: ByteArray, offset: Int, value: Long, size: Int) {
        var current = value
        for (index in size - 1 downTo 0) {
            target[offset + index] = (current and 0xff).toByte()
            current = current ushr 8
        }
    }

    private fun readBigEndianLong(bytes: ByteArray, offset: Long, size: Int): Long {
        require(size in 1..8 && offset >= 0 && offset <= bytes.size.toLong() - size) { "bplist: truncated integer" }
        var value = 0L
        val base = offset.toInt()
        for (index in 0 until size) {
            value = (value shl 8) or (bytes[base + index].toLong() and 0xffL)
        }
        return value
    }

    private sealed class Node
    private class Leaf(val body: ByteArray) : Node()
    private class Container(val head: ByteArray, val refs: IntArray) : Node()
}
