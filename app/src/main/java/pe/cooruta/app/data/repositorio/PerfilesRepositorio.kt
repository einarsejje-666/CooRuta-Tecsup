package pe.cooruta.app.data.repositorio

import com.google.android.gms.tasks.Task
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import pe.cooruta.app.data.Resultado
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Vehículo del conductor. Lo ven los pasajeros autenticados (sin datos del dueño). */
data class Vehiculo(
    val marca: String = "", val modelo: String = "", val color: String = "", val placa: String = "",
    val anio: String = "", val capacidad: Int = 0,
    /** Foto del auto: JPEG en base64, o vacío. */
    val foto: String = ""
) {
    val vacio get() = marca.isBlank() && modelo.isBlank()
    /** "Kia Rio 2019 gris · V4K-219". */
    fun resumen(): String = listOf("$marca $modelo $anio".trim(), color).filter { it.isNotBlank() }.joinToString(" ") +
        (if (placa.isNotBlank()) " · $placa" else "")
}

/** Preferencias de viaje que ayudan a decidir con quién viajar. */
val PREFERENCIAS_VIAJE = listOf("No se fuma", "Acepto mascotas", "Música suave", "Aire acondicionado",
    "Viaje tranquilo", "Espacio para mochila")

/** `perfilesPublicos/{uid}`: lo que otros pueden ver de una persona. */
data class PerfilPublico(
    val uid: String,
    val nombre: String = "",
    val vehiculo: Vehiculo? = null,
    val suma: Int = 0,
    val cantidad: Int = 0,
    val acercaDe: String = "",
    val preferencias: List<String> = emptyList(),
    /** Empresa o universidad: nombre escrito, clave normalizada y dominio verificado por correo (o vacío). */
    val empresa: String = "",
    val empresaClave: String = "",
    val empresaDominio: String = "",
    /** JPEG en base64 (≈256 px), o vacío. */
    val foto: String = ""
) {
    val promedio: Double? get() = if (cantidad > 0) suma.toDouble() / cantidad else null
    /** "4.8★ (12)" o "Nuevo". */
    fun calificacionTexto(): String = promedio?.let { String.format(java.util.Locale.US, "%.1f★ (%d)", it, cantidad) } ?: "Nuevo"
}

/**
 * Perfil público, vehículo y calificaciones. Sin servidor: la suma del promedio se escribe
 * en la misma transacción que la calificación y las reglas comprueban que cuadre.
 */
class PerfilesRepositorio(
    private val db: FirebaseFirestore = FirebaseFirestore.getInstance(),
    private val auth: FirebaseAuth = FirebaseAuth.getInstance()
) {
    private fun perfil(uid: String) = db.collection("perfilesPublicos").document(uid)
    private fun calificacion(id: String) = db.collection("calificaciones").document(id)

    val miUid: String? get() = auth.currentUser?.uid
    /** Dominio corporativo del correo con que entré (null si es Gmail u otro público, o no verificado). */
    val miDominioCorporativo: String? get() = auth.currentUser?.takeIf { it.isEmailVerified }?.email
        ?.let { pe.cooruta.app.data.Comunidad.dominioCorporativo(it) }

    suspend fun perfiles(uids: Set<String>): Map<String, PerfilPublico> = coroutineScope {
        uids.filter { it.isNotBlank() }.map { uid ->
            async { (leerPerfil(uid) as? Resultado.Exito)?.valor }
        }.awaitAll().filterNotNull().associateBy { it.uid }
    }

    suspend fun leerPerfil(uid: String): Resultado<PerfilPublico> = conRed("leer el perfil") {
        val d = esperar(perfil(uid).get())
        @Suppress("UNCHECKED_CAST")
        val v = d.get("vehiculo") as? Map<String, Any?>
        PerfilPublico(
            uid = uid,
            nombre = d.getString("nombre").orEmpty(),
            vehiculo = v?.let { Vehiculo(it["marca"] as? String ?: "", it["modelo"] as? String ?: "",
                it["color"] as? String ?: "", it["placa"] as? String ?: "", it["anio"] as? String ?: "",
                (it["capacidad"] as? Number)?.toInt() ?: 0, it["foto"] as? String ?: "") }?.takeUnless { it.vacio },
            preferencias = (d.get("preferencias") as? List<*>)?.filterIsInstance<String>().orEmpty(),
            empresa = d.getString("empresa").orEmpty(),
            empresaClave = d.getString("empresaClave").orEmpty(),
            empresaDominio = d.getString("empresaDominio").orEmpty(),
            suma = (d.getLong("calificacionSuma") ?: 0).toInt(),
            cantidad = (d.getLong("calificacionCantidad") ?: 0).toInt(),
            acercaDe = d.getString("acercaDe").orEmpty(),
            foto = d.getString("foto").orEmpty()
        )
    }

    /** Guarda nombre y vehículo propios. Si el perfil ya existe (lo crearon calificaciones), solo actualiza. */
    suspend fun guardarVehiculo(nombre: String, v: Vehiculo): Resultado<Unit> {
        val uid = miUid ?: return Resultado.Error("Inicia sesión con Google.")
        val limpio = Vehiculo(v.marca.trim().take(30), v.modelo.trim().take(30), v.color.trim().take(20),
            v.placa.trim().uppercase().take(10), v.anio.filter { it.isDigit() }.take(4), v.capacidad.coerceIn(0, 8), v.foto)
        val datos = mapOf("nombre" to nombre.trim().take(60),
            "vehiculo" to mapOf("marca" to limpio.marca, "modelo" to limpio.modelo, "color" to limpio.color, "placa" to limpio.placa,
                "anio" to limpio.anio, "capacidad" to limpio.capacidad, "foto" to limpio.foto),
            "actualizadoEn" to FieldValue.serverTimestamp())
        return conRed("guardar tu vehículo") {
            esperar(db.runTransaction { tx ->
                val ref = perfil(uid)
                if (tx.get(ref).exists()) tx.update(ref, datos)
                else tx.set(ref, datos + mapOf("calificacionSuma" to 0, "calificacionCantidad" to 0))
                null
            })
            Unit
        }
    }

    /** Guarda campos propios (nombre, acercaDe, foto…). Crea el perfil si aún no existe. */
    suspend fun guardarPropios(campos: Map<String, Any>, accion: String): Resultado<Unit> {
        val uid = miUid ?: return Resultado.Error("Inicia sesión con Google.")
        val datos = campos + ("actualizadoEn" to FieldValue.serverTimestamp())
        // Sin transacción: las transacciones exigen red y fallaban con UNAVAILABLE. Así la escritura
        // se aplica al instante en el teléfono y Firestore la sube sola cuando haya conexión.
        return conRed(accion) {
            val ref = perfil(uid)
            val existe = runCatching { esperar(ref.get()).exists() }.getOrDefault(true)
            val tarea = if (existe) ref.update(datos)
                else ref.set(datos + mapOf("calificacionSuma" to 0, "calificacionCantidad" to 0))
            // Si la red tarda, se da por guardado (queda en cola); un rechazo real sí se informa.
            kotlinx.coroutines.withTimeoutOrNull(6_000) { esperar(tarea) }
            Unit
        }
    }

    private fun usuario(uid: String) = db.collection("usuarios").document(uid)

    /** Mi celular (privado: solo yo lo leo). Vacío si no lo guardé. */
    suspend fun leerTelefono(): Resultado<String> {
        val uid = miUid ?: return Resultado.Error("Inicia sesión con Google.")
        return conRed("leer tu celular") { esperar(usuario(uid).get()).getString("telefono").orEmpty() }
    }

    suspend fun guardarTelefono(telefono: String): Resultado<Unit> {
        val uid = miUid ?: return Resultado.Error("Inicia sesión con Google.")
        val limpio = telefono.trim()
        if (limpio.isNotEmpty() && !Regex("^[+]?[0-9 ]{6,16}$").matches(limpio))
            return Resultado.Error("Escribe solo números, por ejemplo +51 987 654 321.")
        return conRed("guardar tu celular") {
            // merge: no borra los datos del registro que viven en el mismo documento.
            esperar(usuario(uid).set(mapOf("telefono" to limpio, "actualizadoEn" to FieldValue.serverTimestamp()), SetOptions.merge()))
            Unit
        }
    }

    /**
     * Datos del registro, en el documento privado `usuarios/{uid}` (solo su dueño lo lee).
     * [comunidad] la vuelven a comprobar las reglas contra el correo de la cuenta.
     */
    suspend fun guardarRegistro(
        nombres: String, apellidos: String, telefono: String, rol: String,
        comunidad: String, motivos: List<String>, motivoOtro: String
    ): Resultado<Unit> {
        val uid = miUid ?: return Resultado.Error("Inicia sesión con Google.")
        val limpio = telefono.trim()
        if (!Regex("^[+]?[0-9 ]{6,16}$").matches(limpio))
            return Resultado.Error("Escribe tu celular solo con números, por ejemplo 987 654 321.")
        val datos = mutableMapOf<String, Any>(
            "nombres" to nombres.trim().take(60), "apellidos" to apellidos.trim().take(60),
            "telefono" to limpio, "rol" to rol, "comunidad" to comunidad,
            "actualizadoEn" to FieldValue.serverTimestamp())
        if (comunidad == "externa") {
            datos["motivos"] = motivos.take(8)
            datos["motivoOtro"] = motivoOtro.trim().take(120)
        }
        return conRed("guardar tu registro") {
            esperar(usuario(uid).set(datos, SetOptions.merge()))
            Unit
        }
    }

    /** Borra mi perfil público (al eliminar la cuenta). */
    suspend fun borrarMiPerfil(): Resultado<Unit> {
        val uid = miUid ?: return Resultado.Error("Inicia sesión con Google.")
        return conRed("borrar tu perfil") {
            esperar(perfil(uid).delete())
            esperar(usuario(uid).delete())
            Unit
        }
    }

    /** Ids de reserva que ya califiqué (para no ofrecer calificar dos veces). */
    suspend fun reservasCalificadas(): Resultado<Set<String>> {
        val uid = miUid ?: return Resultado.Error("Inicia sesión con Google.")
        return conRed("leer tus calificaciones") {
            esperar(db.collection("calificaciones").whereEqualTo("autorId", uid).get())
                .documents.mapNotNull { it.getString("reservaId") }.toSet()
        }
    }

    /** Califica a la otra persona de [reservaId]. Las reglas exigen reserva aceptada y viaje terminado. */
    suspend fun calificar(reservaId: String, destinatarioId: String, estrellas: Int, comentario: String): Resultado<Unit> {
        val uid = miUid ?: return Resultado.Error("Inicia sesión con Google.")
        if (estrellas !in 1..5) return Resultado.Error("Elige de 1 a 5 estrellas.")
        val cid = "${reservaId}_$uid"
        return conRed("enviar tu calificación") {
            esperar(db.runTransaction { tx ->
                val ref = perfil(destinatarioId)
                val p = tx.get(ref)
                // Repetir no se comprueba aquí: las reglas rechazan crear una calificación que ya existe.
                tx.set(calificacion(cid), mapOf(
                    "reservaId" to reservaId, "autorId" to uid, "destinatarioId" to destinatarioId,
                    "estrellas" to estrellas, "comentario" to comentario.trim().take(280),
                    "creadaEn" to FieldValue.serverTimestamp()))
                val campos = mapOf(
                    "calificacionSuma" to (p.getLong("calificacionSuma") ?: 0) + estrellas,
                    "calificacionCantidad" to (p.getLong("calificacionCantidad") ?: 0) + 1,
                    "ultimaCalificacion" to cid)
                if (p.exists()) tx.update(ref, campos) else tx.set(ref, campos)
                null
            })
            Unit
        }
    }

    private suspend fun <T> esperar(t: Task<T>): T = suspendCancellableCoroutine { k ->
        t.addOnSuccessListener { if (k.isActive) k.resume(it) }
            .addOnFailureListener { if (k.isActive) k.resumeWithException(it) }
    }

    private suspend fun <T> conRed(accion: String, bloque: suspend () -> T): Resultado<T> = try {
        Resultado.Exito(withTimeout(15_000) { bloque() })
    } catch (e: TimeoutCancellationException) {
        Resultado.Error("Sin conexión: no pudimos $accion.")
    } catch (e: FirebaseFirestoreException) {
        Resultado.Error(when (e.code) {
            // Solo calificar depende de que el viaje haya terminado; el resto es un permiso (o dato) rechazado.
            FirebaseFirestoreException.Code.PERMISSION_DENIED ->
                if (accion == "enviar tu calificación") "No se pudo $accion: el viaje aún no termina o no te corresponde."
                else "No se pudo $accion: revisa los datos e inténtalo de nuevo."
            else -> "No se pudo $accion (${e.code.name})."
        })
    } catch (e: Exception) {
        Resultado.Error(e.message ?: "No se pudo $accion.")
    }
}
