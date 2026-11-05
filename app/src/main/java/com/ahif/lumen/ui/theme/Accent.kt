package com.ahif.lumen.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

private fun lightScheme(
    primary: Color,
    container: Color,
    onContainer: Color,
    secondary: Color,
    tertiary: Color
) = lightColorScheme(
    primary = primary,
    onPrimary = Color.White,
    primaryContainer = container,
    onPrimaryContainer = onContainer,
    inversePrimary = primary,
    secondary = secondary,
    onSecondary = Color.White,
    secondaryContainer = container,
    onSecondaryContainer = onContainer,
    tertiary = tertiary,
    onTertiary = Color.White,
    background = Color(0xFFFCFBFF),
    onBackground = Color(0xFF1A1B21),
    surface = Color(0xFFFCFBFF),
    onSurface = Color(0xFF1A1B21),
    surfaceVariant = Color(0xFFE4E1EC),
    onSurfaceVariant = Color(0xFF47464F),
    outline = Color(0xFF787680),
    outlineVariant = Color(0xFFC8C5D0)
)

private fun darkScheme(
    primary: Color,
    container: Color,
    onContainer: Color,
    secondary: Color,
    tertiary: Color
) = darkColorScheme(
    primary = primary,
    onPrimary = Color(0xFF10121A),
    primaryContainer = container,
    onPrimaryContainer = onContainer,
    inversePrimary = primary,
    secondary = secondary,
    onSecondary = Color(0xFF10121A),
    secondaryContainer = container,
    onSecondaryContainer = onContainer,
    tertiary = tertiary,
    background = Color(0xFF111318),
    onBackground = Color(0xFFE3E1E9),
    surface = Color(0xFF111318),
    onSurface = Color(0xFFE3E1E9),
    surfaceVariant = Color(0xFF45464F),
    onSurfaceVariant = Color(0xFFC6C5D0),
    outline = Color(0xFF90909A),
    outlineVariant = Color(0xFF45464F)
)

/** A selectable brand accent, each with a full light + dark colour scheme. */
enum class AccentColor(
    val id: String,
    val displayName: String,
    val light: ColorScheme,
    val dark: ColorScheme
) {
    INDIGO(
        "indigo", "Indigo",
        lightScheme(Color(0xFF4F46E5), Color(0xFFE1E0FF), Color(0xFF150267), Color(0xFF5B5D9C), Color(0xFF7D5260)),
        darkScheme(Color(0xFFC1C1FF), Color(0xFF2C2A7A), Color(0xFFE1E0FF), Color(0xFFC4C4E4), Color(0xFFEFB8C8))
    ),
    BLUE(
        "blue", "Ocean",
        lightScheme(Color(0xFF1667D6), Color(0xFFD7E2FF), Color(0xFF001A41), Color(0xFF565E71), Color(0xFF6C5677)),
        darkScheme(Color(0xFFAAC7FF), Color(0xFF00458D), Color(0xFFD7E2FF), Color(0xFFBEC6DC), Color(0xFFD8BDE3))
    ),
    TEAL(
        "teal", "Teal",
        lightScheme(Color(0xFF006A6A), Color(0xFF6FF7F6), Color(0xFF002020), Color(0xFF4A6363), Color(0xFF4B607C)),
        darkScheme(Color(0xFF4CDADA), Color(0xFF004F4F), Color(0xFF6FF7F6), Color(0xFFB0CCCB), Color(0xFFB3C8E8))
    ),
    EMERALD(
        "emerald", "Emerald",
        lightScheme(Color(0xFF116D3B), Color(0xFFA1F5BC), Color(0xFF00210F), Color(0xFF4F6353), Color(0xFF3B6470)),
        darkScheme(Color(0xFF86D8A1), Color(0xFF00522A), Color(0xFFA1F5BC), Color(0xFFB6CCB9), Color(0xFFA3CDDA))
    ),
    ROSE(
        "rose", "Rose",
        lightScheme(Color(0xFFB4004E), Color(0xFFFFD9E1), Color(0xFF3E0018), Color(0xFF75565C), Color(0xFF785900)),
        darkScheme(Color(0xFFFFB1C6), Color(0xFF8B0039), Color(0xFFFFD9E1), Color(0xFFE5BDC5), Color(0xFFF3BF48))
    ),
    AMBER(
        "amber", "Amber",
        lightScheme(Color(0xFF815600), Color(0xFFFFDDAF), Color(0xFF291800), Color(0xFF6F5B40), Color(0xFF4F6441)),
        darkScheme(Color(0xFFFFB95C), Color(0xFF624000), Color(0xFFFFDDAF), Color(0xFFDDC3A1), Color(0xFFB4CF9F))
    ),
    ORANGE(
        "orange", "Sunset",
        lightScheme(Color(0xFFA23F16), Color(0xFFFFDBCF), Color(0xFF380D00), Color(0xFF77574C), Color(0xFF6C5D2E)),
        darkScheme(Color(0xFFFFB59B), Color(0xFF7D2D07), Color(0xFFFFDBCF), Color(0xFFE7BDB0), Color(0xFFD8C58D))
    ),
    VIOLET(
        "violet", "Orchid",
        lightScheme(Color(0xFF7B3FD0), Color(0xFFEDDCFF), Color(0xFF290055), Color(0xFF635B70), Color(0xFF7F525D)),
        darkScheme(Color(0xFFD6BAFF), Color(0xFF5C2B9E), Color(0xFFEDDCFF), Color(0xFFC8C2DA), Color(0xFFF2B7C4))
    ),
    CONSOLE(
        "console", "Console",
        lightScheme(Color(0xFF1B7F3B), Color(0xFFB6F2C6), Color(0xFF00210E), Color(0xFF4F6353), Color(0xFF3D6473)),
        // Terminal-style: pure-black surfaces with bright green text
        darkColorScheme(
            primary = Color(0xFF4EFF91),
            onPrimary = Color(0xFF00210E),
            primaryContainer = Color(0xFF0C2C18),
            onPrimaryContainer = Color(0xFF8BFFB5),
            inversePrimary = Color(0xFF1B7F3B),
            secondary = Color(0xFF5EE08A),
            onSecondary = Color(0xFF00210E),
            secondaryContainer = Color(0xFF0C2C18),
            onSecondaryContainer = Color(0xFF8BFFB5),
            tertiary = Color(0xFF7FE0C0),
            background = Color(0xFF000000),
            onBackground = Color(0xFF86FFAE),
            surface = Color(0xFF050A06),
            onSurface = Color(0xFF86FFAE),
            surfaceVariant = Color(0xFF13251A),
            onSurfaceVariant = Color(0xFF74E39A),
            outline = Color(0xFF3A6B4A),
            outlineVariant = Color(0xFF1E3A28)
        )
    );

    companion object {
        fun byId(id: String): AccentColor = entries.firstOrNull { it.id == id } ?: INDIGO
    }
}
