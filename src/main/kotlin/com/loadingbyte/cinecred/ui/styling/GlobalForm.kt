package com.loadingbyte.cinecred.ui.styling

import com.loadingbyte.cinecred.common.Severity
import com.loadingbyte.cinecred.common.l10n
import com.loadingbyte.cinecred.project.Global
import com.loadingbyte.cinecred.projectio.toFPS
import com.loadingbyte.cinecred.ui.helper.ColorWellWidget
import com.loadingbyte.cinecred.ui.helper.ComboBoxWidget
import com.loadingbyte.cinecred.ui.helper.Form
import com.loadingbyte.cinecred.ui.helper.SpinnerWidget
import javax.swing.SpinnerNumberModel


object GlobalForm : Form() {

    private val SUGGESTED_FPS = floatArrayOf(23.97f, 24f, 25f, 29.97f, 30f, 59.94f, 60f)

    private val fpsWidget = addWidget(
        l10n("ui.styling.global.fps"),
        ComboBoxWidget(
            SUGGESTED_FPS.map { "%.3f".format(it).dropLast(1) },
            isEditable = true,
            verify = {
                if (it.isBlank())
                    throw VerifyResult(Severity.ERROR, l10n("general.blank"))
                try {
                    it.toFPS()
                } catch (_: IllegalArgumentException) {
                    throw VerifyResult(Severity.ERROR, l10n("ui.styling.global.illFormattedFPS"))
                }
            })
    )
    private val widthPxWidget = addWidget(
        l10n("ui.styling.global.widthPx"),
        SpinnerWidget(SpinnerNumberModel(1, 1, null, 10))
    )
    private val heightPxWidget = addWidget(
        l10n("ui.styling.global.heightPx"),
        SpinnerWidget(SpinnerNumberModel(1, 1, null, 10))
    )
    private val backgroundWidget = addWidget(
        l10n("ui.styling.global.background"),
        ColorWellWidget(
            allowAlpha = false,
            verify = { throw VerifyResult(Severity.INFO, l10n("ui.styling.global.backgroundTransparencyHint")) })
    )
    private val unitVGapPxWidget = addWidget(
        l10n("ui.styling.global.unitVGapPx"),
        SpinnerWidget(SpinnerNumberModel(1f, 0.01f, null, 1f))
    )

    init {
        finishInit()
    }

    private fun load(global: Global) {
        fpsWidget.selectedItem =
            if (global.fps.denominator == 1) global.fps.numerator.toString()
            else "%.3f".format(global.fps.frac).dropLast(1)
        widthPxWidget.value = global.widthPx
        heightPxWidget.value = global.heightPx
        backgroundWidget.selectedColor = global.background
        unitVGapPxWidget.value = global.unitVGapPx
    }

    private fun save() = Global(
        fpsWidget.selectedItem.toFPS(),
        widthPxWidget.value as Int,
        heightPxWidget.value as Int,
        backgroundWidget.selectedColor,
        unitVGapPxWidget.value as Float
    )

    fun open(global: Global, onChange: (Global) -> Unit) {
        withSuspendedChangeEvents {
            load(global)
            changeListener = { if (isErrorFree) onChange(save()) }
        }
    }

}
