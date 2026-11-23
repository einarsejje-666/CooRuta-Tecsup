package pe.cooruta.app.data

import kotlin.math.cos
import kotlin.math.sqrt

/**
 * Umbrales iniciales para elegir un tramo público (configurables). Reducen lo que se ve cerca de
 * los extremos, pero no garantizan anonimato.
 */
data class ReglasTramo(
    /** Distancia mínima de cualquier punto del tramo al origen y al destino privados. */
    val distanciaMinExtremosM: Double = 300.0,
    /** Qué tan lejos de la línea del recorrido puede marcarse un punto. */
    val toleranciaRecorridoM: Double = 50.0,
    /** Largo mínimo del tramo, medido sobre el recorrido. */
    val separacionMinM: Double = 200.0
)

/** Cómo se publicó el tramo que ve Buscar (campo `tramoPublico` de la ruta). */
/** COMPLETO: el conductor eligió mostrar todo su recorrido, incluidos origen y destino. */
enum class TipoTramo(val valor: String) { AUTOMATICO("automatico"), ELEGIDO("elegido"), COMPLETO("completo"), NINGUNO("ninguno") }

data class TramoPublicado(val tipo: TipoTramo, val puntos: List<Punto>)

sealed interface EleccionTramo {
    data class Valido(val puntos: List<Punto>) : EleccionTramo
    data class Invalido(val mensaje: String) : EleccionTramo
}

object TramoPublico {

    fun longitud(pts: List<Punto>): Double = pts.zipWithNext().sumOf { (a, b) -> metros(a, b) }

    /** Utilizable por Buscar: al menos dos puntos distintos y longitud positiva. */
    fun utilizable(pts: List<Punto>): Boolean = pts.distinct().size >= 2 && longitud(pts) > 0.0

    /**
     * Tramo automático: el tramo **contiguo** más largo del recorrido cuyos puntos están todos fuera
     * de [radioM] del origen y del destino. Nunca une partes separadas por una zona privada.
     */
    fun automatico(completos: List<Punto>, origen: Punto, destino: Punto, radioM: Double): List<Punto> =
        tramoContiguoMasLargo(completos) { metros(it, origen) >= radioM && metros(it, destino) >= radioM }

    /** ¿Hay algún tramo contiguo lo bastante largo y lejos de ambos extremos para poder elegirlo? */
    fun hayEspacio(completos: List<Punto>, origen: Punto, destino: Punto, reglas: ReglasTramo = ReglasTramo()): Boolean {
        val tramo = tramoContiguoMasLargo(completos) {
            metros(it, origen) >= reglas.distanciaMinExtremosM && metros(it, destino) >= reglas.distanciaMinExtremosM
        }
        return utilizable(tramo) && longitud(tramo) >= reglas.separacionMinM
    }

    /**
     * Tramo entre dos marcas [a] y [b] sobre el recorrido original: se proyectan sobre la línea, se
     * ordenan en el sentido del recorrido y se toman sus curvas originales entre ambas.
     */
    fun elegir(completos: List<Punto>, origen: Punto, destino: Punto, a: Punto, b: Punto,
               reglas: ReglasTramo = ReglasTramo()): EleccionTramo {
        if (completos.size < 2) return EleccionTramo.Invalido("Tu ruta no tiene un recorrido calculado.")
        val pa = proyectar(completos, a); val pb = proyectar(completos, b)
        val tol = reglas.toleranciaRecorridoM.toInt()
        if (pa.distancia > reglas.toleranciaRecorridoM || pb.distancia > reglas.toleranciaRecorridoM)
            return EleccionTramo.Invalido("Marca el inicio y el fin sobre la línea de tu recorrido (a menos de $tol m).")
        val (ini, fin) = if (pa.posicion <= pb.posicion) pa to pb else pb to pa
        if (fin.posicion - ini.posicion < reglas.separacionMinM)
            return EleccionTramo.Invalido("El inicio y el fin deben estar separados al menos ${reglas.separacionMinM.toInt()} m sobre tu recorrido.")
        val tramo = (listOf(ini.punto) + completos.subList(ini.segmento + 1, fin.segmento + 1) + fin.punto)
            .zipWithNext().filter { (x, y) -> x != y }.map { it.first }.plus(fin.punto)
        val dmin = reglas.distanciaMinExtremosM
        if (tramo.any { metros(it, origen) < dmin || metros(it, destino) < dmin })
            return EleccionTramo.Invalido("El tramo pasa a menos de ${dmin.toInt()} m de tu origen o de tu destino. Elige marcas más alejadas.")
        if (!utilizable(tramo)) return EleccionTramo.Invalido("Ese tramo no tiene longitud suficiente.")
        return EleccionTramo.Valido(tramo)
    }

    // ------------------------------------------------------------ apoyo

    private fun tramoContiguoMasLargo(pts: List<Punto>, dentro: (Punto) -> Boolean): List<Punto> {
        var mejor = emptyList<Punto>()
        var actual = mutableListOf<Punto>()
        fun cerrar() { if (longitud(actual) > longitud(mejor) || mejor.isEmpty()) mejor = actual.toList(); actual = mutableListOf() }
        for (p in pts) if (dentro(p)) actual.add(p) else if (actual.isNotEmpty()) cerrar()
        if (actual.isNotEmpty()) cerrar()
        return mejor
    }

    private data class Proyeccion(val punto: Punto, val segmento: Int, val posicion: Double, val distancia: Double)

    /** Punto más cercano sobre la línea, su segmento, su posición (m) y la distancia (m) a la marca. */
    private fun proyectar(linea: List<Punto>, p: Punto): Proyeccion {
        var mejor: Proyeccion? = null
        var acumulado = 0.0
        for (i in 0 until linea.size - 1) {
            val a = linea[i]; val b = linea[i + 1]
            val kx = 111_320.0 * cos(Math.toRadians(a.latitud)); val ky = 110_540.0
            val bx = (b.longitud - a.longitud) * kx; val by = (b.latitud - a.latitud) * ky
            val px = (p.longitud - a.longitud) * kx; val py = (p.latitud - a.latitud) * ky
            val largo2 = bx * bx + by * by
            val t = if (largo2 == 0.0) 0.0 else ((px * bx + py * by) / largo2).coerceIn(0.0, 1.0)
            val dx = px - t * bx; val dy = py - t * by
            val dist = sqrt(dx * dx + dy * dy)
            val largo = sqrt(largo2)
            if (mejor == null || dist < mejor.distancia) mejor = Proyeccion(
                Punto(a.latitud + t * (b.latitud - a.latitud), a.longitud + t * (b.longitud - a.longitud)),
                i, acumulado + t * largo, dist
            )
            acumulado += largo
        }
        return mejor!!
    }
}
