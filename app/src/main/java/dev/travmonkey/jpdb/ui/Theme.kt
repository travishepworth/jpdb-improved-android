package dev.travmonkey.jpdb.ui

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.intl.LocaleList

@Composable
fun JpdbTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val context = LocalContext.current
    val scheme = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> darkColorScheme()
        else -> lightColorScheme()
    }
    MaterialTheme(colorScheme = scheme, content = content)
}

/** Japanese locale so Han characters use Japanese glyph forms rather than Chinese ones. */
val JapaneseText = TextStyle(localeList = LocaleList("ja"))

/** jpdb's grade colours: red for failing grades, then orange (hard), green (okay/pass), blue (easy). */
enum class GradeColor(private val light: Long, private val dark: Long) {
    Red(0xFFD7443A, 0xFFB3362E),
    Orange(0xFFE5841A, 0xFFB86512),
    Green(0xFF3A9A4E, 0xFF2E7D3F),
    Blue(0xFF3B7BDB, 0xFF2F63B3);

    @Composable
    fun color() = Color(if (isSystemInDarkTheme()) dark else light)
}
