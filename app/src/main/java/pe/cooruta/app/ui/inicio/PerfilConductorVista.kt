package pe.cooruta.app.ui.inicio

import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.CalendarToday
import androidx.compose.material.icons.outlined.Map
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material.icons.outlined.SmokeFree
import androidx.compose.material.icons.outlined.Spa
import androidx.compose.material.icons.outlined.Pets
import androidx.compose.material.icons.outlined.AcUnit
import androidx.compose.material.icons.outlined.Backpack
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import pe.cooruta.app.data.repositorio.MiRuta
import pe.cooruta.app.data.repositorio.PerfilPublico
import pe.cooruta.app.data.repositorio.Vehiculo
import pe.cooruta.app.ui.componentes.IconoAtras
import pe.cooruta.app.ui.componentes.IconoAuto
import pe.cooruta.app.ui.componentes.IconoPersona
import pe.cooruta.app.ui.componentes.IconoRuta
import pe.cooruta.app.ui.componentes.IconoVolante
import pe.cooruta.app.ui.horario.formatoHora
import pe.cooruta.app.ui.horario.resumenDias
import pe.cooruta.app.ui.theme.Coo

/*
 * Perfil público del conductor (lo ve el pasajero). Encabezado con foto y volante, vehículo,
 * preferencias en chips y sus rutas con acceso al mapa. Solo pinta: los datos vienen de PerfilConductor.
 */

@Composable
fun VistaPerfilConductor(
    p: PerfilPublico?,
    favorito: Boolean,
    rutas: List<MiRuta>?,
    alCerrar: () -> Unit,
    alAlternarFavorito: () -> Unit,
    alVerMapa: (MiRuta) -> Unit
) {
    val nombre = p?.nombre?.ifBlank { null } ?: "Conductor"
    // Barra fija arriba; solo el contenido se desplaza.
    Column(
        Modifier.fillMaxSize().background(Coo.Fondo)
            // Brillo azul suave detrás del encabezado.
            .background(Brush.verticalGradient(0f to Coo.AzulBoton.copy(alpha = 0.16f), 0.35f to Color.Transparent))
    ) {
        BarraPerfil("Perfil del conductor", alCerrar)
        LazyColumn(
            Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // Encabezado: foto con volante, nombre, rol, calificación y frase
            item { EncabezadoPerfil(p, nombre, esConductor = true) }
            // Agregar a mis conductores
            item {
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
                        .background(if (favorito) Coo.CampoActivo else Coo.AzulBoton)
                        .clickable(onClick = alAlternarFavorito).padding(vertical = 13.dp),
                    horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(if (favorito) "★ En mis conductores" else "☆ Agregar a mis conductores",
                        color = if (favorito) Coo.Advertencia else Coo.SobreBoton, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                }
            }
            // Vehículo
            p?.vehiculo?.takeIf { !it.vacio }?.let { v -> item { TarjetaVehiculo(v) } }
            // Preferencias
            if (!p?.preferencias.isNullOrEmpty()) item { TarjetaPreferencias(p!!.preferencias) }
            // Rutas
            item {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
                    IconoRuta(color = Coo.AzulUbicacion, lleno = false, tamano = 24.dp)
                    Spacer(Modifier.width(8.dp))
                    Text("Sus rutas", color = Coo.Texto, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                }
            }
            when {
                rutas == null -> item { Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Coo.AzulUbicacion) } }
                rutas.isEmpty() -> item { Text("No tiene rutas públicas activas.", color = Coo.TextoTenue, fontSize = 14.sp) }
                else -> items(rutas, key = { it.id }) { r -> TarjetaRutaPublica(r) { alVerMapa(r) } }
            }
            item { NotaBuscar() }
        }
    }
}

/** Foto con insignia del rol, nombre (con check si su empresa está verificada), rol, calificación y frase. */
@Composable
private fun EncabezadoPerfil(p: PerfilPublico?, nombre: String, esConductor: Boolean) {
    Row(verticalAlignment = Alignment.Top) {
        Box {
            Box(Modifier.size(100.dp).border(3.dp, Coo.AzulBoton, CircleShape).padding(5.dp)) {
                Avatar(p?.foto, nombre, 90.dp, 34.sp)
            }
            Box(
                Modifier.align(Alignment.BottomEnd).offset(x = 2.dp, y = 2.dp).size(32.dp)
                    .border(3.dp, Coo.Fondo, CircleShape).clip(CircleShape).background(Coo.AzulBoton),
                contentAlignment = Alignment.Center
            ) {
                if (esConductor) IconoVolante(color = Color.White, lleno = true, tamano = 18.dp)
                else IconoPersona(color = Color.White, lleno = true, tamano = 18.dp)
            }
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(nombre, color = Coo.Texto, fontSize = 22.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f, fill = false))
                if (!p?.empresaDominio.isNullOrBlank()) {
                    Spacer(Modifier.width(6.dp))
                    InsigniaVerificado()
                }
            }
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Chip(texto = if (esConductor) "Conductor" else "Pasajero", resaltado = true) {
                    if (esConductor) IconoVolante(color = Coo.AzulUbicacion, lleno = false, tamano = 16.dp)
                    else IconoPersona(color = Coo.AzulUbicacion, lleno = false, tamano = 16.dp)
                }
                Spacer(Modifier.width(8.dp))
                Text(p?.calificacionTexto() ?: "", color = Coo.TextoTenue, fontSize = 13.sp)
            }
            p?.empresa?.ifBlank { null }?.let { emp ->
                Text(if (p.empresaDominio.isNotBlank()) "$emp · verificado" else "$emp (declarado)",
                    color = if (p.empresaDominio.isNotBlank()) Coo.Exito else Coo.Advertencia, fontSize = 13.sp,
                    modifier = Modifier.padding(top = 4.dp))
            }
            p?.acercaDe?.ifBlank { null }?.let {
                Text("“$it”", color = Coo.TextoTenue, fontSize = 14.sp, fontStyle = FontStyle.Italic, lineHeight = 20.sp,
                    modifier = Modifier.padding(top = 8.dp))
            }
        }
    }
}

/** Perfil público del pasajero (lo ve el conductor): mismo diseño, sin vehículo ni rutas. */
@Composable
fun VistaPerfilPasajero(p: PerfilPublico?, nombre: String, alCerrar: () -> Unit) {
    // Barra fija arriba; solo el contenido se desplaza.
    Column(
        Modifier.fillMaxSize().background(Coo.Fondo)
            .background(Brush.verticalGradient(0f to Coo.AzulBoton.copy(alpha = 0.16f), 0.35f to Color.Transparent))
    ) {
        BarraPerfil("Perfil del pasajero", alCerrar)
        LazyColumn(
            Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            item { EncabezadoPerfil(p, nombre, esConductor = false) }
            if (p == null) item {
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Coo.AzulUbicacion) }
            }
            if (!p?.preferencias.isNullOrEmpty()) item { TarjetaPreferencias(p!!.preferencias) }
        }
    }
}

/** Barra superior fija: volver y título centrado. */
@Composable
private fun BarraPerfil(titulo: String, alCerrar: () -> Unit) {
    Row(Modifier.fillMaxWidth().statusBarsPadding().padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = alCerrar, modifier = Modifier.semantics { contentDescription = "Volver" }) {
            IconoAtras(color = Coo.Texto)
        }
        Text(titulo, color = Coo.Texto, fontSize = 18.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.weight(1f).padding(end = 48.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
    }
}

// ------------------------------------------------------------------ tarjetas

@Composable
private fun Tarjeta(contenido: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Coo.Campo)
            .border(1.dp, Coo.BordeSuave, RoundedCornerShape(18.dp)).padding(16.dp)
    ) { contenido() }
}

@Composable
private fun TituloTarjeta(texto: String, icono: @Composable () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        icono()
        Spacer(Modifier.width(10.dp))
        Text(texto, color = Coo.Texto, fontSize = 17.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun TarjetaVehiculo(v: Vehiculo) {
    Tarjeta {
        TituloTarjeta("Su vehículo") { IconoAuto(color = Coo.AzulUbicacion, lleno = false, tamano = 22.dp) }
        Spacer(Modifier.height(12.dp))
        Row(Modifier.height(IntrinsicSize.Min), verticalAlignment = Alignment.CenterVertically) {
            FotoAuto(v.foto)
            Box(Modifier.padding(horizontal = 14.dp).width(1.dp).fillMaxHeight().background(Coo.Borde))
            Column(Modifier.weight(1f)) {
                Text("${v.marca} ${v.modelo}".trim().replaceFirstChar { it.uppercase() }, color = Coo.Texto, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                val detalle = listOf(v.anio, v.color.lowercase().replaceFirstChar { it.uppercase() }.let { if (it.isBlank()) "" else "Color $it" })
                    .filter { it.isNotBlank() }.joinToString(" · ")
                if (detalle.isNotBlank()) Text(detalle, color = Coo.TextoTenue, fontSize = 13.sp)
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (v.placa.isNotBlank()) {
                        Text(v.placa.uppercase(), color = Coo.Texto, fontSize = 13.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp,
                            modifier = Modifier.clip(RoundedCornerShape(8.dp)).border(1.dp, Coo.Borde, RoundedCornerShape(8.dp))
                                .background(Coo.CampoActivo).padding(horizontal = 8.dp, vertical = 4.dp))
                        Spacer(Modifier.width(10.dp))
                    }
                    if (v.capacidad > 0) {
                        IconoPersona(color = Coo.TextoTenue, lleno = true, tamano = 16.dp)
                        Spacer(Modifier.width(4.dp))
                        Text("${v.capacidad} pasajeros", color = Coo.TextoTenue, fontSize = 13.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun FotoAuto(foto: String) {
    val incluida = autoIncluido(foto)
    val img = remember(foto) {
        foto.takeIf { it.isNotBlank() && !it.startsWith("auto:") }?.let { f ->
            runCatching {
                android.util.Base64.decode(f, android.util.Base64.DEFAULT)
                    .let { android.graphics.BitmapFactory.decodeByteArray(it, 0, it.size) }?.asImageBitmap()
            }.getOrNull()
        }
    }
    Box(
        Modifier.size(width = 112.dp, height = 80.dp).clip(RoundedCornerShape(12.dp)).background(Coo.CampoActivo),
        contentAlignment = Alignment.Center
    ) {
        if (incluida != null) Image(androidx.compose.ui.res.painterResource(incluida), "Foto del vehículo",
            contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        else if (img != null) Image(img, "Foto del vehículo", contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        else IconoAuto(color = Coo.AzulUbicacion, lleno = true, tamano = 44.dp)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TarjetaPreferencias(lista: List<String>) {
    Tarjeta {
        TituloTarjeta("Sus preferencias") { IconoAjustes(Coo.AzulUbicacion, 22.dp) }
        Spacer(Modifier.height(12.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            lista.forEach { pref -> Chip(pref) { IconoPreferencia(pref, Coo.AzulUbicacion, 16.dp) } }
        }
    }
}

@Composable
private fun TarjetaRutaPublica(r: MiRuta, alVerMapa: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(IntrinsicSize.Min).clip(RoundedCornerShape(18.dp)).background(Coo.Campo)
            .border(1.dp, Coo.BordeSuave, RoundedCornerShape(18.dp)).clickable(onClick = alVerMapa)
    ) {
        // Franja de color a la izquierda
        Box(Modifier.width(4.dp).fillMaxHeight().background(if (r.activa) Coo.AzulBoton else Coo.Borde))
        Box(Modifier.weight(1f)) {
            // Boceto de recorrido, decorativo, a la derecha
            BocetoRecorrido(Modifier.align(Alignment.CenterEnd).padding(end = 12.dp).size(width = 92.dp, height = 64.dp))
            Column(Modifier.padding(start = 14.dp, end = 14.dp, top = 14.dp, bottom = 14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(34.dp).clip(CircleShape).background(Coo.CampoActivo), contentAlignment = Alignment.Center) {
                        IconoAuto(color = Coo.AzulUbicacion, lleno = true, tamano = 18.dp)
                    }
                    Spacer(Modifier.width(10.dp))
                    Text(r.alias.ifBlank { "Ruta" }, color = Coo.Texto, fontSize = 16.sp, fontWeight = FontWeight.Bold,
                        maxLines = 1, modifier = Modifier.weight(1f))
                    if (r.activa) {
                        Spacer(Modifier.width(8.dp))
                        Row(
                            Modifier.clip(RoundedCornerShape(50)).background(Coo.ExitoFondo).padding(horizontal = 10.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(Modifier.size(7.dp).clip(CircleShape).background(Coo.Exito))
                            Spacer(Modifier.width(6.dp))
                            Text("Activa", color = Coo.Exito, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconoCalendario(Coo.TextoTenue, 16.dp)
                    Spacer(Modifier.width(8.dp))
                    Text("${resumenDias(r.dias)} · sale ${formatoHora(r.salida)}", color = Coo.TextoTenue, fontSize = 13.sp)
                }
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconoPersona(color = Coo.TextoTenue, lleno = false, tamano = 16.dp)
                    Spacer(Modifier.width(8.dp))
                    Text("${r.asientosLibres} de ${r.asientos} asientos libres", color = Coo.TextoTenue, fontSize = 13.sp)
                }
                Spacer(Modifier.height(12.dp))
                Row(
                    Modifier.clip(RoundedCornerShape(12.dp)).background(Coo.AzulBoton.copy(alpha = 0.16f))
                        .border(1.dp, Coo.AzulBoton.copy(alpha = 0.45f), RoundedCornerShape(12.dp))
                        .clickable(onClick = alVerMapa).padding(horizontal = 12.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconoMapa(Coo.AzulUbicacion, 18.dp)
                    Spacer(Modifier.width(8.dp))
                    Text("Ver recorrido en el mapa", color = Coo.Texto, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.width(6.dp))
                    pe.cooruta.app.ui.componentes.IconoAdelante(color = Coo.Texto, tamano = 16.dp)
                }
            }
        }
    }
}

@Composable
private fun NotaBuscar() {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Coo.Campo)
            .border(1.dp, Coo.BordeSuave, RoundedCornerShape(16.dp)).padding(14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(34.dp).clip(CircleShape).background(Coo.CampoActivo), contentAlignment = Alignment.Center) {
            pe.cooruta.app.ui.componentes.IconoM(androidx.compose.material.icons.Icons.Outlined.Info, Coo.AzulUbicacion, 20.dp)
        }
        Spacer(Modifier.width(12.dp))
        Text(buildAnnotatedString {
            append("Para pedir asiento, busca tu destino en ")
            withStyle(SpanStyle(color = Coo.AzulUbicacion, fontWeight = FontWeight.Bold)) { append("Buscar") }
            append(": sus rutas que coincidan ")
            withStyle(SpanStyle(color = Coo.Texto, fontWeight = FontWeight.Bold)) { append("saldrán primero") }
            append(".")
        }, color = Coo.TextoTenue, fontSize = 13.sp, lineHeight = 18.sp)
    }
}

@Composable
private fun Chip(texto: String, resaltado: Boolean = false, icono: @Composable () -> Unit) {
    Row(
        Modifier.clip(RoundedCornerShape(50))
            .background(if (resaltado) Coo.AzulBoton.copy(alpha = 0.14f) else Coo.CampoActivo)
            .border(1.dp, if (resaltado) Coo.AzulBoton.copy(alpha = 0.5f) else Coo.Borde, RoundedCornerShape(50))
            .padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        icono()
        Spacer(Modifier.width(6.dp))
        Text(texto, color = if (resaltado) Coo.AzulUbicacion else Coo.Texto, fontSize = 13.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun InsigniaVerificado() {
    Box(
        Modifier.size(20.dp).clip(CircleShape).background(Coo.AzulBoton)
            .semantics { contentDescription = "Empresa verificada" },
        contentAlignment = Alignment.Center
    ) {
        Canvas(Modifier.size(12.dp)) {
            val w = size.width
            drawPath(Path().apply { moveTo(w * 0.18f, w * 0.52f); lineTo(w * 0.42f, w * 0.74f); lineTo(w * 0.84f, w * 0.28f) },
                Color.White, style = Stroke(w * 0.16f, cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
    }
}

// ------------------------------------------------------------------ íconos (cuadrícula de 24)

private fun DrawScope.trazo(u: Float) = Stroke(1.8f * u, cap = StrokeCap.Round, join = StrokeJoin.Round)
private fun o(x: Float, y: Float, u: Float) = Offset(x * u, y * u)

@Composable
private fun Icono(color: Color, tamano: Dp, dibujar: DrawScope.(u: Float) -> Unit) =
    Canvas(Modifier.size(tamano)) { dibujar(size.minDimension / 24f) }

/** Tres barras con perillas: preferencias. */
@Composable
private fun IconoAjustes(color: Color, tamano: Dp) =
    pe.cooruta.app.ui.componentes.IconoM(Icons.Outlined.Tune, color, tamano)

@Composable
private fun IconoCalendario(color: Color, tamano: Dp) =
    pe.cooruta.app.ui.componentes.IconoM(Icons.Outlined.CalendarToday, color, tamano)

/** Mapa plegado en tres paneles. */
@Composable
private fun IconoMapa(color: Color, tamano: Dp) =
    pe.cooruta.app.ui.componentes.IconoM(Icons.Outlined.Map, color, tamano)

/** Ícono según la preferencia (las de PREFERENCIAS_VIAJE); otra cualquiera lleva un punto. */
@Composable
private fun IconoPreferencia(pref: String, color: Color, tamano: Dp) =
    pe.cooruta.app.ui.componentes.IconoM(
        when (pref) {
            "Música suave" -> Icons.Outlined.MusicNote
            "No se fuma" -> Icons.Outlined.SmokeFree
            "Viaje tranquilo" -> Icons.Outlined.Spa
            "Acepto mascotas" -> Icons.Outlined.Pets
            "Aire acondicionado" -> Icons.Outlined.AcUnit
            "Espacio para mochila" -> Icons.Outlined.Backpack
            else -> Icons.Outlined.CheckCircle
        }, color, tamano)

/** Recorrido decorativo: dos pines unidos por una curva, sobre una cuadrícula tenue. */
@Composable
private fun BocetoRecorrido(modifier: Modifier) {
    Canvas(modifier) {
        val w = size.width; val h = size.height
        val tenue = Coo.Borde.copy(alpha = 0.5f)
        drawLine(tenue, Offset(0f, h * 0.35f), Offset(w, h * 0.2f), 2f)
        drawLine(tenue, Offset(w * 0.3f, 0f), Offset(w * 0.45f, h), 2f)
        drawLine(tenue, Offset(w * 0.7f, 0f), Offset(w * 0.6f, h), 2f)
        val inicio = Offset(w * 0.12f, h * 0.78f); val fin = Offset(w * 0.86f, h * 0.24f)
        drawPath(Path().apply {
            moveTo(inicio.x, inicio.y)
            cubicTo(w * 0.35f, h * 0.95f, w * 0.4f, h * 0.35f, w * 0.6f, h * 0.5f)
            cubicTo(w * 0.72f, h * 0.6f, w * 0.78f, h * 0.35f, fin.x, fin.y)
        }, Brush.horizontalGradient(listOf(Coo.AzulBoton.copy(alpha = 0.6f), Coo.AzulUbicacion)),
            style = Stroke(w * 0.035f, cap = StrokeCap.Round))
        listOf(inicio to Coo.TextoTenue, fin to Coo.AzulUbicacion).forEach { (c, col) ->
            drawCircle(col, w * 0.075f, c); drawCircle(Coo.Campo, w * 0.03f, c)
        }
    }
}
