package com.pinostr.app.nostr

import com.google.gson.Gson
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel

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
     * @param identity the app's Nostr identity
     * @param relays list of relay URLs to connect to
     * @param bridgePubkey the bridge's public key (for filtering)
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
            this.client = c

            // Subscribe for events from the bridge
            c.subscribe(mapOf("kinds" to listOf(SIGNALING_KIND), "#p" to listOf(identity.pubkey)))

            // Listen for incoming events
            job = scope.launch {
                for (event in c.events) {
                    if (event.pubkey != bridgePubkey) continue // only from bridge
                    handleIncoming(event)
                }
            }

            println("[signaler] Listening on ${identity.pubkey.take(12)}... via $url")
        }
    }

    /** Stop listening. */
    fun stop() {
        job?.cancel()
        client?.close()
        client = null
    }

    /** Send an encrypted signaling message to the bridge. */
    fun sendMessage(toPubkey: String, msg: SignalingMessage, onPublish: (Map<String, Any?>) -> Unit) {
        val identity = this.identity ?: return
        val json = gson.toJson(msg)
        val convKey = getConversationKey(identity.privkey, toPubkey)
        val ciphertext = Nip44.encrypt(json, convKey)

        val event = mapOf<String, Any?>(
            "kind" to SIGNALING_KIND,
            "created_at" to (System.currentTimeMillis() / 1000),
            "tags" to listOf(listOf("p", toPubkey)),
            "content" to ciphertext,
            "pubkey" to identity.pubkey,
        )

        // The caller provides the NostrClient's publish or external publish
        onPublish(event)
    }

    /** Send a signaling message via the connected NostrClient. */
    fun sendMessage(toPubkey: String, msg: SignalingMessage) {
        val client = this.client ?: return
        sendMessage(toPubkey, msg) { event ->
            client.publish(event)
            println("[signaler] Sent ${msg.type} to ${toPubkey.take(12)}")
        }
    }

    // ── Private ──

    private fun handleIncoming(event: NostrClient.NostrEvent) {
        val identity = this.identity ?: return

        // Check p-tags include us
        val pTags = event.tags.filter { it.isNotEmpty() && it[0] == "p" }
        if (pTags.none { it.size > 1 && it[1] == identity.pubkey }) return

        // Decrypt
        val plaintext: String
        try {
            val convKey = getConversationKey(identity.privkey, event.pubkey)
            plaintext = Nip44.decrypt(event.content, convKey)
        } catch (e: Exception) {
            println("[signaler] Decrypt failed: ${e.message}")
            return
        }

        // Parse as SignalingMessage
        val msg: SignalingMessage
        try {
            msg = gson.fromJson(plaintext, SignalingMessage::class.java)
        } catch (e: Exception) {
            return
        }

        // Route
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
}
