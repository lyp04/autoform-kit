# 部署你自己的面板 Worker

本指南带你把 `panel/`（一个 Cloudflare Worker 管理面板）部署到你自己的 Cloudflare 账号下。面板负责：

- 把表单目录存放到**你自己的** GitHub 仓库；
- 向 App 提供表单目录与运行时配置；
- 校验操作员登录态；
- （可选）在发布时用 AI 翻译 / 精简标签。

> 文档中所有主机名、仓库名、密钥均为**占位示例**，请替换为你自己的值。

---

## 前置条件

- **Node.js 18+** 与 **npm**
- 一个 **Cloudflare 账号**
- **Wrangler CLI**（`npm i -g wrangler`，或用 `npx wrangler`）
- 一个用于存放目录的 **GitHub 仓库**（例如 `your-name/your-catalog-repo`，可以是私有仓库）
- 一个 **GitHub fine-grained personal access token**，对上面这个仓库授予 **Contents: Read and write** 权限

---

## 1. 获取代码并进入 `panel` 目录

```bash
git clone https://github.com/your-name/your-fork-of-autoform-kit.git
cd autoform-kit/panel
npm install
```

## 2. 登录 Cloudflare

```bash
wrangler login
```

## 3. 配置 `wrangler.toml` 的 `[vars]`

打开 `panel/wrangler.toml`，把 `[vars]` 里的值改成你自己的（以下与仓库内 `wrangler.toml` 一致，均为**占位示例**）：

```toml
[vars]
# 面板自身校验操作员登录态时使用的后端 API 基础地址
BACKEND_API_BASE = "https://backend.example.com/api"
# 存放表单目录的 GitHub 仓库（owner/name）；面板把 form-profiles.json + manifest.json 发布到这里
GITHUB_REPO      = "your-name/your-catalog-repo"
# App 拉取目录用的公开基础地址，通常就是本 Worker 自己的对外地址
PUBLIC_URL       = "https://your-worker.example.com"
# AI 端点（任意 OpenAI 兼容的 chat 接口），用于发布时翻译 / 精简标签
AI_BASE_URL      = "https://openrouter.ai/api/v1"
# 你的 AI 端点接受的任意 model 名
AI_MODEL         = "openai/gpt-4o-mini"
```

逐项说明：

- **`BACKEND_API_BASE`**：给**面板自身**校验操作员 token 用的后端地址。它与 **App 实际调用**的后端地址是**各自独立**的两处配置——后者属于「全局设置」，在面板网页里配置并下发给 App，详见 [configuration.md](./configuration.md)（两者通常指向同一个后端）。
- **`GITHUB_REPO`**：存放表单目录的 GitHub 仓库（`owner/name`），面板读写目录文件（`form-profiles.json`、`manifest.json`）都在这个仓库里完成。**目录始终使用该仓库的默认分支，没有单独的分支变量。**
- **`PUBLIC_URL`**：App 从这里拉取目录，通常填本 Worker 自己的对外地址；它会写进已发布 manifest 的 `profilesUrl`，所以必须是一个可访问的线上地址。
- **`AI_BASE_URL` / `AI_MODEL`**：可选的 AI 翻译 / 精简标签功能所用的端点与模型，任意 OpenAI 兼容的 chat 接口皆可。不使用 AI 时可保持默认，并跳过下一步的 `AI_API_KEY`。

> `[vars]` 里只放**非敏感**配置。token、密钥等敏感信息**不要**写进这里，改用下一步的 `wrangler secret put`。

## 4. 设置密钥（Secrets）

用 `wrangler secret put` 逐个设置以下密钥（值不会写入代码或仓库）：

```bash
wrangler secret put GITHUB_TOKEN
wrangler secret put AI_API_KEY
wrangler secret put CATALOG_READ_KEY
```

| Secret | 作用 |
| --- | --- |
| `GITHUB_TOKEN` | 前置条件里创建的 GitHub fine-grained token（对目录仓库有 **Contents 读写**）。面板用它读写你的目录仓库。 |
| `AI_API_KEY` | AI 服务的 API Key，用于发布时自动翻译 / 精简标签。若不使用 AI 功能可跳过。 |
| `CATALOG_READ_KEY` | **访问密钥**。App 读取目录时必须携带它，用于给目录读取「上门禁」。请妥善保管，并把**同一个值**填进 App 设置里（见 [connect-app.md](./connect-app.md)）。 |

> 面板**没有**单独的登录口令 / 密码这类密钥。面板网页后台的门槛就是**后端登录本身**：管理员用后端账号登录（账号 + 密码 + 验证码）后，拿到的 token 就是唯一凭证（详见第 5、7 步）。

### 关于访问密钥（`CATALOG_READ_KEY`）

- 面板对外提供目录读取接口时，会校验请求中携带的访问密钥是否等于 `CATALOG_READ_KEY`。
- **只有**持有这个密钥的 App 才能拉取你的表单目录。
- 想「吊销」旧密钥：用 `wrangler secret put CATALOG_READ_KEY` 设一个新值并重新部署，然后更新所有 App 里填写的密钥即可。

## 5. 设置面板网页登录用的后端地址（`BACKEND_BASE`）

面板网页后台**直接在浏览器里**登录你的后端（拉取验证码、登录、读取模板都由浏览器直连后端完成，不经过 Worker）。这个后端地址是**写死在**静态页面里的一个常量，需要你手动改。

打开 `panel/public/index.html`，在文件顶部脚本里搜索 `BACKEND_BASE`，改成你自己的后端 API 基础地址，然后再部署：

```js
const BACKEND_BASE = "https://backend.example.com/api";
```

紧挨着它下面还有一个 `BACKEND_ENDPOINTS` 常量，列出网页登录用到的接口路径（验证码 / 登录 / userInfo / 模板列表 / 模板详情）。如果你的后端路径和这里的默认值不一样，一并改掉：

```js
const BACKEND_ENDPOINTS = {
  captcha: "account/getCaptcha",
  login: "account/adminLogin",
  // …按你的后端调整
};
```

> 这一步必须在部署**之前**完成——`public/` 是作为静态资源随 `wrangler deploy` 一起发布的。（这只影响**面板网页**自己的登录；App 端的接口路径在面板「⚙ 全局设置」的 endpoints 里配，见 [configuration.md](./configuration.md)。）

### 后端要求

因为登录是**浏览器直连后端**：

- 后端需要**允许来自面板域名（origin）的跨域请求（CORS）**。
- 如果后端对登录接口做了 **IP 白名单**限制，面板管理员必须处于被允许的网络里才能登录。
- 面板「➕ 添加新机型」是从后端**拉模板**生成草稿的，所以后端还要提供**模板列表 / 模板详情**接口（路径见上面的 `BACKEND_ENDPOINTS`）。

## 6. 部署

```bash
wrangler deploy
```

部署完成后，Wrangler 会输出你的 Worker 地址，例如：

```
https://your-panel.your-subdomain.workers.dev
```

## 7. 登录面板并继续

在浏览器打开上面部署好的面板地址，用**后端账号**登录（账号 + 密码 + 图形验证码）——这与 App 登录用的是**同一个后端**，面板没有单独的登录口令。登录态本身就是面板的门槛：登进来才能改 / 发布表单。接下来：

- 建立你的表单目录 → [catalog-and-templates.md](./catalog-and-templates.md)
- 配置下发给 App 的全局设置 → [configuration.md](./configuration.md)
- 把 App 连到你的面板 → [connect-app.md](./connect-app.md)
