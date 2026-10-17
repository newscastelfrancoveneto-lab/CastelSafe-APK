package io.github.andreadp271.castelsafe.data

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Allarme sonoro per le situazioni gravi: suona sul canale "sveglia" di Android, che non è
 * silenziato dalla modalità silenziosa o vibrazione, finché l'utente non lo ferma (al massimo [MAX_S]).
 */
object Alarm {
    const val MAX_S = 60
    val active = MutableStateFlow(false)

    private var player: MediaPlayer? = null
    private var vibrator: Vibrator? = null
    private var restoreVolume: Int? = null
    private val handler = Handler(Looper.getMainLooper())
    private val timeout = Runnable { synchronized(this) { if (active.value) stopInternal() } }

    private val attrs = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ALARM)
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
        .build()

    @Synchronized
    fun start(ctx: Context, seconds: Int = MAX_S) {
        stopInternal()
        val app = ctx.applicationContext
        lastCtx = app
        val am = app.getSystemService(AudioManager::class.java)
        if (NotifySettings(app).allarmeVolumeMax) runCatching {
            restoreVolume = am.getStreamVolume(AudioManager.STREAM_ALARM)
            am.setStreamVolume(AudioManager.STREAM_ALARM, am.getStreamMaxVolume(AudioManager.STREAM_ALARM), 0)
        }
        val uri = listOf(RingtoneManager.TYPE_ALARM, RingtoneManager.TYPE_RINGTONE, RingtoneManager.TYPE_NOTIFICATION)
            .firstNotNullOfOrNull { RingtoneManager.getDefaultUri(it) }
        player = uri?.let {
            runCatching {
                MediaPlayer().apply {
                    setAudioAttributes(attrs)
                    setDataSource(app, it)
                    isLooping = true
                    prepare()
                    start()
                }
            }.getOrNull()
        }
        vibrator = (if (Build.VERSION.SDK_INT >= 31) app.getSystemService(VibratorManager::class.java).defaultVibrator
        else @Suppress("DEPRECATION") app.getSystemService(Vibrator::class.java))?.also { v ->
            val effect = VibrationEffect.createWaveform(longArrayOf(0, 900, 400, 900, 400), 0)
            if (Build.VERSION.SDK_INT >= 33) v.vibrate(effect, VibrationAttributes.createForUsage(VibrationAttributes.USAGE_ALARM))
            else @Suppress("DEPRECATION") v.vibrate(effect, attrs)
        }
        active.value = true
        handler.postDelayed(timeout, seconds * 1000L)
    }

    @Synchronized
    fun stop(ctx: Context) {
        if (!active.value) return
        stopInternal(ctx.applicationContext)
    }

    private var lastCtx: Context? = null

    private fun stopInternal(ctx: Context? = lastCtx) {
        handler.removeCallbacks(timeout)
        player?.runCatching { stop(); release() }
        player = null
        vibrator?.cancel()
        vibrator = null
        val r = restoreVolume
        if (r != null && ctx != null) runCatching { ctx.getSystemService(AudioManager::class.java).setStreamVolume(AudioManager.STREAM_ALARM, r, 0) }
        restoreVolume = null
        active.value = false
    }

    /** Attende che l'allarme finisca: tiene vivo il controllo in background mentre suona. */
    suspend fun awaitEnd() {
        withTimeoutOrNull((MAX_S + 5) * 1000L) { active.first { !it } }
    }
}

/** Pulsante "Ferma allarme" della notifica (e notifica scartata). */
class AlarmStopReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) = Alarm.stop(context)
}
