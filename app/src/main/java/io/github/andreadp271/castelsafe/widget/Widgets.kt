package io.github.andreadp271.castelsafe.widget

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
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
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.color.ColorProvider
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
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
import io.github.andreadp271.castelsafe.data.Lettura
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
        // una sessione già aperta non rilancia provideGlance: la ridisegno con questo segnale
        load(ctx)
        tick.value++
        // gli ID arrivano dal sistema, per ricevitore: ogni widget resta quello che è
        refresh(ctx, AllertaWidgetReceiver::class.java, AllertaWidget())
        refresh(ctx, SensoriWidgetReceiver::class.java, SensoriWidget())
        runCatching { RadarRender.renderAll(ctx) }
        refresh(ctx, GraficoWidgetReceiver::class.java, GraficoWidget())
        refresh(ctx, RadarWidgetReceiver::class.java, RadarWidget())
    }

    internal val tick = kotlinx.coroutines.flow.MutableStateFlow(0)

    /** Aggiorna solo il radar, se l'ultimo disegno ha più di [etaMs] millisecondi. */
    suspend fun updateRadar(ctx: Context, etaMs: Long) {
        RadarRender.renderAll(ctx, etaMs = etaMs)
        refresh(ctx, RadarWidgetReceiver::class.java, RadarWidget())
    }

    private suspend fun refresh(ctx: Context, receiver: Class<*>, widget: GlanceAppWidget) {
        val ids = android.appwidget.AppWidgetManager.getInstance(ctx)
            .getAppWidgetIds(android.content.ComponentName(ctx, receiver))
        val gm = androidx.glance.appwidget.GlanceAppWidgetManager(ctx)
        ids.forEach { runCatching { widget.update(ctx, gm.getGlanceIdBy(it)) } }
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

// ── stile "Semaforo": il fondo è il colore del livello, testo nero (bianco sul rosso) ──

private val nero = Color(0xFF0A0A0A)

private fun Level.bg(): Color = when (this) {
    Level.VERDE -> Color(0xFF22C55E)
    Level.GIALLO -> Color(0xFFFACC15)
    Level.ARANCIONE -> Color(0xFFFB923C)
    Level.ROSSO -> Color(0xFFDC2626)
    Level.NESSUNO -> Color(0xFF94A3B8)
}

private fun Level.ink(): Color = if (this == Level.ROSSO) Color.White else nero
private fun cp(c: Color) = ColorProvider(c, c)

/** Livello di una lettura: senza soglie (es. la temperatura) vale come "nella norma". */
private fun Level.norma() = if (this == Level.NESSUNO) Level.VERDE else this

/** Pallino del livello con un bordo del colore del testo, visibile anche sul suo stesso colore. */
@Composable
private fun Dot(level: Level, ink: Color, size: Int = 13) {
    Box(GlanceModifier.size(size.dp), contentAlignment = Alignment.Center) {
        Image(ImageProvider(R.drawable.widget_dot), null, GlanceModifier.size(size.dp), colorFilter = ColorFilter.tint(cp(ink)))
        Image(ImageProvider(R.drawable.widget_dot), null, GlanceModifier.size((size * 0.62f).dp), colorFilter = ColorFilter.tint(cp(level.norma().bg())))
    }
}

@Composable
private fun Frame(level: Level, route: String, ctx: Context, content: @Composable () -> Unit) {
    Column(
        GlanceModifier.fillMaxSize().background(cp(level.bg())).cornerRadius(30.dp)
            .padding(horizontal = 16.dp, vertical = 12.dp).clickable(Widgets.open(ctx, route)),
    ) { content() }
}

// ── Allerta ───────────────────────────────────────────────────────────────────

class AllertaWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        Widgets.load(context)
        provideContent {
            Widgets.tick.collectAsState().value
            val a = Repo.allerta.collectAsState().value.data
            AllertaContent(context, a)
        }
    }
}

class AllertaWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = AllertaWidget()
}

@Composable
private fun AllertaContent(ctx: Context, a: Allerta?) {
    val max = a?.maxLevel ?: Level.NESSUNO
    val ink = max.ink()
    val dim = ink.copy(alpha = 0.6f)
    Frame(max.let { if (a == null) Level.NESSUNO else it.norma() }, "allerta", ctx) {
        Text(
            when {
                a == null -> "Allerta non disponibile"
                max <= Level.VERDE -> "Nessuna allerta"
                else -> "Allerta ${max.label.lowercase()}"
            },
            style = TextStyle(color = cp(ink), fontSize = 20.sp, fontWeight = FontWeight.Bold), maxLines = 1,
        )
        if (a == null) return@Frame
        val oggi = a.oggi?.rischi.orEmpty().toMap()
        val domani = a.domani?.rischi.orEmpty().toMap()
        // solo i rischi che non sono a verde, i più gravi per primi
        val righe = (oggi.keys + domani.keys).distinct()
            .map { r -> Triple(r, oggi[r] ?: Level.NESSUNO, domani[r] ?: Level.NESSUNO) }
            .filter { maxOf(it.second, it.third) > Level.VERDE }
            .sortedByDescending { maxOf(it.second, it.third) }
            .take(3)
        Spacer(GlanceModifier.height(4.dp))
        if (righe.isEmpty()) {
            Text(
                "Bollettino ${a.aggiornamento?.data ?: ""} ${a.aggiornamento?.ora?.take(5).orEmpty()}".trim(),
                style = TextStyle(color = cp(dim), fontSize = 12.sp, fontWeight = FontWeight.Medium), maxLines = 1,
            )
            return@Frame
        }
        Row(GlanceModifier.fillMaxWidth()) {
            Spacer(GlanceModifier.defaultWeight())
            listOf("OGGI", "DOMANI").forEach {
                Text(it, GlanceModifier.width(56.dp), style = TextStyle(color = cp(dim), fontSize = 9.sp, fontWeight = FontWeight.Bold, textAlign = androidx.glance.text.TextAlign.Center))
            }
        }
        righe.forEach { (nome, o, d) ->
            Row(GlanceModifier.fillMaxWidth().padding(vertical = 1.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(nome, GlanceModifier.defaultWeight(), style = TextStyle(color = cp(ink), fontSize = 13.sp, fontWeight = FontWeight.Bold), maxLines = 1)
                listOf(o, d).forEach { l ->
                    Box(GlanceModifier.width(56.dp), contentAlignment = Alignment.Center) { Dot(l, ink, 14) }
                }
            }
        }
    }
}

// ── Fiumi e meteo ─────────────────────────────────────────────────────────────

class SensoriWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        Widgets.load(context)
        val awId = GlanceAppWidgetManager(context).getAppWidgetId(id)
        WidgetPrefs.righe(context, awId).map { it.sid }.distinct().forEach { runCatching { Repo.loadStazione(it) } }
        provideContent {
            val t = Widgets.tick.collectAsState().value
            val m = Repo.monitor.collectAsState().value.data
            val righe = remember(t) { WidgetPrefs.righe(context, awId) }
            SensoriContent(context, m, righe)
        }
    }
}

class SensoriWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = SensoriWidget()

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        super.onDeleted(context, appWidgetIds)
        WidgetPrefs.elimina(context, appWidgetIds)
    }
}

/** Lettura in tempo reale; se manca (es. il vento), l'ultimo punto dello storico della stazione. */
private fun lettura(m: Map<String, StatoStazione>?, sid: String, tipo: String): Lettura? {
    m?.get(sid)?.sensori?.get(tipo)?.let { return it }
    val serie = Repo.stazione(sid).value.data ?: return null
    val s = serie.firstOrNull { it.type == tipo } ?: return null
    val p = s.ultimo ?: return null
    return Lettura(p.valore, s.unita, p.istante.toLocalTime().toString().take(5))
}

@Composable
private fun SensoriContent(ctx: Context, m: Map<String, StatoStazione>?, righe: List<WRiga>) {
    val letture = righe.map { (sid, t, _) -> lettura(m, sid, t) }
    val livelli = righe.mapIndexed { i, r -> Stations.level(r.sid, r.tipo, letture[i]?.valore) }
    val max = livelli.maxOrNull()?.takeIf { it != Level.NESSUNO } ?: Level.VERDE
    val ora = letture.firstNotNullOfOrNull { it?.istante }
    val livello = if (m == null) Level.NESSUNO else max
    val ink = livello.ink()
    Frame(livello, "sensori", ctx) {
        Text(
            "FIUMI E METEO" + (ora?.let { " · ore $it" } ?: ""),
            style = TextStyle(color = cp(ink.copy(alpha = 0.7f)), fontSize = 10.sp, fontWeight = FontWeight.Bold), maxLines = 1,
        )
        Spacer(GlanceModifier.height(6.dp))
        if (m == null) {
            Text("Dati non disponibili", style = TextStyle(color = cp(ink), fontSize = 20.sp, fontWeight = FontWeight.Bold))
            return@Frame
        }
        val grande = righe.size <= 4
        righe.withIndex().toList().chunked(2).forEach { coppia ->
            Row(GlanceModifier.fillMaxWidth().padding(bottom = 6.dp)) {
                coppia.forEach { (i, r) ->
                    val l = letture[i]
                    val inNorma = livelli[i].norma() == Level.VERDE
                    val c = if (inNorma) ink.copy(alpha = 0.6f) else ink
                    Column(GlanceModifier.defaultWeight()) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Dot(livelli[i], ink, 11)
                            Spacer(GlanceModifier.width(5.dp))
                            Text(r.nome.uppercase(), style = TextStyle(color = cp(c), fontSize = 9.sp, fontWeight = FontWeight.Bold), maxLines = 1)
                        }
                        Text(fmtValue(l?.valore, l?.unita.orEmpty()), style = TextStyle(color = cp(c), fontSize = if (grande) 20.sp else 16.sp, fontWeight = FontWeight.Bold), maxLines = 1)
                    }
                }
                if (coppia.size == 1) Spacer(GlanceModifier.defaultWeight())
            }
        }
    }
}
