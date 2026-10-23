package co.rivium.storage.example

import co.rivium.storage.RiviumStorage
import co.rivium.storage.RiviumStorageException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * "Bucket setup": compares the two test buckets with what the checks expect (what `npm run setup-buckets` in
 * sdk/nodejs/Example creates) and says, for every difference, the exact Console setting to change. Every check
 * that depends on a setting asks this result first and SKIPs with the matching hint instead of failing because
 * of the bucket's configuration.
 *
 * The same rules (and texts) are in every example (Node: sdk/nodejs/Example/src/bucket-setup.ts, Web, React
 * Native, Flutter, iOS): keep them in step.
 *
 * Expected:
 *   public bucket   visibility public; allows image/png, application/pdf, text/plain, application/json, text/csv,
 *                   text/markdown (no allowed types = any type, which is what setup creates); executables blocked
 *   private bucket  visibility private; images only (JPEG / PNG / GIF / WebP: no PDF, no text, no HTML);
 *                   max file size 1 MB; executables blocked
 *   both            rules "users' own folders" (not readable with the app key, so not compared here)
 *
 * A field the API does not send (blockExecutables before the 10-22 Storage API) is "unknown", never "wrong".
 */
class BucketFacts(
    val name: String,
    /** False when the bucket does not exist. */
    val found: Boolean,
    val visibility: String? = null,
    val allowedMimeTypes: List<String>? = null,
    val maxFileSize: Long? = null,
    /** null = the API did not say (older Storage API). */
    val blockExecutables: Boolean? = null,
) {
    companion object {
        /**
         * Reads one bucket with the SDK (the public key may read buckets); 404 = it doesn't exist.
         * `blockExecutables` is not in the SDK's Bucket yet: it is read from the same API answer with a plain GET
         * (null when that fails or an older API doesn't send it).
         */
        suspend fun read(storage: RiviumStorage, name: String): BucketFacts {
            val b = try {
                storage.getBucketByName(name)
            } catch (e: RiviumStorageException) {
                if (e.statusCode == 404) return BucketFacts(name, found = false)
                throw e
            }
            val block: Boolean? = try {
                val u = storage.getUrl("x")
                val base = u.substring(0, u.indexOf("/api/v1/"))
                val raw = withContext(Dispatchers.IO) {
                    ExampleHttp.get("$base/api/v1/buckets/${b.id}", ExampleHttp.storageHeaders(storage.userTokens.value))
                }
                val json = if (raw.status == 200) JSONObject(raw.text) else null
                if (json != null && json.has("blockExecutables") && !json.isNull("blockExecutables")) json.getBoolean("blockExecutables") else null
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null // unknown, never wrong
            }
            return BucketFacts(name, true, b.visibility, b.allowedMimeTypes, b.maxFileSize?.toLong(), block)
        }
    }
}

class BucketSetup(val pub: BucketFacts, val priv: BucketFacts) {

    companion object {
        private const val MB = 1024L * 1024L
        /** What setup gives the private bucket (PRIVATE_BUCKET_MAX_SIZE default). */
        const val EXPECTED_PRIVATE_MAX = MB
        /** The "too big" check uploads max + 1 bytes: it runs only when the limit is known and at most this. */
        const val TOO_BIG_CHECK_LIMIT = 10 * MB

        /** Every type the checks put in the public bucket (MIME type to Console label). */
        val PUBLIC_TYPES = linkedMapOf(
            "image/png" to "PNG",
            "application/pdf" to "PDF",
            "text/plain" to "TXT",
            "application/json" to "JSON",
            "text/csv" to "CSV",
            "text/markdown" to "Markdown",
        )
        /** Types the private bucket must refuse (the "not allowed" / "content mismatch" checks send them). */
        val PRIVATE_REFUSES = listOf("application/pdf", "text/plain", "text/html")

        const val SETUP_ONE_LINER = "Or run `npm run setup-buckets` in sdk/nodejs/Example."

        /** Same matching as the Storage API: no list = any type; type/ * wildcards; "*" and "* / *" match all. */
        fun allowsType(types: List<String>?, mime: String): Boolean {
            if (types.isNullOrEmpty()) return true
            val m = mime.lowercase()
            return types.any { raw ->
                val p = raw.trim().lowercase()
                when {
                    p == "*" || p == "*/*" -> true
                    p.endsWith("/*") -> m.startsWith(p.dropLast(1))
                    else -> p == m
                }
            }
        }

        private fun mb(bytes: Long) = if (bytes % MB == 0L) "${bytes / MB} MB" else "$bytes bytes"
    }

    private fun console(b: BucketFacts) = "Console › Storage › Buckets › ${b.name} › Settings"

    /** Differences from the expected setup, each with the Console fix. Empty = all good. */
    fun differences(): List<String> {
        val out = mutableListOf<String>()
        for ((b, which) in listOf(pub to "Public", priv to "Private")) {
            if (!b.found) out += "$which bucket ${b.name} doesn't exist → create it: Console › Storage › Buckets › New bucket (${which.lowercase()}), with the settings below."
        }
        if (pub.found) {
            if (pub.visibility != "public") out += "Public bucket ${pub.name} is ${pub.visibility} → ${console(pub)} › Visibility: Public"
            val missing = PUBLIC_TYPES.filterKeys { !allowsType(pub.allowedMimeTypes, it) }
            if (missing.isNotEmpty()) {
                out += "Public bucket ${pub.name} doesn't allow ${missing.keys.joinToString()} → ${console(pub)} › Allowed file types: " +
                    "none selected (= any type), or tick ${missing.values.joinToString()} (Images · Documents · Text · Data)"
            }
            if (pub.blockExecutables == false) out += "Public bucket ${pub.name} doesn't block executables → ${console(pub)} › Block executable files: on"
        }
        if (priv.found) {
            if (priv.visibility != "private") out += "Private bucket ${priv.name} is ${priv.visibility} → ${console(priv)} › Visibility: Private"
            if (!allowsType(priv.allowedMimeTypes, "image/png")) {
                out += "Private bucket ${priv.name} doesn't allow image/png → ${console(priv)} › Allowed file types › Images: tick JPEG, PNG, GIF, WebP"
            }
            val extra = PRIVATE_REFUSES.filter { allowsType(priv.allowedMimeTypes, it) }
            if (extra.isNotEmpty()) {
                val what = if (priv.allowedMimeTypes.isNullOrEmpty()) "any file type" else extra.joinToString()
                out += "Private bucket ${priv.name} allows $what → ${console(priv)} › Allowed file types: images only (JPEG, PNG, GIF, WebP; nothing else)"
            }
            val max = priv.maxFileSize
            if (max != null && max != EXPECTED_PRIVATE_MAX) {
                out += "Private bucket ${priv.name} max file size is ${mb(max)}, the checks expect ${mb(EXPECTED_PRIVATE_MAX)} → ${console(priv)} › Upload limits › Max file size: 1 MB"
            }
            if (priv.blockExecutables == false) out += "Private bucket ${priv.name} doesn't block executables → ${console(priv)} › Block executable files: on"
        }
        return out
    }

    /** Fields the API did not send. */
    fun unknowns(): List<String> = listOf(pub, priv).filter { it.found && it.blockExecutables == null }
        .map { "${it.name}: \"Block executable files\" unknown (older Storage API sends no blockExecutables)" }

    // ---- Preconditions: null = the check can run; otherwise the SKIP text. ----

    /** The private bucket refuses [mime] ("Error: type not allowed", "content mismatch"). */
    fun privateRefuses(mime: String): String? {
        if (!priv.found) return "${priv.name} doesn't exist. $SETUP_ONE_LINER"
        if (!allowsType(priv.allowedMimeTypes, mime)) return null
        return "${priv.name} allows $mime, so nothing is refused. Set ${console(priv)} › Allowed file types: images only to run this check. $SETUP_ONE_LINER"
    }

    /** The private bucket has a small, known max size ("too big"). */
    fun privateHasMaxSize(): String? {
        val max = priv.maxFileSize
        if (priv.found && max != null && max > 0 && max <= TOO_BIG_CHECK_LIMIT) return null
        return "${priv.name} has no max file size of ${mb(TOO_BIG_CHECK_LIMIT)} or less${max?.let { " (it is ${mb(it)})" } ?: ""}. " +
            "Set ${console(priv)} › Upload limits › Max file size: 1 MB to run this check. $SETUP_ONE_LINER"
    }

    /** The bucket blocks executables ("executable_blocked"). Unknown = run it. */
    fun blocksExecutables(public: Boolean): String? {
        val b = if (public) pub else priv
        if (b.found && b.blockExecutables != false) return null
        if (!b.found) return "${b.name} doesn't exist. $SETUP_ONE_LINER"
        return "${b.name} doesn't block executable files. Set ${console(b)} › Block executable files: on to run this check. $SETUP_ONE_LINER"
    }

    /** The public bucket allows every one of [mimes] ("Upload progress", "file types", the PDF / text uploads). */
    fun publicAllows(vararg mimes: String): String? {
        if (!pub.found) return "${pub.name} doesn't exist. $SETUP_ONE_LINER"
        val missing = mimes.filter { !allowsType(pub.allowedMimeTypes, it) }
        if (missing.isEmpty()) return null
        val labels = missing.map { PUBLIC_TYPES[it] ?: it }
        return "${pub.name} doesn't allow ${missing.joinToString()}. Set ${console(pub)} › Allowed file types: tick ${labels.joinToString()} " +
            "(or none selected = any type) to run this check. $SETUP_ONE_LINER"
    }

    /** The private bucket allows image/png (the private uploads). */
    fun privateAllowsPng(): String? {
        if (!priv.found) return "${priv.name} doesn't exist. $SETUP_ONE_LINER"
        if (allowsType(priv.allowedMimeTypes, "image/png")) return null
        return "${priv.name} doesn't allow image/png. Set ${console(priv)} › Allowed file types › Images: tick PNG to run this check. $SETUP_ONE_LINER"
    }
}

/**
 * An OLDER Storage API (before the error codes) refuses correctly but sends no `code`. Where a check expects one of
 * these codes, a 400 without a code whose message is the known text also counts, with a note.
 */
private val LEGACY_MESSAGES = mapOf(
    "type_not_allowed" to Regex("is not allowed in this bucket", RegexOption.IGNORE_CASE),
    "file_too_large" to Regex("exceeds maximum", RegexOption.IGNORE_CASE),
    "executable_blocked" to Regex("Executable files are blocked in this bucket", RegexOption.IGNORE_CASE),
)

const val LEGACY_NOTE = "server sent no code (older Storage API)"

/** True when this has no code but is status 400 with the known message for [expectedCode]. */
fun Throwable.legacyMatch(expectedCode: String): Boolean {
    val e = this as? RiviumStorageException ?: return false
    val re = LEGACY_MESSAGES[expectedCode] ?: return false
    return e.code.isNullOrEmpty() && e.statusCode == 400 && re.containsMatchIn(e.message.orEmpty())
}
