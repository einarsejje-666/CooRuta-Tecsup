package pe.cooruta.app.ui.inicio

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DirectionsCar
import androidx.compose.material.icons.outlined.LocalOffer
import androidx.compose.material.icons.outlined.DirectionsCar
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Badge
import androidx.compose.material.icons.outlined.CalendarToday
import androidx.compose.material.icons.outlined.Group
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.outlined.PhotoCamera
import pe.cooruta.app.ui.componentes.IconoM
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.foundation.background
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.size
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.foundation.text.KeyboardOptions
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
import pe.cooruta.app.data.repositorio.PerfilPublico
import pe.cooruta.app.data.repositorio.PerfilesRepositorio
import pe.cooruta.app.data.repositorio.Vehiculo
import pe.cooruta.app.ui.theme.Coo

data class EstadoVehiculo(
    val cargando: Boolean = true,
    val perfil: PerfilPublico? = null,
    val guardando: Boolean = false,
    val error: String? = null
)

class VehiculoViewModel(private val repo: PerfilesRepositorio) : ViewModel() {
    private val _estado = MutableStateFlow(EstadoVehiculo())
    val estado: StateFlow<EstadoVehiculo> = _estado.asStateFlow()

    fun cargar() {
        val uid = repo.miUid ?: return _estado.update { it.copy(cargando = false, error = "Inicia sesión con Google.") }
        _estado.update { it.copy(cargando = true, error = null) }
        viewModelScope.launch {
            when (val r = repo.leerPerfil(uid)) {
                is Resultado.Exito -> _estado.update { it.copy(cargando = false, perfil = r.valor) }
                is Resultado.Error -> _estado.update { it.copy(cargando = false, error = r.mensaje) }
            }
        }
    }

    fun guardar(nombre: String, v: Vehiculo, alAvisar: (String) -> Unit, alListo: () -> Unit) {
        if (_estado.value.guardando) return
        _estado.update { it.copy(guardando = true) }
        viewModelScope.launch {
            val r = repo.guardarVehiculo(nombre, v)
            _estado.update { it.copy(guardando = false) }
            when (r) {
                is Resultado.Exito -> { alAvisar("Vehículo guardado. Los pasajeros lo verán en tus rutas."); alListo() }
                is Resultado.Error -> alAvisar(r.mensaje)
            }
        }
    }

    companion object {
        val Fabrica: ViewModelProvider.Factory = viewModelFactory { initializer { VehiculoViewModel(PerfilesRepositorio()) } }
    }
}

/** Hoja "Mi vehículo": marca, modelo, color y placa. Lo ven los pasajeros en Buscar. */
@Composable
fun DialogoVehiculo(
    nombre: String,
    alAvisar: (String) -> Unit,
    alCerrar: () -> Unit,
    vm: VehiculoViewModel = viewModel(factory = VehiculoViewModel.Fabrica)
) {
    val e by vm.estado.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { vm.cargar() }
    val actual = e.perfil?.vehiculo ?: Vehiculo()
    // Las claves dependen de lo cargado: al llegar el perfil, los campos toman sus valores.
    var marca by rememberSaveable(actual) { mutableStateOf(actual.marca) }
    var modelo by rememberSaveable(actual) { mutableStateOf(actual.modelo) }
    var color by rememberSaveable(actual) { mutableStateOf(actual.color) }
    var placa by rememberSaveable(actual) { mutableStateOf(actual.placa) }
    var anio by rememberSaveable(actual) { mutableStateOf(actual.anio) }
    var capacidad by rememberSaveable(actual) { mutableStateOf(actual.capacidad.takeIf { it > 0 } ?: 4) }
    var foto by rememberSaveable(actual) { mutableStateOf(actual.foto) }
    var elegirAuto by rememberSaveable { mutableStateOf(false) }
    var procesando by rememberSaveable { mutableStateOf(false) }
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val alcance = androidx.compose.runtime.rememberCoroutineScope()
    val elegirFoto = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            procesando = true
            alcance.launch {
                val r = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                    runCatching { MiPerfilViewModel.comprimir(ctx, uri) }.getOrNull()
                }
                procesando = false
                if (r != null) foto = r else alAvisar("No se pudo leer esa imagen.")
            }
        }
    }
    val anioValido = anio.isEmpty() || (anio.length == 4 && anio.toIntOrNull() in 1980..2030)
    val completo = marca.isNotBlank() && modelo.isNotBlank() && color.isNotBlank() && placa.length >= 6 && anioValido
    if (elegirAuto) DialogoElegirAuto(
        alElegir = { n -> foto = "auto:$n"; elegirAuto = false },
        alGaleria = {
            elegirAuto = false
            elegirFoto.launch(androidx.activity.result.PickVisualMediaRequest(
                androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia.ImageOnly))
        },
        alQuitar = { foto = ""; elegirAuto = false },
        alCerrar = { elegirAuto = false }
    )
    val puedeGuardar = completo && !e.guardando && !e.cargando && !procesando
    AlertDialog(
        onDismissRequest = { if (!e.guardando) alCerrar() },
        containerColor = Color(0xFF121212),
        title = {
            androidx.compose.foundation.layout.Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                androidx.compose.foundation.layout.Box(
                    Modifier.size(48.dp).clip(androidx.compose.foundation.shape.RoundedCornerShape(14.dp))
                        .background(Coo.AzulBoton.copy(alpha = 0.22f)),
                    contentAlignment = androidx.compose.ui.Alignment.Center
                ) { IconoM(Icons.Rounded.DirectionsCar, Coo.AzulUbicacion, 26.dp) }
                androidx.compose.foundation.layout.Spacer(Modifier.width(12.dp))
                Column {
                    Text("Mi vehículo", color = Coo.Texto, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                    Text("Actualiza la información de tu auto", color = Coo.TextoTenue, fontSize = 13.sp)
                }
            }
        },
        text = {
            if (e.cargando) CircularProgressIndicator(color = Coo.AzulUbicacion)
            else Column(
                Modifier.verticalScroll(androidx.compose.foundation.rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                e.error?.let { Text(it, color = Coo.RojoError, fontSize = 13.sp) }
                CampoIcono(Icons.Outlined.LocalOffer, "Marca", marca) { marca = it.take(30) }
                CampoIcono(Icons.Outlined.DirectionsCar, "Modelo", modelo) { modelo = it.take(30) }
                CampoIcono(Icons.Outlined.Palette, "Color", color) { color = it.take(20) }
                CampoIcono(Icons.Outlined.Badge, "Placa", placa,
                    teclado = KeyboardOptions(capitalization = KeyboardCapitalization.Characters)) { placa = it.uppercase().take(10) }
                CampoIcono(Icons.Outlined.CalendarToday, "Año (opcional)", anio, error = !anioValido,
                    teclado = KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number)) { anio = it.filter(Char::isDigit).take(4) }
                // Capacidad: botones redondos − / +
                androidx.compose.foundation.layout.Row(Modifier.padding(top = 4.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    IconoM(Icons.Outlined.Group, Coo.AzulUbicacion, 24.dp)
                    androidx.compose.foundation.layout.Spacer(Modifier.width(14.dp))
                    Text("Capacidad de pasajeros", color = Coo.Texto, fontSize = 14.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    BotonRedondo(Icons.Rounded.Remove, "Menos", capacidad > 1) { capacidad = (capacidad - 1).coerceAtLeast(1) }
                    Text("$capacidad", color = Coo.Texto, fontSize = 20.sp, fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 16.dp))
                    BotonRedondo(Icons.Rounded.Add, "Más", capacidad < 8) { capacidad = (capacidad + 1).coerceAtMost(8) }
                }
                // Foto del auto
                androidx.compose.foundation.layout.Row(Modifier.padding(top = 4.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    FotoVehiculo(foto, 96.dp)
                    androidx.compose.foundation.layout.Spacer(Modifier.width(14.dp))
                    Column {
                        androidx.compose.foundation.layout.Row(
                            Modifier.clip(androidx.compose.foundation.shape.RoundedCornerShape(10.dp))
                                .clickable(enabled = !procesando) { elegirAuto = true }.padding(vertical = 6.dp),
                            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
                        ) {
                            IconoM(Icons.Outlined.PhotoCamera, Coo.AzulUbicacion, 22.dp)
                            androidx.compose.foundation.layout.Spacer(Modifier.width(8.dp))
                            Text(if (procesando) "Procesando…" else if (foto.isBlank()) "Agregar foto" else "Cambiar foto",
                                color = Coo.AzulUbicacion, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                        }
                        Text("La placa y la foto ayudan a los pasajeros a reconocer tu auto al subir.",
                            color = Coo.TextoTenue, fontSize = 12.sp, lineHeight = 16.sp)
                    }
                }
            }
        },
        confirmButton = {
            androidx.compose.foundation.layout.Box(
                Modifier.height(48.dp).clip(androidx.compose.foundation.shape.RoundedCornerShape(14.dp))
                    .background(if (puedeGuardar) Coo.AzulBoton else Coo.CampoActivo)
                    .clickable(enabled = puedeGuardar) {
                        vm.guardar(nombre, Vehiculo(marca, modelo, color, placa, anio, capacidad, foto), alAvisar, alCerrar)
                    }.padding(horizontal = 36.dp),
                contentAlignment = androidx.compose.ui.Alignment.Center
            ) {
                Text(if (e.guardando) "Guardando…" else "Guardar", color = if (puedeGuardar) Coo.SobreBoton else Coo.TextoTenue,
                    fontSize = 16.sp, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = { TextButton(onClick = alCerrar, enabled = !e.guardando) { Text("Cancelar", color = Coo.TextoTenue, fontWeight = FontWeight.Bold) } }
    )
}

/** Campo con ícono a la izquierda, etiqueta pequeña arriba y una línea debajo (sin recuadro). */
@Composable
private fun CampoIcono(
    icono: androidx.compose.ui.graphics.vector.ImageVector, etiqueta: String, valor: String,
    error: Boolean = false, teclado: KeyboardOptions = KeyboardOptions.Default, alCambiar: (String) -> Unit
) {
    androidx.compose.foundation.layout.Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        IconoM(icono, Coo.AzulUbicacion, 24.dp)
        androidx.compose.foundation.layout.Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(etiqueta, color = if (error) Coo.RojoError else Coo.TextoTenue, fontSize = 12.sp)
            androidx.compose.foundation.text.BasicTextField(
                value = valor, onValueChange = alCambiar, singleLine = true, keyboardOptions = teclado,
                textStyle = androidx.compose.ui.text.TextStyle(color = Coo.Texto, fontSize = 17.sp),
                cursorBrush = androidx.compose.ui.graphics.SolidColor(Coo.AzulUbicacion),
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
            )
            androidx.compose.foundation.layout.Box(Modifier.fillMaxWidth().height(1.dp)
                .background(if (error) Coo.RojoError else Coo.Borde))
        }
    }
}

/** Botón redondo azul con ícono (para − / +). */
@Composable
private fun BotonRedondo(icono: androidx.compose.ui.graphics.vector.ImageVector, descripcion: String, habilitado: Boolean, alPulsar: () -> Unit) {
    androidx.compose.foundation.layout.Box(
        Modifier.size(36.dp).clip(androidx.compose.foundation.shape.CircleShape)
            .background(if (habilitado) Coo.AzulBoton else Coo.CampoActivo)
            .clickable(enabled = habilitado, onClickLabel = descripcion, onClick = alPulsar),
        contentAlignment = androidx.compose.ui.Alignment.Center
    ) { IconoM(icono, if (habilitado) Coo.SobreBoton else Coo.TextoTenue, 20.dp) }
}

@Composable
private fun Campo(etiqueta: String, valor: String, alCambiar: (String) -> Unit) {
    OutlinedTextField(value = valor, onValueChange = alCambiar, label = { Text(etiqueta) }, singleLine = true,
        modifier = Modifier.fillMaxWidth(),
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words))
}

/** Fotos de autos incluidas en la app (foto = "auto:1" … "auto:6"). */
val AUTOS = listOf(pe.cooruta.app.R.drawable.auto_1, pe.cooruta.app.R.drawable.auto_2, pe.cooruta.app.R.drawable.auto_3,
    pe.cooruta.app.R.drawable.auto_4, pe.cooruta.app.R.drawable.auto_5, pe.cooruta.app.R.drawable.auto_6)

/** Recurso de la foto incluida si [foto] es "auto:N"; null si es una foto subida o no hay. */
fun autoIncluido(foto: String): Int? =
    foto.takeIf { it.startsWith("auto:") }?.removePrefix("auto:")?.toIntOrNull()?.let { AUTOS.getOrNull(it - 1) }

/** Elegir la foto del auto: una de las incluidas, o subir una desde la galería (como en Perfil). */
@Composable
fun DialogoElegirAuto(alElegir: (Int) -> Unit, alGaleria: () -> Unit, alQuitar: () -> Unit, alCerrar: () -> Unit) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = alCerrar,
        containerColor = Coo.Campo,
        title = { Text("Foto del auto", fontWeight = FontWeight.Bold) },
        text = {
            androidx.compose.foundation.layout.Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                AUTOS.indices.chunked(2).forEach { fila ->
                    androidx.compose.foundation.layout.Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        fila.forEach { i ->
                            androidx.compose.foundation.Image(
                                androidx.compose.ui.res.painterResource(AUTOS[i]), contentDescription = "Auto ${i + 1}",
                                contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                                modifier = Modifier.weight(1f).height(84.dp)
                                    .clip(androidx.compose.foundation.shape.RoundedCornerShape(12.dp))
                                    .clickable { alElegir(i + 1) }
                            )
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

/** Foto del auto (o un recuadro vacío). */
@Composable
fun FotoVehiculo(foto: String, tamano: androidx.compose.ui.unit.Dp) {
    val incluida = autoIncluido(foto)
    val img = androidx.compose.runtime.remember(foto) {
        foto.takeIf { it.isNotBlank() && !it.startsWith("auto:") }?.let { f ->
            runCatching { android.util.Base64.decode(f, android.util.Base64.DEFAULT)
                .let { android.graphics.BitmapFactory.decodeByteArray(it, 0, it.size) }?.asImageBitmap() }.getOrNull()
        }
    }
    androidx.compose.foundation.layout.Box(
        Modifier.size(tamano).clip(androidx.compose.foundation.shape.RoundedCornerShape(12.dp)).background(Coo.CampoActivo),
        contentAlignment = androidx.compose.ui.Alignment.Center
    ) {
        if (incluida != null) androidx.compose.foundation.Image(androidx.compose.ui.res.painterResource(incluida), "Foto del vehículo",
            contentScale = androidx.compose.ui.layout.ContentScale.Crop, modifier = Modifier.size(tamano))
        else if (img != null) androidx.compose.foundation.Image(img, "Foto del vehículo",
            contentScale = androidx.compose.ui.layout.ContentScale.Crop, modifier = Modifier.size(tamano))
        else Text("Auto", color = Coo.TextoTenue, fontSize = 12.sp)
    }
}
