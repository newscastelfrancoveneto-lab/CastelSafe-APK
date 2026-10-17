package io.github.andreadp271.castelsafe.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Point
import android.graphics.Rect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.andreadp271.castelsafe.data.Api
import io.github.andreadp271.castelsafe.data.Repo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Overlay
import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.sinh
import kotlin.math.tan

/**
 * Radar precipitazioni Radar-DPC (VMI), come nella PWA: tessere WebP a zoom nativo 7,
 * decodificate (dBZ = R/2 - 19) e ricolorate con la stessa scala, 12 fotogrammi da 5 minuti.
 */
object Radar {
    const val Z = 7
    const val STEP = 5 * 60_000L
    const val FRAMES = 12

    private val stops = listOf(
        10.0 to intArrayOf(90, 190, 255), 25.0 to intArrayOf(0, 205, 150), 35.0 to intArrayOf(45, 195, 45),
        45.0 to intArrayOf(240, 230, 50), 52.0 to intArrayOf(250, 150, 30), 58.0 to intArrayOf(225, 45, 45), 65.0 to intArrayOf(185, 45, 185),
    )
    val legendLabels = listOf(10, 25, 35, 45, 55, 65)

    fun palette(v: Double): Int {
        val c = when {
            v <= stops.first().first -> stops.first().second
            v >= stops.last().first -> stops.last().second
            else -> {
                val i = stops.indexOfFirst { v <= it.first }
                val (a, ca) = stops[i - 1]; val (b, cb) = stops[i]
                val f = (v - a) / (b - a)
                IntArray(3) { k -> (ca[k] + f * (cb[k] - ca[k])).toInt() }
            }
        }
        return (0xFF shl 24) or (c[0] shl 16) or (c[1] shl 8) or c[2]
    }

    /** Colori di riferimento per la legenda (da 10 a 65 dBZ). */
    fun legendColors(): List<Int> = (0..20).map { palette(10 + 55.0 * it / 20) }

    // ── stato osservato dall'interfaccia ─────────────────────────────────────
    var frames by mutableStateOf<List<Long>>(emptyList())
    var current by mutableIntStateOf(0)
    var opacity by mutableFloatStateOf(0.8f)
    var playing by mutableStateOf(false)
    var loading by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)

    /** time → (tile → bitmap ricolorata) */
    val tiles = java.util.concurrent.ConcurrentHashMap<Long, Map<Triple<Int, Int, Int>, Bitmap>>()

    fun tileX(lon: Double, z: Int = Z) = floor((lon + 180) / 360 * (1 shl z)).toInt().coerceIn(0, (1 shl z) - 1)
    fun tileY(lat: Double, z: Int = Z): Int {
        val r = Math.toRadians(lat.coerceIn(-85.0, 85.0))
        return floor((1 - ln(tan(r) + 1 / kotlin.math.cos(r)) / PI) / 2 * (1 shl z)).toInt().coerceIn(0, (1 shl z) - 1)
    }
    fun lonOf(x: Int, z: Int = Z) = x.toDouble() / (1 shl z) * 360 - 180
    fun latOf(y: Int, z: Int = Z) = Math.toDegrees(atan(sinh(PI * (1 - 2.0 * y / (1 shl z)))))

    /** Zoom delle tessere per lo zoom della mappa: nativo fino a 7, come maxNativeZoom della PWA. */
    fun tileZoom(mapZoom: Double) = floor(mapZoom).toInt().coerceIn(5, Z)

    private fun recolor(bytes: ByteArray): Bitmap? {
        val src = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
        val w = src.width; val h = src.height
        val px = IntArray(w * h)
        src.getPixels(px, 0, w, 0, 0, w, h)
        for (i in px.indices) {
            val a = px[i] ushr 24; val r = (px[i] shr 16) and 0xFF
            px[i] = if (a == 0 || r == 0) 0 else palette(r / 2.0 - 19)
        }
        return Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
    }

    /**
     * Aggiorna i fotogrammi per le tessere [keys] (z, x, y) dell'area inquadrata; scarica solo quelle
     * mancanti, prima l'ultimo fotogramma (visibile subito) e poi il resto dell'animazione.
     */
    suspend fun refresh(keys: List<Triple<Int, Int, Int>>, onFrame: () -> Unit = {}) {
        loading = true
        try {
            val last = Api.radarLast()
            val wanted = (FRAMES - 1 downTo 0).map { last - it * STEP }
            tiles.keys.filter { it !in wanted }.forEach { tiles.remove(it) }
            val wasLast = frames.isEmpty() || current >= frames.size - 1
            frames = wanted
            if (wasLast) current = wanted.size - 1
            val sem = Semaphore(8)
            suspend fun load(t: Long) = coroutineScope {
                val have = tiles[t].orEmpty()
                val missing = keys.filter { it !in have }
                if (missing.isEmpty()) return@coroutineScope
                val got = missing.map { k -> async(Dispatchers.IO) { sem.withPermit { k to Api.radarTile(t, k.first, k.second, k.third)?.let(::recolor) } } }.awaitAll()
                tiles[t] = tiles[t].orEmpty() + got.mapNotNull { (k, b) -> b?.let { k to it } }
            }
            load(last)
            onFrame()
            coroutineScope { wanted.dropLast(1).map { t -> async { load(t) } }.awaitAll() }
            onFrame()
            error = null
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            error = "Radar non disponibile: ${Repo.errorText(e)}"
        } finally {
            loading = false // anche se interrotto da uno spostamento della mappa
        }
    }

    fun clear() { tiles.clear(); frames = emptyList(); current = 0; playing = false; error = null }
}

/** Disegna il fotogramma radar corrente sopra la mappa. */
class RadarOverlay : Overlay() {
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val p1 = Point(); private val p2 = Point(); private val dst = Rect()

    override fun draw(c: Canvas, map: MapView, shadow: Boolean) {
        if (shadow) return
        val t = Radar.frames.getOrNull(Radar.current) ?: return
        val set = Radar.tiles[t] ?: return
        paint.alpha = (Radar.opacity * 255).toInt()
        val proj = map.projection
        // solo le tessere dello zoom corrente; finché non arrivano, quelle già scaricate a un altro zoom
        val z = Radar.tileZoom(map.zoomLevelDouble)
        val current = set.filterKeys { it.first == z }
        for ((k, bmp) in current.ifEmpty { set }) {
            proj.toPixels(GeoPoint(Radar.latOf(k.third, k.first), Radar.lonOf(k.second, k.first)), p1)
            proj.toPixels(GeoPoint(Radar.latOf(k.third + 1, k.first), Radar.lonOf(k.second + 1, k.first)), p2)
            dst.set(p1.x, p1.y, p2.x, p2.y)
            c.drawBitmap(bmp, null, dst, paint)
        }
    }
}
