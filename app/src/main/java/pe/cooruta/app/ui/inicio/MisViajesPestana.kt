package pe.cooruta.app.ui.inicio

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.TextButton
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import pe.cooruta.app.ui.componentes.CampoTexto
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import pe.cooruta.app.data.Resultado
import pe.cooruta.app.data.repositorio.MiRuta
import pe.cooruta.app.data.repositorio.RutasRepositorio
import pe.cooruta.app.ui.componentes.IconoReloj
import pe.cooruta.app.ui.componentes.IconoAsiento
import pe.cooruta.app.ui.horario.formatoSoles
import pe.cooruta.app.ui.horario.formatoHora
import pe.cooruta.app.ui.horario.resumenDias
import pe.cooruta.app.ui.theme.Coo

data class EstadoViajes(
    val cargando: Boolean = true,
    val viajes: List<MiRuta> = emptyList(),
    val error: String? = null,
    /** Ruta abierta en el mapa (solo para ver). */
    val mapa: VistaMapa? = null,
    /** Conductor: ruta que no se pudo eliminar (tiene pasajeros) y el motivo; se ofrece «de todos modos». */
    val bloqueoEliminar: Pair<MiRuta, String>? = null
)

/** [completo]: recorrido privado en gris; [visible]: tramo público (o el trayecto del pasajero) en azul. */
data class VistaMapa(val titulo: String, val completo: List<pe.cooruta.app.data.Punto>,
                     val visible: List<pe.cooruta.app.data.Punto>, val cargando: Boolean = false)

/** Mis viajes: rutas publicadas (conductor) o rutas guardadas (pasajero), de Firestore. */
class MisViajesViewModel(private val repo: RutasRepositorio) : ViewModel() {
    private val _estado = MutableStateFlow(EstadoViajes())
    val estado: StateFlow<EstadoViajes> = _estado.asStateFlow()

    private var uidCargado: String? = null

    fun cargar(esConductor: Boolean) {
        // Otra cuenta en el mismo dispositivo: se vacía la lista antes de cargar la nueva.
        val uid = repo.miUid
        if (uid != uidCargado) { uidCargado = uid; _estado.value = EstadoViajes() }
        _estado.update { it.copy(cargando = true, error = null) }
        viewModelScope.launch {
            val r = if (esConductor) repo.misRutas() else repo.misBusquedas()
            when (r) {
                is Resultado.Exito -> _estado.update { it.copy(cargando = false, viajes = r.valor) }
                is Resultado.Error -> _estado.update { it.copy(cargando = false, error = r.mensaje) }
            }
        }
    }

    /** Conductor: marca o desmarca un día suelto en que no viaja. */
    fun alternarFechaLibre(v: MiRuta, fecha: String, alAvisar: (String) -> Unit) {
        val nuevas = if (fecha in v.fechasLibres) v.fechasLibres - fecha else v.fechasLibres + fecha
        _estado.update { e -> e.copy(viajes = e.viajes.map { if (it.id == v.id) it.copy(fechasLibres = nuevas) else it }) }
        viewModelScope.launch {
            when (val r = repo.cambiarFechasLibres(v.id, nuevas)) {
                is Resultado.Exito -> alAvisar(if (fecha in nuevas) "Listo: ese día no aparece en Buscar y avisamos a tus pasajeros."
                    else "Volverás a viajar ese día.")
                is Resultado.Error -> {
                    alAvisar(r.mensaje)
                    _estado.update { e -> e.copy(viajes = e.viajes.map { if (it.id == v.id) it.copy(fechasLibres = v.fechasLibres) else it }) }
                }
            }
        }
    }

    /** Conductor: pausar o reactivar la ruta (deja de aparecer en Buscar mientras está pausada). */
    fun alternarPausa(v: MiRuta, alAvisar: (String) -> Unit) {
        viewModelScope.launch {
            when (val r = repo.cambiarEstado(v.id, !v.activa)) {
                is Resultado.Exito -> _estado.update { e -> e.copy(viajes = e.viajes.map { if (it.id == v.id) it.copy(activa = !v.activa) else it }) }
                is Resultado.Error -> alAvisar(r.mensaje)
            }
        }
    }

    fun eliminar(id: String, esConductor: Boolean, alAvisar: (String) -> Unit) {
        viewModelScope.launch {
            when (val r = repo.eliminar(id, esConductor)) {
                is Resultado.Exito -> {
                    _estado.update { e -> e.copy(viajes = e.viajes.filterNot { it.id == id }) }
                    alAvisar("Ruta eliminada.")
                }
                // Trayecto del pasajero: Firestore ya lo borró en el teléfono y lo envía al volver la señal.
                is Resultado.Error -> if (!esConductor && r.mensaje.startsWith("Sin conexión")) {
                    _estado.update { e -> e.copy(viajes = e.viajes.filterNot { it.id == id }) }
                    alAvisar("Trayecto eliminado. Se sincronizará cuando mejore la conexión.")
                } else if (esConductor && !r.mensaje.startsWith("Sin conexión")) {
                    _estado.value.viajes.firstOrNull { it.id == id }?.let { v -> _estado.update { it.copy(bloqueoEliminar = v to r.mensaje) } }
                        ?: alAvisar(r.mensaje)
                } else alAvisar(r.mensaje)
            }
        }
    }

    fun cerrarBloqueo() = _estado.update { it.copy(bloqueoEliminar = null) }

    /** Elimina la ruta aunque tenga pasajeros: el servidor cancela sus viajes y les avisa. */
    fun eliminarForzado(id: String, alAvisar: (String) -> Unit) {
        _estado.update { it.copy(bloqueoEliminar = null) }
        viewModelScope.launch {
            when (val r = repo.eliminarForzado(id)) {
                is Resultado.Exito -> {
                    _estado.update { e -> e.copy(viajes = e.viajes.filterNot { it.id == id }) }
                    alAvisar(if (r.valor > 0) "Ruta eliminada. Se cancelaron ${r.valor} viaje(s) y se avisó a tus pasajeros." else "Ruta eliminada.")
                }
                is Resultado.Error -> alAvisar(r.mensaje)
            }
        }
    }

    fun cambiarVisibilidad(id: String, tipo: pe.cooruta.app.data.TipoTramo, alAvisar: (String) -> Unit) {
        viewModelScope.launch {
            when (val r = repo.cambiarVisibilidad(id, tipo)) {
                is Resultado.Exito -> { alAvisar(when (tipo) {
                    pe.cooruta.app.data.TipoTramo.NINGUNO -> "Tu ruta ahora es privada: no aparece en Buscar."
                    else -> "Tu ruta ahora es pública: aparece en Buscar."
                }); cargar(esConductor = true) }
                is Resultado.Error -> alAvisar(r.mensaje)
            }
        }
    }

    fun verMapa(v: MiRuta, esConductor: Boolean) {
        val titulo = v.alias.ifBlank { "${v.origen} → ${v.destino}" }
        val propio = v.recorrido.ifEmpty { listOfNotNull(v.origenPunto, v.destinoPunto) }
        if (!esConductor) { _estado.update { it.copy(mapa = VistaMapa(titulo, emptyList(), propio)) }; return }
        _estado.update { it.copy(mapa = VistaMapa(titulo, emptyList(), v.recorrido, cargando = true)) }
        viewModelScope.launch {
            val privado = (pe.cooruta.app.data.repositorio.ViajesRepositorio().privadoDeRuta(v.id) as? Resultado.Exito)?.valor?.recorrido.orEmpty()
            _estado.update { e -> if (e.mapa?.titulo != titulo) e else e.copy(mapa = VistaMapa(titulo, privado, v.recorrido)) }
        }
    }

    fun cerrarMapa() = _estado.update { it.copy(mapa = null) }

    fun renombrar(id: String, alias: String, esConductor: Boolean, alAvisar: (String) -> Unit) {
        viewModelScope.launch {
            when (val r = repo.renombrar(id, alias, esConductor)) {
                is Resultado.Exito -> _estado.update { e ->
                    e.copy(viajes = e.viajes.map { if (it.id == id) it.copy(alias = alias.trim()) else it })
                }
                is Resultado.Error -> alAvisar(r.mensaje)
            }
        }
    }

    companion object {
        val Fabrica: ViewModelProvider.Factory = viewModelFactory {
            initializer { MisViajesViewModel(RutasRepositorio()) }
        }
    }
}

@Composable
fun MisViajesPestana(
    esConductor: Boolean,
    alUsarHoy: (String) -> Unit,
    alAvisar: (String) -> Unit,
    modifier: Modifier = Modifier,
    alEditarRuta: (String, pe.cooruta.app.data.BorradorRuta, String) -> Unit = { _, _, _ -> },
    alCrear: () -> Unit = {}
) {
    if (esConductor) ContenidoViajes(true, alUsarHoy, alAvisar, modifier, alEditarRuta = alEditarRuta, alCrear = alCrear)
    else Column(modifier.fillMaxSize()) {
        // Las reservas confirmadas se leen de `reservas`, aparte de las rutas guardadas.
        ReservasConfirmadas()
        ContenidoViajes(false, alUsarHoy, alAvisar, Modifier.weight(1f), alEditarRuta = alEditarRuta, alCrear = alCrear)
    }
}

@Composable
private fun ContenidoViajes(
    esConductor: Boolean,
    alUsarHoy: (String) -> Unit,
    alAvisar: (String) -> Unit,
    modifier: Modifier = Modifier,
    alEditarRuta: (String, pe.cooruta.app.data.BorradorRuta, String) -> Unit = { _, _, _ -> },
    alCrear: () -> Unit = {},
    vm: MisViajesViewModel = viewModel(factory = MisViajesViewModel.Fabrica)
) {
    val estado by vm.estado.collectAsStateWithLifecycle()
    LaunchedEffect(esConductor) { vm.cargar(esConductor) }
    var editando by remember { mutableStateOf<MiRuta?>(null) }
    var texto by remember { mutableStateOf("") }
    var borrando by remember { mutableStateOf<MiRuta?>(null) }
    estado.mapa?.let { m -> DialogoMapaRuta(m, esConductor, vm::cerrarMapa) }

    borrando?.let { v ->
        AlertDialog(
            onDismissRequest = { borrando = null },
            containerColor = Coo.Campo,
            title = { Text("¿Eliminar esta ruta?", color = Coo.Texto, fontWeight = FontWeight.Bold) },
            text = {
                Text("«${v.alias.ifBlank { "${v.origen} → ${v.destino}" }}» se borrará y no se puede deshacer.",
                    color = Coo.TextoTenue)
            },
            confirmButton = {
                TextButton(onClick = { vm.eliminar(v.id, esConductor, alAvisar); borrando = null }) {
                    Text("Eliminar", color = Coo.RojoError, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = { TextButton(onClick = { borrando = null }) { Text("Cancelar", color = Coo.Texto) } }
        )
    }

    estado.bloqueoEliminar?.let { (v, motivo) ->
        AlertDialog(
            onDismissRequest = { vm.cerrarBloqueo() },
            containerColor = Coo.Campo,
            title = { Text("Esta ruta tiene pasajeros", color = Coo.Texto, fontWeight = FontWeight.Bold) },
            text = {
                Text("$motivo\n\nSi la eliminas de todos modos, se cancelarán sus viajes de hoy en adelante " +
                    "(también los habituales) y les avisaremos.", color = Coo.TextoTenue)
            },
            confirmButton = {
                TextButton(onClick = { vm.eliminarForzado(v.id, alAvisar) }) {
                    Text("Eliminar de todos modos", color = Coo.RojoError, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = { TextButton(onClick = { vm.cerrarBloqueo() }) { Text("Cancelar", color = Coo.Texto) } }
        )
    }

    editando?.let { v ->
        AlertDialog(
            onDismissRequest = { editando = null },
            containerColor = Coo.Campo,
            title = { Text("Nombre de la ruta", color = Coo.Texto, fontWeight = FontWeight.Bold) },
            text = {
                CampoTexto(valor = texto, alCambiar = { texto = it.take(40) }, etiqueta = "Ej.: Casa → Tecsup")
            },
            confirmButton = {
                TextButton(onClick = {
                    vm.renombrar(v.id, texto, esConductor, alAvisar); editando = null
                }) { Text("Guardar", color = Coo.AzulUbicacion, fontWeight = FontWeight.Bold) }
            },
            dismissButton = { TextButton(onClick = { editando = null }) { Text("Cancelar", color = Coo.Texto) } }
        )
    }

    when {
        estado.cargando -> Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = Coo.AzulUbicacion)
        }
        estado.error != null -> Mensaje(modifier, estado.error.orEmpty(), "Toca para reintentar") { vm.cargar(esConductor) }
        estado.viajes.isEmpty() -> VacioCrear(modifier, if (esConductor) "Aún no publicas rutas" else "Aún no guardas trayectos", alCrear)
        else -> LazyColumn(
            modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Conductor: su grupo de confianza (invitaciones por enlace).
            // (Grupo por invitación desactivado: la app entera es un solo grupo.)
            item {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(if (esConductor) "Rutas que publicaste" else "Rutas que guardaste",
                        color = Coo.TextoTenue, fontSize = 13.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    Text("Crear +", color = Coo.AzulUbicacion, fontSize = 15.sp, fontWeight = FontWeight.Bold,
                        modifier = Modifier.clip(RoundedCornerShape(10.dp)).clickable(onClick = alCrear)
                            .padding(horizontal = 10.dp, vertical = 6.dp))
                }
            }
            // Conductor: primero las públicas (orden estable: dentro de cada grupo, como venían).
            val ordenadas = if (esConductor) estado.viajes.sortedBy { if (it.visibleEnBuscar) 0 else 1 } else estado.viajes
            items(ordenadas, key = { it.id }) { v ->
                Tarjeta(v, esConductor, alRenombrar = { texto = v.alias; editando = v }, alEliminar = { borrando = v },
                    alVisibilidad = { t -> vm.cambiarVisibilidad(v.id, t, alAvisar) },
                    alVisibilidadSilenciosa = { t -> vm.cambiarVisibilidad(v.id, t) {} },
                    alVerMapa = { vm.verMapa(v, esConductor) },
                    alPausar = { vm.alternarPausa(v, alAvisar) },
                    alFechaLibre = { f -> vm.alternarFechaLibre(v, f, alAvisar) },
                    alEditar = {
                        val d = v.destinoPunto
                        if (d == null) alAvisar("No encontramos el destino de esta ruta. Créala de nuevo.")
                        else alEditarRuta(v.id, pe.cooruta.app.data.BorradorRuta(
                            origen = v.origenPunto?.let { pe.cooruta.app.data.Lugar(v.origen, "", it.latitud, it.longitud) },
                            destino = pe.cooruta.app.data.Lugar(v.destino, "", d.latitud, d.longitud),
                            trayecto = null,
                            horario = pe.cooruta.app.data.HorarioRuta(v.dias, v.salida, if (esConductor) v.asientos else null, v.pasajeCentimos)
                        ), v.alias)
                    }) { alUsarHoy(v.id) }
            }
        }
    }
}

@Composable
private fun Tarjeta(v: MiRuta, esConductor: Boolean, alRenombrar: () -> Unit, alEliminar: () -> Unit,
                    alVisibilidad: (pe.cooruta.app.data.TipoTramo) -> Unit = {}, alVerMapa: () -> Unit = {},
                    alEditar: () -> Unit = {}, alPausar: () -> Unit = {}, alFechaLibre: (String) -> Unit = {},
                    alVisibilidadSilenciosa: (pe.cooruta.app.data.TipoTramo) -> Unit = {},
                    alUsarHoy: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    var elegirDias by remember { mutableStateOf(false) }
    if (elegirDias) DialogoDiasLibres(v, alFechaLibre) { elegirDias = false }
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(NEGRO_RUTA)
            .border(1.dp, BORDE_RUTA, RoundedCornerShape(20.dp)).clickable(onClick = alVerMapa).padding(16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                if (v.alias.isNotBlank()) Text(v.alias, color = Coo.Texto, fontSize = 17.sp,
                    fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    (if (esConductor) "" else "Guardada · ") + resumenDias(v.dias),
                    color = Coo.AzulUbicacion, fontSize = 13.sp, fontWeight = FontWeight.Bold
                )
            }
            // Conductor: interruptor «Activa / Apagada» (reemplaza «Pausar ruta»).
            if (esConductor) Column(horizontalAlignment = Alignment.CenterHorizontally) {
                androidx.compose.material3.Switch(
                    checked = v.activa, onCheckedChange = { alPausar() },
                    colors = androidx.compose.material3.SwitchDefaults.colors(
                        checkedThumbColor = Coo.SobreBoton, checkedTrackColor = Coo.Exito,
                        uncheckedThumbColor = Coo.TextoTenue, uncheckedTrackColor = Coo.CampoActivo, uncheckedBorderColor = Coo.Borde)
                )
                Text(if (v.activa) "Activa" else "Apagada", color = if (v.activa) Coo.Exito else Coo.TextoTenue, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            }
            Box {
                Text(
                    "⋮", color = Coo.TextoTenue, fontSize = 22.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.clip(CircleShape).clickable { menu = true }
                        .padding(horizontal = 12.dp, vertical = 2.dp)
                )
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }, containerColor = Coo.CampoActivo) {
                    DropdownMenuItem(
                        text = { Text("Editar ruta (recorrido, horario" + (if (esConductor) ", asientos)" else ")"), color = Coo.Texto) },
                        onClick = { menu = false; alEditar() }
                    )
                    if (esConductor) DropdownMenuItem(
                        text = { Text("Elegir días que no viajo", color = Coo.Texto) },
                        onClick = { menu = false; elegirDias = true }
                    )
                    DropdownMenuItem(
                        text = { Text(if (v.alias.isBlank()) "Ponerle nombre" else "Renombrar", color = Coo.Texto) },
                        onClick = { menu = false; alRenombrar() }
                    )
                    DropdownMenuItem(
                        text = { Text("Eliminar ruta", color = Coo.RojoError) },
                        onClick = { menu = false; alEliminar() }
                    )
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        Linea(Coo.AzulUbicacion, v.origen)
        Box(Modifier.padding(start = 4.dp).width(2.dp).height(14.dp).background(Coo.Borde))
        Linea(Coo.RojoDestino, v.destino)
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconoReloj(color = Coo.TextoTenue, tamano = 18.dp)
            Spacer(Modifier.width(6.dp))
            val min = v.duracionSegundos?.let { " · ${(it + 59) / 60} min" }.orEmpty()
            Text("Sale ${formatoHora(v.salida)}$min", color = Coo.TextoTenue, fontSize = 14.sp)
        }
        if (esConductor) {
            Spacer(Modifier.height(8.dp))
            // Las públicas de versiones anteriores (solo un tramo) pasan a mostrarse completas, en silencio.
            LaunchedEffect(v.id, v.tramoPublico) {
                if (v.tramoPublico == pe.cooruta.app.data.TipoTramo.AUTOMATICO.valor || v.tramoPublico == "elegido")
                    alVisibilidadSilenciosa(pe.cooruta.app.data.TipoTramo.COMPLETO)
            }
            // Selector Pública | Privada: se toca la opción que se quiere.
            val publica = v.visibleEnBuscar
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Coo.Fondo)
                    .border(1.dp, Coo.Borde, RoundedCornerShape(12.dp)).padding(4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                listOf(true to "Pública", false to "Privada").forEach { (esPublica, etiqueta) ->
                    val elegida = publica == esPublica
                    val fondo = when { !elegida -> Color.Transparent; esPublica -> Coo.ExitoFondo; else -> Coo.CampoActivo }
                    val color = when { !elegida -> Coo.TextoTenue; esPublica -> Coo.Exito; else -> Coo.Texto }
                    Row(
                        Modifier.weight(1f).clip(RoundedCornerShape(9.dp)).background(fondo)
                            .clickable(enabled = !elegida) {
                                alVisibilidad(if (esPublica) pe.cooruta.app.data.TipoTramo.COMPLETO else pe.cooruta.app.data.TipoTramo.NINGUNO)
                            }.padding(vertical = 9.dp),
                        horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically
                    ) {
                        pe.cooruta.app.ui.confirmar.IconoCandado(abierto = esPublica, color = color, tamano = 16.dp)
                        Spacer(Modifier.width(6.dp))
                        Text(etiqueta, color = color, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
            Text(if (publica) "Los pasajeros la ven en Buscar y pueden pedirte asiento." else "Solo tú la ves; nadie puede pedirte asiento.",
                color = Coo.TextoTenue, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp, bottom = 8.dp))
            // Asientos: un asiento por plaza (azul = libre, gris = ocupado) y el pasaje aparte.
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Coo.Campo)
                    .border(1.dp, Coo.Borde, RoundedCornerShape(16.dp)).padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        repeat(v.asientos.coerceIn(0, 8)) { i ->
                            val libre = i < v.asientosLibres
                            Box(
                                Modifier.size(32.dp).clip(RoundedCornerShape(10.dp))
                                    .background(if (libre) Coo.AzulBoton.copy(alpha = 0.18f) else Coo.CampoActivo)
                                    .border(1.dp, if (libre) Coo.AzulBoton.copy(alpha = 0.6f) else Coo.Borde, RoundedCornerShape(10.dp)),
                                contentAlignment = Alignment.Center
                            ) { IconoAsiento(color = if (libre) Coo.AzulUbicacion else Coo.TextoTenue, tamano = 18.dp, relleno = libre) }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        when {
                            v.asientos <= 0 -> "Sin asientos configurados"
                            v.asientosLibres <= 0 -> "Sin asientos libres"
                            else -> "${v.asientosLibres} de ${v.asientos} asientos libres"
                        },
                        color = if (v.asientosLibres > 0) Coo.Texto else Coo.RojoError, fontSize = 14.sp, fontWeight = FontWeight.Bold
                    )
                }
                v.pasajeCentimos?.let { p ->
                    Column(horizontalAlignment = Alignment.End, modifier = Modifier.padding(start = 10.dp)) {
                        Text(formatoSoles(p), color = Coo.Texto, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                        Text("por pasajero", color = Coo.TextoTenue, fontSize = 12.sp)
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
            if (!v.activa) Text("Apagada: no aparece en Buscar ni en Iniciar hasta que la actives.",
                color = Coo.TextoTenue, fontSize = 12.sp)
            else {
                val hoy = java.time.LocalDate.now()
                val proximas = v.fechasLibres.filter { it >= hoy.toString() }.sorted()
                if (proximas.isNotEmpty()) Text("No viaja: " + proximas.take(4).joinToString(", ") { fechaCorta(it) } +
                    (if (proximas.size > 4) "…" else ""), color = Coo.Advertencia, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            }
        }
        if (!esConductor) {
            Spacer(Modifier.height(14.dp))
            Button(
                onClick = alUsarHoy,
                modifier = Modifier.fillMaxWidth().height(48.dp),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Coo.AzulBoton, contentColor = Coo.SobreBoton)
            ) { Text("Usar esta ruta hoy", fontWeight = FontWeight.Bold) }
        }
    }
}

@Composable
private fun Linea(color: Color, texto: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(10.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(12.dp))
        Text(texto, color = Coo.Texto, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun Mensaje(modifier: Modifier, titulo: String, texto: String, alPulsar: () -> Unit) {
    Column(
        modifier.fillMaxSize().clickable(onClick = alPulsar).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(titulo, color = Coo.Texto, fontSize = 20.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        Text(texto, color = Coo.TextoTenue, textAlign = TextAlign.Center)
    }
}

/** Solo para ver el trazado de una ruta o trayecto (sin editar). */
@Composable
private fun DialogoMapaRuta(m: VistaMapa, esConductor: Boolean, alCerrar: () -> Unit) {
    androidx.compose.ui.window.Dialog(onDismissRequest = alCerrar,
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.fillMaxWidth().padding(16.dp).clip(RoundedCornerShape(20.dp)).background(Coo.Campo).padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(m.titulo, color = Coo.Texto, fontSize = 17.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                androidx.compose.material3.TextButton(onClick = alCerrar) { Text("Cerrar") }
            }
            Box(Modifier.fillMaxWidth().height(380.dp).clip(RoundedCornerShape(16.dp)), contentAlignment = Alignment.Center) {
                if (pe.cooruta.app.BuildConfig.HAY_MAPA) androidx.compose.runtime.key(m.completo.size, m.cargando) { pe.cooruta.app.ui.confirmar.MapaVista(m.completo.ifEmpty { m.visible }) }
                else Text("Sin mapa en este dispositivo.", color = Coo.TextoTenue)
                if (m.cargando) androidx.compose.material3.CircularProgressIndicator(color = Coo.AzulUbicacion)
            }
            Text(
                "Pin azul: inicio. Pin rojo (⚑): destino.",
                color = Coo.TextoTenue, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp)
            )
        }
    }
}


/** "2026-10-29" → "jue 29". */
private fun fechaCorta(iso: String): String = runCatching {
    java.time.LocalDate.parse(iso).format(java.time.format.DateTimeFormatter.ofPattern("EEE d", java.util.Locale("es", "PE")))
}.getOrDefault(iso)

/** Próximos 14 días en que sale la ruta: se tocan para marcar «no viajo». */
@Composable
private fun DialogoDiasLibres(v: MiRuta, alAlternar: (String) -> Unit, alCerrar: () -> Unit) {
    val hoy = java.time.LocalDate.now()
    val dias = (0L until 21L).map { hoy.plusDays(it) }.filter { v.dias.isEmpty() || it.dayOfWeek in v.dias }.take(14)
    androidx.compose.material3.AlertDialog(
        onDismissRequest = alCerrar,
        containerColor = Coo.Campo,
        title = { Text("¿Qué días no viajas?", fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Esos días la ruta no aparece en Buscar y avisamos a tus pasajeros.", color = Coo.TextoTenue, fontSize = 13.sp)
                dias.chunked(4).forEach { fila ->
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        fila.forEach { d ->
                            val libre = d.toString() in v.fechasLibres
                            Text(fechaCorta(d.toString()), color = if (libre) Coo.SobreBoton else Coo.Texto, fontSize = 13.sp,
                                fontWeight = FontWeight.Bold, textAlign = TextAlign.Center,
                                modifier = Modifier.width(62.dp).clip(RoundedCornerShape(10.dp))
                                    .background(if (libre) Coo.Advertencia.copy(alpha = 0.85f) else Coo.CampoActivo)
                                    .clickable { alAlternar(d.toString()) }.padding(vertical = 10.dp))
                        }
                    }
                }
                Text("Marcados en amarillo = no viajas.", color = Coo.TextoTenue, fontSize = 12.sp)
            }
        },
        confirmButton = { androidx.compose.material3.TextButton(onClick = alCerrar) { Text("Listo") } }
    )
}


/** Vacío con un «+» de líneas finas (sin color): todo el bloque crea la primera ruta. */
@Composable
private fun VacioCrear(modifier: Modifier, titulo: String, alCrear: () -> Unit) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            Modifier.clip(RoundedCornerShape(16.dp)).clickable(onClick = alCrear).padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            androidx.compose.foundation.Canvas(Modifier.size(64.dp)) {
                val w = size.width; val g = w * 0.045f
                val c = Coo.Borde
                drawCircle(c, radius = w * 0.4f, style = androidx.compose.ui.graphics.drawscope.Stroke(width = g))
                drawLine(c, androidx.compose.ui.geometry.Offset(w * 0.5f, w * 0.32f), androidx.compose.ui.geometry.Offset(w * 0.5f, w * 0.68f), g,
                    cap = androidx.compose.ui.graphics.StrokeCap.Round)
                drawLine(c, androidx.compose.ui.geometry.Offset(w * 0.32f, w * 0.5f), androidx.compose.ui.geometry.Offset(w * 0.68f, w * 0.5f), g,
                    cap = androidx.compose.ui.graphics.StrokeCap.Round)
            }
            Spacer(Modifier.height(14.dp))
            Text(titulo, color = Coo.TextoTenue, fontSize = 15.sp, fontWeight = FontWeight.Medium)
        }
    }
}

/** Fondo y borde negros de la tarjeta de ruta (igual que las demás tarjetas negras). */
private val NEGRO_RUTA = Color(0xFF121212)
private val BORDE_RUTA = Color(0xFF2A2A2A)
