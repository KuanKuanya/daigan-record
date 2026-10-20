/*
 * Copyright (C) 2022 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.kuankuan.daigan.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightColorScheme = lightColorScheme(
    primary = BrandIndigo,
    onPrimary = Color.White,
    primaryContainer = BrandSoftLight,
    onPrimaryContainer = BrandIndigo,
    secondary = SubLight,
    background = PaperLight,
    onBackground = InkLight,
    surface = PanelLight,
    onSurface = InkLight,
    surfaceVariant = BrandSoftLight,
    onSurfaceVariant = SubLight,
    outlineVariant = LineLight,
    error = WarningRed,
)

private val DarkColorScheme = darkColorScheme(
    primary = BrandIndigoDark,
    onPrimary = PaperDark,
    primaryContainer = BrandSoftDark,
    onPrimaryContainer = BrandIndigoDark,
    secondary = SubDark,
    background = PaperDark,
    onBackground = InkDark,
    surface = PanelDark,
    onSurface = InkDark,
    surfaceVariant = BrandSoftDark,
    onSurfaceVariant = SubDark,
    outlineVariant = LineDark,
    error = WarningRedDark,
)

/**
 * App theme. Light and dark follow the system setting; the brand palette is fixed
 * instead of dynamic so the approved mockup look is stable on every device.
 */
@Composable
fun DaiganHandbookTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme,
        typography = Typography,
        content = content,
    )
}
