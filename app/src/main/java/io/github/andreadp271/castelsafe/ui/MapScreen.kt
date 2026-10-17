package io.github.andreadp271.castelsafe.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas as ACanvas
import android.graphics.Paint
import android.graphics.drawable.BitmapDrawable
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import io.github.andreadp271.castelsafe.R
import io.github.andreadp271.castelsafe.data.AreaPC
import io.github.andreadp271.castelsafe.data.Chiusura
import io.github.andreadp271.castelsafe.data.Config
import io.github.andreadp271.castelsafe.data.Repo
import io.github.andreadp271.castelsafe.data.Stations
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Overlay
import org.osmdroid.views.overlay.Polygon
import org.osmdroid.views.overlay.Polyline
import org.osmdroid.views.overlay.mylocation.GpsMyLocationProvider
import org.osmdroid.views.overlay.mylocation.MyLocationNewOverlay
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Punto su cui centrare la mappa e ultima vista, conservata tra le schede. */
object MapFocus {
    data class Target(val lat: Double, val lon: Double)
    val target = MutableStateFlow<Target?>(null)
    var lastCenter: GeoPoint? = null
    var lastZoom: Double = 14.0
    val layers = MutableStateFlow(Layer.entries.toSet())
    /** Radar precipitazioni attivo (si accende anche dal meteo). */
    val radar = MutableStateFlow(false)
    /** L'impostazione "Radar all'apertura" si applica una volta per sessione. */
    var radarDefaultApplied = false

    /** MapView conservata tra un'apertura e l'altra della scheda: tornare alla mappa è immediato. */
    internal var view: MapView? = null
    internal var viewOwner: Context? = null
    /** Dati con cui sono stati disegnati i livelli: se non cambiano non si ridisegna nulla. */
    internal var builtFrom: List<Any?>? = null

    fun release() { view?.onDetach(); view = null; viewOwner = null; builtFrom = null; myLocationOverlay = null }
}

private val popupState = MutableStateFlow<Popup?>(null)
private var myLocationOverlay: MyLocationNewOverlay? = null
private val radarOverlay = RadarOverlay()

/** Livelli del menu, nelle stesse sezioni della PWA. */
enum class Layer(val section: String, val label: String, val swatch: Color, val area: Boolean = false) {
    CORSI("Idrografia", "Corsi d'acqua", Color(0xFF2563EB)),
    ATTESA("Aree Protezione Civile", "Attesa", Color(0xFF166534), true),
    RICOVERO("Aree Protezione Civile", "Ricovero", Color(0xFF991B1B), true),
    AMMASSAMENTO("Aree Protezione Civile", "Ammassamento", Color(0xFFA16207), true),
    AVVISI("Avvisi e Chiusure", "Strade/aree chiuse", Color(0xFFDC2626)),
    SEDE_PC("Presidi di Emergenza", "Sede Protezione Civile", Color(0xFFFF6600)),
}

/** Contenuto del popup dell'elemento toccato (come i popup Leaflet della PWA). */
private sealed interface Popup {
    data class Area(val a: AreaPC) : Popup
    data object SedePC : Popup
    data class Avviso(val c: Chiusura) : Popup
    data class Corso(val testo: String) : Popup
}

private val waterwayStyle = mapOf(
    "river" to (0xFF2563EB.toInt() to 4f), "stream" to (0xFF3B82F6.toInt() to 2.5f), "canal" to (0xFF0EA5E9.toInt() to 3f),
    "drain" to (0xFF38BDF8.toInt() to 2f), "ditch" to (0xFF7DD3FC.toInt() to 1.5f),
)
private val waterwayLabel = mapOf("river" to "Fiume", "stream" to "Torrente/Rio", "canal" to "Canale", "drain" to "Scolo", "ditch" to "Fosso")

@Composable
fun MapScreen(nav: Nav) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val aree by Repo.aree.collectAsState()
    val chiusure by Repo.chiusure.collectAsState()
    val corsi by Repo.corsiAcqua.collectAsState()
    val confine by Repo.confine.collectAsState()
    val meteo by Repo.meteo.collectAsState()
    val layers by MapFocus.layers.collectAsState()
    val target by MapFocus.target.collectAsState()
    val radarOn by MapFocus.radar.collectAsState()
    val popup by popupState.collectAsState()
    var menuOpen by remember { mutableStateOf(false) }
    val density = ctx.resources.displayMetrics.density

    LaunchedEffect(Unit) {
        if (!MapFocus.radarDefaultApplied) {
            MapFocus.radarDefaultApplied = true
            if (io.github.andreadp271.castelsafe.data.AppSettings.radarAtStart) MapFocus.radar.value = true
        }
        launch { Repo.loadChiusure() }; launch { Repo.loadAree() }; launch { Repo.loadCorsiAcqua() }
        launch { Repo.loadConfine() }; launch { Repo.loadMeteo() }
    }

    val map = remember {
        MapFocus.view?.takeIf { MapFocus.viewOwner === ctx } ?: MapView(ctx).apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
            zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
            isTilesScaledToDpi = true
            minZoomLevel = 6.0 // come la PWA: ci si può allontanare per vedere il radar su tutta Italia
            maxZoomLevel = 19.0
            // come lo zoomSnap di Leaflet: dopo il pizzico lo zoom si ferma su un livello intero,
            // così le tessere sono mostrate alla loro risoluzione e non ingrandite (sgranate).
            // L'arrotondamento di osmdroid zooma sul centro dello schermo e fa "saltare" la mappa:
            // qui invece resta fermo il punto tra le dita.
            snapZoomOnPinch(this)
            setDestroyMode(false) // non distruggerla quando esce dallo schermo: viene riusata
            controller.setZoom(MapFocus.lastZoom)
            controller.setCenter(MapFocus.lastCenter ?: GeoPoint(Config.CENTER_LAT, Config.CENTER_LON))
        }.also { MapFocus.view = it; MapFocus.viewOwner = ctx; MapFocus.builtFrom = null }
    }

    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val obs = LifecycleEventObserver { _, e ->
            when (e) {
                Lifecycle.Event.ON_RESUME -> map.onResume()
                Lifecycle.Event.ON_PAUSE -> map.onPause()
                else -> {}
            }
        }
        lifecycle.addObserver(obs)
        map.onResume()
        myLocationOverlay?.enableMyLocation()
        onDispose {
            MapFocus.lastCenter = GeoPoint(map.mapCenter.latitude, map.mapCenter.longitude)
            MapFocus.lastZoom = map.zoomLevelDouble
            myLocationOverlay?.disableMyLocation()
            lifecycle.removeObserver(obs)
            map.onPause() // la vista resta in memoria per il prossimo ritorno alla scheda
        }
    }

    LaunchedEffect(target) {
        target?.let {
            map.controller.animateTo(GeoPoint(it.lat, it.lon), 17.0, 600L)
            MapFocus.target.value = null
        }
    }

    // Livelli: ricostruiti quando cambiano dati o filtri (ordine di disegno come la PWA)
    LaunchedEffect(aree.data, chiusure.data, corsi.data, confine.data, layers, radarOn) {
        val key = listOf(aree.data, chiusure.data, corsi.data, confine.data, layers, radarOn)
        if (MapFocus.builtFrom == key) return@LaunchedEffect
        MapFocus.builtFrom = key
        val keep = map.overlays.filterIsInstance<MyLocationNewOverlay>()
        map.overlays.clear()
        confine.data?.forEach { ring ->
            map.overlays += Polygon(map).apply {
                points = ring.map { GeoPoint(it.first, it.second) }
                outlinePaint.color = 0xFF8D021F.toInt(); outlinePaint.strokeWidth = 2f * density
                outlinePaint.pathEffect = android.graphics.DashPathEffect(floatArrayOf(6 * density, 6 * density), 0f)
                fillPaint.color = 0x088D021F
                setOnClickListener { _, _, _ -> false }
            }
        }
        if (radarOn) map.overlays += radarOverlay
        if (Layer.CORSI in layers) corsi.data?.forEach { c ->
            val (color, w) = waterwayStyle[c.tipo] ?: (0xFF0EA5E9.toInt() to 2f)
            map.overlays += Polyline(map).apply {
                setPoints(c.linea.map { GeoPoint(it.first, it.second) })
                outlinePaint.color = color; outlinePaint.alpha = 205; outlinePaint.strokeWidth = w * density
                outlinePaint.strokeCap = Paint.Cap.ROUND
                setOnClickListener { _, _, _ -> popupState.value = Popup.Corso(c.nome ?: waterwayLabel[c.tipo] ?: "Corso d'acqua"); true }
            }
        }
        aree.data?.forEach { a ->
            val layer = when (a.tipo) { AreaPC.Tipo.ATTESA -> Layer.ATTESA; AreaPC.Tipo.RICOVERO -> Layer.RICOVERO; AreaPC.Tipo.AMMASSAMENTO -> Layer.AMMASSAMENTO }
            if (layer !in layers) return@forEach
            val (stroke, fill) = when (a.tipo) {
                AreaPC.Tipo.ATTESA -> 0xFF166534.toInt() to 0x5922C55E
                AreaPC.Tipo.RICOVERO -> 0xFF991B1B.toInt() to 0x59EF4444
                AreaPC.Tipo.AMMASSAMENTO -> 0xFFA16207.toInt() to 0x59EAB308
            }
            map.overlays += Polygon(map).apply {
                points = a.forma.map { GeoPoint(it.first, it.second) }
                outlinePaint.color = stroke; outlinePaint.strokeWidth = 2.5f * density
                fillPaint.color = fill
                setOnClickListener { _, _, _ -> popupState.value = Popup.Area(a); true }
            }
        }
        if (Layer.AVVISI in layers) chiusure.data?.filter { it.stato() != Chiusura.Stato.CONCLUSO }?.forEach { c ->
            val prog = c.stato() == Chiusura.Stato.PROGRAMMATO
            val stroke = if (prog) 0xFFC2410C.toInt() else 0xFFB91C1C.toInt()
            val fill = if (prog) 0x59FB923C else 0x59DC2626
            val width = if (prog) 3f else 6f
            c.forme.forEach { f ->
                val closed = f.size >= 4 && f.first() == f.last()
                map.overlays += if (closed) Polygon(map).apply {
                    points = f.map { GeoPoint(it.first, it.second) }
                    outlinePaint.color = stroke; outlinePaint.strokeWidth = width * density; fillPaint.color = fill
                    setOnClickListener { _, _, _ -> popupState.value = Popup.Avviso(c); true }
                } else Polyline(map).apply {
                    setPoints(f.map { GeoPoint(it.first, it.second) })
                    outlinePaint.color = stroke; outlinePaint.strokeWidth = width * density
                    setOnClickListener { _, _, _ -> popupState.value = Popup.Avviso(c); true }
                }
            }
            c.punti.forEach { p -> map.overlays += marker(map, p.first, p.second, circleIcon(ctx, 16, stroke, fill or 0x59000000)) { popupState.value = Popup.Avviso(c) } }
        }
        if (Layer.SEDE_PC in layers) map.overlays += marker(map, Config.PC_LAT, Config.PC_LON, pcIcon(ctx)) { popupState.value = Popup.SedePC }
        // sensori ARPAV: sempre visibili, toccandoli si apre la stazione (come il pannello della PWA)
        Stations.all.forEach { st ->
            map.overlays += marker(map, st.lat, st.lon, arpavIcon(ctx)) { nav.go("stazione/${st.id}") } // "indietro" torna alla mappa
        }
        map.overlays.addAll(keep)
        map.invalidate()
    }

    // Radar: tessere dell'area inquadrata, aggiornate quando si sposta la mappa e ogni minuto
    var viewport by remember { mutableStateOf(0) }
    DisposableEffect(map) {
        val l = object : org.osmdroid.events.MapListener {
            override fun onScroll(event: org.osmdroid.events.ScrollEvent?): Boolean { viewport++; return false }
            override fun onZoom(event: org.osmdroid.events.ZoomEvent?): Boolean { viewport++; return false }
        }
        map.addMapListener(l)
        onDispose { map.removeMapListener(l) }
    }
    LaunchedEffect(radarOn, viewport) {
        if (!radarOn) { Radar.clear(); return@LaunchedEffect }
        delay(400) // attende che lo spostamento finisca
        while (true) {
            val bb = map.boundingBox
            val z = Radar.tileZoom(map.zoomLevelDouble)
            val keys = (Radar.tileX(bb.lonWest, z)..Radar.tileX(bb.lonEast, z)).flatMap { x ->
                (Radar.tileY(bb.latNorth, z)..Radar.tileY(bb.latSouth, z)).map { y -> Triple(z, x, y) }
            }.take(30)
            Radar.refresh(keys) { map.invalidate() }
            map.invalidate()
            delay(60_000)
        }
    }
    LaunchedEffect(Radar.playing, radarOn) {
        while (radarOn && Radar.playing && Radar.frames.isNotEmpty()) {
            val wasLast = Radar.current == Radar.frames.size - 1
            delay(if (wasLast) 1200 else 450)
            Radar.current = (Radar.current + 1) % Radar.frames.size
            map.invalidate()
        }
    }

    val locLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { r ->
        if (r.values.any { it }) myLocationOverlay = enableLocation(map, myLocationOverlay)
    }

    Box(Modifier.fillMaxSize()) {
        AndroidView({ (map.parent as? android.view.ViewGroup)?.removeView(map); map }, Modifier.fillMaxSize())

        // pulsanti in alto a destra, come il controllo della PWA
        Column(Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(top = 10.dp, end = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp), horizontalAlignment = Alignment.End) {
            MapButton(onClick = { menuOpen = !menuOpen; popupState.value = null }) { Icon(painterResource(R.drawable.ic_layers), "Livelli mappa") }
            MapButton(onClick = {
                scope.launch {
                    launch { Repo.loadAree(true) }; launch { Repo.loadCorsiAcqua(true) }; launch { Repo.loadChiusure(true) }
                }
            }) {
                if (aree.loading || corsi.loading || chiusure.loading) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                else Icon(Icons.Filled.Refresh, "Aggiorna dati mappa")
            }
            MapButton(onClick = {
                val granted = ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
                    ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
                if (granted) myLocationOverlay = enableLocation(map, myLocationOverlay)
                else locLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
            }) { Icon(painterResource(R.drawable.ic_my_location), "La mia posizione") }
            MapButton(onClick = { nav.go("meteo") }) {
                val s = meteo.data?.upcoming()?.firstOrNull()
                if (s != null) WeatherIcon(meteoCategory(s.cielo, s.precipitazioni).nightAware(), 30.dp, s.probInt())
                else Icon(painterResource(R.drawable.ic_tab_sensori), "Meteo")
            }
            if (menuOpen) LayerMenu(layers, radarOn) { menuOpen = false }
        }

        // errori OSM
        (aree.error ?: corsi.error)?.takeIf { aree.data == null || corsi.data == null }?.let {
            Surface(Modifier.align(Alignment.TopStart).statusBarsPadding().padding(10.dp).widthIn(max = 240.dp), shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.secondaryContainer) {
                Text("Dati OpenStreetMap non caricati: $it", Modifier.padding(10.dp), style = MaterialTheme.typography.bodySmall)
            }
        }

        if (radarOn) RadarControls(Modifier.align(Alignment.TopStart).statusBarsPadding().padding(start = 10.dp, top = 10.dp, end = 70.dp)) { map.invalidate() }

        // attribuzione
        Text(
            if (radarOn) "© OpenStreetMap contributors · Radar-DPC (CC BY-SA)" else "© OpenStreetMap contributors",
            Modifier.align(Alignment.BottomEnd).background(Color.White.copy(alpha = 0.8f)).clickable { openUrl(ctx, "https://www.openstreetmap.org/copyright") }.padding(horizontal = 5.dp, vertical = 1.dp),
            fontSize = 10.sp, color = Color(0xFF0078A8),
        )

        popup?.let { p -> PopupCard(p, Modifier.align(Alignment.BottomCenter)) { popupState.value = null } }
    }
}

@Composable
private fun MapButton(onClick: () -> Unit, content: @Composable () -> Unit) {
    Surface(onClick = onClick, shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceContainerLowest, contentColor = MaterialTheme.colorScheme.onSurface, shadowElevation = 4.dp, modifier = Modifier.size(46.dp)) {
        Box(contentAlignment = Alignment.Center) { content() }
    }
}

@Composable
private fun LayerMenu(layers: Set<Layer>, radarOn: Boolean, onClose: () -> Unit) {
    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainerLowest, shadowElevation = 8.dp, modifier = Modifier.width(250.dp)) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(vertical = 8.dp)) {
            Row(Modifier.padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Livelli mappa", Modifier.weight(1f), fontWeight = FontWeight.Bold)
                IconButton(onClick = onClose) { Icon(Icons.Filled.Close, "Chiudi") }
            }
            var section = ""
            Layer.entries.forEach { l ->
                if (l.section != section) {
                    section = l.section
                    Text(section.uppercase(), Modifier.padding(start = 16.dp, top = 8.dp, bottom = 2.dp), style = MaterialTheme.typography.labelSmall, letterSpacing = 0.6.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.SemiBold)
                }
                Row(
                    Modifier.fillMaxWidth().clickable { MapFocus.layers.value = if (l in layers) layers - l else layers + l }.padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(l in layers, { MapFocus.layers.value = if (it) layers + l else layers - l })
                    Box(
                        Modifier.size(width = 18.dp, height = if (l.area) 14.dp else 6.dp)
                            .background(if (l.area) l.swatch.copy(alpha = 0.25f) else l.swatch, RoundedCornerShape(3.dp))
                            .then(if (l.area) Modifier.border(2.dp, l.swatch, RoundedCornerShape(3.dp)) else Modifier),
                    )
                    Hspace(10.dp)
                    Text(l.label, style = MaterialTheme.typography.bodyMedium)
                }
            }
            Text("METEO", Modifier.padding(start = 16.dp, top = 8.dp, bottom = 2.dp), style = MaterialTheme.typography.labelSmall, letterSpacing = 0.6.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.SemiBold)
            Row(Modifier.fillMaxWidth().clickable { MapFocus.radar.value = !radarOn }.padding(start = 16.dp, end = 12.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Radar precipitazioni", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                Switch(radarOn, { MapFocus.radar.value = it })
            }
        }
    }
}

private val radarTime = DateTimeFormatter.ofPattern("HH:mm")

@Composable
private fun RadarControls(modifier: Modifier, onChange: () -> Unit) {
    Surface(modifier, shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainerLowest.copy(alpha = 0.95f), shadowElevation = 6.dp) {
        Column(Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
            val frames = Radar.frames
            val t = frames.getOrNull(Radar.current)
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { Radar.playing = !Radar.playing }, enabled = frames.isNotEmpty(), modifier = Modifier.size(36.dp)) {
                    Text(if (Radar.playing) "❚❚" else "▶", fontWeight = FontWeight.Bold)
                }
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            when {
                                t != null -> {
                                    val age = (System.currentTimeMillis() - t) / 60_000
                                    "Radar " + Instant.ofEpochMilli(t).atZone(ZoneId.systemDefault()).format(radarTime) + " · $age min fa"
                                }
                                Radar.loading -> "Caricamento radar…"
                                else -> "Radar precipitazioni"
                            },
                            style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold,
                        )
                        if (t != null && Radar.current == frames.size - 1 && System.currentTimeMillis() - t > 30 * 60_000) {
                            Hspace(6.dp); Text("NON AGGIORNATO", style = MaterialTheme.typography.labelSmall, color = Color(0xFFC2410C), fontWeight = FontWeight.Bold)
                        }
                        if (Radar.loading) { Hspace(6.dp); CircularProgressIndicator(Modifier.size(12.dp), strokeWidth = 1.5.dp) }
                    }
                    if (frames.size > 1) Slider(
                        value = Radar.current.toFloat(), onValueChange = { Radar.playing = false; Radar.current = it.toInt(); onChange() },
                        valueRange = 0f..(frames.size - 1).toFloat(), steps = (frames.size - 2).coerceAtLeast(0), modifier = Modifier.height(28.dp),
                    )
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Opacità", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Slider(Radar.opacity, { Radar.opacity = it; onChange() }, Modifier.weight(1f).height(24.dp).padding(horizontal = 6.dp), valueRange = 0.2f..1f)
                Text("${(Radar.opacity * 100).toInt()}%", style = MaterialTheme.typography.labelSmall)
            }
            Box(Modifier.fillMaxWidth().height(8.dp).background(Brush.horizontalGradient(Radar.legendColors().map { Color(it) }), RoundedCornerShape(4.dp)))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Radar.legendLabels.forEach { Text("$it", fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                Text("dBZ", fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Radar.error?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error) }
        }
    }
}

@Composable
private fun PopupCard(p: Popup, modifier: Modifier, onClose: () -> Unit) {
    val ctx = LocalContext.current
    Surface(modifier.fillMaxWidth().padding(12.dp), shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surfaceContainerLowest, shadowElevation = 8.dp) {
        Box {
            Column(Modifier.padding(start = 16.dp, end = 44.dp, top = 14.dp, bottom = 14.dp)) {
                when (p) {
                    is Popup.Area -> {
                        val color = when (p.a.tipo) { AreaPC.Tipo.ATTESA -> Color(0xFF166534); AreaPC.Tipo.RICOVERO -> Color(0xFF991B1B); AreaPC.Tipo.AMMASSAMENTO -> Color(0xFFA16207) }
                        Text(p.a.tipo.label, color = color, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                        p.a.nome?.let { Muted(it) }
                        NavigaButton { navigateTo(ctx, p.a.centro.first, p.a.centro.second, p.a.nome ?: p.a.tipo.label) }
                    }
                    Popup.SedePC -> {
                        Text("Sede Protezione Civile", color = Color(0xFFFF6600), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                        Muted("Castelfranco Veneto")
                        NavigaButton { navigateTo(ctx, Config.PC_LAT, Config.PC_LON, "Sede Protezione Civile") }
                    }
                    is Popup.Avviso -> {
                        val prog = p.c.stato() == Chiusura.Stato.PROGRAMMATO
                        Row(verticalAlignment = Alignment.Top) {
                            Text(p.c.titolo, Modifier.weight(1f), color = Color(0xFF991B1B), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                            Surface(
                                shape = RoundedCornerShape(50), color = if (prog) Color(0xFFFFEDD5) else Color(0xFFFEE2E2),
                                contentColor = if (prog) Color(0xFFC2410C) else Color(0xFF991B1B),
                                modifier = Modifier.border(1.dp, if (prog) Color(0xFFFDBA74) else Color(0xFFFCA5A5), RoundedCornerShape(50)),
                            ) { Text(if (prog) "IN FUTURO" else "ATTIVO", Modifier.padding(horizontal = 10.dp, vertical = 3.dp), fontSize = 11.sp, fontWeight = FontWeight.ExtraBold) }
                        }
                        Text(p.c.descrizione.ifBlank { "Nessuna descrizione disponibile." }, Modifier.padding(top = 6.dp))
                        if (p.c.aree.isNotEmpty()) Text("Vie/zone interessate: ${p.c.aree.joinToString(", ")}", Modifier.padding(top = 6.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("Periodo: ${p.c.inizioTesto} → ${p.c.fineTesto}", Modifier.padding(top = 6.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    is Popup.Corso -> Text(p.testo, fontWeight = FontWeight.SemiBold)
                }
            }
            IconButton(onClick = onClose, modifier = Modifier.align(Alignment.TopEnd)) { Icon(Icons.Filled.Close, "Chiudi") }
        }
    }
}

@Composable
private fun NavigaButton(onClick: () -> Unit) {
    Button(onClick = onClick, modifier = Modifier.padding(top = 10.dp)) { Text("Naviga con Maps") }
}

// ── icone dei marker, identiche alla PWA ─────────────────────────────────────

private fun bitmap(ctx: Context, sizeDp: Int, draw: (ACanvas, Float) -> Unit): BitmapDrawable {
    val d = ctx.resources.displayMetrics.density
    val shadow = 4 * d
    val px = (sizeDp * d + 2 * shadow).toInt()
    val bmp = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
    val c = ACanvas(bmp)
    c.translate(shadow, shadow)
    draw(c, d)
    return BitmapDrawable(ctx.resources, bmp)
}

private fun disc(c: ACanvas, d: Float, sizeDp: Int, fill: Int) {
    val r = sizeDp * d / 2
    val p = Paint(Paint.ANTI_ALIAS_FLAG)
    p.color = 0x66000000; p.setShadowLayer(4 * d, 0f, 2 * d, 0x66000000)
    c.drawCircle(r, r, r, p)
    p.clearShadowLayer(); p.color = 0xFFFFFFFF.toInt(); c.drawCircle(r, r, r, p)
    p.color = fill; c.drawCircle(r, r, r - 2 * d, p)
}

/** Marker stazione: cerchio verde acqua 26 px con bordo bianco e simbolo ARPAV. */
private fun arpavIcon(ctx: Context) = bitmap(ctx, 26) { c, d ->
    disc(c, d, 26, 0xFF0E8A8A.toInt())
    ContextCompat.getDrawable(ctx, R.drawable.ic_arpav)!!.apply {
        val s = (13 * d).toInt(); val o = ((26 * d - s) / 2).toInt()
        setBounds(o, o, o + s, o + s); draw(c)
    }
}

/** Sede PC: cerchio arancione con triangolo blu. */
private fun pcIcon(ctx: Context) = bitmap(ctx, 26) { c, d ->
    disc(c, d, 26, 0xFFFF6600.toInt())
    val k = 14 * d / 20; val o = (26 * d - 14 * d) / 2
    val path = android.graphics.Path().apply {
        moveTo(o + 10 * k, o + 3 * k); lineTo(o + 19 * k, o + 17 * k); lineTo(o + 1 * k, o + 17 * k); close()
    }
    val p = Paint(Paint.ANTI_ALIAS_FLAG)
    p.color = 0xFF1A3A8F.toInt(); c.drawPath(path, p)
    p.style = Paint.Style.STROKE; p.color = 0xFFFFFFFF.toInt(); p.strokeWidth = k; p.strokeJoin = Paint.Join.ROUND; c.drawPath(path, p)
}

private fun circleIcon(ctx: Context, sizeDp: Int, stroke: Int, fill: Int) = bitmap(ctx, sizeDp) { c, d ->
    val r = sizeDp * d / 2
    val p = Paint(Paint.ANTI_ALIAS_FLAG)
    p.color = fill; c.drawCircle(r, r, r - 1.5f * d, p)
    p.style = Paint.Style.STROKE; p.color = stroke; p.strokeWidth = 3 * d; c.drawCircle(r, r, r - 1.5f * d, p)
}

private fun marker(map: MapView, lat: Double, lon: Double, icon: BitmapDrawable, onTap: () -> Unit) = Marker(map).apply {
    position = GeoPoint(lat, lon)
    this.icon = icon
    setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
    setInfoWindow(null)
    setOnMarkerClickListener { _, _ -> onTap(); true }
}

/** Pallino blu della posizione (con alone di precisione), come la PWA. */
private fun enableLocation(map: MapView, existing: MyLocationNewOverlay?): MyLocationNewOverlay {
    val o = existing ?: MyLocationNewOverlay(GpsMyLocationProvider(map.context), map).also { ov ->
        val d = map.resources.displayMetrics.density
        val size = (22 * d).toInt()
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = ACanvas(bmp)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.color = 0x553B82F6; c.drawCircle(size / 2f, size / 2f, size / 2f, p)
        p.color = 0xFFFFFFFF.toInt(); p.setShadowLayer(2 * d, 0f, d, 0x59000000); c.drawCircle(size / 2f, size / 2f, 7 * d + 2.5f * d, p)
        p.clearShadowLayer(); p.color = 0xFF2563EB.toInt(); c.drawCircle(size / 2f, size / 2f, 7 * d, p)
        ov.setPersonIcon(bmp); ov.setDirectionIcon(bmp)
        ov.setPersonAnchor(0.5f, 0.5f); ov.setDirectionAnchor(0.5f, 0.5f)
        ov.isDrawAccuracyEnabled = true
        map.overlays.add(ov)
    }
    o.enableMyLocation()
    o.runOnFirstFix { map.post { o.myLocation?.let { map.controller.animateTo(it, 16.0, 600L) } } }
    o.myLocation?.let { map.controller.animateTo(it, 16.0, 600L) }
    return o
}

/** Al termine di un pizzico porta lo zoom al livello intero più vicino, tenendo fermo il punto tra le dita. */
@android.annotation.SuppressLint("ClickableViewAccessibility")
private fun snapZoomOnPinch(map: MapView) {
    var pinching = false
    var fx = 0f; var fy = 0f
    map.setOnTouchListener { _, e ->
        when (e.actionMasked) {
            android.view.MotionEvent.ACTION_POINTER_DOWN, android.view.MotionEvent.ACTION_MOVE -> if (e.pointerCount >= 2) {
                pinching = true
                fx = (e.getX(0) + e.getX(1)) / 2; fy = (e.getY(0) + e.getY(1)) / 2
            }
            android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> if (pinching) {
                pinching = false
                map.post {
                    val z = map.zoomLevelDouble
                    val target = Math.round(z).toDouble().coerceIn(map.minZoomLevel, map.maxZoomLevel)
                    if (kotlin.math.abs(z - target) > 0.01) map.controller.zoomToFixing(target, fx.toInt(), fy.toInt(), 220L)
                }
            }
        }
        false // il gesto prosegue normalmente verso la mappa
    }
}
