# 全局设置（面板下发给 App 的配置）

面板除了表单目录之外，还会给 App 下发一份「全局设置」。这些都能在**面板网页的「⚙ 全局设置」对话框**里编辑，App 同步时获取；修改后，所有设备在下次同步时自动生效（App 会自动同步，无需手动操作）。

> 下表中的示例值均为虚构占位，请替换为你自己的值。

| 设置项 | 作用 | 示例（虚构） |
| --- | --- | --- |
| 后端 API 地址（backendApiBase） | App 登录、提交录入结果用的后端基础地址。 | `https://backend.example.com/api` |
| 品牌名（brand） | 在 App 界面上显示的名称。 | `Acme Manufacturing` |
| 通知 Webhook（notifyWebhook，可选） | 接收「错误 / 事件」通知的地址；App 会向它 POST 一段文本消息。留空则不发通知。 | `https://hooks.example.com/t/xxxxxxxx` |
| 自更新源（updateOwner / updateRepo） | App 检查 / 下载新版本 APK 的**公开** GitHub 仓库（owner + repo），公开仓库不需要 token。 | `your-name` / `your-app-releases` |
| Origin / Referer（webOrigin / webReferer，可选） | 若你的后端要求登录请求带特定的 `Origin` / `Referer` 头，在这里填；留空则不发这两个头。 | `https://backend.example.com` |
| 接口路径（endpoints，可选，高级） | 一段 JSON，覆盖 App 调用后端时用的接口路径（登录、验证码、提交、模板等）。 | `{ "login": "/auth/login" }` |
| 会话失效业务码（sessionInvalidCodes，可选） | 后端用于表示 token 不再可用的数字 / 字符串码。 | `[90001, "SESSION_REVOKED"]` |
| 会话失效文案特征（sessionInvalidMessagePatterns，可选） | 后端无稳定业务码时的文案子串，不区分大小写匹配。 | `["signed in on another workstation"]` |

---

## 在哪里设置

1. 用后端账号登录面板网页（账号 + 密码 + 验证码，见 [worker-setup.md](./worker-setup.md)）——面板没有单独的登录口令。
2. 点开顶部的 **⚙ 全局设置**。
3. 改完点「保存」。上面这些字段共用同一个「保存」按钮，一次全部提交。
4. App 会在下次同步时自动获取新配置（启动时、以及你在 App 里保存面板连接后都会自动拉取）。

---

## 各项说明

- **后端 API 地址**：这是 **App 端**实际调用的后端地址。它和面板部署时 `wrangler.toml` 里的 `BACKEND_API_BASE`（供**面板自身**校验 token 用）通常指向同一个后端，但是两个各自独立的配置项。
- **通知 Webhook**：可选，留空即不发。App 向该地址 POST 一段文本消息；请确认你的接收端能处理这种简单文本 Webhook。
- **品牌名**：仅界面展示，可随时改。
- **自更新源**：填一个**公开**仓库，App 从它的 Releases 拉正式版 APK。切勿在此填需要 token 的私有仓库。
- **接口路径（endpoints）**：只有当你的后端接口路径和内置默认值不同才需要填。格式是 `{ "逻辑名": "/你的/路径" }`，只覆盖你写出来的那几个，其余仍用默认。面板网页自己登录用的路径另见 `panel/public/index.html` 顶部的 `BACKEND_BASE` / `BACKEND_ENDPOINTS`。
- **会话失效信号**：App 内置通用 HTTP `401/403`、`session expired`、`token invalid` 等判断；你后端特有的业务码和文案必须在这里配置。文案项是普通子串（不是正则表达式）。命中后 App 会清理无效会话并弹出重新登录提示。

> 这些值可能暴露内部后端、仓库和业务结构。请把真实域名、API 路径、GitHub 仓库名、业务码等只保存在你的部署配置 / 私有目录中，并在生产环境设置 `CATALOG_READ_KEY`；不要把它们提交回公开仓库。
