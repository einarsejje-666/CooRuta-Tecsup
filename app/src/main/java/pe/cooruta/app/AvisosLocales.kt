package pe.cooruta.app

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

/**
 * Avisos: los genera el teléfono mientras la app vive y también llegan por push (Cloud Functions)
 * con la app cerrada. Ambos usan la misma clave y texto: el mismo aviso no se muestra dos veces.
 */
object AvisosLocales {
    private const val CANAL = "reservas"
    private const val PREFS = "avisos"
    private const val CLAVE_ACTIVOS = "activos"

    fun permitido(ctx: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    fun activos(ctx: Context): Boolean = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(CLAVE_ACTIVOS, true)

    fun cambiarActivos(ctx: Context, valor: Boolean) =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(CLAVE_ACTIVOS, valor).apply()

    // ---------------------------------------------------------- historial (campana de la app)

    /** Una notificación guardada para verla luego en la campana. */
    data class Registro(val titulo: String, val texto: String, val ms: Long)

    private const val CLAVE_HIST = "historial"
    private const val CLAVE_VISTO = "vistoMs"
    private val _noLeidos = kotlinx.coroutines.flow.MutableStateFlow(-1)
    /** Cuántas no se han visto (-1 = aún no leído del teléfono). */
    val noLeidos: kotlinx.coroutines.flow.StateFlow<Int> = _noLeidos

    fun historial(ctx: Context): List<Registro> = runCatching {
        val arr = org.json.JSONArray(ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(CLAVE_HIST, "[]"))
        (0 until arr.length()).map { i -> arr.getJSONObject(i).let { Registro(it.optString("t"), it.optString("x"), it.optLong("m")) } }
    }.getOrDefault(emptyList())

    fun actualizarNoLeidos(ctx: Context) {
        val visto = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong(CLAVE_VISTO, 0)
        _noLeidos.value = historial(ctx).count { it.ms > visto }
    }

    fun marcarVistos(ctx: Context) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putLong(CLAVE_VISTO, System.currentTimeMillis()).apply()
        _noLeidos.value = 0
    }

    private fun guardar(ctx: Context, titulo: String, texto: String) =
        escribir(ctx, (listOf(Registro(titulo, texto, System.currentTimeMillis())) + historial(ctx)).take(50))

    private fun escribir(ctx: Context, lista: List<Registro>) {
        val arr = org.json.JSONArray()
        lista.forEach { arr.put(org.json.JSONObject().put("t", it.titulo).put("x", it.texto).put("m", it.ms)) }
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(CLAVE_HIST, arr.toString()).apply()
        actualizarNoLeidos(ctx)
    }

    /** Hora (ms) de la última vez que abrió las notificaciones: lo posterior se marca como nuevo. */
    fun vistoMs(ctx: Context): Long = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong(CLAVE_VISTO, 0)

    /** Borra una notificación del historial (no toca las de la bandeja del sistema). */
    fun eliminar(ctx: Context, r: Registro) = escribir(ctx, historial(ctx).filterNot { it == r })

    /** Devuelve una notificación borrada a su lugar (para «Deshacer»). */
    fun restaurar(ctx: Context, r: Registro) =
        escribir(ctx, (historial(ctx) + r).distinct().sortedByDescending { it.ms }.take(50))

    fun limpiar(ctx: Context) = escribir(ctx, emptyList())

    private const val CLAVE_RECIENTES = "recientes"
    private const val VENTANA_MS = 10 * 60_000L

    /** true si este mismo aviso (id + título + texto) ya se mostró hace poco; si no, lo anota. */
    @Synchronized
    private fun repetido(ctx: Context, id: Int, titulo: String, texto: String): Boolean {
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val ahora = System.currentTimeMillis()
        val firma = "$id|${(titulo + texto).hashCode()}"
        val previos = runCatching { org.json.JSONObject(prefs.getString(CLAVE_RECIENTES, "{}")) }.getOrDefault(org.json.JSONObject())
        val vigentes = org.json.JSONObject()
        previos.keys().forEach { k -> val ms = previos.optLong(k); if (ahora - ms < VENTANA_MS) vigentes.put(k, ms) }
        val ya = vigentes.has(firma)
        if (!ya) vigentes.put(firma, ahora)
        prefs.edit().putString(CLAVE_RECIENTES, vigentes.toString()).apply()
        return ya
    }

    fun mostrar(ctx: Context, id: Int, titulo: String, texto: String) {
        if (repetido(ctx, id, titulo, texto)) return
        // Siempre queda en el historial de la campana, aunque el sistema no muestre la notificación.
        guardar(ctx, titulo, texto)
        if (!activos(ctx) || !permitido(ctx)) return
        val gestor = ctx.getSystemService(NotificationManager::class.java)
        gestor.createNotificationChannel(
            NotificationChannel(CANAL, "Reservas y mensajes", NotificationManager.IMPORTANCE_HIGH)
        )
        val abrir = PendingIntent.getActivity(
            ctx, 0,
            Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val n = NotificationCompat.Builder(ctx, CANAL)
            .setSmallIcon(R.drawable.ic_aviso)
            .setContentTitle(titulo)
            .setContentText(texto)
            .setStyle(NotificationCompat.BigTextStyle().bigText(texto))
            .setAutoCancel(true)
            .setContentIntent(abrir)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        try { NotificationManagerCompat.from(ctx).notify(id, n) } catch (_: SecurityException) { }
    }
}
