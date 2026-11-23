package pe.cooruta.app.data

import pe.cooruta.app.data.repositorio.MiRuta
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/** Pasaje por defecto si la ruta no trae tarifa (S/ 0.25 por km). */
private const val TARIFA_DEFECTO = 25
private const val PASAJE_MINIMO = 200
private const val REDONDEO = 50

/** Más de esto caminando para subir o bajar ya no se considera coincidencia. */
private const val CAMINATA_MAXIMA_M = 1500.0

/** Hasta esto de su ruta, recoger o dejar al pasajero en su punto casi no es desvío. */
private const val CERCA_DE_LA_RUTA_M = 300.0

/** Qué tan bien una ruta de conductor sirve al trayecto del pasajero. */
data class Coincidencia(
    val ruta: MiRuta,
    /** 0–100. */
    val porcentaje: Int,
    /** Dónde sube y baja, sobre el recorrido del conductor. */
    val subida: Punto,
    val bajada: Punto,
    /** Metros que camina el pasajero desde su origen hasta subir, y desde que baja hasta su destino. */
    val caminaSubirM: Int,
    val caminaBajarM: Int,
    /** Kilómetros que viaja en el carro. */
    val kmCompartidos: Double,
    val tarifaKmCentimos: Int,
    val pasajeCentimos: Int,
    /** Minutos de diferencia entre la hora del conductor y la del pasajero. */
    val diferenciaMin: Int,
    /**
     * La ruta no pasa por el camino del pasajero: el conductor tendría que desviarse para recogerlo
     * en su origen y dejarlo en su destino. [desvioKm] es una estimación (ida y vuelta a su ruta).
     */
    val desvio: Boolean = false,
    val desvioKm: Double = 0.0
)

/**
 * Compara el trayecto guardado del pasajero con cada ruta y las ordena de la
 * que más coincide a la que menos. Solo usa datos reales de Firestore.
 */
fun buscarCoincidencias(pasajero: MiRuta, rutas: List<MiRuta>): List<Coincidencia> {
    val origen = pasajero.origenPunto ?: return emptyList()
    val destino = pasajero.destinoPunto ?: return emptyList()
    val kmPasajero = (pasajero.distanciaMetros?.div(1000)) ?: (metros(origen, destino) / 1000)

    return rutas.mapNotNull { ruta ->
        if (pasajero.dias.isNotEmpty() && ruta.dias.isNotEmpty() && (pasajero.dias intersect ruta.dias).isEmpty())
            return@mapNotNull null

        val linea = ruta.recorrido.takeIf { it.size >= 2 }
            ?: listOfNotNull(ruta.origenPunto, ruta.destinoPunto).takeIf { it.size == 2 }
            ?: return@mapNotNull null
        val acumulado = acumulados(linea)

        val (subida, kmSubida) = proyectar(origen, linea, acumulado)
        val (bajada, kmBajada) = proyectar(destino, linea, acumulado)
        val caminaSubir = metros(origen, subida)
        val caminaBajar = metros(destino, bajada)
        val tarifa = ruta.tarifaKmCentimos ?: TARIFA_DEFECTO
        val diferencia = abs((ruta.salida.toSecondOfDay() - pasajero.salida.toSecondOfDay()) / 60)

        // Sin coincidencia (sentido contrario, muy lejos a pie o poca cobertura): igual se muestra,
        // con desvío: el conductor lo recoge en su origen y lo deja en su destino.
        fun conDesvio(): Coincidencia {
            val km = kmPasajero
            val pasaje = (((km * tarifa).roundToInt() + REDONDEO / 2) / REDONDEO * REDONDEO).coerceAtLeast(PASAJE_MINIMO)
            val extra = (caminaSubir + caminaBajar) * 2 / 1000
            return Coincidencia(ruta, 0, origen, destino, 0, 0, km, tarifa, pasaje, diferencia, desvio = true, desvioKm = extra)
        }
        if (kmBajada <= kmSubida) return@mapNotNull conDesvio() // iría en sentido contrario
        if (caminaSubir > CAMINATA_MAXIMA_M || caminaBajar > CAMINATA_MAXIMA_M) return@mapNotNull conDesvio()

        val km = kmBajada - kmSubida
        val cobertura = (km / kmPasajero.coerceAtLeast(0.1)).coerceAtMost(1.0)
        val castigoCaminar = (caminaSubir + caminaBajar) / (2 * CAMINATA_MAXIMA_M) * 30
        val castigoHora = (diferencia / 60.0 * 20).coerceAtMost(20.0)
        val porcentaje = (cobertura * 100 - castigoCaminar - castigoHora).roundToInt().coerceIn(0, 100)
        if (porcentaje < 15) return@mapNotNull conDesvio()

        // Puerta a puerta (grupo de conocidos): siempre sube en su origen y baja en su destino; el
        // conductor recalcula su trayecto pasando por esos puntos. El porcentaje solo sirve para ordenar.
        val pasaje = (((kmPasajero * tarifa).roundToInt() + REDONDEO / 2) / REDONDEO * REDONDEO).coerceAtLeast(PASAJE_MINIMO)
        val cerca = caminaSubir <= CERCA_DE_LA_RUTA_M && caminaBajar <= CERCA_DE_LA_RUTA_M
        Coincidencia(ruta, porcentaje, origen, destino, 0, 0, kmPasajero, tarifa, pasaje, diferencia,
            desvio = !cerca, desvioKm = if (cerca) 0.0 else (caminaSubir + caminaBajar) * 2 / 1000)
    }.sortedWith(compareBy<Coincidencia> { it.desvio }.thenByDescending { it.porcentaje }
        .thenBy { it.desvioKm }.thenBy { it.diferenciaMin })
}

/** Kilómetros acumulados en cada punto del recorrido. */
private fun acumulados(linea: List<Punto>): DoubleArray {
    val r = DoubleArray(linea.size)
    for (i in 1 until linea.size) r[i] = r[i - 1] + metros(linea[i - 1], linea[i]) / 1000
    return r
}

/** El punto del recorrido más cercano a [p] y a cuántos km del inicio está. */
private fun proyectar(p: Punto, linea: List<Punto>, acumulado: DoubleArray): Pair<Punto, Double> {
    var mejor = linea[0]
    var mejorKm = 0.0
    var mejorDist = Double.MAX_VALUE
    // Plano local: suficiente para tramos urbanos cortos.
    val kx = cos(Math.toRadians(p.latitud))
    for (i in 0 until linea.size - 1) {
        val a = linea[i]; val b = linea[i + 1]
        val ax = a.longitud * kx; val ay = a.latitud
        val bx = b.longitud * kx; val by = b.latitud
        val px = p.longitud * kx; val py = p.latitud
        val dx = bx - ax; val dy = by - ay
        val largo = dx * dx + dy * dy
        val t = if (largo == 0.0) 0.0 else (((px - ax) * dx + (py - ay) * dy) / largo).coerceIn(0.0, 1.0)
        val q = Punto(a.latitud + (b.latitud - a.latitud) * t, a.longitud + (b.longitud - a.longitud) * t)
        val d = metros(p, q)
        if (d < mejorDist) {
            mejorDist = d; mejor = q
            mejorKm = acumulado[i] + (acumulado[i + 1] - acumulado[i]) * t
        }
    }
    return mejor to mejorKm
}

/** Distancia en metros entre dos puntos (haversine). */
fun metros(a: Punto, b: Punto): Double {
    val r = 6_371_000.0
    val dLat = Math.toRadians(b.latitud - a.latitud)
    val dLng = Math.toRadians(b.longitud - a.longitud)
    val h = sin(dLat / 2) * sin(dLat / 2) +
        cos(Math.toRadians(a.latitud)) * cos(Math.toRadians(b.latitud)) * sin(dLng / 2) * sin(dLng / 2)
    return 2 * r * asin(sqrt(h))
}
