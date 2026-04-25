package com.pinostr.app.nostr

import com.google.gson.Gson
import com.google.gson.JsonObject
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel

/**
 * Bridge client — sends prompts, receives streaming frames.
 *
 * Uses a pluggable [Transport] so the same client code works over
 * WebSocket (Tailscale), WebRTC DataChannel, or any future transport.
 */
class DirectClient {

    private val gson = Gson()
    private var transport: Transport? = null
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

    private var reconnectUrl: String? = null
    private var reconnectJob: Job? = null

    /**
     * Connect to the bridge.
     * @param url ws://tailscale-ip:3002 or similar transport address
     */
    fun connect(url: String, scope: CoroutineScope) {
        this.scope = scope
        this.reconnectUrl = url

        _connectionState.trySend(ConnectionState.CONNECTING)
        println("[bridge] Connecting to $url")

        // Use WebSocket transport by default (Tailscale/dev path)
        val t = WebSocketTransport()
        transport = t

        t.onOpen = {
            println("[bridge] Connected!")
            _connectionState.trySend(ConnectionState.CONNECTED)
        }

        t.onMessage = { text ->
            handleFrame(text)
        }

        t.onClose = { code, reason ->
            println("[bridge] Closed: $reason (code $code)")
            _connectionState.trySend(ConnectionState.DISCONNECTED)
            scheduleReconnect()
        }

        t.onError = { error ->
            println("[bridge] Error: ${error.message}")
            _connectionState.trySend(ConnectionState.ERROR)
            scheduleReconnect()
        }

        t.connect(url)
    }

    /**
     * Send a message to the bridge (routed as a prompt on the thread).
     */
    fun sendMessage(text: String, threadId: String = "default") {
        val msg = JsonObject().apply {
            addProperty("type", "send")
            add("data", JsonObject().apply {
                addProperty("text", text)
                addProperty("thread", threadId)
            })
        }
        transport?.send(gson.toJson(msg))
        println("[bridge] Sent: ${text.take(50)} (thread: $threadId)")
    }

    /**
     * Request directory listing from the bridge.
     * Response comes as a `dir_list` frame on the frames channel.
     */
    fun listDir(path: String) {
        val msg = JsonObject().apply {
            addProperty("type", "list_dir")
            add("data", JsonObject().apply { addProperty("path", path) })
        }
        transport?.send(gson.toJson(msg))
    }

    /**
     * Send a ping to keep alive.
     */
    fun ping() {
        val msg = JsonObject().apply {
            addProperty("type", "ping")
            add("data", JsonObject())
        }
        transport?.send(gson.toJson(msg))
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
            println("[bridge] Parse error: ${e.message}")
        }
    }

    private fun scheduleReconnect() {
        reconnectJob?.cancel()
        reconnectJob = scope?.launch {
            delay(5000)
            val url = reconnectUrl ?: return@launch
            println("[bridge] Reconnecting...")
            connect(url, scope!!)
        }
    }

    /**
     * Replace the current transport with a new one (e.g. WebSocket → WebRTC).
     * The old transport is closed. Frames continue flowing through the new one.
     */
    fun setTransport(newTransport: Transport) {
        disconnect() // close old transport + cancel reconnect
        transport = newTransport

        newTransport.onMessage = { text ->
            handleFrame(text)
        }

        newTransport.onClose = { code, reason ->
            println("[bridge] Transport closed: $reason (code $code)")
            _connectionState.trySend(ConnectionState.DISCONNECTED)
        }

        newTransport.onError = { error ->
            println("[bridge] Transport error: ${error.message}")
            _connectionState.trySend(ConnectionState.ERROR)
        }

        // Notify connection state once transport is ready
        newTransport.onOpen = {
            println("[bridge] Transport connected!")
            _connectionState.trySend(ConnectionState.CONNECTED)
        }
    }

    fun disconnect() {
        reconnectJob?.cancel()
        reconnectUrl = null
        transport?.close()
        transport = null
        _connectionState.trySend(ConnectionState.DISCONNECTED)
    }
}
