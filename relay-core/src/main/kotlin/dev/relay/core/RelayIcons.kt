package dev.relay.core

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Forward
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.automirrored.filled.Reply
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.NotificationsOff
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.Photo
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SearchOff
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.VideocamOff
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * The shared icon set relay-ui and relay-call read instead of hardcoding `Icons.Filled.X`
 * literals. Every default below was taken from what the SDK's screens actually render today
 * (grepped across RelayChat.kt, CallUi.kt, GroupDetailScreen.kt, AddParticipantsScreen.kt,
 * MediaGalleryScreen.kt, MessageInfoScreen.kt and LinkPreview.kt) — not copied blind from an
 * earlier sketch — so adopting [LocalRelayIcons] / passing `icons =` is opt-in and changes
 * nothing for a host who does neither. Two slots differ from that earlier sketch because the
 * real call sites disagreed with it:
 *  - [callDecline] is `Icons.Filled.Close` today (IncomingCallBanner's Decline button), not
 *    `CallEnd` — [callEnd] (hang up mid-call) is the one that actually uses `CallEnd`.
 *  - [leaveGroup] is the AutoMirrored `Icons.AutoMirrored.Filled.Logout` (GroupDetailScreen),
 *    not the non-mirrored `Icons.Filled.Logout`.
 * One slot ([stopRecording]) was added because the composer's stop-recording button
 * (`Icons.Filled.Stop`) has no equivalent in that sketch at all.
 */
data class RelayIcons(
    val micOn: ImageVector = Icons.Filled.Mic,
    val micOff: ImageVector = Icons.Filled.MicOff,
    val cameraOn: ImageVector = Icons.Filled.Videocam,
    val cameraOff: ImageVector = Icons.Filled.VideocamOff,
    val speakerOn: ImageVector = Icons.Filled.VolumeUp,
    val speakerOff: ImageVector = Icons.Filled.VolumeOff,
    val callAnswer: ImageVector = Icons.Filled.Call,
    val callDecline: ImageVector = Icons.Filled.Close,
    val callEnd: ImageVector = Icons.Filled.CallEnd,
    val attach: ImageVector = Icons.Filled.AttachFile,
    val recordVoice: ImageVector = Icons.Filled.Mic,
    val stopRecording: ImageVector = Icons.Filled.Stop,
    val send: ImageVector = Icons.AutoMirrored.Filled.Send,
    val close: ImageVector = Icons.Filled.Close,
    val back: ImageVector = Icons.AutoMirrored.Filled.ArrowBack,
    val reply: ImageVector = Icons.AutoMirrored.Filled.Reply,
    val forward: ImageVector = Icons.AutoMirrored.Filled.Forward,
    val copy: ImageVector = Icons.Filled.ContentCopy,
    val delete: ImageVector = Icons.Filled.Delete,
    val share: ImageVector = Icons.Filled.Share,
    val info: ImageVector = Icons.Filled.Info,
    val checkSent: ImageVector = Icons.Filled.Done,
    val checkDelivered: ImageVector = Icons.Filled.DoneAll,
    val checkRead: ImageVector = Icons.Filled.DoneAll,
    val search: ImageVector = Icons.Filled.Search,
    val searchOff: ImageVector = Icons.Filled.SearchOff,
    val muteNotifications: ImageVector = Icons.Filled.NotificationsOff,
    val leaveGroup: ImageVector = Icons.AutoMirrored.Filled.Logout,
    val addPeople: ImageVector = Icons.Filled.Add,
    val chevronRight: ImageVector = Icons.Filled.ChevronRight,
    val photo: ImageVector = Icons.Filled.Photo,
    val playVideo: ImageVector = Icons.Filled.PlayArrow,
    val pauseVideo: ImageVector = Icons.Filled.Pause,
    val checkCircleSelected: ImageVector = Icons.Filled.CheckCircle,
    val checkCircleUnselected: ImageVector = Icons.Filled.RadioButtonUnchecked,
)

/**
 * Plain non-null default on purpose — unlike [LocalRelayColors], [RelayIcons] needs no
 * `MaterialTheme` (or anything else ambient) to compute a correct-looking default, so there's
 * no reason to make every read site juggle a nullable. Any composable anywhere in relay-ui or
 * relay-call can safely read `LocalRelayIcons.current` directly, wrapped by a host's
 * [RelayTheme]/entry composable or not.
 */
val LocalRelayIcons = compositionLocalOf { RelayIcons() }
