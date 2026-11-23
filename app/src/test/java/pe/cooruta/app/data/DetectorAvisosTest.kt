package pe.cooruta.app.data

import org.junit.Assert.assertEquals
import org.junit.Test
import pe.cooruta.app.data.repositorio.RutasRepositorio.Solicitud

class DetectorAvisosTest {
    private fun s(id: String, estado: String) = Solicitud(id = id, rutaId = "r", nombrePasajero = "Ana",
        nombreConductor = "Luis", subida = "a", bajada = "b", pasajeCentimos = 250, kmCompartidos = 10.0,
        mensaje = "", estado = estado)

    @Test fun primeraLecturaNoAvisa() =
        assertEquals(0, DetectorAvisos(true).procesar(listOf(s("1", "pendiente"))).size)

    @Test fun conductorRecibeNuevaSolicitudYCancelacion() {
        val d = DetectorAvisos(true)
        d.procesar(listOf(s("1", "aceptada")))
        val avisos = d.procesar(listOf(s("1", "cancelada"), s("2", "pendiente")))
        assertEquals(listOf("Se liberó un asiento", "Nueva solicitud de asiento"), avisos.map { it.titulo })
    }

    @Test fun pasajeroRecibeRespuesta() {
        val d = DetectorAvisos(false)
        d.procesar(listOf(s("1", "pendiente")))
        assertEquals("¡Asiento confirmado!", d.procesar(listOf(s("1", "aceptada"))).single().titulo)
        assertEquals(0, d.procesar(listOf(s("1", "aceptada"))).size)
    }
}
