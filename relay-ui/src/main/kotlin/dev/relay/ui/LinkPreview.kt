package dev.relay.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import dev.relay.core.LinkPreview
import dev.relay.core.RelayApi
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Matches an explicit http(s) URL — bare domains ("google.com") deliberately don't count, same
 *  rule on web/iOS so a message previews identically everywhere. */
private val MESSAGE_URL_RE = Regex("""https?://[^\s<>"')\]]+""")

/** First http(s) URL in a message body — decides which link gets the preview card under the bubble. */
fun firstUrl(text: String): String? = MESSAGE_URL_RE.find(text)?.value?.trimEnd('.', ',', ';', ':', '!', '?')

/** Module-level so every card for the same URL across the app shares one fetch and one result —
 *  the service already Redis-caches per URL; this just avoids re-asking it on every recomposition
 *  (switching threads, scrolling a long history back into view, etc). */
private object LinkPreviewCache {
    private val mutex = Mutex()
    private val results = HashMap<String, LinkPreview?>()

    suspend fun get(api: RelayApi, url: String): LinkPreview? {
        results[url]?.let { return it }
        if (results.containsKey(url)) return null // cached "no preview" (explicit null value)
        return mutex.withLock {
            results[url]?.let { return@withLock it }
            if (results.containsKey(url)) return@withLock null
            // A card scrolled off-screen mid-fetch cancels this coroutine (LazyColumn recycling
            // the composable that launched it) — that's routine, not a failure, and must NOT be
            // cached as "no preview" or the card would never retry the next time it's scrolled
            // back into view. Only a genuine API failure (network error, bad response) counts.
            val preview = try {
                api.linkPreview(url)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
            results[url] = preview
            preview
        }
    }
}

@Composable
private fun rememberLinkPreview(api: RelayApi, url: String): LinkPreview? {
    val state by produceState<LinkPreview?>(initialValue = null, key1 = url) {
        value = LinkPreviewCache.get(api, url)
    }
    return state
}

/** Social-app-style link preview card (title, description, image, site name) for a URL found in a
 *  message — same shape WhatsApp/iMessage/etc. show under a bubble. Renders nothing at all while
 *  loading or when the page has no usable Open Graph metadata, so a plain link doesn't get an
 *  empty shell. */
@Composable
fun LinkPreviewCard(api: RelayApi, url: String, isOwn: Boolean, fg: androidx.compose.ui.graphics.Color) {
    val preview = rememberLinkPreview(api, url)
    if (preview == null || (preview.title == null && preview.description == null)) return
    val context = androidx.compose.ui.platform.LocalContext.current
    // Sizing/spacing mirrors the web card exactly (packages/web/react's .relay-link-preview):
    // 280dp max width, 144dp image, 10dp corner radius, a hairline border rather than just a fill.
    Column(
        Modifier
            .padding(top = 6.dp)
            .widthIn(max = 280.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(fg.copy(alpha = if (isOwn) 0.1f else 0.05f))
            .border(1.dp, fg.copy(alpha = if (isOwn) 0.25f else 0.12f), RoundedCornerShape(10.dp))
            .clickable { runCatching { context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url))) } },
    ) {
        preview.imageUrl?.let {
            AsyncImage(model = it, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxWidth().height(144.dp))
        }
        Column(Modifier.padding(9.dp)) {
            preview.siteName?.let { Text(it.uppercase(), fontSize = 9.sp, fontWeight = FontWeight.SemiBold, color = fg.copy(alpha = 0.65f)) }
            preview.title?.let { Text(it, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = fg, maxLines = 2, overflow = TextOverflow.Ellipsis) }
            preview.description?.let { Text(it, fontSize = 11.sp, color = fg.copy(alpha = 0.8f), maxLines = 2, overflow = TextOverflow.Ellipsis) }
        }
    }
}

/** Compose-time preview shown above the input while a URL sits in the draft, before it's sent —
 *  dismissible, and purely cosmetic: it never changes what's sent, since the recipient's bubble
 *  renders its own `LinkPreviewCard` from the same URL once the message lands. */
@Composable
fun ComposeLinkPreviewCard(api: RelayApi, url: String, onDismiss: () -> Unit) {
    val preview = rememberLinkPreview(api, url)
    if (preview == null || (preview.title == null && preview.description == null)) return
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        preview.imageUrl?.let {
            AsyncImage(model = it, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.size(44.dp).clip(RoundedCornerShape(8.dp)))
        }
        Column(Modifier.weight(1f)) {
            preview.siteName?.let { Text(it.uppercase(), fontSize = 9.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f)) }
            Text(preview.title ?: preview.url, fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            preview.description?.let { Text(it, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        IconButton(onClick = onDismiss) { Icon(Icons.Filled.Close, "Dismiss link preview") }
    }
}
