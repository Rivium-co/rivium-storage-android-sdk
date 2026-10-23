package co.rivium.storage.example

import android.graphics.BitmapFactory
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import co.rivium.storage.ImageTransforms
import co.rivium.storage.ListFilesOptions
import co.rivium.storage.RiviumStorage
import co.rivium.storage.RiviumStorageException
import co.rivium.storage.StorageFile
import co.rivium.storage.UploadOptions
import co.rivium.storage.UploadProgress
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/**
 * "Run all checks": the core flow against the two test buckets, one pass/fail line per step, then deletes
 * every file it created. Use it to check an SDK build (debug or release) on a phone in a few seconds.
 */
class SelfTest(private val vm: StorageViewModel) {

    enum class Status { PENDING, RUNNING, PASS, FAIL, SKIP, WARN }

    class Step(val name: String) {
        var status by mutableStateOf(Status.PENDING)
        var detail by mutableStateOf("")
        var millis by mutableStateOf(0L)
    }

    val steps = mutableStateListOf<Step>()
    var isRunning by mutableStateOf(false)
        private set
    var summary by mutableStateOf("")
        private set

    private class Skip(message: String) : Exception(message)

    /** A step that ran but found something to change (the bucket setup): WARN, not FAIL. */
    private class Warn(message: String) : Exception(message)

    /** The "Bucket setup" result. null (that step failed) = the dependent steps run as they are. */
    private var setup: BucketSetup? = null

    fun start(scope: CoroutineScope) {
        if (isRunning) return
        isRunning = true
        steps.clear()
        summary = ""
        scope.launch {
            try {
                runAll(vm.storage)
            } finally {
                isRunning = false
                val pass = steps.count { it.status == Status.PASS }
                val fail = steps.count { it.status == Status.FAIL }
                val skip = steps.count { it.status == Status.SKIP }
                val warn = steps.count { it.status == Status.WARN }
                summary = "$pass passed, $fail failed, $skip skipped" + (if (warn > 0) ", $warn warning (bucket setup)" else "") +
                    " (SDK ${RiviumStorage.SDK_NAME}/${RiviumStorage.SDK_VERSION}, ${BuildConfig.BUILD_TYPE} build)"
            }
        }
    }

    /** [needs]: the bucket setting the step needs (from "Bucket setup"); a string = SKIP with that hint. */
    private suspend fun step(name: String, needs: ((BucketSetup) -> String?)? = null, block: suspend () -> String) {
        val s = Step(name)
        steps.add(s)
        s.status = Status.RUNNING
        val t0 = System.currentTimeMillis()
        try {
            val unmet = setup?.let { b -> needs?.invoke(b) }
            if (unmet != null) throw Skip(unmet)
            s.detail = block()
            s.status = Status.PASS
        } catch (e: CancellationException) {
            throw e
        } catch (e: Skip) {
            s.detail = e.message.orEmpty()
            s.status = Status.SKIP
        } catch (e: Warn) {
            s.detail = e.message.orEmpty()
            s.status = Status.WARN
        } catch (e: Throwable) {
            s.detail = describeError(e).joinToString("\n")
            s.status = Status.FAIL
        }
        s.millis = System.currentTimeMillis() - t0
    }

    private suspend fun expectFailure(expected: List<String>, block: suspend () -> Any?): String {
        try {
            val v = block()
            throw AssertionError("Expected ${expected.joinToString(" or ")}, but it worked: $v")
        } catch (e: CancellationException) {
            throw e
        } catch (e: AssertionError) {
            throw e
        } catch (e: Throwable) {
            if (!e.matches(expected)) throw AssertionError("Expected ${expected.joinToString(" or ")}, got: ${describeError(e).joinToString(" | ")}")
            val code = (e as? RiviumStorageException)?.code
            val legacy = expected.any { it.startsWith("code:") && e.legacyMatch(it.removePrefix("code:")) }
            return "Refused as expected" + (code?.let { " (code $it)" } ?: "") + (if (legacy) " - $LEGACY_NOTE" else "") + ": ${e.message}"
        }
    }

    private fun check(condition: Boolean, message: () -> String) {
        if (!condition) throw AssertionError(message())
    }

    private suspend fun runAll(storage: RiviumStorage) {
        val cfg = ExampleConfig
        val created = mutableListOf<StorageFile>()
        val run = "selftest-" + System.currentTimeMillis()
        val privPrefix = "users/${cfg.demoUserId}/$run/"
        val pubPrefix = "users/${cfg.demoUserId}/$run/"
        val png = withContext(Dispatchers.Default) { SampleFiles.png(label = "Self-test") }
        var privateFile: StorageFile? = null
        var publicFile: StorageFile? = null
        var unicodeFile: StorageFile? = null

        step("Configuration") {
            check(cfg.hasApiKey) { "RIVIUM_API_KEY is still the placeholder (rivium.properties)" }
            "key ${cfg.maskedKey}, buckets ${cfg.privateBucket} / ${cfg.publicBucket}, token server ${cfg.tokenServerUrl.ifEmpty { "not set" }}"
        }
        // First check against the service: are the test buckets set up the way the checks below expect?
        setup = null
        step("Bucket setup") {
            val b = BucketSetup(BucketFacts.read(storage, cfg.publicBucket), BucketFacts.read(storage, cfg.privateBucket))
            setup = b
            val diffs = b.differences()
            val unknown = b.unknowns()
            if (diffs.isNotEmpty()) {
                throw Warn(
                    (listOf("${diffs.size} difference(s); the checks that need these settings are skipped:") +
                        diffs.map { "• $it" } + unknown.map { "• $it" } + BucketSetup.SETUP_ONE_LINER).joinToString("\n")
                )
            }
            (listOf("${b.pub.name}: public, every type the checks use; ${b.priv.name}: private, images only, max ${b.priv.maxFileSize} B; executables blocked") + unknown)
                .joinToString("\n")
        }
        step("Get buckets (by name and id)") {
            val priv = storage.getBucketByName(cfg.privateBucket)
            val byId = storage.getBucket(priv.id)
            check(byId.id == priv.id) { "getBucket returned another bucket" }
            val pub = storage.getBucketByName(cfg.publicBucket)
            check(pub.visibility == "public") { "${pub.name} is ${pub.visibility}, expected public" }
            "${priv.name}: ${priv.visibility}, types ${priv.allowedMimeTypes?.joinToString() ?: "any"}; ${pub.name}: public"
        }
        step("User token from the token server") {
            check(cfg.hasTokenServer) {
                "TOKEN_SERVER_URL not set: file requests need a signed-in user (they would wait), so the checks stop here"
            }
            vm.markSignedIn(true)
            storage.refreshUserToken()
            storage.listBuckets() // needs no user; makes the SDK ask the provider
            val token = storage.userTokens.value
            check(token != null) { "the provider gave no token: ${vm.providerStatus}" }
            storage.listFiles(cfg.privateBucket, ListFilesOptions(prefix = privPrefix, limit = 1))
            "token held: ${vm.tokenSummary()}"
        }
        if (!cfg.hasTokenServer || storage.userTokens.value == null) return
        step("Calls wait until sign-in") {
            try {
                vm.markSignedIn(false)
                storage.refreshUserToken() // the provider now returns null
                coroutineScope {
                    val call = async { storage.listFiles(cfg.privateBucket, ListFilesOptions(prefix = privPrefix, limit = 1)) }
                    withTimeout(5_000) { while (!storage.isAwaitingUserToken) delay(20) }
                    check(!call.isCompleted) { "listFiles finished while signed out" }
                    vm.markSignedIn(true)
                    storage.refreshUserToken() // the waiting call goes on by itself
                    val list = withTimeout(15_000) { call.await() }
                    check(!storage.isAwaitingUserToken) { "still awaiting a user token after sign-in" }
                    "signed out -> listFiles waited (isAwaitingUserToken) -> signed in -> it completed (${list.files.size} file)"
                }
            } finally {
                vm.markSignedIn(true)
            }
        }
        step("Upload image to private bucket (metadata)", { it.privateAllowsPng() }) {
            val f = storage.upload(cfg.privateBucket, "${privPrefix}photo.png", png,
                UploadOptions(contentType = "image/png", metadata = mapOf("selfTest" to true)))
            created += f; privateFile = f
            check(f.size == png.size.toLong()) { "size ${f.size} != ${png.size}" }
            check(f.mimeType == "image/png") { "mimeType ${f.mimeType}" }
            "${f.id} (${formatBytes(f.size.toLong())})"
        }
        step("Upload to public bucket", { it.publicAllows("image/png") }) {
            val f = storage.upload(cfg.publicBucket, "${pubPrefix}public.png", png, UploadOptions(contentType = "image/png"))
            created += f; publicFile = f
            f.id
        }
        step("Upload progress reaches 100%", { it.publicAllows("image/png") }) {
            // A real PNG of noise pixels (~2 MB; noise does not compress): random bytes would be
            // application/octet-stream, which buckets that allow only images refuse.
            val bytes = withContext(Dispatchers.Default) { SampleFiles.noisePng(850, 850) }
            val decoded = SampleFiles.decodedSize(bytes)
            check(decoded == 850 to 850) { "generated PNG does not decode as 850x850: $decoded" }
            check(bytes.size >= 2 * 1024 * 1024) { "generated PNG is only ${formatBytes(bytes.size.toLong())}" }
            val events = mutableListOf<UploadProgress>()
            var offMain = 0
            val f = try {
                // Its own folder, so "listFiles with prefix + paging" below still sees exactly 3 files.
                storage.upload(cfg.publicBucket, "users/${cfg.demoUserId}/$run-progress/progress-test.png", bytes,
                    UploadOptions(contentType = "image/png"),
                    onProgress = { p ->
                        if (android.os.Looper.myLooper() != android.os.Looper.getMainLooper()) offMain++
                        events += p
                    })
            } catch (e: RiviumStorageException) {
                if (e.isPngNotAllowed()) throw Skip(PNG_NOT_ALLOWED)
                throw e
            }
            created += f
            check(events.isNotEmpty()) { "no progress events" }
            check(offMain == 0) { "$offMain events off the main thread" }
            check(events.zipWithNext().all { (a, b) -> b.bytesSent > a.bytesSent }) { "progress went backwards" }
            check(events.all { it.totalBytes == events[0].totalBytes && it.bytesSent <= it.totalBytes }) { "bytesSent above totalBytes" }
            val last = events.last()
            check(last.bytesSent == last.totalBytes) { "last event ${last.bytesSent} of ${last.totalBytes}" }
            check(last.totalBytes >= bytes.size) { "totalBytes ${last.totalBytes} < file ${bytes.size}" }
            "${events.size} events, last ${formatBytes(last.bytesSent)} of ${formatBytes(last.totalBytes)} (100%)"
        }
        step("File types: .json / .csv / .md upload with no contentType", { it.publicAllows("application/json", "text/csv", "text/markdown") }) {
            // No contentType: the SDK picks the type from the extension (the Console's file types table),
            // and the server stores exactly that type. Own folder, so the paging check below still sees 3 files.
            val expected = linkedMapOf(
                "data.json" to "application/json",
                "table.csv" to "text/csv",
                "notes.md" to "text/markdown",
            )
            val stored = mutableListOf<String>()
            val refused = mutableListOf<String>()
            for ((name, type) in expected) {
                val bytes = when (name.substringAfterLast('.')) {
                    "json" -> """{"selfTest":true}"""
                    "csv" -> "name,count\nself-test,1\n"
                    else -> "# Self-test\n\nUploaded with no content type.\n"
                }.toByteArray()
                try {
                    val f = storage.upload(cfg.publicBucket, "users/${cfg.demoUserId}/$run-types/$name", bytes)
                    created += f
                    check(f.mimeType == type) { "$name stored as ${f.mimeType}, expected $type" }
                    stored += "$name = ${f.mimeType}"
                } catch (e: RiviumStorageException) {
                    if (!e.isTypeNotAllowedOrLegacy()) throw e
                    refused += "$name ($type)"
                }
            }
            if (refused.isNotEmpty()) {
                throw Skip(
                    "${cfg.publicBucket} doesn't allow ${refused.joinToString()} (type_not_allowed). Allow JSON, CSV and " +
                        "Markdown in the Console (Bucket › Settings › File types) to run this check." +
                        (if (stored.isEmpty()) "" else " Stored fine: ${stored.joinToString()}.")
                )
            }
            stored.joinToString()
        }
        step("Upload Unicode name رسید-۸۸۱۲.pdf", { it.publicAllows("application/pdf") }) {
            val f = storage.upload(cfg.publicBucket, "${pubPrefix}رسید-۸۸۱۲.pdf", SampleFiles.pdf(), UploadOptions(contentType = "application/pdf"))
            created += f; unicodeFile = f
            check(f.fileName == "رسید-۸۸۱۲.pdf") { "stored name: ${f.fileName}" }
            f.path
        }
        step("Upload name with spaces", { it.publicAllows("text/plain") }) {
            val f = storage.upload(cfg.publicBucket, "${pubPrefix}my notes 2026.txt", SampleFiles.text("self-test"), UploadOptions(contentType = "text/plain"))
            created += f
            check(f.fileName == "my notes 2026.txt") { "stored name: ${f.fileName}" }
            f.path
        }
        step("getFile(id)") {
            val f = privateFile ?: throw Skip("no private upload")
            val got = storage.getFile(f.id)
            check(got.path == f.path) { "path ${got.path}" }
            got.path
        }
        step("getFileByPath (Unicode)") {
            val f = unicodeFile ?: throw Skip("no Unicode upload")
            val got = storage.getFileByPath(cfg.publicBucket, f.path)
            check(got.id == f.id) { "found ${got.id}, expected ${f.id}" }
            "found ${got.fileName}"
        }
        step("getFileByPath (spaces)") {
            val f = created.firstOrNull { it.fileName == "my notes 2026.txt" } ?: throw Skip("no upload with spaces")
            val got = storage.getFileByPath(cfg.publicBucket, f.path)
            check(got.id == f.id) { "found ${got.id}, expected ${f.id}" }
            "found ${got.fileName}"
        }
        step("listFiles with prefix + paging") {
            if (publicFile == null || unicodeFile == null || created.none { it.fileName == "my notes 2026.txt" }) throw Skip("needs the 3 public uploads above")
            val first = storage.listFiles(cfg.publicBucket, ListFilesOptions(prefix = pubPrefix, limit = 2))
            check(first.files.size == 2) { "first page has ${first.files.size} files, expected 2" }
            val cursor = first.nextCursor ?: throw AssertionError("no nextCursor with 3 files and limit 2")
            val second = storage.listFiles(cfg.publicBucket, ListFilesOptions(prefix = pubPrefix, limit = 2, cursor = cursor))
            check(second.files.size == 1) { "second page has ${second.files.size} files, expected 1" }
            check((first.files + second.files).map { it.id }.toSet().size == 3) { "pages overlap" }
            "2 + 1 files, nextCursor worked"
        }
        step("download() matches upload") {
            val f = privateFile ?: throw Skip("no private upload")
            val bytes = storage.download(f.id)
            check(bytes.contentEquals(png)) { "downloaded ${bytes.size} bytes, uploaded ${png.size}" }
            "${bytes.size} bytes, identical"
        }
        step("Image transform (with headers)") {
            val f = privateFile ?: throw Skip("no private upload")
            val url = storage.getTransformUrl(f.id, ImageTransforms(width = 120, height = 80, fit = "cover", format = "png", quality = 80))
            val raw = withContext(Dispatchers.IO) { ExampleHttp.get(url, ExampleHttp.storageHeaders(storage.userTokens.value)) }
            check(raw.status == 200) {
                TransformRefusal.from(raw.status, raw.text).let { "${it.title}: ${it.message} (${it.code ?: "HTTP ${raw.status}"})" }
            }
            val bmp = BitmapFactory.decodeByteArray(raw.bytes, 0, raw.bytes.size)
            check(bmp != null && bmp.width == 120 && bmp.height == 80) { "got ${bmp?.width}x${bmp?.height}" }
            "120x80 png, ${formatBytes(raw.bytes.size.toLong())}"
        }
        step("Public link (public bucket, no headers)") {
            val f = publicFile ?: throw Skip("no public upload")
            val link = withContext(Dispatchers.IO) { ExampleHttp.fetchPublicLink(storage.getUrl(f.id), storage.userTokens.value) }
            val raw = withContext(Dispatchers.IO) { ExampleHttp.get(link) }
            check(raw.status == 200 && raw.bytes.contentEquals(png)) { "HTTP ${raw.status}, ${raw.bytes.size} bytes" }
            "link works without headers"
        }
        step("Public transform link loads without headers (and a second load is a cache hit)") {
            val f = publicFile ?: throw Skip("no public upload")
            val url = try {
                storage.getPublicUrl(f.id, ImageTransforms(width = 32, format = "webp"))
            } catch (e: RiviumStorageException) {
                if (e.statusCode == 403) throw Skip("${cfg.publicBucket} isn't public (getPublicUrl answered 403): set its Visibility to Public in the Console.")
                throw e
            }
            check(url.endsWith("?w=32&f=webp")) { "unexpected URL $url" }
            // No headers at all; the example's OkHttp client has no HTTP cache, so both loads reach the CDN / server.
            suspend fun load(): Pair<String?, String?> {
                val raw = ExampleHttp.getPublicImage(url)
                if (raw.status != 200) {
                    val r = TransformRefusal.from(raw.status, raw.text, raw.header("Retry-After"))
                    if (r.code == co.rivium.storage.StorageErrorCode.PLAN_FEATURE_LOCKED) {
                        throw Skip("${r.message} (403 plan_feature_locked): this check needs a plan with image transforms.")
                    }
                    throw AssertionError("${r.title}: ${r.message} (${r.code ?: "HTTP ${raw.status}"})")
                }
                check(raw.contentType?.startsWith("image/webp") == true) { "content type ${raw.contentType}" }
                val bmp = BitmapFactory.decodeByteArray(raw.bytes, 0, raw.bytes.size)
                check(bmp != null && bmp.width == 32) { "decoded ${bmp?.width}x${bmp?.height}" }
                return raw.header("X-Transform-Cache") to raw.header("CF-Cache-Status")
            }
            val (first, _) = load()
            check(first == "miss") { "first load: X-Transform-Cache $first, expected miss" }
            val (second, cdn) = load()
            check(second == "hit" || cdn == "HIT") { "second load: X-Transform-Cache $second${cdn?.let { ", CDN $it" } ?: ""}, expected hit" }
            "200 image/webp, miss then ${second ?: "CDN $cdn"}, no headers"
        }
        step("Public link refused for private file") {
            val f = privateFile ?: throw Skip("no private upload")
            expectFailure(listOf("403", "not in a public bucket")) {
                withContext(Dispatchers.IO) { ExampleHttp.fetchPublicLink(storage.getUrl(f.id), storage.userTokens.value) }
            }
        }
        step("Signed link (from token server)") {
            val f = privateFile ?: throw Skip("no private upload")
            if (!cfg.hasTokenServer) throw Skip("TOKEN_SERVER_URL not set")
            val url = withContext(Dispatchers.IO) { ExampleHttp.fetchSignedUrl(f.id, 120) }
            val raw = withContext(Dispatchers.IO) { ExampleHttp.get(url) }
            check(raw.status == 200 && raw.bytes.contentEquals(png)) { "HTTP ${raw.status}, ${raw.bytes.size} bytes" }
            "signed link works without headers"
        }
        step("Error: file not found") {
            expectFailure(listOf("status:404")) { storage.getFile("00000000-0000-4000-8000-000000000000") }
        }
        step("Error: type not allowed", { it.privateRefuses("text/plain") }) {
            expectFailure(listOf("code:type_not_allowed")) {
                storage.upload(cfg.privateBucket, "${privPrefix}note.txt", SampleFiles.text("x"), UploadOptions(contentType = "text/plain")).also { created += it }
            }
        }
        step("Error: HTML declared as image/png", { it.privateRefuses("text/html") }) {
            expectFailure(listOf("code:type_not_allowed")) {
                storage.upload(cfg.privateBucket, "${privPrefix}fake.png", SampleFiles.html(), UploadOptions(contentType = "image/png")).also { created += it }
            }
        }
        step("Error: executable_blocked (.bat)", { it.blocksExecutables(public = false) }) {
            expectFailure(listOf("code:executable_blocked")) {
                storage.upload(cfg.privateBucket, "${privPrefix}run-me.bat", SampleFiles.png(16, 16), UploadOptions(contentType = "image/png")).also { created += it }
            }
        }
        step("Error: access denied by rules") {
            if (!cfg.hasTokenServer) throw Skip("needs a user token (TOKEN_SERVER_URL)")
            expectFailure(listOf("status:403")) {
                storage.upload(cfg.privateBucket, "users/someone-else/$run.png", SampleFiles.png(16, 16), UploadOptions(contentType = "image/png")).also { created += it }
            }
        }
        step("Error: token_invalid") {
            val bad = RiviumStorage.builder(cfg.apiKey).userToken("eyJhbGciOiJFUzI1NiJ9.eyJzdWIiOiJ4In0.c2ln").build()
            expectFailure(listOf("code:token_invalid")) { bad.listFiles(cfg.privateBucket, ListFilesOptions(limit = 1)) }
        }
        step("deleteMany cleans up") {
            val ids = created.map { it.id }.distinct()
            val result = storage.deleteMany(ids)
            check(result.deleted == ids.size) { "deleted ${result.deleted} of ${ids.size}" }
            created.clear()
            "deleted ${result.deleted} file(s)"
        }
        step("Deleted file is gone (404)") {
            val f = privateFile ?: throw Skip("no private upload")
            expectFailure(listOf("status:404")) { storage.getFile(f.id) }
        }

        // Anything left after a failure: delete one by one, and say so.
        if (created.isNotEmpty()) {
            step("Leftover cleanup") {
                val failures = mutableListOf<String>()
                for (f in created) {
                    try {
                        storage.delete(f.id)
                    } catch (e: RiviumStorageException) {
                        failures += "${f.path}: ${e.message}"
                    }
                }
                check(failures.isEmpty()) { failures.joinToString("\n") }
                "deleted ${created.size} leftover file(s)"
            }
        }
    }
}
