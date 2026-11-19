# System-Server `IRemoteInputConnection` Proxy Architecture

Research notes behind `hook/modules/systemframework/input/*` and the migration of `HalfWidthPunctHook` (Phase 1), plus the blueprint for migrating `AiInputExpand` without process-wide injection (Phase 2).

Verified against ZUX OS / Android 17 `services.jar` and `framework.jar`.

## 1. Full-Width Punctuation Provenance

User reports confirmed that non-OEM third-party IMEs also produce full-width symbols on Chinese input, raising the question of whether Android / ZUI framework layers rewrite punctuation centrally.

### Audited classes (`services.jar` & `framework.jar`)

- `services.jar`:
  - `com.android.server.inputmethod.InputMethodManagerService`
  - `com.android.server.inputmethod.ZuiInputMethodManagerService`
  - `com.android.server.inputmethod.InputMethodManagerServiceDelegate`
  - `com.android.server.input.InputManagerService`, `InputManagerServiceDelegate`
  - `com.android.server.input.KeyboardLayoutManager`
  - `KeyboardZuiKeyInputPolicy`, `KeyboardInputManager`, `KeyboardKeyInputManager`, `KeyInputPolicyBase`, `KeyboardInputUtils`, `OlympiaKeyboardPolicy`
  - `ZuiHandWritingService`, `ZuiPhoneWindowManager`, `LenovoKeyboardManagerService`, `ZuiInputManagerInternal`
- `framework.jar`:
  - `android.view.inputmethod.RemoteInputConnectionImpl`, `BaseInputConnection`, `EditableInputConnection`, `InputMethodManager`
  - `android.inputmethodservice.RemoteInputConnection`, `IRemoteInputConnectionInvoker`, `InputMethodService`, `MotoInputMethodServiceImpl`
  - `android.widget.TextView`, `android.widget.Editor`, `android.text.method.QwertyKeyListener`
  - `android.view.KeyCharacterMap`, `KeyEvent`, `ViewRootImpl`, `ViewRootImplDelegate`
  - `zui.widget.ZuiTextWatcher` (only dispatches Ctrl+Enter send-button clicks in WeChat/QQ/DingTalk/Weibo)

### Finding

Neither `services.jar` nor `framework.jar` contains any ASCII-to-fullwidth punctuation conversion (`0xFEE0` offset / fullwidth lookup tables). Each Chinese IME (Sogou OEM, Gboard, WeType, Baidu, Lenovo `HandwritingIme`, etc.) independently maps hardware/soft-keyboard ASCII punctuation into full-width characters in Chinese mode before calling `IRemoteInputConnection`.

### Architectural implication

`system_server` hands the app's `IRemoteInputConnection` Binder handle to every IME during `startInput`. Placing a Binder man-in-the-middle (MITM) proxy inside `system_server` sits strictly **downstream of all IMEs** and **upstream of all target apps**:

- Zero injection into IME processes (no OEM vs. third-party IME breakage).
- Zero injection into third-party apps (banking/games/messaging apps remain untouched).
- Centralized password-field and package filtering inside `system_server`.

## 2. Dual-Channel `system_server` Binder MITM Architecture (API 33+)

Proxying only `startInput` breaks session synchronization if the target app invalidates its `InputConnection` (`TextView.setText`, Compose `TextField` state resets, chat composer clearing after Send). Both directions must be bridged together inside `system_server`.

### Channel A: Downstream (`IME -> App`) via `IInputMethodInvoker.startInput`

- **Hook target**: `com.android.server.inputmethod.IInputMethodInvoker.startInput(...)` (unobfuscated AOSP helper; sole caller in `services.jar` is `InputMethodManagerService.attachNewInputLocked`).
- **Parameter resolution**: Resolve `IRemoteInputConnection` and `EditorInfo` parameter indices dynamically from `startInput` parameter types so Android 13–17 signature variations are tolerated.
- **Why here (and not `UserData.mCurInputConnection`)**:
  - `InputMethodManagerService` never compares `UserData.mCurInputConnection` by Binder identity.
  - Leaving `UserData.mCurInputConnection` untouched preserves system-side `requestAiCursorUpdate()`.
  - `EditorInfo` is directly available at handoff time (`packageName`, `inputType`, `imeOptions`).
- **Bypass rules**: Skip wrapping when `remoteInputConnection == null` or `realBinder is Binder` (local in-process binders belong to `system_server` windows/dialogs rather than app processes).

### Channel B: Upstream Session (`App -> IME`) via `InputBindResult.<init>`

Why Channel B is mandatory:

1. When an app resets or replaces text programmatically, `InputMethodManager.invalidateInput(view)` -> `RemoteInputConnectionImpl.scheduleInvalidateInput` -> `InputMethodManager.doInvalidateInput` increments `sessionId` and sends `IInputMethodSessionInvoker.invalidateInput(editorInfo, mServedInputConnection, sessionId)` **directly from the app process to the IME process** over the app-held `IInputMethodSession` Binder.
2. Notice `mServedInputConnection` inside the app is the **real** `RemoteInputConnectionImpl` (`realIcBinder`), NOT the `system_server` proxy (`proxyIcBinder`).
3. Inside the IME process, `InputMethodService.InputMethodSessionImpl.invalidateInputInternal(EditorInfo, IRemoteInputConnection, int)` calls `RemoteInputConnection.isSameConnection(inputConnection)`, which checks Binder identity (`mInvoker.isSameConnection` -> `current.asBinder() == passed.asBinder()`).
4. If the IME holds `proxyIcBinder` from `startInput` while the app sends `realIcBinder` in `invalidateInput`, `isSameConnection` returns `false`, logging `"ignoring invalidateInput() due to context mismatch."` and leaving the IME stuck on an outdated `sessionId` — after which every `commitText` fails `checkSessionId(header)` and text input silently stops working!

Solution:

- **Hook target**: `com.android.internal.inputmethod.InputBindResult` constructor containing an `IInputMethodSession` parameter.
- Wrap non-local `IInputMethodSession` (`!(realSessionBinder is Binder)`) with `InputMethodSessionProxyBinder`.
- On `TRANSACTION_invalidateInput`, read `(EditorInfo, IBinder realIcBinder, int sessionId)`:
  - Look up `proxyIcBinder` in `icProxyMap[realIcBinder]`.
  - Update `proxyIcBinder.session` (`updateFromEditorInfo(editorInfo, sessionId)`) and notify active interceptors (`onSessionInvalidated`).
  - Repack the `Parcel` replacing `realIcBinder` with `proxyIcBinder` and forward to `realSessionBinder`.
  - Inside the IME, `isSameConnection(proxyIcBinder)` returns `true`, advancing the IME's `sessionId` to match `RemoteInputConnectionImpl`.
- **Fail-safe atomic installation**: Install both `startInput` and `InputBindResult.<init>` together; if installing the second hook throws, immediately unhook the first so the system never runs half-proxied.

### Zero-Stub Binder Proxy Construction

Both `InputConnectionProxyBinder` and `InputMethodSessionProxyBinder` subclass `android.os.Binder` **without** calling `attachInterface(...)`:

1. `queryLocalInterface(descriptor)` returns `null`.
2. Reflected `IRemoteInputConnection$Stub.asInterface(proxyBinder)` / `IInputMethodSession$Stub.asInterface(proxySessionBinder)` therefore constructs a genuine `Stub$Proxy` whose `asBinder()` returns the proxy `Binder`.
3. When AIDL (`StartInputParams` / `InputBindResult`) marshals that `IInterface`, Android Binder automatically creates a remote `BinderProxy` in the recipient process targeting `system_server`.

## 3. AIDL Wire Formats (Verified from A17 `framework.jar`)

Transaction codes are never hardcoded in runtime code; they are reflected dynamically from `Stub.TRANSACTION_*` static `int` fields (`-1` when absent on older API builds).

### `com.android.internal.inputmethod.IRemoteInputConnection`

Header object `com.android.internal.inputmethod.InputConnectionCommandHeader` has a single serialized field: `int mSessionId`.

- **`TRANSACTION_commitText`** (`0xa` on A17, `oneway`):
  1. `interfaceToken` (`"com.android.internal.inputmethod.IRemoteInputConnection"`)
  2. `InputConnectionCommandHeader`: `int hasHeader` (`1`/`0`), if `!= 0` -> `int mSessionId`
  3. `CharSequence text`: `int hasText` (`1`/`0`), if `!= 0` -> `TextUtils.writeToParcel(text, dest, 0)` (read via `TextUtils.CHAR_SEQUENCE_CREATOR.createFromParcel(data)`)
  4. `int newCursorPosition`
- **`TRANSACTION_commitTextWithTextAttribute`** (`0xb` on A17, API 33+, `oneway`):
  - Fields 1–4 identical to `commitText`, plus:
  5. `TextAttribute`: `writeTypedObject(textAttribute, 0)`
  - Audited `android.view.inputmethod.TextAttribute`: contains only `List<String> mTextConversionSuggestions`, `PersistableBundle mExtras`, and `boolean mTextSuggestionSelected` (guarded by `Flags.a11yTextChangeTypesApi()`). It contains zero `IBinder` or `FileDescriptor` handles, so copying trailing bytes verbatim via `out.appendFrom(data, tailStart, data.dataSize() - tailStart)` is exact and preserves hidden-API / ROM-flag fields without deserializing `TextAttribute`.
- **`TRANSACTION_replaceText`** (`0x22` on A17, API 35+, `oneway`):
  1. `interfaceToken`
  2. `InputConnectionCommandHeader` (`hasHeader` + optional `mSessionId`)
  3. `int start`, `int end`
  4. `CharSequence text` (`hasText` + `TextUtils`)
  5. `int newCursorPosition`
  6. `TextAttribute` tail (raw copy via `appendFrom`)

### `com.android.internal.inputmethod.IInputMethodSession`

- **`TRANSACTION_invalidateInput`** (`0xb` on A17, `oneway`):
  1. `interfaceToken` (`"com.android.internal.inputmethod.IInputMethodSession"`)
  2. `EditorInfo`: `readTypedObject(EditorInfo.CREATOR)` / `writeTypedObject(editorInfo, 0)`
  3. `IRemoteInputConnection`: `readStrongBinder()` / `writeStrongBinder(...)`
  4. `int sessionId`
- **`TRANSACTION_finishInput`** (`0xa` on A17, `oneway`), **`TRANSACTION_updateSelection`** (`0x2` on A17, `oneway`).

## 4. Security, Identity, Lifecycle & Fallback Semantics

- **Password field exclusion**: `isPasswordInputType(editorInfo.inputType)` checks `TYPE_MASK_CLASS` + `TYPE_MASK_VARIATION` for `TYPE_TEXT_VARIATION_PASSWORD`, `TYPE_TEXT_VARIATION_VISIBLE_PASSWORD`, `TYPE_TEXT_VARIATION_WEB_PASSWORD`, and `TYPE_NUMBER_VARIATION_PASSWORD`. Password sessions bypass all interception/observation via raw `Parcel.appendFrom` passthrough. Both `startInput` and `invalidateInput` update `isPasswordField`.
- **Transparent passthrough**: Unhandled transaction codes (`FIRST_CALL_TRANSACTION..LAST_CALL_TRANSACTION`) and unchanged payloads copy all bytes + Binder object offsets using `forward.appendFrom(data, 0, data.dataSize())` inside `Binder.clearCallingIdentity()` / `Binder.restoreCallingIdentity()`, then recycle the temporary `Parcel`. Meta-transactions (e.g., `IBinder.INTERFACE_TRANSACTION`) are answered locally or delegated to `super.onTransact`.
- **Exception isolation**: Rewriting/parsing failures log a warning and immediately fall back to `forwardRaw(...)` so user typing cannot be blocked. `RemoteException` from `target.transact(...)` is never swallowed.
- **`commitContent` UID caveat**: `RemoteInputConnectionImpl.commitContent` reads `Binder.getCallingUid()` to verify URI grant permissions (`IUriGrantsManager.checkGrantUriPermission_ignoreNonSystem(imeUid, ...)`). Proxied calls arrive in the app process with `Process.SYSTEM_UID (1000)`, which passes `checkGrantUriPermissionUnlocked` for user 0 (multi-user / work-profile URIs without an explicit userId in the URI use `UserHandle.getUserId(imeUid)`, so `userId 0` is checked if proxied). Text commits do not inspect `Binder.getCallingUid()`.
- **Memory & hot reload**: `WeakHashMap<IBinder, WeakReference<ProxyBinder>>` keyed by the real `BinderProxy` prevents leaking dead `InputConnection` instances. `HookManager.reinitializeForHotReload` loads a fresh `InputConnectionProxyHub` instance per module ClassLoader generation and replaces hooks atomically using stable hook IDs (`imms_input_connection_proxy`, `imms_input_bind_result_session_proxy`) with `ExceptionMode.PROTECTIVE`.
- **API 37 / `Parcel.obtain(IBinder)`**: On API 33+, `Parcel.obtain(target)` binds the target Binder's RPC/object traits cleanly; pre-API 33 falls back to `Parcel.obtain()` (the proxy itself gates on API 33+ anyway since `InputConnectionCommandHeader` was introduced in Android 13 / API 33).

## 5. Phase 2 Blueprint: Migrating `AiInputExpand` (Future Work)

Findings from `framework.jar` (`RemoteInputConnectionImpl`, `LgsiFeatures`, `InputMethodManager`) and `services.jar` (`ZuiInputMethodManagerService`, `InputMethodManagerServiceDelegate`) confirm `AiInputExpand` can also migrate into `system_server`:

1. **`LgsiFeatures` on Android 17**:
   - Only `LgsiFeatures.enabled(String)` exists (no `enabled(int)` overload); PRC ROMs already default `"AIInput"`, `"ZuiIMMS"`, and `"ZuiIms"` to `true`.
2. **Wildcard `mSessionId` bypass**:
   - `RemoteInputConnectionImpl.checkSessionId(InputConnectionCommandHeader)` explicitly returns `true` whenever `header.mSessionId == Integer.MIN_VALUE` (`0x80000000`).
3. **`RemoteInputConnectionImpl` command detection limits in current ROM**:
   - `lambda$commitText$17` only runs inside 2-arg `commitText(...)` (not `commitTextWithTextAttribute`), checks `AI_COMMAND_SIGN_ARRAYS` (`{"&&", "＆＆", "&＆", "＆&"}`) + `AI_COMMAND_SIGN_SINGLE_ARRAYS` (`{"&", "＆"}`), and hardcodes trigger lengths `2` / `4` and `"[＆&\\s]"`.
4. **In-`system_server` execution path**:
   - `InputConnectionProxyHub` already exposes `onTextCommitted(...)`, `onSessionInvalidated(...)`, `getActiveSession()` (`realBinder` + `packageName` + `lastSessionId`), and `InputMethodSessionProxyBinder` (`TRANSACTION_updateSelection`, `TRANSACTION_finishInput`) for cursor/session tracking.
   - `InputMethodManagerServiceDelegate.sInstance` provides `mZuiService` (`ZuiInputMethodManagerService.requestAITaskGenerate(cmd, before, after, remoteCallback, callingUid)`).
   - App compatibility lists (`BLACKLIST`: `com.zuxos.cadlauncher`; `DOCUMENTLIST`: `cn.wps.moffice_eng`, `com.microsoft.office.word`, `com.zui.notes`, `com.microsoft.office.outlook`; `SPECIALLIST`: `com.tencent.mm`, `com.google.android.keep`, `com.whatsapp`; `WPS_THROTTLE_LIST`: `cn.wps.moffice_eng` @ 150ms) and delete/commit operations can be driven directly against `realBinder` using `InputConnectionCommandHeader(Integer.MIN_VALUE)`, completely eliminating `ScopeKeys.UNRESTRICTED` from `AiInputExpand`.
