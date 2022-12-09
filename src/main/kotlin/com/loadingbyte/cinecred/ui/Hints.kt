package com.loadingbyte.cinecred.ui

import com.formdev.flatlaf.FlatClientProperties.STYLE
import com.formdev.flatlaf.ui.FlatEmptyBorder
import com.loadingbyte.cinecred.common.l10n
import com.loadingbyte.cinecred.common.withNewG2
import com.loadingbyte.cinecred.projectio.STYLING_FILE_NAME
import com.loadingbyte.cinecred.ui.comms.ProjectsCard
import com.loadingbyte.cinecred.ui.comms.WelcomeTab
import com.loadingbyte.cinecred.ui.view.welcome.WelcomeFrame
import net.miginfocom.swing.MigLayout
import java.awt.*
import java.awt.event.MouseAdapter
import java.awt.geom.Area
import java.awt.geom.Path2D
import java.awt.geom.Rectangle2D
import javax.swing.*
import kotlin.math.roundToInt


enum class Side { TOP, LEFT, BOTTOM, RIGHT, NONE }
class Hint(val message: String, val owner: Component, val side: Side, val preShow: (() -> Unit)? = null)
typealias HintTrack = List<Hint>


fun makeWelcomeHintTrack(welcomeFrame: WelcomeFrame): HintTrack {
    val welcPanel = welcomeFrame.panel
    val projPanel = welcPanel.projectsPanel
    val goProjStartPnl = fun() { welcPanel.setTab(WelcomeTab.PROJECTS); projPanel.projects_setCard(ProjectsCard.START) }
    val goPrefsPnl = fun() { welcPanel.setTab(WelcomeTab.PREFERENCES) }
    @Suppress("DEPRECATION")
    return listOf(
        Hint(l10n("ui.hints.welcomeTrack.welcome"), welcPanel, Side.NONE, goPrefsPnl),
        Hint(l10n("ui.hints.welcomeTrack.create"), projPanel.leakedStartCreateButton, Side.BOTTOM, goProjStartPnl),
        Hint(l10n("ui.hints.welcomeTrack.open"), projPanel.leakedStartOpenButton, Side.BOTTOM, goProjStartPnl),
        Hint(l10n("ui.hints.welcomeTrack.drop"), projPanel.leakedStartDropLabel, Side.TOP, goProjStartPnl)
    )
}

fun makeProjectHintTrack(ctrl: ProjectController): HintTrack {
    val editPanel = ctrl.projectFrame.panel
    val stylingPanel = ctrl.stylingDialog.panel
    val sfn = STYLING_FILE_NAME
    @Suppress("DEPRECATION")
    return listOf(
        Hint(l10n("ui.hints.projectTrack.pageTabs"), editPanel.leakedPageTabs, Side.NONE),
        Hint(l10n("ui.hints.projectTrack.creditsLog"), editPanel.leakedCreditsLog, Side.TOP),
        Hint(l10n("ui.hints.projectTrack.toggleStyling"), editPanel.leakedStylingDialogButton, Side.BOTTOM),
        Hint(l10n("ui.hints.projectTrack.stylingTree", sfn), stylingPanel.leakedStylingTree, Side.RIGHT) {
            ctrl.setDialogVisible(ProjectDialogType.STYLING, true)
        },
        Hint(l10n("ui.hints.projectTrack.resetStyling"), editPanel.leakedResetStylingButton, Side.BOTTOM),
        Hint(l10n("ui.hints.projectTrack.layoutGuides"), editPanel.leakedLayoutGuidesButton, Side.BOTTOM),
        Hint(l10n("ui.hints.projectTrack.finished"), editPanel, Side.NONE)
    )
}


fun HintTrack.play(onPass: () -> Unit) {
    // Often, when this function is called, the window where the first hint appears hasn't been validated yet.
    // To circumvent the hint briefly appearing at a strange position and then moving to its proper position,
    // we delay the appearance of the first hint for one time step.
    SwingUtilities.invokeLater { showHint(this, 0, onPass) }
}

// Inspired from here:
// https://github.com/JFormDesigner/FlatLaf/blob/main/flatlaf-demo/src/main/java/com/formdev/flatlaf/demo/HintManager.java
private fun showHint(track: HintTrack, idx: Int, onPass: () -> Unit) {
    val hint = track[idx]
    hint.preShow?.invoke()

    val layeredPane = SwingUtilities.getRootPane(hint.owner).layeredPane

    val popup = JPanel()

    fun removePopup() {
        layeredPane.remove(popup)
        layeredPane.repaint(popup.x, popup.y, popup.width, popup.height)
    }

    popup.apply {
        // Grab all mouse events to avoid that components overlapped by the hint panel receive them.
        addMouseListener(object : MouseAdapter() {})

        isOpaque = false

        val pointerSide = when (hint.side) {
            Side.TOP -> Side.BOTTOM
            Side.LEFT -> Side.RIGHT
            Side.BOTTOM -> Side.TOP
            Side.RIGHT -> Side.LEFT
            Side.NONE -> Side.NONE
        }
        border = TextBubbleBorder(
            pointerSide, pointerGirth = 30f, pointerLength = 15f,
            backgroundColor = Color(255, 175, 77),
            outlineWidth = 1f, outlineColor = Color(190, 190, 190),
            shadowWidth = 12f, shadowColor = Color.BLACK, shadowPasses = 16
        )

        fun JLabel.style() = apply {
            putClientProperty(STYLE, "foreground: #0F0F0F")
        }

        fun JButton.style() = apply {
            val style = "foreground: #0F0F0F; background: #FFCC8C; borderColor: #C47F2C; " +
                    "hoverBorderColor: #AD6F21; focusedBorderColor: #AD6F21; focusColor: lighten(#AD6F21,10%)"
            putClientProperty(STYLE, style)
        }

        layout = MigLayout("insets dialog", "[:300:]", "[]para[]")
        add(JLabel("<html>${hint.message}</html>").style(), "growx")
        if (idx == track.lastIndex) {
            add(JButton(l10n("ui.hints.notPassed")).style().apply {
                addActionListener { removePopup() }
            }, "newline, right, split 2")
            add(JButton(l10n("ui.hints.passed")).style().apply {
                addActionListener { removePopup(); onPass() }
            })
        } else
            add(JButton(l10n("ui.hints.next")).style().apply {
                addActionListener { removePopup(); showHint(track, idx + 1, onPass) }
            }, "newline, right")
    }

    fun sizeAndPositionPopup() {
        // Give the popup its proper size.
        popup.size = popup.preferredSize
        // Position the popup.
        val gap = 2
        val popupSize = popup.size
        val ownerLoc = SwingUtilities.convertPoint(hint.owner, 0, 0, layeredPane)
        val ownerSize = hint.owner.size
        val centeredX = ownerLoc.x + (ownerSize.width - popupSize.width) / 2
        val centeredY = ownerLoc.y + (ownerSize.height - popupSize.height) / 2
        popup.location = when (hint.side) {
            Side.TOP -> Point(centeredX, ownerLoc.y - popupSize.height - gap)
            Side.LEFT -> Point(ownerLoc.x - popupSize.width - gap, centeredY)
            Side.BOTTOM -> Point(centeredX, ownerLoc.y + ownerSize.height + gap)
            Side.RIGHT -> Point(ownerLoc.x + ownerSize.width + gap, centeredY)
            Side.NONE -> Point(centeredX, centeredY)
        }
    }

    // For some reason, the JLabel only does word-wrapping and then computes its real preferred height
    // once it has been added to some full component tree and spent some time there. We don't know why this happens,
    // but it means that we don't know the popup's preferred height before it has been painted to the screen once
    // or twice. To cope with this, we size and position the popup now, and then again after it has been painted once,
    // and finally again after it has been painted twice.
    sizeAndPositionPopup()
    SwingUtilities.invokeLater {
        sizeAndPositionPopup()
        SwingUtilities.invokeLater(::sizeAndPositionPopup)
    }

    // The 0 index is important. If we leave it out, other components can for some reason overlap the popups when
    // they repaint (e.g., because of a hover event).
    layeredPane.add(popup, JLayeredPane.POPUP_LAYER, 0)
}


private class TextBubbleBorder(
    private val pointerSide: Side,
    private val pointerGirth: Float,
    private val pointerLength: Float,
    private val backgroundColor: Color,
    private val outlineWidth: Float,
    private val outlineColor: Color,
    private val shadowWidth: Float,
    private val shadowColor: Color,
    private val shadowPasses: Int
) : FlatEmptyBorder(
    Insets(0, 0, 0, 0).apply {
        val atPointerSide = (shadowWidth + pointerLength).roundToInt()
        val elsewhere = shadowWidth.roundToInt()
        when (pointerSide) {
            Side.TOP -> set(atPointerSide, elsewhere, elsewhere, elsewhere)
            Side.LEFT -> set(elsewhere, atPointerSide, elsewhere, elsewhere)
            Side.BOTTOM -> set(elsewhere, elsewhere, atPointerSide, elsewhere)
            Side.RIGHT -> set(elsewhere, elsewhere, elsewhere, atPointerSide)
            Side.NONE -> set(elsewhere, elsewhere, elsewhere, elsewhere)
        }
    }
) {

    init {
        require(shadowWidth >= outlineWidth)
    }

    override fun paintBorder(c: Component, g: Graphics, x: Int, y: Int, width: Int, height: Int) {
        var x1 = x + shadowWidth / 2f
        var x2 = x + width - shadowWidth / 2f
        var y1 = y + shadowWidth / 2f
        var y2 = y + height - shadowWidth / 2f
        when (pointerSide) {
            Side.LEFT -> x1 += pointerLength
            Side.RIGHT -> x2 -= pointerLength
            Side.TOP -> y1 += pointerLength
            Side.BOTTOM -> y2 -= pointerLength
            Side.NONE -> {}
        }
        val xm = (x1 + x2) / 2f
        val ym = (y1 + y2) / 2f

        val bubble = Area(Rectangle2D.Float(x1, y1, x2 - x1, y2 - y1))

        if (pointerSide != Side.NONE) {
            val pointer = Path2D.Float().apply {
                when (pointerSide) {
                    Side.TOP -> {
                        moveTo(xm, y1 - pointerLength)
                        lineTo(xm - pointerGirth / 2f, y1)
                        lineTo(xm + pointerGirth / 2f, y1)
                    }
                    Side.BOTTOM -> {
                        moveTo(xm, y2 + pointerLength)
                        lineTo(xm - pointerGirth / 2f, y2)
                        lineTo(xm + pointerGirth / 2f, y2)
                    }
                    Side.LEFT -> {
                        moveTo(x1 - pointerLength, ym)
                        lineTo(x1, ym - pointerGirth / 2f)
                        lineTo(x1, ym + pointerGirth / 2f)
                    }
                    Side.RIGHT -> {
                        moveTo(x2 + pointerLength, ym)
                        lineTo(x2, ym - pointerGirth / 2f)
                        lineTo(x2, ym + pointerGirth / 2f)
                    }
                    Side.NONE -> throw IllegalStateException()
                }
                closePath()
            }
            bubble.add(Area(pointer))
        }

        g.withNewG2 { g2 ->
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)

            // Draw the shadow by just drawing the outline multiple times with varying alphas and line widths.
            for (pass in 1..shadowPasses) {
                g2.color = Color(shadowColor.red, shadowColor.green, shadowColor.blue, pass)
                g2.stroke = BasicStroke(shadowWidth * (1 + shadowPasses - pass) / shadowPasses)
                g2.draw(bubble)
            }

            g2.color = backgroundColor
            g2.fill(bubble)

            g2.color = outlineColor
            g2.stroke = BasicStroke(outlineWidth)
            g2.draw(bubble)
        }
    }

}
