package pe.cooruta.app.ui.confirmar

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import pe.cooruta.app.ui.componentes.CampoTexto
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import pe.cooruta.app.data.BorradorRuta
import pe.cooruta.app.data.HorarioRuta
import pe.cooruta.app.ui.componentes.IconoAlerta
import pe.cooruta.app.ui.componentes.IconoAtras
import pe.cooruta.app.ui.componentes.IconoCheck
import pe.cooruta.app.ui.componentes.PasosCompactos
import pe.cooruta.app.ui.horario.formatoHora
import pe.cooruta.app.ui.horario.formatoSoles
import pe.cooruta.app.ui.horario.resumenDias
import pe.cooruta.app.ui.theme.Coo
import java.time.LocalTime
import kotlin.math.roundToInt

/**
 * Paso 3 del asistente: revisar todo y publicar.
 *
 * El conductor ve su ruta tal como la verán los pasajeros y la publica. El
 * pasajero revisa su viaje y guarda la ruta para encontrar conductores.
 * Desde aquí se puede volver a editar cualquiera de los dos pasos anteriores.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConfirmarScreen(
    borrador: BorradorRuta,
    nombre: String,
    esConductor: Boolean,
    alVolver: () -> Unit,
    alEditarRuta: () -> Unit,
    alEditarHorario: () -> Unit,
    alTerminar: () -> Unit,
    modifier: Modifier = Modifier,
    /** Editar: se sobrescribe esta ruta en vez de crear otra. */
    idEdicion: String? = null,
    aliasInicial: String = "",
    vm: ConfirmarViewModel = viewModel(factory = ConfirmarViewModel.Fabrica)
) {
    val estado by vm.estado.collectAsStateWithLifecycle()
    LaunchedEffect(idEdicion) { vm.prepararEdicion(idEdicion, aliasInicial) }
    val horario = borrador.horario ?: return
    // Conductor: qué tramo quedaría visible en Buscar (se recalcula si cambió la ruta).
    LaunchedEffect(borrador, esConductor) { vm.prepararTramo(borrador, esConductor) }
    LaunchedEffect(esConductor) { if (esConductor) vm.cargarGrupos() }

    val terminar = {
        vm.reiniciar()
        alTerminar()
    }

    if (estado.publicadaComo != null) {
        BackHandler(onBack = terminar)
        Exito(borrador, horario, esConductor, alListo = terminar, modifier = modifier)
        return
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = Coo.Fondo,
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("Confirmar", fontSize = 22.sp, fontWeight = FontWeight.Medium)
                        Text(
                            text = if (!esConductor) "Revisa tu viaje" else if (estado.modo == ModoTramo.SIN_BUSCAR) "Revisa antes de guardar" else "Revisa antes de publicar",
                            fontSize = 13.sp,
                            color = Coo.TextoTenue
                        )
                    }
                },
                navigationIcon = { IconButton(onClick = alVolver) { IconoAtras() } },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = Coo.Fondo,
                    titleContentColor = Coo.Texto
                )
            )
        },
        bottomBar = {
            HojaPublicar(
                esConductor = esConductor,
                publicando = estado.publicando,
                error = estado.error,
                habilitado = !esConductor || estado.listoParaPublicar,
                queFalta = if (esConductor) estado.queFalta else null,
                sinBuscar = esConductor && estado.modo == ModoTramo.SIN_BUSCAR,
                alPublicar = { vm.publicar(borrador, nombre, esConductor) },
                alAnterior = alVolver
            )
        }
    ) { relleno ->
        Column(
            modifier = Modifier
                .padding(relleno)
                .imePadding() // el teclado no tapa el campo del nombre
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            PasosCompactos(pasoActual = 3, modifier = Modifier.padding(horizontal = 8.dp))

            if (esConductor) {
                Titulo("Visibilidad de la ruta")
                SeccionTramo(estado, vm)
                Titulo("¿Quién puede ver esta ruta?")
                SeccionAlcance(estado, vm)
            }

            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) { Titulo("Nombre de la ruta") }
                Text("Opcional", fontSize = 13.sp, color = Coo.TextoTenue)
            }
            CampoTexto(
                valor = estado.alias,
                alCambiar = vm::escribirAlias,
                etiqueta = "Ej. Casa → Tecsup",
                comoMarcador = true
            )

            Titulo("Resumen de la ruta")
            TarjetaDatos(borrador, horario, esConductor, alEditarRuta, alEditarHorario)

        }
    }
}

// ------------------------------------------------------------------ partes

@Composable
private fun Titulo(texto: String) {
    Text(
        text = texto,
        fontSize = 16.sp,
        fontWeight = FontWeight.Medium,
        color = Coo.Texto,
        modifier = Modifier.semantics { heading() }
    )
}

/** La ruta como la verá la otra persona: quién, de dónde a dónde, cuándo y cuánto. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TarjetaVistaPrevia(
    borrador: BorradorRuta,
    horario: HorarioRuta,
    nombre: String,
    esConductor: Boolean
) {
    val llegada = llegadaDe(borrador, horario.salida)

    Surface(
        shape = RoundedCornerShape(20.dp),
        color = Coo.Campo,
        border = BorderStroke(1.dp, Coo.AzulUbicacion.copy(alpha = 0.55f))
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {

            // Quién
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .background(Coo.CampoActivo, CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Text(iniciales(nombre), fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Coo.AzulUbicacion)
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = nombre.ifBlank { "Sin nombre" },
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Medium,
                        color = Coo.Texto,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(if (esConductor) "Conductor" else "Pasajero", fontSize = 13.sp, color = Coo.TextoTenue)
                }
                val pasaje = horario.pasajeCentimos
                if (esConductor && pasaje != null) {
                    Column(horizontalAlignment = Alignment.End) {
                        Text(formatoSoles(pasaje), fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Coo.Texto)
                        Text("pasaje", fontSize = 12.sp, color = Coo.TextoTenue)
                    }
                }
            }

            HorizontalDivider(color = Coo.BordeSuave)

            // De dónde a dónde, con la hora en cada extremo
            Row(
                modifier = Modifier.height(IntrinsicSize.Min),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Column(
                    modifier = Modifier.fillMaxHeight(),
                    verticalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(formatoHora(horario.salida).substringBefore(' '), fontSize = 15.sp, fontWeight = FontWeight.Medium, color = Coo.Texto)
                    Text(
                        text = llegada?.let { "~" + formatoHora(it).substringBefore(' ') } ?: "",
                        fontSize = 15.sp,
                        color = Coo.TextoTenue
                    )
                }
                Conector()
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    LineaLugar(borrador.origen?.nombre ?: "Mi ubicación actual", borrador.origen?.detalle.orEmpty())
                    LineaLugar(borrador.destino.nombre, borrador.destino.detalle)
                }
            }

            HorizontalDivider(color = Coo.BordeSuave)

            // Lo demás, en etiquetas cortas. FlowRow: si no caben, bajan a otra línea.
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Etiqueta(resumenDias(horario.dias))
                borrador.trayecto?.let { Etiqueta("${it.duracionLegible()} · ${it.distanciaLegible()}") }
                val asientos = horario.asientos
                if (esConductor && asientos != null) {
                    Etiqueta(if (asientos == 1) "1 asiento" else "$asientos asientos")
                }
            }
        }
    }
}

/** Punto azul, puntos y punto rojo: el mismo conector de los pasos anteriores. */
@Composable
private fun Conector() {
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

@Composable
private fun Etiqueta(texto: String) {
    Surface(shape = RoundedCornerShape(8.dp), color = Coo.Fondo) {
        Text(
            text = texto,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            color = Coo.Texto,
            maxLines = 1
        )
    }
}

/** Cada dato con su botón Editar, que lleva al paso donde se eligió. */
@Composable
private fun TarjetaDatos(
    borrador: BorradorRuta,
    horario: HorarioRuta,
    esConductor: Boolean,
    alEditarRuta: () -> Unit,
    alEditarHorario: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = Coo.Campo,
        border = BorderStroke(1.dp, Coo.Borde)
    ) {
        Column {
            FilaDato(
                etiqueta = "Ruta",
                valor = "${borrador.origen?.nombre ?: "Mi ubicación"} → ${borrador.destino.nombre}",
                accion = "Editar la ruta",
                alEditar = alEditarRuta
            )
            HorizontalDivider(color = Coo.BordeSuave)
            FilaDato(
                etiqueta = "Horario",
                valor = "${resumenDias(horario.dias)} · ${formatoHora(horario.salida)}",
                accion = "Editar el horario",
                alEditar = alEditarHorario
            )
            val asientos = horario.asientos
            val pasaje = horario.pasajeCentimos
            if (esConductor && asientos != null && pasaje != null) {
                HorizontalDivider(color = Coo.BordeSuave)
                FilaDato(
                    etiqueta = "Asientos y pasaje",
                    valor = "${if (asientos == 1) "1 asiento" else "$asientos asientos"} · ${formatoSoles(pasaje)} por pasajero",
                    accion = "Editar asientos",
                    alEditar = alEditarHorario
                )
            }
        }
    }
}

@Composable
private fun FilaDato(etiqueta: String, valor: String, accion: String, alEditar: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 4.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(etiqueta, fontSize = 12.sp, color = Coo.TextoTenue)
            Text(valor, fontSize = 15.sp, color = Coo.Texto, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        TextButton(
            onClick = alEditar,
            modifier = Modifier.semantics { this.contentDescription = accion }
        ) {
            Text("Editar", color = Coo.AzulUbicacion, fontSize = 14.sp, fontWeight = FontWeight.Medium)
        }
    }
}

/** Abajo: el error (si lo hay) y el botón de publicar, que muestra cuando trabaja. */
@Composable
private fun HojaPublicar(
    esConductor: Boolean,
    publicando: Boolean,
    error: String?,
    /** El conductor aún no confirmó la vista previa del tramo visible. */
    habilitado: Boolean,
    /** Explicación visible de por qué el botón está deshabilitado. */
    queFalta: String?,
    sinBuscar: Boolean,
    alPublicar: () -> Unit,
    alAnterior: () -> Unit
) {
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
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (error != null) {
                Row(
                    // Que TalkBack lo lea apenas aparece.
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    IconoAlerta(color = Coo.RojoError, tamano = 18.dp)
                    Text(error, fontSize = 13.sp, color = Coo.RojoError)
                }
            }
            if (error == null && queFalta != null) {
                Text(queFalta, fontSize = 13.sp, color = Coo.TextoTenue,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
            }
            Box {
            Button(
                onClick = alPublicar,
                enabled = !publicando && habilitado,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 56.dp),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Coo.AzulBoton,
                    contentColor = Coo.SobreBoton,
                    disabledContainerColor = Coo.AzulBoton.copy(alpha = 0.6f),
                    disabledContentColor = Coo.SobreBoton
                )
            ) {
                if (publicando) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                        color = Coo.SobreBoton
                    )
                    Spacer(Modifier.width(12.dp))
                    Text(if (esConductor) "Publicando…" else "Guardando…", fontSize = 17.sp, fontWeight = FontWeight.Bold)
                } else {
                    Text(
                        text = when {
                            error != null -> "Intentar de nuevo"
                            sinBuscar -> "Guardar"
                            esConductor -> "Publicar"
                            else -> "Guardar trayecto"
                        },
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
            // ‹ a la izquierda, dentro del botón: vuelve al paso anterior.
            Box(
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .width(64.dp)
                    .height(56.dp)
                    .clip(RoundedCornerShape(topStart = 14.dp, bottomStart = 14.dp))
                    .clickable(enabled = !publicando, onClick = alAnterior)
                    .semantics { contentDescription = "Anterior" },
                contentAlignment = Alignment.Center
            ) {
                androidx.compose.foundation.Canvas(Modifier.size(18.dp)) {
                    val g = 3.dp.toPx()
                    val medio = androidx.compose.ui.geometry.Offset(size.width * 0.3f, size.height / 2)
                    drawLine(Coo.SobreBoton, androidx.compose.ui.geometry.Offset(size.width * 0.7f, size.height * 0.1f), medio, g, androidx.compose.ui.graphics.StrokeCap.Round)
                    drawLine(Coo.SobreBoton, medio, androidx.compose.ui.geometry.Offset(size.width * 0.7f, size.height * 0.9f), g, androidx.compose.ui.graphics.StrokeCap.Round)
                }
            }
            }
        }
    }
}

/** Pantalla de éxito: lo publicado, en una línea, y el botón para terminar. */
@Composable
private fun Exito(
    borrador: BorradorRuta,
    horario: HorarioRuta,
    esConductor: Boolean,
    alListo: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(modifier = modifier.fillMaxSize(), color = Coo.Fondo) {
        Column(
            modifier = Modifier
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(Modifier.weight(1f))
            Box(
                modifier = Modifier
                    .size(96.dp)
                    .background(Coo.AzulUbicacion.copy(alpha = 0.15f), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .size(68.dp)
                        .background(Coo.AzulUbicacion, CircleShape),
                    contentAlignment = Alignment.Center
                ) { IconoCheck(color = Coo.Texto, tamano = 40.dp) }
            }
            Spacer(Modifier.height(24.dp))
            Text(
                text = if (esConductor) "¡Ruta publicada!" else "¡Ruta guardada!",
                fontSize = 26.sp,
                fontWeight = FontWeight.Bold,
                color = Coo.Texto,
                modifier = Modifier.semantics { heading() }
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = if (esConductor) {
                    "Te avisaremos cuando un pasajero quiera subirse."
                } else {
                    "Te avisaremos cuando un conductor pase por tu camino."
                },
                fontSize = 15.sp,
                lineHeight = 21.sp,
                color = Coo.TextoTenue,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(20.dp))
            Surface(shape = RoundedCornerShape(12.dp), color = Coo.Campo) {
                Text(
                    text = "${borrador.destino.nombre} · ${resumenDias(horario.dias)} · ${formatoHora(horario.salida)}",
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                    fontSize = 14.sp,
                    color = Coo.Texto,
                    textAlign = TextAlign.Center
                )
            }
            Spacer(Modifier.weight(1f))
            Button(
                onClick = alListo,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 56.dp),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Coo.AzulBoton, contentColor = Coo.SobreBoton)
            ) {
                Text("Listo", fontSize = 17.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

// ------------------------------------------------------------------ ayudas

private fun llegadaDe(borrador: BorradorRuta, salida: LocalTime): LocalTime? =
    borrador.trayecto?.let { salida.plusSeconds(it.segundos.roundToInt().toLong()) }

/** "Eyner Sejje" → "ES". */
private fun iniciales(nombre: String): String =
    nombre.trim().split(Regex("\\s+"))
        .filter { it.isNotEmpty() }
        .take(2)
        .joinToString("") { it.first().uppercase() }
        .ifEmpty { "?" }
