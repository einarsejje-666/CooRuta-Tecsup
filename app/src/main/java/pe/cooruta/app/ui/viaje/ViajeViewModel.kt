package pe.cooruta.app.ui.viaje

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import pe.cooruta.app.data.EstadoViaje
import pe.cooruta.app.R
import pe.cooruta.app.data.Muestra
import pe.cooruta.app.data.PlanificadorRecorrido
import pe.cooruta.app.data.PoliticaUbicacion
import pe.cooruta.app.data.Punto
import pe.cooruta.app.data.Rumbo
import pe.cooruta.app.data.local.CooRutaBaseDatos
import pe.cooruta.app.data.repositorio.LugaresRepositorio
import pe.cooruta.app.data.repositorio.PrivadoRuta
import pe.cooruta.app.data.Resultado
import pe.cooruta.app.data.repositorio.MiRuta
import pe.cooruta.app.data.repositorio.RutasRepositorio
import pe.cooruta.app.data.repositorio.RutasRepositorio.Solicitud
import pe.cooruta.app.data.repositorio.UbicacionViaje
import pe.cooruta.app.data.repositorio.Viaje
import pe.cooruta.app.data.repositorio.ViajesRepositorio
import java.time.LocalDate

/** Viaje abierto en pantalla. [reservaId] solo para el pasajero (su propia participación). */
data class ViajeAbierto(val viajeId: String, val rutaId: String, val comoConductor: Boolean, val reservaId: String? = null)

data class EstadoViajeUi(
    /** Conductor: sus rutas activas que salen hoy. */
    val rutasHoy: List<MiRuta> = emptyList(),
    /** Rutas cuya salida de hoy ya terminó: no se ofrece «Iniciar salida» otra vez. */
    val finalizadasHoy: Set<String> = emptySet(),
    /** Reservas aceptadas de hoy (conductor: de sus rutas; pasajero: las suyas). */
    val confirmadas: List<Solicitud> = emptyList(),
    /** Recién iniciada la salida: la pantalla abre la navegación sola. */
    val navegarAlAbrir: Boolean = false,
    val errorDatos: String? = null,
    val abierto: ViajeAbierto? = null,
    val viaje: Viaje? = null,
    val viajeEnCache: Boolean = false,
    val errorViaje: String? = null,
    val ubicacion: UbicacionViaje? = null,
    val ubicacionEnCache: Boolean = false,
    /** Conductor: id de reserva → estado de recogida. */
    val paradas: Map<String, String> = emptyMap(),
    /** Conductor: id de reserva → hora (ms) de «Llegué» (para la espera de «No se presentó»). */
    val llegadas: Map<String, Long> = emptyMap(),
    /** Conductor: reservas de hoy que marcó como «No se presentó» (ya canceladas). */
    val noSePresentaron: List<Solicitud> = emptyList(),
    /** Pasajero: estado de su propia recogida. */
    val miParada: String? = null,
    val seguimientoActivo: Boolean = false,
    val ultimoEnvioMs: Long? = null,
    /** Hora GPS de la última muestra del conductor (para «esperando GPS»). */
    val ultimaMuestraMs: Long? = null,
    val errorEnvio: String? = null,
    val ocupado: Boolean = false,
    /** Conductor: recorrido por las recogidas pendientes (solo en su dispositivo). */
    val recorrido: EstadoRecorrido = EstadoRecorrido(),
    /** Conductor: su destino final (privado), para que la navegación siga después de la última bajada. */
    val destinoConductor: pe.cooruta.app.data.Punto? = null,
    /** Conductor: orientación del movimiento para la cámara; null si no es válida. */
    val rumbo: Double? = null
)

class ViajeViewModel(
    private val rutas: RutasRepositorio,
    private val viajes: ViajesRepositorio,
    calcularRecorrido: suspend (List<Punto>) -> Resultado<pe.cooruta.app.data.RutaCalculada>,
    /** Para arrancar [pe.cooruta.app.ServicioViaje]; null en pruebas. */
    private val app: android.app.Application? = null
) : ViewModel() {
    private val _estado = MutableStateFlow(EstadoViajeUi())
    val estado: StateFlow<EstadoViajeUi> = _estado.asStateFlow()

    private var datos: Job? = null
    private var observacion: Job? = null
    private var observacionPasajero: Job? = null
    private var uidCargado: String? = null
    private var pantallaVisible = false
    // La ubicación del conductor la publica ServicioViaje: sigue en segundo plano y en la navegación.
    private fun detenerServicio(escribirPausa: Boolean) = pe.cooruta.app.ServicioViaje.detener(escribirPausa)
    private val recorrido = RecorridoConductor(viewModelScope, calcular = calcularRecorrido)
    /** Destino y recorrido original de la ruta abierta (solo conductor); null si no hay parte privada. */
    private var privado: PrivadoRuta? = null
    private var privadoListo = false
    private var ultimaMuestra: Muestra? = null
    private var anclaRumbo: Muestra? = null

    init {
        viewModelScope.launch { recorrido.estado.collect { r -> _estado.update { it.copy(recorrido = r) } } }
        // Primera ubicación conocida antes del GPS del viaje: calcula el trazo sin esperar.
        viewModelScope.launch {
            pe.cooruta.app.data.UbicacionEnVivo.actual.collect { if (it != null && ultimaMuestra == null) refrescarRecorrido() }
        }
        viewModelScope.launch {
            pe.cooruta.app.ServicioViaje.estado.collect { s ->
                _estado.update { it.copy(seguimientoActivo = s.activo, ultimoEnvioMs = s.ultimoEnvioMs, ultimaMuestraMs = s.ultimaMuestraMs, errorEnvio = s.error) }
            }
        }
    }

    fun cargar(esConductor: Boolean) {
        // Otra cuenta o rol: nada del viaje anterior sigue abierto ni visible. Con otra cuenta no se
        // escribe la pausa (esa sesión ya no puede); la ubicación se verá desactualizada.
        if (uidCargado != null && uidCargado != rutas.miUid) detenerServicio(escribirPausa = false)
        cerrarVista() // cambiar de pestaña no deja de compartir la ubicación del viaje activo
        if (uidCargado != rutas.miUid) { uidCargado = rutas.miUid; _estado.value = EstadoViajeUi() }
        datos?.cancel()
        datos = viewModelScope.launch {
            val hoy = LocalDate.now()
            if (esConductor) {
                launch {
                    rutas.misRutasEnVivo().collect { r ->
                        when (r) {
                            is Resultado.Exito -> {
                                val deHoy = r.valor.filter { it.activa && !it.libreEl(hoy.toString()) && (it.dias.isEmpty() || hoy.dayOfWeek in it.dias) }
                                _estado.update { e -> e.copy(errorDatos = null, rutasHoy = deHoy) }
                                // Si falla la lectura se deja el botón: la transacción de iniciar sigue protegiendo.
                                val fin = deHoy.filter { (viajes.estadoDeHoy(it.id) as? Resultado.Exito)?.valor == EstadoViaje.FINALIZADO }
                                _estado.update { e -> e.copy(finalizadasHoy = fin.map { it.id }.toSet()) }
                            }
                            is Resultado.Error -> _estado.update { it.copy(errorDatos = r.mensaje) }
                        }
                    }
                }
                launch { rutas.solicitudesRecibidasEnVivo().collect { alRecibirReservas(it) } }
            } else {
                launch { rutas.misReservasEnVivo().collect { alRecibirReservas(it) } }
            }
        }
    }

    private fun alRecibirReservas(r: Resultado<List<Solicitud>>) {
        val hoy = LocalDate.now().toString()
        when (r) {
            is Resultado.Exito -> {
                _estado.update { e -> e.copy(errorDatos = null,
                    confirmadas = r.valor.filter { it.estado == "aceptada" && it.fecha == hoy }.sortedBy { it.horaSalida },
                    noSePresentaron = r.valor.filter { it.estado == "cancelada" && it.motivo == "no_se_presento" && it.fecha == hoy }) }
                refrescarRecorrido()
            }
            is Resultado.Error -> _estado.update { it.copy(errorDatos = r.mensaje) }
        }
    }

    /**
     * Conductor: recogidas pendientes ordenadas sobre el recorrido original y destino privado.
     * Solo pide un recorrido nuevo si cambian las paradas o el destino (ver [RecorridoConductor]).
     */
    private fun refrescarRecorrido() {
        val e = _estado.value
        val a = e.abierto?.takeIf { it.comoConductor } ?: return
        if (!privadoListo) return // evita una consulta sin destino justo antes de conocerlo
        if (e.viaje?.estado == EstadoViaje.FINALIZADO) { recorrido.detener(); return }
        val base = privado?.recorrido?.takeIf { it.size >= 2 }
            ?: e.rutasHoy.firstOrNull { it.id == a.rutaId }?.recorrido.orEmpty()
        val ordenadas = PlanificadorRecorrido.ordenarPuertaAPuerta(origenReciente() ?: base.firstOrNull(),
            PlanificadorRecorrido.pendientes(e.confirmadas, a.rutaId, e.paradas), { it.punto }, { it.reservaId }, { true })
        recorrido.actualizar(EntradaRecorrido(origenReciente(), ordenadas, privado?.destino))
    }

    // Si aún no llega la primera muestra del GPS del viaje, usa la ubicación en vivo que la app
    // ya conoce: así el trazo aparece al instante en vez de esperar varios segundos.
    private fun origenReciente(): Punto? = ultimaMuestra
        ?.takeIf { System.currentTimeMillis() - it.tomadaMs <= PoliticaUbicacion.MUESTRA_MAX_MS }?.punto
        ?: pe.cooruta.app.data.UbicacionEnVivo.actual.value

    fun reintentarRecorrido() = recorrido.reintentar(origenReciente())

    private fun olvidarRecorrido() {
        recorrido.detener()
        privado = null; privadoListo = false; ultimaMuestra = null; anclaRumbo = null
        _estado.update { it.copy(rumbo = null) }
    }

    /** Conductor: crea (o retoma) la salida de hoy de [rutaId]. Dobles toques no duplican. */
    fun iniciar(rutaId: String, alAvisar: (String) -> Unit) {
        if (_estado.value.ocupado) return
        _estado.update { it.copy(ocupado = true) }
        viewModelScope.launch {
            when (val r = viajes.iniciarSalida(rutaId)) {
                is Resultado.Exito -> {
                    observar(ViajeAbierto(r.valor, rutaId, comoConductor = true))
                    // Un toque: avisa a los pasajeros «sale en 5 minutos» y abre la navegación.
                    val estadoHoy = (viajes.estadoDeHoy(rutaId) as? Resultado.Exito)?.valor
                    if (estadoHoy == null || estadoHoy == EstadoViaje.PREPARANDO) {
                        val c = viajes.cambiarEstado(r.valor, EstadoViaje.SALIENDO)
                        if (c is Resultado.Error) alAvisar(c.mensaje)
                        else alAvisar("Avisamos a tus pasajeros que sales en 5 minutos.")
                    }
                    _estado.update { it.copy(navegarAlAbrir = true) }
                }
                is Resultado.Error -> alAvisar(r.mensaje)
            }
            _estado.update { it.copy(ocupado = false) }
        }
    }

    /** Pasajero: observa el viaje de su reserva confirmada. */
    fun verComoPasajero(s: Solicitud) =
        observar(ViajeAbierto(viajes.idViaje(s.rutaId, s.fecha), s.rutaId, comoConductor = false, reservaId = s.id))

    private fun observar(a: ViajeAbierto) {
        observacion?.cancel()
        observacionPasajero?.cancel(); observacionPasajero = null
        // Otro viaje: deja de compartir el anterior. El mismo viaje sigue compartiéndose.
        if (pe.cooruta.app.ServicioViaje.estado.value.viajeId != a.viajeId) detenerServicio(escribirPausa = false)
        olvidarRecorrido()
        _estado.update { it.copy(abierto = a, viaje = null, errorViaje = null, ubicacion = null, paradas = emptyMap(), miParada = null, destinoConductor = null) }
        observacion = viewModelScope.launch {
            if (a.comoConductor) launch {
                privado = (viajes.privadoDeRuta(a.rutaId) as? Resultado.Exito)?.valor
                privadoListo = true
                _estado.update { it.copy(destinoConductor = privado?.destino ?: privado?.recorrido?.lastOrNull()) }
                refrescarRecorrido()
            }
            launch {
                viajes.viajeEnVivo(a.viajeId).collect { r ->
                    when (r) {
                        is Resultado.Exito -> {
                            val v = r.valor.valor
                            _estado.update { it.copy(viaje = v, viajeEnCache = r.valor.desdeCache, errorViaje = null) }
                            // Terminado: la ubicación ya se borró con el cambio de estado.
                            if (a.comoConductor && v != null && !EstadoViaje.admiteUbicacion(v.estado)) {
                                detenerServicio(escribirPausa = false)
                                recorrido.detener()
                            }
                            if (!a.comoConductor) observarComoPasajero(a, v)
                        }
                        is Resultado.Error -> _estado.update { it.copy(errorViaje = r.mensaje) }
                    }
                }
            }
            if (a.comoConductor) launch {
                viajes.paradasEnVivo(a.viajeId).collect { r ->
                    if (r is Resultado.Exito) { _estado.update { it.copy(paradas = r.valor) }; refrescarRecorrido() }
                }
            }
            if (a.comoConductor) launch {
                viajes.llegadasEnVivo(a.viajeId).collect { m -> _estado.update { it.copy(llegadas = m) } }
            }
        }
    }

    /**
     * Pasajero: ubicación y su propia parada solo existen para él cuando el viaje existe y no
     * terminó (las reglas lo exigen). Se (re)empieza a escuchar cuando eso ocurre.
     */
    private fun observarComoPasajero(a: ViajeAbierto, v: Viaje?) {
        if (v == null || v.estado == EstadoViaje.FINALIZADO) {
            observacionPasajero?.cancel(); observacionPasajero = null
            _estado.update { it.copy(ubicacion = null) }
            return
        }
        if (observacionPasajero?.isActive == true) return
        observacionPasajero = viewModelScope.launch {
            launch {
                viajes.ubicacionEnVivo(a.viajeId).collect { r ->
                    when (r) {
                        is Resultado.Exito -> _estado.update { it.copy(ubicacion = r.valor.valor, ubicacionEnCache = r.valor.desdeCache) }
                        // Reserva ya no aceptada o sin acceso: deja de verse la ubicación.
                        is Resultado.Error -> _estado.update { it.copy(ubicacion = null) }
                    }
                }
            }
            launch {
                viajes.miParadaEnVivo(a.viajeId, a.reservaId.orEmpty()).collect { r ->
                    if (r is Resultado.Exito) _estado.update { it.copy(miParada = r.valor.valor) }
                }
            }
        }
    }

    /** Salir del viaje («‹»): deja de compartir la ubicación. */
    fun cerrar() {
        detenerServicio(escribirPausa = _estado.value.viaje?.estado != EstadoViaje.FINALIZADO)
        cerrarVista()
    }

    /** Cierra la vista del viaje sin tocar el envío de ubicación. */
    private fun cerrarVista() {
        olvidarRecorrido()
        observacion?.cancel()
        observacion = null
        observacionPasajero?.cancel()
        observacionPasajero = null
        _estado.update { it.copy(abierto = null, viaje = null, ubicacion = null, paradas = emptyMap(), miParada = null) }
    }

    fun navegacionAbierta() = _estado.update { it.copy(navegarAlAbrir = false) }

    fun cambiarEstado(nuevo: String, alAvisar: (String) -> Unit) {
        val id = _estado.value.abierto?.takeIf { it.comoConductor }?.viajeId ?: return
        if (_estado.value.ocupado) return
        _estado.update { it.copy(ocupado = true) }
        viewModelScope.launch {
            val r = viajes.cambiarEstado(id, nuevo)
            if (r is Resultado.Error) alAvisar(r.mensaje)
            if (r is Resultado.Exito && nuevo == EstadoViaje.FINALIZADO) {
                detenerServicio(escribirPausa = false)
                val rutaId = _estado.value.abierto?.rutaId
                if (rutaId != null) _estado.update { it.copy(finalizadasHoy = it.finalizadasHoy + rutaId) }
            }
            _estado.update { it.copy(ocupado = false) }
        }
    }

    fun marcar(s: Solicitud, nuevo: String, alAvisar: (String) -> Unit) {
        val id = _estado.value.abierto?.takeIf { it.comoConductor }?.viajeId ?: return
        viewModelScope.launch {
            val r = viajes.marcarParada(id, s, nuevo)
            if (r is Resultado.Error) alAvisar(r.mensaje)
        }
    }

    /** «No se presentó»: la función valida la espera de 5 min y cancela solo la reserva de hoy. */
    fun noSePresento(s: Solicitud, alAvisar: (String) -> Unit) {
        val id = _estado.value.abierto?.takeIf { it.comoConductor }?.viajeId ?: return
        viewModelScope.launch {
            when (val r = viajes.marcarNoSePresento(id, s.id)) {
                is Resultado.Error -> alAvisar(r.mensaje)
                is Resultado.Exito -> alAvisar("Marcado: ${s.nombrePasajero.ifBlank { "el pasajero" }} no se presentó. Se liberó su asiento.")
            }
        }
    }

    // ------------------------------------------------------------ ubicación del conductor

    /** Llamado al mostrarse u ocultarse la pantalla del viaje (ciclo de vida). */
    fun pantallaVisible(visible: Boolean) {
        pantallaVisible = visible
        if (visible) reanudarSeguimiento() else pausarSeguimiento()
    }

    /** Pantalla del viaje visible (conductor): se comparte la ubicación real. */
    private fun reanudarSeguimiento() {
        val e = _estado.value
        if (!pantallaVisible) return
        val a = e.abierto?.takeIf { it.comoConductor } ?: return
        if (e.viaje != null && !EstadoViaje.admiteUbicacion(e.viaje.estado)) return
        app?.let { pe.cooruta.app.ServicioViaje.iniciar(it, a.viajeId) }
    }

    /** Pantalla oculta o cerrada: se deja de enviar y se intenta marcar como pausado. */
    fun pausarSeguimiento() = Unit // con un viaje activo, ServicioViaje sigue compartiendo en segundo plano

    /** Muestra real del GPS, pedida periódicamente mientras la pantalla del viaje está activa. */
    fun alRecibirMuestra(m: Muestra) {
        val e = _estado.value
        if (e.abierto?.comoConductor != true) return
        if (e.viaje == null || !EstadoViaje.admiteUbicacion(e.viaje.estado)) return
        if (System.currentTimeMillis() - m.tomadaMs > PoliticaUbicacion.MUESTRA_MAX_MS) return // vieja: ni cámara ni recorrido
        val rumbo = Rumbo.actualizar(anclaRumbo, m, e.rumbo)
        anclaRumbo = Rumbo.nuevaAncla(anclaRumbo, m)
        ultimaMuestra = m
        if (rumbo != e.rumbo) _estado.update { it.copy(rumbo = rumbo) }
        refrescarRecorrido() // no consulta Directions si las paradas no cambiaron
    }

    override fun onCleared() {
        // viewModelScope ya no corre: la pausa queda en la cola de escrituras de Firestore.
        // ServicioViaje sigue compartiendo hasta que el viaje termine o el conductor lo detenga.
        super.onCleared()
    }

    companion object {
        val Fabrica: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY]!!
                // Mismo cliente de Directions que el asistente de rutas.
                val lugares = LugaresRepositorio(
                    recientesDao = CooRutaBaseDatos.obtener(app).lugaresRecientes()
                )
                ViajeViewModel(RutasRepositorio(), ViajesRepositorio(), lugares::recorridoPor, app)
            }
        }
    }
}
