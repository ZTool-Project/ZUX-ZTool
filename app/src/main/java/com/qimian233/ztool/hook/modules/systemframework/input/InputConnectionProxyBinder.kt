package com.qimian233.ztool.hook.modules.systemframework.input

import android.os.Binder
import android.os.IBinder
import android.os.Parcel
import android.text.TextUtils
import android.view.inputmethod.EditorInfo
import com.qimian233.ztool.hook.base.ModuleLog

// See docs/research/input_connection_system_server_proxy.md.

/** Binder MITM proxy for IRemoteInputConnection handed to the active IME. */
class InputConnectionProxyBinder(
    val session: InputConnectionSession,
    private val spec: InputConnectionWireSpec,
    private val hub: InputConnectionProxyHub,
    private val logger: ModuleLog
) : Binder() {

    // Do not call attachInterface: Stub.asInterface must return a Proxy wrapping this Binder.

    override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
        if (code == INTERFACE_TRANSACTION) {
            reply?.writeString(InputConnectionWireSpec.IC_DESCRIPTOR)
            return true
        }
        if (code !in FIRST_CALL_TRANSACTION..LAST_CALL_TRANSACTION) {
            return super.onTransact(code, data, reply, flags)
        }

        val kind = commitKindForCode(code)
        if (kind == null || session.isPasswordField) {
            return InputConnectionWireSpec.forwardRaw(session.realBinder, code, data, reply, flags)
        }

        val interceptors = hub.activeInterceptors()
        if (interceptors.isEmpty()) {
            return InputConnectionWireSpec.forwardRaw(session.realBinder, code, data, reply, flags)
        }

        val prepared = try {
            prepareCommit(kind, data, interceptors)
        } catch (t: Throwable) {
            logger.warn("Commit parse/rewrite failed, passing through raw: ${t.javaClass.simpleName}")
            return InputConnectionWireSpec.forwardRaw(session.realBinder, code, data, reply, flags)
        } ?: return InputConnectionWireSpec.forwardRaw(session.realBinder, code, data, reply, flags)

        val rewrittenParcel = prepared.rewrittenParcel
        val transacted = if (rewrittenParcel != null) {
            try {
                InputConnectionWireSpec.transactForward(
                    session.realBinder, code, rewrittenParcel, reply, flags
                )
            } finally {
                rewrittenParcel.recycle()
            }
        } else {
            InputConnectionWireSpec.forwardRaw(session.realBinder, code, data, reply, flags)
        }

        notifyCommitted(interceptors, prepared.finalText, kind)
        return transacted
    }

    /** Updates session state when the app invalidates its ServedInputConnection. */
    fun onSessionInvalidated(editorInfo: EditorInfo?, sessionId: Int) {
        session.updateFromEditorInfo(editorInfo, sessionId)
        for (interceptor in hub.activeInterceptors()) {
            runCatching { interceptor.onSessionInvalidated(session) }
                .onFailure { logger.warn("Interceptor ${interceptor.id} invalidate failed: ${it.message}") }
        }
    }

    private fun commitKindForCode(code: Int): CommitKind? = when {
        code == spec.txCommitText -> CommitKind.COMMIT_TEXT
        spec.txCommitTextWithAttribute > 0 && code == spec.txCommitTextWithAttribute ->
            CommitKind.COMMIT_TEXT_WITH_ATTRIBUTE
        spec.txReplaceText > 0 && code == spec.txReplaceText -> CommitKind.REPLACE_TEXT
        else -> null
    }

    private fun prepareCommit(
        kind: CommitKind,
        data: Parcel,
        interceptors: List<InputConnectionInterceptor>
    ): PreparedCommit? {
        data.enforceInterface(InputConnectionWireSpec.IC_DESCRIPTOR)
        val hasHeader = data.readInt()
        val sessionId = if (hasHeader != 0) data.readInt() else null
        if (sessionId != null) {
            session.lastSessionId = sessionId
        }

        var replaceStart = 0
        var replaceEnd = 0
        if (kind == CommitKind.REPLACE_TEXT) {
            replaceStart = data.readInt()
            replaceEnd = data.readInt()
        }

        val hasText = data.readInt()
        val originalText = if (hasText != 0) {
            TextUtils.CHAR_SEQUENCE_CREATOR.createFromParcel(data)
        } else {
            null
        } ?: return null

        val newCursorPosition = data.readInt()
        val tailStart = data.dataPosition()
        val tailLength = data.dataSize() - tailStart

        var currentText: CharSequence = originalText
        var modified = false
        for (interceptor in interceptors) {
            val next = runCatching {
                interceptor.rewriteCommitText(session, currentText, kind)
            }.onFailure {
                logger.warn("Interceptor ${interceptor.id} rewrite failed: ${it.message}")
            }.getOrNull()
            if (next != null && next != currentText) {
                currentText = next
                modified = true
            }
        }

        if (!modified) {
            return PreparedCommit(finalText = currentText, rewrittenParcel = null)
        }

        val out = InputConnectionWireSpec.obtainParcel(session.realBinder)
        try {
            out.writeInterfaceToken(InputConnectionWireSpec.IC_DESCRIPTOR)
            if (sessionId != null) {
                out.writeInt(hasHeader)
                out.writeInt(sessionId)
            } else {
                out.writeInt(0)
            }
            if (kind == CommitKind.REPLACE_TEXT) {
                out.writeInt(replaceStart)
                out.writeInt(replaceEnd)
            }
            out.writeInt(1)
            TextUtils.writeToParcel(currentText, out, 0)
            out.writeInt(newCursorPosition)
            if (tailLength > 0) {
                out.appendFrom(data, tailStart, tailLength)
            }
            return PreparedCommit(finalText = currentText, rewrittenParcel = out)
        } catch (t: Throwable) {
            out.recycle()
            throw t
        }
    }

    private fun notifyCommitted(
        interceptors: List<InputConnectionInterceptor>,
        text: CharSequence,
        kind: CommitKind
    ) {
        for (interceptor in interceptors) {
            runCatching { interceptor.onTextCommitted(session, text, kind) }
                .onFailure { logger.warn("Interceptor ${interceptor.id} commit notify failed: ${it.message}") }
        }
    }

    private class PreparedCommit(
        val finalText: CharSequence,
        val rewrittenParcel: Parcel?
    )
}
