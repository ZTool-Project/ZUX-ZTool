# PLAN — split the media output dialog hook (center vs. album-art color) and bind the playing session

Goal: the dialog opened from the ZTool media-output tile must show the playing media and take its
colors from the album art, without requiring the user to enable the "center dialog" switch (Android
17 centers the dialog natively).

1. `data/keys/PreferenceKeys.kt`: add `MEDIA_OUTPUT_DIALOG_COLOR` (`media_output_dialog_color`,
   default `true`) and register it in `booleanKeys`.
2. New `hook/modules/systemui/qs/MediaOutputDialogColorHook.kt`:
   - wraps `MediaOutputDialogReceiver.onReceive` to mark a broadcast launch,
   - wraps `MediaOutputDialogManager.createAndShow`: for a broadcast launch inject the active media
     session package, force `includePlaybackAndAppMetadata = true` and `useSystemColors = false`.
3. `MediaOutputDialogCenterHook.kt`: keep gravity only; skip installation on Android 17 (API 37)
   because the 17 build centers the dialog itself.
4. `hook/base/HookManager.kt`: register the color hook next to the center hook.
5. `service/MediaOutputTileService.kt`: resolve the current playback package via
   `AudioManager.getActivePlaybackConfigurations()` and pass it as `package_name` (fallback: own
   package, which the color hook treats as "unspecified").
6. Frontend wiring for the new switch: `ControlCenterSettingsRepository`, `ControlCenterSettingsViewModel`
   (UiState + setter), `ControlCenterSettingsScreen` row, hide the center row on API 37+.
7. `search/SearchIndex.kt`: entry `control_center_media_output_dialog_color`.
8. Strings for both locales (`values`, `values-en-rUS`).
9. Docs: extend `docs/research/media_output_dialog_broadcast_android17.md` with the split and the
   broadcast-launch rule.
10. Verify: `.\gradlew.bat assembleDebug`, `SearchIndexConsistencyTest`, string sync check.
11. Commit.

Status: step 1 in progress.
