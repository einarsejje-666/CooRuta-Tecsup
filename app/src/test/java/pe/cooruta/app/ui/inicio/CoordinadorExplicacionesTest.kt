package pe.cooruta.app.ui.inicio

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import pe.cooruta.app.data.Resultado
import pe.cooruta.app.data.repositorio.ResumenOpcion

// El coordinador corre en backgroundScope; advanceUntilIdle no avanza ese trabajo,
// así que cada escenario avanza el tiempo virtual de forma explícita (espera = 500 ms).
@OptIn(ExperimentalCoroutinesApi::class)
class CoordinadorExplicacionesTest {
    private fun TestScope.avanzar(ms: Long) { advanceTimeBy(ms); runCurrent() }

    private fun opcion(min: Int) = ResumenOpcion("07:30", min, 300, 400, 350, 80)
    private val a = listOf(opcion(1))
    private val b = listOf(opcion(2))

    @Test fun noRepiteLlamadasNiLlamaPorCambiosRapidos() = runTest {
        val llamadas = mutableListOf<List<ResumenOpcion>>()
        val c = CoordinadorExplicaciones(backgroundScope, { llamadas += it; Resultado.Exito(it.map { "ok" }) }, 500)
        runCurrent()
        c.actualizar(a); c.actualizar(a)
        avanzar(100)
        c.actualizar(b) // antes de que termine la espera: [a] nunca se pide
        avanzar(600)
        c.actualizar(b) // mismos datos: no vuelve a llamar
        avanzar(600)
        assertEquals(listOf(b), llamadas)
        assertEquals(EstadoExplicaciones.Listas(listOf("ok")), c.estado.value)
    }

    @Test fun descartaRespuestaDeUnaBusquedaAnterior() = runTest {
        val c = CoordinadorExplicaciones(backgroundScope, { ops ->
            if (ops == a) { delay(700); Resultado.Exito(listOf("vieja")) } else Resultado.Exito(listOf("nueva"))
        }, 500)
        runCurrent()
        c.actualizar(a)
        avanzar(1_000) // [a] se pidió a los 500 ms y responde a los 1200 ms
        c.actualizar(b)
        avanzar(300) // t = 1300: la respuesta de [a] llegó y se descartó
        assertEquals(EstadoExplicaciones.Cargando, c.estado.value)
        avanzar(300) // t = 1600: [b] se pidió a los 1500 ms
        assertEquals(EstadoExplicaciones.Listas(listOf("nueva")), c.estado.value)
    }

    @Test fun errorVisibleYReintento() = runTest {
        var intentos = 0
        val c = CoordinadorExplicaciones(backgroundScope, {
            if (intentos++ == 0) Resultado.Error("Sin conexión.") else Resultado.Exito(listOf("ok"))
        }, 500)
        runCurrent()
        c.actualizar(a)
        avanzar(600)
        assertEquals(EstadoExplicaciones.Error("Sin conexión."), c.estado.value)
        c.reintentar()
        avanzar(600)
        assertEquals(EstadoExplicaciones.Listas(listOf("ok")), c.estado.value)
        assertEquals(2, intentos)
    }

    @Test fun excepcionDelServicioSeVuelveError() = runTest {
        val c = CoordinadorExplicaciones(backgroundScope, { throw IllegalStateException("x") }, 500)
        runCurrent()
        c.actualizar(a)
        avanzar(600)
        assertEquals(true, c.estado.value is EstadoExplicaciones.Error)
    }
}
