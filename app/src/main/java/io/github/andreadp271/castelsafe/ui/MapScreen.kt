package io.github.andreadp271.castelsafe.ui

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.drawable.GradientDrawable
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import io.github.andreadp271.castelsafe.R
import io.github.andreadp271.castelsafe.data.AreaPC
import io.github.andreadp271.castelsafe.data.Chiusura
import io.github.andreadp271.castelsafe.data.Config
import io.github.andreadp271.castelsafe.data.Level
import io.github.andreadp271.castelsafe.data.Repo
import io.github.andreadp271.castelsafe.data.Stations
import kotlinx.coroutines.flow.MutableStateFlow
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

/** Punto su cui centrare la mappa (da "Mostra sulla mappa") e ultima vista, conservata tra le schede. */
object MapFocus {
    data class Target(val lat: Double, val lon: Double)
    val target = MutableStateFlow<Target?>(null)
    var lastCenter: GeoPoint? = null
    var lastZoom: Double = 13.5
    val layers = MutableStateFlow(setOf(Layer.AREE, Layer.AVVISI, Layer.SENSORI))
}

enum class Layer(val label: String) { AREE("Aree PC"), AVVISI("Avvisi"), SENSORI("Sensori"), CORSI("Corsi d'acqua") }

/** Elemento toccato sulla mappa, mostrato nella scheda in basso. */
private data class Selected(val title: String, val kind: String, val detail: String?, val lat: Double, val lon: Double, val route: String? = null, val color: Int)

@Composable
fun MapScreen(nav: Nav) {
    val ctx = LocalContext.current
    val aree by Repo.aree.collectAsState()
    val chiusure by Repo.chiusure.collectAsState()
    val monitor by Repo.monitor.collectAsState()
    val corsi by Repo.corsiAcqua.collectAsState()
    val layers by MapFocus.layers.collectAsState()
    val target by MapFocus.target.collectAsState()
    var selected by remember { mutableStateOf<Selected?>(null) }
    var myLocation by remember { mutableStateOf<MyLocationNewOverlay?>(null) }

    LaunchedEffect(Unit) { Repo.loadChiusure(); Repo.loadMonitor(); Repo.loadAree() }
    LaunchedEffect(Layer.CORSI in layers) { if (Layer.CORSI in layers) Repo.loadCorsiAcqua() }

    val map = remember {
        MapView(ctx).apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
            zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
            isTilesScaledToDpi = true
            minZoomLevel = 9.0
            controller.setZoom(MapFocus.lastZoom)
            controller.setCenter(MapFocus.lastCenter ?: GeoPoint(Config.CENTER_LAT, Config.CENTER_LON))
        }
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
        onDispose {
            MapFocus.lastCenter = map.mapCenter as? GeoPoint ?: GeoPoint(map.mapCenter.latitude, map.mapCenter.longitude)
            MapFocus.lastZoom = map.zoomLevelDouble
            myLocation?.disableMyLocation()
            lifecycle.removeObserver(obs)
            map.onPause()
            map.onDetach()
        }
    }

    LaunchedEffect(target) {
        target?.let {
            map.controller.animateTo(GeoPoint(it.lat, it.lon), 16.5, 600L)
            MapFocus.target.value = null
        }
    }

    // Ricostruisce i livelli quando cambiano dati o filtri
    LaunchedEffect(aree.data, chiusure.data, monitor.data, corsi.data, layers) {
        val keep = map.overlays.filterIsInstance<MyLocationNewOverlay>()
        map.overlays.clear()
        val ov = mutableListOf<Overlay>()
        val tap: (Selected) -> Boolean = { selected = it; true }

        if (Layer.CORSI in layers) corsi.data?.forEach { c ->
            ov += Polyline(map).apply {
                setPoints(c.linea.map { GeoPoint(it.first, it.second) })
                outlinePaint.color = 0xFF2563EB.toInt()
                outlinePaint.strokeWidth = if (c.tipo == "river") 6f else 3.5f
                title = c.nome
                setOnClickListener { _, _, p -> tap(Selected(c.nome ?: "Corso d'acqua", "Corso d'acqua (${c.tipo})", null, p.latitude, p.longitude, color = 0xFF2563EB.toInt())) }
            }
        }
        if (Layer.AREE in layers) aree.data?.forEach { a ->
            val (stroke, fill) = when (a.tipo) {
                AreaPC.Tipo.ATTESA -> 0xFF166534.toInt() to 0x5922C55E
                AreaPC.Tipo.RICOVERO -> 0xFF991B1B.toInt() to 0x59EF4444
                AreaPC.Tipo.AMMASSAMENTO -> 0xFFA16207.toInt() to 0x59EAB308
            }
            ov += Polygon(map).apply {
                points = a.forma.map { GeoPoint(it.first, it.second) }
                outlinePaint.color = stroke; outlinePaint.strokeWidth = 4f
                fillPaint.color = fill
                setOnClickListener { _, _, _ ->
                    val (lat, lon) = a.centro
                    tap(Selected(a.nome ?: a.tipo.label, a.tipo.label, descrizioneArea(a.tipo), lat, lon, color = stroke))
                }
            }
        }
        if (Layer.AVVISI in layers) chiusure.data?.filter { it.stato() != Chiusura.Stato.CONCLUSO }?.forEach { c ->
            val prog = c.stato() == Chiusura.Stato.PROGRAMMATO
            val color = if (prog) 0xFFC2410C.toInt() else 0xFFDC2626.toInt()
            val (clat, clon) = c.centro ?: return@forEach
            val sel = Selected(c.titolo, if (prog) "Avviso programmato" else "Avviso in corso",
                listOfNotNull(c.descrizione.takeIf { it.isNotBlank() }, "Dal ${c.inizioTesto} al ${c.fineTesto}").joinToString("\n"), clat, clon, "avvisi", color)
            c.forme.forEach { f ->
                val closed = f.size >= 4 && f.first() == f.last()
                ov += if (closed) Polygon(map).apply {
                    points = f.map { GeoPoint(it.first, it.second) }
                    outlinePaint.color = color; outlinePaint.strokeWidth = 5f
                    fillPaint.color = (color and 0x00FFFFFF) or 0x66000000
                    setOnClickListener { _, _, _ -> tap(sel) }
                } else Polyline(map).apply {
                    setPoints(f.map { GeoPoint(it.first, it.second) })
                    outlinePaint.color = color; outlinePaint.strokeWidth = 9f
                    setOnClickListener { _, _, _ -> tap(sel) }
                }
            }
            c.punti.forEach { p -> ov += dot(map, p.first, p.second, color) { tap(sel) } }
        }
        // sede Protezione Civile
        ov += dot(map, Config.PC_LAT, Config.PC_LON, 0xFFFF6600.toInt(), 13) {
            tap(Selected("Sede Protezione Civile", "Protezione Civile di Castelfranco Veneto", null, Config.PC_LAT, Config.PC_LON, color = 0xFFFF6600.toInt()))
        }
        if (Layer.SENSORI in layers) Stations.all.forEach { st ->
            val l = monitor.data?.get(st.id)?.sensori?.get(st.main)
            val level = Stations.level(st.id, st.main, l?.valore)
            val color = if (level == Level.NESSUNO) 0xFF1D4ED8.toInt() else level.color().toArgb()
            ov += dot(map, st.lat, st.lon, color, 12) {
                tap(Selected(st.name, "Sensore ARPAV · ${st.subtitle}",
                    l?.let { "${Stations.label(st.main)}: ${fmtValue(it.valore, it.unita)} (ore ${it.istante ?: "—"})" }, st.lat, st.lon, "stazione/${st.id}", color))
            }
        }
        map.overlays.addAll(ov)
        map.overlays.addAll(keep)
        map.invalidate()
    }

    val locLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { r ->
        if (r.values.any { it }) myLocation = enableLocation(map, myLocation)
    }

    Box(Modifier.fillMaxSize()) {
        AndroidView({ map }, Modifier.fillMaxSize())

        Column(Modifier.fillMaxWidth().statusBarsPadding().padding(top = 8.dp)) {
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Layer.entries.forEach { l ->
                    FilterChip(
                        selected = l in layers,
                        onClick = { MapFocus.layers.value = if (l in layers) layers - l else layers + l },
                        label = { Text(l.label) },
                        colors = FilterChipDefaults.filterChipColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                    )
                }
            }
            if (aree.loading || chiusure.loading || corsi.loading) LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp))
            (aree.error ?: corsi.error)?.takeIf { aree.data == null || (Layer.CORSI in layers && corsi.data == null) }?.let {
                Surface(Modifier.padding(12.dp), shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.secondaryContainer) {
                    Text("Dati OpenStreetMap non caricati: $it", Modifier.padding(10.dp), style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        SmallFloatingActionButton(
            onClick = {
                val granted = ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
                    ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
                if (granted) myLocation = enableLocation(map, myLocation)
                else locLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
            },
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ) { Icon(painterResource(R.drawable.ic_my_location), "La mia posizione", tint = MaterialTheme.colorScheme.primary) }

        selected?.let { s ->
            Surface(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(12.dp),
                shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainerLow, shadowElevation = 6.dp,
            ) {
                Column(Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.Top) {
                        Column(Modifier.weight(1f)) {
                            Text(s.kind, style = MaterialTheme.typography.labelMedium, color = androidx.compose.ui.graphics.Color(s.color))
                            Text(s.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        }
                        IconButton(onClick = { selected = null }) { Icon(Icons.Filled.Close, "Chiudi") }
                    }
                    s.detail?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                    Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                        s.route?.let { r -> TextButton(onClick = { selected = null; if (r.startsWith("stazione")) { nav.tab("sensori"); nav.go(r) } else nav.tab(r) }) { Text("Dettagli") } }
                        Button(onClick = { navigateTo(ctx, s.lat, s.lon, s.title) }) { Text("Naviga") }
                    }
                }
            }
        }
    }
}

private fun descrizioneArea(t: AreaPC.Tipo) = when (t) {
    AreaPC.Tipo.ATTESA -> "Punto di raccolta per la popolazione in caso di evacuazione. Raggiungila solo se indicato dalle autorità."
    AreaPC.Tipo.RICOVERO -> "Struttura che ospita temporaneamente la popolazione evacuata. Raggiungila solo su indicazione ufficiale."
    AreaPC.Tipo.AMMASSAMENTO -> "Zona riservata a soccorritori, mezzi e logistica. Non è destinata alla popolazione."
}

/** Pallino colorato con bordo bianco, usato per sensori, sede e avvisi puntuali. */
private fun dot(map: MapView, lat: Double, lon: Double, color: Int, sizeDp: Int = 10, onTap: () -> Boolean): Marker {
    val d = map.resources.displayMetrics.density
    val px = (sizeDp * 2 * d).toInt()
    return Marker(map).apply {
        position = GeoPoint(lat, lon)
        icon = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(color)
            setStroke((2.5f * d).toInt(), 0xFFFFFFFF.toInt())
            setSize(px, px)
        }
        setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
        setInfoWindow(null)
        setOnMarkerClickListener { _, _ -> onTap() }
    }
}

private fun enableLocation(map: MapView, existing: MyLocationNewOverlay?): MyLocationNewOverlay {
    val o = existing ?: MyLocationNewOverlay(GpsMyLocationProvider(map.context), map).also { map.overlays.add(it) }
    o.enableMyLocation()
    o.enableFollowLocation()
    o.runOnFirstFix { map.post { map.controller.setZoom(16.0) } }
    return o
}
