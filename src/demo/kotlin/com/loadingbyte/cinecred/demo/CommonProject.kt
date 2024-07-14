package com.loadingbyte.cinecred.demo

import com.loadingbyte.cinecred.common.*
import com.loadingbyte.cinecred.drawer.getBundledFont
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
import kotlin.io.path.Path
import kotlin.io.path.isRegularFile
import kotlin.io.path.readLines
import kotlin.io.path.writeLines


fun extractStyling(global: Global, page: Page): Styling {
    val pageStyles = HashSet<PageStyle>()
    val contentStyles = HashSet<ContentStyle>()
    val letterStyles = HashSet<LetterStyle>()
    for (stage in page.stages) {
        pageStyles.add(stage.style)
        for (compound in stage.compounds)
            for (spine in compound.spines)
                for (block in spine.blocks) {
                    contentStyles.add(block.style)
                    for (elem in block.body)
                        when (elem) {
                            is BodyElement.Nil -> letterStyles.add(elem.sty)
                            is BodyElement.Str -> elem.str.forEach { (_, style) -> letterStyles.add(style) }
                            is BodyElement.Pic -> {}
                            is BodyElement.Tap -> {}
                        }
                    block.head?.forEach { (_, style) -> letterStyles.add(style) }
                    block.tail?.forEach { (_, style) -> letterStyles.add(style) }
                }
    }
    return Styling(
        global, pageStyles.toPersistentList(), contentStyles.toPersistentList(), letterStyles.toPersistentList()
    )
}


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


object BundledFontsStylingContext : StylingContext {
    override fun resolveFont(name: String) = getBundledFont(name)
}


fun template(locale: Locale) =
    Template(locale, PRESET_GLOBAL.resolution, PRESET_GLOBAL.fps, PRESET_GLOBAL.timecodeFormat, sample = true)

val TEMPLATE_SPREADSHEET: Spreadsheet by lazy {
    withDemoProjectDir { projectDir ->
        tryCopyTemplate(projectDir, template(FALLBACK_TRANSLATED_LOCALE), CsvFormat)
        CsvFormat.read(ProjectIntake.locateCreditsFile(projectDir).first!!).first.single()
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

val TEMPLATE_SCROLL_PAGE_FOR_MELT_DEMO: Page by lazy {
    loadTemplateProject { creditsFile ->
        val lines = creditsFile.readLines().toMutableList()
        val headerLineNo = lines.indexOfFirst { "@Body" in it }
        val gafferLineNo = lines.indexOfFirst { "Gaffer" in it }
        val lawyerLineNo = lines.indexOfFirst { "lawyer" in it }
        lines[headerLineNo + 1] = ",,,,Gutter,,,Scroll,\n"
        lines[lawyerLineNo - 1] = ",,,3,,,,,\n"
        lines[lawyerLineNo + 2] = ",,,8,,,,,\n"
        (lawyerLineNo - 2 downTo gafferLineNo + 3).forEach(lines::removeAt)
        (gafferLineNo - 1 downTo headerLineNo + 2).forEach(lines::removeAt)
        creditsFile.writeLines(lines)
    }.credits.single().pages.single()
}

private fun loadTemplateProject(modifyCsv: (Path) -> Unit = {}): Project =
    withDemoProjectDir { projectDir ->
        tryCopyTemplate(projectDir, template(FALLBACK_TRANSLATED_LOCALE), CsvFormat)
        val creditsFile = ProjectIntake.locateCreditsFile(projectDir).first!!
        modifyCsv(creditsFile)
        val spreadsheet = CsvFormat.read(creditsFile).first.single()
        val styling = readStyling(projectDir.resolve(STYLING_FILE_NAME), BundledFontsStylingContext)
        val pictureLoaders = buildList {
            for (file in projectDir.walkSafely())
                if (file.isRegularFile())
                    tryReadPictureLoader(file)?.let(::add)
        }
        val credits = readCredits(spreadsheet, styling, pictureLoaders, emptyList()).first
        for (pl in pictureLoaders)
            pl.dispose()
        Project(styling, BundledFontsStylingContext, persistentListOf(credits))
    }


private val LOGO_PIC by lazy { useResourcePath("/logo.svg") { tryReadPictureLoader(it)!!.apply { picture } } }
private val C_PIC by lazy { useResourcePath("/template/cinecred.svg") { tryReadPictureLoader(it)!!.apply { picture } } }

private val RAINBOW_TAPE: Tape by lazy {
    val tmpDir = Path(System.getProperty("java.io.tmpdir")).resolve("cinecred-rainbow")
    tmpDir.toFile().apply { deleteRecursively(); deleteOnExit() }
    val tapeDir = tmpDir.resolve("rainbow").also(Path::createDirectoriesSafely).apply { toFile().deleteOnExit() }
    val img = BufferedImage(16, 9, BufferedImage.TYPE_3BYTE_BGR)
    for (hue in 0..255)
        ImageIO.write(img.withG2 { g2 ->
            g2.color = Color(Color.HSBtoRGB(hue / 255f, 1f, 1f))
            g2.fillRect(0, 0, 16, 9)
        }, "png", tapeDir.resolve("rainbow.$hue.png").toFile().apply { deleteOnExit() })
    Tape.recognize(tapeDir)!!
}

fun String.parseCreditsCS(vararg contentStyles: ContentStyle, resolution: Resolution? = null): Pair<Global, Page> {
    var styling = TEMPLATE_PROJECT.styling
    if (resolution != null)
        styling = styling.copy(global = styling.global.copy(resolution = resolution))
    styling = styling.copy(contentStyles = styling.contentStyles.toPersistentList().addAll(contentStyles.asList()))

    val spreadsheet = CsvFormat.read("", this)
    val tapes = if ("{{Video rainbow" in this) listOf(RAINBOW_TAPE) else emptyList()
    val pages = readCredits(spreadsheet, styling, listOf(LOGO_PIC, C_PIC), tapes).first.pages
    return Pair(styling.global, pages.single())
}

fun String.parseCreditsLS(vararg letterStyles: LetterStyle): Pair<Global, Page> {
    val styling =
        Styling(PRESET_GLOBAL, persistentListOf(), persistentListOf(), letterStyles.asList().toPersistentList())
    val spreadsheet = CsvFormat.read("", this)
    val pages = readCredits(spreadsheet, styling, emptyList(), emptyList()).first.pages
    return Pair(styling.global, pages.single())
}
