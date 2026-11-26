package pe.cooruta.app.ui.inicio

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import pe.cooruta.app.data.Resultado
import pe.cooruta.app.data.repositorio.PerfilesRepositorio
import pe.cooruta.app.data.repositorio.RutasRepositorio.Solicitud
import pe.cooruta.app.ui.theme.Coo

data class EstadoCalificar(
    val calificadas: Set<String> = emptySet(),
    val cargado: Boolean = false,
    val enviando: String? = null
)

/** Calificaciones del usuario: cuáles ya hizo y enviar una nueva. Compartido por Solicitudes y Viaje. */
class CalificarViewModel(private val repo: PerfilesRepositorio) : ViewModel() {
    private val _estado = MutableStateFlow(EstadoCalificar())
    val estado: StateFlow<EstadoCalificar> = _estado.asStateFlow()

    fun cargar() {
        if (_estado.value.cargado) return
        viewModelScope.launch {
            val r = repo.reservasCalificadas()
            if (r is Resultado.Exito) _estado.update { it.copy(calificadas = r.valor, cargado = true) }
        }
    }

    fun calificar(s: Solicitud, comoConductor: Boolean, estrellas: Int, comentario: String, alAvisar: (String) -> Unit, alListo: () -> Unit) {
        if (_estado.value.enviando != null) return
        _estado.update { it.copy(enviando = s.id) }
        viewModelScope.launch {
            val destino = if (comoConductor) s.pasajeroId else s.conductorId
            val r = repo.calificar(s.id, destino, estrellas, comentario)
            _estado.update { e -> e.copy(enviando = null,
                calificadas = if (r is Resultado.Exito) e.calificadas + s.id else e.calificadas) }
            if (r is Resultado.Exito) { alAvisar("¡Gracias! Tu calificación ayuda a la comunidad."); alListo() }
            else if (r is Resultado.Error) alAvisar(r.mensaje)
        }
    }

    companion object {
        val Fabrica: ViewModelProvider.Factory = viewModelFactory { initializer { CalificarViewModel(PerfilesRepositorio()) } }
    }
}

/**
 * Botón para calificar a la otra persona de una reserva aceptada cuyo día ya llegó.
 * Si el viaje de hoy aún no termina, las reglas lo rechazan y se muestra el motivo.
 */
@Composable
fun BotonCalificar(
    s: Solicitud,
    comoConductor: Boolean,
    alAvisar: (String) -> Unit,
    vm: CalificarViewModel = viewModel(factory = CalificarViewModel.Fabrica)
) {
    if (s.estado != "aceptada" || s.fecha > java.time.LocalDate.now().toString()) return
    val estado by vm.estado.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { vm.cargar() }
    val nombre = (if (comoConductor) s.nombrePasajero else s.nombreConductor).ifBlank { if (comoConductor) "tu pasajero" else "tu conductor" }
    var abierto by rememberSaveable(s.id) { mutableStateOf(false) }
    if (s.id in estado.calificadas) {
        Text("Calificaste a $nombre", color = Coo.Exito, fontSize = 13.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(top = 8.dp))
        return
    }
    Text(
        "Calificar a $nombre",
        color = Coo.AzulUbicacion, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp).clip(RoundedCornerShape(12.dp))
            .clickable { abierto = true }.padding(vertical = 10.dp)
    )
    if (abierto) {
        DialogoCalificar(nombre, enviando = estado.enviando == s.id,
            alEnviar = { e, c -> vm.calificar(s, comoConductor, e, c, alAvisar) { abierto = false } },
            alCerrar = { abierto = false })
    }
}

@Composable
fun DialogoCalificar(nombre: String, enviando: Boolean, alEnviar: (Int, String) -> Unit, alCerrar: () -> Unit) {
    var estrellas by rememberSaveable { mutableIntStateOf(0) }
    var comentario by rememberSaveable { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = { if (!enviando) alCerrar() },
        containerColor = Coo.Campo,
        title = { Text("¿Cómo fue tu viaje con $nombre?", fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(Modifier.fillMaxWidth().wrapContentSize(Alignment.Center), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    (1..5).forEach { n ->
                        Text(
                            "★", fontSize = 36.sp,
                            color = if (n <= estrellas) Coo.AzulBoton else Coo.Borde,
                            modifier = Modifier.size(48.dp).clip(RoundedCornerShape(12.dp))
                                .clickable(enabled = !enviando) { estrellas = n }
                                .semantics { contentDescription = "$n de 5 estrellas" }
                                .wrapContentSize(Alignment.Center)
                        )
                    }
                }
                OutlinedTextField(
                    value = comentario, onValueChange = { comentario = it.take(280) },
                    label = { Text("Comentario (opcional)") }, enabled = !enviando,
                    modifier = Modifier.fillMaxWidth(), minLines = 2
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { alEnviar(estrellas, comentario) }, enabled = estrellas > 0 && !enviando) {
                Text(if (enviando) "Enviando…" else "Enviar")
            }
        },
        dismissButton = { TextButton(onClick = alCerrar, enabled = !enviando) { Text("Ahora no") } }
    )
}
