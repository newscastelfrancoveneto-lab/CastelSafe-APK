package io.github.andreadp271.castelsafe.data

import kotlinx.coroutines.launch
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

/** Come avvisare per un certo livello. */
enum class Modo(val label: String, val breve: String) {
    NO("Nessuna notifica", "No"),
    SILENZIOSA("Silenziosa: solo nella tendina", "Muta"),
    SUONO("Con suono e vibrazione", "Suono"),
    ALLARME("Allarme sonoro: sirena anche in silenzioso", "Allarme"),
}

/**
 * Una cosa che si può notificare: l'allerta, ogni dato di ogni stazione, gli avvisi, le comunicazioni.
 * Per ogni livello di [livelli] l'utente sceglie un [Modo]; [Level.VERDE] è il rientro / la fine allerta,
 * [Level.NESSUNO] indica una voce senza livelli.
 */
data class Voce(val key: String, val categoria: Categoria, val titolo: String, val livelli: List<Level>, val stazione: String? = null, val sensore: String? = null) {
    fun etichetta(l: Level): String = when (categoria) {
        Categoria.ALLERTA -> if (l == Level.VERDE) "Nessuna allerta o fine allerta" else "Allerta ${l.label.lowercase()}"
        Categoria.SENSORI -> if (l == Level.VERDE) "Rientro o abbassamento" else {
            val s = stazione?.let { st -> sensore?.let { Stations.soglie(st, it) } }
            val v = when (l) { Level.GIALLO -> s?.s1; Level.ARANCIONE -> s?.s2; Level.ROSSO -> s?.s3; else -> null }
            "Soglia ${l.label.lowercase()}" + (v?.let { " (≥ ${if (it % 1.0 == 0.0) it.toLong().toString() else it.toString().replace('.', ',')})" } ?: "")
        }
        Categoria.AVVISI -> "Ogni nuova chiusura o divieto"
        Categoria.COMUNICAZIONI -> "Ogni messaggio della Protezione Civile"
    }

    companion object {
        private val livelli4 = listOf(Level.ROSSO, Level.ARANCIONE, Level.GIALLO, Level.VERDE)
        val ALLERTA = Voce("allerta", Categoria.ALLERTA, "Allerta meteo", livelli4)
        val AVVISI = Voce("avvisi", Categoria.AVVISI, "Avvisi e chiusure", listOf(Level.NESSUNO))
        val COMUNICAZIONI = Voce("comunicazioni", Categoria.COMUNICAZIONI, "Comunicazioni", listOf(Level.NESSUNO))

        fun sensore(stazione: String, tipo: String): Voce {
            val id = stazione.padStart(4, '0')
            return Voce("sens_${id}_$tipo", Categoria.SENSORI, Stations.label(tipo), livelli4, id, tipo)
        }

        /** I dati notificati dal server, stazione per stazione. */
        fun sensori(): List<Voce> = Stations.all.filter { it.monitored }.flatMap { st -> Stations.sensoriNotificati(st.id).map { sensore(st.id, it) } }

        fun tutte(): List<Voce> = listOf(ALLERTA) + sensori() + listOf(AVVISI, COMUNICAZIONI)
    }
}

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

/** Preferenze delle notifiche: per ogni voce e livello, se e come avvisare. */
class NotifySettings(context: Context) {
    private val p = context.getSharedPreferences("notify", Context.MODE_PRIVATE)

    private fun default(v: Voce, l: Level): Modo = when {
        v.categoria == Categoria.AVVISI -> Modo.SILENZIOSA
        l == Level.VERDE -> Modo.SILENZIOSA     // rientri e bollettini senza allerta
        else -> Modo.SUONO
    }

    fun modo(v: Voce, l: Level): Modo =
        runCatching { Modo.valueOf(p.getString("m_${v.key}_${l.name}", null)!!) }.getOrDefault(default(v, l))
    fun setModo(v: Voce, l: Level, m: Modo) = p.edit().putString("m_${v.key}_${l.name}", m.name).apply()

    /** Almeno una voce della categoria è attiva (per il riepilogo nelle Impostazioni). */
    fun enabled(c: Categoria) = Voce.tutte().filter { it.categoria == c }.any { v -> v.livelli.any { modo(v, it) != Modo.NO } }

    /** Voce e riga di livello a cui appartiene una notifica del server. */
    fun voceDi(c: Classificata): Pair<Voce, Level> = when (c.categoria) {
        Categoria.ALLERTA -> Voce.ALLERTA to (if (c.rientro || c.livello <= Level.VERDE) Level.VERDE else c.livello)
        Categoria.SENSORI -> {
            val v = if (c.stazione != null && c.sensore != null) Voce.sensore(c.stazione, c.sensore)
            else Voce("sensori", Categoria.SENSORI, "Sensori", Voce.ALLERTA.livelli)
            v to (if (c.rientro || c.livello <= Level.VERDE) Level.VERDE else c.livello)
        }
        Categoria.AVVISI -> Voce.AVVISI to Level.NESSUNO
        Categoria.COMUNICAZIONI -> Voce.COMUNICAZIONI to Level.NESSUNO
    }

    /** Modo effettivo per una notifica: l'allarme fuori dalla sua fascia oraria diventa una notifica con suono. */
    fun modoPer(c: Classificata, now: LocalTime = LocalTime.now()): Modo {
        val (v, l) = voceDi(c)
        val m = modo(v, l)
        return if (m == Modo.ALLARME && allarmeSoloNotte && !inFascia(now, allarmeDa, allarmeA)) Modo.SUONO else m
    }

    /** Durante l'allarme alza al massimo il volume della sveglia (poi lo rimette com'era). */
    var allarmeVolumeMax: Boolean
        get() = p.getBoolean("allarmeVolumeMax", true)
        set(v) = p.edit().putBoolean("allarmeVolumeMax", v).apply()

    var allarmeSuono: SuonoAllarme
        get() = runCatching { SuonoAllarme.valueOf(p.getString("allarmeSuono", null)!!) }.getOrDefault(SuonoAllarme.SIRENA)
        set(v) = p.edit().putString("allarmeSuono", v.name).apply()

    /** L'allarme suona solo nella fascia notturna indicata (di giorno arriva la notifica con suono). */
    var allarmeSoloNotte: Boolean
        get() = p.getBoolean("allarmeSoloNotte", false)
        set(v) = p.edit().putBoolean("allarmeSoloNotte", v).apply()
    var allarmeDa: Int    // minuti dalla mezzanotte
        get() = p.getInt("allarmeDa", 22 * 60)
        set(v) = p.edit().putInt("allarmeDa", v).apply()
    var allarmeA: Int
        get() = p.getInt("allarmeA", 7 * 60)
        set(v) = p.edit().putInt("allarmeA", v).apply()

    private fun inFascia(now: LocalTime, da: Int, a: Int): Boolean {
        val m = now.hour * 60 + now.minute
        return if (da <= a) m in da until a else m >= da || m < a
    }

    /** Nella fascia in cui escono i bollettini il controllo si ripete ogni minuto. */
    var controlloVeloce: Boolean
        get() = p.getBoolean("controlloVeloce", true)
        set(v) = p.edit().putBoolean("controlloVeloce", v).apply()
    var caldo: Boolean
        get() = p.getBoolean("caldo", false)
        set(v) = p.edit().putBoolean("caldo", v).apply()
    var caldoDa: Int    // minuti dalla mezzanotte
        get() = p.getInt("caldoDa", 11 * 60)
        set(v) = p.edit().putInt("caldoDa", v).apply()
    var caldoA: Int
        get() = p.getInt("caldoA", 15 * 60)
        set(v) = p.edit().putInt("caldoA", v).apply()

    fun inCaldo(now: LocalTime = LocalTime.now()): Boolean = controlloVeloce && inFascia(now, caldoDa, caldoA)

    /** Il livello rosso suona anche nelle ore di quiete. */
    var rossoSempre: Boolean
        get() = p.getBoolean("rossoSempre", true)
        set(v) = p.edit().putBoolean("rossoSempre", v).apply()

    /** Ore di quiete: le notifiche "con suono" arrivano in silenzio (l'allarme no: ha la sua fascia). */
    var quiete: Boolean
        get() = p.getBoolean("quiete", false)
        set(v) = p.edit().putBoolean("quiete", v).apply()
    var quieteDa: Int    // minuti dalla mezzanotte
        get() = p.getInt("quieteDa", 22 * 60)
        set(v) = p.edit().putInt("quieteDa", v).apply()
    var quieteA: Int
        get() = p.getInt("quieteA", 7 * 60)
        set(v) = p.edit().putInt("quieteA", v).apply()

    fun inQuiete(now: LocalTime = LocalTime.now()): Boolean = quiete && inFascia(now, quieteDa, quieteA)

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

    private fun channelId(c: Categoria, m: Modo) = if (m == Modo.SILENZIOSA) "${c.key}_silenziosa" else c.key

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

    /**
     * Dopo ogni controllo: nella fascia in cui escono i bollettini ne programma uno tra un minuto;
     * fuori, ne programma uno all'inizio della prossima fascia.
     */
    fun pianifica(ctx: Context, s: NotifySettings) {
        val wm = WorkManager.getInstance(ctx)
        if (!s.controlloVeloce) { s.caldo = false; wm.cancelUniqueWork("$WORK-caldo"); return }
        val caldo = s.inCaldo()
        s.caldo = caldo
        val attesa = if (caldo) 1L else {
            val ora = LocalTime.now()
            val adesso = ora.hour * 60 + ora.minute
            ((s.caldoDa - adesso + 1440) % 1440).coerceAtLeast(1).toLong()
        }
        val req = OneTimeWorkRequestBuilder<FeedWorker>()
            .setInitialDelay(attesa, TimeUnit.MINUTES)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        wm.enqueueUniqueWork("$WORK-caldo", ExistingWorkPolicy.REPLACE, req)
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
        var allarme = false
        // il banner mostra il numero attuale dei sensori e il semaforo delle allerte
        if (res.notifiche.isNotEmpty()) {
            Repo.init(ctx)
            runCatching { Repo.loadMonitor(true) }
            runCatching { Repo.loadAllerta(true) }
        }
        val ricevute = res.notifiche.sortedBy { it.id }.map { item ->
            val c = Classifier.classify(item)
            val modo = s.modoPer(c)
            val show = modo != Modo.NO && now - item.ts < MAX_AGE_S
            if (show) {
                show(ctx, item, c, s, modo)
                allarme = allarme || modo == Modo.ALLARME
            }
            Ricevuta(item, show)
        }
        History.add(ctx, ricevute)
        if (res.notifiche.isNotEmpty()) s.lastId = maxOf(s.lastId, res.notifiche.maxOf { it.id })
        if (allarme) {
            // suona finché l'utente non lo ferma; il controllo resta attivo per non interromperlo
            Alarm.start(ctx)
            Alarm.awaitEnd()
        }
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

    /** Banner nello stile dei widget: fondo del colore del livello, testo nero (bianco su rosso e grafite). */
    private fun banner(ctx: Context, item: FeedItem, c: Classificata, esteso: Boolean): android.widget.RemoteViews {
        val (fondo, testo) = when {
            c.rientro -> 0xFF22C55E.toInt() to 0xFF0A0A0A.toInt()
            c.livello == Level.ROSSO -> 0xFFDC2626.toInt() to 0xFFFFFFFF.toInt()
            c.livello == Level.ARANCIONE -> 0xFFFB923C.toInt() to 0xFF0A0A0A.toInt()
            c.livello == Level.GIALLO -> 0xFFFACC15.toInt() to 0xFF0A0A0A.toInt()
            c.categoria == Categoria.AVVISI -> 0xFF334155.toInt() to 0xFFFFFFFF.toInt()
            c.categoria == Categoria.COMUNICAZIONI || c.livello == Level.NESSUNO -> 0xFF1F1A1A.toInt() to 0xFFFFFFFF.toInt()
            else -> 0xFF22C55E.toInt() to 0xFF0A0A0A.toInt()
        }
        val sensore = if (c.categoria == Categoria.SENSORI && c.stazione != null && c.sensore != null) c else null
        val lettura = sensore?.let { Repo.monitor.value.data?.get(it.stazione!!)?.sensori?.get(it.sensore!!) }
        val etichetta = when {
            sensore != null -> "${Stations.byId(sensore.stazione!!)?.name ?: sensore.stazione} · ${Stations.label(sensore.sensore!!)}"
            c.rientro -> "${c.categoria.titolo} · rientro"
            c.categoria == Categoria.COMUNICAZIONI || c.livello == Level.NESSUNO -> c.categoria.titolo
            else -> "${c.categoria.titolo} · ${c.livello.label}"
        }
        return android.widget.RemoteViews(ctx.packageName, R.layout.notif_banner).apply {
            setInt(R.id.banner_bg, "setColorFilter", fondo)
            setTextViewText(R.id.banner_tag, etichetta)
            // il titolo senza emoji iniziali (l'etichetta in alto dice già il livello)
            setTextViewText(R.id.banner_title, item.title.replaceFirst(Regex("""^[^\p{L}\p{N}]+"""), "").ifBlank { item.title })
            // sensori: il numero attuale è il protagonista; il titolo compare nel banner espanso
            if (lettura?.valore != null) {
                setViewVisibility(R.id.banner_value, android.view.View.VISIBLE)
                setTextViewText(R.id.banner_value, io.github.andreadp271.castelsafe.ui.fmtValue(lettura.valore, lettura.unita))
                setTextColor(R.id.banner_value, testo)
                if (!esteso) setViewVisibility(R.id.banner_title, android.view.View.GONE)
            }
            // allerte: il semaforo dei rischi attivi
            val a = if (c.categoria == Categoria.ALLERTA) Repo.allerta.value.data else null
            val righe = a?.let { semaforo(it) }.orEmpty()
            // con la tabella, le righe a parole con i bollini ripetono gli stessi livelli: restano solo gli orari
            val corpo = if (righe.isNotEmpty()) item.body.lines().filterNot { "Idra:" in it || it.startsWith("Oggi —") || it.startsWith("Domani —") }.joinToString(separator = 10.toChar().toString()).trim() else item.body
            val testoCorpo = when (c.categoria) {
                Categoria.ALLERTA -> pulisciAllerta(corpo)
                Categoria.AVVISI -> pulisciAvviso(corpo)
                else -> corpo
            }
            setTextViewText(R.id.banner_body, testoCorpo)
            if (testoCorpo.isBlank()) setViewVisibility(R.id.banner_body, android.view.View.GONE)
            if (esteso && righe.isNotEmpty()) {
                setViewVisibility(R.id.banner_table, android.view.View.VISIBLE)
                removeAllViews(R.id.banner_table)
                addView(R.id.banner_table, riga(ctx, null, null, null, testo, intestazione = true))
                righe.forEach { (nome, o, d) -> addView(R.id.banner_table, riga(ctx, nome, o, d, testo)) }
            }
            if (!esteso) setViewVisibility(R.id.banner_body, android.view.View.GONE)
            else setInt(R.id.banner_body, "setMaxLines", 8)
            listOf(R.id.banner_tag, R.id.banner_title, R.id.banner_body).forEach { setTextColor(it, testo) }
        }
    }

    private val mesi = listOf("gen", "feb", "mar", "apr", "mag", "giu", "lug", "ago", "set", "ott", "nov", "dic")
    private val periodo = Regex("""(Oggi|Domani):\s*(\d{2})/(\d{2})/\d{4}\s+(\d{2}:\d{2})\s*\S+\s*\d{2}/\d{2}/\d{4}\s+(\d{2}:\d{2})""")
    private val emoji = Regex("""[\p{So}\p{Cs}\u200d\ufe0f]""")

    private val dalAl = Regex("""Dal\s+(\d{2})/(\d{2})/\d{4}\s+al\s+(\d{2})/(\d{2})/\d{4}""")

    /** Chiusure: descrizione, poi "Dove" e "Quando" su righe separate, con date brevi e senza emoji. */
    private fun pulisciAvviso(corpo: String): String = corpo.split(" – ").mapNotNull { parte ->
        val p = emoji.replace(parte, "").trim()
        val m = dalAl.find(p)
        val riga = when {
            m != null -> {
                val (d1, m1, d2, m2) = m.destructured
                fun giorno(g: String, mese: String) = g.trimStart('0') + " " + mesi.getOrElse(mese.toInt() - 1) { mese }
                "Quando · dal " + giorno(d1, m1) + " al " + giorno(d2, m2)
            }
            p.startsWith("Area:") -> "Dove · " + p.removePrefix("Area:").trim()
            else -> p
        }
        riga.ifBlank { null }
    }.joinToString(10.toChar().toString())

    /** Orari del bollettino in chiaro ("Oggi · 20 ott · 12:00 – 24:00"), senza emoji. */
    private fun pulisciAllerta(corpo: String): String = corpo.lines().mapNotNull { riga ->
        val m = periodo.find(riga)
        val r = if (m != null) {
            val (giorno, gg, mm, da, a) = m.destructured
            "$giorno · ${gg.trimStart('0')} ${mesi.getOrElse(mm.toInt() - 1) { mm }} · $da – $a"
        } else emoji.replace(riga, "").trim()
        r.ifBlank { null }
    }.joinToString(10.toChar().toString())

    /** Tutti i rischi del bollettino, i più gravi per primi: nome e livello di oggi e di domani. */
    private fun semaforo(a: Allerta): List<Triple<String, Level, Level>> {
        val oggi = a.oggi?.rischi.orEmpty().toMap()
        val domani = a.domani?.rischi.orEmpty().toMap()
        return (oggi.keys + domani.keys).distinct()
            .map { Triple(it, oggi[it] ?: Level.NESSUNO, domani[it] ?: Level.NESSUNO) }
            .sortedByDescending { maxOf(it.second, it.third) }
    }

    private fun colore(l: Level): Int = when (l) {
        Level.ROSSO -> 0xFFDC2626.toInt()
        Level.ARANCIONE -> 0xFFFB923C.toInt()
        Level.GIALLO -> 0xFFFACC15.toInt()
        else -> 0xFF22C55E.toInt()
    }

    private fun riga(ctx: Context, nome: String?, o: Level?, d: Level?, ink: Int, intestazione: Boolean = false): android.widget.RemoteViews =
        android.widget.RemoteViews(ctx.packageName, R.layout.notif_row).apply {
            setTextViewText(R.id.row_name, nome.orEmpty())
            setTextColor(R.id.row_name, ink)
            // i rischi a verde restano più discreti
            if (o != null && d != null && maxOf(o, d) <= Level.VERDE) setFloat(R.id.row_name, "setAlpha", 0.55f)
            fun punto(box: Int, ring: Int, inner: Int, l: Level?) {
                if (l == null) { setViewVisibility(box, android.view.View.INVISIBLE); return }
                setInt(ring, "setColorFilter", ink)
                setInt(inner, "setColorFilter", colore(l))
            }
            if (intestazione) {
                listOf(R.id.row_h1 to "OGGI", R.id.row_h2 to "DOMANI").forEach { (id, t) ->
                    setViewVisibility(id, android.view.View.VISIBLE); setTextViewText(id, t); setTextColor(id, ink)
                }
                setViewVisibility(R.id.row_d1, android.view.View.GONE)
                setViewVisibility(R.id.row_d2, android.view.View.GONE)
            } else {
                punto(R.id.row_d1, R.id.row_d1_ring, R.id.row_d1_in, o)
                punto(R.id.row_d2, R.id.row_d2_ring, R.id.row_d2_in, d)
            }
        }

    fun show(ctx: Context, item: FeedItem, c: Classificata, s: NotifySettings, modo: Modo) {
        if (!canNotify(ctx) || modo == Modo.NO) return
        val rosso = c.livello == Level.ROSSO && !c.rientro
        val allarme = modo == Modo.ALLARME
        val channel = when {
            allarme -> CH_CRITICO
            modo == Modo.SILENZIOSA -> channelId(c.categoria, Modo.SILENZIOSA)
            rosso && s.rossoSempre -> CH_CRITICO
            s.inQuiete() -> channelId(c.categoria, Modo.SILENZIOSA)
            rosso -> CH_CRITICO
            else -> channelId(c.categoria, Modo.SUONO)
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
            .setStyle(NotificationCompat.DecoratedCustomViewStyle())
            .setCustomContentView(banner(ctx, item, c, false))
            .setCustomHeadsUpContentView(banner(ctx, item, c, false))
            .setCustomBigContentView(banner(ctx, item, c, true))
            .setWhen(item.ts * 1000)
            .setShowWhen(true)
            .setAutoCancel(true)
            .setContentIntent(pi)
            .setCategory(if (c.categoria == Categoria.ALLERTA || rosso) NotificationCompat.CATEGORY_ALARM else NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(if (channel.endsWith("_silenziosa")) NotificationCompat.PRIORITY_LOW else NotificationCompat.PRIORITY_HIGH)
            .apply {
                if (allarme) {
                    val stop = PendingIntent.getBroadcast(ctx, 1, Intent(ctx, AlarmStopReceiver::class.java), PendingIntent.FLAG_IMMUTABLE)
                    addAction(0, "Ferma allarme", stop)
                    setDeleteIntent(stop)
                }
            }
            .build()
        // stesso tag = stessa notifica: l'aggiornamento di un sensore sostituisce quella precedente
        runCatching { NotificationManagerCompat.from(ctx).notify(item.tag, 0, n) }
    }

    /** Notifica di prova per verificare come arriva una voce a un certo livello (l'allarme suona 5 secondi). */
    fun test(ctx: Context, v: Voce, l: Level) {
        val s = NotifySettings(ctx)
        val m = s.modo(v, l)
        if (m == Modo.NO) return
        val item = FeedItem(
            id = 0, ts = System.currentTimeMillis() / 1000, tag = "prova-${v.key}",
            title = "Prova: ${v.titolo}",
            body = v.etichetta(l),
        )
        val c = Classificata(v.categoria, if (l == Level.VERDE) Level.VERDE else l, l == Level.VERDE, v.stazione, v.sensore)
        // la prova mostra i dati veri di adesso: se non sono ancora in memoria li scarica prima
        Repo.init(ctx)
        val mostra = { show(ctx, item, c, s, m) }
        if (Repo.monitor.value.data == null || Repo.allerta.value.data == null) {
            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                runCatching { Repo.loadMonitor() }
                runCatching { Repo.loadAllerta() }
                mostra()
            }
        } else mostra()
        if (m == Modo.ALLARME) Alarm.start(ctx, 5)
    }
}

class FeedWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val s = NotifySettings(applicationContext)
        // i widget si aggiornano a ogni controllo, anche se il registro notifiche non risponde
        runCatching { io.github.andreadp271.castelsafe.widget.Widgets.updateAll(applicationContext) }
        return try {
            Notifier.check(applicationContext)
            s.lastCheck = System.currentTimeMillis()
            s.lastCheckError = null
            Notifier.pianifica(applicationContext, s)
            Result.success()
        } catch (e: Exception) {
            s.lastCheckError = Repo.errorText(e)
            if (runAttemptCount < 2) Result.retry() else Result.success()
        }
    }
}
