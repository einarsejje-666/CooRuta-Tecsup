package pe.cooruta.app.ui.inicio

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Group
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.GroupAdd
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import pe.cooruta.app.data.InvitacionPendiente
import pe.cooruta.app.data.Resultado
import pe.cooruta.app.data.repositorio.GrupoRepositorio
import pe.cooruta.app.ui.componentes.IconoM
import pe.cooruta.app.ui.theme.Coo

/*
 * Grupo del conductor: invitar por WhatsApp con un enlace de un solo uso, ver y quitar integrantes,
 * y el diálogo que ve quien abre el enlace.
 */

private val NEGRO = Color(0xFF121212)
private val BORDE = Color(0xFF2A2A2A)

/** Conductor, en «Mis rutas»: su grupo y el botón para invitar. */
@Composable
fun TarjetaMiGrupo(alAvisar: (String) -> Unit) {
    val repo = remember { GrupoRepositorio() }
    val ctx = LocalContext.current
    val alcance = rememberCoroutineScope()
    val miembros by remember { repo.miembrosEnVivo() }.collectAsStateWithLifecycle(emptyList())
    var creando by remember { mutableStateOf(false) }
    var quitando by remember { mutableStateOf<GrupoRepositorio.Miembro?>(null) }
    // Mantiene al día los tramos que ve el grupo (rutas nuevas, editadas o vueltas privadas).
    LaunchedEffect(Unit) { repo.sincronizarMisRutas() }

    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(NEGRO)
            .border(1.dp, BORDE, RoundedCornerShape(20.dp)).padding(16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(44.dp).clip(RoundedCornerShape(14.dp)).background(Coo.AzulBoton.copy(alpha = 0.18f)),
                contentAlignment = Alignment.Center) { IconoM(Icons.Outlined.Group, Coo.AzulUbicacion, 24.dp) }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("Mi grupo", color = Coo.Texto, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                Text(if (miembros.isEmpty()) "Invita a tus compañeros de viaje de confianza."
                    else "${miembros.size} ${if (miembros.size == 1) "persona ve" else "personas ven"} tus rutas privadas.",
                    color = Coo.TextoTenue, fontSize = 13.sp)
            }
        }
        if (miembros.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            miembros.forEach { m ->
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Avatar(null, m.nombre.ifBlank { "?" }, 32.dp)
                    Spacer(Modifier.width(10.dp))
                    Text(nombrePropio(m.nombre.ifBlank { "Integrante" }), color = Coo.Texto, fontSize = 14.sp, modifier = Modifier.weight(1f))
                    Box(
                        Modifier.size(36.dp).clip(CircleShape).clickable { quitando = m },
                        contentAlignment = Alignment.Center
                    ) { IconoM(Icons.Rounded.Close, Coo.TextoTenue, 18.dp) }
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        Row(
            Modifier.fillMaxWidth().height(48.dp).clip(RoundedCornerShape(14.dp))
                .background(if (!creando) Brush.horizontalGradient(listOf(Color(0xFF7189FF), Coo.AzulBoton, Color(0xFF3F55D9)))
                    else SolidColor(Coo.CampoActivo))
                .clickable(enabled = !creando) {
                    creando = true
                    alcance.launch {
                        when (val r = repo.crearInvitacion()) {
                            is Resultado.Exito -> compartirInvitacion(ctx, r.valor)
                            is Resultado.Error -> alAvisar(r.mensaje)
                        }
                        creando = false
                    }
                },
            horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically
        ) {
            IconoM(Icons.Rounded.Share, Coo.SobreBoton, 20.dp)
            Spacer(Modifier.width(8.dp))
            Text(if (creando) "Creando enlace…" else "Invitar por WhatsApp", color = Coo.SobreBoton, fontSize = 15.sp, fontWeight = FontWeight.Bold)
        }
        Text("Cada enlace sirve una sola vez y vence en 7 días.", color = Coo.TextoTenue, fontSize = 12.sp,
            modifier = Modifier.padding(top = 8.dp))
    }

    quitando?.let { m ->
        AlertDialog(
            onDismissRequest = { quitando = null },
            containerColor = Coo.Campo,
            title = { Text("¿Quitar a ${nombrePropio(m.nombre.ifBlank { "esta persona" })}?", color = Coo.Texto, fontWeight = FontWeight.Bold) },
            text = { Text("Ya no verá tus rutas privadas. Sus reservas ya hechas no cambian.", color = Coo.TextoTenue) },
            confirmButton = {
                TextButton(onClick = {
                    quitando = null
                    alcance.launch { (repo.quitar(m.docId) as? Resultado.Error)?.let { alAvisar(it.mensaje) } }
                }) { Text("Quitar", color = Coo.RojoDestino, fontWeight = FontWeight.Bold) }
            },
            dismissButton = { TextButton(onClick = { quitando = null }) { Text("Cancelar", color = Coo.Texto) } }
        )
    }
}

/** Abre WhatsApp con el mensaje y el enlace; si no está instalado, el menú de compartir. */
private fun compartirInvitacion(ctx: android.content.Context, enlace: String) {
    val texto = "Te invito a mi grupo de CooRuta para compartir viajes. Ábrelo desde tu celular " +
        "(sirve una sola vez y vence en 7 días): $enlace"
    val base = Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_TEXT, texto) }
    val whatsapp = Intent(base).setPackage("com.whatsapp")
    runCatching { ctx.startActivity(whatsapp) }
        .onFailure { ctx.startActivity(Intent.createChooser(base, "Invitar a mi grupo")) }
}
