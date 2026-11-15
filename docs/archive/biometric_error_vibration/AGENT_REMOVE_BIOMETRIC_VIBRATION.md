# Remove biometric failure vibration feature

Scope: Remove the requested SystemUI feature from its hook, configuration,
frontend, search index, localized resources, and current feature documentation.

- [x] Remove hook implementation and registration.
- [x] Remove preference key, repository state/persistence, and ViewModel setter.
- [x] Remove UI switch/callbacks and search entry; update the misc summary.
- [x] Update both string locales, README, and the existing TODO item.
- [x] Verify string synchronization, search consistency, debug build, and diff.
- [x] Archive this completed plan, review staged changes, and commit.

Validation: `assembleDebug` and `SearchIndexConsistencyTest` passed. The string
checker reported locale missing/extra 0/0 and no new UNUSED entries (the existing
`search_title` entry remains). `git diff --check` passed. Former hook target notes
are retained in `docs/research/removed_biometric_error_vibration.md`.
