package io.github.andreadp271.castelsafe.ui

import android.Manifest
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
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
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Flood
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.andreadp271.castelsafe.data.Notifier
import kotlinx.coroutines.launch

private data class Pagina(val icona: ImageVector, val titolo: String, val testo: String, val punti: List<String>, val notifiche: Boolean = false)

private val pagine = listOf(
    Pagina(
        Icons.Filled.Home, "Benvenuto in CastelSafe",
        "La protezione civile di Castelfranco Veneto sempre con te: allerte, fiumi, avvisi e mappa, senza pubblicità e senza account.",
        listOf("Scorri per scoprire cosa puoi fare.", "Puoi saltare: la guida si rivede da Impostazioni."),
    ),
    Pagina(
        Icons.Filled.Warning, "Allerte meteo",
        "In Home trovi il bollettino della Protezione Civile del Veneto per la nostra zona (E1), per oggi e per domani.",
        listOf(
            "Ogni rischio ha il suo colore: idraulico, idrogeologico, temporali, vento, caldo, neve e ghiaccio.",
            "Verde: nessuna criticità. Poi giallo, arancione e rosso.",
            "Tocca il bollettino per i dettagli e le previsioni.",
        ),
    ),
    Pagina(
        Icons.Filled.Flood, "Fiumi e sensori",
        "Livelli dei fiumi, pioggia, vento e temperatura dalle stazioni ARPAV, aggiornati man mano che arrivano.",
        listOf(
            "I dati scorrono a carosello e si fermano su quelli sopra soglia o in rapida salita.",
            "La freccia ↗ ↘ → mostra la tendenza dell'ultima ora.",
            "Tocca la ★ accanto al nome per portare una stazione in cima.",
            "Tocca un valore per aprire la stazione, con grafico e storico.",
        ),
    ),
    Pagina(
        Icons.Filled.Place, "Mappa e avvisi",
        "La mappa mostra le aree di protezione civile, i corsi d'acqua e le strade o aree chiuse.",
        listOf(
            "Con i livelli accendi o spegni ogni elemento, compreso il radar che mostra dove sta piovendo.",
            "Nella scheda Avvisi trovi chiusure, divieti e pericoli, in corso e programmati.",
            "Tocca un punto sulla mappa per i dettagli e per navigare fin lì.",
        ),
    ),
    Pagina(
        Icons.Filled.Notifications, "Notifiche",
        "Decidi tu cosa ricevere: per ogni allerta e per ogni dato di ogni stazione scegli se avvisare, e come.",
        listOf(
            "In silenzio, con suono e vibrazione, oppure con la sirena anche a telefono silenzioso.",
            "Puoi impostare le ore di quiete e far suonare comunque il livello rosso.",
            "Un avviso ti dice quando il server o una stazione non aggiornano più i dati.",
            "Per non perdere avvisi, in Impostazioni consenti l'uso senza restrizioni di batteria.",
        ),
        notifiche = true,
    ),
    Pagina(
        Icons.Filled.Info, "Widget e guida",
        "Porta le informazioni più importanti nella schermata del telefono e sappi sempre cosa fare.",
        listOf(
            "Aggiungi i widget CastelSafe: allerta, grafico dei sensori e radar della pioggia.",
            "\"Cosa fare in caso di allerta\" ha i passi per ogni rischio, dal kit di emergenza ai numeri utili.",
            "In Impostazioni cambi tema, schermata iniziale e controlli degli aggiornamenti.",
        ),
    ),
)

/** Introduzione alle funzioni dell'app: al primo avvio e, su richiesta, da Impostazioni. */
@Composable
fun Benvenuto(onDone: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val stato = rememberPagerState { pagine.size }
    var notificheOk by remember { mutableStateOf(Notifier.canNotify(ctx)) }
    val chiedi = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { notificheOk = it }
    val ultima = stato.currentPage == pagine.lastIndex

    fun vai(i: Int) { scope.launch { stato.animateScrollToPage(i) } }
    BackHandler { if (stato.currentPage > 0) vai(stato.currentPage - 1) else onDone() }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize().systemBarsPadding().padding(horizontal = 24.dp, vertical = 12.dp)) {
            Row(Modifier.fillMaxWidth().height(48.dp), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                if (!ultima) TextButton(onClick = onDone) { Text("Salta") }
            }

            HorizontalPager(stato, Modifier.weight(1f).fillMaxWidth()) { i ->
                val p = pagine[i]
                Column(
                    Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Spacer(Modifier.height(8.dp))
                    Box(Modifier.size(96.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer), contentAlignment = Alignment.Center) {
                        Icon(p.icona, null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer)
                    }
                    Spacer(Modifier.height(24.dp))
                    Text(p.titolo, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                    Text(p.testo, Modifier.padding(top = 10.dp), style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center)
                    Column(Modifier.fillMaxWidth().padding(top = 20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        p.punti.forEach { Row { Text("•", Modifier.padding(end = 10.dp), color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold); Text(it, style = MaterialTheme.typography.bodyMedium) } }
                    }
                    if (p.notifiche && Build.VERSION.SDK_INT >= 33 && !notificheOk) {
                        FilledTonalButton(onClick = { chiedi.launch(Manifest.permission.POST_NOTIFICATIONS) }, Modifier.padding(top = 20.dp)) { Text("Attiva le notifiche") }
                    }
                    Spacer(Modifier.height(8.dp))
                }
            }

            Row(Modifier.fillMaxWidth().padding(vertical = 14.dp), horizontalArrangement = Arrangement.Center) {
                repeat(pagine.size) { k ->
                    Box(
                        Modifier.padding(horizontal = 4.dp).size(if (k == stato.currentPage) 10.dp else 8.dp).clip(CircleShape)
                            .background(if (k == stato.currentPage) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant),
                    )
                }
            }

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (stato.currentPage > 0) OutlinedButton(onClick = { vai(stato.currentPage - 1) }, Modifier.weight(1f)) { Text("Indietro") }
                Button(onClick = { if (ultima) onDone() else vai(stato.currentPage + 1) }, Modifier.weight(1f)) { Text(if (ultima) "Inizia" else "Avanti") }
            }
        }
    }
}
