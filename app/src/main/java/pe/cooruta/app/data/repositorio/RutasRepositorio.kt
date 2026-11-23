package pe.cooruta.app.data.repositorio

import com.google.firebase.FirebaseNetworkException
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import pe.cooruta.app.data.BorradorRuta
import pe.cooruta.app.data.Lugar
import pe.cooruta.app.data.Punto
import pe.cooruta.app.data.metros
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import pe.cooruta.app.data.Resultado
import pe.cooruta.app.ui.auth.clave
import pe.cooruta.app.ui.auth.comunidadDe
import java.time.DayOfWeek
import java.time.LocalTime
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Una ruta que el conductor ya publicó, leída de Firestore. */
data class MiRuta(
    val id: String,
    val conductorId: String = "",
    val nombre: String = "",
    val origenPunto: Punto? = null,
    val destinoPunto: Punto? = null,
    /** Puntos del recorrido real (puede venir vacío en rutas viejas). */
    val recorrido: List<Punto> = emptyList(),
    val distanciaMetros: Double? = null,
    val tarifaKmCentimos: Int? = null,
    val creadaMs: Long = 0,
    /** Ruta de prueba (simula otro conductor). */
    val prueba: Boolean = false,
    /** Nombre que el usuario le puso a su ruta ("Casa → Tecsup"); vacío si no le puso. */
    val alias: String = "",
    val origen: String,
    val destino: String,
    val dias: Set<DayOfWeek>,
    val salida: LocalTime,
    val duracionSegundos: Long?,
    val asientos: Int,
    val asientosLibres: Int,
    val pasajeCentimos: Int?,
    val activa: Boolean,
    /** "automatico", "elegido" o "ninguno"; null en rutas publicadas antes de este campo. */
    val tramoPublico: String? = null,
    /** Días sueltos en que el conductor no viaja ("2026-10-29"); esos días no aparece en Buscar. */
    val fechasLibres: Set<String> = emptySet()
) {
    fun libreEl(fecha: String) = fecha in fechasLibres
    val libreHoy: Boolean get() = libreEl(java.time.LocalDate.now().toString())
    /** Sale hoy: activa, hoy es uno de sus días (rutas sin días cuentan) y no lo marcó libre. Las solicitudes son siempre para hoy. */
    val saleHoy: Boolean get() = activa && !libreHoy && (dias.isEmpty() || java.time.LocalDate.now().dayOfWeek in dias)

    /** Buscar solo puede usar la ruta si su tramo público es utilizable (rutas antiguas incluidas). */
    val visibleEnBuscar: Boolean
        get() = tramoPublico != pe.cooruta.app.data.TipoTramo.NINGUNO.valor && pe.cooruta.app.data.TramoPublico.utilizable(recorrido)
}

/** Cuánto esperar a que Firestore confirme antes de decir "sin conexión". */
private const val ESPERA_MAXIMA_MS = 15_000L

/**
 * Radio alrededor del origen y del destino cuyo recorrido no se publica en `rutas`.
 * Reduce lo que se puede deducir del domicilio, pero no es garantía de anonimato:
 * el valor es una decisión pendiente.
 */
internal const val RADIO_PRIVADO_M = 1000.0

/** Totales que permitirían deducir cuánto falta del recorrido publicado: solo en `rutasPrivadas`. */
private val CAMPOS_SOLO_PRIVADOS = listOf("distanciaMetros", "duracionSegundos", "pasajeCentimos")

/** Recorrido publicable: sin los puntos a menos de [radioM] del origen o del destino. */
/**
 * Como máximo [max] puntos (≥ 2), en orden, con el primero y el último sin duplicar;
 * vacío si no hay al menos 2. Índices repartidos de forma pareja: ⌊k·(n−1)/(max−1)⌋.
 */
internal fun reducirPuntos(pts: List<Punto>, max: Int): List<Punto> {
    require(max >= 2)
    if (pts.size < 2) return emptyList()
    if (pts.size <= max) return pts
    val n = pts.size
    return (0 until max).map { k -> pts[(k.toLong() * (n - 1) / (max - 1)).toInt()] }
}

internal fun recorridoPublico(puntos: List<Punto>, origen: Punto, destino: Punto, radioM: Double = RADIO_PRIVADO_M): List<Punto> =
    // Tramo contiguo: no une partes separadas por la zona privada con líneas rectas.
    pe.cooruta.app.data.TramoPublico.automatico(puntos, origen, destino, radioM)

/** Tramo por defecto al publicar: el automático si es utilizable; si no, ninguno (no aparece en Buscar). */
internal fun tramoPorDefecto(completos: List<Punto>, origen: Punto, destino: Punto): pe.cooruta.app.data.TramoPublicado {
    val auto = recorridoPublico(completos, origen, destino)
    return if (pe.cooruta.app.data.TramoPublico.utilizable(auto))
        pe.cooruta.app.data.TramoPublicado(pe.cooruta.app.data.TipoTramo.AUTOMATICO, auto)
    else pe.cooruta.app.data.TramoPublicado(pe.cooruta.app.data.TipoTramo.NINGUNO, emptyList())
}

/**
 * Documento público de la ruta (lo leen todos los usuarios con sesión): sin coordenadas de los
 * extremos, sin totales y, como geometría, solo el tramo autorizado (o ninguno).
 */
internal fun armarRutaPublica(datos: Map<String, Any?>, tramo: pe.cooruta.app.data.TramoPublicado): Map<String, Any?> {
    val base = datos - CAMPOS_SOLO_PRIVADOS - "recorrido" + mapOf(
        "origen" to emptyMap<String, Any>(),
        "destino" to emptyMap<String, Any>(),
        "tramoPublico" to tramo.tipo.valor
    )
    if (tramo.tipo == pe.cooruta.app.data.TipoTramo.NINGUNO) return base
    return base + ("recorrido" to reducirPuntos(tramo.puntos, 150).map { mapOf("lat" to it.latitud, "lng" to it.longitud) })
}

/**
 * Publicar en Firestore lo que se armó en el asistente.
 *
 * - Un conductor publica una **ruta** (colección `rutas`): la ven los pasajeros.
 * - Un pasajero guarda una **búsqueda** (colección `busquedas`): con ella se le
 *   buscan conductores que pasen cerca a esa hora.
 *
 * Las reglas de seguridad están en `firestore.rules`, en la raíz del proyecto.
 */
class RutasRepositorio(
    private val db: FirebaseFirestore = FirebaseFirestore.getInstance(),
    private val auth: FirebaseAuth = FirebaseAuth.getInstance()
) {

    /** Comunidad de la cuenta ("tecsup" o "externa"); las reglas la vuelven a comprobar. */
    private fun miComunidad(): String = comunidadDe(auth.currentUser?.email).clave

    /** Rutas propias publicadas antes de las comunidades: se les asigna la comunidad del conductor. */
    suspend fun asignarComunidadPendiente() {
        val uid = auth.currentUser?.uid ?: return
        runCatching {
            val docs = suspendCancellableCoroutine { c ->
                db.collection("rutas").whereEqualTo("conductorId", uid).get()
                    .addOnSuccessListener { if (c.isActive) c.resume(it.documents) }
                    .addOnFailureListener { if (c.isActive) c.resumeWithException(it) }
            }
            docs.filter { it.getString("comunidad") == null }
                .forEach { it.reference.update("comunidad", miComunidad()) }
        }
    }

    /**
     * Un id nuevo para el documento, creado en el teléfono sin ir a la red.
     *
     * Se pide una sola vez por publicación: si la primera vez falla por la
     * red y la persona reintenta, se escribe sobre el mismo documento en vez de
     * crear un duplicado.
     */
    fun nuevoId(esConductor: Boolean): String = coleccion(esConductor).document().id

    suspend fun publicar(
        id: String,
        borrador: BorradorRuta,
        nombre: String,
        esConductor: Boolean,
        alias: String = "",
        /** Tramo visible en Buscar ya confirmado por el conductor; null = automático o ninguno. */
        tramo: pe.cooruta.app.data.TramoPublicado? = null,
        /** Editar una ruta existente: se conserva su ocupación y no se bajan asientos. */
        edicion: Boolean = false
    ): Resultado<String> {
        val uid = auth.currentUser?.uid
            ?: return Resultado.Error("Inicia sesión con Google para publicar.")
        val horario = borrador.horario
            ?: return Resultado.Error("Falta el horario.")

        // Si el origen es "mi ubicación", sus coordenadas vienen del recorrido.
        val origen = borrador.origen ?: borrador.trayecto?.desde?.let {
            Lugar("Ubicación del " + if (esConductor) "conductor" else "pasajero", "", it.latitud, it.longitud)
        } ?: return Resultado.Error("No sabemos desde dónde sales. Vuelve al paso 1 y elige el origen.")

        val datos = buildMap<String, Any?> {
            put(if (esConductor) "conductorId" else "pasajeroId", uid)
            put("nombre", nombre)
            put("origen", lugarComoMapa(origen))
            put("destino", lugarComoMapa(borrador.destino))
            put("distanciaMetros", borrador.trayecto?.metros)
            put("duracionSegundos", borrador.trayecto?.segundos)
            // El recorrido (reducido) sirve para ver dónde sube y baja cada pasajero.
            borrador.trayecto?.puntos?.takeIf { it.size >= 2 }?.let { pts ->
                val paso = maxOf(1, pts.size / 150)
                val reducidos = pts.filterIndexed { i, _ -> i % paso == 0 } + pts.last()
                put("recorrido", reducidos.map { mapOf("lat" to it.latitud, "lng" to it.longitud) })
            }
            // 1 = lunes … 7 = domingo, como DayOfWeek. Ordenados para leerlos fácil.
            put("dias", horario.dias.map { it.value }.sorted())
            put("horaSalida", "%02d:%02d".format(horario.salida.hour, horario.salida.minute))
            // También en minutos desde medianoche: así se puede consultar por rango de hora.
            put("minutoSalida", horario.salida.hour * 60 + horario.salida.minute)
            if (esConductor) {
                put("asientos", horario.asientos)
                put("pasajeCentimos", horario.pasajeCentimos)
                put("tarifaKmCentimos", pe.cooruta.app.ui.horario.TARIFA_KM_CENTIMOS)
                put("comunidad", miComunidad())
            }
            put("estado", "activa")
            if (alias.isNotBlank()) put("alias", alias.trim())
            put("creadaEn", FieldValue.serverTimestamp())
        }

        return try {
            withTimeout(ESPERA_MAXIMA_MS) {
                if (esConductor) escribirRuta(id, datos, origen, borrador, tramo, edicion) else escribir(esConductor, id, datos)
            }
            Resultado.Exito(id)
        } catch (e: TimeoutCancellationException) {
            Resultado.Error("Sin conexión: no pudimos publicar. Revisa tu internet y vuelve a intentar.")
        } catch (e: FirebaseNetworkException) {
            Resultado.Error("Sin conexión a internet.")
        } catch (e: FirebaseFirestoreException) {
            Resultado.Error(
                when (e.code) {
                    FirebaseFirestoreException.Code.PERMISSION_DENIED ->
                        "No tienes permiso para guardar esta ruta."
                    FirebaseFirestoreException.Code.UNAVAILABLE ->
                        "Firestore no responde. Revisa tu internet."
                    else -> "No se pudo publicar (${e.code.name})."
                }
            )
        } catch (e: Exception) {
            Resultado.Error(e.message ?: "No se pudo publicar.")
        }
    }

    /** Las rutas publicadas por el conductor con sesión abierta. */
    suspend fun misRutas(): Resultado<List<MiRuta>> {
        val uid = auth.currentUser?.uid ?: return Resultado.Error("Inicia sesión con Google.")
        return conRed("cargar tus rutas") {
            val docs = suspendCancellableCoroutine { c ->
                db.collection("rutas").whereEqualTo("conductorId", uid).get()
                    .addOnSuccessListener { if (c.isActive) c.resume(it.documents) }
                    .addOnFailureListener { if (c.isActive) c.resumeWithException(it) }
            }
            val privadas = privadasDe(uid)
            docs.mapNotNull(::comoRuta).filter { !it.prueba }.map { conPrivados(it, privadas[it.id]) }.sortedBy { it.salida }
        }
    }

    /**
     * Rutas activas de todos los conductores, para la pestaña Buscar del pasajero.
     * Las propias también salen (marcadas), para poder probar con una sola cuenta.
     */
    suspend fun rutasActivas(): Resultado<List<MiRuta>> {
        if (auth.currentUser == null) return Resultado.Error("Inicia sesión con Google.")
        return conRed("cargar las rutas") {
            val docs = suspendCancellableCoroutine { c ->
                db.collection("rutas").whereEqualTo("estado", "activa").whereEqualTo("comunidad", miComunidad()).get()
                    .addOnSuccessListener { if (c.isActive) c.resume(it.documents) }
                    .addOnFailureListener { if (c.isActive) c.resumeWithException(it) }
            }
            docs.mapNotNull(::comoRuta).filter(::visiblePrueba).sortedBy { it.salida }
        }
    }

    /** Rutas de prueba antiguas (conductores inventados, `prueba = true`): nunca se muestran. */
    private fun visiblePrueba(r: MiRuta) = !r.prueba

    /** Rutas públicas activas; reinicia la escucha al cambiar de sesión. */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    fun rutasActivasEnVivo(): Flow<Resultado<List<MiRuta>>> = uidEnVivo().flatMapLatest { uid ->
        if (uid == null) flowOf(Resultado.Exito(emptyList())) else callbackFlow<Resultado<List<MiRuta>>> {
            val registro = db.collection("rutas").whereEqualTo("estado", "activa").whereEqualTo("comunidad", miComunidad())
                .addSnapshotListener { instantanea, error ->
                    if (error != null) {
                        trySend(Resultado.Error("No se pudieron cargar las rutas activas."))
                    } else if (instantanea != null) {
                        trySend(Resultado.Exito(instantanea.documents.mapNotNull(::comoRuta).filter(::visiblePrueba).sortedBy { it.salida }))
                    }
                }
            awaitClose { registro.remove() }
        }
    }
    /** Las rutas que el pasajero guardó (colección privada `busquedas`). */
    suspend fun misBusquedas(): Resultado<List<MiRuta>> {
        val uid = auth.currentUser?.uid ?: return Resultado.Error("Inicia sesión con Google.")
        return conRed("cargar tus viajes") {
            val docs = suspendCancellableCoroutine { c ->
                db.collection("busquedas").whereEqualTo("pasajeroId", uid).get()
                    .addOnSuccessListener { if (c.isActive) c.resume(it.documents) }
                    .addOnFailureListener { if (c.isActive) c.resumeWithException(it) }
            }
            docs.mapNotNull(::comoRuta).sortedBy { it.salida }
        }
    }

    /**
     * Antes de eliminar la cuenta, sin dejar asientos ocupados sin registro:
     * 1. Si alguna ruta propia tiene pasajeros aceptados de hoy en adelante, no toca nada.
     * 2. Cancela las solicitudes propias vigentes como pasajero (las aceptadas liberan su asiento).
     * 3. Borra las rutas (rechazando sus pendientes vigentes) con su parte privada, las búsquedas
     *    y los avisos de viaje. Las reservas no se borran: son el registro compartido con la otra persona.
     * Cada paso es atómico; si algo falla a mitad, lo hecho es consistente y repetir es seguro.
     */
    suspend fun borrarTodoDelUsuario(): Resultado<Unit> {
        val uid = auth.currentUser?.uid ?: return Resultado.Error("Inicia sesión con Google.")
        // Eliminar la cuenta manda: cada ruta se borra a la fuerza en el servidor (cancela y avisa a
        // sus pasajeros), fuera del límite de 15 s; solo las que queden pasan por el borrado normal.
        runCatching {
            withTimeout(60_000) {
                consultar(db.collection("rutas").whereEqualTo("conductorId", uid)).forEach { eliminarForzado(it.id) }
            }
        }
        return conRed("borrar tus datos") {
            val hoy = hoyTexto()
            val misRutas = consultar(db.collection("rutas").whereEqualTo("conductorId", uid)).map { it.id }
            comprobarRutasBorrables(uid, misRutas)
            consultar(db.collection("reservas").whereEqualTo("pasajeroId", uid))
                .filter { it.getString("estado") in setOf("pendiente", "aceptada") && it.getString("fecha").orEmpty() >= hoy }
                .forEach { cancelarEnTransaccion(it.id, siTermino = false) }
            terminarHabitualesComoPasajero()
            eliminarRutas(uid, misRutas)
            confirmar(db.batch().also { lote ->
                lote.delete(db.collection("miembrosEmpresa").document(uid))
                lote.delete(db.collection("invitacionesEmpresa").document(uid))
            })
            // Grupo: sale de los grupos que integra, disuelve el suyo y borra los tramos que compartía.
            val grupos = consultar(db.collection("miembrosGrupo").whereEqualTo("miembroId", uid)) +
                consultar(db.collection("miembrosGrupo").whereEqualTo("conductorId", uid))
            confirmar(db.batch().also { lote ->
                grupos.forEach { lote.delete(it.reference) }
                lote.delete(db.collection("rutasGrupo").document(uid))
            })
            for (coleccion in listOf("busquedas", "pedidos")) {
                val docs = consultar(db.collection(coleccion).whereEqualTo("pasajeroId", uid))
                if (docs.isNotEmpty()) confirmar(db.batch().also { lote -> docs.forEach { lote.delete(it.reference) } })
            }
            for (sub in listOf("conductores", "tokens")) {
                val docs = consultar(db.collection("usuarios").document(uid).collection(sub))
                if (docs.isNotEmpty()) confirmar(db.batch().also { lote -> docs.forEach { lote.delete(it.reference) } })
            }
        }
    }

    /**
     * Lanza un error si alguna de [ids] tiene pasajeros aceptados de hoy en adelante (o los tuvo:
     * `ultimaFechaAceptada`, que las reglas exigen para borrar). Devuelve las pendientes vigentes.
     */
    private suspend fun comprobarRutasBorrables(uid: String, ids: List<String>): List<com.google.firebase.firestore.DocumentSnapshot> {
        if (ids.isEmpty()) return emptyList()
        exigirSinHabituales(ids)
        val hoy = hoyTexto()
        val reservas = consultar(db.collection("reservas").whereEqualTo("conductorId", uid)).filter { it.getString("rutaId") in ids }
        reservas.firstOrNull { it.getString("estado") == "aceptada" && it.getString("fecha").orEmpty() >= hoy }?.let {
            throw IllegalStateException("Tienes pasajeros confirmados para el ${it.getString("fecha")}. Mientras tanto puedes pausar la ruta.")
        }
        consultar(db.collection("rutas").whereEqualTo("conductorId", uid))
            .firstOrNull { it.id in ids && it.getString("ultimaFechaAceptada").orEmpty() >= hoy }?.let {
                throw IllegalStateException("Tu ruta tuvo pasajeros aceptados para el ${it.getString("ultimaFechaAceptada")}: podrás borrarla después de ese día. Mientras tanto puedes pausarla.")
            }
        return reservas.filter { it.getString("estado") == "pendiente" && it.getString("fecha").orEmpty() >= hoy }
    }

    /**
     * Error si alguna ruta de [ids] tiene acuerdos habituales activos; las solicitudes habituales sin
     * responder se rechazan. Así no quedan reservas futuras de una ruta borrada o pausada.
     */
    private suspend fun exigirSinHabituales(ids: List<String>) {
        val hab = HabitualesRepositorio()
        for (id in ids) {
            val lista = when (val r = hab.activosDeRuta(id)) {
                is Resultado.Exito -> r.valor
                is Resultado.Error -> throw IllegalStateException(r.mensaje)
            }
            lista.firstOrNull { it.estado == "aceptado" }?.let {
                throw IllegalStateException("${it.nombrePasajero} viaja habitualmente en esta ruta. Termina ese viaje habitual en Solicitudes → Confirmados.")
            }
            lista.filter { it.estado == "pendiente" }.forEach { h ->
                (hab.responder(h.id, aceptar = false) as? Resultado.Error)?.let { throw IllegalStateException(it.mensaje) }
            }
        }
    }

    /** En un solo lote: rechaza las pendientes vigentes y borra cada ruta con su parte privada. */
    private suspend fun eliminarRutas(uid: String, ids: List<String>) {
        if (ids.isEmpty()) return
        val pendientes = comprobarRutasBorrables(uid, ids)
        val lote = db.batch()
        pendientes.forEach { lote.update(it.reference, mapOf("estado" to "rechazada", "respondidaEn" to FieldValue.serverTimestamp())) }
        ids.forEach {
            lote.delete(db.collection("rutasPrivadas").document(it))
            lote.delete(db.collection("rutas").document(it))
        }
        confirmar(lote)
    }

    private suspend fun consultar(q: com.google.firebase.firestore.Query): List<com.google.firebase.firestore.DocumentSnapshot> =
        suspendCancellableCoroutine { c ->
            q.get().addOnSuccessListener { if (c.isActive) c.resume(it.documents) }
                .addOnFailureListener { if (c.isActive) c.resumeWithException(it) }
        }

    private suspend fun confirmar(lote: com.google.firebase.firestore.WriteBatch) =
        suspendCancellableCoroutine<Unit> { c ->
            lote.commit().addOnSuccessListener { if (c.isActive) c.resume(Unit) }
                .addOnFailureListener { if (c.isActive) c.resumeWithException(it) }
        }

    /**
     * Ruta del conductor en dos documentos, en un mismo lote:
     * - `rutas/{id}` (la leen todos): solo lo que Buscar necesita. Sin coordenadas de origen ni de
     *   destino, sin totales de distancia, duración o pasaje, y sin el recorrido cercano a los extremos.
     * - `rutasPrivadas/{id}` (solo el dueño): origen, destino, recorrido completo y totales.
     */
    private suspend fun escribirRuta(id: String, datos: Map<String, Any?>, origen: Lugar, borrador: BorradorRuta,
                                     tramo: pe.cooruta.app.data.TramoPublicado?, edicion: Boolean = false) {
        val completos = borrador.trayecto?.puntos.orEmpty()
        val o = Punto(origen.latitud, origen.longitud)
        val d = Punto(borrador.destino.latitud, borrador.destino.longitud)
        // Pública = todos la ven completa; Privada = nadie. (Ya no se publican tramos parciales.)
        val privada = tramo?.tipo == pe.cooruta.app.data.TipoTramo.NINGUNO
        val elegido = if (privada) pe.cooruta.app.data.TramoPublicado(pe.cooruta.app.data.TipoTramo.NINGUNO, emptyList())
            else pe.cooruta.app.data.TramoPublicado(pe.cooruta.app.data.TipoTramo.COMPLETO, completos)
        if (elegido.tipo != pe.cooruta.app.data.TipoTramo.NINGUNO && !pe.cooruta.app.data.TramoPublico.utilizable(elegido.puntos))
            throw IllegalStateException("El tramo público no es utilizable. Elige otro o guarda sin aparecer en Buscar.")
        // Sin etiquetas: el nombre elegido puede ser una dirección exacta (las zonas quedan pendientes).
        val publicos = armarRutaPublica(datos, elegido)
        val privados = mapOf(
            "conductorId" to datos["conductorId"],
            "origen" to datos["origen"],
            "destino" to datos["destino"],
            "recorrido" to reducir(completos, 500),
            "distanciaMetros" to datos["distanciaMetros"],
            "duracionSegundos" to datos["duracionSegundos"],
            "pasajeCentimos" to datos["pasajeCentimos"]
        )
        // Solo depuración: cantidad de puntos y nombres de campos, sin valores ni coordenadas.
        if (pe.cooruta.app.BuildConfig.DEBUG) android.util.Log.d("CooRutaPublicar",
            "rutas: ${(publicos["recorrido"] as? List<*>)?.size ?: 0} puntos públicos; " +
                "campos=${publicos.keys.sorted()}; nulos=${publicos.filterValues { it == null }.keys.sorted()}; " +
                "rutasPrivadas: ${(privados["recorrido"] as? List<*>)?.size ?: 0} puntos; campos=${privados.keys.sorted()}")
        val refRuta = db.collection("rutas").document(id)
        if (edicion) {
            // Las reglas no dejan bajar asientos (habría pasajeros sin lugar).
            val antes = suspendCancellableCoroutine<com.google.firebase.firestore.DocumentSnapshot> { c ->
                refRuta.get().addOnSuccessListener { if (c.isActive) c.resume(it) }
                    .addOnFailureListener { if (c.isActive) c.resumeWithException(it) }
            }
            val previos = (antes.getLong("asientos") ?: 0).toInt()
            val nuevos = (datos["asientos"] as? Int) ?: 0
            if (nuevos < previos) throw IllegalStateException("No puedes bajar de $previos asientos en una ruta publicada. Crea una ruta nueva si necesitas menos.")
            // Merge: conserva ocupación y reservas; el recorrido se manda siempre (vacío si es privada).
            val conRecorrido = if ("recorrido" in publicos) publicos else publicos + ("recorrido" to emptyList<Any>())
            confirmar(db.batch()
                .set(refRuta, conRecorrido, com.google.firebase.firestore.SetOptions.merge())
                .set(db.collection("rutasPrivadas").document(id), privados))
        } else confirmar(db.batch()
            .set(refRuta, publicos)
            .set(db.collection("rutasPrivadas").document(id), privados))
    }

    /** A lo más ~[max] puntos (siempre con el último); vacío si no quedan al menos 2. */
    private fun reducir(pts: List<Punto>, max: Int): List<Map<String, Double>> =
        reducirPuntos(pts, max).map { mapOf("lat" to it.latitud, "lng" to it.longitud) }

    private fun punto(valor: Any?): Punto? {
        val m = valor as? Map<*, *> ?: return null
        val lat = (m["latitud"] as? Number)?.toDouble() ?: return null
        val lng = (m["longitud"] as? Number)?.toDouble() ?: return null
        return Punto(lat, lng)
    }

    // ------------------------------------------------------------ viajes de hoy

    private fun hoyTexto() = java.time.LocalDate.now().toString()

    /**
     * El pasajero avisa que hoy hace [trayecto]: crea `pedidos/{uid}_{fecha}`.
     * Solo esto lo ven los conductores (sus rutas guardadas siguen privadas).
     */
    suspend fun avisarViajeHoy(trayecto: MiRuta): Resultado<Unit> {
        val usuario = auth.currentUser ?: return Resultado.Error("Inicia sesión con Google.")
        val o = trayecto.origenPunto ?: return Resultado.Error("Tu ruta no tiene origen.")
        val d = trayecto.destinoPunto ?: return Resultado.Error("Tu ruta no tiene destino.")
        val datos = mapOf(
            "pasajeroId" to usuario.uid,
            "nombre" to (usuario.displayName ?: "Pasajero"),
            "fecha" to hoyTexto(),
            // Lo leen los conductores: solo puntos aproximados, sin nombre ni dirección.
            "origen" to aproximado(o),
            "destino" to aproximado(d),
            "distanciaMetros" to trayecto.distanciaMetros,
            "horaSalida" to "%02d:%02d".format(trayecto.salida.hour, trayecto.salida.minute),
            "minutoSalida" to trayecto.salida.hour * 60 + trayecto.salida.minute,
            "creadaEn" to FieldValue.serverTimestamp(),
            "comunidad" to miComunidad()
        )
        return conRed("avisar tu viaje") {
            suspendCancellableCoroutine<Unit> { c ->
                db.collection("pedidos").document("${usuario.uid}_${hoyTexto()}").set(datos)
                    .addOnSuccessListener { if (c.isActive) c.resume(Unit) }
                    .addOnFailureListener { if (c.isActive) c.resumeWithException(it) }
            }
        }
    }

    /** Redondeo a 0,01° (~1 km). Reduce la precisión publicada; no garantiza anonimato. */
    private fun aproximado(p: Punto) = mapOf(
        "latitud" to Math.round(p.latitud * 100) / 100.0,
        "longitud" to Math.round(p.longitud * 100) / 100.0
    )

    /** El pasajero ya no viaja hoy: borra su aviso. */
    suspend fun quitarViajeHoy(): Resultado<Unit> {
        val uid = auth.currentUser?.uid ?: return Resultado.Error("Inicia sesión con Google.")
        return conRed("quitar tu aviso") {
            suspendCancellableCoroutine<Unit> { c ->
                db.collection("pedidos").document("${uid}_${hoyTexto()}").delete()
                    .addOnSuccessListener { if (c.isActive) c.resume(Unit) }
                    .addOnFailureListener { if (c.isActive) c.resumeWithException(it) }
            }
        }
    }

    /** Pasajeros que viajan hoy, en tiempo real (para el conductor). */
    // Se reinicia con la sesión: tras cerrar sesión o cambiar de cuenta no queda un listener muerto.
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    fun pedidosDeHoyEnVivo(): Flow<Resultado<List<MiRuta>>> = uidEnVivo().flatMapLatest { uid ->
        if (uid == null) flowOf(Resultado.Exito(emptyList())) else callbackFlow {
        val registro = db.collection("pedidos").whereEqualTo("fecha", hoyTexto()).whereEqualTo("comunidad", miComunidad())
            .addSnapshotListener { instantanea, error ->
                if (error != null) {
                    trySend(Resultado.Error(
                        if (error.code == FirebaseFirestoreException.Code.PERMISSION_DENIED)
                            "No tienes permiso para ver los pasajeros de hoy."
                        else "No se pudieron cargar los pasajeros."
                    ))
                } else if (instantanea != null) {
                    trySend(Resultado.Exito(instantanea.documents.mapNotNull { d ->
                        comoRuta(d)?.copy(conductorId = d.getString("pasajeroId").orEmpty())
                    }))
                }
            }
        awaitClose { registro.remove() }
    } }

    // ------------------------------------------------------------ solicitudes de asiento

    /**
     * Una solicitud de asiento (colección `reservas`), vista por el pasajero o el conductor.
     * Estados: pendiente → aceptada | rechazada | cancelada; rechazada/cancelada → pendiente.
     */
    data class Solicitud(
        val id: String,
        val rutaId: String,
        val pasajeroId: String = "",
        val conductorId: String = "",
        val nombrePasajero: String,
        val nombreConductor: String = "",
        val fecha: String = "",
        val horaSalida: String = "",
        val subida: String,
        val bajada: String,
        val pasajeCentimos: Int,
        val kmCompartidos: Double,
        val mensaje: String,
        val estado: String,
        val creadaMs: Long = 0,
        /** Punto de encuentro sobre el recorrido (solo lo ven los dos participantes de la reserva). */
        val subidaPunto: Punto? = null,
        /** Punto donde baja (solo lo ven los dos participantes). */
        val bajadaPunto: Punto? = null,
        /** Reserva creada por un acuerdo habitual (vacío si es puntual). */
        val habitualId: String = "",
        /** Hora acordada de recogida del acuerdo habitual ("07:12"); vacía en las puntuales. */
        val horaRecogida: String = "",
        /** "acuerdo_terminado" si la canceló el fin de un acuerdo habitual. */
        val motivo: String = ""
    )

    private fun idReserva(rutaId: String, uid: String) = "${rutaId}_${uid}_${hoyTexto()}"

    /**
     * El pasajero pide un asiento hoy. En una transacción: si ya hay una solicitud
     * pendiente o aceptada no se toca; si estaba rechazada o cancelada solo vuelve a
     * "pendiente" (no se reescriben ruta, conductor ni precio).
     */
    suspend fun solicitarAsiento(
        c: pe.cooruta.app.data.Coincidencia, subida: String, bajada: String, mensaje: String
    ): Resultado<Unit> {
        val usuario = auth.currentUser ?: return Resultado.Error("Inicia sesión con Google.")
        val reserva = db.collection("reservas").document(idReserva(c.ruta.id, usuario.uid))
        val ruta = db.collection("rutas").document(c.ruta.id)
        return conRed("enviar tu solicitud") {
            suspendCancellableCoroutine<Unit> { k ->
                db.runTransaction { tx ->
                    val r = tx.get(ruta)
                    if (!r.exists() || r.getString("estado") == "pausada")
                        throw IllegalStateException("Esta ruta ya no está disponible.")
                    if (r.getString("conductorId") == usuario.uid) throw IllegalStateException("No puedes pedir asiento en tu propia ruta.")
                    val dias = (r.get("dias") as? List<*>).orEmpty().mapNotNull { (it as? Number)?.toInt() }
                    val libres = (r.get("fechasLibres") as? List<*>).orEmpty().filterIsInstance<String>()
                    if ((dias.isNotEmpty() && java.time.LocalDate.now().dayOfWeek.value !in dias) || hoyTexto() in libres)
                        throw IllegalStateException("Este conductor no sale hoy.")
                    if (libresEn(r, hoyTexto()) <= 0) throw IllegalStateException("Ya no quedan asientos en esta ruta hoy.")
                    val previa = tx.get(reserva)
                    when (previa.getString("estado")) {
                        "aceptada" -> throw IllegalStateException("Ya tienes un asiento confirmado en esta ruta.")
                        "invitada" -> throw IllegalStateException("El conductor te invitó: responde la invitación en Solicitudes.")
                        // Reenviar (también si seguía pendiente): actualiza dónde sube/baja y el pasaje.
                        "pendiente", "rechazada", "cancelada" -> tx.update(reserva, mapOf(
                            "estado" to "pendiente", "mensaje" to mensaje.trim().take(500),
                            "subida" to mapOf("nombre" to subida, "latitud" to c.subida.latitud, "longitud" to c.subida.longitud),
                            "bajada" to mapOf("nombre" to bajada, "latitud" to c.bajada.latitud, "longitud" to c.bajada.longitud),
                            "kmCompartidos" to c.kmCompartidos,
                            "pasajeCentimos" to c.pasajeCentimos,
                            "actualizadaEn" to FieldValue.serverTimestamp()
                        ))
                        else -> tx.set(reserva, mapOf(
                            "rutaId" to c.ruta.id,
                            "conductorId" to (r.getString("conductorId") ?: ""),
                            "pasajeroId" to usuario.uid,
                            "nombrePasajero" to (usuario.displayName ?: "Pasajero"),
                            "nombreConductor" to (r.getString("nombre") ?: c.ruta.nombre),
                            "fecha" to hoyTexto(),
                            "horaSalida" to (r.getString("horaSalida") ?: ""),
                            "subida" to mapOf("nombre" to subida, "latitud" to c.subida.latitud, "longitud" to c.subida.longitud),
                            "bajada" to mapOf("nombre" to bajada, "latitud" to c.bajada.latitud, "longitud" to c.bajada.longitud),
                            "kmCompartidos" to c.kmCompartidos,
                            "pasajeCentimos" to c.pasajeCentimos,
                            "mensaje" to mensaje.trim().take(500),
                            "estado" to "pendiente",
                            "creadaEn" to FieldValue.serverTimestamp()
                        ))
                    }
                    null
                }
                    .addOnSuccessListener { if (k.isActive) k.resume(Unit) }
                    .addOnFailureListener { if (k.isActive) k.resumeWithException(it) }
            }
        }
    }

    /**
     * El pasajero cancela su solicitud; queda en el historial como "cancelada". Si estaba aceptada
     * (hoy o un día futuro), en la misma transacción libera exactamente un asiento de esa fecha.
     * Repetirla no vuelve a restar: si ya está cancelada no escribe nada.
     */
    suspend fun cancelarSolicitud(id: String): Resultado<Unit> = conRed("cancelar tu solicitud") { cancelarEnTransaccion(id) }

    /** [siTermino] = false: si el viaje de esa reserva ya terminó, no cambia nada (al eliminar la cuenta). */
    private suspend fun cancelarEnTransaccion(id: String, siTermino: Boolean = true) {
        val uid = auth.currentUser?.uid ?: throw IllegalStateException("Inicia sesión con Google.")
        val reserva = db.collection("reservas").document(id)
        suspendCancellableCoroutine<Unit> { k ->
            db.runTransaction { tx ->
                val res = tx.get(reserva)
                if (res.getString("pasajeroId") != uid) throw IllegalStateException("Esta solicitud no es tuya.")
                when (res.getString("estado")) {
                    "cancelada" -> Unit // ya estaba cancelada: no se resta otra vez
                    "pendiente" -> tx.update(reserva, mapOf("estado" to "cancelada", "actualizadaEn" to FieldValue.serverTimestamp()))
                    "aceptada" -> {
                        val fecha = res.getString("fecha").orEmpty()
                        if (fecha < hoyTexto()) throw IllegalStateException("Ese viaje ya pasó.")
                        val rutaId = res.getString("rutaId").orEmpty()
                        // El viaje terminado es el registro de lo que pasó: la reserva ya no se cancela.
                        if (tx.get(db.collection("viajes").document("${rutaId}_$fecha")).getString("estado") == "finalizado") {
                            if (siTermino) throw IllegalStateException("Este viaje ya terminó: no se puede cancelar.")
                            return@runTransaction null
                        }
                        val rutaRef = db.collection("rutas").document(res.getString("rutaId").orEmpty())
                        if ((ocupadosPorFecha(tx.get(rutaRef))[fecha] ?: 0) <= 0)
                            throw IllegalStateException("No se pudo liberar el asiento: la ruta no lo tiene registrado.")
                        tx.update(rutaRef, mapOf("ocupadosPorFecha.$fecha" to FieldValue.increment(-1), "ultimaReserva" to id))
                        tx.update(reserva, mapOf("estado" to "cancelada", "actualizadaEn" to FieldValue.serverTimestamp()))
                    }
                    else -> throw IllegalStateException("La solicitud ya cambió de estado.")
                }
                null
            }
                .addOnSuccessListener { if (k.isActive) k.resume(Unit) }
                .addOnFailureListener { if (k.isActive) k.resumeWithException(it) }
        }
    }

    /** El conductor invita de nuevo a un pasajero que canceló; el pasajero decide si acepta. */
    suspend fun invitarDeNuevo(s: Solicitud): Resultado<Unit> =
        cambiarEstadoReserva(s.id, desde = setOf("cancelada"), hacia = "invitada", "invitar de nuevo")

    /** El pasajero acepta la invitación (vuelve a «pendiente» para que el conductor confirme) o la declina. */
    suspend fun responderInvitacion(s: Solicitud, aceptar: Boolean): Resultado<Unit> =
        cambiarEstadoReserva(s.id, desde = setOf("invitada"), hacia = if (aceptar) "pendiente" else "cancelada",
            if (aceptar) "aceptar la invitación" else "declinar la invitación")

    /** El conductor se equivocó al rechazar: la solicitud vuelve a "pendiente". */
    suspend fun reconsiderarSolicitud(s: Solicitud): Resultado<Unit> =
        cambiarEstadoReserva(s.id, desde = setOf("rechazada"), hacia = "pendiente", "reabrir la solicitud")

    private suspend fun cambiarEstadoReserva(id: String, desde: Set<String>, hacia: String, accion: String): Resultado<Unit> =
        conRed(accion) {
            val reserva = db.collection("reservas").document(id)
            suspendCancellableCoroutine<Unit> { k ->
                db.runTransaction { tx ->
                    val actual = tx.get(reserva).getString("estado")
                    if (actual !in desde) throw IllegalStateException("La solicitud ya cambió de estado.")
                    tx.update(reserva, mapOf("estado" to hacia, "actualizadaEn" to FieldValue.serverTimestamp()))
                    null
                }
                    .addOnSuccessListener { if (k.isActive) k.resume(Unit) }
                    .addOnFailureListener { if (k.isActive) k.resumeWithException(it) }
            }
        }

    /** Todas las solicitudes del pasajero (historial completo), en vivo. */
    fun misReservasEnVivo(): Flow<Resultado<List<Solicitud>>> =
        reservasEnVivo("pasajeroId", "No se pudieron cargar tus solicitudes.")

    /** Solicitudes recibidas por el conductor (todas las fechas), en vivo. */
    fun solicitudesRecibidasEnVivo(): Flow<Resultado<List<Solicitud>>> =
        reservasEnVivo("conductorId", "No se pudieron cargar las solicitudes.")

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private fun reservasEnVivo(campo: String, error: String): Flow<Resultado<List<Solicitud>>> =
        uidEnVivo().flatMapLatest { uid ->
            if (uid == null) flowOf(Resultado.Exito(emptyList())) else callbackFlow {
                val registro = db.collection("reservas").whereEqualTo(campo, uid)
                    .addSnapshotListener { instantanea, e ->
                        if (e != null) trySend(Resultado.Error(
                            if (e.code == FirebaseFirestoreException.Code.PERMISSION_DENIED)
                                "$error No tienes permiso." else "$error Revisa tu conexión."
                        ))
                        else if (instantanea != null) trySend(Resultado.Exito(
                            instantanea.documents.map(::comoSolicitud).sortedByDescending { it.creadaMs }
                        ))
                    }
                awaitClose { registro.remove() }
            }
        }

    private fun comoSolicitud(d: com.google.firebase.firestore.DocumentSnapshot) = Solicitud(
        id = d.id,
        rutaId = d.getString("rutaId").orEmpty(),
        pasajeroId = d.getString("pasajeroId").orEmpty(),
        conductorId = d.getString("conductorId").orEmpty(),
        nombrePasajero = d.getString("nombrePasajero") ?: "Pasajero",
        nombreConductor = d.getString("nombreConductor").orEmpty(),
        fecha = d.getString("fecha").orEmpty(),
        horaSalida = d.getString("horaSalida").orEmpty(),
        subida = (d.get("subida") as? Map<*, *>)?.get("nombre") as? String ?: "",
        bajada = (d.get("bajada") as? Map<*, *>)?.get("nombre") as? String ?: "",
        pasajeCentimos = d.getLong("pasajeCentimos")?.toInt() ?: 0,
        kmCompartidos = (d.get("kmCompartidos") as? Number)?.toDouble() ?: 0.0,
        mensaje = d.getString("mensaje").orEmpty(),
        estado = d.getString("estado") ?: "pendiente",
        creadaMs = d.getTimestamp("creadaEn")?.toDate()?.time ?: 0,
        subidaPunto = punto(d.get("subida")),
        bajadaPunto = punto(d.get("bajada")),
        habitualId = d.getString("habitualId").orEmpty(),
        horaRecogida = d.getString("horaRecogida").orEmpty(),
        motivo = d.getString("motivo").orEmpty()
    )

    /**
     * El conductor responde. Aceptar valida en una transacción: solicitud pendiente,
     * conductor dueño de la ruta, ruta activa y asientos libres PARA ESA FECHA; luego
     * suma 1 en `ocupadosPorFecha.<fecha>` de la ruta. Una solicitud ya respondida no
     * vuelve a contar, así que nunca descuenta dos veces.
     */
    suspend fun responderSolicitud(s: Solicitud, aceptar: Boolean): Resultado<Unit> {
        val uid = auth.currentUser?.uid ?: return Resultado.Error("Inicia sesión con Google.")
        return conRed("responder la solicitud") {
            val reserva = db.collection("reservas").document(s.id)
            suspendCancellableCoroutine<Unit> { k ->
                db.runTransaction { tx ->
                    val res = tx.get(reserva)
                    if (res.getString("estado") != "pendiente") throw IllegalStateException("Esta solicitud ya fue respondida.")
                    if (res.getString("conductorId") != uid) throw IllegalStateException("Esta solicitud no es para tu ruta.")
                    if (aceptar) {
                        val rutaRef = db.collection("rutas").document(res.getString("rutaId").orEmpty())
                        val ruta = tx.get(rutaRef)
                        if (!ruta.exists() || ruta.getString("conductorId") != uid)
                            throw IllegalStateException("La ruta ya no existe.")
                        if (ruta.getString("estado") == "pausada") throw IllegalStateException("Reactiva la ruta para aceptar pasajeros.")
                        val fecha = res.getString("fecha").orEmpty()
                        if (fecha < hoyTexto()) throw IllegalStateException("Esta solicitud era para un día que ya pasó.")
                        if (libresEn(ruta, fecha) <= 0) throw IllegalStateException("Ya no te quedan asientos libres ese día.")
                        // Solo se toca la fecha de esta reserva y se liga a ella (las reglas lo exigen).
                        tx.update(rutaRef, mapOf(
                            "ocupadosPorFecha.$fecha" to FieldValue.increment(1),
                            "ultimaReserva" to s.id,
                            "ultimaFechaAceptada" to maxOf(ruta.getString("ultimaFechaAceptada").orEmpty(), fecha)
                        ))
                    }
                    tx.update(reserva, mapOf("estado" to if (aceptar) "aceptada" else "rechazada",
                        "respondidaEn" to FieldValue.serverTimestamp()))
                    null
                }
                    .addOnSuccessListener { if (k.isActive) k.resume(Unit) }
                    .addOnFailureListener { if (k.isActive) k.resumeWithException(it) }
            }
        }
    }

    private fun ocupadosPorFecha(d: com.google.firebase.firestore.DocumentSnapshot): Map<String, Int> =
        (d.get("ocupadosPorFecha") as? Map<*, *>).orEmpty()
            .mapNotNull { (k, v) -> (k as? String)?.let { f -> (v as? Number)?.toInt()?.let { f to it } } }.toMap()

    /** Asientos libres de una ruta en una fecha: capacidad publicada menos aceptados ese día. */
    private fun libresEn(d: com.google.firebase.firestore.DocumentSnapshot, fecha: String): Int =
        (d.getLong("asientos")?.toInt() ?: 0) - (ocupadosPorFecha(d)[fecha] ?: 0)

    /** El uid de la sesión, en vivo: al cambiar de cuenta o cerrar sesión se cortan los listeners anteriores. */
    private fun uidEnVivo(): Flow<String?> = callbackFlow {
        val oyente = FirebaseAuth.AuthStateListener { trySend(it.currentUser?.uid) }
        auth.addAuthStateListener(oyente)
        awaitClose { auth.removeAuthStateListener(oyente) }
    }.distinctUntilChanged()

    /** Mis rutas de conductor en vivo (asientos libres y confirmados se ven al instante). */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    fun misRutasEnVivo(): Flow<Resultado<List<MiRuta>>> = uidEnVivo().flatMapLatest { uid ->
        if (uid == null) flowOf(Resultado.Exito(emptyList())) else combine(callbackFlow<Resultado<List<MiRuta>>> {
            val registro = db.collection("rutas").whereEqualTo("conductorId", uid)
                .addSnapshotListener { instantanea, e ->
                    if (e != null) trySend(Resultado.Error("No se pudieron cargar tus rutas."))
                    else if (instantanea != null) trySend(Resultado.Exito(
                        instantanea.documents.mapNotNull(::comoRuta).filter { !it.prueba }.sortedBy { it.salida }
                    ))
                }
            awaitClose { registro.remove() }
        }, privadasEnVivo(uid)) { r, p ->
            if (r is Resultado.Exito) Resultado.Exito(r.valor.map { conPrivados(it, p[it.id]) }) else r
        }
    }

    /** Parte privada de las rutas del dueño, en vivo. Si falla, las rutas se muestran sin esos datos. */
    private fun privadasEnVivo(uid: String): Flow<Map<String, com.google.firebase.firestore.DocumentSnapshot>> = callbackFlow {
        val registro = db.collection("rutasPrivadas").whereEqualTo("conductorId", uid)
            .addSnapshotListener { s, e -> trySend(if (e != null || s == null) emptyMap() else s.documents.associateBy { it.id }) }
        awaitClose { registro.remove() }
    }

    private suspend fun privadasDe(uid: String): Map<String, com.google.firebase.firestore.DocumentSnapshot> = runCatching {
        consultar(db.collection("rutasPrivadas").whereEqualTo("conductorId", uid)).associateBy { it.id }
    }.getOrDefault(emptyMap())

    /** Completa una ruta propia con sus nombres de origen/destino y totales privados. */
    private fun conPrivados(r: MiRuta, p: com.google.firebase.firestore.DocumentSnapshot?): MiRuta = if (p == null) r else r.copy(
        origen = (p.get("origen") as? Map<*, *>)?.get("nombre") as? String ?: r.origen,
        destino = (p.get("destino") as? Map<*, *>)?.get("nombre") as? String ?: r.destino,
        // Coordenadas exactas (solo en la parte privada): sirven para editar la ruta.
        origenPunto = punto(p.get("origen")) ?: r.origenPunto,
        destinoPunto = punto(p.get("destino")) ?: r.destinoPunto,
        distanciaMetros = (p.get("distanciaMetros") as? Number)?.toDouble() ?: r.distanciaMetros,
        duracionSegundos = (p.get("duracionSegundos") as? Number)?.toLong() ?: r.duracionSegundos,
        pasajeCentimos = p.getLong("pasajeCentimos")?.toInt() ?: r.pasajeCentimos
    )

    /** Al eliminar la cuenta: retira o termina los acuerdos habituales donde es pasajero. */
    private suspend fun terminarHabitualesComoPasajero() {
        val uid = auth.currentUser?.uid ?: return
        val hab = HabitualesRepositorio()
        consultar(db.collection("habituales").whereEqualTo("pasajeroId", uid)).forEach { d ->
            val r = when (d.getString("estado")) {
                "pendiente" -> hab.cancelar(d.id)
                "aceptado" -> hab.terminar(d.id)
                else -> null
            }
            (r as? Resultado.Error)?.let { throw IllegalStateException(it.mensaje) }
        }
    }

    /** Uid de la sesión abierta, para marcar las rutas propias. */
    val miUid: String? get() = auth.currentUser?.uid

    /** Convierte un documento de `rutas` en [MiRuta]; null si le faltan datos. */
    private fun comoRuta(d: com.google.firebase.firestore.DocumentSnapshot): MiRuta? = runCatching {
        val hora = d.getString("horaSalida")!!.split(":")
        MiRuta(
            id = d.id,
            conductorId = d.getString("conductorId").orEmpty(),
            nombre = d.getString("nombre").orEmpty(),
            origenPunto = punto(d.get("origen")),
            destinoPunto = punto(d.get("destino")),
            recorrido = (d.get("recorrido") as? List<*>).orEmpty().mapNotNull { m ->
                val mapa = m as? Map<*, *> ?: return@mapNotNull null
                val lat = (mapa["lat"] as? Number)?.toDouble() ?: return@mapNotNull null
                val lng = (mapa["lng"] as? Number)?.toDouble() ?: return@mapNotNull null
                Punto(lat, lng)
            },
            distanciaMetros = (d.get("distanciaMetros") as? Number)?.toDouble(),
            tarifaKmCentimos = d.getLong("tarifaKmCentimos")?.toInt(),
            creadaMs = d.getTimestamp("creadaEn")?.toDate()?.time ?: 0,
            prueba = d.getBoolean("prueba") == true,
            alias = d.getString("alias").orEmpty(),
            origen = (d.get("origen") as? Map<*, *>)?.get("nombre") as? String ?: "Origen",
            destino = (d.get("destino") as? Map<*, *>)?.get("nombre") as? String ?: "Destino",
            dias = (d.get("dias") as? List<*>).orEmpty()
                .mapNotNull { (it as? Number)?.toInt()?.let(DayOfWeek::of) }.toSet(),
            fechasLibres = (d.get("fechasLibres") as? List<*>).orEmpty().filterIsInstance<String>().toSet(),
            salida = LocalTime.of(hora[0].trim().toInt(), hora.getOrNull(1)?.trim()?.toInt() ?: 0),
            duracionSegundos = (d.get("duracionSegundos") as? Number)?.toLong(),
            asientos = d.getLong("asientos")?.toInt() ?: 0,
            // Libres HOY = capacidad − aceptados hoy. El viejo campo "asientosLibres" (que se
            // descontaba para siempre) ya no se usa: así cada día empieza con todos sus asientos.
            asientosLibres = (d.getLong("asientos")?.toInt() ?: 0) - ocupadosPorFecha(d)[hoyTexto()].let { it ?: 0 },
            pasajeCentimos = d.getLong("pasajeCentimos")?.toInt(),
            activa = d.getString("estado") != "pausada",
            tramoPublico = d.getString("tramoPublico")
        )
    }.getOrNull()

    /** Borra una ruta (conductor, sin dejar pasajeros aceptados sin ruta) o búsqueda (pasajero). */
    suspend fun eliminar(id: String, esConductor: Boolean): Resultado<Unit> =
        conRed("eliminar la ruta") {
            if (esConductor) {
                val uid = auth.currentUser?.uid ?: throw IllegalStateException("Inicia sesión con Google.")
                return@conRed eliminarRutas(uid, listOf(id))
            }
            suspendCancellableCoroutine<Unit> { c ->
                coleccion(esConductor).document(id).delete()
                    .addOnSuccessListener { if (c.isActive) c.resume(Unit) }
                    .addOnFailureListener { if (c.isActive) c.resumeWithException(it) }
            }
        }

    /**
     * Conductor: elimina la ruta aunque tenga pasajeros o acuerdos habituales (función del servidor:
     * cancela sus viajes de hoy en adelante y les avisa). Devuelve cuántas reservas se cancelaron.
     */
    /**
     * Borra la cuenta completa en el servidor (rutas, reservas, perfil, registros y usuario de Auth).
     * Devuelve null si la función aún no está publicada, para usar el borrado desde la app.
     */
    suspend fun eliminarCuentaCompleta(): Resultado<Unit>? = try {
        kotlinx.coroutines.withTimeout(90_000) {
            suspendCancellableCoroutine<Any?> { k ->
                com.google.firebase.functions.FirebaseFunctions.getInstance(HabitualesRepositorio.REGION)
                    .getHttpsCallable("eliminarCuentaCompleta").call()
                    .addOnSuccessListener { if (k.isActive) k.resume(it.data) }
                    .addOnFailureListener { if (k.isActive) k.resumeWithException(it) }
            }
        }
        Resultado.Exito(Unit)
    } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
        Resultado.Error("Sin conexión: no pudimos eliminar tu cuenta.")
    } catch (e: com.google.firebase.functions.FirebaseFunctionsException) {
        when (e.code) {
            com.google.firebase.functions.FirebaseFunctionsException.Code.NOT_FOUND -> null
            com.google.firebase.functions.FirebaseFunctionsException.Code.UNAVAILABLE,
            com.google.firebase.functions.FirebaseFunctionsException.Code.DEADLINE_EXCEEDED -> Resultado.Error("Sin conexión: no pudimos eliminar tu cuenta.")
            com.google.firebase.functions.FirebaseFunctionsException.Code.FAILED_PRECONDITION -> Resultado.Error(e.message ?: "No se pudo eliminar tu cuenta.")
            else -> Resultado.Error("No se pudo eliminar tu cuenta. Intenta de nuevo.")
        }
    } catch (e: Exception) {
        Resultado.Error("No se pudo eliminar tu cuenta. Intenta de nuevo.")
    }

    suspend fun eliminarForzado(id: String): Resultado<Int> = try {
        val r = kotlinx.coroutines.withTimeout(25_000) {
            suspendCancellableCoroutine<Any?> { k ->
                com.google.firebase.functions.FirebaseFunctions.getInstance(HabitualesRepositorio.REGION)
                    .getHttpsCallable("eliminarRutaForzado").call(mapOf("rutaId" to id))
                    .addOnSuccessListener { if (k.isActive) k.resume(it.data) }
                    .addOnFailureListener { if (k.isActive) k.resumeWithException(it) }
            }
        }
        Resultado.Exito(((r as? Map<*, *>)?.get("canceladas") as? Number)?.toInt() ?: 0)
    } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
        Resultado.Error("Sin conexión: no pudimos eliminar la ruta.")
    } catch (e: com.google.firebase.functions.FirebaseFunctionsException) {
        Resultado.Error(when (e.code) {
            com.google.firebase.functions.FirebaseFunctionsException.Code.UNAVAILABLE,
            com.google.firebase.functions.FirebaseFunctionsException.Code.DEADLINE_EXCEEDED -> "Sin conexión: no pudimos eliminar la ruta."
            com.google.firebase.functions.FirebaseFunctionsException.Code.INTERNAL,
            com.google.firebase.functions.FirebaseFunctionsException.Code.UNKNOWN -> "No se pudo eliminar la ruta. Intenta de nuevo."
            // La función aún no está publicada en Firebase.
            com.google.firebase.functions.FirebaseFunctionsException.Code.NOT_FOUND -> "Esta opción aún no está disponible. Intenta más tarde."
            else -> e.message ?: "No se pudo eliminar la ruta."
        })
    } catch (e: Exception) {
        Resultado.Error(e.message ?: "No se pudo eliminar la ruta.")
    }

    /** Cambia el nombre de una ruta (conductor) o búsqueda (pasajero). */
    suspend fun renombrar(id: String, alias: String, esConductor: Boolean): Resultado<Unit> =
        conRed("cambiar el nombre") {
            suspendCancellableCoroutine<Unit> { c ->
                coleccion(esConductor).document(id).update("alias", alias.trim())
                    .addOnSuccessListener { if (c.isActive) c.resume(Unit) }
                    .addOnFailureListener { if (c.isActive) c.resumeWithException(it) }
            }
        }

    /**
     * Ruta pública (aparece en Buscar con el tramo automático, sin ~1 km de cada extremo) o privada
     * (no aparece). El tramo se calcula del recorrido privado del dueño; nunca se publican los extremos.
     */
    suspend fun cambiarVisibilidad(id: String, tipo: pe.cooruta.app.data.TipoTramo): Resultado<Unit> = conRed("cambiar la visibilidad") {
        val cambios: Map<String, Any> = if (tipo == pe.cooruta.app.data.TipoTramo.NINGUNO) {
            mapOf("tramoPublico" to pe.cooruta.app.data.TipoTramo.NINGUNO.valor, "recorrido" to emptyList<Any>())
        } else {
            val p = suspendCancellableCoroutine<com.google.firebase.firestore.DocumentSnapshot> { c ->
                db.collection("rutasPrivadas").document(id).get()
                    .addOnSuccessListener { if (c.isActive) c.resume(it) }
                    .addOnFailureListener { if (c.isActive) c.resumeWithException(it) }
            }
            val pts = (p.get("recorrido") as? List<*>).orEmpty().mapNotNull { m ->
                val mm = m as? Map<*, *> ?: return@mapNotNull null
                val la = (mm["lat"] as? Number)?.toDouble() ?: return@mapNotNull null
                val ln = (mm["lng"] as? Number)?.toDouble() ?: return@mapNotNull null
                Punto(la, ln)
            }
            val o = punto(p.get("origen")) ?: pts.firstOrNull()
            val d = punto(p.get("destino")) ?: pts.lastOrNull()
            if (o == null || d == null) throw IllegalStateException("No encontramos el recorrido de esta ruta. Vuelve a publicarla.")
            val tramo = if (tipo == pe.cooruta.app.data.TipoTramo.COMPLETO)
                pe.cooruta.app.data.TramoPublicado(pe.cooruta.app.data.TipoTramo.COMPLETO, pts)
            else tramoPorDefecto(pts, o, d)
            if (tramo.tipo == pe.cooruta.app.data.TipoTramo.NINGUNO)
                throw IllegalStateException("Tu ruta es muy corta para mostrarla sin revelar tu origen y destino. Vuelve a publicarla y elige el tramo en el mapa.")
            armarRutaPublica(emptyMap(), tramo).filterKeys { it == "recorrido" || it == "tramoPublico" }
                .mapValues { it.value ?: "" }
        }
        suspendCancellableCoroutine<Unit> { c ->
            db.collection("rutas").document(id).update(cambios)
                .addOnSuccessListener { if (c.isActive) c.resume(Unit) }
                .addOnFailureListener { if (c.isActive) c.resumeWithException(it) }
        }
    }

    /** Pausa o reactiva una ruta del conductor. */
    /** Días sueltos en que no viaja (solo hoy en adelante, como mucho 60). */
    suspend fun cambiarFechasLibres(id: String, fechas: Set<String>): Resultado<Unit> =
        conRed("guardar tus días libres") {
            val hoy = java.time.LocalDate.now().toString()
            val lista = fechas.filter { it >= hoy }.sorted().take(60)
            suspendCancellableCoroutine<Unit> { c ->
                db.collection("rutas").document(id).update("fechasLibres", lista)
                    .addOnSuccessListener { if (c.isActive) c.resume(Unit) }
                    .addOnFailureListener { if (c.isActive) c.resumeWithException(it) }
            }
        }

    suspend fun cambiarEstado(id: String, activa: Boolean): Resultado<Unit> =
        conRed("actualizar la ruta") {
            if (!activa) exigirSinHabituales(listOf(id))
            suspendCancellableCoroutine<Unit> { c ->
                db.collection("rutas").document(id).update("estado", if (activa) "activa" else "pausada")
                    .addOnSuccessListener { if (c.isActive) c.resume(Unit) }
                    .addOnFailureListener { if (c.isActive) c.resumeWithException(it) }
            }
        }

    /** Corre una operación de Firestore con espera máxima y errores entendibles. */
    private suspend fun <T> conRed(accion: String, bloque: suspend () -> T): Resultado<T> = try {
        Resultado.Exito(withTimeout(ESPERA_MAXIMA_MS) { bloque() })
    } catch (e: TimeoutCancellationException) {
        Resultado.Error("Sin conexión: no pudimos $accion.")
    } catch (e: FirebaseNetworkException) {
        Resultado.Error("Sin conexión a internet.")
    } catch (e: FirebaseFirestoreException) {
        Resultado.Error(
            if (e.code == FirebaseFirestoreException.Code.PERMISSION_DENIED)
                "No tienes permiso para $accion."
            else "No se pudo $accion (${e.code.name})."
        )
    } catch (e: Exception) {
        Resultado.Error(e.message ?: "No se pudo $accion.")
    }

    private fun coleccion(esConductor: Boolean) =
        db.collection(if (esConductor) "rutas" else "busquedas")

    /** Envuelve la Task de Firestore para usarla desde una corrutina. */
    private suspend fun escribir(esConductor: Boolean, id: String, datos: Map<String, Any?>) =
        suspendCancellableCoroutine<Unit> { continuacion ->
            coleccion(esConductor).document(id).set(datos)
                .addOnSuccessListener { if (continuacion.isActive) continuacion.resume(Unit) }
                .addOnFailureListener { if (continuacion.isActive) continuacion.resumeWithException(it) }
        }

    private fun lugarComoMapa(lugar: Lugar) = mapOf(
        "nombre" to lugar.nombre,
        "detalle" to lugar.detalle,
        "latitud" to lugar.latitud,
        "longitud" to lugar.longitud
    )
}
