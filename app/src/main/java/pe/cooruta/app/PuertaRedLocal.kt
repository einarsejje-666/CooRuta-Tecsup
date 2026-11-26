package pe.cooruta.app

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import pe.cooruta.app.ui.theme.Coo

/** Android 17 (API 37): el acceso a la red local (emuladores en el equipo) requiere este permiso. */
private const val PERMISO_RED_LOCAL = "android.permission.ACCESS_LOCAL_NETWORK"

/** Solo debug con emuladores y Android 17+; en cualquier otro caso no hace falta pedir nada. */
private fun requierePermiso(contexto: Context) =
    BuildConfig.EMULADOR && Build.VERSION.SDK_INT >= 37 &&
        ContextCompat.checkSelfPermission(contexto, PERMISO_RED_LOCAL) != PackageManager.PERMISSION_GRANTED

/**
 * Antes de cualquier operación con Auth o Firestore: en modo emulador sobre Android 17+ pide el
 * permiso de red local. Si se deniega, explica por qué y permite reintentar. En modo normal,
 * release o Android anteriores muestra [contenido] directamente.
 */
@Composable
fun PuertaRedLocal(contenido: @Composable () -> Unit) {
    val contexto = LocalContext.current
    var falta by remember { mutableStateOf(requierePermiso(contexto)) }
    if (!falta) {
        contenido()
        return
    }
    var denegado by remember { mutableStateOf(false) }
    val lanzador = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { concedido ->
        falta = !concedido
        denegado = !concedido
    }
    LaunchedEffect(Unit) { lanzador.launch(PERMISO_RED_LOCAL) }
    // Si se activa desde Ajustes y se vuelve a la app, se continúa sin pedirlo otra vez.
    LifecycleResumeEffect(Unit) {
        if (!requierePermiso(contexto)) falta = false
        onPauseOrDispose { }
    }
    if (denegado) {
        Column(
            Modifier.fillMaxSize().background(Coo.Fondo).padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("Permiso de red local", color = Coo.Texto, fontSize = 20.sp)
            Spacer(Modifier.height(12.dp))
            Text(
                "En modo de prueba, CooRuta se conecta a los emuladores de Firebase de tu computadora " +
                    "(red local). Sin este permiso no puede iniciar sesión ni cargar datos. " +
                    "Si el diálogo no aparece, actívalo en Ajustes › Apps › CooRuta › Permisos › Dispositivos cercanos.",
                color = Coo.TextoTenue, fontSize = 14.sp, textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(20.dp))
            Button(
                onClick = { if (requierePermiso(contexto)) lanzador.launch(PERMISO_RED_LOCAL) else falta = false },
                colors = ButtonDefaults.buttonColors(containerColor = Coo.AzulBoton)
            ) { Text("Reintentar", color = Coo.SobreBoton) }
        }
    }
}
