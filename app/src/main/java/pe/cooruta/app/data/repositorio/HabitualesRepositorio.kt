package pe.cooruta.app.data.repositorio

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.functions.FirebaseFunctions
import com.google.firebase.functions.FirebaseFunctionsException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import pe.cooruta.app.data.Coincidencia
import pe.cooruta.app.data.Resultado
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Acuerdo habitual (`habituales/{rutaId}_{pasajeroId}`): el pasajero viaja ciertos días de la semana
 * con un conductor. Estados: pendiente → aceptado | rechazado | cancelado. Las reservas de cada fecha
 * siguen siendo `reservas` normales (con `habitualId`).
 */
data class Habitual(
    val id: String,
    val rutaId: String,
    val conductorId: String,
    val pasajeroId: String,
    val nombrePasajero: String,
    val nombreConductor: String,
    /** 1 = lunes … 7 = domingo. */
    val dias: List<Int>,
    val subida: String,
    val bajada: String,
    val horaSalida: String,
    /** Hora estimada en que el conductor pasa por la subida ("07:12"). */
    val horaRecogida: String,
    val pasajeCentimos: Int,
    val mensaje: String,
    val estado: String,
    val creadoMs: Long
)

/** Lo que cambia el recorrido del conductor si acepta, y si le alcanza el cupo. */
data class DesvioHabitual(
    val segundosExtra: Int,
    val metrosExtra: Int,
    val minutosTotal: Int,
    val pasajerosEsosDias: Int,
    val horaRecogida: String,
    val diasSinCupo: List<String>,
    val fechasSinCupo: List<String>
)

/** Las escrituras van por Cloud Functions (validan cupos y crean las reservas); aquí solo se leen. */
class HabitualesRepositorio(
    private val db: FirebaseFirestore = FirebaseFirestore.getInstance(),
    private val auth: FirebaseAuth = FirebaseAuth.getInstance()
) {
    private val funciones: FirebaseFunctions get() = FirebaseFunctions.getInstance(REGION)

    /** Los acuerdos donde soy pasajero ([comoConductor] = false) o conductor; se reinicia al cambiar de cuenta. */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    fun enVivo(comoConductor: Boolean): Flow<Resultado<List<Habitual>>> = callbackFlow {
        val oyente = FirebaseAuth.AuthStateListener { trySend(it.currentUser?.uid) }
        auth.addAuthStateListener(oyente)
        awaitClose { auth.removeAuthStateListener(oyente) }
    }.distinctUntilChanged().flatMapLatest { uid ->
        if (uid == null) flowOf(Resultado.Exito(emptyList())) else callbackFlow {
            val registro = db.collection("habituales").whereEqualTo(if (comoConductor) "conductorId" else "pasajeroId", uid)
                .addSnapshotListener { s, e ->
                    if (e != null) trySend(Resultado.Error(
                        if (e.code == FirebaseFirestoreException.Code.PERMISSION_DENIED) "No tienes permiso para ver tus viajes habituales."
                        else "No se pudieron cargar tus viajes habituales."))
                    else if (s != null) trySend(Resultado.Exito(s.documents.map { d ->
                        Habitual(
                            id = d.id, rutaId = d.getString("rutaId").orEmpty(),
                            conductorId = d.getString("conductorId").orEmpty(), pasajeroId = d.getString("pasajeroId").orEmpty(),
                            nombrePasajero = d.getString("nombrePasajero") ?: "Pasajero",
                            nombreConductor = d.getString("nombreConductor") ?: "Conductor",
                            dias = (d.get("dias") as? List<*>).orEmpty().mapNotNull { (it as? Number)?.toInt() }.sorted(),
                            subida = (d.get("subida") as? Map<*, *>)?.get("nombre") as? String ?: "",
                            bajada = (d.get("bajada") as? Map<*, *>)?.get("nombre") as? String ?: "",
                            horaSalida = d.getString("horaSalida").orEmpty(),
                            horaRecogida = d.getString("horaRecogida").orEmpty(),
                            pasajeCentimos = d.getLong("pasajeCentimos")?.toInt() ?: 0,
                            mensaje = d.getString("mensaje").orEmpty(),
                            estado = d.getString("estado").orEmpty(),
                            creadoMs = d.getTimestamp("creadoEn")?.toDate()?.time ?: 0
                        )
                    }.sortedByDescending { it.creadoMs }))
                }
            awaitClose { registro.remove() }
        }
    }

    /** Pasajero: pide viajar los [dias] (1–7) con la ruta de [c]. Devuelve la hora de recogida estimada. */
    suspend fun solicitar(c: Coincidencia, dias: Set<Int>, subida: String, bajada: String, mensaje: String): Resultado<String> =
        llamar("enviar tu solicitud", "solicitarHabitual", mapOf(
            "rutaId" to c.ruta.id,
            "dias" to dias.sorted(),
            "subida" to mapOf("nombre" to subida.take(120), "latitud" to c.subida.latitud, "longitud" to c.subida.longitud),
            "bajada" to mapOf("nombre" to bajada.take(120), "latitud" to c.bajada.latitud, "longitud" to c.bajada.longitud),
            "kmCompartidos" to c.kmCompartidos,
            "mensaje" to mensaje.trim().take(500)
        )) { it["horaRecogida"] as? String ?: "" }

    /** Pasajero: retira una solicitud aún sin respuesta. */
    suspend fun cancelar(id: String): Resultado<Unit> = llamar("retirar tu solicitud", "cancelarHabitual", mapOf("id" to id)) { }

    /** Conductor: desvío y cupo antes de responder. */
    suspend fun desvio(id: String): Resultado<DesvioHabitual> = llamar("calcular el desvío", "desvioHabitual", mapOf("id" to id)) { m ->
        DesvioHabitual(
            segundosExtra = (m["segundosExtra"] as? Number)?.toInt() ?: 0,
            metrosExtra = (m["metrosExtra"] as? Number)?.toInt() ?: 0,
            minutosTotal = (m["minutosTotal"] as? Number)?.toInt() ?: 0,
            pasajerosEsosDias = (m["pasajerosEsosDias"] as? Number)?.toInt() ?: 0,
            horaRecogida = m["horaRecogida"] as? String ?: "",
            diasSinCupo = (m["diasSinCupo"] as? List<*>).orEmpty().filterIsInstance<String>(),
            fechasSinCupo = (m["fechasSinCupo"] as? List<*>).orEmpty().filterIsInstance<String>()
        )
    }

    /**
     * Pasajero o conductor: termina el acuerdo desde [desde] ("yyyy-MM-dd"; null = hoy). Cancela sus
     * reservas desde esa fecha (no la de hoy si el viaje ya salió). Devuelve cuántas canceló.
     */
    suspend fun terminar(id: String, desde: String? = null): Resultado<Int> =
        llamar("terminar el acuerdo", "terminarHabitual", mapOf("id" to id, "desde" to desde)) { m ->
            (m["canceladas"] as? Number)?.toInt() ?: 0
        }

    /** Ids de los acuerdos activos o por responder de una ruta propia (para no borrarla ni pausarla). */
    suspend fun activosDeRuta(rutaId: String): Resultado<List<Habitual>> {
        val uid = auth.currentUser?.uid ?: return Resultado.Error("Inicia sesión con Google.")
        return try {
            val docs = withTimeout(15_000) {
                suspendCancellableCoroutine<List<com.google.firebase.firestore.DocumentSnapshot>> { k ->
                    db.collection("habituales").whereEqualTo("conductorId", uid).whereEqualTo("rutaId", rutaId).get()
                        .addOnSuccessListener { if (k.isActive) k.resume(it.documents) }
                        .addOnFailureListener { if (k.isActive) k.resumeWithException(it) }
                }
            }
            Resultado.Exito(docs.filter { it.getString("estado") in setOf("aceptado", "pendiente") }.map {
                Habitual(it.id, rutaId, uid, it.getString("pasajeroId").orEmpty(), it.getString("nombrePasajero") ?: "Pasajero",
                    "", emptyList(), "", "", "", "", 0, "", it.getString("estado").orEmpty(), 0)
            })
        } catch (e: Exception) {
            Resultado.Error("No pudimos revisar tus pasajeros habituales. Revisa tu conexión.")
        }
    }

    /** Conductor: acepta (crea las reservas de lo que queda de la semana) o rechaza. Devuelve cuántas fechas se reservaron. */
    suspend fun responder(id: String, aceptar: Boolean): Resultado<Int> =
        llamar("responder la solicitud", "responderHabitual", mapOf("id" to id, "aceptar" to aceptar)) { m ->
            (m["fechas"] as? List<*>)?.size ?: 0
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
            // Los mensajes de las funciones ya están escritos para el usuario.
            Resultado.Error(when (e.code) {
                FirebaseFunctionsException.Code.UNAVAILABLE, FirebaseFunctionsException.Code.DEADLINE_EXCEEDED ->
                    "Sin conexión: no pudimos $accion."
                FirebaseFunctionsException.Code.INTERNAL, FirebaseFunctionsException.Code.UNKNOWN -> "No se pudo $accion. Intenta de nuevo."
                else -> e.message ?: "No se pudo $accion."
            })
        } catch (e: Exception) {
            Resultado.Error(e.message ?: "No se pudo $accion.")
        }
    }

    companion object { const val REGION = "southamerica-west1" }
}
