package pe.cooruta.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import pe.cooruta.app.data.repositorio.RutasRepositorio.Solicitud

class PlanificadorRecorridoTest {
    private fun p(lat: Double, lng: Double) = Punto(lat, lng)
    private fun parada(id: String, punto: Punto) = ParadaRecorrido(id, punto)
    private fun reserva(id: String, estado: String = "aceptada", rutaId: String = "r1", punto: Punto? = p(-16.4, -71.5)) =
        Solicitud(id = id, rutaId = rutaId, nombrePasajero = "P", subida = "s", bajada = "b", pasajeCentimos = 200,
            kmCompartidos = 1.0, mensaje = "", estado = estado, subidaPunto = punto)

    /** Recorrido en «U»: este por la latitud −16,40, norte, y oeste de vuelta por −16,38. */
    private val enU = listOf(p(-16.40, -71.56), p(-16.40, -71.52), p(-16.38, -71.52), p(-16.38, -71.56))

    @Test fun ordenaPorPosicionSobreElRecorridoNoPorLineaRecta() {
        val vuelta = parada("vuelta", p(-16.38, -71.559)) // ~2,2 km en línea recta, pero al final del recorrido
        val ida = parada("ida", p(-16.40, -71.525))       // ~3,7 km en línea recta, al inicio del recorrido
        assertTrue(metros(enU.first(), vuelta.punto) < metros(enU.first(), ida.punto))
        assertEquals(listOf("ida", "vuelta"), PlanificadorRecorrido.ordenar(listOf(vuelta, ida), enU).map { it.reservaId })
    }

    @Test fun sinRecorridoConservaElOrdenRecibido() {
        val a = parada("a", p(-16.38, -71.559)); val b = parada("b", p(-16.40, -71.525))
        assertEquals(listOf(a, b), PlanificadorRecorrido.ordenar(listOf(a, b), emptyList()))
    }

    @Test fun excluyeCanceladasRecogidasCompletadasYOtrasRutas() {
        val confirmadas = listOf(
            reserva("ok"), reserva("llego"), reserva("abordo"), reserva("cancelada", estado = "cancelada"),
            reserva("otra", rutaId = "r2"), reserva("sinPunto", punto = null)
        )
        val paradas = mapOf("llego" to EstadoParada.LLEGUE, "abordo" to EstadoParada.A_BORDO)
        assertEquals(listOf("ok", "llego"), PlanificadorRecorrido.pendientes(confirmadas, "r1", paradas).map { it.reservaId })
    }

    @Test fun respetaElLimiteDeCoordenadasDeDirections() {
        val muchas = (1..30).map { parada("r$it", p(-16.40, -71.56 + it * 0.001)) }
        val origen = p(-16.41, -71.57); val destino = p(-16.35, -71.50)
        val conDestino = PlanificadorRecorrido.planear(origen, muchas, destino)!!
        assertEquals(PlanificadorRecorrido.MAX_COORDENADAS, conDestino.coordenadas.size)
        assertEquals(23, conDestino.incluidas.size)
        assertEquals(muchas.drop(23), conDestino.omitidas)
        assertEquals(destino, conDestino.coordenadas.last())
        val sinDestino = PlanificadorRecorrido.planear(origen, muchas, null)!!
        assertEquals(24, sinDestino.incluidas.size)
        assertEquals(PlanificadorRecorrido.MAX_COORDENADAS, sinDestino.coordenadas.size)
    }

    @Test fun sinRecogidasNiDestinoNoHayRecorrido() {
        assertNull(PlanificadorRecorrido.planear(p(-16.4, -71.5), emptyList(), null))
        assertEquals(2, PlanificadorRecorrido.planear(p(-16.4, -71.5), emptyList(), p(-16.3, -71.4))!!.coordenadas.size)
    }

    @Test fun rumboSoloConDesplazamientoReal() {
        val a = Muestra(p(-16.40, -71.53), 0)
        val este = Muestra(p(-16.40, -71.5295), 5_000)       // ~53 m al este
        val quieto = Muestra(p(-16.40003, -71.53002), 5_000) // ~4 m de oscilación
        assertEquals(90.0, Rumbo.actualizar(a, este, null)!!, 1.0)
        assertEquals(10.0, Rumbo.actualizar(a, quieto, 10.0)!!, 0.0)
        assertNull(Rumbo.actualizar(a, quieto, null))
        assertEquals(10.0, Rumbo.actualizar(a, Muestra(este.punto, 60_000), 10.0)!!, 0.0) // muestras muy separadas
        assertEquals(a, Rumbo.nuevaAncla(a, quieto))
        assertEquals(este, Rumbo.nuevaAncla(a, este))
    }
}
