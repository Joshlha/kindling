package io.github.inductiveautomation.kindling.ai.ui

import com.formdev.flatlaf.extras.FlatSVGIcon
import net.miginfocom.swing.MigLayout
import javax.swing.JButton
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.UIManager
import javax.swing.border.EmptyBorder

/**
 * An inline, dismissible error bar with an optional action.
 *
 * Deliberately not a `JOptionPane`: background work must never produce a modal. An analysis that
 * fails while the user is reading a stack trace should inform them, not interrupt them.
 */
class WarningStrip : JPanel(MigLayout("ins 4, fillx, gap 6, hidemode 3")) {
    private val label = JLabel()
    private val actionButton = JButton()
    private val dismiss = JButton("Dismiss")

    init {
        isVisible = false
        border = EmptyBorder(2, 4, 2, 4)
        background = UIManager.getColor("Component.warning.focusedBorderColor")
            ?: UIManager.getColor("Panel.background")

        add(JLabel(FlatSVGIcon("icons/bx-error.svg").derive(0.75f)))
        add(label, "push, growx")
        add(actionButton)
        add(dismiss)

        dismiss.addActionListener { hideMessage() }
    }

    fun show(
        message: String,
        actionLabel: String? = null,
        action: (() -> Unit)? = null,
    ) {
        label.text = "<html>$message</html>"

        if (actionLabel != null && action != null) {
            actionButton.isVisible = true
            actionButton.text = actionLabel
            for (listener in actionButton.actionListeners) actionButton.removeActionListener(listener)
            actionButton.addActionListener {
                hideMessage()
                action()
            }
        } else {
            actionButton.isVisible = false
        }

        isVisible = true
        revalidate()
    }

    fun hideMessage() {
        isVisible = false
        revalidate()
    }
}
