package dev.relay.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/** Caches the user token and de-duplicates refreshes across concurrent 401s. */
class TokenSource(private val provider: TokenProvider) {
    @Volatile private var current: String? = null
    private val mutex = Mutex()
    suspend fun get(): String = current ?: refresh()
    suspend fun refresh(): String = mutex.withLock {
        val t = provider.token()
        if (t.isBlank()) throw RelayException(401, "no_token", "Token provider returned no token")
        current = t; t
    }
    fun peek(): String? = current
}

/** Typed, stateless wrapper over every Relay REST endpoint (protocol/openapi.yaml). */
class RelayApi(private val config: RelayConfig, internal val tokens: TokenSource, client: OkHttpClient? = null) {
    private val http = client ?: OkHttpClient.Builder().readTimeout(30, TimeUnit.SECONDS).build()
    private val jsonType = "application/json".toMediaType()
    private val base = config.baseUrl.trimEnd('/')

    @Serializable private data class ErrorBody(val error: String? = null, val message: String? = null)

    suspend fun <T> request(method: String, path: String, body: String? = null, serializer: KSerializer<T>, retryOn401: Boolean = true): T =
        withContext(Dispatchers.IO) {
            val token = tokens.get()
            val req = Request.Builder().url(base + path)
                .header("X-Relay-Key", config.publicKey)
                .header("Authorization", "Bearer $token")
                .method(method, body?.toRequestBody(jsonType) ?: if (method == "GET") null else "".toRequestBody(null))
                .build()
            val response = try { http.newCall(req).execute() } catch (e: java.io.IOException) { throw RelayException(0, "network", e.message ?: "network error") }
            response.use { res ->
                val text = res.body?.string().orEmpty()
                if (res.code == 401 && retryOn401) {
                    tokens.refresh()
                    return@withContext request(method, path, body, serializer, retryOn401 = false)
                }
                if (!res.isSuccessful) {
                    val parsed = runCatching { RelayJson.json.decodeFromString(ErrorBody.serializer(), text) }.getOrNull()
                    throw RelayException(res.code, parsed?.error ?: "http_error", parsed?.message ?: "Request failed with status ${res.code}")
                }
                RelayJson.json.decodeFromString(serializer, text.ifBlank { "{}" })
            }
        }

    private fun enc(v: String) = URLEncoder.encode(v, "UTF-8")

    @Serializable private data class UserEnv(val user: RelayUser)
    @Serializable private data class UsersEnv(val users: List<RelayUser>)
    @Serializable private data class ConvEnv(val conversation: Conversation)
    @Serializable private data class ConvsEnv(val conversations: List<Conversation>)
    @Serializable private data class OpenBody(val userId: String)
    @Serializable private data class GroupBody(val name: String, val userIds: List<String>, val photoUrl: String? = null)
    @Serializable private data class UpdateGroupBody(val name: String? = null, val photoUrl: String? = null)
    @Serializable private data class MembersBody(val userIds: List<String>)
    @Serializable data class ParticipantsResponse(val creatorId: String? = null, val participants: List<Participant>)
    @Serializable data class DeleteResult(val ok: Boolean, val deleted: Boolean? = null, val left: Boolean? = null)
    @Serializable data class OkResult(val ok: Boolean)
    @Serializable private data class MessageEnv(val message: Message, val clientId: String? = null)
    @Serializable private data class EditBody(val body: String)
    @Serializable data class ReadResult(val ok: Boolean, val lastReadAt: String)
    @Serializable private data class ReceiptsEnv(val receipts: List<ReadReceipt>)

    suspend fun me(): RelayUser = request("GET", "/users/me", serializer = UserEnv.serializer()).user
    suspend fun users(ids: List<String>): List<RelayUser> =
        if (ids.isEmpty()) emptyList() else request("GET", "/users?ids=${enc(ids.joinToString(","))}", serializer = UsersEnv.serializer()).users

    suspend fun listConversations(includeEmpty: Boolean = false): List<Conversation> =
        request("GET", "/conversations" + if (includeEmpty) "?includeEmpty=true" else "", serializer = ConvsEnv.serializer()).conversations
    suspend fun getConversation(id: String): Conversation = request("GET", "/conversations/$id", serializer = ConvEnv.serializer()).conversation
    suspend fun openConversation(userId: String): Conversation =
        request("POST", "/conversations", RelayJson.json.encodeToString(OpenBody.serializer(), OpenBody(userId)), ConvEnv.serializer()).conversation
    suspend fun createGroup(name: String, userIds: List<String>, photoUrl: String? = null): Conversation =
        request("POST", "/conversations/group", RelayJson.json.encodeToString(GroupBody.serializer(), GroupBody(name, userIds, photoUrl)), ConvEnv.serializer()).conversation
    suspend fun updateGroup(id: String, name: String? = null, photoUrl: String? = null): Conversation =
        request("PATCH", "/conversations/$id", RelayJson.json.encodeToString(UpdateGroupBody.serializer(), UpdateGroupBody(name, photoUrl)), ConvEnv.serializer()).conversation
    suspend fun deleteConversation(id: String): DeleteResult = request("DELETE", "/conversations/$id", serializer = DeleteResult.serializer())
    suspend fun participants(id: String): ParticipantsResponse = request("GET", "/conversations/$id/participants", serializer = ParticipantsResponse.serializer())
    suspend fun addMembers(id: String, userIds: List<String>) { request("POST", "/conversations/$id/participants", RelayJson.json.encodeToString(MembersBody.serializer(), MembersBody(userIds)), OkResult.serializer()) }
    suspend fun removeMember(id: String, userId: String) { request("DELETE", "/conversations/$id/participants/${enc(userId)}", serializer = OkResult.serializer()) }
    suspend fun clearHistory(id: String) { request("POST", "/conversations/$id/clear", serializer = OkResult.serializer()) }

    suspend fun messages(id: String, before: String? = null, limit: Int = 50): MessagesPage =
        request("GET", "/conversations/$id/messages?limit=$limit" + (before?.let { "&before=$it" } ?: ""), serializer = MessagesPage.serializer())
    suspend fun sendMessage(id: String, input: SendMessageInput): Message {
        val env = request("POST", "/conversations/$id/messages", RelayJson.json.encodeToString(SendMessageInput.serializer(), input), MessageEnv.serializer())
        return env.message.copy(clientId = env.clientId)
    }
    suspend fun editMessage(id: String, messageId: String, body: String): Message =
        request("PATCH", "/conversations/$id/messages/$messageId", RelayJson.json.encodeToString(EditBody.serializer(), EditBody(body)), MessageEnv.serializer()).message
    suspend fun deleteMessage(id: String, messageId: String): Message = request("DELETE", "/conversations/$id/messages/$messageId", serializer = MessageEnv.serializer()).message
    suspend fun markRead(id: String): ReadResult = request("POST", "/conversations/$id/read", serializer = ReadResult.serializer())
    suspend fun readReceipts(id: String): List<ReadReceipt> = request("GET", "/conversations/$id/read-receipts", serializer = ReceiptsEnv.serializer()).receipts

    /** Tell peers you're going offline now (call from onStop alongside disconnect()). */
    suspend fun goOffline() { request("POST", "/presence/offline", serializer = OkResult.serializer()) }

    // ---- push devices -------------------------------------------------------------------------
    @Serializable private data class DeviceBody(val token: String, val tokenType: String? = null)
    @Serializable class Empty

    /** Register this device's FCM token so a closed app still rings and gets chat alerts. Idempotent; call on every launch. */
    suspend fun registerDevice(token: String, type: DeviceTokenType = DeviceTokenType.FCM) {
        request("POST", "/devices", RelayJson.json.encodeToString(DeviceBody.serializer(), DeviceBody(token, type.wire)), Empty.serializer())
    }

    /** Sign-out: stop this device ringing for the previous account. */
    suspend fun unregisterDevice(token: String) {
        request("DELETE", "/devices", RelayJson.json.encodeToString(DeviceBody.serializer(), DeviceBody(token)), Empty.serializer())
    }
}

enum class DeviceTokenType(val wire: String) { FCM("fcm"), APNS("apns"), APNS_VOIP("apns_voip") }
