package dev.relay.core

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density

/**
 * The shared color palette relay-ui and relay-call read instead of hardcoding literal colors.
 * Every field has a default (via [fromMaterialTheme]) equal to what the SDK's screens already
 * looked like before this type existed, so adopting it is opt-in — see [RelayTheme].
 */
data class RelayColors(
    val accent: Color, val accentForeground: Color,
    val bubbleMine: Color, val bubbleMineForeground: Color,
    val bubbleTheirs: Color, val bubbleTheirsForeground: Color,
    val background: Color, val surface: Color, val surfaceMuted: Color,
    val foreground: Color, val foregroundMuted: Color, val border: Color,
    val online: Color, val danger: Color,
    val callScrimStart: Color, val callScrimEnd: Color,
    val avatarSaturation: Float = 0.45f, val avatarLightness: Float = 0.92f,
) {
    companion object {
        /** Derives every field from a host's Material3 [ColorScheme] — this is the fallback used
         *  when neither an explicit [RelayColors] nor an ambient [LocalRelayColors] value is
         *  available, so it must reproduce today's actual hardcoded look (see call sites). */
        fun fromMaterialTheme(scheme: ColorScheme): RelayColors = RelayColors(
            accent = scheme.primary, accentForeground = scheme.onPrimary,
            bubbleMine = scheme.primary, bubbleMineForeground = scheme.onPrimary,
            bubbleTheirs = scheme.surfaceVariant, bubbleTheirsForeground = scheme.onSurfaceVariant,
            background = scheme.background, surface = scheme.surface, surfaceMuted = scheme.surfaceVariant,
            foreground = scheme.onSurface, foregroundMuted = scheme.onSurfaceVariant, border = scheme.outlineVariant,
            // Fixed literals, not scheme-derived: these must reproduce today's exact hardcoded
            // call-screen colors regardless of the host's Material error color, or a host who
            // changes nothing would see their decline/hang-up buttons shift color on upgrade.
            online = Color(0xFF22C55E), danger = Color(0xFFDC2626),
            callScrimStart = Color(0xFF12182A), callScrimEnd = Color(0xFF1D2540),
        )
    }
}

/**
 * Nullable-default on purpose: a `compositionLocalOf { error(...) }` here would crash every
 * existing host the moment any relay-ui/relay-call composable read it, since nothing requires a
 * host to ever call [RelayTheme]. Resolution is always `explicit ?: LocalRelayColors.current ?:
 * a computed default` — never a throwing default.
 */
val LocalRelayColors = compositionLocalOf<RelayColors?> { null }

/**
 * Optional root wrapper a host can use to theme every Relay composable underneath it:
 *
 *     RelayTheme(colors = myBrandColors, icons = myBrandIcons, typography = myBrandTypography) {
 *         RelayChat(client = relay)
 *     }
 *
 * Skipping this entirely is just as supported: every public Relay composable resolves its own
 * colors as `LocalRelayColors.current ?: RelayColors.fromMaterialTheme(MaterialTheme.colorScheme)`,
 * its own icons as `LocalRelayIcons.current`, and its own typography as
 * `LocalRelayTypography.current` (a no-op default — see [RelayTypography]), so a host who upgrades
 * and never calls this sees the exact same colors, icons and type as before these types existed.
 * `icons`/`typography` defaulting to `null` (rather than a concrete no-op instance) lets an
 * ambient `LocalRelayIcons`/`LocalRelayTypography` provided further up the tree still win when
 * this particular call site doesn't care — same resolution order as colors.
 */
@Composable
fun RelayTheme(colors: RelayColors? = null, icons: RelayIcons? = null, typography: RelayTypography? = null, content: @Composable () -> Unit) {
    val resolvedColors = colors ?: LocalRelayColors.current ?: RelayColors.fromMaterialTheme(MaterialTheme.colorScheme)
    val resolvedIcons = icons ?: LocalRelayIcons.current
    val resolvedTypography = typography ?: LocalRelayTypography.current
    val density = LocalDensity.current
    val textStyle = LocalTextStyle.current
    CompositionLocalProvider(
        LocalRelayColors provides resolvedColors,
        LocalRelayIcons provides resolvedIcons,
        LocalRelayTypography provides resolvedTypography,
        LocalDensity provides Density(density.density, density.fontScale * resolvedTypography.fontScale),
        LocalTextStyle provides textStyle.copy(fontFamily = resolvedTypography.fontFamily ?: textStyle.fontFamily),
        content = content,
    )
}
