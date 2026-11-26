package pe.cooruta.app.ui.auth

/** Dominio de los correos institucionales que dan acceso a la comunidad Tecsup. */
const val DOMINIO_TECSUP = "tecsup.edu.pe"

/** Las dos comunidades de CooRuta. Se deduce siempre del correo de la cuenta, nunca se elige. */
enum class Comunidad { TECSUP, EXTERNA }

/** Cómo se guarda en Firestore: "tecsup" o "externa". */
val Comunidad.clave: String get() = name.lowercase()

fun comunidadDe(correo: String?): Comunidad =
    if (correo?.trim()?.lowercase()?.endsWith("@$DOMINIO_TECSUP") == true) Comunidad.TECSUP else Comunidad.EXTERNA
