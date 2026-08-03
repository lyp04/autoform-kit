#!/usr/bin/env node

/**
 * Publish one reviewed schema-4 history-rebuild candidate without changing tags or latest.
 *
 * The target sanitized tag must already exist. A non-draft historical Release is first created as
 * a draft, its two exact assets are downloaded and rescanned, and only then made public with
 * latest=false. This command never deletes, overwrites, force-pushes, or creates a tag.
 */

import { spawnSync } from "node:child_process";
import crypto from "node:crypto";
import fs, { constants } from "node:fs";
import os from "node:os";
import path from "node:path";
import process from "node:process";
import { fileURLToPath } from "node:url";
import {
  canonicalJson,
  digest,
  HISTORICAL_BODY,
  HISTORICAL_TAGS,
  loadHistoricalInventory,
  privateWordlistSetIdentity,
  stableReadPrivateJson,
  validatePrivateHistoryAttestation,
  validateHistoricalCandidate,
} from "./historical-release-contract.mjs";
import {
  githubPublicMetadataBinding,
  normalizeGithubBranchesAndTags,
  normalizeGithubReleases,
  normalizeRemoteRefs,
  normalizedAuditBytes,
} from "./normalize-github-releases-for-audit.mjs";

const HERE = path.dirname(fileURLToPath(import.meta.url));
const ROOT = path.resolve(HERE, "..");
const THIS_FILE = fileURLToPath(import.meta.url);
const SCANNER = path.join(HERE, "public-surface-audit.mjs");
const NORMALIZER = path.join(HERE, "normalize-github-releases-for-audit.mjs");
const CONTRACT = path.join(HERE, "historical-release-contract.mjs");
const THIRD_PARTY_POLICY = path.join(HERE, "apk-third-party-components.json");
const RUNTIME_LOCK = path.join(HERE, "android-runtime-dependencies.lock.json");
const SOURCE_VERIFIER = path.join(HERE, "verify-apk-third-party-sources.mjs");
const MAX_FILE_BYTES = 1024 * 1024 * 1024;
const MAX_HISTORY_AGGREGATE_BYTES = 128 * 1024 * 1024;
const HEX_64 = /^[a-f0-9]{64}$/u;
let sideEffectStarted = false;
let temporaryDirectory = "";
let metadataCaptureSequence = 0;
let targetVerificationSequence = 0;
let historyClosureSequence = 0;

function fail(message = "historical publication validation failed") {
  const prefix = sideEffectStarted
    ? "publish-historical-release: remote Release may now contain a draft or partial change; "
    : "publish-historical-release: ";
  throw new Error(`${prefix}${message}`);
}

function info(message) {
  process.stdout.write(`publish-historical-release: ${message}\n`);
}

function run(command, args, { binary = false, input, allowFailure = false } = {}) {
  const result = spawnSync(command, args, {
    cwd: ROOT,
    encoding: binary ? null : "utf8",
    input,
    maxBuffer: 1024 * 1024 * 1024,
    env: { ...process.env },
  });
  if (result.status !== 0 && !allowFailure) fail("a required local or GitHub check failed");
  return result;
}

function sameStat(left, right) {
  return left.dev === right.dev && left.ino === right.ino && left.mode === right.mode
    && left.uid === right.uid && left.nlink === right.nlink && left.size === right.size
    && left.mtimeNs === right.mtimeNs && left.ctimeNs === right.ctimeNs;
}

function stableReadRegular(filename, { allowEmpty = false, privateMode = false } = {}) {
  const absolute = path.resolve(filename);
  const before = fs.lstatSync(absolute, { bigint: true });
  const expectedUid = typeof process.geteuid === "function" ? BigInt(process.geteuid()) : before.uid;
  if (!before.isFile() || before.isSymbolicLink() || before.nlink !== 1n
      || before.uid !== expectedUid
      || (privateMode && (before.mode & 0o7777n) !== 0o600n)
      || (!allowEmpty && before.size <= 0n) || before.size < 0n
      || before.size > BigInt(MAX_FILE_BYTES)) fail();
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

function parseArguments(argv) {
  const output = { wordlists: [] };
  for (let index = 0; index < argv.length;) {
    const key = argv[index];
    if (key === "-h" || key === "--help") return { help: true };
    const value = argv[index + 1];
    if (!value) fail("an option value is missing");
    if (key === "--private-wordlist") output.wordlists.push(value);
    else if ([
      "--candidate", "--inventory", "--inventory-file-sha256", "--private-history-audit",
      "--private-history-audit-file-sha256",
    ].includes(key)
      && !output[key.slice(2)]) output[key.slice(2)] = value;
    else fail("an unsupported or duplicate option was provided");
    index += 2;
  }
  if (!output.candidate || !output.inventory || !output["inventory-file-sha256"]
      || !output["private-history-audit"]
      || !output["private-history-audit-file-sha256"]
      || output.wordlists.length === 0 || !HEX_64.test(output["inventory-file-sha256"])
      || !HEX_64.test(output["private-history-audit-file-sha256"])) fail();
  return output;
}

function usage() {
  process.stdout.write(`Usage: tools/publish-historical-release.mjs \\
  --candidate PATH --inventory PATH --inventory-file-sha256 SHA256 \\
  --private-history-audit PATH --private-history-audit-file-sha256 SHA256 \\
  --private-wordlist PATH [--private-wordlist PATH ...]\n\n`
  + "Publishes exactly one schema-4 v1.0.0-v1.0.6 rebuild from an already existing "
  + "sanitized tag. It always uses latest=false and never uploads candidate-manifest.json.\n");
}

function repositorySlug() {
  const raw = run("git", ["remote", "get-url", "origin"]).stdout.trim();
  const match = /^(?:https:\/\/github\.com\/|git@github\.com:)([^/]+)\/([^/]+?)(?:\.git)?$/u
    .exec(raw);
  if (!match) fail("origin is not one exact GitHub repository");
  return `${match[1]}/${match[2]}`;
}

function repositorySelection(slug) {
  const raw = JSON.parse(run("gh", ["api", `repos/${slug}`]).stdout);
  return {
    id: raw.id,
    node_id: raw.node_id,
    full_name: raw.full_name,
    visibility: raw.visibility,
    private: raw.private,
    description: raw.description,
    homepage: raw.homepage,
    topics: raw.topics,
    default_branch: raw.default_branch,
  };
}

function sourceBlobMatches(commit, relative, local) {
  const expected = run("git", ["rev-parse", "--verify", `${commit}:${relative}`]).stdout.trim();
  const actual = run("git", ["hash-object", "--no-filters", "--", local]).stdout.trim();
  if (!/^[a-f0-9]{40}(?:[a-f0-9]{24})?$/u.test(expected) || actual !== expected) fail();
}

function assertSource(candidate) {
  const head = run("git", ["rev-parse", "HEAD"]).stdout.trim();
  const branch = run("git", ["symbolic-ref", "--quiet", "--short", "HEAD"]).stdout.trim();
  const status = run("git", ["status", "--porcelain", "--untracked-files=normal"]).stdout;
  if (head !== candidate.source.commit || branch !== "main" || status !== "") fail();
  for (const [relative, local] of [
    ["tools/publish-historical-release.mjs", THIS_FILE],
    ["tools/historical-release-contract.mjs", CONTRACT],
    ["tools/normalize-github-releases-for-audit.mjs", NORMALIZER],
    ["tools/public-surface-audit.mjs", SCANNER],
    ["tools/apk-third-party-components.json", THIRD_PARTY_POLICY],
    ["tools/android-runtime-dependencies.lock.json", RUNTIME_LOCK],
    ["tools/verify-apk-third-party-sources.mjs", SOURCE_VERIFIER],
  ]) sourceBlobMatches(head, relative, local);
}

function assertPrivateWordlists(wordlists) {
  return wordlists.map((filename) => {
    const input = stableReadRegular(filename, { privateMode: true });
    if (input.absolute === ROOT || input.absolute.startsWith(`${ROOT}${path.sep}`)) fail();
    return input;
  });
}

function runScanner(args, wordlists, expectedSha = "") {
  const commandArgs = [SCANNER, ...args];
  for (const wordlist of wordlists) commandArgs.push("--private-wordlist", wordlist.absolute);
  const result = run(process.execPath, commandArgs);
  let report;
  try {
    report = JSON.parse(result.stdout);
  } catch {
    fail();
  }
  if (report?.schemaVersion !== 1 || report?.summary?.passed !== true
      || report?.summary?.findingCount !== 0 || !Array.isArray(report.findings)
      || report.findings.length !== 0 || report.privatePolicy?.applied !== true
      || report.privatePolicy?.wordlistCount !== wordlists.length
      || report.scannerSha256 !== digest(fs.readFileSync(SCANNER))
      || (expectedSha && report.input?.sha256 !== expectedSha)) fail();
  const { reportSha256, ...reportBase } = report;
  if (!HEX_64.test(String(reportSha256 || ""))
      || reportSha256 !== digest(Buffer.from(canonicalJson(reportBase), "utf8"))) fail();
  return report;
}

function assertCandidateFiles(candidateInput, candidate) {
  const directory = path.dirname(candidateInput.path);
  if (path.basename(candidateInput.path) !== "candidate-manifest.json"
      || path.basename(directory) !== candidate.tag) fail();
  const names = fs.readdirSync(directory).sort();
  const expected = [
    candidate.artifacts.apk.file,
    candidate.artifacts.notes.file,
    candidate.artifacts.update.file,
    "candidate-manifest.json",
  ].sort();
  if (canonicalJson(names) !== canonicalJson(expected)) fail();
  const files = {
    apk: stableReadRegular(path.join(directory, candidate.artifacts.apk.file)),
    notes: stableReadRegular(path.join(directory, candidate.artifacts.notes.file)),
    update: stableReadRegular(path.join(directory, candidate.artifacts.update.file)),
    manifest: stableReadRegular(candidateInput.path, { privateMode: true }),
  };
  if (files.manifest.sha256 !== candidateInput.fileSha256) fail();
  for (const kind of ["apk", "notes", "update"]) {
    if (files[kind].sha256 !== candidate.artifacts[kind].sha256) fail();
  }
  const publicationByFile = new Map(candidate.historicalRelease.publication.assets
    .map((asset) => [asset.file, asset]));
  for (const kind of ["apk", "update"]) {
    const asset = publicationByFile.get(candidate.artifacts[kind].file);
    if (!asset || asset.sha256 !== candidate.artifacts[kind].sha256
        || asset.size !== files[kind].size) fail();
  }
  if (!files.notes.bytes.equals(Buffer.from(HISTORICAL_BODY, "utf8"))) fail();
  let update;
  try {
    update = JSON.parse(files.update.bytes.toString("utf8"));
  } catch {
    fail();
  }
  if (update?.packageName !== candidate.app.packageName
      || update?.versionCode !== candidate.app.versionCode
      || update?.versionName !== candidate.app.versionName
      || update?.apkAsset !== candidate.artifacts.apk.file
      || update?.sha256 !== candidate.artifacts.apk.sha256
      || update?.notes !== HISTORICAL_BODY) fail();
  return files;
}

function rebuiltCandidateReference(candidateInput, candidate) {
  return {
    apkSha256: candidate.artifacts.apk.sha256,
    candidateManifestSha256: candidateInput.fileSha256,
    packageName: candidate.app.packageName,
    signerSha256: candidate.app.signerSha256,
    tag: candidate.tag,
    versionCode: candidate.app.versionCode,
    versionName: candidate.app.versionName,
  };
}

function loadHistoricalPrefix(candidateInput, candidate, inventory, inventoryFileSha256) {
  const candidatesRoot = path.dirname(path.dirname(candidateInput.path));
  const entries = [];
  const snapshots = {};
  let previousReference = null;
  for (let sequence = 0; sequence < candidate.lineage.sequence; sequence += 1) {
    const tag = HISTORICAL_TAGS[sequence];
    const manifestPath = path.join(candidatesRoot, tag, "candidate-manifest.json");
    const input = stableReadPrivateJson(manifestPath);
    const value = validateHistoricalCandidate(input.value, inventory, inventoryFileSha256);
    if (value.tag !== tag || value.lineage.sequence !== sequence
        || (sequence === 0 && value.lineage.previousRebuiltCandidate !== null)
        || (sequence > 0 && canonicalJson(value.lineage.previousRebuiltCandidate)
          !== canonicalJson(previousReference))) fail();
    value.__manifestPath = input.path;
    const files = assertCandidateFiles(input, value);
    for (const [kind, file] of Object.entries(files)) {
      snapshots[`prefix-${sequence}-${kind}`] = file;
    }
    previousReference = rebuiltCandidateReference(input, value);
    entries.push({ candidate: value, files, input });
  }
  if (candidate.lineage.sequence === 0) {
    if (candidate.lineage.previousRebuiltCandidate !== null) fail();
  } else if (canonicalJson(candidate.lineage.previousRebuiltCandidate)
      !== canonicalJson(previousReference)) fail();
  return { entries, snapshots };
}

function apkIdentity(filename) {
  const aapt = process.env.AAPT || "aapt";
  const signerTool = process.env.APKSIGNER || "apksigner";
  const first = run(aapt, ["dump", "badging", filename]).stdout.split("\n", 1)[0];
  const packageMatch = /^package: name='([^']+)' versionCode='([1-9][0-9]*)' versionName='([^']+)'/u
    .exec(first);
  const signerOutput = run(signerTool, ["verify", "--print-certs", filename]).stdout;
  const signerMatch = /Signer #1 certificate SHA-256 digest:\s*([a-f0-9]{64})/iu
    .exec(signerOutput);
  if (!packageMatch || !signerMatch) fail();
  return {
    packageName: packageMatch[1],
    versionCode: Number(packageMatch[2]),
    versionName: packageMatch[3],
    signerSha256: signerMatch[1].toLowerCase(),
  };
}

function assertApkIdentity(filename, expected) {
  if (canonicalJson(apkIdentity(filename)) !== canonicalJson({
    packageName: expected.packageName,
    signerSha256: expected.signerSha256,
    versionCode: expected.versionCode,
    versionName: expected.versionName,
  })) fail();
}

function runSourceVerifier(apk, candidate) {
  const result = run(process.execPath, [SOURCE_VERIFIER,
    "--apk", apk.absolute,
    "--policy", THIRD_PARTY_POLICY,
    "--build-dir", path.join(ROOT, "app", "build"),
  ]);
  let report;
  try {
    report = JSON.parse(result.stdout);
  } catch {
    fail();
  }
  const expected = candidate.publicAudit.thirdPartyProvenance;
  if (report?.passed !== true || report.apkSha256 !== apk.sha256
      || report.policySha256 !== expected.manifestSha256
      || report.profileId !== expected.profileId
      || report.verifierSha256 !== expected.sourceVerifierSha256
      || report.reportSha256 !== expected.sourceReportSha256) fail();
}

function auditLocal(candidateInput, candidate, files, wordlists) {
  assertSource(candidate);
  assertApkIdentity(files.apk.absolute, candidate.app);
  runSourceVerifier(files.apk, candidate);
  const commitBytes = run("git", ["cat-file", "commit", candidate.source.commit],
    { binary: true }).stdout;
  const commitFile = path.join(temporaryDirectory, "source-commit.bin");
  fs.writeFileSync(commitFile, commitBytes, { mode: 0o600 });
  runScanner(["--file", commitFile], wordlists, digest(commitBytes));
  runScanner(["--git-tree", candidate.source.commit, "--repo", ROOT], wordlists);
  runScanner(["--worktree", "--repo", ROOT], wordlists);
  runScanner(["--apk", files.apk.absolute], wordlists, files.apk.sha256);
  runScanner(["--file", files.update.absolute], wordlists, files.update.sha256);
  runScanner(["--file", files.notes.absolute], wordlists, files.notes.sha256);
  runScanner(["--file", candidateInput.path], wordlists, candidateInput.fileSha256);
}

function auditHistoricalPrefixLocal(prefix, wordlists) {
  for (const entry of prefix.entries) {
    assertApkIdentity(entry.files.apk.absolute, entry.candidate.app);
    runScanner(["--apk", entry.files.apk.absolute], wordlists, entry.files.apk.sha256);
    runScanner(["--file", entry.files.update.absolute], wordlists, entry.files.update.sha256);
    runScanner(["--file", entry.files.notes.absolute], wordlists, entry.files.notes.sha256);
    runScanner(["--file", entry.input.path], wordlists, entry.input.fileSha256);
  }
}

function ghJson(args, { allowFailure = false } = {}) {
  const result = run("gh", args, { allowFailure });
  if (result.status !== 0) return { ok: false, result };
  try {
    return { ok: true, value: JSON.parse(result.stdout), result };
  } catch {
    fail();
  }
}

function readLatest(slug, repository) {
  const response = ghJson(["api", `repos/${slug}/releases/latest`], { allowFailure: true });
  if (!response.ok) {
    const combined = `${response.result.stdout || ""}\n${response.result.stderr || ""}`;
    if (!/(?:HTTP\s+404|"status"\s*:\s*"?404"?|Not Found)/iu.test(combined)) fail();
    return { kind: "absent" };
  }
  if (response.value.tag_name !== "v1.0.7") fail("latest must be absent or the stable v1.0.7 Release");
  const normalized = normalizeGithubReleases([[response.value]], slug, repository);
  return { kind: "stable-v1.0.7", snapshotSha256: normalized.apiSnapshotSha256 };
}

function assertLatestUnchanged(before, slug, repository) {
  const after = readLatest(slug, repository);
  if (canonicalJson(after) !== canonicalJson(before)) fail("historical publication changed latest");
}

function readRemoteMetadata(slug, repository, wordlists, excludedReleaseTag = "") {
  const branches = ghJson(["api", "--paginate", "--slurp",
    `repos/${slug}/branches?per_page=100`]).value;
  const tags = ghJson(["api", "--paginate", "--slurp",
    `repos/${slug}/tags?per_page=100`]).value;
  const releasePages = ghJson(["api", "--paginate", "--slurp",
    `repos/${slug}/releases?per_page=100`]).value;
  const apiRefs = normalizeGithubBranchesAndTags(branches, tags, slug, repository);
  const releases = normalizeGithubReleases(releasePages, slug, repository);
  const preexistingReleasePages = flatPages(releasePages)
    .filter((release) => release.tag_name !== excludedReleaseTag);
  const preexistingReleases = normalizeGithubReleases(
    [preexistingReleasePages], slug, repository,
  );
  const remoteRaw = run("git", ["ls-remote", "origin"],
    { binary: true }).stdout;
  const remoteRefs = normalizeRemoteRefs(remoteRaw, slug, repository);
  if (apiRefs.refIdentitySha256 !== remoteRefs.refIdentitySha256
      || apiRefs.repositoryBindingSha256 !== releases.repositoryBindingSha256
      || apiRefs.repositoryBindingSha256 !== remoteRefs.repositoryBindingSha256) fail();
  metadataCaptureSequence += 1;
  const suffix = String(metadataCaptureSequence).padStart(2, "0");
  const apiRefsFile = path.join(temporaryDirectory, `${suffix}-api-refs.json`);
  const preexistingReleasesFile = path.join(
    temporaryDirectory, `${suffix}-preexisting-releases.json`,
  );
  const refsFile = path.join(temporaryDirectory, `${suffix}-remote-refs.json`);
  const releasesFile = path.join(temporaryDirectory, `${suffix}-remote-releases.json`);
  const apiRefsBytes = normalizedAuditBytes(apiRefs);
  const preexistingReleasesBytes = normalizedAuditBytes(preexistingReleases);
  const refsBytes = normalizedAuditBytes(remoteRefs);
  const releasesBytes = normalizedAuditBytes(releases);
  fs.writeFileSync(apiRefsFile, apiRefsBytes, { mode: 0o600, flag: "wx" });
  fs.writeFileSync(
    preexistingReleasesFile, preexistingReleasesBytes, { mode: 0o600, flag: "wx" },
  );
  fs.writeFileSync(refsFile, refsBytes, { mode: 0o600, flag: "wx" });
  fs.writeFileSync(releasesFile, releasesBytes, { mode: 0o600, flag: "wx" });
  for (const filename of [apiRefsFile, preexistingReleasesFile, refsFile, releasesFile]) {
    stableReadRegular(filename, { privateMode: true });
  }
  const apiRefsReport = runScanner(
    ["--file", apiRefsFile], wordlists, digest(apiRefsBytes),
  );
  const refsReport = runScanner(["--file", refsFile], wordlists, digest(refsBytes));
  const preexistingReleasesReport = runScanner(
    ["--file", preexistingReleasesFile], wordlists, digest(preexistingReleasesBytes),
  );
  const releasesReport = runScanner(
    ["--file", releasesFile], wordlists, digest(releasesBytes),
  );
  const metadataBindingSha256 = githubPublicMetadataBinding({
    pullRefIdentitySha256: remoteRefs.pullRefIdentitySha256,
    refApiSnapshotSha256: apiRefs.apiSnapshotSha256,
    refIdentitySha256: remoteRefs.refIdentitySha256,
    remoteRefsRawSnapshotSha256: remoteRefs.rawSnapshotSha256,
    releaseApiSnapshotSha256: releases.apiSnapshotSha256,
    repositoryBindingSha256: releases.repositoryBindingSha256,
  });
  const preexistingMetadataBindingSha256 = githubPublicMetadataBinding({
    pullRefIdentitySha256: remoteRefs.pullRefIdentitySha256,
    refApiSnapshotSha256: apiRefs.apiSnapshotSha256,
    refIdentitySha256: remoteRefs.refIdentitySha256,
    remoteRefsRawSnapshotSha256: remoteRefs.rawSnapshotSha256,
    releaseApiSnapshotSha256: preexistingReleases.apiSnapshotSha256,
    repositoryBindingSha256: preexistingReleases.repositoryBindingSha256,
  });
  return {
    apiRefs,
    audit: {
      apiRefsInputSha256: digest(apiRefsBytes),
      apiRefsReportSha256: apiRefsReport.reportSha256,
      preexistingReleasesInputSha256: digest(preexistingReleasesBytes),
      preexistingReleasesReportSha256: preexistingReleasesReport.reportSha256,
      refsInputSha256: digest(refsBytes),
      refsReportSha256: refsReport.reportSha256,
      releasesInputSha256: digest(releasesBytes),
      releasesReportSha256: releasesReport.reportSha256,
    },
    metadataBindingSha256,
    preexistingMetadataBindingSha256,
    preexistingReleases,
    releases,
    releasePages,
    remoteRefs,
  };
}

function flatPages(value) {
  if (!Array.isArray(value)) fail();
  if (value.every(Array.isArray)) return value.flat();
  if (value.every((item) => item !== null && typeof item === "object" && !Array.isArray(item))) {
    return value;
  }
  fail();
}

function targetRelease(metadata, tag) {
  return flatPages(metadata.releasePages).find((release) => release.tag_name === tag) || null;
}

function assertHistoricalPrefix(metadata, inventory, sequence, candidate, prefixCandidates,
  { targetPresent = false } = {}) {
  const releases = flatPages(metadata.releasePages);
  for (let index = 0; index < HISTORICAL_TAGS.length; index += 1) {
    const release = releases.find((item) => item.tag_name === HISTORICAL_TAGS[index]);
    if (index < sequence) {
      const original = inventory.releases[index];
      const rebuilt = prefixCandidates[index]?.candidate;
      if (!release || digest(Buffer.from(release.name, "utf8")) !== original.titleSha256
          || [...release.name].length !== original.titleLength || release.draft !== original.draft
          || release.prerelease !== original.prerelease
          || release.body !== HISTORICAL_BODY
          || !rebuilt) fail();
      verifyReleaseMetadata(release, rebuilt, rebuilt.historicalRelease.publication.draft);
    } else if (index === sequence && targetPresent) {
      verifyReleaseMetadata(
        release, candidate, candidate.historicalRelease.publication.draft,
      );
    } else if (release) fail("historical Releases must be created in exact order");
  }
  if (sequence > 0) {
    const previous = candidate.lineage.previousRebuiltCandidate;
    const release = releases.find((item) => item.tag_name === previous.tag);
    const asset = release.assets.find((item) => item.name.endsWith(".apk"));
    if (!asset || asset.digest?.toLowerCase() !== `sha256:${previous.apkSha256}`) fail();
  }
}

function assertTargetTag(metadata, candidate) {
  const tag = metadata.apiRefs.tags.find((item) => item.name === candidate.tag);
  if (!tag) fail("the sanitized target tag must already exist");
  const raw = run("git", ["ls-remote", "origin", `refs/tags/${candidate.tag}`,
    `refs/tags/${candidate.tag}^{}`]).stdout.trim().split("\n").filter(Boolean);
  const oids = raw.map((line) => line.split("\t", 1)[0]);
  const peeled = raw.find((line) => line.endsWith("^{}"));
  const commit = peeled ? peeled.split("\t", 1)[0] : oids[0];
  if (!commit || commit !== candidate.source.commit) fail("target tag does not peel to source commit");
}

function assertTargetAbsent(slug, tag, metadata) {
  if (targetRelease(metadata, tag)) fail("target historical Release already exists");
  const response = ghJson(["api", `repos/${slug}/releases/tags/${tag}`],
    { allowFailure: true });
  if (response.ok) fail("target historical Release already exists");
  const combined = `${response.result.stdout || ""}\n${response.result.stderr || ""}`;
  if (!/(?:HTTP\s+404|"status"\s*:\s*"?404"?|Not Found)/iu.test(combined)) fail();
}

function originUrlForSlug(slug) {
  const raw = run("git", ["remote", "get-url", "origin"]).stdout.trim();
  const match = /^(?:https:\/\/github\.com\/|git@github\.com:)([^/]+)\/([^/]+?)(?:\.git)?$/u
    .exec(raw);
  if (!match || `${match[1]}/${match[2]}` !== slug) fail();
  return raw;
}

function fetchedRemoteRefBytes(bareRepository, metadata) {
  const output = run("git", ["-C", bareRepository, "show-ref", "--dereference"]).stdout;
  const lines = [];
  for (const line of output.trim().split("\n").filter(Boolean)) {
    const match = /^([a-f0-9]{40}|[a-f0-9]{64}) (refs\/(?:heads|tags|pull)\/.+)$/u
      .exec(line);
    if (!match) fail();
    lines.push(`${match[1]}\t${match[2]}`);
  }
  if (lines.length === 0) fail();
  lines.push(`${metadata.remoteRefs.headOid}\tHEAD`);
  return Buffer.from(`${lines.sort().join("\n")}\n`, "utf8");
}

function captureReachableHistory(slug, repository, metadata, wordlists) {
  historyClosureSequence += 1;
  const captureOrdinal = String(historyClosureSequence).padStart(2, "0");
  const bareRepository = path.join(temporaryDirectory,
    `reachable-history-${captureOrdinal}.git`);
  run("git", ["init", "--bare", "--quiet", bareRepository]);
  run("git", ["-C", bareRepository, "fetch", "--quiet", "--no-tags", "--force",
    originUrlForSlug(slug),
    "+refs/heads/*:refs/heads/*",
    "+refs/tags/*:refs/tags/*",
    "+refs/pull/*:refs/pull/*",
  ]);
  const fetchedRefs = normalizeRemoteRefs(
    fetchedRemoteRefBytes(bareRepository, metadata), slug, repository,
  );
  if (canonicalJson(fetchedRefs) !== canonicalJson(metadata.remoteRefs)) {
    fail("isolated fetch did not reproduce the captured public ref envelope");
  }

  const roots = [...new Set([
    ...fetchedRefs.branches.map((branch) => branch.commitSha),
    ...fetchedRefs.tags.flatMap((tag) => [tag.objectSha, tag.commitSha]),
    ...fetchedRefs.pullRefs.map((pullRef) => pullRef.commitSha),
  ])].sort();
  if (roots.length === 0) fail();
  const objectLines = run("git", ["-C", bareRepository, "rev-list", "--objects", ...roots])
    .stdout.split("\n").filter(Boolean);
  const locationsByObject = new Map();
  for (const line of objectLines) {
    const separator = line.indexOf(" ");
    const oid = separator < 0 ? line : line.slice(0, separator);
    if (!/^[a-f0-9]{40}(?:[a-f0-9]{24})?$/u.test(oid)) fail();
    if (!locationsByObject.has(oid)) locationsByObject.set(oid, new Set());
    if (separator >= 0 && line.length > separator + 1) {
      locationsByObject.get(oid).add(line.slice(separator + 1));
    }
  }
  const objectIds = [...locationsByObject.keys()].sort();
  if (objectIds.length === 0) fail();
  const checked = run("git", ["-C", bareRepository, "cat-file",
    "--batch-check=%(objectname) %(objecttype) %(objectsize)"], {
    input: `${objectIds.join("\n")}\n`,
  }).stdout.trim().split("\n").filter(Boolean);
  if (checked.length !== objectIds.length) fail();
  const closure = [];
  const aggregateParts = [];
  let aggregateSize = 0;
  for (let index = 0; index < checked.length; index += 1) {
    const match = /^([a-f0-9]{40}|[a-f0-9]{64}) (blob|commit|tag|tree) ([0-9]+)$/u
      .exec(checked[index]);
    if (!match || match[1] !== objectIds[index]) fail();
    const size = Number(match[3]);
    if (!Number.isSafeInteger(size) || size < 0) fail();
    const content = run("git", ["-C", bareRepository, "cat-file", match[2], match[1]],
      { binary: true }).stdout;
    if (!Buffer.isBuffer(content) || content.length !== size) fail();
    const entry = {
      contentSha256: digest(content),
      locations: [...locationsByObject.get(match[1])].sort(),
      sha: match[1],
      size,
      type: match[2],
    };
    const metadataBytes = Buffer.from(canonicalJson(entry), "utf8");
    aggregateSize += metadataBytes.length + 1 + content.length + 1;
    if (aggregateSize > MAX_HISTORY_AGGREGATE_BYTES) fail();
    aggregateParts.push(metadataBytes, Buffer.from([0]), content, Buffer.from([0]));
    closure.push(entry);
  }
  const aggregateBytes = Buffer.concat(aggregateParts, aggregateSize);
  const aggregateFile = path.join(
    temporaryDirectory, `reachable-history-${captureOrdinal}-objects.bin`,
  );
  fs.writeFileSync(aggregateFile, aggregateBytes, { mode: 0o600, flag: "wx" });
  const stableAggregate = stableReadRegular(
    aggregateFile, { allowEmpty: true, privateMode: true },
  );
  runScanner(["--file", aggregateFile], wordlists, stableAggregate.sha256);
  const closureBytes = Buffer.from(`${canonicalJson(closure)}\n`, "utf8");
  const closureFile = path.join(
    temporaryDirectory, `reachable-history-${captureOrdinal}-closure.json`,
  );
  fs.writeFileSync(closureFile, closureBytes, { mode: 0o600, flag: "wx" });
  stableReadRegular(closureFile, { privateMode: true });
  runScanner(["--file", closureFile], wordlists, digest(closureBytes));
  return {
    reachableObjectClosureSha256: digest(Buffer.from(canonicalJson(closure), "utf8")),
    reachableObjectCount: closure.length,
  };
}

function historyAuditExpected(metadata, closure, wordlists, excludedReleaseTag) {
  return {
    excludedReleaseTag,
    metadataBindingSha256: metadata.preexistingMetadataBindingSha256,
    privateWordlistSetSha256: privateWordlistSetIdentity(wordlists.map((wordlist) => ({
      sha256: wordlist.sha256,
      size: wordlist.size,
    }))),
    pullRefIdentitySha256: metadata.remoteRefs.pullRefIdentitySha256,
    reachableObjectClosureSha256: closure.reachableObjectClosureSha256,
    reachableObjectCount: closure.reachableObjectCount,
    refApiInputSha256: metadata.audit.apiRefsInputSha256,
    refApiSnapshotSha256: metadata.apiRefs.apiSnapshotSha256,
    refIdentitySha256: metadata.remoteRefs.refIdentitySha256,
    releaseApiInputSha256: metadata.audit.preexistingReleasesInputSha256,
    releaseApiSnapshotSha256: metadata.preexistingReleases.apiSnapshotSha256,
    remoteRefsInputSha256: metadata.audit.refsInputSha256,
    remoteRefsRawSnapshotSha256: metadata.remoteRefs.rawSnapshotSha256,
    repositoryBindingSha256: metadata.remoteRefs.repositoryBindingSha256,
    scannerSha256: digest(fs.readFileSync(SCANNER)),
    wordlistCount: wordlists.length,
  };
}

function assertPrivateHistoryAudit(historyAuditInput, expected) {
  const current = stableReadRegular(historyAuditInput.absolute, { privateMode: true });
  if (current.sha256 !== historyAuditInput.sha256
      || !sameStat(current.stat, historyAuditInput.stat)) fail();
  let value;
  try {
    value = JSON.parse(current.bytes.toString("utf8"));
  } catch {
    fail();
  }
  try {
    validatePrivateHistoryAttestation(value, expected);
  } catch {
    fail();
  }
}

function captureState(slug, repository, wordlists, inventory, candidate, historyAuditInput,
  prefix, { verifyPrefixAssets = false } = {}) {
  const latest = readLatest(slug, repository);
  const metadata = readRemoteMetadata(slug, repository, wordlists, candidate.tag);
  assertTargetTag(metadata, candidate);
  assertTargetAbsent(slug, candidate.tag, metadata);
  assertHistoricalPrefix(
    metadata, inventory, candidate.lineage.sequence, candidate, prefix.entries,
  );
  if (verifyPrefixAssets) {
    for (const entry of prefix.entries) {
      const release = targetRelease(metadata, entry.candidate.tag);
      downloadAndAuditAssets(
        slug, release, entry.candidate, wordlists, `prefix-${entry.candidate.tag}`,
      );
    }
  }
  const closure = captureReachableHistory(slug, repository, metadata, wordlists);
  assertPrivateHistoryAudit(
    historyAuditInput, historyAuditExpected(metadata, closure, wordlists, candidate.tag),
  );
  return {
    historyClosureSha256: closure.reachableObjectClosureSha256,
    historyObjectCount: closure.reachableObjectCount,
    latest,
    metadataBindingSha256: metadata.metadataBindingSha256,
    preexistingMetadataBindingSha256: metadata.preexistingMetadataBindingSha256,
    preexistingReleasesApiSha256: metadata.preexistingReleases.apiSnapshotSha256,
    preexistingReleasesInputSha256: metadata.audit.preexistingReleasesInputSha256,
    preexistingReleasesReportSha256: metadata.audit.preexistingReleasesReportSha256,
    pullRefIdentitySha256: metadata.remoteRefs.pullRefIdentitySha256,
    repositorySha256: digest(Buffer.from(canonicalJson(repository))),
    refApiInputSha256: metadata.audit.apiRefsInputSha256,
    refApiReportSha256: metadata.audit.apiRefsReportSha256,
    refApiSnapshotSha256: metadata.apiRefs.apiSnapshotSha256,
    refsInputSha256: metadata.audit.refsInputSha256,
    refsReportSha256: metadata.audit.refsReportSha256,
    refsRawSha256: metadata.remoteRefs.rawSnapshotSha256,
    releasesApiSha256: metadata.releases.apiSnapshotSha256,
  };
}

function assertSnapshotsUnchanged(beforeFiles, inventoryInput, historyAuditInput, wordlists) {
  const currentInventory = stableReadRegular(inventoryInput.absolute, { privateMode: true });
  if (currentInventory.sha256 !== inventoryInput.sha256
      || !sameStat(currentInventory.stat, inventoryInput.stat)) fail();
  const currentHistoryAudit = stableReadRegular(
    historyAuditInput.absolute, { privateMode: true },
  );
  if (currentHistoryAudit.sha256 !== historyAuditInput.sha256
      || !sameStat(currentHistoryAudit.stat, historyAuditInput.stat)) fail();
  for (const [kind, before] of Object.entries(beforeFiles)) {
    const now = stableReadRegular(before.absolute, {
      privateMode: kind === "manifest" || kind.endsWith("-manifest"),
    });
    if (now.sha256 !== before.sha256 || now.size !== before.size
        || !sameStat(now.stat, before.stat)) fail();
  }
  for (const wordlist of wordlists) {
    const now = stableReadRegular(wordlist.absolute, { privateMode: true });
    if (now.sha256 !== wordlist.sha256 || !sameStat(now.stat, wordlist.stat)) fail();
  }
}

function verifyReleaseMetadata(release, candidate, expectedDraft) {
  const publication = candidate.historicalRelease.publication;
  if (!release || release.tag_name !== candidate.tag || release.name !== publication.title
      || release.body !== publication.body || release.draft !== expectedDraft
      || release.prerelease !== publication.prerelease
      || release.target_commitish !== candidate.source.commit
      || !Array.isArray(release.assets) || release.assets.length !== publication.assets.length) fail();
  const expected = [...publication.assets].sort((left, right) => left.name.localeCompare(right.name));
  const actual = [...release.assets].sort((left, right) => left.name.localeCompare(right.name));
  for (let index = 0; index < actual.length; index += 1) {
    if (actual[index].name !== expected[index].name || actual[index].size !== expected[index].size
        || actual[index].state !== "uploaded"
        || actual[index].digest?.toLowerCase() !== `sha256:${expected[index].sha256}`) fail();
  }
}

function downloadAndAuditAssets(slug, release, candidate, wordlists, suffix) {
  const publication = candidate.historicalRelease.publication;
  for (const expected of publication.assets) {
    const remote = release.assets.find((asset) => asset.name === expected.name);
    if (!remote || !Number.isSafeInteger(remote.id) || remote.id <= 0) fail();
    const result = run("gh", ["api", "-H", "Accept: application/octet-stream",
      `repos/${slug}/releases/assets/${remote.id}`], { binary: true });
    const filename = path.join(temporaryDirectory, `${suffix}-${expected.name}`);
    fs.writeFileSync(filename, result.stdout, { mode: 0o600 });
    const downloaded = stableReadRegular(filename);
    if (downloaded.sha256 !== expected.sha256 || downloaded.size !== expected.size) fail();
    if (expected.name.endsWith(".apk")) {
      assertApkIdentity(filename, candidate.app);
      runScanner(["--apk", filename], wordlists, downloaded.sha256);
    } else {
      runScanner(["--file", filename], wordlists, downloaded.sha256);
      if (!downloaded.bytes.equals(fs.readFileSync(path.join(
        path.dirname(candidate.__manifestPath), expected.file)))) fail();
    }
  }
}

function readAndAuditTargetRelease(slug, repository, candidate, wordlists, expectedDraft, suffix) {
  const response = ghJson(["api", `repos/${slug}/releases/tags/${candidate.tag}`]);
  const normalized = normalizeGithubReleases([[response.value]], slug, repository);
  verifyReleaseMetadata(response.value, candidate, expectedDraft);
  targetVerificationSequence += 1;
  const filename = path.join(temporaryDirectory,
    `target-release-${String(targetVerificationSequence).padStart(2, "0")}-${suffix}.json`);
  const bytes = normalizedAuditBytes(normalized);
  fs.writeFileSync(filename, bytes, { mode: 0o600, flag: "wx" });
  stableReadRegular(filename, { privateMode: true });
  runScanner(["--file", filename], wordlists, digest(bytes));
  return { normalized, release: response.value };
}

function verifyRemoteRelease(slug, repository, candidate, wordlists, expectedDraft, suffix) {
  const first = readAndAuditTargetRelease(
    slug, repository, candidate, wordlists, expectedDraft, `${suffix}-scanned`,
  );
  downloadAndAuditAssets(slug, first.release, candidate, wordlists, suffix);
  const second = readAndAuditTargetRelease(
    slug, repository, candidate, wordlists, expectedDraft, `${suffix}-reread`,
  );
  if (second.normalized.apiSnapshotSha256 !== first.normalized.apiSnapshotSha256) {
    fail("historical Release metadata changed during verification");
  }
  if (!Number.isSafeInteger(second.release.id) || second.release.id <= 0
      || second.release.id !== first.release.id) fail();
  return {
    releaseId: second.release.id,
    snapshotSha256: second.normalized.apiSnapshotSha256,
  };
}

function createDraft(slug, candidate, files) {
  const publication = candidate.historicalRelease.publication;
  const args = ["release", "create", candidate.tag,
    files.apk.absolute, files.update.absolute,
    "--repo", slug,
    "--target", candidate.source.commit,
    "--title", publication.title,
    "--notes-file", files.notes.absolute,
    "--verify-tag",
    "--draft",
    "--latest=false",
  ];
  if (publication.prerelease) args.push("--prerelease");
  if (args.includes("--latest") || args.includes("--clobber")
      || args.some((item) => /delete|force/u.test(item))) fail();
  sideEffectStarted = true;
  run("gh", args);
}

function finalizeDraft(slug, candidate, releaseId, wordlists) {
  if (!Number.isSafeInteger(releaseId) || releaseId <= 0) fail();
  const publication = candidate.historicalRelease.publication;
  const payload = {
    body: publication.body,
    draft: publication.draft,
    make_latest: "false",
    name: publication.title,
    prerelease: publication.prerelease,
    tag_name: candidate.tag,
    target_commitish: candidate.source.commit,
  };
  const payloadBytes = Buffer.from(`${canonicalJson(payload)}\n`, "utf8");
  const payloadFile = path.join(temporaryDirectory, `finalize-release-${releaseId}.json`);
  fs.writeFileSync(payloadFile, payloadBytes, { mode: 0o600, flag: "wx" });
  stableReadRegular(payloadFile, { privateMode: true });
  runScanner(["--file", payloadFile], wordlists, digest(payloadBytes));
  const args = ["api", "--method", "PATCH", `repos/${slug}/releases/${releaseId}`,
    "--input", payloadFile];
  if (args.some((item) => /delete|force|clobber/u.test(item))) fail();
  run("gh", args);
}

function ensureCommands() {
  for (const command of ["git", "gh", process.env.AAPT || "aapt",
    process.env.APKSIGNER || "apksigner"]) {
    const result = run(command, command === "git" ? ["--version"] : ["--version"],
      { allowFailure: true });
    if (result.error?.code === "ENOENT") fail("a required command is missing");
  }
}

function main() {
  const args = parseArguments(process.argv.slice(2));
  if (args.help) {
    usage();
    return;
  }
  process.umask(0o077);
  process.chdir(ROOT);
  ensureCommands();
  temporaryDirectory = fs.mkdtempSync(path.join(os.tmpdir(), "autoform-history-publish."));
  fs.chmodSync(temporaryDirectory, 0o700);
  const inventoryInput = stableReadRegular(args.inventory, { privateMode: true });
  if (inventoryInput.sha256 !== args["inventory-file-sha256"]) fail();
  const historyAuditInput = stableReadRegular(
    args["private-history-audit"], { privateMode: true },
  );
  if (historyAuditInput.sha256 !== args["private-history-audit-file-sha256"]) fail();
  const loadedInventory = loadHistoricalInventory(args.inventory, args["inventory-file-sha256"]);
  const candidateInput = stableReadPrivateJson(args.candidate);
  const candidate = validateHistoricalCandidate(
    candidateInput.value, loadedInventory.inventory, loadedInventory.fileSha256,
  );
  candidate.__manifestPath = candidateInput.path;
  const wordlists = assertPrivateWordlists(args.wordlists);
  const files = assertCandidateFiles(candidateInput, candidate);
  const prefix = loadHistoricalPrefix(
    candidateInput, candidate, loadedInventory.inventory, loadedInventory.fileSha256,
  );
  const protectedFiles = { ...files, ...prefix.snapshots };
  run("gh", ["auth", "status", "--hostname", "github.com"]);
  const slug = repositorySlug();
  let repository = repositorySelection(slug);
  normalizeGithubReleases([[]], slug, repository);

  info("running first local and remote preflight");
  auditLocal(candidateInput, candidate, files, wordlists);
  auditHistoricalPrefixLocal(prefix, wordlists);
  const first = captureState(
    slug, repository, wordlists, loadedInventory.inventory, candidate, historyAuditInput,
    prefix, { verifyPrefixAssets: true },
  );
  assertSnapshotsUnchanged(protectedFiles, inventoryInput, historyAuditInput, wordlists);

  info("running immediate pre-side-effect revalidation");
  auditLocal(candidateInput, candidate, files, wordlists);
  auditHistoricalPrefixLocal(prefix, wordlists);
  repository = repositorySelection(slug);
  normalizeGithubReleases([[]], slug, repository);
  const second = captureState(
    slug, repository, wordlists, loadedInventory.inventory, candidate, historyAuditInput,
    prefix,
  );
  if (canonicalJson(first) !== canonicalJson(second)) fail("public state changed during preflight");
  assertSnapshotsUnchanged(protectedFiles, inventoryInput, historyAuditInput, wordlists);

  info("creating non-latest draft from the existing sanitized tag");
  createDraft(slug, candidate, files);
  assertLatestUnchanged(first.latest, slug, repository);
  const verifiedDraft = verifyRemoteRelease(
    slug, repository, candidate, wordlists, true, "draft",
  );

  info("finalizing the verified draft with an explicit make_latest=false PATCH");
  finalizeDraft(slug, candidate, verifiedDraft.releaseId, wordlists);
  const finalTarget = verifyRemoteRelease(slug, repository, candidate, wordlists,
    candidate.historicalRelease.publication.draft, "final");
  if (finalTarget.releaseId !== verifiedDraft.releaseId) fail();
  assertLatestUnchanged(first.latest, slug, repository);
  const finalRepository = repositorySelection(slug);
  if (canonicalJson(finalRepository) !== canonicalJson(repository)) fail();
  const finalMetadata = readRemoteMetadata(
    slug, finalRepository, wordlists, candidate.tag,
  );
  assertHistoricalPrefix(finalMetadata, loadedInventory.inventory,
    candidate.lineage.sequence, candidate, prefix.entries, { targetPresent: true });
  const finalClosure = captureReachableHistory(
    slug, finalRepository, finalMetadata, wordlists,
  );
  assertPrivateHistoryAudit(
    historyAuditInput,
    historyAuditExpected(finalMetadata, finalClosure, wordlists, candidate.tag),
  );
  const finalTargetRelease = targetRelease(finalMetadata, candidate.tag);
  verifyReleaseMetadata(finalTargetRelease, candidate,
    candidate.historicalRelease.publication.draft);
  const finalTargetEnvelope = normalizeGithubReleases(
    [[finalTargetRelease]], slug, finalRepository,
  );
  if (finalMetadata.remoteRefs.rawSnapshotSha256 !== first.refsRawSha256
      || finalMetadata.remoteRefs.pullRefIdentitySha256 !== first.pullRefIdentitySha256
      || finalClosure.reachableObjectClosureSha256 !== first.historyClosureSha256
      || finalClosure.reachableObjectCount !== first.historyObjectCount
      || finalMetadata.apiRefs.apiSnapshotSha256 !== first.refApiSnapshotSha256
      || finalMetadata.audit.apiRefsInputSha256 !== first.refApiInputSha256
      || finalMetadata.audit.apiRefsReportSha256 !== first.refApiReportSha256
      || finalMetadata.audit.refsInputSha256 !== first.refsInputSha256
      || finalMetadata.audit.refsReportSha256 !== first.refsReportSha256
      || finalMetadata.preexistingReleases.apiSnapshotSha256
        !== first.preexistingReleasesApiSha256
      || finalMetadata.audit.preexistingReleasesInputSha256
        !== first.preexistingReleasesInputSha256
      || finalMetadata.audit.preexistingReleasesReportSha256
        !== first.preexistingReleasesReportSha256
      || finalMetadata.preexistingMetadataBindingSha256
        !== first.preexistingMetadataBindingSha256
      || digest(Buffer.from(canonicalJson(finalRepository))) !== first.repositorySha256
      || finalTargetEnvelope.apiSnapshotSha256 !== finalTarget.snapshotSha256) {
    fail("tag, branch, or pre-existing Release state changed during historical publication");
  }
  assertSnapshotsUnchanged(protectedFiles, inventoryInput, historyAuditInput, wordlists);
  assertLatestUnchanged(first.latest, slug, finalRepository);
  info(`published and verified ${candidate.tag} without changing latest; `
    + `public metadata binding ${finalMetadata.metadataBindingSha256}`);
}

try {
  main();
} catch (error) {
  const original = error instanceof Error ? error.message : "historical publication failed";
  const message = sideEffectStarted
      && !original.startsWith(
        "publish-historical-release: remote Release may now contain a draft or partial change; ")
    ? "publish-historical-release: remote Release may now contain a draft or partial change; "
      + "a post-create validation failed"
    : original;
  process.stderr.write(`${message}\n`);
  process.exitCode = 1;
} finally {
  if (temporaryDirectory
      && path.basename(temporaryDirectory).startsWith("autoform-history-publish.")) {
    try {
      fs.rmSync(temporaryDirectory, { recursive: true, force: true });
    } catch {
      if (!process.exitCode) process.exitCode = 1;
    }
  }
}
