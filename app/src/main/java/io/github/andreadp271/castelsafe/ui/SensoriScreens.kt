package io.github.andreadp271.castelsafe.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.andreadp271.castelsafe.data.Level
import io.github.andreadp271.castelsafe.data.Repo
import io.github.andreadp271.castelsafe.data.Serie
import io.github.andreadp271.castelsafe.data.Soglie
import io.github.andreadp271.castelsafe.data.Stations
import kotlinx.coroutines.launch
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

@Composable
fun SensoriScreen(nav: Nav) {
    val monitor by Repo.monitor.collectAsState()
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) { Repo.loadMonitor() }
    Column(Modifier.fillMaxSize()) {
        Bar("Sensori ARPAV", "Livelli, pioggia e meteo in tempo reale", actions = {
            IconButton(onClick = { scope.launch { Repo.loadMonitor(true) } }) { Icon(Icons.Filled.Refresh, "Aggiorna") }
        })
        if (monitor.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        LazyColumn(contentPadding = screenPadding, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item { ResNotice(monitor, "Sensori") }
            Stations.all.forEach { st ->
                item {
                    val stato = monitor.data?.get(st.id)
                    Panel(onClick = { nav.go("stazione/${st.id}") }) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(st.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                                Muted(st.subtitle + when {
                                    !st.monitored -> " · solo consultazione"
                                    stato == null -> ""
                                    stato.stale -> " · dato non aggiornato"
                                    else -> " · aggiornato ${stato.etaMinuti ?: "?"} min fa"
                                })
                            }
                        }
                        if (stato != null) {
                            Column(Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                stato.sensori.filterKeys { it != "PRECCUM24_DRY" }.forEach { (type, l) ->
                                    val level = Stations.level(st.id, type, l.valore)
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(Stations.label(type), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        Text(fmtValue(l.valore, l.unita), fontWeight = if (type == st.main) FontWeight.Bold else FontWeight.Normal)
                                        if (level != Level.NESSUNO) { Hspace(8.dp); LevelDot(level, 10.dp) }
                                    }
                                }
                            }
                        } else if (!st.monitored) {
                            Muted("Stazione a monte sul Muson: utile per vedere in anticipo la piena. Tocca per il grafico.", Modifier.padding(top = 6.dp))
                        }
                    }
                }
            }
            item { Muted("Dati ARPAV Veneto (CC BY 4.0) pubblicati senza validazione preventiva. I pallini indicano la fascia rispetto alle soglie di attenzione usate per le notifiche.") }
        }
    }
}

@Composable
fun StazioneScreen(nav: Nav, id: String) {
    val st = Stations.byId(id) ?: return run { Column { Bar("Stazione non trovata", onBack = nav::back) } }
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val res by Repo.stazione(st.id).collectAsState()
    LaunchedEffect(st.id) { Repo.loadStazione(st.id) }
    val serie = res.data.orEmpty().filter { it.punti.any { p -> p.valore != null } }
    var selected by rememberSaveable(st.id) { mutableStateOf<String?>(null) }
    val current = serie.firstOrNull { it.type == selected } ?: serie.firstOrNull { it.type == st.main } ?: serie.firstOrNull()

    Column(Modifier.fillMaxSize()) {
        Bar(st.name, st.subtitle, onBack = nav::back, actions = {
            IconButton(onClick = { nav.showOnMap(st.lat, st.lon) }) { Icon(Icons.Filled.Place, "Mostra sulla mappa") }
            IconButton(onClick = { scope.launch { Repo.loadStazione(st.id, true) } }) { Icon(Icons.Filled.Refresh, "Aggiorna") }
        })
        if (res.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        LazyColumn(contentPadding = screenPadding, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item { ResNotice(res, "Dati della stazione") }
            if (serie.size > 1) item {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    serie.forEach { s -> FilterChip(selected = s == current, onClick = { selected = s.type }, label = { Text(Stations.label(s.type)) }) }
                }
            }
            if (current != null) {
                val soglie = if (current.type == "LIVIDRO") st.soglieLivello ?: Stations.soglie(st.id, current.type) else Stations.soglie(st.id, current.type)
                val last = current.ultimo
                item {
                    Panel {
                        Text(current.nome.ifBlank { Stations.label(current.type) }, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(fmtValue(last?.valore, current.unita), style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold)
                            val level = soglie?.let { s -> last?.valore?.let { v -> levelFor(v, s) } } ?: Level.NESSUNO
                            if (level != Level.NESSUNO) { Hspace(12.dp); LevelChip(level, if (level == Level.VERDE) "SOTTO SOGLIA" else "OLTRE ${level.label.uppercase()}") }
                        }
                        Muted("Ultima misura: ${fmtDateTime(last?.istante)}")
                        Chart(current, soglie, Modifier.fillMaxWidth().height(220.dp).padding(top = 12.dp))
                        if (soglie != null) SoglieLegend(soglie, current.unita, current.type == "LIVIDRO")
                    }
                }
                item { SectionTitle("Ultime misure") }
                item {
                    Panel(padding = 0.dp) {
                        current.punti.filter { it.valore != null }.takeLast(16).reversed().forEachIndexed { i, p ->
                            if (i > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
                                Text(fmtDateTime(p.istante), Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant)
                                val l = soglie?.let { s -> p.valore?.let { levelFor(it, s) } }
                                if (l != null && l > Level.VERDE) { LevelDot(l, 10.dp); Hspace(8.dp) }
                                Text(fmtValue(p.valore, current.unita), fontWeight = FontWeight.SemiBold)
                            }
                        }
                    }
                }
            } else if (res.data == null && res.error == null) {
                item { Muted("Caricamento dei dati ARPAV…") }
            } else if (res.data != null) {
                item { Muted("Nessun dato disponibile per questa stazione.") }
            }
            item {
                Muted("Fonte: ARPAV Veneto, dati in tempo reale non validati (licenza CC BY 4.0). Orari convertiti nell'ora locale.")
                TextButton(onClick = { openUrl(ctx, st.arpavUrl) }) { Text("Apri la stazione sul sito ARPAV") }
            }
        }
    }
}

private fun levelFor(v: Double, s: Soglie) = when {
    v >= s.s3 -> Level.ROSSO
    v >= s.s2 -> Level.ARANCIONE
    v >= s.s1 -> Level.GIALLO
    else -> Level.VERDE
}

@Composable
private fun SoglieLegend(s: Soglie, unit: String, guardia: Boolean) {
    Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        listOf(Level.GIALLO to s.s1, Level.ARANCIONE to s.s2, Level.ROSSO to s.s3).forEachIndexed { i, (l, v) ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                LevelDot(l, 8.dp); Hspace(4.dp)
                Text((if (guardia) "Guardia ${i + 1}: " else "") + fmtValue(v, unit), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

private val hourFmt = DateTimeFormatter.ofPattern("HH:mm")
private val dayFmt = DateTimeFormatter.ofPattern("dd/MM")

/** Grafico a linea delle ultime 48 ore, con le soglie tratteggiate. */
@Composable
private fun Chart(s: Serie, soglie: Soglie?, modifier: Modifier) {
    val cs = MaterialTheme.colorScheme
    val measurer = rememberTextMeasurer()
    val since = remember(s) { (s.punti.lastOrNull()?.istante ?: LocalDateTime.now()).minusHours(48) }
    val pts = remember(s) { s.punti.filter { it.valore != null && it.istante.isAfter(since) } }
    if (pts.size < 2) { Muted("Dati insufficienti per il grafico.", modifier); return }
    val isBar = s.type == "PREC"
    val labelStyle = TextStyle(fontSize = 10.sp, color = cs.onSurfaceVariant)

    Canvas(modifier) {
        val left = 44.dp.toPx(); val bottom = 20.dp.toPx(); val top = 6.dp.toPx()
        val w = size.width - left; val h = size.height - bottom - top
        val values = pts.map { it.valore!! }
        var lo = values.min(); var hi = values.max()
        // le soglie restano visibili se il valore è vicino; il grafico parte da zero per pioggia e livelli bassi
        soglie?.let { if (hi >= it.s1 * 0.6) hi = maxOf(hi, it.s1) }
        // pioggia, livelli e portate partono da zero: oscillazioni di un centimetro non devono sembrare una piena
        if (isBar || s.type.startsWith("PRECCUM") || s.type == "LIVIDRO" || s.type == "PORTATA" || lo >= 0 && lo < hi * 0.3) lo = minOf(lo, 0.0)
        if (s.type == "LIVIDRO" && soglie == null) hi = maxOf(hi, 1.0)
        if (hi - lo < 1e-6) { hi += 1.0; lo -= if (lo > 0) minOf(1.0, lo) else 0.0 }
        val pad = (hi - lo) * 0.08; hi += pad; if (lo != 0.0) lo -= pad
        val t0 = pts.first().istante; val span = java.time.Duration.between(t0, pts.last().istante).seconds.toFloat().coerceAtLeast(1f)
        fun x(t: LocalDateTime) = left + w * java.time.Duration.between(t0, t).seconds / span
        fun y(v: Double) = top + h * (1 - ((v - lo) / (hi - lo))).toFloat()

        // griglia orizzontale con 4 valori
        for (i in 0..3) {
            val v = lo + (hi - lo) * i / 3
            val yy = y(v)
            drawLine(cs.outlineVariant, Offset(left, yy), Offset(size.width, yy), 1f)
            val t = measurer.measure("%.${if (hi - lo < 3) 2 else 0}f".format(java.util.Locale.ITALY, v), labelStyle)
            drawText(t, topLeft = Offset(left - t.size.width - 6.dp.toPx(), yy - t.size.height / 2))
        }
        // etichette tempo: ogni 12 ore
        var tick = t0.withMinute(0).withSecond(0).withNano(0).plusHours(1)
        while (tick.hour % 12 != 0) tick = tick.plusHours(1)
        while (!tick.isAfter(pts.last().istante)) {
            val xx = x(tick)
            drawLine(cs.outlineVariant, Offset(xx, top), Offset(xx, top + h), 1f)
            val t = measurer.measure(if (tick.hour == 0) tick.format(dayFmt) else tick.format(hourFmt), labelStyle)
            drawText(t, topLeft = Offset((xx - t.size.width / 2).coerceIn(left, size.width - t.size.width), top + h + 4.dp.toPx()))
            tick = tick.plusHours(12)
        }
        // soglie
        soglie?.let { so ->
            listOf(Level.GIALLO to so.s1, Level.ARANCIONE to so.s2, Level.ROSSO to so.s3).forEach { (l, v) ->
                if (v in lo..hi) drawLine(l.color(), Offset(left, y(v)), Offset(size.width, y(v)), 2f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f)))
            }
        }
        if (isBar) {
            val bw = (w / pts.size).coerceIn(1f, 8.dp.toPx()) * 0.7f
            pts.forEach { p -> if (p.valore!! > 0) drawRect(cs.primary, Offset(x(p.istante) - bw / 2, y(p.valore)), androidx.compose.ui.geometry.Size(bw, y(0.0) - y(p.valore))) }
        } else {
            val path = Path()
            pts.forEachIndexed { i, p -> if (i == 0) path.moveTo(x(p.istante), y(p.valore!!)) else path.lineTo(x(p.istante), y(p.valore!!)) }
            val fill = Path().apply { addPath(path); lineTo(x(pts.last().istante), top + h); lineTo(x(pts.first().istante), top + h); close() }
            drawPath(fill, cs.primary.copy(alpha = 0.10f))
            drawPath(path, cs.primary, style = Stroke(width = 2.5.dp.toPx()))
            val lastP = pts.last()
            drawCircle(cs.primary, 4.dp.toPx(), Offset(x(lastP.istante), y(lastP.valore!!)))
        }
    }
}
