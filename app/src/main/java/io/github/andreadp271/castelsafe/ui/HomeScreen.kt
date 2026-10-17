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
import androidx.compose.material.icons.filled.Call
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
                        Stations.all.filter { it.monitored }.forEachIndexed { i, st ->
                            if (i > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                            val serie by Repo.stazione(st.id).collectAsState()
                            SensoreHomeRow(st, monitor.data?.get(st.id), serie.data) { nav.tab("sensori"); nav.go("stazione/${st.id}") }
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
                    rischiSimboli.forEach { (simbolo, nome) ->
                        Column(Modifier.weight(0.152f), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(simbolo, fontSize = 20.sp)
                            Text(nome, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
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

/** Simbolo e nome breve dei rischi, nell'ordine di [AllertaGiorno.rischi]. */
private val rischiSimboli = listOf(
    "🌊" to "Idraulico",
    "⛰️" to "Frane",
    "⛈️" to "Temporali",
    "💨" to "Vento",
    "🌡️" to "Calore",
)

@Composable
private fun GiornoRow(label: String, g: AllertaGiorno?) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(0.24f), fontWeight = FontWeight.SemiBold)
        rischiSimboli.indices.forEach { i ->
            Box(Modifier.weight(0.152f), contentAlignment = Alignment.Center) {
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

private val guida = listOf(
    "Colori delle allerte" to listOf(
        "" to "I colori indicano un livello crescente di criticità attesa: verde (nessuna criticità), gialla, arancione e rossa. " +
            "La valutazione riguarda i rischi idraulico, idrogeologico e, quando previsto, i temporali. Per il rischio temporali il livello massimo previsto è l'arancione.",
    ),
    "Alluvione (rischio idraulico e idrogeologico)" to listOf(
        "Durante l'allerta" to "Leggi il bollettino e tieni attive le notifiche dell'app per ricevere gli allarmi di torrenti e precipitazioni. Segui ogni indicazione ufficiale del Comune. " +
            "Non dormire né soggiornare nei piani seminterrati. Proteggi i locali al piano strada e chiudi porte di cantine e garage solo se non ti esponi a pericoli. " +
            "Se devi viaggiare, controlla nell'app le chiusure stradali. Valuta bene se mettere al sicuro l'auto o altri beni: può essere pericoloso.",
        "Durante l'alluvione" to "Se sei in casa, spostati ai piani alti e chiudi porte e finestre. Chiudi il gas e disattiva l'impianto elettrico solo se non rischi di bagnarti. " +
            "Non usare ascensori. Non cercare strade \"alternative\": 20–30 cm d'acqua bastano a trascinare auto e pedoni. " +
            "Allontanati dalle zone allagate verso aree elevate o le aree di attesa indicate dal Comune. Segui solo le autorità.",
        "Dopo l'alluvione" to "Attendi il cessato allarme prima di rientrare. Fai verificare gas, elettricità e acqua; non bere dal rubinetto e getta i cibi contaminati. " +
            "Attenzione a voragini, cavi scoperti, detriti ed edifici instabili. Segnala i danni al Comune.",
    ),
    "Temporali forti" to listOf(
        "Prima" to "Evita attività all'aperto (escursioni, sport, lavori in quota) e segui l'evoluzione meteo.",
        "Durante" to "All'aperto cerca riparo in un edificio o in auto con i finestrini chiusi. Evita alberi isolati, pali, tralicci e specchi d'acqua. " +
            "In casa chiudi porte e finestre e scollega i dispositivi sensibili. Attenzione a grandine e raffiche improvvise.",
        "Dopo" to "Segnala i danni al Comune ed evita alberi o rami pericolanti e cavi elettrici a terra.",
    ),
    "Vento forte" to listOf(
        "Prima" to "Metti al sicuro oggetti, ombrelloni, tende e mobili da giardino; verifica coperture provvisorie e grondaie.",
        "Durante" to "Non sostare sotto alberi, impalcature, cartelloni e cornicioni. Alla guida modera la velocità su ponti e strade esposte. Evita spostamenti non necessari.",
        "Dopo" to "Segnala alberi pericolanti e danni; non avvicinarti a cavi elettrici caduti.",
    ),
    "Ondate di calore" to listOf(
        "Come comportarti" to "Evita sole e uscite nelle ore più calde (12–18). Bevi molta acqua, evita alcolici, indossa abiti leggeri. " +
            "Non lasciare mai bambini o animali in auto. Aiuta anziani, bambini e malati cronici a stare al fresco.",
        "In caso di malore" to "Con crampi, capogiri, pelle calda e secca o confusione: sposta la persona al fresco, bagnala con acqua fresca e chiama il 112.",
    ),
    "Terremoto" to listOf(
        "Prima" to "Fissa alla parete mobili e scaffali alti. Individua i punti sicuri della casa e tieni a portata di mano torcia e radio a pile.",
        "Durante" to "In casa riparati sotto un tavolo robusto, lontano da vetri e mobili alti; non usare ascensori. All'aperto allontanati da edifici, alberi e linee elettriche.",
        "Dopo" to "Esci con prudenza. Se senti odore di gas chiudi il rubinetto e non accendere fiamme o interruttori. Segui solo le indicazioni ufficiali.",
    ),
    "Rischio industriale" to listOf(
        "Al suono della sirena" to "Chiuditi in un edificio, chiudi porte e finestre e spegni aerazione e condizionatori. Segui radio, TV locali e notifiche del Comune. " +
            "Non uscire e non intasare le linee telefoniche fino al cessato allarme, salvo evacuazione disposta dalle autorità.",
    ),
)

@Composable
fun GuidaScreen(nav: Nav) {
    val ctx = LocalContext.current
    var zoom by remember { mutableStateOf(false) }
    if (zoom) ZoomImage(io.github.andreadp271.castelsafe.R.drawable.infografica_allerte) { zoom = false }
    Column(Modifier.fillMaxSize()) {
        Bar("Cosa fare", "Guida rapida in caso di allerta", onBack = nav::back)
        LazyColumn(contentPadding = screenPadding, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Panel {
                    Text("Colori delle allerte", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text(
                        "I colori dell'allerta indicano un livello crescente di criticità attesa sul territorio. La valutazione riguarda i rischi idraulico, " +
                            "idrogeologico e, quando previsto, i temporali. Per il rischio temporali il livello massimo previsto è l'arancione.",
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
            guida.drop(1).forEach { (titolo, parti) ->
                item {
                    Panel {
                        Text(titolo, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        parti.forEach { (sub, testo) ->
                            if (sub.isNotBlank()) Text(sub, Modifier.padding(top = 10.dp), fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
                            Text(testo, Modifier.padding(top = 4.dp), style = MaterialTheme.typography.bodyMedium)
                        }
                    }
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
private fun SensoreHomeRow(st: Station, stato: StatoStazione?, serie: List<Serie>?, onClick: () -> Unit) {
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
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(st.name, style = MaterialTheme.typography.bodyLarge)
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
                Text(fmtValue(l?.valore, l?.unita.orEmpty()), fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleMedium)
                if (level != Level.NESSUNO) LevelDot(level)
            }
        }
    }
}

private val carosello: AnimatedContentTransitionScope<String>.() -> ContentTransform = {
    (slideInVertically { it / 2 } + fadeIn()) togetherWith (slideOutVertically { -it / 2 } + fadeOut())
}
