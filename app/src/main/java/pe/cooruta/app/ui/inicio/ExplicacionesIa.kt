package pe.cooruta.app.ui.inicio

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import pe.cooruta.app.data.Resultado
import pe.cooruta.app.data.repositorio.ResumenOpcion
import kotlin.coroutines.cancellation.CancellationException

/** Cuántas de las primeras coincidencias se explican. */
const val OPCIONES_IA = 3
private const val ESPERA_IA_MS = 800L

sealed interface EstadoExplicaciones {
    data object Inactivo : EstadoExplicaciones
    data object Cargando : EstadoExplicaciones
    /** Una frase por opción, en el orden de las coincidencias. */
    data class Listas(val textos: List<String>) : EstadoExplicaciones
    data class Error(val mensaje: String) : EstadoExplicaciones
}

/**
 * Coordina las explicaciones de la IA para el ViewModel:
 * - solo llama cuando cambian las opciones (no por recomposición ni por datos iguales);
 * - espera [esperaMs] a que la búsqueda se estabilice (p. ej. mientras se escribe);
 * - cancela la solicitud anterior y descarta respuestas de búsquedas anteriores.
 */
@OptIn(FlowPreview::class)
class CoordinadorExplicaciones(
    scope: CoroutineScope,
    private val explicar: suspend (List<ResumenOpcion>) -> Resultado<List<String>>,
    esperaMs: Long = ESPERA_IA_MS
) {
    private data class Pedido(val opciones: List<ResumenOpcion>, val intento: Int)

    private val pedido = MutableStateFlow<Pedido?>(null)
    private val _estado = MutableStateFlow<EstadoExplicaciones>(EstadoExplicaciones.Inactivo)
    val estado: StateFlow<EstadoExplicaciones> = _estado.asStateFlow()

    init {
        scope.launch {
            pedido.debounce(esperaMs).collectLatest { p ->
                if (p == null) return@collectLatest
                val r = try {
                    explicar(p.opciones)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Resultado.Error("No se pudo obtener la explicación de la IA.")
                }
                if (pedido.value != p) return@collectLatest // respuesta de una búsqueda anterior
                _estado.value = when (r) {
                    is Resultado.Exito -> EstadoExplicaciones.Listas(r.valor)
                    is Resultado.Error -> EstadoExplicaciones.Error(r.mensaje)
                }
            }
        }
    }

    fun actualizar(opciones: List<ResumenOpcion>) {
        if (opciones == pedido.value?.opciones) return
        if (opciones.isEmpty()) {
            pedido.value = null
            _estado.value = EstadoExplicaciones.Inactivo
            return
        }
        pedido.value = Pedido(opciones, intento = 0)
        _estado.value = EstadoExplicaciones.Cargando
    }

    /** Vuelve a pedir las mismas opciones tras un error. */
    fun reintentar() {
        val p = pedido.value ?: return
        if (_estado.value !is EstadoExplicaciones.Error) return
        pedido.value = p.copy(intento = p.intento + 1)
        _estado.value = EstadoExplicaciones.Cargando
    }
}
