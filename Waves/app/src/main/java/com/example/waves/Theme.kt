package com.example.waves

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

@Composable
fun WavesTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(primary = Color(Style.accent), onPrimary = Color.Black,
            background = Color(0xFF08080D), surface = Color(0xFF17171C)),
        content = content)
}
