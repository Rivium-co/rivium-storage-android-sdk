package co.rivium.storage.example

/**
 * Values from example/rivium.properties (see rivium.properties.example and README.md).
 * Nothing secret is here: only the public key, the token server address and the test bucket names.
 */
object ExampleConfig {
    val apiKey: String = BuildConfig.RIVIUM_API_KEY
    val tokenServerUrl: String = BuildConfig.TOKEN_SERVER_URL.trimEnd('/')
    val publicBucket: String = BuildConfig.PUBLIC_BUCKET
    val privateBucket: String = BuildConfig.PRIVATE_BUCKET
    val demoUserId: String = BuildConfig.DEMO_USER_ID

    /** False while the placeholder key is still in place. */
    val hasApiKey: Boolean get() = apiKey.startsWith("rv_") && !apiKey.contains("xxxx")

    val hasTokenServer: Boolean get() = tokenServerUrl.startsWith("http")

    /** The key with the middle hidden, for the screen. */
    val maskedKey: String
        get() = if (apiKey.length <= 14) apiKey else apiKey.take(10) + "…" + apiKey.takeLast(4)
}
