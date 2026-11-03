# Security Policy

## Supported Versions

Security fixes are applied to the latest release only. Older releases do not
receive backports; please update before reporting.

## Reporting a Vulnerability

Please report security issues privately through GitHub's
[Report a Vulnerability](https://github.com/ZTool-Project/ZUX-ZTool/security/advisories/new)
form (Security → Advisories). Do not open a public issue for a vulnerability.

Please include:

- the affected version (see Settings → About in the app),
- the device model and ZUXOS / Android version,
- a minimal reproduction or the relevant log excerpt.

You can expect an initial reply within 7 days. Confirmed issues are fixed in
the next release, and the advisory is published once a fixed version is
available.

## Scope notes

ZUX-ZTool is an LSPosed module that runs with elevated privileges by design.
Reports about behavior that requires a user to install and enable the module
on their own device are in scope when they allow privilege escalation beyond
what the module discloses, silent data exfiltration, or bypass of the
module's own source verification.
