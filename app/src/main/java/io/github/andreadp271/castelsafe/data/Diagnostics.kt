package io.github.andreadp271.castelsafe.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.Request
import java.util.concurrent.TimeUnit

/** Verifica della raggiungibilità dei servizi usati dall'app (pagina "Verifica connessione"). */
object Diagnostics {
    data class Check(val group: String, val name: String, val what: String, val url: String, val post: String? = null)

    data class Result(val ok: Boolean, val ms: Long, val detail: String)

    private val client = Api.client.newBuilder().connectTimeout(10, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS).build()

    val checks: List<Check> = listOf(
        Check("Rete", "Internet", "Connessione del telefono", "https://connectivitycheck.gstatic.com/generate_204"),
        Check("Server CastelSafe", "Bollettino di allerta", "Server della Protezione Civile (Raspberry)", "${Config.API_BASE}/api/allerta"),
        Check("Server CastelSafe", "Sensori in tempo reale", "Ultimi valori e soglie", "${Config.API_BASE}/api/monitor/stato"),
        Check("Server CastelSafe", "Storico ARPAV", "Grafici delle stazioni (via server)", "${Config.API_BASE}/api/arpav/xml/0292"),
        Check("Server CastelSafe", "Previsioni meteo", "Bollettino ARPAV (via server)", "${Config.API_BASE}/api/arpav/bollettino-utenti"),
        Check("Server CastelSafe", "Avvisi e chiusure", "Elenco pubblicato dal Comune", "${Config.API_BASE}/api/closures"),
        Check("Server CastelSafe", "Registro notifiche", "Usato per le notifiche dell'app", "${Config.API_BASE}/api/notifiche/feed?limite=1"),
        Check("Mappa", "Tessere OpenStreetMap", "Sfondo della mappa", "https://tile.openstreetmap.org/12/2183/1462.png"),
        Check("Mappa", "Overpass (via server)", "Aree di protezione civile e corsi d'acqua", "${Config.API_BASE}/api/overpass", post = "[out:json][timeout:10];node(1);out;"),
        Check("Mappa", "Overpass diretto", "Ripiego se il server non risponde", "https://overpass-api.de/api/status"),
        Check("Mappa", "Nominatim", "Confine comunale", "https://nominatim.openstreetmap.org/status"),
        Check("Radar", "Radar-DPC: ultimo dato", "Protezione Civile nazionale", "https://radar-api.protezionecivile.it/findLastProductByType?type=VMI"),
        Check("Radar", "Radar-DPC: immagini", "Archivio tessere (Amazon S3)", "RADAR_TILE"),
        Check("App", "Aggiornamenti", "Release su GitHub", "https://api.github.com/repos/${Config.GITHUB_REPO}/releases/latest"),
    )

    suspend fun run(c: Check): Result = withContext(Dispatchers.IO) {
        val start = System.nanoTime()
        fun ms() = (System.nanoTime() - start) / 1_000_000
        try {
            val url = if (c.url == "RADAR_TILE") {
                val last = Api.radarLast()
                val d = java.time.Instant.ofEpochMilli(last).atZone(java.time.ZoneOffset.UTC)
                "https://s3-prod-dpc-radar-webp-cache.s3.eu-south-1.amazonaws.com/VMI/%04d/%02d/%02d/%02d%02d/7/68/45/vmi.webp"
                    .format(d.year, d.monthValue, d.dayOfMonth, d.hour, d.minute)
            } else c.url
            val req = Request.Builder().url(url).apply {
                if (c.post != null) post(FormBody.Builder().add("data", c.post).build())
            }.build()
            client.newCall(req).execute().use { r ->
                val bytes = r.body?.bytes()?.size ?: 0
                val ok = r.isSuccessful || (c.name == "Aggiornamenti" && r.code == 404)
                val detail = when {
                    c.name == "Aggiornamenti" && r.code == 404 -> "Raggiungibile, nessuna release pubblicata"
                    r.isSuccessful -> "HTTP ${r.code} · ${fmtBytes(bytes)}"
                    else -> "HTTP ${r.code} ${r.message}".trim()
                }
                Result(ok, ms(), detail)
            }
        } catch (e: Exception) {
            Result(false, ms(), Repo.errorText(e) + (e.javaClass.simpleName.takeIf { it !in (e.message ?: "") }?.let { " ($it)" } ?: ""))
        }
    }

    private fun fmtBytes(b: Int) = if (b >= 1024) "${b / 1024} kB" else "$b B"
}
