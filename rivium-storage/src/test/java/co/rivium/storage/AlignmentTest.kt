package co.rivium.storage

import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/**
 * The rules every Rivium Storage SDK applies the same way (Android, iOS, Flutter, React Native, Web, Node): paths in
 * URLs, upload paths, empty arguments, the answers' fields, the error codes and the transform options.
 */
class AlignmentTest {

    private lateinit var server: MockWebServer

    private val date = "2026-10-23T09:15:02.123Z"

    /** A file answer with exactly the §11 fields. */
    private val fileJson = """{"id":"f1","bucketId":"b1","path":"docs/a.txt","fileName":"a.txt",
        |"mimeType":"text/plain","size":3000000000,"checksum":null,"metadata":null,"uploadedBy":null,
        |"uploadedAt":null,"createdAt":"$date","updatedAt":"$date"}""".trimMargin()

    private val bucketJson = """{"id":"b1","name":"docs","description":null,"visibility":"public",
        |"allowedMimeTypes":null,"maxFileSize":2147483647,"blockExecutables":true,
        |"createdAt":"$date","updatedAt":"$date"}""".trimMargin()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun storage() = RiviumStorage(apiKey = "rv_live_x", userToken = "tok", client = OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
        val original = chain.request()
        val target = original.url.newBuilder().scheme("http").host(server.hostName).port(server.port).build()
        chain.proceed(original.newBuilder().url(target).build())
    }).build())

    private suspend fun refusal(call: suspend () -> Unit): RiviumStorageException {
        try {
            call()
        } catch (e: RiviumStorageException) {
            return e
        }
        fail("no exception")
        throw IllegalStateException()
    }

    private suspend fun assertRefused(code: String, message: String? = null, call: suspend () -> Unit) {
        val e = refusal(call)
        assertEquals(e.message, code, e.code)
        assertEquals(400, e.statusCode)
        if (message != null) assertTrue(e.message, e.message!!.endsWith(message))
    }

    @Test
    fun emptySegmentsAreDroppedFromPathsInUrls() = runBlocking {
        val storage = storage()
        for (path in listOf("/a", "a/", "a//b", "//x///y//")) {
            server.enqueue(MockResponse().setBody(fileJson))
            storage.getFileByPath("b1", path)
        }
        server.enqueue(MockResponse().setBody("{}"))
        storage.deleteByPath("b1", "/a//b/")
        assertEquals("/api/v1/buckets/b1/files/a", server.takeRequest().path)
        assertEquals("/api/v1/buckets/b1/files/a", server.takeRequest().path)
        assertEquals("/api/v1/buckets/b1/files/a/b", server.takeRequest().path)
        assertEquals("/api/v1/buckets/b1/files/x/y", server.takeRequest().path)
        assertEquals("/api/v1/buckets/b1/files/a/b", server.takeRequest().path)
    }

    @Test
    fun dotSegmentsAreRefusedAlsoPercentEncoded() = runBlocking {
        val storage = storage()
        for (path in listOf(".", "..", "a/./b", "a/../b", "../a", "%2e", "%2E%2e", "x/%2e%2e/y", "a/%252e%252e/b")) {
            assertRefused(StorageErrorCode.INVALID_PATH) { storage.getFileByPath("b1", path) }
            assertRefused(StorageErrorCode.INVALID_PATH) { storage.deleteByPath("b1", path) }
        }
        assertEquals(0, server.requestCount)
    }

    @Test
    fun questionHashPercentSpacesAndScriptsAreEncodedPerSegment() = runBlocking {
        val storage = storage()
        server.enqueue(MockResponse().setBody(fileJson))
        storage.getFileByPath("b1", "dir one/a?b#c%d.txt")
        assertEquals("/api/v1/buckets/b1/files/dir%20one/a%3Fb%23c%25d.txt", server.takeRequest().path)
        server.enqueue(MockResponse().setBody(fileJson))
        storage.getFileByPath("b1", "фото/日本.png")
        assertEquals(
            "/api/v1/buckets/b1/files/%D1%84%D0%BE%D1%82%D0%BE/%E6%97%A5%E6%9C%AC.png",
            server.takeRequest().path,
        )
    }

    @Test
    fun idsAreOneSegmentAndDotIdsAreRefused() = runBlocking {
        val storage = storage()
        server.enqueue(MockResponse().setBody(fileJson))
        storage.getFile("a/b")
        assertEquals("/api/v1/files/a%2Fb", server.takeRequest().path)
        for (id in listOf(".", "..", "%2e%2e")) {
            assertRefused(StorageErrorCode.INVALID_PATH) { storage.getFile(id) }
            assertRefused(StorageErrorCode.INVALID_PATH) { storage.getBucket(id) }
        }
        assertEquals(1, server.requestCount)
    }

    @Test
    fun emptyArgumentsAreRefusedBeforeAnyRequest() = runBlocking {
        val storage = storage()
        for (blank in listOf("", "  ", "/", " / ")) {
            assertRefused(StorageErrorCode.BUCKET_REQUIRED) { storage.getBucket(blank) }
            assertRefused(StorageErrorCode.BUCKET_REQUIRED) { storage.getBucketByName(blank) }
            assertRefused(StorageErrorCode.BUCKET_REQUIRED) { storage.listFiles(blank) }
            assertRefused(StorageErrorCode.BUCKET_REQUIRED) { storage.upload(blank, "a.txt", byteArrayOf(1)) }
            assertRefused(StorageErrorCode.BUCKET_REQUIRED) { storage.getFileByPath(blank, "a.txt") }
            assertRefused(StorageErrorCode.BUCKET_REQUIRED) { storage.deleteByPath(blank, "a.txt") }
            assertRefused(StorageErrorCode.FILE_ID_REQUIRED) { storage.getFile(blank) }
            assertRefused(StorageErrorCode.FILE_ID_REQUIRED) { storage.download(blank) }
            assertRefused(StorageErrorCode.FILE_ID_REQUIRED) { storage.delete(blank) }
            assertRefused(StorageErrorCode.FILE_ID_REQUIRED) { storage.getPublicUrl(blank) }
            assertRefused(StorageErrorCode.FILE_ID_REQUIRED) { storage.getUrl(blank) }
            assertRefused(StorageErrorCode.FILE_ID_REQUIRED) { storage.getDownloadUrl(blank) }
            assertRefused(StorageErrorCode.FILE_ID_REQUIRED) { storage.getTransformUrl(blank) }
            assertRefused(StorageErrorCode.PATH_REQUIRED) { storage.getFileByPath("b1", blank) }
            assertRefused(StorageErrorCode.PATH_REQUIRED) { storage.deleteByPath("b1", blank) }
            assertRefused(StorageErrorCode.PATH_REQUIRED) { storage.upload("b1", blank, byteArrayOf(1)) }
        }
        assertEquals(0, server.requestCount)
    }

    @Test
    fun uploadPathsAreCheckedWithTheServerMessages() = runBlocking {
        val storage = storage()
        val cases = listOf(
            "a?b.txt" to "Path can't contain '?', '#' or '%'",
            "a#b.txt" to "Path can't contain '?', '#' or '%'",
            "100%.txt" to "Path can't contain '?', '#' or '%'",
            "x/%2e%2e/y.txt" to "Path can't contain '?', '#' or '%'",
            "a\u202Eb.txt" to "Path can't contain text-direction characters",
            "a\u200Fb.txt" to "Path can't contain text-direction characters",
            "a/../b.txt" to "File paths can't contain '.' or '..' segments",
            "./b.txt" to "File paths can't contain '.' or '..' segments",
        )
        for ((path, message) in cases) {
            assertRefused(StorageErrorCode.INVALID_PATH, message) { storage.upload("b1", path, byteArrayOf(1)) }
        }
        assertEquals(0, server.requestCount)
    }

    @Test
    fun uploadPathsAreNormalisedLikeTheServer() = runBlocking {
        server.enqueue(MockResponse().setBody(fileJson))
        storage().upload("b1", "/photos//2026/a b.png/", byteArrayOf(1))
        val body = server.takeRequest().body.readUtf8()
        assertTrue(body, body.contains("name=\"path\"\r\nContent-Length: 19\r\n\r\nphotos/2026/a b.png\r\n"))
        assertTrue(body, body.contains("filename=\"a b.png\""))
    }

    @Test
    fun answersWithOnlyTheWhitelistedFieldsDecode() = runBlocking {
        val storage = storage()
        server.enqueue(MockResponse().setBody(fileJson))
        val file = storage.getFile("f1")
        assertEquals(3_000_000_000L, file.size)
        assertNull(file.checksum)
        assertNull(file.metadata)
        assertNull(file.uploadedBy)
        assertNull(file.uploadedAt)
        assertEquals(1792746902123L, file.createdAt.time)

        server.enqueue(MockResponse().setBody(bucketJson))
        val bucket = storage.getBucket("b1")
        assertEquals(2147483647, bucket.maxFileSize)
        assertEquals(true, bucket.blockExecutables)
        assertNull(bucket.description)
        assertNull(bucket.allowedMimeTypes)

        server.enqueue(MockResponse().setBody("""{"files":[$fileJson],"nextCursor":null}"""))
        val list = storage.listFiles("b1")
        assertNull(list.nextCursor)
        assertEquals("f1", list.files.single().id)
    }

    @Test
    fun getPublicUrlReturnsThePublicLink() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"url":"https://storage.rivium.co/public/b1/docs/a%20b.txt"}"""))
        assertEquals("https://storage.rivium.co/public/b1/docs/a%20b.txt", storage().getPublicUrl("f1"))
        val request = server.takeRequest()
        assertEquals("GET", request.method)
        assertEquals("/api/v1/files/f1/url", request.path)
    }

    @Test
    fun errorCodesAreTheSameListInEverySdk() {
        val codes = StorageErrorCode::class.java.declaredFields
            .filter { java.lang.reflect.Modifier.isStatic(it.modifiers) && it.type == String::class.java }
            .map { it.get(null) as String }
            .sorted()
        assertEquals(
            listOf(
                "access_denied", "bucket_required", "executable_blocked", "file_id_required", "file_required",
                "file_too_large", "image_damaged", "image_format_not_supported", "image_too_large",
                "invalid_ids", "invalid_path", "invalid_rules", "invalid_transform", "ip_not_allowed", "not_an_image",
                "path_required", "path_taken", "plan_feature_locked", "server_secret_required", "source_too_large",
                "token_expired", "token_invalid", "token_required", "too_many_variants", "transform_busy",
                "type_not_allowed", "upload_expired", "upload_missing", "upload_rejected",
            ),
            codes,
        )
    }

    @Test
    fun transformOptionsAreChecked() = runBlocking {
        val storage = storage()
        val url = storage.getTransformUrl(
            "f1",
            ImageTransforms(width = 5000, height = 10, fit = "Cover", format = "jpg", quality = 100, blur = 20, sharpen = 10, rotate = 90),
        )
        assertEquals("https://storage.rivium.co/api/v1/transform/f1?w=4096&h=10&fit=cover&f=jpeg&q=100&blur=20&sharpen=10&rotate=90", url)
        assertEquals(
            "https://storage.rivium.co/api/v1/transform/f1?fit=outside&f=avif&q=1&blur=0&sharpen=0",
            storage.getTransformUrl("f1", ImageTransforms(format = "avif", fit = "outside", quality = 1, blur = 0, sharpen = 0)),
        )
        assertEquals("https://storage.rivium.co/api/v1/transform/f1", storage.getTransformUrl("f1"))
        val bad = listOf(
            ImageTransforms(format = "gif"), ImageTransforms(format = "tiff"), ImageTransforms(fit = "stretch"),
            ImageTransforms(width = 0), ImageTransforms(height = -1), ImageTransforms(quality = 0),
            ImageTransforms(quality = 101), ImageTransforms(blur = 21), ImageTransforms(blur = -1),
            ImageTransforms(sharpen = 11),
        )
        for (t in bad) assertRefused(StorageErrorCode.INVALID_TRANSFORM) { storage.getTransformUrl("f1", t) }
    }
}
