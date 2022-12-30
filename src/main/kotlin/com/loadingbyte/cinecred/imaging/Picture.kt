package com.loadingbyte.cinecred.imaging

import mkl.testarea.pdfbox2.extract.BoundingBoxFinder
import org.apache.batik.gvt.GraphicsNode
import org.apache.pdfbox.pdmodel.PDDocument
import java.awt.geom.Rectangle2D
import java.awt.image.BufferedImage


sealed class Picture {

    abstract val width: Double
    abstract val height: Double
    abstract fun scaled(scaling: Double): Picture
    open fun dispose() {}


    class Raster(val img: BufferedImage, val scaling: Double = 1.0) : Picture() {

        override val width get() = scaling * img.width
        override val height get() = scaling * img.height

        override fun scaled(scaling: Double) = Raster(img, this.scaling * scaling)

    }


    class SVG(
        val gvtRoot: GraphicsNode, private val docWidth: Double, private val docHeight: Double,
        val scaling: Double = 1.0, val isCropped: Boolean = false
    ) : Picture() {

        override val width get() = scaling * (if (isCropped) gvtRoot.bounds.width else docWidth)
        override val height get() = scaling * (if (isCropped) gvtRoot.bounds.height else docHeight)

        override fun scaled(scaling: Double) = SVG(gvtRoot, docWidth, docHeight, this.scaling * scaling, isCropped)
        fun cropped() = SVG(gvtRoot, docWidth, docHeight, scaling, isCropped = true)

    }


    class PDF private constructor(
        private val aDoc: AugmentedDoc, val scaling: Double, val isCropped: Boolean
    ) : Picture() {

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
