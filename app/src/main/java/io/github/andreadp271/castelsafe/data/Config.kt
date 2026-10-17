package io.github.andreadp271.castelsafe.data

/** Costanti condivise: server, stazioni ARPAV, soglie e contatti (allineati alla PWA). */
object Config {
    const val API_BASE = "https://andrea.tail04be23.ts.net"
    const val SITE_URL = "https://newscastelfrancoveneto-lab.github.io/CastelSafe/"
    const val GITHUB_REPO = "AndreaDp271/CastelSafe-Android"
    const val WEATHER_ZONE = "Treviso e pianura orientale"
    const val RADAR_URL = "https://radar.protezionecivile.it/"

    /** Centro di Castelfranco Veneto. */
    const val CENTER_LAT = 45.6717
    const val CENTER_LON = 11.9270

    /** Sede della Protezione Civile. */
    const val PC_LAT = 45.670167
    const val PC_LON = 11.953020
}

data class Soglie(val s1: Double, val s2: Double, val s3: Double)

data class Station(
    val id: String,          // "0292"
    val name: String,
    val subtitle: String,
    val lat: Double,
    val lon: Double,
    val arpavUrl: String,
    /** Soglie di guardia del livello idrometrico (null = nessuna). */
    val soglieLivello: Soglie?,
    /** Sensore da mostrare in evidenza nelle liste. */
    val main: String,
    /** Monitorata dal server (soglie e notifiche); le altre sono solo consultabili. */
    val monitored: Boolean,
)

object Stations {
    val all = listOf(
        Station(
            "0292", "Muson dei Sassi", "Castelfranco Veneto", 45.66983651, 11.92027581,
            "https://www.arpa.veneto.it/dati-ambientali/dati-in-diretta/meteo-idro-nivo/variabili_meteo?codseqst=300001731&focus=PREC&return=replace",
            Soglie(1.8, 2.5, 2.9), "LIVIDRO", true,
        ),
        Station(
            "0653", "Avenale", "Castelfranco Veneto", 45.6747453, 11.9285348,
            "https://www.arpa.veneto.it/dati-ambientali/dati-in-diretta/meteo-idro-nivo/variabili_idro?codseqst=300006480&focus=LIVIDRO&return=replace",
            Soglie(1.5, 1.8, 2.1), "LIVIDRO", true,
        ),
        Station(
            "0102", "Castelfranco Veneto", "Stazione meteorologica", 45.6949262, 11.9477799,
            "https://www.arpa.veneto.it/dati-ambientali/dati-in-diretta/meteo-idro-nivo/variabili_meteo?codseqst=300000047&focus=PREC&return=replace",
            null, "TEMP", true,
        ),
        Station(
            "0554", "Muson dei Sassi ad Asolo", "Asolo (a monte)", 45.78576616, 11.89466078,
            "https://www.arpa.veneto.it/dati-ambientali/dati-in-diretta/meteo-idro-nivo/variabili_idro?codseqst=300003340&focus=LIVIDRO&return=replace",
            null, "LIVIDRO", false,
        ),
    )

    fun byId(id: String) = all.firstOrNull { it.id == id || it.id.trimStart('0') == id.trimStart('0') }

    val sensorLabels = mapOf(
        "PREC" to "Precipitazione",
        "LIVIDRO" to "Livello idrometrico",
        "PORTATA" to "Portata",
        "VVENTO" to "Velocità vento",
        "DVENTO" to "Direzione vento",
        "TEMP" to "Temperatura aria",
        "TARIA" to "Temperatura aria",
        "UMID" to "Umidità relativa",
        "UREL" to "Umidità relativa",
        "RADSOL" to "Radiazione solare",
        "RADGLOB" to "Radiazione solare",
        "PRECCUM24" to "Pioggia ultime 24h",
        "PRECCUM24_DRY" to "Pioggia 24h corretta",
    )

    fun label(type: String) = sensorLabels[type] ?: type

    /**
     * Soglie usate dal server per le notifiche (SENSOR_RULES, solo quelle ">="):
     * qui servono a colorare i valori, la decisione di notificare resta del server.
     */
    private val rules: Map<String, Map<String, Soglie>> = mapOf(
        "0292" to mapOf(
            "PREC" to Soglie(50.0, 70.0, 100.0),
            "LIVIDRO" to Soglie(1.80, 2.50, 2.90),
            "PORTATA" to Soglie(50.0, 70.0, 100.0),
            "PRECCUM24" to Soglie(50.0, 100.0, 150.0),
            "PRECCUM24_DRY" to Soglie(50.0, 100.0, 150.0),
        ),
        "0653" to mapOf("LIVIDRO" to Soglie(1.50, 1.80, 2.10)),
        "0102" to mapOf(
            "PREC" to Soglie(50.0, 70.0, 100.0),
            "VVENTO" to Soglie(11.0, 15.0, 20.0),
            "TEMP" to Soglie(33.0, 36.0, 39.0),
            "RADSOL" to Soglie(1500.0, 2000.0, 2500.0),
            "PRECCUM24" to Soglie(50.0, 100.0, 150.0),
            "PRECCUM24_DRY" to Soglie(50.0, 100.0, 150.0),
        ),
    )

    fun soglie(stationId: String, type: String): Soglie? = rules[stationId]?.get(type)

    fun level(stationId: String, type: String, value: Double?): Level {
        val s = soglie(stationId, type) ?: return Level.NESSUNO
        if (value == null) return Level.NESSUNO
        return when {
            value >= s.s3 -> Level.ROSSO
            value >= s.s2 -> Level.ARANCIONE
            value >= s.s1 -> Level.GIALLO
            else -> Level.VERDE
        }
    }
}

/** Livelli di allerta/soglia, in ordine di gravità. */
enum class Level(val label: String) {
    NESSUNO(""), VERDE("Verde"), GIALLO("Gialla"), ARANCIONE("Arancione"), ROSSO("Rossa");

    companion object {
        fun parse(s: String?): Level = when (s?.trim()?.uppercase()) {
            "VERDE" -> VERDE
            "GIALLO", "GIALLA" -> GIALLO
            "ARANCIONE" -> ARANCIONE
            "ROSSO", "ROSSA" -> ROSSO
            else -> NESSUNO
        }
    }
}

data class Contact(val name: String, val note: String?, val display: String, val tel: String)

object Contacts {
    val national = listOf(
        Contact("Carabinieri", null, "112", "112"),
        Contact("Polizia di Stato", null, "113", "113"),
        Contact("Vigili del Fuoco", null, "115", "115"),
        Contact("Guardia di Finanza", null, "117", "117"),
    )
    val local = listOf(
        Contact("Polizia locale", null, "0423 735 536", "+390423735536"),
        Contact("Protezione civile locale", "In emergenza", "329 299 3682", "+393292993682"),
        Contact("Protezione civile locale", "In orario d'ufficio", "0423 735 727", "+390423735727"),
        Contact("Protezione civile locale", "Fuori orario d'ufficio", "348 355 1489", "+393483551489"),
        Contact("Protezione civile regionale", null, "800 99 00 09", "800990009"),
    )
}
