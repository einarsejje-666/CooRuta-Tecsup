package pe.cooruta.app.ui.viaje

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.rounded.DirectionsCar
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Group
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.foundation.layout.fillMaxHeight
import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.rememberUpdatedState
import android.annotation.SuppressLint
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Looper
import pe.cooruta.app.data.Muestra
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import pe.cooruta.app.data.PlanificadorRecorrido
import androidx.compose.runtime.rememberCoroutineScope
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.model.CameraPosition
import com.google.maps.android.compose.CameraMoveStartedReason
import com.google.maps.android.compose.GoogleMap
import com.google.maps.android.compose.rememberCameraPositionState
import kotlinx.coroutines.launch
import pe.cooruta.app.ui.mapa.LineaMapa
import pe.cooruta.app.ui.mapa.MarcaMapa
import pe.cooruta.app.ui.mapa.UI_MAPA
import pe.cooruta.app.ui.mapa.latLng
import pe.cooruta.app.ui.mapa.propiedadesMapa
import kotlinx.coroutines.delay
import pe.cooruta.app.BuildConfig
import pe.cooruta.app.data.EstadoParada
import pe.cooruta.app.data.EstadoViaje
import pe.cooruta.app.data.PoliticaUbicacion
import pe.cooruta.app.data.Punto
import pe.cooruta.app.data.Seguimiento
import pe.cooruta.app.data.repositorio.RutasRepositorio.Solicitud
import pe.cooruta.app.ui.horario.formatoHora
import pe.cooruta.app.ui.theme.Coo
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val VERDE = Coo.Exito
private val AMBAR = Coo.Advertencia
/** Cartel superior al estilo de las apps de navegación. */
private val CARTEL = Coo.Cartel

/** Pestaña central «Iniciar»: el conductor inicia su salida; el pasajero observa la de su reserva. */
@Composable
fun ViajePestana(
    esConductor: Boolean,
    alAvisar: (String) -> Unit,
    modifier: Modifier = Modifier,
    vm: ViajeViewModel = viewModel(factory = ViajeViewModel.Fabrica)
) {
    val e by vm.estado.collectAsStateWithLifecycle()
    LaunchedEffect(esConductor) { vm.cargar(esConductor) }
    // Salidas ya finalizadas hoy: abajo y plegadas para que no estorben (mañana vuelven a estar listas).
    var verFinalizadas by rememberSaveable { mutableStateOf(false) }
    val abierto = e.abierto
    if (abierto != null) {
        BackHandler { vm.cerrar() }
        PantallaViaje(e, abierto, vm, alAvisar, modifier)
        return
    }
    LazyColumn(
        modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            // El título solo cuando hay algo que elegir; vacío se ve centrado y limpio.
            val hayAlgo = if (esConductor) e.rutasHoy.isNotEmpty() else e.confirmadas.isNotEmpty()
            if (hayAlgo) Text(if (esConductor) "Elige una ruta para comenzar" else "Elige un viaje para seguirlo",
                color = Coo.TextoTenue, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            e.errorDatos?.let { Text(it, color = Coo.RojoError, fontSize = 13.sp) }
        }
        if (esConductor) {
            if (e.rutasHoy.isEmpty()) item { Vacio("No tienes rutas activas para hoy.") }
            val (terminadas, porIniciar) = e.rutasHoy.partition { it.id in e.finalizadasHoy }
            if (porIniciar.isEmpty() && terminadas.isNotEmpty()) item {
                Text("Ya terminaste todas tus salidas de hoy.", color = Coo.TextoTenue, fontSize = 14.sp)
            }
            // Primero las que faltan; luego «Finalizadas hoy (n)», plegable.
            listOf(porIniciar, if (verFinalizadas) terminadas else emptyList()).forEachIndexed { g, grupo ->
                if (g == 1 && terminadas.isNotEmpty()) item(key = "plegar_finalizadas") {
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Color(0xFF121212))
                            .border(1.dp, Color(0xFF2A2A2A), RoundedCornerShape(16.dp))
                            .clickable { verFinalizadas = !verFinalizadas }.padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        pe.cooruta.app.ui.componentes.IconoM(Icons.Outlined.CheckCircle, Coo.Exito, 20.dp)
                        Spacer(Modifier.width(10.dp))
                        Text("Finalizadas hoy (${terminadas.size})", color = Coo.Texto, fontSize = 15.sp, fontWeight = FontWeight.Bold,
                            modifier = Modifier.weight(1f))
                        Text(if (verFinalizadas) "Ocultar" else "Ver", color = Coo.AzulUbicacion, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                        if (verFinalizadas) pe.cooruta.app.ui.componentes.IconoArriba(color = Coo.AzulUbicacion, tamano = 20.dp)
                        else pe.cooruta.app.ui.componentes.IconoAbajo(color = Coo.AzulUbicacion, tamano = 20.dp)
                    }
                }
                items(grupo, key = { it.id }) { r ->
                    val confirmados = e.confirmadas.filter { it.rutaId == r.id }
                    val finalizada = r.id in e.finalizadasHoy
                    Tarjeta {
                        // "casa -mercado" → "Casa → Mercado"; "PRUEBA FILTRO 02 NOV" → "Prueba Filtro 02 Nov"
                        val nombre = r.alias.takeIf { it.isNotBlank() }?.split("→", "->", "-")?.map { it.trim() }?.filter { it.isNotEmpty() }
                            ?.joinToString(" → ") { t -> t.split(" ").joinToString(" ") { w -> w.lowercase().replaceFirstChar(Char::uppercase) } }
                            ?: "${r.origen} → ${r.destino}"
                        Row(Modifier.height(androidx.compose.foundation.layout.IntrinsicSize.Min)) {
                            // Estado a la izquierda: check verde (terminada) o auto azul (por iniciar).
                            val colorEstado = if (finalizada) Coo.Exito else Coo.AzulBoton
                            Box(
                                Modifier.size(52.dp).clip(RoundedCornerShape(14.dp)).background(colorEstado.copy(alpha = 0.18f))
                                    .border(1.dp, colorEstado.copy(alpha = 0.45f), RoundedCornerShape(14.dp)),
                                contentAlignment = Alignment.Center
                            ) {
                                pe.cooruta.app.ui.componentes.IconoM(
                                    if (finalizada) Icons.Outlined.CheckCircle else Icons.Rounded.DirectionsCar,
                                    if (finalizada) Coo.Exito else Coo.AzulUbicacion, 28.dp)
                            }
                            Box(Modifier.padding(horizontal = 14.dp).width(1.dp).fillMaxHeight().background(Color(0xFF2A2A2A)))
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(nombre, color = Coo.Texto, fontSize = 17.sp, fontWeight = FontWeight.Bold, maxLines = 2,
                                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    pe.cooruta.app.ui.componentes.IconoM(Icons.Outlined.Schedule, Coo.TextoTenue, 18.dp)
                                    Spacer(Modifier.width(8.dp))
                                    Text("Salida: ${horaAmPm(r.salida)}", color = Coo.TextoTenue, fontSize = 14.sp)
                                }
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    pe.cooruta.app.ui.componentes.IconoM(Icons.Outlined.Group, Coo.TextoTenue, 18.dp)
                                    Spacer(Modifier.width(8.dp))
                                    Text(when (confirmados.size) {
                                        0 -> "Sin pasajeros confirmados"
                                        1 -> "1 pasajero confirmado"
                                        else -> "${confirmados.size} pasajeros confirmados"
                                    }, color = Coo.TextoTenue, fontSize = 14.sp)
                                }
                                confirmados.forEach { s -> Text("${s.nombrePasajero} · sube en ${s.subida}", color = Coo.Texto, fontSize = 13.sp,
                                    maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, modifier = Modifier.padding(start = 26.dp)) }
                            }
                        }
                        Spacer(Modifier.height(6.dp))
                        // Estado en pastilla · botón para iniciar.
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            val colorPastilla = if (finalizada) Coo.Exito else Coo.AzulUbicacion
                            Row(
                                Modifier.clip(RoundedCornerShape(50)).background(colorPastilla.copy(alpha = 0.14f))
                                    .border(1.dp, colorPastilla.copy(alpha = 0.45f), RoundedCornerShape(50))
                                    .padding(horizontal = 12.dp, vertical = 7.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                pe.cooruta.app.ui.componentes.IconoM(if (finalizada) Icons.Outlined.CheckCircle else Icons.Outlined.Timer, colorPastilla, 17.dp)
                                Spacer(Modifier.width(6.dp))
                                Text(if (finalizada) "Salida finalizada" else "Listo para iniciar", color = colorPastilla,
                                    fontSize = 13.sp, fontWeight = FontWeight.Bold)
                            }
                            Spacer(Modifier.weight(1f))
                            if (!finalizada) Row(
                                Modifier.height(46.dp).clip(RoundedCornerShape(14.dp))
                                    .background(if (!e.ocupado) androidx.compose.ui.graphics.Brush.horizontalGradient(
                                        listOf(Color(0xFF7189FF), Coo.AzulBoton, Color(0xFF3F55D9)))
                                        else androidx.compose.ui.graphics.SolidColor(Coo.CampoActivo))
                                    .clickable(enabled = !e.ocupado) { vm.iniciar(r.id, alAvisar) }
                                    .padding(horizontal = 16.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                pe.cooruta.app.ui.componentes.IconoM(Icons.Rounded.PlayArrow, Coo.SobreBoton, 22.dp)
                                Spacer(Modifier.width(6.dp))
                                Text(if (e.ocupado) "Iniciando…" else "Iniciar viaje", color = Coo.SobreBoton, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }
        } else {
            if (e.confirmadas.isEmpty()) item { Vacio("No tienes reservas confirmadas para hoy.") }
            items(e.confirmadas, key = { it.id }) { s ->
                Tarjeta {
                    // Mismo orden que la tarjeta del conductor: quién, hora, dónde sube y dónde baja.
                    val foto by androidx.compose.runtime.produceState<String?>(null, s.conductorId) {
                        if (s.conductorId.isNotBlank()) value = runCatching {
                            pe.cooruta.app.data.repositorio.PerfilesRepositorio().perfiles(setOf(s.conductorId))[s.conductorId]?.foto
                        }.getOrNull()?.ifBlank { null }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        pe.cooruta.app.ui.inicio.Avatar(foto, s.nombreConductor, 36.dp)
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(s.nombreConductor.ifBlank { "Tu conductor" }, color = Coo.Texto, fontSize = 16.sp, fontWeight = FontWeight.Bold,
                                maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                            Text("Conductor", color = Coo.TextoTenue, fontSize = 13.sp)
                        }
                        Text(pe.cooruta.app.ui.horario.formatoSoles(s.pasajeCentimos), color = Coo.Texto, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        pe.cooruta.app.ui.componentes.IconoReloj(color = Coo.TextoTenue, tamano = 18.dp)
                        Spacer(Modifier.width(8.dp))
                        Text("Sale ${s.horaSalida}", color = Coo.TextoTenue, fontSize = 14.sp)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.padding(horizontal = 5.dp).size(8.dp).background(Coo.AzulUbicacion, androidx.compose.foundation.shape.CircleShape))
                        Spacer(Modifier.width(8.dp))
                        Text("Subes en ${s.subida}", color = Coo.Texto, fontSize = 14.sp, maxLines = 1,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.padding(horizontal = 5.dp).size(8.dp).background(Coo.RojoDestino, androidx.compose.foundation.shape.CircleShape))
                        Spacer(Modifier.width(8.dp))
                        Text("Bajas en ${s.bajada}", color = Coo.Texto, fontSize = 14.sp, maxLines = 1,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                    }
                    Boton("Ver viaje") { vm.verComoPasajero(s) }
                }
            }
        }
    }
}

@Composable
private fun PantallaViaje(e: EstadoViajeUi, a: ViajeAbierto, vm: ViajeViewModel, alAvisar: (String) -> Unit, modifier: Modifier) {
    val contexto = LocalContext.current
    // Reloj de la pantalla para que «hace cuánto» se actualice sin nuevas lecturas.
    val ahora by produceState(System.currentTimeMillis()) { while (true) { delay(5_000); value = System.currentTimeMillis() } }

    var permiso by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(contexto, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED)
    }
    val pedidoUbicacion = pe.cooruta.app.ui.componentes.rememberPedidoUbicacion()
    if (a.comoConductor) {
        LaunchedEffect(Unit) {
            if (!permiso || !pe.cooruta.app.ui.componentes.ubicacionActivada(contexto))
                pedidoUbicacion.pedir { permiso = pe.cooruta.app.data.UbicacionEnVivo.tienePermiso(contexto) }
        }
        // Al mostrarse, arranca ServicioViaje: la ubicación sigue en segundo plano hasta finalizar o «‹».
        LifecycleResumeEffect(a.viajeId) {
            vm.pantallaVisible(true)
            onPauseOrDispose { vm.pantallaVisible(false) }
        }
    }

    val pasajeros = e.confirmadas.filter { it.rutaId == a.rutaId }
    val mia = pasajeros.firstOrNull { it.id == a.reservaId }
    val puntos = if (a.comoConductor) pasajeros.mapNotNull { it.subidaPunto } else listOfNotNull(mia?.subidaPunto)
    val seguimiento = PoliticaUbicacion.seguimiento(e.ubicacion?.activa, e.ubicacion?.tomadaMs, ahora)
    // Muestras periódicas del GPS solo mientras se comparte (pantalla visible y viaje sin terminar).
    if (a.comoConductor) MuestreoUbicacion(activo = permiso && e.seguimientoActivo, alMuestra = vm::alRecibirMuestra)

    // Avisos por voz del conductor: aquí arriba para que funcionen aunque el panel esté plegado.
    var voz by rememberSaveable { mutableStateOf(true) }
    val enRuta = e.viaje?.estado == EstadoViaje.EN_CURSO || e.viaje?.estado == EstadoViaje.SALIENDO
    if (a.comoConductor && enRuta) VozConductor(e, activa = voz)
    val navegar = if (a.comoConductor) rememberNavegar(e, alAvisar) else ({})
    // «Iniciar salida»: abre la navegación en cuanto hay recorrido (o a los 6 s, con lo que haya).
    if (a.comoConductor && e.navegarAlAbrir) {
        LaunchedEffect(e.recorrido.linea.isNotEmpty()) {
            if (e.recorrido.linea.isEmpty()) kotlinx.coroutines.delay(6_000)
            vm.navegacionAbierta(); navegar()
        }
    }
    // Avisó «salgo en 5 minutos»: a los 5 min el viaje pasa solo a «en curso».
    if (a.comoConductor && e.viaje?.estado == EstadoViaje.SALIENDO) {
        LaunchedEffect(e.viaje?.salidaMs) {
            val desde = e.viaje?.salidaMs ?: System.currentTimeMillis()
            kotlinx.coroutines.delay((desde + 5 * 60_000L - System.currentTimeMillis()).coerceAtLeast(0))
            vm.cambiarEstado(EstadoViaje.EN_CURSO, alAvisar)
        }
    }
    // Pasajero: recorrido completo del conductor, para ver su tramo resaltado en tiempo real.
    val rutaConductor by androidx.compose.runtime.produceState(emptyList<Punto>(), a.comoConductor, mia?.rutaId) {
        val id = mia?.rutaId
        if (!a.comoConductor && !id.isNullOrBlank()) value = runCatching {
            val d = kotlinx.coroutines.suspendCancellableCoroutine<com.google.firebase.firestore.DocumentSnapshot> { k ->
                com.google.firebase.firestore.FirebaseFirestore.getInstance().collection("rutas").document(id).get()
                    .addOnSuccessListener { if (k.isActive) k.resumeWith(Result.success(it)) }
                    .addOnFailureListener { if (k.isActive) k.resumeWith(Result.failure(it)) }
            }
            (d.get("recorrido") as? List<*>).orEmpty().mapNotNull { m ->
                val mapa = m as? Map<*, *> ?: return@mapNotNull null
                val la = (mapa["lat"] as? Number)?.toDouble() ?: return@mapNotNull null
                val ln = (mapa["lng"] as? Number)?.toDouble() ?: return@mapNotNull null
                Punto(la, ln)
            }
        }.getOrDefault(emptyList())
    }
    // Vista tipo navegación: mapa casi a pantalla completa, cartel arriba y panel abajo.
    Column(modifier.fillMaxSize()) {
        Box(Modifier.fillMaxWidth().weight(1f).background(Coo.Campo), contentAlignment = Alignment.Center) {
            if (BuildConfig.HAY_MAPA) MapaViaje(
                conductor = a.comoConductor && permiso,
                // Pasajero: solo su punto de encuentro. Conductor: sus recogidas pendientes.
                encuentros = if (a.comoConductor) emptyList() else puntos,
                recogidas = if (a.comoConductor) e.recorrido.recogidas.drop(1).map { it.punto } else emptyList(),
                proxima = if (a.comoConductor) e.recorrido.proxima?.punto else null,
                linea = if (a.comoConductor) e.recorrido.linea else emptyList(),
                // El pasajero ve la última ubicación publicada; el conductor ve su propio punto azul.
                conductorPunto = if (!a.comoConductor && seguimiento != Seguimiento.SIN_DATOS &&
                    e.viaje?.estado != EstadoViaje.FINALIZADO) e.ubicacion?.punto else null,
                rumbo = e.rumbo,
                rutaConductor = rutaConductor,
                bajada = if (a.comoConductor) null else mia?.bajadaPunto
            ) else Text("Mapa no disponible (falta GOOGLE_MAPS_KEY).", color = Coo.TextoTenue, fontSize = 13.sp)
            Cartel(e, a, pasajeros, mia, vm, Modifier.align(Alignment.TopCenter))
        }
        // Panel inferior: acciones y pasajeros (sin el bloque de detalles técnicos).
        LazyColumn(
            Modifier.fillMaxWidth().heightIn(max = 400.dp)
                .clip(RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)).background(Coo.Fondo),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item {
                // Conductor: el estado ya se ve en el cartel del mapa; aquí solo el del pasajero.
                if (!a.comoConductor) Text(textoEstado(e), color = Coo.Texto, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                e.errorViaje?.let { Text(it, color = Coo.RojoError, fontSize = 13.sp) }
                // Solo se avisa cuando no hay conexión.
                if (e.viajeEnCache || e.ubicacionEnCache)
                    Text("Sin conexión con el servidor: se muestran los últimos datos guardados.", color = Coo.RojoError, fontSize = 13.sp)
                if (a.comoConductor) {
                    // Solo avisos que piden acción; el detalle del GPS y los envíos ya no se muestra.
                    when {
                        !permiso -> "Sin permiso de ubicación: los pasajeros no verán tu posición."
                        e.seguimientoActivo && (e.ultimaMuestraMs == null || ahora - e.ultimaMuestraMs > PoliticaUbicacion.MUESTRA_MAX_MS) ->
                            "Esperando señal GPS: los pasajeros verán tu ubicación como desactualizada."
                        else -> null
                    }?.let { Text(it, color = Coo.RojoError, fontSize = 13.sp) }
                    e.errorEnvio?.let { Text(it, color = Coo.RojoError, fontSize = 13.sp) }
                } else {
                    Text(
                        when (e.miParada) {
                            EstadoParada.LLEGUE -> "El conductor llegó a tu punto de encuentro."
                            EstadoParada.A_BORDO -> "Estás a bordo."
                            EstadoParada.NO_SE_PRESENTO -> "El conductor te esperó 5 minutos y siguió su ruta. Tu reserva de hoy se canceló."
                            else -> "Tu punto de encuentro: ${mia?.subida ?: "—"}."
                        },
                        color = Coo.Texto, fontSize = 14.sp
                    )
                    if (mia != null) {
                        PanelSeguridad(
                            "Voy en CooRuta con ${mia.nombreConductor.ifBlank { "mi conductor" }}, sale a las ${mia.horaSalida}. " +
                                "Subo en ${mia.subida} y bajo en ${mia.bajada}.",
                            e.ubicacion?.punto
                        ) { pe.cooruta.app.ui.inicio.IconoChat(mia, comoConductor = false) }
                    }
                    if (e.viaje?.estado == EstadoViaje.FINALIZADO && mia != null)
                        pe.cooruta.app.ui.inicio.BotonCalificar(mia, comoConductor = false, alAvisar = alAvisar)
                }
            }
            if (a.comoConductor) {
                item { EstadoDelRecorrido(e, pasajeros, vm) }
                // Acciones del viaje en un plegable: siempre empieza cerrado (deja ver a los pasajeros).
                item {
                    var abiertas by rememberSaveable(a.viajeId) { mutableStateOf(false) }
                    Column(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Coo.Campo)
                            .border(1.dp, Coo.BordeSuave, RoundedCornerShape(16.dp))
                            .animateContentSize()
                    ) {
                        Row(
                            Modifier.fillMaxWidth().clickable(onClickLabel = if (abiertas) "Ocultar acciones" else "Mostrar acciones") { abiertas = !abiertas }
                                .padding(horizontal = 16.dp, vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text("Acciones del viaje", color = Coo.Texto, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                                Text(if (abiertas) "Toca para ocultar" else "Navegar, avisos por voz, finalizar, emergencia",
                                    color = Coo.TextoTenue, fontSize = 12.sp, maxLines = 1,
                                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                            }
                            // Flecha en un círculo que gira al abrir y cerrar.
                            val giro by androidx.compose.animation.core.animateFloatAsState(if (abiertas) 180f else 0f, label = "giro")
                            Box(
                                Modifier.padding(start = 12.dp).size(34.dp).clip(androidx.compose.foundation.shape.CircleShape)
                                    .background(if (abiertas) Coo.CampoActivo else Coo.AzulBoton),
                                contentAlignment = Alignment.Center
                            ) {
                                androidx.compose.foundation.Canvas(Modifier.size(16.dp).graphicsLayer { rotationZ = giro }) {
                                    val w = size.width; val h = size.height
                                    val flecha = androidx.compose.ui.graphics.Path().apply {
                                        moveTo(w * 0.18f, h * 0.36f); lineTo(w * 0.5f, h * 0.68f); lineTo(w * 0.82f, h * 0.36f)
                                    }
                                    drawPath(flecha, Color.White, style = androidx.compose.ui.graphics.drawscope.Stroke(
                                        width = w * 0.14f, cap = androidx.compose.ui.graphics.StrokeCap.Round,
                                        join = androidx.compose.ui.graphics.StrokeJoin.Round))
                                }
                            }
                        }
                        if (abiertas) Column(
                            Modifier.padding(start = 12.dp, end = 12.dp, bottom = 12.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            AccionesConductor(e, vm, alAvisar, voz, { voz = !voz }, navegar)
                            PanelSeguridad("Estoy conduciendo con CooRuta: salida de hoy con ${pasajeros.size} pasajero(s).", e.ubicacion?.punto)
                        }
                    }
                }
                if (e.viaje?.estado == EstadoViaje.EN_CURSO || e.viaje?.estado == EstadoViaje.FINALIZADO || pasajeros.isNotEmpty())
                    item { Text("Pasajeros de hoy", color = Coo.TextoTenue, fontSize = 13.sp, fontWeight = FontWeight.Bold) }
                items(pasajeros, key = { it.id }) { s -> FilaPasajero(s, e, vm, alAvisar) }
                // Solo las ausencias de esta salida (el conductor puede tener otras rutas hoy).
                items(e.noSePresentaron.filter { it.rutaId == a.rutaId }, key = { "nsp_" + it.id }) { s ->
                    Tarjeta {
                        Text(s.nombrePasajero, color = Coo.Texto, fontWeight = FontWeight.Bold)
                        Text("No se presentó · asiento liberado", color = Coo.TextoTenue, fontSize = 13.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun AccionesConductor(e: EstadoViajeUi, vm: ViajeViewModel, alAvisar: (String) -> Unit, voz: Boolean, alAlternarVoz: () -> Unit,
                              navegar: () -> Unit) {
    val estado = e.viaje?.estado ?: return
    var confirmarFin by remember(estado) { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (estado == EstadoViaje.PREPARANDO)
            Boton("Salgo en 5 minutos", habilitado = !e.ocupado) { vm.cambiarEstado(EstadoViaje.SALIENDO, alAvisar) }
        if (estado == EstadoViaje.PREPARANDO || estado == EstadoViaje.SALIENDO)
            Boton("Iniciar recorrido", habilitado = !e.ocupado) { vm.cambiarEstado(EstadoViaje.EN_CURSO, alAvisar); navegar() }
        if (estado == EstadoViaje.EN_CURSO || estado == EstadoViaje.SALIENDO)
            Boton("Navegar (Google, con voz)") { navegar() }
        // Avisos por voz durante el viaje (próxima recogida, cancelaciones).
        if (estado == EstadoViaje.EN_CURSO || estado == EstadoViaje.SALIENDO) {
            Boton(if (voz) "🔊 Avisos por voz activados" else "🔇 Avisos por voz silenciados", fondo = Coo.CampoActivo) { alAlternarVoz() }
        }
        if (estado != EstadoViaje.FINALIZADO)
            Boton(if (confirmarFin) "Toca otra vez para finalizar" else "Finalizar", habilitado = !e.ocupado, fondo = Coo.CampoActivo) {
                if (confirmarFin) vm.cambiarEstado(EstadoViaje.FINALIZADO, alAvisar) else confirmarFin = true
            }
    }
}

/**
 * Espera de «No se presentó»: corre desde lo más tarde entre la hora acordada de recogida y la
 * llegada del conductor. Al cumplirse 5 min se habilita el botón (doble toque). La función vuelve
 * a validar la espera con la hora del servidor.
 */
@Composable
private fun EsperaNoSePresento(s: Solicitud, llegadaMs: Long?, alMarcar: () -> Unit) {
    val lima = java.time.ZoneId.of("America/Lima")
    val acordadaMs = remember(s.id, s.horaRecogida, s.horaSalida) {
        runCatching {
            java.time.LocalDate.parse(s.fecha)
                .atTime(java.time.LocalTime.parse(s.horaRecogida.ifBlank { s.horaSalida }))
                .atZone(lima).toInstant().toEpochMilli()
        }.getOrNull()
    }
    val desde = listOfNotNull(llegadaMs, acordadaMs).maxOrNull() ?: return
    val hasta = desde + EstadoParada.ESPERA_MIN * 60_000
    val ahora by produceState(System.currentTimeMillis(), hasta) {
        while (value < hasta) { kotlinx.coroutines.delay(1_000); value = System.currentTimeMillis() }
    }
    var confirmar by rememberSaveable(s.id) { mutableStateOf(false) }
    val falta = hasta - ahora
    if (falta > 0) {
        val seg = (falta + 999) / 1000
        Text(
            if (ahora < desde) "Esperando la hora acordada (${s.horaRecogida.ifBlank { s.horaSalida }})."
            else "Si no aparece, podrás marcar «No se presentó» en ${seg / 60}:${"%02d".format(seg % 60)}.",
            color = Coo.TextoTenue, fontSize = 13.sp
        )
    } else {
        Boton(if (confirmar) "Toca otra vez: no se presentó" else "No se presentó", fondo = Coo.CampoActivo) {
            if (confirmar) { confirmar = false; alMarcar() } else confirmar = true
        }
    }
}

@Composable
private fun FilaPasajero(s: Solicitud, e: EstadoViajeUi, vm: ViajeViewModel, alAvisar: (String) -> Unit) {
    val parada = e.paradas[s.id]
    Tarjeta {
        Text(s.nombrePasajero, color = Coo.Texto, fontWeight = FontWeight.Bold)
        Text("Sube en ${s.subida}", color = Coo.TextoTenue, fontSize = 13.sp)
        when {
            // Al finalizar, solo cuenta como recogida la que llegó a «a bordo».
            parada == EstadoParada.A_BORDO -> Text(
                if (e.viaje?.estado == EstadoViaje.FINALIZADO) "Recogida completada" else "A bordo",
                color = VERDE, fontWeight = FontWeight.Bold)
            e.viaje?.estado == EstadoViaje.FINALIZADO -> Text("Sin recogida registrada", color = Coo.TextoTenue, fontSize = 13.sp)
            e.viaje?.estado != EstadoViaje.EN_CURSO -> Text(
                if (parada == EstadoParada.LLEGUE) "Llegaste a su punto" else "Pendiente de recoger", color = Coo.TextoTenue, fontSize = 13.sp)
            parada == EstadoParada.LLEGUE -> {
                Boton("Pasajero a bordo") { vm.marcar(s, EstadoParada.A_BORDO, alAvisar) }
                EsperaNoSePresento(s, e.llegadas[s.id]) { vm.noSePresento(s, alAvisar) }
            }
            else -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Boton("Llegué", modifier = Modifier.weight(1f)) { vm.marcar(s, EstadoParada.LLEGUE, alAvisar) }
                Boton("Pasajero a bordo", modifier = Modifier.weight(1f)) { vm.marcar(s, EstadoParada.A_BORDO, alAvisar) }
            }
        }
        pe.cooruta.app.ui.inicio.BotonChat(s, comoConductor = true)
        if (e.viaje?.estado == EstadoViaje.FINALIZADO)
            pe.cooruta.app.ui.inicio.BotonCalificar(s, comoConductor = true, alAvisar = alAvisar)
    }
}

@Composable
private fun MapaViaje(
    conductor: Boolean,
    encuentros: List<Punto>,
    recogidas: List<Punto>,
    proxima: Punto?,
    linea: List<Punto>,
    conductorPunto: Punto?,
    rumbo: Double?,
    /** Pasajero: recorrido del conductor y dónde baja (su tramo se resalta). */
    rutaConductor: List<Punto> = emptyList(),
    bajada: Punto? = null
) {
    val contexto = LocalContext.current
    val centro = conductorPunto ?: proxima ?: encuentros.firstOrNull() ?: Punto(-16.409, -71.537)
    val camara = rememberCameraPositionState { position = CameraPosition.fromLatLngZoom(centro.latLng(), 13f) }
    var satelite by rememberSaveable { mutableStateOf(false) }
    val alcance = rememberCoroutineScope()
    val densidad = androidx.compose.ui.platform.LocalDensity.current.density
    // Conductor: su propia posición (se dibuja como carrito, en vez del punto azul).
    val permiso = ContextCompat.checkSelfPermission(contexto, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
        ContextCompat.checkSelfPermission(contexto, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
    var miPos by remember { mutableStateOf<Punto?>(null) }
    MuestreoUbicacion(conductor && permiso) { miPos = it.punto }
    // Cámara del conductor: sigue su carrito. Un gesto manual la suelta y aparece «Centrar».
    var siguiendo by remember { mutableStateOf(conductor) }
    LaunchedEffect(camara.isMoving) {
        if (camara.isMoving && camara.cameraMoveStartedReason == CameraMoveStartedReason.GESTURE) siguiendo = false
    }
    fun seguir() {
        val p = miPos ?: return
        siguiendo = true
        alcance.launch {
            runCatching {
                camara.animate(CameraUpdateFactory.newCameraPosition(CameraPosition.Builder()
                    .target(p.latLng()).zoom(16f).tilt(45f).bearing((rumbo ?: camara.position.bearing.toDouble()).toFloat()).build()), 700)
            }
        }
    }
    LaunchedEffect(miPos, rumbo) { if (conductor && siguiendo) seguir() }

    // Pasajero: su posición en vivo y su tramo (de donde sube a donde baja) sobre la ruta del conductor.
    val yo by pe.cooruta.app.data.UbicacionEnVivo.actual.collectAsStateWithLifecycle()
    val subida = encuentros.firstOrNull()
    val tramo = remember(rutaConductor, subida, bajada) {
        if (conductor || subida == null || bajada == null || rutaConductor.size < 2) emptyList()
        else {
            fun cerca(x: Punto) = rutaConductor.indices.minByOrNull { pe.cooruta.app.data.metros(rutaConductor[it], x) } ?: 0
            val i = cerca(subida); val j = cerca(bajada)
            listOf(subida) + rutaConductor.subList(minOf(i, j), maxOf(i, j) + 1) + listOf(bajada)
        }
    }
    var cargado by remember { mutableStateOf(false) }
    var encuadrado by remember { mutableStateOf(false) }
    fun encuadrar() {
        val todos = tramo + listOfNotNull(subida, bajada, yo, conductorPunto)
        pe.cooruta.app.ui.mapa.limites(todos)?.let { b ->
            alcance.launch { runCatching { camara.animate(CameraUpdateFactory.newLatLngBounds(b, (60 * densidad).toInt()), 600) } }
        }
    }
    LaunchedEffect(cargado, tramo) { if (!conductor && cargado && !encuadrado && tramo.isNotEmpty()) { encuadrar(); encuadrado = true } }

    Box(Modifier.fillMaxSize()) {
        GoogleMap(
            modifier = Modifier.fillMaxSize(),
            cameraPositionState = camara,
            properties = propiedadesMapa(satelite),
            uiSettings = UI_MAPA,
            // Deja libre la franja del cartel de arriba.
            contentPadding = PaddingValues(top = if (conductor) 0.dp else 110.dp),
            onMapLoaded = { cargado = true }
        ) {
            LineaMapa(linea, Coo.AzulUbicacion, 6f)
            if (!conductor) {
                LineaMapa(rutaConductor, Coo.Neutro, 5f)
                LineaMapa(tramo, Coo.AzulUbicacion, 7f)
                // Lo que camina hasta su punto de encuentro (en vivo).
                val pasos = remember { listOf(com.google.android.gms.maps.model.Dot(), com.google.android.gms.maps.model.Gap(12f)) }
                val p0 = yo
                if (p0 != null && subida != null) com.google.maps.android.compose.Polyline(
                    points = listOf(p0.latLng(), subida.latLng()), color = Coo.Exito, width = 5f * densidad, pattern = pasos)
                bajada?.let { b ->
                    val icono = remember(densidad) { pe.cooruta.app.ui.mapa.circulo(Coo.RojoDestino, densidad, 8f, 3f) }
                    MarcaMapa(b, icono, z = 1f)
                }
                p0?.let { y ->
                    val icono = remember(densidad) { pe.cooruta.app.ui.mapa.circulo(Coo.AzulUbicacion, densidad, 7f, 3f) }
                    MarcaMapa(y, icono, z = 3f)
                }
            }
            // Pasajeros (puntos de encuentro): ícono de persona.
            val persona = remember(densidad) { iconoPersona(densidad) }
            (encuentros + recogidas).forEach { MarcaMapa(it, persona) }
            // La próxima recogida se distingue en ámbar.
            proxima?.let { p ->
                val icono = remember(densidad) { iconoPersona(densidad, android.graphics.Color.rgb(0xFF, 0xC8, 0x57)) }
                MarcaMapa(p, icono, z = 1f)
            }
            // Carrito: el conductor ve el suyo; el pasajero, la última ubicación publicada.
            val carro = remember(densidad) { iconoCarro(densidad) }
            (if (conductor) miPos else conductorPunto)?.let { MarcaMapa(it, carro, z = 2f) }
        }
        Column(Modifier.align(Alignment.BottomEnd).padding(end = 10.dp, bottom = 34.dp), verticalArrangement = Arrangement.spacedBy(8.dp), horizontalAlignment = Alignment.End) {
            // Acercar, alejar y ver mi ubicación actual.
            BotonRedondo({ pe.cooruta.app.ui.componentes.IconoMas(color = Coo.Texto, tamano = 22.dp) }, "Acercar") {
                alcance.launch { runCatching { camara.animate(CameraUpdateFactory.zoomIn(), 300) } }
            }
            BotonRedondo({ pe.cooruta.app.ui.componentes.IconoMenos(color = Coo.Texto, tamano = 22.dp) }, "Alejar") {
                alcance.launch { runCatching { camara.animate(CameraUpdateFactory.zoomOut(), 300) } }
            }
            val aqui = if (conductor) miPos else yo
            if (aqui != null) BotonRedondo({ pe.cooruta.app.ui.componentes.IconoMiUbicacion(color = Coo.AzulUbicacion, tamano = 22.dp) }, "Mi ubicación") {
                if (conductor) seguir()
                else alcance.launch { runCatching { camara.animate(CameraUpdateFactory.newLatLngZoom(aqui.latLng(), 16f), 600) } }
            }
            if (conductor && !siguiendo && miPos != null) BotonMapa("Centrar") { seguir() }
            if (!conductor && tramo.isNotEmpty()) BotonMapa("Centrar") { encuadrar() }
            BotonMapa(if (satelite) "Mapa" else "Satélite") { satelite = !satelite }
        }
    }
}

@Composable
private fun BotonRedondo(icono: @Composable () -> Unit, descripcion: String, alPulsar: () -> Unit) {
    Box(
        Modifier.size(46.dp).clip(androidx.compose.foundation.shape.CircleShape).background(Coo.Fondo.copy(alpha = 0.9f))
            .border(1.dp, Coo.Borde, androidx.compose.foundation.shape.CircleShape).clickable(onClick = alPulsar)
            .semantics { contentDescription = descripcion },
        contentAlignment = Alignment.Center
    ) { icono() }
}

@Composable
private fun BotonMapa(texto: String, alPulsar: () -> Unit) {
    Text(texto, color = Coo.Texto, fontSize = 13.sp, fontWeight = FontWeight.Bold,
        modifier = Modifier.clip(RoundedCornerShape(10.dp)).background(Coo.Fondo.copy(alpha = 0.85f))
            .clickable(onClick = alPulsar).padding(horizontal = 12.dp, vertical = 8.dp))
}

/** Conductor: estado del recorrido y próxima recogida. El cálculo vive en el ViewModel. */
@Composable
private fun EstadoDelRecorrido(e: EstadoViajeUi, pasajeros: List<Solicitud>, vm: ViajeViewModel) {
    if (e.viaje?.estado == EstadoViaje.FINALIZADO) return
    val r = e.recorrido
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        r.proxima?.let { p ->
            val s = pasajeros.firstOrNull { it.id == p.reservaId }
            Text("Próxima recogida: ${s?.nombrePasajero ?: "pasajero"} — ${s?.subida ?: ""}", color = AMBAR, fontSize = 14.sp, fontWeight = FontWeight.Bold)
        }
        when {
            r.esperandoUbicacion -> Text("El recorrido se calculará cuando haya una ubicación GPS reciente.", color = Coo.TextoTenue, fontSize = 13.sp)
            r.cargando -> Text("Calculando el recorrido…", color = Coo.TextoTenue, fontSize = 13.sp)
            r.error != null -> {
                Text("${r.error} El mapa sigue disponible.", color = Coo.RojoError, fontSize = 13.sp)
                Boton("Reintentar recorrido") { vm.reintentarRecorrido() }
            }
        }
        if (r.omitidas > 0) Text(
            "${r.omitidas} recogida(s) no entran en el recorrido: Google admite como máximo ${PlanificadorRecorrido.MAX_COORDENADAS} puntos por consulta.",
            color = Coo.RojoError, fontSize = 13.sp)
    }
}

private fun textoEstado(e: EstadoViajeUi): String = when (e.viaje?.estado) {
    null -> if (e.abierto?.comoConductor == true) "Preparando la salida…" else "El conductor aún no inicia la salida."
    EstadoViaje.PREPARANDO -> "Salida preparada"
    EstadoViaje.SALIENDO -> "El conductor sale en 5 minutos"
    EstadoViaje.EN_CURSO -> "Recorrido en curso"
    EstadoViaje.FINALIZADO -> "Viaje finalizado"
    else -> "Estado desconocido"
}

/** Cartel superior: próxima recogida (conductor) o estado del viaje y tu punto (pasajero). */
@Composable
private fun Cartel(e: EstadoViajeUi, a: ViajeAbierto, pasajeros: List<Solicitud>, mia: Solicitud?, vm: ViajeViewModel, modifier: Modifier) {
    Row(
        modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp).clip(RoundedCornerShape(14.dp)).background(CARTEL)
            .padding(start = 2.dp, end = 12.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier.sizeIn(minWidth = 44.dp, minHeight = 44.dp)
                .clickable(onClickLabel = "Salir del viaje", role = androidx.compose.ui.semantics.Role.Button) { vm.cerrar() }
                .semantics { contentDescription = "Volver" },
            contentAlignment = Alignment.Center
        ) { pe.cooruta.app.ui.componentes.IconoAtras(color = Color.White, tamano = 22.dp) }
        Column(Modifier.weight(1f)) {
            val p = e.recorrido.proxima
            val s = pasajeros.firstOrNull { it.id == p?.reservaId }
            val r = e.recorrido.ruta
            when {
                !a.comoConductor -> {
                    Text(textoEstado(e), color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    mia?.let { Text("Tu punto de encuentro: ${it.subida}", color = Color.White, fontSize = 12.sp) }
                }
                e.viaje?.estado == EstadoViaje.FINALIZADO -> Text("Viaje finalizado", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                p != null -> {
                    Text("Próxima recogida", color = Color.White.copy(alpha = 0.8f), fontSize = 11.sp)
                    Text(s?.nombrePasajero ?: "Pasajero", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                    Text("Sube en ${s?.subida ?: "—"}", color = Color.White, fontSize = 12.sp)
                }
                r != null -> Text("Hacia tu destino", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                else -> Text(textoEstado(e), color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            }
            // Estimación de Mapbox al calcular el recorrido (no es una predicción en vivo).
            if (a.comoConductor && r?.metrosPrimerTramo != null && e.viaje?.estado != EstadoViaje.FINALIZADO)
                Text("≈ ${minutos(r.segundosPrimerTramo)} · ${distancia(r.metrosPrimerTramo)}", color = AMBAR, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        }
    }
}

private fun minutos(segundos: Double?) = segundos?.let { "${kotlin.math.ceil(it / 60).toInt().coerceAtLeast(1)} min" } ?: "—"
private fun distancia(m: Double) = if (m < 1000) "${m.toInt()} m" else String.format(Locale.US, "%.1f km", m / 1000)

private fun hora(ms: Long?) = ms?.let { SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(it)) } ?: "—"

@Composable
private fun Tarjeta(contenido: @Composable () -> Unit) {
    Column(
        // Negro oscuro, igual que las demás tarjetas de la app.
        Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Color(0xFF121212))
            .border(1.dp, Color(0xFF2A2A2A), RoundedCornerShape(20.dp)).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) { contenido() }
}

@Composable
private fun Boton(
    texto: String, habilitado: Boolean = true, fondo: Color = Coo.AzulBoton,
    modifier: Modifier = Modifier.fillMaxWidth(), alPulsar: () -> Unit
) {
    Text(
        texto, color = if (!habilitado) Coo.TextoTenue else if (fondo == Coo.AzulBoton) Coo.SobreBoton else Coo.Texto, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center,
        modifier = modifier.padding(top = 4.dp).clip(RoundedCornerShape(12.dp)).background(fondo)
            .clickable(enabled = habilitado, onClick = alPulsar).padding(vertical = 12.dp)
    )
}

@Composable
private fun Vacio(texto: String) {
    // Igual que los demás vacíos: ícono de líneas finas (▷ dentro de un círculo) y un título, centrados.
    val alto = androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp
    Column(Modifier.fillMaxWidth().heightIn(min = (alto * 0.6f).dp), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center) {
        androidx.compose.foundation.Canvas(Modifier.size(64.dp)) {
            val w = size.width; val g = w * 0.045f; val c = Coo.Borde
            drawCircle(c, radius = w * 0.4f, style = androidx.compose.ui.graphics.drawscope.Stroke(width = g))
            val t = androidx.compose.ui.graphics.Path().apply { moveTo(w * 0.42f, w * 0.34f); lineTo(w * 0.66f, w * 0.5f); lineTo(w * 0.42f, w * 0.66f); close() }
            drawPath(t, c, style = androidx.compose.ui.graphics.drawscope.Stroke(width = g, join = androidx.compose.ui.graphics.StrokeJoin.Round))
        }
        Spacer(Modifier.height(14.dp))
        Text(texto.removeSuffix("."), color = Coo.TextoTenue, fontSize = 15.sp, fontWeight = FontWeight.Medium, textAlign = TextAlign.Center)
    }
}

/**
 * Pide muestras al GPS del dispositivo cada [PoliticaUbicacion.MUESTREO_MS] mientras [activo]
 * (LocationManager del sistema, sin dependencias nuevas). Cada muestra lleva la hora real de la
 * lectura. Al desactivarse o salir de la pantalla se retiran las actualizaciones.
 */
@SuppressLint("MissingPermission") // solo se activa con el permiso de ubicación concedido
@Composable
private fun MuestreoUbicacion(activo: Boolean, alMuestra: (Muestra) -> Unit) {
    val contexto = LocalContext.current
    val actual by rememberUpdatedState(alMuestra)
    DisposableEffect(activo) {
        val gestor = contexto.getSystemService(LocationManager::class.java)
        if (!activo || gestor == null) return@DisposableEffect onDispose { }
        val proveedor = when {
            Build.VERSION.SDK_INT >= 31 && gestor.hasProvider(LocationManager.FUSED_PROVIDER) -> LocationManager.FUSED_PROVIDER
            gestor.isProviderEnabled(LocationManager.GPS_PROVIDER) -> LocationManager.GPS_PROVIDER
            else -> LocationManager.NETWORK_PROVIDER
        }
        // Objeto completo (no lambda): en Android 10 los demás métodos aún no tienen implementación por defecto.
        val oyente = object : LocationListener {
            override fun onLocationChanged(l: android.location.Location) = actual(Muestra(Punto(l.latitude, l.longitude), l.time))
            @Deprecated("Requerido en API < 30") override fun onStatusChanged(p: String?, s: Int, e: android.os.Bundle?) = Unit
            override fun onProviderEnabled(p: String) = Unit
            override fun onProviderDisabled(p: String) = Unit
        }
        // Arranque rápido: la última ubicación conocida si es de hace menos de 30 s.
        runCatching {
            listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER)
                .mapNotNull { gestor.getLastKnownLocation(it) }.maxByOrNull { it.time }
                ?.takeIf { System.currentTimeMillis() - it.time <= PoliticaUbicacion.MUESTRA_MAX_MS }
                ?.let { actual(Muestra(Punto(it.latitude, it.longitude), it.time)) }
        }
        // Sin proveedor disponible no hay muestras: la pantalla mostrará «esperando señal GPS».
        runCatching { gestor.requestLocationUpdates(proveedor, PoliticaUbicacion.MUESTREO_MS, 0f, oyente, Looper.getMainLooper()) }
        onDispose { gestor.removeUpdates(oyente) }
    }
}

/** Círculo de color con borde blanco: base de los íconos del mapa. */
private fun baseIcono(densidad: Float, color: Int): Pair<android.graphics.Bitmap, android.graphics.Canvas> {
    val t = (40 * densidad).toInt()
    val bmp = android.graphics.Bitmap.createBitmap(t, t, android.graphics.Bitmap.Config.ARGB_8888)
    val c = android.graphics.Canvas(bmp)
    val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
    p.color = android.graphics.Color.WHITE; c.drawCircle(t / 2f, t / 2f, t / 2f, p)
    p.color = color; c.drawCircle(t / 2f, t / 2f, t / 2f - 3 * densidad, p)
    return bmp to c
}

/** Carrito blanco sobre círculo azul (ubicación del conductor). */
internal fun iconoCarro(densidad: Float): android.graphics.Bitmap {
    val (bmp, c) = baseIcono(densidad, android.graphics.Color.rgb(0x55, 0x6F, 0xF7))
    val t = bmp.width.toFloat(); val u = t / 40f
    val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.WHITE }
    // Techo y carrocería
    c.drawRoundRect(android.graphics.RectF(13 * u, 12 * u, 27 * u, 20 * u), 3 * u, 3 * u, p)
    c.drawRoundRect(android.graphics.RectF(9 * u, 18 * u, 31 * u, 27 * u), 3 * u, 3 * u, p)
    // Ruedas (huecos del color de fondo)
    p.color = android.graphics.Color.rgb(0x55, 0x6F, 0xF7)
    c.drawCircle(14 * u, 27 * u, 3 * u, p); c.drawCircle(26 * u, 27 * u, 3 * u, p)
    p.color = android.graphics.Color.WHITE
    c.drawCircle(14 * u, 27 * u, 1.6f * u, p); c.drawCircle(26 * u, 27 * u, 1.6f * u, p)
    return bmp
}

/** Personita blanca sobre círculo verde (punto de encuentro del pasajero). */
internal fun iconoPersona(densidad: Float, fondo: Int = android.graphics.Color.rgb(0x5C, 0xC9, 0x8A)): android.graphics.Bitmap {
    val (bmp, c) = baseIcono(densidad, fondo)
    val t = bmp.width.toFloat(); val u = t / 40f
    val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.WHITE }
    c.drawCircle(20 * u, 14 * u, 5 * u, p)
    c.drawRoundRect(android.graphics.RectF(12 * u, 21 * u, 28 * u, 31 * u), 7 * u, 7 * u, p)
    return bmp
}

/**
 * Avisos por voz para el conductor (TextToSpeech del teléfono, sin internet ni IA):
 * próxima recogida a ~5 min, llegada a la recogida, cancelaciones y fin de recogidas.
 */
@Composable
private fun VozConductor(e: EstadoViajeUi, activa: Boolean) {
    val ctx = LocalContext.current
    // Voz Gemini TTS (con respaldo de la voz del teléfono): ver VozCooRuta.
    val lista = true
    val dichos = remember { mutableSetOf<String>() }
    fun decir(clave: String, texto: String) {
        // Con la navegación de Google abierta, la voz la da ella (salvo cancelaciones, que solo sabe esta pantalla).
        if (pe.cooruta.app.NavegacionActivity.abierta && !clave.startsWith("cancelo_")) return
        if (!activa || !lista || !dichos.add(clave)) return
        pe.cooruta.app.data.VozCooRuta.decir(ctx, texto)
    }
    val pasajeros = e.confirmadas
    val nombres = pasajeros.associate { it.id to it.nombrePasajero.ifBlank { "tu pasajero" } }
    // Cancelaciones: estaba confirmado y ya no.
    var antes by remember { mutableStateOf<Map<String, String>?>(null) }
    LaunchedEffect(nombres.keys) {
        // Si la lista llega vacía de golpe (recarga), no se anuncia nada.
        if (nombres.isNotEmpty() || (antes?.size ?: 0) <= 1) antes?.filterKeys { it !in nombres }?.forEach { (id, n) -> decir("cancelo_$id", "$n canceló su viaje. Continuemos.") }
        antes = nombres
    }
    // Bajadas y llegada: según la posición en vivo del conductor.
    var pos by remember { mutableStateOf<Punto?>(null) }
    MuestreoUbicacion(activo = UbicacionEnVivoPermiso(ctx)) { pos = it.punto }
    // ¿Todos (conductor y pasajeros) de la misma empresa? Para el saludo final.
    var empresaComun by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(nombres.keys) {
        val repo = pe.cooruta.app.data.repositorio.PerfilesRepositorio()
        val uids = (pasajeros.map { it.pasajeroId } + listOfNotNull(repo.miUid)).toSet()
        val perfiles = repo.perfiles(uids)
        val claves = uids.map { perfiles[it]?.empresaClave.orEmpty() }.toSet()
        empresaComun = if (pasajeros.isNotEmpty() && claves.size == 1 && claves.first().isNotBlank())
            repo.miUid?.let { perfiles[it] }?.empresa else null
    }
    val pendientes = e.recorrido.recogidas.map { it.reservaId }.toSet()
    val destino = e.recorrido.linea.lastOrNull()
    LaunchedEffect(pos, lista, activa) {
        val yo = pos ?: return@LaunchedEffect
        // Pasajeros a bordo (ya recogidos) que se acercan a su bajada.
        pasajeros.filter { it.id !in pendientes }.forEach { s ->
            val b = s.bajadaPunto ?: return@forEach
            val n = nombres[s.id] ?: "tu pasajero"
            val d = pe.cooruta.app.data.metros(yo, b)
            if (d <= 60) decir("bajo_${s.id}", "$n, llegaste a ${s.bajada}. Gracias por viajar en CooRuta, que tengas ${saludoHora()}.")
            else if (d <= 200) decir("bajada_${s.id}", "Próxima parada, ${s.bajada}. $n termina su viaje.")
        }
        // Llegada al destino final, sin recogidas pendientes.
        if (destino != null && pendientes.isEmpty() && pe.cooruta.app.data.metros(yo, destino) <= 150) {
            val quien = empresaComun?.let { "Felicidades, equipo de $it" } ?: "Felicidades"
            decir("destino", "$quien, han llegado a su destino. Que tengan ${saludoHora()}.")
        }
    }
    val p = e.recorrido.proxima
    val r = e.recorrido.ruta
    // Tiempo hasta la próxima recogida, en vivo: distancia actual × 1,3 (calles) a la velocidad
    // media del recorrido calculado (o 25 km/h si aún no hay).
    val velocidad = r?.let { if ((it.segundosPrimerTramo ?: 0.0) > 30 && (it.metrosPrimerTramo ?: 0.0) > 0)
        it.metrosPrimerTramo!! / it.segundosPrimerTramo!! else null } ?: 6.9
    LaunchedEffect(p?.reservaId, pos, r?.segundosPrimerTramo, lista, activa) {
        if (p == null) {
            if (r != null && dichos.any { it.startsWith("cerca_") || it.startsWith("cinco_") || it.startsWith("inicio_") })
                decir("todos", "Todos los pasajeros recogidos. Continuemos a tu destino.")
            return@LaunchedEffect
        }
        val n = nombres[p.reservaId] ?: "tu pasajero"
        val lugar = pasajeros.firstOrNull { it.id == p.reservaId }?.subida?.let { " en $it" }.orEmpty()
        val m = pos?.let { pe.cooruta.app.data.metros(it, p.punto) * 1.3 } ?: r?.metrosPrimerTramo
        val seg = m?.let { it / velocidad } ?: r?.segundosPrimerTramo
        val min = seg?.let { kotlin.math.ceil(it / 60).toInt().coerceAtLeast(1) }
        when {
            m != null && m <= 200 -> decir("cerca_${p.reservaId}", "Llegando. Recoge a $n$lugar.")
            min != null && min <= 5 -> decir("cinco_${p.reservaId}",
                "Próxima parada a $min ${if (min == 1) "minuto" else "minutos"}, para recoger a $n$lugar.")
            min != null -> decir("inicio_${p.reservaId}", "Próxima parada en $min minutos, para recoger a $n.")
        }
    }
}

private fun UbicacionEnVivoPermiso(ctx: android.content.Context) = pe.cooruta.app.data.UbicacionEnVivo.tienePermiso(ctx)

/** "un buen día", "una buena tarde" o "una buena noche" según la hora. */
private fun saludoHora(): String = when (java.time.LocalTime.now().hour) {
    in 5..11 -> "un buen día"
    in 12..18 -> "una buena tarde"
    else -> "una buena noche"
}


/** Prepara la navegación de Google con las paradas del viaje y devuelve la acción para abrirla. */
@Composable
private fun rememberNavegar(e: EstadoViajeUi, alAvisar: (String) -> Unit): () -> Unit {
    val ctx = LocalContext.current
    var empresaNav by remember { mutableStateOf<String?>(null) }
    var miNombre by remember { mutableStateOf("") }
    LaunchedEffect(e.confirmadas.map { it.pasajeroId }) {
        val repo = pe.cooruta.app.data.repositorio.PerfilesRepositorio()
        val uids = (e.confirmadas.map { it.pasajeroId } + listOfNotNull(repo.miUid)).toSet()
        val perfiles = repo.perfiles(uids)
        miNombre = repo.miUid?.let { perfiles[it] }?.nombre?.substringBefore(" ").orEmpty()
        val claves = uids.map { perfiles[it]?.empresaClave.orEmpty() }.toSet()
        empresaNav = if (e.confirmadas.isNotEmpty() && claves.size == 1 && claves.first().isNotBlank())
            repo.miUid?.let { perfiles[it] }?.empresa else null
    }
    // Navegación de Google dentro de la app: recogidas pendientes en orden y luego el destino.
    fun navegar() {
        val conf = e.confirmadas.associateBy { it.id }
        fun nombre(id: String) = conf[id]?.nombrePasajero?.ifBlank { null } ?: "tu pasajero"
        val todasSubidas = e.recorrido.recogidas.map {
            pe.cooruta.app.ParadaNav(it.punto, pe.cooruta.app.ParadaNav.Tipo.SUBE, nombre(it.reservaId), conf[it.reservaId]?.subida.orEmpty(),
                conf[it.reservaId]?.pasajeroId.orEmpty())
        }
        // Solo las reservas confirmadas de hoy de esta ruta (las canceladas ya no están).
        val bajadas = e.confirmadas.filter { it.rutaId == e.abierto?.rutaId }.mapNotNull { s ->
            s.bajadaPunto?.let { pe.cooruta.app.ParadaNav(it, pe.cooruta.app.ParadaNav.Tipo.BAJA, nombre(s.id), s.bajada, s.pasajeroId) }
        }
        val linea = e.recorrido.linea
        // Quien sube a ≤ 300 m de donde está el conductor no es parada: se le espera antes de salir.
        val aqui = pe.cooruta.app.data.UbicacionEnVivo.actual.value ?: linea.firstOrNull()
        val (cercanos, subidas) = todasSubidas.partition { aqui != null && pe.cooruta.app.data.metros(aqui, it.punto) <= 300 }
        // Puerta a puerta: siempre la parada más cercana; cada bajada después de su subida.
        val intermedias = pe.cooruta.app.data.PlanificadorRecorrido.ordenarPuertaAPuerta(aqui, subidas + bajadas,
            { it.punto }, { it.uid }, { it.tipo == pe.cooruta.app.ParadaNav.Tipo.SUBE })
        // Destino final del conductor: siempre al final, aunque el recorrido aún no esté calculado.
        val destino = e.destinoConductor ?: linea.lastOrNull()
            ?: e.rutasHoy.firstOrNull { it.id == e.abierto?.rutaId }?.recorrido?.lastOrNull()
        val paradas = intermedias + listOfNotNull(destino?.let { pe.cooruta.app.ParadaNav(it, pe.cooruta.app.ParadaNav.Tipo.DESTINO) })
        pe.cooruta.app.NavegacionActivity.abrir(ctx, paradas, empresaNav, conductor = miNombre,
            pasajeros = e.confirmadas.map { nombre(it.id) }, cercanos = cercanos, viajeId = e.abierto?.viajeId.orEmpty(),
            // La ruta publicada (la que ven los pasajeros): la navegación intenta seguirla.
            guia = e.rutasHoy.firstOrNull { it.id == e.abierto?.rutaId }?.recorrido.orEmpty())?.let(alAvisar)
    }
    return { navegar() }
}

/** 01:10 → "1:10 a. m." */
private fun horaAmPm(t: java.time.LocalTime): String {
    val h = if (t.hour % 12 == 0) 12 else t.hour % 12
    return "%d:%02d %s".format(h, t.minute, if (t.hour < 12) "a. m." else "p. m.")
}
