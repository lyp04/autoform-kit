# autoform-kit

A Panel-led form platform with a self-hosted control plane and a generic Android runtime.

> Project status: active development. This repository is a framework, not a hosted service or a
> ready-to-use deployment. No release-ready candidate is currently declared. The tracked profiles
> and adapters are fictional examples.

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
- Fail-closed journals for remote side effects and a durable multipart-upload replay barrier.
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

The browser and App send backend credentials directly to the configured backend. The Worker holds
catalog-write and optional service credentials, validates protected Panel writes, and exposes only
the App-facing subset of the private catalog. GitHub is the default catalog authority. A bound but
unseeded R2 bucket reads from GitHub and rejects publishes; only a seeded, hash-verified current
pointer makes R2 authoritative. The Panel has no separate role database: deployments must restrict
authoring accounts through backend authorization and, when needed, an edge or network access policy.

## Where customization lives

These are the current customization entry points plus the explicitly reserved future pairing entry:

| Entry point | What belongs there | Storage |
| --- | --- | --- |
| Panel template import | Read backend templates through the Panel-managed adapter and convert their fields, choices, compatibility metadata, and opaque result mappings into a review-required draft; an administrator reviews required values and visibility before publishing | Private draft, then private `form-profiles.json` |
| Panel profile editor | Form identity and visibility, localized operator labels (without rewriting legacy/import result labels or values), template/field bindings, choices, opaque result mappings, photo slots and order, scanner policy, materials, alternate entries, previous steps, duplicate checks, printing, retries, and profile notification policy | Private `form-profiles.json` |
| Panel advanced JSON | Profile fields not yet covered by a structured control, plus opaque legacy extensions required by an already-signed client during a migration window; preserve those extensions privately, do not invent or publish them, and remove them only after old-client replay | Private `form-profiles.json` |
| Panel global settings | `backendAdapter` (including final-submit outcome policy, the independent previous-step recipe outcome policy, and the release-only controlled-recovery verification capability), live resolvers/builders, printing protocol, brand, request overrides, profile order, explicit cross-profile daily-result groups, versioned public-update repository owner/repo, minimum App version, diagnostics policy, and migration-only legacy fields | Private `form-profiles.json` and derived `manifest.json` |
| Panel notification settings | Provider protocol, templates, delivery rules, response policy, and timeouts | Optional private Worker-only `panel-settings.json`; absence is valid |
| Optional Panel AI editor | Draft wording and translation assistance; output remains subject to validation and review | Private draft, then private catalog if published |
| Panel administration policy | Accounts allowed to author or publish, plus optional edge/network restrictions for the Panel and pre-login metadata | Deployment backend authorization and edge/network policy |
| Cloudflare deployment configuration | Default private GitHub catalog repository/branch, optional R2 catalog-authority binding and reviewed cutover, Worker identity/routes and public origin, `CF_VERSION_METADATA`, source-tagged deploy/runtime provenance, bootstrap adapter, catalog credentials, optional AI configuration, and migration-only legacy inputs | Bindings, variables, secrets, and private deployment evidence |
| One-time App pairing issuer | Future authorized download-session policy, short-lived ticket audience, atomic consumption store, and rate limits; this server-side issuer is not implemented, so the pairing action must remain disabled | Future deployment-owned service and private state; never the APK or catalog |
| App Settings | Panel address/read key plus operator-local language and a device-local stable/beta update channel (toggle by tapping the Chinese language button five times within 2.5 seconds); backend/form policy is not editable here | Device-local storage |
| Android build/release configuration | Package/launcher name/icon/version/signing, SDK and release feature gates, neutral update-protocol defaults, and the empty-by-default cross-App trust allow-list | Source plus ignored/private signing inputs |

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

The App-side one-time pairing protocol is only a reserved interface. Until a deployment-owned issuer
and atomic redeem endpoint pass review, manual Panel URL/read-key entry remains authoritative and a
download page must not advertise pairing as available.

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

The documented quick start uses the GitHub-backed default. The repository exposes the R2 seed
library contract and tests, but no operator-facing Panel route or supported CLI performs that
cutover; do not bind R2 without a separately reviewed private one-shot procedure.

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

There is no declared release-ready candidate. Passing public tests or moving configuration into
Panel does not by itself certify an upgrade or the repository's existing public history.

- Final-submit and alternate-entry retries require private replay evidence bound to
  `operations.submit.outcomePolicy`. Missing-material recovery requires its own not-written rules
  and a profile-owned material-code pattern.
- An executable previous-step recipe requesting more than one attempt requires separate evidence
  bound to `operations.previousSteps.recipeOutcomePolicy`; its legacy retry/already-exists strings
  do not authorize the new App, and submit evidence cannot substitute for recipe evidence. Retaining
  legacy already-exists behavior for old Apps also requires explicit acknowledgement rules for the
  new App.
- Private release gates must bind each policy's `evidenceSha256` to the exact reviewed replay. Public
  schema validation checks shape, not deployment truth.
- Duplicate-response parity cannot be established by replay counts or mock/offline fixtures. The
  private-only migration gate must stable-read three independent mode-`0600` raw-response/provenance
  pairs—duplicate, non-duplicate, and unclassified fail-closed—plus their audited GET execution
  receipts. It recomputes the current capture-tool and command-protocol hashes and binds each case to
  the exact adapter, candidate, and external Panel config/catalog pair; private fields are never
  copied into public reports.
- Release still requires a full public-history/tag/Release-asset and APK privacy audit, signed
  in-place upgrade tests, exact old/new update routing, real-profile payload replay, and reviewed
  handling for uncertain remote side effects. An ordinary merge or deletion commit does not erase
  sensitive objects from earlier Git history.

## Synchronization and upgrade safety

Config and catalog are one logical unit. Each active pair is bound to the exact Panel/key and one
positive catalog revision. Downloads first enter candidate slots and become active together only at
a safe Settings boundary.

- A strictly newer, individually valid candidate may wait while the App continues using the exact
  old active pair. This preserves the established offline/cache fallback without mixing revisions.
- A same-revision mutation, malformed candidate, wrong connection binding, or uncertain recovery
  state locks new work instead of falling back silently.
- A manually saved queue backup is deliberately long-lived. It pins the active pair so a downloaded
  candidate cannot reinterpret work intended for later restoration. Only the explicit, confirmed
  “delete queue backup” action can remove that backup and release the pin; restart, logout, ordinary
  queue completion, or a new download does not.

For an upgrade from a legacy App cache, **Panel first** is a hard prerequisite: deploy the compatible
Panel, publish a behavior-equivalent higher revision, then fully stop and restart the old App after
that publication so it caches config and catalog from the exact same target revision. The Panel proof
binds the Panel origin, read-key digest, exact raw catalog digest, and revision. If the proof or any
bound input is absent or different, the new App does not infer ownership; it requires an online pair
refresh, and an unbound legacy draft remains locked. Before the first signed-v1-to-current install,
the old App must be fully stopped with no camera flow awaiting a result, and all three legacy
`pending_a_step_photo_path`, `pending_a_step_photo_seq`, and
`pending_a_step_entry_photo_path` slots must be readable and absent. Uncertain slots must not be
deleted by hand; recover them with a compatible old workflow or stop the rollout.

The current App also introduces a realm-bound v2 login store. Its realm covers the full Panel/key
connection identity and the effective authenticated backend transport, while form/profile-only
publishes do not change it. For safety, a first upgrade does **not** import or send an unbound legacy
token, password, account, user name, or web fingerprint: the operator must sign in once after the
upgrade. Legacy credential bytes are left untouched during the ordinary upgrade so a signed-App
rollback remains possible; an explicit Panel/key switch clears both legacy and v2 session storage.
Drafts, queues, ledgers, and pending photos are not cleared by this one-time re-login requirement.

Remote results that cannot be proven are also locked rather than replayed. This repository currently
does **not** ship an operator-facing controlled-recovery tool for uncertain final submissions,
previous-step writes, or multipart uploads. Do not delete their local state manually or describe
those paths as recoverable. A deployment-specific backend reconciliation procedure and reviewed
recovery tool remain release prerequisites. Reprints have only the narrower, implemented convergence
path documented in [deployment security](./docs/security.md).

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

**Panel 是主体，Android App 是通用运行框架。** 当前改造尚未发布，也没有已声明可发布的候选。
表单、字段与结果映射、照片框和拍摄顺序、扫描规则、可选流程、后端适配器、通知和更新源都在
私有 Panel/catalog 中维护；候选工作树只允许通用代码和不可运行示例。现有公开历史、tags、
Releases 与 assets 在完成历史重写和独立复核前不属于这一结论，普通合并或删除提交不能清除旧
对象。新装 App 的 Panel 地址与 read key 均为空，不能从 APK 获得生产配置。

升级旧设备必须先部署兼容 Panel，并让旧 App 在目标 revision 发布后彻底退出、重新启动，缓存
带精确 proof 的同一 config/catalog pair；这是兼容迁移的必要条件，不是充分条件，签名升级、完整
运行流等价、回滚与 recovery 门禁仍须全部通过。无 proof、摘要不符或连接改变时不能猜旧 cache
或草稿归属。严格更新候选等待时可以继续使用完整旧 active pair；同版本篡改、损坏或错绑候选会
锁定。手动队列备份会长期保留并 pin 住原 pair，只有用户明确确认删除后才会解除。

当前公开仓没有可执行的受控恢复工具来解锁结果不明的最终提交、上一工序或上传；在部署方完成
后端核对流程和受审工具前，这些状态必须保持锁定，不能手工删除或声称已经可恢复。
