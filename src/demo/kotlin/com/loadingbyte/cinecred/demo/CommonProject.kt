package com.loadingbyte.cinecred.demo

import com.loadingbyte.cinecred.common.*
import com.loadingbyte.cinecred.imaging.Picture
import com.loadingbyte.cinecred.imaging.Tape
import com.loadingbyte.cinecred.project.*
import com.loadingbyte.cinecred.projectio.*
import com.loadingbyte.cinecred.ui.helper.withG2
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toPersistentList
import java.awt.Color
import java.awt.image.BufferedImage
import java.nio.file.Path
import java.util.*
import javax.imageio.ImageIO
import kotlin.io.path.*


inline fun <R> withDemoProjectDir(block: (Path) -> R): R {
    val projectDir = Path(System.getProperty("java.io.tmpdir")).resolve(l10nDemo("projectDir"))
    projectDir.toFile().deleteRecursively()
    projectDir.createDirectoriesSafely()
    try {
        return block(projectDir)
    } finally {
        projectDir.toFile().deleteRecursively()
    }
}


fun template(locale: Locale) =
    Template(locale, PRESET_GLOBAL.resolution, PRESET_GLOBAL.fps, PRESET_GLOBAL.timecodeFormat, sample = true)

val TEMPLATE_SPREADSHEET: Spreadsheet by lazy {
    withDemoProjectDir { projectDir ->
        tryCopyTemplate(projectDir, template(FALLBACK_TRANSLATED_LOCALE), CsvFormat)
        CsvFormat.read(ProjectIntake.locateCreditsFile(projectDir).first!!, "").first.single()
    }
}

val TEMPLATE_PROJECT: Project by lazy { loadTemplateProject() }

val TEMPLATE_SCROLL_PAGE_FROM_DOP: Page by lazy {
    loadTemplateProject { creditsFile ->
        val lines = creditsFile.readLines().toMutableList()
        val headerLineNo = lines.indexOfFirst { "@Body" in it }
        val dopLineNo = lines.indexOfFirst { "Director of Photography" in it }
        lines[headerLineNo + 1] = ",,,,Gutter,,,Scroll,\n"
        (dopLineNo - 1 downTo headerLineNo + 2).forEach(lines::removeAt)
        creditsFile.writeLines(lines)
    }.credits.single().pages.single()
}

private fun loadTemplateProject(modifyCsv: (Path) -> Unit = {}): Project =
    withDemoProjectDir { projectDir ->
        tryCopyTemplate(projectDir, template(FALLBACK_TRANSLATED_LOCALE), CsvFormat)
        val creditsFile = ProjectIntake.locateCreditsFile(projectDir).first!!
        modifyCsv(creditsFile)
        val spreadsheet = CsvFormat.read(creditsFile, "").first.single()
        val pictureLoaders = buildMap {
            for (file in projectDir.walkSafely())
                if (file.isRegularFile())
                    Picture.Loader.recognize(file)?.let { put(file.name, it) }
        }
        val styling = readStyling(projectDir.resolve(STYLING_FILE_NAME), emptyMap(), pictureLoaders, emptyMap())
        val credits = readCredits(spreadsheet, styling, pictureLoaders, emptyMap()).first
        for (pictureLoader in pictureLoaders.values)
            pictureLoader.close()
        Project(styling, persistentListOf(credits))
    }


val LOGO_PIC by lazy { useResourcePath("/logo.svg") { Picture.Loader.recognize(it)!!.apply { picture } } }

val RAINBOW_TAPE: Tape by lazy {
    val tmpDir = Path(System.getProperty("java.io.tmpdir")).resolve("cinecred-rainbow")
    tmpDir.toFile().apply { deleteRecursively(); deleteOnExit() }
    val tapeDir = tmpDir.resolve("rainbow").also(Path::createDirectoriesSafely).apply { toFile().deleteOnExit() }
    val img = BufferedImage(192, 108, BufferedImage.TYPE_3BYTE_BGR)
    for (frame in 0..<200)
        ImageIO.write(img.withG2 { g2 ->
            g2.color = Color(Color.HSBtoRGB(frame / 2 / 100f, 1f, 1f))
            g2.fillRect(0, 0, img.width, img.height)
            g2.color = Color(0, 0, 0, 100)
            g2.font = BUNDLED_FONTS.first { it.getFontName(Locale.ROOT) == "Titillium Regular Upright" }.deriveFont(40f)
            val fm = g2.fontMetrics
            val str = "${frame + 1}"
            g2.drawString(str, 6, 7 + fm.ascent)
            g2.drawString(str, img.width - 5 - fm.stringWidth(str), img.height - fm.descent)
        }, "png", tapeDir.resolve("$frame.png").toFile().apply { deleteOnExit() })
    Tape.recognize(tapeDir)!!
}

fun String.parseCreditsCS(vararg contentStyles: ContentStyle, resolution: Resolution? = null): Pair<Global, Page> {
    var styling = TEMPLATE_PROJECT.styling
    if (resolution != null)
        styling = styling.copy(global = styling.global.copy(resolution = resolution))
    styling = styling.copy(contentStyles = styling.contentStyles.toPersistentList().addAll(contentStyles.asList()))
    return parseCredits(styling)
}

fun String.parseCreditsLS(vararg letterStyles: LetterStyle): Pair<Global, Page> {
    val styling = Styling(
        PRESET_GLOBAL, persistentListOf(), persistentListOf(), letterStyles.asList().toPersistentList(),
        persistentListOf(), persistentListOf(), persistentListOf()
    )
    return parseCredits(styling)
}

fun String.parseCreditsPiS(vararg pictureStyles: PictureStyle): Pair<Global, Page> {
    var styling = TEMPLATE_PROJECT.styling
    styling = styling.copy(pictureStyles = styling.pictureStyles.toPersistentList().addAll(pictureStyles.asList()))
    return parseCredits(styling)
}

fun String.parseCreditsTS(vararg tapeStyles: TapeStyle): Pair<Global, Page> {
    var styling = TEMPLATE_PROJECT.styling
    styling = styling.copy(global = styling.global.copy(resolution = Resolution(700, 350), fps = FPS(30, 1)))
    styling = styling.copy(tapeStyles = styling.tapeStyles.toPersistentList().addAll(tapeStyles.asList()))
    return parseCredits(styling)
}

private fun String.parseCredits(styling: Styling): Pair<Global, Page> {
    val spreadsheet = CsvFormat.read(this, "")
    val picLoaders = if ("logo.svg" in this) mapOf(LOGO_PIC.file.name to LOGO_PIC) else emptyMap()
    val tapes = if ("rainbow" in this) mapOf(RAINBOW_TAPE.fileOrDir.name to RAINBOW_TAPE) else emptyMap()
    val pages = readCredits(spreadsheet, styling, picLoaders, tapes).first.pages
    return Pair(styling.global, pages.single())
}
