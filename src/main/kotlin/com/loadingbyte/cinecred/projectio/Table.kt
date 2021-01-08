package com.loadingbyte.cinecred.projectio

import com.loadingbyte.cinecred.Severity
import com.loadingbyte.cinecred.l10n
import org.apache.commons.csv.CSVRecord
import java.util.*


class Table(
    private val log: MutableList<ParserMsg>,
    rawLines: List<CSVRecord>,
    expectedColNames: List<String> = emptyList()
) {

    val numRows: Int

    private val bodyLines: List<CSVRecord>
    private val colMap: Map<String, Int>

    init {
        val header = rawLines[0].map { it.trim() }
        val headerLineNo = rawLines[0].recordNumber.toInt()

        // Determine the lines which make up the data rows of the table.
        fun isLineNotEmpty(line: CSVRecord) = line.any { cell -> cell.isNotEmpty() }
        val rawBodyLines = rawLines.drop(1)
        bodyLines =
            rawBodyLines.subList(
                rawBodyLines.indexOfFirst(::isLineNotEmpty),
                rawBodyLines.indexOfLast(::isLineNotEmpty) + 1
            )
        numRows = bodyLines.size

        // 1. Find the index of each expected column name. Emit warnings for missing columns.
        colMap = TreeMap(String.CASE_INSENSITIVE_ORDER)
        for (colName in expectedColNames) {
            val col = header.indexOfFirst { it.equals(colName, ignoreCase = true) }
            if (col != -1)
                colMap[colName] = col
            else
                warn(headerLineNo, l10n("projectIO.table.missingExpectedColumn", colName))
        }

        // 2. Emit a warning for each unexpected column name.
        val expectedColNamesSet = TreeSet(String.CASE_INSENSITIVE_ORDER).apply { addAll(expectedColNames) }
        for (colName in header)
            if (colName.isNotEmpty() && colName !in expectedColNamesSet)
                warn(headerLineNo, l10n("projectIO.table.unexpectedColumn", colName))
    }

    // Helper function to shorten logging calls.
    private fun warn(lineNo: Int, msg: String) {
        log.add(ParserMsg(lineNo, Severity.WARN, msg))
    }

    fun isEmpty(row: Int): Boolean = bodyLines[row].all { it.trim().isEmpty() }

    fun getLineNo(row: Int): Int = bodyLines[row].recordNumber.toInt()

    fun getString(row: Int, colName: String): String? {
        val col = colMap[colName]
        if (col != null) {
            // If the column is present in the table, retrieve its value.
            val str = bodyLines[row][col].trim()
            // If the value is non-empty, return it.
            if (str.isNotEmpty())
                return str
        }
        // If the column is present but the cell is empty, or if the column is missing in the table, return null.
        return null
    }

    fun <T> get(row: Int, colName: String, lazyTypeDesc: () -> String, convert: (String) -> T): T? {
        val str = getString(row, colName) ?: return null
        return try {
            return str.let(convert)
        } catch (_: IllegalArgumentException) {
            // If the cell value is available but has the wrong format, log a warning and return null.
            warn(getLineNo(row), l10n("projectIO.table.illFormattedCell", str, colName, lazyTypeDesc()))
            null
        }
    }

    fun getFiniteFloat(row: Int, colName: String, nonNegative: Boolean = false, nonZero: Boolean = false): Float? =
        get(row, colName, { l10n("projectIO.table.floatTypeDesc", restrStr(nonNegative, nonZero)).trim() }) { str ->
            str.toFiniteFloat(nonNegative, nonZero)
        }

    inline fun <reified T : Enum<T>> getEnum(row: Int, colName: String): T? =
        get(row, colName, { l10n("projectIO.table.oneOfTypeDesc", optionStr<T>()) }) { str ->
            str.toEnum<T>()
        }

    fun <T> getLookup(row: Int, colName: String, map: Map<String, T>): T? =
        get(row, colName, { l10n("projectIO.table.oneOfTypeDesc", map.keys.joinToString("/")) }) { str ->
            map[str] ?: throw IllegalArgumentException()
        }

    companion object {

        private fun restrStr(nonNegative: Boolean, nonZero: Boolean) =
            when {
                nonNegative && nonZero -> "> 0"
                nonNegative && !nonZero -> ">= 0"
                !nonNegative && nonZero -> "!= 0"
                else -> ""
            }

        inline fun <reified T : Enum<T>> optionStr() =
            enumValues<T>().joinToString("/") { "\"" + it.name.toLowerCase().replace('_', ' ') + "\"" }

    }

}
