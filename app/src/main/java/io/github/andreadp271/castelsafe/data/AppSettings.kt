package io.github.andreadp271.castelsafe.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Preferenze generali dell'app (aspetto, avvio, privacy, aggiornamenti), come in Capotreno. */
object AppSettings {
    enum class Theme(val label: String) { SYSTEM("Sistema"), LIGHT("Chiaro"), DARK("Scuro") }
    enum class Palette(val label: String) { CASTELSAFE("CastelSafe"), DYNAMIC("Dallo sfondo") }

    val startTabs = listOf("home" to "Home", "sensori" to "Sensori", "mappa" to "Mappa", "avvisi" to "Avvisi")

    private lateinit var prefs: SharedPreferences
    private val _theme = MutableStateFlow(Theme.SYSTEM)
    val theme: StateFlow<Theme> = _theme
    private val _palette = MutableStateFlow(Palette.CASTELSAFE)
    val palette: StateFlow<Palette> = _palette
    private val _portrait = MutableStateFlow(true)
    val portrait: StateFlow<Boolean> = _portrait

    fun init(context: Context) {
        if (::prefs.isInitialized) return
        prefs = context.applicationContext.getSharedPreferences("app_settings", Context.MODE_PRIVATE)
        _theme.value = enumOr(prefs.getString("theme", null), Theme.SYSTEM)
        _palette.value = enumOr(prefs.getString("palette", null), Palette.CASTELSAFE)
        _portrait.value = prefs.getBoolean("portrait", true)
    }

    private inline fun <reified E : Enum<E>> enumOr(name: String?, default: E) =
        runCatching { enumValueOf<E>(name!!) }.getOrDefault(default)

    fun setTheme(t: Theme) { _theme.value = t; prefs.edit().putString("theme", t.name).apply() }
    fun setPalette(p: Palette) { _palette.value = p; prefs.edit().putString("palette", p.name).apply() }
    fun setPortrait(v: Boolean) { _portrait.value = v; prefs.edit().putBoolean("portrait", v).apply() }

    /** Scheda aperta all'avvio. */
    var startTab: String
        get() = prefs.getString("start_tab", "home")!!
        set(v) = prefs.edit().putString("start_tab", v).apply()

    /** Schermo sempre acceso mentre si guarda il grafico di un sensore (es. durante una piena). */
    var keepScreenOn: Boolean
        get() = prefs.getBoolean("keep_screen_on", false)
        set(v) = prefs.edit().putBoolean("keep_screen_on", v).apply()

    /** Radar precipitazioni già acceso quando si apre la mappa. */
    var radarAtStart: Boolean
        get() = prefs.getBoolean("radar_at_start", false)
        set(v) = prefs.edit().putBoolean("radar_at_start", v).apply()

    /** Statistiche anonime d'uso (stesso sistema della PWA). */
    var analytics: Boolean
        get() = prefs.getBoolean("analytics", true)
        set(v) = prefs.edit().putBoolean("analytics", v).apply()

    /** Controllo degli aggiornamenti su GitHub all'avvio (al massimo una volta al giorno). */
    var autoUpdateCheck: Boolean
        get() = prefs.getBoolean("auto_update_check", true)
        set(v) = prefs.edit().putBoolean("auto_update_check", v).apply()

    var lastUpdateCheck: Long
        get() = prefs.getLong("last_update_check", 0L)
        set(v) = prefs.edit().putLong("last_update_check", v).apply()
}
