/* Portions based on Amethyst (MIT) github.com/vitorpamplona/amethyst */
package com.pinostr.app.nostr

import android.util.Base64
import fr.acinq.secp256k1.Secp256k1
import fr.acinq.secp256k1.jni.NativeSecp256k1AndroidLoader
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * NIP-44 encryption/decryption (v2).
 *
 * Uses libsecp256k1 ECDH + HKDF + ChaCha20 + HMAC-SHA256.
 * Compatible with nostr-tools' nip44 implementation.
 */
object Nip44 {

    private const val VERSION = 2
    private const val MIN_PT = 1
    private const val MAX_PT = 65535
    private val HKDF_SALT = "nip44-v2".encodeToByteArray()
    private val secp: Secp256k1 by lazy { NativeSecp256k1AndroidLoader.load() }

    /** Derive 32-byte conversation key from two keypairs. */
    fun getConversationKey(privkeyHex: String, pubkeyHex: String): ByteArray {
        val priv = hexToBytes(privkeyHex)
        // Bridge pubkey may be 32-byte x-only (64 hex chars).
        // secp.ecdh() requires 33-byte compressed key (02/03 + x).
        val pubHex = if (pubkeyHex.length == 64) "02$pubkeyHex" else pubkeyHex
        val pub = hexToBytes(pubHex)
        // ECDH returns 32-byte x coordinate directly (no prefix to strip)
        val sharedX = secp.ecdh(priv, pub)
        return hkdfExtract(HKDF_SALT, sharedX)
    }

    /** Encrypt plaintext with conversation key. Returns base64 payload. */
    fun encrypt(plaintext: String, conversationKey: ByteArray): String {
        val nonce = SecureRandom().generateSeed(32)
        val keys = hkdfExpand(conversationKey, nonce, 76)
        val chachaKey = keys.copyOfRange(0, 32)
        val chachaNonce = keys.copyOfRange(32, 44)
        val hmacKey = keys.copyOfRange(44, 76)

        val padded = pad(plaintext)
        val ciphertext = ChaCha20Core.chaCha20Xor(padded, chachaKey, chachaNonce)
        val mac = hmacSha256(hmacKey, nonce + ciphertext)

        val payload = byteArrayOf(VERSION.toByte()) + nonce + ciphertext + mac
        return Base64.encodeToString(payload, Base64.NO_WRAP)
    }

    /** Decrypt a base64 NIP-44 payload. */
    fun decrypt(payload: String, conversationKey: ByteArray): String {
        val data = Base64.decode(payload, Base64.NO_WRAP)
        if (data.size < 99) throw IllegalArgumentException("payload too short")

        val version = data[0].toInt() and 0xFF
        if (version != VERSION) throw IllegalArgumentException("unknown version: $version")

        val nonce = data.copyOfRange(1, 33)
        val ciphertext = data.copyOfRange(33, data.size - 32)
        val mac = data.copyOfRange(data.size - 32, data.size)

        val keys = hkdfExpand(conversationKey, nonce, 76)
        val chachaKey = keys.copyOfRange(0, 32)
        val chachaNonce = keys.copyOfRange(32, 44)
        val hmacKey = keys.copyOfRange(44, 76)

        val calculatedMac = hmacSha256(hmacKey, nonce + ciphertext)
        if (!calculatedMac.contentEquals(mac)) throw SecurityException("MAC mismatch")

        val padded = ChaCha20Core.chaCha20Xor(ciphertext, chachaKey, chachaNonce)
        return unpad(padded)
    }

    // ── Private ──

    private fun hkdfExtract(salt: ByteArray, ikm: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(salt, "HmacSHA256"))
        return mac.doFinal(ikm)
    }

    private fun hkdfExpand(prk: ByteArray, info: ByteArray, length: Int): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(prk, "HmacSHA256"))
        val result = ByteArray(length)
        var t = ByteArray(0)
        var remaining = length
        var block = 1
        while (remaining > 0) {
            mac.reset(); mac.update(t); mac.update(info); mac.update(block.toByte())
            t = mac.doFinal()
            val copy = minOf(t.size, remaining)
            System.arraycopy(t, 0, result, length - remaining, copy)
            remaining -= copy; block++
        }
        return result
    }

    private fun pad(text: String): ByteArray {
        val utf8 = text.toByteArray(Charsets.UTF_8)
        val len = utf8.size
        if (len < MIN_PT || len > MAX_PT) throw IllegalArgumentException("plaintext size $len out of range")
        val paddedLen = calcPaddedLen(len)
        val result = ByteArray(2 + paddedLen)
        result[0] = (len shr 8).toByte(); result[1] = len.toByte()
        System.arraycopy(utf8, 0, result, 2, len)
        return result
    }

    private fun unpad(padded: ByteArray): String {
        if (padded.size < 2) throw IllegalArgumentException("too short")
        val len = ((padded[0].toInt() and 0xFF) shl 8) or (padded[1].toInt() and 0xFF)
        if (len < MIN_PT || len > MAX_PT) throw IllegalArgumentException("invalid length: $len")
        val expected = 2 + calcPaddedLen(len)
        if (padded.size != expected) throw IllegalArgumentException("invalid padding")
        return padded.copyOfRange(2, 2 + len).toString(Charsets.UTF_8)
    }

    private fun calcPaddedLen(len: Int): Int {
        if (len <= 32) return 32
        val next = 1 shl (32 - Integer.numberOfLeadingZeros(len - 1))
        val chunk = if (next <= 256) 32 else next / 8
        return chunk * ((len - 1) / chunk + 1)
    }

    private fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(data)
    }

    private fun hexToBytes(hex: String): ByteArray {
        val len = hex.length / 2
        val bytes = ByteArray(len)
        for (i in 0 until len) bytes[i] = ((Character.digit(hex[i * 2], 16) shl 4) or Character.digit(hex[i * 2 + 1], 16)).toByte()
        return bytes
    }

    private operator fun ByteArray.plus(other: ByteArray): ByteArray {
        val r = this.copyOf(this.size + other.size)
        System.arraycopy(other, 0, r, this.size, other.size)
        return r
    }
}
