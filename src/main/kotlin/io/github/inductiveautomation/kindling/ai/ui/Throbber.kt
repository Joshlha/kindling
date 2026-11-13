package io.github.inductiveautomation.kindling.ai.ui

import java.awt.Font
import javax.swing.JLabel
import javax.swing.Timer

/**
 * An elapsed-time status label shown while a request is in flight.
 *
 * Text rather than a spinning icon on purpose: Kindling's existing "throbber"
 * (`bx-loader-circle.svg` rendered through FlatSVGIcon) does not actually animate, and a visible
 * clock is what makes a 90-second wait tolerable. Users forgive slow far more readily than they
 * forgive "is this thing frozen?".
 */
class Throbber : JLabel(" ") {
    private var startedAt = 0L
    private var dots = 0

    private val timer = Timer(TICK_MILLIS) {
        dots = (dots + 1) % 4
        updateText()
    }

    init {
        font = font.deriveFont(Font.PLAIN, font.size - 1f)
    }

    var isRunning: Boolean = false
        set(value) {
            if (field == value) return
            field = value

            if (value) {
                startedAt = System.currentTimeMillis()
                dots = 0
                updateText()
                timer.start()
            } else {
                timer.stop()
                text = " "
            }
        }

    private fun updateText() {
        val seconds = (System.currentTimeMillis() - startedAt) / 1000
        val elapsed = if (seconds >= 60) {
            "%d:%02d".format(seconds / 60, seconds % 60)
        } else {
            "${seconds}s"
        }
        text = "Thinking${".".repeat(dots)}${" ".repeat(3 - dots)}  $elapsed"
    }

    private companion object {
        const val TICK_MILLIS = 400
    }
}
