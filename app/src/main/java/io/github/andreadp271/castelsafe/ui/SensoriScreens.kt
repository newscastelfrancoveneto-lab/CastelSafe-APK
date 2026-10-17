package io.github.andreadp271.castelsafe.ui

import android.app.Activity
import android.content.pm.ActivityInfo
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.github.andreadp271.castelsafe.data.Level
import io.github.andreadp271.castelsafe.data.Repo
import io.github.andreadp271.castelsafe.data.Scheda
import io.github.andreadp271.castelsafe.data.SensorData
import io.github.andreadp271.castelsafe.data.Serie
import io.github.andreadp271.castelsafe.data.Soglie
import io.github.andreadp271.castelsafe.data.Station
import io.github.andreadp271.castelsafe.data.Stations
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

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
                            ArpavBadge()
                            Hspace(12.dp)
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
                                stato.sensori.filterKeys { it != "PRECCUM24_DRY" && it != "DVENTO" }.forEach { (type, l) ->
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

/** Cerchio verde acqua con il simbolo del pluviometro: lo stesso marker della PWA. */
@Composable
fun ArpavBadge(size: androidx.compose.ui.unit.Dp = 34.dp) {
    Box(Modifier.size(size).background(Color(0xFF0E8A8A), RoundedCornerShape(50)), contentAlignment = Alignment.Center) {
        Icon(androidx.compose.ui.res.painterResource(io.github.andreadp271.castelsafe.R.drawable.ic_arpav), null, tint = Color.White, modifier = Modifier.size(size * 0.5f))
    }
}

// ── Modello del grafico (come renderSensor / renderWindCombined / renderSpecialPrecipitation della PWA) ──

private data class Stat(val value: String, val label: String)
private data class ChartModel(
    val times: List<LocalDateTime>,
    val series: List<ChartSeries>,
    val yMin: Double,
    val yMax: Double,
    val yTitle: String,
    val stats: List<Stat>,
    val legend: List<Pair<String, Color>>,
    val dashedLegend: Boolean,
    val columns: List<String>,
    /** Righe della tabella, dalla più recente; secondo elemento = livello (per colorare). */
    val rows: List<Pair<List<String>, Level>>,
    val arrows: List<Double?>? = null,
    val tooltipExtra: (Int) -> String? = { null },
    val decimals: Int = 2,
    val note: String? = null,
)

private val rowFmt = DateTimeFormatter.ofPattern("dd/MM HH:mm")
private fun f(v: Double?, d: Int) = if (v == null) "—" else "%.${d}f".format(Locale.ITALY, v)

private fun buildModel(st: Station, s: Scheda, all: List<Serie>): ChartModel {
    val punti = s.serie.punti
    val times = punti.map { it.istante }
    val values = punti.map { it.valore }
    val unit = s.unita
    val color = Color(SensorData.colorFor(s.type, s.serie.nome))

    if (s.isVento) {
        val dir = times.map { s.direzione!![it] }
        val v = values.filterNotNull()
        val lastI = values.indexOfLast { it != null }
        return ChartModel(
            times, listOf(ChartSeries("Velocità ($unit)", values, color, fill = true)),
            0.0, (v.maxOrNull() ?: 0.0) * 1.15 + if ((v.maxOrNull() ?: 0.0) == 0.0) 1.0 else 0.0, "Velocità ($unit)",
            listOf(
                Stat(if (lastI >= 0) "${f(values[lastI], 1)} $unit" else "—", "Vel. attuale"),
                Stat(SensorData.windDir(dir.getOrNull(lastI)), "Prov. attuale"),
                Stat(v.maxOrNull()?.let { "${f(it, 1)} $unit" } ?: "—", "Max"),
                Stat(if (v.isNotEmpty()) "${f(v.average(), 1)} $unit" else "—", "Media"),
            ),
            emptyList(), false,
            listOf("Ora", "Velocità ($unit)", "Provenienza"),
            punti.indices.reversed().filter { values[it] != null }.map { i -> listOf(times[i].format(rowFmt), "${f(values[i], 1)} $unit", SensorData.windDir(dir[i])) to Level.NESSUNO },
            arrows = dir, tooltipExtra = { i -> dir.getOrNull(i)?.let { "Provenienza: ${SensorData.windDir(it)}" } }, decimals = 1,
        )
    }

    if (SensorData.pioggiaSpeciale(st.id, s)) {
        val bars = SensorData.bars(values)
        val temp = SensorData.support(all, times, "temperat", "temp aria", "temp")
        val rad = SensorData.support(all, times, "radiaz", "solar", "solare", "globale")
        val hum = SensorData.support(all, times, "umid", "humid", "relat")
        val drying = temp != null || rad != null || hum != null
        val acc = SensorData.rolling24h(times, bars)
        val corr = if (drying) SensorData.dryingAdjusted24h(times, bars, temp ?: times.map { null }, rad ?: times.map { null }, hum ?: times.map { null }) else null
        val line = if (corr != null) ChartSeries("Accumulo 24h corretto ($unit)", corr, Color(0xFF7C3AED)) else ChartSeries("Precipitazione cumulata 24h ($unit)", acc, Color(0xFFF97316))
        val accShown = corr ?: acc
        val top = maxOf(bars.maxOrNull() ?: 0.0, accShown.maxOrNull() ?: 0.0)
        return ChartModel(
            times,
            listOf(ChartSeries("Precipitazione ($unit)", bars, Color(0xFF2563EB), ChartSeries.Kind.BAR), line),
            0.0, if (top > 0) top * 1.15 else 1.0, "Precipitazione / Accumulo ($unit)",
            listOf(
                Stat(f(bars.lastOrNull() ?: 0.0, 2), "Ultimo step ($unit)"),
                Stat(f(accShown.lastOrNull(), 2), if (corr != null) "Accumulo 24h corretto ($unit)" else "Accumulo 24h ($unit)"),
                Stat(f(acc.maxOrNull(), 2), "Picco 24h ($unit)"),
                Stat(f(bars.sum(), 2), "Totale ($unit)"),
            ),
            listOf("Precipitazione ($unit)" to Color(0xFF2563EB), line.label to line.color), false,
            listOf("Data / Ora", "Precipitazione ($unit)", if (corr != null) "Accumulo 24h corretto" else "Accumulo 24h"),
            times.indices.reversed().map { i -> listOf(times[i].format(rowFmt), f(bars[i], 2), f(accShown[i], 2)) to Level.NESSUNO },
            note = if (corr != null) "Accumulo corretto per l'evaporazione stimata da temperatura, radiazione e umidità della stazione." else null,
        )
    }

    val isLivello = s.type == "LIVIDRO"
    val soglie: Soglie? = if (isLivello) st.soglieLivello else null
    val v = values.filterNotNull()
    val dec = if (isLivello || s.type == "PORTATA") 3 else 1
    val min = v.minOrNull() ?: 0.0; val max = v.maxOrNull() ?: 0.0
    val yMin = if (min >= 0) min * 0.95 else min - kotlin.math.abs(min) * 0.05
    val yMax = if (soglie != null) maxOf(max, soglie.s3) * 1.10 else (max * 1.15).takeIf { it > yMin } ?: (yMin + 1)
    val series = buildList {
        add(ChartSeries("${s.label} ($unit)", values, color, fill = true))
        if (soglie != null) listOf("Guardia 1" to soglie.s1, "Guardia 2" to soglie.s2, "Guardia 3" to soglie.s3).zip(listOf(Level.GIALLO, Level.ARANCIONE, Level.ROSSO)).forEach { (g, l) ->
            add(ChartSeries("${g.first} – ${f(g.second, 2)} $unit", times.map { g.second }, l.color(), ChartSeries.Kind.DASHED, inTooltip = false))
        }
    }
    fun lvl(x: Double?): Level = if (soglie == null || x == null) Level.NESSUNO else when {
        x >= soglie.s3 -> Level.ROSSO; x >= soglie.s2 -> Level.ARANCIONE; x >= soglie.s1 -> Level.GIALLO; else -> Level.NESSUNO
    }
    return ChartModel(
        times, series, yMin, yMax, "($unit)",
        listOf(
            Stat(f(v.lastOrNull(), dec), "Ultimo ($unit)"),
            Stat(f(v.minOrNull(), dec), "Min ($unit)"),
            Stat(f(v.maxOrNull(), dec), "Max ($unit)"),
            Stat(if (v.isNotEmpty()) f(v.average(), dec) else "—", "Media ($unit)"),
        ),
        series.drop(1).map { it.label to it.color }, true,
        listOf("Data / Ora", "${s.serie.nome.ifBlank { s.label }} ($unit)"),
        times.indices.reversed().map { i -> listOf(times[i].format(rowFmt), f(values[i], dec)) to lvl(values[i]) },
        decimals = dec,
    )
}

// ── Aggiornamento ogni mezz'ora (countdown della PWA) ───────────────────────

private fun nextHalfHour(now: LocalDateTime): LocalDateTime {
    val base = now.withSecond(0).withNano(0)
    return if (now.minute < 30) base.withMinute(30) else base.withMinute(0).plusHours(1)
}

@Composable
fun StazioneScreen(nav: Nav, id: String) {
    val st = Stations.byId(id) ?: return run { Column { Bar("Stazione non trovata", onBack = nav::back) } }
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val res by Repo.stazione(st.id).collectAsState()
    LaunchedEffect(st.id) { Repo.loadStazione(st.id) }
    val all = res.data.orEmpty()
    val schede = remember(all) { SensorData.schede(st.id, all) }
    var tab by rememberSaveable(st.id) { mutableStateOf(0) }
    val scheda = schede.getOrNull(tab.coerceIn(0, (schede.size - 1).coerceAtLeast(0)))
    val model = remember(scheda, all) { scheda?.let { buildModel(st, it, all) } }
    var fullscreen by remember { mutableStateOf(false) }

    // countdown al prossimo dato (ogni :00 e :30), poi "In attesa di ARPAV" finché non arriva
    val latest = all.mapNotNull { it.ultimo?.istante }.maxOrNull()
    val latestState by rememberUpdatedState(latest)
    var now by remember { mutableStateOf(LocalDateTime.now()) }
    var next by remember { mutableStateOf(nextHalfHour(LocalDateTime.now())) }
    var waitingFrom by remember { mutableStateOf<LocalDateTime?>(null) }
    LaunchedEffect(st.id) {
        var lastPoll = 0L
        while (true) {
            delay(1000)
            now = LocalDateTime.now()
            if (waitingFrom == null && !now.isBefore(next)) { waitingFrom = latestState ?: LocalDateTime.MIN; lastPoll = 0 }
            val baseline = waitingFrom
            if (baseline != null) {
                val l = latestState
                if (l != null && l.isAfter(baseline)) { waitingFrom = null; next = nextHalfHour(now) }
                else if (System.currentTimeMillis() - lastPoll > 60_000) { lastPoll = System.currentTimeMillis(); launch { Repo.loadStazione(st.id, true) } }
            }
        }
    }
    val countdown = if (waitingFrom != null) "In attesa di ARPAV" else {
        val s = java.time.Duration.between(now, next).seconds.coerceAtLeast(0)
        "%02d:%02d".format(s / 60, s % 60)
    }

    Column(Modifier.fillMaxSize()) {
        Bar(st.name, st.subtitle, onBack = nav::back, actions = {
            IconButton(onClick = { nav.showOnMap(st.lat, st.lon) }) { Icon(Icons.Filled.Place, "Mostra sulla mappa") }
            IconButton(onClick = { scope.launch { Repo.loadStazione(st.id, true) } }) { Icon(Icons.Filled.Refresh, "Aggiorna") }
        })
        if (res.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        LazyColumn(contentPadding = screenPadding, verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item { ResNotice(res, "Dati della stazione") }
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Muted("Stazione #${st.id.trimStart('0')} · ultimo dato ${fmtDateTime(latest)}", Modifier.weight(1f))
                    Surface(
                        shape = RoundedCornerShape(50),
                        color = if (waitingFrom != null) Color(0xFFFFEDD5) else MaterialTheme.colorScheme.primaryContainer,
                        contentColor = if (waitingFrom != null) Color(0xFFC2410C) else MaterialTheme.colorScheme.onPrimaryContainer,
                    ) {
                        Text(if (waitingFrom != null) countdown else "Prossimo dato $countdown", Modifier.padding(horizontal = 10.dp, vertical = 4.dp), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
            if (schede.size > 1) item {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    schede.forEachIndexed { i, s -> FilterChip(selected = s == scheda, onClick = { tab = i }, label = { Text(s.label) }) }
                }
            }
            if (model != null) {
                item { StatsRow(model.stats) }
                item {
                    Panel(padding = 12.dp) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Surface(shape = RoundedCornerShape(6.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                                Text("ARPAV (CC BY 4.0)", Modifier.padding(horizontal = 6.dp, vertical = 2.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Spacer(Modifier.weight(1f))
                            TextButton(onClick = { fullscreen = true }) { Text("⛶ Schermo intero") }
                        }
                        if (model.legend.isNotEmpty()) Legend(model.legend, model.dashedLegend)
                        SensorChart(
                            model.times, model.series, model.yMin, model.yMax, model.yTitle,
                            Modifier.fillMaxWidth().height(260.dp).padding(top = 6.dp),
                            arrows = model.arrows, tooltipExtra = model.tooltipExtra, decimals = model.decimals,
                        )
                        Muted("Tocca il grafico per leggere i valori, pizzica per ingrandire e trascina per scorrere.", Modifier.padding(top = 6.dp))
                        model.note?.let { Muted(it, Modifier.padding(top = 2.dp)) }
                    }
                }
                item { DataTableHeader(model.columns) }
                itemsIndexed(model.rows, key = { i, _ -> i }) { i, (cells, level) -> DataTableRow(cells, level, i == 0) }
            } else if (res.data == null && res.error == null) {
                item { Muted("Caricamento dei dati ARPAV…") }
            } else if (res.data != null) {
                item { Muted("Nessun sensore disponibile per questa stazione.") }
            }
            item {
                Muted("Fonte: ARPAV Veneto, dati in tempo reale non validati (licenza CC BY 4.0).", Modifier.padding(top = 8.dp))
                TextButton(onClick = { openUrl(ctx, st.arpavUrl) }) { Text("Apri il grafico sul sito ARPAV ↗") }
            }
        }
    }

    if (fullscreen && model != null) ChartFullscreen(st.name + " · " + (scheda?.label ?: ""), model) { fullscreen = false }
}

@Composable
private fun StatsRow(stats: List<Stat>) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        stats.forEach { s ->
            Surface(Modifier.weight(1f), shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
                Column(Modifier.padding(vertical = 10.dp, horizontal = 6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(s.value, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall, textAlign = TextAlign.Center, maxLines = 1)
                    Text(s.label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center, maxLines = 2)
                }
            }
        }
    }
}

@Composable
private fun Legend(items: List<Pair<String, Color>>, dashed: Boolean) {
    Row(Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        items.forEach { (label, c) ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.width(18.dp).height(if (dashed) 2.dp else 8.dp).background(c, RoundedCornerShape(2.dp)))
                Hspace(5.dp)
                Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun DataTableHeader(cols: List<String>) {
    Surface(shape = RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.padding(top = 6.dp)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp)) {
            cols.forEachIndexed { i, c ->
                Text(c, Modifier.weight(if (i == 0) 1.1f else 1f), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = if (i == 0) TextAlign.Start else TextAlign.End)
            }
        }
    }
}

@Composable
private fun DataTableRow(cells: List<String>, level: Level, latest: Boolean) {
    val bg = when {
        latest -> MaterialTheme.colorScheme.primaryContainer
        level != Level.NESSUNO -> level.color().copy(alpha = 0.16f)
        else -> MaterialTheme.colorScheme.surfaceContainerLow
    }
    Column(Modifier.background(bg)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
            cells.forEachIndexed { i, c ->
                Text(c, Modifier.weight(if (i == 0) 1.1f else 1f), style = MaterialTheme.typography.bodySmall,
                    fontWeight = if (i == 1 || latest) FontWeight.SemiBold else FontWeight.Normal, textAlign = if (i == 0) TextAlign.Start else TextAlign.End)
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
    }
}

/** Grafico a schermo intero in orizzontale, come toggleChartFullscreen della PWA. */
@Composable
private fun ChartFullscreen(title: String, m: ChartModel, onClose: () -> Unit) {
    val activity = LocalContext.current as? Activity
    DisposableEffect(Unit) {
        val prev = activity?.requestedOrientation
        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        onDispose { activity?.requestedOrientation = prev ?: ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED }
    }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.systemBarsPadding().padding(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(title, Modifier.weight(1f).padding(start = 8.dp), fontWeight = FontWeight.SemiBold)
                    if (m.legend.isNotEmpty()) Legend(m.legend, m.dashedLegend)
                    IconButton(onClick = onClose) { Icon(Icons.Filled.Close, "Chiudi") }
                }
                SensorChart(m.times, m.series, m.yMin, m.yMax, m.yTitle, Modifier.fillMaxSize(), arrows = m.arrows, tooltipExtra = m.tooltipExtra, decimals = m.decimals)
            }
        }
    }
}
