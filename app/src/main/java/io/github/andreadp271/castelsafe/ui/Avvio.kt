package io.github.andreadp271.castelsafe.ui

import android.os.SystemClock
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.andreadp271.castelsafe.R
import io.github.andreadp271.castelsafe.data.Repo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** Vero finché l'app non ha mostrato la schermata di caricamento in questa esecuzione (non si ripete rientrando). */
object AvvioStato { var mostrato = false }

private val Bordeaux = Color(0xFF8D021F)
private const val MIN_MS = 700L      // abbastanza per leggere il passaggio, anche con i dati già in memoria
private const val LENTO_MS = 6_000L
private const val MAX_MS = 15_000L   // rete di sicurezza: dopo si entra comunque, con l'ultimo dato salvato

private class Passo(val testo: String, val carica: suspend () -> Unit)

/**
 * Schermata di caricamento tutta rossa, come quella della PWA: icona, rotellina e il passaggio in corso.
 * Scarica i dati iniziali (le copie salvate compaiono subito) e chiama [onDone] quando sono pronti.
 */
@Composable
fun Avvio(onDone: () -> Unit) {
    val passi = remember {
        listOf(
            Passo("Leggo il bollettino di allerta…") { Repo.loadAllerta() },
            Passo("Carico i sensori ARPAV…") { Repo.loadMonitor() },
            Passo("Controllo chiusure e avvisi…") { Repo.loadChiusure() },
            Passo("Preparo il meteo…") { Repo.loadMeteo() },
        )
    }
    val fatti = remember { mutableStateListOf(*Array(passi.size) { false }) }
    var lento by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        val inizio = SystemClock.elapsedRealtime()
        launch { delay(LENTO_MS); lento = true }
        withTimeoutOrNull(MAX_MS) {
            coroutineScope {
                passi.forEachIndexed { i, p ->
                    launch {
                        try { p.carica() } catch (e: CancellationException) { throw e } catch (_: Exception) { /* resta l'ultimo dato salvato */ }
                        fatti[i] = true
                    }
                }
            }
        }
        delay((MIN_MS - (SystemClock.elapsedRealtime() - inizio)).coerceAtLeast(0))
        onDone()
    }

    val testo = passi.indices.firstOrNull { !fatti[it] }?.let { passi[it].testo } ?: "Tutto pronto"
    Box(Modifier.fillMaxSize().background(Bordeaux), contentAlignment = Alignment.Center) {
        Column(
            Modifier.padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            Image(
                painterResource(R.drawable.logo_app), "CastelSafe",
                Modifier.size(96.dp).shadow(12.dp, RoundedCornerShape(22.dp)).clip(RoundedCornerShape(22.dp)),
            )
            CircularProgressIndicator(Modifier.size(30.dp), color = Color.White, trackColor = Color.White.copy(alpha = 0.35f), strokeWidth = 3.dp)
            Text(testo, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
            if (lento) Text(
                "La connessione è lenta: ancora qualche secondo…",
                color = Color.White.copy(alpha = 0.8f), fontSize = 13.sp, textAlign = TextAlign.Center,
            )
        }
    }
}
