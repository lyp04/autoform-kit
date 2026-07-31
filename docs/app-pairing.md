# App 一次性配对协议

本协议让授权下载页用一次性票据把通用 Android App 连接到对应 Panel，同时保持所有下载者获得同一份可公开审计的签名 APK。Panel 地址、长期 access key 和业务配置都不写入 APK，也不写入 APK 下载 URL。

> **实现状态：** App 侧的版本化 deep-link handler、校验、确认、HTTPS 兑换客户端和安全连接切换已预留；App 内没有用户可见的配对按钮或菜单，只有供未来授权下载页调用的外部 handler。Panel 也尚未实现票据发行与 `/api/app-pair/v1/redeem`。因此当前不能端到端启用，正式下载页也不应显示发行或打开配对动作。手动填写 Panel URL 与 access key 的方式继续有效。

## 为什么 APK 下载不能直接带入配置

浏览器下载 APK 后，Android package installer 只安装 APK 字节，不会把下载页 URL、查询参数或网页会话交给首次启动的 App。因此下面的查询参数不会自动进入安装后的 App：

```text
https://downloads.example.invalid/app.apk?ticket=example
```

为每次下载动态修改 APK 会产生不同的 APK SHA-256，需要重新签名，并让任何能够读取安装包的人提取其中的值。本项目不采用这种方式，也绝不通过它传递长期凭据。

Google Play Install Referrer 和受管设备的 MDM/EMM 配置属于其他部署模式。即使使用这些通道，也只能传递短期、一次性的票据，不能传递长期 access key。

## 推荐流程

1. 用户进入已授权的下载会话，并主动请求“连接此 Panel”。
2. 下载页从未来的 Panel 发行服务取得一个短期、一次性 `ticket` 及其服务端过期时间。
3. 下载页始终提供同一份已签名、已审计的 APK；票据不进入 APK 或 APK 下载 URL。
4. 用户安装完成后回到下载页，点击“打开并连接”。
5. 网页使用带精确 package 的 Android Intent 打开 App，只携带 Panel HTTPS origin、票据和过期时间。
6. App 显示规范化后的 Panel origin；用户确认后，App 才向该 origin 的固定 HTTPS 端点兑换票据。
7. App 在兑换前后都检查 Settings 安全边界，再把返回的 access key 交给现有连接切换流程。
8. 本地连接保存成功后，App 重新下载并验证完整 config/catalog pair；只有该同步门禁通过，正式流程才可使用。

安装和配对是两个独立动作。网页不能假定 APK 安装后会自动回到网页或自动启动 App。

## Application ID 与 Intent 契约

配对 URI 的 scheme 和 Intent package 都是**目标 APK 的精确 `applicationId`**：

```text
<applicationId>://pair/v1
```

本仓库正式构建的默认值是 `com.autoformkit.app`；并排安装的 debug 构建当前是 `com.autoformkit.app.debug`。如果发行方有意修改 `applicationId`，下载页必须同时使用该构建的实际 scheme 和 package，不能继续写死默认值。

下面的 JavaScript 展示动态过期时间和 Intent 生成方式。`<ISSUED_PAIRING_TICKET>` 是明确占位符，实际页面必须替换为授权发行响应中的真实票据；正式页面还必须使用服务端返回的 `expires`，不能信任浏览器自行延长有效期。

```js
function buildPairingIntent({ applicationId, panelOrigin, ticket, expires }) {
  const query = new URLSearchParams({
    panel: panelOrigin,
    ticket,
    expires: String(expires),
  }).toString();

  return `intent://pair/v1?${query}`
    + `#Intent;scheme=${applicationId};package=${applicationId};end`;
}

// 这两个值必须原样来自未来发行端点的同一次服务端响应。
const { ticket, expires } = await issuePairingTicket();
const intentUrl = buildPairingIntent({
  applicationId: "com.autoformkit.app",
  panelOrigin: "https://panel.example.invalid",
  ticket,
  expires,
});
```

`URLSearchParams` 会把内层 Panel origin 编码为外层查询值，例如 `https%3A%2F%2Fpanel.example.invalid`。下载页必须使用带明确 package 的 Intent，降低其他 App 接收同一 custom scheme 的风险。

显式 package 是**发送方要求**，不是下载页身份认证。App 会校验 URI scheme 等于自身 `applicationId`，但 custom scheme 不能证明链接来自哪个网页，也不能证明发送方原始 Intent 一定写了 package。安全性仍依赖授权下载会话、不可猜票据、精确 audience、HTTPS、一次性消费和用户核对 Panel origin。

## 查询参数的精确约束

| 参数 | 必需 | App v1 接受的值 |
| --- | --- | --- |
| `panel` | 是 | 外层查询中 percent-encoded 的 HTTPS server origin。规范形式为 `https://host[:port]`；不能包含 user info、业务 path、query 或 fragment。App 会小写并 IDNA 规范化 host、移除默认 `:443` 和根 `/`。 |
| `ticket` | 是 | 不透明 URL-safe 字符串，必须匹配 `[A-Za-z0-9_-]{32,512}`。不能含 `=`, `+`, `/`，也不能用 percent-encoded 别名。 |
| `expires` | 是 | 正的 Unix epoch seconds，必须匹配 `[1-9][0-9]{0,18}`；不能有前导零、符号或 percent-encoded 别名。 |

正式 Panel 的票据寿命不得超过 600 秒，建议 300 秒。App 运行时只额外容许 60 秒本地时钟偏差：它会拒绝比本地时间晚超过 660 秒的 `expires`，也会在超过 `expires + 60` 秒后拒绝继续。这个 60 秒容差不能由 Panel 当作延长票据寿命；Panel 仍按自己的服务端时间执行最终过期校验。

URI 的 scheme、authority `pair`、path `/v1` 和三个参数名都区分大小写。未知、重复、缺失、空值、fragment 或超限值全部 fail closed；`ticket` / `expires` 的 percent-encoded alias 也会拒绝。`panel` 在解析后按上表规范化 origin，因此等价的 percent-escape 拼写不被当作独立身份。任何失败都不能回退为普通 Panel 连接值。改变字段或语义时必须使用新版本路径，不能静默改变 `/v1`。

Intent 不得包含 access key、backend token、账号、密码、表单内容、设备记录或其他长期/业务凭据。App 只在进程内短暂持有已解析票据，不把它写入 SharedPreferences、saved instance state、draft、日志、analytics 或剪贴板；进程死亡会丢弃未完成票据。

## 兑换端点

用户确认 origin 且本地安全预检通过后，App 只向该 origin 的固定路径发送：

```http
POST /api/app-pair/v1/redeem
Content-Type: application/json; charset=utf-8
Accept: application/json
Cache-Control: no-store

{
  "version": 1,
  "ticket": "<ISSUED_PAIRING_TICKET>"
}
```

App 不在该请求中发送现有 Panel key、backend token、账号、设备标识、表单值或 deployment metadata。连接超时为 10 秒，读取超时为 15 秒。

唯一成功响应是 HTTP `200`，并使用 `application/json` content type；允许标准 media-type 参数：

```http
HTTP/1.1 200 OK
Content-Type: application/json; charset=utf-8
Cache-Control: no-store

{
  "version": 1,
  "accessKey": "<RETURNED_ACCESS_KEY>"
}
```

App v1 对成功响应执行以下检查：

- 不接受任何 3xx redirect；same-origin redirect 也失败；
- 响应 body 不得超过 16 KiB，并且必须是严格 UTF-8；
- JSON 语法外的前后空白只允许 ASCII SP、TAB、CR、LF；不接受 BOM、其他 Unicode whitespace 或 JSON 后追加的非空白字节；
- 顶层必须恰好包含 `version` 与 `accessKey`，不得有未知字段或重复字段；
- `version` 必须是数值 `1`；
- `accessKey` 长度必须为 1–4096 个字符，并且完整匹配 `[A-Za-z0-9._~+/-]+={0,2}`；`=` 只能出现在末尾且最多两个，不接受空白、control、Unicode 或其他字符；
- 非 `200`、错误 content type、超限、空 key、未知版本或解析失败均保持通用失败，不暴露票据或服务器响应内容。

Panel 的失败响应不得包含 access key。过期、已用、未知、audience 不符和并发失败都必须 fail closed；App 不依赖具体错误 body 或状态码恢复票据。

## App 本地连接切换的真实语义

票据兑换不能绕过手动保存 Panel connection 时的安全边界。App 会在发送兑换请求前和收到成功响应后再次检查：当前必须处于可安全切换的 Settings 边界，且不存在表单、照片、草稿、队列备份、上传、提交/打印不确定状态、安装交接或其他绑定旧 Panel 的工作。

还必须遵守以下事实：

- App 只有获得前台焦点并进入安全的 Settings 边界后，才会显示由 URI 解析和规范化得到的 Panel origin 并要求用户确认；网页自报名称不能替代 origin。等待期间票据只在 App 进程内存中，进程死亡即丢失。
- 同 origin 的 access key 变化仍是连接变化，不能静默覆盖。
- 网络等待期间若本地状态或当前连接发生变化，返回的 key 不得覆盖新状态；本次票据可能已经消耗，用户需要重新发行。
- Panel URL 与 access key 使用同一次设备本地提交，不能留下来自两个连接的半更新值。
- 如果本地提交失败，旧连接仍保留；返回的 access key 不能写入日志或错误信息。
- **本地提交成功后没有自动回滚到旧 Panel。** App 会清理旧 Panel 的 session/cache，并保存新连接；随后 config/catalog 同步若失败，App 保持绑定新连接并 fail closed，直到新连接同步成功或用户明确重新设置连接。
- “票据兑换成功”“设备本地连接已保存”“config/catalog pair 已验证”是三个不同状态。下载页和 App 都不能把前两个状态宣传成已经可用于正式流程。

当前 access key 存在 App 的 `MODE_PRIVATE` SharedPreferences 中，应用备份已关闭；本项目不声称它经过额外的 Android Keystore 或 EncryptedSharedPreferences 加密。它还会在 App 内存和发往已保存 Panel 的后续 HTTPS 鉴权请求中短暂存在。它不得进入 URL、Intent、日志、analytics、剪贴板、公开错误、公开测试夹具或 APK。

## 未来 Panel 的票据发行合同

Panel 端尚未实现。实现时必须满足下面的最低合同，不能用普通随机数、可重放签名 URL 或非原子的 KV `get`/`delete` 近似替代。

### 授权下载会话

- 只有已完成部署方授权的下载会话，才能请求该部署的配对票据；页面加载本身不能自动发行。
- 会话 cookie 应使用 `Secure`、`HttpOnly` 和合适的 `SameSite` 策略，并受 CSRF 防护；授权检查必须在服务端完成。
- 发行响应只能把一次性 ticket、服务端 `expires`、目标 Panel origin、目标 `applicationId` 和协议版本交给下载页，不能把长期 access key 交给浏览器。
- 每次发行都绑定精确 APK/applicationId 选择；debug、fork 或重新打包的 applicationId 不能复用正式包链接。

### Exact audience

每个服务端票据记录必须绑定至少以下 exact audience：

- 协议标识和版本：`app-pair/v1`；
- 精确、规范化的 redeem HTTPS origin 与固定 endpoint；
- 精确目标 `applicationId`；
- 授权下载会话的不可逆标识或摘要；
- 将要返回的 access-key 版本或摘要。

兑换端点必须确认请求到达记录绑定的 origin/version，并且记录仍属于当前有效的 access-key 版本。key 已轮换或任一 audience 不符时应使票据失效，不能把旧票据自动升级为新的长期 key。

`applicationId` audience 用于防止发行服务和下载页混配，但 custom scheme 与当前 v1 redeem body 不提供 Android 包证明或设备证明；不能把它描述为 App attestation。

### 随机性、hash-only 存储与过期

- ticket 必须由 CSPRNG 生成，熵至少 128 bit；同时必须满足 App 的 32 字符下限。推荐生成至少 24 个随机字节并使用无 padding 的 base64url，或直接使用 32 个随机字节。
- ticket 不得包含账号、Panel、key、时间或其他可解码业务字段。
- 服务端只保存 ticket 的 SHA-256 或带服务端 pepper 的 HMAC-SHA-256，不保存 ticket 明文；明文只在一次发行响应、下载页内存、Intent 和 App 进程内短暂出现。
- 服务端记录使用绝对 `expires`，最大寿命 600 秒，建议 300 秒。过期记录不可兑换，清理延迟不能延长授权。
- 发行页、兑换端点、代理、analytics、APM、错误追踪和审计日志都不得记录 ticket、access key 或完整 Intent URL。

### 原子单次消费

- 兑换必须通过支持事务或条件写的状态存储，把记录从 `issued` 原子转换为 `consumed`；只有转换成功的一个请求可以继续。
- 必须在返回 access key 之前完成消费。两个并发请求不能同时成功，读后再异步删除不符合本合同。
- 响应丢失、客户端超时或进程死亡时，已消费票据保持已消费；不能为了“重试方便”再次返回 key。用户必须从授权下载会话重新发行。
- 状态存储若不能证明原子 compare-and-set/transaction，就不能启用配对功能。

### 限流与响应安全

- 对授权会话、账号、来源地址、票据摘要和 redeem endpoint 分别设置有界限流；限流日志只记录不可逆摘要和通用结果。
- 对未知、过期、已消费和 audience 不符使用不泄露内部差异的失败响应；禁止通过响应时间或详细错误枚举有效票据。
- 发行响应、下载页和兑换响应使用 `Cache-Control: no-store`；下载页还应设置严格 CSP 与 `Referrer-Policy: no-referrer`，且不加载会看到完整 Intent 的第三方脚本。
- 只有完成原子消费且 audience/key-version 仍精确匹配时才能返回上述最小成功 JSON；不得增加 redirect、任意 callback URL 或动态 redeem endpoint。

## 下载页要求

下载页必须：

- 始终链接同一份已发布、已签名且通过公开审计的 APK；
- 显示 APK version、SHA-256 和 signer verification 指引；
- 只在用户主动请求且授权会话有效时发行短期票据；
- 在安装说明后提供独立的“打开并连接”动作，并把 scheme/package 绑定到所选 APK 的精确 `applicationId`；
- 不把票据写入 APK 下载 URL、长期存储、第三方 analytics、referrer 或日志；
- 票据过期、消费或结果不确定时重新发行，绝不把长期 access key 降级放进链接；
- 在 Panel 发行、原子兑换和 App 设备端到端测试完成前保持配对按钮关闭。

这种设计让 deployment 自定义继续留在 Panel：公开 App 只实现通用、版本化、用户确认且 fail-closed 的配对入口，不包含任何具体部署地址、访问密钥或产线信息。
