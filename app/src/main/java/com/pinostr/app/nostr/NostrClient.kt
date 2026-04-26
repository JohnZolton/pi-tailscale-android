package com.pinostr.app.nostr

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import okhttp3.*

/**
 * Minimal Nostr relay client for signaling.
 *
 * Connects to a relay via WebSocket, subscribes to events,
 * and publishes events. Used for exchanging WebRTC handshake
 * messages (NIP-44 encrypted DMs).
 */
class NostrClient {

    private val gson = Gson()
    private val client = OkHttpClient.Builder()
        .pingInterval(15, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(0, java.util.concurrent.TimeUnit.SECONDS)
        .build()

    private var ws: WebSocket? = null
    private var scope: CoroutineScope? = null
    private var subId: String? = null
    private var relayUrl: String? = null
    private var currentFilters: Map<String, Any?>? = null
    private var reconnectJob: Job? = null

    private val _events = Channel<NostrEvent>(Channel.UNLIMITED)
    val events: kotlinx.coroutines.channels.ReceiveChannel<NostrEvent> = _events

    /** A received Nostr event. */
    data class NostrEvent(
        val id: String,
        val pubkey: String,
        val kind: Int,
        val content: String,
        val tags: List<List<String>>,
        val createdAt: Long,
    )

    /** Connect to a relay URL (wss://...). */
    fun connect(url: String, scope: CoroutineScope) {
        this.scope = scope
        this.relayUrl = url
        println("[nostr] Connecting to $url...")
        val request = Request.Builder().url(url).build()
        ws = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                println("[nostr] ✅ Connected to $url")
                // Re-subscribe if we reconnected
                currentFilters?.let { subscribe(it) }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                println("[nostr] 📩 Message from relay: ${text.take(500)}")
                handleMessage(text)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                println("[nostr] ❌ Closed: $reason (code $code)")
                scheduleReconnect()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                println("[nostr] 💥 Error: ${t.message}")
                scheduleReconnect()
            }
        })
    }

    private fun scheduleReconnect() {
        reconnectJob?.cancel()
        reconnectJob = scope?.launch {
            delay(5000)
            val url = relayUrl ?: return@launch
            println("[nostr] Reconnecting to $url...")
            connect(url, scope!!)
        }
    }

    /** Subscribe to events. Nostr format: ["REQ", subId, {filter1}, {filter2}, ...] */
    fun subscribe(filters: Map<String, Any?>) {
        currentFilters = filters
        val id = "sub_${System.currentTimeMillis()}"
        subId = id
        val req = JsonArray().apply {
            add("REQ")
            add(id)
            val filterObj = JsonObject()
            for ((key, value) in filters) {
                when (value) {
                    is String -> filterObj.addProperty(key, value)
                    is Number -> filterObj.addProperty(key, value)
                    is Boolean -> filterObj.addProperty(key, value)
                    is List<*> -> {
                        val arr = JsonArray()
                        for (v in value) {
                            when (v) {
                                is Number -> arr.add(v)
                                else -> arr.add(v?.toString())
                            }
                        }
                        filterObj.add(key, arr)
                    }
                }
            }
            add(filterObj)
        }
        ws?.send(gson.toJson(req))
        println("[nostr] Subscribed: $id")
    }

    /** Publish an event. */
    fun publish(event: Map<String, Any?>) {
        val req = JsonArray().apply {
            add("EVENT")
            val ev = JsonObject()
            for ((key, value) in event) {
                when (value) {
                    is String -> ev.addProperty(key, value)
                    is Number -> ev.addProperty(key, value)
                    is Boolean -> ev.addProperty(key, value)
                    is List<*> -> {
                        val arr = JsonArray()
                        for (v in value ?: emptyList<Any>()) {
                            when (v) {
                                is List<*> -> {
                                    val inner = JsonArray()
                                    for (iv in v ?: emptyList<Any>()) inner.add(iv.toString())
                                    arr.add(inner)
                                }
                                else -> arr.add(v.toString())
                            }
                        }
                        ev.add(key, arr)
                    }
                }
            }
            add(ev)
        }
        ws?.send(gson.toJson(req))
    }

    fun close() {
        ws?.close(1000, "App closing")
        ws = null
    }

    // ── Private ──

    private fun handleMessage(text: String) {
        try {
            val arr = gson.fromJson(text, JsonArray::class.java) ?: return
            if (arr.size() < 2) return
            val type = arr[0].asString

            when (type) {
                "EVENT" -> {
                    val ev = arr[2].asJsonObject
                    val event = NostrEvent(
                        id = ev["id"]?.asString ?: "",
                        pubkey = ev["pubkey"]?.asString ?: "",
                        kind = ev["kind"]?.asInt ?: 0,
                        content = ev["content"]?.asString ?: "",
                        tags = ev["tags"]?.asJsonArray?.map { tagArr ->
                            tagArr.asJsonArray.map { it.asString }
                        } ?: emptyList(),
                        createdAt = ev["created_at"]?.asLong ?: 0,
                    )
                    _events.trySend(event)
                }
                "EOSE" -> {
                    val eoseId = if (arr.size() > 1) arr[1]?.asString else null
                    println("[nostr] EOSE for $eoseId")
                }
                "NOTICE" -> {
                    val notice = if (arr.size() > 1) arr[1]?.asString else null
                    println("[nostr] NOTICE: $notice")
                }
            }
        } catch (e: Exception) {
            println("[nostr] Parse error: ${e.message}")
        }
    }
}
