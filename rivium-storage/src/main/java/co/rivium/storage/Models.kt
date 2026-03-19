package co.rivium.storage

import com.google.gson.annotations.SerializedName
import java.util.Date

/**
 * RiviumStorage configuration
 */
data class RiviumStorageConfig(
    /** API Key for project identification (rv_live_xxx or rv_test_xxx) */
    val apiKey: String,
    /** Base URL for RiviumStorage API (internal, not configurable) */
    val baseUrl: String = "https://storage.rivium.co",
    /** Request timeout in seconds */
    val timeout: Int = 30,
    /** Optional user ID for bucket policy enforcement */
    val userId: String? = null
)

/**
 * Bucket model
 */
data class Bucket(
    val id: String,
    val name: String,
    val projectId: String,
    val organizationId: String,
    val visibility: String,
    val allowedMimeTypes: List<String>? = null,
    val maxFileSize: Int? = null,
    val policiesEnabled: Boolean = false,
    val isActive: Boolean = true,
    val createdAt: Date,
    val updatedAt: Date
)

/**
 * Storage file model
 */
data class StorageFile(
    val id: String,
    val bucketId: String,
    val path: String,
    val fileName: String,
    val mimeType: String,
    val size: Int,
    val checksum: String? = null,
    val storageKey: String,
    val metadata: Map<String, Any>? = null,
    val uploadedBy: String? = null,
    val isDeleted: Boolean = false,
    val createdAt: Date,
    val updatedAt: Date,
    val url: String? = null
)

/**
 * List files result
 */
data class ListFilesResult(
    val files: List<StorageFile>,
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
 * Image transformation options
 */
data class ImageTransforms(
    /** Target width in pixels */
    val width: Int? = null,
    /** Target height in pixels */
    val height: Int? = null,
    /** Resize mode: cover, contain, fill, inside, outside */
    val fit: String? = null,
    /** Output format: jpeg, png, webp, avif */
    val format: String? = null,
    /** Compression quality (1-100) */
    val quality: Int? = null,
    /** Blur amount (0-100) */
    val blur: Int? = null,
    /** Sharpen amount (0-100) */
    val sharpen: Int? = null,
    /** Rotation in degrees (90, 180, 270) */
    val rotate: Int? = null
) {
    /**
     * Convert to query parameters
     */
    fun toQueryParams(): Map<String, String> {
        val params = mutableMapOf<String, String>()
        width?.let { params["w"] = it.toString() }
        height?.let { params["h"] = it.toString() }
        fit?.let { params["fit"] = it }
        format?.let { params["f"] = it }
        quality?.let { params["q"] = it.toString() }
        blur?.let { params["blur"] = it.toString() }
        sharpen?.let { params["sharpen"] = it.toString() }
        rotate?.let { params["rotate"] = it.toString() }
        return params
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
 * RiviumStorage error
 */
sealed class RiviumStorageException(message: String) : Exception(message) {
    class NetworkException(message: String) : RiviumStorageException("Network error: $message")
    class HttpException(val statusCode: Int, message: String) : RiviumStorageException("HTTP $statusCode: $message")
    class UploadException(message: String) : RiviumStorageException("Upload error: $message")
    class DownloadException(message: String) : RiviumStorageException("Download error: $message")
    class InvalidResponseException : RiviumStorageException("Invalid response from server")
    class EncodingException(message: String) : RiviumStorageException("Encoding error: $message")
}

/**
 * Internal response wrapper for API responses
 */
internal data class ApiErrorResponse(
    val message: String? = null,
    val error: String? = null
)
