package pe.cooruta.app.data

/**
 * Lo que devuelve una operación que puede fallar (una llamada a la red, por
 * ejemplo). Así el ViewModel distingue "no hubo resultados" de "no hay
 * internet" y la pantalla puede decir cuál de las dos pasó.
 */
sealed interface Resultado<out T> {
    data class Exito<T>(val valor: T) : Resultado<T>

    /** [mensaje] ya viene escrito para mostrárselo a la persona. */
    data class Error(val mensaje: String) : Resultado<Nothing>
}
