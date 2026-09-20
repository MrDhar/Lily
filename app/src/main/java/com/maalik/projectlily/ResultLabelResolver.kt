package com.maalik.projectlily

/**
 * Resolves a compact, useful label for a result tab without changing query execution.
 * A result can be named after a single referenced table. When more than one table is
 * referenced (or the parser cannot confidently isolate one), the caller should use its
 * generic Result N fallback.
 */
object ResultLabelResolver {
    private val fromJoinPattern = Regex(
        "(?is)\\b(?:FROM|JOIN)\\s+((?:`[^`]+`|[A-Za-z_][A-Za-z0-9_$]*)(?:\\.(?:`[^`]+`|[A-Za-z_][A-Za-z0-9_$]*))?)"
    )
    private val writeTablePattern = Regex(
        "(?is)\\b(?:UPDATE|INTO|DELETE\\s+FROM)\\s+((?:`[^`]+`|[A-Za-z_][A-Za-z0-9_$]*)(?:\\.(?:`[^`]+`|[A-Za-z_][A-Za-z0-9_$]*))?)"
    )

    fun referencedTables(sql: String): List<String> {
        val cleaned = stripCommentsAndStrings(sql)
        val cteNames = Regex("(?is)\\bWITH\\s+(?:RECURSIVE\\s+)?((?:`[^`]+`|[A-Za-z_][A-Za-z0-9_$]*)).*?(?=\\bSELECT\\b|\\bINSERT\\b|\\bUPDATE\\b|\\bDELETE\\b)")
            .find(cleaned)?.groupValues?.getOrNull(1).orEmpty()
            .split(',')
            .mapNotNull { Regex("`[^`]+`|[A-Za-z_][A-Za-z0-9_$]*").find(it)?.value }
            .map(::unquote)
            .toSet()
        val found = LinkedHashSet<String>()
        fun collect(pattern: Regex) {
            pattern.findAll(cleaned).forEach { m ->
                val raw = m.groupValues.getOrNull(1).orEmpty().trim()
                if (raw.isNotEmpty()) {
                    val table = unquote(raw)
                    if (table.substringAfterLast('.') !in cteNames) found.add(table)
                }
            }
        }
        collect(fromJoinPattern)
        collect(writeTablePattern)
        return found.toList()
    }

    fun tableLabel(sql: String): String? {
        val tables = referencedTables(sql)
        return if (tables.size == 1) tables[0].substringAfterLast('.').takeIf { it.isNotBlank() } else null
    }

    private fun unquote(identifier: String): String = identifier
        .split('.')
        .joinToString(".") { part ->
            if (part.length >= 2 && part.first() == '`' && part.last() == '`') part.substring(1, part.length - 1)
            else part
        }

    private fun stripCommentsAndStrings(sql: String): String {
        val out = StringBuilder(sql.length)
        var i = 0
        var quote: Char? = null
        var lineComment = false
        var blockComment = false
        while (i < sql.length) {
            val c = sql[i]
            if (lineComment) {
                if (c == '\n') { lineComment = false; out.append('\n') } else out.append(' ')
                i++; continue
            }
            if (blockComment) {
                if (c == '*' && i + 1 < sql.length && sql[i + 1] == '/') { blockComment = false; out.append("  "); i += 2 }
                else { if (c == '\n') out.append('\n') else out.append(' '); i++ }
                continue
            }
            if (quote != null) {
                if (c == quote) {
                    if (i + 1 < sql.length && sql[i + 1] == quote) { out.append("  "); i += 2; continue }
                    quote = null
                }
                out.append(if (c == '\n') '\n' else ' '); i++; continue
            }
            if (c == '-' && i + 2 < sql.length && sql[i + 1] == '-' && sql[i + 2].isWhitespace()) {
                lineComment = true; out.append("   "); i += 3; continue
            }
            if (c == '#') { lineComment = true; out.append(' '); i++; continue }
            if (c == '/' && i + 1 < sql.length && sql[i + 1] == '*') { blockComment = true; out.append("  "); i += 2; continue }
            if (c == '\'' || c == '"') { quote = c; out.append(' '); i++; continue }
            out.append(c); i++
        }
        return out.toString()
    }
}
