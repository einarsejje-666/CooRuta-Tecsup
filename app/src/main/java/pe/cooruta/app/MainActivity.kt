package pe.cooruta.app

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import pe.cooruta.app.ui.theme.CooRutaTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Debug: Firebase normal o emuladores locales (local.properties); release: sin cambios.
        iniciarFirebase(this)
        // App Check protege las llamadas a la IA (Firebase AI Logic). Debe ir antes de usarla.
        // Con emuladores la IA está desactivada, así que no se instala.
        if (!BuildConfig.EMULADOR) instalarAppCheck()

        // La app es oscura siempre, así que los iconos de las barras del
        // sistema tienen que ser claros aunque el teléfono esté en modo claro.
        // Sin esto, en un teléfono en claro la hora y la batería salen oscuras
        // sobre nuestro fondo oscuro: invisibles.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT)
        )

        // Invitación al grupo de un conductor (enlace https o cooruta://).
        pe.cooruta.app.data.InvitacionPendiente.desdeIntent(intent)

        setContent {
            CooRutaTheme {
                // Modo emulador en Android 17+: pide el permiso de red local antes de usar Auth o Firestore.
                PuertaRedLocal { if (BuildConfig.TIENDA_PLAY) CooRutaApp() else ActualizacionObligatoria { CooRutaApp() } }
            }
        }
    }

    /** Con la app abierta (singleTop), un enlace de invitación llega aquí. */
    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        pe.cooruta.app.data.InvitacionPendiente.desdeIntent(intent)
    }
}
