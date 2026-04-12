package com.loadingbyte.cinecred.ui

import java.awt.event.KeyEvent
import java.awt.event.KeyEvent.*


enum class Shortcut(val modifiers: Int, val keyCode: Int) {

    ESCAPE(0, VK_ESCAPE),
    CLOSE_WINDOW(CTRL_DOWN_MASK, VK_W),
    QUIT(CTRL_DOWN_MASK, VK_Q),

    TOOLBAR_POLL_CREDITS(0, VK_F5),
    TOOLBAR_OPEN_CREDITS(CTRL_DOWN_MASK, VK_T),
    TOOLBAR_BROWSE_PROJECT_DIR(CTRL_DOWN_MASK, VK_F),
    TOOLBAR_UNDO_STYLING(CTRL_DOWN_MASK, VK_Z),
    TOOLBAR_REDO_STYLING(CTRL_DOWN_MASK or SHIFT_DOWN_MASK, VK_Z),
    TOOLBAR_SAVE_STYLING(CTRL_DOWN_MASK, VK_S),
    TOOLBAR_RESET_STYLING(CTRL_DOWN_MASK, VK_R),
    TOOLBAR_ZOOM_IN(CTRL_DOWN_MASK, VK_PLUS),
    TOOLBAR_ZOOM_OUT(CTRL_DOWN_MASK, VK_MINUS),
    TOOLBAR_ZOOM_RESET(CTRL_DOWN_MASK, VK_0),
    TOOLBAR_TOGGLE_GUIDES(CTRL_DOWN_MASK, VK_G),
    TOOLBAR_OVERLAY_MENU(CTRL_DOWN_MASK, VK_O),
    TOOLBAR_TOGGLE_STYLING_DOCKABLE(CTRL_DOWN_MASK, VK_E),
    TOOLBAR_TOGGLE_PLAYBACK_DOCKABLE(CTRL_DOWN_MASK, VK_P),
    TOOLBAR_TOGGLE_DELIVERY_DOCKABLE(CTRL_DOWN_MASK, VK_J),
    TOOLBAR_HOME(CTRL_DOWN_MASK, VK_H),

    PREVIEW_SWITCH_CREDITS_BOOK_TAB_LEFT(ALT_DOWN_MASK, VK_PAGE_UP),
    PREVIEW_SWITCH_CREDITS_BOOK_TAB_RIGHT(ALT_DOWN_MASK, VK_PAGE_DOWN),
    PREVIEW_SWITCH_CREDITS_TAB_LEFT(SHIFT_DOWN_MASK, VK_PAGE_UP),
    PREVIEW_SWITCH_CREDITS_TAB_RIGHT(SHIFT_DOWN_MASK, VK_PAGE_DOWN),
    PREVIEW_SWITCH_PAGE_TAB_LEFT(CTRL_DOWN_MASK, VK_PAGE_UP),
    PREVIEW_SWITCH_PAGE_TAB_RIGHT(CTRL_DOWN_MASK, VK_PAGE_DOWN),

    STYLING_ADD_PAGE_STYLE(CTRL_DOWN_MASK or SHIFT_DOWN_MASK, VK_P),
    STYLING_ADD_CONTENT_STYLE(CTRL_DOWN_MASK or SHIFT_DOWN_MASK, VK_C),
    STYLING_ADD_LETTER_STYLE(CTRL_DOWN_MASK or SHIFT_DOWN_MASK, VK_L),
    STYLING_ADD_TRANSITION_STYLE(CTRL_DOWN_MASK or SHIFT_DOWN_MASK, VK_T),
    STYLING_ADD_PICTURE_STYLE(CTRL_DOWN_MASK or SHIFT_DOWN_MASK, VK_I),
    STYLING_ADD_TAPE_STYLE(CTRL_DOWN_MASK or SHIFT_DOWN_MASK, VK_V),
    STYLING_DUPLICATE_STYLE(CTRL_DOWN_MASK, VK_D),
    STYLING_REMOVE_STYLE(0, VK_DELETE),

    PLAYBACK_REWIND(0, VK_J),
    PLAYBACK_PAUSE(0, VK_K),
    PLAYBACK_PLAY(0, VK_L),
    PLAYBACK_TOGGLE_PLAY(0, VK_SPACE),
    PLAYBACK_ONE_FRAME_BACK(0, VK_LEFT),
    PLAYBACK_ONE_FRAME_FORWARD(0, VK_RIGHT),
    PLAYBACK_JUMP_TO_START(0, VK_HOME),
    PLAYBACK_JUMP_TO_END(0, VK_END),
    PLAYBACK_TOGGLE_FULL_SCREEN(0, VK_F11),
    // Don't listen for the regular left/right keys here as those are reserved for text fields.
    PLAYBACK_ONE_SECOND_BACK(SHIFT_DOWN_MASK, VK_KP_LEFT),
    PLAYBACK_ONE_SECOND_FORWARD(SHIFT_DOWN_MASK, VK_KP_RIGHT),
    PLAYBACK_TOGGLE_DECK_LINK_CONNECTED(CTRL_DOWN_MASK, VK_L),
    PLAYBACK_TOGGLE_ACTUAL_SIZE(CTRL_DOWN_MASK, VK_1),

    DELIVERY_ADD_RENDER_JOB(CTRL_DOWN_MASK, VK_B),

    HIDDEN_SHOW_LOG(SHIFT_DOWN_MASK or CTRL_DOWN_MASK or ALT_DOWN_MASK, VK_L);


    // Note: We have to rebuild this string each time because the default locale can change.
    val hint: String
        get() = buildString {
            if (modifiers != 0)
                append(getModifiersExText(modifiers)).append("+")
            append(getKeyText(keyCode))
        }

    fun matches(event: KeyEvent): Boolean {
        val mask = SHIFT_DOWN_MASK or CTRL_DOWN_MASK or META_DOWN_MASK or ALT_DOWN_MASK or ALT_GRAPH_DOWN_MASK
        return (event.modifiersEx and mask) == modifiers &&
                (event.keyCode == keyCode || event.keyCode == keypadAlias(keyCode))
    }

    companion object {

        fun match(event: KeyEvent): Shortcut? =
            entries.find { shortcut -> shortcut.matches(event) }

        private fun keypadAlias(keyCode: Int) = when (keyCode) {
            VK_LEFT -> VK_KP_LEFT
            VK_UP -> VK_KP_UP
            VK_RIGHT -> VK_KP_RIGHT
            VK_DOWN -> VK_KP_DOWN
            VK_0 -> VK_NUMPAD0
            VK_1 -> VK_NUMPAD1
            VK_2 -> VK_NUMPAD2
            VK_3 -> VK_NUMPAD3
            VK_4 -> VK_NUMPAD4
            VK_5 -> VK_NUMPAD5
            VK_6 -> VK_NUMPAD6
            VK_7 -> VK_NUMPAD7
            VK_8 -> VK_NUMPAD8
            VK_9 -> VK_NUMPAD9
            VK_PLUS -> VK_ADD
            VK_MINUS -> VK_SUBTRACT
            else -> keyCode
        }

    }

}
