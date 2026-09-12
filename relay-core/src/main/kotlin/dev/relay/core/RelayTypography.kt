package dev.relay.core

import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.text.font.FontFamily

/**
 * Font overrides relay-ui and relay-call apply on top of whatever `Text()`/`.sp` already renders,
 * instead of hardcoding a font family or size per call site. Both fields default to "leave it
 * alone" — `fontFamily = null` keeps each `Text()`'s own resolved font (typically the host's
 * MaterialTheme default), and `fontScale = 1f` multiplies every `.sp` size by exactly 1 — so a
 * host who upgrades and never touches this type sees the exact same type as before it existed.
 */
data class RelayTypography(
    val fontFamily: FontFamily? = null,
    val fontScale: Float = 1f,
)

/**
 * Safe non-null default (`RelayTypography()`, itself a no-op), same reasoning as
 * [LocalRelayIcons] — no ambient value is required to compute it, so any composable can read
 * `LocalRelayTypography.current` directly without a host having wrapped anything.
 */
val LocalRelayTypography = compositionLocalOf { RelayTypography() }
