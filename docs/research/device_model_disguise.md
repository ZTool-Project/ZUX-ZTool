# Game tool device-model disguise

Reverse-engineering notes for `hook/modules/gametool/DeviceModelDisguiseHook.kt` and
`data/gametool/DeviceModelCatalog.kt`. Host: `com.zui.game.service`
(`/system/priv-app/ZuiGameHelper/ZuiGameHelper.apk`, single APK, classes4.dex).

## Host shape (`com.zui.util.DeviceUtils`)

- 34 `static final String` model codes plus one unused `persist.debug.game.testmodel`
  property constant (zero xrefs in the APK — not a supported debug switch).
- Every model decision funnels through one comparison:

  `isXxx()` (40+ getters) -> `isModel(String)` -> `getBuildModel()` -> `Lazy { Build.MODEL }`

  `isModel` is `getBuildModel`'s only caller, so hooking it covers every getter,
  including getters added by later ROMs.
- Non-model boolean getters (7): `isRow`, `isPad`, `isPadLgsi`, `isPhone`,
  `isLTEDevice`, `isLGSIV`, `isTouchAdjustPlatform`. They read `ro.config.*`
  properties and are *not* affected by the disguise.
- Row variants (`isEldenRow`, `isWuJiRow`, ...) are `isModel(code) && isRow()`, so
  the PRC/ROW split keeps following the real system property.

## Consumers

`com.zui.game.service.di.Settings` wraps most getters in `Lazy`, but
`isOneStepDevice`, `isLegionDevice`, `isSvkDevice`, `isProGTDevice`,
`isLargeScreenDevice` and `isInception` are computed eagerly in its constructor.
`FeaturesBaseOnRomKt.<clinit>` then picks a whole `RomFeatures` table from those
booleans, which is why the hook has to be installed at `handleLoadPackage` (before
any host code) and why changing the model flips feature sets, not just a label.

## Reading the model table (app side)

DexKit cannot read the codes: `FieldData` exposes name/type/modifiers only, while
the values live in the dex `static_values` section. Loading the host class and
reflecting its fields reads them directly, independent of how R8 laid out
`<clinit>` or whether it inlined the literals into the getters:

```kotlin
PathClassLoader(apkPath, moduleClassLoader)
Class.forName("com.zui.util.DeviceUtils", false, loader).declaredFields
```

Verified on TB710FU (Android 17): 35 static-final String fields, values correct.
`<clinit>` only runs `INSTANCE = new DeviceUtils()` and builds one `Lazy`, so
initialization is side-effect free. Reading the APK needs no extra privilege:
0644 `system_file`, and the same file is already read by `SystemUiDexIndexer`.

## Known gaps

- `Build.MODEL` is read directly by `highlight/util/SvkSlideUtils.isSvkSlideDisable`
  (blacklist match) and `ui/MainActivity`; both stay on the real model. No impact
  observed.
- The disguise never touches `ro.config.lgsi.region`, `ro.config.lgsi.device.type`
  or `ro.config.lgsi.device.nettype`, so ROW/PRC, pad/phone and LTE detection keep
  describing the actual device.
- Fallback when the host changes shape: the hook logs and leaves the model alone;
  the module ships `TB322FC` as the default selection.
