package com.loadingbyte.cinecred.projectio

import com.loadingbyte.cinecred.Severity
import com.loadingbyte.cinecred.Severity.ERROR
import com.loadingbyte.cinecred.Severity.WARN
import com.loadingbyte.cinecred.l10n
import org.apache.commons.csv.CSVRecord
import java.util.*


class Table(
    private val log: MutableList<ParserMsg>,
    rawLines: List<CSVRecord>,
    requiredColNames: List<String> = emptyList(),
    defaultedColNames: Map<String, String> = emptyMap(),
    nullableColNames: List<String> = emptyList(),
    removeInterspersedEmptyLines: Boolean = true
) {

    private val requiredColNames = TreeSet(String.CASE_INSENSITIVE_ORDER)
        .apply { addAll(requiredColNames) }
    private val defaultedColNames = TreeMap<String, String>(String.CASE_INSENSITIVE_ORDER)
        .apply { putAll(defaultedColNames) }

    val numRows: Int

    private val bodyLines: List<CSVRecord>
    private val colMap: Map<String, Int>

    init {
        val header = rawLines[0].map { it.trim() }
        val headerLineNo = rawLines[0].recordNumber.toInt()

        // Determine the lines which make up the data rows of the table.
        fun isLineNotEmpty(line: CSVRecord) = line.any { cell -> cell.isNotEmpty() }
        val rawBodyLines = rawLines.drop(1)
        bodyLines = if (removeInterspersedEmptyLines)
            rawBodyLines.filter(::isLineNotEmpty)
        else
            rawBodyLines.subList(
                rawBodyLines.indexOfFirst(::isLineNotEmpty),
                rawBodyLines.indexOfLast(::isLineNotEmpty) + 1
            )
        numRows = bodyLines.size

        // 1. Find the index of each expected column name. Emit errors resp. warnings for missing columns.
        colMap = TreeMap(String.CASE_INSENSITIVE_ORDER)
        val expectedColNames = requiredColNames + defaultedColNames.keys + nullableColNames
        for (colName in expectedColNames) {
            val col = header.indexOfFirst { it.equals(colName, ignoreCase = true) }
            when {
                col != -1 ->
                    colMap[colName] = col
                colName in this.requiredColNames ->
                    log(headerLineNo, ERROR, l10n("projectIO.table.missingRequiredColumn", colName))
                colName in this.defaultedColNames ->
                    log(
                        headerLineNo, WARN,
                        l10n("projectIO.table.missingDefaultedColumn", colName, this.defaultedColNames[colName])
                    )
                else -> log(headerLineNo, WARN, l10n("projectIO.table.missingNullableColumn", colName))
            }
        }

        // 2. Emit a warning for each unexpected column name.
        val expectedColNamesSet = TreeSet(String.CASE_INSENSITIVE_ORDER).apply { addAll(expectedColNames) }
        for (colName in header)
            if (colName.isNotEmpty() && colName !in expectedColNamesSet)
                log(headerLineNo, WARN, l10n("projectIO.table.unexpectedColumn", colName))
    }

    // Helper function to shorten logging calls.
    private fun log(lineNo: Int, severity: Severity, msg: String) {
        log.add(ParserMsg(lineNo, severity, msg))
    }

    fun isEmpty(row: Int): Boolean = bodyLines[row].all { it.trim().isEmpty() }

    fun getLineNo(row: Int): Int = bodyLines[row].recordNumber.toInt()

    fun getString(row: Int, colName: String): String? {
        val col = colMap[colName]
        val default = defaultedColNames[colName]
        if (col != null) {
            // If the column is present in the table, retrieve its value.
            val str = bodyLines[row][col].trim()
            when {
                // If the value is non-empty, return it.
                str.isNotEmpty() -> return str
                // Otherwise, potentially log a warning/error depending on the column type.
                // Afterwards, the last line of this function returns null or the default value.
                colName in requiredColNames ->
                    log(getLineNo(row), ERROR, l10n("projectIO.table.emptyRequiredCell", colName))
                colName in defaultedColNames ->
                    log(getLineNo(row), WARN, l10n("projectIO.table.emptyDefaultedCell", colName, default))
            }
        }
        // If the column is present but the cell is empty, or if the column is missing in the table,
        // return the default if it's a defaulted column and null otherwise.
        // In the second case, no warning/error is logged since that has already been done when the header was parsed.
        return default
    }

    fun <T> get(row: Int, colName: String, lazyTypeDesc: () -> String, convert: (String) -> T): T? {
        val default = defaultedColNames[colName]
        // If the column is not present in the table, do not log a warning/error
        // since that has already been done when the header was parsed.
        val col = colMap[colName] ?: return default?.let(convert)
        val str = bodyLines[row][col].trim()

        if (str.isEmpty()) {
            // If the cell is empty, log a warning/error depending on the column type.
            // Do of course not log an error if the column is nullable.
            val l = getLineNo(row)
            val typeDesc = lazyTypeDesc()
            when (colName) {
                in requiredColNames ->
                    log(l, ERROR, l10n("projectIO.table.emptyRequiredFormattedCell", colName, typeDesc))
                in defaultedColNames ->
                    log(l, WARN, l10n("projectIO.table.emptyDefaultedFormattedCell", colName, typeDesc, default))
            }
        } else
            try {
                return str.let(convert)
            } catch (_: IllegalArgumentException) {
                // If the cell value is available but has the wrong format,
                // log a warning/error depending on the column type.
                val line = getLineNo(row)
                val typeDesc = lazyTypeDesc()
                when (colName) {
                    in requiredColNames ->
                        log(line, ERROR, l10n("projectIO.table.illFormattedRequiredCell", str, colName, typeDesc))
                    in defaultedColNames ->
                        log(
                            line, WARN,
                            l10n("projectIO.table.illFormattedDefaultedCell", str, colName, typeDesc, default)
                        )
                    else ->
                        log(line, WARN, l10n("projectIO.table.illFormattedNullableCell", str, colName, typeDesc))
                }
            }

        // Return the default if it's a defaulted column and null otherwise.
        return default?.let(convert)
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
