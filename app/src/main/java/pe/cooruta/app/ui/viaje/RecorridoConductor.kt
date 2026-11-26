package pe.cooruta.app.ui.viaje

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import pe.cooruta.app.data.ParadaRecorrido
import pe.cooruta.app.data.PlanificadorRecorrido
import pe.cooruta.app.data.Punto
import pe.cooruta.app.data.Resultado
import pe.cooruta.app.data.RutaCalculada
import kotlin.coroutines.cancellation.CancellationException

/** Lo que define el recorrido: origen (ubicación GPS reciente), recogidas ordenadas y destino privado. */
data class EntradaRecorrido(val origen: Punto?, val recogidas: List<ParadaRecorrido>, val destino: Punto?)

data class EstadoRecorrido(
    /** Línea real de Directions; vacía mientras no hay una respuesta válida (nunca líneas rectas). */
    val linea: List<Punto> = emptyList(),
    /** Distancia/tiempo totales y hasta la próxima parada, estimados al calcular ([calculadoMs]). */
    val ruta: RutaCalculada? = null,
    val calculadoMs: Long? = null,
    /** Recogidas pendientes en orden; la primera es la próxima. */
    val recogidas: List<ParadaRecorrido> = emptyList(),
    /** Recogidas que no entraron por el límite de coordenadas de la API. */
    val omitidas: Int = 0,
    val cargando: Boolean = false,
    val error: String? = null,
    /** Aún no hay muestra GPS reciente para usar como origen. */
    val esperandoUbicacion: Boolean = false
) {
    val proxima: ParadaRecorrido? get() = recogidas.firstOrNull()
}

/**
 * Solicitudes de recorrido del conductor (sin dibujar nada):
 * - solo se consulta Directions cuando cambian las recogidas pendientes o el destino, no por cada
 *   muestra GPS ni recomposición;
 * - una solicitud nueva cancela la anterior y las respuestas viejas se descartan;
 * - ante un error se conserva el resto del mapa y se puede reintentar.
 */
class RecorridoConductor(
    private val scope: CoroutineScope,
    private val reloj: () -> Long = System::currentTimeMillis,
    private val calcular: suspend (List<Punto>) -> Resultado<RutaCalculada>
) {
    private val _estado = MutableStateFlow(EstadoRecorrido())
    val estado: StateFlow<EstadoRecorrido> = _estado.asStateFlow()

    private var clave: Pair<List<String>, Punto?>? = null
    private var ultimaEntrada: EntradaRecorrido? = null
    private var trabajo: Job? = null
    private var generacion = 0L

    fun actualizar(e: EntradaRecorrido) {
        ultimaEntrada = e
        val nueva = e.recogidas.map { it.reservaId } to e.destino
        if (nueva == clave) return // mismas paradas: una muestra GPS nueva no recalcula
        val origen = e.origen
        if (origen == null) {
            // Se muestran las recogidas; la línea espera a tener una ubicación reciente.
            _estado.value = EstadoRecorrido(recogidas = e.recogidas, esperandoUbicacion = true)
            return
        }
        clave = nueva
        generacion++
        val gen = generacion
        trabajo?.cancel()
        val plan = PlanificadorRecorrido.planear(origen, e.recogidas, e.destino)
        if (plan == null) { _estado.value = EstadoRecorrido(); return }
        _estado.value = EstadoRecorrido(recogidas = plan.incluidas + plan.omitidas, omitidas = plan.omitidas.size, cargando = true)
        trabajo = scope.launch {
            val r = try {
                calcular(plan.coordenadas)
            } catch (c: CancellationException) {
                throw c
            } catch (x: Exception) {
                Resultado.Error("No se pudo calcular el recorrido.")
            }
            if (gen != generacion) return@launch // respuesta de una solicitud anterior
            _estado.update {
                when (r) {
                    is Resultado.Exito -> it.copy(linea = r.valor.linea, ruta = r.valor, calculadoMs = reloj(), cargando = false, error = null)
                    is Resultado.Error -> it.copy(cargando = false, error = r.mensaje)
                }
            }
        }
    }

    /** Vuelve a pedir el mismo recorrido (con la ubicación más reciente conocida). */
    fun reintentar(origenActual: Punto?) {
        val e = ultimaEntrada ?: return
        clave = null
        actualizar(e.copy(origen = origenActual ?: e.origen))
    }

    fun detener() {
        generacion++
        trabajo?.cancel()
        clave = null
        ultimaEntrada = null
        _estado.value = EstadoRecorrido()
    }
}
