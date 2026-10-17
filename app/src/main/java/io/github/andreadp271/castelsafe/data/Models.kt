package io.github.andreadp271.castelsafe.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

// ── Bollettino di allerta (/api/allerta) ─────────────────────────────────────

@Serializable
data class Allerta(
    val zona: String? = null,
    val aggiornamento: AllertaHeader? = null,
    val oggi: AllertaGiorno? = null,
    val domani: AllertaGiorno? = null,
    @SerialName("fenomeni_previsti") val fenomeni: String? = null,
    val tendenza: String? = null,
    val aggiornato: String? = null,
) {
    val maxLevel: Level get() = maxOf(oggi?.maxLevel ?: Level.NESSUNO, domani?.maxLevel ?: Level.NESSUNO)
}

@Serializable
data class AllertaHeader(
    @SerialName("num_boll") val numero: String? = null,
    @SerialName("data_compilazione") val data: String? = null,
    @SerialName("ora_compilazione") val ora: String? = null,
    val revisione: String? = null,
)

@Serializable
data class AllertaGiorno(
    val periodo: String? = null,
    val idra: String? = null,
    val idro: String? = null,
    val temp: String? = null,
    val vent: String? = null,
    val calo: String? = null,
) {
    /** Rischi nell'ordine della PWA: nome esteso e livello. */
    val rischi: List<Pair<String, Level>>
        get() = listOf(
            "Idraulico" to Level.parse(idra),
            "Idrogeologico" to Level.parse(idro),
            "Temporali" to Level.parse(temp),
            "Vento" to Level.parse(vent),
            "Ondate di calore" to Level.parse(calo),
        )

    val maxLevel: Level get() = rischi.maxOf { it.second }
}

// ── Sensori in tempo reale (/api/monitor/stato) ──────────────────────────────

@Serializable
data class Lettura(val valore: Double? = null, val unita: String = "", val istante: String? = null)

@Serializable
data class StatoStazione(
    val aggiornato: String? = null,
    val sensori: Map<String, Lettura> = emptyMap(),
    val stale: Boolean = false,
    @SerialName("eta_minuti") val etaMinuti: Int? = null,
)

// ── Storico di una stazione (XML ARPAV) ──────────────────────────────────────

data class Serie(val type: String, val nome: String, val unita: String, val punti: List<Punto>) {
    val ultimo: Punto? get() = punti.lastOrNull { it.valore != null }
}

data class Punto(val istante: LocalDateTime, val valore: Double?)

// ── Avvisi e chiusure (/api/closures) ────────────────────────────────────────

data class Chiusura(
    val id: String,
    val titolo: String,
    val descrizione: String,
    val aree: List<String>,
    val inizio: LocalDateTime?,
    val fine: LocalDateTime?,
    val inizioTesto: String,
    val fineTesto: String,
    /** Anelli/linee in coordinate (lat, lon). */
    val forme: List<List<Pair<Double, Double>>>,
    val punti: List<Pair<Double, Double>>,
) {
    enum class Stato { IN_CORSO, PROGRAMMATO, CONCLUSO }

    fun stato(now: LocalDateTime = LocalDateTime.now()): Stato = when {
        fine != null && fine.isBefore(now) -> Stato.CONCLUSO
        inizio != null && inizio.isAfter(now) -> Stato.PROGRAMMATO
        else -> Stato.IN_CORSO
    }

    val centro: Pair<Double, Double>?
        get() {
            val all = forme.flatten() + punti
            if (all.isEmpty()) return null
            return all.sumOf { it.first } / all.size to all.sumOf { it.second } / all.size
        }

    companion object {
        fun dataOra(d: String?, t: String?, default: LocalTime): LocalDateTime? = runCatching {
            if (d.isNullOrBlank()) return null
            LocalDateTime.of(LocalDate.parse(d), t?.takeIf { it.isNotBlank() }?.let { LocalTime.parse(it) } ?: default)
        }.getOrNull()
    }
}

// ── Previsioni meteo (bollettino ARPAV per zona) ─────────────────────────────

data class Meteo(
    val emissione: String,
    val aggiornamento: String,
    val scadenze: List<Scadenza>,
)

data class Scadenza(
    val etichetta: String,       // "sab 17 ottobre pomeriggio"
    val giorno: String,          // "sab 17 ottobre"
    val fascia: String?,         // mattina / pomeriggio / null
    val simbolo: String,         // URL icona ARPAV
    val cielo: String,
    val temperatura: String,
    val precipitazioni: String,
    val probabilita: String,
    val quotaNeve: String,
    val attendibilita: String,
)

// ── Aree di protezione civile e corsi d'acqua (OpenStreetMap) ────────────────

data class AreaPC(val tipo: Tipo, val nome: String?, val forma: List<Pair<Double, Double>>) {
    enum class Tipo(val label: String) { ATTESA("Area di attesa"), RICOVERO("Area di ricovero"), AMMASSAMENTO("Area di ammassamento") }

    val centro: Pair<Double, Double> get() = forma.sumOf { it.first } / forma.size to forma.sumOf { it.second } / forma.size
}

data class CorsoAcqua(val nome: String?, val tipo: String, val linea: List<Pair<Double, Double>>)

// ── Registro notifiche del server (/api/notifiche/feed) ─────────────────────

@Serializable
data class FeedResponse(
    @SerialName("ultimo_id") val ultimoId: Long = 0,
    val notifiche: List<FeedItem> = emptyList(),
)

@Serializable
data class FeedItem(
    val id: Long,
    val ts: Long,
    val title: String = "",
    val body: String = "",
    val tag: String = "",
    val url: String = "",
)
