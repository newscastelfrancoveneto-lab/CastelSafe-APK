package io.github.andreadp271.castelsafe.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.andreadp271.castelsafe.BuildConfig
import io.github.andreadp271.castelsafe.data.Diagnostics
import io.github.andreadp271.castelsafe.data.Level
import io.github.andreadp271.castelsafe.data.Notifier
import io.github.andreadp271.castelsafe.data.NotifySettings
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

/** "Verifica connessione": prova ogni servizio usato dall'app e mostra dove si interrompe la catena. */
@Composable
fun DiagnosticsScreen(nav: Nav) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val results = remember { mutableStateMapOf<Diagnostics.Check, Diagnostics.Result>() }
    var running by remember { mutableStateOf(false) }
    var lastRun by remember { mutableStateOf<LocalDateTime?>(null) }

    fun runAll() = scope.launch {
        running = true
        results.clear()
        Diagnostics.checks.map { c -> async { results[c] = Diagnostics.run(c) } }.awaitAll()
        lastRun = LocalDateTime.now()
        running = false
    }
    LaunchedEffect(Unit) { runAll() }

    val ns = remember { NotifySettings(ctx) }
    val network = remember(lastRun) { networkType(ctx) }
    val okCount = results.values.count { it.ok }
    val failed = Diagnostics.checks.filter { results[it]?.ok == false }

    Column(Modifier.fillMaxSize()) {
        Bar("Verifica connessione", "Server e servizi usati dall'app", onBack = nav::back)
        LazyColumn(contentPadding = screenPadding, verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
                Panel {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                when {
                                    running -> "Verifica in corso…"
                                    failed.isEmpty() -> "Tutto raggiungibile"
                                    failed.any { it.group == "Server CastelSafe" } && failed.count { it.group == "Server CastelSafe" } == Diagnostics.checks.count { it.group == "Server CastelSafe" } ->
                                        "Il server CastelSafe non è raggiungibile"
                                    failed.any { it.group == "Rete" } -> "Il telefono non è connesso a Internet"
                                    else -> "${failed.size} servizi non raggiungibili"
                                },
                                style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold,
                            )
                            Muted("$okCount di ${Diagnostics.checks.size} servizi raggiungibili · rete: $network" + (lastRun?.let { " · ${fmtTime(it)}" } ?: ""))
                        }
                        if (running) CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                    }
                    Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { runAll() }, enabled = !running) { Text("Ripeti verifica") }
                        OutlinedButton(onClick = { copy(ctx, report(results, network, ns)) }, enabled = !running) { Text("Copia rapporto") }
                    }
                }
            }

            Diagnostics.checks.groupBy { it.group }.forEach { (group, list) ->
                item { SectionTitle(group) }
                item {
                    Panel(padding = 0.dp) {
                        list.forEachIndexed { i, c ->
                            if (i > 0) HorizontalDivider(Modifier.padding(start = 40.dp), color = MaterialTheme.colorScheme.outlineVariant)
                            val r = results[c]
                            Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
                                if (r == null) CircularProgressIndicator(Modifier.size(12.dp), strokeWidth = 1.5.dp)
                                else LevelDot(if (r.ok) Level.VERDE else Level.ROSSO, 12.dp)
                                Hspace(14.dp)
                                Column(Modifier.weight(1f)) {
                                    Text(c.name, fontWeight = FontWeight.SemiBold)
                                    Muted(c.what)
                                    r?.let { Text(it.detail, style = MaterialTheme.typography.bodySmall, color = if (it.ok) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error) }
                                }
                                r?.let { Text("${it.ms} ms", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                            }
                        }
                    }
                }
            }

            item { SectionTitle("Notifiche sul telefono") }
            item {
                Panel {
                    val last = ns.lastCheck.takeIf { it > 0 }?.let { LocalDateTime.ofInstant(Instant.ofEpochMilli(it), ZoneId.systemDefault()) }
                    StatusRow(Notifier.canNotify(ctx), "Permesso notifiche", if (Notifier.canNotify(ctx)) "Concesso" else "Negato")
                    StatusRow(Notifier.batteryUnrestricted(ctx), "Risparmio batteria", if (Notifier.batteryUnrestricted(ctx)) "Nessuna restrizione" else "Attivo: i controlli possono essere rimandati")
                    StatusRow(ns.lastCheckError == null && last != null, "Ultimo controllo in background", (last?.let { ago(it) } ?: "mai") + (ns.lastCheckError?.let { " · errore: $it" } ?: ""))
                }
            }
            item { Muted("CastelSafe ${BuildConfig.VERSION_NAME} · Android ${Build.VERSION.RELEASE} · ${Build.MANUFACTURER} ${Build.MODEL}") }
        }
    }
}

@Composable
private fun StatusRow(ok: Boolean, title: String, detail: String) {
    Row(Modifier.padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
        LevelDot(if (ok) Level.VERDE else Level.ARANCIONE, 12.dp)
        Hspace(14.dp)
        Column {
            Text(title, fontWeight = FontWeight.SemiBold)
            Muted(detail)
        }
    }
}

private fun networkType(ctx: Context): String {
    val cm = ctx.getSystemService(ConnectivityManager::class.java)
    val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return "nessuna"
    return when {
        caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
        caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "dati mobili"
        caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
        caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "VPN"
        else -> "altra"
    }
}

private fun report(results: Map<Diagnostics.Check, Diagnostics.Result>, network: String, ns: NotifySettings): String = buildString {
    appendLine("CastelSafe ${BuildConfig.VERSION_NAME} · Android ${Build.VERSION.RELEASE} · ${Build.MANUFACTURER} ${Build.MODEL} · rete $network")
    appendLine("Verifica del ${LocalDateTime.now().withNano(0)}")
    Diagnostics.checks.forEach { c ->
        val r = results[c]
        appendLine("${if (r?.ok == true) "OK " else "ERR"} ${c.name}: ${r?.detail ?: "—"} (${r?.ms ?: "?"} ms)")
    }
    appendLine("Ultimo controllo notifiche: ${ns.lastCheck} ${ns.lastCheckError ?: ""}")
}

private fun copy(ctx: Context, text: String) {
    ctx.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("CastelSafe diagnostica", text))
    if (Build.VERSION.SDK_INT < 33) Toast.makeText(ctx, "Rapporto copiato", Toast.LENGTH_SHORT).show()
}
