package pe.cooruta.app.ui.inicio

import androidx.lifecycle.ViewModelStore
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import pe.cooruta.app.data.Resultado
import pe.cooruta.app.data.repositorio.*

@OptIn(ExperimentalCoroutinesApi::class)
class EmpresaViewModelTest {
    private class Fuente : FuenteEmpresas {
        val datos = MutableStateFlow(EstadoEmpresa(uid = "a", cargando = false))
        val respuesta = CompletableDeferred<Resultado<Unit>>()
        var llamadas = 0
        var escuchas = 0
        override fun observar() = flow {
            escuchas++
            try { emitAll(datos) } finally { escuchas-- }
        }
        override suspend fun aceptar(): Resultado<Unit> { llamadas++; return respuesta.await() }
        override suspend fun salir(): Resultado<Unit> { llamadas++; return respuesta.await() }
    }
    private fun escenario(prueba: suspend TestScope.(Fuente, EmpresaViewModel) -> Unit) = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val fuente = Fuente()
        val vm = EmpresaViewModel(fuente)
        val almacen = ViewModelStore().apply { put("empresa", vm) }
        try { runCurrent(); prueba(fuente, vm) }
        finally { almacen.clear(); runCurrent(); Dispatchers.resetMain() }
    }
    @Test fun dobleToqueSoloEnviaUnaAceptacionYPresentaElError() = escenario { f, vm ->
        vm.aceptar(); vm.aceptar(); runCurrent()
        assertEquals(1, f.llamadas)
        f.respuesta.complete(Resultado.Error("Invitación vencida")); runCurrent()
        assertEquals("Invitación vencida", vm.mensaje.value)
        assertFalse(vm.ocupado.value)
    }
    @Test fun respuestaTardiaNoApareceEnOtraCuenta() = escenario { f, vm ->
        vm.aceptar(); runCurrent()
        f.datos.value = EstadoEmpresa(uid = "b", cargando = false); runCurrent()
        f.respuesta.complete(Resultado.Error("Error de a")); runCurrent()
        assertEquals("b", vm.estado.value.uid)
        assertNull(vm.mensaje.value)
    }
    @Test fun cerrarSesionYVolverNoRecuperaRespuestaAntigua() = escenario { f, vm ->
        vm.salir(); runCurrent()
        f.datos.value = EstadoEmpresa(cargando = false); runCurrent()
        f.datos.value = EstadoEmpresa(uid = "a", cargando = false); runCurrent()
        f.respuesta.complete(Resultado.Exito(Unit)); runCurrent()
        assertNull(vm.mensaje.value)
    }
    @Test fun actualizarNoDuplicaEscuchasYRevocacionActualizaEstado() = escenario { f, vm ->
        vm.actualizar(); vm.actualizar(); runCurrent()
        assertEquals(1, f.escuchas)
        f.datos.value = EstadoEmpresa(uid = "a", cargando = false, miembro = true, activa = true,
            companeros = setOf("c")); runCurrent()
        assertTrue(vm.estado.value.disponible)
        f.datos.value = EstadoEmpresa(uid = "a", cargando = false); runCurrent()
        assertFalse(vm.estado.value.disponible)
        assertTrue(vm.estado.value.companeros.isEmpty())
    }
}
