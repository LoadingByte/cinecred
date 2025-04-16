package com.loadingbyte.cinecred.demos

import com.loadingbyte.cinecred.common.l10n
import com.loadingbyte.cinecred.demo.StyleSettingsDemo
import com.loadingbyte.cinecred.imaging.Transition
import com.loadingbyte.cinecred.project.PRESET_TRANSITION_STYLE
import com.loadingbyte.cinecred.project.TransitionStyle
import com.loadingbyte.cinecred.project.st


private const val DIR = "guide/transition-style"

val GUIDE_TRANSITION_STYLE_DEMOS
    get() = listOf(
        GuideTransitionStyleNameDemo,
        GuideTransitionStyleGraphDemo
    )


object GuideTransitionStyleNameDemo : StyleSettingsDemo<TransitionStyle>(
    TransitionStyle::class.java, "$DIR/name", Format.PNG,
    listOf(TransitionStyle::name.st())
) {
    override fun styles() = buildList<TransitionStyle> {
        this += PRESET_TRANSITION_STYLE.copy(name = l10n("project.template.transitionStyleLinear"))
    }
}


object GuideTransitionStyleGraphDemo : StyleSettingsDemo<TransitionStyle>(
    TransitionStyle::class.java, "$DIR/graph", Format.STEP_GIF,
    listOf(TransitionStyle::graph.st())
) {
    override fun styles() = buildList<TransitionStyle> {
        this += PRESET_TRANSITION_STYLE
        this += last().copy(graph = Transition(0.42, 0.0, 1.0, 1.0))
        this += last().copy(graph = Transition(0.42, 0.0, 0.58, 1.0))
        this += last().copy(graph = Transition(0.5, 0.1, 0.58, 1.0))
        this += last().copy(graph = Transition(0.5, 0.1, 0.4, 0.95))
    }
}
