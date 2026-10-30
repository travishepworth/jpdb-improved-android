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

/** Material has no "success" role; these tint the Pass buttons. */
@Composable
fun passContainer() = if (isSystemInDarkTheme()) Color(0xFF1F4D2C) else Color(0xFFCDEFD5)

@Composable
fun onPassContainer() = if (isSystemInDarkTheme()) Color(0xFFC8F0D1) else Color(0xFF0B3A18)
