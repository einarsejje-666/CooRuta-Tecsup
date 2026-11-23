package pe.cooruta.app.data

import pe.cooruta.app.data.repositorio.RutasRepositorio.Solicitud

/** Emisión media estimada de un auto particular, en kg de CO₂ por km (referencia ~120 g/km). */
const val CO2_KG_POR_KM = 0.12

/** Viajes compartidos ya hechos (reservas aceptadas hasta hoy) y lo que significan. */
data class Impacto(val viajes: Int, val km: Double, val centimos: Int) {
    val co2Kg: Double get() = km * CO2_KG_POR_KM

    companion object {
        fun de(lista: List<Solicitud>, hoy: String = java.time.LocalDate.now().toString()): Impacto {
            val hechos = lista.filter { it.estado == "aceptada" && it.fecha <= hoy }
            return Impacto(hechos.size, hechos.sumOf { it.kmCompartidos }, hechos.sumOf { it.pasajeCentimos })
        }
    }
}
