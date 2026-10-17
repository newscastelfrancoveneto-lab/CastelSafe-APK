package io.github.andreadp271.castelsafe.data

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import io.github.andreadp271.castelsafe.MainActivity
import io.github.andreadp271.castelsafe.R
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import java.time.LocalTime
import java.util.concurrent.TimeUnit

/** Tipi di notifica che l'utente può scegliere. */
enum class Categoria(val key: String, val titolo: String, val descrizione: String) {
    ALLERTA("allerta", "Allerta meteo", "Bollettini di allerta della Protezione Civile del Veneto"),
    SENSORI("sensori", "Sensori ARPAV", "Livelli dei fiumi, pioggia, vento e temperatura oltre le soglie"),
    AVVISI("avvisi", "Avvisi e chiusure", "Nuove chiusure di strade e aree, divieti e pericoli"),
    COMUNICAZIONI("comunicazioni", "Comunicazioni", "Messaggi diretti della Protezione Civile e altri avvisi"),
}

/** Come avvisare: con suono/vibrazione o in silenzio (solo nella tendina). */
enum class Modo(val label: String) { SUONO("Con suono"), SILENZIOSA("Silenziosa") }

/** Notifica del server interpretata: categoria, gravità e (per i sensori) stazione e sensore. */
data class Classificata(
    val categoria: Categoria,
    val livello: Level,
    /** Abbassamento o rientro di un livello (sensori) o fine allerta. */
    val rientro: Boolean,
    val stazione: String? = null,
    val sensore: String? = null,
)

object Classifier {
    private fun livello(s: String): Level = when {
        "🔴" in s -> Level.ROSSO        // 🔴
        "🟠" in s -> Level.ARANCIONE    // 🟠
        "🟡" in s -> Level.GIALLO       // 🟡
        else -> Level.VERDE
    }

    fun classify(item: FeedItem): Classificata {
        val tag = item.tag
        return when {
            tag.startsWith("allerta") -> {
                // nel corpo ci sono i bollini di ogni rischio: conta il più grave
                val l = livello(item.title + " " + item.body)
                Classificata(Categoria.ALLERTA, l, rientro = l == Level.VERDE)
            }
            tag.startsWith("sensore-") || tag.startsWith("sensor-") -> {
                val parts = tag.split("-", limit = 3)
                val rientro = item.title.startsWith("✅") || "rientro" in item.title.lowercase()
                Classificata(Categoria.SENSORI, livello(item.title), rientro, parts.getOrNull(1), parts.getOrNull(2))
            }
            tag.startsWith("closure") -> Classificata(Categoria.AVVISI, Level.NESSUNO, false)
            else -> Classificata(Categoria.COMUNICAZIONI, livello(item.title), false)
        }
    }
}

/** Preferenze delle notifiche: cosa ricevere e come. */
class NotifySettings(context: Context) {
    private val p = context.getSharedPreferences("notify", Context.MODE_PRIVATE)

    fun enabled(c: Categoria) = p.getBoolean("on_${c.key}", true)
    fun setEnabled(c: Categoria, v: Boolean) = p.edit().putBoolean("on_${c.key}", v).apply()

    fun modo(c: Categoria) = runCatching { Modo.valueOf(p.getString("modo_${c.key}", null)!!) }
        .getOrDefault(if (c == Categoria.AVVISI) Modo.SILENZIOSA else Modo.SUONO)
    fun setModo(c: Categoria, v: Modo) = p.edit().putString("modo_${c.key}", v.name).apply()

    /** Livello minimo del bollettino da notificare (VERDE = tutti, anche il rientro). */
    var allertaMin: Level
        get() = runCatching { Level.valueOf(p.getString("allertaMin", null)!!) }.getOrDefault(Level.VERDE)
        set(v) = p.edit().putString("allertaMin", v.name).apply()

    /** Livello minimo dei superamenti di soglia dei sensori. */
    var sensoriMin: Level
        get() = runCatching { Level.valueOf(p.getString("sensoriMin", null)!!) }.getOrDefault(Level.GIALLO)
        set(v) = p.edit().putString("sensoriMin", v.name).apply()

    /** Avvisare anche quando un sensore torna sotto soglia. */
    var sensoriRientri: Boolean
        get() = p.getBoolean("sensoriRientri", true)
        set(v) = p.edit().putBoolean("sensoriRientri", v).apply()

    fun stazione(id: String) = p.getBoolean("staz_$id", true)
    fun setStazione(id: String, v: Boolean) = p.edit().putBoolean("staz_$id", v).apply()

    /** Il livello rosso suona sempre (anche se la categoria è silenziosa o durante la notte). */
    var rossoSempre: Boolean
        get() = p.getBoolean("rossoSempre", true)
        set(v) = p.edit().putBoolean("rossoSempre", v).apply()

    /** Ore di silenzio: le notifiche arrivano lo stesso ma senza suono. */
    var quiete: Boolean
        get() = p.getBoolean("quiete", false)
        set(v) = p.edit().putBoolean("quiete", v).apply()
    var quieteDa: Int    // minuti dalla mezzanotte
        get() = p.getInt("quieteDa", 22 * 60)
        set(v) = p.edit().putInt("quieteDa", v).apply()
    var quieteA: Int
        get() = p.getInt("quieteA", 7 * 60)
        set(v) = p.edit().putInt("quieteA", v).apply()

    fun inQuiete(now: LocalTime = LocalTime.now()): Boolean {
        if (!quiete) return false
        val m = now.hour * 60 + now.minute
        return if (quieteDa <= quieteA) m in quieteDa until quieteA else m >= quieteDa || m < quieteA
    }

    /** Decide se una notifica va mostrata con queste preferenze. */
    fun accetta(c: Classificata): Boolean {
        if (!enabled(c.categoria)) return false
        return when (c.categoria) {
            Categoria.ALLERTA -> c.livello >= allertaMin || (c.rientro && allertaMin == Level.VERDE)
            Categoria.SENSORI -> {
                if (c.stazione != null && !stazione(c.stazione)) false
                else if (c.rientro) sensoriRientri
                else c.livello >= sensoriMin
            }
            else -> true
        }
    }

    // ── stato del registro del server ────────────────────────────────────────
    var lastId: Long
        get() = p.getLong("lastId", 0)
        set(v) = p.edit().putLong("lastId", v).apply()
    var initialized: Boolean
        get() = p.getBoolean("initialized", false)
        set(v) = p.edit().putBoolean("initialized", v).apply()
    var lastCheck: Long
        get() = p.getLong("lastCheck", 0)
        set(v) = p.edit().putLong("lastCheck", v).apply()
    var lastCheckError: String?
        get() = p.getString("lastCheckError", null)
        set(v) = p.edit().putString("lastCheckError", v).apply()
}

/** Notifica ricevuta, conservata nella cronologia dell'app. */
@Serializable
data class Ricevuta(val item: FeedItem, val mostrata: Boolean)

object History {
    private const val MAX = 150
    val items = MutableStateFlow<List<Ricevuta>>(emptyList())

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("history", Context.MODE_PRIVATE)
    private val ser = ListSerializer(Ricevuta.serializer())

    fun load(ctx: Context) {
        items.value = runCatching { Api.json.decodeFromString(ser, prefs(ctx).getString("items", "[]")!!) }.getOrDefault(emptyList())
    }

    @Synchronized
    fun add(ctx: Context, new: List<Ricevuta>) {
        if (new.isEmpty()) return
        load(ctx)
        val known = items.value.map { it.item.id }.toSet()
        val merged = (new.filter { it.item.id !in known } + items.value).sortedByDescending { it.item.id }.take(MAX)
        prefs(ctx).edit().putString("items", Api.json.encodeToString(ser, merged)).apply()
        items.value = merged
    }

    fun clear(ctx: Context) {
        prefs(ctx).edit().remove("items").apply()
        items.value = emptyList()
    }
}

object Notifier {
    const val EXTRA_ROUTE = "route"
    private const val CH_CRITICO = "critico"
    private const val CH_SERVIZIO = "servizio"
    private const val WORK = "feed"
    /** Una notifica più vecchia di così (telefono spento, senza rete) entra in cronologia ma non suona. */
    private const val MAX_AGE_S = 24 * 3600L

    private fun channelId(c: Categoria, m: Modo) = if (m == Modo.SUONO) c.key else "${c.key}_silenziosa"

    fun createChannels(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        val list = mutableListOf<NotificationChannel>()
        for (c in Categoria.entries) {
            val imp = if (c == Categoria.AVVISI) NotificationManager.IMPORTANCE_DEFAULT else NotificationManager.IMPORTANCE_HIGH
            list += NotificationChannel(channelId(c, Modo.SUONO), c.titolo, imp).apply { description = c.descrizione }
            list += NotificationChannel(channelId(c, Modo.SILENZIOSA), "${c.titolo} (silenziose)", NotificationManager.IMPORTANCE_LOW).apply {
                description = "${c.descrizione}, senza suono"
            }
        }
        list += NotificationChannel(CH_CRITICO, "Livello rosso", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "Allerta rossa e sensori oltre la soglia massima: suono e vibrazione prolungata"
            enableVibration(true)
            vibrationPattern = longArrayOf(0, 800, 300, 800, 300, 800)
        }
        list += NotificationChannel(CH_SERVIZIO, "Stato dell'app", NotificationManager.IMPORTANCE_LOW).apply {
            description = "Aggiornamenti dell'app e messaggi di servizio"
        }
        nm.createNotificationChannels(list)
    }

    fun canNotify(ctx: Context) = Build.VERSION.SDK_INT < 33 ||
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    fun batteryUnrestricted(ctx: Context) =
        ctx.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(ctx.packageName)

    /** Controllo periodico del registro notifiche (minimo consentito da Android: 15 minuti). */
    fun schedule(ctx: Context) {
        val req = PeriodicWorkRequestBuilder<FeedWorker>(15, TimeUnit.MINUTES)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(WORK, ExistingPeriodicWorkPolicy.UPDATE, req)
    }

    /** Controllo immediato (apertura dell'app, ritorno della rete). */
    fun checkNow(ctx: Context) {
        val req = OneTimeWorkRequestBuilder<FeedWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(ctx).enqueueUniqueWork("$WORK-now", ExistingWorkPolicy.REPLACE, req)
    }

    /** Legge le notifiche nuove dal server e mostra quelle che l'utente vuole ricevere. */
    suspend fun check(ctx: Context) {
        val s = NotifySettings(ctx)
        val res = Api.feed(if (s.initialized) s.lastId else 0)
        val now = System.currentTimeMillis() / 1000
        if (!s.initialized) {
            // prima volta: niente squilli per il passato, ma la cronologia mostra le ultime 24 ore
            History.add(ctx, res.notifiche.filter { now - it.ts < MAX_AGE_S }.map { Ricevuta(it, false) })
            s.lastId = res.ultimoId
            s.initialized = true
            return
        }
        if (res.ultimoId < s.lastId) s.lastId = res.ultimoId  // registro del server azzerato
        val ricevute = res.notifiche.sortedBy { it.id }.map { item ->
            val c = Classifier.classify(item)
            val show = s.accetta(c) && now - item.ts < MAX_AGE_S
            if (show) show(ctx, item, c, s)
            Ricevuta(item, show)
        }
        History.add(ctx, ricevute)
        if (res.notifiche.isNotEmpty()) s.lastId = maxOf(s.lastId, res.notifiche.maxOf { it.id })
    }

    fun routeFor(item: FeedItem): String {
        val c = Classifier.classify(item)
        return when (c.categoria) {
            Categoria.ALLERTA -> "allerta"
            Categoria.SENSORI -> c.stazione?.let { "stazione/$it" } ?: "sensori"
            Categoria.AVVISI -> "avvisi"
            Categoria.COMUNICAZIONI -> "avvisi"
        }
    }

    fun show(ctx: Context, item: FeedItem, c: Classificata, s: NotifySettings = NotifySettings(ctx)) {
        if (!canNotify(ctx)) return
        val rosso = c.livello == Level.ROSSO && !c.rientro
        val channel = when {
            rosso && s.rossoSempre -> CH_CRITICO
            s.inQuiete() -> channelId(c.categoria, Modo.SILENZIOSA)
            else -> channelId(c.categoria, s.modo(c.categoria))
        }
        val intent = Intent(ctx, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .putExtra(EXTRA_ROUTE, routeFor(item))
        val pi = PendingIntent.getActivity(ctx, item.tag.hashCode(), intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val color = when (c.livello) {
            Level.ROSSO -> 0xFFDC2626.toInt()
            Level.ARANCIONE -> 0xFFEA580C.toInt()
            Level.GIALLO -> 0xFFCA8A04.toInt()
            else -> 0xFF8D021F.toInt()
        }
        val n = NotificationCompat.Builder(ctx, channel)
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(color)
            .setContentTitle(item.title)
            .setContentText(item.body.lineSequence().firstOrNull().orEmpty())
            .setStyle(NotificationCompat.BigTextStyle().bigText(item.body))
            .setWhen(item.ts * 1000)
            .setShowWhen(true)
            .setAutoCancel(true)
            .setContentIntent(pi)
            .setCategory(if (c.categoria == Categoria.ALLERTA || rosso) NotificationCompat.CATEGORY_ALARM else NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(if (channel.endsWith("_silenziosa")) NotificationCompat.PRIORITY_LOW else NotificationCompat.PRIORITY_HIGH)
            .build()
        // stesso tag = stessa notifica: l'aggiornamento di un sensore sostituisce quella precedente
        runCatching { NotificationManagerCompat.from(ctx).notify(item.tag, 0, n) }
    }

    /** Notifica di prova per verificare suono e aspetto della categoria scelta. */
    fun test(ctx: Context, c: Categoria) {
        val item = FeedItem(
            id = 0, ts = System.currentTimeMillis() / 1000, tag = "prova-${c.key}",
            title = "Prova: ${c.titolo}",
            body = "Così arriveranno le notifiche di questo tipo (${NotifySettings(ctx).modo(c).label.lowercase()}).",
        )
        show(ctx, item, Classificata(c, Level.NESSUNO, false))
    }
}

class FeedWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val s = NotifySettings(applicationContext)
        return try {
            Notifier.check(applicationContext)
            s.lastCheck = System.currentTimeMillis()
            s.lastCheckError = null
            Result.success()
        } catch (e: Exception) {
            s.lastCheckError = Repo.errorText(e)
            if (runAttemptCount < 2) Result.retry() else Result.success()
        }
    }
}
