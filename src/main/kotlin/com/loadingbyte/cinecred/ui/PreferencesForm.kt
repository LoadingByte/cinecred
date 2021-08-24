package com.loadingbyte.cinecred.ui

import com.loadingbyte.cinecred.common.TRANSLATED_LOCALES
import com.loadingbyte.cinecred.common.l10n
import com.loadingbyte.cinecred.ui.PreferencesController.LocaleWish
import com.loadingbyte.cinecred.ui.helper.*
import java.awt.Toolkit
import java.awt.Window
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import java.util.*
import javax.swing.JDialog
import javax.swing.JFrame
import javax.swing.JOptionPane


class PreferencesForm : EasyForm() {

    private val uiLocaleWishWidget = addWidget(
        l10n("ui.preferences.uiLocaleWish"),
        ComboBoxWidget(
            LocaleWish::class.java,
            listOf(LocaleWish.System) + TRANSLATED_LOCALES.map(LocaleWish::Specific),
            widthSpec = WidthSpec.FILL,
            toString = { wish ->
                when (wish) {
                    is LocaleWish.System -> l10n("ui.preferences.uiLocaleWishSystem")
                    is LocaleWish.Specific ->
                        if (Locale.getDefault() != wish.locale)
                            "${wish.locale.getDisplayName(wish.locale)} (${wish.locale.displayName})"
                        else
                            wish.locale.displayName
                }
            })
    )

    private val checkForUpdatesWidget = addWidget(
        l10n("ui.preferences.checkForUpdates"),
        CheckBoxWidget()
    )

    private val hintTrackPendingWidgets = HINT_TRACK_NAMES.associateWith { trackName ->
        addWidget(
            l10n("ui.preferences.hintTrackPending.$trackName"),
            CheckBoxWidget()
        )
    }


    class Values(val uiLocaleWish: LocaleWish, val checkForUpdates: Boolean, val pendingHintTracks: Set<String>)

    companion object {

        /**
         * The returned boolean signals whether the user has accepted to exit the program.
         */
        fun showDialog(values: Values, parent: Window?, askForRestart: Boolean): Pair<Values, Boolean>? {
            val form = PreferencesForm().apply {
                uiLocaleWishWidget.value = values.uiLocaleWish
                checkForUpdatesWidget.value = values.checkForUpdates
                for (trackName in values.pendingHintTracks)
                    hintTrackPendingWidgets[trackName]?.value = true
            }

            val title = "Cinecred \u2013 ${l10n("ui.preferences.title")}"
            val pane = JOptionPane(
                arrayOf(l10n("ui.preferences.msg"), form),
                JOptionPane.QUESTION_MESSAGE, JOptionPane.OK_CANCEL_OPTION
            )

            // Create the frame or modal dialog, depending on whether there's a parent.
            val useFrame = parent == null
            val window: Window = if (useFrame)
                JFrame(title).apply { isResizable = false }
            else
                JDialog(parent, title, JDialog.DEFAULT_MODALITY_TYPE).apply { isResizable = false }
            // Configure the window and add the JOptionPane.
            window.apply {
                add(pane)
                pack()
                setLocationRelativeTo(null)  // Center
                iconImages = WINDOW_ICON_IMAGES
            }

            // If we're using a frame, showing it will not automatically block this thread. So in that case,
            // we will hand over control a secondary event loop immediately after having set the frame to visible.
            // This way, the primary event loop (this thread) is blocked until the frame is closed.
            val loop = if (!useFrame) null else Toolkit.getDefaultToolkit().systemEventQueue.createSecondaryLoop()

            // This function is called when the window is about to be closed. If necessary, it shows another dialog
            // asking whether the program should exit. This is why it has to be called prior to the window actually
            // being closed. After this function is done, the return value of the outer function is available
            // in the "ret" variable.
            var ret: Pair<Values, Boolean>? = null
            fun onClose() {
                if (pane.value == JOptionPane.OK_OPTION) {
                    val newValues = Values(
                        form.uiLocaleWishWidget.value,
                        form.checkForUpdatesWidget.value,
                        form.hintTrackPendingWidgets.filterValues(CheckBoxWidget::value).keys
                    )
                    var exit = false
                    if (askForRestart && newValues.uiLocaleWish != values.uiLocaleWish) {
                        val newLocale = newValues.uiLocaleWish.resolve()
                        exit = JOptionPane.showConfirmDialog(
                            window, l10n("ui.preferences.restart.msg", newLocale),
                            l10n("ui.preferences.restart.title", newLocale),
                            JOptionPane.OK_CANCEL_OPTION
                        ) == JOptionPane.OK_OPTION
                    }
                    ret = Pair(newValues, exit)
                }
                // If we're using a frame, explicitly hand control back over to the primary event loop.
                loop?.exit()
                // If we're using a dialog, dispose it. This will also hand back control to the primary event loop.
                window.dispose()
            }

            // When the user tries to close the window or presses either OK or Cancel, call the onClose() function.
            window.addWindowListener(object : WindowAdapter() {
                override fun windowClosing(e: WindowEvent) {
                    onClose()
                }
            })
            pane.addPropertyChangeListener { e ->
                if (e.propertyName == JOptionPane.VALUE_PROPERTY)
                    onClose()
            }

            // Show the window.
            // If we're using a dialog, this hands over control to the secondary event loop.
            window.isVisible = true
            // If we're using a frame, hand over control to the secondary event loop explicitly.
            loop?.enter()

            return ret
        }

    }

}
