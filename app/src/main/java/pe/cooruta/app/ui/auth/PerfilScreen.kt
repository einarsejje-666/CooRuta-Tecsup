package pe.cooruta.app.ui.auth

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.draw.blur
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import pe.cooruta.app.ui.componentes.IconoArriba
import pe.cooruta.app.ui.componentes.IconoAbajo
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import pe.cooruta.app.R
import pe.cooruta.app.ui.componentes.IconoAtras
import pe.cooruta.app.ui.componentes.IconoAuto
import pe.cooruta.app.ui.componentes.IconoCheck
import pe.cooruta.app.ui.componentes.IconoPersona
import pe.cooruta.app.ui.theme.Coo

/** Los dos roles con los que se puede usar CooRuta. */
enum class Rol { PASAJERO, CONDUCTOR }

/** El rol que la persona dice que asumirá al registrarse. */
enum class RolDeseado { PASAJERO, CONDUCTOR }

/** Lo que se llena en el registro. Los motivos solo los da la comunidad externa. */
data class DatosRegistro(
    val nombres: String, val apellidos: String, val telefono: String, val rol: RolDeseado,
    val motivos: List<String>, val motivoOtro: String
)

/** Motivos que se marcan con casillas (además de «Otros»). */
val MOTIVOS_COORUTA = listOf("Ahorrar en pasajes", "Llegar más rápido", "Evitar transbordos",
    "Viajar más seguro", "Compartir los gastos de mi auto", "Cuidar el medio ambiente")

/**
 * Revisión simple del celular peruano (sin SMS): 9 dígitos que empiezan con 9 y que no parezcan
 * inventados (todos iguales, escaleras como 12345678, bloques repetidos o muy pocos dígitos distintos).
 * Devuelve el problema a mostrar, o null si el número es plausible. No confirma que sea de la persona.
 */
internal fun problemaCelular(n: String): String? {
    if (n.length != 9 || !n.all { it.isDigit() }) return "Escribe tu celular de 9 dígitos."
    if (n[0] != '9') return "Los celulares en Perú empiezan con 9."
    val falso = "Ese número no parece real. Revisa tu celular."
    if (n.toSet().size < 4) return falso
    // 5 o más dígitos iguales seguidos (p. ej. 955555123)
    if (Regex("(\\d)\\1{4,}").containsMatchIn(n)) return falso
    // escalera de 6 o más dígitos seguidos, subiendo o bajando (p. ej. 912345678, 987654321)
    var sube = 1; var baja = 1
    for (i in 1 until n.length) {
        val d = n[i] - n[i - 1]
        sube = if (d == 1) sube + 1 else 1
        baja = if (d == -1) baja + 1 else 1
        if (sube >= 6 || baja >= 6) return falso
    }
    // los 8 dígitos tras el 9 son un bloque repetido (p. ej. 912121212, 912341234)
    val resto = n.substring(1)
    for (p in 1..4) if (resto.indices.all { resto[it] == resto[it % p] }) return falso
    return null
}

/** Cuántas letras necesitan los nombres y los apellidos para darlos por buenos. */
private const val LETRAS_NOMBRE = 2

/** Degradado del botón principal (tonos del azul de CooRuta). */
private val AZUL_CLARO = Color(0xFF7189FF)
private val AZUL_HONDO = Color(0xFF3F55D9)

/**
 * Pantalla posterior al acceso: la persona elige cómo va a usar la app y confirma su nombre.
 * Si entró con Google, el nombre llega prellenado desde esa cuenta.
 * Mismo diseño para Tecsup y para la comunidad externa; solo cambia el texto y los motivos (externos).
 */
@Composable
fun PerfilScreen(
    nombreInicial: String,
    externo: Boolean,
    cargando: Boolean,
    alConfirmar: (DatosRegistro) -> Unit,
    alVolver: () -> Unit,
    modifier: Modifier = Modifier
) {
    // El nombre de Google se reparte: la primera palabra en nombres y el resto en apellidos.
    var nombres by rememberSaveable { mutableStateOf(nombreInicial.trim().substringBefore(' ')) }
    var apellidos by rememberSaveable { mutableStateOf(nombreInicial.trim().substringAfter(' ', "")) }
    var telefono by rememberSaveable { mutableStateOf("") }
    var rol by rememberSaveable { mutableStateOf<RolDeseado?>(null) }
    var motivos by rememberSaveable { mutableStateOf(listOf<String>()) }
    var otros by rememberSaveable { mutableStateOf(false) }
    var motivoOtro by rememberSaveable { mutableStateOf("") }
    var motivosAbierto by rememberSaveable { mutableStateOf(false) }
    val desenfoque by animateDpAsState(if (motivosAbierto) 18.dp else 0.dp, tween(220), label = "desenfoque")

    // Celular peruano: 9 dígitos; el código +51 se muestra fijo en el campo y se agrega al guardar.
    val problemaCelular = problemaCelular(telefono)
    val telefonoValido = problemaCelular == null
    val motivosListos = !externo || ((motivos.isNotEmpty() || otros) && (!otros || motivoOtro.trim().length >= 3))
    val listo = rol != null && nombres.trim().length >= LETRAS_NOMBRE && apellidos.trim().length >= LETRAS_NOMBRE &&
        telefonoValido && motivosListos
    // Los errores se muestran recién al pulsar «Continuar» con algo pendiente.
    var intentado by rememberSaveable { mutableStateOf(false) }
    val desplazamiento = rememberScrollState()
    val alcance = rememberCoroutineScope()

    Surface(modifier = modifier.fillMaxSize(), color = Coo.Fondo) {
        Box(Modifier.fillMaxSize().blur(desenfoque)) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .safeDrawingPadding()
                    .imePadding()
            ) {
                // ---------- lo que se desplaza ----------
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .verticalScroll(desplazamiento)
                        .padding(horizontal = 20.dp)
                ) {
                    Spacer(Modifier.height(8.dp))
                    BotonVolver(alVolver)
                    Spacer(Modifier.height(6.dp))

                    Titulo("Crea tu cuenta", tamano = 28)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = buildAnnotatedString {
                            append("Únete a la comunidad de ")
                            withStyle(SpanStyle(color = Coo.AzulUbicacion, fontWeight = FontWeight.Bold)) {
                                append(if (externo) "Arequipa" else "Tecsup")
                            }
                            append(if (externo) " y comparte trayectos seguros con tu grupo." else " y comparte trayectos seguros y confiables.")
                        },
                        color = Coo.Texto.copy(alpha = 0.88f),
                        fontSize = 14.sp,
                        lineHeight = 19.sp
                    )
                    Spacer(Modifier.height(18.dp))
                    Text("¿Cómo usarás la app?", color = Coo.Texto, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(10.dp))
                    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Max)) {
                        TarjetaRolCompacta(
                            titulo = "Soy pasajero",
                            detalle = "Busco un traslado seguro.",
                            seleccionada = rol == RolDeseado.PASAJERO,
                            alPulsar = { rol = RolDeseado.PASAJERO },
                            modifier = Modifier.weight(1f).fillMaxHeight()
                        ) { color -> IconoPersona(color = color, lleno = false, tamano = 32.dp) }
                        Spacer(Modifier.width(12.dp))
                        TarjetaRolCompacta(
                            titulo = "Soy conductor",
                            detalle = "Tengo asientos disponibles y quiero compartir mi ruta.",
                            seleccionada = rol == RolDeseado.CONDUCTOR,
                            alPulsar = { rol = RolDeseado.CONDUCTOR },
                            modifier = Modifier.weight(1f).fillMaxHeight()
                        ) { color -> IconoAuto(color = color, lleno = false, tamano = 36.dp) }
                    }
                    if (intentado && rol == null) TextoError("Elige si eres pasajero o conductor (luego puedes cambiarlo en tu perfil).")

                    Spacer(Modifier.height(16.dp))
                    Titulo("Tus datos", tamano = 18)
                    Spacer(Modifier.height(8.dp))
                    Etiqueta("Nombres")
                    Spacer(Modifier.height(5.dp))
                    val errNombres = intentado && nombres.trim().length < LETRAS_NOMBRE
                    CampoNombre(valor = nombres, alCambiar = { nombres = it.take(60) }, pista = "Escribe tus nombres", error = errNombres, alto = 50.dp)
                    if (errNombres) TextoError("Escribe tus nombres.")
                    Spacer(Modifier.height(10.dp))
                    Etiqueta("Apellidos")
                    Spacer(Modifier.height(5.dp))
                    val errApellidos = intentado && apellidos.trim().length < LETRAS_NOMBRE
                    CampoNombre(valor = apellidos, alCambiar = { apellidos = it.take(60) }, pista = "Escribe tus apellidos", error = errApellidos, alto = 50.dp)
                    if (errApellidos) TextoError("Escribe tus apellidos.")
                    Spacer(Modifier.height(10.dp))
                    Etiqueta("Celular")
                    Spacer(Modifier.height(5.dp))
                    CampoNombre(valor = telefono, alCambiar = { v -> telefono = v.filter { it.isDigit() }.take(9) },
                        pista = "*** *** ***", telefono = true, error = intentado && !telefonoValido, alto = 50.dp)
                    if (intentado && problemaCelular != null) TextoError(problemaCelular)
                    if (externo) {
                        Spacer(Modifier.height(10.dp))
                        Etiqueta("¿Para qué usarás CooRuta?")
                        Spacer(Modifier.height(5.dp))
                        DesplegableMotivos(
                            seleccion = motivos,
                            otros = otros,
                            error = intentado && motivos.isEmpty() && !otros,
                            abierto = motivosAbierto,
                            alAbrir = { motivosAbierto = true },
                            alCerrar = { motivosAbierto = false },
                            alElegir = { m ->
                                // Una sola opción: al elegir se registra y la ventana se cierra.
                                if (m == "Otros") { motivos = emptyList(); otros = true } else { motivos = listOf(m); otros = false }
                            }
                        )
                        if (otros) {
                            Spacer(Modifier.height(10.dp))
                            val errOtro = intentado && motivoOtro.trim().length < 3
                            CampoNombre(valor = motivoOtro, alCambiar = { motivoOtro = it.take(120) }, pista = "Escribe tu motivo", conIcono = false, error = errOtro)
                            if (errOtro) TextoError("Escribe tu motivo.")
                        }
                        if (intentado && motivos.isEmpty() && !otros) TextoError("Elige un motivo.")
                    }
                    Spacer(Modifier.height(12.dp))
                }

                // ---------- anclado abajo ----------
                Column(Modifier.padding(horizontal = 20.dp)) {
                    if (intentado && !listo) {
                        Text("Completa los campos marcados en rojo.", color = Coo.RojoError, fontSize = 14.sp,
                            modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp), textAlign = TextAlign.Center)
                    }
                    BotonContinuar(habilitado = !cargando, cargando = cargando) {
                        intentado = true
                        // Si falta el rol, sube hasta las tarjetas para que se vea el aviso.
                        if (rol == null) alcance.launch { desplazamiento.animateScrollTo(0) }
                        if (listo) rol?.let { elegido ->
                            alConfirmar(DatosRegistro(nombres.trim(), apellidos.trim(), "+51 $telefono", elegido,
                                if (externo) motivos else emptyList(), if (externo && otros) motivoOtro.trim() else ""))
                        }
                    }
                    Spacer(Modifier.height(14.dp))
                }
            }
        }
    }
}

/** Pin rojo de mapa (estilo Google Maps) que marca el rol elegido en el registro Tecsup. */
private val PinMapa: ImageVector by lazy {
    ImageVector.Builder("pinMapa", 24.dp, 24.dp, 24f, 24f).apply {
        addPath(addPathNodes("M12 2C8.13 2 5 5.13 5 9c0 5.25 7 13 7 13s7-7.75 7-13c0-3.87-3.13-7-7-7z"), fill = SolidColor(Coo.RojoDestino))
        addPath(addPathNodes("M12 6.5a2.5 2.5 0 1 1 0 5a2.5 2.5 0 1 1 0-5z"), fill = SolidColor(Color(0xFF8E1B14)))
    }.build()
}

/** Título de sección del registro Tecsup (sin adornos: la jerarquía la da el tamaño). */
@Composable
private fun Titulo(texto: String, tamano: Int) {
    Text(texto, color = Coo.Texto, fontSize = tamano.sp, lineHeight = (tamano + 6).sp, fontWeight = FontWeight.Bold)
}

/**
 * Tarjeta de rol compacta (dos lado a lado): ícono de línea arriba, título y detalle centrados.
 * La elegida se nota por el borde azul, el fondo azulado y el pin rojo de mapa en la esquina.
 */
@Composable
private fun TarjetaRolCompacta(
    titulo: String,
    detalle: String,
    seleccionada: Boolean,
    alPulsar: () -> Unit,
    modifier: Modifier = Modifier,
    icono: @Composable (Color) -> Unit
) {
    val borde by animateColorAsState(if (seleccionada) Coo.AzulBoton else Coo.Borde, tween(200), label = "borde")
    val forma = RoundedCornerShape(22.dp)
    Box(
        modifier = modifier
            .clip(forma)
            .background(if (seleccionada) Coo.AzulBoton.copy(alpha = 0.14f) else Coo.Campo)
            .border(if (seleccionada) 2.dp else 1.dp, borde, forma)
            .clickable(onClick = alPulsar)
            .semantics {
                this.selected = seleccionada
                role = Role.RadioButton
            }
    ) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 14.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            icono(if (seleccionada) Coo.Texto else Coo.Texto.copy(alpha = 0.85f))
            Spacer(Modifier.height(8.dp))
            Text(titulo, color = Coo.Texto, fontSize = 16.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
            Spacer(Modifier.height(3.dp))
            Text(detalle, color = Coo.TextoTenue, fontSize = 12.sp, lineHeight = 16.sp, textAlign = TextAlign.Center)
        }
        if (seleccionada) {
            Image(
                imageVector = PinMapa,
                contentDescription = null,
                modifier = Modifier.align(Alignment.TopEnd).padding(8.dp).size(30.dp)
            )
        }
    }
}

/** Botón de volver: flecha «<» con área táctil completa de 48dp. */
@Composable
private fun BotonVolver(alTocar: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(48.dp)
            .clip(CircleShape)
            .clickable(onClick = alTocar)
            .semantics {
                contentDescription = "Volver"
                role = Role.Button
            },
        contentAlignment = Alignment.Center
    ) {
        IconoAtras(tamano = 24.dp)
    }
}

/** Aviso rojo bajo un campo que falta completar. */
@Composable
private fun TextoError(texto: String) {
    Text(texto, color = Coo.RojoError, fontSize = 13.sp, modifier = Modifier.padding(start = 6.dp, top = 6.dp))
}

/** Título de un grupo de campos: presente, pero sin competir con el encabezado. */
@Composable
private fun Etiqueta(texto: String, modifier: Modifier = Modifier) {
    Text(text = texto, color = Coo.TextoTenue, fontSize = 13.sp, fontWeight = FontWeight.Medium, modifier = modifier)
}

/** Campo de texto del registro, con un ícono de persona (o de teléfono) delante. */
@Composable
private fun CampoNombre(
    valor: String,
    alCambiar: (String) -> Unit,
    modifier: Modifier = Modifier,
    pista: String = "Escribe tu nombre",
    telefono: Boolean = false,
    conIcono: Boolean = true,
    error: Boolean = false,
    alto: Dp = 58.dp
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = alto),
        shape = RoundedCornerShape(16.dp),
        color = Coo.Campo,
        border = BorderStroke(if (error) 1.5.dp else 1.dp, if (error) Coo.RojoError else Coo.Borde)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = if (alto < 58.dp) 12.dp else 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (telefono) {
                // Bandera del Perú y código fijo +51
                BanderaPeru()
                Spacer(Modifier.width(8.dp))
                Text("+51", color = Coo.Texto, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.width(12.dp))
                Box(Modifier.width(1.dp).height(22.dp).background(Coo.Borde))
            }
            else if (conIcono) IconoPersona(color = Coo.TextoTenue, lleno = false, tamano = 22.dp)
            if (telefono || conIcono) Spacer(Modifier.width(14.dp))
            Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                if (valor.isEmpty()) Text(pista, color = Coo.TextoTenue, fontSize = 16.sp)
                BasicTextField(
                    value = valor,
                    onValueChange = alCambiar,
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    textStyle = TextStyle(color = Coo.Texto, fontSize = 16.sp),
                    cursorBrush = SolidColor(Coo.AzulUbicacion),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = if (telefono) KeyboardType.Phone else KeyboardType.Text,
                        capitalization = if (telefono) KeyboardCapitalization.None
                            else if (conIcono) KeyboardCapitalization.Words else KeyboardCapitalization.Sentences,
                        imeAction = ImeAction.Done
                    )
                )
            }
        }
    }
}

/**
 * Campo de motivo: muestra el motivo elegido y, al tocarlo, abre una ventana flotante (con el fondo
 * desenfocado) con las opciones en casillas. Al elegir una se registra y la ventana se cierra.
 */
@Composable
private fun DesplegableMotivos(
    seleccion: List<String>,
    otros: Boolean,
    error: Boolean,
    abierto: Boolean,
    alAbrir: () -> Unit,
    alCerrar: () -> Unit,
    alElegir: (String) -> Unit
) {
    val elegido = if (otros) "Otros" else seleccion.firstOrNull()
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = Coo.Campo,
        border = BorderStroke(if (error) 1.5.dp else 1.dp, when { error -> Coo.RojoError; abierto -> Coo.AzulBoton; else -> Coo.Borde }),
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 50.dp)
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = alAbrir)
            .semantics { role = Role.DropdownList }
    ) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 13.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                elegido ?: "Elige un motivo",
                color = if (elegido == null) Coo.TextoTenue else Coo.Texto,
                fontSize = 15.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(8.dp))
            if (abierto) IconoArriba(color = Coo.TextoTenue, tamano = 20.dp) else IconoAbajo(color = Coo.TextoTenue, tamano = 20.dp)
        }
    }

    if (abierto) {
        Dialog(onDismissRequest = alCerrar, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            Surface(
                shape = RoundedCornerShape(26.dp),
                color = Coo.Campo,
                border = BorderStroke(1.dp, Coo.Borde),
                shadowElevation = 24.dp,
                modifier = Modifier.fillMaxWidth(0.88f)
            ) {
                Column(Modifier.padding(top = 22.dp, bottom = 10.dp)) {
                    Text("¿Para qué usarás CooRuta?", color = Coo.Texto, fontSize = 19.sp, fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 22.dp))
                    Spacer(Modifier.height(4.dp))
                    Text("Elige la opción que más te represente.", color = Coo.TextoTenue, fontSize = 13.sp,
                        modifier = Modifier.padding(horizontal = 22.dp))
                    Spacer(Modifier.height(12.dp))
                    (MOTIVOS_COORUTA + "Otros").forEach { m ->
                        val marcada = m == elegido
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    alElegir(m)
                                    alCerrar()
                                }
                                .background(if (marcada) Coo.AzulBoton.copy(alpha = 0.12f) else Color.Transparent)
                                .padding(horizontal = 22.dp, vertical = 13.dp)
                                .semantics { role = Role.Checkbox; this.selected = marcada },
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Casilla(marcada)
                            Spacer(Modifier.width(14.dp))
                            Text(m, color = Coo.Texto, fontSize = 15.sp, fontWeight = if (marcada) FontWeight.SemiBold else FontWeight.Normal)
                        }
                    }
                    Text(
                        "Cancelar",
                        color = Coo.AzulUbicacion,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier
                            .align(Alignment.End)
                            .padding(end = 10.dp, top = 6.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .clickable(onClick = alCerrar)
                            .padding(horizontal = 14.dp, vertical = 10.dp)
                    )
                }
            }
        }
    }
}

/** Bandera del Perú: rojo, blanco y rojo en franjas verticales. */
@Composable
private fun BanderaPeru() {
    Row(Modifier.size(width = 26.dp, height = 18.dp).clip(RoundedCornerShape(3.dp))) {
        Box(Modifier.weight(1f).fillMaxHeight().background(Color(0xFFD91023)))
        Box(Modifier.weight(1f).fillMaxHeight().background(Color.White))
        Box(Modifier.weight(1f).fillMaxHeight().background(Color(0xFFD91023)))
    }
}

/** Casilla cuadrada: vacía con borde, o azul con palomita cuando está marcada. */
@Composable
private fun Casilla(marcada: Boolean) {
    Box(
        modifier = Modifier
            .size(22.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(if (marcada) Coo.AzulBoton else Color.Transparent)
            .border(2.dp, if (marcada) Coo.AzulBoton else Coo.TextoTenue, RoundedCornerShape(6.dp)),
        contentAlignment = Alignment.Center
    ) {
        if (marcada) IconoCheck(tamano = 16.dp, color = Coo.Texto)
    }
}

/** «Continuar» con degradado azul y flecha; apagado hasta elegir rol y escribir el nombre. */
@Composable
private fun BotonContinuar(habilitado: Boolean, cargando: Boolean, alPulsar: () -> Unit) {
    val forma = RoundedCornerShape(18.dp)
    Box(
        Modifier
            .fillMaxWidth()
            .height(58.dp)
            .clip(forma)
            .background(
                if (habilitado) Brush.horizontalGradient(listOf(AZUL_CLARO, Coo.AzulBoton, AZUL_HONDO))
                else SolidColor(Coo.CampoActivo)
            )
            .border(1.dp, if (habilitado) AZUL_CLARO.copy(alpha = 0.7f) else Coo.Borde, forma)
            .clickable(enabled = habilitado, onClick = alPulsar)
            .semantics { role = Role.Button },
        contentAlignment = Alignment.Center
    ) {
        Text(if (cargando) "Creando…" else "Continuar",
            color = if (habilitado) Coo.SobreBoton else Coo.TextoTenue, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        pe.cooruta.app.ui.componentes.IconoAdelante(
            Modifier.align(Alignment.CenterEnd).padding(end = 20.dp), if (habilitado) Color.White else Coo.TextoTenue, 22.dp)
    }
}
