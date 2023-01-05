package com.loadingbyte.cinecred.ui.helper

import com.formdev.flatlaf.ui.FlatFormattedTextFieldUI
import com.formdev.flatlaf.ui.FlatTextFieldUI
import com.loadingbyte.cinecred.common.ceilDiv
import java.awt.Shape
import javax.swing.JComponent
import javax.swing.UIManager
import javax.swing.plaf.basic.BasicTextFieldUI
import javax.swing.text.*


/**
 * All components whose UIs extend from [BasicTextFieldUI] share the same issue: when confronted with a font whose
 * height (according to FontMetrics) is odd while the component's height is even (or the other way around), they
 * vertically center the text one pixel too high in comparison to all other components (at least those used by us).
 * This not only looks bad, but also causes vertical misalignment between components by 1px under baseline alignment.
 *
 * The cause for this is simple: when computing the vertical offset of the text, they use the formula
 *     `(componentHeight - fontHeight) / 2`
 * while all other components effectively use
 *     `ceilDiv(componentHeight, fontHeight)`.
 *
 * As such, to resolve the issue, we just need to replace the former snippet with the latter in two places:
 *   - [BasicTextFieldUI.getBaseline]. As of JDK 17, the offending lines are:
 *     ```
 *     int slop = height - vspan;
 *     baseline += slop / 2;
 *     ```
 *   - [FieldView.adjustAllocation]. As of JDK 17, the offending lines are:
 *     ```
 *     int slop = bounds.height - vspan;
 *     bounds.y += slop / 2;
 *     ```
 *
 * Actually, [BasicTextFieldUI.create], which is the method that returns [View]s, can also return
 * [BasicTextFieldUI.I18nFieldView], which could be adapted in the same way but is package-protected, and [GlyphView],
 * which is very distinct and would require deeper study to fix. Both of these views are only used for bidirectional
 * text. This fix ignores both of them for now. Astoundingly however, in practice the fix seems to still work in bidi
 * cases, but we are not certain why.
 *
 * Because we do not want to manipulate the JDK's bytecode at runtime, this method instead replaces both all subclasses
 * of [BasicTextFieldUI] and [FieldView] with custom subclasses whose implementations of the offending methods replace
 * the floor division with ceil division.
 */
fun fixTextFieldVerticalCentering() {
    UIManager.put("TextFieldUI", CcTextFieldUI::class.java.name)
    UIManager.put("FormattedTextFieldUI", CcFormattedTextFieldUI::class.java.name)
    UIManager.put("PasswordFieldUI", CcPasswordFieldUI::class.java.name)
}


class CcTextFieldUI : FlatTextFieldUI() {
    companion object {
        @JvmStatic
        fun createUI(@Suppress("UNUSED_PARAMETER") c: JComponent) = CcTextFieldUI()
    }

    override fun create(elem: Element): View = postCreate(elem, super.create(elem))
    override fun getBaseline(c: JComponent, width: Int, height: Int): Int =
        postGetBaseline(this, c, height, super.getBaseline(c, width, height))
}

class CcFormattedTextFieldUI : FlatFormattedTextFieldUI() {
    companion object {
        @JvmStatic
        fun createUI(@Suppress("UNUSED_PARAMETER") c: JComponent) = CcFormattedTextFieldUI()
    }

    override fun create(elem: Element): View = postCreate(elem, super.create(elem))
    override fun getBaseline(c: JComponent, width: Int, height: Int): Int =
        postGetBaseline(this, c, height, super.getBaseline(c, width, height))
}

class CcPasswordFieldUI : FlatFormattedTextFieldUI() {
    companion object {
        @JvmStatic
        fun createUI(@Suppress("UNUSED_PARAMETER") c: JComponent) = CcPasswordFieldUI()
    }

    override fun create(elem: Element): View = postCreate(elem, super.create(elem))
    override fun getBaseline(c: JComponent, width: Int, height: Int): Int =
        postGetBaseline(this, c, height, super.getBaseline(c, width, height))
}


/**
 * This snippet is executed after [BasicTextFieldUI.getBaseline] has been executed on the superclass. [superBaseline]
 * provides the baseline returned by the superclass.
 *
 * This function is a copy of the first part of the implementation in the superclass, with the floor division changed
 * to adding the difference between floor and ceil division (unless the superclass determined the baseline to be -1, in
 * which case we skip our adjustment).
 */
private fun postGetBaseline(ui: BasicTextFieldUI, c: JComponent, height: Int, superBaseline: Int): Int {
    var baseline = superBaseline
    if (baseline == -1)
        return -1
    val rootView = ui.getRootView(c as JTextComponent)
    if (rootView.viewCount > 0) {
        val insets = c.insets
        @Suppress("NAME_SHADOWING")
        val height = height - insets.top - insets.bottom
        if (height > 0) {
            val fieldView = rootView.getView(0)
            val vspan = fieldView.getPreferredSpan(View.Y_AXIS).toInt()
            if (height != vspan) {
                val slop = height - vspan
                baseline += slop % 2
            }
        }
    }
    return baseline
}


/**
 * This snippet is executed after [BasicTextFieldUI.create] has been executed on the superclass. [superView] provides
 * the view returned by the superclass.
 *
 * Depending on the kind of view returned by the superclass, this function constructs an equivalent view that however
 * includes our fix for [FieldView.adjustAllocation]. Because the superclass just passes [elem] to the view's
 * constructor with the rest of the function's logic being selection between view types, we can also just pass the same
 * [elem] to the constructor of our custom view.
 *
 * As noted above, the switch is missing BasicTextFieldUI.I18nFieldViewClass and GlyphView. The former could be
 * adapted just like FieldView, but is package-private, and we do not want to introduce split package issues.
 * The latter is very distinct from FieldView, so adapting it would require more work.
 */
private fun postCreate(elem: Element, superView: View): View =
    when (superView.javaClass) {
        FieldView::class.java -> CcFieldView(elem)
        PasswordView::class.java -> CcPasswordView(elem)
        else -> superView
    }


private class CcFieldView(elem: Element) : FieldView(elem) {
    override fun adjustAllocation(a: Shape?): Shape? = super.adjustAllocation(preAdjustAllocation(this, a))
}

private class CcPasswordView(elem: Element) : PasswordView(elem) {
    override fun adjustAllocation(a: Shape?): Shape? = super.adjustAllocation(preAdjustAllocation(this, a))
}


/**
 * This snippet is executed before [FieldView.adjustAllocation] will be executed on the superclass. The shape returned
 * by it will be passed to the implementation in the superclass.
 *
 * This function is a copy of the first part of the implementation in the superclass, with the floor division changed
 * to ceil division. Because the shape will, as a result of this method, already attain a height that matches exactly
 * `vspan`, the corresponding portion of the implementation in the superclass will be skipped by virtue of the if check.
 */
private fun preAdjustAllocation(view: View, a: Shape?): Shape? {
    if (a != null) {
        val bounds = a.bounds
        val vspan = view.getPreferredSpan(View.Y_AXIS).toInt()
        if (bounds.height != vspan) {
            val slop = bounds.height - vspan
            bounds.y += ceilDiv(slop, 2)
            bounds.height -= slop
        }
        return bounds
    }
    return null
}
