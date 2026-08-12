# 部署安全与公开仓库边界

本页描述当前实现的 trust boundary 与上线检查。它不是对 deployment backend、Cloudflare、GitHub、Android device、notification provider 或 AI provider 的安全保证；部署者仍须完成自己的 threat model 与测试。

漏洞报告方式见根目录 [SECURITY.md](../SECURITY.md)。

## 1. Public source 与 live Panel 必须分离

Panel 是正式表单、策略和集成配置的主体，App 只是通用执行框架。公开 repository 中的 `panel/` 是通用 control plane code，不是任何部署的 live state。真实 profile、backend adapter、通知设置、更新源坐标、业务分类器和其他正式环境值必须留在部署方私有 Panel/catalog；公开仓库只保留不可运行的虚构示例。Panel 的 authority 位于独立 private catalog repository，包含两个必选文件和一个可选状态：

- `form-profiles.json`：App-facing settings 与 profiles；
- `manifest.json`：version、hash 和 payload URL；
- 可选 `panel-settings.json`：存在 Worker-only settings 时保存 provider adapter 等内容；未配置时合法地不存在。

以下位置都属于 public surface，不能出现 deployment data：

- 当前 tree 与已删除文件；
- commit、diff、message、branch、tag、Release 与 asset；
- test fixture、snapshot、screenshot、recording、log 和 issue；
- fork、cache、CI artifact 或 mirror。

发现误传后，先吊销或轮换 credential、保存必要事件证据，再净化完整 Git history，并处理 remote tags、Releases、fork 和 cache。只在新 commit 删除内容不能完成脱敏。

## 2. Cloudflare 配置

| 配置 | 安全要求 |
| --- | --- |
| `GITHUB_TOKEN` | Fine-grained token，只授权 private catalog 的 Contents read/write。只放 Cloudflare secret。 |
| `CATALOG_READ_KEY` | 正式环境必须设置高熵值。它是 shared read credential，不是 per-user authorization。 |
| `APP_PAIR_ISSUER_KEY` | 与下载 Worker 共享的独立高熵 server-to-server credential；不能复用 read key 或 browser/backend token。 |
| `BACKEND_ADAPTER_JSON` | 首次引导值。通过 read key 后其 authoring subset 会返回浏览器，不能当作 secret。 |
| `AI_API_KEY` | 仅在审查并启用 AI 时设置；限制额度和权限并定期轮换。 |
| `GITHUB_REPO` / `PUBLIC_URL` | 使用 deployment vars；不要回填 public example。 |

一次性 App 配对还要求 SQLite-backed `APP_PAIR_TICKETS` Durable Object、精确
`APP_PAIR_APPLICATION_IDS` allow-list 和 60–600 秒 TTL。issuer 与 redeem 任一 prerequisite 缺失都会
fail closed。Panel 只在 Durable Object 中保存 ticket/access-key 摘要、exact audience、状态及不可逆
限流 identity；明文 ticket 只出现在一次发行响应、当前下载页/Intent 与 App 内存，明文 access key 只在
Worker secret/runtime、成功兑换响应和 App connection store 中出现。issuer route 不能从浏览器直接调用，
下载 Worker 也不能把 issuer secret 发送到页面。下载 Worker 可以由部署方明确配置为公开或受控发行；
公开模式不提供用户授权，并意味着任意访客都可取得 shared Panel connection credential。

### Read gate 是 fail-open

当 `CATALOG_READ_KEY` 未设置时，shared read check 允许匿名访问。影响包括：

- `/catalog/*`；
- `/api/config`；
- `/api/profiles` 的 read-key path；
- `/api/panel-config`；
- `/api/runtime-provenance`；
- `/api/notify`。

最后一项意味着匿名请求可能使用已配置的 notification proxy。正式环境必须设置 key，并验证：无 credential 与错误 credential 返回 `401`，正确 credential 只得到目标 deployment 的 App-facing contract。轮换后旧 device 应失去同步与代理权限。

持有 read key 的人能读取 App-facing settings 和 profiles，也能调用 notification proxy。因此它应进入 device-management 与 incident-response 流程，但仍不能替代 backend account authorization。

## 3. Catalog 与 notification 隔离

`panel-settings.json` 位于 private catalog，但 Worker 不通过 `/catalog/*` 提供它。`notificationAdapter` 中的 provider URL、method、provider body template、Panel-owned event templates 和 success policy 不出现在 App `/api/config`；App 只收到同源 endpoint 与可用事件：

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

`catalogVersion` 是同一 immutable snapshot 中公开 catalog 的正整数 version，不是 secret；它只用于让新 App 拒绝 adapter/settings 与 profile revision 混配。

App 请求只允许 `version:2`、受支持的 `type` 和对应的精确 `data` object；顶层或 `data` 中的未知字段以及自由文本 `message` 都会被拒绝。当前白名单为：

- `submission.summary`：success boolean 和提交、错误、未确认打印、缺失项类型、新增缺失类型、恢复类型、网络影响的非负聚合计数；不包含 profile、账号、记录或 item 明细。
- `runtime.failure`：受限的 stage/error code/subphase/fingerprint/build 标识，以及 Android SDK 和最小化 network capability boolean；不包含调用方 message/context、账号、记录、host、URL、路径、backend response、log、breadcrumb、crash text 或 stack trace。

Worker 使用私有 `eventTemplates` 把白名单字段渲染成 message，再将它填入私有 provider body template。Worker 使用 15-second timeout，向 provider 发送 JSON，并对 HTTP status 和可选 response code 做校验；failure response 不应泄露 provider URL 或 raw body。

Profile 的 `workflow.notifications.submissionSummary` 必须显式为 `true` 才会发送提交汇总，缺失时关闭。`runtime.failure` 必须同时有对应事件模板和全局 `diagnosticsPolicy.enabled:true`，两项均默认关闭。旧 v1 adapter 的自动迁移只生成通用提交汇总模板，不会创建故障模板或打开 diagnostics。

`runtime.failure` 是可丢弃的诊断事件，不假设 provider 或 relay 幂等。App 在调用 notification client 前，先用 `AtomicFile` 同步移除队首物理行并读回核对完整剩余队列；移除、同步或核对失败时不会尝试 socket。timeout、5xx、进程终止或其他不确定结果都不会把该事件重新入队，因此同一队列事件最多尝试一次；代价是异常路径中允许丢失诊断。畸形行一次只原子清理该物理行，不能按“已解析事件序号”误删后面的有效事件。Panel/key generation 在 dequeue 前后检查，连接切换与实际发送使用同一互斥边界。队列事件的 `ts` 表示故障被观察到的时间；本地 `lastAttemptMs` 表示事件交给 notification client 的时间（不代表送达）；`lastSentMs`/兼容 getter `lastUploadMs` 只表示收到 2xx 的时间。4xx 继续记录配置错误并终止该事件，status 0、3xx、5xx 或本地 transport failure 记录 transport error 并停止本轮 drain；这些结果都不重放已移除事件。

Provider adapter 不支持 custom headers。如果 provider 要求 header credential，应使用独立 server-side relay，而不是把 credential 放进 App-facing config。即使 provider URL 只在 Worker path 可见，它仍是 private deployment metadata，不应进入 public source。

`notifyWebhook` 只为旧 App migration 保留，旧客户端可能直接获得 provider URL。完成升级后应清空它。

## 4. Host、same-origin 与 transport

- Panel SPA 与 Worker API 使用同一 origin 和相对 route。
- `PUBLIC_URL` 写入 manifest `profilesUrl`，必须与 device Panel URL 使用同一 host；App 不向另一 host 或 cross-host redirect 发送 read key。
- Panel browser 直连 backend。Backend CORS 只允许预期 Panel origin、method 和 header。
- Android manifest 当前允许 cleartext traffic 以兼容集成；正式配置仍应只使用 HTTPS。
- Browser backend token 位于 Panel origin 的 `localStorage`；任何 same-origin XSS 都可能读取它。使用受控 domain、TLS 和最少第三方 script。

`/api/panel-config` 在 backend login 前、read-key validation 后提供 challenge/login/template authoring 所需 adapter subset。持有 read key 的访问者可看到 backend host、authoring endpoint、field 和 conversion mapping。若这些 metadata 也必须隐藏，请增加 network/edge access control；把 adapter 放进 Cloudflare secret 并不会改变 bootstrap response。

## 5. Credential 与 session

### Panel browser

- Account、password 和 challenge 直接发送给 backend，不经过 Worker。
- Backend token 保存在 Panel origin，并随 protected Panel API request 发送。
- Worker 调用配置的 user-info endpoint 验证 token；backend 仍必须执行真实 authorization。
- Shared device 使用后要执行 backend logout/token revocation，并清理 site data。

### Android App

- App 的 realm-bound v2 store 使用 Android Keystore + AES-GCM 保存 token/password 密文，密文字节位于 `MODE_PRIVATE` preferences；没有 plaintext fallback。当前 App 不读取或发送 v1 legacy credential bytes，因此首次从没有 v2 binding 的旧版覆盖安装后必须重新登录一次；普通升级只为原 signer 回滚保留旧 bytes，显式登出或 Panel/read-key 切换才按对应边界清除。重新登录不删除草稿、队列、ledger 或待处理照片。
- Read key 与 Panel URL 是 device-local connection setting。使用 MDM、screen lock、disk encryption、debug restriction 和 lost-device revocation。
- 不要假设 rooted 或不受管 device 上的 local secret 无法提取。

### Optional cross-App session

Session peer allow-list 默认为空。启用后，列出的 package 可通过 exported provider 读写 token；当前 boundary 主要依赖 package allow-list，而不是 certificate pinning。启用前应增加 signer verification 并专项审计，不能通过远程 Panel 放宽。

## 6. Private catalog 与 GitHub

- Catalog repository 必须 private，并与 public source repository 分开。
- Fine-grained token 只授权该 repository，不授予更大范围。
- 开启 audit、branch protection 与最小成员权限。
- Template、SKU、item、field、serial rule、result value 和 workflow policy 都可能是 private operational metadata；没有 password 不代表可以公开。
- Worker 通过 Git Data API 在一个 commit 中写完整 catalog，并用非强制 branch ref update 发布。发布前保留可回滚 version，并检查目标 repository / branch。

## 7. AI data egress

AI 是 optional。设置 `AI_API_KEY` 后，以下 authoring data 可能发送到 `AI_BASE_URL`：

- Template field ID、type、title、option 和 required state；
- 完整 profile draft；
- 管理员 instruction；
- 待翻译或精简的 label。

Normal flow 不会把 App submission 或 photo 用于 AI，但 template/profile 本身可能包含 private process metadata。启用前确认 provider、region、retention、training policy、contract 和 data classification。不接受 data egress 时不要设置 `AI_API_KEY`。

Field-reference check 与 runtime-policy preservation 不能替代人工审阅；发布前检查完整 profile。

## 8. Notification 与 diagnostic data

App 不发送自由文本诊断。故障事件使用精确字段白名单，仅包含受限的阶段/错误/build 标识、Android SDK 和最小化网络能力状态；调用方 message/context、记录标识、账号、backend 数据、host、URL、路径、log、breadcrumb、crash text 与 stack trace 不会进入 v2 queue 或请求。通知 endpoint/key 只从 Main 已原子采用的 config/catalog 同 revision 内存快照解析，不独立读取可能只更新一半的磁盘 cache；检查与实际发送闭包同一个快照。切换 Panel/key 时，旧诊断 queue、dedupe 和待 flush generation 会同步失效并清除，不能交给新 provider。升级时缺少显式 connection marker 的既有 queue 默认不受信任并清空；旧的自由文本诊断 queue 也不会通过 v2 transport 重放。

- 只配置受控 HTTPS provider 或 relay；
- Receiver 应 authentication、rate limit、限制访问并设置最短 retention；
- 使用 fictional data 检查 Panel 私有 event template 渲染结果与 provider body；
- 不需要 remote diagnostics 时保持 `diagnosticsPolicy.enabled:false`，并省略 `runtime.failure` template；
- 不要扩展事件白名单来传 raw log、photo、token、record、完整 API response 或其他业务数据。

## 9. Fallback seed

全新安装的 Panel URL 和 read key 都为空，只能显示禁用远程操作的 fictional seed 预览。已有连接时，config/catalog 下载先进入 candidate slot；严格更高 revision 且各自结构有效的候选可以等待配对，同时新的和已经打开的流程继续使用当前精确绑定的旧 active pair。App 不读取半更新 cache，也不混用 revision；匹配的新 pair 只在 Settings 且无草稿、队列、拍照任务或 pair pin 的安全边界一次性采用。同 revision 内容变化、候选损坏、错误连接绑定或恢复状态不确定时则锁定新流程，不能借旧 active pair 静默继续。没有可用 active pair、任一半未绑定/缺失、active revision 不同、schema 过新或 config 结构过旧时同样保持锁定，不会把 seed 当作可编辑产线表单。

signed v1.0.4 与 signed v1.0.6 的历史 cache 本身没有连接 binding。**Panel-first 是兼容迁移的必要条件，但不是发布或无感升级的充分条件**：先升级 Panel 并发布行为等价、revision 递增的私有 catalog，再让旧 App 在发布后彻底退出并重新启动以预热同一 revision。旧 `/api/config` 必须保存由 Panel 生成的规范化 origin、read-key SHA-256、精确 catalog 原始字节 SHA-256 与 revision proof，并与当前设置和旁边的原始 catalog 字节全部相符。任何缺项/不符都不得根据相似内容、旧 `applied_version` 或后来取得的 catalog 猜绑定或原地盖章；首次启动必须在线重新获取当前 Panel 的 config 与 catalog，且两路 revision 相同后才解锁。预热离线路径和未预热 fail-closed 路径都要做签名覆盖升级验证。

主队列草稿还要独立绑定其创建时的 connection namespace、`catalogVersion`，以及当前 immutable catalog profile 与实际生效 backend adapter/endpoint/header 提交语义的 canonical SHA-256。恢复前、首次 upload/上一工序 side effect 前和最终业务 POST 前都会重新核对。唯一允许的自动跨 revision 路径是：设备处于设置页安全边界、没有相机/上传/提交/打印/上一工序 journal 或其他远端副作用，且完整候选来自同一 connection、版本严格递增、profile ID 完全相同；此时新 pair 可先启用，草稿在恢复前把新 binding 持久化，保留原队列与照片。跨 Panel、跨 profile、同版语义改变、回滚、binding 畸形或写入失败仍禁止恢复并保留原草稿。旧 App 写出的无绑定 v1/v2 草稿只在 cache proof 迁移成功时处理：迁移器必须跨启动持久化与 App release、精确 connection/version 和完整 logical config/catalog pair hash 绑定的 receipt，随后先把草稿同步升级为有绑定格式，再允许进入内存。旧 App 未完整预热、receipt 缺失或 pair hash 改变时，即使后来联网取得了一个可用 catalog，也不能据此猜测旧草稿语义；草稿继续保留并锁定。

用户手动保存的队列备份是另一类长期恢复材料。它会跨重启、退出登录与多天保留，并固定创建时的 active config/catalog pair；候选下载和普通队列完成都不会解除这个 pin。只有专门的“删除队列备份”操作经用户明确确认且实际删除成功后才能释放；删除失败时恢复原备份并继续锁定，不能为了采用新候选而自动清理。

这是 availability behavior，不是 deployment success。启用 device 前确认：

- App 显示目标 private catalog，而非 sample；
- Catalog/App version 符合预期；
- Panel 不可达时使用旧 cache 符合 policy；
- Sample 无法被误作真实 form 提交。

## 10. Optional workflow 的 fail-closed 边界

- 重复检查必须显式配置有序 `dateTransforms`、`epochUnits`、`dateFormats` 与 IANA `timeZone`。六项日期兼容策略可整体省略；任一出现时必须全部合法。已有记录但日期无法完整解析时阻止提交；App 只尝试明确允许的转换、数字单位与兼容策略，不自行猜日期清洗、格式或时区来源。
- Panel 只为迁移而允许旧 duplicate adapter 的四项解析字段同时缺失；部分填写无效，且未补齐时不能发布启用重复检查的 profile。这个入口不构成 live catalog 的无损迁移保证。
- 上一工序启用时必须显式配置可为空的 `missingResponseCodes`、`missingMessagePatterns`、`retryableMessagePatterns` 与 `alreadyExistsMessagePatterns`；只有前两项精确分类为不存在才允许自动创建，公共代码不猜业务码或错误文案，私有 classifier 必须由脱敏 replay 证明。未分类 lookup 是硬性 no-retry，即使错误文案像网络超时也不能进入整单元重试；手动检查入口必须通过已核对 profile 与完整 side-effect capability gate。扫描预检也必须先通过已核对 workflow 与 lookup gate；候选超时保持 indeterminate，只报告失败，不能累计 missing、纠正标识、移除单元或改变附件/阻止状态。
- 打印 v1 只接受 adapter allow-list 中的任务类型，按主标识精确匹配，并以最大正 numeric job ID 选择最新任务。printed / failed / ongoing 状态集合必须非空且互不重叠；未知状态保持未确认。
- 手动补打只允许 profile 显式列出的 failed / ongoing / unknown 状态；无论是否显示确认框，发送前都重新验证最新任务 ID 和状态。Printed 或缺失任务永不允许补打。补打 POST 还必须先写入精确 job/SN/session/policy/payload journal；printing v1 没有 Panel-owned “明确未受理”分类器，所以 socket 可能已发出后只有 configured success 才能直接完成该 POST。结果不明时保持 `UNCERTAIN`；之后仅当成功的精确绑定状态查询在同一远端上下文返回同一 job/SN，且 adapter 将其分类为 printed，才可原子清理同一条记录。Business non-success、解析/传输错误、缺失/failed/ongoing/unknown 状态、目标不符或本地清理失败都继续锁定。
- Profile 缺少新 workflow policy 时按安全关闭/阻止处理。Panel 结构化编辑器会把默认值写入旧 profile 草稿，发布前必须检查完整 diff。
- HTTP transport 对只读 GET 使用有界兼容重试；图片 multipart upload 是不会创建最终表单的准备动作，也可在 profile 的整单元有限网络重试预算内重新上传。旧版留下的 upload-only barrier 会被当前 App 自动移除，不再阻止后续提交、profile/Panel 切换或 App 更新。主流程/独立入口最终提交、按序的上一工序 recipe POST 与补打 POST 仍分别使用 fail-closed journal；真正的表单 POST 一旦进入 `POSTING`，遗留或不可分类结果保持 `UNCERTAIN` 并禁止 App 重放。业务 POST 只有被 Panel-owned adapter/profile classifier 明确证明未写入时才可有限重试，且复用完全相同的请求字节和目标绑定。主流程/独立入口最终提交与上一工序不确定记录必须先在原 backend 精确核对；补打只有同一 job/SN 的精确 printed 状态可以自动收敛。

## 11. Release 与 supply chain

- Signing certificate、keystore 和 password 不得进入 Git。
- Update repository 必须 public，因为 App 匿名读取 Releases；不要在其中放 catalog 或 internal notes。
- 每次 release 验证 signer continuity、APK SHA-256、package、versionCode 和 versionName。
- 审阅 dependency/lock/wrapper 变更，运行 Panel 与 Android tests。
- 发布前扫描当前 tree、full history、all branches/tags 与 Release assets。

具体命令见 [Releasing](./releasing.md)。

## 上线检查

- [ ] Private catalog 与 public source 完全分离；两份必选文件位于正确 repository，可选 `panel-settings.json` 的 present/absent 状态符合当前 authority。
- [ ] Full history、tag、Release 和附件不含 deployment data。
- [ ] `CATALOG_READ_KEY` 已设置；catalog、config、Panel bootstrap、runtime provenance 与 notification proxy 均拒绝匿名访问。
- [ ] 若启用一键配对：公开/受控发行策略与 shared read-key 权限边界已明确接受；`APP_PAIR_TICKETS` SQLite migration、独立 `APP_PAIR_ISSUER_KEY`、精确 applicationId allow-list 与 <=600 秒 TTL 已配置；错误 issuer/read key、未知/过期/已用 ticket 和并发兑换均以 no-store 通用失败结束，且真实设备只成功一次。
- [ ] `notificationAdapter` 与 `eventTemplates` 未出现在 App-facing response；migration-only direct URL 已清空。
- [ ] 每个 profile 的提交汇总开关与全局 diagnostics policy 均按最小需要显式设置；缺失时已验证为关闭。
- [ ] `PUBLIC_URL`、Panel URL 与 catalog host 一致，全部正式 endpoint 使用 HTTPS。
- [ ] Backend CORS 与 authorization、GitHub token scope、secret rotation 已验证。
- [ ] Adapter 不含 secret，每个 enabled operation 都完成 end-to-end test。
- [ ] Duplicate date transform order/unit/format/time zone 已显式验证；printing 的精确标识、非重叠状态与单调 numeric job ID 契约已验证，或对应模块关闭。
- [ ] Recipe、提交与网络重试保持默认单次/关闭，或 backend 幂等/去重已通过不确定结果测试。
- [ ] 最终提交、上一工序 recipe 与补打 journal 已在 signed upgrade build 上覆盖进程退出、存储失败、登录失效、超时和未分类响应；真正的 POST 不确定记录不能重提或被新 catalog 重解释。图片上传失败允许在有限预算内重新上传，并证明不会创建或重复最终表单。补打已证明只有同一远端上下文、同一 job/SN 的成功 configured-printed 查询可以自动收敛。
- [ ] Panel 的 `defaultPhotoOrder` 结构化控件与 App 顺序一致；新可见 `singleChoice` 字段保持 `reviewRequired:true`，直到管理员明确选择提交值。
- [ ] 手动队列备份跨重启长期保留并固定原 active pair；只有明确确认且成功删除备份后才释放 pair pin。
- [ ] 回滚到旧 App 前，设备已证明主流程、独立入口、上一工序与补打四类 POST journal 均不存在且可读、遗留 upload-only key 已清理，并且没有相关远程 worker 在执行；任一业务 POST slot 存在、无法读取或未解决时回滚必须 fail closed。
- [ ] App 已同步目标 catalog，不再显示 fallback seed。
- [ ] AI 与 notification data policy 已批准，或相应功能关闭。
- [ ] Optional cross-App session 未启用，或已完成 signer verification 与专项审计。
- [ ] 已在 dedicated environment 完成 signed upgrade 和 rollback rehearsal。
- [ ] 已按新 Panel、private config、旧 App compatibility、新 App 的顺序完成分阶段升级；首次无 v2 binding 覆盖安装已预留一次重新登录，之后才开启新增 workflow。
