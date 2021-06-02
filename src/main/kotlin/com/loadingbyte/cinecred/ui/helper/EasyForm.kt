package com.loadingbyte.cinecred.ui.helper

import javax.swing.JButton


open class EasyForm : Form() {

    private class WidgetInfo(
        val isVisibleFunc: (() -> Boolean)?,
        val isEnabledFunc: (() -> Boolean)?,
        val verify: (() -> Notice?)? = null
    ) {
        // We keep track of the widgets which are visible, enabled, and have a verification error (not a warning).
        // One can use this information to determine whether the form is error-free.
        var isErroneous = false
    }


    private val widgetInfo = HashMap<Widget<*>, WidgetInfo>()
    private var submitButton: JButton? = null

    fun <W : Widget<V>, V> addWidget(
        label: String,
        widget: W,
        isVisible: (() -> Boolean)? = null,
        isEnabled: (() -> Boolean)? = null,
        verify: ((V) -> Notice?)? = null
    ): W {
        super.addWidget(label, widget)
        val doVerify = verify?.let { { it(widget.value) } }
        widgetInfo[widget] = WidgetInfo(isVisible, isEnabled, doVerify)
        return widget
    }

    fun addSubmitButton(label: String, actionListener: () -> Unit) {
        val button = JButton(label)
        button.addActionListener { actionListener() }
        submitButton = button
        add(button, "newline, skip 1, span, align left")
    }

    override fun onChange(widget: Widget<*>) {
        for ((w, info) in widgetInfo) {
            info.isVisibleFunc?.let { w.isVisible = it() }
            info.isEnabledFunc?.let { w.isEnabled = it() }
            info.verify?.let { w.noticeOverride = it() }
        }
        // Disable the submit button if there are errors in the form.
        submitButton?.isEnabled = isErrorFree
    }

    private val isErrorFree: Boolean
        get() = widgetInfo.all { (widget, info) -> !widget.isVisible || !widget.isEnabled || !info.isErroneous }

}
