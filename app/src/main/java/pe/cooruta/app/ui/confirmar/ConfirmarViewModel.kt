package pe.cooruta.app.ui.confirmar

import kotlinx.coroutines.flow.firstOrNull
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
import pe.cooruta.app.data.BorradorRuta
import pe.cooruta.app.data.EleccionTramo
import pe.cooruta.app.data.Punto
import pe.cooruta.app.data.ReglasTramo
import pe.cooruta.app.data.TipoTramo
import pe.cooruta.app.data.TramoPublicado
import pe.cooruta.app.data.TramoPublico
import pe.cooruta.app.data.repositorio.recorridoPublico
import pe.cooruta.app.data.Resultado
import pe.cooruta.app.data.repositorio.RutasRepositorio

data class EstadoConfirmar(
    val publicando: Boolean = false,
    /** Id del documento ya guardado en Firestore. Si no es null, se muestra el éxito. */
    val publicadaComo: String? = null,
    val error: String? = null,
    /** Nombre opcional que el usuario le pone a su ruta. */
    val alias: String = "",
    // ---- Solo conductor: tramo que verán en Buscar los usuarios con sesión.
    /** null hasta calcularlo (y siempre null para pasajeros). */
    val tramoUtilizable: Boolean? = null,
    val modo: ModoTramo = ModoTramo.AUTOMATICO,
    /** Recorrido completo: solo para la vista previa en este dispositivo, nunca se publica entero. */
    val recorridoCompleto: List<Punto> = emptyList(),
    /** Lo que se publicaría como tramo visible. */
    val tramoVisible: List<Punto> = emptyList(),
    val marcas: List<Punto> = emptyList(),
    val errorTramo: String? = null,
    /** ¿Hay algún tramo lo bastante lejos de los extremos para elegirlo? */
    val hayEspacio: Boolean = true,
    /** Confirmación explícita de la vista previa (o de guardar sin aparecer en Buscar). */
    val confirmado: Boolean = false,
    // ---- Solo conductor: quién puede ver la ruta.
    val soloGrupos: Boolean = false,
    val gruposElegidos: Set<String> = emptySet(),
    /** Mis grupos activos (donde puedo compartir la ruta); null mientras carga. */
    val misGrupos: List<pe.cooruta.app.data.repositorio.GrupoRepositorio.GrupoResumen>? = null
) {
    /** Qué falta para habilitar el botón principal, en palabras del usuario (null si nada). */
    val queFalta: String? get() = when {
        soloGrupos && gruposElegidos.isEmpty() -> "Elige al menos un grupo, o comparte la ruta con toda tu comunidad."
        tramoUtilizable == null -> null
        modo == ModoTramo.ELEGIR && tramoVisible.size < 2 ->
            "Marca en el mapa el inicio y el fin del tramo público, o elige «Privada»."
        else -> null
    }

    // Elegir Pública o Privada ya es la confirmación (sin casilla aparte).
    val listoParaPublicar: Boolean get() = (!soloGrupos || gruposElegidos.isNotEmpty()) && (tramoUtilizable == null || when (modo) {
        ModoTramo.AUTOMATICO -> tramoUtilizable == true && tramoVisible.size >= 2
        ModoTramo.ELEGIR -> tramoVisible.size >= 2
        ModoTramo.SIN_BUSCAR -> true
        ModoTramo.COMPLETO -> recorridoCompleto.size >= 2
    })
}

/** AUTOMATICO: el tramo recortado sirve. ELEGIR: el conductor marca inicio y fin. SIN_BUSCAR: no aparece. */
enum class ModoTramo { AUTOMATICO, ELEGIR, SIN_BUSCAR, COMPLETO }

/**
 * Paso 3 del asistente: revisar y publicar.
 *
 * Publicar es lo único que sale a la red. Mientras tanto la pantalla muestra
 * que está trabajando; si falla, dice por qué y deja reintentar sin duplicar.
 */
class ConfirmarViewModel(private val rutas: RutasRepositorio) : ViewModel() {

    private val _estado = MutableStateFlow(EstadoConfirmar())
    val estado: StateFlow<EstadoConfirmar> = _estado.asStateFlow()

    // Mismo id en todos los reintentos de una misma publicación: si un intento
    // llegó a Firestore aunque el teléfono no se enteró, no queda duplicado.
    private var idPendiente: String? = null

    private var edicion = false

    /** Editar una ruta existente: mismo id y su nombre actual. */
    fun prepararEdicion(id: String?, alias: String) {
        if (id == null) {
            // Ruta nueva después de una edición: no reutilizar el id editado.
            if (edicion) { idPendiente = null; edicion = false; _estado.update { it.copy(alias = "") } }
            return
        }
        edicion = true
        if (idPendiente != id) {
            idPendiente = id
            _estado.update { it.copy(alias = alias) }
            // Con quién estaba compartida.
            viewModelScope.launch {
                val g = rutas.gruposDeRuta(id)
                _estado.update { it.copy(soloGrupos = g.isNotEmpty(), gruposElegidos = g.toSet()) }
            }
        }
    }

    /** Conductor: mis grupos activos, para elegir con cuáles compartir la ruta. */
    fun cargarGrupos() {
        viewModelScope.launch {
            val g = pe.cooruta.app.data.repositorio.GrupoRepositorio().misGruposEnVivo().firstOrNull().orEmpty()
            _estado.update { it.copy(misGrupos = g.filter { it.activo }) }
        }
    }

    fun elegirSoloGrupos(solo: Boolean) = _estado.update { it.copy(soloGrupos = solo) }

    /** Marca o desmarca un grupo (máximo 10). */
    fun alternarGrupo(id: String) = _estado.update {
        val nuevos = if (id in it.gruposElegidos) it.gruposElegidos - id else if (it.gruposElegidos.size < 10) it.gruposElegidos + id else it.gruposElegidos
        it.copy(gruposElegidos = nuevos)
    }

    fun escribirAlias(texto: String) = _estado.update { it.copy(alias = texto.take(40)) }

    /** Umbrales iniciales del tramo elegido (configurables). */
    private val reglas = ReglasTramo()
    private var origen: Punto? = null
    private var destino: Punto? = null
    private var borradorPreparado: BorradorRuta? = null

    /**
     * Conductor: calcula qué quedaría visible con el recorte de siempre (1 km alrededor de cada
     * extremo, sin reducirlo). Si no queda un tramo utilizable, propone elegir uno o no aparecer.
     */
    fun prepararTramo(borrador: BorradorRuta, esConductor: Boolean) {
        // Se recalcula si el conductor volvió y cambió su ruta.
        if (!esConductor || (borrador == borradorPreparado && _estado.value.tramoUtilizable != null)) return
        borradorPreparado = borrador
        val completos = borrador.trayecto?.puntos.orEmpty()
        val o = borrador.origen?.let { Punto(it.latitud, it.longitud) } ?: borrador.trayecto?.desde
        val d = Punto(borrador.destino.latitud, borrador.destino.longitud)
        origen = o; destino = d
        val auto = if (o == null) emptyList() else recorridoPublico(completos, o, d)
        val util = TramoPublico.utilizable(auto)
        val espacio = o != null && TramoPublico.hayEspacio(completos, o, d, reglas)
        _estado.update { it.copy(
            tramoUtilizable = util,
            modo = if (TramoPublico.utilizable(completos)) ModoTramo.COMPLETO else ModoTramo.SIN_BUSCAR,
            recorridoCompleto = completos,
            tramoVisible = if (TramoPublico.utilizable(completos)) completos else emptyList(),
            hayEspacio = espacio, marcas = emptyList(), errorTramo = null, confirmado = true
        ) }
    }

    fun elegirModo(m: ModoTramo) {
        val e = _estado.value
        if (e.tramoUtilizable == null || (m == ModoTramo.ELEGIR && !e.hayEspacio) ||
            (m == ModoTramo.AUTOMATICO && e.tramoUtilizable != true) ||
            (m == ModoTramo.COMPLETO && !TramoPublico.utilizable(e.recorridoCompleto))) return
        val auto = when (m) {
            ModoTramo.AUTOMATICO -> origen?.let { o -> destino?.let { d -> recorridoPublico(e.recorridoCompleto, o, d) } }.orEmpty()
            ModoTramo.COMPLETO -> e.recorridoCompleto
            else -> emptyList()
        }
        _estado.update { it.copy(modo = m, marcas = emptyList(), tramoVisible = auto, errorTramo = null, confirmado = true) }
    }

    /** Toque en el mapa: primero el inicio, luego el fin; un tercer toque empieza de nuevo. */
    fun marcar(p: Punto) {
        val e = _estado.value
        val o = origen ?: return
        val d = destino ?: return
        if (e.modo != ModoTramo.ELEGIR) return
        val marcas = if (e.marcas.size >= 2) listOf(p) else e.marcas + p
        if (marcas.size < 2) {
            _estado.update { it.copy(marcas = marcas, tramoVisible = emptyList(), errorTramo = null, confirmado = false) }
            return
        }
        when (val r = TramoPublico.elegir(e.recorridoCompleto, o, d, marcas[0], marcas[1], reglas)) {
            is EleccionTramo.Valido -> _estado.update { it.copy(marcas = marcas, tramoVisible = r.puntos, errorTramo = null, confirmado = true) }
            is EleccionTramo.Invalido -> _estado.update { it.copy(marcas = marcas, tramoVisible = emptyList(), errorTramo = r.mensaje, confirmado = false) }
        }
    }

    fun borrarMarcas() = _estado.update { it.copy(marcas = emptyList(), tramoVisible = emptyList(), errorTramo = null, confirmado = false) }

    fun confirmar(valor: Boolean) = _estado.update { it.copy(confirmado = valor) }

    private fun tramoAPublicar(): TramoPublicado? {
        val e = _estado.value
        if (e.tramoUtilizable == null) return null
        return when (e.modo) {
            ModoTramo.AUTOMATICO -> TramoPublicado(TipoTramo.AUTOMATICO, e.tramoVisible)
            ModoTramo.ELEGIR -> TramoPublicado(TipoTramo.ELEGIDO, e.tramoVisible)
            ModoTramo.SIN_BUSCAR -> TramoPublicado(TipoTramo.NINGUNO, emptyList())
            ModoTramo.COMPLETO -> TramoPublicado(TipoTramo.COMPLETO, e.recorridoCompleto)
        }
    }

    fun publicar(borrador: BorradorRuta, nombre: String, esConductor: Boolean) {
        if (_estado.value.publicando || _estado.value.publicadaComo != null) return
        // El conductor debe confirmar la vista previa del tramo visible antes de publicar.
        if (esConductor && !_estado.value.listoParaPublicar) return
        val id = idPendiente ?: rutas.nuevoId(esConductor).also { idPendiente = it }

        _estado.update { it.copy(publicando = true, error = null) }
        viewModelScope.launch {
            val e = _estado.value
            // Solo grupos donde sigo siendo integrante (las reglas rechazarían otros).
            val grupos = if (esConductor && e.soloGrupos) e.gruposElegidos.filter { g -> e.misGrupos?.any { it.id == g } ?: true } else emptyList()
            when (val r = rutas.publicar(id, borrador, nombre, esConductor, alias = e.alias, tramo = tramoAPublicar(), edicion = edicion, grupos = grupos)) {
                is Resultado.Exito -> _estado.update { it.copy(publicando = false, publicadaComo = r.valor) }
                is Resultado.Error -> _estado.update { it.copy(publicando = false, error = r.mensaje) }
            }
        }
    }

    /** Para la siguiente ruta: se olvida la publicación terminada. */
    fun reiniciar() {
        idPendiente = null; edicion = false
        origen = null; destino = null; borradorPreparado = null
        _estado.value = EstadoConfirmar()
    }

    companion object {
        val Fabrica: ViewModelProvider.Factory = viewModelFactory {
            initializer { ConfirmarViewModel(RutasRepositorio()) }
        }
    }
}
