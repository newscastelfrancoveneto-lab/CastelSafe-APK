package io.github.andreadp271.castelsafe.ui

import android.os.Build
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.andreadp271.castelsafe.BuildConfig
import io.github.andreadp271.castelsafe.R
import io.github.andreadp271.castelsafe.data.AppSettings
import io.github.andreadp271.castelsafe.data.Categoria
import io.github.andreadp271.castelsafe.data.Config
import io.github.andreadp271.castelsafe.data.Notifier
import io.github.andreadp271.castelsafe.data.NotifySettings
import io.github.andreadp271.castelsafe.data.Updater
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

/** Colori delle icone per gruppo, come in Capotreno. */
private object SetColors {
    val look = Color(0xFF7C3AED)
    val general = Color(0xFF0E8A8A)
    val alerts = Color(0xFFDC2626)
    val data = Color(0xFF2563EB)
    val about = Color(0xFF8D021F)
}

private const val REPO = "https://github.com/${Config.GITHUB_REPO}"

@Composable
fun SettingsScreen(nav: Nav) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val theme by AppSettings.theme.collectAsState()
    val palette by AppSettings.palette.collectAsState()
    val portrait by AppSettings.portrait.collectAsState()
    val update by availableUpdate.collectAsState()
    var startTab by remember { mutableStateOf(AppSettings.startTab) }
    var screenOn by remember { mutableStateOf(AppSettings.keepScreenOn) }
    var radarStart by remember { mutableStateOf(AppSettings.radarAtStart) }
    var analytics by remember { mutableStateOf(AppSettings.analytics) }
    var autoUpdate by remember { mutableStateOf(AppSettings.autoUpdateCheck) }
    var startDialog by remember { mutableStateOf(false) }
    var cacheDialog by remember { mutableStateOf(false) }
    var cacheSize by remember { mutableStateOf<Long?>(null) }
    var checking by remember { mutableStateOf(false) }
    var checkedNoUpdate by remember { mutableStateOf(false) }
    val ns = remember { NotifySettings(ctx) }
    val activeCats = Categoria.entries.filter { ns.enabled(it) }

    LaunchedEffect(Unit) { cacheSize = withContext(Dispatchers.IO) { cacheBytes(ctx) } }

    Column(Modifier.fillMaxSize()) {
        Bar("Impostazioni")
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            AppCard()

            if (update != null) {
                Surface(onClick = { nav.go("aggiornamenti") }, shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.primaryContainer) {
                    Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Aggiornamento disponibile", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimaryContainer)
                            Text("Versione ${update!!.version} · tocca per installarla", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onPrimaryContainer)
                        }
                        Chevron()
                    }
                }
            }

            Group("Aspetto") {
                Item(painterResource(R.drawable.ic_set_moon), SetColors.look, "Tema", "Chiaro, scuro o come il sistema")
                Choice(AppSettings.Theme.entries, theme, { it.label }) { AppSettings.setTheme(it) }
                Divider()
                Item(
                    painterResource(R.drawable.ic_set_palette), SetColors.look, "Colori",
                    if (palette == AppSettings.Palette.DYNAMIC && Build.VERSION.SDK_INT >= 31) "Presi dallo sfondo del telefono (Material You)"
                    else if (palette == AppSettings.Palette.DYNAMIC) "Disponibile da Android 12: si usa il bordeaux CastelSafe"
                    else "Bordeaux CastelSafe, come la versione web",
                )
                Choice(AppSettings.Palette.entries, palette, { it.label }) { AppSettings.setPalette(it) }
            }

            Group("Generale") {
                Item(
                    painterResource(R.drawable.ic_set_start), SetColors.general, "Schermata iniziale",
                    AppSettings.startTabs.firstOrNull { it.first == startTab }?.second ?: "Home",
                    trailing = { Chevron() }, onClick = { startDialog = true },
                )
                Divider()
                Item(
                    painterResource(R.drawable.ic_set_rotate), SetColors.general, "Blocca in verticale",
                    "I grafici a schermo intero ruotano comunque in orizzontale",
                    trailing = { Switch(portrait, { AppSettings.setPortrait(it) }) },
                    onClick = { AppSettings.setPortrait(!portrait) },
                )
                Divider()
                Item(
                    painterResource(R.drawable.ic_set_phone), SetColors.general, "Schermo sempre acceso",
                    "Mentre guardi il grafico di un sensore, ad esempio durante una piena",
                    trailing = { Switch(screenOn, { AppSettings.keepScreenOn = it; screenOn = it }) },
                    onClick = { AppSettings.keepScreenOn = !screenOn; screenOn = !screenOn },
                )
                Divider()
                Item(
                    rememberVectorPainter(Icons.Filled.Place), SetColors.general, "Radar all'apertura della mappa",
                    "Mostra subito dove sta piovendo (Radar-DPC)",
                    trailing = { Switch(radarStart, { AppSettings.radarAtStart = it; radarStart = it }) },
                    onClick = { AppSettings.radarAtStart = !radarStart; radarStart = !radarStart },
                )
            }

            Group("Notifiche") {
                Item(
                    rememberVectorPainter(Icons.Filled.Notifications), SetColors.alerts, "Notifiche",
                    when {
                        !Notifier.canNotify(ctx) -> "Bloccate: tocca per consentirle"
                        activeCats.isEmpty() -> "Tutte disattivate"
                        else -> activeCats.joinToString(", ") { it.titolo.lowercase() }.replaceFirstChar { it.uppercase() }
                    },
                    trailing = { Chevron() }, onClick = { nav.go("notifiche") },
                )
                Divider()
                Item(
                    painterResource(R.drawable.ic_set_update), SetColors.alerts, "Controllo in background",
                    if (Notifier.batteryUnrestricted(ctx)) "Ogni 15 minuti circa, senza restrizioni di batteria"
                    else "Ogni 15 minuti circa · il risparmio batteria può ritardarlo",
                    trailing = { Chevron() }, onClick = { nav.go("notifiche") },
                )
            }

            Group("Assistenza") {
                Item(
                    painterResource(R.drawable.ic_set_bug), SetColors.general, "Verifica connessione",
                    "Controlla server CastelSafe, ARPAV, mappa, radar e notifiche",
                    trailing = { Chevron() }, onClick = { nav.go("diagnostica") },
                )
            }

            Group("Dati e privacy") {
                Item(
                    painterResource(R.drawable.ic_set_cleanup), SetColors.data, "Svuota cache",
                    cacheSize?.let { "${formatSize(it)} di mappe e dati scaricati" } ?: "Calcolo…",
                    onClick = { cacheDialog = true },
                )
                Divider()
                Item(
                    painterResource(R.drawable.ic_tab_sensori), SetColors.data, "Statistiche anonime",
                    "Contano quante persone usano l'app: nessun account, nessun IP salvato",
                    trailing = { Switch(analytics, { AppSettings.analytics = it; analytics = it }) },
                    onClick = { AppSettings.analytics = !analytics; analytics = !analytics },
                )
                Divider()
                Item(
                    rememberVectorPainter(Icons.Filled.Info), SetColors.data, "Informazioni e privacy",
                    "Fonti dei dati, aree di protezione civile, note", trailing = { Chevron() }, onClick = { nav.go("info") },
                )
            }

            Group("Informazioni") {
                Item(
                    painterResource(R.drawable.ic_set_update), SetColors.about, "Aggiornamenti",
                    when {
                        update != null -> "Disponibile la versione ${update!!.version}"
                        checkedNoUpdate -> "Hai l'ultima versione (${BuildConfig.VERSION_NAME})"
                        else -> "Versione ${BuildConfig.VERSION_NAME} · tocca per controllare"
                    },
                    trailing = if (checking) { { CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp) } } else null,
                    onClick = {
                        if (update != null) nav.go("aggiornamenti")
                        else scope.launch {
                            checking = true
                            runCatching { Updater.latest() }.getOrNull()?.let { r ->
                                if (Updater.isNewer(r.version)) availableUpdate.value = r else checkedNoUpdate = true
                            }
                            AppSettings.lastUpdateCheck = System.currentTimeMillis()
                            checking = false
                        }
                    },
                )
                Divider()
                Item(
                    painterResource(R.drawable.ic_set_update), SetColors.about, "Controlla all'avvio",
                    "Una volta al giorno cerca nuove versioni su GitHub",
                    trailing = { Switch(autoUpdate, { AppSettings.autoUpdateCheck = it; autoUpdate = it }) },
                    onClick = { AppSettings.autoUpdateCheck = !autoUpdate; autoUpdate = !autoUpdate },
                )
                Divider()
                Item(
                    rememberVectorPainter(Icons.Filled.Share), SetColors.about, "Condividi CastelSafe", "Invia l'app ad amici e familiari",
                    onClick = {
                        share(ctx, "CastelSafe: allerte meteo, livelli dei fiumi, avvisi e chiusure a Castelfranco Veneto.\n" +
                            "Android: $REPO/releases/latest\nWeb e iPhone: ${Config.SITE_URL}")
                    },
                )
                Divider()
                Item(painterResource(R.drawable.ic_tab_mappa), SetColors.about, "Versione web", "Per iPhone e computer", onClick = { openUrl(ctx, Config.SITE_URL) })
                Divider()
                Item(painterResource(R.drawable.ic_set_code), SetColors.about, "Codice sorgente", REPO.removePrefix("https://"), onClick = { openUrl(ctx, REPO) })
                Divider()
                Item(painterResource(R.drawable.ic_set_bug), SetColors.about, "Segnala un problema", "Apri una segnalazione su GitHub", onClick = { openUrl(ctx, "$REPO/issues") })
            }

            Text(
                "Allerte: Protezione Civile Regione Veneto. Sensori e previsioni: ARPAV Veneto. Radar: Dipartimento della Protezione Civile. " +
                    "Mappe © contributori di OpenStreetMap.\nCastelSafe è uno strumento informativo: in emergenza segui sempre le indicazioni ufficiali.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 12.dp),
            )
            Spacer(Modifier.height(16.dp))
        }
    }

    if (startDialog) AlertDialog(
        onDismissRequest = { startDialog = false },
        title = { Text("Schermata iniziale") },
        text = {
            Column {
                AppSettings.startTabs.forEach { (route, label) ->
                    Row(
                        Modifier.fillMaxWidth().clickable { AppSettings.startTab = route; startTab = route; startDialog = false }.padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(startTab == route, { AppSettings.startTab = route; startTab = route; startDialog = false })
                        Text(label)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { startDialog = false }) { Text("Chiudi") } },
    )

    if (cacheDialog) AlertDialog(
        onDismissRequest = { cacheDialog = false },
        title = { Text("Svuotare la cache?") },
        text = { Text("Tessere della mappa, aree e corsi d'acqua verranno scaricati di nuovo quando servono. Impostazioni e cronologia delle notifiche restano.") },
        confirmButton = {
            TextButton(onClick = {
                cacheDialog = false
                scope.launch {
                    withContext(Dispatchers.IO) {
                        ctx.cacheDir.listFiles()?.forEach { it.deleteRecursively() }
                        listOf("aree", "corsi_acqua_v2", "confine").forEach { File(ctx.filesDir, "cache/$it.txt").delete() }
                    }
                    cacheSize = withContext(Dispatchers.IO) { cacheBytes(ctx) }
                }
            }) { Text("Svuota") }
        },
        dismissButton = { TextButton(onClick = { cacheDialog = false }) { Text("Annulla") } },
    )
}

/** Intestazione con icona, nome e versione dell'app. */
@Composable
private fun AppCard() {
    Box(Modifier.fillMaxWidth().padding(vertical = 8.dp).clip(RoundedCornerShape(24.dp)).background(Brush.linearGradient(listOf(Color(0xFF8D021F), Color(0xFF0E8A8A))))) {
        Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
            Image(painterResource(R.mipmap.ic_launcher), null, Modifier.size(64.dp).clip(RoundedCornerShape(18.dp)))
            Column(Modifier.padding(start = 16.dp)) {
                Text("CastelSafe", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = Color.White)
                Text("Versione ${BuildConfig.VERSION_NAME}", color = Color.White.copy(alpha = 0.9f))
                Text("Protezione civile di Castelfranco Veneto · senza pubblicità", style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.75f))
            }
        }
    }
}

@Composable
private fun Group(title: String, content: @Composable () -> Unit) {
    Text(
        title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(start = 8.dp, top = 12.dp, bottom = 4.dp),
    )
    Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainerLow, tonalElevation = 1.dp) {
        Column(Modifier.padding(vertical = 4.dp)) { content() }
    }
}

@Composable
private fun Item(icon: Painter, color: Color, title: String, subtitle: String?, trailing: (@Composable () -> Unit)? = null, onClick: (() -> Unit)? = null) {
    val tint = if (isDark()) lerp(color, Color.White, 0.35f) else color
    Row(
        Modifier.fillMaxWidth().then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(tint.copy(alpha = 0.16f)), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = tint, modifier = Modifier.size(22.dp))
        }
        Column(Modifier.weight(1f).padding(start = 16.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        trailing?.let { Spacer(Modifier.width(8.dp)); it() }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun <T> Choice(options: List<T>, selected: T, label: (T) -> String, onSelect: (T) -> Unit) {
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 12.dp)) {
        options.forEachIndexed { i, o ->
            SegmentedButton(selected = o == selected, onClick = { onSelect(o) }, shape = SegmentedButtonDefaults.itemShape(i, options.size), icon = {}) {
                Text(label(o), maxLines = 1, style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

@Composable
private fun Divider() = HorizontalDivider(Modifier.padding(start = 72.dp, end = 16.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

@Composable
private fun Chevron() = Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)

private fun cacheBytes(ctx: android.content.Context): Long =
    (ctx.cacheDir.walkTopDown() + File(ctx.filesDir, "cache").walkTopDown() + File(ctx.filesDir, "osmdroid").walkTopDown())
        .filter { it.isFile }.sumOf { it.length() }

private fun formatSize(bytes: Long): String = when {
    bytes >= 1_000_000 -> String.format(Locale.ITALY, "%.1f MB", bytes / 1_000_000.0)
    bytes >= 1_000 -> "${bytes / 1_000} kB"
    else -> "$bytes B"
}
