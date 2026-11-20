package pe.cooruta.app.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * Los colores de CooRuta, en un solo lugar.
 *
 * Antes cada pantalla redeclaraba los suyos y el mismo azul estaba copiado en
 * tres archivos. Si hay que cambiar un tono, se cambia aquí y punto.
 */
object Coo {
    // Paleta definitiva: #0A0D14 (fondo), #28364E (superficie/borde), #556FF7 (acción), #FFFFFF (texto).
    // Campo y CampoActivo son pasos intermedios entre fondo y #28364E. Verde, rojo y ámbar son solo semánticos.
    /** Fondo de pantalla. */
    val Fondo = Color(0xFF0A0D14)

    /** Superficie de tarjetas, campos y hojas. */
    val Campo = Color(0xFF131A26)

    /** Un campo mientras está enfocado. */
    val CampoActivo = Color(0xFF1C2638)

    /** Borde de un campo o una tarjeta: separa sin gritar. */
    val Borde = Color(0xFF28364E)

    /** Borde decorativo: divisores dentro de una tarjeta, más callado que Borde. */
    val BordeSuave = Color(0xFF1C2638)

    /** Acción principal: azul de la paleta (#556FF7), con texto blanco. */
    val AzulBoton = Color(0xFF556FF7)

    /** Texto e iconos sobre el botón principal. */
    val SobreBoton = Color(0xFFFFFFFF)

    /** Texto secundario: etiquetas, ayudas, estados vacíos. */
    val TextoTenue = Color(0xFF9AA4B8)

    /** Texto principal. */
    val Texto = Color.White

    /** Discos de iconos y botones circulares sobre el mapa. */
    val PlomoOscuro = Color(0xFF28364E)

    /** El punto de "mi ubicación". Es el azul que usa todo mapa. */
    val AzulUbicacion = Color(0xFF556FF7)

    /** El destino. */
    val RojoDestino = Color(0xFFE04A3F)

    /** Texto sobre superficies claras, como el botón blanco de Google. */
    val TextoSobreClaro = Color(0xFF1F1F1F)

    /** Texto terciario sobre claro: notas al pie, letra chica. */
    val TextoGris = Color(0xFF5F5F5F)

    /** Verde del panel de sesión abierta. */
    val VerdeSesion = Color(0xFF1E3A2B)

    /** Mensajes de error: el texto y su fondo. */
    val RojoError = Color(0xFFFFB4AB)
    val FondoError = Color(0xFF4A1F22)
    /** Éxito / confirmado (verde semántico) como texto o indicador sobre fondo oscuro. */
    val Exito = Color(0xFF5CC98A)
    /** Fondo de un estado confirmado con texto blanco encima. */
    val ExitoFondo = Color(0xFF163323)
    /** Advertencia accesible (no está en la paleta base: se usa solo como color semántico). */
    val Advertencia = Color(0xFFFFC857)
    /** Neutro cálido: información privada o secundaria (p. ej. recorrido que no se publica). */
    val Neutro = Color(0xFF8A93A6)
    /** Cartel superior del viaje (#28364E, con texto blanco). */
    val Cartel = Color(0xFF28364E)
}
