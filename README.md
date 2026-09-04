# autoform-kit

A form platform in two parts: a generic Android app that runs configurable forms, and a panel that
authors those forms and publishes them as a versioned catalog the app reads.

Both live here. The panel is also deployed on its own, so there is a second repository:

- **[autoform-panel](https://github.com/lyp04/autoform-panel)** — how to host the panel, either on
  Cloudflare Workers or as a plain Node server, with its configuration and operational docs. It
  mirrors `panel/` from this repository; it is not a separate implementation.

Panel changes belong here, in `panel/`, where the full test suite runs. autoform-panel resyncs from
this repository and its CI fails if the two drift apart.

The profiles and adapters tracked here are fictional examples. Real forms, endpoints, and
credentials belong in a deployment's private catalog, never in this repository.

## How it fits together

```text
panel ── publishes ──> versioned catalog ── downloaded by ──> Android app
                                                                  │
                              configured backend  <───────────────┘
```

The panel imports backend templates, edits form profiles, and publishes a signed catalog
(`form-profiles.json` + `manifest.json`). The app downloads one verified catalog, renders the
selected profile, and runs only the operations that profile declares. Config and catalog form one
versioned pair, checked by SHA-256 and a minimum app version. The app and the backend talk directly;
the panel never sees backend credentials.

## The Android app

- Renders profiles: fields, choices, photo slots and capture order, scanner policy, materials, and
  optional workflows.
- Chinese, English, and Spanish UI.
- Fail-closed journals for form-creating requests, with bounded image-upload retries.
- Optional over-the-air updates from public GitHub Releases.
- A fresh install has no panel address or key and ships no live catalog; the bundled sample is a
  network-disabled preview until an operator connects a panel.

## Build and test

Android:

```sh
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

Requires JDK 17 and Android SDK 35. The app runs on Android 6.0 (API 23) and newer. Signing inputs
are private and git-ignored.

Panel:

```sh
cd panel
npm ci
npm test
```

Ten of the panel tests read the Android `app/` tree, so they only pass in a full checkout of this
repository — which is why the panel test suite lives here rather than downstream.

## Repository layout

```text
app/     Android app, tests, and a fictional fallback seed
panel/   Panel source and tests (mirrored downstream to autoform-panel for hosting)
docs/    Schema, adapter, catalog, and connection guides
config/  Signing example (local signing config is git-ignored)
tools/   Build, audit, and release tooling
```

## Documentation

- [Profile schema](./docs/profile-schema.md)
- [Backend adapter](./docs/backend-adapter.md)
- [Catalog management](./docs/catalog-and-templates.md)
- [Connect the app](./docs/connect-app.md)
- Hosting the panel, and its configuration: see the
  [autoform-panel](https://github.com/lyp04/autoform-panel) repository.

## Upgrades

Config and catalog are one versioned pair bound to a specific panel and key. A newer verified pair
activates only at a safe boundary; a malformed or mismatched pair locks new work instead of mixing
revisions. Upgrade the panel first (publish an equivalent higher revision), let the old app cache it,
then install the signed app update. Active drafts, queues, and pending uploads are preserved and are
never cleared by hand.

## Security and contributing

Keep real organizations, endpoints, templates, credentials, and records out of this repository and
its history — issues, branches, tags, and release assets included. Open issues only with fictional or
redacted data. Report vulnerabilities privately as described in [SECURITY.md](./SECURITY.md).

## License

[MIT](./LICENSE)

## 中文概要

autoform-kit 分两部分:一个通用的 Android 表单 App,和一个编辑表单、发布带版本 catalog 的面板。
**两部分都在本仓库**;面板另有一个部署仓库
[autoform-panel](https://github.com/lyp04/autoform-panel),讲的是**怎么把面板跑起来**
(Cloudflare Workers 或原生 Node)以及它的配置,它只是本仓库 `panel/` 的下游镜像,不是另一份实现。

**面板的改动提到本仓库的 `panel/`**,完整测试套件在这里跑;autoform-panel 会从这里同步,两边不
一致时它的 CI 会直接失败。

面板导入后端模板、编辑表单、发布签名 catalog(`form-profiles.json` + `manifest.json`);App 下载并
校验一份 catalog,渲染所选 profile,只执行该 profile 声明的操作。config 与 catalog 是一对带版本、按
SHA-256 校验的组合。仓库里的 profile/adapter 均为示例,真实表单与凭证只存在于部署方的私有 catalog。

构建见上面的 gradle 与 `cd panel && npm ci && npm test`。其中 10 个面板测试要读 Android `app/` 目录,
只有在本仓库的完整检出里才能通过——这也是面板测试套件留在这里的原因。升级遵循"先面板、后 App",
活动草稿与队列会保留,不可手工清除。
