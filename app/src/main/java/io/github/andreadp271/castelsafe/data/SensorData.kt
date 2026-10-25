package io.github.andreadp271.castelsafe.data

import java.time.Duration
import java.time.LocalDateTime
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Elaborazioni dei dati ARPAV identiche alla PWA (index.html): ordine delle schede,
 * vento combinato velocità+direzione, barre di pioggia e accumulo 24h (classico o
 * corretto per l'evaporazione), statistiche.
 */

/** Una scheda della stazione: un sensore, oppure il vento combinato. */
data class Scheda(
    val type: String,
    val label: String,
    val unita: String,
    val serie: Serie,
    /** Solo per il vento combinato: direzione (gradi) per istante. */
    val direzione: Map<LocalDateTime, Double>? = null,
) {
    val isVento get() = direzione != null
    val isPioggia get() = (type + " " + serie.nome).lowercase().contains("precip")
}

object SensorData {
    private val tabLabels = mapOf(
        "LIVIDRO" to "Livello idrometrico", "PRECCUM" to "Precipitazioni", "PORTATA" to "Portata", "VENTO" to "Vento",
        "TARIA" to "Temperatura", "UREL" to "Umidità rel.", "RADGLOB" to "Radiazione",
    )

    /** Schede nell'ordine della PWA: pioggia, vento, poi gli altri (ad Asolo prima il livello). */
    fun schede(stationId: String, serie: List<Serie>): List<Scheda> {
        val usable = serie.filter { s -> s.punti.any { it.valore != null } }
        val vel = usable.indexOfFirst { it.nome.lowercase().let { n -> "velocit" in n && "vento" in n } || it.type == "VVENTO" }
        val dir = usable.indexOfFirst { it.nome.lowercase().let { n -> ("direzion" in n || "dir" in n) && "vento" in n } || it.type == "DVENTO" }
        val out = mutableListOf<Scheda>()
        usable.forEachIndexed { i, s ->
            when {
                vel >= 0 && dir >= 0 && i == minOf(vel, dir) -> {
                    val v = usable[vel]; val d = usable[dir]
                    out += Scheda("VENTO", "Vento", v.unita, v, d.punti.filter { it.valore != null }.associate { it.solare to it.valore!! })
                }
                vel >= 0 && dir >= 0 && (i == vel || i == dir) -> {}
                else -> out += Scheda(s.type, tabLabels[s.type] ?: s.nome.ifBlank { Stations.label(s.type) }, s.unita, s)
            }
        }
        val lividroFirst = stationId.trimStart('0') == "554"
        fun pri(s: Scheda): Int = if (lividroFirst) when {
            s.type == "LIVIDRO" -> 0; s.isPioggia -> 1; s.isVento -> 2; else -> 3
        } else when {
            s.isPioggia -> 0; s.isVento -> 1; else -> 2
        }
        return out.sortedBy { pri(it) }
    }

    /** Pioggia "speciale" (barre + accumulo) per le stazioni di Castelfranco e del Muson, come la PWA. */
    fun pioggiaSpeciale(stationId: String, s: Scheda) = s.isPioggia && stationId.trimStart('0') in setOf("292", "554", "102")

    /** Mm per intervallo: ARPAV (PREC) li dà già così, non come totale progressivo. */
    fun bars(values: List<Double?>): List<Double> =
        values.map { maxOf(0.0, Math.round((it ?: 0.0) * 1000) / 1000.0) }

    private fun stepMs(t: List<LocalDateTime>): Long =
        (1 until t.size).map { Duration.between(t[it - 1], t[it]).toMillis() }.firstOrNull { it > 0 } ?: 30 * 60_000L

    /** Somma mobile delle ultime 24 ore (ogni valore copre l'intervallo che finisce al suo istante: 48 valori da 30'). */
    fun rolling24h(t: List<LocalDateTime>, bars: List<Double>): List<Double> {
        val window = 24 * 3600_000L
        val out = ArrayList<Double>(bars.size)
        var start = 0; var sum = 0.0
        for (i in bars.indices) {
            sum += bars[i]
            while (start <= i && Duration.between(t[start], t[i]).toMillis() >= window) { sum -= bars[start]; start++ }
            out += Math.round(sum * 1000) / 1000.0
        }
        return out
    }

    // stazione meteo di Castelfranco (102), l'unica con i sensori per l'evaporazione
    private const val LAT = 45.6949 * PI / 180
    private const val LON = 11.9478
    private const val QUOTA = 49.0

    /**
     * Evapotraspirazione di riferimento FAO-56 Penman-Monteith (forma oraria ASCE) in mm nel passo
     * che finisce a [fine] (ora solare ARPAV, UTC+1). Senza temperatura, umidità o radiazione: 0.
     * Il vento è a 10 m; se manca si usa il valore tipico FAO di 2 m/s a 2 m.
     */
    fun et0Mm(fine: LocalDateTime, ore: Double, t: Double?, rh: Double?, rsW: Double?, u10: Double?): Double {
        if (t == null || rh == null || rsW == null || ore <= 0) return 0.0
        val es = 0.6108 * exp(17.27 * t / (t + 237.3))
        val ea = es * rh.coerceIn(0.0, 100.0) / 100
        val delta = 4098 * es / (t + 237.3).pow(2)
        val gamma = 0.000665 * 101.3 * ((293 - 0.0065 * QUOTA) / 293).pow(5.26)
        val u2 = u10?.let { maxOf(0.0, it) * 4.87 / ln(67.8 * 10 - 5.42) } ?: 2.0
        val rs = maxOf(0.0, rsW) * 0.0036 * ore                       // MJ/m² nel passo
        // radiazione extraterrestre nel passo (FAO-56 eq. 28), a metà intervallo
        val mezzo = fine.minusSeconds((ore * 1800).toLong())
        val j = mezzo.dayOfYear
        val b = 2 * PI * (j - 81) / 364
        val sc = 0.1645 * sin(2 * b) - 0.1255 * cos(b) - 0.025 * sin(b)
        val w = PI / 12 * (mezzo.hour + mezzo.minute / 60.0 + 0.06667 * (15 - LON) + sc - 12)
        val w1 = w - PI * ore / 24; val w2 = w + PI * ore / 24
        val dr = 1 + 0.033 * cos(2 * PI * j / 365)
        val dec = 0.409 * sin(2 * PI * j / 365 - 1.39)
        val ra = maxOf(0.0, 12 * 60 / PI * 0.082 * dr * ((w2 - w1) * sin(LAT) * sin(dec) + cos(LAT) * cos(dec) * (sin(w2) - sin(w1))))
        val rso = (0.75 + 2e-5 * QUOTA) * ra
        // copertura del cielo da radiazione misurata / attesa a cielo sereno; di notte un valore tipico
        val cielo = if (rso > 0.05) (rs / rso).coerceIn(0.25, 1.0) else 0.8
        val rnl = 4.903e-9 / 24 * ore * (t + 273.16).pow(4) * (0.34 - 0.14 * sqrt(ea)) * (1.35 * cielo - 0.35)
        val rn = 0.77 * rs - rnl
        val giorno = rn > 0
        val g = (if (giorno) 0.1 else 0.5) * rn
        val cd = if (giorno) 0.24 else 0.96
        val et = (0.408 * delta * (rn - g) + gamma * 37 / (t + 273) * u2 * (es - ea) * ore) / (delta + gamma * (1 + cd * u2))
        return maxOf(0.0, et)
    }

    /** Accumulo 24h corretto per l'evaporazione (FAO-56: temperatura, umidità, radiazione, vento), come la PWA. */
    fun dryingAdjusted24h(t: List<LocalDateTime>, bars: List<Double>, temp: List<Double?>, rad: List<Double?>, hum: List<Double?>, wind: List<Double?>): List<Double>? {
        if (temp.all { it == null } || rad.all { it == null } || hum.all { it == null }) return null
        val window = 24 * 3600_000L
        val step = stepMs(t)
        data class Q(val date: LocalDateTime, var remaining: Double)
        val queue = ArrayDeque<Q>()
        var sum = 0.0
        val out = ArrayList<Double>(bars.size)
        for (i in bars.indices) {
            val ms = if (i > 0 && t[i].isAfter(t[i - 1])) Duration.between(t[i - 1], t[i]).toMillis() else step
            var loss = et0Mm(t[i], ms / 3600_000.0, temp.getOrNull(i), hum.getOrNull(i), rad.getOrNull(i), wind.getOrNull(i))
            if (bars[i] > 0) { queue.addLast(Q(t[i], bars[i])); sum += bars[i] }
            for (q in queue) {
                if (loss <= 0) break
                if (q.remaining <= 0) continue
                val c = minOf(q.remaining, loss)
                q.remaining -= c; sum = maxOf(0.0, sum - c); loss -= c
            }
            while (queue.isNotEmpty() && Duration.between(queue.first().date, t[i]).toMillis() >= window) {
                sum = maxOf(0.0, sum - queue.removeFirst().remaining)
            }
            while (queue.isNotEmpty() && queue.first().remaining <= 0) queue.removeFirst()
            out += Math.round(maxOf(0.0, sum) * 1000) / 1000.0
        }
        return out
    }

    /** Serie di supporto allineata agli istanti della pioggia (null se manca il dato). */
    fun support(all: List<Serie>, t: List<LocalDateTime>, vararg match: String): List<Double?>? {
        val s = all.firstOrNull { se -> val txt = "${se.type} ${se.nome} ${se.unita}".lowercase(); match.any { it in txt } } ?: return null
        val m = s.punti.associate { it.solare to it.valore }
        return t.map { m[it] }
    }

    fun windDir(deg: Double?): String {
        if (deg == null) return "—"
        val dirs = listOf("N", "NNE", "NE", "ENE", "E", "ESE", "SE", "SSE", "S", "SSO", "SO", "OSO", "O", "ONO", "NO", "NNO")
        return dirs[(Math.round(deg / 22.5) % 16).toInt()] + " (" + Math.round(deg) + "°)"
    }

    /** Colore della serie per tipo di sensore (TYPE_COLOR della PWA). */
    fun colorFor(type: String, nome: String): Long {
        val n = nome.lowercase()
        return when {
            type == "VENTO" || type == "VVENTO" -> 0xFF38BDF8
            type == "LIVIDRO" -> 0xFF1D4ED8
            type.startsWith("PRECCUM") -> 0xFF2563EB
            type == "PORTATA" -> 0xFF075985
            type == "TARIA" || type == "TEMP" || "temp" in n -> 0xFF16A34A
            type == "UREL" || type == "UMID" || "umid" in n -> 0xFF1E3A8A
            type == "RADGLOB" || type == "RADSOL" || "radi" in n || "solar" in n -> 0xFFEAB308
            else -> 0xFF1D4ED8
        }
    }
}
