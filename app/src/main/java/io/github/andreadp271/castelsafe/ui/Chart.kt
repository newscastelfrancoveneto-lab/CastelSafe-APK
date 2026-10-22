package io.github.andreadp271.castelsafe.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlin.math.abs
import kotlin.math.roundToInt

/** Una serie del grafico. */
data class ChartSeries(
    val label: String,
    val values: List<Double?>,
    val color: Color,
    val kind: Kind = Kind.LINE,
    val fill: Boolean = false,
    /** Inclusa nel riquadro del valore toccato. */
    val inTooltip: Boolean = true,
) {
    enum class Kind { LINE, BAR, DASHED }
}

private val labelFmt = DateTimeFormatter.ofPattern("dd/MM HH:mm")

/**
 * Grafico temporale come quello della PWA (Chart.js): tocca o trascina per leggere i valori,
 * pizzica per lo zoom orizzontale e trascina per scorrere quando ingrandito.
 */
@Composable
fun SensorChart(
    times: List<LocalDateTime>,
    series: List<ChartSeries>,
    yMin: Double,
    yMax: Double,
    yTitle: String,
    modifier: Modifier = Modifier,
    /** Direzione del vento (gradi) per punto: frecce sopra la linea della prima serie. */
    arrows: List<Double?>? = null,
    tooltipExtra: (Int) -> String? = { null },
    decimals: Int = 2,
) {
    val n = times.size
    if (n < 2) { Muted("Dati insufficienti per il grafico.", modifier); return }
    val cs = MaterialTheme.colorScheme
    val measurer = rememberTextMeasurer()
    val slop = LocalViewConfiguration.current.touchSlop
    // l'asse X è il tempo vero (minuti dal primo punto): i buchi nei dati restano buchi
    val tm = remember(times) { FloatArray(n) { java.time.Duration.between(times[0], times[it]).toMinutes().toFloat() } }
    val total = tm[n - 1].coerceAtLeast(1f)
    val step = remember(tm) { tm.toList().zipWithNext { a, b -> b - a }.filter { it > 0 }.sorted().let { if (it.isEmpty()) 1f else it[it.size / 2] } }
    fun nearest(m: Float): Int {
        var lo = 0; var hi = n - 1
        while (lo < hi) { val mid = (lo + hi) / 2; if (tm[mid] < m) lo = mid + 1 else hi = mid }
        return if (lo > 0 && m - tm[lo - 1] <= tm[lo] - m) lo - 1 else lo
    }
    var start by remember(n) { mutableFloatStateOf(0f) }
    var end by remember(n) { mutableFloatStateOf(total) }
    var selected by remember(n) { mutableIntStateOf(-1) }
    val zoomed = start > step / 2 || end < total - step / 2
    val labelStyle = TextStyle(fontSize = 10.sp, color = cs.onSurfaceVariant)
    val leftPad = 46f; val bottomPad = 22f; val topPad = if (arrows != null) 30f else 8f

    Box(modifier) {
        Canvas(
            Modifier.matchParentSize().pointerInput(n) {
                val left = leftPad * density
                fun indexAt(x: Float): Int {
                    val w = size.width - left
                    val f = ((x - left) / w).coerceIn(0f, 1f)
                    return nearest(start + f * (end - start))
                }
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    selected = indexAt(down.position.x)
                    var mode = 0 // 0 da decidere, 1 orizzontale, 2 verticale (scorre la pagina), 3 due dita
                    while (true) {
                        val ev = awaitPointerEvent()
                        val pressed = ev.changes.filter { it.pressed }
                        if (pressed.isEmpty()) break
                        if (pressed.size >= 2) {
                            mode = 3
                            val zoom = ev.calculateZoom()
                            val pan = ev.calculatePan()
                            val cx = ev.calculateCentroid().x
                            val w = size.width - left
                            val span = end - start
                            val newSpan = (span / zoom).coerceIn(minOf(8f * step, total), total)
                            val anchor = start + ((cx - left) / w).coerceIn(0f, 1f) * span
                            var s = anchor - (anchor - start) * newSpan / span - pan.x / w * newSpan
                            s = s.coerceIn(0f, total - newSpan)
                            start = s; end = s + newSpan
                            ev.changes.forEach { it.consume() }
                            continue
                        }
                        val c = pressed[0]
                        if (mode == 0) {
                            val d = c.position - down.position
                            if (abs(d.x) > slop && abs(d.x) > abs(d.y)) mode = 1
                            else if (abs(d.y) > slop) mode = 2
                        }
                        if (mode == 1) {
                            if (zoomed) {
                                val w = size.width - left
                                val dx = (c.position.x - c.previousPosition.x) / w * (end - start)
                                val span = end - start
                                val s = (start - dx).coerceIn(0f, total - span)
                                start = s; end = s + span
                            } else selected = indexAt(c.position.x)
                            c.consume()
                        }
                    }
                }
            },
        ) {
            val l = leftPad * density; val b = bottomPad * density; val tp = topPad * density
            val w = size.width - l; val h = size.height - b - tp
            val span = (end - start).coerceAtLeast(1f)
            fun x(i: Int) = l + w * (tm[i] - start) / span
            fun y(v: Double) = tp + h * (1 - ((v - yMin) / (yMax - yMin))).toFloat()

            // griglia e valori sull'asse Y
            for (k in 0..4) {
                val v = yMin + (yMax - yMin) * k / 4
                val yy = y(v)
                drawLine(cs.outlineVariant.copy(alpha = 0.6f), Offset(l, yy), Offset(size.width, yy), 1f)
                val t = measurer.measure("%.${if (yMax - yMin < 2) 2 else if (yMax - yMin < 20) 1 else 0}f".format(java.util.Locale.ITALY, v), labelStyle)
                drawText(t, topLeft = Offset(l - t.size.width - 6f * density, yy - t.size.height / 2))
            }
            val title = measurer.measure(yTitle, labelStyle)
            rotate(-90f, Offset(6f * density, tp + h / 2)) { drawText(title, topLeft = Offset(6f * density - title.size.width / 2, tp + h / 2 - title.size.height)) }

            // etichette X: sei, a intervalli di tempo uguali, come maxTicksLimit della PWA
            val first = nearest(start); val last = nearest(end)
            val larg = measurer.measure(times[0].format(labelFmt), labelStyle).size.width
            val nEt = (w / (larg + 24f * density)).toInt().coerceIn(2, 6)
            for (k in 0 until nEt) {
                val f = k / (nEt - 1f)
                val lab = times[0].plusSeconds(((start + (end - start) * f) * 60).toLong())
                val tt = measurer.measure(lab.format(labelFmt), labelStyle)
                val lx = (l + w * f - tt.size.width / 2).coerceIn(l, size.width - tt.size.width)
                drawText(tt, topLeft = Offset(lx, tp + h + 5f * density))
            }

            clipRect(l, 0f, size.width, tp + h) {
                val barSeries = series.filter { it.kind == ChartSeries.Kind.BAR }
                barSeries.forEach { s ->
                    val bw = (w / span * step * 0.8f).coerceIn(1f, 24f * density)
                    s.values.forEachIndexed { idx, v ->
                        if (v != null && v > 0 && idx >= first - 1 && idx <= last + 1) drawRect(s.color.copy(alpha = 0.75f), Offset(x(idx) - bw / 2, y(v)), Size(bw, y(yMin) - y(v)))
                    }
                }
                series.filter { it.kind != ChartSeries.Kind.BAR }.forEach { s ->
                    val path = Path(); var started = false
                    val fill = Path(); var segStart = 0f; var segEnd = 0f
                    fun chiudi() { if (s.fill && started) { fill.lineTo(segEnd, tp + h); fill.lineTo(segStart, tp + h); fill.close() } }
                    s.values.forEachIndexed { idx, v ->
                        if (v == null) { chiudi(); started = false; return@forEachIndexed }
                        val px = x(idx); val py = y(v)
                        if (!started) { path.moveTo(px, py); fill.moveTo(px, py); started = true; segStart = px } else { path.lineTo(px, py); fill.lineTo(px, py) }
                        segEnd = px
                    }
                    chiudi()
                    if (s.fill) drawPath(fill, s.color.copy(alpha = 0.15f))
                    drawPath(
                        path, s.color,
                        style = Stroke(
                            width = 2f * density,
                            pathEffect = if (s.kind == ChartSeries.Kind.DASHED) PathEffect.dashPathEffect(floatArrayOf(6f * density, 4f * density)) else null,
                        ),
                    )
                }
            }

            // frecce del vento: verso dove soffia (provenienza + 180°), come la PWA
            if (arrows != null) {
                val base = series.first().values
                val every = maxOf(1, (14f * density / (w / span * step)).toInt() + 1)
                val sz = 9f * density
                for (idx in first..last) {
                    if ((idx - first) % every != 0) continue
                    val deg = arrows.getOrNull(idx) ?: continue
                    val v = base.getOrNull(idx) ?: continue
                    val c = Offset(x(idx), y(v))
                    rotate((deg + 180).toFloat(), c) {
                        val p = Path().apply {
                            moveTo(c.x, c.y - (sz + 7f * density))
                            lineTo(c.x - sz * 0.4f, c.y - sz * 0.1f)
                            lineTo(c.x, c.y - sz * 0.5f)
                            lineTo(c.x + sz * 0.4f, c.y - sz * 0.1f)
                            close()
                        }
                        drawPath(p, Color(0xFF0369A1))
                        drawPath(p, Color.White.copy(alpha = 0.85f), style = Stroke(1f))
                    }
                }
            }

            // valore toccato
            if (selected in 0 until n && tm[selected] in (start - step / 2)..(end + step / 2)) {
                val sx = x(selected)
                drawLine(cs.onSurfaceVariant, Offset(sx, tp), Offset(sx, tp + h), 1.5f)
                series.forEach { s -> s.values.getOrNull(selected)?.let { if (s.kind != ChartSeries.Kind.BAR) drawCircle(s.color, 4f * density, Offset(sx, y(it))) } }
                val lines = buildList {
                    add(times[selected].format(labelFmt))
                    series.filter { it.inTooltip }.forEach { s ->
                        s.values.getOrNull(selected)?.let { add("${s.label}: ${"%.${decimals}f".format(java.util.Locale.ITALY, it)}") }
                    }
                    tooltipExtra(selected)?.let { add(it) }
                }
                val texts = lines.mapIndexed { k, s ->
                    measurer.measure(s, TextStyle(fontSize = if (k == 0) 12.sp else 11.sp, color = Color.White, fontWeight = if (k == 0) FontWeight.Bold else FontWeight.Normal))
                }
                val bw = texts.maxOf { it.size.width } + 20f * density
                val bh = texts.sumOf { it.size.height } + 14f * density
                val bx = if (sx + 10f * density + bw < size.width) sx + 10f * density else sx - 10f * density - bw
                drawRoundRect(Color(0xE60F172A), Offset(bx.coerceAtLeast(0f), tp), Size(bw, bh), CornerRadius(8f * density))
                var ty = tp + 7f * density
                texts.forEach { t -> drawText(t, topLeft = Offset(bx.coerceAtLeast(0f) + 10f * density, ty)); ty += t.size.height }
            }
        }
        if (zoomed) TextButton(onClick = { start = 0f; end = total }, modifier = Modifier.align(Alignment.TopEnd).padding(end = 4.dp)) {
            Text("Ripristina zoom", style = MaterialTheme.typography.labelSmall)
        }
    }
}
