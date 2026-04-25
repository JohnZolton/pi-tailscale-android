package com.pinostr.app.nostr

/**
 * Pluggable transport layer for sending/receiving JSON frames.
 *
 * Current implementations:
 *   - WebSocketTransport  (Tailscale / direct WS)
 *   - WebRtcTransport     (P2P data channel)   — coming soon
 *
 * The BridgeClient uses this interface so any transport
 * can be swapped in without changing the app logic.
 */
interface Transport {
    /** Connect to the remote endpoint. */
    fun connect(url: String)

    /** Send a raw JSON string. */
    fun send(data: String)

    /** Gracefully close the connection. */
    fun close()

    // ── Callbacks (set before connect) ──

    var onOpen: (() -> Unit)?
    var onMessage: ((String) -> Unit)?
    var onClose: ((code: Int, reason: String) -> Unit)?
    var onError: ((Throwable) -> Unit)?
}
