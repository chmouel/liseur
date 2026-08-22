package com.chmouel.liseur.ui

import androidx.compose.foundation.IndicationNodeFactory
import androidx.compose.foundation.interaction.FocusInteraction
import androidx.compose.foundation.interaction.HoverInteraction
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.material3.LocalContentColor
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.node.CompositionLocalConsumerModifierNode
import androidx.compose.ui.node.DelegatableNode
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.currentValueOf
import androidx.compose.ui.node.invalidateDraw
import kotlinx.coroutines.launch

/**
 * Touch feedback for electronic paper: there, or not there.
 *
 * Material's ripple spreads from the finger and fades, which is a
 * sequence of frames. A panel that takes about a fifth of a second to
 * repaint cannot show a sequence of frames; it shows some arbitrary
 * subset of them, late, as a smear over the control. This draws a flat
 * overlay instead, on when the control is held and off when it is not,
 * which is one repaint each way and the most any of this hardware was
 * ever going to manage.
 *
 * Focus and hover are drawn the same way, so that moving through the app
 * with a D-pad or a keyboard still shows where you are.
 */
object EInkPressIndication : IndicationNodeFactory {

    override fun create(interactionSource: InteractionSource): DelegatableNode =
        EInkPressNode(interactionSource)

    override fun equals(other: Any?): Boolean = other === this

    override fun hashCode(): Int = javaClass.hashCode()
}

/** How much of the content colour a held control is washed with. */
private const val PRESS_ALPHA = 0.16f

private class EInkPressNode(
    private val interactionSource: InteractionSource,
) : Modifier.Node(), DrawModifierNode, CompositionLocalConsumerModifierNode {

    private var presses = 0
    private var focuses = 0
    private var hovers = 0
    private var marked = false

    override fun onAttach() {
        coroutineScope.launch {
            interactionSource.interactions.collect { interaction ->
                when (interaction) {
                    is PressInteraction.Press -> presses++
                    is PressInteraction.Release, is PressInteraction.Cancel -> presses--
                    is FocusInteraction.Focus -> focuses++
                    is FocusInteraction.Unfocus -> focuses--
                    is HoverInteraction.Enter -> hovers++
                    is HoverInteraction.Exit -> hovers--
                }
                // Only a change of state is worth a repaint here, which is
                // the whole point: a press and its release are two frames,
                // not however many the interaction stream happens to send.
                val nowMarked = presses > 0 || focuses > 0 || hovers > 0
                if (marked != nowMarked) {
                    marked = nowMarked
                    invalidateDraw()
                }
            }
        }
    }

    override fun ContentDrawScope.draw() {
        drawContent()
        if (marked) {
            drawRect(color = currentValueOf(LocalContentColor).copy(alpha = PRESS_ALPHA))
        }
    }
}
