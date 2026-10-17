package io.github.andreadp271.castelsafe.data

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalDateTime

/** Dato caricato dal server, con l'ultima copia valida conservata se la rete manca. */
data class Res<T>(
    val data: T? = null,
    val loading: Boolean = false,
    val error: String? = null,
    /** Quando è stato scaricato il dato mostrato (null = mai). */
    val fetchedAt: LocalDateTime? = null,
    /** true se il dato mostrato viene dalla copia salvata e l'ultimo aggiornamento è fallito. */
    val fromCache: Boolean = false,
)

/**
 * Stato dell'app: ogni risorsa ha una copia su disco, così all'apertura (anche senza rete)
 * si vede subito l'ultimo dato scaricato, segnalato come non aggiornato.
 */
object Repo {
    private lateinit var dir: File

    val allerta = MutableStateFlow(Res<Allerta>())
    val monitor = MutableStateFlow(Res<Map<String, StatoStazione>>())
    val chiusure = MutableStateFlow(Res<List<Chiusura>>())
    val meteo = MutableStateFlow(Res<Meteo>())
    val aree = MutableStateFlow(Res<List<AreaPC>>())
    val corsiAcqua = MutableStateFlow(Res<List<CorsoAcqua>>())
    private val stazioni = mutableMapOf<String, MutableStateFlow<Res<List<Serie>>>>()

    private val locks = mutableMapOf<String, Mutex>()

    private lateinit var assets: android.content.res.AssetManager

    fun init(ctx: Context) {
        if (::dir.isInitialized) return
        dir = File(ctx.filesDir, "cache").apply { mkdirs() }
        assets = ctx.assets
    }

    /** Copia inclusa nell'app (assets/seed): la mappa ha aree e corsi d'acqua già al primo avvio. */
    private fun seed(key: String): String? = runCatching { assets.open("seed/$key.txt").use { it.readBytes().decodeToString() } }.getOrNull()

    fun stazione(id: String): StateFlow<Res<List<Serie>>> = synchronized(stazioni) {
        stazioni.getOrPut(id) { MutableStateFlow(Res()) }
    }

    private fun file(key: String) = File(dir, "$key.txt")

    /**
     * Scarica [key] con [fetch], lo interpreta con [parse] e aggiorna [flow].
     * Se il download fallisce resta il dato precedente (o quello salvato su disco).
     */
    private suspend fun <T> load(
        key: String,
        flow: MutableStateFlow<Res<T>>,
        maxAgeMinutes: Long,
        force: Boolean,
        fetch: suspend () -> String,
        parse: (String) -> T,
    ) = appScope.async {
        // nello scope dell'app: cambiare schermata durante il download non lo interrompe
        val mutex = synchronized(locks) { locks.getOrPut(key) { Mutex() } }
        mutex.withLock {
            val f = file(key)
            // prima apertura: mostra subito la copia su disco (o quella inclusa nell'app)
            if (flow.value.data == null && f.exists()) {
                withContext(Dispatchers.IO) { runCatching { parse(f.readText()) }.getOrNull() }?.let {
                    val at = LocalDateTime.ofEpochSecond(f.lastModified() / 1000, 0, java.time.ZoneId.systemDefault().rules.getOffset(java.time.Instant.ofEpochMilli(f.lastModified())))
                    flow.value = Res(it, fetchedAt = at, fromCache = true)
                }
            }
            if (flow.value.data == null) {
                withContext(Dispatchers.IO) { seed(key)?.let { raw -> runCatching { parse(raw) }.getOrNull() } }?.let {
                    flow.value = Res(it, fetchedAt = null, fromCache = true) // da aggiornare appena possibile
                }
            }
            val cur = flow.value
            // l'età conta anche per la copia su disco: dati OSM di ieri non vanno riscaricati a ogni avvio
            val fresh = cur.fetchedAt != null && cur.fetchedAt.isAfter(LocalDateTime.now().minusMinutes(maxAgeMinutes))
            if (fresh && !force) return@withLock
            flow.value = cur.copy(loading = true)
            try {
                val raw = fetch()
                val parsed = withContext(Dispatchers.Default) { parse(raw) }
                withContext(Dispatchers.IO) { runCatching { f.writeText(raw) } }
                flow.value = Res(parsed, fetchedAt = LocalDateTime.now())
            } catch (e: Exception) {
                flow.value = flow.value.copy(loading = false, error = errorText(e), fromCache = flow.value.data != null)
            }
        }
    }.await()

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    fun errorText(e: Throwable): String = when (e) {
        is java.net.UnknownHostException -> "Nessuna connessione a Internet"
        is java.net.SocketTimeoutException -> "Il server non risponde"
        is java.net.ConnectException -> "Server non raggiungibile"
        is javax.net.ssl.SSLException -> "Connessione sicura non riuscita"
        else -> e.message ?: e.javaClass.simpleName
    }

    suspend fun loadAllerta(force: Boolean = false) =
        load("allerta", allerta, 5, force, Api::allertaRaw, Api::parseAllerta)

    suspend fun loadMonitor(force: Boolean = false) =
        load("monitor", monitor, 2, force, Api::monitorRaw, Api::parseMonitor)

    suspend fun loadChiusure(force: Boolean = false) =
        load("chiusure", chiusure, 5, force, Api::closuresRaw, Api::parseClosures)

    suspend fun loadMeteo(force: Boolean = false) =
        load("meteo", meteo, 30, force, Api::meteoRaw) { Api.parseMeteo(it) }

    /** Ora in cui l'app ha visto arrivare un dato ARPAV più recente, per stazione (serve al countdown). */
    val nuovoDatoVistoAlle = java.util.concurrent.ConcurrentHashMap<String, LocalDateTime>()

    private fun ultimoIstante(r: Res<List<Serie>>) = r.data?.mapNotNull { it.ultimo?.istante }?.maxOrNull()

    suspend fun loadStazione(id: String, force: Boolean = false) {
        val flow = synchronized(stazioni) { stazioni.getOrPut(id) { MutableStateFlow(Res()) } }
        val prima = ultimoIstante(flow.value)
        load("stazione_$id", flow, 10, force, { Api.stationXml(id) }, Api::parseStation)
        val dopo = ultimoIstante(flow.value)
        if (prima != null && dopo != null && dopo.isAfter(prima)) nuovoDatoVistoAlle[id] = LocalDateTime.now()
    }

    /** Dati OSM: cambiano raramente, si aggiornano al massimo una volta al giorno. */
    suspend fun loadAree(force: Boolean = false) =
        load("aree", aree, 24 * 60, force, { Api.overpass(Api.queryAree) }, Api::parseAree)

    suspend fun loadCorsiAcqua(force: Boolean = false) =
        load("corsi_acqua_v2", corsiAcqua, 7 * 24 * 60, force, { Api.overpass(Api.queryCorsiAcqua) }, Api::parseCorsiAcqua)

    val confine = MutableStateFlow(Res<List<List<Pair<Double, Double>>>>())
    suspend fun loadConfine(force: Boolean = false) =
        load("confine", confine, 30 * 24 * 60, force, Api::confineRaw, Api::parseConfine)

    /** Tutto quello che serve alla schermata Home. */
    suspend fun refreshHome(force: Boolean) = coroutineScope {
        listOf(
            async { loadAllerta(force) },
            async { loadMonitor(force) },
            async { loadChiusure(force) },
            async { loadMeteo(force) },
        ).forEach { it.await() }
    }
}
