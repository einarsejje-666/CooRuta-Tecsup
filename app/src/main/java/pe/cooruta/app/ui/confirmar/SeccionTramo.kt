package pe.cooruta.app.ui.confirmar

import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.LockOpen
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.model.CameraPosition
import com.google.maps.android.compose.GoogleMap
import com.google.maps.android.compose.rememberCameraPositionState
import kotlinx.coroutines.launch
import pe.cooruta.app.ui.mapa.LineaMapa
import pe.cooruta.app.ui.mapa.MarcaMapa
import pe.cooruta.app.ui.mapa.UI_MAPA
import pe.cooruta.app.ui.mapa.circulo
import pe.cooruta.app.ui.mapa.latLng
import pe.cooruta.app.ui.mapa.limites
import pe.cooruta.app.ui.mapa.propiedadesMapa
import pe.cooruta.app.BuildConfig
import pe.cooruta.app.data.Punto
import pe.cooruta.app.ui.theme.Coo

// Ámbar: se distingue de las calles (grises) y del tramo público (azul).
private val GRIS_PRIVADO = Coo.Advertencia

/**
 * Conductor: vista previa de lo que verán en Buscar los usuarios con sesión y confirmación
 * explícita. Los extremos originales no se publican. Solo dibuja y comunica toques.
 */
@Composable
internal fun SeccionTramo(e: EstadoConfirmar, vm: ConfirmarViewModel) {
    if (e.tramoUtilizable == null) return
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        // Selector segmentado: Pública | Privada.
        Row(
            Modifier.fillMaxWidth().height(56.dp).clip(RoundedCornerShape(14.dp)).background(Coo.Campo)
                .border(1.dp, Coo.Borde, RoundedCornerShape(14.dp))
        ) {
            OpcionVisibilidad("Pública", publica = true, elegida = e.modo != ModoTramo.SIN_BUSCAR, Modifier.weight(1f)) {
                vm.elegirModo(ModoTramo.COMPLETO)
            }
            OpcionVisibilidad("Privada", publica = false, elegida = e.modo == ModoTramo.SIN_BUSCAR, Modifier.weight(1f)) {
                vm.elegirModo(ModoTramo.SIN_BUSCAR)
            }
        }
        Texto(if (e.modo == ModoTramo.SIN_BUSCAR) "Solo tú puedes ver esta ruta." else "Los pasajeros podrán verla en Buscar.")
        if (e.modo == ModoTramo.ELEGIR) {
            if (!e.hayEspacio) Texto("Tu recorrido es demasiado corto para mostrar un tramo sin revelar tus extremos. Elige «Privada».", color = Coo.RojoError)
            e.errorTramo?.let { Texto(it, color = Coo.RojoError) }
            if (e.marcas.isNotEmpty()) Text("Borrar marcas", color = Coo.AzulUbicacion, fontWeight = FontWeight.Bold,
                modifier = Modifier.clickable { vm.borrarMarcas() }.padding(vertical = 4.dp))
            if (BuildConfig.HAY_MAPA) Box(Modifier.fillMaxWidth().height(260.dp).clip(RoundedCornerShape(16.dp))) {
                MapaTramo(e.recorridoCompleto, e.tramoVisible, e.marcas, alTocar = vm::marcar)
            }
        }
    }
}

/** Tarjeta seleccionable con candado abierto (pública) o cerrado (privada). */
@Composable
private fun OpcionVisibilidad(titulo: String, publica: Boolean, elegida: Boolean, modifier: Modifier, alPulsar: () -> Unit) {
    val color = if (elegida) Coo.SobreBoton else Coo.TextoTenue
    Box(
        modifier.fillMaxHeight().clip(RoundedCornerShape(14.dp))
            .background(if (elegida) Coo.AzulBoton else Color.Transparent)
            .clickable(onClick = alPulsar),
        contentAlignment = Alignment.Center
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (publica) IconoMundo(color) else IconoCandado(false, color, 22.dp)
            Text(titulo, color = color, fontSize = 16.sp, fontWeight = FontWeight.Medium)
        }
        if (elegida) Box(Modifier.align(Alignment.CenterEnd).padding(end = 14.dp)) {
            // ✓ dentro de un círculo blanco.
            androidx.compose.foundation.Canvas(Modifier.size(22.dp)) {
                drawCircle(color)
                val g = 2.dp.toPx()
                val w = size.width; val h = size.height
                val m = androidx.compose.ui.geometry.Offset(w * 0.43f, h * 0.66f)
                drawLine(Coo.AzulBoton, androidx.compose.ui.geometry.Offset(w * 0.27f, h * 0.5f), m, g, androidx.compose.ui.graphics.StrokeCap.Round)
                drawLine(Coo.AzulBoton, m, androidx.compose.ui.geometry.Offset(w * 0.73f, h * 0.36f), g, androidx.compose.ui.graphics.StrokeCap.Round)
            }
        }
    }
}

/** Globo terráqueo de líneas: ruta pública. */
@Composable
private fun IconoMundo(color: Color) =
    pe.cooruta.app.ui.componentes.IconoM(androidx.compose.material.icons.Icons.Rounded.Public, color, 22.dp)

/** Candado dibujado: cerrado = privado, abierto = público. */
@Composable
fun IconoCandado(abierto: Boolean, color: Color, tamano: androidx.compose.ui.unit.Dp) =
    pe.cooruta.app.ui.componentes.IconoM(
        if (abierto) androidx.compose.material.icons.Icons.Rounded.LockOpen else androidx.compose.material.icons.Icons.Rounded.Lock,
        color, tamano)

@Composable
private fun Texto(t: String, color: Color = Coo.TextoTenue) = Text(t, color = color, fontSize = 13.sp, lineHeight = 18.sp)

@Composable
private fun Opcion(texto: String, elegida: Boolean, modifier: Modifier, alPulsar: () -> Unit) {
    Text(texto, color = if (elegida) Coo.SobreBoton else Coo.Texto, fontSize = 13.sp, fontWeight = FontWeight.Bold,
        modifier = modifier.clip(RoundedCornerShape(12.dp)).background(if (elegida) Coo.AzulBoton else Coo.Campo)
            .clickable(onClick = alPulsar).padding(12.dp))
}

/** Ámbar: recorrido completo (solo en este dispositivo). Azul: tramo público. Puntos: marcas. */
@Composable
internal fun MapaTramo(completo: List<Punto>, visible: List<Punto>, marcas: List<Punto>, alTocar: ((Punto) -> Unit)?) {
    val base = completo.ifEmpty { visible }
    val centro = base.getOrNull(base.size / 2) ?: Punto(-16.409, -71.537)
    val camara = rememberCameraPositionState { position = CameraPosition.fromLatLngZoom(centro.latLng(), 14f) }
    val densidad = androidx.compose.ui.platform.LocalDensity.current.density
    GoogleMap(
        modifier = Modifier.fillMaxSize(),
        cameraPositionState = camara,
        properties = propiedadesMapa(satelital = false),
        uiSettings = UI_MAPA,
        onMapClick = { ll -> alTocar?.invoke(Punto(ll.latitude, ll.longitude)) }
    ) {
        LineaMapa(completo, GRIS_PRIVADO, 5f)
        LineaMapa(visible, Coo.AzulUbicacion, 7f)
        val icono = remember(densidad) { circulo(Coo.Advertencia, densidad) }
        marcas.forEach { MarcaMapa(it, icono) }
    }
}

/** Solo ver: la ruta en azul con inicio y fin, sobre vista satelital, encuadrada completa. */
@Composable
internal fun MapaVista(
    ruta: List<Punto>, subida: Punto? = null, bajada: Punto? = null,
    pasajeros: List<PasajeroMapa> = if (subida != null || bajada != null) listOf(PasajeroMapa("", Coo.Exito, subida, bajada)) else emptyList(),
    /** Ruta recalculada pasando por las paradas de los pasajeros; si viene, se dibuja encima en verde. */
    nueva: List<Punto> = emptyList()
) {
    val extremosP = pasajeros.flatMap { listOfNotNull(it.subida, it.bajada) }
    val pts = ruta.ifEmpty { extremosP }
    val todos = (ruta + extremosP + nueva)
    val centro = pts.getOrNull(pts.size / 2) ?: Punto(-16.409, -71.537)
    val camara = rememberCameraPositionState { position = CameraPosition.fromLatLngZoom(centro.latLng(), 13f) }
    var cargado by remember { mutableStateOf(false) }
    val alcance = rememberCoroutineScope()
    val densidad = androidx.compose.ui.platform.LocalDensity.current.density
    // Encuadra la ruta completa con margen; luego el usuario mueve y hace zoom libremente.
    LaunchedEffect(cargado, pts) {
        if (cargado) limites(todos)?.let { runCatching { camara.move(CameraUpdateFactory.newLatLngBounds(it, (60 * densidad).toInt())) } }
    }
    Box(Modifier.fillMaxSize()) {
    GoogleMap(
        modifier = Modifier.fillMaxSize(), cameraPositionState = camara,
        properties = propiedadesMapa(satelital = true), uiSettings = UI_MAPA,
        onMapLoaded = { cargado = true }
    ) {
        if (nueva.size >= 2) {
            // Ruta original tenue debajo; la nueva (con el desvío) en verde encima.
            LineaMapa(pts, Coo.AzulBoton.copy(alpha = 0.45f), 4f)
            LineaMapa(nueva, Coo.Exito, 6f, z = 1f)
        } else LineaMapa(pts, Coo.AzulBoton, 6f)
        // Tramo de cada pasajero (de donde sube a donde baja): línea fina al centro, de su color.
        if (nueva.size < 2) pasajeros.forEach { p ->
            val compartido = remember(ruta, p) {
                val s = p.subida; val b = p.bajada
                if (s == null || b == null || ruta.size < 2) emptyList()
                else {
                    fun cerca(x: Punto) = ruta.indices.minByOrNull { pe.cooruta.app.data.metros(ruta[it], x) } ?: 0
                    val i = cerca(s); val j = cerca(b)
                    listOf(s) + ruta.subList(minOf(i, j), maxOf(i, j) + 1) + listOf(b)
                }
            }
            LineaMapa(compartido, if (pasajeros.size == 1) Color.White else p.color, 2f)
        }
        // Marcadores tipo pin: inicio y destino del conductor; dónde sube (S) y baja (B) el pasajero.
        val pines = remember(pts, pasajeros, densidad) {
            listOfNotNull(
                pts.firstOrNull()?.let { it to pin(Coo.AzulBoton, "", densidad) },
                pts.lastOrNull()?.takeIf { pts.size > 1 }?.let { it to pin(Coo.RojoDestino, "⚑", densidad) }
            ) + pasajeros.flatMap { p ->
                // Un pasajero: S verde y B amarilla. Varios: S1/B1… del color de cada uno.
                val (cs, cb) = if (pasajeros.size == 1) Coo.Exito to Coo.Advertencia else p.color to p.color
                listOfNotNull(p.subida?.let { it to pin(cs, "S${p.numero}", densidad) },
                    p.bajada?.let { it to pin(cb, "B${p.numero}", densidad) })
            }
        }
        pines.forEach { (pt, bmp) -> MarcaMapa(pt, bmp, anclaAbajo = true) }
    }
    // Botones + y − para acercar o alejar (además de pellizcar).
    Column(Modifier.align(Alignment.CenterEnd).padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf("+" to 1f, "−" to -1f).forEach { (texto, paso) ->
            Box(
                Modifier.size(44.dp).clip(RoundedCornerShape(12.dp)).background(Coo.Campo.copy(alpha = 0.9f))
                    .border(1.dp, Coo.Borde, RoundedCornerShape(12.dp))
                    .clickable { alcance.launch { camara.animate(CameraUpdateFactory.zoomBy(paso)) } },
                contentAlignment = Alignment.Center
            ) { Text(texto, color = Coo.Texto, fontSize = 22.sp, fontWeight = FontWeight.Bold) }
        }
    }
    }
}

/** Pin en forma de gota con borde blanco y un texto corto al centro (vacío = punto blanco). */
private fun pin(color: Color, texto: String, densidad: Float): android.graphics.Bitmap {
    val w = (30 * densidad).toInt(); val h = (40 * densidad).toInt()
    val bmp = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888)
    val c = android.graphics.Canvas(bmp)
    val r = w / 2f - 2 * densidad
    val camino = android.graphics.Path().apply {
        addCircle(w / 2f, r + 2 * densidad, r, android.graphics.Path.Direction.CW)
        moveTo(w / 2f - r * 0.6f, r * 1.6f); lineTo(w / 2f, h - 1f); lineTo(w / 2f + r * 0.6f, r * 1.6f); close()
    }
    val pintura = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
    pintura.color = android.graphics.Color.WHITE
    c.drawPath(camino, pintura)
    c.save(); c.scale(0.84f, 0.84f, w / 2f, r + 2 * densidad)
    pintura.color = android.graphics.Color.argb((color.alpha * 255).toInt(), (color.red * 255).toInt(), (color.green * 255).toInt(), (color.blue * 255).toInt())
    c.drawPath(camino, pintura); c.restore()
    pintura.color = android.graphics.Color.WHITE
    if (texto.isEmpty()) c.drawCircle(w / 2f, r + 2 * densidad, r * 0.35f, pintura)
    else {
        pintura.textSize = if (texto.length > 1) r * 0.85f else r * 1.1f; pintura.textAlign = android.graphics.Paint.Align.CENTER
        pintura.typeface = android.graphics.Typeface.DEFAULT_BOLD
        c.drawText(texto, w / 2f, r + 2 * densidad - (pintura.descent() + pintura.ascent()) / 2, pintura)
    }
    return bmp
}

/** Un pasajero en el mapa: su número (vacío si es uno solo), color y puntos. */
data class PasajeroMapa(val numero: String, val color: Color, val subida: Punto?, val bajada: Punto?)

/** Colores para distinguir pasajeros en el mapa. */
val COLORES_PASAJEROS = listOf(Color(0xFF5CC98A), Color(0xFFFFC857), Color(0xFFB48CF2), Color(0xFF4FC3F7), Color(0xFFF48FB1), Color(0xFFFF8A65))
