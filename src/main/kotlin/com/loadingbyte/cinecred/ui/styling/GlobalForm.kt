package com.loadingbyte.cinecred.ui.styling

import com.loadingbyte.cinecred.common.Severity
import com.loadingbyte.cinecred.common.l10n
import com.loadingbyte.cinecred.project.FPS
import com.loadingbyte.cinecred.project.Global
import com.loadingbyte.cinecred.projectio.toFPS
import com.loadingbyte.cinecred.projectio.toString2
import com.loadingbyte.cinecred.ui.helper.*
import kotlinx.collections.immutable.toImmutableList
import java.text.NumberFormat
import java.util.*
import javax.swing.SpinnerNumberModel


class GlobalForm : Form() {

    companion object {

        private val SUGGESTED_FRAC_FPS = listOf(
            23.98 to FPS(24000, 1001),
            29.97 to FPS(30000, 1001),
            47.95 to FPS(48000, 1001),
            59.94 to FPS(60000, 1001)
        )
        private val SUGGESTED_FPS: List<FPS>

        init {
            val suggestedIntFPS = listOf(24, 25, 30, 48, 50, 60)
                .map { it.toDouble() to FPS(it, 1) }
            SUGGESTED_FPS = (SUGGESTED_FRAC_FPS + suggestedIntFPS)
                .sortedBy { it.first }
                .map { it.second }
        }

        private fun String.fpsFromDisplayString(): FPS {
            runCatching(NumberFormat.getNumberInstance()::parse).onSuccess { frac ->
                SUGGESTED_FRAC_FPS.find { it.first == frac }?.let { return it.second }
            }
            runCatching(String::toInt).onSuccess { return FPS(it, 1) }
            return toFPS()
        }

        private fun FPS.toDisplayString(): String {
            val frac = SUGGESTED_FRAC_FPS.find { it.second == this }?.first
            return when {
                frac != null -> NumberFormat.getNumberInstance().format(frac)
                denominator == 1 -> numerator.toString()
                else -> toString2()
            }
        }

    }

    private val widthPxWidget = addWidget(
        l10n("ui.styling.global.widthPx"),
        SpinnerWidget(SpinnerNumberModel(1, 1, null, 10))
    )
    private val heightPxWidget = addWidget(
        l10n("ui.styling.global.heightPx"),
        SpinnerWidget(SpinnerNumberModel(1, 1, null, 10))
    )
    private val fpsWidget = addWidget(
        l10n("ui.styling.global.fps"),
        ComboBoxWidget(
            SUGGESTED_FPS.map { it.toDisplayString() },
            isEditable = true,
            verify = {
                if (it.isBlank())
                    throw VerifyResult(Severity.ERROR, l10n("ui.blank"))
                try {
                    it.fpsFromDisplayString()
                } catch (_: IllegalArgumentException) {
                    throw VerifyResult(Severity.ERROR, l10n("ui.styling.global.illFormattedFPS"))
                }
            })
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
    private val localeWidget = addWidget(
        l10n("ui.styling.global.locale"),
        InconsistentComboBoxWidget(
            Locale.getAvailableLocales().sortedBy { it.displayName },
            toString = { it.displayName })
    )
    private val uppercaseExceptionsWidget = addWidget(
        l10n("ui.styling.global.uppercaseExceptions"),
        TextAreaWidget(
            grow = false,
            verify = { throw VerifyResult(Severity.INFO, l10n("ui.styling.global.uppercaseExceptionsHint")) })
    )

    init {
        finishInit()
    }

    private fun load(global: Global) {
        widthPxWidget.value = global.widthPx
        heightPxWidget.value = global.heightPx
        fpsWidget.selectedItem = global.fps.toDisplayString()
        backgroundWidget.selectedColor = global.background
        unitVGapPxWidget.value = global.unitVGapPx
        localeWidget.selectedItem = global.locale
        uppercaseExceptionsWidget.text = global.uppercaseExceptions.joinToString("\n")
    }

    private fun save() = Global(
        widthPxWidget.value as Int,
        heightPxWidget.value as Int,
        fpsWidget.selectedItem.fpsFromDisplayString(),
        backgroundWidget.selectedColor,
        unitVGapPxWidget.value as Float,
        localeWidget.selectedItem,
        uppercaseExceptionsWidget.text.split("\n").filter(String::isNotBlank).toImmutableList()
    )

    fun open(global: Global, onChange: (Global) -> Unit) {
        withoutChangeListeners {
            load(global)
            postChangeListener = { if (isErrorFree) onChange(save()) }
        }
    }

}
