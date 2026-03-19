package co.rivium.storage

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * RiviumStorage Android SDK
 *
 * Official SDK for RiviumStorage file storage and image transformation service.
 *
 * Example:
 * ```kotlin
 * val storage = RiviumStorage(apiKey = "rv_live_xxx")
 *
 * // Upload a file
 * val file = storage.upload(
 *     bucketId = "my-bucket",
 *     path = "images/photo.jpg",
 *     data = imageBytes,
 *     options = UploadOptions(contentType = "image/jpeg")
 * )
 *
 * // Get transformed URL
 * val thumbnailUrl = storage.getTransformUrl(
 *     fileId = file.id,
 *     transforms = ImageTransforms(width = 200, height = 200)
 * )
 * ```
 */
class RiviumStorage(
    apiKey: String,
    timeout: Int = 30,
    userId: String? = null,
    client: OkHttpClient? = null
) {
    private val config = RiviumStorageConfig(apiKey = apiKey, timeout = timeout, userId = userId)
    private var currentUserId: String? = userId

    private val httpClient: OkHttpClient = client ?: OkHttpClient.Builder()
        .connectTimeout(timeout.toLong(), TimeUnit.SECONDS)
        .readTimeout(timeout.toLong(), TimeUnit.SECONDS)
        .writeTimeout(timeout.toLong(), TimeUnit.SECONDS)
        .build()

    private val gson: Gson = GsonBuilder()
        .setDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'")
        .create()

    // ==========================================
    // User ID
    // ==========================================

    /** Current user ID for bucket policy enforcement */
    val userId: String? get() = currentUserId

    /**
     * Set the user ID for bucket policy enforcement.
     * Pass null to clear the user ID.
     */
    fun setUserId(userId: String?) {
        currentUserId = userId
    }

    // ==========================================
    // Private Helpers
    // ==========================================

    private fun buildRequest(method: String, endpoint: String, body: Map<String, Any>? = null): Request {
        val url = "${config.baseUrl}$endpoint"
        val builder = Request.Builder()
            .url(url)
            .addHeader("x-api-key", config.apiKey)
            .addHeader("Content-Type", "application/json")

        currentUserId?.let { builder.addHeader("x-user-id", it) }

        when (method) {
            "GET" -> builder.get()
            "POST" -> {
                val json = if (body != null) gson.toJson(body) else "{}"
                builder.post(json.toRequestBody("application/json".toMediaType()))
            }
            "PUT" -> {
                val json = if (body != null) gson.toJson(body) else "{}"
                builder.put(json.toRequestBody("application/json".toMediaType()))
            }
            "DELETE" -> builder.delete()
        }

        return builder.build()
    }

    private suspend inline fun <reified T> request(
        method: String,
        endpoint: String,
        body: Map<String, Any>? = null
    ): T {
        val request = buildRequest(method, endpoint, body)

        val responseBody: String
        val code: Int
        try {
            val response = withContext(Dispatchers.IO) {
                httpClient.newCall(request).execute()
            }
            code = response.code
            responseBody = response.body?.string() ?: ""

            if (!response.isSuccessful) {
                var message = "HTTP $code"
                try {
                    val error = gson.fromJson(responseBody, ApiErrorResponse::class.java)
                    message = error.message ?: error.error ?: message
                } catch (_: Exception) {}
                throw RiviumStorageException.HttpException(code, message)
            }
        } catch (e: RiviumStorageException) {
            throw e
        } catch (e: Exception) {
            throw RiviumStorageException.NetworkException(e.message ?: "Unknown error")
        }

        if (code == 204 || responseBody.isEmpty()) {
            @Suppress("UNCHECKED_CAST")
            return emptyMap<String, Any>() as T
        }

        return gson.fromJson(responseBody, T::class.java)
    }

    private suspend inline fun <reified T> requestList(
        method: String,
        endpoint: String
    ): List<T> {
        val request = buildRequest(method, endpoint)

        val responseBody: String
        try {
            val response = withContext(Dispatchers.IO) {
                httpClient.newCall(request).execute()
            }
            responseBody = response.body?.string() ?: ""

            if (!response.isSuccessful) {
                var message = "HTTP ${response.code}"
                try {
                    val error = gson.fromJson(responseBody, ApiErrorResponse::class.java)
                    message = error.message ?: error.error ?: message
                } catch (_: Exception) {}
                throw RiviumStorageException.HttpException(response.code, message)
            }
        } catch (e: RiviumStorageException) {
            throw e
        } catch (e: Exception) {
            throw RiviumStorageException.NetworkException(e.message ?: "Unknown error")
        }

        if (responseBody.isEmpty()) {
            return emptyList()
        }

        val type = TypeToken.getParameterized(List::class.java, T::class.java).type
        return gson.fromJson(responseBody, type)
    }

    // ==========================================
    // Bucket Operations
    // ==========================================

    /**
     * List all buckets in the project
     */
    suspend fun listBuckets(): List<Bucket> {
        return requestList("GET", "/api/v1/buckets")
    }

    /**
     * Get bucket by ID
     */
    suspend fun getBucket(bucketId: String): Bucket {
        return request("GET", "/api/v1/buckets/$bucketId")
    }

    /**
     * Get bucket by name
     */
    suspend fun getBucketByName(name: String): Bucket {
        return request("GET", "/api/v1/buckets/name/$name")
    }

    // ==========================================
    // File Operations
    // ==========================================

    /**
     * Upload a file to a bucket
     *
     * @param bucketId Bucket ID or name
     * @param path File path within the bucket
     * @param data File content as ByteArray
     * @param options Upload options (contentType, metadata)
     * @return The uploaded file
     */
    suspend fun upload(
        bucketId: String,
        path: String,
        data: ByteArray,
        options: UploadOptions = UploadOptions()
    ): StorageFile = withContext(Dispatchers.IO) {
        val url = "${config.baseUrl}/api/v1/buckets/$bucketId/files"

        // Detect content type
        val contentType = options.contentType ?: getMimeType(path) ?: "application/octet-stream"

        // Build multipart body
        val fileName = path.substringAfterLast('/')
        val bodyBuilder = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart(
                "file",
                fileName,
                data.toRequestBody(contentType.toMediaType())
            )
            .addFormDataPart("path", path)

        // Add metadata
        options.metadata?.let {
            bodyBuilder.addFormDataPart("metadata", gson.toJson(it))
        }

        val requestBuilder = Request.Builder()
            .url(url)
            .addHeader("x-api-key", config.apiKey)
            .post(bodyBuilder.build())

        currentUserId?.let { requestBuilder.addHeader("x-user-id", it) }

        val request = requestBuilder.build()

        try {
            val response = httpClient.newCall(request).execute()
            val responseBody = response.body?.string() ?: ""

            if (!response.isSuccessful) {
                var message = "Upload failed: ${response.code}"
                try {
                    val error = gson.fromJson(responseBody, ApiErrorResponse::class.java)
                    message = error.message ?: error.error ?: message
                } catch (_: Exception) {}
                throw RiviumStorageException.UploadException(message)
            }

            gson.fromJson(responseBody, StorageFile::class.java)
        } catch (e: RiviumStorageException) {
            throw e
        } catch (e: Exception) {
            throw RiviumStorageException.UploadException(e.message ?: "Unknown error")
        }
    }

    /**
     * List files in a bucket
     */
    suspend fun listFiles(
        bucketId: String,
        options: ListFilesOptions = ListFilesOptions()
    ): ListFilesResult {
        val params = mutableListOf<String>()
        options.prefix?.let { params.add("prefix=${URLEncoder.encode(it, "UTF-8")}") }
        options.limit?.let { params.add("limit=$it") }
        options.cursor?.let { params.add("cursor=${URLEncoder.encode(it, "UTF-8")}") }

        val query = if (params.isNotEmpty()) "?${params.joinToString("&")}" else ""

        return request("GET", "/api/v1/buckets/$bucketId/files$query")
    }

    /**
     * Get file by ID
     */
    suspend fun getFile(fileId: String): StorageFile {
        return request("GET", "/api/v1/files/$fileId")
    }

    /**
     * Get file by path in bucket
     */
    suspend fun getFileByPath(bucketId: String, path: String): StorageFile {
        val encodedPath = URLEncoder.encode(path, "UTF-8")
        return request("GET", "/api/v1/buckets/$bucketId/files/$encodedPath")
    }

    /**
     * Download file content
     */
    suspend fun download(fileId: String): ByteArray = withContext(Dispatchers.IO) {
        val url = "${config.baseUrl}/api/v1/files/$fileId/download"

        val requestBuilder = Request.Builder()
            .url(url)
            .addHeader("x-api-key", config.apiKey)
            .get()

        currentUserId?.let { requestBuilder.addHeader("x-user-id", it) }

        val request = requestBuilder.build()

        try {
            val response = httpClient.newCall(request).execute()

            if (!response.isSuccessful) {
                throw RiviumStorageException.DownloadException("Download failed: ${response.code}")
            }

            response.body?.bytes() ?: throw RiviumStorageException.DownloadException("Empty response body")
        } catch (e: RiviumStorageException) {
            throw e
        } catch (e: Exception) {
            throw RiviumStorageException.DownloadException(e.message ?: "Unknown error")
        }
    }

    /**
     * Delete a file by ID
     */
    suspend fun delete(fileId: String) {
        request<Map<String, Any>>("DELETE", "/api/v1/files/$fileId")
    }

    /**
     * Delete a file by path in bucket
     */
    suspend fun deleteByPath(bucketId: String, path: String) {
        val encodedPath = URLEncoder.encode(path, "UTF-8")
        request<Map<String, Any>>("DELETE", "/api/v1/buckets/$bucketId/files/$encodedPath")
    }

    /**
     * Delete multiple files by IDs
     *
     * @param fileIds Array of file IDs to delete
     * @return The number of files successfully deleted
     */
    suspend fun deleteMany(fileIds: List<String>): DeleteManyResult {
        return request("POST", "/api/v1/files/delete-many", mapOf("ids" to fileIds))
    }

    // ==========================================
    // URL Generation
    // ==========================================

    /**
     * Get public URL for a file (only works for public buckets)
     */
    fun getUrl(fileId: String): String {
        return "${config.baseUrl}/api/v1/files/$fileId/url"
    }

    /**
     * Get URL with image transformations
     *
     * @param fileId The file ID
     * @param transforms Image transformation options
     * @return The transform URL
     */
    fun getTransformUrl(fileId: String, transforms: ImageTransforms? = null): String {
        val params = transforms?.toQueryParams() ?: emptyMap()
        val query = if (params.isNotEmpty()) {
            "?" + params.entries.joinToString("&") { (key, value) ->
                "$key=${URLEncoder.encode(value, "UTF-8")}"
            }
        } else ""
        return "${config.baseUrl}/api/v1/transform/$fileId$query"
    }

    /**
     * Get download URL (for direct access without SDK)
     */
    fun getDownloadUrl(fileId: String): String {
        return "${config.baseUrl}/api/v1/files/$fileId/download"
    }

    // ==========================================
    // Helpers
    // ==========================================

    private fun getMimeType(path: String): String? {
        val extension = path.substringAfterLast('.', "").lowercase()

        val mimeTypes = mapOf(
            "jpg" to "image/jpeg",
            "jpeg" to "image/jpeg",
            "png" to "image/png",
            "gif" to "image/gif",
            "webp" to "image/webp",
            "avif" to "image/avif",
            "svg" to "image/svg+xml",
            "pdf" to "application/pdf",
            "json" to "application/json",
            "txt" to "text/plain",
            "html" to "text/html",
            "css" to "text/css",
            "js" to "application/javascript",
            "mp4" to "video/mp4",
            "mov" to "video/quicktime",
            "mp3" to "audio/mpeg",
            "wav" to "audio/wav",
            "zip" to "application/zip",
            "doc" to "application/msword",
            "docx" to "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "xls" to "application/vnd.ms-excel",
            "xlsx" to "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
        )

        return mimeTypes[extension]
    }
}
