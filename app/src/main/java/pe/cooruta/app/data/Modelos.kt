package pe.cooruta.app.data

/** Un lugar: lo que devuelve la búsqueda y lo que guardamos de una ruta. */
data class Lugar(
    val nombre: String,
    val detalle: String,
    val latitud: Double,
    val longitud: Double
)

/**
 * Un resultado de la búsqueda, todavía sin coordenadas.
 *
 * La Search Box API responde en dos tiempos: primero sugiere nombres, y solo
 * cuando la persona elige uno entrega su posición. Así no se paga por resolver
 * ocho sitios cuando solo interesa uno.
 */
data class Sugerencia(
    val id: String,
    val nombre: String,
    val detalle: String,
    /** "college", "restaurant"... Solo viene en puntos de interés. */
    val categoria: String?
)

/** Un punto pelado del recorrido. Sin nombre: son cientos y solo importan sus coordenadas. */
data class Punto(val latitud: Double, val longitud: Double)

/** El recorrido entre dos puntos: por dónde pasa, cuánto dura y cuánto mide. */
data class Trayecto(
    val puntos: List<Punto>,
    val segundos: Double,
    val metros: Double,
    /** Los extremos que pedimos, antes de que Mapbox los pegara a la calzada. */
    val desde: Punto,
    val hasta: Punto
) {
    /**
     * El recorrido con los dos extremos reales cosidos a los bordes.
     *
     * Mapbox devuelve la ruta pegada a las calles: su primer punto está en la
     * calzada más cercana, no donde está la persona. Sin esto, la línea azul
     * aparece flotando lejos del punto azul y del pin.
     */
    fun conExtremos(): List<Punto> = buildList {
        add(desde)
        addAll(puntos)
        add(hasta)
    }

    /** "8 min", "1 h 12 min". */
    fun duracionLegible(): String {
        val minutos = Math.round(segundos / 60.0).toInt().coerceAtLeast(1)
        return if (minutos < 60) {
            "$minutos min"
        } else {
            val horas = minutos / 60
            val resto = minutos % 60
            if (resto == 0) "$horas h" else "$horas h $resto min"
        }
    }

    /** "850 m", "2,1 km". Con coma decimal, que es como se escribe acá. */
    fun distanciaLegible(): String = if (metros < 1000) {
        "${Math.round(metros / 10.0) * 10} m"
    } else {
        val km = Math.round(metros / 100.0) / 10.0
        "${km.toString().replace('.', ',')} km"
    }
}

/**
 * La ruta que el conductor va armando en el asistente, paso a paso.
 * [origen] en null significa "mi ubicación actual".
 */
data class BorradorRuta(
    val origen: Lugar?,
    val destino: Lugar,
    val trayecto: Trayecto?,
    val horario: HorarioRuta? = null
)

/**
 * Paso 2 del asistente: cuándo se hace la ruta y en qué condiciones.
 * [asientos] y [pasajeCentimos] solo los pone el conductor; para un pasajero son null.
 */
data class HorarioRuta(
    val dias: Set<java.time.DayOfWeek>,
    val salida: java.time.LocalTime,
    val asientos: Int?,
    /** En céntimos para no arrastrar errores de decimales: S/ 4.50 = 450. */
    val pasajeCentimos: Int?
)
