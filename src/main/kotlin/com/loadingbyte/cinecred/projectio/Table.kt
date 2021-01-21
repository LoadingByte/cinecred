package com.loadingbyte.cinecred.projectio

import com.loadingbyte.cinecred.common.*
import org.apache.commons.csv.CSVRecord
import java.util.*


class Table(
    private val log: MutableList<ParserMsg>,
    rawLines: List<CSVRecord>,
    val l10nPrefix: String,
    l10nColNames: List<String>
) {

    val numRows: Int
    private val header: List<String>
    private val bodyLines: List<CSVRecord>
    private val colMap: Map<String, Int>

    init {
        header = rawLines[0].map { it.trim() }
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
        outer@
        for (l10nColName in l10nColNames) {
            val key = "$l10nPrefix$l10nColName"
            val possibleColNames = l10nAll(key).map { "@$it" }
            for (colName in possibleColNames) {
                val col = header.indexOfFirst { it.equals(colName, ignoreCase = true) }
                if (col != -1) {
                    colMap[l10nColName] = col
                    continue@outer
                }
            }
            // The column is missing. Emit a warning.
            val primaryColName = "@${l10n(key)}"
            val alternativeColNames = possibleColNames.toMutableSet()
                .apply { remove(primaryColName) }
                .joinToString("/") { "\"$it\"" }
            warn(headerLineNo, l10n("projectIO.table.missingExpectedColumn", primaryColName, alternativeColNames))
        }

        // 2. Emit a warning for each unexpected column name.
        for ((col, colName) in header.withIndex())
            if (colName.isNotEmpty() && col !in colMap.values)
                warn(headerLineNo, l10n("projectIO.table.unexpectedColumn", colName))
    }

    // Helper function to shorten logging calls.
    private fun warn(lineNo: Int, msg: String) {
        log.add(ParserMsg(lineNo, Severity.WARN, msg))
    }

    fun isEmpty(row: Int): Boolean = bodyLines[row].all { it.trim().isEmpty() }

    fun getLineNo(row: Int): Int = bodyLines[row].recordNumber.toInt()

    fun getString(row: Int, l10nColName: String): String? {
        val col = colMap[l10nColName]
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

    fun <T> get(row: Int, l10nColName: String, convert: (String) -> T): T? {
        val str = getString(row, l10nColName) ?: return null
        return try {
            str.let(convert)
        } catch (e: IllFormattedCell) {
            // If the cell value is available but has the wrong format, log a warning and return null.
            val colName = header[colMap[l10nColName]!!]
            warn(getLineNo(row), l10n("projectIO.table.illFormattedCell", str, colName, e.message))
            null
        }
    }

    fun getFiniteFloat(row: Int, l10nColName: String, nonNeg: Boolean = false, non0: Boolean = false): Float? =
        get(row, l10nColName) { str ->
            try {
                str.toFiniteFloat(nonNeg, non0)
            } catch (_: NumberFormatException) {
                val restriction = when {
                    nonNeg && non0 -> "> 0"
                    nonNeg && !non0 -> "\u2265 0"
                    !nonNeg && non0 -> "\u2260 0"
                    else -> ""
                }
                throw IllFormattedCell(l10n("projectIO.table.illFormattedFloat", restriction).trim())
            }
        }

    inline fun <reified T : Enum<T>> getEnum(row: Int, l10nColName: String): T? =
        get(row, l10nColName) { str ->
            val keyBase = "$l10nPrefix${T::class.java.simpleName}."
            for (enumElem in enumValues<T>())
                if (l10nAll("$keyBase${enumElem.name}").any { it.equals(str, ignoreCase = true) })
                    return@get enumElem

            val keys = enumValues<T>().map { "$l10nPrefix${it.javaClass.simpleName}.${it.name}" }
            val primaryOptions = keys.map(::l10n)
            val alternativeOptions = keys.flatMap { l10nAll(it).toMutableSet().apply { removeAll(primaryOptions) } }
            val msg = l10n(
                "projectIO.table.illFormattedOneOfWithAlternatives",
                primaryOptions.joinToString("/") { "\"$it\"" },
                alternativeOptions.joinToString("/") { "\"$it\"" }
            )
            throw IllFormattedCell(msg)
        }

    fun <T> getLookup(row: Int, l10nColName: String, map: Map<String, T>): T? =
        get(row, l10nColName) { str ->
            map[str] ?: throw IllFormattedCell(
                l10n("projectIO.table.illFormattedOneOf", map.keys.joinToString("/") { "\"$it\"" })
            )
        }


    class IllFormattedCell(message: String) : Exception(message)

}
