package pe.cooruta.app.ui.componentes

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import pe.cooruta.app.ui.theme.Coo

/** Los tres pasos del asistente, con nombre al lado de cada número. */
@Composable
fun PasosCompactos(pasoActual: Int, modifier: Modifier = Modifier) {
    val pasos = listOf("Ruta", "Horario", "Confirmar")
    Row(
        modifier = modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) {
                contentDescription = "Paso $pasoActual de ${pasos.size}: ${pasos[pasoActual - 1]}"
            },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        pasos.forEachIndexed { indice, nombre ->
            val numero = indice + 1
            val hecho = numero < pasoActual
            val actual = numero == pasoActual
            Box(
                modifier = Modifier
                    .size(24.dp)
                    .clip(CircleShape)
                    .background(
                        when {
                            hecho -> Coo.AzulUbicacion
                            actual -> Coo.AzulBoton
                            else -> Coo.Campo
                        }
                    )
                    .border(
                        width = if (actual) 2.dp else 1.dp,
                        color = when {
                            hecho -> Coo.AzulUbicacion
                            actual -> Coo.AzulUbicacion
                            else -> Coo.Borde
                        },
                        shape = CircleShape
                    ),
                contentAlignment = Alignment.Center
            ) {
                if (hecho) {
                    IconoCheck(color = Coo.Texto, tamano = 16.dp)
                } else {
                    Text(
                        text = numero.toString(),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (actual) Coo.Texto else Coo.TextoTenue
                    )
                }
            }
            Text(
                text = nombre,
                fontSize = 13.sp,
                fontWeight = if (actual) FontWeight.Medium else FontWeight.Normal,
                color = if (actual) Coo.Texto else Coo.TextoTenue
            )
            if (indice < pasos.lastIndex) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(2.dp)
                        .background(if (hecho) Coo.AzulUbicacion else Coo.Borde, RoundedCornerShape(1.dp))
                )
            }
        }
    }
}
