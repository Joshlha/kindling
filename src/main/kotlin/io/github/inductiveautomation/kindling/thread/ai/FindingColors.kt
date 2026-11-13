package io.github.inductiveautomation.kindling.thread.ai

import io.github.inductiveautomation.kindling.core.Kindling.Preferences.UI.Theme
import java.awt.Color
import javax.swing.UIManager

/**
 * Severity colours that work in every bundled theme.
 *
 * `Actions.*` and `Objects.*` are IntelliJ-platform keys supplied only by `.theme.json` themes.
 * They are **not** defined by FlatLightLaf, FlatMacLightLaf, FlatDarkLaf or FlatMacDarkLaf -- and
 * Kindling's default is FlatLightLaf, so `UIManager.getColor` returns null for most users. An
 * explicit fallback is therefore mandatory, not polish. (The pre-existing deadlock highlighter in
 * `MultiThreadView` has exactly this bug and is very likely a silent no-op today.)
 */
object FindingColors {
    private fun themed(
        key: String,
        light: Int,
        dark: Int,
    ): Color = UIManager.getColor(key) ?: Color(if (Theme.currentValue.isDark) dark else light)

    fun background(severity: Severity): Color = when (severity) {
        Severity.HIGH -> themed("Objects.Red", light = 0xFFD5D5, dark = 0x6E2B2B)
        Severity.MEDIUM -> themed("Objects.YellowDark", light = 0xFFEFC2, dark = 0x6E581F)
        Severity.LOW -> themed("Objects.Blue", light = 0xD8E8FF, dark = 0x24415E)
    }

    /**
     * Picks readable text for an arbitrary background, so the result stays legible across all the
     * bundled IntelliJ themes without hand-tuning each one.
     */
    fun readableForeground(background: Color): Color {
        val luminance = (0.2126 * background.red + 0.7152 * background.green + 0.0722 * background.blue) / 255.0
        return if (luminance > 0.5) Color(0x1F1F1F) else Color(0xF2F2F2)
    }

    fun foreground(severity: Severity): Color = readableForeground(background(severity))
}
