package dev.relay.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.pdf.PdfRenderer
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.util.Base64
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File

// Client-side caps mirroring the service's own (service/src/routes/conversations.js) — rejecting
// an oversized pick here gives an instant, specific error instead of a round trip that 400s.
private const val MAX_IMAGE_BYTES = 1_800_000L   // leaves room for base64 under the service's 2.5MB data: URL cap
private const val MAX_ATTACHMENT_BYTES = 9_500_000L // leaves room under the service's 14MB (~10MB raw) file cap

/** What a picked attachment turns into before it's handed to SendMessageInput. */
sealed class PickedAttachment {
    data class Image(val dataUrl: String) : PickedAttachment()
    data class FileAttachment(val dataUrl: String, val name: String, val mime: String, val thumbnail: String?, val durationSec: Int?) : PickedAttachment()
    data class Audio(val dataUrl: String, val durationSec: Int) : PickedAttachment()
}

private fun bytesToDataUrl(bytes: ByteArray, mime: String) = "data:$mime;base64,${Base64.encodeToString(bytes, Base64.NO_WRAP)}"

/** Reads a picked/captured Uri fully into memory, size-capped, mime resolved via ContentResolver. */
private suspend fun readUri(context: Context, uri: Uri, cap: Long): Pair<ByteArray, String>? = withContext(Dispatchers.IO) {
    val mime = context.contentResolver.getType(uri) ?: "application/octet-stream"
    val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return@withContext null
    if (bytes.size > cap) return@withContext null
    bytes to mime
}

/**
 * Downscales a full-res camera capture to a JPEG under the image cap — a raw phone photo is
 * routinely 5-10MB, well over what the service accepts inline.
 */
private fun downscaleJpeg(bytes: ByteArray, maxDimension: Int = 1280, quality: Int = 70): ByteArray {
    val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return bytes
    val scale = maxDimension.toFloat() / maxOf(bitmap.width, bitmap.height)
    val scaled = if (scale < 1f) Bitmap.createScaledBitmap(bitmap, (bitmap.width * scale).toInt(), (bitmap.height * scale).toInt(), true) else bitmap
    val out = ByteArrayOutputStream()
    scaled.compress(Bitmap.CompressFormat.JPEG, quality, out)
    return out.toByteArray()
}

/** A small JPEG frame + rounded duration for a video Uri — entirely client-side, this SDK never
 *  decodes video server-side either (see service/src/lib/mediaStorage.js's own comment). Best
 *  effort: nulls on any failure rather than blocking the send. */
private suspend fun videoThumbnail(context: Context, uri: Uri): Pair<String?, Int?> = withContext(Dispatchers.IO) {
    val retriever = MediaMetadataRetriever()
    try {
        retriever.setDataSource(context, uri)
        val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
        val durationSec = durationMs?.let { Math.round(it / 1000.0).toInt() }
        val frame = retriever.getFrameAtTime(100_000) // 0.1s in, matches iOS's own choice
        val thumb = frame?.let {
            val out = ByteArrayOutputStream()
            it.compress(Bitmap.CompressFormat.JPEG, 60, out)
            "data:image/jpeg;base64,${Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)}"
        }
        thumb to durationSec
    } catch (e: Exception) {
        null to null
    } finally {
        retriever.release()
    }
}

/** A small JPEG render of a PDF's first page — entirely client-side, mirroring videoThumbnail's
 *  best-effort style (nulls on any failure, e.g. a corrupt or encrypted PDF, rather than blocking
 *  the send). Uses Android's built-in PdfRenderer (API 21+), no extra dependency needed. */
private suspend fun pdfThumbnail(context: Context, uri: Uri): String? = withContext(Dispatchers.IO) {
    var pfd: ParcelFileDescriptor? = null
    var renderer: PdfRenderer? = null
    var page: PdfRenderer.Page? = null
    try {
        pfd = context.contentResolver.openFileDescriptor(uri, "r") ?: return@withContext null
        renderer = PdfRenderer(pfd)
        if (renderer.pageCount <= 0) return@withContext null
        page = renderer.openPage(0)
        val maxDimension = 640
        val scale = maxDimension.toFloat() / maxOf(page.width, page.height)
        val width = if (scale < 1f) (page.width * scale).toInt() else page.width
        val height = if (scale < 1f) (page.height * scale).toInt() else page.height
        val bitmap = Bitmap.createBitmap(maxOf(width, 1), maxOf(height, 1), Bitmap.Config.ARGB_8888)
        page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 60, out)
        "data:image/jpeg;base64,${Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)}"
    } catch (e: Exception) {
        null
    } finally {
        page?.close()
        renderer?.close()
        pfd?.close()
    }
}

/** Handles a gallery-picked image or video Uri (ActivityResultContracts.PickVisualMedia). */
suspend fun loadPickedMedia(context: Context, uri: Uri): Result<PickedAttachment> {
    val mime = context.contentResolver.getType(uri) ?: "application/octet-stream"
    return if (mime.startsWith("video/")) {
        val read = readUri(context, uri, MAX_ATTACHMENT_BYTES) ?: return Result.failure(IllegalStateException("That video is too large (max ~9.5MB)."))
        val (thumb, duration) = videoThumbnail(context, uri)
        val name = "video_${System.currentTimeMillis()}.${mime.substringAfterLast('/')}"
        Result.success(PickedAttachment.FileAttachment(bytesToDataUrl(read.first, mime), name, mime, thumb, duration))
    } else if (mime == "application/pdf") {
        val read = readUri(context, uri, MAX_ATTACHMENT_BYTES) ?: return Result.failure(IllegalStateException("That file is too large (max ~9.5MB)."))
        val thumb = pdfThumbnail(context, uri)
        val name = "document_${System.currentTimeMillis()}.pdf"
        Result.success(PickedAttachment.FileAttachment(bytesToDataUrl(read.first, mime), name, mime, thumb, null))
    } else {
        // Try as-is first (fast path for an already-small photo); only downscale if it's over cap.
        val direct = readUri(context, uri, MAX_IMAGE_BYTES)
        val (bytes, resultMime) = direct
            ?: readUri(context, uri, Long.MAX_VALUE)?.let { (bytes, _) -> downscaleJpeg(bytes) to "image/jpeg" }
            ?: return Result.failure(IllegalStateException("Couldn't load that photo."))
        Result.success(PickedAttachment.Image(bytesToDataUrl(bytes, resultMime)))
    }
}

/** Handles a file-picker Uri (ActivityResultContracts.GetContent) — any mime, always a File
 *  attachment (never routed through imageUrl even if it happens to be a picture, matching
 *  DevBattel's own File-vs-Gallery distinction: File attachments always show as a document card). */
suspend fun loadPickedFile(context: Context, uri: Uri): Result<PickedAttachment> {
    val read = readUri(context, uri, MAX_ATTACHMENT_BYTES) ?: return Result.failure(IllegalStateException("That file is too large (max ~9.5MB)."))
    val (bytes, mime) = read
    val name = queryDisplayName(context, uri) ?: uri.lastPathSegment ?: "file"
    val (thumb, duration) = if (mime.startsWith("video/")) videoThumbnail(context, uri)
        else if (mime == "application/pdf") pdfThumbnail(context, uri) to null
        else null to null
    return Result.success(PickedAttachment.FileAttachment(bytesToDataUrl(bytes, mime), name, mime, thumb, duration))
}

private fun queryDisplayName(context: Context, uri: Uri): String? = runCatching {
    context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
        if (cursor.moveToFirst()) cursor.getString(0) else null
    }
}.getOrNull()

/** A content:// Uri under this app's FileProvider (see relay-ui's AndroidManifest.xml) for
 *  TakePicture() to capture into — the file itself is discarded (read once, then left in cache;
 *  the OS clears app cache under storage pressure, same as any other transient camera temp file). */
fun newCameraCaptureUri(context: Context): Uri {
    val dir = File(context.cacheDir, "relay-camera").apply { mkdirs() }
    val file = File(dir, "capture_${System.currentTimeMillis()}.jpg")
    return FileProvider.getUriForFile(context, "${context.packageName}.relayfileprovider", file)
}

/** Reads back a camera capture, downscaling the same way a gallery pick would. */
suspend fun loadCameraCapture(context: Context, uri: Uri): Result<PickedAttachment> {
    val read = readUri(context, uri, Long.MAX_VALUE) ?: return Result.failure(IllegalStateException("Couldn't read that photo."))
    return Result.success(PickedAttachment.Image(bytesToDataUrl(downscaleJpeg(read.first), "image/jpeg")))
}
