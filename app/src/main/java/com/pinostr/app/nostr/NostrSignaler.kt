package com.pinostr.app.nostr

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonParser
import fr.acinq.secp256k1.Secp256k1
import fr.acinq.secp256k1.jni.NativeSecp256k1AndroidLoader
import kotlinx.coroutines.*
import java.security.MessageDigest

/**
 * Nostr signaling layer (Android).
 *
 * Mirrors the bridge's NostrSignaler. Exchanges WebRTC handshake
 * messages (offer, answer, ICE candidates) over NIP-44 encrypted
 * DMs using the app's identity keypair.
 */
class NostrSignaler {

    private val gson = Gson()
    private var client: NostrClient? = null
    private var identity: NostrIdentity? = null
    private var scope: CoroutineScope? = null
    private var convCache = mutableMapOf<String, ByteArray>()
    private var job: Job? = null

    private val secp: Secp256k1 by lazy { NativeSecp256k1AndroidLoader.load() }

    /** Signaling message types. */
    data class SignalingMessage(
        val type: String,
        val pairingCode: String? = null,
        val sdp: String? = null,
        val sessionPubkey: String? = null,
        val candidate: String? = null,
        val appPubkey: String? = null,
    )

    /** Callbacks for received messages. */
    var onOffer: ((msg: SignalingMessage, fromPubkey: String) -> Unit)? = null
    var onAnswer: ((msg: SignalingMessage, fromPubkey: String) -> Unit)? = null
    var onIce: ((msg: SignalingMessage, fromPubkey: String) -> Unit)? = null
    var onPairingRequest: ((msg: SignalingMessage, fromPubkey: String) -> Unit)? = null

    companion object {
        private const val SIGNALING_KIND = 1059
    }

    /**
     * Start listening for signaling messages.
     */
    fun start(
        identity: NostrIdentity,
        relays: List<String>,
        bridgePubkey: String,
        scope: CoroutineScope,
    ) {
        this.identity = identity
        this.scope = scope

        for (url in relays) {
            val c = NostrClient()
            c.connect(url, scope)

            // Use the FIRST relay as our client (skip broken ones like relay.nostr.band)
            if (this.client == null) {
                this.client = c
                println("[signaler] Using relay: $url")
            }

            // Wait a moment for WS to open, then subscribe
            scope.launch {
                delay(3000)
                c.subscribe(mapOf("kinds" to listOf(SIGNALING_KIND), "#p" to listOf(identity.pubkey)))
                println("[signaler] Subscribed on $url")
            }

            // Listen for incoming events
            scope.launch {
                for (event in c.events) {
                    if (event.pubkey != bridgePubkey) continue
                    handleIncoming(event)
                }
            }
        }
    }

    fun stop() {
        job?.cancel()
        client?.close()
        client = null
    }

    /**
     * Send an encrypted, signed signaling message to the bridge via NostrClient.
     */
    fun sendMessage(toPubkey: String, msg: SignalingMessage) {
        val client = this.client ?: return
        val identity = this.identity ?: return

        // Encrypt with NIP-44
        val json = gson.toJson(msg)
        val convKey = getConversationKey(identity.privkey, toPubkey)
        val ciphertext = Nip44.encrypt(json, convKey)

        // Build and sign the Nostr event
        val createdAt = System.currentTimeMillis() / 1000
        val eventTags = listOf(listOf("p", toPubkey))

        val eventId = computeEventId(identity.pubkey, createdAt, SIGNALING_KIND, eventTags, ciphertext)
        val sig = signEvent(eventId, identity.privkey)

        val event = mapOf<String, Any?>(
            "id" to eventId,
            "pubkey" to identity.pubkey,
            "created_at" to createdAt,
            "kind" to SIGNALING_KIND,
            "tags" to eventTags,
            "content" to ciphertext,
            "sig" to sig,
        )

        println("[signaler] Publishing ${msg.type} (event: ${eventId.take(16)}...)")
        client.publish(event)
        println("[signaler] Publish sent")
        println("[signaler] Sent ${msg.type} to ${toPubkey.take(12)}")
    }

    // ── Private ──

    /** Compute Nostr event ID: sha256(JSON([0, pubkey, created_at, kind, tags, content])). */
    private fun computeEventId(pubkey: String, createdAt: Long, kind: Int, tags: List<List<String>>, content: String): String {
        // Build the canonical JSON array for hashing
        val arr = JsonArray().apply {
            add(0) // reserved
            add(pubkey)
            add(createdAt)
            add(kind)
            val tagsArr = JsonArray()
            for (tag in tags) {
                val t = JsonArray()
                for (v in tag) t.add(v)
                tagsArr.add(t)
            }
            add(tagsArr)
            add(content)
        }
        val canonical = arr.toString()
        val hash = MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray(Charsets.UTF_8))
        return bytesToHex(hash)
    }

    /** Sign an event ID with secp256k1 ECDSA. */
    private fun signEvent(eventId: String, privkeyHex: String): String {
        val eventIdBytes = hexToBytes(eventId)
        val privBytes = hexToBytes(privkeyHex)
        val sig = secp.sign(eventIdBytes, privBytes)
        return bytesToHex(sig)
    }

    private fun handleIncoming(event: NostrClient.NostrEvent) {
        val identity = this.identity ?: return

        val pTags = event.tags.filter { it.isNotEmpty() && it[0] == "p" }
        if (pTags.none { it.size > 1 && it[1] == identity.pubkey }) return

        val plaintext: String
        try {
            val convKey = getConversationKey(identity.privkey, event.pubkey)
            plaintext = Nip44.decrypt(event.content, convKey)
        } catch (e: Exception) {
            println("[signaler] Decrypt failed: ${e.message}")
            return
        }

        val msg: SignalingMessage
        try {
            msg = gson.fromJson(plaintext, SignalingMessage::class.java)
        } catch (e: Exception) {
            return
        }

        when (msg.type) {
            "webrtc-offer" -> onOffer?.invoke(msg, event.pubkey)
            "webrtc-answer" -> onAnswer?.invoke(msg, event.pubkey)
            "webrtc-ice" -> onIce?.invoke(msg, event.pubkey)
            "pairing-request" -> onPairingRequest?.invoke(msg, event.pubkey)
        }
    }

    private fun getConversationKey(privkeyHex: String, pubkeyHex: String): ByteArray {
        val key = "conv:${pubkeyHex.take(16)}"
        return convCache.getOrPut(key) {
            Nip44.getConversationKey(privkeyHex, pubkeyHex)
        }
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
