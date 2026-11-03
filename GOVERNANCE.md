# Project Governance

## Ownership and history

ZUX-ZTool was created by qwqawa64 in October 2025. Since March 2026 the
project has been maintained day-to-day by WASDDestroy. In October 2026 the
repository moved to this organization, which is administered by the current
maintainer, so that repository governance (branch protection, Dependabot,
security reporting, release signing) can be managed by the person doing the
work. The full git history, the AUTHORS file, and qwqawa64's authorship of
the original project are preserved unchanged.

qwqawa64 is welcome to join this organization as an Owner at any time; the
move changes administration, not credit.

## Maintenance model

The project currently has a single active maintainer. This is stated plainly
rather than implied otherwise:

- All changes land through pull requests, including the maintainer's own.
- Pull requests are squash-merged; the PR title becomes the commit message
  and must follow the commit convention in `.gitmessage`
  (`hook / patch / module / frontend / base / utils / build / docs / i18n /
  deps / chore`). Work-in-progress commits (`wip`, `fixup`) must be squashed
  locally before a PR is opened.
- Required CI (Android build and PR title lint) must pass before merging.
  The ruleset requires 0 approving reviews on purpose: a sole maintainer
  cannot approve their own PR, and a review requirement that is routinely
  bypassed is worse than none. External reviews are welcome and, when
  present, are addressed before merging.

## Norms effective date

Commit-message, review, and release norms in this document apply from the
first release published from this repository. History before that point is
kept as-is: early commits were written under different constraints (including
accommodating the original author's language and tooling), and rewriting
them would destroy attribution and audit value.

## Releases

- Releases are signed with the project's release key (unchanged by the move;
  upgrades install in place).
- Every release asset is accompanied by a `SHA256SUMS.txt` and a GitHub
  build provenance attestation.
- Only releases published from this repository are official (see the user
  agreement, section 2.1).
