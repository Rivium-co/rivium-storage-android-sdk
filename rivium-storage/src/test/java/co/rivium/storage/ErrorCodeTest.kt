package co.rivium.storage

import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/** The server's `code` and HTTP status reach the app on every request path. */
class ErrorCodeTest {

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    // A server token_required makes a call wait for a new token; the short limit turns that wait into the error.
    private fun storage() = RiviumStorage(apiKey = "rv_live_x", userToken = "tok", userTokenWaitTimeoutMillis = 300, client = OkHttpClient.Builder()
        .addInterceptor(Interceptor { chain ->
            val original = chain.request()
            val target = original.url.newBuilder().scheme("http").host(server.hostName).port(server.port).build()
            chain.proceed(original.newBuilder().url(target).build())
        }).build())

    private fun refuse(status: Int, code: String?, message: String) {
        val codePart = code?.let { ""","code":"$it"""" } ?: ""
        server.enqueue(
            MockResponse().setResponseCode(status)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"statusCode":$status,"error":"Bad Request","message":"$message"$codePart}""")
        )
    }

    private inline fun <reified E : RiviumStorageException> expect(block: () -> Unit): E {
        try {
            block()
        } catch (e: RiviumStorageException) {
            assertTrue("expected ${E::class.simpleName}, got ${e::class.simpleName}: ${e.message}", e is E)
            return e as E
        }
        fail("no exception")
        throw AssertionError()
    }

    private fun upload() = runBlocking { storage().upload("b1", "a/file.bin", byteArrayOf(1, 2, 3)) }

    @Test
    fun uploadTypeNotAllowed() {
        refuse(400, "type_not_allowed", "File type application/x-msdownload is not allowed in this bucket")
        val e = expect<RiviumStorageException.UploadException> { upload() }
        assertEquals("type_not_allowed", e.code)
        assertEquals(400, e.statusCode)
        assertTrue(e.isTypeNotAllowed)
        assertFalse(e.isFileTooLarge)
        assertFalse(e.isExecutableBlocked)
        assertEquals("Upload error: File type application/x-msdownload is not allowed in this bucket", e.message)
    }

    @Test
    fun uploadFileTooLarge() {
        refuse(400, "file_too_large", "File is too large")
        val e = expect<RiviumStorageException.UploadException> { upload() }
        assertEquals(StorageErrorCode.FILE_TOO_LARGE, e.code)
        assertTrue(e.isFileTooLarge)
    }

    @Test
    fun uploadExecutableBlocked() {
        refuse(400, "executable_blocked", "Executable files are not allowed")
        val e = expect<RiviumStorageException.UploadException> { upload() }
        assertEquals(StorageErrorCode.EXECUTABLE_BLOCKED, e.code)
        assertTrue(e.isExecutableBlocked)
    }

    @Test
    fun uploadPathTaken() {
        refuse(409, "path_taken", "A file already exists at this path")
        val e = expect<RiviumStorageException.UploadException> { upload() }
        assertEquals(StorageErrorCode.PATH_TAKEN, e.code)
        assertEquals(409, e.statusCode)
    }

    @Test
    fun uploadDeniedByRulesHasStatusButNoCode() {
        refuse(403, null, "No matching rules - denied by default")
        val e = expect<RiviumStorageException.UploadException> { upload() }
        assertNull(e.code)
        assertEquals(403, e.statusCode)
        assertEquals("Upload error: No matching rules - denied by default", e.message)
    }

    @Test
    fun uploadTokenRequiredWaitsThenTimesOutWithItsOwnType() {
        refuse(401, "token_required", "token_required")
        val e = expect<RiviumStorageException.UserTokenRequiredException> { upload() }
        assertEquals("token_required", e.code)
        assertEquals(401, e.statusCode)
        assertEquals(1, server.requestCount) // not re-sent until the token changes
    }

    @Test
    fun uploadTokenExpiredWithoutProvider() {
        refuse(401, "token_expired", "expired")
        val e = expect<RiviumStorageException.UserTokenRejectedException> { upload() }
        assertEquals(StorageErrorCode.TOKEN_EXPIRED, e.code)
        assertEquals(401, e.statusCode)
    }

    @Test
    fun messageAsListStillGivesTheCode() {
        server.enqueue(MockResponse().setResponseCode(400)
            .setBody("""{"statusCode":400,"message":["path must be a string","path is too long"],"code":"type_not_allowed"}"""))
        val e = expect<RiviumStorageException.UploadException> { upload() }
        assertEquals("type_not_allowed", e.code)
        assertEquals("Upload error: path must be a string, path is too long", e.message)
    }

    @Test
    fun nonJsonBodyGivesStatusOnly() {
        server.enqueue(MockResponse().setResponseCode(502).setBody("<html>Bad gateway</html>"))
        val e = expect<RiviumStorageException.UploadException> { upload() }
        assertNull(e.code)
        assertEquals(502, e.statusCode)
        assertEquals("Upload error: Upload failed: 502", e.message)
    }

    @Test
    fun downloadKeepsServerMessageAndCode() {
        refuse(403, null, "Access denied by bucket rules")
        val e = expect<RiviumStorageException.DownloadException> { runBlocking { storage().download("f1") } }
        assertEquals(403, e.statusCode)
        assertNull(e.code)
        assertEquals("Download error: Access denied by bucket rules", e.message)

        server.enqueue(MockResponse().setResponseCode(404)
            .setBody("""{"statusCode":404,"message":"File not found","code":"file_not_found"}"""))
        val e2 = expect<RiviumStorageException.DownloadException> { runBlocking { storage().download("f1") } }
        assertEquals("file_not_found", e2.code)
        assertEquals(404, e2.statusCode)
    }

    @Test
    fun downloadWithoutBodySaysTheStatus() {
        server.enqueue(MockResponse().setResponseCode(500))
        val e = expect<RiviumStorageException.DownloadException> { runBlocking { storage().download("f1") } }
        assertEquals("Download error: Download failed: 500", e.message)
        assertEquals(500, e.statusCode)
    }

    @Test
    fun jsonRequestsCarryCodeAndStatus() {
        refuse(409, "path_taken", "taken")
        val del = expect<RiviumStorageException.HttpException> { runBlocking { storage().deleteByPath("b1", "a.txt") } }
        assertEquals("path_taken", del.code)
        assertEquals(409, del.statusCode)

        refuse(403, null, "No matching rules - denied by default")
        val list = expect<RiviumStorageException.HttpException> { runBlocking { storage().listFiles("b1") } }
        assertNull(list.code)
        assertEquals(403, list.statusCode)

        refuse(404, "not_found", "File not found")
        val get = expect<RiviumStorageException.HttpException> { runBlocking { storage().getFile("f1") } }
        assertEquals("not_found", get.code)
    }

    /**
     * The SDK only builds transform URLs (the app fetches them); these are the
     * bodies the transform route answers with. The SDK's own requests read them
     * the same way, so code, status and message reach the app.
     */
    @Test
    fun transformRefusalsCarryCodeStatusAndMessage() {
        val refusals = listOf(
            Triple(415, StorageErrorCode.IMAGE_FORMAT_NOT_SUPPORTED,
                "This image format can't be resized: BMP. Supported: JPEG, PNG, WebP, GIF, AVIF, HEIC, TIFF, SVG."),
            Triple(400, StorageErrorCode.NOT_AN_IMAGE, "This file isn't an image (application/pdf), so it can't be resized."),
            Triple(422, StorageErrorCode.IMAGE_DAMAGED, "The image is damaged or incomplete (it may not have finished uploading)."),
            Triple(413, StorageErrorCode.IMAGE_TOO_LARGE, "This image is 12000×9000 (108 MP); images up to 50 MP can be resized."),
        )
        for ((status, code, message) in refusals) {
            refuse(status, code, message)
            val d = expect<RiviumStorageException.DownloadException> { runBlocking { storage().download("f1") } }
            assertEquals(code, d.code)
            assertEquals(status, d.statusCode)
            assertEquals("Download error: $message", d.message)

            refuse(status, code, message)
            val g = expect<RiviumStorageException.HttpException> { runBlocking { storage().getFile("f1") } }
            assertEquals(code, g.code)
            assertEquals(status, g.statusCode)
            assertEquals("HTTP $status: $message", g.message)
        }
    }

    @Test
    fun existingConstructorsStillWork() {
        val http: RiviumStorageException = RiviumStorageException.HttpException(404, "Not found")
        assertEquals(404, http.statusCode)
        assertNull(http.code)
        val upload: RiviumStorageException = RiviumStorageException.UploadException("x")
        assertNull(upload.code)
        assertNull(upload.statusCode)
        assertNull(RiviumStorageException.NetworkException("offline").statusCode)
        // HttpException.statusCode is still a non-null Int.
        val status: Int = RiviumStorageException.HttpException(500, "x").statusCode
        assertEquals(500, status)
    }
}
