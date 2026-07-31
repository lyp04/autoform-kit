# Catalog 与 profile 管理

Panel 是 catalog 的主体和唯一推荐管理入口；App 只是读取并执行已发布 profile 的通用框架。管理员从部署 backend 导入模板、编辑和预览 profile、配置运行规则并发布；Worker 默认写入单独的私有 GitHub repository，也可在完成受审的精确 seed 后改由 private R2 snapshot/current-pointer authority 承载。

不要把 live Panel catalog 复制到公开源码，也不要长期绕过 Panel 手改 JSON。真实 profile、adapter、通知、更新源和其他生产值都属于私有 Panel；公开仓库中的 seed、adapter 与配置只能是不可运行示例。Panel 负责 validation、ordering、version、SHA-256 和 manifest 的一致性，并把所有变化文件作为一个 authority revision 发布：默认是同一 Git commit；R2 cutover 后是一个 immutable content-addressed snapshot 加 ETag-CAS current pointer。

## Private catalog 文件

当前 authoritative snapshot 包含两份必选文件和一项可选状态：

| 文件 | 读取方 | 内容 |
| --- | --- | --- |
| `form-profiles.json` | App、Panel、Worker | `schemaVersion`、递增 `version`、App-facing `settings`、有序 `profiles`。 |
| `manifest.json` | App、Panel、Worker | Catalog version、payload SHA-256、`profilesUrl`、最低 App version、更新时间与说明。 |
| 可选 `panel-settings.json` | Worker only | 存在 Worker-only setting 时保存 `notificationAdapter` 等绝不能下发给 App 的内容；未配置时合法地不存在。 |

`panel-settings.json` 存在时也不通过 `/catalog/*` 提供。Worker 读取配置时会在服务端合并，但 `/api/config` 只输出安全的 App contract；通知 provider URL 不在其中。响应中的非敏感正整数 `catalogVersion` 来自同一 authoritative snapshot，必须等于该 snapshot 的 `form-profiles.json.version`，供新 App 拒绝跨 revision 混配。

App 先读取 manifest，再下载 `form-profiles.json` 并验证 SHA-256。只有严格更高 revision 且单独校验有效的 candidate 才能在等待配对时保留旧 active pair 的可用性；schema 过新、App version 不足、hash 不匹配、JSON 损坏、profile 为空或同 revision 内容变化都不会提升，并会按候选异常锁定新工作。

APK 内置 seed 只是 fictional fallback，不是 private catalog 的初始数据。正式使用前必须确认 App 已加载目标 catalog。

## 进入 Panel

1. 输入与 `CATALOG_READ_KEY` 相同的 Panel access key；浏览器只在当前 tab 的 `sessionStorage` 保存它。
2. 使用获准的 backend account 完成 challenge 和 login。
3. 浏览器直连 backend；Worker 在受保护写操作前调用 user-info 重新验证 token。

Panel 没有独立 account database。Backend 必须确保只有获准 account 能通过这条校验，或在 edge/network 层增加更严格的访问控制。

## 从模板创建 profile

1. 在 Panel 搜索 backend template。
2. `backendAdapter.fields` 与 `conversion` 将响应转换为 canonical template。
3. Converter 生成 profile draft；需要用户访问时确认 `pickerVisible:true`。
4. 用实时预览和结构化 editor 修改。新导入的可见 `singleChoice` 字段不会自动选择第一项：它以空值和 `reviewRequired:true` 进入草稿，管理员必须在 Panel 明确选择，发布前标记才会清除。
5. 必要时在 advanced JSON 配置完整 profile workflow。
6. 人工核对 template、field、option、result key 和提交值。
7. 发布到 private catalog。
8. 使用 dedicated non-production data 和 device 完成端到端验证。

Panel 不从名称、serial prefix、SKU 或相邻数字猜测 identity。必须由 backend 返回或管理员明确提供精确值。

## 编辑、排序与发布

- **Edit**：从已发布 profile 建立 working draft。
- **Advanced JSON**：用于 structured UI 尚未覆盖的字段；应用后仍由 Panel validator 检查。
- **Photo order**：结构化“默认拍照顺序”控件只提供按照片框分组和按条目逐个完成两种选择，直接写入 App 使用的 `defaultPhotoOrder`，并同步刷新预览与 advanced JSON；缺失/未知值不会被静默改成默认值。
- **Choice review**：新模板的可见 `singleChoice` 必须由管理员明确选择；`reviewRequired:true` 或必填空值均阻止发布。
- **Order**：调整完整 profiles 数组顺序；App 按该顺序显示所有 `pickerVisible:true` 项。
- **Publish**：单 profile 默认按 `id` upsert；同一 `id` 的 profile 使用完整对象替换语义，明确 replace 才替换完整集合。
- **Version**：每次 publish 递增 catalog version，并重建 payload hash 与 manifest。
- **Minimum App version**：在 Panel 全局设置维护；其他发布会保留现有值。
- **Atomic publish**：profiles、manifest 与 Worker-only settings 组成一个 authority revision。GitHub 模式以非强制 ref update 发布同一 Git tree；R2 模式先写 immutable snapshot，再以 ETag CAS 移动 current pointer。每个写请求还必须携带编辑器加载时的 catalog version；version、branch tip 或 R2 pointer 已变化时返回 `409`，要求重新加载，不会用旧草稿覆盖新发布。
- **Whole-catalog validation**：profile 增量更新、排序、完整替换和 settings-only 保存都会校验合并后的完整 profiles 集合；任一保留 profile 不再满足 App 执行契约时整次发布返回 `422`，不会生成一个同步后才被 App 拒绝的 revision。

绑定但尚未 seed 的 R2 不是双写模式：读取回退 GitHub，publish 必须失败。有效 R2 pointer 一旦
建立，损坏或缺失的 authoritative pointer/snapshot 也必须直接失败，不能静默退回可能已经过期的
GitHub 内容。R2 seed/cutover 没有面向操作员的 Panel route 或支持的 CLI；部署方必须使用受审的
private one-shot tooling，并把精确 source version、snapshot digest 与 current pointer 留在私有
迁移证据中。

Panel 编辑器从已发布的完整 profile 建立草稿，因此会携带不认识的扩展字段。第三方客户端若调用写接口，也必须传 `baseVersion` 和完整 profile 对象；局部对象不是 patch，缺少的字段会按完整替换语义删除。

修改 `id` 等同于创建新 identity。删除、重命名或合并前，应评估 device cache、未完成 draft 与外部引用。

## Profile 负责的运行配置

- display name、search text、visibility、UI color 与 localization；
- backend template、serial field、choice、operation 和 conditional field；
- arbitrary result key 及其 label、color 与 submit value；
- photo slot、数量、顺序、localized title 与 transition prompt；
- scanner hint 与 item code rule；
- 上一工序的扫描预检、标识纠正/大小写、缺失处理、附件、recipe 尝试/等待与复核策略；
- 重复记录的年龄阈值及近期/可重录动作；
- 打印预检、确认轮询、自动/手动补打、手动允许状态/再次确认、最终复核与未确认动作；
- 缺失项恢复/本机提示、提交尝试、连续失败和有限网络重试；
- profile 级提交汇总通知开关。

这些内容都属于 private profile，不是 App compile-time constant。详见 [Profile schema](./profile-schema.md)。

照片切换文案本身是 App 的通用本地化模板，两个名称和切换顺序来自当前 profile：完成上一个框后显示“`{上一个框标题}已拍完，开始拍{下一个框标题}。`”。因此不要在 App source 中写部署方向名；修改 Panel 的 slot 标题/顺序并发布 catalog 即可改变提示。

## AI authoring（可选）

配置 `AI_API_KEY` 后，Panel 可整理 draft、精简 label 或补充 `en` / `es` 翻译。请求可能包含完整 draft、template field metadata、option、管理员 instruction 和 label；未完成数据出境审核时不要启用。

实现会验证 field / option reference，并保留 `pickerVisible`、scanner、workflow 和 item-code policy 等 runtime setting，但管理员仍须检查完整 JSON 与 preview。AI failure 不阻止基础发布，因此 publish success 不代表翻译一定完成。

## App 同步语义

- App 启动、前台节流检查或保存 Panel connection 时请求新 catalog。
- 有效 config/catalog 先分别写入 app-private candidate；只有在 Settings 安全边界（无草稿、队列、拍照任务、远程副作用记录或 active worker）才成对提升为 active cache。重启只恢复或加载已经完整提交的同 revision pair，不会绕过未解决状态，也不会改写正在处理的队列。
- 严格更高 revision 且单独校验有效的 candidate half 可以等待另一半或安全边界，同时继续使用精确旧 active pair；不会把新 adapter 与旧 profile 混用。同 revision 内容变化、损坏、错绑或事务恢复不确定的 candidate 会锁定新工作，不能用旧 cache 掩盖异常。
- 下载失败时，当前 Panel/key 仍有完整、绑定且同 revision 的旧 active pair 才继续使用；仅在完全未配置 Panel 时显示不可联网的 fictional seed 预览。已配置 Panel 但 active pair 缺失或无效时保持锁定，不回退为可操作表单。
- 手动保存的队列备份用于长期/隔日恢复，会 pin 住创建它时的 active pair。新 candidate 可下载但不会提升；提交当前队列、退出或重启不会删除备份。只有用户在 Settings 明确确认“删除队列备份”，且所有持久镜像删除成功后，下一安全边界才可解除 pin；删除失败保留或恢复原备份。
- 高于 App 支持范围的 `schemaVersion` 被忽略。

发布后重启至少一台测试 App，核对 catalog version、profile order、picker visibility、label、photo prompt 和 enabled operations，再扩大部署范围。

## 现有部署迁移

采用 Panel → private config → compatibility test → App 的顺序：

1. 备份现有 private catalog，再部署新 Panel，但不改 live catalog 行为；确认它能读取现有 private catalog。兼容读取不表示已有配置已无损转换。
2. 在 private catalog 中补齐 adapter 和每个 profile 的全部显式策略。旧 App 原本已经执行的上一工序、重复、打印、恢复或重试行为必须写成等价值；只有真正新增的功能保持关闭。发布前检查完整 JSON diff。
3. 发布行为等价、revision 递增的目标 catalog 后，彻底退出并重新启动旧 App，验证 `/api/config` proof 中的 Panel origin、read-key SHA-256、精确原始 catalog SHA-256 和 revision 与旁边 catalog 全部一致；仅回到前台、等待或只看到 catalog 更新不构成预热。再用新 App 对等价策略逐项做候选验证。proof 缺失/不符时新版必须在线重拉完整 pair，不能猜旧 cache 或遗留草稿归属。
4. 使用原签名发布新 App；首次从没有 v2 binding 的旧版覆盖安装后预留一次重新登录，旧 credential 不会被读取或发送，草稿、队列、ledger 和待处理照片不会因此删除；设备确认加载已迁移 catalog 后，才考虑逐项启用真正新增的 workflow。

不要把 live catalog 搬进 public source 作为迁移中转或备份。每个阶段都保留可回滚的 private catalog version。现场设备的完整顺序、旧 App 重启预热要求与 signed-device 验证见 [Staged upgrade](./worker-setup.md#10-staged-upgrade-for-an-existing-deployment) 和 [Releasing](./releasing.md)。

主提交、上一工序或 multipart upload 的不确定远端结果不会因 catalog 更新而解除。当前仓库没有提供能在后端核对后安全清除这些记录的受控恢复工具；在部署方实现、审阅并验证工具之前，应把对应状态视为持续锁定，而不是声称已有恢复能力。

## 发布前数据检查

- `GITHUB_REPO` 指向目标 private catalog，而不是 public source repository。
- 两份必选 catalog 文件只包含预期 deployment data；可选 `panel-settings.json` 的 present/absent 状态正确，且存在时未通过 App routes 暴露。
- Backend adapter 不含 secret；notification adapter 不含 credential 或 custom secret header。
- Example、screenshot、log 和 test record 使用完全 fictional data。
- Catalog 有可回滚 version，且变更 diff 已人工检查。
