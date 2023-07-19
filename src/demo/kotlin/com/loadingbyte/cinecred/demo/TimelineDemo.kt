package com.loadingbyte.cinecred.demo

import com.loadingbyte.cinecred.common.setHighQuality
import com.loadingbyte.cinecred.common.withG2
import com.loadingbyte.cinecred.ui.helper.PALETTE_BLUE_COLOR
import com.loadingbyte.cinecred.ui.helper.PALETTE_GRAY_COLOR
import com.loadingbyte.cinecred.ui.helper.PALETTE_RED_COLOR
import java.awt.Color
import java.awt.Point
import java.awt.geom.Path2D
import java.awt.geom.RoundRectangle2D
import java.awt.image.BufferedImage
import javax.swing.UIManager


abstract class TimelineDemo(
    filename: String,
    format: Format,
    private val leftMargin: Int = 0,
    private val rightMargin: Int = 0,
    private val leftFade: Int = 0,
    private val rightFade: Int = 0
) : VideoDemo(filename, format) {

    private val innerFrames = mutableListOf<BufferedImage>()

    override fun receiveFrame(frame: BufferedImage) {
        innerFrames.add(frame)
    }

    override fun flushFrames() {
        val (timeline, timelinePos) = drawTimeline()
        for ((i, iFr) in innerFrames.withIndex())
            write(BufferedImage(iFr.width, iFr.height + timeline.height, BufferedImage.TYPE_3BYTE_BGR).withG2 { g2 ->
                g2.drawImage(iFr, 0, 0, null)
                g2.drawImage(timeline, 0, iFr.height, null)
                // Play head
                g2.color = PALETTE_RED_COLOR
                g2.translate(timelinePos.x + i, iFr.height + timelinePos.y)
                g2.fill(PLAY_HEAD)
            })
    }

    private fun drawTimeline(): Pair<BufferedImage, Point> {
        val imgW = innerFrames.first().width
        val imgH = 50
        val timelineW = innerFrames.size
        val timelineX = (imgW - timelineW) / 2
        val timelineY = 10
        val clipY = 16
        val clipH = 20
        val clipArc = 5

        val timeline = BufferedImage(imgW, imgH, BufferedImage.TYPE_3BYTE_BGR).withG2 { g2 ->
            g2.setHighQuality()

            g2.color = UIManager.getColor("Panel.background")
            g2.fillRect(0, 0, imgW, imgH)

            g2.translate(timelineX, timelineY)

            // Clip
            g2.color = PALETTE_BLUE_COLOR
            g2.fill(roundRect(leftMargin, clipY, timelineW - leftMargin - rightMargin, clipH, clipArc))

            // Fades
            g2.color = Color.WHITE
            if (leftFade > 0) {
                val rect = roundRect(leftMargin, clipY, leftFade, clipH - 1, clipArc)
                g2.draw(rect)
                g2.clip(rect)
                g2.drawLine(leftMargin, clipY + clipH, leftMargin + leftFade, clipY)
                g2.clip = null
            }
            if (rightFade > 0) {
                val rect = roundRect(timelineW - rightMargin - rightFade, clipY, rightFade, clipH - 1, clipArc)
                g2.draw(rect)
                g2.clip(rect)
                g2.drawLine(timelineW - rightMargin - rightFade, clipY, timelineW - rightMargin, clipY + clipH)
                g2.clip = null
            }

            // Stoppers
            g2.color = PALETTE_GRAY_COLOR
            g2.scale(-1.0, 1.0)
            g2.fill(STOPPER)
            g2.scale(-1.0, 1.0)
            g2.translate(timelineW, 0)
            g2.fill(STOPPER)
        }
        return Pair(timeline, Point(timelineX, timelineY))
    }

    companion object {

        private const val STOPPER_H = 40
        private val STOPPER = Path2D.Double().apply {
            moveTo(0.0, 0.0)
            lineTo(10.0, 0.0)
            lineTo(10.0, 6.0)
            lineTo(2.0, 14.0)
            lineTo(2.0, STOPPER_H.toDouble())
            lineTo(0.0, STOPPER_H.toDouble())
            closePath()
        }

        private val PLAY_HEAD = Path2D.Double().apply {
            moveTo(-4.0, 0.0)
            lineTo(5.0, 0.0)
            lineTo(5.0, 6.0)
            lineTo(1.0, 10.0)
            lineTo(1.0, STOPPER_H.toDouble())
            lineTo(0.0, STOPPER_H.toDouble())
            lineTo(0.0, 10.0)
            lineTo(-4.0, 6.0)
            closePath()
        }

        private fun roundRect(x: Int, y: Int, w: Int, h: Int, arc: Int) = RoundRectangle2D.Double(
            x.toDouble(), y.toDouble(), w.toDouble(), h.toDouble(), arc.toDouble(), arc.toDouble()
        )

    }

}
