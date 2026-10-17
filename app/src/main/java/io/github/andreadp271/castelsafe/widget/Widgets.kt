package io.github.andreadp271.castelsafe.widget

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.ColorFilter
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll
import androidx.glance.background
import androidx.glance.color.ColorProvider
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxHeight
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import io.github.andreadp271.castelsafe.MainActivity
import io.github.andreadp271.castelsafe.R
import io.github.andreadp271.castelsafe.data.Allerta
import io.github.andreadp271.castelsafe.data.Level
import io.github.andreadp271.castelsafe.data.Notifier
import io.github.andreadp271.castelsafe.data.Repo
import io.github.andreadp271.castelsafe.data.StatoStazione
import io.github.andreadp271.castelsafe.data.Stations
import io.github.andreadp271.castelsafe.ui.fmtValue
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/** Widget della schermata Home: allerta e sensori, con i dati in cache dell'app. */
object Widgets {
    /** Aggiorna i dati (se scaduti) e ridisegna tutti i widget. */
    suspend fun updateAll(ctx: Context) {
        AllertaWidget().updateAll(ctx)
        SensoriWidget().updateAll(ctx)
    }

    internal suspend fun load(ctx: Context) {
        Repo.init(ctx)
        runCatching {
            coroutineScope {
                launch { Repo.loadAllerta() }
                launch { Repo.loadMonitor() }
            }
        }
    }

    internal fun open(ctx: Context, route: String) = actionStartActivity(
        Intent(ctx, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .putExtra(Notifier.EXTRA_ROUTE, route),
    )
}

// ── colori (chiaro / scuro) ───────────────────────────────────────────────────

private val bg = ColorProvider(Color(0xFFFFF8F7), Color(0xFF1F1A1A))
private val fg = ColorProvider(Color(0xFF231919), Color(0xFFF1DEDD))
private val muted = ColorProvider(Color(0xFF6B5B5B), Color(0xFFBFAAAA))

private fun Level.argb(): Color = when (this) {
    Level.VERDE -> Color(0xFF16A34A)
    Level.GIALLO -> Color(0xFFEAB308)
    Level.ARANCIONE -> Color(0xFFF97316)
    Level.ROSSO -> Color(0xFFDC2626)
    Level.NESSUNO -> Color(0xFF94A3B8)
}

@Composable
private fun Dot(level: Level, size: Int = 14) {
    Image(
        ImageProvider(R.drawable.widget_dot), contentDescription = level.label.ifBlank { null },
        modifier = GlanceModifier.size(size.dp),
        colorFilter = ColorFilter.tint(ColorProvider(level.argb(), level.argb())),
    )
}

/** Cornice comune: sfondo arrotondato e barra laterale del colore del livello. */
@Composable
private fun Frame(level: Level, route: String, ctx: Context, content: @Composable () -> Unit) {
    Row(
        GlanceModifier.fillMaxSize().background(bg).cornerRadius(18.dp).clickable(Widgets.open(ctx, route)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(GlanceModifier.width(6.dp).fillMaxHeight().background(ColorProvider(level.argb(), level.argb()))) {}
        Column(GlanceModifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 10.dp)) { content() }
    }
}

// ── Allerta ───────────────────────────────────────────────────────────────────

class AllertaWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        Widgets.load(context)
        val a = Repo.allerta.value.data
        provideContent { AllertaContent(context, a) }
    }
}

class AllertaWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = AllertaWidget()
}

@Composable
private fun AllertaContent(ctx: Context, a: Allerta?) {
    val max = a?.maxLevel ?: Level.NESSUNO
    Frame(max, "allerta", ctx) {
        Text(
            when {
                a == null -> "Allerta non disponibile"
                max == Level.VERDE -> "Nessuna allerta"
                else -> "Allerta ${max.label.lowercase()}"
            },
            style = TextStyle(color = fg, fontSize = 16.sp, fontWeight = FontWeight.Bold), maxLines = 1,
        )
        Spacer(GlanceModifier.height(6.dp))
        if (a != null) {
            listOf("Oggi" to a.oggi, "Domani" to a.domani).forEach { (label, g) ->
                Row(GlanceModifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(label, GlanceModifier.width(58.dp), style = TextStyle(color = fg, fontSize = 13.sp, fontWeight = FontWeight.Medium))
                    (g?.rischi ?: emptyList()).forEach { (_, l) ->
                        Dot(l)
                        Spacer(GlanceModifier.width(6.dp))
                    }
                }
            }
            Spacer(GlanceModifier.height(4.dp))
            Text(
                "Bollettino ${a.aggiornamento?.data ?: ""} ${a.aggiornamento?.ora?.take(5).orEmpty()}".trim(),
                style = TextStyle(color = muted, fontSize = 11.sp), maxLines = 1,
            )
        }
    }
}

// ── Fiumi e meteo ─────────────────────────────────────────────────────────────

class SensoriWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        Widgets.load(context)
        val m = Repo.monitor.value.data
        provideContent { SensoriContent(context, m) }
    }
}

class SensoriWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = SensoriWidget()
}

/** Righe mostrate: livello dei due fiumi, temperatura e pioggia delle ultime 24 ore. */
private val righe = listOf(
    Triple("0292", "LIVIDRO", "Muson dei Sassi"),
    Triple("0653", "LIVIDRO", "Avenale"),
    Triple("0102", "TEMP", "Temperatura"),
    Triple("0102", "PRECCUM24", "Pioggia 24 ore"),
)

@Composable
private fun SensoriContent(ctx: Context, m: Map<String, StatoStazione>?) {
    val livelli = righe.map { (sid, t, _) -> Stations.level(sid, t, m?.get(sid)?.sensori?.get(t)?.valore) }
    val max = livelli.maxOrNull()?.takeIf { it != Level.NESSUNO } ?: Level.VERDE
    val ora = m?.get("0292")?.sensori?.get("LIVIDRO")?.istante
    Frame(if (m == null) Level.NESSUNO else max, "sensori", ctx) {
        Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Sensori ARPAV", GlanceModifier.defaultWeight(), style = TextStyle(color = fg, fontSize = 14.sp, fontWeight = FontWeight.Bold), maxLines = 1)
            ora?.let { Text("ore $it", style = TextStyle(color = muted, fontSize = 11.sp)) }
        }
        Spacer(GlanceModifier.height(4.dp))
        if (m == null) Text("Dati non disponibili", style = TextStyle(color = muted, fontSize = 12.sp))
        else righe.forEachIndexed { i, (sid, t, nome) ->
            val l = m[sid]?.sensori?.get(t)
            Row(GlanceModifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(nome, GlanceModifier.defaultWeight(), style = TextStyle(color = fg, fontSize = 13.sp), maxLines = 1)
                Text(fmtValue(l?.valore, l?.unita.orEmpty()), style = TextStyle(color = fg, fontSize = 13.sp, fontWeight = FontWeight.Bold), maxLines = 1)
                Spacer(GlanceModifier.width(6.dp))
                Dot(livelli[i], 10)
            }
        }
    }
}
