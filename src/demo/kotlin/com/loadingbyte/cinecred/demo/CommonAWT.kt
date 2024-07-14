package com.loadingbyte.cinecred.demo

import com.loadingbyte.cinecred.common.Resolution
import com.loadingbyte.cinecred.imaging.*
import com.loadingbyte.cinecred.imaging.Canvas
import com.loadingbyte.cinecred.ui.helper.preserveTransform
import com.loadingbyte.cinecred.ui.helper.usableBounds
import com.loadingbyte.cinecred.ui.view.playback.PlaybackDialog
import org.bytedeco.ffmpeg.global.avutil.AV_PIX_FMT_BGR24
import java.awt.*
import java.awt.color.ColorSpace
import java.awt.image.*
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import javax.swing.SwingUtilities
import kotlin.concurrent.withLock
import kotlin.math.roundToInt
import com.loadingbyte.cinecred.imaging.ColorSpace as ImagingColorSpace


inline fun buildImage(width: Int, height: Int, type: Int, draw: (Graphics2D) -> Unit): BufferedImage {
    val image = BufferedImage(width, height, type)
    val g2 = image.createGraphics()
    draw(g2)
    g2.dispose()
    return image
}


val gCfg: GraphicsConfiguration =
    GraphicsEnvironment.getLocalGraphicsEnvironment().defaultScreenDevice.defaultConfiguration

fun edt(block: () -> Unit) {
    val lock = ReentrantLock()
    val condition = lock.newCondition()
    lock.withLock {
        SwingUtilities.invokeLater {
            block()
            lock.withLock { condition.signal() }
        }
        // Some AWT actions block the EDT thread (e.g., opening a modal dialog). To avoid deadlocking the demo,
        // stop waiting for the action to return after some time has passed.
        condition.await(5, TimeUnit.SECONDS)
    }
}


fun printWithPopups(component: Component): BufferedImage =
    buildImage(component.width, component.height, BufferedImage.TYPE_3BYTE_BGR) { g2 ->
        printWithPopups(component, g2)
    }

fun printWithPopups(component: Component, g2: Graphics2D) {
    edt {
        val window = SwingUtilities.getWindowAncestor(component)
        if (window.isVisible)
            component.print(g2)
        if (window is PlaybackDialog) {
            @Suppress("DEPRECATION")
            val canvas = window.panel.leakedVideoCanvas
            if (SwingUtilities.isDescendingFrom(canvas, component))
                g2.preserveTransform {
                    val canvasLocInComp = SwingUtilities.convertPoint(canvas, 0, 0, component)
                    g2.translate(canvasLocInComp.x, canvasLocInComp.y)
                    canvas.print(g2)
                }
        }
        forEachVisiblePopupOf(window) { popup ->
            g2.preserveTransform {
                val compLocOnScreen = component.locationOnScreen
                g2.translate(popup.x - compLocOnScreen.x, popup.y - compLocOnScreen.y)
                popup.print(g2)
            }
        }
    }
}

inline fun forEachVisiblePopupOf(window: Window, action: (Window) -> Unit) {
    for (popup in Window.getWindows())
        if (popup.isVisible && popup.type == Window.Type.POPUP) {
            var owner = popup
            while ((owner ?: continue) != window)
                owner = owner.owner
            action(popup)
        }
}


fun reposition(window: Window, width: Int, height: Int) {
    edt {
        window.minimumSize = Dimension(0, 0)

        // Add the window decorations to the size.
        val insets = window.insets
        val winWidth = width + insets.left + insets.right
        val winHeight = height + insets.top + insets.bottom

        val screenBounds = gCfg.usableBounds
        window.setBounds(
            screenBounds.x + (screenBounds.width - winWidth) / 2,
            screenBounds.y + (screenBounds.height - winHeight) / 2,
            winWidth, winHeight
        )
    }
}


val CANVAS_REPRESENTATION = Canvas.compatibleRepresentation(
    ImagingColorSpace.of(ImagingColorSpace.Primaries.BT709, ImagingColorSpace.Transfer.BLENDING)
)

val BGR24_REPRESENTATION = Bitmap.Representation(
    Bitmap.PixelFormat.of(AV_PIX_FMT_BGR24), ImagingColorSpace.SRGB, Bitmap.Alpha.OPAQUE
)

fun bgr24BitmapToImage(bitmap: Bitmap): BufferedImage {
    require(bitmap.spec.representation == BGR24_REPRESENTATION)
    val (w, h) = bitmap.spec.resolution
    val data = bitmap.getB(w * 3)
    val dataBuffer = DataBufferByte(data, data.size)
    val raster = Raster.createInterleavedRaster(dataBuffer, w, h, w * 3, 3, intArrayOf(2, 1, 0), null)
    val cs = ColorSpace.getInstance(ColorSpace.CS_sRGB)
    val cm = ComponentColorModel(cs, intArrayOf(8, 8, 8), false, false, Transparency.OPAQUE, DataBuffer.TYPE_BYTE)
    return BufferedImage(cm, raster, false, null)
}

fun defImageToImage(grounding: Color4f, layers: List<DeferredImage.Layer>, defImg: DeferredImage): BufferedImage {
    val res = Resolution(defImg.width.roundToInt(), defImg.height.resolve().roundToInt())
    val canvasBitmap = Bitmap.allocate(Bitmap.Spec(res, CANVAS_REPRESENTATION))
    val bgr24Bitmap = Bitmap.allocate(Bitmap.Spec(res, BGR24_REPRESENTATION))
    Canvas.forBitmap(canvasBitmap).use { canvas ->
        canvas.fill(Canvas.Shader.Solid(grounding))
        defImg.materialize(canvas, cache = null, layers)
    }
    BitmapConverter.convert(canvasBitmap, bgr24Bitmap, promiseOpaque = true)
    val img = bgr24BitmapToImage(bgr24Bitmap)
    canvasBitmap.close()
    bgr24Bitmap.close()
    return img
}
