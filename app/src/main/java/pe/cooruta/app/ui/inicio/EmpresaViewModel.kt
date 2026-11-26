package pe.cooruta.app.ui.inicio

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import pe.cooruta.app.data.Resultado
import pe.cooruta.app.data.repositorio.EmpresasRepositorio
import pe.cooruta.app.data.repositorio.EstadoEmpresa
import pe.cooruta.app.data.repositorio.FuenteEmpresas

class EmpresaViewModel(private val repo: FuenteEmpresas) : ViewModel() {
    private val _estado = MutableStateFlow(EstadoEmpresa())
    val estado = _estado.asStateFlow()
    private val _ocupado = MutableStateFlow(false)
    val ocupado = _ocupado.asStateFlow()
    private val _mensaje = MutableStateFlow<String?>(null)
    val mensaje = _mensaje.asStateFlow()
    private var escucha: Job? = null
    private var sesion = 0L
    init { actualizar() }
    fun actualizar() {
        escucha?.cancel()
        escucha = viewModelScope.launch {
            repo.observar().collect {
                if (_estado.value.uid != it.uid) { _mensaje.value = null; sesion++ }
                _estado.value = it
            }
        }
    }
    fun aceptar() = cambiar(true)
    fun salir() = cambiar(false)
    private fun cambiar(aceptar: Boolean) {
        if (_ocupado.value) return
        _ocupado.value = true
        val uid = _estado.value.uid
        val inicioSesion = sesion
        viewModelScope.launch {
            try {
                val r = if (aceptar) repo.aceptar() else repo.salir()
                if (_estado.value.uid == uid && sesion == inicioSesion) {
                    _mensaje.value = if (r is Resultado.Error) r.mensaje
                        else if (aceptar) "Ya puedes buscar viajes de tu comunidad." else "Saliste de la comunidad. Tus reservas siguen vigentes."
                    if (r is Resultado.Exito) actualizar()
                }
            } finally { _ocupado.value = false }
        }
    }
    companion object {
        val Fabrica: ViewModelProvider.Factory = viewModelFactory { initializer { EmpresaViewModel(EmpresasRepositorio()) } }
    }
}
