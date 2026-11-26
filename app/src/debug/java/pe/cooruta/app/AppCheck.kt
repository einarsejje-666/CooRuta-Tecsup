package pe.cooruta.app

import com.google.firebase.Firebase
import com.google.firebase.appcheck.appCheck
import com.google.firebase.appcheck.debug.DebugAppCheckProviderFactory

/**
 * Depuración: proveedor de depuración de App Check. El token aparece en Logcat
 * ("DebugAppCheckProvider") y se registra en la consola de Firebase. No se usa en release.
 */
fun instalarAppCheck() {
    Firebase.appCheck.installAppCheckProviderFactory(DebugAppCheckProviderFactory.getInstance())
}
