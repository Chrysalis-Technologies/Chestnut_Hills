package com.chrysalis.treeid.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightColors = lightColorScheme(
    primary = Color(0xFF2E5E3A),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFCDE8CF),
    onPrimaryContainer = Color(0xFF0B2912),
    secondary = Color(0xFF6B5B3E),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFF1E1C1),
    onSecondaryContainer = Color(0xFF241A04),
    tertiaryContainer = Color(0xFFFFE0B2),
    onTertiaryContainer = Color(0xFF3A2400),
    background = Color(0xFFFBFAF5),
    onBackground = Color(0xFF1B1C18),
    surface = Color(0xFFFBFAF5),
    onSurface = Color(0xFF1B1C18),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF9CD3A6),
    onPrimary = Color(0xFF0B2912),
    primaryContainer = Color(0xFF1F4429),
    onPrimaryContainer = Color(0xFFCDE8CF),
    secondary = Color(0xFFD7C4A0),
    onSecondary = Color(0xFF3A2F16),
    secondaryContainer = Color(0xFF52452B),
    onSecondaryContainer = Color(0xFFF1E1C1),
    tertiaryContainer = Color(0xFF5A3F12),
    onTertiaryContainer = Color(0xFFFFE0B2),
    background = Color(0xFF121411),
    onBackground = Color(0xFFE3E3DC),
    surface = Color(0xFF121411),
    onSurface = Color(0xFFE3E3DC),
)

@Composable
fun TreeIdTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors,
        content = content,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BackTopBar(title: String, onBack: () -> Unit) {
    TopAppBar(
        title = { Text(title) },
        navigationIcon = {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
        },
    )
}
