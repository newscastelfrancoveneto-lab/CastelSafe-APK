package io.github.andreadp271.castelsafe

import android.app.Application
import android.appwidget.AppWidgetManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import io.github.andreadp271.castelsafe.widget.RadarWidgetReceiver
import io.github.andreadp271.castelsafe.widget.Widgets
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class CastelSafeApp : Application() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        // quando si accende o si sblocca lo schermo, il radar dei widget si rinnova se ha più di 5 minuti
        val r = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val ids = AppWidgetManager.getInstance(context).getAppWidgetIds(ComponentName(context, RadarWidgetReceiver::class.java))
                if (ids.isEmpty()) return
                scope.launch { runCatching { Widgets.updateRadar(context.applicationContext, 5 * 60_000L) } }
            }
        }
        val f = IntentFilter().apply { addAction(Intent.ACTION_USER_PRESENT); addAction(Intent.ACTION_SCREEN_ON) }
        androidx.core.content.ContextCompat.registerReceiver(this, r, f, androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED)
    }
}
