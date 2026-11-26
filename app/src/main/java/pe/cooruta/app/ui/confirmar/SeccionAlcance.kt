package pe.cooruta.app.ui.confirmar

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import pe.cooruta.app.ui.auth.Comunidad
import pe.cooruta.app.ui.auth.comunidadDe
import pe.cooruta.app.ui.theme.Coo

/**
 * Conductor: con quién comparte la ruta. Toda su comunidad, o solo los grupos que elija.
 * Ser del grupo no reserva nada: el conductor sigue confirmando cada solicitud.
 */
@Composable
fun SeccionAlcance(estado: EstadoConfirmar, vm: ConfirmarViewModel) {
    val comunidad = if (comunidadDe(com.google.firebase.auth.FirebaseAuth.getInstance().currentUser?.email) == Comunidad.TECSUP)
        "la comunidad Tecsup" else "la comunidad externa"
    val grupos = estado.misGrupos
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Coo.Campo)
            .border(1.dp, Coo.BordeSuave, RoundedCornerShape(16.dp)).padding(vertical = 6.dp)
    ) {
        Opcion("Toda mi comunidad", "La ven todos los integrantes de $comunidad.", !estado.soloGrupos) { vm.elegirSoloGrupos(false) }
        Opcion(
            "Solo grupos elegidos",
            if (grupos?.isEmpty() == true) "Aún no estás en ningún grupo. Créalos en Perfil → Mis grupos."
            else "Solo la ven los integrantes de los grupos que marques.",
            estado.soloGrupos, habilitada = grupos?.isNotEmpty() == true || estado.soloGrupos
        ) { vm.elegirSoloGrupos(true) }
        if (estado.soloGrupos) {
            grupos.orEmpty().forEach { g ->
                Row(
                    Modifier.fillMaxWidth().clickable { vm.alternarGrupo(g.id) }.semantics { role = Role.Checkbox }
                        .padding(start = 40.dp, end = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(
                        checked = g.id in estado.gruposElegidos, onCheckedChange = null,
                        colors = CheckboxDefaults.colors(checkedColor = Coo.AzulBoton, uncheckedColor = Coo.TextoTenue, checkmarkColor = Coo.SobreBoton),
                        modifier = Modifier.padding(12.dp)
                    )
                    Column(Modifier.weight(1f)) {
                        Text(g.nombre, color = Coo.Texto, fontSize = 15.sp)
                        Text(if (g.miembros == 1) "1 integrante" else "${g.miembros} integrantes", color = Coo.TextoTenue, fontSize = 12.sp)
                    }
                }
            }
            Text("Ser del grupo no reserva asiento: tú confirmas cada solicitud.", color = Coo.TextoTenue, fontSize = 12.sp,
                modifier = Modifier.padding(start = 52.dp, end = 12.dp, top = 4.dp, bottom = 8.dp))
        }
    }
}

@Composable
private fun Opcion(titulo: String, detalle: String, elegida: Boolean, habilitada: Boolean = true, alElegir: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(enabled = habilitada, onClick = alElegir).semantics { role = Role.RadioButton }
            .padding(horizontal = 4.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected = elegida, onClick = null, enabled = habilitada,
            colors = RadioButtonDefaults.colors(selectedColor = Coo.AzulBoton, unselectedColor = Coo.TextoTenue),
            modifier = Modifier.padding(12.dp))
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(titulo, color = if (habilitada) Coo.Texto else Coo.TextoTenue, fontSize = 15.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(2.dp))
            Text(detalle, color = Coo.TextoTenue, fontSize = 12.sp, lineHeight = 16.sp)
        }
        Spacer(Modifier.width(4.dp))
    }
}
