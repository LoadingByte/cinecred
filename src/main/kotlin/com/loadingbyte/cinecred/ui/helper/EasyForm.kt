package com.loadingbyte.cinecred.ui.helper

import com.loadingbyte.cinecred.common.Severity
import javax.swing.JButton


open class EasyForm(insets: Boolean, noticeArea: Boolean, constLabelWidth: Boolean) :
    Form(insets, noticeArea, constLabelWidth) {

    private class ExtFormRow(
        val formRow: FormRow,
        val isVisibleFunc: (() -> Boolean)?,
        val isEnabledFunc: (() -> Boolean)?,
        val verify: (() -> Notice?)? = null
    ) {
        // We keep track of the widgets which are visible, enabled, and have a verification error (not a warning).
        // One can use this information to determine whether the form is error-free.
        var isErroneous = false
    }


    private val extFormRows = mutableListOf<ExtFormRow>()
    private var submitButton: JButton? = null

    fun <W : Widget<V>, V> addWidget(
        label: String,
        widget: W,
        invisibleSpace: Boolean = false,
        isVisible: (() -> Boolean)? = null,
        isEnabled: (() -> Boolean)? = null,
        verify: ((V) -> Notice?)? = null
    ): W {
        val formRow = FormRow(label, widget)
        addFormRow(formRow, invisibleSpace)
        val doVerify = verify?.let { { it(widget.value) } }
        extFormRows.add(ExtFormRow(formRow, isVisible, isEnabled, doVerify))
        return widget
    }

    fun addSubmitButton(label: String, actionListener: () -> Unit) {
        check(submitButton == null)
        val button = JButton(label)
        button.addActionListener { actionListener() }
        submitButton = button
        add(button, "newline, skip 1, span, align left")
    }

    fun removeSubmitButton() {
        if (submitButton != null)
            remove(submitButton)
        submitButton = null
    }

    override fun onChange(widget: Widget<*>) {
        for (efr in extFormRows) {
            efr.isVisibleFunc?.let { efr.formRow.isVisible = it() }
            efr.isEnabledFunc?.let { efr.formRow.isEnabled = it() }
            efr.verify?.let {
                val notice = it()
                efr.formRow.noticeOverride = notice
                efr.formRow.widget.applySeverity(-1, notice?.severity)
                efr.isErroneous = notice?.severity == Severity.ERROR
            }
        }
        // Disable the submit button if there are errors in the form.
        submitButton?.isEnabled = isErrorFree
        super.onChange(widget)
    }

    val isErrorFree: Boolean
        get() = extFormRows.all { efr -> !efr.formRow.isVisible || !efr.formRow.isEnabled || !efr.isErroneous }

}
