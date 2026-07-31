# 后端适配器 v1

`backendAdapter` 是 Panel、Worker 与 Android App 共用的版本化协议描述。它把部署后端的地址、接口、请求和响应结构、模板字段、字段类型、结果选项与可选模块映射到稳定的 runtime 名称。

公开源码没有可调用的后端 fallback。Adapter 缺失、版本不支持或启用模块的配置不完整时，客户端会报告配置错误，而不是猜测路由或业务值。

完整虚构结构见 [`panel/backend-adapter.example.json`](../panel/backend-adapter.example.json)。该文件使用保留示例域名，不能直接部署。

## 存放与引导

1. 在仓库外复制并填写示例。
2. 将完整 JSON 设置为 Cloudflare `BACKEND_ADAPTER_JSON`，供未初始化的 Panel 完成首次引导。
3. 登录 Panel，在全局设置中检查并保存 `backendAdapter`。
4. 后续活动副本位于私有 `form-profiles.json` 的 `settings.backendAdapter`。

解析优先级由低到高为：迁移期扁平设置、Cloudflare 引导值、私有 catalog 值。真正的密码、token、API key 或私有请求凭证不得写入 adapter。

Worker 仍读取 `BACKEND_API_BASE` 和 `BACKEND_SESSION_PROOF_CODES`，仅用于已经部署过这些旧
Cloudflare 输入的一次迁移。前者只补 legacy base URL；后者只在完整 adapter 没有显式
`auth.sessionProofCodes` 时补值。新部署不得把它们当成第二套配置：把经核对的等价值写入完整
`BACKEND_ADAPTER_JSON`，随后保存到 private `settings.backendAdapter` 并删除旧输入。

`/api/panel-config` 在校验 `CATALOG_READ_KEY` 后、backend 登录前，向浏览器返回 authoring 所需的 adapter 子集。因此 backend host、authoring endpoint、字段与 conversion mapping 是受 read key 保护的配置，但不是 secret。需要更强隐藏时，应限制整个 Panel 的网络可达范围。

## 顶层结构

| 字段 | 必需 | 作用 |
| --- | --- | --- |
| `version` | 是 | 当前为整数 `1`。 |
| `baseUrl` | 是 | 绝对 backend URL；正式环境使用 HTTPS。 |
| `endpoints` | 是 | 稳定逻辑名到相对或绝对 endpoint 的映射。 |
| `request` | 是 | 登录/authoring Body 编码、鉴权 scheme、可选指纹 header，以及 App web-session 请求身份。 |
| `response` | 是 | 通用状态、data 与错误文案路径。 |
| `auth` | 是 | 登录字段、token / 用户名路径、会话信号。 |
| `pagination` | 是 | Panel 模板搜索 query。 |
| `fields` | 是 | Backend 模板 envelope 到 canonical template 的字段路径。 |
| `conversion` | 是 | Backend field type 与 result option 到通用 profile 的转换。 |
| `operations` | 是 | 上传、提交、模板详情和可选运行模块。 |
| `printing` | 否 | 可选异步打印协议。 |

点路径只表示逐层对象属性，例如 `payload.session.token`；不要依赖数组索引、通配符或 JSONPath。

## Endpoints

相对路径基于 `baseUrl`；完整 URL 也可使用。推荐把同一 backend 的路径保持为相对值。

| 逻辑名 | 必需条件 | 使用方 |
| --- | --- | --- |
| `captcha` | 基础 authoring 契约必需 | Panel、App |
| `loginVerify` | 可选 | Panel、App |
| `login` | 必需 | Panel、App |
| `userInfo` | 必需 | Panel、Worker、App |
| `templateList` | 必需 | Panel |
| `templateDetail` | 必需 | Panel 模板导入与编辑 |
| `uploadFile` | 必需 | App |
| `submitEntry` | 必需 | App |
| `detectionData` | 存在 `operations.previousSteps` 时必需 | App |
| `snRepetition` | 存在 `operations.duplicateCheck` 时必需 | App |
| `printerState`、`messageList`、`labelRetry` | `printing.enabled:true` 时必需 | App |

省略可选 operation 时应同时省略仅由它使用的 endpoint。不要为关闭的模块编造路径。

## Request、response 与 auth

`request`：

- `bodyEncoding`：`form` 或 `json`，仅控制 Panel authoring POST 与 App 的 login/loginVerify；App 提交固定使用 JSON，上传固定使用 multipart；
- `authScheme`：Authorization scheme；
- `fingerprintHeader`：可选 header 名，空或缺失表示不发送；
- `webUserAgent`：App 建立/复核 web session 时发送的 User-Agent；必须显式为 string，空字符串表示不覆盖系统默认值；
- `webAcceptLanguage`：同一路径使用的 Accept-Language；必须显式为 string，空字符串表示不发送。

App 不再内置浏览器版本或语言偏好。旧部署若依赖原请求指纹，必须在私有 adapter 中填入旧值后再升级。

`response`：

- `codeField`：可选业务状态点路径；
- `dataField`：data 点路径，空字符串代表响应根；
- `messageFields`：错误文案候选路径，按顺序取首个非空值；
- `successValues`：设置 `codeField` 时必须是非空成功值数组。

少数旧业务接口会在 2xx JSON 中省略 `codeField`。这类兼容必须由 Panel 在
`response` 中成组声明，三项缺一不可；不声明时继续严格拒绝缺 code 的业务响应：

- `successFieldsWhenCodeMissing`：非空、去重的点路径数组；仅当响应没有 code，且至少一个声明路径真实存在时提供成功证据，字段值为 `null` 也只代表“路径存在”；
- `dataRootWhenCodeMissing`：上述兼容成功且 `dataField` 没有可用值时，是否把响应根作为 data；
- `rejectMessageWhenCodeMissing`：为 `true` 时，只要任一 `messageFields` 路径包含非空消息，就拒绝无 code 兼容；为 `false` 表示部署已经用脱敏 replay 证明成功响应可同时带消息。

响应明确包含 code 时始终只按 `successValues` 判断，兼容路径不能覆盖失败 code。该三项只应在本地/私有响应 replay 已证明字段稳定后配置；公共示例默认不启用。

旧认证服务若会返回没有业务状态码的 2xx JSON，必须在 `auth` 中显式声明兼容证据：

- `successFieldsWhenCodeMissing`：仅用于验证码、登录校验、登录和用户信息接口；只有 `codeField` 缺失或为 `null`、所有 `messageFields` 均为空，且数组中至少一个点路径真实存在时才视为成功；
- `dataRootWhenCodeMissing`：布尔值；满足上述无状态码条件且 `dataField` 没有可用值时，允许认证接口把响应根作为 data；
- 登录 token 先按 `tokenFields` 从 data 查找，再从认证响应根查找。

这两个认证字段默认不启用；认证接口先按通用 `response`（包括上面的可选三项）判断，再使用 `auth` 专用 fallback。`auth` fallback 不会影响模板读取、上传、提交、打印或其他业务接口。

除显式 `sessionInvalidHttpStatuses` 与 502/503/504 网关状态外，带 JSON body 的 HTTP 4xx/5xx
仍会进入上述 response 与 Panel-owned 业务拒绝分类器，以保留旧部署的明确拒绝语义；非 JSON
或无 body 仍按传输/解析失败处理。HTTP 失败即使 body 命中 `successValues` 也不会转成成功。
业务值比较兼容同值的 string / number 表示，但配置仍应保持 backend 原始类型。

`auth.loginFields` 将 `account`、`password`、`captcha`、`client` 映射到请求字段。`tokenFields` 与 `userNameFields` 是候选点路径数组。

会话策略也归 `auth` 所有：

- `sessionProofCodes`：Worker 校验刚登录 token 时，可作为有效会话证据的特殊 backend 状态；通常为空。
- `sessionInvalidHttpStatuses`：App 应清除会话的 HTTP 状态整数数组；必须显式配置，可为空。
- `sessionInvalidCodes`：App 应清除会话的部署特有状态值。
- `sessionInvalidMessagePatterns`：不区分大小写的失效文案子串；不是正则表达式。

配置必须尽量窄。普通失败值不应加入会话策略；例如是否把 403 当作 session 失效必须由部署明确决定。App 不再附加内置英文文案或 HTTP 状态启发式。Catalog 顶层的同名失效字段只用于迁移；新配置写入 `backendAdapter.auth`。

## Pagination 与模板字段

`pagination` 定义 `pageParam`、整数 `pageStart` 和 `keywordParam`。

`fields` 把 backend 响应转换为 canonical template：

- challenge：`captchaClient`、`captchaImage`；
- 列表：`templateList` 候选路径数组；
- template：`id`、`name`、`sku`、`step`、`warehouseId`、`fieldList`；
- form field：`id`、`type`、`parentType`、`typeName`、`title`、`englishTitle`、`required`、`visible`、`maxCount`、`options`；
- option：`value`、`label`、`englishLabel`、`quantity`。

这里配置的是字段路径，不是实际模板数据。真实 identity、field ID 和 option value 只通过 backend 响应进入私有 profile。

## Conversion

### `conversion.fieldKinds`

每个 canonical kind 对应一个 backend type 值数组。数组允许为空，但九个 key 都必须存在：

```json
{
  "conversion": {
    "fieldKinds": {
      "photo": ["example-photo-type"],
      "result": ["example-result-type"],
      "items": [],
      "serial": ["example-serial-type"],
      "scan": [],
      "number": [],
      "singleChoice": ["example-single-type"],
      "multipleChoice": ["example-multiple-type"],
      "text": ["example-text-type"]
    }
  }
}
```

值可以是非空 string、有限 number 或 boolean。未映射类型保持 `unknown`；公共 converter 不根据标题或字段名猜测类型。

### `conversion.result`

Result mapping 把 backend option 转成 profile 的不透明 result key：

```json
{
  "includeUnmapped": true,
  "mappings": [
    {
      "key": "option-one",
      "label": "示例结果一",
      "labelI18n": {
        "en": "Example result one",
        "es": "Resultado de ejemplo uno"
      },
      "operatorLabel": "示例操作项一",
      "operatorLabelI18n": {
        "en": "Sample operator option one",
        "es": "Opción de operador de ejemplo uno"
      },
      "uiColor": "#2563EB",
      "include": true,
      "submitValue": "EXAMPLE_SUBMIT_VALUE",
      "matchValues": ["EXAMPLE_BACKEND_VALUE"],
      "matchLabelPatterns": []
    }
  ]
}
```

- `key` 必须非空、唯一，但没有预设集合或业务含义。
- `label`、`labelI18n`、`operatorLabel`、`operatorLabelI18n`、`uiColor`、`include` 和任意 JSON 类型的 `submitValue` 可选。
- `operatorLabel` 与 `operatorLabelI18n.en/es` 一旦提供，必须是无首尾空白的非空文本，最长 160 characters；多语言对象只允许 `en`、`es`。
- `matchValues` 与 `matchLabelPatterns` 必须是数组，可为空；label pattern 是不区分大小写的 regular expression。
- `includeUnmapped:true` 保留未命中的 option，并分配稳定位置产生的通用 key；`false` 将其排除。
- 未提供 `submitValue` 时保留 backend option 的原始 value。

Converter 将结果写入 profile 的 legacy wire key `gradeMap`，但运行时把其 key 当作完全不透明的 result key。映射中的 `operatorLabel` / `operatorLabelI18n` 只写入同名显示字段；它们不会覆盖导入得到的 `label`，也不会改变原始或显式配置的提交 `value`。

## Operations

`operations.upload`、`operations.submit` 和 `operations.templateDetail` 是基础契约：

| 子对象 | 字段 |
| --- | --- |
| `upload` | `multipartField`、`resultPath` |
| `submit` | `templateIdField`、`warehouseIdField`、`skuField`、`dataField`、`videoIdField`、`videoIdValue` |
| `submit.materialItemMapping` | `codeField`、`nameField`、`quantityField` |
| `submit` rolling-upgrade policy | legacy `retryableMessagePatterns`、`missingMaterialMessagePatterns`，以及可选 `outcomePolicy` |
| `previousSteps` recipe rolling-upgrade policy | 仅供旧 App 兼容的 `retryableMessagePatterns`、`alreadyExistsMessagePatterns`，以及新 App 使用的可选 `recipeOutcomePolicy` |
| `templateDetail` | `idParam`；独立入口实时覆盖另可声明 `alternateEntryResolvers` |

`submit` 的五个 envelope 字段名必须两两不同，`materialItemMapping` 的三个字段名也必须两两不同；否则后写值会覆盖先写值，Panel 与 App 都会在构造请求前拒绝配置。`materialItemMapping` 决定 profile item 如何包装进提交 payload，公共 App 不固定其字段名。

新 App 只允许结构化 `operations.submit.outcomePolicy` 为副作用重试证明“该次确定未写入”。旧的两个 message pattern 数组仅保留给滚动升级期间的旧 App，新 App 不用它们授权重试，也绝不把旧值自动复制进新规则。`outcomePolicy` 可在未完成迁移时整体省略；省略后正常成功提交不受影响，但任何业务 non-success 都不能自动重放。

`outcomePolicy` 的固定结构为：

- `version:1`；
- `evidenceSha256`：对应私有、脱敏响应 replay 证据原始字节的 64 位小写 SHA-256；公共校验只检查格式，正式私有 release gate 必须检查真实绑定；
- `retryableNotWrittenRules`：确定可原样重试的未写入拒绝规则数组；
- `missingMaterialNotWrittenRules`：确定为缺料且未写入的拒绝规则数组。

每条 rule 必须显式同时写 `codeValues` 与 `messagePatterns` 两个数组，至少一个非空；同一 rule 的非空 selector 取 AND，不同 rules 取 OR。`codeValues` 只精确匹配 `response.codeField`，`messagePatterns` 只在 `response.messageFields` 的汇总文本中做不区分大小写的子串匹配。data、请求回显和完整 JSON 永远不能成为 not-written 证据。

缺料分类与“缺哪个材料”是两个独立步骤。`missingMaterialNotWrittenRules` 命中后，App 仅使用当前 profile 的 Java `materialCodePattern` 从已配置消息文本提取，再与该 profile 明确列出的材料 code 求交；正则缺失、非法、没有精确命中或结果为空时停止且不重试。Panel 发布开启缺料恢复的材料 profile 时要求非空 pattern，但实际 Java 正则仍由 Android promotion gate 编译验证。

### `operations.previousSteps.recipeOutcomePolicy`

`recipeOutcomePolicy` 只解释上一工序 recipe POST 的业务 non-success，和最终提交使用的 `operations.submit.outcomePolicy` 是两个相互独立的 capability。两者不能互相授权：submit policy 的规则或证据不能让 recipe 重试/确认，recipe policy 也不能改变最终 submit 的处理。

`recipeOutcomePolicy` 的固定字段为：

- `version:1`；
- `evidenceSha256`：对应私有、脱敏 recipe 响应 replay 原始字节的 64 位小写 SHA-256；
- `retryableNotWrittenRules`：明确证明该 recipe POST 未写入、可以复用同一请求字节有限重试的规则数组；
- `alreadyExistsAcknowledgedRules`：明确证明同一个 recipe 已由 backend 完成或去重、可以把本次位置记为已确认的规则数组。

两组数组中的每条 rule 都必须且只能包含 `codeValues` 与 `messagePatterns`，两个数组必须显式存在并至少一个非空。同一 rule 中的非空 selector 取 AND：同时配置 code 与 message 时必须同时命中；同一规则数组中的不同 rules 取 OR。`codeValues` 只精确比较 `response.codeField`；`messagePatterns` 只在 `response.messageFields` 明确指向的 **scalar** string/number/boolean 值汇总文本中做不区分大小写的子串匹配。对象、数组、data、请求回显和序列化后的完整响应都不能成为分类输入。

通用 `response.successValues` 仍先决定普通成功。对于业务 non-success，若同一响应同时命中 `retryableNotWrittenRules` 与 `alreadyExistsAcknowledgedRules`，结果是冲突而不是任选一边，App 将 journal 保持为 `UNCERTAIN` 并停止；未命中任一组也同样保持 `UNCERTAIN`。

旧 `operations.previousSteps.retryableMessagePatterns` 与 `alreadyExistsMessagePatterns` 只为滚动升级期间的旧 App 保留。新 App 不读取它们来授权重试或把 already-exists 当作成功，也不会把它们自动复制到结构化 policy。缺少有效 `recipeOutcomePolicy.retryableNotWrittenRules` 时，即使 profile 的 `recipeMaxAttempts` 大于 `1`，新 App 对每个 recipe 位置也最多发送一次；缺少有效 `alreadyExistsAcknowledgedRules` 时，legacy already-exists 文案只能得到 `UNCERTAIN`。

Outcome evidence 发布门只检查实际可执行的 recipe 路径：`workflow.previousSteps.enabled:true`、`triggerResultKeys` 非空且 `templates` 非空必须同时成立。这样的 profile 请求 `recipeMaxAttempts>1` 时才要求非空 `retryableNotWrittenRules`；同一可执行路径还保留非空 legacy `alreadyExistsMessagePatterns` 时，才要求非空 `alreadyExistsAcknowledgedRules`。关闭模块、没有 trigger 或没有 template 的存量配置没有 recipe POST 路径，不会仅因保存了这些字段而被要求补 outcome policy。

以下 operation 可省略：

| 子对象 | 存在时需要 | 作用 |
| --- | --- | --- |
| `ocr` | `multipartField`、`userInfoUrlFields`、`resultPaths` | 从 user-info 获取 OCR URL 并解析结果。 |
| `previousSteps` | `queryFields.{templateId,warehouseId,sku,serial}`、`itemsPath`、`itemDataPath`、`serialPath`、`missingResponseCodes`、`missingMessagePatterns`、仅供旧 App 的 `retryableMessagePatterns`、`alreadyExistsMessagePatterns`，以及 `endpoints.detectionData`；新 App 的 recipe 业务结果由可选 `recipeOutcomePolicy` 解释；动态 recipe 另需 `recipeResolvers` 与 `optionValueBuilders` | Profile 显式启用的上一流程查询与 recipe 执行。 |
| `duplicateCheck` | `queryFields.{templateId,serial}`、`itemsPath`、`dateFields`、`dateTransforms`、`epochUnits`、`dateFormats`、`timeZone`，以及 `endpoints.snRepetition`；六项兼容策略可整体省略，`numericEpochPrecision` 可省略并默认 `exact` | Profile 显式启用的重复查询和确定性日期解释。 |
| `recovery` | `version`、`issuanceMode`、`evidenceAlgorithm`、`keyId`、`publicKeySpkiHex`、`maxEvidenceAgeSeconds`、`reconciliationContractSha256`、`enabledOperations` | 仅为受控恢复提供 App-facing 公钥 capability；不包含私钥、远端结果或解锁按钮。 |

省略 operation 表示 backend 不提供该模块。Profile 不得启用一个 adapter 未声明的模块。

上一流程启用时，四组 rolling-upgrade classifier 都必须显式为数组，也可以显式为空。存在性 GET 的 `missingResponseCodes` 与 `missingMessagePatterns` 仍由新 App 使用：业务 non-success 只有精确命中已配置 code，或在已配置 scalar 消息字段中命中文案时，才可解释为“记录不存在”并进入纠正或自动创建；未分类响应立即停止，绝不发送创建 POST。后两组 recipe 数组 `retryableMessagePatterns` 与 `alreadyExistsMessagePatterns` 仅供旧 App 兼容，新 App 的 recipe POST 只接受上节的结构化 `recipeOutcomePolicy`。普通成功响应始终按通用 `response` contract 判断。公共 adapter 不提供任何业务码或部署文案 fallback，真实 classifier 只能保存在私有 catalog，并须用脱敏响应 replay 验证。

为了让旧 private adapter 能进入 Panel 完成迁移，基础 adapter 校验允许 missing classifier 两项整体缺失，也允许 legacy recipe classifier 两项整体缺失；任一组只填一项会报错。只要任一 profile 开启 `workflow.previousSteps`，Panel 发布门和 App 提交门都会要求四组数组完整存在。结构化 recipe outcome evidence 的发布门则只针对上节定义的实际可执行 recipe，不会因为关闭、无 trigger 或无 template 的配置而触发。兼容读取不表示旧响应语义已被自动还原。

### `operations.recovery`

迁移期间可省略；省略或无效时，普通登录、上传和提交仍按原 adapter 契约运行，但不能通过正式 release 的 controlled-recovery gate。正式 capability 必须使用 `version:1`、`issuanceMode:"panel_signed_exact_reconciliation"`、`evidenceAlgorithm:"RS256"`，提供可由 Android 解析的 RSA SPKI DER 小写 hex、1–3600 秒证据窗口、私有 reconciliation contract 原始字节 SHA-256，并在 `enabledOperations` 中无重复地包含 `FINAL_SUBMISSION`、`PREVIOUS_STEP_RECIPE`、`MULTIPART_UPLOAD`。

这里放的是非秘密的验证公钥与 contract 摘要；签名私钥、真实 endpoint 证据、server correlation、remote receipt、journal/pair cross-proof 和 replay 原文只能留在受控私有系统。Panel/Worker 的同步校验只阻止明显错误或可编辑 outcome 字段；Android `KeyFactory` 与正式 private gate 才做完整公钥/证据验证。

Capability 本身不能恢复任何记录。App 只有在原始 journal/barrier、原 Panel/catalog/backend 上下文、持久 one-time challenge、签名 evidence 和原子 write-back 全部精确匹配后才可能转换状态。本仓库当前尚未把这些步骤接入 operator-facing runtime；`Subject.request()` 只有 opaque hash 与 nonce，而原 POST 也没有把 recovery subject/operation id 发送到 authority。部署方必须先建立原请求发生时可用的服务端相关性来源，不能事后仅凭 subject hash 猜请求。验收格式与当前 release blocker 见 [Controlled-recovery 私有证据契约](./releasing.md#controlled-recovery-私有证据契约)。

### 动态上一工序 `template_detail` resolver

静态 recipe 已足够时不要配置这一能力。只有启用的 profile 的 `templates[]` 实际出现 `mode:"template_detail"` 时，发布门才要求 `endpoints.templateDetail`、`operations.templateDetail.idParam`、`operations.previousSteps.recipeResolvers` 与 `optionValueBuilders`。纯静态 profile 的验证路径保持不变。

`recipeResolvers` 与 `optionValueBuilders` 都是 ID 到对象的 map，分别最多 32 项。ID 是最多 128 字符的有限标识符。公共 example 中只有完整虚构的 resolver 示意，公开 seed 不启用它；真实 resolver 只能保存在 private catalog。

每个 resolver 只允许以下根 key：

```json
{
  "version": 1,
  "identity": {
    "templateId": { "type": "present", "path": "template.id", "fallbackIfMissing": 0 },
    "expectedStep": { "type": "present", "path": "template.step", "fallbackIfMissing": 0 },
    "warehouseId": { "type": "present", "path": "template.warehouseId", "fallbackIfMissing": 0 },
    "sku": { "type": "firstNonEmpty", "paths": ["template.sku"] }
  },
  "searchTextAttributes": ["title", "englishTitle", "typeName"],
  "optionSearchTextAttributes": ["title", "englishTitle", "id"],
  "kindSelectors": [],
  "rules": []
}
```

- `version` 必须为 `1`；`identity` 必须恰好提供 `templateId`、`expectedStep`、`warehouseId`、`sku` 四个 typed builder。前两项解析结果必须与 profile recipe 的值相同，后两项成为提交 identity。缺失、类型不符或不一致均 fail closed。
- `searchTextAttributes` 与 `optionSearchTextAttributes` 是显式、有序、无重复的数组，只能从 `id`、`type`、`parentType`、`typeName`、`title`、`englishTitle` 中选择，决定 search text 如何拼接；允许显式空数组，表示不构造 search text。App 不自行添加标题或 backend 字段。
- `kindSelectors` 是非空数组、最多 32 项，每项只有 `{kind,selector}`，同一 resolver 内 `kind` 唯一。它把受限 selector 的命中结果写入 canonical `kind`。
- `rules` 为非空、有序数组，最多 32 项；每项只有 `{selector,cardinality,action}`。`cardinality` 只能是 `exactly_one` 或 `first_in_backend_order`。前者要求恰好一项，后者仍按 backend 原始顺序确定第一项，不做名称排序。

Selector 只允许 `allOf`、`anyOf`、`noneOf`；每个出现的组必须是非空 predicate 数组，每组最多 16 项，三组合计最多 32 项。Predicate 只允许 `attribute`、`caseSensitive`，以及恰好一个 `equalsAny`、`containsAny` 或 `present`：

```json
{
  "allOf": [
    {
      "attribute": "searchText",
      "containsAny": ["sample accepted"],
      "caseSensitive": false
    }
  ],
  "noneOf": [
    {
      "attribute": "required",
      "equalsAny": [false],
      "caseSensitive": true
    }
  ]
}
```

可选 canonical attribute 仅为 `id`、`kind`、`type`、`parentType`、`typeName`、`title`、`englishTitle`、`searchText`、`required`、`visible`、`hasOptions`。`caseSensitive` 必须显式为 boolean；即使比较 boolean 或 number 也不能省略。`equalsAny` 接受有限 JSON scalar，`containsAny` 只接受非空 string，`present` 只接受 boolean。Rule 通过 `kind` 比较时只能使用 `equalsAny`，且每个值必须引用当前 resolver 已声明的 `kindSelectors.kind`；禁止对 `kind` 做模糊 `containsAny`。Regular expression、JSONPath、脚本、eval 与任意表达式都不是 DSL 的一部分，未知 key 会被拒绝。

Rule action 只有四种：

| `type` | 唯一允许的其他字段 | 语义 |
| --- | --- | --- |
| `serial` | 无 | 将当前标识写入唯一命中的字段。 |
| `photo` | `source`、`joinWith` | `source` 引用 profile recipe 的 source alias；多 URL 只按显式 `joinWith` 拼接。 |
| `fixedOption` | `optionSelectors`、`valueBuilder`、`onNoMatch` | 按有序 option selector 选择 option 并构造固定值。 |
| `omit` | `allowRequired` | 只有显式允许时才能省略 required 字段。 |

每个 resolver 必须恰好有一个 `serial` action，但不强制有 `photo` action。需要照片的 recipe 必须让所有 `photo.source` 与 profile recipe 的 `sources` 双向精确对应；不需要照片的 recipe 使用空 `sources` 和零个 `photo` action。这样可表达第一步上传证据、第二步只提交序列号/固定选项的旧流程，同时禁止 App 擅自沿用前一步照片。

`fixedOption.optionSelectors` 最多 16 项；每项只有 `{selector,cardinality}` 和可选 `literalOverride`。`literalOverride` 是受大小/深度限制的 JSON literal，命中时直接覆盖 builder 结果，不会被当作表达式。`onNoMatch` 只能是 `reject` 或 `use_value_builder`。`valueBuilder` 必须引用 `optionValueBuilders` 中已存在的 ID。

Builder 是封闭的 tagged union：

| `type` | 允许字段 | 语义 |
| --- | --- | --- |
| `literal` | `value` | 返回受限 JSON literal。 |
| `present` | `path`、`fallbackIfMissing` | path 存在即返回原值，包括空 string 与 JSON null；只有真正缺失时返回 literal fallback。 |
| `firstNonEmpty` | `paths` | 按显式顺序取第一个非空值，最多 16 条。 |
| `integer` | `path`、`default` | 读取整数；缺失时使用显式 integer default。 |
| `object` | `members` | 按成员名递归构造 object，最多 32 个成员。 |

Builder 最深 8 层。Path 最长 512 字符、最多 16 段，根和首个 key 只能是以下 canonical 值；更深层只能继续读取已取得 JSONObject 的安全成员：

- `template`：`id`、`name`、`sku`、`step`、`warehouseId`、`fieldList`；
- `field`：`id`、`type`、`parentType`、`typeName`、`title`、`englishTitle`、`required`、`visible`、`maxCount`、`options`、`kind`、`searchText`、`hasOptions`；
- `option`：`id`、`title`、`englishTitle`、`quantity`、`searchText`、`hasOptions`；
- `input`：`serial`；
- `identity`：`templateId`、`expectedStep`、`warehouseId`、`sku`。

Path 不支持 `$`、数组索引、通配符或原型相关 key。DSL string 最长 4096 字符。单次 live template 最多解析 256 个字段、每字段最多 256 个 option；超过限制立即停止，防止配置或响应导致无界工作。

上线前必须在隔离环境用受控私有、最小化但机器值保真的 live `templateDetail` fixture 做确定性 replay，至少覆盖：正确/错误 template identity、每条 field selector 的 0/1/多命中、每条 option selector 的 0/1/多命中、required 字段、缺失与空值、source alias、多照片、超限输入，以及 backend 字段顺序变化。对同一 fixture，旧版本与 candidate 必须产生相同的 warehouse/SKU、字段 key、serial、照片 URL 和固定 option payload；失败 fixture 必须在任何 upload 或 submit 前停止。凭据、真实 SN/照片和无关记录必须剥离，但证明等价所需的 identity/field/option 不得替换。未完成 replay 时保持对应 profile `compatibilityReviewed:false`。

### 独立入口 live override resolver

`operations.templateDetail.alternateEntryResolvers` 是 resolver ID 到封闭对象的 map。只有 `workflow.alternateEntries.entries[].dynamicOverrideProviders` 非空时才需要；provider 的 `resolverId` 必须精确命中该 map。公共 example 只使用虚构字段与 option：

```json
{
  "alternateEntryResolvers": {
    "sample-alternate-live-option-v1": {
      "version": 1,
      "searchTextAttributes": ["id", "title", "englishTitle"],
      "optionSearchTextAttributes": ["id", "title", "englishTitle"],
      "fieldSelector": {
        "allOf": [
          {
            "attribute": "id",
            "equalsAny": ["sample-live-choice"],
            "caseSensitive": true
          }
        ]
      },
      "optionSelector": {
        "allOf": [
          {
            "attribute": "id",
            "equalsAny": ["sample-selected-option"],
            "caseSensitive": true
          }
        ]
      },
      "valueBuilder": {
        "type": "present",
        "path": "option.id",
        "fallbackIfMissing": ""
      }
    }
  }
}
```

每个 resolver 只能有 `version`、两个 search-attribute 数组、`fieldSelector`、`optionSelector` 和 `valueBuilder`。Selector 使用上一节同一 allow-list DSL，但不得引用 `kind`；字段与 option cardinality 固定为 `exactly_one`，不能选择 `first_in_backend_order`。Builder 使用同一 typed union，但 path 根仅允许 `template`、`field`、`option`、`identity`，不接受 `input`。未知 key、脚本、regex、JSONPath 和 eval 一律拒绝。

Profile provider 负责声明目标 `templateId` / `expectedStep` 和唯一 `outputField`；隐藏目标提供预期 `warehouseId` / `sku`。Live response 的四项 identity 必须全部相符，命中的 live field ID 还必须等于 provider 的 `outputField`。Builder 结果不得为 null。Active provider 的响应集合必须与请求集合完全相等，缺失或多余响应均失败。

这一 resolver 只产生受 allow-list 限制的动态字段值，不得覆盖 serial、result、照片、固定/toggle owner 或 identity。Android 主流程必须先编译全部 active provider，再拉取并 resolve 全部 live template，最后才允许开始 upload/submit；任一阶段失败都不得继续或使用 fallback。只有 schema / 纯函数测试而没有完成这条主流程接线和受控私有 live replay 的 build，不得启用 provider。

### 提交前刷新列表项

`workflow.materials.refreshBeforeSubmit:true` 使用已有的通用模板契约，不在 App 中写死部署字段：

- 请求 `endpoints.templateDetail`，查询参数名来自 `operations.templateDetail.idParam`；
- 模板字段数组来自 `fields.template.fieldList`；字段 id、type/parentType/typeName、标题和 options 分别由 `fields.formField` 映射；
- 只有 type、parentType 或 typeName 命中 `conversion.fieldKinds.items` 的字段才被视为列表项组；
- option 的 code、名称和数量来自 `fields.option.{value,label,englishLabel,quantity}`。

一次批次只做一次逻辑刷新，顺序是打印预检之后、任何上传、上一流程 recipe 或提交之前。App 只替换当前 profile 已在 `materialGroups` 声明的 field，不会把 backend 新出现的未知 field 自动带入 payload；每个已声明组必须在响应中唯一出现且有非空 options。重复 code、缺组、空组、非法数量或解析不完整都会在任何条目提交前失败并保留草稿。

数量默认采用保守合并（`operations.templateDetail.existingQuantityPolicy` 缺失或为 `strict_live_match`）：已有 code 没有返回数量时保留 Panel 的 `defaultQty`；若 backend 同时返回数量，则必须与 Panel 值一致；新 code 必须返回正整数数量。

经私有 live replay 证明旧版以 Panel 数量为准的部署，可以显式设置 `existingQuantityPolicy:"profile_authoritative"`。此模式只对已经存在于当前 profile 的 code 使用其正整数 `defaultQty`；live quantity 若存在仍必须是正整数，但不取得所有权。未知的新 code 仍必须由 live response 给出正整数数量，否则刷新失败。两种模式都不会按名称、语言或产品词猜数量，非法枚举也会阻止启用材料刷新能力。生产 code 与数量继续只存在于私有 Panel profile/adapter。

开启前必须用受控私有、最小化但机器值保真的真实 `templateDetail` 响应做 replay，核对 field kind、组、code 与数量。选择 `profile_authoritative` 时还必须证明每个既有 code 的 Panel 数量与旧版 live 输出相等，并把 source/live 漂移单独记录；不能用该开关掩盖未核对差异。仅仅补齐 adapter key 不代表与旧版硬编码解析完全等价；未通过 replay 时保持 `refreshBeforeSubmit:false` 和 `compatibilityReviewed:false`。

### `duplicateCheck` 日期契约

重复记录的时间语义必须来自 adapter，不能由 App 根据数字长度、字符串形状或设备时区猜测：

- `dateFields`：在每条记录中按顺序检查的非空点路径数组；
- `dateTransforms`：解析文本日期前按数组顺序执行的显式转换；可以为空，元素不得重复，只允许 `iso_t_to_space`、`localized_ymd_to_dashes`、`strip_fractional_suffix`、`strip_trailing_z`、`truncate_after_seconds`；
- `epochUnits`：必须显式为数组，元素只能是 `seconds`、`milliseconds` 且不得重复；可只允许一种，也可为兼容已验证的混合返回明确同时允许两种；空数组表示不接受 numeric epoch；
- `dateFormats`：必须显式为 Java `SimpleDateFormat` pattern 数组，可以为空；但 `epochUnits` 与 `dateFormats` 不得同时为空；
- `timeZone`：必须是显式、有效的 IANA time-zone ID，例如虚构示例使用 `UTC`；即使当前 backend 只返回 epoch 也不能省略。

需要复现旧系统较宽松的日期解释时，可以直接在 `duplicateCheck` 下增加一组兼容策略。该组六项必须全有或全无：

- `epochDigitLengths`：无重复的 `1..19` 整数数组；空数组表示不按位数过滤 numeric epoch；
- `numericFractionPolicy`：`reject` 拒绝带小数的 JSON number，`truncate` 明确允许向整数截断；
- `numericEpochPrecision`：可选 `exact` 或 `minute_floor`，缺失等同 `exact`；`minute_floor` 只把 numeric epoch 在通过单位与合理性校验后向下归一到分钟，不影响文本日期；
- `textParseConsumption`：`full` 要求整个字符串被 pattern 消费，`prefix` 明确允许只消费前缀；
- `plausibilityScope`：`all` 对 epoch 和文本日期都执行合理时间窗口检查，`epoch_only` 只检查 epoch；
- `timeZoneSource`：`configured` 使用 `timeZone`，`device` 明确使用设备时区；
- `rootValueEnabled`：boolean；仅为 `true` 时允许把 `dateFields` 中的 `$` 当作整条返回值解析。

公开示例写明当前严格语义：`epochDigitLengths:[]`、`numericFractionPolicy:"reject"`、`numericEpochPrecision:"exact"`、`textParseConsumption:"full"`、`plausibilityScope:"all"`、`timeZoneSource:"configured"`、`rootValueEnabled:false`。除显式写出的 numeric epoch 精度外，这与六项全部缺失时的现有行为一致；不要为了“补字段”改成宽松值。只有本地签名旧版回放证明旧流程确实先格式化到分钟时，才由私有 Panel 设置 `minute_floor`。

五种转换分别用于把 ISO 日期时间分隔符 `T` 变为空格、把本地化年月日分隔写法变为连字符、移除秒后小数部分、移除末尾 `Z`，以及截断秒之后的内容。转换会改变输入，且顺序可能改变结果；只配置脱敏 replay 已证明需要的步骤。空数组表示原样解析，App 不自动套用任何转换。

App 只对文本日期按顺序应用 `dateTransforms`，纯整数不经过文本转换，只按 `epochUnits` 中明确列出的单位逐项尝试。六项兼容策略缺失时，继续使用上面列出的公开严格语义；存在时只执行显式选择的消费、合理性、时区和 root-value 规则，不做额外猜测。`numericEpochPrecision` 缺失时仍为 `exact`，只有 Panel 明确设置 `minute_floor` 才恢复 numeric epoch 的旧分钟精度。`workflow.duplicateCheck.agePolicy.unit=calendar_months` 使用同一解析策略解析出的时区来源。只要查询返回记录但所有已配置日期都无法解析，App 就执行 profile 显式声明的 `unknownDateAction`；不会自动应用未列出的转换、猜数字单位或自动当作可重录。

为让旧 private adapter 仍能完成 Panel 登录和迁移，基础 adapter 校验只把 `dateTransforms`、`epochUnits`、`dateFormats`、`timeZone` 四项全部缺失视为 legacy inactive state；任一出现就要求四项全部有效。只要准备发布任一 `workflow.duplicateCheck.enabled:true` 的 profile，Panel capability 校验就要求 duplicate operation、`snRepetition` endpoint 与这四项全部显式有效，否则拒绝发布。六项兼容策略是独立的 optional group：全部缺失时保留现有严格行为，出现任意一项时 Panel 与 App 都要求六项全部存在且合法。`numericEpochPrecision` 可缺失，但单独声明它不能绕过前述完整性校验。Android 也只在当前 profile 启用重复检查时把旧四项缺失视为提交配置错误。

这只是代码层的迁移兼容，不代表旧 live catalog 已被无损转换。转换顺序、Java pattern 和任何非严格兼容选项是否符合真实 backend 输出仍须用专用脱敏数据验证；先备份并在 private adapter 中补齐旧四项，新六项仅在 replay 证明需要时成组配置，再开启任何 profile 的重复检查。

## Optional printing

`printing` 可完全省略。`{"enabled":false}` 也表示关闭，其他字段不参与运行。只有 `enabled:true` 时才要求：

- 三个 printing endpoint；
- `online.statusPath` 与非空 `online.values`；
- `jobsPath`；
- `query.serialParam`、`pageParam`、非负整数 `pageStart`；
- `fields.id/serial/type/status`；
- `values.acceptedTypes/printed/failed/ongoing`；
- `retryIdField`。

打印 v1 还有以下运行前提：

- `online.values`、`acceptedTypes`、`printed`、`failed`、`ongoing` 都必须非空；三个状态集合必须互不重叠，未知状态保持“未确认”，不能同时归入成功与失败。
- `fields.serial` 必须能与当前 profile 的主标识进行**精确字符串匹配**。公共 App 不做模糊匹配、前缀匹配或部署特有规范化。
- `fields.id` 必须是正数值或可无损解析为正整数的字符串，并在同一标识的任务中单调递增；App 以最大 ID 选择最新的已接受任务，补打也按这个 ID 发送。
- `messageList` 在配置的 `pageStart` 查询结果中必须包含足以确定当前标识最新任务的数据；v1 不遍历后续分页。
- `acceptedTypes` 是 adapter 级 allow-list。若多个 profile 共用打印服务，必须保证这些类型与精确标识组合不会把别的任务关联到当前记录。
- 自动补打只针对明确属于 `failed` 的任务。手动补打由 profile 的 `manualReprintStatuses` 单独允许 `failed`、`ongoing` 和/或 `unknown`；`printed` 与未找到任务不能加入 allow-list。发送补打请求前，App 始终重新查询并要求最新任务 ID 精确相同、当前状态仍在 allow-list。
- `manualReprintRequiresConfirmation:true` 在发送前显示确认；设为 `false` 只省略对话框，不省略上述最新任务复查。
- 手动与自动补打共用持久化 journal，先绑定 Panel/catalog/backend/profile/session/policy、精确 job/SN 与 payload hash，再发出 POST。printing v1 尚无 Panel-owned “明确未受理” classifier；因此只有命中已配置的通用 success contract 才直接完成该补打 POST。Socket 可能已发出后的 business non-success、transport/解析错误或本地清理失败都保持 `UNCERTAIN`，阻止同一远程任务重放以及 profile/Panel/catalog 切换。之后的状态查询只有在响应成功、同一远端上下文返回同一 job ID 与 SN、且 adapter 将状态分类为 printed 时，才可原子清理同一条未解决记录；缺失、failed、ongoing、unknown、查询失败或绑定不同均不清理。

不能满足这些约束时，将 `backendAdapter.printing.enabled` 和所有 profile 的 `workflow.printing.enabled` 保持为 `false`。Profile 只决定是否启用、预检动作、轮询/等待、批末缺失任务是否执行两轮复查、未知状态的 UI 呈现、自动补打上限、未确认动作、手动补打开关、手动允许状态与是否再次确认；endpoint、字段和 backend 状态值仍由 adapter 声明。兼容呈现不会把 adapter 未识别状态重新分类，`inline_only` 也不会关闭停止前检查、精确 session/job/SN 绑定或补打 journal。

## HTTP 重试边界

Adapter 不包含隐式的副作用 transport retry。为贴近旧版本的只读网络体验，App 的底层 GET 在 DNS、连接、超时、TLS 或 502/503/504 这类瞬时错误后等待 3 秒，最多再请求一次（合计最多两次）；非瞬时错误不重试。每个 POST、multipart upload、OCR upload 与打印 POST 仍只执行一次，避免在不知道后端幂等性的情况下重放请求。GET 的第二次尝试会重新检查当前 Panel、session 与远程 worker gate，配置已经变化时不会沿用旧上下文继续。

Profile 的 `workflow.previousSteps.recipeMaxAttempts` 只有在上一工序独立的 `recipeOutcomePolicy.retryableNotWrittenRules` 明确命中、证明 recipe 未写入时才允许进入下一次；它不是 submit `outcomePolicy` 的复用。该上限是每个精确 recipe 位置跨进程/重启/再次提交的持久累计总数；policy 缺失时有效上限收敛为一次，已完成前缀会保留，`UNCERTAIN` 不得继续。`workflow.submission.maxAttempts` 则只在最终 submit non-success 命中其自己的 `outcomePolicy` retryable / missing-material not-written rule 后进入下一次。所有消息分类器只读取 `response.messageFields` 声明路径上的 scalar 值，绝不扫描完整响应、对象/数组、data、请求回显或其他任意字段；同一关键词只出现在这些非消息字段时必须保持未分类并停止。上一工序 recipe 和最终 submit 都在 socket 调用前写入独立 journal；transport、解析、分类冲突或未分类响应保持锁定，不会自动重放对应 POST。

会话失效也不能把已经开始的副作用 POST 解释为“确定未写入”。主流程/独立入口最终提交或上一工序 recipe 在 socket 调用后遇到 configured session-invalid HTTP 状态、业务码或消息时，App 会先把对应 attempt 持久化为 `UNCERTAIN` 再交给登录恢复；重新登录不会清理该 journal、改写为明确拒绝或自动重放。其他副作用 POST 同样不能仅凭 session-invalid 信号获得重试授权。

独立录入使用 entry 自己的 `submissionRetry.{maxAttempts,retryDelayMs}`，而且只有最终响应明确命中 submit `outcomePolicy.retryableNotWrittenRules` 时才重试。照片只上传一轮；App 在循环前冻结返回 URL、最终 payload、请求字节、目标 identity 与 operation ID，后续尝试复用完全相同的请求。明确拒绝达到上限后，最终 POST journal 可按“确定未写入”清理，但 upload barrier 仍保留，防止下一次手工操作重新上传同一批照片；因此这条耗尽路径仍是需要受控处理的兼容差异，不能描述成与旧版完全一致。任何 transport、网关、解析或未分类结果都立即写为 `UNCERTAIN` 并停止。

`workflow.submission.networkRetry` 仍是整单元策略，但只允许重放尚未开始 upload 的只读准备。首个 multipart socket 前，App 会同步保存独立的 upload replay barrier；开始上传后本次整单元重试立即关闭，任何不确定结果跨重启锁住后续远程动作、profile/Panel 切换和 App 安装，直到原后端经受控流程核对。Barrier 不保存返回 URL，也不会续传、删除孤立文件或给 backend endpoint 提供幂等性。只有后端对受影响操作提供经过验证的 idempotency key、唯一约束或等价去重保证时才设置非默认重试值。Legacy `alreadyExistsMessagePatterns` 只能为仍在使用的旧 App 保留；新 App 只有在私有 replay 绑定的 `recipeOutcomePolicy.alreadyExistsAcknowledgedRules` 命中时才确认同一 recipe 已完成。Adapter v1 本身不声明或证明幂等性。

## 与通知适配器的边界

通知提供方协议不属于 `backendAdapter`。它由 Panel 的 `notificationAdapter` 配置，保存在 Worker-only `panel-settings.json`。App-facing `/api/config` 只返回：

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

`catalogVersion` 来自同一次 `readProfiles` snapshot，并与该 snapshot 的 `form-profiles.json.version` 相同；它不含业务信息或 secret。新 App 用它拒绝同一 Panel origin 下不同 catalog revision 的 adapter/profile 混配。未初始化或非正整数 version 时 `/api/config` 返回不可用，不发明 fallback version。

Provider URL、provider body 与 Panel-owned `eventTemplates` 均不下发。App 只构造事件类型对应的严格结构化白名单，不把 backend message、response 或其他自由文本映射进事件。Profile 提交汇总与全局 diagnostics 分别显式开启，缺失时默认关闭。

详见 [Global configuration](./configuration.md#notificationadapter)。

## 验证与迁移

在 `panel/` 中运行：

```sh
npm test
```

部署后使用完全虚构或专用测试数据验证：Panel 登录与模板导入、App 登录、上传、提交、result conversion，以及每个已启用的可选 operation。

迁移期扁平 host、endpoint 和 session 字段只用于旧 catalog。现有部署应先上线能读取旧 catalog 的新 Panel，再在 private adapter 中补齐日期/打印契约并保持相应 profile 模块关闭；确认旧 App 的登录、上传和提交未受影响后，才测试并发布新 App，最后逐项启用新模块和移除重复来源。不要把已填写 adapter 复制到公开源码、fixture、日志或 Release asset。完整顺序、旧 App 重启预热要求和 signed-device 门禁见 [Staged upgrade](./worker-setup.md#10-staged-upgrade-for-an-existing-deployment) 与 [Releasing](./releasing.md)。
