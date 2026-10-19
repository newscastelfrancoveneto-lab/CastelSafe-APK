package io.github.andreadp271.castelsafe.widget

import android.appwidget.AppWidgetManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import androidx.compose.runtime.collectAsState
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
import io.github.andreadp271.castelsafe.ui.Radar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
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

/** Radar delle precipitazioni (Radar-DPC) sulla stessa mappa dell'app, centrato su Castelfranco Veneto. */
object RadarRender {
    val bitmaps = MutableStateFlow<Map<Int, Bitmap>>(emptyMap())
    private val ultimo = mutableMapOf<Int, Long>()

    private const val Z = 7                          // zoom nativo delle tessere radar
    private const val ZB = 9                         // zoom della mappa di base: 50 km attorno a noi
    private const val T = 256
    private const val MONDO = T * (1 shl ZB)         // pixel del mondo a zoom ZB
    private const val ALTEZZA = 468                  // pixel di mappa in altezza a zoom 9: circa 100 km
    private const val K = 1.6f                         // definizione dell'immagine rispetto ai pixel di mappa
    private val LON = io.github.andreadp271.castelsafe.data.Config.CENTER_LON
    private val LAT = io.github.andreadp271.castelsafe.data.Config.CENTER_LAT

    private fun px(lon: Double) = (lon + 180) / 360 * MONDO
    private fun py(lat: Double): Double {
        val r = Math.toRadians(lat)
        return (1 - ln(tan(r) + 1 / cos(r)) / PI) / 2 * MONDO
    }

    suspend fun renderAll(ctx: Context, forza: Boolean = false) {
        val ids = AppWidgetManager.getInstance(ctx).getAppWidgetIds(android.content.ComponentName(ctx, RadarWidgetReceiver::class.java))
        val ora = System.currentTimeMillis()
        // il radar cambia ogni 5 minuti: non serve scaricare 40 tessere a ogni controllo
        ids.filter { forza || ora - (ultimo[it] ?: 0) > 10 * 60_000 || bitmaps.value[it] == null }.forEach { render(ctx, it) }
    }

    suspend fun render(ctx: Context, awId: Int) {
        val o = AppWidgetManager.getInstance(ctx).getAppWidgetOptions(awId)
        val w = o.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 250).coerceAtLeast(1)
        val h = o.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 250).coerceAtLeast(1)
        val ratio = (w.toFloat() / h).coerceIn(0.75f, 1.7f)
        val bmp = runCatching { disegna(ctx, ratio) }.getOrNull() ?: return
        bitmaps.value = bitmaps.value + (awId to bmp)
        ultimo[awId] = System.currentTimeMillis()
    }

    private suspend fun tessera(ctx: Context, x: Int, y: Int): Bitmap? = withContext(Dispatchers.IO) {
        val f = File(ctx.cacheDir, "wr/b_${ZB}_${x}_$y.png").apply { parentFile?.mkdirs() }
        if (!f.exists() || System.currentTimeMillis() - f.lastModified() > 30L * 24 * 3600_000) {
            runCatching {
                Api.client.newCall(Request.Builder().url("https://tile.openstreetmap.org/$ZB/$x/$y.png").build()).execute()
                    .use { r -> if (r.isSuccessful) f.writeBytes(r.body!!.bytes()) }
            }
        }
        runCatching { BitmapFactory.decodeFile(f.path) }.getOrNull()
    }

    private suspend fun disegna(ctx: Context, ratio: Float): Bitmap {
        val vh = ALTEZZA; val vw = (ALTEZZA * ratio).toInt()
        val left = px(LON) - vw / 2.0; val top = py(LAT) - vh / 2.0
        val tx = (floor(left / T).toInt()..floor((left + vw) / T).toInt()).filter { it in 0 until (1 shl ZB) }
        val ty = (floor(top / T).toInt()..floor((top + vh) / T).toInt()).filter { it in 0 until (1 shl ZB) }
        val chiavi = tx.flatMap { x -> ty.map { y -> x to y } }
        // le tessere radar sono a zoom 7: ognuna copre 4×4 tessere della mappa di base
        val sc = 1 shl (ZB - Z)
        val chiaviRadar = chiavi.map { (x, y) -> (x / sc) to (y / sc) }.distinct()
        val sem = Semaphore(8)
        val (ultimoRadar, base, radar) = coroutineScope {
            val t = runCatching { Api.radarLast() }.getOrNull()
            val b = chiavi.map { (x, y) -> async { sem.withPermit { (x to y) to tessera(ctx, x, y) } } }
            val r = if (t == null) emptyList() else chiaviRadar.map { (x, y) ->
                async { sem.withPermit { (x to y) to Api.radarTile(t, Z, x, y)?.let(Radar::recolor) } }
            }
            Triple(t, b.awaitAll(), r.awaitAll())
        }
        val bmp = Bitmap.createBitmap((vw * K).toInt(), (vh * K).toInt(), Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.scale(K, K)
        c.drawColor(0xFF0A0A0A.toInt())
        val smorzato = Paint(Paint.FILTER_BITMAP_FLAG).apply {
            colorFilter = ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(0.55f) })
            alpha = 255
        }
        fun dst(k: Pair<Int, Int>, n: Int = 1) = RectF((k.first * T * n - left).toFloat(), (k.second * T * n - top).toFloat(), ((k.first + 1) * T * n - left).toFloat(), ((k.second + 1) * T * n - top).toFloat())
        base.forEach { (k, bm) -> if (bm != null) c.drawBitmap(bm, null, dst(k), smorzato) }
        val pr = Paint(Paint.FILTER_BITMAP_FLAG).apply { alpha = 225 }
        radar.forEach { (k, bm) -> if (bm != null) c.drawBitmap(bm, null, dst(k, sc), pr) }

        // Castelfranco Veneto
        val mx = (px(11.9477) - left).toFloat(); val my = (py(45.6949) - top).toFloat()
        // cerchi a 25 e 50 km per leggere la distanza della pioggia
        val mPerPx = 156543.03 * cos(Math.toRadians(LAT)) / (1 shl ZB)
        val anello = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = 0xFF0A0A0A.toInt(); strokeWidth = 1.4f; alpha = 150; pathEffect = android.graphics.DashPathEffect(floatArrayOf(6f, 5f), 0f) }
        val etichetta = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF0A0A0A.toInt(); textSize = 13f; isFakeBoldText = true; textAlign = Paint.Align.CENTER }
        listOf(25, 50).forEach { km ->
            val rr = (km * 1000 / mPerPx).toFloat()
            c.drawCircle(mx, my, rr, anello)
            c.drawText("$km km", mx, my - rr - 3f, etichetta)
        }
        c.drawCircle(mx, my, 11f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF0A0A0A.toInt() })
        c.drawCircle(mx, my, 7f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt() })

        // fascia in alto: titolo e ora del radar
        val s = vw / 560f * 1.15f
        val fasciaA = 62f * s
        c.drawRect(0f, 0f, vw.toFloat(), fasciaA, Paint().apply { color = 0xE60A0A0A.toInt() })
        val t1 = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt(); textSize = 26f * s; isFakeBoldText = true; letterSpacing = 0.1f }
        c.drawText("RADAR PIOGGIA · CASTELFRANCO", 24f * s, 40f * s, t1)
        val ora = ultimoRadar?.let { Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).toLocalTime().toString().take(5) }
        val t2 = Paint(t1).apply { textAlign = Paint.Align.RIGHT; color = 0xFF38BDF8.toInt() }
        c.drawText(if (ora != null) "ore $ora" else "non disponibile", vw - 24f * s, 40f * s, t2)

        // fascia in basso: legenda e fonte
        val fasciaB = 52f * s
        c.drawRect(0f, vh - fasciaB, vw.toFloat(), vh.toFloat(), Paint().apply { color = 0xE60A0A0A.toInt() })
        val cols = Radar.legendColors().toIntArray()
        val lx = 24f * s; val lw = 220f * s; val ly = vh - 30f * s
        c.drawRect(lx, ly, lx + lw, ly + 12f * s, Paint().apply {
            shader = LinearGradient(lx, 0f, lx + lw, 0f, cols, null, Shader.TileMode.CLAMP)
        })
        val tl = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFB6C0CC.toInt(); textSize = 17f * s; isFakeBoldText = true }
        c.drawText("debole", lx, vh - 8f * s, tl)
        c.drawText("forte", lx + lw - tl.measureText("forte"), vh - 8f * s, tl)
        tl.textAlign = Paint.Align.RIGHT
        c.drawText("Radar-DPC · © OpenStreetMap", vw - 24f * s, vh - 14f * s, tl)
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
                    modifier = GlanceModifier.fillMaxSize().background(androidx.glance.color.ColorProvider(androidx.compose.ui.graphics.Color(0xFF0A0A0A), androidx.compose.ui.graphics.Color(0xFF0A0A0A)))
                        .cornerRadius(30.dp).clickable(Widgets.open(context, "mappa")),
                    contentScale = ContentScale.Crop,
                )
            }
        }
    }
}

class RadarWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = RadarWidget()
}
