# Relay Android SDK

| Module | What it is |
|---|---|
| `relay-core` | Headless client: `RelayClient`, `RelayApi` (OkHttp), `RelaySocket`, `ChatStore` (StateFlow). |
| `relay-ui` | Jetpack Compose chat kit: `RelayChat`, `ConversationList`, `MessageThread`, `MessageComposer`, `MessageBubble`. |
| `relay-call` | 1:1 audio/video calls: `CallCenter` (WebRTC state machine over `io.getstream:stream-webrtc-android`), `RelayCallOverlay`, `CallScreen`, `IncomingCallBanner`, `CallButtons`. |

Requires minSdk 26, Kotlin 2.1, Compose BOM 2025.01.

## Install

Until it is published, add the modules with a Gradle composite or `includeBuild("../relay-sdk/packages/android")`,
then `implementation(project(":relay-ui"))`.

## The 10-line integration

```kotlin
val relay = RelayClient(RelayConfig(
    baseUrl = "https://relay.example.com",
    publicKey = "pk_…",                                     // safe to embed
    tokenProvider = { myBackend.relayToken() },             // POST /users/token on YOUR server with the secret key
    packageId = BuildConfig.APPLICATION_ID,                 // optional — scopes the key to this app, see Security below
))

setContent { MaterialTheme { RelayChat(client = relay) } }  // list → thread → composer, live
```

Call `relay.goToBackground()` from `onStop` and `relay.connect()` from `onStart`; the store
resyncs after every reconnect (the server never replays missed events). `relay.disconnect()`
on sign-out.

## Security

Pass `packageId` (your app's `applicationId`, e.g. `BuildConfig.APPLICATION_ID`) in `RelayConfig`
if the project has an Android package allowlist configured (in the admin panel or via
`PATCH /projects/me/settings`) — the SDK sends it as `X-App-Package-Id` on every request, and the
service rejects requests from an app not on that list. Optional and backward compatible: omit it,
or leave the project's allowlist empty, and nothing is enforced.

## Attachments

`MessageComposer`'s attach button (Camera / Gallery / File) covers images, video and generic
files. Gallery (`PickVisualMedia`, Android's system Photo Picker) and File (`GetContent`) need
**no permission at all** — both are out-of-process pickers, so this SDK never gets broader
media/storage access than the one item the user picked, on any API level. **Camera is the one
exception**: add `<uses-permission android:name="android.permission.CAMERA"/>` to your own
`AndroidManifest.xml` — `MessageComposer` requests it at runtime the first time Camera is tapped.
Deliberately not bundled into `relay-ui`'s own manifest, so a chat-only app that never uses the
camera isn't forced to carry that permission. (The `FileProvider` Camera needs to hand off its
capture *is* bundled in `relay-ui`'s manifest and merges into yours automatically — that's pure
plumbing, not a user-facing permission.) A video pick/capture gets a small client-extracted
thumbnail automatically; this SDK never decodes video server-side either.

## Link previews

A message whose body contains an `http(s)://` URL automatically gets a social-app-style preview
card (image, title, description, site name) under the bubble — and the same card appears above
`MessageComposer`'s text field, live, the moment a link is typed or pasted into the draft, before
it's even sent. No setup needed: the metadata is fetched and cached server-side (`GET
/link-preview`), so this composable never talks to the linked site directly. A link with no usable
Open Graph metadata (or that fails to load) renders no card at all — never an empty placeholder.

## Calling

```kotlin
val calls = CallCenter(context, relay)                 // once per client
Box { RelayChat(relay); RelayCallOverlay(calls) }       // banner + full-screen call UI
// in a thread header: CallButtons(calls, conversation)
```

Request `RECORD_AUDIO` (and `CAMERA` for video) before starting a call.

### Ringing when the app is closed (FCM)

The service sends a high-priority FCM data message for incoming calls (and a cancel when the
ring ends) once the project has FCM credentials (`PATCH /projects/me/settings`, done from your
backend). On the app side:

```kotlin
// Application.onCreate()
RelayCallHost.configure(this, MainActivity::class.java) { RelayClient(relayConfig) }
// use RelayCallHost.client() / RelayCallHost.callCenter() in your UI so state is shared

// your FirebaseMessagingService
override fun onMessageReceived(m: RemoteMessage) {
    if (RelayCallPush.onMessage(this, m.data)) return   // Relay handled a call push
    // ... your own notifications
}
override fun onNewToken(token: String) = RelayCallPush.onNewToken(token)
// after sign-in: RelayCallPush.registerCurrentToken(); on sign-out: RelayCallPush.unregisterCurrentToken()
```

`RelayCallService` (a `phoneCall` foreground service declared by this module) rings, vibrates,
shows a full-screen incoming-call notification with Answer / Decline, and keeps the process alive
for the length of the call. Your activity is launched with `RelayCallService.EXTRA_FROM_CALL`; show
`RelayCallOverlay` and it takes over. Ask for `POST_NOTIFICATIONS` on API 33+ and, on API 34+, be
aware the user can revoke full-screen intents in settings (the call then shows as a heads-up
notification). Chat messages arrive as ordinary FCM notification messages with
`data.relay = "chat_message"` and `conversationId` for deep-linking.

## Headless

```kotlin
relay.connect()
relay.chat.loadConversations()
val convo = relay.chat.openConversation("user-42")          // your own user id
relay.chat.sendMessage(convo.id, "hello")
relay.chat.state.collect { snapshot -> /* conversations, threads, typing, presence, totalUnread */ }
```

## Debugging

`RelayConfig` takes a `logger` callback (`((String) -> Unit)?`) plus a `debug: Boolean = false`
flag. With `debug = false` (the default) the SDK stays silent apart from two minimal connection
lines; set `debug = true` and pass a `logger` to get verbose diagnostics for connection, REST, and
call issues:

```kotlin
val relay = RelayClient(RelayConfig(
    baseUrl = "https://relay.example.com",
    publicKey = "pk_…",
    tokenProvider = { myBackend.relayToken() },
    debug = BuildConfig.DEBUG,
    logger = { msg -> Log.d("Relay", msg) },
))
```

A log line looks like:

```
[relay] connecting to wss://relay.example.com
[relay] connected
[relay] -> GET /conversations
[relay] <- GET /conversations 200
[relay] event: chat_message conversationId=c_1 messageId=m_9
[relay] modules updated: videoCalls=false
[relay] call call_123 answered
[relay] TURN credentials fetched (2 ICE server URLs)
```

Debug logs never include auth tokens, TURN credentials, message content, attachment URLs, or user
display names/avatars — only connection state, request paths (no query strings), event types, and
non-content ids.

## Status

Chat (`relay-core`, `relay-ui`), calling (`relay-call`) and push wake-up (FCM data → foreground
service) are implemented and compiling. Unit tests: `./gradlew testDebugUnitTest` (API client
against a mock server, event decoding, push parsing). Not yet: a Telecom `ConnectionService`
(native dialer integration / Bluetooth answer buttons), and an on-device end-to-end run of the
push path against a real Firebase project.
