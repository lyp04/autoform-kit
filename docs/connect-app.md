# 把 App 连接到 Panel

Panel 是表单与运行策略的主体，App 是通用执行框架。App 不内置可用的 deployment backend 或 private catalog；全新安装时 Panel URL 与 catalog read key 都为空。每台 device 必须先保存自己的 Panel 连接，之后真实 profile、adapter、通知能力、更新源和其他正式环境值才从私有 Panel/catalog 获取。公开仓库只提供不可联网的虚构示例。

## 1. 保存连接

在 App Settings 中填写：

- **Panel URL**：已部署 Worker 的 HTTPS base URL（正式环境要求）；
- **Access key**：与 Cloudflare `CATALOG_READ_KEY` 完全相同。

这里的 HTTPS 是部署要求，不是当前手工输入路径的完整运行时保证：手工设置不强制 HTTPS，也不会
拒绝 `http://` 地址；release 构建使用的 Android manifest 还为旧集成兼容允许 cleartext traffic。
一次性 pairing 链接则只接受 HTTPS origin。正式 Panel、backend、通知和文件端点必须全部使用
HTTPS。若以后关闭 cleartext，应作为独立兼容迁移验证，而不能假定现有 APK 已经拒绝所有 HTTP
地址。

保存后 App 异步请求：

- `/api/config`：`backendAdapter`、brand、notification proxy、update source 和其他 App-facing setting；
- `/catalog/manifest` 与其 `profilesUrl`：catalog metadata 与 profiles。

App 只向设置的 Panel host 和 same-host redirect 发送 read key。Manifest 指向不同 host 时，App 不会转发 key，受保护的 payload 下载将失败。因此 Cloudflare `PUBLIC_URL` 与 device Panel URL 必须使用同一 host。

App 还预留了从授权下载页取得一次性票据、经用户确认后自动填写同一组设备本地连接值的入口。当前 Panel 尚未实现票据发行与兑换端点，因此该入口不能端到端启用，现阶段仍以本节的手动设置为准。下载页不得把 Panel URL 或长期 read key 写入 APK；完整的 URI、兑换、存储和未来 Panel 发行合同见 [App 一次性配对协议](./app-pairing.md)。

## 2. 同步与 fallback

同步自动发生：

- App 启动时；
- App 回到前台时，受节流间隔限制；
- 保存新 Panel connection 后立即触发。

Config 与 catalog 分别原子写入 candidate slot，并绑定到 Panel URL + access key；`/api/config.catalogVersion` 还必须与 catalog 根 `version` 是同一个正整数。严格更高 revision 的候选只要每一半各自结构有效，就可以等待完整配对，同时新的和已经打开的流程继续使用精确绑定的旧 active pair。若发布过程中先读到 config v8/catalog v7，App 会在有界短延迟内整对重拉，不受普通前台节流阻塞，也绝不会混用 v7/v8。候选完整后只在 Settings 安全边界一次性采用。若候选是同 revision 内容变化、损坏、连接绑定错误，或存在不确定恢复状态，则新流程保持锁定，不能借旧 cache 静默继续。

从 signed v1.0.4 或 signed v1.0.6 覆盖升级前，应先部署兼容 Panel 并发布行为等价、revision 递增的 private catalog，让旧 App 至少完整同步一次。这里的“完整同步”必须是 **Panel 发布目标 revision 后彻底退出并重新启动旧 App**，再确认 config 与 catalog 都是该 revision；仅把旧 App 切到前台、等待或只看到 catalog 刷新不算完成，因为这两个版本只在启动时刷新 `/api/config`。新版 Panel 的 `/api/config` 会附带通用 cache proof：请求的 Panel origin、read-key SHA-256、精确 catalog SHA-256 与 revision；旧 App 会原样保留未知字段。新版 App 只有在 proof、当前保存的 Panel/key、旧 catalog 原始字节及 config/catalog revision 全部一致时，才把这一对旧 cache 原地绑定，因此预热成功的设备可在升级后离线恢复同一表单。proof 缺失、旧 App 未同步、地址/key 改变或任一摘要/revision 不符时，新版不会猜测归属，首次启动仍须联网重拉完整 pair 后才开放产线操作；遗留的未绑定草稿也会保持锁定，不能把这条 fail-closed 路径称为无感升级。发布验证必须同时覆盖预热离线路径和未预热 fail-closed 路径，不能笼统承诺任意旧 cache 都能离线升级。

首次从没有 v2 binding 的旧版覆盖安装后，App 不读取或发送旧 token、password、account、userName 或 web fingerprint，操作员必须重新登录一次。该要求不删除草稿、队列、ledger 或待处理照片；完成 v2 登录后，同一 realm 的后续 profile-only 更新会保留登录状态。

没有可采用的新候选时：

- 当前连接有完整、有效且精确绑定的 active config/catalog pair：继续使用这一对旧 cache；
- 同 revision 候选变异、候选损坏、绑定错误或恢复状态不确定：保持同步锁定，不回退到 active pair 或可编辑 seed；
- 已配置 Panel 但没有可用 active pair，或 active pair 任一半缺失、未绑定、revision 不同、schema 过新或 config 仍是旧结构：保持同步锁定；
- 完全未配置 Panel：可以显示 bundled fictional seed 作为预览，但所有远程操作始终关闭。

手动保存的队列备份可以跨重启、退出登录和多天后继续恢复。它会固定创建时的 active pair，避免下载的新候选重解释这份工作；普通队列完成或重新同步不会释放这个 pin。只有在专门入口明确确认删除，并且 App 成功删除备份后，pin 才释放；删除失败会恢复并保留原备份。

因此 App 能打开不代表新配置已生效。正式使用前核对目标 profile、version 和 backend test，确认不是 fallback seed 或另一部署的旧 cache。

## 3. Backend 登录

App 根据 `backendAdapter` 获取 challenge、构造 login request 并解释 response。Account、password 和 challenge 直接发往 deployment backend，不经过 Panel Worker。

Adapter 缺失、不支持或基础字段不完整时，App 会显示 configuration error，不会使用公开源码中的默认 endpoint。请在 Panel 修正 adapter、保存并重新同步。

登录成功后，App 保存 backend token，并只调用 adapter 与当前 profile 明确声明的 upload、submit 和 optional operation。安全边界见 [Deployment security](./security.md)。

## 4. 表单入口与 profile 验证

App 的标准表单选择器只显示 `pickerVisible:true` 的来源 profiles。来源 profile 在
`workflow.alternateEntries` 中显式声明的每个有效独立入口，会作为该来源表单页的单独按钮
显示；它提交到 `targetProfileId` 唯一引用的 `pickerVisible:false` 隐藏目标。隐藏目标不出现在
选择器中，App 也不会根据入口标题、profile 顺序、模板或其他业务值猜测来源和目标。

首次连接至少检查：

1. Brand 与 private catalog 相符；
2. 可见 profiles 的名称、顺序和颜色与 Panel 一致；
3. Hidden profile 不出现在 picker；每个已启用独立入口只在其明确来源 profile 上显示，并只绑定它唯一引用的 hidden target；
4. 使用 dedicated non-production account 完成 login；
5. Scan policy、result choices、conditional value 和 localized labels 正确；对配置离散长度的主表单和每个已启用独立入口，分别用 OCR、barcode 和 entered 验证当前 effective `allowedLengths` 中的每个允许值都通过，相邻未允许长度都被拒绝；即使同时保留单一 `expectedLength` / `expectedSnLength` 给旧 App 作 fallback，新 App 也不得用它覆盖允许列表；
6. Photo slot 标题、顺序与数量边界正确，`defaultPhotoOrder` 的 Panel 结构化选项与 App 实际顺序一致；
7. 分组拍摄切换提示使用当前框和下一框的实际标题；
8. 新导入的可见 `singleChoice` 字段在 Panel 中先保持空值与 `reviewRequired:true`，明确选定提交值后才发布；
9. 用 fictional record 完成 upload 与 submit；
10. 逐一验证上一工序（含 recipe 响应分类/重试）、重复记录、打印（含手动允许状态/确认）、缺失恢复、提交/网络重试和通知中每个显式开启的策略；
11. 重复日期严格按 adapter 的 transform order / epoch unit / pattern / time-zone source / 可选兼容策略解释，打印任务按精确标识与 numeric job ID 关联；
12. 若配置 `dailyStatsV2`，逐项验证 profile/result pair 的归类、flat summary 展示，以及 flat 数值不会再次加入今日总数；同名 result key 在不同 profile 的行为必须分别验证；若还配置 `dailyStatsAlternateEntries`，每个 source profile / entry pair 只进入它明确引用的 group / flat summary，且不污染普通 result 计数；
13. 迁移验证中确认旧 App 继续使用未改动的 v1 `dailyStats`，新版 App 优先使用有效 v2，删除 v2 后新版 App 回退 v1；
14. Offline、wrong key 和 expired token 不会向意外 host 发请求。

## 5. Notification proxy

新 App 从 `/api/config` 读取 versioned notification capability。下面是 v2 示例；v3 使用 `version:3` 与 `eventTypes:["submission.round"]`：

```json
{
  "catalogVersion": 42,
  "notification": {
    "version": 2,
    "enabled": true,
    "endpoint": "/api/notify",
    "eventTypes": ["submission.summary"],
    "diagnosticsEnabled": false
  }
}
```

`catalogVersion` 是与这次 `/api/config` 同一 Git snapshot 中 `form-profiles.json.version` 完全一致的正整数。新 App 只把 adapter/settings 与相同 catalog version 配对；严格更高且各自有效的未配对 candidate 等待整对时继续使用旧 active pair，同 revision 变异、缺失或无效 candidate 则锁定新工作。旧 App 会忽略这个新增字段。

App 只把受支持的 event type 和该类型的严格结构化 allowlist 发回同一 Panel origin；它不发送自由文本 `message`，也不会收到或直接调用 provider URL。v2 Worker 使用私有 `eventTemplates`；v3 Worker 使用彼此独立的 `summary` / `problem` delivery。完整字段表见[全局配置](./configuration.md#notificationadapter)。

v2 `submission.summary` 只含聚合计数，并且仅在当前 profile 显式设置 `workflow.notifications.submissionSummary:true` 且 `eventTypes` 包含该事件时发送；配置缺失默认关闭。`runtime.failure` 还要求 Panel 全局 `diagnosticsPolicy.enabled:true` 和对应私有 event template，任一缺失时 `diagnosticsEnabled:false`。v3 `submission.round` 还要求该 profile 显式提供 `workflow.notifications.profileLabel`；Panel 在发布时交叉校验 adapter capability 和所有已启用 profile，不允许 App 运行后再静默丢通知。

测试时确认：

- 没有有效 adapter 时 `enabled:false`，App 不排队发送；
- 正确 read key 才能调用 `/api/notify`；
- provider 不可用时 App 不会获得 provider URL 或 raw response；
- 未知 event、未知字段和自由文本 `message` 均被拒绝；
- 提交汇总和 diagnostics 的独立开关在缺失时均保持关闭；
- payload 已用 fictional data 验证字段白名单和 Panel template 渲染。

旧 v1 adapter 会自动迁移成只提供通用 `submission.summary` 的 v2 adapter；不会自动创建 `runtime.failure` template、v3 delivery 或开启 diagnostics。`/api/notify` 根据私有 adapter version 严格接受 v2 `submission.summary` / `runtime.failure` 或 v3 `submission.round`，不会跨版本猜测或转换 App 请求。

`notifyWebhook` 只支持迁移期旧 App。完成 device upgrade 后应在 Panel 清空它。

## 6. 多语言与照片提示

App UI 支持 `zh`、`en`、`es`。Profile 的 `titleI18n`、`nameI18n` 和普通 `labelI18n` 随语言切换。结果按钮优先读取 `operatorLabelI18n` / `operatorLabel`，缺失时回退旧版/导入 `labelI18n` / `label`；Panel 编辑操作员文案不会改写旧 App 仍读取的结果标签或提交值。

照片切换提示同样使用当前语言，并将 profile 中当前 slot title 与 next slot title插入通用模板。修改 Panel 标题后，无需编译 App 即可改变提示中的两个名称。

## 7. 提交与重试边界

App 的 HTTP transport 只对只读 GET 做有界兼容重试：DNS、连接、超时、TLS 或 502/503/504 等瞬时故障后等待 3 秒再试一次，合计最多两次；第二次尝试会重新校验当前 Panel、session 与远程 worker gate。POST、multipart upload、OCR upload 与打印 POST 的 transport 调用仍各执行一次。业务 POST 只有在 Panel-owned adapter/profile classifier 明确拒绝并证明未写入时才能按显式策略有限重试；重试复用完全相同的请求字节、目标绑定和已经上传的 URL，不再次上传照片。上一工序 recipe 必须由独立的 `operations.previousSteps.recipeOutcomePolicy` 授权，最终提交必须由 `operations.submit.outcomePolicy` 授权；两者不能互相替代。新发布请求多次尝试却缺少相应规则时会被发布/提交门拒绝；仍可兼容读取的旧配置若缺 policy，runtime 有效上限收敛为一次。普通 success 不受 policy 缺失影响，业务 non-success 则不能自动重放。

从旧 App 过渡时必须先让 Panel 同时提供旧版 flat config 与新版 `backendAdapter`，且 backend base、endpoint 和 session-invalid policy 必须等价；Panel 会拒绝保存或下发互相矛盾的两套配置。`/api/config` 的旧版 endpoint/session 字段由同一个已解析 adapter 派生，避免旧、新 App 在迁移窗口请求不同地址或使用不同登录失效规则。这种 session policy 等价只用于一致地判定会话失效，不是副作用 POST 的 outcome proof。旧 cache 本身没有新绑定标记；只有旧 App 已从目标 Panel 完整同步同 revision config/catalog，且 config 中的 cache proof 与当前 Panel/key 和 catalog 原始字节全部吻合时，新版才支持离线覆盖升级。未预热或 proof 不匹配的设备首次启动仍须联网同步，并在完成前保持锁定。

Panel 可在 `workflow.previousSteps` 显式设置有限 recipe 尝试/等待，并在 `workflow.submission` 设置有限业务尝试与网络额外重试。存在性 GET 只有精确命中私有 adapter 的 `missingResponseCodes` 或 `missingMessagePatterns` 才可进入纠正/创建；未分类业务失败停止且不发 POST，即使其消息恰好含有 `timeout` 或连接失败字样，也不能被整单元网络策略重试。手动“检查上一工序”与正式提交共用同一内部 side-effect gate：profile 必须已完成兼容核对，lookup/upload/submit 及实际使用的动态能力必须完整。扫描预检同样先要求已核对 workflow 与 lookup classifier，因为后续纠正、移除、要求附件或阻止都属于本地 side effect；候选超时只报告检查失败，不得折算为 missing 次数或动作。

`recipeMaxAttempts` 是每个精确 recipe 位置跨进程、设备重启和用户再次提交的持久累计上限，不会因重新进入批次而重置。新 App 只有在业务 non-success 命中 `recipeOutcomePolicy.retryableNotWrittenRules` 时才消耗下一次；只有命中 `alreadyExistsAcknowledgedRules` 时才确认同一 recipe 已完成。Legacy `retryableMessagePatterns` 与 `alreadyExistsMessagePatterns` 只供旧 App 兼容，不授权新 App，也不会自动迁移成结构化规则。Outcome evidence 发布门只检查实际可执行路径：`previousSteps.enabled:true`、`triggerResultKeys` 非空且 `templates` 非空必须同时成立；这种路径请求 `recipeMaxAttempts>1` 时要求非空 retryable rules，仍保留非空 legacy already-exists 数组时要求非空 acknowledged rules。关闭、无 trigger 或无 template 的存量 recipe 不会仅因保存了字段而触发该门。缺少有效 recipe policy 的旧配置即使保存了更大的上限，新 App 的 runtime 也收敛为最多一次；普通 success 仍可完成，但业务 non-success 不会因 legacy 文案变成重试或成功。

Candidate App 分别为主流程/独立入口最终提交、按序的上一工序 recipe POST 和手动/自动补打 POST 保存 fail-closed journal。每条记录都在 socket 调用前绑定 Panel/catalog/backend/profile、目标、会话/策略和精确 payload 摘要。上一工序 journal 保留已完成 recipe 前缀，直到本单元终态与最终提交回执可靠落盘。主提交只有明确成功才完成，后续尝试只由 submit `outcomePolicy` 明确证明未写入后授权；上一工序只由独立 `recipeOutcomePolicy` 授权重试或确认 already-exists。两种 policy 的每条 rule 对非空 code/message selector 取 AND，rules 之间取 OR；message 只读取 `response.messageFields` 已配置路径上的 scalar 值，不扫描对象、数组、data、请求回显或完整响应。同一 recipe non-success 同时命中 retryable 与 already-exists 两组属于冲突并保持 `UNCERTAIN`。

Panel-owned session-invalid policy 只说明会话需要失效，不能证明已经开始的副作用 POST 未写入。Socket 调用后命中 configured session-invalid HTTP 状态、业务码或消息时，App 会先把对应 journal 标记为 `UNCERTAIN`；重新登录不会把它改写成明确拒绝、清除 journal 或自动重放。遗留 `POSTING`、transport/解析失败、分类冲突或未分类响应也都保持 `UNCERTAIN`，禁止重放与切换 profile/Panel/catalog。主流程/独立入口最终提交与上一工序没有自动核销路径，必须先在原 backend 精确核对。本仓库目前没有操作员可用的受控恢复工具，因此未实现工具前继续锁定，不能手工删除或声称已经可恢复。Printing v1 没有“明确未受理” classifier，所以补打 POST 只有 configured success 才直接完成；补打例外仅限精确绑定的远端状态查询：响应成功、同一远端上下文返回同一 job ID 与 SN、且 adapter 将状态分类为 printed 时，App 可原子清理同一条未解决记录，其他结果均不清理。

未完成主队列本身使用单独的 v3 snapshot binding：connection namespace、catalog revision、immutable profile 与实际 backend 提交契约共同生成稳定摘要。App 在恢复、首次 upload/上一工序动作和最终 POST 前都要求完全匹配；不匹配时不上传、不 POST，也不自动删除草稿。旧 App 的无绑定草稿只有在上述预热 cache proof 已通过并生成跨启动、pair-hash-bound migration receipt 时才会先持久化升级后恢复。未完整预热的设备即使在线同步成功，也不会把旧草稿自动套到新 catalog；应恢复原预热 pair，或由操作员明确丢弃该草稿。

Multipart upload 使用单独的持久防重放 barrier：首个 socket 前写入精确上下文摘要，上传开始后本次整单元网络重试关闭；正常本地终态精确清锁，失败、超时、进程退出或清锁失败则跨重启锁住后续记录、profile/Panel 切换和 App 安装。Barrier 不保存返回 URL，不会续传、删除孤立文件，也不会让 backend 自动幂等；本仓库目前也没有用于核销该不确定状态的操作员恢复工具。部署方提供经过审查、与原 backend 核对证据绑定的工具之前，该状态必须保持锁定。没有对 upload、recipe 和 submit 全链路的幂等/去重证明时仍须保持网络额外重试为 `0`。连接测试必须覆盖“upload 已到达 backend，但客户端收到超时”、前/后/附加/slot/上一工序/独立入口的部分上传、进程退出、登录失效和本地存储失败；generic App 不会猜测不确定的远端结果。

## 8. App 更新

Panel global `updateOwner` / `updateRepo` 指向公开 GitHub Releases repository。真实更新源坐标属于私有 Panel 配置，不应硬编码在 App 或提交到公开示例。App 匿名读取 release metadata 与 `update.json`，不会携带 GitHub token。

设备本地保存 stable/beta channel；设置页当前没有普通通道选择器，在 2.5 秒内连续点击“中文”
5 次会切换 stable/beta 并立即检查更新。无本地值时仍按旧规则使用 stable，只有严格的 beta 值才走
beta。APK 的中性 `update-config.json` 提供 enabled、`update.json`、stable `/latest` 与 beta tag
协议，release build flag 可整体关闭 capability。Panel 的 exact `updateSource` v1 只复制
owner/repo，并同时保留相同 flat 字段给旧 App；它不能覆盖 channel/tag/manifest asset。真实仓库
坐标不得写进公开源码。

把坐标移入 Panel 是必要边界，但不足以证明可发布；仍需 signed 原位升级、精确路由、真实
profile/payload replay、远端副作用和公开历史/APK 脱敏证据。

Update repository 不得存放 private catalog 或 internal release note。APK signing certificate 必须连续，否则 Android 不会将其作为已安装 App 的升级。详见 [Releasing](./releasing.md)。

## 常见问题

### 保存后连接失败

- 确认 Panel URL 使用 HTTPS 且 device 可达；
- 确认 read key 与 Cloudflare secret 一致；
- 确认 `/api/config` 返回 supported adapter；
- 检查 TLS、DNS、proxy 和 firewall。

### 仍显示旧 profile

- 完全退出并重新启动 App；
- 确认 manifest `version` 已递增；
- 检查 `schemaVersion`、`minAppVersionCode` 与 payload SHA-256；
- 确认 Panel 写入目标 private catalog。

### Profile 不在选择器中

在 Panel 检查该 profile 是否显式设置 `pickerVisible:true`，发布并让 App 重新同步。不要通过 App source 添加绕过入口。

### 显示 sample profile

未配置 Panel 时 sample 是不可联网预览。若已保存 Panel 却仍看到同步提示，说明当前连接的 config/catalog 至少一项尚未通过绑定与结构校验；修复 Panel/key/manifest/profile 后重新保存或等待两路同步完成，成功后 Settings 会自动刷新，无需重启。不要把 sample 当作 deployment success。

### 登录或提交提示缺少配置

按 error 中的 logical field 回到 Panel 补齐 `backendAdapter`。若 profile 启用了 optional workflow，还要确认对应 operation 和 endpoint 存在。不要修改 App source 增加 deployment fallback。

### 重复记录日期无法判断

在 private `backendAdapter.operations.duplicateCheck` 检查 `dateFields`、有序 `dateTransforms`、`epochUnits`、`dateFormats` 与 `timeZone`。若使用 `epochDigitLengths`、`numericFractionPolicy`、`textParseConsumption`、`plausibilityScope`、`timeZoneSource`、`rootValueEnabled` 中任一项，必须把六项全部配置；否则全部省略以保留当前严格行为。App 只应用显式允许的文本转换、数字单位和兼容策略，不自行猜测；已有记录的日期无法解析时会阻止提交。

### 找到错误的打印任务或无法补打

关闭 profile 打印，检查 adapter 的 `serial` 是否与主标识精确一致、`acceptedTypes` 是否足够窄、三个状态集合是否非空且互不重叠，以及 job ID 是否为正数且按时间单调递增。当前 v1 只读取配置起始页并以同一标识下最大 numeric ID 作为最新任务。
