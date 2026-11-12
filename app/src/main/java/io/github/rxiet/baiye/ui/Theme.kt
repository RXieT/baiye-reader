package io.github.rxiet.baiye.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.rxiet.baiye.BaiyeApplication
import io.github.rxiet.baiye.data.AppAppearance
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.defaultTextStyles
import top.yukonga.miuix.kmp.theme.lightColorScheme

val Ink = Color(0xFF181818)
val Accent = Color(0xFFD53025)
val Muted = Color(0xFF626262)
val Line = Color(0xFFE7E7E7)
val Soft = Color(0xFFF5F5F5)
val LocalAppFont = staticCompositionLocalOf<FontFamily> { FontFamily.Default }

@Composable
fun BaiyeTheme(content: @Composable () -> Unit) {
    val app = LocalContext.current.applicationContext as BaiyeApplication
    val appearanceFlow = remember(app) { app.library.state.map { it.appearance }.distinctUntilChanged() }
    val appearance by appearanceFlow.collectAsStateWithLifecycle(initialValue = AppAppearance())
    val family by produceState<FontFamily>(FontFamily.Default, appearance.fontFile) {
        value = withContext(Dispatchers.IO) {
            runCatching { app.media.fontFile(appearance.fontFile)?.let { FontFamily(Font(it)) } }.getOrNull() ?: FontFamily.Default
        }
    }
    val typography = remember(family) {
        val defaults = defaultTextStyles()
        defaults.copy(
            main = defaults.main.copy(fontFamily = family), paragraph = defaults.paragraph.copy(fontFamily = family),
            body1 = defaults.body1.copy(fontFamily = family), body2 = defaults.body2.copy(fontFamily = family),
            button = defaults.button.copy(fontFamily = family), footnote1 = defaults.footnote1.copy(fontFamily = family),
            footnote2 = defaults.footnote2.copy(fontFamily = family), headline1 = defaults.headline1.copy(fontFamily = family),
            headline2 = defaults.headline2.copy(fontFamily = family), subtitle = defaults.subtitle.copy(fontFamily = family),
            title1 = defaults.title1.copy(fontFamily = family), title2 = defaults.title2.copy(fontFamily = family),
            title3 = defaults.title3.copy(fontFamily = family), title4 = defaults.title4.copy(fontFamily = family),
        )
    }
    CompositionLocalProvider(LocalAppFont provides family) {
        MiuixTheme(
            colors = lightColorScheme(
                primary = Accent, primaryVariant = Accent, background = Color.White, surface = Color.White,
                onBackground = Ink, onSurface = Ink, secondaryVariant = Soft, onSecondaryVariant = Ink,
                surfaceContainer = Color.White, dividerLine = Line,
            ), textStyles = typography, content = content,
        )
    }
}
