#!/usr/bin/env node

/**
 * Strict, value-safe contracts for the one-time v1.0.0-v1.0.6 history rebuild.
 *
 * The inventory is private because it binds the original public assets. Candidate manifests may
 * copy only hashes, counts, flags and reviewed public text from it. All validation errors are
 * deliberately value-free so a rejected private inventory is never echoed into logs.
 */

import crypto from "node:crypto";
import fs, { constants } from "node:fs";
import path from "node:path";
import process from "node:process";
import { fileURLToPath } from "node:url";

const THIS_FILE = fileURLToPath(import.meta.url);
const MAX_PRIVATE_BYTES = 16 * 1024 * 1024;
const HEX_64 = /^[a-f0-9]{64}$/u;
const SAFE_ASSET = /^[A-Za-z0-9][A-Za-z0-9._-]{0,179}$/u;
const PACKAGE = /^[A-Za-z][A-Za-z0-9_]*(?:\.[A-Za-z][A-Za-z0-9_]*)+$/u;

export const HISTORICAL_TAGS = Object.freeze(
  Array.from({ length: 7 }, (_, index) => `v1.0.${index}`),
);
export const HISTORICAL_PUBLICATION_MODE = "historical-rewrite-non-latest";
export const HISTORICAL_BODY =
  "Sanitized historical rebuild from the public autoform-kit framework. "
  + "No site-specific configuration is included.";
export const PRIVATE_HISTORY_AUDIT_KIND =
  "autoform-private-public-history-audit";
export const PRIVATE_HISTORY_ATTESTATION_KIND =
  "autoform-private-full-history-publication-attestation";

function fail() {
  throw new Error("historical release contract validation failed");
}

function isObject(value) {
  return value !== null && typeof value === "object" && !Array.isArray(value);
}

function exactKeys(value, keys) {
  return isObject(value)
    && JSON.stringify(Object.keys(value).sort()) === JSON.stringify([...keys].sort());
}

function string(value, { empty = false, maximum = 4096 } = {}) {
  if (typeof value !== "string" || (!empty && value.length === 0)
      || value.length > maximum || /[\u0000]/u.test(value)) fail();
  return value;
}

function regularString(value, maximum = 4096) {
  const text = string(value, { maximum });
  if (/[\u0001-\u001f\u007f]/u.test(text)) fail();
  return text;
}

function positiveInteger(value) {
  if (!Number.isSafeInteger(value) || value <= 0) fail();
  return value;
}

function nonnegativeInteger(value) {
  if (!Number.isSafeInteger(value) || value < 0) fail();
  return value;
}

function sha(value) {
  if (typeof value !== "string" || !HEX_64.test(value)) fail();
  return value;
}

export function canonical(value) {
  if (Array.isArray(value)) return value.map(canonical);
  if (isObject(value)) {
    return Object.fromEntries(Object.keys(value).sort()
      .map((key) => [key, canonical(value[key])]));
  }
  return value;
}

export function canonicalJson(value) {
  return JSON.stringify(canonical(value));
}

export function digest(value) {
  return crypto.createHash("sha256").update(value).digest("hex");
}

function domainDigest(domain, value) {
  return digest(Buffer.from(`${domain}\n${canonicalJson(value)}`, "utf8"));
}

const PRIVATE_HISTORY_BINDING_KEYS = Object.freeze([
  "metadataBindingSha256",
  "pullRefIdentitySha256",
  "refApiInputSha256",
  "refApiSnapshotSha256",
  "refIdentitySha256",
  "releaseApiInputSha256",
  "releaseApiSnapshotSha256",
  "remoteRefsInputSha256",
  "remoteRefsRawSnapshotSha256",
  "repositoryBindingSha256",
]);

function privateHistoryAuditBase(value) {
  const { reportSha256: ignored, ...base } = value;
  return base;
}

export function privateHistoryAuditIdentity(value) {
  return domainDigest(
    "autoform-kit/private-full-history-publication-audit/v1",
    privateHistoryAuditBase(value),
  );
}

function privateHistoryAttestationBase(value) {
  const { reportSha256: ignored, ...base } = value;
  return base;
}

export function privateHistoryAttestationIdentity(value) {
  return domainDigest(
    "autoform-kit/private-full-history-publication-attestation/v1",
    privateHistoryAttestationBase(value),
  );
}

export function privateWordlistSetIdentity(wordlists) {
  if (!Array.isArray(wordlists) || wordlists.length === 0) fail();
  const entries = wordlists.map((entry) => {
    if (!exactKeys(entry, ["sha256", "size"])) fail();
    sha(entry.sha256);
    positiveInteger(entry.size);
    return { sha256: entry.sha256, size: entry.size };
  }).sort((left, right) => left.sha256.localeCompare(right.sha256)
    || left.size - right.size);
  if (new Set(entries.map((entry) => `${entry.sha256}:${entry.size}`)).size !== entries.length) {
    fail();
  }
  return domainDigest("autoform-kit/private-wordlist-set/v1", entries);
}

/**
 * Validate the private proof that every object reachable from the exact public ref envelope was
 * audited. The expected object is computed independently by the publisher from a fresh isolated
 * fetch; it is never copied from this attestation.
 */
export function validatePrivateHistoryAttestation(value, expected) {
  if (!exactKeys(value, [
    "auditReport", "auditReportFileSha256", "kind", "releaseReady", "reportSha256",
    "schemaVersion",
  ]) || value.schemaVersion !== 1 || value.kind !== PRIVATE_HISTORY_ATTESTATION_KIND
      || value.releaseReady !== true || !isObject(value.auditReport)
      || !exactKeys(expected, [
        ...PRIVATE_HISTORY_BINDING_KEYS,
        "excludedReleaseTag", "privateWordlistSetSha256", "reachableObjectClosureSha256",
        "reachableObjectCount", "scannerSha256", "wordlistCount",
      ]) || !HISTORICAL_TAGS.includes(expected.excludedReleaseTag)) fail();
  const audit = value.auditReport;
  if (!exactKeys(audit, [
    "auditMode", "auditorSha256", "bindings", "excludedReleaseTag", "findingCount", "findings",
    "kind", "matchCount", "matches", "ok", "privateWordlistSetSha256", "problemCount",
    "problemPaths", "reachableObjectClosureSha256", "reachableObjectCount", "reportSha256",
    "scannerSha256", "version", "wordlistCount",
  ]) || audit.version !== 1 || audit.kind !== PRIVATE_HISTORY_AUDIT_KIND
      || audit.auditMode !== "historical-incremental"
      || audit.excludedReleaseTag !== expected.excludedReleaseTag
      || !HISTORICAL_TAGS.includes(audit.excludedReleaseTag)
      || audit.ok !== true || audit.problemCount !== 0 || audit.findingCount !== 0
      || audit.matchCount !== 0 || !Array.isArray(audit.problemPaths)
      || audit.problemPaths.length !== 0 || !Array.isArray(audit.findings)
      || audit.findings.length !== 0 || !Array.isArray(audit.matches)
      || audit.matches.length !== 0 || !exactKeys(audit.bindings, PRIVATE_HISTORY_BINDING_KEYS)) {
    fail();
  }
  for (const field of PRIVATE_HISTORY_BINDING_KEYS) {
    sha(audit.bindings[field]);
    if (audit.bindings[field] !== expected[field]) fail();
  }
  for (const field of [
    "auditorSha256", "privateWordlistSetSha256", "reachableObjectClosureSha256",
    "reportSha256", "scannerSha256",
  ]) sha(audit[field]);
  positiveInteger(audit.reachableObjectCount);
  positiveInteger(audit.wordlistCount);
  if (audit.privateWordlistSetSha256 !== expected.privateWordlistSetSha256
      || audit.reachableObjectClosureSha256 !== expected.reachableObjectClosureSha256
      || audit.reachableObjectCount !== expected.reachableObjectCount
      || audit.scannerSha256 !== expected.scannerSha256
      || audit.wordlistCount !== expected.wordlistCount
      || audit.reportSha256 !== privateHistoryAuditIdentity(audit)) fail();
  sha(value.auditReportFileSha256);
  sha(value.reportSha256);
  const canonicalAuditBytes = Buffer.from(`${canonicalJson(audit)}\n`, "utf8");
  if (value.auditReportFileSha256 !== digest(canonicalAuditBytes)
      || value.reportSha256 !== privateHistoryAttestationIdentity(value)) fail();
  return value;
}

function sameStat(left, right) {
  return left.dev === right.dev && left.ino === right.ino && left.mode === right.mode
    && left.uid === right.uid && left.nlink === right.nlink
    && left.size === right.size && left.mtimeNs === right.mtimeNs
    && left.ctimeNs === right.ctimeNs;
}

export function stableReadPrivateJson(filename) {
  const absolute = path.resolve(filename);
  const before = fs.lstatSync(absolute, { bigint: true });
  const expectedUid = typeof process.geteuid === "function" ? BigInt(process.geteuid()) : before.uid;
  if (!before.isFile() || before.isSymbolicLink() || (before.mode & 0o7777n) !== 0o600n
      || before.uid !== expectedUid || before.nlink !== 1n || before.size <= 0n
      || before.size > BigInt(MAX_PRIVATE_BYTES)) fail();
  const descriptor = fs.openSync(absolute, constants.O_RDONLY | (constants.O_NOFOLLOW || 0));
  try {
    const opened = fs.fstatSync(descriptor, { bigint: true });
    if (!sameStat(before, opened)) fail();
    const bytes = fs.readFileSync(descriptor);
    const afterDescriptor = fs.fstatSync(descriptor, { bigint: true });
    const afterPath = fs.lstatSync(absolute, { bigint: true });
    if (!sameStat(opened, afterDescriptor) || !sameStat(afterDescriptor, afterPath)
        || bytes.length !== Number(opened.size)) fail();
    return {
      bytes,
      fileSha256: digest(bytes),
      path: absolute,
      value: JSON.parse(bytes.toString("utf8")),
    };
  } finally {
    fs.closeSync(descriptor);
  }
}

function validateAsset(asset) {
  if (!exactKeys(asset, ["kind", "name", "sha256", "size"])
      || !["apk", "update-manifest"].includes(asset.kind)
      || !SAFE_ASSET.test(string(asset.name, { maximum: 180 }))
      || asset.name.includes("..") || asset.name.includes("/") || asset.name.includes("\\")) {
    fail();
  }
  nonnegativeInteger(asset.size);
  sha(asset.sha256);
  if ((asset.kind === "apk") !== asset.name.endsWith(".apk")
      || (asset.kind === "update-manifest") !== (asset.name === "update.json")) fail();
  return asset;
}

function releaseIdentityBase(release) {
  const { releaseIdentitySha256: ignored, ...base } = release;
  return base;
}

export function historicalReleaseIdentity(release) {
  return domainDigest(
    "autoform-kit/historical-release-inventory-entry/v1",
    releaseIdentityBase(release),
  );
}

function validateRelease(release, sequence, baseline) {
  if (!exactKeys(release, [
    "assets", "draft", "originalApk", "originalUpdate", "prerelease",
    "releaseIdentitySha256", "sequence", "tag", "titleLength", "titleSha256",
  ]) || release.sequence !== sequence || release.tag !== HISTORICAL_TAGS[sequence]
      || typeof release.draft !== "boolean" || typeof release.prerelease !== "boolean") fail();
  positiveInteger(release.titleLength);
  sha(release.titleSha256);
  if (!Array.isArray(release.assets) || release.assets.length !== 2) fail();
  const assets = release.assets.map(validateAsset);
  if (new Set(assets.map((asset) => asset.name)).size !== assets.length
      || assets.filter((asset) => asset.kind === "apk").length !== 1
      || assets.filter((asset) => asset.kind === "update-manifest").length !== 1
      || canonicalJson([...assets].sort((left, right) => left.name.localeCompare(right.name)))
        !== canonicalJson(assets)) fail();

  const original = release.originalApk;
  if (!exactKeys(original, [
    "assetName", "packageName", "sha256", "signerSha256", "size", "versionCode",
    "versionName",
  ]) || original.assetName !== assets.find((asset) => asset.kind === "apk").name
      || original.sha256 !== assets.find((asset) => asset.kind === "apk").sha256
      || original.size !== assets.find((asset) => asset.kind === "apk").size
      || !PACKAGE.test(string(original.packageName, { maximum: 255 }))
      || original.versionName !== release.tag.slice(1)) fail();
  positiveInteger(original.size);
  positiveInteger(original.versionCode);
  sha(original.sha256);
  sha(original.signerSha256);
  const update = release.originalUpdate;
  const updateAsset = assets.find((asset) => asset.kind === "update-manifest");
  if (!exactKeys(update, [
    "apkAsset", "apkSha256", "assetName", "notesLength", "notesSha256",
    "packageName", "sha256", "size", "versionCode", "versionName",
  ]) || update.assetName !== updateAsset.name || update.sha256 !== updateAsset.sha256
      || update.size !== updateAsset.size || update.packageName !== original.packageName
      || update.versionCode !== original.versionCode
      || update.versionName !== original.versionName
      || update.apkAsset !== original.assetName || update.apkSha256 !== original.sha256) fail();
  positiveInteger(update.size);
  positiveInteger(update.versionCode);
  nonnegativeInteger(update.notesLength);
  sha(update.sha256);
  sha(update.apkSha256);
  sha(update.notesSha256);
  if (sequence === 0 && original.versionCode !== 1) fail();
  if (baseline) {
    if (original.packageName !== baseline.packageName
        || original.signerSha256 !== baseline.signerSha256
        || original.versionCode <= baseline.versionCode) fail();
  }
  if (release.releaseIdentitySha256 !== historicalReleaseIdentity(release)) fail();
  return original;
}

function inventoryBase(inventory) {
  const { inventorySha256: ignored, ...base } = inventory;
  return base;
}

export function historicalInventoryIdentity(inventory) {
  return domainDigest("autoform-kit/historical-release-inventory/v1", inventoryBase(inventory));
}

export function validateHistoricalInventory(inventory) {
  if (!exactKeys(inventory, [
    "inventorySha256", "kind", "releases", "schemaVersion", "sourceInventory",
  ]) || inventory.schemaVersion !== 1
      || inventory.kind !== "autoform-historical-release-inventory"
      || !exactKeys(inventory.sourceInventory, [
        "assetCount", "fileSha256", "gitTagRefListingSha256", "inventorySha256",
        "kind", "releaseCount", "releaseMetadataSha256", "schemaVersion",
        "stableIdentitySha256", "tagCount",
      ]) || inventory.sourceInventory.schemaVersion !== 1
      || inventory.sourceInventory.kind !== "public-rewrite-inventory"
      || inventory.sourceInventory.tagCount !== HISTORICAL_TAGS.length
      || inventory.sourceInventory.releaseCount !== HISTORICAL_TAGS.length
      || inventory.sourceInventory.assetCount !== HISTORICAL_TAGS.length * 2
      || !Array.isArray(inventory.releases)
      || inventory.releases.length !== HISTORICAL_TAGS.length) fail();
  for (const field of [
    "fileSha256", "gitTagRefListingSha256", "inventorySha256", "releaseMetadataSha256",
    "stableIdentitySha256",
  ]) {
    sha(inventory.sourceInventory[field]);
  }
  let baseline = null;
  for (let index = 0; index < inventory.releases.length; index += 1) {
    baseline = validateRelease(inventory.releases[index], index, baseline);
  }
  sha(inventory.inventorySha256);
  if (inventory.inventorySha256 !== historicalInventoryIdentity(inventory)) fail();
  return inventory;
}

export function createHistoricalInventory({
  sourceFileSha256, sourceInventory, apkIdentities, updateIdentities,
}) {
  sha(sourceFileSha256);
  if (!isObject(sourceInventory) || !Array.isArray(sourceInventory.releases)
      || sourceInventory.schemaVersion !== 1
      || sourceInventory.kind !== "public-rewrite-inventory"
      || sourceInventory.tagCount !== HISTORICAL_TAGS.length
      || sourceInventory.releaseCount !== HISTORICAL_TAGS.length
      || sourceInventory.assetCount !== HISTORICAL_TAGS.length * 2
      || !HEX_64.test(sourceInventory.inventorySha256)
      || !HEX_64.test(sourceInventory.stableIdentitySha256)
      || !isObject(sourceInventory.sourceBindings)
      || !HEX_64.test(sourceInventory.sourceBindings.gitTagRefListingSha256)
      || !HEX_64.test(sourceInventory.sourceBindings.releaseMetadataSha256)
      || !Array.isArray(apkIdentities) || apkIdentities.length !== HISTORICAL_TAGS.length
      || !Array.isArray(updateIdentities)
      || updateIdentities.length !== HISTORICAL_TAGS.length) fail();
  const sourceByTag = new Map(sourceInventory.releases.map((release) =>
    [release.tagName, release]));
  const identityByTag = new Map(apkIdentities.map((identity) => [identity.tag, identity]));
  const updateByTag = new Map(updateIdentities.map((identity) => [identity.tag, identity]));
  if (sourceByTag.size !== HISTORICAL_TAGS.length
      || identityByTag.size !== HISTORICAL_TAGS.length
      || updateByTag.size !== HISTORICAL_TAGS.length) fail();
  const releases = HISTORICAL_TAGS.map((tag, sequence) => {
    const source = sourceByTag.get(tag);
    const identity = identityByTag.get(tag);
    const updateIdentity = updateByTag.get(tag);
    if (!isObject(source) || !isObject(identity)
        || !isObject(updateIdentity)
        || !Array.isArray(source.assets) || source.assets.length !== 2
        || identity.tag !== tag || updateIdentity.tag !== tag) fail();
    const assets = source.assets.map((asset) => ({
      kind: asset.name.endsWith(".apk") ? "apk"
        : asset.name === "update.json" ? "update-manifest" : fail(),
      name: asset.name,
      sha256: asset.sha256,
      size: asset.size,
    })).sort((left, right) => left.name.localeCompare(right.name));
    const apkAsset = assets.find((asset) => asset.kind === "apk");
    const updateAsset = assets.find((asset) => asset.kind === "update-manifest");
    const release = {
      assets,
      draft: source.draft,
      originalApk: {
        assetName: apkAsset.name,
        packageName: identity.packageName,
        sha256: apkAsset.sha256,
        signerSha256: identity.signerSha256,
        size: apkAsset.size,
        versionCode: identity.versionCode,
        versionName: identity.versionName,
      },
      originalUpdate: {
        apkAsset: updateIdentity.apkAsset,
        apkSha256: updateIdentity.apkSha256,
        assetName: updateAsset.name,
        notesLength: updateIdentity.notesLength,
        notesSha256: updateIdentity.notesSha256,
        packageName: updateIdentity.packageName,
        sha256: updateAsset.sha256,
        size: updateAsset.size,
        versionCode: updateIdentity.versionCode,
        versionName: updateIdentity.versionName,
      },
      prerelease: source.prerelease,
      releaseIdentitySha256: "",
      sequence,
      tag,
      titleLength: source.titleLength,
      titleSha256: source.titleSha256,
    };
    release.releaseIdentitySha256 = historicalReleaseIdentity(release);
    return release;
  });
  const inventory = {
    inventorySha256: "",
    kind: "autoform-historical-release-inventory",
    releases,
    schemaVersion: 1,
    sourceInventory: {
      assetCount: sourceInventory.assetCount,
      fileSha256: sourceFileSha256,
      gitTagRefListingSha256: sourceInventory.sourceBindings.gitTagRefListingSha256,
      inventorySha256: sourceInventory.inventorySha256,
      kind: sourceInventory.kind,
      releaseCount: sourceInventory.releaseCount,
      releaseMetadataSha256: sourceInventory.sourceBindings.releaseMetadataSha256,
      schemaVersion: sourceInventory.schemaVersion,
      stableIdentitySha256: sourceInventory.stableIdentitySha256,
      tagCount: sourceInventory.tagCount,
    },
  };
  inventory.inventorySha256 = historicalInventoryIdentity(inventory);
  return validateHistoricalInventory(canonical(inventory));
}

export function loadHistoricalInventory(filename, expectedFileSha256 = "") {
  const input = stableReadPrivateJson(filename);
  if (expectedFileSha256 && input.fileSha256 !== sha(expectedFileSha256)) fail();
  return { ...input, inventory: validateHistoricalInventory(input.value) };
}

export function selectHistoricalRelease(inventory, tag) {
  validateHistoricalInventory(inventory);
  if (!HISTORICAL_TAGS.includes(tag)) fail();
  const release = inventory.releases.find((item) => item.tag === tag);
  if (!release) fail();
  return release;
}

function exactShaObject(value) {
  if (!exactKeys(value, ["file", "sha256"])) fail();
  if (!SAFE_ASSET.test(string(value.file, { maximum: 180 }))
      || value.file.includes("..")) fail();
  sha(value.sha256);
  return value;
}

function validatePublicAudit(value, artifactSha256) {
  if (!exactKeys(value, [
    "apk", "policySha256", "releaseMetadata", "scannerSha256", "sourceTree",
    "thirdPartyProvenance", "worktree",
  ])) fail();
  sha(value.scannerSha256);
  sha(value.policySha256);
  if (!exactKeys(value.sourceTree, ["gitTreeOid", "inputSha256", "reportSha256"])
      || !/^[a-f0-9]{40}(?:[a-f0-9]{24})?$/u.test(value.sourceTree.gitTreeOid)) fail();
  sha(value.sourceTree.inputSha256);
  sha(value.sourceTree.reportSha256);
  if (!exactKeys(value.worktree, ["inputSha256", "reportSha256"])) fail();
  sha(value.worktree.inputSha256);
  sha(value.worktree.reportSha256);
  if (!exactKeys(value.apk, ["inputSha256", "reportSha256", "zipEntryManifestSha256"])
      || value.apk.inputSha256 !== artifactSha256) fail();
  sha(value.apk.reportSha256);
  sha(value.apk.zipEntryManifestSha256);
  if (!exactKeys(value.releaseMetadata, ["notes", "update"])
      || !exactKeys(value.releaseMetadata.notes, ["inputSha256", "reportSha256"])
      || !exactKeys(value.releaseMetadata.update, ["inputSha256", "reportSha256"])) fail();
  for (const item of [value.releaseMetadata.notes, value.releaseMetadata.update]) {
    sha(item.inputSha256);
    sha(item.reportSha256);
  }
  const provenance = value.thirdPartyProvenance;
  if (!exactKeys(provenance, [
    "apkMatchedDexStringCount", "applicationDexStrict", "compiledOutputCount",
    "declaredDexStringCount", "dexSourceArtifactCount", "dexSourceEntryCount",
    "manifestFile", "manifestSha256", "matchedEntryCount", "mergedSourceCount",
    "profileId", "runtimeLockFile", "runtimeLockSha256", "sourceArtifactCount",
    "sourceEntryCount", "sourceMatchedDexStringCount", "sourceReportSha256",
    "sourceVerifierFile", "sourceVerifierSha256",
  ]) || provenance.manifestFile !== "tools/apk-third-party-components.json"
      || provenance.runtimeLockFile !== "tools/android-runtime-dependencies.lock.json"
      || provenance.sourceVerifierFile !== "tools/verify-apk-third-party-sources.mjs"
      || provenance.applicationDexStrict !== true
      || !/^[a-z0-9][a-z0-9._-]*$/u.test(provenance.profileId)) fail();
  for (const field of [
    "manifestSha256", "runtimeLockSha256", "sourceReportSha256", "sourceVerifierSha256",
  ]) sha(provenance[field]);
  for (const field of [
    "apkMatchedDexStringCount", "compiledOutputCount", "declaredDexStringCount",
    "dexSourceArtifactCount", "dexSourceEntryCount", "matchedEntryCount",
    "mergedSourceCount", "sourceArtifactCount", "sourceEntryCount",
    "sourceMatchedDexStringCount",
  ]) positiveInteger(provenance[field]);
  if (provenance.sourceEntryCount !== provenance.sourceArtifactCount
      || provenance.mergedSourceCount !== 1
      || provenance.sourceMatchedDexStringCount !== provenance.declaredDexStringCount
      || provenance.apkMatchedDexStringCount !== provenance.declaredDexStringCount) fail();
}

export function validateHistoricalCandidate(manifest, inventory, inventoryFileSha256) {
  validateHistoricalInventory(inventory);
  sha(inventoryFileSha256);
  if (!exactKeys(manifest, [
    "app", "artifacts", "historicalRelease", "lineage", "publicationMode", "publicAudit",
    "schemaVersion", "source", "tag",
  ]) || manifest.schemaVersion !== 4
      || manifest.publicationMode !== HISTORICAL_PUBLICATION_MODE
      || !HISTORICAL_TAGS.includes(manifest.tag)
      || !exactKeys(manifest.source, ["branch", "commit", "workingTreeClean"])
      || manifest.source.branch !== "main" || manifest.source.workingTreeClean !== true
      || !/^[a-f0-9]{40}(?:[a-f0-9]{24})?$/u.test(manifest.source.commit)
      || !exactKeys(manifest.app,
        ["packageName", "signerSha256", "versionCode", "versionName"])
      || !PACKAGE.test(manifest.app.packageName)
      || manifest.app.versionName !== manifest.tag.slice(1)) fail();
  positiveInteger(manifest.app.versionCode);
  sha(manifest.app.signerSha256);
  if (!exactKeys(manifest.artifacts, ["apk", "notes", "update"])) fail();
  exactShaObject(manifest.artifacts.apk);
  exactShaObject(manifest.artifacts.notes);
  exactShaObject(manifest.artifacts.update);
  if (manifest.artifacts.notes.file !== "release-notes.txt"
      || manifest.artifacts.update.file !== "update.json") fail();

  const historical = manifest.historicalRelease;
  if (!exactKeys(historical, ["inventory", "original", "publication"])
      || !exactKeys(historical.inventory,
        ["fileSha256", "inventorySha256", "sourceInventory"])
      || historical.inventory.fileSha256 !== inventoryFileSha256
      || historical.inventory.inventorySha256 !== inventory.inventorySha256
      || canonicalJson(historical.inventory.sourceInventory)
        !== canonicalJson(inventory.sourceInventory)) fail();
  const original = selectHistoricalRelease(inventory, manifest.tag);
  if (canonicalJson(historical.original) !== canonicalJson(original)) fail();
  if (manifest.app.packageName !== original.originalApk.packageName
      || manifest.app.versionCode !== original.originalApk.versionCode
      || manifest.app.versionName !== original.originalApk.versionName
      || manifest.app.signerSha256 !== original.originalApk.signerSha256
      || manifest.artifacts.apk.file !== original.originalApk.assetName) fail();

  const publication = historical.publication;
  if (!exactKeys(publication, [
    "assets", "body", "bodyPolicy", "draft", "makeLatest", "prerelease", "title",
  ]) || regularString(publication.title, 4096).includes("\n")
      || publication.body !== HISTORICAL_BODY
      || publication.bodyPolicy !== "fixed-source-bound-generic-v1"
      || publication.draft !== original.draft || publication.prerelease !== original.prerelease
      || publication.makeLatest !== false || digest(Buffer.from(publication.title, "utf8"))
        !== original.titleSha256 || [...publication.title].length !== original.titleLength
      || !Array.isArray(publication.assets) || publication.assets.length !== 2) fail();
  const expectedAssets = [
    {
      file: manifest.artifacts.apk.file,
      name: original.originalApk.assetName,
      sha256: manifest.artifacts.apk.sha256,
    },
    {
      file: manifest.artifacts.update.file,
      name: original.originalUpdate.assetName,
      sha256: manifest.artifacts.update.sha256,
    },
  ].sort((left, right) => left.name.localeCompare(right.name));
  for (let index = 0; index < publication.assets.length; index += 1) {
    const asset = publication.assets[index];
    if (!exactKeys(asset, ["file", "name", "sha256", "size"])
        || asset.file !== expectedAssets[index].file || asset.name !== expectedAssets[index].name
        || asset.sha256 !== expectedAssets[index].sha256) fail();
    positiveInteger(asset.size);
    sha(asset.sha256);
  }
  if (canonicalJson([...publication.assets].sort((left, right) =>
    left.name.localeCompare(right.name))) !== canonicalJson(publication.assets)) fail();
  const oldDigests = new Set(inventory.releases.flatMap((release) =>
    release.assets.map((asset) => asset.sha256)));
  if (oldDigests.has(manifest.artifacts.apk.sha256)
      || oldDigests.has(manifest.artifacts.update.sha256)) fail();
  if (manifest.publicAudit.releaseMetadata.notes.inputSha256
        !== manifest.artifacts.notes.sha256
      || manifest.publicAudit.releaseMetadata.update.inputSha256
        !== manifest.artifacts.update.sha256) fail();
  validatePublicAudit(manifest.publicAudit, manifest.artifacts.apk.sha256);

  const lineage = manifest.lineage;
  if (!exactKeys(lineage,
    ["kind", "originalApk", "previousRebuiltCandidate", "sequence"])
      || lineage.sequence !== original.sequence
      || canonicalJson(lineage.originalApk) !== canonicalJson(original.originalApk)) fail();
  if (lineage.sequence === 0) {
    if (lineage.kind !== "historical-initial-rebuild"
        || lineage.previousRebuiltCandidate !== null) fail();
  } else {
    const previous = lineage.previousRebuiltCandidate;
    if (lineage.kind !== "historical-upgrade-rebuild"
        || !exactKeys(previous, [
          "apkSha256", "candidateManifestSha256", "packageName", "signerSha256", "tag",
          "versionCode", "versionName",
        ]) || previous.tag !== HISTORICAL_TAGS[lineage.sequence - 1]
        || previous.versionName !== previous.tag.slice(1)
        || previous.packageName !== manifest.app.packageName
        || previous.signerSha256 !== manifest.app.signerSha256
        || previous.versionCode >= manifest.app.versionCode) fail();
    positiveInteger(previous.versionCode);
    sha(previous.apkSha256);
    sha(previous.candidateManifestSha256);
    sha(previous.signerSha256);
  }
  return manifest;
}

function parseArguments(argv) {
  const values = {};
  for (let index = 0; index < argv.length; index += 2) {
    const key = argv[index];
    const value = argv[index + 1];
    if (!["--candidate", "--inventory", "--inventory-file-sha256", "--tag"].includes(key)
        || !value || values[key]) fail();
    values[key] = value;
  }
  const inventorySelection = values["--inventory"] && values["--tag"]
    && !values["--candidate"] && Object.keys(values).length >= 2
    && Object.keys(values).length <= 3;
  const candidateValidation = values["--inventory"] && values["--candidate"]
    && values["--inventory-file-sha256"] && !values["--tag"]
    && Object.keys(values).length === 3;
  if (!inventorySelection && !candidateValidation) fail();
  return values;
}

function main() {
  try {
    const args = parseArguments(process.argv.slice(2));
    const loaded = loadHistoricalInventory(
      args["--inventory"], args["--inventory-file-sha256"] || "",
    );
    if (args["--candidate"]) {
      const candidateInput = stableReadPrivateJson(args["--candidate"]);
      const candidate = validateHistoricalCandidate(
        candidateInput.value, loaded.inventory, loaded.fileSha256,
      );
      process.stdout.write(`${canonicalJson({
        apk: candidate.artifacts.apk,
        candidateFileSha256: candidateInput.fileSha256,
        inventoryFileSha256: loaded.fileSha256,
        inventorySha256: loaded.inventory.inventorySha256,
        publication: candidate.historicalRelease.publication,
        sourceCommit: candidate.source.commit,
        tag: candidate.tag,
        update: candidate.artifacts.update,
      })}\n`);
    } else {
      const release = selectHistoricalRelease(loaded.inventory, args["--tag"]);
      process.stdout.write(`${canonicalJson({
        inventoryFileSha256: loaded.fileSha256,
        inventorySha256: loaded.inventory.inventorySha256,
        body: HISTORICAL_BODY,
        publicationMode: HISTORICAL_PUBLICATION_MODE,
        originalAssetSha256: loaded.inventory.releases
          .flatMap((item) => item.assets.map((asset) => asset.sha256)).sort(),
        release,
        sourceInventory: loaded.inventory.sourceInventory,
      })}\n`);
    }
  } catch {
    process.stderr.write("historical release contract validation failed; no values were emitted\n");
    process.exitCode = 1;
  }
}

if (process.argv[1] && path.resolve(process.argv[1]) === THIS_FILE) main();
