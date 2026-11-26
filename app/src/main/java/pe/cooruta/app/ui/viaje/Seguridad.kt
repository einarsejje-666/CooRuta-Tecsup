package pe.cooruta.app.ui.viaje

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import pe.cooruta.app.data.Punto
import pe.cooruta.app.ui.theme.Coo
import java.util.Locale

/** Central de emergencias de la Policía Nacional del Perú. */
const val NUMERO_EMERGENCIA = "105"

fun enlaceMapa(p: Punto): String = String.format(Locale.US, "https://maps.google.com/?q=%.5f,%.5f", p.latitud, p.longitud)

/** Botones "Compartir viaje" y "Emergencia". Usan apps del teléfono; CooRuta no guarda nada extra. */
@Composable
fun PanelSeguridad(textoViaje: String, ubicacion: Punto?, extra: (@Composable () -> Unit)? = null) {
    val ctx = LocalContext.current
    var emergencia by rememberSaveable { mutableStateOf(false) }
    fun compartir(texto: String) {
        val i = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, texto)
        ctx.startActivity(Intent.createChooser(i, "Compartir con"))
    }
    val conUbicacion = textoViaje + (ubicacion?.let { "\nÚltima ubicación: ${enlaceMapa(it)}" } ?: "")
    Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        Text("Compartir viaje", color = Coo.Texto, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center,
            modifier = Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).border(1.dp, Coo.Borde, RoundedCornerShape(12.dp))
                .clickable { compartir(conUbicacion) }.padding(vertical = 12.dp))
        Text("Emergencia", color = Coo.RojoError, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center,
            modifier = Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).border(1.dp, Coo.RojoError, RoundedCornerShape(12.dp))
                .clickable { emergencia = true }.padding(vertical = 12.dp))
        extra?.invoke()
    }
    if (emergencia) AlertDialog(
        onDismissRequest = { emergencia = false },
        containerColor = Coo.Campo,
        title = { Text("Emergencia", fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Llama a la Policía ($NUMERO_EMERGENCIA) o envía tu viaje y ubicación a alguien de confianza.",
                    color = Coo.TextoTenue, fontSize = 14.sp)
                if (ubicacion == null) Text("Aún no hay ubicación del viaje: se enviarán solo los datos del viaje.",
                    color = Coo.TextoTenue, fontSize = 12.sp)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                emergencia = false
                ctx.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:$NUMERO_EMERGENCIA")))
            }) { Text("Llamar al $NUMERO_EMERGENCIA", color = Coo.RojoError, fontWeight = FontWeight.Bold) }
        },
        dismissButton = {
            TextButton(onClick = { emergencia = false; compartir("Necesito ayuda. $conUbicacion") }) { Text("Enviar mi viaje") }
        }
    )
}
