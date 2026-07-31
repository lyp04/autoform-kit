# Security policy

## Supported versions

autoform-kit is under active development. Security fixes target the current default branch and, when practical, the latest release. Older releases have no guaranteed backports.

| Version | Support |
| --- | --- |
| Latest release | Best effort |
| Older releases | No guaranteed backports |

## Reporting a vulnerability

Use [GitHub private vulnerability reporting](../../security/advisories/new) when available. Include the affected component/version, reproduction, impact, and a minimal redacted proof of concept.

Do not open a public issue containing credentials, backend details, private catalog data, user records, photos, serial values, logs, signing material, or an unpatched exploit. If private reporting is unavailable, open only a fully redacted request for a private contact channel.

Revoke or rotate any exposed credential before reporting. Removing it from the latest commit is insufficient because it may remain in history, forks, caches, tags, Releases, assets, and clones.

## Public repository data policy

Contributions must use fictional data and reserved example domains. This applies to source, tests, fixtures, screenshots, documentation, commit messages, branch names, tags, release notes, and assets.

Never contribute:

- real organization, customer, location, form, or workflow identifiers;
- live domains, routes, repository names, headers, response fields, business values, or error text;
- deployment template, warehouse, SKU, item, serial, or package identifiers;
- passwords, keys, tokens, signing files, `.dev.vars`, logs, photos, or API payloads;
- a live catalog, filled backend adapter, or filled notification adapter.

Deployment values belong in a separate private catalog repository, Cloudflare configuration/secrets, device-local settings, or ignored signing configuration. The private catalog contains App-facing `form-profiles.json` and `manifest.json`, plus Worker-only `panel-settings.json`; none belongs in this repository.

See [deployment security](./docs/security.md) for the operational checklist.

## Security model and limitations

- The Panel is the control plane and the Android App is a generic runtime; the project is not an identity provider or backend.
- Panel writes rely on a backend token revalidated by the Worker. Catalog access uses a shared read key, not per-user authorization.
- Catalog, App config, Panel bootstrap, and notification proxy reads fail open when `CATALOG_READ_KEY` is unset.
- Pre-login Panel bootstrap exposes the authoring adapter subset to clients holding the read key; endpoint metadata is not a secret.
- v2 `notificationAdapter` and Panel-owned `eventTemplates` stay Worker-side. The App can call only a same-origin `/api/notify` contract with exact structured event allowlists; free-form messages are rejected. Profile submission summaries and global diagnostics default off when their explicit switches are missing, and v1 adapter migration never auto-enables `runtime.failure`. Migration-only `notifyWebhook` can still expose a provider URL to old clients until removed.
- Optional AI sends authoring metadata to the configured provider.
- Android currently permits cleartext traffic for integration compatibility; formal deployments should configure HTTPS only.
- Optional cross-App session sharing is disabled by an empty allow-list and requires a separate review before use.

Security-sensitive deployments should perform their own threat model, dependency review, penetration testing, device management, backend authorization review, and incident-response planning.
