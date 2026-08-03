#!/usr/bin/env bash

set -euo pipefail
umask 077

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)"
PUBLISH_SCRIPT="${SCRIPT_DIR}/publish-release.sh"
RELEASE_SCRIPT="${SCRIPT_DIR}/release.sh"
PUBLIC_AUDIT_SCANNER="${SCRIPT_DIR}/public-surface-audit.mjs"
GITHUB_RELEASE_AUDIT_NORMALIZER="${SCRIPT_DIR}/normalize-github-releases-for-audit.mjs"
APK_SOURCE_PROVENANCE_VERIFIER="${SCRIPT_DIR}/verify-apk-third-party-sources.mjs"

die() {
  printf 'release-workflow-selftest: %s\n' "$*" >&2
  exit 1
}

command -v jq >/dev/null 2>&1 || die "jq is required"
REAL_SHASUM="$(command -v shasum 2>/dev/null || true)"
REAL_SHA256SUM="$(command -v sha256sum 2>/dev/null || true)"
REAL_NODE="$(command -v node 2>/dev/null || true)"
[[ -n "${REAL_SHASUM}" || -n "${REAL_SHA256SUM}" ]] || die "a real SHA-256 tool is required"
[[ -n "${REAL_NODE}" ]] || die "a real Node.js runtime is required"

sha256_file() {
  local file="$1"
  if command -v shasum >/dev/null 2>&1; then
    shasum -a 256 "${file}" | awk '{ print $1 }'
  elif command -v sha256sum >/dev/null 2>&1; then
    sha256sum "${file}" | awk '{ print $1 }'
  else
    die "shasum or sha256sum is required"
  fi
}

FIXTURE_ROOT="$(mktemp -d "${TMPDIR:-/tmp}/autoform-release-selftest.XXXXXX")"
FIXTURE_ROOT="$(cd "${FIXTURE_ROOT}" && pwd -P)"
cleanup() {
  local status=$?
  trap - EXIT HUP INT TERM
  rm -rf "${FIXTURE_ROOT}"
  exit "${status}"
}
trap cleanup EXIT HUP INT TERM

CONTROLLED_RECOVERY_SELFTEST_LOG="${FIXTURE_ROOT}/controlled-recovery-selftest.log"
if ! "${REAL_NODE}" --test "${SCRIPT_DIR}/controlled-recovery-attestation-selftest.mjs" \
  >"${CONTROLLED_RECOVERY_SELFTEST_LOG}" 2>&1; then
  /bin/cat "${CONTROLLED_RECOVERY_SELFTEST_LOG}" >&2
  die "controlled-recovery attestation selftest failed"
fi

PRIVATE_EVIDENCE_SELFTEST_LOG="${FIXTURE_ROOT}/private-evidence-selftest.log"
if ! "${REAL_NODE}" --test "${SCRIPT_DIR}/verify-private-release-evidence-selftest.mjs" \
  >"${PRIVATE_EVIDENCE_SELFTEST_LOG}" 2>&1; then
  /bin/cat "${PRIVATE_EVIDENCE_SELFTEST_LOG}" >&2
  die "private release evidence verifier selftest failed"
fi

PRIVATE_RELEASE_CHAIN_SELFTEST_LOG="${FIXTURE_ROOT}/private-release-chain-selftest.log"
if ! bash "${SCRIPT_DIR}/private-release-chain-selftest.sh" \
  >"${PRIVATE_RELEASE_CHAIN_SELFTEST_LOG}" 2>&1; then
  /bin/cat "${PRIVATE_RELEASE_CHAIN_SELFTEST_LOG}" >&2
  die "isolated private verifier-to-attestation release chain selftest failed"
fi

GITHUB_METADATA_NORMALIZER_SELFTEST_LOG="${FIXTURE_ROOT}/github-metadata-normalizer-selftest.log"
if ! "${REAL_NODE}" --test \
  "${SCRIPT_DIR}/normalize-github-releases-for-audit-selftest.mjs" \
  >"${GITHUB_METADATA_NORMALIZER_SELFTEST_LOG}" 2>&1; then
  /bin/cat "${GITHUB_METADATA_NORMALIZER_SELFTEST_LOG}" >&2
  die "GitHub public metadata normalizer selftest failed"
fi

BIN_DIR="${FIXTURE_ROOT}/bin"
CANDIDATE_DIR="${FIXTURE_ROOT}/candidate"
PREVIOUS_APK="${FIXTURE_ROOT}/previous.apk"
GATE_PROGRAM="${FIXTURE_ROOT}/private-release-gate"
PRIVATE_MIGRATION_REPORT="${FIXTURE_ROOT}/private-migration-report.json"
PANEL_CONFIG_EVIDENCE="${FIXTURE_ROOT}/panel-config.json"
PANEL_CATALOG_EVIDENCE="${FIXTURE_ROOT}/panel-catalog.json"
PANEL_MANIFEST_EVIDENCE="${FIXTURE_ROOT}/panel-manifest.json"
PRIVATE_DEPLOYMENT_EVIDENCE="${FIXTURE_ROOT}/private-deployment-evidence.json"
PRIVATE_WORDLIST="${FIXTURE_ROOT}/private-wordlist.json"
GH_LOG="${FIXTURE_ROOT}/gh.log"
GH_RELEASE_STATE="${FIXTURE_ROOT}/gh-release-state"
GH_ASSET_DOWNLOAD_LOG="${FIXTURE_ROOT}/gh-asset-download.log"
GATE_LOG="${FIXTURE_ROOT}/gate.log"
SCANNER_HASH_COUNT="${FIXTURE_ROOT}/scanner-hash-count"
SOURCE_PROVENANCE_REPORT="${FIXTURE_ROOT}/source-provenance-report.json"
STALE_SOURCE_PROVENANCE_REPORT="${FIXTURE_ROOT}/stale-source-provenance-report.json"
TREE_REPORT="${FIXTURE_ROOT}/tree-report.json"
STALE_TREE_REPORT="${FIXTURE_ROOT}/stale-tree-report.json"
WORKTREE_REPORT="${FIXTURE_ROOT}/worktree-report.json"
APK_REPORT="${FIXTURE_ROOT}/apk-report.json"
UPDATE_REPORT="${FIXTURE_ROOT}/update-report.json"
NOTES_REPORT="${FIXTURE_ROOT}/notes-report.json"
MANIFEST_REPORT="${FIXTURE_ROOT}/manifest-report.json"
STALE_UPDATE_REPORT="${FIXTURE_ROOT}/stale-update-report.json"
STALE_MANIFEST_REPORT="${FIXTURE_ROOT}/stale-manifest-report.json"
HISTORY_COMMIT_OBJECT_FIXTURE="${FIXTURE_ROOT}/source-commit-object.fixture"
HISTORY_REMOTE_REFS_FIXTURE="${FIXTURE_ROOT}/remote-refs.fixture"
HISTORY_BRANCHES_API_FIXTURE="${FIXTURE_ROOT}/branches-api.fixture.json"
HISTORY_TAGS_API_FIXTURE="${FIXTURE_ROOT}/tags-api.fixture.json"
HISTORY_REF_API_FIXTURE="${FIXTURE_ROOT}/refs-api.fixture.json"
HISTORY_RELEASES_API_FIXTURE="${FIXTURE_ROOT}/releases-api.fixture.json"
HISTORY_RELEASE_AUTHOR_MARKER_API_FIXTURE="${FIXTURE_ROOT}/release-author-marker-api.fixture.json"
HISTORY_RELEASE_UPLOADER_MARKER_API_FIXTURE="${FIXTURE_ROOT}/release-uploader-marker-api.fixture.json"
HISTORY_RELEASES_FIXTURE="${FIXTURE_ROOT}/releases.fixture.json"
HISTORY_REPOSITORY_API_FIXTURE="${FIXTURE_ROOT}/repository-api.fixture.json"
HISTORY_COMMIT_REPORT="${FIXTURE_ROOT}/source-commit-object-report.json"
HISTORY_REMOTE_REFS_REPORT="${FIXTURE_ROOT}/remote-refs-report.json"
HISTORY_REF_API_REPORT="${FIXTURE_ROOT}/refs-api-report.json"
HISTORY_RELEASES_REPORT="${FIXTURE_ROOT}/releases-report.json"
STALE_HISTORY_COMMIT_REPORT="${FIXTURE_ROOT}/stale-source-commit-object-report.json"
mkdir -p "${BIN_DIR}" "${CANDIDATE_DIR}"
: > "${GH_LOG}"
: > "${GH_ASSET_DOWNLOAD_LOG}"
: > "${GATE_LOG}"
printf '["fictional-private-selftest-marker"]\n' > "${PRIVATE_WORDLIST}"

SOURCE_COMMIT="aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
SIGNER_SHA256="bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
PACKAGE_NAME="com.example.autoform"
VERSION_NAME="1.2.3"
VERSION_CODE="123"
PREVIOUS_VERSION_NAME="1.2.2"
PREVIOUS_VERSION_CODE="122"
TREE_OID="cccccccccccccccccccccccccccccccccccccccc"
SCANNER_BLOB_OID="3333333333333333333333333333333333333333"
NORMALIZER_BLOB_OID="8888888888888888888888888888888888888888"
SOURCE_VERIFIER_BLOB_OID="4444444444444444444444444444444444444444"
HISTORICAL_CONTRACT_BLOB_OID="9999999999999999999999999999999999999999"
PRIVATE_EVIDENCE_VERIFIER_BLOB_OID="6666666666666666666666666666666666666666"
PRIVATE_GATE_POLICY_BLOB_OID="7777777777777777777777777777777777777777"
POLICY_SHA256="dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd"
TREE_INPUT_SHA256="eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee"
STALE_TREE_INPUT_SHA256="ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff"
WORKTREE_INPUT_SHA256="1111111111111111111111111111111111111111111111111111111111111111"
APK_ZIP_INPUT_SHA256="2222222222222222222222222222222222222222222222222222222222222222"
APK_FILE="autoform-kit-${VERSION_NAME}.apk"
APK_PATH="${CANDIDATE_DIR}/${APK_FILE}"
UPDATE_PATH="${CANDIDATE_DIR}/update.json"
NOTES_PATH="${CANDIDATE_DIR}/release-notes.txt"
MANIFEST_PATH="${CANDIDATE_DIR}/candidate-manifest.json"

printf 'tree %s\n\nautoform release workflow selftest\n' "${TREE_OID}" \
  > "${HISTORY_COMMIT_OBJECT_FIXTURE}"
printf '%s\tHEAD\n%s\trefs/heads/main\n' \
  "${SOURCE_COMMIT}" "${SOURCE_COMMIT}" > "${HISTORY_REMOTE_REFS_FIXTURE}"
q_branch_commit_url="https://api.github.com/repos/example/autoform-kit/commits/${SOURCE_COMMIT}"
jq -nS \
  --arg sourceCommit "${SOURCE_COMMIT}" \
  --arg commitUrl "${q_branch_commit_url}" \
  '[[{name:"main",commit:{sha:$sourceCommit,url:$commitUrl},protected:false}]]' \
  > "${HISTORY_BRANCHES_API_FIXTURE}"
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

write_marker_release_fixture() {
  local marker_location="$1"
  local output="$2"
  jq -nS --arg markerLocation "${marker_location}" '
    def user($login; $id): {
      avatar_url:("https://avatars.githubusercontent.com/u/" + ($id|tostring) + "?v=4"),
      events_url:("https://api.github.com/users/" + $login + "/events{/privacy}"),
      followers_url:("https://api.github.com/users/" + $login + "/followers"),
      following_url:("https://api.github.com/users/" + $login + "/following{/other_user}"),
      gists_url:("https://api.github.com/users/" + $login + "/gists{/gist_id}"),
      gravatar_id:"", html_url:("https://github.com/" + $login), id:$id,
      login:$login, node_id:("U_fixture_" + ($id|tostring)),
      organizations_url:("https://api.github.com/users/" + $login + "/orgs"),
      received_events_url:("https://api.github.com/users/" + $login + "/received_events"),
      repos_url:("https://api.github.com/users/" + $login + "/repos"), site_admin:false,
      starred_url:("https://api.github.com/users/" + $login + "/starred{/owner}{/repo}"),
      subscriptions_url:("https://api.github.com/users/" + $login + "/subscriptions"),
      type:"User", url:("https://api.github.com/users/" + $login), user_view_type:"public"
    };
    (user("example";101)) as $owner |
    (user("fictional-private-selftest-marker";102)) as $marker |
    # Keep generic timestamp member names split in fixture source because a broad
    # deployment wordlist can otherwise mistake those API keys for private values.
    [[{
      assets:[{
        browser_download_url:"https://github.com/example/autoform-kit/releases/download/v0.9.0/fixture.apk",
        content_type:"application/vnd.android.package-archive",
        (("created" + "_at")):"2001-02-03T04:05:06Z",
        digest:("sha256:" + ("b" * 64)),
        download_count:0, id:401, label:"Android package", name:"fixture.apk",
        node_id:"RA_fixture_401", size:123, state:"uploaded",
        (("updated" + "_at")):"2001-02-03T04:05:07Z",
        uploader:(if $markerLocation == "uploader" then $marker else $owner end),
        url:"https://api.github.com/repos/example/autoform-kit/releases/assets/401"
      }],
      assets_url:"https://api.github.com/repos/example/autoform-kit/releases/301/assets",
      author:(if $markerLocation == "author" then $marker else $owner end),
      body:"Generic release notes",
      (("created" + "_at")):"2001-02-03T04:05:06Z",
      draft:false, html_url:"https://github.com/example/autoform-kit/releases/tag/v0.9.0",
      id:301, immutable:false, name:"autoform-kit 0.9.0", node_id:"R_fixture_301",
      prerelease:false, published_at:"2001-02-03T04:05:08Z", tag_name:"v0.9.0",
      tarball_url:"https://api.github.com/repos/example/autoform-kit/tarball/v0.9.0",
      target_commitish:"main",
      (("updated" + "_at")):"2001-02-03T04:05:08Z",
      upload_url:"https://uploads.github.com/repos/example/autoform-kit/releases/301/assets{?name,label}",
      url:"https://api.github.com/repos/example/autoform-kit/releases/301",
      zipball_url:"https://api.github.com/repos/example/autoform-kit/zipball/v0.9.0"
    }]]
  ' > "${output}"
}
write_marker_release_fixture author "${HISTORY_RELEASE_AUTHOR_MARKER_API_FIXTURE}"
write_marker_release_fixture uploader "${HISTORY_RELEASE_UPLOADER_MARKER_API_FIXTURE}"

"${REAL_NODE}" "${GITHUB_RELEASE_AUDIT_NORMALIZER}" \
  --repo example/autoform-kit \
  --repository-input "${HISTORY_REPOSITORY_API_FIXTURE}" \
  --remote-refs-input "${HISTORY_REMOTE_REFS_FIXTURE}" \
  > "${FIXTURE_ROOT}/remote-refs.normalized.fixture.json"
mv "${FIXTURE_ROOT}/remote-refs.normalized.fixture.json" "${HISTORY_REMOTE_REFS_FIXTURE}.normalized"
mv "${HISTORY_REMOTE_REFS_FIXTURE}.normalized" "${HISTORY_REMOTE_REFS_FIXTURE}"
"${REAL_NODE}" "${GITHUB_RELEASE_AUDIT_NORMALIZER}" \
  --repo example/autoform-kit \
  --repository-input "${HISTORY_REPOSITORY_API_FIXTURE}" \
  --branches-input "${HISTORY_BRANCHES_API_FIXTURE}" \
  --tags-input "${HISTORY_TAGS_API_FIXTURE}" > "${HISTORY_REF_API_FIXTURE}"
"${REAL_NODE}" "${GITHUB_RELEASE_AUDIT_NORMALIZER}" \
  --repo example/autoform-kit \
  --repository-input "${HISTORY_REPOSITORY_API_FIXTURE}" \
  --input "${HISTORY_RELEASES_API_FIXTURE}" > "${HISTORY_RELEASES_FIXTURE}"

printf 'fixture candidate apk bytes\n' > "${APK_PATH}"
printf 'fixture previous apk bytes\n' > "${PREVIOUS_APK}"
printf 'Fictional release notes\n' > "${NOTES_PATH}"
APK_SHA256="$(sha256_file "${APK_PATH}")"
PREVIOUS_APK_SHA256="$(sha256_file "${PREVIOUS_APK}")"
PUBLIC_AUDIT_SCANNER_SHA256="$(sha256_file "${PUBLIC_AUDIT_SCANNER}")"
PUBLIC_SOURCE_VERIFIER_SHA256="$(sha256_file "${APK_SOURCE_PROVENANCE_VERIFIER}")"
PUBLIC_THIRD_PARTY_POLICY_SHA256="$(sha256_file "${SCRIPT_DIR}/apk-third-party-components.json")"
PUBLIC_RUNTIME_LOCK_SHA256="$(sha256_file "${SCRIPT_DIR}/android-runtime-dependencies.lock.json")"

write_source_provenance_report() {
  local apk_sha="$1"
  local source_artifact_count="$2"
  local output="$3"
  local base="${output}.base"
  local canonical report_sha

  jq -nS \
    --arg verifier "${PUBLIC_SOURCE_VERIFIER_SHA256}" \
    --arg policy "${PUBLIC_THIRD_PARTY_POLICY_SHA256}" \
    --arg apk "${apk_sha}" \
    --argjson sourceArtifactCount "${source_artifact_count}" \
    '{
      schemaVersion: 1,
      passed: true,
      verifierSha256: $verifier,
      policySha256: $policy,
      apkSha256: $apk,
      profileId: "fixture-profile",
      sourceArtifactCount: $sourceArtifactCount,
      sourceEntryCount: $sourceArtifactCount,
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

write_source_provenance_report "${APK_SHA256}" 1 "${SOURCE_PROVENANCE_REPORT}"
write_source_provenance_report \
  "9999999999999999999999999999999999999999999999999999999999999999" \
  2 \
  "${STALE_SOURCE_PROVENANCE_REPORT}"
SOURCE_PROVENANCE_REPORT_SHA256="$(
  jq -er '.reportSha256' "${SOURCE_PROVENANCE_REPORT}"
)"

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
          schemaVersion: 1,
          scannerSha256: $scanner,
          policySha256: $policy,
          input: {
            mode: "git-tree",
            selection: "exact-git-tree",
            gitTreeOid: $tree,
            sha256: $input,
            entryCount: 3
          },
          privatePolicy: {applied: true, wordlistCount: 1, termCount: 1},
          summary: {passed: true, findingCount: 0},
          findings: []
        }' > "${base}"
      ;;
    worktree)
      jq -nS \
        --arg scanner "${PUBLIC_AUDIT_SCANNER_SHA256}" \
        --arg policy "${POLICY_SHA256}" \
        --arg input "${input_sha}" \
        '{
          schemaVersion: 1,
          scannerSha256: $scanner,
          policySha256: $policy,
          input: {
            mode: "worktree",
            selection: "tracked-and-untracked-nonignored-present-files",
            sha256: $input,
            entryCount: 3
          },
          privatePolicy: {applied: true, wordlistCount: 1, termCount: 1},
          summary: {passed: true, findingCount: 0},
          findings: []
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
          schemaVersion: 1,
          scannerSha256: $scanner,
          policySha256: $policy,
          input: {
            mode: "apk",
            selection: "exact-apk-container-and-zip-entries",
            sha256: $input,
            zipEntryManifestSha256: $zipInput,
            entryCount: 3
          },
          privatePolicy: {applied: true, wordlistCount: 1, termCount: 1},
          summary: {passed: true, findingCount: 0},
          findings: []
      }' > "${base}"
      ;;
    file)
      jq -nS \
        --arg scanner "${PUBLIC_AUDIT_SCANNER_SHA256}" \
        --arg policy "${POLICY_SHA256}" \
        --arg input "${input_sha}" \
        '{
          schemaVersion: 1,
          scannerSha256: $scanner,
          policySha256: $policy,
          input: {
            mode: "file",
            selection: "exact-regular-file",
            sha256: $input,
            entryCount: 1
          },
          privatePolicy: {applied: true, wordlistCount: 1, termCount: 1},
          summary: {passed: true, findingCount: 0},
          findings: []
        }' > "${base}"
      ;;
    *)
      die "unsupported fixture audit mode"
      ;;
  esac
  jq -S \
    --arg manifestSha256 "${PUBLIC_THIRD_PARTY_POLICY_SHA256}" \
    --argjson applied "${applied}" \
    --argjson profileId "${profile}" \
    --argjson matchedEntryCount "${matched}" \
    '. + {thirdPartyPolicy: {
      manifestSha256: $manifestSha256,
      applied: $applied,
      profileId: $profileId,
      matchedEntryCount: $matchedEntryCount,
      applicationDexPolicy: "all application-namespace type descriptors remain strictly scanned"
    }}' "${base}" > "${base}.with-policy"
  mv "${base}.with-policy" "${base}"
  canonical="$(jq -cS . "${base}")"
  report_sha="$(printf '%s' "${canonical}" | sha256_file /dev/stdin)"
  jq -S --arg reportSha256 "${report_sha}" '. + {reportSha256: $reportSha256}' \
    "${base}" > "${output}"
  rm -f "${base}"
}

write_audit_report git-tree "${TREE_INPUT_SHA256}" "${TREE_REPORT}"
write_audit_report git-tree "${STALE_TREE_INPUT_SHA256}" "${STALE_TREE_REPORT}"
write_audit_report worktree "${WORKTREE_INPUT_SHA256}" "${WORKTREE_REPORT}"
write_audit_report apk "${APK_SHA256}" "${APK_REPORT}"
HISTORY_COMMIT_INPUT_SHA256="$(sha256_file "${HISTORY_COMMIT_OBJECT_FIXTURE}")"
HISTORY_REMOTE_REFS_INPUT_SHA256="$(sha256_file "${HISTORY_REMOTE_REFS_FIXTURE}")"
HISTORY_REF_API_INPUT_SHA256="$(sha256_file "${HISTORY_REF_API_FIXTURE}")"
HISTORY_RELEASES_INPUT_SHA256="$(sha256_file "${HISTORY_RELEASES_FIXTURE}")"
HISTORY_REMOTE_REFS_IDENTITY_SHA256="$(
  jq -er '.refIdentitySha256' "${HISTORY_REMOTE_REFS_FIXTURE}"
)"
[[ "$(jq -er '.refIdentitySha256' "${HISTORY_REF_API_FIXTURE}")" \
  == "${HISTORY_REMOTE_REFS_IDENTITY_SHA256}" ]] || \
  die "fixture git and API refs do not have the same identity"
HISTORY_REMOTE_REFS_RAW_SNAPSHOT_SHA256="$(
  jq -er '.rawSnapshotSha256' "${HISTORY_REMOTE_REFS_FIXTURE}"
)"
HISTORY_PULL_REFS_IDENTITY_SHA256="$(
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
  --arg pullRefs "${HISTORY_PULL_REFS_IDENTITY_SHA256}" \
  --arg refApi "${HISTORY_REF_API_SNAPSHOT_SHA256}" \
  --arg refs "${HISTORY_REMOTE_REFS_IDENTITY_SHA256}" \
  --arg refsRaw "${HISTORY_REMOTE_REFS_RAW_SNAPSHOT_SHA256}" \
  --arg releases "${HISTORY_RELEASE_API_SNAPSHOT_SHA256}" \
  --arg repository "${HISTORY_REPOSITORY_BINDING_SHA256}" \
  '{pullRefIdentitySha256:$pullRefs, refApiSnapshotSha256:$refApi,
    refIdentitySha256:$refs, remoteRefsRawSnapshotSha256:$refsRaw,
    releaseApiSnapshotSha256:$releases, repositoryBindingSha256:$repository}')"
HISTORY_METADATA_BINDING_SHA256="$(
  printf 'autoform-kit/github-public-metadata-binding/v2\n%s' \
    "${HISTORY_METADATA_BINDING_BASE}" | sha256_file /dev/stdin
)"
write_audit_report file "${HISTORY_COMMIT_INPUT_SHA256}" "${HISTORY_COMMIT_REPORT}"
write_audit_report file "${HISTORY_REMOTE_REFS_INPUT_SHA256}" "${HISTORY_REMOTE_REFS_REPORT}"
write_audit_report file "${HISTORY_REF_API_INPUT_SHA256}" "${HISTORY_REF_API_REPORT}"
write_audit_report file "${HISTORY_RELEASES_INPUT_SHA256}" "${HISTORY_RELEASES_REPORT}"
write_audit_report file "${STALE_TREE_INPUT_SHA256}" "${STALE_HISTORY_COMMIT_REPORT}"
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
  '{
    packageName: $packageName,
    versionCode: $versionCode,
    versionName: $versionName,
    apkAsset: $apkAsset,
    sha256: $sha256,
    notes: $notes
  }' > "${UPDATE_PATH}"
UPDATE_SHA256="$(sha256_file "${UPDATE_PATH}")"
NOTES_SHA256="$(sha256_file "${NOTES_PATH}")"
write_audit_report file "${UPDATE_SHA256}" "${UPDATE_REPORT}"
write_audit_report file "${NOTES_SHA256}" "${NOTES_REPORT}"
write_audit_report file "${STALE_TREE_INPUT_SHA256}" "${STALE_UPDATE_REPORT}"
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
    schemaVersion: 2,
    tag: "v1.2.3",
    source: { commit: $sourceCommit, branch: "main", workingTreeClean: true },
    app: {
      packageName: $packageName,
      versionCode: $versionCode,
      versionName: $versionName,
      signerSha256: $signerSha256
    },
    artifacts: {
      apk: { file: $apkFile, sha256: $apkSha256 },
      update: { file: "update.json", sha256: $updateSha256 },
      notes: { file: "release-notes.txt", sha256: $notesSha256 }
    },
    previousApk: {
      sha256: $previousApkSha256,
      packageName: $packageName,
      versionCode: $previousVersionCode,
      versionName: $previousVersionName,
      signerSha256: $signerSha256
    },
    publicAudit: {
      scannerSha256: $auditScanner,
      policySha256: $auditPolicy,
      sourceTree: {
        gitTreeOid: $treeOid,
        inputSha256: $treeInput,
        reportSha256: $treeReport
      },
      worktree: {
        inputSha256: $worktreeInput,
        reportSha256: $worktreeReport
      },
      apk: {
        inputSha256: $apkSha256,
        reportSha256: $apkReport,
        zipEntryManifestSha256: $apkZipEntryManifest
      },
      thirdPartyProvenance: {
        manifestFile: "tools/apk-third-party-components.json",
        manifestSha256: $thirdPartyPolicy,
        runtimeLockFile: "tools/android-runtime-dependencies.lock.json",
        runtimeLockSha256: $runtimeLock,
        profileId: "fixture-profile",
        matchedEntryCount: 1,
        applicationDexStrict: true,
        sourceVerifierFile: "tools/verify-apk-third-party-sources.mjs",
        sourceVerifierSha256: $sourceVerifier,
        sourceReportSha256: $sourceReport,
        sourceArtifactCount: 1,
        sourceEntryCount: 1,
        mergedSourceCount: 1,
        compiledOutputCount: 2,
        dexSourceArtifactCount: 1,
        dexSourceEntryCount: 1,
        declaredDexStringCount: 1,
        sourceMatchedDexStringCount: 1,
        apkMatchedDexStringCount: 1
      },
      releaseMetadata: {
        update: {
          inputSha256: $updateSha256,
          reportSha256: $updateReport
        },
        notes: {
          inputSha256: $notesSha256,
          reportSha256: $notesReport
        }
      }
    }
  }' > "${MANIFEST_PATH}"

jq -n '{schemaVersion:2,version:42,profiles:[],settings:{}}' > "${PANEL_CATALOG_EVIDENCE}"
PANEL_CATALOG_SHA256="$(sha256_file "${PANEL_CATALOG_EVIDENCE}")"
PANEL_MANIFEST_SHA256="${PANEL_CATALOG_SHA256}"
jq -n \
  --arg catalogSha256 "${PANEL_MANIFEST_SHA256}" \
  '{
    schemaVersion:2,
    version:42,
    sha256:$catalogSha256,
    profilesUrl:"https://panel.example.invalid/catalog/form-profiles.json",
    minAppVersionCode:1,
    updatedAt:"2030-01-01T00:00:00.000Z",
    notes:"fixture"
  }' > "${PANEL_MANIFEST_EVIDENCE}"
CATALOG_READ_KEY="fictional-selftest-read-key-0000000000000000"
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
CANDIDATE_MANIFEST_SHA256="$(sha256_file "${MANIFEST_PATH}")"
PANEL_CONFIG_SHA256="$(sha256_file "${PANEL_CONFIG_EVIDENCE}")"
PANEL_PAIR_SHA256="$(printf 'AUTOFORM_KIT_PRIVATE_PANEL_PAIR_V1\n%s\n%s\n42' \
  "${PANEL_CONFIG_SHA256}" "${PANEL_CATALOG_SHA256}" | sha256_file /dev/stdin)"
jq -n \
  --arg sourceCommit "${SOURCE_COMMIT}" \
  --arg candidateManifestSha256 "${CANDIDATE_MANIFEST_SHA256}" \
  --arg candidateApkSha256 "${APK_SHA256}" \
  --arg panelPairSha256 "${PANEL_PAIR_SHA256}" \
  --arg panelCatalogSha256 "${PANEL_CATALOG_SHA256}" \
  --arg publicHistoryRemoteRefsInputSha256 "${HISTORY_REMOTE_REFS_INPUT_SHA256}" \
  --arg publicHistoryRefApiInputSha256 "${HISTORY_REF_API_INPUT_SHA256}" \
  --arg publicHistoryRefApiSnapshotSha256 "${HISTORY_REF_API_SNAPSHOT_SHA256}" \
  --arg publicHistoryRemoteRefsRawSnapshotSha256 \
    "${HISTORY_REMOTE_REFS_RAW_SNAPSHOT_SHA256}" \
  --arg publicHistoryRefIdentitySha256 "${HISTORY_REMOTE_REFS_IDENTITY_SHA256}" \
  --arg publicHistoryPullRefIdentitySha256 "${HISTORY_PULL_REFS_IDENTITY_SHA256}" \
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
chmod 600 "${PRIVATE_MIGRATION_REPORT}" "${PANEL_CONFIG_EVIDENCE}" \
  "${PANEL_CATALOG_EVIDENCE}" "${PANEL_MANIFEST_EVIDENCE}"

write_audit_report file "$(sha256_file "${MANIFEST_PATH}")" "${MANIFEST_REPORT}"
write_audit_report file "${WORKTREE_INPUT_SHA256}" "${STALE_MANIFEST_REPORT}"

cat > "${BIN_DIR}/git" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
case "${1:-}" in
  status)
    exit 0
    ;;
  symbolic-ref)
    printf 'main\n'
    ;;
  rev-parse)
    last_argument="${!#}"
    if [[ "${last_argument}" == *'^{tree}' ]]; then
      printf '%s\n' "${AUTOFORM_SELFTEST_TREE_OID}"
    elif [[ "${last_argument}" == *':tools/public-surface-audit.mjs' ]]; then
      printf '%s\n' "${AUTOFORM_SELFTEST_SCANNER_BLOB_OID}"
    elif [[ "${last_argument}" == *':tools/normalize-github-releases-for-audit.mjs' ]]; then
      printf '%s\n' "${AUTOFORM_SELFTEST_NORMALIZER_BLOB_OID}"
    elif [[ "${last_argument}" == *':tools/verify-apk-third-party-sources.mjs' ]]; then
      printf '%s\n' "${AUTOFORM_SELFTEST_SOURCE_VERIFIER_BLOB_OID}"
    elif [[ "${last_argument}" == *':tools/historical-release-contract.mjs' ]]; then
      printf '%s\n' "${AUTOFORM_SELFTEST_HISTORICAL_CONTRACT_BLOB_OID}"
    elif [[ "${last_argument}" == *':tools/verify-private-release-evidence.mjs' ]]; then
      printf '%s\n' "${AUTOFORM_SELFTEST_PRIVATE_EVIDENCE_VERIFIER_BLOB_OID}"
    elif [[ "${last_argument}" == *':tools/private-release-gate-policy.json' ]]; then
      printf '%s\n' "${AUTOFORM_SELFTEST_PRIVATE_GATE_POLICY_BLOB_OID}"
    else
      printf '%s\n' "${AUTOFORM_SELFTEST_SOURCE_COMMIT}"
    fi
    ;;
  hash-object)
    last_argument="${!#}"
    if [[ "${last_argument}" == *'/normalize-github-releases-for-audit.mjs' ]]; then
      printf '%s\n' "${AUTOFORM_SELFTEST_NORMALIZER_BLOB_OID}"
    elif [[ "${last_argument}" == *'/verify-apk-third-party-sources.mjs' ]]; then
      if [[ "${AUTOFORM_SELFTEST_SOURCE_VERIFIER_BLOB_MISMATCH:-false}" == true ]]; then
        printf '5555555555555555555555555555555555555555\n'
      else
        printf '%s\n' "${AUTOFORM_SELFTEST_SOURCE_VERIFIER_BLOB_OID}"
      fi
    elif [[ "${last_argument}" == *'/historical-release-contract.mjs' ]]; then
      printf '%s\n' "${AUTOFORM_SELFTEST_HISTORICAL_CONTRACT_BLOB_OID}"
    elif [[ "${last_argument}" == *'/verify-private-release-evidence.mjs' ]]; then
      printf '%s\n' "${AUTOFORM_SELFTEST_PRIVATE_EVIDENCE_VERIFIER_BLOB_OID}"
    elif [[ "${last_argument}" == *'/private-release-gate-policy.json' ]]; then
      printf '%s\n' "${AUTOFORM_SELFTEST_PRIVATE_GATE_POLICY_BLOB_OID}"
    else
      printf '%s\n' "${AUTOFORM_SELFTEST_SCANNER_BLOB_OID}"
    fi
    ;;
  cat-file)
    [[ "${2:-}" == "commit" && "${3:-}" == "${AUTOFORM_SELFTEST_SOURCE_COMMIT}" ]]
    /bin/cat "${AUTOFORM_SELFTEST_HISTORY_COMMIT_OBJECT_FIXTURE}"
    ;;
  remote)
    [[ "${2:-}" == "get-url" && "${3:-}" == "origin" ]]
    printf 'https://github.com/example/autoform-kit.git\n'
    ;;
  ls-remote)
    if [[ "${2:-}" == "origin" && $# -eq 2 ]]; then
      printf '%s\tHEAD\n' "${AUTOFORM_SELFTEST_SOURCE_COMMIT}"
      printf '%s\trefs/heads/main\n' "${AUTOFORM_SELFTEST_SOURCE_COMMIT}"
      if [[ "${AUTOFORM_SELFTEST_REMOTE_PULL_REF:-false}" == true ]]; then
        printf '%s\trefs/pull/1/head\n' "${AUTOFORM_SELFTEST_SOURCE_COMMIT}"
      fi
      if [[ "${AUTOFORM_SELFTEST_REMOTE_UNKNOWN_REF:-false}" == true ]]; then
        printf '%s\trefs/changes/1\n' "${AUTOFORM_SELFTEST_SOURCE_COMMIT}"
      fi
    elif [[ "${2:-}" == "--heads" ]]; then
      printf '%s\trefs/heads/main\n' "${AUTOFORM_SELFTEST_SOURCE_COMMIT}"
    elif [[ "${2:-}" == "--tags" ]]; then
      :
    else
      exit 2
    fi
    ;;
  show-ref)
    exit 1
    ;;
  ls-files)
    exit 1
    ;;
  check-ignore)
    exit 0
    ;;
  *)
    printf 'unexpected fake git command: %s\n' "$*" >&2
    exit 2
    ;;
esac
EOF

FETCH_MOCK="${FIXTURE_ROOT}/fetch-mock.cjs"
cat > "${FETCH_MOCK}" <<'EOF'
const fs = require("node:fs");

global.fetch = async function fixtureFetch(url, options = {}) {
  const parsed = new URL(url);
  const authenticated = options.headers?.Authorization
    === `Bearer ${process.env.AUTOFORM_SELFTEST_CATALOG_READ_KEY}`;
  let status = 401;
  let body = Buffer.from('{"error":"unauthorized"}\n', "utf8");
  if (authenticated) {
    if (parsed.pathname === "/api/config") {
      status = 200;
      body = fs.readFileSync(process.env.AUTOFORM_SELFTEST_PANEL_CONFIG);
    } else if (parsed.pathname === "/catalog/form-profiles.json") {
      status = 200;
      body = fs.readFileSync(process.env.AUTOFORM_SELFTEST_PANEL_CATALOG);
    } else if (parsed.pathname === "/catalog/manifest") {
      status = 200;
      body = fs.readFileSync(process.env.AUTOFORM_SELFTEST_PANEL_MANIFEST);
    } else if (parsed.pathname === "/api/panel-config") {
      status = 200;
      body = Buffer.from('{"fixture":true}\n', "utf8");
    } else {
      status = 404;
      body = Buffer.from('{"error":"not found"}\n', "utf8");
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

cat > "${BIN_DIR}/node" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
dynamic_file_report() {
  local file="$1"
  local input_sha base canonical report_sha
  input_sha="$(shasum -a 256 "${file}" | awk '{ print $1 }')"
  base="$(mktemp "${TMPDIR:-/tmp}/autoform-dynamic-file-report.XXXXXX")"
  jq -nS \
    --arg scanner "${AUTOFORM_SELFTEST_PUBLIC_AUDIT_SCANNER_SHA256}" \
    --arg policy "${AUTOFORM_SELFTEST_AUDIT_POLICY_SHA256}" \
    --arg thirdPartyPolicy "${AUTOFORM_SELFTEST_THIRD_PARTY_POLICY_SHA256}" \
    --arg input "${input_sha}" \
    '{
      schemaVersion: 1,
      scannerSha256: $scanner,
      policySha256: $policy,
      input: {
        mode: "file",
        selection: "exact-regular-file",
        sha256: $input,
        entryCount: 1
      },
      privatePolicy: {applied: true, wordlistCount: 1, termCount: 1},
      summary: {passed: true, findingCount: 0},
      findings: [],
      thirdPartyPolicy: {
        manifestSha256: $thirdPartyPolicy,
        applied: false,
        profileId: null,
        matchedEntryCount: 0,
        applicationDexPolicy: "all application-namespace type descriptors remain strictly scanned"
      }
    }' > "${base}"
  canonical="$(jq -cS . "${base}")"
  report_sha="$(printf '%s' "${canonical}" | shasum -a 256 | awk '{ print $1 }')"
  jq -S --arg reportSha256 "${report_sha}" \
    '. + {reportSha256: $reportSha256}' "${base}"
  rm -f "${base}"
}
program="${1:-}"
[[ -n "${program}" ]]
shift
if [[ "$(basename "${program}")" == "normalize-github-releases-for-audit.mjs" ]]; then
  exec "${AUTOFORM_SELFTEST_REAL_NODE}" "${program}" "$@"
fi
if [[ "$(basename "${program}")" == "historical-release-contract.mjs" ]]; then
  exec "${AUTOFORM_SELFTEST_REAL_NODE}" "${program}" "$@"
fi
if [[ "$(basename "${program}")" == "verify-private-release-evidence.mjs" ]]; then
  NODE_OPTIONS="--require=${AUTOFORM_SELFTEST_FETCH_MOCK}" \
    exec "${AUTOFORM_SELFTEST_REAL_NODE}" "${program}" "$@"
fi
if [[ "$(basename "${program}")" == "verify-apk-third-party-sources.mjs" ]]; then
  if [[ "${AUTOFORM_SELFTEST_SOURCE_PROVENANCE_FAIL:-false}" == true ]]; then
    exit 1
  fi
  if [[ "${AUTOFORM_SELFTEST_STALE_SOURCE_PROVENANCE_REPORT:-false}" == true ]]; then
    /bin/cat "${AUTOFORM_SELFTEST_STALE_SOURCE_PROVENANCE_REPORT_PATH}"
  else
    /bin/cat "${AUTOFORM_SELFTEST_SOURCE_PROVENANCE_REPORT}"
  fi
  exit 0
fi
mode=""
apk=""
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
      apk="$2"
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
      [[ "$2" == "${AUTOFORM_SELFTEST_PRIVATE_WORDLIST}" ]]
      wordlist_seen=true
      shift 2
      ;;
    *)
      shift
      ;;
  esac
done
[[ "${wordlist_seen}" == true ]]
if [[ "${AUTOFORM_SELFTEST_AUDIT_FAIL:-false}" == true ]]; then
  exit 1
fi
if [[ -n "${AUTOFORM_SELFTEST_AUDIT_INPUT_LOG:-}" ]]; then
  case "${mode}" in
    apk)
      printf 'apk\t%s\t%s\n' "${apk}" "$(shasum -a 256 "${apk}" | awk '{ print $1 }')" \
        >> "${AUTOFORM_SELFTEST_AUDIT_INPUT_LOG}"
      ;;
    file)
      printf 'file\t%s\t%s\n' "${file}" "$(shasum -a 256 "${file}" | awk '{ print $1 }')" \
        >> "${AUTOFORM_SELFTEST_AUDIT_INPUT_LOG}"
      ;;
  esac
fi
if [[ "${mode}" == "file" && "${AUTOFORM_SELFTEST_FILE_AUDIT_FAIL:-false}" == true ]]; then
  exit 1
fi
if [[ "${mode}" == "file" ]] \
  && grep -Fq 'fictional-private-selftest-marker' "${file}"; then
  exit 1
fi
case "${mode}" in
  git-tree)
    if [[ "${AUTOFORM_SELFTEST_STALE_REPORT:-false}" == true ]]; then
      /bin/cat "${AUTOFORM_SELFTEST_STALE_TREE_REPORT}"
    else
      /bin/cat "${AUTOFORM_SELFTEST_TREE_REPORT}"
    fi
    ;;
  worktree)
    /bin/cat "${AUTOFORM_SELFTEST_WORKTREE_REPORT}"
    ;;
  apk)
    /bin/cat "${AUTOFORM_SELFTEST_APK_REPORT}"
    if [[ "${AUTOFORM_SELFTEST_MUTATE_APK_DURING_AUDIT:-false}" == true ]]; then
      printf 'changed-during-audit\n' >> "${apk}"
    fi
    ;;
  file)
    if [[ "${AUTOFORM_SELFTEST_DYNAMIC_FILE_REPORT:-false}" == true ]]; then
      dynamic_file_report "${file}"
      exit 0
    fi
    case "${file}" in
      "${AUTOFORM_SELFTEST_UPDATE_PATH}")
        if [[ "${AUTOFORM_SELFTEST_STALE_METADATA_REPORT:-false}" == true ]]; then
          /bin/cat "${AUTOFORM_SELFTEST_STALE_UPDATE_REPORT}"
        else
          /bin/cat "${AUTOFORM_SELFTEST_UPDATE_REPORT}"
        fi
        ;;
      "${AUTOFORM_SELFTEST_NOTES_PATH}") /bin/cat "${AUTOFORM_SELFTEST_NOTES_REPORT}" ;;
      "${AUTOFORM_SELFTEST_MANIFEST_PATH}")
        if [[ "${AUTOFORM_SELFTEST_STALE_MANIFEST_REPORT:-false}" == true ]]; then
          /bin/cat "${AUTOFORM_SELFTEST_STALE_MANIFEST_REPORT_PATH}"
        else
          /bin/cat "${AUTOFORM_SELFTEST_MANIFEST_REPORT}"
        fi
        ;;
      */source-commit-object.scan-input)
        if [[ "${AUTOFORM_SELFTEST_STALE_HISTORY_REPORT:-false}" == true ]]; then
          /bin/cat "${AUTOFORM_SELFTEST_STALE_HISTORY_COMMIT_REPORT}"
        else
          /bin/cat "${AUTOFORM_SELFTEST_HISTORY_COMMIT_REPORT}"
        fi
        ;;
      */remote-refs.scan-input) /bin/cat "${AUTOFORM_SELFTEST_HISTORY_REMOTE_REFS_REPORT}" ;;
      */github-refs.scan-input.json) /bin/cat "${AUTOFORM_SELFTEST_HISTORY_REF_API_REPORT}" ;;
      */github-releases.scan-input.json) /bin/cat "${AUTOFORM_SELFTEST_HISTORY_RELEASES_REPORT}" ;;
      *) exit 2 ;;
    esac
    ;;
  *)
    exit 2
    ;;
esac
EOF

cat > "${BIN_DIR}/shasum" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
last_argument="${!#}"
if [[ "${AUTOFORM_SELFTEST_SCANNER_SHA_FLIP:-false}" == true \
  && "${last_argument}" == "${AUTOFORM_SELFTEST_SCANNER_PATH}" ]]; then
  count=0
  if [[ -f "${AUTOFORM_SELFTEST_SCANNER_HASH_COUNT}" ]]; then
    count="$(<"${AUTOFORM_SELFTEST_SCANNER_HASH_COUNT}")"
  fi
  count=$((count + 1))
  printf '%s\n' "${count}" > "${AUTOFORM_SELFTEST_SCANNER_HASH_COUNT}"
  if (( count >= 2 )); then
    printf '9999999999999999999999999999999999999999999999999999999999999999  scanner\n'
    exit 0
  fi
fi
if [[ -n "${AUTOFORM_SELFTEST_REAL_SHASUM:-}" ]]; then
  exec "${AUTOFORM_SELFTEST_REAL_SHASUM}" "$@"
fi
[[ "${1:-}" == "-a" && "${2:-}" == "256" ]]
shift 2
exec "${AUTOFORM_SELFTEST_REAL_SHA256SUM}" "$@"
EOF

cat > "${BIN_DIR}/aapt" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
[[ "${1:-}" == "dump" && "${2:-}" == "badging" && -n "${3:-}" ]]
if [[ "$(basename "${3}")" == "previous.apk" ]]; then
  package="${AUTOFORM_SELFTEST_PACKAGE}"
  version_code="${AUTOFORM_SELFTEST_PREVIOUS_VERSION_CODE}"
  version_name="${AUTOFORM_SELFTEST_PREVIOUS_VERSION_NAME}"
elif [[ -n "${AUTOFORM_SELFTEST_IDENTITY_APK:-}" \
  && "${3}" == "${AUTOFORM_SELFTEST_IDENTITY_APK}" ]]; then
  package="${AUTOFORM_SELFTEST_IDENTITY_PACKAGE:-${AUTOFORM_SELFTEST_PACKAGE}}"
  version_code="${AUTOFORM_SELFTEST_IDENTITY_VERSION_CODE:-${AUTOFORM_SELFTEST_VERSION_CODE}}"
  version_name="${AUTOFORM_SELFTEST_IDENTITY_VERSION_NAME:-${AUTOFORM_SELFTEST_VERSION_NAME}}"
else
  package="${AUTOFORM_SELFTEST_PACKAGE}"
  version_code="${AUTOFORM_SELFTEST_VERSION_CODE}"
  version_name="${AUTOFORM_SELFTEST_VERSION_NAME}"
fi
printf "package: name='%s' versionCode='%s' versionName='%s'\n" \
  "${package}" "${version_code}" "${version_name}"
EOF

cat > "${BIN_DIR}/apksigner" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
case "${1:-}" in
  sign)
    output=""
    input="${!#}"
    while [[ $# -gt 0 ]]; do
      if [[ "$1" == "--out" ]]; then
        output="$2"
        break
      fi
      shift
    done
    [[ -n "${output}" ]]
    /bin/cp "${input}" "${output}"
    ;;
  verify)
    signer="${AUTOFORM_SELFTEST_SIGNER}"
    apk="${!#}"
    if [[ -n "${AUTOFORM_SELFTEST_IDENTITY_APK:-}" \
      && "${apk}" == "${AUTOFORM_SELFTEST_IDENTITY_APK}" ]]; then
      signer="${AUTOFORM_SELFTEST_IDENTITY_SIGNER:-${signer}}"
    fi
    printf 'Verifies\n'
    printf 'Signer #1 certificate SHA-256 digest: %s\n' "${signer}"
    ;;
  *)
    exit 2
    ;;
esac
EOF

cat > "${BIN_DIR}/zipalign" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
case "${1:-}" in
  -f)
    [[ "${2:-}" == "-p" && "${3:-}" == "4" && -n "${4:-}" && -n "${5:-}" ]]
    /bin/cp "$4" "$5"
    ;;
  -c)
    [[ "${2:-}" == "-p" && "${3:-}" == "4" && -n "${4:-}" ]]
    ;;
  *)
    exit 2
    ;;
esac
EOF

cat > "${BIN_DIR}/gh" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
case "${1:-}" in
  auth)
    exit 0
    ;;
  api)
    endpoint="${!#}"
    if [[ "${endpoint}" == *'/releases?per_page=100'* ]]; then
      if [[ "${AUTOFORM_SELFTEST_RELEASE_ACTOR_MARKER:-false}" == true ]]; then
        /bin/cat "${AUTOFORM_SELFTEST_HISTORY_RELEASE_AUTHOR_MARKER_API_FIXTURE}"
      elif [[ "${AUTOFORM_SELFTEST_RELEASE_UPLOADER_MARKER:-false}" == true ]]; then
        /bin/cat "${AUTOFORM_SELFTEST_HISTORY_RELEASE_UPLOADER_MARKER_API_FIXTURE}"
      else
        /bin/cat "${AUTOFORM_SELFTEST_HISTORY_RELEASES_API_FIXTURE}"
      fi
      exit 0
    fi
    if [[ "${endpoint}" == *'/branches?per_page=100'* ]]; then
      if [[ "${AUTOFORM_SELFTEST_REQUIRED_STATUS_MARKER:-false}" == true ]]; then
        jq '
          .[0][0].protected = true
          | .[0][0].protection_url =
            "https://api.github.com/repos/example/autoform-kit/branches/main/protection"
          | .[0][0].protection = {
              enabled:true,
              required_status_checks:{
                checks:[{app_id:null,context:"fictional-private-selftest-marker"}],
                contexts:["fictional-private-selftest-marker"],
                enforcement_level:"everyone"
              }
            }
        ' "${AUTOFORM_SELFTEST_HISTORY_BRANCHES_API_FIXTURE}"
      else
        /bin/cat "${AUTOFORM_SELFTEST_HISTORY_BRANCHES_API_FIXTURE}"
      fi
      exit 0
    fi
    if [[ "${endpoint}" == *'/tags?per_page=100'* ]]; then
      /bin/cat "${AUTOFORM_SELFTEST_HISTORY_TAGS_API_FIXTURE}"
      exit 0
    fi
    if [[ "${endpoint}" == "repos/example/autoform-kit" ]]; then
      visibility="${AUTOFORM_SELFTEST_REPO_VISIBILITY:-public}"
      if [[ "${visibility}" == "public" ]]; then
        /bin/cat "${AUTOFORM_SELFTEST_HISTORY_REPOSITORY_API_FIXTURE}"
      else
        jq '.visibility = "private" | .private = true' \
          "${AUTOFORM_SELFTEST_HISTORY_REPOSITORY_API_FIXTURE}"
      fi
      exit 0
    fi
    if [[ "${endpoint}" == "repos/private-example/forms" ]]; then
      printf '%s\n' '{"node_id":"private-fixture-node","full_name":"private-example/forms","private":true}'
      exit 0
    fi
    if [[ "${endpoint}" == "repos/private-example/forms/contents/form-profiles.json?ref=dddddddddddddddddddddddddddddddddddddddd" ]]; then
      /bin/cat "${AUTOFORM_SELFTEST_PANEL_CATALOG}"
      exit 0
    fi
    if [[ "${endpoint}" == "repos/private-example/forms/contents/manifest.json?ref=dddddddddddddddddddddddddddddddddddddddd" ]]; then
      /bin/cat "${AUTOFORM_SELFTEST_PANEL_MANIFEST}"
      exit 0
    fi
    if [[ "${endpoint}" == "repos/private-example/forms/contents/panel-settings.json?ref=dddddddddddddddddddddddddddddddddddddddd" ]]; then
      printf '%s\n' '{"message":"Not Found","status":"404"}'
      exit 1
    fi
    if [[ "${endpoint}" == "repos/example/autoform-kit/releases/latest" ]]; then
      [[ -f "${AUTOFORM_SELFTEST_GH_RELEASE_STATE}" ]] || {
        printf 'gh: Not Found (HTTP 404)\n' >&2
        exit 1
      }
      tag="v${AUTOFORM_SELFTEST_VERSION_NAME}"
      if [[ "${AUTOFORM_SELFTEST_LATEST_WRONG_TAG:-false}" == true ]]; then
        tag="v0.0.0"
      fi
      draft="${AUTOFORM_SELFTEST_LATEST_DRAFT:-false}"
      prerelease="${AUTOFORM_SELFTEST_LATEST_PRERELEASE:-false}"
      apk_size="$(wc -c < "${AUTOFORM_SELFTEST_APK_PATH}" | tr -d ' ')"
      update_size="$(wc -c < "${AUTOFORM_SELFTEST_UPDATE_PATH}" | tr -d ' ')"
      manifest_size="$(wc -c < "${AUTOFORM_SELFTEST_MANIFEST_PATH}" | tr -d ' ')"
      assets="$(jq -cn \
        --arg apk "$(basename "${AUTOFORM_SELFTEST_APK_PATH}")" \
        --argjson apkSize "${apk_size}" \
        --argjson updateSize "${update_size}" \
        --argjson manifestSize "${manifest_size}" \
        '[
          {id:101,name:$apk,state:"uploaded",size:$apkSize},
          {id:102,name:"update.json",state:"uploaded",size:$updateSize},
          {id:103,name:"candidate-manifest.json",state:"uploaded",size:$manifestSize}
        ]')"
      if [[ "${AUTOFORM_SELFTEST_LATEST_EXTRA_ASSET:-false}" == true ]]; then
        assets="$(jq -cn --argjson assets "${assets}" \
          '$assets + [{id:104,name:"unexpected.txt",state:"uploaded",size:1}]')"
      fi
      jq -n \
        --arg tag "${tag}" \
        --argjson draft "${draft}" \
        --argjson prerelease "${prerelease}" \
        --argjson assets "${assets}" \
        '{tag_name:$tag,draft:$draft,prerelease:$prerelease,assets:$assets}'
      exit 0
    fi
    case "${endpoint}" in
      repos/example/autoform-kit/releases/assets/101)
        source_path="${AUTOFORM_SELFTEST_APK_PATH}"
        ;;
      repos/example/autoform-kit/releases/assets/102)
        source_path="${AUTOFORM_SELFTEST_UPDATE_PATH}"
        ;;
      repos/example/autoform-kit/releases/assets/103)
        source_path="${AUTOFORM_SELFTEST_MANIFEST_PATH}"
        ;;
      *)
        printf 'gh: Not Found (HTTP 404)\n' >&2
        exit 1
        ;;
    esac
    asset_id="${endpoint##*/}"
    printf '%s\n' "${asset_id}" >> "${AUTOFORM_SELFTEST_GH_ASSET_DOWNLOAD_LOG}"
    if [[ "${AUTOFORM_SELFTEST_ASSET_TAMPER_ID:-}" == "${asset_id}" ]]; then
      printf 'tampered published asset\n'
    else
      /bin/cat "${source_path}"
    fi
    exit 0
    ;;
  release)
    [[ "${2:-}" == "create" ]]
    printf 'call\n' >> "${AUTOFORM_SELFTEST_GH_LOG}"
    for argument in "$@"; do
      printf 'arg=%s\n' "${argument}" >> "${AUTOFORM_SELFTEST_GH_LOG}"
    done
    printf 'created\n' > "${AUTOFORM_SELFTEST_GH_RELEASE_STATE}"
    if [[ "${AUTOFORM_SELFTEST_RELEASE_CREATE_FAIL:-false}" == true ]]; then
      exit 1
    fi
    ;;
  *)
    printf 'unexpected fake gh command: %s\n' "$*" >&2
    exit 2
    ;;
esac
EOF

cat > "${GATE_PROGRAM}" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
printf 'called\n' >> "${AUTOFORM_SELFTEST_GATE_LOG}"
rollback="${AUTOFORM_SELFTEST_GATE_ROLLBACK:-true}"
controlled_recovery="${AUTOFORM_SELFTEST_GATE_CONTROLLED_RECOVERY:-true}"
commit_object_input="${AUTOFORM_RELEASE_PUBLIC_COMMIT_OBJECT_INPUT_SHA256}"
if [[ "${AUTOFORM_SELFTEST_GATE_HISTORY_BINDING:-true}" != true ]]; then
  commit_object_input="0000000000000000000000000000000000000000000000000000000000000000"
fi
if [[ "${AUTOFORM_SELFTEST_GATE_MUTATE_HISTORY:-false}" == true ]]; then
  printf 'mutated-by-private-gate\n' >> "${AUTOFORM_RELEASE_PUBLIC_REMOTE_REFS_FILE}"
fi
jq -n \
  --arg manifest "${AUTOFORM_RELEASE_CANDIDATE_MANIFEST_SHA256}" \
  --arg apk "${AUTOFORM_RELEASE_APK_SHA256}" \
  --arg update "${AUTOFORM_RELEASE_UPDATE_SHA256}" \
  --arg notes "${AUTOFORM_RELEASE_NOTES_SHA256}" \
  --arg previousApk "${AUTOFORM_RELEASE_PREVIOUS_APK_SHA256}" \
  --arg sourceCommit "${AUTOFORM_RELEASE_SOURCE_COMMIT}" \
  --arg auditScanner "${AUTOFORM_RELEASE_PUBLIC_AUDIT_SCANNER_SHA256}" \
  --arg auditPolicy "${AUTOFORM_RELEASE_PUBLIC_AUDIT_POLICY_SHA256}" \
  --arg treeOid "${AUTOFORM_RELEASE_PUBLIC_TREE_OID}" \
  --arg treeInput "${AUTOFORM_RELEASE_PUBLIC_TREE_INPUT_SHA256}" \
  --arg treeReport "${AUTOFORM_RELEASE_PUBLIC_TREE_REPORT_SHA256}" \
  --arg worktreeInput "${AUTOFORM_RELEASE_PUBLIC_WORKTREE_INPUT_SHA256}" \
  --arg worktreeReport "${AUTOFORM_RELEASE_PUBLIC_WORKTREE_REPORT_SHA256}" \
  --arg apkInput "${AUTOFORM_RELEASE_PUBLIC_APK_INPUT_SHA256}" \
  --arg apkReport "${AUTOFORM_RELEASE_PUBLIC_APK_REPORT_SHA256}" \
  --arg apkZipEntryManifest "${AUTOFORM_RELEASE_PUBLIC_APK_ZIP_ENTRY_MANIFEST_SHA256}" \
  --arg thirdPartyPolicy "${AUTOFORM_RELEASE_APK_THIRD_PARTY_POLICY_SHA256}" \
  --arg thirdPartyProfile "${AUTOFORM_RELEASE_APK_THIRD_PARTY_PROFILE_ID}" \
  --argjson thirdPartyEntryCount "${AUTOFORM_RELEASE_APK_THIRD_PARTY_MATCHED_ENTRY_COUNT}" \
  --arg runtimeLock "${AUTOFORM_RELEASE_ANDROID_RUNTIME_LOCK_SHA256}" \
  --arg sourceVerifier "${AUTOFORM_RELEASE_APK_SOURCE_VERIFIER_SHA256}" \
  --arg sourceReport "${AUTOFORM_RELEASE_APK_SOURCE_REPORT_SHA256}" \
  --argjson sourceArtifactCount "${AUTOFORM_RELEASE_APK_SOURCE_ARTIFACT_COUNT}" \
  --argjson sourceEntryCount "${AUTOFORM_RELEASE_APK_SOURCE_ENTRY_COUNT}" \
  --argjson mergedSourceCount "${AUTOFORM_RELEASE_APK_MERGED_SOURCE_COUNT}" \
  --argjson compiledOutputCount "${AUTOFORM_RELEASE_APK_COMPILED_OUTPUT_COUNT}" \
  --argjson dexSourceArtifactCount "${AUTOFORM_RELEASE_APK_DEX_SOURCE_ARTIFACT_COUNT}" \
  --argjson dexSourceEntryCount "${AUTOFORM_RELEASE_APK_DEX_SOURCE_ENTRY_COUNT}" \
  --argjson declaredDexStringCount "${AUTOFORM_RELEASE_APK_DECLARED_DEX_STRING_COUNT}" \
  --argjson sourceMatchedDexStringCount "${AUTOFORM_RELEASE_APK_SOURCE_MATCHED_DEX_STRING_COUNT}" \
  --argjson apkMatchedDexStringCount "${AUTOFORM_RELEASE_APK_MATCHED_DEX_STRING_COUNT}" \
  --arg updateInput "${AUTOFORM_RELEASE_PUBLIC_UPDATE_INPUT_SHA256}" \
  --arg updateReport "${AUTOFORM_RELEASE_PUBLIC_UPDATE_REPORT_SHA256}" \
  --arg notesInput "${AUTOFORM_RELEASE_PUBLIC_NOTES_INPUT_SHA256}" \
  --arg notesReport "${AUTOFORM_RELEASE_PUBLIC_NOTES_REPORT_SHA256}" \
  --arg manifestInput "${AUTOFORM_RELEASE_PUBLIC_MANIFEST_INPUT_SHA256}" \
  --arg manifestReport "${AUTOFORM_RELEASE_PUBLIC_MANIFEST_REPORT_SHA256}" \
  --arg commitObjectInput "${commit_object_input}" \
  --arg commitObjectReport "${AUTOFORM_RELEASE_PUBLIC_COMMIT_OBJECT_REPORT_SHA256}" \
  --arg remoteRefsInput "${AUTOFORM_RELEASE_PUBLIC_REMOTE_REFS_INPUT_SHA256}" \
  --arg remoteRefsReport "${AUTOFORM_RELEASE_PUBLIC_REMOTE_REFS_REPORT_SHA256}" \
  --arg refApiInput "${AUTOFORM_RELEASE_PUBLIC_REF_API_INPUT_SHA256}" \
  --arg refApiReport "${AUTOFORM_RELEASE_PUBLIC_REF_API_REPORT_SHA256}" \
  --arg releasesInput "${AUTOFORM_RELEASE_PUBLIC_RELEASES_INPUT_SHA256}" \
  --arg releasesReport "${AUTOFORM_RELEASE_PUBLIC_RELEASES_REPORT_SHA256}" \
  --arg refIdentity "${AUTOFORM_RELEASE_PUBLIC_REF_IDENTITY_SHA256}" \
  --arg pullRefIdentity "${AUTOFORM_RELEASE_PUBLIC_PULL_REF_IDENTITY_SHA256}" \
  --arg remoteRefsRawSnapshot "${AUTOFORM_RELEASE_PUBLIC_REMOTE_REFS_RAW_SNAPSHOT_SHA256}" \
  --arg refApiSnapshot "${AUTOFORM_RELEASE_PUBLIC_REF_API_SNAPSHOT_SHA256}" \
  --arg releaseApiSnapshot "${AUTOFORM_RELEASE_PUBLIC_RELEASE_API_SNAPSHOT_SHA256}" \
  --arg repositoryBinding "${AUTOFORM_RELEASE_PUBLIC_REPOSITORY_BINDING_SHA256}" \
  --arg metadataBinding "${AUTOFORM_RELEASE_PUBLIC_METADATA_BINDING_SHA256}" \
  --arg privateVerifier "${AUTOFORM_RELEASE_PRIVATE_EVIDENCE_VERIFIER_SHA256}" \
  --arg privateEvidenceReport "${AUTOFORM_RELEASE_PRIVATE_EVIDENCE_REPORT_SHA256}" \
  --arg privateMigrationReport "${AUTOFORM_RELEASE_PRIVATE_MIGRATION_REPORT_SHA256}" \
  --arg privatePanelConfig "${AUTOFORM_RELEASE_PRIVATE_PANEL_CONFIG_SHA256}" \
  --arg privatePanelCatalog "${AUTOFORM_RELEASE_PRIVATE_PANEL_CATALOG_SHA256}" \
  --arg privatePanelPair "${AUTOFORM_RELEASE_PRIVATE_PANEL_PAIR_SHA256}" \
  --arg privateDeploymentEvidence "${AUTOFORM_RELEASE_PRIVATE_DEPLOYMENT_EVIDENCE_SHA256}" \
  --arg privatePanelWorkerVersion "${AUTOFORM_RELEASE_PRIVATE_PANEL_WORKER_VERSION_ID}" \
  --arg privateAuthorityType "${AUTOFORM_RELEASE_PRIVATE_CATALOG_AUTHORITY_TYPE}" \
  --arg privateAuthorityIdentity "${AUTOFORM_RELEASE_PRIVATE_CATALOG_AUTHORITY_IDENTITY_SHA256}" \
  --arg privateAuthorityRevision "${AUTOFORM_RELEASE_PRIVATE_CATALOG_AUTHORITY_REVISION}" \
  --arg privateAuthorityWorkerBinding "${AUTOFORM_RELEASE_PRIVATE_CATALOG_AUTHORITY_WORKER_BINDING_SHA256}" \
  --arg privateCatalogManifest "${AUTOFORM_RELEASE_PRIVATE_CATALOG_MANIFEST_SHA256}" \
  --arg privatePanelSettings "${AUTOFORM_RELEASE_PRIVATE_PANEL_SETTINGS_SHA256}" \
  --argjson privatePanelSettingsPresent "${AUTOFORM_RELEASE_PRIVATE_PANEL_SETTINGS_PRESENT}" \
  --arg privateGate "${AUTOFORM_RELEASE_PRIVATE_GATE_SHA256}" \
  --argjson privateCatalogVersion "${AUTOFORM_RELEASE_PRIVATE_CATALOG_VERSION}" \
  --argjson rollback "${rollback}" \
  --argjson controlledRecovery "${controlled_recovery}" \
  '{
    schemaVersion: 4,
    releaseReady: true,
    bindings: {
      candidateManifestSha256: $manifest,
      apkSha256: $apk,
      updateSha256: $update,
      notesSha256: $notes,
      previousApkSha256: $previousApk,
      sourceCommit: $sourceCommit,
      publicAudit: {
        scannerSha256: $auditScanner,
        policySha256: $auditPolicy,
        sourceTree: {
          gitTreeOid: $treeOid,
          inputSha256: $treeInput,
          reportSha256: $treeReport
        },
        worktree: {
          inputSha256: $worktreeInput,
          reportSha256: $worktreeReport
        },
        apk: {
          inputSha256: $apkInput,
          reportSha256: $apkReport,
          zipEntryManifestSha256: $apkZipEntryManifest
        },
        thirdPartyProvenance: {
          manifestFile: "tools/apk-third-party-components.json",
          manifestSha256: $thirdPartyPolicy,
          runtimeLockFile: "tools/android-runtime-dependencies.lock.json",
          runtimeLockSha256: $runtimeLock,
          profileId: $thirdPartyProfile,
          matchedEntryCount: $thirdPartyEntryCount,
          applicationDexStrict: true,
          sourceVerifierFile: "tools/verify-apk-third-party-sources.mjs",
          sourceVerifierSha256: $sourceVerifier,
          sourceReportSha256: $sourceReport,
          sourceArtifactCount: $sourceArtifactCount,
          sourceEntryCount: $sourceEntryCount,
          mergedSourceCount: $mergedSourceCount,
          compiledOutputCount: $compiledOutputCount,
          dexSourceArtifactCount: $dexSourceArtifactCount,
          dexSourceEntryCount: $dexSourceEntryCount,
          declaredDexStringCount: $declaredDexStringCount,
          sourceMatchedDexStringCount: $sourceMatchedDexStringCount,
          apkMatchedDexStringCount: $apkMatchedDexStringCount
        },
        releaseMetadata: {
          update: {
            inputSha256: $updateInput,
            reportSha256: $updateReport
          },
          notes: {
            inputSha256: $notesInput,
            reportSha256: $notesReport
          },
          candidateManifest: {
            inputSha256: $manifestInput,
            reportSha256: $manifestReport
          }
        },
        publicHistory: {
          sourceCommitObject: {
            inputSha256: $commitObjectInput,
            reportSha256: $commitObjectReport
          },
          remoteRefs: {
            inputSha256: $remoteRefsInput,
            reportSha256: $remoteRefsReport
          },
          refsApi: {
            inputSha256: $refApiInput,
            reportSha256: $refApiReport
          },
          releases: {
            inputSha256: $releasesInput,
            reportSha256: $releasesReport
          },
          refIdentitySha256: $refIdentity,
          pullRefIdentitySha256: $pullRefIdentity,
          remoteRefsRawSnapshotSha256: $remoteRefsRawSnapshot,
          refApiSnapshotSha256: $refApiSnapshot,
          releaseApiSnapshotSha256: $releaseApiSnapshot,
          repositoryBindingSha256: $repositoryBinding,
          metadataBindingSha256: $metadataBinding
        }
      },
      privateEvidence: {
        verifierFile: "tools/verify-private-release-evidence.mjs",
        verifierSha256: $privateVerifier,
        verificationReportSha256: $privateEvidenceReport,
        migrationReportSha256: $privateMigrationReport,
        panelConfigSha256: $privatePanelConfig,
        panelCatalogSha256: $privatePanelCatalog,
        panelPairSha256: $privatePanelPair,
        deploymentEvidenceSha256: $privateDeploymentEvidence,
        catalogVersion: $privateCatalogVersion,
        panelWorkerVersionId: $privatePanelWorkerVersion,
        catalogAuthorityType: $privateAuthorityType,
        catalogAuthorityIdentitySha256: $privateAuthorityIdentity,
        catalogAuthorityRevision: $privateAuthorityRevision,
        catalogAuthorityWorkerBindingSha256: $privateAuthorityWorkerBinding,
        catalogManifestSha256: $privateCatalogManifest,
        panelSettingsPresent: $privatePanelSettingsPresent,
        panelSettingsSha256: $privatePanelSettings,
        privateGateSha256: $privateGate
      }
    },
    checks: {
      privateMigration: true,
      privateDeployment: true,
      publicWorktree: true,
      publicHistory: true,
      candidateApk: true,
      signedUpgrade: true,
      rollback: $rollback,
      flowParity: true,
      controlledRecovery: $controlledRecovery
    }
  }' > "${AUTOFORM_RELEASE_ATTESTATION_OUT}"
EOF

chmod 700 \
  "${BIN_DIR}/git" \
  "${BIN_DIR}/node" \
  "${BIN_DIR}/shasum" \
  "${BIN_DIR}/aapt" \
  "${BIN_DIR}/apksigner" \
  "${BIN_DIR}/zipalign" \
  "${BIN_DIR}/gh" \
  "${GATE_PROGRAM}"

write_private_deployment_evidence() {
  jq -n '{
    schemaVersion:1,
    catalogRepository:"private-example/forms",
    catalogCommit:"dddddddddddddddddddddddddddddddddddddddd",
    panelBase:"https://panel.example.invalid",
    catalogReadKey:"fictional-selftest-read-key-0000000000000000"
  }' > "${PRIVATE_DEPLOYMENT_EVIDENCE}"
  chmod 600 "${PRIVATE_DEPLOYMENT_EVIDENCE}"
}

write_private_deployment_evidence

run_publish() {
  printf '0\n' > "${SCANNER_HASH_COUNT}"
  rm -f "${GH_RELEASE_STATE}"
  : > "${GH_ASSET_DOWNLOAD_LOG}"
  PATH="${BIN_DIR}:${PATH}" \
  AAPT="${BIN_DIR}/aapt" \
  APKSIGNER="${BIN_DIR}/apksigner" \
  AUTOFORM_SELFTEST_SOURCE_COMMIT="${SOURCE_COMMIT}" \
  AUTOFORM_SELFTEST_TREE_OID="${TREE_OID}" \
  AUTOFORM_SELFTEST_SCANNER_BLOB_OID="${SCANNER_BLOB_OID}" \
  AUTOFORM_SELFTEST_NORMALIZER_BLOB_OID="${NORMALIZER_BLOB_OID}" \
  AUTOFORM_SELFTEST_SOURCE_VERIFIER_BLOB_OID="${SOURCE_VERIFIER_BLOB_OID}" \
  AUTOFORM_SELFTEST_PRIVATE_EVIDENCE_VERIFIER_BLOB_OID="${PRIVATE_EVIDENCE_VERIFIER_BLOB_OID}" \
  AUTOFORM_SELFTEST_PRIVATE_GATE_POLICY_BLOB_OID="${PRIVATE_GATE_POLICY_BLOB_OID}" \
  AUTOFORM_SELFTEST_SOURCE_VERIFIER_BLOB_MISMATCH="${AUTOFORM_SELFTEST_SOURCE_VERIFIER_BLOB_MISMATCH:-false}" \
  AUTOFORM_SELFTEST_SIGNER="${SIGNER_SHA256}" \
  AUTOFORM_SELFTEST_PACKAGE="${PACKAGE_NAME}" \
  AUTOFORM_SELFTEST_VERSION_CODE="${VERSION_CODE}" \
  AUTOFORM_SELFTEST_VERSION_NAME="${VERSION_NAME}" \
  AUTOFORM_SELFTEST_PREVIOUS_VERSION_CODE="${PREVIOUS_VERSION_CODE}" \
  AUTOFORM_SELFTEST_PREVIOUS_VERSION_NAME="${PREVIOUS_VERSION_NAME}" \
  AUTOFORM_SELFTEST_GH_LOG="${GH_LOG}" \
  AUTOFORM_SELFTEST_GH_RELEASE_STATE="${GH_RELEASE_STATE}" \
  AUTOFORM_SELFTEST_GH_ASSET_DOWNLOAD_LOG="${GH_ASSET_DOWNLOAD_LOG}" \
  AUTOFORM_SELFTEST_REPO_VISIBILITY="${AUTOFORM_SELFTEST_REPO_VISIBILITY:-public}" \
  AUTOFORM_SELFTEST_RELEASE_CREATE_FAIL="${AUTOFORM_SELFTEST_RELEASE_CREATE_FAIL:-false}" \
  AUTOFORM_SELFTEST_LATEST_WRONG_TAG="${AUTOFORM_SELFTEST_LATEST_WRONG_TAG:-false}" \
  AUTOFORM_SELFTEST_LATEST_DRAFT="${AUTOFORM_SELFTEST_LATEST_DRAFT:-false}" \
  AUTOFORM_SELFTEST_LATEST_PRERELEASE="${AUTOFORM_SELFTEST_LATEST_PRERELEASE:-false}" \
  AUTOFORM_SELFTEST_LATEST_EXTRA_ASSET="${AUTOFORM_SELFTEST_LATEST_EXTRA_ASSET:-false}" \
  AUTOFORM_SELFTEST_ASSET_TAMPER_ID="${AUTOFORM_SELFTEST_ASSET_TAMPER_ID:-}" \
  AUTOFORM_SELFTEST_APK_PATH="${APK_PATH}" \
  AUTOFORM_SELFTEST_GATE_LOG="${GATE_LOG}" \
  AUTOFORM_SELFTEST_GATE_ROLLBACK="${AUTOFORM_SELFTEST_GATE_ROLLBACK:-true}" \
  AUTOFORM_SELFTEST_GATE_CONTROLLED_RECOVERY="${AUTOFORM_SELFTEST_GATE_CONTROLLED_RECOVERY:-true}" \
  AUTOFORM_SELFTEST_GATE_HISTORY_BINDING="${AUTOFORM_SELFTEST_GATE_HISTORY_BINDING:-true}" \
  AUTOFORM_SELFTEST_GATE_MUTATE_HISTORY="${AUTOFORM_SELFTEST_GATE_MUTATE_HISTORY:-false}" \
  AUTOFORM_SELFTEST_PRIVATE_WORDLIST="${PRIVATE_WORDLIST}" \
  AUTOFORM_SELFTEST_TREE_REPORT="${TREE_REPORT}" \
  AUTOFORM_SELFTEST_STALE_TREE_REPORT="${STALE_TREE_REPORT}" \
  AUTOFORM_SELFTEST_WORKTREE_REPORT="${WORKTREE_REPORT}" \
  AUTOFORM_SELFTEST_APK_REPORT="${APK_REPORT}" \
  AUTOFORM_SELFTEST_SOURCE_PROVENANCE_REPORT="${SOURCE_PROVENANCE_REPORT}" \
  AUTOFORM_SELFTEST_STALE_SOURCE_PROVENANCE_REPORT_PATH="${STALE_SOURCE_PROVENANCE_REPORT}" \
  AUTOFORM_SELFTEST_UPDATE_PATH="${UPDATE_PATH}" \
  AUTOFORM_SELFTEST_NOTES_PATH="${NOTES_PATH}" \
  AUTOFORM_SELFTEST_MANIFEST_PATH="${MANIFEST_PATH}" \
  AUTOFORM_SELFTEST_UPDATE_REPORT="${UPDATE_REPORT}" \
  AUTOFORM_SELFTEST_NOTES_REPORT="${NOTES_REPORT}" \
  AUTOFORM_SELFTEST_MANIFEST_REPORT="${MANIFEST_REPORT}" \
  AUTOFORM_SELFTEST_STALE_UPDATE_REPORT="${STALE_UPDATE_REPORT}" \
  AUTOFORM_SELFTEST_STALE_MANIFEST_REPORT_PATH="${STALE_MANIFEST_REPORT}" \
  AUTOFORM_SELFTEST_HISTORY_COMMIT_OBJECT_FIXTURE="${HISTORY_COMMIT_OBJECT_FIXTURE}" \
  AUTOFORM_SELFTEST_HISTORY_BRANCHES_API_FIXTURE="${HISTORY_BRANCHES_API_FIXTURE}" \
  AUTOFORM_SELFTEST_HISTORY_TAGS_API_FIXTURE="${HISTORY_TAGS_API_FIXTURE}" \
  AUTOFORM_SELFTEST_HISTORY_RELEASES_API_FIXTURE="${HISTORY_RELEASES_API_FIXTURE}" \
  AUTOFORM_SELFTEST_HISTORY_RELEASE_AUTHOR_MARKER_API_FIXTURE="${HISTORY_RELEASE_AUTHOR_MARKER_API_FIXTURE}" \
  AUTOFORM_SELFTEST_HISTORY_RELEASE_UPLOADER_MARKER_API_FIXTURE="${HISTORY_RELEASE_UPLOADER_MARKER_API_FIXTURE}" \
  AUTOFORM_SELFTEST_HISTORY_REPOSITORY_API_FIXTURE="${HISTORY_REPOSITORY_API_FIXTURE}" \
  AUTOFORM_SELFTEST_HISTORY_COMMIT_REPORT="${HISTORY_COMMIT_REPORT}" \
  AUTOFORM_SELFTEST_HISTORY_REMOTE_REFS_REPORT="${HISTORY_REMOTE_REFS_REPORT}" \
  AUTOFORM_SELFTEST_HISTORY_REF_API_REPORT="${HISTORY_REF_API_REPORT}" \
  AUTOFORM_SELFTEST_HISTORY_RELEASES_REPORT="${HISTORY_RELEASES_REPORT}" \
  AUTOFORM_SELFTEST_STALE_HISTORY_COMMIT_REPORT="${STALE_HISTORY_COMMIT_REPORT}" \
  AUTOFORM_SELFTEST_AUDIT_FAIL="${AUTOFORM_SELFTEST_AUDIT_FAIL:-false}" \
  AUTOFORM_SELFTEST_SOURCE_PROVENANCE_FAIL="${AUTOFORM_SELFTEST_SOURCE_PROVENANCE_FAIL:-false}" \
  AUTOFORM_SELFTEST_STALE_SOURCE_PROVENANCE_REPORT="${AUTOFORM_SELFTEST_STALE_SOURCE_PROVENANCE_REPORT:-false}" \
  AUTOFORM_SELFTEST_FILE_AUDIT_FAIL="${AUTOFORM_SELFTEST_FILE_AUDIT_FAIL:-false}" \
  AUTOFORM_SELFTEST_STALE_REPORT="${AUTOFORM_SELFTEST_STALE_REPORT:-false}" \
  AUTOFORM_SELFTEST_STALE_METADATA_REPORT="${AUTOFORM_SELFTEST_STALE_METADATA_REPORT:-false}" \
  AUTOFORM_SELFTEST_STALE_MANIFEST_REPORT="${AUTOFORM_SELFTEST_STALE_MANIFEST_REPORT:-false}" \
  AUTOFORM_SELFTEST_STALE_HISTORY_REPORT="${AUTOFORM_SELFTEST_STALE_HISTORY_REPORT:-false}" \
  AUTOFORM_SELFTEST_REQUIRED_STATUS_MARKER="${AUTOFORM_SELFTEST_REQUIRED_STATUS_MARKER:-false}" \
  AUTOFORM_SELFTEST_RELEASE_ACTOR_MARKER="${AUTOFORM_SELFTEST_RELEASE_ACTOR_MARKER:-false}" \
  AUTOFORM_SELFTEST_RELEASE_UPLOADER_MARKER="${AUTOFORM_SELFTEST_RELEASE_UPLOADER_MARKER:-false}" \
  AUTOFORM_SELFTEST_REMOTE_PULL_REF="${AUTOFORM_SELFTEST_REMOTE_PULL_REF:-false}" \
  AUTOFORM_SELFTEST_REMOTE_UNKNOWN_REF="${AUTOFORM_SELFTEST_REMOTE_UNKNOWN_REF:-false}" \
  AUTOFORM_SELFTEST_MUTATE_APK_DURING_AUDIT="${AUTOFORM_SELFTEST_MUTATE_APK_DURING_AUDIT:-false}" \
  AUTOFORM_SELFTEST_SCANNER_SHA_FLIP="${AUTOFORM_SELFTEST_SCANNER_SHA_FLIP:-false}" \
  AUTOFORM_SELFTEST_SCANNER_PATH="${PUBLIC_AUDIT_SCANNER}" \
  AUTOFORM_SELFTEST_SCANNER_HASH_COUNT="${SCANNER_HASH_COUNT}" \
  AUTOFORM_SELFTEST_REAL_SHASUM="${REAL_SHASUM}" \
  AUTOFORM_SELFTEST_REAL_SHA256SUM="${REAL_SHA256SUM}" \
  AUTOFORM_SELFTEST_REAL_NODE="${REAL_NODE}" \
  AUTOFORM_SELFTEST_FETCH_MOCK="${FETCH_MOCK}" \
  AUTOFORM_SELFTEST_CATALOG_READ_KEY="${CATALOG_READ_KEY}" \
  AUTOFORM_SELFTEST_PANEL_CONFIG="${PANEL_CONFIG_EVIDENCE}" \
  AUTOFORM_SELFTEST_PANEL_CATALOG="${PANEL_CATALOG_EVIDENCE}" \
  AUTOFORM_SELFTEST_PANEL_MANIFEST="${PANEL_MANIFEST_EVIDENCE}" \
    bash "${PUBLISH_SCRIPT}" \
      --candidate "${MANIFEST_PATH}" \
      --previous-apk "${PREVIOUS_APK}" \
      --gate "${GATE_PROGRAM}" \
      --private-migration-report "${PRIVATE_MIGRATION_REPORT}" \
      --panel-config-evidence "${PANEL_CONFIG_EVIDENCE}" \
      --panel-catalog-evidence "${PANEL_CATALOG_EVIDENCE}" \
      --private-deployment-evidence "${PRIVATE_DEPLOYMENT_EVIDENCE}" \
      --private-wordlist "${PRIVATE_WORDLIST}"
}

# The retired one-step option must stop before any Gradle invocation.
if bash "${RELEASE_SCRIPT}" --publish >"${FIXTURE_ROOT}/retired-option.log" 2>&1; then
  die "release.sh accepted the retired --publish option"
fi
grep -q -- '--publish was removed' "${FIXTURE_ROOT}/retired-option.log" || \
  die "release.sh did not explain the two-stage replacement"

# Offline candidate preparation must be explicit and documented. This keeps local-only
# rehearsals from depending on an undocumented Gradle environment side effect.
bash "${RELEASE_SCRIPT}" --help >"${FIXTURE_ROOT}/release-help.log"
grep -q -- '--offline' "${FIXTURE_ROOT}/release-help.log" || \
  die "release.sh did not document its offline Gradle mode"
grep -q -- 'GRADLE_ARGS+=(--offline)' "${RELEASE_SCRIPT}" || \
  die "release.sh did not pass its offline mode to Gradle"
grep -q -- '--v4-signing-enabled false' "${RELEASE_SCRIPT}" || \
  die "release.sh did not suppress an unmanifested APK v4 sidecar"
grep -q -- 'candidate directory contains unexpected artifacts' "${RELEASE_SCRIPT}" || \
  die "release.sh did not reject unmanifested candidate artifacts"
grep -Fq -- "git cat-file commit \"\${SOURCE_HEAD}\"" "${RELEASE_SCRIPT}" || \
  die "release.sh did not capture the raw source commit object"
grep -Fq -- "run_public_audit \"\${PUBLIC_COMMIT_REPORT}\" file \"\${PUBLIC_COMMIT_OBJECT}\"" \
  "${RELEASE_SCRIPT}" || \
  die "release.sh did not scan source commit metadata with the private wordlist"

# The standard release path must still require a real previous APK. The only
# exception is a narrowly bounded, one-time historical rebuild of v1.0.0/code 1.
HISTORICAL_REPO="${FIXTURE_ROOT}/historical-initial-repo"
HISTORICAL_IDENTITY_APK="${FIXTURE_ROOT}/autoform-kit-1.0.0.apk"
HISTORICAL_SYMLINK_APK="${FIXTURE_ROOT}/historical-v1.0.0-symlink.apk"
HISTORICAL_BAD_MODE_APK="${FIXTURE_ROOT}/historical-v1.0.0-bad-mode.apk"
HISTORICAL_HARDLINK_APK="${FIXTURE_ROOT}/historical-v1.0.0-hardlink.apk"
HISTORICAL_GRADLE_LOG="${FIXTURE_ROOT}/historical-gradle.log"
HISTORICAL_AUDIT_INPUT_LOG="${FIXTURE_ROOT}/historical-audit-inputs.log"
HISTORICAL_JAVA_HOME="${FIXTURE_ROOT}/historical-jdk"
mkdir -p \
  "${HISTORICAL_REPO}/app" \
  "${HISTORICAL_REPO}/config" \
  "${HISTORICAL_REPO}/tools" \
  "${HISTORICAL_JAVA_HOME}/bin"
cp "${RELEASE_SCRIPT}" "${HISTORICAL_REPO}/tools/release.sh"
cp "${PUBLIC_AUDIT_SCANNER}" \
  "${HISTORICAL_REPO}/tools/public-surface-audit.mjs"
cp "${APK_SOURCE_PROVENANCE_VERIFIER}" \
  "${HISTORICAL_REPO}/tools/verify-apk-third-party-sources.mjs"
cp "${SCRIPT_DIR}/historical-release-contract.mjs" \
  "${HISTORICAL_REPO}/tools/historical-release-contract.mjs"
cp "${SCRIPT_DIR}/apk-third-party-components.json" \
  "${HISTORICAL_REPO}/tools/apk-third-party-components.json"
cp "${SCRIPT_DIR}/android-runtime-dependencies.lock.json" \
  "${HISTORICAL_REPO}/tools/android-runtime-dependencies.lock.json"

cat > "${HISTORICAL_REPO}/app/build.gradle" <<'EOF'
android {
  defaultConfig {
    applicationId "com.example.autoform"
    versionCode = (project.findProperty("versionCode") ?: "8").toInteger()
    versionName = (project.findProperty("versionName") ?: "1.0.7").toString()
  }
}
EOF
cat > "${HISTORICAL_REPO}/config/signing.local.json" <<'EOF'
{
  "keystore": "config/release.jks",
  "keyAlias": "fixture-release",
  "storePassword": "fixture-store-password",
  "keyPassword": "fixture-key-password"
}
EOF
printf 'fixture keystore bytes\n' > "${HISTORICAL_REPO}/config/release.jks"
cat > "${HISTORICAL_REPO}/gradlew" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
printf 'called\n' >> "${AUTOFORM_SELFTEST_GRADLE_LOG}"
mkdir -p app/build/outputs/apk/release
printf 'fixture candidate apk bytes\n' \
  > app/build/outputs/apk/release/app-release-unsigned.apk
if [[ "${AUTOFORM_SELFTEST_MUTATE_IDENTITY_DURING_BUILD:-false}" == true ]]; then
  printf 'changed-during-build\n' >> "${AUTOFORM_SELFTEST_IDENTITY_APK}"
fi
if [[ "${AUTOFORM_SELFTEST_CHANGE_IDENTITY_MODE_DURING_BUILD:-false}" == true ]]; then
  chmod 644 "${AUTOFORM_SELFTEST_IDENTITY_APK}"
fi
if [[ "${AUTOFORM_SELFTEST_SYMLINK_IDENTITY_DURING_BUILD:-false}" == true ]]; then
  /bin/mv "${AUTOFORM_SELFTEST_IDENTITY_APK}" \
    "${AUTOFORM_SELFTEST_IDENTITY_APK}.toctou-original"
  /bin/ln -s "${AUTOFORM_SELFTEST_IDENTITY_APK}.toctou-original" \
    "${AUTOFORM_SELFTEST_IDENTITY_APK}"
fi
EOF
cat > "${HISTORICAL_JAVA_HOME}/bin/java" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
printf 'openjdk version "17.0.0"\n' >&2
EOF
chmod 700 \
  "${HISTORICAL_REPO}/gradlew" \
  "${HISTORICAL_JAVA_HOME}/bin/java"
printf 'distinct historical identity apk bytes\n' > "${HISTORICAL_IDENTITY_APK}"
chmod 600 "${HISTORICAL_IDENTITY_APK}"
HISTORICAL_IDENTITY_SHA256="$(sha256_file "${HISTORICAL_IDENTITY_APK}")"
/bin/ln -s "${HISTORICAL_IDENTITY_APK}" "${HISTORICAL_SYMLINK_APK}"
cp "${HISTORICAL_IDENTITY_APK}" "${HISTORICAL_BAD_MODE_APK}"
chmod 644 "${HISTORICAL_BAD_MODE_APK}"
HISTORICAL_BAD_MODE_SHA256="$(sha256_file "${HISTORICAL_BAD_MODE_APK}")"
HISTORICAL_INVENTORY="${FIXTURE_ROOT}/historical-release-inventory.private.json"
HISTORICAL_TITLE_FILE="${FIXTURE_ROOT}/historical-release-title.private.txt"
printf '%s' 'autoform-kit 1.0.0' > "${HISTORICAL_TITLE_FILE}"
chmod 600 "${HISTORICAL_TITLE_FILE}"
"${REAL_NODE}" --input-type=module - \
  "${SCRIPT_DIR}/historical-release-contract.mjs" \
  "${HISTORICAL_INVENTORY}" \
  "${HISTORICAL_IDENTITY_SHA256}" <<'EOF'
import fs from "node:fs";
import { pathToFileURL } from "node:url";
const modulePath = process.argv[2];
const outputPath = process.argv[3];
const firstApkSha256 = process.argv[4];
const contract = await import(pathToFileURL(modulePath));
const hex = (character) => character.repeat(64);
const releases = contract.HISTORICAL_TAGS.map((tag, sequence) => {
  const version = tag.slice(1);
  const title = `autoform-kit ${version}`;
  return {
    assets: [
      {name:`autoform-kit-${version}.apk`,size:sequence === 0 ? 39 : 100 + sequence,
        sha256:sequence === 0 ? firstApkSha256 : String(sequence).repeat(64)},
      {name:"update.json",size:40 + sequence,sha256:String(sequence + 2).repeat(64)}
    ].sort((left, right) => left.name.localeCompare(right.name)),
    draft:false,prerelease:false,tagName:tag,
    titleLength:[...title].length,titleSha256:contract.digest(Buffer.from(title))
  };
});
const sourceInventory = {
  schemaVersion:1,kind:"public-rewrite-inventory",tagCount:7,releaseCount:7,assetCount:14,
  stableIdentitySha256:hex("a"),inventorySha256:hex("b"),
  sourceBindings:{gitTagRefListingSha256:hex("c"),releaseMetadataSha256:hex("d")},
  releases
};
const apkIdentities = contract.HISTORICAL_TAGS.map((tag, sequence) => ({
  tag,packageName:"com.example.autoform",versionCode:sequence + 1,
  versionName:tag.slice(1),signerSha256:hex("b")
}));
const updateIdentities = contract.HISTORICAL_TAGS.map((tag, sequence) => ({
  tag,packageName:"com.example.autoform",versionCode:sequence + 1,
  versionName:tag.slice(1),apkAsset:`autoform-kit-${tag.slice(1)}.apk`,
  apkSha256:releases[sequence].assets.find((asset) => asset.name.endsWith(".apk")).sha256,
  notesLength:1,notesSha256:hex("e")
}));
const inventory = contract.createHistoricalInventory({
  sourceFileSha256:hex("f"),sourceInventory,apkIdentities,updateIdentities
});
fs.writeFileSync(outputPath, `${contract.canonicalJson(inventory)}\n`, {mode:0o600});
EOF
chmod 600 "${HISTORICAL_INVENTORY}"

run_historical_release() {
  (
    cd "${HISTORICAL_REPO}"
    PATH="${BIN_DIR}:${PATH}" \
    JAVA_HOME="${HISTORICAL_JAVA_HOME}" \
    ZIPALIGN="${BIN_DIR}/zipalign" \
    AAPT="${BIN_DIR}/aapt" \
    APKSIGNER="${BIN_DIR}/apksigner" \
    AUTOFORM_SELFTEST_SOURCE_COMMIT="${SOURCE_COMMIT}" \
    AUTOFORM_SELFTEST_TREE_OID="${TREE_OID}" \
    AUTOFORM_SELFTEST_SCANNER_BLOB_OID="${SCANNER_BLOB_OID}" \
    AUTOFORM_SELFTEST_SOURCE_VERIFIER_BLOB_OID="${SOURCE_VERIFIER_BLOB_OID}" \
    AUTOFORM_SELFTEST_HISTORICAL_CONTRACT_BLOB_OID="${HISTORICAL_CONTRACT_BLOB_OID}" \
    AUTOFORM_SELFTEST_SIGNER="${SIGNER_SHA256}" \
    AUTOFORM_SELFTEST_IDENTITY_SIGNER="${AUTOFORM_SELFTEST_IDENTITY_SIGNER:-${SIGNER_SHA256}}" \
    AUTOFORM_SELFTEST_IDENTITY_APK="${HISTORICAL_IDENTITY_APK}" \
    AUTOFORM_SELFTEST_PACKAGE="${AUTOFORM_SELFTEST_PACKAGE:-${PACKAGE_NAME}}" \
    AUTOFORM_SELFTEST_IDENTITY_PACKAGE="${AUTOFORM_SELFTEST_IDENTITY_PACKAGE:-${PACKAGE_NAME}}" \
    AUTOFORM_SELFTEST_VERSION_CODE="${AUTOFORM_SELFTEST_VERSION_CODE:-1}" \
    AUTOFORM_SELFTEST_VERSION_NAME="${AUTOFORM_SELFTEST_VERSION_NAME:-1.0.0}" \
    AUTOFORM_SELFTEST_IDENTITY_VERSION_CODE="${AUTOFORM_SELFTEST_IDENTITY_VERSION_CODE:-1}" \
    AUTOFORM_SELFTEST_IDENTITY_VERSION_NAME="${AUTOFORM_SELFTEST_IDENTITY_VERSION_NAME:-1.0.0}" \
    AUTOFORM_SELFTEST_PREVIOUS_VERSION_CODE="${AUTOFORM_SELFTEST_PREVIOUS_VERSION_CODE:-1}" \
    AUTOFORM_SELFTEST_PREVIOUS_VERSION_NAME="${AUTOFORM_SELFTEST_PREVIOUS_VERSION_NAME:-1.0.0}" \
    AUTOFORM_SELFTEST_HISTORY_COMMIT_OBJECT_FIXTURE="${HISTORY_COMMIT_OBJECT_FIXTURE}" \
    AUTOFORM_SELFTEST_PRIVATE_WORDLIST="${PRIVATE_WORDLIST}" \
    AUTOFORM_SELFTEST_TREE_REPORT="${TREE_REPORT}" \
    AUTOFORM_SELFTEST_STALE_TREE_REPORT="${STALE_TREE_REPORT}" \
    AUTOFORM_SELFTEST_WORKTREE_REPORT="${WORKTREE_REPORT}" \
    AUTOFORM_SELFTEST_APK_REPORT="${APK_REPORT}" \
    AUTOFORM_SELFTEST_SOURCE_PROVENANCE_REPORT="${SOURCE_PROVENANCE_REPORT}" \
    AUTOFORM_SELFTEST_STALE_SOURCE_PROVENANCE_REPORT_PATH="${STALE_SOURCE_PROVENANCE_REPORT}" \
    AUTOFORM_SELFTEST_PUBLIC_AUDIT_SCANNER_SHA256="${PUBLIC_AUDIT_SCANNER_SHA256}" \
    AUTOFORM_SELFTEST_AUDIT_POLICY_SHA256="${POLICY_SHA256}" \
    AUTOFORM_SELFTEST_THIRD_PARTY_POLICY_SHA256="${PUBLIC_THIRD_PARTY_POLICY_SHA256}" \
    AUTOFORM_SELFTEST_DYNAMIC_FILE_REPORT=true \
    AUTOFORM_SELFTEST_GRADLE_LOG="${HISTORICAL_GRADLE_LOG}" \
    AUTOFORM_SELFTEST_AUDIT_INPUT_LOG="${HISTORICAL_AUDIT_INPUT_LOG}" \
    AUTOFORM_SELFTEST_MUTATE_IDENTITY_DURING_BUILD="${AUTOFORM_SELFTEST_MUTATE_IDENTITY_DURING_BUILD:-false}" \
    AUTOFORM_SELFTEST_CHANGE_IDENTITY_MODE_DURING_BUILD="${AUTOFORM_SELFTEST_CHANGE_IDENTITY_MODE_DURING_BUILD:-false}" \
    AUTOFORM_SELFTEST_SYMLINK_IDENTITY_DURING_BUILD="${AUTOFORM_SELFTEST_SYMLINK_IDENTITY_DURING_BUILD:-false}" \
    AUTOFORM_SELFTEST_REAL_SHASUM="${REAL_SHASUM}" \
    AUTOFORM_SELFTEST_REAL_SHA256SUM="${REAL_SHA256SUM}" \
    AUTOFORM_SELFTEST_REAL_NODE="${REAL_NODE}" \
      bash tools/release.sh "$@"
  )
}

if run_historical_release \
  --version 1.2.4 \
  --version-code 124 \
  --private-wordlist "${PRIVATE_WORDLIST}" \
  >"${FIXTURE_ROOT}/standard-missing-previous.log" 2>&1; then
  die "standard release path stopped requiring --previous-apk"
fi
grep -q -- '--previous-apk is required' \
  "${FIXTURE_ROOT}/standard-missing-previous.log" || \
  die "standard release path no longer reports its previous-APK requirement"
[[ ! -s "${HISTORICAL_GRADLE_LOG}" ]] || \
  die "missing standard previous APK reached Gradle"

# Historical routing no longer accepts a caller-selected APK+SHA pair. It requires the exact
# mode-0600 pre-rewrite inventory, the selected original APK, and an exact title file.
if run_historical_release \
  --version 1.0.0 \
  --version-code 1 \
  --historical-initial-identity-apk "${HISTORICAL_IDENTITY_APK}" \
  --private-wordlist "${PRIVATE_WORDLIST}" \
  >"${FIXTURE_ROOT}/historical-retired-anchor.log" 2>&1; then
  die "historical mode accepted the retired unbound identity option"
fi
grep -q 'unbound historical identity options were removed' \
  "${FIXTURE_ROOT}/historical-retired-anchor.log" || \
  die "historical mode did not explain the retired unbound identity option"
[[ ! -s "${HISTORICAL_GRADLE_LOG}" ]] || \
  die "retired historical identity option reached Gradle"

if run_historical_release \
  --version 1.0.0 \
  --version-code 1 \
  --historical-inventory "${HISTORICAL_INVENTORY}" \
  --historical-original-apk "${HISTORICAL_IDENTITY_APK}" \
  --private-wordlist "${PRIVATE_WORDLIST}" \
  >"${FIXTURE_ROOT}/historical-missing-title.log" 2>&1; then
  die "historical mode accepted a missing exact title input"
fi
grep -q 'requires inventory, original APK, and exact title file' \
  "${FIXTURE_ROOT}/historical-missing-title.log" || \
  die "historical mode did not explain its complete inventory inputs"

if run_historical_release \
  --version 1.0.0 \
  --version-code 1 \
  --notes 'caller supplied old body' \
  --historical-inventory "${HISTORICAL_INVENTORY}" \
  --historical-original-apk "${HISTORICAL_IDENTITY_APK}" \
  --historical-title-file "${HISTORICAL_TITLE_FILE}" \
  --private-wordlist "${PRIVATE_WORDLIST}" \
  >"${FIXTURE_ROOT}/historical-custom-body.log" 2>&1; then
  die "historical mode accepted a caller-controlled Release body"
fi
grep -q 'fixed audited body' "${FIXTURE_ROOT}/historical-custom-body.log" || \
  die "historical mode did not explain the fixed-body boundary"

if run_historical_release \
  --version 1.0.0 \
  --version-code 1 \
  --historical-inventory "${HISTORICAL_INVENTORY}" \
  --historical-original-apk "${HISTORICAL_SYMLINK_APK}" \
  --historical-title-file "${HISTORICAL_TITLE_FILE}" \
  --private-wordlist "${PRIVATE_WORDLIST}" \
  >"${FIXTURE_ROOT}/historical-symlink-v4.log" 2>&1; then
  die "historical mode accepted a symlink original APK"
fi
grep -q 'must be a regular non-symlink file' \
  "${FIXTURE_ROOT}/historical-symlink-v4.log" || \
  die "historical mode did not explain the original APK symlink rejection"

: > "${HISTORICAL_GRADLE_LOG}"
if run_historical_release \
  --version 1.0.0 \
  --version-code 1 \
  --historical-inventory "${HISTORICAL_INVENTORY}" \
  --historical-original-apk "${HISTORICAL_BAD_MODE_APK}" \
  --historical-title-file "${HISTORICAL_TITLE_FILE}" \
  --private-wordlist "${PRIVATE_WORDLIST}" \
  >"${FIXTURE_ROOT}/historical-bad-mode-v4.log" 2>&1; then
  die "historical mode accepted a non-private original APK"
fi
grep -q 'must be owned by the current user, have mode 0600, and have one link' \
  "${FIXTURE_ROOT}/historical-bad-mode-v4.log" || \
  die "historical mode did not explain the original APK permission contract"
[[ ! -s "${HISTORICAL_GRADLE_LOG}" ]] || \
  die "bad-mode historical original APK reached Gradle"

/bin/ln "${HISTORICAL_IDENTITY_APK}" "${HISTORICAL_HARDLINK_APK}"
if run_historical_release \
  --version 1.0.0 \
  --version-code 1 \
  --historical-inventory "${HISTORICAL_INVENTORY}" \
  --historical-original-apk "${HISTORICAL_HARDLINK_APK}" \
  --historical-title-file "${HISTORICAL_TITLE_FILE}" \
  --private-wordlist "${PRIVATE_WORDLIST}" \
  >"${FIXTURE_ROOT}/historical-hardlink-v4.log" 2>&1; then
  die "historical mode accepted a multiply-linked original APK"
fi
grep -q 'must be owned by the current user, have mode 0600, and have one link' \
  "${FIXTURE_ROOT}/historical-hardlink-v4.log" || \
  die "historical mode did not explain the original APK single-link contract"
[[ ! -s "${HISTORICAL_GRADLE_LOG}" ]] || \
  die "hard-linked historical original APK reached Gradle"
rm -f "${HISTORICAL_HARDLINK_APK}"

: > "${HISTORICAL_AUDIT_INPUT_LOG}"
run_historical_release \
  --version 1.0.0 \
  --version-code 1 \
  --config config/signing.local.json \
  --historical-inventory "${HISTORICAL_INVENTORY}" \
  --historical-original-apk "${HISTORICAL_IDENTITY_APK}" \
  --historical-title-file "${HISTORICAL_TITLE_FILE}" \
  --private-wordlist "${PRIVATE_WORDLIST}" \
  --offline >"${FIXTURE_ROOT}/historical-v4-success.log" 2>&1 || {
    /bin/cat "${FIXTURE_ROOT}/historical-v4-success.log" >&2
    die "historical schema-4 candidate fixture failed"
  }
HISTORICAL_CANDIDATE_DIR="${HISTORICAL_REPO}/dist/release-candidates/v1.0.0"
HISTORICAL_MANIFEST="${HISTORICAL_CANDIDATE_DIR}/candidate-manifest.json"
HISTORICAL_CANDIDATE_APK="${HISTORICAL_CANDIDATE_DIR}/$(basename "${HISTORICAL_IDENTITY_APK}")"
[[ "$(find "${HISTORICAL_CANDIDATE_DIR}" -mindepth 1 -maxdepth 1 -type f \
  | wc -l | tr -d '[:space:]')" == "4" ]] || \
  die "historical schema-4 candidate did not contain exactly four local files"
[[ "$(sha256_file "${HISTORICAL_CANDIDATE_APK}")" != "${HISTORICAL_IDENTITY_SHA256}" ]] || \
  die "historical schema-4 candidate reused the original APK bytes"
grep -Eq $'^apk\t.*/autoform-kit-1\\.0\\.0\\.apk\t[0-9a-f]{64}$' \
  "${HISTORICAL_AUDIT_INPUT_LOG}" || \
  die "historical schema-4 fixture did not scan its generated candidate APK"
if grep -Fq -- $'\t'"${HISTORICAL_IDENTITY_APK}" "${HISTORICAL_AUDIT_INPUT_LOG}"; then
  die "historical original APK path entered the public surface scanner"
fi
jq -e \
  --arg inventoryFileSha256 "$(sha256_file "${HISTORICAL_INVENTORY}")" \
  --arg identitySha256 "${HISTORICAL_IDENTITY_SHA256}" \
  --arg fixedBody 'Sanitized historical rebuild from the public autoform-kit framework. No site-specific configuration is included.' \
  '.schemaVersion == 4
    and .publicationMode == "historical-rewrite-non-latest"
    and (keys == ["app", "artifacts", "historicalRelease", "lineage", "publicAudit", "publicationMode", "schemaVersion", "source", "tag"])
    and .tag == "v1.0.0"
    and .lineage.kind == "historical-initial-rebuild"
    and .lineage.sequence == 0
    and .lineage.previousRebuiltCandidate == null
    and .lineage.originalApk.sha256 == $identitySha256
    and .artifacts.apk.sha256 != .lineage.originalApk.sha256
    and .historicalRelease.inventory.fileSha256 == $inventoryFileSha256
    and .historicalRelease.original.originalApk.sha256 == $identitySha256
    and .historicalRelease.publication.body == $fixedBody
      and .historicalRelease.publication.bodyPolicy == "fixed-source-bound-generic-v1"
      and .historicalRelease.publication.makeLatest == false
      and (.historicalRelease.publication.assets | length) == 2' \
  "${HISTORICAL_MANIFEST}" >/dev/null || \
  {
    jq '{topKeys:keys, schemaVersion, publicationMode, tag, lineage,
      artifactApk:.artifacts.apk, inventory:.historicalRelease.inventory,
      originalApk:.historicalRelease.original.originalApk,
      publication:.historicalRelease.publication}' "${HISTORICAL_MANIFEST}" >&2
    die "historical candidate manifest did not use the strict schema-4 publication lineage"
  }
grep -q 'historical schema 4 candidate' "${FIXTURE_ROOT}/historical-v4-success.log" || \
  die "historical candidate did not explain its separate publication boundary"

# Reserved historical versions can no longer be generated through the ordinary schema-2 path.
if AUTOFORM_SELFTEST_VERSION_CODE=2 AUTOFORM_SELFTEST_VERSION_NAME=1.0.1 \
  run_historical_release \
    --version 1.0.1 \
    --version-code 2 \
    --config config/signing.local.json \
    --previous-apk "${PREVIOUS_APK}" \
    --private-wordlist "${PRIVATE_WORDLIST}" \
    >"${FIXTURE_ROOT}/reserved-standard-build.log" 2>&1; then
  die "release.sh generated a schema-2 candidate for a reserved historical version"
fi
grep -q 'reserved historical versions require the inventory-bound historical mode' \
  "${FIXTURE_ROOT}/reserved-standard-build.log" || \
  die "release.sh did not explain the reserved historical version boundary"

# The normal stable publisher rejects schema 4 before its private gate or GitHub command.
cp "${MANIFEST_PATH}" "${FIXTURE_ROOT}/candidate-manifest.schema2.backup"
cp "${HISTORICAL_MANIFEST}" "${MANIFEST_PATH}"
: > "${GATE_LOG}"
: > "${GH_LOG}"
if run_publish >"${FIXTURE_ROOT}/historical-publisher-v4-rejection.log" 2>&1; then
  die "standard publisher accepted a historical schema-4 candidate"
fi
grep -q 'historical or explicitly routed candidates cannot be published' \
  "${FIXTURE_ROOT}/historical-publisher-v4-rejection.log" || \
  die "standard publisher did not explain the historical routing rejection"
[[ ! -s "${GATE_LOG}" && ! -s "${GH_LOG}" ]] || \
  die "historical schema 4 reached the private gate or GitHub command"
mv "${FIXTURE_ROOT}/candidate-manifest.schema2.backup" "${MANIFEST_PATH}"

# Alternate schema numbers, explicit historical routing, reserved tags, and a
# reserved versionName hidden behind a newer tag all stop before any remote call.
cp "${MANIFEST_PATH}" "${FIXTURE_ROOT}/candidate-manifest.standard.backup"
for rejected_schema in 3 4; do
  jq --argjson schema "${rejected_schema}" '.schemaVersion = $schema' \
    "${FIXTURE_ROOT}/candidate-manifest.standard.backup" > "${MANIFEST_PATH}"
  : > "${GATE_LOG}"
  : > "${GH_LOG}"
  if run_publish >"${FIXTURE_ROOT}/standard-schema-${rejected_schema}-rejection.log" 2>&1; then
    die "standard publisher accepted historical schema ${rejected_schema}"
  fi
  [[ ! -s "${GATE_LOG}" && ! -s "${GH_LOG}" ]] || \
    die "historical schema ${rejected_schema} reached a gate or GitHub command"
done
jq '.publicationMode = "historical-rewrite-non-latest"' \
  "${FIXTURE_ROOT}/candidate-manifest.standard.backup" > "${MANIFEST_PATH}"
: > "${GATE_LOG}"
: > "${GH_LOG}"
if run_publish >"${FIXTURE_ROOT}/standard-publication-mode-rejection.log" 2>&1; then
  die "standard publisher accepted an explicitly historical publication mode"
fi
[[ ! -s "${GATE_LOG}" && ! -s "${GH_LOG}" ]] || \
  die "historical publication mode reached a gate or GitHub command"
for reserved_version in 1.0.0 1.0.1 1.0.2 1.0.3 1.0.4 1.0.5 1.0.6; do
  jq --arg tag "v${reserved_version}" --arg version "${reserved_version}" \
    '.tag = $tag | .app.versionName = $version' \
    "${FIXTURE_ROOT}/candidate-manifest.standard.backup" > "${MANIFEST_PATH}"
  : > "${GATE_LOG}"
  : > "${GH_LOG}"
  if run_publish >"${FIXTURE_ROOT}/reserved-${reserved_version}-rejection.log" 2>&1; then
    die "standard publisher accepted reserved tag v${reserved_version}"
  fi
  [[ ! -s "${GATE_LOG}" && ! -s "${GH_LOG}" ]] || \
    die "reserved tag v${reserved_version} reached a gate or GitHub command"
done
jq '.app.versionName = "1.0.6"' \
  "${FIXTURE_ROOT}/candidate-manifest.standard.backup" > "${MANIFEST_PATH}"
: > "${GATE_LOG}"
: > "${GH_LOG}"
if run_publish >"${FIXTURE_ROOT}/reserved-version-disguise-rejection.log" 2>&1; then
  die "standard publisher accepted a reserved versionName behind another tag"
fi
[[ ! -s "${GATE_LOG}" && ! -s "${GH_LOG}" ]] || \
  die "reserved versionName disguise reached a gate or GitHub command"
mv "${FIXTURE_ROOT}/candidate-manifest.standard.backup" "${MANIFEST_PATH}"

# The block below retains obsolete fixture cases only as inert documentation while the new
# inventory-bound cases above replace them.
if false; then
if run_historical_release \
  --version 1.0.0 \
  --version-code 1 \
  --historical-initial-identity-apk "${HISTORICAL_IDENTITY_APK}" \
  --private-wordlist "${PRIVATE_WORDLIST}" \
  >"${FIXTURE_ROOT}/historical-partial-identity.log" 2>&1; then
  die "historical initial mode accepted a missing identity SHA-256"
fi
grep -q 'requires both identity APK and expected SHA-256' \
  "${FIXTURE_ROOT}/historical-partial-identity.log" || \
  die "historical initial mode did not explain its paired identity inputs"

if run_historical_release \
  --version 1.0.0 \
  --version-code 1 \
  --previous-apk "${HISTORICAL_IDENTITY_APK}" \
  --historical-initial-identity-apk "${HISTORICAL_IDENTITY_APK}" \
  --historical-initial-identity-sha256 "${HISTORICAL_IDENTITY_SHA256}" \
  --private-wordlist "${PRIVATE_WORDLIST}" \
  >"${FIXTURE_ROOT}/historical-mixed-lineage.log" 2>&1; then
  die "historical initial mode accepted --previous-apk"
fi
grep -q 'mutually exclusive with --previous-apk' \
  "${FIXTURE_ROOT}/historical-mixed-lineage.log" || \
  die "historical initial mode did not explain the lineage-mode boundary"

if run_historical_release \
  --version 1.0.1 \
  --version-code 2 \
  --historical-initial-identity-apk "${HISTORICAL_IDENTITY_APK}" \
  --historical-initial-identity-sha256 "${HISTORICAL_IDENTITY_SHA256}" \
  --private-wordlist "${PRIVATE_WORDLIST}" \
  >"${FIXTURE_ROOT}/historical-wrong-version.log" 2>&1; then
  die "historical initial mode accepted a version other than v1.0.0/code 1"
fi
grep -q 'restricted to versionName 1.0.0 and versionCode 1' \
  "${FIXTURE_ROOT}/historical-wrong-version.log" || \
  die "historical initial mode did not explain its exact version boundary"

if run_historical_release \
  --version 1.0.0 \
  --version-code 1 \
  --historical-initial-identity-apk "${HISTORICAL_BAD_MODE_APK}" \
  --historical-initial-identity-sha256 "${HISTORICAL_BAD_MODE_SHA256}" \
  --private-wordlist "${PRIVATE_WORDLIST}" \
  >"${FIXTURE_ROOT}/historical-bad-mode.log" 2>&1; then
  die "historical initial mode accepted a non-private identity APK"
fi
grep -q 'must have mode 0600' "${FIXTURE_ROOT}/historical-bad-mode.log" || \
  die "historical initial mode did not explain the identity APK mode requirement"

if run_historical_release \
  --version 1.0.0 \
  --version-code 1 \
  --historical-initial-identity-apk "${HISTORICAL_SYMLINK_APK}" \
  --historical-initial-identity-sha256 "${HISTORICAL_IDENTITY_SHA256}" \
  --private-wordlist "${PRIVATE_WORDLIST}" \
  >"${FIXTURE_ROOT}/historical-symlink.log" 2>&1; then
  die "historical initial mode accepted a symlink identity APK"
fi
grep -q 'must be a regular non-symlink file' \
  "${FIXTURE_ROOT}/historical-symlink.log" || \
  die "historical initial mode did not explain the identity symlink rejection"

if run_historical_release \
  --version 1.0.0 \
  --version-code 1 \
  --historical-initial-identity-apk "${HISTORICAL_IDENTITY_APK}" \
  --historical-initial-identity-sha256 \
    "0000000000000000000000000000000000000000000000000000000000000000" \
  --private-wordlist "${PRIVATE_WORDLIST}" \
  >"${FIXTURE_ROOT}/historical-bad-sha.log" 2>&1; then
  die "historical initial mode accepted a mismatched identity APK SHA-256"
fi
grep -q 'SHA-256 does not match the expected value' \
  "${FIXTURE_ROOT}/historical-bad-sha.log" || \
  die "historical initial mode did not explain the identity SHA-256 mismatch"

if AUTOFORM_SELFTEST_IDENTITY_PACKAGE=com.example.wrong \
  run_historical_release \
    --version 1.0.0 \
    --version-code 1 \
    --config config/signing.local.json \
    --historical-initial-identity-apk "${HISTORICAL_IDENTITY_APK}" \
    --historical-initial-identity-sha256 "${HISTORICAL_IDENTITY_SHA256}" \
    --private-wordlist "${PRIVATE_WORDLIST}" \
    >"${FIXTURE_ROOT}/historical-package-mismatch.log" 2>&1; then
  die "historical initial mode accepted an identity APK from another package"
fi
grep -q 'package does not match the source applicationId' \
  "${FIXTURE_ROOT}/historical-package-mismatch.log" || \
  die "historical initial mode did not explain the identity package mismatch"

if AUTOFORM_SELFTEST_IDENTITY_VERSION_CODE=2 \
  AUTOFORM_SELFTEST_IDENTITY_VERSION_NAME=1.0.1 \
  run_historical_release \
    --version 1.0.0 \
    --version-code 1 \
    --config config/signing.local.json \
    --historical-initial-identity-apk "${HISTORICAL_IDENTITY_APK}" \
    --historical-initial-identity-sha256 "${HISTORICAL_IDENTITY_SHA256}" \
    --private-wordlist "${PRIVATE_WORDLIST}" \
    >"${FIXTURE_ROOT}/historical-version-mismatch.log" 2>&1; then
  die "historical initial mode accepted a non-v1.0.0/code-1 identity APK"
fi
grep -q 'identity APK must be versionName 1.0.0 and versionCode 1' \
  "${FIXTURE_ROOT}/historical-version-mismatch.log" || \
  die "historical initial mode did not explain the identity version mismatch"

if AUTOFORM_SELFTEST_IDENTITY_SIGNER=cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc \
  run_historical_release \
    --version 1.0.0 \
    --version-code 1 \
    --config config/signing.local.json \
    --historical-initial-identity-apk "${HISTORICAL_IDENTITY_APK}" \
    --historical-initial-identity-sha256 "${HISTORICAL_IDENTITY_SHA256}" \
    --private-wordlist "${PRIVATE_WORDLIST}" \
    >"${FIXTURE_ROOT}/historical-signer-mismatch.log" 2>&1; then
  die "historical initial mode accepted a different candidate signer"
fi
grep -q 'certificate does not match the historical identity anchor' \
  "${FIXTURE_ROOT}/historical-signer-mismatch.log" || \
  die "historical initial mode did not explain the signer mismatch"

if AUTOFORM_SELFTEST_MUTATE_IDENTITY_DURING_BUILD=true \
  run_historical_release \
    --version 1.0.0 \
    --version-code 1 \
    --config config/signing.local.json \
    --historical-initial-identity-apk "${HISTORICAL_IDENTITY_APK}" \
    --historical-initial-identity-sha256 "${HISTORICAL_IDENTITY_SHA256}" \
    --private-wordlist "${PRIVATE_WORDLIST}" \
    >"${FIXTURE_ROOT}/historical-bytes-toctou.log" 2>&1; then
  die "historical initial mode accepted identity APK bytes changing during the build"
fi
grep -q 'identity APK changed while building the candidate' \
  "${FIXTURE_ROOT}/historical-bytes-toctou.log" || \
  die "historical initial mode did not explain the identity byte change"
printf 'distinct historical identity apk bytes\n' > "${HISTORICAL_IDENTITY_APK}"
chmod 600 "${HISTORICAL_IDENTITY_APK}"

if AUTOFORM_SELFTEST_CHANGE_IDENTITY_MODE_DURING_BUILD=true \
  run_historical_release \
    --version 1.0.0 \
    --version-code 1 \
    --config config/signing.local.json \
    --historical-initial-identity-apk "${HISTORICAL_IDENTITY_APK}" \
    --historical-initial-identity-sha256 "${HISTORICAL_IDENTITY_SHA256}" \
    --private-wordlist "${PRIVATE_WORDLIST}" \
    >"${FIXTURE_ROOT}/historical-mode-toctou.log" 2>&1; then
  die "historical initial mode accepted identity APK permissions changing during the build"
fi
grep -q 'must have mode 0600' "${FIXTURE_ROOT}/historical-mode-toctou.log" || \
  die "historical initial mode did not recheck identity APK permissions"
chmod 600 "${HISTORICAL_IDENTITY_APK}"

if AUTOFORM_SELFTEST_SYMLINK_IDENTITY_DURING_BUILD=true \
  run_historical_release \
    --version 1.0.0 \
    --version-code 1 \
    --config config/signing.local.json \
    --historical-initial-identity-apk "${HISTORICAL_IDENTITY_APK}" \
    --historical-initial-identity-sha256 "${HISTORICAL_IDENTITY_SHA256}" \
    --private-wordlist "${PRIVATE_WORDLIST}" \
    >"${FIXTURE_ROOT}/historical-symlink-toctou.log" 2>&1; then
  die "historical initial mode accepted an identity APK symlink replacement"
fi
grep -q 'must be a regular non-symlink file' \
  "${FIXTURE_ROOT}/historical-symlink-toctou.log" || \
  die "historical initial mode did not recheck the identity APK file type"
[[ -L "${HISTORICAL_IDENTITY_APK}" \
  && -f "${HISTORICAL_IDENTITY_APK}.toctou-original" ]] || \
  die "historical symlink TOCTOU fixture did not replace the identity pathname"
/bin/rm "${HISTORICAL_IDENTITY_APK}"
/bin/mv "${HISTORICAL_IDENTITY_APK}.toctou-original" \
  "${HISTORICAL_IDENTITY_APK}"
chmod 600 "${HISTORICAL_IDENTITY_APK}"

: > "${HISTORICAL_AUDIT_INPUT_LOG}"
run_historical_release \
  --version 1.0.0 \
  --version-code 1 \
  --config config/signing.local.json \
  --historical-initial-identity-apk "${HISTORICAL_IDENTITY_APK}" \
  --historical-initial-identity-sha256 "${HISTORICAL_IDENTITY_SHA256}" \
  --private-wordlist "${PRIVATE_WORDLIST}" \
  --offline >"${FIXTURE_ROOT}/historical-success.log" 2>&1 || {
    /bin/cat "${FIXTURE_ROOT}/historical-success.log" >&2
    die "historical initial candidate fixture failed"
  }
HISTORICAL_CANDIDATE_DIR="${HISTORICAL_REPO}/dist/release-candidates/v1.0.0"
HISTORICAL_MANIFEST="${HISTORICAL_CANDIDATE_DIR}/candidate-manifest.json"
HISTORICAL_CANDIDATE_APK="${HISTORICAL_CANDIDATE_DIR}/autoform-kit-1.0.0.apk"
[[ "$(find "${HISTORICAL_CANDIDATE_DIR}" -mindepth 1 -maxdepth 1 -type f | wc -l | tr -d '[:space:]')" \
  == "4" ]] || die "historical initial candidate did not contain exactly four files"
[[ "$(sha256_file "${HISTORICAL_CANDIDATE_APK}")" != "${HISTORICAL_IDENTITY_SHA256}" ]] || \
  die "historical initial candidate reused the private identity APK bytes"
while IFS= read -r HISTORICAL_CANDIDATE_FILE; do
  [[ "$(sha256_file "${HISTORICAL_CANDIDATE_FILE}")" != "${HISTORICAL_IDENTITY_SHA256}" ]] || \
    die "historical identity APK bytes entered the candidate directory"
done < <(find "${HISTORICAL_CANDIDATE_DIR}" -mindepth 1 -maxdepth 1 -type f -print)
[[ -s "${HISTORICAL_AUDIT_INPUT_LOG}" ]] || \
  die "historical initial fixture did not record public audit inputs"
if grep -Fq -- $'\t'"${HISTORICAL_IDENTITY_APK}" "${HISTORICAL_AUDIT_INPUT_LOG}"; then
  die "historical identity APK path entered the public surface scanner"
fi
if grep -Fq -- $'\t'"${HISTORICAL_IDENTITY_SHA256}" "${HISTORICAL_AUDIT_INPUT_LOG}"; then
  die "historical identity APK bytes entered the public surface scanner"
fi
grep -Eq $'^apk\t.*/autoform-kit-1\\.0\\.0\\.apk\t[0-9a-f]{64}$' \
  "${HISTORICAL_AUDIT_INPUT_LOG}" || \
  die "historical initial fixture did not scan its generated candidate APK"
jq -e \
  --arg identitySha256 "${HISTORICAL_IDENTITY_SHA256}" \
  --arg signerSha256 "${SIGNER_SHA256}" \
  '.schemaVersion == 3
    and (keys == ["app", "artifacts", "lineage", "publicAudit", "schemaVersion", "source", "tag"])
    and .tag == "v1.0.0"
    and .app.versionName == "1.0.0"
    and .app.versionCode == 1
    and .lineage.kind == "historical-initial-rebuild"
    and (.lineage | keys == ["identityAnchor", "kind"])
    and .lineage.identityAnchor.sha256 == $identitySha256
    and .artifacts.apk.sha256 != .lineage.identityAnchor.sha256
    and .lineage.identityAnchor.versionName == "1.0.0"
    and .lineage.identityAnchor.versionCode == 1
    and .lineage.identityAnchor.signerSha256 == $signerSha256
    and (.lineage.identityAnchor
      | keys == ["packageName", "sha256", "signerSha256", "versionCode", "versionName"])
    and (has("previousApk") | not)' "${HISTORICAL_MANIFEST}" >/dev/null || \
  die "historical initial candidate manifest did not use the strict schema-3 lineage"
grep -q 'tools/publish-release.sh intentionally rejects this candidate' \
  "${FIXTURE_ROOT}/historical-success.log" || \
  die "historical initial candidate did not explain its non-publishable boundary"

# A normal upgrade built by the same implementation must retain schema 2 and
# the real previousApk identity contract.
AUTOFORM_SELFTEST_VERSION_CODE=2 \
AUTOFORM_SELFTEST_VERSION_NAME=1.0.1 \
AUTOFORM_SELFTEST_PREVIOUS_VERSION_CODE=1 \
AUTOFORM_SELFTEST_PREVIOUS_VERSION_NAME=1.0.0 \
run_historical_release \
  --version 1.0.1 \
  --version-code 2 \
  --config config/signing.local.json \
  --previous-apk "${PREVIOUS_APK}" \
  --private-wordlist "${PRIVATE_WORDLIST}" \
  --offline >"${FIXTURE_ROOT}/standard-upgrade-success.log" 2>&1 || {
    /bin/cat "${FIXTURE_ROOT}/standard-upgrade-success.log" >&2
    die "standard upgrade candidate fixture failed after adding historical mode"
  }
STANDARD_UPGRADE_MANIFEST="${HISTORICAL_REPO}/dist/release-candidates/v1.0.1/candidate-manifest.json"
jq -e '
  .schemaVersion == 2
  and (keys == ["app", "artifacts", "previousApk", "publicAudit", "schemaVersion", "source", "tag"])
  and .tag == "v1.0.1"
  and .app.versionName == "1.0.1"
  and .app.versionCode == 2
  and .previousApk.versionName == "1.0.0"
  and .previousApk.versionCode == 1
  and (has("lineage") | not)' "${STANDARD_UPGRADE_MANIFEST}" >/dev/null || \
  die "standard upgrade manifest changed from the schema-2 previousApk contract"

# The normal stable publisher must reject the historical schema before the
# private gate or GitHub can be reached, even if all standard arguments exist.
cp "${MANIFEST_PATH}" "${FIXTURE_ROOT}/candidate-manifest.schema2.backup"
cp "${HISTORICAL_MANIFEST}" "${MANIFEST_PATH}"
: > "${GATE_LOG}"
: > "${GH_LOG}"
if run_publish >"${FIXTURE_ROOT}/historical-publisher-rejection.log" 2>&1; then
  die "standard publisher accepted a historical-initial schema-3 candidate"
fi
grep -q 'historical-initial schema version 3 candidates cannot be published' \
  "${FIXTURE_ROOT}/historical-publisher-rejection.log" || \
  die "standard publisher did not explicitly explain the schema-3 rejection"
[[ ! -s "${GATE_LOG}" && ! -s "${GH_LOG}" ]] || \
  die "historical schema reached the private gate or GitHub release command"
mv "${FIXTURE_ROOT}/candidate-manifest.schema2.backup" "${MANIFEST_PATH}"
fi

# Neither phase may silently skip the repository-external private policy.
if PATH="${BIN_DIR}:${PATH}" bash "${PUBLISH_SCRIPT}" \
  --candidate "${MANIFEST_PATH}" \
  --previous-apk "${PREVIOUS_APK}" \
  --gate "${GATE_PROGRAM}" >"${FIXTURE_ROOT}/missing-wordlist.log" 2>&1; then
  die "publisher accepted a missing private wordlist"
fi
[[ ! -s "${GATE_LOG}" && ! -s "${GH_LOG}" ]] || \
  die "publisher reached a side-effect gate without a private wordlist"

# This publisher owns only the stable /releases/latest route. A prerelease
# candidate must stop before repository access, the private gate, or release creation;
# the device beta channel uses the separate fixed tag "beta" and must never become latest.
cp "${MANIFEST_PATH}" "${FIXTURE_ROOT}/candidate-manifest.stable.backup"
jq '.tag = "v1.2.4-beta.1" | .app.versionName = "1.2.4-beta.1"' \
  "${MANIFEST_PATH}" > "${FIXTURE_ROOT}/candidate-manifest.prerelease"
mv "${FIXTURE_ROOT}/candidate-manifest.prerelease" "${MANIFEST_PATH}"
if run_publish >"${FIXTURE_ROOT}/prerelease-candidate.log" 2>&1; then
  die "stable publisher accepted a beta/prerelease candidate"
fi
grep -q 'only publishes stable candidates' "${FIXTURE_ROOT}/prerelease-candidate.log" || \
  die "stable publisher did not explain the beta/prerelease boundary"
[[ ! -s "${GATE_LOG}" && ! -s "${GH_LOG}" ]] || \
  die "prerelease candidate reached the private gate or release creation"
mv "${FIXTURE_ROOT}/candidate-manifest.stable.backup" "${MANIFEST_PATH}"

# Authenticated access is insufficient: the installed Apps read Releases anonymously.
if AUTOFORM_SELFTEST_REPO_VISIBILITY=private \
  run_publish >"${FIXTURE_ROOT}/private-source-repository.log" 2>&1; then
  die "publisher accepted a private source update repository"
fi
grep -q 'source update repository must be confirmed public' \
  "${FIXTURE_ROOT}/private-source-repository.log" || \
  die "publisher did not explain the public update repository requirement"
[[ ! -s "${GATE_LOG}" && ! -s "${GH_LOG}" ]] || \
  die "private source repository reached the private gate or release creation"

# A byte mismatch must stop before the private gate and release command.
cp "${UPDATE_PATH}" "${FIXTURE_ROOT}/update.backup"
printf 'tampered\n' >> "${UPDATE_PATH}"
if run_publish >"${FIXTURE_ROOT}/tamper.log" 2>&1; then
  die "publisher accepted a changed update.json"
fi
[[ ! -s "${GATE_LOG}" && ! -s "${GH_LOG}" ]] || \
  die "publisher reached the gate or release command after a hash mismatch"
cp "${FIXTURE_ROOT}/update.backup" "${UPDATE_PATH}"

# A fresh scanner finding must stop before an attestation or release command.
if AUTOFORM_SELFTEST_AUDIT_FAIL=true run_publish >"${FIXTURE_ROOT}/audit-finding.log" 2>&1; then
  die "publisher accepted a failed fresh public audit"
fi
[[ ! -s "${GATE_LOG}" && ! -s "${GH_LOG}" ]] || \
  die "publisher reached the gate after a public audit failure"

# Public release notes, update metadata, and candidate manifest are scanned too.
if AUTOFORM_SELFTEST_FILE_AUDIT_FAIL=true run_publish >"${FIXTURE_ROOT}/release-metadata-finding.log" 2>&1; then
  die "publisher accepted prohibited data in public release metadata"
fi
[[ ! -s "${GATE_LOG}" && ! -s "${GH_LOG}" ]] || \
  die "publisher reached the gate after a public release metadata finding"

# A valid report for different update.json bytes cannot satisfy the manifest binding.
if AUTOFORM_SELFTEST_STALE_METADATA_REPORT=true \
  run_publish >"${FIXTURE_ROOT}/stale-metadata-report.log" 2>&1; then
  die "publisher accepted an update.json audit for different bytes"
fi
[[ ! -s "${GATE_LOG}" && ! -s "${GH_LOG}" ]] || \
  die "publisher reached the gate after a metadata report/input mismatch"

# candidate-manifest.json cannot self-bind its report hash, so its fresh scan must
# at least bind the exact selected manifest SHA before the private attestation.
if AUTOFORM_SELFTEST_STALE_MANIFEST_REPORT=true \
  run_publish >"${FIXTURE_ROOT}/stale-manifest-report.log" 2>&1; then
  die "publisher accepted a candidate manifest audit for different bytes"
fi
[[ ! -s "${GATE_LOG}" && ! -s "${GH_LOG}" ]] || \
  die "publisher reached the gate after a candidate manifest report/input mismatch"

# A valid-looking old report with a different input hash must not be trusted.
if AUTOFORM_SELFTEST_STALE_REPORT=true run_publish >"${FIXTURE_ROOT}/stale-report.log" 2>&1; then
  die "publisher accepted a stale public audit report"
fi
[[ ! -s "${GATE_LOG}" && ! -s "${GH_LOG}" ]] || \
  die "publisher reached the gate after a stale report mismatch"

# Scanner bytes are sampled on both sides of every scan to close that TOCTOU window.
if AUTOFORM_SELFTEST_SCANNER_SHA_FLIP=true run_publish >"${FIXTURE_ROOT}/scanner-toctou.log" 2>&1; then
  die "publisher accepted scanner bytes changing during an audit"
fi
[[ ! -s "${GATE_LOG}" && ! -s "${GH_LOG}" ]] || \
  die "publisher reached the gate after scanner TOCTOU"

# The independent source verifier is mandatory and must pass before the private gate.
if AUTOFORM_SELFTEST_SOURCE_PROVENANCE_FAIL=true \
  run_publish >"${FIXTURE_ROOT}/source-provenance-failure.log" 2>&1; then
  die "publisher accepted a failed APK source-provenance verification"
fi
[[ ! -s "${GATE_LOG}" && ! -s "${GH_LOG}" ]] || \
  die "publisher reached the gate after source-provenance verification failed"

# An internally valid report for another APK and different counts is still stale.
if AUTOFORM_SELFTEST_STALE_SOURCE_PROVENANCE_REPORT=true \
  run_publish >"${FIXTURE_ROOT}/stale-source-provenance-report.log" 2>&1; then
  die "publisher accepted a source-provenance report for different bytes or counts"
fi
[[ ! -s "${GATE_LOG}" && ! -s "${GH_LOG}" ]] || \
  die "publisher reached the gate after a stale source-provenance report"

# Verifier bytes must be exactly those committed at the candidate source commit.
if AUTOFORM_SELFTEST_SOURCE_VERIFIER_BLOB_MISMATCH=true \
  run_publish >"${FIXTURE_ROOT}/source-verifier-binding.log" 2>&1; then
  die "publisher accepted source-verifier bytes outside the exact source commit"
fi
[[ ! -s "${GATE_LOG}" && ! -s "${GH_LOG}" ]] || \
  die "publisher reached the gate after a source-verifier source mismatch"

# Candidate bytes changing inside a scan must be caught even when its report says pass.
cp "${APK_PATH}" "${FIXTURE_ROOT}/apk.backup"
if AUTOFORM_SELFTEST_MUTATE_APK_DURING_AUDIT=true \
  run_publish >"${FIXTURE_ROOT}/apk-toctou.log" 2>&1; then
  die "publisher accepted an APK changing during its audit"
fi
cp "${FIXTURE_ROOT}/apk.backup" "${APK_PATH}"
[[ ! -s "${GATE_LOG}" && ! -s "${GH_LOG}" ]] || \
  die "publisher reached the gate after APK TOCTOU"

# Public-history inputs are freshly scanned and a valid report for different commit bytes fails.
if AUTOFORM_SELFTEST_STALE_HISTORY_REPORT=true \
  run_publish >"${FIXTURE_ROOT}/stale-history-report.log" 2>&1; then
  die "publisher accepted a public-history audit for different commit bytes"
fi
[[ ! -s "${GATE_LOG}" && ! -s "${GH_LOG}" ]] || \
  die "publisher reached the gate after a public-history report mismatch"

# Branch-protection status contexts are administrator-controlled public text and
# must pass through the exact refs API normalizer into the private-wordlist scan.
: > "${GATE_LOG}"
: > "${GH_LOG}"
if AUTOFORM_SELFTEST_REQUIRED_STATUS_MARKER=true \
  run_publish >"${FIXTURE_ROOT}/required-status-marker.log" 2>&1; then
  die "publisher accepted prohibited text in a required status context"
fi
[[ ! -s "${GATE_LOG}" && ! -s "${GH_LOG}" ]] || \
  die "required-status marker reached the private gate or release creation"

# Third-party actor and uploader identities are also retained in the normalized
# Release scan surface. Test both independently before any side effect.
for actor_location in ACTOR UPLOADER; do
  : > "${GATE_LOG}"
  : > "${GH_LOG}"
  actor_marker_status=0
  if [[ "${actor_location}" == "ACTOR" ]]; then
    actor_label="actor"
    AUTOFORM_SELFTEST_RELEASE_ACTOR_MARKER=true \
      run_publish >"${FIXTURE_ROOT}/release-actor-marker.log" 2>&1 || \
      actor_marker_status=$?
  else
    actor_label="uploader"
    AUTOFORM_SELFTEST_RELEASE_UPLOADER_MARKER=true \
      run_publish >"${FIXTURE_ROOT}/release-uploader-marker.log" 2>&1 || \
      actor_marker_status=$?
  fi
  if [[ ${actor_marker_status} -eq 0 ]]; then
    die "publisher accepted prohibited text in a Release ${actor_label}"
  fi
  [[ ! -s "${GATE_LOG}" && ! -s "${GH_LOG}" ]] || \
    die "Release ${actor_label} marker reached the private gate or release creation"
done

# A full ls-remote capture may contain heads, tags, pull refs, and one literal
# HEAD only. Any future or unexpected namespace must fail closed.
: > "${GATE_LOG}"
: > "${GH_LOG}"
if AUTOFORM_SELFTEST_REMOTE_UNKNOWN_REF=true \
  run_publish >"${FIXTURE_ROOT}/unknown-remote-ref.log" 2>&1; then
  die "publisher accepted an unknown remote ref namespace"
fi
[[ ! -s "${GATE_LOG}" && ! -s "${GH_LOG}" ]] || \
  die "unknown remote ref reached the private gate or release creation"

# Until a reviewed real private gate is pinned in the source-committed policy,
# even a gate that copies every environment hash and writes every boolean true
# must stop before gate execution and before GitHub Release creation.
: > "${GATE_LOG}"
: > "${GH_LOG}"
if run_publish >"${FIXTURE_ROOT}/disabled-trusted-gate-policy.log" 2>&1; then
  die "publisher bypassed the disabled trusted private gate policy"
fi
grep -q 'trusted private release gate policy is disabled' \
  "${FIXTURE_ROOT}/disabled-trusted-gate-policy.log" || \
  die "publisher did not explain the missing reviewed gate identity"
[[ ! -s "${GATE_LOG}" ]] || die "publisher invoked an arbitrary private gate"
[[ ! -s "${GH_LOG}" ]] || die "publisher called release create without a trusted gate"

# The scripts and generated logs must not copy the external policy path or values.
if grep -Fq "${PRIVATE_WORDLIST}" "${FIXTURE_ROOT}"/*.log \
  || grep -Fq 'fictional-private-selftest-marker' "${FIXTURE_ROOT}"/*.log; then
  die "release workflow exposed private wordlist data in logs"
fi

printf 'release-workflow-selftest: PASS (temporary fixtures; no build; expected network edges mocked)\n'
