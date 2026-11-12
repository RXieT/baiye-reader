package io.github.rxiet.baiye.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.lightColorScheme

val Ink = Color(0xFF181818)
val Accent = Color(0xFFD53025)
val Muted = Color(0xFF626262)
val Line = Color(0xFFE7E7E7)
val Soft = Color(0xFFF5F5F5)

@Composable
fun BaiyeTheme(content: @Composable () -> Unit) {
    MiuixTheme(
        colors = lightColorScheme(
            primary = Accent, primaryVariant = Accent,
            background = Color.White, surface = Color.White,
            onBackground = Ink, onSurface = Ink,
            secondaryVariant = Soft, onSecondaryVariant = Ink,
            surfaceContainer = Color.White,
            dividerLine = Line,
        ),
        content = content,
    )
}
