package pe.cooruta.app

import android.content.Context
import android.security.NetworkSecurityPolicy
import android.util.Log
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore

/**
 * Debug: inicializa Firebase antes del primer uso de Auth y Firestore.
 * - Normal: igual que la inicialización automática (google-services.json).
 * - Con `cooruta.emulador=true` en local.properties: proyecto demo-cooruta contra Auth Emulator
 *   (9099) y Firestore Emulator (8080) del equipo. Usa otro applicationId para que la sesión y la
 *   caché locales queden separadas de las de producción.
 */
fun iniciarFirebase(contexto: Context) {
    val previas = FirebaseApp.getApps(contexto)
    if (previas.isNotEmpty()) { // ya configurado en este proceso (o inicializado por otro camino)
        Log.i(ETIQUETA, "Firebase ya inicializado: proyecto=${previas.first().options.projectId}, EMULADOR=${BuildConfig.EMULADOR}")
        return
    }
    if (!BuildConfig.EMULADOR) {
        FirebaseApp.initializeApp(contexto)
        Log.i(ETIQUETA, "Modo normal: proyecto=${FirebaseApp.getInstance().options.projectId}")
        return
    }
    FirebaseApp.initializeApp(
        contexto,
        FirebaseOptions.Builder()
            .setProjectId("demo-cooruta")
            .setApplicationId("1:000000000000:android:0000000000000000")
            .setApiKey("AIzaSyDemoCooRutaEmuladorLocal000000000") // ficticia: los emuladores no la validan
            .build()
    )
    FirebaseAuth.getInstance().useEmulator(BuildConfig.EMULADOR_HOST, 9099)
    FirebaseFirestore.getInstance().useEmulator(BuildConfig.EMULADOR_HOST, 8080)
    com.google.firebase.functions.FirebaseFunctions.getInstance(pe.cooruta.app.data.repositorio.HabitualesRepositorio.REGION)
        .useEmulator(BuildConfig.EMULADOR_HOST, 5001)
    val host = BuildConfig.EMULADOR_HOST
    Log.i(ETIQUETA, "Modo emulador: proyecto=${FirebaseApp.getInstance().options.projectId}, " +
        "Auth=$host:9099, Firestore=$host:8080, " +
        "HTTP sin cifrar permitido=${NetworkSecurityPolicy.getInstance().isCleartextTrafficPermitted(host)}")
}

private const val ETIQUETA = "CooRutaEmulador"
