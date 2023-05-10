package com.loadingbyte.cinecred.imaging

import com.loadingbyte.cinecred.common.withG2
import mkl.testarea.pdfbox2.extract.BoundingBoxFinder
import org.apache.batik.gvt.GraphicsNode
import org.apache.pdfbox.pdmodel.PDDocument
import java.awt.geom.Rectangle2D
import java.awt.image.BufferedImage
import java.awt.image.BufferedImage.TYPE_3BYTE_BGR
import java.awt.image.BufferedImage.TYPE_4BYTE_ABGR


sealed interface Picture {

    val width: Double
    val height: Double
    fun scaled(scaling: Double): Picture
    fun dispose() {}


    class Raster(img: BufferedImage, val scaling: Double = 1.0) : Picture {

        // Conform non-standard raster images to the 8-bit (A)BGR pixel format and the sRGB color space. This needs to
        // be done at some point because some of our code expects (A)BGR, and we're compositing in sRGB.
        val img =
            if (img.colorModel.colorSpace.isCS_sRGB && img.type.let { it == TYPE_3BYTE_BGR || it == TYPE_4BYTE_ABGR })
                img
            else
                BufferedImage(img.width, img.height, if (img.colorModel.hasAlpha()) TYPE_4BYTE_ABGR else TYPE_3BYTE_BGR)
                    .withG2 { g2 -> g2.drawImage(img, 0, 0, null) }

        override val width get() = scaling * img.width
        override val height get() = scaling * img.height

        override fun scaled(scaling: Double) = Raster(img, this.scaling * scaling)

    }


    class SVG(
        val gvtRoot: GraphicsNode, private val docWidth: Double, private val docHeight: Double,
        val scaling: Double = 1.0, val isCropped: Boolean = false
    ) : Picture {

        override val width get() = scaling * (if (isCropped) gvtRoot.bounds.width else docWidth)
        override val height get() = scaling * (if (isCropped) gvtRoot.bounds.height else docHeight)

        override fun scaled(scaling: Double) = SVG(gvtRoot, docWidth, docHeight, this.scaling * scaling, isCropped)
        fun cropped() = SVG(gvtRoot, docWidth, docHeight, scaling, isCropped = true)

    }


    class PDF private constructor(
        private val aDoc: AugmentedDoc, val scaling: Double, val isCropped: Boolean
    ) : Picture {

        constructor(doc: PDDocument) : this(AugmentedDoc(doc), 1.0, false)

        val doc get() = aDoc.doc
        val minBox get() = aDoc.minBox

        override val width get() = scaling * (if (isCropped) minBox.width else doc.pages[0].cropBox.width.toDouble())
        override val height get() = scaling * (if (isCropped) minBox.height else doc.pages[0].cropBox.height.toDouble())

        override fun scaled(scaling: Double) = PDF(aDoc, this.scaling * scaling, isCropped)
        fun cropped() = PDF(aDoc, scaling, isCropped = true)

        override fun dispose() {
            // Synchronize the closing operation so that other threads can safely check whether a document is closed
            // and then use it in their own synchronized block.
            synchronized(doc) {
                doc.close()
            }
        }

        private class AugmentedDoc(val doc: PDDocument) {
            val minBox: Rectangle2D by lazy {
                // If the document has already been closed, the responsible project has just been closed. In that case,
                // just return a dummy rectangle here to let the project closing finish regularly.
                if (doc.document.isClosed)
                    return@lazy Rectangle2D.Double(0.0, 0.0, 1.0, 1.0)
                val raw = BoundingBoxFinder(doc.pages[0]).apply { processPage(doc.pages[0]) }.boundingBox
                // The raw bounding box y coordinate is actually relative to the bottom of the crop box, so we need
                // to convert it such that it is relative to the top because the rest of our program works like that.
                Rectangle2D.Double(raw.x, doc.pages[0].cropBox.height - raw.y - raw.height, raw.width, raw.height)
            }
        }

    }

}
