package co.rivium.storage.example

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * The example's own HTTP calls - things the Storage SDK does not do for you:
 *
 * 1. Your token server (holds the server secret, which never ships in an app):
 *    - POST /token {userId, expiresIn} -> {token, expiresAt}: a Rivium user token for the signed-in user.
 *    - POST /signed-url   {fileId, expiresIn} -> {url}: a time-limited link to a private file.
 * 2. The public-link endpoint (GET /api/v1/files/:id/url with x-api-key -> {url}). The SDK's getUrl(id)
 *    only returns that endpoint's address; the address itself needs the key header and answers JSON.
 * 3. Any Storage URL that needs headers (transform, download) when you want the raw response.
 *
 * All calls here block: run them on Dispatchers.IO.
 */
object ExampleHttp {
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val json = "application/json".toMediaType()

    class HttpFailure(val status: Int, val body: String) : IOException("HTTP $status: ${body.take(300)}")

    data class TokenResult(val token: String, val expiresAt: String?)

    /** Asks your token server for a Rivium user token. Throws when the server is missing or says no. */
    fun fetchUserToken(userId: String, expiresInSeconds: Int? = null): TokenResult {
        check(ExampleConfig.hasTokenServer) { "TOKEN_SERVER_URL is not set in rivium.properties" }
        val body = JSONObject().put("userId", userId)
        if (expiresInSeconds != null) body.put("expiresIn", expiresInSeconds)
        val res = postJson("${ExampleConfig.tokenServerUrl}/token", body)
        val token = res.optString("token")
        if (token.isNullOrEmpty()) throw IOException("Token server answered without a \"token\" field: $res")
        return TokenResult(token, res.optString("expiresAt").ifEmpty { null })
    }

    /** Asks your token server for a signed download link (it calls getSignedUrl with the server secret). */
    fun fetchSignedUrl(fileId: String, expiresInSeconds: Int): String {
        check(ExampleConfig.hasTokenServer) { "TOKEN_SERVER_URL is not set in rivium.properties" }
        val res = postJson(
            "${ExampleConfig.tokenServerUrl}/signed-url",
            JSONObject().put("fileId", fileId).put("expiresIn", expiresInSeconds)
        )
        val url = res.optString("url").ifEmpty { res.optString("signedUrl") }
        if (url.isEmpty()) throw IOException("Token server answered without a \"url\" field: $res")
        return url
    }

    /**
     * The public link of a file in a PUBLIC bucket: GET <getUrl(id)> with x-api-key answers {"url": "..."}.
     * A private bucket answers 403 "File is not in a public bucket".
     */
    fun fetchPublicLink(endpoint: String, userToken: String?): String {
        val res = get(endpoint, storageHeaders(userToken))
        if (res.status !in 200..299) throw HttpFailure(res.status, res.text)
        return JSONObject(res.text).getString("url")
    }

    class Raw(
        val status: Int,
        val contentType: String?,
        val bytes: ByteArray,
        private val headers: okhttp3.Headers = okhttp3.Headers.headersOf(),
    ) {
        val text: String get() = String(bytes, Charsets.UTF_8)

        /** A response header (case-insensitive), or null. */
        fun header(name: String): String? = headers[name]
    }

    /** GET with the given headers; returns the response whatever its status. */
    fun get(url: String, headers: Map<String, String> = emptyMap()): Raw {
        val request = Request.Builder().url(url).get().apply { headers.forEach { (k, v) -> header(k, v) } }.build()
        client.newCall(request).execute().use { r ->
            return Raw(r.code, r.header("Content-Type"), r.body?.bytes() ?: ByteArray(0), r.headers)
        }
    }

    /**
     * GET a public image URL with NO headers (no key, no user token), as Coil / Glide do. This client has no HTTP
     * cache, so every call reaches the CDN / server. A 503 transform_busy is retried once after its Retry-After
     * seconds ([onBusy] is told first); other refusals come back as they are (check [Raw.status]).
     */
    suspend fun getPublicImage(url: String, onBusy: (Int) -> Unit = {}): Raw {
        val first = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { get(url) }
        if (first.status != 503) return first
        val refusal = TransformRefusal.from(first.status, first.text, first.header("Retry-After"))
        if (refusal.code != co.rivium.storage.StorageErrorCode.TRANSFORM_BUSY) return first
        val seconds = refusal.retryAfter ?: 2
        onBusy(seconds)
        kotlinx.coroutines.delay(seconds * 1000L)
        return kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { get(url) }
    }

    /** The headers a Storage API address needs: the public key, plus the user token when there is one. */
    fun storageHeaders(userToken: String?): Map<String, String> = buildMap {
        put("x-api-key", ExampleConfig.apiKey)
        if (userToken != null) put("x-user-token", userToken)
    }

    private fun postJson(url: String, body: JSONObject): JSONObject {
        val request = Request.Builder().url(url).post(body.toString().toRequestBody(json)).build()
        client.newCall(request).execute().use { r ->
            val text = r.body?.string().orEmpty()
            if (!r.isSuccessful) throw HttpFailure(r.code, text)
            return JSONObject(text)
        }
    }
}
