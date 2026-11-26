package pe.cooruta.app.ui.inicio

import androidx.compose.material.icons.outlined.Contacts
import androidx.compose.material.icons.outlined.Group
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.Flag
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import pe.cooruta.app.BuildConfig
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import pe.cooruta.app.ui.componentes.IconoAlerta
import pe.cooruta.app.ui.componentes.IconoBuscar
import pe.cooruta.app.ui.componentes.IconoCheck
import pe.cooruta.app.ui.componentes.IconoIntercambiar
import pe.cooruta.app.ui.componentes.IconoPerfil
import pe.cooruta.app.ui.componentes.IconoReloj
import pe.cooruta.app.ui.componentes.IconoViajes
import pe.cooruta.app.ui.theme.Coo

private const val TEXTO_FAQ =
    "¿Cuánto cuesta? S/ 0,25 por km de tu tramo, mínimo S/ 2, redondeado a S/ 0,50. Se paga al conductor como acuerden.\n\n" +
    "¿Quién ve mi dirección? Nadie. En Buscar solo aparece el tramo público que el conductor eligió.\n\n" +
    "¿Cómo reservo? En Buscar elige una ruta y toca «Solicitar asiento». El conductor acepta o rechaza.\n\n" +
    "¿Puedo cancelar? Sí, desde Solicitudes. Si estaba aceptada, el asiento se libera al instante."

private const val TEXTO_VERIFICACION =
    "Entras con tu cuenta de Google, así que tu correo ya está verificado por Google.\n\n" +
    "Si tu empresa o universidad te invitó, verás tu comunidad en la tarjeta «Mi empresa» de esta pestaña."

internal const val URL_PRIVACIDAD = "https://cooruta-36603.web.app/politica-privacidad/"

/** Pestaña Perfil del menú principal: datos de la cuenta, ajustes y ayuda. */
@Composable
fun PerfilPestana(
    nombre: String,
    esConductor: Boolean,
    alEditarPerfil: (String) -> Unit,
    alCambiarRol: () -> Unit,
    alEliminarCuenta: () -> Unit,
    eliminando: Boolean,
    alAvisar: (String) -> Unit,
    alCerrarSesion: () -> Unit,
    modifier: Modifier = Modifier
) {
    val enlaces = LocalUriHandler.current
    var confirmar by rememberSaveable { mutableStateOf(false) }
    // "faq" o "verificacion": hojas de ayuda con contenido real (antes decían «Muy pronto»).
    var ayuda by rememberSaveable { mutableStateOf<String?>(null) }
    var vehiculo by rememberSaveable { mutableStateOf(false) }
    var verConductores by rememberSaveable { mutableStateOf(false) }
    var verGrupos by rememberSaveable { mutableStateOf(false) }
    var avisoUbicacion by rememberSaveable { mutableStateOf(false) }
    var verContactos by rememberSaveable { mutableStateOf(false) }
    var verBloqueados by rememberSaveable { mutableStateOf(false) }
    var reportarProblema by rememberSaveable { mutableStateOf(false) }
    val vmPerfil: MiPerfilViewModel = viewModel(factory = MiPerfilViewModel.Fabrica)
    val mp by vmPerfil.estado.collectAsStateWithLifecycle()
    androidx.compose.runtime.LaunchedEffect(Unit) { vmPerfil.cargar() }
    val ctxFoto = androidx.compose.ui.platform.LocalContext.current
    val elegirFoto = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) vmPerfil.cambiarFoto(ctxFoto, uri, alAvisar)
    }
    var editar by rememberSaveable { mutableStateOf<String?>(null) }
    when (editar) {
        "acerca" -> DialogoTexto("Acerca de mí", mp.perfil?.acercaDe.orEmpty(), "Cuéntale a la comunidad sobre ti",
            "Lo ven quienes viajan contigo", 200, guardando = mp.guardando,
            alGuardar = { vmPerfil.guardarAcercaDe(it, alAvisar) }, alCerrar = { editar = null })
        "celular" -> DialogoTexto("Número de celular", mp.telefono, "Celular", "Privado: solo tú lo ves", 16, numerico = true,
            guardando = mp.guardando, alGuardar = { vmPerfil.guardarTelefono(it, alAvisar) }, alCerrar = { editar = null })
        "nombre" -> DialogoTexto("Editar nombre", nombre, "Nombre completo", "Así te verán en CooRuta", 60,
            guardando = mp.guardando, alGuardar = { n ->
                if (n.isNotBlank()) { alEditarPerfil(n.trim()); vmPerfil.guardarNombre(n) } }, alCerrar = { editar = null })
        "preferencias" -> DialogoPreferencias(mp.perfil?.preferencias.orEmpty(), mp.guardando,
            alGuardar = { vmPerfil.guardarPreferencias(it, alAvisar) }, alCerrar = { editar = null })
    }
    if (vehiculo) DialogoVehiculo(nombre, alAvisar, alCerrar = { vehiculo = false; vmPerfil.cargar() })
    if (verGrupos) PantallaGrupos(alCerrar = { verGrupos = false }, alAvisar = alAvisar)
    if (verContactos) PantallaContactos(alCerrar = { verContactos = false }, alAvisar = alAvisar)
    if (verBloqueados) PantallaBloqueados(alCerrar = { verBloqueados = false }, alAvisar = alAvisar)
    if (reportarProblema) DialogoReportar("", null, "perfil", alAvisar) { reportarProblema = false }
    // Pasajero: sus conductores favoritos, a pantalla completa.
    if (verConductores) androidx.compose.ui.window.Dialog(
        onDismissRequest = { verConductores = false },
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Column(Modifier.fillMaxSize().background(Coo.Fondo).statusBarsPadding().navigationBarsPadding()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(44.dp).clip(CircleShape).clickable { verConductores = false }, contentAlignment = Alignment.Center) {
                    pe.cooruta.app.ui.componentes.IconoAtras()
                }
                Text("Mis conductores", color = Coo.Texto, fontSize = 20.sp, fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(start = 8.dp))
            }
            MisConductoresPestana(alAvisar = alAvisar, modifier = Modifier.weight(1f).fillMaxWidth())
        }
    }
    ayuda?.let { tipo ->
        val (titulo, cuerpo) = if (tipo == "faq") "Preguntas frecuentes" to TEXTO_FAQ
        else "Verificación de cuenta" to TEXTO_VERIFICACION
        AlertDialog(
            onDismissRequest = { ayuda = null },
            confirmButton = { TextButton(onClick = { ayuda = null }) { Text("Entendido") } },
            title = { Text(titulo, fontWeight = FontWeight.Bold) },
            text = { Text(cuerpo, color = Coo.TextoTenue, fontSize = 14.sp) },
            containerColor = Coo.Campo
        )
    }

    Column(modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {

        // ---------- encabezado (foto: toca para cambiarla; se elige con el selector del sistema, sin permisos)
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 16.dp)) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                var elegirImagen by remember { mutableStateOf(false) }
                if (elegirImagen) DialogoElegirFoto(
                    alElegir = { n -> elegirImagen = false; vmPerfil.elegirAvatar(n, alAvisar) },
                    alGaleria = {
                        elegirImagen = false
                        elegirFoto.launch(androidx.activity.result.PickVisualMediaRequest(
                            androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia.ImageOnly))
                    },
                    alQuitar = { elegirImagen = false; vmPerfil.quitarFoto(alAvisar) },
                    alCerrar = { elegirImagen = false }
                )
                // Foto con botón «+» celeste abajo a la derecha (borde blanco), en lugar de «Cambiar foto».
                androidx.compose.foundation.layout.Box(
                    Modifier.size(80.dp).clickable(enabled = !mp.guardando) { elegirImagen = true }
                ) {
                    Avatar(mp.perfil?.foto, nombre, 80.dp, 32.sp)
                    androidx.compose.foundation.layout.Box(
                        Modifier.align(Alignment.BottomEnd).size(28.dp).clip(CircleShape)
                            .background(androidx.compose.ui.graphics.Color.White).padding(3.dp)
                            .clip(CircleShape).background(androidx.compose.ui.graphics.Color(0xFF34C3F0)),
                        contentAlignment = Alignment.Center
                    ) {
                        if (mp.guardando) androidx.compose.material3.CircularProgressIndicator(
                            color = androidx.compose.ui.graphics.Color.White, strokeWidth = 2.dp, modifier = Modifier.size(14.dp))
                        else Text("+", color = androidx.compose.ui.graphics.Color.White, fontSize = 20.sp,
                            fontWeight = FontWeight.Bold, lineHeight = 20.sp)
                    }
                }
            }
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(nombre.ifBlank { "Sin nombre" }, color = Coo.Texto,
                    style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(4.dp))
                // Modo entre paréntesis; la calificación solo si ya tiene.
                Text("(${if (esConductor) "Conductor" else "Pasajero"})" +
                    (mp.perfil?.takeIf { it.cantidad > 0 }?.let { " · ${it.calificacionTexto()}" } ?: ""),
                    color = Coo.TextoTenue, fontSize = 14.sp)
            }
            // Modo actual; al tocarlo se confirma el cambio.
            var confirmarModo by remember { mutableStateOf(false) }
            // Texto simple, alineado arriba a la derecha junto al nombre.
            Column(horizontalAlignment = Alignment.End,
                // Centrado verticalmente con el nombre, un poco más grande.
                modifier = Modifier.align(Alignment.CenterVertically).padding(bottom = 20.dp).clip(RoundedCornerShape(10.dp))
                    .clickable { confirmarModo = true }.padding(horizontal = 8.dp, vertical = 6.dp)) {
                Text("⇄ Cambiar", color = Coo.AzulUbicacion, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            }
            if (confirmarModo) AlertDialog(
                onDismissRequest = { confirmarModo = false },
                containerColor = Coo.Campo,
                title = { Text(if (esConductor) "¿Cambiar a modo pasajero?" else "¿Cambiar a modo conductor?", fontWeight = FontWeight.Bold) },
                text = {
                    Text(if (esConductor) "Verás rutas de otros conductores para pedir asiento. Tus rutas publicadas siguen activas."
                        else "Podrás publicar tus rutas y aceptar pasajeros. Tus trayectos guardados se conservan.",
                        color = Coo.TextoTenue)
                },
                confirmButton = { TextButton(onClick = { confirmarModo = false; alCambiarRol() }) { Text("Cambiar", fontWeight = FontWeight.Bold) } },
                dismissButton = { TextButton(onClick = { confirmarModo = false }) { Text("Cancelar", color = Coo.Texto) } }
            )
        }


        // ---------- MI PERFIL
        Bloque("MI PERFIL") {
            Fila("Nombre", { IconoPerfil(color = Coo.TextoTenue) }, { editar = "nombre" }, detalle = nombre)
            Separador()
            Fila("Acerca de mí", { IconoPerfil(color = Coo.TextoTenue) }, { editar = "acerca" },
                detalle = mp.perfil?.acercaDe?.takeIf { it.isNotBlank() } ?: "Agrega una descripción corta")
            Separador()
            Fila("Número de celular", { IconoCheck(color = Coo.TextoTenue) }, { editar = "celular" },
                detalle = mp.telefono.ifBlank { "Agregar (privado)" })
            Separador()
            Fila("Preferencias de viaje", { IconoViajes(color = Coo.TextoTenue) }, { editar = "preferencias" },
                detalle = mp.perfil?.preferencias?.takeIf { it.isNotEmpty() }?.joinToString(" · ") ?: "No se fuma, mascotas, música…")
        }

        // ---------- VEHÍCULO (conductor)
        if (esConductor) Bloque("VEHÍCULO") {
            Fila("Mi vehículo", { IconoViajes(color = Coo.TextoTenue) }, { vehiculo = true },
                detalle = mp.perfil?.vehiculo?.resumen()?.ifBlank { null } ?: "Marca, modelo, color y placa")
        }

        // ---------- COMUNIDAD
        Bloque("COMUNIDAD") {
            TarjetaEmpresa(mp.perfil, vmPerfil.miDominio, mp.guardando) { n, v -> vmPerfil.guardarEmpresa(n, v, alAvisar) }
            Separador()
            Fila("Mis grupos", { pe.cooruta.app.ui.componentes.IconoM(androidx.compose.material.icons.Icons.Outlined.Group, Coo.TextoTenue, 22.dp) },
                { verGrupos = true }, detalle = "Crea grupos e invita por WhatsApp")
            Fila("Mis contactos", { pe.cooruta.app.ui.componentes.IconoM(androidx.compose.material.icons.Icons.Outlined.Contacts, Coo.TextoTenue, 22.dp) },
                { verContactos = true }, detalle = "Personas que puedes invitar a tus grupos")
            Fila("Usuarios bloqueados", { pe.cooruta.app.ui.componentes.IconoM(androidx.compose.material.icons.Icons.Rounded.Block, Coo.TextoTenue, 22.dp) },
                { verBloqueados = true }, detalle = "No ves sus rutas ni pueden pedirse asiento")
            if (!esConductor) {
                Fila("Mis conductores", { Text("★", color = Coo.TextoTenue, fontSize = 18.sp) }, { verConductores = true },
                    detalle = "Tus conductores favoritos")
                Separador()
            }
            Fila("Verificación de cuenta", { IconoCheck(color = Coo.TextoTenue) }, { ayuda = "verificacion" },
                detalle = "Cuenta de Google verificada")
        }

        // ---------- CONFIGURACIÓN
        val ctx = androidx.compose.ui.platform.LocalContext.current
        var avisosActivos by rememberSaveable { mutableStateOf(pe.cooruta.app.AvisosLocales.activos(ctx)) }
        var permisoAvisos by remember { mutableStateOf(pe.cooruta.app.AvisosLocales.permitido(ctx)) }
        val pedirPermiso = androidx.activity.compose.rememberLauncherForActivityResult(
            androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) { ok ->
            permisoAvisos = ok
            if (!ok) alAvisar("Actívalas desde Ajustes del teléfono › Apps › CooRuta › Notificaciones.")
        }
        fun abrirAjustesApp() = runCatching {
            ctx.startActivity(android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                android.net.Uri.fromParts("package", ctx.packageName, null)))
        }
        Bloque("CONFIGURACIÓN") {
            Fila("Notificaciones", { IconoReloj(color = Coo.TextoTenue) }, {
                if (!permisoAvisos && android.os.Build.VERSION.SDK_INT >= 33) pedirPermiso.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                else {
                    avisosActivos = !avisosActivos
                    pe.cooruta.app.AvisosLocales.cambiarActivos(ctx, avisosActivos)
                    alAvisar(if (avisosActivos) "Avisos activados." else "Avisos desactivados.")
                }
            }, detalle = when {
                !permisoAvisos -> "Sin permiso · toca para permitir"
                avisosActivos -> "Activadas · solicitudes y respuestas"
                else -> "Desactivadas"
            })
            Separador()
            if (avisoUbicacion) pe.cooruta.app.ui.componentes.AvisoUbicacion(
                alContinuar = { avisoUbicacion = false; abrirAjustesApp() },
                alCancelar = { avisoUbicacion = false }
            )
            Fila("Ubicación", { IconoBuscar(color = Coo.TextoTenue) }, {
                if (pe.cooruta.app.ServicioUbicacion.enSegundoPlano(ctx)) abrirAjustesApp() else avisoUbicacion = true
            }, detalle = when {
                !pe.cooruta.app.data.UbicacionEnVivo.tienePermiso(ctx) -> "Sin permiso · toca para permitir"
                pe.cooruta.app.ServicioUbicacion.enSegundoPlano(ctx) -> "Permitida todo el tiempo"
                else -> "Solo mientras usas la app (recomendado: todo el tiempo)"
            })
            Separador()
            Fila("Idioma", { IconoBuscar(color = Coo.TextoTenue) }, null, detalle = "Español")
        }

        // ---------- AYUDA
        Bloque("AYUDA") {
            Fila("Preguntas frecuentes", { IconoAlerta(color = Coo.TextoTenue) }, { ayuda = "faq" })
            Fila("Reportar un problema", { pe.cooruta.app.ui.componentes.IconoM(androidx.compose.material.icons.Icons.Rounded.Flag, Coo.TextoTenue, 22.dp) },
                { reportarProblema = true }, detalle = "Cuéntanos si algo no funciona")
            Separador()
            Fila("Política de privacidad", { IconoAlerta(color = Coo.TextoTenue) }, { enlaces.openUri(URL_PRIVACIDAD) })
            Separador()
            Fila("Acerca de CooRuta", { IconoAlerta(color = Coo.TextoTenue) },
                { alAvisar("CooRuta · Conectamos trayectos, transformamos rutinas.") }, detalle = "Versión 1.0")
        }

        // ---------- SESIÓN
        Bloque("SESIÓN") {
            Text(
                "Cerrar sesión", color = Coo.RojoError, fontWeight = FontWeight.Bold,
                modifier = Modifier.fillMaxWidth().clickable(onClick = alCerrarSesion).padding(vertical = 16.dp)
            )
            Separador()
            Text(
                if (eliminando) "Eliminando cuenta…" else "Eliminar cuenta", color = Coo.TextoTenue, fontSize = 14.sp,
                modifier = Modifier.fillMaxWidth().clickable(enabled = !eliminando) { confirmar = true }.padding(vertical = 14.dp)
            )
        }
        Spacer(Modifier.height(96.dp))
    }

    if (confirmar) {
        AlertDialog(
            onDismissRequest = { confirmar = false },
            containerColor = Coo.Campo,
            title = { Text("¿Eliminar tu cuenta?", color = Coo.Texto, fontWeight = FontWeight.Bold) },
            text = {
                Text(
                    "Se borrarán tu cuenta, tus rutas publicadas y tus rutas guardadas. No se puede deshacer.",
                    color = Coo.TextoTenue
                )
            },
            confirmButton = {
                TextButton(onClick = { confirmar = false; alEliminarCuenta() }) {
                    Text("Eliminar", color = Coo.RojoError, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmar = false }) { Text("Cancelar", color = Coo.Texto) }
            }
        )
    }
}

@Composable
private fun Etiqueta(texto: String) {
    Text(
        texto, color = Coo.Texto, fontSize = 12.sp, fontWeight = FontWeight.Bold,
        modifier = Modifier.clip(RoundedCornerShape(50)).background(Coo.Campo)
            .padding(horizontal = 10.dp, vertical = 3.dp)
    )
}

@Composable
private fun Seccion(titulo: String) {
    Spacer(Modifier.height(20.dp))
    Text(titulo, color = Coo.TextoTenue, fontSize = 13.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
    Spacer(Modifier.height(8.dp))
}

@Composable
private fun Fila(
    titulo: String,
    icono: @Composable () -> Unit,
    alPulsar: (() -> Unit)?,
    detalle: String? = null
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth()
            .then(if (alPulsar != null) Modifier.clickable(onClick = alPulsar) else Modifier)
            .padding(vertical = 14.dp)
    ) {
        icono()
        Spacer(Modifier.width(20.dp))
        Column(Modifier.weight(1f)) {
            Text(titulo, color = Coo.Texto, fontSize = 16.sp)
            if (detalle != null) Text(detalle, color = Coo.TextoTenue, fontSize = 13.sp)
        }
        if (alPulsar != null) pe.cooruta.app.ui.componentes.IconoAdelante(color = Coo.TextoTenue, tamano = 20.dp)
    }
    HorizontalDivider(color = Coo.BordeSuave)
}


/** Bloque de ajustes: título pequeño y tarjeta redondeada con sus filas. */
@Composable
private fun Bloque(titulo: String?, contenido: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    if (titulo != null) Seccion(titulo) else Spacer(Modifier.height(10.dp))
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Coo.Campo)
            .border(1.dp, Coo.BordeSuave, RoundedCornerShape(16.dp)).padding(horizontal = 16.dp),
        content = contenido
    )
}

@Composable
private fun Separador() = HorizontalDivider(color = Coo.BordeSuave)
