package pe.cooruta.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import pe.cooruta.app.data.repositorio.RADIO_PRIVADO_M

class TramoPublicoTest {
    /** Recta hacia el este en la latitud −16,40: un punto cada ~107 m. */
    private fun recta(n: Int) = (0 until n).map { Punto(-16.40, -71.56 + it * 0.001) }

    /** «L»: ~1,07 km al este y ~1,1 km al norte (puntos cada ~107 m / ~110 m). */
    private val ele = (0..10).map { Punto(-16.40, -71.56 + it * 0.001) } + (1..10).map { Punto(-16.40 + it * 0.001, -71.55) }
    private val esquina = ele[10]

    private fun auto(pts: List<Punto>) = TramoPublico.automatico(pts, pts.first(), pts.last(), RADIO_PRIVADO_M)
    private fun elegir(pts: List<Punto>, a: Punto, b: Punto, reglas: ReglasTramo = ReglasTramo()) =
        TramoPublico.elegir(pts, pts.first(), pts.last(), a, b, reglas)

    @Test fun utilizableExigeDosPuntosDistintosYLongitud() {
        assertFalse(TramoPublico.utilizable(emptyList()))
        assertFalse(TramoPublico.utilizable(listOf(Punto(-16.4, -71.5))))
        assertFalse(TramoPublico.utilizable(listOf(Punto(-16.4, -71.5), Punto(-16.4, -71.5))))
        assertTrue(TramoPublico.utilizable(listOf(Punto(-16.4, -71.5), Punto(-16.4, -71.501))))
    }

    @Test fun rutaCortaSinTramoUtilizableYLargaConTramo() {
        assertFalse(TramoPublico.utilizable(auto(recta(15))))  // ~1,5 km: todo cae en el recorte de 1 km
        val larga = auto(recta(101))                           // ~10,7 km
        assertTrue(TramoPublico.utilizable(larga))
        assertTrue(larga.all { metros(it, recta(101).first()) >= RADIO_PRIVADO_M && metros(it, recta(101).last()) >= RADIO_PRIVADO_M })
    }

    @Test fun elTramoAutomaticoNoUnePartesSeparadasPorLaZonaPrivada() {
        // Sale 3 km al este, vuelve junto al origen y sale 2 km al norte.
        val ida = (0..28).map { Punto(-16.40, -71.56 + it * 0.001) }
        val vuelta = (27 downTo 0).map { Punto(-16.4005, -71.56 + it * 0.001) }
        val norte = (1..20).map { Punto(-16.4005 + it * 0.001, -71.56) }
        val todo = ida + vuelta + norte
        val tramo = TramoPublico.automatico(todo, todo.first(), Punto(-16.30, -71.40), RADIO_PRIVADO_M)
        val inicio = todo.indexOf(tramo.first())
        assertEquals(todo.subList(inicio, inicio + tramo.size), tramo) // contiguo en el recorrido original
    }

    @Test fun tramoElegidoConservaCurvasYSentido() {
        val r = elegir(ele, Punto(-16.4001, -71.554), Punto(-16.396, -71.5501)) as EleccionTramo.Valido
        assertTrue(esquina in r.puntos)                                  // la curva sigue ahí
        assertTrue(r.puntos.first().longitud < r.puntos.last().longitud) // empieza en el tramo este
        assertTrue(r.puntos.last().latitud > -16.40)                     // termina en el tramo norte
        val invertido = elegir(ele, Punto(-16.396, -71.5501), Punto(-16.4001, -71.554)) as EleccionTramo.Valido
        assertEquals(r.puntos, invertido.puntos)                         // mismo sentido del recorrido
    }

    @Test fun marcasFueraDelRecorridoOCercaDeLosExtremosSeRechazan() {
        assertTrue(elegir(ele, Punto(-16.402, -71.554), Punto(-16.396, -71.5501)) is EleccionTramo.Invalido) // ~220 m fuera
        assertTrue(elegir(ele, Punto(-16.40, -71.559), Punto(-16.396, -71.5501)) is EleccionTramo.Invalido)  // ~100 m del origen
        assertTrue(elegir(ele, Punto(-16.40, -71.554), Punto(-16.40, -71.553)) is EleccionTramo.Invalido)    // ~107 m de largo
    }

    @Test fun losUmbralesSonConfigurables() {
        val cerca = Punto(-16.40, -71.5585) // ~160 m del origen
        assertTrue(elegir(ele, cerca, Punto(-16.396, -71.5501)) is EleccionTramo.Invalido)
        assertTrue(elegir(ele, cerca, Punto(-16.396, -71.5501), ReglasTramo(distanciaMinExtremosM = 100.0)) is EleccionTramo.Valido)
    }

    @Test fun sinEspacioSuficienteParaElegirUnTramo() {
        assertFalse(TramoPublico.hayEspacio(recta(6), recta(6).first(), recta(6).last()))  // ~530 m
        assertTrue(TramoPublico.hayEspacio(ele, ele.first(), ele.last()))
    }
}
