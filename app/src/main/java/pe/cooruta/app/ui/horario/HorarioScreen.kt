package pe.cooruta.app.ui.horario

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimeInput
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TimePickerDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import pe.cooruta.app.data.BorradorRuta
import pe.cooruta.app.data.HorarioRuta
import pe.cooruta.app.ui.componentes.IconoAlerta
import pe.cooruta.app.ui.componentes.PasosCompactos
import pe.cooruta.app.ui.componentes.IconoAsiento
import pe.cooruta.app.ui.componentes.IconoAtras
import pe.cooruta.app.ui.componentes.IconoCheck
import pe.cooruta.app.ui.componentes.IconoMas
import pe.cooruta.app.ui.componentes.IconoMenos
import pe.cooruta.app.ui.componentes.IconoReloj
import pe.cooruta.app.ui.theme.Coo
import java.time.DayOfWeek

/**
 * Paso 2 del asistente: cuándo se hace la ruta.
 *
 * Diseño en Figma: "CooRuta — Publicar ruta", página "Paso 2 · Horario".
 * Pinta el estado de [HorarioViewModel] y le avisa de los toques; no decide nada.
 *
 * El pasajero solo elige días y hora. Asientos libres y pasaje son cosa del
 * conductor: solo aparecen si [esConductor].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HorarioScreen(
    borrador: BorradorRuta,
    esConductor: Boolean,
    alVolver: () -> Unit,
    alSiguiente: (HorarioRuta) -> Unit,
    modifier: Modifier = Modifier,
    vm: HorarioViewModel = viewModel()
) {
    val estado by vm.estado.collectAsStateWithLifecycle()

    // El pasaje y la hora de llegada salen del recorrido del paso 1.
    LaunchedEffect(borrador.trayecto) { vm.usarRecorrido(borrador.trayecto) }
    // Al editar (o al volver desde Confirmar): muestra el horario guardado.
    LaunchedEffect(Unit) { borrador.horario?.let(vm::precargar) }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = Coo.Fondo,
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("Horario", fontSize = 22.sp, fontWeight = FontWeight.Medium)
                        Text(
                            text = if (esConductor) "Cuándo haces esta ruta" else "Cuándo viajas",
                            fontSize = 13.sp,
                            color = Coo.TextoTenue
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = alVolver) { IconoAtras() }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = Coo.Fondo,
                    titleContentColor = Coo.Texto
                )
            )
        },
        bottomBar = {
            HojaSiguiente(
                estado = estado,
                esConductor = esConductor,
                alSiguiente = { vm.confirmar(esConductor)?.let(alSiguiente) },
                alAnterior = alVolver
            )
        }
    ) { relleno ->
        Column(
            modifier = Modifier
                .padding(relleno)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            PasosCompactos(pasoActual = 2, modifier = Modifier.padding(horizontal = 8.dp))

            SeccionDias(
                dias = estado.dias,
                conError = estado.sinDias,
                alTocarDia = vm::alternarDia,
                alElegirDias = vm::elegirDias
            )

            SeccionHora(
                estado = estado,
                destino = borrador.destino.nombre,
                esConductor = esConductor,
                alAbrir = vm::abrirHora
            )

            if (esConductor) Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(IntrinsicSize.Min),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                TarjetaContador(
                    titulo = "Asientos libres",
                    valor = estado.asientos.toString(),
                    descripcionMenos = "Ofrecer un asiento menos",
                    descripcionMas = "Ofrecer un asiento más",
                    alMenos = { vm.cambiarAsientos(-1) },
                    alMas = { vm.cambiarAsientos(1) },
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        repeat(CAPACIDAD_CARRO) { i ->
                            val ofrecido = i < estado.asientos
                            IconoAsiento(
                                color = if (ofrecido) Coo.AzulUbicacion else Coo.PlomoOscuro,
                                relleno = ofrecido,
                                tamano = 22.dp
                            )
                        }
                    }
                    Text("Tu carro lleva $CAPACIDAD_CARRO", fontSize = 12.sp, color = Coo.TextoTenue)
                }

                TarjetaPasaje(
                    pasajeCentimos = estado.pasajeCentimos,
                    kilometros = estado.kilometros,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                )
            }
        }
    }

    if (estado.eligiendoHora) {
        DialogoHora(
            hora = estado.salida.hour,
            minuto = estado.salida.minute,
            destino = borrador.destino.nombre,
            llegadaPara = vm::llegadaSiSale,
            alCancelar = vm::cerrarHora,
            alAceptar = vm::fijarHora
        )
    }
}

// ------------------------------------------------------------------ secciones

/** De dónde a dónde va la ruta (lo que se eligió en el paso 1). */
@Composable
private fun ResumenRuta(borrador: BorradorRuta) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = Coo.Campo,
        border = BorderStroke(1.dp, Coo.Borde)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(IntrinsicSize.Min)
                .padding(horizontal = 16.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Punto azul, línea de puntos y punto rojo: el mismo conector del paso 1.
            Column(
                modifier = Modifier
                    .width(10.dp)
                    .fillMaxHeight()
                    .padding(vertical = 5.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Box(Modifier.size(10.dp).background(Coo.AzulUbicacion, CircleShape))
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(vertical = 3.dp),
                    verticalArrangement = Arrangement.SpaceEvenly
                ) {
                    repeat(4) { Box(Modifier.size(2.dp, 3.dp).background(Coo.TextoTenue)) }
                }
                Box(Modifier.size(10.dp).background(Coo.RojoDestino, CircleShape))
            }

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                LineaLugar(
                    nombre = borrador.origen?.nombre ?: "Mi ubicación actual",
                    detalle = borrador.origen?.detalle.orEmpty()
                )
                LineaLugar(nombre = borrador.destino.nombre, detalle = borrador.destino.detalle)
            }

            borrador.trayecto?.let { t ->
                Column(
                    modifier = Modifier.align(Alignment.CenterVertically),
                    horizontalAlignment = Alignment.End
                ) {
                    Text(t.duracionLegible(), color = Coo.AzulUbicacion, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    Text(t.distanciaLegible(), color = Coo.TextoTenue, fontSize = 12.sp)
                }
            }
        }
    }
}

@Composable
private fun LineaLugar(nombre: String, detalle: String) {
    Column {
        Text(nombre, fontSize = 15.sp, color = Coo.Texto, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (detalle.isNotBlank()) {
            Text(detalle, fontSize = 12.sp, color = Coo.TextoTenue, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

private val DIAS = listOf(
    DayOfWeek.MONDAY to ("L" to "lunes"),
    DayOfWeek.TUESDAY to ("M" to "martes"),
    DayOfWeek.WEDNESDAY to ("M" to "miércoles"),
    DayOfWeek.THURSDAY to ("J" to "jueves"),
    DayOfWeek.FRIDAY to ("V" to "viernes"),
    DayOfWeek.SATURDAY to ("S" to "sábado"),
    DayOfWeek.SUNDAY to ("D" to "domingo")
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SeccionDias(
    dias: Set<DayOfWeek>,
    conError: Boolean,
    alTocarDia: (DayOfWeek) -> Unit,
    alElegirDias: (Set<DayOfWeek>) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Días de trayecto", fontSize = 16.sp, fontWeight = FontWeight.Medium, color = Coo.Texto)

        // Barra segmentada: una casilla por día, separadas por una línea fina.
        val forma = RoundedCornerShape(12.dp)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
                .clip(forma)
                .background(Coo.Campo)
                .border(1.dp, if (conError) Coo.RojoError else Coo.Borde, forma)
        ) {
            DIAS.forEachIndexed { i, (dia, letras) ->
                val (letra, nombre) = letras
                val elegido = dia in dias
                if (i > 0) Box(Modifier.width(1.dp).fillMaxHeight().background(Coo.Borde))
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .background(if (elegido) Coo.AzulBoton.copy(alpha = 0.55f) else Color.Transparent)
                        // Cada día es una casilla: TalkBack dice "lunes, marcado".
                        .toggleable(
                            value = elegido,
                            role = Role.Checkbox,
                            onValueChange = { alTocarDia(dia) }
                        )
                        .semantics { contentDescription = nombre },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = letra,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Medium,
                        color = if (elegido) Coo.Texto else Coo.TextoTenue
                    )
                }
            }
        }

        if (conError) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                IconoAlerta(color = Coo.RojoError, tamano = 18.dp)
                Text("Elige al menos un día para publicar la ruta", fontSize = 13.sp, color = Coo.RojoError)
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AtajoDias("Lunes a viernes", dias == LUNES_A_VIERNES) { alElegirDias(LUNES_A_VIERNES) }
            AtajoDias("Todos los días", dias == TODOS_LOS_DIAS) { alElegirDias(TODOS_LOS_DIAS) }
        }
    }
}

@Composable
private fun AtajoDias(texto: String, elegido: Boolean, alTocar: () -> Unit) {
    FilterChip(
        selected = elegido,
        onClick = alTocar,
        label = { Text(texto, fontSize = 13.sp) },
        leadingIcon = if (elegido) {
            { IconoCheck(color = Coo.AzulUbicacion, tamano = 16.dp) }
        } else null,
        colors = FilterChipDefaults.filterChipColors(
            containerColor = Coo.Fondo,
            labelColor = Coo.Texto,
            selectedContainerColor = Coo.CampoActivo,
            selectedLabelColor = Coo.Texto
        ),
        border = FilterChipDefaults.filterChipBorder(
            enabled = true,
            selected = elegido,
            borderColor = Coo.Borde,
            selectedBorderColor = Coo.AzulUbicacion,
            borderWidth = 1.dp,
            selectedBorderWidth = 1.dp
        )
    )
}

@Composable
private fun SeccionHora(estado: EstadoHorario, destino: String, esConductor: Boolean, alAbrir: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = if (esConductor) "¿A qué hora sales?" else "¿A qué hora quieres salir?",
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium,
                color = Coo.Texto
            )
            TextButton(onClick = alAbrir) {
                Text("Cambiar", color = Coo.AzulUbicacion, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            }
        }

        val (horaTexto, sufijo) = formatoHora(estado.salida).let { it.substringBefore(' ') to it.substringAfter(' ') }

        Surface(
            onClick = alAbrir,
            shape = RoundedCornerShape(16.dp),
            color = Coo.Campo,
            border = BorderStroke(1.dp, Coo.Borde)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(IntrinsicSize.Min)
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(horaTexto, fontSize = 40.sp, lineHeight = 44.sp, color = Coo.Texto)
                    Spacer(Modifier.width(4.dp))
                    Text(sufijo, fontSize = 16.sp, color = Coo.TextoTenue, modifier = Modifier.padding(bottom = 6.dp))
                }
            }
        }
    }
}

/** Tarjeta con − valor + y lo que se quiera debajo. */
@Composable
private fun TarjetaContador(
    titulo: String,
    valor: String,
    descripcionMenos: String,
    descripcionMas: String,
    alMenos: () -> Unit,
    alMas: () -> Unit,
    modifier: Modifier = Modifier,
    debajo: @Composable () -> Unit
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        color = Coo.Campo,
        border = BorderStroke(1.dp, Coo.Borde)
    ) {
        Column(
            modifier = Modifier.padding(start = 10.dp, end = 10.dp, top = 14.dp, bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(titulo, fontSize = 13.sp, color = Coo.TextoTenue, modifier = Modifier.padding(horizontal = 4.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                BotonRedondo(descripcionMenos, alMenos) { IconoMenos(tamano = 18.dp) }
                Text(valor, fontSize = 24.sp, fontWeight = FontWeight.Medium, color = Coo.Texto)
                BotonRedondo(descripcionMas, alMas) { IconoMas(tamano = 18.dp) }
            }
            Column(
                modifier = Modifier.padding(horizontal = 4.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) { debajo() }
        }
    }
}

/**
 * El pasaje no se elige: sale de la distancia por la tarifa por kilómetro.
 * La tarjeta muestra el resultado y de dónde sale, para que no parezca un
 * número inventado.
 */
@Composable
private fun TarjetaPasaje(pasajeCentimos: Int?, kilometros: Double?, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        color = Coo.Campo,
        border = BorderStroke(1.dp, Coo.Borde)
    ) {
        Column(
            modifier = Modifier.padding(start = 14.dp, end = 14.dp, top = 14.dp, bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text("Pasaje", fontSize = 13.sp, color = Coo.TextoTenue)
            Text(
                text = pasajeCentimos?.let { formatoSoles(it) } ?: "—",
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold,
                color = Coo.Texto,
                modifier = Modifier.padding(vertical = 4.dp)
            )
            Text(
                text = if (kilometros != null) {
                    "${formatoSoles(TARIFA_KM_CENTIMOS)} por km × " +
                        "${"%.1f".format(kilometros).replace('.', ',')} km, por pasajero"
                } else {
                    "Se calcula con la distancia del recorrido."
                },
                fontSize = 12.sp,
                lineHeight = 16.sp,
                color = Coo.TextoTenue
            )
        }
    }
}

/** Botón de 40dp a la vista, pero con 48dp de área táctil como pide Android. */
@Composable
private fun BotonRedondo(descripcion: String, alTocar: () -> Unit, icono: @Composable () -> Unit) {
    Box(
        modifier = Modifier
            .size(48.dp)
            .clip(CircleShape)
            .clickable(role = Role.Button, onClickLabel = descripcion, onClick = alTocar)
            .semantics { contentDescription = descripcion },
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .background(Coo.Fondo, CircleShape)
                .border(1.dp, Coo.Borde, CircleShape),
            contentAlignment = Alignment.Center
        ) { icono() }
    }
}

/** La hoja de abajo: resumen de lo elegido y el botón para seguir. */
@Composable
private fun HojaSiguiente(estado: EstadoHorario, esConductor: Boolean, alSiguiente: () -> Unit, alAnterior: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
        color = Coo.Campo,
        shadowElevation = 16.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(start = 24.dp, end = 24.dp, top = 16.dp, bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // Un solo botón: ‹ a la izquierda (anterior), "Siguiente" al centro y › a la derecha.
            val activo = estado.puedeSeguir
            val fondo = if (activo) Coo.AzulBoton else Coo.BordeSuave
            val tinta = if (activo) Coo.SobreBoton else Coo.TextoTenue
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(fondo)
            ) {
                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .clickable(enabled = activo, onClick = alSiguiente)
                        .semantics { contentDescription = "Siguiente" }
                )
                Text("Siguiente", fontSize = 17.sp, fontWeight = FontWeight.Bold, color = tinta,
                    modifier = Modifier.align(Alignment.Center))
                Box(Modifier.align(Alignment.CenterEnd).padding(end = 22.dp)) { Flechita(izquierda = false, color = tinta) }
                Box(
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .width(64.dp)
                        .fillMaxHeight()
                        .clickable(onClick = alAnterior)
                        .semantics { contentDescription = "Anterior" },
                    contentAlignment = Alignment.Center
                ) { Flechita(izquierda = true, color = tinta) }
            }
        }
    }
}

/** Flecha ‹ o › (la misma de toda la app). */
@Composable
private fun Flechita(izquierda: Boolean, color: androidx.compose.ui.graphics.Color) {
    if (izquierda) IconoAtras(color = color, tamano = 22.dp)
    else pe.cooruta.app.ui.componentes.IconoAdelante(color = color, tamano = 22.dp)
}

/**
 * Diálogo para elegir la hora de salida.
 *
 * Abre con el reloj de Material 3 (TimePicker): se elige tocando, sin teclado.
 * "Escribir la hora" cambia al TimeInput para quien prefiera teclearla.
 *
 * Va con Dialog propio y no con AlertDialog: el reloj mide unos 320dp de ancho
 * y AlertDialog lo apretaba hasta dejarlo sin respuesta en pantallas angostas.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DialogoHora(
    hora: Int,
    minuto: Int,
    destino: String,
    llegadaPara: (Int, Int) -> java.time.LocalTime?,
    alCancelar: () -> Unit,
    alAceptar: (Int, Int) -> Unit
) {
    val reloj = rememberTimePickerState(initialHour = hora, initialMinute = minuto, is24Hour = false)
    var conTeclado by rememberSaveable { mutableStateOf(false) }
    val llegada = llegadaPara(reloj.hour, reloj.minute)

    val colores = TimePickerDefaults.colors(
        clockDialColor = Coo.Fondo,
        clockDialSelectedContentColor = Coo.SobreBoton,
        clockDialUnselectedContentColor = Coo.TextoTenue,
        selectorColor = Coo.AzulBoton,
        containerColor = Coo.Campo,
        periodSelectorBorderColor = Coo.Borde,
        periodSelectorSelectedContainerColor = Coo.AzulBoton,
        periodSelectorUnselectedContainerColor = Coo.Campo,
        periodSelectorSelectedContentColor = Coo.SobreBoton,
        periodSelectorUnselectedContentColor = Coo.TextoTenue,
        timeSelectorSelectedContainerColor = Coo.AzulBoton,
        timeSelectorUnselectedContainerColor = Coo.Fondo,
        timeSelectorSelectedContentColor = Coo.SobreBoton,
        timeSelectorUnselectedContentColor = Coo.Texto
    )

    Dialog(
        onDismissRequest = alCancelar,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .padding(horizontal = 20.dp)
                .widthIn(max = 400.dp),
            shape = RoundedCornerShape(28.dp),
            color = Coo.Campo
        ) {
            Column(
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text(
                    text = "HORA DE SALIDA",
                    modifier = Modifier.fillMaxWidth(),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    letterSpacing = 0.6.sp,
                    color = Coo.TextoTenue
                )

                if (conTeclado) {
                    TimeInput(state = reloj, colors = colores)
                } else {
                    TimePicker(state = reloj, colors = colores)
                }

                if (llegada != null) {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        color = Coo.Fondo
                    ) {
                        Text(
                            text = "Llegarías a $destino cerca de las ${formatoHora(llegada)}",
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                            fontSize = 14.sp,
                            color = Coo.Texto
                        )
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = { conTeclado = !conTeclado }) {
                        Text(
                            text = if (conTeclado) "Usar el reloj" else "Escribir la hora",
                            color = Coo.TextoTenue
                        )
                    }
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = alCancelar) { Text("Cancelar", color = Coo.AzulUbicacion) }
                    TextButton(onClick = { alAceptar(reloj.hour, reloj.minute) }) {
                        Text("Listo", color = Coo.AzulUbicacion, fontWeight = FontWeight.Medium)
                    }
                }
            }
        }
    }
}
