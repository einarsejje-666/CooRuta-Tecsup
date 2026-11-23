package pe.cooruta.app.data

import pe.cooruta.app.data.repositorio.PerfilPublico
import java.text.Normalizer

/** Empresa o universidad compartida: verificada por dominio de correo, o declarada por nombre. */
object Comunidad {
    val DOMINIOS_PUBLICOS = setOf("gmail.com", "googlemail.com", "hotmail.com", "outlook.com", "live.com",
        "yahoo.com", "yahoo.es", "icloud.com", "proton.me", "protonmail.com")
    private val SUFIJOS = setOf("sac", "sa", "saa", "eirl", "srl", "sas", "inc", "ltda", "peru")

    fun dominioCorporativo(email: String): String? =
        email.substringAfter('@', "").lowercase().takeIf { it.contains('.') && it !in DOMINIOS_PUBLICOS }

    /** "Minera Cerro Verde S.A.A." → "mineracerroverde": sin tildes, mayúsculas, puntos ni sufijo legal. */
    fun clave(nombre: String): String {
        val sinTildes = Normalizer.normalize(nombre, Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "")
        return sinTildes.lowercase().replace(".", "").split(Regex("[^a-z0-9]+"))
            .filter { it.isNotBlank() && it !in SUFIJOS }.joinToString("").take(60)
    }

    enum class Tipo { VERIFICADA, DECLARADA }

    /** Cómo coinciden dos perfiles, o null si no comparten comunidad. */
    fun coincidencia(yo: PerfilPublico?, otro: PerfilPublico?): Tipo? = when {
        yo == null || otro == null -> null
        yo.empresaDominio.isNotBlank() && yo.empresaDominio == otro.empresaDominio -> Tipo.VERIFICADA
        yo.empresaClave.isNotBlank() && yo.empresaClave == otro.empresaClave -> Tipo.DECLARADA
        else -> null
    }
}
