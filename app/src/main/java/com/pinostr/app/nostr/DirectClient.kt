package com.pinostr.app.nostr

import com.google.gson.Gson
import com.google.gson.JsonObject
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import okhttp3.*
import java.util.concurrent.TimeUnit

/**
 * Direct WebSocket client to the bridge over Tailscale.
 * No Nostr relays, no NIP-17, just JSON frames.
 */
class DirectClient {

    private val gson = Gson()
    private var ws: WebSocket? = null
    private var scope: CoroutineScope? = null

    private val _frames = Channel<StreamFrame>(Channel.BUFFERED)
    val frames: kotlinx.coroutines.channels.ReceiveChannel<StreamFrame> = _frames

    private val _connectionState = Channel<ConnectionState>(Channel.CONFLATED)
    val connectionState: kotlinx.coroutines.channels.ReceiveChannel<ConnectionState> = _connectionState

    enum class ConnectionState { CONNECTING, CONNECTED, DISCONNECTED, ERROR }

    data class StreamFrame(
        val type: String,
        val data: Map<String, Any?>,
    )

    private val client = OkHttpClient.Builder()
        .pingInterval(15, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS)
        .connectTimeout(10, TimeUnit.SECONDS)
        .build()

    private var reconnectUrl: String? = null
    private var reconnectJob: Job? = null

    /**
     * Connect to the bridge WebSocket.
     * @param url ws://tailscale-ip:3002/stream
     */
    fun connect(url: String, scope: CoroutineScope) {
        this.scope = scope
        this.reconnectUrl = url

        _connectionState.trySend(ConnectionState.CONNECTING)
        println("[direct] Connecting to $url")

        val request = Request.Builder().url(url).build()
        ws = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                println("[direct] Connected!")
                _connectionState.trySend(ConnectionState.CONNECTED)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                handleFrame(text)
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                println("[direct] Closing: $reason")
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                println("[direct] Closed: $reason")
                ws = null
                _connectionState.trySend(ConnectionState.DISCONNECTED)
                scheduleReconnect()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                println("[direct] Failed: ${t.message}")
                ws = null
                _connectionState.trySend(ConnectionState.ERROR)
                scheduleReconnect()
            }
        })
    }

    /**
     * Send a message to the bridge (routed as a prompt on the thread).
     */
    fun sendMessage(text: String) {
        val msg = JsonObject().apply {
            addProperty("type", "send")
            add("data", JsonObject().apply { addProperty("text", text) })
        }
        ws?.send(gson.toJson(msg))
        println("[direct] Sent: ${text.take(50)}")
    }

    /**
     * Send a ping to keep alive.
     */
    fun ping() {
        val msg = JsonObject().apply {
            addProperty("type", "ping")
            add("data", JsonObject())
        }
        ws?.send(gson.toJson(msg))
    }

    private fun handleFrame(text: String) {
        try {
            val obj = gson.fromJson(text, JsonObject::class.java)
            val type = obj.get("type")?.asString ?: return
            val dataObj = obj.getAsJsonObject("data") ?: JsonObject()

            val dataMap = mutableMapOf<String, Any?>()
            for (key in dataObj.keySet()) {
                val value = dataObj.get(key)
                dataMap[key] = when {
                    value?.isJsonPrimitive == true -> {
                        val p = value.asJsonPrimitive
                        when { p.isString -> p.asString; p.isBoolean -> p.asBoolean; p.isNumber -> p.asNumber; else -> p.toString() }
                    }
                    value?.isJsonArray == true -> value.asJsonArray.toList()
                    value?.isJsonObject == true -> value.asJsonObject.toString()
                    else -> null
                }
            }
            _frames.trySend(StreamFrame(type, dataMap))
        } catch (e: Exception) {
            println("[direct] Parse error: ${e.message}")
        }
    }

    private fun scheduleReconnect() {
        reconnectJob?.cancel()
        reconnectJob = scope?.launch {
            delay(5000)
            val url = reconnectUrl ?: return@launch
            println("[direct] Reconnecting...")
            connect(url, scope!!)
        }
    }

    fun disconnect() {
        reconnectJob?.cancel()
        reconnectUrl = null
        ws?.close(1000, "App closing")
        ws = null
    }
}
