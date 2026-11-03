# Release changelogs

One Markdown file per release, named after the release tag: `v1.0.0.md`, `v1.0.0-beta.1.md`,
`v1.1.0-canary.20261120.md`. The release workflow uses the file verbatim as the GitHub Release
body, so the same text is what the app's update card digests and what users read on the release
page.

## Writing a release note

1. Copy `TEMPLATE.md` to `<tag>.md`, where `<tag>` is the exact tag (`v` prefix included).
2. Write headings and bullet lists as usual; the card turns them into a plain-text digest.
3. Media is allowed. Prefer stable sources:
   - files committed under `docs/media/` and referenced by relative path, or
   - release assets referenced as
     `https://github.com/ZTool-Project/ZUX-ZTool/releases/download/<tag>/<file>`.
   Images pasted into the GitHub editor get a signed URL that expires in about five minutes, so the
   app cannot show them later; they work on the release page only.
4. The workflow refuses a release whose file is missing.

Canary releases do not use a file here; the nightly workflow builds their body from the commit
range since the previous canary.

## Version bumps

`ztool.version.base` in `gradle.properties` is the numeric core currently in development. It is
the source for every non-explicit build name:

| Build | Name source |
|---|---|
| nightly canary | `v<base>-canary.<yyMMdd>` — follows a base bump automatically |
| local / PR dev | `v<base>-dev.<hash>` — follows automatically |
| beta / rc / stable | `version` input to **Draft a Release**, validated against `<base>` |

So a bump to `2.0.0` is a normal commit that changes only the base. Canary and dev builds pick it
up on their next run. The draft-release workflow refuses a beta/stable version whose numeric core
does not equal the base, so the three channels cannot silently drift.

Keep the base at the version being stabilised until that stable release is published, then bump
again for the next cycle. Do not bump before running the release workflow: the workflow builds the
checked-out commit, and the guard compares that commit's base with the input.

## Cutting a release

1. (New cycle only) commit the `ztool.version.base` bump, e.g. `2.0.0`.
2. Create `docs/changelog/v<version>.md` (copy `TEMPLATE.md`).
3. Run **Draft a Release** with `version` = `<version>` (no leading `v`) and review the draft.
   Beta and rc versions use the same numeric core as the base (`2.0.0-beta.1` while base is
   `2.0.0`).
4. Publish the draft. The tag, release name `<version> (c<count>)` and APK are produced by CI.
5. For the first stable release only, update the **old repository's** `UpdateCheck.json` once with
   this version's name, counter, changelog and release URL, then archive the old repository. The
   new repository keeps no JSON and has no migration workflow.
6. After publishing the stable release, bump `ztool.version.base` to the next cycle's core
   (`2.1.0`, or `2.0.1` for a patch cycle) so canaries stop advertising the released version.
