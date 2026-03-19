<p align="center">
  <a href="https://rivium.co">
    <img src="https://rivium.co/logo.png" alt="Rivium" width="120" />
  </a>
</p>

<h3 align="center">Rivium Storage Android SDK</h3>

<p align="center">
  File storage and image transformation SDK for Android with upload, download, and on-the-fly image processing.
</p>

<p align="center">
  <a href="https://central.sonatype.com/artifact/co.rivium/rivium-storage"><img src="https://img.shields.io/maven-central/v/co.rivium/rivium-storage.svg" alt="Maven Central" /></a>
  <img src="https://img.shields.io/badge/Kotlin-1.9+-7F52FF?logo=kotlin&logoColor=white" alt="Kotlin 1.9+" />
  <img src="https://img.shields.io/badge/Android-API_16+-3DDC84?logo=android&logoColor=white" alt="Android API 16+" />
  <img src="https://img.shields.io/badge/License-MIT-blue.svg" alt="MIT License" />
</p>

---

## Installation

Add to your `build.gradle.kts`:

```kotlin
dependencies {
    implementation("co.rivium:rivium-storage:0.1.0")
}
```

Add Internet permission to `AndroidManifest.xml`:

```xml
<uses-permission android:name="android.permission.INTERNET" />
```

## Quick Start

```kotlin
import co.rivium.storage.*

// Initialize
val storage = RiviumStorage(apiKey = "rv_live_xxx")

// Upload a file (in a coroutine)
val file = storage.upload(
    bucketId = "my-bucket",
    path = "images/photo.jpg",
    data = imageBytes,
    options = UploadOptions(contentType = "image/jpeg")
)

// Get a thumbnail URL
val thumbnailUrl = storage.getTransformUrl(
    fileId = file.id,
    transforms = ImageTransforms(width = 200, height = 200, fit = "cover")
)

// Download a file
val bytes = storage.download(file.id)
```

## Features

- **Upload & Download** — Upload files to buckets, download by ID or path
- **Image Transformations** — Resize, crop, blur, sharpen, rotate, format conversion (WebP, AVIF, JPEG, PNG) on the fly
- **Bucket Management** — List, get by ID or name
- **File Operations** — List with prefix filtering, pagination, get metadata, delete single or batch
- **URL Generation** — Public URLs, download URLs, and transform URLs for use with Coil/Glide
- **Security Rules** — User-scoped access with `setUserId()` for bucket policy enforcement
- **Coroutine-based** — All async operations use Kotlin coroutines
- **OkHttp + Gson** — Built on industry-standard libraries

## Documentation

For full documentation, visit [rivium.co/docs](https://rivium.co/cloud/rivium-storage/docs).
- [Rivium Cloud](https://rivium.co/cloud)
- [Rivium Console](https://console.rivium.co)

## License

MIT License — see [LICENSE](LICENSE) for details.
