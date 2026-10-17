package io.github.andreadp271.castelsafe.ui

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.res.painterResource
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import io.github.andreadp271.castelsafe.R
import io.github.andreadp271.castelsafe.data.Chiusura
import io.github.andreadp271.castelsafe.data.Repo

/** Azioni di navigazione usate dalle schermate. */
class Nav(val c: NavHostController) {
    fun back() { if (!c.popBackStack()) tab("home") }
    fun go(route: String) { c.navigate(route) { launchSingleTop = true } }

    /** Cambio di scheda: come in Capotreno, ogni scheda conserva la propria pila. */
    fun tab(route: String) {
        c.navigate(route) {
            popUpTo(c.graph.findStartDestination().id) { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
    }

    /** Apre la mappa centrata su un punto. */
    fun showOnMap(lat: Double, lon: Double) {
        MapFocus.target.value = MapFocus.Target(lat, lon)
        tab("mappa")
    }
}

private data class Tab(val route: String, val label: String, val icon: @Composable () -> Painter)

private val tabs = listOf(
    Tab("home", "Home") { rememberVectorPainter(Icons.Filled.Home) },
    Tab("sensori", "Sensori") { painterResource(R.drawable.ic_tab_sensori) },
    Tab("mappa", "Mappa") { painterResource(R.drawable.ic_tab_mappa) },
    Tab("avvisi", "Avvisi") { rememberVectorPainter(Icons.Filled.Warning) },
    Tab("altro", "Altro") { rememberVectorPainter(Icons.Filled.Menu) },
)

private val topLevel = setOf("home", "sensori", "mappa", "avvisi", "altro")

/** Scheda "naturale" di una schermata aperta da una notifica. */
private fun tabOf(route: String): String = when {
    route.startsWith("stazione") -> "sensori"
    route in setOf("notifiche", "info", "aggiornamenti") -> "altro"
    route in topLevel -> route
    else -> "home"
}

@Composable
fun App(pendingRoute: String?, onRouteHandled: () -> Unit) {
    val controller = rememberNavController()
    val nav = Nav(controller)
    val entry by controller.currentBackStackEntryAsState()
    // la scheda evidenziata resta quella da cui si è partiti (es. il meteo aperto dalla mappa)
    var current by rememberSaveable { mutableStateOf("home") }
    LaunchedEffect(entry) { entry?.destination?.route?.takeIf { it in topLevel }?.let { current = it } }
    val chiusure by Repo.chiusure.collectAsState()
    val attivi = chiusure.data.orEmpty().count { it.stato() == Chiusura.Stato.IN_CORSO }

    LaunchedEffect(pendingRoute) {
        val r = pendingRoute ?: return@LaunchedEffect
        when {
            r.startsWith("stazione/") -> { nav.tab("sensori"); nav.go(r) }
            r in topLevel -> nav.tab(r)
            else -> { nav.tab(tabOf(r)); nav.go(r) }
        }
        onRouteHandled()
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        bottomBar = {
            NavigationBar {
                tabs.forEach { t ->
                    NavigationBarItem(
                        selected = current == t.route,
                        onClick = { if (current == t.route) controller.popBackStack(t.route, false) else nav.tab(t.route) },
                        icon = {
                            if (t.route == "avvisi" && attivi > 0) BadgedBox(badge = { Badge { Text("$attivi") } }) { Icon(t.icon(), null) }
                            else Icon(t.icon(), null)
                        },
                        label = { Text(t.label) },
                    )
                }
            }
        },
    ) { inner ->
        NavHost(controller, startDestination = "home", modifier = Modifier.fillMaxSize().padding(inner).consumeWindowInsets(inner)) {
            composable("home") { HomeScreen(nav) }
            composable("allerta") { AllertaScreen(nav) }
            composable("guida") { GuidaScreen(nav) }
            composable("sensori") { SensoriScreen(nav) }
            composable("stazione/{id}") { StazioneScreen(nav, it.arguments?.getString("id").orEmpty()) }
            composable("mappa") { MapScreen(nav) }
            composable("avvisi") { AvvisiScreen(nav) }
            composable("altro") { AltroScreen(nav) }
            composable("meteo") { MeteoScreen(nav) }
            composable("contatti") { ContattiScreen(nav) }
            composable("notifiche") { NotificheScreen(nav) }
            composable("info") { InfoScreen(nav) }
            composable("aggiornamenti") { UpdateScreen(nav) }
        }
    }
}
