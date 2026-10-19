package io.github.andreadp271.castelsafe.data

import android.content.Context
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** Suoni dell'allarme sonoro. I primi tre sono sintetizzati dall'app, l'ultimo è la sveglia del telefono. */
enum class SuonoAllarme(val label: String, val descrizione: String) {
    SIRENA("Sirena", "Tono che sale e scende, come la sirena di protezione civile"),
    DUE_TONI("Due toni", "Alternato acuto/grave, come i mezzi di soccorso"),
    BIP("Bip bip", "Due bip secchi e ravvicinati, poi una pausa: il più penetrante"),
    SVEGLIA("Sveglia del telefono", "La suoneria della sveglia scelta in Android"),
}

/** Genera (una volta) i file audio delle sirene, in cicli interi così il loop non ha scatti. */
object Siren {
    private const val RATE = 44_100

    fun file(ctx: Context, s: SuonoAllarme): File? {
        if (s == SuonoAllarme.SVEGLIA) return null
        val f = File(ctx.cacheDir, "allarme_${s.name.lowercase()}_v1.wav")
        if (f.length() > 0) return f
        val samples = when (s) {
            SuonoAllarme.SIRENA -> sirena()
            SuonoAllarme.DUE_TONI -> dueToni()
            SuonoAllarme.BIP -> bip()
            SuonoAllarme.SVEGLIA -> return null
        }
        val tmp = File(f.path + ".tmp")
        tmp.writeBytes(wav(samples))
        tmp.renameTo(f)
        return f
    }

    /** Ululato: frequenza che sale da 500 a 1250 Hz e ridiscende, ciclo di 2,4 s (due cicli). */
    private fun sirena(): ShortArray {
        val period = 2.4
        val n = (RATE * period * 2).toInt()
        var phase = 0.0
        return ShortArray(n) { i ->
            val t = i.toDouble() / RATE
            val f = 500 + 750 * (0.5 - 0.5 * cos(2 * PI * t / period))
            phase += 2 * PI * f / RATE
            // fondamentale + armoniche dispari: timbro più "metallico" e udibile dal piccolo altoparlante
            val v = sin(phase) + 0.35 * sin(3 * phase) + 0.15 * sin(5 * phase)
            (v / 1.5 * 0.95 * Short.MAX_VALUE).toInt().toShort()
        }
    }

    /** Bitonale: 960 Hz e 770 Hz alternati ogni 0,45 s (quattro alternanze). */
    private fun dueToni(): ShortArray {
        val half = (RATE * 0.45).toInt()
        val n = half * 4
        var phase = 0.0
        return ShortArray(n) { i ->
            val f = if ((i / half) % 2 == 0) 960.0 else 770.0
            phase += 2 * PI * f / RATE
            val v = sin(phase) + 0.33 * sin(3 * phase) + 0.2 * sin(5 * phase)
            // brevi rampe al cambio di tono per evitare il "click"
            val k = i % half
            val ramp = minOf(1.0, k / 220.0, (half - k) / 220.0)
            (v / 1.53 * 0.95 * ramp * Short.MAX_VALUE).toInt().toShort()
        }
    }

    /** Bip bip: due beep a 2,4 kHz da 0,12 s, distanziati di 0,09 s, poi pausa; ciclo di 1,2 s (due cicli). */
    private fun bip(): ShortArray {
        val ciclo = (RATE * 1.2).toInt()
        val durata = (RATE * 0.12).toInt()
        val stacco = (RATE * 0.09).toInt()
        var phase = 0.0
        return ShortArray(ciclo * 2) { i ->
            val k = i % ciclo
            val dentro = k % (durata + stacco)
            val attivo = k < 2 * (durata + stacco) && dentro < durata
            if (!attivo) { 0 } else {
                phase += 2 * PI * 2400.0 / RATE
                val v = sin(phase) + 0.3 * sin(3 * phase)
                // rampe di 3 ms: niente "click" all'inizio e alla fine di ogni bip
                val ramp = minOf(1.0, dentro / 130.0, (durata - dentro) / 130.0)
                (v / 1.3 * 0.95 * ramp * Short.MAX_VALUE).toInt().toShort()
            }.toShort()
        }
    }

    private fun wav(pcm: ShortArray): ByteArray {
        val data = pcm.size * 2
        val b = ByteBuffer.allocate(44 + data).order(ByteOrder.LITTLE_ENDIAN)
        b.put("RIFF".toByteArray()); b.putInt(36 + data); b.put("WAVE".toByteArray())
        b.put("fmt ".toByteArray()); b.putInt(16); b.putShort(1); b.putShort(1)
        b.putInt(RATE); b.putInt(RATE * 2); b.putShort(2); b.putShort(16)
        b.put("data".toByteArray()); b.putInt(data)
        pcm.forEach { b.putShort(it) }
        return b.array()
    }
}
