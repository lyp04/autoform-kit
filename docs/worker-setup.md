# 部署 Panel Worker

`panel/` 是通用 Cloudflare Worker + static SPA。Panel 是运行配置主体，负责 template authoring、profile editing、global integration、private catalog publishing、App config/catalog serving，以及 optional AI 与 notification proxy。

Public repository 只包含 implementation 和 fictional examples。所有 live domain、repository、route、adapter 与 credential 都放在 ignored local config、Cloudflare 或 private catalog。

## Prerequisites

- Node.js 22+ 与 npm；
- Cloudflare account；
- 独立 private GitHub catalog repository；
- 只授予该 repository `Contents: Read and write` 的 fine-grained GitHub token；
- Optional：用于受审 catalog-authority cutover 的 private R2 bucket；
- 按 [Backend adapter](./backend-adapter.md) 准备的 v1 adapter；
- 支持 browser direct access 和 Worker token validation 的 deployment backend。

不要把 live catalog repository 作为 public fork 的一部分。

## 1. 安装与测试

```sh
git clone https://code.example.invalid/autoform-kit.git
cd autoform-kit/panel
npm ci
npm test
```

URL 是不可用的 fictional placeholder，请替换为已审核的 source location。`npm ci` 使用 committed lockfile。

## 2. Local Wrangler config

Repository 只跟踪 `wrangler.example.toml`：

```sh
cp wrangler.example.toml wrangler.local.toml
```

`wrangler.local.toml` 被 Git 忽略。仅在该文件或 Cloudflare Dashboard 设置：

| 配置 / binding | 作用 |
| --- | --- |
| `name` / `routes` | Worker identity 与 optional custom domain。 |
| `CF_VERSION_METADATA` | Cloudflare `version_metadata` binding；正式部署用它公开受 read-key 保护的 Worker version/source-tag provenance。 |
| `GITHUB_REPO` | Private catalog 的 `owner/name`。 |
| `GITHUB_BRANCH` | Optional catalog branch；省略时使用 repository default branch。 |
| `CATALOG_R2` | Optional R2 bucket binding；未完成精确 seed 时只读回退 GitHub 且拒绝发布，不能把“已绑定”当作“已切换”。 |
| `APP_PAIR_TICKETS` | SQLite-backed Durable Object namespace；保存 hash-only 短期票据状态与不可逆限流计数，保证单次兑换原子消费。 |
| `PUBLIC_URL` | App 下载 catalog 的 Panel base URL。 |
| `APP_PAIR_APPLICATION_IDS` | 允许发行的精确 Android applicationId，逗号分隔；默认正式包为 `com.autoformkit.app`。 |
| `APP_PAIR_TTL_SECONDS` | 票据绝对寿命，60–600 秒，建议/默认 300。 |
| `AI_BASE_URL` / `AI_MODEL` | Optional AI provider endpoint 与 model。 |

`PUBLIC_URL` 会写入 manifest `profilesUrl`。启用 read key 后，它必须与 device Panel URL 使用同一 host。

不要在 local TOML 写 token、password 或 signing material；secret 使用 Cloudflare secret store。

## 3. Cloudflare secrets

```sh
npx wrangler login
npx wrangler secret put BACKEND_ADAPTER_JSON --config wrangler.local.toml
npx wrangler secret put GITHUB_TOKEN --config wrangler.local.toml
npx wrangler secret put CATALOG_READ_KEY --config wrangler.local.toml
npx wrangler secret put APP_PAIR_ISSUER_KEY --config wrangler.local.toml
```

Optional AI：

```sh
npx wrangler secret put AI_API_KEY --config wrangler.local.toml
```

| Secret | 作用 |
| --- | --- |
| `BACKEND_ADAPTER_JSON` | 首次 Panel login 所需完整 v1 adapter；从 standard input 粘贴 repository 外准备的 JSON。 |
| `GITHUB_TOKEN` | Worker 读写 private catalog。 |
| `CATALOG_READ_KEY` | Shared credential，保护 catalog、App config、Panel bootstrap 和 notification proxy。 |
| `APP_PAIR_ISSUER_KEY` | 只与下载 Worker 共享的独立 server-to-server 高熵 credential；不得复用 read key 或 browser/backend token。 |
| `AI_API_KEY` | Optional authoring AI；未启用时不要设置。 |

Wrangler 会交互读取 secret；不要把值放在 command arguments 或 shell history。

现有旧部署可能还有 `BACKEND_API_BASE` 或 `BACKEND_SESSION_PROOF_CODES`。Worker 只为迁移读取
它们；新部署不要设置。先把等价 base URL / `auth.sessionProofCodes` 纳入完整 adapter，在 Panel
保存到 private catalog 并完成旧 App 对照后，再移除旧 Cloudflare 输入，避免长期存在两套来源。

### 正式环境必须设置 read key

未配置 `CATALOG_READ_KEY` 时 shared read gate fail-open，包括 `/catalog/*`、`/api/config`、`/api/profiles` read-key path、`/api/panel-config`、`/api/runtime-provenance` 和 `/api/notify`。这只适合没有 live data/adapter 的 local demo。

使用高熵随机值，并通过受管方式配置 devices。轮换会立即使旧 key 失效，应先安排 rollout window。

## 4. Backend access

Panel browser 直接调用 backend challenge、login、user-info、template-list 和 template-detail endpoints。Worker 也从 Cloudflare network 调用 user-info，验证每个 protected Panel write。

Backend 必须：

- 只向预期 Panel origin 开放必要 CORS method/header；
- 允许 browser network 访问 authoring endpoints；
- 允许 Worker 访问 user-info；
- 按 adapter 返回约定 field 与 status；
- 对 token 执行 authorization 与 expiry validation。

Panel 没有第二套 password 或 role database。任何通过 configured user-info validation 的 backend token 都可执行 Panel write，因此 backend 或 edge layer 必须限制获准 account。

`/api/panel-config` 在 login 前向持有 read key 的 browser 返回 authoring adapter subset。若 endpoint metadata 也必须隐藏，应限制整个 Panel 的 network access。

### 管理授权边界

允许哪些账号 author/publish 是部署自定义的一部分，不是 profile 字段。正式环境必须在 backend
user-info authorization 中只允许专用管理员账号，并按需要在 Cloudflare Access、反向代理或受控网络
增加第二层访问限制。`CATALOG_READ_KEY` 只保护读取 route，不提供管理员角色隔离。

### One-time App pairing issuer

Worker 提供独立 server-to-server `POST /api/app-pair/v1/issue` 与 App-facing
`POST /api/app-pair/v1/redeem`。必须同时配置 SQLite-backed `APP_PAIR_TICKETS` binding/migration、
高熵 `CATALOG_READ_KEY`、独立 `APP_PAIR_ISSUER_KEY`、HTTPS `PUBLIC_URL`、精确
`APP_PAIR_APPLICATION_IDS` 和不超过 600 秒的 TTL；缺少任一项，两条 route 都 fail closed。issuer
只接受 `Authorization: Bearer APP_PAIR_ISSUER_KEY`，不能接受 browser backend token 或 read key。

下载 Worker 必须明确选择公开或受控发行，并只提交不可逆 HMAC client digest。公开发行允许任意页面
访客取得 shared Panel connection credential，但不会创建或绕过 backend account session；受控发行则
由下载 Worker 自己完成服务端 session authorization 与 CSRF 防护。Panel 仅保存 ticket/access-key
摘要、exact audience、过期时间、消费状态与不可逆限流计数。兑换在
Durable Object transaction 中先完成 `issued -> consumed` 再返回当前 read key；响应丢失也不得重放。
在下载 Worker 发行策略、secret 配置、网页 Intent 与真实设备端到端测试全部完成前，下载页仍须隐藏或
禁用配对动作，继续允许 Settings 手动连接。完整合同见
[App 一次性配对协议](./app-pairing.md)。

## 5. Local preflight

`npm run dev` 和 `npm run deploy` 都显式使用 ignored config：

```sh
npm run dev
```

Local development 不连接 live backend/catalog。使用 dedicated account 与 fictional data，并至少验证：

- bootstrap adapter 与 challenge 可读；
- browser CORS 足够窄且可完成 login；
- Worker 能通过 user-info 验证 token；
- template field type/result option 被 `conversion` 正确处理；
- upload、submit 和每个 planned optional operation 通过 adapter validation；
- no/wrong read key 被拒绝。

## 6. Deploy

```sh
npm run deploy
```

该命令通过受控 wrapper 部署：要求完整 clean worktree、local config 中恰好一个
`CF_VERSION_METADATA` binding，并给 Cloudflare version 附加当前完整 Git commit 的 source tag。
不要用裸 `wrangler deploy` 绕过该入口。Source tag 可以证明 live Worker 自报的部署来源，但不能
单独证明上传 bundle 的 exact bytes；正式发布仍要保留独立 bundle/build attestation。

正式 release evidence 使用 schema v2，必须记录 Cloudflare 的 32 位小写 account id 与 exact Worker
name。Verifier 先从 authenticated `/api/runtime-provenance` 取得当前 Worker version id，再通过
`wrangler versions view` 复核该 exact version：GitHub 模式要求唯一、精确的 `GITHUB_REPO`，并要求
`GITHUB_BRANCH`（缺失时为 repository default branch）及其 live head 精确匹配 evidence branch/commit，
且不能同时存在 `CATALOG_R2`；R2 模式要求 `CATALOG_R2` 的 bucket 与 jurisdiction 精确匹配。因而带 R2
binding 的 GitHub→R2 过渡部署不能作为最终 GitHub-authority 发布证据，必须先完成或撤销 cutover。

首次 deployment 后，把实际 Worker URL 填入 local `PUBLIC_URL` 并再次 deploy。使用 custom domain 时确认 DNS、TLS 和 route 指向当前 Worker。

切勿 commit `wrangler.local.toml`。提交前运行：

```sh
git status --short
git diff --check
```

## 7. 初始化 private catalog authority

先在 private repository 创建默认分支和至少一个初始 commit；Panel 不负责创建空仓库的首个分支。若使用非默认分支，确保该分支已经存在，并在 local `wrangler.local.toml` 设置同一个 `GITHUB_BRANCH`。

1. 打开已部署 Panel。
2. 输入与 `CATALOG_READ_KEY` 相同的 Panel access key；它只保存在当前 tab `sessionStorage`。
3. 加载 bootstrap 后，用 dedicated backend account login。
4. 在 global settings 检查并保存完整 `backendAdapter`。
5. Optional：配置 v2 `notificationAdapter`、`diagnosticsPolicy`、brand 和 public update source。
6. 从 fictional/dedicated template 创建第一个 profile，设置需要访问项的 `pickerVisible:true`。
7. 人工检查完整 JSON，然后 publish。
8. 确认写入正确的默认 private GitHub authority。

Catalog root 的 contract：

| 文件 | 内容 |
| --- | --- |
| `form-profiles.json` | App-facing settings 与 profiles。 |
| `manifest.json` | Version、SHA-256 与 payload URL。 |
| `panel-settings.json` | 可选的 Worker-only `notificationAdapter`；首次保存 Worker-only setting 时创建，没有该设置时合法地不存在。 |

活动 `backendAdapter` 存在 `form-profiles.json` 的 `settings`，覆盖 Cloudflare bootstrap。v2/v3 `notificationAdapter` 的 provider URL 只在 `panel-settings.json`，不会进入 App catalog/config；但 old-App migration 的 legacy `notifyWebhook` 仍在 `form-profiles.json`，并会暂时出现在 `/api/config`，所有旧设备升级后必须清空。Authority 中不存在 `panel-settings.json` 时，备份与发布证据也必须记录显式 absent，不能创建虚构空文件。Exact filled files 可保存到访问受控的 private backup/evidence，但不得复制到 public source、history、tag、Release、artifact 或日志。

### Optional R2 cutover

GitHub 是默认 authority。`CATALOG_R2` 绑定存在但 bucket 中没有
`catalog-current-v1.json` 时，Worker 仍从 GitHub 读取，但所有 publish 都 fail closed；这只是迁移窗口，
不是双写模式。切换前应冻结旧 Git-backed Worker 的写入，并用当前精确 GitHub snapshot 先创建
content-addressed R2 snapshot，再以 create-only 条件写入 current pointer。公共实现提供
`buildR2CatalogSeed` / `seedR2CatalogFromGitHub` 的库契约和测试，但不暴露可远程调用的 seed route；
实际调用应放在受审、一次性的 private deployment tooling 中，并绑定预期 catalog version。

current pointer 存在且其 SHA-256、snapshot key、catalog/manifest version 全部通过后，R2 成为唯一
authority；后续 publish 先写 immutable snapshot，再用 ETag CAS 移动 current pointer。此后 GitHub
只是一份可能过期的 baseline，不能直接作为 rollback。移除 R2 binding 前，必须先把 R2 current 的
精确 authority 状态（两份必选文件及 optional `panel-settings.json` 的 present/absent）恢复到 GitHub
并独立验证；否则继续使用仍读取 R2 的旧 Worker 回滚。完整注释见
[`wrangler.example.toml`](../panel/wrangler.example.toml)，实现契约见
[`panel/src/catalog.js`](../panel/src/catalog.js)。

切换记录必须私下保留 current pointer、条件写 ETag、immutable snapshot identity，以及 snapshot
内 exact catalog/manifest 和 optional panel-settings present/absent 状态。GitHub baseline 不能充当
R2 current 的发布证据。

## 8. Notification proxy

在 Panel global settings 按 [`notificationAdapter` contract](./configuration.md#notificationadapter) 配置 provider transport：v2 使用私有 `eventTemplates`，v3 使用私有 `summary` / `problem` delivery。Adapter 不支持 custom headers；需要 header credential 时，在 provider 前部署受控 server-side relay。

新 App 只接收 `notification {version,enabled,endpoint:"/api/notify",eventTypes,diagnosticsEnabled}`，并以 read key 调用 same-origin proxy。v2 capability 只提供 `submission.summary` / 可选 `runtime.failure`；v3 只提供 `submission.round`。App 只发送对应版本的严格结构化字段，不发送自由文本 message；Worker 根据私有 template 生成 provider message。

提交汇总必须在对应 profile 显式设置 `workflow.notifications.submissionSummary:true`，缺失时默认关闭；v3 还必须提供显式 `profileLabel`。Panel 在 publish / settings save 时交叉校验当前 adapter capability 与所有启用 profile，缺项直接拒绝。Device diagnostics 只属于 v2，必须同时配置 `runtime.failure` event template 并显式打开全局 `diagnosticsPolicy`；任一缺失时关闭。先让 provider/relay 进入 safe test mode，用 fictional structured events 验证 template、provider body、status、response marker、timeout 与 rate limit，再启用。

旧 v1 adapter 会在读取时安全迁移为 v2，并只增加通用 `submission.summary` template；保存全局设置会持久化 v2。迁移不会创建 `runtime.failure` template、v3 delivery 或打开 diagnostics。v3 必须私下显式配置，不会因 Panel/App 升级自动启用。`notifyWebhook` 只用于 old-App migration，升级完成后清空。

## 9. Post-deployment verification

使用 reserved example host 表示实际 environment：

```sh
PANEL_EXAMPLE_ORIGIN='https://panel.example.invalid'

assert_401() {
  test "$(curl -sS -o /dev/null -w '%{http_code}' "$@")" = '401'
}

for PANEL_EXAMPLE_PATH in \
  /catalog/form-profiles.json \
  /catalog/manifest \
  /api/config \
  /api/profiles \
  /api/panel-config \
  /api/runtime-provenance; do
  assert_401 "${PANEL_EXAMPLE_ORIGIN}${PANEL_EXAMPLE_PATH}"
  assert_401 \
    -H 'Authorization: Bearer DELIBERATELY_WRONG_EXAMPLE_KEY' \
    "${PANEL_EXAMPLE_ORIGIN}${PANEL_EXAMPLE_PATH}"
done

assert_401 -X POST -H 'Content-Type: application/json' --data '{}' \
  "${PANEL_EXAMPLE_ORIGIN}/api/notify"
assert_401 -X POST \
  -H 'Content-Type: application/json' \
  -H 'Authorization: Bearer DELIBERATELY_WRONG_EXAMPLE_KEY' \
  --data '{}' \
  "${PANEL_EXAMPLE_ORIGIN}/api/notify"

# Fictional positive samples; inject the real key only in a controlled shell.
curl --fail-with-body \
  -H 'Authorization: Bearer EXAMPLE_READ_KEY' \
  "${PANEL_EXAMPLE_ORIGIN}/api/panel-config"
curl --fail-with-body \
  -H 'Authorization: Bearer EXAMPLE_READ_KEY' \
  "${PANEL_EXAMPLE_ORIGIN}/catalog/manifest"
curl --fail-with-body \
  -H 'Authorization: Bearer EXAMPLE_READ_KEY' \
  "${PANEL_EXAMPLE_ORIGIN}/api/runtime-provenance"
curl --fail-with-body \
  -X POST \
  -H 'Content-Type: application/json' \
  -H 'Authorization: Bearer EXAMPLE_READ_KEY' \
  --data '{"version":2,"type":"submission.summary","data":{"success":true,"submittedCount":1,"errorCount":0,"unconfirmedPrintCount":0,"missingMaterialTypeCount":0,"newMissingMaterialTypeCount":0,"recoveredMaterialTypeCount":0,"networkAffectedCount":0}}' \
  "${PANEL_EXAMPLE_ORIGIN}/api/notify"
```

在受控 shell 通过环境变量注入 read key（不要放进 argument 或日志），再把 live source tag 与审阅的
完整 commit 对照：

```sh
cd panel
npm run verify:provenance -- \
  --url https://panel.example.invalid \
  --expected-commit <full-40-character-source-commit>
```

`verify:provenance` 从 `AUTOFORM_PANEL_READ_KEY` 读取可选鉴权值。不要把真实 key 写进本文命令。

Expected：

- Requests without key return `401`，including `/api/profiles`、notification POST and runtime provenance；a deliberately incorrect Bearer returns `401` on every protected route；
- Correct key returns a non-secret authoring adapter subset and App-facing catalog/config；
- `panel-settings.json` 与 v2/v3 `notificationAdapter` provider URL 不能通过 `/catalog/*` 或 `/api/config` 读取；migration-only legacy `notifyWebhook` 是临时 App-facing 例外，旧设备升级后必须清空；
- `/api/notify` rejects free-form `message` and fields outside the selected event schema；
- Manifest `profilesUrl` uses the same host as Panel；
- Runtime provenance is version `1` and its exact source commit matches the reviewed deploy；
- Test App shows private profiles, not bundled seed；
- Login、upload、submit 与 every enabled optional module pass with dedicated data。

完整 checklist 见 [Deployment security](./security.md)。

## 10. Staged upgrade for an existing deployment

新 Panel、private catalog 和 App 不应在同一步骤切换。按以下顺序保留旧 App 可用性：

1. **备份与基线**：保存 private catalog 当前 branch/commit、Worker 配置、已安装 App 的版本与 signer digest；用专用数据记录旧 App 的登录、同步、上传和提交基线。
2. **先部署兼容新 Panel**：只更新 Worker/SPA，暂不发布 profile 行为变化。确认新 Panel 能读取现有 catalog、旧 App 仍取得相同 App-facing config，且 private Worker-only setting 没有泄露。兼容读取只是迁移入口，不证明旧配置已无损转换。
3. **私下补齐契约**：仅在 private catalog 和 Cloudflare 中补齐 `backendAdapter` 的上一工序响应分类、重复日期、打印等字段，以及每个 profile 的完整显式策略。旧 App 已有的上一工序、重复、打印、恢复和重试行为必须写成等价值；只把真正新增的功能保持关闭。旧 previous-step adapter 的两个 recipe message pattern 数组可同时缺失以便进入 Panel，但部分填写无效，未补齐时不能发布任何开启上一工序的 profile；旧 duplicate adapter 的 `dateTransforms` / `epochUnits` / `dateFormats` / `timeZone` 同理。日期兼容策略六项也只能全部省略或全部填写；若无需复现宽松旧行为，保持省略或使用公开示例的严格值。逐项验证完成前保持 `workflow.compatibilityReviewed:false`；Panel 不会自动勾选且会拒绝发布未确认的 profile。最后检查 Panel 生成的完整 diff 与递增 version。
4. **先验证并预热旧 App**：目标 revision 发布后，让测试设备和每台现场旧 App 彻底退出并重新启动，再验证原有表单、登录、上传和提交没有改变，并用设备侧证据确认 `/api/config` 与精确 catalog 已缓存同 revision 的 cache proof。旧版本只在启动时刷新 config，仅回到前台、等待或看到 catalog 更新不能替代这一步。新增字段必须被旧 App 安全忽略；不要在此阶段提高 `minAppVersionCode` 到旧 App 无法读取。未完成预热的设备不能列入无感升级批次：candidate 会要求联网同步，且不会猜测恢复遗留未绑定草稿。
5. **再验证并发布新 App**：新 App 会拒绝提交仍缺完整策略段的旧 profile。首次从没有 v2 binding 的旧版覆盖安装后必须重新登录一次；旧 credential 不会被读取或发送，草稿、队列、ledger 和待处理照片不会因此删除。用 candidate App 对迁移后的等价行为逐项做端到端测试，包括不确定网络结果、重复日期无法解析、打印任务晚到/未知状态和补打确认；通过后才使用原 package 与 signer 发布。
6. **最后逐项启用新能力**：确认设备已升级且加载目标 catalog 后，才单独开启旧 App 从未执行过的新能力；每项都保留可回滚 catalog version。全部旧客户端退出后再清理 legacy field / webhook。

底层 HTTP transport 只对只读 GET 做有界兼容重试：DNS、连接、超时、TLS 或 502/503/504 等瞬时故障后等待 3 秒再试一次，合计最多两次；每次尝试都重新校验当前 Panel、session 与远程 worker gate。POST、multipart upload、OCR upload 与打印 POST 的 transport 调用仍各执行一次。业务 POST 只有被 Panel-owned adapter/profile classifier 明确拒绝并证明未写入时才可有限重试，且复用完全相同的请求字节、目标绑定与已上传 URL，不重复上传。主流程/独立入口最终提交、按序的上一工序 recipe POST 和补打 POST 分别受 fail-closed journal 保护，未知结果锁定而不重放。上一工序存在性 GET 只有精确命中私有 `missingResponseCodes` / `missingMessagePatterns` 才能进入纠正或创建，未分类业务响应不发 POST。主流程/独立入口最终提交与上一工序必须经后端侧核对和受控恢复；补打只有在成功的精确绑定远端查询返回同一 job/SN、且 adapter 分类为 printed 时才会自动清理同一条记录。Multipart upload 另有持久防重放 barrier：首个 socket 前写入，开始上传后关闭整单元重试，并在不确定结果时跨重启阻止后续记录、profile/Panel 切换和 App 安装。它不续传、不删除孤立文件，也不让 backend 自动幂等。`workflow.previousSteps.recipeMaxAttempts` 是每 recipe 跨进程/重启/用户再次提交的持久累计上限。若把它或 `workflow.submission.maxAttempts` 设为大于 `1`，或把 `networkRetry.maxAttempts` 设为大于 `0`，仍必须先证明受影响 backend 操作具有相应幂等/去重语义，并用脱敏响应 replay 验证 lookup missing、recipe retry/already-exists、submit retryable 与 missing-material classifier；不能证明时保持上一工序关闭、一次 recipe、一次提交、零次网络额外重试。
