package pe.cooruta.app.ui.auth

import com.google.firebase.auth.GoogleAuthProvider
import java.util.UUID

/**
 * Solo con los emuladores locales (BuildConfig.EMULADOR): cuenta Google ficticia que el Auth
 * Emulator acepta como token. Nunca usa cuentas ni sesiones reales. Cada entrada crea una cuenta nueva.
 */
fun credencialFicticia(tecsup: Boolean = false): ResultadoCredencial {
    val id = UUID.randomUUID().toString().take(8)
    val claims = """{"sub":"ficticia-$id","email":"ficticia-$id@${if (tecsup) DOMINIO_TECSUP else "demo-cooruta.test"}","email_verified":true,"name":"Prueba $id"}"""
    return ResultadoCredencial.Lista(GoogleAuthProvider.getCredential(claims, null))
}
