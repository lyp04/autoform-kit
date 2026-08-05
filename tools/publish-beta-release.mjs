#!/usr/bin/env node

/**
 * Publish the one reviewed code-9 beta candidate through the fixed `beta` channel.
 *
 * This is intentionally independent from the stable publisher. It performs every local and
 * remote preflight before its first write, creates a non-latest prerelease draft, verifies the
 * exact uploaded bytes, and only then makes that draft public with make_latest="false".
 * It never overwrites, moves, or deletes an existing tag or Release and never attempts cleanup.
 */

import { spawnSync } from "node:child_process";
import crypto from "node:crypto";
import fs, { constants } from "node:fs";
import os from "node:os";
import path from "node:path";
import process from "node:process";
import { fileURLToPath } from "node:url";
import { HISTORICAL_BODY, HISTORICAL_TAGS } from "./historical-release-contract.mjs";

const HERE = path.dirname(fileURLToPath(import.meta.url));
const ROOT = path.resolve(HERE, "..");
const SCANNER = path.join(HERE, "public-surface-audit.mjs");
const THIRD_PARTY_POLICY = path.join(HERE, "apk-third-party-components.json");
const RUNTIME_LOCK = path.join(HERE, "android-runtime-dependencies.lock.json");
const SOURCE_VERIFIER = path.join(HERE, "verify-apk-third-party-sources.mjs");

const CHANNEL_TAG = "beta";
const CANDIDATE_TAG = "v1.0.8-beta.1";
const VERSION_NAME = "1.0.8-beta.1";
const VERSION_CODE = 9;
const PACKAGE_NAME = "com.autoformkit.app";
const RELEASE_TITLE = "autoform-kit 1.0.8-beta.1";
const RELEASE_BODY = "Public beta build of the autoform-kit framework. "
  + "No site-specific configuration is included.";
const RELEASE_BODY_BYTES = Buffer.from(`${RELEASE_BODY}\n`, "utf8");
const APK_NAME = `autoform-kit-${VERSION_NAME}.apk`;
const UPDATE_NAME = "update.json";
const MANIFEST_NAME = "candidate-manifest.json";
const NOTES_NAME = "release-notes.txt";
const EXPECTED_CANDIDATE_FILES = [APK_NAME, MANIFEST_NAME, NOTES_NAME, UPDATE_NAME].sort();
const REPOSITORY_CREATED_AT_KEY = ["created", "at"].join("_");
const HEX_64 = /^[a-f0-9]{64}$/u;
const GIT_OID = /^(?:[a-f0-9]{40}|[a-f0-9]{64})$/u;
const SAFE_NAME = /^[A-Za-z0-9][A-Za-z0-9._-]{0,179}$/u;
const MAX_FILE_BYTES = 1024 * 1024 * 1024;
const MAX_JSON_BYTES = 64 * 1024 * 1024;
const SINGLE_WRITER_ENV = "AUTOFORM_BETA_SINGLE_WRITER_WINDOW";
const SINGLE_WRITER_CONFIRMATION = "EXCLUSIVE_BETA_RELEASE_WRITER_CONFIRMED";

let sideEffectStarted = false;
let remoteBetaKnown = false;
let auditDirectory = "";
let captureSequence = 0;

function fail(message = "beta publication validation failed") {
  const partial = sideEffectStarted || remoteBetaKnown
    ? " remote may now contain a beta draft, tag, or partial change; do not delete or retry;"
    : "";
  throw new Error(`publish-beta-release:${partial} ${message}`);
}

function info(message) {
  process.stdout.write(`publish-beta-release: ${message}\n`);
}

function digest(value) {
  return crypto.createHash("sha256").update(value).digest("hex");
}

function canonical(value) {
  if (Array.isArray(value)) return value.map(canonical);
  if (value && typeof value === "object") {
    const output = {};
    for (const key of Object.keys(value).sort()) output[key] = canonical(value[key]);
    return output;
  }
  return value;
}

function canonicalJson(value) {
  return JSON.stringify(canonical(value));
}

function exactKeys(value, expected) {
  return value && typeof value === "object" && !Array.isArray(value)
    && JSON.stringify(Object.keys(value).sort()) === JSON.stringify([...expected].sort());
}

function run(command, args, { allowFailure = false, binary = false, input } = {}) {
  const result = spawnSync(command, args, {
    cwd: ROOT,
    encoding: binary ? null : "utf8",
    env: { ...process.env },
    input,
    maxBuffer: MAX_FILE_BYTES,
    stdio: [input === undefined ? "ignore" : "pipe", "pipe", "pipe"],
  });
  if ((result.error || result.status !== 0) && !allowFailure) {
    fail("a required local or GitHub check failed");
  }
  return result;
}

function sameStat(left, right) {
  return left.dev === right.dev && left.ino === right.ino && left.mode === right.mode
    && left.uid === right.uid && left.nlink === right.nlink && left.size === right.size
    && left.mtimeNs === right.mtimeNs && left.ctimeNs === right.ctimeNs;
}

function stableReadRegular(filename, { privateMode = false, maximum = MAX_FILE_BYTES } = {}) {
  const absolute = path.resolve(filename);
  const before = fs.lstatSync(absolute, { bigint: true });
  const expectedUid = typeof process.geteuid === "function" ? BigInt(process.geteuid()) : before.uid;
  if (!before.isFile() || before.isSymbolicLink() || before.nlink !== 1n
      || before.uid !== expectedUid || before.size <= 0n || before.size > BigInt(maximum)
      || (privateMode && (before.mode & 0o7777n) !== 0o600n)) fail();
  const descriptor = fs.openSync(absolute, constants.O_RDONLY | (constants.O_NOFOLLOW || 0));
  try {
    const opened = fs.fstatSync(descriptor, { bigint: true });
    if (!sameStat(before, opened)) fail();
    const bytes = fs.readFileSync(descriptor);
    const afterDescriptor = fs.fstatSync(descriptor, { bigint: true });
    const afterPath = fs.lstatSync(absolute, { bigint: true });
    if (!sameStat(opened, afterDescriptor) || !sameStat(afterDescriptor, afterPath)
        || bytes.length !== Number(opened.size)) fail();
    return { absolute, bytes, sha256: digest(bytes), size: bytes.length, stat: afterPath };
  } finally {
    fs.closeSync(descriptor);
  }
}

function stableJson(filename, options = {}) {
  const input = stableReadRegular(filename, { ...options, maximum: MAX_JSON_BYTES });
  try {
    return { ...input, value: JSON.parse(input.bytes.toString("utf8")) };
  } catch {
    fail();
  }
}

function parseArguments(argv) {
  const output = { wordlists: [] };
  for (let index = 0; index < argv.length;) {
    const key = argv[index];
    if (key === "-h" || key === "--help") return { help: true };
    const value = argv[index + 1];
    if (!value) fail("an option value is missing");
    if (key === "--private-wordlist") output.wordlists.push(value);
    else if (key === "--candidate" && !output.candidate) output.candidate = value;
    else if (key === "--previous-apk" && !output.previousApk) output.previousApk = value;
    else if (key === "--resume-draft" && output.resumeDraftId === undefined
        && /^[1-9][0-9]{0,15}$/u.test(value) && Number.isSafeInteger(Number(value))) {
      output.resumeDraftId = Number(value);
    }
    else fail("an unsupported or duplicate option was provided");
    index += 2;
  }
  if (!output.candidate || !output.previousApk || output.wordlists.length === 0) fail();
  return output;
}

function usage() {
  process.stdout.write(`Usage: tools/publish-beta-release.mjs \\\n  --candidate PATH --previous-apk PATH \\\n  --private-wordlist PATH [--private-wordlist PATH ...] \\\n  [--resume-draft RELEASE_ID]\n\n`
  + `Publishes only ${CANDIDATE_TAG} through the fixed ${CHANNEL_TAG} tag as a non-latest `
  + "public prerelease. An explicit Release ID may resume one exact unpublished draft; "
  + "the publisher never deletes or replaces remote state.\n");
}

function requiredString(value, maximum = 4096) {
  if (typeof value !== "string" || value.length === 0 || value.length > maximum
      || /[\u0000]/u.test(value)) fail();
  return value;
}

function positiveInteger(value) {
  if (!Number.isSafeInteger(value) || value <= 0) fail();
  return value;
}

function sha256Value(value) {
  if (typeof value !== "string" || !HEX_64.test(value)) fail();
  return value;
}

function oidValue(value) {
  if (typeof value !== "string" || !GIT_OID.test(value)) fail();
  return value;
}

function stableWriteAudit(name, bytes) {
  captureSequence += 1;
  const filename = path.join(auditDirectory,
    `${String(captureSequence).padStart(3, "0")}-${name}`);
  fs.writeFileSync(filename, bytes, { mode: 0o600, flag: "wx" });
  return stableReadRegular(filename, { privateMode: true });
}

function assertSelfBoundReport(report) {
  if (!report || report.schemaVersion !== 1 || report.summary?.passed !== true
      || report.summary?.findingCount !== 0 || !HEX_64.test(report.reportSha256)
      || report.reportSha256 !== digest(Buffer.from(canonicalJson((({ reportSha256, ...rest }) => rest)(report)), "utf8"))) {
    fail("a public-surface audit did not produce a clean self-bound report");
  }
}

function runScanner(mode, input, wordlists) {
  const args = [SCANNER];
  if (mode === "git-tree") args.push("--git-tree", input, "--repo", ROOT);
  else if (mode === "worktree") args.push("--worktree", "--repo", ROOT);
  else if (mode === "apk") args.push("--apk", input);
  else if (mode === "file") args.push("--file", input);
  else fail();
  for (const wordlist of wordlists) args.push("--private-wordlist", wordlist.absolute);
  const before = wordlists.map((wordlist) => stableReadRegular(wordlist.absolute,
    { privateMode: true }));
  const result = run(process.execPath, args, { allowFailure: true });
  if (result.status !== 0) fail("a public surface audit failed");
  let report;
  try {
    report = JSON.parse(result.stdout);
  } catch {
    fail();
  }
  assertSelfBoundReport(report);
  for (let index = 0; index < wordlists.length; index += 1) {
    const after = stableReadRegular(wordlists[index].absolute, { privateMode: true });
    if (after.sha256 !== before[index].sha256 || !sameStat(after.stat, before[index].stat)) fail();
  }
  return report;
}

function findAndroidTool(name, override) {
  if (override) {
    const input = stableReadRegular(override);
    if ((input.stat.mode & 0o111n) === 0n) fail();
    return input.absolute;
  }
  const sdk = process.env.ANDROID_HOME || process.env.ANDROID_SDK_ROOT;
  if (!sdk) fail("Android SDK location is required");
  const buildTools = path.join(path.resolve(sdk), "build-tools");
  const candidates = fs.readdirSync(buildTools, { withFileTypes: true })
    .filter((entry) => entry.isDirectory() && !entry.isSymbolicLink())
    .map((entry) => path.join(buildTools, entry.name, name))
    .filter((filename) => {
      try {
        return fs.lstatSync(filename).isFile();
      } catch {
        return false;
      }
    }).sort().reverse();
  if (candidates.length === 0) fail();
  return candidates[0];
}

function apkIdentity(apk, aapt, apksigner) {
  const packageResult = run(aapt, ["dump", "badging", apk.absolute]);
  const line = packageResult.stdout.split("\n").find((value) => value.startsWith("package: "));
  const match = /^package: name='([^']+)' versionCode='([1-9][0-9]*)' versionName='([^']+)'/u
    .exec(line || "");
  if (!match) fail("APK identity could not be read");
  const signerResult = run(apksigner, ["verify", "--print-certs", apk.absolute]);
  const signers = [...signerResult.stdout.matchAll(
    /Signer #\d+ certificate SHA-256 digest:\s*([A-Fa-f0-9]{64})/gu,
  )].map((item) => item[1].toLowerCase());
  if (signers.length !== 1 || new Set(signers).size !== 1) fail("APK signer is ambiguous");
  return {
    packageName: match[1],
    signerSha256: signers[0],
    versionCode: Number(match[2]),
    versionName: match[3],
  };
}

function validateCandidate(manifestInput, previousApk, aapt, apksigner) {
  const value = manifestInput.value;
  if (!exactKeys(value, ["app", "artifacts", "previousApk", "publicAudit", "schemaVersion",
    "source", "tag"]) || value.schemaVersion !== 2 || value.tag !== CANDIDATE_TAG
      || !exactKeys(value.source, ["branch", "commit", "workingTreeClean"])
      || value.source.workingTreeClean !== true || !requiredString(value.source.branch, 255)
      || !oidValue(value.source.commit)
      || !exactKeys(value.app, ["packageName", "signerSha256", "versionCode", "versionName"])
      || value.app.packageName !== PACKAGE_NAME || value.app.versionCode !== VERSION_CODE
      || value.app.versionName !== VERSION_NAME || !sha256Value(value.app.signerSha256)
      || !exactKeys(value.previousApk,
        ["packageName", "sha256", "signerSha256", "versionCode", "versionName"])
      || value.previousApk.packageName !== PACKAGE_NAME || value.previousApk.versionCode !== 8
      || value.previousApk.versionName !== "1.0.7" || !sha256Value(value.previousApk.sha256)
      || value.previousApk.signerSha256 !== value.app.signerSha256
      || !exactKeys(value.artifacts, ["apk", "notes", "update"])) fail("invalid beta candidate");

  const expectedArtifacts = {
    apk: [APK_NAME, value.app],
    notes: [NOTES_NAME, null],
    update: [UPDATE_NAME, null],
  };
  for (const [kind, [filename]] of Object.entries(expectedArtifacts)) {
    const artifact = value.artifacts[kind];
    if (!exactKeys(artifact, ["file", "sha256"]) || artifact.file !== filename
        || !sha256Value(artifact.sha256)) fail("invalid beta candidate artifact");
  }
  if (!exactKeys(value.publicAudit, ["apk", "policySha256", "releaseMetadata",
    "scannerSha256", "sourceTree", "thirdPartyProvenance", "worktree"])
      || !sha256Value(value.publicAudit.scannerSha256)
      || !sha256Value(value.publicAudit.policySha256)
      || !exactKeys(value.publicAudit.sourceTree, ["gitTreeOid", "inputSha256", "reportSha256"])
      || !oidValue(value.publicAudit.sourceTree.gitTreeOid)
      || !sha256Value(value.publicAudit.sourceTree.inputSha256)
      || !sha256Value(value.publicAudit.sourceTree.reportSha256)
      || !exactKeys(value.publicAudit.worktree, ["inputSha256", "reportSha256"])
      || !sha256Value(value.publicAudit.worktree.inputSha256)
      || !sha256Value(value.publicAudit.worktree.reportSha256)
      || !exactKeys(value.publicAudit.apk,
        ["inputSha256", "reportSha256", "zipEntryManifestSha256"])
      || !sha256Value(value.publicAudit.apk.inputSha256)
      || !sha256Value(value.publicAudit.apk.reportSha256)
      || !sha256Value(value.publicAudit.apk.zipEntryManifestSha256)
      || !exactKeys(value.publicAudit.releaseMetadata, ["notes", "update"])) fail();
  for (const key of ["notes", "update"]) {
    const binding = value.publicAudit.releaseMetadata[key];
    if (!exactKeys(binding, ["inputSha256", "reportSha256"])
        || !sha256Value(binding.inputSha256) || !sha256Value(binding.reportSha256)) fail();
  }
  const provenance = value.publicAudit.thirdPartyProvenance;
  const provenanceKeys = ["apkMatchedDexStringCount", "applicationDexStrict",
    "compiledOutputCount", "declaredDexStringCount", "dexSourceArtifactCount",
    "dexSourceEntryCount", "manifestFile", "manifestSha256", "matchedEntryCount",
    "mergedSourceCount", "profileId", "runtimeLockFile", "runtimeLockSha256",
    "sourceArtifactCount", "sourceEntryCount", "sourceMatchedDexStringCount",
    "sourceReportSha256", "sourceVerifierFile", "sourceVerifierSha256"];
  if (!exactKeys(provenance, provenanceKeys)
      || provenance.manifestFile !== "tools/apk-third-party-components.json"
      || provenance.runtimeLockFile !== "tools/android-runtime-dependencies.lock.json"
      || provenance.sourceVerifierFile !== "tools/verify-apk-third-party-sources.mjs"
      || provenance.applicationDexStrict !== true || !requiredString(provenance.profileId, 255)
      || !sha256Value(provenance.manifestSha256)
      || !sha256Value(provenance.runtimeLockSha256)
      || !sha256Value(provenance.sourceVerifierSha256)
      || !sha256Value(provenance.sourceReportSha256)) fail();
  for (const key of ["apkMatchedDexStringCount", "compiledOutputCount", "declaredDexStringCount",
    "dexSourceArtifactCount", "dexSourceEntryCount", "matchedEntryCount", "mergedSourceCount",
    "sourceArtifactCount", "sourceEntryCount", "sourceMatchedDexStringCount"]) {
    positiveInteger(provenance[key]);
  }
  if (provenance.sourceArtifactCount !== provenance.sourceEntryCount
      || provenance.declaredDexStringCount !== provenance.sourceMatchedDexStringCount
      || provenance.declaredDexStringCount !== provenance.apkMatchedDexStringCount
      || provenance.mergedSourceCount !== 1) fail();

  const candidateDirectory = path.dirname(manifestInput.absolute);
  const names = fs.readdirSync(candidateDirectory).sort();
  if (canonicalJson(names) !== canonicalJson(EXPECTED_CANDIDATE_FILES)) {
    fail("candidate directory closure is not exact");
  }
  const apk = stableReadRegular(path.join(candidateDirectory, APK_NAME));
  const update = stableJson(path.join(candidateDirectory, UPDATE_NAME));
  const notes = stableReadRegular(path.join(candidateDirectory, NOTES_NAME));
  if (apk.sha256 !== value.artifacts.apk.sha256
      || update.sha256 !== value.artifacts.update.sha256
      || notes.sha256 !== value.artifacts.notes.sha256
      || notes.bytes.compare(RELEASE_BODY_BYTES) !== 0
      || value.publicAudit.apk.inputSha256 !== apk.sha256
      || value.publicAudit.releaseMetadata.update.inputSha256 !== update.sha256
      || value.publicAudit.releaseMetadata.notes.inputSha256 !== notes.sha256) fail();
  if (!exactKeys(update.value,
    ["apkAsset", "notes", "packageName", "sha256", "versionCode", "versionName"])
      || update.value.apkAsset !== APK_NAME || update.value.notes !== RELEASE_BODY
      || update.value.packageName !== PACKAGE_NAME || update.value.sha256 !== apk.sha256
      || update.value.versionCode !== VERSION_CODE || update.value.versionName !== VERSION_NAME) fail();

  const actualApk = apkIdentity(apk, aapt, apksigner);
  const actualPrevious = apkIdentity(previousApk, aapt, apksigner);
  if (canonicalJson(actualApk) !== canonicalJson(value.app)
      || actualPrevious.packageName !== value.previousApk.packageName
      || actualPrevious.signerSha256 !== value.previousApk.signerSha256
      || actualPrevious.versionCode !== value.previousApk.versionCode
      || actualPrevious.versionName !== value.previousApk.versionName
      || previousApk.sha256 !== value.previousApk.sha256) fail("APK identity mismatch");
  return { apk, candidateDirectory, manifest: manifestInput, notes, update, value };
}

function assertStableInput(original, { privateMode = false } = {}) {
  const current = stableReadRegular(original.absolute, { privateMode });
  if (current.sha256 !== original.sha256 || !sameStat(current.stat, original.stat)) fail();
}

function assertCandidateInputsStable(candidate, previousApk, wordlists) {
  for (const input of [candidate.apk, candidate.update, candidate.notes, candidate.manifest]) {
    assertStableInput(input);
  }
  assertStableInput(previousApk, { privateMode: true });
  for (const wordlist of wordlists) assertStableInput(wordlist, { privateMode: true });
  const names = fs.readdirSync(candidate.candidateDirectory).sort();
  if (canonicalJson(names) !== canonicalJson(EXPECTED_CANDIDATE_FILES)) fail();
}

function assertSource(candidate) {
  const source = candidate.value.source;
  const head = run("git", ["rev-parse", "HEAD"]).stdout.trim();
  const branch = run("git", ["branch", "--show-current"]).stdout.trim();
  const tree = run("git", ["rev-parse", `${source.commit}^{tree}`]).stdout.trim();
  const status = run("git", ["status", "--porcelain", "--untracked-files=normal"]).stdout;
  if (head !== source.commit || branch !== source.branch || status !== ""
      || tree !== candidate.value.publicAudit.sourceTree.gitTreeOid) {
    fail("source checkout does not match the candidate");
  }
  run("git", ["cat-file", "-e", `${source.commit}^{commit}`]);
  if (run("git", ["diff", "--quiet"], { allowFailure: true }).status !== 0
      || run("git", ["diff", "--cached", "--quiet"], { allowFailure: true }).status !== 0) {
    fail("source checkout is not clean");
  }
}

function assertScannerReportMatches(report, expected, mode) {
  if (report.scannerSha256 !== expected.scannerSha256
      || report.policySha256 !== expected.policySha256
      || report.input?.mode !== mode) fail("fresh public audit binding mismatch");
}

function runSourceVerifier(candidate) {
  const provenance = candidate.value.publicAudit.thirdPartyProvenance;
  const result = run(process.execPath, [SOURCE_VERIFIER,
    "--apk", candidate.apk.absolute,
    "--policy", THIRD_PARTY_POLICY,
    "--build-dir", path.join(ROOT, "app", "build"),
    "--gradle-user-home", process.env.GRADLE_USER_HOME
      ? path.resolve(process.env.GRADLE_USER_HOME) : path.join(os.homedir(), ".gradle"),
  ], { allowFailure: true });
  if (result.status !== 0) fail("APK source provenance verification failed");
  let report;
  try {
    report = JSON.parse(result.stdout);
  } catch {
    fail();
  }
  const reportBase = (({ reportSha256, ...rest }) => rest)(report);
  if (report.schemaVersion !== 1 || report.passed !== true
      || report.reportSha256 !== digest(Buffer.from(canonicalJson(reportBase), "utf8"))
      || report.reportSha256 !== provenance.sourceReportSha256
      || report.verifierSha256 !== provenance.sourceVerifierSha256
      || report.policySha256 !== provenance.manifestSha256
      || report.apkSha256 !== candidate.apk.sha256
      || report.profileId !== provenance.profileId
      || report.sourceArtifactCount !== provenance.sourceArtifactCount
      || report.sourceEntryCount !== provenance.sourceEntryCount
      || report.mergedSourceCount !== provenance.mergedSourceCount
      || report.compiledOutputCount !== provenance.compiledOutputCount
      || report.dexSourceArtifactCount !== provenance.dexSourceArtifactCount
      || report.dexSourceEntryCount !== provenance.dexSourceEntryCount
      || report.declaredDexStringCount !== provenance.declaredDexStringCount
      || report.sourceMatchedDexStringCount !== provenance.sourceMatchedDexStringCount
      || report.apkMatchedDexStringCount !== provenance.apkMatchedDexStringCount) fail();
}

function runCandidateAudits(candidate, wordlists) {
  assertSource(candidate);
  const audit = candidate.value.publicAudit;
  if (digest(fs.readFileSync(SCANNER)) !== audit.scannerSha256
      || digest(fs.readFileSync(THIRD_PARTY_POLICY))
        !== audit.thirdPartyProvenance.manifestSha256
      || digest(fs.readFileSync(RUNTIME_LOCK))
        !== audit.thirdPartyProvenance.runtimeLockSha256
      || digest(fs.readFileSync(SOURCE_VERIFIER))
        !== audit.thirdPartyProvenance.sourceVerifierSha256) fail("auditor source binding mismatch");

  const commitBytes = run("git", ["cat-file", "commit", candidate.value.source.commit],
    { binary: true }).stdout;
  if (!Buffer.isBuffer(commitBytes) || commitBytes.length === 0) {
    fail("source commit metadata could not be read");
  }
  const commit = stableWriteAudit("source-commit-object.txt", commitBytes);
  runScanner("file", commit.absolute, wordlists);
  const tree = runScanner("git-tree", candidate.value.source.commit, wordlists);
  const worktree = runScanner("worktree", "", wordlists);
  const apk = runScanner("apk", candidate.apk.absolute, wordlists);
  const update = runScanner("file", candidate.update.absolute, wordlists);
  const notes = runScanner("file", candidate.notes.absolute, wordlists);
  const manifest = runScanner("file", candidate.manifest.absolute, wordlists);
  const title = stableWriteAudit("beta-title.txt", Buffer.from(`${RELEASE_TITLE}\n`, "utf8"));
  runScanner("file", title.absolute, wordlists);

  for (const report of [tree, worktree, apk, update, notes, manifest]) {
    assertScannerReportMatches(report, audit, report.input.mode);
  }
  if (tree.input.gitTreeOid !== audit.sourceTree.gitTreeOid
      || tree.input.sha256 !== audit.sourceTree.inputSha256
      || tree.reportSha256 !== audit.sourceTree.reportSha256
      || worktree.input.sha256 !== audit.worktree.inputSha256
      || worktree.reportSha256 !== audit.worktree.reportSha256
      || apk.input.sha256 !== audit.apk.inputSha256
      || apk.input.zipEntryManifestSha256 !== audit.apk.zipEntryManifestSha256
      || apk.reportSha256 !== audit.apk.reportSha256
      || apk.thirdPartyPolicy?.manifestSha256
        !== audit.thirdPartyProvenance.manifestSha256
      || apk.thirdPartyPolicy?.profileId !== audit.thirdPartyProvenance.profileId
      || apk.thirdPartyPolicy?.matchedEntryCount
        !== audit.thirdPartyProvenance.matchedEntryCount
      || update.input.sha256 !== audit.releaseMetadata.update.inputSha256
      || update.reportSha256 !== audit.releaseMetadata.update.reportSha256
      || notes.input.sha256 !== audit.releaseMetadata.notes.inputSha256
      || notes.reportSha256 !== audit.releaseMetadata.notes.reportSha256
      || manifest.input.sha256 !== candidate.manifest.sha256) fail("candidate audit mismatch");
  runSourceVerifier(candidate);
}

function originSlug() {
  const raw = run("git", ["remote", "get-url", "origin"]).stdout.trim();
  const match = /^(?:https:\/\/github\.com\/|git@github\.com:)([^/]+)\/([^/]+?)(?:\.git)?$/u
    .exec(raw);
  if (!match) fail("origin is not a canonical GitHub repository");
  const owner = match[1];
  const repo = match[2];
  if (!/^[A-Za-z0-9](?:[A-Za-z0-9-]{0,37}[A-Za-z0-9])?$/u.test(owner)
      || !/^[A-Za-z0-9_.-]{1,100}$/u.test(repo) || repo.includes("..")) fail();
  return `${owner}/${repo}`;
}

function ghJson(args, { allow404 = false } = {}) {
  const result = run("gh", args, { allowFailure: allow404 });
  if (result.status !== 0) {
    const combined = `${result.stdout || ""}\n${result.stderr || ""}`;
    if (allow404 && /(?:HTTP\s+404|Not Found|"status"\s*:\s*"?404"?)/iu.test(combined)) {
      return { found: false, value: null };
    }
    fail("GitHub metadata could not be read");
  }
  try {
    return { found: true, value: JSON.parse(result.stdout) };
  } catch {
    fail();
  }
}

function flattenPages(value) {
  if (!Array.isArray(value)) fail();
  if (value.length === 0 || value.every((item) => !Array.isArray(item))) return value;
  if (!value.every(Array.isArray)) fail();
  return value.flat();
}

function repositoryProjection(value, slug) {
  if (!value || !Number.isSafeInteger(value.id) || value.id <= 0
      || requiredString(value.node_id, 256) !== value.node_id
      || typeof value.full_name !== "string" || value.full_name.toLowerCase() !== slug.toLowerCase()
      || value.private !== false || value.visibility !== "public"
      || !requiredString(value.default_branch, 255)
      || typeof value[REPOSITORY_CREATED_AT_KEY] !== "string"
      || !Number.isFinite(Date.parse(value[REPOSITORY_CREATED_AT_KEY]))
      || (value.description !== null && typeof value.description !== "string")
      || (value.homepage !== null && typeof value.homepage !== "string")
      || !Array.isArray(value.topics) || value.topics.some((topic) => typeof topic !== "string")) {
    fail("source repository is not an exact public repository");
  }
  return canonical({
    createdAt: value[REPOSITORY_CREATED_AT_KEY],
    defaultBranch: value.default_branch,
    description: value.description,
    fullName: value.full_name.toLowerCase(),
    homepage: value.homepage,
    id: value.id,
    nodeId: value.node_id,
    private: value.private,
    topics: [...value.topics].sort(),
    visibility: value.visibility,
  });
}

function branchProjection(value) {
  if (!value || !requiredString(value.name, 255) || !oidValue(value.commit?.sha)
      || typeof value.protected !== "boolean") fail();
  return { commitSha: value.commit.sha, name: value.name, protected: value.protected };
}

function tagProjection(value) {
  if (!value || !SAFE_NAME.test(requiredString(value.name, 180))
      || !oidValue(value.commit?.sha)) fail();
  return { commitSha: value.commit.sha, name: value.name };
}

function assetProjection(value) {
  if (!value || !positiveInteger(value.id) || !requiredString(value.node_id, 256)
      || !SAFE_NAME.test(requiredString(value.name, 180))
      || (value.label !== null && typeof value.label !== "string")
      || value.state !== "uploaded" || !positiveInteger(value.size)
      || !requiredString(value.content_type, 256)
      || typeof value.digest !== "string" || !/^sha256:[a-f0-9]{64}$/u.test(value.digest)
      || !requiredString(value.url) || !requiredString(value.browser_download_url)) fail();
  return canonical({
    browserDownloadUrl: value.browser_download_url,
    contentType: value.content_type,
    digest: value.digest.toLowerCase(),
    id: value.id,
    label: value.label,
    name: value.name,
    nodeId: value.node_id,
    size: value.size,
    state: value.state,
    url: value.url,
  });
}

function releaseProjection(value) {
  if (!value || !positiveInteger(value.id) || !requiredString(value.node_id, 256)
      || !SAFE_NAME.test(requiredString(value.tag_name, 180))
      || !requiredString(value.target_commitish, 255)
      || typeof value.name !== "string" || typeof value.body !== "string"
      || typeof value.draft !== "boolean" || typeof value.prerelease !== "boolean"
      || typeof value.immutable !== "boolean" || !Array.isArray(value.assets)) fail();
  const assets = value.assets.map(assetProjection)
    .sort((left, right) => left.name.localeCompare(right.name));
  if (new Set(assets.map((asset) => asset.id)).size !== assets.length
      || new Set(assets.map((asset) => asset.name)).size !== assets.length) fail();
  return canonical({
    assets,
    body: value.body,
    draft: value.draft,
    id: value.id,
    immutable: value.immutable,
    name: value.name,
    nodeId: value.node_id,
    prerelease: value.prerelease,
    tagName: value.tag_name,
    targetCommitish: value.target_commitish,
  });
}

function parseRemoteRefs(text) {
  if (typeof text !== "string" || !text.endsWith("\n")) fail();
  const refs = [];
  let symbolicHead = "";
  for (const line of text.split("\n").filter(Boolean)) {
    const symbolic = /^ref:\s+(refs\/heads\/.+)\tHEAD$/u.exec(line);
    if (symbolic) {
      if (symbolicHead) fail();
      symbolicHead = symbolic[1];
      continue;
    }
    const match = /^([a-f0-9]{40}|[a-f0-9]{64})\t(HEAD|refs\/(?:heads|tags|pull)\/.+)$/u
      .exec(line);
    if (!match) fail();
    refs.push({ oid: match[1], ref: match[2] });
  }
  if (!symbolicHead || refs.length === 0
      || new Set(refs.map((entry) => entry.ref)).size !== refs.length) fail();
  return canonical({ refs: refs.sort((a, b) => a.ref.localeCompare(b.ref)), symbolicHead });
}

function captureLatest(slug) {
  const response = ghJson(["api", `repos/${slug}/releases/latest`], { allow404: true });
  return response.found
    ? { kind: "present", release: releaseProjection(response.value) }
    : { kind: "absent" };
}

function capturePublicState(slug, wordlists) {
  const repositoryRaw = ghJson(["api", `repos/${slug}`]).value;
  const releasesRaw = flattenPages(ghJson([
    "api", `repos/${slug}/releases?per_page=100`, "--paginate", "--slurp",
  ]).value);
  const branchesRaw = flattenPages(ghJson([
    "api", `repos/${slug}/branches?per_page=100`, "--paginate", "--slurp",
  ]).value);
  const tagsRaw = flattenPages(ghJson([
    "api", `repos/${slug}/tags?per_page=100`, "--paginate", "--slurp",
  ]).value);
  const refsRaw = run("git", ["ls-remote", "--symref", "origin"]).stdout;
  const repository = repositoryProjection(repositoryRaw, slug);
  const releases = releasesRaw.map(releaseProjection)
    .sort((left, right) => left.id - right.id);
  const branches = branchesRaw.map(branchProjection)
    .sort((left, right) => left.name.localeCompare(right.name));
  const tags = tagsRaw.map(tagProjection)
    .sort((left, right) => left.name.localeCompare(right.name));
  if (new Set(releases.map((release) => release.id)).size !== releases.length
      || new Set(releases.map((release) => release.tagName)).size !== releases.length
      || new Set(branches.map((branch) => branch.name)).size !== branches.length
      || new Set(tags.map((tag) => tag.name)).size !== tags.length) fail();
  const state = canonical({
    branches,
    latest: captureLatest(slug),
    releases,
    remoteRefs: parseRemoteRefs(refsRaw),
    repository,
    tags,
  });
  const input = stableWriteAudit("github-public-state.json",
    Buffer.from(`${canonicalJson(state)}\n`, "utf8"));
  runScanner("file", input.absolute, wordlists);
  return state;
}

function remoteTagCommit(state, tag) {
  const peeled = state.remoteRefs.refs.find((entry) => entry.ref === `refs/tags/${tag}^{}`);
  const direct = state.remoteRefs.refs.find((entry) => entry.ref === `refs/tags/${tag}`);
  return peeled?.oid || direct?.oid || "";
}

function assertExactPublicRefEnvelope(state, sourceCommit, { betaRefExpected = false } = {}) {
  const defaultBranch = state.branches.find(
    (branch) => branch.name === state.repository.defaultBranch,
  );
  const head = state.remoteRefs.refs.find((entry) => entry.ref === "HEAD");
  if (state.branches.length !== 1 || !defaultBranch || defaultBranch.commitSha !== sourceCommit
      || state.remoteRefs.symbolicHead !== `refs/heads/${state.repository.defaultBranch}`
      || head?.oid !== sourceCommit) fail("default branch is not the candidate source");

  const expectedTags = new Set(state.tags.map((tag) => tag.name));
  if (betaRefExpected !== expectedTags.has(CHANNEL_TAG)) {
    fail("beta ref presence is not exact");
  }
  const expectedRefs = new Set([
    "HEAD",
    `refs/heads/${state.repository.defaultBranch}`,
    ...[...expectedTags].map((tag) => `refs/tags/${tag}`),
  ]);
  if (state.remoteRefs.refs.length !== expectedRefs.size
      || state.remoteRefs.refs.some((entry) => !expectedRefs.has(entry.ref))) {
    fail("unexpected branch, tag, or pull ref exists");
  }
  const branchRef = state.remoteRefs.refs.find(
    (entry) => entry.ref === `refs/heads/${state.repository.defaultBranch}`,
  );
  if (branchRef?.oid !== sourceCommit) fail("default branch transport ref changed");
  for (const tag of state.tags) {
    const direct = state.remoteRefs.refs.find((entry) => entry.ref === `refs/tags/${tag.name}`);
    if (!direct || direct.oid !== tag.commitSha) fail("tag API and transport ref disagree");
  }
}

function assertHistoryAndLatestState(state, sourceCommit, options = {}) {
  assertExactPublicRefEnvelope(state, sourceCommit, options);

  let historicalCommit = "";

  for (const tag of HISTORICAL_TAGS) {
    const apiTag = state.tags.find((entry) => entry.name === tag);
    const release = state.releases.find((entry) => entry.tagName === tag);
    const version = tag.slice(1);
    const expectedAssets = [`autoform-kit-${version}.apk`, UPDATE_NAME].sort();
    if (!apiTag || remoteTagCommit(state, tag) !== apiTag.commitSha
        || !release || release.targetCommitish !== apiTag.commitSha
        || release.name !== `autoform-kit ${version}` || release.draft !== false
        || release.prerelease !== false || release.body !== HISTORICAL_BODY
        || canonicalJson(release.assets.map((asset) => asset.name).sort())
          !== canonicalJson(expectedAssets)) fail("sanitized historical Release semantics changed");
    if (!historicalCommit) historicalCommit = apiTag.commitSha;
    if (historicalCommit !== apiTag.commitSha) {
      fail("sanitized historical tags do not share one reviewed commit");
    }
  }
  if (run("git", ["merge-base", "--is-ancestor", historicalCommit, sourceCommit],
    { allowFailure: true }).status !== 0) {
    fail("sanitized historical commit is not an ancestor of the candidate source");
  }

  const nonHistory = state.releases.filter(
    (release) => !HISTORICAL_TAGS.includes(release.tagName) && release.tagName !== CHANNEL_TAG,
  );
  if (state.latest.kind === "absent") {
    if (nonHistory.length !== 0) fail("unexpected non-historical Release exists without latest");
  } else {
    const historicalLatest = HISTORICAL_TAGS.includes(state.latest.release.tagName)
      ? state.releases.find((release) => release.tagName === state.latest.release.tagName) : null;
    if (historicalLatest) {
      if (nonHistory.length !== 0
          || canonicalJson(historicalLatest) !== canonicalJson(state.latest.release)) {
        fail("existing latest is not one exact validated historical Release");
      }
    } else {
      const stableTag = nonHistory.length === 1
        ? state.tags.find((tag) => tag.name === nonHistory[0].tagName) : null;
      if (nonHistory.length !== 1 || !stableTag
          || canonicalJson(nonHistory[0]) !== canonicalJson(state.latest.release)
          || nonHistory[0].draft !== false || nonHistory[0].prerelease !== false
          || !/^v[0-9]+\.[0-9]+\.[0-9]+(?:\+[0-9A-Za-z.-]+)?$/u.test(nonHistory[0].tagName)
          || nonHistory[0].targetCommitish !== stableTag.commitSha
          || remoteTagCommit(state, nonHistory[0].tagName) !== stableTag.commitSha) {
        fail("existing latest is not one exact stable Release");
      }
    }
  }
  const allowedTags = new Set([
    ...HISTORICAL_TAGS,
    ...nonHistory.map((release) => release.tagName),
    ...(options.betaRefExpected ? [CHANNEL_TAG] : []),
  ]);
  if (state.tags.some((tag) => !allowedTags.has(tag.name))
      || state.tags.length !== allowedTags.size) fail("unexpected public tag exists");
  return { historicalCommit };
}

function captureReachableObjectClosure(state, sourceCommit, wordlists) {
  const roots = [...new Set([
    sourceCommit,
    ...state.branches.map((branch) => branch.commitSha),
    ...state.tags.map((tag) => tag.commitSha),
  ])].sort();
  if (roots.length === 0) fail();
  for (const root of roots) run("git", ["cat-file", "-e", `${root}^{object}`]);
  const objectLines = run("git", ["rev-list", "--objects", ...roots])
    .stdout.split("\n").filter(Boolean);
  const locationsByObject = new Map();
  for (const line of objectLines) {
    const separator = line.indexOf(" ");
    const oid = separator < 0 ? line : line.slice(0, separator);
    if (!GIT_OID.test(oid)) fail("reachable Git object list is invalid");
    if (!locationsByObject.has(oid)) locationsByObject.set(oid, new Set());
    if (separator >= 0 && line.length > separator + 1) {
      locationsByObject.get(oid).add(line.slice(separator + 1));
    }
  }
  const objectIds = [...locationsByObject.keys()].sort();
  if (objectIds.length === 0 || roots.some((root) => !locationsByObject.has(root))) {
    fail("reachable Git object closure is incomplete");
  }
  const checked = run("git", ["cat-file",
    "--batch-check=%(objectname) %(objecttype) %(objectsize)"], {
    input: `${objectIds.join("\n")}\n`,
  }).stdout.trim().split("\n").filter(Boolean);
  if (checked.length !== objectIds.length) fail("reachable Git objects could not be typed");
  const closure = [];
  const aggregate = [];
  let aggregateSize = 0;
  for (let index = 0; index < checked.length; index += 1) {
    const match = /^([a-f0-9]{40}|[a-f0-9]{64}) (blob|commit|tag|tree) ([0-9]+)$/u
      .exec(checked[index]);
    if (!match || match[1] !== objectIds[index]) fail("reachable Git object identity changed");
    const size = Number(match[3]);
    if (!Number.isSafeInteger(size) || size < 0) fail();
    const content = run("git", ["cat-file", match[2], match[1]], { binary: true }).stdout;
    if (!Buffer.isBuffer(content) || content.length !== size) {
      fail("reachable Git object bytes changed");
    }
    const entry = canonical({
      contentSha256: digest(content),
      locations: [...locationsByObject.get(match[1])].sort(),
      oid: match[1],
      size,
      type: match[2],
    });
    const metadata = Buffer.from(canonicalJson(entry), "utf8");
    aggregateSize += metadata.length + content.length + 2;
    if (aggregateSize > MAX_FILE_BYTES) fail("reachable Git object closure is too large");
    aggregate.push(metadata, Buffer.from([0]), content, Buffer.from([0]));
    closure.push(entry);
  }
  const sourceEntry = closure.find((entry) => entry.oid === sourceCommit);
  if (!sourceEntry || sourceEntry.type !== "commit") fail("source commit is absent from closure");
  const aggregateInput = stableWriteAudit(
    "reachable-git-objects.bin", Buffer.concat(aggregate, aggregateSize),
  );
  runScanner("file", aggregateInput.absolute, wordlists);
  const closureInput = stableWriteAudit("reachable-git-closure.json",
    Buffer.from(`${canonicalJson(closure)}\n`, "utf8"));
  runScanner("file", closureInput.absolute, wordlists);
  return canonical({
    objectCount: closure.length,
    objectIdentitySha256: digest(Buffer.from(canonicalJson(closure), "utf8")),
  });
}

function assertBetaRefAbsent(slug, state) {
  if (state.tags.some((tag) => tag.name === CHANNEL_TAG)
      || state.remoteRefs.refs.some((entry) => entry.ref === `refs/tags/${CHANNEL_TAG}`
        || entry.ref === `refs/tags/${CHANNEL_TAG}^{}`)) fail("beta ref already exists");
  const ref = ghJson(["api", `repos/${slug}/git/ref/tags/${CHANNEL_TAG}`],
    { allow404: true });
  if (ref.found) fail("beta ref already exists");
}

function assertBetaAbsent(slug, state) {
  assertBetaRefAbsent(slug, state);
  if (state.releases.some((release) => release.tagName === CHANNEL_TAG)) {
    fail("beta tag or Release already exists");
  }
  const release = ghJson(["api", `repos/${slug}/releases/tags/${CHANNEL_TAG}`],
    { allow404: true });
  if (release.found) fail("beta tag or Release already exists");
}

function stateWithoutBeta(state) {
  return canonical({
    branches: state.branches,
    latest: state.latest,
    releases: state.releases.filter((release) => release.tagName !== CHANNEL_TAG),
    remoteRefs: {
      refs: state.remoteRefs.refs.filter((entry) => entry.ref !== `refs/tags/${CHANNEL_TAG}`
        && entry.ref !== `refs/tags/${CHANNEL_TAG}^{}`),
      symbolicHead: state.remoteRefs.symbolicHead,
    },
    repository: state.repository,
    tags: state.tags.filter((tag) => tag.name !== CHANNEL_TAG),
  });
}

function assertPreserved(before, after) {
  if (canonicalJson(stateWithoutBeta(after)) !== canonicalJson(before)) {
    fail("latest, history, repository, branches, or unrelated refs changed");
  }
}

function expectedReleaseAssets(candidate) {
  return new Map([
    [APK_NAME, candidate.apk],
    [UPDATE_NAME, candidate.update],
    [MANIFEST_NAME, candidate.manifest],
  ]);
}

function assertBetaTag(slug, sourceCommit, state) {
  const apiTag = state.tags.find((tag) => tag.name === CHANNEL_TAG);
  if (!apiTag || apiTag.commitSha !== sourceCommit
      || remoteTagCommit(state, CHANNEL_TAG) !== sourceCommit) fail("beta tag target mismatch");
  const response = ghJson(["api", `repos/${slug}/git/ref/tags/${CHANNEL_TAG}`]);
  if (response.value?.ref !== `refs/tags/${CHANNEL_TAG}`
      || response.value?.object?.type !== "commit"
      || response.value?.object?.sha !== sourceCommit) fail("beta tag is not an exact commit ref");
}

function assertBetaReleaseEnvelope(release, candidate, expectedDraft) {
  const expected = expectedReleaseAssets(candidate);
  if (!release || release.tagName !== CHANNEL_TAG
      || release.targetCommitish !== candidate.value.source.commit
      || release.name !== RELEASE_TITLE || release.body !== RELEASE_BODY_BYTES.toString("utf8")
      || release.draft !== expectedDraft || release.prerelease !== true
      || release.immutable !== false || release.assets.length !== expected.size) {
    fail("beta Release envelope mismatch");
  }
  for (const asset of release.assets) {
    const input = expected.get(asset.name);
    if (!input || asset.size !== input.size || asset.digest !== `sha256:${input.sha256}`) {
      fail("beta Release asset closure mismatch");
    }
  }
  return release;
}

function readBetaReleaseByTag(slug) {
  const response = ghJson(["api", `repos/${slug}/releases/tags/${CHANNEL_TAG}`]);
  return releaseProjection(response.value);
}

function readBetaReleaseById(slug, releaseId) {
  const response = ghJson(["api", `repos/${slug}/releases/${releaseId}`]);
  return releaseProjection(response.value);
}

function downloadAsset(slug, asset, expected, wordlists, candidate, aapt, apksigner) {
  const result = run("gh", ["api", `repos/${slug}/releases/assets/${asset.id}`,
    "-H", "Accept: application/octet-stream"], { binary: true });
  if (!Buffer.isBuffer(result.stdout) || result.stdout.length !== expected.size
      || digest(result.stdout) !== expected.sha256) fail("downloaded beta asset mismatch");
  const input = stableWriteAudit(`download-${asset.name}`, result.stdout);
  const mode = asset.name === APK_NAME ? "apk" : "file";
  runScanner(mode, input.absolute, wordlists);
  if (asset.name === APK_NAME) {
    const identity = apkIdentity(input, aapt, apksigner);
    if (canonicalJson(identity) !== canonicalJson(candidate.value.app)) fail();
    runSourceVerifier({ ...candidate, apk: input });
  }
}

function verifyRemoteBeta(slug, state, candidate, expectedDraft, expectedReleaseId, wordlists,
  aapt, apksigner) {
  const listedBetas = state.releases.filter((release) => release.tagName === CHANNEL_TAG);
  if (listedBetas.length !== 1) fail("beta draft or Release is not unique");
  remoteBetaKnown = true;
  const listed = listedBetas[0];
  if (expectedReleaseId !== null && listed.id !== expectedReleaseId) {
    fail("beta Release identity mismatch");
  }
  if (expectedDraft) assertBetaRefAbsent(slug, state);
  else assertBetaTag(slug, candidate.value.source.commit, state);
  const direct = readBetaReleaseById(slug, listed.id);
  if (canonicalJson(listed) !== canonicalJson(direct)) fail("beta Release reads disagree");
  if (!expectedDraft) {
    const tagged = readBetaReleaseByTag(slug);
    if (canonicalJson(direct) !== canonicalJson(tagged)) fail("beta Release reads disagree");
  }
  const release = assertBetaReleaseEnvelope(direct, candidate, expectedDraft);
  const expected = expectedReleaseAssets(candidate);
  for (const asset of release.assets) {
    downloadAsset(slug, asset, expected.get(asset.name), wordlists, candidate, aapt, apksigner);
  }
  return release;
}

function preflight(candidate, previousApk, wordlists, slug, aapt, apksigner,
  resumeDraftId, expectedEvidence = null) {
  assertCandidateInputsStable(candidate, previousApk, wordlists);
  const rereadManifest = stableJson(candidate.manifest.absolute);
  const rereadPrevious = stableReadRegular(previousApk.absolute, { privateMode: true });
  const freshCandidate = validateCandidate(rereadManifest, rereadPrevious, aapt, apksigner);
  runCandidateAudits(freshCandidate, wordlists);
  assertCandidateInputsStable(candidate, previousApk, wordlists);
  const state = capturePublicState(slug, wordlists);
  assertHistoryAndLatestState(state, candidate.value.source.commit);
  if (resumeDraftId === undefined) assertBetaAbsent(slug, state);
  else verifyRemoteBeta(
    slug, state, candidate, true, resumeDraftId, wordlists, aapt, apksigner,
  );
  const closure = captureReachableObjectClosure(
    state, candidate.value.source.commit, wordlists,
  );
  const evidence = canonical({ closure, state });
  if (expectedEvidence && canonicalJson(evidence) !== canonicalJson(expectedEvidence)) {
    fail("public state changed between preflights");
  }
  return evidence;
}

function main() {
  const args = parseArguments(process.argv.slice(2));
  if (args.help) {
    usage();
    return;
  }
  auditDirectory = fs.mkdtempSync(path.join(os.tmpdir(), "autoform-beta-publisher."));
  fs.chmodSync(auditDirectory, 0o700);
  const manifest = stableJson(args.candidate);
  if (path.basename(manifest.absolute) !== MANIFEST_NAME) fail();
  const previousApk = stableReadRegular(args.previousApk, { privateMode: true });
  const wordlists = args.wordlists.map((filename) =>
    stableReadRegular(filename, { privateMode: true, maximum: MAX_JSON_BYTES }));
  if (new Set(wordlists.map((wordlist) => wordlist.absolute)).size !== wordlists.length
      || wordlists.some((wordlist) => wordlist.absolute.startsWith(`${ROOT}${path.sep}`))) {
    fail("private wordlists must be unique and outside the repository");
  }
  const aapt = findAndroidTool("aapt", process.env.AAPT);
  const apksigner = findAndroidTool("apksigner", process.env.APKSIGNER);
  const candidate = validateCandidate(manifest, previousApk, aapt, apksigner);
  run("gh", ["auth", "status", "--hostname", "github.com"]);
  const slug = originSlug();

  info("running first complete read-only beta preflight");
  const before = preflight(
    candidate, previousApk, wordlists, slug, aapt, apksigner, args.resumeDraftId,
  );
  info("running second complete read-only beta preflight");
  preflight(
    candidate, previousApk, wordlists, slug, aapt, apksigner, args.resumeDraftId, before,
  );

  assertCandidateInputsStable(candidate, previousApk, wordlists);
  if (process.env[SINGLE_WRITER_ENV] !== SINGLE_WRITER_CONFIRMATION) {
    fail("the explicit single-writer release window was not confirmed");
  }
  const preservedState = stateWithoutBeta(before.state);
  if (args.resumeDraftId === undefined) {
    sideEffectStarted = true;
    info("creating fixed beta prerelease as a non-latest draft");
    run("gh", ["release", "create", CHANNEL_TAG,
      candidate.apk.absolute,
      candidate.update.absolute,
      candidate.manifest.absolute,
      "--repo", slug,
      "--target", candidate.value.source.commit,
      "--title", RELEASE_TITLE,
      "--notes-file", candidate.notes.absolute,
      "--draft",
      "--prerelease",
      "--latest=false",
    ]);
  } else {
    info(`resuming exact existing beta draft Release ${args.resumeDraftId}`);
  }

  assertCandidateInputsStable(candidate, previousApk, wordlists);
  const draftState = capturePublicState(slug, wordlists);
  assertHistoryAndLatestState(draftState, candidate.value.source.commit);
  assertPreserved(preservedState, draftState);
  const draftClosure = captureReachableObjectClosure(
    draftState, candidate.value.source.commit, wordlists,
  );
  if (canonicalJson(draftClosure) !== canonicalJson(before.closure)) fail();
  const draft = verifyRemoteBeta(
    slug, draftState, candidate, true, args.resumeDraftId ?? null,
    wordlists, aapt, apksigner,
  );
  if (canonicalJson(draftState.latest) !== canonicalJson(before.state.latest)) fail();

  const patch = Buffer.from(`${JSON.stringify({
    body: RELEASE_BODY_BYTES.toString("utf8"),
    draft: false,
    make_latest: "false",
    name: RELEASE_TITLE,
    prerelease: true,
    tag_name: CHANNEL_TAG,
    target_commitish: candidate.value.source.commit,
  })}\n`, "utf8");

  info("revalidating the complete draft at the single-writer publication boundary");
  assertCandidateInputsStable(candidate, previousApk, wordlists);
  const boundaryState = capturePublicState(slug, wordlists);
  assertHistoryAndLatestState(boundaryState, candidate.value.source.commit);
  assertPreserved(preservedState, boundaryState);
  if (canonicalJson(boundaryState) !== canonicalJson(draftState)) {
    fail("draft or public state changed before publication");
  }
  const boundaryClosure = captureReachableObjectClosure(
    boundaryState, candidate.value.source.commit, wordlists,
  );
  if (canonicalJson(boundaryClosure) !== canonicalJson(before.closure)) fail();
  const boundaryDraft = verifyRemoteBeta(
    slug, boundaryState, candidate, true, draft.id, wordlists, aapt, apksigner,
  );
  if (canonicalJson(boundaryDraft) !== canonicalJson(draft)
      || canonicalJson(boundaryState.latest) !== canonicalJson(before.state.latest)) fail();
  assertCandidateInputsStable(candidate, previousApk, wordlists);

  sideEffectStarted = true;
  info("making the twice-verified beta draft public without changing latest");
  const patchResult = run("gh", ["api", `repos/${slug}/releases/${draft.id}`,
    "--method", "PATCH", "--input", "-"], { input: patch });
  let patchedRelease;
  try {
    patchedRelease = releaseProjection(JSON.parse(patchResult.stdout));
  } catch {
    fail("beta publication response is invalid");
  }
  assertBetaReleaseEnvelope(patchedRelease, candidate, false);
  if (patchedRelease.id !== draft.id) fail("beta publication response changed Release identity");

  assertCandidateInputsStable(candidate, previousApk, wordlists);
  const finalState = capturePublicState(slug, wordlists);
  assertHistoryAndLatestState(
    finalState, candidate.value.source.commit, { betaRefExpected: true },
  );
  assertPreserved(preservedState, finalState);
  verifyRemoteBeta(
    slug, finalState, candidate, false, draft.id, wordlists, aapt, apksigner,
  );
  const finalClosure = captureReachableObjectClosure(
    finalState, candidate.value.source.commit, wordlists,
  );
  if (canonicalJson(finalClosure) !== canonicalJson(before.closure)
      || canonicalJson(finalState.latest) !== canonicalJson(before.state.latest)) fail();
  info("published and verified fixed beta prerelease without changing latest or history");
  fs.rmSync(auditDirectory, { recursive: true, force: false });
  auditDirectory = "";
}

try {
  main();
} catch (error) {
  process.stderr.write(`${error instanceof Error ? error.message : "publish-beta-release failed"}\n`);
  process.exitCode = 1;
}
