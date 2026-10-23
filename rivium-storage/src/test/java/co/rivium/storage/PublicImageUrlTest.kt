package co.rivium.storage

import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/**
 * Header-free image URLs for public buckets: `getPublicUrl(fileId, transforms)` and the pure helper
 * `RiviumStorage.publicImageUrl(publicUrl, transforms)`. The same rules are in every Rivium Storage SDK.
 */
class PublicImageUrlTest {

    private lateinit var server: MockWebServer
    private val link = "https://storage.rivium.co/public/b1/photos/my%20cat.png"

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun storage() = RiviumStorage(apiKey = "rv_live_x", client = OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
        val original = chain.request()
        val target = original.url.newBuilder().scheme("http").host(server.hostName).port(server.port).build()
        chain.proceed(original.newBuilder().url(target).build())
    }).build())

    private fun answerLink() = server.enqueue(MockResponse().setBody("""{"url":"$link"}"""))

    private inline fun refusal(call: () -> Unit): RiviumStorageException {
        try {
            call()
        } catch (e: RiviumStorageException) {
            return e
        }
        fail("no exception")
        throw IllegalStateException()
    }

    @Test
    fun getPublicUrlAddsTheOptionsWithShortNamesInAFixedOrder() = runBlocking {
        answerLink()
        val url = storage().getPublicUrl(
            "f1",
            ImageTransforms(rotate = 90, sharpen = 2, blur = 1, quality = 70, format = "WEBP", fit = "Contain", height = 300, width = 600),
        )
        assertEquals("$link?w=600&h=300&fit=contain&f=webp&q=70&blur=1&sharpen=2&rotate=90", url)
        val request = server.takeRequest()
        assertEquals("GET", request.method)
        assertEquals("/api/v1/files/f1/url", request.path)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun onlySetOptionsJpgIsJpegLargeSizesAreClampedEqualOptionsGiveEqualUrls() = runBlocking {
        val storage = storage()
        repeat(6) { answerLink() }
        assertEquals("$link?w=600&f=webp", storage.getPublicUrl("f1", ImageTransforms(width = 600, format = "webp")))
        assertEquals("$link?w=600&f=webp", storage.getPublicUrl("f1", ImageTransforms(format = "webp", width = 600)))
        assertEquals("$link?w=4096&f=jpeg", storage.getPublicUrl("f1", ImageTransforms(width = 5000, format = "jpg")))
        assertEquals(link, storage.getPublicUrl("f1", ImageTransforms(fit = "")))
        assertEquals(link, storage.getPublicUrl("f1", ImageTransforms()))
        assertEquals(link, storage.getPublicUrl("f1"))
    }

    @Test
    fun aRefusedOptionThrowsInvalidTransformBeforeAnyRequest() = runBlocking {
        val storage = storage()
        val bad = listOf(
            ImageTransforms(format = "gif"), ImageTransforms(fit = "stretch"), ImageTransforms(width = 0),
            ImageTransforms(height = -1), ImageTransforms(quality = 0), ImageTransforms(quality = 101),
            ImageTransforms(blur = 21), ImageTransforms(blur = -1), ImageTransforms(sharpen = 11),
        )
        for (t in bad) {
            val e = try {
                storage.getPublicUrl("f1", t)
                fail("no exception for $t")
                throw IllegalStateException()
            } catch (e: RiviumStorageException) {
                e
            }
            assertEquals(t.toString(), StorageErrorCode.INVALID_TRANSFORM, e.code)
            assertEquals(400, e.statusCode)
        }
        assertEquals(0, server.requestCount)
    }

    @Test
    fun aPrivateBucketKeepsTheServer403() = runBlocking {
        val storage = storage()
        for (t in listOf(null, ImageTransforms(width = 200))) {
            server.enqueue(MockResponse().setResponseCode(403).setBody("""{"statusCode":403,"message":"File is not in a public bucket"}"""))
            val e = try {
                storage.getPublicUrl("f1", t)
                fail("no exception")
                throw IllegalStateException()
            } catch (e: RiviumStorageException) {
                e
            }
            assertEquals(403, e.statusCode)
            assertTrue(e.message, e.message!!.endsWith("File is not in a public bucket"))
        }
    }

    @Test
    fun publicImageUrlMakesNoRequestAndMatchesGetPublicUrl() = runBlocking {
        val t = ImageTransforms(width = 600, height = 400, fit = "cover", format = "avif", quality = 50)
        answerLink()
        val fromServer = storage().getPublicUrl("f1", t)
        server.takeRequest()
        assertEquals(fromServer, RiviumStorage.publicImageUrl(link, t))
        assertEquals(link, RiviumStorage.publicImageUrl(link))
        assertEquals(1, server.requestCount)
    }

    @Test
    fun publicImageUrlReplacesAQueryOrFragment() {
        assertEquals("$link?w=300", RiviumStorage.publicImageUrl("$link?w=100&f=png", ImageTransforms(width = 300)))
        assertEquals("$link?f=webp", RiviumStorage.publicImageUrl("$link#top", ImageTransforms(format = "webp")))
        assertEquals(link, RiviumStorage.publicImageUrl("$link?v=2", ImageTransforms()))
    }

    @Test
    fun publicImageUrlRefusesBadOptionsAndLinksThatAreNotPublicFileLinks() {
        for (t in listOf(ImageTransforms(format = "bmp"), ImageTransforms(quality = 0))) {
            val e = refusal { RiviumStorage.publicImageUrl(link, t) }
            assertEquals(StorageErrorCode.INVALID_TRANSFORM, e.code)
            assertEquals(400, e.statusCode)
        }
        for (bad in listOf(
            "", "photos/cat.png", "https://storage.rivium.co/api/v1/transform/f1", "https://storage.rivium.co/public/b1",
            "https://storage.rivium.co/public/b1/", "ftp://x/public/b1/a.png",
        )) {
            val e = refusal { RiviumStorage.publicImageUrl(bad, ImageTransforms(width = 10)) }
            assertEquals(bad, StorageErrorCode.INVALID_PATH, e.code)
            assertEquals(400, e.statusCode)
        }
    }

    @Test
    fun theNewCodes() {
        assertEquals("transform_busy", StorageErrorCode.TRANSFORM_BUSY)
        assertEquals("too_many_variants", StorageErrorCode.TOO_MANY_VARIANTS)
        assertEquals("plan_feature_locked", StorageErrorCode.PLAN_FEATURE_LOCKED)
    }
}
