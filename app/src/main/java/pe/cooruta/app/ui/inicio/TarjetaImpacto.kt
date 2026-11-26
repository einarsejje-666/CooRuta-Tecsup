package pe.cooruta.app.ui.inicio

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import pe.cooruta.app.data.Impacto
import pe.cooruta.app.data.repositorio.RutasRepositorio.Solicitud
import pe.cooruta.app.ui.horario.formatoSoles
import pe.cooruta.app.ui.theme.Coo
import java.util.Locale

/** Resumen de viajes compartidos: cantidad, km, dinero y CO₂ evitado (estimado). */
@Composable
fun TarjetaImpacto(lista: List<Solicitud>, comoConductor: Boolean) {
    val i = Impacto.de(lista)
    if (i.viajes == 0) {
        Text("Aún no tienes viajes completados. Aquí verás tus km compartidos, CO₂ evitado y dinero.",
            color = Coo.TextoTenue, fontSize = 13.sp,
            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Coo.Campo).padding(16.dp))
        return
    }
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Coo.Campo)
            .border(1.dp, Coo.BordeSuave, RoundedCornerShape(16.dp)).padding(16.dp)
    ) {
        Row(Modifier.fillMaxWidth()) {
            Dato("${i.viajes}", if (i.viajes == 1) "viaje" else "viajes", Modifier.weight(1f))
            Dato(String.format(Locale.US, "%.0f km", i.km), "compartidos", Modifier.weight(1f))
            Dato(String.format(Locale.US, "%.1f kg", i.co2Kg), "CO₂ evitado*", Modifier.weight(1f))
        }
        Text(
            (if (comoConductor) "Recibiste " else "Pagaste ") + formatoSoles(i.centimos) +
                " en total. *Estimado con 120 g de CO₂ por km de auto.",
            color = Coo.TextoTenue, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp)
        )
    }
}

@Composable
private fun Dato(valor: String, etiqueta: String, modifier: Modifier) {
    Column(modifier) {
        Text(valor, color = Coo.Texto, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Text(etiqueta, color = Coo.TextoTenue, fontSize = 12.sp)
    }
}
