package dev.relay.call

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
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
            else -> CallScreen(center, state, call)
        }
        state.error?.let { msg ->
            Surface(Modifier.align(Alignment.TopCenter).padding(12.dp).clickable { center.clearError() }, color = MaterialTheme.colorScheme.errorContainer, shape = RoundedCornerShape(10.dp)) {
                Text(msg, Modifier.padding(10.dp), color = MaterialTheme.colorScheme.onErrorContainer)
            }
        }
    }
}

@Composable
fun IncomingCallBanner(center: CallCenter, call: ActiveCall, modifier: Modifier = Modifier) {
    Surface(modifier.padding(12.dp).fillMaxWidth(), shape = RoundedCornerShape(16.dp), tonalElevation = 6.dp, shadowElevation = 6.dp) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(call.peerName ?: call.peerId, fontWeight = FontWeight.Bold)
                Text("Incoming ${call.type.wire} call…", style = MaterialTheme.typography.bodySmall)
            }
            RoundButton(Icons.Filled.Close, "Decline", Color(0xFFDC2626)) { center.decline() }
            Spacer(Modifier.width(8.dp))
            RoundButton(if (call.type == CallType.VIDEO) Icons.Filled.Videocam else Icons.Filled.Call, "Answer", Color(0xFF22C55E)) { center.answer() }
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
    Box(Modifier.fillMaxSize().background(Color(0xFF12182A))) {
        if (call.type == CallType.VIDEO && state.remoteVideoTrack != null) VideoView(state.remoteVideoTrack, Modifier.fillMaxSize())
        else Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.size(112.dp).clip(CircleShape).background(Color.Gray.copy(alpha = 0.5f)), contentAlignment = Alignment.Center) { Text((call.peerName ?: call.peerId).take(2).uppercase(), color = Color.White, style = MaterialTheme.typography.headlineMedium) }
            Spacer(Modifier.height(12.dp)); Text(call.peerName ?: call.peerId, color = Color.White, style = MaterialTheme.typography.titleLarge)
        }
        Text(status, Modifier.align(Alignment.TopCenter).padding(top = 28.dp), color = if (call.phase == CallPhase.RECONNECTING) Color.Yellow else Color.White.copy(alpha = 0.75f))
        if (call.type == CallType.VIDEO) state.localVideoTrack?.let { VideoView(it, Modifier.align(Alignment.BottomEnd).padding(16.dp, 0.dp, 16.dp, 120.dp).size(100.dp, 150.dp).clip(RoundedCornerShape(14.dp)), mirror = true) }
        Row(Modifier.align(Alignment.BottomCenter).padding(bottom = 36.dp), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            RoundButton(if (state.micEnabled) Icons.Filled.Mic else Icons.Filled.MicOff, "Mute", if (state.micEnabled) Color.White.copy(alpha = 0.2f) else Color.White, if (state.micEnabled) Color.White else Color.Black) { center.toggleMic() }
            if (call.type == CallType.VIDEO) RoundButton(if (state.cameraEnabled) Icons.Filled.Videocam else Icons.Filled.VideocamOff, "Camera", if (state.cameraEnabled) Color.White.copy(alpha = 0.2f) else Color.White, if (state.cameraEnabled) Color.White else Color.Black) { center.toggleCamera() }
            RoundButton(if (state.speakerEnabled) Icons.Filled.VolumeUp else Icons.Filled.VolumeOff, "Speaker", if (state.speakerEnabled) Color.White else Color.White.copy(alpha = 0.2f), if (state.speakerEnabled) Color.Black else Color.White) { center.toggleSpeaker() }
            RoundButton(Icons.Filled.CallEnd, "End call", Color(0xFFDC2626)) { center.hangUp() }
        }
    }
}

/** Audio + video call buttons for a 1:1 thread header (pass as MessageThread's headerActions). */
@Composable
fun CallButtons(center: CallCenter, conversation: Conversation) {
    val state by center.state.collectAsStateWithLifecycle()
    if (conversation.isGroup || conversation.peer == null) return
    IconButton(onClick = { center.start(conversation, CallType.AUDIO) }, enabled = state.call == null) { Icon(Icons.Filled.Call, "Audio call") }
    IconButton(onClick = { center.start(conversation, CallType.VIDEO) }, enabled = state.call == null) { Icon(Icons.Filled.Videocam, "Video call") }
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
