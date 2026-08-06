# Android 构建与发布

仓库提供两种构建方式：Gradle 直接生成 debug / unsigned release，以及 `tools/release.sh` 生成对齐、签名、验证过的不可覆盖候选。GitHub 发布是独立的第二阶段：`tools/publish-release.sh` 不重新构建或改写候选，只在公开与私有证据均通过后发布候选中的精确字节。

签名证书、密码、正式 APK 和真实 release note 都不得进入公开源码历史。

## 要求

- JDK 17+；
- Android SDK 35；
- Android build-tools 中的 `zipalign`、`apksigner`、`aapt`；
- `git`、`jq`、Node.js；
- 第二阶段需要已登录的 GitHub CLI `gh`，以及位于仓库外或 Git-ignored 位置的私有 release gate；
- 两个阶段都需要通过 `--private-wordlist` 显式传入位于仓库外的私有敏感词表；
- 发布仓库必须为 App 可匿名读取的 public GitHub repository。

Panel 使用 Node.js 22+，发布前也应运行 Panel 测试。

## 发布前测试

```sh
cd panel
npm ci
npm test
cd ..
./gradlew testDebugUnitTest lintDebug assembleDebug --no-daemon
```

CI 只处理公开源码：先自测通用公开面扫描器并扫描 publishable worktree，再运行 Panel 测试、Android unit test、Lint 和 debug 构建。它不读取仓库外私有词表、Cloudflare secret、private catalog 或签名配置，也不上传 APK artifact；因此 CI 的通用扫描不能替代发布现场的私有词表、历史、Release asset 与签名候选审计。

另外应在非生产环境完成 backend adapter 与每个启用 profile workflow 的端到端测试；unit test 不能证明部署协议正确。

若任一 profile 启用动态上一工序 `mode:"template_detail"`，发布测试还必须使用私下保存、最小化的真实 live `templateDetail` replay corpus。成功样本要逐字段比较旧版与 candidate 生成的 template identity、serial、照片 URL 和固定 option payload；失败/歧义样本要证明 candidate 在任何 upload 或 submit 前停止。为了证明等价，template/SKU/field/option 等必要机器值必须原样保留；只剥离凭据、cookie/header、真实 SN、照片、不相关记录与不参与解析的文案。仅通过 schema 校验、虚构 fixture 或编译不满足这一门槛。Replay 输入与结果摘要均不得放入公开源码、tag、Release notes 或附件。

若任一 profile 启用 `workflow.alternateEntries`，还必须用上述受控私有样本分别回放每个入口：确认来源入口标题/toggle 文案、唯一 `pickerVisible:false` 目标、主标识、result key、照片数量与 URL 拼接、固定/toggle/动态 override 以及最终 template identity/payload 与旧版一致；同时验证 `submissionRetry` 只在明确未写入时复用同一 payload/已上传 URL，任何结果不明都停止且不会重复上传。并证明目标缺失/重复/可见、字段悬空、override ownership 冲突或任一 flags 非 false 时会在上传/提交前停止。Panel 的虚构 fixture 只能证明通用 schema，不能替代 live 等价验证。

若独立入口声明 `dynamicOverrideProviders`，还须验证 Android 主流程确实按“最终 toggle state 编译全部 provider → 使用 adapter `idParam` 拉取每个 live template → 按 provider ID 精确 resolve → 合入 allow-list payload → 才开始任何 upload/POST”的顺序执行。缺失/多余响应、任一 identity 不符、field/option 0 或多命中、未知 key、空 builder 结果、受保护 field 或 owner 冲突都必须在副作用前停止；不能只凭 Panel schema 或纯 Java provider 测试发布。

混合新旧 App 的 staged catalog 若仍有 `conditionalFields.perGrade`，candidate 必须同时包含深度相等的 `perResult`。只有旧 selectable-result replay 已证明不可达、且 key 不存在于 raw `gradeMap` 的 stale branch 才能从 candidate 两份 map 同步删除；source 备份保持不变，公开报告只写计数，不写 key 或值。

## Debug 与 unsigned release

可并存安装的 debug APK：

```sh
./gradlew :app:assembleDebug
```

unsigned release：

```sh
./gradlew :app:assembleRelease
```

`assembleRelease` 不包含 signing config。不能把 unsigned APK 当作可升级的正式发布。

## 准备本地签名配置

复制示例到已被 `.gitignore` 排除的文件：

```sh
cp config/signing.example.json config/signing.local.json
```

推荐只在 JSON 中写环境变量名称，不写密码值：

```json
{
  "keystore": "/secure/example/autoform-release.keystore",
  "keyAlias": "example-release",
  "storePasswordEnv": "EXAMPLE_RELEASE_STORE_PASSWORD",
  "keyPasswordEnv": "EXAMPLE_RELEASE_KEY_PASSWORD"
}
```

上面的路径、alias 与变量名全部为虚构示意。实际 keystore 应位于仓库外的受控 secret storage；CI 中使用平台 secret 注入。

允许的 key：

- `keystore`
- `keyAlias`
- `storePassword` 或 `storePasswordEnv`，只能选一个
- `keyPassword` 或 `keyPasswordEnv`，只能选一个

脚本通过环境变量把密码交给 `apksigner`，不会把密码放进命令参数或输出。literal password 仍会存在本地 JSON，因此不推荐。

## 创建签名候选

```sh
tools/release.sh \
  --version 1.2.3 \
  --version-code 123 \
  --previous-apk /secure/example/previous-release.apk \
  --private-wordlist /secure/example/private-publication-wordlist.json \
  --notes "Example release notes"
```

所有版本、路径和说明均为虚构示意。标准升级候选必须提供 `--previous-apk`；唯一例外是下节所述、严格限定为 v1.0.0–v1.0.6 的 inventory-bound 历史净化重建。至少一个 `--private-wordlist` 始终是强制项；后者可重复，文件可使用逐行 literal、JSON 字符串数组，或扫描器支持的严格 `{ "term", "matchMode" }` 条目数组。结构化条目用于明确选择 `literal`、`identifier` 或 `deployment-token` 匹配；不允许额外字段。词表必须是仓库外的普通非符号链接文件；路径、条目和词表文件哈希不会写入扫描报告、candidate manifest、attestation 或日志。脚本会：

1. 验证版本、signing config，要求从 `main` 构建，记录 source commit，并要求构建前后 working tree 均干净且 HEAD / branch 未变化；
2. 选择 JDK 17+；
3. 运行 Android unit test 与 release Lint，再用传入的 version name / code 构建 unsigned release；
4. 在任何签名动作前，用仓库内固定来源 verifier 验证 unsigned APK 的公开 AAR、嵌套来源 entry、合并/编译产物和 DEX 完整字符串来源链；
5. 对齐并签名 APK，再对最终 signed APK 重跑同一来源验证；
6. 验证 alignment 与签名；
7. 标准升级模式比较上一版 APK 的 package 与 signer，并要求新 `versionCode` 更高；历史模式把每个原始 APK 的 package/version/code/signer 与重写前 inventory 精确绑定，并要求 v1.0.0 从 code 1 开始、后续 code 严格递增；
8. 用 `aapt` 验证新 APK，并按模式验证上一版 APK，或所选原始历史 APK及紧邻的上一份重建候选；
9. 使用仓库内固定扫描器分别现场审计 source commit 原始对象（含作者、提交者和 message）、该 commit 的 exact Git tree、当前 tracked/untracked non-ignored worktree 和已签名 APK；findings、缺失报告或操作错误都会失败；
10. 生成 `update.json`、规范化的 release notes 和 candidate manifest；标准升级候选使用 schema 2 并绑定上一版 APK identity，全部历史候选使用 schema 4、显式 `publicationMode`、最小化的原始 Release/inventory 加密绑定与无歧义的连续重建 lineage；两者都绑定 source/clean state、APK/update/notes hash、package/version/signer，以及扫描器/policy/input/report SHA 和最终来源 verifier/report/counts；
11. 再用同一扫描器和私有词表逐字节审计 `update.json`、`release-notes.txt` 与 `candidate-manifest.json`，任一 finding 或扫描期间字节变化都会失败。标准 schema-2 的三者都会公开；历史 schema-4 manifest 仅作为本地私有证明，但仍按潜在公开面扫描，历史 Release 只公开 APK、`update.json`、title 与固定 body。

输出：

```text
dist/release-candidates/v1.2.3/autoform-kit-1.2.3.apk
dist/release-candidates/v1.2.3/update.json
dist/release-candidates/v1.2.3/release-notes.txt
dist/release-candidates/v1.2.3/candidate-manifest.json
```

`dist/` 被 Git 忽略。同版本候选目录已经存在时脚本会停止，不会覆盖；审查开始后不要修改其中任一文件，任何字节变化都会在第二阶段被拒绝。七个完整扫描报告（commit object、tree、worktree、APK 和三个 release-metadata safety surface）及来源 verifier 的完整报告只存在于权限受限的临时目录，候选落盘前即删除。Candidate manifest 保存 tree、worktree、APK、`update.json` 与 release notes 的公开安全 scanner/policy/input/report SHA、Git tree OID、来源 verifier binding 与无值计数；commit object 由 `source.commit` 选定并在发布阶段重新捕获扫描，candidate manifest 自身的 fresh exact-file report 则因不能自引用而在第二阶段单独交给私有 gate。任何报告都不保存 findings、受审常量或私有词表标识。标准 schema-2 manifest 会成为 Release 资产；历史 schema-4 manifest 始终保持 mode-`0600` 的本地私有证明，不会进入 Git 或 Release。发布阶段还会用同一 scanner/policy 重新现场扫描全部相应输入并重跑来源 verifier。`tools/release.sh --publish` 已停用并会明确报错。

### 历史 v1.0.0–v1.0.6 净化重建

此模式只用于公开历史重写时按顺序重新生成七个安全历史资产，不是创建新项目首版或正常升级的通用开关。先在仓库外保存重写前的 mode-`0600` inventory，以及每个 tag 目录中精确的原始 APK 与 `update.json`。传给候选生成器的 inventory、原始 APK 和 title 文件必须都是仓库外、当前用户拥有、权限精确为 `0600`、单链接的普通非符号链接文件；脚本会在关键边界重复核对。再生成只读历史合同：

```sh
AAPT=/path/to/aapt APKSIGNER=/path/to/apksigner \
node dist/private-migration/build-historical-release-inventory.mjs \
  --before-inventory /secure/history/before.private.json \
  --asset-dir /secure/history/original-assets \
  --out /secure/history/rebuild-inventory.private.json
```

该 builder 要求 v1.0.0–v1.0.6 各有且仅有一个 APK 和一个 `update.json`，逐项核对文件名、大小、SHA-256、package/version/code/signer 以及 update→APK 关系，并以不可覆盖方式创建 mode-`0600` 输出。此 inventory 绑定重写前文件 SHA、refs/Release metadata identity、七个 title 的 SHA/字符数、draft/prerelease、资产名称/数量/大小/摘要及全链 identity；它与原始资产都不得进入 Git 或 Release。

为每个 tag 准备一个 mode-`0600`、无换行的精确 Release title 文件。v1.0.0 示例：

```sh
tools/release.sh \
  --version 1.0.0 \
  --version-code 1 \
  --historical-inventory /secure/history/rebuild-inventory.private.json \
  --historical-original-apk /secure/history/original-assets/v1.0.0/autoform-kit-1.0.0.apk \
  --historical-title-file /secure/history/titles/v1.0.0.txt \
  --private-wordlist /secure/example/private-publication-wordlist.json \
  --offline
```

v1.0.1–v1.0.6 使用相同参数，并额外把 `--historical-previous-candidate` 指向 `dist/release-candidates/<紧邻前一 tag>/candidate-manifest.json`。脚本拒绝跳版、混用 inventory、复用任何旧资产字节或旧的未绑定 `--historical-initial-*` 参数。原始 APK 只用于私下验证 identity；不会复制进候选，也绝不能重新上传。Schema-4 manifest 只保存 exact inventory file SHA-256、inventory identity、所选 Release identity、公开 publication 计划和重建链；不会复制 private inventory 的 `sourceInventory`、完整原始 Release 条目或原始 APK metadata。Validator 与发布器必须每次从仓库外 mode-`0600` inventory 现场派生并核对这些值。所有七个历史候选均使用 schema 4 与固定 `publicationMode:"historical-rewrite-non-latest"`，Release body 只能是源码绑定的通用固定文本；旧 Release body 已知不安全，绝不继承。

标准 `tools/publish-release.sh` 在 gate/GitHub 操作前拒绝所有 schema 3/4、任何 `publicationMode`，以及伪装成 schema 2 的 v1.0.0–v1.0.6。历史候选只能按 tag 顺序交给独立发布器。

每一版发布前都要从当前 fresh branches、tags、Releases、repository、完整 raw refs 及已下载资产重新运行私有 full-history audit，并生成该目标 tag 专用、不可覆盖的 mode-`0600` publication attestation。历史审计和历史发布器必须使用同一组仓库外 JSON wordlist；每个 wordlist 都必须由当前用户拥有、权限精确为 `0600`、单链接且为普通非符号链接文件。逐行文本 wordlist 虽可用于一般候选扫描，但不能作为此 publication attestation 的输入。下面只列出历史模式新增的环境变量；其余输入仍使用私有 migration workspace 中同一审计器记录的完整路径：

```sh
AUTOFORM_PRIVATE_PUBLIC_HISTORY_AUDIT_MODE=historical-incremental \
AUTOFORM_PRIVATE_PUBLIC_HISTORY_EXCLUDED_RELEASE_TAG=v1.0.0 \
AUTOFORM_PRIVATE_PUBLIC_HISTORY_PUBLICATION_ATTESTATION=/secure/history/v1.0.0.publication-attestation.json \
AUTOFORM_PRIVATE_PUBLIC_HISTORY_WORDLISTS_JSON='["/secure/example/private-publication-wordlist.json"]' \
node dist/private-migration/audit-public-history-and-releases.mjs
```

`EXCLUDED_RELEASE_TAG` 必须等于本次尚未创建的目标 tag。它只让发布器在创建 draft 后仍能用同一份 pre-existing Release envelope 复核增量；已发布前缀的数量、顺序、manifest、APK 与 `update.json` 仍由发布器递归严格校验。此 wrapper 的 nested audit 必须显式为 `historical-incremental`，不能用标准发布所需的 `final` migration audit 代替，反向也不允许。确认 attestation 已由审计器以 `0600`、当前用户、单链接方式创建后，计算其 exact file SHA-256，再调用发布器：

```sh
node tools/publish-historical-release.mjs \
  --candidate dist/release-candidates/v1.0.0/candidate-manifest.json \
  --inventory /secure/history/rebuild-inventory.private.json \
  --inventory-file-sha256 <exact-file-sha256> \
  --private-history-audit /secure/history/v1.0.0.publication-attestation.json \
  --private-history-audit-file-sha256 <exact-attestation-file-sha256> \
  --private-wordlist /secure/example/private-publication-wordlist.json
```

历史发布器要求 sanitized tag 已存在且精确指向 candidate source commit；它绝不创建、移动或删除 tag。两次完整 preflight 在任何远端副作用前绑定并扫描 commit/tree/worktree、候选四文件、Git refs、GitHub branches/tags（含 protection status contexts/checks）、repository 与 Releases；每次还在隔离 bare repository 中精确 fetch heads、tags 和 pull refs，从这些 roots 逐对象读取并扫描内容，再把 object count、content hash 和 canonical reachable closure 与 incremental attestation 精确比对。第一轮还下载、核对并扫描全部已发布历史前缀的 APK 与 `update.json`。它只上传本次重建 APK 和 `update.json`，绝不上传 schema-4 `candidate-manifest.json`；先创建 `--draft --latest=false`，下载两项资产重新核对 identity/SHA 并扫描，原 Release 非 draft 时才以 `--draft=false --latest=false` 发布，再次下载和扫描。title、draft/prerelease、资产数量/名称从外部 inventory 现场校验；body 始终使用固定通用文本；历史 Release 永不成为 latest。开始时 `/releases/latest` 只能不存在或精确为 v1.0.7，结束时必须保持同一状态，否则明确失败。

Candidate manifest 的 `publicAudit` 绑定结构如下；示例值均为占位符：

```json
{
  "schemaVersion": 2,
  "publicAudit": {
    "scannerSha256": "<64-hex>",
    "policySha256": "<64-hex>",
    "sourceTree": {
      "gitTreeOid": "<exact-tree-oid>",
      "inputSha256": "<64-hex>",
      "reportSha256": "<64-hex>"
    },
    "worktree": {
      "inputSha256": "<64-hex>",
      "reportSha256": "<64-hex>"
    },
    "apk": {
      "inputSha256": "<same-apk-sha256>",
      "reportSha256": "<64-hex>",
      "zipEntryManifestSha256": "<64-hex>"
    },
    "thirdPartyProvenance": {
      "manifestFile": "tools/apk-third-party-components.json",
      "manifestSha256": "<64-hex>",
      "runtimeLockFile": "tools/android-runtime-dependencies.lock.json",
      "runtimeLockSha256": "<64-hex>",
      "profileId": "<reviewed-exact-profile>",
      "matchedEntryCount": 1,
      "applicationDexStrict": true,
      "sourceVerifierFile": "tools/verify-apk-third-party-sources.mjs",
      "sourceVerifierSha256": "<64-hex>",
      "sourceReportSha256": "<64-hex>",
      "sourceArtifactCount": 6,
      "sourceEntryCount": 6,
      "mergedSourceCount": 1,
      "compiledOutputCount": 2,
      "dexSourceArtifactCount": 2,
      "dexSourceEntryCount": 2,
      "declaredDexStringCount": 5,
      "sourceMatchedDexStringCount": 5,
      "apkMatchedDexStringCount": 5
    },
    "releaseMetadata": {
      "update": {
        "inputSha256": "<same-update-sha256>",
        "reportSha256": "<64-hex>"
      },
      "notes": {
        "inputSha256": "<same-notes-sha256>",
        "reportSha256": "<64-hex>"
      }
    }
  }
}
```

`update.json` 与 `release-notes.txt` 的 exact-file report hash 可直接写入 candidate manifest。`candidate-manifest.json` 不能在自身字节中保存自己的 report hash，否则会形成不可收敛的循环；因此第一阶段只在临时目录生成它的 fresh exact-file report，第二阶段再以 candidate manifest 的实际 SHA-256 作为输入绑定并把 fresh input/report SHA 交给私有 gate。Gate attestation 必须同时确认这两个值，发布命令执行前还会再次扫描。

### APK 第三方来源策略

APK 扫描不按扩展名、目录或“二进制文件”整体放行。`tools/apk-third-party-components.json` 只允许一个已审阅 profile 中列出的精确 ZIP entry path 与解压后 SHA-256，并绑定 Gradle 构建文件及 `tools/android-runtime-dependencies.lock.json`。同路径不同摘要、额外的受保护 model/native/resource entry、profile 缺失或 profile 多重匹配都会停止发布。

DEX 例外同样是逐 entry 或逐 logical-string 精确绑定。扫描器先严格解析 DEX header、type table 与 MUTF-8 string table；只要待整 entry 放行的 DEX 含应用 namespace descriptor，该 entry 立即退出第三方信任。混合第一方 DEX 不会整包放行：只有 profile 明确选中的 component、其公开 AAR SHA-256、AAR 内 class-entry SHA-256 与完整 logical-string SHA-256 四者同时绑定的常量才会遮蔽，应用 descriptor 和其余字符串继续扫描，非字符串区域仍用外部私有词表扫描。子串摘要、未选 component、未命中摘要和单字节变化都不获得信任。当前 debug profile 只用于固定 Gradle debug fixture 的回归测试，**不能**授权 release APK；正式候选使用独立的 exact release profile。任何源文件、依赖、AGP 或 entry 摘要变化都要求重新审查，不能扩大成通配 allowlist。

这条 DEX 信任链不是只靠 policy 中手写的摘要。`tools/verify-apk-third-party-sources.mjs` 按公开 Maven coordinate 和 `sourceArtifactSha256` 在本机 Gradle cache 中定位唯一 AAR，验证嵌套 `source.path` 对应的 class entry，再严格解析 Java class constant pool 的 MUTF-8 字符串；policy 声明的每个完整字符串 SHA-256 都必须在该 class 中出现。随后 verifier 严格解析候选 APK 的全部 DEX string table，要求同一组声明摘要全部且各恰好一次命中，并拒绝把应用 namespace descriptor 当成第三方来源。缺失或重复命中，以及 AAR、class、字符串、APK DEX 或 policy 任一层被改动都会失败。报告和 candidate manifest 只保留 verifier/policy/APK/report SHA 与无值计数，不复制受审常量。

Release 中的 `assets/dexopt/baseline.prof` 与 `.profm` 也不是按目录放行。policy 记录生成它们的 6 个公开 AndroidX AAR 的完整 SHA-256、各 AAR 内 `baseline-prof.txt` 的 SHA-256、AGP 合并后文本 SHA-256，以及两个编译后 APK entry SHA-256；`buildBindings.androidGradlePlugin` 固定编译器版本。同一个 verifier 会先对本机构建实际使用的公开 AAR 与内嵌 entry 逐项重算，再检查 `mergeReleaseArtProfile/baseline-prof.txt`，最后确认 APK entry 与 `compileReleaseArtProfile` 的二进制逐字节同 hash。`tools/release.sh` 在签名前验证 unsigned APK，签名后再对最终候选重验并把最终 report SHA 与全部计数写入 manifest；任一层不一致都会停止，必须重建并重新审查 profile。

### 签名连续性

Android 升级必须使用相同 signing certificate。候选生成和最终发布都必须提供同一个上一版 APK；两阶段分别重算其 hash、package、version 与 signer，不匹配时停止。

旧 App 与新 App 共存的部署窗口还要求双协议一致：先验证 Panel `/api/config` 向旧 App 下发的 flat backend base、endpoint 与 session-invalid policy，和向新 App 下发的 `backendAdapter` 完全等价；任一不一致都停止 Panel 发布。Session-invalid 等价只保证新旧 App 对会话失效的判断一致，不是任何已开始副作用 POST 的未写入证明。旧 cache 本身没有新连接绑定，因此应先让旧 App 同步一次带通用 cache proof 的等价新 revision；每台设备都必须在目标 revision 发布后彻底退出并重新启动旧 App，再验证 config/catalog 的精确 revision，而不是只等待前台 catalog 刷新。candidate 只有在 Panel origin、read-key 摘要、精确 catalog 摘要和 revision 全部匹配时才原地绑定。未预热设备仍必须联网完成同 revision config/catalog 同步，且遗留未绑定草稿保持锁定，不能把任意离线旧 cache 当作兼容路径或无感升级。

丢失 release keystore 通常意味着无法升级已安装 package。至少保留加密备份、恢复流程和有限访问审计。

当前 candidate 使用 realm-bound v2 session storage。Realm 绑定完整的 Panel/key 连接 SHA-256、
实际会携带 Authorization 的 endpoint URL 以及认证 transport/header 语义；只改 profile/显示内容
不会改变 realm。首次从没有 v2 binding 的旧版本覆盖安装时，新 App **不会读取或发送**旧 token、
password、account、userName 或 web fingerprint，现场必须预留一次重新登录。普通升级不会物理删除
旧 credential bytes，以保留用原 signer 回滚旧 App 的可能；用户显式切换 Panel/read key 时才会在
同一持久事务中清除 v1 与 v2 session。重新登录不删除草稿、队列、ledger 或待处理照片。发布验证
必须同时覆盖：旧无 binding 必须登出、同 realm/profile-only 更新保持新 v2 登录、任一 realm 字段
变化立即使旧 session 不可读，以及 Provider/Bridge 的 realm+fingerprint+session+stateId capability CAS。

### 版本与 package

- `versionCode` 必须对每次升级单调递增；
- `versionName` 使用脚本接受的语义化形式；
- package 从 `app/build.gradle` 的 `applicationId` 读取，并写入 `update.json`；
- 修改 `applicationId` 会变成另一个 Android App，不能原地升级旧 package，也会改变 provider authority；
- debug build 带 `.debug` suffix，可与 release 并存。

Package identity、图标和签名属于构建时例外，不能由 Panel 在 APK 安装后更改。运行时表单与 backend 差异仍应全部留在 Panel。

## 发布 GitHub Release

先人工检查标准 schema-2 候选目录，并让仓库外或 Git-ignored 的私有 gate 汇总所有私有迁移、真实回放与设备证据。然后从干净、已完整推送的 `main` 执行第二阶段。该命令拒绝 schema 3/4、任何 historical `publicationMode` 以及保留给重建流程的 v1.0.0–v1.0.6 tag：

```sh
tools/publish-release.sh \
  --candidate dist/release-candidates/v1.2.3/candidate-manifest.json \
  --previous-apk /secure/example/previous-release.apk \
  --gate /secure/example/autoform-private-release-gate \
  --private-migration-report /secure/example/form-profiles.migration-report.json \
  --panel-config-evidence /secure/example/panel-config.json \
  --panel-catalog-evidence /secure/example/form-profiles.json \
  --private-deployment-evidence /secure/example/deployment-input.json \
  --private-wordlist /secure/example/private-publication-wordlist.json
```

**当前发布策略有意保持 fail closed。** `tools/private-release-gate-policy.json` 的
`enabled` 仍为 `false`，因为仓库中尚无经审查并固定 SHA-256 的真实私有 gate。即使任意
external/ignored gate 抄写全部环境 hash 并把全部检查写成 `true`，发布脚本也会在调用它和
`gh release create` 之前停止。只有审查真实 gate 后，把其 exact SHA-256 写入该 source-commit-bound
policy 并显式启用，才能继续后续现场验证；不得为了发布临时关闭或绕过此检查。

原 gate 必须是可执行、非符号链接且 group/other 不可写的普通文件。发布脚本不会从该可变路径直接
执行：它把已匹配 policy SHA-256 的字节复制到 owner-only `0700` 临时目录，将 snapshot 固定为
owner-owned `0500`、再次核对 SHA-256，然后进入该目录并通过 inode-bound 相对路径执行；attestation
也在同一目录 inode 中创建和读取。原路径前后 identity/hash 复核只用于发现漂移，受信执行边界是
snapshot。Gate 应为单文件入口；若需要其他私有资源，应通过受控绝对路径或环境输入读取，不能依赖
原 `$0` 旁边的 sibling 文件被自动复制。

`TMPDIR` 会在创建任何发布临时目录前解析为物理路径并固定 identity；group/other 可写时只接受
sticky 且由 root 或当前 EUID 拥有的目录。Public-audit 与 attestation 临时目录分别绑定创建时的
device/inode/mode/owner，cleanup 只删除仍指向同一非符号链接目录的路径；若路径被替换则拒绝递归
删除并让发布失败，不能把 cleanup 当成对未知路径的恢复动作。

发布脚本本身不调用 Gradle、不签名、不生成 notes，也不复制或重打包 APK。它独立重验：

- candidate manifest 与 APK、`update.json`、notes、上一版 APK 的 SHA-256；
- 新旧 APK 的 package/version/signer、versionCode 单调递增，以及 `update.json` 对 APK 与 notes 的精确描述；
- 当前 checkout 是 `main`；
- working tree 干净；
- candidate source commit、本地 HEAD 与实时读取的 `origin/main` 三者完全一致；
- 当前扫描器字节与 candidate 的 `scannerSha256` 一致，然后重新扫描 exact pushed commit tree、clean worktree 与 exact candidate APK；fresh report 的 scanner/policy/input/report SHA 必须逐项等于 candidate 绑定，APK 的 ZIP-entry manifest、exact provenance profile、matched-entry count 与 runtime lock 也必须一致；
- 当前来源 verifier 字节与 candidate source commit 及 `sourceVerifierSha256` 一致，并对 exact candidate APK 重新执行 AAR → nested class / baseline source → compiled output / DEX string 的完整来源验证；fresh `sourceReportSha256` 和全部无值计数必须逐项等于 candidate 绑定；
- 用同一固定扫描器和私有词表重新扫描 exact `update.json`、release notes 与 candidate manifest；前两者必须命中 candidate 中的 exact input/report binding，manifest fresh report 必须绑定 manifest 当前实际 SHA；三者的扫描前后字节和实际上传路径必须保持一致；
- `gh` 已对 GitHub 登录；
- GitHub API 返回的 source repository 为 public，且仓库全名与 candidate 中的 owner/repo 精确一致；
- 完整 `git ls-remote origin` 只含一个指向 default branch head 的 literal `HEAD`、heads、tags 与格式严格的 `refs/pull/<正整数>/(head|merge)`；GitHub branches/tags API 与 Git transport 的 branch/tag identity 完全一致，branch protection 的 required-status contexts/checks 也进入敏感词扫描；
- 本地 / 远端 tag 与同名 GitHub Release 均不存在。

以上检查先执行一次。私有 gate 通过后会第二次完整读取候选、扫描器、来源 verifier、词表与全部 public surface，并重跑 source tree、worktree、APK、三份发布文件以及 commit/完整 remote refs/refs API/Releases metadata 共十个扫描和来源验证。随后 live private-evidence verifier 再次完整执行，其 report SHA-256 必须与 gate 所绑定的第一份完全相同；verifier 返回后还会第三次捕获并扫描 repository、完整 remote refs、GitHub branches/tags 与 Releases，并与第一次 metadata binding 逐项比较，之后才执行唯一的 `gh release create --latest`。脚本从不把旧报告或人工布尔值当作公开安全结论；旧报告即使 JSON 合法，只要 input/report binding 或计数不同也会失败。每次扫描前后还会重算 verifier、normalizer、扫描器、词表和相应输入字节，阻止扫描中的 TOCTOU。第二阶段不调用 Gradle，因此必须保留生成该候选时的 exact `app/build` intermediates 和 policy 绑定的 Gradle cache AAR；缺失或改变会 fail closed，不能用 candidate manifest 中的旧计数代替现场验证。Release 上传候选中的 APK、`update.json` 与 `candidate-manifest.json`，并把同一候选中的 `release-notes.txt` 作为正文；不会覆盖已有 tag / Release。

`publish-release.sh` 只接受稳定版 candidate；带 `-beta`、`-rc` 等 prerelease 后缀的版本会在私有 gate 和发布之前停止。beta 更新使用 App 已有的固定 `beta` tag 路由，必须由独立流程发布且明确设置 `--latest=false`，不能替换 stable `/releases/latest`。

创建后，脚本通过 GitHub API 重新读取 `/releases/latest`，要求 tag、title 与 release body（即 exact `release-notes.txt` 字节）精确匹配、`draft:false`、`prerelease:false`，且资产恰好只有 candidate APK、`update.json`、`candidate-manifest.json` 三项；随后逐项通过 authenticated asset API 下载实际公开字节并核对 SHA-256。`gh release create` 可能在返回失败前已经创建 tag、draft、Release 或部分资产；创建失败或创建后复核失败时，脚本会明确报告“远端可能已有部分发布”，且不会自动删除或声称已回滚。此时必须冻结重试并人工核对远端状态。

### 固定 `beta` tag 发布

当前独立 Beta publisher 只接受 package `com.autoformkit.app`、`versionCode=10`、
`versionName=1.0.8-beta.2`、candidate tag `v1.0.8-beta.2`。公开 Release 继续使用 App 协议固定读取的
`beta` tag。Release title 固定为 `autoform-kit 1.0.8-beta.2`，body 与
`update.json.notes` 固定为：

```text
Public beta build of the autoform-kit framework. No site-specific configuration is included.
```

先用已发布的 code 9、name `1.0.8-beta.1` exact 已签名 APK 作为 `--previous-apk` 生成并审查 schema-2
candidate；该文件和所有私有词表必须是仓库外、owner-only mode-`0600` 文件。随后从 candidate
绑定的干净、已完整推送 source commit 执行：

```sh
AUTOFORM_BETA_SINGLE_WRITER_WINDOW=EXCLUSIVE_BETA_RELEASE_WRITER_CONFIRMED \
  tools/publish-beta-release.mjs \
  --candidate dist/release-candidates/v1.0.8-beta.2/candidate-manifest.json \
  --previous-apk /secure/example/autoform-kit-1.0.8-beta.1-installed.apk \
  --private-wordlist /secure/example/private-publication-wordlist.json
```

该命令不调用或复用 stable publisher。首个远端写之前，它执行两次完整 preflight：重新核对
candidate directory closure、APK/update/notes/manifest SHA、package/code/name/signer、previous APK、
source commit/tree/clean worktree、source commit 原始对象（含 author/committer/message）、scanner 与
第三方来源 verifier；扫描固定 title/body、candidate 和公开 repository/refs/branches/tags/Releases
投影；要求公开引用恰好只有唯一 default branch、v1.0.0-v1.0.6 七个同指净化祖先提交的轻量 tag、
可选的唯一 stable latest tag，以及已发布的 exact `beta` / `1.0.8-beta.1` Release，且没有额外
branch/tag/pull ref。旧 Beta 必须是 code 9、同 signer、固定 title/body、非 draft prerelease，资产恰好为
beta.1 APK、`update.json`、`candidate-manifest.json`；publisher 会逐项下载、扫描、核对 SHA、APK identity、
update 内容、schema-2 manifest 和 source commit，并要求其 APK 与本次 `--previous-apk` 完全相同且 source
commit 是 beta.2 source 的祖先。publisher 从 exact remote roots 在本地逐对象读取全部可达
commit/tree/blob，扫描包含对象位置、元数据和原始字节的完整闭包，同时把历史语义和
`/releases/latest` 的 exact 状态冻结为只读基线。
`latest` 可以在开始时为 404，也可以是一份已存在且非 draft、非 prerelease 的 exact stable Release，
但整个 Beta 发布期间必须逐字段不变。历史逐版 publisher 的 `historical-incremental` attestation 明确
绑定一个 v1.0.0-v1.0.6 excluded tag，不能冒充 Beta 证明；Beta 因而每轮现场重建并扫描完整可达闭包。

轮换写流程有五个独立检查点：先在 beta.1 source commit 创建轻量归档 tag `v1.0.8-beta.1`；再把旧
Release 精确改绑到该 tag，并保持 `draft:false`、`prerelease:true`、`make_latest:"false"`；复核归档
Release 后才删除旧的固定 `refs/tags/beta`；随后执行
`gh release create beta --draft --prerelease --latest=false`，只上传 candidate APK、
`update.json` 与 `candidate-manifest.json`。GitHub 的未发布 draft 此时只有 Release identity，不会提前创建
`refs/tags/beta`；publisher 因而要求 `beta` ref 仍不存在，按唯一 Release ID 读取 draft，并下载三项实际
远端资产重新核对 SHA、APK identity、signer、source provenance 和敏感扫描。紧邻 PATCH 之前还会再次
完整获取 repository/refs/branches/tags/Releases 与同一 Release ID，重建可达对象闭包，并第二次下载、
扫描和逐字节核对三项资产；该边界的任何 metadata、ref 或 asset 并发变化都会在 PATCH 前停止。全部通过
后才用 REST PATCH 提交字符串字段 `make_latest: "false"`，同时明确保持 `prerelease:true` 并改为
`draft:false`。发布成功后才要求 `refs/tags/beta` 精确指向 candidate source commit，并通过 tag endpoint
再次读取同一个 Release；PATCH 响应和随后完整远端复核也必须精确通过。最终响应不要求出现
`make_latest` 字段；是否意外成为 latest 只以发布前后 `/releases/latest` exact identity 复核为准。
每次远端写后都会重新执行完整 preflight；stable/latest、历史 Release、无关 refs、repository metadata
或可达对象闭包的任何变化都会 fail closed。归档 tag 已建、旧 Release 已改绑、旧固定 ref 已删除和新
draft 已创建都是可识别的恢复检查点；发布 PATCH 已实际生效但响应丢失时，完整 beta.2 状态也会在下次
运行中只读验明并成功退出，不会重复写入。

`AUTOFORM_BETA_SINGLE_WRITER_WINDOW` 是操作员对“从 draft create 到最终复核期间已暂停其他 repository /
Release 写入者和自动化”的显式确认，不是 GitHub 锁、ETag CAS 或对外部写权限的技术约束。当前使用的
GitHub Release PATCH endpoint 没有在本流程中实测出可依赖的条件写语义，因此不得把该环境变量描述成
防并发保证；若不能维持实际单写入窗口就不能运行。任何 create 后错误都只报告远端可能存在 draft、tag
或 partial assets，绝不自动回滚或删除未知状态；重新运行时只允许 publisher 自己分类上述 exact
checkpoint。若存在唯一且完全匹配的 draft，错误信息会给出其数值 Release ID，必须显式运行恢复模式：

```sh
AUTOFORM_BETA_SINGLE_WRITER_WINDOW=EXCLUSIVE_BETA_RELEASE_WRITER_CONFIRMED \
  tools/publish-beta-release.mjs \
  --candidate dist/release-candidates/v1.0.8-beta.2/candidate-manifest.json \
  --previous-apk /secure/example/autoform-kit-1.0.8-beta.1-installed.apk \
  --private-wordlist /secure/example/private-publication-wordlist.json \
  --resume-draft RELEASE_ID
```

恢复模式不会创建另一个 Release；它会对指定 ID 重新执行两轮完整只读 preflight 和 draft 资产验证，
只在边界仍精确一致时发布同一个 draft。若该 PATCH 已经成功但客户端只丢失响应，同一命令会验证 exact
complete checkpoint 后零写入退出。

离线 fake-tool 自测不联网，也不会执行真实 Git、设备或 GitHub 写入：

```sh
node tools/beta-release-publisher-selftest.mjs
```

旧 code-8 `v1.0.7` candidate 已作废，不能作为 Beta 或 stable 发布。通过 Beta 后的未来 stable 至少使用
`versionCode=11`、`versionName=1.0.8` 和 tag `v1.0.8`，仍由 stable publisher 独立发布为 latest；
code-10 Beta 设备必须显式切回 stable 通道并完成 code10→code11 真机升级证据。Beta 发布和未来 stable
都不得改变 v1.0.0-v1.0.6 的历史 Release/title/body/assets 语义。

### Catalog authority 与 live manifest 证据

Mode-`0600` private deployment evidence 必须显式选择且只选择一条 authority 分支。正式发布只接受
schema v2 的 `catalogAuthority.type` discriminator；schema v1 仅用于识别旧输入并给出明确失败，不能用于
发布。Verifier 不会仅凭相同的 live 响应字节猜测 authority，因此真实输入必须来自已审阅的 Worker
deployment state，不能靠手工声明选择分支。两种 v2 输入分别为：

```json
{
  "schemaVersion": 2,
  "panelBase": "https://panel.example.invalid",
  "catalogReadKey": "<external-secret>",
  "catalogAuthority": {
    "type": "github",
    "accountId": "<32-lowercase-hex>",
    "workerName": "<exact-worker-name>",
    "repository": "<private-owner/name>",
    "branch": "<exact-branch>",
    "commit": "<exact-git-object-id>"
  }
}
```

```json
{
  "schemaVersion": 2,
  "panelBase": "https://panel.example.invalid",
  "catalogReadKey": "<external-secret>",
  "catalogAuthority": {
    "type": "r2",
    "accountId": "<32-lowercase-hex>",
    "workerName": "<exact-worker-name>",
    "bucket": "<private-bucket-name>",
    "jurisdiction": null
  }
}
```

`panelBase` 必须是与 Panel pair proof 相同的单一 HTTPS origin；不能带 credentials、业务 path、query
或 fragment。`catalogReadKey` 至少 32 个字符，并应使用高熵随机值。R2 `jurisdiction` 只能是
`null`、`eu` 或 `fedramp`。CLI 传入的 migration report、Panel config、Panel catalog 与 deployment
evidence 四个私有输入都必须是非空、非符号链接的普通文件，权限精确为 `0600`，单文件不得超过
16 MiB；读取窗口内 bytes/stat 变化会 fail closed。

- **GitHub authority**：authenticated GitHub API 确认 exact repository 私有且与 public source 分离，
  再要求 Worker 的 `GITHUB_BRANCH`（或仓库 default branch）与 evidence branch 一致、该 branch head
  精确等于声明 commit，并绑定该 commit 中
  `form-profiles.json`、`manifest.json` 和 optional `panel-settings.json` 的原始字节；未配置
  Worker-only setting 时，以 exact commit Git tree 中不存在该 blob 绑定显式 absent 状态，而不是依赖
  `404` 或创建空文件。Live Worker version 必须恰好绑定同一个 `GITHUB_REPO`，且不得同时存在
  `CATALOG_R2`。
- **R2 authority**：使用 repository-local Wrangler 与受管 Cloudflare 凭据执行 remote read，确认 bucket
  identity、`r2.dev` URL 已禁用且没有 custom domain；随后绑定 `catalog-current-v1.json` 的 exact
  bytes、`stateSha256`、content-addressed immutable snapshot，以及 snapshot 内同一组 exact 文件和
  optional absent 状态。Verifier 在同一验证窗口末尾重新 remote-read current pointer，并要求原始
  bytes 完全相同。当前合同不取得或绑定 R2 ETag；若未来把 ETag 纳入更强证明，必须升级并测试合同，
  不能把现有 byte recheck 描述为 ETag evidence，也不能检测 `A → B → A` 的 ABA 变化。Live Worker
  version 必须恰好绑定所声明的 `CATALOG_R2` bucket 与 jurisdiction。R2 cutover 后的 GitHub baseline
  可能过期，不能作为 current 或 rollback 证明。

Verifier 会核对 Wrangler package 声明、bin 路径、版本输出以及 lockfile 中的 version/integrity 描述，
但这不是对已安装 Wrangler 全部执行字节的独立供应链证明。发布环境中的 Node、repository-local
`node_modules`、OS、凭据存储、Cloudflare CLI/API，以及 PATH 中未由本合同固定版本/哈希的 `gh`、
其 GitHub 登录配置和 GitHub API 都仍是外部信任根。`gh` 会话必须同时具备读取所声明 private catalog
repository 的权限，以及检查并创建 public source repository Release 所需的权限。应从 clean
`npm ci` 环境运行，并由受审私有 gate 绑定更强的工具链证据。
`catalogAuthorityWorkerBindingSha256` 同时折入 Wrangler 工具描述，不能把它解释成 Cloudflare 对
Worker bundle 的密码学 attestation，也不能视为对 `gh` 的绑定。

两条分支都稳定读取 mode-`0600` 的外部 config/catalog evidence，再 authenticated fetch live
`/api/config`、App-facing catalog 和 `/catalog/manifest`。Config/catalog 必须与 evidence 原始字节相同；
live manifest 必须与 authority manifest 原始字节相同，并以 SHA-256 绑定 catalog version、catalog
digest、same-origin `profilesUrl` 与最低 App version。Worker-only setting 不通过 live App route 获取，
只从所选 authority 绑定 present/absent 及其 domain-separated SHA-256。匿名 catalog/config/manifest、
profiles、Panel config、runtime provenance 与 notification 请求必须全部返回 `401`；向这七条受保护
route 分别发送一个确保不同于真实 key 的错误 Bearer 也必须返回 `401`。验证窗口末端还会重新读取 authenticated runtime
provenance，要求原始响应字节完全不变，并重新确认 public repository identity 仍为 public。

上述 GitHub/R2 discriminator、private-bucket checks、pointer byte recheck、optional settings 与 live
manifest exact binding 已由 `verify-private-release-evidence.mjs` 的 schema v2 report 实现，并由
`publish-release.sh` 传入 schema v4 attestation 的 generic authority fields。当前
`release-workflow-selftest.sh` 覆盖 verifier 的纯函数合同、trusted-gate-disabled 的 fail-closed 路径，
并强制运行 `private-release-chain-selftest.sh`。后者在临时真实 Git repository 中跑通
verifier → gate environment → schema-v4 attestation → 第二次 exact verifier → Release 创建前边界 →
发布后资产下载复核的隔离成功链，也验证篡改 attestation 会在 Release 创建前停止。它对预期的
GitHub、Git remote、Panel fetch、Wrangler 和 Android metadata tool 边界使用严格 fixture；这不是
OS 层网络隔离，也不能代替真实部署证据。当前全链成功 fixture 只覆盖 GitHub authority 且
`panelSettingsPresent:false`；R2 authority 与 settings-present 分支由 verifier 单元/纯函数测试覆盖，
尚不是各自的端到端发布链成功证据。

Runtime provenance 是 private-evidence verifier 的强制输入：它通过 read key 请求
`/api/runtime-provenance`，校验 source commit 与 Worker version id，并在验证末端重读 exact bytes。
它仍只证明受控部署流程提供的 version metadata/source tag，不是上传 bundle 的 byte attestation；受审
private gate 在生成 `privateDeployment:true` 前还必须绑定独立的 exact bundle/build 证据。

> **当前发布结论仍为 NO-GO。** 尚未为实际 deployment 生成本次 candidate 绑定的 mode-`0600`
> config/catalog/deployment inputs、authenticated live authority/manifest 结果和 release-ready migration
> report；真实 private gate 也尚未审阅固定，source policy 仍为 disabled。实现和 selftest 通过不等于
> 这些现场证据已经存在。

### 私有 gate 与 attestation 契约

在 policy 尚未启用时，本节是启用 gate 前必须满足并审查的合同，不表示当前已可发布。
仓库中的 `verify-private-release-evidence.mjs` 已实现 mode-`0600` migration report、Panel
config/catalog pair、GitHub/R2 authority、optional settings、authenticated live manifest 与 runtime
provenance 的 schema v2 检查。它只在取得真实 deployment inputs 时才有现场意义；当前没有这些输入。
在真实 gate 完成独立审查、固定身份和完整 live 验证前，policy 保持
fail closed。手填 status/hash/boolean 不构成启用依据。

`--gate` 必须是可执行的普通文件。它可以位于仓库外；若位于仓库内，则必须未被跟踪且被 `.gitignore` 覆盖。发布脚本不接受预先写好的证明，而是为每次调用创建一个新的私有临时输出路径，再执行 gate。以下环境变量提供给 gate：

- `AUTOFORM_RELEASE_REPOSITORY_ROOT`；
- `AUTOFORM_RELEASE_CANDIDATE_MANIFEST` 与 `AUTOFORM_RELEASE_CANDIDATE_MANIFEST_SHA256`；
- `AUTOFORM_RELEASE_APK`、`AUTOFORM_RELEASE_UPDATE`、`AUTOFORM_RELEASE_NOTES`、`AUTOFORM_RELEASE_PREVIOUS_APK`，以及对应的 `AUTOFORM_RELEASE_*_SHA256`；
- `AUTOFORM_RELEASE_SOURCE_COMMIT`；
- `AUTOFORM_RELEASE_PUBLIC_AUDIT_SCANNER_SHA256` 与 `AUTOFORM_RELEASE_PUBLIC_AUDIT_POLICY_SHA256`；
- `AUTOFORM_RELEASE_PUBLIC_TREE_OID`、`AUTOFORM_RELEASE_PUBLIC_TREE_INPUT_SHA256`、`AUTOFORM_RELEASE_PUBLIC_TREE_REPORT_SHA256`；
- `AUTOFORM_RELEASE_PUBLIC_WORKTREE_INPUT_SHA256`、`AUTOFORM_RELEASE_PUBLIC_WORKTREE_REPORT_SHA256`；
- `AUTOFORM_RELEASE_PUBLIC_APK_INPUT_SHA256`、`AUTOFORM_RELEASE_PUBLIC_APK_REPORT_SHA256`、`AUTOFORM_RELEASE_PUBLIC_APK_ZIP_ENTRY_MANIFEST_SHA256`；
- `AUTOFORM_RELEASE_APK_THIRD_PARTY_POLICY_SHA256`、`AUTOFORM_RELEASE_APK_THIRD_PARTY_PROFILE_ID`、`AUTOFORM_RELEASE_APK_THIRD_PARTY_MATCHED_ENTRY_COUNT` 与 `AUTOFORM_RELEASE_ANDROID_RUNTIME_LOCK_SHA256`；
- `AUTOFORM_RELEASE_APK_SOURCE_VERIFIER_SHA256`、`AUTOFORM_RELEASE_APK_SOURCE_REPORT_SHA256`；
- `AUTOFORM_RELEASE_APK_SOURCE_ARTIFACT_COUNT`、`AUTOFORM_RELEASE_APK_SOURCE_ENTRY_COUNT`、`AUTOFORM_RELEASE_APK_MERGED_SOURCE_COUNT`、`AUTOFORM_RELEASE_APK_COMPILED_OUTPUT_COUNT`；
- `AUTOFORM_RELEASE_APK_DEX_SOURCE_ARTIFACT_COUNT`、`AUTOFORM_RELEASE_APK_DEX_SOURCE_ENTRY_COUNT`、`AUTOFORM_RELEASE_APK_DECLARED_DEX_STRING_COUNT`、`AUTOFORM_RELEASE_APK_SOURCE_MATCHED_DEX_STRING_COUNT`、`AUTOFORM_RELEASE_APK_MATCHED_DEX_STRING_COUNT`；
- `AUTOFORM_RELEASE_PUBLIC_UPDATE_INPUT_SHA256`、`AUTOFORM_RELEASE_PUBLIC_UPDATE_REPORT_SHA256`；
- `AUTOFORM_RELEASE_PUBLIC_NOTES_INPUT_SHA256`、`AUTOFORM_RELEASE_PUBLIC_NOTES_REPORT_SHA256`；
- `AUTOFORM_RELEASE_PUBLIC_MANIFEST_INPUT_SHA256`、`AUTOFORM_RELEASE_PUBLIC_MANIFEST_REPORT_SHA256`；
- `AUTOFORM_RELEASE_PUBLIC_COMMIT_OBJECT_FILE`、`AUTOFORM_RELEASE_PUBLIC_COMMIT_OBJECT_INPUT_SHA256`、`AUTOFORM_RELEASE_PUBLIC_COMMIT_OBJECT_REPORT_SHA256`；
- `AUTOFORM_RELEASE_PUBLIC_REMOTE_REFS_FILE`、`AUTOFORM_RELEASE_PUBLIC_REMOTE_REFS_INPUT_SHA256`、`AUTOFORM_RELEASE_PUBLIC_REMOTE_REFS_REPORT_SHA256`；
- `AUTOFORM_RELEASE_PUBLIC_REF_API_FILE`、`AUTOFORM_RELEASE_PUBLIC_REF_API_INPUT_SHA256`、`AUTOFORM_RELEASE_PUBLIC_REF_API_REPORT_SHA256`；
- `AUTOFORM_RELEASE_PUBLIC_RELEASES_FILE`、`AUTOFORM_RELEASE_PUBLIC_RELEASES_INPUT_SHA256`、`AUTOFORM_RELEASE_PUBLIC_RELEASES_REPORT_SHA256`；
- `AUTOFORM_RELEASE_PUBLIC_REF_IDENTITY_SHA256`、`AUTOFORM_RELEASE_PUBLIC_PULL_REF_IDENTITY_SHA256`、`AUTOFORM_RELEASE_PUBLIC_REMOTE_REFS_RAW_SNAPSHOT_SHA256`、`AUTOFORM_RELEASE_PUBLIC_REF_API_SNAPSHOT_SHA256`、`AUTOFORM_RELEASE_PUBLIC_RELEASE_API_SNAPSHOT_SHA256`、`AUTOFORM_RELEASE_PUBLIC_REPOSITORY_BINDING_SHA256` 与 `AUTOFORM_RELEASE_PUBLIC_METADATA_BINDING_SHA256`；
- `AUTOFORM_RELEASE_PRIVATE_EVIDENCE_VERIFIER_SHA256`、`AUTOFORM_RELEASE_PRIVATE_EVIDENCE_REPORT_SHA256` 与 `AUTOFORM_RELEASE_PRIVATE_MIGRATION_REPORT_SHA256`；
- `AUTOFORM_RELEASE_PRIVATE_PANEL_CONFIG_SHA256`、`AUTOFORM_RELEASE_PRIVATE_PANEL_CATALOG_SHA256`、`AUTOFORM_RELEASE_PRIVATE_PANEL_PAIR_SHA256` 与 `AUTOFORM_RELEASE_PRIVATE_CATALOG_VERSION`；
- `AUTOFORM_RELEASE_PRIVATE_DEPLOYMENT_EVIDENCE_SHA256`、`AUTOFORM_RELEASE_PRIVATE_PANEL_WORKER_VERSION_ID`、`AUTOFORM_RELEASE_PRIVATE_CATALOG_AUTHORITY_TYPE`、`AUTOFORM_RELEASE_PRIVATE_CATALOG_AUTHORITY_IDENTITY_SHA256`、`AUTOFORM_RELEASE_PRIVATE_CATALOG_AUTHORITY_REVISION` 与 `AUTOFORM_RELEASE_PRIVATE_CATALOG_AUTHORITY_WORKER_BINDING_SHA256`；
- `AUTOFORM_RELEASE_PRIVATE_CATALOG_MANIFEST_SHA256`、`AUTOFORM_RELEASE_PRIVATE_PANEL_SETTINGS_PRESENT`、`AUTOFORM_RELEASE_PRIVATE_PANEL_SETTINGS_SHA256` 与 `AUTOFORM_RELEASE_PRIVATE_GATE_SHA256`；
- 必须由 gate 新建的 `AUTOFORM_RELEASE_ATTESTATION_OUT`。

Gate 可在私有工作区运行迁移、worktree/full-history/Release asset 扫描、candidate APK 检查、签名覆盖升级、故障注入、回滚演练与流程等价回放。它必须把仅含公开安全摘要的最终判断写成下面的 schema；示例 hash 均为占位符：

```json
{
  "schemaVersion": 4,
  "releaseReady": true,
  "bindings": {
    "candidateManifestSha256": "<64-hex>",
    "apkSha256": "<64-hex>",
    "updateSha256": "<64-hex>",
    "notesSha256": "<64-hex>",
    "previousApkSha256": "<64-hex>",
    "sourceCommit": "<candidate-source-commit>",
    "publicAudit": {
      "scannerSha256": "<64-hex>",
      "policySha256": "<64-hex>",
      "sourceTree": {
        "gitTreeOid": "<exact-tree-oid>",
        "inputSha256": "<64-hex>",
        "reportSha256": "<64-hex>"
      },
      "worktree": {
        "inputSha256": "<64-hex>",
        "reportSha256": "<64-hex>"
      },
      "apk": {
        "inputSha256": "<same-apk-sha256>",
        "reportSha256": "<64-hex>",
        "zipEntryManifestSha256": "<64-hex>"
      },
      "thirdPartyProvenance": {
        "manifestFile": "tools/apk-third-party-components.json",
        "manifestSha256": "<64-hex>",
        "runtimeLockFile": "tools/android-runtime-dependencies.lock.json",
        "runtimeLockSha256": "<64-hex>",
        "profileId": "<reviewed-exact-profile>",
        "matchedEntryCount": 1,
        "applicationDexStrict": true,
        "sourceVerifierFile": "tools/verify-apk-third-party-sources.mjs",
        "sourceVerifierSha256": "<64-hex>",
        "sourceReportSha256": "<64-hex>",
        "sourceArtifactCount": 6,
        "sourceEntryCount": 6,
        "mergedSourceCount": 1,
        "compiledOutputCount": 2,
        "dexSourceArtifactCount": 2,
        "dexSourceEntryCount": 2,
        "declaredDexStringCount": 5,
        "sourceMatchedDexStringCount": 5,
        "apkMatchedDexStringCount": 5
      },
      "releaseMetadata": {
        "update": {
          "inputSha256": "<same-update-sha256>",
          "reportSha256": "<64-hex>"
        },
        "notes": {
          "inputSha256": "<same-notes-sha256>",
          "reportSha256": "<64-hex>"
        },
        "candidateManifest": {
          "inputSha256": "<same-candidate-manifest-sha256>",
          "reportSha256": "<64-hex>"
        }
      },
      "publicHistory": {
        "sourceCommitObject": {
          "inputSha256": "<64-hex>",
          "reportSha256": "<64-hex>"
        },
        "remoteRefs": {
          "inputSha256": "<64-hex>",
          "reportSha256": "<64-hex>"
        },
        "refsApi": {
          "inputSha256": "<64-hex>",
          "reportSha256": "<64-hex>"
        },
        "releases": {
          "inputSha256": "<64-hex>",
          "reportSha256": "<64-hex>"
        },
        "refIdentitySha256": "<64-hex>",
        "pullRefIdentitySha256": "<64-hex>",
        "remoteRefsRawSnapshotSha256": "<64-hex>",
        "refApiSnapshotSha256": "<64-hex>",
        "releaseApiSnapshotSha256": "<64-hex>",
        "repositoryBindingSha256": "<64-hex>",
        "metadataBindingSha256": "<64-hex>"
      }
    },
    "privateEvidence": {
      "verifierFile": "tools/verify-private-release-evidence.mjs",
      "verifierSha256": "<64-hex>",
      "verificationReportSha256": "<64-hex>",
      "migrationReportSha256": "<64-hex>",
      "panelConfigSha256": "<64-hex>",
      "panelCatalogSha256": "<64-hex>",
      "panelPairSha256": "<64-hex>",
      "deploymentEvidenceSha256": "<64-hex>",
      "catalogVersion": 42,
      "panelWorkerVersionId": "<worker-version-uuid>",
      "catalogAuthorityType": "<github-or-r2>",
      "catalogAuthorityIdentitySha256": "<64-hex>",
      "catalogAuthorityRevision": "<git-commit-or-r2-state-sha256>",
      "catalogAuthorityWorkerBindingSha256": "<64-hex>",
      "catalogManifestSha256": "<64-hex>",
      "panelSettingsPresent": false,
      "panelSettingsSha256": "<file-or-domain-separated-absent-sha256>",
      "privateGateSha256": "<source-policy-pinned-64-hex>"
    }
  },
  "checks": {
    "privateMigration": true,
    "privateDeployment": true,
    "publicWorktree": true,
    "publicHistory": true,
    "candidateApk": true,
    "signedUpgrade": true,
    "rollback": true,
    "flowParity": true,
    "controlledRecovery": true
  }
}
```

`bindings` 与 `checks` 必须和上面对象精确相等，不能用非空字符串、`ok:true` 或 `structuralOk:true` 代替。发布脚本会现场捕获并用同一外部私有词表独立扫描 source commit 对象、完整 Git transport refs、GitHub refs API 投影，以及稳定化后的 GitHub Release/asset metadata；四份 scan input/report 都是 owner-only mode-`0600` 普通单链接文件。私有 gate 必须绑定这些 exact input/report SHA-256 及上述 identities/snapshots/v2 metadata binding，单独写 `publicHistory:true` 已不再被接受。上述四个只读文件路径也会交给 gate，以便其完整历史与 Release asset 下载审计核对同一时刻的公开状态；公开脚本对 metadata 的扫描不替代私有 gate 对历史可达对象和实际 asset 字节的逐项扫描。gate 返回后先完整复捕获，live private verifier 复核后再做第三次关键 metadata 捕获；任一并发 ref/status/Release/repository metadata 变化都会 fail closed。

GitHub metadata 不直接把 REST 响应整块交给私有词表。GitHub 会在 repository、actor、uploader
和派生 URL 中机械重复公开 owner；若 owner 本身也在部署词表中，整块扫描会产生不可消除的误报。
发布器使用 source-commit 绑定的 `tools/normalize-github-releases-for-audit.mjs` 严格验证
repository id/node ID/full name/public/private、branch/tag/release/asset/user/reaction schema，以及所有
GitHub/API/download URL 的 host、路径和对象 ID。Release/tag/title/body/target commitish、asset
name/label/content type、branch/tag name、required-status context，以及 repository
description/homepage/topics/default branch 等管理员可编辑文本会逐字进入扫描投影。只有与
repository owner 精确相同的 actor/uploader login 可从文本投影省略；任何第三方 Release author 或
asset uploader 的 login/type 都会进入扫描。owner 与服务器派生 URL 仍只进入 canonical raw
snapshot/hash binding。未知 Release、asset、user、reaction、
branch 或 tag 字段一律失败。Release raw snapshot 只排除会自然变化的 `download_count` 和
`reactions`；remote-ref raw snapshot 保留 literal `HEAD`、annotated-tag object OID/peeled commit 以及 pull head/merge OID。严格 normalizer 拒绝缺失、重复或错指向 default branch 的 `HEAD`，也拒绝 heads/tags/pull 之外的任何未知 ref namespace；pull refs 另有独立 `pullRefIdentitySha256`，不会被 branch/tag identity 掩盖。

标准最终发布的私有 full-history audit 必须使用 `auditMode:"final"`，并把规范化完整 Git refs（包括全部 `refs/pull/*`）、GitHub refs API 与 Release exact-input SHA、remote raw/refs API/Release API snapshot、branch/tag identity、pull-ref identity、repository identity、v2 组合 metadata binding，以及从 heads/tags/pull refs 出发的完整 reachable-object closure SHA/report SHA/count 写入原有 mode-`0600` migration report。source-commit 绑定的私有 verifier 要求这些值与发布器第一次现场捕获完全一致。历史逐版发布使用前述独立的 `historical-incremental` wrapper；两种证据的 kind/mode/Release envelope 不可互换。私有 gate 后进行第二次完整捕获，live verifier 复核后再进行第三次关键 metadata 捕获；因此同名仓库删除重建、pull/head/tag/status/Release 并发变化或可编辑仓库说明变化都会停止发布。

这个边界只覆盖发布流程明确读取的 repository selection、branches、tags、完整 Git refs（含 pull refs）、Releases/assets 和 Git
可达对象；pull ref 的对象可达性被覆盖不等于 PR 页面正文/评论已被覆盖，也不代表已扫描 GitHub Pages、Wiki、issues、PR body/comments、Actions 日志/产物或 repository
custom properties。若这些功能已公开启用，必须另做逐面审计并把结果纳入私有 gate；在此之前不能
声称“GitHub 全部公开面已审完”。本发布流程同时把 Release tag 限制为无斜杠的安全单段名称。

上面的 schema v4 已使用 generic authority fields 接收 verifier schema v2 的 GitHub/R2 结果。
`catalogAuthorityRevision` 对 GitHub 是 exact commit，对 R2 是 current pointer 绑定的 `stateSha256`；
`panelSettingsPresent:false` 时，`panelSettingsSha256` 是固定 domain-separated absent digest，不是假空文件。
旧 deployment-evidence schema v1 只会被识别并拒绝；正式证据必须使用 schema v2 discriminator。

`privateDeployment:true` 必须由私有 gate 在受控本地证据中确认：GitHub authority 分支的实际
`GITHUB_REPO` 与 public source repository 不同且目标 repository 为 **private**；R2 authority 分支则
必须证明 private bucket、live current pointer exact bytes/state/snapshot 和验证窗口前后的 pointer byte
stability，而不能把 GitHub baseline 当作 authority 或声称已绑定 ETag。两种分支均须
确认 bootstrap GitHub repository 不公开、正式部署存在至少 32 字符的高熵 `CATALOG_READ_KEY`，
且匿名与 deliberately incorrect Bearer 分别请求 `/catalog/form-profiles.json`、`/catalog/manifest`、
`/api/config`、`/api/profiles`、`/api/panel-config`、`/api/runtime-provenance` 与 `/api/notify` 七条受保护
route 都返回 `401`。公开源码无法替代这些部署证据，因为 Worker 为无 live data/adapter 的 local demo 保留了
“未设置 key 时开放”的兼容行为。任一证据缺失、过期或不匹配时，gate 必须输出
`releaseReady:false`，不得发布。

`controlledRecovery:true` 只能在私有 gate 已对精确 backend adapter、Panel/catalog pair、reconciliation contract、脱敏 replay 输入/结果及 signed-upgrade 设备证据运行 `tools/controlled-recovery-attestation.mjs` 并通过后写入；一个手填布尔值、可编辑结果 JSON 或普通操作员按钮都不是核销证据。只要任一 hash 不同、`releaseReady` 不是布尔值 `true`、任一检查缺失/为假，或 gate 没有现场生成普通 JSON 文件，发布就会停止。这里的 public audit binding 来自脚本自己的 fresh scan，并由 gate 再确认；它不替代 private migration、历史/Releases 和真机流程证据。私有原始输入、词表、设备证据和 attestation 不上传到公开 Release；需要留档时由 gate 在受控私有存储中另存。

#### Controlled-recovery 私有证据契约

`tools/controlled-recovery-attestation.mjs` 是私有 gate 的验证器，不是 App 恢复入口，也不会清理任何设备状态。Gate 必须把 adapter、contract、replay input/result、签名 attestation 及下述 12 个 artifact 放在仓库外的普通非符号链接文件中，所有文件权限必须精确为 `0600`（不得带 setuid、setgid 或 sticky 特殊位）；私有 evidence directory 必须是权限精确为 `0700` 的非符号链接目录。验证器通过已打开的文件描述符读取，并在打开、读取与发布关键点核对 inode、大小、权限及修改元数据；检测到普通路径替换或元数据漂移即失败。证据路径在验证期间必须由 gate 独占，同一用户不得并发改名或写入；这些核对是 fail-closed 检测，不是文件系统锁。调用时还要传入 exact source commit、正整数 catalog revision、logical config/catalog pair SHA-256，以及一个尚不存在的 `--output` 路径。成功结果只会以同目录临时文件完整写入并 `fsync`，再原子发布为 `0600` 普通文件；已有文件或符号链接不会被覆盖。命令强制读取的 evidence directory 为 `final-submission`、`previous-step-recipe`、`multipart-upload` 三个前缀分别要求以下四个非空文件：

- `*.server-correlation.bin`：reconciliation authority 在原请求发生时或之前持有的服务端相关性来源。它必须能把 exact journal subject/operation 映射到原 backend 请求；事后只凭 `subjectSha256` 猜 serial、时间窗口或 payload 不算相关性证据。
- `*.remote-receipt.bin`：原 backend 的权威查询结果或不可变 receipt，能够区分 written、not-written，以及 upload 的 partial/complete；操作员选择、截图文字或本地 JSON 不算 receipt。
- `*.journal-pair-cross-proof.bin`：证明 journal 中的 connection/source/payload/operation 与传入的 exact catalog revision、Panel pair、backend adapter/contract 属于同一次不可变上下文。仅把这些值分别哈希后并列，不算 cross-proof。
- `*.legacy-uncertain-fixture.bin`：该操作类型的 legacy/unknown/损坏记录与升级故障注入证据；三类必须分别存在，不能用一个总布尔值代替。

Replay input 是 exact JSON object，绑定 `sourceCommit`、`catalogVersion`、`panelPairSha256`、`backendAdapterSha256`、`reconciliationContractSha256`，并逐操作绑定 correlation/cross-proof/legacy fixture SHA。Replay result 必须绑定 replay-input 原始字节 SHA，并逐操作绑定同一组 SHA、remote receipt SHA 与精确检查表。最后的 RS256 attestation 再绑定 adapter capability、contract、两份 replay 原始字节以及上述逐操作证据。任何额外/缺失字段、空文件、权限不符、路径替换、SHA 不同、签名错误、缺少 operation，或任一检查不是布尔值 `true` 都失败；`--output` 文件只包含 `ok`、operation count 与私有 artifact count，不输出私有路径或内容，stdout 保持为空。CLI 拒绝时 stderr 也只输出固定公开错误码 `CONTROLLED_RECOVERY_GATE_REJECTED`，不会回显 adapter 字段名、值或路径；详细诊断只能由私有 runner 在不公开的内存/受控日志中自行记录。

逐操作检查必须覆盖：authority/server correlation、remote receipt、journal/pair cross-proof、持久 challenge 重放、evidence id 重放、本地可编辑 JSON/按钮不能解锁、原子持久化返回 false、持久化后进程退出、该类 legacy uncertainty，以及该操作自己的 written/not-written/partial/complete 与错 connection/catalog/pair/profile/draft/operation/payload/recipe/upload identity。顶层还必须有真实 signed-upgrade operator-device test、runtime wiring、challenge consumption 与 atomic persistence 证据。

当前仓库中的 `ControlledRecoveryRules` 与 adapter schema 只是纯验证/状态转换合同；`MainActivity` 尚未接入持久 challenge、evidence-id consumption、operator transport 或原子 write-back/清锁。当前 App 的原 POST 也未把 recovery subject/operation id 送到 reconciliation authority。因此在部署方实现服务器相关性来源和运行时 wiring，并在签名覆盖升级设备上生成上述材料之前，真实 gate 必须失败，不能把公共 selftest 或手填 attestation 写成 `controlledRecovery:true`。

仓库自带纯临时 fixture 自测，用 stub 替代 GitHub、Git 与 Android 元数据工具，不构建也不联网。`release-workflow-selftest.sh` 会先强制运行 controlled-recovery attestation 自测，任一权限、竞态、绑定或输出保护回归都会让整个离线发布自测失败：

```sh
bash -n tools/release.sh tools/publish-release.sh tools/release-workflow-selftest.sh
tools/release-workflow-selftest.sh
```

更新仓库是公开的，所以 release note 和附件也必须通过公开数据审计。不要附带 private catalog、真实 adapter、日志、测试照片或内部变更说明。

## App 更新配置

在 Panel 全局设置中将 `updateOwner` / `updateRepo` 指向发布 APK 的 public repository。App 匿名读取 Release，并校验 `update.json` 中的 package、version、asset 与 SHA-256。

不要给 App 配 GitHub token，也不要使用私有 update repo。需要私有分发时，应实现并审核另一套更新渠道，而不是把 token 写进 catalog 或 APK。

stable 与 beta 的入口不同：stable 匿名读取 GitHub `/releases/latest`，beta 匿名读取固定 `/releases/tags/beta`。因此稳定版发布必须显式成为 latest；beta 发布必须保持固定 `beta` tag 且绝不能成为 latest。

### 重建 tags / Releases 时的旧 App 兼容性

私有只读审计确认，signed v1.0.4、signed v1.0.6 与本次 candidate 的更新代码采用同一外部协议：内置 `update-config.json` 不保存仓库坐标，运行时从 Panel 的 `updateOwner` / `updateRepo` 取得 public repository；stable 请求 GitHub `/releases/latest`，beta 请求 `/releases/tags/beta`，两者按精确名称读取 `update.json`。candidate 的 `updateSource` v1 只允许 owner/repo，并与继续下发给旧 App 的 flat 字段完全一致，不能覆盖 channel/tag/manifest asset。随后按 manifest 中的 `apkAsset` 找 APK，并校验 package、单调递增的 `versionCode` 与 APK SHA-256；candidate 还会绑定 Panel source、manifest、`versionName`、signer 与下载文件身份。该静态审计记录不包含真实坐标，也不能替代下述两个旧 signed 版本分别覆盖安装到 exact candidate 的真机证据。

因此历史重写不要求保留旧 commit OID 或旧 tag object，但必须满足以下运行时契约：

- 不改变 Panel 中现有 public owner/repo 坐标、设备本地 stable/beta channel，以及已安装 APK 的 `update.json`、stable `/latest`、beta tag 路由。旧 Panel 只有 flat owner/repo 时必须保持字段缺失兼容；structured source 只能精确复制 owner/repo，不能引入 tag/asset/default-channel 分支；
- 新稳定 Release 必须 public、非 draft、非 prerelease，并在维护结束时仍是 GitHub 选出的 latest；它必须包含精确命名的 `update.json` 与该文件指定的 APK asset；
- APK 必须保持相同 application ID/package 和 signer，`versionCode` 必须大于现场所有版本（用最新已部署正式 APK作为 `--previous-apk`，并另行核对更早版本 signer continuity），`versionName`、asset 名与 SHA-256 必须和 `update.json` 完全一致；
- 不复用任何旧 Release asset。历史 APK、manifest 或 notes 即使还能下载，也必须重新构建/生成并通过当前 gate；旧版本 Release 如因审计需要重建，应先创建且明确不设为 latest，最后再发布唯一的新稳定 Release；
- 删除 Release 到新稳定 Release 可读之间，旧 App 的更新检查会得到空结果或网络错误并静默返回，不会阻塞表单流程，但这段时间无法更新。应安排短维护窗口，并在删除前用非生产设备验证新的 exact assets；不要通过临时改变 Panel 坐标规避窗口。

重建结束后，必须分别从已安装 signed v1.0.4 与 signed v1.0.6 的非生产设备触发 App 内更新检查、完整下载，并覆盖安装到同一份 exact candidate；不能只测其中一个旧版本，也不能用预装 candidate 代替旧版起点。两个旧版本都必须验证 stable 路由；若现场实际启用了 beta 或其他固定-tag 路由，还必须从每个受影响旧版本逐一验证这些实际启用路由能够下载并覆盖安装到同一 candidate。只用浏览器看到 Release 页面不算兼容性证据。

## 与 Panel / catalog 的发布顺序

已有设备时不要把 App release 作为第一步。先部署向后兼容的新 Panel，再只在 private catalog 中补齐 adapter 与每个 profile 的完整策略：旧 App 仍需的 recipe 文案数组原样保留给旧 App，新 App 的 `recipeOutcomePolicy` 与 submit `outcomePolicy` 必须分别根据各自私有 replay 证据配置，不能互相复用或从 legacy 数组自动生成；只有真正新增的能力关闭。确认旧 App 仍能同步、登录、上传和提交，并证明 candidate App 行为等价后，才用原 signer 发布新 App。未迁移完整策略的 profile 会被新 App 明确阻止提交。设备升级并确认目标 catalog version 后，再从 Panel 逐项开启新功能。完整步骤见 [Panel staged upgrade](./worker-setup.md#10-staged-upgrade-for-an-existing-deployment)。

HTTP transport 只对只读 GET 做有界兼容重试：DNS、连接、超时、TLS 或 502/503/504 等瞬时故障后等待 3 秒再试一次，合计最多两次；每次尝试都重新校验当前 Panel、session 与远程 worker gate。POST、multipart upload、OCR upload 与打印 POST 的 transport 调用仍各执行一次。业务 POST 只有被 Panel-owned adapter/profile classifier 明确拒绝并证明未写入时才可有限重试，且复用完全相同的请求字节、目标绑定与已上传 URL，不重复上传。主流程/独立入口最终提交、按序的上一工序 recipe POST 和手动/自动补打 POST 分别使用 fail-closed journal。最终提交只有自己的 `operations.submit.outcomePolicy` 可以授权明确未写入后的下一次；recipe 只有独立的 `operations.previousSteps.recipeOutcomePolicy.retryableNotWrittenRules` 可以消耗下一次持久累计尝试，`alreadyExistsAcknowledgedRules` 只确认同一 recipe 已完成或去重。Legacy `retryableMessagePatterns` 与 `alreadyExistsMessagePatterns` 仅供旧 App 兼容。

Session-invalid policy 不能释放任何已开始的副作用 POST。Socket 调用后命中 configured session-invalid HTTP 状态、业务码或消息时，App 必须先把对应 journal 持久化为 `UNCERTAIN`；登出或重新登录都不能清 journal、改成明确拒绝或自动重放。这些不确定记录必须经后端侧核对和受控恢复。Printing v1 没有明确未受理 classifier，只有 configured success 可直接完成已发补打；未解决补打仅可由成功的精确绑定状态查询在同一远端上下文确认同一 job/SN 为 adapter-configured printed 后自动收敛。Signed-upgrade 测试必须对四类 POST journal 和 upload replay barrier 的全部状态逐态做进程退出/存储失败注入，并确认不能重复上传/提交/上一工序/补打、串到另一 profile/Panel/catalog，且已确认的终态可安全收尾。

Recipe/final-submit journal 阻止 App 重放不确定 POST，但不会为 backend endpoint 提供幂等性。Multipart upload 使用独立 barrier：首个 socket 前同步保存，开始后整单元重试关闭；不确定结果必须跨重启保留，并阻止后续记录、profile/Panel 切换和 App 安装。若 candidate catalog 开启上一工序 recipe 或整单元网络重试，必须覆盖 backend 已处理请求但客户端超时、前/后/附加/slot/上一工序/独立入口部分上传和本地清锁失败，确认 App 不重放、不续传，也不误把已确认终态降为 failed。

私有 gate 必须分别用脱敏响应 replay 验证 lookup missing code/message、结构化 `recipeOutcomePolicy` 的 retryable/already-exists rules，以及 submit `outcomePolicy` 的 retryable/missing-material rules，并把每个 policy 的 `evidenceSha256` 与其 exact replay 原始字节真实绑定；只通过 64 位格式校验不够。Policy rule 只能读取配置的 code 与 scalar message 路径，同一 rule 的非空 selector 取 AND、rules 之间取 OR；测试必须包含同一 recipe non-success 同时命中 retryable 与 already-exists 的冲突样本，并确认结果为 `UNCERTAIN`。还要证明未分类 lookup 不会被整单元重试或发送创建 POST。

Recipe outcome 发布门只针对 `workflow.previousSteps.enabled:true`、`triggerResultKeys` 非空且 `templates` 非空的实际可执行路径：`recipeMaxAttempts>1` 才要求非空 retryable rules；同一可执行路径仍保留非空 legacy `alreadyExistsMessagePatterns` 时才要求非空 acknowledged rules。关闭、无 trigger 或无 template 的存量配置不触发该门。不能证明时保持上一工序关闭、一次 recipe、一次业务提交和零次网络额外重试。

动态上一工序还需单独验证 capability gate：纯静态 catalog 在没有 resolver map 时仍可发布并运行；只有启用动态项的 profile 才要求 `templateDetail`、referenced resolver、referenced option builder 与全部 source alias。任何 identity 不符、selector 0/多命中、未知引用或超限响应都必须 fail closed，不能回退到标题、数字顺序或 App 内置业务值。

## 发布后验证与回滚

1. 标准 schema-2 Release 重新下载 APK、`update.json` 与 `candidate-manifest.json`；历史 schema-4 Release 只下载 APK 与 `update.json`，远端出现 `candidate-manifest.json` 反而必须停止；
2. 独立计算 SHA-256；标准发布与已下载 manifest 核对，历史发布与本地 mode-`0600` manifest、外部 inventory 及私有 attestation 核对；
3. 用 `apksigner verify --print-certs` 核对 signer；
4. 在已安装上一版的非生产设备上执行覆盖升级；
5. 确认本地草稿 / 设置迁移、Panel 连接、catalog 同步、登录、提交与更新检查；
6. 对断网、超时、登录失效、进程被杀和本地存储失败做故障注入，覆盖最终提交、上一工序 recipe、补打 journal 和 upload replay barrier；逐一验证前/后/附加/slot/上一工序/独立入口的部分上传不会重放或继续下一条，session-invalid 在 socket 后只会留下 `UNCERTAIN` 且重登不清锁，锁存在时不能换 profile/Panel 或安装更新；另验证补打只有同一远端上下文、同一 job/SN 的成功 printed 查询可以清理，其他状态、错误或绑定均保持锁定；
7. 记录 tag、commit、versionCode、signer digest 与验证结果。

Android 不支持用较低 `versionCode` 直接回滚。回滚通常需要发布一个使用相同 signer、但 `versionCode` 更高且代码回退的新版本；提前演练这一流程。回滚到不识别 candidate schema 的旧代码前，必须在设备上证明主流程、独立入口、上一工序、补打 journal 和 upload replay barrier 五个 slot 全部不存在且可读，并且没有相关远程 worker 在执行。任一 slot 存在、无法读取或未解决时，回滚门禁必须 fail closed，不能通过清 App 数据规避。

## 公开历史重写与发布面重建

发现真实部署数据后，仅在最新 commit 删除文件不够。执行远端写操作前先在受控私有存储保存只读 mirror、refs 清单、Release/asset 清单和必要事件证据；任何已暴露 credential 先轮换。若根 commit 起整条历史都不适合公开，优先从已通过扫描的当前公共框架树重建新的最小历史，而不是保留大量难以证明的旧 commit。重建时同时净化 author/committer identity、commit subject/body、annotated tag message、branch/tag name 与 Release/PR 可控 metadata。

维护窗口按以下顺序执行，并在每一阶段保留可回滚的私有证据：

1. 冻结发布；从所有 remote branch/tag 和 GitHub API 重新盘点 refs、Releases/assets、Actions artifacts/caches、issues/PR/comments/attachments、Packages、Pages/deployments、Wiki 与 forks。
2. 在离线 clone 中生成 sanitized history。逐个扫描所有将保留 commit 的 exact Git tree，并把 commit/tag/ref metadata 作为 exact file 扫描；最终 worktree 只能包含通用框架、虚构 example Panel/config 和明确的自定义位置。
3. 先删除不再保留的 Release（从而删除其 assets）与 tags，再 force-update经过审计的 branches；不复用旧 APK、`update.json` 或 notes。额外 branch 若无长期用途应删除，否则必须指向独立扫描通过的新 history。
4. 从 sanitized main 创建经过当前 release gate 的新 tags/Releases。v1.0.0–v1.0.6 全部使用本页限定的 inventory-bound schema-4 连续重建链，并由独立历史发布器按顺序、以 `latest=false` 发布；不得把任一历史 tag 送入标准 schema-2 发布链。若现有 v1.0.7 保留为 latest，历史发布全程必须证明它未改变；若开始时没有 latest，历史发布结束后仍必须没有 latest。唯一的新稳定升级 Release 最后通过标准流程创建并验证 `/releases/latest` 与所有现场固定-tag channel。App 兼容要求见上一节。
5. PR 的 `refs/pull/*` 不能通过普通 branch/tag force-push 删除。关闭 PR、删除 head branch 也不等于 purge；向 GitHub Support 申请移除敏感 cached views/hidden pull refs 和已删除 Release asset 缓存。若平台无法完成，评估删除并重建同名 repository，但仍不能声称已控制外部 clone、搜索缓存或第三方镜像。
6. 重建后从全新位置 mirror-clone，再次扫描 `git ls-remote` 可见的全部 refs、GitHub pull refs、每个 commit tree/metadata、全部 Release assets 与 API 可控 metadata；同时重新核对 Actions、Packages、Pages、Wiki 和 forks。只要 hidden pull ref、旧 object URL 或 CDN asset 仍可匿名读取，公开清理门禁就不能通过。

远端重写、tag/Release 删除和 Support purge 都是独立的受控操作，不由 `tools/release.sh` 或 `tools/publish-release.sh` 自动执行。

## 公开发布检查

- [ ] source、完整 Git 历史、tag 与 Release 不含真实部署数据。
- [ ] `panel/wrangler.local.toml`、`.dev.vars`、signing config 与 keystore 未被跟踪。
- [ ] Node lockfile、Gradle wrapper 与依赖变更已审阅。
- [ ] Panel / Android 测试和非生产端到端测试通过。
- [ ] versionCode 递增，package 与 signer 连续。
- [ ] Candidate manifest 绑定的 source、APK/update/notes/previous APK hash 与重新计算结果一致。
- [ ] APK ZIP-entry manifest、第三方 provenance profile、runtime dependency lock 与构建输入精确绑定；来源 verifier 已机械验证 AAR / nested class / baseline / compiled output / APK DEX string 全链，fresh report SHA 与全部计数等于 candidate；release profile 已单独审阅，app namespace DEX 保持严格扫描。
- [ ] `update.json`、release notes 与 candidate manifest 都在发布现场用同一 scanner/policy 做 exact-file scan；input/report binding 与上传字节一致。
- [ ] APK 与 `update.json` SHA-256 匹配。
- [ ] GitHub API 已机械确认 source repository 为 public；稳定 Release 已显式设为 latest，并在创建后复核 tag、draft/prerelease 状态、精确三项资产及重新下载字节的 SHA-256。
- [ ] Release note 与附件适合公开。
- [ ] Reviewed deployment evidence 已选择唯一 authority：GitHub 分支绑定 private repository + exact commit；R2 分支通过 remote Wrangler 确认 bucket 无 public URL/domain，绑定 current pointer exact bytes/state/snapshot，并通过验证窗口前后 pointer byte recheck。没有把该检查写成 ETag binding，也未把 stale GitHub baseline 当成 current；Release 未包含 filled Panel/adapter export。
- [ ] 正式部署已设置高熵 `CATALOG_READ_KEY`，并实测匿名和错误 Bearer 访问 `/catalog/form-profiles.json`、`/catalog/manifest`、`/api/config`、`/api/profiles`、`/api/panel-config`、`/api/runtime-provenance`、`/api/notify` 全部返回 `401`。缺少任一证据即停止发布。
- [ ] Mode-`0600` config/catalog evidence 与 authenticated live config/catalog 原始字节一致；live manifest 与 authority manifest 原始字节一致，其 version、catalog SHA-256、same-origin profiles URL 与最低 App version 精确绑定同一 pair。Optional `panel-settings.json` 的 present/absent 状态来自 authority 证据，不来自 App route。
- [ ] Panel 通过带 `CF_VERSION_METADATA` 的 clean source-tag 入口部署；authenticated live runtime provenance 精确匹配审阅的 source commit，并另有 exact bundle/build attestation。匿名 provenance route 返回 `401`。
- [ ] 新 Panel → private config → 旧 App compatibility → 新 App 的顺序已验证，新增 workflow 尚未提前启用。
- [ ] 每台旧 App 已在目标 revision 发布后彻底退出并重启，设备侧证据确认 config/catalog 与 cache proof 精确匹配；签名覆盖升级验证了预热后离线恢复，proof 缺失/错误时遗留草稿保持锁定且不向错误 Panel 发送。
- [ ] 首次从 signed v1.0.4/v1.0.6 覆盖安装前，旧 App 已完全停止且没有相机结果待返回；逐台读取 `settings` 并证明 `pending_a_step_photo_path`、`pending_a_step_photo_seq`、`pending_a_step_entry_photo_path` 三个槽位均不存在。任一槽位存在或不可读时已停止发布并使用兼容旧流程恢复，未手工删除不确定状态。
- [ ] 首次无 v2 binding 覆盖安装已验证旧 credential 不会被读取或发送、必须重新登录一次且不会删除草稿/队列/ledger/待处理照片；同 realm 后续更新保持 v2 登录，realm 变化立即失效。
- [ ] 若启用动态上一工序，受控私有 live replay 已覆盖成功、缺失、歧义和超限样本，且旧版/candidate payload 等价；公开仓库中只有虚构 fixture。
- [ ] 实际可执行的上一工序 recipe 已按 `enabled + non-empty triggers + non-empty templates` 判定；请求多次 recipe 时有独立且与 exact replay 绑定的 retryable rules，保留 legacy already-exists 行为时有独立 acknowledged rules，关闭/无 trigger/无 template 的配置未被误要求补 outcome evidence。
- [ ] 若启用独立入口，每个入口的隐藏目标、显示文案、照片、override、identity/payload 和三项关闭 flags 均已用受控私有 replay 验证；公开 seed 仍默认关闭。
- [ ] 若启用独立入口 live provider，主流程已证明在 upload/POST 前完成全部 active provider 的严格 resolve，toggle on/off 与失败样本均 fail closed。
- [ ] 混合版本 conditional alias 的 `perGrade` / `perResult` 深等；任何删除分支均已证明不可达，private report 只含计数。
- [ ] 私有正常流程 replay 精确绑定同一 source/catalog/adapter/App source，已覆盖每个可见 profile、可选结果、照片槽、条件字段、操作字段、物料组及 adapter envelope/item；真实脱敏扫码候选已按每个 profile/identifier role 与旧版逐项比对，计数或 hash 缺失时 fail closed。
- [ ] 私有 live replay 已覆盖 duplicate 成功/未重复/未分类响应，以及上一工序双候选两种延迟反转顺序；打印 replay 已覆盖 printed/failed/ongoing/unknown/missing、延迟、补打和 session 绑定。批末额外复查若与旧版不同，已有 signed live/UI 接受证据或经过审计的兼容开关。
- [ ] signed v1.0.4 与 signed v1.0.6 都完成 Panel-first prewarm，并分别经 stable 及现场实际启用的 beta/固定-tag 路由覆盖安装到同一 exact candidate；candidate catalog 显式保留经核对的可见/隐藏 profile 数，旧 raw catalog 缺少 visibility 字段时的 fallback 差异不能作为等价证据。
- [ ] 主流程/独立入口最终提交、上一工序 recipe、补打 journal 和 upload replay barrier 已完成编译、单元测试和设备逐态故障注入；不确定结果不会重放、继续下一条、换 profile/Panel 或安装更新，主/独立入口、上一工序和 upload 有受控后端核对/恢复 runbook，且补打只在同一远端上下文、同一 job/SN 的成功 configured-printed 查询后自动收敛。
- [ ] 高 `versionCode` 旧代码回滚前已在设备证明五个远程副作用 slot 全部不存在/可读且无远程 worker；存在或无法读取时已验证回滚门禁 fail closed。
- [ ] Private migration 报告为 `releaseReady:true`，不是仅 `ok:true` / `structuralOk:true`；validation、missing、unresolved 与 compatibility review 均已清零/完成。
- [ ] 私有 gate 现场生成的 attestation 精确绑定本候选，且 worktree/history/APK/signed-upgrade/rollback/flow-parity/controlled-recovery checks 全为布尔值 `true`；controlled-recovery 由精确 adapter/Panel pair/contract/replay/device 证据生成，不是手填结论。
- [ ] 全部 branch/tag/pull refs、commit/tag/ref metadata、Releases/assets、Actions、PR attachments、Packages、Pages、Wiki 与 forks 已在 authenticated 与匿名视角复核；任何无法删除的 pull ref/cache 已由 GitHub Support 处理并重新验证。
- [ ] 更新、失败回退和高 versionCode 回滚方案已验证。
