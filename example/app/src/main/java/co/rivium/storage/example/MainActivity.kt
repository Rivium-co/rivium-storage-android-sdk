package co.rivium.storage.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import co.rivium.storage.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.text.DecimalFormat

/**
 * RiviumStorage Android SDK - Complete Example
 *
 * This example demonstrates ALL capabilities of the RiviumStorage SDK:
 * - Bucket operations (list, get by ID, get by name)
 * - File operations (upload, list, get, download, delete)
 * - URL generation (public URL, transform URL, download URL)
 * - Image transformations (resize, format, quality, effects)
 * - Policy testing (no rules, private, public read, user folders, images only)
 * - Error handling
 *
 * Only the API key and bucket name are configured manually.
 * All IDs (bucket ID, file IDs, paths) are captured from API responses
 * and reused by subsequent operations — nothing is hardcoded.
 * Run the buttons top-to-bottom for the best experience.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    RiviumStorageExampleApp()
                }
            }
        }
    }
}

// ============================================================
// Configuration — only these two values need to be set
// ============================================================

// Replace with your actual API key from the Rivium Console
private const val API_KEY = "YOUR_API_KEY"
private const val BUCKET_NAME = "my-bucket"

// User ID for bucket policy enforcement (sent as x-user-id header)
private const val USER_ID = "demo-user-123"

@Composable
fun RiviumStorageExampleApp() {
    val storage = remember { RiviumStorage(apiKey = API_KEY, userId = USER_ID, timeout = 30) }
    val scope = rememberCoroutineScope()
    val logs = remember { mutableStateListOf<LogEntry>() }

    // Shared state — captured from API responses, used by subsequent operations
    var lastBucketId by remember { mutableStateOf<String?>(null) }
    var lastFileId by remember { mutableStateOf<String?>(null) }
    var lastFilePath by remember { mutableStateOf<String?>(null) }
    var lastImageFileId by remember { mutableStateOf<String?>(null) }
    val uploadedFileIds = remember { mutableStateListOf<String>() }

    fun log(message: String, isError: Boolean = false) {
        logs.add(LogEntry(message, isError))
    }

    // Helper: try an upload and report result
    suspend fun tryUpload(
        storage: RiviumStorage,
        bucketId: String,
        path: String,
        data: ByteArray,
        contentType: String? = null
    ): String {
        return try {
            val file = storage.upload(
                bucketId = bucketId,
                path = path,
                data = data,
                options = UploadOptions(contentType = contentType)
            )
            // Clean up: delete the uploaded file
            try { storage.delete(file.id) } catch (_: Exception) {}
            "ALLOWED"
        } catch (e: RiviumStorageException) {
            if (e is RiviumStorageException.HttpException && e.statusCode == 403) {
                "DENIED (${e.message})"
            } else {
                "ERROR (${e.message})"
            }
        }
    }

    // Helper: try listing files and report result
    suspend fun tryList(storage: RiviumStorage, bucketId: String): String {
        return try {
            storage.listFiles(bucketId)
            "ALLOWED"
        } catch (e: RiviumStorageException) {
            if (e is RiviumStorageException.HttpException && e.statusCode == 403) {
                "DENIED"
            } else {
                "ERROR (${e.message})"
            }
        }
    }

    // Helper: ensure bucket is available
    suspend fun ensureBucket(
        storage: RiviumStorage,
        currentBucketId: String?,
        log: (String, Boolean) -> Unit,
        setBucketId: (String) -> Unit
    ): String? {
        if (currentBucketId != null) return currentBucketId
        log("No bucket. Running 'List All Buckets' first...", false)
        try {
            val buckets = storage.listBuckets()
            if (buckets.isNotEmpty()) {
                val id = buckets.first().id
                setBucketId(id)
                log("Found ${buckets.size} bucket(s). Using: $id", false)
                return id
            }
        } catch (e: RiviumStorageException) {
            log("Error: ${e.message}", true)
        }
        log("No bucket available. Create one in the dashboard first.", true)
        return null
    }

    fun textData(): ByteArray = "test content".toByteArray()

    fun pngData(): ByteArray = byteArrayOf(
        0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
        0x00, 0x00, 0x00, 0x0D, 0x49, 0x48, 0x44, 0x52,
        0x00, 0x00, 0x00, 0x02, 0x00, 0x00, 0x00, 0x02,
        0x08, 0x02, 0x00, 0x00, 0x00, 0xFD.toByte(), 0xD4.toByte(), 0x9A.toByte(),
        0x73, 0x00, 0x00, 0x00, 0x14, 0x49, 0x44, 0x41,
        0x54, 0x78, 0x9C.toByte(), 0x62, 0xF8.toByte(), 0x0F, 0x00, 0x01,
        0x01, 0x00, 0x05, 0x18, 0xD8.toByte(), 0x4D, 0x00, 0x00,
        0x00, 0x00, 0x49, 0x45, 0x4E, 0x44, 0xAE.toByte(), 0x42,
        0x60, 0x82.toByte()
    )

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item {
            Text(
                "RiviumStorage SDK Example",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                "Demonstrates all SDK capabilities. IDs are captured from responses.",
                style = MaterialTheme.typography.bodyMedium,
                color = Color.Gray
            )
            Spacer(modifier = Modifier.height(16.dp))
        }

        // ============================================================
        // Bucket Operations
        // ============================================================
        item { SectionHeader("Bucket Operations") }

        item {
            ExampleButton("List All Buckets") {
                scope.launch {
                    log("Listing all buckets...")
                    try {
                        val buckets = storage.listBuckets()
                        log("Found ${buckets.size} bucket(s):")
                        buckets.forEach { bucket ->
                            log("   - ${bucket.name} (${bucket.visibility}) [${bucket.id}]")
                        }
                        if (buckets.isNotEmpty()) {
                            lastBucketId = buckets.first().id
                            log("")
                            log("   Stored bucket ID: ${lastBucketId} for next operations")
                        }
                    } catch (e: RiviumStorageException) {
                        log("Error: ${e.message}", true)
                    }
                }
            }
        }

        item {
            ExampleButton("Get Bucket by ID") {
                scope.launch {
                    if (lastBucketId == null) {
                        log("No bucket ID available. Run 'List All Buckets' first.", true)
                        return@launch
                    }
                    log("Getting bucket by ID: $lastBucketId")
                    try {
                        val bucket = storage.getBucket(lastBucketId!!)
                        log("Bucket: ${bucket.name}")
                        log("   - ID: ${bucket.id}")
                        log("   - Visibility: ${bucket.visibility}")
                        log("   - Policies Enabled: ${bucket.policiesEnabled}")
                        log("   - Active: ${bucket.isActive}")
                        bucket.allowedMimeTypes?.let {
                            log("   - Allowed MIME: ${it.joinToString(", ")}")
                        }
                        bucket.maxFileSize?.let {
                            log("   - Max File Size: ${formatBytes(it)}")
                        }
                    } catch (e: RiviumStorageException) {
                        log("Error: ${e.message}", true)
                    }
                }
            }
        }

        item {
            ExampleButton("Get Bucket by Name") {
                scope.launch {
                    log("Getting bucket by name: $BUCKET_NAME")
                    try {
                        val bucket = storage.getBucketByName(BUCKET_NAME)
                        log("Found: ${bucket.name} (${bucket.id})")
                        lastBucketId = bucket.id
                        log("   Stored bucket ID: ${bucket.id}")
                    } catch (e: RiviumStorageException) {
                        log("Error: ${e.message}", true)
                    }
                }
            }
        }

        // ============================================================
        // File Operations
        // ============================================================
        item { SectionHeader("File Operations") }

        item {
            ExampleButton("Upload Text File") {
                scope.launch {
                    if (lastBucketId == null) {
                        log("No bucket ID available. Run a bucket operation first.", true)
                        return@launch
                    }
                    val content = "Hello, RiviumStorage! Timestamp: ${System.currentTimeMillis()}"
                    val data = content.toByteArray()
                    val path = "examples/test-${System.currentTimeMillis()}.txt"

                    log("Uploading text file: $path")
                    try {
                        val file = storage.upload(
                            bucketId = lastBucketId!!,
                            path = path,
                            data = data,
                            options = UploadOptions(
                                contentType = "text/plain",
                                metadata = mapOf("author" to "Android Example", "version" to "1.0")
                            )
                        )
                        lastFileId = file.id
                        lastFilePath = file.path
                        uploadedFileIds.add(file.id)
                        log("Uploaded: ${file.fileName}")
                        log("   - ID: ${file.id}")
                        log("   - Path: ${file.path}")
                        log("   - Size: ${formatBytes(file.size)}")
                        log("   - MIME: ${file.mimeType}")
                        file.url?.let { log("   - URL: $it") }
                        log("   Stored file ID: ${file.id}")
                    } catch (e: RiviumStorageException) {
                        log("Error: ${e.message}", true)
                    }
                }
            }
        }

        item {
            ExampleButton("Upload Image (PNG)") {
                scope.launch {
                    if (lastBucketId == null) {
                        log("No bucket ID available. Run a bucket operation first.", true)
                        return@launch
                    }
                    // 1x1 red PNG
                    val redPixelPNG = byteArrayOf(
                        0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
                        0x00, 0x00, 0x00, 0x0D, 0x49, 0x48, 0x44, 0x52,
                        0x00, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x01,
                        0x08, 0x02, 0x00, 0x00, 0x00, 0x90.toByte(), 0x77, 0x53,
                        0xDE.toByte(), 0x00, 0x00, 0x00, 0x0C, 0x49, 0x44, 0x41,
                        0x54, 0x08, 0xD7.toByte(), 0x63, 0xF8.toByte(), 0xCF.toByte(),
                        0xC0.toByte(), 0x00, 0x00, 0x00, 0x03, 0x00, 0x01, 0x00,
                        0x05, 0xFE.toByte(), 0xD4.toByte(), 0xEF.toByte(), 0x00,
                        0x00, 0x00, 0x00, 0x49, 0x45, 0x4E, 0x44, 0xAE.toByte(),
                        0x42, 0x60, 0x82.toByte()
                    )
                    val path = "examples/images/sample-${System.currentTimeMillis()}.png"

                    log("Uploading image: $path")
                    try {
                        val file = storage.upload(
                            bucketId = lastBucketId!!,
                            path = path,
                            data = redPixelPNG,
                            options = UploadOptions(contentType = "image/png")
                        )
                        lastImageFileId = file.id
                        lastFileId = file.id
                        lastFilePath = file.path
                        uploadedFileIds.add(file.id)
                        log("Uploaded: ${file.fileName}")
                        log("   - ID: ${file.id}")
                        log("   - Size: ${formatBytes(file.size)}")
                        log("   Stored image file ID: ${file.id}")
                    } catch (e: RiviumStorageException) {
                        log("Error: ${e.message}", true)
                    }
                }
            }
        }

        item {
            ExampleButton("List Files") {
                scope.launch {
                    if (lastBucketId == null) {
                        log("No bucket ID available. Run a bucket operation first.", true)
                        return@launch
                    }
                    log("Listing files (prefix: examples/, limit: 10)...")
                    try {
                        val result = storage.listFiles(
                            bucketId = lastBucketId!!,
                            options = ListFilesOptions(prefix = "examples/", limit = 10)
                        )
                        log("Found ${result.files.size} file(s):")
                        result.files.forEach { file ->
                            log("   - ${file.path} (${formatBytes(file.size)}) [${file.id}]")
                        }
                        if (result.nextCursor != null) {
                            log("   (More files available, cursor: ${result.nextCursor!!.take(20)}...)")
                        }
                        if (result.files.isNotEmpty() && lastFileId == null) {
                            lastFileId = result.files.first().id
                            lastFilePath = result.files.first().path
                            log("")
                            log("   Stored file ID: $lastFileId from listing")
                        }
                    } catch (e: RiviumStorageException) {
                        log("Error: ${e.message}", true)
                    }
                }
            }
        }

        item {
            ExampleButton("Get File by ID") {
                scope.launch {
                    if (lastFileId == null) {
                        log("No file ID available. Upload a file or run 'List Files' first.", true)
                        return@launch
                    }
                    log("Getting file by ID: $lastFileId")
                    try {
                        val file = storage.getFile(lastFileId!!)
                        log("Found: ${file.fileName}")
                        log("   - Path: ${file.path}")
                        log("   - Size: ${formatBytes(file.size)}")
                        log("   - MIME: ${file.mimeType}")
                        log("   - Created: ${file.createdAt}")
                        log("   - Updated: ${file.updatedAt}")
                    } catch (e: RiviumStorageException) {
                        log("Error: ${e.message}", true)
                    }
                }
            }
        }

        item {
            ExampleButton("Get File by Path") {
                scope.launch {
                    if (lastBucketId == null || lastFilePath == null) {
                        log("No bucket or file path available. Upload a file first.", true)
                        return@launch
                    }
                    log("Getting file by path: $lastFilePath")
                    try {
                        val file = storage.getFileByPath(lastBucketId!!, lastFilePath!!)
                        log("Found: ${file.fileName} (${file.id})")
                        log("   - Size: ${formatBytes(file.size)}")
                        log("   - MIME: ${file.mimeType}")
                    } catch (e: RiviumStorageException) {
                        log("Error: ${e.message}", true)
                    }
                }
            }
        }

        item {
            ExampleButton("Download File") {
                scope.launch {
                    if (lastFileId == null) {
                        log("No file ID available. Upload a file first.", true)
                        return@launch
                    }
                    log("Downloading file: $lastFileId")
                    try {
                        val data = storage.download(lastFileId!!)
                        log("Downloaded ${formatBytes(data.size)}")
                        if (data.size < 200) {
                            try {
                                val content = String(data, Charsets.UTF_8)
                                log("   Content: \"$content\"")
                            } catch (_: Exception) {
                                log("   (Binary content)")
                            }
                        }
                    } catch (e: RiviumStorageException) {
                        log("Error: ${e.message}", true)
                    }
                }
            }
        }

        item {
            ExampleButton("Delete File") {
                scope.launch {
                    if (lastFileId == null) {
                        log("No file ID available. Upload a file first.", true)
                        return@launch
                    }
                    log("Deleting file: $lastFileId")
                    try {
                        storage.delete(lastFileId!!)
                        log("Deleted successfully")
                        uploadedFileIds.remove(lastFileId)
                        lastFileId = if (uploadedFileIds.isNotEmpty()) uploadedFileIds.last() else null
                        lastFilePath = null
                    } catch (e: RiviumStorageException) {
                        log("Error: ${e.message}", true)
                    }
                }
            }
        }

        item {
            ExampleButton("Delete by Path") {
                scope.launch {
                    if (lastBucketId == null || lastFilePath == null) {
                        log("No bucket or file path available. Upload a file first.", true)
                        return@launch
                    }
                    log("Deleting file by path: $lastFilePath")
                    try {
                        storage.deleteByPath(lastBucketId!!, lastFilePath!!)
                        log("Deleted successfully")
                        uploadedFileIds.remove(lastFileId)
                        lastFileId = if (uploadedFileIds.isNotEmpty()) uploadedFileIds.last() else null
                        lastFilePath = null
                    } catch (e: RiviumStorageException) {
                        log("Error: ${e.message}", true)
                    }
                }
            }
        }

        item {
            ExampleButton("Delete Multiple Files") {
                scope.launch {
                    if (uploadedFileIds.isEmpty()) {
                        log("No uploaded file IDs tracked. Upload some files first.", true)
                        return@launch
                    }
                    val idsToDelete = uploadedFileIds.toList()
                    log("Deleting ${idsToDelete.size} file(s): ${idsToDelete.joinToString(", ")}")
                    try {
                        val result = storage.deleteMany(idsToDelete)
                        log("Deleted ${result.deleted} file(s)")
                        uploadedFileIds.clear()
                        lastFileId = null
                        lastFilePath = null
                        lastImageFileId = null
                    } catch (e: RiviumStorageException) {
                        log("Error: ${e.message}", true)
                    }
                }
            }
        }

        // ============================================================
        // URL Generation
        // ============================================================
        item { SectionHeader("URL Generation") }

        item {
            ExampleButton("Generate All URL Types") {
                if (lastFileId == null) {
                    log("No file ID available. Upload a file first.", true)
                    return@ExampleButton
                }
                val fileId = lastFileId!!

                log("Generating URLs for file: $fileId")
                log("")

                val publicUrl = storage.getUrl(fileId)
                log("Public URL:")
                log("   $publicUrl")

                val downloadUrl = storage.getDownloadUrl(fileId)
                log("")
                log("Download URL:")
                log("   $downloadUrl")

                val thumbnailUrl = storage.getTransformUrl(
                    fileId = fileId,
                    transforms = ImageTransforms(width = 200, height = 200)
                )
                log("")
                log("Thumbnail URL (200x200):")
                log("   $thumbnailUrl")

                val advancedUrl = storage.getTransformUrl(
                    fileId = fileId,
                    transforms = ImageTransforms(
                        width = 800, height = 600,
                        fit = "cover", format = "webp", quality = 85
                    )
                )
                log("")
                log("Advanced Transform URL:")
                log("   $advancedUrl")
            }
        }

        // ============================================================
        // Image Transformations
        // ============================================================
        item { SectionHeader("Image Transformations") }

        item {
            ExampleButton("Show All Transform Options") {
                val fileId = lastImageFileId ?: lastFileId
                if (fileId == null) {
                    log("No file ID available. Upload an image first.", true)
                    return@ExampleButton
                }

                log("Image Transform Examples (file: $fileId):")
                log("=".repeat(50))

                val transforms = listOf(
                    "Resize 200x200" to ImageTransforms(width = 200, height = 200),
                    "Width only (auto height)" to ImageTransforms(width = 400),
                    "Height only (auto width)" to ImageTransforms(height = 300),
                    "Fit: cover" to ImageTransforms(width = 200, height = 200, fit = "cover"),
                    "Fit: contain" to ImageTransforms(width = 200, height = 200, fit = "contain"),
                    "Fit: fill" to ImageTransforms(width = 200, height = 200, fit = "fill"),
                    "Format: WebP" to ImageTransforms(width = 200, format = "webp"),
                    "Format: AVIF" to ImageTransforms(width = 200, format = "avif"),
                    "Format: JPEG" to ImageTransforms(width = 200, format = "jpeg"),
                    "Quality: 50%" to ImageTransforms(width = 200, format = "jpeg", quality = 50),
                    "Quality: 90%" to ImageTransforms(width = 200, format = "jpeg", quality = 90),
                    "Blur effect" to ImageTransforms(width = 200, blur = 10),
                    "Sharpen effect" to ImageTransforms(width = 200, sharpen = 50),
                    "Rotate 90" to ImageTransforms(rotate = 90),
                    "Rotate 180" to ImageTransforms(rotate = 180),
                    "Rotate 270" to ImageTransforms(rotate = 270),
                    "Combined transforms" to ImageTransforms(
                        width = 400, height = 300,
                        fit = "cover", format = "webp",
                        quality = 80, sharpen = 20
                    )
                )

                transforms.forEach { (name, transform) ->
                    val url = storage.getTransformUrl(fileId, transform)
                    log("")
                    log("$name:")
                    log("   $url")
                }
            }
        }

        // ============================================================
        // Policy Testing
        // ============================================================
        item { SectionHeader("Policy Testing") }

        // Test: No Rules
        item {
            ExampleButton("Test: No Rules (allow all)") {
                scope.launch {
                    val bucketId = ensureBucket(storage, lastBucketId, ::log) { lastBucketId = it }
                        ?: return@launch

                    log("")
                    log("=".repeat(40))
                    log("  TEST: No Rules (no policy on bucket)")
                    log("  Dashboard: Delete the policy from bucket")
                    log("  When no policy exists, all access is allowed")
                    log("=".repeat(40))
                    log("")
                    log("Current userId: ${storage.userId ?: "none (unauthenticated)"}")
                    log("")

                    val ts = System.currentTimeMillis()
                    log("Upload text file:   ${tryUpload(storage, bucketId, "test/no-rules-$ts.txt", textData())}")
                    log("Upload image:       ${tryUpload(storage, bucketId, "test/no-rules-$ts.png", pngData(), "image/png")}")
                    log("List files:         ${tryList(storage, bucketId)}")
                    log("")
                    log("Expected: Everything ALLOWED (no policy = no restrictions)")
                }
            }
        }

        // Test: Private
        item {
            ExampleButton("Test: Private (login required)") {
                scope.launch {
                    val bucketId = ensureBucket(storage, lastBucketId, ::log) { lastBucketId = it }
                        ?: return@launch

                    log("")
                    log("=".repeat(40))
                    log("  TEST: Private Template")
                    log("  Dashboard: Apply 'Private' template")
                    log("  Rule: Allow only authenticated users")
                    log("  (default-deny: unauthenticated = denied)")
                    log("=".repeat(40))
                    log("")

                    val ts = System.currentTimeMillis()

                    // Test WITH userId
                    log("-- With userId: ${storage.userId} --")
                    log("Upload text:   ${tryUpload(storage, bucketId, "test/private-$ts.txt", textData())}")
                    log("Upload image:  ${tryUpload(storage, bucketId, "test/private-$ts.png", pngData(), "image/png")}")
                    log("List files:    ${tryList(storage, bucketId)}")
                    log("")

                    // Test WITHOUT userId
                    val savedUserId = storage.userId
                    storage.setUserId(null)
                    log("-- Without userId (unauthenticated) --")
                    log("Upload text:   ${tryUpload(storage, bucketId, "test/private-anon-$ts.txt", textData())}")
                    log("List files:    ${tryList(storage, bucketId)}")
                    storage.setUserId(savedUserId)

                    log("")
                    log("Expected:")
                    log("  With userId:    Everything ALLOWED")
                    log("  Without userId: Everything DENIED")
                }
            }
        }

        // Test: Public Read
        item {
            ExampleButton("Test: Public Read") {
                scope.launch {
                    val bucketId = ensureBucket(storage, lastBucketId, ::log) { lastBucketId = it }
                        ?: return@launch

                    log("")
                    log("=".repeat(40))
                    log("  TEST: Public Read Template")
                    log("  Dashboard: Apply 'Public Read' template")
                    log("  Rule: Anyone can read/list,")
                    log("        auth required to write/delete")
                    log("=".repeat(40))
                    log("")

                    val ts = System.currentTimeMillis()

                    // Test WITH userId
                    log("-- With userId: ${storage.userId} --")
                    log("Upload text:   ${tryUpload(storage, bucketId, "test/public-$ts.txt", textData())}")
                    log("List files:    ${tryList(storage, bucketId)}")
                    log("")

                    // Test WITHOUT userId
                    val savedUserId = storage.userId
                    storage.setUserId(null)
                    log("-- Without userId (unauthenticated) --")
                    log("List files:    ${tryList(storage, bucketId)}")
                    log("Upload text:   ${tryUpload(storage, bucketId, "test/public-anon-$ts.txt", textData())}")
                    storage.setUserId(savedUserId)

                    log("")
                    log("Expected:")
                    log("  With userId:    Upload ALLOWED, List ALLOWED")
                    log("  Without userId: List ALLOWED, Upload DENIED")
                }
            }
        }

        // Test: User Folders
        item {
            ExampleButton("Test: User Folders") {
                scope.launch {
                    val bucketId = ensureBucket(storage, lastBucketId, ::log) { lastBucketId = it }
                        ?: return@launch

                    log("")
                    log("=".repeat(40))
                    log("  TEST: User Folders Template")
                    log("  Dashboard: Apply 'User Folders' template")
                    log("  Rule: Auth users can read/list all,")
                    log("        write/delete only in users/{userId}/")
                    log("=".repeat(40))
                    log("")

                    val uid = storage.userId ?: "demo-user-123"
                    val ts = System.currentTimeMillis()

                    log("-- With userId: $uid --")
                    log("")
                    log("Upload to own folder (users/$uid/):")
                    log("  users/$uid/photo.txt:        ${tryUpload(storage, bucketId, "users/$uid/photo-$ts.txt", textData())}")
                    log("  users/$uid/sub/doc.txt:      ${tryUpload(storage, bucketId, "users/$uid/sub/doc-$ts.txt", textData())}")
                    log("")
                    log("Upload to OTHER user folder:")
                    log("  users/other-user/hack.txt:   ${tryUpload(storage, bucketId, "users/other-user/hack-$ts.txt", textData())}")
                    log("")
                    log("Upload to root (no user folder):")
                    log("  test/random.txt:             ${tryUpload(storage, bucketId, "test/random-$ts.txt", textData())}")
                    log("")
                    log("List files:                    ${tryList(storage, bucketId)}")

                    // Test without userId
                    val savedUserId = storage.userId
                    storage.setUserId(null)
                    log("")
                    log("-- Without userId (unauthenticated) --")
                    log("Upload:   ${tryUpload(storage, bucketId, "users/anon/test-$ts.txt", textData())}")
                    log("List:     ${tryList(storage, bucketId)}")
                    storage.setUserId(savedUserId)

                    log("")
                    log("Expected:")
                    log("  Own folder:     ALLOWED")
                    log("  Other folder:   DENIED")
                    log("  Root path:      DENIED")
                    log("  List:           ALLOWED")
                    log("  No userId:      DENIED (all)")
                }
            }
        }

        // Test: Images Only
        item {
            ExampleButton("Test: Images Only") {
                scope.launch {
                    val bucketId = ensureBucket(storage, lastBucketId, ::log) { lastBucketId = it }
                        ?: return@launch

                    log("")
                    log("=".repeat(40))
                    log("  TEST: Images Only Template")
                    log("  Dashboard: Apply 'Images Only' template")
                    log("  Rule: Anyone can read/list/delete,")
                    log("        only auth users can upload images")
                    log("        (JPEG/PNG/GIF/WebP, 5MB max)")
                    log("=".repeat(40))
                    log("")

                    val ts = System.currentTimeMillis()

                    log("-- With userId: ${storage.userId} --")
                    log("")
                    log("Upload PNG image:        ${tryUpload(storage, bucketId, "test/image-$ts.png", pngData(), "image/png")}")
                    log("Upload text file:        ${tryUpload(storage, bucketId, "test/doc-$ts.txt", textData(), "text/plain")}")
                    log("Upload PDF:              ${tryUpload(storage, bucketId, "test/doc-$ts.pdf", textData(), "application/pdf")}")
                    log("List files:              ${tryList(storage, bucketId)}")

                    // Test without userId
                    val savedUserId = storage.userId
                    storage.setUserId(null)
                    log("")
                    log("-- Without userId (unauthenticated) --")
                    log("Upload PNG:   ${tryUpload(storage, bucketId, "test/anon-$ts.png", pngData(), "image/png")}")
                    log("Upload text:  ${tryUpload(storage, bucketId, "test/anon-$ts.txt", textData())}")
                    log("List files:   ${tryList(storage, bucketId)}")
                    storage.setUserId(savedUserId)

                    log("")
                    log("Expected:")
                    log("  PNG image:      ALLOWED")
                    log("  Text file:      DENIED (not an image)")
                    log("  PDF file:       DENIED (not an image)")
                    log("  List:           ALLOWED (read is open)")
                    log("  No userId PNG:  DENIED (auth required for upload)")
                    log("  No userId List: ALLOWED (read is open)")
                }
            }
        }

        // ============================================================
        // Error Handling
        // ============================================================
        item { SectionHeader("Error Handling") }

        item {
            ExampleButton("Demonstrate Error Handling") {
                scope.launch {
                    log("Testing error handling with invalid file ID...")
                    try {
                        storage.getFile("non-existent-file-id")
                    } catch (e: RiviumStorageException) {
                        log("")
                        when (e) {
                            is RiviumStorageException.NetworkException ->
                                log("Network Error: ${e.message}", true)
                            is RiviumStorageException.HttpException ->
                                log("HTTP ${e.statusCode}: ${e.message}", true)
                            is RiviumStorageException.UploadException ->
                                log("Upload Error: ${e.message}", true)
                            is RiviumStorageException.DownloadException ->
                                log("Download Error: ${e.message}", true)
                            is RiviumStorageException.InvalidResponseException ->
                                log("Invalid Response", true)
                            is RiviumStorageException.EncodingException ->
                                log("Encoding Error: ${e.message}", true)
                        }
                        log("")
                        log("Error handling example complete!")
                    }
                }
            }
        }

        // ============================================================
        // Output Log
        // ============================================================
        item {
            SectionHeader("Output Log")
            Spacer(modifier = Modifier.height(8.dp))
            Button(
                onClick = { logs.clear() },
                colors = ButtonDefaults.buttonColors(containerColor = Color.Gray)
            ) {
                Text("Clear Log")
            }
        }

        items(logs) { entry ->
            Text(
                text = entry.message,
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
                color = if (entry.isError) Color.Red else Color.Unspecified,
                modifier = Modifier.padding(vertical = 2.dp)
            )
        }

        item {
            Spacer(modifier = Modifier.height(100.dp))
        }
    }
}

@Composable
fun SectionHeader(title: String) {
    Spacer(modifier = Modifier.height(16.dp))
    Text(
        text = title,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.primary
    )
    Divider(modifier = Modifier.padding(vertical = 8.dp))
}

@Composable
fun ExampleButton(
    text: String,
    onClick: () -> Unit
) {
    Button(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(text)
    }
}

data class LogEntry(
    val message: String,
    val isError: Boolean = false
)

fun formatBytes(bytes: Int): String {
    if (bytes < 1024) return "$bytes B"
    val kb = bytes / 1024.0
    if (kb < 1024) return "${DecimalFormat("#.##").format(kb)} KB"
    val mb = kb / 1024.0
    return "${DecimalFormat("#.##").format(mb)} MB"
}
