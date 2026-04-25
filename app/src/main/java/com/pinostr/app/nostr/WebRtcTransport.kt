package com.pinostr.app.nostr

import android.content.Context
import org.webrtc.*
import java.nio.ByteBuffer
import java.util.concurrent.Executors

/**
 * WebRTC DataChannel transport — implements the Transport interface
 * using Google's WebRTC SDK (via stream-webrtc-android).
 *
 * Paired with the bridge's DataChannelTransport (node-datachannel).
 * Signaling (offer/answer/ICE) happens via NostrSignaler.
 */
class WebRtcTransport(
    private val stunServers: List<String> = listOf("stun:stun.l.google.com:19302"),
) : Transport {

    private val executor = Executors.newSingleThreadExecutor()
    private var peerConnection: PeerConnection? = null
    private var dataChannel: DataChannel? = null
    private var factory: PeerConnectionFactory? = null
    private var initialized = false

    override var onOpen: (() -> Unit)? = null
    override var onMessage: ((String) -> Unit)? = null
    override var onClose: ((code: Int, reason: String) -> Unit)? = null
    override var onError: ((Throwable) -> Unit)? = null

    /** Called when a local SDP is ready (offer or answer) for Nostr signaling. */
    var onLocalDescription: ((sdp: String, type: String) -> Unit)? = null

    /** Called when a local ICE candidate is generated. */
    var onLocalCandidate: ((candidate: String, mid: String) -> Unit)? = null

    /** Initialize WebRTC (must be called before use, with an Android Context). */
    fun initialize(context: Context) {
        if (initialized) return
        initialized = true
        executor.execute {
            PeerConnectionFactory.InitializationOptions
                .builder(context)
                .setFieldTrials("")
                .createInitializationOptions()
                .also { PeerConnectionFactory.initialize(it) }

            factory = PeerConnectionFactory.builder()
                .setOptions(PeerConnectionFactory.Options())
                .createPeerConnectionFactory()
        }
    }

    override fun connect(url: String) {
        // url ignored — WebRTC connection is managed via signaling calls
    }

    /** Create a WebRTC offer to initiate a connection with the bridge. */
    fun createOffer(peerId: String, label: String = "pi-bridge") {
        executor.execute {
            val pc = createPeerConnection(peerId)
            val dcInit = DataChannel.Init().apply { ordered = true; negotiated = false }
            val dc = pc.createDataChannel(label, dcInit)
            setupDataChannel(dc)
            this.dataChannel = dc
            this.peerConnection = pc

            pc.createOffer(offerObserver(pc), MediaConstraints())
        }
    }

    /** Set the bridge's offer SDP and generate an answer. */
    fun setRemoteOffer(sdp: String) {
        executor.execute {
            val pc = peerConnection ?: return@execute
            val desc = SessionDescription(SessionDescription.Type.OFFER, sdp)
            pc.setRemoteDescription(descObserver(pc, desc), desc)
        }
    }

    /** Set the bridge's answer SDP (after we sent an offer). */
    fun setRemoteAnswer(sdp: String) {
        executor.execute {
            val desc = SessionDescription(SessionDescription.Type.ANSWER, sdp)
            peerConnection?.setRemoteDescription(
                object : SdpObserver {
                    override fun onCreateSuccess(desc: SessionDescription?) {}
                    override fun onSetSuccess() {}
                    override fun onCreateFailure(msg: String) {
                        onError?.invoke(RuntimeException("Create answer failed: $msg"))
                    }
                    override fun onSetFailure(msg: String) {
                        onError?.invoke(RuntimeException("Set answer failed: $msg"))
                    }
                },
                desc,
            )
        }
    }

    /** Add a remote ICE candidate. */
    fun addIceCandidate(candidate: String, mid: String) {
        executor.execute {
            peerConnection?.addIceCandidate(IceCandidate(mid, 0, candidate))
        }
    }

    override fun send(data: String) {
        executor.execute {
            val buffer = ByteBuffer.wrap(data.toByteArray(Charsets.UTF_8))
            dataChannel?.send(DataChannel.Buffer(buffer, false))
        }
    }

    override fun close() {
        executor.execute {
            dataChannel?.close()
            dataChannel = null
            peerConnection?.close()
            peerConnection = null
            factory?.dispose()
            factory = null
            initialized = false
        }
    }

    // ── Private ──

    private fun createPeerConnection(peerId: String): PeerConnection {
        val iceServers = stunServers.map { PeerConnection.IceServer.builder(it).createIceServer() }
        val config = PeerConnection.RTCConfiguration(iceServers).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
        }
        return factory!!.createPeerConnection(config, pcObserver)!!
    }

    private val pcObserver = object : PeerConnection.Observer {
        override fun onIceCandidate(candidate: IceCandidate) {
            onLocalCandidate?.invoke(candidate.sdp, candidate.sdpMid ?: "")
        }
        override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>) {}
        override fun onSignalingChange(state: PeerConnection.SignalingState) {}
        override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) {}
        override fun onIceConnectionReceivingChange(receiving: Boolean) {}
        override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) {}
        override fun onAddStream(stream: MediaStream) {}
        override fun onRemoveStream(stream: MediaStream) {}
        override fun onDataChannel(dc: DataChannel) {
            setupDataChannel(dc)
            this@WebRtcTransport.dataChannel = dc
        }
        override fun onRenegotiationNeeded() {}
        override fun onAddTrack(track: RtpReceiver, streams: Array<out MediaStream>) {}
    }

    private fun offerObserver(pc: PeerConnection): SdpObserver = object : SdpObserver {
        override fun onCreateSuccess(desc: SessionDescription?) {
            if (desc == null) return
            pc.setLocalDescription(setObserver("offer"), desc)
            onLocalDescription?.invoke(desc.description, "offer")
        }
        override fun onSetSuccess() {}
        override fun onCreateFailure(msg: String) {
            onError?.invoke(RuntimeException("Create offer failed: $msg"))
        }
        override fun onSetFailure(msg: String) {
            onError?.invoke(RuntimeException("Set local description failed: $msg"))
        }
    }

    private fun descObserver(pc: PeerConnection, remoteDesc: SessionDescription): SdpObserver = object : SdpObserver {
        override fun onCreateSuccess(desc: SessionDescription?) {}
        override fun onSetSuccess() {
            // Remote description set, now create answer
            pc.createAnswer(answerObserver(pc), MediaConstraints())
        }
        override fun onCreateFailure(msg: String) {
            onError?.invoke(RuntimeException("Create answer failed: $msg"))
        }
        override fun onSetFailure(msg: String) {
            onError?.invoke(RuntimeException("Set remote description failed: $msg"))
        }
    }

    private fun answerObserver(pc: PeerConnection): SdpObserver = object : SdpObserver {
        override fun onCreateSuccess(desc: SessionDescription?) {
            if (desc == null) return
            pc.setLocalDescription(setObserver("answer"), desc)
            onLocalDescription?.invoke(desc.description, "answer")
        }
        override fun onSetSuccess() {}
        override fun onCreateFailure(msg: String) {
            onError?.invoke(RuntimeException("Create answer failed: $msg"))
        }
        override fun onSetFailure(msg: String) {
            onError?.invoke(RuntimeException("Set answer failed: $msg"))
        }
    }

    private fun setObserver(type: String): SdpObserver = object : SdpObserver {
        override fun onCreateSuccess(desc: SessionDescription?) {}
        override fun onSetSuccess() {
            println("[webrtc] Local $type set")
        }
        override fun onCreateFailure(msg: String) {
            onError?.invoke(RuntimeException("Create $type failed: $msg"))
        }
        override fun onSetFailure(msg: String) {
            onError?.invoke(RuntimeException("Set $type failed: $msg"))
        }
    }

    private fun setupDataChannel(dc: DataChannel) {
        dc.registerObserver(object : DataChannel.Observer {
            override fun onBufferedAmountChange(previousAmount: Long) {}
            override fun onStateChange() {
                when (dc.state()) {
                    DataChannel.State.OPEN -> onOpen?.invoke()
                    DataChannel.State.CLOSED -> onClose?.invoke(1000, "DataChannel closed")
                    else -> {}
                }
            }
            override fun onMessage(buffer: DataChannel.Buffer) {
                val bytes = ByteArray(buffer.data.remaining())
                buffer.data.get(bytes)
                onMessage?.invoke(String(bytes, Charsets.UTF_8))
            }
        })
    }
}
