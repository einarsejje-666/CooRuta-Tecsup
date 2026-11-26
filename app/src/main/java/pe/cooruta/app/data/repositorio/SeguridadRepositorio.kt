package pe.cooruta.app.data.repositorio

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.functions.FirebaseFunctions
import com.google.firebase.functions.FirebaseFunctionsException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import pe.cooruta.app.data.Resultado
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Contactos, bloqueos y reportes.
 * - `usuarios/{uid}/contactos/{otro}`: los agrega la función agregarContacto (grupo o viaje en común).
 * - `bloqueos/{bloqueador}_{bloqueado}`: los escribe quien bloquea; las reglas impiden pedir asiento entre ambos.
 * - `reportes/{id}`: solo se crean; se revisan desde la consola de Firebase.
 */
class SeguridadRepositorio(
    private val db: FirebaseFirestore = FirebaseFirestore.getInstance(),
    private val auth: FirebaseAuth = FirebaseAuth.getInstance()
) {
    data class Persona(val uid: String, val nombre: String)

    private val funciones get() = FirebaseFunctions.getInstance(HabitualesRepositorio.REGION)
    private val miUid: String? get() = auth.currentUser?.uid

    fun contactosEnVivo(): Flow<List<Persona>> = callbackFlow {
        val uid = miUid
        if (uid == null) { trySend(emptyList()); awaitClose { }; return@callbackFlow }
        val r = db.collection("usuarios").document(uid).collection("contactos").addSnapshotListener { s, _ ->
            if (s != null) trySend(s.documents.map { Persona(it.id, it.getString("nombre").orEmpty()) }.sortedBy { it.nombre.lowercase() })
        }
        awaitClose { r.remove() }
    }

    /** Devuelve el nombre de la persona agregada. */
    suspend fun agregarContacto(uid: String): Resultado<String> =
        llamar("agregar el contacto", "agregarContacto", mapOf("uid" to uid)) { it["nombre"] as? String ?: "" }

    suspend fun quitarContacto(uid: String): Resultado<Unit> = conRed("quitar el contacto") {
        db.collection("usuarios").document(miUid!!).collection("contactos").document(uid).delete().esperar()
    }

    fun bloqueadosEnVivo(): Flow<List<Persona>> = callbackFlow {
        val uid = miUid
        if (uid == null) { trySend(emptyList()); awaitClose { }; return@callbackFlow }
        val r = db.collection("bloqueos").whereEqualTo("bloqueadorId", uid).addSnapshotListener { s, _ ->
            if (s != null) trySend(s.documents.map { Persona(it.getString("bloqueadoId").orEmpty(), it.getString("nombre").orEmpty()) }
                .sortedBy { it.nombre.lowercase() })
        }
        awaitClose { r.remove() }
    }

    /** Ids de las personas que bloqueé (para ocultar sus rutas). */
    suspend fun idsBloqueados(): Set<String> = runCatching {
        db.collection("bloqueos").whereEqualTo("bloqueadorId", miUid ?: return emptySet()).get().esperar()
            .documents.mapNotNull { it.getString("bloqueadoId") }.toSet()
    }.getOrDefault(emptySet())

    /** Bloquea y, si era contacto, lo quita de la lista. */
    suspend fun bloquear(uid: String, nombre: String): Resultado<Unit> = conRed("bloquear") {
        val yo = miUid!!
        db.collection("bloqueos").document("${yo}_$uid").set(mapOf(
            "bloqueadorId" to yo, "bloqueadoId" to uid, "nombre" to nombre.trim().take(60),
            "creadoEn" to FieldValue.serverTimestamp())).esperar()
        runCatching { db.collection("usuarios").document(yo).collection("contactos").document(uid).delete().esperar() }
    }

    suspend fun desbloquear(uid: String): Resultado<Unit> = conRed("desbloquear") {
        db.collection("bloqueos").document("${miUid!!}_$uid").delete().esperar()
    }

    /** [reportadoId] vacío = problema general con la app. */
    suspend fun reportar(reportadoId: String, motivos: List<String>, detalle: String, contexto: String): Resultado<Unit> = conRed("enviar el reporte") {
        db.collection("reportes").add(mapOf(
            "autorId" to miUid!!, "reportadoId" to reportadoId, "motivos" to motivos.take(6),
            "detalle" to detalle.trim().take(500), "contexto" to contexto.take(100),
            "creadoEn" to FieldValue.serverTimestamp())).esperar()
    }

    private suspend fun conRed(accion: String, bloque: suspend () -> Any?): Resultado<Unit> {
        if (miUid == null) return Resultado.Error("Inicia sesión con Google.")
        return try {
            withTimeout(15_000) { bloque() }
            Resultado.Exito(Unit)
        } catch (e: TimeoutCancellationException) {
            Resultado.Error("Sin conexión: no pudimos $accion.")
        } catch (e: Exception) {
            Resultado.Error("No se pudo $accion. Intenta de nuevo.")
        }
    }

    private suspend fun <T> llamar(accion: String, nombre: String, datos: Map<String, Any?>, leer: (Map<*, *>) -> T): Resultado<T> {
        if (miUid == null) return Resultado.Error("Inicia sesión con Google.")
        return try {
            val r = withTimeout(25_000) {
                suspendCancellableCoroutine<Any?> { k ->
                    funciones.getHttpsCallable(nombre).call(datos)
                        .addOnSuccessListener { if (k.isActive) k.resume(it.data) }
                        .addOnFailureListener { if (k.isActive) k.resumeWithException(it) }
                }
            }
            Resultado.Exito(leer(r as? Map<*, *> ?: emptyMap<String, Any>()))
        } catch (e: TimeoutCancellationException) {
            Resultado.Error("Sin conexión: no pudimos $accion.")
        } catch (e: FirebaseFunctionsException) {
            Resultado.Error(when (e.code) {
                FirebaseFunctionsException.Code.UNAVAILABLE, FirebaseFunctionsException.Code.DEADLINE_EXCEEDED -> "Sin conexión: no pudimos $accion."
                FirebaseFunctionsException.Code.INTERNAL, FirebaseFunctionsException.Code.UNKNOWN -> "No se pudo $accion. Intenta de nuevo."
                else -> e.message ?: "No se pudo $accion."
            })
        } catch (e: Exception) {
            Resultado.Error(e.message ?: "No se pudo $accion.")
        }
    }
}

private suspend fun <T> com.google.android.gms.tasks.Task<T>.esperar(): T = suspendCancellableCoroutine { k ->
    addOnSuccessListener { if (k.isActive) k.resume(it) }
    addOnFailureListener { if (k.isActive) k.resumeWithException(it) }
}
