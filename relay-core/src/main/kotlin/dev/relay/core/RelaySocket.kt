package dev.relay.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.net.URLEncoder
import kotlin.random.Random

/**
 * Gateway WebSocket: connected only once the server's `connected` frame arrives (it is sent after
 * the user's channels are subscribed), exponential backoff with jitter, `{"event":"ping"}`
 * heartbeats, token refresh on a 4401 close, and a `reconnected` signal for resync — the server
 * never replays missed events.
 */
class RelaySocket(private val config: RelayConfig, private val tokens: TokenSource, client: OkHttpClient? = null) {
    sealed class Signal { object Connected : Signal(); object Reconnected : Signal(); data class Disconnected(val code: Int) : Signal(); data class Event(val event: RelayEvent) : Signal() }

    private val http = client ?: OkHttpClient()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _state = MutableStateFlow(ConnectionSnapshot(ConnectionState.IDLE))
    val state: StateFlow<ConnectionSnapshot> = _state.asStateFlow()
    private val _signals = MutableSharedFlow<Signal>(extraBufferCapacity = 256)
    val signals = _signals.asSharedFlow()

    @Volatile private var ws: WebSocket? = null
    @Volatile private var wantOpen = false
    private var everConnected = false
    private var pingJob: Job? = null
    private var reconnectJob: Job? = null
    private val openWaiters = mutableListOf<kotlinx.coroutines.CompletableDeferred<Unit>>()

    val isOpen get() = _state.value.state == ConnectionState.CONNECTED

    /** Opens and keeps the connection open until close(). Suspends until the first handshake. */
    suspend fun connect() {
        wantOpen = true
        if (isOpen) return
        val waiter = kotlinx.coroutines.CompletableDeferred<Unit>()
        synchronized(openWaiters) { openWaiters.add(waiter) }
        if (_state.value.state == ConnectionState.IDLE || _state.value.state == ConnectionState.CLOSED) open()
        waiter.await()
    }

    fun close() {
        wantOpen = false
        pingJob?.cancel(); reconnectJob?.cancel()
        ws?.close(1000, "client closed"); ws = null
        setState(ConnectionSnapshot(ConnectionState.CLOSED))
        failWaiters(RelayException(0, "closed", "connection closed"))
    }

    /** Send a client→server frame; false (dropped) when not connected. */
    fun send(frame: JsonObject): Boolean {
        val socket = ws ?: return false
        if (!isOpen) return false
        return socket.send(frame.toString())
    }

    fun send(vararg pairs: Pair<String, String?>): Boolean = send(buildJsonObject { pairs.forEach { (k, v) -> if (v != null) put(k, JsonPrimitive(v)) } })

    private fun url(token: String): String {
        val base = (config.webSocketUrl ?: config.baseUrl).trimEnd('/').replaceFirst(Regex("^http"), "ws")
        return "$base/ws/gateway?key=${URLEncoder.encode(config.publicKey, "UTF-8")}&token=${URLEncoder.encode(token, "UTF-8")}"
    }

    private fun open() {
        if (!wantOpen) return
        setState(_state.value.copy(state = if (everConnected) ConnectionState.RECONNECTING else ConnectionState.CONNECTING))
        scope.launch {
            val token = try { tokens.get() } catch (e: Exception) { scheduleReconnect(e.message ?: "token error"); return@launch }
            if (!wantOpen) return@launch
            val requestBuilder = Request.Builder().url(url(token))
            config.packageId?.let { requestBuilder.header("X-App-Package-Id", it) }
            val socket = http.newWebSocket(requestBuilder.build(), object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) { config.logger?.invoke("[relay] socket open, waiting for gateway handshake") }
                override fun onMessage(webSocket: WebSocket, text: String) {
                    if (ws !== webSocket) return
                    val obj = runCatching { RelayJson.json.parseToJsonElement(text).let { it as JsonObject } }.getOrNull() ?: return
                    val event = RelayEvent.decode(obj) ?: return
                    if (event is RelayEvent.Connected) markOpen(webSocket)
                    _signals.tryEmit(Signal.Event(event))
                }
                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = handleClose(webSocket, code, reason)
                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) = handleClose(webSocket, response?.code ?: 1006, t.message ?: "failure")
            })
            ws = socket
        }
    }

    private fun markOpen(socket: WebSocket) {
        if (ws !== socket || isOpen) return
        val wasReconnect = everConnected
        everConnected = true
        setState(ConnectionSnapshot(ConnectionState.CONNECTED))
        startPing(socket)
        val waiters = synchronized(openWaiters) { openWaiters.toList().also { openWaiters.clear() } }
        waiters.forEach { it.complete(Unit) }
        _signals.tryEmit(Signal.Connected)
        if (wasReconnect) _signals.tryEmit(Signal.Reconnected)
    }

    private fun handleClose(socket: WebSocket, code: Int, reason: String) {
        if (ws !== socket) return
        ws = null
        pingJob?.cancel()
        _signals.tryEmit(Signal.Disconnected(code))
        if (!wantOpen) return
        if (code == 4401 || code == 4403) scope.launch { runCatching { tokens.refresh() } }
        scheduleReconnect(if (code == 4429) "too many connections" else reason.ifBlank { "closed ($code)" })
    }

    private fun scheduleReconnect(lastError: String) {
        if (!wantOpen) return
        val attempts = _state.value.attempts + 1
        setState(ConnectionSnapshot(if (everConnected) ConnectionState.RECONNECTING else ConnectionState.CONNECTING, attempts, lastError))
        val base = minOf(config.maxBackoffMs, 1000L shl minOf(attempts - 1, 6))
        val delayMs = base / 2 + Random.nextLong(base / 2 + 1)
        config.logger?.invoke("[relay] reconnecting in ${delayMs}ms ($lastError)")
        reconnectJob?.cancel()
        reconnectJob = scope.launch { delay(delayMs); open() }
    }

    private fun startPing(socket: WebSocket) {
        pingJob?.cancel()
        pingJob = scope.launch {
            while (ws === socket) { delay(config.pingIntervalMs); if (ws === socket) send("event" to "ping") }
        }
    }

    private fun setState(next: ConnectionSnapshot) { if (_state.value != next) _state.value = next }
    private fun failWaiters(e: Exception) {
        val waiters = synchronized(openWaiters) { openWaiters.toList().also { openWaiters.clear() } }
        waiters.forEach { it.completeExceptionally(e) }
    }
}
