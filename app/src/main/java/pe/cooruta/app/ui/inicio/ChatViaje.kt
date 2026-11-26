package pe.cooruta.app.ui.inicio

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Map
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.Place
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Smartphone
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.foundation.layout.size
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Spacer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
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
import pe.cooruta.app.data.repositorio.ChatRepositorio
import pe.cooruta.app.data.repositorio.Mensaje
import pe.cooruta.app.data.repositorio.RutasRepositorio.Solicitud
import pe.cooruta.app.ui.theme.Coo

data class EstadoChat(
    val reservaId: String? = null,
    val mensajes: List<Mensaje> = emptyList(),
    val error: String? = null,
    val enviando: Boolean = false,
    /** Mi celular guardado en Perfil (para enviarlo con un toque). */
    val miTelefono: String = ""
)

class ChatViewModel(
    private val repo: ChatRepositorio,
    private val perfiles: pe.cooruta.app.data.repositorio.PerfilesRepositorio
) : ViewModel() {
    private val _estado = MutableStateFlow(EstadoChat())
    val estado: StateFlow<EstadoChat> = _estado.asStateFlow()
    val miUid: String? get() = repo.miUid
    private var escucha: Job? = null

    fun abrir(reservaId: String) {
        if (_estado.value.reservaId == reservaId && escucha?.isActive == true) return
        escucha?.cancel()
        ChatAbierto.id = reservaId
        _estado.value = EstadoChat(reservaId = reservaId)
        viewModelScope.launch {
            (perfiles.leerTelefono() as? Resultado.Exito)?.let { r -> _estado.update { it.copy(miTelefono = r.valor) } }
        }
        escucha = viewModelScope.launch {
            repo.mensajesEnVivo(reservaId).collect { r ->
                when (r) {
                    is Resultado.Exito -> _estado.update { it.copy(mensajes = r.valor, error = null) }
                    is Resultado.Error -> _estado.update { it.copy(error = r.mensaje) }
                }
            }
        }
    }

    fun enviar(texto: String, alEnviado: () -> Unit) {
        val id = _estado.value.reservaId ?: return
        if (_estado.value.enviando || texto.isBlank()) return
        _estado.update { it.copy(enviando = true) }
        alEnviado() // el mensaje aparece al instante (escritura local); el error, si lo hay, se avisa abajo
        viewModelScope.launch {
            val r = repo.enviar(id, texto)
            _estado.update { it.copy(enviando = false, error = (r as? Resultado.Error)?.mensaje) }
        }
    }

    fun cerrar() {
        escucha?.cancel(); escucha = null
        if (ChatAbierto.id == _estado.value.reservaId) ChatAbierto.id = null
    }

    companion object {
        val Fabrica: ViewModelProvider.Factory = viewModelFactory { initializer { ChatViewModel(ChatRepositorio(), pe.cooruta.app.data.repositorio.PerfilesRepositorio()) } }
    }
}

/** Chat que la persona tiene abierto ahora: sus mensajes no generan aviso. */
object ChatAbierto { @Volatile var id: String? = null }

/** Mensaje recién llegado de otra persona. */
data class MensajeEntrante(val s: Solicitud, val otro: String, val texto: String)

/** Aviso emergente arriba de la pantalla; tocarlo abre el chat. Se va solo a los 6 s. */
@Composable
fun BannerMensaje(m: MensajeEntrante, alAbrir: () -> Unit, alCerrar: () -> Unit) {
    LaunchedEffect(m) { kotlinx.coroutines.delay(6_000); alCerrar() }
    androidx.compose.ui.window.Popup(alignment = Alignment.TopCenter, onDismissRequest = alCerrar) {
        Row(
            Modifier.statusBarsPadding().padding(horizontal = 12.dp, vertical = 8.dp).fillMaxWidth()
                .clip(RoundedCornerShape(16.dp)).background(Coo.CampoActivo)
                .border(1.dp, Coo.AzulUbicacion, RoundedCornerShape(16.dp))
                .clickable(onClick = alAbrir).padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text("Mensaje de ${m.otro}", color = Coo.Texto, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                Text(m.texto, color = Coo.TextoTenue, fontSize = 14.sp, maxLines = 2,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            }
            Text("Responder", color = Coo.AzulUbicacion, fontWeight = FontWeight.Bold, fontSize = 14.sp,
                modifier = Modifier.padding(start = 10.dp))
        }
    }
}

private val RAPIDOS = listOf("Ya estoy aquí", "Llego en 5 min", "Voy un poco tarde", "Gracias")

/** Enlace "Mensajes" para una reserva pendiente o aceptada de hoy en adelante. */
@Composable
fun BotonChat(s: Solicitud, comoConductor: Boolean) {
    if (s.estado != "pendiente" && s.estado != "aceptada") return
    val abierto = rememberSaveable(s.id) { mutableStateOf(false) }
    val otro = (if (comoConductor) s.nombrePasajero else s.nombreConductor).ifBlank { if (comoConductor) "Pasajero" else "Conductor" }
    Text(
        "Mensajes con $otro", color = Coo.AzulUbicacion, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp).clip(RoundedCornerShape(12.dp))
            .border(1.dp, Coo.Borde, RoundedCornerShape(12.dp)).clickable { abierto.value = true }.padding(vertical = 10.dp)
    )
    if (abierto.value) PantallaChat(s, otro, alCerrar = { abierto.value = false })
}

/** Ícono redondo de chat (globo con «…»): abre los mensajes de la reserva. */
@Composable
fun IconoChat(s: Solicitud, comoConductor: Boolean) {
    if (s.estado != "pendiente" && s.estado != "aceptada") return
    val abierto = rememberSaveable(s.id) { mutableStateOf(false) }
    val otro = (if (comoConductor) s.nombrePasajero else s.nombreConductor).ifBlank { if (comoConductor) "Pasajero" else "Conductor" }
    Box(
        Modifier.size(40.dp).clip(androidx.compose.foundation.shape.CircleShape)
            .border(1.dp, Coo.Borde, androidx.compose.foundation.shape.CircleShape)
            .clickable { abierto.value = true }
            .semantics { contentDescription = "Mensajes con $otro" },
        contentAlignment = Alignment.Center
    ) {
        pe.cooruta.app.ui.componentes.IconoM(Icons.Outlined.ChatBubbleOutline, Coo.AzulUbicacion, 20.dp)
    }
    if (abierto.value) PantallaChat(s, otro, alCerrar = { abierto.value = false })
}

@Composable
fun PantallaChat(
    s: Solicitud,
    otro: String,
    alCerrar: () -> Unit,
    vm: ChatViewModel = viewModel(key = "chat_${s.id}", factory = ChatViewModel.Fabrica)
) {
    val e by vm.estado.collectAsStateWithLifecycle()
    LaunchedEffect(s.id) { vm.abrir(s.id) }
    var texto by rememberSaveable { mutableStateOf("") }
    var avisoPrivacidad by rememberSaveable(s.id) { mutableStateOf(true) }
    var verRuta by rememberSaveable(s.id) { mutableStateOf(false) }
    val lista = rememberLazyListState()
    LaunchedEffect(e.mensajes.size) { if (e.mensajes.isNotEmpty()) lista.animateScrollToItem(e.mensajes.size - 1) }
    val abierto = s.fecha >= java.time.LocalDate.now().toString()
    val cerrar = { vm.cerrar(); alCerrar() }
    // Foto de la otra persona.
    val otroId = if (vm.miUid == s.conductorId) s.pasajeroId else s.conductorId
    val foto by androidx.compose.runtime.produceState<String?>(null, otroId) {
        if (otroId.isNotBlank()) value = runCatching {
            pe.cooruta.app.data.repositorio.PerfilesRepositorio().perfiles(setOf(otroId))[otroId]?.foto
        }.getOrNull()?.ifBlank { null }
    }
    if (verRuta) MapaSolicitud(s) { verRuta = false }

    Dialog(onDismissRequest = cerrar, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().background(Coo.Fondo)) {
            OlaInferior(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(140.dp))
            Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding()) {
                // ---------- encabezado ----------
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier.size(44.dp).clip(CircleShape).background(Coo.Campo).border(1.dp, Coo.Borde, CircleShape)
                            .clickable(onClick = cerrar).semantics { contentDescription = "Volver" },
                        contentAlignment = Alignment.Center
                    ) { pe.cooruta.app.ui.componentes.IconoAtras(tamano = 20.dp) }
                    Spacer(Modifier.width(12.dp))
                    Box(Modifier.size(46.dp).border(2.dp, Coo.AzulBoton, CircleShape).padding(3.dp)) { Avatar(foto, otro, 40.dp) }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(otro, color = Coo.Texto, fontWeight = FontWeight.Bold, fontSize = 17.sp, maxLines = 1,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                        Text(subtituloViaje(s), color = Coo.TextoTenue, fontSize = 13.sp)
                    }
                }
                // ---------- recorrido ----------
                Row(
                    Modifier.padding(horizontal = 12.dp).fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Coo.Campo)
                        .border(1.dp, Coo.Borde, RoundedCornerShape(18.dp)).padding(horizontal = 16.dp, vertical = 14.dp)
                        .height(androidx.compose.foundation.layout.IntrinsicSize.Min),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.fillMaxHeight().padding(vertical = 5.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(Modifier.size(11.dp).clip(CircleShape).background(Coo.AzulBoton))
                        Box(Modifier.width(2.dp).weight(1f).background(Coo.Borde))
                        Box(Modifier.size(11.dp).clip(CircleShape).background(Coo.RojoDestino))
                    }
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(s.subida, color = Coo.Texto, fontSize = 15.sp, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                        Text(s.bajada, color = Coo.Texto, fontSize = 15.sp, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                    }
                    Box(Modifier.padding(horizontal = 12.dp).width(1.dp).fillMaxHeight().background(Coo.Borde))
                    Row(
                        Modifier.clip(RoundedCornerShape(12.dp)).background(Coo.AzulBoton.copy(alpha = 0.16f))
                            .border(1.dp, Coo.AzulBoton.copy(alpha = 0.5f), RoundedCornerShape(12.dp))
                            .clickable { verRuta = true }.padding(horizontal = 12.dp, vertical = 9.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconoChatVista("mapa", Coo.AzulUbicacion, 18.dp)
                        Spacer(Modifier.width(6.dp))
                        Text("Ver ruta", color = Coo.AzulUbicacion, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    }
                }
                // ---------- aviso de privacidad (se puede cerrar) ----------
                if (avisoPrivacidad) Row(
                    Modifier.padding(start = 12.dp, end = 12.dp, top = 12.dp).fillMaxWidth().clip(RoundedCornerShape(16.dp))
                        .background(Coo.CampoActivo).padding(start = 14.dp, end = 4.dp, top = 12.dp, bottom = 12.dp),
                    verticalAlignment = Alignment.Top
                ) {
                    Box(Modifier.size(36.dp).clip(CircleShape).background(Coo.AzulBoton.copy(alpha = 0.18f)), contentAlignment = Alignment.Center) {
                        IconoChatVista("escudo", Coo.AzulUbicacion, 20.dp)
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Tu privacidad es importante", color = Coo.Texto, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        Text("No compartas datos de pago ni tu dirección exacta. El chat se cerrará después del día del viaje.",
                            color = Coo.TextoTenue, fontSize = 12.sp, lineHeight = 17.sp)
                    }
                    Box(
                        Modifier.size(36.dp).clip(CircleShape).clickable { avisoPrivacidad = false }
                            .semantics { contentDescription = "Cerrar aviso" },
                        contentAlignment = Alignment.Center
                    ) { pe.cooruta.app.ui.componentes.IconoCerrar(color = Coo.TextoTenue, tamano = 18.dp) }
                }
                // ---------- mensajes ----------
                LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = lista,
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (e.mensajes.isEmpty()) item { ChatVacio() }
                    items(e.mensajes, key = { it.id }) { m -> Burbuja(m, mio = m.autorId == vm.miUid) }
                }
                e.error?.let { Text(it, color = Coo.RojoError, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 16.dp)) }
                if (abierto) {
                    // ---------- mensajes rápidos ----------
                    Row(Modifier.fillMaxWidth().padding(horizontal = 28.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.weight(1f).height(1.dp).background(Coo.Borde))
                        Text("Mensajes rápidos", color = Coo.TextoTenue, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 12.dp))
                        Box(Modifier.weight(1f).height(1.dp).background(Coo.Borde))
                    }
                    Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        val rapidos = RAPIDOS.map { it to iconoRapido(it) } +
                            listOfNotNull(e.miTelefono.takeIf { it.isNotBlank() }?.let { "Mi celular: $it" to "telefono" })
                        rapidos.forEach { (r, icono) ->
                            Row(
                                Modifier.clip(RoundedCornerShape(50)).background(Coo.Campo)
                                    .border(1.dp, Coo.Borde, RoundedCornerShape(50))
                                    .clickable(enabled = !e.enviando) { vm.enviar(r) {} }
                                    .padding(horizontal = 14.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                IconoChatVista(icono, Coo.AzulUbicacion, 17.dp)
                                Spacer(Modifier.width(7.dp))
                                Text(r, color = Coo.Texto, fontSize = 13.sp)
                            }
                        }
                    }
                    // ---------- escribir ----------
                    Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 10.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier.weight(1f).heightIn(min = 52.dp).clip(RoundedCornerShape(18.dp)).background(Coo.Campo)
                                .border(1.dp, Coo.Borde, RoundedCornerShape(18.dp)).padding(horizontal = 16.dp, vertical = 14.dp),
                            contentAlignment = Alignment.CenterStart
                        ) {
                            if (texto.isEmpty()) Text("Escribe un mensaje…", color = Coo.TextoTenue, fontSize = 15.sp)
                            androidx.compose.foundation.text.BasicTextField(
                                value = texto, onValueChange = { texto = it.take(500) }, maxLines = 4,
                                textStyle = androidx.compose.ui.text.TextStyle(color = Coo.Texto, fontSize = 15.sp),
                                cursorBrush = androidx.compose.ui.graphics.SolidColor(Coo.AzulUbicacion),
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                        Spacer(Modifier.width(10.dp))
                        val puede = texto.isNotBlank() && !e.enviando
                        Box(
                            Modifier.size(52.dp).clip(RoundedCornerShape(16.dp))
                                .background(if (puede) Brush.linearGradient(listOf(Color(0xFF7189FF), Coo.AzulBoton, Color(0xFF3F55D9)))
                                    else androidx.compose.ui.graphics.SolidColor(Coo.CampoActivo))
                                .clickable(enabled = puede) { vm.enviar(texto) { texto = "" } }
                                .semantics { contentDescription = "Enviar mensaje" },
                            contentAlignment = Alignment.Center
                        ) { IconoChatVista("enviar", if (puede) Color.White else Coo.TextoTenue, 22.dp) }
                    }
                } else {
                    Text("Este chat ya está cerrado.", color = Coo.TextoTenue, textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(16.dp))
                }
            }
        }
    }
}

/** "Viaje del 3 nov · Sale 1:10 a. m." */
private fun subtituloViaje(s: Solicitud): String {
    val dia = runCatching {
        java.time.LocalDate.parse(s.fecha).format(java.time.format.DateTimeFormatter.ofPattern("d MMM", java.util.Locale("es", "PE")))
            .replace(".", "")
    }.getOrDefault(s.fecha)
    val hora = runCatching {
        val t = java.time.LocalTime.parse(s.horaSalida)
        val h = if (t.hour % 12 == 0) 12 else t.hour % 12
        "%d:%02d %s".format(h, t.minute, if (t.hour < 12) "a. m." else "p. m.")
    }.getOrDefault(s.horaSalida)
    return "Viaje del $dia · Sale $hora"
}

private fun iconoRapido(texto: String) = when (texto) {
    "Ya estoy aquí" -> "pin"; "Llego en 5 min" -> "cronometro"; "Voy un poco tarde" -> "reloj"; else -> "gracias"
}

/** Sin mensajes: dos globos de chat con brillo, título y ayuda. */
@Composable
private fun ChatVacio() {
    Column(Modifier.fillMaxWidth().padding(top = 36.dp, bottom = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        androidx.compose.foundation.Canvas(Modifier.size(width = 170.dp, height = 120.dp)) {
            val u = size.width / 170f
            // halo
            drawCircle(Brush.radialGradient(listOf(Coo.AzulBoton.copy(alpha = 0.28f), Color.Transparent),
                center = Offset(85f * u, 66f * u), radius = 80f * u), 80f * u, Offset(85f * u, 66f * u))
            // globo de atrás
            drawRoundRect(Coo.PlomoOscuro, Offset(38f * u, 50f * u), androidx.compose.ui.geometry.Size(64f * u, 42f * u),
                androidx.compose.ui.geometry.CornerRadius(12f * u))
            drawPath(Path().apply { moveTo(48f * u, 90f * u); lineTo(46f * u, 104f * u); lineTo(62f * u, 90f * u); close() }, Coo.PlomoOscuro)
            // globo de adelante
            val frente = Brush.linearGradient(listOf(Color(0xFF8FA0FF), Coo.AzulBoton), Offset(70f * u, 28f * u), Offset(140f * u, 84f * u))
            drawRoundRect(frente, Offset(66f * u, 26f * u), androidx.compose.ui.geometry.Size(76f * u, 52f * u),
                androidx.compose.ui.geometry.CornerRadius(14f * u))
            drawPath(Path().apply { moveTo(120f * u, 76f * u); lineTo(134f * u, 92f * u); lineTo(132f * u, 76f * u); close() }, Coo.AzulBoton)
            listOf(88f, 104f, 120f).forEach { x -> drawCircle(Coo.Fondo.copy(alpha = 0.75f), 4.5f * u, Offset(x * u, 52f * u)) }
            // destellos
            drawLine(Coo.AzulUbicacion, Offset(140f * u, 6f * u), Offset(144f * u, 18f * u), 3f * u, StrokeCap.Round)
            drawLine(Coo.AzulUbicacion, Offset(152f * u, 12f * u), Offset(146f * u, 22f * u), 3f * u, StrokeCap.Round)
            drawLine(Coo.AzulUbicacion, Offset(160f * u, 26f * u), Offset(150f * u, 28f * u), 3f * u, StrokeCap.Round)
        }
        Spacer(Modifier.height(18.dp))
        Text("Aún no hay mensajes", color = Coo.Texto, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(6.dp))
        Text("Coordina aquí el punto de encuentro, horario o cualquier detalle del viaje.",
            color = Coo.TextoTenue, fontSize = 15.sp, lineHeight = 21.sp, textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 24.dp))
    }
}

/** Ola tenue al pie, detrás de la caja de texto. */
@Composable
private fun OlaInferior(modifier: Modifier) {
    androidx.compose.foundation.Canvas(modifier) {
        val w = size.width; val h = size.height
        drawPath(Path().apply {
            moveTo(0f, h * 0.45f)
            cubicTo(w * 0.3f, h * 0.2f, w * 0.6f, h * 0.65f, w, h * 0.3f)
            lineTo(w, h); lineTo(0f, h); close()
        }, Brush.verticalGradient(listOf(Coo.AzulBoton.copy(alpha = 0.14f), Color.Transparent)))
    }
}

/** Íconos de línea de la pantalla de chat (cuadrícula de 24). */
@Composable
private fun IconoChatVista(tipo: String, color: Color, tamano: androidx.compose.ui.unit.Dp) {
    val icono = when (tipo) {
        "mapa" -> Icons.Outlined.Map
        "escudo" -> Icons.Outlined.Shield
        "pin" -> Icons.Outlined.Place
        "cronometro" -> Icons.Outlined.Timer
        "reloj" -> Icons.Outlined.Schedule
        "telefono" -> Icons.Outlined.Smartphone
        "enviar" -> Icons.AutoMirrored.Rounded.Send
        else -> Icons.Outlined.FavoriteBorder
    }
    pe.cooruta.app.ui.componentes.IconoM(icono, color, tamano)
}

@Composable
private fun Burbuja(m: Mensaje, mio: Boolean) {
    Box(Modifier.fillMaxWidth(), contentAlignment = if (mio) Alignment.CenterEnd else Alignment.CenterStart) {
        Column(
            Modifier.widthIn(max = 280.dp)
                .clip(RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp, bottomStart = if (mio) 18.dp else 6.dp, bottomEnd = if (mio) 6.dp else 18.dp))
                .background(
                    if (mio) Brush.linearGradient(listOf(Color(0xFF7189FF), Coo.AzulBoton))
                    else androidx.compose.ui.graphics.SolidColor(Coo.CampoActivo)
                )
                .padding(horizontal = 14.dp, vertical = 9.dp)
        ) {
            Text(m.texto, color = if (mio) Coo.SobreBoton else Coo.Texto, fontSize = 15.sp, lineHeight = 20.sp)
            Text(
                if (m.enviandoLocal) "Enviando…" else m.creadoMs?.let {
                    java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date(it))
                }.orEmpty(),
                color = if (mio) Coo.SobreBoton.copy(alpha = 0.75f) else Coo.TextoTenue, fontSize = 11.sp,
                modifier = Modifier.align(Alignment.End)
            )
        }
    }
}
