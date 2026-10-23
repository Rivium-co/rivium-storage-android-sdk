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
  <img src="https://img.shields.io/badge/Android-API_21+-3DDC84?logo=android&logoColor=white" alt="Android API 21+" />
  <img src="https://img.shields.io/badge/License-MIT-blue.svg" alt="MIT License" />
</p>

---

## Installation

Add to your `build.gradle.kts`:

```kotlin
dependencies {
    implementation("co.rivium:rivium-storage:0.2.1")
}
```

Add Internet permission to `AndroidManifest.xml`:

```xml
<uses-permission android:name="android.permission.INTERNET" />
```

## Quick Start

```kotlin
import co.rivium.storage.*

// Initialize: the public key, and the signed-in user's token (see "Sign users in")
val storage = RiviumStorage.builder("rv_live_xxx")
    .userTokenProvider(RiviumAuth.shared.blockingTokenProvider)
    .build()

// Upload a file (in a coroutine; waits until a user is signed in)
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

## Show images

**Public buckets: one link, no headers.** `getPublicUrl` with image options
returns the file's public link with those options, for example
`https://storage.rivium.co/public/<bucketId>/<path>?w=600&f=webp`. It needs no
key, user token or header, so it goes straight into an `AsyncImage` (Coil), Glide or any image loader:

```kotlin
// A 600 px wide WebP, straight into Coil (Compose): no headers
val url = storage.getPublicUrl(file.id, ImageTransforms(width = 600, format = "webp"))
AsyncImage(model = url, contentDescription = null)
// Views + Glide: Glide.with(imageView).load(url).into(imageView)

// A public link you stored earlier: the same URL, no request
val thumb = RiviumStorage.publicImageUrl(
    savedLink,
    ImageTransforms(width = 200, height = 200, fit = "cover", format = "webp"),
)
```

- **Cached.** The same options always give the same URL (the SDK writes them in
  a fixed order), and the CDN and Rivium Storage keep each size / format of an
  image once: later views come from the cache and don't count as a new
  transform. Replacing the file's content updates the image (copies already in
  a browser or CDN cache can show the old one for up to a day).
- **Use a few fixed sizes.** One image keeps at most 50 variants (different
  option combinations). A new combination beyond that is refused with
  `too_many_variants` (429); the existing ones keep working. Pick a few sizes
  (for example 200, 600 and 1200 wide) instead of the exact size of every view.
- **Plan.** On a plan without image transforms (Free) the image URL answers
  `plan_feature_locked` (403). The plain public link, without options, always
  works.
- **Busy.** When image processing is busy the URL answers `transform_busy`
  (503, with a `Retry-After` header in seconds). Coil and Glide show their error image: load it again (for example on a retry tap). When you fetch the URL yourself, retry after `Retry-After` seconds.
- **Already have the public link?** `publicImageUrl` builds the same URL from it
  without a request: store the public link once and make sizes as you need
  them. It checks the options the same way.
- **Private buckets** have no public link (`getPublicUrl` fails with 403).
  Load the image with `getTransformUrl` and your headers (unchanged, see below), `download` it, or show it from a signed link your server makes.

## Upload progress

Pass `onProgress` to show a progress bar. It runs on the main thread, about
every 50 ms or 1%, and the last call has `bytesSent == totalBytes`:

```kotlin
val file = storage.upload(
    bucketId = "my-bucket",
    path = "videos/clip.mp4",
    data = videoBytes,
    onProgress = { progress ->
        progressBar.progress = (progress.fraction * 100).toInt()
    }
)
```

`totalBytes` is the size of the whole upload request: your file plus a few
hundred bytes of form fields.

## File types

When you pass no `contentType`, the SDK sends the type the Rivium Console uses
for the file's extension: every format you can choose in Bucket › Settings ›
File types (`photo.HEIC` is sent as `image/heic`, `notes.md` as
`text/markdown`, `voice.m4a` as `audio/x-m4a`...), so any format the bucket
allows uploads as is. For an extension the Console doesn't list, it sends
`application/octet-stream` and the server works out the type from the name
and the content. A `contentType` you pass is always sent as it is.

## Handling errors

Every `RiviumStorageException` the server caused carries its HTTP
`statusCode` and, when the server named the reason, its `code`
(`StorageErrorCode`):

```kotlin
try {
    storage.upload("photos", "clips/a.mov", bytes)
} catch (e: RiviumStorageException) {
    when {
        e.isTypeNotAllowed -> showError("This bucket doesn't take this file type")
        e.isFileTooLarge -> showError("The file is too big")
        e.isExecutableBlocked -> showError("Programs and scripts can't be uploaded")
        e.code == StorageErrorCode.PATH_TAKEN -> showError("A file with this name already exists")
        e.code == StorageErrorCode.ACCESS_DENIED -> showError("You can't write here")  // the bucket's rules said no
        else -> showError(e.message ?: "Upload failed")
    }
}
```

`code` is null when the server gave no reason (a `404`, a `429`) and `statusCode` is null when no answer arrived
(`NetworkException`).

## What the answers contain

A `StorageFile` has `id`, `bucketId`, `path`, `fileName`, `mimeType`, `size`, `checksum`, `metadata`,
`uploadedBy`, `uploadedAt`, `createdAt` and `updatedAt` (`checksum`, `metadata`, `uploadedBy` and `uploadedAt` are
`null` when empty). A `Bucket` has `id`, `name`, `description`, `visibility`, `allowedMimeTypes` (`null` = every
type), `maxFileSize`, `blockExecutables`, `createdAt` and `updatedAt`. Dates are `java.util.Date`. `listFiles` answers
`files` and `nextCursor`, with `nextCursor` `null` on the last page.

## Paths, links and image transforms

- **Paths.** Pass paths as you write them: each part is percent-encoded for you,
  so spaces, `?`, `#`, `%` and other scripts are fine when you read or delete by
  path. Leading, trailing and repeated `/` are dropped, as the server stores
  paths (`/a//b/` is `a/b`). A `.` or `..` part (also written `%2e`) is refused
  with `invalid_path` before anything is sent. An empty bucket, file id or path
  fails at once with `bucket_required`, `file_id_required` or `path_required`.
- **New files.** An upload path can't contain `?`, `#`, `%`, text-direction
  characters or a `.` / `..` part: the SDK refuses it with `invalid_path` (and
  the server's message) before sending anything.
- **Public link.** `storage.getPublicUrl(fileId)` returns
  `https://storage.rivium.co/public/<bucketId>/<path>` for a file in a
  **public** bucket. Anyone can open it without a key or token, so it goes
  straight into Coil, Glide or a browser. A file in a private bucket has no public link
  (`403`): use `download`, or a signed link from your server.
- **Public image links.** With image options, `getPublicUrl` returns a
  resized / converted image URL that needs no headers, and `publicImageUrl`
  builds it from a stored public link: see [Show images](#show-images).
- **API addresses.** `getUrl`, `getDownloadUrl` and `getTransformUrl` return
  `/api/v1` addresses. They need the `x-api-key` header (and the user token
  where your rules ask for one), so load them with an HTTP client that sends
  headers, never as a plain image URL.
- **Image transforms.** `format`: `jpeg` (default), `png`, `webp` or `avif`;
  `fit`: `cover`, `contain`, `fill`, `inside` or `outside`; `width` / `height`:
  1 or more (above 4096 sent as 4096); `quality`: 1-100; `blur`: 0-20;
  `sharpen`: 0-10; `rotate`: degrees. `getTransformUrl` throws `invalid_transform` for anything else, before building the URL. Images over 25 MB can't be
  transformed (`source_too_large`); see [Handling transform errors](#handling-transform-errors) for why else a transform can fail.

```kotlin
val link = storage.getPublicUrl(file.id) // public bucket only
// "https://storage.rivium.co/public/<bucketId>/photos/my%20cat.png"

val thumb = storage.getTransformUrl(
    file.id,
    ImageTransforms(width = 200, height = 200, fit = "cover", format = "webp")
) // load with the x-api-key header
```

### Handling transform errors

The SDK builds the transform URL; your app fetches it. When the image can't be
made, the answer is JSON instead of an image, with `statusCode`, `code` and a
`message` you can show to your users. Read `code` to decide what to do:

```kotlin
val request = Request.Builder().url(thumb)
    .header("x-api-key", apiKey) // + x-user-token when signed in
    .build()
okHttp.newCall(request).execute().use { response ->
    if (!response.isSuccessful) {
        val body = runCatching { JSONObject(response.body?.string().orEmpty()) }.getOrNull()
        when (body?.optString("code")) {
            StorageErrorCode.NOT_AN_IMAGE -> showError("Pick an image file.")
            StorageErrorCode.IMAGE_DAMAGED -> showError("Upload it again.")
            else -> showError(body?.optString("message")?.ifEmpty { null } ?: "HTTP ${response.code}")
        }
    }
}
```

| Status | `code` | What the message says |
| --- | --- | --- |
| 415 | `image_format_not_supported` | This image format can't be resized: BMP. Supported: JPEG, PNG, WebP, GIF, AVIF, HEIC, TIFF, SVG. |
| 400 | `not_an_image` | This file isn't an image (application/pdf), so it can't be resized. |
| 422 | `image_damaged` | The image is damaged or incomplete (it may not have finished uploading). |
| 413 | `image_too_large` | This image is 12000×9000 (108 MP); images up to 50 MP can be resized. |
| 413 | `source_too_large` | The image is over 25 MB. |
| 400 | `invalid_transform` | An option isn't allowed (the message names it). |
| 503 | `transform_busy` | Image processing is busy. Try again in a moment. (Retry after the `Retry-After` header's seconds.) |
| 429 | `too_many_variants` | Public image URLs: this image already has 50 sizes / formats. Use one of the sizes you already use. |
| 403 | `plan_feature_locked` | Image transforms aren't included in this project's plan. Upgrade to use them. |

## Sign users in (one token for every Rivium product)

Signed user tokens are required for file requests; the SDK waits for a token
until your app signs a user in (`isAwaitingUserToken` / `onAwaitingUserToken`,
optional `userTokenWaitTimeout`). Bucket rules that use `{userId}` match the
user in that token. It is the same token Rivium Sync, Chat, Push and A/B
Testing accept.

### Sign users in with Rivium Auth

Rivium Auth's token is this token, so no backend is needed. Pass its provider
(it returns `null` while no one is signed in) and tell Storage when the user
changes:

```kotlin
val auth = RiviumAuth.shared
val storage = RiviumStorage.builder("rv_live_xxx")
    // Called off the main thread, so the blocking provider is the right one.
    .userTokenProvider(auth.blockingTokenProvider)
    .build()

// After sign-in or sign-out, from Auth's state listener:
auth.addAuthStateListener { storage.refreshUserToken() }
```

### Or mint tokens on your own server

Your backend mints the token with `POST https://auth.rivium.co/users/token`
and your project's server secret, which must stay on your server and never
ship in an app:

```kotlin
val storage = RiviumStorage.builder("rv_live_xxx")
    // Called on a background thread. Return null when no one is signed in.
    .userTokenProvider { myBackend.fetchRiviumToken() }
    .build()

// When the user signs in or out:
storage.refreshUserToken()          // same as storage.userTokens.refresh()
```

If you would rather fetch the token yourself, call
`storage.setUserToken(token)` (or `storage.userTokens.set(token)`) whenever
you refresh it; `null` signs out.

### Waiting for a user

The SDK sends the token as the `x-user-token` header, asks the provider before
the first request, and asks again (retrying the request once, uploads
included) when the token has expired or is refused.

With no token (no one signed in), file calls (upload, list, get, download,
delete) are not sent and do not fail: they wait. They go on by themselves, in
order, after `setUserToken(token)` or, with a provider, `refreshUserToken()`.
Bucket reads (`listBuckets`, `getBucket`, `getBucketByName`) and
`getPublicUrl` need no user and never wait.

```kotlin
val storage = RiviumStorage.builder("rv_live_xxx")
    .userTokenProvider(auth.blockingTokenProvider)
    .onAwaitingUserToken { showSignIn() }               // once per wait, background thread
    .userTokenWaitTimeout(30, TimeUnit.SECONDS)          // optional; default: wait until sign-in
    .build()

storage.isAwaitingUserToken // true while file calls wait for a user
```

With `userTokenWaitTimeout`, a call that waited that long throws
`RiviumStorageException.UserTokenRequiredException` (code `token_required`,
status 401). Cancelling the coroutine also ends the wait.

URL helpers (`getUrl`, `getTransformUrl`, `getDownloadUrl`) return API
addresses that need your key; see [Paths, links and image transforms](#paths-links-and-image-transforms).

## Features

- **Upload & Download** — Upload files to buckets (with progress), download by ID or path
- **Image Transformations** — Resize, crop, blur, sharpen, rotate, format conversion (WebP, AVIF, JPEG, PNG) on the fly
- **Bucket Management** — List, get by ID or name
- **File Operations** — List with prefix filtering, pagination, get metadata, delete single or batch
- **Links** — Public links for public buckets (`getPublicUrl`), plus download and transform addresses
- **Security Rules** — User-scoped access with a signed Rivium user token (`userTokenProvider`) for bucket policy enforcement
- **Coroutine-based** — All async operations use Kotlin coroutines
- **OkHttp + Gson** — Built on industry-standard libraries

## Documentation

For full documentation, visit [rivium.co/docs](https://rivium.co/cloud/rivium-storage/docs/android).
- [Rivium Cloud](https://rivium.co/cloud)
- [Rivium Console](https://console.rivium.co)

## License

MIT License — see [LICENSE](LICENSE) for details.
