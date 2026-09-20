package com.maalik.projectlily

import android.app.Activity
import android.content.Intent
import android.net.Uri
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets

/** Saves the active Lily query as a real .sql file through Android's document provider. */
internal class QueryFileSaveController(
    private val activity: Activity,
    private val queryProvider: () -> Pair<String, String>
) {
    companion object { const val REQUEST_CODE = 96 }
    private var pendingQuery: String? = null
    private var pendingName: String = "Query 1"

    fun saveActiveQuery() {
        val (name, query) = queryProvider()
        pendingQuery = query
        pendingName = safeName(name)
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "text/plain"
            putExtra(Intent.EXTRA_TITLE, "${pendingName}.sql")
        }
        activity.startActivityForResult(intent, REQUEST_CODE)
    }

    fun handleActivityResult(requestCode: Int, resultCode: Int, uri: Uri?): Boolean {
        if (requestCode != REQUEST_CODE) return false
        if (resultCode == Activity.RESULT_OK && uri != null) {
            val query = pendingQuery.orEmpty()
            val name = pendingName
            Thread {
                try {
                    activity.contentResolver.openOutputStream(uri)?.use { raw ->
                        OutputStreamWriter(raw, StandardCharsets.UTF_8).buffered().use { out ->
                            out.write(query)
                            if (query.isNotEmpty() && !query.endsWith("\n")) out.write("\n")
                        }
                    } ?: throw IllegalStateException("Could not open output file")
                    activity.runOnUiThread { android.widget.Toast.makeText(activity, "Saved $name.sql", android.widget.Toast.LENGTH_SHORT).show() }
                } catch (e: Exception) {
                    activity.runOnUiThread { android.widget.Toast.makeText(activity, "Save failed: ${e.message ?: "error"}", android.widget.Toast.LENGTH_LONG).show() }
                }
            }.start()
        }
        pendingQuery = null
        pendingName = "Query 1"
        return true
    }

    private fun safeName(value: String): String = value
        .replace(Regex("[^A-Za-z0-9._ -]"), "_")
        .trim()
        .removeSuffix(".sql")
        .ifBlank { "Query 1" }
        .take(80)
}
