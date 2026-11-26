package pe.cooruta.app.ui.inicio

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import pe.cooruta.app.data.Resultado
import pe.cooruta.app.data.repositorio.ConductoresRepositorio
import pe.cooruta.app.data.repositorio.MiRuta
import pe.cooruta.app.data.repositorio.PerfilPublico
import pe.cooruta.app.data.repositorio.PerfilesRepositorio
import pe.cooruta.app.data.repositorio.RutasRepositorio
import pe.cooruta.app.ui.horario.formatoHora
import pe.cooruta.app.ui.horario.resumenDias
import pe.cooruta.app.ui.theme.Coo

data class EstadoConductores(
    val ids: Set<String> = emptySet(),
    val perfiles: Map<String, PerfilPublico> = emptyMap(),
    val cargando: Boolean = true,
    /** Rutas públicas activas del conductor abierto en su perfil. */
    val rutasPerfil: List<MiRuta>? = null
)

/** Mis conductores (favoritos del pasajero) y el perfil de cualquier conductor. */
class ConductoresViewModel(
    private val repo: ConductoresRepositorio = ConductoresRepositorio(),
    private val perfilesRepo: PerfilesRepositorio = PerfilesRepositorio(),
    private val rutas: RutasRepositorio = RutasRepositorio()
) : ViewModel() {
    private val _estado = MutableStateFlow(EstadoConductores())
    val estado: StateFlow<EstadoConductores> = _estado.asStateFlow()

    init {
        viewModelScope.launch {
            repo.escuchar().collect { ids ->
                _estado.update { it.copy(ids = ids, cargando = false) }
                val faltan = ids - _estado.value.perfiles.keys
                if (faltan.isNotEmpty()) {
                    val p = perfilesRepo.perfiles(faltan)
                    _estado.update { it.copy(perfiles = it.perfiles + p) }
                }
            }
        }
    }

    fun esFavorito(id: String) = id in _estado.value.ids

    fun alternar(id: String, alAvisar: (String) -> Unit) {
        viewModelScope.launch {
            val quitar = id in _estado.value.ids
            val r = if (quitar) repo.quitar(id) else repo.agregar(id)
            when (r) {
                is Resultado.Exito -> alAvisar(if (quitar) "Quitado de Mis conductores." else "Agregado a Mis conductores ★")
                is Resultado.Error -> alAvisar(r.mensaje)
            }
        }
    }

    /** Abre el perfil: datos del conductor y sus rutas públicas activas. */
    fun abrirPerfil(id: String) {
        _estado.update { it.copy(rutasPerfil = null) }
        viewModelScope.launch {
            if (id !in _estado.value.perfiles) {
                (perfilesRepo.leerPerfil(id) as? Resultado.Exito)?.valor?.let { p -> _estado.update { it.copy(perfiles = it.perfiles + (id to p)) } }
            }
            val lista = (rutas.rutasActivas() as? Resultado.Exito)?.valor.orEmpty()
                .filter { it.conductorId == id && it.visibleEnBuscar && !it.prueba }
            _estado.update { it.copy(rutasPerfil = lista) }
        }
    }

    companion object {
        val Fabrica: ViewModelProvider.Factory = viewModelFactory { initializer { ConductoresViewModel() } }
    }
}

/** Pestaña del pasajero: sus conductores guardados. */
@Composable
fun MisConductoresPestana(
    alAvisar: (String) -> Unit,
    modifier: Modifier = Modifier,
    vm: ConductoresViewModel = viewModel(factory = ConductoresViewModel.Fabrica)
) {
    val e by vm.estado.collectAsStateWithLifecycle()
    var abierto by remember { mutableStateOf<String?>(null) }
    abierto?.let { PerfilConductor(it, alAvisar, alCerrar = { abierto = null }, vm = vm) }
    when {
        e.cargando -> Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Coo.AzulUbicacion) }
        e.ids.isEmpty() -> Column(modifier.fillMaxSize().padding(32.dp), verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally) {
            Text("☆", color = Coo.TextoTenue, fontSize = 40.sp)
            Text("Aún no tienes conductores", color = Coo.Texto, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(6.dp))
            Text("En Buscar, abre una ruta y toca «☆ Agregar a mis conductores» para tenerlos aquí y ver sus rutas.",
                color = Coo.TextoTenue, fontSize = 14.sp, textAlign = TextAlign.Center)
        }
        else -> LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(e.ids.toList(), key = { it }) { id ->
                val p = e.perfiles[id]
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Coo.Campo)
                        .border(1.dp, Coo.BordeSuave, RoundedCornerShape(16.dp))
                        .clickable { vm.abrirPerfil(id); abierto = id }.padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Avatar(p?.foto, p?.nombre.orEmpty().ifBlank { "C" }, 44.dp)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(p?.nombre?.ifBlank { null } ?: "Conductor", color = Coo.Texto, fontWeight = FontWeight.Bold)
                        Text(listOfNotNull(p?.calificacionTexto(), p?.empresa?.ifBlank { null }).joinToString(" · "),
                            color = Coo.TextoTenue, fontSize = 13.sp)
                    }
                    Text("★", color = Coo.Advertencia, fontSize = 22.sp)
                }
            }
        }
    }
}

/** Perfil de un conductor: datos, vehículo, preferencias y sus rutas públicas. */
@Composable
fun PerfilConductor(
    id: String,
    alAvisar: (String) -> Unit,
    alCerrar: () -> Unit,
    vm: ConductoresViewModel = viewModel(factory = ConductoresViewModel.Fabrica)
) {
    val e by vm.estado.collectAsStateWithLifecycle()
    LaunchedEffect(id) { vm.abrirPerfil(id) }
    val p = e.perfiles[id]
    val favorito = id in e.ids
    var mapa by remember { mutableStateOf<MiRuta?>(null) }
    Dialog(onDismissRequest = alCerrar, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.fillMaxSize().background(Coo.Fondo)) {
            Box(Modifier.weight(1f)) {
                VistaPerfilConductor(
                    p = p, favorito = favorito, rutas = e.rutasPerfil, alCerrar = alCerrar,
                    alAlternarFavorito = { vm.alternar(id, alAvisar) }, alVerMapa = { mapa = it }
                )
            }
            // Agregar a contactos, bloquear o reportar.
            Box(Modifier.navigationBarsPadding()) { AccionesPersona(id, p?.nombre.orEmpty(), alAvisar, "perfil_conductor") }
        }
    }
    mapa?.let { r ->
        Dialog(onDismissRequest = { mapa = null }) {
            Column(Modifier.clip(RoundedCornerShape(16.dp)).background(Coo.Campo).padding(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(r.alias.ifBlank { "Recorrido" }, color = Coo.Texto, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    Text("Cerrar", color = Coo.AzulUbicacion, fontWeight = FontWeight.Bold,
                        modifier = Modifier.clickable { mapa = null }.padding(8.dp))
                }
                Box(Modifier.fillMaxWidth().height(420.dp).clip(RoundedCornerShape(12.dp))) {
                    pe.cooruta.app.ui.confirmar.MapaVista(r.recorrido)
                }
            }
        }
    }
}

@Composable
private fun Seccion(titulo: String, texto: String) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Coo.Campo).padding(14.dp)) {
        Text(titulo, color = Coo.TextoTenue, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(4.dp))
        Text(texto, color = Coo.Texto, fontSize = 14.sp)
    }
}


/** Perfil de un pasajero (lo ve el conductor): foto, calificación, empresa, acerca de y preferencias. */
@Composable
fun PerfilPasajero(uid: String, nombre: String, alCerrar: () -> Unit) {
    val p by androidx.compose.runtime.produceState<pe.cooruta.app.data.repositorio.PerfilPublico?>(null, uid) {
        value = runCatching { pe.cooruta.app.data.repositorio.PerfilesRepositorio().perfiles(setOf(uid))[uid] }.getOrNull()
    }
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val avisar = { m: String -> android.widget.Toast.makeText(ctx, m, android.widget.Toast.LENGTH_SHORT).show() }
    Dialog(onDismissRequest = alCerrar, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.fillMaxSize().background(Coo.Fondo)) {
            Box(Modifier.weight(1f)) { VistaPerfilPasajero(p, p?.nombre?.ifBlank { null } ?: nombre.ifBlank { "Pasajero" }, alCerrar) }
            Box(Modifier.navigationBarsPadding()) { AccionesPersona(uid, p?.nombre?.ifBlank { null } ?: nombre, avisar, "perfil_pasajero") }
        }
    }
}
