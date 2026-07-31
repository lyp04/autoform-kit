import test from "node:test";
import assert from "node:assert/strict";
import { createHash, generateKeyPairSync, sign as signBytes } from "node:crypto";
import {
  chmodSync,
  lstatSync,
  mkdirSync,
  mkdtempSync,
  readFileSync,
  readdirSync,
  renameSync,
  rmSync,
  symlinkSync,
  writeFileSync
} from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { spawnSync } from "node:child_process";

import { validBackendAdapter } from "../panel/test/backend-adapter-fixture.js";
import {
  controlledRecoveryBackendAdapterSha256,
  controlledRecoveryCapabilitySha256,
  controlledRecoveryAttestationSignedBytes,
  readOperationArtifacts,
  readPrivateFile,
  validateControlledRecoveryReleaseEvidence,
  writePrivateOutputAtomically
} from "./controlled-recovery-attestation.mjs";

const SOURCE_COMMIT = "a".repeat(40);
const PANEL_PAIR = "b".repeat(64);
const CATALOG_VERSION = 17;
const CONTRACT = Buffer.from(
  "generic-private-reconciliation-contract-v1\nexact-query-and-signed-evidence\n",
  "utf8");
const CONTRACT_SHA = createHash("sha256").update(CONTRACT).digest("hex");

const OPERATIONS = [
  "FINAL_SUBMISSION", "PREVIOUS_STEP_RECIPE", "MULTIPART_UPLOAD"
];

const ARTIFACT_PREFIX = {
  FINAL_SUBMISSION: "final-submission",
  PREVIOUS_STEP_RECIPE: "previous-step-recipe",
  MULTIPART_UPLOAD: "multipart-upload"
};

const ARTIFACT_SUFFIX = {
  serverCorrelationBytes: "server-correlation.bin",
  remoteReceiptBytes: "remote-receipt.bin",
  journalPairCrossProofBytes: "journal-pair-cross-proof.bin",
  legacyUncertainFixtureBytes: "legacy-uncertain-fixture.bin"
};

const COMMON_CHECKS = [
  "authorityCorrelationExact", "remoteReceiptBound",
  "journalPairCrossProofExact", "persistentChallengeReplayRejected",
  "evidenceReplayRejected", "operatorJsonCannotUnlock",
  "atomicCommitFailureKeepsLocked", "atomicRestartKeepsLocked",
  "legacyUncertainBlocked"
];

const FINAL_CHECKS = [
  ...COMMON_CHECKS,
  "written", "notWritten", "wrongConnectionRejected", "wrongCatalogRejected",
  "wrongPanelPairRejected", "wrongProfileRejected", "wrongDraftRejected",
  "wrongOperationRejected", "wrongPayloadRejected", "expiredEvidenceRejected",
  "unsignedEvidenceRejected"
];
const PREVIOUS_CHECKS = [
  ...FINAL_CHECKS, "wrongRecipeRejected", "completedPrefixPreserved",
  "retryAttemptRemainsMonotonic"
];
const UPLOAD_CHECKS = [
  ...COMMON_CHECKS,
  "notWrittenClearsForRetry", "partialKeepsLocked",
  "completeWithoutReceiptsKeepsLocked", "wrongConnectionRejected",
  "wrongCatalogRejected", "wrongPanelPairRejected", "wrongProfileRejected",
  "wrongDraftRejected", "wrongOperationRejected", "wrongUploadIdentityRejected",
  "expiredEvidenceRejected", "unsignedEvidenceRejected"
];

function allTrue(keys) {
  return Object.fromEntries(keys.map((key) => [key, true]));
}

function digest(value) {
  return createHash("sha256").update(value).digest("hex");
}

function fictionalOperationArtifacts() {
  return Object.fromEntries(OPERATIONS.map((operation) => [operation, {
    serverCorrelationBytes: Buffer.from(
      `fictional-${operation}-server-correlation-v1`, "utf8"),
    remoteReceiptBytes: Buffer.from(
      `fictional-${operation}-remote-receipt-v1`, "utf8"),
    journalPairCrossProofBytes: Buffer.from(
      `fictional-${operation}-journal-pair-cross-proof-v1`, "utf8"),
    legacyUncertainFixtureBytes: Buffer.from(
      `fictional-${operation}-legacy-uncertain-fixture-v1`, "utf8")
  }]));
}

function artifactDigests(artifacts, operation) {
  const value = artifacts[operation];
  return {
    serverCorrelationSha256: digest(value.serverCorrelationBytes),
    remoteReceiptSha256: digest(value.remoteReceiptBytes),
    journalPairCrossProofSha256: digest(value.journalPairCrossProofBytes),
    legacyUncertainFixtureSha256: digest(value.legacyUncertainFixtureBytes)
  };
}

function fixture() {
  const adapter = validBackendAdapter();
  const signingKey = generateKeyPairSync("rsa", { modulusLength: 2048 });
  adapter.operations.recovery = {
    version: 1,
    issuanceMode: "panel_signed_exact_reconciliation",
    evidenceAlgorithm: "RS256",
    keyId: "sample-recovery-key-1",
    publicKeySpkiHex: signingKey.publicKey
      .export({ type: "spki", format: "der" }).toString("hex"),
    maxEvidenceAgeSeconds: 300,
    reconciliationContractSha256: CONTRACT_SHA,
    enabledOperations: [
      "FINAL_SUBMISSION", "PREVIOUS_STEP_RECIPE", "MULTIPART_UPLOAD"
    ]
  };
  const operationArtifacts = fictionalOperationArtifacts();
  const backendAdapterSha256 = controlledRecoveryBackendAdapterSha256(adapter);
  const replayInputValue = {
    schemaVersion: 1,
    sourceCommit: SOURCE_COMMIT,
    catalogVersion: CATALOG_VERSION,
    panelPairSha256: PANEL_PAIR,
    backendAdapterSha256,
    reconciliationContractSha256: CONTRACT_SHA,
    operations: Object.fromEntries(OPERATIONS.map((operation) => {
      const hashes = artifactDigests(operationArtifacts, operation);
      return [operation, {
        serverCorrelationSha256: hashes.serverCorrelationSha256,
        journalPairCrossProofSha256: hashes.journalPairCrossProofSha256,
        legacyUncertainFixtureSha256: hashes.legacyUncertainFixtureSha256
      }];
    }))
  };
  const replayInput = Buffer.from(JSON.stringify(replayInputValue), "utf8");
  const checks = {
    FINAL_SUBMISSION: allTrue(FINAL_CHECKS),
    PREVIOUS_STEP_RECIPE: allTrue(PREVIOUS_CHECKS),
    MULTIPART_UPLOAD: allTrue(UPLOAD_CHECKS)
  };
  const replayResultValue = {
    schemaVersion: 1,
    replayInputSha256: digest(replayInput),
    operations: Object.fromEntries(OPERATIONS.map((operation) => [operation, {
      ...artifactDigests(operationArtifacts, operation),
      checks: checks[operation]
    }]))
  };
  const replayResult = Buffer.from(JSON.stringify(replayResultValue), "utf8");
  const attestation = {
    schemaVersion: 1,
    status: "PASS",
    sourceCommit: SOURCE_COMMIT,
    catalogVersion: CATALOG_VERSION,
    panelPairSha256: PANEL_PAIR,
    backendAdapterSha256,
    recoveryCapabilitySha256:
      controlledRecoveryCapabilitySha256(adapter.operations.recovery),
    reconciliationContractSha256: CONTRACT_SHA,
    replayInputSha256: digest(replayInput),
    replayResultSha256: digest(replayResult),
    keyId: adapter.operations.recovery.keyId,
    signedUpgradeDeviceTest: true,
    operatorRecoveryDeviceTest: true,
    runtimeRecoveryWiringVerified: true,
    persistentChallengeConsumptionVerified: true,
    atomicRecoveryPersistenceVerified: true,
    legacyUncertainBlocked: true,
    normalUpgradeExactJournal: true,
    operations: Object.fromEntries(OPERATIONS.map((operation) => [operation, {
      ...artifactDigests(operationArtifacts, operation),
      checks: checks[operation]
    }])),
    attestationSignatureHex: "00"
  };
  attestation.attestationSignatureHex = signBytes(
    "RSA-SHA256",
    controlledRecoveryAttestationSignedBytes(attestation),
    signingKey.privateKey).toString("hex");
  return {
    adapter, attestation, signingKey, replayInput, replayResult, operationArtifacts
  };
}

function validate(value, overrides = {}) {
  return validateControlledRecoveryReleaseEvidence({
    adapter: overrides.adapter || value.adapter,
    reconciliationContractBytes: overrides.contract || CONTRACT,
    replayInputBytes: overrides.replayInput || value.replayInput,
    replayResultBytes: overrides.replayResult || value.replayResult,
    operationArtifacts: overrides.operationArtifacts || value.operationArtifacts,
    attestation: overrides.attestation || value.attestation,
    expectedSourceCommit: SOURCE_COMMIT,
    expectedCatalogVersion: CATALOG_VERSION,
    expectedPanelPairSha256: PANEL_PAIR
  });
}

const VERIFIER_PATH = new URL(
  "./controlled-recovery-attestation.mjs", import.meta.url).pathname;
const CLI_FAILURE_STDERR =
  "controlled recovery gate failed: CONTROLLED_RECOVERY_GATE_REJECTED\n";
let cliOutputSequence = 0;

function createCliFixture() {
  const value = fixture();
  const root = mkdtempSync(join(tmpdir(), "autoform-controlled-recovery-selftest."));
  const evidenceDir = join(root, "evidence");
  mkdirSync(evidenceDir, { mode: 0o700 });
  const paths = {
    adapter: join(root, "adapter.json"),
    contract: join(root, "contract.bin"),
    replayInput: join(root, "replay-input.json"),
    replayResult: join(root, "replay-result.json"),
    attestation: join(root, "attestation.json")
  };
  writeFileSync(paths.adapter, JSON.stringify(value.adapter), { mode: 0o600 });
  writeFileSync(paths.contract, CONTRACT, { mode: 0o600 });
  writeFileSync(paths.replayInput, value.replayInput, { mode: 0o600 });
  writeFileSync(paths.replayResult, value.replayResult, { mode: 0o600 });
  writeFileSync(paths.attestation, JSON.stringify(value.attestation), { mode: 0o600 });
  for (const operation of OPERATIONS) {
    for (const [key, suffix] of Object.entries(ARTIFACT_SUFFIX)) {
      writeFileSync(join(evidenceDir, `${ARTIFACT_PREFIX[operation]}.${suffix}`),
        value.operationArtifacts[operation][key], { mode: 0o600 });
    }
  }
  return { value, root, evidenceDir, paths };
}

function cliArguments(value, outputPath) {
  return [
    VERIFIER_PATH,
    "--adapter", value.paths.adapter,
    "--contract", value.paths.contract,
    "--replay-input", value.paths.replayInput,
    "--replay-result", value.paths.replayResult,
    "--attestation", value.paths.attestation,
    "--operation-evidence-dir", value.evidenceDir,
    "--source-commit", SOURCE_COMMIT,
    "--catalog-version", String(CATALOG_VERSION),
    "--panel-pair", PANEL_PAIR,
    "--output", outputPath
  ];
}

function runCli(value, { output, transformArguments } = {}) {
  const outputPath = output || join(
    value.root, `result-${cliOutputSequence += 1}.json`);
  const originalArguments = cliArguments(value, outputPath);
  const run = spawnSync(process.execPath,
    transformArguments ? transformArguments(originalArguments) : originalArguments,
    { encoding: "utf8" });
  return { run, outputPath };
}

function assertPrivateFailure(value, result) {
  assert.equal(result.run.status, 1, result.run.stdout);
  assert.equal(result.run.stdout, "");
  assert.equal(result.run.stderr, CLI_FAILURE_STDERR);
  assert.equal(result.run.stderr.includes(value.root), false);
  assert.equal(result.run.stderr.includes(
    "fictional-FINAL_SUBMISSION-server-correlation"), false);
  assert.equal(lstatExists(result.outputPath), false);
}

function lstatExists(path) {
  try {
    lstatSync(path);
    return true;
  } catch (error) {
    if (error?.code === "ENOENT" || error?.code === "ENOTDIR") return false;
    throw error;
  }
}

test("exact private recovery attestation passes without exposing its values", () => {
  const value = fixture();
  assert.deepEqual(validate(value), []);
});

test("missing operation proof and optimistic partial-upload classification fail", () => {
  const value = fixture();
  delete value.attestation.operations.PREVIOUS_STEP_RECIPE.checks.wrongRecipeRejected;
  value.attestation.operations.MULTIPART_UPLOAD.checks.partialKeepsLocked = false;
  const errors = validate(value);
  assert.ok(errors.includes(
    "attestation PREVIOUS_STEP_RECIPE checks are not exact"));
  assert.ok(errors.includes(
    "attestation MULTIPART_UPLOAD.partialKeepsLocked must be true"));
  assert.ok(errors.includes("attestation signature is invalid"));
});

test("adapter, Panel pair, source commit and private contract are exact bindings", () => {
  const changedAdapter = fixture();
  changedAdapter.adapter.operations.recovery.maxEvidenceAgeSeconds = 301;
  const adapterErrors = validate(changedAdapter);
  assert.ok(adapterErrors.includes(
    "attestation backendAdapterSha256 does not match"));
  assert.ok(adapterErrors.includes(
    "attestation recoveryCapabilitySha256 does not match"));

  const wrongPair = fixture();
  wrongPair.attestation.panelPairSha256 = "c".repeat(64);
  assert.ok(validate(wrongPair).includes(
    "attestation panelPairSha256 does not match"));

  const wrongContract = fixture();
  assert.ok(validate(wrongContract, {
    contract: Buffer.from("different-private-contract", "utf8")
  }).includes(
      "reconciliation contract digest does not match"));
});

test("raw and wrapped adapters normalize once and bind the whole normalized adapter", () => {
  const value = fixture();
  const raw = JSON.parse(JSON.stringify(value.adapter));
  assert.equal(
    controlledRecoveryBackendAdapterSha256(raw),
    controlledRecoveryBackendAdapterSha256({ backendAdapter: raw }));

  // This invalid-but-truthy field is a regression trap for the former second unwrap.
  raw.backendAdapter = { fictionalNestedValue: "must-not-replace-the-adapter-root" };
  const wrapped = { backendAdapter: raw };
  const normalizedDigest = controlledRecoveryBackendAdapterSha256(wrapped);
  value.attestation.backendAdapterSha256 = normalizedDigest;
  const baselineErrors = validate(value, { adapter: wrapped });
  assert.equal(baselineErrors.includes(
    "attestation backendAdapterSha256 does not match"), false);

  for (const mutate of [
    (adapter) => { adapter.endpoints.login = "/changed-login-binding"; },
    (adapter) => { adapter.operations.upload.multipartField = "changedUploadBinding"; }
  ]) {
    const changed = JSON.parse(JSON.stringify(wrapped));
    mutate(changed.backendAdapter);
    assert.notEqual(controlledRecoveryBackendAdapterSha256(changed), normalizedDigest);
    assert.ok(validate(value, { adapter: changed }).includes(
      "attestation backendAdapterSha256 does not match"));
  }
});

test("runtime wiring, persistence and signed-upgrade checks cannot be omitted or false", () => {
  const value = fixture();
  value.attestation.signedUpgradeDeviceTest = false;
  value.attestation.operatorRecoveryDeviceTest = false;
  value.attestation.runtimeRecoveryWiringVerified = false;
  value.attestation.persistentChallengeConsumptionVerified = false;
  value.attestation.atomicRecoveryPersistenceVerified = false;
  const errors = validate(value);
  assert.ok(errors.includes("attestation signedUpgradeDeviceTest must be true"));
  assert.ok(errors.includes("attestation operatorRecoveryDeviceTest must be true"));
  assert.ok(errors.includes("attestation runtimeRecoveryWiringVerified must be true"));
  assert.ok(errors.includes(
    "attestation persistentChallengeConsumptionVerified must be true"));
  assert.ok(errors.includes(
    "attestation atomicRecoveryPersistenceVerified must be true"));
  assert.ok(errors.includes("attestation signature is invalid"));
});

test("legacy uncertainty is required separately for all three operation kinds", () => {
  const value = fixture();
  value.attestation.legacyUncertainBlocked = false;
  value.attestation.operations.FINAL_SUBMISSION.checks.legacyUncertainBlocked = false;
  value.attestation.operations.PREVIOUS_STEP_RECIPE.checks.legacyUncertainBlocked = false;
  value.attestation.operations.MULTIPART_UPLOAD.checks.legacyUncertainBlocked = false;
  const errors = validate(value);
  assert.ok(errors.includes("attestation legacyUncertainBlocked must be true"));
  for (const operation of OPERATIONS) {
    assert.ok(errors.includes(
      `attestation ${operation}.legacyUncertainBlocked must be true`));
  }
  assert.ok(errors.includes("attestation signature is invalid"));
});

test("private replay bytes and signer prevent a hand-filled attestation", () => {
  const value = fixture();
  const wrongReplay = validate(value, {
    replayInput: Buffer.from("different", "utf8")
  });
  assert.ok(wrongReplay.includes("attestation replayInputSha256 does not match"));
  assert.ok(wrongReplay.includes("private replay input is not an exact JSON object"));

  value.attestation.operations.FINAL_SUBMISSION.checks.written = false;
  const handEdited = validate(value);
  assert.ok(handEdited.includes("attestation FINAL_SUBMISSION.written must be true"));
  assert.ok(handEdited.includes("attestation signature is invalid"));
});

test("server correlation, remote receipt and journal-pair cross-proof bytes are mandatory", () => {
  const value = fixture();
  value.operationArtifacts.FINAL_SUBMISSION.serverCorrelationBytes = Buffer.from(
    "different-fictional-correlation", "utf8");
  value.operationArtifacts.PREVIOUS_STEP_RECIPE.remoteReceiptBytes = Buffer.alloc(0);
  value.operationArtifacts.MULTIPART_UPLOAD.journalPairCrossProofBytes = Buffer.from(
    "different-fictional-cross-proof", "utf8");
  const errors = validate(value);
  assert.ok(errors.includes(
    "attestation FINAL_SUBMISSION.serverCorrelationSha256 does not match private evidence"));
  assert.ok(errors.includes(
    "private PREVIOUS_STEP_RECIPE.remoteReceiptBytes is empty"));
  assert.ok(errors.includes(
    "attestation MULTIPART_UPLOAD.journalPairCrossProofSha256 does not match private evidence"));
});

test("replay result must bind exact replay input and per-operation evidence", () => {
  const value = fixture();
  const result = JSON.parse(value.replayResult.toString("utf8"));
  result.replayInputSha256 = "c".repeat(64);
  result.operations.MULTIPART_UPLOAD.legacyUncertainFixtureSha256 = "d".repeat(64);
  const errors = validate(value, {
    replayResult: Buffer.from(JSON.stringify(result), "utf8")
  });
  assert.ok(errors.includes(
    "private replay result does not bind the exact replay input"));
  assert.ok(errors.includes(
    "private replay result MULTIPART_UPLOAD.legacyUncertainFixtureSha256 does not match private evidence"));
});

test("CLI atomically creates one mode-0600 aggregate output without leaking values", () => {
  const value = createCliFixture();
  try {
    const { run, outputPath } = runCli(value);
    assert.equal(run.status, 0, run.stderr);
    assert.equal(run.stdout, "");
    assert.deepEqual(JSON.parse(readFileSync(outputPath, "utf8")), {
      ok: true,
      operationCount: 3,
      privateArtifactCount: 12
    });
    const outputStat = lstatSync(outputPath);
    assert.equal(outputStat.isFile(), true);
    assert.equal(outputStat.isSymbolicLink(), false);
    assert.equal(outputStat.mode & 0o7777, 0o600);
    assert.equal(readdirSync(value.root).some((name) => name.endsWith(".tmp")), false);
    const output = `${run.stdout}${run.stderr}${readFileSync(outputPath, "utf8")}`;
    assert.equal(output.includes(value.root), false);
    assert.equal(output.includes("fictional-FINAL_SUBMISSION-server-correlation"), false);
  } finally {
    rmSync(value.root, { recursive: true, force: true });
  }
});

test("CLI rejects every top-level private input unless its mode is exactly 0600", () => {
  const value = createCliFixture();
  try {
    for (const path of Object.values(value.paths)) {
      chmodSync(path, 0o644);
      const result = runCli(value);
      assertPrivateFailure(value, result);
      chmodSync(path, 0o600);
    }
  } finally {
    rmSync(value.root, { recursive: true, force: true });
  }
});

test("CLI rejects mode-0644 artifacts and a mode-0755 evidence directory", () => {
  const value = createCliFixture();
  try {
    const artifact = join(
      value.evidenceDir, "final-submission.server-correlation.bin");
    chmodSync(artifact, 0o644);
    const artifactResult = runCli(value);
    assertPrivateFailure(value, artifactResult);
    chmodSync(artifact, 0o600);

    chmodSync(value.evidenceDir, 0o755);
    const directoryResult = runCli(value);
    assertPrivateFailure(value, directoryResult);
  } finally {
    rmSync(value.root, { recursive: true, force: true });
  }
});

test("CLI rejects special permission bits instead of treating them as exact modes", () => {
  const value = createCliFixture();
  try {
    // macOS clears setuid/setgid on ordinary data files, but preserves sticky.
    chmodSync(value.paths.attestation, 0o1600);
    const fileResult = runCli(value);
    assertPrivateFailure(value, fileResult);
    chmodSync(value.paths.attestation, 0o600);

    chmodSync(value.evidenceDir, 0o1700);
    const directoryResult = runCli(value);
    assertPrivateFailure(value, directoryResult);
  } finally {
    rmSync(value.root, { recursive: true, force: true });
  }
});

test("CLI rejects attestation, artifact and evidence-directory symlinks", () => {
  const attestationValue = createCliFixture();
  try {
    const realAttestation = `${attestationValue.paths.attestation}.real`;
    renameSync(attestationValue.paths.attestation, realAttestation);
    symlinkSync(realAttestation, attestationValue.paths.attestation);
    assertPrivateFailure(attestationValue, runCli(attestationValue));
  } finally {
    rmSync(attestationValue.root, { recursive: true, force: true });
  }

  const artifactValue = createCliFixture();
  try {
    const artifact = join(
      artifactValue.evidenceDir, "multipart-upload.remote-receipt.bin");
    const realArtifact = `${artifact}.real`;
    renameSync(artifact, realArtifact);
    symlinkSync(realArtifact, artifact);
    assertPrivateFailure(artifactValue, runCli(artifactValue));
  } finally {
    rmSync(artifactValue.root, { recursive: true, force: true });
  }

  const directoryValue = createCliFixture();
  try {
    const realEvidence = `${directoryValue.evidenceDir}.real`;
    renameSync(directoryValue.evidenceDir, realEvidence);
    symlinkSync(realEvidence, directoryValue.evidenceDir, "dir");
    const result = runCli(directoryValue);
    assertPrivateFailure(directoryValue, result);
  } finally {
    rmSync(directoryValue.root, { recursive: true, force: true });
  }
});

test("an input path replaced after open fails closed even with identical bytes", () => {
  const root = mkdtempSync(join(tmpdir(), "autoform-controlled-recovery-race."));
  try {
    const path = join(root, "private.bin");
    const displaced = join(root, "private.original.bin");
    const contents = Buffer.from("same-private-bytes", "utf8");
    writeFileSync(path, contents, { mode: 0o600 });
    assert.throws(() => readPrivateFile(path, "race fixture", {
      afterOpen() {
        renameSync(path, displaced);
        writeFileSync(path, contents, { mode: 0o600 });
      }
    }), /stable bounded regular private file with mode 0600/u);
  } finally {
    rmSync(root, { recursive: true, force: true });
  }
});

test("an evidence-directory ABA replacement is detected before acceptance", () => {
  const value = createCliFixture();
  try {
    const displaced = `${value.evidenceDir}.displaced`;
    assert.throws(() => readOperationArtifacts(value.evidenceDir, {
      afterOpen() {
        renameSync(value.evidenceDir, displaced);
        mkdirSync(value.evidenceDir, { mode: 0o700 });
        rmSync(value.evidenceDir, { recursive: true });
        renameSync(displaced, value.evidenceDir);
      }
    }), /operation evidence directory changed/u);
  } finally {
    rmSync(value.root, { recursive: true, force: true });
  }
});

test("CLI rejects missing, duplicate and unknown arguments before creating output", () => {
  const value = createCliFixture();
  try {
    const missing = runCli(value, {
      transformArguments: (args) => args.slice(0, -2)
    });
    assertPrivateFailure(value, missing);

    const duplicate = runCli(value, {
      transformArguments: (args) => [...args, "--output", join(value.root, "other.json")]
    });
    assertPrivateFailure(value, duplicate);

    const unknown = runCli(value, {
      transformArguments: (args) => [...args, "--unexpected", "value"]
    });
    assertPrivateFailure(value, unknown);
  } finally {
    rmSync(value.root, { recursive: true, force: true });
  }
});

test("CLI never echoes an unknown private adapter field name", () => {
  const value = createCliFixture();
  const unknownPrivateField = "fictionalUnknownPrivateFieldZXQ91";
  try {
    const adapter = JSON.parse(readFileSync(value.paths.adapter, "utf8"));
    adapter[unknownPrivateField] = "fictional-private-value-never-log";
    writeFileSync(value.paths.adapter, JSON.stringify(adapter), { mode: 0o600 });
    const result = runCli(value);
    assertPrivateFailure(value, result);
    assert.equal(result.run.stderr.includes(unknownPrivateField), false);
    assert.equal(result.run.stderr.includes(adapter[unknownPrivateField]), false);
  } finally {
    rmSync(value.root, { recursive: true, force: true });
  }
});

test("atomic output refuses an existing symlink and never changes its target", () => {
  const value = createCliFixture();
  try {
    const target = join(value.root, "existing-private-result.json");
    const outputPath = join(value.root, "result-link.json");
    const sentinel = Buffer.from("do-not-overwrite\n", "utf8");
    writeFileSync(target, sentinel, { mode: 0o600 });
    symlinkSync(target, outputPath);
    const result = runCli(value, { output: outputPath });
    assert.equal(result.run.status, 1);
    assert.equal(result.run.stdout, "");
    assert.equal(result.run.stderr, CLI_FAILURE_STDERR);
    assert.equal(lstatSync(outputPath).isSymbolicLink(), true);
    assert.deepEqual(readFileSync(target), sentinel);
    assert.equal(readdirSync(value.root).some((name) => name.endsWith(".tmp")), false);
  } finally {
    rmSync(value.root, { recursive: true, force: true });
  }
});

test("atomic output refuses an existing regular file without changing it", () => {
  const value = createCliFixture();
  try {
    const outputPath = join(value.root, "existing-result.json");
    const sentinel = Buffer.from("existing-result-must-remain\n", "utf8");
    writeFileSync(outputPath, sentinel, { mode: 0o600 });
    const result = runCli(value, { output: outputPath });
    assert.equal(result.run.status, 1);
    assert.equal(result.run.stderr, CLI_FAILURE_STDERR);
    assert.deepEqual(readFileSync(outputPath), sentinel);
    assert.equal(lstatSync(outputPath).mode & 0o7777, 0o600);
    assert.equal(readdirSync(value.root).some((name) => name.endsWith(".tmp")), false);
  } finally {
    rmSync(value.root, { recursive: true, force: true });
  }
});

test("atomic output rejects a symlinked or non-directory parent", () => {
  const value = createCliFixture();
  try {
    const realParent = join(value.root, "real-output-parent");
    const linkedParent = join(value.root, "linked-output-parent");
    mkdirSync(realParent, { mode: 0o700 });
    symlinkSync(realParent, linkedParent, "dir");
    const symlinkOutput = join(linkedParent, "result.json");
    const symlinkResult = runCli(value, { output: symlinkOutput });
    assertPrivateFailure(value, symlinkResult);
    assert.equal(lstatExists(join(realParent, "result.json")), false);

    const fileParent = join(value.root, "not-a-directory");
    writeFileSync(fileParent, "fixture\n", { mode: 0o600 });
    const boundaryOutput = join(fileParent, "result.json");
    const boundaryResult = runCli(value, { output: boundaryOutput });
    assertPrivateFailure(value, boundaryResult);
    assert.deepEqual(readFileSync(fileParent, "utf8"), "fixture\n");
  } finally {
    rmSync(value.root, { recursive: true, force: true });
  }
});

test("output-directory ABA and post-publish replacement both fail closed", () => {
  const parent = mkdtempSync(join(tmpdir(), "autoform-controlled-recovery-output-race."));
  try {
    const directory = join(parent, "private-output");
    const displacedDirectory = join(parent, "private-output.displaced");
    mkdirSync(directory, { mode: 0o700 });
    const directoryRaceOutput = join(directory, "directory-race.json");
    assert.throws(() => writePrivateOutputAtomically(
      directoryRaceOutput,
      { ok: true },
      {
        afterDirectoryOpen() {
          renameSync(directory, displacedDirectory);
          mkdirSync(directory, { mode: 0o700 });
          rmSync(directory, { recursive: true });
          renameSync(displacedDirectory, directory);
        }
      }), /not atomically created/u);
    assert.equal(lstatExists(directoryRaceOutput), false);

    const lateOutput = join(directory, "late-race.json");
    const displacedOutput = join(directory, "late-race.original.json");
    const replacement = Buffer.from('{"ok":true}\n', "utf8");
    assert.throws(() => writePrivateOutputAtomically(
      lateOutput,
      { ok: true },
      {
        afterPublish() {
          renameSync(lateOutput, displacedOutput);
          writeFileSync(lateOutput, replacement, { mode: 0o600 });
        }
      }), /not atomically created/u);
    assert.deepEqual(readFileSync(lateOutput), replacement);
    assert.equal(lstatSync(displacedOutput).mode & 0o777, 0o600);
    assert.equal(readdirSync(directory).some((name) => name.endsWith(".tmp")), false);
  } finally {
    rmSync(parent, { recursive: true, force: true });
  }
});
