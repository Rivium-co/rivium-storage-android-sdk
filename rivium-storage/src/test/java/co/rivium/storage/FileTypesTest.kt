package co.rivium.storage

import com.google.gson.JsonParser
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The content type sent when the app passes none.
 *
 * `src/test/resources/file-types.json` is a copy of the JSON block in
 * RiviumConsole/docs/storage-redesign/FILE-TYPES.md (the Console's and the
 * server's file-types.json). It must stay identical: when the Console's list
 * changes, copy it here and update [FileTypes].
 */
class FileTypesTest {

    /** Every extension in the canonical JSON with the type an SDK must send for it. */
    private val canonical: Map<String, String> by lazy {
        val text = javaClass.classLoader!!.getResource("file-types.json")!!.readText()
        val root = JsonParser.parseString(text).asJsonObject
        val out = linkedMapOf<String, String>()
        for (group in root.getAsJsonArray("groups")) {
            for (format in group.asJsonObject.getAsJsonArray("formats")) {
                for ((ext, type) in format.asJsonObject.getAsJsonObject("extensions").entrySet()) {
                    out[ext] = type.asString
                }
            }
        }
        out
    }

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

    private fun storage() = RiviumStorage(apiKey = "rv_live_x", userToken = "tok", client = OkHttpClient.Builder()
        .addInterceptor(Interceptor { chain ->
            val original = chain.request()
            val target = original.url.newBuilder().scheme("http").host(server.hostName).port(server.port).build()
            chain.proceed(original.newBuilder().url(target).build())
        }).build())

    private val fileJson = """{"id":"f1","bucketId":"b1","path":"p","fileName":"p","mimeType":"x",
        |"size":1,"createdAt":"2026-01-01T00:00:00.000Z",
        |"updatedAt":"2026-01-01T00:00:00.000Z"}""".trimMargin()

    @Test
    fun tableEqualsTheCanonicalJson() {
        assertTrue("canonical list looks empty", canonical.size >= 40)
        assertEquals(canonical, FileTypes.byExtension)
    }

    @Test
    fun everyExtensionMapsToItsPrimaryType() {
        for ((ext, type) in canonical) {
            assertEquals("photo.$ext", type, FileTypes.contentTypeFor("photo.$ext"))
            assertEquals("dir/sub/FILE.${ext.uppercase()}", type, FileTypes.contentTypeFor("dir/sub/FILE.${ext.uppercase()}"))
        }
    }

    @Test
    fun edgeCases() {
        assertEquals("image/jpeg", FileTypes.contentTypeFor("PHOTO.JPG"))
        assertEquals("application/gzip", FileTypes.contentTypeFor("backups/a.tar.gz"))
        assertEquals("application/x-tar", FileTypes.contentTypeFor("a.tar"))
        assertNull(FileTypes.contentTypeFor("README"))
        assertNull(FileTypes.contentTypeFor(".config/file"))
        assertNull(FileTypes.contentTypeFor("my.folder/file"))
        assertNull(FileTypes.contentTypeFor(".gitignore"))
        assertNull(FileTypes.contentTypeFor("name."))
        assertNull(FileTypes.contentTypeFor("tool.exe"))
        assertEquals("text/markdown", FileTypes.contentTypeFor(".notes/todo.md"))
    }

    /** The multipart part's Content-Type, as the server receives it. */
    private fun sentType(path: String, contentType: String? = null): String = runBlocking {
        server.enqueue(MockResponse().setResponseCode(201).setBody(fileJson))
        storage().upload("b1", path, "hi".toByteArray(), UploadOptions(contentType = contentType))
        val body = server.takeRequest().body.readUtf8()
        Regex("""filename="[^"]*"\r\nContent-Type: ([^\r\n]+)""").find(body)!!.groupValues[1]
    }

    @Test
    fun uploadSendsTheTableTypeWithoutContentType() {
        assertEquals("application/json", sentType("data/a.json"))
        assertEquals("text/csv", sentType("data/a.csv"))
        assertEquals("text/markdown", sentType("data/a.md"))
        assertEquals("audio/vnd.wave", sentType("rec/A.WAV"))
        assertEquals("application/octet-stream", sentType("data/blob.unknownext"))
        assertEquals("application/octet-stream", sentType(".config/file"))
    }

    @Test
    fun uploadKeepsTheAppsContentType() {
        assertEquals("audio/wav", sentType("rec/a.wav", "audio/wav"))
    }
}
