# PackageInstaller A17 Fix & Unified OEM Hook Refactor Checklist

## Tasks
- [x] Step 1: Create root execution checklist
- [x] Step 2: Create `PackageInstallerPolicy.kt` (pure Kotlin state & policy model)
- [x] Step 3: Create `LenovoPackageInstallerHook.kt` (unified OEM owner for scan, warn, permissions, ad block, no-delete)
- [x] Step 4: Clean up and fix `PackageInstallerStyleHook.kt` (A17 `install_confirm_question_warning` fix, `isEnabled()` guards, namespaced IDs)
- [x] Step 5: Retire 5 old OEM hook classes and update `HookManager.kt`
- [x] Step 6: Add focused unit tests in `PackageInstallerPolicyTest.kt`
- [x] Step 7: Add research doc `docs/research/packageinstaller_refactor_audit.md`
- [x] Step 8: Archive checklist to `docs/archive/packageinstaller/`, run verification (`assembleDebug`, `PackageInstallerPolicyTest`, `SearchIndexConsistencyTest`, `git diff --check`), and commit scoped changes
