package dev.relay.call

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.Build
import android.os.IBinder
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import dev.relay.core.RelayClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/**
 * Push wake-up for Android calls.
 *
 * The service sends a high-priority FCM *data* message (no `notification` block) for an incoming
 * call and for a ring that ended before pickup — see protocol/events.md, "Push wake-ups". The host
 * app's `FirebaseMessagingService` forwards every message to [RelayCallPush.onMessage]; Relay
 * decides whether it is one of its own (`data["relay"]`) and, for an invite, starts
 * [RelayCallService] — a phone-call foreground service that rings, shows a full-screen incoming
 * call notification with Answer / Decline, and keeps the process alive for the length of the call.
 *
 * Setup, once, in `Application.onCreate()`:
 *
 *     RelayCallHost.configure(this, MainActivity::class.java) { RelayClient(relayConfig) }
 *
 * and in your FirebaseMessagingService:
 *
 *     override fun onMessageReceived(m: RemoteMessage) { if (RelayCallPush.onMessage(this, m.data)) return; /* yours */ }
 *     override fun onNewToken(t: String) { RelayCallPush.onNewToken(t) }
 *
 * The host app must hold POST_NOTIFICATIONS (API 33+) and USE_FULL_SCREEN_INTENT (declared by this
 * module's manifest; on API 34+ the user may need to grant it in settings) for the ring to show
 * over the lock screen. Without them the call still lands in [CallCenter.state] once the app opens.
 */
sealed class CallPush {
    data class Invite(val callId: String, val conversationId: String, val callerId: String, val callerName: String?, val type: CallType) : CallPush()
    data class Cancel(val callId: String, val reason: String?) : CallPush()

    enum class Outcome { RINGING, CANCELLED, IGNORED }

    companion object {
        /** `null` when the map is not a Relay call push. Pure — unit-tested without Android. */
        fun parse(data: Map<String, String>): CallPush? = when (data["relay"]) {
            "call_invite" -> {
                val callId = data["callId"]; val conv = data["conversationId"]; val caller = data["callerId"]
                if (callId.isNullOrBlank() || conv.isNullOrBlank() || caller.isNullOrBlank()) null
                else Invite(callId, conv, caller, data["callerName"]?.takeIf { it.isNotBlank() }, if (data["type"] == "video") CallType.VIDEO else CallType.AUDIO)
            }
            "call_cancel" -> data["callId"]?.takeIf { it.isNotBlank() }?.let { Cancel(it, data["reason"]) }
            else -> null
        }

        fun isRelayPush(data: Map<String, String>) = data["relay"] == "call_invite" || data["relay"] == "call_cancel"
    }
}

/** Process-wide holder so a push can reach the same [CallCenter] the UI observes. */
object RelayCallHost {
    @Volatile private var factory: (() -> RelayClient)? = null
    @Volatile private var activity: Class<*>? = null
    @Volatile private var client: RelayClient? = null
    @Volatile private var center: CallCenter? = null
    @Volatile private var appContext: Context? = null
    @Volatile internal var fcmToken: String? = null

    /** @param activity the Activity to launch full-screen for an incoming call (your main/call activity). */
    fun configure(context: Context, activity: Class<*>, client: () -> RelayClient) {
        appContext = context.applicationContext; this.activity = activity; factory = client
    }

    val isConfigured get() = factory != null
    val activityClass: Class<*>? get() = activity

    /** The shared client (created on first use). Use this instance in your UI too so state is shared. */
    fun client(): RelayClient = client ?: synchronized(this) { client ?: (factory ?: error("RelayCallHost.configure() has not been called")).invoke().also { client = it } }

    fun callCenter(): CallCenter = center ?: synchronized(this) { center ?: CallCenter(appContext ?: error("RelayCallHost.configure() has not been called"), client()).also { center = it } }

    /** Hand in an already-built client + center (e.g. from your DI graph) instead of a factory. */
    fun attach(context: Context, activity: Class<*>, client: RelayClient, center: CallCenter) {
        appContext = context.applicationContext; this.activity = activity; this.client = client; this.center = center; factory = { client }
    }

    /** Sign-out: forget the shared instances (the next push rebuilds them from the factory). */
    fun reset() { center = null; client = null }
}

object RelayCallPush {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Forward every FCM message here. Returns true when the message was a Relay call push (handled),
     * false when it is the host app's own — check it first and return early when true.
     */
    fun onMessage(context: Context, data: Map<String, String>): Boolean {
        if (!CallPush.isRelayPush(data)) return false
        if (!RelayCallHost.isConfigured) return true // ours, but nowhere to deliver it — swallow
        val outcome = RelayCallHost.callCenter().handlePush(data)
        if (outcome == CallPush.Outcome.RINGING) RelayCallService.start(context)
        return true
    }

    /** Forward `onNewToken` here (and call once at startup with the current token). Registration
     *  needs a signed-in user; it is retried by [registerCurrentToken] after sign-in. */
    fun onNewToken(token: String) { RelayCallHost.fcmToken = token; registerCurrentToken() }

    /** Re-register the last known FCM token — call after sign-in. */
    fun registerCurrentToken() {
        val token = RelayCallHost.fcmToken ?: return
        if (!RelayCallHost.isConfigured) return
        scope.launch { runCatching { RelayCallHost.client().api.registerDevice(token) } }
    }

    /** Sign-out: remove this device's token for the current user. */
    suspend fun unregisterCurrentToken() {
        val token = RelayCallHost.fcmToken ?: return
        runCatching { RelayCallHost.client().api.unregisterDevice(token) }
    }
}

/**
 * Foreground service (type phoneCall) for the lifetime of a call that started from a push: rings
 * and vibrates while INCOMING, shows a full-screen notification with Answer / Decline, switches to
 * an "ongoing call" notification once connected, and stops when the call ends. Started by
 * [RelayCallPush.onMessage]; the UI never needs to talk to it.
 */
class RelayCallService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var observe: Job? = null
    private var ringtone: Ringtone? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val center = runCatching { RelayCallHost.callCenter() }.getOrNull()
        if (center == null) { stopSelf(); return START_NOT_STICKY }
        when (intent?.action) {
            ACTION_ANSWER -> { center.answer(); openApp() }
            ACTION_DECLINE -> center.decline()
            ACTION_HANG_UP -> center.hangUp()
        }
        val call = center.current
        if (call == null) { stopRinging(); stopSelf(); return START_NOT_STICKY }
        ensureChannels()
        val notification = buildNotification(call)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL)
        } else startForeground(NOTIFICATION_ID, notification)
        if (call.phase == CallPhase.INCOMING) startRinging() else stopRinging()
        if (observe == null) observe = scope.launch {
            center.state.collect { s ->
                val c = s.call
                if (c == null) { stopRinging(); stopForeground(STOP_FOREGROUND_REMOVE); stopSelf(); return@collect }
                if (c.phase != CallPhase.INCOMING) stopRinging()
                notificationManager.notify(NOTIFICATION_ID, buildNotification(c))
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() { stopRinging(); observe?.cancel(); super.onDestroy() }

    private val notificationManager get() = getSystemService(NOTIFICATION_SERVICE) as NotificationManager

    private fun ensureChannels() {
        val incoming = NotificationChannel(CHANNEL_INCOMING, "Incoming calls", NotificationManager.IMPORTANCE_HIGH).apply {
            setSound(null, null) // we ring through RingtoneManager so the sound follows the call, not the notification
            enableVibration(false)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }
        val ongoing = NotificationChannel(CHANNEL_ONGOING, "Ongoing calls", NotificationManager.IMPORTANCE_LOW)
        notificationManager.createNotificationChannels(listOf(incoming, ongoing))
    }

    private fun serviceIntent(action: String) = PendingIntent.getService(
        this, action.hashCode(), Intent(this, RelayCallService::class.java).setAction(action),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun activityIntent(): PendingIntent? {
        val cls = RelayCallHost.activityClass ?: return null
        val intent = Intent(this, cls).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP).putExtra(EXTRA_FROM_CALL, true)
        return PendingIntent.getActivity(this, 1, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    private fun openApp() { activityIntent()?.let { runCatching { it.send() } } }

    private fun buildNotification(call: ActiveCall): Notification {
        val name = call.peerName ?: call.peerId
        val incoming = call.phase == CallPhase.INCOMING
        val kind = if (call.type == CallType.VIDEO) "video call" else "call"
        val builder = Notification.Builder(this, if (incoming) CHANNEL_INCOMING else CHANNEL_ONGOING)
            .setSmallIcon(android.R.drawable.sym_call_incoming)
            .setContentTitle(name)
            .setContentText(if (incoming) "Incoming $kind" else if (call.phase == CallPhase.ACTIVE) "Ongoing $kind" else "Connecting…")
            .setCategory(Notification.CATEGORY_CALL)
            .setOngoing(true)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
        activityIntent()?.let { pi -> builder.setContentIntent(pi); if (incoming) builder.setFullScreenIntent(pi, true) }
        if (incoming) {
            builder.addAction(Notification.Action.Builder(null, "Decline", serviceIntent(ACTION_DECLINE)).build())
            builder.addAction(Notification.Action.Builder(null, "Answer", serviceIntent(ACTION_ANSWER)).build())
        } else {
            builder.addAction(Notification.Action.Builder(null, "Hang up", serviceIntent(ACTION_HANG_UP)).build())
            call.startedAtMs?.let { builder.setWhen(it).setUsesChronometer(true) }
        }
        return builder.build()
    }

    private fun startRinging() {
        if (ringtone?.isPlaying == true) return
        runCatching {
            val r = RingtoneManager.getRingtone(this, RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE))
            r.audioAttributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) r.isLooping = true
            r.play(); ringtone = r
        }
        runCatching {
            vibrator()?.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 800, 1200), 0))
        }
    }

    private fun stopRinging() {
        runCatching { ringtone?.stop() }; ringtone = null
        runCatching { vibrator()?.cancel() }
    }

    private fun vibrator(): Vibrator? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) (getSystemService(VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
    else @Suppress("DEPRECATION") getSystemService(VIBRATOR_SERVICE) as? Vibrator

    companion object {
        const val ACTION_ANSWER = "dev.relay.call.ANSWER"
        const val ACTION_DECLINE = "dev.relay.call.DECLINE"
        const val ACTION_HANG_UP = "dev.relay.call.HANG_UP"
        /** Set on the activity intent launched for an incoming call, so the host can route straight to its call UI. */
        const val EXTRA_FROM_CALL = "dev.relay.call.FROM_CALL"
        const val CHANNEL_INCOMING = "relay_incoming_calls"
        const val CHANNEL_ONGOING = "relay_ongoing_calls"
        private const val NOTIFICATION_ID = 0x5E1A

        fun start(context: Context) {
            val intent = Intent(context, RelayCallService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent) else context.startService(intent)
        }
    }
}
