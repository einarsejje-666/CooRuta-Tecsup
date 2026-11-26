package pe.cooruta.app.ui.auth

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.material3.TextButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.AlertDialog
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import pe.cooruta.app.R
import pe.cooruta.app.ui.theme.Coo


/**
 * Pantalla de bienvenida. Es solo presentación: recibe el estado y avisa los
 * eventos hacia arriba, donde CooRutaApp decide qué hacer (state hoisting).
 */
@Composable
fun BienvenidaScreen(
    cargando: Boolean,
    alEntrarConGoogle: () -> Unit,
    alRegistroExterno: () -> Unit,
    alEntrarConCorreo: (String, String) -> Unit,
    alPulsarTerminos: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(modifier = modifier.fillMaxSize()) {

        Image(
            painter = painterResource(R.drawable.fondologin),
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop
        )

        // Degradado para que el texto blanco se lea sobre la ilustración.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0.0f to Color.Black.copy(alpha = 0.25f),
                        0.35f to Color.Transparent,
                        0.60f to Color.Black.copy(alpha = 0.35f),
                        1.0f to Color.Black.copy(alpha = 0.82f)
                    )
                )
        )

        Image(
            painter = painterResource(R.drawable.logoblanco),
            contentDescription = "CooRuta",
            modifier = Modifier
                .align(Alignment.TopStart)
                .safeDrawingPadding()
                .padding(start = 20.dp, top = 16.dp)
                .height(80.dp),
            contentScale = ContentScale.Fit
        )

        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .safeDrawingPadding()
                .padding(horizontal = 24.dp, vertical = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "Conectamos trayectos,\ntransformamos rutinas",
                color = Color.White,
                fontSize = 26.sp,
                lineHeight = 32.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
            )

            Spacer(Modifier.height(10.dp))

            Text(
                text = "Comparte el viaje con personas de confianza que hacen tu misma ruta diaria en Arequipa.",
                color = Color.White.copy(alpha = 0.92f),
                fontSize = 15.sp,
                lineHeight = 21.sp,
                textAlign = TextAlign.Center
            )

            Spacer(Modifier.height(14.dp))

            // Beneficios como texto (no pastillas): no parecen botones y caben en pantallas angostas.
            Text(
                text = "Sin transbordos  ·  Ahorra tiempo  ·  Pasaje justo",
                color = Color.White.copy(alpha = 0.92f),
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center
            )

            Spacer(Modifier.height(28.dp))

            BotonSocial(
                    texto = "Continuar con Google — Tecsup",
                    icono = painterResource(R.drawable.google),
                    colorFondo = Color.White,
                    colorTexto = Coo.TextoSobreClaro,
                    cargando = cargando,
                    alPulsar = alEntrarConGoogle
                )

                Spacer(Modifier.height(12.dp))
                // Segundo botón, visible: registro para quienes no son de Tecsup.
                OutlinedButton(
                    onClick = alRegistroExterno,
                    enabled = !cargando,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp),
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.5.dp, Color.White),
                    colors = ButtonDefaults.outlinedButtonColors(
                        containerColor = Color.White.copy(alpha = 0.12f),
                        contentColor = Color.White
                    )
                ) {
                    Text(
                        text = "¿No eres de Tecsup? Regístrate aquí",
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp,
                        textAlign = TextAlign.Center
                    )
                }
                // Acceso con correo y contraseña (cuentas creadas en Firebase, como la de revisión de Google Play).
                var verCorreo by remember { mutableStateOf(false) }
                Text(
                    text = "Entrar con correo y contraseña",
                    color = Color.White.copy(alpha = 0.85f),
                    fontSize = 14.sp,
                    textDecoration = TextDecoration.Underline,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .clickable(enabled = !cargando, onClick = { verCorreo = true })
                        .padding(horizontal = 8.dp, vertical = 12.dp)
                )
                if (verCorreo) {
                    DialogoCorreo(
                        alCerrar = { verCorreo = false },
                        alEntrar = { correo, clave ->
                            verCorreo = false
                            alEntrarConCorreo(correo, clave)
                        }
                    )
                }

                // Todo el bloque abre la política (área táctil de 48 dp; el lector lo anuncia como enlace).
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .clickable(onClickLabel = "Abrir términos y política de privacidad", onClick = alPulsarTerminos)
                        .padding(horizontal = 8.dp, vertical = 12.dp)
                ) {
                    Text(
                        text = "Al continuar, aceptas los",
                        color = Color.White.copy(alpha = 0.85f),
                        style = MaterialTheme.typography.bodySmall,
                        textAlign = TextAlign.Center
                    )
                    Text(
                        text = "Términos de uso y política de privacidad",
                        color = Color.White,
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Bold,
                        textDecoration = TextDecoration.Underline,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }
        }
    }
}

/** Botón ancho con un icono opcional a la izquierda del texto. */
@Composable
private fun BotonSocial(
    texto: String,
    icono: Painter?,
    colorFondo: Color,
    colorTexto: Color,
    alPulsar: () -> Unit,
    modifier: Modifier = Modifier,
    cargando: Boolean = false
) {
    Button(
        onClick = alPulsar,
        modifier = modifier
            .fillMaxWidth()
            .height(52.dp),
        enabled = !cargando,
        shape = RoundedCornerShape(12.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = colorFondo,
            contentColor = colorTexto,
            disabledContainerColor = colorFondo.copy(alpha = 0.6f),
            disabledContentColor = colorTexto.copy(alpha = 0.6f)
        )
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (cargando) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp,
                    color = colorTexto
                )
            } else {
                if (icono != null) {
                    Image(
                        painter = icono,
                        contentDescription = null,
                        modifier = Modifier.size(22.dp)
                    )
                    Spacer(Modifier.size(12.dp))
                }
                Text(
                    text = texto,
                    fontWeight = FontWeight.SemiBold,
                    style = MaterialTheme.typography.titleSmall
                )
            }
        }
    }
}

@Composable
private fun DialogoCorreo(alCerrar: () -> Unit, alEntrar: (String, String) -> Unit) {
    var correo by remember { mutableStateOf("") }
    var clave by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = alCerrar,
        title = { Text("Entrar con correo") },
        text = {
            Column {
                OutlinedTextField(
                    value = correo,
                    onValueChange = { correo = it },
                    label = { Text("Correo") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = clave,
                    onValueChange = { clave = it },
                    label = { Text("Contraseña") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = { TextButton(onClick = { alEntrar(correo, clave) }) { Text("Entrar") } },
        dismissButton = { TextButton(onClick = alCerrar) { Text("Cancelar") } }
    )
}
