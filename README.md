# autoform-kit

A Panel-led form platform with a self-hosted control plane and a generic Android runtime.

> Project status: active development. This repository is a framework, not a hosted service or a
> ready-to-use deployment. Release policy permits generic framework artifacts only, but visibility
> on GitHub is not proof that an existing history or Release has completed the required rewrite and
> post-publication audit. Every real deployment must pass its own private compatibility and release
> evidence. A GitHub artifact is release-ready only after the repository's documented candidate,
> provenance, upgrade, and publication gates pass. The tracked profiles and adapters are fictional
> examples.

[中文概要](#中文概要)

## Overview

The **Panel is the product surface and source of deployment-specific runtime behavior**. It imports backend templates,
edits profiles, previews forms, validates policy, and publishes a versioned private catalog. The
**Android App is a generic execution framework**: it downloads one verified Panel configuration and
catalog pair, renders the selected profile, and runs only the declared operations.

Deployment-specific forms, field mappings, workflow rules, provider settings, and update-source
coordinates do not belong in the APK or public Git history.

## Features

- Structured Panel editing for profiles, ordering, labels, results, photo slots, capture order,
  scanner policy, choices, materials, and optional workflows.
- Versioned backend adapter for authentication, template import, upload, submission, and optional
  operations.
- Private catalog publishing with schema, revision, minimum-App-version, and SHA-256 checks.
- Simplified Chinese, English, and Spanish App UI.
- Worker-proxied structured notifications; v2/v3 provider configuration remains Worker-only.
  The migration-only legacy `notifyWebhook` remains App-facing until old devices are upgraded and
  the field is cleared.
- Fail-closed journals for form-creating remote POSTs, with bounded retryable image uploads.
- Optional AI authoring assistance and optional App updates from public GitHub Releases.

## Architecture

```text
Admin browser ──> Panel Worker ──> private catalog authority
      │                │              ├─ GitHub repository (default/bootstrap)
      │                │              ├─ R2 immutable snapshot + CAS pointer (optional cutover)
      │                │              └─ form-profiles.json / manifest.json /
      │                │                 optional panel-settings.json (Worker only)
      │                │
      │                ├─ verified config/catalog pair ──> Android App
      │                └─ structured notification proxy ─> provider
      │
      └──────────── configured backend <──────────── Android App
```

The browser and App send backend credentials directly to the configured backend. The Worker keeps
catalog-write and optional service credentials, protects Panel writes, and exposes only the
App-facing catalog subset. GitHub is the default catalog authority; R2 is an optional, explicit
cutover. Panel authoring access is controlled by the deployment backend and, when needed, an edge or
network policy. See [Panel deployment](./docs/worker-setup.md) and
[deployment security](./docs/security.md) for the trust boundaries and cutover rules.

## Where customization lives

These are the current customization entry points, including the deployment-gated pairing service:

| Entry point | What belongs there | Storage |
| --- | --- | --- |
| Panel template import | Read backend templates through the Panel-managed adapter and convert their fields, choices, compatibility metadata, and opaque result mappings into a review-required draft; an administrator reviews required values and visibility before publishing | Private draft, then private `form-profiles.json` |
| Panel profile editor | Form identity and visibility, localized input labels/placeholders and operator labels (without rewriting legacy/import result labels or values), template/field bindings, choices, opaque result mappings, photo slots and order, scanner policy, materials, alternate entries including optional mutually exclusive result presets (code visibility/label wrapping/localization/color/result/toggles/overrides), previous steps, duplicate checks, printing, retries, and profile notification policy | Private `form-profiles.json` |
| Panel advanced JSON | Profile fields not yet covered by a structured control, plus opaque legacy extensions required by an already-signed client during a migration window; preserve those extensions privately, do not invent or publish them, and remove them only after old-client replay | Private `form-profiles.json` |
| Panel global settings | `backendAdapter` (including final-submit outcome policy, the independent previous-step recipe outcome policy, and the release-only controlled-recovery verification capability), live resolvers/builders, printing protocol, Settings-title brand prefix, request overrides, profile order, legacy result-key daily groups plus exact profile/result daily groups and flat summaries, versioned public-update repository owner/repo, minimum App version, diagnostics policy, and migration-only legacy fields | Private `form-profiles.json` and derived `manifest.json` |
| Panel notification settings | Provider protocol, templates, delivery rules, response policy, and timeouts | Optional private Worker-only `panel-settings.json`; absence is valid |
| Optional Panel AI editor | Draft wording and translation assistance; output remains subject to validation and review | Private draft, then private catalog if published |
| Panel administration policy | Accounts allowed to author or publish, plus optional edge/network restrictions for the Panel and pre-login metadata | Deployment backend authorization and edge/network policy |
| Cloudflare deployment configuration | Default private GitHub catalog repository/branch, optional R2 catalog-authority binding and reviewed cutover, Worker identity/routes and public origin, `CF_VERSION_METADATA`, source-tagged deploy/runtime provenance, bootstrap adapter, catalog credentials, optional AI configuration, and migration-only legacy inputs | Bindings, variables, secrets, and private deployment evidence |
| One-time App pairing issuer | Download-portal issuance policy, short-lived exact-audience tickets, applicationId allow-list, SQLite Durable Object atomic consumption, and rate limits; the button stays disabled until both Workers are configured and device-tested | Deployment Worker bindings/secrets and private ephemeral state; never the APK or catalog |
| App Settings | Panel address/read key plus operator-local language, the last independent-entry source selected for each Panel/entry pair, and a device-local stable/beta update channel (toggle by tapping the Chinese language button five times within 2.5 seconds). On a configured device, Panel connection, redacted support diagnostics, and diagnostic/crash logs are hidden by default; tap the English language button five times within 2.5 seconds to reveal them for the current App process. The access key remains password-masked. If the Panel address or read key is absent, the section remains visible by default so setup and recovery stay reachable. This visibility shortcut does not bypass login; backend/form policy is not editable here | Device-local storage |
| Android build/release configuration | Package/launcher name/icon/version/signing, SDK and release feature gates, neutral update-protocol defaults, the cleartext-transport compatibility policy, and the empty-by-default cross-App trust allow-list | Source plus ignored/private signing inputs |

Real profiles, adapters, field/option values, notification settings, deployment update-repository
coordinates, endpoints, and other production metadata stay in the private Panel/catalog or in the
explicit deployment-private locations listed above (Cloudflare bindings, variables, secrets, and
controlled deployment evidence).
Build-time values are limited to installation identity, supply-chain/trust boundaries, feature
gates, and neutral backward-compatible protocol defaults; they are not a fallback location for form
or backend behavior. The candidate worktree must contain only generic code and non-operational
examples. See the [customization inventory](./docs/customization.md) for the field-level list and the
small set of bootstrap, infrastructure, device-local, and build trust-boundary values that cannot
come from Panel at runtime.

Formal APKs remain non-debuggable. The hidden support report contains only App/Android versions,
cache presence/revisions, validation booleans, and anonymous local blocker counts; copyable crash
and diagnostic text is redacted for credentials, endpoints, accounts, serial identifiers, and
private paths. When an obsolete, now-invalid cached Panel pair makes its own local unsubmitted draft
unrestorable while a strictly newer complete pair is already verified, the same hidden section may
offer a double-confirmed recovery action. That action appears only when no remote-operation,
submission, upload, print, independent-entry, manual-queue, camera, migration, or storage ambiguity
exists. It deletes only the counted local unsubmitted drafts, never remote records, and then promotes
the already-verified pair; it never runs automatically.

The App and Panel implement the one-time pairing protocol, but source availability alone does not
enable it. Until the Panel Durable Object/secrets, download Worker, exact APK applicationId and
real-device flow pass deployment review, manual Panel URL/read-key entry remains available and the
download page must not advertise pairing as ready. A public issuer deliberately makes the shared
Panel connection credential obtainable by any visitor; it does not create or bypass the separate
backend account session required for operational submissions.

Production Panel, backend, notification, and file endpoints are required to use HTTPS. This is
currently an operational/deployment requirement rather than a complete runtime guarantee: manual
Panel entry does not enforce HTTPS or reject an `http://` address, and the Android application
manifest used by release builds permits cleartext traffic for legacy integration compatibility,
while the reserved pairing flow accepts HTTPS origins only. Treat the cleartext setting as an
explicit APK build boundary, not as Panel-owned production customization.

Two Panel safeguards are worth calling out:

- `defaultPhotoOrder` has a structured two-option control. It writes the same field consumed by the
  App and updates the preview/JSON together. New or migrated Panel publications must choose a
  supported value explicitly; the App keeps only the documented grouped-order fallback for an
  untouched legacy catalog.
- A newly imported visible `singleChoice` field starts empty with `reviewRequired:true`. An
  administrator must explicitly choose its submission value in the Panel before publication.

The Panel configures controls and protocols already implemented by the generic framework. Adding a
new native widget, renderer, transport, or protocol capability still requires a reviewed framework
upgrade; the deployment-specific labels, mappings, values, and enablement for that capability then
remain Panel-owned.

## Quick start

Prerequisites:

- Node.js 22+, npm, a Cloudflare account, a separate private GitHub catalog repository, and a
  repository-scoped Contents read/write token; an R2 bucket is optional and becomes authoritative
  only after the documented exact-snapshot cutover;
- JDK 17 and Android SDK 35; the App requires Android 6.0 (API 23) or newer;
- a backend that permits the documented browser/Worker access and implements every operation enabled
  by the private adapter and profiles.

The quick start uses the GitHub-backed default. R2 cutover requires the separately reviewed
procedure described in [Panel deployment](./docs/worker-setup.md); merely binding a bucket does not
make it authoritative.

Set up in this order:

1. [Define the private backend adapter](./docs/backend-adapter.md).
2. [Deploy the Panel and initialize a private catalog](./docs/worker-setup.md).
3. [Create, review, and publish private profiles](./docs/catalog-and-templates.md).
4. [Review global configuration](./docs/configuration.md).
5. [Connect and verify an Android device](./docs/connect-app.md).

A fresh App installation has an empty Panel address and empty read key. It does not contain a live
backend or production catalog. The bundled sample may be displayed only as a network-disabled
preview until an operator saves a Panel connection and the App verifies a complete pair.

## Release status and hard gates

A framework Release does not certify a deployment. Public tests validate the generic framework;
deployment truth must be established with private evidence bound to the exact Panel config/catalog,
adapter, candidate APK, and retry/outcome policies.

Publication is blocked until the complete public surface—history, branches, tags, pull-request refs,
Releases, assets, and APK contents—passes the privacy audit. The one-time v1.0.14 maintenance gate
also binds the exact v1.0.13 predecessor, reviewed source diff, current private Panel/R2 snapshot,
signed in-place upgrade, fresh install, live-catalog smoke, and update protocol. Releases that change
submission, upload, or recovery behavior still require the full rollback, payload-replay, and
controlled-recovery evidence. Deleting a file or merging a cleanup commit does not erase older Git
objects.

The exact evidence formats, capture requirements, and publication sequence are documented in
[Releasing](./docs/releasing.md) and [deployment security](./docs/security.md). Repository visibility
or a successful command is not evidence that those gates passed.

## Synchronization and upgrade safety

Config and catalog form one atomic, revisioned pair bound to the exact Panel/key. A newer verified
pair activates only at a safe boundary; a malformed, same-revision-mutated, or wrongly bound pair
locks new work instead of mixing revisions. A saved queue backup pins its original pair until the
operator explicitly deletes that backup.

Legacy upgrades are **Panel first**: publish a behavior-equivalent higher revision, then let the old
App cache the exact target pair before installing the signed update. The first upgrade requires one
new login because unbound legacy credentials are never imported or sent; drafts, queues, ledgers,
pending photos, and rollback credentials are preserved. Unscoped legacy statistics and round-ledger
mirrors are migrated when their ownership can be established, but they do not permanently block an
otherwise valid Panel pair. Active drafts, queues, pending photos, submissions and uncertain remote
results still fail closed and must not be cleared or replayed by hand.

Follow the operational checks in [Connect the App](./docs/connect-app.md) and the recovery limits in
[deployment security](./docs/security.md) before upgrading a real device.

## Public/private boundary

Never commit a live catalog or deployment export. Public surfaces include the current tree, deleted
files, commit messages, branches, tags, Releases, assets, fixtures, screenshots, logs, issues, forks,
caches, and CI artifacts.

Keep real organizations, endpoints, routes, templates, identifiers, result values, provider settings,
credentials, records, photos, and payloads in deployment-owned private storage. Review
[SECURITY.md](./SECURITY.md) and [deployment security](./docs/security.md) before publishing source
history, tags, or release assets.

## Development

Panel:

```sh
cd panel
npm ci
npm test
```

Android:

```sh
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

Local Panel deployment configuration and signing inputs are ignored/private. Release tooling is
described in [Releasing](./docs/releasing.md); running a command is not a substitute for reviewing
the generated artifacts and required deployment evidence.

## Repository layout

```text
app/       Generic Android runtime, tests, and fictional fallback seed
panel/     Worker, browser control plane, tests, and fictional examples
docs/      Deployment, configuration, schema, security, and release guides
config/    Signing example; local signing configuration is ignored
tools/     Audit, build, and release tooling
.github/   CI and contribution guidance
```

## Documentation

- [Customization inventory](./docs/customization.md)
- [Backend adapter](./docs/backend-adapter.md)
- [Profile schema](./docs/profile-schema.md)
- [Panel deployment](./docs/worker-setup.md)
- [Catalog management](./docs/catalog-and-templates.md)
- [Global configuration](./docs/configuration.md)
- [Connect the App](./docs/connect-app.md)
- [One-time App pairing protocol](./docs/app-pairing.md)
- [Deployment security](./docs/security.md)
- [Release process](./docs/releasing.md)

## Support and contributing

Open public issues only with fictional or fully redacted data. Report security issues through the
private process in [SECURITY.md](./SECURITY.md). Contributions, including their history and assets,
must remain deployment-neutral.

## License

[MIT](./LICENSE)

## 中文概要

**Panel 是主体，Android App 是通用运行框架。** 发布政策只允许 GitHub Release 包含通用框架产物，
但内容在 GitHub 上可见，不代表现有历史或 Release 已完成规定的重写和发布后审计，也不等于任何
真实部署已经通过投产门；当前一次性 v1.0.14 maintenance gate 仍须在发布现场通过全部 live 证据。
表单、字段与结果映射、照片框和拍摄顺序、
扫描规则、可选流程、后端适配器、通知和更新源都在私有 Panel/catalog 中维护；候选工作树只允许
通用代码和不可运行示例。公开前必须独立复核完整历史、tags、Releases 与 assets，普通合并或删除
提交不能清除旧对象。新装 App 的 Panel 地址与 read key 均为空，不能从 APK 获得生产配置。

升级旧设备必须先部署兼容 Panel，并让旧 App 在目标 revision 发布后彻底退出、重新启动，缓存
带精确 proof 的同一 config/catalog pair；这是兼容迁移的必要条件，不是充分条件。v1.0.14 通过精确
v1.0.13 基线与 changed-path allowlist、当前签名升级/fresh-install/live-catalog/update 证据发布；任何
触及提交、上传或 recovery 的后续版本仍须完整运行流等价、回滚与 recovery 门禁。无 proof、摘要不符或连接改变时不能猜旧 cache
或草稿归属。严格更新候选等待时可以继续使用完整旧 active pair；同版本篡改、损坏或错绑候选会
锁定。手动队列备份会长期保留并 pin 住原 pair，只有用户明确确认删除后才会解除。

当前公开仓没有可执行的受控恢复工具来解锁结果不明的最终提交、上一工序或上传；在部署方完成
后端核对流程和受审工具前，这些状态必须保持锁定，不能手工删除或声称已经可恢复。
