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
