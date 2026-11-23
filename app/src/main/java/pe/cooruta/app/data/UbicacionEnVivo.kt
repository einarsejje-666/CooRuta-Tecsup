package pe.cooruta.app.data

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Looper
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Ubicación del teléfono en tiempo real mientras la app está abierta.
 * Una sola fuente para toda la app (Buscar, recomendaciones, rutas rápidas).
 */
object UbicacionEnVivo {
    private val _actual = MutableStateFlow<Punto?>(null)
    val actual: StateFlow<Punto?> = _actual.asStateFlow()

    private var gestor: LocationManager? = null
    private val oyente = object : LocationListener {
        override fun onLocationChanged(l: Location) { _actual.value = Punto(l.latitude, l.longitude) }
        @Deprecated("Requerido en API < 30") override fun onStatusChanged(p: String?, s: Int, e: android.os.Bundle?) = Unit
        override fun onProviderEnabled(p: String) = Unit
        override fun onProviderDisabled(p: String) = Unit
    }

    fun tienePermiso(ctx: Context) =
        ContextCompat.checkSelfPermission(ctx, android.Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(ctx, android.Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    /** Empieza a escuchar (cada ~5 s o 15 m). Sin permiso no hace nada. */
    @SuppressLint("MissingPermission")
    fun iniciar(ctx: Context) {
        if (gestor != null || !tienePermiso(ctx)) return
        val g = ctx.applicationContext.getSystemService(LocationManager::class.java) ?: return
        gestor = g
        if (_actual.value == null) {
            listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER)
                .mapNotNull { runCatching { g.getLastKnownLocation(it) }.getOrNull() }
                .maxByOrNull { it.time }?.let { _actual.value = Punto(it.latitude, it.longitude) }
        }
        val proveedores = buildList {
            if (Build.VERSION.SDK_INT >= 31 && g.hasProvider(LocationManager.FUSED_PROVIDER)) add(LocationManager.FUSED_PROVIDER)
            else {
                if (g.isProviderEnabled(LocationManager.GPS_PROVIDER)) add(LocationManager.GPS_PROVIDER)
                if (g.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) add(LocationManager.NETWORK_PROVIDER)
            }
        }
        proveedores.forEach { runCatching { g.requestLocationUpdates(it, 5_000L, 15f, oyente, Looper.getMainLooper()) } }
    }

    /** Deja de escuchar (al pasar la app a segundo plano). */
    fun detener() {
        gestor?.let { runCatching { it.removeUpdates(oyente) } }
        gestor = null
    }
}
