package com.maalik.projectlily.rules

import android.database.sqlite.SQLiteDatabase
import java.util.Locale

/**
 * Conservative MySQL 8.4 strict-mode preflight.
 *
 * This validator is deliberately isolated from query execution. It only blocks a
 * statement when Lily can identify a MySQL-default-mode violation with high
 * confidence. Rules that are already enforced by SQLite (PRIMARY KEY, UNIQUE,
 * NOT NULL, FOREIGN KEY, CHECK) remain delegated to the live database so the
 * existing execution path is not duplicated or altered.
 */
internal object MySqlStrictRuleValidator {
    const val DEFAULT_SQL_MODE = "ONLY_FULL_GROUP_BY,STRICT_TRANS_TABLES,NO_ZERO_IN_DATE,NO_ZERO_DATE,ERROR_FOR_DIVISION_BY_ZERO,NO_ENGINE_SUBSTITUTION"

    private val aggregateFunctions = setOf(
        "AVG", "BIT_AND", "BIT_OR", "BIT_XOR", "COUNT", "GROUP_CONCAT", "JSON_ARRAYAGG",
        "JSON_OBJECTAGG", "MAX", "MIN", "STD", "STDDEV", "STDDEV_POP", "STDDEV_SAMP",
        "SUM", "VAR_POP", "VAR_SAMP", "VARIANCE"
    )

    data class Violation(
        val code: String,
        val message: String,
        val columnOffset: Int = 0
    )

    private enum class Kind { WORD, NUMBER, STRING, SYMBOL }
    private data class Token(val kind: Kind, val text: String, val offset: Int)
    private data class ColumnInfo(val name: String, val type: String, val notNull: Boolean, val primaryKey: Int, val hasDefault: Boolean)

    fun validate(database: SQLiteDatabase, sql: String): Violation? {
        val trimmed = sql.trim()
        if (trimmed.isBlank()) return null
        val tokens = tokenize(sql)
        if (tokens.isEmpty()) return null

        val first = firstWord(tokens) ?: return null
        val upper = first.uppercase(Locale.US)

        // MySQL 8.4 default mode: ONLY_FULL_GROUP_BY.
        if (upper == "SELECT") {
            checkOnlyFullGroupBy(database, sql, tokens)?.let { return it }
        }

        // MySQL 8.4 default modes: NO_ZERO_DATE / NO_ZERO_IN_DATE.
        if (upper in setOf("INSERT", "UPDATE", "REPLACE", "LOAD")) {
            checkStrictDmlAssignments(database, sql, tokens)?.let { return it }
            checkDivisionByZero(tokens)?.let { return it }
        }

        return null
    }

    private fun checkDivisionByZero(tokens: List<Token>): Violation? {
        for (i in 0 until tokens.lastIndex) {
            val a = tokens[i]
            val b = tokens[i + 1]
            if (a.text == "/" && b.kind == Kind.NUMBER && b.text == "0") {
                return Violation(
                    "ERROR_FOR_DIVISION_BY_ZERO",
                    "MySQL strict mode rejects division by zero in INSERT/UPDATE expressions when ERROR_FOR_DIVISION_BY_ZERO is enabled.",
                    a.offset
                )
            }
            if (a.text.equals("DIV", true) && b.kind == Kind.NUMBER && b.text == "0") {
                return Violation(
                    "ERROR_FOR_DIVISION_BY_ZERO",
                    "MySQL strict mode rejects integer division by zero in INSERT/UPDATE expressions when ERROR_FOR_DIVISION_BY_ZERO is enabled.",
                    a.offset
                )
            }
        }
        return null
    }

    private fun checkStrictDmlAssignments(database: SQLiteDatabase, sql: String, tokens: List<Token>): Violation? {
        val first = firstWord(tokens)?.uppercase(Locale.US) ?: return null
        val table = when (first) {
            "INSERT", "REPLACE" -> parseInsertTable(tokens)
            "UPDATE" -> tokens.drop(1).firstOrNull { it.kind == Kind.WORD }?.text
            else -> null
        } ?: return null

        val columns = loadColumns(database, table) ?: return null

        if (first == "INSERT" || first == "REPLACE") {
            val open = tokens.indexOfFirst { it.text == "(" }
            val close = if (open >= 0) matchingParen(tokens, open) else -1
            val valuesIndex = tokens.indexOfFirst { it.text.equals("VALUES", true) }
            if (open >= 0 && close > open && valuesIndex > close) {
                val columnTokens = tokens.subList(open + 1, close)
                val names = splitTopLevel(columnTokens, ",").map { it.firstOrNull { t -> t.kind == Kind.WORD || t.text.startsWith("`") }?.text?.trim('`') }
                checkMissingRequiredColumns(columns, names, open)?.let { return it }
                var tupleOpen = -1
                for (j in valuesIndex + 1 until tokens.size) { if (tokens[j].text == "(") { tupleOpen = j; break } }
                if (tupleOpen >= 0) {
                    val tupleClose = matchingParen(tokens, tupleOpen)
                    if (tupleClose > tupleOpen) {
                        val values = splitTopLevel(tokens.subList(tupleOpen + 1, tupleClose), ",")
                        for (i in minOf(names.size, values.size).let { 0 until it }) {
                            val name = names[i] ?: continue
                            val info = columns.firstOrNull { it.name.equals(name, true) } ?: continue
                            validateAssignedLiteral(info, values[i])?.let { return it }
                        }
                    }
                }
            }
            return null
        }

        val setIndex = tokens.indexOfFirst { it.text.equals("SET", true) }
        if (setIndex >= 0) {
            val assignmentEnd = listOfNotNull(
                findTopLevelKeyword(tokens, "WHERE", setIndex + 1),
                findTopLevelKeyword(tokens, "ORDER", setIndex + 1),
                findTopLevelKeyword(tokens, "LIMIT", setIndex + 1)
            ).minOrNull() ?: tokens.size
            val assignments = splitTopLevel(tokens.subList(setIndex + 1, assignmentEnd), ",")
            for (assignment in assignments) {
                val eq = assignment.indexOfFirst { it.text == "=" }
                if (eq <= 0 || eq + 1 >= assignment.size) continue
                val nameToken = assignment[0]
                val info = columns.firstOrNull { it.name.equals(nameToken.text.trim('`'), true) } ?: continue
                validateAssignedLiteral(info, assignment.subList(eq + 1, assignment.size))?.let { return it }
            }
        }
        return null
    }

    // MySQL 8.4 default mode: STRICT_TRANS_TABLES. An INSERT/REPLACE with an explicit
    // column list that omits a NOT NULL column with no DEFAULT is rejected by MySQL
    // ("Field 'x' doesn't have a default value") even though SQLite would happily leave
    // that column NULL, so this must be caught here rather than left to the live database.
    private fun checkMissingRequiredColumns(columns: List<ColumnInfo>, providedNames: List<String?>, columnListOffset: Int): Violation? {
        val provided = providedNames.filterNotNull().map { it.uppercase(Locale.US) }.toSet()
        val missing = columns.firstOrNull { col ->
            col.notNull && !col.hasDefault && col.name.uppercase(Locale.US) !in provided
        } ?: return null
        return Violation(
            "STRICT_TRANS_TABLES",
            "MySQL strict mode rejects an INSERT/REPLACE that omits NOT NULL column '${missing.name}', which has no default value.",
            columnListOffset
        )
    }

    private fun validateAssignedLiteral(info: ColumnInfo, valueTokens: List<Token>): Violation? {
        if (valueTokens.size != 1) return null
        val v = valueTokens[0]
        if (v.text.equals("NULL", true) && info.notNull) {
            return Violation(
                "STRICT_TRANS_TABLES",
                "MySQL strict mode rejects NULL for a NOT NULL column: ${info.name}.",
                v.offset
            )
        }
        if (v.kind == Kind.STRING) {
            val value = v.text
            val upperType = info.type.uppercase(Locale.US)
            val numeric = upperType.contains("INT") || upperType.contains("DECIMAL") || upperType.contains("NUMERIC") ||
                upperType.contains("FLOAT") || upperType.contains("DOUBLE") || upperType.contains("REAL")
            if (numeric && value.toDoubleOrNull() == null) {
                return Violation(
                    "STRICT_TRANS_TABLES",
                    "MySQL strict mode rejects a nonnumeric string for numeric column ${info.name}: '$value'.",
                    v.offset
                )
            }
            if (upperType.contains("DATE") || upperType.contains("DATETIME") || upperType.contains("TIMESTAMP")) {
                checkDateLiteral(value, v.offset)?.let { return it }
            }
        }
        return null
    }

    private fun checkDateLiteral(value: String, offset: Int): Violation? {
        val date = Regex("^(\\d{4})-(\\d{2})-(\\d{2})(?:[ T](\\d{2}):(\\d{2})(?::(\\d{2})(?:\\.\\d+)?)?)?$").matchEntire(value) ?: return null
        val month = date.groupValues[2].toIntOrNull() ?: return null
        val day = date.groupValues[3].toIntOrNull() ?: return null
        val hour = date.groupValues.getOrNull(4)?.toIntOrNull() ?: 0
        val minute = date.groupValues.getOrNull(5)?.toIntOrNull() ?: 0
        val second = date.groupValues.getOrNull(6)?.toIntOrNull() ?: 0
        if (date.groupValues[1] == "0000" && month == 0 && day == 0) {
            return Violation("NO_ZERO_DATE", "MySQL 8.4 default SQL mode rejects the zero date 0000-00-00.", offset)
        }
        if (month == 0 || day == 0) {
            return Violation(
                "NO_ZERO_IN_DATE",
                "MySQL 8.4 default SQL mode rejects dates containing a zero month or day.",
                offset
            )
        }
        if (hour !in 0..23 || minute !in 0..59 || second !in 0..59) {
            return Violation("STRICT_TRANS_TABLES", "Invalid time value for a MySQL DATE/DATETIME/TIMESTAMP column.", offset)
        }
        return null
    }

    private fun checkOnlyFullGroupBy(database: SQLiteDatabase, sql: String, tokens: List<Token>): Violation? {
        val groupIndex = findTopLevelKeyword(tokens, "GROUP") ?: return null
        if (groupIndex + 1 >= tokens.size || !tokens[groupIndex + 1].text.equals("BY", true)) return null
        val selectIndex = tokens.indexOfFirst { it.text.equals("SELECT", true) }
        val fromIndex = findTopLevelKeyword(tokens, "FROM", selectIndex + 1) ?: return null
        if (selectIndex < 0 || fromIndex <= selectIndex + 1) return null

        val selectItems = splitTopLevel(tokens.subList(selectIndex + 1, fromIndex), ",")
        val groupEnd = listOfNotNull(
            findTopLevelKeyword(tokens, "HAVING", groupIndex + 2),
            findTopLevelKeyword(tokens, "ORDER", groupIndex + 2),
            findTopLevelKeyword(tokens, "LIMIT", groupIndex + 2),
            findTopLevelKeyword(tokens, "WINDOW", groupIndex + 2)
        ).minOrNull() ?: tokens.size
        val groupItems = splitTopLevel(tokens.subList(groupIndex + 2, groupEnd), ",")
        val normalizedGroups = groupItems.mapNotNull { normalizeExpression(it) }.toSet()
        if (normalizedGroups.isEmpty()) return null
        val groupedColumnNames = normalizedGroups.mapNotNull(::directColumnName).map { it.uppercase(Locale.US) }.toSet()

        val hasJoin = tokens.subList(fromIndex, minOf(groupIndex, tokens.size)).any { it.text.equals("JOIN", true) }
        val table = parseSingleFromTable(tokens, fromIndex, groupIndex)
        val tableColumns = table?.let { loadColumns(database, it) }.orEmpty()
        val uniqueColumns = if (tableColumns.isNotEmpty()) loadUniqueColumns(database, table!!) else emptySet()
        val groupingDeterminesSingleTable = uniqueColumns.any { it.uppercase(Locale.US) in groupedColumnNames }

        for (item in selectItems) {
            val normalized = normalizeExpression(item) ?: continue
            if (normalized.equals("DISTINCT", true)) continue
            if (isAggregate(item)) continue
            if (normalized in normalizedGroups) continue
            if (isConstant(item)) continue

            if (isWildcard(item)) {
                if (tableColumns.isEmpty()) continue
                val allGrouped = groupingDeterminesSingleTable || tableColumns.all { col -> groupedColumnNames.contains(normalizeSimpleIdentifier(col.name)) }
                if (!allGrouped) {
                    return Violation(
                        "ONLY_FULL_GROUP_BY",
                        "SELECT * is incompatible with MySQL's ONLY_FULL_GROUP_BY because grouped query results do not determine every selected column.",
                        item.firstOrNull()?.offset ?: 0
                    )
                }
                continue
            }

            // Be conservative with joins/complex expressions to avoid falsely rejecting
            // valid functional-dependency cases. Single-table direct columns can be checked.
            if (hasJoin) continue
            val direct = directColumnName(normalized)
            if (groupingDeterminesSingleTable) continue
            if (direct != null && direct in uniqueColumns && groupedColumnNames.contains(normalizeSimpleIdentifier(direct))) continue

            return Violation(
                "ONLY_FULL_GROUP_BY",
                "Selected expression '$normalized' is not in GROUP BY and is not aggregated. MySQL 8.4 default ONLY_FULL_GROUP_BY rejects this query.",
                item.firstOrNull()?.offset ?: 0
            )
        }
        return null
    }

    private fun parseSingleFromTable(tokens: List<Token>, fromIndex: Int, endExclusive: Int): String? {
        if (fromIndex + 1 >= endExclusive) return null
        val next = tokens[fromIndex + 1]
        if (next.text == "(" || next.kind != Kind.WORD && !next.text.startsWith("`")) return null
        val candidate = next.text.trim('`')
        val after = tokens.getOrNull(fromIndex + 2)?.text?.uppercase(Locale.US)
        return if (after == null || after in setOf("WHERE", "GROUP", "ORDER", "HAVING", "LIMIT", "OFFSET", "JOIN", "LEFT", "RIGHT", "INNER", "OUTER", "CROSS")) candidate else candidate
    }

    private fun parseInsertTable(tokens: List<Token>): String? {
        val intoIndex = tokens.indexOfFirst { it.text.equals("INTO", true) }
        if (intoIndex < 0 || intoIndex + 1 >= tokens.size) return null
        return tokens[intoIndex + 1].text.trim('`')
    }

    private fun loadColumns(database: SQLiteDatabase, table: String): List<ColumnInfo>? {
        return try {
            val out = mutableListOf<ColumnInfo>()
            database.rawQuery("PRAGMA table_info('" + table.replace("'", "''") + "')", null).use { c ->
                while (c.moveToNext()) out.add(ColumnInfo(c.getString(1), c.getString(2), c.getInt(3) > 0, c.getInt(5), !c.isNull(4)))
            }
            out.ifEmpty { null }
        } catch (_: Exception) { null }
    }

    private fun loadUniqueColumns(database: SQLiteDatabase, table: String): Set<String> {
        val out = mutableSetOf<String>()
        loadColumns(database, table)?.filter { it.primaryKey > 0 && it.notNull }.orEmpty().forEach { out.add(it.name) }
        try {
            database.rawQuery("PRAGMA index_list('" + table.replace("'", "''") + "')", null).use { indexes ->
                while (indexes.moveToNext()) {
                    val unique = indexes.getInt(2) > 0
                    if (!unique) continue
                    val indexName = indexes.getString(1)
                    database.rawQuery("PRAGMA index_info('" + indexName.replace("'", "''") + "')", null).use { info ->
                        val cols = mutableListOf<String>()
                        while (info.moveToNext()) cols.add(info.getString(2))
                        if (cols.size == 1) out.add(cols[0])
                    }
                }
            }
        } catch (_: Exception) { }
        return out
    }

    private fun directColumnName(expression: String): String? {
        val parts = expression.trim().removeSuffix(";").split(Regex("\\s+"))
        val core = when {
            parts.size == 1 -> parts[0]
            parts.size == 3 && parts[1].equals("AS", true) -> parts[0]
            else -> return null
        }
        if (core.contains("(") || core.count { it == '.' } > 1) return null
        return core.substringAfterLast('.').trim('`').takeIf { it.matches(Regex("[A-Za-z_][A-Za-z0-9_]*")) }
    }

    private fun normalizeExpression(tokens: List<Token>): String? {
        val text = tokens.asSequence().filter { it.text != ";" }.joinToString(" ") { it.text.trim() }.trim()
        return text.takeIf { it.isNotBlank() }?.replace(Regex("\\s+"), " ")?.uppercase(Locale.US)
    }

    private fun normalizeSimpleIdentifier(value: String): String = value.trim().trim('`').uppercase(Locale.US)

    private fun isAggregate(tokens: List<Token>): Boolean {
        for (i in tokens.indices) {
            if (tokens[i].kind == Kind.WORD && aggregateFunctions.contains(tokens[i].text.uppercase(Locale.US))) {
                if (tokens.getOrNull(i + 1)?.text == "(") return true
            }
        }
        return false
    }

    private fun isWildcard(tokens: List<Token>): Boolean = tokens.size == 1 && tokens[0].text == "*" ||
        tokens.size == 3 && tokens[1].text == "." && tokens[2].text == "*"

    private fun isConstant(tokens: List<Token>): Boolean {
        if (tokens.isEmpty()) return true
        val allowed = tokens.all { it.kind == Kind.NUMBER || it.kind == Kind.STRING || it.text in setOf("+", "-", ".", "(", ")", "NULL", "TRUE", "FALSE") }
        return allowed
    }

    private fun firstWord(tokens: List<Token>): String? = tokens.firstOrNull { it.kind == Kind.WORD }?.text

    private fun findTopLevelKeyword(tokens: List<Token>, word: String, start: Int = 0): Int? {
        var depth = 0
        for (i in start until tokens.size) {
            when (tokens[i].text) { "(" -> depth++; ")" -> depth-- }
            if (depth == 0 && tokens[i].text.equals(word, true)) return i
        }
        return null
    }

    private fun matchingParen(tokens: List<Token>, openIndex: Int): Int {
        var depth = 0
        for (i in openIndex until tokens.size) {
            when (tokens[i].text) {
                "(" -> depth++
                ")" -> { depth--; if (depth == 0) return i }
            }
        }
        return -1
    }

    private fun splitTopLevel(tokens: List<Token>, separator: String): List<List<Token>> {
        if (tokens.isEmpty()) return emptyList()
        val out = mutableListOf<List<Token>>(); var start = 0; var depth = 0
        for (i in tokens.indices) {
            when (tokens[i].text) { "(" -> depth++; ")" -> depth-- }
            if (depth == 0 && tokens[i].text == separator) { out.add(tokens.subList(start, i)); start = i + 1 }
        }
        out.add(tokens.subList(start, tokens.size)); return out.filter { it.isNotEmpty() }
    }

    private fun tokenize(sql: String): List<Token> {
        val out = mutableListOf<Token>(); var i = 0
        fun add(kind: Kind, start: Int, end: Int) { out.add(Token(kind, sql.substring(start, end), start)) }
        while (i < sql.length) {
            val c = sql[i]
            if (c.isWhitespace()) { i++; continue }
            if (c == '-' && i + 1 < sql.length && sql[i + 1] == '-') { i += 2; while (i < sql.length && sql[i] != '\n') i++; continue }
            if (c == '/' && i + 1 < sql.length && sql[i + 1] == '*') { i += 2; while (i + 1 < sql.length && !(sql[i] == '*' && sql[i + 1] == '/')) i++; i = minOf(i + 2, sql.length); continue }
            if (c == '\'' || c == '"' || c == '`') {
                val quote = c; val start = i; i++
                val contentStart = i
                while (i < sql.length) {
                    if (sql[i] == quote) {
                        if (i + 1 < sql.length && sql[i + 1] == quote) { i += 2; continue }
                        val raw = sql.substring(contentStart, i); out.add(Token(if (quote == '`') Kind.WORD else Kind.STRING, raw, start)); i++; break
                    }
                    i++
                }
                continue
            }
            if (c.isLetter() || c == '_') { val start = i; i++; while (i < sql.length && (sql[i].isLetterOrDigit() || sql[i] == '_' || sql[i] == '$')) i++; add(Kind.WORD, start, i); continue }
            if (c.isDigit()) { val start = i; i++; while (i < sql.length && (sql[i].isDigit() || sql[i] == '.')) i++; add(Kind.NUMBER, start, i); continue }
            out.add(Token(Kind.SYMBOL, c.toString(), i)); i++
        }
        return out
    }
}
