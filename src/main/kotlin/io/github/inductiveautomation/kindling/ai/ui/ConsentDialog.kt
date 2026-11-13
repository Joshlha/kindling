package io.github.inductiveautomation.kindling.ai.ui

import io.github.inductiveautomation.kindling.ai.AiAvailability
import io.github.inductiveautomation.kindling.ai.AiPreferences
import net.miginfocom.swing.MigLayout
import java.awt.Component
import javax.swing.JCheckBox
import javax.swing.JLabel
import javax.swing.JOptionPane
import javax.swing.JPanel

/**
 * One-time acknowledgement that a thread dump is about to leave the machine.
 *
 * Thread dumps from customer gateways routinely carry SQL fragments, device names, URLs, and
 * project and tag paths inside stack frames. Naming the actual destination matters: someone
 * pointed at a local Ollama needs to see that nothing leaves their machine, and someone with the
 * knowledge base enabled needs to see that the dump is *not* what goes to kapa.
 */
object ConsentDialog {
    /** Returns true when the user has consented, now or previously. */
    fun ensureConsent(parent: Component?): Boolean {
        if (AiPreferences.ConsentAcknowledged.currentValue) return true

        val llm = AiAvailability.client().displayName
        val knowledgeBase = AiAvailability.contextProvider()?.displayName

        val message = buildString {
            append("<html><body style='width: 380px'>")
            append("<p>Thread dumps may contain customer-identifying information. SQL fragments, ")
            append("device names, URLs, and project or tag paths routinely appear in stack frames.</p>")
            append("<p>Sending this dump will transmit it to <b>$llm</b>.</p>")
            if (knowledgeBase != null) {
                append("<p>A short summary of the dump's symptoms will also be sent to <b>$knowledgeBase</b> ")
                append("to look up related support cases. <b>The thread dump itself is never sent there.</b></p>")
            }
            append("</body></html>")
        }

        val dontAsk = JCheckBox("Don't ask again")

        val panel = JPanel(MigLayout("ins 0, wrap 1, fillx")).apply {
            add(JLabel(message), "growx")
            add(dontAsk, "gaptop 8")
        }

        val choice = JOptionPane.showConfirmDialog(
            parent,
            panel,
            "Send thread dump to an external service?",
            JOptionPane.OK_CANCEL_OPTION,
            JOptionPane.WARNING_MESSAGE,
        )

        if (choice != JOptionPane.OK_OPTION) return false

        if (dontAsk.isSelected) {
            AiPreferences.ConsentAcknowledged.currentValue = true
        }
        return true
    }
}
