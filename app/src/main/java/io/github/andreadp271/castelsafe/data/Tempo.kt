package io.github.andreadp271.castelsafe.data

import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * ARPAV pubblica in ora solare (UTC+1 fisso): non si ripete e non salta mai, quindi è il tempo
 * giusto per ordinare le misure e misurare intervalli. L'ora legale italiana serve solo a mostrarla.
 * Nei giorni del cambio d'ora le due differiscono: a ottobre un'ora legale si ripete.
 */
object Tempo {
    private val roma = ZoneId.of("Europe/Rome")
    private val solare = ZoneOffset.ofHours(1)

    /** Ora legale italiana di un istante dato in ora solare. */
    fun legale(s: LocalDateTime): LocalDateTime = s.atOffset(solare).atZoneSameInstant(roma).toLocalDateTime()

    /** Ora solare di un'ora legale (nell'ora ripetuta vale la prima occorrenza). */
    fun aSolare(l: LocalDateTime): LocalDateTime = l.atZone(roma).withZoneSameInstant(solare).toLocalDateTime()

    /** Adesso, in ora solare. */
    fun adesso(): LocalDateTime = LocalDateTime.now(solare)
}
