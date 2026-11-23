package pe.cooruta.app.data.repositorio

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import pe.cooruta.app.data.Resultado

class ExplicacionesRepositorioTest {
    private val opciones = listOf(ResumenOpcion("07:30", 5, 300, 400, 350, 82), ResumenOpcion("08:00", 25, 900, 100, 200, 60))

    @Test fun respuestaBienFormadaConservaElOrden() = runTest {
        val r = ExplicacionesRepositorio { "2. Segunda.\n1. **Primera.**" }.explicar(opciones)
        assertEquals(Resultado.Exito(listOf("Primera.", "Segunda.")), r)
    }

    @Test fun formatoInesperadoEsError() = runTest {
        assertTrue(ExplicacionesRepositorio { "1. Solo una." }.explicar(opciones) is Resultado.Error)
        assertTrue(ExplicacionesRepositorio { null }.explicar(opciones) is Resultado.Error)
    }

    @Test fun fallaDelServicioEsErrorSinRomper() = runTest {
        assertTrue(ExplicacionesRepositorio { throw RuntimeException("sin red") }.explicar(opciones) is Resultado.Error)
    }
}
