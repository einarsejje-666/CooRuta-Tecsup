package pe.cooruta.app.data.remoto

import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.Query
import retrofit2.http.Url
import java.util.concurrent.TimeUnit

/**
 * Las APIs de Google Maps Platform que usa CooRuta: Places (New), Geocoding y Routes.
 * Cada una vive en un dominio distinto, por eso las URLs van completas con @Url.
 */
interface GoogleMapsApi {

    /** Places (New): sugerencias mientras la persona escribe. */
    @POST
    suspend fun autocompletar(
        @Url url: String = "https://places.googleapis.com/v1/places:autocomplete",
        @Header("X-Goog-Api-Key") clave: String,
        @Body cuerpo: Map<String, @JvmSuppressWildcards Any>
    ): RespuestaAutocompletar

    /** Places (New): coordenadas del lugar elegido. [url] = .../v1/places/{id} */
    @GET
    suspend fun lugar(
        @Url url: String,
        @Header("X-Goog-Api-Key") clave: String,
        @Header("X-Goog-FieldMask") campos: String = "location,displayName,formattedAddress",
        @Query("sessionToken") sesion: String,
        @Query("languageCode") idioma: String = "es"
    ): LugarGoogleJson

    /** Geocoding: qué dirección hay en unas coordenadas. */
    @GET
    suspend fun direccion(
        @Url url: String = "https://maps.googleapis.com/maps/api/geocode/json",
        @Query("latlng") latLng: String,
        @Query("language") idioma: String = "es",
        @Query("key") clave: String
    ): RespuestaGeocoding

    /** Places (New) Nearby: sitios conocidos más cercanos a un punto (ordenados por distancia). */
    @POST
    suspend fun cercanos(
        @Url url: String = "https://places.googleapis.com/v1/places:searchNearby",
        @Header("X-Goog-Api-Key") clave: String,
        @Header("X-Goog-FieldMask") campos: String = "places.displayName,places.location",
        @Body cuerpo: Map<String, @JvmSuppressWildcards Any>
    ): RespuestaCercanos

    /** Routes: recorrido en carro (con paradas intermedias opcionales). */
    @POST
    suspend fun recorrido(
        @Url url: String = "https://routes.googleapis.com/directions/v2:computeRoutes",
        @Header("X-Goog-Api-Key") clave: String,
        @Header("X-Goog-FieldMask") campos: String =
            "routes.duration,routes.distanceMeters,routes.polyline.encodedPolyline,routes.legs.duration,routes.legs.distanceMeters",
        @Body cuerpo: Map<String, @JvmSuppressWildcards Any>
    ): RespuestaRutas
}

/** Un solo cliente para toda la app. */
object ClienteGoogle {
    val api: GoogleMapsApi by lazy {
        val http = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
        Retrofit.Builder()
            .baseUrl("https://places.googleapis.com/")
            .client(http)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(GoogleMapsApi::class.java)
    }
}

/** Decodifica un "encoded polyline" de Google en pares (lat, lng). */
fun decodificarPolyline(codigo: String): List<Pair<Double, Double>> {
    val puntos = ArrayList<Pair<Double, Double>>()
    var i = 0; var lat = 0; var lng = 0
    while (i < codigo.length) {
        var resultado = 0; var desplazamiento = 0; var b: Int
        do { b = codigo[i++].code - 63; resultado = resultado or ((b and 0x1f) shl desplazamiento); desplazamiento += 5 } while (b >= 0x20)
        lat += if (resultado and 1 != 0) (resultado shr 1).inv() else resultado shr 1
        resultado = 0; desplazamiento = 0
        do { b = codigo[i++].code - 63; resultado = resultado or ((b and 0x1f) shl desplazamiento); desplazamiento += 5 } while (b >= 0x20)
        lng += if (resultado and 1 != 0) (resultado shr 1).inv() else resultado shr 1
        puntos.add(lat / 1e5 to lng / 1e5)
    }
    return puntos
}

// ------------------------------------------------------------------ JSON (todo nullable)

data class RespuestaAutocompletar(val suggestions: List<SugerenciaGoogleJson>?)
data class SugerenciaGoogleJson(val placePrediction: PrediccionJson?)
data class PrediccionJson(
    val placeId: String?,
    val text: TextoJson?,
    val structuredFormat: FormatoJson?,
    val types: List<String>?
)
data class FormatoJson(val mainText: TextoJson?, val secondaryText: TextoJson?)
data class TextoJson(val text: String?)
data class RespuestaCercanos(val places: List<LugarGoogleJson>?)

data class LugarGoogleJson(val location: LatLngJson?, val displayName: TextoJson?, val formattedAddress: String?)
data class LatLngJson(val latitude: Double?, val longitude: Double?)

data class RespuestaGeocoding(val status: String?, val results: List<ResultadoGeocodingJson>?)
data class ResultadoGeocodingJson(val formatted_address: String?, val types: List<String>?)

data class RespuestaRutas(val routes: List<RutaGoogleJson>?)
data class RutaGoogleJson(
    val duration: String?,
    /** Duración sin tráfico (solo si se pide con TRAFFIC_AWARE). */
    val staticDuration: String? = null,
    val distanceMeters: Double?,
    val polyline: PolylineJson?,
    val legs: List<TramoGoogleJson>?,
    /** Token para que el Navigation SDK siga exactamente esta ruta (solo si se pide en el FieldMask). */
    val routeToken: String? = null
)
data class PolylineJson(val encodedPolyline: String?)
data class TramoGoogleJson(val duration: String?, val distanceMeters: Double?)

/** "123s" → 123.0 */
fun segundosDe(duracion: String?): Double? = duracion?.removeSuffix("s")?.toDoubleOrNull()
