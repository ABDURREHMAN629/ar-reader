package com.ahif.lumen.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ahif.lumen.data.model.ThemeMode
import com.ahif.lumen.data.prefs.AppSettings
import com.ahif.lumen.di.Graph
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SettingsViewModel : ViewModel() {
    private val settings = Graph.settings
    private val library = Graph.library

    val state: StateFlow<AppSettings> = settings.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), AppSettings())

    fun setThemeMode(mode: ThemeMode) = viewModelScope.launch { settings.setThemeMode(mode) }
    fun setAccent(id: String) = viewModelScope.launch { settings.setAccent(id) }
    fun setDynamicColor(enabled: Boolean) = viewModelScope.launch { settings.setDynamicColor(enabled) }
    fun setAmoled(enabled: Boolean) = viewModelScope.launch { settings.setAmoled(enabled) }
    fun setKeepScreenOn(enabled: Boolean) = viewModelScope.launch { settings.setKeepScreenOn(enabled) }
    fun setFontScale(scale: Float) = viewModelScope.launch { settings.setReaderFontScale(scale) }
    fun setBrightness(value: Float) = viewModelScope.launch { settings.setBrightness(value) }

    fun removeFolder(uri: String) = library.removeFolder(uri)
}
