package pe.cooruta.app

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

/**
 * Avisos push (Cloud Functions → FCM): llegan aunque la app esté cerrada. Son mensajes de datos
 * con la misma clave y texto que los avisos locales, así que no se muestran dos veces.
 */
class ServicioMensajes : FirebaseMessagingService() {

    override fun onNewToken(token: String) = TokensPush.guardar(token)

    override fun onMessageReceived(m: RemoteMessage) {
        val d = m.data
        val clave = d["clave"] ?: return
        // Mensaje para otra cuenta que usó este teléfono: no se muestra.
        if (d["uid"] != FirebaseAuth.getInstance().currentUser?.uid) return
        // Chat abierto en pantalla: ya se está viendo.
        if (clave.endsWith("_msg") && pe.cooruta.app.ui.inicio.ChatAbierto.id == clave.removeSuffix("_msg")) return
        AvisosLocales.mostrar(applicationContext, clave.hashCode(), d["titulo"].orEmpty(), d["texto"].orEmpty())
    }
}

/** Token de este teléfono en `usuarios/{uid}/tokens/{token}` (solo lo lee su dueño y el servidor). */
object TokensPush {
    fun guardar(token: String) {
        val uid = FirebaseAuth.getInstance().currentUser?.uid ?: return
        FirebaseFirestore.getInstance().collection("usuarios").document(uid).collection("tokens").document(token)
            .set(mapOf("actualizadoEn" to FieldValue.serverTimestamp()))
    }

    /** Con sesión abierta: registra el token actual de este teléfono. */
    fun registrar() {
        if (FirebaseAuth.getInstance().currentUser == null) return
        runCatching { FirebaseMessaging.getInstance().token.addOnSuccessListener { guardar(it) } }
    }

    /** Sin sesión: invalida el token; el servidor borra el registro viejo al fallar el envío. */
    fun olvidar() {
        runCatching { FirebaseMessaging.getInstance().deleteToken() }
    }
}
