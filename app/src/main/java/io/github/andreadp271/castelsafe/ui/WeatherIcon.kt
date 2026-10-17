package io.github.andreadp271.castelsafe.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.unit.Dp
import io.github.andreadp271.castelsafe.data.Scadenza
import java.time.LocalTime
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Icone meteo animate della PWA (weatherIconSvg in index.html), ridisegnate in Compose
 * con le stesse forme, colori e tempi delle animazioni CSS.
 */
enum class MeteoIcon { SERENO, SERENO_NOTTE, POCO_NUVOLOSO, POCO_NUVOLOSO_NOTTE, NUVOLOSO, PIOGGIA, TEMPORALE, NEVE, NEBBIA }

fun meteoCategory(cielo: String, prec: String): MeteoIcon {
    val t = "$cielo $prec".lowercase()
    return when {
        "temporal" in t -> MeteoIcon.TEMPORALE
        "neve" in t -> MeteoIcon.NEVE
        "nebbia" in t || "foschia" in t -> MeteoIcon.NEBBIA
        Regex("rovesci|pioggia|piovosc").containsMatchIn(t) -> MeteoIcon.PIOGGIA
        "poco nuvoloso" in t || "variabile" in t || "schiarit" in t -> MeteoIcon.POCO_NUVOLOSO
        "coperto" in t || "nuvoloso" in t -> MeteoIcon.NUVOLOSO
        "sereno" in t -> MeteoIcon.SERENO
        else -> MeteoIcon.POCO_NUVOLOSO
    }
}

/** Di notte (prima delle 6 e dalle 21) sole → luna, come nightAwareCategory della PWA. */
fun MeteoIcon.nightAware(now: LocalTime = LocalTime.now()): MeteoIcon {
    val night = now.hour < 6 || now.hour >= 21
    return when {
        !night -> this
        this == MeteoIcon.SERENO -> MeteoIcon.SERENO_NOTTE
        this == MeteoIcon.POCO_NUVOLOSO -> MeteoIcon.POCO_NUVOLOSO_NOTTE
        else -> this
    }
}

fun Scadenza.probInt(): Int? = probabilita.removeSuffix("%").trim().toIntOrNull()

private fun dropCount(prob: Int?, lo: Int, hi: Int): Int {
    if (prob == null) return ((lo + hi) / 2.0).roundToInt()
    val t = prob.coerceIn(0, 100) / 100.0
    return (lo + t * (hi - lo)).roundToInt().coerceIn(lo, hi)
}

private val sunBrush = listOf(Color(0xFFFDE68A), Color(0xFFFBBF24), Color(0xFFF59E0B))
private val moonBrush = listOf(Color(0xFFF1F5F9), Color(0xFFCBD5E1), Color(0xFF94A3B8))
private val cloudLight = listOf(Color(0xFFF1F5F9), Color(0xFFCBD5E1))
private val cloudDark = listOf(Color(0xFF94A3B8), Color(0xFF64748B))
private val drop = listOf(Color(0xFF60A5FA), Color(0xFF2563EB))

@Composable
fun WeatherIcon(icon: MeteoIcon, size: Dp, prob: Int? = null, modifier: Modifier = Modifier) {
    val tr = rememberInfiniteTransition(label = "meteo")
    // un'unica fase 0..1 per ogni durata delle animazioni CSS
    fun cyc(ms: Int) = infiniteRepeatable<Float>(tween(ms, easing = LinearEasing))
    val p20 by tr.animateFloat(0f, 1f, cyc(20000), label = "spin")
    val p32 by tr.animateFloat(0f, 1f, cyc(3200), label = "pulse")
    val p34 by tr.animateFloat(0f, 1f, cyc(3400), label = "sway")
    val p9 by tr.animateFloat(0f, 1f, cyc(9000), label = "wisp")
    val p105 by tr.animateFloat(0f, 1f, cyc(1050), label = "rain")
    val p26 by tr.animateFloat(0f, 1f, cyc(2600), label = "snow")
    val p36 by tr.animateFloat(0f, 1f, cyc(3600), label = "bolt")
    val p40 by tr.animateFloat(0f, 1f, cyc(4000), label = "moon")
    val p22 by tr.animateFloat(0f, 1f, cyc(2200), label = "star")

    Canvas(modifier.size(size)) {
        // viewBox "-6 -6 60 60"
        val k = this.size.minDimension / 60f
        scale(k, k, Offset.Zero) {
            translate(6f, 6f) {
                fun pulse(p: Float) = (1 - cos(2 * PI * p).toFloat()) / 2 // 0→1→0
                fun sun(cx: Float, cy: Float, r: Float, rayLen: Float, rays: Int) {
                    rotate(p20 * 360f, Offset(cx, cy)) {
                        for (i in 0 until rays) {
                            val a = 2 * PI * i / rays
                            drawLine(
                                Color(0xFFF59E0B),
                                Offset(cx + cos(a).toFloat() * rayLen, cy + sin(a).toFloat() * rayLen),
                                Offset(cx + cos(a).toFloat() * (rayLen + 6), cy + sin(a).toFloat() * (rayLen + 6)),
                                2.4f, StrokeCap.Round,
                            )
                        }
                    }
                    val s = 1f + 0.1f * pulse(p32)
                    scale(s, s, Offset(cx, cy)) {
                        drawCircle(Color(0x55FBBF24), r * 1.25f, Offset(cx, cy))
                        drawCircle(Brush.radialGradient(sunBrush, Offset(cx - r * 0.3f, cy - r * 0.4f), r * 1.5f), r, Offset(cx, cy))
                    }
                }
                fun cloud(colors: List<Color>, y: Float) {
                    val b = Brush.verticalGradient(colors, y - 16, y + 12)
                    drawRoundRect(b, Offset(6f, y), Size(32f, 12f), CornerRadius(6f))
                    drawCircle(b, 7f, Offset(14f, y))
                    drawCircle(b, 7f, Offset(33f, y))
                    val dx = -1.6f + 3.2f * pulse(p34)
                    drawCircle(b, 10f, Offset(23f + dx, y - 6))
                }
                fun wisp(y: Float) {
                    // attraversa da -26 a +46 con dissolvenza ai bordi
                    val x = -26f + 72f * p9
                    val peak = 0.7f
                    val a = when { p9 < 0.12f -> p9 / 0.12f * peak; p9 > 0.88f -> (1 - p9) / 0.12f * peak; else -> peak }
                    // forme opache su un livello unico, poi trasparenza applicata all'insieme:
                    // dove i cerchi si sovrappongono non risultano più scuri (come l'opacity del gruppo SVG)
                    val c = Color(0xFFCBD5E1)
                    val layer = androidx.compose.ui.graphics.Paint().apply { alpha = a }
                    drawContext.canvas.saveLayer(androidx.compose.ui.geometry.Rect(x - 1f, y - 9f, x + 20f, y + 8f), layer)
                    translate(x, 0f) {
                        drawRoundRect(c, Offset(0f, y), Size(17f, 6.5f), CornerRadius(3.2f))
                        drawCircle(c, 3.6f, Offset(4f, y)); drawCircle(c, 5f, Offset(9f, y - 3)); drawCircle(c, 3.6f, Offset(14.5f, y))
                    }
                    drawContext.canvas.restore()
                }
                fun spread(n: Int, a: Float, b: Float) = (0 until n).map { if (n == 1) (a + b) / 2 else a + (b - a) / (n - 1) * it }
                fun rain(n: Int, y0: Float, y1: Float, a: Float = 13f, b: Float = 33f) {
                    spread(n, a, b).forEachIndexed { i, x ->
                        val p = ((p105 - i * 0.16f / 1.05f) % 1f + 1f) % 1f
                        val ty = -3f + 10f * p
                        val alpha = when { p < 0.18f -> p / 0.18f; p > 0.7f -> 0.9f * (1 - p) / 0.3f; else -> 1f }
                        drawLine(Brush.verticalGradient(drop, y0 + ty, y1 + ty), Offset(x, y0 + ty), Offset(x - 1.5f, y1 + ty), 2.6f, StrokeCap.Round, alpha = alpha.coerceIn(0f, 1f))
                    }
                }
                fun moon(cx: Float, cy: Float, r: Float, stars: List<Triple<Float, Float, Float>>) {
                    val s = 1f + 0.05f * pulse(p40)
                    scale(s, s, Offset(cx, cy)) {
                        val outer = Path().apply { addOval(Rect(Offset(cx, cy), r)) }
                        val bite = Path().apply { addOval(Rect(Offset(cx + r * 0.55f, cy - r * 0.45f), r * 0.78f)) }
                        val path = Path().apply { op(outer, bite, androidx.compose.ui.graphics.PathOperation.Difference) }
                        drawPath(path, Brush.radialGradient(moonBrush, Offset(cx - r * 0.3f, cy - r * 0.3f), r * 1.4f))
                    }
                    stars.forEachIndexed { i, (x, y, rr) ->
                        val p = pulse(((p22 - i * 0.6f / 2.2f) % 1f + 1f) % 1f)
                        drawCircle(Color(0xFFF8FAFC).copy(alpha = 0.25f + 0.75f * p), rr * (0.8f + 0.35f * p), Offset(x, y))
                    }
                }

                when (icon) {
                    MeteoIcon.SERENO -> sun(24f, 24f, 11f, 12f, 8)
                    MeteoIcon.SERENO_NOTTE -> moon(23f, 22f, 11f, listOf(Triple(38f, 10f, 1.4f), Triple(10f, 16f, 1f), Triple(40f, 30f, 1f)))
                    MeteoIcon.POCO_NUVOLOSO, MeteoIcon.POCO_NUVOLOSO_NOTTE -> {
                        if (icon == MeteoIcon.POCO_NUVOLOSO) sun(18f, 17f, 7.5f, 8f, 6)
                        else moon(17f, 16f, 7f, listOf(Triple(36f, 8f, 1.2f), Triple(6f, 12f, 0.9f)))
                        wisp(32f)
                        translate(6f, 13f) { scale(0.72f, 0.72f, Offset.Zero) { cloud(cloudLight, 24f) } }
                    }
                    MeteoIcon.NUVOLOSO -> { wisp(30f); cloud(cloudLight, 24f) }
                    MeteoIcon.PIOGGIA -> { cloud(cloudLight, 20f); rain(dropCount(prob, 2, 6), 34f, 43f) }
                    MeteoIcon.TEMPORALE -> {
                        cloud(cloudDark, 19f)
                        val n = dropCount(prob, 2, 4); val left = (n + 1) / 2
                        rain(left, 32f, 39f, 14f, 18f); rain(n - left, 32f, 39f, 28f, 32f)
                        bolt(p36)
                    }
                    MeteoIcon.NEVE -> {
                        cloud(cloudLight, 20f)
                        spread(dropCount(prob, 3, 6), 13f, 33f).forEachIndexed { i, x ->
                            val p = ((p26 - i * 0.35f / 2.6f) % 1f + 1f) % 1f
                            val dx = if (p < 0.5f) 3f * p else 1.5f - 6f * (p - 0.5f)
                            val dy = -2f + 8f * p
                            val a = 0.35f + 0.65f * (1 - abs2(p - 0.5f) * 2)
                            drawCircle(Color(0xFFBFDBFE).copy(alpha = a), 2.3f, Offset(x + dx, 37f + (if (i % 2 == 1) 2 else -2) + dy))
                        }
                    }
                    MeteoIcon.NEBBIA -> {
                        drawCircle(Brush.verticalGradient(cloudLight, 6f, 22f), 8f, Offset(20f, 14f), alpha = 0.8f)
                        listOf(Triple(6f, 42f, 25f), Triple(10f, 38f, 32f), Triple(6f, 42f, 39f)).forEachIndexed { i, (x1, x2, y) ->
                            val p = pulse(((p34 - i * 0.4f / 3.4f) % 1f + 1f) % 1f)
                            val dx = -2f + 4f * p
                            drawLine(Color(0xFF94A3B8).copy(alpha = 0.5f + 0.5f * p), Offset(x1 + dx, y), Offset(x2 + dx, y), 2.6f, StrokeCap.Round)
                        }
                    }
                }
            }
        }
    }
}

private fun abs2(x: Float) = if (x < 0) -x else x

/** Fulmine che "cade", lampeggia e risale (w-bolt-strike). */
private fun DrawScope.bolt(p: Float) {
    val pts = listOf(26f to 23f, 19f to 36f, 24f to 36f, 21f to 44f, 33f to 29f, 27f to 29f, 30f to 23f)
    val path = Path().apply { pts.forEachIndexed { i, (x, y) -> if (i == 0) moveTo(x, y) else lineTo(x, y) }; close() }
    val top = 23f; val bottom = 44f; val h = bottom - top
    // fino al 15%: scopre dall'alto; 60-80%: si richiude verso l'alto
    val (clipTop, clipBottom) = when {
        p < 0.15f -> top to top + h * (p / 0.15f)
        p < 0.60f -> top to bottom
        p < 0.80f -> top + h * ((p - 0.6f) / 0.2f) to bottom
        else -> bottom to bottom
    }
    if (clipBottom <= clipTop) return
    val bright = when {
        p < 0.15f -> 1.8f; p < 0.19f -> 1f; p < 0.24f -> 1.6f; p < 0.28f -> 1f; p < 0.33f -> 1.5f; else -> 1f
    }
    val base = Color(0xFFFBBF24)
    val c = Color(min(1f, base.red * bright), min(1f, base.green * bright), min(1f, max(base.blue, base.blue * bright)))
    clipRect(0f, clipTop, 60f, clipBottom) {
        drawPath(path, c.copy(alpha = 0.35f), style = androidx.compose.ui.graphics.drawscope.Stroke(3f))
        drawPath(path, c)
    }
}
