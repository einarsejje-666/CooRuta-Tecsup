package pe.cooruta.app.ui.inicio

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.launch
import pe.cooruta.app.AvisosLocales
import pe.cooruta.app.data.DetectorAvisos
import pe.cooruta.app.data.Resultado
import pe.cooruta.app.data.repositorio.RutasRepositorio

/** Escucha reservas mientras la app vive y muestra avisos locales al cambiar su estado. */
class AvisosViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = RutasRepositorio()
    private var escucha: Job? = null
    private var rol: Boolean? = null

    // Pasajero: viajes de hoy de sus reservas aceptadas, para avisar «sale en 5 min» y «en camino».
    private val viajesVigilados = mutableMapOf<String, Job>()
    private val viajesRepo = pe.cooruta.app.data.repositorio.ViajesRepositorio()
    private val publicando = mutableMapOf<String, Job>()

    /** Deja de compartir la ubicación en [vid] y la borra (el conductor no conserva la última posición). */
    private fun dejarDePublicar(vid: String) {
        publicando.remove(vid)?.let { it.cancel(); viajesRepo.quitarMiUbicacion(vid) }
    }

    private fun vigilarViajes(reservas: List<pe.cooruta.app.data.repositorio.RutasRepositorio.Solicitud>) {
        val hoy = java.time.LocalDate.now().toString()
        val activas = reservas.filter { it.estado == "aceptada" && it.fecha == hoy }.associateBy { viajesRepo.idViaje(it.rutaId, it.fecha) }
        (viajesVigilados.keys - activas.keys).forEach { viajesVigilados.remove(it)?.cancel() }
        (publicando.keys - activas.keys).forEach(::dejarDePublicar)
        vigilarDiasLibres(reservas)
        activas.forEach { (vid, s) ->
            if (vid in viajesVigilados) return@forEach
            viajesVigilados[vid] = viewModelScope.launch {
                var anterior: String? = null
                viajesRepo.viajeEnVivo(vid).collect { r ->
                    val estado = (r as? Resultado.Exito)?.valor?.valor?.estado ?: return@collect
                    val conductor = s.nombreConductor.ifBlank { "Tu conductor" }
                    if (anterior != null && estado != anterior) when (estado) {
                        pe.cooruta.app.data.EstadoViaje.SALIENDO -> AvisosLocales.mostrar(getApplication(), "${vid}_sale".hashCode(),
                            "Tu conductor sale en 5 minutos", "$conductor inicia su recorrido en 5 minutos. Ve a ${s.subida}.")
                        pe.cooruta.app.data.EstadoViaje.EN_CURSO -> AvisosLocales.mostrar(getApplication(), "${vid}_curso".hashCode(),
                            "Tu conductor está en camino", "$conductor ya va hacia ${s.subida}.")
                    }
                    // Primera lectura: si ya estaba «saliendo», también se avisa (no lo habíamos visto).
                    if (anterior == null && estado == pe.cooruta.app.data.EstadoViaje.SALIENDO)
                        AvisosLocales.mostrar(getApplication(), "${vid}_sale".hashCode(),
                            "Tu conductor sale en 5 minutos", "$conductor inicia su recorrido en 5 minutos. Ve a ${s.subida}.")
                    anterior = estado
                    // Mientras el conductor sale o va en camino, el pasajero comparte su ubicación con él.
                    val enRuta = estado == pe.cooruta.app.data.EstadoViaje.SALIENDO || estado == pe.cooruta.app.data.EstadoViaje.EN_CURSO
                    if (enRuta && publicando[vid]?.isActive != true) publicando[vid] = viewModelScope.launch {
                        pe.cooruta.app.data.UbicacionEnVivo.iniciar(getApplication())
                        var ultimo = 0L
                        pe.cooruta.app.data.UbicacionEnVivo.actual.collect { p ->
                            if (p != null && System.currentTimeMillis() - ultimo >= 5_000) {
                                ultimo = System.currentTimeMillis()
                                viajesRepo.publicarMiUbicacion(vid, p)
                            }
                        }
                    }
                    if (!enRuta) dejarDePublicar(vid)
                }
            }
        }
    }

    // Pasajero: si el conductor marca «no viajo» un día con reserva aceptada, se le avisa.
    private val rutasVigiladas = mutableMapOf<String, Job>()

    private fun vigilarDiasLibres(reservas: List<pe.cooruta.app.data.repositorio.RutasRepositorio.Solicitud>) {
        val hoy = java.time.LocalDate.now().toString()
        val porRuta = reservas.filter { it.estado == "aceptada" && it.fecha >= hoy }.groupBy { it.rutaId }
        (rutasVigiladas.keys - porRuta.keys).forEach { rutasVigiladas.remove(it)?.cancel() }
        porRuta.forEach { (rutaId, lista) ->
            if (rutaId in rutasVigiladas) return@forEach
            rutasVigiladas[rutaId] = viewModelScope.launch {
                kotlinx.coroutines.flow.callbackFlow {
                    val r = com.google.firebase.firestore.FirebaseFirestore.getInstance().collection("rutas").document(rutaId)
                        .addSnapshotListener { d, _ -> if (d != null) trySend((d.get("fechasLibres") as? List<*>).orEmpty().filterIsInstance<String>().toSet()) }
                    awaitClose { r.remove() }
                }.collect { libres ->
                    lista.filter { it.fecha in libres }.forEach { s ->
                        val fecha = runCatching { java.time.LocalDate.parse(s.fecha)
                            .format(java.time.format.DateTimeFormatter.ofPattern("EEEE d", java.util.Locale("es", "PE"))) }.getOrDefault(s.fecha)
                        AvisosLocales.mostrar(getApplication(), "${s.id}_libre".hashCode(), "Tu conductor no viajará",
                            "${s.nombreConductor.ifBlank { "El conductor" }} no hará el viaje del $fecha. Busca otra ruta para ese día.")
                    }
                }
            }
        }
    }

    // Ambos roles: mensajes nuevos del otro en los chats de reservas vigentes → aviso y banner.
    private val chatsVigilados = mutableMapOf<String, Job>()
    private val chatRepo = pe.cooruta.app.data.repositorio.ChatRepositorio()
    private val _entrante = kotlinx.coroutines.flow.MutableStateFlow<MensajeEntrante?>(null)
    val entrante: kotlinx.coroutines.flow.StateFlow<MensajeEntrante?> = _entrante
    fun descartarEntrante() { _entrante.value = null }

    private fun vigilarChats(reservas: List<pe.cooruta.app.data.repositorio.RutasRepositorio.Solicitud>, esConductor: Boolean) {
        val hoy = java.time.LocalDate.now().toString()
        val vigentes = reservas.filter { (it.estado == "pendiente" || it.estado == "aceptada") && it.fecha >= hoy }.associateBy { it.id }
        (chatsVigilados.keys - vigentes.keys).forEach { chatsVigilados.remove(it)?.cancel() }
        vigentes.forEach { (id, s) ->
            if (id in chatsVigilados) return@forEach
            chatsVigilados[id] = viewModelScope.launch {
                var vistos: Set<String>? = null
                chatRepo.mensajesEnVivo(id).collect { r ->
                    val lista = (r as? Resultado.Exito)?.valor ?: return@collect
                    val antes = vistos
                    vistos = lista.map { it.id }.toSet()
                    if (antes == null) return@collect // primera lectura: lo que ya había no se avisa
                    val yo = chatRepo.miUid
                    lista.filter { it.id !in antes && it.autorId != yo && !it.enviandoLocal }.lastOrNull()?.let { m ->
                        if (ChatAbierto.id == id) return@let
                        val otro = (if (esConductor) s.nombrePasajero else s.nombreConductor).ifBlank { if (esConductor) "Pasajero" else "Conductor" }
                        AvisosLocales.mostrar(getApplication(), "${id}_msg".hashCode(), "Mensaje de $otro", m.texto)
                        _entrante.value = MensajeEntrante(s, otro, m.texto)
                    }
                }
            }
        }
    }

    fun escuchar(esConductor: Boolean) {
        if (rol == esConductor && escucha?.isActive == true) return
        rol = esConductor
        escucha?.cancel()
        chatsVigilados.values.forEach { it.cancel() }; chatsVigilados.clear()
        // Al cambiar de rol no sigue nada del anterior (en especial, la ubicación compartida como pasajero).
        viajesVigilados.values.forEach { it.cancel() }; viajesVigilados.clear()
        publicando.keys.toList().forEach(::dejarDePublicar)
        rutasVigiladas.values.forEach { it.cancel() }; rutasVigiladas.clear()
        val detector = DetectorAvisos(esConductor)
        escucha = viewModelScope.launch {
            (if (esConductor) repo.solicitudesRecibidasEnVivo() else repo.misReservasEnVivo()).collect { r ->
                if (r is Resultado.Exito) {
                    detector.procesar(r.valor).forEach {
                        AvisosLocales.mostrar(getApplication(), it.clave.hashCode(), it.titulo, it.texto)
                    }
                    if (!esConductor) vigilarViajes(r.valor)
                    vigilarChats(r.valor, esConductor)
                }
            }
        }
    }
}
