package pe.cooruta.app.ui.inicio

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.util.Base64
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import pe.cooruta.app.data.Resultado
import pe.cooruta.app.data.repositorio.PerfilPublico
import pe.cooruta.app.data.repositorio.PerfilesRepositorio
import pe.cooruta.app.ui.theme.Coo
import java.io.ByteArrayOutputStream

data class EstadoMiPerfil(
    val perfil: PerfilPublico? = null,
    val telefono: String = "",
    val guardando: Boolean = false
)

/** Datos editables de la cuenta: foto, acerca de mí (públicos) y celular (privado). */
class MiPerfilViewModel(private val repo: PerfilesRepositorio) : ViewModel() {
    private val _estado = MutableStateFlow(EstadoMiPerfil())
    val estado: StateFlow<EstadoMiPerfil> = _estado.asStateFlow()

    fun cargar() {
        val uid = repo.miUid ?: return
        viewModelScope.launch {
            (repo.leerPerfil(uid) as? Resultado.Exito)?.let { r -> _estado.update { it.copy(perfil = r.valor) } }
            (repo.leerTelefono() as? Resultado.Exito)?.let { r -> _estado.update { it.copy(telefono = r.valor) } }
        }
    }

    private fun guardar(alAvisar: (String) -> Unit, ok: String, bloque: suspend () -> Resultado<Unit>) {
        if (_estado.value.guardando) return
        _estado.update { it.copy(guardando = true) }
        viewModelScope.launch {
            val r = bloque()
            _estado.update { it.copy(guardando = false) }
            alAvisar(if (r is Resultado.Error) r.mensaje else ok)
            if (r is Resultado.Exito) cargar()
        }
    }

    fun guardarAcercaDe(texto: String, alAvisar: (String) -> Unit) =
        guardar(alAvisar, "Guardado.") { repo.guardarPropios(mapOf("acercaDe" to texto.trim().take(200)), "guardar tu descripción") }

    val miDominio: String? get() = repo.miDominioCorporativo

    /** Guarda la empresa; con [verificar] y correo corporativo, queda verificada por dominio. */
    fun guardarEmpresa(nombre: String, verificar: Boolean, alAvisar: (String) -> Unit) {
        val dominio = if (verificar) repo.miDominioCorporativo.orEmpty() else ""
        val limpio = nombre.trim().take(60)
        val clave = pe.cooruta.app.data.Comunidad.clave(limpio.ifBlank { dominio })
        guardar(alAvisar, when {
            limpio.isBlank() && dominio.isBlank() -> "Quitaste tu empresa."
            dominio.isNotBlank() -> "Empresa verificada con tu correo @$dominio."
            else -> "Empresa guardada como declarada."
        }) {
            repo.guardarPropios(mapOf("empresa" to limpio.ifBlank { dominio }, "empresaClave" to clave,
                "empresaDominio" to dominio), "guardar tu empresa")
        }
    }

    /** Copia el nombre al perfil público (lo que ven los demás). */
    fun guardarNombre(nombre: String) = viewModelScope.launch {
        repo.guardarPropios(mapOf("nombre" to nombre.trim().take(60)), "guardar tu nombre")
    }

    fun guardarPreferencias(lista: List<String>, alAvisar: (String) -> Unit) =
        guardar(alAvisar, "Preferencias guardadas.") { repo.guardarPropios(mapOf("preferencias" to lista.take(8)), "guardar tus preferencias") }

    fun guardarTelefono(t: String, alAvisar: (String) -> Unit) =
        guardar(alAvisar, if (t.isBlank()) "Celular borrado." else "Celular guardado. Solo tú lo ves; puedes enviarlo por el chat.") { repo.guardarTelefono(t) }

    /** Reduce la imagen elegida a 256 px y la guarda como JPEG en base64. */
    fun cambiarFoto(ctx: Context, uri: Uri, alAvisar: (String) -> Unit) = guardar(alAvisar, "Foto actualizada.") {
        val base64 = withContext(Dispatchers.Default) { runCatching { comprimir(ctx, uri) }.getOrNull() }
            ?: return@guardar Resultado.Error("No se pudo leer esa imagen.")
        repo.guardarPropios(mapOf("foto" to base64), "guardar tu foto")
    }

    /** Una de las imágenes de perfil incluidas en la app (se guarda como "avatar:N"). */
    fun elegirAvatar(n: Int, alAvisar: (String) -> Unit) =
        guardar(alAvisar, "Foto actualizada.") { repo.guardarPropios(mapOf("foto" to "avatar:$n"), "guardar tu foto") }

    fun quitarFoto(alAvisar: (String) -> Unit) =
        guardar(alAvisar, "Foto quitada.") { repo.guardarPropios(mapOf("foto" to ""), "quitar tu foto") }

    companion object {
        val Fabrica: ViewModelProvider.Factory = viewModelFactory { initializer { MiPerfilViewModel(PerfilesRepositorio()) } }

        fun comprimir(ctx: Context, uri: Uri): String {
            val fuente = ImageDecoder.createSource(ctx.contentResolver, uri)
            val bmp = ImageDecoder.decodeBitmap(fuente) { d, info, _ ->
                val lado = minOf(info.size.width, info.size.height).coerceAtLeast(1)
                val escala = 256f / lado
                if (escala < 1f) d.setTargetSize((info.size.width * escala).toInt().coerceAtLeast(1), (info.size.height * escala).toInt().coerceAtLeast(1))
                d.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
            // Recorte cuadrado al centro.
            val lado = minOf(bmp.width, bmp.height)
            val cuadrado = Bitmap.createBitmap(bmp, (bmp.width - lado) / 2, (bmp.height - lado) / 2, lado, lado)
            val salida = ByteArrayOutputStream()
            var calidad = 80
            do {
                salida.reset()
                cuadrado.compress(Bitmap.CompressFormat.JPEG, calidad, salida)
                calidad -= 15
            } while (salida.size() > 80_000 && calidad > 20)
            return Base64.encodeToString(salida.toByteArray(), Base64.NO_WRAP)
        }
    }
}

/** Imágenes de perfil incluidas en la app (foto = "avatar:1" … "avatar:6"). */
val AVATARES = listOf(pe.cooruta.app.R.drawable.avatar_1, pe.cooruta.app.R.drawable.avatar_2, pe.cooruta.app.R.drawable.avatar_3,
    pe.cooruta.app.R.drawable.avatar_4, pe.cooruta.app.R.drawable.avatar_5, pe.cooruta.app.R.drawable.avatar_6)

/** Foto de perfil redonda o, si no hay, la inicial. */
@Composable
fun Avatar(foto: String?, nombre: String, tamano: Dp, letra: TextUnit = 16.sp, modifier: Modifier = Modifier) {
    val incluida = foto?.removePrefix("avatar:")?.takeIf { foto.startsWith("avatar:") }?.toIntOrNull()?.let { AVATARES.getOrNull(it - 1) }
    if (incluida != null) {
        Image(androidx.compose.ui.res.painterResource(incluida), contentDescription = "Foto de $nombre",
            contentScale = ContentScale.Crop, modifier = modifier.size(tamano).clip(CircleShape))
        return
    }
    val imagen: ImageBitmap? = remember(foto) {
        foto?.takeIf { it.isNotBlank() && !it.startsWith("avatar:") }?.let { f ->
            runCatching { Base64.decode(f, Base64.DEFAULT).let { BitmapFactory.decodeByteArray(it, 0, it.size) }?.asImageBitmap() }.getOrNull()
        }
    }
    // Sin foto: inicial blanca sobre círculo azul (con foto, la imagen lo cubre).
    Box(modifier.size(tamano).clip(CircleShape).background(Coo.AzulBoton), contentAlignment = Alignment.Center) {
        if (imagen != null) Image(imagen, contentDescription = "Foto de $nombre", contentScale = ContentScale.Crop, modifier = Modifier.size(tamano))
        else Text(nombre.trim().firstOrNull()?.uppercase() ?: "?", color = Coo.SobreBoton, fontSize = letra, fontWeight = FontWeight.Bold)
    }
}

/** Diálogo de un solo campo de texto (acerca de mí, celular). */
@Composable
fun DialogoTexto(
    titulo: String, inicial: String, etiqueta: String, ayuda: String, maximo: Int,
    numerico: Boolean = false, guardando: Boolean, alGuardar: (String) -> Unit, alCerrar: () -> Unit
) {
    var valor by rememberSaveable(inicial) { mutableStateOf(inicial) }
    AlertDialog(
        onDismissRequest = { if (!guardando) alCerrar() },
        containerColor = Coo.Campo,
        title = { Text(titulo, fontWeight = FontWeight.Bold) },
        text = {
            Column {
                OutlinedTextField(value = valor, onValueChange = { valor = it.take(maximo) }, label = { Text(etiqueta) },
                    modifier = Modifier.fillMaxWidth(), singleLine = numerico, minLines = if (numerico) 1 else 3,
                    keyboardOptions = if (numerico) KeyboardOptions(keyboardType = KeyboardType.Phone) else KeyboardOptions.Default)
                Text("$ayuda (${valor.length}/$maximo)", color = Coo.TextoTenue, fontSize = 12.sp)
            }
        },
        confirmButton = { TextButton(onClick = { alGuardar(valor); alCerrar() }, enabled = !guardando) { Text("Guardar") } },
        dismissButton = { TextButton(onClick = alCerrar, enabled = !guardando) { Text("Cancelar") } }
    )
}

/** Casillas de preferencias de viaje. */
@Composable
fun DialogoPreferencias(actuales: List<String>, guardando: Boolean, alGuardar: (List<String>) -> Unit, alCerrar: () -> Unit) {
    var elegidas by rememberSaveable(actuales) { mutableStateOf(actuales.toSet().toList()) }
    AlertDialog(
        onDismissRequest = { if (!guardando) alCerrar() },
        containerColor = Coo.Campo,
        title = { Text("Preferencias de viaje", fontWeight = FontWeight.Bold) },
        text = {
            Column {
                pe.cooruta.app.data.repositorio.PREFERENCIAS_VIAJE.forEach { p ->
                    androidx.compose.foundation.layout.Row(
                        Modifier.fillMaxWidth().clickable { elegidas = if (p in elegidas) elegidas - p else elegidas + p },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        androidx.compose.material3.Checkbox(checked = p in elegidas,
                            onCheckedChange = { elegidas = if (it) elegidas + p else elegidas - p })
                        Text(p, color = Coo.Texto, fontSize = 14.sp)
                    }
                }
                Text("Las ven los pasajeros o conductores antes de viajar contigo.", color = Coo.TextoTenue, fontSize = 12.sp)
            }
        },
        confirmButton = { TextButton(onClick = { alGuardar(elegidas); alCerrar() }, enabled = !guardando) { Text("Guardar") } },
        dismissButton = { TextButton(onClick = alCerrar, enabled = !guardando) { Text("Cancelar") } }
    )
}

/** Tarjeta "Mi empresa o universidad" del perfil. */
@Composable
fun TarjetaEmpresa(perfil: PerfilPublico?, miDominio: String?, guardando: Boolean, alGuardar: (String, Boolean) -> Unit) {
    var abierto by rememberSaveable { mutableStateOf(false) }
    val empresa = perfil?.empresa.orEmpty()
    // Fila compacta (va dentro del bloque «Comunidad»); el detalle se edita en el diálogo.
    val verificada = perfil?.empresaDominio?.isNotBlank() == true
    androidx.compose.foundation.layout.Row(
        Modifier.fillMaxWidth().clickable(enabled = !guardando) { abierto = true }.padding(vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("🏢", fontSize = 18.sp)
        androidx.compose.foundation.layout.Spacer(Modifier.size(width = 18.dp, height = 1.dp))
        Column(Modifier.weight(1f)) {
            Text("Empresa o universidad", color = Coo.Texto, fontSize = 16.sp)
            if (empresa.isBlank()) Text("Agrégala para ver primero a tus compañeros", color = Coo.TextoTenue, fontSize = 13.sp)
            else Text("$empresa · " + if (verificada) "verificada ✓" else "no verificada",
                color = if (verificada) Coo.Exito else Coo.TextoTenue, fontSize = 13.sp)
        }
        pe.cooruta.app.ui.componentes.IconoAdelante(color = Coo.TextoTenue, tamano = 20.dp)
    }
    if (abierto) {
        var nombre by rememberSaveable { mutableStateOf(empresa) }
        var verificar by rememberSaveable { mutableStateOf(miDominio != null) }
        AlertDialog(
            onDismissRequest = { abierto = false },
            containerColor = Coo.Campo,
            title = { Text("Mi empresa o universidad", fontWeight = FontWeight.Bold) },
            text = {
                Column {
                    OutlinedTextField(value = nombre, onValueChange = { nombre = it.take(60) }, singleLine = true,
                        label = { Text("Nombre (ej. UNSA, Cerro Verde)") }, modifier = Modifier.fillMaxWidth())
                    if (miDominio != null) {
                        androidx.compose.foundation.layout.Row(verticalAlignment = Alignment.CenterVertically) {
                            androidx.compose.material3.Checkbox(checked = verificar, onCheckedChange = { verificar = it })
                            Text("Verificar con mi correo @$miDominio", color = Coo.Texto, fontSize = 14.sp)
                        }
                    } else {
                        Text("Para verificarla, entra con el correo de tu empresa o universidad. Con Gmail queda como «declarada».",
                            color = Coo.TextoTenue, fontSize = 12.sp)
                    }
                }
            },
            confirmButton = { TextButton(onClick = { alGuardar(nombre, verificar); abierto = false }) { Text("Guardar") } },
            dismissButton = {
                if (empresa.isNotBlank()) TextButton(onClick = { alGuardar("", false); abierto = false }) { Text("Quitar", color = Coo.RojoError) }
                else TextButton(onClick = { abierto = false }) { Text("Cancelar") }
            }
        )
    }
}


/** Elegir foto: una de las incluidas, o subir una desde la galería. */
@Composable
fun DialogoElegirFoto(alElegir: (Int) -> Unit, alGaleria: () -> Unit, alQuitar: () -> Unit, alCerrar: () -> Unit) {
    AlertDialog(
        onDismissRequest = alCerrar,
        containerColor = Coo.Campo,
        title = { Text("Foto de perfil", fontWeight = FontWeight.Bold) },
        text = {
            androidx.compose.foundation.layout.Column(verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(12.dp)) {
                AVATARES.indices.chunked(3).forEach { fila ->
                    androidx.compose.foundation.layout.Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.SpaceEvenly
                    ) {
                        fila.forEach { i ->
                            Image(androidx.compose.ui.res.painterResource(AVATARES[i]), contentDescription = "Imagen ${i + 1}",
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.size(72.dp).clip(CircleShape).clickable { alElegir(i + 1) })
                        }
                    }
                }
                TextButton(onClick = alGaleria, modifier = Modifier.fillMaxWidth()) { Text("Subir desde la galería") }
                TextButton(onClick = alQuitar, modifier = Modifier.fillMaxWidth()) { Text("Quitar foto", color = Coo.TextoTenue) }
            }
        },
        confirmButton = { TextButton(onClick = alCerrar) { Text("Cerrar") } }
    )
}
