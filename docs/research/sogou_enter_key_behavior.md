# Sogou OEM IME Enter-key behavior

APK: `com.sohu.inputmethod.sogou.oem`
`1.0.android_pad_lenovo_2024.20261021193614` (versionCode 29876376; previously verified on `29615556`).

## Findings

### Hardware Enter

- `SogouIME` extends `coa`, which dispatches `onKeyDown` / `onKeyUp` through `yha` (`C4177yha.b()`).
- `IRa` is the hardware-key handler used by both input modes.
  Its `a(int, KeyEvent, int, boolean, boolean)` only forwards Enter (`66`) when
  `keyEvent.hasNoModifiers()` is true; it then calls `InterfaceC3977wra.f()`.
- `MF.f()` delegates to `WF.e()`, which calls `C3629tia.a()` to post message 59399 to the input
  worker. `C2531jja` handles that message by calling `C2509jaa.f()`.
- `C2509jaa.f()` either calls `performEditorAction(4)` (send) or `b('\n')`
  (raw Enter key event / newline), depending on its `a` flag.
- That flag is computed in `WF.a(boolean, int, int, int)` as
  `ZC.A().j() && ZC.A().O() && ZC.A().N() && ...`. `ZC` reads the send-mode
  and switch preferences from default shared preferences.
- Shift+Enter and Ctrl+Enter do **not** pass `hasNoModifiers()`, so `IRa`
  returns `false` and the event falls through to the target app. There is no
  code path that converts them into `performEditorAction(4)`.
- `C2993nua` is the combination-key listener. It maps Shift/Ctrl/Alt
  combinations through `C1449_sa`, whose default shortcut table only binds
  IME functions (soft/hard keyboard switch, Chinese/English switch, panel
  toggles, etc.). Enter is not bound to send.

### Soft-keyboard Enter

- `UU` / `WU` / `C1617bV` treat the key label `换行` as newline and
  otherwise call `performEditorAction(4)`.
- `C4142ySa.f(int)` uses the same split: `0` sends,
  non-zero commits `\r\n`.

## Conclusion

This APK contains no explicit "Ctrl+Enter / Shift+Enter -> send" interception.
Plain Enter can send when the OEM preference set above is enabled; modified
Enter is left to the host app, which may itself treat every Enter variant as
send. The hook therefore intercepts at `SogouIME.onKeyDown` / `onKeyUp` (resolved on
superclass `coa`), where the modifier state is still available, and performs the
send/newline action itself only for send editors (`IME_ACTION_SEND`, or a
WeChat/QQ/TIM text field whose action is not search/go/next/done).

## Hook mapping

| Mode | Enter | Shift/Ctrl+Enter |
| --- | --- | --- |
| `enter_send` | send | newline |
| `enter_newline` | newline | send |

Composing text is left to the IME: the hook probes Sogou's cached input
connection (`MF.V().z().j()` on default-package classes `"MF"` and `"Cha"`,
where `Cha.j()` checks active connection state and `Cha$b.e()` composing bounds;
note `Cha$b.j()` instead checks selection cache validity) and falls back to
`ComposingText` spans in `getExtractedText`.
