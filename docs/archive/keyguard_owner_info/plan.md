# SystemUI owner-info implementation plan

Scope: replace persistent owner-info writers with an instance-scoped SystemUI
cache and asynchronous fetcher. Preserve existing preference names and original
signature fallback. Add an opt-in device-owner override.

- [x] Implement cache, request coalescing, retry interval and stale-result guards.
- [x] Implement SystemUI reader hooks and completion refresh.
- [x] Remove obsolete Settings/framework writers and register the SystemUI hook.
- [x] Add the default-off preference, Repository/UiState/ViewModel/UI wiring,
  Chinese/English resources and searchable child entry.
- [x] Add meaningful cache/failure/concurrency/parser verification.
- [x] Update the research note and TODO with final behavior and limits.
- [x] Run assembleDebug, focused unit tests, string checker and diff checks.
- [x] Review staged changes, archive this completed plan and commit.

Validation: assembleDebug and the 11 OwnerInfoQuoteCacheTest cases passed;
SearchIndexConsistencyTest passed. Locale missing/extra counts are zero, with
no new unused resources (the existing search_title entry remains unused).
Device verification is outside this implementation run and remains documented
in docs/research/keyguard_owner_info_refresh.md.
