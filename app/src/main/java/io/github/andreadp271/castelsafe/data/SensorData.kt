package io.github.andreadp271.castelsafe.data

import java.time.Duration
import java.time.LocalDateTime

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
                    out += Scheda("VENTO", "Vento", v.unita, v, d.punti.filter { it.valore != null }.associate { it.istante to it.valore!! })
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

    /** Mm per intervallo; se la serie sembra cumulata la trasforma in incrementi. */
    fun bars(values: List<Double?>): List<Double> {
        val v = values.map { it ?: 0.0 }
        val decreases = (1 until v.size).count { v[it] < v[it - 1] }
        val cumulative = decreases <= maxOf(2, v.size / 10)
        return v.mapIndexed { i, cur ->
            when {
                !cumulative -> cur
                i == 0 -> 0.0
                else -> if (cur >= v[i - 1]) cur - v[i - 1] else cur
            }
        }.map { maxOf(0.0, Math.round(it * 1000) / 1000.0) }
    }

    private fun stepMs(t: List<LocalDateTime>): Long =
        (1 until t.size).map { Duration.between(t[it - 1], t[it]).toMillis() }.firstOrNull { it > 0 } ?: 30 * 60_000L

    /** Somma mobile delle ultime 24 ore. */
    fun rolling24h(t: List<LocalDateTime>, bars: List<Double>): List<Double> {
        val window = 24 * 3600_000L
        val out = ArrayList<Double>(bars.size)
        var start = 0; var sum = 0.0
        for (i in bars.indices) {
            sum += bars[i]
            while (start <= i && Duration.between(t[start], t[i]).toMillis() > window) { sum -= bars[start]; start++ }
            out += Math.round(sum * 1000) / 1000.0
        }
        return out
    }

    private fun dryingLossMm(stepHours: Double, temp: Double?, rad: Double?, hum: Double?): Double {
        val h = maxOf(0.0, stepHours)
        val humFactor = hum?.let { maxOf(0.0, 1 - it.coerceIn(0.0, 100.0) / 100) } ?: 1.0
        val rate = when {
            temp != null && rad != null -> maxOf(0.0, (temp - 4) * 0.015 + rad * 0.00025)
            rad != null -> maxOf(0.0, rad * 0.00035)
            temp != null -> maxOf(0.0, (temp - 4) * 0.03)
            else -> 0.0
        }
        return maxOf(0.0, rate * humFactor * h)
    }

    /** Accumulo 24h corretto per l'evaporazione (temperatura, radiazione, umidità), come la PWA. */
    fun dryingAdjusted24h(t: List<LocalDateTime>, bars: List<Double>, temp: List<Double?>, rad: List<Double?>, hum: List<Double?>): List<Double>? {
        if (temp.all { it == null } && rad.all { it == null }) return null
        val window = 24 * 3600_000L
        val step = stepMs(t)
        data class Q(val date: LocalDateTime, var remaining: Double)
        val queue = ArrayDeque<Q>()
        var sum = 0.0
        val out = ArrayList<Double>(bars.size)
        for (i in bars.indices) {
            val ms = if (i > 0 && t[i].isAfter(t[i - 1])) Duration.between(t[i - 1], t[i]).toMillis() else step
            var loss = dryingLossMm(ms / 3600_000.0, temp.getOrNull(i), rad.getOrNull(i), hum.getOrNull(i))
            if (bars[i] > 0) { queue.addLast(Q(t[i], bars[i])); sum += bars[i] }
            for (q in queue) {
                if (loss <= 0) break
                if (q.remaining <= 0) continue
                val c = minOf(q.remaining, loss)
                q.remaining -= c; sum = maxOf(0.0, sum - c); loss -= c
            }
            while (queue.isNotEmpty() && Duration.between(queue.first().date, t[i]).toMillis() > window) {
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
        val m = s.punti.associate { it.istante to it.valore }
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
