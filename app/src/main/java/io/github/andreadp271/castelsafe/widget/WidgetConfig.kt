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
import kotlinx.coroutines.launch

/** Una lettura che il widget Sensori può mostrare. */
data class WRiga(val sid: String, val tipo: String, val nome: String) {
    val key get() = "$sid|$tipo"
}

/** Scelte del widget Sensori, una per ogni widget messo sulla Home. */
object WidgetPrefs {
    val catalogo = listOf(
        WRiga("0292", "LIVIDRO", "Muson dei Sassi"),
        WRiga("0653", "LIVIDRO", "Avenale"),
        WRiga("0554", "LIVIDRO", "Muson ad Asolo"),
        WRiga("0102", "TEMP", "Temperatura"),
        WRiga("0102", "PRECCUM24", "Pioggia 24 ore"),
        WRiga("0102", "PREC", "Pioggia"),
        WRiga("0102", "VVENTO", "Vento"),
        WRiga("0102", "UMID", "Umidità"),
    )
    private val predefinite = listOf("0292|LIVIDRO", "0653|LIVIDRO", "0102|TEMP", "0102|PRECCUM24")

    private fun p(ctx: Context) = ctx.getSharedPreferences("widget_sensori", Context.MODE_PRIVATE)

    private fun daChiavi(keys: List<String>) = keys.mapNotNull { k -> catalogo.firstOrNull { it.key == k } }

    fun righe(ctx: Context, appWidgetId: Int): List<WRiga> {
        val keys = p(ctx).getString("w$appWidgetId", null)?.split(",")?.filter { it.isNotBlank() } ?: predefinite
        return daChiavi(keys).ifEmpty { daChiavi(predefinite) }
    }

    fun salva(ctx: Context, appWidgetId: Int, keys: List<String>) =
        p(ctx).edit().putString("w$appWidgetId", keys.joinToString(",")).apply()

    fun elimina(ctx: Context, ids: IntArray) = p(ctx).edit().apply { ids.forEach { remove("w$it") } }.apply()
}

/** Schermata di scelta delle letture, aperta quando si aggiunge o si riconfigura il widget. */
class SensoriWidgetConfigActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val id = intent?.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
            ?: AppWidgetManager.INVALID_APPWIDGET_ID
        setResult(Activity.RESULT_CANCELED, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id))
        if (id == AppWidgetManager.INVALID_APPWIDGET_ID) { finish(); return }
        setContent {
            CastelSafeTheme {
                Surface(Modifier.statusBarsPadding(), color = MaterialTheme.colorScheme.background) {
                    val scope = rememberCoroutineScope()
                    val scelte = remember { mutableStateListOf<String>().also { l -> l.addAll(WidgetPrefs.righe(this, id).map { it.key }) } }
                    Column(Modifier.padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Letture da mostrare", style = MaterialTheme.typography.titleLarge)
                        Text("Il widget le elenca nell'ordine qui sotto.", style = MaterialTheme.typography.bodyMedium)
                        WidgetPrefs.catalogo.forEach { r ->
                            val on = r.key in scelte
                            Row(
                                Modifier.fillMaxWidth().clickable { if (on) scelte.remove(r.key) else scelte.add(r.key) },
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Checkbox(on, onCheckedChange = { if (on) scelte.remove(r.key) else scelte.add(r.key) })
                                Text(r.nome)
                            }
                        }
                        Button(
                            enabled = scelte.isNotEmpty(),
                            onClick = {
                                WidgetPrefs.salva(this@SensoriWidgetConfigActivity, id, WidgetPrefs.catalogo.map { it.key }.filter { it in scelte })
                                scope.launch {
                                    runCatching { Widgets.updateAll(applicationContext) }
                                    setResult(Activity.RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id))
                                    finish()
                                }
                            },
                            modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                        ) { Text("Salva") }
                    }
                }
            }
        }
    }
}
