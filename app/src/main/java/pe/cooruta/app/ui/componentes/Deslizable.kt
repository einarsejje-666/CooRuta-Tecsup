package pe.cooruta.app.ui.componentes

import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import pe.cooruta.app.ui.theme.Coo

/** Pregunta antes de una acción real (cancelar, rechazar). */
data class Confirmacion(val titulo: String, val texto: String, val boton: String)

/**
 * Tarjeta que se quita deslizándola a la izquierda o a la derecha: debajo aparece un fondo rojo
 * con el basurero del lado que se descubre. Con [confirmacion] pregunta antes y, si se cancela,
 * la tarjeta vuelve a su sitio. Envolver en `key(id)` si va dentro de un forEach.
 */
@Composable
fun DeslizableParaBorrar(
    alBorrar: () -> Unit,
    confirmacion: Confirmacion? = null,
    habilitado: Boolean = true,
    contenido: @Composable () -> Unit
) {
    val estado = rememberSwipeToDismissBoxState()
    var preguntar by remember { mutableStateOf(false) }
    LaunchedEffect(estado.currentValue) {
        if (estado.currentValue != SwipeToDismissBoxValue.Settled) {
            if (confirmacion == null) alBorrar() else preguntar = true
        }
    }
    if (preguntar && confirmacion != null) {
        val volver: () -> Unit = { preguntar = false }
        AlertDialog(
            onDismissRequest = volver,
            containerColor = Coo.Campo,
            title = { Text(confirmacion.titulo, color = Coo.Texto, fontWeight = FontWeight.Bold) },
            text = { Text(confirmacion.texto, color = Coo.TextoTenue) },
            confirmButton = {
                TextButton(onClick = { preguntar = false; alBorrar() }) {
                    Text(confirmacion.boton, color = Coo.RojoDestino, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = { TextButton(onClick = volver) { Text("Volver", color = Coo.Texto) } }
        )
    }
    // Con confirmación, la tarjeta vuelve a su sitio al cerrar el diálogo: si se aceptó,
    // desaparece sola cuando el servidor confirma el cambio.
    LaunchedEffect(preguntar) {
        if (!preguntar && confirmacion != null && estado.currentValue != SwipeToDismissBoxValue.Settled) estado.reset()
    }
    SwipeToDismissBox(
        gesturesEnabled = habilitado,
        state = estado,
        backgroundContent = {
            val direccion = estado.dismissDirection
            Box(
                Modifier.fillMaxSize().clip(RoundedCornerShape(16.dp))
                    .background(if (direccion == SwipeToDismissBoxValue.Settled) Color.Transparent else Coo.RojoDestino)
                    .padding(horizontal = 24.dp),
                contentAlignment = if (direccion == SwipeToDismissBoxValue.StartToEnd) Alignment.CenterStart else Alignment.CenterEnd
            ) { IconoBasurero(Color.White, 24.dp) }
        }
    ) { contenido() }
}

/** Basurero de líneas: tapa, asa y cuerpo con tres franjas. */
@Composable
fun IconoBasurero(color: Color, tamano: Dp = 24.dp) =
    IconoM(androidx.compose.material.icons.Icons.Rounded.DeleteOutline, color, tamano)
