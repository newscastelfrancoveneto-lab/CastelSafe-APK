package io.github.andreadp271.castelsafe.data

import android.util.Xml
import io.github.andreadp271.castelsafe.BuildConfig
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.xmlpull.v1.XmlPullParser
import java.io.IOException
import java.io.StringReader
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit

/** Accesso al server CastelSafe (Raspberry) e parsing delle risposte. */
object Api {
    val json = Json { ignoreUnknownKeys = true; isLenient = true; coerceInputValues = true }

    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .addInterceptor { chain ->
            chain.proceed(chain.request().newBuilder().header("User-Agent", "CastelSafe-Android/${BuildConfig.VERSION_NAME}").build())
        }
        .build()

    private val overpassClient = client.newBuilder().readTimeout(60, TimeUnit.SECONDS).build()

    private fun getBytes(url: String, c: OkHttpClient = client): ByteArray {
        c.newCall(Request.Builder().url(url).build()).execute().use { r ->
            if (!r.isSuccessful) throw IOException("Il server ha risposto ${r.code}")
            return r.body!!.bytes()
        }
    }

    fun getText(url: String): String = decode(getBytes(url))

    /** UTF-8 se valido, altrimenti ISO-8859-1 (alcuni XML ARPAV, es. stazione 0102). */
    fun decode(bytes: ByteArray): String = try {
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes)).toString()
    } catch (e: CharacterCodingException) {
        String(bytes, Charsets.ISO_8859_1)
    }

    private fun url(path: String) = Config.API_BASE + path

    // ── Endpoints ────────────────────────────────────────────────────────────

    suspend fun allertaRaw(): String = withContext(Dispatchers.IO) { getText(url("/api/allerta")) }
    fun parseAllerta(s: String): Allerta = json.decodeFromString(Allerta.serializer(), s)

    suspend fun monitorRaw(): String = withContext(Dispatchers.IO) { getText(url("/api/monitor/stato")) }
    fun parseMonitor(s: String): Map<String, StatoStazione> =
        json.parseToJsonElement(s).jsonObject.mapValues { json.decodeFromJsonElement(StatoStazione.serializer(), it.value) }

    suspend fun closuresRaw(): String = withContext(Dispatchers.IO) { getText(url("/api/closures")) }
    suspend fun meteoRaw(): String = withContext(Dispatchers.IO) { getText(url("/api/arpav/bollettino-utenti")) }
    suspend fun stationXml(id: String): String = withContext(Dispatchers.IO) { getText(url("/api/arpav/xml/$id")) }

    suspend fun feed(after: Long): FeedResponse = withContext(Dispatchers.IO) {
        json.decodeFromString(FeedResponse.serializer(), getText(url("/api/notifiche/feed?dopo=$after&limite=50")))
    }

    /** Statistiche anonime: stesso schema della PWA (ID casuale, nessun IP salvato). */
    suspend fun analyticsHit(cid: String) = withContext(Dispatchers.IO) {
        val body = """{"cid":"$cid","standalone":true}""".toRequestBody("application/json".toMediaType())
        runCatching {
            client.newCall(Request.Builder().url(url("/api/analytics/hit")).post(body).build()).execute().close()
        }
    }

    /** Mirror Overpass pubblici, gli stessi della PWA (overpass.osm.ch escluso: risponde subito ma vuoto). */
    private val overpassMirrors = listOf(
        "https://overpass.private.coffee/api/interpreter",
        "https://overpass.kumi.systems/api/interpreter",
        "https://overpass.monicz.dev/api/interpreter",
        "https://overpass.openstreetmap.fr/api/interpreter",
        "https://maps.mail.ru/osm/tools/overpass/api/interpreter",
        "https://overpass-api.de/api/interpreter",
        "https://lz4.overpass-api.de/api/interpreter",
    )

    private val emptyElements = Regex(""""elements"\s*:\s*\[\s*]""")

    /**
     * Query Overpass "multicanale" come la PWA: la stessa richiesta parte in parallelo verso tutti
     * i mirror e il proxy del server, vince la prima risposta valida e le altre vengono annullate.
     * Una risposta senza elementi vale solo se nessun altro canale ne dà una piena.
     */
    suspend fun overpass(query: String): String = coroutineScope {
        val endpoints = listOf(url("/api/overpass")) + overpassMirrors
        val winner = CompletableDeferred<String>()
        var empty: String? = null
        var last: Exception? = null
        val jobs = endpoints.map { endpoint ->
            launch(Dispatchers.IO) {
                try {
                    val body = FormBody.Builder().add("data", query).build()
                    val call = overpassClient.newCall(Request.Builder().url(endpoint).post(body).build())
                    val text = kotlinx.coroutines.runInterruptible { call.execute() }.use { r ->
                        if (!r.isSuccessful) throw IOException("OpenStreetMap: HTTP ${r.code}")
                        r.body!!.string()
                    }
                    if (!text.trimStart().startsWith("{")) throw IOException("OpenStreetMap: risposta non valida")
                    if (emptyElements.containsMatchIn(text)) empty = text
                    else winner.complete(text)
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    last = e
                }
            }
        }
        launch { jobs.joinAll(); winner.complete(empty ?: throw (last ?: IOException("OpenStreetMap non raggiungibile"))) }
        try { winner.await() } finally { coroutineContext.cancelChildren() }
    }

    // ── Parsing chiusure (GeoJSON) ───────────────────────────────────────────

    fun parseClosures(s: String): List<Chiusura> =
        json.parseToJsonElement(s).jsonArray.mapNotNull { f ->
            runCatching {
                val o = f.jsonObject
                val p = o["properties"]?.jsonObject ?: JsonObject(emptyMap())
                fun str(k: String) = p[k]?.jsonPrimitive?.contentOrNull.orEmpty()
                val forme = mutableListOf<List<Pair<Double, Double>>>()
                val punti = mutableListOf<Pair<Double, Double>>()
                o["geometry"]?.let { collectGeometry(it, forme, punti) }
                Chiusura(
                    id = str("id"),
                    titolo = str("title").ifBlank { "Avviso" },
                    descrizione = str("description"),
                    aree = (p["osmNames"] as? JsonArray)?.mapNotNull { it.jsonPrimitive.contentOrNull }.orEmpty(),
                    inizio = Chiusura.dataOra(str("startDate"), str("startTime"), LocalTime.MIN),
                    fine = Chiusura.dataOra(str("endDate"), str("endTime"), LocalTime.of(23, 59)),
                    inizioTesto = listOf(itDate(str("startDate")), str("startTime")).filter { it.isNotBlank() }.joinToString(" "),
                    fineTesto = listOf(itDate(str("endDate")), str("endTime")).filter { it.isNotBlank() }.joinToString(" "),
                    forme = forme,
                    punti = punti,
                )
            }.getOrNull()
        }

    private fun itDate(iso: String) = runCatching {
        java.time.LocalDate.parse(iso).format(DateTimeFormatter.ofPattern("dd/MM/yyyy"))
    }.getOrDefault(iso)

    private fun coord(e: JsonElement): Pair<Double, Double>? {
        val a = e.jsonArray
        val lon = a.getOrNull(0)?.jsonPrimitive?.doubleOrNull ?: return null
        val lat = a.getOrNull(1)?.jsonPrimitive?.doubleOrNull ?: return null
        return lat to lon
    }

    private fun collectGeometry(g: JsonElement, forme: MutableList<List<Pair<Double, Double>>>, punti: MutableList<Pair<Double, Double>>) {
        val o = g.jsonObject
        val c = o["coordinates"]
        when (o["type"]?.jsonPrimitive?.contentOrNull) {
            "Point" -> c?.let { coord(it) }?.let(punti::add)
            "MultiPoint" -> c?.jsonArray?.mapNotNull { coord(it) }?.let(punti::addAll)
            "LineString" -> c?.jsonArray?.mapNotNull { coord(it) }?.let(forme::add)
            "MultiLineString", "Polygon" -> c?.jsonArray?.forEach { r -> forme.add(r.jsonArray.mapNotNull { coord(it) }) }
            "MultiPolygon" -> c?.jsonArray?.forEach { poly -> poly.jsonArray.forEach { r -> forme.add(r.jsonArray.mapNotNull { coord(it) }) } }
            "GeometryCollection" -> o["geometries"]?.jsonArray?.forEach { collectGeometry(it, forme, punti) }
        }
    }

    // ── Parsing previsioni meteo (XML ARPAV) ─────────────────────────────────

    fun parseMeteo(xml: String, zone: String = Config.WEATHER_ZONE): Meteo {
        val p = Xml.newPullParser()
        p.setInput(StringReader(xml.substringAfter("?>", xml)))
        var emissione = ""
        var aggiornamento = ""
        val scadenze = mutableListOf<Scadenza>()
        var inZone = false
        var label = ""
        var values = mutableMapOf<String, String>()
        while (p.next() != XmlPullParser.END_DOCUMENT) {
            when (p.eventType) {
                XmlPullParser.START_TAG -> when (p.name) {
                    "data_emissione" -> emissione = p.getAttributeValue(null, "date").orEmpty()
                    "data_aggiornamento" -> aggiornamento = p.getAttributeValue(null, "date").orEmpty()
                    "meteogramma" -> inZone = p.getAttributeValue(null, "name") == zone
                    "scadenza" -> if (inZone) { label = p.getAttributeValue(null, "data").orEmpty(); values = mutableMapOf() }
                    "previsione" -> if (inZone) values[p.getAttributeValue(null, "title").orEmpty()] = p.getAttributeValue(null, "value").orEmpty()
                }
                XmlPullParser.END_TAG -> when (p.name) {
                    "meteogramma" -> if (inZone) inZone = false
                    "scadenza" -> if (inZone) {
                        val m = Regex("""^(.*?)\s+(mattina|pomeriggio|sera|notte)$""", RegexOption.IGNORE_CASE).find(label.trim())
                        scadenze += Scadenza(
                            etichetta = label,
                            giorno = m?.groupValues?.get(1) ?: label,
                            fascia = m?.groupValues?.get(2),
                            simbolo = values["Simbolo"].orEmpty(),
                            cielo = values["Cielo"].orEmpty(),
                            temperatura = values["Temperatura"].orEmpty().replace(Regex("""\s*C$"""), "°C"),
                            precipitazioni = values["Precipitazioni"].orEmpty(),
                            probabilita = values["Probabilita' precipitazione"].orEmpty(),
                            quotaNeve = values["Quota neve"].orEmpty(),
                            attendibilita = values["Attendibilita'"].orEmpty(),
                        )
                    }
                }
            }
        }
        if (scadenze.isEmpty()) throw IOException("Zona \"$zone\" non trovata nel bollettino meteo")
        return Meteo(emissione, aggiornamento, scadenze)
    }

    // ── Parsing storico stazione (XML ARPAV) ─────────────────────────────────

    /**
     * Gli ISTANTE dei dati sono già in ora legale italiana (la converte il server, che manda anche
     * SOLARE: l'ora solare ARPAV, senza ripetizioni, per ordinare e fare i conti).
     */
    private val arpavTime = DateTimeFormatter.ofPattern("yyyyMMddHHmm")

    fun parseStation(xml: String): List<Serie> {
        val p = Xml.newPullParser()
        p.setInput(StringReader(xml.substringAfter("?>", xml)))
        val out = mutableListOf<Serie>()
        var type = ""; var nome = ""; var unita = ""
        var punti = mutableListOf<Punto>()
        var istante: LocalDateTime? = null
        var solare: LocalDateTime? = null
        var text = StringBuilder()
        var inSensore = false
        while (p.next() != XmlPullParser.END_DOCUMENT) {
            when (p.eventType) {
                XmlPullParser.START_TAG -> {
                    text = StringBuilder()
                    when (p.name) {
                        "SENSORE" -> { inSensore = true; type = ""; nome = ""; unita = ""; punti = mutableListOf() }
                        "DATI" -> {
                            istante = runCatching { LocalDateTime.parse(p.getAttributeValue(null, "ISTANTE"), arpavTime) }.getOrNull()
                            // SOLARE arriva dal server; con un server vecchio si ricava dall'ora legale
                            solare = p.getAttributeValue(null, "SOLARE")?.let { runCatching { LocalDateTime.parse(it, arpavTime) }.getOrNull() }
                                ?: istante?.let { Tempo.aSolare(it) }
                        }
                    }
                }
                XmlPullParser.TEXT -> text.append(p.text)
                XmlPullParser.END_TAG -> if (inSensore) when (p.name) {
                    "TYPE" -> type = text.toString().trim()
                    "PARAMNM" -> nome = text.toString().trim()
                    "UNITNM" -> unita = text.toString().trim()
                    "VM" -> istante?.let { punti += Punto(it, text.toString().trim().replace(',', '.').toDoubleOrNull(), solare ?: it) }
                    "SENSORE" -> { inSensore = false; if (type.isNotEmpty()) out += Serie(type, nome, unita, punti.sortedBy { it.solare }) }
                }
            }
        }
        return out
    }

    // ── OpenStreetMap: aree di protezione civile e corsi d'acqua ─────────────

    /** Confine comunale di Castelfranco Veneto (relazione OSM 45549). */
    private const val AREA = "area(3600045549)->.a;"

    val queryAree = """
        [out:json][timeout:30];$AREA
        (
          way["emergency"~"^(waiting_area|camp_area|staging_area)$"](area.a);
          way["emergency:waiting_area"="yes"](area.a);
          way["emergency:camp_area"="yes"](area.a);
          way["emergency:staging_area"](area.a);
        );
        out geom qt;
    """.trimIndent()

    val queryCorsiAcqua = """
        [out:json][timeout:30];$AREA
        (
          way["waterway"~"^(river|stream|canal|drain|ditch)$"](area.a);
        );
        out geom qt;
    """.trimIndent()

    private fun ways(s: String): List<Pair<Map<String, String>, List<Pair<Double, Double>>>> =
        json.parseToJsonElement(s).jsonObject["elements"]?.jsonArray.orEmpty().mapNotNull { el ->
            val o = el.jsonObject
            if (o["type"]?.jsonPrimitive?.contentOrNull != "way") return@mapNotNull null
            val tags = o["tags"]?.jsonObject?.mapValues { it.value.jsonPrimitive.contentOrNull.orEmpty() }.orEmpty()
            val geom = o["geometry"]?.jsonArray.orEmpty().mapNotNull { pt ->
                val po = pt.jsonObject
                val lat = po["lat"]?.jsonPrimitive?.doubleOrNull
                val lon = po["lon"]?.jsonPrimitive?.doubleOrNull
                if (lat != null && lon != null) lat to lon else null
            }
            if (geom.size < 2) null else tags to geom
        }

    fun parseAree(s: String): List<AreaPC> = ways(s).map { (t, g) ->
        val tipo = when {
            t["emergency"] == "camp_area" || t["emergency:camp_area"] == "yes" -> AreaPC.Tipo.RICOVERO
            t["emergency"] == "staging_area" || t.containsKey("emergency:staging_area") -> AreaPC.Tipo.AMMASSAMENTO
            else -> AreaPC.Tipo.ATTESA
        }
        AreaPC(tipo, t["name"] ?: t["ref"], g)
    }

    fun parseCorsiAcqua(s: String): List<CorsoAcqua> = ways(s).map { (t, g) -> CorsoAcqua(t["name"] ?: t["ref"], t["waterway"].orEmpty(), g) }

    /** Confine comunale (Nominatim, relazione 45549), disegnato tratteggiato come nella PWA. */
    suspend fun confineRaw(): String = withContext(Dispatchers.IO) {
        getText("https://nominatim.openstreetmap.org/lookup?osm_type=R&osm_ids=45549&format=json&polygon_geojson=1")
    }

    fun parseConfine(s: String): List<List<Pair<Double, Double>>> {
        val geo = json.parseToJsonElement(s).jsonArray.firstOrNull()?.jsonObject?.get("geojson") ?: throw IOException("Confine non trovato")
        val forme = mutableListOf<List<Pair<Double, Double>>>()
        collectGeometry(geo, forme, mutableListOf())
        return forme
    }

    // ── Radar DPC (VMI): stessa sorgente e palette della PWA ─────────────────

    /** Istante (ms UTC) dell'ultima mappa radar disponibile. */
    suspend fun radarLast(): Long = withContext(Dispatchers.IO) {
        val o = json.parseToJsonElement(getText("https://radar-api.protezionecivile.it/findLastProductByType?type=VMI")).jsonObject
        o["lastProducts"]?.jsonArray?.firstOrNull()?.jsonObject?.get("time")?.jsonPrimitive?.contentOrNull?.toLongOrNull()
            ?: throw IOException("Nessun prodotto radar")
    }

    suspend fun radarTile(time: Long, z: Int, x: Int, y: Int): ByteArray? = withContext(Dispatchers.IO) {
        val d = java.time.Instant.ofEpochMilli(time).atZone(ZoneOffset.UTC)
        val path = "%04d/%02d/%02d/%02d%02d".format(d.year, d.monthValue, d.dayOfMonth, d.hour, d.minute)
        runCatching {
            client.newCall(Request.Builder().url("https://s3-prod-dpc-radar-webp-cache.s3.eu-south-1.amazonaws.com/VMI/$path/$z/$x/$y/vmi.webp").build())
                .execute().use { r -> if (r.isSuccessful) r.body!!.bytes() else null }
        }.getOrNull()
    }

    /** "2026-10-17T16:45:20.786683" → ora locale. */
    fun parseIso(s: String?): LocalDateTime? = runCatching { LocalDateTime.parse(s) }.getOrNull()
        ?: runCatching { OffsetDateTime.parse(s).atZoneSameInstant(ZoneId.systemDefault()).toLocalDateTime() }.getOrNull()
}
