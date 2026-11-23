package pe.cooruta.app.data.repositorio

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.MetadataChanges
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import pe.cooruta.app.data.EstadoParada
import pe.cooruta.app.data.EstadoViaje
import pe.cooruta.app.data.Muestra
import pe.cooruta.app.data.Punto
import pe.cooruta.app.data.Resultado
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Una salida del conductor: una por ruta y fecha (`viajes/<rutaId>_<fecha>`). */
data class Viaje(
    val id: String,
    val rutaId: String,
    val fecha: String,
    val estado: String,
    val salidaMs: Long? = null,
    val iniciadoMs: Long? = null,
    val finalizadoMs: Long? = null
)

/**
 * Última ubicación publicada por el conductor. [tomadaMs]: hora de la muestra GPS; [enMs]: cuándo la
 * recibió el servidor. [activa] = false cuando pausó el seguimiento.
 */
data class UbicacionViaje(val punto: Punto, val enMs: Long?, val activa: Boolean, val tomadaMs: Long? = null)

/** Datos privados de la ruta que solo lee su conductor: destino exacto y recorrido original. */
data class PrivadoRuta(val destino: Punto?, val recorrido: List<Punto>)

/** Un dato observado y si viene de la caché local (sin confirmación del servidor). */
data class Observado<T>(val valor: T?, val desdeCache: Boolean)

private const val ESPERA_VIAJE_MS = 15_000L

/**
 * Viaje en curso. Solo el conductor dueño cambia su viaje, sus paradas y su ubicación.
 * El pasajero lee el viaje, la ubicación (mientras su reserva siga aceptada y el viaje no
 * termine) y únicamente su propia parada. Nada de esto toca reservas ni asientos.
 */
class ViajesRepositorio(
    private val db: FirebaseFirestore = FirebaseFirestore.getInstance(),
    private val auth: FirebaseAuth = FirebaseAuth.getInstance()
) {
    private fun hoyTexto() = java.time.LocalDate.now().toString()
    private fun viaje(id: String) = db.collection("viajes").document(id)
    private fun ubicacion(id: String) = viaje(id).collection("ubicacion").document("actual")
    private fun parada(viajeId: String, reservaId: String) = viaje(viajeId).collection("paradas").document(reservaId)

    fun idViaje(rutaId: String, fecha: String = hoyTexto()) = "${rutaId}_$fecha"

    /**
     * Crea la salida de hoy para [rutaId] o devuelve la que ya existe: dobles toques y
     * reintentos usan el mismo documento (id fijo + transacción).
     */
    suspend fun iniciarSalida(rutaId: String): Resultado<String> {
        val uid = auth.currentUser?.uid ?: return Resultado.Error("Inicia sesión con Google.")
        val id = idViaje(rutaId)
        return conRed("iniciar la salida") {
            transaccion { tx ->
                val actual = tx.get(viaje(id))
                if (actual.exists()) {
                    if (actual.getString("conductorId") != uid) throw IllegalStateException("Esta salida no es tuya.")
                } else {
                    tx.set(viaje(id), mapOf(
                        "conductorId" to uid, "rutaId" to rutaId, "fecha" to hoyTexto(),
                        "estado" to EstadoViaje.PREPARANDO,
                        "creadoEn" to FieldValue.serverTimestamp(), "actualizadoEn" to FieldValue.serverTimestamp()
                    ))
                }
            }
            id
        }
    }

    /** Cambia el estado del viaje. Repetir el mismo estado no escribe nada. */
    /** Conductor: estado de la salida de hoy de [rutaId]; null si aún no se inició. */
    suspend fun estadoDeHoy(rutaId: String): Resultado<String?> = conRed("leer la salida de hoy") {
        suspendCancellableCoroutine<String?> { k ->
            viaje(idViaje(rutaId)).get()
                .addOnSuccessListener { d -> if (k.isActive) k.resume(d.getString("estado")) }
                .addOnFailureListener { if (k.isActive) k.resumeWithException(it) }
        }
    }

    suspend fun cambiarEstado(id: String, nuevo: String): Resultado<Unit> = conRed("actualizar el viaje") {
        transaccion { tx ->
            val actual = tx.get(viaje(id)).getString("estado") ?: throw IllegalStateException("El viaje no existe.")
            if (actual == nuevo) return@transaccion
            if (!EstadoViaje.puedePasar(actual, nuevo)) throw IllegalStateException("El viaje ya está en otro estado.")
            val cambios = mutableMapOf<String, Any>("estado" to nuevo, "actualizadoEn" to FieldValue.serverTimestamp())
            when (nuevo) {
                EstadoViaje.SALIENDO -> cambios["salidaEn"] = FieldValue.serverTimestamp()
                EstadoViaje.EN_CURSO -> cambios["iniciadoEn"] = FieldValue.serverTimestamp()
                EstadoViaje.FINALIZADO -> {
                    cambios["finalizadoEn"] = FieldValue.serverTimestamp()
                    tx.delete(ubicacion(id)) // al terminar no queda la última ubicación guardada
                }
            }
            tx.update(viaje(id), cambios)
        }
    }

    /** Marca la recogida de un pasajero. Repetir o retroceder no escribe nada. */
    suspend fun marcarParada(viajeId: String, s: RutasRepositorio.Solicitud, nuevo: String): Resultado<Unit> =
        conRed("actualizar la recogida") {
            val uid = auth.currentUser?.uid ?: throw IllegalStateException("Inicia sesión con Google.")
            transaccion { tx ->
                val ref = parada(viajeId, s.id)
                val actual = tx.get(ref).getString("estado")
                if (actual == nuevo || !EstadoParada.puedePasar(actual, nuevo)) return@transaccion
                if (actual == null) tx.set(ref, mapOf(
                    "pasajeroId" to s.pasajeroId, "conductorId" to uid,
                    "estado" to nuevo, "actualizadoEn" to FieldValue.serverTimestamp()
                ))
                else tx.update(ref, mapOf("estado" to nuevo, "actualizadoEn" to FieldValue.serverTimestamp()))
            }
        }

    /** Publica la ubicación real del conductor (solo se llama con la pantalla del viaje activa). */
    suspend fun publicarUbicacion(id: String, m: Muestra): Resultado<Unit> = conRed("compartir tu ubicación") {
        esperar(ubicacion(id).set(mapOf(
            "latitud" to m.punto.latitud, "longitud" to m.punto.longitud,
            // Hora de la muestra (GPS) separada de la recepción en el servidor.
            "tomadaEn" to com.google.firebase.Timestamp(java.util.Date(m.tomadaMs)),
            "en" to FieldValue.serverTimestamp(), "activa" to true
        )))
    }

    /** Pasajero: publica su propia ubicación en el viaje (la ve solo el conductor). Sin esperar respuesta. */
    fun publicarMiUbicacion(viajeId: String, p: Punto) {
        val uid = auth.currentUser?.uid ?: return
        viaje(viajeId).collection("pasajerosUbicacion").document(uid).set(mapOf(
            "latitud" to p.latitud, "longitud" to p.longitud, "en" to FieldValue.serverTimestamp()))
    }

    /** Pasajero: deja de compartir. Borra su ubicación del viaje para que no quede la última posición. */
    fun quitarMiUbicacion(viajeId: String) {
        val uid = auth.currentUser?.uid ?: return
        viaje(viajeId).collection("pasajerosUbicacion").document(uid).delete()
    }

    /** Conductor: ubicación en vivo de cada pasajero del viaje (uid → punto). Las viejas no se muestran como actuales. */
    fun pasajerosUbicacionEnVivo(viajeId: String): Flow<Map<String, Punto>> = callbackFlow {
        val r = viaje(viajeId).collection("pasajerosUbicacion").addSnapshotListener { s, e ->
            if (e != null) { close(); return@addSnapshotListener }
            if (s != null) trySend(s.documents.mapNotNull { d ->
                // Tolera un poco de desfase de reloj; más vieja que esto ya no es «ahora».
                val en = d.getTimestamp("en")?.toDate()?.time ?: return@mapNotNull null
                if (System.currentTimeMillis() - en > 2 * pe.cooruta.app.data.PoliticaUbicacion.VIGENCIA_MS) return@mapNotNull null
                val la = d.getDouble("latitud") ?: return@mapNotNull null
                val ln = d.getDouble("longitud") ?: return@mapNotNull null
                d.id to Punto(la, ln)
            }.toMap())
        }
        awaitClose { r.remove() }
    }

    /** Al salir de la pantalla: la última ubicación queda marcada como seguimiento pausado. */
    suspend fun pausarUbicacion(id: String): Resultado<Unit> = conRed("pausar el seguimiento") {
        esperar(ubicacion(id).update(mapOf("activa" to false, "en" to FieldValue.serverTimestamp())))
    }

    /**
     * Sin esperar respuesta: deja la pausa en la cola de escrituras de Firestore (que la envía al
     * recuperar la red). No hay confirmación; si nunca llega, la ubicación se verá desactualizada.
     */
    fun encolarPausa(id: String) {
        ubicacion(id).update(mapOf("activa" to false, "en" to FieldValue.serverTimestamp()))
    }

    /**
     * Conductor: destino y recorrido original de su ruta (`rutasPrivadas`, solo el dueño). No se
     * copia a ningún documento que lea el pasajero. Rutas antiguas sin parte privada: error.
     */
    suspend fun privadoDeRuta(rutaId: String): Resultado<PrivadoRuta> = conRed("leer tu ruta") {
        val d = suspendCancellableCoroutine<DocumentSnapshot> { k ->
            db.collection("rutasPrivadas").document(rutaId).get()
                .addOnSuccessListener { if (k.isActive) k.resume(it) }
                .addOnFailureListener { if (k.isActive) k.resumeWithException(it) }
        }
        val destino = (d.get("destino") as? Map<*, *>)?.let { m ->
            val lat = (m["latitud"] as? Number)?.toDouble(); val lng = (m["longitud"] as? Number)?.toDouble()
            if (lat != null && lng != null) Punto(lat, lng) else null
        }
        val recorrido = (d.get("recorrido") as? List<*>).orEmpty().mapNotNull { m ->
            val mapa = m as? Map<*, *> ?: return@mapNotNull null
            val lat = (mapa["lat"] as? Number)?.toDouble() ?: return@mapNotNull null
            val lng = (mapa["lng"] as? Number)?.toDouble() ?: return@mapNotNull null
            Punto(lat, lng)
        }
        PrivadoRuta(destino, recorrido)
    }

    fun viajeEnVivo(id: String): Flow<Resultado<Observado<Viaje>>> = enSesion {
        escuchar(viaje(id)) { d -> if (!d.exists()) null else Viaje(
            id = d.id, rutaId = d.getString("rutaId").orEmpty(), fecha = d.getString("fecha").orEmpty(),
            estado = d.getString("estado").orEmpty(),
            salidaMs = d.getTimestamp("salidaEn")?.toDate()?.time,
            iniciadoMs = d.getTimestamp("iniciadoEn")?.toDate()?.time,
            finalizadoMs = d.getTimestamp("finalizadoEn")?.toDate()?.time
        ) }
    }

    fun ubicacionEnVivo(id: String): Flow<Resultado<Observado<UbicacionViaje>>> = enSesion {
        escuchar(ubicacion(id)) { d ->
            val lat = d.getDouble("latitud"); val lng = d.getDouble("longitud")
            if (!d.exists() || lat == null || lng == null) null
            else UbicacionViaje(Punto(lat, lng), d.getTimestamp("en")?.toDate()?.time, d.getBoolean("activa") == true,
                // Documentos anteriores sin hora de muestra: se usa la de recepción.
                (d.getTimestamp("tomadaEn") ?: d.getTimestamp("en"))?.toDate()?.time)
        }
    }

    /** Conductor: estado de recogida de cada reserva (id de reserva → estado). */
    fun paradasEnVivo(viajeId: String): Flow<Resultado<Map<String, String>>> = enSesion { uid ->
        callbackFlow {
            val registro = viaje(viajeId).collection("paradas").whereEqualTo("conductorId", uid)
                .addSnapshotListener { s, e ->
                    if (e != null) trySend(Resultado.Error(mensajeLectura(e)))
                    else if (s != null) trySend(Resultado.Exito(s.documents.associate { it.id to it.getString("estado").orEmpty() }))
                }
            awaitClose { registro.remove() }
        }
    }

    /** Conductor: id de reserva → hora (ms) en que marcó «Llegué», para la espera de «No se presentó». */
    fun llegadasEnVivo(viajeId: String): Flow<Map<String, Long>> = enSesion { uid ->
        callbackFlow {
            val registro = viaje(viajeId).collection("paradas").whereEqualTo("conductorId", uid)
                .addSnapshotListener { s, _ ->
                    if (s != null) trySend(s.documents.mapNotNull { d ->
                        if (d.getString("estado") != EstadoParada.LLEGUE) null
                        else d.getTimestamp("actualizadoEn", DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
                            ?.toDate()?.time?.let { d.id to it }
                    }.toMap())
                }
            awaitClose { registro.remove() }
        }
    }

    /** Conductor: tras la espera, marca que el pasajero no se presentó (lo valida y aplica la función). */
    suspend fun marcarNoSePresento(viajeId: String, reservaId: String): Resultado<Unit> {
        if (auth.currentUser == null) return Resultado.Error("Inicia sesión con Google.")
        return try {
            withTimeout(25_000) {
                suspendCancellableCoroutine<Unit> { k ->
                    com.google.firebase.functions.FirebaseFunctions.getInstance(HabitualesRepositorio.REGION)
                        .getHttpsCallable("marcarNoSePresento")
                        .call(mapOf("viajeId" to viajeId, "reservaId" to reservaId))
                        .addOnSuccessListener { if (k.isActive) k.resume(Unit) }
                        .addOnFailureListener { if (k.isActive) k.resumeWithException(it) }
                }
            }
            Resultado.Exito(Unit)
        } catch (e: TimeoutCancellationException) {
            Resultado.Error("Sin conexión: no pudimos marcarlo.")
        } catch (e: com.google.firebase.functions.FirebaseFunctionsException) {
            // Los mensajes de la función ya están escritos para el usuario (p. ej. «Espera 2 min más…»).
            Resultado.Error(when (e.code) {
                com.google.firebase.functions.FirebaseFunctionsException.Code.UNAVAILABLE,
                com.google.firebase.functions.FirebaseFunctionsException.Code.DEADLINE_EXCEEDED -> "Sin conexión: no pudimos marcarlo."
                com.google.firebase.functions.FirebaseFunctionsException.Code.INTERNAL,
                com.google.firebase.functions.FirebaseFunctionsException.Code.UNKNOWN -> "No se pudo marcar. Intenta de nuevo."
                else -> e.message ?: "No se pudo marcar."
            })
        } catch (e: Exception) {
            Resultado.Error(e.message ?: "No se pudo marcar.")
        }
    }

    /** Pasajero: solo su propia parada. */
    fun miParadaEnVivo(viajeId: String, reservaId: String): Flow<Resultado<Observado<String>>> = enSesion {
        escuchar(parada(viajeId, reservaId)) { d -> d.getString("estado") }
    }

    // ------------------------------------------------------------ apoyo

    /** Cierra el listener al cerrar sesión o cambiar de cuenta. */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private fun <T> enSesion(bloque: (String) -> Flow<T>): Flow<T> = callbackFlow {
        val oyente = FirebaseAuth.AuthStateListener { trySend(it.currentUser?.uid) }
        auth.addAuthStateListener(oyente)
        awaitClose { auth.removeAuthStateListener(oyente) }
    }.distinctUntilChanged().flatMapLatest { uid -> if (uid == null) emptyFlow() else bloque(uid) }

    private fun <T> escuchar(ref: com.google.firebase.firestore.DocumentReference, leer: (DocumentSnapshot) -> T?) =
        callbackFlow<Resultado<Observado<T>>> {
            val registro = ref.addSnapshotListener(MetadataChanges.INCLUDE) { d, e ->
                // Tras un error Firestore ya no envía más: se cierra para poder volver a escuchar.
                if (e != null) { trySend(Resultado.Error(mensajeLectura(e))); close() }
                else if (d != null) trySend(Resultado.Exito(Observado(leer(d), d.metadata.isFromCache)))
            }
            awaitClose { registro.remove() }
        }

    private fun mensajeLectura(e: FirebaseFirestoreException) =
        if (e.code == FirebaseFirestoreException.Code.PERMISSION_DENIED) "Ya no tienes acceso a este viaje."
        else "No se pudo leer el viaje. Revisa tu conexión."

    private suspend fun transaccion(bloque: (com.google.firebase.firestore.Transaction) -> Unit) =
        suspendCancellableCoroutine<Unit> { k ->
            db.runTransaction { tx -> bloque(tx); null }
                .addOnSuccessListener { if (k.isActive) k.resume(Unit) }
                .addOnFailureListener { if (k.isActive) k.resumeWithException(it) }
        }

    private suspend fun esperar(tarea: com.google.android.gms.tasks.Task<Void>) =
        suspendCancellableCoroutine<Unit> { k ->
            tarea.addOnSuccessListener { if (k.isActive) k.resume(Unit) }
                .addOnFailureListener { if (k.isActive) k.resumeWithException(it) }
        }

    private suspend fun <T> conRed(accion: String, bloque: suspend () -> T): Resultado<T> = try {
        Resultado.Exito(withTimeout(ESPERA_VIAJE_MS) { bloque() })
    } catch (e: TimeoutCancellationException) {
        Resultado.Error("Sin conexión: no pudimos $accion.")
    } catch (e: FirebaseFirestoreException) {
        Resultado.Error(
            if (e.code == FirebaseFirestoreException.Code.PERMISSION_DENIED) "No tienes permiso para $accion."
            else "No se pudo $accion (${e.code.name})."
        )
    } catch (e: Exception) {
        Resultado.Error(e.message ?: "No se pudo $accion.")
    }
}
