package dev.relay.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import okhttp3.OkHttpClient

/**
 * The one object a host app holds — create it once per signed-in user:
 *
 *     val relay = RelayClient(RelayConfig(baseUrl, publicKey, { myBackend.relayToken() }))
 *     relay.connect()
 *     relay.chat.loadConversations()
 *
 * `relay.chat.state` is a StateFlow for Compose; `relay.api` is the raw REST surface.
 */
class RelayClient(val config: RelayConfig, okHttp: OkHttpClient? = null) {
    internal val tokens = TokenSource(config.tokenProvider)
    val api = RelayApi(config, tokens, okHttp)
    internal val socket = RelaySocket(config, tokens, okHttp)
    val chat = ChatStore(api, socket)
    val connection: StateFlow<ConnectionSnapshot> get() = socket.state
    /** Every gateway event (chat + anything else, e.g. call_* frames consumed by relay-call). */
    val events: Flow<RelayEvent> get() = socket.signals.filterIsInstance<RelaySocket.Signal.Event>().map { it.event }
    @Volatile var me: RelayUser? = null; private set
    /** Per-project module-gating flags from the last successful connect, kept live thereafter by
     *  the server's `modules_updated` push (see [scope] listener below). Defaults to all-enabled
     *  (today's fully-open behavior) before the first connect, and on older services that don't
     *  return the `modules` field or send that event yet. Exposed as a StateFlow so Compose UI
     *  (`modulesFlow.collectAsState()`) recomposes on live changes — `modules` itself is a plain
     *  snapshot getter for non-Compose callers. */
    private val _modules = MutableStateFlow(RelayModules())
    val modulesFlow: StateFlow<RelayModules> = _modules.asStateFlow()
    val modules: RelayModules get() = _modules.value
    val userId: String? get() = me?.userId ?: chat.userId
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    init {
        scope.launch {
            events.filterIsInstance<RelayEvent.ModulesUpdated>().collect {
                val prev = _modules.value
                _modules.value = it.modules
                modulesDiffLogLine(prev, it.modules)?.let { diff -> config.debugLog { "[relay] modules updated: $diff" } }
            }
        }
    }

    /** Opens the realtime connection and resolves once the gateway handshake completes. */
    suspend fun connect(): RelayUser = coroutineScope {
        val user = async { me?.let { it to modules } ?: api.meWithModules() }
        socket.connect()
        val (u, m) = user.await()
        _modules.value = m
        u.also { me = it; chat.setMe(it) }
    }

    /** Closes the connection and clears local state (sign-out). */
    fun disconnect() { socket.close(); chat.reset(); me = null; _modules.value = RelayModules(); api.tokens.clear() }

    /** From Activity.onStop: closes the socket and tells the server right away. */
    suspend fun goToBackground() { socket.close(); runCatching { api.goOffline() } }

    /** Raw client→server frame (relay-call uses this for signaling). */
    fun sendFrame(frame: JsonObject) = socket.send(frame)
}
