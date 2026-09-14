package dev.relay.call

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.relay.core.Conversation
import dev.relay.core.LocalRelayColors
import dev.relay.core.LocalRelayIcons
import dev.relay.core.LocalRelayTypography
import dev.relay.core.RelayColors
import dev.relay.core.RelayIcons
import dev.relay.core.RelayTypography
import kotlinx.coroutines.delay
import org.webrtc.RendererCommon
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoTrack

/** Mount once at your root: incoming banner, full-screen call view and error toast. */
@Composable
fun RelayCallOverlay(center: CallCenter, icons: RelayIcons? = null, typography: RelayTypography? = null) {
    // Resolved once here and re-provided, so every Relay composable nested underneath (through
    // IncomingCallBanner/CallScreen/GroupCallScreen) sees a non-null LocalRelayColors.current
    // whether or not the host ever wraps anything in RelayTheme — see relay-core's RelayColors.kt.
    val resolvedColors = LocalRelayColors.current ?: RelayColors.fromMaterialTheme(MaterialTheme.colorScheme)
    val resolvedIcons = icons ?: LocalRelayIcons.current
    val resolvedTypography = typography ?: LocalRelayTypography.current
    val density = LocalDensity.current
    val textStyle = LocalTextStyle.current
    CompositionLocalProvider(
        LocalRelayColors provides resolvedColors,
        LocalRelayIcons provides resolvedIcons,
        LocalDensity provides Density(density.density, density.fontScale * resolvedTypography.fontScale),
        LocalTextStyle provides textStyle.copy(fontFamily = resolvedTypography.fontFamily ?: textStyle.fontFamily),
    ) {
        val state by center.state.collectAsStateWithLifecycle()
        val call = state.call
        // Whatever put a call on screen — the user tapping a call button, a host calling
        // center.start() directly, or a ring arriving while they were typing — the soft keyboard
        // must go first: left up, it covers the incoming banner's Answer/Decline row and pushes the
        // in-call controls off the bottom of the screen. Keyed on the call id so it runs once per
        // call, not on every phase change (an active call may legitimately show a keyboard later).
        val keyboardController = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
        val focusManager = androidx.compose.ui.platform.LocalFocusManager.current
        LaunchedEffect(call?.id) {
            if (call != null) { focusManager.clearFocus(force = true); keyboardController?.hide() }
        }
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
fun IncomingCallBanner(center: CallCenter, call: ActiveCall, modifier: Modifier = Modifier, icons: RelayIcons? = null, typography: RelayTypography? = null) {
    // Standalone-safe (a host can show its own incoming-call UI using just this composable),
    // same pattern as RelayCallOverlay above.
    val colors = LocalRelayColors.current ?: RelayColors.fromMaterialTheme(MaterialTheme.colorScheme)
    val resolvedIcons = icons ?: LocalRelayIcons.current
    val resolvedTypography = typography ?: LocalRelayTypography.current
    val density = LocalDensity.current
    val textStyle = LocalTextStyle.current
    CompositionLocalProvider(
        LocalRelayColors provides colors,
        LocalRelayIcons provides resolvedIcons,
        LocalDensity provides Density(density.density, density.fontScale * resolvedTypography.fontScale),
        LocalTextStyle provides textStyle.copy(fontFamily = resolvedTypography.fontFamily ?: textStyle.fontFamily),
    ) {
        val answer = rememberAnswerAction(center)
        Surface(modifier.padding(12.dp).fillMaxWidth(), shape = RoundedCornerShape(16.dp), tonalElevation = 6.dp, shadowElevation = 6.dp) {
            Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(call.peerName ?: call.peerId, fontWeight = FontWeight.Bold)
                    Text("Incoming ${call.type.wire} call…", style = MaterialTheme.typography.bodySmall)
                }
                RoundButton(resolvedIcons.callDecline, "Decline", colors.danger) { center.decline() }
                Spacer(Modifier.width(8.dp))
                RoundButton(if (call.type == CallType.VIDEO) resolvedIcons.cameraOn else resolvedIcons.callAnswer, "Answer", colors.online) { answer(call.type) }
            }
        }
    }
}

@Composable
fun CallScreen(center: CallCenter, state: CallState, call: ActiveCall, icons: RelayIcons? = null, typography: RelayTypography? = null) {
    // Standalone-safe (a host can mount just this 1:1 call screen directly instead of going
    // through RelayCallOverlay), same pattern as RelayCallOverlay above.
    val colors = LocalRelayColors.current ?: RelayColors.fromMaterialTheme(MaterialTheme.colorScheme)
    val resolvedIcons = icons ?: LocalRelayIcons.current
    val resolvedTypography = typography ?: LocalRelayTypography.current
    val density = LocalDensity.current
    val textStyle = LocalTextStyle.current
    CompositionLocalProvider(
        LocalRelayColors provides colors,
        LocalRelayIcons provides resolvedIcons,
        LocalDensity provides Density(density.density, density.fontScale * resolvedTypography.fontScale),
        LocalTextStyle provides textStyle.copy(fontFamily = resolvedTypography.fontFamily ?: textStyle.fontFamily),
    ) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(call.phase) { while (true) { delay(1000); now = System.currentTimeMillis() } }
    val status = when (call.phase) {
        CallPhase.OUTGOING -> "Calling…"; CallPhase.CONNECTING -> "Connecting…"; CallPhase.RECONNECTING -> "Reconnecting…"
        else -> call.startedAtMs?.let { val s = ((now - it) / 1000).coerceAtLeast(0); "%02d:%02d".format(s / 60, s % 60) } ?: ""
    }
    // A disabled remote camera still sends frames (all black), so remoteCameraEnabled — not just
    // "is there a remote track" — decides whether to show video or fall back to the avatar.
    val showRemoteVideo = call.type == CallType.VIDEO && state.remoteVideoTrack != null && state.remoteCameraEnabled
    Box(Modifier.fillMaxSize().background(colors.callScrimStart)) {
        if (showRemoteVideo) VideoView(state.remoteVideoTrack!!, Modifier.fillMaxSize())
        else Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
            // Kept as a literal gray, not a RelayColors token: unlike the buttons below, there is
            // no field for this avatar-placeholder-on-video wash, and the nearest candidate
            // (surfaceMuted) would change the default look for most hosts (their MaterialTheme's
            // surfaceVariant is rarely neutral gray) — see summary.
            Box(Modifier.size(112.dp).clip(CircleShape).background(Color.Gray.copy(alpha = 0.5f)), contentAlignment = Alignment.Center) { Text((call.peerName ?: call.peerId).take(2).uppercase(), color = Color.White, style = MaterialTheme.typography.headlineMedium) }
            Spacer(Modifier.height(12.dp)); Text(call.peerName ?: call.peerId, color = Color.White, style = MaterialTheme.typography.titleLarge)
        }
        if (!state.remoteMicEnabled) {
            Row(
                Modifier.align(Alignment.TopStart).windowInsetsPadding(WindowInsets.statusBars).padding(16.dp).clip(RoundedCornerShape(999.dp)).background(Color.Black.copy(alpha = 0.45f)).padding(horizontal = 10.dp, vertical = 5.dp),
                horizontalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                Icon(resolvedIcons.micOff, "Their microphone is muted", Modifier.size(14.dp), tint = Color.White)
                Text("Muted", color = Color.White, style = MaterialTheme.typography.labelSmall)
            }
        }
        Text(status, Modifier.align(Alignment.TopCenter).windowInsetsPadding(WindowInsets.statusBars).padding(top = 16.dp), color = if (call.phase == CallPhase.RECONNECTING) Color.Yellow else Color.White.copy(alpha = 0.75f))
        PermissionDeniedBanner(state.localMicPermissionDenied, state.localCameraPermissionDenied, Modifier.align(Alignment.TopCenter).windowInsetsPadding(WindowInsets.statusBars).padding(top = 44.dp, start = 24.dp, end = 24.dp))
        // The renderer stays mounted (not conditionally) when the camera is off, so its track
        // assignment survives the toggle — only the overlay changes. Without this the box shows a
        // stale black frame instead of your own avatar when you turn your camera off mid-call.
        if (call.type == CallType.VIDEO) state.localVideoTrack?.let { track ->
            Box(Modifier.align(Alignment.BottomEnd).padding(16.dp, 0.dp, 16.dp, 120.dp).size(100.dp, 150.dp).clip(RoundedCornerShape(14.dp)).background(colors.callScrimEnd)) {
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
            RoundButton(if (state.micEnabled) resolvedIcons.micOn else resolvedIcons.micOff, "Mute", if (state.micEnabled) Color.White.copy(alpha = 0.2f) else Color.White, if (state.micEnabled) Color.White else Color.Black) { center.toggleMic() }
            if (call.type == CallType.VIDEO) RoundButton(if (state.cameraEnabled) resolvedIcons.cameraOn else resolvedIcons.cameraOff, "Camera", if (state.cameraEnabled) Color.White.copy(alpha = 0.2f) else Color.White, if (state.cameraEnabled) Color.White else Color.Black) { center.toggleCamera() }
            RoundButton(if (state.speakerEnabled) resolvedIcons.speakerOn else resolvedIcons.speakerOff, "Speaker", if (state.speakerEnabled) Color.White else Color.White.copy(alpha = 0.2f), if (state.speakerEnabled) Color.Black else Color.White) { center.toggleSpeaker() }
            RoundButton(resolvedIcons.callEnd, "End call", colors.danger) { center.hangUp() }
        }
    }
    }
}

/** Full-screen group call view: one tile per remote participant plus a local self-preview, with
 *  the same global mic/camera/speaker/hangup controls as the 1:1 [CallScreen] — a group call has
 *  no per-remote-participant controls, only a grid of who's on it. */
@Composable
fun GroupCallScreen(center: CallCenter, state: CallState, call: ActiveCall, icons: RelayIcons? = null, typography: RelayTypography? = null) {
    // Standalone-safe (a host can mount just this group call screen directly instead of going
    // through RelayCallOverlay), same pattern as RelayCallOverlay above.
    val colors = LocalRelayColors.current ?: RelayColors.fromMaterialTheme(MaterialTheme.colorScheme)
    val resolvedIcons = icons ?: LocalRelayIcons.current
    val resolvedTypography = typography ?: LocalRelayTypography.current
    val density = LocalDensity.current
    val textStyle = LocalTextStyle.current
    CompositionLocalProvider(
        LocalRelayColors provides colors,
        LocalRelayIcons provides resolvedIcons,
        LocalDensity provides Density(density.density, density.fontScale * resolvedTypography.fontScale),
        LocalTextStyle provides textStyle.copy(fontFamily = resolvedTypography.fontFamily ?: textStyle.fontFamily),
    ) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(call.phase) { while (true) { delay(1000); now = System.currentTimeMillis() } }
    val status = when (call.phase) {
        CallPhase.CONNECTING -> "Connecting…"; CallPhase.RECONNECTING -> "Reconnecting…"
        else -> call.startedAtMs?.let { val s = ((now - it) / 1000).coerceAtLeast(0); "%02d:%02d".format(s / 60, s % 60) } ?: ""
    }
    val remoteIds = call.participantIds.filterNot { it == center.myUserId }
    // Tapping a tile expands it full-screen; tapping the exit button returns to the grid. `null`
    // means "showing the grid."
    var expanded by remember { mutableStateOf<ExpandedTile?>(null) }
    if (expanded is ExpandedTile.Remote && (expanded as ExpandedTile.Remote).userId !in remoteIds) expanded = null
    Box(Modifier.fillMaxSize().background(colors.callScrimStart)) {
        val current = expanded
        if (current != null) {
            Box(Modifier.fillMaxSize().padding(top = 64.dp, bottom = 8.dp, start = 8.dp, end = 8.dp)) {
                when (current) {
                    is ExpandedTile.Local -> LocalVideoTile(center, state, call)
                    is ExpandedTile.Remote -> RemoteVideoTile(current.userId, state, center)
                }
                // Top-start, not top-end — the tile's own per-participant mute button (RemoteVideoTile)
                // already occupies top-end and would otherwise sit right under this.
                IconButton(onClick = { expanded = null }, Modifier.align(Alignment.TopStart).padding(6.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.5f))) {
                    Icon(resolvedIcons.close, "Exit full screen", tint = Color.White)
                }
            }
        } else {
            GroupVideoGrid(center, state, call, remoteIds, Modifier.fillMaxSize()) { expanded = it }
        }
        Text("${remoteIds.size + 1} on this call · $status", Modifier.align(Alignment.TopCenter).windowInsetsPadding(WindowInsets.statusBars).padding(top = 16.dp), color = if (call.phase == CallPhase.RECONNECTING) Color.Yellow else Color.White.copy(alpha = 0.75f))
        PermissionDeniedBanner(state.localMicPermissionDenied, state.localCameraPermissionDenied, Modifier.align(Alignment.TopCenter).windowInsetsPadding(WindowInsets.statusBars).padding(top = 44.dp, start = 24.dp, end = 24.dp))
        Row(Modifier.align(Alignment.BottomCenter).padding(bottom = 36.dp), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            RoundButton(if (state.micEnabled) resolvedIcons.micOn else resolvedIcons.micOff, "Mute", if (state.micEnabled) Color.White.copy(alpha = 0.2f) else Color.White, if (state.micEnabled) Color.White else Color.Black) { center.toggleMic() }
            if (call.type == CallType.VIDEO) RoundButton(if (state.cameraEnabled) resolvedIcons.cameraOn else resolvedIcons.cameraOff, "Camera", if (state.cameraEnabled) Color.White.copy(alpha = 0.2f) else Color.White, if (state.cameraEnabled) Color.White else Color.Black) { center.toggleCamera() }
            RoundButton(if (state.speakerEnabled) resolvedIcons.speakerOn else resolvedIcons.speakerOff, "Speaker", if (state.speakerEnabled) Color.White else Color.White.copy(alpha = 0.2f), if (state.speakerEnabled) Color.Black else Color.White) { center.toggleSpeaker() }
            RoundButton(resolvedIcons.callEnd, "End call", colors.danger) { center.hangUp() }
        }
    }
    }
}

private sealed class ExpandedTile {
    data class Remote(val userId: String) : ExpandedTile()
    object Local : ExpandedTile()
}

/** Balanced-rows layout for a given tile count: 2 stacks top/bottom, 3 is 2-over-1, 4 is 2-and-2,
 *  5 is 3-over-2, and 6 (the group-call cap) is three rows of 2 rather than 2 rows of 3 — each row
 *  filled left-to-right before starting the next. */
private fun groupGridRows(tileCount: Int): List<Int> = when (tileCount) {
    0 -> emptyList()
    1 -> listOf(1)
    6 -> listOf(2, 2, 2)
    else -> listOf((tileCount + 1) / 2, tileCount / 2)
}

/** Everyone on the call, your own tile included as just one more entry (last), arranged via
 *  [groupGridRows] — tiles fill their row/column share of the screen rather than staying square.
 *  Tapping a tile reports it via [onExpand] so the caller can show it full screen. */
@Composable
private fun GroupVideoGrid(center: CallCenter, state: CallState, call: ActiveCall, remoteIds: List<String>, modifier: Modifier = Modifier, onExpand: (ExpandedTile) -> Unit) {
    if (remoteIds.isEmpty()) {
        Box(modifier, contentAlignment = Alignment.Center) { Text("Waiting for others to join…", color = Color.White.copy(alpha = 0.7f)) }
        return
    }
    val rows = groupGridRows(remoteIds.size + 1)
    Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        var index = 0
        rows.forEach { rowSize ->
            Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                repeat(rowSize) {
                    val userId = remoteIds.getOrNull(index)
                    index++
                    Box(
                        Modifier.weight(1f).fillMaxHeight()
                            .clickable { onExpand(userId?.let { ExpandedTile.Remote(it) } ?: ExpandedTile.Local) },
                    ) {
                        if (userId != null) RemoteVideoTile(userId, state, center) else LocalVideoTile(center, state, call)
                    }
                }
            }
        }
    }
}

/** Bottom-left name pill (mic-off icon when muted) shared by every tile, video or avatar. */
@Composable
private fun BoxScope.NameBadge(name: String, muted: Boolean) {
    // Plain non-null default (see RelayIcons.kt) — safe regardless of caller.
    val icons = LocalRelayIcons.current
    Row(
        Modifier.align(Alignment.BottomStart).padding(8.dp).clip(RoundedCornerShape(999.dp)).background(Color.Black.copy(alpha = 0.45f)).padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (muted) Icon(icons.micOff, "Muted", Modifier.size(12.dp), tint = Color.White)
        Text(name, color = Color.White, style = MaterialTheme.typography.labelSmall, maxLines = 1)
    }
}

@Composable
private fun RemoteVideoTile(userId: String, state: CallState, center: CallCenter) {
    // Safe non-null: only ever reached from GroupCallScreen, which resolves/provides
    // LocalRelayColors before calling this (directly or via GroupVideoGrid).
    val colors = LocalRelayColors.current!!
    val icons = LocalRelayIcons.current
    val track = state.remoteVideoTracks[userId]
    val media = state.remoteParticipantMedia[userId] ?: RemoteParticipantMedia()
    val locallyMuted = userId in state.locallyMutedUsers
    Box(Modifier.fillMaxSize().background(colors.callScrimEnd)) {
        if (track != null && media.cameraEnabled) VideoView(track, Modifier.fillMaxSize())
        else Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Box(Modifier.size(64.dp).clip(CircleShape).background(Color.Gray.copy(alpha = 0.5f)), contentAlignment = Alignment.Center) {
                Text(userId.take(2).uppercase(), color = Color.White, style = MaterialTheme.typography.titleMedium)
            }
        }
        NameBadge(userId, muted = !media.micEnabled)
        // Local-only "don't let me hear this person" toggle — never sent over the wire.
        IconButton(
            onClick = { center.toggleLocalMute(userId) },
            modifier = Modifier.align(Alignment.TopEnd).padding(6.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.5f)).size(28.dp),
        ) {
            Icon(if (locallyMuted) icons.speakerOff else icons.speakerOn, if (locallyMuted) "Unmute for me" else "Mute for me", Modifier.size(16.dp), tint = Color.White)
        }
    }
}

@Composable
private fun LocalVideoTile(center: CallCenter, state: CallState, call: ActiveCall) {
    // Safe non-null: only ever reached from GroupCallScreen, which resolves/provides
    // LocalRelayColors before calling this (directly or via GroupVideoGrid).
    val colors = LocalRelayColors.current!!
    Box(Modifier.fillMaxSize().background(colors.callScrimEnd)) {
        // Permission-denied can still produce a (frameless) local track on some devices, which
        // would otherwise render as a blank rectangle instead of falling back to the avatar — so
        // this checks the permission flag too, not just "is there a track."
        if (call.type == CallType.VIDEO && state.cameraEnabled && !state.localCameraPermissionDenied) {
            state.localVideoTrack?.let { VideoView(it, Modifier.fillMaxSize(), mirror = true) }
        } else Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Box(Modifier.size(56.dp).clip(CircleShape).background(Color.Gray.copy(alpha = 0.5f)), contentAlignment = Alignment.Center) {
                Text((center.myDisplayName ?: "You").take(2).uppercase(), color = Color.White, style = MaterialTheme.typography.titleMedium)
            }
        }
        NameBadge(center.myDisplayName ?: "You", muted = !state.micEnabled)
    }
}

/** Audio + video call buttons for a 1:1 thread header (pass as MessageThread's headerActions). */
@Composable
fun CallButtons(center: CallCenter, conversation: Conversation) {
    // Plain non-null default (see RelayIcons.kt) — safe standalone, no wrapping requirement.
    val icons = LocalRelayIcons.current
    val state by center.state.collectAsStateWithLifecycle()
    if (conversation.peer == null && !conversation.isGroup) return
    val modules by center.modulesFlow.collectAsStateWithLifecycle()
    if (!modules.audioCalls && !modules.videoCalls) return
    val start = rememberCallAction { type -> center.start(conversation, type) }
    if (modules.audioCalls) IconButton(onClick = { start(CallType.AUDIO) }, enabled = state.call == null) { Icon(icons.callAnswer, "Audio call") }
    if (modules.videoCalls) IconButton(onClick = { start(CallType.VIDEO) }, enabled = state.call == null) { Icon(icons.cameraOn, "Video call") }
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
