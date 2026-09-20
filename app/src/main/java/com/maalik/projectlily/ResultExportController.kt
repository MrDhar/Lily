package com.maalik.projectlily

import android.app.Activity
import android.content.Intent
import android.net.Uri
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets

/** Owns exporting data currently displayed in result tabs. */
internal class ResultExportController(
    private val activity: Activity,
    private val resultProvider: (Int) -> List<List<String>>?
) {
    companion object { const val REQUEST_CODE = 95 }

    private var pendingData: List<List<String>>? = null
    private var pendingTitle: String = "query-result"
    private var pendingFormat: Format = Format.CSV

    private enum class Format { CSV, JSON }

    fun exportTab(index: Int, title: String? = null) = openCreateDocument(index, title, Format.CSV, "Export CSV")
    fun exportJsonTab(index: Int, title: String? = null) = openCreateDocument(index, title, Format.JSON, "Export JSON")
    fun saveAsTab(index: Int, title: String? = null) = openCreateDocument(index, title, Format.CSV, "Save as")
    fun exportActive(activeIndex: Int, title: String? = null) = exportTab(activeIndex, title)

    private fun openCreateDocument(index: Int, title: String?, format: Format, exportLabel: String) {
        val data = resultProvider(index)
        if (data.isNullOrEmpty()) {
            Toasts.error(activity, "No result data to $exportLabel")
            return
        }
        pendingData = data
        pendingTitle = safeFileName(title ?: "query-result")
        pendingFormat = format
        val mime = if (format == Format.JSON) "application/json" else "text/csv"
        val extension = if (format == Format.JSON) "json" else "csv"
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = mime
            putExtra(Intent.EXTRA_TITLE, "$pendingTitle.$extension")
        }
        activity.startActivityForResult(intent, REQUEST_CODE)
    }

    fun handleActivityResult(requestCode: Int, resultCode: Int, uri: Uri?): Boolean {
        if (requestCode != REQUEST_CODE) return false
        if (resultCode == Activity.RESULT_OK && uri != null) {
            pendingData?.let { data ->
                if (pendingFormat == Format.JSON) writeJson(uri, data, pendingTitle) else writeCsv(uri, data, pendingTitle)
            }
        }
        pendingData = null
        pendingTitle = "query-result"
        pendingFormat = Format.CSV
        return true
    }

    private fun writeCsv(uri: Uri, data: List<List<String>>, title: String) {
        Thread {
            try {
                activity.contentResolver.openOutputStream(uri)?.use { raw ->
                    OutputStreamWriter(raw, StandardCharsets.UTF_8).buffered().use { out ->
                        data.forEach { row ->
                            row.forEachIndexed { i, value ->
                                if (i > 0) out.write(','.code)
                                out.write(csvEscape(value))
                            }
                            out.newLine()
                        }
                    }
                } ?: throw IllegalStateException("Could not open output file")
                activity.runOnUiThread { Toasts.ok(activity, "Exported $title as CSV") }
            } catch (e: Exception) {
                activity.runOnUiThread { Toasts.error(activity, "CSV export failed: ${e.message ?: "error"}") }
            }
        }.start()
    }

    private fun writeJson(uri: Uri, data: List<List<String>>, title: String) {
        Thread {
            try {
                activity.contentResolver.openOutputStream(uri)?.use { raw ->
                    OutputStreamWriter(raw, StandardCharsets.UTF_8).buffered().use { out ->
                        val header = data.firstOrNull().orEmpty()
                        out.write("[\n")
                        data.drop(1).forEachIndexed { rowIndex, row ->
                            if (rowIndex > 0) out.write(",\n")
                            out.write("  {")
                            header.forEachIndexed { index, key ->
                                if (index > 0) out.write(", ")
                                out.write("\"")
                                out.write(jsonEscape(key.ifBlank { "column_${index + 1}" }))
                                out.write("\":")
                                val value = row.getOrElse(index) { "NULL" }
                                if (value == "NULL") out.write("null") else {
                                    out.write("\"")
                                    out.write(jsonEscape(value))
                                    out.write("\"")
                                }
                            }
                            out.write("}")
                        }
                        out.write("\n]\n")
                    }
                } ?: throw IllegalStateException("Could not open output file")
                activity.runOnUiThread { Toasts.ok(activity, "Exported $title as JSON") }
            } catch (e: Exception) {
                activity.runOnUiThread { Toasts.error(activity, "JSON export failed: ${e.message ?: "error"}") }
            }
        }.start()
    }

    private fun jsonEscape(value: String): String = buildString(value.length + 8) {
        value.forEach { ch ->
            when (ch) {
                '\\' -> append("\\\\")
                '\"' -> append("\\\"")
                '\b' -> append("\\b")
                '\u000C' -> append("\\f")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (ch.code < 0x20) append("\\u%04x".format(ch.code)) else append(ch)
            }
        }
    }

    private fun safeFileName(value: String): String = value
        .replace(Regex("[^A-Za-z0-9._ -]"), "_")
        .trim()
        .ifBlank { "query-result" }
        .take(80)

    private fun csvEscape(value: String): String = if (
        value.contains(',') || value.contains('"') || value.contains('\n') || value.contains('\r')
    ) "\"${value.replace("\"", "\"\"")}\"" else value

    private object Toasts {
        fun ok(activity: Activity, message: String) = android.widget.Toast.makeText(activity, message, android.widget.Toast.LENGTH_SHORT).show()
        fun error(activity: Activity, message: String) = android.widget.Toast.makeText(activity, message, android.widget.Toast.LENGTH_LONG).show()
    }
}
