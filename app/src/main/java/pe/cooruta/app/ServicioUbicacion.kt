package pe.cooruta.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import pe.cooruta.app.data.UbicacionEnVivo

/**
 * Mantiene la ubicación del pasajero en tiempo real aunque la app esté en segundo plano.
 * Android exige una notificación fija mientras corre; tiene un botón «Detener».
 */
class ServicioUbicacion : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == DETENER || !UbicacionEnVivo.tienePermiso(this)) {
            UbicacionEnVivo.detener(); stopSelf(); return START_NOT_STICKY
        }
        val gestor = getSystemService(NotificationManager::class.java)
        gestor.createNotificationChannel(NotificationChannel(CANAL, "Ubicación en tiempo real", NotificationManager.IMPORTANCE_LOW))
        val abrir = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val detener = PendingIntent.getService(this, 1, Intent(this, ServicioUbicacion::class.java).setAction(DETENER), PendingIntent.FLAG_IMMUTABLE)
        val n = NotificationCompat.Builder(this, CANAL)
            .setSmallIcon(R.drawable.ic_aviso)
            .setContentTitle("CooRuta usa tu ubicación")
            .setContentText("Para recomendarte rutas y calcularlas desde donde estás.")
            .setOngoing(true).setContentIntent(abrir)
            .addAction(0, "Detener", detener)
            .build()
        val tipo = if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0
        runCatching { ServiceCompat.startForeground(this, ID, n, tipo) }.onFailure { stopSelf(); return START_NOT_STICKY }
        UbicacionEnVivo.iniciar(this)
        return START_STICKY
    }

    override fun onDestroy() {
        UbicacionEnVivo.detener()
        super.onDestroy()
    }

    companion object {
        private const val CANAL = "ubicacion"
        private const val ID = 7001
        private const val DETENER = "pe.cooruta.app.DETENER_UBICACION"

        /** ¿Tiene «Permitir todo el tiempo»? */
        fun enSegundoPlano(ctx: Context) = Build.VERSION.SDK_INT < 29 ||
            ContextCompat.checkSelfPermission(ctx, android.Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED

        fun iniciar(ctx: Context) {
            if (!UbicacionEnVivo.tienePermiso(ctx) || !enSegundoPlano(ctx)) return
            runCatching { ContextCompat.startForegroundService(ctx, Intent(ctx, ServicioUbicacion::class.java)) }
        }

        fun detener(ctx: Context) {
            runCatching { ctx.stopService(Intent(ctx, ServicioUbicacion::class.java)) }
        }
    }
}
