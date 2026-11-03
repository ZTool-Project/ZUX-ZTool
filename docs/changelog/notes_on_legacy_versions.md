# Notes on the legacy versions (v0.1.0 – v0.13.0)

The `v0.*.0` files in this directory record releases made **before** the `v1.0.0` update model.
They are history, not release notes for an existing tag: under the old model a build named itself
`Beta/yyMMdd` (or `Demo/yyMMdd`), so no `v0.x.0` version ever appeared in a Git tag, in
`UpdateCheck.json`, or on the app's About page.

**In short: `v0.x.0` are the old versions that used the old update model.**

## What the old update model was

| Aspect | Old model (the `v0.x.0` era) | Current model (`v1.0.0` and later) |
|---|---|---|
| Version name | `Beta/261027`, `Demo/251127` — derived from the build date | `vMAJOR.MINOR.PATCH[-channel.N]`, equal to the release tag |
| Version code | `git rev-list --count HEAD`, published as `(cNNNN)` in the changelog header | the same commit count, carried in the release name `v1.0.0 (c2471)` |
| Changelog source | append-only root `更新日志.txt`; CI extracted the newest block with `awk` | one file per release in this directory, used verbatim as the release body |
| Update discovery | `UpdateCheck.json` in the old repository, fetched over `raw.githubusercontent.com`; `newVersionName` / `newVersionCode` / `whatNew` / `url` edited by hand around releases | GitHub Releases Atom feed; tag, name, counter and body are all CI-generated |
| Release tags | `20261027-ZTool_Beta`, `ZUX-ZToolDemo251127(8)`, … — not semver | `v1.0.0`, `v1.0.0-beta.2`, `v1.1.0-canary.20261120` |
| Where users read it | the in-app update card, rendered from the JSON `whatNew` field | the release page, plus an in-app digest of the release body |

The old flow is analysed in `docs/research/update-delivery-model.md`; the current flow is described
in `README.md` next to this file.

## Numbering

The `v0.x.0` numbers are **editorial**: they were assigned when the archive was split, in ascending
order from the oldest surviving release (`Demo/251027`) to the last release of the old model
(`Beta/261027`), which is the release immediately preceding `v1.0.0`. Every legacy release uses
`.0` so that the file names keep the `vMAJOR.MINOR.PATCH` shape; the releases themselves were
rolling demo/beta batches, not minor-version boundaries.

## Mapping

| File | Original identity | Version code | Git tag |
|---|---|---|---|
| `v0.1.0.md` | `Demo/251027` | Demo build 2–4 | — (no tag was created) |
| `v0.2.0.md` | `Demo/251031` | Demo build 5 | `ZUX-ZToolDemo251031(5)` |
| `v0.3.0.md` | `Demo/251106` | Demo build 6 | `ZUX-ZToolDemo251106` |
| `v0.4.0.md` | `Demo/251114` | Demo build 7 | `ZUX-ZToolDemo251114(7)` |
| `v0.5.0.md` | `Demo/251127` | Demo build 8 | `ZUX-ZToolDemo251127(8)` |
| `v0.6.0.md` | `Beta/251227` | `c295` | `ZUX-ZTool_Beta_251227_c295` |
| `v0.7.0.md` | `Beta/260325` | `c346` | `ZUX-ZTool_Beta_260325_c346` |
| `v0.8.0.md` | `Beta/260624` | `c791` | `20260624-ZTool_Beta` |
| `v0.9.0.md` | `Beta/260710` | `c1105` | `20260710-ZTool_Beta` |
| `v0.10.0.md` | `Beta/260808` | `c1358` | `20260808-ZTool_Beta` |
| `v0.11.0.md` | `Beta/260913` | `c1605` | `20260913-ZTool_Beta` |
| `v0.12.0.md` | `Beta/261001` | `c1907` | `20261001-ZTool_Beta` |
| `v0.13.0.md` | `Beta/261027` | `c2333` | `20261027-ZTool_Beta` |

Each file states its original identity and tag in a blockquote under the heading, so a file is
self-describing without this table.

## What was preserved

The entries are verbatim from the former `docs/changelog/legacy.md` archive of the root
`更新日志.txt`, including the original release headers. Only presentation changed: every entry
became a bullet, and the group labels the original already had (`修复：`, `功能：`, `优化：`,
`新功能（Hook）：`, `稳定性（Hook）：`, `其它：`) became section headings. Releases without such
labels are a single `## 更新内容` list. The archive was removed once every release had its own
file; this document carries its preamble.

These files are read-only history. New releases go to `v<tag>.md` following `README.md`, and the
old tags (`20261027-ZTool_Beta`, `Demo/…`) are ignored by the in-app updater because they do not
match the semver tag regex.
