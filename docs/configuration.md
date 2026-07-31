# 全局配置

Panel 是运行配置与表单行为的主体，App 只是读取并执行已验证配置的通用框架。App-facing 值写入私有 `form-profiles.json` 的 `settings`；只供 Worker 使用的通知提供方适配器写入私有 `panel-settings.json`。真实 profile、backend adapter、通知设置、更新源坐标与其他正式环境值都留在私有 Panel/catalog；公开源码只保留不可运行的虚构示例。完整自定义入口见[自定义边界](./customization.md)。

## 当前配置项

| 配置 | 作用 | 下发给 App |
| --- | --- | --- |
| `backendAdapter` | 后端地址、接口、字段与可选模块协议 | 是 |
| `brand` | App 显示名称 | 是 |
| `notificationAdapter` | Worker 调用通知提供方的协议 | 否 |
| `diagnosticsPolicy` | 是否允许发送最小化结构化故障事件 | 是；运行时再派生为 `/api/config.notification.diagnosticsEnabled` |
| `dailyStats` | 登录页按 Panel 显式 result-key 分组进行全表单汇总 | 是 |
| `updateSource` v1 | 版本化公开更新源 contract；只允许精确的 `version:1`、`owner`、`repo` | 是 |
| `updateOwner` / `updateRepo` | 与 `updateSource` 相同的公开仓库坐标，供 signed v1.0.4/v1.0.6 兼容读取 | 是 |
| `minAppVersionCode` | 接受新 catalog 所需的最低 Android versionCode | 通过 `manifest.json` |
| `webOrigin` / `webReferer` | Backend 明确要求时由 App 添加的请求 header 值 | 是 |
| profile 顺序 | App 选择器中的顺序 | 作为 profiles 数组顺序 |

会话失效策略属于 `backendAdapter.auth`，profile-specific 行为属于各 profile。迁移期扁平 host、endpoint、会话字段和 `notifyWebhook` 不应作为新配置来源。

上一工序、重复记录、打印、提交前列表刷新、材料缺失恢复、提交重试和提交汇总都是 profile-specific policy，不是 App 常量。全局 adapter 只描述后端能力与 wire contract；同时缺少 profile 开关或 adapter 能力时，可选模块不会运行。

照片默认顺序同样属于 profile：Panel 用结构化控件维护 `defaultPhotoOrder`，预览与 JSON 使用同一字段。新导入的可见 `singleChoice` 字段默认没有提交值并带有 `reviewRequired:true`；管理员在 Panel 明确选择后才可发布。不要在 App 源码中加入业务默认值来绕过这两项审查。

## `dailyStats`

`settings.dailyStats` 是可选的 App-facing 登录页汇总契约。它只允许 `scope:"all_profiles"` 和一个有序 `groups` 数组；每组由稳定 `id`、显示 `label` / 可选 `labelI18n`、`#RRGGBB` `uiColor` 与一个非空 `resultKeys` 数组组成。组最多 16 项，同一 result key 在组内和组间都不能重复，并且必须由至少一个显式 `pickerVisible:true` profile 的 `gradeMap` 精确声明。

Panel 的全局设置提供结构化增删、排序、标签、颜色和 result-key 选择控件。省略该对象表示不启用全表单汇总。它不会改变结果提交值：主表单仍由 profile 的 `gradeMap[key].value` 提交，主表单按钮优先使用 profile 自己的 `operatorLabel` / `operatorLabelI18n` 和颜色，缺失时回退只读的旧版/导入 `label` / `labelI18n`。完整结构与虚构示例见 [Profile schema](./profile-schema.md#settingsdailystats)。

Profile 结果编辑器不会把现场显示需求写回旧版 `gradeMap[key].label`，也不会触碰 `value`；它只维护可选 `operatorLabel` / `operatorLabelI18n`。这样同一 catalog 可让旧 App 继续读取原标签和提交值，同时由新版 App 显示 Panel 配置的短操作员文案。

## `backendAdapter`

Panel、Worker 与 App 共用 v1 adapter。首次引导值来自 Cloudflare `BACKEND_ADAPTER_JSON`；登录 Panel 后应保存到私有 catalog 并由 Panel 维护。

Adapter 包含 endpoint metadata 和 field mapping，不能包含密码、长期 token、API key 或 secret header。完整字段见 [backend-adapter.md](./backend-adapter.md)。

启用重复检查前必须在 `operations.duplicateCheck` 显式填写 `dateTransforms`、`epochUnits`、`dateFormats` 和 `timeZone`。`dateTransforms` 是受限、有序且无重复的文本日期转换数组，空数组表示不转换；`epochUnits` 是允许的 `seconds` / `milliseconds` 列表，空列表表示只接受配置的文本格式。Panel 只为迁移而允许旧 adapter 的四项同时缺失；部分填写无效，未补齐时不能发布启用重复检查的 profile。可选兼容组 `epochDigitLengths`、`numericFractionPolicy`、`textParseConsumption`、`plausibilityScope`、`timeZoneSource`、`rootValueEnabled` 必须全有或全无；全部缺失与公开示例的 `[] / reject / full / all / configured / false` 保持同一严格行为。启用打印前必须提供精确标识关联、非空 accepted type、互不重叠且非空的打印状态集合，以及正数且单调递增的 numeric job ID。App 不从真实数据猜这些语义。

## `notificationAdapter`

通知通过同源 Worker 代理。App 只能向 `/api/notify` 提交版本匹配、字段固定的结构化事件；provider URL、消息模板、响应成功标记与超时只保存在私有 `panel-settings.json`。v2 与 v3 是两套独立契约，不能混用字段。

### v2：聚合事件（保留）

v2 由 Panel 私有 `eventTemplates` 生成消息，继续支持 `submission.summary` 与 `runtime.failure`。完全虚构的 provider adapter 示例：

```json
{
  "version": 2,
  "url": "https://notifications.example.invalid/hooks/demo",
  "method": "POST",
  "bodyTemplate": {
    "event": "{{type}}",
    "text": "{{message}}"
  },
  "eventTemplates": {
    "submission.summary": "Example run: submitted={{submittedCount}}, errors={{errorCount}}",
    "runtime.failure": "Example runtime failure: {{stage}}/{{errorCode}} ({{fingerprint}})"
  },
  "successStatuses": [200, 202, 204],
  "response": {
    "codePath": "result.code",
    "successValues": ["EXAMPLE_OK"]
  }
}
```

规则：

- v2 的 `version` 必须为 `2`。
- `url` 必须是绝对 HTTP(S) URL；正式环境使用 HTTPS。
- `method` 仅支持 `POST`、`PUT`、`PATCH`。
- `bodyTemplate` 必须是 JSON object 或 array，并至少包含一次 `{{message}}`；可选 `{{type}}`。这里的 `message` 是 Worker 根据私有事件模板生成的文本，不来自 App。
- `eventTemplates` 必须是非空 object，目前仅支持 `submission.summary` 与 `runtime.failure`。每项是最长 4,000 characters 的非空 string，只能引用该事件白名单字段和 `{{type}}`。
- `successStatuses` 是非空 HTTP status 数组。
- `response` 可省略；存在时，status 通过后还要让 `codePath` 的值匹配 `successValues`。
- 自定义 provider header 不受支持。若 provider 必须使用 header credential，请在其前面部署受控 server-side relay。

事件字段白名单：

| 事件 | `data` 必需字段 |
| --- | --- |
| `submission.summary` | `success`、`submittedCount`、`errorCount`、`unconfirmedPrintCount`、`missingMaterialTypeCount`、`newMissingMaterialTypeCount`、`recoveredMaterialTypeCount`、`networkAffectedCount` |
| `runtime.failure` | `stage`、`errorCode`、`subphase`、`fingerprint`、`appVersion`、`gitHead`（构建时 App 子树的完整 Git object id，不是可变 release metadata 所在的 commit id）、`androidSdk`、`networkTransport`、`networkValidated`、`networkCaptive`、`networkInternet`、`networkMetered`、`networkVpn` |

计数必须是非负整数；布尔字段必须是 JSON boolean；标识字段只允许短标识符。`subphase` 可以是空字符串，但字段本身仍必须存在。任何未知字段和自由文本 `message` 都会被拒绝；官方 App 只从固定聚合值与运行环境生成这些字段，不把记录标识、账号、路径、URL、日志、响应正文或堆栈映射进事件。

v2 App 只收到如下代理配置：

```json
{
  "notification": {
    "version": 2,
    "enabled": true,
    "endpoint": "/api/notify",
    "eventTypes": ["submission.summary", "runtime.failure"],
    "diagnosticsEnabled": true
  }
}
```

`enabled` 表示 v2 adapter 有效；`eventTypes` 只列出已配置模板的事件。`diagnosticsEnabled` 仅在 `runtime.failure` 模板存在且 `diagnosticsPolicy.enabled:true` 时为 `true`。

App 向同一 Panel origin 的 endpoint 发送 read key 与严格结构化 payload，例如：

```json
{
  "version": 2,
  "type": "submission.summary",
  "data": {
    "success": true,
    "submittedCount": 3,
    "errorCount": 0,
    "unconfirmedPrintCount": 0,
    "missingMaterialTypeCount": 0,
    "newMissingMaterialTypeCount": 0,
    "recoveredMaterialTypeCount": 0,
    "networkAffectedCount": 0
  }
}
```

Worker 校验 read key、事件类型、字段集合与类型，使用相应 `eventTemplates` 项生成 `message`，再把 `{{message}}` / `{{type}}` 写入 provider body。Provider URL、私有模板和响应细节不会返回给 App。

提交汇总还必须由对应 profile 显式设置 `workflow.notifications.submissionSummary:true`；该对象或字段缺失时默认关闭。故障事件必须同时满足全局 `diagnosticsPolicy.enabled:true` 和 `runtime.failure` 模板存在；任一缺失都默认关闭。配置 adapter 本身不会自动开启这两类发送。

### v3：一轮提交的双 delivery（显式选择）

v3 只接受 `submission.round`。它要求同时配置两个彼此独立的私有 delivery：`summary` 用于每轮汇总，`problem` 用于符合条件的问题通知。两个 delivery 可以使用不同 provider URL、body、消息模板、成功标记与超时。完全虚构的示例：

```json
{
  "version": 3,
  "deliveries": {
    "summary": {
      "url": "https://summary.notifications.example.invalid/hooks/example",
      "method": "POST",
      "bodyTemplate": {
        "event": "{{type}}",
        "text": "{{message}}"
      },
      "messageTemplate": "Example round {{profileLabel}} at {{completedAt}}: submitted={{submittedCount}}, unconfirmed={{unconfirmedIdentifiers}}{{#if missingItems}}\n{{missingItems}}{{/if}}",
      "formatters": {
        "completedAt": {
          "type": "isoLocalSeconds"
        },
        "unconfirmedIdentifiers": {
          "type": "length"
        },
        "missingItems": {
          "type": "groupedCountList",
          "empty": "Example: none",
          "groupSeparator": "\n\n",
          "itemSeparator": "\n",
          "groupTemplate": "Affected {{count}}:\n{{items}}",
          "itemTemplate": "{{index}}. {{label}}"
        }
      },
      "successStatuses": [200, 202],
      "response": {
        "textContains": "\"example-summary-accepted\":true"
      },
      "timeoutMs": 8000
    },
    "problem": {
      "url": "https://problems.notifications.example.invalid/hooks/example",
      "method": "POST",
      "bodyTemplate": {
        "event": "{{type}}",
        "text": "{{message}}"
      },
      "messageTemplate": "Example round problem for {{profileLabel}}{{#if errors}}\n{{errors}}{{/if}}{{#if unconfirmedIdentifiers}}\n{{unconfirmedIdentifiers}}{{/if}}",
      "formatters": {
        "errors": {
          "type": "list",
          "empty": "Example: none",
          "separator": "\n",
          "prefixEach": "- "
        },
        "unconfirmedIdentifiers": {
          "type": "list",
          "empty": "Example: none",
          "separator": "\n",
          "prefixEach": "- "
        }
      },
      "successStatuses": [200],
      "response": {
        "textContains": "\"example-problem-accepted\":true"
      },
      "timeoutMs": 5000
    }
  }
}
```

Adapter 结构是 fail-closed 的：

- 顶层只允许 `version` 与 `deliveries`，`version` 必须为 `3`；`deliveries` 必须恰好提供 `summary`、`problem`，未知 delivery 无效。
- 每个 delivery 只允许 `url`、`method`、`bodyTemplate`、`messageTemplate`、可选 `formatters`、`successStatuses`、`response`、`timeoutMs`；除 `formatters` 外这些字段都必需，未知字段无效。
- `url` 是最长 2,048 characters 的绝对 HTTP(S) URL；正式环境使用 HTTPS。`method` 仅支持 `POST`、`PUT`、`PATCH`。
- `bodyTemplate` 必须是 JSON object 或 array，至少包含一次 `{{message}}`，可选 `{{type}}`。嵌套深度最多 8；每个 object/array 最多 100 项；字符串最长 4,000 characters；object field name 最长 128 characters。
- `messageTemplate` 是最长 4,000 characters 的非空字符串，只能使用 `{{type}}` 与下表列出的 `submission.round` 字段。未配置 formatter 时保持既有 v3 行为：数组按换行渲染，`missingItems` 项按 `label × affectedCount` 渲染。
- `successStatuses` 必须是非空、无重复的 HTTP status 整数数组，每项范围为 100–599。
- `response` 必须且只能包含非空 `textContains`，最长 256 characters。自定义 provider header 不受支持。
- `timeoutMs` 必须是 1,000–8,000 范围内的整数，并分别作用于对应 delivery。

`messageTemplate` 还支持受限条件块 `{{#if field}}...{{/if}}`。`field` 必须是下表中的固定 v3 data field；不支持 `else`、表达式、函数或动态路径，嵌套最多 4 层，整个模板最多 128 个模板 token。条件始终读取 App 已通过 schema 校验的原始 typed value：空 array/空 string、`0` 和 `false` 为 false；formatter 只改变占位符输出，不能让 `empty` 文本触发条件。

`formatters` 是“固定字段名 → formatter object”的可选 object，并且对每个 delivery 分别生效。例如，`summary` 可以把 `unconfirmedIdentifiers` 配成 `length`，而 `problem` 把同一字段配成 `list`。Formatter key 只能是下表中的字段，formatter object 只允许对应 type 的固定 key，未知 field、type、key、错误字段类型、缺失 option、超长文本或过深模板都会让整个 adapter 无效：

| `type` | 可用于 | 固定配置与输出 |
| --- | --- | --- |
| `length` | 六个 array 字段（含 `missingItems`） | object 只能含 `type`；输出 array 长度。 |
| `list` | 五个 string array 字段 | 必须显式提供 `empty`、`separator`、`prefixEach`；非空时保持输入顺序，为每项加 `prefixEach` 后用 `separator` 连接，空 array 输出 `empty`。 |
| `isoLocalSeconds` | `completedAt` | object 只能含 `type`；严格解析输入的 ISO offset datetime 后，直接保留该 offset 对应的墙上年月日时分秒，输出 `yyyy-MM-dd HH:mm:ss`。它不会转成 UTC，也不读取 Worker 时区。 |
| `groupedCountList` | `missingItems` | 必须显式提供 `empty`、`groupSeparator`、`itemSeparator`、`groupTemplate`、`itemTemplate`。按 `affectedCount` 分组并按 count 降序输出；同组项目保持输入顺序。`groupTemplate` 只允许 `{{count}}` / `{{items}}`；`itemTemplate` 只允许 `{{index}}` / `{{label}}` / `{{count}}`，其中 `index` 从 1 开始并在每个 count 组内重置。 |

`empty` 最长 512 characters；separator 和 `prefixEach` 最长 64；group/item template 最长 1,000。Group 与 item 使用独立 separator，避免把组间空行误用于组内。Formatter 子模板不支持条件块。最终渲染消息最多 128,000 characters；超过上限时该 delivery fail-closed，Worker 不向 provider 发送截断或部分内容。

v3 App request 顶层只允许 `version`、`type`、`data`，且必须是 `version:3` 与 `type:"submission.round"`。`data` 的全部字段都必需；缺失字段、未知字段、错误类型、超长值或超限数组都会被拒绝：

| `data` 字段 | 严格约束 |
| --- | --- |
| `success` | JSON boolean |
| `profileLabel` | 非空 string，最长 160 characters |
| `operatorLabel` | string，可为空，最长 160 characters |
| `completedAt` | 最长 40 characters；必须是有效公历日期时间，格式为 `YYYY-MM-DDTHH:mm:ss`，秒后可选 1–3 位小数，末尾必须是 `Z` 或 `±HH:mm`（offset 最大 `±14:00`） |
| `submittedCount` | 0–1,000,000 的整数 |
| `missingItems` | 最多 100 项；每项只能含非空 `label`（最长 160）与 `affectedCount`（1–1,000,000 的整数） |
| `newMissingItems` | 最多 100 个非空 string，每项最长 160 characters |
| `recoveredItems` | 最多 100 个非空 string，每项最长 160 characters |
| `errors` | 最多 100 个非空 string，每项最长 512 characters |
| `unconfirmedIdentifiers` | 最多 100 个非空 string，每项最长 128 characters |
| `networkAffectedIdentifiers` | 最多 100 个非空 string，每项最长 128 characters |

完全虚构的 request 示例：

```json
{
  "version": 3,
  "type": "submission.round",
  "data": {
    "success": false,
    "profileLabel": "Example profile",
    "operatorLabel": "Example operator",
    "completedAt": "2030-01-02T03:04:05Z",
    "submittedCount": 3,
    "missingItems": [
      {
        "label": "Example component",
        "affectedCount": 2
      }
    ],
    "newMissingItems": ["Example new component"],
    "recoveredItems": ["Example recovered component"],
    "errors": ["Example submission failure"],
    "unconfirmedIdentifiers": ["EXAMPLE-UNIT-001"],
    "networkAffectedIdentifiers": []
  }
}
```

发送规则：

- 每个通过校验的 round 都发送 `summary`。
- 仅当 `success:false`、`errors` 非空、`unconfirmedIdentifiers` 非空或 `networkAffectedIdentifiers` 非空时发送 `problem`。只有缺失、新增缺失或恢复项目时，不会单独触发 `problem`。
- 适用的 delivery 会独立、并行开始；一个 provider 的失败、超时或拒绝不会阻止另一个 delivery 尝试发送。
- 每个 delivery 只有在 HTTP status 命中自己的 `successStatuses`，并且 provider **原始响应文本**包含自己的 `response.textContains` 时才成功。匹配是 exact、case-sensitive 的普通文本包含判断；不解析 JSON，也不忽略大小写、空格或其他字符差异。
- `/api/notify` 的失败响应只会指出 `summary` / `problem` 哪个 delivery 失败，不会回显 provider URL、模板、成功标记或响应正文。

有效 v3 adapter 只让 App 收到：

```json
{
  "notification": {
    "version": 3,
    "enabled": true,
    "endpoint": "/api/notify",
    "eventTypes": ["submission.round"],
    "diagnosticsEnabled": false
  }
}
```

`/api/config` 不返回 `deliveries`、provider URL、body/message template、`textContains` 或 `timeoutMs`；`/catalog/*` 也不包含 `notificationAdapter`。App 只能看到同源 endpoint、通知版本、启用状态与允许的事件类型，以及与同一 snapshot `form-profiles.json.version` 相等的非敏感正整数 `catalogVersion`。新 App 应用后者拒绝跨 revision 混配；旧 App 忽略它。

### 迁移与默认状态

v2 继续受支持。读取旧 v1 adapter 时，Worker/Panel 只会将 transport 与 provider success policy 安全迁移到 v2，并加入通用 `submission.summary` 模板；保存后写回 v2。迁移不会创建 `runtime.failure` 模板，也不会打开 `diagnosticsPolicy`。

v1 或 v2 都不会自动迁移成 v3，公开仓库中的虚构 adapter 示例也继续使用 v2。升级 Panel 或 App 不会自动配置、发送或启用 `submission.round`。只有部署方在私有 `panel-settings.json` 中显式保存有效 v3 adapter，并让对应 profile 同时设置 `workflow.notifications.submissionSummary:true` 与显式 `profileLabel`，candidate App 才发送 v3 round。Panel 发布会交叉校验 adapter capability 与这些 profile 字段；缺项时拒绝发布。`/api/notify` 不接受旧的自由文本 body。

`notifyWebhook` 仅为旧 App 的迁移窗口保留；旧客户端可能直接收到并调用该 URL。新客户端不使用它。确认所有设备升级且 `/api/notify` 验证完成后，应清空该字段，避免继续暴露 provider 地址。

## Brand、更新源与请求覆盖

`brand` 只影响运行时文字，不改变 Android package、launcher label、图标或签名。

`updateOwner` / `updateRepo` 必须指向公开 GitHub Releases 仓库，因为 App 不携带 GitHub token。不要用更新仓库存放 private catalog 或内部 release notes。不需要 App 自更新时将两项留空。

Panel 可将更新仓库保存为严格的 `updateSource` v1，其唯一合法结构是
`{ "version": 1, "owner": "...", "repo": "..." }`。Panel 同时保留字节一致的 flat
`updateOwner` / `updateRepo`，所以 v1.0.4/v1.0.6 与新版 App 访问同一公开仓库。contract 整体
缺失时，新版继续读旧 flat 坐标；存在但损坏、不规范、含额外字段或与 flat 不一致时，Worker 仍
返回可用的 backend/form config，并只下发不含原值的 `{ "version": 0 }` sentinel。新版因此只
关闭更新检查，旧版仍读取 flat 坐标，config/catalog pair 不因更新字段损坏而失效。Panel 保存 API
会以 HTTP 400 拒绝同样的无效输入。

Channel、manifest asset 与 release tag 不属于 Panel contract。设备无已保存偏好时始终 stable，
只有本地值严格为 beta 时才走 beta；stable 继续使用 APK 的 `update.json` + `/releases/latest`，
beta 继续使用 `update.json` + `/releases/tags/beta`，APK 已显式提供的旧 channel 配置也原样保留。
私有只读审计已确认 signed v1.0.4/v1.0.6 均使用这组路由，仓库坐标由实际 Panel flat 字段提供；
审计记录不包含真实坐标。公开 `update-config.json` 只能保留中性协议默认，真实 owner/repo 必须留在
私有 Panel/catalog。

Panel-first 只是必要条件，不是发布充分条件。还必须通过公开历史/APK 脱敏、signed 原位升级、
真实 profile/payload replay、更新路由与远端副作用门禁；不能仅凭配置位置宣称升级等价或不会传错。

`minAppVersionCode` 必须是非负整数。Panel 将它保存在 App-facing settings，并派生到 `manifest.json`；只保存其他设置或 profile 时会保留现有值，不会重置为 `0`。提高它之前，先确认现场设备已有满足要求的可升级版本。

只有 backend 确实校验时才填写：

- `webOrigin`：完整 origin；
- `webReferer`：backend 期望的完整 referer。

它们只影响 App 到 backend 的请求。Panel 浏览器直连 backend 的 CORS 仍须由 backend 独立配置。

## 保存与生效

全新安装的 App 中 Panel 地址和 read key 都为空；保存并验证完整连接前，不含任何可用的正式 backend 或 profile。

1. 用获准 backend 账号登录 Panel。
2. 打开全局设置并编辑目标字段。
3. Panel 校验 adapter 与 JSON 结构。
4. 保存后检查私有 catalog 的三个文件是否写入预期仓库。
5. 让测试 App 同步 `/api/config` 与 catalog，再重新进入表单验证。

配置下载先进入 candidate slot，不会直接改写 active pair。严格更高 revision 的候选只要各自结构有效，就可以等待另一半到达，同时新的与已经打开的流程继续使用当前精确绑定的旧 active pair；例如先读到 config v8/catalog v7 时，App 会有界地整对重拉，但不会混用 v7/v8。相反，同 revision 内容变化、候选损坏、连接绑定错误或恢复状态不确定时会锁定新流程，不能借旧 cache 静默继续。匹配的新 pair 只在 Settings 安全边界一次性采用；不会回退到可编辑的 seed form。

手动保存的队列备份是跨天保留的恢复材料，不会在重启、退出登录、普通队列完成或候选下载后自动清除。备份会固定它所属的 active config/catalog pair，阻止新候选重解释待恢复工作；只有用户进入专门入口并明确确认“删除队列备份”，且删除成功后，才会释放这个 pair pin。删除失败时 App 保留原备份与锁定状态。

从 signed v1.0.4 或 signed v1.0.6 升级时，旧 cache 本身没有 Panel/key binding，不能直接信任。**Panel-first 是兼容迁移的必要条件，但不是发布或无感升级的充分条件**：先上线兼容 Panel、发布行为等价且 revision 递增的 private catalog，并在该 revision 发布后让旧 App **彻底退出再重新启动**，确认 config 与 catalog 均已同步；仅回到前台或等待不能证明启动时才刷新的 `/api/config` 已更新。`/api/config` 中的通用 cache proof 会绑定规范化 Panel origin、read-key SHA-256、精确 catalog 原始字节 SHA-256 和 revision。新版只在 proof 与当前保存连接和旧 catalog 原始字节全部相符时原地绑定。未完成预热、proof 缺失/不符或地址/key 已变更时，即使旧 `applied_version` 相同也必须在线重拉 config + catalog；系统不会根据相似内容、版本号或后来下载的 catalog 猜测旧 cache/草稿归属，遗留未绑定草稿会继续锁定。

首次从没有 v2 binding 的旧版覆盖安装后必须重新登录一次；旧 token、password、account、userName 和 web fingerprint 不会被读取或发送，草稿、队列、ledger 和待处理照片不会因此删除。同一 realm 完成 v2 登录后的 profile-only 更新会保留登录状态。

首次从 signed v1 覆盖安装当前版之前，还必须让旧 App 完全停止且没有相机结果待返回，并逐台确认 `settings` 中 `pending_a_step_photo_path`、`pending_a_step_photo_seq`、`pending_a_step_entry_photo_path` 三个旧槽位均可读且不存在。旧版安装器无法执行这项门禁；当前版只能在安装完成后保留这些证据并阻止下一次更新、Panel 切换和 candidate promotion。任一槽位存在或不可读时都不得首跳安装，也不得手工删除，必须先用兼容旧流程恢复，或停止该设备的发布。

App 的 HTTP transport 只对只读 GET 做有界兼容重试：DNS、连接、超时、TLS 或 502/503/504 等瞬时故障后等待 3 秒再试一次，合计最多两次；每次尝试都重新校验当前 Panel、session 与远程 worker gate。POST、multipart upload、OCR upload 与打印 POST 的 transport 调用仍各执行一次。业务 POST 只在 Panel-owned adapter/profile classifier 明确拒绝并证明未写入时有限重试，并复用完全相同的请求字节、目标绑定与已上传 URL，不重复上传。Profile 缺少相应字段时，上一工序 recipe 与业务提交各只尝试一次，网络额外重试和相邻条目等待均为零；结构化编辑器会把这些安全值与显式的本地核对记录保留天数写入旧 profile 草稿。发布门仍要求策略全部显式存在。

主流程/独立入口最终提交、按序的上一工序 recipe POST 和补打 POST 分别受持久化 fail-closed journal 保护。未知结果会锁定而不是重放；上一工序还保留已完成 recipe 前缀，且 `recipeMaxAttempts` 是跨重启/再次提交的每 recipe 持久累计上限。主流程/独立入口最终提交与上一工序的未知结果需要部署方先在原 backend 精确核对；本仓库目前没有操作员可用的受控恢复工具，因此这些状态不能被描述为已经可恢复，也不能手工删除。补打仅可由成功的精确绑定远端查询在同一上下文确认同一 job/SN 已被 adapter 分类为 printed 后自动清理。Multipart upload 另有持久防重放 barrier：首个 socket 前写入，正常本地终态后精确清理；开始上传后整单元网络重试、后续记录、profile/Panel 切换和 App 安装均被阻止。上传结果不明时同样保持锁定，直到部署方提供经过审查、与 backend 核对结果绑定的恢复工具；当前代码不会续传、清理孤立文件或使 backend 自动幂等。任何大于默认值的 recipe、业务或网络重试仍须按实际影响完成不确定结果测试。

## 不得存放的内容

持有 read key 的客户端可以读取 App-facing 配置，Panel 浏览器也会取得 authoring adapter 子集。因此这些配置中不得放入：

- backend 密码或长期用户 token；
- GitHub、AI 或其他服务 API key；
- Android keystore、签名密码或私钥；
- 用户记录、照片、日志或 API payload；
- 任何只应由服务端掌握的 secret。

真正 secret 使用 Cloudflare Secrets、backend secret store、CI secret store 或本地忽略文件。通知 provider URL 虽不是 App-facing，也仍属于私有部署配置，不得复制到公开仓库。
