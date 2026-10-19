package io.github.andreadp271.castelsafe.widget

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
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
import androidx.glance.action.ActionParameters
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.layout.Alignment as GAlignment
import androidx.glance.layout.Box as GBox
import androidx.glance.layout.ContentScale
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.height
import androidx.glance.layout.fillMaxWidth
import androidx.compose.ui.unit.sp
import androidx.glance.layout.width
import io.github.andreadp271.castelsafe.R
import androidx.glance.layout.fillMaxSize
import io.github.andreadp271.castelsafe.data.Api
import io.github.andreadp271.castelsafe.data.Config
import io.github.andreadp271.castelsafe.ui.CastelSafeTheme
import io.github.andreadp271.castelsafe.ui.Radar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.File
import java.time.Instant
import java.time.ZoneId
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.tan

/** Raggio mostrato dal widget Radar, scelto per ogni widget. */
object RadarPrefs {
    val opzioni = listOf(10, 25, 50, 100, 150, 250)
    private fun p(ctx: Context) = ctx.getSharedPreferences("widget_radar", Context.MODE_PRIVATE)
    fun km(ctx: Context, awId: Int) = p(ctx).getInt("w$awId", 50)
    fun salva(ctx: Context, awId: Int, km: Int) = p(ctx).edit().putInt("w$awId", km).apply()
    fun elimina(ctx: Context, ids: IntArray) = p(ctx).edit().apply { ids.forEach { remove("w$it") } }.apply()
}

/** Radar delle precipitazioni (Radar-DPC) sulla stessa mappa dell'app, centrato su Castelfranco Veneto. */
object RadarRender {
    val bitmaps = MutableStateFlow<Map<Int, Bitmap>>(emptyMap())
    /** Ora dell'ultimo fotogramma radar, per widget. */
    val ore = MutableStateFlow<Map<Int, String?>>(emptyMap())

    /** Barra colorata della legenda, nella stessa scala del radar. */
    val legenda: Bitmap by lazy {
        val w = 240; val h = 12
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        Canvas(b).drawRect(0f, 0f, w.toFloat(), h.toFloat(), Paint().apply {
            shader = LinearGradient(0f, 0f, w.toFloat(), 0f, Radar.legendColors().toIntArray(), null, Shader.TileMode.CLAMP)
        })
        b
    }
    private val ultimo = mutableMapOf<Int, Long>()

    private const val Z = 7          // zoom nativo delle tessere radar
    private const val T = 256
    private const val NERO = 0xFF0A0A0A.toInt()

    suspend fun renderAll(ctx: Context, forza: Boolean = false, etaMs: Long = 10 * 60_000L) {
        val ids = AppWidgetManager.getInstance(ctx).getAppWidgetIds(ComponentName(ctx, RadarWidgetReceiver::class.java))
        val ora = System.currentTimeMillis()
        // il radar cambia ogni 5 minuti: non serve scaricare decine di tessere a ogni controllo
        ids.filter { forza || ora - (ultimo[it] ?: 0) > etaMs || bitmaps.value[it] == null }.forEach { render(ctx, it) }
    }

    private val rapporti = mutableMapOf<Int, Float>()
    fun rapporto(awId: Int): Float? = rapporti[awId]

    /** [ratioReale]: proporzioni larghezza/altezza misurate dal widget; senza, si stimano dalle opzioni del launcher. */
    suspend fun render(ctx: Context, awId: Int, ratioReale: Float? = null) {
        val o = AppWidgetManager.getInstance(ctx).getAppWidgetOptions(awId)
        // le dimensioni dipendono dall'orientamento: in verticale larghezza minima e altezza massima, in orizzontale il contrario
        val oriz = ctx.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
        val w = o.getInt(if (oriz) AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH else AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 250).coerceAtLeast(1)
        val h = o.getInt(if (oriz) AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT else AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 250).coerceAtLeast(1)
        val ratio = ((ratioReale ?: (w.toFloat() / h)) * 1.2f).coerceIn(0.6f, 2.6f)
        val bmp = runCatching { disegna(ctx, ratio, RadarPrefs.km(ctx, awId), awId) }.getOrNull() ?: return
        rapporti[awId] = ratio
        bitmaps.value = bitmaps.value + (awId to bmp)
        ultimo[awId] = System.currentTimeMillis()
    }

    private suspend fun tessera(ctx: Context, z: Int, x: Int, y: Int): Bitmap? = withContext(Dispatchers.IO) {
        val f = File(ctx.cacheDir, "wr/b_${z}_${x}_$y.png").apply { parentFile?.mkdirs() }
        if (!f.exists() || System.currentTimeMillis() - f.lastModified() > 30L * 24 * 3600_000) {
            runCatching {
                Api.client.newCall(Request.Builder().url("https://tile.openstreetmap.org/$z/$x/$y.png").build()).execute()
                    .use { r -> if (r.isSuccessful) f.writeBytes(r.body!!.bytes()) }
            }
        }
        runCatching { BitmapFactory.decodeFile(f.path) }.getOrNull()
    }

    private fun pill(c: Canvas, testo: String, x: Float, y: Float, s: Float, destra: Boolean, colore: Int, sfondo: Int = 0xD90A0A0A.toInt()) {
        val tp = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = colore; textSize = 15f * s; isFakeBoldText = true; letterSpacing = 0.06f }
        val w = tp.measureText(testo) + 24f * s
        val h = 30f * s
        val x0 = if (destra) x - w else x
        c.drawRoundRect(RectF(x0, y, x0 + w, y + h), h / 2, h / 2, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = sfondo })
        c.drawText(testo, x0 + 12f * s, y + h / 2 + 5.2f * s, tp)
    }

    private suspend fun disegna(ctx: Context, ratio: Float, km: Int, awId: Int): Bitmap {
        // zoom della mappa di base: il più piccolo che mostra 2 x raggio in almeno ~380 px
        val lat = Config.CENTER_LAT; val lon = Config.CENTER_LON
        val mPerPx0 = 156543.03 * cos(Math.toRadians(lat))
        val zb = (Z..11).firstOrNull { 2000.0 * km * (1 shl it) / mPerPx0 >= 380 } ?: 11
        val mondo = T * (1 shl zb).toDouble()
        fun px(l: Double) = (l + 180) / 360 * mondo
        fun py(l: Double): Double { val r = Math.toRadians(l); return (1 - ln(tan(r) + 1 / cos(r)) / PI) / 2 * mondo }
        val vh = (2000.0 * km * (1 shl zb) / mPerPx0).toInt()
        val vw = (vh * ratio).toInt()
        val left = px(lon) - vw / 2.0; val top = py(lat) - vh / 2.0
        val n = 1 shl zb
        val tx = (floor(left / T).toInt()..floor((left + vw) / T).toInt()).filter { it in 0 until n }
        val ty = (floor(top / T).toInt()..floor((top + vh) / T).toInt()).filter { it in 0 until n }
        val chiavi = tx.flatMap { x -> ty.map { y -> x to y } }
        // le tessere radar sono a zoom 7: ognuna copre 2^(zb-7) tessere della mappa di base per lato
        val sc = 1 shl (zb - Z)
        val chiaviRadar = chiavi.map { (x, y) -> (x / sc) to (y / sc) }.distinct()
        val sem = Semaphore(8)
        val (ultimoRadar, base, radar) = coroutineScope {
            val t = runCatching { Api.radarLast() }.getOrNull()
            val b = chiavi.map { (x, y) -> async { sem.withPermit { (x to y) to tessera(ctx, zb, x, y) } } }
            val r = if (t == null) emptyList() else chiaviRadar.map { (x, y) ->
                async { sem.withPermit { (x to y) to Api.radarTile(t, Z, x, y)?.let(Radar::recolor) } }
            }
            Triple(t, b.awaitAll(), r.awaitAll())
        }
        val k = (760f / vh).coerceIn(1f, 2.4f)
        val bmp = Bitmap.createBitmap((vw * k).toInt(), (vh * k).toInt(), Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.scale(k, k)
        c.drawColor(NERO)
        // mappa smorzata e più scura: il radar risalta
        val smorzato = Paint(Paint.FILTER_BITMAP_FLAG).apply {
            colorFilter = ColorMatrixColorFilter(ColorMatrix().apply {
                setSaturation(0.3f)
                postConcat(ColorMatrix(floatArrayOf(0.8f, 0f, 0f, 0f, 0f, 0f, 0.8f, 0f, 0f, 0f, 0f, 0f, 0.8f, 0f, 0f, 0f, 0f, 0f, 1f, 0f)))
            })
        }
        fun dst(kk: Pair<Int, Int>, m: Int = 1) = RectF((kk.first * T * m - left).toFloat(), (kk.second * T * m - top).toFloat(), ((kk.first + 1) * T * m - left).toFloat(), ((kk.second + 1) * T * m - top).toFloat())
        base.forEach { (kk, bm) -> if (bm != null) c.drawBitmap(bm, null, dst(kk), smorzato) }
        val pr = Paint(Paint.FILTER_BITMAP_FLAG).apply { alpha = 235 }
        radar.forEach { (kk, bm) -> if (bm != null) c.drawBitmap(bm, null, dst(kk, sc), pr) }

        // Castelfranco Veneto: un puntino al centro
        val mx = vw / 2f; val my = vh / 2f
        val s = vh / 468f
        val ora = ultimoRadar?.let { Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).toLocalTime().toString().take(5) }
        c.drawCircle(mx, my, 8f * s, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = NERO })
        c.drawCircle(mx, my, 5f * s, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt() })

        ore.value = ore.value + (awId to ora)
        return bmp
    }
}

class RadarWidget : GlanceAppWidget() {
    override val sizeMode = androidx.glance.appwidget.SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val awId = GlanceAppWidgetManager(context).getAppWidgetId(id)
        if (RadarRender.bitmaps.value[awId] == null) RadarRender.render(context, awId)
        provideContent {
            val bmp = RadarRender.bitmaps.collectAsState().value[awId]
            // le proporzioni vere del widget si conoscono solo qui: se l'immagine non corrisponde, la si rifà
            val dim = androidx.glance.LocalSize.current
            val r = ((dim.width.value / dim.height.value.coerceAtLeast(1f)) * 1.2f).coerceIn(0.6f, 2.6f)
            androidx.compose.runtime.LaunchedEffect(Math.round(r * 25)) {
                val attuale = RadarRender.rapporto(awId)
                if (attuale == null || kotlin.math.abs(attuale - r) / r > 0.04f) RadarRender.render(context, awId, r)
            }
            if (bmp != null) {
                GBox(GlanceModifier.fillMaxSize(), contentAlignment = GAlignment.TopEnd) {
                    Image(
                        ImageProvider(bmp), contentDescription = "Radar delle precipitazioni attorno a Castelfranco Veneto",
                        modifier = GlanceModifier.fillMaxSize()
                            .background(androidx.glance.color.ColorProvider(androidx.compose.ui.graphics.Color(0xFF0A0A0A), androidx.compose.ui.graphics.Color(0xFF0A0A0A)))
                            .cornerRadius(30.dp).clickable(Widgets.open(context, "mappa")),
                        contentScale = ContentScale.Crop,
                    )
                    val km = RadarPrefs.km(context, awId)
                    val ora = RadarRender.ore.collectAsState().value[awId]
                    val stretto = dim.width.value < 260
                    val nero = androidx.glance.color.ColorProvider(androidx.compose.ui.graphics.Color(0xD90A0A0A), androidx.compose.ui.graphics.Color(0xD90A0A0A))
                    fun st(c: Long, sp: Int) = androidx.glance.text.TextStyle(
                        color = androidx.glance.color.ColorProvider(androidx.compose.ui.graphics.Color(c), androidx.compose.ui.graphics.Color(c)),
                        fontSize = sp.sp, fontWeight = androidx.glance.text.FontWeight.Bold,
                    )
                    // titolo, raggio e ora in alto a sinistra
                    GBox(GlanceModifier.fillMaxSize().padding(start = 12.dp, top = 12.dp), contentAlignment = GAlignment.TopStart) {
                        GBox(GlanceModifier.height(34.dp).cornerRadius(17.dp).background(nero).padding(horizontal = 14.dp), contentAlignment = GAlignment.Center) {
                            androidx.glance.text.Text("RADAR $km km · ${ora ?: "n.d."}", style = st(0xFFFFFFFF, 13), maxLines = 1)
                        }
                    }
                    // legenda e fonte in basso
                    GBox(GlanceModifier.fillMaxSize().padding(start = 12.dp, bottom = 12.dp, end = 12.dp), contentAlignment = GAlignment.BottomStart) {
                        androidx.glance.layout.Row(GlanceModifier.fillMaxWidth(), verticalAlignment = GAlignment.Vertical.CenterVertically) {
                            androidx.glance.layout.Row(
                                GlanceModifier.height(30.dp).cornerRadius(15.dp).background(nero).padding(horizontal = 12.dp),
                                verticalAlignment = GAlignment.Vertical.CenterVertically,
                            ) {
                                androidx.glance.text.Text("debole", style = st(0xFFB6C0CC, 10))
                                androidx.glance.layout.Spacer(GlanceModifier.width(6.dp))
                                Image(ImageProvider(RadarRender.legenda), null, GlanceModifier.width(if (stretto) 54.dp else 90.dp).height(8.dp))
                                androidx.glance.layout.Spacer(GlanceModifier.width(6.dp))
                                androidx.glance.text.Text("forte", style = st(0xFFB6C0CC, 10))
                            }
                            androidx.glance.layout.Spacer(GlanceModifier.defaultWeight())
                            GBox(GlanceModifier.height(22.dp).cornerRadius(11.dp).background(nero).padding(horizontal = 10.dp), contentAlignment = GAlignment.Center) {
                                androidx.glance.text.Text(if (stretto) "© OSM" else "© OpenStreetMap · Radar-DPC", style = st(0xFFB6C0CC, 9), maxLines = 1)
                            }
                        }
                    }
                    // aggiorna il radar adesso, senza aprire l'app
                    GBox(GlanceModifier.padding(top = 12.dp, end = 12.dp)) {
                        GBox(
                            GlanceModifier.size(34.dp).cornerRadius(17.dp)
                                .background(androidx.glance.color.ColorProvider(androidx.compose.ui.graphics.Color(0xD90A0A0A), androidx.compose.ui.graphics.Color(0xD90A0A0A)))
                                .clickable(actionRunCallback<RefreshRadarAction>()),
                            contentAlignment = GAlignment.Center,
                        ) {
                            Image(ImageProvider(R.drawable.ic_widget_refresh), "Aggiorna il radar", GlanceModifier.size(20.dp))
                        }
                    }
                }
            }
        }
    }
}

/** Tocco sul tasto di aggiornamento: scarica subito l'ultimo fotogramma. */
class RefreshRadarAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        RadarRender.render(context, GlanceAppWidgetManager(context).getAppWidgetId(glanceId))
        RadarWidget().update(context, glanceId)
    }
}

class RadarWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = RadarWidget()

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        super.onDeleted(context, appWidgetIds)
        RadarPrefs.elimina(context, appWidgetIds)
    }

    /** Ridimensionato dall'utente: l'immagine va rifatta con le nuove proporzioni. */
    override fun onAppWidgetOptionsChanged(context: Context, appWidgetManager: AppWidgetManager, appWidgetId: Int, newOptions: Bundle) {
        super.onAppWidgetOptionsChanged(context, appWidgetManager, appWidgetId, newOptions)
        // goAsync() può restituire null quando il metodo non arriva da un broadcast
        val pending: android.content.BroadcastReceiver.PendingResult? = goAsync()
        kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch {
            try { runCatching { RadarRender.render(context.applicationContext, appWidgetId) } } finally { pending?.finish() }
        }
    }
}

/** Scelta del raggio, aperta quando si aggiunge o si riconfigura il widget. */
class RadarWidgetConfigActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val id = intent?.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
            ?: AppWidgetManager.INVALID_APPWIDGET_ID
        setResult(Activity.RESULT_CANCELED, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id))
        if (id == AppWidgetManager.INVALID_APPWIDGET_ID) { finish(); return }
        setContent {
            CastelSafeTheme {
                Surface(Modifier.statusBarsPadding(), color = MaterialTheme.colorScheme.background) {
                    val scope = rememberCoroutineScope()
                    var km by remember { mutableIntStateOf(RadarPrefs.km(this, id)) }
                    Column(Modifier.padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text("Raggio del radar", style = MaterialTheme.typography.titleLarge)
                        Text("Quanta strada attorno a Castelfranco Veneto mostrare.", style = MaterialTheme.typography.bodyMedium)
                        RadarPrefs.opzioni.forEach { o ->
                            Row(Modifier.fillMaxWidth().clickable { km = o }, verticalAlignment = Alignment.CenterVertically) {
                                RadioButton(km == o, onClick = { km = o })
                                Text(
                                    "$o km" + when (o) {
                                        10 -> " · Castelfranco e i paesi vicini"
                                        25 -> " · fino a Bassano e Treviso"
                                        50 -> " · fino a Padova, Vicenza e Venezia"
                                        100 -> " · da Belluno a Rovigo, fino a Udine"
                                        150 -> " · Trento, Trieste e Bologna"
                                        else -> " · da Milano a Firenze"
                                    },
                                )
                            }
                        }
                        Button(
                            onClick = {
                                RadarPrefs.salva(this@RadarWidgetConfigActivity, id, km)
                                scope.launch {
                                    runCatching { RadarRender.render(applicationContext, id) }
                                    runCatching { Widgets.updateAll(applicationContext) }
                                    setResult(Activity.RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id))
                                    finish()
                                }
                            },
                            modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
                        ) { Text("Salva") }
                    }
                }
            }
        }
    }
}
