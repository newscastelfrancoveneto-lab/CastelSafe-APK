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
import androidx.glance.layout.ContentScale
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
    private val ultimo = mutableMapOf<Int, Long>()

    private const val Z = 7          // zoom nativo delle tessere radar
    private const val T = 256
    private const val NERO = 0xFF0A0A0A.toInt()

    suspend fun renderAll(ctx: Context, forza: Boolean = false) {
        val ids = AppWidgetManager.getInstance(ctx).getAppWidgetIds(ComponentName(ctx, RadarWidgetReceiver::class.java))
        val ora = System.currentTimeMillis()
        // il radar cambia ogni 5 minuti: non serve scaricare decine di tessere a ogni controllo
        ids.filter { forza || ora - (ultimo[it] ?: 0) > 10 * 60_000 || bitmaps.value[it] == null }.forEach { render(ctx, it) }
    }

    suspend fun render(ctx: Context, awId: Int) {
        val o = AppWidgetManager.getInstance(ctx).getAppWidgetOptions(awId)
        val w = o.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 250).coerceAtLeast(1)
        val h = o.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 250).coerceAtLeast(1)
        val ratio = (w.toFloat() / h).coerceIn(0.75f, 1.7f)
        val bmp = runCatching { disegna(ctx, ratio, RadarPrefs.km(ctx, awId)) }.getOrNull() ?: return
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

    private suspend fun disegna(ctx: Context, ratio: Float, km: Int): Bitmap {
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
        c.drawCircle(mx, my, 8f * s, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = NERO })
        c.drawCircle(mx, my, 5f * s, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt() })

        // etichette: titolo e ora in alto a sinistra, raggio in alto a destra
        val ora = ultimoRadar?.let { Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).toLocalTime().toString().take(5) }
        pill(c, "RADAR  " + (ora?.let { "ore $it" } ?: "non disponibile"), 14f * s, 14f * s, s, false, 0xFFFFFFFF.toInt())
        pill(c, "$km km", vw - 14f * s, 14f * s, s, true, 0xFF38BDF8.toInt())

        // legenda in basso a sinistra, fonte a destra
        val lh = 30f * s; val ly = vh - 14f * s - lh
        val lw = 220f * s
        c.drawRoundRect(RectF(14f * s, ly, 14f * s + lw, ly + lh), lh / 2, lh / 2, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xD90A0A0A.toInt() })
        val tl = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFB6C0CC.toInt(); textSize = 12f * s; isFakeBoldText = true }
        val gx = 14f * s + 14f * s + tl.measureText("debole") + 6f * s
        val gw = lw - 28f * s - tl.measureText("debole") - tl.measureText("forte") - 12f * s
        c.drawText("debole", 28f * s, ly + lh / 2 + 4.2f * s, tl)
        c.drawRect(gx, ly + lh / 2 - 4f * s, gx + gw, ly + lh / 2 + 4f * s, Paint().apply {
            shader = LinearGradient(gx, 0f, gx + gw, 0f, Radar.legendColors().toIntArray(), null, Shader.TileMode.CLAMP)
        })
        c.drawText("forte", gx + gw + 6f * s, ly + lh / 2 + 4.2f * s, tl)
        pill(c, "© OpenStreetMap · Radar-DPC", vw - 14f * s, ly, s, true, 0xFFB6C0CC.toInt(), 0xB30A0A0A.toInt())
        return bmp
    }
}

class RadarWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val awId = GlanceAppWidgetManager(context).getAppWidgetId(id)
        if (RadarRender.bitmaps.value[awId] == null) RadarRender.render(context, awId)
        provideContent {
            val bmp = RadarRender.bitmaps.collectAsState().value[awId]
            if (bmp != null) {
                Image(
                    ImageProvider(bmp), contentDescription = "Radar delle precipitazioni attorno a Castelfranco Veneto",
                    modifier = GlanceModifier.fillMaxSize()
                        .background(androidx.glance.color.ColorProvider(androidx.compose.ui.graphics.Color(0xFF0A0A0A), androidx.compose.ui.graphics.Color(0xFF0A0A0A)))
                        .cornerRadius(30.dp).clickable(Widgets.open(context, "mappa")),
                    contentScale = ContentScale.Crop,
                )
            }
        }
    }
}

class RadarWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = RadarWidget()

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        super.onDeleted(context, appWidgetIds)
        RadarPrefs.elimina(context, appWidgetIds)
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
                                    "$o km" + when (o) { 10 -> " · il quartiere"; 25 -> " · la zona"; 50 -> " · la provincia"; 100 -> " · il Veneto centrale"; 150 -> " · il Nord-Est"; else -> " · il Nord Italia" },
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
