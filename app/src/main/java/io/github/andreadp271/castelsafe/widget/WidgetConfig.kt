package io.github.andreadp271.castelsafe.widget

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.andreadp271.castelsafe.ui.CastelSafeTheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import io.github.andreadp271.castelsafe.data.Repo
import io.github.andreadp271.castelsafe.data.SensorData
import io.github.andreadp271.castelsafe.data.Stations
import kotlinx.coroutines.launch

/** Una lettura che il widget Sensori può mostrare: un dato di una stazione. */
data class WRiga(val sid: String, val tipo: String, val nome: String) {
    val key get() = "$sid|$tipo"
}

/** Scelte del widget Sensori, una per ogni widget messo sulla Home. */
object WidgetPrefs {
    private val predefinite = listOf("0292|LIVIDRO", "0653|LIVIDRO", "0102|TEMP", "0102|PRECCUM24")

    /** Nome breve di una lettura nel widget: i fiumi col nome della stazione, il resto "Stazione · dato". */
    fun nome(sid: String, tipo: String): String {
        val st = Stations.byId(sid)?.name ?: sid
        val breve = st.substringBefore(" Veneto")
        return if (tipo == "LIVIDRO") st else "$breve · ${Stations.label(tipo)}"
    }

    private fun p(ctx: Context) = ctx.getSharedPreferences("widget_sensori", Context.MODE_PRIVATE)

    private fun daChiavi(keys: List<String>) = keys.mapNotNull { k ->
        k.split("|").takeIf { it.size == 2 }?.let { (sid, t) -> WRiga(sid, t, nome(sid, t)) }
    }

    fun righe(ctx: Context, appWidgetId: Int): List<WRiga> {
        val keys = p(ctx).getString("w$appWidgetId", null)?.split(",")?.filter { it.isNotBlank() } ?: predefinite
        return daChiavi(keys).ifEmpty { daChiavi(predefinite) }
    }

    fun salva(ctx: Context, appWidgetId: Int, keys: List<String>) =
        p(ctx).edit().putString("w$appWidgetId", keys.joinToString(",")).apply()

    fun elimina(ctx: Context, ids: IntArray) = p(ctx).edit().apply { ids.forEach { remove("w$it") } }.apply()

    /** Tutti i dati di una stazione: quelli in tempo reale e quelli dello storico. */
    fun tipiStazione(sid: String): List<String> {
        val live = Repo.monitor.value.data?.get(sid)?.sensori?.keys.orEmpty()
        val storico = Repo.stazione(sid).value.data?.let { SensorData.schede(sid, it).map { c -> if (c.isVento) "VVENTO" else c.type } }.orEmpty()
        return (live + storico).distinct()
    }
}

/** Schermata di scelta delle letture, aperta quando si aggiunge o si riconfigura il widget. */
class SensoriWidgetConfigActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val id = intent?.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
            ?: AppWidgetManager.INVALID_APPWIDGET_ID
        setResult(Activity.RESULT_CANCELED, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id))
        if (id == AppWidgetManager.INVALID_APPWIDGET_ID) { finish(); return }
        Repo.init(this)
        setContent {
            CastelSafeTheme {
                Surface(Modifier.statusBarsPadding(), color = MaterialTheme.colorScheme.background) {
                    val scope = rememberCoroutineScope()
                    val scelte = remember { mutableStateListOf<String>().also { l -> l.addAll(WidgetPrefs.righe(this, id).map { it.key }) } }
                    LaunchedEffect(Unit) {
                        runCatching { Repo.loadMonitor() }
                        Stations.all.forEach { st -> launch { runCatching { Repo.loadStazione(st.id) } } }
                    }
                    // rilegge l'elenco quando arrivano i dati
                    Repo.monitor.collectAsState().value
                    Stations.all.forEach { Repo.stazione(it.id).collectAsState().value }
                    Column(Modifier.padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text("Letture da mostrare", style = MaterialTheme.typography.titleLarge)
                        Text("Tutti i dati di ogni stazione. Il widget li elenca nell'ordine della Home.", style = MaterialTheme.typography.bodyMedium)
                        Stations.all.forEach { st ->
                            val tipi = WidgetPrefs.tipiStazione(st.id)
                            Text(st.name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 14.dp))
                            Text(st.subtitle, style = MaterialTheme.typography.bodySmall)
                            if (tipi.isEmpty()) Text("Carico i dati…", style = MaterialTheme.typography.bodySmall)
                            tipi.forEach { t ->
                                val key = "${st.id}|$t"
                                val on = key in scelte
                                Row(
                                    Modifier.fillMaxWidth().clickable { if (on) scelte.remove(key) else scelte.add(key) },
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Checkbox(on, onCheckedChange = { if (on) scelte.remove(key) else scelte.add(key) })
                                    Text(Stations.label(t))
                                }
                            }
                        }
                        Button(
                            enabled = scelte.isNotEmpty(),
                            onClick = {
                                WidgetPrefs.salva(this@SensoriWidgetConfigActivity, id, scelte.toList())
                                scope.launch {
                                    runCatching { Widgets.updateAll(applicationContext) }
                                    setResult(Activity.RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id))
                                    finish()
                                }
                            },
                            modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
                        ) { Text("Salva") }
                    }
                }
            }
        }
    }
}
