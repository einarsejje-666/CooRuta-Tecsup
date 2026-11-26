package pe.cooruta.app.ui.inicio

import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.launch
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.Dot
import com.google.android.gms.maps.model.Gap
import com.google.maps.android.compose.GoogleMap
import com.google.maps.android.compose.Polyline
import com.google.maps.android.compose.rememberCameraPositionState
import pe.cooruta.app.data.Coincidencia
import pe.cooruta.app.data.Punto
import pe.cooruta.app.data.metros
import pe.cooruta.app.ui.mapa.LineaMapa
import pe.cooruta.app.ui.mapa.MarcaMapa
import pe.cooruta.app.ui.mapa.UI_MAPA
import pe.cooruta.app.ui.mapa.circulo
import pe.cooruta.app.ui.mapa.latLng
import pe.cooruta.app.ui.mapa.limites
import pe.cooruta.app.ui.mapa.propiedadesMapa
import pe.cooruta.app.ui.theme.Coo

/**
 * Compara en el mapa la ruta del conductor con el trayecto del pasajero:
 * recorrido del conductor (gris), tramo en el carro (azul) y lo que camina (puntos verdes).
 */
@Composable
fun DialogoMapaComparar(c: Coincidencia, origen: Punto?, destino: Punto?, alCerrar: () -> Unit) {
    Dialog(onDismissRequest = alCerrar, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().background(Coo.Fondo)) {
            val ruta = c.ruta.recorrido
            // Tramo que el pasajero va en el carro: de donde sube a donde baja, sobre el recorrido.
            val compartido = remember(c) {
                if (ruta.size < 2) listOf(c.subida, c.bajada)
                else {
                    fun cerca(x: Punto) = ruta.indices.minByOrNull { metros(ruta[it], x) } ?: 0
                    val i = cerca(c.subida); val j = cerca(c.bajada)
                    listOf(c.subida) + ruta.subList(minOf(i, j), maxOf(i, j) + 1) + listOf(c.bajada)
                }
            }
            // Se encuadra lo que importa al pasajero: su origen, su tramo en el carro y su destino.
            val todos = remember(c, origen, destino) { compartido + listOfNotNull(origen, destino, c.subida, c.bajada) }
            val camara = rememberCameraPositionState {
                position = CameraPosition.fromLatLngZoom((todos.getOrNull(todos.size / 2) ?: c.subida).latLng(), 13f)
            }
            var cargado by remember { mutableStateOf(false) }
            val densidad = LocalDensity.current.density
            val alcance = androidx.compose.runtime.rememberCoroutineScope()
            LaunchedEffect(cargado) {
                if (cargado) limites(todos)?.let { runCatching { camara.move(CameraUpdateFactory.newLatLngBounds(it, (48 * densidad).toInt())) } }
            }
            GoogleMap(
                modifier = Modifier.fillMaxSize(), cameraPositionState = camara,
                // Deja libre la franja del título y la leyenda: el encuadre queda en la zona visible.
                contentPadding = androidx.compose.foundation.layout.PaddingValues(top = 90.dp, bottom = 190.dp),
                properties = propiedadesMapa(satelital = false), uiSettings = UI_MAPA,
                onMapLoaded = { cargado = true }
            ) {
                LineaMapa(ruta, Coo.Neutro, 5f)
                LineaMapa(compartido, Coo.AzulBoton, 7f)
                val puntos = remember { listOf(Dot(), Gap(12f)) }
                if (origen != null) Polyline(points = listOf(origen.latLng(), c.subida.latLng()), color = Coo.Exito,
                    width = 5f * densidad, pattern = puntos)
                if (destino != null) Polyline(points = listOf(c.bajada.latLng(), destino.latLng()), color = Coo.Exito,
                    width = 5f * densidad, pattern = puntos)
                val iconos = remember(densidad) {
                    listOf(circulo(Coo.Neutro, densidad, 6f), circulo(Coo.AzulUbicacion, densidad, 8f, 3f),
                        circulo(Coo.RojoDestino, densidad, 8f, 3f), circulo(Coo.Exito, densidad, 7f, 3f))
                }
                ruta.firstOrNull()?.let { MarcaMapa(it, iconos[0]) }
                ruta.lastOrNull()?.takeIf { ruta.size > 1 }?.let { MarcaMapa(it, iconos[0]) }
                origen?.let { MarcaMapa(it, iconos[3], z = 2f) }
                destino?.let { MarcaMapa(it, iconos[3], z = 2f) }
                MarcaMapa(c.subida, iconos[1], z = 3f)
                MarcaMapa(c.bajada, iconos[2], z = 3f)
            }

            // Acercar, alejar y volver a centrar.
            Column(
                Modifier.align(Alignment.CenterEnd).padding(end = 12.dp, bottom = 100.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                BotonMapa("+") { alcance.launch { camara.animate(CameraUpdateFactory.zoomIn()) } }
                BotonMapa("−") { alcance.launch { camara.animate(CameraUpdateFactory.zoomOut()) } }
                BotonMapa("◎") {
                    limites(todos)?.let { b -> alcance.launch { runCatching { camara.animate(CameraUpdateFactory.newLatLngBounds(b, (48 * densidad).toInt())) } } }
                }
            }

            // Arriba: cerrar y título.
            Row(
                Modifier.fillMaxWidth().background(Coo.Fondo.copy(alpha = 0.92f)).statusBarsPadding()
                    .padding(horizontal = 8.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(Modifier.size(48.dp).clip(CircleShape).clickable(onClick = alCerrar)
                    .semantics { contentDescription = "Cerrar mapa"; role = androidx.compose.ui.semantics.Role.Button },
                    contentAlignment = Alignment.Center) {
                    Text("✕", color = Coo.Texto, fontSize = 20.sp)
                }
                Spacer(Modifier.width(6.dp))
                Column {
                    Text(c.ruta.nombre.ifBlank { "Conductor" }, color = Coo.Texto, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                    Text("Su ruta y tu trayecto", color = Coo.TextoTenue, fontSize = 13.sp)
                }
            }

            // Abajo: leyenda.
            Column(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(Coo.Campo, RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp))
                    .navigationBarsPadding().padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Leyenda(Coo.Neutro, "Ruta del conductor")
                Leyenda(Coo.AzulBoton, "Vas en el carro · %.1f km".format(c.kmCompartidos))
                Leyenda(Coo.Exito, "Caminas · ${c.caminaSubirM + c.caminaBajarM} m en total", punteada = true)
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    PuntoLeyenda(Coo.AzulUbicacion, "Subes")
                    PuntoLeyenda(Coo.RojoDestino, "Bajas")
                    PuntoLeyenda(Coo.Exito, "Tu origen y destino")
                }
            }
        }
    }
}

@Composable
private fun BotonMapa(texto: String, alTocar: () -> Unit) {
    Box(
        Modifier.size(46.dp).clip(CircleShape).background(Coo.Campo.copy(alpha = 0.95f))
            .border(1.dp, Coo.Borde, CircleShape).clickable(onClick = alTocar),
        contentAlignment = Alignment.Center
    ) { Text(texto, color = Coo.Texto, fontSize = 22.sp, fontWeight = FontWeight.Bold) }
}

@Composable
private fun Leyenda(color: Color, texto: String, punteada: Boolean = false) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (punteada) Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            repeat(4) { Box(Modifier.size(5.dp).background(color, CircleShape)) }
        } else Box(Modifier.width(32.dp).height(5.dp).background(color, RoundedCornerShape(3.dp)))
        Spacer(Modifier.width(12.dp))
        Text(texto, color = Coo.Texto, fontSize = 14.sp)
    }
}

@Composable
private fun PuntoLeyenda(color: Color, texto: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(10.dp).background(color, CircleShape))
        Spacer(Modifier.width(6.dp))
        Text(texto, color = Coo.TextoTenue, fontSize = 12.sp)
    }
}
