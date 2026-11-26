package pe.cooruta.app.ui.auth

import android.app.Activity
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.NoCredentialException
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.firebase.auth.AuthCredential
import com.google.firebase.auth.GoogleAuthProvider
import pe.cooruta.app.R

/** Lo que devuelve el selector de cuentas de Google. */
sealed interface ResultadoCredencial {
    data class Lista(val credencial: AuthCredential) : ResultadoCredencial
    data object Cancelada : ResultadoCredencial
    data class Error(val mensaje: String) : ResultadoCredencial
}

/*
 * Por qué esto vive en la capa de interfaz y no en el repositorio:
 * el selector de cuentas de Google es una ventana que
 * se abre encima de la app, y necesita la Activity. Un ViewModel no
 * puede guardar una Activity (se destruye al girar el teléfono), así que la
 * pantalla consigue la credencial y el ViewModel hace el resto con
 * AuthRepositorio.
 */

/** Abre el selector de cuentas de Google con Credential Manager. */
class SelectorGoogle(private val actividad: Activity) {

    /** [dominio]: si se indica, el selector solo ofrece cuentas de ese dominio (es una ayuda, no seguridad). */
    suspend fun pedir(dominio: String? = null): ResultadoCredencial = try {
        val opcion = GetSignInWithGoogleOption
            .Builder(actividad.getString(R.string.default_web_client_id))
            .apply { if (dominio != null) setHostedDomainFilter(dominio) }
            .build()

        val solicitud = GetCredentialRequest.Builder()
            .addCredentialOption(opcion)
            .build()

        val credencial = CredentialManager.create(actividad)
            .getCredential(actividad, solicitud)
            .credential

        if (credencial is CustomCredential &&
            credencial.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL
        ) {
            val token = GoogleIdTokenCredential.createFrom(credencial.data).idToken
            ResultadoCredencial.Lista(GoogleAuthProvider.getCredential(token, null))
        } else {
            ResultadoCredencial.Error("Google devolvió una credencial que no reconocemos.")
        }
    } catch (e: GetCredentialCancellationException) {
        ResultadoCredencial.Cancelada
    } catch (e: NoCredentialException) {
        ResultadoCredencial.Error("No hay ninguna cuenta de Google en este dispositivo.")
    } catch (e: GetCredentialException) {
        ResultadoCredencial.Error(e.message ?: "No se pudo abrir el acceso con Google.")
    } catch (e: Exception) {
        ResultadoCredencial.Error(e.message ?: "Ocurrió un error inesperado.")
    }
}
