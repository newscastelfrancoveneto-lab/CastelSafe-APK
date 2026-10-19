package io.github.andreadp271.castelsafe.widget

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment as GAlignment
import androidx.glance.layout.Column as GColumn
import androidx.glance.layout.ContentScale
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.padding
import androidx.glance.text.FontWeight
import androidx.glance.text.Text as GText
import androidx.glance.text.TextStyle
import io.github.andreadp271.castelsafe.data.Repo
import io.github.andreadp271.castelsafe.data.Scheda
import io.github.andreadp271.castelsafe.data.SensorData
import io.github.andreadp271.castelsafe.data.Stations
import io.github.andreadp271.castelsafe.ui.CastelSafeTheme
import io.github.andreadp271.castelsafe.ui.fmtValue
import kotlinx.coroutines.launch
import java.time.LocalDateTime

/** Scelta di ogni widget Grafico: stazione e tipo di dato ("0292|LIVIDRO"). */
object GraficoPrefs {
    private fun p(ctx: Context) = ctx.getSharedPreferences("widget_grafico", Context.MODE_PRIVATE)

    fun get(ctx: Context, awId: Int): Pair<String, String> =
        p(ctx).getString("w$awId", null)?.split("|")?.takeIf { it.size == 2 }?.let { it[0] to it[1] } ?: ("0292" to "LIVIDRO")

    fun set(ctx: Context, awId: Int, sid: String, type: String) = p(ctx).edit().putString("w$awId", "$sid|$type").apply()

    fun elimina(ctx: Context, ids: IntArray) = p(ctx).edit().apply { ids.forEach { remove("w$it") } }.apply()
}

class GraficoWidget : GlanceAppWidget() {
    override val sizeMode = androidx.glance.appwidget.SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        Repo.init(context)
        val awId = GlanceAppWidgetManager(context).getAppWidgetId(id)
        val (sid, tipo) = GraficoPrefs.get(context, awId)
        runCatching { Repo.loadStazione(sid) }
        provideContent {
            val t = Widgets.tick.collectAsState().value
            val (s2, t2) = remember(t) { GraficoPrefs.get(context, awId) }
            val serie = Repo.stazione(s2).collectAsState().value.data
            val scheda = serie?.let { SensorData.schede(s2, it).firstOrNull { c -> c.type == t2 } }
            val stazione = Stations.byId(s2)?.name ?: s2
            val etichetta = scheda?.label ?: Stations.label(t2)
            val titulo = "$stazione · $etichetta"
            val dim = androidx.glance.LocalSize.current
            val ratio = (dim.width.value / dim.height.value.coerceAtLeast(1f)).coerceIn(0.5f, 3.6f)
            val bmp = remember(scheda, titulo, ratio) { disegna(scheda, s2, stazione, etichetta, ratio) }
            Image(
                ImageProvider(bmp), contentDescription = titulo,
                modifier = GlanceModifier.fillMaxSize().background(sfondo).cornerRadius(30.dp).clickable(Widgets.open(context, "sensori")),
                contentScale = ContentScale.Fit,
            )
        }
    }
}

private val sfondo = androidx.glance.color.ColorProvider(androidx.compose.ui.graphics.Color(0xFF0A0A0A), androidx.compose.ui.graphics.Color(0xFF0A0A0A))

class GraficoWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = GraficoWidget()

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        super.onDeleted(context, appWidgetIds)
        GraficoPrefs.elimina(context, appWidgetIds)
    }
}

/** Grafico delle ultime 24 ore su fondo nero, disegnato come immagine (Glance non ha un Canvas). */
private fun disegna(scheda: Scheda?, sid: String, stazione: String, etichetta: String, ratio: Float): Bitmap {
    // si disegna su una tela larga 720 unità e alta quanto serve alle proporzioni del widget
    val w = 720; val h = (720 / ratio).toInt(); val m = 36f
    val k = 1.5f
    val bmp = Bitmap.createBitmap((w * k).toInt(), (h * k).toInt(), Bitmap.Config.ARGB_8888)
    val c = Canvas(bmp)
    c.scale(k, k)
    val grigio = 0xFF94A3B8.toInt()
    val pt = scheda?.serie?.punti?.filter { it.valore != null && it.istante >= LocalDateTime.now().minusHours(24) } ?: emptyList()
    val ult = pt.lastOrNull()?.valore
    val liv = if (scheda == null) io.github.andreadp271.castelsafe.data.Level.NESSUNO else Stations.level(sid, scheda.type, ult)
    val col = when {
        scheda == null || pt.size < 2 -> grigio
        liv != io.github.andreadp271.castelsafe.data.Level.NESSUNO -> when (liv) {
            io.github.andreadp271.castelsafe.data.Level.VERDE -> 0xFF22C55E.toInt()
            io.github.andreadp271.castelsafe.data.Level.GIALLO -> 0xFFFACC15.toInt()
            io.github.andreadp271.castelsafe.data.Level.ARANCIONE -> 0xFFFB923C.toInt()
            else -> 0xFFEF4444.toInt()
        }
        scheda.type.startsWith("TEMP") || scheda.type == "TARIA" -> 0xFFF8FAFC.toInt()
        else -> 0xFF38BDF8.toInt()
    }
    fun p(sz: Float, color: Int, bold: Boolean = true, align: Paint.Align = Paint.Align.LEFT) =
        Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = sz; this.color = color; isFakeBoldText = bold; textAlign = align; letterSpacing = 0.04f }
    val t1 = p(22f, col)
    // il nome si accorcia da solo se non sta nello spazio accanto al valore
    val unita = scheda?.unita.orEmpty()
    val numero = if (ult == null) "" else fmtValue(ult, "").trim()
    val vp = p(66f, col, align = Paint.Align.RIGHT).apply { letterSpacing = -0.03f }
    val up = p(30f, col, align = Paint.Align.RIGHT)
    val larghVal = vp.measureText(numero) + (if (unita.isBlank()) 0f else up.measureText(unita) + 6f)
    val maxW = w - 2 * m - larghVal - 20f
    val l1 = stazione.uppercase(); val l2 = "${etichetta.uppercase()} · 24 H"
    while (maxOf(t1.measureText(l1), t1.measureText(l2)) > maxW && t1.textSize > 12f) t1.textSize -= 1f
    c.drawText(l1, m, 46f, t1)
    c.drawText(l2, m, 46f + t1.textSize + 6f, t1)
    if (scheda == null || pt.size < 2) {
        val gp = p(40f, grigio)
        c.drawText(if (scheda == null) "Dati non" else "Pochi dati", m, h - 90f, gp)
        c.drawText(if (scheda == null) "disponibili" else "nelle 24 ore", m, h - 44f, gp)
        return bmp
    }
    if (unita.isNotBlank()) c.drawText(unita, w - m, 76f, up)
    c.drawText(numero, w - m - (if (unita.isBlank()) 0f else up.measureText(unita) + 6f), 76f, vp)
    val vals = pt.map { it.valore!! }
    val barre = scheda.type == "PREC"
    var lo = if (barre) 0.0 else vals.min(); var hi = vals.max()
    if (hi - lo < 1e-6) { hi = lo + 1; if (!barre) lo -= 1 }
    val pad = if (barre) 0.0 else (hi - lo) * 0.1
    lo -= pad; hi += pad
    val l = m; val r = w - m; val top = 110f; val bot = h - 54f
    val t0 = pt.first().istante
    val span = java.time.Duration.between(t0, pt.last().istante).toMillis().coerceAtLeast(1)
    fun x(i: Int) = l + (r - l) * java.time.Duration.between(t0, pt[i].istante).toMillis() / span.toFloat()
    fun y(v: Double) = bot - (bot - top) * ((v - lo) / (hi - lo)).toFloat()
    if (barre) {
        val fp = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = col }
        val bw = ((r - l) / pt.size * 0.7f).coerceAtLeast(2f)
        pt.indices.forEach { i -> c.drawRect(x(i) - bw / 2, y(vals[i]), x(i) + bw / 2, bot, fp) }
    } else {
        val lp = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = col; style = Paint.Style.STROKE; strokeWidth = 7f; strokeJoin = Paint.Join.ROUND; strokeCap = Paint.Cap.ROUND }
        val path = Path().apply { pt.indices.forEach { i -> if (i == 0) moveTo(x(i), y(vals[i])) else lineTo(x(i), y(vals[i])) } }
        c.drawPath(path, lp)
        c.drawCircle(x(pt.size - 1), y(vals.last()), 10f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = col })
    }
    val fp = p(22f, 0xFFB6C0CC.toInt(), bold = true)
    val fd = p(22f, 0xFFB6C0CC.toInt(), bold = true, align = Paint.Align.RIGHT)
    if (barre) {
        fp.color = 0xFFFFFFFF.toInt()
        c.drawText("Totale 24 h ${fmtValue(vals.sum(), unita)}", l, h - 18f, fp)
        c.drawText("picco ${fmtValue(vals.max(), unita)}", r, h - 18f, fd)
    } else {
        c.drawText("min ${fmtValue(vals.min(), unita)}", l, h - 18f, fp)
        c.drawText("max ${fmtValue(vals.max(), unita)}", r, h - 18f, fd)
    }
    return bmp
}

/** Schermata di scelta di stazione e dato, aperta quando si aggiunge o si riconfigura il widget. */
class GraficoWidgetConfigActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val id = intent?.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
            ?: AppWidgetManager.INVALID_APPWIDGET_ID
        setResult(Activity.RESULT_CANCELED, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id))
        if (id == AppWidgetManager.INVALID_APPWIDGET_ID) { finish(); return }
        Repo.init(this)
        setContent {
            CastelSafeTheme {
                Surface(Modifier.statusBarsPadding(), color = MaterialTheme.colorScheme.background) {
                    val scope = rememberCoroutineScope()
                    val iniziale = remember { GraficoPrefs.get(this, id) }
                    var sid by remember { mutableStateOf(iniziale.first) }
                    var tipo by remember { mutableStateOf(iniziale.second) }
                    LaunchedEffect(sid) { runCatching { Repo.loadStazione(sid) } }
                    val serie = Repo.stazione(sid).collectAsState().value
                    val schede = serie.data?.let { SensorData.schede(sid, it) } ?: emptyList()
                    Column(Modifier.padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text("Stazione", style = MaterialTheme.typography.titleMedium)
                        Stations.all.forEach { s ->
                            Row(Modifier.fillMaxWidth().clickable { sid = s.id; tipo = "" }, verticalAlignment = Alignment.CenterVertically) {
                                RadioButton(sid == s.id, onClick = { sid = s.id; tipo = "" })
                                Text(s.name)
                            }
                        }
                        Text("Dato", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 12.dp))
                        if (schede.isEmpty()) Text(if (serie.loading) "Carico i dati…" else "Dati non disponibili", fontSize = 14.sp)
                        schede.forEach { c ->
                            Row(Modifier.fillMaxWidth().clickable { tipo = c.type }, verticalAlignment = Alignment.CenterVertically) {
                                RadioButton(tipo == c.type, onClick = { tipo = c.type })
                                Text(c.label)
                            }
                        }
                        Button(
                            enabled = schede.any { it.type == tipo },
                            onClick = {
                                GraficoPrefs.set(this@GraficoWidgetConfigActivity, id, sid, tipo)
                                scope.launch {
                                    runCatching { Widgets.updateAll(applicationContext) }
                                    setResult(Activity.RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id))
                                    finish()
                                }
                            },
                            modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                        ) { Text("Salva") }
                    }
                }
            }
        }
    }
}
