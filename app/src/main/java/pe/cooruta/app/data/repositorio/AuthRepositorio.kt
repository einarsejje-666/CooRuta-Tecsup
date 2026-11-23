package pe.cooruta.app.data.repositorio

import android.util.Log
import com.google.firebase.FirebaseNetworkException
import com.google.firebase.auth.AuthCredential
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import kotlinx.coroutines.suspendCancellableCoroutine
import pe.cooruta.app.BuildConfig
import pe.cooruta.app.data.Resultado
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Resultado de intentar iniciar sesión. */
sealed interface ResultadoAcceso {
    data class Exito(val usuario: FirebaseUser) : ResultadoAcceso
    data object Cancelado : ResultadoAcceso
    data class Error(val mensaje: String) : ResultadoAcceso
}

/**
 * Todo lo que tiene que ver con la sesión en Firebase Authentication.
 *
 * Es la única puerta a Firebase Auth: los ViewModels no tocan FirebaseAuth
 * directamente. Google llega aquí ya convertido en una
 * [AuthCredential] — abrir el selector de cuentas es cosa de la pantalla,
 * porque necesita la Activity (ver ui/auth/SelectoresCredencial.kt).
 */
class AuthRepositorio(
    private val auth: FirebaseAuth = FirebaseAuth.getInstance()
) {

    /** Usuario con sesión abierta, o null. Firebase la recuerda entre arranques. */
    val usuarioActual: FirebaseUser? get() = auth.currentUser

    /** Canjea la credencial de Google por una sesión de Firebase. */
    suspend fun entrarCon(credencial: AuthCredential): ResultadoAcceso = try {
        ResultadoAcceso.Exito(iniciarSesion(credencial))
    } catch (e: Exception) {
        if (BuildConfig.DEBUG) Log.w("CooRutaAuth", "Fallo de acceso: ${describirFallo(e)}")
        ResultadoAcceso.Error(traducir(e))
    }

    /**
     * Elimina la cuenta de Firebase Auth. Firebase pide un inicio de sesión
     * reciente; si es viejo, se avisa para volver a entrar y reintentar.
     */
    suspend fun eliminarCuenta(): Resultado<Unit> {
        val usuario = auth.currentUser ?: return Resultado.Error("No hay sesión abierta.")
        return try {
            suspendCancellableCoroutine<Unit> { c ->
                usuario.delete()
                    .addOnSuccessListener { if (c.isActive) c.resume(Unit) }
                    .addOnFailureListener { if (c.isActive) c.resumeWithException(it) }
            }
            Resultado.Exito(Unit)
        } catch (e: com.google.firebase.auth.FirebaseAuthRecentLoginRequiredException) {
            Resultado.Error("Por seguridad, vuelve a entrar con Google y elimina la cuenta otra vez.")
        } catch (e: Exception) {
            Resultado.Error(traducir(e))
        }
    }

    /** Cierra la sesión en Firebase. */
    fun cerrarSesion() {
        auth.signOut()
    }

    /** Envuelve la Task de Firebase para poder usarla desde una corrutina. */
    private suspend fun iniciarSesion(credencial: AuthCredential): FirebaseUser =
        suspendCancellableCoroutine { continuacion ->
            auth.signInWithCredential(credencial)
                .addOnSuccessListener { resultado ->
                    val usuario = resultado.user
                    if (usuario != null) {
                        continuacion.resume(usuario)
                    } else {
                        continuacion.resumeWithException(
                            IllegalStateException("Firebase no devolvió ningún usuario.")
                        )
                    }
                }
                .addOnFailureListener { continuacion.resumeWithException(it) }
        }

    /** Convierte los errores de Firebase en algo que la persona entienda. */
    private fun traducir(e: Exception): String {
        val mensaje = e.message.orEmpty()
        // Con emuladores no se usa internet: un fallo de red es el emulador local inaccesible.
        if (BuildConfig.EMULADOR && (e is FirebaseNetworkException || mensaje.contains("network", true)))
            return "No se pudo conectar con el emulador de Auth (${BuildConfig.EMULADOR_HOST}:9099). " +
                "Revisa que esté iniciado; no es un problema de internet."
        if (e is FirebaseNetworkException) return "No hay conexión a internet."
        return when {
            mensaje.contains("configuration is not found", true) ||
                mensaje.contains("OPERATION_NOT_ALLOWED", true) ||
                mensaje.contains("not allowed", true) ->
                "Este método de acceso no está habilitado en Firebase. " +
                    "Actívalo en Authentication → Sign-in method."
            mensaje.contains("TOO_MANY_REQUESTS", true) ->
                "Demasiados intentos. Espera un rato antes de volver a probar."
            mensaje.contains("network", true) ->
                "No hay conexión a internet."
            else -> mensaje.ifBlank { "Ocurrió un error inesperado." }
        }
    }
}

/**
 * Solo para el registro de depuración: clase, mensaje y causa, sin correos, tokens ni JSON.
 */
private fun describirFallo(e: Throwable): String {
    fun limpiar(t: String?) = t.orEmpty()
        .replace(Regex("""\{.*\}""", RegexOption.DOT_MATCHES_ALL), "<json>")
        .replace(Regex("""[\w.+-]+@[\w-]+(\.[\w-]+)+"""), "<correo>")
        .replace(Regex("""[A-Za-z0-9_\-.]{40,}"""), "<omitido>")
        .take(200)
    val causa = e.cause?.let { "${it.javaClass.name}: ${limpiar(it.message)}" } ?: "ninguna"
    return "${e.javaClass.name}: ${limpiar(e.message)} | causa: $causa"
}
