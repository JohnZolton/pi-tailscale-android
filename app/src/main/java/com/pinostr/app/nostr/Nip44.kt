package com.pinostr.app.nostr

import android.util.Base64
import org.bouncycastle.crypto.AsymmetricCipherKeyPair
import org.bouncycastle.crypto.agreement.ECDHBasicAgreement
import org.bouncycastle.crypto.engines.ChaCha7539Engine
import org.bouncycastle.crypto.generators.ECKeyPairGenerator
import org.bouncycastle.crypto.params.*
import org.bouncycastle.crypto.params.ECDomainParameters
import org.bouncycastle.crypto.params.ECPrivateKeyParameters
import org.bouncycastle.crypto.params.ECPublicKeyParameters
import org.bouncycastle.crypto.signers.ECDSASigner
import org.bouncycastle.math.ec.ECPoint
import java.math.BigInteger
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * NIP-44 encryption/decryption for Nostr DMs.
 *
 * Uses ECDH + HKDF + ChaCha20-Poly1305 + HMAC-SHA256.
 * Compatible with nostr-tools' nip44 implementation.
 */
object Nip44 {

    // secp256k1 curve parameters (Bouncy Castle lightweight API)
    private val CURVE by lazy {
        org.bouncycastle.asn1.sec.SECNamedCurves.getByName("secp256k1")
    }
    private val DOMAIN by lazy {
        ECDomainParameters(CURVE.curve, CURVE.g, CURVE.n, CURVE.h)
    }

    /** Minimum and maximum plaintext sizes per NIP-44 spec. */
    private const val MIN_PLAINTEXT = 1
    private const val MAX_PLAINTEXT = 65535

    /** Version byte for NIP-44 v2. */
    private const val VERSION = 2

    /** HKDF salt string. */
    private val HKDF_SALT = "nip44-v2".toByteArray(Charsets.UTF_8)

    /**
     * Derive a 32-byte conversation key from two keypairs.
     * @param privkeyAHex hex-encoded private key
     * @param pubkeyBHex hex-encoded public key (compressed)
     */
    fun getConversationKey(privkeyAHex: String, pubkeyBHex: String): ByteArray {
        // ECDH: shared secret's x coordinate (bytes 1..32)
        val sharedX = ecdhSharedX(privkeyAHex, pubkeyBHex)
        // HKDF_extract(salt="nip44-v2", ikm=sharedX)
        return hkdfExtract(HKDF_SALT, sharedX)
    }

    /**
     * Encrypt plaintext with a conversation key.
     * @return base64-encoded payload: version(1) || nonce(32) || ciphertext || mac(32)
     */
    fun encrypt(plaintext: String, conversationKey: ByteArray): String {
        val nonce = SecureRandom().generateSeed(32) // 32 random bytes

        // Derive message keys: chacha_key(32) || chacha_nonce(12) || hmac_key(32)
        val keys = hkdfExpand(conversationKey, nonce, 76)
        val chachaKey = keys.copyOfRange(0, 32)
        val chachaNonce = keys.copyOfRange(32, 44)
        val hmacKey = keys.copyOfRange(44, 76)

        // Pad plaintext
        val padded = pad(plaintext)

        // ChaCha20 encrypt
        val ciphertext = chacha20Encrypt(chachaKey, chachaNonce, padded)

        // HMAC-SHA256 over nonce || ciphertext
        val macData = nonce + ciphertext
        val mac = hmacSha256(hmacKey, macData)

        // Assemble: version(1) || nonce(32) || ciphertext || mac(32)
        val payload = byteArrayOf(VERSION.toByte()) + nonce + ciphertext + mac
        return Base64.encodeToString(payload, Base64.NO_WRAP)
    }

    /**
     * Decrypt a NIP-44 payload.
     * @param payload base64-encoded payload string
     * @param conversationKey 32-byte conversation key
     * @return decrypted plaintext string
     */
    fun decrypt(payload: String, conversationKey: ByteArray): String {
        val data = Base64.decode(payload, Base64.NO_WRAP)

        // Validate minimum length
        if (data.size < 99) throw IllegalArgumentException("payload too short: ${data.size}")

        // Parse version
        val version = data[0].toInt() and 0xFF
        if (version != VERSION) throw IllegalArgumentException("unknown version: $version")

        // Parse fields
        val nonce = data.copyOfRange(1, 33)           // bytes 1..32
        val ciphertext = data.copyOfRange(33, data.size - 32)
        val mac = data.copyOfRange(data.size - 32, data.size)

        // Derive message keys
        val keys = hkdfExpand(conversationKey, nonce, 76)
        val chachaKey = keys.copyOfRange(0, 32)
        val chachaNonce = keys.copyOfRange(32, 44)
        val hmacKey = keys.copyOfRange(44, 76)

        // Verify HMAC
        val macData = nonce + ciphertext
        val calculatedMac = hmacSha256(hmacKey, macData)
        if (!calculatedMac.contentEquals(mac)) {
            throw SecurityException("MAC verification failed")
        }

        // ChaCha20 decrypt
        val padded = chacha20Encrypt(chachaKey, chachaNonce, ciphertext)

        // Unpad
        return unpad(padded)
    }

    // ── Private helpers ──

    /** ECDH shared secret x-coordinate (32 bytes) using BC lightweight API. */
    private fun ecdhSharedX(privkeyHex: String, pubkeyHex: String): ByteArray {
        val d = BigInteger(privkeyHex, 16)
        val privParams = ECPrivateKeyParameters(d, DOMAIN)

        val point = CURVE.curve.decodePoint(hexToBytes(pubkeyHex))
        val pubParams = ECPublicKeyParameters(point, DOMAIN)

        val agreement = ECDHBasicAgreement()
        agreement.init(privParams)
        val shared = agreement.calculateAgreement(pubParams)

        // Convert to exactly 32 bytes (big-endian, unsigned, zero-padded)
        val bytes = shared.toByteArray()
        return when {
            bytes.size == 32 -> bytes
            bytes.size > 32 -> bytes.copyOfRange(bytes.size - 32, bytes.size)  // trim leading zero
            else -> ByteArray(32 - bytes.size) + bytes  // pad with zeros
        }
    }

    /** HKDF-extract using SHA-256. */
    private fun hkdfExtract(salt: ByteArray, ikm: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(salt, "HmacSHA256"))
        return mac.doFinal(ikm)
    }

    /** HKDF-expand using SHA-256. */
    private fun hkdfExpand(prk: ByteArray, info: ByteArray, length: Int): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(prk, "HmacSHA256"))

        val result = ByteArray(length)
        var t = ByteArray(0)
        var remaining = length
        var blockIndex = 1

        while (remaining > 0) {
            mac.reset()
            mac.update(t)
            mac.update(info)
            mac.update(blockIndex.toByte())
            t = mac.doFinal()

            val copyLen = minOf(t.size, remaining)
            System.arraycopy(t, 0, result, length - remaining, copyLen)
            remaining -= copyLen
            blockIndex++
        }

        return result
    }

    /** ChaCha20 encryption/decryption (RFC 7539 variant, 12-byte nonce). */
    private fun chacha20Encrypt(key: ByteArray, nonce: ByteArray, data: ByteArray): ByteArray {
        val engine = ChaCha7539Engine()
        engine.init(true, ParametersWithIV(KeyParameter(key), nonce))

        val result = ByteArray(data.size)
        engine.processBytes(data, 0, data.size, result, 0)
        return result
    }

    /** Pad plaintext per NIP-44 spec: 2-byte length + data + zero-padding. */
    private fun pad(plaintext: String): ByteArray {
        val utf8 = plaintext.toByteArray(Charsets.UTF_8)
        val len = utf8.size
        if (len < MIN_PLAINTEXT || len > MAX_PLAINTEXT) {
            throw IllegalArgumentException("plaintext size $len out of range")
        }

        val paddedLen = calcPaddedLen(len)
        val result = ByteArray(2 + paddedLen)
        // Write 16-bit big-endian length
        result[0] = (len shr 8).toByte()
        result[1] = len.toByte()
        // Copy data
        System.arraycopy(utf8, 0, result, 2, len)
        // Rest is already zero-initialized
        return result
    }

    /** Unpad per NIP-44 spec. */
    private fun unpad(padded: ByteArray): String {
        if (padded.size < 2) throw IllegalArgumentException("padded data too short")

        val len = ((padded[0].toInt() and 0xFF) shl 8) or (padded[1].toInt() and 0xFF)
        if (len < MIN_PLAINTEXT || len > MAX_PLAINTEXT) {
            throw IllegalArgumentException("invalid plaintext length: $len")
        }

        val expectedTotal = 2 + calcPaddedLen(len)
        if (padded.size != expectedTotal) {
            throw IllegalArgumentException("invalid padding: expected $expectedTotal, got ${padded.size}")
        }

        return padded.copyOfRange(2, 2 + len).toString(Charsets.UTF_8)
    }

    /** Calculate padded length per NIP-44 spec. */
    private fun calcPaddedLen(len: Int): Int {
        if (len <= 32) return 32
        val nextPower = 1 shl (32 - Integer.numberOfLeadingZeros(len - 1))
        val chunk = if (nextPower <= 256) 32 else nextPower / 8
        return chunk * ((len - 1) / chunk + 1)
    }

    /** HMAC-SHA256. */
    private fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(data)
    }

    /** Hex string to byte array. */
    private fun hexToBytes(hex: String): ByteArray {
        val len = hex.length / 2
        val bytes = ByteArray(len)
        for (i in 0 until len) {
            bytes[i] = ((Character.digit(hex[i * 2], 16) shl 4)
                    or Character.digit(hex[i * 2 + 1], 16)).toByte()
        }
        return bytes
    }

    /** Byte array concatenation. */
    private operator fun ByteArray.plus(other: ByteArray): ByteArray {
        val result = this.copyOf(this.size + other.size)
        System.arraycopy(other, 0, result, this.size, other.size)
        return result
    }
}
