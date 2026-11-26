package pe.cooruta.app.ui.inicio

import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.outlined.NotificationsNone
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.delay
import kotlin.time.Duration.Companion.seconds
import pe.cooruta.app.AvisosLocales
import pe.cooruta.app.ui.componentes.IconoAtras
import pe.cooruta.app.ui.theme.Coo
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Notificaciones en su propia ventana: agrupadas por día, las nuevas con punto azul.
 * Se borran deslizando a cualquier lado (aparece el basurero) o con el botón; «Limpiar» borra todo.
 * Solo es el historial guardado en el teléfono: no toca reservas ni la bandeja del sistema.
 */
@Composable
fun PantallaNotificaciones(alCerrar: () -> Unit) {
    val ctx = LocalContext.current
    val visto = remember { AvisosLocales.vistoMs(ctx) }
    val lista = remember { mutableStateListOf<AvisosLocales.Registro>().apply { addAll(AvisosLocales.historial(ctx)) } }
    LaunchedEffect(Unit) { AvisosLocales.marcarVistos(ctx) }
    var borrada by remember { mutableStateOf<AvisosLocales.Registro?>(null) }
    var confirmarLimpiar by remember { mutableStateOf(false) }
    // El aviso «Notificación eliminada · Deshacer» se va solo.
    LaunchedEffect(borrada) { if (borrada != null) { delay(4.seconds); borrada = null } }

    fun borrar(r: AvisosLocales.Registro) {
        lista.remove(r); AvisosLocales.eliminar(ctx, r); borrada = r
    }

    Dialog(onDismissRequest = alCerrar, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.fillMaxSize().background(Coo.Fondo).statusBarsPadding().navigationBarsPadding()) {
            // Barra superior
            Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = alCerrar, modifier = Modifier.semantics { contentDescription = "Volver" }) {
                    IconoAtras(color = Coo.Texto)
                }
                Text("Notificaciones", color = Coo.Texto, fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                if (lista.isNotEmpty()) TextButton(onClick = { confirmarLimpiar = true }) {
                    Text("Limpiar todo", color = Coo.AzulBoton, fontWeight = FontWeight.Bold)
                }
            }
            HorizontalDivider(color = Coo.BordeSuave)

            Box(Modifier.weight(1f).fillMaxWidth()) {
                if (lista.isEmpty()) Column(Modifier.align(Alignment.Center).padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    IconoCampanaVacia(Coo.Borde, 64.dp)
                    Spacer(Modifier.height(14.dp))
                    Text("No tienes notificaciones", color = Coo.TextoTenue, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                } else LazyColumn(
                    Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    lista.groupBy { diaDe(it.ms) }.forEach { (dia, delDia) ->
                        item(key = "dia_$dia") { CabeceraDia(dia) }
                        items(delDia, key = { "${it.ms}_${it.titulo.hashCode()}" }) { r ->
                            FilaDeslizable(r, nueva = r.ms > visto, alBorrar = { borrar(r) })
                        }
                    }
                }

                // Confirmación de borrado con «Deshacer».
                // Nombre completo: dentro de este Box el compilador elegía la versión de ColumnScope.
                androidx.compose.animation.AnimatedVisibility(
                    visible = borrada != null,
                    enter = slideInVertically { -it } + fadeIn(), exit = slideOutVertically { -it } + fadeOut(),
                    modifier = Modifier.align(Alignment.TopCenter).padding(12.dp)
                ) {
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Coo.CampoActivo)
                            .padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("✓", color = Coo.AzulBoton, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.width(10.dp))
                        Text("Notificación eliminada", color = Coo.Texto, fontSize = 14.sp, modifier = Modifier.weight(1f))
                        TextButton(onClick = {
                            borrada?.let { r ->
                                AvisosLocales.restaurar(ctx, r)
                                lista.clear(); lista.addAll(AvisosLocales.historial(ctx))
                            }
                            borrada = null
                        }) { Text("Deshacer", color = Coo.AzulBoton, fontWeight = FontWeight.Bold) }
                    }
                }
            }
        }
    }

    if (confirmarLimpiar) AlertDialog(
        onDismissRequest = { confirmarLimpiar = false },
        containerColor = Coo.Campo,
        title = { Text("¿Borrar todas las notificaciones?", color = Coo.Texto, fontWeight = FontWeight.Bold) },
        text = { Text("Se quitarán de esta lista. Tus reservas y viajes no cambian.", color = Coo.TextoTenue) },
        confirmButton = {
            TextButton(onClick = { AvisosLocales.limpiar(ctx); lista.clear(); borrada = null; confirmarLimpiar = false }) {
                Text("Borrar todo", color = Coo.RojoDestino, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = { TextButton(onClick = { confirmarLimpiar = false }) { Text("Cancelar", color = Coo.Texto) } }
    )
}

private val zona: ZoneId = ZoneId.systemDefault()
private fun diaDe(ms: Long): LocalDate = Instant.ofEpochMilli(ms).atZone(zona).toLocalDate()
private val formatoDia = DateTimeFormatter.ofPattern("dd/MM/yyyy")
private val formatoHora = DateTimeFormatter.ofPattern("HH:mm")

@Composable
private fun CabeceraDia(dia: LocalDate) {
    val hoy = LocalDate.now(zona)
    val texto = when (dia) { hoy -> "Hoy"; hoy.minusDays(1) -> "Ayer"; else -> dia.format(formatoDia) }
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        HorizontalDivider(Modifier.weight(1f), color = Coo.BordeSuave)
        Text(texto, color = Coo.TextoTenue, fontSize = 12.sp, fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(horizontal = 10.dp).clip(RoundedCornerShape(50)).background(Coo.Campo)
                .padding(horizontal = 12.dp, vertical = 5.dp))
        HorizontalDivider(Modifier.weight(1f), color = Coo.BordeSuave)
    }
}

/** Tarjeta que se borra al deslizarla a la izquierda o a la derecha. */
@Composable
private fun FilaDeslizable(r: AvisosLocales.Registro, nueva: Boolean, alBorrar: () -> Unit) {
    val estado = rememberSwipeToDismissBoxState()
    LaunchedEffect(estado.currentValue) {
        if (estado.currentValue != SwipeToDismissBoxValue.Settled) alBorrar()
    }
    SwipeToDismissBox(
        state = estado,
        backgroundContent = {
            val alInicio = estado.dismissDirection == SwipeToDismissBoxValue.StartToEnd
            Box(
                Modifier.fillMaxSize().clip(RoundedCornerShape(16.dp))
                    .background(if (estado.dismissDirection == SwipeToDismissBoxValue.Settled) Color.Transparent else Coo.RojoDestino)
                    .padding(horizontal = 24.dp),
                contentAlignment = if (alInicio) Alignment.CenterStart else Alignment.CenterEnd
            ) { IconoBasurero(Color.White, 24.dp) }
        }
    ) {
        TarjetaNotificacion(r, nueva, alBorrar)
    }
}

@Composable
private fun TarjetaNotificacion(r: AvisosLocales.Registro, nueva: Boolean, alBorrar: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Coo.Campo)
            .padding(start = 14.dp, end = 6.dp, top = 14.dp, bottom = 10.dp).animateContentSize(),
        verticalAlignment = Alignment.Top
    ) {
        // Punto azul: llegó después de la última vez que abriste esta ventana.
        Box(Modifier.padding(top = 6.dp).size(8.dp).clip(CircleShape)
            .background(if (nueva) Coo.AzulBoton else Color.Transparent))
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(r.titulo, color = Coo.Texto, fontWeight = FontWeight.Bold, fontSize = 15.sp)
            Spacer(Modifier.height(3.dp))
            Text(r.texto, color = Coo.TextoTenue, fontSize = 14.sp, lineHeight = 19.sp)
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(Instant.ofEpochMilli(r.ms).atZone(zona).format(formatoHora), color = Coo.TextoTenue, fontSize = 12.sp,
                modifier = Modifier.padding(end = 8.dp))
            IconButton(onClick = alBorrar, modifier = Modifier.semantics { contentDescription = "Eliminar notificación" }) {
                Box(Modifier.size(36.dp).clip(CircleShape).background(Coo.CampoActivo), contentAlignment = Alignment.Center) {
                    IconoBasurero(Coo.TextoTenue, 18.dp)
                }
            }
        }
    }
}

/** Basurero de líneas: tapa, asa y cuerpo con tres franjas. */
@Composable
private fun IconoBasurero(color: Color, tamano: Dp) =
    pe.cooruta.app.ui.componentes.IconoM(androidx.compose.material.icons.Icons.Rounded.DeleteOutline, color, tamano)

/** Campana de líneas para el estado vacío. */
@Composable
private fun IconoCampanaVacia(color: Color, tamano: Dp) =
    pe.cooruta.app.ui.componentes.IconoM(androidx.compose.material.icons.Icons.Outlined.NotificationsNone, color, tamano)
