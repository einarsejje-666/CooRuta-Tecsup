package pe.cooruta.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import pe.cooruta.app.data.repositorio.PerfilPublico

class ComunidadTest {
    @Test fun dominios() {
        assertNull(Comunidad.dominioCorporativo("ana@gmail.com"))
        assertEquals("unsa.edu.pe", Comunidad.dominioCorporativo("ana@UNSA.edu.pe"))
    }
    @Test fun claveIgnoraTildesYSufijos() =
        assertEquals(Comunidad.clave("Minera Cerro Verde S.A.A."), Comunidad.clave("minera  cerro verde"))

    @Test fun coincidencia() {
        val a = PerfilPublico("a", empresaClave = "unsa", empresaDominio = "unsa.edu.pe")
        assertEquals(Comunidad.Tipo.VERIFICADA, Comunidad.coincidencia(a, PerfilPublico("b", empresaDominio = "unsa.edu.pe")))
        assertEquals(Comunidad.Tipo.DECLARADA, Comunidad.coincidencia(a, PerfilPublico("c", empresaClave = "unsa")))
        assertNull(Comunidad.coincidencia(a, PerfilPublico("d", empresaClave = "otra")))
    }
}
