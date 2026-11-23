package pe.cooruta.app.data.repositorio

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.Query
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import pe.cooruta.app.data.Resultado
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Un mensaje del chat de una reserva. [enviandoLocal]: aún no confirmado por el servidor. */
data class Mensaje(val id: String, val autorId: String, val texto: String, val creadoMs: Long?, val enviandoLocal: Boolean)

/** Chat entre pasajero y conductor: `reservas/{id}/mensajes`. */
class ChatRepositorio(
    private val db: FirebaseFirestore = FirebaseFirestore.getInstance(),
    private val auth: FirebaseAuth = FirebaseAuth.getInstance()
) {
    val miUid: String? get() = auth.currentUser?.uid
    private fun mensajes(reservaId: String) = db.collection("reservas").document(reservaId).collection("mensajes")

    fun mensajesEnVivo(reservaId: String): Flow<Resultado<List<Mensaje>>> = callbackFlow {
        val r = mensajes(reservaId).orderBy("creadoEn", Query.Direction.ASCENDING).limitToLast(200)
            .addSnapshotListener { s, e ->
                if (e != null) {
                    trySend(Resultado.Error(if (e.code == FirebaseFirestoreException.Code.PERMISSION_DENIED)
                        "No tienes acceso a este chat." else "No se pudieron cargar los mensajes."))
                } else if (s != null) {
                    trySend(Resultado.Exito(s.documents.map { d ->
                        Mensaje(d.id, d.getString("autorId").orEmpty(), d.getString("texto").orEmpty(),
                            d.getTimestamp("creadoEn")?.toDate()?.time, d.metadata.hasPendingWrites())
                    }))
                }
            }
        awaitClose { r.remove() }
    }

    suspend fun enviar(reservaId: String, texto: String): Resultado<Unit> {
        val uid = miUid ?: return Resultado.Error("Inicia sesión con Google.")
        val limpio = texto.trim().take(500)
        if (limpio.isEmpty()) return Resultado.Error("Escribe un mensaje.")
        return try {
            withTimeout(15_000) {
                suspendCancellableCoroutine<Unit> { k ->
                    mensajes(reservaId).add(mapOf("autorId" to uid, "texto" to limpio, "creadoEn" to FieldValue.serverTimestamp()))
                        .addOnSuccessListener { if (k.isActive) k.resume(Unit) }
                        .addOnFailureListener { if (k.isActive) k.resumeWithException(it) }
                }
            }
            Resultado.Exito(Unit)
        } catch (e: TimeoutCancellationException) {
            Resultado.Error("Sin conexión: el mensaje se enviará cuando vuelva la señal.")
        } catch (e: FirebaseFirestoreException) {
            Resultado.Error(if (e.code == FirebaseFirestoreException.Code.PERMISSION_DENIED)
                "El chat está cerrado: la reserva terminó o se canceló." else "No se pudo enviar (${e.code.name}).")
        } catch (e: Exception) {
            Resultado.Error(e.message ?: "No se pudo enviar.")
        }
    }
}
