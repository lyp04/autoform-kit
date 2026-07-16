# autoform-kit

Remote-configurable form auto-entry — an Android app plus a self-hosted Cloudflare Worker panel.

[![License: MIT](https://img.shields.io/badge/license-MIT-blue.svg)](./LICENSE)

[English](#autoform-kit) · [中文](#中文)

Operators on a shop floor or out in the field open the app, sign in, and work through a form: scan a serial number, take the photos it asks for, fill in the fields, submit. What each form looks like isn't baked into the app — it's published from the panel, so you change a form once and every device picks it up on its next sync. Sign-in and submission go to a backend you run; autoform-kit doesn't ship one.

> Every hostname, repo name, key, token, and form label in this repo and its docs is a placeholder — swap in your own.

## Configuration boundary

The public app is a generic runtime; deployment-specific business knowledge belongs to the panel and
the private catalog. Keep real product/customer/company names, internal hosts and API paths, repository
names, template and warehouse IDs, SKUs, and backend-specific session codes/messages out of this
repository. In particular, previous-step templates must be configured with exact IDs in each profile;
the app intentionally does not infer them from a product name, serial-number prefix, or nearby ID.

## Features

- Forms are defined in the panel and pushed to every device — no new app build to change a form.
- The app walks the operator through each step: scan serial number, take the required photos, fill fields, submit.
- App UI in Simplified Chinese, English, and Spanish (`zh` / `en` / `es`).
- Optional: labels get auto-translated and shortened by an LLM when you publish.
- The app self-updates from a public GitHub repo you control — no token needed.
- The app reaches the panel's catalog through a single access key you set.

## Architecture

Two pieces: the Android **app** operators use, and the Cloudflare Worker **panel** admins use. The panel keeps its catalog of forms in a GitHub repo you own, serves that catalog and some runtime config to the app, and checks operator tokens against your backend. The app signs in and submits directly to your backend.

```
                          edit catalog & global settings
   +----------------+     -------------------------------->    +---------------------------+
   | Admin browser  |                                          |          panel/           |
   +----------------+                                          |    (Cloudflare Worker)    |
                                                               |                           |
   +----------------+          read / write (token)            |  - edit catalog + config  |
   | Catalog repo   | <--------------------------------------> |  - serve form catalog     |
   | (GitHub)       |                                          |  - serve app config       |
   +----------------+                                          |  - verify operator tokens |
                                                               |  - AI translate labels    |
   +----------------+       optional, on publish               |    (optional)             |
   | AI API         | <--------------------------------------> |                           |
   +----------------+                                          +-------------+-------------+
                                                                             ^
                                       catalog + config (via access key)     |
                                       + verify operator token               |
                                                                             |
   +----------------+       sign in (account + password + captcha)   +-------+----------+
   | Backend API    | <----------------------------------------------| app/             |
   | (you provide)  |       submit completed entries                 | (Android device) |
   +----------------+ <----------------------------------------------| operator UI      |
                                                                     +------------------+

   App self-update: the app pulls release APKs from a PUBLIC GitHub repo (no token).
```

## Quick start

Work through it in order — each step links to its guide:

1. Deploy your own panel Worker — [docs/worker-setup.md](./docs/worker-setup.md)
2. Create your catalog and forms in the panel — [docs/catalog-and-templates.md](./docs/catalog-and-templates.md)
3. Set the config the panel serves to the app (backend URL, brand, and so on) — [docs/configuration.md](./docs/configuration.md)
4. Point the app at your panel and sign in — [docs/connect-app.md](./docs/connect-app.md)

The guides under `docs/` are in Chinese for now.

## Repository layout

```
autoform-kit/
├── app/               # Android app operators use (scan / photo / fill / submit)
├── panel/             # Cloudflare Worker panel (catalog + config + AI labels)
├── docs/              # Setup guides (Chinese)
├── build.gradle       # root Gradle build
├── settings.gradle    # includes the one module, :app
├── gradle.properties
├── gradlew, gradlew.bat, gradle/
├── LICENSE
└── README.md
```

The repo root is the Gradle project and `app/` is its only module, so build from the root:

```sh
./gradlew :app:assembleRelease
```

`assembleRelease` produces an **unsigned** APK — the repo deliberately ships no signing config. Generate your own keystore and either add a `signingConfig` to `app/build.gradle` or sign the output with `apksigner`. For a quick install, `./gradlew :app:assembleDebug` gives you an installable debug APK.

## Requirements

To deploy the panel: Node.js 18+, npm, a Cloudflare account, the [Wrangler](https://developers.cloudflare.com/workers/wrangler/) CLI, and a GitHub repo for the catalog with a fine-grained token that has Contents read/write on it.

To build the app: JDK 17 and the Android SDK (or just sideload a release APK).

Either way you need your own backend API — it handles operator login and accepts submitted entries, and (for the panel's "pull a template" flow) exposes a template list/detail endpoint. An AI provider key is optional, only for label translation on publish.

## Contributing

Issues and pull requests are welcome. Please keep changes focused and describe the motivation in the PR.

## License

[MIT](./LICENSE). © 2026 autoform-kit contributors.

---

## 中文

**autoform-kit** 是一套开源的「表单自动录入」工具，表单可以远程配置。它分两部分：操作员用的 Android App，和你自己部署的 Cloudflare Worker 管理面板。

操作员打开 App、登录，然后照着表单一步步来：扫序列号、按要求拍照、填字段、提交。表单长什么样不写死在 App 里，而是从面板下发——改一次，所有设备下次同步就更新了。登录和提交走你自己的后端，autoform-kit 不带后端。

> 仓库和文档里出现的主机名、仓库名、密钥、token、表单标签都是占位示例，换成你自己的。

### 配置边界

公开仓库中的 App 只是通用运行时；具体业务知识应由面板和私有目录管理。真实产品 / 客户 / 公司名、内部域名与 API 路径、仓库名、模板号、仓库号、SKU、后端特有的会话失效码 / 文案都不应写入本仓库。前置步骤模板必须在 profile 中填精确 ID；App 不会根据产品名、SN 前缀或相邻编号猜测。

### 功能

- 表单在面板里定义、下发到所有设备，改表单不用重新打包 App。
- App 引导操作员按步骤走：扫码、拍照、填字段、提交。
- 界面支持简体中文、英文、西班牙文（`zh` / `en` / `es`）。
- 可选：发布时用大模型自动翻译、精简标签。
- App 从你指定的公开 GitHub 仓库自更新，不需要 token。
- App 通过一个你掌控的访问密钥读面板的表单目录。

### 架构

面板把表单目录存在你自己的 GitHub 仓库里，向 App 下发目录和运行时配置，并拿你的后端校验操作员登录态；App 直接连你的后端完成登录和提交。完整数据流见上面英文小节的 ASCII 图。

### 快速开始

按顺序来，每步都有对应文档：

1. 部署你自己的面板 Worker —— [docs/worker-setup.md](./docs/worker-setup.md)
2. 在面板里建目录和表单 —— [docs/catalog-and-templates.md](./docs/catalog-and-templates.md)
3. 配置下发给 App 的设置（后端地址、品牌名等）—— [docs/configuration.md](./docs/configuration.md)
4. 把 App 指向你的面板并登录 —— [docs/connect-app.md](./docs/connect-app.md)

### 目录结构与构建

仓库根目录就是 Gradle 工程，`app/` 是唯一模块，在根目录构建：`./gradlew :app:assembleRelease`。注意 `assembleRelease` 产出的是**未签名** APK（仓库故意不带签名配置）——自己生成 keystore，给 `app/build.gradle` 加 `signingConfig`，或事后用 `apksigner` 签。想快速装机用 `./gradlew :app:assembleDebug`。

### 环境要求

- 部署面板：Node.js 18+、npm、Cloudflare 账号、Wrangler CLI，以及一个存目录的 GitHub 仓库 + 对它有 Contents 读写权限的 fine-grained token。
- 构建 App：JDK 17 与 Android SDK；或直接装打好的正式版 APK。
- 端到端：需要你自己的后端 API（处理登录、接收提交；想用面板「拉模板」功能还要有模板列表/详情接口）。AI 翻译是可选的，需要 AI 服务的 API Key。

### 许可证

[MIT](./LICENSE)。© 2026 autoform-kit contributors。
