package com.shilapi.xcertplay.transport

/**
 * Minimal NTB16 codec for the iPhone NCM data link.
 *
 * Only the 16-bit transfer-block layout used by LIVI's `iap2-usbmux/src/ntb.rs` is implemented:
 * one NTH16 header, one NDP16 table with one datagram, and the USB short-packet pad byte. There
 * is no NTB32, alignment, CRC, or NCM control-plane handling here.
 */
object Ntb16Codec {
    const val NTH16_SIG = 0x484d434e
    const val NDP16_SIG = 0x304d434e

    private const val NTH_LENGTH = 12
    private const val NDP_LENGTH = 16
    private const val DATAGRAM_INDEX = 28
    private const val USB_PACKET_SIZE = 512

    /** Wraps one Ethernet frame in one NTB16 block, padding an exact 512-byte boundary. */
    fun build(frame: ByteArray, sequence: Int): ByteArray {
        require(frame.isNotEmpty()) { "frame must not be empty" }
        require(frame.size <= MAX_DATAGRAM_BYTES) { "frame exceeds the NTB16 length field" }
        require(sequence in 0..0xffff) { "sequence must fit in u16" }

        val blockLength = DATAGRAM_INDEX + frame.size
        val block = ByteArray(blockLength)
        putU32(block, 0, NTH16_SIG)
        putU16(block, 4, NTH_LENGTH)
        putU16(block, 6, sequence)
        putU16(block, 8, blockLength)
        putU16(block, 10, NTH_LENGTH)

        putU32(block, 12, NDP16_SIG)
        putU16(block, 16, NDP_LENGTH)
        putU16(block, 18, 0)
        putU16(block, 20, DATAGRAM_INDEX)
        putU16(block, 22, frame.size)
        putU16(block, 24, 0)
        putU16(block, 26, 0)

        frame.copyInto(block, DATAGRAM_INDEX)
        // A transfer that ends exactly on a USB packet boundary is read as a short packet.
        return if (block.size % USB_PACKET_SIZE == 0) block + byteArrayOf(0) else block
    }

    /**
     * Extracts the Ethernet frames carried by one NTB16 block.
     *
     * Wire garbage returns an empty list instead of throwing; malformed entries are skipped where
     * a bounds check fails. A step guard stops a malformed NDP pointer chain from cycling.
     */
    fun parse(block: ByteArray): List<ByteArray> = parse(block, 0, block.size)

    /** Parses one NTB16 block from [offset] without copying the containing buffer. */
    fun parse(block: ByteArray, offset: Int, length: Int): List<ByteArray> {
        require(offset >= 0 && length >= 0 && offset <= block.size - length) {
            "NTB16 range is outside the source buffer"
        }
        val end = offset + length
        if (length < NTH_LENGTH || readU32(block, offset) != NTH16_SIG) return emptyList()

        // Validate descriptors before copying any payload. Most blocks have one table/frame;
        // a tiny primitive descriptor array avoids boxed ranges and grows only when needed.
        var ranges = IntArray(8)
        var count = 0
        var totalBytes = 0
        var visited = IntArray(4)
        var visitedCount = 0
        var relativeNdp = readU16(block, offset + 10)
        while (relativeNdp != 0) {
            if (relativeNdp < NTH_LENGTH || relativeNdp > length - 12) return emptyList()
            for (i in 0 until visitedCount) if (visited[i] == relativeNdp) return emptyList()
            if (visitedCount >= MAX_DATAGRAMS) return emptyList()
            if (visitedCount == visited.size) visited = visited.copyOf(visited.size * 2)
            visited[visitedCount++] = relativeNdp
            val ndpOffset = offset + relativeNdp
            if (readU32(block, ndpOffset) and 0x00ffffff != NDP16_SIG and 0x00ffffff) return emptyList()
            val ndpLength = readU16(block, ndpOffset + 4)
            if (ndpLength < 12 || ndpLength > end - ndpOffset || ndpLength % 4 != 0) return emptyList()
            var entry = ndpOffset + 8
            val ndpEnd = ndpOffset + ndpLength
            while (entry + 4 <= ndpEnd) {
                val index = readU16(block, entry)
                val size = readU16(block, entry + 2)
                if (index == 0 && size == 0) break
                if (index < NTH_LENGTH || size == 0 || index > length - size) return emptyList()
                if (count >= MAX_DATAGRAMS || size > MAX_OUTPUT_BYTES - totalBytes) return emptyList()
                // A datagram cannot alias another datagram and multiply a small wire buffer.
                for (i in 0 until count) {
                    val previous = ranges[i * 2]
                    if (index < previous + ranges[i * 2 + 1] && previous < index + size) return emptyList()
                }
                if (count * 2 == ranges.size) ranges = ranges.copyOf(ranges.size * 2)
                ranges[count * 2] = index; ranges[count * 2 + 1] = size
                count++; totalBytes += size
                entry += 4
            }
            relativeNdp = readU16(block, ndpOffset + 6)
        }
        val datagrams = ArrayList<ByteArray>(count)
        for (i in 0 until count) {
            val begin = offset + ranges[i * 2]
            datagrams.add(block.copyOfRange(begin, begin + ranges[i * 2 + 1]))
        }
        return datagrams
    }

    private fun putU16(target: ByteArray, offset: Int, value: Int) {
        target[offset] = value.toByte()
        target[offset + 1] = (value ushr 8).toByte()
    }

    private fun putU32(target: ByteArray, offset: Int, value: Int) {
        target[offset] = value.toByte()
        target[offset + 1] = (value ushr 8).toByte()
        target[offset + 2] = (value ushr 16).toByte()
        target[offset + 3] = (value ushr 24).toByte()
    }

    private fun readU16(source: ByteArray, offset: Int): Int =
        (source[offset].toInt() and 0xff) or ((source[offset + 1].toInt() and 0xff) shl 8)

    private fun readU32(source: ByteArray, offset: Int): Int =
        (source[offset].toInt() and 0xff) or
            ((source[offset + 1].toInt() and 0xff) shl 8) or
            ((source[offset + 2].toInt() and 0xff) shl 16) or
            ((source[offset + 3].toInt() and 0xff) shl 24)

    private const val MAX_DATAGRAMS = 256
    private const val MAX_OUTPUT_BYTES = 1024 * 1024
    private const val MAX_DATAGRAM_BYTES = 0xffff - DATAGRAM_INDEX
}
