package com.qimian233.ztool.hook.modules.systemframework.input

import android.os.IBinder
import android.text.InputType
import android.view.inputmethod.EditorInfo

// See docs/research/input_connection_system_server_proxy.md.

/** Commit transaction variants intercepted on IRemoteInputConnection. */
enum class CommitKind {
    COMMIT_TEXT,
    COMMIT_TEXT_WITH_ATTRIBUTE,
    REPLACE_TEXT
}

/** Returns true when `inputType` identifies a text or numeric password field. */
fun isPasswordInputType(inputType: Int): Boolean {
    val inputClass = inputType and InputType.TYPE_MASK_CLASS
    val variation = inputType and InputType.TYPE_MASK_VARIATION
    return when (inputClass) {
        InputType.TYPE_CLASS_TEXT -> variation == InputType.TYPE_TEXT_VARIATION_PASSWORD ||
            variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD ||
            variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD
        InputType.TYPE_CLASS_NUMBER -> variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD
        else -> false
    }
}

/** Mutable metadata associated with a proxied IRemoteInputConnection binder. */
class InputConnectionSession(
    val realBinder: IBinder,
    editorInfo: EditorInfo?
) {
    @Volatile
    var packageName: String? = editorInfo?.packageName

    @Volatile
    var inputType: Int = editorInfo?.inputType ?: 0

    @Volatile
    var isPasswordField: Boolean = editorInfo != null && isPasswordInputType(editorInfo.inputType)

    @Volatile
    var lastSessionId: Int = 0

    /** Refreshes editor attributes and optionally the latest IMMS session id. */
    fun updateFromEditorInfo(editorInfo: EditorInfo?, sessionId: Int? = null) {
        if (editorInfo != null) {
            packageName = editorInfo.packageName
            inputType = editorInfo.inputType
            isPasswordField = isPasswordInputType(editorInfo.inputType)
        }
        if (sessionId != null) {
            lastSessionId = sessionId
        }
    }
}

/** Consumer hook registered with InputConnectionProxyHub to rewrite or observe commits. */
interface InputConnectionInterceptor {
    val id: String

    val priority: Int
        get() = 0

    /** Returns true while this interceptor wants sessions proxied and inspected. */
    fun isActive(): Boolean

    fun onSessionStarted(session: InputConnectionSession) = Unit

    fun onSessionInvalidated(session: InputConnectionSession) = Unit

    /** Returns replacement text, or null to keep `text` unchanged. */
    fun rewriteCommitText(
        session: InputConnectionSession,
        text: CharSequence,
        kind: CommitKind
    ): CharSequence? = null

    fun onTextCommitted(
        session: InputConnectionSession,
        text: CharSequence,
        kind: CommitKind
    ) = Unit
}
