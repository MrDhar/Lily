package com.maalik.projectlily

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/** Owns the Messages/Error panel so MainActivity only reports query failures to it. */
internal class ErrorPanelController(
    private val activity: Activity,
    private val panel: LinearLayout
) {
    private data class QueryError(
        val statementNumber: Int,
        val message: String,
        val location: String,
        val elapsedMs: String,
        val query: String
    )

    private val errors = mutableListOf<QueryError>()
    private val list: LinearLayout = panel.findViewById(R.id.errorList)
    private val count: TextView = panel.findViewById(R.id.errorPanelCount)

    fun setup() {
        panel.findViewById<Button>(R.id.clearErrorsButton)?.setOnClickListener {
            errors.clear()
            render()
        }
        render()
    }

    fun addError(statementNumber: Int, message: String, location: String, elapsedMs: String, query: String) {
        errors.add(0, QueryError(statementNumber, message, location, elapsedMs, query.trim()))
        while (errors.size > 20) errors.removeAt(errors.lastIndex)
        render()
    }

    private fun render() {
        panel.visibility = if (errors.isEmpty()) View.GONE else View.VISIBLE
        count.text = "Errors ${errors.size}"
        list.removeAllViews()
        errors.forEach { error ->
            val card = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(9), dp(7), dp(9), dp(7))
                background = rounded(Color.rgb(24, 25, 31), 1, Color.rgb(54, 48, 53), 8f)
            }
            val title = TextView(activity).apply {
                text = "Statement ${error.statementNumber} failed"
                textSize = 11f
                setTextColor(Color.rgb(229, 139, 134))
                setTypeface(null, Typeface.BOLD)
            }
            val detail = TextView(activity).apply {
                text = formatUserFacingSqlError(error.message) + "\n${error.location} · ${error.elapsedMs} ms"
                textSize = 10.5f
                setTextColor(Color.rgb(205, 201, 207))
                setPadding(0, dp(4), 0, dp(4))
                setTextIsSelectable(true)
            }
            val sql = TextView(activity).apply {
                text = error.query.ifBlank { "(statement unavailable)" }
                textSize = 9.5f
                setTextColor(Color.rgb(171, 190, 203))
                setPadding(dp(7), dp(6), dp(7), dp(6))
                background = rounded(Color.rgb(17, 18, 23), 1, Color.rgb(45, 47, 55), 6f)
                setTextIsSelectable(true)
            }
            card.addView(title)
            card.addView(detail)
            card.addView(sql)
            list.addView(card, LinearLayout.LayoutParams(-1, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(5) })
        }
    }

    private fun formatUserFacingSqlError(raw: String): String {
        val m = raw.trim().removePrefix("android.database.sqlite.SQLiteException: ").trim()
        val lower = m.lowercase()
        return when {
            lower.contains("no such table") -> {
                val name = m.substringAfter(":", "").trim()
                "Table not found — $name does not exist in the selected database."
            }
            lower.contains("no such column") -> {
                val name = m.substringAfter(":", "").trim()
                "Column not found — $name does not exist in the referenced table(s)."
            }
            lower.contains("only_full_group_by") -> "MySQL ONLY_FULL_GROUP_BY violation — every selected nonaggregated expression must be grouped or functionally determined by the GROUP BY columns."
            lower.contains("error_for_division_by_zero") -> "MySQL strict-mode division by zero — INSERT/UPDATE expressions cannot divide by zero."
            lower.contains("no_zero_date") -> "MySQL NO_ZERO_DATE violation — 0000-00-00 is not allowed by the default strict SQL mode."
            lower.contains("no_zero_in_date") -> "MySQL NO_ZERO_IN_DATE violation — a zero month or day is not allowed by the default strict SQL mode."
            lower.contains("strict_trans_tables") -> "MySQL STRICT_TRANS_TABLES violation — the supplied value is not valid for the target column."
            lower.contains("no such function") -> {
                val name = Regex("no such function:\\s*(\\w+)", RegexOption.IGNORE_CASE).find(m)?.groupValues?.getOrNull(1)
                val label = if (name != null) " '$name'" else ""
                "Unsupported function$label — Project Lily runs on SQLite, so only a subset of MySQL functions is translated. This one has no SQLite equivalent here."
            }
            lower.contains("syntax error") -> "SQL syntax error — check the statement structure, keywords, quotes, and punctuation."
            lower.contains("near ") && lower.contains("syntax") -> "SQL syntax error — SQLite could not understand the SQL near the reported token."
            lower.contains("datatype mismatch") -> "Data type mismatch — one or more values are not compatible with the target column type."
            lower.contains("unique constraint") -> "Duplicate value — a UNIQUE or PRIMARY KEY constraint was violated."
            lower.contains("not null constraint") -> "Missing required value — a NOT NULL column was given NULL or no value."
            lower.contains("constraint failed") || lower.contains("constraint") -> "Constraint error — the statement violates a table constraint such as PRIMARY KEY, UNIQUE, NOT NULL, or FOREIGN KEY."
            else -> m.ifBlank { "SQLite could not execute this statement." }
        }
    }

    private fun dp(v: Int): Int = (v * activity.resources.displayMetrics.density + 0.5f).toInt()
    private fun rounded(fill: Int, stroke: Int, strokeColor: Int, radius: Float): android.graphics.drawable.GradientDrawable =
        android.graphics.drawable.GradientDrawable().apply {
            setColor(fill)
            setStroke(dp(stroke), strokeColor)
            cornerRadius = dp(radius.toInt()).toFloat()
        }
}
