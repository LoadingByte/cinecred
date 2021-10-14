package com.loadingbyte.cinecred.projectio

import ch.rabanti.nanoxlsx4j.styles.CellXf
import com.github.miachm.sods.Borders
import com.github.miachm.sods.Sheet
import com.github.miachm.sods.SpreadSheet
import com.loadingbyte.cinecred.common.Severity.ERROR
import com.loadingbyte.cinecred.common.Severity.WARN
import com.loadingbyte.cinecred.common.l10n
import jxl.CellView
import jxl.format.BorderLineStyle
import jxl.write.Label
import jxl.write.WritableCellFormat
import jxl.write.WritableFont
import org.apache.commons.csv.CSVFormat
import org.apache.commons.csv.CSVRecord
import java.io.StringReader
import java.nio.charset.Charset
import java.nio.file.Path
import kotlin.io.path.nameWithoutExtension
import kotlin.io.path.readText


typealias Spreadsheet = List<SpreadsheetRecord>

class SpreadsheetRecord(val recordNo: Int, val cells: List<String>)


// The formats are ordered according to decreasing preference.
val SPREADSHEET_FORMATS = listOf(XlsxFormat, XlsFormat, OdsFormat, CsvFormat)


interface SpreadsheetFormat {

    val fileExt: String

    fun read(file: Path): Pair<Spreadsheet, List<ParserMsg>>

    /**
     * @param colWidths Width of some of the columns, in characters.
     */
    fun write(file: Path, spreadsheet: Spreadsheet, rowLooks: Map<Int, RowLook>, colWidths: List<Int>)

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

    override val fileExt = "xlsx"

    override fun read(file: Path) = readOfficeDocument(
        file,
        open = { ch.rabanti.nanoxlsx4j.Workbook.load(file.toString()) },
        getNumSheets = { workbook -> workbook.worksheets.size },
        read = { workbook ->
            val sheet = workbook.worksheets[0]
            val numRows = sheet.lastRowNumber + 1
            val numCols = sheet.lastColumnNumber + 1

            val cellMatrix = Array(numRows) { Array(numCols) { "" } }
            for (cell in sheet.cells.values)
                cellMatrix[cell.rowNumber][cell.columnNumber] = cell.value.toString()
            cellMatrix.mapIndexed { idx, cells -> SpreadsheetRecord(idx, cells.asList()) }
        }
    )

    override fun write(
        file: Path, spreadsheet: Spreadsheet, rowLooks: Map<Int, SpreadsheetFormat.RowLook>, colWidths: List<Int>
    ) {
        val workbook = ch.rabanti.nanoxlsx4j.Workbook(false)
        workbook.addWorksheet(file.nameWithoutExtension)
        val sheet = workbook.worksheets[0]

        for (record in spreadsheet) {
            val row = record.recordNo
            val style = ch.rabanti.nanoxlsx4j.styles.Style()
            style.font.size = 10
            rowLooks[row]?.let { look ->
                if (look.fontSize != -1)
                    style.font.size = look.fontSize
                style.font.isBold = look.bold
                style.font.isItalic = look.italic
                if (look.wrap)
                    style.cellXf.alignment = CellXf.TextBreakValue.wrapText
                if (look.borderBottom) {
                    style.border.bottomStyle = ch.rabanti.nanoxlsx4j.styles.Border.StyleValue.thin
                    style.border.bottomColor = "black"
                }
            }
            record.cells.forEachIndexed { col, cell ->
                sheet.addCell(cell.autoCast(), col, row, style)
            }
        }

        for ((row, look) in rowLooks)
            if (look.height != -1)
                sheet.setRowHeight(row, look.height * 2.85f)
        for ((col, width) in colWidths.withIndex())
            sheet.setColumnWidth(col, width * 0.4f)

        workbook.saveAs(file.toString())
    }

}


object XlsFormat : SpreadsheetFormat {

    override val fileExt = "xls"

    override fun read(file: Path) = readOfficeDocument(
        file,
        open = { jxl.Workbook.getWorkbook(file.toFile()) },
        getNumSheets = { workbook -> workbook.numberOfSheets },
        read = { workbook ->
            val records = mutableListOf<SpreadsheetRecord>()
            val sheet = workbook.getSheet(0)
            for (row in 0 until sheet.rows) {
                val cells = ArrayList<String>(sheet.columns)
                for (col in 0 until sheet.columns)
                    cells.add(sheet.getCell(col, row).contents)
                records.add(SpreadsheetRecord(row, cells))
            }
            workbook.close()
            records
        }
    )

    override fun write(
        file: Path, spreadsheet: Spreadsheet, rowLooks: Map<Int, SpreadsheetFormat.RowLook>, colWidths: List<Int>
    ) {
        val workbook = jxl.Workbook.createWorkbook(file.toFile())
        val sheet = workbook.createSheet(file.nameWithoutExtension, 0)

        for (record in spreadsheet) {
            val row = record.recordNo
            val fmt = WritableCellFormat()
            rowLooks[row]?.let { look ->
                val pointSize = if (look.fontSize != -1) look.fontSize else WritableFont.DEFAULT_POINT_SIZE
                val boldStyle = if (look.bold) WritableFont.BOLD else WritableFont.NO_BOLD
                fmt.setFont(WritableFont(WritableFont.ARIAL, pointSize, boldStyle, look.italic))
                fmt.wrap = look.wrap
                if (look.borderBottom)
                    fmt.setBorder(jxl.format.Border.BOTTOM, BorderLineStyle.THIN)
            }
            record.cells.forEachIndexed { col, cell ->
                val castedCell = cell.autoCast()
                sheet.addCell(
                    if (castedCell is Double) jxl.write.Number(col, row, castedCell, fmt)
                    else Label(col, row, cell, fmt)
                )
            }
        }

        for ((row, look) in rowLooks)
            if (look.height != -1)
                sheet.setRowView(row, CellView().apply { size = look.height * 56 })
        for ((col, width) in colWidths.withIndex())
            sheet.setColumnView(col, CellView().apply { size = width * 130 })

        workbook.write()
        workbook.close()
    }

}


object OdsFormat : SpreadsheetFormat {

    override val fileExt = "ods"

    override fun read(file: Path) = readOfficeDocument(
        file,
        open = { SpreadSheet(file.toFile()) },
        getNumSheets = { workbook -> workbook.numSheets },
        read = { workbook ->
            val sheet = workbook.getSheet(0)
            sheet.dataRange.values.mapIndexed { idx, cells ->
                SpreadsheetRecord(idx, cells.map { it?.toString() ?: "" })
            }
        }
    )

    override fun write(
        file: Path, spreadsheet: Spreadsheet, rowLooks: Map<Int, SpreadsheetFormat.RowLook>, colWidths: List<Int>
    ) {
        val numRows = spreadsheet.size
        val numCols = spreadsheet[0].cells.size

        val sheet = Sheet(file.nameWithoutExtension, numRows, numCols)
        val cellMatrix = Array(numRows) { row -> Array(numCols) { col -> spreadsheet[row].cells[col].autoCast() } }
        sheet.dataRange.values = cellMatrix

        for (record in spreadsheet) {
            val row = record.recordNo
            val style = com.github.miachm.sods.Style()
            rowLooks[row]?.let { look ->
                if (look.fontSize != -1)
                    style.fontSize = look.fontSize
                style.isBold = look.bold
                style.isItalic = look.italic
                style.isWrap = look.wrap
                if (look.borderBottom)
                    style.borders = Borders(false, null, true, "0.75pt solid #000000", false, null, false, null)
            }
            for (col in record.cells.indices)
                sheet.getRange(row, col).style = style
        }

        for ((row, look) in rowLooks)
            if (look.height != -1)
                sheet.setRowHeight(row, look.height.toDouble())
        for ((col, width) in colWidths.withIndex())
            sheet.setColumnWidth(col, width.toDouble())

        val workbook = SpreadSheet()
        workbook.appendSheet(sheet)
        workbook.save(file.toFile())
    }

}


object CsvFormat : SpreadsheetFormat {

    override val fileExt = "csv"

    override fun read(file: Path): Pair<Spreadsheet, List<ParserMsg>> {
        return Pair(read(file.readText()), emptyList())
    }

    fun read(text: String): Spreadsheet {
        // We trim the unicode character "ZERO WIDTH NO-BREAK SPACE" which is added by Excel for some reason.
        val trimmed = text.trimStart(0xFEFF.toChar())

        // Parse the CSV file into a list of CSV records.
        val csvRecords = CSVFormat.DEFAULT.parse(StringReader(trimmed)).records

        // Convert the CSV records to our record type.
        fun CSVRecord.efficientToList() = ArrayList<String>(size()).also { it.addAll(this) }
        return csvRecords.map { SpreadsheetRecord(it.recordNumber.toInt() - 1, it.efficientToList()) }
    }

    override fun write(
        file: Path, spreadsheet: Spreadsheet, rowLooks: Map<Int, SpreadsheetFormat.RowLook>, colWidths: List<Int>
    ) {
        CSVFormat.DEFAULT.print(file, Charset.forName("UTF-8")).use { printer ->
            for (record in spreadsheet)
                printer.printRecord(record.cells)
        }
    }

}


private fun String.autoCast(): Any =
    try {
        toDouble()
    } catch (_: NumberFormatException) {
        this
    }


// Helper function to avoid duplicate code.
private inline fun <W> readOfficeDocument(
    file: Path,
    open: () -> W,
    getNumSheets: (W) -> Int,
    read: (W) -> Spreadsheet
): Pair<Spreadsheet, List<ParserMsg>> {
    val log = mutableListOf<ParserMsg>()

    val workbook = open()
    val numSheets = getNumSheets(workbook)

    if (numSheets == 0)
        log.add(ParserMsg(null, null, null, ERROR, l10n("projectIO.spreadsheet.noSheet", file)))
    else if (numSheets > 1)
        log.add(ParserMsg(null, null, null, WARN, l10n("projectIO.spreadsheet.multipleSheets", file)))

    return Pair(if (numSheets == 0) emptyList() else read(workbook), log)
}
