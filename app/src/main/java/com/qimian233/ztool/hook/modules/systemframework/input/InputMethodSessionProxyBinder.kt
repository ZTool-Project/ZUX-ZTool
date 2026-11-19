package com.qimian233.ztool.hook.modules.systemframework.input

import android.os.Binder
import android.os.IBinder
import android.os.Parcel
import android.view.inputmethod.EditorInfo
import com.qimian233.ztool.hook.base.ModuleLog

// See docs/research/input_connection_system_server_proxy.md.

/**
 * Binder MITM proxy for IInputMethodSession handed back to apps via InputBindResult.
 * Translates invalidateInput(EditorInfo, realIc, sessionId) to reference the IC proxy.
 */
class InputMethodSessionProxyBinder(
    private val realSessionBinder: IBinder,
    private val spec: InputConnectionWireSpec,
    private val hub: InputConnectionProxyHub,
    private val logger: ModuleLog
) : Binder() {

    // Do not call attachInterface: Stub.asInterface must return a Proxy wrapping this Binder.

    override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
        if (code == INTERFACE_TRANSACTION) {
            reply?.writeString(InputConnectionWireSpec.SESSION_DESCRIPTOR)
            return true
        }
        if (code !in FIRST_CALL_TRANSACTION..LAST_CALL_TRANSACTION) {
            return super.onTransact(code, data, reply, flags)
        }
        if (spec.txInvalidateInput <= 0 || code != spec.txInvalidateInput) {
            return InputConnectionWireSpec.forwardRaw(realSessionBinder, code, data, reply, flags)
        }

        val outParcel = try {
            prepareInvalidateParcel(data)
        } catch (t: Throwable) {
            logger.warn("invalidateInput translation failed, passing through raw: ${t.javaClass.simpleName}")
            return InputConnectionWireSpec.forwardRaw(realSessionBinder, code, data, reply, flags)
        } ?: return InputConnectionWireSpec.forwardRaw(realSessionBinder, code, data, reply, flags)

        return try {
            InputConnectionWireSpec.transactForward(
                realSessionBinder, code, outParcel, reply, flags
            )
        } finally {
            outParcel.recycle()
        }
    }

    private fun prepareInvalidateParcel(data: Parcel): Parcel? {
        data.enforceInterface(InputConnectionWireSpec.SESSION_DESCRIPTOR)
        val editorInfo = data.readTypedObject(EditorInfo.CREATOR)
        val realIcBinder = data.readStrongBinder()
        val sessionId = data.readInt()
        val tailStart = data.dataPosition()
        val tailLength = data.dataSize() - tailStart

        val proxyIcBinder = realIcBinder?.let { hub.findProxyForRealBinder(it) } ?: return null
        proxyIcBinder.onSessionInvalidated(editorInfo, sessionId)

        val out = InputConnectionWireSpec.obtainParcel(realSessionBinder)
        try {
            out.writeInterfaceToken(InputConnectionWireSpec.SESSION_DESCRIPTOR)
            out.writeTypedObject(editorInfo, 0)
            out.writeStrongBinder(proxyIcBinder)
            out.writeInt(sessionId)
            if (tailLength > 0) {
                out.appendFrom(data, tailStart, tailLength)
            }
            return out
        } catch (t: Throwable) {
            out.recycle()
            throw t
        }
    }
}
