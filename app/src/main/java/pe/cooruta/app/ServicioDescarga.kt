package pe.cooruta.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.FileProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Descarga del APK de actualización en un servicio en primer plano: la notificación muestra el
 * avance y la descarga sigue aunque se cambie de app o se cierre CooRuta. Funciona con wifi o datos
 * (el gestor de descargas de Android retenía las descargas grandes por datos móviles).
 */
class ServicioDescarga : Service() {

    enum class Fase { NADA, PREPARANDO, DESCARGANDO, LISTO, ERROR }
    data class Estado(val fase: Fase = Fase.NADA, val bajados: Long = 0, val total: Long = 0, val version: Long = 0) {
        val porcentaje: Int get() = if (total > 0) (bajados * 100 / total).toInt().coerceIn(0, 100) else -1
    }

    companion object {
        private const val CANAL = "actualizacion"
        private const val ID_NOTIF = 4201
        private const val CANCELAR = "pe.cooruta.app.CANCELAR_DESCARGA"

        private val _estado = MutableStateFlow(Estado())
        val estado: StateFlow<Estado> = _estado

        /** APK de una versión (en la caché, compartido con el instalador por FileProvider). */
        fun archivo(ctx: Context, version: Long) =
            File(File(ctx.cacheDir, "actualizacion").apply { mkdirs() }, "cooruta-$version.apk")

        /** ¿Ya está descargado completo? */
        fun listo(ctx: Context, version: Long, bytes: Long): Boolean {
            val f = archivo(ctx, version)
            return f.exists() && f.length() > 0 && (bytes <= 0 || f.length() == bytes)
        }

        fun iniciar(ctx: Context, url: String, version: Long, bytes: Long) {
            if (_estado.value.fase in setOf(Fase.PREPARANDO, Fase.DESCARGANDO) && _estado.value.version == version) return
            _estado.value = Estado(Fase.PREPARANDO, version = version)
            val i = Intent(ctx, ServicioDescarga::class.java)
                .putExtra("url", url).putExtra("version", version).putExtra("bytes", bytes)
            androidx.core.content.ContextCompat.startForegroundService(ctx, i)
        }

        fun cancelar(ctx: Context) {
            _estado.value = Estado()
            runCatching { ctx.startService(Intent(ctx, ServicioDescarga::class.java).setAction(CANCELAR)) }
        }

        fun intentInstalar(ctx: Context, f: File): Intent =
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(FileProvider.getUriForFile(ctx, "${ctx.packageName}.archivos", f), "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)

        /** Abre el instalador de Android; false si no se pudo. */
        fun instalar(ctx: Context, version: Long): Boolean {
            val f = archivo(ctx, version)
            if (!f.exists()) return false
            return runCatching { ctx.startActivity(intentInstalar(ctx, f)) }.isSuccess
        }
    }

    private val alcance = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var trabajo: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == CANCELAR) {
            trabajo?.cancel()
            terminar(quitarNotificacion = true)
            return START_NOT_STICKY
        }
        val url = intent?.getStringExtra("url")
        val version = intent?.getLongExtra("version", 0L) ?: 0L
        val bytes = intent?.getLongExtra("bytes", 0L) ?: 0L
        crearCanal()
        ServiceCompat.startForeground(this, ID_NOTIF, notificacion(0, bytes, indeterminado = true),
            if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0)
        if (url.isNullOrBlank()) { terminar(quitarNotificacion = true); return START_NOT_STICKY }
        if (trabajo?.isActive == true) return START_NOT_STICKY
        trabajo = alcance.launch { descargar(url, version, bytes) }
        return START_NOT_STICKY
    }

    private fun descargar(url: String, version: Long, bytes: Long) {
        val destino = archivo(this, version)
        val parcial = File(destino.parentFile, destino.name + ".parte")
        val con = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 20_000; readTimeout = 30_000
            setRequestProperty("Accept-Encoding", "identity") // así los bytes contados son los del APK
        }
        try {
            if (con.responseCode !in 200..299) error("HTTP ${con.responseCode}")
            val total = con.contentLengthLong.takeIf { it > 0 } ?: bytes
            var bajados = 0L
            var ultimaNotif = 0L
            _estado.value = Estado(Fase.DESCARGANDO, 0, total, version)
            con.inputStream.use { entrada ->
                parcial.outputStream().use { salida ->
                    val buf = ByteArray(64 * 1024)
                    while (alcance.isActive) {
                        val n = entrada.read(buf)
                        if (n < 0) break
                        salida.write(buf, 0, n)
                        bajados += n
                        _estado.value = Estado(Fase.DESCARGANDO, bajados, total, version)
                        val ahora = System.currentTimeMillis()
                        if (ahora - ultimaNotif > 1_000) {
                            ultimaNotif = ahora
                            gestor().notify(ID_NOTIF, notificacion(bajados, total, indeterminado = total <= 0))
                        }
                    }
                }
            }
            if (!alcance.isActive || _estado.value.fase == Fase.NADA) { parcial.delete(); return }
            destino.delete()
            if (!parcial.renameTo(destino)) error("No se pudo guardar")
            _estado.value = Estado(Fase.LISTO, bajados, total, version)
            // Aviso final que se queda: tocarlo abre el instalador.
            val abrir = PendingIntent.getActivity(this, 0, intentInstalar(this, destino),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            gestor().notify(ID_NOTIF, NotificationCompat.Builder(this, CANAL)
                .setSmallIcon(R.drawable.ic_aviso)
                .setContentTitle("Actualización de CooRuta lista")
                .setContentText("Toca para instalar la nueva versión.")
                .setContentIntent(abrir).setAutoCancel(true).build())
            stopSelf()
        } catch (e: Exception) {
            parcial.delete()
            if (_estado.value.fase != Fase.NADA) _estado.value = Estado(Fase.ERROR, version = version)
            terminar(quitarNotificacion = true)
        } finally {
            con.disconnect()
        }
    }

    private fun terminar(quitarNotificacion: Boolean) {
        ServiceCompat.stopForeground(this, if (quitarNotificacion) ServiceCompat.STOP_FOREGROUND_REMOVE else ServiceCompat.STOP_FOREGROUND_DETACH)
        stopSelf()
    }

    override fun onDestroy() {
        alcance.cancel()
        super.onDestroy()
    }

    private fun gestor() = getSystemService(NotificationManager::class.java)

    private fun crearCanal() {
        if (Build.VERSION.SDK_INT >= 26) gestor().createNotificationChannel(
            NotificationChannel(CANAL, "Actualizaciones", NotificationManager.IMPORTANCE_LOW))
    }

    private fun notificacion(bajados: Long, total: Long, indeterminado: Boolean): android.app.Notification {
        val mb = { b: Long -> String.format(java.util.Locale.US, "%.1f", b / 1048576.0) }
        val p = if (total > 0) (bajados * 100 / total).toInt() else 0
        val abrirApp = packageManager.getLaunchIntentForPackage(packageName)?.let {
            PendingIntent.getActivity(this, 1, it, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        }
        return NotificationCompat.Builder(this, CANAL)
            .setSmallIcon(R.drawable.ic_aviso)
            .setContentTitle("Descargando actualización de CooRuta")
            .setContentText(if (total > 0) "$p %  ·  ${mb(bajados)} / ${mb(total)} MB" else "${mb(bajados)} MB")
            .setProgress(100, p, indeterminado)
            .setOngoing(true).setOnlyAlertOnce(true).setSilent(true)
            .setContentIntent(abrirApp)
            .build()
    }
}
