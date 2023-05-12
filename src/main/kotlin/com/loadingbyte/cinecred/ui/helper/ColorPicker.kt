package com.loadingbyte.cinecred.ui.helper

import com.formdev.flatlaf.ui.FlatBorder
import com.formdev.flatlaf.ui.FlatUIUtils
import com.loadingbyte.cinecred.common.l10n
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
import java.text.DecimalFormat
import java.text.ParseException
import javax.swing.*
import javax.swing.JSpinner.NumberEditor
import javax.swing.event.ChangeEvent
import javax.swing.event.ChangeListener
import javax.swing.text.DefaultFormatter
import javax.swing.text.DefaultFormatterFactory
import kotlin.math.roundToInt


class ColorPicker(private val allowAlpha: Boolean) : JComponent() {

    private val hueSpinner = makeSpinner(SpinnerNumberModel(0.0, 0.0, 360.0, 1.0), ::onHueChange)
    private val satSpinner = makeSpinner(SpinnerNumberModel(0.0, 0.0, 100.0, 1.0), ::onSatBriChange)
    private val briSpinner = makeSpinner(SpinnerNumberModel(0.0, 0.0, 100.0, 1.0), ::onSatBriChange)

    private val redSpinner = makeSpinner(SpinnerNumberModel(0, 0, 255, 1), ::onRGBChange)
    private val grnSpinner = makeSpinner(SpinnerNumberModel(0, 0, 255, 1), ::onRGBChange)
    private val bluSpinner = makeSpinner(SpinnerNumberModel(0, 0, 255, 1), ::onRGBChange)
    private val hexTextField = makeHexTextField()

    private val alphaSlider = JSlider(0, 255, 0).apply { addChangeListener { onAlphaChange(true) } }
    private val alphaSpinner = makeSpinner(SpinnerNumberModel(0, 0, 255, 1)) { onAlphaChange(false) }
    private val alphaRangeButton = JButton().apply {
        addActionListener { nextAlphaRange() }
        toolTipText = l10n("ui.form.colorAlphaRangeTooltip")
    }

    private val hueDiagram = HueDiagram().apply { border = FlatBorder() }
    private val satBriDiagram = SatBriDiagram().apply { border = FlatBorder() }

    private val swatches = Swatches(onSelect = { color ->
        withoutOnChange { updateRGBFromColor(color, useAlpha = false) }
        onRGBChange()
    })

    private fun makeSpinner(model: SpinnerNumberModel, onChange: () -> Unit) =
        JSpinner(model).apply {
            if (model.value is Double)
                editor = NumberEditor(this, "#.#")
            addChangeListener { onChange() }
        }

    private fun makeHexTextField() =
        JFormattedTextField(HexColorFormatter(allowAlpha))
            .apply { addPropertyChangeListener("value") { onHexChange() } }

    init {
        resetUI()
        updateHexFromRGBAndAlpha()

        layout = MigLayout(
            "wrap",
            "[][]unrel[right][fill]",
            "[]8[][][]unrel[][][]unrel[]" + if (allowAlpha) "unrel[]" else ""
        )
        add(JLabel(l10n("ui.form.colorSwatches")), "spanx, split 2")
        add(swatches, "growx")
        add(satBriDiagram, "spany 7, growy, width 210")
        add(hueDiagram, "spany 7, growy, width 24")
        add(JLabel(l10n("ui.form.colorHue")).apply { toolTipText = l10n("ui.form.colorHueTooltip") })
        add(hueSpinner)
        add(JLabel(l10n("ui.form.colorSaturation")).apply { toolTipText = l10n("ui.form.colorSaturationTooltip") })
        add(satSpinner)
        add(JLabel(l10n("ui.form.colorBrightness")).apply { toolTipText = l10n("ui.form.colorBrightnessTooltip") })
        add(briSpinner)
        add(JLabel(l10n("ui.form.colorRed")))
        add(redSpinner)
        add(JLabel(l10n("ui.form.colorGreen")))
        add(grnSpinner)
        add(JLabel(l10n("ui.form.colorBlue")))
        add(bluSpinner)
        add(JLabel("Hex"))
        add(hexTextField)
        if (allowAlpha) {
            add(JLabel(l10n("ui.form.colorAlpha")), "spanx, split 4")
            add(alphaSlider, "width 0, growx")
            add(alphaSpinner)
            add(alphaRangeButton)
        }
    }

    // @formatter:off
    private var hue: Double get() = hueSpinner.value as Double / 360.0; set(v) { hueSpinner.value = v * 360.0 }
    private var sat: Double get() = satSpinner.value as Double / 100.0; set(v) { satSpinner.value = v * 100.0 }
    private var bri: Double get() = briSpinner.value as Double / 100.0; set(v) { briSpinner.value = v * 100.0 }

    private var red: Int get() = redSpinner.value as Int; set(v) { redSpinner.value = v }
    private var grn: Int get() = grnSpinner.value as Int; set(v) { grnSpinner.value = v }
    private var blu: Int get() = bluSpinner.value as Int; set(v) { bluSpinner.value = v }

    private var alpha: Int get() = alphaSlider.value; set(v) { alphaSlider.value = v; alphaSpinner.value = v }
    // @formatter:on

    private fun updateHSBFromRGB() {
        val (hue, sat, bri) = Color.RGBtoHSB(red, grn, blu, null)
        this.hue = hue.toDouble()
        this.sat = sat.toDouble()
        this.bri = bri.toDouble()
    }

    private fun updateRGBFromHSB() {
        val rgb = Color.HSBtoRGB(hue.toFloat(), sat.toFloat(), bri.toFloat())
        red = (rgb shr 16) and 0xFF
        grn = (rgb shr 8) and 0xFF
        blu = rgb and 0xFF
    }

    private fun updateRGBAndAlphaFromHex() {
        val argb = (hexTextField.value ?: return) as Int
        red = (argb shr 16) and 0xFF
        grn = (argb shr 8) and 0xFF
        blu = argb and 0xFF
        if (allowAlpha)
            alpha = (argb shr 24) and 0xFF
    }

    private fun updateHexFromRGBAndAlpha() {
        var argb = (red shl 16) or (grn shl 8) or blu
        if (allowAlpha)
            argb = argb or (alpha shl 24)
        hexTextField.value = argb
    }

    private fun updateRGBFromColor(color: Color, useAlpha: Boolean) {
        red = color.red
        grn = color.green
        blu = color.blue
        if (allowAlpha && useAlpha)
            alpha = color.alpha
    }

    private fun onHueChange() {
        if (disableOnChange) return
        withoutOnChange {
            updateRGBFromHSB()
            updateHexFromRGBAndAlpha()
        }
        hueDiagram.repaint()
        satBriDiagram.rerenderImageAndRepaint()
        fireStateChanged()
    }

    private fun onSatBriChange() {
        if (disableOnChange) return
        withoutOnChange {
            updateRGBFromHSB()
            updateHexFromRGBAndAlpha()
        }
        satBriDiagram.repaint()
        fireStateChanged()
    }

    private fun onRGBChange() {
        if (disableOnChange) return
        withoutOnChange {
            updateHexFromRGBAndAlpha()
            updateHSBFromRGB()
        }
        hueDiagram.repaint()
        satBriDiagram.rerenderImageAndRepaint()
        fireStateChanged()
    }

    private fun onHexChange() {
        if (disableOnChange) return
        withoutOnChange {
            updateRGBAndAlphaFromHex()
            updateHSBFromRGB()
        }
        hueDiagram.repaint()
        satBriDiagram.rerenderImageAndRepaint()
        fireStateChanged()
    }

    private fun onAlphaChange(slider: Boolean) {
        if (disableOnChange) return
        withoutOnChange {
            if (slider)
                alphaSpinner.value = alphaSlider.value
            else
                alphaSlider.value = alphaSpinner.value as Int
            updateHexFromRGBAndAlpha()
        }
        fireStateChanged()
    }

    private var alphaRange = AlphaRange.RANGE_255
        set(alphaRange) {
            field = alphaRange
            alphaRangeButton.text = alphaRange.label
            val newEditor = when (alphaRange) {
                AlphaRange.RANGE_255 -> NumberEditor(alphaSpinner)
                AlphaRange.RANGE_100 -> NumberEditor(alphaSpinner).apply {
                    textField.isEditable = true
                    textField.formatterFactory = DefaultFormatterFactory(Alpha100Formatter())
                }
            }
            withoutOnChange {
                alphaSpinner.editor = newEditor
            }
        }

    private fun nextAlphaRange() {
        alphaRange = AlphaRange.values().let { it[(alphaRange.ordinal + 1) % it.size] }
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

    var swatchColors: List<Color> by swatches::colors

    var value: Color
        get() = Color(red, grn, blu, if (allowAlpha) alpha else 255)
        set(value) {
            if (value == this.value)
                return
            withoutOnChange { updateRGBFromColor(value, useAlpha = true) }
            onRGBChange()
        }

    // @formatter:off
    fun addChangeListener(listener: ChangeListener) { listenerList.add(ChangeListener::class.java, listener) }
    fun removeChangeListener(listener: ChangeListener) { listenerList.remove(ChangeListener::class.java, listener) }
    // @formatter:on

    fun resetUI() {
        alphaRange = AlphaRange.RANGE_255
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


    private inner class HueDiagram : Diagram(plane = false) {

        override fun renderImage(w: Int, h: Int, out: IntArray) {
            var i = 0
            for (y in 0 until h) {
                val hue = y / (h - 1).toFloat()
                out.fill(Color.HSBtoRGB(hue, 1f, 1f), i, i + w)
                i += w
            }
        }

        // @formatter:off
        override fun getSelectionY(h: Int) = (hue * (h - 1)).roundToInt()
        override fun setSelectionY(h: Int, selY: Int) { withoutOnChange { hue = selY / (h - 1).toDouble() } }
        override fun onSelectionChange() { onHueChange() }
        // @formatter:on

    }


    private inner class SatBriDiagram : Diagram(plane = true) {

        override fun renderImage(w: Int, h: Int, out: IntArray) {
            val hue = hue.toFloat()
            var i = 0
            for (y in 0 until h) {
                val bri = 1f - y / (h - 1).toFloat()
                for (x in 0 until w) {
                    val sat = x / (w - 1).toFloat()
                    out[i++] = Color.HSBtoRGB(hue, sat, bri)
                }
            }
        }

        // @formatter:off
        override fun getSelectionX(w: Int) = (sat * (w - 1)).roundToInt()
        override fun setSelectionX(w: Int, selX: Int) { withoutOnChange { sat = selX / (w - 1).toDouble() } }
        override fun getSelectionY(h: Int) = ((1.0 - bri) * (h - 1)).roundToInt()
        override fun setSelectionY(h: Int, selY: Int) { withoutOnChange { bri = 1.0 - selY / (h - 1).toDouble() } }
        override fun onSelectionChange() { onSatBriChange() }
        // @formatter:on

    }


    private abstract class Diagram(private val plane: Boolean) : JComponent() {

        private var image: BufferedImage? = null

        init {
            addMouseListener(object : MouseAdapter() {
                override fun mousePressed(e: MouseEvent) {
                    onClickOrDrag(e)
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
            repaint()
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

    }


    private class Swatches(private val onSelect: (Color) -> Unit) : JComponent() {

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

        var colors: List<Color> = emptyList()
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
                g.color = color
                g.fillRect(x, insets.top, SWATCH_SIZE, SWATCH_SIZE)
                if (idx == selectionIdx && FlatUIUtils.isPermanentFocusOwner(this)) {
                    g.color = Color(
                        if (color.red < 125) 255 else 0,
                        if (color.green < 125) 255 else 0,
                        if (color.blue < 125) 255 else 0
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


    private class HexColorFormatter(private val allowAlpha: Boolean) : DefaultFormatter() {

        override fun valueToString(value: Any?): String =
            value?.let { if (allowAlpha) "%08x".format(it) else "%06x".format(it as Int and 0xFFFFFF) } ?: ""

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
                    // See colorFromHex() in Common regarding why we first need to parse to a long here.
                    8 -> string.toLongOrNull(16)?.toInt()?.let {
                        return@stringToValue if (allowAlpha) it else it or (0xFF shl 24)
                    }
                }
            throw ParseException("", 0)
        }

    }


    private class Alpha100Formatter : DefaultFormatter() {

        private val format = DecimalFormat("#.#")

        override fun valueToString(value: Any?): String =
            value?.let { format.format(it as Int * 100 / 255.0) } ?: ""

        override fun stringToValue(string: String?): Int =
            (format.parse(string).toDouble() * 255.0 / 100.0).roundToInt()

    }


    private enum class AlphaRange(val label: String) {
        RANGE_255("0\u2013255"), RANGE_100("0\u2013100")
    }

}
