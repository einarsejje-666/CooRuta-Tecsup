package pe.cooruta.app.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * «Limpiar historial»: oculta en este teléfono las solicitudes rechazadas o canceladas.
 * No se borran de la base de datos (la otra persona y las reglas las necesitan).
 */
object HistorialOculto {
    private const val PREFS = "historial_oculto"
    private val _ids = MutableStateFlow<Set<String>?>(null)
    val ids: StateFlow<Set<String>?> = _ids

    fun cargar(ctx: Context) {
        if (_ids.value != null) return
        _ids.value = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getStringSet("ids", emptySet())?.toSet() ?: emptySet()
    }

    fun ocultar(ctx: Context, nuevos: Collection<String>) {
        val todos = (_ids.value ?: emptySet()) + nuevos
        // Tope para no crecer sin límite: se quedan los últimos 500.
        val recortado = todos.toList().takeLast(500).toSet()
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putStringSet("ids", recortado).apply()
        _ids.value = recortado
    }
}
