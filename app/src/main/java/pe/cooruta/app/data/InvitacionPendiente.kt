package pe.cooruta.app.data

import android.content.Intent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Código de invitación que llegó por enlace y aún no se aceptó. Queda guardado hasta que la
 * persona inicia sesión y decide (unirse o «ahora no»). [cambios] avisa a Buscar que recargue
 * los grupos tras unirse.
 */
object InvitacionPendiente {
    private val _codigo = MutableStateFlow<String?>(null)
    val codigo: StateFlow<String?> = _codigo
    private val _cambios = MutableStateFlow(0)
    val cambios: StateFlow<Int> = _cambios

    /** https://cooruta-36603.web.app/i/ABCD2345 o cooruta://i/ABCD2345 → "ABCD2345". */
    fun desdeIntent(intent: Intent?) {
        val uri = intent?.data ?: return
        val crudo = when {
            uri.scheme == "https" && uri.host == "cooruta-36603.web.app" -> uri.path?.removePrefix("/i/")
            uri.scheme == "cooruta" && uri.host == "i" -> uri.path?.removePrefix("/")
            else -> null
        }
        val c = crudo?.filter { it.isLetterOrDigit() }?.uppercase()?.take(8)
        if (c != null && c.length == 8) _codigo.value = c
    }

    fun descartar() { _codigo.value = null }
    fun unido() { _codigo.value = null; _cambios.value++ }
}
