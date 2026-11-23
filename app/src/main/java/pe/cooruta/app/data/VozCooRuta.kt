package pe.cooruta.app.data

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.speech.tts.TextToSpeech
import android.util.Base64
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import pe.cooruta.app.BuildConfig
import java.io.File
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Voz de CooRuta con Gemini TTS (Vertex AI): natural y cálida. Las frases se dicen una tras
 * otra; las repetidas salen del caché del teléfono (no se vuelven a cobrar). Sin red o si
 * Gemini tarda, usa la voz del teléfono como respaldo.
 */
object VozCooRuta {
    private const val MODELO = "gemini-2.5-flash-tts"
    private const val VOZ = "Kore"
    private const val TASA = 24_000 // Gemini devuelve PCM 16 bits, mono, 24 kHz
    private const val ESTILO = "Lee en español latino, con tono normal y natural: "

    private val alcance = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val cola = Channel<String>(Channel.UNLIMITED)
    private val http by lazy {
        OkHttpClient.Builder().connectTimeout(5, TimeUnit.SECONDS).readTimeout(10, TimeUnit.SECONDS).build()
    }
    private var app: Context? = null
    private var respaldo: TextToSpeech? = null
    private var respaldoListo = false

    /** Encola una frase. */
    fun decir(ctx: Context, texto: String) {
        iniciar(ctx)
        cola.trySend(texto)
    }

    @Synchronized
    private fun iniciar(ctx: Context) {
        if (app != null) return
        val a = ctx.applicationContext
        app = a
        respaldo = TextToSpeech(a) { st ->
            if (st == TextToSpeech.SUCCESS) { respaldo?.language = Locale.forLanguageTag("es-PE"); respaldoListo = true }
        }
        alcance.launch { for (t in cola) hablar(a, t) }
    }

    private suspend fun hablar(ctx: Context, texto: String) {
        val pcm = withTimeoutOrNull(8_000) { runCatching { audio(ctx, texto) }.getOrNull() }
        if (pcm != null && pcm.isNotEmpty()) reproducir(pcm)
        else if (respaldoListo) {
            respaldo?.speak(texto, TextToSpeech.QUEUE_ADD, null, texto.hashCode().toString())
            delay(texto.length * 65L) // aprox. lo que dura, para no pisar la siguiente frase
        }
    }

    /** Audio de la frase: del caché o de Gemini. */
    private fun audio(ctx: Context, texto: String): ByteArray? {
        val dir = File(ctx.cacheDir, "voz").apply { mkdirs() }
        val clave = MessageDigest.getInstance("SHA-1").digest("$MODELO|$VOZ|$texto".toByteArray())
            .joinToString("") { "%02x".format(it) }
        val archivo = File(dir, "$clave.pcm")
        if (archivo.exists()) return archivo.readBytes()
        if (BuildConfig.GEMINI_VOZ_KEY.isBlank()) return null

        val cuerpo = JSONObject()
            .put("contents", JSONArray().put(JSONObject().put("role", "user")
                .put("parts", JSONArray().put(JSONObject().put("text", ESTILO + texto)))))
            .put("generationConfig", JSONObject()
                .put("responseModalities", JSONArray().put("AUDIO"))
                .put("speechConfig", JSONObject().put("voiceConfig", JSONObject()
                    .put("prebuiltVoiceConfig", JSONObject().put("voiceName", VOZ)))))
        val url = "https://aiplatform.googleapis.com/v1/projects/cooruta-36603/locations/global/" +
            "publishers/google/models/$MODELO:generateContent?key=${BuildConfig.GEMINI_VOZ_KEY}"
        val pedido = Request.Builder().url(url)
            .post(cuerpo.toString().toRequestBody("application/json".toMediaType())).build()
        http.newCall(pedido).execute().use { r ->
            if (!r.isSuccessful) return null
            val datos = JSONObject(r.body?.string() ?: return null)
                .optJSONArray("candidates")?.optJSONObject(0)?.optJSONObject("content")
                ?.optJSONArray("parts")?.optJSONObject(0)?.optJSONObject("inlineData")?.optString("data")
                ?.takeIf { it.isNotBlank() } ?: return null
            val pcm = Base64.decode(datos, Base64.DEFAULT)
            runCatching { archivo.writeBytes(pcm) }
            return pcm
        }
    }

    /** Reproduce PCM y espera a que termine (así las frases no se pisan). */
    private suspend fun reproducir(pcm: ByteArray) {
        val pista = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setAudioFormat(AudioFormat.Builder().setSampleRate(TASA)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
            .setTransferMode(AudioTrack.MODE_STATIC)
            .setBufferSizeInBytes(pcm.size)
            .build()
        try {
            pista.write(pcm, 0, pcm.size)
            pista.play()
            delay(pcm.size * 1000L / (TASA * 2) + 250)
        } finally {
            runCatching { pista.stop() }
            pista.release()
        }
    }
}
