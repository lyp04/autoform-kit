#!/usr/bin/env node

import { createHash } from "node:crypto";
import { execFileSync } from "node:child_process";
import { constants as fsConstants } from "node:fs";
import { lstat, open, readFile } from "node:fs/promises";
import { resolve } from "node:path";
import { fileURLToPath } from "node:url";

const SHA256 = /^[0-9a-f]{64}$/u;
const GIT_OID = /^[0-9a-f]{40}(?:[0-9a-f]{24})?$/u;
const PANEL_SOURCE_COMMIT = /^[0-9a-f]{40}$/u;
const WORKER_VERSION_ID =
  /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/u;
const UTC_TIMESTAMP =
  /^(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2}):(\d{2})(?:\.(\d{1,9}))?Z$/u;
const PANEL_RUNTIME_KEYS = [
  "provenance", "sourceCommit", "version", "versionCreatedAt", "workerVersionId"
];
const MAX_PRIVATE_FILE_BYTES = 16n * 1024n * 1024n;
const R2_POINTER_KEY = "catalog-current-v1.json";
const R2_SNAPSHOT_PREFIX = "catalog-snapshots-v1/";
const CATALOG_PATHS = ["form-profiles.json", "manifest.json", "panel-settings.json"];
const PANEL_SETTINGS_ABSENT_SHA256 = sha256(Buffer.from(
  "AUTOFORM_KIT_PANEL_SETTINGS_ABSENT_V1\n", "utf8"));
const REPOSITORY_ROOT = resolve(fileURLToPath(new URL("..", import.meta.url)));
const WRANGLER_BIN = resolve(
  REPOSITORY_ROOT, "panel/node_modules/wrangler/bin/wrangler.js");

function fail(message) {
  process.stderr.write(`verify-private-release-evidence: ${message}\n`);
  process.exit(1);
}

function isObject(value) {
  return value !== null && typeof value === "object" && !Array.isArray(value);
}

function exactKeys(value, expected) {
  return isObject(value)
    && JSON.stringify(Object.keys(value).sort()) === JSON.stringify([...expected].sort());
}

function sha256(bytes) {
  return createHash("sha256").update(bytes).digest("hex");
}

function positiveInteger(value) {
  return Number.isSafeInteger(value) && value > 0;
}

function validGitHubBranch(value) {
  return typeof value === "string"
    && value.length > 0
    && value.length <= 255
    && /^[A-Za-z0-9](?:[A-Za-z0-9._/-]*[A-Za-z0-9])?$/u.test(value)
    && !value.includes("..")
    && !value.includes("//")
    && !value.includes("@{")
    && !value.split("/").some((part) => part === "" || part.startsWith(".")
      || part.endsWith(".lock"));
}

function validUtcTimestamp(value) {
  if (typeof value !== "string") return false;
  const match = value.match(UTC_TIMESTAMP);
  if (!match) return false;
  const [, year, month, day, hour, minute, second] = match;
  const date = new Date(`${year}-${month}-${day}T${hour}:${minute}:${second}Z`);
  return Number.isFinite(date.getTime())
    && date.getUTCFullYear() === Number(year)
    && date.getUTCMonth() + 1 === Number(month)
    && date.getUTCDate() === Number(day)
    && date.getUTCHours() === Number(hour)
    && date.getUTCMinutes() === Number(minute)
    && date.getUTCSeconds() === Number(second);
}

export function validatePanelRuntimeContract(value) {
  if (!exactKeys(value, PANEL_RUNTIME_KEYS)
      || value.version !== 1
      || value.provenance !== "cloudflare_version_tag"
      || !PANEL_SOURCE_COMMIT.test(String(value.sourceCommit || ""))
      || !WORKER_VERSION_ID.test(String(value.workerVersionId || ""))
      || !validUtcTimestamp(value.versionCreatedAt)) {
    throw new Error("live Panel runtime provenance is unavailable or malformed");
  }
  return { ...value };
}

function parseArguments(argv) {
  const allowed = new Set([
    "migration-report", "panel-config", "panel-catalog", "deployment-evidence",
    "source-commit", "candidate-manifest-sha256", "apk-sha256",
    "previous-apk-sha256", "private-gate-sha256", "public-repository",
    "public-history-remote-refs-input-sha256",
    "public-history-ref-api-input-sha256",
    "public-history-ref-api-snapshot-sha256",
    "public-history-remote-refs-raw-snapshot-sha256",
    "public-history-ref-identity-sha256",
    "public-history-pull-ref-identity-sha256",
    "public-history-release-input-sha256",
    "public-history-release-api-snapshot-sha256",
    "public-history-repository-binding-sha256",
    "public-history-metadata-binding-sha256"
  ]);
  const values = {};
  for (let index = 0; index < argv.length; index += 2) {
    const option = argv[index];
    const value = argv[index + 1];
    if (!option?.startsWith("--") || value === undefined) fail("invalid arguments");
    const name = option.slice(2);
    if (!allowed.has(name) || Object.hasOwn(values, name)) fail("invalid arguments");
    values[name] = value;
  }
  if (Object.keys(values).length !== allowed.size) fail("missing required evidence binding");
  return values;
}

function exactMode(stat, expected) {
  return Number(stat.mode & 0o7777n) === expected;
}

function stableFileSnapshot(before, after) {
  return before.dev === after.dev
    && before.ino === after.ino
    && before.mode === after.mode
    && before.nlink === after.nlink
    && before.size === after.size
    && before.mtimeNs === after.mtimeNs
    && before.ctimeNs === after.ctimeNs;
}

function privateFileStatIsValid(stat) {
  return stat.isFile()
    && !stat.isSymbolicLink()
    && exactMode(stat, 0o600)
    && stat.size > 0n
    && stat.size <= MAX_PRIVATE_FILE_BYTES;
}

// Read one immutable descriptor-bound snapshot. The optional hook exists only for the
// self-test so it can deterministically replace or mutate the pathname after open.
export async function readPrivateFileSnapshot(path, label, { afterOpen } = {}) {
  const target = resolve(path);
  let handle;
  try {
    const before = await lstat(target, { bigint: true });
    if (!privateFileStatIsValid(before)) throw new Error("invalid file");
    handle = await open(target, fsConstants.O_RDONLY | fsConstants.O_NOFOLLOW);
    const opened = await handle.stat({ bigint: true });
    if (!privateFileStatIsValid(opened) || !stableFileSnapshot(before, opened)) {
      throw new Error("file changed before open");
    }
    if (afterOpen) await afterOpen();
    const bytes = await handle.readFile();
    const afterRead = await handle.stat({ bigint: true });
    const afterPath = await lstat(target, { bigint: true });
    if (!privateFileStatIsValid(afterRead)
        || !privateFileStatIsValid(afterPath)
        || !stableFileSnapshot(opened, afterRead)
        || !stableFileSnapshot(opened, afterPath)
        || BigInt(bytes.length) !== opened.size) {
      throw new Error("file changed during read");
    }
    return { bytes, sha256: sha256(bytes) };
  } catch {
    throw new Error(`${label} must be a stable bounded regular file with mode 0600`);
  } finally {
    await handle?.close().catch(() => {});
  }
}

async function readPrivateFile(path, label) {
  try {
    return await readPrivateFileSnapshot(path, label);
  } catch (error) {
    fail(error.message);
  }
}

function parseJson(bytes, label) {
  try {
    const value = JSON.parse(bytes.toString("utf8"));
    if (!isObject(value)) fail(`${label} must contain one JSON object`);
    return value;
  } catch {
    fail(`${label} is not valid JSON`);
  }
}

function strictJsonObject(bytes, label) {
  try {
    if (!Buffer.isBuffer(bytes) || bytes.length === 0
        || BigInt(bytes.length) > MAX_PRIVATE_FILE_BYTES) {
      throw new Error("invalid length");
    }
    const text = new TextDecoder("utf-8", { fatal: true }).decode(bytes);
    const value = JSON.parse(text);
    if (!isObject(value)) throw new Error("not an object");
    return value;
  } catch {
    throw new Error(`${label} is not a bounded UTF-8 JSON object`);
  }
}

export function parseDeploymentAuthority(deployment) {
  if (exactKeys(deployment, [
    "schemaVersion", "catalogRepository", "catalogCommit", "panelBase", "catalogReadKey"
  ]) && deployment.schemaVersion === 1) {
    if (!/^[A-Za-z0-9_.-]+\/[A-Za-z0-9_.-]+$/u.test(deployment.catalogRepository)
        || !GIT_OID.test(deployment.catalogCommit)) {
      throw new Error("GitHub catalog authority is invalid");
    }
    return {
      schemaVersion: 1,
      legacyAuthorityInput: true,
      panelBase: deployment.panelBase,
      catalogReadKey: deployment.catalogReadKey,
      authority: {
        type: "github",
        repository: deployment.catalogRepository,
        commit: deployment.catalogCommit
      }
    };
  }
  if (!exactKeys(deployment, [
    "schemaVersion", "catalogAuthority", "panelBase", "catalogReadKey"
  ]) || deployment.schemaVersion !== 2 || !isObject(deployment.catalogAuthority)) {
    throw new Error("deployment evidence is not schema v1 or v2");
  }
  const authority = deployment.catalogAuthority;
  if (authority.type === "github") {
    if (!exactKeys(authority, [
      "type", "accountId", "workerName", "repository", "branch", "commit"
    ])
        || !/^[0-9a-f]{32}$/u.test(authority.accountId)
        || !/^[A-Za-z0-9_-]{1,63}$/u.test(authority.workerName)
        || !/^[A-Za-z0-9_.-]+\/[A-Za-z0-9_.-]+$/u.test(authority.repository)
        || !validGitHubBranch(authority.branch)
        || !GIT_OID.test(authority.commit)) {
      throw new Error("GitHub catalog authority is invalid");
    }
    return {
      schemaVersion: 2,
      legacyAuthorityInput: false,
      panelBase: deployment.panelBase,
      catalogReadKey: deployment.catalogReadKey,
      authority
    };
  }
  if (authority.type === "r2") {
    if (!exactKeys(authority, [
      "type", "accountId", "workerName", "bucket", "jurisdiction"
    ])
        || !/^[0-9a-f]{32}$/u.test(authority.accountId)
        || !/^[A-Za-z0-9_-]{1,63}$/u.test(authority.workerName)
        || !/^[a-z0-9](?:[a-z0-9-]{1,61}[a-z0-9])$/u.test(authority.bucket)
        || !(authority.jurisdiction === null
          || authority.jurisdiction === "eu"
          || authority.jurisdiction === "fedramp")) {
      throw new Error("R2 catalog authority is invalid");
    }
    return {
      schemaVersion: 2,
      legacyAuthorityInput: false,
      panelBase: deployment.panelBase,
      catalogReadKey: deployment.catalogReadKey,
      authority
    };
  }
  throw new Error("catalog authority type is unsupported");
}

export function parseR2AuthoritySnapshot(pointerBytes, stateBytes) {
  const pointer = strictJsonObject(pointerBytes, "R2 current pointer");
  if (!exactKeys(pointer, ["schemaVersion", "snapshotKey", "stateSha256", "catalogVersion"])
      || pointer.schemaVersion !== 1
      || !SHA256.test(String(pointer.stateSha256 || ""))
      || pointer.snapshotKey !== `${R2_SNAPSHOT_PREFIX}${pointer.stateSha256}.json`
      || !positiveInteger(pointer.catalogVersion)) {
    throw new Error("R2 current pointer contract is invalid");
  }
  if (sha256(stateBytes) !== pointer.stateSha256) {
    throw new Error("R2 snapshot bytes do not match the current pointer");
  }
  const state = strictJsonObject(stateBytes, "R2 catalog snapshot");
  if (!exactKeys(state, ["schemaVersion", "parentStateSha256", "files"])
      || state.schemaVersion !== 1
      || !(state.parentStateSha256 === null || SHA256.test(state.parentStateSha256))
      || !exactKeys(state.files, CATALOG_PATHS)
      || typeof state.files["form-profiles.json"] !== "string"
      || typeof state.files["manifest.json"] !== "string"
      || !(state.files["panel-settings.json"] === null
        || typeof state.files["panel-settings.json"] === "string")) {
    throw new Error("R2 catalog snapshot contract is invalid");
  }
  const catalogBytes = Buffer.from(state.files["form-profiles.json"], "utf8");
  const manifestBytes = Buffer.from(state.files["manifest.json"], "utf8");
  const panelSettingsBytes = state.files["panel-settings.json"] === null
    ? null
    : Buffer.from(state.files["panel-settings.json"], "utf8");
  const catalog = strictJsonObject(catalogBytes, "R2 catalog file");
  const manifest = strictJsonObject(manifestBytes, "R2 catalog manifest");
  if (catalog.version !== pointer.catalogVersion
      || manifest.version !== pointer.catalogVersion
      || manifest.sha256 !== sha256(catalogBytes)) {
    throw new Error("R2 catalog files do not match the current pointer");
  }
  if (panelSettingsBytes !== null) {
    strictJsonObject(panelSettingsBytes, "R2 Panel settings");
  }
  return {
    pointer,
    parentStateSha256: state.parentStateSha256,
    catalogBytes,
    manifestBytes,
    panelSettingsBytes
  };
}

export function parseGitHubCatalogTree(treeBytes) {
  const value = strictJsonObject(treeBytes, "private GitHub catalog tree");
  if (value.truncated !== false || !Array.isArray(value.tree)) {
    throw new Error("private GitHub catalog tree response is invalid");
  }
  const entries = new Map();
  for (const entry of value.tree) {
    if (!isObject(entry) || typeof entry.path !== "string") continue;
    if (CATALOG_PATHS.includes(entry.path)) {
      if (entries.has(entry.path) || entry.type !== "blob"
          || !GIT_OID.test(String(entry.sha || ""))) {
        throw new Error("private GitHub catalog tree response is invalid");
      }
      entries.set(entry.path, entry.sha);
    }
  }
  if (!entries.has("form-profiles.json") || !entries.has("manifest.json")) {
    throw new Error("private GitHub catalog tree is missing required files");
  }
  return {
    catalogBlobOid: entries.get("form-profiles.json"),
    manifestBlobOid: entries.get("manifest.json"),
    panelSettingsPresent: entries.has("panel-settings.json"),
    panelSettingsBlobOid: entries.get("panel-settings.json") || ""
  };
}

function panelSettingsBinding(bytes) {
  return bytes === null
    ? { present: false, sha256: PANEL_SETTINGS_ABSENT_SHA256 }
    : { present: true, sha256: sha256(bytes) };
}

function runBytes(command, commandArgs, label, {
  env = process.env,
  cwd = REPOSITORY_ROOT,
  timeout = 30_000
} = {}) {
  try {
    return execFileSync(command, commandArgs, {
      cwd,
      encoding: null,
      env,
      maxBuffer: 32 * 1024 * 1024,
      timeout,
      stdio: ["ignore", "pipe", "pipe"]
    });
  } catch {
    fail(`${label} live verification failed`);
  }
}

function r2CommandEnvironment(accountId) {
  const environment = {
    CI: "true",
    NO_COLOR: "1",
    FORCE_COLOR: "0",
    WRANGLER_SEND_METRICS: "false",
    CLOUDFLARE_ACCOUNT_ID: accountId
  };
  for (const name of [
    "HOME", "USERPROFILE", "XDG_CONFIG_HOME", "WRANGLER_HOME",
    "CLOUDFLARE_API_TOKEN", "CLOUDFLARE_API_KEY", "CLOUDFLARE_EMAIL",
    "HTTPS_PROXY", "HTTP_PROXY", "NO_PROXY", "SSL_CERT_FILE", "SSL_CERT_DIR"
  ]) {
    if (typeof process.env[name] === "string" && process.env[name] !== "") {
      environment[name] = process.env[name];
    }
  }
  return environment;
}

function r2JurisdictionArguments(authority) {
  return authority.jurisdiction === null
    ? []
    : ["--jurisdiction", authority.jurisdiction];
}

function runWrangler(authority, args, label) {
  return runBytes(process.execPath, [WRANGLER_BIN, ...args], label, {
    env: r2CommandEnvironment(authority.accountId)
  });
}

export function parseWorkerAuthorityBinding(
  authority, workerVersionId, versionBytes, defaultBranch = "") {
  if (!WORKER_VERSION_ID.test(workerVersionId)) {
    throw new Error("Panel Worker version identity is invalid");
  }
  const version = strictJsonObject(versionBytes, "Panel Worker version");
  const bindings = version.resources?.bindings;
  if (version.id !== workerVersionId || !Array.isArray(bindings)) {
    throw new Error("Panel Worker version response is invalid");
  }
  let expected;
  let matches;
  if (authority.type === "r2") {
    expected = {
      type: "r2_bucket",
      name: "CATALOG_R2",
      bucketName: authority.bucket,
      jurisdiction: authority.jurisdiction
    };
    matches = bindings.filter((binding) => isObject(binding)
      && binding.name === "CATALOG_R2");
    if (matches.length !== 1
        || matches[0].type !== "r2_bucket"
        || matches[0].bucket_name !== authority.bucket
        || (matches[0].jurisdiction ?? null) !== authority.jurisdiction) {
      throw new Error("live Panel Worker is not bound to the declared R2 authority");
    }
  } else if (authority.type === "github") {
    expected = {
      type: "plain_text",
      name: "GITHUB_REPO",
      repository: authority.repository,
      branch: authority.branch,
      branchSource: ""
    };
    matches = bindings.filter((binding) => isObject(binding)
      && binding.name === "GITHUB_REPO");
    const r2Bindings = bindings.filter((binding) => isObject(binding)
      && binding.name === "CATALOG_R2");
    const branchBindings = bindings.filter((binding) => isObject(binding)
      && binding.name === "GITHUB_BRANCH");
    if (matches.length !== 1
        || matches[0].type !== "plain_text"
        || matches[0].text !== authority.repository
        || r2Bindings.length !== 0
        || branchBindings.length > 1) {
      throw new Error("live Panel Worker is not bound to the declared GitHub authority");
    }
    if (branchBindings.length === 1) {
      if (branchBindings[0].type !== "plain_text"
          || branchBindings[0].text !== authority.branch) {
        throw new Error("live Panel Worker is not bound to the declared GitHub authority");
      }
      expected.branchSource = "worker_binding";
    } else {
      if (defaultBranch !== authority.branch) {
        throw new Error("live Panel Worker is not bound to the declared GitHub authority");
      }
      expected.branchSource = "repository_default";
    }
  } else {
    throw new Error("catalog authority type is unsupported");
  }
  return sha256(Buffer.from(JSON.stringify({
    accountId: authority.accountId,
    workerName: authority.workerName,
    workerVersionId,
    binding: expected
  }), "utf8"));
}

function readWorkerAuthorityBinding(
  authority, workerVersionId, runner, defaultBranch = "") {
  const versionBytes = runner([
    "versions", "view", workerVersionId,
    "--name", authority.workerName, "--json"
  ], "live Panel Worker authority binding");
  return parseWorkerAuthorityBinding(
    authority, workerVersionId, versionBytes, defaultBranch);
}

async function wranglerToolchainSha256() {
  const installedPackagePath = resolve(
    REPOSITORY_ROOT, "panel/node_modules/wrangler/package.json");
  const packageLockPath = resolve(REPOSITORY_ROOT, "panel/package-lock.json");
  try {
    const [wranglerStat, installedBytes, lockBytes] = await Promise.all([
      lstat(WRANGLER_BIN),
      readFile(installedPackagePath),
      readFile(packageLockPath)
    ]);
    if (!wranglerStat.isFile() || wranglerStat.isSymbolicLink()) {
      throw new Error("invalid binary");
    }
    const installed = strictJsonObject(installedBytes, "installed Wrangler package");
    const lock = strictJsonObject(lockBytes, "Panel dependency lock");
    const locked = lock.packages?.["node_modules/wrangler"];
    if (installed.name !== "wrangler"
        || typeof installed.version !== "string"
        || !isObject(locked)
        || locked.version !== installed.version
        || typeof locked.integrity !== "string"
        || !locked.integrity.startsWith("sha512-")
        || installed.bin?.wrangler !== "bin/wrangler.js") {
      throw new Error("version mismatch");
    }
    const reported = runBytes(process.execPath, [WRANGLER_BIN, "--version"],
      "installed Wrangler version").toString("utf8").trim();
    if (reported !== installed.version) throw new Error("version mismatch");
    return sha256(Buffer.from(JSON.stringify({
      version: installed.version,
      integrity: locked.integrity,
      packageLockSha256: sha256(lockBytes)
    }), "utf8"));
  } catch {
    fail("Panel Wrangler installation does not match panel/package-lock.json; run npm ci in panel");
  }
}

function bindWorkerToolchain(bindingSha256, toolchainSha256) {
  return sha256(Buffer.from([
    "AUTOFORM_KIT_PANEL_AUTHORITY_BINDING_V1",
    bindingSha256,
    toolchainSha256
  ].join("\n"), "utf8"));
}

function verifyR2PrivateSurface(authority, runner, suffix = "") {
  const bucketInfoBytes = runner([
    "r2", "bucket", "info", authority.bucket, "--json",
    ...r2JurisdictionArguments(authority)
  ], `private R2 bucket identity${suffix}`);
  let bucketInfo;
  try {
    bucketInfo = JSON.parse(bucketInfoBytes.toString("utf8"));
  } catch {
    throw new Error("private R2 bucket identity response is invalid");
  }
  if (!isObject(bucketInfo) || bucketInfo.name !== authority.bucket) {
    throw new Error("private R2 bucket identity response is invalid");
  }
  const devUrl = runner([
    "r2", "bucket", "dev-url", "get", authority.bucket,
    ...r2JurisdictionArguments(authority)
  ], `private R2 managed-domain policy${suffix}`).toString("utf8");
  const domains = runner([
    "r2", "bucket", "domain", "list", authority.bucket,
    ...r2JurisdictionArguments(authority)
  ], `private R2 custom-domain policy${suffix}`).toString("utf8");
  if (!devUrl.includes("Public access via the r2.dev URL is disabled.")
      || devUrl.includes("Public access is enabled")
      || !domains.includes("There are no custom domains connected to this bucket.")) {
    throw new Error("catalog R2 bucket must not expose a public dev URL or custom domain");
  }
}

export async function verifyR2AuthorityWithRunner(
  authority, workerVersionId, runner) {
  const workerBindingSha256 = readWorkerAuthorityBinding(
    authority, workerVersionId, runner);
  verifyR2PrivateSurface(authority, runner);
  const pointerBytes = runner([
    "r2", "object", "get", `${authority.bucket}/${R2_POINTER_KEY}`,
    "--remote", "--pipe", ...r2JurisdictionArguments(authority)
  ], "private R2 current pointer");
  let pointer;
  try {
    pointer = strictJsonObject(pointerBytes, "R2 current pointer");
  } catch (error) {
    throw new Error(error.message);
  }
  if (!SHA256.test(String(pointer.stateSha256 || ""))) {
    throw new Error("R2 current pointer contract is invalid");
  }
  const stateBytes = runner([
    "r2", "object", "get",
    `${authority.bucket}/${R2_SNAPSHOT_PREFIX}${pointer.stateSha256}.json`,
    "--remote", "--pipe", ...r2JurisdictionArguments(authority)
  ], "private R2 immutable snapshot");
  let snapshot;
  try {
    snapshot = parseR2AuthoritySnapshot(pointerBytes, stateBytes);
  } catch (error) {
    throw new Error(error.message);
  }
  return {
    ...snapshot,
    workerBindingSha256,
    authorityIdentitySha256: sha256(Buffer.from([
      "AUTOFORM_KIT_CATALOG_AUTHORITY_V1",
      "r2",
      authority.accountId,
      authority.bucket,
      authority.jurisdiction ?? ""
    ].join("\n"), "utf8")),
    authorityRevision: snapshot.pointer.stateSha256,
    async assertStillCurrent() {
      const workerBindingAfter = readWorkerAuthorityBinding(
        authority, workerVersionId, runner);
      verifyR2PrivateSurface(authority, runner, " recheck");
      const after = runner([
        "r2", "object", "get", `${authority.bucket}/${R2_POINTER_KEY}`,
        "--remote", "--pipe", ...r2JurisdictionArguments(authority)
      ], "private R2 current pointer recheck");
      if (workerBindingAfter !== workerBindingSha256 || !after.equals(pointerBytes)) {
        throw new Error("private R2 current pointer changed during verification");
      }
    }
  };
}

async function liveR2Authority(authority, workerVersionId, toolchainSha256) {
  try {
    const verified = await verifyR2AuthorityWithRunner(
      authority,
      workerVersionId,
      (args, label) => runWrangler(authority, args, label)
    );
    return {
      ...verified,
      workerBindingSha256: bindWorkerToolchain(
        verified.workerBindingSha256, toolchainSha256)
    };
  } catch (error) {
    fail(error.message);
  }
}

function liveGitHubWorkerBinding(
  authority, workerVersionId, toolchainSha256, defaultBranch) {
  const binding = readWorkerAuthorityBinding(
    authority,
    workerVersionId,
    (args, label) => runWrangler(authority, args, label),
    defaultBranch
  );
  return bindWorkerToolchain(binding, toolchainSha256);
}

function migrationReportPass(report) {
  const zeroFields = [
    "missingPathCount",
    "unresolvedPathCount",
    "validationErrorCount",
    "candidateAdapterProblemCount",
    "previousStepLookupReplayProblemCount",
    "previousStepReadonlyProvenanceProblemCount",
    "publicWorktreeAuditProblemCount",
    "publicHistoryReleaseAuditProblemCount",
    "submissionRetryAuditProblemCount",
    "candidateApkAuditProblemCount",
    "upgradePersistenceAuditProblemCount",
    "runtimeFlowParityProblemCount"
  ];
  return report.releaseReady === true
    && report.structuralValidationPassed === true
    && report.candidateApkReleaseContextRequested === true
    && report.candidateApkReleaseContextInputComplete === true
    && report.candidateApkReleaseContextBindingProven === true
    && report.candidateApkCurrentSourceBindingProven === true
    && report.previousStepReadonlyProvenanceDirectGatePass === true
    && report.previousStepReadonlyProvenanceStoredReportByteMatch === true
    && report.previousStepLookupReplayStoredReportByteMatch === true
    && report.candidateApkSignedDeviceMatrixRequested === true
    && report.candidateApkSignedDeviceMatrixInputComplete === true
    && report.candidateApkSignedDeviceMatrixVerified === true
    && report.candidateApkSignedDeviceMatrixProblemCount === 0
    && report.candidateApkSignedDeviceCandidateBindingPass === true
    && report.candidateApkSignedDevicePanelPairBindingPass === true
    && report.candidateApkSignedInstallUpgradeEvidenceCount === 2
    && report.candidateApkSignedAppInternalUpdateEvidenceCount === 2
    && report.candidateApkSignedRestoredDraftReplayEvidenceCount === 4
    && report.candidateApkSignedProcessRestartEvidenceCount === 2
    && report.candidateApkSignedCleanupEvidenceCount === 2
    && report.candidateApkSignedRollbackDraftReplayEvidenceCount === 0
    && Number.isSafeInteger(report.candidateApkSignedFaultScenarioEvidenceCount)
    && report.candidateApkSignedFaultScenarioEvidenceCount >= 5
    && report.upgradePersistenceSignedDeviceMatrixRequested === true
    && report.upgradePersistenceSignedDeviceMatrixInputComplete === true
    && report.upgradePersistenceSignedDeviceMatrixVerified === true
    && report.upgradePersistenceSignedDeviceMatrixProblemCount === 0
    && report.upgradePersistenceSignedDeviceCandidateBindingPass === true
    && report.upgradePersistenceSignedDevicePanelPairBindingPass === true
    && report.upgradePersistenceLegacyPanelPrewarmOldAppEvidenceCount === 2
    && report.upgradePersistencePrewarmedOfflineUpgradeEvidenceCount === 2
    && report.upgradePersistenceSignedUpgradeReplayEvidenceCount === 2
    && report.upgradePersistenceRestoredDraftReplayEvidenceCount === 4
    && report.upgradePersistenceSignedProcessRestartEvidenceCount === 2
    && report.upgradePersistenceSignedCleanupEvidenceCount === 2
    && positiveInteger(report.upgradePersistenceRollbackDraftReplayEvidenceCount)
    && positiveInteger(
      report.upgradePersistenceSignedRollbackJournalWorkerPreflightEvidenceCount)
    && [
      "candidateApkSignedDeviceMatrixDigestSha256",
      "candidateApkSignedDeviceMatrixVerifierSha256",
      "candidateApkSignedDeviceRecorderSha256",
      "candidateApkSignedDeviceV104ReportSha256",
      "candidateApkSignedDeviceV106ReportSha256",
      "candidateApkSignedDeviceCandidateManifestSha256",
      "candidateApkSignedDeviceCandidateApkSha256",
      "candidateApkSignedDevicePanelPairSha256",
      "candidateApkSignedDevicePanelCatalogSha256"
    ].every((field) => SHA256.test(String(report[field] || "")))
    && GIT_OID.test(String(report.candidateApkSignedDeviceSourceCommit || ""))
    && report.upgradePersistenceSignedDeviceMatrixDigestSha256
      === report.candidateApkSignedDeviceMatrixDigestSha256
    && report.upgradePersistenceSignedDeviceMatrixVerifierSha256
      === report.candidateApkSignedDeviceMatrixVerifierSha256
    && report.upgradePersistenceSignedDeviceRecorderSha256
      === report.candidateApkSignedDeviceRecorderSha256
    && report.upgradePersistenceSignedDeviceV104ReportSha256
      === report.candidateApkSignedDeviceV104ReportSha256
    && report.upgradePersistenceSignedDeviceV106ReportSha256
      === report.candidateApkSignedDeviceV106ReportSha256
    && report.upgradePersistenceSignedDeviceSourceCommit
      === report.candidateApkSignedDeviceSourceCommit
    && report.upgradePersistenceSignedDeviceCandidateManifestSha256
      === report.candidateApkSignedDeviceCandidateManifestSha256
    && report.upgradePersistenceSignedDeviceCandidateApkSha256
      === report.candidateApkSignedDeviceCandidateApkSha256
    && report.upgradePersistenceSignedDevicePanelPairSha256
      === report.candidateApkSignedDevicePanelPairSha256
    && report.upgradePersistenceSignedDevicePanelCatalogSha256
      === report.candidateApkSignedDevicePanelCatalogSha256
    && report.runtimeFlowParityPickerSignedPanelFirstPrewarmEvidenceCount === 2
    && report.runtimeFlowParityPickerSignedDeviceMatrixVerified === true
    && report.runtimeFlowParityPickerSignedDeviceMatrixBaselinePassCount === 2
    && report.runtimeFlowParityPickerSignedDeviceCandidateBindingPass === true
    && report.runtimeFlowParityPickerSignedDevicePanelPairBindingPass === true
    && report.runtimeFlowParityPickerSignedDeviceMatrixDigestSha256
      === report.candidateApkSignedDeviceMatrixDigestSha256
    && report.runtimeFlowParityPickerSignedDeviceMatrixVerifierSha256
      === report.candidateApkSignedDeviceMatrixVerifierSha256
    && report.runtimeFlowParityPickerSignedDeviceRecorderSha256
      === report.candidateApkSignedDeviceRecorderSha256
    && report.runtimeFlowParityPickerSignedDeviceV104ReportSha256
      === report.candidateApkSignedDeviceV104ReportSha256
    && report.runtimeFlowParityPickerSignedDeviceV106ReportSha256
      === report.candidateApkSignedDeviceV106ReportSha256
    && report.runtimeFlowParityPickerSignedDeviceSourceCommit
      === report.candidateApkSignedDeviceSourceCommit
    && report.runtimeFlowParityPickerSignedDeviceCandidateManifestSha256
      === report.candidateApkSignedDeviceCandidateManifestSha256
    && report.runtimeFlowParityPickerSignedDeviceCandidateApkSha256
      === report.candidateApkSignedDeviceCandidateApkSha256
    && report.runtimeFlowParityPickerSignedDevicePanelPairSha256
      === report.candidateApkSignedDevicePanelPairSha256
    && report.runtimeFlowParityPickerSignedDevicePanelCatalogSha256
      === report.candidateApkSignedDevicePanelCatalogSha256
    && [
      "publicHistoryRemoteRefsInputSha256",
      "publicHistoryRefApiInputSha256",
      "publicHistoryRefApiSnapshotSha256",
      "publicHistoryRemoteRefsRawSnapshotSha256",
      "publicHistoryRefIdentitySha256",
      "publicHistoryPullRefIdentitySha256",
      "publicHistoryReleaseApiSnapshotSha256",
      "publicHistoryReleaseInputSha256",
      "publicHistoryRepositoryBindingSha256",
      "publicHistoryMetadataBindingSha256",
      "publicHistoryReachableObjectClosureSha256",
      "publicHistoryAuditReportSha256"
    ].every((field) => SHA256.test(String(report[field] || "")))
    && positiveInteger(report.publicHistoryReachableObjectCount)
    && report.publicHistoryReachableObjectCount
      === report.publicHistoryReleaseAuditReachableObjectCount
    && zeroFields.every((field) => report[field] === 0);
}

/**
 * A narrowly scoped maintenance release may use the exact currently published APK as its only
 * signed upgrade predecessor.  This evidence contract is intentionally much smaller than the
 * historical migration contract above, but it is not a boolean escape hatch: every public and
 * private byte binding is supplied by the publisher/verifier at run time, and every named check
 * must be present as an exact boolean true.  The separately pinned private gate additionally
 * restricts which concrete version pair may use this mode.
 */
export function currentUpgradeReportPass(report, expected) {
  const checkKeys = [
    "automaticUpdateProtocolVerified",
    "currentPanelCatalogSmokeVerified",
    "freshInstallVerified",
    "legacyMatrixWaiverApproved",
    "productionMutationAvoided",
    "signedCurrentUpgradeVerified"
  ];
  return exactKeys(report, [
    "bindings", "checks", "kind", "releaseReady", "schemaVersion"
  ])
    && report.schemaVersion === 1
    && report.kind === "autoform-current-upgrade-release-evidence-v1"
    && report.releaseReady === true
    && exactKeys(report.bindings, [
      "apkSha256", "candidateManifestSha256", "catalogVersion",
      "panelCatalogSha256", "panelConfigSha256", "previousApkSha256",
      "sourceCommit"
    ])
    && report.bindings.sourceCommit === expected.sourceCommit
    && report.bindings.candidateManifestSha256 === expected.candidateManifestSha256
    && report.bindings.apkSha256 === expected.apkSha256
    && report.bindings.previousApkSha256 === expected.previousApkSha256
    && report.bindings.panelConfigSha256 === expected.panelConfigSha256
    && report.bindings.panelCatalogSha256 === expected.panelCatalogSha256
    && report.bindings.catalogVersion === expected.catalogVersion
    && exactKeys(report.checks, checkKeys)
    && checkKeys.every((key) => report.checks[key] === true);
}

function expectedPairSha256(configSha256, catalogSha256, catalogVersion) {
  return sha256(Buffer.from([
    "AUTOFORM_KIT_PRIVATE_PANEL_PAIR_V1",
    configSha256,
    catalogSha256,
    String(catalogVersion)
  ].join("\n"), "utf8"));
}

export async function main(argv = process.argv.slice(2)) {
const args = parseArguments(argv);
if (!GIT_OID.test(args["source-commit"])) fail("source commit binding is invalid");
for (const name of [
  "candidate-manifest-sha256", "apk-sha256", "previous-apk-sha256",
  "private-gate-sha256", "public-history-remote-refs-input-sha256",
  "public-history-ref-api-input-sha256",
  "public-history-ref-api-snapshot-sha256",
  "public-history-remote-refs-raw-snapshot-sha256",
  "public-history-ref-identity-sha256",
  "public-history-pull-ref-identity-sha256",
  "public-history-release-input-sha256",
  "public-history-release-api-snapshot-sha256",
  "public-history-repository-binding-sha256",
  "public-history-metadata-binding-sha256"
]) {
  if (!SHA256.test(args[name])) fail("SHA-256 binding is invalid");
}

const [migrationFile, configFile, catalogFile, deploymentFile, verifierBytes] = await Promise.all([
  readPrivateFile(args["migration-report"], "private migration report"),
  readPrivateFile(args["panel-config"], "Panel config evidence"),
  readPrivateFile(args["panel-catalog"], "Panel catalog evidence"),
  readPrivateFile(args["deployment-evidence"], "private deployment evidence"),
  readFile(fileURLToPath(import.meta.url))
]);

const migration = parseJson(migrationFile.bytes, "private migration report");
const config = parseJson(configFile.bytes, "Panel config evidence");
const catalog = parseJson(catalogFile.bytes, "Panel catalog evidence");
const deployment = parseJson(deploymentFile.bytes, "private deployment evidence");
const historicalMigrationEvidence = migrationReportPass(migration);
const currentUpgradeEvidence = currentUpgradeReportPass(migration, {
  sourceCommit: args["source-commit"],
  candidateManifestSha256: args["candidate-manifest-sha256"],
  apkSha256: args["apk-sha256"],
  previousApkSha256: args["previous-apk-sha256"],
  panelConfigSha256: configFile.sha256,
  panelCatalogSha256: catalogFile.sha256,
  catalogVersion: catalog.version
});
if (!historicalMigrationEvidence && !currentUpgradeEvidence) {
  fail("private migration/current-upgrade report is not release-ready");
}
if (historicalMigrationEvidence && (migration.publicHistoryRemoteRefsInputSha256
      !== args["public-history-remote-refs-input-sha256"]
    || migration.publicHistoryRefApiInputSha256
      !== args["public-history-ref-api-input-sha256"]
    || migration.publicHistoryRefApiSnapshotSha256
      !== args["public-history-ref-api-snapshot-sha256"]
    || migration.publicHistoryRemoteRefsRawSnapshotSha256
      !== args["public-history-remote-refs-raw-snapshot-sha256"]
    || migration.publicHistoryRefIdentitySha256
      !== args["public-history-ref-identity-sha256"]
    || migration.publicHistoryPullRefIdentitySha256
      !== args["public-history-pull-ref-identity-sha256"]
    || migration.publicHistoryReleaseApiSnapshotSha256
      !== args["public-history-release-api-snapshot-sha256"]
    || migration.publicHistoryReleaseInputSha256
      !== args["public-history-release-input-sha256"]
    || migration.publicHistoryRepositoryBindingSha256
      !== args["public-history-repository-binding-sha256"]
    || migration.publicHistoryMetadataBindingSha256
      !== args["public-history-metadata-binding-sha256"])) {
  fail("private full-history audit does not match the publisher metadata capture");
}

const proof = config._autoFormKitLegacyCacheProof;
if (!exactKeys(proof, ["version", "panelBase", "keySha256", "catalogSha256", "catalogVersion"])
    || proof.version !== 1
    || typeof proof.panelBase !== "string" || proof.panelBase.length === 0
    || !SHA256.test(proof.keySha256)
    || proof.catalogSha256 !== catalogFile.sha256
    || !positiveInteger(proof.catalogVersion)
    || catalog.version !== proof.catalogVersion) {
  fail("Panel config/catalog pair proof is invalid");
}
const pairSha256 = expectedPairSha256(
  configFile.sha256, catalogFile.sha256, proof.catalogVersion);
if (migration.candidateApkSignedDeviceSourceCommit !== args["source-commit"]
    || migration.candidateApkSignedDeviceCandidateManifestSha256
      !== args["candidate-manifest-sha256"]
    || migration.candidateApkSignedDeviceCandidateApkSha256 !== args["apk-sha256"]
    || migration.candidateApkSignedDevicePanelPairSha256 !== pairSha256
    || migration.candidateApkSignedDevicePanelCatalogSha256 !== catalogFile.sha256) {
  fail("signed-device matrix does not match the final source/candidate/Panel pair");
}

let deploymentInput;
try {
  deploymentInput = parseDeploymentAuthority(deployment);
} catch {
  fail("private deployment input structure is invalid");
}
if (deploymentInput.legacyAuthorityInput) {
  fail("private deployment input must use schema v2 with a live Worker authority binding");
}
if (typeof deploymentInput.panelBase !== "string"
    || typeof deploymentInput.catalogReadKey !== "string"
    || deploymentInput.catalogReadKey.length < 32) {
  fail("private deployment input structure is invalid");
}
let panelUrl;
try {
  panelUrl = new URL(deploymentInput.panelBase);
} catch {
  fail("private deployment Panel URL is invalid");
}
if (panelUrl.protocol !== "https:" || panelUrl.username || panelUrl.password
    || panelUrl.search || panelUrl.hash || panelUrl.pathname !== "/"
    || panelUrl.origin !== proof.panelBase
    || sha256(Buffer.from(deploymentInput.catalogReadKey, "utf8")) !== proof.keySha256
    || (deploymentInput.authority.type === "github"
      && deploymentInput.authority.repository === args["public-repository"])) {
  fail("private deployment input does not match the Panel pair");
}

function liveRepository(path, label) {
  const bytes = runBytes("gh", ["api", `repos/${path}`], label);
  const value = parseJson(bytes, label);
  if (typeof value.node_id !== "string" || value.node_id.length === 0
      || typeof value.full_name !== "string" || value.full_name.length === 0
      || typeof value.private !== "boolean") fail(`${label} response is invalid`);
  return value;
}

function liveGitHubBranchHead(authority, label) {
  const encodedBranch = authority.branch.split("/")
    .map((part) => encodeURIComponent(part)).join("/");
  const bytes = runBytes("gh", ["api",
    `repos/${authority.repository}/git/ref/heads/${encodedBranch}`], label);
  const value = parseJson(bytes, label);
  if (value.ref !== `refs/heads/${authority.branch}`
      || value.object?.type !== "commit"
      || value.object?.sha !== authority.commit) {
    fail(`${label} does not match the declared catalog commit`);
  }
  return value.object.sha;
}

function verifiedPanelRuntime(response, label) {
  if (response.status !== 200) {
    fail(`${label} verification failed`);
  }
  const runtimeEnvelope = parseJson(response.body, label);
  try {
    if (!exactKeys(runtimeEnvelope, ["panelRuntime"])) {
      throw new Error("invalid envelope");
    }
    return validatePanelRuntimeContract(runtimeEnvelope.panelRuntime);
  } catch {
    fail(`${label} verification failed`);
  }
}

const runtimeResponse = await requestStatus(
  "runtime-provenance", "/api/runtime-provenance", { authenticated: true });
const panelRuntime = verifiedPanelRuntime(
  runtimeResponse, "live Panel runtime provenance");
if (panelRuntime.sourceCommit !== args["source-commit"]) {
  fail("live Panel source commit does not match the release source");
}
const authorityToolchainSha256 = await wranglerToolchainSha256();
const publicRepository = liveRepository(args["public-repository"], "public source repository");
if (publicRepository.private !== false) {
  fail("public source repository identity is invalid");
}
let authoritySnapshot;
if (deploymentInput.authority.type === "github") {
  const authority = deploymentInput.authority;
  const privateRepository = liveRepository(
    authority.repository, "private catalog repository");
  if (privateRepository.private !== true
      || privateRepository.node_id === publicRepository.node_id
      || privateRepository.full_name.toLowerCase()
        === publicRepository.full_name.toLowerCase()
      || !validGitHubBranch(privateRepository.default_branch)) {
    fail("catalog repository is not private and separate from public source");
  }
  const initialBranchHead = liveGitHubBranchHead(
    authority, "private catalog branch head");
  const rawBase = `repos/${authority.repository}/contents`;
  const rawHeaders = ["-H", "Accept: application/vnd.github.raw+json"];
  const treeBytes = runBytes("gh", ["api",
    `repos/${authority.repository}/git/trees/${authority.commit}?recursive=1`],
  "private catalog Git tree");
  let treeState;
  try {
    treeState = parseGitHubCatalogTree(treeBytes);
  } catch (error) {
    fail(error.message);
  }
  const catalogBytes = runBytes("gh", ["api", ...rawHeaders,
    `${rawBase}/form-profiles.json?ref=${authority.commit}`], "private catalog snapshot");
  const manifestBytes = runBytes("gh", ["api", ...rawHeaders,
    `${rawBase}/manifest.json?ref=${authority.commit}`], "private catalog manifest");
  const panelSettingsBytes = treeState.panelSettingsPresent
    ? runBytes("gh", ["api", ...rawHeaders,
      `${rawBase}/panel-settings.json?ref=${authority.commit}`], "private Panel settings")
    : null;
  if (panelSettingsBytes !== null) {
    parseJson(panelSettingsBytes, "private Panel settings");
  }
  const initialWorkerBinding = liveGitHubWorkerBinding(
    authority,
    panelRuntime.workerVersionId,
    authorityToolchainSha256,
    privateRepository.default_branch
  );
  authoritySnapshot = {
    catalogBytes,
    manifestBytes,
    panelSettingsBytes,
    authorityIdentitySha256: sha256(Buffer.from([
      "AUTOFORM_KIT_CATALOG_AUTHORITY_V1",
      "github",
      privateRepository.node_id,
      privateRepository.full_name
    ].join("\n"), "utf8")),
    authorityRevision: authority.commit,
    workerBindingSha256: initialWorkerBinding,
    async assertStillCurrent() {
      const repositoryAfter = liveRepository(
        authority.repository, "private catalog repository recheck");
      if (repositoryAfter.private !== true
          || repositoryAfter.node_id !== privateRepository.node_id
          || repositoryAfter.full_name !== privateRepository.full_name
          || repositoryAfter.default_branch !== privateRepository.default_branch) {
        fail("private GitHub catalog authority changed during verification");
      }
      const branchHeadAfter = liveGitHubBranchHead(
        authority, "private catalog branch head recheck");
      const treeAfterBytes = runBytes("gh", ["api",
        `repos/${authority.repository}/git/trees/${authority.commit}?recursive=1`],
      "private catalog Git tree recheck");
      let treeAfter;
      try {
        treeAfter = parseGitHubCatalogTree(treeAfterBytes);
      } catch (error) {
        fail(error.message);
      }
      if (JSON.stringify(treeAfter) !== JSON.stringify(treeState)
          || branchHeadAfter !== initialBranchHead
          || !runBytes("gh", ["api", ...rawHeaders,
            `${rawBase}/form-profiles.json?ref=${authority.commit}`],
          "private catalog snapshot recheck").equals(catalogBytes)
          || !runBytes("gh", ["api", ...rawHeaders,
            `${rawBase}/manifest.json?ref=${authority.commit}`],
          "private catalog manifest recheck").equals(manifestBytes)
          || (panelSettingsBytes !== null
            && !runBytes("gh", ["api", ...rawHeaders,
              `${rawBase}/panel-settings.json?ref=${authority.commit}`],
            "private Panel settings recheck").equals(panelSettingsBytes))
          || liveGitHubWorkerBinding(authority, panelRuntime.workerVersionId,
            authorityToolchainSha256, repositoryAfter.default_branch)
            !== initialWorkerBinding) {
        fail("private GitHub catalog authority changed during verification");
      }
    }
  };
} else {
  authoritySnapshot = await liveR2Authority(
    deploymentInput.authority,
    panelRuntime.workerVersionId,
    authorityToolchainSha256
  );
}
if (!authoritySnapshot.catalogBytes.equals(catalogFile.bytes)) {
  fail("live private catalog bytes do not match evidence");
}
const rawManifest = authoritySnapshot.manifestBytes;
const manifest = parseJson(rawManifest, "private catalog manifest");
if (!exactKeys(manifest, [
  "schemaVersion", "version", "sha256", "profilesUrl", "minAppVersionCode",
  "updatedAt", "notes"
]) || manifest.schemaVersion !== catalog.schemaVersion
    || manifest.version !== proof.catalogVersion
    || manifest.sha256 !== catalogFile.sha256
    || manifest.profilesUrl !== `${panelUrl.origin}/catalog/form-profiles.json`
    || !Number.isSafeInteger(manifest.minAppVersionCode)
    || manifest.minAppVersionCode < 0
    || typeof manifest.updatedAt !== "string"
    || typeof manifest.notes !== "string") {
  fail("private catalog manifest does not match the exact Panel catalog");
}
const settingsBinding = panelSettingsBinding(authoritySnapshot.panelSettingsBytes);

async function requestStatus(name, path, {
  authenticated = false,
  incorrectBearer = false,
  post = false
} = {}) {
  if (authenticated && incorrectBearer) {
    fail("invalid internal Panel request authentication mode");
  }
  const headers = {};
  if (authenticated) headers.Authorization = `Bearer ${deploymentInput.catalogReadKey}`;
  if (incorrectBearer) {
    const fixedInvalidToken = "autoform-kit-deliberately-invalid-release-token-v1";
    const invalidToken = fixedInvalidToken === deploymentInput.catalogReadKey
      ? `${fixedInvalidToken}-alternate`
      : fixedInvalidToken;
    headers.Authorization = `Bearer ${invalidToken}`;
  }
  if (post) headers["Content-Type"] = "application/json";
  let response;
  try {
    response = await fetch(`${panelUrl.origin}${path}`, {
      method: post ? "POST" : "GET",
      headers,
      body: post ? "{}" : undefined,
      redirect: "error",
      signal: AbortSignal.timeout(15_000)
    });
  } catch {
    fail(`Panel ${name} live verification failed`);
  }
  if (response.url && new URL(response.url).origin !== panelUrl.origin) {
    fail(`Panel ${name} changed origin`);
  }
  return { status: response.status, body: Buffer.from(await response.arrayBuffer()) };
}

const protectedPanelRoutes = Object.freeze([
  { name: "catalog", path: "/catalog/form-profiles.json", post: false },
  { name: "manifest", path: "/catalog/manifest", post: false },
  { name: "config", path: "/api/config", post: false },
  { name: "profiles", path: "/api/profiles", post: false },
  { name: "panel-config", path: "/api/panel-config", post: false },
  { name: "runtime-provenance", path: "/api/runtime-provenance", post: false },
  { name: "notification", path: "/api/notify", post: true }
]);
const [authenticatedResponses, anonymousResponses, incorrectBearerResponses] =
  await Promise.all([
    Promise.all([
      requestStatus("authenticated-config", "/api/config", { authenticated: true }),
      requestStatus(
        "authenticated-catalog", "/catalog/form-profiles.json", { authenticated: true }),
      requestStatus("authenticated-manifest", "/catalog/manifest", { authenticated: true }),
      requestStatus(
        "authenticated-panel-config", "/api/panel-config", { authenticated: true }),
      requestStatus("authenticated-profiles", "/api/profiles", { authenticated: true })
    ]),
    Promise.all(protectedPanelRoutes.map((route) => requestStatus(
      `anonymous-${route.name}`, route.path, { post: route.post }))),
    Promise.all(protectedPanelRoutes.map((route) => requestStatus(
      `incorrect-bearer-${route.name}`, route.path,
      { incorrectBearer: true, post: route.post })))
  ]);
const [authenticatedConfig, authenticatedCatalog, authenticatedManifest,
  authenticatedPanelConfig, authenticatedProfiles] = authenticatedResponses;
if (authenticatedConfig.status !== 200 || !authenticatedConfig.body.equals(configFile.bytes)
    || authenticatedCatalog.status !== 200 || !authenticatedCatalog.body.equals(catalogFile.bytes)
    || authenticatedManifest.status !== 200
    || !authenticatedManifest.body.equals(rawManifest)
    || authenticatedPanelConfig.status !== 200
    || authenticatedProfiles.status !== 200
    || [...anonymousResponses, ...incorrectBearerResponses]
      .some((response) => response.status !== 401)) {
  fail("live CATALOG_READ_KEY or anonymous-denial verification failed");
}
try {
  await authoritySnapshot.assertStillCurrent();
} catch (error) {
  fail(error.message);
}
const runtimeResponseAfter = await requestStatus(
  "runtime-provenance recheck", "/api/runtime-provenance", { authenticated: true });
if (runtimeResponseAfter.status !== 200
    || !runtimeResponseAfter.body.equals(runtimeResponse.body)) {
  fail("live Panel runtime provenance changed during verification");
}
const panelRuntimeAfter = verifiedPanelRuntime(
  runtimeResponseAfter, "live Panel runtime provenance recheck");
if (JSON.stringify(panelRuntimeAfter) !== JSON.stringify(panelRuntime)) {
  fail("live Panel runtime provenance changed during verification");
}
const publicRepositoryAfter = liveRepository(
  args["public-repository"], "public source repository recheck");
if (publicRepositoryAfter.private !== false
    || publicRepositoryAfter.node_id !== publicRepository.node_id
    || publicRepositoryAfter.full_name !== publicRepository.full_name) {
  fail("public source repository identity changed during verification");
}

const expectedBindings = {
  sourceCommit: args["source-commit"],
  candidateManifestSha256: args["candidate-manifest-sha256"],
  apkSha256: args["apk-sha256"],
  previousApkSha256: args["previous-apk-sha256"],
  privateGateSha256: args["private-gate-sha256"],
  migrationReportSha256: migrationFile.sha256,
  panelConfigSha256: configFile.sha256,
  panelCatalogSha256: catalogFile.sha256,
  panelPairSha256: pairSha256,
  catalogVersion: proof.catalogVersion
};
const result = {
  schemaVersion: 2,
  passed: true,
  verifierSha256: sha256(verifierBytes),
  bindings: {
    ...expectedBindings,
    deploymentEvidenceSha256: deploymentFile.sha256,
    panelWorkerVersionId: panelRuntime.workerVersionId,
    catalogAuthorityType: deploymentInput.authority.type,
    catalogAuthorityIdentitySha256: authoritySnapshot.authorityIdentitySha256,
    catalogAuthorityRevision: authoritySnapshot.authorityRevision,
    catalogAuthorityWorkerBindingSha256: authoritySnapshot.workerBindingSha256,
    catalogManifestSha256: sha256(rawManifest),
    panelSettingsPresent: settingsBinding.present,
    panelSettingsSha256: settingsBinding.sha256
  },
  checks: {
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
  }
};
process.stdout.write(`${JSON.stringify(result)}\n`);
}

if (process.argv[1] && fileURLToPath(import.meta.url) === resolve(process.argv[1])) {
  await main();
}
