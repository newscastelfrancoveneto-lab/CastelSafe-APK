package io.github.andreadp271.castelsafe

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import io.github.andreadp271.castelsafe.data.Api
import io.github.andreadp271.castelsafe.data.History
import io.github.andreadp271.castelsafe.data.Notifier
import io.github.andreadp271.castelsafe.data.Repo
import io.github.andreadp271.castelsafe.ui.App
import io.github.andreadp271.castelsafe.ui.CastelSafeTheme
import kotlinx.coroutines.launch
import org.osmdroid.config.Configuration
import java.io.File
import java.util.UUID

class MainActivity : ComponentActivity() {
    /** Schermata da aprire toccando una notifica. */
    private var pendingRoute by mutableStateOf<String?>(null)

    private fun handle(intent: Intent?) {
        intent?.getStringExtra(Notifier.EXTRA_ROUTE)?.let { pendingRoute = it }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Repo.init(this)
        History.load(this)
        // osmdroid: User-Agent identificativo (richiesto dalla tile policy OSM) e cache nello spazio privato dell'app
        Configuration.getInstance().apply {
            userAgentValue = "${BuildConfig.APPLICATION_ID}/${BuildConfig.VERSION_NAME}"
            osmdroidBasePath = File(filesDir, "osmdroid")
            osmdroidTileCache = File(cacheDir, "osmdroid-tiles")
        }
        enableEdgeToEdge()
        if (savedInstanceState == null) handle(intent)
        Notifier.createChannels(this)
        Notifier.schedule(this)
        setContent {
            CastelSafeTheme {
                App(pendingRoute) { pendingRoute = null }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        // all'apertura: notifiche arrivate nel frattempo e statistiche anonime (max una ogni 30 minuti, come la PWA)
        Notifier.checkNow(this)
        val p = getSharedPreferences("app", Context.MODE_PRIVATE)
        val cid = p.getString("cid", null) ?: UUID.randomUUID().toString().replace("-", "").also { p.edit().putString("cid", it).apply() }
        val now = System.currentTimeMillis()
        if (now - p.getLong("lastHit", 0) > 30 * 60_000L) {
            p.edit().putLong("lastHit", now).apply()
            lifecycleScope.launch { Api.analyticsHit(cid) }
        }
    }
}
