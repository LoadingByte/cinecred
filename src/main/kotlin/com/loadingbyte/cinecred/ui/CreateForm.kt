package com.loadingbyte.cinecred.ui

import com.loadingbyte.cinecred.common.TRANSLATED_LOCALES
import com.loadingbyte.cinecred.common.l10n
import com.loadingbyte.cinecred.projectio.SPREADSHEET_FORMATS
import com.loadingbyte.cinecred.projectio.SpreadsheetFormat
import com.loadingbyte.cinecred.ui.helper.ComboBoxWidget
import com.loadingbyte.cinecred.ui.helper.EasyForm
import java.util.*
import javax.swing.JOptionPane


class CreateForm : EasyForm() {

    private val localeWidget = addWidget(
        l10n("ui.create.locale"),
        ComboBoxWidget(
            Locale::class.java, TRANSLATED_LOCALES,
            hFill = true, toString = { it.displayName })
    )

    private val formatWidget = addWidget(
        l10n("ui.create.format"),
        ComboBoxWidget(
            SpreadsheetFormat::class.java, SPREADSHEET_FORMATS,
            hFill = true, toString = { l10n("ui.create.format.${it.fileExt}") + " (Credits.${it.fileExt})" })
    )

    init {
        localeWidget.value = Locale.getDefault()
    }


    companion object {
        fun showDialog(): Pair<Locale, SpreadsheetFormat>? {
            val form = CreateForm()
            val option = JOptionPane.showConfirmDialog(
                null, arrayOf(l10n("ui.create.msg"), form), "Cinecred \u2013 ${l10n("ui.create.title")}",
                JOptionPane.OK_CANCEL_OPTION
            )
            return if (option == JOptionPane.OK_OPTION)
                Pair(form.localeWidget.value, form.formatWidget.value)
            else
                null
        }
    }

}
