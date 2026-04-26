package com.pinostr.app.nostr

import com.google.gson.Gson
import fr.acinq.secp256k1.Secp256k1
import fr.acinq.secp256k1.jni.NativeSecp256k1AndroidLoader
import kotlinx.coroutines.*
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Nostr DM transport — sends/receives agent frames as NIP-44 encrypted
 * Nostr events. Implements the Transport interface so DirectClient can
 * use it transparently, replacing WebSocket entirely.
 *
 * No NAT issues, no port forwarding, no native libraries.
 */
class NostrTransport(
    private val identity: NostrIdentity,
    private val bridgePubkey: String,
    private val relays: List<String>,
) : Transport {

    private val gson = Gson()
    private val secp: Secp256k1 by lazy { NativeSecp256k1AndroidLoader.load() }
    private val secureRandom = SecureRandom()
    private var client: NostrClient? = null
    private var scope: CoroutineScope? = null
    private var job: Job? = null
    private var convCache = mutableMapOf<String, ByteArray>()

    override var onOpen: (() -> Unit)? = null
    override var onMessage: ((String) -> Unit)? = null
    override var onClose: ((code: Int, reason: String) -> Unit)? = null
    override var onError: ((Throwable) -> Unit)? = null

    override fun connect(url: String) {}

    fun start(scope: CoroutineScope) {
        this.scope = scope

        // Connect to first relay
        val relayUrl = relays.firstOrNull() ?: return
        val c = NostrClient()
        c.connect(relayUrl, scope)
        this.client = c

        // Subscribe for events from the bridge
        scope.launch {
            delay(3000)
            c.subscribe(mapOf("kinds" to listOf(1059), "#p" to listOf(identity.pubkey)))
        }

        // Listen for incoming events
        job = scope.launch {
            for (event in c.events) {
                if (event.pubkey != bridgePubkey) continue
                handleEvent(event)
            }
        }

        // Signal connection
        scope.launch {
            delay(4000)
            onOpen?.invoke()
        }
    }

    override fun send(data: String) {
        val client = this.client ?: return
        val msg = gson.toJson(mapOf("type" to "frame", "data" to data))
        val convKey = getConversationKey(identity.privkey, bridgePubkey)
        val ciphertext = Nip44.encrypt(msg, convKey)
        val eventId = computeEventId(identity.pubkey, System.currentTimeMillis() / 1000, ciphertext)
        val sig = signEvent(eventId, identity.privkey)

        client.publish(mapOf(
            "id" to eventId,
            "pubkey" to identity.pubkey,
            "created_at" to (System.currentTimeMillis() / 1000),
            "kind" to 1059,
            "tags" to listOf(listOf("p", bridgePubkey)),
            "content" to ciphertext,
            "sig" to sig,
        ))
    }

    override fun close() {
        job?.cancel()
        client?.close()
        client = null
    }

    // ── Private ──

    private fun handleEvent(event: NostrClient.NostrEvent) {
        try {
            val convKey = getConversationKey(identity.privkey, event.pubkey)
            val plain = Nip44.decrypt(event.content, convKey)
            val parsed = gson.fromJson(plain, Map::class.java)
            if (parsed["type"] == "frame") {
                val frameData = parsed["data"] as? String
                frameData?.let { onMessage?.invoke(it) }
            }
        } catch (e: Exception) {
            // ignore
        }
    }

    private fun getConversationKey(privkeyHex: String, pubkeyHex: String): ByteArray {
        val key = "conv:${pubkeyHex.take(16)}"
        return convCache.getOrPut(key) { Nip44.getConversationKey(privkeyHex, pubkeyHex) }
    }

    private fun computeEventId(pubkey: String, createdAt: Long, content: String): String {
        val esc = { s: String -> "\"${s.replace("\\", "\\\\").replace("\"", "\\\"")}\"" }
        val canonical = "[0,${esc(pubkey)},$createdAt,1059,[[\"p\",${esc(bridgePubkey)}]],${esc(content)}]"
        val hash = MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray(Charsets.UTF_8))
        return bytesToHex(hash)
    }

    private fun signEvent(eventId: String, privkeyHex: String): String {
        val auxRand = ByteArray(32).also { secureRandom.nextBytes(it) }
        val sig = secp.signSchnorr(hexToBytes(eventId), hexToBytes(privkeyHex), auxRand)
        return bytesToHex(sig)
    }

    private fun bytesToHex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }
    private fun hexToBytes(hex: String): ByteArray {
        val len = hex.length / 2; val b = ByteArray(len)
        for (i in 0 until len) b[i] = ((Character.digit(hex[i*2], 16) shl 4) or Character.digit(hex[i*2+1], 16)).toByte()
        return b
    }
}
