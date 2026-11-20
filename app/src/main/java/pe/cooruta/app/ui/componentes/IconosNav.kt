package pe.cooruta.app.ui.componentes

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DirectionsCar
import androidx.compose.material.icons.outlined.Navigation
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.PersonAdd
import androidx.compose.material.icons.outlined.Place
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material.icons.rounded.DirectionsCar
import androidx.compose.material.icons.rounded.Navigation
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PersonAdd
import androidx.compose.material.icons.rounded.Place
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Star
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/*
 * Íconos de la barra inferior: Material de Google. `lleno = true` para la pestaña activa,
 * `false` (contorno) para las demás.
 */

/** Lupa: buscar viaje. */
@Composable
fun IconoLupa(modifier: Modifier = Modifier, color: Color = Color.White, lleno: Boolean = true, tamano: Dp = 24.dp) =
    IconoM(Icons.Rounded.Search, color, tamano, modifier)

/** Auto: rutas del conductor. */
@Composable
fun IconoAuto(modifier: Modifier = Modifier, color: Color = Color.White, lleno: Boolean = true, tamano: Dp = 24.dp) =
    IconoM(if (lleno) Icons.Rounded.DirectionsCar else Icons.Outlined.DirectionsCar, color, tamano, modifier)

/** Persona con «+»: solicitudes. */
@Composable
fun IconoSolicitudes(modifier: Modifier = Modifier, color: Color = Color.White, lleno: Boolean = true, tamano: Dp = 24.dp) =
    IconoM(if (lleno) Icons.Rounded.PersonAdd else Icons.Outlined.PersonAdd, color, tamano, modifier)

/** Flecha de navegación (como en los mapas): iniciar / seguir el viaje. */
@Composable
fun IconoNavegar(modifier: Modifier = Modifier, color: Color = Color.White, lleno: Boolean = true, tamano: Dp = 24.dp) =
    IconoM(if (lleno) Icons.Rounded.Navigation else Icons.Outlined.Navigation, color, tamano, modifier)

/** Pin de mapa: trayectos guardados. */
@Composable
fun IconoRuta(modifier: Modifier = Modifier, color: Color = Color.White, lleno: Boolean = true, tamano: Dp = 24.dp) =
    IconoM(if (lleno) Icons.Rounded.Place else Icons.Outlined.Place, color, tamano, modifier)

/** Persona: perfil. */
@Composable
fun IconoPersona(modifier: Modifier = Modifier, color: Color = Color.White, lleno: Boolean = true, tamano: Dp = 24.dp) =
    IconoM(if (lleno) Icons.Rounded.Person else Icons.Outlined.Person, color, tamano, modifier)

/** Rol conductor (insignias): auto. */
@Composable
fun IconoVolante(modifier: Modifier = Modifier, color: Color = Color.White, lleno: Boolean = true, tamano: Dp = 24.dp) =
    IconoM(if (lleno) Icons.Rounded.DirectionsCar else Icons.Outlined.DirectionsCar, color, tamano, modifier)

/** Mis conductores (favoritos): estrella. */
@Composable
fun IconoEstrella(modifier: Modifier = Modifier, color: Color = Color.White, lleno: Boolean = true, tamano: Dp = 24.dp) =
    IconoM(if (lleno) Icons.Rounded.Star else Icons.Outlined.StarBorder, color, tamano, modifier)
