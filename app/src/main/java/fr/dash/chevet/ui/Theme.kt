package fr.dash.chevet.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily

/** Texte principal, blanc chaud. */
val Encre = Color(0xFFF5E9DA)

/** Texte secondaire. */
val EncreDouce = Color(0xFFCBB8A6)

/** Accent ambré du couchant. */
val Ambre = Color(0xFFF2B36B)

/** Voile sombre derrière le texte, pour la lisibilité sur le ciel. */
val Voile = Color(0x99100C1C)

private val Couleurs = darkColorScheme(
    primary = Ambre,
    onPrimary = Color(0xFF2A1A10),
    background = Color(0xFF0B1026),
    onBackground = Encre,
    surface = Color(0xFF1A1630),
    onSurface = Encre,
)

private val Typo = Typography().let { t ->
    t.copy(
        headlineSmall = t.headlineSmall.copy(fontFamily = FontFamily.Serif),
        titleLarge = t.titleLarge.copy(fontFamily = FontFamily.Serif),
        bodyLarge = t.bodyLarge.copy(fontFamily = FontFamily.Serif),
    )
}

@Composable
fun ChevetTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = Couleurs, typography = Typo, content = content)
}
