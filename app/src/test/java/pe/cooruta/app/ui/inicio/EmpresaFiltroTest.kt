package pe.cooruta.app.ui.inicio

import org.junit.Assert.*
import org.junit.Test
import pe.cooruta.app.data.Punto
import pe.cooruta.app.data.repositorio.MiRuta
import pe.cooruta.app.data.repositorio.EstadoEmpresa
import java.time.LocalTime

class EmpresaFiltroTest {
    private val linea = (0..100).map { Punto(-16.40, -71.56 + it * 0.001) }
    private val conductor = MiRuta(id = "a", conductorId = "compañero", nombre = "A",
        recorrido = linea, tarifaKmCentimos = 25, origen = "Origen", destino = "Destino",
        dias = emptySet(), salida = LocalTime.of(7, 30), duracionSegundos = null,
        asientos = 3, asientosLibres = 3, pasajeCentimos = null, activa = true)
    private val trayecto = conductor.copy(id = "p", conductorId = "pasajero",
        origenPunto = linea[20], destinoPunto = linea[80])
    private val base = EstadoBuscar(misTrayectos = listOf(trayecto), trayectoId = "p",
        rutas = listOf(conductor, conductor.copy(id = "b", conductorId = "externo")))

    @Test fun todosConservaResultadosYFiltroSoloIncluyeMiembros() {
        assertEquals(2, base.coincidencias.size)
        val filtrado = base.copy(soloEmpresa = true, companerosEmpresa = setOf("compañero"))
        assertEquals(listOf("a"), filtrado.coincidencias.map { it.ruta.id })
        assertEquals(base.coincidencias.first().pasajeCentimos, filtrado.coincidencias.single().pasajeCentimos)
    }
    @Test fun sinVerificacionNoSeMuestranRutasComoCompaneros() {
        assertTrue(base.copy(soloEmpresa = true).coincidencias.isEmpty())
        assertTrue(base.copy(soloEmpresa = true, companerosEmpresa = setOf("revocado")).coincidencias.isEmpty())
    }
    @Test fun filtroNoConvierteTrayectoIncompatibleEnCoincidencia() {
        assertTrue(base.copy(soloEmpresa = true, companerosEmpresa = setOf("compañero"),
            rutas = listOf(conductor.copy(recorrido = linea.reversed()))).coincidencias.isEmpty())
    }
    @Test fun invitacionNoEsPertenenciaYErrorCierraFiltro() {
        assertFalse(EstadoEmpresa(cargando = false, activa = true, invitacion = true).disponible)
        val miembro = EstadoEmpresa(cargando = false, activa = true, miembro = true)
        assertTrue(miembro.disponible)
        assertFalse(miembro.copy(error = "Sin conexión").disponible)
        assertFalse(miembro.copy(activa = false).disponible)
    }
}
