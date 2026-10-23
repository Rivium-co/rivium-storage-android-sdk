package co.rivium.storage.example.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil.compose.SubcomposeAsyncImage
import co.rivium.storage.example.ActionResult
import co.rivium.storage.example.StorageViewModel
import co.rivium.storage.example.formatBytes
import co.rivium.storage.UploadProgress

/** Colours for result panels that read well in light and dark mode. */
@Composable
fun resultColors(kind: ActionResult.Kind): Pair<Color, Color> {
    val dark = MaterialTheme.colorScheme.background.let { (it.red + it.green + it.blue) / 3 < 0.5f }
    return when (kind) {
        ActionResult.Kind.SUCCESS -> if (dark) Color(0xFF12351F) to Color(0xFFB7F0C8) else Color(0xFFE6F6EC) to Color(0xFF14532D)
        ActionResult.Kind.EXPECTED_ERROR -> if (dark) Color(0xFF14304A) to Color(0xFFBFDBFE) else Color(0xFFE7F0FB) to Color(0xFF1E3A8A)
        ActionResult.Kind.ERROR -> MaterialTheme.colorScheme.errorContainer to MaterialTheme.colorScheme.onErrorContainer
        ActionResult.Kind.WARNING -> if (dark) Color(0xFF3D3110) to Color(0xFFFDE68A) else Color(0xFFFFF7E0) to Color(0xFF713F12)
    }
}

fun kindLabel(kind: ActionResult.Kind) = when (kind) {
    ActionResult.Kind.SUCCESS -> "Success"
    ActionResult.Kind.EXPECTED_ERROR -> "Expected error"
    ActionResult.Kind.ERROR -> "Error"
    ActionResult.Kind.WARNING -> "Check this"
}

/** A screen body: a padded lazy column. */
@Composable
fun ScreenColumn(padding: PaddingValues, content: LazyListScope.() -> Unit) {
    LazyColumn(
        modifier = Modifier.padding(padding),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        content = content
    )
}

@Composable
fun Intro(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/**
 * One feature: what it is for, the buttons that run it, and its result.
 * [keys] are the result keys this card shows (usually one).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DemoCard(
    vm: StorageViewModel,
    title: String,
    explanation: String,
    keys: List<String>,
    code: String? = null,
    actions: @Composable () -> Unit,
    extra: @Composable () -> Unit = {},
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f))
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(explanation, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (code != null) CodeBlock(code)
            extra()
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                actions()
            }
            keys.forEach { key ->
                vm.uploadProgress[key]?.let { UploadProgressBar(it) }
                if (vm.running[key] == true) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                        Text("Working…", style = MaterialTheme.typography.bodySmall)
                    }
                }
                vm.results[key]?.let { ResultPanel(it) }
            }
        }
    }
}

/** A real upload progress bar, fed by upload(..., onProgress = ...). */
@Composable
fun UploadProgressBar(p: UploadProgress) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        LinearProgressIndicator(progress = p.fraction.toFloat(), modifier = Modifier.fillMaxWidth())
        Text(
            "${formatBytes(p.bytesSent)} of ${formatBytes(p.totalBytes)} (${(p.fraction * 100).toInt()}%)",
            style = MaterialTheme.typography.bodySmall
        )
    }
}

@Composable
fun CodeBlock(code: String) {
    Surface(color = MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(8.dp), modifier = Modifier.fillMaxWidth()) {
        Text(
            code,
            modifier = Modifier.padding(10.dp),
            fontFamily = FontFamily.Monospace,
            style = MaterialTheme.typography.bodySmall
        )
    }
}

@Composable
fun ResultPanel(result: ActionResult) {
    val (bg, fg) = resultColors(result.kind)
    val context = LocalContext.current
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(bg)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(kindLabel(result.kind), color = fg, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelLarge)
        result.title?.let { Text(it, color = fg, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium) }
        SelectionContainer {
            Column {
                result.lines.forEach { line ->
                    Text(line, color = fg, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                }
            }
        }
        result.bitmap?.let {
            Image(
                bitmap = it.asImageBitmap(),
                contentDescription = "Result image",
                modifier = Modifier.fillMaxWidth().heightIn(max = 260.dp).clip(RoundedCornerShape(8.dp)),
                contentScale = ContentScale.Fit
            )
        }
        result.imageUrl?.let { url ->
            UrlImage(url, fg)
        }
        result.openUrl?.let { url ->
            TextButton(onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }) {
                Text("Open in browser")
            }
        }
    }
}

/** Loads a URL with NO headers (as any image widget does) and shows success or the loader's error. */
@Composable
fun UrlImage(url: String, fg: Color) {
    SubcomposeAsyncImage(
        model = url,
        contentDescription = "Image loaded from a URL",
        modifier = Modifier.fillMaxWidth().heightIn(max = 260.dp).clip(RoundedCornerShape(8.dp)),
        contentScale = ContentScale.Fit,
        loading = {
            Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(Modifier.size(24.dp))
            }
        },
        error = { state ->
            Text(
                "Image widget could not load this URL: ${state.result.throwable.message}",
                color = fg, style = MaterialTheme.typography.bodySmall
            )
        }
    )
}

@Composable
fun ActionButton(vm: StorageViewModel, key: String, label: String, primary: Boolean = true, onClick: () -> Unit) {
    val busy = vm.running[key] == true
    if (primary) {
        Button(onClick = onClick, enabled = !busy) { Text(label) }
    } else {
        OutlinedButton(onClick = onClick, enabled = !busy) { Text(label) }
    }
}

@Composable
fun TonalButton(label: String, enabled: Boolean = true, onClick: () -> Unit) {
    FilledTonalButton(onClick = onClick, enabled = enabled) { Text(label) }
}

@Composable
fun KeyValue(key: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(key, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold, modifier = Modifier.width(120.dp))
        Text(value, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
    }
}
