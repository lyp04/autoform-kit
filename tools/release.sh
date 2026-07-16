#!/usr/bin/env bash

set -euo pipefail
umask 077

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)"
ROOT_DIR="$(cd "${SCRIPT_DIR}/.." && pwd -P)"
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

Build, align, sign, and verify an Android release, then create:
  dist/autoform-kit-<version>.apk
  dist/update.json

Options:
  --version VERSION       Version name (default: app/build.gradle)
  --version-code CODE     Positive Android version code (default: app/build.gradle)
  --config PATH           Signing JSON (default: config/signing.local.json)
  --notes TEXT            Update/release notes (default: "autoform-kit <version>")
  --notes-file PATH       Read update/release notes from a UTF-8 file
  --previous-apk PATH     Require the signer certificate to match this installed release
  --publish               Create GitHub tag/release v<version> with gh
  -h, --help              Show this help

Signing JSON keys:
  keystore, keyAlias, storePassword, keyPassword

Instead of literal passwords, storePasswordEnv and keyPasswordEnv may name
already-exported environment variables. Passwords are never passed as command
arguments or printed.

Relative config, notes, and keystore paths are resolved from the repository
root. A relative keystore path is also tried relative to the config directory.
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

repo_slug_from_origin() {
  local origin slug
  origin="$(git remote get-url origin 2>/dev/null || true)"
  origin="${origin%.git}"
  case "${origin}" in
    https://github.com/*) slug="${origin#https://github.com/}" ;;
    http://github.com/*) slug="${origin#http://github.com/}" ;;
    git@github.com:*) slug="${origin#git@github.com:}" ;;
    ssh://git@github.com/*) slug="${origin#ssh://git@github.com/}" ;;
    *) die "origin must be a github.com repository (found an unsupported URL)" ;;
  esac
  [[ "${slug}" =~ ^[^/]+/[^/]+$ ]] || die "could not determine owner/repo from origin"
  printf '%s\n' "${slug}"
}

release_exists_via_gh() {
  local repo_slug="$1"
  local tag="$2"
  local response status

  set +e
  response="$(gh api "repos/${repo_slug}/releases/tags/${tag}" 2>&1)"
  status=$?
  set -e
  if [[ ${status} -eq 0 ]]; then
    return 0
  fi
  if [[ "${response}" == *"HTTP 404"* ]]; then
    return 1
  fi
  # Authentication, network, rate-limit, and server failures are not evidence
  # that a release is absent. Fail closed rather than risking an overwrite.
  die "unable to verify existing GitHub Releases with authenticated gh"
}

release_exists_public() {
  local repo_slug="$1"
  local tag="$2"
  local repo_code release_code

  require_command curl
  repo_code="$(curl --silent --show-error --location --output /dev/null \
    --write-out '%{http_code}' "https://api.github.com/repos/${repo_slug}")" || \
    die "unable to verify GitHub repository"
  [[ "${repo_code}" == "200" ]] || \
    die "repository is not publicly queryable; authenticate gh before releasing"
  release_code="$(curl --silent --show-error --location --output /dev/null \
    --write-out '%{http_code}' "https://api.github.com/repos/${repo_slug}/releases/tags/${tag}")" || \
    die "unable to verify existing GitHub Releases"
  case "${release_code}" in
    200) return 0 ;;
    404) return 1 ;;
    *) die "unable to verify existing GitHub Release (HTTP ${release_code})" ;;
  esac
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

  if command -v gh >/dev/null 2>&1 && gh auth status --hostname github.com >/dev/null 2>&1; then
    if release_exists_via_gh "${repo_slug}" "${tag}"; then
      die "GitHub Release already exists: ${tag}"
    fi
  else
    if release_exists_public "${repo_slug}" "${tag}"; then
      die "GitHub Release already exists: ${tag}"
    fi
  fi
}

assert_publishable_head() {
  local branch head remote_head

  [[ -z "$(git status --porcelain --untracked-files=normal)" ]] || \
    die "--publish requires a clean working tree"
  branch="$(git symbolic-ref --quiet --short HEAD 2>/dev/null || true)"
  [[ -n "${branch}" ]] || die "--publish requires a branch checkout, not detached HEAD"
  [[ "${branch}" == "main" ]] || die "--publish must run from main (current branch: ${branch})"
  head="$(git rev-parse HEAD)"
  remote_head="$(git ls-remote --heads origin "refs/heads/${branch}" 2>/dev/null | awk 'NR == 1 { print $1 }')"
  [[ -n "${remote_head}" ]] || die "current branch is not present on origin: ${branch}"
  [[ "${head}" == "${remote_head}" ]] || \
    die "current HEAD is not pushed exactly to origin/${branch}"
  command -v gh >/dev/null 2>&1 || die "--publish requires gh"
  gh auth status --hostname github.com >/dev/null 2>&1 || \
    die "--publish requires an authenticated gh session"
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

signer_sha256() {
  local apk="$1"
  local digest
  digest="$("${APKSIGNER_BIN}" verify --print-certs "${apk}" 2>/dev/null \
    | awk -F': ' '/Signer #1 certificate SHA-256 digest:/ { print tolower($2); exit }')"
  [[ "${digest}" =~ ^[0-9a-f]{64}$ ]] || die "could not read signer SHA-256 from ${apk}"
  printf '%s\n' "${digest}"
}

VERSION=""
VERSION_CODE=""
CONFIG_PATH="config/signing.local.json"
NOTES=""
NOTES_FILE=""
PREVIOUS_APK=""
PUBLISH=false

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
    --publish)
      PUBLISH=true
      shift
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
[[ -x ./gradlew ]] || die "Gradle wrapper is missing or not executable"

if [[ -z "${VERSION}" ]]; then
  VERSION="$(default_gradle_value versionName)"
fi
if [[ -z "${VERSION_CODE}" ]]; then
  VERSION_CODE="$(default_gradle_value versionCode)"
fi
[[ "${VERSION}" =~ ^[0-9]+\.[0-9]+\.[0-9]+([+-][0-9A-Za-z.-]+)?$ ]] || \
  die "invalid version name: ${VERSION}"
[[ "${VERSION_CODE}" =~ ^[1-9][0-9]*$ ]] || die "version code must be a positive integer"

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
  [[ -f "${PREVIOUS_APK}" ]] || die "previous APK not found: ${PREVIOUS_APK}"
fi

# Signing configuration is deliberately validated before network checks or a
# Gradle build, so an unprepared checkout fails quickly and creates no artifact.
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

TAG="v${VERSION}"
REPO_SLUG="$(repo_slug_from_origin)"
FINAL_APK="${ROOT_DIR}/dist/autoform-kit-${VERSION}.apk"
FINAL_UPDATE="${ROOT_DIR}/dist/update.json"
[[ ! -e "${FINAL_APK}" ]] || die "local artifact already exists: ${FINAL_APK}"
[[ ! -e "${FINAL_UPDATE}" ]] || die "local artifact already exists: ${FINAL_UPDATE}"

if [[ "${PUBLISH}" == true ]]; then
  assert_publishable_head
fi
assert_tag_and_release_absent "${REPO_SLUG}" "${TAG}"

select_jdk
JAVA_BIN="${JAVA_HOME:+${JAVA_HOME}/bin/}java"
JAVA_MAJOR="$(java_major "${JAVA_BIN}")"
info "using JDK ${JAVA_MAJOR}"

ZIPALIGN_BIN="$(find_android_tool zipalign "${ZIPALIGN:-}")"
APKSIGNER_BIN="$(find_android_tool apksigner "${APKSIGNER:-}")"
AAPT_BIN="$(find_android_tool aapt "${AAPT:-}")"

mkdir -p "${ROOT_DIR}/dist"
TMP_DIR="$(mktemp -d "${ROOT_DIR}/dist/.release-${VERSION}.XXXXXX")"
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
SIGNED_APK="${TMP_DIR}/autoform-kit-${VERSION}.apk"
UPDATE_JSON="${TMP_DIR}/update.json"
RELEASE_NOTES="${TMP_DIR}/release-notes.txt"

info "building unsigned release ${VERSION} (${VERSION_CODE})"
./gradlew --no-daemon :app:assembleRelease \
  -PversionCode="${VERSION_CODE}" \
  -PversionName="${VERSION}"
[[ -f "${UNSIGNED_APK}" ]] || die "Gradle did not create ${UNSIGNED_APK}"

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
  --out "${SIGNED_APK}" \
  "${ALIGNED_APK}"
unset AUTOFORM_RELEASE_STORE_PASSWORD AUTOFORM_RELEASE_KEY_PASSWORD
STORE_PASSWORD=""
KEY_PASSWORD=""

info "verifying alignment and signature"
"${ZIPALIGN_BIN}" -c -p 4 "${SIGNED_APK}"
"${APKSIGNER_BIN}" verify --verbose --print-certs "${SIGNED_APK}"
if [[ -n "${PREVIOUS_APK}" ]]; then
  PREVIOUS_SIGNER="$(signer_sha256 "${PREVIOUS_APK}")"
  CURRENT_SIGNER="$(signer_sha256 "${SIGNED_APK}")"
  [[ "${CURRENT_SIGNER}" == "${PREVIOUS_SIGNER}" ]] || \
    die "signed APK certificate does not match the previous release"
  info "signer certificate matches the previous release"
fi

PACKAGE_LINE="$("${AAPT_BIN}" dump badging "${SIGNED_APK}" | sed -n '1p')"
ACTUAL_PACKAGE="$(printf '%s\n' "${PACKAGE_LINE}" | sed -n "s/^package: name='\([^']*\)'.*/\1/p")"
ACTUAL_CODE="$(printf '%s\n' "${PACKAGE_LINE}" | sed -n "s/.* versionCode='\([^']*\)'.*/\1/p")"
ACTUAL_VERSION="$(printf '%s\n' "${PACKAGE_LINE}" | sed -n "s/.* versionName='\([^']*\)'.*/\1/p")"
[[ "${ACTUAL_PACKAGE}" == "com.autoformkit.app" ]] || \
  die "signed APK has unexpected package name: ${ACTUAL_PACKAGE}"
[[ "${ACTUAL_CODE}" == "${VERSION_CODE}" ]] || \
  die "signed APK versionCode ${ACTUAL_CODE} does not match ${VERSION_CODE}"
[[ "${ACTUAL_VERSION}" == "${VERSION}" ]] || \
  die "signed APK versionName ${ACTUAL_VERSION} does not match ${VERSION}"

APK_SHA256="$(sha256_file "${SIGNED_APK}")"
jq -n \
  --arg packageName "com.autoformkit.app" \
  --argjson versionCode "${VERSION_CODE}" \
  --arg versionName "${VERSION}" \
  --arg apkAsset "autoform-kit-${VERSION}.apk" \
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
printf '%s\n' "${NOTES}" > "${RELEASE_NOTES}"

mv "${SIGNED_APK}" "${FINAL_APK}"
mv "${UPDATE_JSON}" "${FINAL_UPDATE}"
info "created dist/autoform-kit-${VERSION}.apk"
info "created dist/update.json (sha256 ${APK_SHA256})"

if [[ "${PUBLISH}" == true ]]; then
  # Recheck immediately before the mutating command to avoid racing another
  # publisher after the build began.
  assert_publishable_head
  assert_tag_and_release_absent "${REPO_SLUG}" "${TAG}"
  info "publishing GitHub Release ${TAG}"
  gh release create "${TAG}" \
    "${FINAL_APK}" \
    "${FINAL_UPDATE}" \
    --repo "${REPO_SLUG}" \
    --target "$(git rev-parse HEAD)" \
    --title "autoform-kit ${VERSION}" \
    --notes-file "${RELEASE_NOTES}"
fi
