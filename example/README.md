# RiviumStorage Android Example

This example demonstrates all capabilities of the RiviumStorage Android SDK using Jetpack Compose.

## Features Demonstrated

### Bucket Operations
- List all buckets
- Get bucket by ID
- Get bucket by name

### File Operations
- Upload files with metadata
- Upload images
- List files with pagination
- Get file by ID
- Download file content
- Delete single file
- Delete multiple files (bulk delete)

### URL Generation
- Public URL for files
- Download URL
- Transform URL for images

### Image Transformations
- Resize (width, height)
- Fit modes (cover, contain, fill)
- Format conversion (webp, avif, jpeg, png)
- Quality adjustment
- Blur effect
- Sharpen effect
- Rotation

### Error Handling
- Network errors
- HTTP errors
- Upload/download errors
- Response parsing errors

## Running the Example

### Prerequisites
- Android Studio Hedgehog (2023.1.1) or later
- Android SDK 21+
- Kotlin 1.9+

### Setup

1. Open Android Studio

2. Select "Open" and navigate to:
   ```
   /path/to/rivium_storage_project/examples/android
   ```

3. Wait for Gradle sync to complete

4. Update the API key in `MainActivity.kt`:
   ```kotlin
   private const val API_KEY = "rv_live_your_api_key_here"
   private const val BUCKET_ID = "your-bucket-id"
   ```

5. Run on emulator or device (API 21+)

## Project Structure

```
examples/android/
├── app/
│   ├── src/main/
│   │   ├── java/co/rivium/storage/example/
│   │   │   └── MainActivity.kt      # Main example UI
│   │   ├── res/
│   │   │   ├── values/
│   │   │   │   ├── strings.xml
│   │   │   │   ├── colors.xml
│   │   │   │   └── themes.xml
│   │   │   └── layout/
│   │   └── AndroidManifest.xml
│   └── build.gradle.kts
├── settings.gradle.kts               # Links to SDK module
├── build.gradle.kts
└── README.md
```

## Code Snippets

### Initialize SDK

```kotlin
import co.rivium.storage.*

val storage = RiviumStorage(apiKey = "rv_live_xxx")
```

### Upload File

```kotlin
val file = storage.upload(
    bucketId = "my-bucket",
    path = "images/photo.jpg",
    data = imageBytes,
    options = UploadOptions(
        contentType = "image/jpeg",
        metadata = mapOf("userId" to "123")
    )
)
println("Uploaded: ${file.id}")
```

### List Files with Pagination

```kotlin
var cursor: String? = null
do {
    val result = storage.listFiles(
        bucketId = "my-bucket",
        options = ListFilesOptions(
            prefix = "images/",
            limit = 50,
            cursor = cursor
        )
    )
    result.files.forEach { file ->
        println("${file.path}: ${file.size} bytes")
    }
    cursor = result.nextCursor
} while (cursor != null)
```

### Generate Transform URL

```kotlin
val thumbnailUrl = storage.getTransformUrl(
    fileId = file.id,
    transforms = ImageTransforms(
        width = 200,
        height = 200,
        fit = "cover",
        format = "webp",
        quality = 80
    )
)
```

### Error Handling

```kotlin
try {
    val file = storage.getFile(fileId = "file-id")
} catch (e: RiviumStorageException) {
    when (e) {
        is RiviumStorageException.NetworkException -> {
            println("Network error: ${e.message}")
        }
        is RiviumStorageException.HttpException -> {
            println("HTTP ${e.statusCode}: ${e.message}")
        }
        is RiviumStorageException.UploadException -> {
            println("Upload failed: ${e.message}")
        }
        is RiviumStorageException.DownloadException -> {
            println("Download failed: ${e.message}")
        }
        is RiviumStorageException.InvalidResponseException -> {
            println("Invalid server response")
        }
        is RiviumStorageException.EncodingException -> {
            println("Encoding error: ${e.message}")
        }
    }
}
```

## Jetpack Compose Integration

### Image with Transforms

```kotlin
@Composable
fun TransformedImage(fileId: String) {
    val storage = remember { RiviumStorage(apiKey = "rv_live_xxx") }

    val thumbnailUrl = storage.getTransformUrl(
        fileId = fileId,
        transforms = ImageTransforms(
            width = 200,
            height = 200,
            fit = "cover",
            format = "webp"
        )
    )

    AsyncImage(
        model = thumbnailUrl,
        contentDescription = "Image",
        modifier = Modifier.size(200.dp)
    )
}
```

### Upload with Progress

```kotlin
@Composable
fun UploadButton() {
    val scope = rememberCoroutineScope()
    val storage = remember { RiviumStorage(apiKey = "rv_live_xxx") }
    var uploading by remember { mutableStateOf(false) }
    var uploadedFile by remember { mutableStateOf<StorageFile?>(null) }

    Column {
        Button(
            onClick = {
                scope.launch {
                    uploading = true
                    try {
                        val file = storage.upload(
                            bucketId = "my-bucket",
                            path = "uploads/${UUID.randomUUID()}.jpg",
                            data = imageBytes,
                            options = UploadOptions(contentType = "image/jpeg")
                        )
                        uploadedFile = file
                    } catch (e: RiviumStorageException) {
                        // Handle error
                    } finally {
                        uploading = false
                    }
                }
            },
            enabled = !uploading
        ) {
            if (uploading) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp))
            } else {
                Text("Upload")
            }
        }

        uploadedFile?.let { file ->
            Text("Uploaded: ${file.fileName}")
        }
    }
}
```

### File List with Delete

```kotlin
@Composable
fun FileList(bucketId: String) {
    val scope = rememberCoroutineScope()
    val storage = remember { RiviumStorage(apiKey = "rv_live_xxx") }
    var files by remember { mutableStateOf<List<StorageFile>>(emptyList()) }

    LaunchedEffect(bucketId) {
        val result = storage.listFiles(bucketId)
        files = result.files
    }

    LazyColumn {
        items(files) { file ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(file.fileName)
                IconButton(onClick = {
                    scope.launch {
                        storage.delete(file.id)
                        files = files.filter { it.id != file.id }
                    }
                }) {
                    Icon(Icons.Default.Delete, "Delete")
                }
            }
        }
    }
}
```

## Dependencies

The example uses:
- **RiviumStorage SDK** - File storage SDK
- **Jetpack Compose** - Modern UI toolkit
- **Coil** - Image loading library
- **Material 3** - Material Design components

## License

MIT License
