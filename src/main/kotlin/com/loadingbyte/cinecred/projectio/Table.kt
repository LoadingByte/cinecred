package com.loadingbyte.cinecred.projectio

import com.loadingbyte.cinecred.Severity
import com.loadingbyte.cinecred.Severity.ERROR
import com.loadingbyte.cinecred.Severity.WARN
import com.loadingbyte.cinecred.project.FontSpec
import org.apache.commons.csv.CSVRecord
import java.awt.Color


class Table(
    private val log: MutableList<ParserMsg>,
    rawLines: List<CSVRecord>,
    private val requiredColNames: List<String> = emptyList(),
    private val defaultedColNames: Map<String, String> = emptyMap(),
    nullableColNames: List<String> = emptyList(),
    removeInterspersedEmptyLines: Boolean = true
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
        bodyLines = if (removeInterspersedEmptyLines)
            rawBodyLines.filter(::isLineNotEmpty)
        else
            rawBodyLines.subList(
                rawBodyLines.indexOfFirst(::isLineNotEmpty),
                rawBodyLines.indexOfLast(::isLineNotEmpty) + 1
            )
        numRows = bodyLines.size

        val expectedColNames = requiredColNames + defaultedColNames.keys + nullableColNames

        // 1. Find the index of each expected column name. Emit errors resp. warnings for missing columns.
        colMap = mutableMapOf()
        for (colName in expectedColNames) {
            val col = header.indexOf(colName)
            when {
                col != -1 ->
                    colMap[colName] = col
                colName in requiredColNames ->
                    log(headerLineNo, ERROR, "Missing required column '$colName'.")
                colName in defaultedColNames ->
                    log(headerLineNo, WARN, "Missing column '$colName'. Will default to ${defaultedColNames[colName]}.")
                else -> log(headerLineNo, WARN, "Missing column '$colName'.")
            }
        }

        // 2. Emit a warning for each unexpected column name.
        for (colName in header)
            if (colName.isNotEmpty() && colName !in expectedColNames)
                log(headerLineNo, WARN, "Unexpected column '$colName'.")
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
                    log(getLineNo(row), ERROR, "Empty cell in required column '$colName'.")
                colName in defaultedColNames ->
                    log(getLineNo(row), WARN, "Empty cell in column '$colName'. Will default to '$default'.")
            }
        }
        // If the column is present but the cell is empty, or if the column is missing in the table,
        // return the default if it's a defaulted column and null otherwise.
        // In the second case, no warning/error is logged since that has already been done when the header was parsed.
        return default
    }

    fun <T> getConverted(row: Int, colName: String, lazyTypeDesc: () -> String, convert: (String) -> T): T? {
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
                    log(l, ERROR, "Empty cell required column '$colName', but must be $typeDesc.")
                in defaultedColNames ->
                    log(l, WARN, "Empty cell in column '$colName', but must be $typeDesc. Will default to '$default'.")
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
                        log(line, ERROR, "'$str' in required column '$colName' is not $typeDesc.")
                    in defaultedColNames ->
                        log(line, WARN, "'$str' in column '$colName' is not $typeDesc. Will default to '$default'.")
                    else ->
                        log(line, WARN, "'$str' in column '$colName' is not $typeDesc. Will discard the cell.")
                }
            }

        // Return the default if it's a defaulted column and null otherwise.
        return default?.let(convert)
    }

    fun getInt(row: Int, colName: String, nonNegative: Boolean = false, nonZero: Boolean = false): Int? =
        getConverted(row, colName, { "an integer" + restrictionStr(nonNegative, nonZero) }) { str ->
            str.toInt(nonNegative, nonZero)
        }

    fun getFiniteFloat(row: Int, colName: String, nonNegative: Boolean = false, nonZero: Boolean = false): Float? =
        getConverted(row, colName, { "a finite decimal number" + restrictionStr(nonNegative, nonZero) }) { str ->
            str.toFiniteFloat(nonNegative, nonZero)
        }

    inline fun <reified T : Enum<T>> getEnum(row: Int, colName: String): T? =
        getConverted(row, colName, { "one of ${optionStr<T>()}" }) { str ->
            str.toEnum<T>()
        }

    inline fun <reified T : Enum<T>, reified U : Enum<U>> getEnumPair(row: Int, colName: String): Pair<T, U>? =
        getConverted(
            row, colName,
            { "one of ${optionStr<T>()} and one of ${optionStr<U>()}, separated by a space, or the other way around" },
            String::toEnumPair
        )

    inline fun <reified T : Enum<T>> getEnumList(row: Int, colName: String): List<T>? =
        getConverted(row, colName, { "a space-separated list of ${optionStr<T>()}" }, String::toEnumList)

    fun getColor(row: Int, colName: String): Color? =
        getConverted(row, colName, { "a valid color" }, String::toColor)

    fun getFontSpec(row: Int, colName: String): FontSpec? =
        getConverted(row, colName, { FONT_SPEC_TYPE_DESC }, String::toFontSpec)

    fun <T> getLookup(row: Int, colName: String, map: Map<String, T>): T? =
        getConverted(row, colName, { "one of " + map.keys.joinToString("/") + ", ignoring case" }) { str ->
            map[str] ?: throw IllegalArgumentException()
        }

    companion object {

        private const val FONT_SPEC_TYPE_DESC = "a valid font specification consisting of at least font family, " +
                "integer height in pixels (+ optional decimal extra line spacing in pixels), and color " +
                "(e.g., 'sans-serif 12+4.5 white'), optionally including 'bold' and/or 'italic'"

        private fun restrictionStr(nonNegative: Boolean, nonZero: Boolean) =
            when {
                nonNegative && nonZero -> " > 0"
                nonNegative && !nonZero -> " >= 0"
                !nonNegative && nonZero -> " != 0"
                else -> ""
            }

        inline fun <reified T : Enum<T>> optionStr() =
            enumValues<T>().joinToString("/") { "'" + it.name.toLowerCase().replace('_', ' ') + "'" }

    }

}
