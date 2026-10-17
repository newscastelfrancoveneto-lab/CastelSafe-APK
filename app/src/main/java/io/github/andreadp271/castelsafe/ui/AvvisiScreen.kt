package io.github.andreadp271.castelsafe.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Place
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.andreadp271.castelsafe.data.Categoria
import io.github.andreadp271.castelsafe.data.Chiusura
import io.github.andreadp271.castelsafe.data.Classifier
import io.github.andreadp271.castelsafe.data.History
import io.github.andreadp271.castelsafe.data.Level
import io.github.andreadp271.castelsafe.data.Notifier
import io.github.andreadp271.castelsafe.data.Repo
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AvvisiScreen(nav: Nav) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    Column(Modifier.fillMaxSize()) {
        Bar("Avvisi", "Chiusure, divieti e notifiche ricevute")
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
            listOf("Chiusure e divieti", "Notifiche ricevute").forEachIndexed { i, label ->
                SegmentedButton(selected = tab == i, onClick = { tab = i }, shape = SegmentedButtonDefaults.itemShape(i, 2)) { Text(label) }
            }
        }
        if (tab == 0) ChiusureList(nav) else NotificheList(nav)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChiusureList(nav: Nav) {
    val res by Repo.chiusure.collectAsState()
    val scope = rememberCoroutineScope()
    var refreshing by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { Repo.loadChiusure() }
    val now = LocalDateTime.now()
    val lista = res.data.orEmpty().filter { it.stato(now) != Chiusura.Stato.CONCLUSO }
        .sortedWith(compareBy({ it.stato(now) != Chiusura.Stato.IN_CORSO }, { it.inizio }))

    PullToRefreshBox(refreshing, { scope.launch { refreshing = true; Repo.loadChiusure(true); refreshing = false } }, Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = screenPadding, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item { ResNotice(res, "Avvisi") }
            if (lista.isEmpty() && res.data != null) item {
                Panel { Text("Nessuna chiusura o divieto in corso", fontWeight = FontWeight.SemiBold); Muted("Quando il Comune pubblica un avviso lo trovi qui e sulla mappa.") }
            }
            items(lista, key = { it.id }) { c ->
                val prog = c.stato(now) == Chiusura.Stato.PROGRAMMATO
                Panel {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Surface(color = if (prog) Color(0xFFC2410C) else Color(0xFFDC2626), contentColor = Color.White, shape = RoundedCornerShape(50)) {
                            Text(if (prog) "PROGRAMMATO" else "IN CORSO", Modifier.padding(horizontal = 8.dp, vertical = 2.dp), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                        }
                        Hspace(8.dp)
                        Muted("dal ${c.inizioTesto} al ${c.fineTesto}", Modifier.weight(1f))
                    }
                    Text(c.titolo, Modifier.padding(top = 8.dp), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    if (c.descrizione.isNotBlank()) Text(c.descrizione, style = MaterialTheme.typography.bodyMedium)
                    c.centro?.let { (lat, lon) ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                            TextButton(onClick = { nav.showOnMap(lat, lon) }) { Icon(Icons.Filled.Place, null); Hspace(4.dp); Text("Vedi sulla mappa") }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun NotificheList(nav: Nav) {
    val ctx = LocalContext.current
    val items by History.items.collectAsState()
    var confirm by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { History.load(ctx) }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = screenPadding, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Muted("Tutte le notifiche inviate dal servizio. Quelle che hai escluso nelle impostazioni sono in grigio.", Modifier.weight(1f))
                if (items.isNotEmpty()) IconButton(onClick = { confirm = true }) { Icon(Icons.Filled.Delete, "Svuota cronologia") }
            }
        }
        if (items.isEmpty()) item {
            Panel {
                Text("Nessuna notifica", fontWeight = FontWeight.SemiBold)
                Muted("Le notifiche arriveranno qui. L'app controlla il servizio ogni 15 minuti circa e a ogni apertura.")
            }
        }
        items(items, key = { it.item.id }) { r ->
            val c = Classifier.classify(r.item)
            val time = LocalDateTime.ofInstant(Instant.ofEpochSecond(r.item.ts), ZoneId.systemDefault())
            Panel(onClick = { navigateRoute(nav, Notifier.routeFor(r.item)) }) {
                val alpha = if (r.mostrata) 1f else 0.55f
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (c.livello > Level.VERDE && !c.rientro) { LevelDot(c.livello, 10.dp); Hspace(6.dp) }
                    Text(c.categoria.titolo.uppercase(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary.copy(alpha = alpha), fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    Muted(ago(time))
                }
                Text(r.item.title, Modifier.padding(top = 4.dp), fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface.copy(alpha = alpha))
                Text(r.item.body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = alpha))
                if (!r.mostrata) Muted("Non notificata (esclusa dalle tue impostazioni o arrivata in ritardo)", Modifier.padding(top = 4.dp))
            }
        }
    }
    if (confirm) AlertDialog(
        onDismissRequest = { confirm = false },
        title = { Text("Svuotare la cronologia?") },
        text = { Text("Le notifiche già ricevute verranno tolte da questo elenco.") },
        confirmButton = { TextButton(onClick = { History.clear(ctx); confirm = false }) { Text("Svuota") } },
        dismissButton = { TextButton(onClick = { confirm = false }) { Text("Annulla") } },
    )
}

private fun navigateRoute(nav: Nav, route: String) {
    when {
        route.startsWith("stazione/") -> { nav.tab("sensori"); nav.go(route) }
        route == "avvisi" -> {}
        route == "allerta" -> { nav.tab("home"); nav.go(route) }
        else -> nav.tab(route)
    }
}
