package pe.cooruta.app.ui.componentes

import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Ícono Material de Google (los mismos que usa Android), con color y tamaño.
 * Decorativo: el texto o el botón de al lado ya dice qué es (contentDescription = null).
 */
@Composable
fun IconoM(icono: ImageVector, color: Color, tamano: Dp = 24.dp, modifier: Modifier = Modifier) {
    Icon(icono, contentDescription = null, tint = color, modifier = modifier.size(tamano))
}
