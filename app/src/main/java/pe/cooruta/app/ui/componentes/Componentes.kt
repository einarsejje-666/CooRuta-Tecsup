package pe.cooruta.app.ui.componentes

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp

/** Campo de texto estándar de la app. */
@Composable
fun CampoTexto(
    valor: String,
    alCambiar: (String) -> Unit,
    etiqueta: String,
    modifier: Modifier = Modifier,
    ayuda: String? = null,
    esError: Boolean = false,
    prefijo: String? = null,
    tipoTeclado: KeyboardType = KeyboardType.Text,
    accionIme: ImeAction = ImeAction.Done,
    /** true: el texto de [etiqueta] va como marcador dentro del campo, sin etiqueta flotante. */
    comoMarcador: Boolean = false
) {
    val soporte: (@Composable () -> Unit)? =
        if (ayuda != null) {
            { Text(ayuda) }
        } else null

    val textoPrefijo: (@Composable () -> Unit)? =
        if (prefijo != null) {
            { Text(prefijo) }
        } else null

    OutlinedTextField(
        value = valor,
        onValueChange = alCambiar,
        modifier = modifier.fillMaxWidth(),
        label = if (comoMarcador) null else { { Text(etiqueta) } },
        placeholder = if (comoMarcador) { { Text(etiqueta) } } else null,
        singleLine = true,
        isError = esError,
        supportingText = soporte,
        prefix = textoPrefijo,
        shape = RoundedCornerShape(14.dp),
        keyboardOptions = KeyboardOptions(keyboardType = tipoTeclado, imeAction = accionIme)
    )
}

/** Botón principal, de ancho completo. */
@Composable
fun BotonPrimario(
    texto: String,
    alPulsar: () -> Unit,
    modifier: Modifier = Modifier,
    habilitado: Boolean = true,
    cargando: Boolean = false
) {
    Button(
        onClick = alPulsar,
        modifier = modifier
            .fillMaxWidth()
            .height(52.dp),
        enabled = habilitado && !cargando,
        shape = RoundedCornerShape(14.dp)
    ) {
        if (cargando) {
            CircularProgressIndicator(
                modifier = Modifier.height(20.dp),
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.onPrimary
            )
        } else {
            Text(texto, style = MaterialTheme.typography.titleMedium)
        }
    }
}

/** Franja para mostrar el error de un formulario. */
@Composable
fun MensajeError(mensaje: String?, modifier: Modifier = Modifier) {
    if (mensaje.isNullOrBlank()) return
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.errorContainer
    ) {
        Text(
            text = mensaje,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            color = MaterialTheme.colorScheme.onErrorContainer,
            style = MaterialTheme.typography.bodyMedium
        )
    }
}

/** Encabezado con título y subtítulo. */
@Composable
fun EncabezadoAuth(titulo: String, subtitulo: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = titulo,
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground
        )
        Text(
            text = subtitulo,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
