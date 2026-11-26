package pe.cooruta.app.ui.viaje

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import pe.cooruta.app.data.Muestra
import pe.cooruta.app.data.PoliticaUbicacion
import pe.cooruta.app.data.Punto
import pe.cooruta.app.data.Resultado

data class EstadoSeguimiento(
    val viajeId: String? = null,
    val activo: Boolean = false,
    /** Hora (dispositivo) del último envío confirmado. */
    val ultimoEnvioMs: Long? = null,
    /** Hora GPS de la última muestra recibida; sirve para mostrar «esperando GPS». */
    val ultimaMuestraMs: Long? = null,
    val error: String? = null
)

/**
 * Publicación de la ubicación del conductor, con dueño y duración definidos ([scope]):
 * - solo se envían muestras GPS nuevas y recientes; nunca se reenvía una muestra ya enviada o
 *   vieja como si fuera actual (la hora de la muestra viaja aparte de la del servidor);
 * - un conductor detenido sigue enviando sus muestras nuevas cada [PoliticaUbicacion.LATIDO_MS];
 * - cada activación, pausa o detención abre una nueva «generación»: una publicación anterior que
 *   responda tarde no reactiva el seguimiento ni cambia el estado de otra sesión;
 * - publicar y pausar se escriben en orden ([escrituras]).
 */
class SeguimientoConductor(
    private val scope: CoroutineScope,
    private val publicar: suspend (String, Muestra) -> Resultado<Unit>,
    private val pausarRemoto: suspend (String) -> Resultado<Unit>,
    private val reloj: () -> Long = System::currentTimeMillis
) {
    private val _estado = MutableStateFlow(EstadoSeguimiento())
    val estado: StateFlow<EstadoSeguimiento> = _estado.asStateFlow()

    private val escrituras = Mutex()
    private var generacion = 0L
    private var enviando = false
    private var ultimoPunto: Punto? = null
    private var ultimoEnvio: Long? = null
    /** Hora de la última muestra vista: cada muestra se considera una sola vez (nunca se reenvía). */
    private var ultimaVista: Long? = null
    /** Se emitió al menos un envío en este viaje (aunque no esté confirmado): hay algo que pausar. */
    private var hayUbicacion = false

    private fun olvidarMuestras() {
        ultimoPunto = null; ultimoEnvio = null; ultimaVista = null; hayUbicacion = false
    }

    fun activar(viajeId: String) {
        val e = _estado.value
        if (e.activo && e.viajeId == viajeId) return
        if (e.viajeId != viajeId) olvidarMuestras()
        generacion++
        enviando = false
        _estado.value = if (e.viajeId == viajeId) e.copy(activo = true) else EstadoSeguimiento(viajeId, activo = true)
    }

    /**
     * Deja de enviar. Con [escribirPausa] marca la ubicación como pausada; si no hay red para
     * confirmarlo se avisa, y el pasajero la verá como desactualizada (no se promete la pausa).
     */
    fun pausar(escribirPausa: Boolean = true) {
        val e = _estado.value
        generacion++
        enviando = false
        if (!e.activo) return
        _estado.update { it.copy(activo = false) }
        val id = e.viajeId ?: return
        if (!escribirPausa || !hayUbicacion) return
        val gen = generacion
        scope.launch {
            val r = escrituras.withLock { pausarRemoto(id) }
            if (gen == generacion && r is Resultado.Error) _estado.update {
                it.copy(error = "No se pudo confirmar la pausa: los pasajeros verán tu última ubicación como desactualizada.")
            }
        }
    }

    /** Cerrar el viaje, finalizarlo o cambiar de cuenta: se olvida todo lo de esta sesión. */
    fun detener(escribirPausa: Boolean) {
        pausar(escribirPausa)
        generacion++
        olvidarMuestras()
        _estado.value = EstadoSeguimiento()
    }

    /**
     * Al destruirse el dueño (sin corrutinas disponibles): [encolar] deja la pausa en la cola de
     * escrituras de Firestore, que la envía cuando pueda. Sin confirmación: si no llega, la
     * ubicación se verá desactualizada.
     */
    fun cerrarAlDestruir(encolar: (String) -> Unit) {
        val id = _estado.value.viajeId
        if (id != null && _estado.value.activo && hayUbicacion) encolar(id)
        detener(escribirPausa = false)
    }

    /** Muestra real del GPS (solo llega con la pantalla del viaje activa). */
    fun alRecibirMuestra(m: Muestra) {
        val e = _estado.value
        val id = e.viajeId ?: return
        if (!e.activo) return
        val ahora = reloj()
        if (ahora - m.tomadaMs > PoliticaUbicacion.MUESTRA_MAX_MS) return // vieja: no es la ubicación actual
        if (ultimaVista?.let { m.tomadaMs <= it } == true) return       // ya vista
        ultimaVista = m.tomadaMs
        _estado.update { it.copy(ultimaMuestraMs = m.tomadaMs) }
        if (enviando) return
        if (!PoliticaUbicacion.debePublicar(ultimoPunto, ultimoEnvio, m.punto, ahora)) return
        val gen = generacion
        enviando = true
        scope.launch {
            val r = escrituras.withLock {
                if (gen != generacion) null // se pausó mientras esperaba turno: no se envía
                else { hayUbicacion = true; publicar(id, m) }
            }
            if (gen != generacion) return@launch // respuesta de una sesión anterior: se descarta
            enviando = false
            when (r) {
                is Resultado.Exito -> {
                    ultimoPunto = m.punto; ultimoEnvio = ahora
                    _estado.update { it.copy(ultimoEnvioMs = ahora, error = null) }
                }
                is Resultado.Error -> _estado.update { it.copy(error = r.mensaje) }
                null -> Unit
            }
        }
    }
}
