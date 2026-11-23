package pe.cooruta.app.data

import org.junit.Assert.assertEquals
import org.junit.Test
import pe.cooruta.app.data.repositorio.RutasRepositorio.Solicitud

class ImpactoTest {
    private fun s(estado: String, fecha: String, km: Double, cent: Int) = Solicitud(id = fecha + estado, rutaId = "r",
        nombrePasajero = "A", fecha = fecha, subida = "a", bajada = "b", pasajeCentimos = cent, kmCompartidos = km,
        mensaje = "", estado = estado)

    @Test fun cuentaSoloAceptadasHastaHoy() {
        val i = Impacto.de(listOf(
            s("aceptada", "2026-10-01", 10.0, 250), s("aceptada", "2026-10-19", 5.0, 200),
            s("aceptada", "2026-10-25", 8.0, 200), s("cancelada", "2026-10-02", 9.0, 250)
        ), hoy = "2026-10-19")
        assertEquals(2, i.viajes)
        assertEquals(15.0, i.km, 1e-9)
        assertEquals(450, i.centimos)
        assertEquals(1.8, i.co2Kg, 1e-9)
    }
}
