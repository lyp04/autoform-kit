# 自定义项总表

本项目的原则是：**Panel 是配置主体，App 是通用运行框架。** 任何会随表单、后端协议或运行流程改变的值，都应通过 Panel 写入独立私有 catalog，再由 App 同步；修改这些值不应要求重编 APK。

公开仓库只保留通用实现与虚构示例。真实 profile、adapter、通知、更新源和其他生产配置只能存在于私有 catalog、Cloudflare、设备本地设置或 ignored build configuration。下表列出当前入口及明确预留的未来入口；表外的运行时差异不应写进 App 源码。

## 配置位置

| 自定义内容 | 管理入口 | 实际存放位置 |
| --- | --- | --- |
| Profile 名称、顺序、可见性、模板与字段，以及导入后暂不显示的 `snPluginsHidden` | Panel profile editor / advanced JSON | 私有 `form-profiles.json` |
| 导入/旧客户端兼容 metadata：profile `brand` / `model` / `color` / `graded` / `discovery` | Template import / advanced JSON（不是新增运行策略入口） | 私有 `form-profiles.json` |
| 已签名旧客户端在迁移期仍会读取、但尚未纳入公开 schema 的 opaque legacy extensions（仅原样保留，不得猜测、新建或公开；清理前必须完成旧 App replay） | Panel advanced JSON | 私有 `form-profiles.json` |
| Result key、标签、颜色、提交值、条件字段与 staged `perGrade` alias | Panel profile editor / advanced JSON | 私有 `form-profiles.json` |
| 照片框标题、多语言、数量边界与 `defaultPhotoOrder` 拍摄顺序 | Panel profile structured editor / advanced JSON | 私有 `form-profiles.json` |
| 旧 App 的正反面 `uploadFields` 映射 | Panel advanced JSON（仅迁移兼容，不用于新建 profile） | 私有 `form-profiles.json` |
| 单选/多选提交值；新导入可见 `singleChoice` 的人工确认 | Panel preview / profile editor | 私有 `form-profiles.json` |
| 扫描规则与上一工序的附件、静态/动态 recipe、source alias、标识纠正、缺失处理、提交尝试和复核策略 | Panel profile editor / advanced JSON | 私有 `form-profiles.json` |
| 独立入口、隐藏目标、结果/照片/覆盖字段、扫描长度作用入口、toggle 文案/生命周期与 live override provider | Panel profile structured editor / advanced JSON | 私有 `form-profiles.json` |
| 重复检查阈值与近期/可重录记录动作 | Panel profile editor / advanced JSON | 私有 `form-profiles.json` |
| 打印启用、预检动作、轮询、批末缺失任务复查模式、未知状态呈现、自动/手动补打、最终复核与未确认动作 | Panel profile editor / advanced JSON | 私有 `form-profiles.json` |
| 提交前列表刷新、材料缺失恢复、本机提示、提交次数、等待、连续失败与网络重试 | Panel profile editor / advanced JSON | 私有 `form-profiles.json` |
| v2/v3 Profile 提交通知开关与 v3 显式显示标签 | Panel profile editor / advanced JSON | 私有 `form-profiles.json` |
| Backend 协议、field conversion、optional operations、上一工序 recipe outcome policy、上一工序与独立入口 live resolver/builder、响应文案、重复日期解析、打印协议，以及 release-only `operations.recovery` 公钥/证据 capability | Panel global `backendAdapter` | 私有 `form-profiles.json` |
| 通知 provider URL、body、v2 event template，或 v3 `summary` / `problem` message template、formatter、响应标记与超时 | Panel global `notificationAdapter` | 可选的私有 Worker-only `panel-settings.json`；未配置 Worker-only setting 时可不存在 |
| 最小化 runtime diagnostics 总开关 | Panel global `diagnosticsPolicy` | 私有 `form-profiles.json` |
| 旧 App 登录页汇总的 result-key 分组、顺序、标签与颜色 | Panel global `dailyStats` structured editor | 私有 `form-profiles.json` |
| 新版 App 登录页汇总的精确 profile/result pair 分组、独立录入入口归属、扁平汇总、顺序、标签、颜色与显式 legacy 计数归属 | Panel global `dailyStatsV2` / `dailyStatsAlternateEntries` structured editor | 私有 `form-profiles.json` |
| 可选 AI 辅助编辑的临时 instruction、draft 文案与翻译建议；只有经校验、审核并发布的最终字段才成为运行配置 | Panel optional AI editor | 私有 draft；发布后最终字段进入私有 `form-profiles.json` |
| 设置页标题的 Brand 前缀、版本化公开更新源 `updateSource` v1（仅 owner/repo，并保留旧 App 所需的同值 flat 字段）、最低 App version、request override，以及迁移期旧 flat backend/`notifyWebhook` | Panel global settings | 私有 `form-profiles.json` + 派生 `manifest.json` |
| 默认 GitHub catalog repository/branch、可选 `CATALOG_R2` bucket binding 与受审 cutover、Worker identity/routes、Panel public URL、`CF_VERSION_METADATA` binding、精确 source-tag deploy/runtime provenance、AI endpoint/model | Cloudflare deployment config | Cloudflare 与受控私有部署证据 |
| 默认/迁移期 GitHub token、catalog read key、AI key，以及 private catalog 建立前的 bootstrap adapter | Cloudflare variables / Secrets | Cloudflare |
| 哪些账号可以 author/publish，以及 Panel 与 login 前 metadata 是否还受 edge/network 限制 | Backend authorization + edge/network policy；Panel 没有独立 RBAC | 部署方 backend / edge |
| 下载页公开/受控发行策略、短期一次性 ticket audience、applicationId allow-list、原子消费与限流 | 下载 Worker policy + Panel pairing issuer/redeem；完成 Worker secrets/binding 与设备 E2E 前入口必须禁用 | Cloudflare secret、SQLite Durable Object 临时/摘要状态；不得进入 APK、catalog 或公开下载 URL |
| Panel URL、catalog read key、操作员语言、每个 Panel/独立入口上次选择的来源表单，以及 stable/beta update channel（在 2.5 秒内连续点击“中文”5 次切换） | App Settings | 单台设备本地 |
| 可选跨 App session peer allow-list | Android trust-boundary source（默认空；启用前必须增加 signer verification） | APK build |
| Package identity、launcher name、icon、签名、version、SDK/release feature gates、中性的 update protocol defaults，以及 cleartext transport 兼容策略 | Android build/release config | Source 与 ignored local secrets |

`manifest.json` 由 Panel 随每次 catalog 发布自动生成，不是手工自定义入口。`panel-settings.json`
只有在存在 Worker-only setting 时才创建；显式 absence 是合法 authority 状态，不能用虚构空文件替代。
三类 authority 文件的精确副本可以进入访问受控的 private backup 或 release evidence，但不得进入
public source、tag、Release、artifact 或日志。直接编辑 private catalog 文件不是另一套推荐入口；
需要手工 JSON 时仍应从 Panel 的 advanced JSON 进入并经过同一个发布校验器。

正式 Panel、backend、通知与文件端点必须使用 HTTPS，但当前手工 Panel 地址输入并不强制该协议，
也不会拒绝 `http://` 地址；release 构建使用的 Android manifest 还为旧集成兼容保留了 cleartext
traffic。一次性 pairing 入口则只接受 HTTPS origin。是否继续保留 cleartext 是 APK build/release
边界，不是可下发的 Panel 业务自定义项；将来收紧时必须作为单独的兼容迁移处理，不能把“运营要求
HTTPS”误写成现有代码保证。

Catalog authority 是部署状态，不是表单运行值：未绑定 `CATALOG_R2` 时 GitHub 为权威；绑定但
尚无有效 `catalog-current-v1.json` 时只从 GitHub 读取并拒绝所有发布；有效 pointer 建立后 R2
成为唯一正常读写权威。Pointer、snapshot、SHA-256 或 version 任一异常都 fail closed，不回退
GitHub。首次 R2-only publish 后 GitHub 可能过期，移除 binding 或直接从 GitHub 回滚前必须恢复
并验证 exact R2 current bytes。当前仓库没有面向操作员的 seed CLI/API，不能只靠打开 binding
完成迁移；受审的一次性 private tooling 是部署自定义内容的一部分。

## Panel 全局设置

- `backendAdapter`：Panel、Worker 与 App 共用的版本化 backend contract；
- `notificationAdapter`：只由 Worker 使用的 provider contract；v2 使用 `eventTemplates`，v3 使用两个私有 `summary` / `problem` delivery，并可为固定 round 字段配置受限 formatter；
- `diagnosticsPolicy`：最小化结构化故障事件总开关，缺失或 `enabled:false` 时关闭；
- `dailyStats`：给旧 App 保留的可选 result-key 汇总，迁移期间保持原值；
- `dailyStatsV2`：新版 App 的可选精确汇总；selector 结构化选择 profile/result pair，groups 组成总数，flat summaries 只附加显示；仅由独立录入供数的 flat summary 可把普通 selectors 留空，但必须与同 id 的非空 `dailyStatsAlternateEntries` 映射原子保存，缺失 v2 时新版 App 回退 v1；
- `dailyStatsAlternateEntries`：按显式 source profile / entry identity 把独立录入计数归入已有 v2 group 或 flat summary；与普通结果计数分离，不能从入口标题或 backend result 推断；
- `brand`：设置页标题前缀；不改变 launcher 或普通表单页标题；
- `updateSource` v1：公开 GitHub Releases 更新源，只允许 `version:1`、`owner`、`repo`；Panel 同时保留同值的 `updateOwner` / `updateRepo` 供旧 App 读取；
- `minAppVersionCode`：允许应用新 catalog 的最低 Android versionCode；
- `webOrigin` / `webReferer`：backend 明确要求时的 App request override；
- profile 顺序。

`backendAdapter.operations.recovery` 也是 Panel-owned 可选项：它只保存版本、签发模式、算法、key ID、验证公钥、证据时效、reconciliation contract 摘要和允许的 operation 列表。它不保存签名私钥、远端核对结果，也不等于 App 已有操作员恢复入口；当前 runtime 尚未接线时只能用于 release gate 的 capability 声明，不能据此人工清锁。

会话失效 HTTP 状态、业务状态与文案信号，以及 App web-session 的 User-Agent / Accept-Language，都放在 `backendAdapter`。App 不为这些部署行为提供隐藏默认值。Session-invalid 信号只触发会话失效处理，不能证明已经开始的副作用 POST 未写入，也不能授权重试或清除 journal。旧扁平 backend 字段和 `notifyWebhook` 只用于迁移，不应继续作为新部署的配置模型。详见 [全局配置](./configuration.md) 与 [后端适配器](./backend-adapter.md)。

通知 v2 继续受支持，旧 v1 adapter 也只迁移到 v2。v3 `submission.round` 不会由 v1/v2 迁移、Panel 升级或 App 升级自动配置、发送或启用；启用前必须在私有 `panel-settings.json` 中显式提供两个 delivery，并在测试环境验证 App 工作流与两个 provider 响应契约。完整字段、边界与发送条件见[全局配置](./configuration.md)。

启用 `backendAdapter.operations.previousSteps` 时必须显式声明 `missingResponseCodes`、`missingMessagePatterns`、`retryableMessagePatterns` 与 `alreadyExistsMessagePatterns`；四者可为空。前两组仍供新 App 对存在性 GET 做精确 missing 分类，未分类失败不会触发创建 POST；后两组 legacy recipe 文案数组只供旧 App 兼容，不能授权新 App 重试或确认 already-exists。新 App 使用独立的 `operations.previousSteps.recipeOutcomePolicy`，其证据和规则不能由最终提交的 `operations.submit.outcomePolicy` 代替，也不会从 legacy 数组自动生成。

Recipe outcome 发布门只针对实际可执行路径：`workflow.previousSteps.enabled:true`、`triggerResultKeys` 非空且 `templates` 非空必须同时成立。这样的路径请求 `recipeMaxAttempts>1` 时要求非空 `retryableNotWrittenRules`；同一路径仍为旧 App 保留非空 `alreadyExistsMessagePatterns` 时要求非空 `alreadyExistsAcknowledgedRules`。关闭、无 trigger 或无 template 的存量配置不触发该门。只有 profile 启用动态 `template_detail` recipe 时，才额外要求私有 `recipeResolvers`、`optionValueBuilders` 和 template-detail capability；静态 recipe 不需要动态 resolver，但实际可执行时仍受相同 outcome gate。

独立入口 live override 使用 `operations.templateDetail.alternateEntryResolvers`，并由 profile provider 显式绑定 toggle、目标 identity/step、唯一输出 field。两种动态 DSL 都只有 allow-list selector/builder，没有 regex、eval、JSONPath 或任意表达式；引用必须闭合。启用 `backendAdapter.operations.duplicateCheck` 时必须显式声明有序 `dateTransforms`、`epochUnits`、`dateFormats` 与 `timeZone`；转换数组可为空，`epochUnits` 可只允许秒、只允许毫秒，或明确同时允许两者。额外六项日期兼容策略可整体省略，但不能只填一部分；公开示例显式写入与省略时相同的严格值。Panel 对旧 adapter 字段提供有限迁移读取，但在完整补齐必需契约前拒绝发布相应 enabled profile，这不等于 live catalog 已自动迁移。打印开启时，adapter 还必须声明精确标识字段、可接受任务类型、互不重叠且非空的状态集合，以及可按数值单调排序的 job ID。完整约束见[后端适配器](./backend-adapter.md)。

## Panel profile 设置

- Identity 与访问：`id`、`displayName`、`searchText`、`pickerVisible`、`uiColor`；
- Backend template：`template`、`snFields`、`requiresSecondSn`、提交字段与 option value；
- 输入：`snPlugins` 中的框名、输入提示及各自 `labelI18n` / `placeholderI18n`，以及 `photoSlots`、`optionalSlots`、`choiceFields`、`operationFields`、`conditionalFields.perResult`、混合版本迁移期的等值 `perGrade` alias、`materialGroups`；新模板导入的可见 `singleChoice` 使用空 `value` 和 `reviewRequired:true`，管理员在 Panel 明确选择后才清除标记，发布门拒绝仍待确认的值；
- Result mapping：legacy wire name `gradeMap`，但 key 是任意不透明 result key；`label` / `value` 保留旧版与导入契约，结构化编辑器只用可选 `operatorLabel` / `operatorLabelI18n` 定制新版操作员文案，并可设置结果颜色；
- 扫描：`expectedSnLength`、profile migration fallback `scanner`、`snPlugins[key=primary|secondary].scanner`；role scanner 可定义单一 `expectedLength`、离散 `allowedLengths` 及各自应用于 OCR / barcode / entered 的 source scope，prefix、文字识别 timing、纯数字拒绝、候选来源/评分、label 匹配、字符与排除规则、归一化以及 `prompt` / `promptI18n` 提示；额外输入当前不声明相机扫码；
- Legacy-compatible missing-item ignore policy：`notifySkipMaterials`（不自动移除、重试或通知）；
- 上一工序：`workflow.previousSteps` 总开关、扫描预检、结果排除/触发、只对 trigger 子集生效的 `directCreateResultKeys`（跳过创建前查询但保留创建后复核）、附件及其 Panel-owned `uploadNameTemplate`、静态 recipe，或动态 recipe 的 template identity、resolver ID 与 source alias、标识字符替换、适用 result key 及应用动作、大小写策略、缺失次数/动作、recipe 尝试/等待与复核次数/等待；
- 迁移核对：`workflow.compatibilityReviewed`；Panel 不会自动勾选，只有逐项对照旧现场行为后才能设为 `true`；
- 照片运行策略：结构化“默认拍照顺序”控件直接写 `defaultPhotoOrder`，只允许按照片框分组或按条目逐个完成，并同步更新预览和 advanced JSON；`workflow.photos.includeOptionalSlots` 决定 `optionalSlots` 是否进入 App 拍摄和提交，旧部署迁移默认关闭；
- 独立入口：`workflow.alternateEntries` 的总开关、入口标题/多语言、唯一隐藏目标、目标 result/photo field、照片数量与 URL 分隔符、Panel-owned `uploadNameTemplate`，以及独立入口的 `scanner.applyExpectedLengthTo` / `scanner.applyAllowedLengthsTo` source scope；长度数值仍只定义在来源 profile 的 primary role scanner。`applyExpectedLengthTo` 是非空兼容 scope；可选 `applyAllowedLengthsTo` 缺失时继承来源允许长度 scope，显式配置时只覆盖该独立入口。两者都可用结构化入口控件编辑；Advanced JSON 的精确路径为 `workflow.alternateEntries.entries[].scanner`，“恢复继承”会删除可选的 allowed-scope override。任一显式 scope 都必须是非空、无重复的 `ocr` / `barcode` / `entered`；显式 allowed scope 还要求来源 primary scanner 存在非空 `allowedLengths`，否则 Panel 拒绝发布；这些字段存在但类型、范围或交叉约束错误时 App 也 fail closed。入口还配置只允许“明确未写入”时复用同一 payload/已上传 URL 的 `submissionRetry`；新建入口的文件名模板默认留空，必须由管理员在结构化控件中显式确认，否则不能发布；新建入口的 `submissionRetry` 也默认为 `1/0`，不会因打开编辑器就隐式启用任何业务 POST 重试；固定 override 与 toggle 的 `label` / `labelI18n`、默认值和保留周期也由 Panel 提供；可选 `resultPresets` 可把入口显示为 Panel-owned 的互斥结果按钮，底层代码、代码是否显示、加号是否独占一行、文案、多语言、颜色、默认项、保留周期、结果、底层 toggle 及字段覆盖都不写死在 App；`maxPhotos:0` 明确保留旧入口不限张数的行为；可选 live provider 通过 `dynamicOverrideFields` / `dynamicOverrideProviders` 显式绑定 toggle、目标 template/step、resolver 与唯一输出 field，不使用时两数组都必须为 `[]`；独立路径的重复检查、上一工序和打印 flags 固定关闭；
- 重复记录：`workflow.duplicateCheck` 的年龄单位/数值、未知日期动作、近期动作和达到阈值后的动作；
- 打印：`workflow.printing` 的 profile 总开关、预检动作、确认轮询、批末缺失任务复查模式、未知状态呈现、自动补打上限、最终复核等待、未确认动作、手动补打开关、允许状态与再次确认开关；
- 材料与提交：`workflow.materials.refreshBeforeSubmit`、`missingRecovery`、本机提示，以及 `workflow.submission` 的业务尝试次数、重试等待、相邻条目等待、本地核对记录保留天数、连续失败上限和有限网络重试；
- 通知：`workflow.notifications.submissionSummary`；v3 还必须提供显式 `profileLabel`，缺失时不能发送 round；
- Item recognition：`materialCodePattern` 和每项 `defaultQty`。

需要用户从标准表单选择器进入的每个来源 profile 都必须显式设置 `pickerVisible:true`。只作为独立入口提交目标的 profile 必须显式设置 `pickerVisible:false`，并由来源 profile 的 `workflow.alternateEntries.entries[].targetProfileId` 精确引用；Panel 不按名称、相邻顺序或模板 ID 猜目标。完整结构见 [Profile schema](./profile-schema.md)。

缺少新策略字段的旧 profile 在解析/编辑阶段按安全值展示：独立入口和其他可选模块关闭、重复记录阻止、打印关闭、缺失恢复关闭、提交一次、网络额外重试和相邻条目等待为零、本地核对记录只保留 1 天。安全值不是无损迁移；新 App 的提交门要求既有完整显式策略和 `compatibilityReviewed:true`。为保持已发布 catalog 可升级，旧 catalog 单独缺少打印的 `batchEndRecheckMode` / `unknownStatusPresentation` 时仍按当前 `deferred_missing_two_pass` / `distinct` 运行；字段存在但非法会被 App 拒绝，Panel 的任何新发布也必须显式补齐两者。用结构化编辑器打开旧 profile 时，Panel 会把默认值写入草稿；管理员仍应按旧现场行为逐项校正和验证后再发布。

App 的 HTTP transport 只为只读 GET 提供有界兼容重试：DNS、连接、超时、TLS 或 502/503/504 等瞬时故障后等待 3 秒再试一次，合计最多两次；第二次尝试前重新校验当前 Panel、session 与远程 worker gate。POST、multipart upload、OCR upload 与打印 POST 在 transport 层都只执行一次。业务 POST 的有限重试不是 transport retry：只有 Panel-owned adapter/profile classifier 明确拒绝并证明该请求未写入时才允许，而且同一业务请求必须复用完全相同的请求字节、目标绑定与已上传 URL，不重新上传照片。

`previousSteps.recipeMaxAttempts > 1` 只在上一工序自己的 `recipeOutcomePolicy.retryableNotWrittenRules` 命中时生效；`submission.maxAttempts > 1` 只由最终 submit 自己的 `outcomePolicy` 授权。Recipe policy 的每条 rule 对非空 code/message selector 取 AND，rules 之间取 OR；message 只读取 `response.messageFields` 已配置路径上的 scalar 值。缺少有效 recipe retry rules 时新 App 每个 recipe 位置最多发送一次，legacy `alreadyExistsMessagePatterns` 也不会被当作成功。同一 recipe non-success 同时命中 retryable 与 already-exists 两组属于冲突。

最终提交、上一工序 recipe 和补打 POST 都有独立 fail-closed journal；transport、解析、分类冲突、未分类结果或 socket 后命中的 session-invalid 信号都会进入持久化 `UNCERTAIN` 并停止，不由整单元网络策略重放。重新登录不会把 journal 改写为明确拒绝、清锁或自动重放。最终提交与上一工序需要后端侧核对后才能恢复，但本仓库目前没有提供执行该核对并安全清锁的受控恢复工具；在部署方实现并审阅工具前必须保持锁定，不能手工删除状态。补打仅在成功的精确绑定远端查询返回同一 job/SN、且 Panel-owned adapter 分类为 printed 时自动清理同一条记录。

Multipart 图片 upload 是不会创建最终表单的准备动作，允许在 profile 的有限整单元网络重试预算内重新上传；App 不再创建持久 upload-only barrier，并会清理旧版留下的该 key，所以图片响应丢失不会跨重启阻止后续记录、profile/Panel 切换或 App 安装。最终 submit、上一工序 recipe 与补打 POST 仍分别使用持久 journal；只有业务 POST 的 classifier、幂等或去重语义得到验证后，才提高对应 POST 的非默认重试值。

动态 recipe 与独立入口 live provider 都是迁移工具，不是标题猜测开关。发布前必须在受控私有环境保存最小化的真实 `templateDetail` replay corpus，逐条证明 identity、字段与 option cardinality、required/omit、照片 alias、toggle on/off 和最终 payload 与当前 App 一致，并证明 provider 全部在任何 upload/POST 前完成或 fail closed。为了完成等价校验，template identity、SKU、field、option 等必要机器值必须原样保留，因此 corpus 本身仍是敏感资料；只剥离 token/cookie/header、真实 SN、照片、不相关用户记录以及不参与解析的文案。Corpus 只能存在于 ignored/private test storage，不能提交到公开仓库。未完成主流程接线或 replay 时保持 provider 为空、`workflow.compatibilityReviewed:false`。

## Cache 与手动队列备份不是自定义入口

App 下载的 config/catalog candidate 不是可编辑的第二份配置。严格更高 revision、单独校验有效的 candidate 等待另一半或等待安全边界时，App 可以继续使用完整旧 active pair；同 revision 内容变化、损坏、错绑或恢复不确定的 candidate 会锁定新工作。两种状态都不能靠手改设备文件处理。

“保存当前队列”产生的手动备份用于隔天恢复，会长期保留并绑定创建它的精确 Panel/catalog/backend/profile 语义。只要该备份存在，active pair 就被 pin 住，新 candidate 不能替换它。提交完当前内存队列、退出、登录切换、重启或再次下载均不删除备份；只有设置中的“删除队列备份”在用户明确确认且所有镜像确实删除后才解除 pin。删除失败会保留或恢复原备份。

## 仍在 Panel 之外的少量设置

这些值在 Panel 或 App 获取远程配置之前就必须存在：

- **首次引导 adapter**：私有 catalog 尚未初始化时，Worker 从 `BACKEND_ADAPTER_JSON` 获取 Panel 登录所需 contract；登录后由 Panel 保存正式副本。
- **旧 Cloudflare adapter 输入**：`BACKEND_API_BASE` 与 `BACKEND_SESSION_PROOF_CODES` 仍只为既有部署迁移读取；新部署不得依赖它们，完整值写入 `BACKEND_ADAPTER_JSON` / private `settings.backendAdapter` 后应删除旧输入。
- **Panel 管理授权**：Panel 没有独立 RBAC；可 author/publish 的账号由 backend user-info authorization 限制，额外的 Panel/metadata 可见性由 edge 或 network policy 限制。
- **设备连接**：新装 App 的 Panel URL 与 read key 都为空；管理员可以在 App Settings 手动填写。App 与 Panel 已实现一次性配对协议，但它只有在独立下载 Worker 完成发行、两端配置同一独立 issuer secret、Panel Durable Object migration 生效且真实设备 E2E 通过后才能启用；不能把长期 read key 写入页面或 Intent。下载页可以公开发行，也可以要求自己的授权会话；公开模式不代替 backend 登录，但表示部署方主动把 shared Panel connection credential 提供给任意访客。
- **设备本地选择**：操作员语言、每个 connection namespace + 独立入口上次选择的来源表单，以及 stable/beta update channel 保存在单台设备，不改变 backend/profile contract。未完成草稿仍以草稿中的精确来源 ID 为最高优先级；来源被删除、重复或失效时不会回退到第一项继续。设置页当前没有普通通道选择器，需在 2.5 秒内连续点击“中文”5 次切换并立即检查更新。缺少本地 channel 时仍按旧规则使用 stable，Panel 不提供默认通道。
- **Cloudflare / catalog infrastructure**：Worker 必须先知道默认私有 GitHub repository、public URL 与 service credentials；可选 R2 只有在精确 seed 和 current pointer 校验完成后才成为 catalog authority，不能由 Panel 表单运行值临时切换。正式部署还必须保留 `CF_VERSION_METADATA` binding，通过精确 source tag 发布，并把 live runtime provenance 纳入私有发布证据。
- **Android identity 与签名**：package、launcher name、icon、certificate 和 version 属于安装包本身。
- **Android capability / protocol defaults**：`AUTO_UPDATE_ENABLED`、`CROSS_APP_SESSION_ENABLED`、SDK，以及 `update-config.json` 的 enabled、manifest asset、stable `/latest` 与 beta tag 路由属于 APK。公开 release source 只能保留中性默认；deployment-specific owner/repo 写入私有 Panel，Panel 不能覆盖 channel/tag/asset。
- **可选跨 App session allow-list**：这是 Android 安装包级 trust boundary，不能由远程配置放宽。

除这些 bootstrap、infrastructure、设备本地选择、package identity 与明确列出的 build trust/protocol 边界外，运行时差异应留在 Panel。旧 `uploadFields`、flat backend 字段和 `notifyWebhook` 只是迁移兼容面，不是新部署的第二套配置模型。

Panel-first 是必要架构边界，但不是发布充分条件。仍须独立证明公开历史/APK 已脱敏、signed
upgrade 保留数据与路由、所有真实 profile 的 payload replay 一致，以及远端不确定写入不会重放；
不能用“配置已移入 Panel”替代这些证据。

## 不得写入公开源码

- 真实组织、客户、地点、表单或流程名称；
- 真实 backend、Panel、notification、catalog 或 repository URL；
- 真实 route、header、response field、business value 或 error text；
- 真实 template、warehouse、SKU、item、serial identifier；
- token、password、API key、signing material、log、photo 或 API payload；
- 从 live Panel 导出的任何 catalog 或截图。

范围包括 source、test、fixture、documentation、commit message、branch、tag、Release notes 和 assets。缺少部署配置时，公开代码应清楚报错或保持可选模块关闭，不应内置某个部署的 fallback。

## 发布前检查

- Private catalog 与 public source repository 完全分开。
- `form-profiles.json` 与 `manifest.json` 存在当前 private authority；`panel-settings.json` 在有 Worker-only setting 时存在，否则以显式 absent 状态取证。
- Catalog 文件的受控副本只用于 private backup/evidence，权限与访问范围已审阅；public source、history、tag、Release、asset、日志和截图中均不存在 filled copy。
- `CATALOG_READ_KEY` 已设置，匿名访问受保护 route 返回 `401`。
- Backend/edge 已把 Panel author/publish 权限限制到获准管理员；没有把“能通过任意 backend 登录”误当作 Panel RBAC。
- Worker 通过带 `CF_VERSION_METADATA` 的受控入口部署，live runtime provenance 精确匹配本次审阅的 source commit；source tag 不替代 bundle/build attestation。
- App 没有原生可发现的配对入口；若启用下载页动作，已确认下载 Worker 的公开/受控发行策略，并验证独立 issuer secret、Panel SQLite Durable Object migration、exact audience、短期/单次/并发失败与真实设备打开/确认/同步证据。缺少任一技术证据时下载页隐藏或禁用发行/打开动作，手动连接继续有效。
- `notificationAdapter`、v2 私有 `eventTemplates` 与 v3 私有 delivery 细节不出现在 `/api/config` 或 `/catalog/*`；新 App 只收到版本、同源代理 endpoint、启用状态与可用事件列表。唯一例外是 old-App migration 的 legacy `notifyWebhook`：迁移期仍会出现在 App-facing config，所有旧设备升级后必须清空。
- v2 提交汇总只在 profile 显式开启，diagnostics 只在全局 policy 与 `runtime.failure` template 均显式开启；缺失配置默认关闭。
- 若显式选择 v3，`summary` 与 `problem` 都已配置，`timeoutMs` 均在 1,000–8,000 内，provider success status 与原始响应 `textContains` 已逐一验证；未完成验证时保持 v3 未启用，已有部署可继续使用经验证的 v2。
- 所有标准选择器来源 profile 都有 `pickerVisible:true`；仅作独立入口目标的 profile 明确为 `false`。
- 每个独立入口只引用一个 `pickerVisible:false` 的隐藏目标；result、photo、override 与 toggle 引用全部闭合，三个副作用 flags 均为 `false`。
- 混合新旧 App 的 conditional field 同时保存深度相等的 `perGrade` / `perResult`；删除的旧分支已被 replay 证明不可达并只在私有报告中记数。
- 若独立入口启用 live provider，toggle、四项 live identity、唯一 field/option、builder、输出 allow-list 与 upload 前 fail-closed 顺序均已用私有 fixture 验证。
- 上一工序存在性 GET 的私有缺失码/消息已用脱敏响应 replay 验证，且未分类业务失败已证明不会改变 SN、执行缺失动作或发送创建 POST。
- 每个启用的 profile module 都有对应 backend operation 与 endpoint。
- 新导入的可见 `singleChoice` 已由管理员逐项选择，`reviewRequired` 全部为 `false`；没有把第一项当成隐式默认。
- `defaultPhotoOrder` 已通过结构化控件明确选择，预览、advanced JSON 与 App 字段一致。
- 手动队列备份仍需保留时，已确认它 pin 住当前 pair；只有确实不再需要时才执行带确认的删除。
- 最终提交、上一工序和 upload 若可能进入不确定状态，发布计划明确记录“当前无受控恢复工具”；在工具实现和审阅前不得以手工清锁代替。
- App 已同步目标 catalog，不再显示 fictional fallback seed。
- Full Git history、tags 和 Release assets 已扫描，不含部署数据。

## 现有部署的分阶段升级

不要同时替换 Panel、私有配置和所有设备。推荐顺序：

1. 备份 private catalog、Worker 配置、当前签名 APK 与签名证书信息；先部署能读取旧 catalog、且新字段缺失时保持安全关闭的新 Panel。该兼容只为迁移，不是无损保证。
2. 只在 private catalog / Cloudflare 中补齐 `backendAdapter` 和每个 profile 的完整策略；把旧 App 已有行为写成等价的显式值，只关闭真正新增的功能，并检查 Panel 生成的完整 diff。
3. 目标 revision 发布后，彻底退出并重新启动旧 App，验证它缓存的 config proof 精确绑定当前 Panel origin、read-key 摘要、原始 catalog 摘要和同一 revision；仅回到前台或看到 catalog 更新不算预热。再用测试设备验证新 App 的等价行为与预热后离线覆盖升级。proof 缺失或任一输入不符时保持锁定并联网同步，不猜旧 cache 或草稿归属。
4. 使用相同签名证书发布新 App；首次从没有 v2 binding 的旧版覆盖安装后预留一次重新登录，旧 credential 不会被读取或发送，草稿、队列、ledger 和待处理照片不会因此删除；确认现场设备同步已迁移 catalog 后，再逐项开启真正新增的 workflow，最后清理迁移字段。

任何阶段失败都应回到前一份已验证的 private catalog 或 Worker 部署；不要用公开示例替代真实配置，也不要把 private catalog 复制进本仓库作为备份。
