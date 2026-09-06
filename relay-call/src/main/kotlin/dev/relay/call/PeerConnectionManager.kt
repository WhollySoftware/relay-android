package dev.relay.call

import android.content.Context
import kotlinx.coroutines.suspendCancellableCoroutine
import org.webrtc.AudioTrack
import org.webrtc.Camera2Enumerator
import org.webrtc.CameraVideoCapturer
import org.webrtc.DataChannel
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpReceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoTrack
import org.webrtc.audio.JavaAudioDeviceModule
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Process-wide WebRTC bootstrap: factory init is once-per-process; one EglBase for all renderers. */
object RelayWebRtc {
    @Volatile private var initialized = false
    lateinit var eglBase: EglBase
    lateinit var factory: PeerConnectionFactory
    @Synchronized fun ensure(context: Context) {
        if (initialized) return
        PeerConnectionFactory.initialize(PeerConnectionFactory.InitializationOptions.builder(context.applicationContext).createInitializationOptions())
        eglBase = EglBase.create()
        factory = PeerConnectionFactory.builder()
            .setVideoEncoderFactory(DefaultVideoEncoderFactory(eglBase.eglBaseContext, true, true))
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(eglBase.eglBaseContext))
            .setAudioDeviceModule(JavaAudioDeviceModule.builder(context.applicationContext).createAudioDeviceModule())
            .createPeerConnectionFactory()
        initialized = true
    }
}

/**
 * One PeerConnection for one 1:1 call — same shape as the iOS/web managers: candidates queued
 * until the remote description exists, ICE restart by the offerer, mic/camera toggles.
 */
class PeerConnectionManager(private val context: Context) {
    class IceServer(val urls: List<String>, val username: String?, val credential: String?)
    var onIceCandidate: ((IceCandidatePayload) -> Unit)? = null
    var onConnectionStateChange: ((PeerConnection.PeerConnectionState) -> Unit)? = null
    var onRemoteVideoTrack: ((VideoTrack) -> Unit)? = null

    private var pc: PeerConnection? = null
    private var audioTrack: AudioTrack? = null
    var localVideoTrack: VideoTrack? = null; private set
    private var capturer: CameraVideoCapturer? = null
    private var surfaceHelper: SurfaceTextureHelper? = null
    private val pendingCandidates = mutableListOf<IceCandidate>()
    @Volatile private var remoteDescriptionSet = false
    var isOfferer = false; private set
    private var iceRestarts = 0
    val connectionState get() = pc?.connectionState()
    val canRestartIce get() = pc != null && isOfferer && iceRestarts < 3

    fun start(type: CallType, iceServers: List<IceServer>) {
        RelayWebRtc.ensure(context)
        val servers = if (iceServers.isEmpty()) listOf(PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer())
            else iceServers.map { s -> PeerConnection.IceServer.builder(s.urls).apply { s.username?.let { setUsername(it) }; s.credential?.let { setPassword(it) } }.createIceServer() }
        val config = PeerConnection.RTCConfiguration(servers).apply { sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN; continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY }
        val connection = RelayWebRtc.factory.createPeerConnection(config, object : PeerConnection.Observer {
            override fun onIceCandidate(c: IceCandidate) { onIceCandidate?.invoke(IceCandidatePayload(c.sdp, c.sdpMid, c.sdpMLineIndex)) }
            override fun onConnectionChange(newState: PeerConnection.PeerConnectionState) { onConnectionStateChange?.invoke(newState) }
            override fun onAddTrack(receiver: RtpReceiver, streams: Array<out MediaStream>) { (receiver.track() as? VideoTrack)?.let { onRemoteVideoTrack?.invoke(it) } }
            override fun onSignalingChange(p0: PeerConnection.SignalingState) {}
            override fun onIceConnectionChange(p0: PeerConnection.IceConnectionState) {}
            override fun onIceConnectionReceivingChange(p0: Boolean) {}
            override fun onIceGatheringChange(p0: PeerConnection.IceGatheringState) {}
            override fun onIceCandidatesRemoved(p0: Array<out IceCandidate>) {}
            override fun onAddStream(p0: MediaStream) {}
            override fun onRemoveStream(p0: MediaStream) {}
            override fun onDataChannel(p0: DataChannel) {}
            override fun onRenegotiationNeeded() {}
        }) ?: throw IllegalStateException("createPeerConnection failed")
        pc = connection
        val audio = RelayWebRtc.factory.createAudioTrack("audio0", RelayWebRtc.factory.createAudioSource(MediaConstraints()))
        audioTrack = audio
        connection.addTrack(audio, listOf("stream0"))
        if (type == CallType.VIDEO) {
            val enumerator = Camera2Enumerator(context)
            val device = enumerator.deviceNames.firstOrNull { enumerator.isFrontFacing(it) } ?: enumerator.deviceNames.firstOrNull()
            if (device != null) {
                val source = RelayWebRtc.factory.createVideoSource(false)
                val helper = SurfaceTextureHelper.create("relay-capture", RelayWebRtc.eglBase.eglBaseContext)
                val cap = enumerator.createCapturer(device, null)
                cap.initialize(helper, context, source.capturerObserver)
                cap.startCapture(1280, 720, 30)
                capturer = cap; surfaceHelper = helper
                val video = RelayWebRtc.factory.createVideoTrack("video0", source)
                localVideoTrack = video
                connection.addTrack(video, listOf("stream0"))
            }
        }
    }

    private suspend fun create(offer: Boolean, restart: Boolean = false): SessionDescription {
        val pc = pc ?: throw IllegalStateException("not started")
        val constraints = MediaConstraints().apply { optional.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true")); if (restart) mandatory.add(MediaConstraints.KeyValuePair("IceRestart", "true")) }
        return suspendCancellableCoroutine { cont ->
            val observer = object : SdpObserver {
                override fun onCreateSuccess(d: SessionDescription) { cont.resume(d) }
                override fun onCreateFailure(e: String?) { cont.resumeWithException(IllegalStateException(e ?: "create failed")) }
                override fun onSetSuccess() {}
                override fun onSetFailure(e: String?) {}
            }
            if (offer) pc.createOffer(observer, constraints) else pc.createAnswer(observer, constraints)
        }
    }

    private suspend fun setLocal(d: SessionDescription) = suspendCancellableCoroutine<Unit> { cont ->
        (pc ?: throw IllegalStateException("not started")).setLocalDescription(object : SdpObserver {
            override fun onSetSuccess() { cont.resume(Unit) }
            override fun onSetFailure(e: String?) { cont.resumeWithException(IllegalStateException(e ?: "setLocal failed")) }
            override fun onCreateSuccess(p0: SessionDescription?) {}
            override fun onCreateFailure(p0: String?) {}
        }, d)
    }

    private suspend fun setRemote(payload: SdpPayload) {
        val pc = pc ?: throw IllegalStateException("not started")
        val d = SessionDescription(if (payload.type == "offer") SessionDescription.Type.OFFER else SessionDescription.Type.ANSWER, payload.sdp)
        suspendCancellableCoroutine<Unit> { cont ->
            pc.setRemoteDescription(object : SdpObserver {
                override fun onSetSuccess() { cont.resume(Unit) }
                override fun onSetFailure(e: String?) { cont.resumeWithException(IllegalStateException(e ?: "setRemote failed")) }
                override fun onCreateSuccess(p0: SessionDescription?) {}
                override fun onCreateFailure(p0: String?) {}
            }, d)
        }
        remoteDescriptionSet = true
        val queued = synchronized(pendingCandidates) { pendingCandidates.toList().also { pendingCandidates.clear() } }
        queued.forEach { pc.addIceCandidate(it) }
    }

    suspend fun createOffer(): SdpPayload { isOfferer = true; val d = create(offer = true); setLocal(d); return SdpPayload("offer", d.description) }
    suspend fun restartIce(): SdpPayload? {
        val pc = pc ?: return null
        if (!canRestartIce || pc.signalingState() != PeerConnection.SignalingState.STABLE) return null
        iceRestarts++
        val d = create(offer = true, restart = true); setLocal(d); remoteDescriptionSet = false
        return SdpPayload("offer", d.description)
    }
    fun noteConnected() { iceRestarts = 0 }
    suspend fun createAnswer(offer: SdpPayload): SdpPayload { setRemote(offer); val d = create(offer = false); setLocal(d); return SdpPayload("answer", d.description) }
    suspend fun acceptAnswer(answer: SdpPayload) { val pc = pc ?: return; if (pc.signalingState() != PeerConnection.SignalingState.HAVE_LOCAL_OFFER) return; setRemote(answer) }
    fun addRemoteIceCandidate(p: IceCandidatePayload) {
        val c = IceCandidate(p.sdpMid, p.sdpMLineIndex ?: 0, p.candidate)
        val pc = pc
        if (pc == null || !remoteDescriptionSet) { synchronized(pendingCandidates) { pendingCandidates.add(c) }; return }
        pc.addIceCandidate(c)
    }
    fun setMicEnabled(enabled: Boolean) { audioTrack?.setEnabled(enabled) }
    fun setCameraEnabled(enabled: Boolean) { localVideoTrack?.setEnabled(enabled) }
    fun close() {
        runCatching { capturer?.stopCapture() }; capturer?.dispose(); capturer = null
        surfaceHelper?.dispose(); surfaceHelper = null
        pc?.close(); pc?.dispose(); pc = null
        audioTrack = null; localVideoTrack = null
        synchronized(pendingCandidates) { pendingCandidates.clear() }
        remoteDescriptionSet = false
    }
}
