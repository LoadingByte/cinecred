package com.loadingbyte.cinecred.ui.helper

import com.formdev.flatlaf.FlatClientProperties.*
import com.loadingbyte.cinecred.common.Severity
import net.miginfocom.swing.MigLayout
import java.awt.Dimension
import java.awt.Rectangle
import javax.swing.*


open class Form(insets: Boolean, noticeArea: Boolean, private val constLabelWidth: Boolean) : JPanel(
    MigLayout(
        "hidemode 3, insets " + if (insets) "dialog" else "0",
        "[" + (if (constLabelWidth) "$LABEL_WIDTH_CONSTRAINT!, " else "") + "align right][grow]" +
                if (noticeArea) "150[]" else ""
    )
), Scrollable {

    abstract class Storable<O : Any /* non-null */>(insets: Boolean, noticeArea: Boolean, constLabelWidth: Boolean) :
        Form(insets, noticeArea, constLabelWidth) {
        abstract fun open(stored: O)
        abstract fun save(): O
    }

    interface Widget<V : Any /* non-null */> {
        val components: List<JComponent>
        val constraints: List<String>
        val changeListeners: MutableList<(Widget<*>) -> Unit>
        val visibleListeners: MutableList<(Boolean) -> Unit>
        val enabledListeners: MutableList<(Boolean) -> Unit>
        var value: V
        var isVisible: Boolean
        var isEnabled: Boolean
        fun getSeverity(index: Int): Severity?
        fun setSeverity(index: Int, severity: Severity?)
        fun applyConfigurator(configurator: (Widget<*>) -> Unit)
    }

    interface Choice<VE : Any> {
        var items: List<VE>
    }

    abstract class AbstractWidget<V : Any> : Widget<V> {

        private var severity: Severity? = null

        override val changeListeners = mutableListOf<(Widget<*>) -> Unit>()
        override val visibleListeners = mutableListOf<(Boolean) -> Unit>()
        override val enabledListeners = mutableListOf<(Boolean) -> Unit>()
        protected var disableChangeListeners = false

        override var isVisible = true
            set(isVisible) {
                if (field == isVisible)
                    return
                field = isVisible
                for (comp in components)
                    comp.isVisible = isVisible
                for (listener in visibleListeners)
                    listener(isVisible)
            }

        override var isEnabled = true
            set(isEnabled) {
                if (field == isEnabled)
                    return
                field = isEnabled
                for (comp in components)
                    comp.isEnabled = isEnabled
                for (listener in enabledListeners)
                    listener(isEnabled)
            }

        override fun getSeverity(index: Int): Severity? = severity

        override fun setSeverity(index: Int, severity: Severity?) {
            this.severity = severity
            // Adjust FlatLaf outlines.
            val outline = outline(severity)
            for (comp in components)
                comp.putClientProperty(OUTLINE, outline)
        }

        protected fun outline(severity: Severity?): String? = when (severity) {
            Severity.WARN -> OUTLINE_WARNING
            Severity.ERROR -> OUTLINE_ERROR
            else -> null
        }

        override fun applyConfigurator(configurator: (Widget<*>) -> Unit) {
            configurator(this)
        }

        protected inline fun withoutChangeListeners(block: () -> Unit) {
            disableChangeListeners = true
            try {
                block()
            } finally {
                disableChangeListeners = false
            }
        }

        protected fun notifyChangeListeners() {
            notifyChangeListenersAboutOtherWidgetChange(this)
        }

        protected fun notifyChangeListenersAboutOtherWidgetChange(widget: Widget<*>) {
            if (!disableChangeListeners)
                for (listener in changeListeners)
                    listener(widget)
        }

    }

    class Notice(val severity: Severity, val msg: String?)

    class FormRow(label: String, val widget: Widget<*>) {

        val labelComp = JLabel(label).apply { toolTipText = label }
        val noticeIconComp = JLabel()
        val noticeMsgComp = newLabelTextArea()

        fun setAffixVisible(visible: Boolean) {
            labelComp.isVisible = visible
            noticeIconComp.isVisible = visible
            noticeMsgComp.isVisible = visible
        }

        fun setAffixEnabled(enabled: Boolean) {
            labelComp.isEnabled = enabled
        }

        var notice: Notice? = null
            set(notice) {
                field = notice
                applyEffectiveNotice()
            }

        var noticeOverride: Notice? = null
            set(noticeOverride) {
                field = noticeOverride
                applyEffectiveNotice()
            }

        private fun applyEffectiveNotice() {
            val effectiveNotice = noticeOverride ?: notice
            noticeIconComp.icon = effectiveNotice?.severity?.icon
            noticeMsgComp.text = effectiveNotice?.msg
        }

    }


    companion object {
        private const val LABEL_WIDTH_CONSTRAINT = "min(25%, 250)"
    }

    val changeListeners = mutableListOf<(Widget<*>) -> Unit>()

    private val formRows = mutableListOf<FormRow>()

    protected fun addFormRow(formRow: FormRow, wholeWidth: Boolean = false, invisibleSpace: Boolean = false) {
        val widget = formRow.widget

        require(widget.components.size == widget.constraints.size)
        require(widget.constraints.all { "wrap" !in it }) // we only allow "newline"

        widget.changeListeners.add(::onChange)

        val labelId = "l_${formRows.size}"
        if (!wholeWidth) {
            val labelConstraints = mutableListOf("id $labelId")
            if (constLabelWidth)
                labelConstraints.add("wmax $LABEL_WIDTH_CONSTRAINT")
            if (formRows.isNotEmpty() /* is not the first widget */)
                labelConstraints.add("newline")
            if (invisibleSpace)
                labelConstraints.add("hidemode 0")
            add(formRow.labelComp, labelConstraints.joinToString())
        }

        val endlineGroupId = "g_${formRows.size}"
        for ((fieldIdx, field) in widget.components.withIndex()) {
            val fieldConstraints = mutableListOf(widget.constraints[fieldIdx])
            // Add each field to the "endlineGroup", whose "x2" will as such become the rightmost x border of any field
            // in the row. This "x2" will be used later when positioning the notice components. Because of the syntax,
            // we also need to assign each field a unique ID (after the dot), but it's not used by us anywhere.
            fieldConstraints.add("id $endlineGroupId.f_${formRows.size}_$fieldIdx")
            // If this field starts the first line, and we don't have a row label, start the new form row here.
            if (wholeWidth && fieldIdx == 0)
                fieldConstraints.add("newline")
            // If this field starts a new line, add a skip constraint to skip the label column.
            if (!wholeWidth && "newline" in fieldConstraints[0])
                fieldConstraints.add("skip 1")
            // If this field starts the first or a later line, add a split constraint to make sure all components
            // are located in the same cell horizontally.
            if (fieldIdx == 0 || "newline" in fieldConstraints[0]) {
                fieldConstraints.add("split")
                // If the row doesn't have a label and doesn't reserve a notice area, make the cell which contains
                // the fields span from the label to the final dummy column.
                if (wholeWidth)
                    fieldConstraints.add("span")
            }
            if (invisibleSpace)
                fieldConstraints.add("hidemode 0")
            add(field, fieldConstraints.joinToString())
        }

        // Position the notice components using the rightmost x border coordinate of any field and the y coordinate of
        // the widget's label.
        if (!wholeWidth) {
            val noticeIconId = "n_${formRows.size}"
            add(formRow.noticeIconComp, "id $noticeIconId, pos ($endlineGroupId.x2 + 3*rel) ($labelId.y + 1)")
            add(formRow.noticeMsgComp, "pos ($noticeIconId.x2 + 6) $labelId.y visual.x2 null")
        }

        formRows.add(formRow)
    }

    fun addSeparator() {
        add(JSeparator(), "newline, span, growx")
    }

    protected open fun onChange(widget: Widget<*>) {
        for (listener in changeListeners)
            listener(widget)
    }

    // The baseline of the whole form should be the baseline of the first widget's label.
    override fun getBaseline(width: Int, height: Int) = when {
        formRows.isEmpty() -> -1
        else -> insets.top + formRows.first().labelComp.let { it.y + it.getBaseline(it.width, it.height) }
    }

    // Implementation of the Scrollable interface. The important change is made by the first function
    // getScrollableTracksViewportWidth(). By returning true, we fix the form's width to the width of the surrounding
    // scroll pane viewport, thereby enabling the usage of the component constraint "width 100%" or "pushx, growx"
    // to maximally grow a component into the available horizontal space.
    // The increment functions are adopted from JTextComponent's implementation.

    override fun getScrollableTracksViewportWidth() = true
    override fun getScrollableTracksViewportHeight() = false
    override fun getPreferredScrollableViewportSize(): Dimension = preferredSize

    override fun getScrollableUnitIncrement(visibleRect: Rectangle, orientation: Int, direction: Int) =
        if (orientation == SwingConstants.VERTICAL) visibleRect.height / 20 else visibleRect.width / 20

    override fun getScrollableBlockIncrement(visibleRect: Rectangle, orientation: Int, direction: Int) =
        if (orientation == SwingConstants.VERTICAL) visibleRect.height else visibleRect.width

}
