package pe.cooruta.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import pe.cooruta.app.data.repositorio.MiRuta
import pe.cooruta.app.data.repositorio.RADIO_PRIVADO_M
import pe.cooruta.app.data.repositorio.armarRutaPublica
import pe.cooruta.app.data.repositorio.recorridoPublico
import java.time.LocalTime

/**
 * Buscar con rutas recortadas (formato nuevo) y con documentos antiguos.
 * Geometría sintética: recta de ~10,7 km en la latitud −16,40 (1 punto cada ~107 m).
 * El radio es el de producción; estas pruebas no lo ajustan.
 */
class BuscarCompatibilidadTest {
    private val linea = (0..100).map { Punto(-16.40, -71.56 + it * 0.001) }
    private val casaConductor = linea.first()
    private val destinoConductor = linea.last()

    private fun ruta(recorrido: List<Punto>, origen: Punto? = null, destino: Punto? = null) = MiRuta(
        id = "r", conductorId = "c", nombre = "C", origenPunto = origen, destinoPunto = destino,
        recorrido = recorrido, tarifaKmCentimos = 25, origen = "Origen", destino = "Destino",
        dias = emptySet(), salida = LocalTime.of(7, 30), duracionSegundos = null,
        asientos = 3, asientosLibres = 3, pasajeCentimos = null, activa = true
    )

    private fun pasajero(o: Punto, d: Punto) = MiRuta(
        id = "p", origenPunto = o, destinoPunto = d, origen = "", destino = "",
        dias = emptySet(), salida = LocalTime.of(7, 30), duracionSegundos = null,
        asientos = 0, asientosLibres = 0, pasajeCentimos = null, activa = true
    )

    private val publico = recorridoPublico(linea, casaConductor, destinoConductor)

    @Test fun recorteQuitaLosPuntosCercanosALosExtremos() {
        assertTrue(publico.size >= 2)
        assertTrue(publico.all { metros(it, casaConductor) >= RADIO_PRIVADO_M && metros(it, destinoConductor) >= RADIO_PRIVADO_M })
        assertTrue(publico.size < linea.size)
    }

    @Test fun pasajeroEnElTramoPublicoCoincideIgualQueConElDocumentoAntiguo() {
        val p = pasajero(Punto(-16.401, -71.53), Punto(-16.401, -71.49))
        val nueva = buscarCoincidencias(p, listOf(ruta(publico)))
        val antigua = buscarCoincidencias(p, listOf(ruta(linea, casaConductor, destinoConductor)))
        assertEquals(1, nueva.size)
        assertEquals(1, antigua.size)
        assertEquals(antigua[0].pasajeCentimos, nueva[0].pasajeCentimos)
        assertEquals(antigua[0].kmCompartidos, nueva[0].kmCompartidos, 0.05)
    }

    @Test fun ningunPuntoDeSubidaQuedaDentroDelRadioDelDomicilio() {
        // Pasajero junto a la casa del conductor: si coincide, sube fuera del radio privado.
        val p = pasajero(Punto(-16.4005, -71.5595), Punto(-16.401, -71.49))
        buscarCoincidencias(p, listOf(ruta(publico))).forEach {
            assertTrue(metros(it.subida, casaConductor) >= RADIO_PRIVADO_M * 0.99)
        }
    }

    @Test fun documentoAntiguoSinRecorridoUsaOrigenYDestino() {
        val p = pasajero(Punto(-16.4001, -71.5599), Punto(-16.4001, -71.4601))
        assertEquals(1, buscarCoincidencias(p, listOf(ruta(emptyList(), casaConductor, destinoConductor))).size)
    }

    @Test fun rutaNuevaSinRecorridoPublicoNoCoincideNiFalla() {
        val p = pasajero(Punto(-16.401, -71.53), Punto(-16.401, -71.49))
        assertTrue(buscarCoincidencias(p, listOf(ruta(emptyList()))).isEmpty())
    }

    @Test fun rutaCortaQuedaSinRecorridoPublico() {
        val corta = linea.take(15) // ~1,5 km: todo está a menos del radio de algún extremo
        assertTrue(recorridoPublico(corta, corta.first(), corta.last()).size < 2)
    }

    // ---------------------------------------------------------------- tramo público elegido / ninguno

    /** Ruta corta en «L» (~2,2 km): sin tramo automático; tramo elegido entre dos marcas. */
    private val corta = (0..10).map { Punto(-16.40, -71.56 + it * 0.001) } + (1..10).map { Punto(-16.40 + it * 0.001, -71.55) }
    private val tramoElegido = (TramoPublico.elegir(corta, corta.first(), corta.last(),
        Punto(-16.4001, -71.554), Punto(-16.396, -71.5501)) as EleccionTramo.Valido).puntos

    @Test fun pasajeroCercaDelOrigenPrivadoSoloRecibeElTramoAutorizado() {
        // Vive junto al origen privado: puede coincidir si camina hasta el tramo, pero sus puntos de
        // subida y bajada quedan dentro del tramo publicado y lejos del origen.
        val p = pasajero(Punto(-16.4002, -71.5595), Punto(-16.3955, -71.5502))
        val publica = ruta(tramoElegido)
        assertEquals(null, publica.origenPunto)
        assertEquals(null, publica.destinoPunto)
        buscarCoincidencias(p, listOf(publica)).forEach { c ->
            for (q in listOf(c.subida, c.bajada)) {
                assertTrue(tramoElegido.zipWithNext().any { (x, y) -> metros(x, q) + metros(q, y) <= metros(x, y) + 1.0 })
                assertTrue(metros(q, corta.first()) >= ReglasTramo().distanciaMinExtremosM - 1.0)
            }
        }
    }

    @Test fun documentoPublicoSinCoordenadasPrivadasNiRecorridoCompleto() {
        val origen = corta.first(); val destino = corta.last()
        val datos = mapOf(
            "conductorId" to "c", "origen" to mapOf("nombre" to "Casa", "latitud" to origen.latitud, "longitud" to origen.longitud),
            "destino" to mapOf("nombre" to "Tecsup", "latitud" to destino.latitud, "longitud" to destino.longitud),
            "recorrido" to corta.map { mapOf("lat" to it.latitud, "lng" to it.longitud) },
            "distanciaMetros" to 2200.0, "duracionSegundos" to 300L, "pasajeCentimos" to 600, "asientos" to 3
        )
        val publico = armarRutaPublica(datos, TramoPublicado(TipoTramo.ELEGIDO, tramoElegido))
        assertEquals("elegido", publico["tramoPublico"])
        assertEquals(emptyMap<String, Any>(), publico["origen"])
        assertEquals(emptyMap<String, Any>(), publico["destino"])
        assertFalse(publico.keys.any { it in setOf("distanciaMetros", "duracionSegundos", "pasajeCentimos") })
        val pts = (publico["recorrido"] as List<*>).map { m -> (m as Map<*, *>).let { Punto(it["lat"] as Double, it["lng"] as Double) } }
        assertEquals(tramoElegido, pts)
        assertFalse(origen in pts || destino in pts)
        val ninguno = armarRutaPublica(datos, TramoPublicado(TipoTramo.NINGUNO, emptyList()))
        assertEquals("ninguno", ninguno["tramoPublico"])
        assertFalse("recorrido" in ninguno)
    }

    @Test fun rutaSinTramoPublicoNoApareceEnBuscar() {
        val p = pasajero(Punto(-16.401, -71.53), Punto(-16.401, -71.49))
        assertTrue(buscarCoincidencias(p, listOf(ruta(emptyList()).copy(tramoPublico = "ninguno"))).isEmpty())
        assertFalse(ruta(emptyList()).copy(tramoPublico = "ninguno").visibleEnBuscar)
        assertTrue(ruta(publico).visibleEnBuscar)          // ruta larga: tramo automático
        assertFalse(ruta(emptyList()).visibleEnBuscar)     // ruta antigua sin tramo utilizable
    }
}
