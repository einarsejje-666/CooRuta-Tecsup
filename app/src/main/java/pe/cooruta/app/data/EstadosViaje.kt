package pe.cooruta.app.data

/**
 * Estados propios del viaje en curso. No reutilizan ni cambian los estados de las reservas
 * ni sus contadores de asientos.
 */
object EstadoViaje {
    const val PREPARANDO = "preparando"
    /** «Salgo en 5 minutos»: declaración manual del conductor, no una predicción. */
    const val SALIENDO = "saliendo"
    const val EN_CURSO = "en_curso"
    const val FINALIZADO = "finalizado"

    fun puedePasar(de: String, a: String): Boolean = when (de) {
        PREPARANDO -> a == SALIENDO || a == EN_CURSO || a == FINALIZADO
        SALIENDO -> a == EN_CURSO || a == FINALIZADO
        EN_CURSO -> a == FINALIZADO
        else -> false
    }

    /** Mientras no termine, el conductor puede compartir su ubicación con la pantalla abierta. */
    fun admiteUbicacion(estado: String) = estado == PREPARANDO || estado == SALIENDO || estado == EN_CURSO
}

/** Recogida de un pasajero dentro del viaje (no es el estado de su reserva). */
object EstadoParada {
    const val LLEGUE = "llegue"
    const val A_BORDO = "a_bordo"
    /** Lo escribe solo la función marcarNoSePresento, tras [ESPERA_MIN] minutos de espera. */
    const val NO_SE_PRESENTO = "no_se_presento"
    const val ESPERA_MIN = 5L

    fun puedePasar(de: String?, a: String): Boolean =
        (de == null && (a == LLEGUE || a == A_BORDO)) || (de == LLEGUE && a == A_BORDO)
}

/** Una lectura real del GPS: [tomadaMs] es la hora de la muestra (no la de envío ni la del servidor). */
data class Muestra(val punto: Punto, val tomadaMs: Long)

/** Qué tan reciente es la ubicación del conductor que ve el pasajero (según la hora de la muestra). */
enum class Seguimiento { SIN_DATOS, EN_VIVO, DESACTUALIZADO, PAUSADO }

object PoliticaUbicacion {
    /** Mínimo entre envíos, para no escribir por cada lectura del GPS. */
    const val INTERVALO_MS = 10_000L
    /** Aun sin moverse, se reenvía cada tanto para que se vea que sigue activa. */
    const val LATIDO_MS = 60_000L
    const val DISTANCIA_M = 20.0
    /** Cada cuánto se piden muestras al GPS con la pantalla del viaje activa. */
    const val MUESTREO_MS = 5_000L
    /** Una muestra más vieja que esto no se envía como «ubicación actual». */
    const val MUESTRA_MAX_MS = 30_000L
    /** Pasado esto desde la muestra, la ubicación se muestra como desactualizada. */
    const val VIGENCIA_MS = 90_000L

    fun debePublicar(ultimo: Punto?, ultimoMs: Long?, nuevo: Punto, ahoraMs: Long): Boolean {
        if (ultimo == null || ultimoMs == null) return true
        val transcurrido = ahoraMs - ultimoMs
        if (transcurrido < INTERVALO_MS) return false
        return transcurrido >= LATIDO_MS || metros(ultimo, nuevo) >= DISTANCIA_M
    }

    /** [tomadaMs]: hora de la muestra GPS. */
    fun seguimiento(activa: Boolean?, tomadaMs: Long?, ahoraMs: Long): Seguimiento = when {
        activa == null || tomadaMs == null -> Seguimiento.SIN_DATOS
        !activa -> Seguimiento.PAUSADO
        ahoraMs - tomadaMs > VIGENCIA_MS -> Seguimiento.DESACTUALIZADO
        else -> Seguimiento.EN_VIVO
    }
}
