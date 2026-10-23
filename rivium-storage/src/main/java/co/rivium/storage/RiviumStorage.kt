package co.rivium.storage

import android.os.Handler
import android.os.Looper
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonParser
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.URLEncoder
import java.util.Date
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit

/**
 * RiviumStorage Android SDK
 *
 * Official SDK for RiviumStorage file storage and image transformation service.
 *
 * Example:
 * ```kotlin
 * val storage = RiviumStorage.builder("rv_live_xxx")
 *     // Rivium Auth's provider (called off the main thread); null while no one is signed in.
 *     .userTokenProvider(RiviumAuth.shared.blockingTokenProvider)
 *     .build()
 *
 * // Upload a file (waits until a user is signed in)
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
    client: OkHttpClient? = null,
    userTokenProvider: (() -> String?)? = null,
    userToken: String? = null,
    /**
     * How long a file request may wait for a user token, in milliseconds;
     * then it fails with `token_required`. Null (default): wait until a user
     * is signed in, like the Rivium Sync SDK.
     */
    val userTokenWaitTimeoutMillis: Long? = null
) {
    private val config = RiviumStorageConfig(apiKey = apiKey, timeout = timeout)

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

    /** File requests wait here while there is no user token. */
    private val tokenWait = UserTokenWait(userTokenWaitTimeoutMillis) {
        withContext(Dispatchers.IO) { userTokens.current() }
    }.also { wait -> userTokens.onChange = { wait.wake() } }

    /**
     * True while at least one file request is waiting for a user token: there
     * is none and the provider (if any) returned null, so no one is signed in.
     * The waiting requests go on by themselves, in order, once the app calls
     * [setUserToken] or, with a provider, [refreshUserToken] after sign-in.
     */
    val isAwaitingUserToken: Boolean
        get() = tokenWait.isAwaiting

    /**
     * Called once each time file requests start waiting for a user token (on
     * the background thread of the request). Show your sign-in screen here.
     */
    var onAwaitingUserToken: (() -> Unit)?
        get() = tokenWait.onAwaiting
        set(value) {
            tokenWait.onAwaiting = value
        }

    /**
     * Never follows redirects, so `x-api-key` and `x-user-token` are never sent to another host; a 3xx answer is
     * an error. An app's own client is used through `newBuilder()` (same pool, interceptors and timeouts).
     */
    private val httpClient: OkHttpClient = (
        client?.newBuilder() ?: OkHttpClient.Builder()
            .connectTimeout(timeout.toLong(), TimeUnit.SECONDS)
            .readTimeout(timeout.toLong(), TimeUnit.SECONDS)
            .writeTimeout(timeout.toLong(), TimeUnit.SECONDS)
        )
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    /**
     * Where upload progress callbacks run. Null = the main thread. Tests set
     * a direct executor (there is no main looper in a JVM unit test).
     */
    internal var progressExecutor: Executor? = null

    private val mainThreadExecutor: Executor by lazy {
        val handler = Handler(Looper.getMainLooper())
        Executor { handler.post(it) }
    }

    private val gson: Gson = GsonBuilder()
        .registerTypeAdapter(Date::class.java, IsoDateAdapter)
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

        /**
         * The image URL for a public link you already have (from [getPublicUrl],
         * saved in your database), with no request: the link plus the transform
         * options, checked and written exactly as `getPublicUrl(id, transforms)`
         * writes them (only the options that are set, short names, fixed order,
         * so the same options always give the same URL: one cached image on the
         * CDN). A query or `#` already on the link is dropped. The URL needs no
         * headers: give it to Coil, Glide or any image loader.
         *
         * ```kotlin
         * AsyncImage(model = RiviumStorage.publicImageUrl(user.avatarUrl, ImageTransforms(width = 200, format = "webp")), ...)
         * ```
         *
         * @throws RiviumStorageException.HttpException 400 `invalid_transform` for an
         *   option the server would refuse, 400 `invalid_path` when [publicUrl] isn't
         *   a public file link (`https://storage.rivium.co/public/<bucketId>/<path>`).
         */
        @JvmStatic
        @JvmOverloads
        fun publicImageUrl(publicUrl: String, transforms: ImageTransforms? = null): String {
            val query = transformQuery(transforms)
            val base = publicUrl.trim().split('?', '#', limit = 2)[0]
            if (!PUBLIC_LINK.matches(base)) {
                throw RiviumStorageException.HttpException(400, PUBLIC_URL_MESSAGE, StorageErrorCode.INVALID_PATH)
            }
            return base + query
        }

        internal const val PUBLIC_URL_MESSAGE =
            "Expected a public file link (https://storage.rivium.co/public/<bucketId>/<path>), as getPublicUrl() returns"

        /** `https://host/public/<bucketId>/<path...>`: the public link of a file. */
        private val PUBLIC_LINK = Regex("^https?://[^/?#]+/public/[^/?#]+/[^?#]+$", RegexOption.IGNORE_CASE)

        /** `?w=..&h=..` for the transform options (checked first), or `""` when none is set. */
        internal fun transformQuery(transforms: ImageTransforms?): String {
            val params = transforms?.toQueryParams() ?: emptyMap()
            if (params.isEmpty()) return ""
            return "?" + params.entries.joinToString("&") { (key, value) ->
                "$key=${URLEncoder.encode(value, "UTF-8")}"
            }
        }

        internal const val DOT_PATH_MESSAGE = "File paths can't contain '.' or '..' segments"
        internal const val DOT_ID_MESSAGE = "Ids and bucket names can't be empty, '.' or '..'"

        /** True when the value is empty, or only spaces and `/`. */
        internal fun isBlank(value: String): Boolean = value.all { it.isWhitespace() || it == '/' }

        /** Text-direction marks (U+200E, U+200F, U+202A-U+202E, U+2066-U+2069), refused in new paths. */
        internal fun isBidiMark(c: Char): Boolean =
            c == '\u200E' || c == '\u200F' || c in '\u202A'..'\u202E' || c in '\u2066'..'\u2069'

        /** True for `.` or `..`, also percent-encoded (`%2e%2E`, `%252e`...), as a URL parser or server reads it. */
        internal fun isDotSegment(segment: String): Boolean {
            var value = segment
            repeat(4) {
                if (value == "." || value == "..") return true
                if (!value.contains('%')) return false
                val decoded = percentDecode(value) ?: return false
                if (decoded == value) return false
                value = decoded
            }
            return false
        }

        /** Decodes `%XX` escapes as UTF-8 (a `+` stays a `+`); null when an escape is malformed. */
        private fun percentDecode(value: String): String? {
            val out = java.io.ByteArrayOutputStream()
            var i = 0
            while (i < value.length) {
                val c = value[i]
                if (c == '%') {
                    if (i + 2 >= value.length) return null
                    val byte = value.substring(i + 1, i + 3).toIntOrNull(16) ?: return null
                    out.write(byte)
                    i += 3
                } else {
                    out.write(c.toString().toByteArray(Charsets.UTF_8))
                    i++
                }
            }
            return String(out.toByteArray(), Charsets.UTF_8)
        }
    }

    /** Builder with the same option names as the Rivium Sync Android SDK. */
    class Builder(private val apiKey: String) {
        private var timeout: Int = 30
        private var client: OkHttpClient? = null
        private var userToken: String? = null
        private var userTokenProvider: (() -> String?)? = null
        private var userTokenWaitTimeoutMillis: Long? = null
        private var onAwaitingUserToken: (() -> Unit)? = null

        /** Request timeout in seconds (default 30). */
        fun timeout(seconds: Int) = apply { this.timeout = seconds }

        /** Use your own OkHttpClient. */
        fun client(client: OkHttpClient) = apply { this.client = client }

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
         * reports one expired or invalid. With Rivium Auth pass
         * `RiviumAuth.shared.blockingTokenProvider`; or fetch a token from your
         * own backend. Return null when no one is signed in: file requests
         * then wait until [refreshUserToken] finds a user.
         */
        fun userTokenProvider(provider: () -> String?) = apply { this.userTokenProvider = provider }

        /**
         * Fail a file request with `token_required` when it waited this long
         * for a user token. Default: no limit (it waits until sign-in).
         */
        fun userTokenWaitTimeout(timeout: Long, unit: TimeUnit) = apply {
            this.userTokenWaitTimeoutMillis = unit.toMillis(timeout)
        }

        /** Same as setting [RiviumStorage.onAwaitingUserToken]. */
        fun onAwaitingUserToken(callback: () -> Unit) = apply { this.onAwaitingUserToken = callback }

        fun build(): RiviumStorage = RiviumStorage(
            apiKey = apiKey,
            timeout = timeout,
            client = client,
            userTokenProvider = userTokenProvider,
            userToken = userToken,
            userTokenWaitTimeoutMillis = userTokenWaitTimeoutMillis
        ).also { it.onAwaitingUserToken = onAwaitingUserToken }
    }

    // ==========================================
    // User identity
    // ==========================================

    /**
     * Replace the signed Rivium user token (same as `userTokens.set(token)`).
     * File requests waiting for a token go on with it. Pass null when the
     * user signs out: file requests then wait for the next sign-in.
     */
    fun setUserToken(token: String?) {
        userTokens.set(token)
    }

    /** Set or remove the token provider after construction (same as `userTokens.provider = provider`). */
    fun setUserTokenProvider(provider: (() -> String?)?) {
        userTokens.provider = provider
    }

    /**
     * Ask the provider again (same as `userTokens.refresh()`). Call when the
     * user signs in or out, for example from Rivium Auth's state listener;
     * file requests waiting for a token ask the provider at once.
     */
    fun refreshUserToken() {
        userTokens.refresh()
    }

    // ==========================================
    // Private Helpers
    // ==========================================

    /**
     * Auth headers: the key, and `x-user-token` when there is a token. Every Rivium Storage API request goes
     * through here, so it also carries `X-Rivium-SDK`. A `x-user-id` header is never sent.
     */
    private fun withAuth(request: Request, token: String?): Request {
        val builder = request.newBuilder()
            .header("x-api-key", config.apiKey)
            .header(SDK_HEADER, SDK_HEADER_VALUE)
            .removeHeader("x-user-id")
        if (token != null) {
            builder.header("x-user-token", token)
        } else {
            builder.removeHeader("x-user-token")
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

    /**
     * Sends a file request, which needs a user token. Without one it waits (see [isAwaitingUserToken]); a
     * `token_required` answer waits for the next token change too. `token_expired` / `token_invalid`: the provider
     * is asked once more and the request re-sent once; when the provider then has no token, it waits.
     */
    private suspend fun sendWithUserToken(request: Request): RawResponse {
        var changedSince: Long? = null
        while (true) {
            val token = tokenWait.token(changedSince)
            changedSince = null
            val sentAt = tokenWait.generation()
            val first = withContext(Dispatchers.IO) { execute(withAuth(request, token)) }
            if (first.code != 401) return first

            when (errorCodeOf(first.text)) {
                "token_required" -> {
                    changedSince = sentAt
                }
                "token_expired", "token_invalid" -> {
                    if (userTokens.provider == null) return first
                    val fresh = withContext(Dispatchers.IO) { userTokens.renewAfterRejection(token) }
                    if (fresh == null) {
                        if (userTokens.value != null) return first // Same token again: refused for good.
                        continue // Signed out meanwhile: wait for a token.
                    }
                    val retriedAt = tokenWait.generation()
                    val second = withContext(Dispatchers.IO) { execute(withAuth(request, fresh)) }
                    if (second.code == 401 && errorCodeOf(second.text) == "token_required") {
                        changedSince = retriedAt
                    } else {
                        return second
                    }
                }
                else -> return first
            }
        }
    }

    private fun execute(request: Request): RawResponse {
        httpClient.newCall(request).execute().use { response ->
            return RawResponse(response.code, response.body?.bytes() ?: ByteArray(0))
        }
    }

    private fun errorCodeOf(body: String): String? = parseError(body).code

    /**
     * Reads the server's error body `{statusCode, error, message, code}`.
     * `message` may be a string or a list of strings; anything unreadable
     * (not JSON, an HTML page from a proxy) gives empty fields.
     */
    private fun parseError(body: String): ApiErrorResponse = try {
        val json = JsonParser.parseString(body)
        if (!json.isJsonObject) {
            ApiErrorResponse()
        } else {
            val obj = json.asJsonObject
            fun text(name: String): String? {
                val value = obj.get(name) ?: return null
                return when {
                    value.isJsonPrimitive && value.asJsonPrimitive.isString -> value.asString
                    value.isJsonArray -> value.asJsonArray
                        .filter { it.isJsonPrimitive }
                        .joinToString(", ") { it.asString }
                        .ifEmpty { null }
                    else -> null
                }
            }
            ApiErrorResponse(message = text("message"), error = text("error"), code = text("code"))
        }
    } catch (_: Exception) {
        ApiErrorResponse()
    }

    /**
     * Turns a failed response into the exception to throw: a user token
     * exception for the token codes, otherwise [fallback], which gets the
     * server's message (or [defaultMessage]) and its `code`.
     */
    private fun failure(
        response: RawResponse,
        defaultMessage: String,
        fallback: (message: String, code: String?) -> RiviumStorageException
    ): RiviumStorageException {
        val error = parseError(response.text)
        val message = error.message ?: error.error ?: defaultMessage
        val code = error.code
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
        return fallback(message, code)
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

    private suspend fun sendForText(request: Request, needsUserToken: Boolean): Pair<Int, String> {
        val response: RawResponse
        try {
            response = if (needsUserToken) {
                sendWithUserToken(request)
            } else {
                withContext(Dispatchers.IO) { send(request) }
            }
        } catch (e: RiviumStorageException) {
            throw e
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw RiviumStorageException.NetworkException(e.message ?: "Unknown error")
        }
        if (!response.isSuccessful) {
            throw failure(response, "HTTP ${response.code}") { message, code ->
                RiviumStorageException.HttpException(response.code, message, code)
            }
        }
        return response.code to response.text
    }

    private suspend inline fun <reified T> request(
        method: String,
        endpoint: String,
        body: Map<String, Any>? = null,
        needsUserToken: Boolean = true
    ): T {
        val (code, responseBody) = sendForText(buildRequest(method, endpoint, body), needsUserToken)

        if (code == 204 || responseBody.isEmpty()) {
            @Suppress("UNCHECKED_CAST")
            return emptyMap<String, Any>() as T
        }

        return gson.fromJson(responseBody, T::class.java)
    }

    private suspend inline fun <reified T> requestList(
        method: String,
        endpoint: String,
        needsUserToken: Boolean = true
    ): List<T> {
        val (_, responseBody) = sendForText(buildRequest(method, endpoint), needsUserToken)

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
     * List all buckets in the project. Works with the key alone (no user token needed, never waits).
     */
    suspend fun listBuckets(): List<Bucket> {
        return requestList("GET", "/api/v1/buckets", needsUserToken = false)
    }

    /**
     * Get bucket by ID. Works with the key alone (never waits).
     */
    suspend fun getBucket(bucketId: String): Bucket {
        return request("GET", "/api/v1/buckets/${bucketSegment(bucketId)}", needsUserToken = false)
    }

    /**
     * Get bucket by name. Works with the key alone (never waits).
     */
    suspend fun getBucketByName(name: String): Bucket {
        return request("GET", "/api/v1/buckets/name/${bucketSegment(name)}", needsUserToken = false)
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
     * Needs a signed-in user: without a user token the upload waits (see
     * [isAwaitingUserToken]) and reports no progress until it starts.
     *
     * @param onProgress Called on the main thread while the file is sent, with
     *   the bytes sent so far and the size of the whole request body (your
     *   file plus a few hundred bytes of form fields). At most about every
     *   50 ms or 1%; the last call has `bytesSent == totalBytes`. Without it,
     *   the upload is sent exactly as before.
     * @return The uploaded file
     */
    suspend fun upload(
        bucketId: String,
        path: String,
        data: ByteArray,
        options: UploadOptions = UploadOptions(),
        onProgress: ((UploadProgress) -> Unit)? = null
    ): StorageFile {
        val bucket = bucketSegment(bucketId)
        // Normalised like the server stores it; `?`, `#`, `%`, text-direction
        // characters and `.` / `..` segments are refused before anything is sent.
        val filePath = checkUploadPath(path)
        return uploadChecked(bucket, filePath, data, options, onProgress)
    }

    private suspend fun uploadChecked(
        bucket: String,
        path: String,
        data: ByteArray,
        options: UploadOptions,
        onProgress: ((UploadProgress) -> Unit)?
    ): StorageFile {
        val url = "${config.baseUrl}/api/v1/buckets/$bucket/files"

        // No content type from the app: the type the Console's file types
        // table gives this extension; otherwise octet-stream, and the server
        // decides from the extension and the file's content.
        val contentType = options.contentType ?: FileTypes.contentTypeFor(path) ?: "application/octet-stream"

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
        val multipart = bodyBuilder.build()
        val body = if (onProgress == null) {
            multipart
        } else {
            val reporter = UploadProgressReporter(
                totalBytes = multipart.contentLength(),
                executor = progressExecutor ?: mainThreadExecutor,
                callback = onProgress
            )
            ProgressRequestBody(multipart, reporter)
        }
        val request = Request.Builder()
            .url(url)
            .post(body)
            .build()

        val response = try {
            sendWithUserToken(request)
        } catch (e: RiviumStorageException) {
            throw e
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw RiviumStorageException.UploadException(e.message ?: "Unknown error")
        }

        if (!response.isSuccessful) {
            throw failure(response, "Upload failed: ${response.code}") { message, code ->
                RiviumStorageException.UploadException(message, code, response.code)
            }
        }

        return try {
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
        val bucket = bucketSegment(bucketId)
        val params = mutableListOf<String>()
        options.prefix?.let { params.add("prefix=${URLEncoder.encode(it, "UTF-8")}") }
        options.limit?.let { params.add("limit=$it") }
        options.cursor?.let { params.add("cursor=${URLEncoder.encode(it, "UTF-8")}") }

        val query = if (params.isNotEmpty()) "?${params.joinToString("&")}" else ""

        return request("GET", "/api/v1/buckets/$bucket/files$query")
    }

    /**
     * Get file by ID
     */
    suspend fun getFile(fileId: String): StorageFile {
        return request("GET", "/api/v1/files/${fileIdSegment(fileId)}")
    }

    /**
     * Get file by path in bucket
     */
    suspend fun getFileByPath(bucketId: String, path: String): StorageFile {
        return request("GET", "/api/v1/buckets/${bucketSegment(bucketId)}/files/${encodePath(path)}")
    }

    /**
     * Download file content
     */
    suspend fun download(fileId: String): ByteArray {
        val url = "${config.baseUrl}/api/v1/files/${fileIdSegment(fileId)}/download"
        return downloadUrl(url)
    }

    private suspend fun downloadUrl(url: String): ByteArray {
        val request = Request.Builder().url(url).get().build()

        val response = try {
            sendWithUserToken(request)
        } catch (e: RiviumStorageException) {
            throw e
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw RiviumStorageException.DownloadException(e.message ?: "Unknown error")
        }

        if (!response.isSuccessful) {
            throw failure(response, "Download failed: ${response.code}") { message, code ->
                RiviumStorageException.DownloadException(message, code, response.code)
            }
        }

        return response.body
    }

    /**
     * Delete a file by ID
     */
    suspend fun delete(fileId: String) {
        request<Map<String, Any>>("DELETE", "/api/v1/files/${fileIdSegment(fileId)}")
    }

    /**
     * Delete a file by path in bucket
     */
    suspend fun deleteByPath(bucketId: String, path: String) {
        request<Map<String, Any>>("DELETE", "/api/v1/buckets/${bucketSegment(bucketId)}/files/${encodePath(path)}")
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
    // Links
    // ==========================================

    /**
     * The public link of a file in a PUBLIC bucket:
     * `https://storage.rivium.co/public/<bucketId>/<path>`. Anyone can open it
     * without a key or token (an image loader, a browser, a share sheet). One
     * request to `GET /api/v1/files/:id/url`; a file in a private bucket is
     * refused with `403` (it has no public link: use [download], or a signed
     * link from your server).
     *
     * With [transforms] the link is a resized / converted image, still with no
     * headers: `getPublicUrl(id, ImageTransforms(width = 600, format = "webp"))`
     * gives `.../public/<bucketId>/<path>?w=600&f=webp`, for Coil's `AsyncImage`,
     * Glide or any image loader. The options are checked first
     * (`invalid_transform`, 400, before any request) and written in a fixed
     * order, so the same options always give the same URL: the CDN and the
     * server cache it as one image (replacing the file updates it). One image
     * keeps at most 50 different variants (`too_many_variants`, 429): use a few
     * fixed sizes. Plans without image transforms (Free) answer
     * `plan_feature_locked` (403). To build the URL from a public link you
     * already have, without a request, use [RiviumStorage.publicImageUrl].
     */
    suspend fun getPublicUrl(fileId: String, transforms: ImageTransforms? = null): String {
        // invalid_transform before any request.
        val query = transformQuery(transforms)
        // Public links need no user (the server answers with the key alone), so this never waits.
        val answer: PublicUrlResponse =
            request("GET", "/api/v1/files/${fileIdSegment(fileId)}/url", needsUserToken = false)
        return if (query.isEmpty()) answer.url else publicImageUrl(answer.url, transforms)
    }

    /**
     * The API address `GET /api/v1/files/:id/url`. Called with the `x-api-key`
     * header it answers `{ "url": ... }`, the public link above; the address
     * itself is not a link to the file. Use [getPublicUrl], which makes that
     * request for you.
     */
    fun getUrl(fileId: String): String {
        return "${config.baseUrl}/api/v1/files/${fileIdSegment(fileId)}/url"
    }

    /**
     * The API address of a transformed image:
     * `/api/v1/transform/:id?w=&h=&fit=&f=&q=&blur=&sharpen=&rotate=`.
     *
     * Options are checked first (see [ImageTransforms.toQueryParams]); a value
     * outside the allowed ranges throws `invalid_transform` (400). Like every
     * `/api/v1` address it needs the `x-api-key` header (and the user token
     * where your rules ask for one), so load it with an HTTP client that sends
     * them, not as a plain image URL.
     *
     * @param fileId The file ID
     * @param transforms Image transformation options
     * @return The transform URL
     */
    fun getTransformUrl(fileId: String, transforms: ImageTransforms? = null): String {
        val id = fileIdSegment(fileId)
        return "${config.baseUrl}/api/v1/transform/$id${transformQuery(transforms)}"
    }

    /**
     * The API address of a file's bytes, `/api/v1/files/:id/download`. It
     * needs the `x-api-key` header (and the user token where your rules ask
     * for one); [download] sends them for you.
     */
    fun getDownloadUrl(fileId: String): String {
        return "${config.baseUrl}/api/v1/files/${fileIdSegment(fileId)}/download"
    }

    // ==========================================
    // Helpers
    // ==========================================

    /** A bucket id or name as one URL segment: `bucket_required` when blank, `invalid_path` for `.` / `..`. */
    private fun bucketSegment(value: String): String {
        if (isBlank(value)) throw refuse(StorageErrorCode.BUCKET_REQUIRED, "A bucket id or name is required")
        return encodeSegment(value)
    }

    /** A file id as one URL segment: `file_id_required` when blank, `invalid_path` for `.` / `..`. */
    private fun fileIdSegment(value: String): String {
        if (isBlank(value)) throw refuse(StorageErrorCode.FILE_ID_REQUIRED, "A file id is required")
        return encodeSegment(value)
    }

    /**
     * One URL path segment (a file id, bucket id or name), percent-encoded as RFC 3986 asks: a space is %20
     * (URLEncoder writes `+`, which is form encoding — in a path the server reads it as a plus sign); `/`, `?`, `#`
     * and `%` are escaped too. An empty, `.` or `..` value (also percent-encoded, like `%2e%2e`) is refused with
     * `invalid_path` before any request, so a stored value can never make the SDK call a different endpoint.
     */
    private fun encodeSegment(segment: String): String {
        if (segment.isEmpty() || isDotSegment(segment)) throw refuse(StorageErrorCode.INVALID_PATH, DOT_ID_MESSAGE)
        return encodeRaw(segment)
    }

    private fun encodeRaw(segment: String): String = URLEncoder.encode(segment, "UTF-8").replace("+", "%20")

    /**
     * A file path in a URL: leading, trailing and repeated `/` are dropped (as the server stores paths) and each
     * segment is encoded. A path with no name left is refused with `path_required`, one with a `.` or `..` segment
     * (also percent-encoded) with `invalid_path`.
     */
    private fun encodePath(path: String): String {
        if (isBlank(path)) throw refuse(StorageErrorCode.PATH_REQUIRED, "A file path is required")
        val segments = path.split('/').filter { it.isNotEmpty() }
        if (segments.any { isDotSegment(it) }) throw refuse(StorageErrorCode.INVALID_PATH, DOT_PATH_MESSAGE)
        return segments.joinToString("/") { encodeRaw(it) }
    }

    /**
     * The path of a new file, normalised like the server stores it (leading, trailing and repeated `/` dropped).
     * Refused before any request, with the server's messages: no name left (`path_required`); `?`, `#`, `%`,
     * text-direction characters or a `.` / `..` segment (`invalid_path`).
     */
    private fun checkUploadPath(path: String): String {
        if (isBlank(path)) throw refuse(StorageErrorCode.PATH_REQUIRED, "A file path is required")
        if (path.any { it == '?' || it == '#' || it == '%' }) {
            throw refuse(StorageErrorCode.INVALID_PATH, "Path can't contain '?', '#' or '%'")
        }
        if (path.any { isBidiMark(it) }) {
            throw refuse(StorageErrorCode.INVALID_PATH, "Path can't contain text-direction characters")
        }
        val segments = path.split('/').filter { it.isNotEmpty() }
        if (segments.any { isDotSegment(it) }) throw refuse(StorageErrorCode.INVALID_PATH, DOT_PATH_MESSAGE)
        return segments.joinToString("/")
    }

    private fun refuse(code: String, message: String) = RiviumStorageException.HttpException(400, message, code)
}

/** The answer of `GET /api/v1/files/:id/url`. */
internal data class PublicUrlResponse(val url: String)
