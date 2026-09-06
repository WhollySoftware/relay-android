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
))

setContent { MaterialTheme { RelayChat(client = relay) } }  // list → thread → composer, live
```

Call `relay.goToBackground()` from `onStop` and `relay.connect()` from `onStart`; the store
resyncs after every reconnect (the server never replays missed events). `relay.disconnect()`
on sign-out.

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

## Status

Chat (`relay-core`, `relay-ui`), calling (`relay-call`) and push wake-up (FCM data → foreground
service) are implemented and compiling. Unit tests: `./gradlew testDebugUnitTest` (API client
against a mock server, event decoding, push parsing). Not yet: a Telecom `ConnectionService`
(native dialer integration / Bluetooth answer buttons), and an on-device end-to-end run of the
push path against a real Firebase project.
