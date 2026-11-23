package pe.cooruta.app.data.repositorio

import com.google.android.gms.tasks.Task
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import pe.cooruta.app.data.Resultado
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class EstadoEmpresa(
    val uid: String? = null,
    val cargando: Boolean = true,
    val empresaId: String? = null,
    val nombre: String = "",
    val miembro: Boolean = false,
    val activa: Boolean = false,
    val invitacion: Boolean = false,
    /** La invitación existe pero ya venció: no se ofrece aceptarla. */
    val invitacionVencida: Boolean = false,
    val companeros: Set<String> = emptySet(),
    val error: String? = null
) {
    val disponible: Boolean get() = miembro && activa && !cargando && error == null
}

interface FuenteEmpresas {
    fun observar(): Flow<EstadoEmpresa>
    suspend fun aceptar(): Resultado<Unit>
    suspend fun salir(): Resultado<Unit>
}

/** Invitaciones provisionadas por un operador: aceptar es voluntario y está sujeto a reglas. */
class EmpresasRepositorio(
    private val auth: FirebaseAuth = FirebaseAuth.getInstance(),
    private val db: FirebaseFirestore = FirebaseFirestore.getInstance()
) : FuenteEmpresas {
    private fun documento(coleccion: String, id: String): Flow<DocumentSnapshot> = callbackFlow {
        val registro = db.collection(coleccion).document(id).addSnapshotListener(MetadataChanges.INCLUDE) { d, e ->
            if (e != null) close(e) else if (d != null) trySend(d)
        }
        awaitClose { registro.remove() }
    }

    private fun miembros(empresa: String): Flow<QuerySnapshot> = callbackFlow {
        val registro = db.collection("miembrosEmpresa").whereEqualTo("empresaId", empresa)
            .addSnapshotListener(MetadataChanges.INCLUDE) { d, e ->
                if (e != null) close(e) else if (d != null) trySend(d)
            }
        awaitClose { registro.remove() }
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    override fun observar(): Flow<EstadoEmpresa> = callbackFlow {
        val escucha = FirebaseAuth.AuthStateListener { trySend(it.currentUser?.uid) }
        auth.addAuthStateListener(escucha)
        awaitClose { auth.removeAuthStateListener(escucha) }
    }.distinctUntilChanged().flatMapLatest { uid ->
        if (uid == null) flowOf(EstadoEmpresa(cargando = false)) else
            combine(documento("miembrosEmpresa", uid), documento("invitacionesEmpresa", uid)) { m, i -> m to i }
                .flatMapLatest { (m, i) ->
                    val base = EstadoEmpresa(uid = uid, cargando = false, miembro = m.exists())
                    if (m.metadata.isFromCache || i.metadata.isFromCache)
                        flowOf(base.copy(error = "Conéctate para verificar tu empresa."))
                    else {
                        val id = m.getString("empresaId") ?: i.getString("empresaId")
                        if (id == null) flowOf(base) else documento("empresas", id).flatMapLatest { empresa ->
                            val e = base.copy(empresaId = id, nombre = empresa.getString("nombre").orEmpty(),
                                activa = empresa.getBoolean("activa") == true,
                                invitacion = !m.exists() && i.exists(),
                                invitacionVencida = !m.exists() && i.exists() &&
                                    (i.getTimestamp("venceEn")?.toDate()?.time ?: 0) <= System.currentTimeMillis())
                            when {
                                empresa.metadata.isFromCache -> flowOf(e.copy(error = "Conéctate para verificar tu empresa."))
                                !empresa.exists() || !e.activa -> flowOf(e.copy(error = "Esta comunidad está desactivada."))
                                !m.exists() -> flowOf(e)
                                else -> miembros(id).map { lista ->
                                    if (lista.metadata.isFromCache) e.copy(error = "Conéctate para verificar a tus compañeros.")
                                    else e.copy(companeros = lista.documents.map { it.id }.toSet())
                                }.catch { emit(e.copy(error = "No se pudo verificar el directorio. Actualiza tu vinculación.")) }
                            }
                        }
                    }
                }.onStart { emit(EstadoEmpresa(uid = uid)) }
                .catch { emit(EstadoEmpresa(uid = uid, cargando = false,
                    error = "No se pudo verificar tu empresa. Reintenta cuando tengas conexión.")) }
    }

    override suspend fun aceptar(): Resultado<Unit> = operar {
        val uid = auth.currentUser?.uid ?: error("Inicia sesión de nuevo.")
        db.runTransaction { tx ->
            val invitacion = tx.get(db.collection("invitacionesEmpresa").document(uid))
            val id = invitacion.getString("empresaId") ?: error("No tienes una invitación disponible.")
            val empresa = tx.get(db.collection("empresas").document(id))
            val miembro = db.collection("miembrosEmpresa").document(uid)
            val actual = tx.get(miembro)
            check(empresa.getBoolean("activa") == true) { "Esta comunidad está desactivada." }
            check((invitacion.getTimestamp("venceEn")?.toDate()?.time ?: 0) > System.currentTimeMillis()) {
                "La invitación venció. Solicita una nueva al responsable."
            }
            check(!actual.exists()) { "Ya estás vinculado. Actualiza la pantalla." }
            tx.set(miembro, mapOf("empresaId" to id, "unidoEn" to FieldValue.serverTimestamp()))
            // Invitación de un solo uso: nadie puede reutilizarla después de salir.
            tx.delete(invitacion.reference)
            Unit
        }.esperarEmpresa()
    }

    override suspend fun salir(): Resultado<Unit> = operar {
        val uid = auth.currentUser?.uid ?: error("Inicia sesión de nuevo.")
        db.collection("miembrosEmpresa").document(uid).delete().esperarEmpresa()
    }

    private suspend fun operar(accion: suspend () -> Unit): Resultado<Unit> = try {
        withTimeout(15_000) { accion() }
        Resultado.Exito(Unit)
    } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
        Resultado.Error("No se confirmó el cambio. Revisa tu conexión y actualiza antes de reintentar.")
    } catch (e: CancellationException) { throw e
    } catch (e: IllegalStateException) {
        Resultado.Error(e.message ?: "No se pudo completar el cambio.")
    } catch (e: Exception) {
        Resultado.Error("No se confirmó el cambio. Actualiza o pide al responsable que revise tu invitación.")
    }
}

private suspend fun <T> Task<T>.esperarEmpresa(): T = suspendCancellableCoroutine { c ->
    addOnSuccessListener { if (c.isActive) c.resume(it) }
    addOnFailureListener { if (c.isActive) c.resumeWithException(it) }
}
