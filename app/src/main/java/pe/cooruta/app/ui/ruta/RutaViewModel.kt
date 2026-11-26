package pe.cooruta.app.ui.ruta

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import pe.cooruta.app.R
import pe.cooruta.app.data.Lugar
import pe.cooruta.app.data.Punto
import pe.cooruta.app.data.Resultado
import pe.cooruta.app.data.Sugerencia
import pe.cooruta.app.data.Trayecto
import pe.cooruta.app.data.local.CooRutaBaseDatos
import pe.cooruta.app.data.repositorio.LugaresRepositorio
import java.util.UUID

/** Centro de Arequipa, mientras no sepamos dónde está la persona. */
internal val AREQUIPA = Lugar("Arequipa", "Perú", -16.4090, -71.5375)

/** Milisegundos de espera antes de consultar a Mapbox, para no pedir por cada letra. */
private const val ESPERA_BUSQUEDA = 350L

/** Milisegundos de espera antes de pedir el recorrido. Más que la búsqueda:
 * mientras se arrastra el mapa no tiene sentido recalcular la ruta. */
private const val ESPERA_TRAYECTO = 700L

/** Desde cuántas letras vale la pena buscar. */
internal const val LETRAS_MINIMAS = 3

/** Cuál de los dos campos está abierto en la búsqueda. */
enum class CampoRuta { ORIGEN, DESTINO }

/** Lo que muestra el panel de búsqueda. */
data class EstadoBusqueda(
    val texto: String = "",
    val resultados: List<Sugerencia> = emptyList(),
    val buscando: Boolean = false,
    /** Pidiendo las coordenadas del lugar elegido. */
    val resolviendo: Boolean = false,
    val error: String? = null
)

/** Todo lo que pinta la pantalla de ruta. */
data class EstadoRuta(
    /** null significa "mi ubicación actual", que es el caso normal. */
    val origen: Lugar? = null,
    val destino: Lugar? = null,
    /**
     * Mientras se elige, el pin va clavado al centro y el mapa se mueve por
     * debajo. Al confirmar, el destino queda fijo: centrar en tu ubicación ya
     * no lo cambia y la cámara puede encuadrar el recorrido entero.
     */
    val eligiendo: Boolean = true,
    val satelital: Boolean = false,
    /** Dónde está la persona. Nos lo cuenta el punto azul del propio mapa. */
    val miUbicacion: Punto? = null,
    /**
     * Mi ubicación con nombre de calle ("Calle Mercaderes 120"). Es lo que se
     * guarda como origen cuando la ruta sale de donde estoy, para que la otra
     * persona sepa dónde es. Null mientras no se sepa.
     */
    val miUbicacionConNombre: Lugar? = null,
    val trayecto: Trayecto? = null,
    /** Caminos posibles entre origen y destino; [trayecto] es el elegido. */
    val alternativas: List<Trayecto> = emptyList(),
    val calculandoTrayecto: Boolean = false,
    /** Por qué no hay recorrido (sin red, sin camino...). Null si todo va bien. */
    val avisoTrayecto: String? = null,
    /** Los últimos lugares elegidos, guardados en el teléfono con Room. */
    val recientes: List<Lugar> = emptyList(),
    val campoAbierto: CampoRuta? = null,
    val busqueda: EstadoBusqueda = EstadoBusqueda()
) {
    /** De dónde sale el recorrido: el origen elegido o, si no hay, mi ubicación. */
    val puntoOrigen: Punto? get() = origen?.let { Punto(it.latitud, it.longitud) } ?: miUbicacion
    val puntoDestino: Punto? get() = destino?.let { Punto(it.latitud, it.longitud) }
}

/** Órdenes de una sola vez para la cámara del mapa. */
sealed interface MovimientoCamara {
    /** Dejar los dos extremos a la vista. */
    data class Encuadrar(val a: Punto, val b: Punto) : MovimientoCamara
    data class Centrar(val punto: Punto, val zoom: Double) : MovimientoCamara
}

/**
 * Paso 1 del asistente: de dónde a dónde va la ruta.
 *
 * Aquí está toda la lógica que antes vivía dentro de RutaScreen: buscar
 * direcciones, saber qué hay bajo el pin, calcular el recorrido y decidir a
 * dónde mira la cámara. La pantalla solo pinta [estado] y avisa de los toques.
 */
class RutaViewModel(private val lugares: LugaresRepositorio) : ViewModel() {

    private val _estado = MutableStateFlow(EstadoRuta())
    val estado: StateFlow<EstadoRuta> = _estado.asStateFlow()

    // La cámara es del mapa, que vive en la pantalla. El ViewModel no la toca:
    // le manda órdenes por este canal.
    private val _camara = Channel<MovimientoCamara>(Channel.BUFFERED)
    val camara: Flow<MovimientoCamara> = _camara.receiveAsFlow()

    // Cada trabajo pendiente se cancela cuando llega uno más nuevo: así solo
    // consultamos a Mapbox cuando la persona deja de mover el mapa o de teclear.
    private var trabajoCentro: Job? = null
    private var trabajoTrayecto: Job? = null
    private var trabajoBusqueda: Job? = null
    private var trabajoMiDireccion: Job? = null

    // Mapbox cobra la búsqueda por sesión, no por tecla: todas las consultas
    // de una misma búsqueda comparten este identificador.
    private var sesionBusqueda = UUID.randomUUID().toString()

    // Mi ubicación redondeada a unos 50 m: sin esto, cada temblor del GPS
    // pediría un recorrido nuevo.
    private var ubicacionRedondeada: Punto? = null

    init {
        // Room avisa cada vez que cambia la tabla: la lista se mantiene sola.
        viewModelScope.launch {
            lugares.recientes.collect { recientes ->
                _estado.update { it.copy(recientes = recientes) }
            }
        }
    }

    // ------------------------------------------------------------ mapa

    /** El centro del mapa se quedó quieto aquí. Si se está eligiendo, eso es el destino. */
    fun alMoverMapa(latitud: Double, longitud: Double) {
        if (!_estado.value.eligiendo) return
        trabajoCentro?.cancel()
        trabajoCentro = viewModelScope.launch {
            delay(ESPERA_BUSQUEDA)
            val lugar = lugares.direccionDe(latitud, longitud)
                ?: Lugar("Punto en el mapa", coordenadas(latitud, longitud), latitud, longitud)
            if (_estado.value.eligiendo) {
                _estado.update { it.copy(destino = lugar) }
                recalcularTrayecto()
            }
        }
    }

    fun alSaberUbicacion(punto: Punto) {
        _estado.update { it.copy(miUbicacion = punto) }
        val redondeada = Punto(redondear(punto.latitud), redondear(punto.longitud))
        if (redondeada != ubicacionRedondeada) {
            ubicacionRedondeada = redondeada
            // Solo importa si el recorrido sale de donde estoy.
            if (_estado.value.origen == null) recalcularTrayecto()
            buscarMiDireccion(punto)
        }
    }

    /**
     * Pone nombre a mi ubicación (geocodificación inversa). Solo se pide cuando
     * me muevo unos 50 m, no por cada temblor del GPS. Si falla, el origen se
     * guarda igual con sus coordenadas.
     */
    private fun buscarMiDireccion(punto: Punto) {
        trabajoMiDireccion?.cancel()
        trabajoMiDireccion = viewModelScope.launch {
            delay(ESPERA_TRAYECTO)
            val lugar = lugares.direccionDe(punto.latitud, punto.longitud) ?: return@launch
            _estado.update { it.copy(miUbicacionConNombre = lugar) }
        }
    }

    /** Clava el destino en el mapa y encuadra el recorrido completo. */
    fun fijarDestino(lugar: Lugar? = null) {
        trabajoCentro?.cancel()
        _estado.update { it.copy(destino = lugar ?: it.destino, eligiendo = false) }
        val e = _estado.value
        val a = e.puntoOrigen
        val b = e.puntoDestino
        if (a != null && b != null) _camara.trySend(MovimientoCamara.Encuadrar(a, b))
        if (lugar != null) recalcularTrayecto()
    }

    private var clavePrecargada: String? = null

    /** Editar: pone el origen y destino guardados (una sola vez por edición) y recalcula los caminos. */
    fun precargar(clave: String, origen: Lugar?, destino: Lugar) {
        if (clavePrecargada == clave) return
        clavePrecargada = clave
        _estado.update { it.copy(origen = origen) }
        fijarDestino(destino)
    }

    /** "Mover el pin": el destino vuelve a seguir al centro del mapa. */
    fun soltarPin() = _estado.update { it.copy(eligiendo = true) }

    fun alternarSatelite() = _estado.update { it.copy(satelital = !it.satelital) }

    fun centrarEnMiUbicacion() {
        val yo = _estado.value.miUbicacion ?: return
        _camara.trySend(MovimientoCamara.Centrar(yo, 16.0))
    }

    fun intercambiar() {
        val e = _estado.value
        val anterior = e.origen
        _estado.update { it.copy(origen = e.destino) }
        if (anterior == null) {
            // El origen era "mi ubicación": al invertirlos no queda destino,
            // así que se vuelve a elegir.
            _estado.update { it.copy(destino = null, eligiendo = true) }
            recalcularTrayecto()
        } else {
            fijarDestino(anterior)
        }
    }

    // ------------------------------------------------------------ búsqueda

    fun abrirBusqueda(campo: CampoRuta) {
        sesionBusqueda = UUID.randomUUID().toString()
        _estado.update { it.copy(campoAbierto = campo, busqueda = EstadoBusqueda()) }
    }

    fun cerrarBusqueda() {
        trabajoBusqueda?.cancel()
        _estado.update { it.copy(campoAbierto = null, busqueda = EstadoBusqueda()) }
    }

    fun escribirBusqueda(texto: String) {
        trabajoBusqueda?.cancel()
        val consulta = texto.trim()

        if (consulta.length < LETRAS_MINIMAS) {
            _estado.update {
                it.copy(busqueda = EstadoBusqueda(texto = texto))
            }
            return
        }

        _estado.update { it.copy(busqueda = it.busqueda.copy(texto = texto, buscando = true, error = null)) }
        trabajoBusqueda = viewModelScope.launch {
            delay(ESPERA_BUSQUEDA)
            val e = _estado.value
            val r = lugares.sugerir(consulta, sesionBusqueda, e.destino ?: e.origen ?: AREQUIPA)
            _estado.update {
                it.copy(
                    busqueda = when (r) {
                        is Resultado.Exito -> it.busqueda.copy(resultados = r.valor, buscando = false, error = null)
                        is Resultado.Error -> it.busqueda.copy(resultados = emptyList(), buscando = false, error = r.mensaje)
                    }
                )
            }
        }
    }

    fun elegirSugerencia(sugerencia: Sugerencia) {
        val campo = _estado.value.campoAbierto ?: return
        viewModelScope.launch {
            // La sugerencia no trae coordenadas: hay que pedirlas antes de
            // poder mover el mapa.
            _estado.update { it.copy(busqueda = it.busqueda.copy(resolviendo = true, error = null)) }
            when (val r = lugares.detalle(sugerencia, sesionBusqueda)) {
                is Resultado.Error -> _estado.update {
                    it.copy(busqueda = it.busqueda.copy(resolviendo = false, error = r.mensaje))
                }
                is Resultado.Exito -> usarLugar(campo, r.valor)
            }
        }
    }

    /** Un lugar de la lista de recientes: ya trae coordenadas, no hace falta red. */
    /** Vuelve a salir desde «Mi ubicación actual» (origen = null). */
    fun usarMiUbicacion() {
        cerrarBusqueda()
        _estado.update { it.copy(origen = null) }
        recalcularTrayecto()
    }

    fun elegirReciente(lugar: Lugar) {
        val campo = _estado.value.campoAbierto ?: return
        viewModelScope.launch { usarLugar(campo, lugar) }
    }

    /** Pone el lugar en el campo que estaba abierto y lo guarda como reciente. */
    private suspend fun usarLugar(campo: CampoRuta, lugar: Lugar) {
        cerrarBusqueda()
        if (campo == CampoRuta.ORIGEN) {
            _estado.update { it.copy(origen = lugar) }
            recalcularTrayecto()
        } else {
            fijarDestino(lugar)
        }
        lugares.guardarReciente(lugar)
    }

    // ------------------------------------------------------------ recorrido

    /** Pide el trazo por calles entre origen y destino, con una pequeña espera. */
    private fun recalcularTrayecto() {
        trabajoTrayecto?.cancel()
        val desde = _estado.value.puntoOrigen
        val hasta = _estado.value.puntoDestino

        if (desde == null || hasta == null) {
            _estado.update { it.copy(trayecto = null, alternativas = emptyList(), calculandoTrayecto = false, avisoTrayecto = null) }
            return
        }

        trabajoTrayecto = viewModelScope.launch {
            _estado.update { it.copy(calculandoTrayecto = true, avisoTrayecto = null) }
            delay(ESPERA_TRAYECTO)
            when (val r = lugares.alternativas(desde, hasta)) {
                is Resultado.Exito -> _estado.update {
                    it.copy(trayecto = r.valor.first(), alternativas = r.valor, calculandoTrayecto = false, avisoTrayecto = null)
                }
                is Resultado.Error -> _estado.update {
                    it.copy(trayecto = null, alternativas = emptyList(), calculandoTrayecto = false, avisoTrayecto = r.mensaje)
                }
            }
        }
    }

    /** El conductor elige otro de los caminos posibles. */
    fun elegirAlternativa(t: Trayecto) {
        if (t in _estado.value.alternativas) _estado.update { it.copy(trayecto = t) }
    }

    companion object {
        /**
         * Arma el repositorio con sus tres piezas: el cliente Retrofit, el
         * token (de los recursos, que lo sacan de local.properties) y la tabla
         * de Room.
         */
        val Fabrica: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY]!!
                RutaViewModel(
                    LugaresRepositorio(
                        recientesDao = CooRutaBaseDatos.obtener(app).lugaresRecientes()
                    )
                )
            }
        }
    }
}

/** Recorta una coordenada a unos 50 metros de resolución. */
private fun redondear(grados: Double): Double = Math.round(grados * 2000.0) / 2000.0

/** Coordenadas legibles, para cuando no hay nombre que mostrar. */
internal fun coordenadas(latitud: Double, longitud: Double): String =
    "%.5f, %.5f".format(latitud, longitud)
