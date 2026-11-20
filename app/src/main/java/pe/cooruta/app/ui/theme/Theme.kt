package pe.cooruta.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * La paleta de CooRuta metida dentro de un esquema de Material 3.
 *
 * Antes había dos sistemas de color que no se conocían: este archivo aplicaba
 * un esquema verde azulado que ninguna pantalla usaba, y las pantallas pintaban
 * sus colores a mano. Ahora `Coo` es la única fuente y los componentes de
 * Material los heredan: un `Button` sale azul sin que nadie se lo diga.
 *
 * CooRuta es una app oscura y punto: no hay tema claro. Si el teléfono está en
 * modo claro, la app se sigue viendo así, que es lo que diseñamos.
 *
 * Paleta definitiva: fondo #0A0D14, superficies hacia #28364E, acción #556FF7
 * con texto blanco (≈4.2:1; los botones usan texto grande y en negrita).
 */
private val EsquemaCooRuta = darkColorScheme(
    // Acción principal: el botón de Continuar / Siguiente / Enviar código.
    primary = Coo.AzulBoton,
    onPrimary = Coo.SobreBoton,
    primaryContainer = Coo.CampoActivo,
    onPrimaryContainer = Coo.Texto,
    inversePrimary = Coo.AzulUbicacion,

    // Acento: el azul de "mi ubicación".
    secondary = Coo.AzulUbicacion,
    onSecondary = Coo.Texto,
    secondaryContainer = Coo.PlomoOscuro,
    onSecondaryContainer = Coo.Texto,

    // Atención: el rojo del destino.
    tertiary = Coo.Exito,
    onTertiary = Coo.Fondo,
    tertiaryContainer = Coo.FondoError,
    onTertiaryContainer = Coo.RojoError,

    // Fondos y superficies.
    background = Coo.Fondo,
    onBackground = Coo.Texto,
    surface = Coo.Fondo,
    onSurface = Coo.Texto,
    surfaceVariant = Coo.Campo,
    onSurfaceVariant = Coo.TextoTenue,
    surfaceTint = Coo.Fondo,

    // La escalera de contenedores: de lo más hundido a lo más levantado.
    surfaceContainerLowest = Coo.Fondo,
    surfaceContainerLow = Coo.Campo,
    surfaceContainer = Coo.Campo,
    surfaceContainerHigh = Coo.CampoActivo,
    surfaceContainerHighest = Coo.Borde,
    surfaceDim = Coo.Fondo,
    surfaceBright = Coo.PlomoOscuro,

    // Bordes: `outline` para lo que delimita, `outlineVariant` para divisores.
    outline = Coo.Borde,
    outlineVariant = Coo.BordeSuave,

    // Lo que se invierte: snackbars y similares.
    inverseSurface = Coo.Texto,
    inverseOnSurface = Coo.Fondo,

    // Errores.
    error = Coo.RojoError,
    onError = Coo.FondoError,
    errorContainer = Coo.FondoError,
    onErrorContainer = Coo.RojoError,

    scrim = Color.Black
)

@Composable
fun CooRutaTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = EsquemaCooRuta,
        typography = Typography,
        content = content
    )
}
