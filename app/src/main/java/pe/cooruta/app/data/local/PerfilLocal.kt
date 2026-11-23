package pe.cooruta.app.data.local

import android.content.Context
import pe.cooruta.app.ui.auth.Rol

/** Rol y nombre elegidos, guardados por cuenta en el teléfono para no pasar por Perfil cada vez. */
interface AlmacenPerfil {
    fun leer(uid: String): Pair<String, Rol>?
    fun guardar(uid: String, nombre: String, rol: Rol)
    fun borrar(uid: String)
}

class PerfilLocal(ctx: Context) : AlmacenPerfil {
    private val prefs = ctx.applicationContext.getSharedPreferences("perfil", Context.MODE_PRIVATE)

    override fun leer(uid: String): Pair<String, Rol>? {
        val rol = prefs.getString("rol_$uid", null)?.let { runCatching { Rol.valueOf(it) }.getOrNull() } ?: return null
        return prefs.getString("nombre_$uid", "").orEmpty() to rol
    }

    override fun guardar(uid: String, nombre: String, rol: Rol) {
        prefs.edit().putString("rol_$uid", rol.name).putString("nombre_$uid", nombre).apply()
    }

    override fun borrar(uid: String) {
        prefs.edit().remove("rol_$uid").remove("nombre_$uid").apply()
    }
}
