# Network speed refactor execution

Scope: implement docs/research/network_speed_refactor_plan.md with the existing
host class names, method signatures and reflection starting classes preserved.

- [x] Implement pure rate, option, formatting and generation policies.
- [x] Install one network-speed owner with preference-selected hook groups.
- [x] Preserve native messages and sampling gates; share frames with text and visibility.
- [x] Replace old registrations and retire the four feature-specific Hooks.
- [x] Broaden the existing offline Handler index without changing its namespace.
- [x] Run focused unit tests and assembleDebug; review reflection and comment contracts.
- [x] Record validation limits and update the research plan.
- [x] Archive this checklist and commit the scoped changes.

Device testing is conditional on a connected test device. Build dev for any
LSPosed device verification. Do not commit unrelated TODOS.md edits.

Result: assembleDebug passed and all 14 NetworkSpeedPolicyTest cases passed.
Device behavior remains unverified; no APK was installed.
