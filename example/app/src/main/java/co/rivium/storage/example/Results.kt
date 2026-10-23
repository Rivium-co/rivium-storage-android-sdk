package co.rivium.storage.example

import android.graphics.Bitmap
import co.rivium.storage.RiviumStorageException
import co.rivium.storage.StorageErrorCode
import co.rivium.storage.StorageFile
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Locale

/** What a demo card shows after its action ran. */
data class ActionResult(
    val kind: Kind,
    val lines: List<String>,
    /** An image decoded in the app (download() bytes, a transform fetched with headers). */
    val bitmap: Bitmap? = null,
    /** An image loaded by URL with no headers (public link, signed link). */
    val imageUrl: String? = null,
    /** A link the user can open in the browser. */
    val openUrl: String? = null,
    /** A short headline shown above the lines (a refused transform: "Format not supported"). */
    val title: String? = null,
) {
    enum class Kind {
        /** Worked. */
        SUCCESS,
        /** Failed, and failing was the point of the demo (expected error). */
        EXPECTED_ERROR,
        /** Failed when it should have worked. */
        ERROR,
        /** Worked when it should have failed, or a setup note. */
        WARNING,
    }

    companion object {
        fun ok(vararg lines: String) = ActionResult(Kind.SUCCESS, lines.toList())
        fun ok(lines: List<String>) = ActionResult(Kind.SUCCESS, lines)
        fun warn(vararg lines: String) = ActionResult(Kind.WARNING, lines.toList())
        fun error(e: Throwable) = ActionResult(Kind.ERROR, describeError(e))
    }
}

/**
 * Every field of a Storage error, so the screen shows exactly what the SDK threw: every
 * RiviumStorageException carries the server's `code` (type_not_allowed, file_too_large,
 * executable_blocked, path_taken, token_*; null when the server gave none, e.g. a 403 from the rules)
 * and the HTTP `statusCode` (null when no response arrived).
 */
fun describeError(e: Throwable): List<String> {
    val lines = mutableListOf<String>()
    lines += "Exception: ${e.javaClass.name.substringAfterLast('.').replace('$', '.')}"
    when (e) {
        is RiviumStorageException -> {
            lines += "code: ${e.code ?: "(none)"}"
            lines += "statusCode: ${e.statusCode ?: "(no response)"}"
        }
        is ExampleHttp.HttpFailure -> lines += "status: ${e.status}"
        else -> {}
    }
    lines += "message: ${e.message ?: "(none)"}"
    return lines
}

/**
 * A refused image transform as an app should show it: a title per `code`, the server's
 * message, the code and what to do. The app fetches transform URLs itself, so it reads
 * `code` / `message` from the JSON body of the non-2xx answer (no raw JSON, no URL).
 */
data class TransformRefusal(
    val title: String,
    val message: String,
    val code: String?,
    val status: Int,
    val hint: String?,
    /** Seconds from the `Retry-After` header (`transform_busy` sends 2), when the answer had one. */
    val retryAfter: Int? = null,
) {
    fun toResult() = ActionResult(
        ActionResult.Kind.ERROR,
        listOfNotNull(message, "code: ${code ?: "(none)"} · HTTP $status", hint?.let { "What to do: $it" }),
        title = title,
    )

    companion object {
        fun from(status: Int, body: String, retryAfterHeader: String? = null): TransformRefusal {
            val json = runCatching { JSONObject(body) }.getOrNull()
            val code = json?.optString("code")?.takeIf { it.isNotBlank() }
            val message = json?.let { j ->
                j.optJSONArray("message")?.let { a -> (0 until a.length()).joinToString(", ") { a.optString(it) } }
                    ?: j.optString("message").takeIf { it.isNotBlank() }
            } ?: "The server answered HTTP $status."
            val (title, hint) = when (code) {
                StorageErrorCode.IMAGE_FORMAT_NOT_SUPPORTED -> "Format not supported" to "Convert it to JPEG, PNG or WebP and upload it again."
                StorageErrorCode.NOT_AN_IMAGE -> "Not an image" to "Pick an image file."
                StorageErrorCode.IMAGE_DAMAGED -> "Image damaged" to "Upload it again."
                StorageErrorCode.IMAGE_TOO_LARGE -> "Image too large" to "Use a smaller image (up to 50 megapixels)."
                StorageErrorCode.INVALID_TRANSFORM -> "Invalid options" to "Change the transform options."
                StorageErrorCode.SOURCE_TOO_LARGE -> "File too large" to "Use an image under 25 MB."
                StorageErrorCode.PLAN_FEATURE_LOCKED -> "Not in your plan" to
                    "Image transforms aren't in this project's plan (Free). Upgrade the plan, or show the plain public link."
                StorageErrorCode.TOO_MANY_VARIANTS -> "Too many sizes" to
                    "This image already has 50 sizes / formats. Use a few fixed sizes (for example 200, 600 and 1200 wide)."
                StorageErrorCode.TRANSFORM_BUSY -> "Busy" to "Image processing is busy. Try again in a moment."
                StorageErrorCode.TOKEN_REQUIRED, StorageErrorCode.TOKEN_INVALID, StorageErrorCode.TOKEN_EXPIRED ->
                    "Sign-in needed" to "Sign in again (Setup screen), then retry."
                StorageErrorCode.ACCESS_DENIED -> "Access denied" to "The bucket's access rules refused this user."
                else -> when (status) {
                    403 -> "Access denied" to "The bucket's access rules refused this user."
                    404 -> "File not found" to "Upload the image again."
                    else -> "Couldn't transform the image" to null
                }
            }
            return TransformRefusal(title, message, code, status, hint, retryAfterHeader?.trim()?.toIntOrNull()?.takeIf { it >= 0 })
        }
    }
}

/** The progress demo's PNG was refused because the bucket does not allow image/png (`code` type_not_allowed). */
fun Throwable.isPngNotAllowed(): Boolean = isTypeNotAllowedOrLegacy()

/** The bucket refused the file's format: code type_not_allowed, or (older Storage API, no codes yet) a 400 with the known message. */
fun Throwable.isTypeNotAllowedOrLegacy(): Boolean =
    (this is RiviumStorageException && isTypeNotAllowed) || legacyMatch("type_not_allowed")

const val PNG_NOT_ALLOWED =
    "This bucket doesn't allow image/png. Allow PNG in the Console (Bucket › Settings › Allowed file types) to run this check."

/**
 * True when the error is the one a demo expects. Each hint is `code:<server code>` (matched against the
 * exception's `code`, never the message text), `status:<HTTP status>`, or a word in the class name / message
 * (for errors without a code: network, 403 from the rules).
 */
fun Throwable.matches(expected: List<String>): Boolean {
    val name = javaClass.name.substringAfterLast('.').replace('$', '.')
    val storage = this as? RiviumStorageException
    return expected.any { hint ->
        when {
            // An older Storage API refuses with the known message but no code: that counts too (BucketSetup.kt).
            hint.startsWith("code:") -> storage?.code == hint.removePrefix("code:") || legacyMatch(hint.removePrefix("code:"))
            hint.startsWith("status:") -> storage?.statusCode?.toString() == hint.removePrefix("status:")
            else -> "$name ${message.orEmpty()}".contains(hint, ignoreCase = true)
        }
    }
}

private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

fun StorageFile.describe(): List<String> = listOf(
    "id: $id",
    "path: $path",
    "fileName: $fileName",
    "mimeType: $mimeType",
    "size: ${formatBytes(size.toLong())}",
    "bucketId: $bucketId",
    "metadata: ${metadata ?: "{}"}",
    "uploadedBy: ${uploadedBy ?: "-"}",
    "checksum: ${checksum ?: "-"}",
    "createdAt: ${dateFormat.format(createdAt)}",
)

fun formatBytes(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> String.format(Locale.US, "%.1f KB", bytes / 1024.0)
    else -> String.format(Locale.US, "%.2f MB", bytes / (1024.0 * 1024.0))
}
