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
import kotlinx.coroutines.launch
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


    // ------------------------------------------------------------ grupos permanentes
    // Los escriben las Cloud Functions (crearGrupo, unirseGrupo…); aquí solo se leen y se llaman.

    data class GrupoResumen(
        val id: String, val nombre: String, val miembros: Int, val requiereAprobacion: Boolean,
        val tieneEnlace: Boolean, val soyAdmin: Boolean,
        /** Pedí entrar y espero la aprobación. */
        val pendiente: Boolean,
        /** Un administrador me invitó y debo aceptar o rechazar. */
        val invitado: Boolean = false
    ) { val activo: Boolean get() = !pendiente && !invitado }
    data class Integrante(val uid: String, val nombre: String, val admin: Boolean, val pendiente: Boolean, val invitado: Boolean = false)
    data class InvitacionGrupo(val grupoId: String, val nombre: String, val miembros: Int, val requiereAprobacion: Boolean, val estado: String?)

    val miUid: String? get() = auth.currentUser?.uid

    private fun refGrupo(gid: String) = db.collection("grupos").document(gid)

    private suspend fun resumen(gid: String, uid: String): GrupoResumen? {
        val g = refGrupo(gid).get().esperar()
        if (!g.exists()) return null
        val m = refGrupo(gid).collection("miembros").document(uid).get().esperar()
        return GrupoResumen(gid, g.getString("nombre").orEmpty(), (g.getLong("miembros") ?: 0L).toInt(),
            g.getBoolean("requiereAprobacion") == true, g.getString("codigo") != null,
            m.getString("rol") == "admin", m.getString("estado") == "pendiente", m.getString("estado") == "invitado")
    }

    /** Mis grupos (activos y solicitudes pendientes), según `membresias/{uid}`. */
    fun misGruposEnVivo(): Flow<List<GrupoResumen>> = callbackFlow {
        val uid = auth.currentUser?.uid
        if (uid == null) { trySend(emptyList()); awaitClose { }; return@callbackFlow }
        val r = db.collection("membresias").document(uid).addSnapshotListener { s, _ ->
            val ids = ((s?.get("grupos") as? List<*>).orEmpty() + (s?.get("pendientes") as? List<*>).orEmpty() +
                (s?.get("invitaciones") as? List<*>).orEmpty())
                .filterIsInstance<String>().distinct()
            launch {
                val lista = ids.mapNotNull { gid -> runCatching { resumen(gid, uid) }.getOrNull() }
                    .sortedWith(compareBy({ !it.invitado }, { it.pendiente }, { it.nombre.lowercase() }))
                trySend(lista)
            }
        }
        awaitClose { r.remove() }
    }

    /** Un grupo en vivo; null si ya no existe o ya no soy parte de él. */
    fun grupoEnVivo(gid: String): Flow<GrupoResumen?> = callbackFlow {
        val uid = auth.currentUser?.uid
        if (uid == null) { trySend(null); awaitClose { }; return@callbackFlow }
        fun recargar() = launch { trySend(runCatching { resumen(gid, uid) }.getOrNull()) }
        val r1 = refGrupo(gid).addSnapshotListener { _, _ -> recargar() }
        val r2 = refGrupo(gid).collection("miembros").document(uid).addSnapshotListener { _, _ -> recargar() }
        awaitClose { r1.remove(); r2.remove() }
    }

    /** Integrantes y solicitudes del grupo (solo lo ven sus integrantes activos). */
    fun integrantesEnVivo(gid: String): Flow<List<Integrante>> = callbackFlow {
        val r = refGrupo(gid).collection("miembros").addSnapshotListener { s, e ->
            if (e != null) { trySend(emptyList()); return@addSnapshotListener }
            if (s != null) trySend(s.documents.map {
                Integrante(it.id, it.getString("nombre").orEmpty(), it.getString("rol") == "admin",
                    it.getString("estado") == "pendiente", it.getString("estado") == "invitado")
            }.sortedWith(compareBy({ !it.admin }, { it.nombre.lowercase() })))
        }
        awaitClose { r.remove() }
    }

    suspend fun crearGrupo(nombre: String, requiereAprobacion: Boolean): Resultado<String> =
        llamar("crear el grupo", "crearGrupo", mapOf("nombre" to nombre, "requiereAprobacion" to requiereAprobacion)) { it["grupoId"] as? String ?: "" }

    /** Enlace vigente para compartir; con [renovar] crea otro y el anterior deja de funcionar. */
    suspend fun enlaceGrupo(gid: String, renovar: Boolean = false): Resultado<String> =
        llamar("obtener el enlace", "enlaceGrupo", mapOf("grupoId" to gid, "renovar" to renovar)) { it["enlace"] as? String ?: "" }

    suspend fun desactivarEnlace(gid: String): Resultado<Unit> =
        llamar("desactivar el enlace", "desactivarEnlaceGrupo", mapOf("grupoId" to gid)) { }

    suspend fun ajustesGrupo(gid: String, nombre: String? = null, requiereAprobacion: Boolean? = null): Resultado<Unit> =
        llamar("guardar los cambios", "ajustesGrupo", buildMap {
            put("grupoId", gid); nombre?.let { put("nombre", it) }; requiereAprobacion?.let { put("requiereAprobacion", it) }
        }) { }

    suspend fun verInvitacion(codigo: String): Resultado<InvitacionGrupo> =
        llamar("abrir la invitación", "verInvitacionGrupo", mapOf("codigo" to codigo)) {
            InvitacionGrupo(it["grupoId"] as? String ?: "", it["nombre"] as? String ?: "", (it["miembros"] as? Number)?.toInt() ?: 0,
                it["requiereAprobacion"] == true, it["estado"] as? String)
        }

    /** Devuelve (nombre del grupo, estado: "activo" o "pendiente"). */
    suspend fun unirseGrupo(codigo: String): Resultado<Pair<String, String>> =
        llamar("unirte al grupo", "unirseGrupo", mapOf("codigo" to codigo)) {
            (it["nombre"] as? String ?: "el grupo") to (it["estado"] as? String ?: "activo")
        }

    suspend fun responderSolicitud(gid: String, uid: String, aceptar: Boolean): Resultado<Unit> =
        llamar("responder la solicitud", "responderSolicitudGrupo", mapOf("grupoId" to gid, "uid" to uid, "aceptar" to aceptar)) { }

    suspend fun retirarIntegrante(gid: String, uid: String): Resultado<Unit> =
        llamar("retirar a esta persona", "retirarMiembroGrupo", mapOf("grupoId" to gid, "uid" to uid)) { }

    /** El administrador invita a contactos suyos; devuelve cuántos quedaron invitados. */
    suspend fun invitarContactos(gid: String, uids: List<String>): Resultado<Int> =
        llamar("invitar a tus contactos", "invitarContactosGrupo", mapOf("grupoId" to gid, "uids" to uids)) { (it["invitados"] as? Number)?.toInt() ?: 0 }

    /** Responde una invitación directa; devuelve el nombre del grupo. */
    suspend fun responderInvitacion(gid: String, aceptar: Boolean): Resultado<String> =
        llamar("responder la invitación", "responderInvitacionGrupo", mapOf("grupoId" to gid, "aceptar" to aceptar)) { it["nombre"] as? String ?: "el grupo" }

    suspend fun salirDeGrupo(gid: String): Resultado<Unit> =
        llamar("salir del grupo", "salirGrupo", mapOf("grupoId" to gid)) { }

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
