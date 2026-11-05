package com.ahif.lumen.data.tools.encoders

import java.io.OutputStream

internal fun interface CodeSink {
    fun put(code: Int, width: Int)
}

/**
 * LZW as used by GIF and TIFF. The two differ in when the code width grows: TIFF switches one
 * code early ("early change") and restarts at 4094 entries, GIF grows late and restarts at 4096.
 * Every stream starts with a Clear code and ends with End-of-Information.
 */
internal class LzwEncoder(private val rootBits: Int, private val tiff: Boolean, private val sink: CodeSink) {
    private val clearCode = 1 shl rootBits
    private val eoiCode = clearCode + 1
    private val firstFree = clearCode + 2
    private val keys = IntArray(TABLE_SIZE)
    private val values = IntArray(TABLE_SIZE)
    private var next = firstFree
    private var width = rootBits + 1
    private var prefix = -1

    init {
        reset()
        sink.put(clearCode, width)
    }

    private fun reset() {
        keys.fill(-1)
        next = firstFree
        width = rootBits + 1
    }

    fun write(symbol: Int) {
        val p = prefix
        if (p < 0) {
            prefix = symbol
            return
        }
        val key = (p shl 8) or symbol
        var slot = (key * HASH_MUL) ushr (32 - TABLE_BITS)
        while (true) {
            val k = keys[slot]
            if (k == key) {
                prefix = values[slot]
                return
            }
            if (k == -1) break
            slot = (slot + 1) and (TABLE_SIZE - 1)
        }
        sink.put(p, width)
        if (tiff) {
            keys[slot] = key
            values[slot] = next++
            if (next == TIFF_LIMIT) {
                sink.put(clearCode, width)
                reset()
            } else if (next == 1 shl width) {
                width++
            }
        } else {
            if (width < MAX_BITS && next == 1 shl width) width++
            if (next < GIF_LIMIT) {
                keys[slot] = key
                values[slot] = next++
            } else {
                sink.put(clearCode, width)
                reset()
            }
        }
        prefix = symbol
    }

    fun finish() {
        val p = prefix
        if (p >= 0) {
            sink.put(p, width)
            if (tiff) {
                val n = next + 1
                if (n == TIFF_LIMIT) {
                    sink.put(clearCode, width)
                    width = rootBits + 1
                } else if (n == 1 shl width) {
                    width++
                }
            } else if (width < MAX_BITS && next == 1 shl width) {
                width++
            }
        }
        sink.put(eoiCode, width)
        prefix = -1
    }

    private companion object {
        const val MAX_BITS = 12
        const val GIF_LIMIT = 4096
        const val TIFF_LIMIT = 4094
        const val TABLE_BITS = 13
        const val TABLE_SIZE = 1 shl TABLE_BITS
        const val HASH_MUL = -0x61c88647 // 0x9E3779B9, Fibonacci hashing
    }
}

/** GIF: codes packed least-significant bit first, written as ≤255-byte data sub-blocks. */
internal class GifBlockSink(private val out: OutputStream) : CodeSink {
    private var acc = 0
    private var bits = 0
    private val block = ByteArray(255)
    private var count = 0

    override fun put(code: Int, width: Int) {
        acc = acc or (code shl bits)
        bits += width
        while (bits >= 8) {
            byte(acc and 0xFF)
            acc = acc ushr 8
            bits -= 8
        }
    }

    private fun byte(b: Int) {
        block[count++] = b.toByte()
        if (count == block.size) flushBlock()
    }

    private fun flushBlock() {
        if (count > 0) {
            out.write(count)
            out.write(block, 0, count)
            count = 0
        }
    }

    /** Flushes the last partial byte and writes the zero-length block terminator. */
    fun close() {
        if (bits > 0) byte(acc and 0xFF)
        acc = 0
        bits = 0
        flushBlock()
        out.write(0)
    }
}

/** TIFF: codes packed most-significant bit first. */
internal class MsbFirstSink(private val out: OutputStream) : CodeSink {
    private var acc = 0
    private var bits = 0

    override fun put(code: Int, width: Int) {
        acc = (acc shl width) or code
        bits += width
        while (bits >= 8) {
            bits -= 8
            out.write((acc ushr bits) and 0xFF)
        }
        acc = acc and ((1 shl bits) - 1)
    }

    fun close() {
        if (bits > 0) out.write((acc shl (8 - bits)) and 0xFF)
        acc = 0
        bits = 0
    }
}
