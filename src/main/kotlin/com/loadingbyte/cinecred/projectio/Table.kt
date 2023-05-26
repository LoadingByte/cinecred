package com.loadingbyte.cinecred.projectio

import com.loadingbyte.cinecred.common.Severity
import com.loadingbyte.cinecred.common.Severity.ERROR
import com.loadingbyte.cinecred.common.Severity.WARN
import com.loadingbyte.cinecred.common.TRANSLATED_LOCALES
import com.loadingbyte.cinecred.common.l10n
import com.loadingbyte.cinecred.common.toFiniteDouble
import java.util.*


data class ParserMsg(
    val recordNo: Int?,
    val colHeader: String?,
    val cellValue: String?,
    val severity: Severity,
    val msg: String
)


class Table(
    spreadsheet: Spreadsheet,
    l10nPrefix: String,
    l10nColNames: List<String>,
    legacyColNames: Map<String, List<String>>
) {

    val log = mutableListOf<ParserMsg>()

    val numRows: Int
    private val headerRecord: List<String>
    private val bodyRecords: List<Spreadsheet.Record>
    private val colMap: Map<String, Int>

    init {
        val headerRecordNo = spreadsheet
            .indexOfFirst { record -> record.cells.any { cell -> cell.trim().startsWith("@") } }

        if (headerRecordNo == -1) {
            // If no table header can be found, log that and bail out.
            numRows = 0
            headerRecord = emptyList()
            bodyRecords = emptyList()
            colMap = emptyMap()
            log.add(ParserMsg(null, null, null, ERROR, l10n("projectIO.table.noHeader")))
        } else {
            headerRecord = spreadsheet[headerRecordNo].cells.map { it.trim() }

            // Determine the records which make up the data rows of the table.
            val rawBodyRecords = spreadsheet.drop(headerRecordNo + 1)
            bodyRecords =
                rawBodyRecords.subList(
                    rawBodyRecords.indexOfFirst(Spreadsheet.Record::isNotEmpty).coerceAtLeast(0), // avoid crash upon -1
                    rawBodyRecords.indexOfLast(Spreadsheet.Record::isNotEmpty) + 1
                )
            numRows = bodyRecords.size

            // 1. Find the index of each expected column name. Emit warnings for legacy and missing columns.
            colMap = TreeMap(String.CASE_INSENSITIVE_ORDER)
            outer@
            for (l10nColName in l10nColNames) {
                val key = "$l10nPrefix$l10nColName"
                val possibleColNames = TRANSLATED_LOCALES.map { "@${l10n(key, it)}" }
                for (colName in possibleColNames) {
                    val col = headerRecord.indexOfFirst { it.equals(colName, ignoreCase = true) }
                    if (col != -1) {
                        colMap[l10nColName] = col
                        continue@outer
                    }
                }
                // Prepare the column name which will be shown in a warning message.
                val colName = "@${l10n(key)}"
                // The column might be missing, but first look for a legacy column name. Emit a warning if we find one.
                val possibleLegacyColNames = legacyColNames.getOrDefault(l10nColName, emptyList()).map { "@$it" }
                for (legacyColName in possibleLegacyColNames) {
                    val col = headerRecord.indexOfFirst { it.equals(legacyColName, ignoreCase = true) }
                    if (col != -1) {
                        colMap[l10nColName] = col
                        val msg = l10n("projectIO.table.legacyColumnName", colName)
                        log.add(ParserMsg(headerRecordNo, legacyColName, null, WARN, msg))
                        continue@outer
                    }
                }
                // The column is missing. Emit a warning.
                val msg = l10n("projectIO.table.missingExpectedColumn")
                log.add(ParserMsg(headerRecordNo, colName, null, WARN, msg))
            }

            // 2. Emit a warning for each unexpected column name.
            for ((col, colName) in headerRecord.withIndex())
                if (colName.isNotEmpty() && col !in colMap.values)
                    log.add(ParserMsg(headerRecordNo, colName, null, WARN, l10n("projectIO.table.unexpectedColumn")))
        }
    }

    fun log(row: Int?, l10nColName: String?, severity: Severity, msg: String) {
        val cellValue = if (row != null && l10nColName != null) getString(row, l10nColName) else null
        log.add(ParserMsg(row?.let(::getRecordNo), l10nColName?.let(::getColHeader), cellValue, severity, msg))
    }

    private fun getRecordNo(row: Int): Int = bodyRecords[row].recordNo
    private fun getColHeader(l10ColName: String): String? = colMap[l10ColName]?.let(headerRecord::get)

    fun isEmpty(row: Int, l10nColName: String): Boolean =
        colMap[l10nColName]?.let { col -> bodyRecords[row].cells.getOrNull(col).isNullOrBlank() } ?: true

    fun getString(row: Int, l10nColName: String): String? {
        val col = colMap[l10nColName]
        if (col != null) {
            // If the column is present in the table, try to retrieve its value in this row.
            val str = bodyRecords[row].cells.getOrNull(col)?.trim()
            // If the column is present in this row and the value is non-empty, return it.
            if (!str.isNullOrEmpty())
                return str
        }
        // If the column is present but the cell is empty, or if the column is missing in the table, return null.
        return null
    }

    fun getFiniteDouble(row: Int, l10nColName: String, nonNeg: Boolean = false, non0: Boolean = false): Double? {
        val str = getString(row, l10nColName) ?: return null
        return try {
            str.toFiniteDouble(nonNeg, non0)
        } catch (_: NumberFormatException) {
            val msg = when {
                nonNeg && non0 -> l10n("projectIO.table.illFormattedDoubleGT0")
                nonNeg && !non0 -> l10n("projectIO.table.illFormattedDoubleGTE0")
                !nonNeg && non0 -> l10n("projectIO.table.illFormattedDoubleNEQ0")
                else -> l10n("projectIO.table.illFormattedDouble")
            }
            log(row, l10nColName, WARN, msg)
            null
        }
    }

    fun <T> getLookup(row: Int, l10nColName: String, map: Map<String, T>, l10Warning: String, fallback: T? = null): T? {
        val str = getString(row, l10nColName) ?: return null
        map[str]?.let { return it }
        log(row, l10nColName, WARN, l10n(l10Warning, map.keys.joinToString()))
        return fallback
    }

}
