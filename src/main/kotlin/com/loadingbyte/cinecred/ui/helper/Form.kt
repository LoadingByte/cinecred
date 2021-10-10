package com.loadingbyte.cinecred.ui.helper

import com.formdev.flatlaf.FlatClientProperties.*
import com.loadingbyte.cinecred.common.Severity
import net.miginfocom.swing.MigLayout
import java.awt.Dimension
import java.awt.Rectangle
import javax.swing.*


open class Form(insets: Boolean = true) :
    JPanel(MigLayout("hidemode 3, insets " + if (insets) "dialog" else "0", "[align right][]")),
    Scrollable {

    abstract class Storable<O>(insets: Boolean = true) : Form(insets) {
        abstract fun open(stored: O)
        abstract fun save(): O
    }

    interface Widget<V> {
        val components: List<JComponent>
        val constraints: List<String>
        val changeListeners: MutableList<(Widget<*>) -> Unit>
        var value: V
        var isVisible: Boolean
        var isEnabled: Boolean
        fun applyConfigurator(configurator: (Widget<*>) -> Unit)
        fun applySeverity(index: Int, severity: Severity?)
    }

    interface ChoiceWidget<V, E> : Widget<V> {
        fun updateChoices(choices: Collection<E>)
    }

    abstract class AbstractWidget<V> : Widget<V> {

        override val changeListeners = mutableListOf<(Widget<*>) -> Unit>()

        override var isVisible = true
            set(isVisible) {
                field = isVisible
                for (comp in components)
                    comp.isVisible = isVisible
            }

        override var isEnabled = true
            set(isEnabled) {
                field = isEnabled
                for (comp in components)
                    comp.isEnabled = isEnabled
            }

        override fun applyConfigurator(configurator: (Widget<*>) -> Unit) {
            configurator(this)
        }

        override fun applySeverity(index: Int, severity: Severity?) {
            // Adjust FlatLaf outlines.
            val outline = when (severity) {
                Severity.WARN -> OUTLINE_WARNING
                Severity.ERROR -> OUTLINE_ERROR
                else -> null
            }
            for (comp in components)
                comp.putClientProperty(OUTLINE, outline)
        }

        protected fun notifyChangeListeners() {
            notifyChangeListenersAboutOtherWidgetChange(this)
        }

        protected fun notifyChangeListenersAboutOtherWidgetChange(widget: Widget<*>) {
            for (listener in changeListeners)
                listener(widget)
        }

    }

    class Notice(val severity: Severity, val msg: String?)

    class FormRow(label: String, val widget: Widget<*>) {

        val labelComp = JLabel(label)
        val noticeIconComp = JLabel()
        val noticeMsgComp = newLabelTextArea(insets = false)

        var isVisible: Boolean
            get() = widget.isVisible
            set(isVisible) {
                widget.isVisible = isVisible
                labelComp.isVisible = isVisible
                noticeIconComp.isVisible = isVisible
                noticeMsgComp.isVisible = isVisible
            }

        var isEnabled: Boolean
            get() = widget.isEnabled
            set(isEnabled) {
                widget.isEnabled = isEnabled
                labelComp.isEnabled = isEnabled
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


    val changeListeners = mutableListOf<(Widget<*>) -> Unit>()

    private val formRows = mutableListOf<FormRow>()

    protected fun addFormRow(formRow: FormRow) {
        val widget = formRow.widget

        require(widget.components.size == widget.constraints.size)
        require(widget.constraints.all { "wrap" !in it }) // we only allow "newline"

        widget.changeListeners.add(::onChange)

        val labelId = "l_${formRows.size}"
        add(formRow.labelComp, "id $labelId, " + if (formRows.isEmpty() /* is first widget */) "" else "newline")

        val endlineGroupId = "g_${formRows.size}"
        val endlineFieldIds = mutableListOf<String>()
        for ((fieldIdx, field) in widget.components.withIndex()) {
            val fieldConstraints = mutableListOf(widget.constraints[fieldIdx])
            // If the field ends a line, assign it a unique ID. For this, we just use its location in memory. Also add
            // it to the "endlineGroup". These IDs will be used later when positioning the verification components.
            if (fieldIdx == widget.components.lastIndex ||
                "newline" in widget.constraints.getOrElse(fieldIdx + 1) { "" }
            ) {
                val id = "f_${formRows.size}_$fieldIdx"
                fieldConstraints.add("id $endlineGroupId.$id")
                endlineFieldIds.add(id)
            }
            // If this field starts a new line, add a skip constraint to skip the label column.
            if ("newline" in fieldConstraints[0])
                fieldConstraints.add("skip 1")
            // If this field starts the first or a later line, add a split constraint to make sure all components
            // are located in the same cell horizontally.
            if (fieldIdx == 0 || "newline" in fieldConstraints[0])
                fieldConstraints.add("split")
            add(field, fieldConstraints.joinToString())
        }

        // Position the notice components using x coordinates relative to the fields that are at the line ends
        // and y coordinates relative to the widget's label.
        val noticeIconId = "n_${formRows.size}"
        add(formRow.noticeIconComp, "id $noticeIconId, pos ($endlineGroupId.x2 + 3*rel) ($labelId.y + 1)")
        add(formRow.noticeMsgComp, "pos ($noticeIconId.x2 + 6) $labelId.y visual.x2 null")

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
    override fun getScrollableTracksViewportWidth() = true
    override fun getScrollableTracksViewportHeight() = false
    override fun getPreferredScrollableViewportSize(): Dimension = preferredSize
    override fun getScrollableUnitIncrement(visibleRect: Rectangle, orientation: Int, direction: Int) = 1
    override fun getScrollableBlockIncrement(visibleRect: Rectangle, orientation: Int, direction: Int) =
        if (orientation == SwingConstants.VERTICAL) visibleRect.height else visibleRect.width

}
