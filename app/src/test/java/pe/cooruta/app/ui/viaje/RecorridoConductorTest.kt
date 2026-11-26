package pe.cooruta.app.ui.viaje

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import pe.cooruta.app.data.ParadaRecorrido
import pe.cooruta.app.data.Punto
import pe.cooruta.app.data.Resultado
import pe.cooruta.app.data.RutaCalculada

@OptIn(ExperimentalCoroutinesApi::class)
class RecorridoConductorTest {
    private fun TestScope.avanzar(ms: Long) { advanceTimeBy(ms); runCurrent() }
    private val origen = Punto(-16.41, -71.57)
    private val destino = Punto(-16.35, -71.50)
    private val r1 = ParadaRecorrido("r1", Punto(-16.40, -71.55))
    private val r2 = ParadaRecorrido("r2", Punto(-16.39, -71.54))
    private val lineaA = listOf(Punto(0.0, 0.0), Punto(0.0, 1.0))
    private val lineaB = listOf(Punto(1.0, 0.0), Punto(1.0, 1.0))

    @Test fun respuestaDeUnaSolicitudAnteriorSeDescarta() = runTest {
        val llamadas = mutableListOf<Int>()
        val c = RecorridoConductor(backgroundScope) { pts ->
            llamadas += pts.size
            if (pts.size == 4) { delay(1_000); Resultado.Exito(RutaCalculada(lineaA)) } else Resultado.Exito(RutaCalculada(lineaB))
        }
        c.actualizar(EntradaRecorrido(origen, listOf(r1, r2), destino)); runCurrent() // 4 puntos, lenta
        avanzar(100)
        c.actualizar(EntradaRecorrido(origen, listOf(r2), destino)); runCurrent()     // r1 ya a bordo
        avanzar(2_000)
        assertEquals(lineaB, c.estado.value.linea)
        assertEquals(listOf(r2), c.estado.value.recogidas)
        assertEquals(listOf(4, 3), llamadas)
    }

    @Test fun unaMuestraGpsNuevaNoVuelveAConsultar() = runTest {
        var llamadas = 0
        val c = RecorridoConductor(backgroundScope) { llamadas++; Resultado.Exito(RutaCalculada(lineaA)) }
        repeat(5) { i ->
            c.actualizar(EntradaRecorrido(Punto(-16.41 + i * 0.001, -71.57), listOf(r1), destino)); runCurrent()
        }
        assertEquals(1, llamadas)
        assertEquals(r1, c.estado.value.proxima)
    }

    @Test fun errorNoDibujaLineaYPermiteReintentar() = runTest {
        var falla = true
        val c = RecorridoConductor(backgroundScope) { if (falla) Resultado.Error("Sin conexión.") else Resultado.Exito(RutaCalculada(lineaA)) }
        c.actualizar(EntradaRecorrido(origen, listOf(r1), destino)); runCurrent()
        assertTrue(c.estado.value.linea.isEmpty()) // nunca una línea recta de reemplazo
        assertEquals("Sin conexión.", c.estado.value.error)
        assertEquals(r1, c.estado.value.proxima)   // la próxima recogida sigue visible
        falla = false
        c.reintentar(origen); runCurrent()
        assertEquals(lineaA, c.estado.value.linea)
        assertNull(c.estado.value.error)
    }

    @Test fun excepcionDelServicioEsError() = runTest {
        val c = RecorridoConductor(backgroundScope) { throw IllegalStateException("x") }
        c.actualizar(EntradaRecorrido(origen, listOf(r1), destino)); runCurrent()
        assertTrue(c.estado.value.error != null)
        assertFalse(c.estado.value.cargando)
    }

    @Test fun esperaUnaUbicacionRecienteAntesDeConsultar() = runTest {
        var llamadas = 0
        val c = RecorridoConductor(backgroundScope) { llamadas++; Resultado.Exito(RutaCalculada(lineaA)) }
        c.actualizar(EntradaRecorrido(null, listOf(r1), destino)); runCurrent()
        assertEquals(0, llamadas)
        assertTrue(c.estado.value.esperandoUbicacion)
        c.actualizar(EntradaRecorrido(origen, listOf(r1), destino)); runCurrent()
        assertEquals(1, llamadas)
        assertEquals(lineaA, c.estado.value.linea)
    }

    @Test fun detenerDescartaLaSolicitudEnCurso() = runTest {
        val c = RecorridoConductor(backgroundScope) { delay(1_000); Resultado.Exito(RutaCalculada(lineaA)) }
        c.actualizar(EntradaRecorrido(origen, listOf(r1), destino)); runCurrent()
        c.detener()
        avanzar(2_000)
        assertEquals(EstadoRecorrido(), c.estado.value)
    }

    @Test fun conservaLaEstimacionDeMapboxConSuHora() = runTest {
        val ruta = RutaCalculada(lineaA, metros = 2_000.0, segundos = 300.0, metrosPrimerTramo = 500.0, segundosPrimerTramo = 90.0)
        val c = RecorridoConductor(backgroundScope, reloj = { 42L }) { Resultado.Exito(ruta) }
        c.actualizar(EntradaRecorrido(origen, listOf(r1), destino)); runCurrent()
        assertEquals(ruta, c.estado.value.ruta)
        assertEquals(42L, c.estado.value.calculadoMs)
    }
}
