package dev.relay.call

import android.content.Context
import android.media.AudioManager
import dev.relay.core.Conversation
import dev.relay.core.RelayClient
import dev.relay.core.RelayEvent
import dev.relay.core.RelayException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.webrtc.PeerConnection
import org.webrtc.VideoTrack

enum class CallPhase { OUTGOING, INCOMING, CONNECTING, ACTIVE, RECONNECTING }

data class ActiveCall(val id: String, val conversationId: String, val peerId: String, val peerName: String?, val type: CallType, val phase: CallPhase, val startedAtMs: Long? = null)

data class CallState(
    val call: ActiveCall? = null,
    val localVideoTrack: VideoTrack? = null,
    val remoteVideoTrack: VideoTrack? = null,
    val micEnabled: Boolean = true,
    val cameraEnabled: Boolean = true,
    val speakerEnabled: Boolean = false,
    val error: String? = null,
)

/**
 * 1:1 call state machine — the same rules as RelayCall (iOS) and @relay/core: one call at a time,
 * events validated against the current callId, buffered early signaling, the answered-elsewhere
 * race excluded for the answering device, ICE restart with a reconnecting grace period.
 *
 *     val calls = CallCenter(context, relay)
 *     calls.start(conversation, CallType.VIDEO)
 *     RelayCallOverlay(calls)   // Compose
 *
 * Ringing while the app is backgrounded (ConnectionService + FCM wake) is a host-app concern for
 * now; see README.
 */
class CallCenter(context: Context, private val client: RelayClient) {
    private val appContext = context.applicationContext
    private val _state = MutableStateFlow(CallState())
    val state: StateFlow<CallState> = _state.asStateFlow()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var manager: PeerConnectionManager? = null
    private var pendingOffer: Pair<String, SdpPayload>? = null
    private val pendingCandidates = mutableListOf<Pair<String, IceCandidatePayload>>()
    private var pendingAcceptRecipient: String? = null
    private var answeringCallId: String? = null
    private var starting = false
    private var ringJob: Job? = null; private var connectJob: Job? = null; private var graceJob: Job? = null; private var restartJob: Job? = null
    private val audioManager get() = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    init {
        scope.launch { client.events.collect { e -> if (e is RelayEvent.Unknown) CallEvent.decode(e.event, e.payload)?.let { handle(it) } } }
    }

    val current get() = _state.value.call
    fun clearError() = _state.update { it.copy(error = null) }

    /**
     * Entry point for an FCM data message (see RelayCallPush / protocol/events.md "Push wake-ups").
     * Returns what happened so the caller (RelayCallService) knows whether to ring. Safe to call
     * from any thread; state changes are applied on the main dispatcher.
     */
    fun handlePush(data: Map<String, String>): CallPush.Outcome {
        val push = CallPush.parse(data) ?: return CallPush.Outcome.IGNORED
        return when (push) {
            is CallPush.Invite -> {
                val cur = current
                if (cur != null || push.callerId == client.userId) return CallPush.Outcome.IGNORED
                setCall { ActiveCall(push.callId, push.conversationId, push.callerId, push.callerName, push.type, CallPhase.INCOMING) }
                // The socket carries the offer/ICE once the user answers — open it now so answering
                // doesn't pay the connect latency (idempotent when already connected). The socket's
                // own call_invite for this callId is ignored because `current` is already set.
                scope.launch { runCatching { client.connect() } }
                scheduleIncomingTimeout(push.callId)
                CallPush.Outcome.RINGING
            }
            is CallPush.Cancel -> {
                val cur = current
                if (cur?.id == push.callId && cur.phase == CallPhase.INCOMING) { cleanup(); CallPush.Outcome.CANCELLED } else CallPush.Outcome.IGNORED
            }
        }
    }

    // A ring started from a push may never see the socket's call_missed (the app can stay
    // disconnected if the user ignores it) — mirror the server's 2-minute ring timeout locally.
    private fun scheduleIncomingTimeout(callId: String) { ringJob?.cancel(); ringJob = scope.launch { delay(120_000); if (current?.id == callId && current?.phase == CallPhase.INCOMING) cleanup() } }
    private fun setCall(change: (ActiveCall?) -> ActiveCall?) = _state.update { it.copy(call = change(it.call)) }
    private fun send(vararg pairs: Pair<String, Any?>) = client.sendFrame(buildJsonObject { pairs.forEach { (k, v) -> when (v) { null -> {}; is String -> put(k, v); is kotlinx.serialization.json.JsonElement -> put(k, v); else -> put(k, v.toString()) } } })
    private fun sdpJson(s: SdpPayload) = buildJsonObject { put("type", s.type); put("sdp", s.sdp) }

    fun start(conversation: Conversation, type: CallType) {
        val peer = conversation.peer ?: return
        if (current != null || starting) return
        starting = true
        scope.launch {
            try {
                val res = try { client.api.startCall(conversation.id, type) } catch (e: RelayException) {
                    _state.update { it.copy(error = if (e.status == 409) "They're already on another call." else "Couldn't start the call. Please try again.") }; return@launch
                } finally { starting = false }
                val callId = res.callId
                // Local state first: the callee may answer/decline during our permission prompt.
                setCall { ActiveCall(callId, conversation.id, peer.userId, peer.displayName, type, CallPhase.OUTGOING) }
                scheduleRing(callId)
                val m = makeManager(callId)
                try {
                    val servers = iceServers()
                    if (current?.id != callId) { m.close(); return@launch }
                    m.start(type, servers)
                } catch (e: Exception) {
                    m.close(); if (current?.id != callId) return@launch
                    runCatching { client.api.endCall(callId, "failed") }
                    _state.update { it.copy(error = "Couldn't start the call — check microphone/camera permissions.") }
                    cleanup(); return@launch
                }
                if (current?.id != callId) { m.close(); return@launch }
                manager = m
                _state.update { it.copy(localVideoTrack = m.localVideoTrack, speakerEnabled = type == CallType.VIDEO) }
                applySpeaker()
                pendingAcceptRecipient?.let { if (current?.phase == CallPhase.CONNECTING) { pendingAcceptRecipient = null; sendOffer(m, callId) } }
            } finally { starting = false }
        }
    }

    fun answer() {
        val cur = current ?: return
        if (cur.phase != CallPhase.INCOMING) return
        val callId = cur.id
        answeringCallId = callId
        scope.launch {
            var m: PeerConnectionManager? = null
            try {
                client.api.answerCall(callId)
                if (current?.id != callId) return@launch
                setCall { it?.copy(phase = CallPhase.CONNECTING) }
                scheduleConnect(callId)
                m = makeManager(callId)
                val servers = iceServers()
                if (current?.id != callId) { m.close(); return@launch }
                m.start(cur.type, servers)
                if (current?.id != callId) { m.close(); return@launch }
                manager = m
                _state.update { it.copy(localVideoTrack = m.localVideoTrack, speakerEnabled = cur.type == CallType.VIDEO) }
                applySpeaker()
                pendingOffer?.takeIf { it.first == callId }?.let { (_, sdp) ->
                    pendingOffer = null
                    val answer = m.createAnswer(sdp)
                    if (current?.id == callId) send("event" to "call_answer_sdp", "callId" to callId, "sdp" to sdpJson(answer))
                }
                drainCandidates(m, callId)
            } catch (e: RelayException) {
                m?.close(); if (current?.id == callId && e.status == 409) cleanup()
                else if (current?.id == callId) { _state.update { it.copy(error = e.message) }; cleanup() }
            } catch (e: Exception) {
                m?.close(); if (current?.id != callId) return@launch
                runCatching { client.api.endCall(callId, "failed") }
                _state.update { it.copy(error = "Couldn't answer the call — check microphone/camera permissions.") }
                cleanup()
            } finally { if (answeringCallId == callId) answeringCallId = null }
        }
    }

    fun decline() { val cur = current ?: return; if (cur.phase != CallPhase.INCOMING) return; scope.launch { runCatching { client.api.declineCall(cur.id) } }; cleanup() }
    fun hangUp() { val cur = current ?: return; scope.launch { runCatching { if (cur.phase == CallPhase.INCOMING) client.api.declineCall(cur.id) else client.api.endCall(cur.id) } }; cleanup() }
    fun toggleMic() { _state.update { it.copy(micEnabled = !it.micEnabled) }; manager?.setMicEnabled(_state.value.micEnabled) }
    fun toggleCamera() { _state.update { it.copy(cameraEnabled = !it.cameraEnabled) }; manager?.setCameraEnabled(_state.value.cameraEnabled) }
    fun toggleSpeaker() { _state.update { it.copy(speakerEnabled = !it.speakerEnabled) }; applySpeaker() }
    private fun applySpeaker() { runCatching { audioManager.mode = AudioManager.MODE_IN_COMMUNICATION; @Suppress("DEPRECATION") audioManager.isSpeakerphoneOn = _state.value.speakerEnabled } }

    private suspend fun iceServers(): List<PeerConnectionManager.IceServer> =
        runCatching { client.api.turnCredentials() }.getOrNull()?.let { listOf(PeerConnectionManager.IceServer(it.urls, it.username, it.credential)) } ?: emptyList()

    private fun makeManager(callId: String): PeerConnectionManager {
        val m = PeerConnectionManager(appContext)
        m.onIceCandidate = { c -> send("event" to "call_ice_candidate", "callId" to callId, "candidate" to buildJsonObject { put("candidate", c.candidate); c.sdpMid?.let { put("sdpMid", it) }; c.sdpMLineIndex?.let { put("sdpMLineIndex", it) } }) }
        m.onRemoteVideoTrack = { t -> scope.launch { if (manager === m) _state.update { it.copy(remoteVideoTrack = t) } } }
        m.onConnectionStateChange = { s -> scope.launch {
            if (manager !== m || current?.id != callId) return@launch
            when (s) {
                PeerConnection.PeerConnectionState.CONNECTED -> { connectJob?.cancel(); graceJob?.cancel(); restartJob?.cancel(); graceJob = null; m.noteConnected(); setCall { it?.copy(phase = CallPhase.ACTIVE, startedAtMs = it.startedAtMs ?: System.currentTimeMillis()) }; applySpeaker() }
                PeerConnection.PeerConnectionState.DISCONNECTED -> connectionLost(callId, false, m)
                PeerConnection.PeerConnectionState.FAILED -> connectionLost(callId, true, m)
                else -> {}
            }
        } }
        return m
    }

    private fun sendOffer(m: PeerConnectionManager, callId: String) = scope.launch {
        val offer = runCatching { m.createOffer() }.getOrNull() ?: return@launch
        if (current?.id == callId) send("event" to "call_offer", "callId" to callId, "sdp" to sdpJson(offer))
    }
    private fun drainCandidates(m: PeerConnectionManager, callId: String) { val mine = synchronized(pendingCandidates) { pendingCandidates.filter { it.first == callId }.also { pendingCandidates.clear() } }; mine.forEach { m.addRemoteIceCandidate(it.second) } }

    private fun connectionLost(callId: String, failed: Boolean, m: PeerConnectionManager) {
        val cur = current ?: return
        if (cur.id != callId) return
        if (cur.phase != CallPhase.ACTIVE && cur.phase != CallPhase.RECONNECTING) { if (failed && !m.canRestartIce) fail(callId, "Call failed to connect — check your network."); return }
        if (cur.phase == CallPhase.ACTIVE) setCall { it?.copy(phase = CallPhase.RECONNECTING) }
        if (graceJob == null) graceJob = scope.launch { delay(20_000); if (current?.id == callId && current?.phase == CallPhase.RECONNECTING) fail(callId, "Call dropped — check your network.") }
        if (!m.canRestartIce) return
        restartJob?.cancel()
        restartJob = scope.launch { if (!failed) delay(3_000); if (current?.id != callId || m.connectionState == PeerConnection.PeerConnectionState.CONNECTED) return@launch
            runCatching { m.restartIce() }.getOrNull()?.let { send("event" to "call_offer", "callId" to callId, "sdp" to sdpJson(it)) } }
    }

    private fun fail(callId: String, message: String) { scope.launch { runCatching { client.api.endCall(callId, "failed") } }; _state.update { it.copy(error = message) }; cleanup() }
    private fun scheduleRing(callId: String) { ringJob?.cancel(); ringJob = scope.launch { delay(45_000); if (current?.id == callId && current?.phase == CallPhase.OUTGOING) { runCatching { client.api.endCall(callId, "timeout") }; cleanup() } } }
    private fun scheduleConnect(callId: String) { connectJob?.cancel(); connectJob = scope.launch { delay(25_000); if (current?.id == callId && current?.phase == CallPhase.CONNECTING) fail(callId, "Call failed to connect — check your network.") } }

    private fun cleanup() {
        listOf(ringJob, connectJob, graceJob, restartJob).forEach { it?.cancel() }; ringJob = null; connectJob = null; graceJob = null; restartJob = null
        manager?.close(); manager = null
        pendingOffer = null; synchronized(pendingCandidates) { pendingCandidates.clear() }; pendingAcceptRecipient = null; answeringCallId = null
        runCatching { audioManager.mode = AudioManager.MODE_NORMAL; @Suppress("DEPRECATION") audioManager.isSpeakerphoneOn = false }
        _state.update { CallState(error = it.error) }
    }

    private fun handle(e: CallEvent) {
        val cur = current
        when (e) {
            is CallEvent.Invite -> if (e.callerId != client.userId && cur == null) setCall { ActiveCall(e.callId, e.conversationId, e.callerId, e.callerName, e.type, CallPhase.INCOMING) }
            is CallEvent.Accepted -> {
                if (cur?.id == e.callId && cur.phase == CallPhase.INCOMING && e.acceptedBy == client.userId && answeringCallId != e.callId) { cleanup(); return }
                if (cur?.id != e.callId || cur.phase != CallPhase.OUTGOING) return
                ringJob?.cancel()
                setCall { it?.copy(phase = CallPhase.CONNECTING) }
                scheduleConnect(e.callId)
                val m = manager
                if (m == null) pendingAcceptRecipient = e.acceptedBy else sendOffer(m, e.callId)
            }
            is CallEvent.Offer -> { val m = manager; if (m == null || cur?.id != e.callId) { pendingOffer = e.callId to e.sdp; return }
                scope.launch { val a = runCatching { m.createAnswer(e.sdp) }.getOrNull() ?: return@launch; if (current?.id == e.callId) send("event" to "call_answer_sdp", "callId" to e.callId, "sdp" to sdpJson(a)) } }
            is CallEvent.AnswerSdp -> if (cur?.id == e.callId) scope.launch { runCatching { manager?.acceptAnswer(e.sdp) } }
            is CallEvent.Ice -> { val m = manager; if (m != null && cur?.id == e.callId) m.addRemoteIceCandidate(e.candidate)
                else if (cur == null || cur.id == e.callId) synchronized(pendingCandidates) { if (pendingCandidates.size < 64) pendingCandidates.add(e.callId to e.candidate) } }
            is CallEvent.Declined -> if (cur?.id == e.callId) cleanup()
            is CallEvent.Missed -> if (cur?.id == e.callId) cleanup()
            is CallEvent.Ended -> if (cur?.id == e.callId) cleanup()
        }
    }
}
