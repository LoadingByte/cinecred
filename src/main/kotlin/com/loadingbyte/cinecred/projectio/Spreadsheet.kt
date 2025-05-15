package com.loadingbyte.cinecred.projectio

import ch.rabanti.nanoxlsx4j.Address
import ch.rabanti.nanoxlsx4j.styles.CellXf
import ch.rabanti.nanoxlsx4j.styles.NumberFormat
import ch.rabanti.nanoxlsx4j.styles.NumberFormat.FormatNumber
import com.formdev.flatlaf.util.SystemInfo
import com.github.miachm.sods.Borders
import com.loadingbyte.cinecred.common.LOGGER
import com.loadingbyte.cinecred.common.Severity.ERROR
import com.loadingbyte.cinecred.common.execProcess
import com.loadingbyte.cinecred.common.l10n
import de.siegmar.fastcsv.reader.CsvReader
import de.siegmar.fastcsv.reader.StringArrayHandler
import de.siegmar.fastcsv.writer.CsvWriter
import jxl.CellView
import jxl.WorkbookSettings
import jxl.format.BorderLineStyle
import jxl.write.Label
import jxl.write.NumberFormats
import jxl.write.WritableCellFormat
import jxl.write.WritableFont
import org.slf4j.LoggerFactory
import java.io.IOException
import java.nio.file.Path
import kotlin.io.path.*


class Spreadsheet(val name: String, matrix: List<List<String>>) : Iterable<Spreadsheet.Record> {

    private val records: List<Record> = matrix.mapIndexed(::Record)

    val numRecords: Int get() = records.size
    val numColumns: Int get() = records.maxOf { it.cells.size }

    operator fun get(recordNo: Int): Record = records[recordNo]
    operator fun get(recordNo: Int, columnNo: Int): String = records[recordNo].cells[columnNo]

    fun map(transform: (String) -> String): Spreadsheet = Spreadsheet(name, records.map { it.cells.map(transform) })

    override fun iterator(): Iterator<Record> = records.iterator()

    class Record(val recordNo: Int, val cells: List<String>) {
        fun isNotEmpty() = cells.any { it.isNotEmpty() }
    }

}


// The formats are ordered according to decreasing preference.
val SPREADSHEET_FORMATS = listOf(XlsxFormat, XlsFormat, OdsFormat, NumbersFormat, CsvFormat)


interface SpreadsheetFormat {

    val fileExt: String
    val label: String
    val available: Boolean get() = true

    /** @throws Exception */
    fun read(file: Path, defaultName: String): Pair<List<Spreadsheet>, List<ParserMsg>>

    /** @throws IOException */
    fun write(file: Path, spreadsheet: Spreadsheet, look: SpreadsheetLook)

}


class SpreadsheetLook(
    val rowLooks: Map<Int, RowLook>,
    /** Width of the columns, in characters. */
    val colWidths: List<Int>
) {

    class RowLook(
        val height: Int = -1,
        val fontSize: Int = -1,
        val bold: Boolean = false,
        val italic: Boolean = false,
        val wrap: Boolean = false,
        val borderBottom: Boolean = false
    )

}


object XlsxFormat : SpreadsheetFormat {

    override val fileExt get() = "xlsx"
    override val label get() = "Microsoft Excel 2007+"

    override fun read(file: Path, defaultName: String) = readOfficeDocument(
        file,
        open = { ch.rabanti.nanoxlsx4j.Workbook.load(file.toString()) },
        getNumSheets = { workbook -> workbook.worksheets.size },
        read = { workbook, sheetIdx ->
            val sheet = workbook.worksheets[sheetIdx]
            val numRows = sheet.lastRowNumber + 1
            val numCols = sheet.lastColumnNumber + 1
            val matrix = List(numRows) { MutableList(numCols) { "" } }
            for (cell in sheet.cells.values)
                cell.value?.let { matrix[cell.rowNumber][cell.columnNumber] = it.toString() }
            Spreadsheet(sheet.sheetName, matrix)
        },
        close = {}
    )

    override fun write(file: Path, spreadsheet: Spreadsheet, look: SpreadsheetLook) {
        val numCols = spreadsheet.numColumns

        val workbook = ch.rabanti.nanoxlsx4j.Workbook(false)
        workbook.addWorksheet(spreadsheet.name)
        val sheet = workbook.worksheets[0]

        // Add the sheet content.
        for (record in spreadsheet)
            for ((col, cell) in record.cells.withIndex())
                if (cell.isNotEmpty())
                    sheet.addCell(cell, col, record.recordNo)

        // Set the row heights & styles.
        for ((row, rowLook) in look.rowLooks) {
            if (rowLook.height != -1)
                sheet.setRowHeight(row, rowLook.height * 2.85f)
            sheet.setStyle(Address(0, row), Address(numCols - 1, row), createStyle(rowLook))
        }

        // Set the column widths & make them use the raw text data format.
        val defaultStyle = createStyle()
        for (col in 0..<numCols) {
            sheet.setColumnDefaultStyle(col, defaultStyle)
            look.colWidths.getOrNull(col)?.let { sheet.setColumnWidth(col, it * 0.5f) }
        }

        workbook.saveAs(file.toString())
    }

    private fun createStyle() =
        ch.rabanti.nanoxlsx4j.styles.Style().apply {
            numberFormat = NumberFormat().apply { number = FormatNumber.format_49 }
        }

    private fun createStyle(rowLook: SpreadsheetLook.RowLook) =
        createStyle().apply {
            if (rowLook.fontSize != -1)
                font.size = rowLook.fontSize.toFloat()
            font.isBold = rowLook.bold
            font.isItalic = rowLook.italic
            if (rowLook.wrap)
                cellXf.alignment = CellXf.TextBreakValue.wrapText
            if (rowLook.borderBottom)
                border.bottomStyle = ch.rabanti.nanoxlsx4j.styles.Border.StyleValue.thin
        }

}


object XlsFormat : SpreadsheetFormat {

    override val fileExt get() = "xls"
    override val label get() = "Microsoft Excel 97-2003"

    override fun read(file: Path, defaultName: String) = readOfficeDocument(
        file,
        open = { jxl.Workbook.getWorkbook(file.toFile(), WorkbookSettings().apply { encoding = "ISO-8859-1" }) },
        getNumSheets = { workbook -> workbook.numberOfSheets },
        read = { workbook, sheetIdx ->
            val sheet = workbook.getSheet(sheetIdx)
            val matrix = List(sheet.rows) { row -> List(sheet.columns) { col -> sheet.getCell(col, row).contents } }
            Spreadsheet(sheet.name, matrix)
        },
        close = { workbook -> workbook.close() }
    )

    override fun write(file: Path, spreadsheet: Spreadsheet, look: SpreadsheetLook) {
        val workbook = jxl.Workbook.createWorkbook(file.toFile())
        val sheet = workbook.createSheet(spreadsheet.name, 0)
        val defaultStyle = createStyle()

        // Add the sheet content, and set the row heights & styles.
        for (record in spreadsheet) {
            val row = record.recordNo
            val rowLook = look.rowLooks[row]
            val style = rowLook?.let(::createStyle) ?: defaultStyle
            for ((col, cell) in record.cells.withIndex())
                if (cell.isNotEmpty())
                    sheet.addCell(Label(col, row, cell, style))
            if (rowLook != null && rowLook.height != -1)
                sheet.setRowView(row, rowLook.height * 56)
        }

        // Set the column widths & make them use the raw text data format.
        for (col in 0..<spreadsheet.numColumns)
            sheet.setColumnView(col, CellView().apply {
                look.colWidths.getOrNull(col)?.let { size = it * 130 }
                format = defaultStyle
            })

        workbook.write()
        workbook.close()
    }

    private fun createStyle() =
        WritableCellFormat(NumberFormats.TEXT)

    private fun createStyle(rowLook: SpreadsheetLook.RowLook) =
        createStyle().apply {
            val font = WritableFont(
                WritableFont.ARIAL,
                if (rowLook.fontSize != -1) rowLook.fontSize else WritableFont.DEFAULT_POINT_SIZE,
                @Suppress("INACCESSIBLE_TYPE")
                if (rowLook.bold) WritableFont.BOLD else WritableFont.NO_BOLD,
                rowLook.italic
            )
            setFont(font)
            wrap = rowLook.wrap
            if (rowLook.borderBottom)
                setBorder(jxl.format.Border.BOTTOM, BorderLineStyle.THIN)
        }

}


object OdsFormat : SpreadsheetFormat {

    override val fileExt get() = "ods"
    override val label get() = "LibreOffice Calc"

    override fun read(file: Path, defaultName: String) = readOfficeDocument(
        file,
        open = { com.github.miachm.sods.SpreadSheet(file.toFile()) },
        getNumSheets = { workbook -> workbook.numSheets },
        read = { workbook, sheetIdx ->
            val sheet = workbook.getSheet(sheetIdx)
            val matrix = sheet.dataRange.values.map { cells -> cells.map { it?.toString() ?: "" } }
            Spreadsheet(sheet.name, matrix)
        },
        close = {}
    )

    override fun write(file: Path, spreadsheet: Spreadsheet, look: SpreadsheetLook) {
        val numRows = spreadsheet.numRecords
        val numCols = spreadsheet.numColumns

        val sheet = com.github.miachm.sods.Sheet(spreadsheet.name, numRows, numCols)

        // Add the sheet content.
        val cellMatrix = Array(numRows) { row -> Array(numCols) { col -> spreadsheet[row, col].ifEmpty { null } } }
        sheet.dataRange.values = cellMatrix

        // Set the row heights & styles.
        for ((row, rowLook) in look.rowLooks) {
            if (rowLook.height != -1)
                sheet.setRowHeight(row, rowLook.height.toDouble())
            sheet.getRange(row, 0, 1, numCols).style = createStyle(rowLook)
        }

        // Set the column widths & make them use the raw text data format.
        val defaultStyle = createStyle()
        for (col in 0..<numCols) {
            sheet.setDefaultColumnCellStyle(col, defaultStyle)
            look.colWidths.getOrNull(col)?.let { sheet.setColumnWidth(col, it.toDouble()) }
        }

        val workbook = com.github.miachm.sods.SpreadSheet()
        workbook.appendSheet(sheet)
        workbook.save(file.toFile())
    }

    private fun createStyle() =
        com.github.miachm.sods.Style().apply { dataStyle = "@" }

    private fun createStyle(rowLook: SpreadsheetLook.RowLook) =
        createStyle().apply {
            if (rowLook.fontSize != -1)
                fontSize = rowLook.fontSize
            isBold = rowLook.bold
            isItalic = rowLook.italic
            isWrap = rowLook.wrap
            if (rowLook.borderBottom)
                borders = Borders(false, null, true, "0.75pt solid #000000", false, null, false, null)
        }

}


object NumbersFormat : SpreadsheetFormat {

    override val fileExt get() = "numbers"
    override val label get() = "Apple Numbers"

    override val available = SystemInfo.isMacOS && try {
        runScript("AppleScript", "tell application \"Numbers\" to id")
        true
    } catch (_: IOException) {
        LOGGER.warn("Apple Numbers is not installed.")
        false
    }

    override fun read(file: Path, defaultName: String): Pair<List<Spreadsheet>, List<ParserMsg>> {
        if (!available)
            throw FormatUnavailableException(l10n("projectIO.spreadsheet.numbersUnavailable", ".numbers"))
        val script = """
            function run(argv) {
                const Numbers = Application("Numbers")
                const args = {to: Path(argv[1]), as: "Microsoft Excel", withProperties: {excludeSummaryWorksheet: true}}
                for (const doc of Numbers.documents()) {
                    if (doc.file() == argv[0]) {
                        Numbers.export(doc, args)
                        return
                    }
                }
                const doc = Numbers.open(Path(argv[0]))
                try {
                    Numbers.windows[0].visible = false
                    Numbers.export(doc, args)
                } finally {
                    Numbers.close(doc, {saving: "no"})
                }
            }
        """
        return withTempFile("cinecred-numbers2xlsx-") { tmpFile ->
            runScript("JavaScript", script, file.absolutePathString(), tmpFile.pathString)
            XlsxFormat.read(tmpFile, defaultName)
        }
    }

    override fun write(file: Path, spreadsheet: Spreadsheet, look: SpreadsheetLook) {
        if (!available)
            throw FormatUnavailableException(l10n("projectIO.spreadsheet.numbersUnavailable", ".numbers"))
        val script = """
            function run(argv) {
                const Numbers = Application("Numbers")
                const doc = Numbers.open(Path(argv[0]))
                try {
                    Numbers.windows[0].visible = false
                    Numbers.save(doc, {in: Path(argv[1])})
                } finally {
                    Numbers.close(doc, {saving: "no"})
                }
            }
        """
        withTempFile("cinecred-xlsx2numbers-") { tmpFile ->
            XlsxFormat.write(tmpFile, spreadsheet, look)
            runScript("JavaScript", script, tmpFile.pathString, file.absolutePathString())
        }
    }

    private fun <R> withTempFile(prefix: String, action: (Path) -> R): R {
        val tmpFile = createTempFile(prefix, ".xlsx")
        try {
            return action(tmpFile)
        } finally {
            try {
                tmpFile.deleteExisting()
            } catch (e: IOException) {
                // Ignore; file will be deleted upon OS restart anyway.
                LOGGER.error("Cannot delete temporary XLSX file '{}' created for Apple Numbers.", tmpFile, e)
            }
        }
    }

    private fun runScript(language: String, script: String, vararg args: String) {
        val logger = LoggerFactory.getLogger("AppleNumbers")
        execProcess(listOf("osascript", "-l", language, "-") + args, stdin = script, logger = logger)
    }

}


object CsvFormat : SpreadsheetFormat {

    override val fileExt get() = "csv"
    override val label get() = l10n("projectIO.spreadsheet.csv")

    override fun read(file: Path, defaultName: String): Pair<List<Spreadsheet>, List<ParserMsg>> {
        return Pair(listOf(read(file.readText(), defaultName)), emptyList())
    }

    fun read(text: String, name: String): Spreadsheet {
        // Trim the character which results from the byte order mark (BOM) added by Excel.
        val trimmed = text.trimStart(0xFEFF.toChar())

        // Parse the CSV file into a string matrix.
        val matrix = CsvReader.builder().skipEmptyLines(false).build(StringArrayHandler.of(), trimmed)
            .map(Array<String>::asList)

        // Create a spreadsheet.
        return Spreadsheet(name, matrix)
    }

    override fun write(file: Path, spreadsheet: Spreadsheet, look: SpreadsheetLook) {
        CsvWriter.builder().build(file).use { writer ->
            for (record in spreadsheet)
                writer.writeRecord(record.cells)
        }
    }

}


class FormatUnavailableException(message: String) : IOException(message)


// Helper function to avoid duplicate code.
private inline fun <W> readOfficeDocument(
    file: Path,
    open: () -> W,
    getNumSheets: (W) -> Int,
    read: (W, Int) -> Spreadsheet,
    close: (W) -> Unit
): Pair<List<Spreadsheet>, List<ParserMsg>> {
    val log = mutableListOf<ParserMsg>()

    val workbook = open()
    try {
        val numSheets = getNumSheets(workbook)

        if (numSheets == 0)
            log.add(ParserMsg(null, null, null, null, ERROR, l10n("projectIO.spreadsheet.noSheet", file)))

        return Pair(if (numSheets == 0) emptyList() else List(numSheets) { read(workbook, it) }, log)
    } finally {
        close(workbook)
    }
}
