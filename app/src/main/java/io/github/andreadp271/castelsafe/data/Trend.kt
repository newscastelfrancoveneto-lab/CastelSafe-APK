package io.github.andreadp271.castelsafe.data

import java.time.Duration

/**
 * Riconosce i sensori che stanno salendo in fretta, dallo storico ARPAV della stazione.
 * Serve solo a mettere in evidenza il dato nella Home: le notifiche restano decise dal server.
 */
object Trend {
    /** Aumento in un'ora oltre il quale il dato è "in rapida salita". */
    private val salitaOraria = mapOf(
        "LIVIDRO" to 0.15,   // m: un fiume che cresce di 15 cm in un'ora
        "PORTATA" to 10.0,   // m³/s
        "VVENTO" to 5.0,     // m/s
        "TEMP" to 5.0,       // °C
    )

    /** Pioggia caduta nell'ultima ora oltre la quale è "intensa" (PREC è il dato del singolo intervallo). */
    private const val PIOGGIA_ORARIA = 6.0

    data class Salita(val delta: Double, val unita: String)

    /** Sensori in rapida salita, con l'aumento dell'ultima ora. */
    fun rapide(serie: List<Serie>): Map<String, Salita> {
        val out = mutableMapOf<String, Salita>()
        for (s in serie) {
            val last = s.ultimo ?: continue
            val v = last.valore ?: continue
            val oraPrima = last.istante.minus(Duration.ofMinutes(60))
            if (s.type == "PREC") {
                val somma = s.punti.filter { it.istante > oraPrima && it.istante <= last.istante }.sumOf { it.valore ?: 0.0 }
                if (somma >= PIOGGIA_ORARIA) out[s.type] = Salita(somma, s.unita)
                continue
            }
            val soglia = salitaOraria[s.type] ?: continue
            // il dato più vicino a un'ora prima (ARPAV pubblica ogni 10-30 minuti)
            val prima = s.punti.lastOrNull { it.istante <= oraPrima && it.valore != null }
                ?.takeIf { Duration.between(it.istante, oraPrima).toMinutes() <= 30 } ?: continue
            val delta = v - prima.valore!!
            if (delta >= soglia) out[s.type] = Salita(delta, s.unita)
        }
        return out
    }
}
