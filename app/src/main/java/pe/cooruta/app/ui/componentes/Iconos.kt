package pe.cooruta.app.ui.componentes

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.EventSeat
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Directions
import androidx.compose.material.icons.rounded.EventSeat
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material.icons.rounded.MyLocation
import androidx.compose.material.icons.rounded.Navigation
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SwapVert
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/*
 * Íconos de la app: ahora son los íconos Material de Google (familiares para cualquier usuario
 * de Android). Se conservan los nombres y parámetros de antes para no tocar las pantallas.
 */

/** Volver. */
@Composable
fun IconoAtras(modifier: Modifier = Modifier, color: Color = Color.White, tamano: Dp = 24.dp) =
    IconoM(FlechaAtras, color, tamano, modifier)

/** Adelante / abrir: flecha «>». */
@Composable
fun IconoAdelante(modifier: Modifier = Modifier, color: Color = Color.White, tamano: Dp = 24.dp) =
    IconoM(FlechaAdelante, color, tamano, modifier)

/** Desplegar: flecha hacia abajo. */
@Composable
fun IconoAbajo(modifier: Modifier = Modifier, color: Color = Color.White, tamano: Dp = 24.dp) =
    IconoM(FlechaAbajo, color, tamano, modifier)

/** Plegar: flecha hacia arriba. */
@Composable
fun IconoArriba(modifier: Modifier = Modifier, color: Color = Color.White, tamano: Dp = 24.dp) =
    IconoM(FlechaArriba, color, tamano, modifier)

/** Flechas de línea fina, el mismo estilo en toda la app. */
private fun flecha(nombre: String, trazo: String, espejo: Boolean) =
    ImageVector.Builder(nombre, 24.dp, 24.dp, 24f, 24f, autoMirror = espejo).apply {
        addPath(
            pathData = addPathNodes(trazo),
            fill = null,
            stroke = SolidColor(Color.White),
            strokeLineWidth = 2.4f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round
        )
    }.build()

private val FlechaAtras: ImageVector by lazy { flecha("flechaAtras", "M15 4.5 L7.5 12 L15 19.5", true) }
private val FlechaAdelante: ImageVector by lazy { flecha("flechaAdelante", "M9 4.5 L16.5 12 L9 19.5", true) }
private val FlechaAbajo: ImageVector by lazy { flecha("flechaAbajo", "M4.5 9 L12 16.5 L19.5 9", false) }
private val FlechaArriba: ImageVector by lazy { flecha("flechaArriba", "M4.5 15 L12 7.5 L19.5 15", false) }

/** Intercambiar origen y destino. */
@Composable
fun IconoIntercambiar(modifier: Modifier = Modifier, color: Color = Color.White, tamano: Dp = 24.dp) =
    IconoM(Icons.Rounded.SwapVert, color, tamano, modifier)

/** Mi ubicación. */
@Composable
fun IconoMiUbicacion(modifier: Modifier = Modifier, color: Color = Color.White, tamano: Dp = 24.dp) =
    IconoM(Icons.Rounded.MyLocation, color, tamano, modifier)

/** Cerrar. */
@Composable
fun IconoCerrar(modifier: Modifier = Modifier, color: Color = Color.White, tamano: Dp = 24.dp) =
    IconoM(Icons.Rounded.Close, color, tamano, modifier)

/** Listo / confirmado. */
@Composable
fun IconoCheck(modifier: Modifier = Modifier, color: Color = Color.White, tamano: Dp = 24.dp) =
    IconoM(Icons.Rounded.Check, color, tamano, modifier)

/** Capas del mapa. */
@Composable
fun IconoCapas(modifier: Modifier = Modifier, color: Color = Color.White, tamano: Dp = 24.dp) =
    IconoM(Icons.Rounded.Layers, color, tamano, modifier)

/** Acercar. */
@Composable
fun IconoMas(modifier: Modifier = Modifier, color: Color = Color.White, tamano: Dp = 24.dp) =
    IconoM(Icons.Rounded.Add, color, tamano, modifier)

/** Alejar. */
@Composable
fun IconoMenos(modifier: Modifier = Modifier, color: Color = Color.White, tamano: Dp = 24.dp) =
    IconoM(Icons.Rounded.Remove, color, tamano, modifier)

/** Buscar. */
@Composable
fun IconoBuscar(modifier: Modifier = Modifier, color: Color = Color.White, tamano: Dp = 24.dp) =
    IconoM(Icons.Rounded.Search, color, tamano, modifier)

/** Reloj: la hora de salida. */
@Composable
fun IconoReloj(modifier: Modifier = Modifier, color: Color = Color.White, tamano: Dp = 24.dp) =
    IconoM(Icons.Rounded.Schedule, color, tamano, modifier)

/** Advertencia. */
@Composable
fun IconoAlerta(modifier: Modifier = Modifier, color: Color = Color.White, tamano: Dp = 24.dp) =
    IconoM(Icons.Rounded.Warning, color, tamano, modifier)

/** Asiento: relleno o solo contorno. */
@Composable
fun IconoAsiento(
    modifier: Modifier = Modifier,
    color: Color = Color.White,
    tamano: Dp = 24.dp,
    relleno: Boolean = true
) = IconoM(if (relleno) Icons.Rounded.EventSeat else Icons.Outlined.EventSeat, color, tamano, modifier)

/** Perfil. */
@Composable
fun IconoPerfil(modifier: Modifier = Modifier, color: Color = Color.White, tamano: Dp = 24.dp) =
    IconoM(Icons.Rounded.Person, color, tamano, modifier)

/** Mis viajes / recorridos. */
@Composable
fun IconoViajes(modifier: Modifier = Modifier, color: Color = Color.White, tamano: Dp = 24.dp) =
    IconoM(Icons.Rounded.Directions, color, tamano, modifier)

/** Iniciar el viaje. */
@Composable
fun IconoIniciar(modifier: Modifier = Modifier, color: Color = Color.White, tamano: Dp = 24.dp) =
    IconoM(Icons.Rounded.Navigation, color, tamano, modifier)
