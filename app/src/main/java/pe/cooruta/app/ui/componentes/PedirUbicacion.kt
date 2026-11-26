package pe.cooruta.app.ui.componentes

import android.Manifest
import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.google.android.gms.common.api.ResolvableApiException
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.LocationSettingsRequest
import com.google.android.gms.location.Priority
import pe.cooruta.app.data.UbicacionEnVivo

/**
 * Lo necesario para usar la ubicación actual, con las ventanas nativas de Android:
 * 1) el permiso de ubicación, si falta; 2) «Activar la ubicación del dispositivo», si el GPS está apagado.
 * [pedir] avisa al terminar (haya aceptado o no); cada pantalla vuelve a leer lo que necesite.
 */
class PedidoUbicacion internal constructor(private val iniciar: (() -> Unit) -> Unit) {
    fun pedir(alTerminar: () -> Unit = {}) = iniciar(alTerminar)
}

/** ¿Está encendida la ubicación del dispositivo? */
fun ubicacionActivada(ctx: Context): Boolean = runCatching {
    androidx.core.location.LocationManagerCompat.isLocationEnabled(
        ctx.getSystemService(Context.LOCATION_SERVICE) as android.location.LocationManager)
}.getOrDefault(true)

/** Texto del aviso destacado de ubicación (exigido por Google Play antes de pedir el permiso). */
const val TEXTO_AVISO_UBICACION =
    "CooRuta recoge datos de tu ubicación para mostrarte rutas y viajes cerca de ti y, durante un viaje, " +
    "para compartir tu posición en tiempo real con las personas de ese viaje.\n\n" +
    "Si eliges «Permitir todo el tiempo», la ubicación se sigue usando aunque la app esté cerrada o no la estés " +
    "usando, por ejemplo con la pantalla bloqueada o mientras usas la navegación. Mientras se usa verás una " +
    "notificación fija y puedes detenerla cuando quieras."

/** Aviso destacado: se muestra antes de la ventana de permiso de Android y antes de abrir Ajustes. */
@Composable
fun AvisoUbicacion(alContinuar: () -> Unit, alCancelar: () -> Unit) {
    AlertDialog(
        onDismissRequest = alCancelar,
        title = { Text("CooRuta usa tu ubicación") },
        text = { Text(TEXTO_AVISO_UBICACION) },
        confirmButton = { TextButton(onClick = alContinuar) { Text("Continuar") } },
        dismissButton = { TextButton(onClick = alCancelar) { Text("Ahora no") } }
    )
}

@Composable
fun rememberPedidoUbicacion(): PedidoUbicacion {
    val ctx = LocalContext.current
    val pendiente = remember { arrayOfNulls<() -> Unit>(1) }
    fun terminar() { pendiente[0]?.invoke(); pendiente[0] = null }

    val activar = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { terminar() }

    fun revisarGps() {
        if (ubicacionActivada(ctx)) { terminar(); return }
        val pedido = LocationSettingsRequest.Builder()
            .addLocationRequest(LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 10_000L).build())
            .setAlwaysShow(true)
            .build()
        LocationServices.getSettingsClient(ctx).checkLocationSettings(pedido)
            .addOnSuccessListener { terminar() }
            .addOnFailureListener { e ->
                if (e is ResolvableApiException) runCatching { activar.launch(IntentSenderRequest.Builder(e.resolution).build()) }
                    .onFailure { terminar() }
                else terminar()
            }
    }

    val permiso = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { r ->
        if (r.values.any { it }) revisarGps() else terminar()
    }

    var aviso by remember { mutableStateOf(false) }
    if (aviso) AvisoUbicacion(
        alContinuar = {
            aviso = false
            permiso.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
        },
        alCancelar = { aviso = false; terminar() }
    )

    return remember {
        PedidoUbicacion { alTerminar ->
            pendiente[0] = alTerminar
            if (UbicacionEnVivo.tienePermiso(ctx)) revisarGps()
            else aviso = true
        }
    }
}
