package pe.cooruta.app.data.repositorio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import pe.cooruta.app.data.Punto
import pe.cooruta.app.data.metros

class ReducirPuntosTest {
    /** Puntos distintos y ordenados (longitud creciente). */
    private fun linea(n: Int) = (0 until n).map { Punto(-16.40, -71.60 + it * 0.0005) }

    private fun comprobar(n: Int, max: Int) {
        val pts = linea(n)
        val r = reducirPuntos(pts, max)
        if (n < 2) { assertTrue(r.isEmpty()); return }
        assertEquals("n=$n max=$max", minOf(n, max), r.size)
        assertEquals(pts.first(), r.first())
        assertEquals(pts.last(), r.last())
        assertEquals("sin duplicados n=$n", r.size, r.toSet().size)
        assertTrue("en orden n=$n", r.zipWithNext().all { (a, b) -> a.longitud < b.longitud })
    }

    @Test fun limitesYTamaniosIntermedios() {
        for (n in listOf(0, 1, 2, 3, 149, 150, 151, 199, 200, 201, 250, 299, 300, 301, 499, 500, 501, 1000, 4999)) {
            comprobar(n, 150)
            comprobar(n, 500)
        }
    }

    @Test fun nuncaSuperaElMaximoEnTodoElRango() {
        for (n in 0..1200) comprobar(n, 150)
    }

    @Test fun casoReportado250Con150() {
        assertEquals(150, reducirPuntos(linea(250), 150).size)
    }

    @Test fun recortePublicoReducidoRespetaRadioYLimite() {
        val pts = linea(1000) // ~53 km
        val publico = reducirPuntos(recorridoPublico(pts, pts.first(), pts.last()), 150)
        assertTrue(publico.size in 2..150)
        assertTrue(publico.all { metros(it, pts.first()) >= RADIO_PRIVADO_M && metros(it, pts.last()) >= RADIO_PRIVADO_M })
    }
}
