package io.github.andreadp271.castelsafe.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.andreadp271.castelsafe.data.Config
import io.github.andreadp271.castelsafe.data.Meteo
import io.github.andreadp271.castelsafe.data.Repo
import io.github.andreadp271.castelsafe.data.Scadenza
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalDateTime

private val mesi = mapOf(
    "gennaio" to 1, "febbraio" to 2, "marzo" to 3, "aprile" to 4, "maggio" to 5, "giugno" to 6,
    "luglio" to 7, "agosto" to 8, "settembre" to 9, "ottobre" to 10, "novembre" to 11, "dicembre" to 12,
)

/** "17/10/2026 alle 13:00" → data e ora di emissione. */
fun Meteo.emissioneDate(): LocalDateTime? = Regex("""(\d{1,2})/(\d{1,2})/(\d{4})\D+(\d{1,2})[:.](\d{2})""").find(emissione)?.destructured?.let { (d, m, y, h, mi) ->
    runCatching { LocalDateTime.of(y.toInt(), m.toInt(), d.toInt(), h.toInt(), mi.toInt()) }.getOrNull()
}

fun Meteo.isStale(): Boolean = emissioneDate()?.let { it.isBefore(LocalDateTime.now().minusHours(26)) } ?: true

/** Data della scadenza ("sab 17 ottobre"), con l'anno ricavato dall'emissione. */
fun Scadenza.date(ref: LocalDateTime?): LocalDate? {
    val m = Regex("""^\S+\s+(\d{1,2})\s+(\S+)""").find(giorno.trim()) ?: return null
    val mese = mesi[m.groupValues[2].lowercase()] ?: return null
    val r = ref?.toLocalDate() ?: LocalDate.now()
    var anno = r.year
    if (mese < r.monthValue) anno += 1 // capodanno
    return runCatching { LocalDate.of(anno, mese, m.groupValues[1].toInt()) }.getOrNull()
}

/** Scadenze non ancora passate (la mattina di oggi scade alle 14), mai lista vuota. */
fun Meteo.upcoming(now: LocalDateTime = LocalDateTime.now()): List<Scadenza> {
    val ref = emissioneDate()
    val up = scadenze.filter { s ->
        val d = s.date(ref) ?: return@filter true
        when {
            d.isBefore(now.toLocalDate()) -> false
            d.isAfter(now.toLocalDate()) -> true
            else -> !(s.fascia == "mattina" && now.hour >= 14)
        }
    }
    return up.ifEmpty { scadenze.takeLast(1) }
}

private val teal = Color(0xFF0E8A8A)
private val tealDark = Color(0xFF096B6B)
private val giorniLunghi = listOf("Lunedì", "Martedì", "Mercoledì", "Giovedì", "Venerdì", "Sabato", "Domenica")
private val mesiNomi = listOf("gennaio", "febbraio", "marzo", "aprile", "maggio", "giugno", "luglio", "agosto", "settembre", "ottobre", "novembre", "dicembre")
private val tempRe = Regex("""(min|max)\s+(-?\d+)(?:/(-?\d+))?""", RegexOption.IGNORE_CASE)

/** "max 21/23 C" → ("max", "21–23°"). */
internal fun tempOf(s: Scadenza): Pair<String, String>? = tempRe.find(s.temperatura)?.destructured?.let { (tipo, lo, hi) ->
    tipo.lowercase() to "$lo${if (hi.isNotBlank() && hi != lo) "–$hi" else ""}°"
}

private fun dayTitle(d: LocalDate?, fallback: String): String {
    if (d == null) return fallback.replaceFirstChar { it.uppercase() }
    val today = LocalDate.now()
    val name = when (d) {
        today -> "Oggi"
        today.plusDays(1) -> "Domani"
        else -> giorniLunghi[d.dayOfWeek.value - 1]
    }
    return "$name ${d.dayOfMonth} ${mesiNomi[d.monthValue - 1]}"
}

private fun fasciaLabel(f: String?) = when (f) {
    "mattina" -> "Mattina"
    "pomeriggio" -> "Pomeriggio"
    null -> "Tutto il giorno"
    else -> f.replaceFirstChar { it.uppercase() }
}

@Composable
fun MeteoScreen(nav: Nav) {
    val res by Repo.meteo.collectAsState()
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) { Repo.loadMeteo() }
    val m = res.data
    val list = m?.upcoming().orEmpty()
    val first = list.firstOrNull()
    val ref = m?.emissioneDate()
    Column(Modifier.fillMaxSize()) {
        Bar("Meteo", "Previsioni ARPAV · Castelfranco Veneto", onBack = nav::back, actions = {
            IconButton(onClick = { scope.launch { Repo.loadMeteo(true) } }) { Icon(Icons.Filled.Refresh, "Aggiorna") }
        })
        if (res.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        LazyColumn(contentPadding = screenPadding, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            // ── adesso ──
            item {
                Column(
                    Modifier.fillMaxWidth()
                        .background(Brush.linearGradient(listOf(teal, tealDark)), RoundedCornerShape(20.dp))
                        .padding(20.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                first?.let { dayTitle(it.date(ref), it.giorno) + " · " + fasciaLabel(it.fascia).lowercase() } ?: "Previsioni",
                                color = Color.White.copy(alpha = 0.85f), fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                            )
                            first?.let(::tempOf)?.let { (tipo, v) ->
                                Row(verticalAlignment = Alignment.Bottom) {
                                    Text(v, color = Color.White, fontWeight = FontWeight.ExtraBold, fontSize = 40.sp)
                                    Text(" $tipo", color = Color.White.copy(alpha = 0.85f), fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(bottom = 8.dp))
                                }
                            }
                            Text(first?.cielo ?: if (res.loading) "Caricamento…" else "—", color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                            first?.precipitazioni?.takeIf { it.isNotBlank() }?.let { Text(it, color = Color.White.copy(alpha = 0.85f), fontSize = 13.sp) }
                        }
                        if (first != null) WeatherIcon(meteoCategory(first.cielo, first.precipitazioni).nightAware(), 88.dp, first.probInt())
                    }
                    if (m != null) {
                        val stale = m.isStale()
                        Row(Modifier.padding(top = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                            if (stale) {
                                Text(
                                    "NON AGGIORNATO", Modifier.background(Color(0xFFFFEDD5), RoundedCornerShape(50)).padding(horizontal = 8.dp, vertical = 2.dp),
                                    color = Color(0xFFC2410C), fontSize = 10.sp, fontWeight = FontWeight.ExtraBold,
                                )
                                Hspace(8.dp)
                            }
                            Text("${Config.WEATHER_ZONE} · emesso il ${m.emissione}", color = Color.White.copy(alpha = 0.8f), fontSize = 11.sp)
                        }
                    }
                }
            }
            item { ResNotice(res, "Previsioni") }

            // ── prossimi giorni: un blocco per giorno, una riga per fascia ──
            list.groupBy { it.giorno }.forEach { (giorno, fasce) ->
                item { SectionTitle(dayTitle(fasce.first().date(ref), giorno)) }
                item {
                    Panel(padding = 0.dp) {
                        fasce.forEachIndexed { i, s ->
                            if (i > 0) HorizontalDivider(Modifier.padding(start = 72.dp), color = MaterialTheme.colorScheme.outlineVariant)
                            FasciaRow(s)
                        }
                    }
                }
            }

            item {
                Muted("Fonte: ARPAV (CC BY 3.0) — previsioni per la zona ${Config.WEATHER_ZONE}, non sostituiscono i bollettini di allerta ufficiali.")
                TextButton(onClick = { MapFocus.radar.value = true; nav.tab("mappa") }) { Text("Mostra il radar precipitazioni sulla mappa") }
            }
        }
    }
}

@Composable
private fun FasciaRow(s: Scadenza) {
    val cs = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        WeatherIcon(meteoCategory(s.cielo, s.precipitazioni), 48.dp, s.probInt())
        Hspace(12.dp)
        Column(Modifier.weight(1f)) {
            Text(fasciaLabel(s.fascia), style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant)
            Text(s.cielo.ifBlank { "—" }, fontWeight = FontWeight.SemiBold, maxLines = 2)
            val sub = listOfNotNull(
                s.precipitazioni.takeIf { it.isNotBlank() },
                s.attendibilita.takeIf { it.isNotBlank() }?.let { "attendibilità ${it.lowercase()}" },
            ).joinToString(" · ")
            if (sub.isNotBlank()) Text(sub, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, maxLines = 2)
        }
        Hspace(8.dp)
        Column(horizontalAlignment = Alignment.End, modifier = Modifier.width(64.dp)) {
            tempOf(s)?.let { (tipo, v) ->
                Text(v, fontWeight = FontWeight.Bold, fontSize = 18.sp, color = if (tipo == "max") Color(0xFFEA580C) else Color(0xFF3B82F6))
                Text(tipo, style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
            }
            s.probInt()?.let { p ->
                Text("💧 $p%", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, color = if (p >= 40) Color(0xFF3B82F6) else cs.onSurfaceVariant)
            }
        }
    }
}
