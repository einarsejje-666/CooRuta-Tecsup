package pe.cooruta.app.ui.inicio

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Group
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.GroupAdd
import androidx.compose.material.icons.rounded.LinkOff
import androidx.compose.material.icons.rounded.PersonAdd
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import pe.cooruta.app.data.InvitacionPendiente
import pe.cooruta.app.data.Resultado
import pe.cooruta.app.data.repositorio.GrupoRepositorio
import pe.cooruta.app.ui.componentes.IconoAtras
import pe.cooruta.app.ui.componentes.IconoM
import pe.cooruta.app.ui.theme.Coo

/*
 * Grupos permanentes: lista de mis grupos, crear uno, detalle con integrantes y solicitudes,
 * invitar por WhatsApp y el diálogo que ve quien abre un enlace de invitación.
 */

private val AZUL_CLARO = Color(0xFF7189FF)
private val AZUL_HONDO = Color(0xFF3F55D9)

/** «Mis grupos», a pantalla completa (desde Perfil). */
@Composable
fun PantallaGrupos(alCerrar: () -> Unit, alAvisar: (String) -> Unit) {
    val repo = remember { GrupoRepositorio() }
    var recarga by remember { mutableIntStateOf(0) }
    val grupos by remember(recarga) { repo.misGruposEnVivo() }.collectAsStateWithLifecycle(null)
    var abierto by remember { mutableStateOf<String?>(null) }
    var creando by remember { mutableStateOf(false) }

    PantallaCompleta("Mis grupos", alCerrar) {
        val lista = grupos
        when {
            lista == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Coo.AzulBoton) }
            lista.isEmpty() -> Column(
                Modifier.fillMaxSize().padding(32.dp), verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                CirculoIcono(Icons.Outlined.Group, 72.dp)
                Spacer(Modifier.height(16.dp))
                Text("Aún no estás en ningún grupo", color = Coo.Texto, fontSize = 18.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                Spacer(Modifier.height(8.dp))
                Text("Crea uno con tus compañeros de viaje de confianza o únete con el enlace que te compartan por WhatsApp.",
                    color = Coo.TextoTenue, fontSize = 14.sp, lineHeight = 20.sp, textAlign = TextAlign.Center)
                Spacer(Modifier.height(24.dp))
                BotonPrincipal("Crear grupo", Icons.Rounded.GroupAdd) { creando = true }
            }
            else -> LazyColumn(
                Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 16.dp, top = 8.dp, end = 16.dp, bottom = 96.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(lista, key = { it.id }) { g -> TarjetaGrupo(g) { abierto = g.id } }
            }
        }
        if (!lista.isNullOrEmpty()) Box(Modifier.align(Alignment.BottomCenter).padding(16.dp)) {
            BotonPrincipal("Crear grupo", Icons.Rounded.GroupAdd) { creando = true }
        }
    }
    if (creando) DialogoCrearGrupo(repo, alAvisar, alCerrar = { creando = false }, alCrear = { gid -> creando = false; abierto = gid })
    abierto?.let { gid -> DetalleGrupo(gid, repo, alAvisar, alCerrar = { abierto = null; recarga++ }) }
}

@Composable
private fun TarjetaGrupo(g: GrupoRepositorio.GrupoResumen, alPulsar: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Coo.Campo)
            .border(1.dp, Coo.BordeSuave, RoundedCornerShape(18.dp)).clickable(onClick = alPulsar).padding(14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        CirculoIcono(Icons.Outlined.Group, 46.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(g.nombre, color = Coo.Texto, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            Text(
                when {
                    g.invitado -> "Te invitaron · toca para responder"
                    g.pendiente -> "Esperando la aprobación del administrador"
                    g.miembros == 1 -> "1 integrante"
                    else -> "${g.miembros} integrantes"
                },
                color = Coo.TextoTenue, fontSize = 13.sp
            )
        }
        if (g.soyAdmin) Etiqueta("Administras")
        pe.cooruta.app.ui.componentes.IconoAdelante(Modifier.padding(start = 8.dp), Coo.TextoTenue, 20.dp)
    }
}

@Composable
private fun DialogoCrearGrupo(
    repo: GrupoRepositorio, alAvisar: (String) -> Unit, alCerrar: () -> Unit, alCrear: (String) -> Unit
) {
    var nombre by remember { mutableStateOf("") }
    var aprobacion by remember { mutableStateOf(true) }
    var guardando by remember { mutableStateOf(false) }
    val alcance = rememberCoroutineScope()
    val valido = nombre.trim().length in 3..40
    AlertDialog(
        onDismissRequest = { if (!guardando) alCerrar() },
        containerColor = Coo.Campo,
        title = { Text("Crear grupo", color = Coo.Texto, fontWeight = FontWeight.Bold) },
        text = {
            Column {
                CampoTexto(nombre, { nombre = it.take(40) }, "Nombre del grupo (ej. Compañeros de Tecsup)")
                Spacer(Modifier.height(14.dp))
                FilaInterruptor("Aprobar a quien entra", "Quien abra el enlace esperará tu aprobación.", aprobacion) { aprobacion = it }
            }
        },
        confirmButton = {
            TextButton(enabled = valido && !guardando, onClick = {
                guardando = true
                alcance.launch {
                    when (val r = repo.crearGrupo(nombre.trim(), aprobacion)) {
                        is Resultado.Exito -> { alAvisar("Grupo creado. Invita a tus compañeros por WhatsApp."); alCrear(r.valor) }
                        is Resultado.Error -> alAvisar(r.mensaje)
                    }
                    guardando = false
                }
            }) { Text(if (guardando) "Creando…" else "Crear", color = if (valido) Coo.AzulUbicacion else Coo.TextoTenue, fontWeight = FontWeight.Bold) }
        },
        dismissButton = { TextButton(enabled = !guardando, onClick = alCerrar) { Text("Cancelar", color = Coo.Texto) } }
    )
}

/** Confirmaciones del detalle de un grupo. */
private sealed interface Confirmar {
    data object Salir : Confirmar
    data object Renovar : Confirmar
    data object Desactivar : Confirmar
    data class Retirar(val i: GrupoRepositorio.Integrante) : Confirmar
}

@Composable
private fun DetalleGrupo(gid: String, repo: GrupoRepositorio, alAvisar: (String) -> Unit, alCerrar: () -> Unit) {
    val ctx = LocalContext.current
    val alcance = rememberCoroutineScope()
    val g by remember(gid) { repo.grupoEnVivo(gid) }.collectAsStateWithLifecycle(null)
    val activo = g?.activo == true
    val integrantes by remember(gid, activo) { if (activo) repo.integrantesEnVivo(gid) else flowOf(emptyList()) }
        .collectAsStateWithLifecycle(emptyList())
    var ocupado by remember { mutableStateOf(false) }
    var confirmar by remember { mutableStateOf<Confirmar?>(null) }
    var renombrar by remember { mutableStateOf(false) }
    var invitarContactos by remember { mutableStateOf(false) }

    /** Ejecuta una acción del grupo mostrando su resultado. */
    fun accion(ok: String?, alTerminar: () -> Unit = {}, bloque: suspend () -> Resultado<*>) {
        if (ocupado) return
        ocupado = true
        alcance.launch {
            when (val r = bloque()) {
                is Resultado.Exito -> { ok?.let(alAvisar); alTerminar() }
                is Resultado.Error -> alAvisar(r.mensaje)
            }
            ocupado = false
        }
    }

    val gr = g
    PantallaCompleta(gr?.nombre ?: "Grupo", alCerrar, accion = if (gr?.soyAdmin == true) ({ renombrar = true }) else null) {
        if (gr == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Coo.AzulBoton) }
            return@PantallaCompleta
        }
        val yo = repo.miUid
        val pendientes = integrantes.filter { it.pendiente }
        val activos = integrantes.filter { !it.pendiente && !it.invitado }
        val invitados = integrantes.filter { it.invitado }
        LazyColumn(
            Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item {
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                    CirculoIcono(Icons.Outlined.Group, 72.dp)
                    Spacer(Modifier.height(10.dp))
                    Text(gr.nombre, color = Coo.Texto, fontSize = 20.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                    Text(if (gr.miembros == 1) "1 integrante" else "${gr.miembros} integrantes", color = Coo.TextoTenue, fontSize = 14.sp)
                    if (gr.soyAdmin) { Spacer(Modifier.height(6.dp)); Etiqueta("Administras este grupo") }
                }
            }
            if (gr.invitado) item {
                Tarjeta {
                    Text("Te invitaron a este grupo", color = Coo.Texto, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(4.dp))
                    Text("Si aceptas, verás las rutas que se comparten con el grupo. Puedes salir cuando quieras.",
                        color = Coo.TextoTenue, fontSize = 14.sp)
                    Spacer(Modifier.height(12.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextoAccion("Rechazar", Coo.TextoTenue) { accion("Rechazaste la invitación.", alTerminar = alCerrar) { repo.responderInvitacion(gid, false) } }
                        TextoAccion("Aceptar", Coo.AzulUbicacion) { accion("Te uniste a ${gr.nombre}.") { repo.responderInvitacion(gid, true) } }
                    }
                }
            }
            if (gr.pendiente) item {
                Tarjeta {
                    Text("Tu solicitud está pendiente", color = Coo.Texto, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(4.dp))
                    Text("Te avisaremos cuando el administrador la acepte.", color = Coo.TextoTenue, fontSize = 14.sp)
                    Spacer(Modifier.height(12.dp))
                    TextoAccion("Cancelar solicitud", Coo.RojoDestino) { confirmar = Confirmar.Salir }
                }
            }
            if (gr.soyAdmin) item {
                Tarjeta {
                    Text("Invitar", color = Coo.Texto, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(4.dp))
                    Text("Comparte el enlace por WhatsApp. Solo pueden entrar personas de tu misma comunidad.",
                        color = Coo.TextoTenue, fontSize = 13.sp, lineHeight = 18.sp)
                    Spacer(Modifier.height(12.dp))
                    BotonPrincipal(if (ocupado) "Un momento…" else "Invitar por WhatsApp", Icons.Rounded.Share, Modifier.fillMaxWidth(), habilitado = !ocupado) {
                        accion(null) { repo.enlaceGrupo(gid).also { if (it is Resultado.Exito) compartirEnlaceGrupo(ctx, gr.nombre, it.valor) } }
                    }
                    Spacer(Modifier.height(6.dp))
                    FilaAccion(Icons.Rounded.PersonAdd, "Invitar contactos", "Marca a tus contactos; cada uno debe aceptar") { invitarContactos = true }
                    FilaAccion(Icons.Rounded.Refresh, "Renovar enlace", "El enlace anterior dejará de funcionar") { confirmar = Confirmar.Renovar }
                    if (gr.tieneEnlace) FilaAccion(Icons.Rounded.LinkOff, "Desactivar enlace", "Nadie más podrá entrar con él") { confirmar = Confirmar.Desactivar }
                    Spacer(Modifier.height(4.dp))
                    FilaInterruptor("Aprobar a quien entra", "Las solicitudes aparecen aquí para que las aceptes.", gr.requiereAprobacion) {
                        accion(if (it) "Ahora apruebas a quien entra." else "Ahora entran sin aprobación.") { repo.ajustesGrupo(gid, requiereAprobacion = it) }
                    }
                }
            }
            if (gr.soyAdmin && pendientes.isNotEmpty()) {
                item { Titulo("Solicitudes (${pendientes.size})") }
                items(pendientes, key = { "p" + it.uid }) { p ->
                    FilaPersona(p.nombre, "Quiere unirse") {
                        TextoAccion("Rechazar", Coo.TextoTenue) { accion("Solicitud rechazada.") { repo.responderSolicitud(gid, p.uid, false) } }
                        Spacer(Modifier.width(4.dp))
                        TextoAccion("Aceptar", Coo.AzulUbicacion) { accion("${nombrePropio(p.nombre.ifBlank { "Integrante" })} ya es parte del grupo.") { repo.responderSolicitud(gid, p.uid, true) } }
                    }
                }
            }
            if (activo) {
                item { Titulo("Integrantes (${activos.size})") }
                items(activos, key = { "a" + it.uid }) { a ->
                    FilaPersona(a.nombre + if (a.uid == yo) " (tú)" else "", if (a.admin) "Administrador" else null) {
                        if (a.uid != yo) MenuPersona(a.uid, a.nombre, alAvisar, "grupo:$gid")
                        if (gr.soyAdmin && !a.admin && a.uid != yo) Box(
                            Modifier.size(40.dp).clip(CircleShape).clickable { confirmar = Confirmar.Retirar(a) },
                            contentAlignment = Alignment.Center
                        ) { IconoM(Icons.Rounded.Close, Coo.TextoTenue, 18.dp) }
                    }
                }
                if (gr.soyAdmin && invitados.isNotEmpty()) {
                    item { Titulo("Invitados (${invitados.size})") }
                    items(invitados, key = { "i" + it.uid }) { p ->
                        FilaPersona(p.nombre, "Esperando su respuesta") {
                            TextoAccion("Cancelar", Coo.TextoTenue) { accion("Invitación cancelada.") { repo.retirarIntegrante(gid, p.uid) } }
                        }
                    }
                }
                item {
                    Spacer(Modifier.height(8.dp))
                    TextoAccion("Abandonar grupo", Coo.RojoDestino, Modifier.fillMaxWidth()) { confirmar = Confirmar.Salir }
                }
            }
        }
    }

    if (invitarContactos) DialogoInvitarContactos(gid, integrantes.map { it.uid }.toSet(), alAvisar) { invitarContactos = false }
    if (renombrar && gr != null) DialogoRenombrar(gr.nombre, alCerrar = { renombrar = false }) { n ->
        renombrar = false
        accion("Nombre actualizado.") { repo.ajustesGrupo(gid, nombre = n) }
    }
    confirmar?.let { c ->
        val soloAdmin = gr?.soyAdmin == true && integrantes.count { it.admin && !it.pendiente } <= 1
        val (titulo, texto, boton) = when (c) {
            Confirmar.Salir -> if (gr?.pendiente == true) Triple("¿Cancelar tu solicitud?", "Podrás volver a pedir entrar con el enlace.", "Cancelar solicitud")
                else Triple("¿Abandonar ${gr?.nombre ?: "el grupo"}?",
                    if (soloAdmin) "Eres el único administrador: el cargo pasará al integrante más antiguo. Si no queda nadie, el grupo se borrará."
                    else "Dejarás de ver las rutas compartidas solo con este grupo.", "Abandonar")
            Confirmar.Renovar -> Triple("¿Renovar el enlace?", "El enlace que ya compartiste dejará de funcionar y se creará uno nuevo.", "Renovar")
            Confirmar.Desactivar -> Triple("¿Desactivar el enlace?", "Nadie más podrá entrar con él. Puedes crear uno nuevo cuando quieras.", "Desactivar")
            is Confirmar.Retirar -> Triple("¿Retirar a ${nombrePropio(c.i.nombre.ifBlank { "esta persona" })}?",
                "Dejará de ver las rutas compartidas con este grupo. Le avisaremos.", "Retirar")
        }
        AlertDialog(
            onDismissRequest = { confirmar = null },
            containerColor = Coo.Campo,
            title = { Text(titulo, color = Coo.Texto, fontWeight = FontWeight.Bold) },
            text = { Text(texto, color = Coo.TextoTenue) },
            confirmButton = {
                TextButton(onClick = {
                    confirmar = null
                    when (c) {
                        Confirmar.Salir -> accion(if (gr?.pendiente == true) "Cancelaste tu solicitud." else "Saliste del grupo.", alTerminar = alCerrar) { repo.salirDeGrupo(gid) }
                        Confirmar.Renovar -> accion(null) { repo.enlaceGrupo(gid, renovar = true).also { if (it is Resultado.Exito) compartirEnlaceGrupo(ctx, gr?.nombre.orEmpty(), it.valor) } }
                        Confirmar.Desactivar -> accion("Enlace desactivado.") { repo.desactivarEnlace(gid) }
                        is Confirmar.Retirar -> accion("Retiraste a ${nombrePropio(c.i.nombre.ifBlank { "la persona" })}.") { repo.retirarIntegrante(gid, c.i.uid) }
                    }
                }) { Text(boton, color = if (c is Confirmar.Renovar) Coo.AzulUbicacion else Coo.RojoDestino, fontWeight = FontWeight.Bold) }
            },
            dismissButton = { TextButton(onClick = { confirmar = null }) { Text("Volver", color = Coo.Texto) } }
        )
    }
}

@Composable
private fun DialogoRenombrar(actual: String, alCerrar: () -> Unit, alGuardar: (String) -> Unit) {
    var nombre by remember { mutableStateOf(actual) }
    val valido = nombre.trim().length in 3..40 && nombre.trim() != actual
    AlertDialog(
        onDismissRequest = alCerrar,
        containerColor = Coo.Campo,
        title = { Text("Nombre del grupo", color = Coo.Texto, fontWeight = FontWeight.Bold) },
        text = { CampoTexto(nombre, { nombre = it.take(40) }, "Nombre del grupo") },
        confirmButton = {
            TextButton(enabled = valido, onClick = { alGuardar(nombre.trim()) }) {
                Text("Guardar", color = if (valido) Coo.AzulUbicacion else Coo.TextoTenue, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = { TextButton(onClick = alCerrar) { Text("Cancelar", color = Coo.Texto) } }
    )
}

/** Abre WhatsApp con el mensaje y el enlace; si no está instalado, el menú de compartir. */
private fun compartirEnlaceGrupo(ctx: Context, nombre: String, enlace: String) {
    val texto = "Únete a mi grupo «$nombre» en CooRuta para compartir viajes. Ábrelo desde tu celular: $enlace\n" +
        "Si aún no tienes la app, instálala, regístrate y vuelve a abrir este enlace."
    val base = Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_TEXT, texto) }
    runCatching { ctx.startActivity(Intent(base).setPackage("com.whatsapp")) }
        .onFailure { ctx.startActivity(Intent.createChooser(base, "Invitar a mi grupo")) }
}

/**
 * Quien abrió un enlace de invitación: ve el grupo y decide si unirse. Los enlaces antiguos
 * (grupo de un conductor, de un solo uso) siguen funcionando como antes.
 */
@Composable
fun DialogoInvitacionGrupo(alAvisar: (String) -> Unit) {
    val codigo by InvitacionPendiente.codigo.collectAsStateWithLifecycle()
    val c = codigo ?: return
    val repo = remember { GrupoRepositorio() }
    val alcance = rememberCoroutineScope()
    var info by remember(c) { mutableStateOf<GrupoRepositorio.InvitacionGrupo?>(null) }
    var antigua by remember(c) { mutableStateOf(false) }
    var uniendo by remember { mutableStateOf(false) }

    LaunchedEffect(c) {
        when (val r = repo.verInvitacion(c)) {
            is Resultado.Exito -> when (r.valor.estado) {
                "activo" -> { InvitacionPendiente.descartar(); alAvisar("Ya eres parte de ${r.valor.nombre}.") }
                "pendiente" -> { InvitacionPendiente.descartar(); alAvisar("Tu solicitud a ${r.valor.nombre} sigue pendiente.") }
                else -> info = r.valor
            }
            // Código que no es de un grupo nuevo: puede ser una invitación antigua de un conductor.
            is Resultado.Error -> if (r.mensaje.contains("ya no es válida")) antigua = true
                else { InvitacionPendiente.descartar(); alAvisar(r.mensaje) }
        }
    }

    val i = info
    if (i == null && !antigua) return
    AlertDialog(
        onDismissRequest = { if (!uniendo) InvitacionPendiente.descartar() },
        containerColor = Coo.Campo,
        icon = { IconoM(Icons.Rounded.GroupAdd, Coo.AzulUbicacion, 32.dp) },
        title = { Text(if (i != null) "Te invitaron a «${i.nombre}»" else "Te invitaron a un grupo", color = Coo.Texto, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center) },
        text = {
            Text(
                if (i != null) buildString {
                    append(if (i.miembros == 1) "1 integrante. " else "${i.miembros} integrantes. ")
                    append("Al unirte verás las rutas que se comparten con este grupo y podrás pedir asiento; cada viaje lo confirma el conductor.")
                    if (i.requiereAprobacion) append("\n\nEl administrador deberá aprobar tu ingreso.")
                } else "Al unirte verás las rutas privadas de este conductor en Buscar y podrás pedirle asiento. Puedes salir cuando quieras.",
                color = Coo.TextoTenue
            )
        },
        confirmButton = {
            TextButton(enabled = !uniendo, onClick = {
                uniendo = true
                alcance.launch {
                    if (i != null) when (val r = repo.unirseGrupo(c)) {
                        is Resultado.Exito -> {
                            InvitacionPendiente.unido()
                            alAvisar(if (r.valor.second == "pendiente") "Enviamos tu solicitud a ${r.valor.first}. Te avisaremos cuando la acepten."
                                else "Te uniste a ${r.valor.first}.")
                        }
                        is Resultado.Error -> { InvitacionPendiente.descartar(); alAvisar(r.mensaje) }
                    } else when (val r = repo.aceptar(c)) {
                        is Resultado.Exito -> {
                            InvitacionPendiente.unido()
                            alAvisar("Te uniste al grupo de ${nombrePropio(r.valor)}. Sus rutas ya aparecen en Buscar.")
                        }
                        is Resultado.Error -> { InvitacionPendiente.descartar(); alAvisar(r.mensaje) }
                    }
                    uniendo = false
                }
            }) { Text(if (uniendo) "Uniendo…" else if (i?.requiereAprobacion == true) "Pedir unirme" else "Unirme", color = Coo.AzulUbicacion, fontWeight = FontWeight.Bold) }
        },
        dismissButton = {
            TextButton(enabled = !uniendo, onClick = { InvitacionPendiente.descartar() }) { Text("Ahora no", color = Coo.Texto) }
        }
    )
}

// ------------------------------------------------------------ piezas

@Composable
internal fun PantallaCompleta(titulo: String, alVolver: () -> Unit, accion: (() -> Unit)? = null, contenido: @Composable BoxScope.() -> Unit) {
    Dialog(onDismissRequest = alVolver, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.fillMaxSize().background(Coo.Fondo).statusBarsPadding().navigationBarsPadding()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(44.dp).clip(CircleShape).clickable(onClick = alVolver), contentAlignment = Alignment.Center) { IconoAtras() }
                Text(titulo, color = Coo.Texto, fontSize = 20.sp, fontWeight = FontWeight.Medium, maxLines = 1,
                    modifier = Modifier.weight(1f).padding(start = 8.dp))
                if (accion != null) Box(Modifier.size(44.dp).clip(CircleShape).clickable(onClick = accion), contentAlignment = Alignment.Center) {
                    IconoM(Icons.Rounded.Edit, Coo.TextoTenue, 22.dp)
                }
            }
            Box(Modifier.weight(1f).fillMaxWidth(), content = contenido)
        }
    }
}

@Composable
internal fun CirculoIcono(icono: ImageVector, tamano: androidx.compose.ui.unit.Dp) {
    Box(Modifier.size(tamano).clip(CircleShape).background(Coo.AzulBoton.copy(alpha = 0.18f)), contentAlignment = Alignment.Center) {
        IconoM(icono, Coo.AzulUbicacion, tamano * 0.5f)
    }
}

@Composable
private fun BotonPrincipal(texto: String, icono: ImageVector, modifier: Modifier = Modifier, habilitado: Boolean = true, alPulsar: () -> Unit) {
    Row(
        modifier.height(50.dp).clip(RoundedCornerShape(14.dp))
            .background(if (habilitado) Brush.horizontalGradient(listOf(AZUL_CLARO, Coo.AzulBoton, AZUL_HONDO)) else SolidColor(Coo.CampoActivo))
            .clickable(enabled = habilitado, onClick = alPulsar).padding(horizontal = 22.dp),
        horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically
    ) {
        IconoM(icono, Coo.SobreBoton, 20.dp)
        Spacer(Modifier.width(8.dp))
        Text(texto, color = Coo.SobreBoton, fontSize = 15.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun Tarjeta(contenido: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Coo.Campo)
            .border(1.dp, Coo.BordeSuave, RoundedCornerShape(18.dp)).padding(16.dp),
        content = contenido
    )
}

@Composable
private fun Titulo(texto: String) {
    Text(texto.uppercase(), color = Coo.TextoTenue, fontSize = 13.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp,
        modifier = Modifier.padding(top = 8.dp))
}

@Composable
private fun Etiqueta(texto: String) {
    Text(texto, color = Coo.AzulUbicacion, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
        modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(Coo.AzulBoton.copy(alpha = 0.15f)).padding(horizontal = 8.dp, vertical = 3.dp))
}

@Composable
private fun TextoAccion(texto: String, color: Color, modifier: Modifier = Modifier, alPulsar: () -> Unit) {
    Text(texto, color = color, fontSize = 15.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center,
        modifier = modifier.clip(RoundedCornerShape(10.dp)).clickable(onClick = alPulsar).padding(horizontal = 12.dp, vertical = 10.dp))
}

@Composable
private fun FilaAccion(icono: ImageVector, titulo: String, detalle: String, alPulsar: () -> Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable(onClick = alPulsar).padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically) {
        IconoM(icono, Coo.TextoTenue, 22.dp)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(titulo, color = Coo.Texto, fontSize = 15.sp)
            Text(detalle, color = Coo.TextoTenue, fontSize = 12.sp)
        }
    }
}

@Composable
private fun FilaInterruptor(titulo: String, detalle: String, activo: Boolean, alCambiar: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(titulo, color = Coo.Texto, fontSize = 15.sp)
            Text(detalle, color = Coo.TextoTenue, fontSize = 12.sp, lineHeight = 16.sp)
        }
        Spacer(Modifier.width(10.dp))
        Switch(checked = activo, onCheckedChange = alCambiar,
            colors = SwitchDefaults.colors(checkedTrackColor = Coo.AzulBoton, checkedThumbColor = Coo.SobreBoton))
    }
}

@Composable
private fun FilaPersona(nombre: String, detalle: String?, acciones: @Composable () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Coo.Campo).padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Avatar(null, nombre.ifBlank { "?" }, 36.dp)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(nombrePropio(nombre.ifBlank { "Integrante" }), color = Coo.Texto, fontSize = 15.sp)
            if (detalle != null) Text(detalle, color = Coo.TextoTenue, fontSize = 12.sp)
        }
        acciones()
    }
}

@Composable
private fun CampoTexto(valor: String, alCambiar: (String) -> Unit, etiqueta: String) {
    OutlinedTextField(
        value = valor, onValueChange = alCambiar, singleLine = true, label = { Text(etiqueta) },
        modifier = Modifier.fillMaxWidth(),
        colors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = Coo.Texto, unfocusedTextColor = Coo.Texto, focusedBorderColor = Coo.AzulBoton,
            unfocusedBorderColor = Coo.Borde, focusedLabelColor = Coo.AzulUbicacion, unfocusedLabelColor = Coo.TextoTenue,
            cursorColor = Coo.AzulUbicacion
        )
    )
}
