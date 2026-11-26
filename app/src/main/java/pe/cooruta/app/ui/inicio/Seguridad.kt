package pe.cooruta.app.ui.inicio

import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Contacts
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.Flag
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.PersonAdd
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import pe.cooruta.app.data.Resultado
import pe.cooruta.app.data.repositorio.GrupoRepositorio
import pe.cooruta.app.data.repositorio.SeguridadRepositorio
import pe.cooruta.app.ui.componentes.IconoM
import pe.cooruta.app.ui.theme.Coo

/*
 * Contactos, bloqueos y reportes: acciones sobre una persona (perfil o lista de un grupo),
 * las listas de Perfil y la invitación de contactos a un grupo.
 */

private val MOTIVOS_PERSONA = listOf("Comportamiento inapropiado o acoso", "No se presentó o canceló sin avisar",
    "Conducción peligrosa", "Perfil falso o suplantación", "Cobró distinto a lo acordado", "Otro")
private val MOTIVOS_APP = listOf("La app falla o se cierra", "Algo no funciona como debería", "Problema con un viaje", "Otro")

private enum class AccionPersona { BLOQUEAR, REPORTAR }

/** Fila de acciones al pie de un perfil: agregar a contactos, bloquear y reportar. */
@Composable
fun AccionesPersona(uid: String, nombre: String, alAvisar: (String) -> Unit, contexto: String = "perfil") {
    val repo = remember { SeguridadRepositorio() }
    val alcance = rememberCoroutineScope()
    var accion by remember { mutableStateOf<AccionPersona?>(null) }
    Row(
        Modifier.fillMaxWidth().background(Coo.Campo).padding(horizontal = 8.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceEvenly
    ) {
        BotonAccion(Icons.Rounded.PersonAdd, "Agregar", Coo.AzulUbicacion) {
            alcance.launch {
                when (val r = repo.agregarContacto(uid)) {
                    is Resultado.Exito -> alAvisar("${nombrePropio(r.valor.ifBlank { nombre })} está en tus contactos.")
                    is Resultado.Error -> alAvisar(r.mensaje)
                }
            }
        }
        BotonAccion(Icons.Rounded.Block, "Bloquear", Coo.TextoTenue) { accion = AccionPersona.BLOQUEAR }
        BotonAccion(Icons.Rounded.Flag, "Reportar", Coo.RojoDestino) { accion = AccionPersona.REPORTAR }
    }
    DialogosPersona(accion, uid, nombre, contexto, alAvisar) { accion = null }
}

/** Menú «⋮» con las mismas acciones (para listas). */
@Composable
fun MenuPersona(uid: String, nombre: String, alAvisar: (String) -> Unit, contexto: String) {
    val repo = remember { SeguridadRepositorio() }
    val alcance = rememberCoroutineScope()
    var abierto by remember { mutableStateOf(false) }
    var accion by remember { mutableStateOf<AccionPersona?>(null) }
    Box {
        Box(Modifier.size(40.dp).clip(CircleShape).clickable { abierto = true }, contentAlignment = Alignment.Center) {
            IconoM(Icons.Rounded.MoreVert, Coo.TextoTenue, 20.dp)
        }
        DropdownMenu(expanded = abierto, onDismissRequest = { abierto = false }, containerColor = Coo.Campo) {
            DropdownMenuItem(text = { Text("Agregar a mis contactos", color = Coo.Texto) }, onClick = {
                abierto = false
                alcance.launch {
                    when (val r = repo.agregarContacto(uid)) {
                        is Resultado.Exito -> alAvisar("${nombrePropio(r.valor.ifBlank { nombre })} está en tus contactos.")
                        is Resultado.Error -> alAvisar(r.mensaje)
                    }
                }
            })
            DropdownMenuItem(text = { Text("Bloquear", color = Coo.Texto) }, onClick = { abierto = false; accion = AccionPersona.BLOQUEAR })
            DropdownMenuItem(text = { Text("Reportar", color = Coo.RojoDestino) }, onClick = { abierto = false; accion = AccionPersona.REPORTAR })
        }
    }
    DialogosPersona(accion, uid, nombre, contexto, alAvisar) { accion = null }
}

@Composable
private fun DialogosPersona(accion: AccionPersona?, uid: String, nombre: String, contexto: String, alAvisar: (String) -> Unit, alCerrar: () -> Unit) {
    val repo = remember { SeguridadRepositorio() }
    val alcance = rememberCoroutineScope()
    val quien = nombrePropio(nombre.ifBlank { "esta persona" })
    when (accion) {
        AccionPersona.BLOQUEAR -> AlertDialog(
            onDismissRequest = alCerrar,
            containerColor = Coo.Campo,
            title = { Text("¿Bloquear a $quien?", color = Coo.Texto, fontWeight = FontWeight.Bold) },
            text = {
                Text("No verás sus rutas, ninguno podrá pedirle asiento al otro y no podrán invitarse a grupos. " +
                    "No le avisaremos. Puedes desbloquearlo en Perfil › Usuarios bloqueados.", color = Coo.TextoTenue)
            },
            confirmButton = {
                TextButton(onClick = {
                    alCerrar()
                    alcance.launch {
                        when (val r = repo.bloquear(uid, nombre)) {
                            is Resultado.Exito -> alAvisar("Bloqueaste a $quien.")
                            is Resultado.Error -> alAvisar(r.mensaje)
                        }
                    }
                }) { Text("Bloquear", color = Coo.RojoDestino, fontWeight = FontWeight.Bold) }
            },
            dismissButton = { TextButton(onClick = alCerrar) { Text("Cancelar", color = Coo.Texto) } }
        )
        AccionPersona.REPORTAR -> DialogoReportar(uid, quien, contexto, alAvisar, alCerrar)
        null -> Unit
    }
}

/** Reporte con motivos marcados por casillas; [reportadoId] vacío = problema con la app. */
@Composable
fun DialogoReportar(reportadoId: String, nombre: String?, contexto: String, alAvisar: (String) -> Unit, alCerrar: () -> Unit) {
    val repo = remember { SeguridadRepositorio() }
    val alcance = rememberCoroutineScope()
    val opciones = if (reportadoId.isBlank()) MOTIVOS_APP else MOTIVOS_PERSONA
    var motivos by remember { mutableStateOf(setOf<String>()) }
    var detalle by remember { mutableStateOf("") }
    var enviando by remember { mutableStateOf(false) }
    val listo = motivos.isNotEmpty() && ("Otro" !in motivos || detalle.trim().length >= 5)
    AlertDialog(
        onDismissRequest = { if (!enviando) alCerrar() },
        containerColor = Coo.Campo,
        title = { Text(if (reportadoId.isBlank()) "Reportar un problema" else "Reportar a $nombre", color = Coo.Texto, fontWeight = FontWeight.Bold) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text("¿Qué pasó? Puedes marcar varios.", color = Coo.TextoTenue, fontSize = 14.sp)
                Spacer(Modifier.height(6.dp))
                opciones.forEach { m ->
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
                            .clickable { motivos = if (m in motivos) motivos - m else motivos + m }.semantics { role = Role.Checkbox },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(checked = m in motivos, onCheckedChange = null,
                            colors = CheckboxDefaults.colors(checkedColor = Coo.AzulBoton, uncheckedColor = Coo.TextoTenue, checkmarkColor = Coo.SobreBoton),
                            modifier = Modifier.padding(10.dp))
                        Text(m, color = Coo.Texto, fontSize = 14.sp)
                    }
                }
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = detalle, onValueChange = { detalle = it.take(500) },
                    label = { Text(if ("Otro" in motivos) "Cuéntanos qué pasó" else "Detalle (opcional)") },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 96.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Coo.Texto, unfocusedTextColor = Coo.Texto, focusedBorderColor = Coo.AzulBoton,
                        unfocusedBorderColor = Coo.Borde, focusedLabelColor = Coo.AzulUbicacion, unfocusedLabelColor = Coo.TextoTenue,
                        cursorColor = Coo.AzulUbicacion)
                )
                Spacer(Modifier.height(6.dp))
                Text("Lo revisará el equipo de CooRuta. No le diremos quién lo envió.", color = Coo.TextoTenue, fontSize = 12.sp)
            }
        },
        confirmButton = {
            TextButton(enabled = listo && !enviando, onClick = {
                enviando = true
                alcance.launch {
                    when (val r = repo.reportar(reportadoId, opciones.filter { it in motivos }, detalle, contexto)) {
                        is Resultado.Exito -> { alAvisar("Gracias. Recibimos tu reporte."); alCerrar() }
                        is Resultado.Error -> alAvisar(r.mensaje)
                    }
                    enviando = false
                }
            }) { Text(if (enviando) "Enviando…" else "Enviar", color = if (listo) Coo.RojoDestino else Coo.TextoTenue, fontWeight = FontWeight.Bold) }
        },
        dismissButton = { TextButton(enabled = !enviando, onClick = alCerrar) { Text("Cancelar", color = Coo.Texto) } }
    )
}

/** «Mis contactos» (desde Perfil). */
@Composable
fun PantallaContactos(alCerrar: () -> Unit, alAvisar: (String) -> Unit) {
    val repo = remember { SeguridadRepositorio() }
    val alcance = rememberCoroutineScope()
    val contactos by remember { repo.contactosEnVivo() }.collectAsStateWithLifecycle(null)
    PantallaCompleta("Mis contactos", alCerrar) {
        val lista = contactos
        if (lista != null && lista.isEmpty()) {
            Vacio(Icons.Outlined.Contacts, "Aún no tienes contactos",
                "Agrégalos desde el perfil de alguien con quien compartes un grupo o un viaje. Luego podrás invitarlos a tus grupos.")
        } else LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(lista.orEmpty(), key = { it.uid }) { c ->
                FilaSimple(c.nombre) {
                    TextoBoton("Quitar", Coo.TextoTenue) {
                        alcance.launch { (repo.quitarContacto(c.uid) as? Resultado.Error)?.let { alAvisar(it.mensaje) } }
                    }
                    MenuPersona(c.uid, c.nombre, alAvisar, "contactos")
                }
            }
        }
    }
}

/** «Usuarios bloqueados» (desde Perfil). */
@Composable
fun PantallaBloqueados(alCerrar: () -> Unit, alAvisar: (String) -> Unit) {
    val repo = remember { SeguridadRepositorio() }
    val alcance = rememberCoroutineScope()
    val lista by remember { repo.bloqueadosEnVivo() }.collectAsStateWithLifecycle(null)
    PantallaCompleta("Usuarios bloqueados", alCerrar) {
        val l = lista
        if (l != null && l.isEmpty()) {
            Vacio(Icons.Rounded.Block, "No has bloqueado a nadie", "Puedes bloquear a alguien desde su perfil o desde la lista de un grupo.")
        } else LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(l.orEmpty(), key = { it.uid }) { b ->
                FilaSimple(b.nombre) {
                    TextoBoton("Desbloquear", Coo.AzulUbicacion) {
                        alcance.launch {
                            when (val r = repo.desbloquear(b.uid)) {
                                is Resultado.Exito -> alAvisar("Desbloqueaste a ${nombrePropio(b.nombre.ifBlank { "esta persona" })}.")
                                is Resultado.Error -> alAvisar(r.mensaje)
                            }
                        }
                    }
                }
            }
        }
    }
}

/** El administrador marca contactos para invitarlos al grupo. */
@Composable
fun DialogoInvitarContactos(gid: String, yaEstan: Set<String>, alAvisar: (String) -> Unit, alCerrar: () -> Unit) {
    val seguridad = remember { SeguridadRepositorio() }
    val grupos = remember { GrupoRepositorio() }
    val alcance = rememberCoroutineScope()
    val contactos by remember { seguridad.contactosEnVivo() }.collectAsStateWithLifecycle(null)
    var elegidos by remember { mutableStateOf(setOf<String>()) }
    var enviando by remember { mutableStateOf(false) }
    val disponibles = contactos.orEmpty().filter { it.uid !in yaEstan }
    AlertDialog(
        onDismissRequest = { if (!enviando) alCerrar() },
        containerColor = Coo.Campo,
        title = { Text("Invitar contactos", color = Coo.Texto, fontWeight = FontWeight.Bold) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                if (contactos != null && disponibles.isEmpty()) {
                    Text(if (contactos.isNullOrEmpty()) "Aún no tienes contactos. Agrégalos desde el perfil de alguien con quien compartes un grupo o un viaje."
                        else "Todos tus contactos ya están en este grupo o invitados.", color = Coo.TextoTenue, fontSize = 14.sp)
                }
                disponibles.forEach { c ->
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
                            .clickable { elegidos = if (c.uid in elegidos) elegidos - c.uid else elegidos + c.uid }
                            .semantics { role = Role.Checkbox },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(checked = c.uid in elegidos, onCheckedChange = null,
                            colors = CheckboxDefaults.colors(checkedColor = Coo.AzulBoton, uncheckedColor = Coo.TextoTenue, checkmarkColor = Coo.SobreBoton),
                            modifier = Modifier.padding(10.dp))
                        Avatar(null, c.nombre.ifBlank { "?" }, 30.dp)
                        Spacer(Modifier.width(10.dp))
                        Text(nombrePropio(c.nombre.ifBlank { "Contacto" }), color = Coo.Texto, fontSize = 15.sp)
                    }
                }
                if (disponibles.isNotEmpty()) {
                    Spacer(Modifier.height(6.dp))
                    Text("Cada persona deberá aceptar la invitación.", color = Coo.TextoTenue, fontSize = 12.sp)
                }
            }
        },
        confirmButton = {
            TextButton(enabled = elegidos.isNotEmpty() && !enviando, onClick = {
                enviando = true
                alcance.launch {
                    when (val r = grupos.invitarContactos(gid, elegidos.toList())) {
                        is Resultado.Exito -> {
                            alAvisar(if (r.valor == 0) "No se pudo invitar a esas personas." else if (r.valor == 1) "Enviamos 1 invitación." else "Enviamos ${r.valor} invitaciones.")
                            alCerrar()
                        }
                        is Resultado.Error -> alAvisar(r.mensaje)
                    }
                    enviando = false
                }
            }) { Text(if (enviando) "Invitando…" else "Invitar", color = if (elegidos.isNotEmpty()) Coo.AzulUbicacion else Coo.TextoTenue, fontWeight = FontWeight.Bold) }
        },
        dismissButton = { TextButton(enabled = !enviando, onClick = alCerrar) { Text("Cancelar", color = Coo.Texto) } }
    )
}

// ------------------------------------------------------------ piezas

@Composable
private fun BotonAccion(icono: ImageVector, texto: String, color: Color, alPulsar: () -> Unit) {
    Column(
        Modifier.clip(RoundedCornerShape(12.dp)).clickable(onClick = alPulsar).padding(horizontal = 18.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        IconoM(icono, color, 22.dp)
        Spacer(Modifier.height(2.dp))
        Text(texto, color = color, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun FilaSimple(nombre: String, acciones: @Composable () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Coo.Campo).padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Avatar(null, nombre.ifBlank { "?" }, 36.dp)
        Spacer(Modifier.width(10.dp))
        Text(nombrePropio(nombre.ifBlank { "Usuario" }), color = Coo.Texto, fontSize = 15.sp, modifier = Modifier.weight(1f))
        acciones()
    }
}

@Composable
private fun TextoBoton(texto: String, color: Color, alPulsar: () -> Unit) {
    Text(texto, color = color, fontSize = 14.sp, fontWeight = FontWeight.Bold,
        modifier = Modifier.clip(RoundedCornerShape(10.dp)).clickable(onClick = alPulsar).padding(horizontal = 10.dp, vertical = 8.dp))
}

@Composable
private fun Vacio(icono: ImageVector, titulo: String, texto: String) {
    Column(Modifier.fillMaxSize().padding(32.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        CirculoIcono(icono, 72.dp)
        Spacer(Modifier.height(16.dp))
        Text(titulo, color = Coo.Texto, fontSize = 18.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        Text(texto, color = Coo.TextoTenue, fontSize = 14.sp, lineHeight = 20.sp, textAlign = TextAlign.Center)
    }
}
