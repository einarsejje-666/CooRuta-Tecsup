package pe.cooruta.app

import androidx.lifecycle.ViewModel
import com.google.firebase.auth.EmailAuthProvider
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import pe.cooruta.app.data.BorradorRuta
import pe.cooruta.app.data.HorarioRuta
import pe.cooruta.app.data.local.AlmacenPerfil
import pe.cooruta.app.data.local.PerfilLocal
import pe.cooruta.app.data.repositorio.AuthRepositorio
import pe.cooruta.app.data.repositorio.ResultadoAcceso
import pe.cooruta.app.data.repositorio.RutasRepositorio
import pe.cooruta.app.data.Resultado
import pe.cooruta.app.ui.auth.Comunidad
import pe.cooruta.app.ui.auth.DatosRegistro
import pe.cooruta.app.ui.auth.ResultadoCredencial
import pe.cooruta.app.ui.auth.RolDeseado
import pe.cooruta.app.ui.auth.comunidadDe
import pe.cooruta.app.ui.auth.Rol

/** Pantallas de la app. */
enum class Pantalla { BIENVENIDA, PERFIL, INICIO, RUTA, HORARIO, CONFIRMAR }

/** Todo lo que la interfaz necesita saber de la sesión, en un solo objeto. */
data class EstadoSesion(
    val pantalla: Pantalla,
    val cargando: Boolean = false,
    val perfilListo: Boolean = false,
    val nombrePerfil: String = "",
    val rol: Rol? = null,
    /** La ruta que se está publicando, mientras se recorren los pasos del asistente. */
    val borrador: BorradorRuta? = null,
    /** Id de la ruta que se está editando (null = ruta nueva). */
    val idEdicion: String? = null,
    val aliasEdicion: String = "",
    /** Cambia en cada edición, para que el mapa cargue los puntos guardados una vez. */
    val claveEdicion: String? = null
)

/**
 * Sesión y navegación.
 *
 * La interfaz solo observa [estado] y llama a estas funciones; no decide nada.
 * Las operaciones lentas corren en viewModelScope, así que sobreviven a girar
 * el teléfono y se cancelan solas cuando la app se cierra.
 */
class SesionViewModel(
    private val auth: AuthRepositorio,
    private val rutas: RutasRepositorio = RutasRepositorio(),
    private val perfilLocal: AlmacenPerfil? = null
) : ViewModel() {

    private val _estado = MutableStateFlow(
        // Firebase recuerda la sesión; el rol elegido se guarda por cuenta en el teléfono.
        // Con rol guardado se entra directo al menú; si no, se pasa por Perfil.
        auth.usuarioActual.let { u ->
            val guardado = u?.uid?.let { perfilLocal?.leer(it) }
            when {
                u == null -> EstadoSesion(pantalla = Pantalla.BIENVENIDA)
                guardado != null -> EstadoSesion(pantalla = Pantalla.INICIO, perfilListo = true,
                    nombrePerfil = guardado.first.ifBlank { u.displayName.orEmpty() }, rol = guardado.second)
                else -> EstadoSesion(pantalla = Pantalla.PERFIL, nombrePerfil = u.displayName.orEmpty())
            }
        }
    )

    private fun guardarPerfil() {
        val uid = auth.usuarioActual?.uid ?: return
        val e = _estado.value
        e.rol?.let { perfilLocal?.guardar(uid, e.nombrePerfil, it) }
    }
    val estado: StateFlow<EstadoSesion> = _estado.asStateFlow()

    init { viewModelScope.launch { rutas.asignarComunidadPendiente() } }

    // Avisos de una sola vez (los snackbars). Un Channel y no un StateFlow:
    // si giras el teléfono, el aviso no se vuelve a mostrar.
    private val _mensajes = Channel<String>(Channel.BUFFERED)
    val mensajes: Flow<String> = _mensajes.receiveAsFlow()

    fun avisar(mensaje: String) {
        _mensajes.trySend(mensaje)
    }

    // ------------------------------------------------------------ Google

    /**
     * Entrar con Google. [obtener] abre el selector de
     * cuentas (eso lo pone la pantalla, porque necesita la Activity); el resto
     * lo hace el ViewModel.
     */
    fun entrarCon(comunidad: Comunidad, obtener: suspend () -> ResultadoCredencial) {
        if (_estado.value.cargando) return
        _estado.update { it.copy(cargando = true) }

        viewModelScope.launch {
            when (val credencial = obtener()) {
                is ResultadoCredencial.Cancelada -> avisar("Cancelaste el acceso.")
                is ResultadoCredencial.Error -> avisar(credencial.mensaje)
                is ResultadoCredencial.Lista -> when (val resultado = auth.entrarCon(credencial.credencial)) {
                    is ResultadoAcceso.Exito -> when {
                        // La comunidad sale del correo: cada acceso solo admite la suya.
                        comunidad == Comunidad.TECSUP && comunidadDe(resultado.usuario.email) != Comunidad.TECSUP -> {
                            auth.cerrarSesion()
                            avisar("Este acceso es solo para correos @tecsup.edu.pe. Si no eres de Tecsup, usa «Regístrate aquí».")
                        }
                        comunidad == Comunidad.EXTERNA && comunidadDe(resultado.usuario.email) == Comunidad.TECSUP -> {
                            auth.cerrarSesion()
                            avisar("Las cuentas de Tecsup deben entrar con «Continuar con Google — Tecsup».")
                        }
                        else -> alEntrar(resultado, "Hola, ${resultado.usuario.displayName ?: "bienvenido"}")
                    }
                    is ResultadoAcceso.Cancelado -> avisar("Cancelaste el acceso.")
                    is ResultadoAcceso.Error -> avisar(resultado.mensaje)
                }
            }
            _estado.update { it.copy(cargando = false) }
        }
    }

    /** Acceso con correo y contraseña: cuentas creadas en Firebase (p. ej. la de revisión de Google Play). */
    fun entrarConCorreo(correo: String, contrasena: String) {
        if (correo.isBlank() || contrasena.isBlank()) {
            avisar("Escribe tu correo y tu contraseña.")
            return
        }
        entrarCon(Comunidad.EXTERNA) {
            ResultadoCredencial.Lista(EmailAuthProvider.getCredential(correo.trim(), contrasena))
        }
    }

    // ------------------------------------------------------------ perfil y ruta

    /** Comunidad de la cuenta actual, deducida de su correo. */
    fun comunidadActual(): Comunidad = comunidadDe(auth.usuarioActual?.email)

    /** Guarda el registro en el servidor y, si se acepta, entra al menú. */
    fun registrar(datos: DatosRegistro) {
        if (_estado.value.cargando) return
        _estado.update { it.copy(cargando = true) }
        viewModelScope.launch {
            val r = pe.cooruta.app.data.repositorio.PerfilesRepositorio().guardarRegistro(
                datos.nombres, datos.apellidos, datos.telefono, datos.rol.name.lowercase(),
                if (comunidadActual() == Comunidad.TECSUP) "tecsup" else "externa", datos.motivos, datos.motivoOtro)
            _estado.update { it.copy(cargando = false) }
            when (r) {
                // «Ambos» empieza como pasajero; desde el menú se cambia de modo.
                is Resultado.Exito -> confirmarPerfil("${datos.nombres} ${datos.apellidos}",
                    if (datos.rol == RolDeseado.CONDUCTOR) Rol.CONDUCTOR else Rol.PASAJERO)
                is Resultado.Error -> avisar(r.mensaje)
            }
        }
    }

    fun confirmarPerfil(nombre: String, rol: Rol) {
        _estado.update {
            it.copy(nombrePerfil = nombre, rol = rol, perfilListo = true, pantalla = Pantalla.INICIO)
        }
        guardarPerfil()
        avisar("Listo, $nombre. Entraste como ${if (rol == Rol.CONDUCTOR) "conductor" else "pasajero"}.")
    }

    /** Pasa de pasajero a conductor o al revés, sin salir del menú principal. */
    fun cambiarRol() {
        val nuevo = if (_estado.value.rol == Rol.CONDUCTOR) Rol.PASAJERO else Rol.CONDUCTOR
        _estado.update { it.copy(rol = nuevo, pantalla = Pantalla.INICIO) }
        guardarPerfil()
        avisar("Ahora estás en modo ${if (nuevo == Rol.CONDUCTOR) "conductor" else "pasajero"}.")
    }

    fun volverAPerfil() = _estado.update { it.copy(pantalla = Pantalla.PERFIL) }

    /** Cambia solo el nombre, sin salir del menú. */
    fun cambiarNombre(nombre: String) {
        _estado.update { it.copy(nombrePerfil = nombre) }
        guardarPerfil()
        avisar("Nombre actualizado.")
    }

    /** Atrás en Perfil: si ya había perfil, vuelve al menú; solo en el primer ingreso sale de la cuenta. */
    fun atrasDesdePerfil() {
        if (_estado.value.perfilListo) _estado.update { it.copy(pantalla = Pantalla.INICIO) } else cerrarSesion()
    }

    /** Atrás en el primer paso de publicar: descarta el borrador y vuelve al menú. */
    fun cancelarPublicacion() = _estado.update {
        it.copy(borrador = null, idEdicion = null, aliasEdicion = "", pantalla = if (it.perfilListo) Pantalla.INICIO else Pantalla.PERFIL)
    }

    // ------------------------------------------------------------ asistente de ruta

    /** Paso 1 → paso 2: se guarda de dónde a dónde y se pasa al horario. */
    fun irAHorario(borrador: BorradorRuta) = _estado.update {
        // Si ya había un horario (se volvió atrás y adelante), se conserva.
        it.copy(borrador = borrador.copy(horario = it.borrador?.horario), pantalla = Pantalla.HORARIO)
    }

    fun volverARuta() = _estado.update { it.copy(pantalla = Pantalla.RUTA) }

    /** Paso 2 → paso 3: se guarda el horario y se pasa a revisar. */
    fun confirmarHorario(horario: HorarioRuta) = _estado.update {
        it.copy(borrador = it.borrador?.copy(horario = horario), pantalla = Pantalla.CONFIRMAR)
    }

    fun volverAHorario() = _estado.update { it.copy(pantalla = Pantalla.HORARIO) }

    /** Publicada la ruta: se descarta el borrador y se va al menú principal. */
    fun terminarPublicacion() = _estado.update {
        it.copy(borrador = null, idEdicion = null, aliasEdicion = "", pantalla = Pantalla.INICIO)
    }

    /** Desde el FAB del menú principal: abre el asistente de 3 pasos. */
    fun irAPublicar() = _estado.update { it.copy(pantalla = Pantalla.RUTA, idEdicion = null, aliasEdicion = "") }

    /** Editar una ruta guardada: abre el asistente con sus datos y al final se sobrescribe la misma. */
    fun editarRuta(id: String, borrador: BorradorRuta, alias: String) = _estado.update {
        it.copy(borrador = borrador, idEdicion = id, aliasEdicion = alias,
            claveEdicion = "$id|${System.currentTimeMillis()}", pantalla = Pantalla.RUTA)
    }

    /** Borra los datos del usuario en Firestore y su cuenta; luego vuelve a la bienvenida. */
    fun eliminarCuenta() {
        if (_estado.value.cargando) return
        _estado.update { it.copy(cargando = true) }
        viewModelScope.launch {
            // Se guarda antes: después de borrar la cuenta ya no hay usuario actual.
            val uid = auth.usuarioActual?.uid
            // Primero en el servidor (borra todo, sin bloqueos); si aún no está publicado, desde la app.
            val cuenta = rutas.eliminarCuentaCompleta() ?: run {
                val datos = rutas.borrarTodoDelUsuario()
                val perfil = if (datos is Resultado.Exito) pe.cooruta.app.data.repositorio.PerfilesRepositorio().borrarMiPerfil() else datos
                if (perfil is Resultado.Exito) auth.eliminarCuenta() else perfil
            }
            when (cuenta) {
                is Resultado.Exito -> {
                    uid?.let { perfilLocal?.borrar(it) }
                    auth.cerrarSesion()
                    _estado.update { EstadoSesion(pantalla = Pantalla.BIENVENIDA) }
                    avisar("Tu cuenta fue eliminada.")
                }
                is Resultado.Error -> {
                    _estado.update { it.copy(cargando = false) }
                    avisar(cuenta.mensaje)
                }
            }
        }
    }

    /** Volver desde Perfil es salir de la cuenta: es la puerta para cambiar de usuario. */
    fun cerrarSesion() {
        auth.cerrarSesion()
        _estado.update {
            EstadoSesion(pantalla = Pantalla.BIENVENIDA)
        }
        avisar("Cerraste sesión.")
    }

    /** Después de entrar con Google: a dónde ir. */
    private fun alEntrar(resultado: ResultadoAcceso.Exito, saludo: String) {
        viewModelScope.launch { rutas.asignarComunidadPendiente() }
        val guardado = perfilLocal?.leer(resultado.usuario.uid)
        if (guardado != null) {
            _estado.update { it.copy(pantalla = Pantalla.INICIO, perfilListo = true, rol = guardado.second,
                nombrePerfil = guardado.first.ifBlank { resultado.usuario.displayName.orEmpty() }) }
            avisar(saludo)
        } else if (_estado.value.perfilListo) {
            _estado.update { it.copy(pantalla = Pantalla.BIENVENIDA) }
            avisar(saludo)
        } else {
            _estado.update {
                it.copy(
                    nombrePerfil = resultado.usuario.displayName.orEmpty(),
                    pantalla = Pantalla.PERFIL
                )
            }
        }
    }

    companion object {
        /** Cómo se construye: el repositorio entra por el constructor. */
        val Fabrica: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY]
                SesionViewModel(AuthRepositorio(), perfilLocal = app?.let { PerfilLocal(it) })
            }
        }
    }
}
