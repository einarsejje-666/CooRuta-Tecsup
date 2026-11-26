package pe.cooruta.app.ui.inicio

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Cancel
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.CalendarToday
import androidx.compose.material.icons.rounded.Place
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.rounded.Info
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
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import pe.cooruta.app.data.Resultado
import pe.cooruta.app.data.repositorio.RutasRepositorio
import pe.cooruta.app.data.repositorio.RutasRepositorio.Solicitud
import pe.cooruta.app.ui.horario.formatoSoles
import pe.cooruta.app.ui.theme.Coo

data class EstadoReservas(
    val cargando: Boolean = true,
    val error: String? = null,
    val todas: List<Solicitud> = emptyList(),
    val cancelando: String? = null
) {
    private val hoy get() = java.time.LocalDate.now().toString()
    val pendientes get() = todas.filter { (it.estado == "pendiente" || it.estado == "invitada") && it.fecha >= hoy }
    val confirmadas get() = todas.filter { it.estado == "aceptada" && it.fecha >= hoy }.sortedBy { it.fecha }
    val historial get() = todas - pendientes.toSet() - confirmadas.toSet()
}

/** Solicitudes y reservas del pasajero, leídas directo de `reservas` (no dependen de Buscar). */
class MisReservasViewModel(private val repo: RutasRepositorio) : ViewModel() {
    private val _estado = MutableStateFlow(EstadoReservas())
    val estado: StateFlow<EstadoReservas> = _estado.asStateFlow()
    private var escucha: Job? = null

    fun cargar() {
        _estado.update { it.copy(cargando = it.todas.isEmpty(), error = null) }
        escucha?.cancel()
        escucha = viewModelScope.launch {
            repo.misReservasEnVivo().collect { r ->
                when (r) {
                    is Resultado.Exito -> _estado.update { it.copy(cargando = false, todas = r.valor, error = null) }
                    is Resultado.Error -> _estado.update { it.copy(cargando = false, error = r.mensaje) }
                }
            }
        }
    }

    fun responderInvitacion(s: Solicitud, aceptar: Boolean, alAvisar: (String) -> Unit) {
        if (_estado.value.cancelando != null) return
        _estado.update { it.copy(cancelando = s.id) }
        viewModelScope.launch {
            val r = repo.responderInvitacion(s, aceptar)
            _estado.update { it.copy(cancelando = null) }
            alAvisar(when {
                r is Resultado.Error -> r.mensaje
                aceptar -> "Aceptaste. ${s.nombreConductor.substringBefore(" ").ifBlank { "El conductor" }} confirmará tu asiento."
                else -> "Declinaste la invitación."
            })
        }
    }

    fun cancelar(s: Solicitud, alAvisar: (String) -> Unit) {
        if (_estado.value.cancelando != null) return
        _estado.update { it.copy(cancelando = s.id) }
        viewModelScope.launch {
            val r = repo.cancelarSolicitud(s.id)
            _estado.update { it.copy(cancelando = null) }
            alAvisar(when {
                r is Resultado.Error -> r.mensaje
                s.estado == "aceptada" -> "Cancelaste tu reserva y se liberó el asiento."
                else -> "Cancelaste tu solicitud."
            })
        }
    }

    companion object {
        val Fabrica: ViewModelProvider.Factory = viewModelFactory {
            initializer { MisReservasViewModel(RutasRepositorio()) }
        }
    }
}

/** Pestaña Solicitudes del pasajero: pendientes, confirmadas e historial. */
@Composable
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
fun MisSolicitudesPestana(
    alAvisar: (String) -> Unit,
    modifier: Modifier = Modifier,
    vm: MisReservasViewModel = viewModel(factory = MisReservasViewModel.Fabrica)
) {
    val estado by vm.estado.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { vm.cargar() }
    var seccionReservas by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableIntStateOf(-1) }
    val ctxHist = androidx.compose.ui.platform.LocalContext.current
    LaunchedEffect(Unit) { pe.cooruta.app.data.HistorialOculto.cargar(ctxHist) }
    val ocultos by pe.cooruta.app.data.HistorialOculto.ids.collectAsStateWithLifecycle()
    val hayHabituales = hayHabitualesPasajero()
    // Acuerdos habituales repartidos: en espera → Pendientes, activos → Confirmadas, rechazados/terminados → Rechazadas.
    val habList = habitualesDelPasajero()
    val habPendientes = habList.count { it.estado == "pendiente" }
    val habActivos = habList.count { it.estado == "aceptado" }
    val habCerradosIds = habList.filter { it.estado in CERRADOS && it.id !in (ocultos ?: emptySet()) }.map { it.id }
    val habCerrados = habCerradosIds.size
    when {
        estado.cargando -> Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = Coo.AzulUbicacion)
        }
        estado.error != null && estado.todas.isEmpty() -> MensajeReservas(modifier, "No pudimos cargar tus solicitudes", "${estado.error} Toca para reintentar.", vm::cargar)
        estado.todas.isEmpty() && !hayHabituales -> MensajeReservas(modifier, "Aún no tienes solicitudes", "Cuando pidas un asiento en Buscar, lo verás aquí con su estado.", null)
        else -> LazyColumn(
            modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            estado.error?.let { e -> item { Text(e, color = Coo.RojoError, fontSize = 13.sp) } }
            // Secciones: Pendientes | Confirmadas | Rechazadas (una a la vez).
            val actual = (if (seccionReservas >= 0) seccionReservas
                else if (estado.pendientes.isNotEmpty() || habPendientes > 0 || (estado.confirmadas.isEmpty() && habActivos == 0)) 0 else 1)
                .coerceAtMost(2)
            // Fijas arriba al desplazar (igual que en la vista del conductor).
            stickyHeader(key = "pestanas") {
                Box(Modifier.fillMaxWidth().background(Coo.Fondo)) {
                    PestanasSolicitudes(listOf("Pendientes" to estado.pendientes.size + habPendientes,
                        "Confirmadas" to estado.confirmadas.size,
                        "Rechazadas" to estado.historial.count { it.id !in (ocultos ?: emptySet()) } + habCerrados), actual) { seccionReservas = it }
                }
            }
            val historial = estado.historial.filter { it.id !in (ocultos ?: emptySet()) }
            val lista = when (actual) { 0 -> estado.pendientes; 1 -> estado.confirmadas; else -> historial }

            // Rechazadas: «Limpiar todo» arriba (solo oculta en este teléfono).
            if (actual == 2 && historial.size + habCerrados > 0) item {
                BotonLimpiar(historial.size + habCerrados) {
                    pe.cooruta.app.data.HistorialOculto.ocultar(ctxHist, historial.map { it.id } + habCerradosIds)
                }
            }
            // Acuerdos habituales de esta pestaña, arriba de las reservas de cada fecha.
            val habituales = when (actual) { 0 -> habPendientes; 1 -> habActivos; else -> habCerrados }
            if (habituales > 0) item {
                HabitualesPasajero(
                    alAvisar,
                    estados = when (actual) { 0 -> setOf("pendiente"); 1 -> setOf("aceptado"); else -> setOf("rechazado", "terminado") },
                    titulo = when (actual) { 0 -> "Viajes habituales por confirmar"; 1 -> "Viajes habituales"; else -> "Viajes habituales cerrados" }
                )
            }
            if (habituales > 0 && lista.isNotEmpty()) item {
                Text(if (actual == 1) "Próximos viajes" else "Solicitudes de asiento", color = Coo.TextoTenue, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            }
            if (lista.isEmpty() && habituales == 0) item {
                when (actual) {
                    0 -> VacioSolicitudes("📭", "Sin solicitudes en espera", "Pide un asiento desde Buscar y lo verás aquí.")
                    1 -> VacioSolicitudes("🚗", "Aún no tienes viajes confirmados", "Cuando un conductor acepte tu solicitud, aparecerá aquí.")
                    else -> VacioSolicitudes("🗂️", "Nada en el historial", "Aquí verás las solicitudes rechazadas o canceladas.")
                }
            }
            items(lista, key = { "${actual}_${it.id}" }) { s ->
                // Deslizar a un lado: Rechazadas → quitar del historial; Pendientes/Confirmadas → cancelar (pregunta antes).
                val confirmacion = when {
                    actual == 2 -> null
                    s.estado == "invitada" -> pe.cooruta.app.ui.componentes.Confirmacion(
                        "¿Rechazar la invitación?", "${s.nombreConductor.ifBlank { "El conductor" }} sabrá que no viajarás.", "Rechazar")
                    actual == 1 -> pe.cooruta.app.ui.componentes.Confirmacion(
                        "¿Cancelar esta reserva?", "Se liberará tu asiento y se avisará al conductor.", "Cancelar reserva")
                    else -> pe.cooruta.app.ui.componentes.Confirmacion(
                        "¿Cancelar esta solicitud?", "El conductor ya no la verá.", "Cancelar solicitud")
                }
                pe.cooruta.app.ui.componentes.DeslizableParaBorrar(
                    alBorrar = {
                        when {
                            actual == 2 -> pe.cooruta.app.data.HistorialOculto.ocultar(ctxHist, listOf(s.id))
                            s.estado == "invitada" -> vm.responderInvitacion(s, false, alAvisar)
                            else -> vm.cancelar(s, alAvisar)
                        }
                    },
                    confirmacion = confirmacion,
                    habilitado = actual == 2 || estado.cancelando == null
                ) {
                    if (actual == 2) TarjetaReserva(s, alAvisar = alAvisar)
                    else TarjetaReserva(s, cancelando = estado.cancelando == s.id, alCancelar = { vm.cancelar(s, alAvisar) },
                        alResponderInvitacion = { ok -> vm.responderInvitacion(s, ok, alAvisar) })
                }
            }
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.seccion(
    titulo: String, lista: List<Solicitud>, tarjeta: @Composable (Solicitud) -> Unit
) {
    if (lista.isEmpty()) return
    item { Text("$titulo · ${lista.size}", color = Coo.TextoTenue, fontSize = 13.sp, fontWeight = FontWeight.Bold) }
    items(lista, key = { "${titulo}_${it.id}" }) { tarjeta(it) }
}

/** Reservas confirmadas del pasajero, para la parte superior de Mis viajes. */
@Composable
fun ReservasConfirmadas(vm: MisReservasViewModel = viewModel(factory = MisReservasViewModel.Fabrica)) {
    val estado by vm.estado.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { vm.cargar() }
    if (estado.confirmadas.isEmpty() && estado.error == null) return
    Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Reservas confirmadas", color = Coo.TextoTenue, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        estado.error?.let { Text(it, color = Coo.RojoError, fontSize = 13.sp) }
        estado.confirmadas.forEach { TarjetaReserva(it) }
    }
}

@Composable
fun TarjetaReserva(s: Solicitud, cancelando: Boolean = false, alCancelar: (() -> Unit)? = null, alAvisar: ((String) -> Unit)? = null,
                   alResponderInvitacion: ((Boolean) -> Unit)? = null) {
    val (etiqueta, color) = when (s.estado) {
        "invitada" -> "${s.nombreConductor.substringBefore(" ").ifBlank { "El conductor" }} te invita de nuevo" to Coo.AzulUbicacion
        "aceptada" -> "Asiento confirmado" to Coo.Exito
        "rechazada" -> "Rechazada" to Coo.RojoError
        "cancelada" -> "Cancelada" to Coo.TextoTenue
        else -> "En espera de confirmación" to Coo.AzulUbicacion
    }
    val activa = s.estado == "pendiente" || s.estado == "aceptada"
    var verChat by remember(s.id) { mutableStateOf(false) }
    if (verChat) PantallaChat(s, s.nombreConductor.ifBlank { "Conductor" }, alCerrar = { verChat = false })
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(NEGRO_RESERVA)
            .border(1.dp, BORDE_RESERVA, RoundedCornerShape(22.dp)).padding(16.dp)
    ) {
        var verPerfil by remember(s.id) { mutableStateOf(false) }
        if (verPerfil) PerfilConductor(s.conductorId, alAvisar ?: {}, alCerrar = { verPerfil = false })
        // ---------- conductor, estado y pasaje ----------
        Row(verticalAlignment = Alignment.CenterVertically) {
            Row(verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).clickable(enabled = s.conductorId.isNotBlank()) { verPerfil = true }) {
                // Foto del conductor (si no tiene, Avatar pone su inicial).
                val foto by androidx.compose.runtime.produceState<String?>(null, s.conductorId) {
                    if (s.conductorId.isNotBlank()) value = runCatching {
                        pe.cooruta.app.data.repositorio.PerfilesRepositorio().perfiles(setOf(s.conductorId))[s.conductorId]?.foto
                    }.getOrNull()?.ifBlank { null }
                }
                Box(Modifier.size(58.dp).border(2.dp, Coo.AzulBoton.copy(alpha = 0.6f), androidx.compose.foundation.shape.CircleShape).padding(3.dp)) {
                    Avatar(foto, s.nombreConductor, 52.dp)
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(nombrePropio(s.nombreConductor.ifBlank { "Conductor" }), color = Coo.Texto, fontSize = 18.sp, fontWeight = FontWeight.Bold,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.height(5.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconoReserva(if (s.estado == "aceptada") "check" else if (s.estado == "rechazada" || s.estado == "cancelada") "aspa" else "reloj", color, 16.dp)
                        Spacer(Modifier.width(6.dp))
                        Text(etiqueta, color = color, fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            Box(Modifier.padding(horizontal = 12.dp).width(1.dp).height(44.dp).background(BORDE_RESERVA))
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(formatoSoles(s.pasajeCentimos), color = Coo.Texto, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                Text("por viaje", color = Coo.TextoTenue, fontSize = 12.sp)
            }
        }
        Spacer(Modifier.height(14.dp))
        // ---------- fecha y hora ----------
        Row(Modifier.height(androidx.compose.foundation.layout.IntrinsicSize.Min), verticalAlignment = Alignment.CenterVertically) {
            IconoReserva("calendario", Coo.TextoTenue, 18.dp)
            Spacer(Modifier.width(8.dp))
            Text(fechaLegible(s.fecha).replaceFirstChar { it.uppercase() }, color = Coo.Texto, fontSize = 14.sp)
            Box(Modifier.padding(horizontal = 12.dp).width(1.dp).fillMaxHeight().background(BORDE_RESERVA))
            IconoReserva("reloj", Coo.TextoTenue, 18.dp)
            Spacer(Modifier.width(8.dp))
            Text(
                when {
                    s.horaRecogida.isNotBlank() -> "Te recoge hacia las ${horaAmPmReserva(s.horaRecogida)}"
                    s.horaSalida.isNotBlank() -> "Sale a las ${horaAmPmReserva(s.horaSalida)}"
                    else -> "Hora por confirmar"
                },
                color = Coo.TextoTenue, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis
            )
        }
        Box(Modifier.padding(vertical = 12.dp).fillMaxWidth().height(1.dp).background(BORDE_RESERVA))
        // ---------- subida y bajada ----------
        Row(Modifier.height(androidx.compose.foundation.layout.IntrinsicSize.Min)) {
            Column(Modifier.width(20.dp).fillMaxHeight().padding(top = 4.dp, bottom = 2.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.size(12.dp).clip(androidx.compose.foundation.shape.CircleShape).background(Coo.AzulBoton))
                IconoReserva("guiones", Coo.AzulBoton.copy(alpha = 0.7f), 0.dp, Modifier.width(2.dp).weight(1f).padding(vertical = 3.dp))
                IconoReserva("pin", Coo.RojoDestino, 18.dp)
            }
            Spacer(Modifier.width(12.dp))
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text("Subes en ${s.subida}", color = Coo.Texto, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("Bajas en ${s.bajada}", color = Coo.Texto, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        // Historial: viajes pasados ya se pueden calificar.
        if (alAvisar != null) BotonCalificar(s, comoConductor = false, alAvisar = alAvisar)
        if (s.estado == "invitada" && alResponderInvitacion != null) {
            Row(Modifier.fillMaxWidth().padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                androidx.compose.material3.OutlinedButton(onClick = { alResponderInvitacion(false) }, enabled = !cancelando,
                    modifier = Modifier.weight(1f)) { Text("No, gracias", color = Coo.TextoTenue) }
                androidx.compose.material3.Button(onClick = { alResponderInvitacion(true) }, enabled = !cancelando,
                    modifier = Modifier.weight(1f),
                    colors = androidx.compose.material3.ButtonDefaults.buttonColors(containerColor = Coo.AzulBoton, contentColor = Coo.SobreBoton)
                ) { Text(if (cancelando) "…" else "Aceptar", fontWeight = FontWeight.Bold) }
            }
        }
        // ---------- Mensaje · Cancelar ----------
        if (alCancelar != null && activa) {
            // Una reserva confirmada pide un segundo toque antes de liberar el asiento.
            var confirmar by remember(s.id, s.estado) { mutableStateOf(false) }
            Row(Modifier.fillMaxWidth().padding(top = 12.dp).height(androidx.compose.foundation.layout.IntrinsicSize.Min),
                verticalAlignment = Alignment.CenterVertically) {
                Row(
                    Modifier.weight(1f).height(48.dp).clip(RoundedCornerShape(12.dp))
                        .clickable { verChat = true },
                    horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically
                ) {
                    IconoReserva("chat", Coo.AzulUbicacion, 22.dp)
                    Spacer(Modifier.width(8.dp))
                    Text("Mensaje", color = Coo.AzulUbicacion, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                }
                Box(Modifier.width(1.dp).height(28.dp).background(BORDE_RESERVA))
                Row(
                    Modifier.weight(1f).height(48.dp).clip(RoundedCornerShape(12.dp))
                        .background(Coo.RojoDestino.copy(alpha = if (confirmar) 0.16f else 0f))
                        .clickable(enabled = !cancelando) {
                            if (s.estado == "aceptada" && !confirmar) confirmar = true else alCancelar()
                        },
                    horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically
                ) {
                    pe.cooruta.app.ui.componentes.IconoBasurero(Coo.RojoError, 18.dp)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        when {
                            cancelando -> "Cancelando…"
                            confirmar -> "Toca otra vez"
                            s.estado == "pendiente" -> "Cancelar solicitud"
                            else -> "Cancelar reserva"
                        },
                        color = Coo.RojoError, fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
                }
            }
            if (confirmar) Text("Se liberará tu asiento y se avisará al conductor.", color = Coo.TextoTenue, fontSize = 12.sp,
                modifier = Modifier.padding(top = 6.dp).align(Alignment.End))
        }
    }
}

// ------------------------------------------------------------------ tarjeta negra de reserva

private val NEGRO_RESERVA = androidx.compose.ui.graphics.Color(0xFF121212)
private val BORDE_RESERVA = androidx.compose.ui.graphics.Color(0xFF2A2A2A)

/** "01:11" → "1:11 a. m.". */
private fun horaAmPmReserva(hhmm: String): String = runCatching {
    val t = java.time.LocalTime.parse(hhmm)
    val h = if (t.hour % 12 == 0) 12 else t.hour % 12
    "%d:%02d %s".format(h, t.minute, if (t.hour < 12) "a. m." else "p. m.")
}.getOrDefault(hhmm)

/** Íconos de la tarjeta (cuadrícula de 24): check, aspa, reloj, calendario, pin, chat y línea punteada. */
@Composable
private fun IconoReserva(tipo: String, color: androidx.compose.ui.graphics.Color, tamano: androidx.compose.ui.unit.Dp,
                         modifier: Modifier = Modifier.size(tamano)) {
    if (tipo == "guiones") {
        androidx.compose.foundation.Canvas(modifier) {
            drawLine(color, androidx.compose.ui.geometry.Offset(size.width / 2, 0f),
                androidx.compose.ui.geometry.Offset(size.width / 2, size.height), size.width, androidx.compose.ui.graphics.StrokeCap.Round,
                androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(size.width * 3, size.width * 3)))
        }
        return
    }
    val icono = when (tipo) {
        "check" -> Icons.Rounded.CheckCircle
        "aspa" -> Icons.Rounded.Cancel
        "reloj" -> Icons.Outlined.Schedule
        "calendario" -> Icons.Outlined.CalendarToday
        "pin" -> Icons.Rounded.Place
        "chat" -> Icons.Outlined.ChatBubbleOutline
        else -> Icons.Rounded.Info
    }
    pe.cooruta.app.ui.componentes.IconoM(icono, color, tamano)
}

@Composable
private fun MensajeReservas(modifier: Modifier, titulo: String, texto: String, alPulsar: (() -> Unit)?) {
    Column(
        modifier.fillMaxSize().then(if (alPulsar != null) Modifier.clickable(onClick = alPulsar) else Modifier).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(titulo, color = Coo.Texto, fontSize = 20.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        Text(texto, color = Coo.TextoTenue, textAlign = TextAlign.Center)
    }
}
