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
import pe.cooruta.app.data.Punto
import pe.cooruta.app.data.Resultado
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Grupo del conductor: invitaciones de un solo uso por enlace y sus integrantes.
 * - `invitacionesGrupo/{codigo}` y `miembrosGrupo/{conductorId}_{uid}`: solo las escriben las funciones.
 * - `rutasGrupo/{conductorId}`: tramos de sus rutas privadas para el grupo (lo escribe el conductor).
 */
class GrupoRepositorio(
    private val db: FirebaseFirestore = FirebaseFirestore.getInstance(),
    private val auth: FirebaseAuth = FirebaseAuth.getInstance()
) {
    data class Miembro(val docId: String, val uid: String, val nombre: String)

    private val funciones get() = FirebaseFunctions.getInstance(HabitualesRepositorio.REGION)

    /** Conductor: quienes están en su grupo. */
    fun miembrosEnVivo(): Flow<List<Miembro>> = callbackFlow {
        val uid = auth.currentUser?.uid
        if (uid == null) { trySend(emptyList()); awaitClose { }; return@callbackFlow }
        val r = db.collection("miembrosGrupo").whereEqualTo("conductorId", uid)
            .addSnapshotListener { s, _ ->
                if (s != null) trySend(s.documents.map {
                    Miembro(it.id, it.getString("miembroId").orEmpty(), it.getString("nombre").orEmpty())
                }.sortedBy { it.nombre.lowercase() })
            }
        awaitClose { r.remove() }
    }

    /** Conductor: crea una invitación y devuelve el enlace para compartir. */
    suspend fun crearInvitacion(): Resultado<String> =
        llamar("crear la invitación", "crearInvitacionGrupo", emptyMap()) { it["enlace"] as? String ?: "" }

    /** Invitado: se une con el código; devuelve el nombre del conductor. */
    suspend fun aceptar(codigo: String): Resultado<String> =
        llamar("unirte al grupo", "aceptarInvitacionGrupo", mapOf("codigo" to codigo)) { it["nombreConductor"] as? String ?: "el conductor" }

    /** Conductor quita a alguien, o el integrante sale del grupo. */
    suspend fun quitar(docId: String): Resultado<Unit> = try {
        withTimeout(15_000) { db.collection("miembrosGrupo").document(docId).delete().esperar() }
        Resultado.Exito(Unit)
    } catch (e: Exception) {
        Resultado.Error("No se pudo quitar. Revisa tu conexión.")
    }

    /**
     * Conductor: publica para su grupo el recorrido completo de cada ruta (de origen a destino).
     * Solo lo leen él y sus integrantes, que son personas de su confianza.
     */
    suspend fun sincronizarMisRutas() {
        val uid = auth.currentUser?.uid ?: return
        runCatching {
            val privadas = db.collection("rutasPrivadas").whereEqualTo("conductorId", uid).get().esperar()
            val rutas = privadas.documents.associate { d ->
                val pts = puntos(d.get("recorrido"))
                d.id to reducirPuntos(pts, 200).map { mapOf("lat" to it.latitud, "lng" to it.longitud) }
            }.filterValues { it.size >= 2 }
            db.collection("rutasGrupo").document(uid)
                .set(mapOf("rutas" to rutas, "actualizadoEn" to FieldValue.serverTimestamp())).esperar()
        }
    }

    /** Pasajero: rutaId → tramo, de las rutas de los conductores cuyos grupos integra. */
    suspend fun recorridosDeMisGrupos(): Map<String, List<Punto>> {
        val uid = auth.currentUser?.uid ?: return emptyMap()
        return runCatching {
            val conductores = db.collection("miembrosGrupo").whereEqualTo("miembroId", uid).get().esperar()
                .documents.mapNotNull { it.getString("conductorId") }
            conductores.flatMap { cid ->
                val d = runCatching { db.collection("rutasGrupo").document(cid).get().esperar() }.getOrNull()
                (d?.get("rutas") as? Map<*, *>).orEmpty().mapNotNull { (k, v) ->
                    (k as? String)?.let { it to puntos(v) }
                }
            }.toMap()
        }.getOrDefault(emptyMap())
    }

    private fun puntos(v: Any?): List<Punto> = (v as? List<*>).orEmpty().mapNotNull { m ->
        val mm = m as? Map<*, *> ?: return@mapNotNull null
        val la = (mm["lat"] as? Number)?.toDouble() ?: return@mapNotNull null
        val ln = (mm["lng"] as? Number)?.toDouble() ?: return@mapNotNull null
        Punto(la, ln)
    }

    private fun punto(v: Any?): Punto? {
        val m = v as? Map<*, *> ?: return null
        val la = ((m["latitud"] ?: m["lat"]) as? Number)?.toDouble() ?: return null
        val ln = ((m["longitud"] ?: m["lng"]) as? Number)?.toDouble() ?: return null
        return Punto(la, ln)
    }

    private suspend fun <T> llamar(accion: String, nombre: String, datos: Map<String, Any?>, leer: (Map<*, *>) -> T): Resultado<T> {
        if (auth.currentUser == null) return Resultado.Error("Inicia sesión con Google.")
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

/** Espera una tarea de Firebase (sin depender de kotlinx-coroutines-play-services). */
private suspend fun <T> com.google.android.gms.tasks.Task<T>.esperar(): T = suspendCancellableCoroutine { k ->
    addOnSuccessListener { if (k.isActive) k.resume(it) }
    addOnFailureListener { if (k.isActive) k.resumeWithException(it) }
}
