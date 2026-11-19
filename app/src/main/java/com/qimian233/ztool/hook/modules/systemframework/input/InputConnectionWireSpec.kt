package com.qimian233.ztool.hook.modules.systemframework.input

import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.Parcel
import java.lang.reflect.Method

// See docs/research/input_connection_system_server_proxy.md.

/** Reflected AIDL descriptors, Stub wrappers, and dynamic transaction codes. */
class InputConnectionWireSpec private constructor(
    val remoteIcClass: Class<*>,
    val inputMethodSessionClass: Class<*>,
    private val icAsInterface: Method,
    private val sessionAsInterface: Method,
    val txCommitText: Int,
    val txCommitTextWithAttribute: Int,
    val txReplaceText: Int,
    val txInvalidateInput: Int
) {
    /** Wraps `binder` in an IRemoteInputConnection Stub Proxy. */
    fun asRemoteInputConnection(binder: IBinder): Any = icAsInterface.invoke(null, binder)!!

    /** Wraps `binder` in an IInputMethodSession Stub Proxy. */
    fun asInputMethodSession(binder: IBinder): Any = sessionAsInterface.invoke(null, binder)!!

    companion object {
        const val IC_DESCRIPTOR = "com.android.internal.inputmethod.IRemoteInputConnection"
        const val SESSION_DESCRIPTOR = "com.android.internal.inputmethod.IInputMethodSession"

        private const val IC_STUB_CLASS = "$IC_DESCRIPTOR\$Stub"
        private const val SESSION_STUB_CLASS = "$SESSION_DESCRIPTOR\$Stub"

        /** Resolves hidden input AIDL metadata from `classLoader`, or returns null. */
        fun resolve(classLoader: ClassLoader): InputConnectionWireSpec? = runCatching {
            val remoteIcClass = classLoader.loadClass(IC_DESCRIPTOR)
            val icStubClass = classLoader.loadClass(IC_STUB_CLASS)
            val sessionClass = classLoader.loadClass(SESSION_DESCRIPTOR)
            val sessionStubClass = classLoader.loadClass(SESSION_STUB_CLASS)

            val icAsInterface = icStubClass
                .getDeclaredMethod("asInterface", IBinder::class.java)
                .apply { isAccessible = true }
            val sessionAsInterface = sessionStubClass
                .getDeclaredMethod("asInterface", IBinder::class.java)
                .apply { isAccessible = true }

            val txCommitText = readTxCode(icStubClass, "TRANSACTION_commitText", required = true)
            val txCommitTextAttr = readTxCode(
                icStubClass,
                "TRANSACTION_commitTextWithTextAttribute",
                required = false
            )
            val txReplaceText = readTxCode(icStubClass, "TRANSACTION_replaceText", required = false)
            val txInvalidateInput = readTxCode(
                sessionStubClass,
                "TRANSACTION_invalidateInput",
                required = true
            )

            if (txCommitText <= 0 || txInvalidateInput <= 0) {
                return null
            }

            InputConnectionWireSpec(
                remoteIcClass = remoteIcClass,
                inputMethodSessionClass = sessionClass,
                icAsInterface = icAsInterface,
                sessionAsInterface = sessionAsInterface,
                txCommitText = txCommitText,
                txCommitTextWithAttribute = txCommitTextAttr,
                txReplaceText = txReplaceText,
                txInvalidateInput = txInvalidateInput
            )
        }.getOrNull()

        /** Allocates a reply-compatible Parcel bound to `target` on API 33+. */
        fun obtainParcel(target: IBinder): Parcel =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                Parcel.obtain(target)
            } else {
                Parcel.obtain()
            }

        /** Forwards `data` to `target` under cleared Binder calling identity. */
        fun transactForward(
            target: IBinder,
            code: Int,
            data: Parcel,
            reply: Parcel?,
            flags: Int
        ): Boolean {
            val ident = Binder.clearCallingIdentity()
            return try {
                target.transact(code, data, reply, flags)
            } finally {
                Binder.restoreCallingIdentity(ident)
            }
        }

        /** Copies all raw bytes and embedded Binders of `data` to `target`. */
        fun forwardRaw(
            target: IBinder,
            code: Int,
            data: Parcel,
            reply: Parcel?,
            flags: Int
        ): Boolean {
            val forward = obtainParcel(target)
            return try {
                forward.appendFrom(data, 0, data.dataSize())
                transactForward(target, code, forward, reply, flags)
            } finally {
                forward.recycle()
            }
        }

        private fun readTxCode(stubClass: Class<*>, fieldName: String, required: Boolean): Int {
            return try {
                stubClass.getDeclaredField(fieldName).apply { isAccessible = true }.getInt(null)
            } catch (t: Throwable) {
                if (required) throw t
                -1
            }
        }
    }
}
