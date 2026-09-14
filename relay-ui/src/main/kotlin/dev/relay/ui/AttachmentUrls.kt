package dev.relay.ui

import android.net.Uri

/**
 * Attachment/avatar URLs on a message are strings another client put there. The service only
 * admits `http(s)://` and `data:` at send time, but a message that predates that check, a
 * different client build, or a compromised host backend can still deliver anything — and handing
 * an arbitrary string to `ACTION_VIEW` launches whatever app owns that scheme (`tel:`, `sms:`,
 * `market://`, `intent:`, a third-party deep link), while an image loader given `file://` would
 * pull an app-private file into a chat bubble. Only these three schemes are ever opened or loaded.
 */
internal fun isSafeAttachmentUrl(url: String?): Boolean {
    if (url.isNullOrBlank()) return false
    val scheme = runCatching { Uri.parse(url).scheme }.getOrNull()?.lowercase() ?: return false
    return scheme == "http" || scheme == "https" || scheme == "data"
}

/** `url` when it is safe to load/open, otherwise null — for direct use in `AsyncImage(model = …)`. */
internal fun safeAttachmentUrl(url: String?): String? = url?.takeIf { isSafeAttachmentUrl(it) }
