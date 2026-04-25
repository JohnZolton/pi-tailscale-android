package com.pinostr.app.nostr

import android.content.Context
import java.math.BigInteger
import java.security.*
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPrivateKeySpec
import java.security.spec.ECPublicKeySpec

/**
 * Nostr identity — secp256k1 keypair via Bouncy Castle provider.
 *
 * Persisted to app's private storage so the same key is reused
 * across app restarts (for consistent pairing with the bridge).
 */
data class NostrIdentity(
    val privkey: String,  // hex-encoded 32-byte secret scalar
    val pubkey: String,   // hex-encoded 33-byte compressed public key
) {
    companion object {
        private const val PREFS_KEY = "pi_nostr_identity"
        private const val PRIVKEY = "nostr_privkey"

        init {
            // Register Bouncy Castle as a JCA provider
            Security.insertProviderAt(
                org.bouncycastle.jce.provider.BouncyCastleProvider(),
                1,
            )
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

        /** Generate a fresh secp256k1 keypair. */
        fun generate(): NostrIdentity {
            val kpg = KeyPairGenerator.getInstance("EC", "BC")
            kpg.initialize(ECGenParameterSpec("secp256k1"), SecureRandom())
            val kp = kpg.generateKeyPair()

            val priv = (kp.private as org.bouncycastle.jce.interfaces.ECPrivateKey).d.toString(16)
            val pubKey = kp.public as org.bouncycastle.jce.interfaces.ECPublicKey
            val encoded = pubKey.q.getEncoded(true) // compressed
            return NostrIdentity(priv.padStart(64, '0'), bytesToHex(encoded))
        }

        /** Derive the public key hex from a private key hex. */
        fun derivePubkey(privkeyHex: String): String {
            val spec = org.bouncycastle.jce.ECNamedCurveTable.getParameterSpec("secp256k1")
            val q = spec.g.multiply(BigInteger(privkeyHex, 16))
            return bytesToHex(q.getEncoded(true))
        }

        private fun bytesToHex(bytes: ByteArray): String =
            bytes.joinToString("") { "%02x".format(it) }
    }
}
