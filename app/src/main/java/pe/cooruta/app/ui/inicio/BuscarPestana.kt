package pe.cooruta.app.ui.inicio

import androidx.compose.material.icons.outlined.Place
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.LocalOffer
import androidx.compose.material.icons.outlined.AccountBalanceWallet
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CalendarToday
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Directions
import androidx.compose.material.icons.outlined.Map
import androidx.compose.material.icons.rounded.Place
import androidx.compose.material.icons.automirrored.rounded.DirectionsWalk
import androidx.compose.material.icons.outlined.DirectionsCar
import androidx.compose.material.icons.outlined.Payments
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.rounded.EventSeat
import androidx.compose.material.icons.rounded.VerifiedUser
import androidx.compose.material.icons.rounded.ThumbUp
import androidx.compose.material.icons.rounded.Info
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import pe.cooruta.app.data.Punto
import pe.cooruta.app.data.repositorio.PerfilPublico
import pe.cooruta.app.data.Coincidencia
import pe.cooruta.app.ui.componentes.IconoAsiento
import pe.cooruta.app.ui.componentes.IconoBuscar
import pe.cooruta.app.ui.componentes.IconoCerrar
import pe.cooruta.app.ui.componentes.IconoReloj
import pe.cooruta.app.ui.horario.formatoHora
import pe.cooruta.app.ui.horario.formatoSoles
import pe.cooruta.app.ui.horario.resumenDias
import pe.cooruta.app.ui.theme.Coo

/**
 * Pestaña Buscar del pasajero: rutas que coinciden con su trayecto guardado,
 * ordenadas por coincidencia, con asientos libres en tiempo real y pasaje
 * calculado según dónde sube y dónde baja.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun BuscarPestana(
    alAvisar: (String) -> Unit,
    alGuardarTrayecto: () -> Unit,
    /** Trayecto elegido en Mis viajes con «Usar esta ruta hoy». */
    usarHoyId: String? = null,
    modifier: Modifier = Modifier,
    vm: BuscarViewModel = viewModel(factory = BuscarViewModel.Fabrica)
) {
    val estado by vm.estado.collectAsStateWithLifecycle()
    val ia by vm.estadoIa.collectAsStateWithLifecycle()
    val empresaVm: EmpresaViewModel = viewModel(factory = EmpresaViewModel.Fabrica)
    // Mis conductores: salen primero, con su filtro y su perfil.
    val conductoresVm: ConductoresViewModel = viewModel(factory = ConductoresViewModel.Fabrica)
    val conductores by conductoresVm.estado.collectAsStateWithLifecycle()
    LaunchedEffect(conductores.ids) { vm.actualizarFavoritos(conductores.ids) }
    // Tras unirse a un grupo por invitación, sus rutas privadas aparecen sin recargar a mano.
    val cambiosGrupo by pe.cooruta.app.data.InvitacionPendiente.cambios.collectAsStateWithLifecycle()
    LaunchedEffect(cambiosGrupo) { if (cambiosGrupo > 0) vm.cargarGrupos() }
    var perfilAbierto by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<String?>(null) }
    perfilAbierto?.let { PerfilConductor(it, alAvisar, alCerrar = { perfilAbierto = null }, vm = conductoresVm) }
    val empresa by empresaVm.estado.collectAsStateWithLifecycle()
    LaunchedEffect(empresa, estado.miUid) { vm.actualizarEmpresa(empresa) }
    // Al volver a Buscar (por ejemplo desde Perfil) se relee, así la empresa recién guardada cuenta.
    LaunchedEffect(Unit) { vm.cargar() }
    // Al elegir un destino: se pide la ubicación actual (una vez) para calcular la ruta.
    val ctxUbic = androidx.compose.ui.platform.LocalContext.current
    var pendiente by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<pe.cooruta.app.data.Sugerencia?>(null) }
    // Permiso y, si el GPS está apagado, la ventana de Android para activarlo.
    val pedidoUbicacion = pe.cooruta.app.ui.componentes.rememberPedidoUbicacion()
    var enfocado by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    val foco = androidx.compose.ui.platform.LocalFocusManager.current
    // Atrás cierra las búsquedas recientes en vez de salir.
    androidx.activity.compose.BackHandler(enabled = enfocado) { foco.clearFocus() }
    fun elegirReciente(l: pe.cooruta.app.data.Lugar) {
        foco.clearFocus()
        vm.elegirReciente(l, ubicacionActual(ctxUbic))
    }
    fun elegirConUbicacion(s: pe.cooruta.app.data.Sugerencia) {
        val p = ubicacionActual(ctxUbic)
        if (p != null || (tienePermisoUbicacion(ctxUbic) && pe.cooruta.app.ui.componentes.ubicacionActivada(ctxUbic))) vm.elegirSugerencia(s, p)
        else {
            pendiente = s
            pedidoUbicacion.pedir { pendiente?.let { vm.elegirSugerencia(it, ubicacionActual(ctxUbic)) }; pendiente = null }
        }
    }
    // Ubicación en tiempo real: si te mueves, la ruta hasta tu destino se recalcula sola.
    val enVivo by pe.cooruta.app.data.UbicacionEnVivo.actual.collectAsStateWithLifecycle()
    LaunchedEffect(enVivo) { enVivo?.let(vm::actualizarOrigen) }
    LaunchedEffect(usarHoyId) { usarHoyId?.substringBefore("|")?.let(vm::usarHoy) }
    // Las rutas donde el conductor ya rechazó van al final.
    val lista = estado.coincidencias.sortedBy { estado.solicitudes[it.ruta.id] == "rechazada" }
    var elegida by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf<String?>(null) }

    lista.firstOrNull { it.ruta.id == elegida }?.let { c ->
        androidx.compose.material3.ModalBottomSheet(
            onDismissRequest = { elegida = null },
            containerColor = Coo.Campo,
            sheetState = androidx.compose.material3.rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ) {
            HojaSolicitud(
                c = c,
                subida = estado.nombres[clave(c.subida)],
                bajada = estado.nombreBajada(c),
                estadoSolicitud = estado.solicitudes[c.ruta.id],
                motivoSolicitud = estado.motivosSolicitud[c.ruta.id],
                bajadaGuardada = estado.bajadasSolicitud[c.ruta.id],
                perfil = estado.perfiles[c.ruta.conductorId],
                favorito = c.ruta.conductorId in conductores.ids,
                alFavorito = { conductoresVm.alternar(c.ruta.conductorId, alAvisar) },
                alVerPerfil = { perfilAbierto = c.ruta.conductorId },
                enviando = estado.enviando == c.ruta.id,
                alSolicitar = { msg -> vm.solicitar(c, msg, alAvisar) },
                alCancelar = { vm.cancelarSolicitud(c.ruta.id, alAvisar) },
                enviandoHabitual = estado.enviando == "hab_${c.ruta.id}",
                alSolicitarHabitual = { dias, msg -> vm.solicitarHabitual(c, dias, msg, alAvisar) }
            )
        }
    }

    Box(modifier.fillMaxSize()) {
    Column(Modifier.fillMaxSize()) {
        Buscador(estado.texto, vm::escribir, Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) { enfocado = it }
        if (estado.lugarBuscado != null) Text(
            if (estado.trayectoRapido != null) "Desde tu ubicación hasta ${estado.lugarBuscado?.nombre}"
            else "Rutas que pasan cerca de: ${estado.lugarBuscado?.nombre} (activa tu ubicación para ver coincidencias)",
            color = Coo.TextoTenue, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 20.dp))

        // Sin filtro «Mi empresa» por ahora (pocos usuarios): los compañeros ya salen primero
        // y su tarjeta dice «Pertenece a …». El filtro sigue en el ViewModel para volver a usarlo.

        val t = estado.trayectoGuardado
        if (estado.soloHoy && t != null && estado.trayectoRapido == null) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp).fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp)).background(Coo.Campo).border(1.dp, Coo.AzulUbicacion, RoundedCornerShape(16.dp)).padding(12.dp)
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Hoy · ${formatoHora(t.salida)}", color = Coo.AzulUbicacion, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    Text(t.alias.ifBlank { "${t.origen} → ${t.destino}" }, color = Coo.Texto, fontSize = 14.sp,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(if (estado.avisadoHoy) "Los conductores de tu camino ya te ven" else "Avisando a los conductores…",
                        color = Coo.Texto.copy(alpha = 0.75f), fontSize = 12.sp)
                }
                IconoCerrar(color = Coo.Texto, tamano = 20.dp,
                    modifier = Modifier.clip(CircleShape).clickable(onClick = vm::quitarHoy))
            }
        } else if (estado.misTrayectos.size > 1) {
            Row(
                Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 20.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                estado.misTrayectos.forEach { t ->
                    val activo = t.id == estado.trayectoId
                    Text(
                        t.alias.ifBlank { "${t.origen.take(18)} → ${t.destino.take(18)}" },
                        color = if (activo) Coo.SobreBoton else Coo.Texto, fontSize = 13.sp,
                        modifier = Modifier.clip(RoundedCornerShape(50))
                            .background(if (activo) Coo.AzulBoton else Coo.Campo)
                            .clickable { vm.elegirTrayecto(t.id) }
                            .padding(horizontal = 12.dp, vertical = 7.dp)
                    )
                }
            }
        }

        androidx.compose.material3.pulltorefresh.PullToRefreshBox(
            isRefreshing = estado.refrescando,
            onRefresh = vm::refrescar,
            modifier = Modifier.fillMaxSize()
        ) {
        when {
            estado.cargando -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = Coo.AzulUbicacion)
            }
            estado.error != null -> Aviso(estado.error.orEmpty(), "Toca para reintentar", vm::cargar)
            // Al tocar el buscador vacío: búsquedas recientes.
            // Lista de lugares como en el mapa; al elegir uno se muestran las rutas que pasan cerca.
            estado.texto.isNotBlank() && estado.lugarBuscado == null -> androidx.compose.foundation.lazy.LazyColumn(Modifier.fillMaxSize()) {
                if (estado.sugerencias.isEmpty()) item {
                    Text(if (estado.buscandoLugar) "Buscando…" else "No encontramos ese lugar.", color = Coo.TextoTenue,
                        fontSize = 13.sp, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
                }
                items(estado.sugerencias.size) { i ->
                    val s = estado.sugerencias[i]
                    Column(Modifier.fillMaxWidth().clickable { elegirConUbicacion(s) }.padding(horizontal = 20.dp, vertical = 12.dp)) {
                        Text(s.nombre, color = Coo.Texto, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(listOfNotNull(s.categoria, s.detalle.takeIf { it.isNotBlank() }).joinToString(" · "),
                            color = Coo.TextoTenue, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            estado.soloEmpresa && !empresa.disponible && !estado.tengoEmpresa -> Aviso(
                "No podemos verificar tu comunidad",
                empresa.error ?: "Revisa tu vinculación en Perfil → Mi empresa, o elige Todos.", empresaVm::actualizar)
            // Buscar un lugar funciona aunque no haya trayecto guardado.
            estado.lugarBuscado != null && (estado.trayecto == null || lista.isEmpty()) ->
                RutasCercanas(estado.rutasCercaDelLugar, estado.lugarBuscado?.nombre.orEmpty(), estado.trayecto == null, alGuardarTrayecto)
            estado.trayecto == null -> Aviso(
                "Guarda tu trayecto para ver conductores",
                "",
                alGuardarTrayecto
            )
            lista.isEmpty() -> Column(Modifier.fillMaxSize()) {
                Box(Modifier.weight(1f)) {
                    Aviso(
                        if (estado.soloEmpresa) "No hay compañeros con un recorrido compatible" else if (estado.texto.isBlank()) "Aún no hay conductores en tu camino" else "Sin rutas para \"${estado.texto}\"",
                        "Se actualiza solo cuando un conductor publica una ruta.", null
                    )
                }
            }
            else -> LazyColumn(
                contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 96.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                item {
                    Text("${lista.size} ${if (lista.size == 1) "ruta coincide" else "rutas coinciden"} con tu trayecto · en vivo",
                        color = Coo.TextoTenue, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    estado.errorSolicitudes?.let { Text(it, color = Coo.RojoError, fontSize = 13.sp) }
                    // IA pausada: no se muestra ningún aviso.
                }
                itemsIndexed(lista, key = { _, c -> c.ruta.id }) { i, c ->
                    Casilla(
                        c, esMia = c.ruta.conductorId == estado.miUid && !c.ruta.prueba,
                        nombreSubida = estado.nombres[clave(c.subida)],
                        nombreBajada = estado.nombreBajada(c),
                        estadoSolicitud = estado.solicitudes[c.ruta.id],
                motivoSolicitud = estado.motivosSolicitud[c.ruta.id],
                        perfil = estado.perfiles[c.ruta.conductorId],
                        comunidad = estado.comunidadDe(c.ruta.conductorId),
                        favorito = c.ruta.conductorId in conductores.ids,
                        alPulsar = { elegida = c.ruta.id },
                        explicacion = null,
                        miOrigen = estado.trayecto?.origenPunto,
                        miDestino = estado.trayecto?.destinoPunto,
                        alVerPerfil = { perfilAbierto = c.ruta.conductorId }
                    )
                }
            }
        }
        }
    }
    // Búsquedas recientes: ventana translúcida justo bajo el buscador, encima de los filtros.
    if (enfocado && estado.texto.isBlank() && estado.recientes.isNotEmpty())
        Box(Modifier.padding(top = 68.dp)) {
            Box(
                // Tocar fuera de la ventana la cierra.
                Modifier.fillMaxSize().clickable(
                    interactionSource = androidx.compose.runtime.remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                    indication = null
                ) { foco.clearFocus() }
            ) {
                androidx.compose.animation.AnimatedVisibility(
                    visibleState = androidx.compose.runtime.remember { androidx.compose.animation.core.MutableTransitionState(false).apply { targetState = true } },
                    enter = androidx.compose.animation.fadeIn()
                ) {
                // Ventana translúcida que se funde con el fondo.
                Column(
                    Modifier.padding(horizontal = 20.dp, vertical = 4.dp).fillMaxWidth()
                        .clip(RoundedCornerShape(20.dp))
                        .background(Coo.Campo.copy(alpha = 0.92f))
                        .padding(vertical = 8.dp)
                ) {
                    Text("Búsquedas recientes", color = Coo.TextoTenue, fontSize = 12.sp, fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 18.dp, vertical = 8.dp))
                    estado.recientes.forEach { l ->
                        Row(Modifier.fillMaxWidth().clickable { elegirReciente(l) }.padding(horizontal = 18.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(34.dp).clip(CircleShape).background(Coo.Borde.copy(alpha = 0.6f)), contentAlignment = Alignment.Center) {
                                pe.cooruta.app.ui.componentes.IconoReloj(color = Coo.AzulUbicacion, tamano = 18.dp)
                            }
                            Spacer(Modifier.width(12.dp))
                            Column {
                                Text(l.nombre, color = Coo.Texto, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                if (l.detalle.isNotBlank()) Text(l.detalle, color = Coo.TextoTenue, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
                }
            }
        }
    }
}

/** Estado de las explicaciones: si fallan, Buscar sigue funcionando y se puede reintentar. */
@Composable
private fun EstadoIa(ia: EstadoExplicaciones, alReintentar: () -> Unit) {
    when (ia) {
        EstadoExplicaciones.Cargando -> Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.size(14.dp), color = Coo.AzulUbicacion, strokeWidth = 2.dp)
            Spacer(Modifier.width(8.dp))
            Text("La IA está explicando tus mejores opciones…", color = Coo.TextoTenue, fontSize = 13.sp)
        }
        is EstadoExplicaciones.Error -> Text(
            "${ia.mensaje} Toca para reintentar.", color = Coo.RojoError, fontSize = 13.sp,
            modifier = Modifier.padding(top = 6.dp).clickable(onClick = alReintentar)
        )
        else -> Unit
    }
}

@Composable
private fun Aviso(titulo: String, @Suppress("UNUSED_PARAMETER") texto: String, alPulsar: (() -> Unit)?) {
    // Mismo estilo que los demás vacíos: ícono de líneas finas sin color y un título (sin submensaje).
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            Modifier.clip(RoundedCornerShape(16.dp))
                .then(if (alPulsar != null) Modifier.clickable(onClick = alPulsar) else Modifier).padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            androidx.compose.foundation.Canvas(Modifier.size(64.dp)) {
                val w = size.width; val g = w * 0.045f; val c = Coo.Borde
                val redondo = androidx.compose.ui.graphics.StrokeCap.Round
                if (alPulsar != null) { // «+»: crear el trayecto
                    drawCircle(c, radius = w * 0.4f, style = androidx.compose.ui.graphics.drawscope.Stroke(width = g))
                    drawLine(c, androidx.compose.ui.geometry.Offset(w * 0.5f, w * 0.32f), androidx.compose.ui.geometry.Offset(w * 0.5f, w * 0.68f), g, cap = redondo)
                    drawLine(c, androidx.compose.ui.geometry.Offset(w * 0.32f, w * 0.5f), androidx.compose.ui.geometry.Offset(w * 0.68f, w * 0.5f), g, cap = redondo)
                } else { // lupa
                    drawCircle(c, radius = w * 0.26f, center = androidx.compose.ui.geometry.Offset(w * 0.44f, w * 0.44f),
                        style = androidx.compose.ui.graphics.drawscope.Stroke(width = g))
                    drawLine(c, androidx.compose.ui.geometry.Offset(w * 0.63f, w * 0.63f), androidx.compose.ui.geometry.Offset(w * 0.82f, w * 0.82f), g, cap = redondo)
                }
            }
            Spacer(Modifier.height(14.dp))
            Text(titulo, color = Coo.TextoTenue, fontSize = 15.sp, fontWeight = FontWeight.Medium, textAlign = TextAlign.Center)
        }
    }
}

@Composable
private fun Buscador(texto: String, alCambiar: (String) -> Unit, modifier: Modifier, alFoco: (Boolean) -> Unit = {}) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier.fillMaxWidth().height(52.dp).clip(RoundedCornerShape(26.dp))
            .background(Coo.Campo).border(1.dp, Coo.Borde, RoundedCornerShape(26.dp))
            .padding(horizontal = 16.dp)
    ) {
        IconoBuscar(color = Coo.TextoTenue, tamano = 22.dp)
        Spacer(Modifier.width(12.dp))
        Box(Modifier.weight(1f)) {
            if (texto.isEmpty()) Text("¿A dónde vas?", color = Coo.TextoTenue, fontSize = 16.sp)
            BasicTextField(
                value = texto,
                onValueChange = alCambiar,
                singleLine = true,
                textStyle = TextStyle(color = Coo.Texto, fontSize = 16.sp),
                cursorBrush = SolidColor(Coo.AzulUbicacion),
                modifier = Modifier.fillMaxWidth().onFocusChanged { alFoco(it.isFocused) }
            )
        }
        if (texto.isNotEmpty()) {
            IconoCerrar(color = Coo.TextoTenue, tamano = 20.dp,
                modifier = Modifier.clip(CircleShape).clickable { alCambiar("") })
        }
    }
}


@Composable
private fun Casilla(
    c: Coincidencia, esMia: Boolean, nombreSubida: String?, nombreBajada: String?, estadoSolicitud: String?, motivoSolicitud: String?,
    perfil: PerfilPublico?, alPulsar: () -> Unit, explicacion: String? = null,
    comunidad: pe.cooruta.app.data.Comunidad.Tipo? = null,
    favorito: Boolean = false,
    /** Origen y destino del pasajero, para compararlos en el mapa. */
    miOrigen: pe.cooruta.app.data.Punto? = null,
    miDestino: pe.cooruta.app.data.Punto? = null,
    alVerPerfil: (() -> Unit)? = null
) {
    val r = c.ruta
    var verMapa by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    if (verMapa) DialogoMapaComparar(c, miOrigen, miDestino) { verMapa = false }
    val colorCoincide = when {
        c.porcentaje >= 75 -> Coo.Exito
        c.porcentaje >= 45 -> Coo.Advertencia
        else -> Coo.TextoTenue
    }
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(NEGRO_CASILLA)
            .border(1.dp, BORDE_CASILLA, RoundedCornerShape(22.dp))
            .clickable(onClick = alPulsar).padding(16.dp)
    ) {
        // ---------- conductor · calificación de la coincidencia ----------
        Row(verticalAlignment = Alignment.CenterVertically) {
            Avatar(perfil?.foto, r.nombre, 52.dp, 22.sp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(if (esMia) "${nombrePropio(r.nombre)} (tú)" else nombrePropio(r.nombre.ifBlank { "Conductor" }), color = Coo.Texto,
                    fontSize = 17.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = 21.sp)
                Spacer(Modifier.height(5.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (perfil != null) Row(
                        Modifier.clip(RoundedCornerShape(10.dp)).background(Coo.AzulBoton)
                            .padding(horizontal = 9.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconoCasilla("escudo", Coo.SobreBoton, 15.dp)
                        Spacer(Modifier.width(5.dp))
                        Text(perfil.calificacionTexto(), color = Coo.SobreBoton, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                    if (favorito) Text("  ★ Tu conductor", color = Coo.Advertencia, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
            }
            Spacer(Modifier.width(8.dp))
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                IndicadorCoincidencia(c, colorCoincide)
            }
        }
        if (r.tramoPublico == "grupo") Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
            pe.cooruta.app.ui.componentes.IconoM(Icons.Rounded.Lock, Coo.AzulUbicacion, 15.dp)
            Spacer(Modifier.width(6.dp))
            Text("Ruta privada de tu grupo", color = Coo.AzulUbicacion, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        }
        // Empresa en común y vehículo, en una línea pequeña.
        when (comunidad) {
            pe.cooruta.app.data.Comunidad.Tipo.VERIFICADA -> Text("Pertenece a ${perfil?.empresa?.ifBlank { null } ?: "tu empresa"} ✓", color = Coo.Exito, fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp))
            pe.cooruta.app.data.Comunidad.Tipo.DECLARADA -> Text("Pertenece a ${perfil?.empresa?.ifBlank { null } ?: "tu empresa"} (no verificado)", color = Coo.Advertencia, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
            null -> {}
        }
        Spacer(Modifier.height(12.dp))
        // ---------- días y hora ----------
        Row(Modifier.height(androidx.compose.foundation.layout.IntrinsicSize.Min), verticalAlignment = Alignment.CenterVertically) {
            IconoCasilla("calendario", Coo.TextoTenue, 18.dp)
            Spacer(Modifier.width(8.dp))
            Text(resumenDias(r.dias).replaceFirstChar { it.uppercase() }, color = Coo.Texto, fontSize = 14.sp)
            Box(Modifier.padding(horizontal = 12.dp).width(1.dp).fillMaxHeight().background(BORDE_CASILLA))
            IconoCasilla("reloj", Coo.TextoTenue, 18.dp)
            Spacer(Modifier.width(8.dp))
            Text("Sale ${formatoHora(r.salida)}", color = Coo.TextoTenue, fontSize = 14.sp)
        }
        Spacer(Modifier.height(12.dp))
        // ---------- ruta del conductor ----------
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            IconoCasilla("pinContorno", Coo.AzulUbicacion, 24.dp)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text("Ruta del conductor", color = Coo.Texto, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                Text("${r.origen} → ${r.destino}", color = Coo.TextoTenue, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.width(8.dp))
            Row(
                Modifier.clip(RoundedCornerShape(14.dp)).background(Coo.AzulBoton)
                    .clickable { verMapa = true }.padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconoCasilla("mapa", Coo.SobreBoton, 18.dp)
                Spacer(Modifier.width(6.dp))
                Text("Ver en el mapa", color = Coo.SobreBoton, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            }
        }
        Spacer(Modifier.height(14.dp))
        // ---------- dónde subes y bajas ----------
        Row(Modifier.height(androidx.compose.foundation.layout.IntrinsicSize.Min)) {
            Column(Modifier.width(20.dp).fillMaxHeight().padding(top = 4.dp, bottom = 22.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.size(12.dp).clip(CircleShape).background(Coo.AzulBoton))
                IconoCasilla("guiones", Coo.AzulBoton.copy(alpha = 0.7f), 0.dp, Modifier.width(2.dp).weight(1f).padding(vertical = 3.dp))
                IconoCasilla("pin", Coo.RojoDestino, 18.dp)
            }
            Spacer(Modifier.width(12.dp))
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                PasoCasilla("Subes en ${nombreSubida ?: "buscando la calle…"}", ayudaSubida(c))
                PasoCasilla("Bajas en ${nombreBajada ?: "buscando la calle…"}", ayudaBajada(c))
            }
        }
        Box(Modifier.padding(vertical = 14.dp).fillMaxWidth().height(1.dp).background(BORDE_CASILLA))
        // ---------- asientos y distancia ----------
        Row(Modifier.height(androidx.compose.foundation.layout.IntrinsicSize.Min), verticalAlignment = Alignment.CenterVertically) {
            IconoCasilla("auto", if (r.asientosLibres > 0) Coo.Texto else Coo.RojoError, 20.dp)
            Spacer(Modifier.width(10.dp))
            Text(
                if (r.asientosLibres > 0) "${r.asientosLibres} de ${r.asientos} asientos libres" else "Sin asientos",
                color = if (r.asientosLibres > 0) Coo.Texto else Coo.RojoError, fontSize = 14.sp, modifier = Modifier.weight(1f)
            )
            Box(Modifier.padding(horizontal = 12.dp).width(1.dp).fillMaxHeight().background(BORDE_CASILLA))
            IconoCasilla("ruta", Coo.TextoTenue, 20.dp)
            Spacer(Modifier.width(8.dp))
            Column {
                Text("%.1f km".format(c.kmCompartidos), color = Coo.Texto, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                Text("≈ ${minutosEnAuto(c.kmCompartidos)} min", color = Coo.TextoTenue, fontSize = 12.sp)
            }
        }
        Box(Modifier.padding(vertical = 14.dp).fillMaxWidth().height(1.dp).background(BORDE_CASILLA))
        // ---------- tarifa y pasaje ----------
        Row(
            Modifier.fillMaxWidth().height(androidx.compose.foundation.layout.IntrinsicSize.Min),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconoCasilla("etiqueta", Coo.TextoTenue, 20.dp)
            Spacer(Modifier.width(10.dp))
            Text("${formatoSoles(c.tarifaKmCentimos)} por km", color = Coo.TextoTenue, fontSize = 13.sp, modifier = Modifier.weight(1f))
            Box(Modifier.padding(horizontal = 12.dp).width(1.dp).fillMaxHeight().background(BORDE_CASILLA))
            Text("Tu pasaje", color = Coo.TextoTenue, fontSize = 13.sp)
            Spacer(Modifier.width(10.dp))
            Text(formatoSoles(c.pasajeCentimos), color = Coo.Texto, fontSize = 24.sp, fontWeight = FontWeight.Bold)
        }
        explicacion?.let {
            Spacer(Modifier.height(10.dp))
            Text("IA · $it", color = Coo.TextoTenue, fontSize = 13.sp)
        }
        Spacer(Modifier.height(14.dp))
        // ---------- Ver perfil · acción principal ----------
        val (texto, fondo) = when (estadoSolicitud) {
            "pendiente" -> "En espera de confirmación" to Coo.CampoActivo
            "aceptada" -> "¡Asiento confirmado!" to Coo.ExitoFondo
            "rechazada" -> "Rechazada · volver a intentar" to Coo.FondoError
            "cancelada" -> (if (motivoSolicitud == "no_se_presento") "No te presentaste" else "Volver a solicitar") to Coo.CampoActivo
            "invitada" -> "Te invitó · responde en Solicitudes" to Coo.CampoActivo
            else -> when {
                !r.saleHoy -> "Hoy no sale" to Coo.CampoActivo
                else -> (if (r.asientosLibres > 0) "Solicitar asiento" else "Sin asientos libres") to Coo.AzulBoton
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (alVerPerfil != null && !esMia) Row(
                Modifier.weight(0.8f).height(52.dp).clip(RoundedCornerShape(16.dp)).background(Coo.Campo)
                    .border(1.dp, Coo.Borde, RoundedCornerShape(16.dp)).clickable(onClick = alVerPerfil),
                horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically
            ) {
                IconoCasilla("personaLlena", Coo.Texto, 20.dp)
                Spacer(Modifier.width(8.dp))
                Text("Ver perfil", color = Coo.Texto, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            }
            val principal = fondo == Coo.AzulBoton
            Row(
                Modifier.weight(1.3f).height(52.dp).clip(RoundedCornerShape(16.dp))
                    .background(fondo)
                    .clickable(onClick = alPulsar).padding(horizontal = 8.dp),
                horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically
            ) {
                if (principal) { IconoCasilla("asiento", Coo.SobreBoton, 20.dp); Spacer(Modifier.width(8.dp)) }
                Text(texto, color = if (principal) Coo.SobreBoton else Coo.Texto, fontSize = 14.sp, fontWeight = FontWeight.Bold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

// ------------------------------------------------------------------ tarjeta negra de resultado

/** Ruta que no pasa por tu camino: el conductor te recoge en tu origen y te deja en tu destino. */
private fun ayudaSubida(c: Coincidencia) =
    if (c.caminaSubirM <= 0) "Te recoge en tu punto de partida" else "Caminas ${distancia(c.caminaSubirM)} desde tu origen (≈ ${minutosAPie(c.caminaSubirM)} min)"
private fun ayudaBajada(c: Coincidencia) =
    if (c.caminaBajarM <= 0) "Te deja en tu destino" else "Caminas ${distancia(c.caminaBajarM)} hasta tu destino (≈ ${minutosAPie(c.caminaBajarM)} min)"

/** «👍 85% Coincide», o «+4.2 km · Con desvío» si la ruta no pasa por tu camino. */
@Composable
private fun IndicadorCoincidencia(c: Coincidencia, color: Color) {
    if (c.desvio) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconoCasilla("ruta", Coo.Advertencia, 18.dp)
            Spacer(Modifier.width(4.dp))
            Text("+%.1f km".format(c.desvioKm), color = Coo.Advertencia, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        }
        Text("Con desvío", color = Coo.TextoTenue, fontSize = 12.sp)
    } else {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconoCasilla("pulgar", color, 20.dp)
            Spacer(Modifier.width(4.dp))
            Text("${c.porcentaje}%", color = color, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        }
        Text("Coincide", color = Coo.TextoTenue, fontSize = 12.sp)
    }
}

/** Línea fina entre secciones de la hoja «Apartar asiento». */
@Composable
private fun LineaHoja() = Box(Modifier.padding(vertical = 10.dp).fillMaxWidth().height(1.dp).background(BORDE_CASILLA))

/** Un dato de «Detalles del viaje»: ícono a la izquierda, etiqueta y valor (y una línea pequeña opcional). */
@Composable
private fun DatoViaje(icono: String, etiqueta: String, valor: String, extra: String?, modifier: Modifier) {
    Row(modifier.padding(horizontal = 6.dp), verticalAlignment = Alignment.Top) {
        IconoCasilla(icono, Coo.TextoTenue, 18.dp, Modifier.padding(top = 2.dp).size(18.dp))
        Spacer(Modifier.width(6.dp))
        Column {
            Text(etiqueta, color = Coo.TextoTenue, fontSize = 12.sp)
            Text(valor, color = Coo.Texto, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            extra?.let { Text(it, color = Coo.TextoTenue, fontSize = 11.sp) }
        }
    }
}

/** Una fila de «Detalles del viaje»: ícono, etiqueta y valor a la derecha. */
@Composable
private fun DetalleViaje(icono: String, etiqueta: String, valor: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
        IconoCasilla(icono, Coo.TextoTenue, 18.dp)
        Spacer(Modifier.width(10.dp))
        Text(etiqueta, color = Coo.TextoTenue, fontSize = 14.sp, modifier = Modifier.weight(1f))
        Text(valor, color = Coo.Texto, fontSize = 14.sp)
    }
}

private val NEGRO_CASILLA = Color(0xFF121212)
private val BORDE_CASILLA = Color(0xFF2A2A2A)

/** A pie, unos 80 m por minuto. */
private fun minutosAPie(m: Int) = ((m + 79) / 80).coerceAtLeast(1)
/** En auto por la ciudad, unos 27 km/h. */
private fun minutosEnAuto(km: Double) = kotlin.math.ceil(km / 27.0 * 60).toInt().coerceAtLeast(1)

@Composable
private fun PasoCasilla(titulo: String, ayuda: String) {
    Column {
        Text(titulo, color = Coo.Texto, fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 2.dp)) {
            IconoCasilla("caminar", Coo.TextoTenue, 14.dp)
            Spacer(Modifier.width(6.dp))
            Text(ayuda, color = Coo.TextoTenue, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** Íconos de línea de la tarjeta (cuadrícula de 24). */
@Composable
private fun IconoCasilla(tipo: String, color: Color, tamano: androidx.compose.ui.unit.Dp, modifier: Modifier = Modifier.size(tamano)) {
    if (tipo == "guiones") {
        androidx.compose.foundation.Canvas(modifier) {
            drawLine(color, androidx.compose.ui.geometry.Offset(size.width / 2, 0f),
                androidx.compose.ui.geometry.Offset(size.width / 2, size.height), size.width, androidx.compose.ui.graphics.StrokeCap.Round,
                androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(size.width * 3, size.width * 3)))
        }
        return
    }
    val icono = when (tipo) {
        "calendario" -> Icons.Outlined.CalendarToday
        "reloj" -> Icons.Outlined.Schedule
        "ruta" -> Icons.Outlined.Directions
        "mapa" -> Icons.Outlined.Map
        "pin" -> Icons.Rounded.Place
        "caminar" -> Icons.AutoMirrored.Rounded.DirectionsWalk
        "auto" -> Icons.Outlined.DirectionsCar
        "monedas" -> Icons.Outlined.Payments
        "persona" -> Icons.Outlined.Person
        "asiento" -> Icons.Rounded.EventSeat
        "escudo" -> Icons.Rounded.VerifiedUser
        "pinContorno" -> Icons.Outlined.Place
        "personaLlena" -> Icons.Rounded.Person
        "documento" -> Icons.Outlined.Description
        "etiqueta" -> Icons.Outlined.LocalOffer
        "billetera" -> Icons.Outlined.AccountBalanceWallet
        "chat" -> Icons.Outlined.ChatBubbleOutline
        "info" -> Icons.Outlined.Info
        "estrella" -> Icons.Outlined.StarBorder
        "estrellaLlena" -> Icons.Rounded.Star
        "pulgar" -> Icons.Rounded.ThumbUp
        else -> Icons.Rounded.Info
    }
    pe.cooruta.app.ui.componentes.IconoM(icono, color, tamano)
}


private fun distancia(m: Int) = if (m < 1000) "$m m" else "%.1f km".format(m / 1000.0)

@Composable
private fun PuntoRuta(color: Color, etiqueta: String, lugar: String, ayuda: String) {
    Row(verticalAlignment = Alignment.Top) {
        Box(Modifier.padding(top = 5.dp).size(10.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(12.dp))
        Column {
            Text("$etiqueta $lugar", color = Coo.Texto, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(ayuda, color = Coo.TextoTenue, fontSize = 12.sp)
        }
    }
}


/** Hoja inferior para apartar un asiento: resumen del viaje, pasaje y confirmación. */
@Composable
private fun HojaSolicitud(
    c: Coincidencia,
    subida: String?,
    bajada: String?,
    estadoSolicitud: String?,
    motivoSolicitud: String?,
    bajadaGuardada: String?,
    perfil: PerfilPublico?,
    enviando: Boolean,
    alSolicitar: (String) -> Unit,
    alCancelar: () -> Unit,
    favorito: Boolean = false,
    alFavorito: () -> Unit = {},
    alVerPerfil: () -> Unit = {},
    enviandoHabitual: Boolean = false,
    /** Pedir viajar habitualmente (días 1–7 y mensaje); null = no se ofrece. */
    alSolicitarHabitual: ((Set<Int>, String) -> Unit)? = null
) {
    val r = c.ruta
    var mensaje by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf("") }
    Column(
        Modifier.fillMaxWidth().verticalScroll(androidx.compose.foundation.rememberScrollState())
            .padding(horizontal = 20.dp).padding(bottom = 16.dp)
    ) {
        var verMapa by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
        if (verMapa) DialogoMapaComparar(c, null, null) { verMapa = false }
        // Todo en una vista, sin contenedores: secciones separadas por líneas finas.
        Text("Apartar asiento", color = Coo.Texto, fontSize = 24.sp, fontWeight = FontWeight.Bold)
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 2.dp)) {
            IconoCasilla("calendario", Coo.TextoTenue, 16.dp)
            Spacer(Modifier.width(6.dp))
            Text("Hoy · ${java.time.LocalDate.now().format(java.time.format.DateTimeFormatter.ofPattern("EEEE d 'de' MMMM", java.util.Locale("es", "PE"))).replaceFirstChar { it.uppercase() }}",
                color = Coo.TextoTenue, fontSize = 13.sp)
        }
        Spacer(Modifier.height(12.dp))

        // ---------- conductor · coincidencia ----------
        Row(verticalAlignment = Alignment.CenterVertically) {
            Avatar(perfil?.foto, r.nombre, 60.dp, 24.sp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(nombrePropio(r.nombre.ifBlank { "Conductor" }), color = Coo.Texto, fontSize = 17.sp, fontWeight = FontWeight.Bold,
                    maxLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = 20.sp)
                Text("Sale ${formatoHora(r.salida)} · ${r.asientosLibres} de ${r.asientos} asientos libres",
                    color = Coo.TextoTenue, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                perfil?.let {
                    Row(
                        Modifier.padding(top = 4.dp).clip(RoundedCornerShape(10.dp)).background(Coo.AzulBoton)
                            .padding(horizontal = 9.dp, vertical = 3.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconoCasilla("escudo", Coo.SobreBoton, 14.dp)
                        Spacer(Modifier.width(5.dp))
                        Text(it.calificacionTexto(), color = Coo.SobreBoton, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
            Spacer(Modifier.width(8.dp))
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                IndicadorCoincidencia(c, Coo.Exito)
            }
        }
        // ---------- mis conductores · perfil ----------
        Row(Modifier.padding(top = 6.dp).height(androidx.compose.foundation.layout.IntrinsicSize.Min), verticalAlignment = Alignment.CenterVertically) {
            Row(
                Modifier.weight(1.4f).clip(RoundedCornerShape(10.dp)).clickable(onClick = alFavorito).padding(vertical = 10.dp),
                horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically
            ) {
                IconoCasilla(if (favorito) "estrellaLlena" else "estrella", if (favorito) Coo.Advertencia else Coo.AzulUbicacion, 18.dp)
                Spacer(Modifier.width(6.dp))
                Text(if (favorito) "En mis conductores" else "Agregar a mis conductores",
                    color = if (favorito) Coo.Advertencia else Coo.Texto, fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 1)
            }
            Box(Modifier.width(1.dp).fillMaxHeight().padding(vertical = 8.dp).background(BORDE_CASILLA))
            Row(
                Modifier.weight(1f).clip(RoundedCornerShape(10.dp)).clickable(onClick = alVerPerfil).padding(vertical = 10.dp),
                horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically
            ) {
                IconoCasilla("persona", Coo.AzulUbicacion, 18.dp)
                Spacer(Modifier.width(6.dp))
                Text("Ver perfil", color = Coo.Texto, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            }
        }
        LineaHoja()

        // ---------- subes y bajas ----------
        Row(Modifier.fillMaxWidth().height(androidx.compose.foundation.layout.IntrinsicSize.Min), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.width(20.dp).fillMaxHeight().padding(top = 4.dp, bottom = 20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.size(12.dp).clip(CircleShape).background(Coo.AzulBoton))
                IconoCasilla("guiones", Coo.AzulBoton.copy(alpha = 0.7f), 0.dp, Modifier.width(2.dp).weight(1f).padding(vertical = 3.dp))
                IconoCasilla("pin", Coo.RojoDestino, 18.dp)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                PasoCasilla("Subes en ${subida ?: "…"}", ayudaSubida(c))
                PasoCasilla("Bajas en ${bajada ?: "…"}", ayudaBajada(c))
            }
            Column(
                Modifier.padding(start = 6.dp).clip(RoundedCornerShape(12.dp)).clickable { verMapa = true }.padding(8.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                IconoCasilla("mapa", Coo.AzulUbicacion, 22.dp)
                Text("Ver\nmapa", color = Coo.AzulUbicacion, fontSize = 12.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, lineHeight = 14.sp)
            }
        }
        LineaHoja()

        // ---------- detalles del viaje, en tres columnas ----------
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconoCasilla("documento", Coo.AzulUbicacion, 20.dp)
            Spacer(Modifier.width(8.dp))
            Text("Detalles del viaje", color = Coo.Texto, fontSize = 15.sp, fontWeight = FontWeight.Bold)
        }
        Row(Modifier.fillMaxWidth().padding(top = 8.dp).height(androidx.compose.foundation.layout.IntrinsicSize.Min)) {
            DatoViaje("ruta", "Distancia", "%.1f km".format(c.kmCompartidos), null, Modifier.weight(1f))
            Box(Modifier.width(1.dp).fillMaxHeight().background(BORDE_CASILLA))
            DatoViaje("monedas", "Tarifa", formatoSoles(c.tarifaKmCentimos), "por km", Modifier.weight(1f))
            Box(Modifier.width(1.dp).fillMaxHeight().background(BORDE_CASILLA))
            DatoViaje("etiqueta", "Mínimo", "S/ 2.00", null, Modifier.weight(1f))
        }
        LineaHoja()
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconoCasilla("billetera", Coo.AzulUbicacion, 22.dp)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text("Pagas al conductor", color = Coo.Texto, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                Text("En efectivo o Yape al bajar.", color = Coo.TextoTenue, fontSize = 12.sp)
            }
            Text(formatoSoles(c.pasajeCentimos), color = Coo.SobreBoton, fontSize = 20.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.clip(RoundedCornerShape(50)).background(Coo.AzulBoton).padding(horizontal = 18.dp, vertical = 7.dp))
        }
        LineaHoja()
        when (estadoSolicitud) {
            null -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconoCasilla("chat", Coo.TextoTenue, 18.dp)
                    Spacer(Modifier.width(8.dp))
                    Text("Mensaje para el conductor", color = Coo.Texto, fontSize = 14.sp)
                    Text(" (opcional)", color = Coo.TextoTenue, fontSize = 14.sp)
                }
                Spacer(Modifier.height(6.dp))
                Box(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(NEGRO_CASILLA)
                        .border(1.dp, Coo.Borde, RoundedCornerShape(14.dp)).padding(horizontal = 14.dp, vertical = 12.dp)
                ) {
                    if (mensaje.isEmpty()) Text("Ej.: Llevo una mochila, te espero en la esquina…", color = Coo.TextoTenue,
                        fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    BasicTextField(
                        value = mensaje, onValueChange = { mensaje = it.take(120) },
                        textStyle = TextStyle(color = Coo.Texto, fontSize = 15.sp),
                        cursorBrush = SolidColor(Coo.AzulUbicacion), modifier = Modifier.fillMaxWidth()
                    )
                }
                Spacer(Modifier.height(12.dp))
                // La solicitud es siempre para hoy: si la ruta hoy no sale, no se ofrece.
                val puede = r.asientosLibres > 0 && r.saleHoy && !enviando
                Row(
                    Modifier.fillMaxWidth().height(56.dp).clip(RoundedCornerShape(16.dp))
                        .background(if (puede) Coo.AzulBoton else Coo.CampoActivo)
                        .clickable(enabled = puede) { alSolicitar(mensaje) },
                    horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically
                ) {
                    if (puede) { IconoCasilla("asiento", Coo.SobreBoton, 22.dp); Spacer(Modifier.width(10.dp)) }
                    Text(
                        when { enviando -> "Enviando…"; !r.saleHoy -> "Este conductor no sale hoy"; r.asientosLibres <= 0 -> "Sin asientos libres"; else -> "Solicitar asiento · ${formatoSoles(c.pasajeCentimos)}" },
                        color = if (puede) Coo.SobreBoton else Coo.TextoTenue, fontSize = 17.sp, fontWeight = FontWeight.Bold
                    )
                }
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 4.dp)) {
                    IconoCasilla("info", Coo.TextoTenue, 18.dp)
                    Spacer(Modifier.width(8.dp))
                    Text("El conductor acepta o rechaza tu solicitud. Te avisaremos aquí.", color = Coo.TextoTenue, fontSize = 12.sp)
                }
            }
            else -> {
                val (titulo, texto, color) = when (estadoSolicitud) {
                    "aceptada" -> Triple("¡Asiento confirmado!", "Te espera en ${subida ?: "el punto de subida"} a las ${formatoHora(r.salida)}.", Coo.Exito)
                    "cancelada" -> if (motivoSolicitud == "no_se_presento") Triple("No te presentaste", "El conductor te esperó 5 minutos y siguió su ruta. Tu reserva de hoy se canceló.", Coo.TextoTenue) else Triple("Cancelaste esta solicitud", "Puedes volver a solicitar si aún hay asientos.", Coo.TextoTenue)
                    "rechazada" -> Triple("Tu solicitud fue rechazada", "Si crees que fue un error, vuelve a solicitar o prueba con otro conductor.", Coo.RojoError)
                    "invitada" -> Triple("Te invitó de nuevo", "Acepta o declina la invitación en Solicitudes.", Coo.AzulUbicacion)
                    else -> Triple("En espera", "Reserva enviada. Esperando que ${r.nombre} confirme tu asiento.", Coo.AzulUbicacion)
                }
                Column(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(NEGRO_CASILLA)
                        .border(1.dp, BORDE_CASILLA, RoundedCornerShape(18.dp)).padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(titulo, color = color, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(4.dp))
                    Text(texto, color = Coo.TextoTenue, textAlign = TextAlign.Center)
                }
                if (estadoSolicitud == "rechazada" || (estadoSolicitud == "cancelada" && motivoSolicitud != "no_se_presento")) {
                    Spacer(Modifier.height(12.dp))
                    Text(
                        if (enviando) "Enviando…" else "Volver a solicitar",
                        color = if (r.asientosLibres > 0 && r.saleHoy && !enviando) Coo.SobreBoton else Coo.TextoTenue, fontSize = 16.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
                            .background(if (r.asientosLibres > 0 && r.saleHoy && !enviando) Coo.AzulBoton else Coo.CampoActivo)
                            .clickable(enabled = r.asientosLibres > 0 && r.saleHoy && !enviando) { alSolicitar(mensaje) }
                            .padding(vertical = 15.dp)
                    )
                }
                if (estadoSolicitud == "pendiente") {
                    Spacer(Modifier.height(12.dp))
                    Text(
                        if (enviando) "Cancelando…" else "Cancelar solicitud",
                        color = Coo.RojoError, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                            .clickable(enabled = !enviando, onClick = alCancelar).padding(vertical = 14.dp)
                    )
                }
            }
        }
        if (alSolicitarHabitual != null) {
            Spacer(Modifier.height(12.dp))
            SolicitarHabitual(r.dias, enviandoHabitual) { dias -> alSolicitarHabitual(dias, mensaje) }
        }
    }
}

@Composable
private fun FilaPrecio(etiqueta: String, valor: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text(etiqueta, color = Coo.TextoTenue, fontSize = 14.sp, modifier = Modifier.weight(1f))
        Text(valor, color = Coo.Texto, fontSize = 14.sp)
    }
}

/** Rutas que pasan cerca de un lugar buscado, sin necesitar trayecto guardado. */
@Composable
private fun RutasCercanas(rutas: List<Pair<pe.cooruta.app.data.repositorio.MiRuta, Int>>, lugar: String, sinTrayecto: Boolean, alGuardarTrayecto: () -> Unit) {
    if (rutas.isEmpty()) {
        Aviso("Ninguna ruta pasa cerca de $lugar", "Prueba con otro lugar cercano.", null)
        return
    }
    androidx.compose.foundation.lazy.LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 96.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Text("${rutas.size} ${if (rutas.size == 1) "ruta pasa" else "rutas pasan"} cerca de $lugar",
                color = Coo.TextoTenue, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        }
        items(rutas.size) { i ->
            val (r, d) = rutas[i]
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Coo.Campo)
                    .border(1.dp, Coo.BordeSuave, RoundedCornerShape(16.dp)).padding(16.dp)
            ) {
                Text(r.nombre.ifBlank { "Conductor" }, color = Coo.Texto, fontWeight = FontWeight.Bold)
                Text("${resumenDias(r.dias)} · sale ${formatoHora(r.salida)} · ${r.asientosLibres} de ${r.asientos} libres",
                    color = Coo.TextoTenue, fontSize = 13.sp)
                Text("Pasa a $d m del lugar", color = Coo.Exito, fontSize = 13.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(top = 4.dp))
            }
        }
        if (sinTrayecto) item {
            Text("Para pedir asiento y ver tu pasaje, guarda tu trayecto (de dónde sales, a dónde vas y a qué hora).",
                color = Coo.TextoTenue, fontSize = 13.sp)
            Text("Guardar trayecto", color = Coo.AzulUbicacion, fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(top = 6.dp).clip(RoundedCornerShape(8.dp)).clickable(onClick = alGuardarTrayecto).padding(8.dp))
        }
    }
}

private fun tienePermisoUbicacion(ctx: android.content.Context) =
    androidx.core.content.ContextCompat.checkSelfPermission(ctx, android.Manifest.permission.ACCESS_FINE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED ||
        androidx.core.content.ContextCompat.checkSelfPermission(ctx, android.Manifest.permission.ACCESS_COARSE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED

/** Última ubicación conocida del teléfono (GPS, red o pasiva); null sin permiso o sin dato. */
@android.annotation.SuppressLint("MissingPermission")
private fun ubicacionActual(ctx: android.content.Context): Punto? {
    if (!tienePermisoUbicacion(ctx)) return null
    pe.cooruta.app.data.UbicacionEnVivo.actual.value?.let { return it }
    val gestor = ctx.getSystemService(android.location.LocationManager::class.java) ?: return null
    return listOf(android.location.LocationManager.GPS_PROVIDER, android.location.LocationManager.NETWORK_PROVIDER,
        android.location.LocationManager.PASSIVE_PROVIDER)
        .mapNotNull { runCatching { gestor.getLastKnownLocation(it) }.getOrNull() }
        .maxByOrNull { it.time }?.let { Punto(it.latitude, it.longitude) }
}
