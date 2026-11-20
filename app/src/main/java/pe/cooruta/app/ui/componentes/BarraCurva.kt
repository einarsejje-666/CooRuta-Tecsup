package pe.cooruta.app.ui.componentes

import android.provider.Settings
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import pe.cooruta.app.ui.theme.Coo
import kotlin.math.roundToInt

private val ALTO_BARRA = 74.dp
private val DIAMETRO = 52.dp
private val SOBRESALE = 30.dp   // espacio sobre la barra para el círculo
private val HOLGURA = 6.dp      // aire entre el círculo y el fondo de la ola
private val ESQUINA = 24.dp
private val MARGEN = 6.dp
private val AZUL_CLARO = Color(0xFF8A9BFF)
private val AZUL_HONDO = Color(0xFF3A4FD0)

/**
 * Barra con ola bajo la pestaña activa, círculo con degradado y nombre bajo cada ícono.
 * Al cambiar: el círculo se hunde dentro de la barra mientras la ola se aplana y viaja,
 * y luego emerge con rebote en la nueva pestaña. Respeta «quitar animaciones».
 */
@Composable
fun BarraCurva(
    cantidad: Int,
    seleccion: Int,
    alElegir: (Int) -> Unit,
    etiqueta: (Int) -> String,
    modifier: Modifier = Modifier,
    colorBarra: Color = Coo.CampoActivo,
    degradado: List<Color> = listOf(AZUL_CLARO, Coo.AzulBoton, AZUL_HONDO),
    colorIconoActivo: Color = Coo.SobreBoton,
    colorIconoInactivo: Color = Coo.TextoTenue,
    colorTextoActivo: Color = Coo.Texto,
    icono: @Composable (indice: Int, color: Color) -> Unit
) {
    val ctx = LocalContext.current
    val sinMovimiento = remember {
        Settings.Global.getFloat(ctx.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    }
    val posicion = remember { Animatable(seleccion.toFloat()) } // en celdas
    val hundir = remember { Animatable(0f) }                     // 0 arriba · 1 dentro de la barra
    var enCirculo by remember { mutableStateOf(seleccion) }

    LaunchedEffect(seleccion) {
        if (sinMovimiento || (posicion.value == seleccion.toFloat() && hundir.value == 0f)) {
            posicion.snapTo(seleccion.toFloat()); hundir.snapTo(0f); enCirculo = seleccion
            return@LaunchedEffect
        }
        coroutineScope {
            val viaje = launch {
                posicion.animateTo(seleccion.toFloat(), tween(360, delayMillis = 110, easing = FastOutSlowInEasing))
            }
            hundir.animateTo(1f, tween(200, easing = FastOutLinearInEasing))
            enCirculo = seleccion
            viaje.join()
            hundir.animateTo(0f, spring(dampingRatio = 0.5f, stiffness = 360f))
        }
    }

    val d = LocalDensity.current
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val margen = with(d) { MARGEN.toPx() }
        val celda = (with(d) { maxWidth.toPx() } - 2 * margen) / cantidad
        val radio = with(d) { DIAMETRO.toPx() } / 2
        val arriba = with(d) { SOBRESALE.toPx() }
        val holgura = with(d) { HOLGURA.toPx() }
        val esquina = with(d) { ESQUINA.toPx() }
        val bajada = radio * 2 + holgura * 2
        fun centroX() = margen + celda * (posicion.value + 0.5f)

        // 1) Círculo (debajo de la barra, para que «se hunda» detrás de ella).
        Box(
            Modifier
                .offset {
                    IntOffset((centroX() - radio).roundToInt(), (arriba - radio + bajada * hundir.value).roundToInt())
                }
                .size(DIAMETRO)
                .shadow(10.dp, CircleShape, ambientColor = Coo.AzulBoton, spotColor = Coo.AzulBoton)
                .background(Brush.linearGradient(degradado), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Box(Modifier.graphicsLayer { alpha = (1f - hundir.value * 1.6f).coerceIn(0f, 1f) }) {
                icono(enCirculo, colorIconoActivo)
            }
        }

        // 2) Barra con la ola.
        Canvas(Modifier.matchParentSize()) {
            val x = centroX()
            val forma = Path().apply {
                addRoundRect(RoundRect(0f, arriba, size.width, size.height,
                    topLeftCornerRadius = CornerRadius(esquina), topRightCornerRadius = CornerRadius(esquina)))
            }
            val prof = (radio + holgura) * (1f - hundir.value).coerceIn(0f, 1.2f)
            if (prof < 0.5f) { drawPath(forma, colorBarra); return@Canvas }
            val w = radio * 2.5f
            val ola = Path().apply {
                moveTo(x - w, arriba - 1f); lineTo(x - w, arriba)
                cubicTo(x - w * 0.55f, arriba, x - radio * 1.25f, arriba + prof, x, arriba + prof)
                cubicTo(x + radio * 1.25f, arriba + prof, x + w * 0.55f, arriba, x + w, arriba)
                lineTo(x + w, arriba - 1f); close()
            }
            drawPath(Path.combine(PathOperation.Difference, forma, ola), colorBarra)
        }

        // 3) Íconos y nombres.
        Column(Modifier.fillMaxWidth()) {
            Spacer(Modifier.height(SOBRESALE))
            Row(Modifier.fillMaxWidth().height(ALTO_BARRA).padding(horizontal = MARGEN)) {
                repeat(cantidad) { i ->
                    val activa = i == seleccion
                    val colorTexto by animateColorAsState(if (activa) colorTextoActivo else colorIconoInactivo, tween(220), label = "texto")
                    Box(
                        Modifier.weight(1f).fillMaxHeight()
                            .selectable(
                                selected = activa, onClick = { alElegir(i) }, role = Role.Tab,
                                interactionSource = remember { MutableInteractionSource() }, indication = null
                            )
                            .semantics { contentDescription = etiqueta(i) }
                    ) {
                        Box(
                            Modifier.align(Alignment.TopCenter).padding(top = 10.dp)
                                .graphicsLayer { alpha = if (i == enCirculo) hundir.value.coerceIn(0f, 1f) else 1f }
                        ) { icono(i, colorIconoInactivo) }
                        Text(
                            etiqueta(i), color = colorTexto, fontSize = 11.sp,
                            fontWeight = if (activa) FontWeight.Bold else FontWeight.Medium,
                            maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 10.dp, start = 2.dp, end = 2.dp)
                        )
                    }
                }
            }
            Spacer(Modifier.navigationBarsPadding())
        }
    }
}
