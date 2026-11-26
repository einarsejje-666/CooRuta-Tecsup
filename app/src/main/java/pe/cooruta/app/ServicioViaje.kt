package pe.cooruta.app

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import pe.cooruta.app.data.EstadoViaje
import pe.cooruta.app.data.Muestra
import pe.cooruta.app.data.PoliticaUbicacion
import pe.cooruta.app.data.Punto
import pe.cooruta.app.data.Resultado
import pe.cooruta.app.data.UbicacionEnVivo
import pe.cooruta.app.data.repositorio.ViajesRepositorio
import pe.cooruta.app.ui.viaje.EstadoSeguimiento
import pe.cooruta.app.ui.viaje.SeguimientoConductor

/**
 * Conductor con un viaje activo: publica su ubicación aunque la app esté en segundo plano, con la
 * pantalla apagada o en la navegación. Se detiene sola al finalizar el viaje, al cerrar sesión, con
 * el botón «Detener» de la notificación o tras [MAXIMO_MS]. Las reglas de envío (muestras nuevas,
 * intervalo, latido) son las de [SeguimientoConductor].
 */
class ServicioViaje : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val repo = ViajesRepositorio()
    private val seguimiento = SeguimientoConductor(scope, repo::publicarUbicacion, repo::pausarUbicacion)
    private var viajeId: String? = null
    private var tareas: Job? = null
    private var gestor: LocationManager? = null
    private val oyente = object : LocationListener {
        override fun onLocationChanged(l: android.location.Location) =
            seguimiento.alRecibirMuestra(Muestra(Punto(l.latitude, l.longitude), l.time))
        @Deprecated("Requerido en API < 30") override fun onStatusChanged(p: String?, s: Int, e: android.os.Bundle?) = Unit
        override fun onProviderEnabled(p: String) = Unit
        override fun onProviderDisabled(p: String) = Unit
    }
    private val sesion = FirebaseAuth.AuthStateListener { if (it.currentUser == null) terminar(escribirPausa = false) }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instancia = this
        scope.launch { seguimiento.estado.collect { _estado.value = it } }
        FirebaseAuth.getInstance().addAuthStateListener(sesion)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == DETENER) {
            (intent.getStringExtra(VIAJE) ?: viajeId)?.let { id ->
                getSharedPreferences(PREFERENCIAS, MODE_PRIVATE).edit().putString(DETENIDO, id).apply()
            }
            terminar(escribirPausa = true)
            return START_NOT_STICKY
        }
        val id = intent?.getStringExtra(VIAJE)
        if (id.isNullOrBlank() || detenidoPorConductor(this, id) ||
            !UbicacionEnVivo.tienePermiso(this) || FirebaseAuth.getInstance().currentUser == null) {
            stopSelf(); return START_NOT_STICKY
        }
        val tipo = if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0
        val iniciado = runCatching { ServiceCompat.startForeground(this, ID, notificacion(id), tipo) }.isSuccess
        if (!iniciado) { stopSelf(); return START_NOT_STICKY }
        if (id != viajeId) empezar(id)
        return START_NOT_STICKY
    }

    private fun empezar(id: String) {
        tareas?.cancel()
        if (viajeId != null) seguimiento.detener(escribirPausa = false)
        viajeId = id
        seguimiento.activar(id)
        escucharGps()
        tareas = scope.launch {
            // Termina solo cuando el servidor confirma que el viaje ya no admite ubicación.
            launch {
                repo.viajeEnVivo(id).collect { r ->
                    when (r) {
                        is Resultado.Exito -> if (!r.valor.desdeCache) {
                            val v = r.valor.valor
                            if (v == null || !EstadoViaje.admiteUbicacion(v.estado)) terminar(escribirPausa = false)
                        }
                        is Resultado.Error -> terminar(escribirPausa = false)
                    }
                }
            }
            launch { delay(MAXIMO_MS); terminar(escribirPausa = true) }
        }
    }

    @SuppressLint("MissingPermission")
    private fun escucharGps() {
        val g = gestor ?: getSystemService(LocationManager::class.java)?.also { gestor = it } ?: return
        runCatching { g.removeUpdates(oyente) }
        val proveedor = when {
            Build.VERSION.SDK_INT >= 31 && g.hasProvider(LocationManager.FUSED_PROVIDER) -> LocationManager.FUSED_PROVIDER
            g.isProviderEnabled(LocationManager.GPS_PROVIDER) -> LocationManager.GPS_PROVIDER
            else -> LocationManager.NETWORK_PROVIDER
        }
        runCatching { g.requestLocationUpdates(proveedor, PoliticaUbicacion.MUESTREO_MS, 0f, oyente, Looper.getMainLooper()) }
    }

    /** Deja de compartir. Con [escribirPausa] la última ubicación queda marcada como pausada. */
    private fun terminar(escribirPausa: Boolean) {
        val id = viajeId
        // La pausa va a la cola de Firestore de inmediato: no depende de que este servicio siga vivo.
        if (escribirPausa && id != null && _estado.value.ultimoEnvioMs != null) repo.encolarPausa(id)
        seguimiento.detener(escribirPausa = false)
        tareas?.cancel(); tareas = null
        viajeId = null
        gestor?.let { runCatching { it.removeUpdates(oyente) } }
        _estado.value = EstadoSeguimiento()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        FirebaseAuth.getInstance().removeAuthStateListener(sesion)
        gestor?.let { runCatching { it.removeUpdates(oyente) } }
        if (instancia === this) instancia = null
        if (viajeId != null) _estado.value = EstadoSeguimiento()
        scope.cancel()
        super.onDestroy()
    }

    private fun notificacion(id: String): android.app.Notification {
        val gestorN = getSystemService(NotificationManager::class.java)
        gestorN.createNotificationChannel(NotificationChannel(CANAL, "Viaje en curso", NotificationManager.IMPORTANCE_LOW))
        val abrir = PendingIntent.getActivity(this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE)
        val detener = PendingIntent.getService(this, 2,
            Intent(this, ServicioViaje::class.java).setAction(DETENER).putExtra(VIAJE, id),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return NotificationCompat.Builder(this, CANAL)
            .setSmallIcon(R.drawable.ic_aviso)
            .setContentTitle("Compartiendo tu ubicación con tus pasajeros")
            .setContentText("Se detiene sola al finalizar el viaje.")
            .setOngoing(true).setContentIntent(abrir)
            .addAction(0, "Detener", detener)
            .build()
    }

    companion object {
        private const val CANAL = "viaje"
        private const val ID = 7002
        private const val VIAJE = "viaje"
        private const val DETENER = "pe.cooruta.app.DETENER_VIAJE"
        private const val PREFERENCIAS = "servicio_viaje"
        private const val DETENIDO = "detenido_por_conductor"
        /** Tope por si el conductor olvida finalizar. */
        private const val MAXIMO_MS = 4 * 60 * 60_000L

        @Volatile private var instancia: ServicioViaje? = null
        private val _estado = MutableStateFlow(EstadoSeguimiento())
        /** Estado del envío (para la pantalla del viaje). */
        val estado: StateFlow<EstadoSeguimiento> = _estado.asStateFlow()

        private fun detenidoPorConductor(ctx: Context, viajeId: String): Boolean =
            ctx.getSharedPreferences(PREFERENCIAS, Context.MODE_PRIVATE).getString(DETENIDO, null) == viajeId

        /** Empieza (o sigue) a compartir la ubicación en [viajeId]. Llamar con la app en primer plano. */
        fun iniciar(ctx: Context, viajeId: String) {
            if (detenidoPorConductor(ctx, viajeId)) return
            if (_estado.value.activo && _estado.value.viajeId == viajeId) return
            runCatching {
                ContextCompat.startForegroundService(ctx, Intent(ctx, ServicioViaje::class.java).putExtra(VIAJE, viajeId))
            }
        }

        /** Deja de compartir (cerrar el viaje, finalizarlo, cambiar de cuenta). */
        fun detener(escribirPausa: Boolean) {
            val s = instancia ?: return
            s.scope.launch { s.terminar(escribirPausa) }
        }
    }
}
