# Profile schema 参考

Catalog 当前使用 `schemaVersion: 2`。Profile 是 Panel 发布给 App 的运行描述；表单可见性、字段、结果、照片、扫描和可选流程都应由 profile 决定，而不是由 App 猜测。

优先使用 Panel 的结构化编辑器与预览。只有尚无控件的字段才使用「高级 JSON」。本页示例均为虚构数据；真实模板、字段、选项和流程标识只应进入私有 catalog。

## Catalog envelope

```json
{
  "schemaVersion": 2,
  "version": 1,
  "settings": {},
  "profiles": []
}
```

- `schemaVersion`：App 可解释的结构版本；过新的 catalog 不会替换有效缓存。
- `version`：每次发布单调递增；App 只应用更新版本。
- `settings`：App-facing 全局配置；包括私有部署的可选 exact `{version:1,owner,repo}` `updateSource`、旧 App 使用的可选 `dailyStats`、新版 App 使用的可选 `dailyStatsV2`，以及独立录入统计归属 `dailyStatsAlternateEntries`；Worker-only 配置不在此文件。更新源必须与旧 flat owner/repo 完全一致，不能包含 channel/tag/asset；省略时保留旧 flat 兼容，不能把真实值写入公开 seed。
- `profiles`：非空、有序的 profile 数组。

Panel 同步生成 `manifest.json`，包含 payload SHA-256、`profilesUrl` 和可选 `minAppVersionCode`。不要绕过 Panel 分别编辑这两个文件。

### `settings.dailyStats`

这是为旧 App 保留的 v1 result-key 汇总。登录页没有一个可安全代表所有表单的“当前 profile”；App 不从 result 标签、提交值、机型名称或 profile 顺序猜测业务含义。完全虚构的示例：

```json
{
  "dailyStats": {
    "scope": "all_profiles",
    "groups": [
      {
        "id": "sample-ready-summary",
        "label": "示例完成汇总",
        "labelI18n": {
          "en": "Sample ready total",
          "es": "Total de ejemplo listo"
        },
        "uiColor": "#2563EB",
        "resultKeys": ["sample-ready"]
      },
      {
        "id": "sample-review-summary",
        "label": "示例复核汇总",
        "uiColor": "#7C3AED",
        "resultKeys": ["sample-review"]
      }
    ]
  }
}
```

- `dailyStats` 只允许 `scope` / `groups`；`scope` 必须精确为 `all_profiles`。
- `groups` 按数组顺序显示，必须包含 `1..16` 项。每项只允许 `id`、`label`、可选 `labelI18n`、`uiColor` 和 `resultKeys`。
- `id` 必须唯一、非空且最长 128 characters；`label` 及每个 `labelI18n.en/es` 必须为去除首尾空白后的非空文本，最长 160 characters；`uiColor` 必须是完整 `#RRGGBB`。
- `resultKeys` 必须包含 `1..128` 个组内唯一的精确 key，每个最长 256 characters；同一个 key 不能进入多个组。每个 key 必须由至少一个显式 `pickerVisible:true` profile 的 `gradeMap` 声明，隐藏独立入口目标不能单独授权登录页汇总项。
- 省略整个 `dailyStats` 表示旧 App 不启用全表单汇总。Panel 结构化全局编辑器负责添加、排序和删除组；保存时再次执行同一严格校验。

v1 只有 result key，没有 profile identity；同一个 key 在不同 profile 代表不同结果时无法安全拆分。因此迁移期应保留现有 v1 给旧 App，同时为新版 App 配置下面的 v2，不能把两者互相嵌套或用 App 常量补足歧义。

汇总卡片的文案和颜色属于 `dailyStats.groups`；主表单结果按钮分别使用各 profile 的 `gradeMap[key].operatorLabel` / `operatorLabelI18n`（缺失时回退只读的 `label` / `labelI18n`）和 `uiColor`。两者不得通过 App 内置业务常量绑定。

### `settings.dailyStatsV2`

`dailyStatsV2` 是与 v1 `dailyStats` 同级的精确汇总契约。每个 selector 都同时选择显式可见 profile 与其 `gradeMap` result key，因此相同 key 在不同 profile 中可以分别归类。完全虚构的示例：

```json
{
  "dailyStatsV2": {
    "version": 2,
    "scope": "all_profiles",
    "groups": [
      {
        "id": "sample-ready-exact",
        "label": "示例完成精确汇总",
        "labelI18n": {
          "en": "Exact sample ready total",
          "es": "Total exacto de ejemplo listo"
        },
        "uiColor": "#2563EB",
        "selectors": [
          {"profileId": "example-intake", "resultKey": "sample-ready"}
        ],
        "legacyResultKeys": ["sample-ready"]
      }
    ],
    "flatSummaries": [
      {
        "id": "sample-attention-flat",
        "label": "示例需处理统计",
        "labelI18n": {
          "en": "Sample attention",
          "es": "Atención de ejemplo"
        },
        "uiColor": "#B45309",
        "selectors": [
          {"profileId": "example-checklist", "resultKey": "sample-review"}
        ]
      }
    ]
  }
}
```

- 根对象只允许 `version`、`scope`、`groups`、`flatSummaries`；`version` 必须是整数 `2`，`scope` 必须是 `all_profiles`。`groups` 必须有 `1..16` 项，`flatSummaries` 必须有 `0..8` 项。
- 每个 group 只允许 `id`、`label`、可选 `labelI18n`、`uiColor`、`selectors` 与可选 `legacyResultKeys`；flat summary 不允许 `legacyResultKeys`。两类 item 的 `id` 在整个 v2 对象内全局唯一，最长 128 characters。标签只允许 fallback 与可选 `en` / `es`，各最长 160 characters；颜色必须是完整 `#RRGGBB`。
- group 的 `selectors` 必须有 `1..512` 项；flat summary 通常也遵循该范围，但当其计数全部来自独立录入时可以显式写 `[]`，前提是 `dailyStatsAlternateEntries.flatSummaries` 中存在同 `id`、非空且完整有效的入口映射。缺少该联合映射时 Panel 拒绝发布，Worker/App 也不会下发或显示一个误导性的零值行。非空 selector 每项只允许无首尾空白且最长 256 characters 的 `profileId` / `resultKey`；该 pair 必须由对应的显式 `pickerVisible:true` profile 自己的 `gradeMap` 声明，不能只因另一个 profile 有同名 key 而通过。
- 同一 item 内 pair 不得重复；pair 不得跨 groups 重复，也不得跨 flat summaries 重复。flat summary 可以明确复用 group 中的 pair，因为它是附加展示，不是另一个主分组。
- `legacyResultKeys` 只用于把旧版本地 `legacyResults` 的未分 profile 计数明确归入某个 group；存在时必须有 `1..128` 个互不重复的 key，每个 key 必须同时出现在该 group selector 的 `resultKey` 中，并且不能分给多个 group。没有可靠归属时必须省略，App 和 Panel 都不会按名称、标签、颜色或 backend value 猜测。
- `groups` 的计数组成今日总数；`flatSummaries` 在卡片下方单独显示，不再次计入总数。数组顺序就是显示顺序。
- 省略 v2 时，新 App 回退读取 v1；存在有效 v2 时优先使用 v2。旧 App 忽略同级 v2 并继续读取未改动的 v1，因此升级迁移不要求先删旧配置。

### `settings.dailyStatsAlternateEntries`

普通表单结果和独立录入事件使用不同的本地计数 namespace。该补充契约只负责把一个显式入口 identity 归入已有 v2 item；它不会改变提交目标、payload 或 `gradeMap`。完全虚构的示例：

```json
{
  "dailyStatsAlternateEntries": {
    "version": 1,
    "scope": "all_profiles",
    "groups": [],
    "flatSummaries": [
      {
        "id": "sample-attention-flat",
        "selectors": [
          {"profileId": "example-intake", "entryId": "sample-independent-review"}
        ]
      }
    ]
  }
}
```

- 根对象只允许 `version`、`scope`、`groups`、`flatSummaries`；`version` 必须是整数 `1`，`scope` 必须是 `all_profiles`。两个 collection 都必须是数组且可以为空；整个对象没有 selector 时应省略。
- 每项只允许 `id` 与非空 `selectors`。`groups[].id` 必须精确引用 `dailyStatsV2.groups[].id`，`flatSummaries[].id` 必须精确引用 `dailyStatsV2.flatSummaries[].id`；同一 collection 内 item id 唯一。
- selector 只允许 `profileId` / `entryId`，各为无首尾空白的非空文本且最长 256 characters。`profileId` 必须属于显式 `pickerVisible:true` source profile，`entryId` 必须唯一引用该 profile 上启用的 `workflow.alternateEntries.entries`。
- selector 在同一 item 内不得重复，也不得跨同一 collection 的 item 重复；同一 selector 可以同时出现在一个 group 和一个 flat summary。每项最多 512 个 selector。
- App 使用 `profileId + entryId + serial` 的独立幂等 identity 记账，并把计数放在按 Panel connection 隔离、旧 App 不读取的 App-private store。它不会把入口的 backend `resultKey` 或新对象字段写进普通 `results` / `daily_stats_*` 回滚镜像，因此选择入口不会污染普通结果计数，也不会破坏旧 App 对既有当日统计的读取。计数失败只影响展示；App 会在仍有 `COMPLETED` tombstone 时幂等补记，但不会因此阻塞已确认提交的本地清理或下一次生产提交。
- 该对象依赖有效的 `dailyStatsV2`；当某个 v2 flat summary 的普通 `selectors` 为 `[]` 时，同 `id` 的这里的 flat item 是强制项且必须含非空有效 selectors。其他缺失、引用失效或结构错误会关闭独立录入汇总，不改变独立录入提交；联合配置中的孤立空 flat summary 也会 fail closed，不会显示假 0。旧 App 忽略未知的同级 setting，并继续使用原有统计与表单。

## 身份、可见性与模板

| 字段 | 必需 | 说明 |
| --- | --- | --- |
| `id` | 是 | Catalog 内稳定且唯一的 identity。修改它等同于创建新 profile。 |
| `displayName` | 是 | App 显示名。 |
| `searchText` | 是 | 搜索文本。 |
| `pickerVisible` | 建议显式填写 | 新 catalog 中只有 `true` 才在 App 表单选择器中出现；`false` 隐藏。需要用户进入的每个 profile 都应显式设为 `true`。 |
| `uiColor` | 否 | Profile UI 色，格式为 `#RRGGBB`。 |
| `template.id` | 提交时 | 后端模板 identity。 |
| `template.sku` | 提交时 | 后端提交值。 |
| `template.warehouseId` | 提交时 | 后端提交值。 |
| `template.step` | 视后端 | 可选模板元数据。 |

App 没有特殊入口类型。所有需要在选择器中访问的表单统一使用 `pickerVisible:true`。Panel 不会从名称、序列号、SKU 或相邻数字推断模板 identity。

Template import 还会保留 profile-level `brand`、`model`、`color`、`graded` 与 `discovery`，用于
旧 catalog / client 兼容和导入 provenance。当前主表单 UI 使用 `displayName` / `uiColor`；
`graded` 只增加 Panel 的 result-map 发布检查，`model` / profile `brand` 只可能由默认禁用的
cross-App profile provider 暴露，`color` / `discovery` 不驱动当前主表单流程。这些 metadata 仍可能
包含 deployment identity，必须留在私有 catalog，但不能被用作绕过显式 profile/workflow 字段的
隐藏策略。

兼容旧部署时，若整个 catalog 的所有 profile 都完全没有 `pickerVisible`，App 暂时沿用旧版“全部可见”的行为，避免升级后选择器变空。只要任一 profile 出现该字段，catalog 就进入显式模式：只有严格的 boolean `true` 可见，缺失、类型错误和 `false` 都隐藏。迁移后的 Panel catalog 必须为每个 profile 显式补齐该字段；这个 catalog 级 fallback 不能用来隐藏独立入口目标。

## 序列号与扫描

`snFields` 只映射 App 的两个专用输入角色：

```json
{
  "snFields": {
    "primary": "example_serial",
    "secondary": "example_secondary_serial"
  }
}
```

额外输入必须放在 `snPlugins`，不能在 `snFields` 中发明第三种角色。`snPlugins` 的每项可包含 `key`、`field`、`label`、`labelI18n`、`required`、`search`、`scan` 和 `placeholder`。`snPluginsHidden` 保存从模板导入但默认不显示的项。`key:"primary"` 与 `key:"secondary"` 对应 App 的两个专用扫码入口；其他 key 是普通 profile-owned 文本输入，`scan:true` 和 `scanner` 都不允许用于额外 key，Panel 预览也不会为它们显示不存在的扫码按钮。

扫描策略优先由两个专用 `snPlugins` 分别提供：

```json
{
  "snPlugins": [
    {
      "key": "primary",
      "field": "example_serial",
      "label": "示例主要标识",
      "scan": true,
      "scanner": {
        "expectedLength": 10,
        "applyExpectedLengthTo": ["ocr", "barcode", "entered"],
        "allowedLengths": [9, 10, 11],
        "applyAllowedLengthsTo": ["ocr", "barcode", "entered"],
        "preferredPrefixes": ["ZX"],
        "autoTextMode": "fallback",
        "rejectNumericOnly": true,
        "candidateMode": "ranked",
        "candidateOrder": ["label", "prefix", "general"],
        "minLength": 8,
        "maxLength": 14,
        "requireLetterAndDigit": true,
        "rejectedSubstrings": ["IGNORE"],
        "stripLabels": ["S/N"],
        "labelMatchMode": "compact_optional_slash",
        "candidateCharacterMode": "alphanumeric",
        "applyCandidateRulesTo": ["ocr"],
        "stripLabelsFrom": ["ocr"],
        "caseMode": "upper",
        "removeWhitespace": true
      }
    }
  ]
}
```

- `snPlugins[].scanner` 只对相应 role 生效；primary 与 secondary 的长度和规则互不继承。
- primary 插件没有 `scanner` 时可迁移 fallback 到 profile-level `scanner`；secondary 不使用该 fallback。
- `expectedSnLength` 和 profile-level `scanner.preferredSnPrefixes` 仅保留给旧 catalog 迁移；新配置使用 role scanner 的 `expectedLength` / `preferredPrefixes`。
- 新建 profile 显式写 `scan:true` 时必须同时提供该 role 的 scanner（primary 可显式提供 profile fallback）。缺失会被 Panel 拒绝发布，App 也会停止该入口，不猜生产规则。
- 旧 profile 省略 `scan` 的状态仍保留升级前的通用 scanner 默认，便于分阶段迁移；这不是“已完成等价核对”。迁移时应显式补全 scanner，再写 `scan:true`。
- `scan:false` 会同时隐藏首页扫码按钮和记录详情重扫按钮；手输值仍执行该 role 已配置的内容约束。
- 公开示例不得包含真实长度、序列号、前缀、排除文本或标签；上例全部为虚构值。

Scanner 对象支持：

- `expectedLength`、`minLength`、`maxLength`：`1..256`；min 不大于 max，expected 必须落在显式边界内。`expectedLength` 的适用入口由 `applyExpectedLengthTo` 决定；min/max 属于下面可选择 source scope 的候选约束；
- `allowedLengths`：可选的非空、无重复 `1..256` 整数数组，每项必须落在显式 `minLength` / `maxLength` 内。它可与 `expectedLength` 同时存在以兼容尚不识别列表的旧 App，但此时 `expectedLength` 必须是列表成员；新版优先使用 `allowedLengths`。`applyAllowedLengthsTo` 可选，规则同样只能使用非空、无重复的 `ocr`、`barcode`、`entered`；配置它时必须同时配置 `allowedLengths`，省略时列表默认应用于三个入口；
- `applyExpectedLengthTo`：`expectedLength` 存在时可选的非空、无重复 source 数组，只能使用精确小写值 `ocr`、`barcode`、`entered`。三种来源可独立开关；迁移旧独立入口且旧版只限制相机结果时，应显式写 `["ocr", "barcode"]`，不能为了复用相机长度而收紧手输。为兼容已有主表单，`expectedLength` 存在但本字段缺失时仍按三种来源全部适用；反之，本字段存在而 `expectedLength` 缺失、数组为空、类型错误、重复、带首尾空白或含未知 source 时，App policy 会 fail-closed；
- `preferredPrefixes`（迁移 fallback 也接受 `preferredSnPrefixes`）：非空字符串数组；
- `autoTextMode`：空字符串、`always` 或 `fallback`；
- `rejectNumericOnly`：boolean；
- `candidateMode`：`ranked` 或 `ordered`。`ranked` 使用通用来源/行/长度/前缀/数字评分；`ordered` 严格选择 `candidateOrder` 中最先出现的可用来源；
- `candidateOrder`：由 `label`、`prefix`、`general` 组成的非空、无重复数组。`label` 从 `stripLabels` 后提取 token，`prefix` 从 `preferredPrefixes` 开头提取，`general` 提取通用 `[A-Z0-9._/-]` token；
- `requireLetterAndDigit`：为 true 时候选必须同时包含至少一个字母和一个数字；
- `rejectedSubstrings`：候选包含任一配置文本时拒绝；真实排除词只能保存在私有 profile；
- `stripLabels`：定义可识别的 label candidate；`stripLabelsFrom` 决定还在哪些来源的 payload 开头剥离 label 与紧随的空白或 `:` / `：` / `#` / `=` / `-`，只能使用精确小写值 `ocr`、`barcode`、`entered`。缺失时只对 OCR 使用，避免升级时改变既有手输或条码 payload。若条码内容带 `SN:` 一类标签，必须在私有 Panel profile 中同时显式列出该 label，并把 `barcode` 加入 `stripLabelsFrom`；需要严格区分标点时把完整 label（包括标点）作为 `literal` 值配置。App 不内置任何产线 label，缺少 label 配置时原值不会被猜测性剥离，source 类型错误、重复、带首尾空白或未知时 policy fail-closed；
- `labelMatchMode`：`literal` 按 `stripLabels` 字面匹配；`compact_optional_slash` 在 label 内忽略任意空白并允许斜杠缺省，可通用表达印刷标签的紧凑/分隔变体，不在 App 写死某个 label；
- `candidateCharacterMode`：`identifier` 允许字母、数字及 `._/-`；`alphanumeric` 只允许字母数字；
- `applyCandidateRulesTo`：非空 source 数组，可含 `ocr`、`barcode`、`entered`，且必须包含 `ocr`。它决定 min/max、字符模式、字母数字组合和排除文本还要应用到哪些入口；`expectedLength` 使用独立的 `applyExpectedLengthTo`，`rejectNumericOnly` 仍应用于三种来源。`entered` 包含首页手输、详情编辑和结果回填，`barcode` 包含首次扫码与相机重扫。配置希望候选约束在所有入口不可绕过时写三项；回放只在 OCR 候选阶段执行这些规则的旧算法时显式只写 `["ocr"]`；
- `caseMode`：`upper` 或 `preserve`；`removeWhitespace`：是否移除全部空白；
- `prompt` 与可选 `promptI18n`：Panel 定义的扫码提示。

`always` 在 scanner 打开约 350ms 后开始本地文字候选，`fallback` 约 1200ms 后才开始；两者文字尝试间隔均为约 650ms。相机仍使用 App 固定的稳定结果复核与安全 zoom，它们不是生产 profile 差异。要回放旧的评分型候选算法，应使用 `candidateMode:"ranked"`，显式列出旧算法实际使用的 candidate sources，并把旧前缀、排除文本、长度、label 匹配、字符模式、source scope、归一化和字符约束逐项迁到私有 role scanner；不得把这些真实值写回公开源码。

缺少整个 scanner 的旧 profile 使用已存在的通用默认：大写、移除空白、`ranked`、prefix/general candidate、OCR 不自动启动、相机候选长度 6..64，且手输不额外套用隐式 6..64。旧 profile 通过 `expectedSnLength` 迁移出的 `expectedLength` 在未声明 source scope 时继续覆盖 OCR、barcode 和 entered；旧独立入口要保持“相机校验长度、手输不校验长度”，必须在私有 Panel profile 中显式写 `applyExpectedLengthTo:["ocr","barcode"]`。配置字段一旦存在但类型、范围或交叉约束错误，Android policy 会 fail-closed，不接受候选或录入；Panel 会在更早阶段拒绝发布。这样升级不会把拼错的生产规则静默变成宽松默认。

## 照片框与动态提示

`photoSlots` 定义主要照片框，`optionalSlots` 使用同一结构：

```json
{
  "defaultPhotoOrder": "fronts_then_backs",
  "photoSlots": [
    {
      "field": "example_photo_one",
      "title": "示例照片一",
      "titleI18n": {
        "en": "Example photo one",
        "es": "Foto de ejemplo uno"
      },
      "minPhotos": 1,
      "maxPhotos": 3,
      "required": true,
      "conditional": false
    },
    {
      "field": "example_photo_two",
      "title": "示例照片二",
      "minPhotos": 1,
      "maxPhotos": 3,
      "required": true,
      "conditional": false
    }
  ]
}
```

- `field` 必须来自部署 backend 的实际模板。Panel 会校验本地结构、重复 owner 与 profile 内引用，
  但 publish 时不会重新查询 live backend 来证明该 field 仍存在；模板刷新与私有 payload replay
  仍是上线证据。
- `title` 是 fallback；`titleI18n` 可提供 `en`、`es`。
- `minPhotos` / `maxPhotos` 决定数量边界；可选框通常使用 `minPhotos:0`。
- `defaultPhotoOrder` 允许 `fronts_then_backs` 或 `front_back_per_unit`。
- `optionalSlots` 只有在 `workflow.photos.includeOptionalSlots:true` 时才进入 App 的拍摄、校验、上传和 payload；迁移默认是 `false`，避免把旧目录中仅供预览的可选框自动变成现场提交字段。

在分组拍摄从已完成照片框切换到下一照片框时，提示由**当前框标题**和**下一框标题**动态组成：

- `zh`：`{当前框标题}已拍完，开始拍{下一框标题}。`
- `en`：`{current title} is complete. Start {next title}.`
- `es`：`{current title} completado. Empiece con {next title}.`

因此提示不会写死方向名称；Panel 中的框顺序、`title` 和 `titleI18n` 是显示来源。

### 旧版 `uploadFields`

当前 App 仍读取没有非空 `photoSlots` 的旧 profile 中的 `uploadFields`，用于 v1.0.4–v1.0.6
迁移兼容。每项至少包含模板 `field`；可选 `title` / `titleI18n` 也会作为照片框切换提示的
显示来源。旧路径的 wire 行为并不是新的通用 schema：`sources` 只识别 `front` / `back`，
省略时按数组位置分配正反面，第二项还承接 supplemental URL，最终 URL 使用逗号连接。

因此新 profile 应使用 `photoSlots` / `optionalSlots`。App 仍按上述位置规则读取已缓存的旧
`uploadFields`，但 Panel 的任何新发布都要求每项显式写出非空 `sources`，不再创建依靠位置猜测
正反面的新目录。迁移中的 `uploadFields` 必须原样留在私有 catalog，不能重排、猜字段或按标题改
映射；只有用旧 APK 与 candidate App 的 exact payload replay 证明等价后，才可转换为新 slot。
Panel advanced JSON 和发布校验仍会检查结构、非空 field、重复 owner 与 profile 内引用，但不会
重新查询 live backend；这不等于已自动完成无损迁移。

## 结果映射

`gradeMap` 是为 wire compatibility 保留的字段名，实际语义是**结果映射**。它的 key 是任意、非空、不透明的 result key；App 不限制 key 集合，也不应从 key 猜测业务含义。

```json
{
  "gradeMap": {
    "option-one": {
      "field": "example_result",
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
      "value": "EXAMPLE_RESULT_ONE"
    },
    "option-two": {
      "field": "example_result",
      "label": "示例结果二",
      "operatorLabel": "示例操作项二",
      "uiColor": "#7C3AED",
      "value": {
        "code": "EXAMPLE_RESULT_TWO"
      }
    }
  }
}
```

每项必须有 `field`、`label` 和任意 JSON 类型的 `value`；`labelI18n`、`operatorLabel`、`operatorLabelI18n` 与 `uiColor` 可选。省略或留空整个 `gradeMap` 表示该 profile 不要求选择结果。

`label` / `labelI18n` 是旧 App 与模板导入使用的兼容字段，Panel 的结构化结果编辑器不会改写它们。新版操作员界面优先显示 `operatorLabelI18n` / `operatorLabel`，缺失时才回退旧标签。`operatorLabel` 及 `operatorLabelI18n.en/es` 一旦提供，必须是无首尾空白的非空文本，最长 160 characters；多语言对象只允许 `en`、`es`。这些显示字段绝不能改变 result key、`field` 或任意 JSON `value`。

`backendAdapter.conversion.result.mappings` 决定导入模板时如何生成这些 key、兼容标签、操作员标签、颜色和提交值。公开源码不会提供某个部署的默认结果集合。

## 通用提交字段

### `choiceFields`

单选 / 多选字段可包含：

- `field`、`title`、可选 `titleI18n`；
- `kind`：`single` 或 `multi`；
- `options:[{value,label}]`；
- `value`：single 为字符串，multi 为数组；
- `required`、`visible`；
- 新模板导入的可见 single 字段会带 `reviewRequired:true` 且 `value:""`。管理员必须在 Panel
  明确选择提交值，选择后 Panel 写为 `false`；为 `true` 时拒绝发布。旧目录可省略该字段。

Panel 会确认提交值存在于 `options`，必填项不能保持空值。

### `conditionalFields.perResult` 与 staged `perGrade` alias

条件字段可按不透明 result key 选择提交值：

```json
{
  "conditionalFields": [
    {
      "field": "example_conditions",
      "title": "示例条件",
      "value": [],
      "perResult": {
        "option-one": ["EXAMPLE_VALUE_ONE"],
        "option-two": ["EXAMPLE_VALUE_TWO"]
      },
      "perGrade": {
        "option-one": ["EXAMPLE_VALUE_ONE"],
        "option-two": ["EXAMPLE_VALUE_TWO"]
      }
    }
  ]
}
```

选中的 result key 存在于 `perResult` 时，当前 App 提交对应值；否则使用 `value` 作为 fallback。`perResult` 的 key 必须与同一 profile 的 `gradeMap` key 完全一致。升级期间若目录仍只有旧 `perGrade`，新版 App 会按相同规则读取它；两者同时存在却不一致时，App 会在提交前停止，不能任选一份发送。

`perGrade` 仅用于新旧 App 混用期间的 staged migration alias：旧 App 读取它，迁移脚本将每个可达分支的 JSON 值深拷贝到 `perResult`，并保留原属性。Panel 对 `perGrade` 使用与 `perResult` 相同的 result-key / array 约束；两者同时出现时必须深度完全相等，否则拒绝发布，避免同一 catalog 在新旧 App 产生不同 payload。新 profile 不需要为历史客户端发明 `perGrade`；确认旧 App 全部退出后可删除 alias。只有经旧版 selectable-result replay 证明、且 key 不存在于 raw `gradeMap` 的死分支才可从 candidate 的两个 alias 同步删除，并在私有迁移报告中只记录计数；其他无法映射的旧 key 都是 blocker，不能猜测改名。

`operationFields` 保存固定 / 可编辑操作值。所有 field 与 option value 都必须来自实际模板；Panel 和 AI 的引用检查不能替代发布前的人工核对。

## Material 配置

`materialGroups[].materials[]` 的通用结构为：

```json
{
  "code": "EXAMPLE-ITEM-01",
  "name": "示例项目",
  "nameI18n": {
    "en": "Example item",
    "es": "Elemento de ejemplo"
  },
  "aliases": [],
  "defaultQty": 1
}
```

可选 `materialCodePattern` 是 Java regular expression。App 只接受同时匹配正则且已由当前 profile 明确列出的 code；它不会提交由正则发现但 catalog 未声明的未知值。

提交 item 的字段名由 `backendAdapter.operations.submit.materialItemMapping` 决定。每个 item 必须显式提供正整数 `defaultQty`；App 不从 workflow 猜数量。

`notifySkipMaterials` 可列出当前 `materialGroups` 中的 code，保留旧版“忽略此类缺料”的行为：这些 code 不授权自动移除或重试，也不进入本机提示和缺料汇总。Panel 会拒绝未知或重复引用。若一次响应同时包含其他未忽略的已知 code，App 仍只对未忽略部分执行面板已授权的恢复。

## Workflow：显式启用、缺失即安全关闭

缺少 `workflow` 时所有可选 workflow 在解析层关闭。存在时，Panel 要求旧契约中的 `previousSteps`、`duplicateCheck` 和 `materials` 结构有效；为兼容旧 catalog，新增的 `alternateEntries`、`printing`、`submission`、`notifications` 及上一工序决策字段可以进入编辑器，并以安全值展示。安全值只用于读取和迁移；Panel 不会自动把 `compatibilityReviewed` 设为 `true`。新 App 的提交门要求 profile 完整显式声明所有决策且已人工核对，未迁移 profile 会被明确阻止提交：

```json
{
  "workflow": {
    "compatibilityReviewed": true,
    "previousSteps": {
      "enabled": false,
      "scanPrecheck": false,
      "scanPrecheckExcludedResultKeys": [],
      "triggerResultKeys": [],
      "directCreateResultKeys": [],
      "identifierCorrection": {
        "enabled": false,
        "substitutions": [],
        "resultKeys": [],
        "applyAction": "block"
      },
      "identifierCasePolicy": "preserve",
      "scanPrecheckPolicy": {
        "maxMissingAttempts": 1,
        "beforeLimitAction": "block",
        "atLimitAction": "block"
      },
      "verifyAttempts": 1,
      "verifyDelayMs": 0,
      "recipeMaxAttempts": 1,
      "recipeRetryDelayMs": 0,
      "legacyDraftArtifactKey": "",
      "artifacts": [
        {
          "key": "example-artifact",
          "title": "示例附件",
          "titleI18n": {
            "en": "Example artifact",
            "es": "Archivo de ejemplo"
          },
          "required": true,
          "uploadNameTemplate": "{identifier}-example-evidence.jpg"
        }
      ],
      "templates": [
        {
          "templateId": 100001,
          "warehouseId": 200001,
          "sku": "EXAMPLE-SKU",
          "fixedData": {},
          "serialField": "example_serial",
          "photoBindings": [
            {
              "targetField": "example_photo",
              "source": "example-artifact"
            }
          ],
          "delayAfterMs": 0
        }
      ]
    },
    "photos": {
      "includeOptionalSlots": false
    },
    "alternateEntries": {
      "enabled": false,
      "entries": []
    },
    "duplicateCheck": {
      "enabled": false,
      "agePolicy": {
        "unit": "days",
        "value": 0
      },
      "unknownDateAction": "block",
      "recentAction": "block",
      "eligibleAction": "block"
    },
    "printing": {
      "enabled": false,
      "preflightAction": "block",
      "confirmationPolls": 1,
      "confirmationPollIntervalMs": 250,
      "maxAutoReprints": 0,
      "finalRecheckDelayMs": 0,
      "onUnconfirmed": "stop",
      "manualReprintEnabled": false,
      "manualReprintStatuses": [],
      "manualReprintRequiresConfirmation": true
    },
    "materials": {
      "refreshBeforeSubmit": false,
      "missingRecovery": {
        "enabled": false,
        "localNotice": false
      }
    },
    "submission": {
      "maxAttempts": 1,
      "retryDelayMs": 0,
      "interUnitDelayMs": 0,
      "roundLedgerRetentionDays": 1,
      "maxConsecutiveFailures": 1,
      "networkRetry": {
        "maxAttempts": 0,
        "baseDelayMs": 250,
        "maxDelayMs": 250
      }
    },
    "notifications": {
      "submissionSummary": false
    }
  }
}
```

### `workflow.previousSteps`

| 字段 | 规则 |
| --- | --- |
| `enabled` | 总开关。为 `false` 时不执行该模块。 |
| `scanPrecheck` | 扫描后预检；只在总开关同时开启时生效。 |
| `scanPrecheckExcludedResultKeys` | 不执行扫描预检的 result key 数组；key 无预设集合，但必须引用当前 `gradeMap`。 |
| `triggerResultKeys` | 允许自动执行 templates 的 result key 数组；必须引用当前 `gradeMap`，空数组表示不自动触发。 |
| `directCreateResultKeys` | 不做创建前存在性查询、直接执行 templates 的 result key 数组；必须引用当前 `gradeMap` 且是 `triggerResultKeys` 的子集。空数组保持“先查询，明确缺失后再创建”。 |
| `identifierCorrection` | 标识字符纠正规则；缺失时关闭，且不应用任何替换。 |
| `identifierCasePolicy` | `preserve` 保持扫描值；`match_existing` 才允许按已找到的上一工序标识调整大小写。缺失时为 `preserve`。 |
| `scanPrecheckPolicy` | 扫描预检找不到上一工序时的有限尝试次数及达到上限前/后的动作。 |
| `verifyAttempts` / `verifyDelayMs` | 创建上一工序 recipe 后的复核次数（`1..10`）和每次等待（`0..30000` ms）。缺失时为一次、零等待。 |
| `recipeMaxAttempts` | 单个精确上一工序 recipe 位置的持久累计总提交次数，`1..10`；进程/设备重启、再次点击提交或重新进入批次都不重置。只有业务 non-success 命中独立的 `operations.previousSteps.recipeOutcomePolicy.retryableNotWrittenRules`、明确证明 recipe 未写入时才会消耗下一次；submit `outcomePolicy` 和 legacy `retryableMessagePatterns` 都不能授权。缺少有效 recipe retry policy 时新 App 把有效上限收敛为 `1`；`UNCERTAIN` 不得继续。字段本身缺失时也为 `1`。 |
| `recipeRetryDelayMs` | recipe 可重试响应后的等待，`0..60000` ms；最后一次之后不等待。缺失时为 `0`。 |
| `legacyDraftArtifactKey` | 旧 v1 草稿中未命名 `aStepPhotoPath` 的显式兼容目标。空字符串表示不映射；非空时必须精确引用一个 `artifacts[].key`。App 不会按附件数量、标题或顺序猜测。 |
| `artifacts` | 可选附件定义；每项包含唯一 `key`、`title`、可选 `titleI18n`、boolean `required` 与 Panel-owned `uploadNameTemplate`。 |
| `templates` | 可选 recipe 数组；只有总开关开启、数组非空且当前 result key 命中 `triggerResultKeys` 时才执行。 |

`directCreateResultKeys` 只改变 recipe 创建前的存在性 GET。当前结果命中该数组、且没有可精确匹配当前草稿与 recipe 链的持久 receipt 时，App 跳过创建前 GET，仍按 `triggerResultKeys`、`templates` 及照片 source/binding 执行 recipe；创建完成后仍按 `verifyAttempts` / `verifyDelayMs` 做存在性 GET 复核。精确持久 receipt 的续跑与恢复优先，不会因这个字段被忽略或重建。若还要避免扫码后的快速 GET，同一 result key 也必须列入 `scanPrecheckExcludedResultKeys`。移除 trigger、清空 templates 或关闭总开关都会同时关闭 recipe 与其照片上传，不能用来表达“跳过查询但仍上传照片”。

`identifierCorrection` 的结构为：

```json
{
  "enabled": false,
  "substitutions": [
    { "from": "X", "to": "Y" }
  ],
  "resultKeys": ["option-one"],
  "applyAction": "block"
}
```

`substitutions` 最多 8 项；每个 `from` / `to` 必须恰好一个非空 Unicode 字符，且 `from` 不能重复。`resultKeys` 必须引用当前 `gradeMap`；空数组表示所有结果，非空数组只对列出的结果尝试纠正。`applyAction` 允许 `auto`、`confirm` 或 `block`。只有上一工序总开关与 `enabled:true` 同时成立才允许使用这些候选替换；缺失或 malformed 配置按关闭/阻止处理，App 不内置特定字符对。

候选优先级严格按 Panel 中 `substitutions` 派生顺序。扫描预检可以并发发出有限 GET，但必须依次裁决：只有更高优先候选都明确分类为 missing 后才能采用后续成功；高优先响应未分类或等待超时都会 fail closed，不能由更快返回的低优先候选抢先修改 SN。

扫描预检虽然不发送 recipe POST，但会按 `scanPrecheckPolicy` 纠正标识、移除单元、要求附件或阻止流程，因此也属于本地 side-effect 决策。App 在任何 lookup 或这些本地动作之前要求同一份已捕获 workflow 的 `compatibilityReviewed:true` 与完整 lookup classifier；超时与“全部候选明确 missing”是不同结果，超时只报告检查失败，不累计 missing 次数，也不修改单元。

`scanPrecheckPolicy.maxMissingAttempts` 为 `1..10`。`beforeLimitAction` 允许 `remove` 或 `block`；`atLimitAction` 允许 `require_artifact` 或 `block`。缺失时为一次尝试且两阶段均阻止。使用 `require_artifact` 时必须至少提供一个 required artifact、一个有效 template，并让 template binding 引用该 required artifact。App 会按设备持久化这一强制状态，使附件入口、提交校验与 recipe 执行不再依赖当前结果是否命中 `triggerResultKeys`。

`legacyDraftArtifactKey` 必须由私有迁移根据真实旧流程语义填写，不能因为当前只有一个 artifact 就自动选中。它只控制旧草稿照片如何进入当前 `workflowArtifacts` 以及如何为高 versionCode 回滚保留旧字段；当前提交仍只读取 recipe 明确引用的 artifact。没有被 live replay 证明等价时保持空字符串，让旧路径原样留在草稿但不上传。

`artifacts[].uploadNameTemplate` 必须显式包含 `{identifier}`，可选包含 `{index}`，且不能含路径分隔符、冒号、引号、控制字符或其他花括号占位符。旧流程只有一张附件时可以由私有 Panel 写成类似 `{identifier}-example-evidence.jpg` 的部署规则；公共示例只使用虚构名称。App 只在 recipe 来源确实引用该 artifact 时应用它，不会把某条产线文件名固化在框架内。非 artifact 的普通照片来源继续使用框架通用文件名。

`templates[]` 支持两种互斥形式。原有静态 recipe 保持兼容：`templateId`、`warehouseId`、`sku` 都必须显式给出；两个 ID 必须是正整数，SKU 与 `serialField` 必须非空，`fixedData` 必须是对象，`delayAfterMs` 是非负整数。`photoBindings[].source` 必须引用 `artifacts[].key` 或当前 profile 的照片字段，`targetField` 是目标模板字段。`serialField` 和所有 `targetField` 不得覆盖 `fixedData` 的顶层 key，`targetField` 不得等于 `serialField` 或彼此重复。App 不会回退到主 profile 的仓库或 SKU。

只有旧部署确实需要从 live `templateDetail` 解析上一工序时，才使用受限动态形式：

```json
{
  "templateId": 100002,
  "mode": "template_detail",
  "resolverId": "sample-template-detail-v1",
  "expectedStep": 2,
  "sources": {
    "sample-evidence": "example-artifact"
  },
  "delayAfterMs": 0
}
```

- `mode` 只能是 `template_detail`；一旦出现，允许的 key 只有 `templateId`、`mode`、`resolverId`、`expectedStep`、`sources`、`delayAfterMs`。不得同时携带静态模式的 `warehouseId`、`sku`、`serialField`、`fixedData` 或 `photoBindings`。
- `templateId` 与 `expectedStep` 都必须是非空 string 或有限整数。解析出的 live identity 必须分别与两者相等；不匹配立即停止，不会继续提交。
- `resolverId` 引用私有 `backendAdapter.operations.previousSteps.recipeResolvers` 中的同名 resolver。
- `sources` 最多 32 项，将 resolver 使用的通用 alias 映射到当前 profile 的 `artifacts[].key` 或有效照片 field。alias 必须唯一且符合有限标识符格式；resolver 的每个 `photo` action 都必须找到 alias，未被 `photo` action 使用的 alias 也会被拒绝。明确不上传照片的 recipe（例如旧流程的第二步）必须写成 `sources:{}`，其 resolver 不包含 `photo` action；App 不会为了凑齐照片而隐式复用前一步的上传结果。
- `delayAfterMs` 为 `0..120000` 的整数。公共 seed 的上一工序仍保持关闭，动态模式没有隐式默认 resolver。

动态 recipe 不把真实字段、标题、option 或业务值写入 App。它只引用 Panel 中的 profile source 与 adapter resolver；完整受限 DSL 见[动态上一工序 resolver](./backend-adapter.md#动态上一工序-template_detail-resolver)。

该 profile 模块还要求 adapter 提供 `operations.previousSteps` 与 `endpoints.detectionData`。启用模块时，`missingResponseCodes`、`missingMessagePatterns`、`retryableMessagePatterns` 与 `alreadyExistsMessagePatterns` 必须全部显式为数组。前两项仍由新 App 使用，只把精确业务码或 `response.messageFields` 已配置 scalar 消息中的不区分大小写子串分类为“上一工序不存在”；未命中时阻止纠正、缺失动作与自动创建。后两项只为滚动升级期间的旧 App 保留；新 App 不使用 legacy 数组来重试 recipe，也不据此把 already-exists 当成功。四项都可为空，App 不内置业务码或部署文案。只有启用的 profile 实际包含动态项时，发布门才额外要求 `templateDetail` endpoint/operation、完整 resolver/builder DSL 与所有引用；纯静态部署无需为了未使用能力补配置。

新 App 的 recipe POST 业务结果由 `operations.previousSteps.recipeOutcomePolicy` 单独解释，不能复用最终提交的 `operations.submit.outcomePolicy`。它固定包含 `version:1`、私有脱敏 recipe replay 的 `evidenceSha256`、`retryableNotWrittenRules` 与 `alreadyExistsAcknowledgedRules`。每条 rule 必须显式包含 `codeValues` 和 `messagePatterns`，至少一项非空；同一 rule 的非空 selector 取 AND，不同 rules 取 OR。Code 只匹配 `response.codeField`，message 只匹配 `response.messageFields` 指向的 scalar string/number/boolean；对象、数组、data、请求回显和完整 JSON 不参与。对于通用 success contract 未接受的响应，同时命中 retryable 与 already-exists 两组属于冲突，必须保持 `UNCERTAIN`，不能任选“重试”或“已完成”。

Policy 缺失时，新 App 对每个 recipe 位置最多发送一次，并把所有业务 non-success 保持为 `UNCERTAIN`；非空 legacy `alreadyExistsMessagePatterns` 也不能改变这一结果。Outcome evidence 发布门只针对实际可执行的 recipe：`previousSteps.enabled:true`、`triggerResultKeys` 非空且 `templates` 非空三项必须同时成立。只有这样的路径请求 `recipeMaxAttempts>1` 时才要求 retryable rule；只有这样的路径还保留非空 legacy already-exists 数组时才要求 acknowledged rule。关闭、无 trigger 或无 template 的存量配置不会仅因保存了 recipe 字段而触发该 policy 门。

动态 resolver 上线前必须用受控私有、最小化的真实 `templateDetail` 成功响应与失败/歧义响应做 replay，覆盖 identity 相符与不符、字段 0/1/多命中、option 0/1/多命中、required 字段、空值和多照片。必要机器 identity/field/option 必须保真，凭据、真实 SN/照片及无关记录必须移除。只通过 JSON schema 或单元测试不代表 live 模板等价；replay 未通过时保持 `workflow.compatibilityReviewed:false`，不得发布该 profile。

### `workflow.photos`

`includeOptionalSlots` 控制 `optionalSlots` 是否真正进入 App 运行时。设为 `false` 时只处理 `photoSlots`；设为 `true` 后，可选框会按 Panel 标题显示，达到必填框最少张数后可从具体照片框选择入口拍摄，并随对应 field 提交。旧 App 未提交这些可选框，因此既有部署迁移时必须保持 `false`，除非现场明确要启用这项新行为并完成端到端验证。

### `workflow.alternateEntries`

独立入口由来源 profile 自己声明，但提交 identity、结果和 payload 字段全部来自一个明确的隐藏目标 profile。公共 seed 只包含 `{ "enabled": false, "entries": [] }`；真实入口、目标 ID、字段和值只保存在私有 catalog。结构化示例中的名称、ID、field 与值均为虚构：

```json
{
  "alternateEntries": {
    "enabled": true,
    "entries": [
      {
        "id": "sample-independent-entry",
        "title": "示例独立入口",
        "titleI18n": {
          "en": "Sample independent entry",
          "es": "Entrada independiente de ejemplo"
        },
        "targetProfileId": "sample-hidden-target",
        "identifierRole": "primary",
        "resultKey": "sample-ready",
        "photoTargetFields": ["sample-photo"],
        "joinWith": ",",
        "minPhotos": 1,
        "maxPhotos": 2,
        "uploadNameTemplate": "{identifier}-alternate-entry-{index}.jpg",
        "scanner": {
          "applyExpectedLengthTo": ["ocr", "barcode", "entered"]
        },
        "submissionRetry": {
          "maxAttempts": 3,
          "retryDelayMs": 4000
        },
        "toggles": [
          {
            "key": "sample-option",
            "label": "示例选项",
            "labelI18n": {
              "en": "Sample option",
              "es": "Opción de ejemplo"
            },
            "default": false,
            "retainUntilExit": true,
            "dataOverrides": {
              "sample-toggle-field": "SAMPLE_OPTION"
            }
          }
        ],
        "flags": {
          "duplicateCheck": false,
          "previousSteps": false,
          "printing": false
        },
        "dataOverrides": {
          "sample-fixed-field": "SAMPLE_FIXED"
        },
        "dynamicOverrideFields": ["sample-live-choice"],
        "dynamicOverrideProviders": [
          {
            "id": "sample-live-provider",
            "triggerToggleKey": "sample-option",
            "templateId": 300001,
            "expectedStep": 2,
            "resolverId": "sample-alternate-live-option-v1",
            "outputField": "sample-live-choice"
          }
        ]
      }
    ]
  }
}
```

发布时 Panel 进行跨 profile 的闭合校验：

- 新建或迁移完成的 entry 除 `titleI18n` 外，示例列出的字段（包括 `submissionRetry`）都应显式存在；`scanner.applyAllowedLengthsTo` 是示例未写出的可选 override，只有来源 primary scanner 已配置非空 `allowedLengths` 时才能显式添加。仅为读取旧 catalog 兼容缺失的 `submissionRetry`，此时 App 安全地按不自动重试处理。toggle 中除 `labelI18n` 外都必须显式存在。没有固定覆盖时仍写 `dataOverrides:{}`，不用缺失值表达默认；
- `targetProfileId` 必须在最终 catalog 中只命中一个 profile，不能等于来源 profile，并且目标必须显式设置 `pickerVisible:false`；
- 隐藏目标必须有正整数 `template.id` / `template.warehouseId`、非空 `template.sku` 与 `snFields.primary`；`identifierRole` 当前只能是 `primary`，App 不从入口名称猜标识来源；
- `resultKey` 必须精确引用目标 `gradeMap`；`photoTargetFields` 必须非空、无重复，并且只引用目标 `uploadFields`、`photoSlots` 或 `optionalSlots` 声明的 field；`minPhotos` 必须为 `1..20`；`maxPhotos:0` 明确表示不限张数（用于保持旧独立入口行为），正数则必须 `>= minPhotos`；`joinWith` 必须非空；
- `uploadNameTemplate` 由 Panel 显式保存，只能使用 `{identifier}` 与 `{index}` 两个占位符并且必须同时包含两者；禁止 `/`、`\\`、冒号、引号和控制字符。结构化编辑器新建入口时把该字段留空，管理员必须显式填写（例如完全虚构的 `{identifier}-alternate-entry-{index}.jpg`），否则发布校验会拒绝；App 格式化时把 identifier 中不属于 `A-Z` / `a-z` / `0-9` / `.` / `_` / `-` 的字符替换为 `_`，index 从 `1` 开始；
- `scanner.applyExpectedLengthTo` 必须显式列出 `ocr`、`barcode`、`entered` 中至少一个且不能重复；它只覆盖此独立入口的精确长度适用来源，精确长度值及其他归一化/标签规则仍来自来源 profile 的 effective primary scanner。来源只配置 `allowedLengths` 而没有精确长度时，该兼容 scope 仍必须保留，但没有可应用的精确长度规则；
- `scanner.applyAllowedLengthsTo` 是可选的独立入口 override。缺失时继承来源 effective primary scanner 的 allowed-length scope；来源存在 `allowedLengths` 但缺失自身 `applyAllowedLengthsTo` 时，其默认 scope 是 `ocr`、`barcode`、`entered` 三者。显式配置时只覆盖该独立入口，必须是由这三个精确小写值组成的非空、无重复数组，并且来源 primary scanner 必须有非空 `allowedLengths`；否则 Panel 拒绝发布，App 也 fail closed。离散长度数值从不定义在 entry 上；
- 当同一 source 同时命中 allowed-length 与为旧 App 保留的 exact-length fallback 时，新 App 优先使用 `allowedLengths`；`expectedLength` / `expectedSnLength` 必须是该列表成员，不会把列表压回单一长度。Panel 结构化独立入口控件可显示继承的 allowed scope、写入显式 override 或“恢复继承”；同样的字段可在 Advanced JSON 的 `workflow.alternateEntries.entries[].scanner` 精确编辑；
- `submissionRetry` 只控制“后端已经返回且 Panel 分类器明确证明未写入”的最终 POST：`maxAttempts` 为包含首次请求在内的 `1..10`，`retryDelayMs` 为 `0..60000`。每次重试复用同一份不可变 payload 和本次已经取得的上传 URL，绝不重新上传照片；超时、断线、网关错误、非 JSON 或未分类响应立即进入结果不明锁，不消耗此重试额度。旧 catalog 缺少该对象或结构化编辑器新建入口时都使用安全值 `1/0`，即不自动重试；
- `dataOverrides` 与每个 toggle 的 `dataOverrides` 只能引用目标已声明 field，field ownership 互不重复，不能替换目标主标识或 `template` identity；
- `dynamicOverrideFields` 与 `dynamicOverrideProviders` 都必须显式为数组；不使用实时覆盖时两者都写 `[]`。每个 provider 必须引用同入口的 `triggerToggleKey`、隐藏目标的精确 `templateId`、正整数 `expectedStep`、adapter 中存在的 `resolverId` 和唯一 `outputField`。allow-list 必须与全部 provider 输出字段完全相等；输出不得与固定/toggle override 重叠，也不得替换 serial、result、照片或 template identity；
- provider 开启时，运行时必须在任何照片 upload 或 submit POST 前拉取每个 active provider 的 live template，验证 template/step/warehouse/SKU identity，并要求 field selector 与 option selector 各恰好命中一项。响应缺失、多余、0/多命中、未知 key、空 builder 结果或 identity 不符都必须停止，不得回退到静态值；
- 每个 toggle 必须有唯一 `key`、显式 `label`、可选 `labelI18n`、boolean `default` / `retainUntilExit` 及一个 `dataOverrides` 对象。App 显示 `labelI18n` 或 `label`，不按 key 写死现场文案；
- `flags` 只允许 `duplicateCheck`、`previousSteps`、`printing`，且三项必须全部为 `false`，确保独立入口不意外继承标准提交链的可选副作用；
- alternate object、entry、scanner、`submissionRetry`、toggle 与 flags 都拒绝未知 key；`enabled:false` 时 `entries` 必须为空，`enabled:true` 时至少一个 entry；entries / toggles 分别最多 16 项，照片 field 最多 32 项，覆盖来源必须唯一。

缺少整个 `alternateEntries` 时旧 catalog 仍可在 Panel 打开，但视为关闭且不能按新 schema 发布；结构化编辑器会写入显式安全默认。发布仍要求 `workflow.compatibilityReviewed:true`。Panel 不会自动勾选兼容性核对，也不会把可见 profile 自动改为隐藏目标。上线前应以受控私有、最小化但机器值保真的 replay 逐项核对入口文案、toggle 生命周期、照片数量与拼接、上传文件名、最终 identity/payload，以及三个关闭 flags。Provider schema 与纯规则测试不等于 Android 主流程已经接线；尚未验证“compile → fetch → resolve → payload”全部发生在 upload 前的 build 必须保持 provider 数组为空。

### `workflow.duplicateCheck`

| 字段 | 规则 |
| --- | --- |
| `enabled` | 提交前查询已有记录；缺失或 `false` 时关闭。 |
| `agePolicy` | `{unit,value}`；`unit` 为 `days` 或 `calendar_months`，`value` 为 `0..36500`。用最新一条可解析日期判断是否达到再次提交年龄。 |
| `unknownDateAction` | 有重复记录但时间无法解析时：`skip_as_submitted`、`confirm` 或 `block`。启用模块时必须显式填写。 |
| `recentAction` | 未达到年龄阈值时：`skip_as_submitted`、`confirm` 或 `block`。启用模块时必须显式填写。 |
| `eligibleAction` | 已达到年龄阈值时：`continue`、`confirm` 或 `block`。启用模块时必须显式填写。 |

该模块要求 `backendAdapter.operations.duplicateCheck`、`endpoints.snRepetition`，以及显式的 `dateTransforms`、`epochUnits`、`dateFormats`、`timeZone`。Panel 可以读取四项全部缺失的旧 adapter 以便迁移，但会拒绝发布任何开启重复检查的 profile，直至四项补齐；只填写一部分同样无效。`dateTransforms` 是有序、无重复的 allow-list，只能使用 `iso_t_to_space`、`localized_ymd_to_dashes`、`strip_fractional_suffix`、`strip_trailing_z`、`truncate_after_seconds`，空数组表示不改写文本。六项日期兼容策略可全部省略并保持当前严格行为；任一出现时必须全部合法。可选 `numericEpochPrecision` 缺失等同 `exact`；只有本地签名旧版回放证明 numeric epoch 曾丢弃秒时才由 Panel 设置 `minute_floor`，且该值不影响文本日期。App 不猜转换、epoch 单位、日期格式或时区来源；日期解析与 `calendar_months` 阈值使用同一显式时区策略。记录存在但日期无法解析时按 `unknownDateAction` 执行，不会自动当作可重录。详见[重复日期契约](./backend-adapter.md#duplicatecheck-日期契约)。

### `workflow.printing`

打印只有在 profile 的 `workflow.printing.enabled:true` 与全局 `backendAdapter.printing.enabled:true` 同时成立时才运行。Profile 策略为：

| 字段 | 范围与含义 |
| --- | --- |
| `preflightAction` | 打印服务预检未通过时执行 `block`、`confirm` 或 `continue`。 |
| `confirmationPolls` | 每条提交后的确认轮询次数，`1..12`。 |
| `confirmationPollIntervalMs` | 轮询间隔，`250..30000` ms。 |
| `maxAutoReprints` | 明确失败状态的自动补打上限，`0..3`。 |
| `finalRecheckDelayMs` | 最终复核前等待，`0..120000` ms。 |
| `onUnconfirmed` | 最终仍未确认时 `stop` 或 `continue`。 |
| `batchEndRecheckMode` | `deferred_missing_two_pass` 在逐条确认未找到任务时，于批次结束立即复查一次，只对仍缺失任务等待 `finalRecheckDelayMs` 后再查一次；`inline_only` 在逐条窗口结束后直接汇总未确认，不做这两轮批末查询或晚到自动补打。`onUnconfirmed:stop` 自身的停止前最终检查不受该字段影响。 |
| `unknownStatusPresentation` | `distinct` 将 adapter 未识别状态显示为独立的灰色“未知”；`as_ongoing` 仅使用黄色“进行中”的呈现。两者内部均保持 `unknown`，不会改变 allow-list、状态复查或补打安全判断。 |
| `manualReprintEnabled` | 是否显示并允许手动补打入口。 |
| `manualReprintStatuses` | 允许补打的最新任务状态数组；元素只能是 `failed`、`ongoing` 或 `unknown`，不得重复。启用手动补打时必须非空；`printed` 与 `missing` 永不允许。 |
| `manualReprintRequiresConfirmation` | `true` 时发送前显示人工确认；`false` 时直接进入补打流程。无论取值如何，发送前都会重新查询并要求最新任务 ID 精确相同、当前状态仍在 allow-list。 |

缺失 `printing` 时整体关闭；其他缺失/非法值收敛到阻止、一次轮询、零补打、零等待、不允许手动补打、空状态 allow-list 和需要确认。旧 catalog 缺少两个兼容字段时，App 使用当前安全行为 `deferred_missing_two_pass` 与 `distinct`；字段存在但非法时 catalog promotion 拒绝。Panel 的新发布必须显式包含两个字段。自动补打仍只针对 adapter 明确分类为 `failed` 的任务，不受手动 allow-list 影响。手动与自动补打共用精确 job/SN/session/policy/payload journal；socket 可能已发出后，printing v1 只有 configured success 可直接完成补打 POST，business non-success、transport/解析错误或清理失败都保持 `UNCERTAIN` 并禁止重放。后续精确绑定的远端状态查询只有在响应成功、返回同一 job/SN 且状态被 adapter 分类为 printed 时，才会清理同一条未解决补打记录；缺失、failed、ongoing、unknown、查询失败或绑定不同都不会清理。启用前还必须满足 adapter v1 的精确标识、任务类型、状态集合和 job ID 约束，详见[打印协议](./backend-adapter.md#optional-printing)。

### `workflow.materials`

`refreshBeforeSubmit:false` 只使用 Panel 已发布的 `materialGroups`。设为 `true` 时，App 在打印预检之后、任何上传/recipe/提交之前，每批只按 backend adapter 的模板字段映射刷新一次；瞬时网络失败可使用同一 profile 的 `submission.networkRetry` 有限预算重试这个只读 GET，但不会因此重放任何上传或 POST。开关只允许在 `materialGroups` 及其中每个 `materials` 均非空时发布。

刷新只替换 profile 已声明 group field 的 options，不引入未知 field。已声明组缺失/为空、重复 group/code、adapter 路径缺失或非法数量都会 fail-closed，草稿保持不变。默认 `strict_live_match` 下，已有 code 的 live quantity 缺失时沿用 Panel `defaultQty`，存在时必须与 Panel 一致。只有经过私有 replay 的部署才可在 backend adapter 显式选择 `profile_authoritative`，让已有 code 始终使用 Panel 的正整数 `defaultQty`；未知新 code 在两种模式下都必须有显式正整数 live quantity。完整 wire contract 与上线前 replay 要求见[后端适配器](./backend-adapter.md#提交前刷新列表项)。

`missingRecovery.enabled:true` 只允许在 backend 错误命中 `operations.submit.outcomePolicy.missingMaterialNotWrittenRules`、证明该次确定未写入后进入恢复。随后 App 仅用当前 profile 的非空、有效 `materialCodePattern` 从 `response.messageFields` 声明的消息中提取 code，再与当前 profile 已列出的材料求交；不会扫描 data、请求回显或完整 JSON，也不会在缺 pattern 时退回全文 `contains`。分类命中但提取为空时停止且不重试。`localNotice` 只控制是否显示本机提示。两项缺失均为 `false`。`notifySkipMaterials` 中的 code 保持旧版语义：不参与自动移除/重试，也不进入本机或远端缺料通知。

### `workflow.submission`

| 字段 | 范围与含义 |
| --- | --- |
| `maxAttempts` | 单条业务提交的总尝试次数，`1..10`；默认 `1`。只有 non-success response 命中私有 `outcomePolicy` 的 retryable / missing-material not-written rule、明确证明该次未写入后才会进入下一次。 |
| `retryDelayMs` | 匹配可重试业务文案或执行缺失项恢复后的等待，`0..60000` ms。 |
| `interUnitDelayMs` | 同一批次相邻两条之间的等待，`0..60000` ms；最后一条之后不等待。 |
| `roundLedgerRetentionDays` | 本地批次提交/打印核对记录的保留天数，`1..30`；创建 round 时写入快照，之后修改或删除 profile 不会重解释已有 round。 |
| `maxConsecutiveFailures` | 批次连续失败达到该值后停止，`1..100`。 |
| `structuredNonSuccessAction` | 已收到并解析成功的 backend non-success 响应如何处理。默认 `lock`；仅在部署已确认这类响应必定未写入时可设为 `reject_as_not_written`，此时保留草稿并允许修正后重提。断网、超时、非 JSON 与响应丢失始终锁定。 |
| `networkRetry.maxAttempts` | 瞬时网络错误后的**额外**整单元重试次数，`0..100`；默认 `0`。 |
| `networkRetry.baseDelayMs` | 指数等待起点，`250..60000` ms。 |
| `networkRetry.maxDelayMs` | 等待上限，`250..300000` ms，且不得小于 `baseDelayMs`。 |

底层 HTTP transport 只对只读 GET 做有界兼容重试：DNS、连接、超时、TLS 或 502/503/504 等瞬时故障后等待 3 秒再试一次，合计最多两次；每次尝试都重新校验当前 Panel、session 与远程 worker gate。每次具体的 POST、multipart upload、OCR upload 与打印 POST transport 调用仍各执行一次；外层整单元有限网络重试允许重新上传图片。业务 POST 只有被 Panel-owned adapter/profile classifier 明确拒绝并证明未写入时才可按显式策略有限重试，而且必须复用完全相同的请求字节与目标绑定。主流程/独立入口最终提交、按序的上一工序 recipe POST 和补打 POST 分别在 socket 调用前持久化 exact attempt identity 与 payload 摘要。上一工序还持久化已完成 recipe 前缀，直到本单元终态与最终提交回执落盘。Transport、解析、recipe 分类冲突保持 `UNCERTAIN`；未分类 non-success 默认同样锁定，只有 profile 明确声明 `structuredNonSuccessAction:reject_as_not_written` 时，主流程才按已拒绝保留草稿。进程退出时遗留的 `POSTING` 仍恢复成 `UNCERTAIN`。Configured session-invalid HTTP 状态、业务码或消息也不能证明已经开始的副作用 POST 未写入：App 先把对应 attempt 标记为 `UNCERTAIN`，重新登录不会清理 journal、改写为明确拒绝或自动重放。App 不会猜测这些记录：主流程/独立入口最终提交与上一工序必须经后端侧核对和受控恢复；补打仅可由上一段所述的同一 job/SN 精确 printed 状态自动收敛。

Profile 可用 `previousSteps.recipeMaxAttempts`、`submission.maxAttempts` 和 `submission.networkRetry.maxAttempts` 分别请求有限 recipe、业务提交和整单元网络重试，但三者不能共享 outcome 证明。Recipe 必须由独立的 `operations.previousSteps.recipeOutcomePolicy` 授权，final submit 必须由 `operations.submit.outcomePolicy` 授权；缺失 recipe policy 时 profile 中更大的 `recipeMaxAttempts` 不会生效。图片 multipart upload 是可重新执行的准备动作，可随有限整单元网络重试再次上传；它不会自行创建最终表单。结果不明的 journaled recipe/final POST 仍不会被整单元策略重放。只有在 backend 对 recipe 和 submit 操作提供经验证的明确未写入结果、幂等或去重保证时才能提高对应 POST 重试值。

### `workflow.notifications`

`submissionSummary:true` 表示该 profile 允许发送结构化提交通知。缺少 `notifications`、缺少该字段或值为 `false` 时均不发送。这个 profile 开关不会单独启用通知：v2 还要求 Panel 全局 `notificationAdapter.eventTemplates` 包含 `submission.summary`；v3 还要求有效的双 delivery adapter 与下面的显式 `profileLabel`。v2 App 不把 profile 名称、账号、记录标识、item 明细或自由文本放入计数事件；完整通知协议见[全局配置](./configuration.md#notificationadapter)。

可选 `profileLabel` 是提供给 v3 `submission.round.data.profileLabel` 的显式通知显示名称：必须是非空 string，最长 160 characters。Panel 编辑器留空时删除该字段；App 不得从 `displayName`、`model`、template、SKU 或其他业务字段猜测替代值。缺少 `profileLabel` 时不能据此构造 v3 round 通知，但不会改变 v2 `submission.summary` 的既有行为。`workflow.notifications` 只允许：

```json
{
  "submissionSummary": true,
  "profileLabel": "Example line"
}
```

上例完全虚构。`profileLabel` 属于 profile/catalog 自定义内容，不应把真实名称写进公开 seed 或公开文档。

### Adapter 能力匹配

| 配置 | 行为 | Adapter 要求 |
| --- | --- | --- |
| `previousSteps.enabled` | 查询/创建配置的上一工序记录。 | `operations.previousSteps` 与 `endpoints.detectionData`。 |
| 实际可执行的上一工序 recipe 且 `recipeMaxAttempts>1` | 对明确未写入的同一 recipe 做有限重试。 | 独立的 `operations.previousSteps.recipeOutcomePolicy.retryableNotWrittenRules`；submit policy 与 legacy 数组均不能替代。 |
| 实际可执行的上一工序 recipe 且保留 legacy already-exists 数组 | 让新旧 App 对“同一 recipe 已完成”的迁移语义一致。 | 独立的 `operations.previousSteps.recipeOutcomePolicy.alreadyExistsAcknowledgedRules`。 |
| `duplicateCheck.enabled` | 查询已有记录并按显式日期契约分类。 | `operations.duplicateCheck` 与 `endpoints.snRepetition`。 |
| `printing.enabled` | 对成功提交执行打印预检、关联和确认。 | `backendAdapter.printing.enabled:true` 及三个打印 endpoint。 |

这里“实际可执行”精确表示 `previousSteps.enabled:true`、`triggerResultKeys` 非空且 `templates` 非空。关闭模块、无 trigger 或无 template 时不会要求 recipe outcome evidence；不要为未使用的功能编造 endpoint 或 policy。

## 多语言

原始 label 是 fallback。可选 sibling map 只允许 `en` 与 `es` 字符串：

- `titleI18n`
- `nameI18n`
- `labelI18n`

缺少翻译不会阻止发布，malformed map 会失败。AI 翻译是可选 authoring 功能，启用前应审查数据出境范围。

## 发布与兼容性

- Panel validator 会检查结构、result key、模板绑定、数量边界和引用；发布时还要求 `defaultPhotoOrder` 为受支持值，并要求 App 提交门使用的每个 workflow 策略键都已显式写入。
- AI 整对象修改与模板刷新会保留 `pickerVisible`、扫描策略、workflow 和 material-code policy；这些项应由管理员明确编辑。
- Catalog schema 过新、SHA 不匹配、payload 损坏或 profiles 为空时，App 只保留当前 Panel/key 已完整绑定且同 revision 的有效缓存。仅在完全未配置 Panel 时显示不可联网的虚构 seed 预览；已配置连接但没有有效 cache 时保持锁定。
- 迁移旧 catalog 时，应先在测试环境用当前 Panel 为每个 profile 写入完整运行策略，并检查完整 JSON，再升级设备。旧 App 已有行为必须显式写成等价值；新 App 会阻止未完成该迁移的 profile 提交，避免静默采用关闭型默认。不要依赖 App 对历史业务结构的隐式解释。

发布后至少验证：选择器可见性、result key、条件字段、照片提示、本地化、提交 payload，以及每个显式启用的 workflow。
