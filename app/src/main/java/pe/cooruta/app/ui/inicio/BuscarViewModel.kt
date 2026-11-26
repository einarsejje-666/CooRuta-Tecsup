package pe.cooruta.app.ui.inicio

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import pe.cooruta.app.BuildConfig
import pe.cooruta.app.R
import pe.cooruta.app.data.Coincidencia
import pe.cooruta.app.data.Punto
import pe.cooruta.app.data.Resultado
import pe.cooruta.app.data.buscarCoincidencias
import pe.cooruta.app.data.local.CooRutaBaseDatos
import pe.cooruta.app.data.repositorio.ExplicacionesRepositorio
import pe.cooruta.app.data.repositorio.LugaresRepositorio
import pe.cooruta.app.data.repositorio.PerfilPublico
import pe.cooruta.app.data.repositorio.PerfilesRepositorio
import pe.cooruta.app.data.repositorio.MiRuta
import pe.cooruta.app.data.repositorio.ResumenOpcion
import pe.cooruta.app.data.repositorio.RutasRepositorio

data class EstadoBuscar(
    val cargando: Boolean = true,
    /** Deslizar hacia abajo para actualizar. */
    val refrescando: Boolean = false,
    val error: String? = null,
    val texto: String = "",
    val miUid: String? = null,
    /** Todas las rutas activas (en vivo). */
    val rutas: List<MiRuta> = emptyList(),
    /** Trayectos que el pasajero guardó; uno está elegido. */
    val misTrayectos: List<MiRuta> = emptyList(),
    val trayectoId: String? = null,
    /** true = solo conductores que hacen su ruta hoy. */
    val soloHoy: Boolean = false,
    val soloEmpresa: Boolean = false,
    /** Mis conductores (favoritos) y el filtro «Mis conductores». */
    val favoritos: Set<String> = emptySet(),
    val soloFavoritos: Boolean = false,
    val companerosEmpresa: Set<String> = emptySet(),
    /** true cuando los conductores ya ven que viaja hoy. */
    val avisadoHoy: Boolean = false,
    /** rutaId → estado de mi solicitud de hoy. */
    val solicitudes: Map<String, String> = emptyMap(),
    /** rutaId → motivo de cancelación de mi solicitud de hoy. */
    val motivosSolicitud: Map<String, String> = emptyMap(),
    /** rutaId → id del documento de mi solicitud de hoy. */
    val idsSolicitud: Map<String, String> = emptyMap(),
    /** rutaId → dónde dice que baja mi solicitud de hoy (para ofrecer actualizarla si cambió). */
    val bajadasSolicitud: Map<String, String> = emptyMap(),
    val errorSolicitudes: String? = null,
    /** rutaId de la solicitud que se está enviando o cancelando. */
    val enviando: String? = null,
    /** Nombres de calles de subida/bajada ya consultados ("lat,lng" → nombre). */
    val nombres: Map<String, String> = emptyMap(),
    /** conductorId → perfil público (calificación y vehículo). */
    val perfiles: Map<String, PerfilPublico> = emptyMap(),
    /** Lugar encontrado para el texto del buscador (como en el mapa); null si aún no hay. */
    val lugarBuscado: pe.cooruta.app.data.Lugar? = null,
    val buscandoLugar: Boolean = false,
    /** Sugerencias de lugares mientras se escribe (como en el mapa). */
    val sugerencias: List<pe.cooruta.app.data.Sugerencia> = emptyList(),
    /** Trayecto calculado al vuelo: mi ubicación → destino elegido en el buscador. */
    val trayectoRapido: MiRuta? = null,
    /** Últimos destinos elegidos en el buscador (guardados en el teléfono). */
    val recientes: List<pe.cooruta.app.data.Lugar> = emptyList(),
    /** rutaId → tramo de las rutas privadas de los grupos que integro (invitación del conductor). */
    val grupo: Map<String, List<Punto>> = emptyMap()
) {
    /** Rutas para buscar: las privadas de mis grupos llevan su tramo de grupo (y se marcan «grupo»). */
    val rutasVisibles: List<MiRuta>
        get() = if (grupo.isEmpty()) rutas else rutas.map { r ->
            if (r.recorrido.size >= 2) r else grupo[r.id]?.let { r.copy(recorrido = it, tramoPublico = "grupo") } ?: r
        }
    /** Mi perfil público (para comparar empresa). */
    val miPerfil: PerfilPublico? get() = miUid?.let { perfiles[it] }
    /** Rutas visibles que pasan cerca del lugar buscado (no hace falta trayecto), con su distancia en m. */
    val rutasCercaDelLugar: List<Pair<MiRuta, Int>>
        get() {
            val l = lugarBuscado ?: return emptyList()
            val p = Punto(l.latitud, l.longitud)
            return rutasVisibles.filter { it.visibleEnBuscar && !it.libreHoy && (!soloEmpresa || comunidadDe(it.conductorId) != null) }
                .mapNotNull { r ->
                    val puntos = r.recorrido + r.recorrido.zipWithNext().map { (a, b) -> Punto((a.latitud + b.latitud) / 2, (a.longitud + b.longitud) / 2) }
                    val d = puntos.minOfOrNull { pe.cooruta.app.data.metros(it, p) } ?: return@mapNotNull null
                    if (d <= RADIO_LUGAR_M) r to d.toInt() else null
                }.sortedBy { it.second }
        }
    /** Nombre del punto de bajada: el lugar elegido en el buscador si queda cerca; si no, la calle y a cuánto está. */
    fun nombreBajada(c: Coincidencia): String? {
        val calle = nombres[clave(c.bajada)]
        val l = lugarBuscado?.takeIf { trayectoRapido != null } ?: return calle
        val d = pe.cooruta.app.data.metros(c.bajada, Punto(l.latitud, l.longitud)).toInt()
        return if (d <= 150) l.nombre else "${calle ?: "Punto de bajada"} (a $d m de ${l.nombre})"
    }
    val tengoEmpresa: Boolean get() = miPerfil?.empresaClave?.isNotBlank() == true
    /** Cómo coincide la empresa de este conductor con la mía (verificada, declarada o invitación del operador). */
    fun comunidadDe(conductorId: String): pe.cooruta.app.data.Comunidad.Tipo? =
        if (conductorId in companerosEmpresa) pe.cooruta.app.data.Comunidad.Tipo.VERIFICADA
        else pe.cooruta.app.data.Comunidad.coincidencia(miPerfil, perfiles[conductorId])

    /** El guardado que eligió el pasajero (tarjeta «Hoy»). */
    val trayectoGuardado: MiRuta? get() = misTrayectos.firstOrNull { it.id == trayectoId }
    /** El que se usa para buscar: el rápido (ubicación → destino) tiene prioridad. */
    val trayecto: MiRuta? get() = trayectoRapido ?: trayectoGuardado

    /** Coincidencias con el trayecto elegido, de mayor a menor, filtradas por el texto. */
    val coincidencias: List<Coincidencia>
        get() {
            val t = trayecto ?: return emptyList()
            val q = texto.trim()
            val hoy = java.time.LocalDate.now().dayOfWeek
            val base = if (soloHoy) t.copy(dias = setOf(hoy)) else t
            // Compañeros de empresa primero (orden estable: dentro de cada grupo, por coincidencia).
            val lugar = lugarBuscado?.let { Punto(it.latitud, it.longitud) }
            return buscarCoincidencias(base, rutasVisibles.filter { !it.libreHoy && (!soloHoy || hoy in it.dias) && (!soloEmpresa || comunidadDe(it.conductorId) != null) &&
                (!soloFavoritos || it.conductorId in favoritos) }).filter { c ->
                // Con un lugar encontrado: solo rutas que pasan cerca de él. Si no, por nombre.
                q.isEmpty() || trayectoRapido != null || (lugar != null && pasaCerca(c.ruta.recorrido, lugar)) ||
                    listOf(c.ruta.origen, c.ruta.destino, c.ruta.nombre).any { it.contains(q, true) }
            }.sortedWith(compareBy({ if (it.ruta.conductorId in favoritos) 0 else 1 }, { comunidadDe(it.ruta.conductorId)?.ordinal ?: 2 }))
        }
}

/** Distancia máxima entre el lugar buscado y el recorrido para decir que "pasa por ahí". */
const val RADIO_LUGAR_M = 600.0

fun pasaCerca(recorrido: List<Punto>, p: Punto, radio: Double = RADIO_LUGAR_M): Boolean =
    recorrido.any { pe.cooruta.app.data.metros(it, p) <= radio } ||
        recorrido.zipWithNext().any { (a, b) ->
            pe.cooruta.app.data.metros(Punto((a.latitud + b.latitud) / 2, (a.longitud + b.longitud) / 2), p) <= radio
        }

fun clave(p: Punto) = "%.4f,%.4f".format(p.latitud, p.longitud)

/** Pestaña Buscar del pasajero: rutas que coinciden con su trayecto, en tiempo real. */
class BuscarViewModel(
    private val repo: RutasRepositorio,
    private val lugares: LugaresRepositorio,
    explicaciones: ExplicacionesRepositorio,
    private val perfiles: PerfilesRepositorio = PerfilesRepositorio()
) : ViewModel() {

    private val _estado = MutableStateFlow(EstadoBuscar())
    val estado: StateFlow<EstadoBuscar> = _estado.asStateFlow()

    /** Explicaciones de la IA para las primeras coincidencias (no cambian orden, tarifa ni reservas). */
    private val ia = CoordinadorExplicaciones(viewModelScope, explicaciones::explicar)
    val estadoIa: StateFlow<EstadoExplicaciones> = ia.estado

    init {
        viewModelScope.launch { lugares.recientes.collect { r -> _estado.update { it.copy(recientes = r) } } }
        // Solo cuando cambian los datos enviados de las primeras opciones, no por recomposición.
        viewModelScope.launch {
            _estado.map { e -> e.coincidencias.take(OPCIONES_IA).map(::resumen) }
                .distinctUntilChanged()
                .collect { ia.actualizar(it) }
        }
    }

    fun reintentarIa() = ia.reintentar()

    private fun resumen(c: Coincidencia) = ResumenOpcion(
        salida = "%02d:%02d".format(c.ruta.salida.hour, c.ruta.salida.minute),
        diferenciaMin = c.diferenciaMin,
        caminaSubirM = c.caminaSubirM,
        caminaBajarM = c.caminaBajarM,
        pasajeCentimos = c.pasajeCentimos,
        porcentaje = c.porcentaje,
        empresa = when (_estado.value.comunidadDe(c.ruta.conductorId)) {
            pe.cooruta.app.data.Comunidad.Tipo.VERIFICADA -> "de tu misma empresa (verificada)"
            pe.cooruta.app.data.Comunidad.Tipo.DECLARADA -> "dice trabajar en tu misma empresa (no verificado)"
            null -> ""
        }
    )
    private var escucha: Job? = null
    private var escuchaSolicitudes: Job? = null

    fun refrescar() = cargar(refresco = true)

    fun cargar(refresco: Boolean = false) {
        // Otra cuenta en el mismo dispositivo: no se muestra nada de la anterior.
        if (_estado.value.miUid != null && _estado.value.miUid != repo.miUid) _estado.value = EstadoBuscar()
        _estado.update {
            if (refresco) it.copy(refrescando = true, error = null, miUid = repo.miUid)
            else it.copy(cargando = true, error = null, miUid = repo.miUid)
        }
        // Mi perfil se relee siempre: la empresa pudo cambiar en Perfil.
        repo.miUid?.let { uid -> viewModelScope.launch {
            val p = perfiles.perfiles(setOf(uid))
            _estado.update { it.copy(perfiles = it.perfiles + p) }
        } }
        viewModelScope.launch {
            when (val r = repo.misBusquedas()) {
                is Resultado.Exito -> _estado.update { e ->
                    val lista = r.valor.sortedByDescending { it.creadaMs }
                    e.copy(misTrayectos = lista,
                        trayectoId = e.trayectoId?.takeIf { id -> lista.any { it.id == id } } ?: lista.firstOrNull()?.id)
                }
                is Resultado.Error -> _estado.update { it.copy(error = r.mensaje) }
            }
            nombrarPuntos()
        }
        escuchaSolicitudes?.cancel()
        escuchaSolicitudes = viewModelScope.launch {
            repo.misReservasEnVivo().collect { r ->
                when (r) {
                    is Resultado.Exito -> {
                        val hoy = r.valor.filter { it.fecha == java.time.LocalDate.now().toString() }
                        _estado.update { it.copy(
                            solicitudes = hoy.associate { s -> s.rutaId to s.estado },
                            motivosSolicitud = hoy.associate { s -> s.rutaId to s.motivo },
                            idsSolicitud = hoy.associate { s -> s.rutaId to s.id },
                            bajadasSolicitud = hoy.associate { s -> s.rutaId to s.bajada },
                            errorSolicitudes = null
                        ) }
                    }
                    is Resultado.Error -> _estado.update { it.copy(errorSolicitudes = r.mensaje) }
                }
            }
        }
        cargarGrupos()
        escucha?.cancel()
        escucha = viewModelScope.launch {
            repo.rutasActivasEnVivo().collect { r ->
                when (r) {
                    is Resultado.Exito -> {
                        rutasComunidad = r.valor
                        _estado.update { it.copy(cargando = false, refrescando = false, rutas = rutasCombinadas(), error = null) }
                        cargarPerfiles()
                    }
                    is Resultado.Error -> _estado.update { it.copy(cargando = false, refrescando = false, error = r.mensaje) }
                }
                nombrarPuntos()
            }
        }
    }

    private var rutasComunidad: List<pe.cooruta.app.data.repositorio.MiRuta> = emptyList()
    private var rutasGrupos: List<pe.cooruta.app.data.repositorio.MiRuta> = emptyList()
    private var bloqueados: Set<String> = emptySet()
    private fun rutasCombinadas() = (rutasComunidad + rutasGrupos).distinctBy { it.id }
        .filter { it.conductorId !in bloqueados }.sortedBy { it.salida }

    /** Tramos de las rutas privadas de los conductores cuyos grupos integro y rutas compartidas con mis grupos. */
    fun cargarGrupos() {
        viewModelScope.launch {
            val g = pe.cooruta.app.data.repositorio.GrupoRepositorio().recorridosDeMisGrupos()
            _estado.update { it.copy(grupo = g) }
        }
        viewModelScope.launch {
            bloqueados = pe.cooruta.app.data.repositorio.SeguridadRepositorio().idsBloqueados()
            rutasGrupos = repo.rutasDeMisGrupos()
            _estado.update { it.copy(rutas = rutasCombinadas()) }
        }
    }

    fun solicitar(c: Coincidencia, mensaje: String, alAvisar: (String) -> Unit) {
        if (_estado.value.enviando != null) return
        _estado.update { it.copy(enviando = c.ruta.id) }
        viewModelScope.launch {
            val n = _estado.value.nombres
            val r = repo.solicitarAsiento(
                c, n[clave(c.subida)] ?: "Punto de subida", _estado.value.nombreBajada(c) ?: "Punto de bajada", mensaje
            )
            _estado.update { it.copy(enviando = null) }
            alAvisar(if (r is Resultado.Error) r.mensaje else "Solicitud enviada a ${c.ruta.nombre}.")
        }
    }

    /** Pide viajar habitualmente los [dias] (1–7) con la ruta de [c]: una sola solicitud para todas las semanas. */
    fun solicitarHabitual(c: Coincidencia, dias: Set<Int>, mensaje: String, alAvisar: (String) -> Unit) {
        if (_estado.value.enviando != null) return
        _estado.update { it.copy(enviando = "hab_${c.ruta.id}") }
        viewModelScope.launch {
            val n = _estado.value.nombres
            val r = pe.cooruta.app.data.repositorio.HabitualesRepositorio().solicitar(
                c, dias, n[clave(c.subida)] ?: "Punto de subida", _estado.value.nombreBajada(c) ?: "Punto de bajada", mensaje
            )
            _estado.update { it.copy(enviando = null) }
            alAvisar(when (r) {
                is Resultado.Error -> r.mensaje
                is Resultado.Exito -> "Solicitud enviada a ${c.ruta.nombre}" +
                    (r.valor.takeIf { it.isNotBlank() }?.let { " · te recogería hacia las $it" } ?: "") + "."
            })
        }
    }

    fun cancelarSolicitud(rutaId: String, alAvisar: (String) -> Unit) {
        if (_estado.value.enviando != null) return
        _estado.update { it.copy(enviando = rutaId) }
        viewModelScope.launch {
            val id = _estado.value.idsSolicitud[rutaId]
            val r = if (id == null) Resultado.Error("No encontramos tu solicitud.") else repo.cancelarSolicitud(id)
            _estado.update { it.copy(enviando = null) }
            alAvisar(if (r is Resultado.Error) r.mensaje else "Cancelaste tu solicitud.")
        }
    }

    fun actualizarFavoritos(ids: Set<String>) = _estado.update { it.copy(favoritos = ids) }

    fun filtrarFavoritos(solo: Boolean) {
        _estado.update { it.copy(soloFavoritos = solo) }
        nombrarPuntos()
    }

    fun filtrarEmpresa(solo: Boolean) {
        _estado.update { it.copy(soloEmpresa = solo) }
        nombrarPuntos()
    }
    fun actualizarEmpresa(e: pe.cooruta.app.data.repositorio.EstadoEmpresa) {
        _estado.update { it.copy(companerosEmpresa =
            if (e.disponible && e.uid == it.miUid) e.companeros else emptySet()) }
        nombrarPuntos()
    }

    private val sesionBusqueda = java.util.UUID.randomUUID().toString()
    private var busquedaLugar: Job? = null

    /** Escribe y, tras una pausa, busca el lugar como en el mapa (Mapbox). */
    fun escribir(texto: String) {
        _estado.update { it.copy(texto = texto, lugarBuscado = null, trayectoRapido = null, buscandoLugar = texto.trim().length >= 2,
            sugerencias = if (texto.isBlank()) emptyList() else it.sugerencias) }
        busquedaLugar?.cancel()
        if (texto.trim().length < 2) return
        busquedaLugar = viewModelScope.launch {
            kotlinx.coroutines.delay(350)
            val lista = (lugares.sugerir(texto.trim(), sesionBusqueda, null) as? Resultado.Exito)?.valor.orEmpty()
            _estado.update { if (it.texto != texto) it else it.copy(sugerencias = lista, buscandoLugar = false) }
        }
    }

    /** Elige un lugar de la lista: se muestran las rutas que pasan cerca. */
    fun elegirSugerencia(s: pe.cooruta.app.data.Sugerencia, origen: Punto?) {
        busquedaLugar?.cancel()
        _estado.update { it.copy(texto = s.nombre, sugerencias = emptyList(), buscandoLugar = true, trayectoRapido = null) }
        busquedaLugar = viewModelScope.launch {
            val lugar = (lugares.detalle(s, sesionBusqueda) as? Resultado.Exito)?.valor
            if (lugar != null) usarLugar(lugar, origen) else _estado.update { it.copy(buscandoLugar = false) }
        }
    }

    /** Elige un destino reciente (no hace falta volver a buscarlo). */
    fun elegirReciente(l: pe.cooruta.app.data.Lugar, origen: Punto?) {
        busquedaLugar?.cancel()
        _estado.update { it.copy(texto = l.nombre, sugerencias = emptyList(), buscandoLugar = true, trayectoRapido = null) }
        busquedaLugar = viewModelScope.launch { usarLugar(l, origen) }
    }

    /** Nueva posición del pasajero: si se alejó >300 m del origen usado, recalcula la ruta al destino. */
    fun actualizarOrigen(p: Punto) {
        val e = _estado.value
        val lugar = e.lugarBuscado ?: return
        val previo = e.trayectoRapido?.origenPunto ?: return
        if (e.buscandoLugar || pe.cooruta.app.data.metros(previo, p) < 300) return
        busquedaLugar?.cancel()
        busquedaLugar = viewModelScope.launch { usarLugar(lugar, p) }
    }

    private suspend fun usarLugar(lugar: pe.cooruta.app.data.Lugar, origen: Punto?) {
        run {
            runCatching { lugares.guardarReciente(lugar) }
            // Con mi ubicación: calcula la ruta hasta el destino y busca conductores que coincidan.
            val rapido = if (lugar != null && origen != null) {
                val destino = Punto(lugar.latitud, lugar.longitud)
                val tr = (lugares.trayecto(origen, destino) as? Resultado.Exito)?.valor
                MiRuta(
                    id = "rapido", origenPunto = origen, destinoPunto = destino,
                    recorrido = tr?.conExtremos() ?: listOf(origen, destino),
                    distanciaMetros = tr?.metros ?: pe.cooruta.app.data.metros(origen, destino),
                    origen = "Tu ubicación", destino = lugar.nombre,
                    dias = setOf(java.time.LocalDate.now().dayOfWeek), salida = java.time.LocalTime.now(),
                    duracionSegundos = tr?.segundos?.toLong(), asientos = 0, asientosLibres = 0,
                    pasajeCentimos = null, activa = true
                )
            } else null
            _estado.update { it.copy(lugarBuscado = lugar, trayectoRapido = rapido, buscandoLugar = false) }
            nombrarPuntos()
        }
    }

    /** Desde Mis viajes: «Usar esta ruta hoy». */
    fun usarHoy(id: String) {
        // Usar un trayecto guardado: se descarta la búsqueda por ubicación actual, si no la tapaba.
        busquedaLugar?.cancel()
        _estado.update { it.copy(trayectoId = id, soloHoy = true, texto = "", lugarBuscado = null, trayectoRapido = null,
            sugerencias = emptyList(), buscandoLugar = false) }
        nombrarPuntos()
        // Avisa a los conductores que hoy hace este trayecto.
        viewModelScope.launch {
            val t = _estado.value.misTrayectos.firstOrNull { it.id == id }
                ?: (repo.misBusquedas() as? Resultado.Exito)?.valor?.firstOrNull { it.id == id }
                ?: return@launch
            val r = repo.avisarViajeHoy(t)
            _estado.update { it.copy(avisadoHoy = r is Resultado.Exito) }
        }
    }

    fun quitarHoy() {
        _estado.update { it.copy(soloHoy = false, avisadoHoy = false) }
        viewModelScope.launch { repo.quitarViajeHoy() }
    }

    fun elegirTrayecto(id: String) {
        busquedaLugar?.cancel()
        _estado.update { it.copy(trayectoId = id, texto = "", lugarBuscado = null, trayectoRapido = null,
            sugerencias = emptyList(), buscandoLugar = false) }
        nombrarPuntos()
    }

    /** Busca el nombre de la calle donde sube y baja (solo los que faltan). */
    private fun nombrarPuntos() {
        val e = _estado.value
        val faltan = e.coincidencias.flatMap { listOf(it.subida, it.bajada) }
            .filter { clave(it) !in e.nombres }.distinctBy { clave(it) }.take(20)
        if (faltan.isEmpty()) return
        viewModelScope.launch {
            for (p in faltan) {
                val nombre = lugares.direccionDe(p.latitud, p.longitud)?.nombre ?: continue
                _estado.update { it.copy(nombres = it.nombres + (clave(p) to nombre)) }
            }
        }
    }

    /** Pide una sola vez el perfil público de cada conductor nuevo. */
    private fun cargarPerfiles() {
        val e = _estado.value
        val faltan = (e.rutas.filter { !it.prueba }.map { it.conductorId } + listOfNotNull(e.miUid)).toSet() - e.perfiles.keys
        if (faltan.isEmpty()) return
        viewModelScope.launch {
            val nuevos = perfiles.perfiles(faltan)
            _estado.update { it.copy(perfiles = it.perfiles + nuevos) }
        }
    }

    companion object {
        val Fabrica: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY]!!
                BuscarViewModel(
                    RutasRepositorio(),
                    LugaresRepositorio(
                        recientesDao = CooRutaBaseDatos.obtener(app).lugaresRecientes()
                    ),
                    // IA pausada por ahora (la API de Gemini aún no responde en el proyecto). Para activarla: !BuildConfig.EMULADOR
                    ExplicacionesRepositorio(habilitada = false)
                )
            }
        }
    }
}
