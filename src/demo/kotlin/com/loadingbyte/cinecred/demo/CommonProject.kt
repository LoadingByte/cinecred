package com.loadingbyte.cinecred.demo

import com.loadingbyte.cinecred.common.FALLBACK_TRANSLATED_LOCALE
import com.loadingbyte.cinecred.common.createDirectoriesSafely
import com.loadingbyte.cinecred.common.useResourcePath
import com.loadingbyte.cinecred.common.walkSafely
import com.loadingbyte.cinecred.drawer.getBundledFont
import com.loadingbyte.cinecred.project.*
import com.loadingbyte.cinecred.projectio.*
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toPersistentList
import java.nio.file.Path
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
        for (spines in when (stage) {
            is Stage.Card -> stage.compounds.asSequence().map(Compound::spines)
            is Stage.Scroll -> stage.laterals.asSequence().map(Lateral::spines)
        })
            for (spine in spines)
                for (block in spine.blocks) {
                    contentStyles.add(block.style)
                    for (elem in block.body)
                        when (elem) {
                            is BodyElement.Nil -> letterStyles.add(elem.sty)
                            is BodyElement.Str -> elem.str.forEach { (_, style) -> letterStyles.add(style) }
                            is BodyElement.Pic -> {}
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


val TEMPLATE_SPREADSHEET: Spreadsheet by lazy {
    withDemoProjectDir { projectDir ->
        tryCopyTemplate(projectDir, FALLBACK_TRANSLATED_LOCALE, 1, CsvFormat)
        CsvFormat.read(ProjectIntake.locateCreditsFile(projectDir).first!!).first
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
    }.pages.single()
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
    }.pages.single()
}

private fun loadTemplateProject(modifyCsv: (Path) -> Unit = {}): Project =
    withDemoProjectDir { projectDir ->
        tryCopyTemplate(projectDir, FALLBACK_TRANSLATED_LOCALE, 1, CsvFormat)
        val creditsFile = ProjectIntake.locateCreditsFile(projectDir).first!!
        modifyCsv(creditsFile)
        val spreadsheet = CsvFormat.read(creditsFile).first
        val styling = readStyling(projectDir.resolve(STYLING_FILE_NAME), BundledFontsStylingContext)
        val pictureLoaders = buildList {
            for (file in projectDir.walkSafely())
                if (file.isRegularFile())
                    tryReadPictureLoader(file)?.let(::add)
        }
        val (pages, runtimeGroups, _) = readCredits(spreadsheet, styling, pictureLoaders)
        for (pl in pictureLoaders)
            pl.dispose()
        Project(styling, BundledFontsStylingContext, pages.toPersistentList(), runtimeGroups.toPersistentList())
    }


fun String.parseCreditsCS(vararg contentStyles: ContentStyle): Pair<Global, Page> {
    var styling = TEMPLATE_PROJECT.styling
    styling = styling.copy(contentStyles = styling.contentStyles.toPersistentList().addAll(contentStyles.asList()))

    val spreadsheet = CsvFormat.read(this)
    val (pages, _, _) = useResourcePath("/logo.svg") { logoSvg ->
        useResourcePath("/template/cinecred.svg") { cinecredSvg ->
            val pictureLoaders = listOf(tryReadPictureLoader(logoSvg)!!, tryReadPictureLoader(cinecredSvg)!!)
            readCredits(spreadsheet, styling, pictureLoaders)
        }
    }
    return Pair(styling.global, pages.single())
}

fun String.parseCreditsLS(vararg letterStyles: LetterStyle): Pair<Global, Page> {
    val styling =
        Styling(PRESET_GLOBAL, persistentListOf(), persistentListOf(), letterStyles.asList().toPersistentList())
    val spreadsheet = CsvFormat.read(this)
    val (pages, _, _) = readCredits(spreadsheet, styling, emptyList())
    return Pair(styling.global, pages.single())
}
