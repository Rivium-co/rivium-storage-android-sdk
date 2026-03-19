package co.rivium.storage

import org.junit.Assert.*
import org.junit.Test

class RiviumStorageTest {

    @Test
    fun testConfigInitialization() {
        val config = RiviumStorageConfig(apiKey = "rv_live_test123")

        assertEquals("rv_live_test123", config.apiKey)
        assertEquals("https://storage.rivium.co", config.baseUrl)
        assertEquals(30, config.timeout)
    }

    @Test
    fun testImageTransformsToQueryParams() {
        val transforms = ImageTransforms(
            width = 200,
            height = 150,
            fit = "cover",
            format = "webp",
            quality = 80
        )

        val params = transforms.toQueryParams()

        assertEquals("200", params["w"])
        assertEquals("150", params["h"])
        assertEquals("cover", params["fit"])
        assertEquals("webp", params["f"])
        assertEquals("80", params["q"])
    }

    @Test
    fun testEmptyImageTransforms() {
        val transforms = ImageTransforms()
        val params = transforms.toQueryParams()

        assertTrue(params.isEmpty())
    }

    @Test
    fun testRiviumStorageUrlGeneration() {
        val storage = RiviumStorage(apiKey = "rv_live_test123")

        val url = storage.getUrl("file-123")
        assertEquals("https://storage.rivium.co/api/v1/files/file-123/url", url)

        val downloadUrl = storage.getDownloadUrl("file-123")
        assertEquals("https://storage.rivium.co/api/v1/files/file-123/download", downloadUrl)
    }

    @Test
    fun testTransformUrlGeneration() {
        val storage = RiviumStorage(apiKey = "rv_live_test123")

        val url = storage.getTransformUrl(
            fileId = "file-123",
            transforms = ImageTransforms(width = 200, height = 200)
        )

        assertTrue(url.contains("/api/v1/transform/file-123"))
        assertTrue(url.contains("w=200"))
        assertTrue(url.contains("h=200"))
    }

    @Test
    fun testUploadOptionsDefaults() {
        val options = UploadOptions()

        assertNull(options.contentType)
        assertNull(options.metadata)
    }

    @Test
    fun testListFilesOptionsDefaults() {
        val options = ListFilesOptions()

        assertNull(options.prefix)
        assertNull(options.limit)
        assertNull(options.cursor)
    }

    @Test
    fun testRiviumStorageExceptionMessages() {
        val networkError = RiviumStorageException.NetworkException("Connection failed")
        assertTrue(networkError.message!!.contains("Network error"))

        val httpError = RiviumStorageException.HttpException(404, "Not found")
        assertTrue(httpError.message!!.contains("404"))

        val uploadError = RiviumStorageException.UploadException("File too large")
        assertTrue(uploadError.message!!.contains("Upload error"))
    }
}
