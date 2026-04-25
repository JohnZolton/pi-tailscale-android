package com.pinostr.app.nostr

import android.content.Context
import fr.acinq.secp256k1.Secp256k1
import fr.acinq.secp256k1.jni.NativeSecp256k1AndroidLoader
import java.security.SecureRandom

/**
 * Nostr identity — secp256k1 keypair via libsecp256k1 native bindings.
 *
 * Persisted to app's private storage so the same key is reused
 * across app restarts.
 */
data class NostrIdentity(
    val privkey: String,  // hex-encoded 32-byte secret key
    val pubkey: String,   // hex-encoded 33-byte compressed public key
) {
    companion object {
        private const val PREFS_KEY = "pi_nostr_identity"
        private const val PRIVKEY = "nostr_privkey"
        private val secp: Secp256k1 by lazy {
            NativeSecp256k1AndroidLoader.load()
        }

        /** Load or generate a persistent identity. */
        fun loadOrCreate(context: Context): NostrIdentity {
            val prefs = context.getSharedPreferences(PREFS_KEY, Context.MODE_PRIVATE)
            val saved = prefs.getString(PRIVKEY, null)
            if (saved != null && saved.length == 64) {
                val pubkey = derivePubkey(saved)
                return NostrIdentity(saved, pubkey)
            }
            val identity = generate()
            prefs.edit().putString(PRIVKEY, identity.privkey).apply()
            return identity
        }

        /** Generate a fresh secp256k1 keypair (x-only pubkey, 64 hex chars). */
        fun generate(): NostrIdentity {
            val priv = ByteArray(32).also { SecureRandom().nextBytes(it) }
            val pub = secp.pubkeyCreate(priv)  // 33 bytes compressed
            val pubXOnly = pub.copyOfRange(1, 33)  // 32 bytes x-only (Nostr format)
            return NostrIdentity(bytesToHex(priv), bytesToHex(pubXOnly))
        }

        /** Derive the x-only public key hex from a private key hex. */
        fun derivePubkey(privkeyHex: String): String {
            val priv = hexToBytes(privkeyHex)
            val pub = secp.pubkeyCreate(priv)  // 33 bytes compressed
            val pubXOnly = pub.copyOfRange(1, 33)  // 32 bytes x-only
            return bytesToHex(pubXOnly)
        }

        private fun bytesToHex(bytes: ByteArray): String =
            bytes.joinToString("") { "%02x".format(it) }

        private fun hexToBytes(hex: String): ByteArray {
            val len = hex.length / 2
            val bytes = ByteArray(len)
            for (i in 0 until len) bytes[i] =
                ((Character.digit(hex[i * 2], 16) shl 4) or Character.digit(hex[i * 2 + 1], 16)).toByte()
            return bytes
        }
    }
}
