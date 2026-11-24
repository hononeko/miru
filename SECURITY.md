# Security Policy

## Supported Versions

Security updates are provided for the current major release line:

| Version | Supported |
| ------- | --------- |
| `1.x`   | Yes       |
| `< 1.0` | No        |

## Reporting a Vulnerability

Please **do not** open a public GitHub issue for security vulnerabilities.

Instead, report vulnerabilities privately through GitHub Security Advisories for [`hononeko/miru`](https://github.com/hononeko/miru/security/advisories/new).

When submitting a report, please include:

- A clear description of the vulnerability and its potential impact
- Steps to reproduce or a proof-of-concept
- Affected versions, deployment mode (`local`, `oidc`, `none`), and database engine (`sqlite` / `postgres`), if relevant
- Any suggested remediation or mitigation

We will acknowledge your report, investigate and validate the issue, and coordinate a fix and disclosure timeline with you.

## Security Architecture

For details on Miru's threat model, trust boundaries, cryptographic controls, SSRF/XXE defenses, and container hardening, see [docs/security.md](docs/security.md).
