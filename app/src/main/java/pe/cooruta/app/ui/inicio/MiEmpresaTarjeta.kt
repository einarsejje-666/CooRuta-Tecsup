package pe.cooruta.app.ui.inicio

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import pe.cooruta.app.ui.theme.Coo

/** Solo presenta el estado: pertenencia, errores y acciones se resuelven en ViewModel/Repository. */
@Composable
fun MiEmpresaTarjeta(vm: EmpresaViewModel = viewModel(factory = EmpresaViewModel.Fabrica)) {
    val e by vm.estado.collectAsStateWithLifecycle()
    val ocupado by vm.ocupado.collectAsStateWithLifecycle()
    val mensaje by vm.mensaje.collectAsStateWithLifecycle()
    var confirmar by remember(e.uid) { mutableStateOf(false) }
    val portapapeles = LocalClipboardManager.current
    Column(Modifier.fillMaxWidth().padding(top = 20.dp).clip(RoundedCornerShape(20.dp))
        .background(Coo.Campo).padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Mi empresa", style = MaterialTheme.typography.titleLarge, color = Coo.Texto, fontWeight = FontWeight.Bold)
        Text("Comparte el camino con tu comunidad de trabajo.", color = Coo.TextoTenue)
        when {
            e.cargando -> { CircularProgressIndicator(Modifier.size(24.dp)); Text("Verificando tu vinculación…", color = Coo.TextoTenue) }
            e.error != null -> {
                Text(e.error.orEmpty(), color = Coo.RojoError)
                TextButton(onClick = vm::actualizar) { Text("Reintentar") }
            }
            e.miembro -> {
                Text(e.nombre, color = Coo.Texto, fontWeight = FontWeight.Bold)
                Text("Vinculación verificada · Puedes activar «Solo mi empresa» en Buscar.", color = Coo.TextoTenue)
            }
            e.invitacionVencida -> {
                Text("Invitación a ${e.nombre} vencida", color = Coo.Texto, fontWeight = FontWeight.Bold)
                Text("Pide una nueva invitación al responsable. Mientras tanto, Buscar muestra todas las rutas públicas.", color = Coo.TextoTenue)
            }
            e.invitacion -> {
                Text("Invitación a ${e.nombre}", color = Coo.Texto, fontWeight = FontWeight.Bold)
                Text("Al aceptar, otros miembros podrán reconocer tus rutas públicas como parte de esta comunidad. La empresa no recibe acceso a tus reservas ni ubicaciones privadas.", color = Coo.TextoTenue)
                Button(onClick = vm::aceptar, enabled = !ocupado, modifier = Modifier.fillMaxWidth()) {
                    Text(if (ocupado) "Confirmando…" else "Aceptar invitación")
                }
            }
            else -> {
                Text("Aún no estás vinculado", color = Coo.Texto, fontWeight = FontWeight.Bold)
                Text("Pide al responsable una invitación individual para tu cuenta. Elegir una empresa por su nombre no verifica tu pertenencia.", color = Coo.TextoTenue)
            }
        }
        if (e.uid != null && !e.miembro) {
            TextButton(onClick = { portapapeles.setText(AnnotatedString(e.uid.orEmpty())) }) { Text("Copiar identificador para mi invitación") }
            TextButton(onClick = vm::actualizar) { Text("Actualizar invitación") }
        }
        if (e.miembro) {
            TextButton(onClick = { confirmar = true }, enabled = !ocupado) { Text("Salir de la empresa", color = Coo.RojoError) }
        }
        mensaje?.let { Text(it, color = Coo.TextoTenue) }
        Text("Vincularte es opcional. Tus rutas públicas siguen disponibles en la búsqueda general.", style = MaterialTheme.typography.bodySmall, color = Coo.TextoTenue)
    }
    if (confirmar) AlertDialog(onDismissRequest = { confirmar = false }, containerColor = Coo.Campo,
        title = { Text("¿Salir de la empresa?") },
        text = { Text("Dejarás de aparecer como compañero de esta empresa. Tus rutas y reservas continuarán igual. Para volver necesitarás otra invitación.") },
        confirmButton = { TextButton(onClick = { confirmar = false; vm.salir() }) { Text("Salir") } },
        dismissButton = { TextButton(onClick = { confirmar = false }) { Text("Seguir vinculado") } })
}
