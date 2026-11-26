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
import pe.cooruta.app.data.Muestra
import pe.cooruta.app.data.PoliticaUbicacion
import pe.cooruta.app.data.Punto
import pe.cooruta.app.data.Resultado

/** Respuestas retrasadas de Firestore frente a pausas, cierres y cambios de cuenta. */
@OptIn(ExperimentalCoroutinesApi::class)
class SeguimientoConductorTest {
    private val a = Punto(-16.40, -71.53)
    private val b = Punto(-16.41, -71.53)
    private fun TestScope.avanzar(ms: Long) { advanceTimeBy(ms); runCurrent() }
    /** Muestra GPS tomada ahora (reloj virtual). */
    private fun TestScope.muestra(p: Punto) = Muestra(p, testScheduler.currentTime)

    private class Falso(val demoraEnvio: Long = 1_000, val pausaOk: Boolean = true) {
        val llamadas = mutableListOf<String>()
        suspend fun publicar(id: String, m: Muestra): Resultado<Unit> {
            llamadas += "publicar"; delay(demoraEnvio); return Resultado.Exito(Unit)
        }
        suspend fun pausar(id: String): Resultado<Unit> {
            llamadas += "pausar"; return if (pausaOk) Resultado.Exito(Unit) else Resultado.Error("sin red")
        }
    }

    private fun TestScope.nuevo(f: Falso) =
        SeguimientoConductor(backgroundScope, f::publicar, f::pausar, reloj = { testScheduler.currentTime })

    @Test fun pausaDuranteUnEnvioNoReactivaNiActualiza() = runTest {
        val f = Falso(); val s = nuevo(f)
        s.activar("v1"); s.alRecibirMuestra(muestra(a)); runCurrent()
        avanzar(100)
        s.pausar()
        avanzar(2_000)
        assertFalse(s.estado.value.activo)
        assertNull(s.estado.value.ultimoEnvioMs)
        assertEquals(listOf("publicar", "pausar"), f.llamadas) // la pausa se escribe después del envío
    }

    @Test fun cambioDeCuentaDescartaLaRespuestaPendiente() = runTest {
        val f = Falso(); val s = nuevo(f)
        s.activar("v1"); s.alRecibirMuestra(muestra(a)); runCurrent()
        avanzar(100)
        s.detener(escribirPausa = false)
        avanzar(2_000)
        assertEquals(EstadoSeguimiento(), s.estado.value)
        assertEquals(listOf("publicar"), f.llamadas)
    }

    @Test fun envioQueEsperabaTurnoNoSeHaceTrasPausar() = runTest {
        val f = Falso(); val s = nuevo(f)
        s.activar("v1"); s.alRecibirMuestra(muestra(a))
        s.pausar() // antes de que la corrutina del envío corra
        avanzar(2_000)
        assertTrue(f.llamadas.isEmpty())
    }

    @Test fun reactivarIgnoraLaRespuestaViejaYPermiteEnviarDeNuevo() = runTest {
        val f = Falso(); val s = nuevo(f)
        s.activar("v1"); s.alRecibirMuestra(muestra(a)); runCurrent()
        avanzar(100); s.pausar()
        avanzar(100); s.activar("v1")
        avanzar(900) // t = 1100: la respuesta vieja ya llegó y se descartó
        assertTrue(s.estado.value.activo)
        assertNull(s.estado.value.ultimoEnvioMs)
        s.alRecibirMuestra(muestra(b)); runCurrent()
        avanzar(1_100)
        assertEquals(testScheduler.currentTime - 1_100, s.estado.value.ultimoEnvioMs)
        assertEquals(listOf("publicar", "pausar", "publicar"), f.llamadas)
    }

    @Test fun pausaSinConfirmarSeAvisaSinPrometerla() = runTest {
        val f = Falso(demoraEnvio = 0, pausaOk = false); val s = nuevo(f)
        s.activar("v1"); s.alRecibirMuestra(muestra(a)); runCurrent()
        s.pausar(); runCurrent()
        assertFalse(s.estado.value.activo)
        assertTrue(s.estado.value.error!!.contains("desactualizada"))
    }

    @Test fun alDestruirseEncolaLaPausaYOlvidaLaSesion() = runTest {
        val f = Falso(demoraEnvio = 0); val s = nuevo(f)
        s.activar("v1"); s.alRecibirMuestra(muestra(a)); runCurrent()
        val encoladas = mutableListOf<String>()
        s.cerrarAlDestruir { encoladas += it }
        assertEquals(listOf("v1"), encoladas)
        assertEquals(EstadoSeguimiento(), s.estado.value)
    }

    // ---------------------------------------------------------------- muestras periódicas del GPS

    @Test fun conductorDetenidoConMuestrasNuevasSigueEnviandoPorLatido() = runTest {
        val f = Falso(demoraEnvio = 0); val s = nuevo(f)
        s.activar("v1")
        // Mismo lugar, una muestra nueva cada 5 s durante 2 min y 5 s.
        repeat(26) { s.alRecibirMuestra(muestra(a)); runCurrent(); avanzar(PoliticaUbicacion.MUESTREO_MS) }
        assertEquals(listOf("publicar", "publicar", "publicar"), f.llamadas) // t = 0, 60 s y 120 s
        assertEquals(120_000L, s.estado.value.ultimoEnvioMs)
        assertEquals(125_000L, s.estado.value.ultimaMuestraMs)
    }

    @Test fun gpsSinNovedadesNoReenviaLaMismaMuestraNiUnaVieja() = runTest {
        val f = Falso(demoraEnvio = 0); val s = nuevo(f)
        s.activar("v1")
        val primera = muestra(a)
        s.alRecibirMuestra(primera); runCurrent()
        avanzar(70_000)
        s.alRecibirMuestra(primera); runCurrent()                                   // la misma, otra vez
        s.alRecibirMuestra(Muestra(b, testScheduler.currentTime - 40_000)); runCurrent() // vieja
        assertEquals(listOf("publicar"), f.llamadas)
        assertEquals(0L, s.estado.value.ultimaMuestraMs) // la UI la ve como «esperando GPS»
    }

    @Test fun trasPausarODetenerNoSeEnvianMuestras() = runTest {
        val f = Falso(demoraEnvio = 0); val s = nuevo(f)
        s.activar("v1"); s.alRecibirMuestra(muestra(a)); runCurrent()
        s.pausar(); runCurrent()
        avanzar(70_000); s.alRecibirMuestra(muestra(b)); runCurrent()
        s.activar("v1"); s.detener(escribirPausa = false)
        avanzar(70_000); s.alRecibirMuestra(muestra(b)); runCurrent()
        assertEquals(listOf("publicar", "pausar"), f.llamadas)
    }
}
