# ZUI Theme Font vs. Compose `FontFamily.Default`

Verified on TB710FU, Android 17, ZUXOS 2.5.10.108 (2026-11-24).

## Symptom

A ZUI theme font (e.g. `阿里妈妈圆体`) applied through the ZUI theme service shows up
in system apps (Settings, Launcher, SystemUI) but not in the ZTool Compose frontend,
which keeps rendering Roboto + NotoSansCJK.

The font file is mapped into the ZTool process (`/proc/<pid>/maps` contains
`/data/system/theme/fonts/<name>.ttf`), so loading and SELinux are not the problem.

## System side

- Props: `persist.sys.zui.theme.fontname`, `persist.sys.zui.theme.fontpath`.
- RRO `com.android.theme.font.customizesource` (`/product/overlay/FontCustomizeSource`)
  overrides `config_bodyFontFamily`, `config_bodyFontFamilyMedium`,
  `config_headlineFontFamily`, `config_headlineFontFamilyMedium` to `customize-family`.
  The ZUI default variant `com.android.theme.font.RookeryRegular` uses `Rookery-Regular`
  (CJK fallback `HYQiHei_60S/70S/80S` for 400/500/700, see `/product/etc/fonts_customization.xml`).
- `ActivityThread.handleBindApplication` calls `zui.themes.ThemeConfig.onActivityLaunch`
  when `LgsiFeatures.enabled("FontSwitch")`, which calls `Typeface.setThemeFont()`.
- `Typeface.setThemeFont()` creates the typeface from the font path, puts it into
  `sSystemFontMap["customize-family"]`, clears the style/weight caches and calls
  `Typeface.reinit("customize-family")`.
- `reinit` updates `sDefaultTypeface`, `sDefaultFontFamily`, `sDefaults` and registers the
  generic family natively, and tries to refresh the static `DEFAULT`, `DEFAULT_BOLD`,
  `SANS_SERIF`, `SERIF`, `MONOSPACE` via `updatePendingTypeface(...)`.
- Android 17 static typefaces are lazy placeholders (`mPendingTypeface`).
  `updatePendingTypeface` does `mPendingTypeface.compareAndSet(null, tf)`; the placeholder
  was already completed earlier with the stock `sans-serif`, so the CAS fails and
  **`Typeface.DEFAULT` / `DEFAULT_BOLD` stay on the stock font**.
- `Typeface.create(String, int)` goes through `getSystemDefaultTypeface` →
  `getThemedTypeface` (when `FontSwitch` is enabled), which maps `sans-serif`,
  `sans-serif-light`, `sans-serif-medium`, `sans-serif-black` to
  `sSystemFontMap[sDefaultFontFamily]` — i.e. the theme font. Views use this path, which
  is why system apps are themed.

## Compose side

`androidx.compose.ui.text.font.PlatformTypefacesApi28.createAndroidTypefaceApi28`:

- `FontFamily.Default` (no generic name): returns `Typeface.DEFAULT` / `DEFAULT_BOLD`
  directly, or `Typeface.create(Typeface.DEFAULT, weight, italic)` → stale stock font.
- `FontFamily.SansSerif`: `Typeface.create("sans-serif", NORMAL)` → `getThemedTypeface`
  → theme font, then `Typeface.create(tf, weight, italic)` for the requested weight.

## Decision

`ZToolDefaultFontFamily` (`ui/theme/ZToolTypography.kt`) is `FontFamily.SansSerif`.
Do not change it back to `FontFamily.Default`.

## Runtime font switch (hot switch)

- A ZUI font change is delivered as a configuration change with the ZUI-specific diff bit
  `0x80000`. `ConfigurationController.handleConfigurationChangedInner` calls
  `zui.themes.ThemeConfig.handleConfigurationChanged`, which on that bit runs
  `Canvas.freeCaches()`, `Canvas.freeTextLayoutCaches()` and `Typeface.setThemeFont()`
  in the app process. `MainActivity` does not declare that bit, so it is relaunched in
  the same process (`wm_relaunch_resume_activity ... 80000`).
- Compose's default `FontFamily.Resolver` uses the process-wide
  `GlobalTypefaceRequestCache` (`LruCache(16)` keyed by family/weight/style/synthesis).
  The recreated composition therefore still gets the old `Typeface` for
  `FontFamily.SansSerif`; only a process restart cleared it.
- Fix: `rememberThemeFontFamilyResolver()` provides `LocalFontFamilyResolver` with
  `emptyCacheFontFamilyResolver(context)` (own cache, `@InternalTextApi`), re-created when
  `Typeface.create("sans-serif", NORMAL)` returns a different instance, i.e. after
  `setThemeFont()` replaced `sSystemFontMap["customize-family"]`. It is keyed on
  `LocalConfiguration` so the in-place `onConfigurationChanged` path is covered too.
- Rejected: `FontFamily(Typeface)` (`LoadedFontFamily`) changes the cache key, but
  `AndroidTypefaceWrapper.getNativeTypeface` ignores the requested weight.
- Verified 2026-11-24: switching ZukChinese → custom → ZukChinese with ZTool in the
  foreground updates the UI each time without a process restart.
- Upgrade risk: `emptyCacheFontFamilyResolver` is internal API; re-check after Compose
  `ui-text` upgrades.

## Weight note

The tested theme TTF is a static font (`OS/2.usWeightClass = 500`, no `fvar`), so heavier
weights are synthesized from the single face by the platform.
