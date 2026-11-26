package pe.cooruta.app.ui.inicio

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Cancel
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.LocalOffer
import androidx.compose.foundation.background
import androidx.compose.ui.text.withStyle
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
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
import pe.cooruta.app.data.repositorio.DesvioHabitual
import pe.cooruta.app.data.repositorio.Habitual
import pe.cooruta.app.data.repositorio.HabitualesRepositorio
import pe.cooruta.app.ui.horario.formatoHora
import pe.cooruta.app.ui.horario.formatoSoles
import pe.cooruta.app.ui.horario.resumenDias
import pe.cooruta.app.ui.theme.Coo
import java.time.DayOfWeek
import java.time.LocalTime

// ------------------------------------------------------------------ estado

data class EstadoHabituales(
    val lista: List<Habitual> = emptyList(),
    val error: String? = null,
    /** Id del acuerdo con una operación en curso. */
    val ocupado: String? = null,
    val desvios: Map<String, DesvioHabitual> = emptyMap(),
    val calculando: Set<String> = emptySet()
)

/** Acuerdos habituales del usuario como pasajero o como conductor. */
class HabitualesViewModel(private val repo: HabitualesRepositorio, comoConductor: Boolean) : ViewModel() {
    private val _estado = MutableStateFlow(EstadoHabituales())
    val estado: StateFlow<EstadoHabituales> = _estado.asStateFlow()

    init {
        viewModelScope.launch {
            repo.enVivo(comoConductor).collect { r ->
                when (r) {
                    is Resultado.Exito -> _estado.update { it.copy(lista = r.valor, error = null) }
                    is Resultado.Error -> _estado.update { it.copy(error = r.mensaje) }
                }
            }
        }
    }

    fun verDesvio(h: Habitual, alAvisar: (String) -> Unit) {
        if (h.id in _estado.value.calculando) return
        _estado.update { it.copy(calculando = it.calculando + h.id) }
        viewModelScope.launch {
            when (val r = repo.desvio(h.id)) {
                is Resultado.Exito -> _estado.update { it.copy(desvios = it.desvios + (h.id to r.valor)) }
                is Resultado.Error -> alAvisar(r.mensaje)
            }
            _estado.update { it.copy(calculando = it.calculando - h.id) }
        }
    }

    fun responder(h: Habitual, aceptar: Boolean, alAvisar: (String) -> Unit) = operar(h) {
        when (val r = repo.responder(h.id, aceptar)) {
            is Resultado.Error -> r.mensaje
            is Resultado.Exito -> if (!aceptar) "Rechazaste la solicitud de ${primerNombre(h.nombrePasajero)}."
                else "${primerNombre(h.nombrePasajero)} viajará contigo ${losDias(h.dias)}." +
                    if (r.valor > 0) " Ya tiene asiento en ${r.valor} viaje(s) de esta semana." else " Su asiento empieza la próxima semana."
        }.let(alAvisar)
    }

    fun terminar(h: Habitual, alAvisar: (String) -> Unit) = operar(h) {
        alAvisar(when (val r = repo.terminar(h.id)) {
            is Resultado.Error -> r.mensaje
            is Resultado.Exito -> "Terminaste el viaje habitual." +
                if (r.valor > 0) " Se liberaron ${r.valor} viaje(s) reservados." else ""
        })
    }

    fun cancelar(h: Habitual, alAvisar: (String) -> Unit) = operar(h) {
        alAvisar(when (val r = repo.cancelar(h.id)) {
            is Resultado.Error -> r.mensaje
            is Resultado.Exito -> "Retiraste tu solicitud."
        })
    }

    private fun operar(h: Habitual, bloque: suspend () -> Unit) {
        if (_estado.value.ocupado != null) return
        _estado.update { it.copy(ocupado = h.id) }
        viewModelScope.launch {
            bloque()
            _estado.update { it.copy(ocupado = null) }
        }
    }

    companion object {
        fun fabrica(comoConductor: Boolean): ViewModelProvider.Factory = viewModelFactory {
            initializer { HabitualesViewModel(HabitualesRepositorio(), comoConductor) }
        }
    }
}

// ------------------------------------------------------------------ formatos

private fun primerNombre(n: String) = n.trim().substringBefore(" ").ifBlank { "El pasajero" }

/** "los lun a vie", "todos los días". */
fun losDias(dias: List<Int>): String {
    val t = resumenDias(dias.filter { it in 1..7 }.map(DayOfWeek::of).toSet())
    return if (t == "Todos los días") "todos los días" else "los ${t.lowercase()}"
}

/** "07:12" → "07:12 a. m."; vacío si no se pudo leer. */
private fun hora(hhmm: String) = runCatching { formatoHora(LocalTime.parse(hhmm)) }.getOrDefault(hhmm)

private fun minutos(seg: Int) = "${(seg + 30) / 60} min"
private fun km(m: Int) = if (m < 1000) "$m m" else "%.1f km".format(m / 1000.0)

// ------------------------------------------------------------------ conductor

/**
 * Conductor, pestaña Solicitudes: acuerdos habituales [pendientes] (con desvío, cupo y respuesta) o ya
 * aceptados. Devuelve true si mostró algo.
 */
@Composable
fun HabitualesConductor(alAvisar: (String) -> Unit, pendientes: Boolean): Boolean {
    val vm: HabitualesViewModel = viewModel(key = "habituales_conductor", factory = HabitualesViewModel.fabrica(true))
    val e by vm.estado.collectAsStateWithLifecycle()
    val lista = e.lista.filter { it.estado == if (pendientes) "pendiente" else "aceptado" }
    if (lista.isEmpty()) return false
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(if (pendientes) "Quieren viajar contigo habitualmente" else "Pasajeros habituales",
            color = Coo.TextoTenue, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        lista.forEach { h -> TarjetaHabitualConductor(h, e, pendientes, vm, alAvisar) }
        Spacer(Modifier.height(6.dp))
    }
    return true
}

@Composable
private fun TarjetaHabitualConductor(h: Habitual, e: EstadoHabituales, pendiente: Boolean, vm: HabitualesViewModel, alAvisar: (String) -> Unit) {
    val ocupado = e.ocupado == h.id
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Coo.Campo)
            .border(if (pendiente) 2.dp else 1.dp, if (pendiente) Coo.AzulUbicacion else Coo.BordeSuave, RoundedCornerShape(16.dp))
            .padding(16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            FotoDe(h.pasajeroId, h.nombrePasajero)
            Spacer(Modifier.size(10.dp))
            Column(Modifier.weight(1f)) {
                Text(h.nombrePasajero, color = Coo.Texto, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                Text("${losDias(h.dias).replaceFirstChar { it.uppercase() }} · recogida ${hora(h.horaRecogida)}",
                    color = Coo.AzulUbicacion, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(formatoSoles(h.pasajeCentimos), color = Coo.Texto, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Text("por viaje", color = Coo.TextoTenue, fontSize = 12.sp)
            }
        }
        Spacer(Modifier.height(10.dp))
        Text("Sube en ${h.subida}", color = Coo.Texto, fontSize = 14.sp)
        Text("Baja en ${h.bajada}", color = Coo.TextoTenue, fontSize = 14.sp)
        if (h.mensaje.isNotBlank()) {
            Spacer(Modifier.height(8.dp))
            Text("“${h.mensaje}”", color = Coo.Texto, fontSize = 14.sp,
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Coo.Fondo).padding(10.dp))
        }
        if (!pendiente) {
            BotonTerminar(ocupado) { vm.terminar(h, alAvisar) }
            return@Column
        }
        Spacer(Modifier.height(12.dp))
        val d = e.desvios[h.id]
        if (d == null) {
            Text(if (h.id in e.calculando) "Calculando…" else "Ver cuánto cambia tu ruta",
                color = Coo.AzulUbicacion, fontSize = 14.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.clip(RoundedCornerShape(10.dp))
                    .clickable(enabled = h.id !in e.calculando) { vm.verDesvio(h, alAvisar) }
                    .padding(vertical = 12.dp))
        } else Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Coo.Fondo).padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(if (d.segundosExtra < 60 && d.metrosExtra < 300) "Casi no cambia tu ruta"
                else "+${minutos(d.segundosExtra)} y +${km(d.metrosExtra)} a tu recorrido",
                color = Coo.Texto, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            Text("Recorrido total ≈ ${d.minutosTotal} min" +
                if (d.pasajerosEsosDias > 0) ", contando a tus ${d.pasajerosEsosDias} pasajero(s) habituales de esos días" else "",
                color = Coo.TextoTenue, fontSize = 13.sp)
            if (d.horaRecogida.isNotBlank()) Text("Lo recogerías hacia las ${hora(d.horaRecogida)}", color = Coo.TextoTenue, fontSize = 13.sp)
            if (d.diasSinCupo.isNotEmpty()) Text("Sin asientos libres: ${d.diasSinCupo.joinToString(", ")}",
                color = Coo.RojoError, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            if (d.fechasSinCupo.isNotEmpty()) Text("Sin asiento esta semana: ${d.fechasSinCupo.joinToString(", ") { fechaLegible(it) }}",
                color = Coo.RojoError, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(
                onClick = { vm.responder(h, false, alAvisar) }, enabled = !ocupado, modifier = Modifier.weight(1f),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Coo.RojoError)
            ) { Text("Rechazar") }
            Button(
                onClick = { vm.responder(h, true, alAvisar) }, enabled = !ocupado, modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(containerColor = Coo.AzulBoton, contentColor = Coo.SobreBoton)
            ) { Text(if (ocupado) "…" else "Aceptar", fontWeight = FontWeight.Bold) }
        }
    }
}

// ------------------------------------------------------------------ pasajero

/** Pasajero, pestaña Solicitudes: sus acuerdos habituales y su estado. Devuelve true si mostró algo. */
@Composable
fun HabitualesPasajero(
    alAvisar: (String) -> Unit,
    conTitulo: Boolean = true,
    /** Solo estos estados (null = todos menos cancelado). */
    estados: Set<String>? = null,
    titulo: String = "Viajes habituales"
): Boolean {
    val vm: HabitualesViewModel = viewModel(key = "habituales_pasajero", factory = HabitualesViewModel.fabrica(false))
    val e by vm.estado.collectAsStateWithLifecycle()
    val ctxOcultos = androidx.compose.ui.platform.LocalContext.current
    LaunchedEffect(Unit) { pe.cooruta.app.data.HistorialOculto.cargar(ctxOcultos) }
    val ocultos by pe.cooruta.app.data.HistorialOculto.ids.collectAsStateWithLifecycle()
    val lista = e.lista.filter {
        it.estado != "cancelado" && (estados == null || it.estado in estados) &&
            !(it.estado in CERRADOS && it.id in (ocultos ?: emptySet()))
    }
    if (lista.isEmpty()) return false
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (conTitulo) Text(titulo, color = Coo.TextoTenue, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        lista.forEach { h ->
            val (titulo, color) = when (h.estado) {
                "aceptado" -> "Viajas habitualmente" to Coo.Exito
                "rechazado" -> "Solicitud rechazada" to Coo.RojoError
                "terminado" -> "Terminado" to Coo.Exito
                else -> "Esperando respuesta" to Coo.AzulUbicacion
            }
            // Deslizar: en espera → retirar (pregunta); cerrado → quitar del historial. El activo usa «Terminar».
            androidx.compose.runtime.key(h.id) {
            pe.cooruta.app.ui.componentes.DeslizableParaBorrar(
                alBorrar = {
                    if (h.estado == "pendiente") vm.cancelar(h, alAvisar)
                    else pe.cooruta.app.data.HistorialOculto.ocultar(ctxOcultos, listOf(h.id))
                },
                confirmacion = if (h.estado == "pendiente") pe.cooruta.app.ui.componentes.Confirmacion(
                    "¿Retirar esta solicitud?", "${h.nombreConductor} ya no verá tu pedido de viaje habitual.", "Retirar") else null,
                habilitado = h.estado != "aceptado" && e.ocupado == null
            ) {
            // Tarjeta negra: estado en pastilla, nombre, días y hora, punto de subida y pasaje.
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(NEGRO_HABITUAL)
                    .border(1.dp, BORDE_HABITUAL, RoundedCornerShape(20.dp)).padding(16.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    FotoDe(h.conductorId, h.nombreConductor, 56.dp)
                    Spacer(Modifier.size(14.dp))
                    Column {
                        Row(
                            Modifier.clip(RoundedCornerShape(50)).background(color.copy(alpha = 0.15f))
                                .padding(start = 6.dp, end = 12.dp, top = 4.dp, bottom = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            IconoEstadoHabitual(h.estado, color)
                            Spacer(Modifier.size(6.dp))
                            Text(titulo, color = color, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                        }
                        Spacer(Modifier.height(6.dp))
                        Text(nombrePropio(h.nombreConductor), color = Coo.Texto, fontSize = 18.sp, fontWeight = FontWeight.Bold,
                            maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                    }
                }
                Spacer(Modifier.height(14.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconoLinea("reloj", Coo.TextoTenue)
                    Spacer(Modifier.size(12.dp))
                    Text("${losDias(h.dias).removePrefix("los ").replaceFirstChar { it.uppercase() }} · te recoge a las ${hora(h.horaRecogida)}",
                        color = Coo.TextoTenue, fontSize = 14.sp)
                }
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(18.dp), contentAlignment = Alignment.Center) {
                        Box(Modifier.size(10.dp).clip(androidx.compose.foundation.shape.CircleShape).background(Coo.AzulBoton))
                    }
                    Spacer(Modifier.size(12.dp))
                    Text("Sube en ${h.subida}", color = Coo.Texto, fontSize = 15.sp,
                        maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                }
                Box(Modifier.padding(vertical = 12.dp).fillMaxWidth().height(1.dp).background(BORDE_HABITUAL))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconoLinea("etiqueta", Coo.TextoTenue)
                    Spacer(Modifier.size(12.dp))
                    Text(androidx.compose.ui.text.buildAnnotatedString {
                        withStyle(androidx.compose.ui.text.SpanStyle(color = Coo.Texto, fontWeight = FontWeight.Bold)) { append(formatoSoles(h.pasajeCentimos)) }
                        append(" por viaje")
                    }, color = Coo.TextoTenue, fontSize = 15.sp)
                }
                if (h.estado == "aceptado") {
                    Text("Si un día no vas, cancela solo ese día abajo.",
                        color = Coo.TextoTenue, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
                    BotonTerminar(e.ocupado == h.id) { vm.terminar(h, alAvisar) }
                }
                if (h.estado == "pendiente") Text(
                    if (e.ocupado == h.id) "Retirando…" else "Retirar solicitud",
                    color = Coo.RojoError, fontSize = 14.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(top = 4.dp).clip(RoundedCornerShape(10.dp))
                        .clickable(enabled = e.ocupado == null) { vm.cancelar(h, alAvisar) }.padding(vertical = 12.dp)
                )
            }
            }
            }
        }
        Spacer(Modifier.height(6.dp))
    }
    return true
}

/** Foto de perfil (o inicial) de [uid]. */
@Composable
private fun FotoDe(uid: String, nombre: String, tamano: androidx.compose.ui.unit.Dp = 40.dp) {
    val foto by androidx.compose.runtime.produceState<String?>(null, uid) {
        if (uid.isNotBlank()) value = runCatching {
            pe.cooruta.app.data.repositorio.PerfilesRepositorio().perfiles(setOf(uid))[uid]?.foto
        }.getOrNull()?.ifBlank { null }
    }
    Avatar(foto, nombre, tamano)
}

/** «Terminar viaje habitual»: pide un segundo toque antes de cancelar las reservas siguientes. */
@Composable
private fun BotonTerminar(ocupado: Boolean, alTerminar: () -> Unit) {
    var confirmar by rememberSaveable { mutableStateOf(false) }
    Text(
        when { ocupado -> "Terminando…"; confirmar -> "Toca otra vez: se cancelan los viajes siguientes"; else -> "Terminar viaje habitual" },
        color = Coo.RojoError, fontSize = 14.sp, fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(top = 8.dp).clip(RoundedCornerShape(10.dp))
            .clickable(enabled = !ocupado) { if (confirmar) { confirmar = false; alTerminar() } else confirmar = true }
            .padding(vertical = 12.dp)
    )
}

/** Estados de un acuerdo que ya no está vigente (van a Rechazadas y se pueden quitar del historial). */
val CERRADOS = setOf("rechazado", "terminado")

/** Pasajero: sus acuerdos (para repartirlos en las pestañas de Solicitudes). */
@Composable
fun habitualesDelPasajero(): List<pe.cooruta.app.data.repositorio.Habitual> {
    val vm: HabitualesViewModel = viewModel(key = "habituales_pasajero", factory = HabitualesViewModel.fabrica(false))
    val e by vm.estado.collectAsStateWithLifecycle()
    return e.lista
}

/** True si el pasajero tiene algún acuerdo que mostrar (para no decir «Aún no tienes solicitudes»). */
@Composable
fun hayHabitualesPasajero(): Boolean {
    val vm: HabitualesViewModel = viewModel(key = "habituales_pasajero", factory = HabitualesViewModel.fabrica(false))
    val e by vm.estado.collectAsStateWithLifecycle()
    return e.lista.any { it.estado != "cancelado" }
}

/**
 * Hoja de Buscar: pedir viajar habitualmente con este conductor. [diasRuta] = días en que sale
 * (vacío = todos). Plegado al inicio para no competir con «Solicitar asiento».
 */
@Composable
fun SolicitarHabitual(diasRuta: Set<DayOfWeek>, enviando: Boolean, alEnviar: (Set<Int>) -> Unit) {
    var abierto by rememberSaveable { mutableStateOf(false) }
    val posibles = (if (diasRuta.isEmpty()) DayOfWeek.entries.toSet() else diasRuta).map { it.value }.toSet()
    var elegidos by rememberSaveable { mutableStateOf(posibles.filter { it <= 5 }.ifEmpty { posibles.toList() }.toIntArray()) }
    if (!abierto) {
        Text("¿Viajas seguido por aquí? Pide viajar habitualmente",
            color = Coo.AzulUbicacion, fontSize = 14.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { abierto = true }.padding(vertical = 14.dp))
        return
    }
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Coo.Fondo).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text("Viajar habitualmente", color = Coo.Texto, fontSize = 16.sp, fontWeight = FontWeight.Bold)
        Text("Elige tus días. Una sola solicitud: si el conductor acepta, tu asiento queda reservado cada semana.",
            color = Coo.TextoTenue, fontSize = 13.sp)
        val letras = listOf("L", "M", "M", "J", "V", "S", "D")
        val nombres = listOf("lunes", "martes", "miércoles", "jueves", "viernes", "sábado", "domingo")
        Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
            (1..7).forEach { d ->
                val disponible = d in posibles
                val activo = d in elegidos
                Box(
                    Modifier.size(40.dp).alpha(if (disponible) 1f else 0.35f).clip(CircleShape)
                        .background(if (activo) Coo.AzulBoton else Coo.CampoActivo)
                        .toggleable(value = activo, enabled = disponible, role = Role.Checkbox) { si ->
                            elegidos = (if (si) elegidos.toSet() + d else elegidos.toSet() - d).sorted().toIntArray()
                        }
                        .semantics { contentDescription = nombres[d - 1] + if (disponible) "" else ", el conductor no viaja" },
                    contentAlignment = Alignment.Center
                ) {
                    Text(letras[d - 1], color = if (activo) Coo.SobreBoton else Coo.Texto, fontWeight = FontWeight.Bold)
                }
            }
        }
        val puede = elegidos.isNotEmpty() && !enviando
        Text(
            if (enviando) "Enviando…" else "Solicitar viaje habitual",
            color = if (puede) Coo.SobreBoton else Coo.TextoTenue, fontSize = 16.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
                .background(if (puede) Coo.AzulBoton else Coo.CampoActivo)
                .clickable(enabled = puede) { alEnviar(elegidos.toSet()) }.padding(vertical = 15.dp)
        )
    }
}

// ------------------------------------------------------------------ tarjeta negra del acuerdo

private val NEGRO_HABITUAL = androidx.compose.ui.graphics.Color(0xFF121212)
private val BORDE_HABITUAL = androidx.compose.ui.graphics.Color(0xFF2A2A2A)

/** "pedro Ruiz escalante" → "Pedro Ruiz Escalante". */
internal fun nombrePropio(n: String) = n.trim().split(Regex("\\s+")).joinToString(" ") { p -> p.lowercase().replaceFirstChar { it.uppercase() } }

/** Círculo relleno con check (aceptado/terminado), aspa (rechazado) o reloj (en espera). */
@Composable
private fun IconoEstadoHabitual(estado: String, color: androidx.compose.ui.graphics.Color) =
    pe.cooruta.app.ui.componentes.IconoM(
        when (estado) {
            "aceptado", "terminado" -> Icons.Rounded.CheckCircle
            "rechazado" -> Icons.Rounded.Cancel
            else -> Icons.Rounded.Schedule
        }, color, 20.dp)

/** Íconos de línea: reloj y etiqueta de precio. */
@Composable
private fun IconoLinea(tipo: String, color: androidx.compose.ui.graphics.Color) =
    pe.cooruta.app.ui.componentes.IconoM(if (tipo == "reloj") Icons.Outlined.Schedule else Icons.Outlined.LocalOffer, color, 18.dp)
