package pe.cooruta.app.data.repositorio

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import pe.cooruta.app.data.Resultado
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** «Mis conductores» del pasajero: `usuarios/{uid}/conductores/{conductorId}` (solo él la ve). */
class ConductoresRepositorio(
    private val db: FirebaseFirestore = FirebaseFirestore.getInstance(),
    private val auth: FirebaseAuth = FirebaseAuth.getInstance()
) {
    private fun coleccion(uid: String) = db.collection("usuarios").document(uid).collection("conductores")

    /** Ids de mis conductores, en vivo (vacío sin sesión). */
    fun escuchar(): Flow<Set<String>> = callbackFlow {
        val uid = auth.currentUser?.uid
        if (uid == null) { trySend(emptySet()); close(); return@callbackFlow }
        val r = coleccion(uid).addSnapshotListener { s, _ -> if (s != null) trySend(s.documents.map { it.id }.toSet()) }
        awaitClose { r.remove() }
    }

    suspend fun agregar(conductorId: String): Resultado<Unit> =
        escribir("agregar al conductor") { uid -> coleccion(uid).document(conductorId).set(mapOf("agregadoEn" to FieldValue.serverTimestamp())) }

    suspend fun quitar(conductorId: String): Resultado<Unit> =
        escribir("quitar al conductor") { uid -> coleccion(uid).document(conductorId).delete() }

    private suspend fun escribir(accion: String, op: (String) -> com.google.android.gms.tasks.Task<Void>): Resultado<Unit> {
        val uid = auth.currentUser?.uid ?: return Resultado.Error("Inicia sesión con Google.")
        return try {
            // Se aplica al instante en el teléfono; si la red tarda, queda en cola.
            val t = op(uid)
            kotlinx.coroutines.withTimeoutOrNull(5_000) {
                suspendCancellableCoroutine<Unit> { k ->
                    t.addOnSuccessListener { if (k.isActive) k.resume(Unit) }
                        .addOnFailureListener { if (k.isActive) k.resumeWithException(it) }
                }
            }
            Resultado.Exito(Unit)
        } catch (e: Exception) {
            Resultado.Error("No se pudo $accion.")
        }
    }
}
