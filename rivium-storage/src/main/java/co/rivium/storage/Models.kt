package co.rivium.storage

import com.google.gson.annotations.SerializedName
import java.util.Date

/**
 * RiviumStorage configuration
 */
data class RiviumStorageConfig(
    /** API Key for project identification (rv_live_xxx or rv_test_xxx) */
    val apiKey: String,
    /** Request timeout in seconds */
    val timeout: Int = 30
) {
    /** The Rivium Storage API, always over HTTPS; apps can't change it. */
    internal val baseUrl: String get() = BASE_URL

    internal companion object {
        const val BASE_URL = "https://storage.rivium.co"
    }
}

/**
 * A bucket, as Rivium Storage shows it to apps.
 */
data class Bucket(
    val id: String,
    val name: String,
    val description: String? = null,
    /** `public` or `private`. */
    val visibility: String,
    /** The file types the bucket takes; null = every type. */
    val allowedMimeTypes: List<String>? = null,
    /** Largest file the bucket takes, in bytes. */
    val maxFileSize: Int,
    /** True when the bucket refuses programs (exe, apk, shell scripts...). */
    val blockExecutables: Boolean,
    val createdAt: Date,
    val updatedAt: Date
)

/**
 * A file, as Rivium Storage shows it to apps.
 */
data class StorageFile(
    val id: String,
    val bucketId: String,
    val path: String,
    val fileName: String,
    val mimeType: String,
    /** Bytes; a Long, since files can be bigger than 2 GB. */
    val size: Long,
    /** MD5 hex of the content. */
    val checksum: String? = null,
    /** Your own metadata, as sent with the upload. */
    val metadata: Map<String, Any>? = null,
    /** The app user who uploaded it (token user, or the user your server named). */
    val uploadedBy: String? = null,
    val createdAt: Date,
    val updatedAt: Date,
    /** Last upload or replace of the content; null on older files. */
    val uploadedAt: Date? = null
)

/**
 * List files result
 */
data class ListFilesResult(
    val files: List<StorageFile>,
    /** Pass as [ListFilesOptions.cursor] for the next page; null on the last page. */
    val nextCursor: String? = null
)

/**
 * Upload options
 */
data class UploadOptions(
    /** Content type (MIME type) of the file */
    val contentType: String? = null,
    /** Custom metadata to attach to the file */
    val metadata: Map<String, Any>? = null
)

/**
 * Progress of an upload, passed to the `onProgress` callback of
 * [RiviumStorage.upload].
 *
 * [totalBytes] is the size of the upload request body: your file plus a few
 * hundred bytes of form fields. [bytesSent] counts the bytes handed to the
 * network so far and never exceeds [totalBytes]; the last event has
 * `bytesSent == totalBytes`.
 */
data class UploadProgress(
    /** Bytes of the request body sent so far. */
    val bytesSent: Long,
    /** Size of the whole request body. */
    val totalBytes: Long
) {
    /** Progress from 0.0 to 1.0. */
    val fraction: Double
        get() = if (totalBytes <= 0L) 1.0 else (bytesSent.toDouble() / totalBytes).coerceIn(0.0, 1.0)
}

/**
 * List files options
 */
data class ListFilesOptions(
    /** Filter files by path prefix */
    val prefix: String? = null,
    /** Maximum number of files to return */
    val limit: Int? = null,
    /** Cursor for pagination */
    val cursor: String? = null
)

/**
 * Image transformation options. [RiviumStorage.getTransformUrl],
 * [RiviumStorage.getPublicUrl] and [RiviumStorage.publicImageUrl] check them
 * first and throw `invalid_transform` (400) for a value outside these ranges.
 */
data class ImageTransforms(
    /** Target width in pixels, 1 or more (values above 4096 are sent as 4096) */
    val width: Int? = null,
    /** Target height in pixels, 1 or more (values above 4096 are sent as 4096) */
    val height: Int? = null,
    /** Resize mode: `cover`, `contain`, `fill`, `inside` or `outside` */
    val fit: String? = null,
    /** Output format: `jpeg` (the default), `png`, `webp` or `avif` */
    val format: String? = null,
    /** Compression quality, 1..100 */
    val quality: Int? = null,
    /** Blur, 0..20 (0 = none) */
    val blur: Int? = null,
    /** Sharpen, 0..10 (0 = none) */
    val sharpen: Int? = null,
    /** Rotation in degrees, a whole number (multiples of 90 recommended) */
    val rotate: Int? = null
) {
    /**
     * The query parameters, in a fixed order, after checking every option:
     * `format` jpeg / png / webp / avif (`jpg` is sent as `jpeg`), `fit`
     * cover / contain / fill / inside / outside, `width` / `height` 1 or more
     * (above 4096 sent as 4096), `quality` 1..100, `blur` 0..20, `sharpen`
     * 0..10. Anything else throws [RiviumStorageException.HttpException] 400
     * with code `invalid_transform`.
     */
    fun toQueryParams(): Map<String, String> {
        fun bad(message: String) = RiviumStorageException.HttpException(400, message, StorageErrorCode.INVALID_TRANSFORM)
        fun size(name: String, value: Int?): Int? {
            if (value == null) return null
            if (value < 1) throw bad("$name must be at least 1")
            return minOf(value, MAX_DIMENSION)
        }
        fun range(name: String, value: Int?, min: Int, max: Int): Int? {
            if (value != null && (value < min || value > max)) throw bad("$name must be between $min and $max")
            return value
        }
        fun oneOf(name: String, value: String?, allowed: List<String>): String? {
            if (value.isNullOrEmpty()) return null
            var v = value.lowercase()
            if (name == "format" && v == "jpg") v = "jpeg"
            if (v !in allowed) throw bad("$name must be one of ${allowed.joinToString(", ")}")
            return v
        }

        val w = size("width", width)
        val h = size("height", height)
        val fitValue = oneOf("fit", fit, FITS)
        val formatValue = oneOf("format", format, FORMATS)
        val q = range("quality", quality, 1, 100)
        val blurValue = range("blur", blur, 0, 20)
        val sharpenValue = range("sharpen", sharpen, 0, 10)

        val params = linkedMapOf<String, String>()
        w?.let { params["w"] = it.toString() }
        h?.let { params["h"] = it.toString() }
        fitValue?.let { params["fit"] = it }
        formatValue?.let { params["f"] = it }
        q?.let { params["q"] = it.toString() }
        blurValue?.let { params["blur"] = it.toString() }
        sharpenValue?.let { params["sharpen"] = it.toString() }
        rotate?.let { params["rotate"] = it.toString() }
        return params
    }

    companion object {
        /** Output formats the transform endpoint makes. */
        val FORMATS: List<String> = listOf("jpeg", "png", "webp", "avif")
        /** Resize modes of the transform endpoint. */
        val FITS: List<String> = listOf("cover", "contain", "fill", "inside", "outside")
        /** Largest width / height; bigger values are sent as this (the server does the same). */
        const val MAX_DIMENSION: Int = 4096
    }
}

/**
 * Delete many files result
 */
data class DeleteManyResult(
    /** Number of files successfully deleted */
    val deleted: Int
)

/**
 * The `code` values a Rivium Storage refusal carries ([RiviumStorageException.code]).
 * The same list is in every Rivium Storage SDK. A refusal without a code (a
 * `404`, a `429`) has `code == null`; check [RiviumStorageException.statusCode] then.
 */
object StorageErrorCode {
    /** `400`: the bucket does not allow this file type (Console: Bucket › Settings › File types). */
    const val TYPE_NOT_ALLOWED = "type_not_allowed"
    /** `400`: the file is bigger than the bucket's maximum file size. */
    const val FILE_TOO_LARGE = "file_too_large"
    /** `400`: the bucket blocks executable files and this one is (by name or content). */
    const val EXECUTABLE_BLOCKED = "executable_blocked"
    /** `400`: the upload had no file. */
    const val FILE_REQUIRED = "file_required"
    /** `409`: a file already exists at this path. */
    const val PATH_TAKEN = "path_taken"
    /**
     * `400`: the path was refused. The SDK refuses before sending anything a
     * path with a `.` or `..` segment (also written as `%2e`), an id or bucket
     * name that is `.` or `..`, and a new file path with `?`, `#`, `%` or
     * text-direction characters; the server refuses the rest (control
     * characters, a name starting or ending with a space, too long).
     */
    const val INVALID_PATH = "invalid_path"
    /** `400`, from the SDK before anything is sent: the file path is empty (or only spaces and `/`). */
    const val PATH_REQUIRED = "path_required"
    /** `400`, from the SDK before anything is sent: the file id is empty. */
    const val FILE_ID_REQUIRED = "file_id_required"
    /** `400`, from the SDK before anything is sent: the bucket id or name is empty. */
    const val BUCKET_REQUIRED = "bucket_required"
    /** `400`: deleteMany needs 1 to 1,000 file ids. */
    const val INVALID_IDS = "invalid_ids"
    /** `400`: an access rule is wrong (the message names the first problem). */
    const val INVALID_RULES = "invalid_rules"
    /** `400`: an image transform option isn't allowed (the SDK checks before building the URL). */
    const val INVALID_TRANSFORM = "invalid_transform"
    /** `413`: the image is too large to transform (over 25 MB). */
    const val SOURCE_TOO_LARGE = "source_too_large"
    /** `415`: the image's format can't be resized (the message names it and lists the supported formats). */
    const val IMAGE_FORMAT_NOT_SUPPORTED = "image_format_not_supported"
    /** `400`: the file isn't an image, so it can't be resized (the message names its type). */
    const val NOT_AN_IMAGE = "not_an_image"
    /** `422`: the image is damaged or incomplete (it may not have finished uploading). */
    const val IMAGE_DAMAGED = "image_damaged"
    /** `413`: the image has too many pixels to resize (up to 50 megapixels; the message gives its size). */
    const val IMAGE_TOO_LARGE = "image_too_large"
    /** `401`: the project requires a Rivium user token and the request carried none. */
    const val TOKEN_REQUIRED = "token_required"
    /** `401`: the user token is not valid for this project. */
    const val TOKEN_INVALID = "token_invalid"
    /** `401`: the user token has expired. */
    const val TOKEN_EXPIRED = "token_expired"
    /** `403`: the bucket's access rules refused the request. */
    const val ACCESS_DENIED = "access_denied"
    /** `403`: the project's IP allow-list doesn't include this caller. */
    const val IP_NOT_ALLOWED = "ip_not_allowed"
    /** `403`: only your server (with the server secret) may do this. */
    const val SERVER_SECRET_REQUIRED = "server_secret_required"
    /** `400`: a signed upload was refused when it was registered (too big, type, rules or plan). */
    const val UPLOAD_REJECTED = "upload_rejected"
    /** `409`: nothing has been uploaded to the signed upload link yet. */
    const val UPLOAD_MISSING = "upload_missing"
    /** `409`: the signed upload link expired before a file arrived. */
    const val UPLOAD_EXPIRED = "upload_expired"
    /**
     * `503`: image processing is busy (every image worker is in use). Try again after the
     * `Retry-After` header's seconds (2). Image loaders (Coil, Glide) load the URL again by themselves.
     */
    const val TRANSFORM_BUSY = "transform_busy"
    /**
     * `429`, public image URLs only: this image already has 50 different sizes / formats, so a new
     * combination is refused (the existing ones keep working). Use a few fixed sizes.
     */
    const val TOO_MANY_VARIANTS = "too_many_variants"
    /** `403`: image transforms aren't included in the project's plan (Free). Upgrade the plan to use them. */
    const val PLAN_FEATURE_LOCKED = "plan_feature_locked"
}

/**
 * RiviumStorage error.
 *
 * Every error the server sent carries its HTTP [statusCode] and, when the
 * server named the reason, its [code] (see [StorageErrorCode]):
 *
 * ```kotlin
 * try {
 *     storage.upload("photos", "a/clip.mov", bytes)
 * } catch (e: RiviumStorageException) {
 *     when {
 *         e.isTypeNotAllowed -> show("This bucket doesn't take this file type")
 *         e.isFileTooLarge -> show("File is too big")
 *         else -> show(e.message)
 *     }
 * }
 * ```
 */
sealed class RiviumStorageException(message: String) : Exception(message) {

    /**
     * The server's machine-readable reason, such as `type_not_allowed`,
     * `file_too_large`, `executable_blocked`, `access_denied` or a token code
     * (see [StorageErrorCode]). Null when the server gave none (a `404`, a
     * `429`) or no response arrived.
     */
    open val code: String? get() = null

    /** The HTTP status of the server's answer; null when no response arrived (network error). */
    open val statusCode: Int? get() = null

    /** The bucket does not allow this file type (`type_not_allowed`). */
    val isTypeNotAllowed: Boolean get() = code == StorageErrorCode.TYPE_NOT_ALLOWED

    /** The file is bigger than the bucket allows (`file_too_large`). */
    val isFileTooLarge: Boolean get() = code == StorageErrorCode.FILE_TOO_LARGE

    /** The file is a program, which Storage never accepts (`executable_blocked`). */
    val isExecutableBlocked: Boolean get() = code == StorageErrorCode.EXECUTABLE_BLOCKED

    class NetworkException(message: String) : RiviumStorageException("Network error: $message")

    class HttpException @JvmOverloads constructor(
        override val statusCode: Int,
        message: String,
        override val code: String? = null
    ) : RiviumStorageException("HTTP $statusCode: $message")

    class UploadException @JvmOverloads constructor(
        message: String,
        override val code: String? = null,
        override val statusCode: Int? = null
    ) : RiviumStorageException("Upload error: $message")

    class DownloadException @JvmOverloads constructor(
        message: String,
        override val code: String? = null,
        override val statusCode: Int? = null
    ) : RiviumStorageException("Download error: $message")

    class InvalidResponseException : RiviumStorageException("Invalid response from server")
    class EncodingException(message: String) : RiviumStorageException("Encoding error: $message")

    /**
     * The project requires a signed Rivium user token and the request carried
     * none (`token_required`). Give RiviumStorage a `userTokenProvider`, or
     * call `setUserToken`.
     */
    class UserTokenRequiredException(message: String) : RiviumStorageException(message) {
        override val code: String get() = StorageErrorCode.TOKEN_REQUIRED
        override val statusCode: Int get() = 401
    }

    /**
     * The server refused the user token ([code] is `token_expired` or
     * `token_invalid`) and the provider had no other one.
     */
    class UserTokenRejectedException(override val code: String, message: String) : RiviumStorageException(message) {
        override val statusCode: Int get() = 401
    }
}

/**
 * Internal response wrapper for API responses
 */
internal data class ApiErrorResponse(
    val message: String? = null,
    val error: String? = null,
    val code: String? = null
)
