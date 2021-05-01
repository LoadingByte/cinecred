package com.loadingbyte.cinecred.ui

import com.loadingbyte.cinecred.common.TRANSLATED_LOCALES
import com.loadingbyte.cinecred.common.l10n
import com.loadingbyte.cinecred.projectio.SPREADSHEET_FORMATS
import com.loadingbyte.cinecred.projectio.SpreadsheetFormat
import com.loadingbyte.cinecred.ui.helper.ComboBoxWidget
import com.loadingbyte.cinecred.ui.helper.Form
import java.util.*
import javax.swing.JOptionPane.*


class CreateForm : Form() {

    val localeWidget = addWidget(
        l10n("ui.create.locale"),
        ComboBoxWidget(
            TRANSLATED_LOCALES, selected = Locale.getDefault(),
            hFill = true, toString = { it.displayName })
    )

    val formatWidget = addWidget(
        l10n("ui.create.format"),
        ComboBoxWidget(
            SPREADSHEET_FORMATS,
            hFill = true, toString = { l10n("ui.create.format.${it.fileExt}") + " (Credits.${it.fileExt})" })
    )


    companion object {
        fun showDialog(): Pair<Locale, SpreadsheetFormat>? {
            val form = CreateForm()
            val option = showConfirmDialog(
                OpenFrame, arrayOf(l10n("ui.create.msg"), form), l10n("ui.create.title"),
                OK_CANCEL_OPTION, QUESTION_MESSAGE
            )
            return if (option == OK_OPTION)
                Pair(form.localeWidget.selectedItem, form.formatWidget.selectedItem)
            else
                null
        }
    }

}
