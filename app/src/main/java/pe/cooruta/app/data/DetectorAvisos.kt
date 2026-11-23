package pe.cooruta.app.data

import pe.cooruta.app.data.repositorio.RutasRepositorio.Solicitud

/** Un aviso a mostrar: [clave] sirve de id estable de la notificación. */
data class Aviso(val clave: String, val titulo: String, val texto: String)

/**
 * Compara el estado anterior de las reservas con el nuevo y decide qué avisar.
 * La primera lectura solo memoriza (no avisa de lo que ya existía al abrir la app).
 */
class DetectorAvisos(private val comoConductor: Boolean) {
    private var previo: Map<String, String>? = null

    fun procesar(lista: List<Solicitud>): List<Aviso> {
        val antes = previo
        previo = lista.associate { it.id to it.estado }
        if (antes == null) return emptyList()
        return lista.mapNotNull { s ->
            val anterior = antes[s.id]
            if (anterior == s.estado) return@mapNotNull null
            if (comoConductor) when {
                s.estado == "pendiente" -> Aviso(s.id, "Nueva solicitud de asiento",
                    "${s.nombrePasajero.ifBlank { "Un pasajero" }} quiere viajar contigo (${s.fecha}, ${s.horaSalida}).")
                // El fin de un acuerdo habitual se avisa una vez (push), no por cada fecha cancelada.
                s.estado == "cancelada" && anterior == "aceptada" && s.motivo != "acuerdo_terminado" && s.motivo != "no_se_presento" && s.motivo != "ruta_eliminada" -> Aviso(s.id, "Se liberó un asiento",
                    "${s.nombrePasajero.ifBlank { "Un pasajero" }} canceló su reserva del ${s.fecha}.")
                else -> null
            } else when (s.estado) {
                // Las fechas de un acuerdo habitual no se avisan una por una (se avisa el acuerdo).
                "aceptada" -> if (s.habitualId.isNotBlank()) null else Aviso(s.id, "¡Asiento confirmado!",
                    "${s.nombreConductor.ifBlank { "El conductor" }} te espera en ${s.subida} a las ${s.horaSalida}.")
                "invitada" -> Aviso(s.id, "Te invitan de nuevo",
                    "${s.nombreConductor.ifBlank { "El conductor" }} te invita otra vez a su viaje del ${s.fecha}. Entra a Solicitudes para aceptar.")
                "rechazada" -> Aviso(s.id, "Solicitud rechazada",
                    "${s.nombreConductor.ifBlank { "El conductor" }} no puede llevarte esta vez. Busca otra ruta.")
                else -> null
            }
        }
    }
}
