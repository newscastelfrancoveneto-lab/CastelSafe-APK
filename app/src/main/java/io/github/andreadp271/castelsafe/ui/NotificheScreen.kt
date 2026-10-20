package io.github.andreadp271.castelsafe.ui

import android.Manifest
import android.app.TimePickerDialog
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import io.github.andreadp271.castelsafe.data.Alarm
import io.github.andreadp271.castelsafe.data.Level
import io.github.andreadp271.castelsafe.data.Modo
import io.github.andreadp271.castelsafe.data.Notifier
import io.github.andreadp271.castelsafe.data.NotifySettings
import io.github.andreadp271.castelsafe.data.Stations
import io.github.andreadp271.castelsafe.data.SuonoAllarme
import io.github.andreadp271.castelsafe.data.Voce
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Impostazioni delle notifiche: per ogni cosa notificabile (allerta, ogni dato di ogni stazione,
 * avvisi, comunicazioni) e per ogni livello si sceglie: nessuna notifica, silenziosa, con suono o allarme.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotificheScreen(nav: Nav) {
    val ctx = LocalContext.current
    val s = remember { NotifySettings(ctx) }
    // le preferenze sono in SharedPreferences: [v] forza il ridisegno dopo ogni modifica
    var v by remember { mutableIntStateOf(0) }
    fun upd(block: () -> Unit) { block(); v++ }
    var canNotify by remember { mutableStateOf(Notifier.canNotify(ctx)) }
    var battery by remember { mutableStateOf(Notifier.batteryUnrestricted(ctx)) }
    var editing by remember { mutableStateOf<Voce?>(null) }

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
        LazyColumn(contentPadding = screenPadding, verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (!canNotify || !battery) item {
                Panel {
                    if (!canNotify) {
                        StatusLine(false, "Notifiche bloccate: non riceverai nessun avviso")
                        Button(onClick = {
                            if (Build.VERSION.SDK_INT >= 33) permLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) else appSettings()
                        }, Modifier.padding(top = 4.dp)) { Text("Consenti notifiche") }
                    }
                    if (!battery) {
                        StatusLine(false, "Ottimizzazione batteria attiva: i controlli possono essere rimandati")
                        FilledTonalButton(onClick = {
                            runCatching {
                                ctx.startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${ctx.packageName}")))
                            }.onFailure { ctx.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
                        }, Modifier.padding(top = 4.dp)) { Text("Togli la restrizione") }
                    }
                }
            }

            item {
                v
                Legenda()
            }

            item { SectionTitle("Allerta meteo") }
            item { v; Panel(padding = 0.dp) { VoceRow(s, Voce.ALLERTA, "Bollettino della Protezione Civile del Veneto") { editing = Voce.ALLERTA } } }

            item { SectionTitle("Sensori ARPAV") }
            Stations.all.filter { it.monitored }.forEach { st ->
                item {
                    v
                    Panel(padding = 0.dp) {
                        Text(st.name, Modifier.padding(start = 16.dp, top = 12.dp, end = 16.dp), fontWeight = FontWeight.Bold)
                        Muted(st.subtitle, Modifier.padding(horizontal = 16.dp))
                        Voce.sensori().filter { it.stazione == st.id }.forEachIndexed { i, voce ->
                            if (i > 0) HorizontalDivider(Modifier.padding(start = 16.dp), color = MaterialTheme.colorScheme.outlineVariant)
                            VoceRow(s, voce) { editing = voce }
                        }
                    }
                }
            }

            item { SectionTitle("Avvisi e comunicazioni") }
            item {
                v
                Panel(padding = 0.dp) {
                    VoceRow(s, Voce.AVVISI, "Chiusure di strade e aree, divieti") { editing = Voce.AVVISI }
                    HorizontalDivider(Modifier.padding(start = 16.dp), color = MaterialTheme.colorScheme.outlineVariant)
                    VoceRow(s, Voce.COMUNICAZIONI, "Messaggi diretti della Protezione Civile") { editing = Voce.COMUNICAZIONI }
                }
            }

            item { SectionTitle("Allarme sonoro") }
            item {
                v
                val alarmOn by Alarm.active.collectAsState()
                Panel {
                    Muted("Dove hai scelto «Allarme», suona una sirena anche con il telefono in silenzioso o in vibrazione, finché non la fermi (al massimo un minuto).")
                    Text("Quando", Modifier.padding(top = 12.dp), fontWeight = FontWeight.Medium)
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(top = 4.dp)) {
                        SegmentedButton(!s.allarmeSoloNotte, { upd { s.allarmeSoloNotte = false } }, SegmentedButtonDefaults.itemShape(0, 2)) { Text("Sempre") }
                        SegmentedButton(s.allarmeSoloNotte, { upd { s.allarmeSoloNotte = true } }, SegmentedButtonDefaults.itemShape(1, 2)) { Text("Solo di notte") }
                    }
                    if (s.allarmeSoloNotte) {
                        Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { pickTime(ctx, s.allarmeDa) { upd { s.allarmeDa = it } } }) { Text("Dalle ${hm(s.allarmeDa)}") }
                            OutlinedButton(onClick = { pickTime(ctx, s.allarmeA) { upd { s.allarmeA = it } } }) { Text("Alle ${hm(s.allarmeA)}") }
                        }
                        Muted("Fuori da questa fascia arriva una notifica con suono.")
                    }
                    Text("Suono", Modifier.padding(top = 12.dp), fontWeight = FontWeight.Medium)
                    SuonoAllarme.entries.forEach { suono ->
                        Row(Modifier.fillMaxWidth().clickable { upd { s.allarmeSuono = suono } }, verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(s.allarmeSuono == suono, { upd { s.allarmeSuono = suono } })
                            Column(Modifier.weight(1f)) {
                                Text(suono.label, style = MaterialTheme.typography.bodyMedium)
                                Muted(suono.descrizione)
                            }
                        }
                    }
                    CheckRow(s.allarmeVolumeMax, "Alza al massimo il volume mentre suona") { upd { s.allarmeVolumeMax = it } }
                    Muted("Con «Non disturbare» attivo suona solo se in Android le sveglie sono consentite (di solito lo sono).")
                    Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.End) {
                        if (alarmOn) Button(onClick = { Alarm.stop(ctx) }) { Text("Ferma") }
                        else TextButton(onClick = { Alarm.start(ctx, 5) }) { Text("Prova (5 secondi)") }
                    }
                }
            }

            item { SectionTitle("Ore di quiete") }
            item {
                v
                Panel {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Ore di quiete")
                            Muted("In questa fascia le notifiche con suono arrivano in silenzio. L'allarme non cambia: ha la sua fascia.")
                        }
                        Switch(s.quiete, { upd { s.quiete = it } })
                    }
                    if (s.quiete) {
                        Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { pickTime(ctx, s.quieteDa) { upd { s.quieteDa = it } } }) { Text("Dalle ${hm(s.quieteDa)}") }
                            OutlinedButton(onClick = { pickTime(ctx, s.quieteA) { upd { s.quieteA = it } } }) { Text("Alle ${hm(s.quieteA)}") }
                        }
                        Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("Il livello rosso suona comunque")
                                Muted("Allerta rossa e sensori oltre la soglia rossa suonano anche nelle ore di quiete.")
                            }
                            Switch(s.rossoSempre, { upd { s.rossoSempre = it } })
                        }
                    }
                }
            }

            item {
                v
                Panel {
                    val last = s.lastCheck.takeIf { it > 0 }?.let { LocalDateTime.ofInstant(Instant.ofEpochMilli(it), ZoneId.systemDefault()) }
                    Muted(
                        (if (s.controlloVeloce && s.caldo) "Ora calda: controllo ogni minuto. " else "L'app controlla il servizio ogni 15 minuti circa e quando la apri. ") +
                            "Ultimo controllo: ${ago(last)}" +
                            (s.lastCheckError?.let { " (errore: $it)" } ?: "."),
                    )
                    Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Controllo ogni minuto nell'ora calda")
                            Muted("Con un'allerta almeno gialla, un sensore oltre la soglia o pioggia in corso il controllo si ripete ogni minuto. Altrimenti ogni 15 minuti.")
                        }
                        Switch(s.controlloVeloce, { upd { s.controlloVeloce = it; if (it) Notifier.checkNow(ctx) } })
                    }
                    TextButton(onClick = { appSettings() }, Modifier.padding(top = 4.dp)) { Text("Suonerie e vibrazioni di sistema") }
                }
            }
        }
    }

    editing?.let { voce ->
        ModalBottomSheet(onDismissRequest = { editing = null }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
            VoceEditor(s, voce, onChange = { v++ }, onClose = { editing = null })
        }
    }
}

/** Spiegazione dei quattro modi, una volta sola in cima alla pagina. */
@Composable
private fun Legenda() {
    Panel {
        Text("Per ogni voce e ogni livello scegli come vuoi essere avvisato.", fontWeight = FontWeight.SemiBold)
        Column(Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Modo.entries.forEach { m ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ModoPill(m)
                    Hspace(8.dp)
                    Text(m.label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

/** Riga di una voce: titolo e, per ogni livello, il modo scelto. Toccandola si apre l'editor. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun VoceRow(s: NotifySettings, voce: Voce, sottotitolo: String? = null, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(voce.titolo, style = MaterialTheme.typography.bodyLarge)
            sottotitolo?.let { Muted(it) }
            FlowRow(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                voce.livelli.forEach { l ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (l != Level.NESSUNO) { LevelDot(l, 10.dp); Hspace(3.dp) }
                        ModoPill(s.modo(voce, l))
                    }
                }
            }
        }
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ModoPill(m: Modo) {
    val (bg, fg) = when (m) {
        Modo.NO -> MaterialTheme.colorScheme.surfaceContainerHighest to MaterialTheme.colorScheme.onSurfaceVariant
        Modo.SILENZIOSA -> MaterialTheme.colorScheme.secondaryContainer to MaterialTheme.colorScheme.onSecondaryContainer
        Modo.SUONO -> MaterialTheme.colorScheme.primaryContainer to MaterialTheme.colorScheme.onPrimaryContainer
        Modo.ALLARME -> Color(0xFFDC2626) to Color.White
    }
    Text(
        m.breve, Modifier.background(bg, RoundedCornerShape(50)).padding(horizontal = 8.dp, vertical = 2.dp),
        color = fg, fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
    )
}

/** Foglio di modifica: un selettore per ogni livello della voce. */
@Composable
private fun VoceEditor(s: NotifySettings, voce: Voce, onChange: () -> Unit, onClose: () -> Unit) {
    val ctx = LocalContext.current
    var v by remember { mutableIntStateOf(0) }
    fun set(l: Level, m: Modo) { s.setModo(voce, l, m); v++; onChange() }
    val stazione = voce.stazione?.let { Stations.byId(it) }

    Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 16.dp).navigationBarsPadding()) {
        v
        Text(voce.titolo, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        stazione?.let { Muted("${it.name} · ${it.subtitle}") }
        voce.livelli.forEach { l ->
            Row(Modifier.padding(top = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                if (l != Level.NESSUNO) { LevelDot(l, 12.dp); Hspace(8.dp) }
                Text(voce.etichetta(l), fontWeight = FontWeight.Medium)
            }
            // l'allarme ha senso per i peggioramenti, non per i rientri
            val modi = if (l == Level.VERDE) Modo.entries.filter { it != Modo.ALLARME } else Modo.entries
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(top = 6.dp)) {
                modi.forEachIndexed { i, m ->
                    SegmentedButton(s.modo(voce, l) == m, { set(l, m) }, SegmentedButtonDefaults.itemShape(i, modi.size), icon = {}) {
                        Text(m.breve, maxLines = 1, fontSize = 13.sp)
                    }
                }
            }
        }

        if (voce.livelli.size > 1) {
            Text("Scelte rapide", Modifier.padding(top = 18.dp), fontWeight = FontWeight.Medium)
            FlowRowButtons(
                listOf(
                    "Tutto con suono" to { voce.livelli.forEach { l -> set(l, if (l == Level.VERDE) Modo.SILENZIOSA else Modo.SUONO) } },
                    "Rosso con allarme" to {
                        voce.livelli.forEach { l -> set(l, when (l) { Level.ROSSO -> Modo.ALLARME; Level.VERDE -> Modo.SILENZIOSA; else -> Modo.SUONO }) }
                    },
                    "Disattiva tutto" to { voce.livelli.forEach { l -> set(l, Modo.NO) } },
                ),
            )
        }
        if (voce.stazione != null) {
            val altri = Voce.sensori().filter { it.stazione == voce.stazione && it.key != voce.key }
            val tutti = Voce.sensori().filter { it.key != voce.key }
            Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (altri.isNotEmpty()) OutlinedButton(onClick = { copia(s, voce, altri); onChange() }) { Text("Uguale per la stazione", fontSize = 13.sp) }
                OutlinedButton(onClick = { copia(s, voce, tutti); onChange() }) { Text("Uguale per tutti", fontSize = 13.sp) }
            }
        }

        Row(Modifier.fillMaxWidth().padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
            val prova = voce.livelli.firstOrNull { s.modo(voce, it) != Modo.NO }
            TextButton(onClick = { prova?.let { Notifier.test(ctx, voce, it) } }, enabled = prova != null) { Text("Prova") }
            Button(onClick = onClose) { Text("Fatto") }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FlowRowButtons(items: List<Pair<String, () -> Unit>>) {
    FlowRow(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items.forEach { (label, action) -> OutlinedButton(onClick = action) { Text(label, fontSize = 13.sp) } }
    }
}

private fun copia(s: NotifySettings, da: Voce, a: List<Voce>) {
    a.forEach { dest -> da.livelli.forEach { l -> s.setModo(dest, l, s.modo(da, l)) } }
}

@Composable
private fun StatusLine(ok: Boolean, text: String) {
    Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        LevelDot(if (ok) Level.VERDE else Level.ARANCIONE, 10.dp)
        Hspace(8.dp)
        Text(text, style = MaterialTheme.typography.bodyMedium)
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
