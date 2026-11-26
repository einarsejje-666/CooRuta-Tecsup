package pe.cooruta.app.ui.horario

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import pe.cooruta.app.data.HorarioRuta
import pe.cooruta.app.data.Trayecto
import java.time.DayOfWeek
import java.time.LocalTime
import kotlin.math.roundToInt

/** Lunes a viernes: el caso de casi todo el que va a trabajar o a estudiar. */
val LUNES_A_VIERNES: Set<DayOfWeek> = setOf(
    DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY
)
val TODOS_LOS_DIAS: Set<DayOfWeek> = DayOfWeek.entries.toSet()

/**
 * Asientos para pasajeros que tiene el carro. Fijo en 4 hasta que exista el
 * registro del vehículo; entonces saldrá de ahí.
 */
const val CAPACIDAD_CARRO = 4

/**
 * Tarifa del pasaje por kilómetro y por pasajero, en céntimos (S/ 0.25).
 *
 * Por qué ese número (octubre 2026): la gasolina 90 en Arequipa está entre
 * S/ 21 y S/ 23 el galón y un auto chico rinde unos 38 km por galón, así que
 * manejar cuesta ~S/ 0.58 por km solo en gasolina, ~S/ 0.75 con desgaste.
 * Con 3 pasajeros a S/ 0.25 el conductor recupera lo que gasta sin ganar
 * como un taxi; y para el pasajero sigue costando mucho menos que un taxi.
 */
const val TARIFA_KM_CENTIMOS = 25

/** Ningún pasaje baja de S/ 2.00: en viajes muy cortos no vale la pena desviarse. */
private const val PASAJE_MINIMO = 200

/** El pasaje se redondea a 50 céntimos para que sea fácil pagar en efectivo. */
private const val REDONDEO = 50

data class EstadoHorario(
    val dias: Set<DayOfWeek> = LUNES_A_VIERNES,
    val salida: LocalTime = LocalTime.of(7, 0),
    val asientos: Int = 3,
    /** Cuánto dura el recorrido, para calcular la llegada. Null si no se pudo calcular. */
    val duracionSegundos: Double? = null,
    val kilometros: Double? = null,
    val eligiendoHora: Boolean = false
) {
    val sinDias: Boolean get() = dias.isEmpty()
    val puedeSeguir: Boolean get() = dias.isNotEmpty() && asientos >= 1

    /** Hora estimada de llegada, o null si no conocemos la duración. */
    val llegada: LocalTime? get() = duracionSegundos?.let { salida.plusSeconds(it.roundToInt().toLong()) }

    /**
     * Pasaje por pasajero: kilómetros × tarifa, redondeado y con mínimo.
     * Null mientras no se conozca la distancia del recorrido.
     */
    val pasajeCentimos: Int? get() = kilometros?.let { km ->
        redondear((km * TARIFA_KM_CENTIMOS).roundToInt()).coerceAtLeast(PASAJE_MINIMO)
    }
}

/**
 * Paso 2 del asistente: días, hora de salida, asientos y pasaje.
 *
 * No toca la red: todo se calcula aquí, a partir del recorrido del paso 1.
 * La pantalla pinta [estado] y avisa de los toques.
 */
class HorarioViewModel : ViewModel() {

    private val _estado = MutableStateFlow(EstadoHorario())
    val estado: StateFlow<EstadoHorario> = _estado.asStateFlow()

    /** Recibe el recorrido del paso 1: de ahí salen la llegada y el pasaje. */
    fun usarRecorrido(trayecto: Trayecto?) = _estado.update {
        it.copy(duracionSegundos = trayecto?.segundos, kilometros = trayecto?.let { t -> t.metros / 1000.0 })
    }

    fun precargar(h: pe.cooruta.app.data.HorarioRuta) = _estado.update {
        it.copy(dias = h.dias, salida = h.salida, asientos = h.asientos ?: it.asientos)
    }

    fun alternarDia(dia: DayOfWeek) = _estado.update {
        it.copy(dias = if (dia in it.dias) it.dias - dia else it.dias + dia)
    }

    fun elegirDias(dias: Set<DayOfWeek>) = _estado.update { it.copy(dias = dias) }

    fun abrirHora() = _estado.update { it.copy(eligiendoHora = true) }

    fun cerrarHora() = _estado.update { it.copy(eligiendoHora = false) }

    fun fijarHora(hora: Int, minuto: Int) = _estado.update {
        it.copy(salida = LocalTime.of(hora, minuto), eligiendoHora = false)
    }

    fun cambiarAsientos(paso: Int) = _estado.update {
        it.copy(asientos = (it.asientos + paso).coerceIn(1, CAPACIDAD_CARRO))
    }

    /** La llegada si se saliera a otra hora: el diálogo la muestra mientras se elige. */
    fun llegadaSiSale(hora: Int, minuto: Int): LocalTime? =
        _estado.value.duracionSegundos?.let {
            LocalTime.of(hora, minuto).plusSeconds(it.roundToInt().toLong())
        }

    /**
     * Devuelve el horario listo, o null si falta algo (la pantalla ya lo está
     * avisando). Asientos y pasaje solo cuentan si quien publica es conductor.
     */
    fun confirmar(esConductor: Boolean): HorarioRuta? {
        val e = _estado.value
        if (!e.puedeSeguir) return null
        return HorarioRuta(
            dias = e.dias,
            salida = e.salida,
            asientos = e.asientos.takeIf { esConductor },
            pasajeCentimos = e.pasajeCentimos.takeIf { esConductor }
        )
    }
}

private fun redondear(centimos: Int): Int =
    ((centimos + REDONDEO / 2) / REDONDEO) * REDONDEO

// ------------------------------------------------------------------ formatos

/** "07:15 a. m." — con la forma en que se escribe la hora en Perú. */
fun formatoHora(hora: LocalTime): String {
    val h12 = if (hora.hour % 12 == 0) 12 else hora.hour % 12
    val sufijo = if (hora.hour < 12) "a. m." else "p. m."
    return "%02d:%02d %s".format(h12, hora.minute, sufijo)
}

/** "S/ 4" o "S/ 4.50". */
fun formatoSoles(centimos: Int): String =
    if (centimos % 100 == 0) "S/ ${centimos / 100}"
    else "S/ %d.%02d".format(centimos / 100, centimos % 100)

/** Resumen corto de los días: "Lun a vie", "Todos los días", "Lun, mié, vie". */
fun resumenDias(dias: Set<DayOfWeek>): String = when {
    dias.isEmpty() -> "Sin días"
    dias == TODOS_LOS_DIAS -> "Todos los días"
    dias == LUNES_A_VIERNES -> "Lun a vie"
    else -> DayOfWeek.entries.filter { it in dias }.joinToString(", ") { abreviatura(it) }
}

private fun abreviatura(dia: DayOfWeek): String = when (dia) {
    DayOfWeek.MONDAY -> "lun"
    DayOfWeek.TUESDAY -> "mar"
    DayOfWeek.WEDNESDAY -> "mié"
    DayOfWeek.THURSDAY -> "jue"
    DayOfWeek.FRIDAY -> "vie"
    DayOfWeek.SATURDAY -> "sáb"
    DayOfWeek.SUNDAY -> "dom"
}.replaceFirstChar { it.uppercase() }
