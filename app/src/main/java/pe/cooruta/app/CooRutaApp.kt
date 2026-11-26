package pe.cooruta.app

import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import pe.cooruta.app.ui.auth.BienvenidaScreen
import pe.cooruta.app.ui.auth.Comunidad
import pe.cooruta.app.ui.auth.DOMINIO_TECSUP
import pe.cooruta.app.ui.auth.PerfilScreen
import pe.cooruta.app.ui.auth.Rol
import pe.cooruta.app.ui.auth.SelectorGoogle
import pe.cooruta.app.ui.auth.credencialFicticia
import pe.cooruta.app.ui.confirmar.ConfirmarScreen
import pe.cooruta.app.ui.horario.HorarioScreen
import pe.cooruta.app.ui.inicio.InicioScreen
import pe.cooruta.app.ui.ruta.RutaScreen

/**
 * Punto de entrada de la interfaz. Solo observa el estado de [SesionViewModel]
 * y le pasa los toques: aquí no se decide nada ni se llama a Firebase.
 */
@Composable
fun CooRutaApp(
    sesion: SesionViewModel = viewModel(factory = SesionViewModel.Fabrica)
) {
    val actividad = LocalContext.current as ComponentActivity

    // La ventana de Google se abre sobre la Activity, por eso
    // el selector vive aquí y no en el ViewModel.
    val selectorGoogle = remember(actividad) { SelectorGoogle(actividad) }
    val enlaces = androidx.compose.ui.platform.LocalUriHandler.current

    val estado by sesion.estado.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(sesion) {
        sesion.mensajes.collect { snackbarHostState.showSnackbar(it) }
    }

    // Sin sesión (cerrar sesión, eliminar cuenta) no se sigue usando la ubicación, tampoco en segundo plano.
    LaunchedEffect(estado.pantalla) {
        if (estado.pantalla == Pantalla.BIENVENIDA) {
            ServicioUbicacion.detener(actividad)
            pe.cooruta.app.data.UbicacionEnVivo.detener()
            TokensPush.olvidar() // sin sesión no llegan avisos de la cuenta anterior
        }
        if (estado.pantalla == Pantalla.INICIO) TokensPush.registrar()
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = Color.Black,
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { _ ->
        when (estado.pantalla) {

            Pantalla.BIENVENIDA -> BienvenidaScreen(
                cargando = estado.cargando,
                // Con emuladores: cuenta ficticia del Auth Emulator, sin abrir el selector de Google.
                alEntrarConGoogle = { sesion.entrarCon(Comunidad.TECSUP) { if (BuildConfig.EMULADOR) credencialFicticia(tecsup = true) else selectorGoogle.pedir(DOMINIO_TECSUP) } },
                alRegistroExterno = { sesion.entrarCon(Comunidad.EXTERNA) { if (BuildConfig.EMULADOR) credencialFicticia() else selectorGoogle.pedir() } },
                alEntrarConCorreo = { correo, clave -> sesion.entrarConCorreo(correo, clave) },
                alPulsarTerminos = {
                    runCatching { enlaces.openUri(pe.cooruta.app.ui.inicio.URL_PRIVACIDAD) }
                        .onFailure { sesion.avisar("No se pudo abrir el navegador.") }
                }
            )

            Pantalla.PERFIL -> {
                BackHandler { sesion.atrasDesdePerfil() }
                PerfilScreen(
                    nombreInicial = estado.nombrePerfil,
                    externo = sesion.comunidadActual() == Comunidad.EXTERNA,
                    cargando = estado.cargando,
                    alConfirmar = sesion::registrar,
                    alVolver = sesion::atrasDesdePerfil
                )
            }

            Pantalla.INICIO -> InicioScreen(
                nombre = estado.nombrePerfil,
                esConductor = estado.rol == Rol.CONDUCTOR,
                alPublicar = sesion::irAPublicar,
                alEditarRuta = sesion::editarRuta,
                alEditarPerfil = sesion::cambiarNombre,
                alCambiarRol = sesion::cambiarRol,
                alEliminarCuenta = sesion::eliminarCuenta,
                eliminando = estado.cargando,
                alAvisar = sesion::avisar,
                alCerrarSesion = sesion::cerrarSesion
            )

            Pantalla.RUTA -> {
                BackHandler { sesion.cancelarPublicacion() }
                RutaScreen(
                    cargando = estado.cargando,
                    inicial = estado.borrador?.takeIf { estado.idEdicion != null },
                    claveInicial = estado.claveEdicion.takeIf { estado.idEdicion != null },
                    alSiguiente = sesion::irAHorario,
                    alVolver = sesion::cancelarPublicacion
                )
            }

            Pantalla.HORARIO -> {
                BackHandler { sesion.volverARuta() }
                val borrador = estado.borrador
                if (borrador != null) {
                    HorarioScreen(
                        borrador = borrador,
                        esConductor = estado.rol == Rol.CONDUCTOR,
                        alVolver = sesion::volverARuta,
                        alSiguiente = sesion::confirmarHorario
                    )
                }
            }

            Pantalla.CONFIRMAR -> {
                BackHandler { sesion.volverAHorario() }
                val borrador = estado.borrador
                if (borrador != null) {
                    ConfirmarScreen(
                        borrador = borrador,
                        nombre = estado.nombrePerfil,
                        esConductor = estado.rol == Rol.CONDUCTOR,
                        alVolver = sesion::volverAHorario,
                        alEditarRuta = sesion::volverARuta,
                        alEditarHorario = sesion::volverAHorario,
                        alTerminar = sesion::terminarPublicacion,
                        idEdicion = estado.idEdicion,
                        aliasInicial = estado.aliasEdicion
                    )
                }
            }
        }
    }
}
