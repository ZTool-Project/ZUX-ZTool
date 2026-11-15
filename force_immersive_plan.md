# Force Immersive Mode Repair

- [x] Inspect SystemUI in Jadx and the matching Android 17 services.jar.
- [x] Repair bar control ownership and SystemUI attributes using the existing key.
- [x] Verify Debug/Dev builds and transient/insets policy unit tests.
- [x] Wire a searchable frontend toggle and both required scopes.
- [x] Record findings and device verification steps in docs/research.
- [x] Run required builds, search consistency, string checks, and diff review.
- [ ] Archive this completed plan and commit scoped changes.

Expected scope: the two immersive hooks, HookManager, policy tests,
Framework Repository/ViewModel/screen, FeatureCatalog, SearchIndex, both string locales,
research notes, and this plan. Preserve existing preference and manifest contracts.
