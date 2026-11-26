package pe.cooruta.app.ui.inicio

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import pe.cooruta.app.R
import pe.cooruta.app.data.Coincidencia
import pe.cooruta.app.data.Resultado
import pe.cooruta.app.data.buscarCoincidencias
import pe.cooruta.app.data.local.CooRutaBaseDatos
import pe.cooruta.app.data.repositorio.LugaresRepositorio
import pe.cooruta.app.data.repositorio.MiRuta
import pe.cooruta.app.data.repositorio.RutasRepositorio

data class EstadoMiRuta(
    val cargando: Boolean = true,
    val rutas: List<MiRuta> = emptyList(),
    val error: String? = null,
    /** Id de la ruta que se está pausando/reactivando. */
    val cambiando: String? = null,
    /** Pasajeros que avisaron que viajan hoy (en vivo). */
    val pedidos: List<MiRuta> = emptyList(),
    val errorPedidos: String? = null,
    /** Solicitudes de asiento de hoy a mis rutas. */
    val solicitudes: List<RutasRepositorio.Solicitud> = emptyList(),
    val errorSolicitudes: String? = null,
    val respondiendo: String? = null,
    val miUid: String? = null,
    val nombres: Map<String, String> = emptyMap()
) {
    /** Pasajero + cómo encaja en una de mis rutas de hoy, de mayor a menor coincidencia. */
    private val hoy: String get() = java.time.LocalDate.now().toString()

    /** Pendientes de hoy en adelante. */
    val pendientes get() = solicitudes.filter { (it.estado == "pendiente" || it.estado == "invitada") && it.fecha >= hoy }

    /** Confirmados de hoy en adelante, agrupados por (ruta, fecha). */
    val confirmados: Map<Pair<String, String>, List<RutasRepositorio.Solicitud>>
        get() = solicitudes.filter { it.estado == "aceptada" && it.fecha >= hoy }
            .sortedBy { it.fecha }.groupBy { it.rutaId to it.fecha }

    /** Rechazadas o canceladas (historial). */
    val cerradas get() = solicitudes.filter { it.estado == "rechazada" || it.estado == "cancelada" }

    /** Confirmados hoy por ruta, para la tarjeta de Mi ruta. */
    fun confirmadosHoy(rutaId: String) = confirmados[rutaId to hoy].orEmpty()

    val pasajerosHoy: List<Pair<MiRuta, Coincidencia>>
        get() {
            val hoy = java.time.LocalDate.now().dayOfWeek
            val mias = rutas.filter { it.activa && hoy in it.dias }
            val fecha = java.time.LocalDate.now().toString()
            val yaPidieron = solicitudes.filter { it.fecha == fecha && (it.estado == "pendiente" || it.estado == "aceptada") }
                .map { it.pasajeroId }.toSet()
            // En pedidos, conductorId guarda el uid del pasajero (ver pedidosDeHoyEnVivo).
            return pedidos.filter { it.conductorId !in yaPidieron }.mapNotNull { p ->
                mias.flatMap { buscarCoincidencias(p.copy(dias = emptySet()), listOf(it)) }
                    .maxByOrNull { it.porcentaje }?.let { p to it }
            }.sortedByDescending { it.second.porcentaje }
        }
}

/** Inicio del conductor: sus rutas publicadas, leídas de Firestore. */
class MiRutaViewModel(
    private val repo: RutasRepositorio,
    private val lugares: LugaresRepositorio
) : ViewModel() {
    private var escucha: kotlinx.coroutines.Job? = null
    private var escuchaSolicitudes: kotlinx.coroutines.Job? = null
    private var escuchaRutas: kotlinx.coroutines.Job? = null

    private val _estado = MutableStateFlow(EstadoMiRuta())
    val estado: StateFlow<EstadoMiRuta> = _estado.asStateFlow()

    fun cargar() {
        _estado.update { it.copy(cargando = true, error = null, miUid = repo.miUid) }
        escuchaRutas?.cancel()
        escuchaRutas = viewModelScope.launch {
            repo.misRutasEnVivo().collect { r ->
                when (r) {
                    is Resultado.Exito -> _estado.update { it.copy(cargando = false, rutas = r.valor, error = null) }
                    is Resultado.Error -> _estado.update { it.copy(cargando = false, error = r.mensaje) }
                }
                nombrarPuntos()
            }
        }
        escuchaSolicitudes?.cancel()
        escuchaSolicitudes = viewModelScope.launch {
            repo.solicitudesRecibidasEnVivo().collect { r ->
                when (r) {
                    is Resultado.Exito -> _estado.update { it.copy(solicitudes = r.valor, errorSolicitudes = null) }
                    is Resultado.Error -> _estado.update { it.copy(errorSolicitudes = r.mensaje) }
                }
            }
        }
        escucha?.cancel()
        escucha = viewModelScope.launch {
            repo.pedidosDeHoyEnVivo().collect { r ->
                when (r) {
                    is Resultado.Exito -> _estado.update { it.copy(pedidos = r.valor, errorPedidos = null) }
                    is Resultado.Error -> _estado.update { it.copy(errorPedidos = r.mensaje) }
                }
                nombrarPuntos()
            }
        }
    }

    fun responder(s: RutasRepositorio.Solicitud, aceptar: Boolean, alAvisar: (String) -> Unit) {
        if (_estado.value.respondiendo != null) return
        _estado.update { it.copy(respondiendo = s.id) }
        viewModelScope.launch {
            when (val r = repo.responderSolicitud(s, aceptar)) {
                is Resultado.Exito -> {
                    alAvisar(if (aceptar) "Aceptaste a ${s.nombrePasajero}." else "Rechazaste la solicitud.")
                }
                is Resultado.Error -> alAvisar(r.mensaje)
            }
            _estado.update { it.copy(respondiendo = null) }
        }
    }

    fun invitar(s: RutasRepositorio.Solicitud, alAvisar: (String) -> Unit) {
        if (_estado.value.respondiendo != null) return
        _estado.update { it.copy(respondiendo = s.id) }
        viewModelScope.launch {
            val r = repo.invitarDeNuevo(s)
            alAvisar(if (r is Resultado.Error) r.mensaje
                else "Invitamos a ${s.nombrePasajero.substringBefore(" ").ifBlank { "tu pasajero" }}. Si acepta, aparecerá en Pendientes.")
            _estado.update { it.copy(respondiendo = null) }
        }
    }

    fun reconsiderar(s: RutasRepositorio.Solicitud, alAvisar: (String) -> Unit) {
        if (_estado.value.respondiendo != null) return
        _estado.update { it.copy(respondiendo = s.id) }
        viewModelScope.launch {
            val r = repo.reconsiderarSolicitud(s)
            alAvisar(if (r is Resultado.Error) r.mensaje else "La solicitud de ${s.nombrePasajero} volvió a pendiente.")
            _estado.update { it.copy(respondiendo = null) }
        }
    }

    /** Nombre de la calle donde sube y baja cada pasajero (solo los que faltan). */
    private fun nombrarPuntos() {
        val e = _estado.value
        val faltan = e.pasajerosHoy.flatMap { listOf(it.second.subida, it.second.bajada) }
            .filter { clave(it) !in e.nombres }.distinctBy { clave(it) }.take(20)
        if (faltan.isEmpty()) return
        viewModelScope.launch {
            for (p in faltan) {
                val n = lugares.direccionDe(p.latitud, p.longitud)?.nombre ?: continue
                _estado.update { it.copy(nombres = it.nombres + (clave(p) to n)) }
            }
        }
    }

    /** Devuelve un mensaje para mostrar si algo falla. */
    fun alternarPausa(ruta: MiRuta, alAvisar: (String) -> Unit) {
        if (_estado.value.cambiando != null) return
        _estado.update { it.copy(cambiando = ruta.id) }
        viewModelScope.launch {
            when (val r = repo.cambiarEstado(ruta.id, !ruta.activa)) {
                is Resultado.Exito -> _estado.update { e ->
                    e.copy(rutas = e.rutas.map { if (it.id == ruta.id) it.copy(activa = !ruta.activa) else it })
                }
                is Resultado.Error -> alAvisar(r.mensaje)
            }
            _estado.update { it.copy(cambiando = null) }
        }
    }

    /** Hace pública la ruta completa (vuelve a aparecer en Buscar). */
    fun hacerPublica(ruta: MiRuta, alAvisar: (String) -> Unit) {
        if (_estado.value.cambiando != null) return
        _estado.update { it.copy(cambiando = ruta.id) }
        viewModelScope.launch {
            when (val r = repo.cambiarVisibilidad(ruta.id, pe.cooruta.app.data.TipoTramo.COMPLETO)) {
                is Resultado.Exito -> { alAvisar("Tu ruta ya es pública y aparece en Buscar."); cargar() }
                is Resultado.Error -> alAvisar(r.mensaje)
            }
            _estado.update { it.copy(cambiando = null) }
        }
    }

    companion object {
        val Fabrica: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY]!!
                MiRutaViewModel(
                    RutasRepositorio(),
                    LugaresRepositorio(
                        recientesDao = CooRutaBaseDatos.obtener(app).lugaresRecientes()
                    )
                )
            }
        }
    }
}
