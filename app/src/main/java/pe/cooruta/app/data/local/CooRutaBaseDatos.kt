package pe.cooruta.app.data.local

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/**
 * Un lugar que la persona ya eligió alguna vez.
 *
 * Se guardan en el teléfono para ofrecerlos al abrir la búsqueda: la mayoría
 * de la gente va siempre a los mismos dos o tres sitios, y así funcionan aunque
 * no haya internet.
 */
@Entity(tableName = "lugares_recientes")
data class LugarRecienteEntidad(
    /** Coordenadas redondeadas a ~1 m: el mismo sitio elegido dos veces es una sola fila. */
    @PrimaryKey val clave: String,
    val nombre: String,
    val detalle: String,
    val latitud: Double,
    val longitud: Double,
    /** Cuándo se usó por última vez, en milisegundos. Ordena la lista. */
    val usadoEn: Long
)

@Dao
interface LugaresRecientesDao {

    /** Los más recientes primero. Room avisa solo cada vez que la tabla cambia. */
    @Query("SELECT * FROM lugares_recientes ORDER BY usadoEn DESC LIMIT :limite")
    fun observar(limite: Int): Flow<List<LugarRecienteEntidad>>

    /** Inserta, o actualiza la fecha si el lugar ya estaba. */
    @Upsert
    suspend fun guardar(lugar: LugarRecienteEntidad)

    /** Deja solo los [conservar] más recientes, para que la tabla no crezca sin fin. */
    @Query(
        "DELETE FROM lugares_recientes WHERE clave NOT IN " +
            "(SELECT clave FROM lugares_recientes ORDER BY usadoEn DESC LIMIT :conservar)"
    )
    suspend fun recortar(conservar: Int)
}

@Database(entities = [LugarRecienteEntidad::class], version = 1, exportSchema = false)
abstract class CooRutaBaseDatos : RoomDatabase() {

    abstract fun lugaresRecientes(): LugaresRecientesDao

    companion object {
        @Volatile
        private var instancia: CooRutaBaseDatos? = null

        /** Una sola base de datos para toda la app. */
        fun obtener(contexto: Context): CooRutaBaseDatos =
            instancia ?: synchronized(this) {
                instancia ?: Room.databaseBuilder(
                    contexto.applicationContext,
                    CooRutaBaseDatos::class.java,
                    "cooruta.db"
                ).build().also { instancia = it }
            }
    }
}
