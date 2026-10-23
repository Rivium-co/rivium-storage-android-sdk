package co.rivium.storage.example.ui

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Divider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import co.rivium.storage.RiviumStorage
import co.rivium.storage.example.ActionResult
import co.rivium.storage.example.BuildConfig
import co.rivium.storage.example.ExampleConfig
import co.rivium.storage.example.SelfTest
import co.rivium.storage.example.StorageViewModel
import co.rivium.storage.example.formatBytes
import kotlinx.coroutines.delay
import java.io.File

enum class Section(val title: String, val subtitle: String) {
    SETUP("Setup and sign-in", "Public key, user token provider, setUserToken, sign in / out"),
    SELF_TEST("Run all checks", "One tap: the core flow with pass / fail per step"),
    BUCKETS("Buckets", "listBuckets, getBucket, getBucketByName"),
    UPLOAD("Upload", "Bytes, photo picker, camera, any file, Unicode names, metadata"),
    FILES("Files", "List with prefix and paging, get, download, delete, deleteMany"),
    LINKS("Links and images", "Public link, signed link, download(): the right way to show a file"),
    TRANSFORMS("Image transforms", "Width, height, fit, format, quality, blur, sharpen, rotate"),
    ERRORS("Errors", "Every error the SDK throws, triggered on purpose"),
    LOG("Request log", "Each request the SDK sent (custom OkHttpClient via .client())"),
}

// ------------------------------------------------------------------------------------------------
// Home
// ------------------------------------------------------------------------------------------------

@Composable
fun HomeScreen(vm: StorageViewModel, padding: PaddingValues, open: (Section) -> Unit) {
    ScreenColumn(padding) {
        item {
            Intro(
                "Every feature of the Rivium Storage Android SDK, each with a short note on when to use it and its real result. " +
                    "Built against the SDK source in this repository (${RiviumStorage.SDK_NAME}/${RiviumStorage.SDK_VERSION}, ${BuildConfig.BUILD_TYPE} build)."
            )
        }
        item { ConfigCard(vm) }
        items(Section.values().toList()) { section ->
            Card(
                modifier = Modifier.fillMaxWidth().clickable { open(section) },
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f))
            ) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(section.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        Text(section.subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Icon(Icons.Default.KeyboardArrowRight, contentDescription = null)
                }
            }
        }
    }
}

@Composable
fun ConfigCard(vm: StorageViewModel) {
    val cfg = ExampleConfig
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text("Configuration (example/rivium.properties)", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            KeyValue("Public key", if (cfg.hasApiKey) cfg.maskedKey else "NOT SET (placeholder)")
            KeyValue("Token server", cfg.tokenServerUrl.ifEmpty { "not set" })
            KeyValue("Public bucket", cfg.publicBucket)
            KeyValue("Private bucket", cfg.privateBucket)
            KeyValue("Demo user", cfg.demoUserId)
            KeyValue("Signed in", if (vm.signedIn) "yes" else "no")
            if (vm.awaitingUserToken) {
                Text(
                    "Waiting for a user token — sign in. File requests wait until a user is signed in (isAwaitingUserToken).",
                    color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold
                )
            }
            vm.results["startup"]?.let { Spacer(Modifier.size(6.dp)); ResultPanel(it) }
            if (!cfg.hasApiKey) {
                Text(
                    "Copy rivium.properties.example to rivium.properties, put your public key in it, and rebuild. See README.md.",
                    color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}

// ------------------------------------------------------------------------------------------------
// Setup and sign-in
// ------------------------------------------------------------------------------------------------

@Composable
fun SetupScreen(vm: StorageViewModel, padding: PaddingValues) {
    ScreenColumn(padding) {
        item { ConfigCard(vm) }
        item {
            DemoCard(
                vm, "Initialise with the public key and a token provider",
                "The app ships only the public key (rv_live_…). Every file request needs a signed Rivium user token: from Rivium Auth " +
                    "(RiviumAuth.shared.blockingTokenProvider), or minted by YOUR server with the server secret (this example). The SDK " +
                    "asks the provider before the first request, shortly before expiry, and once more if the server says it expired or is " +
                    "invalid. Return null when no one is signed in: file requests then wait for sign-in.",
                keys = emptyList(),
                code = """
                    val storage = RiviumStorage.builder("rv_live_…")
                        .timeout(30)                 // seconds
                        .client(myOkHttpClient)      // optional: your own OkHttp
                        .userTokenProvider {         // background thread
                            if (signedIn) myServer.fetchRiviumToken() else null
                        }
                        // With Rivium Auth instead: .userTokenProvider(RiviumAuth.shared.blockingTokenProvider)
                        // and call storage.refreshUserToken() from its auth state listener.
                        .onAwaitingUserToken { showSignIn() } // no token: file requests wait
                        // .userTokenWaitTimeout(30, TimeUnit.SECONDS) // optional: then token_required
                        .build()
                """.trimIndent(),
                actions = {},
                extra = {
                    KeyValue("SDK", "${RiviumStorage.SDK_NAME}/${RiviumStorage.SDK_VERSION}")
                    KeyValue("Token held", vm.tokenSummary())
                    KeyValue("Provider", vm.providerStatus)
                    KeyValue("isAwaitingUserToken", if (vm.awaitingUserToken) "true — waiting for a user token, sign in" else "false")
                }
            )
        }
        item {
            DemoCard(
                vm, "Sign in / sign out (refreshUserToken)",
                "Call refreshUserToken() when your user signs in or out: the SDK forgets the token and asks the provider again. " +
                    "Signed out, the provider returns null and file requests wait until the next sign-in.",
                keys = listOf("signIn", "signOut"),
                actions = {
                    ActionButton(vm, "signIn", "Sign in as ${ExampleConfig.demoUserId}") { vm.signIn() }
                    ActionButton(vm, "signOut", "Sign out", primary = false) { vm.signOut() }
                }
            )
        }
        item {
            DemoCard(
                vm, "Calls wait until sign-in",
                "Signs out, then starts listFiles: with no user token it is not sent and does not fail, it waits " +
                    "(isAwaitingUserToken = true, onAwaitingUserToken fired once). Tap \"Sign in\" above: it completes by itself.",
                keys = listOf("waitForSignIn"),
                code = "storage.onAwaitingUserToken = { showSignIn() }  // background thread",
                extra = { KeyValue("isAwaitingUserToken", vm.awaitingUserToken.toString()) },
                actions = { ActionButton(vm, "waitForSignIn", "Sign out and list") { vm.waitForSignIn() } }
            )
        }
        item {
            DemoCard(
                vm, "setUserToken(token)",
                "When your app already holds the token (for example Rivium Auth returned it at sign-in), hand it over directly. " +
                    "Waiting file requests go on with it. setUserToken(null) (sign-out) makes new file requests wait.",
                keys = listOf("setUserToken"),
                code = "storage.setUserToken(token)   // same as storage.userTokens.set(token)",
                actions = { ActionButton(vm, "setUserToken", "Fetch a token and set it") { vm.setTokenByHand() } }
            )
        }
        item {
            DemoCard(
                vm, "setUserTokenProvider(provider)",
                "Install or replace the provider after the SDK was built (null removes it). The held token is dropped and the new provider is asked.",
                keys = listOf("setUserTokenProvider"),
                code = "storage.setUserTokenProvider { myServer.fetchRiviumToken() }",
                actions = { ActionButton(vm, "setUserTokenProvider", "Replace the provider") { vm.replaceProvider() } }
            )
        }
    }
}

// ------------------------------------------------------------------------------------------------
// Buckets
// ------------------------------------------------------------------------------------------------

@Composable
fun BucketsScreen(vm: StorageViewModel, padding: PaddingValues) {
    ScreenColumn(padding) {
        item {
            Intro(
                "With the public key an app can read buckets (list and get). Creating, changing and deleting buckets and their rules " +
                    "is for your server with the server secret (or the Console), so the SDK has no methods for it."
            )
        }
        item {
            DemoCard(vm, "listBuckets()", "All buckets in the project, with their visibility, allowed types and size limit.",
                listOf("listBuckets"), actions = { ActionButton(vm, "listBuckets", "List buckets") { vm.listBuckets() } })
        }
        item {
            DemoCard(vm, "getBucketByName(name)", "Look a bucket up by the name you gave it. Upload and list also accept the name in place of the id.",
                listOf("getBucketByName:${ExampleConfig.privateBucket}", "getBucketByName:${ExampleConfig.publicBucket}"),
                actions = {
                    ActionButton(vm, "getBucketByName:${ExampleConfig.privateBucket}", ExampleConfig.privateBucket) { vm.getBucketByName(ExampleConfig.privateBucket) }
                    ActionButton(vm, "getBucketByName:${ExampleConfig.publicBucket}", ExampleConfig.publicBucket, primary = false) { vm.getBucketByName(ExampleConfig.publicBucket) }
                })
        }
        item {
            DemoCard(vm, "getBucket(id)", "Look a bucket up by id (the id comes from the private bucket found by name).",
                listOf("getBucket"), actions = { ActionButton(vm, "getBucket", "Get by id") { vm.getBucketById() } })
        }
    }
}

// ------------------------------------------------------------------------------------------------
// Upload
// ------------------------------------------------------------------------------------------------

private fun cameraUri(context: Context): Uri {
    val dir = File(context.cacheDir, "camera").apply { mkdirs() }
    val file = File(dir, "photo-${System.currentTimeMillis()}.jpg")
    return FileProvider.getUriForFile(context, "${context.packageName}.files", file)
}

@Composable
fun UploadScreen(vm: StorageViewModel, padding: PaddingValues) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var toPrivate by rememberSaveable { mutableStateOf(true) }
    var pendingCamera by remember { mutableStateOf<Uri?>(null) }

    val photoPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) vm.uploadFromUri("uploadPhoto", uri, toPrivate, "photo.jpg")
    }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { saved ->
        val uri = pendingCamera
        if (saved && uri != null) vm.uploadFromUri("uploadCamera", uri, toPrivate, "camera.jpg")
    }
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.uploadFromUri("uploadFile", uri, toPrivate = false, fallbackName = "file.bin")
    }

    ScreenColumn(padding) {
        item {
            Intro(
                "upload(bucketId, path, data, UploadOptions(contentType, metadata)) sends bytes. The private test bucket takes images only, " +
                    "under users/<your id>/ (its rules). The server checks the real content, not just the declared type."
            )
        }
        item {
            DemoCard(
                vm, "Upload bytes (with content type and metadata)",
                "Use when the app already has the bytes. metadata is any small JSON map stored with the file.",
                listOf("uploadSample", "uploadPublic"),
                code = """
                    storage.upload(
                        bucketId = "${ExampleConfig.privateBucket}",
                        path = "users/${'$'}userId/example/photo.png",
                        data = pngBytes,
                        options = UploadOptions(contentType = "image/png",
                            metadata = mapOf("source" to "android-example")))
                """.trimIndent(),
                actions = {
                    ActionButton(vm, "uploadSample", "Private bucket") { vm.uploadSampleToPrivate() }
                    ActionButton(vm, "uploadPublic", "Public bucket", primary = false) { vm.uploadSampleToPublic() }
                }
            )
        }
        item {
            DemoCard(
                vm, "Photo picker and camera",
                "Pick a photo (system photo picker, no permission needed) or take one with the camera app, then upload it.",
                listOf("uploadPhoto", "uploadCamera"),
                extra = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = toPrivate, onClick = { toPrivate = true }); Text("Private bucket", style = MaterialTheme.typography.bodySmall)
                        Spacer(Modifier.width(8.dp))
                        RadioButton(selected = !toPrivate, onClick = { toPrivate = false }); Text("Public bucket", style = MaterialTheme.typography.bodySmall)
                    }
                },
                actions = {
                    ActionButton(vm, "uploadPhoto", "Pick a photo") {
                        photoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    }
                    ActionButton(vm, "uploadCamera", "Take a photo", primary = false) {
                        val uri = cameraUri(context); pendingCamera = uri; camera.launch(uri)
                    }
                }
            )
        }
        item {
            DemoCard(
                vm, "Any file (file picker)",
                "Pick any document. It goes to the public test bucket, which accepts every type (the private one takes images only). " +
                    "The file's own name is kept, Unicode and spaces included.",
                listOf("uploadFile"),
                actions = { ActionButton(vm, "uploadFile", "Pick a file") { filePicker.launch(arrayOf("*/*")) } }
            )
        }
        item {
            DemoCard(
                vm, "Unicode and spaces in names",
                "Names are stored as typed: Persian, Arabic, accents, spaces. Only control characters and \"..\" are refused.",
                listOf("uploadUnicode", "uploadSpaces"),
                actions = {
                    ActionButton(vm, "uploadUnicode", "رسید-۸۸۱۲.pdf") { vm.uploadUnicodeName() }
                    ActionButton(vm, "uploadSpaces", "my notes 2026.txt", primary = false) { vm.uploadNameWithSpaces() }
                }
            )
        }
        item {
            DemoCard(
                vm, "Content type left out",
                "Without contentType the SDK guesses from the extension (application/octet-stream otherwise); the server then checks the bytes.",
                listOf("uploadAutoType"),
                actions = { ActionButton(vm, "uploadAutoType", "Upload settings.json") { vm.uploadWithoutContentType() } }
            )
        }
        item {
            DemoCard(
                vm, "Upload to the same path again",
                "Uploading to an existing path replaces the file in place: same id, new bytes, metadata merged.",
                listOf("overwrite"),
                actions = { ActionButton(vm, "overwrite", "Replace the selected image") { vm.overwriteSelected() } }
            )
        }
        item {
            DemoCard(
                vm, "Upload progress",
                "Pass onProgress to upload() to draw a progress bar. It runs on the main thread, about every 50 ms or 1%, and the " +
                    "last call has bytesSent == totalBytes (the request body: your file plus a few hundred bytes of form fields). " +
                    "The other upload cards here use it too. This one sends a generated ~5 MB PNG (noise pixels, so it does not compress) " +
                    "to the public bucket; if the bucket doesn't allow image/png it says so.",
                listOf("uploadLarge"),
                code = """
                    storage.upload(bucketId, path, bytes,
                        onProgress = { p ->
                            progressBar.progress = (p.fraction * 100).toInt()
                        })
                """.trimIndent(),
                actions = { ActionButton(vm, "uploadLarge", "Upload a 5 MB PNG") { vm.uploadLargeFile() } }
            )
        }
    }
}

// ------------------------------------------------------------------------------------------------
// Files
// ------------------------------------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FilesScreen(vm: StorageViewModel, padding: PaddingValues) {
    ScreenColumn(padding) {
        item {
            DemoCard(
                vm, "listFiles(bucket, ListFilesOptions(prefix, limit, cursor))",
                "List a folder: prefix filters by path start, limit sets the page size, and nextCursor fetches the next page.",
                listOf("listFiles"),
                extra = {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(selected = vm.listBucket == ExampleConfig.privateBucket, onClick = {
                            vm.listBucket = ExampleConfig.privateBucket; vm.listPrefix = "users/${ExampleConfig.demoUserId}/"
                        }, label = { Text("Private") })
                        FilterChip(selected = vm.listBucket == ExampleConfig.publicBucket, onClick = {
                            vm.listBucket = ExampleConfig.publicBucket; vm.listPrefix = "users/${ExampleConfig.demoUserId}/"
                        }, label = { Text("Public") })
                    }
                    OutlinedTextField(vm.listPrefix, { vm.listPrefix = it }, label = { Text("prefix") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(vm.listLimit, { vm.listLimit = it }, label = { Text("limit") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
                },
                actions = {
                    ActionButton(vm, "listFiles", "List") { vm.listFiles(nextPage = false) }
                    ActionButton(vm, "listFiles", "Next page", primary = false) { vm.listFiles(nextPage = true) }
                }
            )
        }
        item { FilePicker(vm) }
        item {
            DemoCard(vm, "getFile(id) / getFileByPath(bucket, path)", "Read a file's details (size, type, metadata) without downloading it.",
                listOf("getFile", "getFileByPath"), actions = {
                    ActionButton(vm, "getFile", "getFile") { vm.getFile() }
                    ActionButton(vm, "getFileByPath", "getFileByPath", primary = false) { vm.getFileByPath() }
                })
        }
        item {
            DemoCard(vm, "download(id)", "The file's bytes, with your key and user token, following the bucket's read rules. Works for private files.",
                listOf("download"), actions = { ActionButton(vm, "download", "Download") { vm.download() } })
        }
        item {
            DemoCard(vm, "delete(id) / deleteByPath(bucket, path)", "Delete one file. Follows the bucket's delete rules.",
                listOf("delete", "deleteByPath"), actions = {
                    ActionButton(vm, "delete", "delete") { vm.deleteSelected() }
                    ActionButton(vm, "deleteByPath", "deleteByPath", primary = false) { vm.deleteSelectedByPath() }
                })
        }
        item {
            DemoCard(vm, "deleteMany(ids)", "Delete several files in one request; answers how many were deleted. Here: everything this session uploaded.",
                listOf("deleteMany"), actions = { ActionButton(vm, "deleteMany", "Delete all ${vm.uploaded.size} uploaded") { vm.deleteAllUploaded() } })
        }
    }
}

/** Choose the file the Files / Links screens act on: from this session's uploads or the last listing. */
@Composable
fun FilePicker(vm: StorageViewModel) {
    val choices = (vm.uploaded + vm.listed).distinctBy { it.id }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text("Selected file", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            if (choices.isEmpty()) {
                Text("Upload something (Upload screen) or list files above.", style = MaterialTheme.typography.bodySmall)
            }
            choices.take(12).forEach { f ->
                Row(
                    Modifier.fillMaxWidth().clickable { vm.selectedFile = f },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(selected = vm.selectedFile?.id == f.id, onClick = { vm.selectedFile = f })
                    Column {
                        Text(f.path, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                        Text("${f.mimeType}, ${formatBytes(f.size.toLong())}", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
    }
}

// ------------------------------------------------------------------------------------------------
// Links and images
// ------------------------------------------------------------------------------------------------

@Composable
fun LinksScreen(vm: StorageViewModel, padding: PaddingValues) {
    ScreenColumn(padding) {
        item {
            Intro(
                "Image widgets (Coil, Glide, ImageView) load a URL without headers. Storage API addresses need the x-api-key header, so: " +
                    "public bucket -> the file's public link; private bucket -> download() the bytes, or a signed link made by your server."
            )
        }
        item {
            DemoCard(
                vm, "Public bucket: the public link",
                "GET the getUrl(id) endpoint once with the key; it answers {\"url\": …}, a link that needs no headers. Use it in any image " +
                    "widget, share sheet or web page. Anyone with the link can open the file.",
                listOf("publicLink"),
                actions = { ActionButton(vm, "publicLink", "Get the public link") { vm.publicLink() } }
            )
        }
        item {
            DemoCard(
                vm, "No public link for a private file",
                "The same endpoint refuses files in a private bucket (403).",
                listOf("publicLinkPrivate"),
                actions = { ActionButton(vm, "publicLinkPrivate", "Try on the private image") { vm.publicLinkOfPrivate() } }
            )
        }
        item {
            DemoCard(
                vm, "Private bucket: download() and show the bytes",
                "The simplest safe way to show a private image: the SDK sends the key and the user token, and the rules decide.",
                listOf("privateDownload"),
                actions = { ActionButton(vm, "privateDownload", "Download and show") { vm.showPrivateByDownload() } }
            )
        }
        item {
            DemoCard(
                vm, "Private bucket: a signed link (expires)",
                "When something without headers must open a private file (an image widget, a browser, a share), your server makes a " +
                    "time-limited link with the server secret (Node.js SDK getSignedUrl(fileId, { expiresIn })). The app never holds the secret.",
                listOf("signedUrl"),
                actions = {
                    ActionButton(vm, "signedUrl", "60 seconds") { vm.signedLink(60) }
                    ActionButton(vm, "signedUrl", "10 minutes", primary = false) { vm.signedLink(600) }
                }
            )
        }
        item {
            DemoCard(
                vm, "The wrong way: getUrl(id) in an image widget",
                "getUrl(id), getDownloadUrl(id) and getTransformUrl(id) are API addresses: they need the x-api-key header (and the user token), " +
                    "and getUrl answers JSON, not an image. An image widget sends no headers, so the image stays broken.",
                listOf("wrongWay"),
                actions = { ActionButton(vm, "wrongWay", "Show what happens", primary = false) { vm.wrongWay() } }
            )
        }
        item {
            DemoCard(
                vm, "getDownloadUrl(id)",
                "For an HTTP client that can send headers (a download manager, your own OkHttp). Without headers it is refused.",
                listOf("downloadUrl"),
                actions = { ActionButton(vm, "downloadUrl", "Fetch with and without headers") { vm.downloadUrlWithHeaders() } }
            )
        }
    }
}

// ------------------------------------------------------------------------------------------------
// Transforms
// ------------------------------------------------------------------------------------------------

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun TransformsScreen(vm: StorageViewModel, padding: PaddingValues) {
    ScreenColumn(padding) {
        item {
            Intro(
                "getTransformUrl(id, ImageTransforms(...)) builds the address of a resized / converted copy, made on request. It needs the " +
                    "x-api-key header (and x-user-token), so this screen fetches it with headers and shows the result. Source: " +
                    (vm.anyImage?.path ?: "upload an image first")
            )
        }
        item {
            DemoCard(
                vm, "ImageTransforms",
                "width / height in pixels (max 4096); fit: cover crops, contain pads, fill stretches, inside / outside never upscale / downscale; " +
                    "format: jpeg, png, webp, avif; quality 1-100; blur 0-20; sharpen 0-10; rotate 90 / 180 / 270.",
                listOf("transform"),
                extra = {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(vm.tWidth, { vm.tWidth = it }, label = { Text("width") }, singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.weight(1f))
                        OutlinedTextField(vm.tHeight, { vm.tHeight = it }, label = { Text("height") }, singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.weight(1f))
                    }
                    ChipRow("fit", listOf("cover", "contain", "fill", "inside", "outside"), vm.tFit) { vm.tFit = it }
                    ChipRow("format", listOf("jpeg", "png", "webp", "avif"), vm.tFormat) { vm.tFormat = it }
                    LabeledSlider("quality", vm.tQuality, 1f..100f) { vm.tQuality = it }
                    LabeledSlider("blur", vm.tBlur, 0f..20f) { vm.tBlur = it }
                    LabeledSlider("sharpen", vm.tSharpen, 0f..10f) { vm.tSharpen = it }
                    ChipRow("rotate", listOf("0", "90", "180", "270"), vm.tRotate.toString()) { vm.tRotate = it?.toInt() ?: 0 }
                    Text("Presets", style = MaterialTheme.typography.labelMedium)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TonalButton("Avatar 100x100") { vm.tWidth = "100"; vm.tHeight = "100"; vm.tFit = "cover"; vm.tFormat = "webp"; vm.tQuality = 80f; vm.tBlur = 0f; vm.tSharpen = 0f; vm.tRotate = 0 }
                        TonalButton("Placeholder") { vm.tWidth = "20"; vm.tHeight = ""; vm.tFit = null; vm.tFormat = "webp"; vm.tQuality = 30f; vm.tBlur = 10f; vm.tSharpen = 0f; vm.tRotate = 0 }
                        TonalButton("Product 600 contain") { vm.tWidth = "600"; vm.tHeight = "600"; vm.tFit = "contain"; vm.tFormat = "jpeg"; vm.tQuality = 90f; vm.tBlur = 0f; vm.tSharpen = 0f; vm.tRotate = 0 }
                    }
                    CodeBlock("storage.getTransformUrl(fileId, ImageTransforms(${vm.currentTransforms().toQueryParams()}))")
                },
                actions = { ActionButton(vm, "transform", "Apply") { vm.applyTransform() } }
            )
        }
        item {
            DemoCard(
                vm, "Public bucket: header-free link",
                "getPublicUrl(publicFileId, ImageTransforms(...)) with the options above returns the public link with them " +
                    "(…/public/<bucketId>/<path>?w=200&f=webp). It goes straight into Coil's AsyncImage or Glide with NO headers, " +
                    "and the CDN caches it. Source: " + (vm.publicImage?.path ?: "a sample PNG uploaded to the public bucket on first use") + ".",
                listOf("transformPublic"),
                code = "AsyncImage(model = storage.getPublicUrl(fileId, ImageTransforms(width = 600, format = \"webp\")), contentDescription = null)",
                extra = {
                    Text(
                        "Use a few fixed sizes: one image keeps at most 50 variants (option combinations); a new one beyond that is " +
                            "refused (429 too_many_variants). The same options always give the same URL, so later views are cache hits.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
                actions = { ActionButton(vm, "transformPublic", "Show public link") { vm.applyPublicTransform() } }
            )
        }
        item {
            DemoCard(
                vm, "Coil with headers",
                "To show a transform directly in Coil, add the headers to the request (the docs' pattern):",
                emptyList(),
                code = """
                    AsyncImage(model = ImageRequest.Builder(context)
                        .data(storage.getTransformUrl(id, ImageTransforms(width = 200)))
                        .addHeader("x-api-key", "rv_live_…")
                        .addHeader("x-user-token", token) // when you have one
                        .build(), contentDescription = null)
                """.trimIndent(),
                actions = {}
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun ChipRow(label: String, options: List<String>, selected: String?, onSelect: (String?) -> Unit) {
    Text(label, style = MaterialTheme.typography.labelMedium)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        options.forEach { option ->
            FilterChip(
                selected = selected == option,
                onClick = { onSelect(if (selected == option && label != "rotate") null else option) },
                label = { Text(option) }
            )
        }
    }
}

@Composable
private fun LabeledSlider(label: String, value: Float, range: ClosedFloatingPointRange<Float>, onChange: (Float) -> Unit) {
    Column {
        Text("$label: ${value.toInt()}${if (label != "quality" && value.toInt() == 0) " (off)" else ""}", style = MaterialTheme.typography.labelMedium)
        Slider(value = value, onValueChange = onChange, valueRange = range)
    }
}

// ------------------------------------------------------------------------------------------------
// Errors
// ------------------------------------------------------------------------------------------------

@Composable
fun ErrorsScreen(vm: StorageViewModel, padding: PaddingValues) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(vm.shortTokenExpiresAt) {
        while (vm.shortTokenExpiresAt > System.currentTimeMillis() - 5_000) { now = System.currentTimeMillis(); delay(1000) }
        now = System.currentTimeMillis()
    }
    ScreenColumn(padding) {
        item {
            Intro(
                "Each card triggers one error on purpose and shows exactly what the SDK threw. Blue = refused as expected. " +
                    "Every RiviumStorageException carries the server's code (e.code: type_not_allowed, file_too_large, " +
                    "executable_blocked, path_taken, token_*) and the HTTP status (e.statusCode); the cards check the code, " +
                    "never the message text. Helpers: e.isTypeNotAllowed, e.isFileTooLarge, e.isExecutableBlocked."
            )
        }
        item {
            DemoCard(vm, "Not found", "A file id that does not exist: getFile throws HttpException (statusCode 404); download throws DownloadException (statusCode 404, the server's message).",
                listOf("errNotFound", "errDownloadMissing"), actions = {
                    ActionButton(vm, "errNotFound", "getFile") { vm.errorNotFound() }
                    ActionButton(vm, "errDownloadMissing", "download", primary = false) { vm.errorDownloadMissing() }
                })
        }
        item {
            DemoCard(vm, "Type not allowed", "A text file into the images-only bucket: UploadException with code type_not_allowed (e.isTypeNotAllowed).",
                listOf("errType"), actions = { ActionButton(vm, "errType", "Upload notes.txt") { vm.errorTypeNotAllowed() } })
        }
        item {
            DemoCard(vm, "Content does not match the declared type",
                "HTML bytes declared as image/png. The server reads the content, finds HTML, and the images-only bucket refuses it " +
                    "(code type_not_allowed). This is what stops a disguised web page.",
                listOf("errMismatch"), actions = { ActionButton(vm, "errMismatch", "Upload fake.png") { vm.errorContentMismatch() } })
        }
        item {
            DemoCard(vm, "Too big", "One byte over the bucket's max file size (read with getBucket): code file_too_large (e.isFileTooLarge).",
                listOf("errTooBig"), actions = { ActionButton(vm, "errTooBig", "Upload max + 1 byte") { vm.errorTooBig() } })
        }
        item {
            DemoCard(vm, "Access denied by the bucket's rules",
                "With the \"Users' own folders\" rules, a signed-in user may write only under users/<their id>/. Writing to users/someone-else/ is refused (statusCode 403, no code: the rules say no).",
                listOf("errRules"), actions = { ActionButton(vm, "errRules", "Upload to someone else's folder") { vm.errorRules() } })
        }
        item {
            DemoCard(vm, "executable_blocked",
                "Buckets with \"Block executable files\" refuse programs, installers and scripts by name or content (400, code executable_blocked, " +
                    "e.isExecutableBlocked). First: real PNG bytes named run-me.bat into the private bucket. " +
                    "Second: a tiny fake Windows program (MZ header) named setup.exe into the public bucket (refused if that bucket blocks executables too).",
                listOf("errExe", "errExe2"), actions = {
                    ActionButton(vm, "errExe", "run-me.bat") { vm.errorExecutable() }
                    ActionButton(vm, "errExe2", "setup.exe", primary = false) { vm.errorExecutableExe() }
                })
        }
        item {
            DemoCard(vm, "token_required",
                "With no user token, file requests wait for sign-in instead of failing. Built with userTokenWaitTimeout(3 s), the wait " +
                    "ends with UserTokenRequiredException (code token_required, 401). Nothing is sent.",
                listOf("errTokenRequired"), actions = { ActionButton(vm, "errTokenRequired", "List with no token (3 s limit)") { vm.errorTokenRequired() } })
        }
        item {
            DemoCard(vm, "token_invalid",
                "A token with a bad signature and no provider to get another: UserTokenRejectedException with code token_invalid. " +
                    "(With a provider, the SDK asks it once and retries before giving up.)",
                listOf("errTokenInvalid"), actions = { ActionButton(vm, "errTokenInvalid", "Send a forged token") { vm.errorTokenInvalid() } })
        }
        item {
            val left = ((vm.shortTokenExpiresAt - now) / 1000).coerceAtLeast(0)
            DemoCard(vm, "token_expired",
                "A real token that lives 60 seconds (the shortest your server can mint), sent after it expired, with no provider: " +
                    "UserTokenRejectedException with code token_expired.",
                listOf("shortToken", "errTokenExpired"),
                extra = {
                    if (vm.shortToken != null) {
                        Text(if (left > 0) "Token expires in $left s - wait, then send it." else "Expired. Send it now.",
                            style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                    }
                },
                actions = {
                    ActionButton(vm, "shortToken", "Get a 60-second token") { vm.fetchShortToken() }
                    ActionButton(vm, "errTokenExpired", "Send it", primary = false) { vm.errorTokenExpired() }
                })
        }
        item {
            DemoCard(vm, "No network", "Turn on airplane mode, then tap: NetworkException. Turn it off again afterwards.",
                listOf("errNetwork"), actions = { ActionButton(vm, "errNetwork", "Request offline") { vm.errorNetwork() } })
        }
    }
}

// ------------------------------------------------------------------------------------------------
// Self-test
// ------------------------------------------------------------------------------------------------

@Composable
fun SelfTestScreen(vm: StorageViewModel, test: SelfTest, padding: PaddingValues) {
    val scope = rememberCoroutineScope()
    ScreenColumn(padding) {
        item {
            Intro(
                "Runs the core flow against your two test buckets and shows pass / fail per step, then deletes every file it created. " +
                    "Use it to check an SDK build (debug and release) on a phone."
            )
        }
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ActionButton(vm, "selftest", if (test.isRunning) "Running…" else "Run all checks") { if (!test.isRunning) test.start(scope) }
                Spacer(Modifier.width(12.dp))
                if (test.isRunning) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            }
        }
        if (test.summary.isNotEmpty()) {
            item {
                val failed = test.steps.any { it.status == SelfTest.Status.FAIL }
                ResultPanel(ActionResult(if (failed) ActionResult.Kind.ERROR else ActionResult.Kind.SUCCESS, listOf(test.summary)))
            }
        }
        items(test.steps) { step ->
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.Top) {
                when (step.status) {
                    SelfTest.Status.PASS -> Icon(Icons.Default.CheckCircle, "pass", tint = Color(0xFF16A34A))
                    SelfTest.Status.FAIL -> Icon(Icons.Default.Close, "fail", tint = MaterialTheme.colorScheme.error)
                    SelfTest.Status.SKIP -> Icon(Icons.Default.Info, "skipped", tint = Color(0xFFCA8A04))
                    SelfTest.Status.WARN -> Icon(Icons.Default.Warning, "warning", tint = Color(0xFFEA580C))
                    else -> CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text("${step.name}${if (step.millis > 0) "  (${step.millis} ms)" else ""}", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                    if (step.detail.isNotEmpty()) Text(step.detail, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                }
            }
            Divider()
        }
    }
}

// ------------------------------------------------------------------------------------------------
// Request log
// ------------------------------------------------------------------------------------------------

@Composable
fun LogScreen(vm: StorageViewModel, padding: PaddingValues) {
    ScreenColumn(padding) {
        item {
            Intro(
                "The SDK was given this app's own OkHttpClient with .client(...), whose interceptor writes one line per request: " +
                    "method, path, status, the X-Rivium-SDK header (android/<version>) and whether a user token was sent (never the token itself)."
            )
        }
        if (vm.requestLog.isEmpty()) item { Text("No requests yet.") }
        items(vm.requestLog) { line ->
            Text(line, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
            Divider()
        }
    }
}
