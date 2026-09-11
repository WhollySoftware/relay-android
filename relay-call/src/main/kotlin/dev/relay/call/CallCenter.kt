package dev.relay.call

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioManager
import androidx.core.content.ContextCompat
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
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.webrtc.PeerConnection
import org.webrtc.VideoTrack

enum class CallPhase { OUTGOING, INCOMING, CONNECTING, ACTIVE, RECONNECTING }

data class ActiveCall(
    val id: String, val conversationId: String, val peerId: String, val peerName: String?, val type: CallType, val phase: CallPhase, val startedAtMs: Long? = null,
    /** Group calls (mesh, up to 6) are the N>1 case of the same state machine — see protocol/events.md. */
    val isGroup: Boolean = false,
    /** Everyone currently invited/joined (including self), kept in sync as people join/leave/decline. Always [peerId] for a 1:1 call. */
    val participantIds: List<String> = emptyList(),
)

/** One remote participant's reported mic/camera state, for a group call. */
data class RemoteParticipantMedia(val micEnabled: Boolean = true, val cameraEnabled: Boolean = true)

data class CallState(
    val call: ActiveCall? = null,
    val localVideoTrack: VideoTrack? = null,
    /** 1:1 only — the one remote video track. For a group call use [remoteVideoTracks]. */
    val remoteVideoTrack: VideoTrack? = null,
    /** Every remote participant's video track, keyed by userId. Has exactly one entry for a 1:1 call. */
    val remoteVideoTracks: Map<String, VideoTrack> = emptyMap(),
    val micEnabled: Boolean = true,
    val cameraEnabled: Boolean = true,
    val speakerEnabled: Boolean = false,
    // The PEER's reported mic/camera state (call_media_state) — a disabled camera still sends
    // frames (all black), so the UI uses this rather than "is there a remote track" to decide
    // when to show the peer's avatar instead of a black rectangle. 1:1 only.
    val remoteMicEnabled: Boolean = true,
    val remoteCameraEnabled: Boolean = true,
    /** Per-participant mic/camera, for a group call. Empty for 1:1 — use the scalars above. */
    val remoteParticipantMedia: Map<String, RemoteParticipantMedia> = emptyMap(),
    // Whether OUR OWN mic/camera is unavailable because the OS permission is denied — computed
    // once from the current permission state when the call starts/is answered (not a live
    // permission-change listener). The peer can still be heard/seen fine (see LocalMedia); this
    // just drives the "they can't hear/see you" banner in CallUi.
    val localMicPermissionDenied: Boolean = false,
    val localCameraPermissionDenied: Boolean = false,
    val error: String? = null,
)

/**
 * Call state machine — the same rules as RelayCall (iOS) and @relay/core: one call at a time,
 * events validated against the current callId, buffered early signaling, the answered-elsewhere
 * race excluded for the answering device, ICE restart with a reconnecting grace period. A 1:1 call
 * is simply the N=1 case of the same multi-peer (mesh) machinery used for group calls.
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
    /** One PeerConnectionManager per remote participant, keyed by their userId. A 1:1 call has
     *  exactly one entry (keyed by peerId). All entries for a call share [localMedia]. */
    private val peers = mutableMapOf<String, PeerConnectionManager>()
    /** The one mic/camera acquisition for the current call, shared across every peer (see LocalMedia). */
    private var localMedia: LocalMedia? = null
    private var pendingOffer: Pair<String, SdpPayload>? = null
    /** ICE candidates that arrived before their sender's PeerConnectionManager existed: (callId, senderId, candidate). */
    private val pendingCandidates = mutableListOf<Triple<String, String, IceCandidatePayload>>()
    private var pendingAcceptRecipient: String? = null
    private var answeringCallId: String? = null
    private var starting = false
    private var ringJob: Job? = null; private var connectJob: Job? = null; private var graceJob: Job? = null; private var restartJob: Job? = null
    private val audioManager get() = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    /** Own display name for the self-view fallback (CallUi.kt) when the local camera is off. */
    val myDisplayName: String? get() = client.me?.displayName
    /** Own userId, for filtering self out of a group call's participant grid (CallUi.kt). */
    val myUserId: String? get() = client.userId

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
    /** Current denial for this permission, checked fresh (not cached) each time a call starts/is
     *  answered — see [CallState.localMicPermissionDenied]. */
    private fun permissionDenied(permission: String) = ContextCompat.checkSelfPermission(appContext, permission) != PackageManager.PERMISSION_GRANTED
    private fun send(vararg pairs: Pair<String, Any?>) = client.sendFrame(buildJsonObject { pairs.forEach { (k, v) -> when (v) { null -> {}; is String -> put(k, v); is JsonElement -> put(k, v); else -> put(k, v.toString()) } } })
    private fun sdpJson(s: SdpPayload) = buildJsonObject { put("type", s.type); put("sdp", s.sdp) }

    /** Send a signaling frame for one specific peer — `targetUserId` is only attached for a group
     *  call; a 1:1 frame stays exactly as it was before group calls existed (protocol/events.md). */
    private fun sendSignal(event: String, payloadKey: String, callId: String, userId: String, payload: JsonElement) {
        val pairs = mutableListOf<Pair<String, Any?>>("event" to event, "callId" to callId)
        if (current?.isGroup == true) pairs.add("targetUserId" to userId)
        pairs.add(payloadKey to payload)
        send(*pairs.toTypedArray())
    }

    fun start(conversation: Conversation, type: CallType) {
        if (current != null || starting) return
        val isGroup = conversation.isGroup
        val peer = conversation.peer
        if (!isGroup && peer == null) return
        starting = true
        scope.launch {
            try {
                // Otherwise the caller sits at "Calling…" while the callee's side connects, times
                // out, and hangs up on a peer that never heard a thing — the offer/ICE exchange
                // rides the gateway socket, not this REST call. Idempotent/instant if already
                // connected.
                runCatching { client.connect() }
                val res = try { client.api.startCall(conversation.id, type) } catch (e: RelayException) {
                    _state.update { it.copy(error = if (e.status == 409) "They're already on another call." else "Couldn't start the call. Please try again.") }; return@launch
                } finally { starting = false }
                val callId = res.callId
                // For a group call the REST response has no participant list (server code never
                // returns one for /calls) — the conversation's already-known member list is exactly
                // who was just invited, so use that immediately rather than waiting on anything.
                val me = client.userId
                val participantIds = if (isGroup) (listOfNotNull(me) + conversation.members.map { it.userId }.filterNot { it == me }).distinct()
                    else listOfNotNull(peer?.userId)
                val displayPeerId = peer?.userId ?: participantIds.firstOrNull { it != me } ?: ""
                // Local state first: the callee(s) may answer/decline during our permission prompt.
                setCall { ActiveCall(callId, conversation.id, displayPeerId, peer?.displayName, type, CallPhase.OUTGOING, isGroup = isGroup, participantIds = participantIds) }
                scheduleRing(callId)
                if (!isGroup) {
                    val m = makeManager(callId, displayPeerId)
                    try {
                        val lm = LocalMedia.acquire(appContext, type)
                        val servers = iceServers()
                        if (current?.id != callId) { m.close(); lm.close(); return@launch }
                        localMedia = lm
                        m.start(lm, servers)
                    } catch (e: Exception) {
                        m.close(); if (current?.id != callId) return@launch
                        runCatching { client.api.endCall(callId, "failed") }
                        _state.update { it.copy(error = "Couldn't start the call — check microphone/camera permissions.") }
                        cleanup(); return@launch
                    }
                    if (current?.id != callId) { m.close(); return@launch }
                    peers[displayPeerId] = m
                    _state.update { it.copy(localVideoTrack = localMedia?.videoTrack, speakerEnabled = type == CallType.VIDEO, localMicPermissionDenied = permissionDenied(Manifest.permission.RECORD_AUDIO), localCameraPermissionDenied = type == CallType.VIDEO && permissionDenied(Manifest.permission.CAMERA)) }
                    applySpeaker()
                    pendingAcceptRecipient?.let { if (current?.phase == CallPhase.CONNECTING) { pendingAcceptRecipient = null; sendOffer(m, callId, displayPeerId) } }
                } else {
                    // A group call has nobody to offer to yet — only the caller has joined so far;
                    // peer connections form once someone else joins and offers to us (see answer(),
                    // "newest-joiner-initiates"). We still acquire local media eagerly so it's ready
                    // the instant the first offer arrives.
                    try {
                        val lm = LocalMedia.acquire(appContext, type)
                        if (current?.id != callId) { lm.close(); return@launch }
                        localMedia = lm
                        _state.update { it.copy(localVideoTrack = lm.videoTrack, speakerEnabled = type == CallType.VIDEO, localMicPermissionDenied = permissionDenied(Manifest.permission.RECORD_AUDIO), localCameraPermissionDenied = type == CallType.VIDEO && permissionDenied(Manifest.permission.CAMERA)) }
                        applySpeaker()
                    } catch (e: Exception) {
                        if (current?.id != callId) return@launch
                        runCatching { client.api.endCall(callId, "failed") }
                        _state.update { it.copy(error = "Couldn't start the call — check microphone/camera permissions.") }
                        cleanup(); return@launch
                    }
                }
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
                // Must happen before the REST answer call below — answering immediately prompts
                // the caller to start sending its SDP offer + ICE candidates over the socket, and
                // if this device was woken from a fully backgrounded/killed state via FCM, its own
                // socket hasn't necessarily reconnected yet. Without this, that race can lose the
                // entire signaling exchange to a gateway channel we weren't subscribed to yet.
                // Idempotent/instant if already connected.
                runCatching { client.connect() }
                val answerRes = client.api.answerCallWithParticipants(callId)
                if (current?.id != callId) return@launch
                setCall { it?.copy(phase = CallPhase.CONNECTING) }
                if (!cur.isGroup) {
                    scheduleConnect(callId)
                    m = makeManager(callId, cur.peerId)
                    val mgr = m
                    val lm = LocalMedia.acquire(appContext, cur.type)
                    val servers = iceServers()
                    if (current?.id != callId) { mgr.close(); lm.close(); return@launch }
                    localMedia = lm
                    mgr.start(lm, servers)
                    if (current?.id != callId) { mgr.close(); return@launch }
                    peers[cur.peerId] = mgr
                    _state.update { it.copy(localVideoTrack = lm.videoTrack, speakerEnabled = cur.type == CallType.VIDEO, localMicPermissionDenied = permissionDenied(Manifest.permission.RECORD_AUDIO), localCameraPermissionDenied = cur.type == CallType.VIDEO && permissionDenied(Manifest.permission.CAMERA)) }
                    applySpeaker()
                    pendingOffer?.takeIf { it.first == callId }?.let { (_, sdp) ->
                        pendingOffer = null
                        val answer = mgr.createAnswer(sdp)
                        if (current?.id == callId) sendSignal("call_answer_sdp", "sdp", callId, cur.peerId, sdpJson(answer))
                    }
                    drainCandidates(mgr, callId, cur.peerId)
                } else {
                    // Group call, newest-joiner-initiates: offer to every already-joined participant
                    // (not us) — the ones who join AFTER us are responsible for offering to US.
                    val lm = LocalMedia.acquire(appContext, cur.type)
                    if (current?.id != callId) { lm.close(); return@launch }
                    localMedia = lm
                    _state.update { it.copy(localVideoTrack = lm.videoTrack, speakerEnabled = cur.type == CallType.VIDEO, localMicPermissionDenied = permissionDenied(Manifest.permission.RECORD_AUDIO), localCameraPermissionDenied = cur.type == CallType.VIDEO && permissionDenied(Manifest.permission.CAMERA)) }
                    applySpeaker()
                    val servers = iceServers()
                    if (current?.id != callId) return@launch
                    val myId = client.userId
                    val already = answerRes.participants.filter { it.userId != myId && it.joinedAt != null && it.leftAt == null }
                    for (p in already) {
                        if (current?.id != callId) return@launch
                        val pm = makeManager(callId, p.userId)
                        peers[p.userId] = pm
                        try {
                            pm.start(lm, servers)
                            val offer = pm.createOffer()
                            if (current?.id == callId) sendSignal("call_offer", "sdp", callId, p.userId, sdpJson(offer))
                        } catch (e: Exception) { peers.remove(p.userId); pm.close() }
                    }
                }
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
    fun toggleMic() {
        _state.update { it.copy(micEnabled = !it.micEnabled) }
        localMedia?.setMicEnabled(_state.value.micEnabled)
        current?.let { send("event" to "call_media_state", "callId" to it.id, "micEnabled" to JsonPrimitive(_state.value.micEnabled)) }
    }
    fun toggleCamera() {
        _state.update { it.copy(cameraEnabled = !it.cameraEnabled) }
        localMedia?.setCameraEnabled(_state.value.cameraEnabled)
        current?.let { send("event" to "call_media_state", "callId" to it.id, "cameraEnabled" to JsonPrimitive(_state.value.cameraEnabled)) }
    }
    fun toggleSpeaker() { _state.update { it.copy(speakerEnabled = !it.speakerEnabled) }; applySpeaker() }
    private fun applySpeaker() { runCatching { audioManager.mode = AudioManager.MODE_IN_COMMUNICATION; @Suppress("DEPRECATION") audioManager.isSpeakerphoneOn = _state.value.speakerEnabled } }

    private suspend fun iceServers(): List<PeerConnectionManager.IceServer> =
        runCatching { client.api.turnCredentials() }.getOrNull()?.let { listOf(PeerConnectionManager.IceServer(it.urls, it.username, it.credential)) } ?: emptyList()

    private fun makeManager(callId: String, userId: String): PeerConnectionManager {
        val m = PeerConnectionManager(appContext)
        m.onIceCandidate = { c -> sendSignal("call_ice_candidate", "candidate", callId, userId, buildJsonObject { put("candidate", c.candidate); c.sdpMid?.let { put("sdpMid", it) }; c.sdpMLineIndex?.let { put("sdpMLineIndex", it) } }) }
        m.onRemoteVideoTrack = { t -> scope.launch {
            if (peers[userId] !== m) return@launch
            _state.update { st ->
                val tracks = st.remoteVideoTracks + (userId to t)
                st.copy(remoteVideoTracks = tracks, remoteVideoTrack = if (current?.isGroup == true) st.remoteVideoTrack else t)
            }
        } }
        m.onConnectionStateChange = { s -> scope.launch {
            if (peers[userId] !== m || current?.id != callId) return@launch
            when (s) {
                PeerConnection.PeerConnectionState.CONNECTED -> {
                    val isGroup = current?.isGroup == true
                    m.noteConnected()
                    if (!isGroup) { connectJob?.cancel(); graceJob?.cancel(); restartJob?.cancel(); graceJob = null }
                    else connectJob?.cancel()
                    if (current?.phase != CallPhase.ACTIVE) setCall { it?.copy(phase = CallPhase.ACTIVE, startedAtMs = it.startedAtMs ?: System.currentTimeMillis()) }
                    applySpeaker()
                }
                PeerConnection.PeerConnectionState.DISCONNECTED -> connectionLost(callId, false, m, userId)
                PeerConnection.PeerConnectionState.FAILED -> connectionLost(callId, true, m, userId)
                else -> {}
            }
        } }
        return m
    }

    private fun sendOffer(m: PeerConnectionManager, callId: String, userId: String) = scope.launch {
        val offer = runCatching { m.createOffer() }.getOrNull() ?: return@launch
        if (current?.id == callId) sendSignal("call_offer", "sdp", callId, userId, sdpJson(offer))
    }
    private fun drainCandidates(m: PeerConnectionManager, callId: String, senderId: String) {
        val mine = synchronized(pendingCandidates) {
            val matched = pendingCandidates.filter { it.first == callId && it.second == senderId }
            pendingCandidates.removeAll(matched)
            matched
        }
        mine.forEach { m.addRemoteIceCandidate(it.third) }
    }

    private fun connectionLost(callId: String, failed: Boolean, m: PeerConnectionManager, userId: String) {
        val cur = current ?: return
        if (cur.id != callId) return
        if (cur.isGroup) {
            // A dropped peer just loses that one tile — it doesn't end the call for everyone
            // (call_participant_left / call_end handle that); only give up on this leg once ICE
            // restart is exhausted.
            if (!m.canRestartIce) {
                peers.remove(userId)?.close()
                _state.update { it.copy(remoteVideoTracks = it.remoteVideoTracks - userId, remoteParticipantMedia = it.remoteParticipantMedia - userId) }
            }
            return
        }
        if (cur.phase != CallPhase.ACTIVE && cur.phase != CallPhase.RECONNECTING) { if (failed && !m.canRestartIce) fail(callId, "Call failed to connect — check your network."); return }
        if (cur.phase == CallPhase.ACTIVE) setCall { it?.copy(phase = CallPhase.RECONNECTING) }
        if (graceJob == null) graceJob = scope.launch { delay(20_000); if (current?.id == callId && current?.phase == CallPhase.RECONNECTING) fail(callId, "Call dropped — check your network.") }
        if (!m.canRestartIce) return
        restartJob?.cancel()
        restartJob = scope.launch { if (!failed) delay(3_000); if (current?.id != callId || m.connectionState == PeerConnection.PeerConnectionState.CONNECTED) return@launch
            runCatching { m.restartIce() }.getOrNull()?.let { sendSignal("call_offer", "sdp", callId, userId, sdpJson(it)) } }
    }

    private fun fail(callId: String, message: String) { scope.launch { runCatching { client.api.endCall(callId, "failed") } }; _state.update { it.copy(error = message) }; cleanup() }
    private fun scheduleRing(callId: String) { ringJob?.cancel(); ringJob = scope.launch { delay(45_000); if (current?.id == callId && current?.phase == CallPhase.OUTGOING) { runCatching { client.api.endCall(callId, "timeout") }; cleanup() } } }
    private fun scheduleConnect(callId: String) { connectJob?.cancel(); connectJob = scope.launch { delay(25_000); if (current?.id == callId && current?.phase == CallPhase.CONNECTING) fail(callId, "Call failed to connect — check your network.") } }

    private fun cleanup() {
        listOf(ringJob, connectJob, graceJob, restartJob).forEach { it?.cancel() }; ringJob = null; connectJob = null; graceJob = null; restartJob = null
        peers.values.forEach { it.close() }; peers.clear()
        localMedia?.close(); localMedia = null
        pendingOffer = null; synchronized(pendingCandidates) { pendingCandidates.clear() }; pendingAcceptRecipient = null; answeringCallId = null
        runCatching { audioManager.mode = AudioManager.MODE_NORMAL; @Suppress("DEPRECATION") audioManager.isSpeakerphoneOn = false }
        _state.update { CallState(error = it.error) }
    }

    private fun handle(e: CallEvent) {
        val cur = current
        when (e) {
            is CallEvent.Invite -> if (e.callerId != client.userId && cur == null) setCall { ActiveCall(e.callId, e.conversationId, e.callerId, e.callerName, e.type, CallPhase.INCOMING, isGroup = e.isGroup, participantIds = e.participantIds) }
            is CallEvent.Accepted -> {
                // 1:1-only (a group call's per-member join uses call_participant_joined instead).
                if (cur == null || cur.isGroup) return
                if (cur.id == e.callId && cur.phase == CallPhase.INCOMING && e.acceptedBy == client.userId && answeringCallId != e.callId) { cleanup(); return }
                if (cur.id != e.callId || cur.phase != CallPhase.OUTGOING) return
                ringJob?.cancel()
                setCall { it?.copy(phase = CallPhase.CONNECTING) }
                scheduleConnect(e.callId)
                val m = peers[cur.peerId]
                if (m == null) pendingAcceptRecipient = e.acceptedBy else sendOffer(m, e.callId, cur.peerId)
            }
            is CallEvent.Offer -> {
                val senderId = e.senderId ?: return
                val existing = peers[senderId]
                if (existing != null) {
                    scope.launch { val a = runCatching { existing.createAnswer(e.sdp) }.getOrNull() ?: return@launch; if (current?.id == e.callId) sendSignal("call_answer_sdp", "sdp", e.callId, senderId, sdpJson(a)) }
                    return
                }
                if (cur?.isGroup == true && cur.id == e.callId) {
                    // Newest-joiner-initiates growth: the first call_offer from a sender we've never
                    // seen means someone new joined and is offering to us — create their manager now.
                    val lm = localMedia ?: return
                    scope.launch {
                        val servers = iceServers()
                        if (current?.id != e.callId || peers.containsKey(senderId)) return@launch
                        val m = makeManager(e.callId, senderId)
                        peers[senderId] = m
                        try {
                            m.start(lm, servers)
                            val a = m.createAnswer(e.sdp)
                            if (current?.id == e.callId) sendSignal("call_answer_sdp", "sdp", e.callId, senderId, sdpJson(a))
                            drainCandidates(m, e.callId, senderId)
                        } catch (ex: Exception) { peers.remove(senderId); m.close() }
                    }
                    return
                }
                // 1:1: no manager yet (still ringing, before answer() runs) — buffer for drainPending().
                if (cur == null || cur.id == e.callId) pendingOffer = e.callId to e.sdp
            }
            is CallEvent.AnswerSdp -> { val senderId = e.senderId ?: return; if (cur?.id == e.callId) scope.launch { runCatching { peers[senderId]?.acceptAnswer(e.sdp) } } }
            is CallEvent.Ice -> {
                val senderId = e.senderId ?: return
                val m = peers[senderId]
                if (m != null && cur?.id == e.callId) m.addRemoteIceCandidate(e.candidate)
                else if (cur == null || cur.id == e.callId) synchronized(pendingCandidates) { if (pendingCandidates.size < 64) pendingCandidates.add(Triple(e.callId, senderId, e.candidate)) }
            }
            is CallEvent.Declined -> if (cur?.id == e.callId) cleanup()
            is CallEvent.Missed -> if (cur?.id == e.callId) cleanup()
            is CallEvent.Ended -> if (cur?.id == e.callId) cleanup()
            is CallEvent.MediaState -> if (cur?.id == e.callId) {
                // Each toggle sends only the ONE field that changed — the other is null on this
                // event, not false — so each side is applied independently or a mic-only update
                // would wrongly stomp remoteCameraEnabled (or vice versa).
                if (cur.isGroup) {
                    val senderId = e.senderId ?: return
                    _state.update { st ->
                        val prev = st.remoteParticipantMedia[senderId] ?: RemoteParticipantMedia()
                        val next = prev.copy(cameraEnabled = e.cameraEnabled ?: prev.cameraEnabled, micEnabled = e.micEnabled ?: prev.micEnabled)
                        st.copy(remoteParticipantMedia = st.remoteParticipantMedia + (senderId to next))
                    }
                } else {
                    _state.update {
                        it.copy(
                            remoteCameraEnabled = e.cameraEnabled ?: it.remoteCameraEnabled,
                            remoteMicEnabled = e.micEnabled ?: it.remoteMicEnabled,
                        )
                    }
                }
            }
            is CallEvent.ParticipantJoined -> if (cur?.id == e.callId) setCall { it?.copy(participantIds = e.participantIds) }
            is CallEvent.ParticipantDeclined -> if (cur?.id == e.callId) setCall { it?.copy(participantIds = it.participantIds.filter { id -> id != e.userId }) }
            is CallEvent.ParticipantLeft -> if (cur?.id == e.callId) {
                peers.remove(e.userId)?.close()
                _state.update { it.copy(remoteVideoTracks = it.remoteVideoTracks - e.userId, remoteParticipantMedia = it.remoteParticipantMedia - e.userId) }
                setCall { it?.copy(participantIds = it.participantIds.filter { id -> id != e.userId }) }
            }
        }
    }
}
