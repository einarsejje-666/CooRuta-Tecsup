package pe.cooruta.app.data.repositorio

import com.google.firebase.Firebase
import com.google.firebase.ai.ai
import com.google.firebase.ai.type.GenerativeBackend
import com.google.firebase.ai.type.generationConfig
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import pe.cooruta.app.data.Resultado
import java.util.Locale
import kotlin.coroutines.cancellation.CancellationException

/**
 * Lo único que se envía a la IA por cada opción: horario, caminata, precio y coincidencia.
 * Sin nombres, identificadores ni coordenadas.
 */
data class ResumenOpcion(
    /** Hora de salida del conductor, "HH:mm". */
    val salida: String,
    /** Minutos de diferencia con la hora del pasajero. */
    val diferenciaMin: Int,
    val caminaSubirM: Int,
    val caminaBajarM: Int,
    val pasajeCentimos: Int,
    /** 0–100, calculado por el algoritmo de coincidencia. */
    val porcentaje: Int,
    /** Relación de empresa con el pasajero, en palabras; vacío si no comparten. */
    val empresa: String = ""
)

/** Modelo estable de la Gemini Developer API disponible sin facturación (plan Spark). */
private const val MODELO_IA = "gemini-3.5-flash-lite"
private const val ESPERA_IA_MS = 20_000L

// Firebase AI Logic: sin clave de Gemini en el APK; las llamadas pasan por Firebase y App Check.
private val modeloIa by lazy {
    Firebase.ai(backend = GenerativeBackend.googleAI())
        .generativeModel(modelName = MODELO_IA, generationConfig = generationConfig { temperature = 0.2f })
}

/**
 * Pide a Gemini una frase por opción que explique los resultados reales del algoritmo.
 * No cambia el orden, la tarifa, la capacidad ni ninguna decisión de reserva.
 */
class ExplicacionesRepositorio(
    /** false con los emuladores locales: nunca se llama a Gemini real. */
    private val habilitada: Boolean = true,
    private val generar: suspend (String) -> String? = { prompt -> modeloIa.generateContent(prompt).text }
) {
    suspend fun explicar(opciones: List<ResumenOpcion>): Resultado<List<String>> {
        if (opciones.isEmpty()) return Resultado.Exito(emptyList())
        if (!habilitada) return Resultado.Error("La IA está desactivada con los emuladores locales.")
        return try {
            val texto = withTimeout(ESPERA_IA_MS) { generar(instruccionesIa(opciones)) }
            separarExplicaciones(texto.orEmpty(), opciones.size)?.let { Resultado.Exito(it) }
                ?: Resultado.Error("La IA respondió en un formato inesperado.")
        } catch (e: TimeoutCancellationException) {
            Resultado.Error("La IA tardó demasiado en responder.")
        } catch (e: CancellationException) {
            throw e // búsqueda reemplazada: no es un error
        } catch (e: Exception) {
            // Solo depuración: tipo y mensaje del fallo (sin prompt ni datos personales).
            if (pe.cooruta.app.BuildConfig.DEBUG) runCatching {
                android.util.Log.w("CooRutaIA", "${e::class.java.name}: ${e.message} | causa: ${e.cause?.let { "${it::class.java.name}: ${it.message}" }}")
            }
            // En depuración se muestra la causa técnica para poder diagnosticarla desde la pantalla.
            Resultado.Error(mensajeFalloIa(e) + if (pe.cooruta.app.BuildConfig.DEBUG)
                "\n[${e::class.java.simpleName}: ${(e.message ?: e.cause?.message).orEmpty().take(180)}]" else "")
        }
    }
}

private fun soles(c: Int) = String.format(Locale.US, "S/ %d.%02d", c / 100, c % 100)

internal fun instruccionesIa(opciones: List<ResumenOpcion>): String = buildString {
    appendLine("Eres el asistente de CooRuta, una app de viajes compartidos en Arequipa, Perú.")
    appendLine("Para cada opción numerada escribe UNA frase breve en español (máximo 20 palabras) que explique")
    appendLine("por qué conviene o qué debe tener en cuenta el pasajero.")
    appendLine("Usa solo estos datos; no inventes lugares, nombres, tiempos de viaje ni otros datos.")
    appendLine("No cambies el orden, no recomiendes otra opción como mejor y no sugieras otros precios.")
    appendLine("Si una opción es de la misma empresa del pasajero, menciónalo; si dice «no verificado», dilo así.")
    appendLine("Responde exactamente con ${opciones.size} líneas con el formato \"1. frase\".")
    appendLine("Opciones (ya ordenadas por la app):")
    opciones.forEachIndexed { i, o ->
        appendLine("${i + 1}. Sale a las ${o.salida} (${o.diferenciaMin} min de diferencia con tu hora) · " +
            "caminas ${o.caminaSubirM} m para subir y ${o.caminaBajarM} m desde que bajas · " +
            "pasaje ${soles(o.pasajeCentimos)} · coincidencia ${o.porcentaje} %" +
            (if (o.empresa.isNotBlank()) " · conductor ${o.empresa}" else ""))
    }
}

/** Una frase por opción, en el mismo orden; null si la respuesta no trae exactamente 1..n. */
internal fun separarExplicaciones(texto: String, n: Int): List<String>? {
    val patron = Regex("""^\s*(\d+)[.)]\s*(.+)$""")
    val porNumero = texto.lines().mapNotNull { patron.find(it) }
        .associate { it.groupValues[1].toInt() to it.groupValues[2].replace("*", "").trim().take(200) }
    if ((1..n).any { porNumero[it].isNullOrBlank() }) return null
    return (1..n).map { porNumero.getValue(it) }
}

/** Mensaje según la causa: sin red, App Check/permisos o cuota; si no, uno general. */
internal fun mensajeFalloIa(e: Throwable): String {
    val cadena = generateSequence(e) { it.cause }.take(5).toList()
    val texto = cadena.joinToString(" ") { "${it::class.java.simpleName} ${it.message.orEmpty()}" }.lowercase()
    return when {
        cadena.any { it is java.io.IOException } -> "Sin conexión: no se pudo obtener la explicación de la IA."
        "app check" in texto || "appcheck" in texto || "403" in texto || "permission" in texto ->
            "La IA no está autorizada en este dispositivo (App Check). Avísale al equipo de CooRuta."
        "quota" in texto || "429" in texto || "resource_exhausted" in texto ->
            "La IA alcanzó su límite de uso por ahora. Inténtalo en unos minutos."
        else -> "No se pudo obtener la explicación de la IA. Inténtalo de nuevo."
    }
}
