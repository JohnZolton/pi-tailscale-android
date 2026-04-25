package com.pinostr.app.nostr

import okhttp3.*
import java.util.concurrent.TimeUnit

/**
 * WebSocket-based transport — the current Tailscale path.
 * Wraps OkHttp WebSocket into the Transport interface.
 */
class WebSocketTransport : Transport {

    private val client = OkHttpClient.Builder()
        .pingInterval(15, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS)
        .connectTimeout(10, TimeUnit.SECONDS)
        .build()

    private var ws: WebSocket? = null

    override var onOpen: (() -> Unit)? = null
    override var onMessage: ((String) -> Unit)? = null
    override var onClose: ((code: Int, reason: String) -> Unit)? = null
    override var onError: ((Throwable) -> Unit)? = null

    override fun connect(url: String) {
        val request = Request.Builder().url(url).build()
        ws = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                onOpen?.invoke()
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                onMessage?.invoke(text)
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                // Default close — let onClosed handle it
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                ws = null
                onClose?.invoke(code, reason)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                ws = null
                onError?.invoke(t)
            }
        })
    }

    override fun send(data: String) {
        ws?.send(data)
    }

    override fun close() {
        ws?.close(1000, "App closing")
        ws = null
    }
}
