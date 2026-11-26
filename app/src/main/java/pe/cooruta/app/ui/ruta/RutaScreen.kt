package pe.cooruta.app.ui.ruta

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.runtime.snapshotFlow
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import com.google.maps.android.compose.CameraPositionState
import com.google.maps.android.compose.GoogleMap
import com.google.maps.android.compose.MapEffect
import com.google.maps.android.compose.rememberCameraPositionState
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import pe.cooruta.app.ui.mapa.LineaMapa
import pe.cooruta.app.ui.mapa.MarcaMapa
import pe.cooruta.app.ui.mapa.UI_MAPA
import pe.cooruta.app.ui.mapa.circulo
import pe.cooruta.app.ui.mapa.latLng
import pe.cooruta.app.ui.mapa.propiedadesMapa
import pe.cooruta.app.BuildConfig
import pe.cooruta.app.data.BorradorRuta
import pe.cooruta.app.data.Lugar
import pe.cooruta.app.data.Punto
import pe.cooruta.app.data.Sugerencia
import pe.cooruta.app.data.Trayecto
import pe.cooruta.app.ui.componentes.IconoAtras
import pe.cooruta.app.ui.componentes.PasosCompactos
import pe.cooruta.app.ui.componentes.IconoBuscar
import pe.cooruta.app.ui.componentes.IconoCapas
import pe.cooruta.app.ui.componentes.IconoCerrar
import pe.cooruta.app.ui.componentes.IconoIntercambiar
import pe.cooruta.app.ui.componentes.IconoMas
import pe.cooruta.app.ui.componentes.IconoMenos
import pe.cooruta.app.ui.componentes.IconoMiUbicacion
import pe.cooruta.app.ui.theme.Coo

/** Cuántos grados abarca el recuadro de maqueta, para inventar coordenadas creíbles. */
private const val GRADOS_MAQUETA = 0.06

/**
 * Paso 1 del asistente: de dónde a dónde va la ruta.
 *
 * El mapa ocupa toda la pantalla. Encima flotan la tarjeta de direcciones y,
 * abajo, la hoja con los pasos y el botón. Mientras se elige, el destino es el
 * centro de la pantalla: el pin no se mueve, se mueve el mapa por debajo.
 *
 * Esta pantalla no tiene lógica: pinta el estado de [RutaViewModel] y le
 * avisa de cada toque. Lo único que guarda es lo que es del propio Android
 * (el permiso de ubicación) y del propio mapa (la cámara).
 */
@Composable
fun RutaScreen(
    cargando: Boolean,
    alSiguiente: (BorradorRuta) -> Unit,
    alVolver: () -> Unit,
    modifier: Modifier = Modifier,
    /** Al editar: origen y destino de la ruta guardada. */
    inicial: BorradorRuta? = null,
    claveInicial: String? = null,
    vm: RutaViewModel = viewModel(factory = RutaViewModel.Fabrica)
) {
    LaunchedEffect(claveInicial) { if (claveInicial != null && inicial != null) vm.precargar(claveInicial, inicial.origen, inicial.destino) }
    val contexto = LocalContext.current
    val estado by vm.estado.collectAsStateWithLifecycle()

    var permisoUbicacion by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                contexto,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
        )
    }

    // Permiso y, si el GPS está apagado, la ventana de Android para activarlo.
    val pedidoUbicacion = pe.cooruta.app.ui.componentes.rememberPedidoUbicacion()
    fun solicitarUbicacion() = pedidoUbicacion.pedir {
        permisoUbicacion = ContextCompat.checkSelfPermission(contexto, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(contexto, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
    }

    LaunchedEffect(Unit) {
        if (!permisoUbicacion || !pe.cooruta.app.ui.componentes.ubicacionActivada(contexto)) solicitarUbicacion()
    }

    val vista = rememberCameraPositionState {
        position = CameraPosition.fromLatLngZoom(LatLng(AREQUIPA.latitud, AREQUIPA.longitud), 13f)
    }

    // El ViewModel decide a dónde mirar; la pantalla mueve la cámara.
    LaunchedEffect(vm) {
        vm.camara.collect { movimiento ->
            when (movimiento) {
                is MovimientoCamara.Encuadrar -> {
                    val (centroRuta, acercamiento) = encuadre(movimiento.a, movimiento.b)
                    vista.move(CameraUpdateFactory.newLatLngZoom(centroRuta, acercamiento.toFloat()))
                }
                // Un centrado y ya: transitionToFollowPuckState dejaba la
                // cámara "siguiendo" al puck y rotando con el rumbo.
                is MovimientoCamara.Centrar -> vista.move(
                    CameraUpdateFactory.newLatLngZoom(movimiento.punto.latLng(), movimiento.zoom.toFloat()))
            }
        }
    }

    // Solo la posición nos interesa: acercar o alejar no cambia qué hay en el
    // centro, así que no se avisa por cada toque del zoom.
    // Solo cuando la cámara se detiene: así no se pide la dirección por cada cuadro del gesto.
    LaunchedEffect(vista, estado.eligiendo) {
        snapshotFlow { if (vista.isMoving) null else vista.position.target.let { alMetro(it.latitude) to alMetro(it.longitude) } }
            .filterNotNull().distinctUntilChanged()
            .collect { (latitud, longitud) -> vm.alMoverMapa(latitud, longitud) }
    }

    Surface(modifier = modifier.fillMaxSize(), color = Coo.Fondo) {
        Box(Modifier.fillMaxSize()) {

            if (BuildConfig.HAY_MAPA) {
                MapaReal(
                    vista = vista,
                    permisoUbicacion = permisoUbicacion,
                    satelital = estado.satelital,
                    trayecto = estado.trayecto,
                    alternativas = estado.alternativas,
                    alElegirAlternativa = vm::elegirAlternativa,
                    destinoFijo = if (estado.eligiendo) null else estado.destino,
                    alSaberUbicacion = vm::alSaberUbicacion
                )
                if (estado.eligiendo) {
                    PinDestino(
                        modifier = Modifier
                            .align(Alignment.Center)
                            .offset(y = (-24).dp)
                    )
                }
            } else {
                MapaMaqueta(
                    destino = estado.destino,
                    alTocar = vm::fijarDestino
                )
            }

            // ---------- Arriba: encabezado (como Horario y Confirmar) y la tarjeta ----------
            Column(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Coo.Fondo)
                        .statusBarsPadding()
                        .padding(bottom = 12.dp)
                ) {
                    Box(
                        modifier = Modifier.fillMaxWidth().height(64.dp).padding(start = 4.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .align(Alignment.CenterStart)
                                .size(48.dp)
                                .clip(CircleShape)
                                .clickable(onClick = alVolver)
                                .semantics { contentDescription = "Volver"; role = Role.Button },
                            contentAlignment = Alignment.Center
                        ) { IconoAtras() }
                        Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("Ruta", fontSize = 22.sp, fontWeight = FontWeight.Medium, color = Coo.Texto)
                            Text("Elige tu ruta", fontSize = 13.sp, color = Coo.TextoTenue)
                        }
                    }
                    PasosCompactos(pasoActual = 1, modifier = Modifier.padding(horizontal = 24.dp))
                }
                TarjetaRuta(
                    textoOrigen = estado.origen?.nombre ?: "Mi ubicación actual",
                    textoDestino = estado.destino?.nombre ?: "¿A dónde vas?",
                    destinoElegido = estado.destino != null,
                    alTocarCampo = vm::abrirBusqueda,
                    alIntercambiar = vm::intercambiar,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
                )
            }

            // ---------- Abajo: botones del mapa y la hoja ----------
            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
            ) {
                if (BuildConfig.HAY_MAPA) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.Bottom
                    ) {
                        if (!estado.eligiendo) {
                            BotonTexto("Mover el pin") { vm.soltarPin() }
                        } else {
                            Spacer(Modifier.width(1.dp))
                        }

                        Column(horizontalAlignment = Alignment.End) {
                            BotonCircular(
                                descripcion = if (estado.satelital) "Ver el mapa normal" else "Ver vista satelital",
                                alTocar = vm::alternarSatelite
                            ) { IconoCapas(color = if (estado.satelital) Coo.AzulUbicacion else Coo.Texto) }

                            Spacer(Modifier.height(10.dp))

                            BotonCircular(
                                descripcion = "Acercar el mapa",
                                alTocar = { vista.cambiarZoom(PASO_ZOOM) }
                            ) { IconoMas() }

                            Spacer(Modifier.height(10.dp))

                            BotonCircular(
                                descripcion = "Alejar el mapa",
                                alTocar = { vista.cambiarZoom(-PASO_ZOOM) }
                            ) { IconoMenos() }

                            Spacer(Modifier.height(10.dp))

                            BotonCircular(
                                descripcion = "Centrar el mapa en mi ubicación",
                                alTocar = {
                                    if (permisoUbicacion && estado.miUbicacion != null) {
                                        vm.centrarEnMiUbicacion()
                                    } else {
                                        solicitarUbicacion()
                                    }
                                }
                            ) { IconoMiUbicacion() }
                        }
                    }
                }

                HojaInferior(
                    trayecto = estado.trayecto,
                    alternativas = estado.alternativas,
                    alElegirAlternativa = vm::elegirAlternativa,
                    calculando = estado.calculandoTrayecto,
                    aviso = estado.avisoTrayecto,
                    eligiendo = estado.eligiendo,
                    habilitado = estado.destino != null && !cargando,
                    alConfirmar = { vm.fijarDestino() },
                    alSiguiente = {
                        estado.destino?.let { destino ->
                            // Si sale de "mi ubicación", va con su nombre de calle.
                            val origen = estado.origen ?: estado.miUbicacionConNombre
                            alSiguiente(BorradorRuta(origen, destino, estado.trayecto))
                        }
                    }
                )
            }

            // ---------- Encima de todo: la búsqueda ----------
            estado.campoAbierto?.let { campo ->
                PanelBusqueda(
                    titulo = if (campo == CampoRuta.ORIGEN) "¿De dónde sales?" else "¿A dónde vas?",
                    busqueda = estado.busqueda,
                    recientes = estado.recientes,
                    alEscribir = vm::escribirBusqueda,
                    alElegir = vm::elegirSugerencia,
                    alElegirReciente = vm::elegirReciente,
                    alCerrar = vm::cerrarBusqueda,
                    alUsarMiUbicacion = if (campo == CampoRuta.ORIGEN) vm::usarMiUbicacion else null
                )
            }
        }
    }
}

// ---------------------------------------------------------------- mapas

/** El mapa de verdad, a pantalla completa. */
@OptIn(com.google.maps.android.compose.MapsComposeExperimentalApi::class)
@Composable
private fun MapaReal(
    vista: CameraPositionState,
    permisoUbicacion: Boolean,
    satelital: Boolean,
    trayecto: Trayecto?,
    alternativas: List<Trayecto>,
    alElegirAlternativa: (Trayecto) -> Unit,
    destinoFijo: Lugar?,
    alSaberUbicacion: (Punto) -> Unit,
    modifier: Modifier = Modifier
) {
    val densidad = androidx.compose.ui.platform.LocalDensity.current.density
    GoogleMap(
        modifier = modifier.fillMaxSize(),
        cameraPositionState = vista,
        // Oscuro acorde a la app; la versión satelital trae calles y nombres encima.
        properties = propiedadesMapa(satelital, miUbicacion = permisoUbicacion),
        uiSettings = UI_MAPA
    ) {
        if (permisoUbicacion) {
            // El punto azul nos va contando dónde está la persona: el botón de "mi ubicación"
            // necesita la posición de ahora. El origen se redondea antes de pedir el recorrido.
            @Suppress("DEPRECATION")
            MapEffect(permisoUbicacion) { mapa ->
                mapa.setOnMyLocationChangeListener { l -> alSaberUbicacion(Punto(l.latitude, l.longitude)) }
            }
        }

        // Los extremos de verdad se añaden para que la línea toque el punto azul y el pin.
        // Caminos posibles en gris (se tocan para elegirlos); el elegido en azul, encima.
        alternativas.filter { it != trayecto }.forEach { alt ->
            val pts = remember(alt) { alt.conExtremos().map { it.latLng() } }
            com.google.maps.android.compose.Polyline(points = pts, color = Coo.Neutro, width = 5f * densidad,
                jointType = com.google.android.gms.maps.model.JointType.ROUND, clickable = true, zIndex = 0f,
                onClick = { alElegirAlternativa(alt) })
        }
        trayecto?.let { recorrido ->
            val linea = remember(recorrido) { recorrido.conExtremos() }
            // Encima de los caminos grises (zIndex 0): donde se cruzan, se ve el azul completo.
            LineaMapa(linea, Coo.AzulUbicacion, 6f, z = 1f)
        }

        // Etiqueta con el tiempo sobre cada camino; tocarla lo elige. Cada una en otro tramo para que no se tapen.
        if (alternativas.size > 1) alternativas.forEachIndexed { i, alt ->
            val elegida = alt == trayecto
            // En el punto de este camino más alejado de los otros: así se ve de cuál es la etiqueta.
            val pos = remember(alt, alternativas) {
                val otros = alternativas.filter { it != alt }.flatMap { o -> o.puntos.filterIndexed { k, _ -> k % 3 == 0 } }
                val candidatos = alt.puntos.filterIndexed { k, _ -> k % 2 == 0 }.ifEmpty { alt.puntos }
                candidatos.maxByOrNull { p ->
                    otros.minOfOrNull { o ->
                        val a = p.latitud - o.latitud; val b = p.longitud - o.longitud; a * a + b * b
                    } ?: 0.0
                }!!.latLng()
            }
            com.google.maps.android.compose.MarkerComposable(
                elegida, alt.duracionLegible(),
                state = remember(pos) { com.google.maps.android.compose.MarkerState(pos) },
                anchor = androidx.compose.ui.geometry.Offset(0.5f, 1f),
                zIndex = if (elegida) 3f else 2f,
                onClick = { alElegirAlternativa(alt); true }
            ) {
                Text(
                    alt.duracionLegible(),
                    color = if (elegida) Coo.SobreBoton else Coo.Texto,
                    fontSize = 14.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .background(if (elegida) Coo.AzulBoton else Coo.CampoActivo, RoundedCornerShape(8.dp))
                        .border(1.dp, if (elegida) Coo.AzulBoton else Coo.Neutro, RoundedCornerShape(8.dp))
                        .padding(horizontal = 10.dp, vertical = 5.dp)
                )
            }
        }

        // Con el destino confirmado, el marcador vive en el mapa: se queda aunque muevas la cámara.
        destinoFijo?.let { lugar ->
            val icono = remember(densidad) { circulo(Coo.RojoDestino, densidad, bordeDp = 3f) }
            MarcaMapa(Punto(lugar.latitud, lugar.longitud), icono)
        }
    }
}

/**
 * Recuadro de maqueta: se dibuja solo, sin token ni red, para poder seguir
 * armando el asistente mientras no haya mapa.
 */
@Composable
private fun MapaMaqueta(
    destino: Lugar?,
    alTocar: (Lugar) -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Coo.Campo)
            .pointerInput(Unit) {
                detectTapGestures { toque ->
                    val fx = toque.x / size.width
                    val fy = toque.y / size.height
                    val lat = AREQUIPA.latitud + (0.5 - fy) * GRADOS_MAQUETA
                    val lng = AREQUIPA.longitud + (fx - 0.5) * GRADOS_MAQUETA
                    alTocar(Lugar("Punto en el mapa", coordenadas(lat, lng), lat, lng))
                }
            }
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val grosorCalle = 1.dp.toPx()
            val colorCalle = Coo.Borde.copy(alpha = 0.55f)

            val pasoY = size.height / 9f
            for (i in 1..8) {
                drawLine(colorCalle, Offset(0f, pasoY * i), Offset(size.width, pasoY * i), grosorCalle)
            }
            val pasoX = size.width / 5f
            for (i in 1..4) {
                drawLine(colorCalle, Offset(pasoX * i, 0f), Offset(pasoX * i, size.height), grosorCalle)
            }

            val inicio = Offset(size.width * 0.22f, size.height * 0.72f)
            val fin = destino?.let { punto ->
                Offset(
                    (((punto.longitud - AREQUIPA.longitud) / GRADOS_MAQUETA + 0.5) * size.width).toFloat(),
                    ((0.5 - (punto.latitud - AREQUIPA.latitud) / GRADOS_MAQUETA) * size.height).toFloat()
                )
            } ?: Offset(size.width * 0.72f, size.height * 0.40f)

            val codo = Offset(fin.x, inicio.y)
            val colorRuta = Coo.AzulUbicacion.copy(alpha = if (destino == null) 0.30f else 0.9f)
            drawLine(colorRuta, inicio, codo, 5.dp.toPx(), StrokeCap.Round)
            drawLine(colorRuta, codo, fin, 5.dp.toPx(), StrokeCap.Round)

            drawCircle(Coo.AzulUbicacion.copy(alpha = 0.22f), 15.dp.toPx(), inicio)
            drawCircle(Color.White, 8.dp.toPx(), inicio)
            drawCircle(Coo.AzulUbicacion, 6.dp.toPx(), inicio)

            if (destino != null) {
                drawCircle(Color.White, 10.dp.toPx(), fin)
                drawCircle(Coo.RojoDestino, 8.dp.toPx(), fin)
                drawCircle(Color.White, 3.dp.toPx(), fin)
            }
        }
    }
}

// ---------------------------------------------------------------- piezas

/**
 * La tarjeta flotante con las dos direcciones. Los campos no se escriben aquí:
 * tocarlos abre la búsqueda a pantalla completa, que es donde hay sitio para
 * el teclado y la lista de resultados.
 */
@Composable
private fun TarjetaRuta(
    textoOrigen: String,
    textoDestino: String,
    destinoElegido: Boolean,
    alTocarCampo: (CampoRuta) -> Unit,
    alIntercambiar: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = Coo.Campo,
        border = BorderStroke(1.dp, Coo.Borde),
        shadowElevation = 8.dp
    ) {
        // IntrinsicSize.Min deja que las dos filas de texto manden en el alto y
        // el conector se estire a lo que salga. Con alto fijo, en un teléfono
        // con la letra del sistema agrandada las direcciones se cortaban.
        Row(
            modifier = Modifier
                .padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp)
                .height(IntrinsicSize.Min),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Conector(
                Modifier
                    .width(16.dp)
                    .fillMaxHeight()
            )

            Spacer(Modifier.width(12.dp))

            Column(Modifier.weight(1f)) {
                CampoDireccion(
                    etiqueta = "Desde",
                    valor = textoOrigen,
                    tenue = false,
                    alTocar = { alTocarCampo(CampoRuta.ORIGEN) }
                )
                HorizontalDivider(color = Coo.Borde)
                CampoDireccion(
                    etiqueta = "Hasta",
                    valor = textoDestino,
                    tenue = !destinoElegido,
                    alTocar = { alTocarCampo(CampoRuta.DESTINO) }
                )
            }

            BotonCircular(
                descripcion = "Intercambiar origen y destino",
                fondo = Color.Transparent,
                borde = false,
                alTocar = alIntercambiar
            ) { IconoIntercambiar(color = Coo.TextoTenue) }
        }
    }
}

/** El punto azul, la línea de puntos y el punto rojo, a la izquierda de la tarjeta. */
@Composable
private fun Conector(modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val x = size.width / 2f
        val arriba = size.height * 0.25f
        val abajo = size.height * 0.75f

        drawCircle(Color.White, 6.dp.toPx(), Offset(x, arriba))
        drawCircle(Coo.AzulUbicacion, 4.5.dp.toPx(), Offset(x, arriba))

        var y = arriba + 14.dp.toPx()
        while (y < abajo - 12.dp.toPx()) {
            drawCircle(Coo.Borde, 1.5.dp.toPx(), Offset(x, y))
            y += 7.dp.toPx()
        }

        drawCircle(Color.White, 6.dp.toPx(), Offset(x, abajo))
        drawCircle(Coo.RojoDestino, 4.5.dp.toPx(), Offset(x, abajo))
    }
}

/** Una de las dos filas de la tarjeta. Se toca entera, no solo el texto. */
@Composable
private fun CampoDireccion(
    etiqueta: String,
    valor: String,
    tenue: Boolean,
    alTocar: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clickable(onClick = alTocar)
            .semantics {
                contentDescription = "$etiqueta: $valor. Toca para cambiarlo"
                role = Role.Button
            }
            .padding(vertical = 8.dp),
        verticalArrangement = Arrangement.Center
    ) {
        Text(etiqueta, color = Coo.TextoTenue, fontSize = 12.sp)
        Text(
            text = valor,
            color = if (tenue) Coo.TextoTenue else Coo.Texto,
            fontSize = 16.sp,
            lineHeight = 22.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/** La hoja de abajo: en qué paso vamos y cómo seguir. */
@Composable
private fun HojaInferior(
    trayecto: Trayecto?,
    alternativas: List<Trayecto>,
    alElegirAlternativa: (Trayecto) -> Unit,
    calculando: Boolean,
    aviso: String?,
    eligiendo: Boolean,
    habilitado: Boolean,
    alConfirmar: () -> Unit,
    alSiguiente: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
        color = Coo.Campo,
        shadowElevation = 16.dp
    ) {
        Column(
            modifier = Modifier
                .navigationBarsPadding()
                .padding(horizontal = 24.dp, vertical = 20.dp)
        ) {

            // Si algo tarda o falla, la hoja lo dice: nunca se queda muda.
            if (trayecto == null && (calculando || aviso != null)) {
                Spacer(Modifier.height(18.dp))
                Text(
                    text = if (calculando) "Calculando el recorrido…" else aviso.orEmpty(),
                    color = if (calculando) Coo.TextoTenue else Coo.RojoError,
                    fontSize = 14.sp
                )
            }

            if (trayecto != null) {
                val i = alternativas.indexOf(trayecto).coerceAtLeast(0)
                if (alternativas.size > 1) {
                    Text("Toca una ruta en el mapa", color = Coo.Texto, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(14.dp))
                }
                // La ruta elegida; tocar la tarjeta pasa a la siguiente.
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(Coo.CampoActivo)
                        .border(1.dp, Coo.Borde, RoundedCornerShape(14.dp))
                        .clickable(enabled = alternativas.size > 1) {
                            alElegirAlternativa(alternativas[(i + 1) % alternativas.size])
                        }
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Canvas(Modifier.size(28.dp)) {
                        val g = 3.dp.toPx()
                        val w = size.width; val h = size.height
                        val camino = Path().apply {
                            moveTo(w * 0.12f, h * 0.88f)
                            cubicTo(w * 0.2f, h * 0.45f, w * 0.55f, h * 0.75f, w * 0.6f, h * 0.45f)
                            cubicTo(w * 0.65f, h * 0.2f, w * 0.8f, h * 0.2f, w * 0.88f, h * 0.12f)
                        }
                        drawPath(camino, Coo.AzulUbicacion, style = androidx.compose.ui.graphics.drawscope.Stroke(g, cap = StrokeCap.Round))
                        drawCircle(Coo.AzulUbicacion, 3.5.dp.toPx(), Offset(w * 0.12f, h * 0.88f))
                        drawCircle(Coo.AzulUbicacion, 3.5.dp.toPx(), Offset(w * 0.88f, h * 0.12f))
                    }
                    Spacer(Modifier.width(16.dp))
                    Column(Modifier.weight(1f)) {
                        Text(if (i == 0) "Recomendada" else "Ruta ${i + 1}", color = Coo.Texto, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                        Text("${trayecto.duracionLegible()}  ·  ${trayecto.distanciaLegible()}", color = Coo.TextoTenue, fontSize = 14.sp)
                    }
                    if (alternativas.size > 1) Canvas(Modifier.size(14.dp)) {
                        val g = 2.dp.toPx()
                        val m = Offset(size.width * 0.7f, size.height / 2)
                        drawLine(Coo.Texto, Offset(size.width * 0.3f, size.height * 0.1f), m, g, StrokeCap.Round)
                        drawLine(Coo.Texto, m, Offset(size.width * 0.3f, size.height * 0.9f), g, StrokeCap.Round)
                    }
                }
            }

            Spacer(Modifier.height(20.dp))

            Button(
                onClick = if (eligiendo) alConfirmar else alSiguiente,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                enabled = habilitado,
                shape = RoundedCornerShape(14.dp)
            ) {
                Box(Modifier.fillMaxWidth()) {
                    Text(
                        text = if (eligiendo) "Confirmar destino" else "Siguiente",
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.align(Alignment.Center)
                    )
                    // › en el borde derecho, como en Horario.
                    if (!eligiendo) {
                        val tinta = androidx.compose.material3.LocalContentColor.current
                        Canvas(Modifier.align(Alignment.CenterEnd).padding(end = 6.dp).size(18.dp)) {
                            val g = 3.dp.toPx()
                            val m = Offset(size.width * 0.7f, size.height / 2)
                            drawLine(tinta, Offset(size.width * 0.3f, size.height * 0.1f), m, g, StrokeCap.Round)
                            drawLine(tinta, m, Offset(size.width * 0.3f, size.height * 0.9f), g, StrokeCap.Round)
                        }
                    }
                }
            }
        }
    }
}

/** La búsqueda de direcciones, a pantalla completa. */
@Composable
private fun PanelBusqueda(
    titulo: String,
    busqueda: EstadoBusqueda,
    recientes: List<Lugar>,
    alEscribir: (String) -> Unit,
    alElegir: (Sugerencia) -> Unit,
    alElegirReciente: (Lugar) -> Unit,
    alCerrar: () -> Unit,
    modifier: Modifier = Modifier,
    alUsarMiUbicacion: (() -> Unit)? = null
) {
    val foco = remember { FocusRequester() }
    LaunchedEffect(Unit) { foco.requestFocus() }

    // Atrás cierra la búsqueda en vez de salir de la pantalla.
    BackHandler(onBack = alCerrar)

    Surface(modifier = modifier.fillMaxSize(), color = Coo.Fondo) {
        Column(
            Modifier
                .statusBarsPadding()
                .imePadding()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                BotonCircular(
                    descripcion = "Cerrar la búsqueda",
                    fondo = Color.Transparent,
                    borde = false,
                    alTocar = alCerrar
                ) { IconoAtras() }

                Spacer(Modifier.width(4.dp))

                TextField(
                    value = busqueda.texto,
                    onValueChange = alEscribir,
                    modifier = Modifier
                        .weight(1f)
                        .focusRequester(foco),
                    placeholder = { Text(titulo, color = Coo.TextoTenue) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    leadingIcon = { IconoBuscar(color = Coo.TextoTenue, tamano = 20.dp) },
                    trailingIcon = {
                        if (busqueda.texto.isNotEmpty()) {
                            BotonCircular(
                                descripcion = "Borrar el texto",
                                fondo = Color.Transparent,
                                borde = false,
                                alTocar = { alEscribir("") }
                            ) { IconoCerrar(color = Coo.TextoTenue, tamano = 18.dp) }
                        }
                    },
                    shape = RoundedCornerShape(14.dp),
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = Coo.CampoActivo,
                        unfocusedContainerColor = Coo.Campo,
                        focusedTextColor = Coo.Texto,
                        unfocusedTextColor = Coo.Texto,
                        cursorColor = Coo.AzulUbicacion,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent
                    )
                )
            }

            // Un error con resultados en pantalla (falló abrir uno): se avisa
            // arriba sin esconder la lista, para poder tocar otro.
            if (busqueda.error != null && busqueda.resultados.isNotEmpty()) {
                AvisoError(busqueda.error)
            }

            // Siempre a mano: volver a salir desde donde estoy.
            if (alUsarMiUbicacion != null && !busqueda.resolviendo && busqueda.texto.trim().length < LETRAS_MINIMAS) {
                FilaResultado(
                    sugerencia = Sugerencia(id = "mi_ubicacion", nombre = "📍 Mi ubicación actual",
                        detalle = "Salir desde donde estás ahora", categoria = null),
                    alTocar = alUsarMiUbicacion
                )
            }
            when {
                busqueda.resolviendo -> Aviso("Un momento…")

                busqueda.buscando -> Aviso("Buscando…")

                busqueda.texto.trim().length < LETRAS_MINIMAS -> if (recientes.isEmpty()) {
                    Aviso("Escribe al menos $LETRAS_MINIMAS letras. Puede ser una dirección, un instituto o un sitio conocido.")
                } else {
                    // Los recientes vienen de Room: aparecen aunque no haya internet.
                    LazyColumn(Modifier.fillMaxSize()) {
                        item {
                            Text(
                                text = "RECIENTES",
                                color = Coo.TextoTenue,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                letterSpacing = 1.sp,
                                modifier = Modifier.padding(horizontal = 24.dp, vertical = 10.dp)
                            )
                        }
                        items(recientes, key = { "${it.latitud},${it.longitud}" }) { lugar ->
                            FilaResultado(
                                sugerencia = Sugerencia(
                                    id = "${lugar.latitud},${lugar.longitud}",
                                    nombre = lugar.nombre,
                                    detalle = lugar.detalle,
                                    categoria = null
                                ),
                                alTocar = { alElegirReciente(lugar) }
                            )
                        }
                    }
                }

                busqueda.error != null -> AvisoError(busqueda.error)

                busqueda.resultados.isEmpty() ->
                    Aviso("No encontramos nada con \"${busqueda.texto.trim()}\".\nPrueba con el nombre de la calle o de un lugar conocido cerca.")

                else -> LazyColumn(Modifier.fillMaxSize()) {
                    items(busqueda.resultados, key = { it.id }) { sugerencia ->
                        FilaResultado(
                            sugerencia = sugerencia,
                            alTocar = { alElegir(sugerencia) }
                        )
                    }
                }
            }
        }
    }
}

/** Un resultado de la búsqueda. */
@Composable
private fun FilaResultado(
    sugerencia: Sugerencia,
    modifier: Modifier = Modifier,
    alTocar: () -> Unit
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = alTocar)
            .padding(horizontal = 24.dp, vertical = 14.dp)
    ) {
        Text(
            text = sugerencia.nombre,
            color = Coo.Texto,
            fontSize = 16.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        val secundario = listOfNotNull(
            sugerencia.categoria?.let { categoriaEnEspanol(it) },
            sugerencia.detalle.takeIf { it.isNotBlank() }
        ).joinToString(" · ")
        if (secundario.isNotBlank()) {
            Text(
                text = secundario,
                color = Coo.TextoTenue,
                fontSize = 13.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/** Las categorías de Google vienen en inglés; traducimos las que más salen. */
private fun categoriaEnEspanol(categoria: String): String = when (categoria) {
    "college", "university" -> "Universidad"
    "school" -> "Colegio"
    "education" -> "Educación"
    "hospital", "clinic" -> "Salud"
    "restaurant", "food", "cafe" -> "Comida"
    "shopping_mall", "shopping" -> "Centro comercial"
    "bus_station", "bus_stop" -> "Paradero"
    "park" -> "Parque"
    "bank" -> "Banco"
    "hotel" -> "Hotel"
    "office" -> "Oficina"
    else -> categoria.replace('_', ' ').replaceFirstChar { it.uppercase() }
}

/** Un mensaje centrado: buscando, sin resultados, o la pista inicial. */
@Composable
private fun Aviso(texto: String, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 32.dp, vertical = 40.dp),
        contentAlignment = Alignment.TopCenter
    ) {
        Text(texto, color = Coo.TextoTenue, fontSize = 14.sp)
    }
}

/** Un error de la búsqueda, en rojo para que no pase desapercibido. */
@Composable
private fun AvisoError(texto: String, modifier: Modifier = Modifier) {
    Text(
        text = texto,
        color = Coo.RojoError,
        fontSize = 14.sp,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 12.dp)
    )
}

/** Botón redondo de 48dp: el mínimo que pide Android para un objetivo táctil. */
@Composable
private fun BotonCircular(
    descripcion: String,
    alTocar: () -> Unit,
    modifier: Modifier = Modifier,
    fondo: Color = Coo.Campo,
    borde: Boolean = true,
    contenido: @Composable () -> Unit
) {
    Surface(
        modifier = modifier.size(48.dp),
        shape = CircleShape,
        color = fondo,
        border = if (borde) BorderStroke(1.dp, Coo.Borde) else null,
        shadowElevation = if (borde) 6.dp else 0.dp
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clickable(onClick = alTocar)
                .semantics {
                    contentDescription = descripcion
                    role = Role.Button
                },
            contentAlignment = Alignment.Center
        ) { contenido() }
    }
}

/**
 * Sube o baja el zoom sin mover el centro.
 *
 * Mapbox admite de 0 a 22; por debajo de 3 se ve el planeta entero y por
 * encima de 20 ya no hay más detalle que mostrar.
 */
private fun CameraPositionState.cambiarZoom(paso: Double) {
    val actual = position.zoom
    move(CameraUpdateFactory.zoomTo((actual + paso.toFloat()).coerceIn(3f, 20f)))
}

/** Botón con etiqueta sobre el mapa. No es un icono suelto: dice lo que hace. */
@Composable
private fun BotonTexto(texto: String, modifier: Modifier = Modifier, alTocar: () -> Unit) {
    Surface(
        modifier = modifier.heightIn(min = 48.dp),
        shape = RoundedCornerShape(24.dp),
        color = Coo.Campo,
        border = BorderStroke(1.dp, Coo.Borde),
        shadowElevation = 6.dp
    ) {
        Box(
            modifier = Modifier
                .clickable(onClick = alTocar)
                .padding(horizontal = 20.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(texto, color = Coo.Texto, fontSize = 14.sp)
        }
    }
}

/** El globito rojo del destino, clavado al centro del mapa mientras se elige. */
@Composable
private fun PinDestino(modifier: Modifier = Modifier) {
    Canvas(modifier = modifier.size(width = 34.dp, height = 48.dp)) {
        val centroX = size.width / 2f
        val radio = 13.dp.toPx()
        val centroY = radio + 2.dp.toPx()

        drawOval(
            color = Color.Black.copy(alpha = 0.35f),
            topLeft = Offset(centroX - 7.dp.toPx(), size.height - 6.dp.toPx()),
            size = androidx.compose.ui.geometry.Size(14.dp.toPx(), 5.dp.toPx())
        )

        val punta = Path().apply {
            moveTo(centroX, size.height - 3.dp.toPx())
            lineTo(centroX - radio * 0.62f, centroY + radio * 0.55f)
            lineTo(centroX + radio * 0.62f, centroY + radio * 0.55f)
            close()
        }
        drawPath(punta, Coo.RojoDestino)

        drawCircle(Color.White, radio, Offset(centroX, centroY))
        drawCircle(Coo.RojoDestino, radio - 2.5.dp.toPx(), Offset(centroX, centroY))
        drawCircle(Color.White, 4.dp.toPx(), Offset(centroX, centroY))
    }
}

/** Los tres pasos del asistente, con el actual resaltado. */
@Composable
private fun PasosAsistente(pasoActual: Int, modifier: Modifier = Modifier) {
    val pasos = listOf("Ruta", "Horario", "Confirmar")

    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top
    ) {
        pasos.forEachIndexed { indice, etiqueta ->
            val numero = indice + 1
            val activo = numero <= pasoActual

            Column(
                modifier = Modifier.width(56.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Surface(
                    modifier = Modifier.size(34.dp),
                    shape = CircleShape,
                    color = if (activo) Coo.AzulBoton else Coo.Fondo,
                    border = BorderStroke(1.dp, if (activo) Coo.AzulBoton else Coo.Borde)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(
                            text = numero.toString(),
                            color = if (activo) Coo.SobreBoton else Coo.TextoTenue,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    text = etiqueta,
                    color = if (activo) Coo.AzulUbicacion else Coo.TextoTenue,
                    fontSize = 12.sp
                )
            }

            if (indice < pasos.lastIndex) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .padding(top = 17.dp)
                        .height(2.dp)
                        .background(if (numero < pasoActual) Coo.AzulBoton else Coo.Borde)
                )
            }
        }
    }
}

// ---------------------------------------------------------------- ayudas

/**
 * Centro y acercamiento que dejan los dos extremos a la vista.
 *
 * Mapbox tiene su propio cálculo de encuadre, pero pasa por el MapView y aquí
 * solo tenemos el estado de la cámara. Con dos puntos la cuenta es corta: a
 * zoom Z la pantalla abarca 360/2^Z grados de longitud, así que se despeja Z
 * y se le resta un poco para dejar margen.
 */
private fun encuadre(a: Punto, b: Punto): Pair<LatLng, Double> {
    val centro = LatLng((a.latitud + b.latitud) / 2, (a.longitud + b.longitud) / 2)

    val altoGrados = kotlin.math.abs(a.latitud - b.latitud)
    // Un grado de longitud mide menos cuanto más lejos del ecuador.
    val anchoGrados = kotlin.math.abs(a.longitud - b.longitud) *
        kotlin.math.cos(Math.toRadians(centro.latitude))
    val mayor = maxOf(altoGrados, anchoGrados).coerceAtLeast(0.0008)

    val acercamiento = kotlin.math.ln(360.0 / mayor) / kotlin.math.ln(2.0) - 1.4
    return centro to acercamiento.coerceIn(3.0, 17.0)
}

/** Recorta una coordenada al metro. Sirve para no repetir la geocodificación
 * inversa cuando solo cambió el zoom y el centro sigue siendo el mismo. */
private fun alMetro(grados: Double): Double = Math.round(grados * 100_000.0) / 100_000.0

/** Cuánto sube o baja el zoom en cada toque. */
private const val PASO_ZOOM = 1.0

