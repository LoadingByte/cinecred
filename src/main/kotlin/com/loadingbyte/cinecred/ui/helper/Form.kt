package com.loadingbyte.cinecred.ui.helper

import com.formdev.flatlaf.FlatClientProperties.*
import com.loadingbyte.cinecred.common.Severity
import kotlinx.collections.immutable.ImmutableList
import net.miginfocom.swing.MigLayout
import javax.swing.*


open class Form : JPanel(MigLayout("hidemode 3", "[align right][grow]")) {

    class Notice(val severity: Severity, val msg: String?)

    interface Widget<V> {
        val components: List<JComponent>
        val constraints: List<String>

        val labelComp: JLabel
        val noticeIconComp: JLabel
        val noticeMsgComp: JTextArea

        var value: V
        var changeListeners: MutableList<() -> Unit>
        var isVisible: Boolean
        var isEnabled: Boolean
        var notice: Notice?
        var noticeOverride: Notice?
    }

    abstract class AbstractWidget<V> : Widget<V> {

        override val labelComp = JLabel()
        override val noticeIconComp = JLabel()
        override val noticeMsgComp = newLabelTextArea()

        override var changeListeners = mutableListOf<() -> Unit>()

        override var isVisible = true
            set(isVisible) {
                field = isVisible
                for (comp in components)
                    comp.isVisible = isVisible
                labelComp.isVisible = isVisible
                noticeIconComp.isVisible = isVisible
                noticeMsgComp.isVisible = isVisible
            }

        override var isEnabled = true
            set(isEnabled) {
                field = isEnabled
                for (comp in components)
                    comp.isEnabled = isEnabled
            }

        override var notice: Notice? = null
            set(notice) {
                field = notice
                applyEffectiveNotice()
            }

        override var noticeOverride: Notice? = null
            set(noticeOverride) {
                field = noticeOverride
                applyEffectiveNotice()
            }

        private fun applyEffectiveNotice() {
            val effectiveNotice = noticeOverride ?: notice
            noticeIconComp.icon = effectiveNotice?.let { SEVERITY_ICON[it.severity] }
            noticeMsgComp.text = effectiveNotice?.msg
            // Adjust FlatLaf outlines.
            val outline = when (effectiveNotice?.severity) {
                Severity.WARN -> OUTLINE_WARNING
                Severity.ERROR -> OUTLINE_ERROR
                else -> null
            }
            for (comp in components)
                comp.putClientProperty(OUTLINE, outline)
        }

        protected fun notifyChangeListeners() {
            for (listener in changeListeners)
                listener()
        }

    }

    interface ChoiceWidget<V, E> : Widget<V> {
        var items: ImmutableList<E>
    }

    interface FontRelatedWidget<V> : Widget<V> {
        var projectFamilies: FontFamilies
    }


    private val widgets = mutableListOf<Widget<*>>()

    fun <W : Widget<*>> addWidget(label: String, widget: W): W {
        require(widget.components.size == widget.constraints.size)

        widget.changeListeners.add { onChange(widget) }

        widget.labelComp.text = label
        add(widget.labelComp, "newline")

        val endlineGroupId = "g" + System.identityHashCode(widget.labelComp)
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

        // Position the notice components using coordinates relative to the fields that are at the line ends.
        val iconLabelId = "c${System.identityHashCode(widget.noticeIconComp)}"
        val startYExpr = "${endlineFieldIds[0]}.y"
        add(widget.noticeIconComp, "id $iconLabelId, pos ($endlineGroupId.x2 + 3*rel) ($startYExpr + 3)")
        add(widget.noticeMsgComp, "pos $iconLabelId.x2 $startYExpr visual.x2 null")

        widgets.add(widget)
        return widget
    }

    fun addSeparator() {
        add(JSeparator(), "newline, span, growx")
    }

    fun updateProjectFontFamilies(projectFamilies: FontFamilies) {
        for (widget in widgets)
            if (widget is FontRelatedWidget)
                widget.projectFamilies = projectFamilies
    }

    protected open fun onChange(widget: Widget<*>) {}

}
