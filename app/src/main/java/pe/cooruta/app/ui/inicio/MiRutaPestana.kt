package pe.cooruta.app.ui.inicio

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Repeat
import androidx.compose.material.icons.outlined.Inbox
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.outlined.Cancel
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Group
import androidx.compose.material.icons.outlined.Map
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.foundation.clickable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import pe.cooruta.app.data.Coincidencia
import pe.cooruta.app.data.repositorio.MiRuta
import pe.cooruta.app.ui.componentes.IconoAsiento
import pe.cooruta.app.ui.componentes.IconoReloj
import pe.cooruta.app.ui.horario.formatoHora
import pe.cooruta.app.ui.horario.formatoSoles
import pe.cooruta.app.ui.horario.resumenDias
import pe.cooruta.app.ui.theme.Coo

/** Inicio del conductor: su ruta publicada y las solicitudes de pasajeros. */
@Composable
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
fun MiRutaPestana(
    alPublicar: () -> Unit,
    alAvisar: (String) -> Unit,
    modifier: Modifier = Modifier,
    /** true = pestaña Solicitudes (pedidos de asiento y pasajeros en el camino); false = Mi ruta. */
    soloSolicitudes: Boolean = false,
    vm: MiRutaViewModel = viewModel(factory = MiRutaViewModel.Fabrica)
) {
    val estado by vm.estado.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { vm.cargar() }
    // Secciones de Solicitudes: Pendientes | Confirmados | Rechazados (se ve una a la vez).
    // Se calculan aquí para que las pestañas queden fijas arriba (stickyHeader) al desplazar.
    val ctxHist = androidx.compose.ui.platform.LocalContext.current
    LaunchedEffect(Unit) { pe.cooruta.app.data.HistorialOculto.cargar(ctxHist) }
    val ocultos by pe.cooruta.app.data.HistorialOculto.ids.collectAsStateWithLifecycle()
    val cerradas = estado.cerradas.filter { it.id !in (ocultos ?: emptySet()) }
    var seccion by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableIntStateOf(-1) }
    val actual = if (seccion >= 0) seccion else if (estado.pendientes.isNotEmpty() || estado.confirmados.isEmpty()) 0 else 1

    when {
        estado.cargando -> Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = Coo.AzulUbicacion)
        }
        estado.error != null -> Centro(modifier, "No pudimos cargar tu ruta", estado.error.orEmpty(), "Intentar de nuevo", vm::cargar)
        estado.rutas.isEmpty() && !soloSolicitudes -> Centro(
            modifier, "Aún no publicas una ruta",
            "Publica tu ruta diaria y los pasajeros que van por tu camino podrán pedirte un asiento.",
            "Publicar ruta", alPublicar
        )
        else -> LazyColumn(
            modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (!soloSolicitudes) {
                item { Titulo(if (estado.rutas.size == 1) "Tu ruta" else "Tus rutas") }
                items(estado.rutas, key = { it.id }) { ruta ->
                    TarjetaRuta(ruta, cambiando = estado.cambiando == ruta.id, confirmados = estado.confirmadosHoy(ruta.id), alHacerPublica = { vm.hacerPublica(ruta, alAvisar) }) { vm.alternarPausa(ruta, alAvisar) }
                }
                return@LazyColumn
            }
            stickyHeader(key = "pestanas") {
                Box(Modifier.fillMaxWidth().background(Coo.Fondo)) {
                    PestanasSolicitudes(listOf("Pendientes" to estado.pendientes.size,
                        "Confirmados" to estado.confirmados.values.sumOf { it.size }, "Rechazados" to cerradas.size),
                        actual) { seccion = it }
                }
            }
            item {
                estado.errorSolicitudes?.let { err ->
                    Text(err, color = Coo.RojoError, fontSize = 14.sp,
                        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Coo.FondoError)
                            .clickable(onClick = vm::cargar).padding(12.dp))
                    Spacer(Modifier.height(12.dp))
                }
                val tarjeta: @Composable (pe.cooruta.app.data.repositorio.RutasRepositorio.Solicitud) -> Unit = { s ->
                    TarjetaSolicitud(
                        s, respondiendo = estado.respondiendo == s.id,
                        alAceptar = { vm.responder(s, true, alAvisar) },
                        alRechazar = { vm.responder(s, false, alAvisar) },
                        alReconsiderar = { vm.reconsiderar(s, alAvisar) },
                        alInvitar = { vm.invitar(s, alAvisar) },
                        yaConfirmados = if (s.estado == "pendiente") estado.confirmados[s.rutaId to s.fecha].orEmpty() else emptyList()
                    )
                    Spacer(Modifier.height(12.dp))
                }
                // Tarjeta que se desliza: el fondo rojo cubre solo la tarjeta, no el espacio de abajo.
                val deslizable: @Composable (pe.cooruta.app.data.repositorio.RutasRepositorio.Solicitud,
                                             pe.cooruta.app.ui.componentes.Confirmacion?, () -> Unit) -> Unit = { s, conf, accion ->
                    androidx.compose.runtime.key(s.id) {
                        pe.cooruta.app.ui.componentes.DeslizableParaBorrar(
                            alBorrar = accion, confirmacion = conf, habilitado = conf == null || estado.respondiendo == null
                        ) {
                            TarjetaSolicitud(
                                s, respondiendo = estado.respondiendo == s.id,
                                alAceptar = { vm.responder(s, true, alAvisar) },
                                alRechazar = { vm.responder(s, false, alAvisar) },
                                alReconsiderar = { vm.reconsiderar(s, alAvisar) },
                                alInvitar = { vm.invitar(s, alAvisar) },
                        yaConfirmados = if (s.estado == "pendiente") estado.confirmados[s.rutaId to s.fecha].orEmpty() else emptyList()
                            )
                        }
                        Spacer(Modifier.height(12.dp))
                    }
                }
                Spacer(Modifier.height(6.dp))
                // Acuerdos habituales: pendientes arriba de las solicitudes del día; aceptados en Confirmados.
                val hayHabituales = if (actual <= 1) HabitualesConductor(alAvisar, pendientes = actual == 0) else false
                // 1. Pendientes
                if (actual == 0) if (estado.pendientes.isEmpty()) {
                    if (!hayHabituales) {
                    VacioSolicitudes("📭", "Sin solicitudes por responder", "Cuando un pasajero pida asiento en tus rutas, aparecerá aquí.")
                    Spacer(Modifier.height(12.dp))
                    }
                } else estado.pendientes.forEach { s ->
                    // Deslizar = rechazar (pregunta antes).
                    deslizable(s, pe.cooruta.app.ui.componentes.Confirmacion(
                        "¿Rechazar esta solicitud?", "Se avisará a ${s.nombrePasajero.ifBlank { "el pasajero" }}.", "Rechazar")) {
                        vm.responder(s, false, alAvisar)
                    }
                }
                // 2. Confirmados, por ruta y fecha
                if (actual == 1) if (estado.confirmados.isEmpty()) {
                    if (!hayHabituales) VacioSolicitudes("🚗", "Aún no tienes pasajeros confirmados", "Los que aceptes se verán aquí, agrupados por viaje.")
                    Spacer(Modifier.height(12.dp))
                } else estado.confirmados.forEach { (clave, lista) ->
                    val ruta = estado.rutas.firstOrNull { it.id == clave.first }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "${ruta?.let { r -> r.alias.ifBlank { "${r.origen} → ${r.destino}" } } ?: "Ruta eliminada"} · ${fechaLegible(clave.second)}" +
                                (ruta?.let { " · ${formatoHora(it.salida)} · ${lista.size} de ${it.asientos}" } ?: ""),
                            color = Coo.Texto, fontSize = 14.sp, fontWeight = FontWeight.Bold,
                            maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f)
                        )
                        BotonChatsGrupo(lista)
                    }
                    if (lista.size > 1) {
                        var mapaTodos by androidx.compose.runtime.saveable.rememberSaveable(clave.toString()) { androidx.compose.runtime.mutableStateOf(false) }
                        Text("Ver los ${lista.size} pasajeros en el mapa", color = Coo.AzulUbicacion, fontSize = 14.sp, fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(top = 4.dp).clip(RoundedCornerShape(8.dp)).clickable { mapaTodos = true }.padding(vertical = 6.dp))
                        if (mapaTodos) MapaPasajeros(clave.first, lista, "Pasajeros · ${fechaLegible(clave.second)}", alCerrar = { mapaTodos = false })
                    }
                    Spacer(Modifier.height(8.dp))
                    lista.forEach { tarjeta(it) }
                }
                // 3. Rechazadas o canceladas
                if (actual == 2) if (cerradas.isEmpty()) {
                    VacioSolicitudes("🗂️", "Nada en el historial", "Aquí verás las solicitudes rechazadas o canceladas.")
                } else {
                    BotonLimpiar(cerradas.size) { pe.cooruta.app.data.HistorialOculto.ocultar(ctxHist, cerradas.map { it.id }) }
                    // Deslizar = quitar del historial (solo en este teléfono).
                    cerradas.take(20).forEach { s ->
                        deslizable(s, null) { pe.cooruta.app.data.HistorialOculto.ocultar(ctxHist, listOf(s.id)) }
                    }
                }
            }
        }
    }
}

@Composable
private fun TarjetaSolicitud(
    s: pe.cooruta.app.data.repositorio.RutasRepositorio.Solicitud,
    respondiendo: Boolean,
    alAceptar: () -> Unit,
    alRechazar: () -> Unit,
    alReconsiderar: () -> Unit,
    alInvitar: () -> Unit = {},
    yaConfirmados: List<pe.cooruta.app.data.repositorio.RutasRepositorio.Solicitud> = emptyList()
) {
    val pendiente = s.estado == "pendiente"
    var verMapa by androidx.compose.runtime.saveable.rememberSaveable(s.id) { androidx.compose.runtime.mutableStateOf(false) }
    if (verMapa) MapaSolicitud(s, yaConfirmados) { verMapa = false }
    var verPerfil by androidx.compose.runtime.saveable.rememberSaveable(s.id) { androidx.compose.runtime.mutableStateOf(false) }
    if (verPerfil) PerfilPasajero(s.pasajeroId, s.nombrePasajero) { verPerfil = false }
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(NEGRO_TARJETA)
            .border(if (pendiente) 2.dp else 1.dp, if (pendiente) Coo.AzulUbicacion else BORDE_TARJETA, RoundedCornerShape(20.dp))
            .padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 14.dp)
    ) {
        // Foto, nombre y estado (tocar abre su perfil) · pasaje a la derecha.
        Row(verticalAlignment = Alignment.Top) {
            Row(verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).clickable(enabled = s.pasajeroId.isNotBlank()) { verPerfil = true }) {
                // Foto del pasajero (si no tiene, Avatar pone su inicial).
                val foto by androidx.compose.runtime.produceState<String?>(null, s.pasajeroId) {
                    if (s.pasajeroId.isNotBlank()) value = runCatching {
                        pe.cooruta.app.data.repositorio.PerfilesRepositorio().perfiles(setOf(s.pasajeroId))[s.pasajeroId]?.foto
                    }.getOrNull()?.ifBlank { null }
                }
                Avatar(foto, s.nombrePasajero, 48.dp)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(s.nombrePasajero, color = Coo.Texto, fontSize = 16.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    val colorEstado = when (s.estado) { "aceptada" -> Coo.Exito; "rechazada" -> Coo.RojoError; "cancelada" -> Coo.TextoTenue; else -> Coo.AzulUbicacion }
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 3.dp)) {
                        IconoEstado(s.estado, colorEstado)
                        Spacer(Modifier.width(5.dp))
                        Text(
                            when (s.estado) { "aceptada" -> "Confirmado · ${fechaLegible(s.fecha).replaceFirstChar { it.uppercase() }}"; "rechazada" -> "Rechazada · ${fechaLegible(s.fecha)}"; "cancelada" -> "Cancelada por el pasajero"; "invitada" -> "Invitado · esperando su respuesta"; else -> "Quiere un asiento · ${fechaLegible(s.fecha)}" },
                            color = colorEstado, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
            Spacer(Modifier.width(8.dp))
            Column(horizontalAlignment = Alignment.End) {
                Text(formatoSoles(s.pasajeCentimos), color = Coo.Texto, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                Text("por viaje", color = Coo.TextoTenue, fontSize = 12.sp)
            }
        }
        Spacer(Modifier.height(14.dp))
        // Recorrido: punto azul (recojo) unido por una línea al punto rojo (destino).
        Row(Modifier.height(androidx.compose.foundation.layout.IntrinsicSize.Min)) {
            Column(Modifier.width(14.dp).fillMaxHeight().padding(top = 4.dp, bottom = 26.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.size(12.dp).clip(CircleShape).background(Coo.AzulBoton))
                Box(Modifier.width(2.dp).weight(1f).background(Coo.Borde))
                Box(Modifier.size(12.dp).clip(CircleShape).background(Coo.RojoDestino))
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(s.subida, color = Coo.Texto, fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("Recojo" + (horaAmPm(s.horaRecogida)?.let { " · $it" } ?: ""), color = Coo.TextoTenue, fontSize = 13.sp)
                Spacer(Modifier.height(12.dp))
                Text(s.bajada, color = Coo.Texto, fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("Destino", color = Coo.TextoTenue, fontSize = 13.sp)
            }
        }
        Box(Modifier.padding(vertical = 12.dp).fillMaxWidth().height(1.dp).background(BORDE_TARJETA))
        // Pie: distancia compartida · mapa, chat y perfil.
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconoEnlace(Coo.TextoTenue)
            Spacer(Modifier.width(6.dp))
            Text("%.1f km juntos".format(s.kmCompartidos), color = Coo.TextoTenue, fontSize = 13.sp, modifier = Modifier.weight(1f))
            Row(
                Modifier.padding(end = 8.dp).clip(RoundedCornerShape(50)).border(1.dp, Coo.Borde, RoundedCornerShape(50))
                    .clickable(onClickLabel = "Ver mapa") { verMapa = true }.padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconoMapaPequeno(Coo.Texto)
                Spacer(Modifier.width(8.dp))
                Text("Ver mapa", color = Coo.Texto, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            }
            if (s.fecha >= java.time.LocalDate.now().toString()) {
                IconoChat(s, comoConductor = true)
                Spacer(Modifier.width(8.dp))
            }
            if (s.pasajeroId.isNotBlank()) Box(
                Modifier.size(40.dp).clip(CircleShape).border(1.dp, Coo.Borde, CircleShape)
                    .clickable(onClickLabel = "Ver perfil de ${s.nombrePasajero}") { verPerfil = true },
                contentAlignment = Alignment.Center
            ) { IconoFlecha(Coo.Texto) }
        }
        if (s.mensaje.isNotBlank()) {
            Spacer(Modifier.height(8.dp))
            Text("“${s.mensaje}”", color = Coo.Texto, fontSize = 14.sp,
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Coo.Fondo).padding(10.dp))
        }
        if (pendiente) {
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(
                    onClick = alRechazar, enabled = !respondiendo, modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Coo.RojoError)
                ) { Text("Rechazar") }
                androidx.compose.material3.Button(
                    onClick = alAceptar, enabled = !respondiendo, modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = Coo.AzulBoton, contentColor = Coo.SobreBoton)
                ) { Text(if (respondiendo) "…" else "Aceptar", fontWeight = FontWeight.Bold) }
            }
        } else if (s.estado == "rechazada" && s.fecha >= java.time.LocalDate.now().toString()) {
            // Rechazo del conductor: vuelve a «Pendientes» para aceptarla o rechazarla otra vez.
            Spacer(Modifier.height(12.dp))
            androidx.compose.material3.Button(
                onClick = alReconsiderar, enabled = !respondiendo, modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = Coo.AzulBoton, contentColor = Coo.SobreBoton)
            ) { Text(if (respondiendo) "…" else "↺ Reconsiderar", fontWeight = FontWeight.Bold) }
        } else if (s.estado == "cancelada" && s.fecha >= java.time.LocalDate.now().toString()) {
            // La canceló el pasajero: se le puede invitar de nuevo; él decide si acepta.
            Spacer(Modifier.height(12.dp))
            androidx.compose.material3.Button(
                onClick = alInvitar, enabled = !respondiendo, modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = Coo.AzulBoton, contentColor = Coo.SobreBoton)
            ) { Text(if (respondiendo) "…" else "↺ Invitar de nuevo", fontWeight = FontWeight.Bold) }
            Text("${s.nombrePasajero.substringBefore(" ").ifBlank { "El pasajero" }} recibirá la invitación y decidirá si acepta.",
                color = Coo.TextoTenue, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
        } else if (s.estado == "invitada") {
            Spacer(Modifier.height(10.dp))
            Text("Invitación enviada. Si acepta, podrás confirmarla aquí.", color = Coo.AzulUbicacion, fontSize = 13.sp)
        }

    }
}

@Composable
private fun TarjetaPasajero(
    p: MiRuta, c: Coincidencia, esYo: Boolean, subida: String?, bajada: String?, variasRutas: Boolean
) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Coo.Campo)
            .border(1.dp, Coo.BordeSuave, RoundedCornerShape(16.dp)).padding(16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(36.dp).clip(CircleShape).background(Coo.AzulUbicacion), contentAlignment = Alignment.Center) {
                Text(p.nombre.trim().take(1).uppercase().ifEmpty { "?" }, color = Coo.Texto, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(if (esYo) "${p.nombre} (tú)" else p.nombre, color = Coo.Texto, fontWeight = FontWeight.Bold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("Quiere salir ${formatoHora(p.salida)} · tú sales ${formatoHora(c.ruta.salida)}",
                    color = Coo.TextoTenue, fontSize = 13.sp)
            }
            val color = when {
                c.porcentaje >= 75 -> Coo.Exito
                c.porcentaje >= 45 -> Coo.Advertencia
                else -> Coo.TextoTenue
            }
            Text("${c.porcentaje}%", color = color, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        }
        if (variasRutas) {
            Spacer(Modifier.height(6.dp))
            Text("En tu ruta: ${c.ruta.alias.ifBlank { "${c.ruta.origen} → ${c.ruta.destino}" }}",
                color = Coo.TextoTenue, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.height(12.dp))
        Punto(Coo.AzulUbicacion, "Sube en ${subida ?: "…"}")
        Box(Modifier.padding(start = 4.dp).width(2.dp).height(14.dp).background(Coo.Borde))
        Punto(Coo.RojoDestino, "Baja en ${bajada ?: "…"}")
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("%.1f km juntos".format(c.kmCompartidos), color = Coo.TextoTenue, fontSize = 14.sp,
                modifier = Modifier.weight(1f))
            Text("Recibes ${formatoSoles(c.pasajeCentimos)}", color = Coo.Texto, fontSize = 16.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(12.dp))
        // Invitar pasajeros no existe en esta versión: se informa, sin un botón que parezca roto.
        Text("Si te interesa, espera a que te pida asiento desde Buscar.", color = Coo.TextoTenue, fontSize = 13.sp)
    }
}

@Composable
private fun Titulo(texto: String) =
    Text(texto, color = Coo.TextoTenue, fontSize = 13.sp, fontWeight = FontWeight.Bold)

@Composable
private fun TarjetaRuta(
    ruta: MiRuta, cambiando: Boolean,
    confirmados: List<pe.cooruta.app.data.repositorio.RutasRepositorio.Solicitud>,
    alHacerPublica: () -> Unit, alPausar: () -> Unit
) {
    var verConfirmados by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf(false) }
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Coo.Campo)
            .border(1.dp, Coo.BordeSuave, RoundedCornerShape(16.dp)).padding(16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            val color = if (ruta.activa) Coo.Exito else Coo.TextoTenue
            Box(Modifier.size(8.dp).clip(CircleShape).background(color))
            Spacer(Modifier.width(6.dp))
            Text(if (ruta.activa) "Activa" else "Pausada", color = color, fontSize = 13.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f))
            Text(resumenDias(ruta.dias), color = Coo.TextoTenue, fontSize = 13.sp)
        }
        Spacer(Modifier.height(14.dp))
        Punto(Coo.AzulUbicacion, ruta.origen)
        Box(Modifier.padding(start = 4.dp).width(2.dp).height(14.dp).background(Coo.Borde))
        Punto(Coo.RojoDestino, ruta.destino)
        Spacer(Modifier.height(14.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconoReloj(color = Coo.TextoTenue, tamano = 18.dp)
            Spacer(Modifier.width(6.dp))
            val min = ruta.duracionSegundos?.let { " · ${(it + 59) / 60} min" }.orEmpty()
            Text("Sale ${formatoHora(ruta.salida)}$min", color = Coo.TextoTenue, fontSize = 14.sp)
        }
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconoAsiento(color = Coo.TextoTenue, tamano = 18.dp)
            Spacer(Modifier.width(6.dp))
            val pasaje = ruta.pasajeCentimos?.let { " · ${formatoSoles(it)} por pasajero" }.orEmpty()
            Text("${ruta.asientosLibres} de ${ruta.asientos} asientos libres$pasaje", color = Coo.TextoTenue, fontSize = 14.sp)
        }
        // Rutas sin tramo público utilizable (también las publicadas antes): no se corrigen solas.
        if (!ruta.visibleEnBuscar) {
            Spacer(Modifier.height(8.dp))
            Text("Ruta privada: no aparece en Buscar y ningún pasajero puede pedirte asiento.",
                color = Coo.Advertencia, fontSize = 13.sp)
            Spacer(Modifier.height(8.dp))
            androidx.compose.material3.Button(
                onClick = alHacerPublica, enabled = !cambiando, modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = Coo.AzulBoton, contentColor = Coo.SobreBoton)
            ) { Text(if (cambiando) "Guardando…" else "Hacer pública") }
        }
        Spacer(Modifier.height(14.dp))
        OutlinedButton(
            onClick = alPausar,
            enabled = !cambiando,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = Coo.Texto)
        ) { Text(if (cambiando) "Guardando…" else if (ruta.activa) "Pausar ruta" else "Reactivar ruta") }
        Spacer(Modifier.height(8.dp))
        Text(
            "${confirmados.size} confirmado${if (confirmados.size == 1) "" else "s"} hoy" +
                if (confirmados.isNotEmpty()) (if (verConfirmados) " · ocultar" else " · ver") else "",
            color = if (confirmados.isNotEmpty()) Coo.AzulUbicacion else Coo.TextoTenue,
            fontSize = 14.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                .clickable(enabled = confirmados.isNotEmpty()) { verConfirmados = !verConfirmados }.padding(vertical = 6.dp)
        )
        if (verConfirmados) confirmados.forEach { s ->
            Text("• ${s.nombrePasajero}: sube en ${s.subida}, baja en ${s.bajada} · ${formatoSoles(s.pasajeCentimos)}",
                color = Coo.Texto, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp))
        }
    }
}

/** "2026-10-15" → "hoy", "mañana" o "15/10". */
internal fun fechaLegible(fecha: String): String {
    val hoy = java.time.LocalDate.now()
    val f = runCatching { java.time.LocalDate.parse(fecha) }.getOrNull() ?: return fecha
    return when (f) { hoy -> "hoy"; hoy.plusDays(1) -> "mañana"; else -> "%02d/%02d".format(f.dayOfMonth, f.monthValue) }
}

@Composable
private fun Punto(color: Color, texto: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(10.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(12.dp))
        Text(texto, color = Coo.Texto, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun Centro(modifier: Modifier, titulo: String, texto: String, boton: String, alPulsar: () -> Unit) {
    Column(
        modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(titulo, color = Coo.Texto, fontSize = 20.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        Text(texto, color = Coo.TextoTenue, textAlign = TextAlign.Center)
        Spacer(Modifier.height(20.dp))
        OutlinedButton(onClick = alPulsar, colors = ButtonDefaults.outlinedButtonColors(contentColor = Coo.Texto)) {
            Text(boton)
        }
    }
}

/** Conductor: su ruta con el punto donde sube (verde) y donde baja (ámbar) el pasajero. Solo para ver. */
@Composable
internal fun MapaSolicitud(
    s: pe.cooruta.app.data.repositorio.RutasRepositorio.Solicitud,
    yaConfirmados: List<pe.cooruta.app.data.repositorio.RutasRepositorio.Solicitud> = emptyList(),
    alCerrar: () -> Unit
) = MapaPasajeros(s.rutaId, yaConfirmados.filter { it.id != s.id } + s, s.nombrePasajero, alCerrar, nuevoId = s.id)

/**
 * Conductor: su ruta con todos los pasajeros. Cada uno tiene número y color (S1/B1, S2/B2…), numerados
 * en el orden en que sube sobre la ruta; debajo, la lista de paradas en el orden en que pasará.
 */
@Composable
private fun MapaPasajeros(
    rutaId: String, lista: List<pe.cooruta.app.data.repositorio.RutasRepositorio.Solicitud>, titulo: String, alCerrar: () -> Unit,
    /** Solicitud por aceptar: el desvío se mide contra el recorrido con los demás ya confirmados. */
    nuevoId: String? = null
) {
    val ruta by androidx.compose.runtime.produceState<List<pe.cooruta.app.data.Punto>?>(null, rutaId) {
        value = (pe.cooruta.app.data.repositorio.ViajesRepositorio().privadoDeRuta(rutaId)
            as? pe.cooruta.app.data.Resultado.Exito)?.valor?.recorrido.orEmpty()
    }
    val r = ruta.orEmpty()
    // Paradas en el orden en que pasa el conductor (puerta a puerta, cada bajada después de su subida).
    data class Parada(val s: pe.cooruta.app.data.repositorio.RutasRepositorio.Solicitud, val punto: pe.cooruta.app.data.Punto, val sube: Boolean)
    fun orden(de: List<pe.cooruta.app.data.repositorio.RutasRepositorio.Solicitud>) =
        pe.cooruta.app.data.PlanificadorRecorrido.ordenarPuertaAPuerta(r.firstOrNull(),
            de.flatMap { s -> listOfNotNull(s.subidaPunto?.let { Parada(s, it, true) }, s.bajadaPunto?.let { Parada(s, it, false) }) },
            { it.punto }, { it.s.id }, { it.sube })
    val enOrden = orden(lista)
    val ordenados = enOrden.filter { it.sube }.map { it.s } + lista.filter { s -> enOrden.none { it.sube && it.s.id == s.id } }
    val uno = ordenados.size == 1
    val marcas = ordenados.mapIndexed { i, s ->
        pe.cooruta.app.ui.confirmar.PasajeroMapa(if (uno) "" else "${i + 1}",
            pe.cooruta.app.ui.confirmar.COLORES_PASAJEROS[i % pe.cooruta.app.ui.confirmar.COLORES_PASAJEROS.size],
            s.subidaPunto, s.bajadaPunto)
    }
    val paradas = enOrden.map { p ->
        val i = ordenados.indexOf(p.s)
        val n = if (uno) "" else "${i + 1}"
        Triple(0, if (p.sube) "S$n · Sube ${p.s.nombrePasajero} · ${p.s.subida}" else "B$n · Baja ${p.s.nombrePasajero} · ${p.s.bajada}",
            marcas[i].color)
    }
    // Nueva trayectoria: inicio del conductor → paradas en orden → destino del conductor.
    // «Antes»: el recorrido de hoy con los ya confirmados (sin la solicitud nueva), o la ruta original.
    val ctxMapa = androidx.compose.ui.platform.LocalContext.current
    val calculo by androidx.compose.runtime.produceState<Pair<pe.cooruta.app.data.RutaCalculada?, Double?>?>(null, r, lista) {
        if (r.size < 2) return@produceState
        val repo = pe.cooruta.app.data.repositorio.LugaresRepositorio(
            recientesDao = pe.cooruta.app.data.local.CooRutaBaseDatos.obtener(ctxMapa.applicationContext).lugaresRecientes())
        suspend fun pasandoPor(de: List<pe.cooruta.app.data.repositorio.RutasRepositorio.Solicitud>) =
            (repo.recorridoPor(listOf(r.first()) + orden(de).map { it.punto }.take(25) + listOf(r.last()))
                as? pe.cooruta.app.data.Resultado.Exito)?.valor
        val nueva = if (enOrden.isEmpty()) null else pasandoPor(lista)
        val otros = lista.filter { it.id != nuevoId }
        val antes = if (nuevoId != null && otros.isNotEmpty()) pasandoPor(otros)?.metros
            else r.zipWithNext { a, b -> pe.cooruta.app.data.metros(a, b) }.sum()
        value = nueva to antes
    }
    val nueva = calculo?.first
    val conOtros = nuevoId != null && lista.size > 1
    val resumenNueva = nueva?.metros?.let { m ->
        val extra = calculo?.second?.let { (m - it) / 1000.0 }
        val km = String.format(java.util.Locale.US, "%.1f", m / 1000.0)
        val desvio = if (extra != null && extra >= 0.1) " · +${String.format(java.util.Locale.US, "%.1f", extra)} km de desvío" +
            (if (conOtros) " por este pasajero" else "") else ""
        val min = nueva.segundos?.let { " · ${(it / 60).toInt()} min" } ?: ""
        "Verde: tu recorrido ${if (conOtros) "con tus ${lista.size} pasajeros" else "pasando por las paradas"} · $km km$min$desvio. Azul tenue: tu ruta original."
    }
    androidx.compose.ui.window.Dialog(onDismissRequest = alCerrar,
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.fillMaxWidth().padding(16.dp).clip(RoundedCornerShape(20.dp)).background(Coo.Campo).padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(titulo, color = Coo.Texto, fontSize = 17.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                androidx.compose.material3.TextButton(onClick = alCerrar) { Text("Cerrar") }
            }
            Box(Modifier.fillMaxWidth().height(340.dp).clip(RoundedCornerShape(16.dp)), contentAlignment = Alignment.Center) {
                if (ruta == null) CircularProgressIndicator(color = Coo.AzulUbicacion)
                else if (pe.cooruta.app.BuildConfig.HAY_MAPA) pe.cooruta.app.ui.confirmar.MapaVista(r, pasajeros = marcas, nueva = nueva?.linea.orEmpty())
                else Text("Sin mapa en este dispositivo.", color = Coo.TextoTenue)
            }
            Spacer(Modifier.height(8.dp))
            if (resumenNueva != null) {
                Text(resumenNueva, color = Coo.Exito, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                Spacer(Modifier.height(4.dp))
            }
            if (uno) Text(if (resumenNueva != null) "S: sube en ${lista[0].subida}. B: baja en ${lista[0].bajada}." else "Azul: tu ruta. Línea blanca al centro: el tramo que va contigo. S: sube en ${lista[0].subida}. B: baja en ${lista[0].bajada}.",
                color = Coo.TextoTenue, fontSize = 12.sp)
            else androidx.compose.foundation.lazy.LazyColumn(Modifier.heightIn(max = 180.dp)) {
                item { Text("Paradas en orden", color = Coo.TextoTenue, fontSize = 12.sp, fontWeight = FontWeight.Bold) }
                items(paradas.size) { k ->
                    val (_, texto, color) = paradas[k]
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 4.dp)) {
                        Box(Modifier.size(10.dp).clip(CircleShape).background(color))
                        Spacer(Modifier.width(8.dp))
                        Text("${k + 1}. $texto", color = Coo.Texto, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}


/** Pestañas de sección con contador, estilo subrayado: «Pendientes 2 | Confirmados 1 | Rechazados». */
@Composable
fun PestanasSolicitudes(secciones: List<Pair<String, Int>>, elegida: Int, alElegir: (Int) -> Unit) {
    // Con 4 secciones el texto baja un punto para no cortarse en pantallas angostas.
    val tamano = if (secciones.size > 3) 13.sp else 15.sp
    Column(Modifier.fillMaxWidth().padding(top = 12.dp)) {
        Row(Modifier.fillMaxWidth()) {
            secciones.forEachIndexed { i, (titulo, n) ->
                val activa = i == elegida
                Column(
                    Modifier.weight(1f).clip(RoundedCornerShape(10.dp)).clickable { alElegir(i) },
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Row(Modifier.padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(titulo, color = if (activa) Coo.Texto else Coo.TextoTenue, fontSize = tamano,
                            fontWeight = if (activa) FontWeight.Bold else FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (n > 0) {
                            Spacer(Modifier.width(if (secciones.size > 3) 4.dp else 6.dp))
                            Text("$n", color = Coo.SobreBoton, fontSize = 11.sp, fontWeight = FontWeight.Bold,
                                modifier = Modifier.clip(RoundedCornerShape(50)).background(if (activa) Coo.AzulBoton else Coo.Borde)
                                    .padding(horizontal = 7.dp, vertical = 1.dp))
                        }
                    }
                    // Indicador de la pestaña activa.
                    Box(Modifier.fillMaxWidth(0.7f).height(3.dp).clip(RoundedCornerShape(50))
                        .background(if (activa) Coo.AzulBoton else androidx.compose.ui.graphics.Color.Transparent))
                }
            }
        }
        androidx.compose.material3.HorizontalDivider(color = Coo.BordeSuave)
    }
}

/** Estado vacío centrado: ícono de líneas finas sin color y un título (sin submensaje). */
@Composable
fun VacioSolicitudes(icono: String, titulo: String, @Suppress("UNUSED_PARAMETER") texto: String = "") {
    // Ocupa el alto visible bajo las pestañas para quedar centrado en la pantalla.
    val alto = androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp
    Column(Modifier.fillMaxWidth().heightIn(min = (alto * 0.55f).dp).padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        IconoVacio(icono, Coo.Borde)
        Spacer(Modifier.height(14.dp))
        Text(titulo, color = Coo.TextoTenue, fontSize = 15.sp, fontWeight = FontWeight.Medium, textAlign = TextAlign.Center)
    }
}

/** Íconos minimalistas para estados vacíos: bandeja (pendientes), check (confirmados), reloj (historial). */
@Composable
private fun IconoVacio(tipo: String, color: androidx.compose.ui.graphics.Color) =
    pe.cooruta.app.ui.componentes.IconoM(
        when (tipo) {
            "🚗" -> Icons.Outlined.CheckCircle
            "🗂️" -> Icons.Outlined.History
            "🔁" -> Icons.Outlined.Repeat
            else -> Icons.Outlined.Inbox
        }, color, 64.dp)


/** «Limpiar historial» con confirmación (solo oculta en este teléfono). */
@Composable
fun BotonLimpiar(cantidad: Int, alLimpiar: () -> Unit) {
    var confirmar by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    // Arriba de la lista, a la derecha (como en Notificaciones).
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        Row(Modifier.clip(RoundedCornerShape(10.dp)).clickable { confirmar = true }.padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically) {
            IconoTacho(Coo.AzulBoton)
            Spacer(Modifier.width(6.dp))
            Text("Limpiar todo", color = Coo.AzulBoton, fontSize = 14.sp, fontWeight = FontWeight.Bold)
        }
    }
    if (confirmar) androidx.compose.material3.AlertDialog(
        onDismissRequest = { confirmar = false },
        containerColor = Coo.Campo,
        title = { Text("¿Limpiar historial?", fontWeight = FontWeight.Bold) },
        text = { Text(if (cantidad == 1) "Se quitará 1 solicitud de tu historial." else "Se quitarán $cantidad solicitudes de tu historial.", color = Coo.TextoTenue) },
        confirmButton = { androidx.compose.material3.TextButton(onClick = { confirmar = false; alLimpiar() }) { Text("Limpiar", fontWeight = FontWeight.Bold) } },
        dismissButton = { androidx.compose.material3.TextButton(onClick = { confirmar = false }) { Text("Cancelar", color = Coo.Texto) } }
    )
}


/** Tacho de basura dibujado con líneas finas. */
@Composable
private fun IconoTacho(color: androidx.compose.ui.graphics.Color) =
    pe.cooruta.app.ui.componentes.IconoM(Icons.Rounded.DeleteOutline, color, 18.dp)


/** Ícono de mensajes: abre la lista de confirmados del viaje para elegir con quién chatear. */
@Composable
private fun BotonChatsGrupo(lista: List<pe.cooruta.app.data.repositorio.RutasRepositorio.Solicitud>) {
    var elegir by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf(false) }
    var chatCon by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<pe.cooruta.app.data.repositorio.RutasRepositorio.Solicitud?>(null) }
    Box(
        Modifier.padding(start = 8.dp).size(40.dp).clip(CircleShape).border(1.dp, Coo.Borde, CircleShape)
            .clickable { if (lista.size == 1) chatCon = lista.first() else elegir = true }
            .semantics { contentDescription = "Mensajes con tus pasajeros" },
        contentAlignment = Alignment.Center
    ) {
        // Globo de diálogo de líneas finas.
        androidx.compose.foundation.Canvas(Modifier.size(20.dp)) {
            val g = 1.8.dp.toPx(); val w = size.width; val h = size.height
            val globo = androidx.compose.ui.graphics.Path().apply {
                addRoundRect(androidx.compose.ui.geometry.RoundRect(0f + g, 0f + g, w - g, h * 0.75f,
                    androidx.compose.ui.geometry.CornerRadius(w * 0.22f)))
                moveTo(w * 0.28f, h * 0.75f); lineTo(w * 0.22f, h - g); lineTo(w * 0.48f, h * 0.75f)
            }
            drawPath(globo, Coo.AzulUbicacion, style = androidx.compose.ui.graphics.drawscope.Stroke(g,
                join = androidx.compose.ui.graphics.StrokeJoin.Round))
            listOf(0.32f, 0.5f, 0.68f).forEach { x -> drawCircle(Coo.AzulUbicacion, g * 0.75f, androidx.compose.ui.geometry.Offset(w * x, h * 0.39f)) }
        }
    }
    if (elegir) {
        val fotos by androidx.compose.runtime.produceState(emptyMap<String, String>(), lista) {
            value = runCatching {
                pe.cooruta.app.data.repositorio.PerfilesRepositorio().perfiles(lista.map { it.pasajeroId }.filter { it.isNotBlank() }.toSet())
                    .mapValues { it.value.foto }
            }.getOrDefault(emptyMap())
        }
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { elegir = false },
            containerColor = Coo.Campo,
            title = { Text("¿Con quién quieres chatear?", fontWeight = FontWeight.Bold, fontSize = 18.sp) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    lista.forEach { s ->
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                                .clickable { elegir = false; chatCon = s }.padding(horizontal = 8.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Avatar(fotos[s.pasajeroId]?.ifBlank { null }, s.nombrePasajero, 36.dp)
                            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                                Text(s.nombrePasajero.ifBlank { "Pasajero" }, color = Coo.Texto, fontWeight = FontWeight.Bold, fontSize = 15.sp,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text("Sube en ${s.subida}", color = Coo.TextoTenue, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            },
            confirmButton = { androidx.compose.material3.TextButton(onClick = { elegir = false }) { Text("Cerrar") } }
        )
    }
    chatCon?.let { s -> PantallaChat(s, s.nombrePasajero.ifBlank { "Pasajero" }, alCerrar = { chatCon = null }) }
}

// ------------------------------------------------------------------ tarjeta negra de solicitud

/** Fondo y borde de las tarjetas de solicitud (negro, para que resalten sobre el fondo azul oscuro). */
private val NEGRO_TARJETA = Color(0xFF121212)
private val BORDE_TARJETA = Color(0xFF2A2A2A)

/** "01:11" → "1:11 a. m."; null si no hay hora. */
private fun horaAmPm(hhmm: String): String? = runCatching {
    val t = java.time.LocalTime.parse(hhmm)
    val h = if (t.hour % 12 == 0) 12 else t.hour % 12
    "%d:%02d %s".format(h, t.minute, if (t.hour < 12) "a. m." else "p. m.")
}.getOrNull()

private fun androidx.compose.ui.graphics.drawscope.DrawScope.linea(u: Float) =
    androidx.compose.ui.graphics.drawscope.Stroke(1.8f * u, cap = androidx.compose.ui.graphics.StrokeCap.Round,
        join = androidx.compose.ui.graphics.StrokeJoin.Round)

/** Ícono del estado: check en círculo (confirmado), aspa (rechazada/cancelada) o reloj (en espera). */
@Composable
private fun IconoEstado(estado: String, color: Color) =
    pe.cooruta.app.ui.componentes.IconoM(
        when (estado) {
            "aceptada" -> Icons.Outlined.CheckCircle
            "rechazada", "cancelada" -> Icons.Outlined.Cancel
            else -> Icons.Outlined.Schedule
        }, color, 15.dp)

/** Dos eslabones: kilómetros que comparten. */
@Composable
private fun IconoEnlace(color: Color) =
    pe.cooruta.app.ui.componentes.IconoM(Icons.Outlined.Group, color, 16.dp)

/** Mapa plegado. */
@Composable
private fun IconoMapaPequeno(color: Color) =
    pe.cooruta.app.ui.componentes.IconoM(Icons.Outlined.Map, color, 18.dp)

/** Flecha «›» (abrir perfil). */
@Composable
private fun IconoFlecha(color: Color) =
    pe.cooruta.app.ui.componentes.IconoAdelante(color = color, tamano = 22.dp)
