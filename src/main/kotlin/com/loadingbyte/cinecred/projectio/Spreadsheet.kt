package com.loadingbyte.cinecred.projectio

import com.github.miachm.sods.Sheet
import com.github.miachm.sods.SpreadSheet
import com.loadingbyte.cinecred.common.Severity.WARN
import com.loadingbyte.cinecred.common.l10n
import jxl.write.Label
import org.apache.commons.csv.CSVFormat
import org.apache.commons.csv.CSVRecord
import java.io.StringReader
import java.nio.charset.Charset
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.nameWithoutExtension


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
    fun write(file: Path, spreadsheet: Spreadsheet, colWidths: Map<Int, Int>)

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

            val stringMatrix = Array(numRows) { Array(numCols) { "" } }
            for (cell in sheet.cells.values)
                stringMatrix[cell.rowNumber][cell.columnNumber] = cell.value.toString()
            stringMatrix.mapIndexed { idx, cells -> SpreadsheetRecord(idx, cells.asList()) }
        }
    )

    override fun write(file: Path, spreadsheet: Spreadsheet, colWidths: Map<Int, Int>) {
        val workbook = ch.rabanti.nanoxlsx4j.Workbook(false)
        workbook.addWorksheet(file.nameWithoutExtension)
        val sheet = workbook.worksheets[0]

        for (record in spreadsheet)
            record.cells.forEachIndexed { col, cell ->
                sheet.addCell(cell, col, record.recordNo)
            }

        for ((col, width) in colWidths)
            sheet.setColumnWidth(col, width * 0.8f)

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

    override fun write(file: Path, spreadsheet: Spreadsheet, colWidths: Map<Int, Int>) {
        val workbook = jxl.Workbook.createWorkbook(file.toFile())
        val sheet = workbook.createSheet(file.nameWithoutExtension, 0)

        for (record in spreadsheet)
            record.cells.forEachIndexed { col, cell ->
                sheet.addCell(Label(col, record.recordNo, cell))
            }

        for ((col, width) in colWidths)
            sheet.setColumnView(col, width)

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

    override fun write(file: Path, spreadsheet: Spreadsheet, colWidths: Map<Int, Int>) {
        val numRows = spreadsheet.size
        val numCols = spreadsheet[0].cells.size

        val sheet = Sheet(file.nameWithoutExtension, numRows, numCols)
        val stringMatrix = Array(numRows) { row -> Array(numCols) { col -> spreadsheet[row].cells[col] } }
        sheet.dataRange.values = stringMatrix

        for ((col, width) in colWidths)
            sheet.setColumnWidth(col, width * 2.0)

        val workbook = SpreadSheet()
        workbook.appendSheet(sheet)
        workbook.save(file.toFile())
    }

}


object CsvFormat : SpreadsheetFormat {

    override val fileExt = "csv"

    override fun read(file: Path): Pair<Spreadsheet, List<ParserMsg>> {
        return Pair(read(Files.readString(file)), emptyList())
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

    override fun write(file: Path, spreadsheet: Spreadsheet, colWidths: Map<Int, Int>) {
        CSVFormat.DEFAULT.print(file, Charset.forName("UTF-8")).use { printer ->
            for (record in spreadsheet)
                printer.printRecord(record.cells)
        }
    }

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

    if (numSheets != 1)
        log.add(ParserMsg(null, null, null, WARN, l10n("projectIO.spreadsheet.multipleSheets", file)))

    return Pair(if (numSheets == 0) emptyList() else read(workbook), log)
}
