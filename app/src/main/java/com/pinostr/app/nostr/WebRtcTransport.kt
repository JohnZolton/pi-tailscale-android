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

    var onLocalDescription: ((sdp: String, type: String) -> Unit)? = null
    var onLocalCandidate: ((candidate: String, mid: String) -> Unit)? = null

    fun initialize(context: Context) {
        if (initialized) return
        initialized = true
        println("[webrtc] Initializing WebRTC...")
        executor.execute {
            try {
                println("[webrtc] Creating PeerConnectionFactory...")
                PeerConnectionFactory.InitializationOptions
                    .builder(context)
                    .setFieldTrials("")
                    .createInitializationOptions()
                    .also { PeerConnectionFactory.initialize(it) }
                factory = PeerConnectionFactory.builder()
                    .setOptions(PeerConnectionFactory.Options())
                    .createPeerConnectionFactory()
                println("[webrtc] PeerConnectionFactory ready")
            } catch (e: Exception) {
                println("[webrtc] Init failed: ${e.message}")
                initialized = false
                onError?.invoke(e)
            }
        }
    }

    override fun connect(url: String) {}

    fun createOffer(peerId: String, label: String = "pi-bridge") {
        executor.execute {
            try {
                println("[webrtc] Creating offer for $peerId...")
                val pc = initPc(peerId)
                println("[webrtc] PC created, creating DataChannel...")
                val dcInit = DataChannel.Init().apply { ordered = true; negotiated = false }
                val dc = pc.createDataChannel(label, dcInit)
                setupDataChannel(dc)
                this.dataChannel = dc
                this.peerConnection = pc
                println("[webrtc] Calling createOffer...")
                pc.createOffer(offerObserver(pc), MediaConstraints())
            } catch (e: Exception) {
                println("[webrtc] Offer creation failed: ${e.message}")
                onError?.invoke(e)
            }
        }
    }

    fun setRemoteOffer(sdp: String) {
        executor.execute {
            val pc = peerConnection ?: return@execute
            pc.setRemoteDescription(descObserver(pc), SessionDescription(SessionDescription.Type.OFFER, sdp))
        }
    }

    fun setRemoteAnswer(sdp: String) {
        executor.execute {
            peerConnection?.setRemoteDescription(
                object : SdpObserver {
                    override fun onCreateSuccess(desc: SessionDescription?) {}
                    override fun onSetSuccess() {}
                    override fun onCreateFailure(m: String) { onError?.invoke(RuntimeException(m)) }
                    override fun onSetFailure(m: String) { onError?.invoke(RuntimeException(m)) }
                },
                SessionDescription(SessionDescription.Type.ANSWER, sdp),
            )
        }
    }

    fun addIceCandidate(candidate: String, mid: String) {
        executor.execute {
            peerConnection?.addIceCandidate(IceCandidate(mid, 0, candidate))
        }
    }

    override fun send(data: String) {
        executor.execute {
            val buf = ByteBuffer.wrap(data.toByteArray(Charsets.UTF_8))
            dataChannel?.send(DataChannel.Buffer(buf, false))
        }
    }

    override fun close() {
        executor.execute {
            dataChannel?.close(); dataChannel = null
            peerConnection?.close(); peerConnection = null
            factory?.dispose(); factory = null
            initialized = false
        }
    }

    private fun initPc(peerId: String): PeerConnection {
        val ice = stunServers.map { PeerConnection.IceServer.builder(it).createIceServer() }
        val config = PeerConnection.RTCConfiguration(ice).apply { sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN }
        return factory!!.createPeerConnection(config, pcObs)!!
    }

    private val pcObs = object : PeerConnection.Observer {
        override fun onIceCandidate(c: IceCandidate) { onLocalCandidate?.invoke(c.sdp, c.sdpMid ?: "") }
        override fun onIceCandidatesRemoved(c: Array<out IceCandidate>) {}
        override fun onSignalingChange(s: PeerConnection.SignalingState) {}
        override fun onIceConnectionChange(s: PeerConnection.IceConnectionState) {}
        override fun onIceConnectionReceivingChange(r: Boolean) {}
        override fun onIceGatheringChange(s: PeerConnection.IceGatheringState) {}
        override fun onAddStream(s: MediaStream) {}
        override fun onRemoveStream(s: MediaStream) {}
        override fun onDataChannel(dc: DataChannel) { setupDataChannel(dc); this@WebRtcTransport.dataChannel = dc }
        override fun onRenegotiationNeeded() {}
        override fun onAddTrack(t: RtpReceiver, s: Array<out MediaStream>) {}
    }

    private fun offerObserver(pc: PeerConnection) = object : SdpObserver {
        override fun onCreateSuccess(d: SessionDescription?) {
            if (d == null) return
            pc.setLocalDescription(setObs(), d)
            onLocalDescription?.invoke(d.description, "offer")
        }
        override fun onSetSuccess() {}
        override fun onCreateFailure(m: String) { onError?.invoke(RuntimeException("offer: $m")) }
        override fun onSetFailure(m: String) { onError?.invoke(RuntimeException("set: $m")) }
    }

    private fun descObserver(pc: PeerConnection) = object : SdpObserver {
        override fun onCreateSuccess(d: SessionDescription?) {}
        override fun onSetSuccess() { pc.createAnswer(answerObserver(pc), MediaConstraints()) }
        override fun onCreateFailure(m: String) { onError?.invoke(RuntimeException("desc: $m")) }
        override fun onSetFailure(m: String) { onError?.invoke(RuntimeException("set remote: $m")) }
    }

    private fun answerObserver(pc: PeerConnection) = object : SdpObserver {
        override fun onCreateSuccess(d: SessionDescription?) {
            if (d == null) return
            pc.setLocalDescription(setObs(), d)
            onLocalDescription?.invoke(d.description, "answer")
        }
        override fun onSetSuccess() {}
        override fun onCreateFailure(m: String) { onError?.invoke(RuntimeException("answer: $m")) }
        override fun onSetFailure(m: String) { onError?.invoke(RuntimeException("set ans: $m")) }
    }

    private fun setObs() = object : SdpObserver {
        override fun onCreateSuccess(d: SessionDescription?) {}
        override fun onSetSuccess() {}
        override fun onCreateFailure(m: String) { onError?.invoke(RuntimeException(m)) }
        override fun onSetFailure(m: String) { onError?.invoke(RuntimeException(m)) }
    }

    private fun setupDataChannel(dc: DataChannel) {
        dc.registerObserver(object : DataChannel.Observer {
            override fun onBufferedAmountChange(p: Long) {}
            override fun onStateChange() {
                when (dc.state()) {
                    DataChannel.State.OPEN -> onOpen?.invoke()
                    DataChannel.State.CLOSED -> onClose?.invoke(1000, "closed")
                    else -> {}
                }
            }
            override fun onMessage(b: DataChannel.Buffer) {
                val bytes = ByteArray(b.data.remaining()).also { b.data.get(it) }
                onMessage?.invoke(String(bytes, Charsets.UTF_8))
            }
        })
    }
}
