package com.loadingbyte.cinecred.demos

import com.loadingbyte.cinecred.common.Timecode
import com.loadingbyte.cinecred.common.l10n
import com.loadingbyte.cinecred.common.useResourcePath
import com.loadingbyte.cinecred.demo.*
import com.loadingbyte.cinecred.imaging.Picture
import com.loadingbyte.cinecred.imaging.Tape
import com.loadingbyte.cinecred.project.*
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toPersistentList
import java.awt.Dimension
import java.lang.Thread.sleep
import kotlin.io.path.*


private const val DIR = "guide/picture-video"

val GUIDE_PICTURE_VIDEO_DEMOS
    get() = listOf(
        GuidePictureVideoAutoAddDemo,
        GuidePictureVideoPictureNameDemo,
        GuidePictureVideoVideoNameDemo,
        GuidePictureVideoPictureFileDemo,
        GuidePictureVideoVideoFileDemo,
        GuidePictureVideoResolutionDemo,
        GuidePictureVideoPictureCropBlankSpaceDemo,
        GuidePictureVideoVideoMovingDemo,
        GuidePictureVideoVideoPreviewDemo,
        GuidePictureVideoVideoSliceDemo,
        GuidePictureVideoVideoTemporallyJustifyDemo,
        GuidePictureVideoVideoTemporalMarginDemo,
        GuidePictureVideoVideoFadeDemo
    )


@Suppress("DEPRECATION")
object GuidePictureVideoAutoAddDemo : ScreencastDemo(
    "$DIR/auto-add", Format.VIDEO_GIF, 1100, 600
) {
    override fun generate() {
        val creditsFile = projectDir.resolve("Credits.csv")
        addProjectWindows(hideStyWin = true, prepareProjectDir = {
            val lines = creditsFile.readLines().toMutableList()
            val idx = lines.indexOfFirst { "Dirc Director" in it }
            lines[idx] = lines[idx].replace("A,", ",").replace(",Film,", ",,")
            lines[idx + 1] = ",,,4,,,,,,"
            lines.subList(idx + 2, lines.size).fill(",,,,,,,,,")
            creditsFile.writeLines(lines)

            val logosDir = projectDir.resolve("Logos")
            logosDir.resolve("Cinecred H.svg").moveTo(logosDir.resolve("Cinecred.svg"))
        })

        val oldStyling = projectCtrl.stylingHistory.current
        val newStyling = oldStyling.copy(
            pageStyles = oldStyling.pageStyles.filter { it.name == l10n("project.PageBehavior.CARD") }
                .toPersistentList(),
            contentStyles = oldStyling.contentStyles.filter { it.name == l10n("project.PageBehavior.CARD") }
                .toPersistentList(),
            letterStyles = oldStyling.letterStyles.filter {
                it.name == l10n("project.template.letterStyleCardName") ||
                        it.name == l10n("project.template.letterStyleCardSmall")
            }.toPersistentList(),
            pictureStyles = persistentListOf(),
            tapeStyles = persistentListOf()
        )
        edt {
            projectCtrl.stylingHistory.loadAndRedraw(newStyling)
            projectCtrl.stylingHistory.save()
        }
        sleep(500)

        val spreadsheetEditorWin = SpreadsheetEditorVirtualWindow(creditsFile, skipRows = 1).apply {
            size = Dimension(600, 350)
            colWidths = intArrayOf(100, 200, 50, 100, 100, 50, 50, 50, 50, 50)
        }
        dt.add(spreadsheetEditorWin)
        dt.snapToSide(spreadsheetEditorWin, rightSide = true)
        dt.toBack(spreadsheetEditorWin)

        sc.hold(2 * hold)
        sc.type(spreadsheetEditorWin, 6, 1, "{{${l10n("projectIO.credits.table.pic")} Cinecred}}", 8 * hold)
        sc.mouseTo(prjWin.desktopPosOf(prjPnl.leakedStylingDialogButton))
        sc.click(4 * hold)
        sc.mouseTo(styWin.desktopPosOfTreeItem(styTree, "Cinecred"))
        sc.click(12 * hold)
        sc.mouseTo(prjWin.desktopPosOf(prjPnl.leakedStylingDialogButton))
        sc.click()
        sc.type(spreadsheetEditorWin, 6, 1, "{{${l10n("projectIO.credits.table.pic")} Cinecred.svg}}")
        sc.mouseTo(prjWin.desktopPosOf(prjPnl.leakedStylingDialogButton))
        sc.click(8 * hold)
        sc.click()
        sc.type(spreadsheetEditorWin, 6, 1, "{{${l10n("projectIO.credits.table.pic")} Cinecred Cropped}}")
        sc.mouseTo(prjWin.desktopPosOf(prjPnl.leakedStylingDialogButton))
        sc.click()
        sc.mouseTo(styWin.desktopPosOfTreeItem(styTree, "Cinecred Cropped"))
        sc.click()
        sc.demonstrateSetting(styWin, styPictForm, PictureStyle::cropBlankSpace.st(), 0)
        sc.hold(4 * hold)
    }
}


object GuidePictureVideoPictureNameDemo : StyleSettingsDemo<PictureStyle>(
    PictureStyle::class.java, "$DIR/picture-name", Format.PNG,
    listOf(PictureStyle::name.st())
) {
    override fun styles() = buildList<PictureStyle> {
        this += PRESET_PICTURE_STYLE.copy(name = "Cinecred Cropped")
    }
}


object GuidePictureVideoVideoNameDemo : StyleSettingsDemo<TapeStyle>(
    TapeStyle::class.java, "$DIR/video-name", Format.PNG,
    listOf(TapeStyle::name.st())
) {
    override fun styles() = buildList<TapeStyle> {
        this += PRESET_TAPE_STYLE.copy(name = "Blooper")
    }
}


object GuidePictureVideoPictureFileDemo : StyleSettingsDemo<PictureStyle>(
    PictureStyle::class.java, "$DIR/picture-file", Format.PNG,
    listOf(PictureStyle::picture.st())
) {
    override val pictureLoaders by lazy {
        val tmpDir = createTempDirectory()
        val picFile = tmpDir.resolve("Cinecred.svg")
        useResourcePath("/logo.svg") { it.copyTo(picFile) }
        listOf(Picture.Loader.recognize(picFile)!!.apply { picture }).also { tmpDir.toFile().deleteRecursively() }
    }

    override fun styles() = buildList<PictureStyle> {
        this += PRESET_PICTURE_STYLE.copy(picture = PictureRef(pictureLoaders[0]))
    }
}


object GuidePictureVideoVideoFileDemo : StyleSettingsDemo<TapeStyle>(
    TapeStyle::class.java, "$DIR/video-file", Format.PNG,
    listOf(TapeStyle::tape.st())
) {
    override val tapes by lazy {
        val tmpDir = createTempDirectory()
        val tapeDir = tmpDir.resolve("Blooper.mov")
        RAINBOW_TAPE.fileOrDir.toFile().copyRecursively(tapeDir.toFile())
        listOf(Tape.recognize(tapeDir)!!.apply { spec }).also { tmpDir.toFile().deleteRecursively() }
    }

    override fun styles() = buildList<TapeStyle> {
        this += PRESET_TAPE_STYLE.copy(tape = TapeRef(tapes[0]))
    }
}


object GuidePictureVideoResolutionDemo : StyleSettingsDemo<PictureStyle>(
    PictureStyle::class.java, "$DIR/resolution", Format.STEP_GIF,
    listOf(PictureStyle::widthPx.st(), PictureStyle::heightPx.st()), pageGuides = true
) {
    override fun styles() = buildList<PictureStyle> {
        this += PIC_STYLE
        this += last().copy(widthPx = Opt(true, 150.0))
        this += last().copy(heightPx = Opt(true, 220.0))
    }

    override fun credits(style: PictureStyle) = PIC_SPREADSHEET.parseCreditsPiS(style)
}


object GuidePictureVideoPictureCropBlankSpaceDemo : StyleSettingsDemo<PictureStyle>(
    PictureStyle::class.java, "$DIR/picture-crop-blank-space", Format.STEP_GIF,
    listOf(PictureStyle::cropBlankSpace.st()), pageGuides = true
) {
    override fun styles() = buildList<PictureStyle> {
        this += PIC_STYLE.copy(widthPx = Opt(true, 150.0))
        this += last().copy(cropBlankSpace = true)
    }

    override fun credits(style: PictureStyle) = PIC_SPREADSHEET.parseCreditsPiS(style)
}


object GuidePictureVideoVideoMovingDemo : VideoDemo("$DIR/video-moving", Format.VIDEO_GIF) {
    override val isLocaleSensitive get() = false
    override fun credits() = TAPE_SPREADSHEET.parseCreditsTS(TAPE_STYLE)
}


object GuidePictureVideoVideoPreviewDemo : PageDemo("$DIR/video-preview", Format.PNG, pageGuides = true) {
    override val isLocaleSensitive get() = false
    override fun credits() = listOf(TAPE_SPREADSHEET.parseCreditsCS())
}


object GuidePictureVideoVideoSliceDemo : StyleSettingsTimelineDemo(
    "$DIR/video-slice", Format.VIDEO_GIF,
    listOf(TapeStyle::slice.st())
) {
    override fun styles() = buildList<TapeStyle> {
        this += TAPE_STYLE
        this += last().copy(slice = TapeSlice(Opt(true, Timecode.SMPTENonDropFrame(3, 10)), last().slice.outPoint))
        this += last().copy(slice = TapeSlice(last().slice.inPoint, Opt(true, Timecode.SMPTENonDropFrame(5, 20))))
    }

    override fun credits(style: TapeStyle) = TAPE_SPREADSHEET.parseCreditsTS(style)
}


object GuidePictureVideoVideoTemporallyJustifyDemo : StyleSettingsTimelineDemo(
    "$DIR/video-temporally-justify", Format.VIDEO_GIF,
    listOf(TapeStyle::temporallyJustify.st())
) {
    override fun styles() = buildList<TapeStyle> {
        this += TAPE_STYLE.copy(
            slice = TapeSlice(Opt(true, Timecode.SMPTENonDropFrame(0, 0)), Opt(true, Timecode.SMPTENonDropFrame(3, 0))),
            temporallyJustify = HJustify.LEFT
        )
        this += last().copy(temporallyJustify = HJustify.CENTER)
        this += last().copy(temporallyJustify = HJustify.RIGHT)
    }

    override fun credits(style: TapeStyle) = TAPE_SPREADSHEET.parseCreditsTS(style)
}


object GuidePictureVideoVideoTemporalMarginDemo : StyleSettingsTimelineDemo(
    "$DIR/video-temporal-margin", Format.VIDEO_GIF,
    listOf(TapeStyle::leftTemporalMarginFrames.st(), TapeStyle::rightTemporalMarginFrames.st())
) {
    override fun styles() = buildList<TapeStyle> {
        this += TAPE_STYLE
        this += last().copy(leftTemporalMarginFrames = 40)
        this += last().copy(rightTemporalMarginFrames = 25)
    }

    override fun credits(style: TapeStyle) = TAPE_SPREADSHEET.parseCreditsTS(style)
}


object GuidePictureVideoVideoFadeDemo : StyleSettingsTimelineDemo(
    "$DIR/video-fade", Format.VIDEO_GIF,
    listOf(TapeStyle::fadeInFrames.st(), TapeStyle::fadeOutFrames.st())
) {
    override fun styles() = buildList<TapeStyle> {
        this += TAPE_STYLE.copy(leftTemporalMarginFrames = 42, rightTemporalMarginFrames = 42)
        this += last().copy(fadeInFrames = 30)
        this += last().copy(fadeOutFrames = 10)
    }

    override fun credits(style: TapeStyle) = TAPE_SPREADSHEET.parseCreditsTS(style)
}


private val PIC_STYLE = PRESET_PICTURE_STYLE.copy("logo.svg", picture = PictureRef(LOGO_PIC))
private const val PIC_SPREADSHEET = """
@Body
{{Pic logo.svg}}
        """

private val TAPE_STYLE = PRESET_TAPE_STYLE.copy("rainbow", tape = TapeRef(RAINBOW_TAPE))
private const val TAPE_SPREADSHEET = """
@Body
{{Video rainbow}}
        """
