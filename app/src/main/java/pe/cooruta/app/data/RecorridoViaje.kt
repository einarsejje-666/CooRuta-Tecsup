package pe.cooruta.app.data

import pe.cooruta.app.data.repositorio.RutasRepositorio.Solicitud
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Respuesta de Directions para el recorrido del conductor. Distancias en metros y tiempos en
 * segundos, estimados por Mapbox al momento de calcular (no se actualizan con cada muestra GPS).
 */
data class RutaCalculada(
    val linea: List<Punto>,
    val metros: Double? = null,
    val segundos: Double? = null,
    /** Primer tramo: de la ubicación del conductor a la primera parada. */
    val metrosPrimerTramo: Double? = null,
    val segundosPrimerTramo: Double? = null
)

/** Recogida pendiente dentro del recorrido del conductor (solo la ve él). */
data class ParadaRecorrido(val reservaId: String, val punto: Punto)

/** Coordenadas para Directions (origen, recogidas incluidas, destino) y recogidas que no entraron. */
data class PlanRecorrido(val coordenadas: List<Punto>, val incluidas: List<ParadaRecorrido>, val omitidas: List<ParadaRecorrido>)

object PlanificadorRecorrido {
    /** Directions API v5, perfil driving: entre 2 y 25 coordenadas por solicitud. */
    const val MAX_COORDENADAS = 25

    /** Reservas aceptadas de esta ruta con punto de encuentro y sin recogida completada. */
    fun pendientes(confirmadas: List<Solicitud>, rutaId: String, paradas: Map<String, String>): List<ParadaRecorrido> =
        confirmadas.filter { it.rutaId == rutaId && it.estado == "aceptada" && paradas[it.id] != EstadoParada.A_BORDO }
            .mapNotNull { s -> s.subidaPunto?.let { ParadaRecorrido(s.id, it) } }

    /**
     * Ordena por la posición de cada recogida a lo largo del [recorrido] original de la ruta (no por
     * distancia en línea recta). Sin recorrido utilizable conserva el orden recibido.
     */
    fun ordenar(recogidas: List<ParadaRecorrido>, recorrido: List<Punto>): List<ParadaRecorrido> {
        if (recorrido.size < 2) return recogidas
        return recogidas.withIndex()
            .sortedWith(compareBy({ posicionSobre(recorrido, it.value.punto) }, { it.index }))
            .map { it.value }
    }

    /**
     * Respeta el límite de la API: si las recogidas no entran, se incluyen las primeras (en orden) y
     * el resto queda en [PlanRecorrido.omitidas] para avisarlo. null si no hay a dónde ir.
     */
    fun planear(origen: Punto, recogidasOrdenadas: List<ParadaRecorrido>, destino: Punto?): PlanRecorrido? {
        val cupo = MAX_COORDENADAS - 1 - (if (destino != null) 1 else 0)
        val incluidas = recogidasOrdenadas.take(cupo)
        val coordenadas = listOf(origen) + incluidas.map { it.punto } + listOfNotNull(destino)
        if (coordenadas.size < 2) return null
        return PlanRecorrido(coordenadas, incluidas, recogidasOrdenadas.drop(cupo))
    }

    /**
     * Orden de paradas puerta a puerta: desde [origen] va siempre a la parada permitida más cercana.
     * La bajada de un pasajero solo se permite cuando ya se pasó por su subida (o si no tiene subida
     * en la lista, porque ya va a bordo). [clave] identifica al pasajero; [sube] distingue S de B.
     */
    fun <T> ordenarPuertaAPuerta(origen: Punto?, paradas: List<T>, punto: (T) -> Punto, clave: (T) -> String, sube: (T) -> Boolean): List<T> {
        val quedan = paradas.toMutableList()
        val conSubida = paradas.filter(sube).map(clave).toMutableSet()
        val orden = mutableListOf<T>()
        var aqui = origen
        while (quedan.isNotEmpty()) {
            val posibles = quedan.filter { sube(it) || clave(it) !in conSubida }.ifEmpty { quedan }
            val sig = if (aqui == null) posibles.first() else posibles.minBy { metros(aqui!!, punto(it)) }
            quedan.remove(sig); orden += sig; aqui = punto(sig)
            if (sube(sig)) conSubida.remove(clave(sig))
        }
        return orden
    }

    /** Metros recorridos sobre [linea] hasta la proyección de [p] (segmento más cercano). */
    fun posicionSobre(linea: List<Punto>, p: Punto): Double {
        var mejorDist = Double.MAX_VALUE
        var mejorPos = 0.0
        var acumulado = 0.0
        for (i in 0 until linea.size - 1) {
            val a = linea[i]; val b = linea[i + 1]
            // Plano local en metros alrededor de a (suficiente a escala de ciudad).
            val kx = 111_320.0 * cos(Math.toRadians(a.latitud)); val ky = 110_540.0
            val bx = (b.longitud - a.longitud) * kx; val by = (b.latitud - a.latitud) * ky
            val px = (p.longitud - a.longitud) * kx; val py = (p.latitud - a.latitud) * ky
            val largo2 = bx * bx + by * by
            val t = if (largo2 == 0.0) 0.0 else ((px * bx + py * by) / largo2).coerceIn(0.0, 1.0)
            val dx = px - t * bx; val dy = py - t * by
            val dist = dx * dx + dy * dy
            val largo = sqrt(largo2)
            if (dist < mejorDist) { mejorDist = dist; mejorPos = acumulado + t * largo }
            acumulado += largo
        }
        return mejorPos
    }
}

/** Orientación del movimiento para la cámara: solo cuando el desplazamiento es real. */
object Rumbo {
    /** Por debajo de esto se considera detenido (el GPS oscila unos metros). */
    const val DISTANCIA_MIN_M = 15.0
    /** Muestras más separadas que esto no sirven para estimar el rumbo. */
    const val TIEMPO_MAX_MS = 30_000L

    /** Rumbo (0–360, 0 = norte) de [ancla] a [nueva]; si no es válido, se conserva [previo]. */
    fun actualizar(ancla: Muestra?, nueva: Muestra, previo: Double?): Double? {
        if (ancla == null) return previo
        val dt = nueva.tomadaMs - ancla.tomadaMs
        if (dt <= 0 || dt > TIEMPO_MAX_MS) return previo
        if (metros(ancla.punto, nueva.punto) < DISTANCIA_MIN_M) return previo
        return entre(ancla.punto, nueva.punto)
    }

    /** El ancla avanza solo cuando hubo desplazamiento real o la anterior es vieja. */
    fun nuevaAncla(ancla: Muestra?, nueva: Muestra): Muestra =
        if (ancla == null || nueva.tomadaMs - ancla.tomadaMs > TIEMPO_MAX_MS ||
            metros(ancla.punto, nueva.punto) >= DISTANCIA_MIN_M) nueva else ancla

    fun entre(a: Punto, b: Punto): Double {
        val f1 = Math.toRadians(a.latitud); val f2 = Math.toRadians(b.latitud)
        val dl = Math.toRadians(b.longitud - a.longitud)
        val y = sin(dl) * cos(f2)
        val x = cos(f1) * sin(f2) - sin(f1) * cos(f2) * cos(dl)
        return (Math.toDegrees(atan2(y, x)) + 360.0) % 360.0
    }
}
