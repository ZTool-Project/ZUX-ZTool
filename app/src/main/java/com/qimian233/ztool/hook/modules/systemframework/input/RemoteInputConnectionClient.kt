package com.qimian233.ztool.hook.modules.systemframework.input

import android.annotation.SuppressLint
import android.os.Binder
import android.os.IBinder
import android.view.inputmethod.ExtractedText
import android.view.inputmethod.ExtractedTextRequest
import com.qimian233.ztool.hook.base.ModuleLog
import java.lang.reflect.Constructor
import java.lang.reflect.Method
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

// See docs/research/input_connection_system_server_proxy.md.

/**
 * Outbound IRemoteInputConnection caller from system_server to target apps using
 * the wildcard session id header (`Integer.MIN_VALUE`).
 */
@SuppressLint("PrivateApi")
class RemoteInputConnectionClient private constructor(
    private val spec: InputConnectionWireSpec,
    private val wildcardHeader: Any,
    private val futureCtor: Constructor<*>,
    private val getTextBeforeCursorMethod: Method,
    private val getTextAfterCursorMethod: Method,
    private val getExtractedTextMethod: Method,
    private val beginBatchEditMethod: Method,
    private val endBatchEditMethod: Method,
    private val finishComposingTextMethod: Method,
    private val deleteSurroundingTextMethod: Method,
    private val setSelectionMethod: Method,
    private val commitTextMethod: Method,
    private val logger: ModuleLog
) {

    fun getTextBeforeCursor(realBinder: IBinder, length: Int, flags: Int): CharSequence? =
        awaitFutureCall(realBinder, "getTextBeforeCursor") { ic, future ->
            getTextBeforeCursorMethod.invoke(ic, wildcardHeader, length, flags, future)
        }

    fun getTextAfterCursor(realBinder: IBinder, length: Int, flags: Int): CharSequence? =
        awaitFutureCall(realBinder, "getTextAfterCursor") { ic, future ->
            getTextAfterCursorMethod.invoke(ic, wildcardHeader, length, flags, future)
        }

    fun getCursorPosSafely(realBinder: IBinder): Int {
        val extracted = awaitFutureCall<ExtractedText?>(realBinder, "getExtractedText") { ic, future ->
            getExtractedTextMethod.invoke(ic, wildcardHeader, ExtractedTextRequest(), 0, future)
        }
        if (extracted != null && extracted.selectionEnd >= 0) {
            return extracted.selectionEnd
        }
        val before = getTextBeforeCursor(realBinder, 100_000, 0)
        return before?.length ?: -1
    }

    /** Deletes `spanLength` chars before cursor inside a batch edit. */
    fun deletePromptSpan(realBinder: IBinder, spanLength: Int) {
        if (spanLength <= 0) return
        withClearedIdentity("deletePromptSpan") {
            val ic = spec.asRemoteInputConnection(realBinder)
            beginBatchEditMethod.invoke(ic, wildcardHeader)
            finishComposingTextMethod.invoke(ic, wildcardHeader)
            deleteSurroundingTextMethod.invoke(ic, wildcardHeader, spanLength, 0)
            endBatchEditMethod.invoke(ic, wildcardHeader)
        }
    }

    /** Replaces range `[startPos, currentPos]` with empty string, else deletes `fallbackLength`. */
    fun deleteRangeOrSurrounding(realBinder: IBinder, startPos: Int, fallbackLength: Int) {
        withClearedIdentity("deleteRangeOrSurrounding") {
            val ic = spec.asRemoteInputConnection(realBinder)
            beginBatchEditMethod.invoke(ic, wildcardHeader)
            finishComposingTextMethod.invoke(ic, wildcardHeader)
            val currentPos = if (startPos >= 0) getCursorPosSafely(realBinder) else -1
            if (startPos >= 0 && currentPos > startPos) {
                setSelectionMethod.invoke(ic, wildcardHeader, startPos, currentPos)
                commitTextMethod.invoke(ic, wildcardHeader, "", 1)
            } else if (fallbackLength > 0) {
                deleteSurroundingTextMethod.invoke(ic, wildcardHeader, fallbackLength, 0)
            }
            endBatchEditMethod.invoke(ic, wildcardHeader)
        }
    }

    /** Commits generated AI text directly onto the target app's real binder. */
    fun commitAiText(realBinder: IBinder, text: CharSequence) {
        if (text.isEmpty()) return
        withClearedIdentity("commitAiText") {
            val ic = spec.asRemoteInputConnection(realBinder)
            finishComposingTextMethod.invoke(ic, wildcardHeader)
            commitTextMethod.invoke(ic, wildcardHeader, text, 1)
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> awaitFutureCall(
        realBinder: IBinder,
        opName: String,
        invoker: (ic: Any, future: Any) -> Unit
    ): T? {
        if (!realBinder.isBinderAlive) return null
        val futureObj = runCatching { futureCtor.newInstance() }.getOrNull() ?: return null
        val completable = futureObj as? CompletableFuture<T> ?: return null
        val ident = Binder.clearCallingIdentity()
        return try {
            val ic = spec.asRemoteInputConnection(realBinder)
            invoker(ic, futureObj)
            completable.get(READ_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        } catch (t: Throwable) {
            logger.warn("$opName failed: ${t.javaClass.simpleName}")
            null
        } finally {
            Binder.restoreCallingIdentity(ident)
        }
    }

    private inline fun withClearedIdentity(opName: String, block: () -> Unit) {
        val ident = Binder.clearCallingIdentity()
        try {
            block()
        } catch (t: Throwable) {
            logger.warn("$opName failed: ${t.javaClass.simpleName}")
        } finally {
            Binder.restoreCallingIdentity(ident)
        }
    }

    companion object {
        private const val HEADER_CLASS =
            "com.android.internal.inputmethod.InputConnectionCommandHeader"
        private const val FUTURE_CLASS = "com.android.internal.infra.AndroidFuture"
        private const val READ_TIMEOUT_MS = 800L

        fun create(
            classLoader: ClassLoader,
            spec: InputConnectionWireSpec,
            logger: ModuleLog
        ): RemoteInputConnectionClient? = runCatching {
            val headerClass = classLoader.loadClass(HEADER_CLASS)
            val headerCtor = headerClass.getDeclaredConstructor(Int::class.javaPrimitiveType)
                .apply { isAccessible = true }
            val wildcardHeader = headerCtor.newInstance(Int.MIN_VALUE)

            val futureClass = classLoader.loadClass(FUTURE_CLASS)
            val futureCtor = futureClass.getDeclaredConstructor().apply { isAccessible = true }

            val ic = spec.remoteIcClass
            val intType = Int::class.javaPrimitiveType

            RemoteInputConnectionClient(
                spec = spec,
                wildcardHeader = wildcardHeader,
                futureCtor = futureCtor,
                getTextBeforeCursorMethod = ic.getMethod(
                    "getTextBeforeCursor", headerClass, intType, intType, futureClass
                ),
                getTextAfterCursorMethod = ic.getMethod(
                    "getTextAfterCursor", headerClass, intType, intType, futureClass
                ),
                getExtractedTextMethod = ic.getMethod(
                    "getExtractedText",
                    headerClass,
                    ExtractedTextRequest::class.java,
                    intType,
                    futureClass
                ),
                beginBatchEditMethod = ic.getMethod("beginBatchEdit", headerClass),
                endBatchEditMethod = ic.getMethod("endBatchEdit", headerClass),
                finishComposingTextMethod = ic.getMethod("finishComposingText", headerClass),
                deleteSurroundingTextMethod = ic.getMethod(
                    "deleteSurroundingText", headerClass, intType, intType
                ),
                setSelectionMethod = ic.getMethod("setSelection", headerClass, intType, intType),
                commitTextMethod = ic.getMethod(
                    "commitText", headerClass, CharSequence::class.java, intType
                ),
                logger = logger
            )
        }.onFailure {
            logger.error("Failed to initialize RemoteInputConnectionClient", it)
        }.getOrNull()
    }
}
