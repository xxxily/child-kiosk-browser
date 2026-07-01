package site.anzz.childkiosk.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import coil.compose.AsyncImage
import coil.request.ImageRequest
import site.anzz.childkiosk.util.WebIconDiscovery

@Composable
fun NetworkWebIcon(
    url: String,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    referer: String? = null,
    contentScale: ContentScale = ContentScale.Crop,
    error: Painter = rememberVectorPainter(Icons.Default.Star),
    onError: (() -> Unit)? = null
) {
    val context = LocalContext.current
    val refererCandidates = remember(referer) {
        WebIconDiscovery.iconRefererCandidatesFor(referer)
    }
    var refererIndex by remember(url, referer) { mutableIntStateOf(0) }
    val activeReferer = refererCandidates.getOrNull(refererIndex)
    val request = ImageRequest.Builder(context)
        .data(url)
        .apply {
            if (!activeReferer.isNullOrBlank()) {
                setHeader("Referer", activeReferer)
            }
        }
        .listener(
            onError = { _, _ ->
                if (refererIndex < refererCandidates.lastIndex) {
                    refererIndex += 1
                } else {
                    onError?.invoke()
                }
            }
        )
        .build()

    AsyncImage(
        model = request,
        contentDescription = contentDescription,
        modifier = modifier,
        contentScale = contentScale,
        error = error
    )
}
