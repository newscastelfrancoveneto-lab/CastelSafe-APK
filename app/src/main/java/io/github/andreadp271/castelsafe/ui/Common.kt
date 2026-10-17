package io.github.andreadp271.castelsafe.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.LruCache
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.andreadp271.castelsafe.data.Api
import io.github.andreadp271.castelsafe.data.Level
import io.github.andreadp271.castelsafe.data.Res
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.time.Duration
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Bar(title: String, subtitle: String? = null, onBack: (() -> Unit)? = null, actions: @Composable RowScope.() -> Unit = {}) {
    TopAppBar(
        title = {
            Column {
                Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold)
                if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            }
        },
        navigationIcon = {
            if (onBack != null) IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Indietro") }
        },
        actions = actions,
        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
    )
}

/** Riquadro bianco/scuro con angoli morbidi: l'unico "contenitore" usato nelle liste. */
@Composable
fun Panel(modifier: Modifier = Modifier, onClick: (() -> Unit)? = null, padding: Dp = 16.dp, content: @Composable ColumnScope.() -> Unit) {
    val colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
    val inner: @Composable ColumnScope.() -> Unit = { Column(Modifier.padding(padding), content = content) }
    if (onClick != null) Card(onClick = onClick, modifier = modifier.fillMaxWidth(), colors = colors, shape = RoundedCornerShape(16.dp), content = inner)
    else Card(modifier = modifier.fillMaxWidth(), colors = colors, shape = RoundedCornerShape(16.dp), content = inner)
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text.uppercase(), modifier = modifier.padding(start = 4.dp, top = 8.dp, bottom = 2.dp),
        style = MaterialTheme.typography.labelMedium, letterSpacing = 0.8.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.SemiBold,
    )
}

/** Riga di menu: icona, titolo, sottotitolo e freccia. */
@Composable
fun MenuRow(title: String, subtitle: String? = null, icon: ImageVector? = null, iconPainter: androidx.compose.ui.graphics.painter.Painter? = null, trailing: @Composable (() -> Unit)? = null, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        if (icon != null) Icon(icon, null, tint = MaterialTheme.colorScheme.primary)
        if (iconPainter != null) Icon(iconPainter, null, tint = MaterialTheme.colorScheme.primary)
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (trailing != null) trailing() else Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Pallino colorato del livello. */
@Composable
fun LevelDot(level: Level, size: Dp = 12.dp) {
    Box(Modifier.size(size).clip(CircleShape).background(level.color()))
}

/** Etichetta piena del livello (es. "ALLERTA GIALLA"). */
@Composable
fun LevelChip(level: Level, text: String = level.label.uppercase()) {
    Surface(color = level.color(), contentColor = level.onColor(), shape = RoundedCornerShape(50)) {
        Text(text, Modifier.padding(horizontal = 10.dp, vertical = 3.dp), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
    }
}

/** Avviso su dati non aggiornati o errori di rete, sopra il contenuto. */
@Composable
fun <T> ResNotice(res: Res<T>, what: String) {
    val msg = when {
        res.error != null && res.data != null -> "$what non aggiornati (${res.error}). Dati del ${fmtDateTime(res.fetchedAt)}."
        res.error != null -> "$what non disponibili: ${res.error}"
        else -> null
    } ?: return
    Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
        Text(msg, Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSecondaryContainer)
    }
}

@Composable
fun Muted(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
fun Hspace(w: Dp) = Spacer(Modifier.width(w))

val screenPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 24.dp)

private val dtFmt = DateTimeFormatter.ofPattern("dd/MM HH:mm")
private val tFmt = DateTimeFormatter.ofPattern("HH:mm")

fun fmtDateTime(t: LocalDateTime?): String = t?.format(dtFmt) ?: "—"
fun fmtTime(t: LocalDateTime?): String = t?.format(tFmt) ?: "—"

/** "adesso", "5 min fa", "2 h fa", oppure data e ora. */
fun ago(t: LocalDateTime?): String {
    if (t == null) return "—"
    val m = Duration.between(t, LocalDateTime.now()).toMinutes()
    return when {
        m < 1 -> "adesso"
        m < 60 -> "$m min fa"
        m < 24 * 60 -> "${m / 60} h fa"
        else -> fmtDateTime(t)
    }
}

fun fmtValue(v: Double?, unit: String): String {
    if (v == null) return "n.d."
    val s = if (v == Math.floor(v) && kotlin.math.abs(v) < 1e6 && unit != "m") v.toLong().toString() else "%.2f".format(java.util.Locale.ITALY, v).trimEnd('0').trimEnd(',')
    return if (unit.isBlank()) s else "$s $unit"
}

fun openUrl(ctx: Context, url: String) {
    try {
        ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    } catch (e: ActivityNotFoundException) {
        Toast.makeText(ctx, "Nessuna app per aprire il collegamento", Toast.LENGTH_SHORT).show()
    }
}

fun dial(ctx: Context, tel: String) = openUrl(ctx, "tel:$tel")

/** Navigazione verso un punto con l'app di mappe preferita. */
fun navigateTo(ctx: Context, lat: Double, lon: Double, label: String) {
    val geo = Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=$lat,$lon(${Uri.encode(label)})"))
    try { ctx.startActivity(geo) } catch (e: ActivityNotFoundException) {
        openUrl(ctx, "https://www.google.com/maps/dir/?api=1&destination=$lat,$lon")
    }
}

fun share(ctx: Context, text: String) {
    ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), "Condividi"))
}

private val imageCache = LruCache<String, ImageBitmap>(40)

/** Immagine remota piccola (icone meteo ARPAV), con cache in memoria. */
@Composable
fun RemoteIcon(url: String, size: Dp, modifier: Modifier = Modifier) {
    var bmp by remember(url) { mutableStateOf(imageCache.get(url)) }
    LaunchedEffect(url) {
        if (bmp != null || url.isBlank()) return@LaunchedEffect
        bmp = withContext(Dispatchers.IO) {
            runCatching {
                Api.client.newCall(Request.Builder().url(url).build()).execute().use { r ->
                    BitmapFactory.decodeStream(r.body!!.byteStream())?.asImageBitmap()
                }
            }.getOrNull()
        }?.also { imageCache.put(url, it) }
    }
    bmp?.let { Image(it, null, modifier.size(size)) } ?: Spacer(modifier.size(size))
}
