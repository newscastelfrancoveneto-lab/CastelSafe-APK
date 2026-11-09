package io.github.andreadp271.castelsafe.ui

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.sp
import io.github.andreadp271.castelsafe.data.Serie
import io.github.andreadp271.castelsafe.data.Station
import io.github.andreadp271.castelsafe.data.StatoStazione
import io.github.andreadp271.castelsafe.data.NotifySettings
import io.github.andreadp271.castelsafe.data.Trend
import kotlinx.coroutines.delay
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.filled.Close
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AcUnit
import androidx.compose.material.icons.filled.Grain
import androidx.compose.material.icons.filled.Air
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.FilterChip
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Flood
import androidx.compose.material.icons.filled.Landslide
import androidx.compose.material.icons.filled.Thermostat
import androidx.compose.material.icons.filled.Thunderstorm
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.andreadp271.castelsafe.data.Allerta
import io.github.andreadp271.castelsafe.data.AllertaGiorno
import io.github.andreadp271.castelsafe.data.Chiusura
import io.github.andreadp271.castelsafe.data.Level
import io.github.andreadp271.castelsafe.data.Notifier
import io.github.andreadp271.castelsafe.data.Repo
import io.github.andreadp271.castelsafe.data.Stations
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(nav: Nav) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val allerta by Repo.allerta.collectAsState()
    val monitor by Repo.monitor.collectAsState()
    val chiusure by Repo.chiusure.collectAsState()
    val meteo by Repo.meteo.collectAsState()
    var refreshing by remember { mutableStateOf(false) }
    val notifySettings = remember { NotifySettings(ctx) }
    var preferite by remember { mutableStateOf(notifySettings.preferite) }

    LaunchedEffect(Unit) {
        // storico delle stazioni: serve a riconoscere i dati in rapida salita
        Stations.all.filter { it.monitored }.forEach { st -> launch { Repo.loadStazione(st.id) } }
        Repo.refreshHome(false)
    }

    // permesso notifiche (Android 13+): chiesto una volta, poi gestibile da Altro > Notifiche
    var askNotif by remember { mutableStateOf(!Notifier.canNotify(ctx)) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { askNotif = !it }

    Column(Modifier.fillMaxSize()) {
        Bar("CastelSafe", "Castelfranco Veneto", actions = {
            IconButton(onClick = { nav.go("contatti") }) { Icon(Icons.Filled.Call, "Numeri di emergenza", tint = MaterialTheme.colorScheme.primary) }
        })
        PullToRefreshBox(
            isRefreshing = refreshing,
            onRefresh = {
                scope.launch {
                    refreshing = true
                    Stations.all.filter { it.monitored }.forEach { st -> launch { Repo.loadStazione(st.id, true) } }
                    Repo.refreshHome(true)
                    refreshing = false
                }
            },
            modifier = Modifier.fillMaxSize(),
        ) {
            LazyColumn(Modifier.fillMaxSize(), contentPadding = screenPadding, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (askNotif && Build.VERSION.SDK_INT >= 33) item {
                    Panel {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.Notifications, null, tint = MaterialTheme.colorScheme.primary)
                            Hspace(12.dp)
                            Column(Modifier.weight(1f)) {
                                Text("Attiva le notifiche", fontWeight = FontWeight.SemiBold)
                                Muted("Per sapere subito di allerte, fiumi oltre soglia e chiusure.")
                            }
                        }
                        Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.End) {
                            TextButton(onClick = { askNotif = false }) { Text("Non ora") }
                            Button(onClick = { launcher.launch(Manifest.permission.POST_NOTIFICATIONS) }) { Text("Attiva") }
                        }
                    }
                }

                item { AllertaCard(allerta.data, allerta.loading) { nav.go("allerta") } }
                item { ResNotice(allerta, "Bollettino e dati") }

                // Sensori
                item { SectionTitle("Sensori ARPAV") }
                item {
                    Panel(padding = 0.dp) {
                        // le preferite (★) vanno in cima
                        Stations.all.filter { it.monitored }.sortedByDescending { it.id in preferite }.forEachIndexed { i, st ->
                            if (i > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                            val serie by Repo.stazione(st.id).collectAsState()
                            SensoreHomeRow(
                                st, monitor.data?.get(st.id), serie.data, st.id in preferite,
                                onPreferita = { preferite = (if (st.id in preferite) preferite - st.id else preferite + st.id).also { notifySettings.preferite = it } },
                            ) { dato -> nav.tab("sensori"); nav.go("stazione/${st.id}?dato=$dato") }
                        }
                    }
                }

                // Meteo
                meteo.data?.upcoming()?.firstOrNull()?.let { s ->
                    item { SectionTitle("Meteo") }
                    item {
                        Panel(onClick = { nav.go("meteo") }) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                WeatherIcon(meteoCategory(s.cielo, s.precipitazioni).nightAware(), 52.dp, s.probInt())
                                Hspace(14.dp)
                                Column(Modifier.weight(1f)) {
                                    Text(s.etichetta.replaceFirstChar { it.uppercase() }, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Text(s.cielo.ifBlank { "Previsioni" }, fontWeight = FontWeight.SemiBold, maxLines = 2)
                                }
                                Hspace(8.dp)
                                Column(horizontalAlignment = Alignment.End) {
                                    tempOf(s)?.let { (tipo, v) ->
                                        Text(v, fontSize = 28.sp, fontWeight = FontWeight.Bold, color = if (tipo == "max") Color(0xFFEA580C) else Color(0xFF3B82F6))
                                    }
                                    s.probInt()?.let { p ->
                                        Text("💧 $p%", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = if (p >= 40) Color(0xFF3B82F6) else MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                            }
                        }
                    }
                }

                // Avvisi
                val lista = chiusure.data.orEmpty().filter { it.stato() != Chiusura.Stato.CONCLUSO }
                item { SectionTitle("Avvisi e chiusure") }
                item {
                    Panel(onClick = { nav.tab("avvisi") }) {
                        val inCorso = lista.count { it.stato() == Chiusura.Stato.IN_CORSO }
                        val prog = lista.size - inCorso
                        Text(
                            when {
                                lista.isEmpty() && chiusure.data == null -> "Caricamento…"
                                lista.isEmpty() -> "Nessun avviso attivo"
                                else -> "$inCorso in corso" + if (prog > 0) " · $prog programmati" else ""
                            },
                            fontWeight = FontWeight.SemiBold,
                        )
                        lista.take(3).forEach { Muted("• ${it.titolo}" + if (it.descrizione.isNotBlank()) " — ${it.descrizione}" else "") }
                    }
                }

                item {
                    FilledTonalButton(onClick = { nav.go("guida") }, modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) {
                        Icon(Icons.Filled.Info, null); Hspace(6.dp); Text("Cosa fare in caso di allerta")
                    }
                }
            }
        }
    }
}

@Composable
private fun AllertaCard(a: Allerta?, loading: Boolean, onClick: () -> Unit) {
    val max = a?.maxLevel ?: Level.NESSUNO
    val c = max.color()
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(20.dp),
        color = if (max == Level.NESSUNO) MaterialTheme.colorScheme.surfaceContainerLow else c.copy(alpha = 0.14f),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                when {
                    a == null && loading -> "Caricamento…"
                    a == null -> "Bollettino non disponibile"
                    max == Level.VERDE -> "Nessuna allerta"
                    else -> "Allerta ${max.label.lowercase()}"
                },
                style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold,
            )
            if (a != null) {
                // riepilogo a tabella: i rischi in colonna (simbolo una volta sola), i giorni in riga
                Row(verticalAlignment = Alignment.Bottom) {
                    androidx.compose.foundation.layout.Spacer(Modifier.weight(0.24f))
                    rischiSimboli.forEach { (icona, nome) ->
                        Box(Modifier.weight(0.76f / rischiSimboli.size), contentAlignment = Alignment.Center) {
                            Icon(icona, nome, Modifier.size(22.dp), tint = MaterialTheme.colorScheme.onSurface)
                        }
                    }
                }
                GiornoRow("Oggi", a.oggi)
                GiornoRow("Domani", a.domani)
                Muted("Zona ${a.zona ?: "E1"} · bollettino ${a.aggiornamento?.numero ?: "—"} del ${a.aggiornamento?.data ?: "—"} ${a.aggiornamento?.ora?.take(5).orEmpty()}")
            }
        }
    }
}

/** Simbolo dei rischi (nome solo per l'accessibilità), nell'ordine di [AllertaGiorno.rischi]. */
private val rischiSimboli = listOf(
    Icons.Filled.Flood to "Rischio idraulico",
    Icons.Filled.Landslide to "Rischio idrogeologico",
    Icons.Filled.Thunderstorm to "Temporali",
    Icons.Filled.Air to "Vento",
    Icons.Filled.Thermostat to "Ondate di calore",
    Icons.Filled.Grain to "Neve",
    Icons.Filled.AcUnit to "Ghiaccio al suolo",
)

@Composable
private fun GiornoRow(label: String, g: AllertaGiorno?) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(0.24f), fontWeight = FontWeight.SemiBold)
        rischiSimboli.indices.forEach { i ->
            Box(Modifier.weight(0.76f / rischiSimboli.size), contentAlignment = Alignment.Center) {
                LevelDot(g?.rischi?.getOrNull(i)?.second ?: Level.NESSUNO, 18.dp)
            }
        }
    }
}

@Composable
fun AllertaScreen(nav: Nav) {
    val allerta by Repo.allerta.collectAsState()
    LaunchedEffect(Unit) { Repo.loadAllerta() }
    val a = allerta.data
    Column(Modifier.fillMaxSize()) {
        Bar("Bollettino di allerta", "Protezione Civile del Veneto · zona ${a?.zona ?: "E1"}", onBack = nav::back)
        LazyColumn(contentPadding = screenPadding, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item { ResNotice(allerta, "Bollettino") }
            if (a != null) {
                listOf("Oggi" to a.oggi, "Domani" to a.domani).forEach { (label, g) ->
                    item {
                        Panel {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(label, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                                    Muted(g?.periodo ?: "—")
                                }
                                g?.maxLevel?.let { LevelChip(it) }
                            }
                            g?.rischi?.forEach { (nome, l) ->
                                Row(Modifier.fillMaxWidth().padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                                    LevelDot(l)
                                    Hspace(10.dp)
                                    Text("Rischio ${nome.lowercase()}", Modifier.weight(1f))
                                    Text(l.label.ifBlank { "—" }, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                }
                a.fenomeni?.takeIf { it.isNotBlank() }?.let { item { Panel { Text("Fenomeni previsti", fontWeight = FontWeight.SemiBold); Text(it) } } }
                a.tendenza?.takeIf { it.isNotBlank() }?.let { item { Panel { Text("Tendenza", fontWeight = FontWeight.SemiBold); Text(it) } } }
                item {
                    Muted(
                        "Bollettino n. ${a.aggiornamento?.numero ?: "—"} (revisione ${a.aggiornamento?.revisione ?: "—"}) " +
                            "compilato il ${a.aggiornamento?.data ?: "—"} alle ${a.aggiornamento?.ora ?: "—"}. Aggiornato sul server: ${a.aggiornato ?: "—"}.",
                    )
                }
            }
            item { FilledTonalButton(onClick = { nav.go("guida") }, modifier = Modifier.fillMaxWidth()) { Text("Cosa fare in caso di allerta") } }
        }
    }
}

/** Una fase della guida (prima / durante / dopo) con i gesti da fare, uno per riga. */
private data class Fase(val titolo: String, val colore: Color, val passi: List<String>)

private data class Rischio(val nome: String, val icona: androidx.compose.ui.graphics.vector.ImageVector, val sintesi: String, val fasi: List<Fase>)

private val verdeFase = Color(0xFF22C55E)
private val gialloFase = Color(0xFFFACC15)
private val rossoFase = Color(0xFFDC2626)
private val bluFase = Color(0xFF3B82F6)

private val rischi = listOf(
    Rischio(
        "Alluvione", Icons.Filled.Flood, "Fiumi, torrenti e piogge intense: l'acqua può salire in pochi minuti.",
        listOf(
            Fase("Con l'allerta", gialloFase, listOf(
                "Tieni attive le notifiche dell'app e controlla il bollettino.",
                "Non dormire né stare nei piani interrati o seminterrati.",
                "Metti al sicuro documenti e farmaci ai piani alti.",
                "Controlla nell'app le strade chiuse prima di partire.",
                "Non rischiare per salvare l'auto o altri beni.",
            )),
            Fase("Durante", rossoFase, listOf(
                "Sali ai piani alti, chiudi porte e finestre.",
                "Chiudi il gas e stacca la corrente solo se sei all'asciutto.",
                "Non usare l'ascensore.",
                "Non attraversare strade allagate: bastano 20–30 cm d'acqua per trascinare auto e persone.",
                "Raggiungi le aree di attesa indicate dal Comune e segui solo le autorità.",
            )),
            Fase("Dopo", verdeFase, listOf(
                "Rientra solo dopo il cessato allarme.",
                "Fai controllare gas, luce e acqua prima di usarli.",
                "Non bere l'acqua del rubinetto e butta i cibi bagnati.",
                "Occhio a voragini, cavi scoperti e detriti.",
                "Segnala i danni al Comune.",
            )),
        ),
    ),
    Rischio(
        "Temporali", Icons.Filled.Thunderstorm, "Fulmini, grandine e raffiche improvvise.",
        listOf(
            Fase("Prima", gialloFase, listOf(
                "Rimanda gite, sport e lavori all'aperto o in quota.",
                "Segui l'evoluzione del meteo.",
            )),
            Fase("Durante", rossoFase, listOf(
                "All'aperto cerca riparo in un edificio o in auto, finestrini chiusi.",
                "Stai lontano da alberi isolati, pali, tralicci e specchi d'acqua.",
                "In casa chiudi porte e finestre e scollega gli apparecchi sensibili.",
            )),
            Fase("Dopo", verdeFase, listOf(
                "Evita rami pericolanti e cavi elettrici a terra.",
                "Segnala i danni al Comune.",
            )),
        ),
    ),
    Rischio(
        "Vento forte", Icons.Filled.Air, "Oggetti che volano e alberi che cadono.",
        listOf(
            Fase("Prima", gialloFase, listOf(
                "Metti al sicuro ombrelloni, tende e mobili da giardino.",
                "Controlla coperture provvisorie e grondaie.",
            )),
            Fase("Durante", rossoFase, listOf(
                "Non fermarti sotto alberi, impalcature, cartelloni e cornicioni.",
                "In auto vai piano su ponti e strade esposte.",
                "Evita gli spostamenti non necessari.",
            )),
            Fase("Dopo", verdeFase, listOf(
                "Segnala alberi pericolanti e danni.",
                "Non avvicinarti ai cavi elettrici caduti.",
            )),
        ),
    ),
    Rischio(
        "Neve e ghiaccio", Icons.Filled.AcUnit, "Strade e marciapiedi scivolosi, neve sui tetti. Il bollettino regionale ha per entrambi un'allerta a parte.",
        listOf(
            Fase("Prima", gialloFase, listOf(
                "Monta le gomme invernali o tieni a bordo le catene, e prova a montarle prima di averne bisogno.",
                "Metti scarpe antiscivolo, con suola in gomma.",
            )),
            Fase("Durante", rossoFase, listOf(
                "Esci solo se serve.",
                "In auto vai piano, usa marce basse e freno motore, niente manovre brusche.",
                "Libera l'auto da tutta la neve, non solo dai vetri.",
                "A piedi muoviti con cautela: il ghiaccio si forma su strade e marciapiedi.",
                "Usa il telefono solo se necessario, per non intasare le linee di soccorso.",
            )),
            Fase("Dopo", verdeFase, listOf(
                "Dopo la nevicata il ghiaccio resta: prudenza su strade e marciapiedi.",
                "Attenzione a neve e ghiaccio che cadono da tetti, cornicioni e rami.",
                "Con tanta neve sul tetto c'è rischio di crollo: fallo controllare.",
            )),
        ),
    ),
    Rischio(
        "Caldo",Icons.Filled.Thermostat, "È uno dei rischi del bollettino regionale, con i colori dell'allerta. In più il Ministero della Salute pubblica un bollettino a parte per 27 città, con livelli da 0 a 3: il livello 3 scatta dopo 3 giorni di fila a rischio elevato. I più esposti sono anziani, bambini e malati cronici.",
        listOf(
            Fase("Come comportarti", gialloFase, listOf(
                "Evita il sole e le uscite tra le 12 e le 18.",
                "Bevi molta acqua, niente alcolici, vestiti leggeri.",
                "Non lasciare mai bambini o animali in auto.",
                "Aiuta chi è più fragile a stare al fresco.",
            )),
            Fase("Se qualcuno sta male", rossoFase, listOf(
                "Crampi, capogiri, pelle calda e secca o confusione: portalo al fresco.",
                "Bagnalo con acqua fresca e chiama il 112.",
            )),
        ),
    ),
    Rischio(
        "Terremoto", Icons.Filled.Landslide, "Non esiste un'allerta: le scosse non si prevedono. Conta farsi trovare pronti.",
        listOf(
            Fase("Prima", gialloFase, listOf(
                "Fissa alla parete mobili e scaffali alti.",
                "Individua i punti sicuri della casa.",
                "Tieni a portata di mano torcia e radio a pile.",
            )),
            Fase("Durante", rossoFase, listOf(
                "In casa riparati nel vano di una porta in un muro portante, oppure sotto un letto o un tavolo robusto, lontano da vetri e mobili alti.",
                "Non usare l'ascensore.",
                "All'aperto allontanati da edifici, alberi e linee elettriche.",
            )),
            Fase("Dopo", verdeFase, listOf(
                "Controlla come stanno le persone vicino a te.",
                "Esci con prudenza e raggiungi le aree di attesa del Comune.",
                "Usa il telefono il meno possibile, per lasciare libere le linee.",
                "Se senti odore di gas chiudi il rubinetto, niente fiamme né interruttori.",
                "Segui solo le indicazioni ufficiali.",
            )),
        ),
    ),
    Rischio(
        "Industriale", Icons.Filled.Warning, "Incidente in uno stabilimento: si avverte con la sirena.",
        listOf(
            Fase("Al suono della sirena", rossoFase, listOf(
                "Chiuditi in un edificio, chiudi porte e finestre.",
                "Spegni aerazione e condizionatori.",
                "Segui radio, TV locali e notifiche del Comune.",
                "Non uscire e non intasare le linee telefoniche fino al cessato allarme, salvo evacuazione.",
            )),
        ),
    ),
)

private val kit = listOf(
    "Torcia e radio a pile, con pile di scorta",
    "Documenti e contanti in una busta impermeabile",
    "Farmaci personali e kit di primo soccorso",
    "Acqua e cibo a lunga conservazione per 2–3 giorni",
    "Power bank carico e caricabatterie",
    "Numeri di emergenza anche su carta",
)

@Composable
fun GuidaScreen(nav: Nav) {
    val ctx = LocalContext.current
    var zoom by remember { mutableStateOf(false) }
    var scelto by remember { mutableIntStateOf(0) }
    if (zoom) ZoomImage(io.github.andreadp271.castelsafe.R.drawable.infografica_allerte) { zoom = false }
    val r = rischi[scelto]
    Column(Modifier.fillMaxSize()) {
        Bar("Cosa fare", "Guida rapida in caso di allerta", onBack = nav::back)
        LazyColumn(contentPadding = screenPadding, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Surface(color = MaterialTheme.colorScheme.errorContainer, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("In pericolo?", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onErrorContainer)
                            Text("Chiama subito il 112. Il resto della guida viene dopo.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onErrorContainer)
                        }
                        Hspace(8.dp)
                        Button(onClick = { nav.go("contatti") }) { Icon(Icons.Filled.Call, null); Hspace(6.dp); Text("Numeri") }
                    }
                }
            }
            item {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    rischi.forEachIndexed { i, x ->
                        FilterChip(
                            selected = i == scelto, onClick = { scelto = i }, label = { Text(x.nome) },
                            leadingIcon = { Icon(x.icona, null, Modifier.size(18.dp)) },
                        )
                    }
                }
            }
            item {
                Text(r.nome, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Muted(r.sintesi)
            }
            r.fasi.forEach { fase ->
                item {
                    Panel {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(10.dp).clip(CircleShape).background(fase.colore))
                            Hspace(8.dp)
                            Text(fase.titolo, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        }
                        fase.passi.forEachIndexed { k, passo ->
                            Row(Modifier.padding(top = 8.dp)) {
                                Text("${k + 1}", Modifier.width(22.dp), fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                                Text(passo, style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                }
            }
            item {
                Panel {
                    Text("Tieni pronto in casa", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    kit.forEach { Text("•  $it", Modifier.padding(top = 6.dp), style = MaterialTheme.typography.bodyMedium) }
                }
            }
            // l'infografica vale solo per le allerte meteo-idro: non per caldo, terremoto e rischio industriale
            if (r.nome in setOf("Alluvione", "Temporali", "Vento forte")) item {
                Panel {
                    Text("Colori delle allerte meteo-idro", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text(
                        "Verde: nessuna criticità. Gialla, arancione e rossa indicano una criticità attesa sempre più grave. " +
                            "Si valutano i rischi idraulico, idrogeologico e, quando previsto, i temporali (per i temporali il massimo è l'arancione).",
                        Modifier.padding(top = 4.dp), style = MaterialTheme.typography.bodyMedium,
                    )
                    androidx.compose.foundation.Image(
                        androidx.compose.ui.res.painterResource(io.github.andreadp271.castelsafe.R.drawable.infografica_allerte),
                        "Infografica con i colori delle allerte meteo-idro",
                        Modifier.fillMaxWidth().padding(top = 10.dp).clip(RoundedCornerShape(12.dp)).clickable { zoom = true },
                        contentScale = androidx.compose.ui.layout.ContentScale.FillWidth,
                    )
                    Muted("Tocca per ingrandire", Modifier.align(Alignment.CenterHorizontally).padding(top = 4.dp))
                }
            }
            item {
                Muted(
                    "Indicazioni ispirate alla campagna nazionale \"Io non rischio\" e alle linee guida della Protezione Civile, a scopo informativo. " +
                        "In emergenza fai sempre riferimento alle comunicazioni ufficiali di Protezione Civile, Regione e Comune.",
                )
            }
            item {
                TextButton(onClick = { openUrl(ctx, "https://rischi.protezionecivile.it/it/meteo-idro/allertamento/tabella-allerte-e-scenari-di-criticita/") }) {
                    Text("Tabella ufficiale allerte e scenari")
                }
            }
            item { TextButton(onClick = { openUrl(ctx, "https://iononrischio.protezionecivile.it/") }) { Text("Io non rischio") } }
        }
    }
}

/** Immagine a schermo intero con zoom a due dita (come il lightbox della PWA). */
@Composable
fun ZoomImage(res: Int, onClose: () -> Unit) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color.Black).clickable { if (scale <= 1.01f) onClose() }) {
            androidx.compose.foundation.Image(
                androidx.compose.ui.res.painterResource(res), null,
                Modifier.fillMaxSize()
                    .pointerInput(Unit) {
                        detectTransformGestures { _, pan, z, _ ->
                            scale = (scale * z).coerceIn(1f, 5f)
                            offset = if (scale == 1f) Offset.Zero else offset + pan
                        }
                    }
                    .graphicsLayer { scaleX = scale; scaleY = scale; translationX = offset.x; translationY = offset.y },
                contentScale = androidx.compose.ui.layout.ContentScale.Fit,
            )
            IconButton(onClick = onClose, modifier = Modifier.align(Alignment.TopEnd).padding(16.dp)) {
                Icon(androidx.compose.material.icons.Icons.Filled.Close, "Chiudi", tint = androidx.compose.ui.graphics.Color.White)
            }
        }
    }
}

/**
 * Riga di una stazione nella Home: i suoi dati scorrono a carosello. Se uno o più dati
 * superano una soglia o salgono in fretta, il carosello si ferma su quello (o gira solo tra quelli).
 */
@Composable
private fun SensoreHomeRow(st: Station, stato: StatoStazione?, serie: List<Serie>?, preferita: Boolean, onPreferita: () -> Unit, onClick: (dato: String) -> Unit) {
    val cs = MaterialTheme.colorScheme
    val letture = stato?.sensori.orEmpty().filter { (t, l) -> t != "PRECCUM24_DRY" && t != "DVENTO" && l.valore != null }
    val tipi = letture.keys.sortedBy { if (it == st.main) 0 else 1 }
    val rapide = remember(serie) { serie?.let { Trend.rapide(it) }.orEmpty() }
    val evidenza = tipi.filter { it in rapide || Stations.level(st.id, it, letture[it]?.valore) >= Level.GIALLO }
    val giro = evidenza.ifEmpty { tipi }
    var i by remember(giro) { mutableIntStateOf(0) }
    LaunchedEffect(giro) {
        if (giro.size > 1) while (true) { delay(3500); i = (i + 1) % giro.size }
    }
    val tipo = giro.getOrNull(i) ?: st.main

    Row(
        Modifier.fillMaxWidth().clickable { onClick(tipo) }.padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(st.name, style = MaterialTheme.typography.bodyLarge)
                Text(
                    if (preferita) "★" else "☆", color = if (preferita) cs.primary else cs.onSurfaceVariant,
                    modifier = Modifier.clickable(onClick = onPreferita).padding(horizontal = 8.dp, vertical = 4.dp),
                )
                if (giro.size > 1) {
                    Hspace(8.dp)
                    giro.forEachIndexed { k, _ ->
                        Box(Modifier.padding(horizontal = 1.5.dp).size(5.dp).clip(CircleShape).background(if (k == i) cs.primary else cs.outlineVariant))
                    }
                }
            }
            AnimatedContent(tipo, transitionSpec = carosello, label = "sottotitolo") { t ->
                val l = letture[t]
                val salita = rapide[t]
                Column {
                    Text(
                        "${Stations.label(t)} · ${if (stato?.stale == true) "dato non aggiornato" else "ore ${l?.istante ?: "—"}"}",
                        style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant,
                    )
                    if (salita != null) Text(
                        if (t == "PREC") "▲ Pioggia intensa: ${fmtValue(salita.delta, salita.unita)} nell'ultima ora"
                        else "▲ In rapida salita: +${fmtValue(salita.delta, salita.unita)} in un'ora",
                        style = MaterialTheme.typography.bodySmall, color = cs.error, fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        }
        Hspace(8.dp)
        AnimatedContent(tipo, transitionSpec = carosello, label = "valore") { t ->
            val l = letture[t]
            val level = Stations.level(st.id, t, l?.valore)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (t in rapide) Text("▲", color = cs.error, fontWeight = FontWeight.Bold)
                else serie?.let { Trend.direzione(it, t) }?.let { d ->
                    // sale = arancione, scende = verde, stabile = grigio
                    Text(
                        when (d) { 1 -> "↗"; -1 -> "↘"; else -> "→" },
                        color = when (d) { 1 -> Color(0xFFEA580C); -1 -> Color(0xFF22C55E); else -> cs.onSurfaceVariant },
                        fontWeight = FontWeight.Bold,
                    )
                }
                Text(fmtValue(l?.valore, l?.unita.orEmpty()), fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleMedium)
                if (level != Level.NESSUNO) LevelDot(level)
            }
        }
    }
}

private val carosello: AnimatedContentTransitionScope<String>.() -> ContentTransform = {
    (slideInVertically { it / 2 } + fadeIn()) togetherWith (slideOutVertically { -it / 2 } + fadeOut())
}
