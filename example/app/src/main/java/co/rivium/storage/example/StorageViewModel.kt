package co.rivium.storage.example

import android.app.Application
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Base64
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import co.rivium.storage.Bucket
import co.rivium.storage.ImageTransforms
import co.rivium.storage.ListFilesOptions
import co.rivium.storage.RiviumStorage
import co.rivium.storage.StorageFile
import co.rivium.storage.UploadOptions
import co.rivium.storage.UploadProgress
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Holds the RiviumStorage instances and runs every demo. Each demo has a key; its result (success, the exact
 * error, or an expected error) lands in [results] and the card with that key shows it.
 */
class StorageViewModel(app: Application) : AndroidViewModel(app) {

    val cfg = ExampleConfig
    val userFolder: String get() = "users/${cfg.demoUserId}/"

    // ------------------------------------------------------------------------------------------------
    // Request log: a custom OkHttpClient given to the SDK with .client(...). Shows each Storage request,
    // its status and the X-Rivium-SDK header. Tokens are never logged, only whether one was sent.
    // ------------------------------------------------------------------------------------------------
    val requestLog = mutableStateListOf<String>()
    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.US)

    private val loggingClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .addInterceptor { chain ->
            val request = chain.request()
            val started = System.currentTimeMillis()
            val line = "${request.method} ${request.url.encodedPath}"
            val sent = buildString {
                append(" | X-Rivium-SDK: ").append(request.header("X-Rivium-SDK") ?: "missing")
                append(" | token: ").append(if (request.header("x-user-token") != null) "yes" else "no")
            }
            try {
                val response = chain.proceed(request)
                addLog("${timeFormat.format(Date())} $line -> ${response.code} (${System.currentTimeMillis() - started} ms)$sent")
                response
            } catch (e: Exception) {
                addLog("${timeFormat.format(Date())} $line -> ${e.javaClass.simpleName}: ${e.message}$sent")
                throw e
            }
        }
        .build()

    private fun addLog(line: String) {
        viewModelScope.launch(Dispatchers.Main) {
            requestLog.add(0, line)
            while (requestLog.size > 60) requestLog.removeAt(requestLog.lastIndex)
        }
    }

    // ------------------------------------------------------------------------------------------------
    // The main instance: public key + a token provider that asks YOUR token server for a Rivium user token.
    // ------------------------------------------------------------------------------------------------

    /** The app's own sign-in state. The provider returns null while signed out. */
    @Volatile
    private var signedInFlag = true
    var signedIn by mutableStateOf(true)
        private set

    /** What the token provider did last (so a failing token server is visible, not silent). */
    var providerStatus by mutableStateOf("Not asked yet (the SDK asks before the first request).")
        private set

    val storage: RiviumStorage = RiviumStorage.builder(cfg.apiKey)
        .timeout(30)
        .client(loggingClient)
        .userTokenProvider(::provideToken)
        .build()

    /**
     * True while file requests wait for a user token (signed out). The SDK calls onAwaitingUserToken once when the
     * wait starts; isAwaitingUserToken says when it is over. The Setup screen shows a banner from this.
     */
    var awaitingUserToken by mutableStateOf(false)
        private set

    private fun watchAwaitingUserToken() {
        storage.onAwaitingUserToken = {
            // Background thread: hop to the main thread for Compose state.
            viewModelScope.launch(Dispatchers.Main) { awaitingUserToken = true }
        }
        viewModelScope.launch {
            while (true) {
                awaitingUserToken = storage.isAwaitingUserToken
                kotlinx.coroutines.delay(300)
            }
        }
    }

    /** Called by the SDK on a background thread. Return null when no one is signed in. */
    private fun provideToken(): String? {
        if (!signedInFlag) {
            noteProvider("Returned null: signed out.")
            return null
        }
        if (!cfg.hasTokenServer) {
            noteProvider("Returned null: TOKEN_SERVER_URL is not set, so requests carry no user token.")
            return null
        }
        return try {
            val result = ExampleHttp.fetchUserToken(cfg.demoUserId)
            noteProvider("Token for \"${cfg.demoUserId}\" at ${timeFormat.format(Date())}, expires ${result.expiresAt ?: "?"}")
            result.token
        } catch (e: Exception) {
            noteProvider("FAILED at ${timeFormat.format(Date())}: ${e.message}. The SDK sends the request without a new token.")
            throw e // The SDK keeps any token it had and lets the server decide.
        }
    }

    private fun noteProvider(text: String) {
        viewModelScope.launch(Dispatchers.Main) { providerStatus = text }
    }

    // ------------------------------------------------------------------------------------------------
    // Session state shared by the screens
    // ------------------------------------------------------------------------------------------------
    val results = mutableStateMapOf<String, ActionResult>()
    val running = mutableStateMapOf<String, Boolean>()

    /** Latest upload progress per demo key; the card with that key draws a progress bar. */
    val uploadProgress = mutableStateMapOf<String, UploadProgress>()

    /** An onProgress callback for the card [key]. The SDK calls it on the main thread. */
    private fun progressFor(key: String): (UploadProgress) -> Unit {
        uploadProgress.remove(key)
        return { uploadProgress[key] = it }
    }

    /** Every file this session uploaded and has not deleted yet (newest first). */
    val uploaded = mutableStateListOf<StorageFile>()
    var selectedFile by mutableStateOf<StorageFile?>(null)
    var privateBucketInfo by mutableStateOf<Bucket?>(null)
        private set

    val privateImage: StorageFile? get() = uploaded.firstOrNull { it.bucketId == privateBucketInfo?.id && it.mimeType.startsWith("image/") }
    val anyImage: StorageFile? get() = uploaded.firstOrNull { it.mimeType.startsWith("image/") }

    private fun remember(file: StorageFile) {
        uploaded.removeAll { it.id == file.id }
        uploaded.add(0, file)
        selectedFile = file
    }

    private fun forget(ids: Collection<String>) {
        uploaded.removeAll { it.id in ids }
        if (selectedFile?.id in ids) selectedFile = uploaded.firstOrNull()
    }

    init {
        watchAwaitingUserToken()
        // Startup check (shown on the home screen): reads the private test bucket, which also tells the
        // other screens its id.
        run("startup") {
            val bucket = storage.getBucketByName(cfg.privateBucket)
            privateBucketInfo = bucket
            ActionResult.ok("Connected. Private test bucket \"${bucket.name}\" found (${bucket.visibility}).")
        }
    }

    private fun stamp(): String = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())

    // ------------------------------------------------------------------------------------------------
    // Runners: no silent catch - every outcome is shown on the card.
    // ------------------------------------------------------------------------------------------------

    fun run(key: String, block: suspend () -> ActionResult) {
        if (running[key] == true) return
        running[key] = true
        results.remove(key)
        viewModelScope.launch {
            val result = try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                ActionResult.error(e)
            } finally {
                running[key] = false
            }
            results[key] = result
        }
    }

    /** For demos whose point is an error: shows the error and whether it is the expected one. */
    fun expectError(key: String, expected: List<String>, block: suspend () -> String) = run(key) {
        try {
            val value = block()
            ActionResult.warn(
                "No error, but this demo expects: ${expected.joinToString(" or ")}.",
                "Result: $value",
                "Check the test bucket setup in README.md (rules, allowed types, max size, block executables)."
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            if (e.matches(expected)) {
                ActionResult(ActionResult.Kind.EXPECTED_ERROR, listOf("Refused as expected.") + describeError(e))
            } else {
                ActionResult(
                    ActionResult.Kind.ERROR,
                    listOf("Failed, but not with the expected error (${expected.joinToString(" or ")}).") + describeError(e)
                )
            }
        }
    }

    // ------------------------------------------------------------------------------------------------
    // Identity: token provider, setUserToken, refresh, sign in / out
    // ------------------------------------------------------------------------------------------------

    /** For "Run all checks": the app's sign-in state, which the token provider reads. */
    fun markSignedIn(value: Boolean) {
        signedInFlag = value
        signedIn = value
    }

    fun signIn() = run("signIn") {
        signedInFlag = true
        signedIn = true
        storage.refreshUserToken() // the provider is asked again before the next request
        val list = storage.listFiles(cfg.privateBucket, ListFilesOptions(prefix = userFolder, limit = 5))
        ActionResult.ok(
            "Signed in as \"${cfg.demoUserId}\". refreshUserToken() made the SDK ask the provider again.",
            "Proof: listFiles(\"$userFolder\") worked with the token, ${list.files.size} file(s).",
            "Token held now: ${tokenSummary()}"
        )
    }

    fun signOut() = run("signOut") {
        signedInFlag = false
        signedIn = false
        storage.refreshUserToken()
        ActionResult.ok(
            "Signed out: refreshUserToken() made the SDK ask the provider, which now returns null.",
            "File requests (upload, list, download, delete...) now WAIT for a user token instead of failing; " +
                "isAwaitingUserToken turns true and onAwaitingUserToken fires once. Sign in and they go on by themselves.",
            "Bucket reads (listBuckets, getBucket) need no user and still answer."
        )
    }

    /** Sign out, start a listFiles (it waits), then sign in from the card: the waiting call completes on its own. */
    fun waitForSignIn() = run("waitForSignIn") {
        signedInFlag = false
        signedIn = false
        storage.refreshUserToken()
        val started = System.currentTimeMillis()
        val list = storage.listFiles(cfg.privateBucket, ListFilesOptions(prefix = userFolder, limit = 5))
        ActionResult.ok(
            "listFiles waited ${(System.currentTimeMillis() - started) / 1000.0} s for sign-in, then ran by itself: ${list.files.size} file(s).",
            "Nothing was sent while signed out (see the request log)."
        )
    }

    fun setTokenByHand() = run("setUserToken") {
        val result = withContext(Dispatchers.IO) { ExampleHttp.fetchUserToken(cfg.demoUserId) }
        storage.setUserToken(result.token) // same as storage.userTokens.set(token)
        signedInFlag = true
        signedIn = true
        val list = storage.listFiles(cfg.privateBucket, ListFilesOptions(prefix = userFolder, limit = 1))
        ActionResult.ok(
            "Fetched a token yourself and called setUserToken(token).",
            "Use this when your app already has the token (for example Rivium Auth gave it at sign-in).",
            "It is used right away: listFiles worked (${list.files.size} file).",
            "Token held now: ${tokenSummary()}",
            "The provider stays installed and is asked again when this token nears expiry."
        )
    }

    fun replaceProvider() = run("setUserTokenProvider") {
        storage.setUserTokenProvider(::provideToken) // same as storage.userTokens.provider = ...
        val bucket = storage.getBucketByName(cfg.privateBucket)
        ActionResult.ok(
            "setUserTokenProvider(provider) installed the provider again; the SDK dropped the held token and asked it before this request.",
            "Use it when the provider is only known after start-up (pass null to remove it).",
            "Request after it: getBucketByName -> ${bucket.name}",
            "Token held now: ${tokenSummary()}"
        )
    }

    fun tokenSummary(): String {
        val token = storage.userTokens.value ?: return "none"
        val exp = try {
            val payload = String(Base64.decode(token.split(".")[1], Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP))
            val json = JSONObject(payload)
            "sub=${json.optString("sub")}, expires ${timeFormat.format(Date(json.optLong("exp") * 1000))}"
        } catch (e: Exception) {
            "unreadable payload (${e.message})"
        }
        return "${token.take(12)}… ($exp)"
    }

    // ------------------------------------------------------------------------------------------------
    // Buckets
    // ------------------------------------------------------------------------------------------------

    fun listBuckets() = run("listBuckets") {
        val buckets = storage.listBuckets()
        ActionResult.ok(listOf("${buckets.size} bucket(s):") + buckets.map { describeBucket(it) })
    }

    fun getBucketByName(name: String) = run("getBucketByName:$name") {
        val bucket = storage.getBucketByName(name)
        if (name == cfg.privateBucket) privateBucketInfo = bucket
        ActionResult.ok(describeBucket(bucket).split("\n"))
    }

    fun getBucketById() = run("getBucket") {
        val id = privateBucketInfo?.id ?: storage.getBucketByName(cfg.privateBucket).also { privateBucketInfo = it }.id
        val bucket = storage.getBucket(id)
        ActionResult.ok(listOf("getBucket(\"$id\"):") + describeBucket(bucket).split("\n"))
    }

    private fun describeBucket(b: Bucket): String = listOf(
        "${b.name} [${b.id}]",
        "  visibility: ${b.visibility}",
        "  allowedMimeTypes: ${b.allowedMimeTypes?.joinToString() ?: "any"}",
        "  maxFileSize: ${b.maxFileSize?.let { formatBytes(it.toLong()) } ?: "-"}",
        "  blockExecutables: ${b.blockExecutables ?: "-"}",
    ).joinToString("\n")

    private suspend fun ensurePrivateBucket(): Bucket =
        privateBucketInfo ?: storage.getBucketByName(cfg.privateBucket).also { privateBucketInfo = it }

    // ------------------------------------------------------------------------------------------------
    // Uploads
    // ------------------------------------------------------------------------------------------------

    fun uploadSampleToPrivate() = run("uploadSample") {
        ensurePrivateBucket()
        val bytes = withContext(Dispatchers.Default) { SampleFiles.png(label = "Private") }
        val file = storage.upload(
            bucketId = cfg.privateBucket, // a bucket name or id
            path = "${userFolder}example/photo-${stamp()}.png",
            data = bytes,
            options = UploadOptions(
                contentType = "image/png",
                metadata = mapOf("source" to "android-example", "kind" to "sample")
            ),
            onProgress = progressFor("uploadSample")
        )
        remember(file)
        ActionResult.ok(listOf("Uploaded ${formatBytes(bytes.size.toLong())} to the private bucket.") + file.describe())
    }

    fun uploadSampleToPublic() = run("uploadPublic") {
        val bytes = withContext(Dispatchers.Default) { SampleFiles.png(label = "Public") }
        val file = storage.upload(
            bucketId = cfg.publicBucket,
            path = "${userFolder}images/sample-${stamp()}.png",
            data = bytes,
            options = UploadOptions(contentType = "image/png", metadata = mapOf("source" to "android-example")),
            onProgress = progressFor("uploadPublic")
        )
        remember(file)
        ActionResult.ok(listOf("Uploaded to the public bucket.") + file.describe())
    }

    fun overwriteSelected() = run("overwrite") {
        val target = selectedFile ?: return@run ActionResult.warn("Upload a file first.")
        if (!target.mimeType.startsWith("image/")) return@run ActionResult.warn("Select an image file (the private bucket takes images only).")
        val bytes = withContext(Dispatchers.Default) { SampleFiles.png(label = "Version 2") }
        val file = storage.upload(
            target.bucketId, target.path, bytes,
            UploadOptions(contentType = "image/png", metadata = mapOf("version" to 2))
        )
        remember(file)
        ActionResult.ok(
            listOf(
                "Uploaded again to the same path: the file is replaced in place.",
                "Same id: ${file.id == target.id} (${file.id})",
                "Metadata is merged: ${file.metadata}"
            )
        )
    }

    fun uploadUnicodeName() = run("uploadUnicode") {
        val file = storage.upload(
            cfg.publicBucket,
            "${userFolder}receipts/${stamp()}/رسید-۸۸۱۲.pdf",
            SampleFiles.pdf(),
            UploadOptions(contentType = "application/pdf", metadata = mapOf("note" to "Unicode file name"))
        )
        remember(file)
        ActionResult.ok(listOf("Unicode names are stored as typed (NFC).") + file.describe())
    }

    fun uploadNameWithSpaces() = run("uploadSpaces") {
        val file = storage.upload(
            cfg.publicBucket,
            "${userFolder}notes/${stamp()}/my notes 2026.txt",
            SampleFiles.text("Hello from the Rivium Storage Android example.\n"),
            UploadOptions(contentType = "text/plain")
        )
        remember(file)
        ActionResult.ok(listOf("Spaces are fine in names.") + file.describe())
    }

    fun uploadWithoutContentType() = run("uploadAutoType") {
        val file = storage.upload(
            cfg.publicBucket,
            "${userFolder}data/${stamp()}/settings.json",
            SampleFiles.text("{\"theme\":\"dark\",\"lang\":\"fa\"}"),
            // No contentType: the SDK guesses from the extension, and the server checks the real content.
        )
        remember(file)
        ActionResult.ok(listOf("No contentType given: stored as ${file.mimeType}.") + file.describe())
    }

    /** A generated ~5 MB PNG (noise pixels do not compress), big enough that the progress bar visibly moves. */
    fun uploadLargeFile() = run("uploadLarge") {
        val bytes = withContext(Dispatchers.Default) { SampleFiles.noisePng(1300, 1300) }
        val events = mutableListOf<UploadProgress>()
        val show = progressFor("uploadLarge")
        val t0 = System.currentTimeMillis()
        val file = try {
            storage.upload(
                bucketId = cfg.publicBucket,
                path = "${userFolder}large/${stamp()}/progress-test.png",
                data = bytes,
                options = UploadOptions(contentType = "image/png"),
                onProgress = { p -> events += p; show(p) }
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            if (e.isPngNotAllowed()) return@run ActionResult.warn("Skipped: $PNG_NOT_ALLOWED", "Server said: ${e.message}")
            throw e
        }
        val seconds = (System.currentTimeMillis() - t0) / 1000.0
        remember(file)
        val last = events.lastOrNull()
        ActionResult.ok(
            listOf(
                "Uploaded a ${formatBytes(bytes.size.toLong())} PNG (1300×1300 noise pixels) in ${"%.1f".format(Locale.US, seconds)} s.",
                "${events.size} progress events; last: ${last?.bytesSent} of ${last?.totalBytes} bytes " +
                    "(the request body: the file plus the form fields)."
            ) + file.describe()
        )
    }

    /** A file from the system picker, photo picker or camera. */
    fun uploadFromUri(key: String, uri: Uri, toPrivate: Boolean, fallbackName: String) = run(key) {
        val resolver = getApplication<Application>().contentResolver
        val (bytes, name, type) = withContext(Dispatchers.IO) {
            var displayName: String? = null
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) displayName = c.getString(0)
            }
            val data = resolver.openInputStream(uri)?.use { it.readBytes() }
                ?: throw IllegalStateException("Could not read $uri")
            Triple(data, displayName ?: fallbackName, resolver.getType(uri))
        }
        if (toPrivate) ensurePrivateBucket()
        val bucket = if (toPrivate) cfg.privateBucket else cfg.publicBucket
        val path = if (toPrivate) "${userFolder}photos/${stamp()}-$name" else "${userFolder}files/${stamp()}/$name"
        val file = storage.upload(
            bucket, path, bytes,
            UploadOptions(contentType = type, metadata = mapOf("originalName" to name)),
            onProgress = progressFor(key)
        )
        remember(file)
        ActionResult.ok(listOf("Uploaded \"$name\" (${type ?: "type from extension"}, ${formatBytes(bytes.size.toLong())}) to $bucket.") + file.describe())
    }

    // ------------------------------------------------------------------------------------------------
    // Files: list, get, download, delete
    // ------------------------------------------------------------------------------------------------

    var listBucket by mutableStateOf(cfg.privateBucket)
    var listPrefix by mutableStateOf("users/${cfg.demoUserId}/")
    var listLimit by mutableStateOf("5")
    var nextCursor by mutableStateOf<String?>(null)
        private set
    val listed = mutableStateListOf<StorageFile>()

    fun listFiles(nextPage: Boolean) = run("listFiles") {
        val cursor = if (nextPage) nextCursor else null
        if (nextPage && cursor == null) return@run ActionResult.warn("No next page.")
        val result = storage.listFiles(
            listBucket,
            ListFilesOptions(prefix = listPrefix.ifBlank { null }, limit = listLimit.toIntOrNull(), cursor = cursor)
        )
        if (!nextPage) listed.clear()
        listed.addAll(result.files)
        nextCursor = result.nextCursor
        ActionResult.ok(
            listOf(
                "${result.files.size} file(s) on this page, ${listed.size} shown in all.",
                "nextCursor: ${result.nextCursor ?: "none (last page)"}"
            ) + result.files.map { "• ${it.path}  (${formatBytes(it.size.toLong())}, ${it.mimeType})" }
        )
    }

    fun getFile() = run("getFile") {
        val f = selectedFile ?: return@run ActionResult.warn("Upload or select a file first.")
        ActionResult.ok(listOf("getFile(id):") + storage.getFile(f.id).describe())
    }

    fun getFileByPath() = run("getFileByPath") {
        val f = selectedFile ?: return@run ActionResult.warn("Upload or select a file first.")
        val found = storage.getFileByPath(f.bucketId, f.path)
        ActionResult.ok(listOf("getFileByPath(bucket, \"${f.path}\"):", "same file: ${found.id == f.id}") + found.describe())
    }

    fun download() = run("download") {
        val f = selectedFile ?: return@run ActionResult.warn("Upload or select a file first.")
        val bytes = storage.download(f.id)
        val bitmap = if (SampleFiles.isImage(bytes)) withContext(Dispatchers.Default) {
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        } else null
        val preview = when {
            bitmap != null -> "Image ${bitmap.width}x${bitmap.height}, shown below from the downloaded bytes."
            f.mimeType.startsWith("text/") || f.mimeType.contains("json") -> "Text: " + String(bytes).take(200)
            else -> "First bytes: " + bytes.take(16).joinToString(" ") { "%02x".format(it) }
        }
        ActionResult(ActionResult.Kind.SUCCESS, listOf("download(id): ${formatBytes(bytes.size.toLong())} (expected ${f.size} B)", preview), bitmap = bitmap)
    }

    fun deleteSelected() = run("delete") {
        val f = selectedFile ?: return@run ActionResult.warn("Upload or select a file first.")
        storage.delete(f.id)
        forget(listOf(f.id))
        listed.removeAll { it.id == f.id }
        ActionResult.ok("delete(\"${f.id}\") done: ${f.path}")
    }

    fun deleteSelectedByPath() = run("deleteByPath") {
        val f = selectedFile ?: return@run ActionResult.warn("Upload or select a file first.")
        storage.deleteByPath(f.bucketId, f.path)
        forget(listOf(f.id))
        listed.removeAll { it.id == f.id }
        ActionResult.ok("deleteByPath(bucket, \"${f.path}\") done.")
    }

    fun deleteAllUploaded() = run("deleteMany") {
        val ids = uploaded.map { it.id }
        if (ids.isEmpty()) return@run ActionResult.warn("Nothing uploaded in this session.")
        val result = storage.deleteMany(ids)
        forget(ids)
        listed.removeAll { it.id in ids }
        ActionResult.ok("deleteMany(${ids.size} ids): deleted = ${result.deleted}")
    }

    // ------------------------------------------------------------------------------------------------
    // Links and showing images
    // ------------------------------------------------------------------------------------------------

    fun publicLink() = run("publicLink") {
        val f = uploaded.firstOrNull { it.bucketId != privateBucketInfo?.id && it.mimeType.startsWith("image/") }
            ?: return@run ActionResult.warn("Upload an image to the public bucket first (Upload screen).")
        val endpoint = storage.getUrl(f.id)
        val link = withContext(Dispatchers.IO) { ExampleHttp.fetchPublicLink(endpoint, storage.userTokens.value) }
        ActionResult(
            ActionResult.Kind.SUCCESS,
            listOf(
                "getUrl(id) is the endpoint: $endpoint",
                "GET it with x-api-key -> {\"url\"}: $link",
                "That link needs no headers: the image below loads it with a plain URL."
            ),
            imageUrl = link, openUrl = link
        )
    }

    fun publicLinkOfPrivate() = expectError("publicLinkPrivate", listOf("403", "not in a public bucket")) {
        val f = privateImage ?: error("Upload an image to the private bucket first.")
        withContext(Dispatchers.IO) { ExampleHttp.fetchPublicLink(storage.getUrl(f.id), storage.userTokens.value) }
    }

    fun showPrivateByDownload() = run("privateDownload") {
        val f = privateImage ?: return@run ActionResult.warn("Upload an image to the private bucket first.")
        val bytes = storage.download(f.id)
        val bitmap = withContext(Dispatchers.Default) { BitmapFactory.decodeByteArray(bytes, 0, bytes.size) }
        ActionResult(ActionResult.Kind.SUCCESS, listOf("download(id) -> ${formatBytes(bytes.size.toLong())}, decoded in the app."), bitmap = bitmap)
    }

    fun signedLink(expiresIn: Int) = run("signedUrl") {
        val f = privateImage ?: return@run ActionResult.warn("Upload an image to the private bucket first.")
        if (!cfg.hasTokenServer) return@run ActionResult.warn("Set TOKEN_SERVER_URL: signed links are made by your server with the server secret.")
        val url = withContext(Dispatchers.IO) { ExampleHttp.fetchSignedUrl(f.id, expiresIn) }
        val check = withContext(Dispatchers.IO) { ExampleHttp.get(url) } // no headers at all
        val until = timeFormat.format(Date(System.currentTimeMillis() + expiresIn * 1000L))
        ActionResult(
            if (check.status == 200) ActionResult.Kind.SUCCESS else ActionResult.Kind.ERROR,
            listOf(
                "Your server made a signed link valid for $expiresIn s (until about $until).",
                "Fetched with NO headers: HTTP ${check.status}, ${formatBytes(check.bytes.size.toLong())}, ${check.contentType}",
                "Same bytes as the file: ${check.bytes.size.toLong() == f.size}",
                url
            ),
            imageUrl = url, openUrl = url
        )
    }

    fun wrongWay() = run("wrongWay") {
        val f = anyImage ?: return@run ActionResult.warn("Upload an image first.")
        val endpoint = storage.getUrl(f.id)
        val raw = withContext(Dispatchers.IO) { ExampleHttp.get(endpoint) } // what an image widget does: no headers
        ActionResult(
            ActionResult.Kind.EXPECTED_ERROR,
            listOf(
                "An image widget given getUrl(id) sends no headers. The server answers:",
                "HTTP ${raw.status}: ${raw.text.take(200)}",
                "Even with the key it answers JSON ({\"url\": …}), not image bytes. So the image below stays broken.",
                "Do this instead: public bucket -> the public link; private -> download() or a signed link."
            ),
            imageUrl = endpoint
        )
    }

    fun downloadUrlWithHeaders() = run("downloadUrl") {
        val f = selectedFile ?: return@run ActionResult.warn("Upload or select a file first.")
        val url = storage.getDownloadUrl(f.id)
        val noHeaders = withContext(Dispatchers.IO) { ExampleHttp.get(url) }
        val withHeaders = withContext(Dispatchers.IO) { ExampleHttp.get(url, ExampleHttp.storageHeaders(storage.userTokens.value)) }
        ActionResult.ok(
            "getDownloadUrl(id): $url",
            "Without headers: HTTP ${noHeaders.status} (${noHeaders.text.take(120)})",
            "With x-api-key (+ x-user-token): HTTP ${withHeaders.status}, ${formatBytes(withHeaders.bytes.size.toLong())}",
            "It is the address download() uses; use download() unless your HTTP client must fetch it."
        )
    }

    // ------------------------------------------------------------------------------------------------
    // Transforms
    // ------------------------------------------------------------------------------------------------

    var tWidth by mutableStateOf("200")
    var tHeight by mutableStateOf("200")
    var tFit by mutableStateOf<String?>("cover")
    var tFormat by mutableStateOf<String?>("webp")
    var tQuality by mutableStateOf(80f)
    var tBlur by mutableStateOf(0f)
    var tSharpen by mutableStateOf(0f)
    var tRotate by mutableStateOf(0)

    fun currentTransforms() = ImageTransforms(
        width = tWidth.toIntOrNull(),
        height = tHeight.toIntOrNull(),
        fit = tFit,
        format = tFormat,
        quality = tQuality.toInt(),
        blur = tBlur.toInt().takeIf { it > 0 },
        sharpen = tSharpen.toInt().takeIf { it > 0 },
        rotate = tRotate.takeIf { it > 0 },
    )

    fun applyTransform() = run("transform") {
        val f = anyImage ?: return@run ActionResult.warn("Upload an image first (Upload screen).")
        val transforms = currentTransforms()
        val url = storage.getTransformUrl(f.id, transforms)
        // The transform address needs x-api-key (+ x-user-token), so fetch it with headers.
        val raw = withContext(Dispatchers.IO) { ExampleHttp.get(url, ExampleHttp.storageHeaders(storage.userTokens.value)) }
        if (raw.status != 200) {
            // Read code + message from the JSON body; show a title and a hint, never the raw body or the URL.
            return@run TransformRefusal.from(raw.status, raw.text).toResult()
        }
        val bitmap = withContext(Dispatchers.Default) { BitmapFactory.decodeByteArray(raw.bytes, 0, raw.bytes.size) }
        ActionResult(
            ActionResult.Kind.SUCCESS,
            listOf(
                "Source: ${f.path} (${formatBytes(f.size.toLong())})",
                "Query: ${transforms.toQueryParams()}",
                "Result: ${raw.contentType}, ${formatBytes(raw.bytes.size.toLong())}" +
                    (bitmap?.let { ", ${it.width}x${it.height}" } ?: ", not decodable here (AVIF needs Android 12+)"),
                url
            ),
            bitmap = bitmap
        )
    }

    /** The newest image this session uploaded to the PUBLIC bucket (any bucket but the private one). */
    val publicImage: StorageFile?
        get() = uploaded.firstOrNull { it.bucketId != privateBucketInfo?.id && it.mimeType.startsWith("image/") }

    /**
     * Public bucket: header-free link. getPublicUrl(publicFileId, options) gives the public link with the
     * options; the card shows it in Coil's AsyncImage with NO headers. It is fetched once without headers
     * first, so a refusal shows its title, message and hint (an image loader only says "failed").
     */
    fun applyPublicTransform() = run("transformPublic") {
        val f = publicImage ?: storage.upload(
            cfg.publicBucket, "${userFolder}public-transform-${stamp()}.png", SampleFiles.png(),
            UploadOptions(contentType = "image/png"),
        ).also { remember(it) }
        val transforms = currentTransforms()
        // invalid_transform before any request; 403 when the bucket is private.
        val url = storage.getPublicUrl(f.id, transforms)
        var busy: String? = null
        val raw = ExampleHttp.getPublicImage(url) { seconds ->
            busy = "Busy, retried after $seconds s (Retry-After)"
            results["transformPublic"] = ActionResult.warn("Busy, retrying… (in $seconds s, as Retry-After says)")
        }
        if (raw.status != 200) {
            return@run TransformRefusal.from(raw.status, raw.text, raw.header("Retry-After")).toResult()
        }
        ActionResult(
            ActionResult.Kind.SUCCESS,
            listOfNotNull(
                "Source: ${f.path} (public bucket)",
                "Result: ${raw.contentType}, ${formatBytes(raw.bytes.size.toLong())}, X-Transform-Cache: ${raw.header("X-Transform-Cache") ?: "-"}",
                busy,
                "Shown below by Coil with NO headers.",
                url,
            ),
            imageUrl = url,
        )
    }

    // ------------------------------------------------------------------------------------------------
    // Errors
    // ------------------------------------------------------------------------------------------------

    fun errorNotFound() = expectError("errNotFound", listOf("status:404")) {
        storage.getFile("00000000-0000-4000-8000-000000000000").path
    }

    fun errorDownloadMissing() = expectError("errDownloadMissing", listOf("status:404")) {
        "${storage.download("00000000-0000-4000-8000-000000000000").size} bytes"
    }

    fun errorTypeNotAllowed() = expectError("errType", listOf("code:type_not_allowed")) {
        storage.upload(cfg.privateBucket, "${userFolder}errors/notes-${stamp()}.txt", SampleFiles.text("plain text"),
            UploadOptions(contentType = "text/plain")).also { remember(it) }.path
    }

    fun errorContentMismatch() = expectError("errMismatch", listOf("code:type_not_allowed")) {
        storage.upload(cfg.privateBucket, "${userFolder}errors/fake-${stamp()}.png", SampleFiles.html(),
            UploadOptions(contentType = "image/png")).also { remember(it) }.path
    }

    fun errorTooBig() = run("errTooBig") {
        val bucket = ensurePrivateBucket()
        val max = bucket.maxFileSize
            ?: return@run ActionResult.warn("The private bucket has no max file size. Set 1 MB in the Console.")
        if (max > 20 * 1024 * 1024) {
            return@run ActionResult.warn("Max file size is ${formatBytes(max.toLong())}; this demo uploads max + 1 byte, so set 1 MB on the test bucket.")
        }
        try {
            val f = storage.upload(cfg.privateBucket, "${userFolder}errors/big-${stamp()}.png", SampleFiles.oversized(max + 1),
                UploadOptions(contentType = "image/png"))
            remember(f)
            ActionResult.warn("Uploaded ${formatBytes(max + 1L)} although the bucket max is ${formatBytes(max.toLong())}.")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            val ok = e.matches(listOf("code:file_too_large"))
            ActionResult(
                if (ok) ActionResult.Kind.EXPECTED_ERROR else ActionResult.Kind.ERROR,
                listOf("Uploaded ${max + 1} bytes; bucket max is $max.") + describeError(e)
            )
        }
    }

    fun errorRules() = expectError("errRules", listOf("status:403")) {
        storage.upload(cfg.privateBucket, "users/someone-else/intrusion-${stamp()}.png", SampleFiles.png(64, 64),
            UploadOptions(contentType = "image/png")).also { remember(it) }.path
    }

    fun errorExecutable() = expectError("errExe", listOf("code:executable_blocked")) {
        // Real PNG bytes (allowed type) with a .bat name: refused by name when the bucket blocks executables.
        storage.upload(cfg.privateBucket, "${userFolder}errors/run-me-${stamp()}.bat", SampleFiles.png(32, 32),
            UploadOptions(contentType = "image/png")).also { remember(it) }.path
    }

    fun errorExecutableExe() = expectError("errExe2", listOf("code:executable_blocked", "code:type_not_allowed")) {
        storage.upload(cfg.publicBucket, "${userFolder}errors/setup-${stamp()}.exe", SampleFiles.fakeExe(),
            UploadOptions(contentType = "application/octet-stream")).also { remember(it) }.path
    }

    /**
     * No token and no provider: file requests wait for a user. With userTokenWaitTimeout the wait ends with
     * token_required (UserTokenRequiredException, 401) instead. Nothing is sent.
     */
    fun errorTokenRequired() = expectError("errTokenRequired", listOf("code:token_required")) {
        val bare = RiviumStorage.builder(cfg.apiKey).client(loggingClient)
            .userTokenWaitTimeout(3, TimeUnit.SECONDS)
            .build()
        "${bare.listFiles(cfg.privateBucket, ListFilesOptions(limit = 1)).files.size} file(s)"
    }

    fun errorTokenInvalid() = expectError("errTokenInvalid", listOf("code:token_invalid")) {
        // No provider, so the SDK cannot fetch a better one and throws UserTokenRejectedException.
        val bad = RiviumStorage.builder(cfg.apiKey).client(loggingClient)
            .userToken("eyJhbGciOiJFUzI1NiIsInR5cCI6IkpXVCJ9.eyJzdWIiOiJtYWxsb3J5In0.bm90LWEtc2lnbmF0dXJl")
            .build()
        "${bad.listFiles(cfg.privateBucket, ListFilesOptions(limit = 1)).files.size} file(s)"
    }

    /** A real token that lives 60 s (the shortest the server mints). */
    var shortToken by mutableStateOf<String?>(null)
        private set
    var shortTokenExpiresAt by mutableStateOf(0L)
        private set

    fun fetchShortToken() = run("shortToken") {
        val result = withContext(Dispatchers.IO) { ExampleHttp.fetchUserToken(cfg.demoUserId, expiresInSeconds = 60) }
        shortToken = result.token
        shortTokenExpiresAt = System.currentTimeMillis() + 60_000
        ActionResult.ok("Got a 60-second token (expires ${result.expiresAt}). Wait until the countdown ends, then send it.")
    }

    fun errorTokenExpired() = expectError("errTokenExpired", listOf("code:token_expired")) {
        val token = shortToken ?: error("Get the 60-second token first.")
        val withOld = RiviumStorage.builder(cfg.apiKey).client(loggingClient).userToken(token).build()
        "${withOld.listFiles(cfg.privateBucket, ListFilesOptions(limit = 1)).files.size} file(s) (token still valid?)"
    }

    fun errorNetwork() = expectError("errNetwork", listOf("NetworkException")) {
        storage.getBucketByName(cfg.privateBucket).name
    }

    override fun onCleared() {
        loggingClient.dispatcher.executorService.shutdown()
    }
}
