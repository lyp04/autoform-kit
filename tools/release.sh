#!/usr/bin/env bash

set -euo pipefail
set +x
umask 077

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)"
ROOT_DIR="$(cd "${SCRIPT_DIR}/.." && pwd -P)"
PUBLIC_AUDIT_SCANNER="${SCRIPT_DIR}/public-surface-audit.mjs"
APK_THIRD_PARTY_POLICY="${SCRIPT_DIR}/apk-third-party-components.json"
ANDROID_RUNTIME_LOCK="${SCRIPT_DIR}/android-runtime-dependencies.lock.json"
APK_SOURCE_PROVENANCE_VERIFIER="${SCRIPT_DIR}/verify-apk-third-party-sources.mjs"
HISTORICAL_RELEASE_CONTRACT="${SCRIPT_DIR}/historical-release-contract.mjs"
cd "${ROOT_DIR}"

die() {
  printf 'release: %s\n' "$*" >&2
  exit 1
}

info() {
  printf 'release: %s\n' "$*"
}

usage() {
  cat <<'EOF'
Usage: tools/release.sh [options]

Build, align, sign, and verify an Android release candidate, then create:
  dist/release-candidates/v<version>/autoform-kit-<version>.apk
  dist/release-candidates/v<version>/update.json
  dist/release-candidates/v<version>/release-notes.txt
  dist/release-candidates/v<version>/candidate-manifest.json

Options:
  --version VERSION       Version name (default: app/build.gradle)
  --version-code CODE     Positive Android version code (default: app/build.gradle)
  --config PATH           Signing JSON (default: config/signing.local.json)
  --notes TEXT            Standard update/release notes (default: "autoform-kit <version>")
  --notes-file PATH       Read standard update/release notes from a UTF-8 file
  --previous-apk PATH     Previous signed release (required; verifies upgrade identity)
  --historical-inventory PATH
                          Exact mode-0600 v1.0.0-v1.0.6 pre-rewrite inventory
  --historical-original-apk PATH
                          Exact mode-0600 original APK selected by that inventory
  --historical-title-file PATH
                          Mode-0600 file containing the exact original Release title
  --historical-previous-candidate PATH
                          Previous schema-4 rebuilt candidate manifest; required for
                          v1.0.1-v1.0.6 and forbidden for v1.0.0
  --private-wordlist PATH External private line/JSON wordlist used by every
                          public-surface audit (required; repeatable)
  --offline               Pass --offline to Gradle; requires a complete local cache
  -h, --help              Show this help

Signing JSON keys:
  keystore, keyAlias, storePassword, keyPassword

Instead of literal passwords, storePasswordEnv and keyPasswordEnv may name
already-exported environment variables. Passwords are never passed as command
arguments or printed.

Relative config, notes, and keystore paths are resolved from the repository
root. A relative keystore path is also tried relative to the config directory.

This command never creates a tag or GitHub Release. After private review, use
tools/publish-release.sh for a standard schema-2 upgrade candidate. Every
historical candidate is schema 4 with publicationMode
"historical-rewrite-non-latest" and is accepted only by the separate reviewed
tools/publish-historical-release.mjs flow. Historical mode always uses the fixed,
source-bound generic body; --notes and --notes-file are rejected in that mode.
The retired --publish option is rejected.
EOF
}

require_command() {
  command -v "$1" >/dev/null 2>&1 || die "required command not found: $1"
}

default_gradle_value() {
  local property="$1"
  sed -n "s/.*project.findProperty(\"${property}\") ?: \"\([^\"]*\)\".*/\1/p" \
    app/build.gradle | head -n 1
}

default_application_id() {
  sed -n 's/^[[:space:]]*applicationId[[:space:]]*"\([^"]*\)".*/\1/p' \
    app/build.gradle | head -n 1
}

absolute_repo_path() {
  local value="$1"
  case "${value}" in
    /*) printf '%s\n' "${value}" ;;
    *) printf '%s\n' "${ROOT_DIR}/${value}" ;;
  esac
}

json_string() {
  local file="$1"
  local key="$2"
  jq -er --arg key "${key}" '.[$key] | select(type == "string")' "${file}" 2>/dev/null || true
}

read_secret() {
  local file="$1"
  local literal_key="$2"
  local env_key="$3"
  local literal env_name value

  literal="$(json_string "${file}" "${literal_key}")"
  env_name="$(json_string "${file}" "${env_key}")"
  if [[ -n "${literal}" && -n "${env_name}" ]]; then
    die "signing config must set only one of ${literal_key} or ${env_key}"
  fi
  if [[ -n "${literal}" ]]; then
    printf '%s' "${literal}"
    return
  fi
  if [[ -z "${env_name}" ]]; then
    die "signing config is missing ${literal_key} (or ${env_key})"
  fi
  [[ "${env_name}" =~ ^[A-Za-z_][A-Za-z0-9_]*$ ]] || \
    die "${env_key} is not a valid environment variable name"
  value="$(printenv "${env_name}" 2>/dev/null || true)"
  [[ -n "${value}" ]] || die "environment variable named by ${env_key} is empty or unset"
  printf '%s' "${value}"
}

java_major() {
  local java_bin="$1"
  local raw
  raw="$("${java_bin}" -version 2>&1 | awk -F'"' '/version/ { print $2; exit }')"
  if [[ "${raw}" == 1.* ]]; then
    printf '%s\n' "${raw}" | awk -F. '{ print $2 }'
  else
    printf '%s\n' "${raw}" | awk -F. '{ print $1 }'
  fi
}

select_jdk() {
  local candidate major mac_home

  if [[ -n "${JAVA_HOME:-}" && -x "${JAVA_HOME}/bin/java" ]]; then
    major="$(java_major "${JAVA_HOME}/bin/java")"
    if [[ "${major}" =~ ^[0-9]+$ ]] && (( major >= 17 )); then
      return
    fi
  fi

  if [[ -x /usr/libexec/java_home ]]; then
    mac_home="$(/usr/libexec/java_home -v '17+' 2>/dev/null || true)"
    if [[ -z "${mac_home}" ]]; then
      mac_home="$(/usr/libexec/java_home 2>/dev/null || true)"
    fi
    if [[ -n "${mac_home}" && -x "${mac_home}/bin/java" ]]; then
      major="$(java_major "${mac_home}/bin/java")"
      if [[ "${major}" =~ ^[0-9]+$ ]] && (( major >= 17 )); then
        export JAVA_HOME="${mac_home}"
        return
      fi
    fi
  fi

  for candidate in /usr/lib/jvm/*; do
    [[ -x "${candidate}/bin/java" ]] || continue
    major="$(java_major "${candidate}/bin/java")"
    if [[ "${major}" =~ ^[0-9]+$ ]] && (( major >= 17 )); then
      export JAVA_HOME="${candidate}"
      return
    fi
  done

  if command -v java >/dev/null 2>&1; then
    major="$(java_major "$(command -v java)")"
    if [[ "${major}" =~ ^[0-9]+$ ]] && (( major >= 17 )); then
      unset JAVA_HOME
      return
    fi
  fi

  die "JDK 17 or newer is required (set JAVA_HOME to a compatible JDK)"
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

assert_clean_source() {
  local expected_head="$1"
  local expected_branch="$2"
  local current_head current_branch

  [[ -z "$(git status --porcelain --untracked-files=normal)" ]] || \
    die "release candidates require a clean working tree"
  current_head="$(git rev-parse HEAD)"
  current_branch="$(git symbolic-ref --quiet --short HEAD 2>/dev/null || true)"
  [[ "${current_head}" == "${expected_head}" ]] || \
    die "source HEAD changed while building the candidate"
  [[ "${current_branch}" == "${expected_branch}" ]] || \
    die "source branch changed while building the candidate"
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

private_file_mode() {
  local file="$1"
  local mode

  if mode="$(stat -f '%Lp' "${file}" 2>/dev/null)"; then
    printf '%s\n' "${mode}"
    return
  fi
  if mode="$(stat -c '%a' "${file}" 2>/dev/null)"; then
    printf '%s\n' "${mode}"
    return
  fi
  return 1
}

private_file_owner() {
  local file="$1"
  local owner

  if owner="$(stat -f '%u' "${file}" 2>/dev/null)"; then
    printf '%s\n' "${owner}"
    return
  fi
  if owner="$(stat -c '%u' "${file}" 2>/dev/null)"; then
    printf '%s\n' "${owner}"
    return
  fi
  return 1
}

private_file_link_count() {
  local file="$1"
  local links

  if links="$(stat -f '%l' "${file}" 2>/dev/null)"; then
    printf '%s\n' "${links}"
    return
  fi
  if links="$(stat -c '%h' "${file}" 2>/dev/null)"; then
    printf '%s\n' "${links}"
    return
  fi
  return 1
}

canonical_historical_private_file() {
  local label="$1"
  local value="$2"
  local absolute directory name resolved mode owner links

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
      die "${label} must stay outside the repository"
      ;;
  esac
  mode="$(private_file_mode "${resolved}" 2>/dev/null || true)"
  owner="$(private_file_owner "${resolved}" 2>/dev/null || true)"
  links="$(private_file_link_count "${resolved}" 2>/dev/null || true)"
  [[ "${mode}" == "600" && "${owner}" == "${EUID}" && "${links}" == "1" ]] || \
    die "${label} must be owned by the current user, have mode 0600, and have one link"
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

assert_apk_source_verifier_matches_source() {
  local commit="$1"
  assert_source_file_matches_commit \
    "${commit}" \
    "tools/verify-apk-third-party-sources.mjs" \
    "${APK_SOURCE_PROVENANCE_VERIFIER}" \
    "APK source provenance verifier"
}

assert_historical_contract_matches_source() {
  local commit="$1"
  assert_source_file_matches_commit \
    "${commit}" \
    "tools/historical-release-contract.mjs" \
    "${HISTORICAL_RELEASE_CONTRACT}" \
    "historical release contract"
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

  assert_apk_source_verifier_matches_source "${SOURCE_HEAD}"
  verifier_before="$(sha256_file "${APK_SOURCE_PROVENANCE_VERIFIER}")"
  policy_before="$(sha256_file "${APK_THIRD_PARTY_POLICY}")"
  apk_before="$(sha256_file "${apk}")"
  [[ "${verifier_before}" =~ ^[0-9a-f]{64}$ \
    && "${policy_before}" =~ ^[0-9a-f]{64}$ \
    && "${apk_before}" =~ ^[0-9a-f]{64}$ ]] || \
    die "APK source provenance inputs could not be hashed"

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
  [[ ${status} -eq 0 ]] || die "APK third-party source provenance verification failed"
  [[ -f "${report}" && ! -L "${report}" ]] || \
    die "APK third-party source provenance verification did not create a report"

  verifier_after="$(sha256_file "${APK_SOURCE_PROVENANCE_VERIFIER}")"
  policy_after="$(sha256_file "${APK_THIRD_PARTY_POLICY}")"
  apk_after="$(sha256_file "${apk}")"
  [[ "${verifier_after}" == "${verifier_before}" \
    && "${policy_after}" == "${policy_before}" \
    && "${apk_after}" == "${apk_before}" ]] || \
    die "APK source provenance inputs changed during verification"
  assert_apk_source_verifier_matches_source "${SOURCE_HEAD}"

  jq -e -s \
    --arg verifier "${verifier_before}" \
    --arg policy "${policy_before}" \
    --arg apk "${apk_before}" \
    'length == 1 and (.[0]
      | type == "object"
      and (keys == ["apkMatchedDexStringCount", "apkSha256", "compiledOutputCount", "declaredDexStringCount", "dexSourceArtifactCount", "dexSourceEntryCount", "mergedSourceCount", "passed", "policySha256", "profileId", "reportSha256", "schemaVersion", "sourceArtifactCount", "sourceEntryCount", "sourceMatchedDexStringCount", "verifierSha256"])
      and .schemaVersion == 1
      and .passed == true
      and .verifierSha256 == $verifier
      and .policySha256 == $policy
      and .apkSha256 == $apk
      and (.profileId | type == "string" and test("^[a-z0-9][a-z0-9._-]*$"))
      and (.sourceArtifactCount | type == "number" and floor == . and . > 0)
      and (.sourceEntryCount | type == "number" and floor == . and . > 0)
      and .sourceEntryCount == .sourceArtifactCount
      and .mergedSourceCount == 1
      and (.compiledOutputCount | type == "number" and floor == . and . > 0)
      and (.dexSourceArtifactCount | type == "number" and floor == . and . > 0)
      and (.dexSourceEntryCount | type == "number" and floor == . and . > 0)
      and (.declaredDexStringCount | type == "number" and floor == . and . > 0)
      and .sourceMatchedDexStringCount == .declaredDexStringCount
      and .apkMatchedDexStringCount == .declaredDexStringCount
      and (.reportSha256 | type == "string" and test("^[0-9a-f]{64}$")))' \
    "${report}" >/dev/null 2>&1 || \
    die "APK third-party source provenance report was incomplete or unbound"
  report_base="$(jq -ceS 'del(.reportSha256)' "${report}")" || \
    die "APK third-party source provenance report could not be canonicalized"
  report_sha="$(jq -er '.reportSha256' "${report}")"
  calculated_report_sha="$(printf '%s' "${report_base}" | sha256_stdin)"
  [[ "${report_sha}" == "${calculated_report_sha}" ]] || \
    die "APK third-party source provenance report hash mismatch"
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

  assert_scanner_matches_source "${SOURCE_HEAD}"
  scanner_before="$(sha256_file "${PUBLIC_AUDIT_SCANNER}")"
  third_party_policy_sha="$(sha256_file "${APK_THIRD_PARTY_POLICY}")"
  [[ "${scanner_before}" =~ ^[0-9a-f]{64}$ ]] || die "could not hash public audit scanner"
  wordlist_count="${#PRIVATE_WORDLISTS[@]}"
  for ((index = 0; index < wordlist_count; index++)); do
    fingerprint="$(sha256_private_file "${PRIVATE_WORDLISTS[index]}")" || \
      die "private wordlist became unreadable"
    [[ "${fingerprint}" =~ ^[0-9a-f]{64}$ ]] || die "could not hash private wordlist"
    [[ "${fingerprint}" == "${PRIVATE_WORDLIST_FINGERPRINTS[index]}" ]] || \
      die "private wordlist changed after release preparation began"
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

  scanner_after="$(sha256_file "${PUBLIC_AUDIT_SCANNER}")"
  [[ "${scanner_after}" == "${scanner_before}" ]] || \
    die "public audit scanner changed while an audit was running"
  assert_scanner_matches_source "${SOURCE_HEAD}"
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

signer_sha256() {
  local apk="$1"
  local digest
  digest="$("${APKSIGNER_BIN}" verify --print-certs "${apk}" 2>/dev/null \
    | awk -F': ' '/Signer #1 certificate SHA-256 digest:/ { print tolower($2); exit }')"
  [[ "${digest}" =~ ^[0-9a-f]{64}$ ]] || die "could not read signer SHA-256 from ${apk}"
  printf '%s\n' "${digest}"
}

assert_lineage_input_unchanged() {
  if [[ "${LINEAGE_MODE}" == "standard-upgrade" ]]; then
    [[ "$(sha256_file "${PREVIOUS_APK}")" == "${PREVIOUS_APK_SHA256}" ]] || \
      die "previous APK changed while building the candidate"
  else
    assert_historical_inputs_unchanged
  fi
}

assert_historical_inputs_unchanged() {
  local inventory_resolved original_resolved title_resolved selection selection_sha
  assert_historical_contract_matches_source "${SOURCE_HEAD}"
  inventory_resolved="$(canonical_historical_private_file \
    "historical inventory" "${HISTORICAL_INVENTORY}")"
  original_resolved="$(canonical_historical_private_file \
    "historical original APK" "${HISTORICAL_ORIGINAL_APK}")"
  title_resolved="$(canonical_historical_private_file \
    "historical Release title" "${HISTORICAL_TITLE_FILE}")"
  [[ "${inventory_resolved}" == "${HISTORICAL_INVENTORY}" \
    && "${original_resolved}" == "${HISTORICAL_ORIGINAL_APK}" \
    && "${title_resolved}" == "${HISTORICAL_TITLE_FILE}" ]] || \
    die "a historical private input path changed after validation"
  [[ "$(sha256_file "${HISTORICAL_INVENTORY}")" == "${HISTORICAL_INVENTORY_FILE_SHA256}" \
    && "$(sha256_file "${HISTORICAL_ORIGINAL_APK}")" == "${HISTORICAL_ORIGINAL_APK_SHA256}" \
    && "$(sha256_file "${HISTORICAL_TITLE_FILE}")" == "${HISTORICAL_TITLE_SHA256}" ]] || \
    die "a historical private input changed while building the candidate"
  selection="$(node "${HISTORICAL_RELEASE_CONTRACT}" \
    --inventory "${HISTORICAL_INVENTORY}" \
    --inventory-file-sha256 "${HISTORICAL_INVENTORY_FILE_SHA256}" \
    --tag "${TAG}" 2>/dev/null)" || \
    die "historical inventory no longer validates"
  selection_sha="$(printf '%s' "${selection}" | sha256_stdin)"
  [[ "${selection_sha}" == "${HISTORICAL_SELECTION_SHA256}" ]] || \
    die "historical inventory selection changed while building the candidate"
  if [[ "${LINEAGE_MODE}" == "historical-upgrade" ]]; then
    [[ "$(sha256_file "${HISTORICAL_PREVIOUS_MANIFEST}")" \
      == "${HISTORICAL_PREVIOUS_MANIFEST_SHA256}" \
      && "$(sha256_file "${PREVIOUS_APK}")" == "${PREVIOUS_APK_SHA256}" ]] || \
      die "previous historical rebuilt candidate changed while building the candidate"
  fi
}

VERSION=""
VERSION_CODE=""
EXPECTED_PACKAGE=""
CONFIG_PATH="config/signing.local.json"
NOTES=""
NOTES_FILE=""
PREVIOUS_APK=""
HISTORICAL_INVENTORY=""
HISTORICAL_ORIGINAL_APK=""
HISTORICAL_TITLE_FILE=""
HISTORICAL_PREVIOUS_CANDIDATE=""
LINEAGE_MODE="standard-upgrade"
PRIVATE_WORDLISTS=()
PRIVATE_WORDLIST_FINGERPRINTS=()
GRADLE_OFFLINE=false

while [[ $# -gt 0 ]]; do
  case "$1" in
    --version)
      [[ $# -ge 2 ]] || die "--version requires a value"
      VERSION="$2"
      shift 2
      ;;
    --version-code)
      [[ $# -ge 2 ]] || die "--version-code requires a value"
      VERSION_CODE="$2"
      shift 2
      ;;
    --config)
      [[ $# -ge 2 ]] || die "--config requires a path"
      CONFIG_PATH="$2"
      shift 2
      ;;
    --notes)
      [[ $# -ge 2 ]] || die "--notes requires text"
      [[ -z "${NOTES_FILE}" ]] || die "use only one of --notes or --notes-file"
      NOTES="$2"
      shift 2
      ;;
    --notes-file)
      [[ $# -ge 2 ]] || die "--notes-file requires a path"
      [[ -z "${NOTES}" ]] || die "use only one of --notes or --notes-file"
      NOTES_FILE="$2"
      shift 2
      ;;
    --previous-apk)
      [[ $# -ge 2 ]] || die "--previous-apk requires a path"
      PREVIOUS_APK="$2"
      shift 2
      ;;
    --historical-inventory)
      [[ $# -ge 2 ]] || die "--historical-inventory requires a path"
      HISTORICAL_INVENTORY="$2"
      shift 2
      ;;
    --historical-original-apk)
      [[ $# -ge 2 ]] || die "--historical-original-apk requires a path"
      HISTORICAL_ORIGINAL_APK="$2"
      shift 2
      ;;
    --historical-title-file)
      [[ $# -ge 2 ]] || die "--historical-title-file requires a path"
      HISTORICAL_TITLE_FILE="$2"
      shift 2
      ;;
    --historical-previous-candidate)
      [[ $# -ge 2 ]] || die "--historical-previous-candidate requires a path"
      HISTORICAL_PREVIOUS_CANDIDATE="$2"
      shift 2
      ;;
    --historical-initial-identity-apk|--historical-initial-identity-sha256)
      die "the unbound historical identity options were removed; use the exact pre-rewrite inventory"
      ;;
    --private-wordlist)
      [[ $# -ge 2 ]] || die "--private-wordlist requires a path"
      PRIVATE_WORDLISTS+=("$2")
      shift 2
      ;;
    --offline)
      GRADLE_OFFLINE=true
      shift
      ;;
    --publish)
      die "--publish was removed; create a candidate here, then use tools/publish-release.sh"
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

require_command git
require_command jq
require_command node
[[ -x ./gradlew ]] || die "Gradle wrapper is missing or not executable"
[[ -f "${PUBLIC_AUDIT_SCANNER}" && ! -L "${PUBLIC_AUDIT_SCANNER}" ]] || \
  die "public surface audit scanner is missing or is not a regular file"
[[ -f "${APK_THIRD_PARTY_POLICY}" && ! -L "${APK_THIRD_PARTY_POLICY}" ]] || \
  die "APK third-party provenance policy is missing or is not a regular file"
[[ -f "${ANDROID_RUNTIME_LOCK}" && ! -L "${ANDROID_RUNTIME_LOCK}" ]] || \
  die "Android runtime dependency lock is missing or is not a regular file"
[[ -f "${APK_SOURCE_PROVENANCE_VERIFIER}" && ! -L "${APK_SOURCE_PROVENANCE_VERIFIER}" ]] || \
  die "APK source provenance verifier is missing or is not a regular file"
[[ -f "${HISTORICAL_RELEASE_CONTRACT}" && ! -L "${HISTORICAL_RELEASE_CONTRACT}" ]] || \
  die "historical release contract is missing or is not a regular file"
[[ ${#PRIVATE_WORDLISTS[@]} -gt 0 ]] || die "at least one --private-wordlist is required"
for ((PRIVATE_WORDLIST_INDEX = 0; PRIVATE_WORDLIST_INDEX < ${#PRIVATE_WORDLISTS[@]}; PRIVATE_WORDLIST_INDEX++)); do
  PRIVATE_WORDLISTS[PRIVATE_WORDLIST_INDEX]="$(canonical_private_wordlist "${PRIVATE_WORDLISTS[PRIVATE_WORDLIST_INDEX]}")"
  PRIVATE_WORDLIST_FINGERPRINTS[PRIVATE_WORDLIST_INDEX]="$(
    sha256_private_file "${PRIVATE_WORDLISTS[PRIVATE_WORDLIST_INDEX]}"
  )" || die "private wordlist could not be fingerprinted"
  [[ "${PRIVATE_WORDLIST_FINGERPRINTS[PRIVATE_WORDLIST_INDEX]}" =~ ^[0-9a-f]{64}$ ]] || \
    die "private wordlist could not be fingerprinted"
done

if [[ -z "${VERSION}" ]]; then
  VERSION="$(default_gradle_value versionName)"
fi
if [[ -z "${VERSION_CODE}" ]]; then
  VERSION_CODE="$(default_gradle_value versionCode)"
fi
EXPECTED_PACKAGE="$(default_application_id)"
[[ "${VERSION}" =~ ^[0-9]+\.[0-9]+\.[0-9]+([+-][0-9A-Za-z.-]+)?$ ]] || \
  die "invalid version name: ${VERSION}"
[[ "${VERSION_CODE}" =~ ^[1-9][0-9]*$ ]] || die "version code must be a positive integer"
[[ "${EXPECTED_PACKAGE}" =~ ^[A-Za-z][A-Za-z0-9_]*(\.[A-Za-z][A-Za-z0-9_]*)+$ ]] || \
  die "could not determine a valid applicationId from app/build.gradle"

TAG="v${VERSION}"
CANDIDATES_DIR="${ROOT_DIR}/dist/release-candidates"
HISTORICAL_REQUESTED=false
if [[ -n "${HISTORICAL_INVENTORY}" || -n "${HISTORICAL_ORIGINAL_APK}" \
  || -n "${HISTORICAL_TITLE_FILE}" || -n "${HISTORICAL_PREVIOUS_CANDIDATE}" ]]; then
  HISTORICAL_REQUESTED=true
fi

if [[ "${HISTORICAL_REQUESTED}" == true ]]; then
  [[ -n "${HISTORICAL_INVENTORY}" && -n "${HISTORICAL_ORIGINAL_APK}" \
    && -n "${HISTORICAL_TITLE_FILE}" ]] || \
    die "historical mode requires inventory, original APK, and exact title file"
  [[ -z "${PREVIOUS_APK}" ]] || \
    die "historical mode does not accept --previous-apk"
  [[ -z "${NOTES}" && -z "${NOTES_FILE}" ]] || \
    die "historical mode uses the fixed audited body and does not accept custom notes"
  case "${TAG}" in
    v1.0.0|v1.0.1|v1.0.2|v1.0.3|v1.0.4|v1.0.5|v1.0.6) ;;
    *) die "historical mode is restricted to v1.0.0-v1.0.6" ;;
  esac
  HISTORICAL_INVENTORY="$(canonical_historical_private_file \
    "historical inventory" "${HISTORICAL_INVENTORY}")"
  HISTORICAL_ORIGINAL_APK="$(canonical_historical_private_file \
    "historical original APK" "${HISTORICAL_ORIGINAL_APK}")"
  HISTORICAL_TITLE_FILE="$(canonical_historical_private_file \
    "historical Release title" "${HISTORICAL_TITLE_FILE}")"
  HISTORICAL_INVENTORY_FILE_SHA256="$(sha256_file "${HISTORICAL_INVENTORY}")"
  HISTORICAL_ORIGINAL_APK_SHA256="$(sha256_file "${HISTORICAL_ORIGINAL_APK}")"
  HISTORICAL_TITLE_SHA256="$(sha256_file "${HISTORICAL_TITLE_FILE}")"
  HISTORICAL_SELECTION_JSON="$(node "${HISTORICAL_RELEASE_CONTRACT}" \
    --inventory "${HISTORICAL_INVENTORY}" \
    --inventory-file-sha256 "${HISTORICAL_INVENTORY_FILE_SHA256}" \
    --tag "${TAG}" 2>/dev/null)" || die "historical inventory selection failed"
  HISTORICAL_SELECTION_SHA256="$(printf '%s' "${HISTORICAL_SELECTION_JSON}" | sha256_stdin)"
  HISTORICAL_INVENTORY_SHA256="$(printf '%s' "${HISTORICAL_SELECTION_JSON}" \
    | jq -er '.inventorySha256')"
  HISTORICAL_SOURCE_INVENTORY_JSON="$(printf '%s' "${HISTORICAL_SELECTION_JSON}" \
    | jq -ce '.sourceInventory')"
  HISTORICAL_RELEASE_ENTRY_JSON="$(printf '%s' "${HISTORICAL_SELECTION_JSON}" \
    | jq -ce '.release')"
  HISTORICAL_ORIGINAL_ASSET_SHA256_JSON="$(printf '%s' "${HISTORICAL_SELECTION_JSON}" \
    | jq -ce '.originalAssetSha256')"
  HISTORICAL_PUBLICATION_MODE="$(printf '%s' "${HISTORICAL_SELECTION_JSON}" \
    | jq -er '.publicationMode')"
  NOTES="$(printf '%s' "${HISTORICAL_SELECTION_JSON}" | jq -er '.body')"
  HISTORICAL_SEQUENCE="$(printf '%s' "${HISTORICAL_RELEASE_ENTRY_JSON}" \
    | jq -er '.sequence | tostring')"
  HISTORICAL_APK_ASSET_NAME="$(printf '%s' "${HISTORICAL_RELEASE_ENTRY_JSON}" \
    | jq -er '.originalApk.assetName')"
  HISTORICAL_ORIGINAL_PACKAGE="$(printf '%s' "${HISTORICAL_RELEASE_ENTRY_JSON}" \
    | jq -er '.originalApk.packageName')"
  HISTORICAL_ORIGINAL_CODE="$(printf '%s' "${HISTORICAL_RELEASE_ENTRY_JSON}" \
    | jq -er '.originalApk.versionCode | tostring')"
  HISTORICAL_ORIGINAL_VERSION="$(printf '%s' "${HISTORICAL_RELEASE_ENTRY_JSON}" \
    | jq -er '.originalApk.versionName')"
  HISTORICAL_ORIGINAL_SIGNER="$(printf '%s' "${HISTORICAL_RELEASE_ENTRY_JSON}" \
    | jq -er '.originalApk.signerSha256')"
  HISTORICAL_RELEASE_TITLE="$(<"${HISTORICAL_TITLE_FILE}")"
  HISTORICAL_TITLE_LENGTH="$(jq -Rs 'explode | length' "${HISTORICAL_TITLE_FILE}")"
  jq -e -Rs 'length > 0
    and (contains("\u0000") | not)
    and (contains("\r") | not)
    and (contains("\n") | not)' \
    "${HISTORICAL_TITLE_FILE}" >/dev/null 2>&1 || \
    die "historical Release title file must contain one non-empty line without a trailing newline"
  [[ "${HISTORICAL_TITLE_SHA256}" == "$(printf '%s' "${HISTORICAL_RELEASE_ENTRY_JSON}" \
      | jq -er '.titleSha256')" \
    && "${HISTORICAL_TITLE_LENGTH}" == "$(printf '%s' "${HISTORICAL_RELEASE_ENTRY_JSON}" \
      | jq -er '.titleLength | tostring')" ]] || \
    die "historical Release title does not match the pre-rewrite inventory"
  [[ "$(basename "${HISTORICAL_ORIGINAL_APK}")" == "${HISTORICAL_APK_ASSET_NAME}" \
    && "${HISTORICAL_ORIGINAL_APK_SHA256}" == "$(printf '%s' \
      "${HISTORICAL_RELEASE_ENTRY_JSON}" | jq -er '.originalApk.sha256')" ]] || \
    die "historical original APK does not match the selected inventory asset"
  [[ "${VERSION}" == "${HISTORICAL_ORIGINAL_VERSION}" \
    && "${VERSION_CODE}" == "${HISTORICAL_ORIGINAL_CODE}" ]] || \
    die "historical candidate version does not match the selected original Release"
  if [[ "${HISTORICAL_SEQUENCE}" == "0" ]]; then
    [[ -z "${HISTORICAL_PREVIOUS_CANDIDATE}" ]] || \
      die "historical v1.0.0 must not declare a previous rebuilt candidate"
    LINEAGE_MODE="historical-initial"
  else
    [[ -n "${HISTORICAL_PREVIOUS_CANDIDATE}" ]] || \
      die "historical v1.0.1-v1.0.6 require the immediately previous rebuilt candidate"
    LINEAGE_MODE="historical-upgrade"
  fi
else
  case "${TAG}" in
    v1.0.0|v1.0.1|v1.0.2|v1.0.3|v1.0.4|v1.0.5|v1.0.6)
      die "reserved historical versions require the inventory-bound historical mode"
      ;;
  esac
  if [[ -n "${NOTES_FILE}" ]]; then
    NOTES_FILE="$(absolute_repo_path "${NOTES_FILE}")"
    [[ -f "${NOTES_FILE}" ]] || die "notes file not found: ${NOTES_FILE}"
    NOTES="$(<"${NOTES_FILE}")"
  fi
  if [[ -z "${NOTES}" ]]; then
    NOTES="autoform-kit ${VERSION}"
  fi
  if [[ -n "${PREVIOUS_APK}" ]]; then
    PREVIOUS_APK="$(absolute_repo_path "${PREVIOUS_APK}")"
    [[ -f "${PREVIOUS_APK}" && ! -L "${PREVIOUS_APK}" ]] || \
      die "previous APK must be a regular non-symlink file"
  fi
  [[ -n "${PREVIOUS_APK}" ]] || \
    die "--previous-apk is required so package, signer, and version continuity are bound to the candidate"
fi

SOURCE_HEAD="$(git rev-parse HEAD)"
SOURCE_BRANCH="$(git symbolic-ref --quiet --short HEAD 2>/dev/null || true)"
[[ "${SOURCE_HEAD}" =~ ^[0-9a-f]{40}([0-9a-f]{24})?$ ]] || \
  die "could not determine a valid source commit"
[[ -n "${SOURCE_BRANCH}" ]] || die "release candidates require a branch checkout, not detached HEAD"
[[ "${SOURCE_BRANCH}" == "main" ]] || die "release candidates must be built from main"
assert_clean_source "${SOURCE_HEAD}" "${SOURCE_BRANCH}"
assert_scanner_matches_source "${SOURCE_HEAD}"
assert_apk_source_verifier_matches_source "${SOURCE_HEAD}"
if [[ "${HISTORICAL_REQUESTED}" == true ]]; then
  assert_historical_contract_matches_source "${SOURCE_HEAD}"
  if [[ "${LINEAGE_MODE}" == "historical-upgrade" ]]; then
    HISTORICAL_PREVIOUS_SEQUENCE="$((10#${HISTORICAL_SEQUENCE} - 1))"
    HISTORICAL_PREVIOUS_TAG="v1.0.${HISTORICAL_PREVIOUS_SEQUENCE}"
    HISTORICAL_PREVIOUS_CANDIDATE="$(absolute_repo_path \
      "${HISTORICAL_PREVIOUS_CANDIDATE}")"
    [[ -f "${HISTORICAL_PREVIOUS_CANDIDATE}" \
      && ! -L "${HISTORICAL_PREVIOUS_CANDIDATE}" \
      && "$(basename "${HISTORICAL_PREVIOUS_CANDIDATE}")" == "candidate-manifest.json" ]] || \
      die "previous historical candidate manifest must be one regular candidate-manifest.json"
    HISTORICAL_PREVIOUS_MANIFEST_DIRECTORY="$(cd \
      "$(dirname "${HISTORICAL_PREVIOUS_CANDIDATE}")" && pwd -P)"
    HISTORICAL_PREVIOUS_MANIFEST="${HISTORICAL_PREVIOUS_MANIFEST_DIRECTORY}/candidate-manifest.json"
    [[ "${HISTORICAL_PREVIOUS_MANIFEST}" \
      == "${CANDIDATES_DIR}/${HISTORICAL_PREVIOUS_TAG}/candidate-manifest.json" ]] || \
      die "historical continuation must use the immediately previous candidate directory"
    jq -e \
      --arg tag "${HISTORICAL_PREVIOUS_TAG}" \
      --arg publicationMode "${HISTORICAL_PUBLICATION_MODE}" \
      --arg inventoryFile "${HISTORICAL_INVENTORY_FILE_SHA256}" \
      --arg inventory "${HISTORICAL_INVENTORY_SHA256}" \
      --argjson sequence "${HISTORICAL_PREVIOUS_SEQUENCE}" \
      '.schemaVersion == 4
        and .publicationMode == $publicationMode
        and .tag == $tag
        and .lineage.sequence == $sequence
        and .historicalRelease.inventory.fileSha256 == $inventoryFile
        and .historicalRelease.inventory.inventorySha256 == $inventory
        and (.artifacts.apk.file | type == "string")
        and (.artifacts.apk.sha256 | type == "string" and test("^[a-f0-9]{64}$"))
        and (.app.packageName | type == "string")
        and (.app.versionCode | type == "number" and floor == . and . > 0)
        and .app.versionName == ($tag | ltrimstr("v"))
        and (.app.signerSha256 | type == "string" and test("^[a-f0-9]{64}$"))' \
      "${HISTORICAL_PREVIOUS_MANIFEST}" >/dev/null 2>&1 || \
      die "previous historical rebuilt candidate has invalid mode, inventory, or lineage"
    HISTORICAL_PREVIOUS_MANIFEST_SHA256="$(sha256_file \
      "${HISTORICAL_PREVIOUS_MANIFEST}")"
    HISTORICAL_PREVIOUS_APK_FILE="$(jq -er '.artifacts.apk.file' \
      "${HISTORICAL_PREVIOUS_MANIFEST}")"
    [[ "${HISTORICAL_PREVIOUS_APK_FILE}" =~ ^[A-Za-z0-9][A-Za-z0-9._-]{0,179}$ ]] || \
      die "previous historical candidate APK filename is invalid"
    PREVIOUS_APK="${HISTORICAL_PREVIOUS_MANIFEST_DIRECTORY}/${HISTORICAL_PREVIOUS_APK_FILE}"
    [[ -f "${PREVIOUS_APK}" && ! -L "${PREVIOUS_APK}" ]] || \
      die "previous historical candidate APK is missing"
    PREVIOUS_APK_SHA256="$(sha256_file "${PREVIOUS_APK}")"
    [[ "${PREVIOUS_APK_SHA256}" == "$(jq -er '.artifacts.apk.sha256' \
      "${HISTORICAL_PREVIOUS_MANIFEST}")" ]] || \
      die "previous historical candidate APK does not match its manifest"
  fi
fi

# Signing configuration is deliberately validated before a Gradle build, so an
# unprepared checkout fails quickly and creates no artifact.
CONFIG_PATH="$(absolute_repo_path "${CONFIG_PATH}")"
[[ -f "${CONFIG_PATH}" ]] || \
  die "signing config not found: ${CONFIG_PATH} (copy config/signing.example.json to config/signing.local.json and fill it locally)"
jq -e 'type == "object"' "${CONFIG_PATH}" >/dev/null 2>&1 || \
  die "signing config is not a valid JSON object"

KEYSTORE_VALUE="$(json_string "${CONFIG_PATH}" keystore)"
KEY_ALIAS="$(json_string "${CONFIG_PATH}" keyAlias)"
[[ -n "${KEYSTORE_VALUE}" ]] || die "signing config keystore is empty"
[[ -n "${KEY_ALIAS}" ]] || die "signing config keyAlias is empty"

CONFIG_DIR="$(cd "$(dirname "${CONFIG_PATH}")" && pwd -P)"
case "${KEYSTORE_VALUE}" in
  /*) KEYSTORE_PATH="${KEYSTORE_VALUE}" ;;
  *)
    if [[ -f "${ROOT_DIR}/${KEYSTORE_VALUE}" ]]; then
      KEYSTORE_PATH="${ROOT_DIR}/${KEYSTORE_VALUE}"
    elif [[ -f "${CONFIG_DIR}/${KEYSTORE_VALUE}" ]]; then
      KEYSTORE_PATH="${CONFIG_DIR}/${KEYSTORE_VALUE}"
    else
      die "keystore not found (tried repository and config-relative paths)"
    fi
    ;;
esac
[[ -f "${KEYSTORE_PATH}" ]] || die "keystore file not found"

STORE_PASSWORD="$(read_secret "${CONFIG_PATH}" storePassword storePasswordEnv)"
KEY_PASSWORD="$(read_secret "${CONFIG_PATH}" keyPassword keyPasswordEnv)"

FINAL_DIR="${CANDIDATES_DIR}/${TAG}"
[[ ! -e "${FINAL_DIR}" ]] || die "candidate already exists: ${FINAL_DIR}"

select_jdk
JAVA_BIN="${JAVA_HOME:+${JAVA_HOME}/bin/}java"
JAVA_MAJOR="$(java_major "${JAVA_BIN}")"
info "using JDK ${JAVA_MAJOR}"

ZIPALIGN_BIN="$(find_android_tool zipalign "${ZIPALIGN:-}")"
APKSIGNER_BIN="$(find_android_tool apksigner "${APKSIGNER:-}")"
AAPT_BIN="$(find_android_tool aapt "${AAPT:-}")"

if [[ "${HISTORICAL_REQUESTED}" == true ]]; then
  assert_historical_inputs_unchanged
  HISTORICAL_ORIGINAL_PACKAGE_LINE="$("${AAPT_BIN}" dump badging \
    "${HISTORICAL_ORIGINAL_APK}" | sed -n '1p')"
  HISTORICAL_ORIGINAL_ACTUAL_PACKAGE="$(printf '%s\n' \
    "${HISTORICAL_ORIGINAL_PACKAGE_LINE}" \
    | sed -n "s/^package: name='\([^']*\)'.*/\1/p")"
  HISTORICAL_ORIGINAL_ACTUAL_CODE="$(printf '%s\n' \
    "${HISTORICAL_ORIGINAL_PACKAGE_LINE}" \
    | sed -n "s/.* versionCode='\([^']*\)'.*/\1/p")"
  HISTORICAL_ORIGINAL_ACTUAL_VERSION="$(printf '%s\n' \
    "${HISTORICAL_ORIGINAL_PACKAGE_LINE}" \
    | sed -n "s/.* versionName='\([^']*\)'.*/\1/p")"
  HISTORICAL_ORIGINAL_ACTUAL_SIGNER="$(
    signer_sha256 "${HISTORICAL_ORIGINAL_APK}"
  )"
  [[ "${HISTORICAL_ORIGINAL_ACTUAL_PACKAGE}" == "${EXPECTED_PACKAGE}" \
    && "${HISTORICAL_ORIGINAL_ACTUAL_PACKAGE}" == "${HISTORICAL_ORIGINAL_PACKAGE}" \
    && "${HISTORICAL_ORIGINAL_ACTUAL_CODE}" == "${HISTORICAL_ORIGINAL_CODE}" \
    && "${HISTORICAL_ORIGINAL_ACTUAL_VERSION}" == "${HISTORICAL_ORIGINAL_VERSION}" \
    && "${HISTORICAL_ORIGINAL_ACTUAL_SIGNER}" == "${HISTORICAL_ORIGINAL_SIGNER}" ]] || \
    die "historical original APK identity does not match its pre-rewrite inventory entry"
  assert_historical_inputs_unchanged
  info "validated exact private pre-rewrite APK identity"
fi

mkdir -p "${CANDIDATES_DIR}"
TMP_DIR="$(mktemp -d "${CANDIDATES_DIR}/.${TAG}.XXXXXX")"
cleanup() {
  local status=$?
  trap - EXIT HUP INT TERM
  unset AUTOFORM_RELEASE_STORE_PASSWORD AUTOFORM_RELEASE_KEY_PASSWORD
  if [[ -n "${TMP_DIR:-}" && -d "${TMP_DIR}" ]]; then
    rm -rf "${TMP_DIR}"
  fi
  exit ${status}
}
trap cleanup EXIT HUP INT TERM

UNSIGNED_APK="${ROOT_DIR}/app/build/outputs/apk/release/app-release-unsigned.apk"
ALIGNED_APK="${TMP_DIR}/aligned.apk"
if [[ "${HISTORICAL_REQUESTED}" == true ]]; then
  SIGNED_APK="${TMP_DIR}/${HISTORICAL_APK_ASSET_NAME}"
else
  SIGNED_APK="${TMP_DIR}/autoform-kit-${VERSION}.apk"
fi
UPDATE_JSON="${TMP_DIR}/update.json"
RELEASE_NOTES="${TMP_DIR}/release-notes.txt"
CANDIDATE_MANIFEST="${TMP_DIR}/candidate-manifest.json"

info "testing, linting, and building unsigned release ${VERSION} (${VERSION_CODE})"
GRADLE_ARGS=(--no-daemon)
if [[ "${GRADLE_OFFLINE}" == true ]]; then
  GRADLE_ARGS+=(--offline)
fi
./gradlew "${GRADLE_ARGS[@]}" :app:testDebugUnitTest :app:lintRelease :app:assembleRelease \
  -PversionCode="${VERSION_CODE}" \
  -PversionName="${VERSION}"
[[ -f "${UNSIGNED_APK}" ]] || die "Gradle did not create ${UNSIGNED_APK}"

APK_SOURCE_PROVENANCE_REPORT="${TMP_DIR}/.apk-source-provenance.json"
info "verifying unsigned APK public AAR, class, DEX-string, and runtime-profile provenance"
run_apk_source_provenance_verification "${APK_SOURCE_PROVENANCE_REPORT}" "${UNSIGNED_APK}"

info "aligning APK"
"${ZIPALIGN_BIN}" -f -p 4 "${UNSIGNED_APK}" "${ALIGNED_APK}"

# apksigner reads secrets from its environment. Do not replace these with
# pass:<value> arguments: command arguments can be exposed by process listings.
export AUTOFORM_RELEASE_STORE_PASSWORD="${STORE_PASSWORD}"
export AUTOFORM_RELEASE_KEY_PASSWORD="${KEY_PASSWORD}"
info "signing APK"
"${APKSIGNER_BIN}" sign \
  --ks "${KEYSTORE_PATH}" \
  --ks-key-alias "${KEY_ALIAS}" \
  --ks-pass env:AUTOFORM_RELEASE_STORE_PASSWORD \
  --key-pass env:AUTOFORM_RELEASE_KEY_PASSWORD \
  --v4-signing-enabled false \
  --out "${SIGNED_APK}" \
  "${ALIGNED_APK}"
unset AUTOFORM_RELEASE_STORE_PASSWORD AUTOFORM_RELEASE_KEY_PASSWORD
STORE_PASSWORD=""
KEY_PASSWORD=""

info "verifying alignment and signature"
"${ZIPALIGN_BIN}" -c -p 4 "${SIGNED_APK}"
"${APKSIGNER_BIN}" verify --verbose --print-certs "${SIGNED_APK}"
CURRENT_SIGNER="$(signer_sha256 "${SIGNED_APK}")"
if [[ "${LINEAGE_MODE}" == "standard-upgrade" ]]; then
  PREVIOUS_APK_SHA256="$(sha256_file "${PREVIOUS_APK}")"
  PREVIOUS_SIGNER="$(signer_sha256 "${PREVIOUS_APK}")"
  [[ "${CURRENT_SIGNER}" == "${PREVIOUS_SIGNER}" ]] || \
    die "signed APK certificate does not match the previous release"
  info "signer certificate matches the previous release"
else
  [[ "${CURRENT_SIGNER}" == "${HISTORICAL_ORIGINAL_SIGNER}" ]] || \
    die "signed APK certificate does not match the selected original historical APK"
  if [[ "${LINEAGE_MODE}" == "historical-upgrade" ]]; then
    PREVIOUS_SIGNER="$(signer_sha256 "${PREVIOUS_APK}")"
    [[ "${PREVIOUS_SIGNER}" == "${CURRENT_SIGNER}" \
      && "${PREVIOUS_SIGNER}" == "$(jq -er '.app.signerSha256' \
        "${HISTORICAL_PREVIOUS_MANIFEST}")" ]] || \
      die "previous rebuilt candidate signer does not continue the historical chain"
  fi
  info "signer certificate matches the pre-rewrite inventory chain"
fi

PACKAGE_LINE="$("${AAPT_BIN}" dump badging "${SIGNED_APK}" | sed -n '1p')"
ACTUAL_PACKAGE="$(printf '%s\n' "${PACKAGE_LINE}" | sed -n "s/^package: name='\([^']*\)'.*/\1/p")"
ACTUAL_CODE="$(printf '%s\n' "${PACKAGE_LINE}" | sed -n "s/.* versionCode='\([^']*\)'.*/\1/p")"
ACTUAL_VERSION="$(printf '%s\n' "${PACKAGE_LINE}" | sed -n "s/.* versionName='\([^']*\)'.*/\1/p")"
[[ "${ACTUAL_PACKAGE}" == "${EXPECTED_PACKAGE}" ]] || \
  die "signed APK has unexpected package name: ${ACTUAL_PACKAGE}"
[[ "${ACTUAL_CODE}" == "${VERSION_CODE}" ]] || \
  die "signed APK versionCode ${ACTUAL_CODE} does not match ${VERSION_CODE}"
[[ "${ACTUAL_VERSION}" == "${VERSION}" ]] || \
  die "signed APK versionName ${ACTUAL_VERSION} does not match ${VERSION}"

if [[ "${LINEAGE_MODE}" == "standard-upgrade" ]]; then
  PREVIOUS_PACKAGE_LINE="$("${AAPT_BIN}" dump badging "${PREVIOUS_APK}" | sed -n '1p')"
  PREVIOUS_PACKAGE="$(printf '%s\n' "${PREVIOUS_PACKAGE_LINE}" | sed -n "s/^package: name='\([^']*\)'.*/\1/p")"
  PREVIOUS_CODE="$(printf '%s\n' "${PREVIOUS_PACKAGE_LINE}" | sed -n "s/.* versionCode='\([^']*\)'.*/\1/p")"
  PREVIOUS_VERSION="$(printf '%s\n' "${PREVIOUS_PACKAGE_LINE}" | sed -n "s/.* versionName='\([^']*\)'.*/\1/p")"
  [[ "${PREVIOUS_PACKAGE}" == "${ACTUAL_PACKAGE}" ]] || \
    die "previous APK package ${PREVIOUS_PACKAGE} does not match ${ACTUAL_PACKAGE}"
  [[ "${PREVIOUS_CODE}" =~ ^[1-9][0-9]*$ ]] || \
    die "could not read a valid versionCode from the previous APK"
  (( 10#${VERSION_CODE} > 10#${PREVIOUS_CODE} )) || \
    die "candidate versionCode ${VERSION_CODE} must be greater than previous APK versionCode ${PREVIOUS_CODE}"
  [[ -n "${PREVIOUS_VERSION}" ]] || die "could not read versionName from the previous APK"
  [[ "$(sha256_file "${PREVIOUS_APK}")" == "${PREVIOUS_APK_SHA256}" ]] || \
    die "previous APK changed while its identity was being verified"
  LINEAGE_APK_SHA256="${PREVIOUS_APK_SHA256}"
  LINEAGE_PACKAGE="${PREVIOUS_PACKAGE}"
  LINEAGE_CODE="${PREVIOUS_CODE}"
  LINEAGE_VERSION="${PREVIOUS_VERSION}"
  LINEAGE_SIGNER="${PREVIOUS_SIGNER}"
else
  [[ "${ACTUAL_PACKAGE}" == "${HISTORICAL_ORIGINAL_PACKAGE}" \
    && "${ACTUAL_CODE}" == "${HISTORICAL_ORIGINAL_CODE}" \
    && "${ACTUAL_VERSION}" == "${HISTORICAL_ORIGINAL_VERSION}" ]] || \
    die "historical candidate identity does not exactly match its original inventory entry"
  if [[ "${LINEAGE_MODE}" == "historical-upgrade" ]]; then
    PREVIOUS_PACKAGE_LINE="$("${AAPT_BIN}" dump badging "${PREVIOUS_APK}" | sed -n '1p')"
    PREVIOUS_PACKAGE="$(printf '%s\n' "${PREVIOUS_PACKAGE_LINE}" \
      | sed -n "s/^package: name='\([^']*\)'.*/\1/p")"
    PREVIOUS_CODE="$(printf '%s\n' "${PREVIOUS_PACKAGE_LINE}" \
      | sed -n "s/.* versionCode='\([^']*\)'.*/\1/p")"
    PREVIOUS_VERSION="$(printf '%s\n' "${PREVIOUS_PACKAGE_LINE}" \
      | sed -n "s/.* versionName='\([^']*\)'.*/\1/p")"
    [[ "${PREVIOUS_PACKAGE}" == "${ACTUAL_PACKAGE}" \
      && "${PREVIOUS_PACKAGE}" == "$(jq -er '.app.packageName' \
        "${HISTORICAL_PREVIOUS_MANIFEST}")" \
      && "${PREVIOUS_CODE}" == "$(jq -er '.app.versionCode | tostring' \
        "${HISTORICAL_PREVIOUS_MANIFEST}")" \
      && "${PREVIOUS_VERSION}" == "${HISTORICAL_PREVIOUS_TAG#v}" \
      && "${PREVIOUS_VERSION}" == "$(jq -er '.app.versionName' \
        "${HISTORICAL_PREVIOUS_MANIFEST}")" ]] || \
      die "previous rebuilt candidate APK identity does not match its manifest or predecessor tag"
    (( 10#${ACTUAL_CODE} > 10#${PREVIOUS_CODE} )) || \
      die "historical rebuilt candidate versionCode must increase from its immediate predecessor"
  fi
  assert_historical_inputs_unchanged
  LINEAGE_APK_SHA256="${HISTORICAL_ORIGINAL_APK_SHA256}"
  LINEAGE_PACKAGE="${HISTORICAL_ORIGINAL_PACKAGE}"
  LINEAGE_CODE="${HISTORICAL_ORIGINAL_CODE}"
  LINEAGE_VERSION="${HISTORICAL_ORIGINAL_VERSION}"
  LINEAGE_SIGNER="${HISTORICAL_ORIGINAL_SIGNER}"
fi

APK_SHA256="$(sha256_file "${SIGNED_APK}")"
if [[ "${HISTORICAL_REQUESTED}" == true ]]; then
  [[ "${APK_SHA256}" != "${HISTORICAL_ORIGINAL_APK_SHA256}" ]] || \
    die "historical rebuilt APK must be different bytes from the original asset"
  printf '%s' "${HISTORICAL_ORIGINAL_ASSET_SHA256_JSON}" \
    | jq -e --arg apk "${APK_SHA256}" 'index($apk) == null' >/dev/null 2>&1 || \
    die "historical rebuilt APK must not reuse any original Release asset bytes"
fi
info "verifying signed APK public AAR, class, DEX-string, and runtime-profile provenance"
run_apk_source_provenance_verification "${APK_SOURCE_PROVENANCE_REPORT}" "${SIGNED_APK}"
APK_SOURCE_PROVENANCE_REPORT_FILE_SHA256="$(sha256_file "${APK_SOURCE_PROVENANCE_REPORT}")"
jq -n \
  --arg packageName "${ACTUAL_PACKAGE}" \
  --argjson versionCode "${VERSION_CODE}" \
  --arg versionName "${VERSION}" \
  --arg apkAsset "$(basename "${SIGNED_APK}")" \
  --arg sha256 "${APK_SHA256}" \
  --arg notes "${NOTES}" \
  '{
    packageName: $packageName,
    versionCode: $versionCode,
    versionName: $versionName,
    apkAsset: $apkAsset,
    sha256: $sha256,
    notes: $notes
  }' > "${UPDATE_JSON}"
if [[ "${HISTORICAL_REQUESTED}" == true ]]; then
  printf '%s' "${NOTES}" > "${RELEASE_NOTES}"
else
  printf '%s\n' "${NOTES}" > "${RELEASE_NOTES}"
fi
UPDATE_SHA256="$(sha256_file "${UPDATE_JSON}")"
NOTES_SHA256="$(sha256_file "${RELEASE_NOTES}")"
if [[ "${HISTORICAL_REQUESTED}" == true ]]; then
  printf '%s' "${HISTORICAL_ORIGINAL_ASSET_SHA256_JSON}" \
    | jq -e --arg update "${UPDATE_SHA256}" 'index($update) == null' >/dev/null 2>&1 || \
    die "historical rebuilt update manifest must not reuse original Release asset bytes"
fi

# The source and lineage identity input may have changed during a long build.
# Recheck both immediately before auditing and creating the immutable manifest.
assert_clean_source "${SOURCE_HEAD}" "${SOURCE_BRANCH}"
assert_lineage_input_unchanged

PUBLIC_TREE_REPORT="${TMP_DIR}/.public-source-tree-audit.json"
PUBLIC_WORKTREE_REPORT="${TMP_DIR}/.public-worktree-audit.json"
PUBLIC_APK_REPORT="${TMP_DIR}/.public-apk-audit.json"
PUBLIC_COMMIT_OBJECT="${TMP_DIR}/.public-source-commit.scan-input"
PUBLIC_COMMIT_REPORT="${TMP_DIR}/.public-source-commit-audit.json"
set +e
git cat-file commit "${SOURCE_HEAD}" >"${PUBLIC_COMMIT_OBJECT}" \
  2>"${TMP_DIR}/.public-source-commit.stderr"
PUBLIC_COMMIT_STATUS=$?
set -e
rm -f "${TMP_DIR}/.public-source-commit.stderr"
[[ ${PUBLIC_COMMIT_STATUS} -eq 0 \
  && -s "${PUBLIC_COMMIT_OBJECT}" \
  && ! -L "${PUBLIC_COMMIT_OBJECT}" ]] || \
  die "could not capture the exact source commit object for public audit"
chmod 0600 "${PUBLIC_COMMIT_OBJECT}"
info "auditing exact source commit object, tree, current worktree, and generated APK"
run_public_audit "${PUBLIC_COMMIT_REPORT}" file "${PUBLIC_COMMIT_OBJECT}"
run_public_audit "${PUBLIC_TREE_REPORT}" git-tree "${SOURCE_HEAD}"
run_public_audit "${PUBLIC_WORKTREE_REPORT}" worktree
run_public_audit "${PUBLIC_APK_REPORT}" apk "${SIGNED_APK}"
[[ "$(git rev-parse HEAD)" == "${SOURCE_HEAD}" ]] || \
  die "source commit changed while public audits were running"
assert_clean_source "${SOURCE_HEAD}" "${SOURCE_BRANCH}"
[[ "$(sha256_file "${SIGNED_APK}")" == "${APK_SHA256}" ]] || \
  die "candidate APK changed while public audits were running"

PUBLIC_AUDIT_SCANNER_SHA256="$(jq -er '.scannerSha256' "${PUBLIC_TREE_REPORT}")"
PUBLIC_AUDIT_POLICY_SHA256="$(jq -er '.policySha256' "${PUBLIC_TREE_REPORT}")"
PUBLIC_TREE_OID="$(jq -er '.input.gitTreeOid' "${PUBLIC_TREE_REPORT}")"
PUBLIC_TREE_INPUT_SHA256="$(jq -er '.input.sha256' "${PUBLIC_TREE_REPORT}")"
PUBLIC_TREE_REPORT_SHA256="$(jq -er '.reportSha256' "${PUBLIC_TREE_REPORT}")"
PUBLIC_WORKTREE_INPUT_SHA256="$(jq -er '.input.sha256' "${PUBLIC_WORKTREE_REPORT}")"
PUBLIC_WORKTREE_REPORT_SHA256="$(jq -er '.reportSha256' "${PUBLIC_WORKTREE_REPORT}")"
PUBLIC_APK_INPUT_SHA256="$(jq -er '.input.sha256' "${PUBLIC_APK_REPORT}")"
PUBLIC_APK_REPORT_SHA256="$(jq -er '.reportSha256' "${PUBLIC_APK_REPORT}")"
PUBLIC_APK_ZIP_ENTRY_MANIFEST_SHA256="$(jq -er '.input.zipEntryManifestSha256' "${PUBLIC_APK_REPORT}")"
APK_THIRD_PARTY_POLICY_SHA256="$(jq -er '.thirdPartyPolicy.manifestSha256' "${PUBLIC_APK_REPORT}")"
APK_THIRD_PARTY_PROFILE_ID="$(jq -er '.thirdPartyPolicy.profileId' "${PUBLIC_APK_REPORT}")"
APK_THIRD_PARTY_MATCHED_ENTRY_COUNT="$(jq -er '.thirdPartyPolicy.matchedEntryCount' "${PUBLIC_APK_REPORT}")"
ANDROID_RUNTIME_LOCK_SHA256="$(sha256_file "${ANDROID_RUNTIME_LOCK}")"
[[ "$(sha256_file "${APK_SOURCE_PROVENANCE_REPORT}")" == \
  "${APK_SOURCE_PROVENANCE_REPORT_FILE_SHA256}" ]] || \
  die "APK source provenance report changed before candidate binding"
APK_SOURCE_VERIFIER_SHA256="$(jq -er '.verifierSha256' "${APK_SOURCE_PROVENANCE_REPORT}")"
APK_SOURCE_REPORT_SHA256="$(jq -er '.reportSha256' "${APK_SOURCE_PROVENANCE_REPORT}")"
APK_SOURCE_ARTIFACT_COUNT="$(jq -er '.sourceArtifactCount | tostring' "${APK_SOURCE_PROVENANCE_REPORT}")"
APK_SOURCE_ENTRY_COUNT="$(jq -er '.sourceEntryCount | tostring' "${APK_SOURCE_PROVENANCE_REPORT}")"
APK_MERGED_SOURCE_COUNT="$(jq -er '.mergedSourceCount | tostring' "${APK_SOURCE_PROVENANCE_REPORT}")"
APK_COMPILED_OUTPUT_COUNT="$(jq -er '.compiledOutputCount | tostring' "${APK_SOURCE_PROVENANCE_REPORT}")"
APK_DEX_SOURCE_ARTIFACT_COUNT="$(jq -er '.dexSourceArtifactCount | tostring' "${APK_SOURCE_PROVENANCE_REPORT}")"
APK_DEX_SOURCE_ENTRY_COUNT="$(jq -er '.dexSourceEntryCount | tostring' "${APK_SOURCE_PROVENANCE_REPORT}")"
APK_DECLARED_DEX_STRING_COUNT="$(jq -er '.declaredDexStringCount | tostring' "${APK_SOURCE_PROVENANCE_REPORT}")"
APK_SOURCE_MATCHED_DEX_STRING_COUNT="$(jq -er '.sourceMatchedDexStringCount | tostring' "${APK_SOURCE_PROVENANCE_REPORT}")"
APK_MATCHED_DEX_STRING_COUNT="$(jq -er '.apkMatchedDexStringCount | tostring' "${APK_SOURCE_PROVENANCE_REPORT}")"
[[ "$(jq -er '.scannerSha256' "${PUBLIC_WORKTREE_REPORT}")" == "${PUBLIC_AUDIT_SCANNER_SHA256}" \
  && "$(jq -er '.scannerSha256' "${PUBLIC_APK_REPORT}")" == "${PUBLIC_AUDIT_SCANNER_SHA256}" \
  && "$(jq -er '.policySha256' "${PUBLIC_WORKTREE_REPORT}")" == "${PUBLIC_AUDIT_POLICY_SHA256}" \
  && "$(jq -er '.policySha256' "${PUBLIC_APK_REPORT}")" == "${PUBLIC_AUDIT_POLICY_SHA256}" ]] || \
  die "public audits did not use one exact scanner and policy"
[[ "${PUBLIC_APK_INPUT_SHA256}" == "${APK_SHA256}" ]] || \
  die "public APK audit is not bound to the candidate APK"
[[ "${APK_THIRD_PARTY_POLICY_SHA256}" == "$(sha256_file "${APK_THIRD_PARTY_POLICY}")" \
  && "${APK_THIRD_PARTY_PROFILE_ID}" =~ ^[a-z0-9][a-z0-9._-]*$ \
  && "${APK_THIRD_PARTY_MATCHED_ENTRY_COUNT}" =~ ^[1-9][0-9]*$ \
  && "${PUBLIC_APK_ZIP_ENTRY_MANIFEST_SHA256}" =~ ^[0-9a-f]{64}$ \
  && "${ANDROID_RUNTIME_LOCK_SHA256}" =~ ^[0-9a-f]{64}$ ]] || \
  die "candidate APK third-party provenance is incomplete or unbound"
[[ "${APK_SOURCE_VERIFIER_SHA256}" == "$(sha256_file "${APK_SOURCE_PROVENANCE_VERIFIER}")" \
  && "${APK_SOURCE_REPORT_SHA256}" =~ ^[0-9a-f]{64}$ \
  && "$(jq -er '.apkSha256' "${APK_SOURCE_PROVENANCE_REPORT}")" == "${APK_SHA256}" \
  && "$(jq -er '.policySha256' "${APK_SOURCE_PROVENANCE_REPORT}")" == "${APK_THIRD_PARTY_POLICY_SHA256}" \
  && "$(jq -er '.profileId' "${APK_SOURCE_PROVENANCE_REPORT}")" == "${APK_THIRD_PARTY_PROFILE_ID}" \
  && "${APK_SOURCE_ARTIFACT_COUNT}" =~ ^[1-9][0-9]*$ \
  && "${APK_SOURCE_ENTRY_COUNT}" =~ ^[1-9][0-9]*$ \
  && "${APK_MERGED_SOURCE_COUNT}" =~ ^[1-9][0-9]*$ \
  && "${APK_COMPILED_OUTPUT_COUNT}" =~ ^[1-9][0-9]*$ \
  && "${APK_DEX_SOURCE_ARTIFACT_COUNT}" =~ ^[1-9][0-9]*$ \
  && "${APK_DEX_SOURCE_ENTRY_COUNT}" =~ ^[1-9][0-9]*$ \
  && "${APK_DECLARED_DEX_STRING_COUNT}" =~ ^[1-9][0-9]*$ \
  && "${APK_SOURCE_MATCHED_DEX_STRING_COUNT}" == "${APK_DECLARED_DEX_STRING_COUNT}" \
  && "${APK_MATCHED_DEX_STRING_COUNT}" == "${APK_DECLARED_DEX_STRING_COUNT}" ]] || \
  die "candidate APK source provenance is incomplete or disagrees with its public audit"

PUBLIC_UPDATE_REPORT="${TMP_DIR}/.public-update-audit.json"
PUBLIC_NOTES_REPORT="${TMP_DIR}/.public-notes-audit.json"
info "auditing exact public update metadata and release notes"
run_public_audit "${PUBLIC_UPDATE_REPORT}" file "${UPDATE_JSON}"
run_public_audit "${PUBLIC_NOTES_REPORT}" file "${RELEASE_NOTES}"
PUBLIC_UPDATE_INPUT_SHA256="$(jq -er '.input.sha256' "${PUBLIC_UPDATE_REPORT}")"
PUBLIC_UPDATE_REPORT_SHA256="$(jq -er '.reportSha256' "${PUBLIC_UPDATE_REPORT}")"
PUBLIC_NOTES_INPUT_SHA256="$(jq -er '.input.sha256' "${PUBLIC_NOTES_REPORT}")"
PUBLIC_NOTES_REPORT_SHA256="$(jq -er '.reportSha256' "${PUBLIC_NOTES_REPORT}")"
[[ "$(jq -er '.scannerSha256' "${PUBLIC_UPDATE_REPORT}")" == "${PUBLIC_AUDIT_SCANNER_SHA256}" \
  && "$(jq -er '.scannerSha256' "${PUBLIC_NOTES_REPORT}")" == "${PUBLIC_AUDIT_SCANNER_SHA256}" \
  && "$(jq -er '.policySha256' "${PUBLIC_UPDATE_REPORT}")" == "${PUBLIC_AUDIT_POLICY_SHA256}" \
  && "$(jq -er '.policySha256' "${PUBLIC_NOTES_REPORT}")" == "${PUBLIC_AUDIT_POLICY_SHA256}" ]] || \
  die "public release metadata audits did not use the bound scanner and policy"
[[ "${PUBLIC_UPDATE_INPUT_SHA256}" == "${UPDATE_SHA256}" \
  && "${PUBLIC_NOTES_INPUT_SHA256}" == "${NOTES_SHA256}" ]] || \
  die "public release metadata audits are not bound to the exact candidate files"

APK_SIZE="$(wc -c < "${SIGNED_APK}" | tr -d '[:space:]')"
UPDATE_SIZE="$(wc -c < "${UPDATE_JSON}" | tr -d '[:space:]')"
jq -nS \
  --arg tag "${TAG}" \
  --arg sourceCommit "${SOURCE_HEAD}" \
  --arg sourceBranch "${SOURCE_BRANCH}" \
  --arg packageName "${ACTUAL_PACKAGE}" \
  --argjson versionCode "${VERSION_CODE}" \
  --arg versionName "${VERSION}" \
  --arg signerSha256 "${CURRENT_SIGNER}" \
  --arg apkFile "$(basename "${SIGNED_APK}")" \
  --arg apkSha256 "${APK_SHA256}" \
  --argjson apkSize "${APK_SIZE}" \
  --arg updateSha256 "${UPDATE_SHA256}" \
  --argjson updateSize "${UPDATE_SIZE}" \
  --arg notesSha256 "${NOTES_SHA256}" \
  --arg lineageMode "${LINEAGE_MODE}" \
  --arg lineageApkSha256 "${LINEAGE_APK_SHA256}" \
  --arg lineagePackageName "${LINEAGE_PACKAGE}" \
  --argjson lineageVersionCode "${LINEAGE_CODE}" \
  --arg lineageVersionName "${LINEAGE_VERSION}" \
  --arg lineageSignerSha256 "${LINEAGE_SIGNER}" \
  --arg publicationMode "${HISTORICAL_PUBLICATION_MODE:-}" \
  --arg historicalTitle "${HISTORICAL_RELEASE_TITLE:-}" \
  --arg historicalBody "${NOTES}" \
  --arg historicalInventoryFileSha256 "${HISTORICAL_INVENTORY_FILE_SHA256:-}" \
  --arg historicalInventorySha256 "${HISTORICAL_INVENTORY_SHA256:-}" \
  --argjson historicalSourceInventory "${HISTORICAL_SOURCE_INVENTORY_JSON:-null}" \
  --argjson historicalEntry "${HISTORICAL_RELEASE_ENTRY_JSON:-null}" \
  --argjson historicalSequence "${HISTORICAL_SEQUENCE:-0}" \
  --arg historicalPreviousTag "${HISTORICAL_PREVIOUS_TAG:-}" \
  --arg historicalPreviousManifestSha256 "${HISTORICAL_PREVIOUS_MANIFEST_SHA256:-}" \
  --arg historicalPreviousApkSha256 "${PREVIOUS_APK_SHA256:-}" \
  --arg historicalPreviousPackageName "${PREVIOUS_PACKAGE:-}" \
  --argjson historicalPreviousVersionCode "${PREVIOUS_CODE:-0}" \
  --arg historicalPreviousVersionName "${PREVIOUS_VERSION:-}" \
  --arg historicalPreviousSignerSha256 "${PREVIOUS_SIGNER:-}" \
  --arg publicAuditScannerSha256 "${PUBLIC_AUDIT_SCANNER_SHA256}" \
  --arg publicAuditPolicySha256 "${PUBLIC_AUDIT_POLICY_SHA256}" \
  --arg publicTreeOid "${PUBLIC_TREE_OID}" \
  --arg publicTreeInputSha256 "${PUBLIC_TREE_INPUT_SHA256}" \
  --arg publicTreeReportSha256 "${PUBLIC_TREE_REPORT_SHA256}" \
  --arg publicWorktreeInputSha256 "${PUBLIC_WORKTREE_INPUT_SHA256}" \
  --arg publicWorktreeReportSha256 "${PUBLIC_WORKTREE_REPORT_SHA256}" \
  --arg publicApkInputSha256 "${PUBLIC_APK_INPUT_SHA256}" \
  --arg publicApkReportSha256 "${PUBLIC_APK_REPORT_SHA256}" \
  --arg publicApkZipEntryManifestSha256 "${PUBLIC_APK_ZIP_ENTRY_MANIFEST_SHA256}" \
  --arg apkThirdPartyPolicySha256 "${APK_THIRD_PARTY_POLICY_SHA256}" \
  --arg apkThirdPartyProfileId "${APK_THIRD_PARTY_PROFILE_ID}" \
  --argjson apkThirdPartyMatchedEntryCount "${APK_THIRD_PARTY_MATCHED_ENTRY_COUNT}" \
  --arg androidRuntimeLockSha256 "${ANDROID_RUNTIME_LOCK_SHA256}" \
  --arg apkSourceVerifierSha256 "${APK_SOURCE_VERIFIER_SHA256}" \
  --arg apkSourceReportSha256 "${APK_SOURCE_REPORT_SHA256}" \
  --argjson apkSourceArtifactCount "${APK_SOURCE_ARTIFACT_COUNT}" \
  --argjson apkSourceEntryCount "${APK_SOURCE_ENTRY_COUNT}" \
  --argjson apkMergedSourceCount "${APK_MERGED_SOURCE_COUNT}" \
  --argjson apkCompiledOutputCount "${APK_COMPILED_OUTPUT_COUNT}" \
  --argjson apkDexSourceArtifactCount "${APK_DEX_SOURCE_ARTIFACT_COUNT}" \
  --argjson apkDexSourceEntryCount "${APK_DEX_SOURCE_ENTRY_COUNT}" \
  --argjson apkDeclaredDexStringCount "${APK_DECLARED_DEX_STRING_COUNT}" \
  --argjson apkSourceMatchedDexStringCount "${APK_SOURCE_MATCHED_DEX_STRING_COUNT}" \
  --argjson apkMatchedDexStringCount "${APK_MATCHED_DEX_STRING_COUNT}" \
  --arg publicUpdateInputSha256 "${PUBLIC_UPDATE_INPUT_SHA256}" \
  --arg publicUpdateReportSha256 "${PUBLIC_UPDATE_REPORT_SHA256}" \
  --arg publicNotesInputSha256 "${PUBLIC_NOTES_INPUT_SHA256}" \
  --arg publicNotesReportSha256 "${PUBLIC_NOTES_REPORT_SHA256}" \
  '({
    schemaVersion: (if $lineageMode == "standard-upgrade" then 2 else 4 end),
    tag: $tag,
    source: {
      commit: $sourceCommit,
      branch: $sourceBranch,
      workingTreeClean: true
    },
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
    publicAudit: {
      scannerSha256: $publicAuditScannerSha256,
      policySha256: $publicAuditPolicySha256,
      sourceTree: {
        gitTreeOid: $publicTreeOid,
        inputSha256: $publicTreeInputSha256,
        reportSha256: $publicTreeReportSha256
      },
      worktree: {
        inputSha256: $publicWorktreeInputSha256,
        reportSha256: $publicWorktreeReportSha256
      },
      apk: {
        inputSha256: $publicApkInputSha256,
        reportSha256: $publicApkReportSha256,
        zipEntryManifestSha256: $publicApkZipEntryManifestSha256
      },
      thirdPartyProvenance: {
        manifestFile: "tools/apk-third-party-components.json",
        manifestSha256: $apkThirdPartyPolicySha256,
        runtimeLockFile: "tools/android-runtime-dependencies.lock.json",
        runtimeLockSha256: $androidRuntimeLockSha256,
        profileId: $apkThirdPartyProfileId,
        matchedEntryCount: $apkThirdPartyMatchedEntryCount,
        sourceVerifierFile: "tools/verify-apk-third-party-sources.mjs",
        sourceVerifierSha256: $apkSourceVerifierSha256,
        sourceReportSha256: $apkSourceReportSha256,
        sourceArtifactCount: $apkSourceArtifactCount,
        sourceEntryCount: $apkSourceEntryCount,
        mergedSourceCount: $apkMergedSourceCount,
        compiledOutputCount: $apkCompiledOutputCount,
        dexSourceArtifactCount: $apkDexSourceArtifactCount,
        dexSourceEntryCount: $apkDexSourceEntryCount,
        declaredDexStringCount: $apkDeclaredDexStringCount,
        sourceMatchedDexStringCount: $apkSourceMatchedDexStringCount,
        apkMatchedDexStringCount: $apkMatchedDexStringCount,
        applicationDexStrict: true
      },
      releaseMetadata: {
        update: {
          inputSha256: $publicUpdateInputSha256,
          reportSha256: $publicUpdateReportSha256
        },
        notes: {
          inputSha256: $publicNotesInputSha256,
          reportSha256: $publicNotesReportSha256
        }
      }
    }
  } + (if $lineageMode == "standard-upgrade" then {
    previousApk: {
      sha256: $lineageApkSha256,
      packageName: $lineagePackageName,
      versionCode: $lineageVersionCode,
      versionName: $lineageVersionName,
      signerSha256: $lineageSignerSha256
    }
  } else ({
    publicationMode: $publicationMode,
    historicalRelease: {
      inventory: {
        fileSha256: $historicalInventoryFileSha256,
        inventorySha256: $historicalInventorySha256,
        sourceInventory: $historicalSourceInventory
      },
      original: $historicalEntry,
      publication: {
        title: $historicalTitle,
        body: $historicalBody,
        bodyPolicy: "fixed-source-bound-generic-v1",
        draft: $historicalEntry.draft,
        prerelease: $historicalEntry.prerelease,
        makeLatest: false,
        assets: ([
          {file: $apkFile, name: $historicalEntry.originalApk.assetName,
            sha256: $apkSha256, size: $apkSize},
          {file: "update.json", name: $historicalEntry.originalUpdate.assetName,
            sha256: $updateSha256, size: $updateSize}
        ] | sort_by(.name))
      }
    },
    lineage: {
      kind: (if $lineageMode == "historical-initial"
        then "historical-initial-rebuild" else "historical-upgrade-rebuild" end),
      sequence: $historicalSequence,
      originalApk: $historicalEntry.originalApk,
      previousRebuiltCandidate: (if $lineageMode == "historical-initial" then null else {
        tag: $historicalPreviousTag,
        candidateManifestSha256: $historicalPreviousManifestSha256,
        apkSha256: $historicalPreviousApkSha256,
        packageName: $historicalPreviousPackageName,
        versionCode: $historicalPreviousVersionCode,
        versionName: $historicalPreviousVersionName,
        signerSha256: $historicalPreviousSignerSha256
      } end)
    }
  }) end))' > "${CANDIDATE_MANIFEST}"
CANDIDATE_MANIFEST_SHA256="$(sha256_file "${CANDIDATE_MANIFEST}")"
if [[ "${HISTORICAL_REQUESTED}" == true ]]; then
  HISTORICAL_CONTRACT_REPORT="${TMP_DIR}/.historical-candidate-contract.json"
  assert_historical_contract_matches_source "${SOURCE_HEAD}"
  node "${HISTORICAL_RELEASE_CONTRACT}" \
    --candidate "${CANDIDATE_MANIFEST}" \
    --inventory "${HISTORICAL_INVENTORY}" \
    --inventory-file-sha256 "${HISTORICAL_INVENTORY_FILE_SHA256}" \
    > "${HISTORICAL_CONTRACT_REPORT}" 2>"${HISTORICAL_CONTRACT_REPORT}.stderr" || {
      rm -f "${HISTORICAL_CONTRACT_REPORT}.stderr"
      die "historical candidate failed its exact publication and lineage contract"
    }
  rm -f "${HISTORICAL_CONTRACT_REPORT}.stderr"
  jq -e --arg manifest "${CANDIDATE_MANIFEST_SHA256}" \
    --arg inventory "${HISTORICAL_INVENTORY_FILE_SHA256}" \
    '.candidateFileSha256 == $manifest and .inventoryFileSha256 == $inventory' \
    "${HISTORICAL_CONTRACT_REPORT}" >/dev/null 2>&1 || \
    die "historical candidate contract report is not bound to exact inputs"
fi

PUBLIC_MANIFEST_REPORT="${TMP_DIR}/.public-candidate-manifest-audit.json"
info "auditing the exact candidate manifest"
run_public_audit "${PUBLIC_MANIFEST_REPORT}" file "${CANDIDATE_MANIFEST}"
[[ "$(jq -er '.scannerSha256' "${PUBLIC_MANIFEST_REPORT}")" == "${PUBLIC_AUDIT_SCANNER_SHA256}" \
  && "$(jq -er '.policySha256' "${PUBLIC_MANIFEST_REPORT}")" == "${PUBLIC_AUDIT_POLICY_SHA256}" \
  && "$(jq -er '.input.sha256' "${PUBLIC_MANIFEST_REPORT}")" == "${CANDIDATE_MANIFEST_SHA256}" ]] || \
  die "candidate manifest audit is not bound to its exact bytes and public audit policy"
[[ "$(sha256_file "${UPDATE_JSON}")" == "${UPDATE_SHA256}" \
  && "$(sha256_file "${RELEASE_NOTES}")" == "${NOTES_SHA256}" \
  && "$(sha256_file "${CANDIDATE_MANIFEST}")" == "${CANDIDATE_MANIFEST_SHA256}" ]] || \
  die "public release metadata changed while its audits were running"

# Remove build intermediates before the whole directory is moved into place.
rm -f \
  "${ALIGNED_APK}" \
  "${PUBLIC_COMMIT_OBJECT}" \
  "${PUBLIC_COMMIT_REPORT}" \
  "${PUBLIC_TREE_REPORT}" \
  "${PUBLIC_WORKTREE_REPORT}" \
  "${PUBLIC_APK_REPORT}" \
  "${PUBLIC_UPDATE_REPORT}" \
  "${PUBLIC_NOTES_REPORT}" \
  "${PUBLIC_MANIFEST_REPORT}" \
  "${APK_SOURCE_PROVENANCE_REPORT}"
if [[ -n "${HISTORICAL_CONTRACT_REPORT:-}" ]]; then
  rm -f "${HISTORICAL_CONTRACT_REPORT}"
fi
for FINAL_CANDIDATE_FILE in \
  "${SIGNED_APK}" "${UPDATE_JSON}" "${RELEASE_NOTES}" "${CANDIDATE_MANIFEST}"; do
  [[ -f "${FINAL_CANDIDATE_FILE}" && ! -L "${FINAL_CANDIDATE_FILE}" ]] || \
    die "candidate directory is missing an expected regular file"
done
FINAL_CANDIDATE_ENTRY_COUNT="$(
  find "${TMP_DIR}" -mindepth 1 -maxdepth 1 -print | wc -l | tr -d '[:space:]'
)"
[[ "${FINAL_CANDIDATE_ENTRY_COUNT}" == "4" ]] || \
  die "candidate directory contains unexpected artifacts"
assert_clean_source "${SOURCE_HEAD}" "${SOURCE_BRANCH}"
assert_scanner_matches_source "${SOURCE_HEAD}"
assert_apk_source_verifier_matches_source "${SOURCE_HEAD}"
assert_lineage_input_unchanged
[[ "$(sha256_file "${SIGNED_APK}")" == "${APK_SHA256}" \
  && "$(sha256_file "${UPDATE_JSON}")" == "${UPDATE_SHA256}" \
  && "$(sha256_file "${RELEASE_NOTES}")" == "${NOTES_SHA256}" \
  && "$(sha256_file "${CANDIDATE_MANIFEST}")" == "${CANDIDATE_MANIFEST_SHA256}" ]] || \
  die "candidate bytes changed while finalizing the manifest"
[[ ! -e "${FINAL_DIR}" ]] || die "candidate appeared while building: ${FINAL_DIR}"
mv "${TMP_DIR}" "${FINAL_DIR}"
TMP_DIR=""
info "created ${FINAL_DIR#"${ROOT_DIR}"/}"
info "candidate APK sha256 ${APK_SHA256}"
if [[ "${LINEAGE_MODE}" == "standard-upgrade" ]]; then
  info "review candidate-manifest.json, then publish these exact bytes with tools/publish-release.sh"
else
  info "historical schema 4 candidate is only for the reviewed non-latest history-rewrite workflow"
  info "tools/publish-release.sh intentionally rejects this candidate"
fi
