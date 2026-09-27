package dev.shrimpscript.porthole.terminal

import android.content.Context
import android.text.InputType
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView

/**
 * Keyboard capture for the terminal.
 *
 * A Compose `BasicTextField` overlay does NOT work here. It leaves the IME bound to a
 * fallback connection (`dumpsys input_method` shows `mServedView=null`) and every
 * keystroke is silently discarded - verified with a marker file, where a command typed on
 * the phone never ran. A terminal needs a real editor View that owns its InputConnection,
 * which is the approach Termux takes.
 *
 * It is also not a text field in any real sense: there is no buffer to edit, because the
 * shell on the other end owns the line. Keys become bytes and leave immediately.
 */
class TerminalInputView(context: Context) : View(context) {

    var onBytes: ((String) -> Unit)? = null

    init {
        isFocusable = true
        isFocusableInTouchMode = true
    }

    override fun onCheckIsTextEditor(): Boolean = true

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection {
        // VISIBLE_PASSWORD asks the IME for a plain, un-corrected stream: no
        // autocorrect, no capitalisation, no suggestion bar rewriting shell commands.
        outAttrs.inputType = InputType.TYPE_CLASS_TEXT or
            InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
        outAttrs.imeOptions = EditorInfo.IME_ACTION_NONE or
            EditorInfo.IME_FLAG_NO_FULLSCREEN or
            EditorInfo.IME_FLAG_NO_EXTRACT_UI or
            EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING

        // fullEditor = false: there is no Editable to maintain, so the IME falls back to
        // committing text and sending key events, which is exactly what a terminal wants.
        return object : BaseInputConnection(this, false) {
            override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
                text?.takeIf { it.isNotEmpty() }?.let { onBytes?.invoke(it.toString()) }
                return true
            }

            override fun setComposingText(text: CharSequence?, newCursorPosition: Int): Boolean {
                // Composition is meaningless against a remote shell; wait for the commit
                // rather than sending half-formed words.
                return true
            }

            override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
                repeat(beforeLength) { onBytes?.invoke("\u007F") }
                return true
            }

            override fun sendKeyEvent(event: KeyEvent): Boolean {
                if (event.action == KeyEvent.ACTION_DOWN) handleKey(event)
                return true
            }

            override fun performEditorAction(actionCode: Int): Boolean {
                onBytes?.invoke("\r")
                return true
            }
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        handleKey(event)
        return true
    }

    private fun handleKey(event: KeyEvent) {
        val seq = when (event.keyCode) {
            KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> "\r"
            KeyEvent.KEYCODE_DEL -> "\u007F"
            KeyEvent.KEYCODE_FORWARD_DEL -> Keys.DEL
            KeyEvent.KEYCODE_TAB -> Keys.TAB
            KeyEvent.KEYCODE_ESCAPE -> Keys.ESC
            KeyEvent.KEYCODE_DPAD_UP -> Keys.UP
            KeyEvent.KEYCODE_DPAD_DOWN -> Keys.DOWN
            KeyEvent.KEYCODE_DPAD_LEFT -> Keys.LEFT
            KeyEvent.KEYCODE_DPAD_RIGHT -> Keys.RIGHT
            KeyEvent.KEYCODE_MOVE_HOME -> Keys.HOME
            KeyEvent.KEYCODE_MOVE_END -> Keys.END
            KeyEvent.KEYCODE_PAGE_UP -> Keys.PGUP
            KeyEvent.KEYCODE_PAGE_DOWN -> Keys.PGDN
            else -> {
                // A physical keyboard can hold Ctrl; a soft one uses the key row.
                if (event.isCtrlPressed) {
                    val c = event.getUnicodeChar(0).toChar()
                    if (c != '\u0000') Keys.ctrl(c) else ""
                } else {
                    val u = event.unicodeChar
                    if (u != 0) u.toChar().toString() else ""
                }
            }
        }
        if (seq.isNotEmpty()) onBytes?.invoke(seq)
    }

    override fun onDetachedFromWindow() {
        // Closing the terminal should take the keyboard with it; leaving it up covers
        // the feed with nothing left to type into.
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.hideSoftInputFromWindow(windowToken, 0)
        super.onDetachedFromWindow()
    }

    fun showKeyboard() {
        requestFocus()
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.showSoftInput(this, InputMethodManager.SHOW_IMPLICIT)
    }
}

/** Lets the grid (which owns the gestures) ask the input view for the keyboard. */
class TerminalInputController {
    internal var view: TerminalInputView? = null
    fun showKeyboard() { view?.showKeyboard() }
}

@Composable
fun rememberTerminalInputController(): TerminalInputController = remember { TerminalInputController() }

/**
 * The terminal's keyboard owner.
 *
 * It no longer sits over the grid: a View with a click listener consumes the whole
 * touch sequence at ACTION_DOWN, which is why horizontal scrolling and pinch-zoom on the
 * grid could never work while it was on top. Now it is a tiny focusable view beside the
 * grid, and the grid's own tap handler calls [TerminalInputController.showKeyboard].
 */
@Composable
fun TerminalInput(
    onSend: (String) -> Unit,
    modifier: Modifier = Modifier,
    controller: TerminalInputController? = null,
    showOnStart: Boolean = true,
) {
    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            TerminalInputView(ctx).also {
                it.onBytes = onSend
                controller?.view = it
                if (showOnStart) it.post { it.showKeyboard() }
            }
        },
        update = { it.onBytes = onSend; controller?.view = it },
    )
}
