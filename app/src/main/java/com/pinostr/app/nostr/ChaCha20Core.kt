/* MIT License — Copyright (c) 2025 Vitor Pamplona
 * Adapted from Amethyst (github.com/vitorpamplona/amethyst)
 * Quartz: com.vitorpamplona.quartz.nip44Encryption.crypto.ChaCha20Core
 */
package com.pinostr.app.nostr

/**
 * Pure Kotlin implementation of ChaCha20 and HChaCha20 (RFC 8439).
 * Stateless and thread-safe.
 */
object ChaCha20Core {
    private const val SIGMA0 = 0x61707865
    private const val SIGMA1 = 0x3320646e
    private const val SIGMA2 = 0x79622d32
    private const val SIGMA3 = 0x6b206574

    private fun quarterRound(state: IntArray, a: Int, b: Int, c: Int, d: Int) {
        state[a] += state[b]; state[d] = (state[d] xor state[a]).rotateLeft(16)
        state[c] += state[d]; state[b] = (state[b] xor state[c]).rotateLeft(12)
        state[a] += state[b]; state[d] = (state[d] xor state[a]).rotateLeft(8)
        state[c] += state[d]; state[b] = (state[b] xor state[c]).rotateLeft(7)
    }

    private fun chaCha20Rounds(state: IntArray) {
        repeat(10) {
            quarterRound(state, 0, 4, 8, 12)
            quarterRound(state, 1, 5, 9, 13)
            quarterRound(state, 2, 6, 10, 14)
            quarterRound(state, 3, 7, 11, 15)
            quarterRound(state, 0, 5, 10, 15)
            quarterRound(state, 1, 6, 11, 12)
            quarterRound(state, 2, 7, 8, 13)
            quarterRound(state, 3, 4, 9, 14)
        }
    }

    private fun initState(key: ByteArray, counter: Int, nonce: ByteArray): IntArray {
        val s = IntArray(16)
        s[0] = SIGMA0; s[1] = SIGMA1; s[2] = SIGMA2; s[3] = SIGMA3
        s[4] = littleEndianInt(key, 0); s[5] = littleEndianInt(key, 4)
        s[6] = littleEndianInt(key, 8); s[7] = littleEndianInt(key, 12)
        s[8] = littleEndianInt(key, 16); s[9] = littleEndianInt(key, 20)
        s[10] = littleEndianInt(key, 24); s[11] = littleEndianInt(key, 28)
        s[12] = counter
        s[13] = littleEndianInt(nonce, 0); s[14] = littleEndianInt(nonce, 4)
        s[15] = littleEndianInt(nonce, 8)
        return s
    }

    fun chaCha20Xor(message: ByteArray, key: ByteArray, nonce: ByteArray, counter: Int = 0): ByteArray {
        val out = ByteArray(message.size)
        if (message.isEmpty()) return out
        val full = message.size / 64
        val rem = message.size % 64
        val init = initState(key, counter, nonce)
        val work = IntArray(16)
        for (i in 0 until full) {
            init[12] = counter + i; init.copyInto(work); chaCha20Rounds(work)
            val off = i * 64
            for (j in 0..15) {
                val ks = work[j] + init[j]
                val mw = littleEndianInt(message, off + j * 4)
                (ks xor mw).writeLittleEndian(out, off + j * 4)
            }
        }
        if (rem > 0) {
            init[12] = counter + full; init.copyInto(work); chaCha20Rounds(work)
            val off = full * 64
            val fw = rem / 4
            for (j in 0 until fw) {
                val ks = work[j] + init[j]
                val mw = littleEndianInt(message, off + j * 4)
                (ks xor mw).writeLittleEndian(out, off + j * 4)
            }
            val tail = fw * 4
            if (tail < rem) {
                val ks = work[fw] + init[fw]
                for (b in tail until rem) {
                    out[off + b] = (message[off + b].toInt() xor ((ks ushr ((b - tail) * 8)) and 0xFF)).toByte()
                }
            }
        }
        return out
    }
}

// --- Little-endian helpers ---

private fun littleEndianInt(bytes: ByteArray, offset: Int): Int =
    (bytes[offset].toInt() and 0xFF) or
    ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
    ((bytes[offset + 2].toInt() and 0xFF) shl 16) or
    ((bytes[offset + 3].toInt() and 0xFF) shl 24)

private fun Int.writeLittleEndian(output: ByteArray, offset: Int) {
    output[offset] = (this and 0xFF).toByte()
    output[offset + 1] = (this ushr 8 and 0xFF).toByte()
    output[offset + 2] = (this ushr 16 and 0xFF).toByte()
    output[offset + 3] = (this ushr 24 and 0xFF).toByte()
}
