package pe.cooruta.app.data.repositorio

import org.junit.Assert.assertTrue
import org.junit.Test

class MensajeFalloIaTest {
    @Test fun sinRed() =
        assertTrue(mensajeFalloIa(RuntimeException("x", java.net.UnknownHostException("host"))).startsWith("Sin conexión"))

    @Test fun appCheck() =
        assertTrue(mensajeFalloIa(IllegalStateException("App Check token is invalid")).contains("App Check"))

    @Test fun cuota() =
        assertTrue(mensajeFalloIa(IllegalStateException("HTTP 429 RESOURCE_EXHAUSTED")).contains("límite"))

    @Test fun general() =
        assertTrue(mensajeFalloIa(IllegalStateException("algo raro")).startsWith("No se pudo obtener"))
}
