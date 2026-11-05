package com.ahif.lumen.data.prefs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.ahif.lumen.data.model.SortOption
import com.ahif.lumen.data.model.ThemeMode
import com.ahif.lumen.data.model.ViewMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "lumen_settings")

data class AppSettings(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val accentId: String = "indigo",
    val dynamicColor: Boolean = false,
    val amoledDark: Boolean = false,
    val viewMode: ViewMode = ViewMode.GRID,
    val sort: SortOption = SortOption.RECENT,
    val keepScreenOn: Boolean = true,
    val readerFontScale: Float = 1f,
    val brightness: Float = -1f,   // -1 = follow system; 0f..1f = app-local override
    val readingTheme: String = "system", // system | light | sepia | night | black | console
    val folders: Set<String> = emptySet()
)

class SettingsRepository(private val context: Context) {

    private object Keys {
        val THEME = stringPreferencesKey("theme_mode")
        val ACCENT = stringPreferencesKey("accent_id")
        val DYNAMIC = booleanPreferencesKey("dynamic_color")
        val AMOLED = booleanPreferencesKey("amoled_dark")
        val VIEW = stringPreferencesKey("view_mode")
        val SORT = stringPreferencesKey("sort_option")
        val KEEP_ON = booleanPreferencesKey("keep_screen_on")
        val FONT = floatPreferencesKey("reader_font_scale")
        val BRIGHTNESS = floatPreferencesKey("brightness")
        val READING_THEME = stringPreferencesKey("reading_theme")
        val FOLDERS = stringSetPreferencesKey("folders")
    }

    val settings: Flow<AppSettings> = context.dataStore.data.map { p ->
        AppSettings(
            themeMode = p[Keys.THEME]?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() } ?: ThemeMode.SYSTEM,
            accentId = p[Keys.ACCENT] ?: "indigo",
            dynamicColor = p[Keys.DYNAMIC] ?: false,
            amoledDark = p[Keys.AMOLED] ?: false,
            viewMode = p[Keys.VIEW]?.let { runCatching { ViewMode.valueOf(it) }.getOrNull() } ?: ViewMode.GRID,
            sort = p[Keys.SORT]?.let { runCatching { SortOption.valueOf(it) }.getOrNull() } ?: SortOption.RECENT,
            keepScreenOn = p[Keys.KEEP_ON] ?: true,
            readerFontScale = p[Keys.FONT] ?: 1f,
            brightness = p[Keys.BRIGHTNESS] ?: -1f,
            readingTheme = p[Keys.READING_THEME] ?: "system",
            folders = p[Keys.FOLDERS] ?: emptySet()
        )
    }

    suspend fun setThemeMode(mode: ThemeMode) = edit { it[Keys.THEME] = mode.name }
    suspend fun setAccent(id: String) = edit { it[Keys.ACCENT] = id }
    suspend fun setDynamicColor(enabled: Boolean) = edit { it[Keys.DYNAMIC] = enabled }
    suspend fun setAmoled(enabled: Boolean) = edit { it[Keys.AMOLED] = enabled }
    suspend fun setViewMode(mode: ViewMode) = edit { it[Keys.VIEW] = mode.name }
    suspend fun setSort(sort: SortOption) = edit { it[Keys.SORT] = sort.name }
    suspend fun setKeepScreenOn(enabled: Boolean) = edit { it[Keys.KEEP_ON] = enabled }
    suspend fun setReaderFontScale(scale: Float) = edit { it[Keys.FONT] = scale }
    suspend fun setBrightness(value: Float) = edit { it[Keys.BRIGHTNESS] = value }
    suspend fun setReadingTheme(id: String) = edit { it[Keys.READING_THEME] = id }

    suspend fun addFolder(uri: String) = edit {
        it[Keys.FOLDERS] = (it[Keys.FOLDERS] ?: emptySet()) + uri
    }

    suspend fun removeFolder(uri: String) = edit {
        it[Keys.FOLDERS] = (it[Keys.FOLDERS] ?: emptySet()) - uri
    }

    private suspend fun edit(block: (androidx.datastore.preferences.core.MutablePreferences) -> Unit) {
        context.dataStore.edit(block)
    }
}
