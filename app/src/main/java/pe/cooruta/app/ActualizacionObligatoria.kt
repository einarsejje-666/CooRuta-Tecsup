package pe.cooruta.app

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.Settings
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.rounded.Download
import androidx.compose.foundation.clickable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.withContext
import pe.cooruta.app.ui.theme.Coo
import java.io.File

/**
 * Versión publicada en Firestore `config/app`: { versionCode, urlApk, notas, bytes }.
 * `bytes` (tamaño del APK) da el porcentaje cuando el servidor no informa el tamaño.
 */
private data class VersionPublicada(val versionCode: Long, val urlApk: String, val notas: String, val bytes: Long = 0,
    /** Nombre visible de la versión ("1.3"); si falta, se muestra el número. */
    val versionName: String = "")

/**
 * Si hay una versión publicada más nueva que la instalada, bloquea toda la app con una pantalla
 * que descarga el APK e instala la actualización. Se revisa al abrir y cada vez que vuelve al frente.
 */
@Composable
fun ActualizacionObligatoria(contenido: @Composable () -> Unit) {
    var pendiente by remember { mutableStateOf<VersionPublicada?>(null) }
    val ciclo = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(Unit) {
        ciclo.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            // Sin red usa la copia en caché; si falla del todo, no bloquea.
            val v = runCatching {
                val d = suspendCancellableCoroutine { k ->
                    FirebaseFirestore.getInstance().collection("config").document("app").get()
                        .addOnSuccessListener { if (k.isActive) k.resume(it) }
                        .addOnFailureListener { if (k.isActive) k.resumeWithException(it) }
                }
                VersionPublicada(d.getLong("versionCode") ?: 0L, d.getString("urlApk").orEmpty(), d.getString("notas").orEmpty(),
                    d.getLong("bytes") ?: 0L, d.getString("versionName").orEmpty())
            }.getOrNull()
            if (v != null) pendiente = v.takeIf { it.versionCode > BuildConfig.VERSION_CODE && it.urlApk.isNotBlank() }
        }
    }
    val v = pendiente
    if (v == null) contenido() else {
        // Fondo neutro (la app queda bloqueada) con el aviso como recuadro emergente encima.
        Box(Modifier.fillMaxSize().background(Coo.Fondo), contentAlignment = Alignment.Center) {
            Image(painterResource(R.drawable.logoblanco), contentDescription = null, modifier = Modifier.size(120.dp).alpha(0.12f))
        }
        PantallaActualizar(v)
    }
}

@Composable
private fun PantallaActualizar(v: VersionPublicada) {
    val ctx = LocalContext.current
    val d by ServicioDescarga.estado.collectAsStateWithLifecycle()
    // Ya descargado (también si se descargó con la app cerrada).
    var listo by remember { mutableStateOf(ServicioDescarga.listo(ctx, v.versionCode, v.bytes)) }
    var error by remember { mutableStateOf<String?>(null) }
    val enCurso = d.version == v.versionCode && d.fase in setOf(ServicioDescarga.Fase.PREPARANDO, ServicioDescarga.Fase.DESCARGANDO)

    // Al terminar la descarga (con la ventana a la vista) se abre el instalador.
    LaunchedEffect(d.fase, d.version) {
        if (d.version != v.versionCode) return@LaunchedEffect
        when (d.fase) {
            ServicioDescarga.Fase.LISTO -> {
                listo = true
                if (!ServicioDescarga.instalar(ctx, v.versionCode)) error = "No se encontró el archivo descargado. Intenta de nuevo."
            }
            ServicioDescarga.Fase.ERROR -> error = "No se pudo descargar. Revisa tu conexión e intenta de nuevo."
            else -> Unit
        }
    }

    fun actualizar() {
        error = null
        // Android exige permitir "Instalar apps desconocidas" para CooRuta (una sola vez).
        if (!ctx.packageManager.canRequestPackageInstalls()) {
            ctx.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${ctx.packageName}")))
            return
        }
        if (listo) {
            if (!ServicioDescarga.instalar(ctx, v.versionCode)) { listo = false; error = "No se encontró el archivo descargado. Intenta de nuevo." }
            return
        }
        runCatching { ServicioDescarga.iniciar(ctx, v.urlApk, v.versionCode, v.bytes) }
            .onFailure { error = "No se pudo iniciar la descarga. Intenta de nuevo." }
    }

    /** Plan B: el navegador descarga el APK con su propio aviso. */
    fun conNavegador() = runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(v.urlApk)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }

    val totalMb = v.bytes / (1024 * 1024)
    // Novedades en viñetas: una por línea (o por «•»).
    val novedades = v.notas.split("\n", "•").map { it.trim().trimStart('-', '*').trim() }.filter { it.isNotBlank() }
    // No se puede cerrar ni con atrás ni tocando afuera; al volver a abrir la app vuelve a aparecer.
    Dialog(onDismissRequest = {}, properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false)) {
        Surface(shape = RoundedCornerShape(28.dp), color = Coo.Campo, border = BorderStroke(1.dp, Coo.Borde)) {
            Column(Modifier.padding(22.dp)) {
                // Encabezado: ícono, título y subtítulo.
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier.size(56.dp).clip(RoundedCornerShape(16.dp)).background(Coo.Fondo),
                        contentAlignment = Alignment.Center
                    ) { Icon(Icons.Rounded.SystemUpdate, contentDescription = null, tint = Coo.AzulBoton, modifier = Modifier.size(30.dp)) }
                    Spacer(Modifier.width(14.dp))
                    Column {
                        Text("Actualización disponible", color = Coo.Texto, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                        Text("Una nueva versión está lista", color = Coo.TextoTenue, fontSize = 14.sp)
                    }
                }
                Spacer(Modifier.height(18.dp))
                // Versión actual → nueva.
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Coo.Fondo).padding(vertical = 14.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("Actual", color = Coo.TextoTenue, fontSize = 12.sp)
                        Text("v${BuildConfig.VERSION_NAME}", color = Coo.TextoTenue, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                    }
                    pe.cooruta.app.ui.componentes.IconoAdelante(color = Coo.AzulBoton, tamano = 22.dp)
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("Nueva", color = Coo.TextoTenue, fontSize = 12.sp)
                        Text("v${v.versionName.ifBlank { v.versionCode.toString() }}", color = Coo.AzulBoton, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    }
                }
                if (novedades.isNotEmpty() && !enCurso) {
                    Spacer(Modifier.height(18.dp))
                    Text("Novedades", color = Coo.Texto, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(8.dp))
                    Column(
                        Modifier.fillMaxWidth().heightIn(max = 200.dp).clip(RoundedCornerShape(18.dp)).background(Coo.Fondo)
                            .verticalScroll(rememberScrollState()).padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        novedades.forEach { n -> Text("•  $n", color = Coo.TextoTenue, fontSize = 13.sp, lineHeight = 18.sp) }
                    }
                }
                Spacer(Modifier.height(20.dp))
                if (enCurso) {
                    // Tarjeta de descarga: estado, barra, MB y porcentaje.
                    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Coo.Fondo).padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(modifier = Modifier.size(22.dp), color = Coo.AzulBoton, strokeWidth = 2.5.dp)
                            Spacer(Modifier.width(12.dp))
                            Text(if (d.fase == ServicioDescarga.Fase.PREPARANDO) "Preparando la descarga…" else "Descargando…",
                                color = Coo.Texto, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                        }
                        Spacer(Modifier.height(14.dp))
                        if (d.porcentaje < 0) LinearProgressIndicator(modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
                            color = Coo.AzulBoton, trackColor = Coo.Campo)
                        else LinearProgressIndicator(progress = { d.porcentaje / 100f }, modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
                            color = Coo.AzulBoton, trackColor = Coo.Campo, drawStopIndicator = {})
                        Spacer(Modifier.height(10.dp))
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            val mb = { b: Long -> String.format(java.util.Locale.US, "%.1f", b / 1048576.0) }
                            Text(if (d.total > 0) "${mb(d.bajados)} / ${mb(d.total)} MB" else "${mb(d.bajados)} MB",
                                color = Coo.TextoTenue, fontSize = 14.sp, modifier = Modifier.weight(1f))
                            if (d.porcentaje >= 0) Text("${d.porcentaje}%", color = Coo.AzulBoton, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Text("Puedes salir de la app: la descarga sigue en las notificaciones.",
                        color = Coo.TextoTenue, fontSize = 12.sp, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
                    Spacer(Modifier.height(16.dp))
                    // Cancelar detiene la descarga; la ventana sigue (la actualización es obligatoria).
                    Button(
                        onClick = { ServicioDescarga.cancelar(ctx) },
                        modifier = Modifier.fillMaxWidth().height(56.dp),
                        shape = RoundedCornerShape(28.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Coo.Fondo, contentColor = Coo.Texto)
                    ) { Text("Cancelar", fontSize = 16.sp, fontWeight = FontWeight.SemiBold) }
                } else {
                    Button(
                        onClick = { actualizar() },
                        modifier = Modifier.fillMaxWidth().height(56.dp),
                        shape = RoundedCornerShape(28.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Coo.AzulBoton, contentColor = Coo.SobreBoton)
                    ) {
                        Icon(Icons.Rounded.Download, contentDescription = null, modifier = Modifier.size(22.dp))
                        Spacer(Modifier.width(10.dp))
                        Text(if (listo) "Instalar actualización" else "Descargar e instalar", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    }
                    if (totalMb > 0 && !listo) {
                        Spacer(Modifier.height(10.dp))
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Rounded.Wifi, contentDescription = null, tint = Coo.TextoTenue, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Pesa $totalMb MB · te recomendamos usar wifi", color = Coo.TextoTenue, fontSize = 12.sp)
                        }
                    }
                }
                error?.let {
                    Spacer(Modifier.height(12.dp))
                    Text(it, color = Coo.RojoError, fontSize = 13.sp, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
                    Text("Descargar desde el navegador", color = Coo.AzulBoton, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { conNavegador() }.padding(10.dp))
                }
            }
        }
    }
}
