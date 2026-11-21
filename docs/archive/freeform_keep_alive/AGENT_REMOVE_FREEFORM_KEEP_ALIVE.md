# Remove freeform background keep-alive feature

Scope: Remove the requested freeform background keep-alive (`小窗后台保活`) feature from its hooks, preference keys, repositories, ViewModels, UI, search index, localized resources, and README.

- [x] Preserve reverse-engineering findings in `docs/research/removed_freeform_keep_alive.md`.
- [x] Delete hook implementations (`FreeformKeepAliveLauncherHook`, `FreeformKeepAliveSystemHook`) and unregister them in `HookManager`.
- [x] Remove preference keys (`FREEFORM_KEEP_ALIVE_ENABLED`, `FREEFORM_KEEP_ALIVE_PACKAGES`), repository state/persistence, and ViewModel setters/state (`LauncherSettings*`, `FreeformHub*`).
- [x] Remove UI switch/package-picker row/callbacks in `FreeformHubSettingsScreen` and entries in `SearchIndex`.
- [x] Remove localized strings in both `values/` and `values-en-rUS/`, update `README.md`, and tick `TODOS.md` L189.
- [x] Verify grep cleanup, `check_unused_strings.py`, `SearchIndexConsistencyTest`, `ZToolIconsContractTest`, `assembleDebug`, and `git diff --check`.
- [x] Archive this plan under `docs/archive/freeform_keep_alive/`, stage only scoped changes without disturbing unrelated dirty files, and commit.

Validation: `assembleDebug`, `SearchIndexConsistencyTest` (2/2), and `ZToolIconsContractTest` (2/2) passed. `tools/check_unused_strings.py` reported `declared: 1406`, `locale values-en-rUS: missing 0, extra 0`, and no new UNUSED entries (`search_title` only). `git grep` confirmed zero remaining code references under `app/src/`. Former hook target notes are retained in `docs/research/removed_freeform_keep_alive.md`.
