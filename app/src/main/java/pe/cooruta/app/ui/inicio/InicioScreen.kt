package pe.cooruta.app.ui.inicio

import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Badge
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import pe.cooruta.app.ui.componentes.BarraPildora
import pe.cooruta.app.ui.componentes.IconoLupa
import pe.cooruta.app.ui.componentes.IconoPersona
import pe.cooruta.app.ui.componentes.IconoVolante
import pe.cooruta.app.ui.componentes.IconoRuta
import pe.cooruta.app.ui.componentes.IconoNavegar
import pe.cooruta.app.ui.componentes.IconoSolicitudes
import pe.cooruta.app.ui.componentes.IconoAuto
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import pe.cooruta.app.ui.componentes.IconoMas
import pe.cooruta.app.ui.viaje.ViajePestana
import pe.cooruta.app.ui.theme.Coo

/** Pestañas de la barra inferior. */
// INICIAR va al centro de la barra: viaje en curso (conductor) o seguimiento de la reserva (pasajero).
enum class Pestana(val titulo: String) { BUSCAR("Buscar"), SOLICITUDES("Solicitudes"), INICIAR("Iniciar"), VIAJES("Mis viajes"), CONDUCTORES("Mis conductores"), PERFIL("Perfil") }

/**
 * Menú principal: TopAppBar, NavigationBar (Buscar · Mis viajes · Perfil) y
 * FAB para publicar/guardar una ruta. Solo pinta y avisa toques.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InicioScreen(
    nombre: String,
    esConductor: Boolean,
    alPublicar: () -> Unit,
    alEditarRuta: (String, pe.cooruta.app.data.BorradorRuta, String) -> Unit,
    alEditarPerfil: (String) -> Unit,
    alCambiarRol: () -> Unit,
    alEliminarCuenta: () -> Unit,
    eliminando: Boolean,
    alAvisar: (String) -> Unit,
    alCerrarSesion: () -> Unit
) {
    var pestana by rememberSaveable { mutableStateOf(Pestana.BUSCAR) }
    // Trayecto elegido en Mis viajes para buscar conductores hoy.
    var usarHoyId by rememberSaveable { mutableStateOf<String?>(null) }
    // El conductor no busca rutas: su primera pestaña es su propia ruta.
    // Solicitudes existe para ambos roles; el contador de pendientes sale del ViewModel de Mi ruta (conductor).
    // Conductor: «Mis rutas» y «Rutas» se fusionaron en la primera pestaña.
    // Pasajero: Perfil va arriba (icono) y en su lugar está «Mis conductores».
    // Perfil va arriba (con la campana) para ambos roles.
    val pestanas = if (esConductor) listOf(Pestana.BUSCAR, Pestana.INICIAR, Pestana.SOLICITUDES) else listOf(Pestana.BUSCAR, Pestana.SOLICITUDES, Pestana.INICIAR, Pestana.VIAJES)
    val ctxAvisos = androidx.compose.ui.platform.LocalContext.current
    LaunchedEffect(Unit) { pe.cooruta.app.AvisosLocales.actualizarNoLeidos(ctxAvisos) }
    val noLeidos by pe.cooruta.app.AvisosLocales.noLeidos.collectAsStateWithLifecycle()
    var verAvisos by remember { mutableStateOf(false) }
    // Mismo ViewModel que la pestaña Perfil: al cambiar la foto, se actualiza también arriba.
    val vmMiPerfil: MiPerfilViewModel = viewModel(factory = MiPerfilViewModel.Fabrica)
    val miPerfil by vmMiPerfil.estado.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { vmMiPerfil.cargar() }
    if (verAvisos) PantallaNotificaciones { verAvisos = false }
    // Enlace de invitación al grupo de un conductor (llegó al abrir la app o con la app abierta).
    DialogoInvitacionGrupo(alAvisar)
    val vmRuta: MiRutaViewModel? = if (esConductor) viewModel(factory = MiRutaViewModel.Fabrica) else null
    val pendientes = vmRuta?.estado?.collectAsStateWithLifecycle()?.value?.pendientes?.size ?: 0
    if (vmRuta != null) LaunchedEffect(Unit) { vmRuta.cargar() }
    // Avisos locales de reservas; en Android 13+ se pide el permiso una vez al entrar.
    val vmAvisos: AvisosViewModel = viewModel()
    LaunchedEffect(esConductor) { vmAvisos.escuchar(esConductor) }
    // Mensaje nuevo con la app abierta: aviso emergente arriba; tocarlo abre el chat.
    val entrante by vmAvisos.entrante.collectAsStateWithLifecycle()
    var chatDesdeAviso by remember { mutableStateOf<MensajeEntrante?>(null) }
    entrante?.let { m -> BannerMensaje(m, alAbrir = { chatDesdeAviso = m; vmAvisos.descartarEntrante() }, alCerrar = vmAvisos::descartarEntrante) }
    chatDesdeAviso?.let { m -> PantallaChat(m.s, m.otro, alCerrar = { chatDesdeAviso = null }) }
    // Pasajero y conductor: ubicación en tiempo real (también en segundo plano con «Permitir todo el tiempo»).
    PermisosPasajero()
    // Las ventanas de Android de ubicación y notificaciones las pide PermisosPasajero, una tras otra.
    fun titulo(p: Pestana) = when {
        p == Pestana.BUSCAR && esConductor -> "Mis rutas"
        p == Pestana.INICIAR && !esConductor -> "Mi viaje"
        // Ruta recurrente o trayecto guardado (no un viaje de una fecha): nombres distintos de «Mi viaje».
        p == Pestana.VIAJES -> if (esConductor) "Mis rutas" else "Mis trayectos"
        else -> p.titulo
    }

    Scaffold(
        containerColor = Coo.Fondo,
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text(titulo(pestana), fontWeight = FontWeight.Bold) },
                actions = {
                    // Notificaciones y perfil, a la derecha (ambos roles).
                    androidx.compose.material3.IconButton(onClick = { verAvisos = true }) {
                        BadgedBox(badge = { if (noLeidos > 0) Badge(containerColor = Coo.RojoDestino) { Text("$noLeidos") } }) {
                            IconoCampana(color = Coo.Texto)
                        }
                    }
                    androidx.compose.material3.IconButton(onClick = { pestana = Pestana.PERFIL }) {
                        // Su foto de perfil (o la inicial), con borde azul si Perfil está abierto.
                        Avatar(miPerfil.perfil?.foto, nombre, 32.dp, 14.sp,
                            if (pestana == Pestana.PERFIL) Modifier.border(2.dp, Coo.AzulUbicacion, androidx.compose.foundation.shape.CircleShape) else Modifier)
                    }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = Coo.Fondo,
                    titleContentColor = Coo.Texto
                )
            )
        },
        bottomBar = {
            // Barra flotante tipo píldora: la pestaña activa se ensancha con su nombre (ver BarraPildora).
            BarraPildora(
                cantidad = pestanas.size,
                seleccion = pestanas.indexOf(pestana).coerceAtLeast(0),
                alElegir = { pestana = pestanas[it] },
                etiqueta = { i ->
                    when (val p = pestanas[i]) {
                        Pestana.VIAJES -> if (esConductor) "Rutas" else "Trayectos"
                        Pestana.CONDUCTORES -> "Conductores"
                        else -> titulo(p)
                    }
                }
            ) { i, colorIcono, activo ->
                when (pestanas[i]) {
                    Pestana.BUSCAR -> if (esConductor) IconoAuto(color = colorIcono, lleno = activo)
                    else IconoLupa(color = colorIcono, lleno = activo)
                    Pestana.SOLICITUDES -> BadgedBox(badge = {
                        if (pendientes > 0) Badge(containerColor = Coo.RojoDestino) { Text("$pendientes") }
                    }) { IconoSolicitudes(color = colorIcono, lleno = activo) }
                    Pestana.INICIAR -> IconoNavegar(color = colorIcono, lleno = activo)
                    Pestana.VIAJES -> IconoRuta(color = colorIcono, lleno = activo)
                    Pestana.PERFIL -> IconoPersona(color = colorIcono, lleno = activo)
                    Pestana.CONDUCTORES -> pe.cooruta.app.ui.componentes.IconoEstrella(color = colorIcono, lleno = activo)
                }
            }
        }
    ) { relleno ->
        val mod = Modifier.fillMaxSize().padding(relleno).padding(24.dp)
        when (pestana) {
            Pestana.BUSCAR -> if (esConductor) MisViajesPestana(
                esConductor = true,
                alCrear = alPublicar,
                alEditarRuta = alEditarRuta,
                alAvisar = alAvisar,
                alUsarHoy = {},
                modifier = Modifier.fillMaxSize().padding(relleno)
            ) else BuscarPestana(
                alGuardarTrayecto = alPublicar,
                usarHoyId = usarHoyId,
                alAvisar = alAvisar,
                modifier = Modifier.fillMaxSize().padding(relleno)
            )
            Pestana.SOLICITUDES -> if (esConductor) MiRutaPestana(
                alPublicar = alPublicar,
                alAvisar = alAvisar,
                soloSolicitudes = true,
                modifier = Modifier.fillMaxSize().padding(relleno)
            ) else MisSolicitudesPestana(
                alAvisar = alAvisar,
                modifier = Modifier.fillMaxSize().padding(relleno)
            )
            Pestana.INICIAR -> ViajePestana(
                esConductor = esConductor,
                alAvisar = alAvisar,
                modifier = Modifier.fillMaxSize().padding(relleno)
            )
            Pestana.VIAJES -> MisViajesPestana(
                esConductor = esConductor,
                alCrear = alPublicar,
                alEditarRuta = alEditarRuta,
                alAvisar = alAvisar,
                alUsarHoy = { id -> usarHoyId = "$id|${System.currentTimeMillis()}"; pestana = Pestana.BUSCAR },
                modifier = Modifier.fillMaxSize().padding(relleno)
            )
            Pestana.CONDUCTORES -> MisConductoresPestana(
                alAvisar = alAvisar,
                modifier = Modifier.fillMaxSize().padding(relleno)
            )
            Pestana.PERFIL -> PerfilPestana(
                nombre = nombre,
                esConductor = esConductor,
                alEditarPerfil = alEditarPerfil,
                alCambiarRol = { alCambiarRol(); pestana = Pestana.BUSCAR },
                alEliminarCuenta = alEliminarCuenta,
                eliminando = eliminando,
                alAvisar = alAvisar,
                alCerrarSesion = alCerrarSesion,
                modifier = Modifier.fillMaxSize().padding(relleno)
            )
        }
    }
}

@Composable
private fun Vacio(modifier: Modifier, titulo: String, texto: String) {
    Column(
        modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(titulo, color = Coo.Texto, style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        Text(texto, color = Coo.TextoTenue, textAlign = TextAlign.Center)
    }
}


/** Permisos en pantalla (pasajero y conductor): ubicación (recomendado todo el tiempo) y notificaciones. */
@Composable
private fun PermisosPasajero() {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    fun ubic() = pe.cooruta.app.data.UbicacionEnVivo.tienePermiso(ctx)
    fun fondo() = pe.cooruta.app.ServicioUbicacion.enSegundoPlano(ctx)
    fun notif() = android.os.Build.VERSION.SDK_INT < 33 || androidx.core.content.ContextCompat.checkSelfPermission(
        ctx, android.Manifest.permission.POST_NOTIFICATIONS) == android.content.pm.PackageManager.PERMISSION_GRANTED
    var tUbic by remember { mutableStateOf(ubic()) }
    var tFondo by remember { mutableStateOf(fondo()) }
    var tNotif by remember { mutableStateOf(notif()) }
    fun refrescar() { tUbic = ubic(); tFondo = fondo(); tNotif = notif() }
    val contrato = androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions()
    val pedirNotif = androidx.activity.compose.rememberLauncherForActivityResult(contrato) { refrescar() }
    fun lanzarNotif() { if (!tNotif) pedirNotif.launch(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS)) }
    // Permiso de ubicación y, si el GPS está apagado, la ventana para activarlo; luego las notificaciones.
    val pedidoUbic = pe.cooruta.app.ui.componentes.rememberPedidoUbicacion()
    // Al entrar, las ventanas nativas de Android aparecen solas (una vez por sesión).
    var autoPedido by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (autoPedido) return@LaunchedEffect
        autoPedido = true
        if (!tUbic || !pe.cooruta.app.ui.componentes.ubicacionActivada(ctx)) pedidoUbic.pedir { refrescar(); lanzarNotif() }
        else lanzarNotif()
    }

    // Al volver a la app (por ejemplo desde Ajustes) se relee y se arranca la ubicación en vivo.
    androidx.lifecycle.compose.LifecycleResumeEffect(tUbic, tFondo) {
        refrescar()
        pe.cooruta.app.data.UbicacionEnVivo.iniciar(ctx)
        pe.cooruta.app.ServicioUbicacion.iniciar(ctx)
        onPauseOrDispose { if (!fondo()) pe.cooruta.app.data.UbicacionEnVivo.detener() }
    }

    // Sin recuadro propio: solo las ventanas nativas de Android (arriba). «Permitir todo el tiempo»
    // Android solo lo deja elegir en Ajustes; se llega desde Perfil › Ubicación.
}



/** Campana dibujada (sin dependencias de íconos). */
@Composable
private fun IconoCampana(color: androidx.compose.ui.graphics.Color) =
    pe.cooruta.app.ui.componentes.IconoM(androidx.compose.material.icons.Icons.Outlined.Notifications, color, 24.dp)
