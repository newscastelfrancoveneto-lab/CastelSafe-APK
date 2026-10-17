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
import io.github.andreadp271.castelsafe.data.Alarm
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

/** Ultima release trovata su GitHub in questa sessione (avviso nelle impostazioni). */
internal val availableUpdate = MutableStateFlow<Updater.Release?>(null)

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
