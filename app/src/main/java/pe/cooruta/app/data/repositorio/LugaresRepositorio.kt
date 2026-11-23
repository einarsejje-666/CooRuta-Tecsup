package pe.cooruta.app.data.repositorio

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import pe.cooruta.app.data.Lugar
import pe.cooruta.app.data.Punto
import pe.cooruta.app.data.Resultado
import pe.cooruta.app.data.Sugerencia
import pe.cooruta.app.data.Trayecto
import pe.cooruta.app.data.local.LugarRecienteEntidad
import pe.cooruta.app.data.local.LugaresRecientesDao
import pe.cooruta.app.BuildConfig
import pe.cooruta.app.data.remoto.ClienteGoogle
import pe.cooruta.app.data.remoto.GoogleMapsApi
import pe.cooruta.app.data.remoto.decodificarPolyline
import pe.cooruta.app.data.remoto.segundosDe
import retrofit2.HttpException
import java.io.IOException
import java.util.Locale
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll

/** Cuántos lugares recientes se ofrecen al abrir la búsqueda. */
private const val RECIENTES_A_MOSTRAR = 6

/** Cuántos se guardan como máximo en el teléfono. */
private const val RECIENTES_A_GUARDAR = 30

/**
 * Lugares y recorridos. Es lo único que los ViewModels saben de Google Maps y de
 * la base de datos del teléfono.
 *
 * - Lo remoto (buscar, dirección bajo el pin, recorrido) va por Retrofit.
 * - Lo local (lugares recientes) va por Room.
 *
 * Las operaciones que pueden fallar devuelven [Resultado], con el mensaje ya
 * listo para mostrar: así "no hay internet" no se confunde con "no hay nada".
 */
class LugaresRepositorio(
    private val recientesDao: LugaresRecientesDao,
    private val api: GoogleMapsApi = ClienteGoogle.api,
    private val clave: String = BuildConfig.GOOGLE_MAPS_KEY
) {

    // ------------------------------------------------------------ local (Room)

    /** Los últimos lugares elegidos. Se actualiza solo cuando se guarda uno nuevo. */
    val recientes: Flow<List<Lugar>> = recientesDao.observar(RECIENTES_A_MOSTRAR).map { filas ->
        filas.map { Lugar(it.nombre, it.detalle, it.latitud, it.longitud) }
    }

    suspend fun guardarReciente(lugar: Lugar) {
        recientesDao.guardar(
            LugarRecienteEntidad(
                clave = String.format(Locale.US, "%.5f,%.5f", lugar.latitud, lugar.longitud),
                nombre = lugar.nombre,
                detalle = lugar.detalle,
                latitud = lugar.latitud,
                longitud = lugar.longitud,
                usadoEn = System.currentTimeMillis()
            )
        )
        recientesDao.recortar(RECIENTES_A_GUARDAR)
    }

    // ------------------------------------------------------------ remoto (Google Maps Platform)

    /**
     * Lo que coincide con lo que la persona va escribiendo (Places API New):
     * universidades, negocios, calles y direcciones. Limitado a Perú y sesgado
     * hacia [cerca] (o Arequipa si no hay referencia).
     */
    suspend fun sugerir(texto: String, sesion: String, cerca: Lugar?): Resultado<List<Sugerencia>> =
        llamar {
            val lat = cerca?.latitud ?: -16.3989
            val lng = cerca?.longitud ?: -71.5350
            api.autocompletar(
                clave = clave,
                cuerpo = mapOf(
                    "input" to texto,
                    "sessionToken" to sesion,
                    "languageCode" to "es",
                    "includedRegionCodes" to listOf("pe"),
                    "locationBias" to mapOf("circle" to mapOf(
                        "center" to mapOf("latitude" to lat, "longitude" to lng),
                        "radius" to 30000.0))
                )
            ).suggestions.orEmpty().mapNotNull { s ->
                val p = s.placePrediction ?: return@mapNotNull null
                val id = p.placeId?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val nombre = (p.structuredFormat?.mainText?.text ?: p.text?.text)?.takeIf { it.isNotBlank() }
                    ?: return@mapNotNull null
                Sugerencia(
                    id = id,
                    nombre = nombre,
                    detalle = p.structuredFormat?.secondaryText?.text.orEmpty(),
                    categoria = p.types.orEmpty().firstOrNull { it !in TIPOS_GENERICOS }
                )
            }.take(8)
        }

    /** Las coordenadas de la sugerencia que la persona eligió. */
    suspend fun detalle(sugerencia: Sugerencia, sesion: String): Resultado<Lugar> = llamar {
        val l = api.lugar(url = "https://places.googleapis.com/v1/places/${sugerencia.id}", clave = clave, sesion = sesion)
        val lat = l.location?.latitude
        val lng = l.location?.longitude
        if (lat == null || lng == null) {
            return Resultado.Error("No encontramos la ubicación de ese lugar. Prueba con otro.")
        }
        Lugar(nombre = sugerencia.nombre, detalle = sugerencia.detalle, latitud = lat, longitud = lng)
    }

    private val TIPOS_CALLE = setOf("street_address", "route", "intersection", "premise", "subpremise",
        "establishment", "point_of_interest", "neighborhood", "sublocality", "sublocality_level_1")

    /**
     * El nombre del sitio que hay bajo el pin, o null si no se pudo saber.
     * Conservamos el punto preguntado: el pin y el destino son el mismo sitio.
     */
    suspend fun direccionDe(latitud: Double, longitud: Double): Lugar? {
        // Códigos como «HF9J+CH3» no le dicen nada a nadie: se descartan.
        val codigo = Regex("^[23456789CFGHJMPQRVWX]{4,8}\\+[23456789CFGHJMPQRVWX]{2,3}")
        val calle = (llamar {
            val r = api.direccion(latLng = String.format(Locale.US, "%.6f,%.6f", latitud, longitud), clave = clave)
            r.results.orEmpty()
                // Solo calles, sitios o urbanizaciones (no la ciudad entera ni el código postal).
                .filter { g -> g.types.orEmpty().any { it in TIPOS_CALLE } }
                .mapNotNull { it.formatted_address?.trim() }
                .firstOrNull { it.isNotBlank() && !codigo.containsMatchIn(it) }
        } as? Resultado.Exito)?.valor
        // El sitio conocido más cercano (a menos de 150 m): «Tecsup», «Mall Aventura»…
        val sitio = (llamar {
            api.cercanos(clave = clave, cuerpo = mapOf(
                "maxResultCount" to 1, "rankPreference" to "DISTANCE", "languageCode" to "es",
                "locationRestriction" to mapOf("circle" to mapOf(
                    "center" to mapOf("latitude" to latitud, "longitude" to longitud), "radius" to 150.0))
            )).places?.firstOrNull()?.displayName?.text?.takeIf { it.isNotBlank() }
        } as? Resultado.Exito)?.valor
        val partes = calle?.split(", ", limit = 2)
        val nombreCalle = partes?.getOrNull(0)
        return when {
            sitio != null -> Lugar(sitio, listOfNotNull(nombreCalle, partes?.getOrNull(1)).joinToString(", "), latitud, longitud)
            nombreCalle != null -> Lugar(nombreCalle, partes?.getOrNull(1).orEmpty(), latitud, longitud)
            else -> null
        }
    }

    /** El recorrido en carro entre dos puntos (Routes API). */
    suspend fun trayecto(desde: Punto, hasta: Punto): Resultado<Trayecto> = llamar {
        val ruta = api.recorrido(clave = clave, cuerpo = cuerpoRuta(listOf(desde, hasta))).routes?.firstOrNull()
        val puntos = ruta?.polyline?.encodedPolyline?.let(::decodificarPolyline).orEmpty()
            .map { Punto(latitud = it.first, longitud = it.second) }
        if (ruta == null || puntos.isEmpty()) {
            return Resultado.Error("No encontramos un camino en carro entre esos dos puntos.")
        }
        Trayecto(
            puntos = puntos,
            segundos = segundosDe(ruta.duration) ?: 0.0,
            metros = ruta.distanceMeters ?: 0.0,
            desde = desde,
            hasta = hasta
        )
    }

    /** Hasta 3 recorridos en carro distintos entre dos puntos (la principal primero). */
    suspend fun alternativas(desde: Punto, hasta: Punto): Resultado<List<Trayecto>> = llamar {
        val cuerpo = cuerpoRuta(listOf(desde, hasta)) + ("computeAlternativeRoutes" to true)
        val lista = api.recorrido(clave = clave, cuerpo = cuerpo).routes.orEmpty().mapNotNull { ruta ->
            val puntos = ruta.polyline?.encodedPolyline?.let(::decodificarPolyline).orEmpty()
                .map { Punto(latitud = it.first, longitud = it.second) }
            if (puntos.isEmpty()) null
            else Trayecto(puntos = puntos, segundos = segundosDe(ruta.duration) ?: 0.0,
                metros = ruta.distanceMeters ?: 0.0, desde = desde, hasta = hasta)
        }
        if (lista.isEmpty()) return Resultado.Error("No encontramos un camino en carro entre esos dos puntos.")
        val todas = lista.toMutableList()
        // Además de las de Google, probamos caminos por puntos desviados a cada lado (en paralelo).
        run {
            val mLat = (desde.latitud + hasta.latitud) / 2
            val mLng = (desde.longitud + hasta.longitud) / 2
            val dLat = hasta.latitud - desde.latitud
            val dLng = hasta.longitud - desde.longitud
            val base = todas.minOf { it.metros }
            val extras = kotlinx.coroutines.coroutineScope { listOf(0.1, -0.1, 0.22, -0.22).map { lado -> async {
                val via = Punto(latitud = mLat - dLng * lado, longitud = mLng + dLat * lado)
                val c = cuerpoRuta(listOf(desde, hasta)).toMutableMap()
                c["intermediates"] = listOf(mapOf("via" to true, "location" to mapOf("latLng" to
                    mapOf("latitude" to via.latitud, "longitude" to via.longitud))))
                val r = try { api.recorrido(clave = clave, cuerpo = c).routes?.firstOrNull() } catch (e: Exception) {
                    if (e is CancellationException) throw e else null
                } ?: return@async null
                val pts = r.polyline?.encodedPolyline?.let(::decodificarPolyline).orEmpty()
                    .map { Punto(latitud = it.first, longitud = it.second) }
                val m = r.distanceMeters ?: return@async null
                if (pts.isEmpty() || m > base * 1.6) null
                else Trayecto(puntos = pts, segundos = segundosDe(r.duration) ?: 0.0, metros = m, desde = desde, hasta = hasta)
            } }.awaitAll().filterNotNull() }
            // Sin repetidas: casi la misma distancia y el mismo tiempo cuentan como la misma ruta.
            for (t in extras) if (todas.none { kotlin.math.abs(it.metros - t.metros) < it.metros * 0.03 &&
                    kotlin.math.abs(it.segundos - t.segundos) < it.segundos * 0.05 }) todas += t
        }
        // Recomendada: la más rápida; si otra tarda casi lo mismo (≤ 1 min más), la de menos km.
        val minS = todas.minOf { it.segundos }
        val (casiIguales, resto) = todas.partition { it.segundos <= minS + 60 }
        (casiIguales.sortedWith(compareBy<Trayecto>({ it.metros }, { it.segundos })) +
            resto.sortedWith(compareBy<Trayecto>({ it.segundos }, { it.metros }))).take(3)
    }

    /** Recorrido en carro que pasa por [puntos] en ese orden (2 a 27 puntos: hasta 25 intermedios). */
    suspend fun recorridoPor(puntos: List<Punto>): Resultado<pe.cooruta.app.data.RutaCalculada> {
        if (puntos.size !in 2..27) return Resultado.Error("El recorrido tiene ${puntos.size} puntos; se admiten de 2 a 27.")
        return llamar {
            val ruta = api.recorrido(clave = clave, cuerpo = cuerpoRuta(puntos)).routes?.firstOrNull()
            val linea = ruta?.polyline?.encodedPolyline?.let(::decodificarPolyline).orEmpty()
                .map { Punto(latitud = it.first, longitud = it.second) }
            if (ruta == null || linea.size < 2) {
                return Resultado.Error("No encontramos un recorrido en carro por esas paradas.")
            }
            val primero = ruta.legs?.firstOrNull()
            pe.cooruta.app.data.RutaCalculada(linea, ruta.distanceMeters, segundosDe(ruta.duration),
                primero?.distanceMeters, segundosDe(primero?.duration))
        }
    }

    private fun cuerpoRuta(puntos: List<Punto>): Map<String, Any> {
        fun wp(p: Punto) = mapOf("location" to mapOf("latLng" to mapOf("latitude" to p.latitud, "longitude" to p.longitud)))
        val cuerpo = mutableMapOf<String, Any>(
            "origin" to wp(puntos.first()),
            "destination" to wp(puntos.last()),
            "travelMode" to "DRIVE",
            "languageCode" to "es",
            "polylineQuality" to "HIGH_QUALITY"
        )
        if (puntos.size > 2) cuerpo["intermediates"] = puntos.subList(1, puntos.size - 1).map { wp(it) }
        return cuerpo
    }

    /**
     * Hace la llamada y traduce lo que pueda salir mal. Retrofit lanza
     * IOException si no hay red y HttpException si Google responde con error.
     */
    private suspend inline fun <T> llamar(bloque: () -> T): Resultado<T> {
        if (clave.isBlank()) {
            return Resultado.Error("Falta GOOGLE_MAPS_KEY en local.properties.")
        }
        return try {
            // Un segundo intento si la red falló (wifi o datos inestables).
            Resultado.Exito(try { bloque() } catch (e: IOException) { kotlinx.coroutines.delay(800); bloque() })
        } catch (e: CancellationException) {
            throw e
        } catch (e: java.net.SocketTimeoutException) {
            Resultado.Error("La conexión está lenta y Google Maps no respondió a tiempo. Intenta otra vez.")
        } catch (e: IOException) {
            Resultado.Error("Sin conexión a internet. Revisa tus datos o el wifi.")
        } catch (e: HttpException) {
            Resultado.Error(
                if (e.code() == 401 || e.code() == 403) "La clave de Google Maps no es válida o la API no está habilitada."
                else "Google Maps no respondió (error ${e.code()}). Intenta otra vez."
            )
        } catch (e: Exception) {
            Resultado.Error("Algo falló al leer la respuesta de Google Maps.")
        }
    }
}

/** Tipos de Places que no dicen nada útil como categoría. */
private val TIPOS_GENERICOS = setOf(
    "establishment", "point_of_interest", "geocode", "street_address", "route", "premise",
    "political", "locality", "sublocality", "sublocality_level_1", "neighborhood", "street_number",
    "administrative_area_level_1", "administrative_area_level_2", "administrative_area_level_3", "country", "postal_code"
)
