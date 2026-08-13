#!/usr/bin/env bash

set -euo pipefail
set +x
umask 077

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)"
SOURCE_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd -P)"

die() {
  printf 'private-release-chain-selftest: %s\n' "$*" >&2
  exit 1
}

for command in bash git jq node shasum sort stat tee; do
  command -v "${command}" >/dev/null 2>&1 || die "required command not found: ${command}"
done

REAL_GIT="$(command -v git)"
REAL_NODE="$(command -v node)"
REAL_SHASUM="$(command -v shasum)"
REAL_STAT="$(command -v stat)"
ORIGINAL_PATH="${PATH}"

FIXTURE_ROOT="$(mktemp -d "${TMPDIR:-/tmp}/autoform-private-chain-selftest.XXXXXX")"
FIXTURE_ROOT="$(cd "${FIXTURE_ROOT}" && pwd -P)"
cleanup() {
  local status=$?
  trap - EXIT HUP INT TERM
  rm -rf "${FIXTURE_ROOT}"
  exit "${status}"
}
trap cleanup EXIT HUP INT TERM

REPO_DIR="${FIXTURE_ROOT}/repo"
TOOLS_DIR="${REPO_DIR}/tools"
PANEL_DIR="${REPO_DIR}/panel"
BIN_DIR="${FIXTURE_ROOT}/bin"
CANDIDATE_DIR="${FIXTURE_ROOT}/candidate"
EVIDENCE_DIR="${FIXTURE_ROOT}/evidence"
REPORT_DIR="${FIXTURE_ROOT}/reports"
STATE_DIR="${FIXTURE_ROOT}/state"
CHAIN_TEMP_PARENT="${FIXTURE_ROOT}/temp-parent"
UNTRUSTED_TEMP_PARENT="${FIXTURE_ROOT}/untrusted-temp-parent"
mkdir -p "${TOOLS_DIR}" "${PANEL_DIR}" "${BIN_DIR}" "${CANDIDATE_DIR}" \
  "${EVIDENCE_DIR}" "${REPORT_DIR}" "${STATE_DIR}" \
  "${CHAIN_TEMP_PARENT}" "${UNTRUSTED_TEMP_PARENT}"
chmod 700 "${CHAIN_TEMP_PARENT}"
chmod 1777 "${UNTRUSTED_TEMP_PARENT}"

sha256_file() {
  "${REAL_SHASUM}" -a 256 "$1" | awk '{ print $1 }'
}

copy_source_file() {
  local relative="$1"
  local source="${SOURCE_ROOT}/${relative}"
  local target="${REPO_DIR}/${relative}"
  [[ -f "${source}" && ! -L "${source}" ]] || die "missing source fixture: ${relative}"
  mkdir -p "$(dirname "${target}")"
  cp "${source}" "${target}"
  [[ "$(sha256_file "${source}")" == "$(sha256_file "${target}")" ]] || \
    die "copied source fixture changed: ${relative}"
}

for relative in \
  tools/publish-release.sh \
  tools/public-surface-audit.mjs \
  tools/normalize-github-releases-for-audit.mjs \
  tools/apk-third-party-components.json \
  tools/android-runtime-dependencies.lock.json \
  tools/verify-apk-third-party-sources.mjs \
  tools/verify-private-release-evidence.mjs \
  panel/package-lock.json; do
  copy_source_file "${relative}"
done

printf '/panel/node_modules/\n' > "${REPO_DIR}/.gitignore"

GATE_PROGRAM="${FIXTURE_ROOT}/private-release-gate.cjs"
GATE_LOG="${STATE_DIR}/gate.log"
GATE_ENV_CAPTURE="${STATE_DIR}/gate-env.json"
cat > "${GATE_PROGRAM}" <<'EOF'
#!/usr/bin/env node

const fs = require("node:fs");
const crypto = require("node:crypto");
const path = require("node:path");

function required(name) {
  const value = process.env[name];
  if (typeof value !== "string" || value.length === 0) {
    throw new Error(`missing ${name}`);
  }
  return value;
}

function integer(name) {
  const value = Number(required(name));
  if (!Number.isSafeInteger(value)) throw new Error(`invalid ${name}`);
  return value;
}

function boolean(name) {
  const value = required(name);
  if (value === "true") return true;
  if (value === "false") return false;
  throw new Error(`invalid ${name}`);
}

const effectiveUid = process.geteuid();
const snapshotDirectory = fs.lstatSync(__dirname);
const snapshotFile = fs.lstatSync(__filename);
const snapshotSha256 = crypto.createHash("sha256")
  .update(fs.readFileSync(__filename)).digest("hex");
if (!snapshotDirectory.isDirectory() || snapshotDirectory.isSymbolicLink()
    || snapshotDirectory.uid !== effectiveUid
    || (snapshotDirectory.mode & 0o777) !== 0o700
    || path.resolve(process.cwd()) !== path.resolve(__dirname)
    || !snapshotFile.isFile() || snapshotFile.isSymbolicLink()
    || snapshotFile.uid !== effectiveUid || snapshotFile.nlink !== 1
    || (snapshotFile.mode & 0o777) !== 0o500
    || snapshotSha256 !== required("AUTOFORM_RELEASE_PRIVATE_GATE_SHA256")) {
  throw new Error("publisher did not provide the exact owner-only gate snapshot contract");
}

fs.appendFileSync(required("AUTOFORM_CHAIN_GATE_LOG"), "called\n", { mode: 0o600 });
fs.appendFileSync(required("AUTOFORM_CHAIN_EVENT_LOG"), "gate\n", { mode: 0o600 });
const maliciousGate = '#!/bin/sh\nprintf "executed\\n" > "$AUTOFORM_CHAIN_MALICIOUS_MARKER"\nexit 2\n';
if (process.env.AUTOFORM_CHAIN_REPLACE_SNAPSHOT_DIRECTORY === "true") {
  const movedDirectory = `${__dirname}.original`;
  fs.renameSync(__dirname, movedDirectory);
  fs.mkdirSync(__dirname, { mode: 0o700 });
  const replacementGate = path.join(__dirname, path.basename(__filename));
  fs.writeFileSync(replacementGate, maliciousGate, { mode: 0o500 });
  fs.chmodSync(replacementGate, 0o500);
}
if (process.env.AUTOFORM_CHAIN_MUTATE_ORIGINAL_GATE === "true") {
  const originalGate = required("AUTOFORM_CHAIN_ORIGINAL_GATE_PATH");
  const replacementGate = `${originalGate}.replacement`;
  fs.writeFileSync(replacementGate, maliciousGate, { mode: 0o700 });
  fs.chmodSync(replacementGate, 0o700);
  fs.renameSync(replacementGate, originalGate);
}
const privateEnvironment = Object.fromEntries(Object.entries(process.env)
  .filter(([name]) => name.startsWith("AUTOFORM_RELEASE_PRIVATE_"))
  .sort(([left], [right]) => left.localeCompare(right)));
fs.writeFileSync(required("AUTOFORM_CHAIN_GATE_ENV_CAPTURE"),
  `${JSON.stringify(privateEnvironment)}\n`, { mode: 0o600 });

const attestation = {
  schemaVersion: 5,
  releaseReady: true,
  bindings: {
    candidateManifestSha256: required("AUTOFORM_RELEASE_CANDIDATE_MANIFEST_SHA256"),
    apkSha256: required("AUTOFORM_RELEASE_APK_SHA256"),
    updateSha256: required("AUTOFORM_RELEASE_UPDATE_SHA256"),
    notesSha256: required("AUTOFORM_RELEASE_NOTES_SHA256"),
    previousApkSha256: required("AUTOFORM_RELEASE_PREVIOUS_APK_SHA256"),
    sourceCommit: required("AUTOFORM_RELEASE_SOURCE_COMMIT"),
    publicAudit: {
      scannerSha256: required("AUTOFORM_RELEASE_PUBLIC_AUDIT_SCANNER_SHA256"),
      policySha256: required("AUTOFORM_RELEASE_PUBLIC_AUDIT_POLICY_SHA256"),
      sourceTree: {
        gitTreeOid: required("AUTOFORM_RELEASE_PUBLIC_TREE_OID"),
        inputSha256: required("AUTOFORM_RELEASE_PUBLIC_TREE_INPUT_SHA256"),
        reportSha256: required("AUTOFORM_RELEASE_PUBLIC_TREE_REPORT_SHA256")
      },
      worktree: {
        inputSha256: required("AUTOFORM_RELEASE_PUBLIC_WORKTREE_INPUT_SHA256"),
        reportSha256: required("AUTOFORM_RELEASE_PUBLIC_WORKTREE_REPORT_SHA256")
      },
      apk: {
        inputSha256: required("AUTOFORM_RELEASE_PUBLIC_APK_INPUT_SHA256"),
        reportSha256: required("AUTOFORM_RELEASE_PUBLIC_APK_REPORT_SHA256"),
        zipEntryManifestSha256:
          required("AUTOFORM_RELEASE_PUBLIC_APK_ZIP_ENTRY_MANIFEST_SHA256")
      },
      thirdPartyProvenance: {
        manifestFile: "tools/apk-third-party-components.json",
        manifestSha256: required("AUTOFORM_RELEASE_APK_THIRD_PARTY_POLICY_SHA256"),
        runtimeLockFile: "tools/android-runtime-dependencies.lock.json",
        runtimeLockSha256: required("AUTOFORM_RELEASE_ANDROID_RUNTIME_LOCK_SHA256"),
        profileId: required("AUTOFORM_RELEASE_APK_THIRD_PARTY_PROFILE_ID"),
        matchedEntryCount: integer("AUTOFORM_RELEASE_APK_THIRD_PARTY_MATCHED_ENTRY_COUNT"),
        applicationDexStrict: true,
        sourceVerifierFile: "tools/verify-apk-third-party-sources.mjs",
        sourceVerifierSha256: required("AUTOFORM_RELEASE_APK_SOURCE_VERIFIER_SHA256"),
        sourceReportSha256: required("AUTOFORM_RELEASE_APK_SOURCE_REPORT_SHA256"),
        sourceArtifactCount: integer("AUTOFORM_RELEASE_APK_SOURCE_ARTIFACT_COUNT"),
        sourceEntryCount: integer("AUTOFORM_RELEASE_APK_SOURCE_ENTRY_COUNT"),
        mergedSourceCount: integer("AUTOFORM_RELEASE_APK_MERGED_SOURCE_COUNT"),
        compiledOutputCount: integer("AUTOFORM_RELEASE_APK_COMPILED_OUTPUT_COUNT"),
        dexSourceArtifactCount: integer("AUTOFORM_RELEASE_APK_DEX_SOURCE_ARTIFACT_COUNT"),
        dexSourceEntryCount: integer("AUTOFORM_RELEASE_APK_DEX_SOURCE_ENTRY_COUNT"),
        declaredDexStringCount: integer("AUTOFORM_RELEASE_APK_DECLARED_DEX_STRING_COUNT"),
        sourceMatchedDexStringCount:
          integer("AUTOFORM_RELEASE_APK_SOURCE_MATCHED_DEX_STRING_COUNT"),
        apkMatchedDexStringCount: integer("AUTOFORM_RELEASE_APK_MATCHED_DEX_STRING_COUNT")
      },
      releaseMetadata: {
        update: {
          inputSha256: required("AUTOFORM_RELEASE_PUBLIC_UPDATE_INPUT_SHA256"),
          reportSha256: required("AUTOFORM_RELEASE_PUBLIC_UPDATE_REPORT_SHA256")
        },
        notes: {
          inputSha256: required("AUTOFORM_RELEASE_PUBLIC_NOTES_INPUT_SHA256"),
          reportSha256: required("AUTOFORM_RELEASE_PUBLIC_NOTES_REPORT_SHA256")
        },
        candidateManifest: {
          inputSha256: required("AUTOFORM_RELEASE_PUBLIC_MANIFEST_INPUT_SHA256"),
          reportSha256: required("AUTOFORM_RELEASE_PUBLIC_MANIFEST_REPORT_SHA256")
        }
      },
      publicHistory: {
        sourceCommitObject: {
          inputSha256: required("AUTOFORM_RELEASE_PUBLIC_COMMIT_OBJECT_INPUT_SHA256"),
          reportSha256: required("AUTOFORM_RELEASE_PUBLIC_COMMIT_OBJECT_REPORT_SHA256")
        },
        remoteRefs: {
          inputSha256: required("AUTOFORM_RELEASE_PUBLIC_REMOTE_REFS_INPUT_SHA256"),
          reportSha256: required("AUTOFORM_RELEASE_PUBLIC_REMOTE_REFS_REPORT_SHA256")
        },
        refsApi: {
          inputSha256: required("AUTOFORM_RELEASE_PUBLIC_REF_API_INPUT_SHA256"),
          reportSha256: required("AUTOFORM_RELEASE_PUBLIC_REF_API_REPORT_SHA256")
        },
        releases: {
          inputSha256: required("AUTOFORM_RELEASE_PUBLIC_RELEASES_INPUT_SHA256"),
          reportSha256: required("AUTOFORM_RELEASE_PUBLIC_RELEASES_REPORT_SHA256")
        },
        refIdentitySha256: required("AUTOFORM_RELEASE_PUBLIC_REF_IDENTITY_SHA256"),
        pullRefIdentitySha256:
          required("AUTOFORM_RELEASE_PUBLIC_PULL_REF_IDENTITY_SHA256"),
        remoteRefsRawSnapshotSha256:
          required("AUTOFORM_RELEASE_PUBLIC_REMOTE_REFS_RAW_SNAPSHOT_SHA256"),
        refApiSnapshotSha256:
          required("AUTOFORM_RELEASE_PUBLIC_REF_API_SNAPSHOT_SHA256"),
        releaseApiSnapshotSha256:
          required("AUTOFORM_RELEASE_PUBLIC_RELEASE_API_SNAPSHOT_SHA256"),
        repositoryBindingSha256:
          required("AUTOFORM_RELEASE_PUBLIC_REPOSITORY_BINDING_SHA256"),
        metadataBindingSha256:
          required("AUTOFORM_RELEASE_PUBLIC_METADATA_BINDING_SHA256")
      }
    },
    privateEvidence: {
      verifierFile: "tools/verify-private-release-evidence.mjs",
      verifierSha256: required("AUTOFORM_RELEASE_PRIVATE_EVIDENCE_VERIFIER_SHA256"),
      verificationReportSha256:
        required("AUTOFORM_RELEASE_PRIVATE_EVIDENCE_REPORT_SHA256"),
      migrationReportSha256:
        required("AUTOFORM_RELEASE_PRIVATE_MIGRATION_REPORT_SHA256"),
      panelConfigSha256: required("AUTOFORM_RELEASE_PRIVATE_PANEL_CONFIG_SHA256"),
      panelCatalogSha256: required("AUTOFORM_RELEASE_PRIVATE_PANEL_CATALOG_SHA256"),
      panelPairSha256: required("AUTOFORM_RELEASE_PRIVATE_PANEL_PAIR_SHA256"),
      deploymentEvidenceSha256:
        required("AUTOFORM_RELEASE_PRIVATE_DEPLOYMENT_EVIDENCE_SHA256"),
      catalogVersion: integer("AUTOFORM_RELEASE_PRIVATE_CATALOG_VERSION"),
      panelWorkerVersionId: required("AUTOFORM_RELEASE_PRIVATE_PANEL_WORKER_VERSION_ID"),
      catalogAuthorityType:
        required("AUTOFORM_RELEASE_PRIVATE_CATALOG_AUTHORITY_TYPE"),
      catalogAuthorityIdentitySha256:
        required("AUTOFORM_RELEASE_PRIVATE_CATALOG_AUTHORITY_IDENTITY_SHA256"),
      catalogAuthorityRevision:
        required("AUTOFORM_RELEASE_PRIVATE_CATALOG_AUTHORITY_REVISION"),
      catalogAuthorityWorkerBindingSha256:
        required("AUTOFORM_RELEASE_PRIVATE_CATALOG_AUTHORITY_WORKER_BINDING_SHA256"),
      catalogManifestSha256:
        required("AUTOFORM_RELEASE_PRIVATE_CATALOG_MANIFEST_SHA256"),
      panelSettingsPresent:
        boolean("AUTOFORM_RELEASE_PRIVATE_PANEL_SETTINGS_PRESENT"),
      panelSettingsSha256:
        required("AUTOFORM_RELEASE_PRIVATE_PANEL_SETTINGS_SHA256"),
      privateGateSha256: required("AUTOFORM_RELEASE_PRIVATE_GATE_SHA256")
    }
  },
  checks: {
    privateUpgradeEvidence: true,
    privateDeployment: true,
    publicWorktree: true,
    publicHistory: true,
    candidateApk: true,
    signedCurrentUpgrade: true,
    freshInstall: true,
    liveCatalogCompatibility: true,
    automaticUpdateProtocol: true,
    productionMutationFree: true
  }
};

if (process.env.AUTOFORM_CHAIN_TAMPER_ATTESTATION === "true") {
  attestation.bindings.privateEvidence.panelSettingsSha256 = "0".repeat(64);
}
fs.writeFileSync(required("AUTOFORM_RELEASE_ATTESTATION_OUT"),
  `${JSON.stringify(attestation)}\n`, { mode: 0o600 });
EOF
chmod 700 "${GATE_PROGRAM}"
GATE_SHA256="$(sha256_file "${GATE_PROGRAM}")"
GATE_PROGRAM_BACKUP="${STATE_DIR}/private-release-gate.backup"
cp "${GATE_PROGRAM}" "${GATE_PROGRAM_BACKUP}"
chmod 600 "${GATE_PROGRAM_BACKUP}"

jq -nS --arg gateSha256 "${GATE_SHA256}" \
  '{schemaVersion:1,enabled:true,gateSha256:$gateSha256}' \
  > "${TOOLS_DIR}/private-release-gate-policy.json"

(
  cd "${REPO_DIR}"
  "${REAL_GIT}" init -q -b main
  "${REAL_GIT}" config user.name "Autoform Chain Selftest"
  "${REAL_GIT}" config user.email "autoform-chain-selftest@example.invalid"
  "${REAL_GIT}" add .
  "${REAL_GIT}" commit -q -m "fixture release source"
  "${REAL_GIT}" remote add origin https://github.com/example/autoform-kit.git
)

SOURCE_COMMIT="$("${REAL_GIT}" -C "${REPO_DIR}" rev-parse HEAD)"
TREE_OID="$("${REAL_GIT}" -C "${REPO_DIR}" rev-parse "${SOURCE_COMMIT}^{tree}")"
[[ "${SOURCE_COMMIT}" =~ ^[0-9a-f]{40}$ ]] || die "fixture Git commit is not SHA-1"
[[ -z "$("${REAL_GIT}" -C "${REPO_DIR}" status --porcelain --untracked-files=normal)" ]] || \
  die "fixture repository is not clean before installing ignored Wrangler"

WRANGLER_VERSION="$(jq -er '.packages["node_modules/wrangler"].version' \
  "${PANEL_DIR}/package-lock.json")"
WRANGLER_INTEGRITY="$(jq -er '.packages["node_modules/wrangler"].integrity' \
  "${PANEL_DIR}/package-lock.json")"
[[ "${WRANGLER_INTEGRITY}" == sha512-* ]] || die "fixture Wrangler lock is invalid"
mkdir -p "${PANEL_DIR}/node_modules/wrangler/bin"
jq -nS --arg version "${WRANGLER_VERSION}" \
  '{name:"wrangler",version:$version,bin:{wrangler:"bin/wrangler.js"}}' \
  > "${PANEL_DIR}/node_modules/wrangler/package.json"
cat > "${PANEL_DIR}/node_modules/wrangler/bin/wrangler.js" <<'EOF'
#!/usr/bin/env node
const packageJson = require("../package.json");
const args = process.argv.slice(2);
if (args.length === 1 && args[0] === "--version") {
  process.stdout.write(`${packageJson.version}\n`);
  process.exit(0);
}
if (args.length === 6
    && args[0] === "versions" && args[1] === "view"
    && args[2] === "11111111-2222-3333-4444-555555555555"
    && args[3] === "--name" && args[4] === "fixture-panel" && args[5] === "--json") {
  process.stdout.write(`${JSON.stringify({
    id: "11111111-2222-3333-4444-555555555555",
    resources: {
      bindings: [{
        name: "GITHUB_REPO",
        type: "plain_text",
        text: "private-example/forms"
      }, {
        name: "GITHUB_BRANCH",
        type: "plain_text",
        text: "main"
      }]
    }
  })}\n`);
  process.exit(0);
}
process.stderr.write(`unexpected fixture Wrangler command: ${args.join(" ")}\n`);
process.exit(2);
EOF
chmod 700 "${PANEL_DIR}/node_modules/wrangler/bin/wrangler.js"
[[ -z "$("${REAL_GIT}" -C "${REPO_DIR}" status --porcelain --untracked-files=normal)" ]] || \
  die "ignored fixture Wrangler dirtied the repository"

PREVIOUS_APK="${FIXTURE_ROOT}/previous.apk"
PRIVATE_WORDLIST="${FIXTURE_ROOT}/private-wordlist.json"
PRIVATE_MIGRATION_REPORT="${EVIDENCE_DIR}/private-migration-report.json"
PANEL_CONFIG_EVIDENCE="${EVIDENCE_DIR}/panel-config.json"
PANEL_CATALOG_EVIDENCE="${EVIDENCE_DIR}/panel-catalog.json"
PANEL_MANIFEST_EVIDENCE="${EVIDENCE_DIR}/panel-manifest.json"
PRIVATE_DEPLOYMENT_EVIDENCE="${EVIDENCE_DIR}/private-deployment-evidence.json"
RUNTIME_PROVENANCE_EVIDENCE="${EVIDENCE_DIR}/runtime-provenance.json"

TREE_REPORT="${REPORT_DIR}/tree-report.json"
WORKTREE_REPORT="${REPORT_DIR}/worktree-report.json"
APK_REPORT="${REPORT_DIR}/apk-report.json"
UPDATE_REPORT="${REPORT_DIR}/update-report.json"
NOTES_REPORT="${REPORT_DIR}/notes-report.json"
MANIFEST_REPORT="${REPORT_DIR}/manifest-report.json"
SOURCE_PROVENANCE_REPORT="${REPORT_DIR}/source-provenance-report.json"
HISTORY_COMMIT_REPORT="${REPORT_DIR}/source-commit-object-report.json"
HISTORY_REMOTE_REFS_REPORT="${REPORT_DIR}/remote-refs-report.json"
HISTORY_REF_API_REPORT="${REPORT_DIR}/ref-api-report.json"
HISTORY_RELEASES_REPORT="${REPORT_DIR}/releases-report.json"
HISTORY_COMMIT_OBJECT_FIXTURE="${STATE_DIR}/source-commit-object.fixture"
HISTORY_REMOTE_REFS_RAW_FIXTURE="${STATE_DIR}/remote-refs.raw.fixture"
HISTORY_REMOTE_REFS_FIXTURE="${STATE_DIR}/remote-refs.fixture"
HISTORY_BRANCHES_API_FIXTURE="${STATE_DIR}/branches-api.fixture.json"
HISTORY_TAGS_API_FIXTURE="${STATE_DIR}/tags-api.fixture.json"
HISTORY_REF_API_FIXTURE="${STATE_DIR}/ref-api.fixture.json"
HISTORY_RELEASES_API_FIXTURE="${STATE_DIR}/releases-api.fixture.json"
HISTORY_RELEASES_FIXTURE="${STATE_DIR}/releases.fixture.json"
HISTORY_REPOSITORY_API_FIXTURE="${STATE_DIR}/repository-api.fixture.json"

SIGNER_SHA256="bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
PACKAGE_NAME="com.example.autoform"
VERSION_NAME="1.2.3"
VERSION_CODE="123"
PREVIOUS_VERSION_NAME="1.2.2"
PREVIOUS_VERSION_CODE="122"
APK_FILE="autoform-kit-${VERSION_NAME}.apk"
APK_PATH="${CANDIDATE_DIR}/${APK_FILE}"
UPDATE_PATH="${CANDIDATE_DIR}/update.json"
NOTES_PATH="${CANDIDATE_DIR}/release-notes.txt"
MANIFEST_PATH="${CANDIDATE_DIR}/candidate-manifest.json"
POLICY_SHA256="dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd"
TREE_INPUT_SHA256="eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee"
WORKTREE_INPUT_SHA256="1111111111111111111111111111111111111111111111111111111111111111"
APK_ZIP_INPUT_SHA256="2222222222222222222222222222222222222222222222222222222222222222"
CATALOG_COMMIT="dddddddddddddddddddddddddddddddddddddddd"
WORKER_VERSION_ID="11111111-2222-3333-4444-555555555555"
CATALOG_READ_KEY="fictional-selftest-read-key-0000000000000000"

printf '[{"term":"fictional-private-selftest-marker","matchMode":"literal"}]\n' \
  > "${PRIVATE_WORDLIST}"
printf 'fixture candidate apk bytes\n' > "${APK_PATH}"
printf 'fixture previous apk bytes\n' > "${PREVIOUS_APK}"
printf 'Fictional release notes\n' > "${NOTES_PATH}"
APK_SHA256="$(sha256_file "${APK_PATH}")"
PREVIOUS_APK_SHA256="$(sha256_file "${PREVIOUS_APK}")"
PUBLIC_AUDIT_SCANNER_SHA256="$(sha256_file "${TOOLS_DIR}/public-surface-audit.mjs")"
PUBLIC_SOURCE_VERIFIER_SHA256="$(sha256_file \
  "${TOOLS_DIR}/verify-apk-third-party-sources.mjs")"
PUBLIC_THIRD_PARTY_POLICY_SHA256="$(sha256_file \
  "${TOOLS_DIR}/apk-third-party-components.json")"
PUBLIC_RUNTIME_LOCK_SHA256="$(sha256_file \
  "${TOOLS_DIR}/android-runtime-dependencies.lock.json")"

"${REAL_GIT}" -C "${REPO_DIR}" cat-file commit "${SOURCE_COMMIT}" \
  > "${HISTORY_COMMIT_OBJECT_FIXTURE}"
printf '%s\tHEAD\n%s\trefs/heads/main\n%s\trefs/pull/1/head\n%s\trefs/pull/1/merge\n' \
  "${SOURCE_COMMIT}" "${SOURCE_COMMIT}" "${SOURCE_COMMIT}" "${SOURCE_COMMIT}" \
  > "${HISTORY_REMOTE_REFS_RAW_FIXTURE}"
printf '[[]]\n' > "${HISTORY_TAGS_API_FIXTURE}"
printf '[[]]\n' > "${HISTORY_RELEASES_API_FIXTURE}"
jq -nS '{
  id: 901,
  node_id: "R_fixture_901",
  full_name: "example/autoform-kit",
  visibility: "public",
  private: false,
  description: "Generic form framework",
  homepage: "https://example.invalid/autoform-kit",
  topics: ["android", "forms"],
  default_branch: "main"
}' > "${HISTORY_REPOSITORY_API_FIXTURE}"
jq -nS --arg sha "${SOURCE_COMMIT}" '[[{name:"main", protected:false,
  commit:{sha:$sha,url:("https://api.github.com/repos/example/autoform-kit/commits/"+$sha)}}]]' \
  > "${HISTORY_BRANCHES_API_FIXTURE}"
"${REAL_NODE}" "${TOOLS_DIR}/normalize-github-releases-for-audit.mjs" \
  --remote-refs-input "${HISTORY_REMOTE_REFS_RAW_FIXTURE}" \
  --repo example/autoform-kit \
  --repository-input "${HISTORY_REPOSITORY_API_FIXTURE}" \
  > "${HISTORY_REMOTE_REFS_FIXTURE}"
"${REAL_NODE}" "${TOOLS_DIR}/normalize-github-releases-for-audit.mjs" \
  --branches-input "${HISTORY_BRANCHES_API_FIXTURE}" \
  --tags-input "${HISTORY_TAGS_API_FIXTURE}" \
  --repo example/autoform-kit \
  --repository-input "${HISTORY_REPOSITORY_API_FIXTURE}" \
  > "${HISTORY_REF_API_FIXTURE}"
"${REAL_NODE}" "${TOOLS_DIR}/normalize-github-releases-for-audit.mjs" \
  --repo example/autoform-kit \
  --repository-input "${HISTORY_REPOSITORY_API_FIXTURE}" \
  --input "${HISTORY_RELEASES_API_FIXTURE}" > "${HISTORY_RELEASES_FIXTURE}"

write_source_provenance_report() {
  local output="$1"
  local base="${output}.base"
  local canonical report_sha
  jq -nS \
    --arg verifier "${PUBLIC_SOURCE_VERIFIER_SHA256}" \
    --arg policy "${PUBLIC_THIRD_PARTY_POLICY_SHA256}" \
    --arg apk "${APK_SHA256}" \
    '{
      schemaVersion: 1,
      passed: true,
      verifierSha256: $verifier,
      policySha256: $policy,
      apkSha256: $apk,
      profileId: "fixture-profile",
      sourceArtifactCount: 1,
      sourceEntryCount: 1,
      mergedSourceCount: 1,
      compiledOutputCount: 2,
      dexSourceArtifactCount: 1,
      dexSourceEntryCount: 1,
      declaredDexStringCount: 1,
      sourceMatchedDexStringCount: 1,
      apkMatchedDexStringCount: 1
    }' > "${base}"
  canonical="$(jq -cS . "${base}")"
  report_sha="$(printf '%s' "${canonical}" | sha256_file /dev/stdin)"
  jq -S --arg reportSha256 "${report_sha}" \
    '. + {reportSha256: $reportSha256}' "${base}" > "${output}"
  rm -f "${base}"
}

write_audit_report() {
  local mode="$1"
  local input_sha="$2"
  local output="$3"
  local base="${output}.base"
  local canonical report_sha applied=false profile=null matched=0
  case "${mode}" in
    git-tree)
      jq -nS \
        --arg scanner "${PUBLIC_AUDIT_SCANNER_SHA256}" \
        --arg policy "${POLICY_SHA256}" \
        --arg input "${input_sha}" \
        --arg tree "${TREE_OID}" \
        '{
          schemaVersion:1,
          scannerSha256:$scanner,
          policySha256:$policy,
          input:{
            mode:"git-tree",
            selection:"exact-git-tree",
            gitTreeOid:$tree,
            sha256:$input,
            entryCount:8
          },
          privatePolicy:{applied:true,wordlistCount:1,termCount:1},
          summary:{passed:true,findingCount:0},
          findings:[]
        }' > "${base}"
      ;;
    worktree)
      jq -nS \
        --arg scanner "${PUBLIC_AUDIT_SCANNER_SHA256}" \
        --arg policy "${POLICY_SHA256}" \
        --arg input "${input_sha}" \
        '{
          schemaVersion:1,
          scannerSha256:$scanner,
          policySha256:$policy,
          input:{
            mode:"worktree",
            selection:"tracked-and-untracked-nonignored-present-files",
            sha256:$input,
            entryCount:8
          },
          privatePolicy:{applied:true,wordlistCount:1,termCount:1},
          summary:{passed:true,findingCount:0},
          findings:[]
        }' > "${base}"
      ;;
    apk)
      applied=true
      profile='"fixture-profile"'
      matched=1
      jq -nS \
        --arg scanner "${PUBLIC_AUDIT_SCANNER_SHA256}" \
        --arg policy "${POLICY_SHA256}" \
        --arg input "${input_sha}" \
        --arg zipInput "${APK_ZIP_INPUT_SHA256}" \
        '{
          schemaVersion:1,
          scannerSha256:$scanner,
          policySha256:$policy,
          input:{
            mode:"apk",
            selection:"exact-apk-container-and-zip-entries",
            sha256:$input,
            zipEntryManifestSha256:$zipInput,
            entryCount:3
          },
          privatePolicy:{applied:true,wordlistCount:1,termCount:1},
          summary:{passed:true,findingCount:0},
          findings:[]
        }' > "${base}"
      ;;
    file)
      jq -nS \
        --arg scanner "${PUBLIC_AUDIT_SCANNER_SHA256}" \
        --arg policy "${POLICY_SHA256}" \
        --arg input "${input_sha}" \
        '{
          schemaVersion:1,
          scannerSha256:$scanner,
          policySha256:$policy,
          input:{
            mode:"file",
            selection:"exact-regular-file",
            sha256:$input,
            entryCount:1
          },
          privatePolicy:{applied:true,wordlistCount:1,termCount:1},
          summary:{passed:true,findingCount:0},
          findings:[]
        }' > "${base}"
      ;;
    *) die "unsupported audit fixture mode: ${mode}" ;;
  esac
  jq -S \
    --arg manifestSha256 "${PUBLIC_THIRD_PARTY_POLICY_SHA256}" \
    --argjson applied "${applied}" \
    --argjson profileId "${profile}" \
    --argjson matchedEntryCount "${matched}" \
    '. + {thirdPartyPolicy:{
      manifestSha256:$manifestSha256,
      applied:$applied,
      profileId:$profileId,
      matchedEntryCount:$matchedEntryCount,
      applicationDexPolicy:"all application-namespace type descriptors remain strictly scanned"
    }}' "${base}" > "${base}.with-policy"
  mv "${base}.with-policy" "${base}"
  canonical="$(jq -cS . "${base}")"
  report_sha="$(printf '%s' "${canonical}" | sha256_file /dev/stdin)"
  jq -S --arg reportSha256 "${report_sha}" \
    '. + {reportSha256:$reportSha256}' "${base}" > "${output}"
  rm -f "${base}"
}

write_source_provenance_report "${SOURCE_PROVENANCE_REPORT}"
SOURCE_PROVENANCE_REPORT_SHA256="$(jq -er '.reportSha256' "${SOURCE_PROVENANCE_REPORT}")"
write_audit_report git-tree "${TREE_INPUT_SHA256}" "${TREE_REPORT}"
write_audit_report worktree "${WORKTREE_INPUT_SHA256}" "${WORKTREE_REPORT}"
write_audit_report apk "${APK_SHA256}" "${APK_REPORT}"
write_audit_report file "$(sha256_file "${HISTORY_COMMIT_OBJECT_FIXTURE}")" \
  "${HISTORY_COMMIT_REPORT}"
write_audit_report file "$(sha256_file "${HISTORY_REMOTE_REFS_FIXTURE}")" \
  "${HISTORY_REMOTE_REFS_REPORT}"
write_audit_report file "$(sha256_file "${HISTORY_REF_API_FIXTURE}")" \
  "${HISTORY_REF_API_REPORT}"
write_audit_report file "$(sha256_file "${HISTORY_RELEASES_FIXTURE}")" \
  "${HISTORY_RELEASES_REPORT}"
HISTORY_REMOTE_REFS_INPUT_SHA256="$(sha256_file "${HISTORY_REMOTE_REFS_FIXTURE}")"
HISTORY_REF_API_INPUT_SHA256="$(sha256_file "${HISTORY_REF_API_FIXTURE}")"
HISTORY_RELEASES_INPUT_SHA256="$(sha256_file "${HISTORY_RELEASES_FIXTURE}")"
HISTORY_REMOTE_REFS_IDENTITY_SHA256="$(
  jq -er '.refIdentitySha256' "${HISTORY_REMOTE_REFS_FIXTURE}"
)"
HISTORY_REMOTE_REFS_RAW_SNAPSHOT_SHA256="$(
  jq -er '.rawSnapshotSha256' "${HISTORY_REMOTE_REFS_FIXTURE}"
)"
HISTORY_PULL_REF_IDENTITY_SHA256="$(
  jq -er '.pullRefIdentitySha256' "${HISTORY_REMOTE_REFS_FIXTURE}"
)"
HISTORY_REF_API_SNAPSHOT_SHA256="$(
  jq -er '.apiSnapshotSha256' "${HISTORY_REF_API_FIXTURE}"
)"
HISTORY_RELEASE_API_SNAPSHOT_SHA256="$(
  jq -er '.apiSnapshotSha256' "${HISTORY_RELEASES_FIXTURE}"
)"
HISTORY_REPOSITORY_BINDING_SHA256="$(
  jq -er '.repositoryBindingSha256' "${HISTORY_RELEASES_FIXTURE}"
)"
HISTORY_METADATA_BINDING_BASE="$(jq -cnS \
  --arg pullRefs "${HISTORY_PULL_REF_IDENTITY_SHA256}" \
  --arg refsApi "${HISTORY_REF_API_SNAPSHOT_SHA256}" \
  --arg refs "${HISTORY_REMOTE_REFS_IDENTITY_SHA256}" \
  --arg refsRaw "${HISTORY_REMOTE_REFS_RAW_SNAPSHOT_SHA256}" \
  --arg releases "${HISTORY_RELEASE_API_SNAPSHOT_SHA256}" \
  --arg repository "${HISTORY_REPOSITORY_BINDING_SHA256}" \
  '{pullRefIdentitySha256:$pullRefs, refApiSnapshotSha256:$refsApi,
    refIdentitySha256:$refs, remoteRefsRawSnapshotSha256:$refsRaw,
    releaseApiSnapshotSha256:$releases, repositoryBindingSha256:$repository}')"
HISTORY_METADATA_BINDING_SHA256="$(
  printf 'autoform-kit/github-public-metadata-binding/v2\n%s' \
    "${HISTORY_METADATA_BINDING_BASE}" | sha256_file /dev/stdin
)"
TREE_REPORT_SHA256="$(jq -er '.reportSha256' "${TREE_REPORT}")"
WORKTREE_REPORT_SHA256="$(jq -er '.reportSha256' "${WORKTREE_REPORT}")"
APK_REPORT_SHA256="$(jq -er '.reportSha256' "${APK_REPORT}")"

jq -n \
  --arg packageName "${PACKAGE_NAME}" \
  --argjson versionCode "${VERSION_CODE}" \
  --arg versionName "${VERSION_NAME}" \
  --arg apkAsset "${APK_FILE}" \
  --arg sha256 "${APK_SHA256}" \
  --arg notes "Fictional release notes" \
  '{packageName:$packageName,versionCode:$versionCode,versionName:$versionName,
    apkAsset:$apkAsset,sha256:$sha256,notes:$notes}' > "${UPDATE_PATH}"
UPDATE_SHA256="$(sha256_file "${UPDATE_PATH}")"
NOTES_SHA256="$(sha256_file "${NOTES_PATH}")"
write_audit_report file "${UPDATE_SHA256}" "${UPDATE_REPORT}"
write_audit_report file "${NOTES_SHA256}" "${NOTES_REPORT}"
UPDATE_REPORT_SHA256="$(jq -er '.reportSha256' "${UPDATE_REPORT}")"
NOTES_REPORT_SHA256="$(jq -er '.reportSha256' "${NOTES_REPORT}")"

jq -nS \
  --arg sourceCommit "${SOURCE_COMMIT}" \
  --arg packageName "${PACKAGE_NAME}" \
  --argjson versionCode "${VERSION_CODE}" \
  --arg versionName "${VERSION_NAME}" \
  --arg signerSha256 "${SIGNER_SHA256}" \
  --arg apkFile "${APK_FILE}" \
  --arg apkSha256 "${APK_SHA256}" \
  --arg updateSha256 "${UPDATE_SHA256}" \
  --arg notesSha256 "${NOTES_SHA256}" \
  --arg previousApkSha256 "${PREVIOUS_APK_SHA256}" \
  --argjson previousVersionCode "${PREVIOUS_VERSION_CODE}" \
  --arg previousVersionName "${PREVIOUS_VERSION_NAME}" \
  --arg auditScanner "${PUBLIC_AUDIT_SCANNER_SHA256}" \
  --arg auditPolicy "${POLICY_SHA256}" \
  --arg treeOid "${TREE_OID}" \
  --arg treeInput "${TREE_INPUT_SHA256}" \
  --arg treeReport "${TREE_REPORT_SHA256}" \
  --arg worktreeInput "${WORKTREE_INPUT_SHA256}" \
  --arg worktreeReport "${WORKTREE_REPORT_SHA256}" \
  --arg apkReport "${APK_REPORT_SHA256}" \
  --arg updateReport "${UPDATE_REPORT_SHA256}" \
  --arg notesReport "${NOTES_REPORT_SHA256}" \
  --arg apkZipEntryManifest "${APK_ZIP_INPUT_SHA256}" \
  --arg thirdPartyPolicy "${PUBLIC_THIRD_PARTY_POLICY_SHA256}" \
  --arg runtimeLock "${PUBLIC_RUNTIME_LOCK_SHA256}" \
  --arg sourceVerifier "${PUBLIC_SOURCE_VERIFIER_SHA256}" \
  --arg sourceReport "${SOURCE_PROVENANCE_REPORT_SHA256}" \
  '{
    schemaVersion:2,
    tag:"v1.2.3",
    source:{commit:$sourceCommit,branch:"main",workingTreeClean:true},
    app:{packageName:$packageName,versionCode:$versionCode,versionName:$versionName,
      signerSha256:$signerSha256},
    artifacts:{
      apk:{file:$apkFile,sha256:$apkSha256},
      update:{file:"update.json",sha256:$updateSha256},
      notes:{file:"release-notes.txt",sha256:$notesSha256}
    },
    previousApk:{sha256:$previousApkSha256,packageName:$packageName,
      versionCode:$previousVersionCode,versionName:$previousVersionName,
      signerSha256:$signerSha256},
    publicAudit:{
      scannerSha256:$auditScanner,
      policySha256:$auditPolicy,
      sourceTree:{gitTreeOid:$treeOid,inputSha256:$treeInput,reportSha256:$treeReport},
      worktree:{inputSha256:$worktreeInput,reportSha256:$worktreeReport},
      apk:{inputSha256:$apkSha256,reportSha256:$apkReport,
        zipEntryManifestSha256:$apkZipEntryManifest},
      thirdPartyProvenance:{
        manifestFile:"tools/apk-third-party-components.json",
        manifestSha256:$thirdPartyPolicy,
        runtimeLockFile:"tools/android-runtime-dependencies.lock.json",
        runtimeLockSha256:$runtimeLock,
        profileId:"fixture-profile",
        matchedEntryCount:1,
        applicationDexStrict:true,
        sourceVerifierFile:"tools/verify-apk-third-party-sources.mjs",
        sourceVerifierSha256:$sourceVerifier,
        sourceReportSha256:$sourceReport,
        sourceArtifactCount:1,
        sourceEntryCount:1,
        mergedSourceCount:1,
        compiledOutputCount:2,
        dexSourceArtifactCount:1,
        dexSourceEntryCount:1,
        declaredDexStringCount:1,
        sourceMatchedDexStringCount:1,
        apkMatchedDexStringCount:1
      },
      releaseMetadata:{
        update:{inputSha256:$updateSha256,reportSha256:$updateReport},
        notes:{inputSha256:$notesSha256,reportSha256:$notesReport}
      }
    }
  }' > "${MANIFEST_PATH}"
MANIFEST_SHA256="$(sha256_file "${MANIFEST_PATH}")"
write_audit_report file "${MANIFEST_SHA256}" "${MANIFEST_REPORT}"

jq -n '{schemaVersion:2,version:42,profiles:[],settings:{}}' \
  > "${PANEL_CATALOG_EVIDENCE}"
PANEL_CATALOG_SHA256="$(sha256_file "${PANEL_CATALOG_EVIDENCE}")"
jq -n \
  --arg catalogSha256 "${PANEL_CATALOG_SHA256}" \
  '{
    schemaVersion:2,
    version:42,
    sha256:$catalogSha256,
    profilesUrl:"https://panel.example.invalid/catalog/form-profiles.json",
    minAppVersionCode:1,
    updatedAt:"2030-01-01T00:00:00.000Z",
    notes:"fixture"
  }' > "${PANEL_MANIFEST_EVIDENCE}"
CATALOG_READ_KEY_SHA256="$(printf '%s' "${CATALOG_READ_KEY}" | sha256_file /dev/stdin)"
jq -n \
  --arg catalogSha256 "${PANEL_CATALOG_SHA256}" \
  --arg keySha256 "${CATALOG_READ_KEY_SHA256}" \
  '{
    catalogVersion:42,
    _autoFormKitLegacyCacheProof:{
      version:1,
      panelBase:"https://panel.example.invalid",
      keySha256:$keySha256,
      catalogSha256:$catalogSha256,
      catalogVersion:42
    }
  }' > "${PANEL_CONFIG_EVIDENCE}"
PANEL_CONFIG_SHA256="$(sha256_file "${PANEL_CONFIG_EVIDENCE}")"
PANEL_PAIR_SHA256="$(printf 'AUTOFORM_KIT_PRIVATE_PANEL_PAIR_V1\n%s\n%s\n42' \
  "${PANEL_CONFIG_SHA256}" "${PANEL_CATALOG_SHA256}" | sha256_file /dev/stdin)"

jq -n \
  --arg sourceCommit "${SOURCE_COMMIT}" \
  --arg candidateManifestSha256 "${MANIFEST_SHA256}" \
  --arg candidateApkSha256 "${APK_SHA256}" \
  --arg panelPairSha256 "${PANEL_PAIR_SHA256}" \
  --arg panelCatalogSha256 "${PANEL_CATALOG_SHA256}" \
  --arg publicHistoryRemoteRefsInputSha256 "${HISTORY_REMOTE_REFS_INPUT_SHA256}" \
  --arg publicHistoryRefApiInputSha256 "${HISTORY_REF_API_INPUT_SHA256}" \
  --arg publicHistoryRefApiSnapshotSha256 "${HISTORY_REF_API_SNAPSHOT_SHA256}" \
  --arg publicHistoryRemoteRefsRawSnapshotSha256 \
    "${HISTORY_REMOTE_REFS_RAW_SNAPSHOT_SHA256}" \
  --arg publicHistoryRefIdentitySha256 "${HISTORY_REMOTE_REFS_IDENTITY_SHA256}" \
  --arg publicHistoryPullRefIdentitySha256 "${HISTORY_PULL_REF_IDENTITY_SHA256}" \
  --arg publicHistoryReleaseInputSha256 "${HISTORY_RELEASES_INPUT_SHA256}" \
  --arg publicHistoryReleaseApiSnapshotSha256 \
    "${HISTORY_RELEASE_API_SNAPSHOT_SHA256}" \
  --arg publicHistoryRepositoryBindingSha256 \
    "${HISTORY_REPOSITORY_BINDING_SHA256}" \
  --arg publicHistoryMetadataBindingSha256 "${HISTORY_METADATA_BINDING_SHA256}" \
  '{
    releaseReady:true,
    structuralValidationPassed:true,
    candidateApkReleaseContextRequested:true,
    candidateApkReleaseContextInputComplete:true,
    candidateApkReleaseContextBindingProven:true,
    candidateApkCurrentSourceBindingProven:true,
    previousStepReadonlyProvenanceDirectGatePass:true,
    previousStepReadonlyProvenanceStoredReportByteMatch:true,
    previousStepLookupReplayStoredReportByteMatch:true,
    candidateApkSignedDeviceMatrixRequested:true,
    candidateApkSignedDeviceMatrixInputComplete:true,
    candidateApkSignedDeviceMatrixVerified:true,
    candidateApkSignedDeviceMatrixProblemCount:0,
    candidateApkSignedDeviceCandidateBindingPass:true,
    candidateApkSignedDevicePanelPairBindingPass:true,
    candidateApkSignedDeviceMatrixDigestSha256:("a" * 64),
    candidateApkSignedDeviceMatrixVerifierSha256:("b" * 64),
    candidateApkSignedDeviceRecorderSha256:("c" * 64),
    candidateApkSignedDeviceV104ReportSha256:("d" * 64),
    candidateApkSignedDeviceV106ReportSha256:("e" * 64),
    candidateApkSignedDeviceSourceCommit:$sourceCommit,
    candidateApkSignedDeviceCandidateManifestSha256:$candidateManifestSha256,
    candidateApkSignedDeviceCandidateApkSha256:$candidateApkSha256,
    candidateApkSignedDevicePanelPairSha256:$panelPairSha256,
    candidateApkSignedDevicePanelCatalogSha256:$panelCatalogSha256,
    candidateApkSignedInstallUpgradeEvidenceCount:2,
    candidateApkSignedAppInternalUpdateEvidenceCount:2,
    candidateApkSignedRestoredDraftReplayEvidenceCount:4,
    candidateApkSignedProcessRestartEvidenceCount:2,
    candidateApkSignedCleanupEvidenceCount:2,
    candidateApkSignedRollbackDraftReplayEvidenceCount:0,
    candidateApkSignedFaultScenarioEvidenceCount:5,
    upgradePersistenceSignedDeviceMatrixRequested:true,
    upgradePersistenceSignedDeviceMatrixInputComplete:true,
    upgradePersistenceSignedDeviceMatrixVerified:true,
    upgradePersistenceSignedDeviceMatrixProblemCount:0,
    upgradePersistenceSignedDeviceCandidateBindingPass:true,
    upgradePersistenceSignedDevicePanelPairBindingPass:true,
    upgradePersistenceSignedDeviceMatrixDigestSha256:("a" * 64),
    upgradePersistenceSignedDeviceMatrixVerifierSha256:("b" * 64),
    upgradePersistenceSignedDeviceRecorderSha256:("c" * 64),
    upgradePersistenceSignedDeviceV104ReportSha256:("d" * 64),
    upgradePersistenceSignedDeviceV106ReportSha256:("e" * 64),
    upgradePersistenceSignedDeviceSourceCommit:$sourceCommit,
    upgradePersistenceSignedDeviceCandidateManifestSha256:$candidateManifestSha256,
    upgradePersistenceSignedDeviceCandidateApkSha256:$candidateApkSha256,
    upgradePersistenceSignedDevicePanelPairSha256:$panelPairSha256,
    upgradePersistenceSignedDevicePanelCatalogSha256:$panelCatalogSha256,
    upgradePersistenceLegacyPanelPrewarmOldAppEvidenceCount:2,
    upgradePersistencePrewarmedOfflineUpgradeEvidenceCount:2,
    upgradePersistenceSignedUpgradeReplayEvidenceCount:2,
    upgradePersistenceRestoredDraftReplayEvidenceCount:4,
    upgradePersistenceSignedProcessRestartEvidenceCount:2,
    upgradePersistenceSignedCleanupEvidenceCount:2,
    upgradePersistenceRollbackDraftReplayEvidenceCount:1,
    upgradePersistenceSignedRollbackJournalWorkerPreflightEvidenceCount:1,
    runtimeFlowParityPickerSignedPanelFirstPrewarmEvidenceCount:2,
    runtimeFlowParityPickerSignedDeviceMatrixVerified:true,
    runtimeFlowParityPickerSignedDeviceMatrixBaselinePassCount:2,
    runtimeFlowParityPickerSignedDeviceCandidateBindingPass:true,
    runtimeFlowParityPickerSignedDevicePanelPairBindingPass:true,
    runtimeFlowParityPickerSignedDeviceMatrixDigestSha256:("a" * 64),
    runtimeFlowParityPickerSignedDeviceMatrixVerifierSha256:("b" * 64),
    runtimeFlowParityPickerSignedDeviceRecorderSha256:("c" * 64),
    runtimeFlowParityPickerSignedDeviceV104ReportSha256:("d" * 64),
    runtimeFlowParityPickerSignedDeviceV106ReportSha256:("e" * 64),
    runtimeFlowParityPickerSignedDeviceSourceCommit:$sourceCommit,
    runtimeFlowParityPickerSignedDeviceCandidateManifestSha256:$candidateManifestSha256,
    runtimeFlowParityPickerSignedDeviceCandidateApkSha256:$candidateApkSha256,
    runtimeFlowParityPickerSignedDevicePanelPairSha256:$panelPairSha256,
    runtimeFlowParityPickerSignedDevicePanelCatalogSha256:$panelCatalogSha256,
    publicHistoryRemoteRefsInputSha256:$publicHistoryRemoteRefsInputSha256,
    publicHistoryRefApiInputSha256:$publicHistoryRefApiInputSha256,
    publicHistoryRefApiSnapshotSha256:$publicHistoryRefApiSnapshotSha256,
    publicHistoryRemoteRefsRawSnapshotSha256:$publicHistoryRemoteRefsRawSnapshotSha256,
    publicHistoryRefIdentitySha256:$publicHistoryRefIdentitySha256,
    publicHistoryPullRefIdentitySha256:$publicHistoryPullRefIdentitySha256,
    publicHistoryReleaseInputSha256:$publicHistoryReleaseInputSha256,
    publicHistoryReleaseApiSnapshotSha256:$publicHistoryReleaseApiSnapshotSha256,
    publicHistoryRepositoryBindingSha256:$publicHistoryRepositoryBindingSha256,
    publicHistoryMetadataBindingSha256:$publicHistoryMetadataBindingSha256,
    publicHistoryReachableObjectClosureSha256:("f" * 64),
    publicHistoryAuditReportSha256:("9" * 64),
    publicHistoryReachableObjectCount:1,
    publicHistoryReleaseAuditReachableObjectCount:1,
    missingPathCount:0,
    unresolvedPathCount:0,
    validationErrorCount:0,
    candidateAdapterProblemCount:0,
    previousStepLookupReplayProblemCount:0,
    previousStepReadonlyProvenanceProblemCount:0,
    publicWorktreeAuditProblemCount:0,
    publicHistoryReleaseAuditProblemCount:0,
    submissionRetryAuditProblemCount:0,
    candidateApkAuditProblemCount:0,
    upgradePersistenceAuditProblemCount:0,
    runtimeFlowParityProblemCount:0
  }' > "${PRIVATE_MIGRATION_REPORT}"

jq -n \
  --arg accountId "cccccccccccccccccccccccccccccccc" \
  --arg workerName "fixture-panel" \
  --arg repository "private-example/forms" \
  --arg branch "main" \
  --arg commit "${CATALOG_COMMIT}" \
  --arg panelBase "https://panel.example.invalid" \
  --arg catalogReadKey "${CATALOG_READ_KEY}" \
  '{
    schemaVersion:2,
    catalogAuthority:{
      type:"github",
      accountId:$accountId,
      workerName:$workerName,
      repository:$repository,
      branch:$branch,
      commit:$commit
    },
    panelBase:$panelBase,
    catalogReadKey:$catalogReadKey
  }' > "${PRIVATE_DEPLOYMENT_EVIDENCE}"

jq -n \
  --arg sourceCommit "${SOURCE_COMMIT}" \
  --arg workerVersionId "${WORKER_VERSION_ID}" \
  '{panelRuntime:{
    provenance:"cloudflare_version_tag",
    sourceCommit:$sourceCommit,
    version:1,
    versionCreatedAt:"2030-01-01T00:00:00.000Z",
    workerVersionId:$workerVersionId
  }}' > "${RUNTIME_PROVENANCE_EVIDENCE}"

chmod 600 \
  "${PRIVATE_MIGRATION_REPORT}" \
  "${PANEL_CONFIG_EVIDENCE}" \
  "${PANEL_CATALOG_EVIDENCE}" \
  "${PANEL_MANIFEST_EVIDENCE}" \
  "${PRIVATE_DEPLOYMENT_EVIDENCE}" \
  "${RUNTIME_PROVENANCE_EVIDENCE}"

FETCH_MOCK="${FIXTURE_ROOT}/fetch-mock.cjs"
FETCH_LOG="${STATE_DIR}/fetch.log"
VERIFIER_CAPTURE="${STATE_DIR}/private-verifier-report.json"
VERIFIER_CALL_LOG="${STATE_DIR}/private-verifier.log"
GATE_EXEC_PATH_LOG="${STATE_DIR}/gate-exec-path.log"
RELEASE_CREATE_LOG="${STATE_DIR}/release-create.log"
RELEASE_STATE="${STATE_DIR}/release-state"
RELEASE_STORE="${STATE_DIR}/release-store"
ASSET_DOWNLOAD_LOG="${STATE_DIR}/asset-download.log"
GH_COMMAND_LOG="${STATE_DIR}/gh-command.log"
METADATA_CAPTURE_LOG="${STATE_DIR}/metadata-capture.log"
EVENT_LOG="${STATE_DIR}/events.log"
MALICIOUS_GATE_MARKER="${STATE_DIR}/malicious-gate-executed"
CLEANUP_INJECTION_LOG="${STATE_DIR}/cleanup-injection.log"
CLEANUP_SENTINEL_DIR="${STATE_DIR}/cleanup-sentinel"
mkdir -p "${RELEASE_STORE}"

cat > "${FETCH_MOCK}" <<'EOF'
const fs = require("node:fs");

const allowedOrigin = "https://panel.example.invalid";
const protectedPaths = new Set([
  "/api/profiles",
  "/api/config",
  "/catalog/form-profiles.json",
  "/catalog/manifest",
  "/api/panel-config",
  "/api/notify",
  "/api/runtime-provenance"
]);

global.fetch = async function fixtureFetch(url, options = {}) {
  const parsed = new URL(url);
  if (parsed.origin !== allowedOrigin || parsed.search || parsed.hash
      || !protectedPaths.has(parsed.pathname)) {
    throw new Error(`unexpected fixture fetch: ${parsed.toString()}`);
  }
  const expectedMethod = parsed.pathname === "/api/notify" ? "POST" : "GET";
  const method = options.method || "GET";
  if (method !== expectedMethod) {
    throw new Error(`unexpected fixture method: ${method} ${parsed.pathname}`);
  }
  const authorization = options.headers?.Authorization || "";
  const authenticated = authorization
    === `Bearer ${process.env.AUTOFORM_CHAIN_CATALOG_READ_KEY}`;
  const authClass = authenticated ? "correct" : (authorization ? "incorrect" : "anonymous");
  fs.appendFileSync(process.env.AUTOFORM_CHAIN_FETCH_LOG,
    `${JSON.stringify({ method, path: parsed.pathname, authClass })}\n`, { mode: 0o600 });
  let status = parsed.pathname === "/api/panel-config" ? 200 : 401;
  let body = parsed.pathname === "/api/panel-config"
    ? Buffer.from('{"fixture":true}\n', "utf8")
    : Buffer.from('{"error":"unauthorized"}\n', "utf8");
  if (authenticated) {
    status = 200;
    if (parsed.pathname === "/api/profiles") {
      body = Buffer.from('{"profiles":[]}\n', "utf8");
    } else if (parsed.pathname === "/api/config") {
      body = fs.readFileSync(process.env.AUTOFORM_CHAIN_PANEL_CONFIG);
    } else if (parsed.pathname === "/catalog/form-profiles.json") {
      body = fs.readFileSync(process.env.AUTOFORM_CHAIN_PANEL_CATALOG);
    } else if (parsed.pathname === "/catalog/manifest") {
      body = fs.readFileSync(process.env.AUTOFORM_CHAIN_PANEL_MANIFEST);
    } else if (parsed.pathname === "/api/runtime-provenance") {
      body = fs.readFileSync(process.env.AUTOFORM_CHAIN_RUNTIME_PROVENANCE);
    } else if (parsed.pathname === "/api/panel-config") {
      body = Buffer.from('{"fixture":true}\n', "utf8");
    } else {
      throw new Error(`unexpected authenticated fixture fetch: ${parsed.pathname}`);
    }
  }
  return {
    status,
    url: parsed.toString(),
    async arrayBuffer() {
      return body.buffer.slice(body.byteOffset, body.byteOffset + body.byteLength);
    }
  };
};
EOF

cat > "${BIN_DIR}/git" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
real_git="${AUTOFORM_CHAIN_REAL_GIT:?}"
case "${1:-}" in
  ls-remote)
    if [[ $# -eq 2 && "${2}" == "origin" ]]; then
      printf 'capture\n' >> "${AUTOFORM_CHAIN_METADATA_CAPTURE_LOG}"
      /bin/cat "${AUTOFORM_CHAIN_HISTORY_REMOTE_REFS_RAW_FIXTURE}"
      exit 0
    fi
    if [[ $# -eq 4 && "${2}" == "--heads" && "${3}" == "origin" \
      && "${4}" == "refs/heads/main" ]]; then
      printf '%s\trefs/heads/main\n' "${AUTOFORM_CHAIN_SOURCE_COMMIT}"
      exit 0
    fi
    if [[ $# -eq 5 && "${2}" == "--tags" && "${3}" == "origin" \
      && "${4}" == refs/tags/* && "${5}" == refs/tags/*'^{}' ]]; then
      exit 0
    fi
    if [[ $# -eq 4 && "${2}" == "--heads" && "${3}" == "--tags" \
      && "${4}" == "origin" ]]; then
      printf '%s\trefs/heads/main\n' "${AUTOFORM_CHAIN_SOURCE_COMMIT}"
      exit 0
    fi
    printf 'unexpected fixture git remote query:' >&2
    printf ' %q' "$@" >&2
    printf '\n' >&2
    exit 2
    ;;
  fetch|push|pull|clone)
    printf 'network Git command is forbidden in private release chain selftest\n' >&2
    exit 2
    ;;
  remote)
    [[ $# -eq 3 && "${2}" == "get-url" && "${3}" == "origin" ]] || {
      printf 'unexpected fixture git remote command\n' >&2
      exit 2
    }
    exec "${real_git}" "$@"
    ;;
  ls-files|check-ignore|rev-parse|hash-object|status|symbolic-ref|show-ref|cat-file)
    exec "${real_git}" "$@"
    ;;
  *)
    printf 'unexpected fixture git command:' >&2
    printf ' %q' "$@" >&2
    printf '\n' >&2
    exit 2
    ;;
esac
EOF

cat > "${BIN_DIR}/stat" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
real_stat="${AUTOFORM_CHAIN_REAL_STAT:?}"
if [[ "${AUTOFORM_CHAIN_SPOOF_TEMP_PARENT_OWNER:-false}" == "true" \
  && "${!#}" == "${AUTOFORM_CHAIN_TEMP_PARENT:?}" ]]; then
  if "${real_stat}" -f '%u' "${AUTOFORM_CHAIN_TEMP_PARENT}" >/dev/null 2>&1; then
    if [[ $# -eq 3 && "${1:-}" == "-f" && "${2:-}" == "%u" ]]; then
      printf '424242\n'
      exit 0
    fi
  elif [[ $# -eq 4 && "${1:-}" == "-c" && "${2:-}" == "%u" \
    && "${3:-}" == "--" ]]; then
    printf '424242\n'
    exit 0
  elif [[ $# -eq 3 && "${1:-}" == "-c" && "${2:-}" == "%u" ]]; then
    printf '424242\n'
    exit 0
  fi
fi
exec "${real_stat}" "$@"
EOF

cat > "${BIN_DIR}/node" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
program="${1:-}"
[[ -n "${program}" ]]
shift
case "$(basename "${program}")" in
  normalize-github-releases-for-audit.mjs)
    exec "${AUTOFORM_CHAIN_REAL_NODE}" "${program}" "$@"
    ;;
  private-release-gate.cjs)
    printf '%s\n' "${program}" >> "${AUTOFORM_CHAIN_GATE_EXEC_PATH_LOG}"
    set +e
    "${AUTOFORM_CHAIN_REAL_NODE}" "${program}" "$@"
    status=$?
    set -e
    if [[ ${status} -eq 0 \
      && "${AUTOFORM_CHAIN_MUTATE_REF_CONTEXT_AFTER_GATE:-false}" == true ]]; then
      jq '.[0][0].protected = true
        | .[0][0].protection_url =
          "https://api.github.com/repos/example/autoform-kit/branches/main/protection"
        | .[0][0].protection = {enabled:true,required_status_checks:{
          enforcement_level:"everyone",
          contexts:["changed-after-gate-context"],
          checks:[{app_id:null,context:"changed-after-gate-context"}]}}' \
        "${AUTOFORM_CHAIN_HISTORY_BRANCHES_API_FIXTURE}" \
        > "${AUTOFORM_CHAIN_HISTORY_BRANCHES_API_FIXTURE}.changed"
      mv "${AUTOFORM_CHAIN_HISTORY_BRANCHES_API_FIXTURE}.changed" \
        "${AUTOFORM_CHAIN_HISTORY_BRANCHES_API_FIXTURE}"
    fi
    exit "${status}"
    ;;
  verify-private-release-evidence.mjs)
    set +e
    "${AUTOFORM_CHAIN_REAL_NODE}" --require "${AUTOFORM_CHAIN_FETCH_MOCK}" \
      "${program}" "$@" | tee "${AUTOFORM_CHAIN_VERIFIER_CAPTURE}"
    status=${PIPESTATUS[0]}
    set -e
    if [[ ${status} -eq 0 ]]; then
      printf 'called\n' >> "${AUTOFORM_CHAIN_VERIFIER_CALL_LOG}"
      printf 'verifier\n' >> "${AUTOFORM_CHAIN_EVENT_LOG}"
      call_count="$(wc -l < "${AUTOFORM_CHAIN_VERIFIER_CALL_LOG}" | tr -d ' ')"
      if [[ "${AUTOFORM_CHAIN_MUTATE_METADATA_DURING_REVERIFY:-false}" == true \
        && "${call_count}" == "2" ]]; then
        jq '.description = "changed-during-private-reverification"' \
          "${AUTOFORM_CHAIN_HISTORY_REPOSITORY_API_FIXTURE}" \
          > "${AUTOFORM_CHAIN_HISTORY_REPOSITORY_API_FIXTURE}.changed"
        mv "${AUTOFORM_CHAIN_HISTORY_REPOSITORY_API_FIXTURE}.changed" \
          "${AUTOFORM_CHAIN_HISTORY_REPOSITORY_API_FIXTURE}"
      fi
    fi
    exit "${status}"
    ;;
  verify-apk-third-party-sources.mjs)
    /bin/cat "${AUTOFORM_CHAIN_SOURCE_PROVENANCE_REPORT}"
    ;;
  public-surface-audit.mjs)
    mode=""
    file=""
    wordlist_seen=false
    while [[ $# -gt 0 ]]; do
      case "$1" in
        --git-tree)
          mode="git-tree"
          shift 2
          ;;
        --worktree)
          mode="worktree"
          shift
          ;;
        --apk)
          mode="apk"
          shift 2
          ;;
        --file)
          mode="file"
          file="$2"
          shift 2
          ;;
        --repo)
          shift 2
          ;;
        --private-wordlist)
          [[ "$2" == "${AUTOFORM_CHAIN_PRIVATE_WORDLIST}" ]]
          wordlist_seen=true
          shift 2
          ;;
        *)
          printf 'unexpected fixture scanner argument: %s\n' "$1" >&2
          exit 2
          ;;
      esac
    done
    [[ "${wordlist_seen}" == true ]]
    case "${mode}" in
      git-tree) /bin/cat "${AUTOFORM_CHAIN_TREE_REPORT}" ;;
      worktree) /bin/cat "${AUTOFORM_CHAIN_WORKTREE_REPORT}" ;;
      apk) /bin/cat "${AUTOFORM_CHAIN_APK_REPORT}" ;;
      file)
        case "${file}" in
          "${AUTOFORM_CHAIN_UPDATE_PATH}") /bin/cat "${AUTOFORM_CHAIN_UPDATE_REPORT}" ;;
          "${AUTOFORM_CHAIN_NOTES_PATH}") /bin/cat "${AUTOFORM_CHAIN_NOTES_REPORT}" ;;
          "${AUTOFORM_CHAIN_MANIFEST_PATH}") /bin/cat "${AUTOFORM_CHAIN_MANIFEST_REPORT}" ;;
          */source-commit-object.scan-input)
            /bin/cat "${AUTOFORM_CHAIN_HISTORY_COMMIT_REPORT}"
            ;;
          */remote-refs.scan-input)
            /bin/cat "${AUTOFORM_CHAIN_HISTORY_REMOTE_REFS_REPORT}"
            ;;
          */github-refs.scan-input.json)
            /bin/cat "${AUTOFORM_CHAIN_HISTORY_REF_API_REPORT}"
            ;;
          */github-releases.scan-input.json)
            /bin/cat "${AUTOFORM_CHAIN_HISTORY_RELEASES_REPORT}"
            ;;
          *)
            printf 'unexpected fixture scanner file: %s\n' "${file}" >&2
            exit 2
            ;;
        esac
        ;;
      *)
        printf 'unexpected fixture scanner mode: %s\n' "${mode}" >&2
        exit 2
        ;;
    esac
    ;;
  *)
    printf 'unexpected fixture Node program: %s\n' "${program}" >&2
    exit 2
    ;;
esac
EOF

cat > "${BIN_DIR}/aapt" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
[[ "${1:-}" == "dump" && "${2:-}" == "badging" && -n "${3:-}" ]]
if [[ "$(basename "${3}")" == "previous.apk" ]]; then
  printf "package: name='%s' versionCode='%s' versionName='%s'\n" \
    "${AUTOFORM_CHAIN_PACKAGE}" \
    "${AUTOFORM_CHAIN_PREVIOUS_VERSION_CODE}" \
    "${AUTOFORM_CHAIN_PREVIOUS_VERSION_NAME}"
else
  printf "package: name='%s' versionCode='%s' versionName='%s'\n" \
    "${AUTOFORM_CHAIN_PACKAGE}" \
    "${AUTOFORM_CHAIN_VERSION_CODE}" \
    "${AUTOFORM_CHAIN_VERSION_NAME}"
fi
EOF

cat > "${BIN_DIR}/apksigner" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
[[ "${1:-}" == "verify" ]]
printf 'Verifies\n'
printf 'Signer #1 certificate SHA-256 digest: %s\n' "${AUTOFORM_CHAIN_SIGNER}"
EOF

cat > "${BIN_DIR}/gh" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
printf '%q' "${1:-}" >> "${AUTOFORM_CHAIN_GH_COMMAND_LOG}"
for argument in "${@:2}"; do
  printf ' %q' "${argument}" >> "${AUTOFORM_CHAIN_GH_COMMAND_LOG}"
done
printf '\n' >> "${AUTOFORM_CHAIN_GH_COMMAND_LOG}"
case "${1:-}" in
  auth)
    [[ "${2:-}" == "status" ]]
    exit 0
    ;;
  api)
    endpoint="${!#}"
    if [[ "${endpoint}" == "repos/example/autoform-kit/branches?per_page=100" ]]; then
      [[ $# -eq 4 && "${2:-}" == "--paginate" && "${3:-}" == "--slurp" ]] || {
        printf 'unexpected fixture branch-history API arguments\n' >&2
        exit 2
      }
      /bin/cat "${AUTOFORM_CHAIN_HISTORY_BRANCHES_API_FIXTURE}"
      exit 0
    fi
    if [[ "${endpoint}" == "repos/example/autoform-kit/tags?per_page=100" ]]; then
      [[ $# -eq 4 && "${2:-}" == "--paginate" && "${3:-}" == "--slurp" ]] || {
        printf 'unexpected fixture tag-history API arguments\n' >&2
        exit 2
      }
      /bin/cat "${AUTOFORM_CHAIN_HISTORY_TAGS_API_FIXTURE}"
      exit 0
    fi
    if [[ "${endpoint}" == "repos/example/autoform-kit/releases?per_page=100" ]]; then
      [[ $# -eq 4 && "${2:-}" == "--paginate" && "${3:-}" == "--slurp" ]] || {
        printf 'unexpected fixture release-history API arguments\n' >&2
        exit 2
      }
      /bin/cat "${AUTOFORM_CHAIN_HISTORY_RELEASES_API_FIXTURE}"
      exit 0
    fi
    if [[ "${endpoint}" == "repos/example/autoform-kit" ]]; then
      [[ $# -eq 2 ]] || {
        printf 'unexpected fixture public-repository API arguments\n' >&2
        exit 2
      }
      /bin/cat "${AUTOFORM_CHAIN_HISTORY_REPOSITORY_API_FIXTURE}"
      exit 0
    fi
    if [[ "${endpoint}" == "repos/private-example/forms" ]]; then
      [[ $# -eq 2 ]] || {
        printf 'unexpected fixture private-repository API arguments\n' >&2
        exit 2
      }
      printf '%s\n' \
        '{"node_id":"private-fixture-node","full_name":"private-example/forms","private":true,"default_branch":"main"}'
      exit 0
    fi
    if [[ "${endpoint}" == "repos/private-example/forms/git/ref/heads/main" ]]; then
      [[ $# -eq 2 ]] || {
        printf 'unexpected fixture private-branch API arguments\n' >&2
        exit 2
      }
      printf '%s\n' \
        '{"ref":"refs/heads/main","object":{"type":"commit","sha":"dddddddddddddddddddddddddddddddddddddddd"}}'
      exit 0
    fi
    if [[ "${endpoint}" == "repos/private-example/forms/git/trees/${AUTOFORM_CHAIN_CATALOG_COMMIT}?recursive=1" ]]; then
      [[ $# -eq 2 ]] || {
        printf 'unexpected fixture private-tree API arguments\n' >&2
        exit 2
      }
      printf '%s\n' \
        '{"truncated":false,"tree":[{"path":"form-profiles.json","type":"blob","sha":"eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee"},{"path":"manifest.json","type":"blob","sha":"ffffffffffffffffffffffffffffffffffffffff"}]}'
      exit 0
    fi
    if [[ "${endpoint}" == "repos/private-example/forms/contents/form-profiles.json?ref=${AUTOFORM_CHAIN_CATALOG_COMMIT}" ]]; then
      [[ $# -eq 4 && "${2:-}" == "-H" \
        && "${3:-}" == "Accept: application/vnd.github.raw+json" ]] || {
        printf 'unexpected fixture raw-catalog API arguments\n' >&2
        exit 2
      }
      /bin/cat "${AUTOFORM_CHAIN_PANEL_CATALOG}"
      exit 0
    fi
    if [[ "${endpoint}" == "repos/private-example/forms/contents/manifest.json?ref=${AUTOFORM_CHAIN_CATALOG_COMMIT}" ]]; then
      [[ $# -eq 4 && "${2:-}" == "-H" \
        && "${3:-}" == "Accept: application/vnd.github.raw+json" ]] || {
        printf 'unexpected fixture raw-manifest API arguments\n' >&2
        exit 2
      }
      /bin/cat "${AUTOFORM_CHAIN_PANEL_MANIFEST}"
      exit 0
    fi
    if [[ "${endpoint}" == "repos/example/autoform-kit/releases/tags/"* ]]; then
      [[ $# -eq 2 ]] || {
        printf 'unexpected fixture release-tag API arguments\n' >&2
        exit 2
      }
      printf 'gh: Not Found (HTTP 404)\n' >&2
      exit 1
    fi
    if [[ "${endpoint}" == "repos/example/autoform-kit/releases/latest" ]]; then
      [[ $# -eq 2 ]] || {
        printf 'unexpected fixture latest-release API arguments\n' >&2
        exit 2
      }
      if [[ "${AUTOFORM_CHAIN_REPLACE_AUDIT_DIRECTORY:-false}" == "true" \
        && ! -s "${AUTOFORM_CHAIN_CLEANUP_INJECTION_LOG}" ]]; then
        audit_directory=""
        for candidate in "${AUTOFORM_CHAIN_TEMP_PARENT}"/autoform-public-release-audit.*; do
          [[ -d "${candidate}" && ! -L "${candidate}" ]] || continue
          [[ -z "${audit_directory}" ]] || {
            printf 'multiple fixture audit directories found\n' >&2
            exit 2
          }
          audit_directory="${candidate}"
        done
        [[ -n "${audit_directory}" \
          && -d "${AUTOFORM_CHAIN_CLEANUP_SENTINEL_DIR}" ]] || {
          printf 'cleanup drift fixture could not locate its directories\n' >&2
          exit 2
        }
        /bin/mv "${audit_directory}" "${audit_directory}.original"
        /bin/mv "${AUTOFORM_CHAIN_CLEANUP_SENTINEL_DIR}" "${audit_directory}"
        printf '%s\n' "${audit_directory}" > "${AUTOFORM_CHAIN_CLEANUP_INJECTION_LOG}"
      fi
      [[ -f "${AUTOFORM_CHAIN_RELEASE_STATE}" ]] || {
        printf 'gh: Not Found (HTTP 404)\n' >&2
        exit 1
      }
      release_tag="$(jq -er '.tag' "${AUTOFORM_CHAIN_RELEASE_STATE}")"
      release_title="$(jq -er '.title' "${AUTOFORM_CHAIN_RELEASE_STATE}")"
      apk_name="$(jq -er '.apkName' "${AUTOFORM_CHAIN_RELEASE_STATE}")"
      apk_size="$(wc -c < "${AUTOFORM_CHAIN_RELEASE_STORE}/apk.asset" | tr -d ' ')"
      update_size="$(wc -c < "${AUTOFORM_CHAIN_RELEASE_STORE}/update.json" | tr -d ' ')"
      manifest_size="$(wc -c < "${AUTOFORM_CHAIN_RELEASE_STORE}/candidate-manifest.json" | tr -d ' ')"
      jq -n \
        --arg tag "${release_tag}" \
        --arg name "${release_title}" \
        --rawfile body "${AUTOFORM_CHAIN_RELEASE_STORE}/release-notes.txt" \
        --arg apk "${apk_name}" \
        --argjson apkSize "${apk_size}" \
        --argjson updateSize "${update_size}" \
        --argjson manifestSize "${manifest_size}" \
        '{
          tag_name:$tag,
          name:$name,
          body:$body,
          draft:false,
          prerelease:false,
          assets:[
            {id:101,name:$apk,state:"uploaded",size:$apkSize},
            {id:102,name:"update.json",state:"uploaded",size:$updateSize},
            {id:103,name:"candidate-manifest.json",state:"uploaded",size:$manifestSize}
          ]
        }'
      exit 0
    fi
    case "${endpoint}" in
      repos/example/autoform-kit/releases/assets/101)
        source_path="${AUTOFORM_CHAIN_RELEASE_STORE}/apk.asset"
        ;;
      repos/example/autoform-kit/releases/assets/102)
        source_path="${AUTOFORM_CHAIN_RELEASE_STORE}/update.json"
        ;;
      repos/example/autoform-kit/releases/assets/103)
        source_path="${AUTOFORM_CHAIN_RELEASE_STORE}/candidate-manifest.json"
        ;;
      *)
        printf 'unexpected fixture gh api endpoint: %s\n' "${endpoint}" >&2
        exit 2
        ;;
    esac
    [[ $# -eq 4 && "${2:-}" == "-H" \
      && "${3:-}" == "Accept: application/octet-stream" ]] || {
      printf 'unexpected fixture release-asset API arguments\n' >&2
      exit 2
    }
    printf '%s\n' "${endpoint##*/}" >> "${AUTOFORM_CHAIN_ASSET_DOWNLOAD_LOG}"
    /bin/cat "${source_path}"
    ;;
  release)
    [[ $# -eq 15 \
      && "${2:-}" == "create" \
      && "${3:-}" == "v${AUTOFORM_CHAIN_VERSION_NAME}" \
      && "${4:-}" == "${AUTOFORM_CHAIN_APK_PATH}" \
      && "${5:-}" == "${AUTOFORM_CHAIN_UPDATE_PATH}" \
      && "${6:-}" == "${AUTOFORM_CHAIN_MANIFEST_PATH}" \
      && "${7:-}" == "--repo" \
      && "${8:-}" == "${AUTOFORM_CHAIN_REPO_SLUG}" \
      && "${9:-}" == "--target" \
      && "${10:-}" == "${AUTOFORM_CHAIN_SOURCE_COMMIT}" \
      && "${11:-}" == "--title" \
      && "${12:-}" == "autoform-kit ${AUTOFORM_CHAIN_VERSION_NAME}" \
      && "${13:-}" == "--notes-file" \
      && "${14:-}" == "${AUTOFORM_CHAIN_NOTES_PATH}" \
      && "${15:-}" == "--latest" ]] || {
      printf 'unexpected fixture gh release create arguments\n' >&2
      exit 2
    }
    [[ "$(wc -l < "${AUTOFORM_CHAIN_VERIFIER_CALL_LOG}" | tr -d ' ')" == "2" \
      && "$(wc -l < "${AUTOFORM_CHAIN_GATE_LOG}" | tr -d ' ')" == "1" \
      && "$(tr '\n' ' ' < "${AUTOFORM_CHAIN_EVENT_LOG}")" == "verifier gate verifier " ]] || {
      printf 'release creation occurred before verifier/gate/reverification ordering completed\n' >&2
      exit 2
    }
    for source_path in "${4}" "${5}" "${6}" "${14}"; do
      [[ -f "${source_path}" && ! -L "${source_path}" ]] || {
        printf 'release input is not a regular non-symlink file: %s\n' "${source_path}" >&2
        exit 2
      }
    done
    cp "${4}" "${AUTOFORM_CHAIN_RELEASE_STORE}/apk.asset"
    cp "${5}" "${AUTOFORM_CHAIN_RELEASE_STORE}/update.json"
    cp "${6}" "${AUTOFORM_CHAIN_RELEASE_STORE}/candidate-manifest.json"
    cp "${14}" "${AUTOFORM_CHAIN_RELEASE_STORE}/release-notes.txt"
    jq -nS \
      --arg tag "${3}" \
      --arg title "${12}" \
      --arg repo "${8}" \
      --arg target "${10}" \
      --arg apkName "$(basename "${4}")" \
      '{tag:$tag,title:$title,repo:$repo,target:$target,apkName:$apkName}' \
      > "${AUTOFORM_CHAIN_RELEASE_STATE}"
    printf 'called\n' >> "${AUTOFORM_CHAIN_RELEASE_CREATE_LOG}"
    printf 'release\n' >> "${AUTOFORM_CHAIN_EVENT_LOG}"
    ;;
  *)
    printf 'unexpected fixture gh command:' >&2
    printf ' %q' "$@" >&2
    printf '\n' >&2
    exit 2
    ;;
esac
EOF

chmod 700 "${BIN_DIR}/git" "${BIN_DIR}/stat" "${BIN_DIR}/node" "${BIN_DIR}/aapt" \
  "${BIN_DIR}/apksigner" "${BIN_DIR}/gh"

CHAIN_CONFIG_DIR="${FIXTURE_ROOT}/isolated-config"
mkdir -p "${CHAIN_CONFIG_DIR}"
unset NODE_OPTIONS NODE_PATH BASH_ENV ENV GH_TOKEN GITHUB_TOKEN \
  CLOUDFLARE_API_TOKEN CLOUDFLARE_API_KEY CLOUDFLARE_EMAIL

reset_run_state() {
  : > "${VERIFIER_CALL_LOG}"
  : > "${GATE_LOG}"
  : > "${GATE_EXEC_PATH_LOG}"
  : > "${RELEASE_CREATE_LOG}"
  : > "${ASSET_DOWNLOAD_LOG}"
  : > "${GH_COMMAND_LOG}"
  : > "${METADATA_CAPTURE_LOG}"
  : > "${FETCH_LOG}"
  : > "${EVENT_LOG}"
  : > "${CLEANUP_INJECTION_LOG}"
  rm -f "${VERIFIER_CAPTURE}" "${GATE_ENV_CAPTURE}" "${RELEASE_STATE}" \
    "${MALICIOUS_GATE_MARKER}" \
    "${RELEASE_STORE}/apk.asset" \
    "${RELEASE_STORE}/update.json" \
    "${RELEASE_STORE}/candidate-manifest.json" \
    "${RELEASE_STORE}/release-notes.txt"
}

run_publish() {
  local tamper="$1"
  local output="$2"
  local mutate_original_gate="${3:-false}"
  local replace_snapshot_directory="${4:-false}"
  local release_temp_parent="${5:-${CHAIN_TEMP_PARENT}}"
  local spoof_temp_parent_owner="${6:-false}"
  local replace_audit_directory="${7:-false}"
  reset_run_state
  PATH="${BIN_DIR}:${ORIGINAL_PATH}" \
  TMPDIR="${release_temp_parent}" \
  XDG_CONFIG_HOME="${CHAIN_CONFIG_DIR}" \
  WRANGLER_HOME="${CHAIN_CONFIG_DIR}" \
  GH_CONFIG_DIR="${CHAIN_CONFIG_DIR}" \
  GIT_CONFIG_NOSYSTEM=1 \
  GIT_CONFIG_GLOBAL=/dev/null \
  AAPT="${BIN_DIR}/aapt" \
  APKSIGNER="${BIN_DIR}/apksigner" \
  AUTOFORM_CHAIN_REAL_GIT="${REAL_GIT}" \
  AUTOFORM_CHAIN_REAL_NODE="${REAL_NODE}" \
  AUTOFORM_CHAIN_REAL_STAT="${REAL_STAT}" \
  AUTOFORM_CHAIN_SOURCE_COMMIT="${SOURCE_COMMIT}" \
  AUTOFORM_CHAIN_FETCH_MOCK="${FETCH_MOCK}" \
  AUTOFORM_CHAIN_FETCH_LOG="${FETCH_LOG}" \
  AUTOFORM_CHAIN_VERIFIER_CAPTURE="${VERIFIER_CAPTURE}" \
  AUTOFORM_CHAIN_VERIFIER_CALL_LOG="${VERIFIER_CALL_LOG}" \
  AUTOFORM_CHAIN_GATE_LOG="${GATE_LOG}" \
  AUTOFORM_CHAIN_GATE_ENV_CAPTURE="${GATE_ENV_CAPTURE}" \
  AUTOFORM_CHAIN_GATE_EXEC_PATH_LOG="${GATE_EXEC_PATH_LOG}" \
  AUTOFORM_CHAIN_EVENT_LOG="${EVENT_LOG}" \
  AUTOFORM_CHAIN_TAMPER_ATTESTATION="${tamper}" \
  AUTOFORM_CHAIN_MUTATE_ORIGINAL_GATE="${mutate_original_gate}" \
  AUTOFORM_CHAIN_REPLACE_SNAPSHOT_DIRECTORY="${replace_snapshot_directory}" \
  AUTOFORM_CHAIN_SPOOF_TEMP_PARENT_OWNER="${spoof_temp_parent_owner}" \
  AUTOFORM_CHAIN_TEMP_PARENT="${release_temp_parent}" \
  AUTOFORM_CHAIN_REPLACE_AUDIT_DIRECTORY="${replace_audit_directory}" \
  AUTOFORM_CHAIN_MUTATE_METADATA_DURING_REVERIFY="${AUTOFORM_CHAIN_MUTATE_METADATA_DURING_REVERIFY:-false}" \
  AUTOFORM_CHAIN_MUTATE_REF_CONTEXT_AFTER_GATE="${AUTOFORM_CHAIN_MUTATE_REF_CONTEXT_AFTER_GATE:-false}" \
  AUTOFORM_CHAIN_CLEANUP_INJECTION_LOG="${CLEANUP_INJECTION_LOG}" \
  AUTOFORM_CHAIN_CLEANUP_SENTINEL_DIR="${CLEANUP_SENTINEL_DIR}" \
  AUTOFORM_CHAIN_ORIGINAL_GATE_PATH="${GATE_PROGRAM}" \
  AUTOFORM_CHAIN_MALICIOUS_MARKER="${MALICIOUS_GATE_MARKER}" \
  AUTOFORM_CHAIN_RELEASE_CREATE_LOG="${RELEASE_CREATE_LOG}" \
  AUTOFORM_CHAIN_RELEASE_STATE="${RELEASE_STATE}" \
  AUTOFORM_CHAIN_RELEASE_STORE="${RELEASE_STORE}" \
  AUTOFORM_CHAIN_ASSET_DOWNLOAD_LOG="${ASSET_DOWNLOAD_LOG}" \
  AUTOFORM_CHAIN_GH_COMMAND_LOG="${GH_COMMAND_LOG}" \
  AUTOFORM_CHAIN_METADATA_CAPTURE_LOG="${METADATA_CAPTURE_LOG}" \
  AUTOFORM_CHAIN_PRIVATE_WORDLIST="${PRIVATE_WORDLIST}" \
  AUTOFORM_CHAIN_TREE_REPORT="${TREE_REPORT}" \
  AUTOFORM_CHAIN_WORKTREE_REPORT="${WORKTREE_REPORT}" \
  AUTOFORM_CHAIN_APK_REPORT="${APK_REPORT}" \
  AUTOFORM_CHAIN_UPDATE_REPORT="${UPDATE_REPORT}" \
  AUTOFORM_CHAIN_NOTES_REPORT="${NOTES_REPORT}" \
  AUTOFORM_CHAIN_MANIFEST_REPORT="${MANIFEST_REPORT}" \
  AUTOFORM_CHAIN_SOURCE_PROVENANCE_REPORT="${SOURCE_PROVENANCE_REPORT}" \
  AUTOFORM_CHAIN_HISTORY_COMMIT_REPORT="${HISTORY_COMMIT_REPORT}" \
  AUTOFORM_CHAIN_HISTORY_REMOTE_REFS_RAW_FIXTURE="${HISTORY_REMOTE_REFS_RAW_FIXTURE}" \
  AUTOFORM_CHAIN_HISTORY_REMOTE_REFS_REPORT="${HISTORY_REMOTE_REFS_REPORT}" \
  AUTOFORM_CHAIN_HISTORY_REF_API_REPORT="${HISTORY_REF_API_REPORT}" \
  AUTOFORM_CHAIN_HISTORY_RELEASES_REPORT="${HISTORY_RELEASES_REPORT}" \
  AUTOFORM_CHAIN_HISTORY_BRANCHES_API_FIXTURE="${HISTORY_BRANCHES_API_FIXTURE}" \
  AUTOFORM_CHAIN_HISTORY_TAGS_API_FIXTURE="${HISTORY_TAGS_API_FIXTURE}" \
  AUTOFORM_CHAIN_HISTORY_RELEASES_API_FIXTURE="${HISTORY_RELEASES_API_FIXTURE}" \
  AUTOFORM_CHAIN_HISTORY_REPOSITORY_API_FIXTURE="${HISTORY_REPOSITORY_API_FIXTURE}" \
  AUTOFORM_CHAIN_PANEL_CONFIG="${PANEL_CONFIG_EVIDENCE}" \
  AUTOFORM_CHAIN_PANEL_CATALOG="${PANEL_CATALOG_EVIDENCE}" \
  AUTOFORM_CHAIN_PANEL_MANIFEST="${PANEL_MANIFEST_EVIDENCE}" \
  AUTOFORM_CHAIN_RUNTIME_PROVENANCE="${RUNTIME_PROVENANCE_EVIDENCE}" \
  AUTOFORM_CHAIN_CATALOG_READ_KEY="${CATALOG_READ_KEY}" \
  AUTOFORM_CHAIN_PACKAGE="${PACKAGE_NAME}" \
  AUTOFORM_CHAIN_VERSION_CODE="${VERSION_CODE}" \
  AUTOFORM_CHAIN_VERSION_NAME="${VERSION_NAME}" \
  AUTOFORM_CHAIN_REPO_SLUG="example/autoform-kit" \
  AUTOFORM_CHAIN_CATALOG_COMMIT="${CATALOG_COMMIT}" \
  AUTOFORM_CHAIN_PREVIOUS_VERSION_CODE="${PREVIOUS_VERSION_CODE}" \
  AUTOFORM_CHAIN_PREVIOUS_VERSION_NAME="${PREVIOUS_VERSION_NAME}" \
  AUTOFORM_CHAIN_SIGNER="${SIGNER_SHA256}" \
  AUTOFORM_CHAIN_APK_PATH="${APK_PATH}" \
  AUTOFORM_CHAIN_UPDATE_PATH="${UPDATE_PATH}" \
  AUTOFORM_CHAIN_NOTES_PATH="${NOTES_PATH}" \
  AUTOFORM_CHAIN_MANIFEST_PATH="${MANIFEST_PATH}" \
    bash "${TOOLS_DIR}/publish-release.sh" \
      --candidate "${MANIFEST_PATH}" \
      --previous-apk "${PREVIOUS_APK}" \
      --gate "${GATE_PROGRAM}" \
      --private-migration-report "${PRIVATE_MIGRATION_REPORT}" \
      --panel-config-evidence "${PANEL_CONFIG_EVIDENCE}" \
      --panel-catalog-evidence "${PANEL_CATALOG_EVIDENCE}" \
      --private-deployment-evidence "${PRIVATE_DEPLOYMENT_EVIDENCE}" \
      --private-wordlist "${PRIVATE_WORDLIST}" > "${output}" 2>&1
}

assert_fetch_matrix() {
  local verifier_runs="$1"
  jq -s -e --argjson runs "${verifier_runs}" '
    def calls($requests; $method; $path; $authClass):
      $requests
      | map(select(.method == $method and .path == $path and .authClass == $authClass))
      | length;
    . as $requests
    | [
      "/api/config",
      "/api/profiles",
      "/api/panel-config",
      "/catalog/form-profiles.json",
      "/catalog/manifest"
    ] as $ordinaryGetRoutes
    | [
      "/api/config",
      "/api/profiles",
      "/api/runtime-provenance",
      "/catalog/form-profiles.json",
      "/catalog/manifest"
    ] as $protectedGetRoutes
    | ($requests | length) == (21 * $runs)
    and ([$ordinaryGetRoutes[] as $route
      | calls($requests; "GET"; $route; "correct") == $runs] | all)
    and calls($requests; "GET"; "/api/runtime-provenance"; "correct") == (2 * $runs)
    and calls($requests; "GET"; "/api/panel-config"; "correct") == $runs
    and calls($requests; "GET"; "/api/panel-config"; "anonymous") == $runs
    and calls($requests; "GET"; "/api/panel-config"; "incorrect") == $runs
    and ([$protectedGetRoutes[] as $route
      | calls($requests; "GET"; $route; "anonymous") == $runs
        and calls($requests; "GET"; $route; "incorrect") == $runs] | all)
    and calls($requests; "POST"; "/api/notify"; "anonymous") == $runs
    and calls($requests; "POST"; "/api/notify"; "incorrect") == $runs
    and calls($requests; "POST"; "/api/notify"; "correct") == 0
  ' "${FETCH_LOG}" >/dev/null || \
    die "Panel authentication fixture did not observe the complete request matrix"
}

UNTRUSTED_TEMP_LOG="${STATE_DIR}/untrusted-temp-parent.log"
UNTRUSTED_TEMP_ACCEPTED=false
if run_publish false "${UNTRUSTED_TEMP_LOG}" false false \
  "${UNTRUSTED_TEMP_PARENT}" true false; then
  UNTRUSTED_TEMP_ACCEPTED=true
fi
[[ "${UNTRUSTED_TEMP_ACCEPTED}" == false ]] || \
  die "publisher accepted an attacker-owned sticky temporary parent"
grep -q 'release temporary parent is not a trusted sticky directory' \
  "${UNTRUSTED_TEMP_LOG}" || \
  die "publisher did not explain the untrusted temporary parent rejection"
[[ -z "$(/bin/ls -A "${UNTRUSTED_TEMP_PARENT}")" \
  && ! -s "${VERIFIER_CALL_LOG}" && ! -s "${GATE_LOG}" \
  && ! -s "${RELEASE_CREATE_LOG}" && ! -s "${EVENT_LOG}" ]] || \
  die "untrusted temporary parent reached temp creation, verification, gate, or Release"

SUCCESS_LOG="${STATE_DIR}/success.log"
run_publish false "${SUCCESS_LOG}" || {
  /bin/cat "${SUCCESS_LOG}" >&2
  die "isolated verifier-to-release success chain failed"
}

[[ "$(wc -l < "${VERIFIER_CALL_LOG}" | tr -d ' ')" == "2" ]] || \
  die "private verifier did not run exactly twice in success chain"
[[ "$(wc -l < "${GATE_LOG}" | tr -d ' ')" == "1" ]] || \
  die "private gate did not run exactly once in success chain"
[[ "$(wc -l < "${RELEASE_CREATE_LOG}" | tr -d ' ')" == "1" ]] || \
  die "fake Release creation did not run exactly once"
[[ "$(wc -l < "${METADATA_CAPTURE_LOG}" | tr -d ' ')" == "3" ]] || \
  die "success chain did not capture remote metadata exactly three times"
[[ "$(tr '\n' ' ' < "${EVENT_LOG}")" == "verifier gate verifier release " ]] || \
  die "success chain did not preserve verifier/gate/reverification/release ordering"
[[ "$(wc -l < "${GATE_EXEC_PATH_LOG}" | tr -d ' ')" == "1" ]] || \
  die "success chain did not execute exactly one gate snapshot"
GATE_EXECUTED_PATH="$(/usr/bin/tail -n 1 "${GATE_EXEC_PATH_LOG}")"
[[ "${GATE_EXECUTED_PATH}" != "${GATE_PROGRAM}" \
  && "${GATE_EXECUTED_PATH}" == "./private-release-gate.cjs" ]] || \
  die "publisher did not execute the private gate snapshot by inode-bound relative path"
[[ -s "${VERIFIER_CAPTURE}" && -s "${GATE_ENV_CAPTURE}" ]] || \
  die "success chain did not capture verifier output and gate environment"
grep -q 'published and verified GitHub stable Release v1.2.3' "${SUCCESS_LOG}" || \
  die "publisher did not finish post-publish verification"
assert_fetch_matrix 2

jq -e -s \
  --arg verifierReportSha256 "$(sha256_file "${VERIFIER_CAPTURE}")" \
  '.[0] as $report | .[1] as $environment
    | $report.schemaVersion == 2
    and $report.passed == true
    and $report.bindings.catalogAuthorityType == "github"
    and $report.bindings.panelSettingsPresent == false
    and $report.checks.incorrectBearerDenied == true
    and $report.checks.panelBootstrapPublic == true
    and $report.checks.runtimeProvenanceRechecked == true
    and $environment.AUTOFORM_RELEASE_PRIVATE_EVIDENCE_VERIFIER_SHA256
      == $report.verifierSha256
    and $environment.AUTOFORM_RELEASE_PRIVATE_EVIDENCE_REPORT_SHA256
      == $verifierReportSha256
    and $environment.AUTOFORM_RELEASE_PRIVATE_MIGRATION_REPORT_SHA256
      == $report.bindings.migrationReportSha256
    and $environment.AUTOFORM_RELEASE_PRIVATE_PANEL_CONFIG_SHA256
      == $report.bindings.panelConfigSha256
    and $environment.AUTOFORM_RELEASE_PRIVATE_PANEL_CATALOG_SHA256
      == $report.bindings.panelCatalogSha256
    and $environment.AUTOFORM_RELEASE_PRIVATE_PANEL_PAIR_SHA256
      == $report.bindings.panelPairSha256
    and $environment.AUTOFORM_RELEASE_PRIVATE_DEPLOYMENT_EVIDENCE_SHA256
      == $report.bindings.deploymentEvidenceSha256
    and $environment.AUTOFORM_RELEASE_PRIVATE_CATALOG_VERSION
      == ($report.bindings.catalogVersion | tostring)
    and $environment.AUTOFORM_RELEASE_PRIVATE_PANEL_WORKER_VERSION_ID
      == $report.bindings.panelWorkerVersionId
    and $environment.AUTOFORM_RELEASE_PRIVATE_CATALOG_AUTHORITY_TYPE
      == $report.bindings.catalogAuthorityType
    and $environment.AUTOFORM_RELEASE_PRIVATE_CATALOG_AUTHORITY_IDENTITY_SHA256
      == $report.bindings.catalogAuthorityIdentitySha256
    and $environment.AUTOFORM_RELEASE_PRIVATE_CATALOG_AUTHORITY_REVISION
      == $report.bindings.catalogAuthorityRevision
    and $environment.AUTOFORM_RELEASE_PRIVATE_CATALOG_AUTHORITY_WORKER_BINDING_SHA256
      == $report.bindings.catalogAuthorityWorkerBindingSha256
    and $environment.AUTOFORM_RELEASE_PRIVATE_CATALOG_MANIFEST_SHA256
      == $report.bindings.catalogManifestSha256
    and $environment.AUTOFORM_RELEASE_PRIVATE_PANEL_SETTINGS_PRESENT
      == ($report.bindings.panelSettingsPresent | tostring)
    and $environment.AUTOFORM_RELEASE_PRIVATE_PANEL_SETTINGS_SHA256
      == $report.bindings.panelSettingsSha256
    and $environment.AUTOFORM_RELEASE_PRIVATE_GATE_SHA256
      == $report.bindings.privateGateSha256' \
  "${VERIFIER_CAPTURE}" "${GATE_ENV_CAPTURE}" >/dev/null || \
  die "publisher environment was not an exact projection of verifier output"

[[ "$(sort "${ASSET_DOWNLOAD_LOG}" | tr '\n' ' ')" == "101 102 103 " ]] || \
  die "post-publish verification did not download exactly three fixture assets"
if grep -Fq "${CATALOG_READ_KEY}" "${SUCCESS_LOG}" "${VERIFIER_CAPTURE}" \
  "${GATE_ENV_CAPTURE}" "${GH_COMMAND_LOG}"; then
  die "private catalog key leaked into a success-chain log or report"
fi

BRANCH_METADATA_BACKUP="${STATE_DIR}/branches-api.backup.json"
cp "${HISTORY_BRANCHES_API_FIXTURE}" "${BRANCH_METADATA_BACKUP}"
POST_GATE_CONTEXT_TOCTOU_LOG="${STATE_DIR}/post-gate-context-toctou.log"
if AUTOFORM_CHAIN_MUTATE_REF_CONTEXT_AFTER_GATE=true \
  run_publish false "${POST_GATE_CONTEXT_TOCTOU_LOG}"; then
  die "publisher accepted a required-status context changing after the private gate"
fi
grep -Eq 'public file audit selected the wrong bytes|public commit/repository/ref/status/Release metadata changed after private attestation' \
  "${POST_GATE_CONTEXT_TOCTOU_LOG}" || \
  die "publisher did not detect a post-gate required-status context change"
[[ "$(wc -l < "${VERIFIER_CALL_LOG}" | tr -d ' ')" == "1" \
  && "$(wc -l < "${GATE_LOG}" | tr -d ' ')" == "1" \
  && "$(wc -l < "${METADATA_CAPTURE_LOG}" | tr -d ' ')" == "2" \
  && ! -s "${RELEASE_CREATE_LOG}" ]] || \
  die "post-gate context TOCTOU reached reverification or Release creation"
mv "${BRANCH_METADATA_BACKUP}" "${HISTORY_BRANCHES_API_FIXTURE}"

REPOSITORY_METADATA_BACKUP="${STATE_DIR}/repository-api.backup.json"
cp "${HISTORY_REPOSITORY_API_FIXTURE}" "${REPOSITORY_METADATA_BACKUP}"
REVERIFY_METADATA_TOCTOU_LOG="${STATE_DIR}/reverify-metadata-toctou.log"
if AUTOFORM_CHAIN_MUTATE_METADATA_DURING_REVERIFY=true \
  run_publish false "${REVERIFY_METADATA_TOCTOU_LOG}"; then
  die "publisher accepted remote metadata changing during private reverification"
fi
grep -q 'source repository identity or selected public metadata changed during audit' \
  "${REVERIFY_METADATA_TOCTOU_LOG}" || \
  die "publisher did not re-audit remote metadata after private reverification"
[[ "$(wc -l < "${VERIFIER_CALL_LOG}" | tr -d ' ')" == "2" \
  && "$(wc -l < "${GATE_LOG}" | tr -d ' ')" == "1" \
  && "$(wc -l < "${METADATA_CAPTURE_LOG}" | tr -d ' ')" == "2" \
  && ! -s "${RELEASE_CREATE_LOG}" ]] || \
  die "remote metadata TOCTOU did not stop after reverification and before Release creation"
mv "${REPOSITORY_METADATA_BACKUP}" "${HISTORY_REPOSITORY_API_FIXTURE}"

MIGRATION_REPORT_BACKUP="${STATE_DIR}/private-migration-report.backup.json"
cp "${PRIVATE_MIGRATION_REPORT}" "${MIGRATION_REPORT_BACKUP}"
jq '.publicHistoryReleaseInputSha256 = ("0" * 64)' \
  "${PRIVATE_MIGRATION_REPORT}" > "${STATE_DIR}/private-migration-report.mismatch.json"
mv "${STATE_DIR}/private-migration-report.mismatch.json" "${PRIVATE_MIGRATION_REPORT}"
chmod 600 "${PRIVATE_MIGRATION_REPORT}"
METADATA_BINDING_MISMATCH_LOG="${STATE_DIR}/metadata-binding-mismatch.log"
if run_publish false "${METADATA_BINDING_MISMATCH_LOG}"; then
  die "publisher accepted a private full-history audit for another Release envelope"
fi
grep -q 'trusted private evidence verification failed' \
  "${METADATA_BINDING_MISMATCH_LOG}" || \
  die "publisher did not explain the private/public Release metadata mismatch"
[[ ! -s "${GATE_LOG}" && ! -s "${RELEASE_CREATE_LOG}" ]] || \
  die "private/public Release metadata mismatch reached the gate or Release creation"
mv "${MIGRATION_REPORT_BACKUP}" "${PRIVATE_MIGRATION_REPORT}"
chmod 600 "${PRIVATE_MIGRATION_REPORT}"

cp "${PRIVATE_MIGRATION_REPORT}" "${MIGRATION_REPORT_BACKUP}"
jq '.publicHistoryRemoteRefsInputSha256 = ("0" * 64)' \
  "${PRIVATE_MIGRATION_REPORT}" > "${STATE_DIR}/private-migration-report.refs-mismatch.json"
mv "${STATE_DIR}/private-migration-report.refs-mismatch.json" \
  "${PRIVATE_MIGRATION_REPORT}"
chmod 600 "${PRIVATE_MIGRATION_REPORT}"
REF_BINDING_MISMATCH_LOG="${STATE_DIR}/ref-binding-mismatch.log"
if run_publish false "${REF_BINDING_MISMATCH_LOG}"; then
  die "publisher accepted a private full-history audit for another ref envelope"
fi
grep -q 'trusted private evidence verification failed' \
  "${REF_BINDING_MISMATCH_LOG}" || \
  die "publisher did not explain the private/public ref metadata mismatch"
[[ ! -s "${GATE_LOG}" && ! -s "${RELEASE_CREATE_LOG}" ]] || \
  die "private/public ref metadata mismatch reached the gate or Release creation"
mv "${MIGRATION_REPORT_BACKUP}" "${PRIVATE_MIGRATION_REPORT}"
chmod 600 "${PRIVATE_MIGRATION_REPORT}"

SNAPSHOT_PATH_SWAP_LOG="${STATE_DIR}/snapshot-path-swap.log"
run_publish false "${SNAPSHOT_PATH_SWAP_LOG}" false true || {
  /bin/cat "${SNAPSHOT_PATH_SWAP_LOG}" >&2
  die "inode-bound gate execution failed after snapshot pathname replacement"
}
[[ "$(tr '\n' ' ' < "${EVENT_LOG}")" == "verifier gate verifier release " \
  && "$(wc -l < "${RELEASE_CREATE_LOG}" | tr -d ' ')" == "1" \
  && ! -e "${MALICIOUS_GATE_MARKER}" ]] || \
  die "snapshot pathname replacement redirected execution or blocked the trusted inode"
assert_fetch_matrix 2

TAMPER_LOG="${STATE_DIR}/tampered-attestation.log"
if run_publish true "${TAMPER_LOG}"; then
  die "publisher accepted a tampered private gate attestation"
fi
grep -q 'private gate attestation is missing an exact binding' "${TAMPER_LOG}" || \
  die "publisher did not explain the tampered attestation rejection"
[[ "$(wc -l < "${VERIFIER_CALL_LOG}" | tr -d ' ')" == "1" ]] || \
  die "private verifier did not run exactly once before tampered attestation rejection"
[[ "$(wc -l < "${GATE_LOG}" | tr -d ' ')" == "1" ]] || \
  die "private gate did not run exactly once for tampered attestation"
[[ ! -s "${RELEASE_CREATE_LOG}" ]] || \
  die "tampered attestation reached fake Release creation"
[[ "$(tr '\n' ' ' < "${EVENT_LOG}")" == "verifier gate " ]] || \
  die "tampered attestation did not stop after verifier and gate"
assert_fetch_matrix 1

if grep -Fq "${CATALOG_READ_KEY}" "${TAMPER_LOG}" "${VERIFIER_CAPTURE}" \
  "${GATE_ENV_CAPTURE}" "${GH_COMMAND_LOG}"; then
  die "private catalog key leaked during tampered-attestation test"
fi

ORIGINAL_GATE_DRIFT_LOG="${STATE_DIR}/original-gate-drift.log"
ORIGINAL_GATE_DRIFT_ACCEPTED=false
if run_publish false "${ORIGINAL_GATE_DRIFT_LOG}" true false; then
  ORIGINAL_GATE_DRIFT_ACCEPTED=true
fi
cp "${GATE_PROGRAM_BACKUP}" "${GATE_PROGRAM}"
chmod 700 "${GATE_PROGRAM}"
[[ "$(sha256_file "${GATE_PROGRAM}")" == "${GATE_SHA256}" ]] || \
  die "failed to restore the trusted gate fixture after drift injection"
[[ "${ORIGINAL_GATE_DRIFT_ACCEPTED}" == false ]] || \
  die "publisher accepted an original gate pathname replacement"
grep -q 'private gate path changed after verification' "${ORIGINAL_GATE_DRIFT_LOG}" || \
  die "publisher did not explain the original gate pathname drift"
[[ "$(wc -l < "${VERIFIER_CALL_LOG}" | tr -d ' ')" == "1" \
  && "$(wc -l < "${GATE_LOG}" | tr -d ' ')" == "1" \
  && ! -s "${RELEASE_CREATE_LOG}" \
  && "$(tr '\n' ' ' < "${EVENT_LOG}")" == "verifier gate " \
  && ! -e "${MALICIOUS_GATE_MARKER}" ]] || \
  die "original gate drift reached reverification, Release creation, or replacement execution"

for INSECURE_GATE_MODE in 720 702; do
  INSECURE_GATE_LOG="${STATE_DIR}/insecure-gate-mode-${INSECURE_GATE_MODE}.log"
  chmod "${INSECURE_GATE_MODE}" "${GATE_PROGRAM}"
  INSECURE_GATE_ACCEPTED=false
  if run_publish false "${INSECURE_GATE_LOG}"; then
    INSECURE_GATE_ACCEPTED=true
  fi
  chmod 700 "${GATE_PROGRAM}"
  [[ "${INSECURE_GATE_ACCEPTED}" == false ]] || \
    die "publisher accepted mode-${INSECURE_GATE_MODE} private gate"
  grep -q 'private gate must not be writable by group or others' "${INSECURE_GATE_LOG}" || \
    die "publisher did not explain the mode-${INSECURE_GATE_MODE} gate rejection"
  [[ ! -s "${VERIFIER_CALL_LOG}" && ! -s "${GATE_LOG}" \
    && ! -s "${RELEASE_CREATE_LOG}" && ! -s "${EVENT_LOG}" ]] || \
    die "mode-${INSECURE_GATE_MODE} gate reached verification, gate execution, or Release creation"
done

mkdir "${CLEANUP_SENTINEL_DIR}"
chmod 700 "${CLEANUP_SENTINEL_DIR}"
printf 'keep\n' > "${CLEANUP_SENTINEL_DIR}/sentinel.txt"
CLEANUP_DRIFT_LOG="${STATE_DIR}/cleanup-path-drift.log"
CLEANUP_DRIFT_ACCEPTED=false
if run_publish false "${CLEANUP_DRIFT_LOG}" false false \
  "${CHAIN_TEMP_PARENT}" false true; then
  CLEANUP_DRIFT_ACCEPTED=true
fi
[[ "${CLEANUP_DRIFT_ACCEPTED}" == false ]] || \
  die "publisher reported success after its audit cleanup pathname drifted"
grep -q 'refusing to remove drifted public audit temporary directory' \
  "${CLEANUP_DRIFT_LOG}" || \
  die "publisher did not refuse cleanup of a replacement directory"
[[ "$(wc -l < "${CLEANUP_INJECTION_LOG}" | tr -d ' ')" == "1" ]] || \
  die "cleanup drift fixture did not replace exactly one audit pathname"
DRIFTED_AUDIT_PATH="$(/usr/bin/tail -n 1 "${CLEANUP_INJECTION_LOG}")"
[[ -f "${DRIFTED_AUDIT_PATH}/sentinel.txt" \
  && "$(< "${DRIFTED_AUDIT_PATH}/sentinel.txt")" == "keep" ]] || \
  die "identity-bound cleanup deleted or changed the replacement sentinel"

printf 'private-release-chain-selftest: PASS (isolated fixtures; expected network edges mocked)\n'
