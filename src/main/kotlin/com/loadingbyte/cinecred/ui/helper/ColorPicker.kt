package com.loadingbyte.cinecred.ui.helper

import com.formdev.flatlaf.ui.FlatBorder
import com.formdev.flatlaf.ui.FlatUIUtils
import com.loadingbyte.cinecred.common.l10n
import com.loadingbyte.cinecred.imaging.Color4f
import com.loadingbyte.cinecred.imaging.ColorSpace
import net.miginfocom.swing.MigLayout
import java.awt.Color
import java.awt.Dimension
import java.awt.Graphics
import java.awt.event.*
import java.awt.event.KeyEvent.*
import java.awt.image.BufferedImage
import java.awt.image.DataBufferInt
import java.awt.image.DirectColorModel
import java.awt.image.Raster
import java.text.ParseException
import javax.swing.*
import javax.swing.event.ChangeEvent
import javax.swing.event.ChangeListener
import javax.swing.text.DefaultFormatter
import kotlin.math.roundToInt


class ColorPicker(allowNonSRGB: Boolean, private val allowAlpha: Boolean) : JComponent() {

    private val swatches = Swatches(onSelect = ::value::set)

    private val hueSatDiagram = HueSatDiagram().apply { border = FlatBorder() }
    private val briDiagram = BriDiagram().apply { border = FlatBorder() }

    private val hexTextField = makeHexTextField()

    private val hueScrubber = makeScrubber(360f, ::onHueSatChange)
    private val satScrubber = makeScrubber(1f, ::onHueSatChange)
    private val briScrubber = makeScrubber(1f, ::onBriChange)

    private val redScrubber = makeScrubber(1f, ::onRGBChange)
    private val grnScrubber = makeScrubber(1f, ::onRGBChange)
    private val bluScrubber = makeScrubber(1f, ::onRGBChange)

    private val alphaScrubber = makeScrubber(1f, ::onAlphaChange)

    private val priList = makeList(ColorSpace.Primaries.COMMON.toTypedArray(), ::onPriTrcChange)
    private val trcList = makeList(ColorSpace.Transfer.COMMON.toTypedArray(), ::onPriTrcChange)
    private val hdrButton = JToggleButton("HDR").apply {
        toolTipText = l10n("ui.form.colorHDRTooltip")
        addActionListener { onHDRChange() }
    }

    private fun makeScrubber(max: Float, onChange: () -> Unit) =
        Scrubber(Scrubber.NumericScheme(Float::class.javaObjectType, precision = if (max <= 1f) 3 else 1)).apply {
            limiter = Scrubber.NumberLimiter(0f, max)
            sensitivity = max / 200.0
            addValueListener { onChange() }
        }

    private fun <E> makeList(items: Array<E>, onChange: () -> Unit) =
        JList(items).apply {
            border = FlatBorder()
            selectionMode = ListSelectionModel.SINGLE_SELECTION
            selectedIndex = 0
            // When the user ctrl-clicks the selected item to deselect it, cancel that action.
            addListSelectionListener { e -> if (isSelectionEmpty) selectedIndex = e.firstIndex else onChange() }
        }

    private fun makeHexTextField() =
        JFormattedTextField(HexColorFormatter()).apply {
            setCommitsOnValidEdit(this)
            addPropertyChangeListener("value") { onHexChange() }
        }

    init {
        updateHexFromRGB()

        layout = MigLayout(
            "wrap",
            "[][]unrel[right][fill]" + if (allowNonSRGB) "unrel[]" else "",
            "[]8[]unrel[][][]unrel[][][]" + if (allowAlpha) "unrel[top]" else ""
        )
        add(JLabel(l10n("ui.form.colorSwatches")), "spanx, split 2")
        add(swatches, "growx")
        add(hueSatDiagram, "spany 8, growy, width 220")
        add(briDiagram, "spany 8, growy, width 24")
        add(JLabel("sRGB"))
        add(hexTextField)
        if (allowNonSRGB) {
            add(JLabel(l10n("gamut")), "spany 8, split 5, flowy, center")
            add(priList, "growx, gaptop 2")
            add(JLabel("EOTF"), "center")
            add(trcList, "growx, gaptop 2")
            add(hdrButton, "growx")
        }
        add(JLabel(l10n("ui.form.colorHue")).apply { toolTipText = l10n("ui.form.colorHueTooltip") })
        add(hueScrubber)
        add(JLabel(l10n("ui.form.colorSaturation")).apply { toolTipText = l10n("ui.form.colorSaturationTooltip") })
        add(satScrubber)
        add(JLabel(l10n("ui.form.colorBrightness")).apply { toolTipText = l10n("ui.form.colorBrightnessTooltip") })
        add(briScrubber)
        add(JLabel(l10n("ui.form.colorRed")))
        add(redScrubber)
        add(JLabel(l10n("ui.form.colorGreen")))
        add(grnScrubber)
        add(JLabel(l10n("ui.form.colorBlue")))
        add(bluScrubber)
        if (allowAlpha) {
            add(JLabel(l10n("ui.form.colorAlpha").apply { toolTipText = l10n("ui.form.colorAlphaTooltip") }))
            add(alphaScrubber)
        }
    }

    // @formatter:off
    private var hue: Float get() = hueScrubber.value / 360f; set(v) { hueScrubber.value = v * 360f }
    private var sat: Float get() = satScrubber.value; set(v) { satScrubber.value = v }
    private var bri: Float get() = briScrubber.value; set(v) { briScrubber.value = v }

    private var red: Float get() = redScrubber.value; set(v) { redScrubber.value = v }
    private var grn: Float get() = grnScrubber.value; set(v) { grnScrubber.value = v }
    private var blu: Float get() = bluScrubber.value; set(v) { bluScrubber.value = v }

    private var alpha: Float get() = alphaScrubber.value; set(v) { alphaScrubber.value = v }

    private val colorSpace: ColorSpace get() = ColorSpace.of(priList.selectedValue, trcList.selectedValue)
    private val hdr: Boolean get() = hdrButton.isSelected
    // @formatter:on

    private fun updateHSBFromRGB() {
        val (hue, sat, bri) = Color4f(red, grn, blu, 1f, colorSpace).toHSB()
        this.hue = hue
        this.sat = sat
        this.bri = bri
    }

    private fun updateRGBFromHSB() {
        val c = Color4f.fromHSB(hue, sat, bri, colorSpace)
        red = c.r
        grn = c.g
        blu = c.b
    }

    private fun updateRGBFromHex() {
        val c = Color4f.fromSRGBPacked((hexTextField.value ?: return) as Int).convert(colorSpace)
        red = c.r
        grn = c.g
        blu = c.b
    }

    private fun updateHexFromRGB() {
        val c = Color4f(red, grn, blu, colorSpace).convert(ColorSpace.SRGB)
        hexTextField.value = if (c.isClamped()) c.toSRGBPacked() else null
    }

    private fun updateRGBFromColor(color: Color4f, useAlpha: Boolean) {
        val c = color.convert(colorSpace, clamp = true, ceiling = if (hdr) null else 1f)
        red = c.r
        grn = c.g
        blu = c.b
        if (allowAlpha && useAlpha)
            alpha = c.a
    }

    private fun onHueSatChange() {
        if (disableOnChange) return
        withoutOnChange {
            updateRGBFromHSB()
            updateHexFromRGB()
        }
        hueSatDiagram.repaint()
        briDiagram.rerenderImageAndRepaint()
        cached = null
        fireStateChanged()
    }

    private fun onBriChange() {
        if (disableOnChange) return
        withoutOnChange {
            updateRGBFromHSB()
            updateHexFromRGB()
        }
        if (hdr) briDiagram.rerenderImageAndRepaint() else briDiagram.repaint()
        cached = null
        fireStateChanged()
    }

    private fun onRGBChange() {
        if (disableOnChange) return
        withoutOnChange {
            updateHexFromRGB()
            updateHSBFromRGB()
        }
        hueSatDiagram.repaint()
        briDiagram.rerenderImageAndRepaint()
        cached = null
        fireStateChanged()
    }

    private fun onHexChange() {
        if (disableOnChange) return
        withoutOnChange {
            updateRGBFromHex()
            updateHSBFromRGB()
        }
        hueSatDiagram.repaint()
        briDiagram.rerenderImageAndRepaint()
        cached = null
        fireStateChanged()
    }

    private var prevColorSpace = ColorSpace.SRGB
    private fun onPriTrcChange() {
        val prevColorSpace = this.prevColorSpace
        this.prevColorSpace = colorSpace
        if (disableOnChange) return
        withoutOnChange {
            updateRGBFromColor(Color4f(red, grn, blu, prevColorSpace), useAlpha = false)
            updateHexFromRGB()
            updateHSBFromRGB()
        }
        hueSatDiagram.rerenderImageAndRepaint()
        briDiagram.rerenderImageAndRepaint()
        cached = null
        fireStateChanged()
    }

    private fun onHDRChange() {
        if (disableOnChange) return
        val max = if (hdr) null else 1f
        withoutOnChange {
            briScrubber.limiter = Scrubber.NumberLimiter(0f, max)
            redScrubber.limiter = Scrubber.NumberLimiter(0f, max)
            grnScrubber.limiter = Scrubber.NumberLimiter(0f, max)
            bluScrubber.limiter = Scrubber.NumberLimiter(0f, max)
        }
        withoutOnChange {
            updateRGBFromColor(Color4f(red, grn, blu, colorSpace), useAlpha = false)  // clamps the color
            updateHexFromRGB()
            updateHSBFromRGB()
        }
        hueSatDiagram.repaint()
        briDiagram.rerenderImageAndRepaint()
        cached = null
        fireStateChanged()
    }

    private fun onAlphaChange() {
        if (disableOnChange) return
        cached = null
        fireStateChanged()
    }

    private var disableOnChange = false
    private inline fun withoutOnChange(block: () -> Unit) {
        disableOnChange = true
        try {
            block()
        } finally {
            disableOnChange = false
        }
    }

    var swatchColors: List<Color4f>
        get() = swatches.colors
        set(swatchColors) {
            swatches.colors = swatchColors
        }

    private var cached: Color4f? = null
    var value: Color4f
        get() = cached ?: Color4f(red, grn, blu, if (allowAlpha) alpha else 1f, colorSpace).also { cached = it }
        set(value) {
            if (value == this.value)
                return
            val priIdx = ColorSpace.Primaries.COMMON.indexOf(value.colorSpace.primaries)
            val trcIdx = ColorSpace.Transfer.COMMON.indexOf(value.colorSpace.transfer)
            if (priIdx != -1 && trcIdx != -1 && value.isClamped(ceiling = null)) {
                cached = value
                withoutOnChange {
                    priList.selectedIndex = priIdx
                    trcList.selectedIndex = trcIdx
                    hdrButton.isSelected = !value.isClamped(ceiling = 1f)
                }
            } else {
                val trc = ColorSpace.Transfer.SRGB
                outer@ for (hdr in booleanArrayOf(false, true))
                    for (pri in ColorSpace.Primaries.COMMON) {
                        val isContained = value.convert(ColorSpace.of(pri, trc)).isClamped(if (hdr) null else 1f)
                        // Note: If no color space contains the value, just use the largest one and clamp the color.
                        if (isContained || hdr && pri == ColorSpace.Primaries.COMMON.last()) {
                            withoutOnChange {
                                priList.selectedIndex = ColorSpace.Primaries.COMMON.indexOf(pri)
                                trcList.selectedIndex = ColorSpace.Transfer.COMMON.indexOf(trc)
                                hdrButton.isSelected = hdr
                            }
                            cached = if (isContained) value else null
                            break@outer
                        }
                    }
            }
            withoutOnChange {
                updateRGBFromColor(value, useAlpha = true)
                updateHexFromRGB()
                updateHSBFromRGB()
            }
            hueSatDiagram.rerenderImageAndRepaint()
            briDiagram.rerenderImageAndRepaint()
            fireStateChanged()
        }

    // @formatter:off
    fun addChangeListener(listener: ChangeListener) { listenerList.add(ChangeListener::class.java, listener) }
    fun removeChangeListener(listener: ChangeListener) { listenerList.remove(ChangeListener::class.java, listener) }
    // @formatter:on

    fun resetUI() {
        // When the color picker is part of a popup that is closed while an invalid value is entered into a text field,
        // that value will persist once the popup is re-opened. To fix that, we force all text fields to update.
        withoutOnChange {
            resetScrubber(hueScrubber)
            resetScrubber(satScrubber)
            resetScrubber(briScrubber)
            resetScrubber(redScrubber)
            resetScrubber(grnScrubber)
            resetScrubber(bluScrubber)
            hexTextField.value = hexTextField.value
        }
    }

    private fun resetScrubber(scrubber: Scrubber<Float>) {
        scrubber.value = scrubber.value
    }

    private fun fireStateChanged() {
        val e = ChangeEvent(this)
        val listeners = listenerList.listenerList
        var idx = listeners.size - 2
        while (idx >= 0) {
            if (listeners[idx] == ChangeListener::class.java)
                (listeners[idx + 1] as ChangeListener).stateChanged(e)
            idx -= 2
        }
    }

    private fun setCommitsOnValidEdit(textField: JFormattedTextField) {
        (textField.formatter as DefaultFormatter).commitsOnValidEdit = true
    }


    private inner class BriDiagram : Diagram(plane = false) {

        private var lim = 1f
        private var disableLimitChange = false

        override fun renderImage(w: Int, h: Int, out: IntArray) {
            if (!disableLimitChange)
                lim = if (hdr && bri > 1f) 2f * bri else if (hdr) 2f else 1f
            val cs = colorSpace
            val hdr = hdr
            val hue = hue
            val sat = sat
            var prevBri = 0f
            var i = 0
            for (y in 0..<h) {
                val bri = (1f - y / (h - 1).toFloat()) * lim
                val c = Color4f.fromHSB(hue, sat, bri, cs).toSRGBPacked()
                out.fill(c, i, i + w)
                if (hdr && bri <= 1f && prevBri > 1f)
                    for (x in 0..<w)
                        out[i + x] = if (x % 4 < 2) 0 else 0xFFFFFF
                prevBri = bri
                i += w
            }
        }

        override fun getSelectionY(h: Int) = ((1f - bri / lim) * (h - 1)).roundToInt()
        override fun setSelectionY(h: Int, selY: Int) = withoutOnChange { bri = (1f - selY / (h - 1).toFloat()) * lim }

        override fun onSelectionChange() {
            // While the user drags, keep the limit constant.
            disableLimitChange = true
            onBriChange()
            disableLimitChange = false
        }

        override fun onMouseReleased() {
            // Once the user stops dragging, update the limit and repaint the diagram.
            rerenderImageAndRepaint()
        }

    }


    private inner class HueSatDiagram : Diagram(plane = true) {

        override fun renderImage(w: Int, h: Int, out: IntArray) {
            val cs = colorSpace
            val bri = when (cs.transfer) {
                ColorSpace.Transfer.PQ -> 0.58f
                ColorSpace.Transfer.HLG -> 0.75f
                else -> 1f
            }
            var i = 0
            for (y in 0..<h) {
                val sat = 1f - y / (h - 1).toFloat()
                for (x in 0..<w) {
                    val hue = x / (w - 1).toFloat()
                    out[i++] = Color4f.fromHSB(hue, sat, bri, cs).toSRGBPacked()
                }
            }
        }

        override fun getSelectionX(w: Int) = (hue * (w - 1)).roundToInt()
        override fun setSelectionX(w: Int, selX: Int) = withoutOnChange { hue = selX / (w - 1).toFloat() }
        override fun getSelectionY(h: Int) = ((1f - sat) * (h - 1)).roundToInt()
        override fun setSelectionY(h: Int, selY: Int) = withoutOnChange { sat = 1f - selY / (h - 1).toFloat() }
        override fun onSelectionChange() = onHueSatChange()

    }


    private abstract class Diagram(private val plane: Boolean) : JComponent() {

        private var image: BufferedImage? = null

        init {
            addMouseListener(object : MouseAdapter() {
                override fun mousePressed(e: MouseEvent) {
                    onClickOrDrag(e)
                }

                override fun mouseReleased(e: MouseEvent) {
                    onMouseReleased()
                }
            })
            addMouseMotionListener(object : MouseMotionAdapter() {
                override fun mouseDragged(e: MouseEvent) {
                    onClickOrDrag(e)
                }
            })
        }

        private fun onClickOrDrag(e: MouseEvent) {
            val insets = this.insets
            val h = height - insets.top - insets.bottom
            setSelectionY(h, (e.y - insets.top).coerceIn(0, h - 1))
            if (plane) {
                val w = width - insets.left - insets.right
                setSelectionX(w, (e.x - insets.left).coerceIn(0, w - 1))
            }
            onSelectionChange()
        }

        fun rerenderImageAndRepaint() {
            image = null
            paintImmediately(0, 0, width, height)
        }

        override fun paintComponent(g: Graphics) {
            super.paintComponent(g)

            val insets = this.insets
            val w = width - insets.left - insets.right
            val h = height - insets.top - insets.bottom
            if (w <= 0 || h <= 0)
                return
            if (image.let { it == null || it.width != w || it.height != h }) {
                val array = IntArray(w * h).also { renderImage(w, h, it) }
                // Adapted from BufferedImage(w, h, BufferedImage.TYPE_INT_RGB):
                val cm = DirectColorModel(24, 0x00FF0000, 0x0000FF00, 0x000000FF, 0)
                val raster = Raster.createPackedRaster(DataBufferInt(array, array.size), w, h, w, cm.masks, null)
                image = BufferedImage(cm, raster, false, null)
            }
            g.drawImage(image, insets.left, insets.top, null)

            val sy = insets.top + getSelectionY(h)
            if (plane) {
                val sx = insets.left + getSelectionX(w)
                g.color = Color.WHITE
                g.fillRect(sx - 9, sy - 1, 19, 3)
                g.fillRect(sx - 1, sy - 9, 3, 19)
                g.color = Color.BLACK
                g.fillRect(sx - 8, sy, 17, 1)
                g.fillRect(sx, sy - 8, 1, 17)
            } else {
                g.color = Color.WHITE
                g.fillRect(insets.left, sy - 1, w, 3)
                g.color = Color.BLACK
                g.fillRect(insets.left, sy, w, 1)
            }
        }

        protected abstract fun renderImage(w: Int, h: Int, out: IntArray)
        protected open fun getSelectionX(w: Int): Int = throw UnsupportedOperationException()
        protected open fun setSelectionX(w: Int, selX: Int): Unit = throw UnsupportedOperationException()
        protected abstract fun getSelectionY(h: Int): Int
        protected abstract fun setSelectionY(h: Int, selY: Int)
        protected abstract fun onSelectionChange()
        protected open fun onMouseReleased() {}

    }


    private class Swatches(private val onSelect: (Color4f) -> Unit) : JComponent() {

        private var selectionIdx = 0

        init {
            isFocusable = true
            addFocusListener(object : FocusListener {
                override fun focusGained(e: FocusEvent) = repaint()
                override fun focusLost(e: FocusEvent) = repaint()
            })

            addKeyListener(object : KeyAdapter() {
                override fun keyPressed(e: KeyEvent) {
                    when (e.keyCode) {
                        VK_LEFT, VK_KP_LEFT -> if (selectionIdx > 0) {
                            selectionIdx--
                            repaint()
                        }
                        VK_RIGHT, VK_KP_RIGHT -> if (selectionIdx < colors.lastIndex) {
                            selectionIdx++
                            repaint()
                        }
                        VK_SPACE -> if (colors.isNotEmpty()) onSelect(colors[selectionIdx])
                    }
                }
            })

            addMouseListener(object : MouseAdapter() {
                override fun mousePressed(e: MouseEvent) {
                    if (SwingUtilities.isLeftMouseButton(e)) {
                        requestFocusInWindow()
                        val insets = this@Swatches.insets
                        // Check that the user didn't click on the insets.
                        if (e.x in insets.left..width - insets.right && e.y in insets.top..height - insets.bottom) {
                            val x = e.x - insets.left
                            // Check that the user didn't click on a gap.
                            if (x % (SWATCH_SIZE + SWATCH_GAP) < SWATCH_SIZE) {
                                val rawIdx = x / (SWATCH_SIZE + SWATCH_GAP)
                                if (rawIdx in colors.indices) {
                                    selectionIdx = rawIdx
                                    repaint()
                                    onSelect(colors[selectionIdx])
                                }
                            }
                        }
                    }
                }
            })
        }

        var colors: List<Color4f> = emptyList()
            set(colors) {
                field = colors
                selectionIdx = 0
            }

        override fun getPreferredSize() = Dimension(0, SWATCH_SIZE + insets.run { top + bottom })

        override fun paintComponent(g: Graphics) {
            super.paintComponent(g)

            val insets = this.insets
            val y = insets.top
            var x = insets.left
            for ((idx, color) in colors.withIndex()) {
                g.color = color.toSRGBAWT()
                g.fillRect(x, insets.top, SWATCH_SIZE, SWATCH_SIZE)
                if (idx == selectionIdx && FlatUIUtils.isPermanentFocusOwner(this)) {
                    g.color = Color(
                        if (color.r < 0.5f) 255 else 0,
                        if (color.g < 0.5f) 255 else 0,
                        if (color.b < 0.5f) 255 else 0
                    )
                    g.fillRect(x, y, SWATCH_SIZE, 1)
                    g.fillRect(x, y, 1, SWATCH_SIZE)
                    g.fillRect(x, y + SWATCH_SIZE - 1, SWATCH_SIZE, 1)
                    g.fillRect(x + SWATCH_SIZE - 1, y, 1, SWATCH_SIZE)
                    g.drawLine(x, y, x + SWATCH_SIZE - 1, y + SWATCH_SIZE - 1)
                    g.drawLine(x, y + SWATCH_SIZE - 1, x + SWATCH_SIZE - 1, y)
                }
                x += SWATCH_SIZE + SWATCH_GAP
                if (x + SWATCH_SIZE > width - insets.right)
                    break
            }
        }

        companion object {
            private const val SWATCH_SIZE = 16
            private const val SWATCH_GAP = 4
        }

    }


    private class HexColorFormatter : DefaultFormatter() {

        override fun valueToString(value: Any?): String =
            value?.let { "%06X".format(it as Int and 0xFFFFFF) } ?: ""

        override fun stringToValue(string: String?): Int {
            if (string != null)
                when (string.length) {
                    3 -> string.toIntOrNull(16)?.let {
                        val a = it and 0xF00
                        val b = it and 0xF0
                        val c = it and 0xF
                        return@stringToValue (a shl 12) or (a shl 8) or (b shl 8) or (b shl 4) or (c shl 4) or c or
                                (0xFF shl 24)
                    }
                    6 -> string.toIntOrNull(16)?.let {
                        return@stringToValue it or (0xFF shl 24)
                    }
                }
            throw ParseException("", 0)
        }

    }

}
