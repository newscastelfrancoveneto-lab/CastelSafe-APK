package io.github.andreadp271.castelsafe.ui

import android.Manifest
import android.annotation.SuppressLint
import android.app.TimePickerDialog
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import io.github.andreadp271.castelsafe.BuildConfig
import io.github.andreadp271.castelsafe.R
import io.github.andreadp271.castelsafe.data.Categoria
import io.github.andreadp271.castelsafe.data.Config
import io.github.andreadp271.castelsafe.data.Contact
import io.github.andreadp271.castelsafe.data.Contacts
import io.github.andreadp271.castelsafe.data.Level
import io.github.andreadp271.castelsafe.data.Modo
import io.github.andreadp271.castelsafe.data.Notifier
import io.github.andreadp271.castelsafe.data.NotifySettings
import io.github.andreadp271.castelsafe.data.Repo
import io.github.andreadp271.castelsafe.data.Stations
import io.github.andreadp271.castelsafe.data.Updater
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

/** Ultima release trovata su GitHub in questa sessione (per mostrare l'avviso nel menu). */
private val availableUpdate = MutableStateFlow<Updater.Release?>(null)
private var updateChecked = false

@Composable
fun AltroScreen(nav: Nav) {
    val ctx = LocalContext.current
    val update by availableUpdate.collectAsState()
    LaunchedEffect(Unit) {
        if (!updateChecked) {
            updateChecked = true
            runCatching { Updater.latest() }.getOrNull()?.takeIf { Updater.isNewer(it.version) }?.let { availableUpdate.value = it }
        }
    }
    Column(Modifier.fillMaxSize()) {
        Bar("Altro")
        LazyColumn(contentPadding = screenPadding, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            update?.let { u ->
                item {
                    Panel(onClick = { nav.go("aggiornamenti") }) {
                        Text("Aggiornamento disponibile", fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
                        Muted("Versione ${u.version} · tocca per installarla")
                    }
                }
            }
            item { SectionTitle("Informarsi") }
            item {
                Panel(padding = 0.dp) {
                    MenuRow("Meteo e previsioni", "Bollettino ARPAV per Treviso e pianura", iconPainter = painterResource(R.drawable.ic_tab_sensori)) { nav.go("meteo") }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    MenuRow("Radar precipitazioni", "Dove sta piovendo adesso (Protezione Civile)", iconPainter = painterResource(R.drawable.ic_tab_mappa)) { openUrl(ctx, Config.RADAR_URL) }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    MenuRow("Cosa fare in caso di allerta", "Alluvione, temporali, vento, calore, terremoto", icon = Icons.Filled.Warning) { nav.go("guida") }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    MenuRow("Numeri utili", "Emergenze nazionali e Protezione Civile locale", icon = Icons.Filled.Call) { nav.go("contatti") }
                }
            }
            item { SectionTitle("App") }
            item {
                Panel(padding = 0.dp) {
                    MenuRow("Notifiche", "Scegli quali avvisi ricevere e come", icon = Icons.Filled.Notifications) { nav.go("notifiche") }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    MenuRow("Aggiornamenti", "Versione ${BuildConfig.VERSION_NAME}", icon = Icons.Filled.Refresh) { nav.go("aggiornamenti") }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    MenuRow("Condividi CastelSafe", "Invia l'app ad amici e familiari", icon = Icons.Filled.Share) {
                        share(ctx, "CastelSafe: allerte meteo, livelli dei fiumi, avvisi e chiusure a Castelfranco Veneto.\nAndroid: https://github.com/${Config.GITHUB_REPO}/releases/latest\nWeb e iPhone: ${Config.SITE_URL}")
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    MenuRow("Informazioni e privacy", icon = Icons.Filled.Info) { nav.go("info") }
                }
            }
        }
    }
}

// ── Meteo ────────────────────────────────────────────────────────────────────

@Composable
fun MeteoScreen(nav: Nav) {
    val res by Repo.meteo.collectAsState()
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    LaunchedEffect(Unit) { Repo.loadMeteo() }
    Column(Modifier.fillMaxSize()) {
        Bar("Meteo", Config.WEATHER_ZONE, onBack = nav::back, actions = {
            androidx.compose.material3.IconButton(onClick = { scope.launch { Repo.loadMeteo(true) } }) { Icon(Icons.Filled.Refresh, "Aggiorna") }
        })
        if (res.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        LazyColumn(contentPadding = screenPadding, verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item { ResNotice(res, "Previsioni") }
            res.data?.let { m ->
                item { Muted("Bollettino ARPAV emesso il ${m.emissione}, aggiornato il ${m.aggiornamento}") }
                m.scadenze.groupBy { it.giorno }.forEach { (giorno, list) ->
                    item { SectionTitle(giorno) }
                    item {
                        Panel(padding = 0.dp) {
                            list.forEachIndexed { i, s ->
                                if (i > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                                    RemoteIcon(s.simbolo, 52.dp)
                                    Hspace(14.dp)
                                    Column(Modifier.weight(1f)) {
                                        Text((s.fascia ?: "Giornata").replaceFirstChar { it.uppercase() }, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        Text(s.cielo, fontWeight = FontWeight.SemiBold)
                                        Muted(listOf(
                                            s.temperatura,
                                            s.precipitazioni.takeIf { it.isNotBlank() }?.let { "Precipitazioni: ${it.lowercase()}" },
                                            s.probabilita.takeIf { it.isNotBlank() }?.let { "probabilità $it" },
                                            s.quotaNeve.takeIf { it.isNotBlank() }?.let { "neve $it" },
                                        ).filterNotNull().filter { it.isNotBlank() }.joinToString(" · "))
                                        if (s.attendibilita.isNotBlank()) Muted("Attendibilità: ${s.attendibilita.lowercase()}")
                                    }
                                }
                            }
                        }
                    }
                }
            }
            item {
                Muted("Fonte: ARPAV Veneto, bollettino meteo per zone.")
                TextButton(onClick = { openUrl(ctx, Config.RADAR_URL) }) { Text("Radar precipitazioni (Protezione Civile)") }
            }
        }
    }
}

// ── Contatti ─────────────────────────────────────────────────────────────────

@Composable
fun ContattiScreen(nav: Nav) {
    val ctx = LocalContext.current
    Column(Modifier.fillMaxSize()) {
        Bar("Numeri utili", "Tocca per chiamare", onBack = nav::back)
        LazyColumn(contentPadding = screenPadding, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item { SectionTitle("Emergenze nazionali") }
            item { ContactList(Contacts.national) { dial(ctx, it.tel) } }
            item { SectionTitle("Contatti locali") }
            item { ContactList(Contacts.local) { dial(ctx, it.tel) } }
            item { Muted("Usa i numeri locali per segnalazioni e coordinamento sul territorio. Per emergenze immediate chiama i numeri nazionali.") }
        }
    }
}

@Composable
private fun ContactList(list: List<Contact>, onCall: (Contact) -> Unit) {
    Panel(padding = 0.dp) {
        list.forEachIndexed { i, c ->
            if (i > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Row(Modifier.fillMaxWidth().clickable { onCall(c) }.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(c.name, fontWeight = FontWeight.SemiBold)
                    Muted(listOfNotNull(c.note, c.display).joinToString(" · "))
                }
                FilledTonalIconButton(onClick = { onCall(c) }) { Icon(Icons.Filled.Call, "Chiama ${c.display}") }
            }
        }
    }
}

// ── Notifiche ────────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@SuppressLint("BatteryLife")
@Composable
fun NotificheScreen(nav: Nav) {
    val ctx = LocalContext.current
    val s = remember { NotifySettings(ctx) }
    // le preferenze sono in SharedPreferences: [v] forza il ridisegno dopo ogni modifica
    var v by remember { mutableIntStateOf(0) }
    fun upd(block: () -> Unit) { block(); v++ }
    var canNotify by remember { mutableStateOf(Notifier.canNotify(ctx)) }
    var battery by remember { mutableStateOf(Notifier.batteryUnrestricted(ctx)) }

    // al ritorno dalle impostazioni di sistema
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val obs = LifecycleEventObserver { _, e ->
            if (e == Lifecycle.Event.ON_RESUME) { canNotify = Notifier.canNotify(ctx); battery = Notifier.batteryUnrestricted(ctx); v++ }
        }
        lifecycle.addObserver(obs)
        onDispose { lifecycle.removeObserver(obs) }
    }
    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { canNotify = it }
    fun appSettings() = ctx.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName))

    Column(Modifier.fillMaxSize()) {
        Bar("Notifiche", "Cosa ricevere e come", onBack = nav::back)
        LazyColumn(contentPadding = screenPadding, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            // stato
            item {
                v
                Panel {
                    Text("Stato", fontWeight = FontWeight.SemiBold)
                    StatusLine(canNotify, if (canNotify) "Notifiche consentite" else "Notifiche bloccate: non riceverai nessun avviso")
                    if (!canNotify) Button(onClick = {
                        if (Build.VERSION.SDK_INT >= 33) permLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) else appSettings()
                    }, Modifier.padding(top = 4.dp)) { Text("Consenti notifiche") }
                    StatusLine(battery, if (battery) "Nessuna restrizione di batteria" else "Ottimizzazione batteria attiva: i controlli possono essere rimandati")
                    if (!battery) FilledTonalButton(onClick = {
                        runCatching {
                            ctx.startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${ctx.packageName}")))
                        }.onFailure { ctx.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
                    }, Modifier.padding(top = 4.dp)) { Text("Togli la restrizione") }
                    val last = s.lastCheck.takeIf { it > 0 }?.let { LocalDateTime.ofInstant(Instant.ofEpochMilli(it), ZoneId.systemDefault()) }
                    Muted(
                        "L'app controlla il servizio ogni 15 minuti circa e quando la apri. Ultimo controllo: ${ago(last)}" +
                            (s.lastCheckError?.let { " (errore: $it)" } ?: "."),
                        Modifier.padding(top = 8.dp),
                    )
                }
            }

            Categoria.entries.forEach { c ->
                item {
                    v
                    Panel {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(c.titolo, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                                Muted(c.descrizione)
                            }
                            Switch(s.enabled(c), { on -> upd { s.setEnabled(c, on) } })
                        }
                        if (s.enabled(c)) {
                            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(top = 12.dp)) {
                                Modo.entries.forEachIndexed { i, m ->
                                    SegmentedButton(s.modo(c) == m, { upd { s.setModo(c, m) } }, SegmentedButtonDefaults.itemShape(i, Modo.entries.size)) { Text(m.label) }
                                }
                            }
                            when (c) {
                                Categoria.ALLERTA -> {
                                    Text("Avvisami per", Modifier.padding(top = 12.dp), fontWeight = FontWeight.Medium)
                                    listOf(
                                        Level.VERDE to "Ogni bollettino, anche senza allerta e i rientri",
                                        Level.GIALLO to "Allerta gialla, arancione o rossa",
                                        Level.ARANCIONE to "Allerta arancione o rossa",
                                        Level.ROSSO to "Solo allerta rossa",
                                    ).forEach { (l, label) -> RadioRow(s.allertaMin == l, label) { upd { s.allertaMin = l } } }
                                }
                                Categoria.SENSORI -> {
                                    Text("Soglia minima", Modifier.padding(top = 12.dp), fontWeight = FontWeight.Medium)
                                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(top = 4.dp)) {
                                        val opts = listOf(Level.GIALLO, Level.ARANCIONE, Level.ROSSO)
                                        opts.forEachIndexed { i, l ->
                                            SegmentedButton(s.sensoriMin == l, { upd { s.sensoriMin = l } }, SegmentedButtonDefaults.itemShape(i, opts.size), icon = { LevelDot(l, 10.dp) }) {
                                                Text(l.label)
                                            }
                                        }
                                    }
                                    CheckRow(s.sensoriRientri, "Avvisami anche quando il valore rientra") { upd { s.sensoriRientri = it } }
                                    Text("Stazioni", Modifier.padding(top = 8.dp), fontWeight = FontWeight.Medium)
                                    Stations.all.filter { it.monitored }.forEach { st ->
                                        CheckRow(s.stazione(st.id), "${st.name} (${st.subtitle})") { upd { s.setStazione(st.id, it) } }
                                    }
                                }
                                else -> {}
                            }
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                                TextButton(onClick = { Notifier.test(ctx, c) }, enabled = canNotify) { Text("Prova") }
                            }
                        }
                    }
                }
            }

            item {
                v
                Panel {
                    Text("Situazioni gravi e notte", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Livello rosso sempre con suono")
                            Muted("Allerta rossa e sensori oltre la soglia massima suonano e vibrano a lungo, anche se la categoria è silenziosa o sei nelle ore di quiete.")
                        }
                        Switch(s.rossoSempre, { upd { s.rossoSempre = it } })
                    }
                    Row(Modifier.padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Ore di quiete")
                            Muted("In questa fascia le notifiche arrivano in silenzio.")
                        }
                        Switch(s.quiete, { upd { s.quiete = it } })
                    }
                    if (s.quiete) Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { pickTime(ctx, s.quieteDa) { upd { s.quieteDa = it } } }) { Text("Dalle ${hm(s.quieteDa)}") }
                        OutlinedButton(onClick = { pickTime(ctx, s.quieteA) { upd { s.quieteA = it } } }) { Text("Alle ${hm(s.quieteA)}") }
                    }
                }
            }
            item {
                Panel(onClick = { appSettings() }) {
                    Text("Suoni e vibrazioni di sistema", fontWeight = FontWeight.SemiBold)
                    Muted("Scegli la suoneria di ogni tipo di notifica, la vibrazione o se mostrarla sulla schermata di blocco, dalle impostazioni di Android.")
                }
            }
        }
    }
}

@Composable
private fun StatusLine(ok: Boolean, text: String) {
    Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        LevelDot(if (ok) Level.VERDE else Level.ARANCIONE, 10.dp)
        Hspace(8.dp)
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun RadioRow(selected: Boolean, label: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick), verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected, onClick)
        Text(label, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun CheckRow(checked: Boolean, label: String, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onChange(!checked) }, verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked, onChange)
        Text(label, style = MaterialTheme.typography.bodyMedium)
    }
}

private fun hm(m: Int) = "%02d:%02d".format(m / 60, m % 60)

private fun pickTime(ctx: android.content.Context, current: Int, onPick: (Int) -> Unit) {
    TimePickerDialog(ctx, { _, h, min -> onPick(h * 60 + min) }, current / 60, current % 60, true).show()
}

// ── Informazioni ─────────────────────────────────────────────────────────────

@Composable
fun InfoScreen(nav: Nav) {
    val ctx = LocalContext.current
    Column(Modifier.fillMaxSize()) {
        Bar("Informazioni", "CastelSafe ${BuildConfig.VERSION_NAME}", onBack = nav::back)
        LazyColumn(contentPadding = screenPadding, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                InfoBlock("A cosa serve", "CastelSafe aiuta i cittadini di Castelfranco Veneto a consultare rapidamente allerte meteo regionali, previsioni, " +
                    "sensori ARPAV, aree di protezione civile, avvisi temporanei e contatti utili, con notifiche per essere avvisati in tempo.")
            }
            item {
                InfoBlock("Aree di protezione civile", "Area di attesa (verde): punto di raccolta per la popolazione in caso di evacuazione.\n" +
                    "Area di ricovero (rossa): struttura che ospita temporaneamente la popolazione evacuata.\n" +
                    "Area di ammassamento (gialla): riservata a soccorritori e mezzi, non destinata alla popolazione.\n" +
                    "Raggiungile solo su indicazione delle autorità.")
            }
            item {
                InfoBlock("Notifiche", "L'app non usa servizi Google: controlla da sola il server di CastelSafe ogni 15 minuti circa e ogni volta che la apri. " +
                    "Per non perdere avvisi, consenti le notifiche e togli la restrizione di batteria da Altro › Notifiche.")
            }
            item {
                InfoBlock("Privacy e statistiche", "Nessun account, nessun cookie, nessuna posizione inviata. Per contare quante persone usano l'app, " +
                    "il telefono conserva un codice casuale che il server trasforma con una chiave diversa ogni giorno: non è possibile risalire a te " +
                    "né seguirti da un giorno all'altro, e l'indirizzo IP non viene salvato. La posizione GPS, se la attivi, resta sul telefono e serve solo a mostrarti sulla mappa.")
            }
            item {
                InfoBlock("Fonti dei dati", "Bollettini di allerta: Protezione Civile Regione Veneto. Sensori e previsioni: ARPAV Veneto (CC BY 4.0), dati in tempo reale non validati. " +
                    "Mappa, aree e corsi d'acqua: © contributori OpenStreetMap (ODbL). Radar: Dipartimento della Protezione Civile.")
            }
            item {
                InfoBlock("Nota importante", "CastelSafe è uno strumento informativo e non sostituisce i canali ufficiali. In caso di emergenza segui sempre le " +
                    "indicazioni di Protezione Civile, Regione e Comune e chiama i numeri di emergenza.")
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { openUrl(ctx, Config.SITE_URL) }) { Text("Versione web") }
                    TextButton(onClick = { openUrl(ctx, "https://github.com/${Config.GITHUB_REPO}") }) { Text("Codice sorgente") }
                }
            }
        }
    }
}

@Composable
private fun InfoBlock(title: String, text: String) {
    Panel {
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Text(text, Modifier.padding(top = 4.dp), style = MaterialTheme.typography.bodyMedium)
    }
}

// ── Aggiornamenti ────────────────────────────────────────────────────────────

@Composable
fun UpdateScreen(nav: Nav) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var checking by remember { mutableStateOf(false) }
    var rel by remember { mutableStateOf(availableUpdate.value) }
    var error by remember { mutableStateOf<String?>(null) }
    var upToDate by remember { mutableStateOf(false) }
    var progress by remember { mutableFloatStateOf(-1f) }

    fun check() = scope.launch {
        checking = true; error = null
        runCatching { Updater.latest() }
            .onSuccess { r -> if (Updater.isNewer(r.version)) { rel = r; availableUpdate.value = r } else { upToDate = true; rel = null } }
            .onFailure { error = Repo.errorText(it) }
        checking = false
    }
    LaunchedEffect(Unit) { if (rel == null) check() }
    DisposableEffect(Unit) {
        Updater.onFinished = { e -> progress = -1f; error = e }
        onDispose { Updater.onFinished = null }
    }

    Column(Modifier.fillMaxSize()) {
        Bar("Aggiornamenti", "Versione installata ${BuildConfig.VERSION_NAME}", onBack = nav::back)
        LazyColumn(contentPadding = screenPadding, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Panel {
                    when {
                        checking -> { Text("Controllo in corso…"); LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp)) }
                        rel != null -> {
                            Text("Disponibile la versione ${rel!!.version}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                            if (rel!!.notes.isNotBlank()) Text(rel!!.notes, Modifier.padding(top = 6.dp), style = MaterialTheme.typography.bodyMedium)
                            if (progress >= 0f) LinearProgressIndicator({ progress }, Modifier.fillMaxWidth().padding(top = 12.dp))
                            Button(
                                enabled = progress < 0f,
                                onClick = {
                                    if (!Updater.canInstall(ctx)) {
                                        ctx.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${ctx.packageName}")))
                                        return@Button
                                    }
                                    scope.launch {
                                        error = null; progress = 0f
                                        runCatching {
                                            val f = Updater.download(ctx, rel!!) { progress = it }
                                            Updater.install(ctx, f)
                                        }.onFailure { error = Repo.errorText(it); progress = -1f }
                                    }
                                },
                                modifier = Modifier.padding(top = 12.dp),
                            ) { Text(if (Updater.canInstall(ctx)) "Scarica e installa" else "Consenti l'installazione") }
                            if (!Updater.canInstall(ctx)) Muted("Android chiede di autorizzare CastelSafe a installare i propri aggiornamenti: attiva l'opzione e torna qui.", Modifier.padding(top = 6.dp))
                        }
                        upToDate -> Text("Hai già l'ultima versione.", fontWeight = FontWeight.SemiBold)
                    }
                    error?.let { Text(it, Modifier.padding(top = 8.dp), color = MaterialTheme.colorScheme.error) }
                }
            }
            item { OutlinedButton(onClick = { check() }, enabled = !checking) { Text("Controlla di nuovo") } }
            item { Muted("Gli aggiornamenti vengono scaricati dalla pagina ufficiale delle release su GitHub e installati solo se firmati come questa app.") }
        }
    }
}
