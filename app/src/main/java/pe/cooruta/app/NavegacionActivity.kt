package pe.cooruta.app

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.gms.maps.GoogleMap
import com.google.android.libraries.navigation.CustomRoutesOptions
import com.google.android.libraries.navigation.NavigationApi
import com.google.android.libraries.navigation.NavigationView
import com.google.android.libraries.navigation.Navigator
import com.google.android.libraries.navigation.Waypoint
import kotlinx.coroutines.launch
import pe.cooruta.app.data.Punto
import java.util.Locale

/** Una parada de la navegación: dónde, qué pasa ahí y con quién. */
data class ParadaNav(val punto: Punto, val tipo: Tipo, val nombre: String = "", val lugar: String = "", val uid: String = "") {
    enum class Tipo { SUBE, BAJA, DESTINO }
}

/**
 * Navegación de Google (Navigation SDK) dentro de CooRuta. Sin indicaciones de giro:
 * la voz es de CooRuta (tráfico, próxima parada, quién sube o baja, llegada).
 */
class NavegacionActivity : ComponentActivity() {

    private lateinit var vista: NavigationView
    private var navegador: Navigator? = null
    private var paradas: List<ParadaNav> = emptyList()
    private var actual = 0
    private val dichos = mutableSetOf<String>()
    private var tts: TextToSpeech? = null
    private var ttsListo = false
    private val pendientes = mutableListOf<String>()

    private val alLlegar = Navigator.ArrivalListener { ev ->
        val n = navegador ?: return@ArrivalListener
        paradas.getOrNull(actual)?.let { decir("llego_$actual", fraseLlegada(it)) }
        if (ev.isFinalDestination) {
            n.stopGuidance()
            finish()
        } else {
            actual++
            n.continueToNextDestination()
            n.startGuidance()
            anunciarSiguiente()
        }
    }

    private val alCambiarTiempo = Navigator.RemainingTimeOrDistanceChangedListener { revisarCercania() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        abierta = true
        // La voz la da Gemini TTS (VozCooRuta), con la del teléfono como respaldo.
        ttsListo = true
        vista = NavigationView(this)
        val raiz = FrameLayout(this)
        raiz.addView(vista, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        val d = resources.displayMetrics.density
        val volver = TextView(this).apply {
            text = "✕  Volver a CooRuta"
            setTextColor(Color.WHITE); textSize = 15f
            setBackgroundColor(Color.parseColor("#E6131A26"))
            setPadding((16 * d).toInt(), (12 * d).toInt(), (16 * d).toInt(), (12 * d).toInt())
            setOnClickListener { finish() }
        }
        raiz.addView(volver, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = (110 * d).toInt() })
        setContentView(raiz)
        vista.onCreate(savedInstanceState)

        paradas = leer(intent)
        if (paradas.isEmpty()) { finish(); return }

        NavigationApi.getNavigator(this, object : NavigationApi.NavigatorListener {
            override fun onNavigatorReady(n: Navigator) { navegador = n; iniciar(n) }
            override fun onError(codigo: Int) {
                val msg = when (codigo) {
                    NavigationApi.ErrorCode.TERMS_NOT_ACCEPTED -> "Debes aceptar los términos de Google para navegar."
                    NavigationApi.ErrorCode.NOT_AUTHORIZED -> "La clave de Google no tiene permiso para Navigation SDK."
                    NavigationApi.ErrorCode.LOCATION_PERMISSION_MISSING -> "Falta el permiso de ubicación."
                    else -> "No se pudo iniciar la navegación (código $codigo)."
                }
                Toast.makeText(this@NavegacionActivity, msg, Toast.LENGTH_LONG).show()
                finish()
            }
        })
    }

    @Suppress("DEPRECATION")
    private fun iniciar(n: Navigator) {
        val puntos = paradas.mapNotNull { p ->
            runCatching { Waypoint.builder().setLatLng(p.punto.latitud, p.punto.longitud).setTitle(titulo(p)).build() }.getOrNull()
        }
        if (puntos.size != paradas.size) { Toast.makeText(this, "Hay paradas no válidas.", Toast.LENGTH_LONG).show(); finish(); return }
        // Sin giros: solo alertas importantes de Google; el resto lo dice CooRuta.
        n.setAudioGuidance(Navigator.AudioGuidance.VOICE_ALERTS_ONLY)
        n.addArrivalListener(alLlegar)
        n.addRemainingTimeOrDistanceChangedListener(20, 50, alCambiarTiempo)
        // Primero se intenta navegar por la MISMA ruta que ven los pasajeros (route token de Routes API
        // con puntos de paso de la ruta publicada). Si no se puede, Google calcula el camino como antes.
        lifecycleScope.launch {
            val token = runCatching { tokenRuta() }.getOrNull()
            fun alResultado(estado: Navigator.RouteStatus, conToken: Boolean) {
                if (estado == Navigator.RouteStatus.OK) {
                    vista.getMapAsync { mapa -> mapa.followMyLocation(GoogleMap.CameraPerspective.TILTED) }
                    ponerPines()
                    seguirPasajeros()
                    n.startGuidance()
                    anunciarInicio()
                } else if (conToken) {
                    n.setDestinations(puntos).setOnResultListener { alResultado(it, false) }
                } else {
                    Toast.makeText(this@NavegacionActivity, "No se encontró un camino ($estado).", Toast.LENGTH_LONG).show()
                    finish()
                }
            }
            val conRuta = token?.let {
                runCatching {
                    n.setDestinations(puntos, CustomRoutesOptions.builder().setRouteToken(it)
                        .setTravelMode(CustomRoutesOptions.TravelMode.DRIVING).build())
                }.getOrNull()
            }
            if (conRuta != null) conRuta.setOnResultListener { alResultado(it, true) }
            else n.setDestinations(puntos).setOnResultListener { alResultado(it, false) }
        }
    }

    /**
     * Pide a Routes API la ruta desde donde está el conductor, por sus paradas, pasando por puntos
     * de la ruta publicada (la que ven los pasajeros), y devuelve su route token.
     * Null si no hay ruta publicada, ubicación o puntos de paso útiles.
     */
    private suspend fun tokenRuta(): String? {
        val guia = leerGuia(intent)
        if (guia.size < 2) return null
        val origen = pe.cooruta.app.data.UbicacionEnVivo.actual.value ?: return null
        val tramos = puntosVia(origen, paradas.map { it.punto }, guia)
        if (tramos.all { it.isEmpty() }) return null
        fun wp(p: Punto, via: Boolean = false): Map<String, Any> {
            val m = mutableMapOf<String, Any>("location" to mapOf("latLng" to mapOf("latitude" to p.latitud, "longitude" to p.longitud)))
            if (via) m["via"] = true
            return m
        }
        // Orden: puntos de paso del tramo k y luego la parada k (la última parada es el destino).
        val intermedios = mutableListOf<Map<String, Any>>()
        paradas.forEachIndexed { k, p ->
            tramos.getOrNull(k).orEmpty().forEach { intermedios += wp(it, via = true) }
            if (k < paradas.lastIndex) intermedios += wp(p.punto)
        }
        val r = pe.cooruta.app.data.remoto.ClienteGoogle.api.recorrido(
            clave = BuildConfig.GOOGLE_MAPS_KEY,
            campos = "routes.routeToken",
            cuerpo = mapOf(
                "origin" to wp(origen),
                "destination" to wp(paradas.last().punto),
                "intermediates" to intermedios,
                "travelMode" to "DRIVE",
                "routingPreference" to "TRAFFIC_AWARE"
            )
        )
        return r.routes?.firstOrNull()?.routeToken?.takeIf { it.isNotBlank() }
    }

    /**
     * Puntos de paso por tramo (origen→parada 1, parada 1→parada 2, …) tomados de la ruta publicada.
     * Solo se usan en los tramos cuyos dos extremos quedan a ≤ 250 m de esa ruta y van en su sentido.
     * Uno cada ~400 m, sin pasar de 25 intermedios en total (límite de Routes API).
     */
    private fun puntosVia(origen: Punto, destinos: List<Punto>, guia: List<Punto>): List<List<Punto>> {
        fun cercano(p: Punto): Pair<Int, Double> {
            var mejor = 0; var d = Double.MAX_VALUE
            guia.forEachIndexed { i, g -> val m = pe.cooruta.app.data.metros(g, p); if (m < d) { d = m; mejor = i } }
            return mejor to d
        }
        val extremos = listOf(origen) + destinos
        val tramos = MutableList(destinos.size) { mutableListOf<Punto>() }
        for (k in destinos.indices) {
            val (ia, da) = cercano(extremos[k]); val (ib, db) = cercano(extremos[k + 1])
            if (da > 250 || db > 250 || ib - ia < 2) continue
            var acumulado = 0.0
            for (i in ia + 1 until ib) {
                acumulado += pe.cooruta.app.data.metros(guia[i - 1], guia[i])
                val lejosDeLosExtremos = pe.cooruta.app.data.metros(guia[i], extremos[k]) > 150 &&
                    pe.cooruta.app.data.metros(guia[i], extremos[k + 1]) > 150
                if (acumulado >= 400 && lejosDeLosExtremos) { tramos[k] += guia[i]; acumulado = 0.0 }
            }
        }
        // Respeta el límite de 25 intermedios (paradas + puntos de paso), repartiendo los puntos de paso.
        val cupo = (25 - (destinos.size - 1)).coerceAtLeast(0)
        val total = tramos.sumOf { it.size }
        if (total > cupo) {
            val factor = cupo.toDouble() / total
            for (k in tramos.indices) {
                val t = tramos[k]
                val quedan = kotlin.math.floor(t.size * factor).toInt()
                val elegidos = if (quedan <= 0) emptyList() else List(quedan) { j -> t[(j * t.size) / quedan] }
                tramos[k] = elegidos.toMutableList()
            }
        }
        return tramos
    }

    /** Al empezar: cómo está el tráfico y cuál es la primera parada. */
    private fun anunciarInicio() {
        lifecycleScope.launch {
            val estado = estadoTrafico(paradas.first().punto, paradas.last().punto)
            val conductor = intent.getStringExtra(CONDUCTOR).orEmpty()
            val saludo = when (java.time.LocalTime.now().hour) { in 5..11 -> "Buenos días"; in 12..18 -> "Buenas tardes"; else -> "Buenas noches" }
            val nombres = intent.getStringArrayExtra(PASAJEROS).orEmpty().filter { it.isNotBlank() }
            val pasajeros = when (nombres.size) {
                0 -> ""
                1 -> " Hoy llevas 1 pasajero: ${nombres[0]}."
                else -> " Hoy llevas ${nombres.size} pasajeros: ${nombres.dropLast(1).joinToString(", ")} y ${nombres.last()}."
            }
            val trafico = estado?.let { " El tráfico hoy está $it." }.orEmpty()
            decir("inicio", "$saludo${if (conductor.isNotBlank()) ", $conductor" else ""}.$trafico$pasajeros")
            // Pasajeros que suben donde está el conductor (≤ 300 m): esperarlos antes de salir.
            val cerca = intent.getStringArrayExtra(CERCANOS).orEmpty()
            val lugaresCerca = intent.getStringArrayExtra(CERCANOS_LUGAR).orEmpty()
            cerca.forEachIndexed { i, n ->
                val l = lugaresCerca.getOrNull(i)?.takeIf { it.isNotBlank() }?.let { ", en $it" }.orEmpty()
                decir("cerca_inicio_$i", "$n sube cerca de aquí$l. Espéralo antes de salir.")
            }
            anunciarSiguiente()
        }
    }

    private fun anunciarSiguiente() {
        val p = paradas.getOrNull(actual) ?: return
        if (p.tipo == ParadaNav.Tipo.BAJA) return
        val min = minutosRestantes()
        decir("sig_$actual", when (p.tipo) {
            ParadaNav.Tipo.SUBE -> "Próxima parada${min?.let { " en $it" }.orEmpty()}, para recoger a ${p.nombre}${enLugar(p)}."
            ParadaNav.Tipo.BAJA -> "Próxima parada${min?.let { " en $it" }.orEmpty()}, ${p.lugar}. ${p.nombre} termina su viaje."
            ParadaNav.Tipo.DESTINO -> "Siguiente: tu destino${min?.let { ", en $it" }.orEmpty()}."
        })
    }

    /** A 5 minutos y al llegar a la parada actual (según la navegación de Google). */
    private fun revisarCercania() {
        val td = runCatching { navegador?.currentTimeAndDistance }.getOrNull() ?: return
        val p = paradas.getOrNull(actual) ?: return
        val seg = td.seconds; val m = td.meters
        if (m in 1..200) {
            if (p.tipo != ParadaNav.Tipo.DESTINO) decir("cerca_$actual", when (p.tipo) {
                ParadaNav.Tipo.SUBE -> "Llegando. Recoge a ${p.nombre}${enLugar(p)}."
                else -> "Próxima parada, ${p.lugar}. ${p.nombre} termina su viaje."
            })
        } else if (seg in 1..300 && p.tipo != ParadaNav.Tipo.BAJA) {
            val min = ((seg + 59) / 60).coerceAtLeast(1)
            val t = "$min ${if (min == 1) "minuto" else "minutos"}"
            decir("cinco_$actual", when (p.tipo) {
                ParadaNav.Tipo.SUBE -> "Próxima parada a $t, para recoger a ${p.nombre}${enLugar(p)}."
                ParadaNav.Tipo.BAJA -> "Próxima parada a $t, ${p.lugar}. ${p.nombre} termina su viaje."
                ParadaNav.Tipo.DESTINO -> "Tu destino está a $t."
            })
        }
    }

    private fun fraseLlegada(p: ParadaNav) = when (p.tipo) {
        ParadaNav.Tipo.SUBE -> "Llegaste. ${p.nombre} sube aquí."
        ParadaNav.Tipo.BAJA -> "${p.nombre}, llegaste a ${p.lugar}. Gracias por viajar en CooRuta, que tengas ${saludo()}."
        ParadaNav.Tipo.DESTINO -> (intent.getStringExtra(EMPRESA)?.let { "Felicidades, equipo de $it" } ?: "Felicidades") +
            ", han llegado a su destino. Que tengan ${saludo()}."
    }

    private fun minutosRestantes(): String? {
        val s = runCatching { navegador?.currentTimeAndDistance?.seconds }.getOrNull() ?: return null
        if (s <= 0) return null
        val m = ((s + 59) / 60).coerceAtLeast(1)
        return "$m ${if (m == 1) "minuto" else "minutos"}"
    }

    private fun enLugar(p: ParadaNav) = if (p.lugar.isBlank()) "" else " en ${p.lugar}"
    /** Pin con la foto de perfil del pasajero: verde donde sube, amarillo donde baja. */
    private fun ponerPines() {
        // Paradas con pasajero + los que suben cerca (≤ 300 m): esos no son parada, pero también llevan pin.
        val cerca = run {
            val la = intent.getDoubleArrayExtra(CERCA_LATS) ?: DoubleArray(0)
            val ln = intent.getDoubleArrayExtra(CERCA_LNGS) ?: DoubleArray(0)
            val us = intent.getStringArrayExtra(CERCA_UIDS).orEmpty()
            val ns = intent.getStringArrayExtra(CERCANOS).orEmpty()
            val ls = intent.getStringArrayExtra(CERCANOS_LUGAR).orEmpty()
            la.indices.filter { it < ln.size }.map { k ->
                ParadaNav(Punto(la[k], ln[k]), ParadaNav.Tipo.SUBE, ns.getOrElse(k) { "" }, ls.getOrElse(k) { "" }, us.getOrElse(k) { "" })
            }
        }
        val conPasajero = paradas.filter { it.tipo != ParadaNav.Tipo.DESTINO } + cerca
        if (conPasajero.isEmpty()) return
        lifecycleScope.launch {
            val uids = conPasajero.map { it.uid }.filter { it.isNotBlank() }.toSet()
            val fotos = runCatching { pe.cooruta.app.data.repositorio.PerfilesRepositorio().perfiles(uids) }
                .getOrDefault(emptyMap()).mapValues { it.value.foto }
            vista.getMapAsync { mapa ->
                conPasajero.forEach { p ->
                    val sube = p.tipo == ParadaNav.Tipo.SUBE
                    runCatching {
                        mapa.addMarker(com.google.android.gms.maps.model.MarkerOptions()
                            .position(com.google.android.gms.maps.model.LatLng(p.punto.latitud, p.punto.longitud))
                            .icon(com.google.android.gms.maps.model.BitmapDescriptorFactory.fromBitmap(
                                pinFoto(fotos[p.uid], p.nombre, if (sube) Color.parseColor("#5CC98A") else Color.parseColor("#FFC857"))))
                            .anchor(0.5f, 1f).zIndex(10f)
                            .title("${p.nombre.ifBlank { "Pasajero" }} ${if (sube) "sube" else "baja"}")
                            .snippet(p.lugar))
                    }
                }
            }
        }
    }

    /** Posición en vivo de cada pasajero: su foto en un círculo azul que se mueve. */
    private val marcasVivas = mutableMapOf<String, com.google.android.gms.maps.model.Marker>()
    private fun seguirPasajeros() {
        val vid = intent.getStringExtra(VIAJE).orEmpty()
        if (vid.isBlank()) return
        val nombres = (paradas + run {
            val us = intent.getStringArrayExtra(CERCA_UIDS).orEmpty(); val ns = intent.getStringArrayExtra(CERCANOS).orEmpty()
            us.indices.map { ParadaNav(Punto(0.0, 0.0), ParadaNav.Tipo.SUBE, ns.getOrElse(it) { "" }, "", us[it]) }
        }).filter { it.uid.isNotBlank() }.associate { it.uid to it.nombre }
        lifecycleScope.launch {
            val fotos = runCatching { pe.cooruta.app.data.repositorio.PerfilesRepositorio().perfiles(nombres.keys) }
                .getOrDefault(emptyMap()).mapValues { it.value.foto }
            val iconos = mutableMapOf<String, com.google.android.gms.maps.model.BitmapDescriptor>()
            pe.cooruta.app.data.repositorio.ViajesRepositorio().pasajerosUbicacionEnVivo(vid).collect { ubicaciones ->
                vista.getMapAsync { mapa ->
                    (marcasVivas.keys - ubicaciones.keys).forEach { marcasVivas.remove(it)?.remove() }
                    ubicaciones.forEach { (uid, p) ->
                        val pos = com.google.android.gms.maps.model.LatLng(p.latitud, p.longitud)
                        val m = marcasVivas[uid]
                        if (m != null) m.position = pos
                        else runCatching {
                            val icono = iconos.getOrPut(uid) {
                                com.google.android.gms.maps.model.BitmapDescriptorFactory.fromBitmap(
                                    fotoVivo(fotos[uid], nombres[uid].orEmpty()))
                            }
                            mapa.addMarker(com.google.android.gms.maps.model.MarkerOptions().position(pos).icon(icono)
                                .anchor(0.5f, 0.5f).zIndex(20f).title("${nombres[uid]?.ifBlank { null } ?: "Pasajero"} · ahora"))
                        }.getOrNull()?.let { marcasVivas[uid] = it }
                    }
                }
            }
        }
    }

    /** Foto redonda con borde azul y halo: el pasajero en este momento. */
    private fun fotoVivo(foto: String?, nombre: String): android.graphics.Bitmap {
        val d = resources.displayMetrics.density
        val r = 18 * d; val borde = 3 * d; val halo = 7 * d
        val lado = (2 * (r + borde + halo)).toInt()
        val bmp = android.graphics.Bitmap.createBitmap(lado, lado, android.graphics.Bitmap.Config.ARGB_8888)
        val c = android.graphics.Canvas(bmp); val cx = lado / 2f
        val azul = Color.parseColor("#556FF7")
        c.drawCircle(cx, cx, r + borde + halo, android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = azul; alpha = 70 })
        c.drawCircle(cx, cx, r + borde, android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = azul })
        val imagen = fotoBitmap(foto)
        if (imagen != null) {
            val l = (2 * r).toInt()
            val esc = android.graphics.Bitmap.createScaledBitmap(imagen, l, l, true)
            c.drawCircle(cx, cx, r, android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                shader = android.graphics.BitmapShader(esc, android.graphics.Shader.TileMode.CLAMP, android.graphics.Shader.TileMode.CLAMP)
                    .apply { setLocalMatrix(android.graphics.Matrix().apply { setTranslate(cx - r, cx - r) }) }
            })
        } else {
            c.drawCircle(cx, cx, r, android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#131A26") })
            val t = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE; textSize = 16 * d; textAlign = android.graphics.Paint.Align.CENTER
                typeface = android.graphics.Typeface.DEFAULT_BOLD
            }
            c.drawText(nombre.trim().firstOrNull()?.uppercase() ?: "?", cx, cx - (t.descent() + t.ascent()) / 2, t)
        }
        return bmp
    }

    private fun fotoBitmap(foto: String?): android.graphics.Bitmap? = runCatching {
        when {
            foto.isNullOrBlank() -> null
            foto.startsWith("avatar:") -> foto.removePrefix("avatar:").toIntOrNull()
                ?.let { pe.cooruta.app.ui.inicio.AVATARES.getOrNull(it - 1) }
                ?.let { android.graphics.BitmapFactory.decodeResource(resources, it) }
            else -> android.util.Base64.decode(foto, android.util.Base64.DEFAULT)
                .let { android.graphics.BitmapFactory.decodeByteArray(it, 0, it.size) }
        }
    }.getOrNull()

    /** Gota de color con la foto redonda dentro (o la inicial si no hay foto). */
    private fun pinFoto(foto: String?, nombre: String, color: Int): android.graphics.Bitmap {
        val d = resources.displayMetrics.density
        val r = 24 * d; val borde = 3 * d; val punta = 12 * d
        val w = (2 * (r + borde)).toInt(); val h = (2 * (r + borde) + punta).toInt()
        val bmp = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888)
        val c = android.graphics.Canvas(bmp)
        val cx = w / 2f; val cy = r + borde
        val pintura = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { this.color = color }
        val gota = android.graphics.Path().apply {
            moveTo(cx - r * 0.5f, cy + r * 0.8f); lineTo(cx, h.toFloat()); lineTo(cx + r * 0.5f, cy + r * 0.8f); close()
        }
        c.drawPath(gota, pintura)
        c.drawCircle(cx, cy, r + borde, pintura)
        val imagen = fotoBitmap(foto)
        if (imagen != null) {
            val lado = (2 * r).toInt()
            val escalada = android.graphics.Bitmap.createScaledBitmap(imagen, lado, lado, true)
            val pinc = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                shader = android.graphics.BitmapShader(escalada, android.graphics.Shader.TileMode.CLAMP, android.graphics.Shader.TileMode.CLAMP)
                    .apply { setLocalMatrix(android.graphics.Matrix().apply { setTranslate(cx - r, cy - r) }) }
            }
            c.drawCircle(cx, cy, r, pinc)
        } else {
            c.drawCircle(cx, cy, r, android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { this.color = Color.parseColor("#131A26") })
            val letra = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                this.color = Color.WHITE; textSize = 20 * d; textAlign = android.graphics.Paint.Align.CENTER
                typeface = android.graphics.Typeface.DEFAULT_BOLD
            }
            c.drawText(nombre.trim().firstOrNull()?.uppercase() ?: "?", cx, cy - (letra.descent() + letra.ascent()) / 2, letra)
        }
        return bmp
    }

    private fun titulo(p: ParadaNav) = when (p.tipo) {
        ParadaNav.Tipo.SUBE -> "Recoger a ${p.nombre}"
        ParadaNav.Tipo.BAJA -> "Baja ${p.nombre}"
        ParadaNav.Tipo.DESTINO -> "Destino"
    }
    private fun saludo() = when (java.time.LocalTime.now().hour) {
        in 5..11 -> "un buen día"; in 12..18 -> "una buena tarde"; else -> "una buena noche"
    }

    private fun decir(clave: String, texto: String) {
        if (!dichos.add(clave)) return
        if (ttsListo) hablar(texto) else pendientes += texto
    }
    private fun hablar(t: String) = pe.cooruta.app.data.VozCooRuta.decir(this, t)

    /** "despejado", "moderado" o "congestionado": duración con tráfico vs. sin tráfico (Routes API). */
    private suspend fun estadoTrafico(a: Punto, b: Punto): String? = runCatching {
        fun wp(p: Punto) = mapOf("location" to mapOf("latLng" to mapOf("latitude" to p.latitud, "longitude" to p.longitud)))
        val r = pe.cooruta.app.data.remoto.ClienteGoogle.api.recorrido(
            clave = BuildConfig.GOOGLE_MAPS_KEY,
            campos = "routes.duration,routes.staticDuration",
            cuerpo = mapOf("origin" to wp(a), "destination" to wp(b), "travelMode" to "DRIVE", "routingPreference" to "TRAFFIC_AWARE")
        ).routes?.firstOrNull() ?: return@runCatching null
        val con = pe.cooruta.app.data.remoto.segundosDe(r.duration) ?: return@runCatching null
        val sin = pe.cooruta.app.data.remoto.segundosDe(r.staticDuration)?.takeIf { it > 0 } ?: return@runCatching null
        val f = con / sin
        when { f < 1.15 -> "despejado"; f < 1.4 -> "moderado"; else -> "congestionado" }
    }.getOrNull()

    override fun onStart() { super.onStart(); vista.onStart() }
    override fun onResume() { super.onResume(); vista.onResume() }
    override fun onPause() { vista.onPause(); super.onPause() }
    override fun onStop() { vista.onStop(); super.onStop() }
    override fun onSaveInstanceState(outState: Bundle) { super.onSaveInstanceState(outState); vista.onSaveInstanceState(outState) }
    override fun onConfigurationChanged(newConfig: Configuration) { super.onConfigurationChanged(newConfig); vista.onConfigurationChanged(newConfig) }
    override fun onTrimMemory(level: Int) { super.onTrimMemory(level); vista.onTrimMemory(level) }

    override fun onDestroy() {
        abierta = false
        navegador?.let {
            it.removeArrivalListener(alLlegar)
            it.removeRemainingTimeOrDistanceChangedListener(alCambiarTiempo)
            it.stopGuidance()
            it.clearDestinations()
        }
        navegador = null
        tts?.stop(); tts?.shutdown(); tts = null
        if (::vista.isInitialized) vista.onDestroy()
        super.onDestroy()
    }

    companion object {
        /** Mientras está abierta, la voz de la pantalla de viaje se calla (no hablan dos a la vez). */
        @Volatile var abierta = false
            private set

        private const val LATS = "lats"; private const val LNGS = "lngs"; private const val TIPOS = "tipos"
        private const val NOMBRES = "nombres"; private const val LUGARES = "lugares"; private const val EMPRESA = "empresa"
        private const val CONDUCTOR = "conductor"; private const val PASAJEROS = "pasajeros"
        private const val CERCANOS = "cercanos"; private const val CERCANOS_LUGAR = "cercanosLugar"; private const val UIDS = "uids"
        private const val CERCA_LATS = "cercaLats"; private const val CERCA_LNGS = "cercaLngs"; private const val CERCA_UIDS = "cercaUids"
        private const val VIAJE = "viaje"
        private const val GUIA_LATS = "guiaLats"; private const val GUIA_LNGS = "guiaLngs"

        /** Ruta publicada que ven los pasajeros (para que la navegación la siga). */
        private fun leerGuia(i: Intent): List<Punto> {
            val la = i.getDoubleArrayExtra(GUIA_LATS) ?: return emptyList()
            val ln = i.getDoubleArrayExtra(GUIA_LNGS) ?: return emptyList()
            return la.indices.mapNotNull { k -> ln.getOrNull(k)?.let { Punto(la[k], it) } }
        }
        /** Tope propio por día (Google cobra por destino desde el 1.001 del mes). */
        private const val MAX_DESTINOS_DIA = 40

        private fun leer(i: Intent): List<ParadaNav> {
            val lats = i.getDoubleArrayExtra(LATS) ?: return emptyList()
            val lngs = i.getDoubleArrayExtra(LNGS) ?: return emptyList()
            val tipos = i.getStringArrayExtra(TIPOS) ?: return emptyList()
            val nombres = i.getStringArrayExtra(NOMBRES) ?: emptyArray()
            val lugares = i.getStringArrayExtra(LUGARES) ?: emptyArray()
            val uids = i.getStringArrayExtra(UIDS) ?: emptyArray()
            return lats.indices.map { k ->
                ParadaNav(Punto(lats[k], lngs[k]), ParadaNav.Tipo.valueOf(tipos[k]), nombres.getOrElse(k) { "" }, lugares.getOrElse(k) { "" }, uids.getOrElse(k) { "" })
            }
        }

        /** Abre la navegación. Devuelve un mensaje si no se pudo (sin paradas o tope del día alcanzado). */
        fun abrir(ctx: Context, paradas: List<ParadaNav>, empresaComun: String? = null,
                  conductor: String = "", pasajeros: List<String> = emptyList(), cercanos: List<ParadaNav> = emptyList(),
                  viajeId: String = "", guia: List<Punto> = emptyList()): String? {
            val lista = paradas.take(25)
            if (lista.isEmpty()) return "Aún no hay recorrido para navegar."
            val prefs = ctx.getSharedPreferences("navegacion", Context.MODE_PRIVATE)
            val hoy = java.time.LocalDate.now().toString()
            val usados = if (prefs.getString("dia", "") == hoy) prefs.getInt("destinos", 0) else 0
            if (usados + lista.size > MAX_DESTINOS_DIA) return "Llegaste al límite de navegación de hoy."
            prefs.edit().putString("dia", hoy).putInt("destinos", usados + lista.size).apply()
            ctx.startActivity(Intent(ctx, NavegacionActivity::class.java)
                .putExtra(LATS, lista.map { it.punto.latitud }.toDoubleArray())
                .putExtra(LNGS, lista.map { it.punto.longitud }.toDoubleArray())
                .putExtra(TIPOS, lista.map { it.tipo.name }.toTypedArray())
                .putExtra(NOMBRES, lista.map { it.nombre }.toTypedArray())
                .putExtra(LUGARES, lista.map { it.lugar }.toTypedArray())
                .putExtra(UIDS, lista.map { it.uid }.toTypedArray())
                .putExtra(CONDUCTOR, conductor)
                .putExtra(PASAJEROS, pasajeros.toTypedArray())
                .putExtra(CERCANOS, cercanos.map { it.nombre }.toTypedArray())
                .putExtra(CERCANOS_LUGAR, cercanos.map { it.lugar }.toTypedArray())
                .putExtra(CERCA_LATS, cercanos.map { it.punto.latitud }.toDoubleArray())
                .putExtra(CERCA_LNGS, cercanos.map { it.punto.longitud }.toDoubleArray())
                .putExtra(CERCA_UIDS, cercanos.map { it.uid }.toTypedArray())
                .putExtra(VIAJE, viajeId)
                .apply {
                    // Hasta ~500 puntos de la ruta publicada, para no cargar el Intent.
                    val paso = (guia.size / 500) + 1
                    val g = guia.filterIndexed { k, _ -> k % paso == 0 || k == guia.lastIndex }
                    putExtra(GUIA_LATS, g.map { it.latitud }.toDoubleArray())
                    putExtra(GUIA_LNGS, g.map { it.longitud }.toDoubleArray())
                }
                .apply { empresaComun?.let { putExtra(EMPRESA, it) } })
            return null
        }
    }
}
