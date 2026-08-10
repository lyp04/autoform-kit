#!/usr/bin/env bash

set -euo pipefail
set +x
umask 077

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)"
ROOT_DIR="$(cd "${SCRIPT_DIR}/.." && pwd -P)"
PUBLIC_AUDIT_SCANNER="${SCRIPT_DIR}/public-surface-audit.mjs"
GITHUB_RELEASE_AUDIT_NORMALIZER="${SCRIPT_DIR}/normalize-github-releases-for-audit.mjs"
APK_THIRD_PARTY_POLICY="${SCRIPT_DIR}/apk-third-party-components.json"
ANDROID_RUNTIME_LOCK="${SCRIPT_DIR}/android-runtime-dependencies.lock.json"
APK_SOURCE_PROVENANCE_VERIFIER="${SCRIPT_DIR}/verify-apk-third-party-sources.mjs"
PRIVATE_RELEASE_EVIDENCE_VERIFIER="${SCRIPT_DIR}/verify-private-release-evidence.mjs"
PRIVATE_RELEASE_GATE_POLICY="${SCRIPT_DIR}/private-release-gate-policy.json"
cd "${ROOT_DIR}"

ATTESTATION_DIR=""
ATTESTATION_DIR_IDENTITY=""
AUDIT_DIR=""
AUDIT_DIR_IDENTITY=""
RELEASE_TEMP_PARENT=""
RELEASE_TEMP_PARENT_IDENTITY=""
PUBLIC_HISTORY_COMMIT_OBJECT_FILE=""
PUBLIC_HISTORY_COMMIT_OBJECT_INPUT_SHA256=""
PUBLIC_HISTORY_COMMIT_OBJECT_REPORT_SHA256=""
PUBLIC_HISTORY_REMOTE_REFS_FILE=""
PUBLIC_HISTORY_REMOTE_REFS_INPUT_SHA256=""
PUBLIC_HISTORY_REMOTE_REFS_REPORT_SHA256=""
PUBLIC_HISTORY_REF_API_FILE=""
PUBLIC_HISTORY_REF_API_INPUT_SHA256=""
PUBLIC_HISTORY_REF_API_REPORT_SHA256=""
PUBLIC_HISTORY_RELEASES_FILE=""
PUBLIC_HISTORY_RELEASES_INPUT_SHA256=""
PUBLIC_HISTORY_RELEASES_REPORT_SHA256=""
PUBLIC_HISTORY_REMOTE_REFS_IDENTITY_SHA256=""
PUBLIC_HISTORY_PULL_REFS_IDENTITY_SHA256=""
PUBLIC_HISTORY_REMOTE_REFS_RAW_SNAPSHOT_SHA256=""
PUBLIC_HISTORY_REF_API_SNAPSHOT_SHA256=""
PUBLIC_HISTORY_RELEASE_API_SNAPSHOT_SHA256=""
PUBLIC_HISTORY_REPOSITORY_BINDING_SHA256=""
PUBLIC_HISTORY_REPOSITORY_SELECTION_SHA256=""
PUBLIC_HISTORY_METADATA_BINDING_SHA256=""
PRIVATE_EVIDENCE_VERIFIER_SHA256=""
PRIVATE_EVIDENCE_REPORT_SHA256=""
PRIVATE_MIGRATION_REPORT_SHA256=""
PRIVATE_PANEL_CONFIG_SHA256=""
PRIVATE_PANEL_CATALOG_SHA256=""
PRIVATE_PANEL_PAIR_SHA256=""
PRIVATE_DEPLOYMENT_EVIDENCE_SHA256=""
PRIVATE_CATALOG_VERSION=""
PRIVATE_PANEL_WORKER_VERSION_ID=""
PRIVATE_CATALOG_AUTHORITY_TYPE=""
PRIVATE_CATALOG_AUTHORITY_IDENTITY_SHA256=""
PRIVATE_CATALOG_AUTHORITY_REVISION=""
PRIVATE_CATALOG_AUTHORITY_WORKER_BINDING_SHA256=""
PRIVATE_CATALOG_MANIFEST_SHA256=""
PRIVATE_PANEL_SETTINGS_PRESENT=""
PRIVATE_PANEL_SETTINGS_SHA256=""
PRIVATE_GATE_SHA256=""

die() {
  printf 'publish-release: %s\n' "$*" >&2
  exit 1
}

info() {
  printf 'publish-release: %s\n' "$*"
}

cleanup() {
  local status=$?
  local cleanup_failed=false
  trap - EXIT HUP INT TERM
  if ! remove_bound_temporary_directory \
    "${ATTESTATION_DIR:-}" "${ATTESTATION_DIR_IDENTITY:-}" "attestation"; then
    cleanup_failed=true
  fi
  if ! remove_bound_temporary_directory \
    "${AUDIT_DIR:-}" "${AUDIT_DIR_IDENTITY:-}" "public audit"; then
    cleanup_failed=true
  fi
  if [[ "${cleanup_failed}" == true && ${status} -eq 0 ]]; then
    status=1
  fi
  exit "${status}"
}
trap cleanup EXIT HUP INT TERM

usage() {
  cat <<'EOF'
Usage: tools/publish-release.sh --candidate PATH --previous-apk PATH --gate PATH \
  --private-migration-report PATH --panel-config-evidence PATH \
  --panel-catalog-evidence PATH --private-deployment-evidence PATH \
  --private-wordlist PATH [--private-wordlist PATH ...]

Publish an already-built standard upgrade candidate without rebuilding or
rewriting it. Schema 3, schema 4, every historical publicationMode, and the
reserved v1.0.0-v1.0.6 history-rewrite tags are rejected before any gate or
GitHub side effect. Those candidates belong only to the separate reviewed
non-latest history-rewrite workflow.

Required options:
  --candidate PATH       candidate-manifest.json created by tools/release.sh
  --previous-apk PATH    Exact previous APK bound by the candidate manifest
  --gate PATH            Executable private release gate outside the repository,
                         or at a Git-ignored path inside it
  --private-migration-report PATH
                         Real release-ready private migration report (mode 0600)
  --panel-config-evidence PATH
                         Exact App-facing Panel config response (mode 0600)
  --panel-catalog-evidence PATH
                         Exact paired private catalog bytes (mode 0600)
  --private-deployment-evidence PATH
                         Fresh private-repository/read-access proof (mode 0600)
  --private-wordlist PATH External private line/JSON wordlist used by every
                          fresh public-surface audit (required; repeatable)
  -h, --help             Show this help

Relative paths are resolved from the repository root. A fresh attestation path
and exact candidate bindings are passed to the gate through AUTOFORM_RELEASE_*
environment variables. A source-commit-bound verifier checks the private files
before the gate runs. Publishing fails closed unless all evidence and the gate
attestation have the contracts documented in docs/releasing.md.
EOF
}

require_command() {
  command -v "$1" >/dev/null 2>&1 || die "required command not found: $1"
}

absolute_repo_path() {
  local value="$1"
  case "${value}" in
    /*) printf '%s\n' "${value}" ;;
    *) printf '%s\n' "${ROOT_DIR}/${value}" ;;
  esac
}

canonical_regular_file() {
  local label="$1"
  local value="$2"
  local absolute directory basename

  absolute="$(absolute_repo_path "${value}")"
  [[ -f "${absolute}" ]] || die "${label} is not a regular file: ${absolute}"
  [[ ! -L "${absolute}" ]] || die "${label} must not be a symbolic link: ${absolute}"
  directory="$(cd "$(dirname "${absolute}")" && pwd -P)"
  basename="$(basename "${absolute}")"
  printf '%s/%s\n' "${directory}" "${basename}"
}

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

sha256_stdin() {
  if command -v shasum >/dev/null 2>&1; then
    shasum -a 256 | awk '{ print $1 }'
  elif command -v sha256sum >/dev/null 2>&1; then
    sha256sum | awk '{ print $1 }'
  else
    die "shasum or sha256sum is required"
  fi
}

sha256_private_file() {
  local file="$1"
  if command -v shasum >/dev/null 2>&1; then
    shasum -a 256 "${file}" 2>/dev/null | awk '{ print $1 }'
  elif command -v sha256sum >/dev/null 2>&1; then
    sha256sum "${file}" 2>/dev/null | awk '{ print $1 }'
  else
    return 1
  fi
}

private_file_mode() {
  local file="$1"
  if stat -f '%Lp' "${file}" >/dev/null 2>&1; then
    stat -f '%Lp' "${file}"
  else
    stat -c '%a' "${file}"
  fi
}

private_file_identity() {
  local file="$1"
  if stat -f '%d:%i:%Lp:%u:%g:%z:%m:%c' "${file}" >/dev/null 2>&1; then
    stat -f '%d:%i:%Lp:%u:%g:%z:%m:%c' "${file}"
  else
    stat -c '%d:%i:%a:%u:%g:%s:%Y:%Z' "${file}"
  fi
}

private_directory_identity() {
  local directory="$1"
  if stat -f '%d:%i:%Lp:%u:%g' "${directory}" >/dev/null 2>&1; then
    stat -f '%d:%i:%Lp:%u:%g' "${directory}"
  else
    stat -c '%d:%i:%a:%u:%g' "${directory}"
  fi
}

private_file_owner() {
  local file="$1"
  if stat -f '%u' "${file}" >/dev/null 2>&1; then
    stat -f '%u' "${file}"
  else
    stat -c '%u' "${file}"
  fi
}

private_file_link_count() {
  local file="$1"
  if stat -f '%l' "${file}" >/dev/null 2>&1; then
    stat -f '%l' "${file}"
  else
    stat -c '%h' "${file}"
  fi
}

assert_owned_private_regular_file() {
  local file="$1"
  local label="$2"
  [[ -f "${file}" && ! -L "${file}" \
    && "$(private_file_owner "${file}")" == "${EUID}" \
    && "$(private_file_link_count "${file}")" == "1" \
    && "$(private_file_mode "${file}")" == "600" ]] || \
    die "${label} must be an owner-only mode-0600 regular file"
}

assert_owned_private_directory() {
  local directory="$1"
  local expected_mode="$2"
  local label="$3"
  [[ -d "${directory}" && ! -L "${directory}" \
    && "$(private_file_owner "${directory}")" == "${EUID}" \
    && "$(private_file_mode "${directory}")" == "${expected_mode}" ]] || \
    die "${label} must be an owner-only mode-${expected_mode} directory"
}

assert_secure_temporary_parent() {
  local directory="$1"
  local mode owner
  [[ -d "${directory}" && ! -L "${directory}" ]] || \
    die "private gate temporary parent is invalid"
  mode="$(private_file_mode "${directory}" 2>/dev/null)" || \
    die "private gate temporary parent permissions could not be read"
  [[ "${mode}" =~ ^[0-7]{3,4}$ ]] || \
    die "private gate temporary parent permissions are invalid"
  owner="$(private_file_owner "${directory}" 2>/dev/null)" || \
    die "private gate temporary parent owner could not be read"
  if (( (8#${mode} & 8#022) != 0 )) \
    && { (( (8#${mode} & 8#1000) == 0 )) \
      || [[ "${owner}" != "0" && "${owner}" != "${EUID}" ]]; }; then
    die "release temporary parent is not a trusted sticky directory"
  fi
}

initialize_release_temp_parent() {
  local configured_parent="${TMPDIR:-/tmp}"
  [[ -d "${configured_parent}" ]] || die "release temporary parent is not a directory"
  RELEASE_TEMP_PARENT="$(cd "${configured_parent}" && pwd -P)" || \
    die "release temporary parent could not be resolved"
  assert_secure_temporary_parent "${RELEASE_TEMP_PARENT}"
  RELEASE_TEMP_PARENT_IDENTITY="$(private_directory_identity "${RELEASE_TEMP_PARENT}")" || \
    die "release temporary parent identity could not be read"
}

assert_release_temp_parent_stable() {
  [[ -n "${RELEASE_TEMP_PARENT}" && -n "${RELEASE_TEMP_PARENT_IDENTITY}" \
    && -d "${RELEASE_TEMP_PARENT}" && ! -L "${RELEASE_TEMP_PARENT}" \
    && "$(private_directory_identity "${RELEASE_TEMP_PARENT}")" \
      == "${RELEASE_TEMP_PARENT_IDENTITY}" ]] || \
    die "release temporary parent changed after validation"
  assert_secure_temporary_parent "${RELEASE_TEMP_PARENT}"
}

remove_bound_temporary_directory() {
  local directory="$1"
  local expected_identity="$2"
  local label="$3"
  local current_identity=""
  if [[ -z "${directory}" && -z "${expected_identity}" ]]; then
    return 0
  fi
  if [[ -n "${directory}" && -n "${expected_identity}" \
    && -d "${directory}" && ! -L "${directory}" ]]; then
    current_identity="$(private_directory_identity "${directory}" 2>/dev/null || true)"
  fi
  if [[ -z "${current_identity}" || "${current_identity}" != "${expected_identity}" ]]; then
    printf 'publish-release: refusing to remove drifted %s temporary directory\n' \
      "${label}" >&2
    return 1
  fi
  rm -rf "${directory}" || {
    printf 'publish-release: failed to remove bound %s temporary directory\n' \
      "${label}" >&2
    return 1
  }
}

assert_private_gate_permissions() {
  local gate_path="$1"
  local mode
  mode="$(private_file_mode "${gate_path}" 2>/dev/null)" || \
    die "private gate permissions could not be read"
  [[ "${mode}" =~ ^[0-7]{3,4}$ ]] || \
    die "private gate permissions are invalid"
  if (( (8#${mode} & 8#022) != 0 )); then
    die "private gate must not be writable by group or others"
  fi
}

assert_original_private_gate_stable() {
  local gate_path="$1"
  local expected_identity="$2"
  local expected_sha256="$3"
  [[ -f "${gate_path}" && ! -L "${gate_path}" && -x "${gate_path}" ]] || \
    die "private gate path changed after verification"
  assert_private_gate_permissions "${gate_path}"
  [[ "$(private_file_identity "${gate_path}")" == "${expected_identity}" \
    && "$(sha256_private_file "${gate_path}")" == "${expected_sha256}" ]] || \
    die "private gate path changed after verification"
}

canonical_private_wordlist() {
  local value="$1"
  local absolute directory name resolved

  case "${value}" in
    /*) absolute="${value}" ;;
    *) absolute="${ROOT_DIR}/${value}" ;;
  esac
  [[ -f "${absolute}" && ! -L "${absolute}" ]] || \
    die "private wordlist must be a regular non-symlink file"
  directory="$(cd "$(dirname "${absolute}")" 2>/dev/null && pwd -P)" || \
    die "private wordlist parent could not be resolved"
  name="$(basename "${absolute}")"
  resolved="${directory}/${name}"
  case "${resolved}" in
    "${ROOT_DIR}"|"${ROOT_DIR}"/*)
      die "private wordlist must stay outside the repository"
      ;;
  esac
  printf '%s\n' "${resolved}"
}

canonical_private_evidence_file() {
  local label="$1"
  local value="$2"
  local absolute directory name resolved relative

  case "${value}" in
    /*) absolute="${value}" ;;
    *) absolute="${ROOT_DIR}/${value}" ;;
  esac
  [[ -f "${absolute}" && ! -L "${absolute}" ]] || \
    die "${label} must be a regular non-symlink file"
  directory="$(cd "$(dirname "${absolute}")" 2>/dev/null && pwd -P)" || \
    die "${label} parent could not be resolved"
  name="$(basename "${absolute}")"
  resolved="${directory}/${name}"
  case "${resolved}" in
    "${ROOT_DIR}"|"${ROOT_DIR}"/*)
      relative="${resolved#"${ROOT_DIR}"/}"
      if git ls-files --error-unmatch -- "${relative}" >/dev/null 2>&1; then
        die "${label} must not be tracked by Git"
      fi
      git check-ignore --quiet -- "${relative}" || \
        die "${label} inside the repository must be covered by .gitignore"
      ;;
  esac
  printf '%s\n' "${resolved}"
}

assert_scanner_matches_source() {
  local commit="$1"
  assert_source_file_matches_commit \
    "${commit}" \
    "tools/public-surface-audit.mjs" \
    "${PUBLIC_AUDIT_SCANNER}" \
    "public audit scanner"
}

assert_github_release_normalizer_matches_source() {
  local commit="$1"
  assert_source_file_matches_commit \
    "${commit}" \
    "tools/normalize-github-releases-for-audit.mjs" \
    "${GITHUB_RELEASE_AUDIT_NORMALIZER}" \
    "GitHub public metadata normalizer"
}

assert_apk_source_verifier_matches_source() {
  local commit="$1"
  assert_source_file_matches_commit \
    "${commit}" \
    "tools/verify-apk-third-party-sources.mjs" \
    "${APK_SOURCE_PROVENANCE_VERIFIER}" \
    "APK source provenance verifier"
}

assert_private_evidence_verifier_matches_source() {
  local commit="$1"
  assert_source_file_matches_commit \
    "${commit}" \
    "tools/verify-private-release-evidence.mjs" \
    "${PRIVATE_RELEASE_EVIDENCE_VERIFIER}" \
    "private release evidence verifier"
}

assert_private_gate_policy_matches_source() {
  local commit="$1"
  assert_source_file_matches_commit \
    "${commit}" \
    "tools/private-release-gate-policy.json" \
    "${PRIVATE_RELEASE_GATE_POLICY}" \
    "private release gate policy"
}

assert_trusted_private_gate_enabled() {
  local expected_gate_sha
  assert_private_gate_policy_matches_source "${SOURCE_COMMIT}"
  assert_private_gate_permissions "${GATE_PATH}"
  jq -e -s '
    length == 1
    and (.[0] | keys == ["enabled", "gateSha256", "schemaVersion"])
    and .[0].schemaVersion == 1
    and (.[0].enabled | type == "boolean")
    and (.[0].gateSha256 == null
      or (.[0].gateSha256 | type == "string" and test("^[0-9a-f]{64}$")))
  ' "${PRIVATE_RELEASE_GATE_POLICY}" >/dev/null || \
    die "trusted private release gate policy is invalid"
  if [[ "$(jq -r '.enabled' "${PRIVATE_RELEASE_GATE_POLICY}")" != true ]]; then
    die "trusted private release gate policy is disabled; review the real gate, pin its exact SHA-256, and commit the policy before publishing"
  fi
  expected_gate_sha="$(jq -er '.gateSha256' "${PRIVATE_RELEASE_GATE_POLICY}")"
  [[ "$(sha256_private_file "${GATE_PATH}")" == "${expected_gate_sha}" ]] || \
    die "private release gate does not match the source-committed trusted gate SHA-256"
}

assert_source_file_matches_commit() {
  local commit="$1"
  local relative_path="$2"
  local local_path="$3"
  local label="$4"
  local expected_blob actual_blob

  expected_blob="$(git rev-parse --verify \
    "${commit}:${relative_path}" 2>/dev/null)" || \
    die "${label} is not present in the exact source commit"
  actual_blob="$(git hash-object --no-filters -- "${local_path}" 2>/dev/null)" || \
    die "${label} bytes could not be bound to source"
  [[ "${expected_blob}" =~ ^[0-9a-f]{40}([0-9a-f]{24})?$ \
    && "${actual_blob}" == "${expected_blob}" ]] || \
    die "${label} bytes do not match the exact source commit"
}

run_apk_source_provenance_verification() {
  local report="$1"
  local apk="$2"
  local verifier_before verifier_after policy_before policy_after
  local apk_before apk_after report_base report_sha calculated_report_sha status

  assert_apk_source_verifier_matches_source "${SOURCE_COMMIT}"
  verifier_before="$(sha256_file "${APK_SOURCE_PROVENANCE_VERIFIER}")"
  policy_before="$(sha256_file "${APK_THIRD_PARTY_POLICY}")"
  apk_before="$(sha256_file "${apk}")"
  [[ "${verifier_before}" == "${APK_SOURCE_VERIFIER_SHA256}" \
    && "${policy_before}" == "${APK_THIRD_PARTY_POLICY_SHA256}" \
    && "${apk_before}" == "${APK_SHA256}" ]] || \
    die "fresh APK source provenance inputs do not match the candidate binding"

  rm -f "${report}" "${report}.stderr"
  set +x
  set +e
  node "${APK_SOURCE_PROVENANCE_VERIFIER}" \
    --apk "${apk}" \
    --policy "${APK_THIRD_PARTY_POLICY}" \
    --build-dir "${ROOT_DIR}/app/build" > "${report}" 2> "${report}.stderr"
  status=$?
  set -e
  rm -f "${report}.stderr"
  [[ ${status} -eq 0 ]] || die "fresh APK third-party source provenance verification failed"
  [[ -f "${report}" && ! -L "${report}" ]] || \
    die "fresh APK third-party source provenance verification did not create a report"

  verifier_after="$(sha256_file "${APK_SOURCE_PROVENANCE_VERIFIER}")"
  policy_after="$(sha256_file "${APK_THIRD_PARTY_POLICY}")"
  apk_after="$(sha256_file "${apk}")"
  [[ "${verifier_after}" == "${verifier_before}" \
    && "${policy_after}" == "${policy_before}" \
    && "${apk_after}" == "${apk_before}" ]] || \
    die "APK source provenance inputs changed during fresh verification"
  assert_apk_source_verifier_matches_source "${SOURCE_COMMIT}"

  jq -e -s \
    --arg verifier "${APK_SOURCE_VERIFIER_SHA256}" \
    --arg policy "${APK_THIRD_PARTY_POLICY_SHA256}" \
    --arg apk "${APK_SHA256}" \
    --arg profile "${APK_THIRD_PARTY_PROFILE_ID}" \
    --arg reportSha "${APK_SOURCE_REPORT_SHA256}" \
    --argjson sourceArtifactCount "${APK_SOURCE_ARTIFACT_COUNT}" \
    --argjson sourceEntryCount "${APK_SOURCE_ENTRY_COUNT}" \
    --argjson mergedSourceCount "${APK_MERGED_SOURCE_COUNT}" \
    --argjson compiledOutputCount "${APK_COMPILED_OUTPUT_COUNT}" \
    --argjson dexSourceArtifactCount "${APK_DEX_SOURCE_ARTIFACT_COUNT}" \
    --argjson dexSourceEntryCount "${APK_DEX_SOURCE_ENTRY_COUNT}" \
    --argjson declaredDexStringCount "${APK_DECLARED_DEX_STRING_COUNT}" \
    --argjson sourceMatchedDexStringCount "${APK_SOURCE_MATCHED_DEX_STRING_COUNT}" \
    --argjson apkMatchedDexStringCount "${APK_MATCHED_DEX_STRING_COUNT}" \
    'length == 1 and (.[0]
      | type == "object"
      and (keys == ["apkMatchedDexStringCount", "apkSha256", "compiledOutputCount", "declaredDexStringCount", "dexSourceArtifactCount", "dexSourceEntryCount", "mergedSourceCount", "passed", "policySha256", "profileId", "reportSha256", "schemaVersion", "sourceArtifactCount", "sourceEntryCount", "sourceMatchedDexStringCount", "verifierSha256"])
      and .schemaVersion == 1
      and .passed == true
      and .verifierSha256 == $verifier
      and .policySha256 == $policy
      and .apkSha256 == $apk
      and .profileId == $profile
      and .sourceArtifactCount == $sourceArtifactCount
      and .sourceEntryCount == $sourceEntryCount
      and .mergedSourceCount == $mergedSourceCount
      and .compiledOutputCount == $compiledOutputCount
      and .dexSourceArtifactCount == $dexSourceArtifactCount
      and .dexSourceEntryCount == $dexSourceEntryCount
      and .declaredDexStringCount == $declaredDexStringCount
      and .sourceMatchedDexStringCount == $sourceMatchedDexStringCount
      and .apkMatchedDexStringCount == $apkMatchedDexStringCount
      and .reportSha256 == $reportSha)' \
    "${report}" >/dev/null 2>&1 || \
    die "fresh APK source provenance report does not match the candidate binding"
  report_base="$(jq -ceS 'del(.reportSha256)' "${report}")" || \
    die "fresh APK source provenance report could not be canonicalized"
  report_sha="$(jq -er '.reportSha256' "${report}")"
  calculated_report_sha="$(printf '%s' "${report_base}" | sha256_stdin)"
  [[ "${report_sha}" == "${calculated_report_sha}" ]] || \
    die "fresh APK source provenance report hash mismatch"
}

run_public_audit() {
  local report="$1"
  local mode="$2"
  local input="${3:-}"
  local scanner_before scanner_after report_base report_sha calculated_report_sha
  local input_before="" input_after="" tree_before="" tree_after=""
  local status index fingerprint wordlist_count
  local -a audit_args=()
  local -a wordlist_before=()
  local third_party_policy_sha

  assert_scanner_matches_source "${SOURCE_COMMIT}"
  scanner_before="$(sha256_file "${PUBLIC_AUDIT_SCANNER}")"
  third_party_policy_sha="$(sha256_file "${APK_THIRD_PARTY_POLICY}")"
  [[ "${scanner_before}" == "${PUBLIC_AUDIT_SCANNER_SHA256}" ]] || \
    die "public audit scanner bytes do not match the candidate binding"
  wordlist_count="${#PRIVATE_WORDLISTS[@]}"
  for ((index = 0; index < wordlist_count; index++)); do
    fingerprint="$(sha256_private_file "${PRIVATE_WORDLISTS[index]}")" || \
      die "private wordlist became unreadable"
    [[ "${fingerprint}" =~ ^[0-9a-f]{64}$ ]] || die "could not hash private wordlist"
    [[ "${fingerprint}" == "${PRIVATE_WORDLIST_FINGERPRINTS[index]}" ]] || \
      die "private wordlist changed after publication checks began"
    wordlist_before+=("${fingerprint}")
  done

  case "${mode}" in
    git-tree)
      tree_before="$(git rev-parse --verify "${input}^{tree}")"
      [[ "${tree_before}" =~ ^[0-9a-f]{40}([0-9a-f]{24})?$ ]] || \
        die "could not resolve exact source tree for public audit"
      audit_args=(--git-tree "${input}" --repo "${ROOT_DIR}")
      ;;
    worktree)
      audit_args=(--worktree --repo "${ROOT_DIR}")
      ;;
    apk)
      input_before="$(sha256_file "${input}")"
      [[ "${input_before}" =~ ^[0-9a-f]{64}$ ]] || die "could not hash APK before public audit"
      audit_args=(--apk "${input}")
      ;;
    file)
      input_before="$(sha256_file "${input}")"
      [[ "${input_before}" =~ ^[0-9a-f]{64}$ ]] || die "could not hash file before public audit"
      audit_args=(--file "${input}")
      ;;
    *)
      die "unsupported public audit mode"
      ;;
  esac
  for ((index = 0; index < wordlist_count; index++)); do
    audit_args+=(--private-wordlist "${PRIVATE_WORDLISTS[index]}")
  done

  # Never let an inherited xtrace setting expose the external wordlist path.
  set +x
  set +e
  node "${PUBLIC_AUDIT_SCANNER}" "${audit_args[@]}" >"${report}" 2>"${report}.stderr"
  status=$?
  set -e
  rm -f "${report}.stderr"
  [[ ${status} -eq 0 ]] || die "public surface audit failed or found prohibited data"
  [[ -f "${report}" && ! -L "${report}" ]] || die "public surface audit did not create a report"
  assert_owned_private_regular_file "${report}" "public surface audit report"

  scanner_after="$(sha256_file "${PUBLIC_AUDIT_SCANNER}")"
  [[ "${scanner_after}" == "${scanner_before}" ]] || \
    die "public audit scanner changed while an audit was running"
  assert_scanner_matches_source "${SOURCE_COMMIT}"
  for ((index = 0; index < wordlist_count; index++)); do
    fingerprint="$(sha256_private_file "${PRIVATE_WORDLISTS[index]}")" || \
      die "private wordlist became unreadable"
    [[ "${fingerprint}" == "${wordlist_before[index]}" \
      && "${fingerprint}" == "${PRIVATE_WORDLIST_FINGERPRINTS[index]}" ]] || \
      die "private wordlist changed while an audit was running"
  done
  case "${mode}" in
    git-tree)
      tree_after="$(git rev-parse --verify "${input}^{tree}")"
      [[ "${tree_after}" == "${tree_before}" ]] || \
        die "source tree changed while a public audit was running"
      ;;
    apk|file)
      input_after="$(sha256_file "${input}")"
      [[ "${input_after}" == "${input_before}" ]] || \
        die "public audit input changed while its audit was running"
      ;;
  esac

  jq -e -s \
    --arg mode "${mode}" \
    --arg scanner "${scanner_before}" \
    --arg thirdPartyPolicy "${third_party_policy_sha}" \
    --argjson wordlistCount "${wordlist_count}" \
    'length == 1 and (.[0]
      | type == "object"
      and .schemaVersion == 1
      and .scannerSha256 == $scanner
      and .thirdPartyPolicy.manifestSha256 == $thirdPartyPolicy
      and (.thirdPartyPolicy.applicationDexPolicy | type == "string" and length > 0)
      and (.policySha256 | type == "string" and test("^[0-9a-f]{64}$"))
      and (.reportSha256 | type == "string" and test("^[0-9a-f]{64}$"))
      and .input.mode == $mode
      and (.input.sha256 | type == "string" and test("^[0-9a-f]{64}$"))
      and .privatePolicy.applied == true
      and .privatePolicy.wordlistCount == $wordlistCount
      and (.privatePolicy.termCount | type == "number" and floor == . and . > 0)
      and .summary == {passed: true, findingCount: 0}
      and .findings == [])' "${report}" >/dev/null 2>&1 || \
    die "public surface audit report was incomplete or did not pass"

  case "${mode}" in
    git-tree)
      jq -e --arg oid "${tree_before}" \
        '.input.selection == "exact-git-tree" and .input.gitTreeOid == $oid' \
        "${report}" >/dev/null 2>&1 || die "public source-tree audit selected the wrong tree"
      ;;
    worktree)
      jq -e '.input.selection == "tracked-and-untracked-nonignored-present-files"' \
        "${report}" >/dev/null 2>&1 || die "public worktree audit selected the wrong inputs"
      ;;
    apk)
      jq -e --arg sha "${input_before}" \
        '.input.selection == "exact-apk-container-and-zip-entries"
          and .input.sha256 == $sha
          and (.input.zipEntryManifestSha256 | type == "string" and test("^[0-9a-f]{64}$"))
          and .thirdPartyPolicy.applied == true
          and (.thirdPartyPolicy.profileId | type == "string" and length > 0)
          and (.thirdPartyPolicy.matchedEntryCount | type == "number" and floor == . and . > 0)' \
        "${report}" >/dev/null 2>&1 || die "public APK audit selected the wrong bytes"
      ;;
    file)
      jq -e --arg sha "${input_before}" \
        '.input.selection == "exact-regular-file" and .input.sha256 == $sha and .input.entryCount == 1' \
        "${report}" >/dev/null 2>&1 || die "public file audit selected the wrong bytes"
      ;;
  esac

  report_sha="$(jq -er '.reportSha256' "${report}" 2>/dev/null)" || \
    die "public audit report hash is missing"
  report_base="$(jq -cS 'del(.reportSha256)' "${report}" 2>/dev/null)" || \
    die "public audit report could not be canonicalized"
  calculated_report_sha="$(printf '%s' "${report_base}" | sha256_stdin)"
  [[ "${report_sha}" == "${calculated_report_sha}" ]] || \
    die "public audit report hash does not match its contents"
}

android_sdk_root() {
  local sdk="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
  if [[ -z "${sdk}" && -f local.properties ]]; then
    sdk="$(sed -n 's/^sdk\.dir=//p' local.properties | tail -n 1)"
  fi
  if [[ -z "${sdk}" && -n "${HOME:-}" && -d "${HOME}/Library/Android/sdk" ]]; then
    sdk="${HOME}/Library/Android/sdk"
  fi
  [[ -n "${sdk}" && -d "${sdk}" ]] || return 1
  printf '%s\n' "${sdk}"
}

find_android_tool() {
  local name="$1"
  local override="$2"
  local sdk candidate selected=""

  if [[ -n "${override}" ]]; then
    [[ -x "${override}" ]] || die "${name} override is not executable: ${override}"
    printf '%s\n' "${override}"
    return
  fi
  if command -v "${name}" >/dev/null 2>&1; then
    command -v "${name}"
    return
  fi
  sdk="$(android_sdk_root || true)"
  [[ -n "${sdk}" ]] || die "Android SDK not found; set ANDROID_HOME or ANDROID_SDK_ROOT"
  for candidate in "${sdk}"/build-tools/*/"${name}"; do
    [[ -x "${candidate}" ]] || continue
    selected="${candidate}"
  done
  [[ -n "${selected}" ]] || die "${name} not found in Android SDK build-tools"
  printf '%s\n' "${selected}"
}

repo_slug_from_origin() {
  local origin slug
  origin="$(git remote get-url origin 2>/dev/null || true)"
  origin="${origin%.git}"
  case "${origin}" in
    https://github.com/*) slug="${origin#https://github.com/}" ;;
    http://github.com/*) slug="${origin#http://github.com/}" ;;
    git@github.com:*) slug="${origin#git@github.com:}" ;;
    ssh://git@github.com/*) slug="${origin#ssh://git@github.com/}" ;;
    *) die "origin must be a github.com repository" ;;
  esac
  [[ "${slug}" =~ ^[^/]+/[^/]+$ ]] || die "could not determine owner/repo from origin"
  printf '%s\n' "${slug}"
}

release_is_absent() {
  local repo_slug="$1"
  local tag="$2"
  local response status

  set +e
  response="$(gh api "repos/${repo_slug}/releases/tags/${tag}" 2>&1)"
  status=$?
  set -e
  if [[ ${status} -eq 0 ]]; then
    die "GitHub Release already exists: ${tag}"
  fi
  [[ "${response}" == *"HTTP 404"* ]] || \
    die "unable to prove that GitHub Release ${tag} is absent"
}

source_repository_is_public() {
  local repo_slug="$1"
  local current_slug
  local repository_raw="${AUDIT_DIR}/source-repository.raw.json"
  local repository_file="${AUDIT_DIR}/source-repository.json"
  local empty_releases="${AUDIT_DIR}/source-repository.empty-releases.json"
  local normalized="${AUDIT_DIR}/source-repository.normalized.json"
  local status normalizer_before normalizer_after binding selection_sha

  current_slug="$(repo_slug_from_origin)" || return 1
  [[ "${current_slug}" == "${repo_slug}" ]] || return 1

  rm -f "${repository_raw}" "${repository_file}" "${empty_releases}" "${normalized}" \
    "${repository_file}.stderr"
  set +e
  gh api "repos/${repo_slug}" >"${repository_raw}" \
    2>"${repository_file}.stderr"
  status=$?
  set -e
  rm -f "${repository_file}.stderr"
  [[ ${status} -eq 0 && -s "${repository_raw}" && ! -L "${repository_raw}" ]] || \
    return 1
  jq -ceS '
    if type == "object"
      and (.id | type == "number" and floor == . and . > 0)
      and (.node_id | type == "string" and length > 0)
      and (.full_name | type == "string" and length > 0)
      and .visibility == "public"
      and .private == false
      and (.description == null or (.description | type == "string"))
      and (.homepage == null or (.homepage | type == "string"))
      and (.topics | type == "array" and all(.[]; type == "string"))
      and (.default_branch | type == "string" and length > 0)
    then {
      default_branch, description, full_name, homepage, id, node_id, private, topics,
      visibility
    }
    else error("invalid repository selection") end
  ' "${repository_raw}" >"${repository_file}" 2>/dev/null || {
    rm -f "${repository_raw}"
    return 1
  }
  rm -f "${repository_raw}"
  printf '[[]]\n' >"${empty_releases}"
  assert_github_release_normalizer_matches_source "${SOURCE_COMMIT}"
  normalizer_before="$(sha256_file "${GITHUB_RELEASE_AUDIT_NORMALIZER}")"
  set +e
  node "${GITHUB_RELEASE_AUDIT_NORMALIZER}" \
    --repo "${repo_slug}" \
    --repository-input "${repository_file}" \
    --input "${empty_releases}" >"${normalized}" \
    2>"${AUDIT_DIR}/source-repository.normalizer.stderr"
  status=$?
  set -e
  rm -f "${AUDIT_DIR}/source-repository.normalizer.stderr" "${empty_releases}"
  normalizer_after="$(sha256_file "${GITHUB_RELEASE_AUDIT_NORMALIZER}")"
  assert_github_release_normalizer_matches_source "${SOURCE_COMMIT}"
  [[ ${status} -eq 0 && -s "${normalized}" && ! -L "${normalized}" \
    && "${normalizer_before}" == "${normalizer_after}" ]] || return 1
  jq -e -s --arg normalizer "${normalizer_before}" '
    length == 1 and (.[0]
      | type == "object"
      and (keys == ["apiSnapshotSha256", "assetCount", "kind", "normalizerSha256",
        "releaseCount", "releases", "repositoryBindingSha256", "repositoryContent",
        "schemaVersion"])
      and .schemaVersion == 1
      and .kind == "github-releases-sensitive-audit-input"
      and .normalizerSha256 == $normalizer
      and .releaseCount == 0 and .assetCount == 0 and .releases == []
      and (.repositoryBindingSha256 | type == "string" and test("^[a-f0-9]{64}$")))
  ' "${normalized}" >/dev/null 2>&1 || return 1
  binding="$(jq -er '.repositoryBindingSha256' "${normalized}")" || return 1
  selection_sha="$(sha256_file "${repository_file}")"
  if [[ -z "${PUBLIC_HISTORY_REPOSITORY_BINDING_SHA256}" ]]; then
    PUBLIC_HISTORY_REPOSITORY_BINDING_SHA256="${binding}"
    PUBLIC_HISTORY_REPOSITORY_SELECTION_SHA256="${selection_sha}"
  else
    [[ "${binding}" == "${PUBLIC_HISTORY_REPOSITORY_BINDING_SHA256}" \
      && "${selection_sha}" == "${PUBLIC_HISTORY_REPOSITORY_SELECTION_SHA256}" ]] || return 1
  fi
  rm -f "${normalized}"
  return 0
}

assert_public_source_repository() {
  local repo_slug="$1"
  source_repository_is_public "${repo_slug}" || \
    die "source update repository must be confirmed public before publishing"
}

post_publish_die() {
  printf '%s\n' \
    "publish-release: POST-PUBLISH VERIFICATION FAILED: $*" \
    "publish-release: GitHub may already contain a public or draft Release, tag, or uploaded assets." \
    "publish-release: No remote rollback or deletion was attempted; freeze publishing and inspect the repository before retrying." >&2
  exit 1
}

download_and_verify_published_asset() {
  local repo_slug="$1"
  local release_file="$2"
  local asset_name="$3"
  local expected_sha256="$4"
  local index="$5"
  local asset_id download_path status

  asset_id="$(jq -er --arg name "${asset_name}" \
    '.assets | map(select(.name == $name))
      | if length == 1 and (.[0].id | type == "number" and floor == . and . > 0)
        then .[0].id else error("asset identity mismatch") end' \
    "${release_file}" 2>/dev/null)" || \
    post_publish_die "latest Release asset identity did not match the candidate"
  download_path="${AUDIT_DIR}/published-asset-${index}.bin"
  rm -f "${download_path}" "${download_path}.stderr"
  set +e
  gh api -H "Accept: application/octet-stream" \
    "repos/${repo_slug}/releases/assets/${asset_id}" >"${download_path}" \
    2>"${download_path}.stderr"
  status=$?
  set -e
  rm -f "${download_path}.stderr"
  [[ ${status} -eq 0 && -s "${download_path}" && ! -L "${download_path}" ]] || \
    post_publish_die "a published asset could not be downloaded for byte verification"
  [[ "$(sha256_file "${download_path}")" == "${expected_sha256}" ]] || \
    post_publish_die "a downloaded Release asset did not match the candidate SHA-256"
}

verify_published_stable_release() {
  local repo_slug="$1"
  local release_file="${AUDIT_DIR}/published-latest-release.json"
  local status

  source_repository_is_public "${repo_slug}" || \
    post_publish_die "the source update repository is not confirmed public"
  rm -f "${release_file}" "${release_file}.stderr"
  set +e
  gh api "repos/${repo_slug}/releases/latest" >"${release_file}" \
    2>"${release_file}.stderr"
  status=$?
  set -e
  rm -f "${release_file}.stderr"
  [[ ${status} -eq 0 && -s "${release_file}" && ! -L "${release_file}" ]] || \
    post_publish_die "GitHub /releases/latest could not be read after creation"
  jq -e -s \
    --arg tag "${TAG}" \
    --arg title "autoform-kit ${VERSION_NAME}" \
    --arg apk "${APK_FILE}" \
    --rawfile body "${NOTES_PATH}" \
    'length == 1 and (.[0]
      | type == "object"
      and .tag_name == $tag
      and .name == $title
      and .body == $body
      and .draft == false
      and .prerelease == false
      and (.assets | type == "array" and length == 3)
      and ([.assets[].name] | sort == ([$apk, "candidate-manifest.json", "update.json"] | sort))
      and all(.assets[];
        (.id | type == "number" and floor == . and . > 0)
        and .state == "uploaded"
        and (.size | type == "number" and floor == . and . > 0)))' \
    "${release_file}" >/dev/null 2>&1 || \
    post_publish_die "latest tag/title/body/flags or exact asset metadata did not match the stable candidate"

  download_and_verify_published_asset \
    "${repo_slug}" "${release_file}" "${APK_FILE}" "${APK_SHA256}" 0
  download_and_verify_published_asset \
    "${repo_slug}" "${release_file}" "update.json" "${UPDATE_SHA256}" 1
  download_and_verify_published_asset \
    "${repo_slug}" "${release_file}" "candidate-manifest.json" "${MANIFEST_SHA256}" 2
}

assert_publishable_head() {
  local expected_commit="$1"
  local branch head remote_output remote_head remote_count status

  [[ -z "$(git status --porcelain --untracked-files=normal)" ]] || \
    die "publishing requires a clean working tree"
  branch="$(git symbolic-ref --quiet --short HEAD 2>/dev/null || true)"
  [[ "${branch}" == "main" ]] || die "publishing must run from main (current: ${branch:-detached})"
  head="$(git rev-parse HEAD)"
  [[ "${head}" == "${expected_commit}" ]] || \
    die "current HEAD does not match the candidate source commit"

  set +e
  remote_output="$(git ls-remote --heads origin refs/heads/main 2>/dev/null)"
  status=$?
  set -e
  [[ ${status} -eq 0 ]] || die "unable to read origin/main"
  remote_count="$(printf '%s\n' "${remote_output}" | awk '$2 == "refs/heads/main" { count++ } END { print count + 0 }')"
  [[ "${remote_count}" == "1" ]] || die "origin/main did not resolve to exactly one commit"
  remote_head="$(printf '%s\n' "${remote_output}" | awk '$2 == "refs/heads/main" { print $1 }')"
  [[ "${head}" == "${remote_head}" ]] || die "main is not pushed exactly to origin/main"
}

assert_tag_and_release_absent() {
  local repo_slug="$1"
  local tag="$2"
  local remote_refs status

  if git show-ref --verify --quiet "refs/tags/${tag}"; then
    die "local tag already exists: ${tag}"
  fi
  set +e
  remote_refs="$(git ls-remote --tags origin "refs/tags/${tag}" "refs/tags/${tag}^{}" 2>/dev/null)"
  status=$?
  set -e
  [[ ${status} -eq 0 ]] || die "unable to check origin for existing tag ${tag}"
  [[ -z "${remote_refs}" ]] || die "remote tag already exists: ${tag}"
  release_is_absent "${repo_slug}" "${tag}"
}

read_apk_metadata() {
  local apk="$1"
  local line

  line="$("${AAPT_BIN}" dump badging "${apk}" | sed -n '1p')"
  APK_PACKAGE="$(printf '%s\n' "${line}" | sed -n "s/^package: name='\([^']*\)'.*/\1/p")"
  APK_VERSION_CODE="$(printf '%s\n' "${line}" | sed -n "s/.* versionCode='\([^']*\)'.*/\1/p")"
  APK_VERSION_NAME="$(printf '%s\n' "${line}" | sed -n "s/.* versionName='\([^']*\)'.*/\1/p")"
  [[ -n "${APK_PACKAGE}" && "${APK_VERSION_CODE}" =~ ^[1-9][0-9]*$ && -n "${APK_VERSION_NAME}" ]] || \
    die "could not read package/version metadata from ${apk}"
}

signer_sha256() {
  local apk="$1"
  local output digest

  if ! output="$("${APKSIGNER_BIN}" verify --verbose --print-certs "${apk}" 2>&1)"; then
    die "APK signature verification failed: ${apk}"
  fi
  digest="$(printf '%s\n' "${output}" \
    | awk -F': ' '/Signer #1 certificate SHA-256 digest:/ { print tolower($2); exit }')"
  [[ "${digest}" =~ ^[0-9a-f]{64}$ ]] || die "could not read signer SHA-256 from ${apk}"
  printf '%s\n' "${digest}"
}

assert_exact_candidate_directory() {
  local entry name seen=0
  local entries=()

  shopt -s nullglob dotglob
  entries=("${CANDIDATE_DIR}"/*)
  shopt -u nullglob dotglob
  [[ ${#entries[@]} -eq 4 ]] || die "candidate directory must contain exactly four files"
  for entry in "${entries[@]}"; do
    [[ -f "${entry}" && ! -L "${entry}" ]] || die "candidate contains a non-regular entry"
    name="$(basename "${entry}")"
    case "${name}" in
      candidate-manifest.json|"${APK_FILE}"|"${UPDATE_FILE}"|"${NOTES_FILE}") seen=$((seen + 1)) ;;
      *) die "candidate contains an unexpected file: ${name}" ;;
    esac
  done
  [[ ${seen} -eq 4 ]] || die "candidate file names are not unique"
}

assert_candidate_bytes_and_identity() {
  local actual_signer previous_signer notes_text

  assert_exact_candidate_directory
  [[ -f "${PREVIOUS_APK_PATH}" && ! -L "${PREVIOUS_APK_PATH}" ]] || \
    die "previous APK is no longer a regular non-symlink file"
  [[ "$(sha256_file "${MANIFEST_PATH}")" == "${MANIFEST_SHA256}" ]] || \
    die "candidate manifest changed after it was selected"
  [[ "$(sha256_file "${APK_PATH}")" == "${APK_SHA256}" ]] || die "candidate APK hash mismatch"
  [[ "$(sha256_file "${UPDATE_PATH}")" == "${UPDATE_SHA256}" ]] || die "update.json hash mismatch"
  [[ "$(sha256_file "${NOTES_PATH}")" == "${NOTES_SHA256}" ]] || die "release notes hash mismatch"
  [[ "$(sha256_file "${PREVIOUS_APK_PATH}")" == "${PREVIOUS_APK_SHA256}" ]] || \
    die "previous APK hash mismatch"

  read_apk_metadata "${APK_PATH}"
  [[ "${APK_PACKAGE}" == "${PACKAGE_NAME}" ]] || die "candidate APK package mismatch"
  [[ "${APK_VERSION_CODE}" == "${VERSION_CODE}" ]] || die "candidate APK versionCode mismatch"
  [[ "${APK_VERSION_NAME}" == "${VERSION_NAME}" ]] || die "candidate APK versionName mismatch"
  actual_signer="$(signer_sha256 "${APK_PATH}")"
  [[ "${actual_signer}" == "${SIGNER_SHA256}" ]] || die "candidate APK signer mismatch"

  read_apk_metadata "${PREVIOUS_APK_PATH}"
  [[ "${APK_PACKAGE}" == "${PREVIOUS_PACKAGE_NAME}" ]] || die "previous APK package mismatch"
  [[ "${APK_VERSION_CODE}" == "${PREVIOUS_VERSION_CODE}" ]] || die "previous APK versionCode mismatch"
  [[ "${APK_VERSION_NAME}" == "${PREVIOUS_VERSION_NAME}" ]] || die "previous APK versionName mismatch"
  previous_signer="$(signer_sha256 "${PREVIOUS_APK_PATH}")"
  [[ "${previous_signer}" == "${PREVIOUS_SIGNER_SHA256}" ]] || die "previous APK signer mismatch"
  [[ "${previous_signer}" == "${actual_signer}" ]] || die "signer continuity check failed"
  [[ "${PREVIOUS_PACKAGE_NAME}" == "${PACKAGE_NAME}" ]] || die "package continuity check failed"
  (( 10#${VERSION_CODE} > 10#${PREVIOUS_VERSION_CODE} )) || \
    die "candidate versionCode is not greater than the previous APK"

  notes_text="$(<"${NOTES_PATH}")"
  jq -e -s \
    --arg packageName "${PACKAGE_NAME}" \
    --argjson versionCode "${VERSION_CODE}" \
    --arg versionName "${VERSION_NAME}" \
    --arg apkAsset "${APK_FILE}" \
    --arg sha256 "${APK_SHA256}" \
    --arg notes "${notes_text}" \
    'length == 1
      and (.[0] | keys == ["apkAsset", "notes", "packageName", "sha256", "versionCode", "versionName"])
      and .[0].packageName == $packageName
      and .[0].versionCode == $versionCode
      and .[0].versionName == $versionName
      and .[0].apkAsset == $apkAsset
      and .[0].sha256 == $sha256
      and .[0].notes == $notes' \
    "${UPDATE_PATH}" >/dev/null || die "update.json does not describe the exact candidate APK and notes"
}

capture_and_audit_public_history_metadata() {
  local commit_object_file="${AUDIT_DIR}/source-commit-object.scan-input"
  local remote_refs_raw="${AUDIT_DIR}/remote-refs.raw"
  local remote_refs_file="${AUDIT_DIR}/remote-refs.scan-input"
  local branches_raw="${AUDIT_DIR}/github-branches.raw.json"
  local tags_raw="${AUDIT_DIR}/github-tags.raw.json"
  local ref_api_file="${AUDIT_DIR}/github-refs.scan-input.json"
  local releases_raw="${AUDIT_DIR}/github-releases.raw.json"
  local releases_file="${AUDIT_DIR}/github-releases.scan-input.json"
  local commit_report="${AUDIT_DIR}/source-commit-object.audit.json"
  local refs_report="${AUDIT_DIR}/remote-refs.audit.json"
  local ref_api_report="${AUDIT_DIR}/github-refs.audit.json"
  local releases_report="${AUDIT_DIR}/github-releases.audit.json"
  local status commit_input commit_report_sha refs_input refs_report_sha
  local ref_api_input ref_api_report_sha releases_input releases_report_sha
  local normalizer_before normalizer_after refs_identity ref_api_identity
  local pull_refs_identity refs_raw_snapshot ref_api_snapshot releases_snapshot
  local remote_repository_binding ref_api_repository_binding release_repository_binding
  local metadata_base metadata_binding

  if [[ -n "${PUBLIC_HISTORY_COMMIT_OBJECT_INPUT_SHA256}" ]]; then
    assert_owned_private_regular_file "${PUBLIC_HISTORY_COMMIT_OBJECT_FILE}" \
      "captured public source commit object"
    assert_owned_private_regular_file "${PUBLIC_HISTORY_REMOTE_REFS_FILE}" \
      "captured normalized public remote refs"
    assert_owned_private_regular_file "${PUBLIC_HISTORY_REF_API_FILE}" \
      "captured normalized public refs API metadata"
    assert_owned_private_regular_file "${PUBLIC_HISTORY_RELEASES_FILE}" \
      "captured normalized public Release metadata"
    [[ -f "${PUBLIC_HISTORY_COMMIT_OBJECT_FILE}" \
      && ! -L "${PUBLIC_HISTORY_COMMIT_OBJECT_FILE}" \
      && "$(sha256_file "${PUBLIC_HISTORY_COMMIT_OBJECT_FILE}")" \
        == "${PUBLIC_HISTORY_COMMIT_OBJECT_INPUT_SHA256}" \
      && -f "${PUBLIC_HISTORY_REMOTE_REFS_FILE}" \
      && ! -L "${PUBLIC_HISTORY_REMOTE_REFS_FILE}" \
      && "$(sha256_file "${PUBLIC_HISTORY_REMOTE_REFS_FILE}")" \
        == "${PUBLIC_HISTORY_REMOTE_REFS_INPUT_SHA256}" \
      && -f "${PUBLIC_HISTORY_REF_API_FILE}" \
      && ! -L "${PUBLIC_HISTORY_REF_API_FILE}" \
      && "$(sha256_file "${PUBLIC_HISTORY_REF_API_FILE}")" \
        == "${PUBLIC_HISTORY_REF_API_INPUT_SHA256}" \
      && -f "${PUBLIC_HISTORY_RELEASES_FILE}" \
      && ! -L "${PUBLIC_HISTORY_RELEASES_FILE}" \
      && "$(sha256_file "${PUBLIC_HISTORY_RELEASES_FILE}")" \
        == "${PUBLIC_HISTORY_RELEASES_INPUT_SHA256}" ]] || \
      die "private gate changed a public-history snapshot input"
  fi

  source_repository_is_public "${REPO_SLUG}" || \
    die "source repository identity or selected public metadata changed during audit"

  rm -f "${commit_object_file}" "${remote_refs_raw}" "${remote_refs_file}" \
    "${branches_raw}" "${tags_raw}" "${ref_api_file}" \
    "${releases_raw}" "${releases_file}"
  set +e
  git cat-file commit "${SOURCE_COMMIT}" >"${commit_object_file}" \
    2>"${AUDIT_DIR}/source-commit-object.stderr"
  status=$?
  set -e
  rm -f "${AUDIT_DIR}/source-commit-object.stderr"
  [[ ${status} -eq 0 && -s "${commit_object_file}" && ! -L "${commit_object_file}" ]] || \
    die "unable to capture the exact source commit object"

  set +e
  git ls-remote origin >"${remote_refs_raw}" \
    2>"${AUDIT_DIR}/remote-refs.stderr"
  status=$?
  set -e
  rm -f "${AUDIT_DIR}/remote-refs.stderr"
  [[ ${status} -eq 0 && -s "${remote_refs_raw}" && ! -L "${remote_refs_raw}" ]] || \
    die "unable to capture public branch, tag, and pull refs"
  assert_github_release_normalizer_matches_source "${SOURCE_COMMIT}"
  normalizer_before="$(sha256_file "${GITHUB_RELEASE_AUDIT_NORMALIZER}")"
  set +e
  node "${GITHUB_RELEASE_AUDIT_NORMALIZER}" \
    --repo "${REPO_SLUG}" \
    --repository-input "${AUDIT_DIR}/source-repository.json" \
    --remote-refs-input "${remote_refs_raw}" >"${remote_refs_file}" \
    2>"${AUDIT_DIR}/remote-refs.normalizer.stderr"
  status=$?
  set -e
  rm -f "${AUDIT_DIR}/remote-refs.normalizer.stderr"
  [[ ${status} -eq 0 && -s "${remote_refs_file}" && ! -L "${remote_refs_file}" ]] || \
    die "public branch, tag, and pull refs failed strict normalization"
  rm -f "${remote_refs_raw}"

  set +e
  gh api --paginate --slurp \
    "repos/${REPO_SLUG}/branches?per_page=100" >"${branches_raw}" \
    2>"${AUDIT_DIR}/github-branches.stderr"
  status=$?
  set -e
  rm -f "${AUDIT_DIR}/github-branches.stderr"
  [[ ${status} -eq 0 && -s "${branches_raw}" && ! -L "${branches_raw}" ]] || \
    die "unable to capture the public GitHub branch list"

  set +e
  gh api --paginate --slurp \
    "repos/${REPO_SLUG}/tags?per_page=100" >"${tags_raw}" \
    2>"${AUDIT_DIR}/github-tags.stderr"
  status=$?
  set -e
  rm -f "${AUDIT_DIR}/github-tags.stderr"
  [[ ${status} -eq 0 && -s "${tags_raw}" && ! -L "${tags_raw}" ]] || \
    die "unable to capture the public GitHub tag list"

  set +e
  node "${GITHUB_RELEASE_AUDIT_NORMALIZER}" \
    --repo "${REPO_SLUG}" \
    --repository-input "${AUDIT_DIR}/source-repository.json" \
    --branches-input "${branches_raw}" \
    --tags-input "${tags_raw}" >"${ref_api_file}" \
    2>"${AUDIT_DIR}/github-refs.normalizer.stderr"
  status=$?
  set -e
  rm -f "${AUDIT_DIR}/github-refs.normalizer.stderr"
  [[ ${status} -eq 0 && -s "${ref_api_file}" && ! -L "${ref_api_file}" ]] || \
    die "public GitHub branch and tag metadata failed strict normalization"
  rm -f "${branches_raw}" "${tags_raw}"

  set +e
  gh api --paginate --slurp \
    "repos/${REPO_SLUG}/releases?per_page=100" >"${releases_raw}" \
    2>"${AUDIT_DIR}/github-releases.stderr"
  status=$?
  set -e
  rm -f "${AUDIT_DIR}/github-releases.stderr"
  [[ ${status} -eq 0 && -s "${releases_raw}" && ! -L "${releases_raw}" ]] || \
    die "unable to capture the public GitHub Release list"
  set +e
  node "${GITHUB_RELEASE_AUDIT_NORMALIZER}" \
    --repo "${REPO_SLUG}" \
    --repository-input "${AUDIT_DIR}/source-repository.json" \
    --input "${releases_raw}" >"${releases_file}" \
    2>"${AUDIT_DIR}/github-releases.normalizer.stderr"
  status=$?
  set -e
  rm -f "${AUDIT_DIR}/github-releases.normalizer.stderr"
  normalizer_after="$(sha256_file "${GITHUB_RELEASE_AUDIT_NORMALIZER}")"
  assert_github_release_normalizer_matches_source "${SOURCE_COMMIT}"
  [[ ${status} -eq 0 && -s "${releases_file}" && ! -L "${releases_file}" \
    && "${normalizer_before}" == "${normalizer_after}" ]] || \
    die "public GitHub Release metadata failed strict normalization"
  rm -f "${releases_raw}"

  assert_owned_private_regular_file "${commit_object_file}" \
    "captured public source commit object"
  assert_owned_private_regular_file "${remote_refs_file}" \
    "normalized public remote refs"
  assert_owned_private_regular_file "${ref_api_file}" \
    "normalized public refs API metadata"
  assert_owned_private_regular_file "${releases_file}" \
    "normalized public Release metadata"

  jq -e -s --arg normalizer "${normalizer_before}" '
    length == 1 and (.[0]
      | type == "object"
      and (keys == ["branchCount", "branches", "headOid", "kind", "normalizerSha256",
        "pullRefCount", "pullRefIdentitySha256", "pullRefs",
        "rawSnapshotSha256", "refIdentitySha256", "repositoryBindingSha256",
        "schemaVersion", "tagCount", "tags"])
      and .schemaVersion == 1
      and .kind == "github-remote-refs-sensitive-audit-input"
      and .normalizerSha256 == $normalizer
      and (.rawSnapshotSha256 | type == "string" and test("^[a-f0-9]{64}$"))
      and (.refIdentitySha256 | type == "string" and test("^[a-f0-9]{64}$"))
      and (.pullRefIdentitySha256 | type == "string" and test("^[a-f0-9]{64}$"))
      and (.headOid | type == "string" and test("^(?:[a-f0-9]{40}|[a-f0-9]{64})$"))
      and (.repositoryBindingSha256 | type == "string" and test("^[a-f0-9]{64}$"))
      and (.branchCount | type == "number" and floor == . and . > 0)
      and (.tagCount | type == "number" and floor == . and . >= 0)
      and (.pullRefCount | type == "number" and floor == . and . >= 0)
      and (.branches | type == "array")
      and (.branches | length) == .branchCount
      and (.tags | type == "array")
      and (.tags | length) == .tagCount
      and (.pullRefs | type == "array")
      and (.pullRefs | length) == .pullRefCount)
  ' "${remote_refs_file}" >/dev/null 2>&1 || \
    die "normalized public branch, tag, and pull refs have an invalid envelope"
  jq -e -s --arg normalizer "${normalizer_before}" '
    length == 1 and (.[0]
      | type == "object"
      and (keys == ["apiSnapshotSha256", "branchCount", "branches", "kind",
        "normalizerSha256", "refIdentitySha256", "repositoryBindingSha256",
        "repositoryContent", "schemaVersion", "tagCount", "tags"])
      and .schemaVersion == 1
      and .kind == "github-refs-sensitive-audit-input"
      and .normalizerSha256 == $normalizer
      and (.apiSnapshotSha256 | type == "string" and test("^[a-f0-9]{64}$"))
      and (.refIdentitySha256 | type == "string" and test("^[a-f0-9]{64}$"))
      and (.repositoryBindingSha256 | type == "string" and test("^[a-f0-9]{64}$"))
      and (.branchCount | type == "number" and floor == . and . > 0)
      and (.tagCount | type == "number" and floor == . and . >= 0)
      and (.branches | type == "array")
      and (.branches | length) == .branchCount
      and (.tags | type == "array")
      and (.tags | length) == .tagCount
      and (.repositoryContent | type == "object"))
  ' "${ref_api_file}" >/dev/null 2>&1 || \
    die "normalized public GitHub ref metadata has an invalid envelope"
  jq -e -s --arg normalizer "${normalizer_before}" '
    length == 1 and (.[0]
      | type == "object"
      and (keys == ["apiSnapshotSha256", "assetCount", "kind", "normalizerSha256",
        "releaseCount", "releases", "repositoryBindingSha256", "repositoryContent",
        "schemaVersion"])
      and .schemaVersion == 1
      and .kind == "github-releases-sensitive-audit-input"
      and .normalizerSha256 == $normalizer
      and (.apiSnapshotSha256 | type == "string" and test("^[a-f0-9]{64}$"))
      and (.repositoryBindingSha256 | type == "string" and test("^[a-f0-9]{64}$"))
      and (.releaseCount | type == "number" and floor == . and . >= 0)
      and (.assetCount | type == "number" and floor == . and . >= 0)
      and (.releases | type == "array")
      and (.releases | length) == .releaseCount
      and (.repositoryContent | type == "object"))
  ' "${releases_file}" >/dev/null 2>&1 || \
    die "normalized public GitHub Release metadata has an invalid envelope"

  refs_identity="$(jq -er '.refIdentitySha256' "${remote_refs_file}")"
  ref_api_identity="$(jq -er '.refIdentitySha256' "${ref_api_file}")"
  pull_refs_identity="$(jq -er '.pullRefIdentitySha256' "${remote_refs_file}")"
  refs_raw_snapshot="$(jq -er '.rawSnapshotSha256' "${remote_refs_file}")"
  ref_api_snapshot="$(jq -er '.apiSnapshotSha256' "${ref_api_file}")"
  releases_snapshot="$(jq -er '.apiSnapshotSha256' "${releases_file}")"
  remote_repository_binding="$(jq -er '.repositoryBindingSha256' "${remote_refs_file}")"
  ref_api_repository_binding="$(jq -er '.repositoryBindingSha256' "${ref_api_file}")"
  release_repository_binding="$(jq -er '.repositoryBindingSha256' "${releases_file}")"
  [[ "${refs_identity}" == "${ref_api_identity}" ]] || \
    die "GitHub refs API and git transport reported different branch or tag identities"
  [[ "${remote_repository_binding}" == "${PUBLIC_HISTORY_REPOSITORY_BINDING_SHA256}" \
    && "${ref_api_repository_binding}" == "${PUBLIC_HISTORY_REPOSITORY_BINDING_SHA256}" \
    && "${release_repository_binding}" == "${PUBLIC_HISTORY_REPOSITORY_BINDING_SHA256}" ]] || \
    die "normalized public metadata was bound to a different repository identity"
  metadata_base="$(jq -cnS \
    --arg pullRefs "${pull_refs_identity}" \
    --arg refApi "${ref_api_snapshot}" \
    --arg refs "${refs_identity}" \
    --arg refsRaw "${refs_raw_snapshot}" \
    --arg releases "${releases_snapshot}" \
    --arg repository "${release_repository_binding}" \
    '{pullRefIdentitySha256:$pullRefs, refApiSnapshotSha256:$refApi,
      refIdentitySha256:$refs, remoteRefsRawSnapshotSha256:$refsRaw,
      releaseApiSnapshotSha256:$releases,
      repositoryBindingSha256:$repository}')"
  metadata_binding="$(printf 'autoform-kit/github-public-metadata-binding/v2\n%s' \
    "${metadata_base}" | sha256_stdin)"

  run_public_audit "${commit_report}" file "${commit_object_file}"
  run_public_audit "${refs_report}" file "${remote_refs_file}"
  run_public_audit "${ref_api_report}" file "${ref_api_file}"
  run_public_audit "${releases_report}" file "${releases_file}"
  commit_input="$(jq -er '.input.sha256' "${commit_report}")"
  commit_report_sha="$(jq -er '.reportSha256' "${commit_report}")"
  refs_input="$(jq -er '.input.sha256' "${refs_report}")"
  refs_report_sha="$(jq -er '.reportSha256' "${refs_report}")"
  ref_api_input="$(jq -er '.input.sha256' "${ref_api_report}")"
  ref_api_report_sha="$(jq -er '.reportSha256' "${ref_api_report}")"
  releases_input="$(jq -er '.input.sha256' "${releases_report}")"
  releases_report_sha="$(jq -er '.reportSha256' "${releases_report}")"
  assert_owned_private_regular_file "${refs_report}" "public remote refs audit report"
  assert_owned_private_regular_file "${ref_api_report}" "public refs API audit report"
  assert_owned_private_regular_file "${releases_report}" "public Releases audit report"

  if [[ -z "${PUBLIC_HISTORY_COMMIT_OBJECT_INPUT_SHA256}" ]]; then
    PUBLIC_HISTORY_COMMIT_OBJECT_FILE="${commit_object_file}"
    PUBLIC_HISTORY_COMMIT_OBJECT_INPUT_SHA256="${commit_input}"
    PUBLIC_HISTORY_COMMIT_OBJECT_REPORT_SHA256="${commit_report_sha}"
    PUBLIC_HISTORY_REMOTE_REFS_FILE="${remote_refs_file}"
    PUBLIC_HISTORY_REMOTE_REFS_INPUT_SHA256="${refs_input}"
    PUBLIC_HISTORY_REMOTE_REFS_REPORT_SHA256="${refs_report_sha}"
    PUBLIC_HISTORY_REF_API_FILE="${ref_api_file}"
    PUBLIC_HISTORY_REF_API_INPUT_SHA256="${ref_api_input}"
    PUBLIC_HISTORY_REF_API_REPORT_SHA256="${ref_api_report_sha}"
    PUBLIC_HISTORY_RELEASES_FILE="${releases_file}"
    PUBLIC_HISTORY_RELEASES_INPUT_SHA256="${releases_input}"
    PUBLIC_HISTORY_RELEASES_REPORT_SHA256="${releases_report_sha}"
    PUBLIC_HISTORY_REMOTE_REFS_IDENTITY_SHA256="${refs_identity}"
    PUBLIC_HISTORY_PULL_REFS_IDENTITY_SHA256="${pull_refs_identity}"
    PUBLIC_HISTORY_REMOTE_REFS_RAW_SNAPSHOT_SHA256="${refs_raw_snapshot}"
    PUBLIC_HISTORY_REF_API_SNAPSHOT_SHA256="${ref_api_snapshot}"
    PUBLIC_HISTORY_RELEASE_API_SNAPSHOT_SHA256="${releases_snapshot}"
    PUBLIC_HISTORY_METADATA_BINDING_SHA256="${metadata_binding}"
  else
    [[ "${commit_input}" == "${PUBLIC_HISTORY_COMMIT_OBJECT_INPUT_SHA256}" \
      && "${commit_report_sha}" == "${PUBLIC_HISTORY_COMMIT_OBJECT_REPORT_SHA256}" \
      && "${refs_input}" == "${PUBLIC_HISTORY_REMOTE_REFS_INPUT_SHA256}" \
      && "${refs_report_sha}" == "${PUBLIC_HISTORY_REMOTE_REFS_REPORT_SHA256}" \
      && "${ref_api_input}" == "${PUBLIC_HISTORY_REF_API_INPUT_SHA256}" \
      && "${ref_api_report_sha}" == "${PUBLIC_HISTORY_REF_API_REPORT_SHA256}" \
      && "${releases_input}" == "${PUBLIC_HISTORY_RELEASES_INPUT_SHA256}" \
      && "${releases_report_sha}" == "${PUBLIC_HISTORY_RELEASES_REPORT_SHA256}" \
      && "${refs_identity}" == "${PUBLIC_HISTORY_REMOTE_REFS_IDENTITY_SHA256}" \
      && "${pull_refs_identity}" == "${PUBLIC_HISTORY_PULL_REFS_IDENTITY_SHA256}" \
      && "${refs_raw_snapshot}" == "${PUBLIC_HISTORY_REMOTE_REFS_RAW_SNAPSHOT_SHA256}" \
      && "${ref_api_snapshot}" == "${PUBLIC_HISTORY_REF_API_SNAPSHOT_SHA256}" \
      && "${releases_snapshot}" == "${PUBLIC_HISTORY_RELEASE_API_SNAPSHOT_SHA256}" \
      && "${remote_repository_binding}" == "${PUBLIC_HISTORY_REPOSITORY_BINDING_SHA256}" \
      && "${ref_api_repository_binding}" == "${PUBLIC_HISTORY_REPOSITORY_BINDING_SHA256}" \
      && "${release_repository_binding}" == "${PUBLIC_HISTORY_REPOSITORY_BINDING_SHA256}" \
      && "${metadata_binding}" == "${PUBLIC_HISTORY_METADATA_BINDING_SHA256}" ]] || \
      die "public commit/repository/ref/status/Release metadata changed after private attestation"
  fi
}

assert_fresh_public_audits() {
  local tree_report="${AUDIT_DIR}/source-tree.json"
  local worktree_report="${AUDIT_DIR}/worktree.json"
  local apk_report="${AUDIT_DIR}/apk.json"
  local update_report="${AUDIT_DIR}/update.json"
  local notes_report="${AUDIT_DIR}/notes.json"
  local manifest_report="${AUDIT_DIR}/candidate-manifest.json"
  local source_provenance_report="${AUDIT_DIR}/apk-source-provenance.json"

  info "re-auditing exact source, candidate APK provenance, and public release metadata"
  run_public_audit "${tree_report}" git-tree "${SOURCE_COMMIT}"
  run_public_audit "${worktree_report}" worktree
  run_public_audit "${apk_report}" apk "${APK_PATH}"
  run_public_audit "${update_report}" file "${UPDATE_PATH}"
  run_public_audit "${notes_report}" file "${NOTES_PATH}"
  run_public_audit "${manifest_report}" file "${MANIFEST_PATH}"
  run_apk_source_provenance_verification "${source_provenance_report}" "${APK_PATH}"
  capture_and_audit_public_history_metadata
  assert_publishable_head "${SOURCE_COMMIT}"

  [[ "$(jq -er '.scannerSha256' "${tree_report}")" == "${PUBLIC_AUDIT_SCANNER_SHA256}" \
    && "$(jq -er '.scannerSha256' "${worktree_report}")" == "${PUBLIC_AUDIT_SCANNER_SHA256}" \
    && "$(jq -er '.scannerSha256' "${apk_report}")" == "${PUBLIC_AUDIT_SCANNER_SHA256}" \
    && "$(jq -er '.scannerSha256' "${update_report}")" == "${PUBLIC_AUDIT_SCANNER_SHA256}" \
    && "$(jq -er '.scannerSha256' "${notes_report}")" == "${PUBLIC_AUDIT_SCANNER_SHA256}" \
    && "$(jq -er '.scannerSha256' "${manifest_report}")" == "${PUBLIC_AUDIT_SCANNER_SHA256}" \
    && "$(jq -er '.policySha256' "${tree_report}")" == "${PUBLIC_AUDIT_POLICY_SHA256}" \
    && "$(jq -er '.policySha256' "${worktree_report}")" == "${PUBLIC_AUDIT_POLICY_SHA256}" \
    && "$(jq -er '.policySha256' "${apk_report}")" == "${PUBLIC_AUDIT_POLICY_SHA256}" \
    && "$(jq -er '.policySha256' "${update_report}")" == "${PUBLIC_AUDIT_POLICY_SHA256}" \
    && "$(jq -er '.policySha256' "${notes_report}")" == "${PUBLIC_AUDIT_POLICY_SHA256}" \
    && "$(jq -er '.policySha256' "${manifest_report}")" == "${PUBLIC_AUDIT_POLICY_SHA256}" ]] || \
    die "fresh public audits used different scanner or policy bytes"
  [[ "$(jq -er '.input.gitTreeOid' "${tree_report}")" == "${PUBLIC_TREE_OID}" \
    && "$(jq -er '.input.sha256' "${tree_report}")" == "${PUBLIC_TREE_INPUT_SHA256}" \
    && "$(jq -er '.reportSha256' "${tree_report}")" == "${PUBLIC_TREE_REPORT_SHA256}" ]] || \
    die "fresh source-tree audit does not match the candidate binding"
  [[ "$(jq -er '.input.sha256' "${worktree_report}")" == "${PUBLIC_WORKTREE_INPUT_SHA256}" \
    && "$(jq -er '.reportSha256' "${worktree_report}")" == "${PUBLIC_WORKTREE_REPORT_SHA256}" ]] || \
    die "fresh worktree audit does not match the candidate binding"
  [[ "$(jq -er '.input.sha256' "${apk_report}")" == "${PUBLIC_APK_INPUT_SHA256}" \
    && "$(jq -er '.reportSha256' "${apk_report}")" == "${PUBLIC_APK_REPORT_SHA256}" \
    && "$(jq -er '.input.zipEntryManifestSha256' "${apk_report}")" == "${PUBLIC_APK_ZIP_ENTRY_MANIFEST_SHA256}" \
    && "$(jq -er '.thirdPartyPolicy.manifestSha256' "${apk_report}")" == "${APK_THIRD_PARTY_POLICY_SHA256}" \
    && "$(jq -er '.thirdPartyPolicy.profileId' "${apk_report}")" == "${APK_THIRD_PARTY_PROFILE_ID}" \
    && "$(jq -er '.thirdPartyPolicy.matchedEntryCount' "${apk_report}")" == "${APK_THIRD_PARTY_MATCHED_ENTRY_COUNT}" \
    && "$(sha256_file "${APK_THIRD_PARTY_POLICY}")" == "${APK_THIRD_PARTY_POLICY_SHA256}" \
    && "$(sha256_file "${ANDROID_RUNTIME_LOCK}")" == "${ANDROID_RUNTIME_LOCK_SHA256}" ]] || \
    die "fresh APK audit does not match the candidate binding"
  [[ "$(jq -er '.input.sha256' "${update_report}")" == "${PUBLIC_UPDATE_INPUT_SHA256}" \
    && "$(jq -er '.reportSha256' "${update_report}")" == "${PUBLIC_UPDATE_REPORT_SHA256}" \
    && "${PUBLIC_UPDATE_INPUT_SHA256}" == "${UPDATE_SHA256}" ]] || \
    die "fresh update.json audit does not match the candidate binding"
  [[ "$(jq -er '.input.sha256' "${notes_report}")" == "${PUBLIC_NOTES_INPUT_SHA256}" \
    && "$(jq -er '.reportSha256' "${notes_report}")" == "${PUBLIC_NOTES_REPORT_SHA256}" \
    && "${PUBLIC_NOTES_INPUT_SHA256}" == "${NOTES_SHA256}" ]] || \
    die "fresh release-notes.txt audit does not match the candidate binding"
  PUBLIC_MANIFEST_INPUT_SHA256="$(jq -er '.input.sha256' "${manifest_report}")"
  PUBLIC_MANIFEST_REPORT_SHA256="$(jq -er '.reportSha256' "${manifest_report}")"
  [[ "${PUBLIC_MANIFEST_INPUT_SHA256}" == "${MANIFEST_SHA256}" \
    && "${PUBLIC_MANIFEST_REPORT_SHA256}" =~ ^[0-9a-f]{64}$ ]] || \
    die "fresh candidate-manifest.json audit is not bound to its exact bytes"
  [[ "$(sha256_file "${APK_PATH}")" == "${APK_SHA256}" \
    && "$(sha256_file "${UPDATE_PATH}")" == "${UPDATE_SHA256}" \
    && "$(sha256_file "${NOTES_PATH}")" == "${NOTES_SHA256}" \
    && "$(sha256_file "${MANIFEST_PATH}")" == "${MANIFEST_SHA256}" ]] || \
    die "candidate bytes changed after their fresh public audits"
}

assert_private_evidence_unchanged() {
  [[ "$(sha256_private_file "${PRIVATE_MIGRATION_REPORT_PATH}")" \
      == "${PRIVATE_MIGRATION_REPORT_SHA256}" \
    && "$(sha256_private_file "${PRIVATE_PANEL_CONFIG_PATH}")" \
      == "${PRIVATE_PANEL_CONFIG_SHA256}" \
    && "$(sha256_private_file "${PRIVATE_PANEL_CATALOG_PATH}")" \
      == "${PRIVATE_PANEL_CATALOG_SHA256}" \
    && "$(sha256_private_file "${PRIVATE_DEPLOYMENT_EVIDENCE_PATH}")" \
      == "${PRIVATE_DEPLOYMENT_EVIDENCE_SHA256}" \
    && "$(sha256_private_file "${GATE_PATH}")" == "${PRIVATE_GATE_SHA256}" ]] || \
    die "private release evidence or gate changed after verification"
}

verify_private_release_evidence() {
  local report="${AUDIT_DIR}/private-release-evidence-report.json"
  local status migration_before config_before catalog_before deployment_before gate_before

  assert_private_evidence_verifier_matches_source "${SOURCE_COMMIT}"
  PRIVATE_EVIDENCE_VERIFIER_SHA256="$(sha256_file "${PRIVATE_RELEASE_EVIDENCE_VERIFIER}")"
  migration_before="$(sha256_private_file "${PRIVATE_MIGRATION_REPORT_PATH}")" || \
    die "private migration report became unreadable"
  config_before="$(sha256_private_file "${PRIVATE_PANEL_CONFIG_PATH}")" || \
    die "Panel config evidence became unreadable"
  catalog_before="$(sha256_private_file "${PRIVATE_PANEL_CATALOG_PATH}")" || \
    die "Panel catalog evidence became unreadable"
  deployment_before="$(sha256_private_file "${PRIVATE_DEPLOYMENT_EVIDENCE_PATH}")" || \
    die "private deployment evidence became unreadable"
  gate_before="$(sha256_private_file "${GATE_PATH}")" || \
    die "private release gate became unreadable"
  for digest in "${migration_before}" "${config_before}" "${catalog_before}" \
    "${deployment_before}" "${gate_before}" "${PRIVATE_EVIDENCE_VERIFIER_SHA256}"; do
    [[ "${digest}" =~ ^[0-9a-f]{64}$ ]] || die "private evidence SHA-256 could not be calculated"
  done

  rm -f "${report}" "${report}.stderr"
  set +x
  set +e
  node "${PRIVATE_RELEASE_EVIDENCE_VERIFIER}" \
    --migration-report "${PRIVATE_MIGRATION_REPORT_PATH}" \
    --panel-config "${PRIVATE_PANEL_CONFIG_PATH}" \
    --panel-catalog "${PRIVATE_PANEL_CATALOG_PATH}" \
    --deployment-evidence "${PRIVATE_DEPLOYMENT_EVIDENCE_PATH}" \
    --source-commit "${SOURCE_COMMIT}" \
    --candidate-manifest-sha256 "${MANIFEST_SHA256}" \
    --apk-sha256 "${APK_SHA256}" \
    --previous-apk-sha256 "${PREVIOUS_APK_SHA256}" \
    --private-gate-sha256 "${gate_before}" \
    --public-history-remote-refs-input-sha256 \
      "${PUBLIC_HISTORY_REMOTE_REFS_INPUT_SHA256}" \
    --public-history-ref-api-input-sha256 \
      "${PUBLIC_HISTORY_REF_API_INPUT_SHA256}" \
    --public-history-ref-api-snapshot-sha256 \
      "${PUBLIC_HISTORY_REF_API_SNAPSHOT_SHA256}" \
    --public-history-remote-refs-raw-snapshot-sha256 \
      "${PUBLIC_HISTORY_REMOTE_REFS_RAW_SNAPSHOT_SHA256}" \
    --public-history-ref-identity-sha256 \
      "${PUBLIC_HISTORY_REMOTE_REFS_IDENTITY_SHA256}" \
    --public-history-pull-ref-identity-sha256 \
      "${PUBLIC_HISTORY_PULL_REFS_IDENTITY_SHA256}" \
    --public-history-release-input-sha256 \
      "${PUBLIC_HISTORY_RELEASES_INPUT_SHA256}" \
    --public-history-release-api-snapshot-sha256 \
      "${PUBLIC_HISTORY_RELEASE_API_SNAPSHOT_SHA256}" \
    --public-history-repository-binding-sha256 \
      "${PUBLIC_HISTORY_REPOSITORY_BINDING_SHA256}" \
    --public-history-metadata-binding-sha256 \
      "${PUBLIC_HISTORY_METADATA_BINDING_SHA256}" \
    --public-repository "${REPO_SLUG}" > "${report}" 2> "${report}.stderr"
  status=$?
  set -e
  rm -f "${report}.stderr"
  [[ ${status} -eq 0 && -f "${report}" && ! -L "${report}" ]] || \
    die "trusted private evidence verification failed; regenerate the 0600 migration, Panel pair, private-repository, and anonymous-401 evidence"

  PRIVATE_MIGRATION_REPORT_SHA256="${migration_before}"
  PRIVATE_PANEL_CONFIG_SHA256="${config_before}"
  PRIVATE_PANEL_CATALOG_SHA256="${catalog_before}"
  PRIVATE_DEPLOYMENT_EVIDENCE_SHA256="${deployment_before}"
  PRIVATE_GATE_SHA256="${gate_before}"
  PRIVATE_EVIDENCE_REPORT_SHA256="$(sha256_file "${report}")"
  PRIVATE_PANEL_PAIR_SHA256="$(jq -er '.bindings.panelPairSha256' "${report}")"
  PRIVATE_CATALOG_VERSION="$(jq -er '.bindings.catalogVersion | tostring' "${report}")"
  PRIVATE_PANEL_WORKER_VERSION_ID="$(
    jq -er '.bindings.panelWorkerVersionId' "${report}"
  )"
  PRIVATE_CATALOG_AUTHORITY_TYPE="$(
    jq -er '.bindings.catalogAuthorityType' "${report}"
  )"
  PRIVATE_CATALOG_AUTHORITY_IDENTITY_SHA256="$(
    jq -er '.bindings.catalogAuthorityIdentitySha256' "${report}"
  )"
  PRIVATE_CATALOG_AUTHORITY_REVISION="$(
    jq -er '.bindings.catalogAuthorityRevision' "${report}"
  )"
  PRIVATE_CATALOG_AUTHORITY_WORKER_BINDING_SHA256="$(
    jq -er '.bindings.catalogAuthorityWorkerBindingSha256' "${report}"
  )"
  PRIVATE_CATALOG_MANIFEST_SHA256="$(jq -er '.bindings.catalogManifestSha256' "${report}")"
  PRIVATE_PANEL_SETTINGS_PRESENT="$(
    jq -er '.bindings.panelSettingsPresent | tostring' "${report}"
  )"
  PRIVATE_PANEL_SETTINGS_SHA256="$(jq -er '.bindings.panelSettingsSha256' "${report}")"

  jq -e -s \
    --arg verifier "${PRIVATE_EVIDENCE_VERIFIER_SHA256}" \
    --arg sourceCommit "${SOURCE_COMMIT}" \
    --arg manifest "${MANIFEST_SHA256}" \
    --arg apk "${APK_SHA256}" \
    --arg previousApk "${PREVIOUS_APK_SHA256}" \
    --arg gate "${PRIVATE_GATE_SHA256}" \
    --arg migration "${PRIVATE_MIGRATION_REPORT_SHA256}" \
    --arg config "${PRIVATE_PANEL_CONFIG_SHA256}" \
    --arg catalog "${PRIVATE_PANEL_CATALOG_SHA256}" \
    --arg deployment "${PRIVATE_DEPLOYMENT_EVIDENCE_SHA256}" \
    --arg pair "${PRIVATE_PANEL_PAIR_SHA256}" \
    --arg panelWorkerVersion "${PRIVATE_PANEL_WORKER_VERSION_ID}" \
    --arg authorityType "${PRIVATE_CATALOG_AUTHORITY_TYPE}" \
    --arg authorityIdentity "${PRIVATE_CATALOG_AUTHORITY_IDENTITY_SHA256}" \
    --arg authorityRevision "${PRIVATE_CATALOG_AUTHORITY_REVISION}" \
    --arg authorityWorkerBinding "${PRIVATE_CATALOG_AUTHORITY_WORKER_BINDING_SHA256}" \
    --arg catalogManifest "${PRIVATE_CATALOG_MANIFEST_SHA256}" \
    --arg panelSettings "${PRIVATE_PANEL_SETTINGS_SHA256}" \
    --argjson panelSettingsPresent "${PRIVATE_PANEL_SETTINGS_PRESENT}" \
    --argjson catalogVersion "${PRIVATE_CATALOG_VERSION}" \
    'length == 1
      and (.[0] | keys == ["bindings", "checks", "passed", "schemaVersion", "verifierSha256"])
      and .[0].schemaVersion == 2
      and .[0].passed == true
      and .[0].verifierSha256 == $verifier
      and .[0].bindings == {
        sourceCommit: $sourceCommit,
        candidateManifestSha256: $manifest,
        apkSha256: $apk,
        previousApkSha256: $previousApk,
        privateGateSha256: $gate,
        migrationReportSha256: $migration,
        panelConfigSha256: $config,
        panelCatalogSha256: $catalog,
        panelPairSha256: $pair,
        catalogVersion: $catalogVersion,
        deploymentEvidenceSha256: $deployment,
        panelWorkerVersionId: $panelWorkerVersion,
        catalogAuthorityType: $authorityType,
        catalogAuthorityIdentitySha256: $authorityIdentity,
        catalogAuthorityRevision: $authorityRevision,
        catalogAuthorityWorkerBindingSha256: $authorityWorkerBinding,
        catalogManifestSha256: $catalogManifest,
        panelSettingsPresent: $panelSettingsPresent,
        panelSettingsSha256: $panelSettings
      }
      and .[0].checks == {
        privateFilesRegular0600: true,
        privateMigrationReleaseReady: true,
        panelPairExact: true,
        panelRuntimeSourceExact: true,
        catalogAuthorityPrivate: true,
        catalogAuthoritySeparated: true,
        catalogAuthorityPanelBindingExact: true,
        catalogSnapshotBound: true,
        catalogManifestLiveExact: true,
        catalogReadKeyConfigured: true,
        authenticatedPairFetched: true,
        anonymousAccessDenied: true,
        incorrectBearerDenied: true,
        runtimeProvenanceRechecked: true,
        evidenceFresh: true
      }' "${report}" >/dev/null || \
    die "trusted private evidence verifier returned an invalid report"
  assert_private_evidence_unchanged
  info "trusted private release evidence accepted"
}

reverify_private_release_evidence_before_publish() {
  local expected_report_sha256="${PRIVATE_EVIDENCE_REPORT_SHA256}"
  [[ "${expected_report_sha256}" =~ ^[0-9a-f]{64}$ ]] || \
    die "initial private release evidence report binding is invalid"
  verify_private_release_evidence
  [[ "${PRIVATE_EVIDENCE_REPORT_SHA256}" == "${expected_report_sha256}" ]] || \
    die "live private release evidence changed after the private gate"
  info "live private release evidence remained exact after the private gate"
}

assert_private_gate() {
  local gate_path="$1"
  local relative

  case "${gate_path}" in
    "${ROOT_DIR}"/*)
      relative="${gate_path#"${ROOT_DIR}"/}"
      if git ls-files --error-unmatch -- "${relative}" >/dev/null 2>&1; then
        die "private gate must not be tracked by Git: ${relative}"
      fi
      git check-ignore --quiet -- "${relative}" || \
        die "a gate inside the repository must be covered by .gitignore: ${relative}"
      ;;
  esac
}

run_private_gate() {
  local gate_path="$1"
  local attestation gate_basename gate_identity gate_snapshot gate_snapshot_dir
  local gate_snapshot_dir_identity gate_status

  assert_private_gate "${gate_path}"
  assert_private_gate_permissions "${gate_path}"
  gate_identity="$(private_file_identity "${gate_path}")" || \
    die "private gate identity could not be read"
  [[ "$(sha256_private_file "${gate_path}")" == "${PRIVATE_GATE_SHA256}" ]] || \
    die "private gate changed before snapshot creation"
  assert_release_temp_parent_stable
  ATTESTATION_DIR="$(mktemp -d \
    "${RELEASE_TEMP_PARENT}/autoform-release-attestation.XXXXXX")"
  case "${ATTESTATION_DIR}" in
    "${RELEASE_TEMP_PARENT}"/autoform-release-attestation.*) ;;
    *) die "private gate attestation directory escaped the release temporary parent" ;;
  esac
  assert_release_temp_parent_stable
  assert_owned_private_directory "${ATTESTATION_DIR}" 700 \
    "private gate attestation directory"
  ATTESTATION_DIR_IDENTITY="$(private_directory_identity "${ATTESTATION_DIR}")" || \
    die "private gate attestation directory identity could not be read"
  gate_snapshot_dir="${ATTESTATION_DIR}/trusted-gate"
  mkdir "${gate_snapshot_dir}"
  chmod 700 "${gate_snapshot_dir}"
  assert_owned_private_directory "${gate_snapshot_dir}" 700 \
    "private gate snapshot directory"
  gate_basename="$(basename "${gate_path}")"
  gate_snapshot="${gate_snapshot_dir}/${gate_basename}"
  cp "${gate_path}" "${gate_snapshot}"
  chmod 500 "${gate_snapshot}"
  [[ -f "${gate_snapshot}" && ! -L "${gate_snapshot}" \
    && "$(private_file_owner "${gate_snapshot}")" == "${EUID}" \
    && "$(private_file_link_count "${gate_snapshot}")" == "1" \
    && "$(private_file_mode "${gate_snapshot}")" == "500" \
    && "$(sha256_private_file "${gate_snapshot}")" == "${PRIVATE_GATE_SHA256}" ]] || \
    die "private gate snapshot does not match the trusted SHA-256"
  assert_original_private_gate_stable \
    "${gate_path}" "${gate_identity}" "${PRIVATE_GATE_SHA256}"

  # Bind the final gate and attestation lookups to the already-entered private
  # directory inode. Replacing a TMPDIR pathname cannot redirect the relative
  # execution or attestation read to another directory.
  cd "${gate_snapshot_dir}"
  gate_snapshot_dir_identity="$(private_directory_identity .)" || \
    die "private gate snapshot directory identity could not be read"
  assert_owned_private_directory . 700 "private gate snapshot directory"
  gate_snapshot="./${gate_basename}"
  attestation="./attestation.json"
  [[ -f "${gate_snapshot}" && ! -L "${gate_snapshot}" \
    && "$(private_file_owner "${gate_snapshot}")" == "${EUID}" \
    && "$(private_file_link_count "${gate_snapshot}")" == "1" \
    && "$(private_file_mode "${gate_snapshot}")" == "500" \
    && "$(sha256_private_file "${gate_snapshot}")" == "${PRIVATE_GATE_SHA256}" ]] || \
    die "private gate snapshot changed before relative execution"

  info "running required private release gate from a verified private snapshot"
  set +e
  AUTOFORM_RELEASE_REPOSITORY_ROOT="${ROOT_DIR}" \
  AUTOFORM_RELEASE_CANDIDATE_MANIFEST="${MANIFEST_PATH}" \
  AUTOFORM_RELEASE_CANDIDATE_MANIFEST_SHA256="${MANIFEST_SHA256}" \
  AUTOFORM_RELEASE_APK="${APK_PATH}" \
  AUTOFORM_RELEASE_APK_SHA256="${APK_SHA256}" \
  AUTOFORM_RELEASE_UPDATE="${UPDATE_PATH}" \
  AUTOFORM_RELEASE_UPDATE_SHA256="${UPDATE_SHA256}" \
  AUTOFORM_RELEASE_NOTES="${NOTES_PATH}" \
  AUTOFORM_RELEASE_NOTES_SHA256="${NOTES_SHA256}" \
  AUTOFORM_RELEASE_PREVIOUS_APK="${PREVIOUS_APK_PATH}" \
  AUTOFORM_RELEASE_PREVIOUS_APK_SHA256="${PREVIOUS_APK_SHA256}" \
  AUTOFORM_RELEASE_SOURCE_COMMIT="${SOURCE_COMMIT}" \
  AUTOFORM_RELEASE_PUBLIC_AUDIT_SCANNER_SHA256="${PUBLIC_AUDIT_SCANNER_SHA256}" \
  AUTOFORM_RELEASE_PUBLIC_AUDIT_POLICY_SHA256="${PUBLIC_AUDIT_POLICY_SHA256}" \
  AUTOFORM_RELEASE_PUBLIC_TREE_OID="${PUBLIC_TREE_OID}" \
  AUTOFORM_RELEASE_PUBLIC_TREE_INPUT_SHA256="${PUBLIC_TREE_INPUT_SHA256}" \
  AUTOFORM_RELEASE_PUBLIC_TREE_REPORT_SHA256="${PUBLIC_TREE_REPORT_SHA256}" \
  AUTOFORM_RELEASE_PUBLIC_WORKTREE_INPUT_SHA256="${PUBLIC_WORKTREE_INPUT_SHA256}" \
  AUTOFORM_RELEASE_PUBLIC_WORKTREE_REPORT_SHA256="${PUBLIC_WORKTREE_REPORT_SHA256}" \
  AUTOFORM_RELEASE_PUBLIC_APK_INPUT_SHA256="${PUBLIC_APK_INPUT_SHA256}" \
  AUTOFORM_RELEASE_PUBLIC_APK_REPORT_SHA256="${PUBLIC_APK_REPORT_SHA256}" \
  AUTOFORM_RELEASE_PUBLIC_APK_ZIP_ENTRY_MANIFEST_SHA256="${PUBLIC_APK_ZIP_ENTRY_MANIFEST_SHA256}" \
  AUTOFORM_RELEASE_APK_THIRD_PARTY_POLICY_SHA256="${APK_THIRD_PARTY_POLICY_SHA256}" \
  AUTOFORM_RELEASE_APK_THIRD_PARTY_PROFILE_ID="${APK_THIRD_PARTY_PROFILE_ID}" \
  AUTOFORM_RELEASE_APK_THIRD_PARTY_MATCHED_ENTRY_COUNT="${APK_THIRD_PARTY_MATCHED_ENTRY_COUNT}" \
  AUTOFORM_RELEASE_ANDROID_RUNTIME_LOCK_SHA256="${ANDROID_RUNTIME_LOCK_SHA256}" \
  AUTOFORM_RELEASE_APK_SOURCE_VERIFIER_SHA256="${APK_SOURCE_VERIFIER_SHA256}" \
  AUTOFORM_RELEASE_APK_SOURCE_REPORT_SHA256="${APK_SOURCE_REPORT_SHA256}" \
  AUTOFORM_RELEASE_APK_SOURCE_ARTIFACT_COUNT="${APK_SOURCE_ARTIFACT_COUNT}" \
  AUTOFORM_RELEASE_APK_SOURCE_ENTRY_COUNT="${APK_SOURCE_ENTRY_COUNT}" \
  AUTOFORM_RELEASE_APK_MERGED_SOURCE_COUNT="${APK_MERGED_SOURCE_COUNT}" \
  AUTOFORM_RELEASE_APK_COMPILED_OUTPUT_COUNT="${APK_COMPILED_OUTPUT_COUNT}" \
  AUTOFORM_RELEASE_APK_DEX_SOURCE_ARTIFACT_COUNT="${APK_DEX_SOURCE_ARTIFACT_COUNT}" \
  AUTOFORM_RELEASE_APK_DEX_SOURCE_ENTRY_COUNT="${APK_DEX_SOURCE_ENTRY_COUNT}" \
  AUTOFORM_RELEASE_APK_DECLARED_DEX_STRING_COUNT="${APK_DECLARED_DEX_STRING_COUNT}" \
  AUTOFORM_RELEASE_APK_SOURCE_MATCHED_DEX_STRING_COUNT="${APK_SOURCE_MATCHED_DEX_STRING_COUNT}" \
  AUTOFORM_RELEASE_APK_MATCHED_DEX_STRING_COUNT="${APK_MATCHED_DEX_STRING_COUNT}" \
  AUTOFORM_RELEASE_PUBLIC_UPDATE_INPUT_SHA256="${PUBLIC_UPDATE_INPUT_SHA256}" \
  AUTOFORM_RELEASE_PUBLIC_UPDATE_REPORT_SHA256="${PUBLIC_UPDATE_REPORT_SHA256}" \
  AUTOFORM_RELEASE_PUBLIC_NOTES_INPUT_SHA256="${PUBLIC_NOTES_INPUT_SHA256}" \
  AUTOFORM_RELEASE_PUBLIC_NOTES_REPORT_SHA256="${PUBLIC_NOTES_REPORT_SHA256}" \
  AUTOFORM_RELEASE_PUBLIC_MANIFEST_INPUT_SHA256="${PUBLIC_MANIFEST_INPUT_SHA256}" \
  AUTOFORM_RELEASE_PUBLIC_MANIFEST_REPORT_SHA256="${PUBLIC_MANIFEST_REPORT_SHA256}" \
  AUTOFORM_RELEASE_PUBLIC_COMMIT_OBJECT_FILE="${PUBLIC_HISTORY_COMMIT_OBJECT_FILE}" \
  AUTOFORM_RELEASE_PUBLIC_COMMIT_OBJECT_INPUT_SHA256="${PUBLIC_HISTORY_COMMIT_OBJECT_INPUT_SHA256}" \
  AUTOFORM_RELEASE_PUBLIC_COMMIT_OBJECT_REPORT_SHA256="${PUBLIC_HISTORY_COMMIT_OBJECT_REPORT_SHA256}" \
  AUTOFORM_RELEASE_PUBLIC_REMOTE_REFS_FILE="${PUBLIC_HISTORY_REMOTE_REFS_FILE}" \
  AUTOFORM_RELEASE_PUBLIC_REMOTE_REFS_INPUT_SHA256="${PUBLIC_HISTORY_REMOTE_REFS_INPUT_SHA256}" \
  AUTOFORM_RELEASE_PUBLIC_REMOTE_REFS_REPORT_SHA256="${PUBLIC_HISTORY_REMOTE_REFS_REPORT_SHA256}" \
  AUTOFORM_RELEASE_PUBLIC_REF_API_FILE="${PUBLIC_HISTORY_REF_API_FILE}" \
  AUTOFORM_RELEASE_PUBLIC_REF_API_INPUT_SHA256="${PUBLIC_HISTORY_REF_API_INPUT_SHA256}" \
  AUTOFORM_RELEASE_PUBLIC_REF_API_REPORT_SHA256="${PUBLIC_HISTORY_REF_API_REPORT_SHA256}" \
  AUTOFORM_RELEASE_PUBLIC_RELEASES_FILE="${PUBLIC_HISTORY_RELEASES_FILE}" \
  AUTOFORM_RELEASE_PUBLIC_RELEASES_INPUT_SHA256="${PUBLIC_HISTORY_RELEASES_INPUT_SHA256}" \
  AUTOFORM_RELEASE_PUBLIC_RELEASES_REPORT_SHA256="${PUBLIC_HISTORY_RELEASES_REPORT_SHA256}" \
  AUTOFORM_RELEASE_PUBLIC_REF_IDENTITY_SHA256="${PUBLIC_HISTORY_REMOTE_REFS_IDENTITY_SHA256}" \
  AUTOFORM_RELEASE_PUBLIC_PULL_REF_IDENTITY_SHA256="${PUBLIC_HISTORY_PULL_REFS_IDENTITY_SHA256}" \
  AUTOFORM_RELEASE_PUBLIC_REMOTE_REFS_RAW_SNAPSHOT_SHA256="${PUBLIC_HISTORY_REMOTE_REFS_RAW_SNAPSHOT_SHA256}" \
  AUTOFORM_RELEASE_PUBLIC_REF_API_SNAPSHOT_SHA256="${PUBLIC_HISTORY_REF_API_SNAPSHOT_SHA256}" \
  AUTOFORM_RELEASE_PUBLIC_RELEASE_API_SNAPSHOT_SHA256="${PUBLIC_HISTORY_RELEASE_API_SNAPSHOT_SHA256}" \
  AUTOFORM_RELEASE_PUBLIC_REPOSITORY_BINDING_SHA256="${PUBLIC_HISTORY_REPOSITORY_BINDING_SHA256}" \
  AUTOFORM_RELEASE_PUBLIC_METADATA_BINDING_SHA256="${PUBLIC_HISTORY_METADATA_BINDING_SHA256}" \
  AUTOFORM_RELEASE_PRIVATE_EVIDENCE_VERIFIER_SHA256="${PRIVATE_EVIDENCE_VERIFIER_SHA256}" \
  AUTOFORM_RELEASE_PRIVATE_EVIDENCE_REPORT_SHA256="${PRIVATE_EVIDENCE_REPORT_SHA256}" \
  AUTOFORM_RELEASE_PRIVATE_MIGRATION_REPORT_SHA256="${PRIVATE_MIGRATION_REPORT_SHA256}" \
  AUTOFORM_RELEASE_PRIVATE_PANEL_CONFIG_SHA256="${PRIVATE_PANEL_CONFIG_SHA256}" \
  AUTOFORM_RELEASE_PRIVATE_PANEL_CATALOG_SHA256="${PRIVATE_PANEL_CATALOG_SHA256}" \
  AUTOFORM_RELEASE_PRIVATE_PANEL_PAIR_SHA256="${PRIVATE_PANEL_PAIR_SHA256}" \
  AUTOFORM_RELEASE_PRIVATE_DEPLOYMENT_EVIDENCE_SHA256="${PRIVATE_DEPLOYMENT_EVIDENCE_SHA256}" \
  AUTOFORM_RELEASE_PRIVATE_CATALOG_VERSION="${PRIVATE_CATALOG_VERSION}" \
  AUTOFORM_RELEASE_PRIVATE_PANEL_WORKER_VERSION_ID="${PRIVATE_PANEL_WORKER_VERSION_ID}" \
  AUTOFORM_RELEASE_PRIVATE_CATALOG_AUTHORITY_TYPE="${PRIVATE_CATALOG_AUTHORITY_TYPE}" \
  AUTOFORM_RELEASE_PRIVATE_CATALOG_AUTHORITY_IDENTITY_SHA256="${PRIVATE_CATALOG_AUTHORITY_IDENTITY_SHA256}" \
  AUTOFORM_RELEASE_PRIVATE_CATALOG_AUTHORITY_REVISION="${PRIVATE_CATALOG_AUTHORITY_REVISION}" \
  AUTOFORM_RELEASE_PRIVATE_CATALOG_AUTHORITY_WORKER_BINDING_SHA256="${PRIVATE_CATALOG_AUTHORITY_WORKER_BINDING_SHA256}" \
  AUTOFORM_RELEASE_PRIVATE_CATALOG_MANIFEST_SHA256="${PRIVATE_CATALOG_MANIFEST_SHA256}" \
  AUTOFORM_RELEASE_PRIVATE_PANEL_SETTINGS_PRESENT="${PRIVATE_PANEL_SETTINGS_PRESENT}" \
  AUTOFORM_RELEASE_PRIVATE_PANEL_SETTINGS_SHA256="${PRIVATE_PANEL_SETTINGS_SHA256}" \
  AUTOFORM_RELEASE_PRIVATE_GATE_SHA256="${PRIVATE_GATE_SHA256}" \
  AUTOFORM_RELEASE_ATTESTATION_OUT="${attestation}" \
    "${gate_snapshot}"
  gate_status=$?
  set -e

  [[ -f "${gate_snapshot}" && ! -L "${gate_snapshot}" \
    && "$(private_file_owner "${gate_snapshot}")" == "${EUID}" \
    && "$(private_file_link_count "${gate_snapshot}")" == "1" \
    && "$(private_file_mode "${gate_snapshot}")" == "500" \
    && "$(sha256_private_file "${gate_snapshot}")" == "${PRIVATE_GATE_SHA256}" ]] || \
    die "private gate snapshot changed while it was running"
  [[ "$(private_directory_identity .)" == "${gate_snapshot_dir_identity}" ]] || \
    die "private gate snapshot directory changed while it was running"
  assert_owned_private_directory . 700 "private gate snapshot directory"
  assert_original_private_gate_stable \
    "${gate_path}" "${gate_identity}" "${PRIVATE_GATE_SHA256}"
  [[ ${gate_status} -eq 0 ]] || die "private release gate failed"

  [[ -f "${attestation}" && ! -L "${attestation}" ]] || \
    die "private gate did not create a regular attestation file"
  jq -e -s \
    --arg manifest "${MANIFEST_SHA256}" \
    --arg apk "${APK_SHA256}" \
    --arg update "${UPDATE_SHA256}" \
    --arg notes "${NOTES_SHA256}" \
    --arg previousApk "${PREVIOUS_APK_SHA256}" \
    --arg sourceCommit "${SOURCE_COMMIT}" \
    --arg auditScanner "${PUBLIC_AUDIT_SCANNER_SHA256}" \
    --arg auditPolicy "${PUBLIC_AUDIT_POLICY_SHA256}" \
    --arg treeOid "${PUBLIC_TREE_OID}" \
    --arg treeInput "${PUBLIC_TREE_INPUT_SHA256}" \
    --arg treeReport "${PUBLIC_TREE_REPORT_SHA256}" \
    --arg worktreeInput "${PUBLIC_WORKTREE_INPUT_SHA256}" \
    --arg worktreeReport "${PUBLIC_WORKTREE_REPORT_SHA256}" \
    --arg apkInput "${PUBLIC_APK_INPUT_SHA256}" \
    --arg apkReport "${PUBLIC_APK_REPORT_SHA256}" \
    --arg apkZipEntryManifest "${PUBLIC_APK_ZIP_ENTRY_MANIFEST_SHA256}" \
    --arg thirdPartyPolicy "${APK_THIRD_PARTY_POLICY_SHA256}" \
    --arg thirdPartyProfile "${APK_THIRD_PARTY_PROFILE_ID}" \
    --argjson thirdPartyEntryCount "${APK_THIRD_PARTY_MATCHED_ENTRY_COUNT}" \
    --arg runtimeLock "${ANDROID_RUNTIME_LOCK_SHA256}" \
    --arg sourceVerifier "${APK_SOURCE_VERIFIER_SHA256}" \
    --arg sourceReport "${APK_SOURCE_REPORT_SHA256}" \
    --argjson sourceArtifactCount "${APK_SOURCE_ARTIFACT_COUNT}" \
    --argjson sourceEntryCount "${APK_SOURCE_ENTRY_COUNT}" \
    --argjson mergedSourceCount "${APK_MERGED_SOURCE_COUNT}" \
    --argjson compiledOutputCount "${APK_COMPILED_OUTPUT_COUNT}" \
    --argjson dexSourceArtifactCount "${APK_DEX_SOURCE_ARTIFACT_COUNT}" \
    --argjson dexSourceEntryCount "${APK_DEX_SOURCE_ENTRY_COUNT}" \
    --argjson declaredDexStringCount "${APK_DECLARED_DEX_STRING_COUNT}" \
    --argjson sourceMatchedDexStringCount "${APK_SOURCE_MATCHED_DEX_STRING_COUNT}" \
    --argjson apkMatchedDexStringCount "${APK_MATCHED_DEX_STRING_COUNT}" \
    --arg updateInput "${PUBLIC_UPDATE_INPUT_SHA256}" \
    --arg updateReport "${PUBLIC_UPDATE_REPORT_SHA256}" \
    --arg notesInput "${PUBLIC_NOTES_INPUT_SHA256}" \
    --arg notesReport "${PUBLIC_NOTES_REPORT_SHA256}" \
    --arg manifestInput "${PUBLIC_MANIFEST_INPUT_SHA256}" \
    --arg manifestReport "${PUBLIC_MANIFEST_REPORT_SHA256}" \
    --arg commitObjectInput "${PUBLIC_HISTORY_COMMIT_OBJECT_INPUT_SHA256}" \
    --arg commitObjectReport "${PUBLIC_HISTORY_COMMIT_OBJECT_REPORT_SHA256}" \
    --arg remoteRefsInput "${PUBLIC_HISTORY_REMOTE_REFS_INPUT_SHA256}" \
    --arg remoteRefsReport "${PUBLIC_HISTORY_REMOTE_REFS_REPORT_SHA256}" \
    --arg refApiInput "${PUBLIC_HISTORY_REF_API_INPUT_SHA256}" \
    --arg refApiReport "${PUBLIC_HISTORY_REF_API_REPORT_SHA256}" \
    --arg releasesInput "${PUBLIC_HISTORY_RELEASES_INPUT_SHA256}" \
    --arg releasesReport "${PUBLIC_HISTORY_RELEASES_REPORT_SHA256}" \
    --arg refIdentity "${PUBLIC_HISTORY_REMOTE_REFS_IDENTITY_SHA256}" \
    --arg pullRefIdentity "${PUBLIC_HISTORY_PULL_REFS_IDENTITY_SHA256}" \
    --arg remoteRefsRawSnapshot "${PUBLIC_HISTORY_REMOTE_REFS_RAW_SNAPSHOT_SHA256}" \
    --arg refApiSnapshot "${PUBLIC_HISTORY_REF_API_SNAPSHOT_SHA256}" \
    --arg releaseApiSnapshot "${PUBLIC_HISTORY_RELEASE_API_SNAPSHOT_SHA256}" \
    --arg repositoryBinding "${PUBLIC_HISTORY_REPOSITORY_BINDING_SHA256}" \
    --arg metadataBinding "${PUBLIC_HISTORY_METADATA_BINDING_SHA256}" \
    --arg privateVerifier "${PRIVATE_EVIDENCE_VERIFIER_SHA256}" \
    --arg privateEvidenceReport "${PRIVATE_EVIDENCE_REPORT_SHA256}" \
    --arg privateMigrationReport "${PRIVATE_MIGRATION_REPORT_SHA256}" \
    --arg privatePanelConfig "${PRIVATE_PANEL_CONFIG_SHA256}" \
    --arg privatePanelCatalog "${PRIVATE_PANEL_CATALOG_SHA256}" \
    --arg privatePanelPair "${PRIVATE_PANEL_PAIR_SHA256}" \
    --arg privateDeploymentEvidence "${PRIVATE_DEPLOYMENT_EVIDENCE_SHA256}" \
    --arg privatePanelWorkerVersion "${PRIVATE_PANEL_WORKER_VERSION_ID}" \
    --arg privateAuthorityType "${PRIVATE_CATALOG_AUTHORITY_TYPE}" \
    --arg privateAuthorityIdentity "${PRIVATE_CATALOG_AUTHORITY_IDENTITY_SHA256}" \
    --arg privateAuthorityRevision "${PRIVATE_CATALOG_AUTHORITY_REVISION}" \
    --arg privateAuthorityWorkerBinding "${PRIVATE_CATALOG_AUTHORITY_WORKER_BINDING_SHA256}" \
    --arg privateCatalogManifest "${PRIVATE_CATALOG_MANIFEST_SHA256}" \
    --arg privatePanelSettings "${PRIVATE_PANEL_SETTINGS_SHA256}" \
    --argjson privatePanelSettingsPresent "${PRIVATE_PANEL_SETTINGS_PRESENT}" \
    --arg privateGate "${PRIVATE_GATE_SHA256}" \
    --argjson privateCatalogVersion "${PRIVATE_CATALOG_VERSION}" \
    'length == 1
      and (.[0] | keys == ["bindings", "checks", "releaseReady", "schemaVersion"])
      and .[0].schemaVersion == 5
      and .[0].releaseReady == true
      and .[0].bindings == {
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
            apkMatchedDexStringCount: $apkMatchedDexStringCount,
            applicationDexStrict: true
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
      }
      and .[0].checks == {
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
      }' "${attestation}" >/dev/null || \
    die "private gate attestation is missing an exact binding or required true check"
  assert_private_evidence_unchanged
  cd "${ROOT_DIR}"
  info "private release gate attestation accepted"
}

CANDIDATE_INPUT=""
PREVIOUS_APK_INPUT=""
GATE_INPUT=""
PRIVATE_MIGRATION_REPORT_INPUT=""
PRIVATE_PANEL_CONFIG_INPUT=""
PRIVATE_PANEL_CATALOG_INPUT=""
PRIVATE_DEPLOYMENT_EVIDENCE_INPUT=""
PRIVATE_WORDLISTS=()
PRIVATE_WORDLIST_FINGERPRINTS=()

while [[ $# -gt 0 ]]; do
  case "$1" in
    --candidate)
      [[ $# -ge 2 ]] || die "--candidate requires a path"
      CANDIDATE_INPUT="$2"
      shift 2
      ;;
    --previous-apk)
      [[ $# -ge 2 ]] || die "--previous-apk requires a path"
      PREVIOUS_APK_INPUT="$2"
      shift 2
      ;;
    --gate)
      [[ $# -ge 2 ]] || die "--gate requires a path"
      GATE_INPUT="$2"
      shift 2
      ;;
    --private-migration-report)
      [[ $# -ge 2 ]] || die "--private-migration-report requires a path"
      PRIVATE_MIGRATION_REPORT_INPUT="$2"
      shift 2
      ;;
    --panel-config-evidence)
      [[ $# -ge 2 ]] || die "--panel-config-evidence requires a path"
      PRIVATE_PANEL_CONFIG_INPUT="$2"
      shift 2
      ;;
    --panel-catalog-evidence)
      [[ $# -ge 2 ]] || die "--panel-catalog-evidence requires a path"
      PRIVATE_PANEL_CATALOG_INPUT="$2"
      shift 2
      ;;
    --private-deployment-evidence)
      [[ $# -ge 2 ]] || die "--private-deployment-evidence requires a path"
      PRIVATE_DEPLOYMENT_EVIDENCE_INPUT="$2"
      shift 2
      ;;
    --private-wordlist)
      [[ $# -ge 2 ]] || die "--private-wordlist requires a path"
      PRIVATE_WORDLISTS+=("$2")
      shift 2
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    *)
      die "unknown option: $1"
      ;;
  esac
done

[[ -n "${CANDIDATE_INPUT}" ]] || die "--candidate is required"
[[ -n "${PREVIOUS_APK_INPUT}" ]] || die "--previous-apk is required"
[[ -n "${GATE_INPUT}" ]] || die "--gate is required"
[[ -n "${PRIVATE_MIGRATION_REPORT_INPUT}" ]] || die "--private-migration-report is required"
[[ -n "${PRIVATE_PANEL_CONFIG_INPUT}" ]] || die "--panel-config-evidence is required"
[[ -n "${PRIVATE_PANEL_CATALOG_INPUT}" ]] || die "--panel-catalog-evidence is required"
[[ -n "${PRIVATE_DEPLOYMENT_EVIDENCE_INPUT}" ]] || \
  die "--private-deployment-evidence is required"
[[ ${#PRIVATE_WORDLISTS[@]} -gt 0 ]] || die "at least one --private-wordlist is required"

require_command git
require_command gh
require_command jq
require_command node
require_command sort
require_command stat
require_command cp
require_command chmod
require_command mktemp
initialize_release_temp_parent
[[ -f "${PUBLIC_AUDIT_SCANNER}" && ! -L "${PUBLIC_AUDIT_SCANNER}" ]] || \
  die "public surface audit scanner is missing or is not a regular file"
[[ -f "${GITHUB_RELEASE_AUDIT_NORMALIZER}" \
  && ! -L "${GITHUB_RELEASE_AUDIT_NORMALIZER}" ]] || \
  die "GitHub public metadata normalizer is missing or is not a regular file"
[[ -f "${APK_THIRD_PARTY_POLICY}" && ! -L "${APK_THIRD_PARTY_POLICY}" ]] || \
  die "APK third-party provenance policy is missing or is not a regular file"
[[ -f "${ANDROID_RUNTIME_LOCK}" && ! -L "${ANDROID_RUNTIME_LOCK}" ]] || \
  die "Android runtime dependency lock is missing or is not a regular file"
[[ -f "${APK_SOURCE_PROVENANCE_VERIFIER}" && ! -L "${APK_SOURCE_PROVENANCE_VERIFIER}" ]] || \
  die "APK source provenance verifier is missing or is not a regular file"
[[ -f "${PRIVATE_RELEASE_EVIDENCE_VERIFIER}" && ! -L "${PRIVATE_RELEASE_EVIDENCE_VERIFIER}" ]] || \
  die "private release evidence verifier is missing or is not a regular file"
[[ -f "${PRIVATE_RELEASE_GATE_POLICY}" && ! -L "${PRIVATE_RELEASE_GATE_POLICY}" ]] || \
  die "private release gate policy is missing or is not a regular file"
for ((PRIVATE_WORDLIST_INDEX = 0; PRIVATE_WORDLIST_INDEX < ${#PRIVATE_WORDLISTS[@]}; PRIVATE_WORDLIST_INDEX++)); do
  PRIVATE_WORDLISTS[PRIVATE_WORDLIST_INDEX]="$(canonical_private_wordlist "${PRIVATE_WORDLISTS[PRIVATE_WORDLIST_INDEX]}")"
  PRIVATE_WORDLIST_FINGERPRINTS[PRIVATE_WORDLIST_INDEX]="$(
    sha256_private_file "${PRIVATE_WORDLISTS[PRIVATE_WORDLIST_INDEX]}"
  )" || die "private wordlist could not be fingerprinted"
  [[ "${PRIVATE_WORDLIST_FINGERPRINTS[PRIVATE_WORDLIST_INDEX]}" =~ ^[0-9a-f]{64}$ ]] || \
    die "private wordlist could not be fingerprinted"
done

MANIFEST_PATH="$(canonical_regular_file "candidate manifest" "${CANDIDATE_INPUT}")"
[[ "$(basename "${MANIFEST_PATH}")" == "candidate-manifest.json" ]] || \
  die "candidate manifest must be named candidate-manifest.json"
CANDIDATE_SCHEMA_VERSION="$(jq -er \
  '.schemaVersion | select(type == "number" and floor == .) | tostring' \
  "${MANIFEST_PATH}" 2>/dev/null || true)"
CANDIDATE_DECLARED_TAG="$(jq -er '.tag | select(type == "string")' \
  "${MANIFEST_PATH}" 2>/dev/null || true)"
CANDIDATE_PUBLICATION_MODE="$(jq -er \
  'if has("publicationMode") then .publicationMode | select(type == "string") else "" end' \
  "${MANIFEST_PATH}" 2>/dev/null || true)"
if [[ "${CANDIDATE_SCHEMA_VERSION}" == "3" || "${CANDIDATE_SCHEMA_VERSION}" == "4" \
  || -n "${CANDIDATE_PUBLICATION_MODE}" ]]; then
  die "historical or explicitly routed candidates cannot be published by the stable command"
fi
case "${CANDIDATE_DECLARED_TAG}" in
  v1.0.0|v1.0.1|v1.0.2|v1.0.3|v1.0.4|v1.0.5|v1.0.6)
    die "reserved historical tags cannot be published by the stable command"
    ;;
esac
PREVIOUS_APK_PATH="$(canonical_regular_file "previous APK" "${PREVIOUS_APK_INPUT}")"
GATE_PATH="$(canonical_regular_file "private gate" "${GATE_INPUT}")"
[[ -x "${GATE_PATH}" ]] || die "private gate is not executable: ${GATE_PATH}"
assert_private_gate_permissions "${GATE_PATH}"
PRIVATE_MIGRATION_REPORT_PATH="$(canonical_private_evidence_file \
  "private migration report" "${PRIVATE_MIGRATION_REPORT_INPUT}")"
PRIVATE_PANEL_CONFIG_PATH="$(canonical_private_evidence_file \
  "Panel config evidence" "${PRIVATE_PANEL_CONFIG_INPUT}")"
PRIVATE_PANEL_CATALOG_PATH="$(canonical_private_evidence_file \
  "Panel catalog evidence" "${PRIVATE_PANEL_CATALOG_INPUT}")"
PRIVATE_DEPLOYMENT_EVIDENCE_PATH="$(canonical_private_evidence_file \
  "private deployment evidence" "${PRIVATE_DEPLOYMENT_EVIDENCE_INPUT}")"

jq -e -s '
  def positive_integer: type == "number" and . > 0 and floor == .;
  length == 1
  and (.[0]
    | type == "object"
    and (keys == ["app", "artifacts", "previousApk", "publicAudit", "schemaVersion", "source", "tag"])
    and .schemaVersion == 2
    and (.tag | type == "string")
    and (.source.commit | type == "string")
    and .source.branch == "main"
    and .source.workingTreeClean == true
    and (.source | keys == ["branch", "commit", "workingTreeClean"])
    and (.app.packageName | type == "string")
    and (.app.versionCode | type == "number" and . > 0 and floor == .)
    and (.app.versionName | type == "string")
    and (.app.signerSha256 | type == "string")
    and (.app | keys == ["packageName", "signerSha256", "versionCode", "versionName"])
    and (.artifacts.apk.file | type == "string")
    and (.artifacts.apk.sha256 | type == "string")
    and (.artifacts.update.file | type == "string")
    and (.artifacts.update.sha256 | type == "string")
    and (.artifacts.notes.file | type == "string")
    and (.artifacts.notes.sha256 | type == "string")
    and (.artifacts | keys == ["apk", "notes", "update"])
    and (.artifacts.apk | keys == ["file", "sha256"])
    and (.artifacts.update | keys == ["file", "sha256"])
    and (.artifacts.notes | keys == ["file", "sha256"])
    and (.previousApk.sha256 | type == "string")
    and (.previousApk.packageName | type == "string")
    and (.previousApk.versionCode | type == "number" and . > 0 and floor == .)
    and (.previousApk.versionName | type == "string" and length > 0)
    and (.previousApk.signerSha256 | type == "string")
    and (.previousApk | keys == ["packageName", "sha256", "signerSha256", "versionCode", "versionName"])
    and (.publicAudit.scannerSha256 | type == "string")
    and (.publicAudit.policySha256 | type == "string")
    and (.publicAudit.sourceTree.gitTreeOid | type == "string")
    and (.publicAudit.sourceTree.inputSha256 | type == "string")
    and (.publicAudit.sourceTree.reportSha256 | type == "string")
    and (.publicAudit.worktree.inputSha256 | type == "string")
    and (.publicAudit.worktree.reportSha256 | type == "string")
    and (.publicAudit.apk.inputSha256 | type == "string")
    and (.publicAudit.apk.reportSha256 | type == "string")
    and (.publicAudit.apk.zipEntryManifestSha256 | type == "string")
    and .publicAudit.thirdPartyProvenance.manifestFile == "tools/apk-third-party-components.json"
    and (.publicAudit.thirdPartyProvenance.manifestSha256 | type == "string")
    and .publicAudit.thirdPartyProvenance.runtimeLockFile == "tools/android-runtime-dependencies.lock.json"
    and (.publicAudit.thirdPartyProvenance.runtimeLockSha256 | type == "string")
    and (.publicAudit.thirdPartyProvenance.profileId | type == "string")
    and (.publicAudit.thirdPartyProvenance.matchedEntryCount | type == "number")
    and (.publicAudit.thirdPartyProvenance.matchedEntryCount > 0)
    and ((.publicAudit.thirdPartyProvenance.matchedEntryCount | floor)
      == .publicAudit.thirdPartyProvenance.matchedEntryCount)
    and .publicAudit.thirdPartyProvenance.sourceVerifierFile == "tools/verify-apk-third-party-sources.mjs"
    and (.publicAudit.thirdPartyProvenance.sourceVerifierSha256 | type == "string")
    and (.publicAudit.thirdPartyProvenance.sourceReportSha256 | type == "string")
    and (.publicAudit.thirdPartyProvenance.sourceArtifactCount | positive_integer)
    and (.publicAudit.thirdPartyProvenance.sourceEntryCount | positive_integer)
    and (.publicAudit.thirdPartyProvenance.sourceEntryCount
      == .publicAudit.thirdPartyProvenance.sourceArtifactCount)
    and .publicAudit.thirdPartyProvenance.mergedSourceCount == 1
    and (.publicAudit.thirdPartyProvenance.compiledOutputCount | positive_integer)
    and (.publicAudit.thirdPartyProvenance.dexSourceArtifactCount | positive_integer)
    and (.publicAudit.thirdPartyProvenance.dexSourceEntryCount | positive_integer)
    and (.publicAudit.thirdPartyProvenance.declaredDexStringCount | positive_integer)
    and (.publicAudit.thirdPartyProvenance.sourceMatchedDexStringCount
      == .publicAudit.thirdPartyProvenance.declaredDexStringCount)
    and (.publicAudit.thirdPartyProvenance.apkMatchedDexStringCount
      == .publicAudit.thirdPartyProvenance.declaredDexStringCount)
    and .publicAudit.thirdPartyProvenance.applicationDexStrict == true
    and (.publicAudit.releaseMetadata.update.inputSha256 | type == "string")
    and (.publicAudit.releaseMetadata.update.reportSha256 | type == "string")
    and (.publicAudit.releaseMetadata.notes.inputSha256 | type == "string")
    and (.publicAudit.releaseMetadata.notes.reportSha256 | type == "string")
    and (.publicAudit | keys == ["apk", "policySha256", "releaseMetadata", "scannerSha256", "sourceTree", "thirdPartyProvenance", "worktree"])
    and (.publicAudit.sourceTree | keys == ["gitTreeOid", "inputSha256", "reportSha256"])
    and (.publicAudit.worktree | keys == ["inputSha256", "reportSha256"])
    and (.publicAudit.apk | keys == ["inputSha256", "reportSha256", "zipEntryManifestSha256"])
    and (.publicAudit.thirdPartyProvenance | keys == ["apkMatchedDexStringCount", "applicationDexStrict", "compiledOutputCount", "declaredDexStringCount", "dexSourceArtifactCount", "dexSourceEntryCount", "manifestFile", "manifestSha256", "matchedEntryCount", "mergedSourceCount", "profileId", "runtimeLockFile", "runtimeLockSha256", "sourceArtifactCount", "sourceEntryCount", "sourceMatchedDexStringCount", "sourceReportSha256", "sourceVerifierFile", "sourceVerifierSha256"])
    and (.publicAudit.releaseMetadata | keys == ["notes", "update"])
    and (.publicAudit.releaseMetadata.update | keys == ["inputSha256", "reportSha256"])
    and (.publicAudit.releaseMetadata.notes | keys == ["inputSha256", "reportSha256"]))
' "${MANIFEST_PATH}" >/dev/null || die "candidate manifest does not match schema version 2"

TAG="$(jq -er '.tag' "${MANIFEST_PATH}")"
SOURCE_COMMIT="$(jq -er '.source.commit' "${MANIFEST_PATH}")"
PACKAGE_NAME="$(jq -er '.app.packageName' "${MANIFEST_PATH}")"
VERSION_CODE="$(jq -er '.app.versionCode | tostring' "${MANIFEST_PATH}")"
VERSION_NAME="$(jq -er '.app.versionName' "${MANIFEST_PATH}")"
SIGNER_SHA256="$(jq -er '.app.signerSha256' "${MANIFEST_PATH}")"
APK_FILE="$(jq -er '.artifacts.apk.file' "${MANIFEST_PATH}")"
APK_SHA256="$(jq -er '.artifacts.apk.sha256' "${MANIFEST_PATH}")"
UPDATE_FILE="$(jq -er '.artifacts.update.file' "${MANIFEST_PATH}")"
UPDATE_SHA256="$(jq -er '.artifacts.update.sha256' "${MANIFEST_PATH}")"
NOTES_FILE="$(jq -er '.artifacts.notes.file' "${MANIFEST_PATH}")"
NOTES_SHA256="$(jq -er '.artifacts.notes.sha256' "${MANIFEST_PATH}")"
PREVIOUS_APK_SHA256="$(jq -er '.previousApk.sha256' "${MANIFEST_PATH}")"
PREVIOUS_PACKAGE_NAME="$(jq -er '.previousApk.packageName' "${MANIFEST_PATH}")"
PREVIOUS_VERSION_CODE="$(jq -er '.previousApk.versionCode | tostring' "${MANIFEST_PATH}")"
PREVIOUS_VERSION_NAME="$(jq -er '.previousApk.versionName' "${MANIFEST_PATH}")"
PREVIOUS_SIGNER_SHA256="$(jq -er '.previousApk.signerSha256' "${MANIFEST_PATH}")"
PUBLIC_AUDIT_SCANNER_SHA256="$(jq -er '.publicAudit.scannerSha256' "${MANIFEST_PATH}")"
PUBLIC_AUDIT_POLICY_SHA256="$(jq -er '.publicAudit.policySha256' "${MANIFEST_PATH}")"
PUBLIC_TREE_OID="$(jq -er '.publicAudit.sourceTree.gitTreeOid' "${MANIFEST_PATH}")"
PUBLIC_TREE_INPUT_SHA256="$(jq -er '.publicAudit.sourceTree.inputSha256' "${MANIFEST_PATH}")"
PUBLIC_TREE_REPORT_SHA256="$(jq -er '.publicAudit.sourceTree.reportSha256' "${MANIFEST_PATH}")"
PUBLIC_WORKTREE_INPUT_SHA256="$(jq -er '.publicAudit.worktree.inputSha256' "${MANIFEST_PATH}")"
PUBLIC_WORKTREE_REPORT_SHA256="$(jq -er '.publicAudit.worktree.reportSha256' "${MANIFEST_PATH}")"
PUBLIC_APK_INPUT_SHA256="$(jq -er '.publicAudit.apk.inputSha256' "${MANIFEST_PATH}")"
PUBLIC_APK_REPORT_SHA256="$(jq -er '.publicAudit.apk.reportSha256' "${MANIFEST_PATH}")"
PUBLIC_APK_ZIP_ENTRY_MANIFEST_SHA256="$(jq -er '.publicAudit.apk.zipEntryManifestSha256' "${MANIFEST_PATH}")"
APK_THIRD_PARTY_POLICY_SHA256="$(jq -er '.publicAudit.thirdPartyProvenance.manifestSha256' "${MANIFEST_PATH}")"
APK_THIRD_PARTY_PROFILE_ID="$(jq -er '.publicAudit.thirdPartyProvenance.profileId' "${MANIFEST_PATH}")"
APK_THIRD_PARTY_MATCHED_ENTRY_COUNT="$(jq -er '.publicAudit.thirdPartyProvenance.matchedEntryCount | tostring' "${MANIFEST_PATH}")"
ANDROID_RUNTIME_LOCK_SHA256="$(jq -er '.publicAudit.thirdPartyProvenance.runtimeLockSha256' "${MANIFEST_PATH}")"
APK_SOURCE_VERIFIER_SHA256="$(jq -er '.publicAudit.thirdPartyProvenance.sourceVerifierSha256' "${MANIFEST_PATH}")"
APK_SOURCE_REPORT_SHA256="$(jq -er '.publicAudit.thirdPartyProvenance.sourceReportSha256' "${MANIFEST_PATH}")"
APK_SOURCE_ARTIFACT_COUNT="$(jq -er '.publicAudit.thirdPartyProvenance.sourceArtifactCount | tostring' "${MANIFEST_PATH}")"
APK_SOURCE_ENTRY_COUNT="$(jq -er '.publicAudit.thirdPartyProvenance.sourceEntryCount | tostring' "${MANIFEST_PATH}")"
APK_MERGED_SOURCE_COUNT="$(jq -er '.publicAudit.thirdPartyProvenance.mergedSourceCount | tostring' "${MANIFEST_PATH}")"
APK_COMPILED_OUTPUT_COUNT="$(jq -er '.publicAudit.thirdPartyProvenance.compiledOutputCount | tostring' "${MANIFEST_PATH}")"
APK_DEX_SOURCE_ARTIFACT_COUNT="$(jq -er '.publicAudit.thirdPartyProvenance.dexSourceArtifactCount | tostring' "${MANIFEST_PATH}")"
APK_DEX_SOURCE_ENTRY_COUNT="$(jq -er '.publicAudit.thirdPartyProvenance.dexSourceEntryCount | tostring' "${MANIFEST_PATH}")"
APK_DECLARED_DEX_STRING_COUNT="$(jq -er '.publicAudit.thirdPartyProvenance.declaredDexStringCount | tostring' "${MANIFEST_PATH}")"
APK_SOURCE_MATCHED_DEX_STRING_COUNT="$(jq -er '.publicAudit.thirdPartyProvenance.sourceMatchedDexStringCount | tostring' "${MANIFEST_PATH}")"
APK_MATCHED_DEX_STRING_COUNT="$(jq -er '.publicAudit.thirdPartyProvenance.apkMatchedDexStringCount | tostring' "${MANIFEST_PATH}")"
PUBLIC_UPDATE_INPUT_SHA256="$(jq -er '.publicAudit.releaseMetadata.update.inputSha256' "${MANIFEST_PATH}")"
PUBLIC_UPDATE_REPORT_SHA256="$(jq -er '.publicAudit.releaseMetadata.update.reportSha256' "${MANIFEST_PATH}")"
PUBLIC_NOTES_INPUT_SHA256="$(jq -er '.publicAudit.releaseMetadata.notes.inputSha256' "${MANIFEST_PATH}")"
PUBLIC_NOTES_REPORT_SHA256="$(jq -er '.publicAudit.releaseMetadata.notes.reportSha256' "${MANIFEST_PATH}")"

[[ "${TAG}" == "v${VERSION_NAME}" ]] || die "candidate tag/versionName mismatch"
[[ "${VERSION_NAME}" =~ ^[0-9]+\.[0-9]+\.[0-9]+([+-][0-9A-Za-z.-]+)?$ ]] || \
  die "invalid candidate versionName"
[[ "${VERSION_NAME}" =~ ^[0-9]+\.[0-9]+\.[0-9]+(\+[0-9A-Za-z.-]+)?$ ]] || \
  die "publish-release only publishes stable candidates; beta/prerelease fixed tags require a separate non-latest workflow"
[[ "${PREVIOUS_VERSION_NAME}" =~ ^[0-9]+\.[0-9]+\.[0-9]+([+-][0-9A-Za-z.-]+)?$ ]] || \
  die "invalid previous APK versionName"
[[ "${SOURCE_COMMIT}" =~ ^[0-9a-f]{40}([0-9a-f]{24})?$ ]] || die "invalid source commit"
[[ "${PACKAGE_NAME}" =~ ^[A-Za-z][A-Za-z0-9_]*(\.[A-Za-z][A-Za-z0-9_]*)+$ ]] || \
  die "invalid package name"
[[ "${SIGNER_SHA256}" =~ ^[0-9a-f]{64}$ ]] || die "invalid candidate signer digest"
[[ "${PREVIOUS_SIGNER_SHA256}" =~ ^[0-9a-f]{64}$ ]] || die "invalid previous signer digest"
for digest in \
  "${APK_SHA256}" \
  "${UPDATE_SHA256}" \
  "${NOTES_SHA256}" \
  "${PREVIOUS_APK_SHA256}" \
  "${PUBLIC_AUDIT_SCANNER_SHA256}" \
  "${PUBLIC_AUDIT_POLICY_SHA256}" \
  "${PUBLIC_TREE_INPUT_SHA256}" \
  "${PUBLIC_TREE_REPORT_SHA256}" \
  "${PUBLIC_WORKTREE_INPUT_SHA256}" \
  "${PUBLIC_WORKTREE_REPORT_SHA256}" \
  "${PUBLIC_APK_INPUT_SHA256}" \
  "${PUBLIC_APK_REPORT_SHA256}" \
  "${PUBLIC_APK_ZIP_ENTRY_MANIFEST_SHA256}" \
  "${APK_THIRD_PARTY_POLICY_SHA256}" \
  "${ANDROID_RUNTIME_LOCK_SHA256}" \
  "${APK_SOURCE_VERIFIER_SHA256}" \
  "${APK_SOURCE_REPORT_SHA256}" \
  "${PUBLIC_UPDATE_INPUT_SHA256}" \
  "${PUBLIC_UPDATE_REPORT_SHA256}" \
  "${PUBLIC_NOTES_INPUT_SHA256}" \
  "${PUBLIC_NOTES_REPORT_SHA256}"; do
  [[ "${digest}" =~ ^[0-9a-f]{64}$ ]] || die "candidate manifest contains an invalid SHA-256"
done
[[ "${PUBLIC_TREE_OID}" =~ ^[0-9a-f]{40}([0-9a-f]{24})?$ ]] || \
  die "candidate manifest contains an invalid source tree OID"
[[ "${PUBLIC_APK_INPUT_SHA256}" == "${APK_SHA256}" ]] || \
  die "candidate public APK audit is not bound to the candidate APK"
[[ "${APK_THIRD_PARTY_PROFILE_ID}" =~ ^[a-z0-9][a-z0-9._-]*$ \
  && "${APK_THIRD_PARTY_MATCHED_ENTRY_COUNT}" =~ ^[1-9][0-9]*$ \
  && "$(sha256_file "${APK_THIRD_PARTY_POLICY}")" == "${APK_THIRD_PARTY_POLICY_SHA256}" \
  && "$(sha256_file "${ANDROID_RUNTIME_LOCK}")" == "${ANDROID_RUNTIME_LOCK_SHA256}" \
  && "$(sha256_file "${APK_SOURCE_PROVENANCE_VERIFIER}")" == "${APK_SOURCE_VERIFIER_SHA256}" \
  && "${APK_SOURCE_ARTIFACT_COUNT}" =~ ^[1-9][0-9]*$ \
  && "${APK_SOURCE_ENTRY_COUNT}" =~ ^[1-9][0-9]*$ \
  && "${APK_SOURCE_ENTRY_COUNT}" == "${APK_SOURCE_ARTIFACT_COUNT}" \
  && "${APK_MERGED_SOURCE_COUNT}" == "1" \
  && "${APK_COMPILED_OUTPUT_COUNT}" =~ ^[1-9][0-9]*$ \
  && "${APK_DEX_SOURCE_ARTIFACT_COUNT}" =~ ^[1-9][0-9]*$ \
  && "${APK_DEX_SOURCE_ENTRY_COUNT}" =~ ^[1-9][0-9]*$ \
  && "${APK_DECLARED_DEX_STRING_COUNT}" =~ ^[1-9][0-9]*$ \
  && "${APK_SOURCE_MATCHED_DEX_STRING_COUNT}" == "${APK_DECLARED_DEX_STRING_COUNT}" \
  && "${APK_MATCHED_DEX_STRING_COUNT}" == "${APK_DECLARED_DEX_STRING_COUNT}" ]] || \
  die "candidate third-party provenance does not match the exact source policy"
[[ "${PUBLIC_UPDATE_INPUT_SHA256}" == "${UPDATE_SHA256}" ]] || \
  die "candidate public update audit is not bound to update.json"
[[ "${PUBLIC_NOTES_INPUT_SHA256}" == "${NOTES_SHA256}" ]] || \
  die "candidate public notes audit is not bound to release-notes.txt"
[[ "${APK_FILE}" == "autoform-kit-${VERSION_NAME}.apk" ]] || die "unexpected candidate APK file name"
[[ "${UPDATE_FILE}" == "update.json" ]] || die "unexpected update manifest file name"
[[ "${NOTES_FILE}" == "release-notes.txt" ]] || die "unexpected release notes file name"

CANDIDATE_DIR="$(dirname "${MANIFEST_PATH}")"
APK_PATH="$(canonical_regular_file "candidate APK" "${CANDIDATE_DIR}/${APK_FILE}")"
UPDATE_PATH="$(canonical_regular_file "update manifest" "${CANDIDATE_DIR}/${UPDATE_FILE}")"
NOTES_PATH="$(canonical_regular_file "release notes" "${CANDIDATE_DIR}/${NOTES_FILE}")"
MANIFEST_SHA256="$(sha256_file "${MANIFEST_PATH}")"
assert_exact_candidate_directory
assert_release_temp_parent_stable
AUDIT_DIR="$(mktemp -d "${RELEASE_TEMP_PARENT}/autoform-public-release-audit.XXXXXX")"
case "${AUDIT_DIR}" in
  "${RELEASE_TEMP_PARENT}"/autoform-public-release-audit.*) ;;
  *) die "public audit directory escaped the release temporary parent" ;;
esac
assert_release_temp_parent_stable
assert_owned_private_directory "${AUDIT_DIR}" 700 "public audit directory"
AUDIT_DIR_IDENTITY="$(private_directory_identity "${AUDIT_DIR}")" || \
  die "public audit directory identity could not be read"

AAPT_BIN="$(find_android_tool aapt "${AAPT:-}")"
APKSIGNER_BIN="$(find_android_tool apksigner "${APKSIGNER:-}")"
REPO_SLUG="$(repo_slug_from_origin)"
gh auth status --hostname github.com >/dev/null 2>&1 || \
  die "publishing requires an authenticated gh session"
assert_public_source_repository "${REPO_SLUG}"

# Check all local bytes and remote preconditions before invoking the private
# gate. No build or candidate rewrite occurs in this script.
assert_candidate_bytes_and_identity
assert_publishable_head "${SOURCE_COMMIT}"
assert_fresh_public_audits
assert_tag_and_release_absent "${REPO_SLUG}" "${TAG}"
assert_trusted_private_gate_enabled
verify_private_release_evidence
run_private_gate "${GATE_PATH}"

# The gate can take time. Re-read all bytes and public metadata once, then
# reverify the live private evidence. A third, metadata-only capture after that
# verifier closes its network window and must still equal the original binding
# before the sole publishing command.
assert_candidate_bytes_and_identity
assert_publishable_head "${SOURCE_COMMIT}"
assert_fresh_public_audits
assert_tag_and_release_absent "${REPO_SLUG}" "${TAG}"
reverify_private_release_evidence_before_publish
assert_candidate_bytes_and_identity
capture_and_audit_public_history_metadata
assert_publishable_head "${SOURCE_COMMIT}"
assert_tag_and_release_absent "${REPO_SLUG}" "${TAG}"

info "publishing exact candidate ${TAG}"
set +e
gh release create "${TAG}" \
  "${APK_PATH}" \
  "${UPDATE_PATH}" \
  "${MANIFEST_PATH}" \
  --repo "${REPO_SLUG}" \
  --target "${SOURCE_COMMIT}" \
  --title "autoform-kit ${VERSION_NAME}" \
  --notes-file "${NOTES_PATH}" \
  --latest
PUBLISH_STATUS=$?
set -e
[[ ${PUBLISH_STATUS} -eq 0 ]] || \
  post_publish_die "GitHub Release creation did not complete successfully"

info "verifying the public stable Release and downloading its exact assets"
verify_published_stable_release "${REPO_SLUG}"
info "published and verified GitHub stable Release ${TAG}"
