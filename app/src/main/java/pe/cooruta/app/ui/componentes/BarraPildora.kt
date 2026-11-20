package pe.cooruta.app.ui.componentes

import android.provider.Settings
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import pe.cooruta.app.ui.theme.Coo

/**
 * Barra flotante tipo píldora: la pestaña activa se ensancha y muestra una píldora de color
 * con el ícono relleno y su nombre; las demás, solo el ícono de contorno.
 * Respeta «quitar animaciones» del sistema.
 */
@Composable
fun BarraPildora(
    cantidad: Int,
    seleccion: Int,
    alElegir: (Int) -> Unit,
    etiqueta: (Int) -> String,
    modifier: Modifier = Modifier,
    colorBarra: Color = Coo.Campo,
    colorBorde: Color = Coo.Borde,
    colorActivo: Color = Coo.AzulBoton,
    colorSobreActivo: Color = Coo.SobreBoton,
    colorInactivo: Color = Coo.TextoTenue,
    icono: @Composable (indice: Int, color: Color, activo: Boolean) -> Unit
) {
    val ctx = LocalContext.current
    val sinMovimiento = remember {
        Settings.Global.getFloat(ctx.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    }
    val forma = RoundedCornerShape(50)
    Row(
        modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(start = 16.dp, end = 16.dp, bottom = 12.dp)
            .height(68.dp)
            .shadow(16.dp, forma, ambientColor = Color.Black, spotColor = Color.Black)
            .clip(forma)
            .background(colorBarra)
            .border(1.dp, colorBorde.copy(alpha = 0.6f), forma)
            .padding(6.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        repeat(cantidad) { i ->
            val activo = i == seleccion
            val ancho by animateFloatAsState(
                if (activo) 1.7f else 1f,
                if (sinMovimiento) snap() else spring(dampingRatio = 0.75f, stiffness = Spring.StiffnessMediumLow),
                label = "ancho"
            )
            val fondo by animateColorAsState(
                if (activo) colorActivo else colorActivo.copy(alpha = 0f),
                if (sinMovimiento) snap() else tween(220), label = "fondo"
            )
            val colorIcono by animateColorAsState(
                if (activo) colorSobreActivo else colorInactivo,
                if (sinMovimiento) snap() else tween(220), label = "icono"
            )
            Box(
                Modifier
                    .weight(ancho)
                    .fillMaxHeight()
                    .clip(forma)
                    .background(fondo)
                    .selectable(
                        selected = activo, onClick = { alElegir(i) }, role = Role.Tab,
                        interactionSource = remember { MutableInteractionSource() }, indication = null
                    )
                    .semantics { contentDescription = etiqueta(i) },
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    icono(i, colorIcono, activo)
                    AnimatedVisibility(
                        visible = activo,
                        enter = if (sinMovimiento) fadeIn(snap()) else fadeIn(tween(200, delayMillis = 60)) + expandVertically(),
                        exit = if (sinMovimiento) fadeOut(snap()) else fadeOut(tween(120)) + shrinkVertically()
                    ) {
                        Text(
                            etiqueta(i), color = colorSobreActivo, fontSize = 11.sp, fontWeight = FontWeight.Bold,
                            maxLines = 1, softWrap = false, modifier = Modifier.padding(top = 2.dp)
                        )
                    }
                }
            }
        }
    }
}
