# Update delivery and version naming: research note and target model

Analysis note. Scope: how ZTool discovers, verifies and presents a new build, and how a build names
itself. Written ahead of the `qwqawa64/ZUX-ZTool` → `ZTool-Project/ZUX-ZTool` migration, with the
first stable release planned in one to two months.

## 0. Verdict

| Problem today | Resolution in the target model |
|---|---|
| `UpdateCheck.json` duplicates the release note | **GitHub Releases become the only authored artifact**; the app reads the repository's releases Atom feed. Nothing is maintained twice |
| Three channels need three hand-written payloads | Channel is the tag's pre-release suffix. One release body serves one channel; canary reuses the same pipeline with an auto-generated body |
| Release notes cannot carry media | The release body is Markdown and may embed images/video. The card shows a text digest and links to the release page for the rich version |
| Commit count in the JSON drifts by one | The version code is **computed by Gradle and CI from the checkout**, never typed into a file |
| `Beta/yyMMdd` cannot be parsed | `versionName` becomes `vMAJOR.MINOR.PATCH[-channel.N]`; the tag is the same string |
| Old owner coordinates are hard-coded | A single `ZToolSource` constant set, plus one frozen legacy `UpdateCheck.json` as the migration bridge |

The implementation follows the map in §6; the decisions in §8 are the ones this change takes.

## 1. What the current model actually does

### 1.1 Authoring path

```
更新日志.txt (first "Beta/…" header = latest batch)
   └─ draft-release.yml: awk extract → Markdown body
        ├─ softprops/action-gh-release → tag 20261027-ZTool_Beta + draft release
        └─ jq → UpdateCheck.json → commit to master
app (HomeRepository.checkAppUpdate)
   └─ raw.githubusercontent.com/…/UpdateCheck.json  (fallback: gh.absinthe.life)
```

`app/build.gradle.kts` L74-78 derives both identities independently: `versionCode` from
`git rev-list --count HEAD`, `versionName` from the local clock (`Beta/${yyMMdd}`). The release
workflow then re-derives a *third* identity by parsing the human header.

### 1.2 Measured failures

| Observation | Evidence |
|---|---|
| The counter is already ahead of the last published payload | `git rev-list --count HEAD` = **2333**; `UpdateCheck.json.newVersionCode` = **2332**; `更新日志.txt` header says `(c2333)`; tag is `20261027-ZTool_Beta` |
| The payload is edited by hand around releases | 16 commits touch `UpdateCheck.json`, including `chore: revert UpdateCheck.json` |
| The name is not machine-readable | `"版本：\"Beta/261027\" (c2332)"` — a display prefix, quotes and the counter are packed into one string |
| Three identities can disagree | build clock, git count, changelog header, release tag — four sources, no invariant |

The "leading by one" is structural, not a mistake: a release workflow that builds and then commits
necessarily produces a commit count that the already-built APK does not contain.

## 2. Requirements and hard constraints

- **R1** `versionCode` continues to be the commit count. It is the only value Android compares on
  install; a decreasing counter would make every upgrade fail (`INSTALL_FAILED_VERSION_DOWNGRADE`).
- **R2** `versionName` becomes standard `vMAJOR.MINOR.PATCH`, optionally with a pre-release suffix.
- **R3** Existing installs must reach the new repository's release without losing data or being
  stranded on a dead endpoint.
- **R4** One release must be authored once, whatever channel it belongs to.
- **R5** Release notes must be able to contain images and video demonstrations.
- **R6** The client must remain usable behind a GitHub proxy, because raw GitHub is unreliable in
  the target market.
- **R7** The release page and its assets remain the download surface; the app does not need a
  silent installer.

## 3. Transport options, measured

All measurements below were taken from this machine on 2026-11-03.

### 3.1 GitHub REST API (`/repos/{owner}/{repo}/releases`)

Structured (tag, name, `prerelease`, Markdown body, assets), but unauthenticated clients share a
60-request-per-hour IP budget:

```
GET https://api.github.com/repos/ZTool-Project/ZUX-ZTool/releases?per_page=20
→ 403 rate limit exceeded        (reproduced twice, from two different requests)
GET https://api.github.com/repos/termux/termux-app/releases?per_page=5
→ 403 rate limit exceeded
```

The app cannot ship a token, so the API is disqualified as the primary transport. It remains a
possible fallback behind a caching proxy, but that adds infrastructure the project does not have.

### 3.2 Releases Atom feed

```
GET https://github.com/ZTool-Project/ZUX-ZTool/releases.atom   → 200, 10 entries, 27 KB
```

Properties verified against live feeds:

| Property | Result | Consequence for the client |
|---|---|---|
| Draft releases | excluded | a draft cannot leak to users |
| Pre-releases | **included** (`termux/termux-app` shows `v0.119.0-beta.3`) | the client must filter by channel |
| Entry title | **release name**, not tag (`v0.117 (F-Droid only)` links to tag `v0.117`) | the release name can carry the version code |
| Entry link | `/releases/tag/<tag>` | the tag is the canonical version |
| Body | rendered HTML inside `<content type="html">` | Markdown source is not available; media appears as `<img>` |
| Window | exactly the **10 newest** releases | a nightly release per day would evict every real release within 10 days |
| Images | `<img src="https://private-user-images.githubusercontent.com/…?jwt=…">` with `nbf`/`exp` 300 s apart | pasted images in a release body expire in **5 minutes**; they cannot be cached or rendered later |
| Proxy | `https://gh-proxy.com/https://github.com/…/releases.atom` → 200, 10 entries | R6 is satisfiable without a JSON mirror |

Other proxies measured: `ghproxy.net` → 403, `gh.absinthe.life` → 404 (that one only rewrites
`blob`/`raw` paths). The proxy list must therefore be data, not one hard-coded host.

### 3.3 A generated JSON/Atom feed (Pages or `raw`)

A CI-generated file can encode any schema, per channel, with stable media URLs. It also works today
and is fully proxyable. It is nevertheless rejected as the *primary* model: it re-creates the
second authored surface this change is meant to delete, and it can drift from the release exactly
as `UpdateCheck.json` did. It stays on the table as an escape hatch if the 10-entry window or
GitHub availability becomes unacceptable (§7).

### 3.4 Legacy endpoints survive the transfer

```
GET https://raw.githubusercontent.com/qwqawa64/ZUX-ZTool/refs/heads/master/UpdateCheck.json → 200
GET https://github.com/qwqawa64/ZUX-ZTool/releases.atom                                     → 200
```

GitHub redirects the old owner path to the new repository. This is what makes the migration in §5
possible: installed clients keep working while they are moved to the new model.

**Adopted: Releases Atom feed as transport, with tags as the channel and version identity.**

## 4. The target model

### 4.1 Identities

| Identity | Source of truth | Example | Invariant |
|---|---|---|---|
| `versionCode` | `git rev-list --count HEAD` at configuration time | `2471` | strictly increasing while history is append-only |
| `versionName` | Gradle property / CI tag | `v1.0.0`, `v1.0.0-beta.2`, `v1.1.0-canary.20261120` | equals the release tag |
| Release tag | CI input, immutable for stable/beta | same as `versionName` | never reused |
| Release name | CI-generated | `v1.0.0 (c2471)` | `(c<code>)` is the machine-readable counter |
| Channel | pre-release identifier of the tag | `stable` / `beta` / `canary` | derived, never stored in a file |

`versionName` carries the leading `v` so that tag, package name and release name agree character
for character; `PackageInfo.versionName` is what the About page already reads.

Build-time derivation (replacing the clock-based name):

```text
-Pztool.versionName=<release tag>     official release (CI passes it; validated against the tag)
ZTOOL_BUILD_CHANNEL=canary            v<base>-canary.<yyMMdd>
otherwise                             v<base>-dev.<shortHash>
```

`ztool.version.base` lives in `gradle.properties` and is bumped when a release cycle opens. A
nightly rebuilt twice on one day has the same name but a higher code; that is fine, because the
comparison is code-based.

### 4.2 Channels

| Channel | Tag shape | `prerelease` | Who sees it |
|---|---|---|---|
| stable | `v1.0.0` | false | stable, beta, canary installs |
| beta | `v1.0.0-beta.2`, `-rc.1` | true | beta, canary installs |
| canary | `v1.1.0-canary.20261120` | true | canary installs only |

The installed channel is parsed from its own `versionName`; no preference and no UI are needed in
phase 1. A stable user cannot accidentally receive a beta, and a canary user silently falls back to
beta/stable when no newer canary exists.

**Feed-window guard.** Only one canary release exists at any time: the nightly job deletes the
previous canary release (and its tag) before creating the new one. The feed therefore holds one
canary slot plus nine stable/beta slots. Beta releases are expected at one per cycle; the workflow
warns if more than six pre-releases are published between two stables.

### 4.3 Release anatomy

```
tag:      v1.0.0                    ← canonical version, immutable
name:     v1.0.0 (c2471)            ← feed title; (c…) is the only place versionCode is exposed
body:     docs/changelog/v1.0.0.md  ← Markdown, may embed images/video, rendered on the release page
assets:   ZTool_v1.0.0_c2471.apk
          mapping.txt
prerelease: false
```

Changelog authoring moves from the append-only `更新日志.txt` to one file per release under
`docs/changelog/`. The release body is then the file verbatim: no `awk` extractor, no header
parsing, and Markdown media simply works. `更新日志.txt` is retained as the historical archive; the
old extractor is deleted with the old workflow.

Canary releases get an auto-generated body from the commit range since the previous canary (the
existing `android.yml` commit-range step already computes it), so nightly authoring stays at zero.

### 4.4 Client algorithm

```
fetch (direct → proxies, cache-busted, 5 s each)
  → parse Atom (DOM, DTD/external entities disabled)
  → keep entries whose tag matches ^v\d+\.\d+\.\d+(-[0-9A-Za-z.-]+)?$
  → channel = tag suffix contains "canary" → canary
              tag suffix non-empty otherwise → beta
              else                          → stable
  → keep channel rank ≤ installed channel rank
  → versionCode = regex \(c(\d+)\) on the entry title
  → best = max(versionCode), semver as fallback for entries without a counter
  → update iff best.code > installed.code and best.code != ignored.code
  → changelog = HTML → plain text digest (headings, list items, paragraph breaks)
  → downloadUrl = entry <link>
```

Old tags (`20261027-ZTool_Beta`, `Demo/…`) fail the semver regex and are ignored automatically, so
the same feed can contain the pre-migration history. This removes the need for a cutoff list.

Parsing details that matter:

- **DOM over XmlPullParser**: `javax.xml.parsers.DocumentBuilderFactory` exists on Android and on
  the JVM, which keeps the parser unit-testable without Robolectric. Disable
  `FEATURE_PROCESS_DOCDECL`/external entities for XXE.
- **Title is not the tag**: the tag comes from the `<link>`; the name only supplies `(c…)`.
- **HTML digest**: block tags become line breaks, `<li>` becomes `• `, entities are decoded, and
  `<img>`/`<video>` are counted (not fetched). A small `ReleaseNotesText` helper is enough; no HTML
  engine and no new dependency.
- **Media indicator**: `UpdateInfo.hasMedia` lets the card add "含图片/视频" and make the button
  open the release page, which is where the 5-minute signed URLs are regenerated per view.

### 4.5 Media policy

| Authoring method | Stable later? | In-app rendering |
|---|---|---|
| Paste an image into the release editor | **No** — signed URL expires in 5 min | release page only |
| Commit to `docs/media/…` and link | yes | possible in phase 2 |
| Upload as a release asset and link `releases/download/<tag>/…` | yes | possible in phase 2 |

Phase 1 therefore supports media by construction (the release body may contain it) and shows the
rich version on the release page. Phase 2 can add an in-app notes screen with an image loader once
stable-hosted media is the norm; it must not use the signed URLs from a cached feed.

### 4.6 Failure and caching

- Fetch the direct feed first, then the proxy list; accept the first entry set that parses.
- Cache-bust with `?_t=…` and send `Cache-Control: no-cache`; the feed is 27 KB.
- A total failure keeps the existing `UpdateCheckResult.Failure(reason)` UI; no legacy JSON fallback
  is needed in the new client.
- Only one check per app process, as today; the "ignore" key stays `versionCode`.

## 5. Migration of installed users

### 5.1 The frozen legacy payload

Old clients only understand `newVersionName/newVersionCode/whatNew/url`. Publish **one** final
`UpdateCheck.json` pointing at the first release that uses the new model, and never update it
again:

```json
{
  "newVersionName": "v1.0.0",
  "newVersionCode": 2471,
  "whatNew": "更新到全新版本模型……",
  "url": "https://github.com/ZTool-Project/ZUX-ZTool/releases/tag/v1.0.0"
}
```

Because old clients compare codes and `2471 > 2333`, every old install sees exactly one update;
installing it lands on the new client, which never reads the JSON again. Keeping the file frozen
costs nothing and protects users who ignore the card for months. It can be deleted after a long
window (12 months) without affecting anyone who already updated.

**The bridge target must be a stable release**, not a beta, so old stable users are not silently
moved onto pre-release code. Until `v1.0.0` exists, old clients simply see no update, which is the
current state anyway.

### 5.2 OOBE source verification is a migration blocker

Before this change `ZToolSource.GITHUB_OWNER` was `qwqawa64`, and `isVerifiedSourceInput` accepted
only that owner (the bare repository name still passed). A user who downloads from the new
organization and types `ZTool-Project/ZUX-ZTool` would fail first-run verification. The fix is part
of the migration:

- `GITHUB_OWNER = "ZTool-Project"`, `GITHUB_URL` follows it;
- the verifier accepts **both** owners (`qwqawa64`, `ZTool-Project`) plus the bare repo name;
- `ZToolSourceVerificationTest` gains the new-owner cases and keeps the legacy ones.

### 5.3 Coordinate cleanup

All remaining `qwqawa64` references must either move to `ZToolSource` or be updated in the same
change: About links, license link, release fallback, home tip string (both locales), `agreement.md`
§2.1, README badges/links, `UpdateCheck.json` URL, preview sample data. Old URLs redirect today,
but they are not guaranteed to redirect forever.

### 5.4 Sequence

1. Land the new versioning + feed client + CI behind the still-current release process; publish a
   canary/beta build so the feed and client can be exercised without touching stable users.
2. Publish the first real release with a `v*` tag (planned `v1.0.0`).
3. After that release is published, run `bridge-legacy-update.yml` once; it refuses drafts and
   pre-releases and writes the frozen legacy JSON.
4. Update README/agreement/OOBE coordinates; keep accepting the old owner for verification.
5. After the window, delete the legacy payload and the bridge workflow.

## 6. Implementation map

### 6.1 Build

| File | Change |
|---|---|
| `gradle.properties` | add `ztool.version.base=1.0.0` |
| `app/build.gradle.kts` | replace `versionName = "Beta/${getBuildTime()}"` with the tag/property derivation; keep `versionCode = getGitCommitCount()`; APK name already sanitizes `/`, and `v1.0.0` needs no change |
| `BuildConfig` | add `UPDATE_CHANNEL` (`stable`/`beta`/`canary`/`dev`) derived from `versionName`; keep `IS_DEV_BUILD` semantics for local/PR builds |

### 6.2 CI

| File | Change |
|---|---|
| `.github/workflows/draft-release.yml` | input `version` (e.g. `1.0.0-beta.2`); validate the tag and the changelog file; tag `v<version>`; body from `docs/changelog/v<version>.md`; release name `v<version> (c<count>)`; `prerelease` when the version has a suffix; verify the built APK's `output-metadata.json` matches tag + count; refuse a non-advancing commit count |
| `.github/workflows/bridge-legacy-update.yml` | one-shot, run after the first stable release is published; reads its counter from the release name and writes the frozen legacy payload |
| `.github/workflows/android.yml` | nightly passes `ZTOOL_BUILD_CHANNEL=canary`; after a successful scheduled build, publish the new canary release (prerelease, name with counter, auto body) and delete every older canary release/tag; add `permissions: contents: write` to that job only |
| `docs/changelog/` | new per-release Markdown files (`v<tag>.md`), `legacy.md` archive, `README.md` + `TEMPLATE.md` |

### 6.3 App

| File | Change |
|---|---|
| `data/home/HomeRepository.kt` | replace `checkAppUpdate` body with the Atom client; delete `UPDATE_URL`/JSON parsing; keep ignore-code storage; add the pre-release switch |
| new `data/update/` | `ReleaseFeedClient`, `ReleaseFeedParser`, `ReleaseNotesText`, `ReleaseSelector`, `SemVer`, `ReleaseChannel`, `ReleaseEntry`; the parser/selector are pure and unit-tested |
| `viewmodel/HomeViewModel.kt` | `UpdateInfo` gains `hasMedia`; `HomeUiState` gains `isPrereleaseUpdateEnabled`; toggle re-runs the check |
| `screens/home/HomeCards.kt` | media hint; button opens the release page (already does) |
| `screens/ztoolsettings/about/SettingsAboutRoute.kt` | new-repo URLs from `ZToolSource`; "receive beta updates" switch replaces the Actions link |
| `data/home/ZToolSource.kt` | new owner + both-owner verification |
| `screens/home/HomeCardPreviewRoute.kt` | sample data uses `v…` names |
| strings / search | media hint + renamed pre-release strings in both locales; `about_prerelease_update` index entry |

### 6.4 Tests

- `ReleaseFeedParserTest`: title/link/channel/counter extraction, old-tag rejection, notes/media.
- `ReleaseNotesTextTest`: headings, list bullets, entities, media detection.
- `SemVerTest`: ordering and channel classification.
- `ReleaseSelectorTest`: channel visibility, ignore, no-downgrade.
- Extend `ZToolSourceVerificationTest` (new owner accepted, legacy owner accepted, others rejected).

## 7. Risks and guards

| Risk | Guard |
|---|---|
| Ten-entry feed window evicts a channel | One rolling canary release; beta budget warning; escape hatch is a CI-generated feed (§3.3) |
| Release name edited into an unparsable form | Workflow generates it; parser skips entries without `(c…)` and logs |
| Commit count decreases (history rewrite / release branch) | Release job fails if the new count ≤ the latest release's count; tags are immutable |
| Actions shallow clone yields code 1 | Existing `fetch-depth: 0`; assert the count in CI |
| Image URLs expire | Media displayed on the release page; stable hosting recommended for in-app use |
| Proxy outage | Ordered list of independent proxies, direct first; failure UI unchanged |
| Canary release deletion races with clients | New release is created before the old one is deleted, so the feed never goes empty |
| Users switch canary → stable on a lower code | Inherent to commit-count codes; documented, no downgrade possible |
| Draft release accidentally published | `prerelease` and tag shape are computed, and drafts are excluded from the feed anyway |

## 8. Decisions taken

1. **First public version**: `v1.0.0`; `v1.0.0-beta.N` may precede it.
2. **Canary in-app updates**: yes. A nightly publishes one canary release and deletes the previous
   one, so the feed keeps exactly one canary slot.
3. **Changelog source**: one Markdown file per release under `docs/changelog/`; the old
   `更新日志.txt` is archived as `legacy.md`.
4. **Channel selection**: an in-app "接收测试版更新" switch replaces the old Actions link. Off means
   stable only; on means beta and canary too. An installed beta/canary build always keeps its own
   channel, so switching the toggle off cannot strand it on an older stable release.

## 9. Verification plan

- Unit tests above; `.\gradlew.bat testDebugUnitTest`.
- Debug build against the live feed: stable filtering must ignore canary/beta fixtures.
- Dry-run the release workflow on a fork with a throwaway tag and confirm the feed entry, name,
  counter and body.
- After the first real release, verify from an installed old build that the frozen JSON offers
  exactly the new release; then verify the new client ignores the JSON path entirely.
- `python tools/check_unused_strings.py` after the string changes.

## 10. Sources

Local:

- `app/build.gradle.kts` L7-16 (git helpers), L74-78 (identities), L128-133 (APK name)
- `app/src/main/java/com/qimian233/ztool/data/home/HomeRepository.kt` L119-166 (JSON check), L234-237 (URLs)
- `app/src/main/java/com/qimian233/ztool/data/home/ZToolSource.kt` L9-11 (old owner)
- `app/src/main/java/com/qimian233/ztool/screens/ztoolsettings/about/SettingsAboutRoute.kt` L109-111, L189, L300
- `.github/workflows/draft-release.yml` L30-33 (date tag), L69-105 (changelog/JSON), L122-130 (push)
- `.github/workflows/android.yml` L134-157 (nightly release build)
- `UpdateCheck.json`, `更新日志.txt` L1

External (measured 2026-11-03):

- `https://github.com/ZTool-Project/ZUX-ZTool/releases.atom` — 200, 10 entries
- `https://github.com/qwqawa64/ZUX-ZTool/releases.atom` — 200 after ownership transfer
- `https://raw.githubusercontent.com/qwqawa64/ZUX-ZTool/refs/heads/master/UpdateCheck.json` — 200
- `https://github.com/termux/termux-app/releases.atom` — pre-releases present, title ≠ tag
- `https://github.com/Flow-Launcher/Flow.Launcher/releases.atom` — images use 300-second signed URLs
- `https://api.github.com/repos/ZTool-Project/ZUX-ZTool/releases` — 403 rate limit exceeded
- `https://gh-proxy.com/https://github.com/ZTool-Project/ZUX-ZTool/releases.atom` — 200, 10 entries
