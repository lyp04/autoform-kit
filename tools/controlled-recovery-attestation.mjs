#!/usr/bin/env node

import {
  createHash,
  createPublicKey,
  randomBytes,
  verify as verifySignature
} from "node:crypto";
import {
  closeSync,
  constants as fsConstants,
  fchmodSync,
  fstatSync,
  fsyncSync,
  linkSync,
  lstatSync,
  openSync,
  readFileSync,
  unlinkSync,
  writeFileSync
} from "node:fs";
import { basename, dirname, join, resolve } from "node:path";
import { pathToFileURL } from "node:url";

import {
  CONTROLLED_RECOVERY_OPERATIONS,
  validateControlledRecoveryConfig
} from "../panel/src/backend-adapter.js";

const ATTESTATION_KEYS = Object.freeze([
  "schemaVersion",
  "status",
  "sourceCommit",
  "catalogVersion",
  "panelPairSha256",
  "backendAdapterSha256",
  "recoveryCapabilitySha256",
  "reconciliationContractSha256",
  "replayInputSha256",
  "replayResultSha256",
  "keyId",
  "signedUpgradeDeviceTest",
  "operatorRecoveryDeviceTest",
  "runtimeRecoveryWiringVerified",
  "persistentChallengeConsumptionVerified",
  "atomicRecoveryPersistenceVerified",
  "legacyUncertainBlocked",
  "normalUpgradeExactJournal",
  "operations",
  "attestationSignatureHex"
]);

const REPLAY_INPUT_KEYS = Object.freeze([
  "schemaVersion",
  "sourceCommit",
  "catalogVersion",
  "panelPairSha256",
  "backendAdapterSha256",
  "reconciliationContractSha256",
  "operations"
]);

const REPLAY_INPUT_OPERATION_KEYS = Object.freeze([
  "serverCorrelationSha256",
  "journalPairCrossProofSha256",
  "legacyUncertainFixtureSha256"
]);

const REPLAY_RESULT_KEYS = Object.freeze([
  "schemaVersion",
  "replayInputSha256",
  "operations"
]);

const OPERATION_EVIDENCE_KEYS = Object.freeze([
  "serverCorrelationSha256",
  "remoteReceiptSha256",
  "journalPairCrossProofSha256",
  "legacyUncertainFixtureSha256",
  "checks"
]);

const COMMON_OPERATION_CHECKS = Object.freeze([
  "authorityCorrelationExact",
  "remoteReceiptBound",
  "journalPairCrossProofExact",
  "persistentChallengeReplayRejected",
  "evidenceReplayRejected",
  "operatorJsonCannotUnlock",
  "atomicCommitFailureKeepsLocked",
  "atomicRestartKeepsLocked",
  "legacyUncertainBlocked"
]);

const FINAL_CHECKS = Object.freeze([
  ...COMMON_OPERATION_CHECKS,
  "written",
  "notWritten",
  "wrongConnectionRejected",
  "wrongCatalogRejected",
  "wrongPanelPairRejected",
  "wrongProfileRejected",
  "wrongDraftRejected",
  "wrongOperationRejected",
  "wrongPayloadRejected",
  "expiredEvidenceRejected",
  "unsignedEvidenceRejected"
]);

const PREVIOUS_STEP_CHECKS = Object.freeze([
  ...FINAL_CHECKS,
  "wrongRecipeRejected",
  "completedPrefixPreserved",
  "retryAttemptRemainsMonotonic"
]);

const UPLOAD_CHECKS = Object.freeze([
  ...COMMON_OPERATION_CHECKS,
  "notWrittenClearsForRetry",
  "partialKeepsLocked",
  "completeWithoutReceiptsKeepsLocked",
  "wrongConnectionRejected",
  "wrongCatalogRejected",
  "wrongPanelPairRejected",
  "wrongProfileRejected",
  "wrongDraftRejected",
  "wrongOperationRejected",
  "wrongUploadIdentityRejected",
  "expiredEvidenceRejected",
  "unsignedEvidenceRejected"
]);

const CHECKS_BY_OPERATION = Object.freeze({
  FINAL_SUBMISSION: FINAL_CHECKS,
  PREVIOUS_STEP_RECIPE: PREVIOUS_STEP_CHECKS,
  MULTIPART_UPLOAD: UPLOAD_CHECKS
});

const OPERATION_ARTIFACT_PREFIX = Object.freeze({
  FINAL_SUBMISSION: "final-submission",
  PREVIOUS_STEP_RECIPE: "previous-step-recipe",
  MULTIPART_UPLOAD: "multipart-upload"
});

const ARTIFACT_FILE_SUFFIX = Object.freeze({
  serverCorrelationBytes: "server-correlation.bin",
  remoteReceiptBytes: "remote-receipt.bin",
  journalPairCrossProofBytes: "journal-pair-cross-proof.bin",
  legacyUncertainFixtureBytes: "legacy-uncertain-fixture.bin"
});

const CLI_ARGUMENTS = Object.freeze([
  "adapter",
  "contract",
  "replay-input",
  "replay-result",
  "attestation",
  "operation-evidence-dir",
  "source-commit",
  "catalog-version",
  "panel-pair",
  "output"
]);

function sha256(value) {
  return createHash("sha256").update(value).digest("hex");
}

function isObject(value) {
  return value !== null && typeof value === "object" && !Array.isArray(value);
}

function exactKeys(value, expected) {
  if (!isObject(value)) return false;
  const actual = Object.keys(value).sort();
  const wanted = [...expected].sort();
  return actual.length === wanted.length
    && actual.every((key, index) => key === wanted[index]);
}

function canonicalJson(value) {
  if (value === null || typeof value === "boolean" || typeof value === "string") {
    return JSON.stringify(value);
  }
  if (typeof value === "number") {
    if (!Number.isFinite(value)) throw new Error("backendAdapter contains a non-finite number");
    return JSON.stringify(value);
  }
  if (Array.isArray(value)) return `[${value.map(canonicalJson).join(",")}]`;
  if (!isObject(value)) throw new Error("backendAdapter contains an unsupported value");
  return `{${Object.keys(value).sort().map((key) =>
    `${JSON.stringify(key)}:${canonicalJson(value[key])}`).join(",")}}`;
}

function bytes(value) {
  return Buffer.isBuffer(value) ? value : Buffer.from(value || "");
}

function parsePrivateJsonBytes(value, label, errors) {
  const raw = bytes(value);
  if (raw.length === 0) {
    errors.push(`${label} is empty`);
    return null;
  }
  try {
    const parsed = JSON.parse(raw.toString("utf8"));
    if (!isObject(parsed)) throw new Error("not object");
    return parsed;
  } catch {
    errors.push(`${label} is not an exact JSON object`);
    return null;
  }
}

function digestOperationArtifacts(operationArtifacts, operation, errors) {
  const supplied = operationArtifacts?.[operation];
  const digests = {};
  for (const [bytesKey, field] of [
    ["serverCorrelationBytes", "serverCorrelationSha256"],
    ["remoteReceiptBytes", "remoteReceiptSha256"],
    ["journalPairCrossProofBytes", "journalPairCrossProofSha256"],
    ["legacyUncertainFixtureBytes", "legacyUncertainFixtureSha256"]
  ]) {
    const raw = bytes(supplied?.[bytesKey]);
    if (raw.length === 0) {
      errors.push(`private ${operation}.${bytesKey} is empty`);
      digests[field] = "";
    } else {
      digests[field] = sha256(raw);
    }
  }
  return digests;
}

function requireSha256(value, label, errors) {
  if (!/^[0-9a-f]{64}$/u.test(String(value || ""))) {
    errors.push(`${label} must be lowercase SHA-256`);
    return false;
  }
  return true;
}

function normalizedBackendAdapterSha256(backendAdapter) {
  return sha256(canonicalJson(backendAdapter));
}

export function controlledRecoveryBackendAdapterSha256(adapter) {
  const backendAdapter = adapter?.backendAdapter || adapter;
  return normalizedBackendAdapterSha256(backendAdapter);
}

export function controlledRecoveryAttestationSignedBytes(attestation) {
  if (!isObject(attestation)) throw new Error("attestation must be an object");
  const unsigned = Object.fromEntries(Object.entries(attestation)
    .filter(([key]) => key !== "attestationSignatureHex"));
  return Buffer.from(
    `AUTOFORM_KIT_CONTROLLED_RECOVERY_ATTESTATION_V1\n${canonicalJson(unsigned)}`,
    "utf8");
}

class Canonical {
  constructor(domain) {
    this.value = "";
    this.add("domain", domain);
  }

  add(key, raw) {
    const text = raw === null || raw === undefined ? "" : String(raw);
    this.value += `${key.length}:${key}:${Buffer.byteLength(text, "utf8")}:${text};`;
    return this;
  }
}

/** Must remain byte-for-byte equivalent to ControlledRecoveryRules.Capability. */
export function controlledRecoveryCapabilitySha256(recovery) {
  const enabled = CONTROLLED_RECOVERY_OPERATIONS
    .filter((operation) => recovery?.enabledOperations?.includes(operation))
    .join(",");
  return sha256(new Canonical("capability")
    .add("version", recovery.version)
    .add("algorithm", recovery.evidenceAlgorithm)
    .add("keyId", recovery.keyId)
    .add("publicKeySpkiHex", recovery.publicKeySpkiHex)
    .add("maxEvidenceAgeSeconds", recovery.maxEvidenceAgeSeconds)
    .add("reconciliationContractSha256", recovery.reconciliationContractSha256)
    .add("enabledOperations", enabled)
    .value);
}

/**
 * Validates only release evidence. It never prints or copies private adapter/attestation content.
 * A passing structure is still meaningful only when the private replay runner produced it from
 * the exact backend reconciliation contract supplied here.
 */
export function validateControlledRecoveryReleaseEvidence({
  adapter,
  reconciliationContractBytes,
  replayInputBytes,
  replayResultBytes,
  operationArtifacts,
  attestation,
  expectedSourceCommit,
  expectedCatalogVersion,
  expectedPanelPairSha256
}) {
  const errors = [];
  const backendAdapter = adapter?.backendAdapter || adapter;
  const capabilityErrors = validateControlledRecoveryConfig(backendAdapter);
  errors.push(...capabilityErrors.map((error) => `capability: ${error}`));

  if (!exactKeys(attestation, ATTESTATION_KEYS)) {
    errors.push("attestation fields are not exact");
    return errors;
  }
  if (attestation.schemaVersion !== 1) errors.push("attestation schemaVersion must be 1");
  if (attestation.status !== "PASS") errors.push("attestation status must be PASS");
  if (!/^[0-9a-f]{40}(?:[0-9a-f]{24})?$/u.test(String(expectedSourceCommit || ""))) {
    errors.push("expected source commit is invalid");
  }
  if (attestation.sourceCommit !== expectedSourceCommit) {
    errors.push("attestation sourceCommit does not match");
  }
  if (!Number.isSafeInteger(expectedCatalogVersion) || expectedCatalogVersion <= 0
      || attestation.catalogVersion !== expectedCatalogVersion) {
    errors.push("attestation catalogVersion does not match");
  }
  if (!/^[0-9a-f]{64}$/u.test(String(expectedPanelPairSha256 || ""))
      || attestation.panelPairSha256 !== expectedPanelPairSha256) {
    errors.push("attestation panelPairSha256 does not match");
  }

  let backendAdapterSha256 = "";
  try {
    // `backendAdapter` was normalized once above. Hash it directly so a truthy
    // field named backendAdapter inside the adapter cannot trigger a second unwrap.
    backendAdapterSha256 = normalizedBackendAdapterSha256(backendAdapter);
  } catch {
    errors.push("backendAdapter canonical digest failed");
  }
  if (attestation.backendAdapterSha256 !== backendAdapterSha256) {
    errors.push("attestation backendAdapterSha256 does not match");
  }

  const recovery = backendAdapter?.operations?.recovery;
  if (isObject(recovery)) {
    const capabilitySha256 = controlledRecoveryCapabilitySha256(recovery);
    if (attestation.recoveryCapabilitySha256 !== capabilitySha256) {
      errors.push("attestation recoveryCapabilitySha256 does not match");
    }
  }
  const contractBytes = bytes(reconciliationContractBytes);
  const contractSha256 = sha256(contractBytes);
  if (contractBytes.length === 0) errors.push("reconciliation contract is empty");
  if (recovery?.reconciliationContractSha256 !== contractSha256
      || attestation.reconciliationContractSha256 !== contractSha256) {
    errors.push("reconciliation contract digest does not match");
  }
  const replayInput = bytes(replayInputBytes);
  const replayResult = bytes(replayResultBytes);
  if (replayInput.length === 0) errors.push("private replay input is empty");
  if (replayResult.length === 0) errors.push("private replay result is empty");
  if (attestation.replayInputSha256 !== sha256(replayInput)) {
    errors.push("attestation replayInputSha256 does not match");
  }
  if (attestation.replayResultSha256 !== sha256(replayResult)) {
    errors.push("attestation replayResultSha256 does not match");
  }
  if (attestation.keyId !== recovery?.keyId) {
    errors.push("attestation keyId does not match");
  }

  const signatureHex = String(attestation.attestationSignatureHex || "");
  if (!/^[0-9a-f]+$/u.test(signatureHex) || (signatureHex.length % 2) !== 0) {
    errors.push("attestation signature is invalid");
  } else if (isObject(recovery)) {
    try {
      const publicKey = createPublicKey({
        key: Buffer.from(recovery.publicKeySpkiHex, "hex"),
        format: "der",
        type: "spki"
      });
      const valid = verifySignature(
        "RSA-SHA256",
        controlledRecoveryAttestationSignedBytes(attestation),
        publicKey,
        Buffer.from(signatureHex, "hex"));
      if (!valid) errors.push("attestation signature is invalid");
    } catch {
      errors.push("attestation signature is invalid");
    }
  }

  for (const key of [
    "signedUpgradeDeviceTest", "operatorRecoveryDeviceTest",
    "runtimeRecoveryWiringVerified", "persistentChallengeConsumptionVerified",
    "atomicRecoveryPersistenceVerified", "legacyUncertainBlocked",
    "normalUpgradeExactJournal"
  ]) {
    if (attestation[key] !== true) errors.push(`attestation ${key} must be true`);
  }

  const replayInputValue = parsePrivateJsonBytes(
    replayInput, "private replay input", errors);
  const replayResultValue = parsePrivateJsonBytes(
    replayResult, "private replay result", errors);
  if (replayInputValue) {
    if (!exactKeys(replayInputValue, REPLAY_INPUT_KEYS)) {
      errors.push("private replay input fields are not exact");
    }
    if (replayInputValue.schemaVersion !== 1) {
      errors.push("private replay input schemaVersion must be 1");
    }
    if (replayInputValue.sourceCommit !== expectedSourceCommit) {
      errors.push("private replay input sourceCommit does not match");
    }
    if (replayInputValue.catalogVersion !== expectedCatalogVersion) {
      errors.push("private replay input catalogVersion does not match");
    }
    if (replayInputValue.panelPairSha256 !== expectedPanelPairSha256) {
      errors.push("private replay input panelPairSha256 does not match");
    }
    if (replayInputValue.backendAdapterSha256 !== backendAdapterSha256) {
      errors.push("private replay input backendAdapterSha256 does not match");
    }
    if (replayInputValue.reconciliationContractSha256 !== contractSha256) {
      errors.push("private replay input reconciliationContractSha256 does not match");
    }
  }
  if (replayResultValue) {
    if (!exactKeys(replayResultValue, REPLAY_RESULT_KEYS)) {
      errors.push("private replay result fields are not exact");
    }
    if (replayResultValue.schemaVersion !== 1) {
      errors.push("private replay result schemaVersion must be 1");
    }
    if (replayResultValue.replayInputSha256 !== sha256(replayInput)) {
      errors.push("private replay result does not bind the exact replay input");
    }
  }
  if (!exactKeys(attestation.operations, CONTROLLED_RECOVERY_OPERATIONS)) {
    errors.push("attestation operations are not exact");
    return errors;
  }
  if (replayInputValue
      && !exactKeys(replayInputValue.operations, CONTROLLED_RECOVERY_OPERATIONS)) {
    errors.push("private replay input operations are not exact");
  }
  if (replayResultValue
      && !exactKeys(replayResultValue.operations, CONTROLLED_RECOVERY_OPERATIONS)) {
    errors.push("private replay result operations are not exact");
  }
  for (const operation of CONTROLLED_RECOVERY_OPERATIONS) {
    const result = attestation.operations[operation];
    const replayInputOperation = replayInputValue?.operations?.[operation];
    const replayResultOperation = replayResultValue?.operations?.[operation];
    const artifactDigests = digestOperationArtifacts(
      operationArtifacts, operation, errors);
    const expectedChecks = CHECKS_BY_OPERATION[operation];
    if (!exactKeys(result, OPERATION_EVIDENCE_KEYS)) {
      errors.push(`attestation ${operation} evidence fields are not exact`);
      continue;
    }
    if (!exactKeys(replayInputOperation, REPLAY_INPUT_OPERATION_KEYS)) {
      errors.push(`private replay input ${operation} fields are not exact`);
    }
    if (!exactKeys(replayResultOperation, OPERATION_EVIDENCE_KEYS)) {
      errors.push(`private replay result ${operation} fields are not exact`);
    }
    for (const field of [
      "serverCorrelationSha256", "remoteReceiptSha256",
      "journalPairCrossProofSha256", "legacyUncertainFixtureSha256"
    ]) {
      requireSha256(result[field], `attestation ${operation}.${field}`, errors);
      if (result[field] !== artifactDigests[field]) {
        errors.push(`attestation ${operation}.${field} does not match private evidence`);
      }
      if (replayResultOperation?.[field] !== artifactDigests[field]) {
        errors.push(`private replay result ${operation}.${field} does not match private evidence`);
      }
      if (field !== "remoteReceiptSha256"
          && replayInputOperation?.[field] !== artifactDigests[field]) {
        errors.push(`private replay input ${operation}.${field} does not match private evidence`);
      }
    }
    if (!exactKeys(result.checks, expectedChecks)) {
      errors.push(`attestation ${operation} checks are not exact`);
      continue;
    }
    if (!exactKeys(replayResultOperation?.checks, expectedChecks)) {
      errors.push(`private replay result ${operation} checks are not exact`);
      continue;
    }
    for (const check of expectedChecks) {
      if (result.checks[check] !== true) {
        errors.push(`attestation ${operation}.${check} must be true`);
      }
      if (replayResultOperation.checks[check] !== true) {
        errors.push(`private replay result ${operation}.${check} must be true`);
      }
    }
  }
  return errors;
}

function parseArgs(argv) {
  const out = {};
  const allowed = new Set(CLI_ARGUMENTS);
  for (let index = 0; index < argv.length; index += 1) {
    const key = argv[index];
    if (!key.startsWith("--") || index + 1 >= argv.length) {
      throw new Error("arguments must be --name value pairs");
    }
    const name = key.slice(2);
    if (!allowed.has(name) || Object.hasOwn(out, name)) {
      throw new Error("arguments contain an unknown or duplicate name");
    }
    out[name] = argv[index += 1];
  }
  return out;
}

function readJson(path, label) {
  const raw = readPrivateFile(path, label);
  try {
    const value = JSON.parse(raw.toString("utf8"));
    if (!isObject(value)) throw new Error("not object");
    return value;
  } catch {
    throw new Error(`${label} is not a readable JSON object`);
  }
}

function exactMode(stat, expected) {
  return Number(stat.mode & 0o7777n) === expected;
}

function sameIdentity(left, right) {
  return left.dev === right.dev && left.ino === right.ino;
}

function stableFileSnapshot(before, after) {
  return sameIdentity(before, after)
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
    && stat.size <= 16n * 1024n * 1024n;
}

/**
 * Reads one immutable private-file snapshot. The optional hook exists only so the
 * selftest can deterministically replace a path after open and prove fail-closed
 * behavior; the CLI never supplies it.
 */
export function readPrivateFile(path, label, { afterOpen } = {}) {
  const target = resolve(path);
  let descriptor;
  try {
    const before = lstatSync(target, { bigint: true });
    if (!privateFileStatIsValid(before)) {
      throw new Error("invalid file");
    }
    descriptor = openSync(target, fsConstants.O_RDONLY | fsConstants.O_NOFOLLOW);
    const opened = fstatSync(descriptor, { bigint: true });
    if (!privateFileStatIsValid(opened) || !stableFileSnapshot(before, opened)) {
      throw new Error("file changed before open");
    }
    if (afterOpen) afterOpen();
    const raw = readFileSync(descriptor);
    const afterRead = fstatSync(descriptor, { bigint: true });
    const afterPath = lstatSync(target, { bigint: true });
    if (!privateFileStatIsValid(afterRead)
        || !privateFileStatIsValid(afterPath)
        || !stableFileSnapshot(opened, afterRead)
        || !stableFileSnapshot(opened, afterPath)
        || BigInt(raw.length) !== opened.size) {
      throw new Error("file changed during read");
    }
    return raw;
  } catch {
    throw new Error(`${label} is not a stable bounded regular private file with mode 0600`);
  } finally {
    if (descriptor !== undefined) closeSync(descriptor);
  }
}

function privateDirectoryStatIsValid(stat) {
  return stat.isDirectory()
    && !stat.isSymbolicLink()
    && exactMode(stat, 0o700);
}

function stableDirectorySnapshot(before, after) {
  return sameIdentity(before, after)
    && before.mode === after.mode
    && before.nlink === after.nlink
    && before.mtimeNs === after.mtimeNs
    && before.ctimeNs === after.ctimeNs;
}

function openedDirectorySnapshot(descriptor, path) {
  const descriptorStat = fstatSync(descriptor, { bigint: true });
  const pathStat = lstatSync(path, { bigint: true });
  if (!descriptorStat.isDirectory()
      || !pathStat.isDirectory()
      || pathStat.isSymbolicLink()
      || !stableDirectorySnapshot(descriptorStat, pathStat)) {
    throw new Error("directory path no longer names the opened directory");
  }
  return descriptorStat;
}

export function readOperationArtifacts(directory, { afterOpen } = {}) {
  let root;
  let descriptor;
  let opened;
  try {
    root = resolve(directory);
    const before = lstatSync(root, { bigint: true });
    if (!privateDirectoryStatIsValid(before)) throw new Error("invalid directory");
    descriptor = openSync(
      root,
      fsConstants.O_RDONLY | fsConstants.O_DIRECTORY | fsConstants.O_NOFOLLOW);
    opened = fstatSync(descriptor, { bigint: true });
    if (!privateDirectoryStatIsValid(opened)
        || !stableDirectorySnapshot(before, opened)) {
      throw new Error("directory changed before open");
    }
    if (afterOpen) afterOpen();
  } catch {
    if (descriptor !== undefined) closeSync(descriptor);
    throw new Error(
      "operation evidence directory is not a stable non-symlink directory with mode 0700");
  }
  try {
    const artifacts = {};
    for (const operation of CONTROLLED_RECOVERY_OPERATIONS) {
      const prefix = OPERATION_ARTIFACT_PREFIX[operation];
      artifacts[operation] = {};
      for (const [bytesKey, suffix] of Object.entries(ARTIFACT_FILE_SUFFIX)) {
        artifacts[operation][bytesKey] = readPrivateFile(
          resolve(root, `${prefix}.${suffix}`),
          `${operation} ${bytesKey}`);
      }
    }
    try {
      const afterDescriptor = fstatSync(descriptor, { bigint: true });
      const afterPath = lstatSync(root, { bigint: true });
      if (!privateDirectoryStatIsValid(afterDescriptor)
          || !privateDirectoryStatIsValid(afterPath)
          || !stableDirectorySnapshot(opened, afterDescriptor)
          || !stableDirectorySnapshot(opened, afterPath)) {
        throw new Error("directory changed during read");
      }
    } catch {
      throw new Error("operation evidence directory changed while reading private artifacts");
    }
    return artifacts;
  } finally {
    closeSync(descriptor);
  }
}

function unlinkIfOwned(path, identity) {
  if (!path || !identity) return true;
  try {
    const current = lstatSync(path, { bigint: true });
    if (!sameIdentity(current, identity)) return false;
    unlinkSync(path);
    return true;
  } catch (error) {
    // Missing or attacker-replaced temporary paths must not cause another file to be removed.
    return error?.code === "ENOENT";
  }
}

export function writePrivateOutputAtomically(
  path,
  value,
  { afterDirectoryOpen, afterPublish } = {}) {
  const output = resolve(path);
  const outputDirectory = dirname(output);
  let directoryDescriptor;
  let temporaryPath;
  let temporaryIdentity;
  try {
    const directoryBefore = lstatSync(outputDirectory, { bigint: true });
    if (!directoryBefore.isDirectory() || directoryBefore.isSymbolicLink()) {
      throw new Error("invalid output directory");
    }
    directoryDescriptor = openSync(
      outputDirectory,
      fsConstants.O_RDONLY | fsConstants.O_DIRECTORY | fsConstants.O_NOFOLLOW);
    const directoryOpened = fstatSync(directoryDescriptor, { bigint: true });
    if (!directoryOpened.isDirectory() || !sameIdentity(directoryBefore, directoryOpened)) {
      throw new Error("output directory changed before open");
    }
    if (afterDirectoryOpen) afterDirectoryOpen();
    let directoryCheckpoint = openedDirectorySnapshot(
      directoryDescriptor, outputDirectory);
    if (!stableDirectorySnapshot(directoryOpened, directoryCheckpoint)) {
      throw new Error("output directory changed after open");
    }
    try {
      lstatSync(output, { bigint: true });
      throw new Error("output already exists");
    } catch (error) {
      if (error?.code !== "ENOENT") throw error;
    }

    const payload = Buffer.from(`${JSON.stringify(value)}\n`, "utf8");
    temporaryPath = join(
      outputDirectory,
      `.${basename(output)}.${process.pid}.${randomBytes(16).toString("hex")}.tmp`);
    const temporaryDescriptor = openSync(
      temporaryPath,
      fsConstants.O_WRONLY | fsConstants.O_CREAT | fsConstants.O_EXCL
        | fsConstants.O_NOFOLLOW,
      0o600);
    try {
      fchmodSync(temporaryDescriptor, 0o600);
      const temporaryOpened = fstatSync(temporaryDescriptor, { bigint: true });
      const temporaryPathOpened = lstatSync(temporaryPath, { bigint: true });
      if (!temporaryOpened.isFile()
          || !exactMode(temporaryOpened, 0o600)
          || !stableFileSnapshot(temporaryOpened, temporaryPathOpened)) {
        throw new Error("invalid temporary output");
      }
      temporaryIdentity = temporaryOpened;
      directoryCheckpoint = openedDirectorySnapshot(
        directoryDescriptor, outputDirectory);
      writeFileSync(temporaryDescriptor, payload);
      fsyncSync(temporaryDescriptor);
      const temporaryWritten = fstatSync(temporaryDescriptor, { bigint: true });
      const temporaryPathWritten = lstatSync(temporaryPath, { bigint: true });
      if (!sameIdentity(temporaryOpened, temporaryWritten)
          || !exactMode(temporaryWritten, 0o600)
          || temporaryWritten.size !== BigInt(payload.length)
          || !stableFileSnapshot(temporaryWritten, temporaryPathWritten)
          || !stableDirectorySnapshot(
            directoryCheckpoint,
            openedDirectorySnapshot(directoryDescriptor, outputDirectory))) {
        throw new Error("temporary output changed during write");
      }
    } finally {
      closeSync(temporaryDescriptor);
    }

    // link(2) publishes a complete file without overwriting an existing path.
    linkSync(temporaryPath, output);
    const linkedTemporary = lstatSync(temporaryPath, { bigint: true });
    const linkedOutput = lstatSync(output, { bigint: true });
    if (!sameIdentity(temporaryIdentity, linkedTemporary)
        || !sameIdentity(temporaryIdentity, linkedOutput)) {
      throw new Error("output link did not publish the temporary inode");
    }
    directoryCheckpoint = openedDirectorySnapshot(
      directoryDescriptor, outputDirectory);
    if (!unlinkIfOwned(temporaryPath, temporaryIdentity)) {
      throw new Error("temporary output could not be removed safely");
    }
    temporaryPath = undefined;
    directoryCheckpoint = openedDirectorySnapshot(
      directoryDescriptor, outputDirectory);
    if (afterPublish) afterPublish();
    fsyncSync(directoryDescriptor);
    const published = lstatSync(output, { bigint: true });
    if (!privateFileStatIsValid(published)
        || !sameIdentity(temporaryIdentity, published)
        || published.nlink !== 1n) {
      throw new Error("published output identity is invalid");
    }
    const outputBytes = readPrivateFile(output, "controlled recovery output");
    if (!outputBytes.equals(payload)) throw new Error("output bytes changed");
    const publishedAfterRead = lstatSync(output, { bigint: true });
    if (!stableFileSnapshot(published, publishedAfterRead)) {
      throw new Error("published output changed after verification");
    }
    const directoryAfter = lstatSync(outputDirectory, { bigint: true });
    const directoryDescriptorAfter = fstatSync(directoryDescriptor, { bigint: true });
    if (!stableDirectorySnapshot(directoryCheckpoint, directoryDescriptorAfter)
        || !stableDirectorySnapshot(directoryCheckpoint, directoryAfter)) {
      throw new Error("output directory changed during write");
    }
  } catch {
    throw new Error(
      "controlled recovery output was not atomically created as a non-symlink mode 0600 file");
  } finally {
    unlinkIfOwned(temporaryPath, temporaryIdentity);
    if (directoryDescriptor !== undefined) closeSync(directoryDescriptor);
  }
}

function main(argv) {
  const args = parseArgs(argv);
  for (const key of CLI_ARGUMENTS) if (!args[key]) throw new Error(`missing --${key}`);
  const errors = validateControlledRecoveryReleaseEvidence({
    adapter: readJson(args.adapter, "adapter"),
    reconciliationContractBytes: readPrivateFile(
      args.contract, "reconciliation contract"),
    replayInputBytes: readPrivateFile(args["replay-input"], "private replay input"),
    replayResultBytes: readPrivateFile(args["replay-result"], "private replay result"),
    operationArtifacts: readOperationArtifacts(args["operation-evidence-dir"]),
    attestation: readJson(args.attestation, "attestation"),
    expectedSourceCommit: args["source-commit"],
    expectedCatalogVersion: Number(args["catalog-version"]),
    expectedPanelPairSha256: args["panel-pair"]
  });
  if (errors.length) {
    throw new Error(errors.join("; "));
  }
  writePrivateOutputAtomically(args.output, {
    ok: true,
    operationCount: CONTROLLED_RECOVERY_OPERATIONS.length,
    privateArtifactCount: CONTROLLED_RECOVERY_OPERATIONS.length
      * Object.keys(ARTIFACT_FILE_SUFFIX).length
  });
}

if (import.meta.url === pathToFileURL(process.argv[1] || "").href) {
  try {
    main(process.argv.slice(2));
  } catch {
    // Never echo validation details: adapter keys, paths and values are private inputs.
    process.stderr.write(
      "controlled recovery gate failed: CONTROLLED_RECOVERY_GATE_REJECTED\n");
    process.exitCode = 1;
  }
}
