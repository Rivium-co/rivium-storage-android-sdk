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
 * val storage = RiviumStorage.builder("rv_live_xxx")
 *     // Return null when no one is signed in.
 *     .userTokenProvider { myBackend.fetchRiviumToken() }
 *     .build()
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
class RiviumStorage @JvmOverloads constructor(
    apiKey: String,
    timeout: Int = 30,
    userId: String? = null,
    client: OkHttpClient? = null,
    userTokenProvider: (() -> String?)? = null,
    userToken: String? = null
) {
    private val config = RiviumStorageConfig(apiKey = apiKey, timeout = timeout, userId = userId)
    private var currentUserId: String? = userId

    /**
     * The signed Rivium user token the SDK sends (`x-user-token`).
     *
     * Call `storage.userTokens.set(token)` after your app signs a user in or
     * refreshes their token, or give it a `provider` so the SDK fetches one by
     * itself. Same API as `userTokens` in the Rivium Sync Android SDK.
     */
    val userTokens: UserTokenStore = UserTokenStore().apply {
        userTokenProvider?.let { provider = it }
        userToken?.let { set(it) }
    }

    private val httpClient: OkHttpClient = client ?: OkHttpClient.Builder()
        .connectTimeout(timeout.toLong(), TimeUnit.SECONDS)
        .readTimeout(timeout.toLong(), TimeUnit.SECONDS)
        .writeTimeout(timeout.toLong(), TimeUnit.SECONDS)
        .build()

    private val gson: Gson = GsonBuilder()
        .setDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'")
        .create()

    companion object {
        /** This SDK's version, sent to Rivium Storage as `X-Rivium-SDK: android/<version>`. */
        const val SDK_VERSION: String = BuildConfig.SDK_VERSION

        /** Name this SDK reports in the `X-Rivium-SDK` header. */
        const val SDK_NAME: String = "android"

        /** Header that names the SDK making the request. */
        internal const val SDK_HEADER = "X-Rivium-SDK"

        /** Value of [SDK_HEADER]: `android/<version>`. Only the public SDK name and version. */
        internal const val SDK_HEADER_VALUE = "$SDK_NAME/$SDK_VERSION"

        /**
         * Start building a [RiviumStorage]:
         * ```kotlin
         * val storage = RiviumStorage.builder("rv_live_xxx")
         *     .userTokenProvider { myBackend.fetchRiviumToken() }
         *     .build()
         * ```
         */
        @JvmStatic
        fun builder(apiKey: String): Builder = Builder(apiKey)
    }

    /** Builder with the same option names as the Rivium Sync Android SDK. */
    class Builder(private val apiKey: String) {
        private var timeout: Int = 30
        private var userId: String? = null
        private var client: OkHttpClient? = null
        private var userToken: String? = null
        private var userTokenProvider: (() -> String?)? = null

        /** Request timeout in seconds (default 30). */
        fun timeout(seconds: Int) = apply { this.timeout = seconds }

        /** Use your own OkHttpClient. */
        fun client(client: OkHttpClient) = apply { this.client = client }

        /**
         * Legacy user ID sent as `x-user-id` for `{userId}` bucket rules. The
         * server cannot verify it - anyone can change it in a copy of your
         * app. Prefer [userTokenProvider]. Not sent while a user token is in use.
         */
        fun userId(userId: String) = apply { this.userId = userId }

        /**
         * A signed Rivium user token, minted by YOUR backend with its server
         * secret (`POST https://auth.rivium.co/users/token`), or the token
         * Rivium Auth gave you at sign-in. Prefer [userTokenProvider] so the
         * SDK can refresh it by itself.
         */
        fun userToken(token: String) = apply { this.userToken = token }

        /**
         * Called on a background thread whenever the SDK needs a user token -
         * before the first request, shortly before expiry, and after the server
         * reports one expired or invalid. Fetch it from your own backend (or
         * return Rivium Auth's token). Return null when no one is signed in.
         */
        fun userTokenProvider(provider: () -> String?) = apply { this.userTokenProvider = provider }

        fun build(): RiviumStorage = RiviumStorage(
            apiKey = apiKey,
            timeout = timeout,
            userId = userId,
            client = client,
            userTokenProvider = userTokenProvider,
            userToken = userToken
        )
    }

    // ==========================================
    // User identity
    // ==========================================

    /** Current legacy user ID for bucket policy enforcement */
    val userId: String? get() = currentUserId

    /**
     * Set the legacy user ID for bucket policy enforcement.
     * Pass null to clear the user ID.
     *
     * The server cannot verify a user ID sent by an app; prefer a user token
     * ([setUserToken] or a provider). While a token is in use, it is not sent.
     */
    fun setUserId(userId: String?) {
        currentUserId = userId
    }

    /**
     * Replace the signed Rivium user token (same as `userTokens.set(token)`).
     * Pass null to stop sending one, for example when the user signs out.
     */
    fun setUserToken(token: String?) {
        userTokens.set(token)
    }

    /** Set or remove the token provider after construction (same as `userTokens.provider = provider`). */
    fun setUserTokenProvider(provider: (() -> String?)?) {
        userTokens.provider = provider
    }

    /**
     * Ask the provider again before the next request (same as
     * `userTokens.refresh()`). Call when the user signs in or out.
     */
    fun refreshUserToken() {
        userTokens.refresh()
    }

    // ==========================================
    // Private Helpers
    // ==========================================

    /**
     * Auth headers: `x-user-token` when there is a token, otherwise the legacy `x-user-id`.
     * Every Rivium Storage API request goes through here, so it also carries `X-Rivium-SDK`.
     */
    private fun withAuth(request: Request, token: String?): Request {
        val builder = request.newBuilder()
            .header("x-api-key", config.apiKey)
            .header(SDK_HEADER, SDK_HEADER_VALUE)
        if (token != null) {
            builder.header("x-user-token", token).removeHeader("x-user-id")
        } else {
            builder.removeHeader("x-user-token")
            currentUserId?.let { builder.header("x-user-id", it) }
        }
        return builder.build()
    }

    /** A request's response, fully read. */
    private class RawResponse(val code: Int, val body: ByteArray) {
        val isSuccessful: Boolean get() = code in 200..299
        val text: String get() = String(body, Charsets.UTF_8)
    }

    /**
     * Sends [request] (blocking - call on Dispatchers.IO) with auth headers.
     * On a 401 saying the token expired or is invalid, asks the provider once
     * more and re-sends once. Request bodies here are byte-backed, so they can
     * be sent twice (uploads too).
     */
    private fun send(request: Request): RawResponse {
        val token = userTokens.current()
        val first = execute(withAuth(request, token))
        if (first.code != 401 || userTokens.provider == null) return first

        val code = errorCodeOf(first.text)
        val retry = code == "token_expired" || code == "token_invalid" ||
            (code == "token_required" && token == null)
        if (!retry) return first

        val fresh = userTokens.renewAfterRejection(token) ?: return first
        return execute(withAuth(request, fresh))
    }

    private fun execute(request: Request): RawResponse {
        httpClient.newCall(request).execute().use { response ->
            return RawResponse(response.code, response.body?.bytes() ?: ByteArray(0))
        }
    }

    private fun errorCodeOf(body: String): String? = try {
        gson.fromJson(body, ApiErrorResponse::class.java)?.code
    } catch (_: Exception) {
        null
    }

    /**
     * Turns a failed response into the exception to throw: a user token
     * exception for the token codes, otherwise [fallback].
     */
    private fun failure(
        response: RawResponse,
        defaultMessage: String,
        fallback: (String) -> RiviumStorageException
    ): RiviumStorageException {
        var message = defaultMessage
        var code: String? = null
        try {
            val error = gson.fromJson(response.text, ApiErrorResponse::class.java)
            message = error?.message ?: error?.error ?: message
            code = error?.code
        } catch (_: Exception) {}
        if (response.code == 401) {
            when (code) {
                "token_required" -> return RiviumStorageException.UserTokenRequiredException(
                    "This project requires a Rivium user token. Give RiviumStorage a userTokenProvider " +
                        "(or call setUserToken) that returns the token your backend mints with " +
                        "POST https://auth.rivium.co/users/token (a Rivium Auth sign-in token works as is). " +
                        "Server said: $message"
                )
                "token_expired", "token_invalid" -> return RiviumStorageException.UserTokenRejectedException(
                    code,
                    "The Rivium user token was refused ($code). Give RiviumStorage a userTokenProvider that " +
                        "returns a fresh token, or call setUserToken with one. Server said: $message"
                )
            }
        }
        return fallback(message)
    }

    private fun buildRequest(method: String, endpoint: String, body: Map<String, Any>? = null): Request {
        val url = "${config.baseUrl}$endpoint"
        val builder = Request.Builder()
            .url(url)
            .addHeader("Content-Type", "application/json")

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

    private suspend fun sendForText(request: Request): Pair<Int, String> {
        val response: RawResponse
        try {
            response = withContext(Dispatchers.IO) { send(request) }
        } catch (e: RiviumStorageException) {
            throw e
        } catch (e: Exception) {
            throw RiviumStorageException.NetworkException(e.message ?: "Unknown error")
        }
        if (!response.isSuccessful) {
            throw failure(response, "HTTP ${response.code}") {
                RiviumStorageException.HttpException(response.code, it)
            }
        }
        return response.code to response.text
    }

    private suspend inline fun <reified T> request(
        method: String,
        endpoint: String,
        body: Map<String, Any>? = null
    ): T {
        val (code, responseBody) = sendForText(buildRequest(method, endpoint, body))

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
        val (_, responseBody) = sendForText(buildRequest(method, endpoint))

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

        // Byte-backed multipart body: can be re-sent after a token refresh.
        val request = Request.Builder()
            .url(url)
            .post(bodyBuilder.build())
            .build()

        val response = try {
            send(request)
        } catch (e: RiviumStorageException) {
            throw e
        } catch (e: Exception) {
            throw RiviumStorageException.UploadException(e.message ?: "Unknown error")
        }

        if (!response.isSuccessful) {
            throw failure(response, "Upload failed: ${response.code}") {
                RiviumStorageException.UploadException(it)
            }
        }

        try {
            gson.fromJson(response.text, StorageFile::class.java)
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

        val request = Request.Builder().url(url).get().build()

        val response = try {
            send(request)
        } catch (e: RiviumStorageException) {
            throw e
        } catch (e: Exception) {
            throw RiviumStorageException.DownloadException(e.message ?: "Unknown error")
        }

        if (!response.isSuccessful) {
            throw failure(response, "Download failed: ${response.code}") {
                RiviumStorageException.DownloadException("Download failed: ${response.code}")
            }
        }

        response.body
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
    //
    // These return plain URLs for image loaders, browsers or other HTTP
    // clients. A URL cannot carry headers, so neither the API key nor the user
    // token travels with it: they work for public buckets only. For private
    // files use download(), or a signed URL from your server.

    /**
     * Get public URL for a file (only works for public buckets).
     * Carries no user token or API key (a URL has no headers).
     */
    fun getUrl(fileId: String): String {
        return "${config.baseUrl}/api/v1/files/$fileId/url"
    }

    /**
     * Get URL with image transformations.
     * Carries no user token or API key (a URL has no headers), so it only
     * works for files in public buckets.
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
     * Get download URL (for direct access without SDK).
     * Carries no user token or API key (a URL has no headers), so it only
     * works for files in public buckets; use [download] otherwise.
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
