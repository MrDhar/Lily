package com.maalik.projectlily

import android.view.KeyEvent

/**
 * Central keyboard-shortcut map for the SQL editor.
 *
 * This class owns only key -> action mapping. The existing MainActivity methods
 * remain the source of truth for what each action actually does.
 */
internal class EditorShortcuts(private val activity: MainActivity) {
    fun handle(keyCode: Int, event: KeyEvent): Boolean {
        val modifier = event.isCtrlPressed || event.isMetaPressed
        if (!modifier) return false

        val editor = activity.editorForShortcuts()
        return when (keyCode) {
            KeyEvent.KEYCODE_A -> { editor.performLilyTextAction(android.R.id.selectAll); true }
            KeyEvent.KEYCODE_C -> { editor.performLilyTextAction(android.R.id.copy); true }
            KeyEvent.KEYCODE_X -> { editor.performLilyTextAction(android.R.id.cut); true }
            KeyEvent.KEYCODE_V -> { editor.performLilyTextAction(android.R.id.paste); true }
            KeyEvent.KEYCODE_Z -> {
                if (event.isShiftPressed) editor.performLilyTextAction(android.R.id.redo)
                else editor.performLilyTextAction(android.R.id.undo)
                true
            }
            KeyEvent.KEYCODE_Y -> { editor.performLilyTextAction(android.R.id.redo); true }
            KeyEvent.KEYCODE_S -> { activity.persistCurrentScript(); activity.saveScriptTabs(); activity.statusOk("Query saved"); true }
            KeyEvent.KEYCODE_F -> { activity.showFindReplace(); true }
            KeyEvent.KEYCODE_SLASH -> { activity.toggleComment(); true }
            KeyEvent.KEYCODE_ENTER -> {
                if (event.isShiftPressed) activity.runSelectedOrAll(false)
                else activity.runSelectedOrAll(true)
                true
            }
            else -> false
        }
    }
}
