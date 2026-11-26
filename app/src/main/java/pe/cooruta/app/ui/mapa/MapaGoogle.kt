package pe.cooruta.app.ui.mapa

import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import com.google.android.gms.maps.model.BitmapDescriptorFactory
import com.google.android.gms.maps.model.JointType
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.LatLngBounds
import com.google.android.gms.maps.model.MapStyleOptions
import com.google.android.gms.maps.model.RoundCap
import com.google.maps.android.compose.GoogleMapComposable
import com.google.maps.android.compose.MapProperties
import com.google.maps.android.compose.MapType
import com.google.maps.android.compose.MapUiSettings
import com.google.maps.android.compose.Marker
import com.google.maps.android.compose.MarkerState
import com.google.maps.android.compose.Polyline
import pe.cooruta.app.data.Punto

/** Ayudas comunes para los mapas de Google de la app. */

fun Punto.latLng() = LatLng(latitud, longitud)

/** Mapa oscuro (acorde a la paleta) o satelital con calles y nombres. */
fun propiedadesMapa(satelital: Boolean, miUbicacion: Boolean = false) = MapProperties(
    mapType = if (satelital) MapType.HYBRID else MapType.NORMAL,
    mapStyleOptions = if (satelital) null else MapStyleOptions(ESTILO_NOCHE),
    isMyLocationEnabled = miUbicacion
)

/** Sin controles de Google: la app pone los suyos. */
val UI_MAPA = MapUiSettings(
    zoomControlsEnabled = false, myLocationButtonEnabled = false,
    mapToolbarEnabled = false, compassEnabled = false
)

fun limites(puntos: List<Punto>): LatLngBounds? {
    if (puntos.size < 2) return null
    val b = LatLngBounds.builder()
    puntos.forEach { b.include(it.latLng()) }
    return b.build()
}

@Composable
@GoogleMapComposable
fun LineaMapa(pts: List<Punto>, color: Color, anchoDp: Float, z: Float = 0f) {
    if (pts.size < 2) return
    val densidad = LocalDensity.current.density
    val ll = remember(pts) { pts.map { it.latLng() } }
    Polyline(points = ll, color = color, width = anchoDp * densidad,
        jointType = JointType.ROUND, startCap = RoundCap(), endCap = RoundCap(), zIndex = z)
}

/** Marcador con un ícono dibujado por la app. */
@Composable
@GoogleMapComposable
fun MarcaMapa(p: Punto, icono: Bitmap, anclaAbajo: Boolean = false, rotacion: Float = 0f, plano: Boolean = false, z: Float = 0f) {
    val estado = remember(p.latitud, p.longitud) { MarkerState(p.latLng()) }
    val desc = remember(icono) { BitmapDescriptorFactory.fromBitmap(icono) }
    Marker(state = estado, icon = desc, anchor = if (anclaAbajo) Offset(0.5f, 1f) else Offset(0.5f, 0.5f),
        rotation = rotacion, flat = plano, zIndex = z)
}

/** Círculo de color con borde blanco (radio en dp). */
fun circulo(color: Color, densidad: Float, radioDp: Float = 9f, bordeDp: Float = 2f): Bitmap {
    val r = (radioDp + bordeDp) * densidad
    val lado = (r * 2).toInt() + 2
    val bmp = Bitmap.createBitmap(lado, lado, Bitmap.Config.ARGB_8888)
    val c = android.graphics.Canvas(bmp)
    val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
    p.color = android.graphics.Color.WHITE; c.drawCircle(lado / 2f, lado / 2f, r, p)
    p.color = color.toArgb(); c.drawCircle(lado / 2f, lado / 2f, radioDp * densidad, p)
    return bmp
}

private const val ESTILO_NOCHE = """[
{"elementType":"geometry","stylers":[{"color":"#131A26"}]},
{"elementType":"labels.text.fill","stylers":[{"color":"#9AA4B8"}]},
{"elementType":"labels.text.stroke","stylers":[{"color":"#0A0D14"}]},
{"featureType":"administrative","elementType":"geometry.stroke","stylers":[{"color":"#28364E"}]},
{"featureType":"poi","elementType":"geometry","stylers":[{"color":"#172131"}]},
{"featureType":"poi.park","elementType":"geometry","stylers":[{"color":"#13261F"}]},
{"featureType":"road","elementType":"geometry","stylers":[{"color":"#28364E"}]},
{"featureType":"road","elementType":"geometry.stroke","stylers":[{"color":"#1C2638"}]},
{"featureType":"road.highway","elementType":"geometry","stylers":[{"color":"#34466A"}]},
{"featureType":"road","elementType":"labels.text.fill","stylers":[{"color":"#B8C1D3"}]},
{"featureType":"transit","elementType":"geometry","stylers":[{"color":"#1C2638"}]},
{"featureType":"water","elementType":"geometry","stylers":[{"color":"#0B1A2E"}]},
{"featureType":"water","elementType":"labels.text.fill","stylers":[{"color":"#4E6A92"}]}
]"""
