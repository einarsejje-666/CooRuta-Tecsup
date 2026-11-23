package pe.cooruta.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import pe.cooruta.app.data.EstadoViaje.EN_CURSO
import pe.cooruta.app.data.EstadoViaje.FINALIZADO
import pe.cooruta.app.data.EstadoViaje.PREPARANDO
import pe.cooruta.app.data.EstadoViaje.SALIENDO

class EstadosViajeTest {
    private val todos = listOf(PREPARANDO, SALIENDO, EN_CURSO, FINALIZADO)

    @Test fun transicionesDelViaje() {
        val permitidas = setOf(
            PREPARANDO to SALIENDO, PREPARANDO to EN_CURSO, PREPARANDO to FINALIZADO,
            SALIENDO to EN_CURSO, SALIENDO to FINALIZADO, EN_CURSO to FINALIZADO
        )
        for (de in todos) for (a in todos) assertEquals("$de→$a", (de to a) in permitidas, EstadoViaje.puedePasar(de, a))
        assertFalse(EstadoViaje.puedePasar("desconocido", EN_CURSO))
    }

    @Test fun ubicacionSoloMientrasNoTermina() {
        assertTrue(EstadoViaje.admiteUbicacion(PREPARANDO))
        assertTrue(EstadoViaje.admiteUbicacion(EN_CURSO))
        assertFalse(EstadoViaje.admiteUbicacion(FINALIZADO))
    }

    @Test fun recogidaSoloAvanza() {
        assertTrue(EstadoParada.puedePasar(null, EstadoParada.LLEGUE))
        assertTrue(EstadoParada.puedePasar(null, EstadoParada.A_BORDO))
        assertTrue(EstadoParada.puedePasar(EstadoParada.LLEGUE, EstadoParada.A_BORDO))
        assertFalse(EstadoParada.puedePasar(EstadoParada.A_BORDO, EstadoParada.LLEGUE))
        assertFalse(EstadoParada.puedePasar(EstadoParada.LLEGUE, EstadoParada.LLEGUE))
        assertFalse(EstadoParada.puedePasar(null, "aceptada")) // no reutiliza estados de reserva
    }

    @Test fun publicacionLimitadaPorTiempoYDistancia() {
        val p = Punto(-16.40, -71.53)
        val cerca = Punto(-16.40005, -71.53) // ~5 m
        val lejos = Punto(-16.4005, -71.53)  // ~55 m
        assertTrue(PoliticaUbicacion.debePublicar(null, null, p, 0))
        assertFalse(PoliticaUbicacion.debePublicar(p, 0, lejos, PoliticaUbicacion.INTERVALO_MS - 1))
        assertTrue(PoliticaUbicacion.debePublicar(p, 0, lejos, PoliticaUbicacion.INTERVALO_MS))
        assertFalse(PoliticaUbicacion.debePublicar(p, 0, cerca, PoliticaUbicacion.INTERVALO_MS))
        assertTrue(PoliticaUbicacion.debePublicar(p, 0, cerca, PoliticaUbicacion.LATIDO_MS))
    }

    @Test fun estadoDelSeguimiento() {
        assertEquals(Seguimiento.SIN_DATOS, PoliticaUbicacion.seguimiento(null, null, 1_000))
        assertEquals(Seguimiento.PAUSADO, PoliticaUbicacion.seguimiento(false, 0, 1_000))
        assertEquals(Seguimiento.EN_VIVO, PoliticaUbicacion.seguimiento(true, 0, PoliticaUbicacion.VIGENCIA_MS))
        assertEquals(Seguimiento.DESACTUALIZADO, PoliticaUbicacion.seguimiento(true, 0, PoliticaUbicacion.VIGENCIA_MS + 1))
    }
}
