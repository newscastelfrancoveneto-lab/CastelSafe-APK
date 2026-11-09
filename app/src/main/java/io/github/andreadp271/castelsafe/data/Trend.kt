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

    /** Variazione oraria dell'accumulo 24 h sotto la quale è "stabile" (mm: la risoluzione del pluviometro). */
    private const val ACCUMULO_STABILE = 0.2

    data class Salita(val delta: Double, val unita: String)

    /**
     * Verso dell'ultima ora per il sensore [tipo] della stazione. L'accumulo 24 h non è una serie di ARPAV: si ricava
     * da PREC, come differenza tra la pioggia dell'ultima ora e quella che esce dalla finestra delle 24 ore.
     */
    fun direzione(serie: List<Serie>, tipo: String): Int? {
        if (tipo != "PRECCUM24") return serie.firstOrNull { it.type == tipo }?.let { direzione(it) }
        val prec = serie.firstOrNull { it.type == "PREC" } ?: return null
        val last = prec.ultimo?.solare ?: return null
        if ((prec.punti.firstOrNull()?.solare ?: return null) > last.minus(Duration.ofHours(24))) return null  // storico troppo corto
        fun pioggia(da: Duration, a: Duration) = prec.punti.filter { it.solare > last.minus(da) && it.solare <= last.minus(a) }.sumOf { it.valore ?: 0.0 }
        val delta = pioggia(Duration.ofHours(1), Duration.ZERO) - pioggia(Duration.ofHours(25), Duration.ofHours(24))
        return when { delta >= ACCUMULO_STABILE -> 1; delta <= -ACCUMULO_STABILE -> -1; else -> 0 }
    }

    /** Verso dell'ultima ora: 1 sale, -1 scende, 0 stabile; null se il sensore non ha soglia o manca il dato di un'ora prima. */
    fun direzione(s: Serie): Int? {
        val stabile = (salitaOraria[s.type] ?: return null) / 5   // sotto un quinto della salita rapida è "stabile"
        val last = s.ultimo ?: return null
        val oraPrima = last.solare.minus(Duration.ofMinutes(60))
        val prima = s.punti.lastOrNull { it.solare <= oraPrima && it.valore != null }
            ?.takeIf { Duration.between(it.solare, oraPrima).toMinutes() <= 30 } ?: return null
        val delta = last.valore!! - prima.valore!!
        return when { delta >= stabile -> 1; delta <= -stabile -> -1; else -> 0 }
    }

    /** Sensori in rapida salita, con l'aumento dell'ultima ora. */
    fun rapide(serie: List<Serie>): Map<String, Salita> {
        val out = mutableMapOf<String, Salita>()
        for (s in serie) {
            val last = s.ultimo ?: continue
            val v = last.valore ?: continue
            val oraPrima = last.solare.minus(Duration.ofMinutes(60))
            if (s.type == "PREC") {
                val somma = s.punti.filter { it.solare > oraPrima && it.solare <= last.solare }.sumOf { it.valore ?: 0.0 }
                if (somma >= PIOGGIA_ORARIA) out[s.type] = Salita(somma, s.unita)
                continue
            }
            val soglia = salitaOraria[s.type] ?: continue
            // il dato più vicino a un'ora prima (ARPAV pubblica ogni 10-30 minuti)
            val prima = s.punti.lastOrNull { it.solare <= oraPrima && it.valore != null }
                ?.takeIf { Duration.between(it.solare, oraPrima).toMinutes() <= 30 } ?: continue
            val delta = v - prima.valore!!
            if (delta >= soglia) out[s.type] = Salita(delta, s.unita)
        }
        return out
    }
}
