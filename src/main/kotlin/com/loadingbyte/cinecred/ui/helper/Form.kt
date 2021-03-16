package com.loadingbyte.cinecred.ui.helper

import com.formdev.flatlaf.FlatClientProperties.*
import com.loadingbyte.cinecred.common.Severity
import net.miginfocom.swing.MigLayout
import javax.swing.*


open class Form : JPanel(MigLayout("hidemode 3", "[align right][grow]")) {

    abstract class Widget {
        abstract val components: List<JComponent>
        abstract val constraints: List<String>
        abstract val verify: (() -> Unit)?

        var changeListeners = mutableListOf<() -> Unit>()
        protected fun notifyChangeListeners() {
            for (listener in changeListeners)
                listener()
        }
    }

    class VerifyResult(val severity: Severity, msg: String) : Exception(msg)

    private class FormRow(val isVisibleFunc: (() -> Boolean)?, val isEnabledFunc: (() -> Boolean)?) {
        val components = mutableListOf<JComponent>()
        var doVerify: (() -> Unit)? = null

        // We keep track of the form rows which are visible, enabled, and have a verification error (not a warning).
        // One can use this information to determine whether the form is error-free.
        var isVisible = true
            set(value) {
                field = value
                for (comp in components)
                    comp.isVisible = value
            }
        var isEnabled = true
            set(value) {
                field = value
                for (comp in components)
                    comp.isEnabled = value
            }
        var isErroneous = false
    }

    private val formRows = mutableListOf<FormRow>()
    private var submitButton: JButton? = null
    private var isSuspendingChangeEvents = true

    var preChangeListener: ((Widget) -> Unit)? = null
    var postChangeListener: ((Widget) -> Unit)? = null

    fun <W : Widget> addWidget(
        label: String, widget: W,
        isVisible: (() -> Boolean)? = null,
        isEnabled: (() -> Boolean)? = null
    ): W {
        require(widget.components.size == widget.constraints.size)

        widget.changeListeners.add { onChange(widget) }

        val formRow = FormRow(isVisible, isEnabled)

        val jLabel = JLabel(label)
        formRow.components.add(jLabel)
        add(jLabel, "newline")

        formRow.components.addAll(widget.components)
        val endlineGroupId = "g" + System.identityHashCode(jLabel)
        val endlineFieldIds = mutableListOf<String>()
        for ((fieldIdx, field) in widget.components.withIndex()) {
            val fieldConstraints = mutableListOf(widget.constraints[fieldIdx])
            // If the field ends a line, assign it a unique ID. For this, we just use its location in memory. Also add
            // it to the "endlineGroup". These IDs will be used later when positioning the verification components.
            if (fieldIdx == widget.components.lastIndex ||
                "newline" in widget.constraints.getOrElse(fieldIdx + 1) { "" }
            ) {
                val id = "f" + System.identityHashCode(field).toString()
                fieldConstraints.add("id $endlineGroupId.$id")
                endlineFieldIds.add(id)
            }
            // If this field starts a new line, add a skip constraint to skip the label column.
            if ("newline" in fieldConstraints[0])
                fieldConstraints.add("skip 1")
            add(field, fieldConstraints.joinToString())
        }

        val verify = widget.verify
        if (verify != null) {
            val verifyIconLabel = JLabel()
            val verifyMsgArea = newLabelTextArea()
            formRow.components.addAll(arrayOf(verifyIconLabel, verifyMsgArea))

            // Position the verification components using coordinates relative to the fields that are at the line ends.
            val iconLabelId = "c${System.identityHashCode(verifyIconLabel)}"
            val startYExpr = "${endlineFieldIds[0]}.y"
            add(verifyIconLabel, "id $iconLabelId, pos ($endlineGroupId.x2 + 3*rel) ($startYExpr + 3)")
            add(verifyMsgArea, "pos $iconLabelId.x2 $startYExpr visual.x2 null")

            formRow.doVerify = {
                formRow.isErroneous = false
                // Remove FlatLaf outlines.
                for (comp in formRow.components)
                    comp.putClientProperty(OUTLINE, null)
                try {
                    if (formRow.isVisible && formRow.isEnabled)
                        verify()
                    verifyIconLabel.icon = null
                    verifyMsgArea.text = null
                } catch (e: VerifyResult) {
                    verifyIconLabel.icon = SEVERITY_ICON[e.severity]
                    verifyMsgArea.text = e.message
                    if (e.severity == Severity.WARN || e.severity == Severity.ERROR) {
                        // Add FlatLaf outlines.
                        val outline = if (e.severity == Severity.WARN) OUTLINE_WARNING else OUTLINE_ERROR
                        for (comp in formRow.components)
                            comp.putClientProperty(OUTLINE, outline)
                    }
                    if (e.severity == Severity.ERROR)
                        formRow.isErroneous = true
                }
            }
        }

        formRows.add(formRow)

        return widget
    }

    fun addSeparator() {
        add(JSeparator(), "newline, span, growx")
    }

    fun addSubmitButton(label: String, actionListener: () -> Unit) {
        val button = JButton(label)
        button.addActionListener { actionListener() }
        submitButton = button
        add(button, "newline, skip 1, span, align left")
    }

    fun onChange(widget: Widget) {
        if (!isSuspendingChangeEvents) {
            preChangeListener?.invoke(widget)
            updateVerifyAndVisibleAndEnabled()
            postChangeListener?.invoke(widget)
        }
    }

    protected fun finishInit() {
        isSuspendingChangeEvents = false
        updateVerifyAndVisibleAndEnabled()
    }

    fun withoutChangeEvents(block: () -> Unit) {
        isSuspendingChangeEvents = true
        block()
        isSuspendingChangeEvents = false
        updateVerifyAndVisibleAndEnabled()
    }

    private fun updateVerifyAndVisibleAndEnabled() {
        for (formRow in formRows) {
            formRow.doVerify?.invoke()
            formRow.isVisibleFunc?.let { formRow.isVisible = it() }
            formRow.isEnabledFunc?.let { formRow.isEnabled = it() }
        }
        submitButton?.isEnabled = isErrorFree
    }

    val isErrorFree: Boolean
        get() = formRows.all { !it.isVisible || !it.isEnabled || !it.isErroneous }

}
