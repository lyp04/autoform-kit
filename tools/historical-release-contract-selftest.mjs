#!/usr/bin/env node

import assert from "node:assert/strict";
import { mkdtempSync, writeFileSync, chmodSync, linkSync, symlinkSync } from "node:fs";
import os from "node:os";
import path from "node:path";
import { spawnSync } from "node:child_process";
import test from "node:test";
import {
  canonicalJson,
  createHistoricalInventory,
  digest,
  HISTORICAL_TAGS,
  historicalInventoryIdentity,
  historicalReleaseIdentity,
  loadHistoricalInventory,
  selectHistoricalRelease,
  HISTORICAL_BODY,
  HISTORICAL_PUBLICATION_MODE,
  PRIVATE_HISTORY_AUDIT_KIND,
  PRIVATE_HISTORY_ATTESTATION_KIND,
  privateHistoryAttestationIdentity,
  privateHistoryAuditIdentity,
  validatePrivateHistoryAttestation,
  validateHistoricalCandidate,
  validateHistoricalInventory,
} from "./historical-release-contract.mjs";

const HEX = (character) => character.repeat(64);

function fixture() {
  const releases = HISTORICAL_TAGS.map((tag, sequence) => ({
    assets: [
      { name: `autoform-kit-${tag.slice(1)}.apk`, size: 100 + sequence, sha256: HEX("a") },
      { name: "update.json", size: 40 + sequence, sha256: HEX("b") },
    ].sort((left, right) => left.name.localeCompare(right.name)),
    draft: false,
    prerelease: false,
    tagName: tag,
    titleLength: 20,
    titleSha256: HEX("c"),
  }));
  const sourceInventory = {
    schemaVersion: 1,
    kind: "public-rewrite-inventory",
    tagCount: 7,
    releaseCount: 7,
    assetCount: 14,
    stableIdentitySha256: HEX("d"),
    inventorySha256: HEX("e"),
    sourceBindings: {
      gitTagRefListingSha256: HEX("2"),
      releaseMetadataSha256: HEX("3"),
    },
    releases,
  };
  const apkIdentities = HISTORICAL_TAGS.map((tag, sequence) => ({
    tag,
    packageName: "com.example.autoform",
    versionCode: sequence + 1,
    versionName: tag.slice(1),
    signerSha256: HEX("f"),
  }));
  const updateIdentities = HISTORICAL_TAGS.map((tag, sequence) => ({
    tag,
    packageName: "com.example.autoform",
    versionCode: sequence + 1,
    versionName: tag.slice(1),
    apkAsset: `autoform-kit-${tag.slice(1)}.apk`,
    apkSha256: HEX("a"),
    notesLength: 10,
    notesSha256: HEX("4"),
  }));
  return createHistoricalInventory({
    sourceFileSha256: HEX("1"), sourceInventory, apkIdentities, updateIdentities,
  });
}

function clone(value) {
  return JSON.parse(JSON.stringify(value));
}

function privateHistoryAttestationFixture() {
  const bindings = {
    metadataBindingSha256: HEX("1"),
    pullRefIdentitySha256: HEX("2"),
    refApiInputSha256: HEX("3"),
    refApiSnapshotSha256: HEX("4"),
    refIdentitySha256: HEX("5"),
    releaseApiInputSha256: HEX("6"),
    releaseApiSnapshotSha256: HEX("7"),
    remoteRefsInputSha256: HEX("8"),
    remoteRefsRawSnapshotSha256: HEX("9"),
    repositoryBindingSha256: HEX("a"),
  };
  const expected = {
    ...bindings,
    excludedReleaseTag: "v1.0.0",
    privateWordlistSetSha256: HEX("b"),
    reachableObjectClosureSha256: HEX("c"),
    reachableObjectCount: 17,
    scannerSha256: HEX("d"),
    wordlistCount: 1,
  };
  const auditReport = {
    auditMode: "historical-incremental",
    auditorSha256: HEX("e"),
    bindings,
    excludedReleaseTag: expected.excludedReleaseTag,
    findingCount: 0,
    findings: [],
    kind: PRIVATE_HISTORY_AUDIT_KIND,
    matchCount: 0,
    matches: [],
    ok: true,
    privateWordlistSetSha256: expected.privateWordlistSetSha256,
    problemCount: 0,
    problemPaths: [],
    reachableObjectClosureSha256: expected.reachableObjectClosureSha256,
    reachableObjectCount: expected.reachableObjectCount,
    reportSha256: "",
    scannerSha256: expected.scannerSha256,
    version: 1,
    wordlistCount: expected.wordlistCount,
  };
  auditReport.reportSha256 = privateHistoryAuditIdentity(auditReport);
  const attestation = {
    auditReport,
    auditReportFileSha256: digest(Buffer.from(`${canonicalJson(auditReport)}\n`, "utf8")),
    kind: PRIVATE_HISTORY_ATTESTATION_KIND,
    releaseReady: true,
    reportSha256: "",
    schemaVersion: 1,
  };
  attestation.reportSha256 = privateHistoryAttestationIdentity(attestation);
  return { attestation, expected };
}

function rebindRelease(value, index) {
  value.releases[index].releaseIdentitySha256 = historicalReleaseIdentity(value.releases[index]);
  value.inventorySha256 = historicalInventoryIdentity(value);
}

function candidate(inventory, sequence = 0) {
  const original = inventory.releases[sequence];
  const apkSha = HEX("5");
  const updateSha = HEX("6");
  const notesSha = HEX("7");
  const provenance = {
    apkMatchedDexStringCount: 1,
    applicationDexStrict: true,
    compiledOutputCount: 1,
    declaredDexStringCount: 1,
    dexSourceArtifactCount: 1,
    dexSourceEntryCount: 1,
    manifestFile: "tools/apk-third-party-components.json",
    manifestSha256: HEX("8"),
    matchedEntryCount: 1,
    mergedSourceCount: 1,
    profileId: `release-v1.0.${sequence}`,
    runtimeLockFile: "tools/android-runtime-dependencies.lock.json",
    runtimeLockSha256: HEX("9"),
    sourceArtifactCount: 1,
    sourceEntryCount: 1,
    sourceMatchedDexStringCount: 1,
    sourceReportSha256: HEX("1"),
    sourceVerifierFile: "tools/verify-apk-third-party-sources.mjs",
    sourceVerifierSha256: HEX("2"),
  };
  const title = "T".repeat(original.titleLength);
  original.titleSha256 = digest(Buffer.from(title));
  original.releaseIdentitySha256 = historicalReleaseIdentity(original);
  inventory.inventorySha256 = historicalInventoryIdentity(inventory);
  return {
    schemaVersion: 4,
    publicationMode: HISTORICAL_PUBLICATION_MODE,
    tag: original.tag,
    source: { branch: "main", commit: "c".repeat(40), workingTreeClean: true },
    app: {
      packageName: original.originalApk.packageName,
      signerSha256: original.originalApk.signerSha256,
      versionCode: original.originalApk.versionCode,
      versionName: original.originalApk.versionName,
    },
    artifacts: {
      apk: { file: original.originalApk.assetName, sha256: apkSha },
      notes: { file: "release-notes.txt", sha256: notesSha },
      update: { file: "update.json", sha256: updateSha },
    },
    historicalRelease: {
      inventory: {
        fileSha256: HEX("0"),
        inventorySha256: inventory.inventorySha256,
        selectedReleaseIdentitySha256: original.releaseIdentitySha256,
      },
      publication: {
        assets: [
          { file: original.originalApk.assetName, name: original.originalApk.assetName,
            sha256: apkSha, size: 200 },
          { file: "update.json", name: "update.json", sha256: updateSha, size: 80 },
        ].sort((left, right) => left.name.localeCompare(right.name)),
        body: HISTORICAL_BODY,
        bodyPolicy: "fixed-source-bound-generic-v1",
        draft: original.draft,
        makeLatest: false,
        prerelease: original.prerelease,
        title,
      },
    },
    lineage: {
      kind: sequence === 0 ? "historical-initial-rebuild" : "historical-upgrade-rebuild",
      sequence,
      previousRebuiltCandidate: sequence === 0 ? null : {
        apkSha256: HEX("3"),
        candidateManifestSha256: HEX("4"),
        packageName: original.originalApk.packageName,
        signerSha256: original.originalApk.signerSha256,
        tag: HISTORICAL_TAGS[sequence - 1],
        versionCode: inventory.releases[sequence - 1].originalApk.versionCode,
        versionName: HISTORICAL_TAGS[sequence - 1].slice(1),
      },
    },
    publicAudit: {
      apk: { inputSha256: apkSha, reportSha256: HEX("a"),
        zipEntryManifestSha256: HEX("b") },
      policySha256: HEX("c"),
      releaseMetadata: {
        notes: { inputSha256: notesSha, reportSha256: HEX("d") },
        update: { inputSha256: updateSha, reportSha256: HEX("e") },
      },
      scannerSha256: HEX("f"),
      sourceTree: { gitTreeOid: "d".repeat(40), inputSha256: HEX("1"),
        reportSha256: HEX("2") },
      thirdPartyProvenance: provenance,
      worktree: { inputSha256: HEX("3"), reportSha256: HEX("4") },
    },
  };
}

test("strict seven-release inventory is self-bound", () => {
  const inventory = fixture();
  assert.equal(validateHistoricalInventory(inventory), inventory);
  assert.equal(selectHistoricalRelease(inventory, "v1.0.0").originalApk.versionCode, 1);
  assert.equal(selectHistoricalRelease(inventory, "v1.0.6").originalApk.versionCode, 7);
});

test("release metadata and original APK identity cannot drift", () => {
  for (const mutate of [
    (value) => { value.releases[0].titleLength += 1; },
    (value) => { value.releases[0].draft = true; },
    (value) => { value.releases[0].assets[0].name = "different.apk"; },
    (value) => { value.releases[0].originalApk.sha256 = HEX("9"); },
    (value) => { value.releases[0].originalApk.packageName = "com.example.other"; },
    (value) => { value.releases[0].originalApk.signerSha256 = HEX("8"); },
    (value) => { value.releases[0].originalUpdate.apkSha256 = HEX("8"); },
  ]) {
    const value = clone(fixture());
    mutate(value);
    assert.throws(() => validateHistoricalInventory(value));
  }
});

test("even fully rebound inventory rejects ambiguous lineage", () => {
  const cases = [
    (value) => { value.releases[0].originalApk.versionCode = 2; },
    (value) => { value.releases[2].originalApk.versionCode = 2; },
    (value) => { value.releases[2].originalApk.versionName = "1.0.9"; },
    (value) => { value.releases[2].originalApk.signerSha256 = HEX("7"); },
    (value) => { value.releases[2].tag = "v1.0.4"; },
  ];
  for (const mutate of cases) {
    const value = clone(fixture());
    mutate(value);
    for (let index = 0; index < value.releases.length; index += 1) {
      value.releases[index].releaseIdentitySha256 = historicalReleaseIdentity(value.releases[index]);
    }
    value.inventorySha256 = historicalInventoryIdentity(value);
    assert.throws(() => validateHistoricalInventory(value));
  }
});

test("unknown fields and a third asset fail closed", () => {
  const unknown = clone(fixture());
  unknown.releases[0].unexpected = true;
  rebindRelease(unknown, 0);
  assert.throws(() => validateHistoricalInventory(unknown));
  const asset = clone(fixture());
  asset.releases[0].assets.push({
    kind: "update-manifest", name: "extra.json", sha256: HEX("6"), size: 1,
  });
  rebindRelease(asset, 0);
  assert.throws(() => validateHistoricalInventory(asset));
});

test("inventory file must be exact mode 0600 and pinned when requested", () => {
  const directory = mkdtempSync(path.join(os.tmpdir(), "autoform-history-contract."));
  const filename = path.join(directory, "inventory.json");
  const bytes = Buffer.from(`${canonicalJson(fixture())}\n`, "utf8");
  writeFileSync(filename, bytes, { mode: 0o600 });
  const loaded = loadHistoricalInventory(filename, digest(bytes));
  assert.equal(loaded.fileSha256, digest(bytes));
  assert.throws(() => loadHistoricalInventory(filename, HEX("0")));
  chmodSync(filename, 0o644);
  assert.throws(() => loadHistoricalInventory(filename));
  chmodSync(filename, 0o600);
  const link = path.join(directory, "inventory-link.json");
  symlinkSync(filename, link);
  assert.throws(() => loadHistoricalInventory(link));
  const hardlink = path.join(directory, "inventory-hardlink.json");
  linkSync(filename, hardlink);
  assert.throws(() => loadHistoricalInventory(filename));
});

test("schema-4 candidate has an unambiguous non-latest publication and lineage", () => {
  for (const sequence of [0, 1, 6]) {
    const inventory = fixture();
    const manifest = candidate(inventory, sequence);
    assert.equal(validateHistoricalCandidate(manifest, inventory, HEX("0")), manifest);
    assert.equal(Object.hasOwn(manifest.historicalRelease.inventory, "sourceInventory"), false);
    assert.equal(Object.hasOwn(manifest.historicalRelease, "original"), false);
    assert.equal(Object.hasOwn(manifest.lineage, "originalApk"), false);
  }
});

test("historical title binding uses UTF-8 bytes and Unicode code points without shell ambiguity", () => {
  const inventory = fixture();
  const manifest = candidate(inventory, 0);
  const title = "通用表单 🚀 1.0.0";
  const original = inventory.releases[0];
  original.titleLength = [...title].length;
  original.titleSha256 = digest(Buffer.from(title, "utf8"));
  original.releaseIdentitySha256 = historicalReleaseIdentity(original);
  inventory.inventorySha256 = historicalInventoryIdentity(inventory);
  manifest.historicalRelease.inventory.inventorySha256 = inventory.inventorySha256;
  manifest.historicalRelease.inventory.selectedReleaseIdentitySha256 =
    original.releaseIdentitySha256;
  manifest.historicalRelease.publication.title = title;
  assert.equal(validateHistoricalCandidate(manifest, inventory, HEX("0")), manifest);
});

test("private full-history attestation is self-bound to exact refs, metadata and closure", () => {
  const { attestation, expected } = privateHistoryAttestationFixture();
  assert.equal(validatePrivateHistoryAttestation(attestation, expected), attestation);
  for (const mutate of [
    (value) => { value.releaseReady = false; },
    (value) => { value.auditReport.ok = false; },
    (value) => { value.auditReport.auditMode = "final"; },
    (value) => { value.auditReport.excludedReleaseTag = "v1.0.1"; },
    (value) => { value.auditReport.excludedReleaseTag = "v9.9.9"; },
    (value) => { delete value.auditReport.excludedReleaseTag; },
    (value) => { value.auditReport.problemCount = 1; },
    (value) => { value.auditReport.problemPaths = ["redacted"]; },
    (value) => { value.auditReport.findingCount = 1; },
    (value) => { value.auditReport.findings = [{}]; },
    (value) => { value.auditReport.matchCount = 1; },
    (value) => { value.auditReport.matches = [{}]; },
    (value) => { value.auditReport.bindings.pullRefIdentitySha256 = HEX("f"); },
    (value) => { value.auditReport.reachableObjectCount += 1; },
    (value) => { value.auditReport.reachableObjectClosureSha256 = HEX("f"); },
    (value) => { value.auditReport.privateWordlistSetSha256 = HEX("f"); },
    (value) => { value.auditReport.reportSha256 = HEX("f"); },
    (value) => { value.auditReportFileSha256 = HEX("f"); },
    (value) => { value.reportSha256 = HEX("f"); },
    (value) => { value.unexpected = true; },
  ]) {
    const changed = clone(attestation);
    mutate(changed);
    assert.throws(() => validatePrivateHistoryAttestation(changed, expected));
  }
  const wrongExpected = clone(expected);
  wrongExpected.remoteRefsRawSnapshotSha256 = HEX("f");
  assert.throws(() => validatePrivateHistoryAttestation(attestation, wrongExpected));
  const wrongTagExpected = clone(expected);
  wrongTagExpected.excludedReleaseTag = "v1.0.1";
  assert.throws(() => validatePrivateHistoryAttestation(attestation, wrongTagExpected));
});

test("historical candidate rejects routing, body, asset and predecessor confusion", () => {
  for (const mutate of [
    (value) => { value.schemaVersion = 2; },
    (value) => { value.publicationMode = "stable"; },
    (value) => { value.historicalRelease.publication.makeLatest = true; },
    (value) => { value.historicalRelease.publication.body = "old body"; },
    (value) => { value.historicalRelease.publication.assets.pop(); },
    (value) => { value.lineage.previousRebuiltCandidate.tag = "v1.0.0"; },
    (value) => { value.historicalRelease.inventory.fileSha256 = HEX("9"); },
    (value) => { value.historicalRelease.inventory.selectedReleaseIdentitySha256 = HEX("9"); },
    (value) => { value.historicalRelease.inventory.sourceInventory = {}; },
    (value) => { value.historicalRelease.original = {}; },
    (value) => { value.lineage.originalApk = {}; },
  ]) {
    const inventory = fixture();
    const manifest = candidate(inventory, 2);
    mutate(manifest);
    assert.throws(() => validateHistoricalCandidate(manifest, inventory, HEX("0")));
  }
});

test("CLI rejection is value-free", () => {
  const marker = "private-marker-that-must-not-be-echoed";
  const result = spawnSync(process.execPath, [
    path.resolve("tools/historical-release-contract.mjs"),
    "--inventory", marker,
    "--tag", "v1.0.0",
  ], { encoding: "utf8" });
  assert.notEqual(result.status, 0);
  assert.equal(result.stdout, "");
  assert.equal(result.stderr.includes(marker), false);
});
