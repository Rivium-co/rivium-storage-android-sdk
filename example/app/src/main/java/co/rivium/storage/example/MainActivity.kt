package co.rivium.storage.example

import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalContext
import co.rivium.storage.example.ui.BucketsScreen
import co.rivium.storage.example.ui.ErrorsScreen
import co.rivium.storage.example.ui.FilesScreen
import co.rivium.storage.example.ui.HomeScreen
import co.rivium.storage.example.ui.LinksScreen
import co.rivium.storage.example.ui.LogScreen
import co.rivium.storage.example.ui.Section
import co.rivium.storage.example.ui.SelfTestScreen
import co.rivium.storage.example.ui.SetupScreen
import co.rivium.storage.example.ui.TransformsScreen
import co.rivium.storage.example.ui.UploadScreen

/**
 * Rivium Storage Android SDK - complete example.
 *
 * Setup: copy example/rivium.properties.example to example/rivium.properties (public key, token server,
 * test buckets) and see example/README.md. The app builds against the SDK source in ../rivium-storage.
 */
class MainActivity : ComponentActivity() {
    private val vm: StorageViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            ExampleTheme {
                App(vm)
            }
        }
    }
}

@Composable
fun ExampleTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val context = LocalContext.current
    val scheme = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> darkColorScheme()
        else -> lightColorScheme()
    }
    MaterialTheme(colorScheme = scheme, content = content)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun App(vm: StorageViewModel) {
    var sectionName by rememberSaveable { mutableStateOf<String?>(null) }
    val section = sectionName?.let { Section.valueOf(it) }
    val selfTest = remember(vm) { SelfTest(vm) }

    BackHandler(enabled = section != null) { sectionName = null }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(section?.title ?: "Rivium Storage") },
                navigationIcon = {
                    if (section != null) {
                        IconButton(onClick = { sectionName = null }) { Icon(Icons.Default.ArrowBack, contentDescription = "Back") }
                    }
                }
            )
        }
    ) { padding ->
        when (section) {
            null -> HomeScreen(vm, padding) { sectionName = it.name }
            Section.SETUP -> SetupScreen(vm, padding)
            Section.SELF_TEST -> SelfTestScreen(vm, selfTest, padding)
            Section.BUCKETS -> BucketsScreen(vm, padding)
            Section.UPLOAD -> UploadScreen(vm, padding)
            Section.FILES -> FilesScreen(vm, padding)
            Section.LINKS -> LinksScreen(vm, padding)
            Section.TRANSFORMS -> TransformsScreen(vm, padding)
            Section.ERRORS -> ErrorsScreen(vm, padding)
            Section.LOG -> LogScreen(vm, padding)
        }
    }
}
