package dev.relay.call

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.VideocamOff
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.relay.core.Conversation
import kotlinx.coroutines.delay
import org.webrtc.RendererCommon
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoTrack

/** Mount once at your root: incoming banner, full-screen call view and error toast. */
@Composable
fun RelayCallOverlay(center: CallCenter) {
    val state by center.state.collectAsStateWithLifecycle()
    val call = state.call
    Box(Modifier.fillMaxSize()) {
        when (call?.phase) {
            null -> {}
            CallPhase.INCOMING -> IncomingCallBanner(center, call, Modifier.align(Alignment.TopCenter))
            else -> if (call.isGroup) GroupCallScreen(center, state, call) else CallScreen(center, state, call)
        }
        state.error?.let { msg ->
            Surface(Modifier.align(Alignment.TopCenter).padding(12.dp).clickable { center.clearError() }, color = MaterialTheme.colorScheme.errorContainer, shape = RoundedCornerShape(10.dp)) {
                Text(msg, Modifier.padding(10.dp), color = MaterialTheme.colorScheme.onErrorContainer)
            }
        }
    }
}

/** Mic (and, for a video call, camera) permissions needed to actually place/answer a call. */
private fun permissionsFor(type: CallType): List<String> = buildList {
    add(Manifest.permission.RECORD_AUDIO)
    if (type == CallType.VIDEO) add(Manifest.permission.CAMERA)
}

/** A launcher that requests whatever of [permissionsFor] `type` isn't already granted, then runs
 *  [onGranted] — either immediately (already granted) or once the system prompt resolves. */
@Composable
private fun rememberCallAction(onGranted: (CallType) -> Unit): (CallType) -> Unit {
    val context = LocalContext.current
    val keyboardController = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    var pending by remember { mutableStateOf<CallType?>(null) }
    var showSettingsPrompt by remember { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
        val type = pending; pending = null
        when {
            type == null -> {}
            results.values.all { it } -> onGranted(type)
            // The system only shows the request dialog again if the user hasn't permanently
            // denied it ("Don't allow" a second time, or the very first time on some OEMs/API
            // levels). If it's still missing after asking, there's no dialog left to show —
            // the only way forward is the app's own permission screen in system Settings.
            else -> showSettingsPrompt = true
        }
    }
    if (showSettingsPrompt) {
        PermissionSettingsDialog(onDismiss = { showSettingsPrompt = false })
    }
    return { type ->
        // The keyboard racing the call UI onto screen looks broken (it covers the local-preview
        // thumbnail and eats half the call screen on smaller devices) — dismiss it before either
        // the permission prompt or the call itself can appear.
        keyboardController?.hide()
        val missing = permissionsFor(type).filter { ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isEmpty()) onGranted(type) else { pending = type; launcher.launch(missing.toTypedArray()) }
    }
}

/** Shared by [PermissionSettingsDialog] and the in-call permission banner. */
private fun openAppSettings(context: android.content.Context) {
    context.startActivity(
        android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.fromParts("package", context.packageName, null))
            .apply { addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK) },
    )
}

@Composable
private fun PermissionSettingsDialog(onDismiss: () -> Unit, message: String = "Camera and microphone access are needed for calls. Enable them in Settings to continue.") {
    val context = androidx.compose.ui.platform.LocalContext.current
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Permission needed") },
        text = { Text(message) },
        confirmButton = {
            androidx.compose.material3.TextButton(onClick = { onDismiss(); openAppSettings(context) }) { Text("Open Settings") }
        },
        dismissButton = { androidx.compose.material3.TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** A small persistent pill shown in-call when our own mic/camera is unusable because the OS
 *  permission is denied — the peer still comes through fine (see LocalMedia), only OUR outgoing
 *  media is missing. Unlike [PermissionSettingsDialog] this never blocks the call controls. */
@Composable
private fun PermissionDeniedBanner(micDenied: Boolean, cameraDenied: Boolean, modifier: Modifier = Modifier) {
    if (!micDenied && !cameraDenied) return
    val context = LocalContext.current
    val what = if (micDenied && cameraDenied) "Microphone/camera" else if (micDenied) "Microphone" else "Camera"
    val verb = if (micDenied && cameraDenied) "hear/see" else if (micDenied) "hear" else "see"
    Row(
        modifier.clip(RoundedCornerShape(999.dp)).background(Color.Black.copy(alpha = 0.6f)).padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("$what access needed — you can $verb the other person, but they can't $verb you.", color = Color.White, style = MaterialTheme.typography.labelSmall, modifier = Modifier.weight(1f, fill = false))
        Text("Open Settings", color = Color(0xFF60A5FA), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, modifier = Modifier.clickable { openAppSettings(context) })
    }
}

/** Requests whatever mic/camera permission is missing (so the OS prompt can still appear, and if
 *  granted right then it gets used) but — unlike [rememberCallAction] — never gates on the
 *  result: an incoming call must always be answerable, even fully denied, since the peer can
 *  still be heard/seen (see LocalMedia / PeerConnectionManager). */
@Composable
private fun rememberAnswerAction(center: CallCenter): (CallType) -> Unit {
    val context = LocalContext.current
    val keyboardController = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { /* answer() already ran regardless of the outcome */ }
    return { type ->
        keyboardController?.hide()
        val missing = permissionsFor(type).filter { ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) launcher.launch(missing.toTypedArray())
        center.answer()
    }
}

@Composable
fun IncomingCallBanner(center: CallCenter, call: ActiveCall, modifier: Modifier = Modifier) {
    val answer = rememberAnswerAction(center)
    Surface(modifier.padding(12.dp).fillMaxWidth(), shape = RoundedCornerShape(16.dp), tonalElevation = 6.dp, shadowElevation = 6.dp) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(call.peerName ?: call.peerId, fontWeight = FontWeight.Bold)
                Text("Incoming ${call.type.wire} call…", style = MaterialTheme.typography.bodySmall)
            }
            RoundButton(Icons.Filled.Close, "Decline", Color(0xFFDC2626)) { center.decline() }
            Spacer(Modifier.width(8.dp))
            RoundButton(if (call.type == CallType.VIDEO) Icons.Filled.Videocam else Icons.Filled.Call, "Answer", Color(0xFF22C55E)) { answer(call.type) }
        }
    }
}

@Composable
fun CallScreen(center: CallCenter, state: CallState, call: ActiveCall) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(call.phase) { while (true) { delay(1000); now = System.currentTimeMillis() } }
    val status = when (call.phase) {
        CallPhase.OUTGOING -> "Calling…"; CallPhase.CONNECTING -> "Connecting…"; CallPhase.RECONNECTING -> "Reconnecting…"
        else -> call.startedAtMs?.let { val s = ((now - it) / 1000).coerceAtLeast(0); "%02d:%02d".format(s / 60, s % 60) } ?: ""
    }
    // A disabled remote camera still sends frames (all black), so remoteCameraEnabled — not just
    // "is there a remote track" — decides whether to show video or fall back to the avatar.
    val showRemoteVideo = call.type == CallType.VIDEO && state.remoteVideoTrack != null && state.remoteCameraEnabled
    Box(Modifier.fillMaxSize().background(Color(0xFF12182A))) {
        if (showRemoteVideo) VideoView(state.remoteVideoTrack!!, Modifier.fillMaxSize())
        else Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.size(112.dp).clip(CircleShape).background(Color.Gray.copy(alpha = 0.5f)), contentAlignment = Alignment.Center) { Text((call.peerName ?: call.peerId).take(2).uppercase(), color = Color.White, style = MaterialTheme.typography.headlineMedium) }
            Spacer(Modifier.height(12.dp)); Text(call.peerName ?: call.peerId, color = Color.White, style = MaterialTheme.typography.titleLarge)
        }
        if (!state.remoteMicEnabled) {
            Row(
                Modifier.align(Alignment.TopStart).windowInsetsPadding(WindowInsets.statusBars).padding(16.dp).clip(RoundedCornerShape(999.dp)).background(Color.Black.copy(alpha = 0.45f)).padding(horizontal = 10.dp, vertical = 5.dp),
                horizontalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                Icon(Icons.Filled.MicOff, "Their microphone is muted", Modifier.size(14.dp), tint = Color.White)
                Text("Muted", color = Color.White, style = MaterialTheme.typography.labelSmall)
            }
        }
        Text(status, Modifier.align(Alignment.TopCenter).windowInsetsPadding(WindowInsets.statusBars).padding(top = 16.dp), color = if (call.phase == CallPhase.RECONNECTING) Color.Yellow else Color.White.copy(alpha = 0.75f))
        PermissionDeniedBanner(state.localMicPermissionDenied, state.localCameraPermissionDenied, Modifier.align(Alignment.TopCenter).windowInsetsPadding(WindowInsets.statusBars).padding(top = 44.dp, start = 24.dp, end = 24.dp))
        // The renderer stays mounted (not conditionally) when the camera is off, so its track
        // assignment survives the toggle — only the overlay changes. Without this the box shows a
        // stale black frame instead of your own avatar when you turn your camera off mid-call.
        if (call.type == CallType.VIDEO) state.localVideoTrack?.let { track ->
            Box(Modifier.align(Alignment.BottomEnd).padding(16.dp, 0.dp, 16.dp, 120.dp).size(100.dp, 150.dp).clip(RoundedCornerShape(14.dp)).background(Color(0xFF1D2540))) {
                VideoView(track, Modifier.fillMaxSize().alpha(if (state.cameraEnabled) 1f else 0f), mirror = true)
                if (!state.cameraEnabled) {
                    Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(Modifier.size(36.dp).clip(CircleShape).background(Color.Gray.copy(alpha = 0.5f)), contentAlignment = Alignment.Center) {
                            Text((center.myDisplayName ?: "You").take(2).uppercase(), color = Color.White, style = MaterialTheme.typography.labelSmall)
                        }
                        Spacer(Modifier.height(4.dp))
                        Text(center.myDisplayName ?: "You", color = Color.White.copy(alpha = 0.85f), style = MaterialTheme.typography.labelSmall, maxLines = 1)
                    }
                }
            }
        }
        Row(Modifier.align(Alignment.BottomCenter).padding(bottom = 36.dp), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            RoundButton(if (state.micEnabled) Icons.Filled.Mic else Icons.Filled.MicOff, "Mute", if (state.micEnabled) Color.White.copy(alpha = 0.2f) else Color.White, if (state.micEnabled) Color.White else Color.Black) { center.toggleMic() }
            if (call.type == CallType.VIDEO) RoundButton(if (state.cameraEnabled) Icons.Filled.Videocam else Icons.Filled.VideocamOff, "Camera", if (state.cameraEnabled) Color.White.copy(alpha = 0.2f) else Color.White, if (state.cameraEnabled) Color.White else Color.Black) { center.toggleCamera() }
            RoundButton(if (state.speakerEnabled) Icons.Filled.VolumeUp else Icons.Filled.VolumeOff, "Speaker", if (state.speakerEnabled) Color.White else Color.White.copy(alpha = 0.2f), if (state.speakerEnabled) Color.Black else Color.White) { center.toggleSpeaker() }
            RoundButton(Icons.Filled.CallEnd, "End call", Color(0xFFDC2626)) { center.hangUp() }
        }
    }
}

/** Full-screen group call view: one tile per remote participant plus a local self-preview, with
 *  the same global mic/camera/speaker/hangup controls as the 1:1 [CallScreen] — a group call has
 *  no per-remote-participant controls, only a grid of who's on it. */
@Composable
fun GroupCallScreen(center: CallCenter, state: CallState, call: ActiveCall) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(call.phase) { while (true) { delay(1000); now = System.currentTimeMillis() } }
    val status = when (call.phase) {
        CallPhase.CONNECTING -> "Connecting…"; CallPhase.RECONNECTING -> "Reconnecting…"
        else -> call.startedAtMs?.let { val s = ((now - it) / 1000).coerceAtLeast(0); "%02d:%02d".format(s / 60, s % 60) } ?: ""
    }
    val remoteIds = call.participantIds.filterNot { it == center.myUserId }
    Box(Modifier.fillMaxSize().background(Color(0xFF12182A))) {
        GroupVideoGrid(center, state, call, remoteIds, Modifier.fillMaxSize())
        Text("${remoteIds.size + 1} on this call · $status", Modifier.align(Alignment.TopCenter).windowInsetsPadding(WindowInsets.statusBars).padding(top = 16.dp), color = if (call.phase == CallPhase.RECONNECTING) Color.Yellow else Color.White.copy(alpha = 0.75f))
        PermissionDeniedBanner(state.localMicPermissionDenied, state.localCameraPermissionDenied, Modifier.align(Alignment.TopCenter).windowInsetsPadding(WindowInsets.statusBars).padding(top = 44.dp, start = 24.dp, end = 24.dp))
        Row(Modifier.align(Alignment.BottomCenter).padding(bottom = 36.dp), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            RoundButton(if (state.micEnabled) Icons.Filled.Mic else Icons.Filled.MicOff, "Mute", if (state.micEnabled) Color.White.copy(alpha = 0.2f) else Color.White, if (state.micEnabled) Color.White else Color.Black) { center.toggleMic() }
            if (call.type == CallType.VIDEO) RoundButton(if (state.cameraEnabled) Icons.Filled.Videocam else Icons.Filled.VideocamOff, "Camera", if (state.cameraEnabled) Color.White.copy(alpha = 0.2f) else Color.White, if (state.cameraEnabled) Color.White else Color.Black) { center.toggleCamera() }
            RoundButton(if (state.speakerEnabled) Icons.Filled.VolumeUp else Icons.Filled.VolumeOff, "Speaker", if (state.speakerEnabled) Color.White else Color.White.copy(alpha = 0.2f), if (state.speakerEnabled) Color.Black else Color.White) { center.toggleSpeaker() }
            RoundButton(Icons.Filled.CallEnd, "End call", Color(0xFFDC2626)) { center.hangUp() }
        }
    }
}

/** One grid for everyone on the call, your own tile included as just one more entry (last) —
 *  matching web's `repeat(auto-fit, minmax(140px, 1fr))` / iOS's `.adaptive(minimum: 140)`: the
 *  column count is however many ~140dp-wide tiles fit the available width, not a fixed 1-or-2
 *  keyed off headcount, and there's no separate floating self-view PiP box. */
@Composable
private fun GroupVideoGrid(center: CallCenter, state: CallState, call: ActiveCall, remoteIds: List<String>, modifier: Modifier = Modifier) {
    if (remoteIds.isEmpty()) {
        Box(modifier, contentAlignment = Alignment.Center) { Text("Waiting for others to join…", color = Color.White.copy(alpha = 0.7f)) }
        return
    }
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 140.dp),
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(2.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        items(remoteIds) { userId ->
            val track = state.remoteVideoTracks[userId]
            val media = state.remoteParticipantMedia[userId] ?: RemoteParticipantMedia()
            Box(Modifier.aspectRatio(1f).background(Color(0xFF1D2540))) {
                if (track != null && media.cameraEnabled) VideoView(track, Modifier.fillMaxSize())
                else Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Box(Modifier.size(64.dp).clip(CircleShape).background(Color.Gray.copy(alpha = 0.5f)), contentAlignment = Alignment.Center) {
                        Text(userId.take(2).uppercase(), color = Color.White, style = MaterialTheme.typography.titleMedium)
                    }
                }
                if (!media.micEnabled) {
                    Icon(Icons.Filled.MicOff, "Muted", Modifier.align(Alignment.BottomStart).padding(6.dp).size(16.dp), tint = Color.White)
                }
            }
        }
        item {
            Box(Modifier.aspectRatio(1f).background(Color(0xFF1D2540))) {
                if (call.type == CallType.VIDEO && state.cameraEnabled) state.localVideoTrack?.let { VideoView(it, Modifier.fillMaxSize(), mirror = true) }
                else Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                    Box(Modifier.size(56.dp).clip(CircleShape).background(Color.Gray.copy(alpha = 0.5f)), contentAlignment = Alignment.Center) {
                        Text((center.myDisplayName ?: "You").take(2).uppercase(), color = Color.White, style = MaterialTheme.typography.titleMedium)
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(center.myDisplayName ?: "You", color = Color.White.copy(alpha = 0.85f), style = MaterialTheme.typography.labelSmall, maxLines = 1)
                }
                if (!state.micEnabled) {
                    Icon(Icons.Filled.MicOff, "Muted", Modifier.align(Alignment.BottomStart).padding(6.dp).size(16.dp), tint = Color.White)
                }
            }
        }
    }
}

/** Audio + video call buttons for a 1:1 thread header (pass as MessageThread's headerActions). */
@Composable
fun CallButtons(center: CallCenter, conversation: Conversation) {
    val state by center.state.collectAsStateWithLifecycle()
    if (conversation.peer == null && !conversation.isGroup) return
    val start = rememberCallAction { type -> center.start(conversation, type) }
    IconButton(onClick = { start(CallType.AUDIO) }, enabled = state.call == null) { Icon(Icons.Filled.Call, "Audio call") }
    IconButton(onClick = { start(CallType.VIDEO) }, enabled = state.call == null) { Icon(Icons.Filled.Videocam, "Video call") }
}

@Composable
private fun RoundButton(icon: ImageVector, label: String, bg: Color, tint: Color = Color.White, onClick: () -> Unit) {
    IconButton(onClick = onClick, Modifier.size(56.dp).clip(CircleShape).background(bg)) { Icon(icon, label, tint = tint) }
}

@Composable
fun VideoView(track: VideoTrack, modifier: Modifier = Modifier, mirror: Boolean = false) {
    AndroidView(modifier = modifier, factory = { ctx ->
        SurfaceViewRenderer(ctx).apply { init(RelayWebRtc.eglBase.eglBaseContext, null); setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FILL); setMirror(mirror); track.addSink(this) }
    }, update = { view -> track.addSink(view) }, onRelease = { view -> runCatching { track.removeSink(view) }; view.release() })
}
