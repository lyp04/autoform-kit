#!/usr/bin/env node

/**
 * Publish the reviewed code-21 beta through the fixed `beta` channel.
 *
 * This is intentionally independent from the stable publisher. It performs every local and
 * remote preflight before its first write, creates a non-latest prerelease draft, verifies the
 * exact uploaded bytes, and only then makes that draft public with make_latest="false". The current
 * public repository has no fixed beta channel, so this publisher accepts only an absent channel, its
 * own exact unpublished draft, or its own exact completed Release. Existing stable and archived-beta
 * Releases, assets, refs, history, branches, pull refs, and latest are never mutated.
 */

import { spawnSync } from "node:child_process";
import crypto from "node:crypto";
import fs, { constants } from "node:fs";
import os from "node:os";
import path from "node:path";
import process from "node:process";
import { fileURLToPath } from "node:url";
import { HISTORICAL_BODY, HISTORICAL_TAGS } from "./historical-release-contract.mjs";

const HERE = fs.realpathSync.native(path.dirname(fileURLToPath(import.meta.url)));
const ROOT = fs.realpathSync.native(path.resolve(HERE, ".."));
const SCANNER = path.join(HERE, "public-surface-audit.mjs");
const THIRD_PARTY_POLICY = path.join(HERE, "apk-third-party-components.json");
const RUNTIME_LOCK = path.join(HERE, "android-runtime-dependencies.lock.json");
const SOURCE_VERIFIER = path.join(HERE, "verify-apk-third-party-sources.mjs");

const CHANNEL_TAG = "beta";
const PREVIOUS_CANDIDATE_TAG = "v1.0.16";
const PREVIOUS_VERSION_NAME = "1.0.16";
const PREVIOUS_VERSION_CODE = 20;
const PREVIOUS_PREDECESSOR_NAME = "1.0.15";
const PREVIOUS_PREDECESSOR_CODE = 19;
const CANDIDATE_TAG = "v1.0.17-beta.1";
const VERSION_NAME = "1.0.17-beta.1";
const VERSION_CODE = 21;
const PACKAGE_NAME = "com.autoformkit.app";
const PREVIOUS_RELEASE_TITLE = `autoform-kit ${PREVIOUS_VERSION_NAME}`;
const RELEASE_TITLE = `autoform-kit ${VERSION_NAME}`;
const RELEASE_BODY = "Public beta build of the autoform-kit framework. "
  + "No site-specific configuration is included.";
const RELEASE_BODY_BYTES = Buffer.from(`${RELEASE_BODY}\n`, "utf8");
const PREVIOUS_RELEASE_BODY = "Panel controls camera, gallery, or file selection for each "
  + "photo location; includes same-Panel draft upgrades and Panel notification/print controls.";
const PREVIOUS_RELEASE_BODY_BYTES = Buffer.from(`${PREVIOUS_RELEASE_BODY}\n`, "utf8");
const APK_NAME = `autoform-kit-${VERSION_NAME}.apk`;
const PREVIOUS_APK_NAME = `autoform-kit-${PREVIOUS_VERSION_NAME}.apk`;
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

const APK_CONTENT_TYPE = "application/vnd.android.package-archive";
const JSON_CONTENT_TYPE = "application/json";
const PRESERVED_RELEASE_SPECS = [
  ["v1.0.0", "47551320a1f6f612d5151dc742839f095f82a004", HISTORICAL_BODY, false, [
    ["autoform-kit-1.0.0.apk", "20adce30767b76a44dbed108160a6a53a13e48a9182f3169e095d796bd931a63", 68773320, APK_CONTENT_TYPE],
    [UPDATE_NAME, "1131339a65825c685557eda29b1785ca6d140bed23c99da113a6dc439745d6fa", 336, JSON_CONTENT_TYPE],
  ]],
  ["v1.0.1", "47551320a1f6f612d5151dc742839f095f82a004", HISTORICAL_BODY, false, [
    ["autoform-kit-1.0.1.apk", "aa6ae170e51d36f0a3b337670319af935513a9b20614bf5b08914381cf78beda", 68773320, APK_CONTENT_TYPE],
    [UPDATE_NAME, "f0a1f0108cce7cff0fe4e804eb9773bf48560eda2de430f5b96e7bb07d4c09ec", 336, JSON_CONTENT_TYPE],
  ]],
  ["v1.0.2", "47551320a1f6f612d5151dc742839f095f82a004", HISTORICAL_BODY, false, [
    ["autoform-kit-1.0.2.apk", "7f7882a6ff8135a52ea5ce4a002140d6a795789815cda37cb552e89047d8cc7c", 68773320, APK_CONTENT_TYPE],
    [UPDATE_NAME, "1d07437b3d1903d9fcb0516f3629d4727ba9af9dbc735a7b59def21649e81758", 336, JSON_CONTENT_TYPE],
  ]],
  ["v1.0.3", "47551320a1f6f612d5151dc742839f095f82a004", HISTORICAL_BODY, false, [
    ["autoform-kit-1.0.3.apk", "3bac2d0762f68d8ecbcef1cdadf9221dc4aa66419a8541ad54134fd865e2af44", 68773320, APK_CONTENT_TYPE],
    [UPDATE_NAME, "b04b85f0f95821a9abd8ff89823eb9ef16f25e466fe8f888a7d151f930c2e77b", 336, JSON_CONTENT_TYPE],
  ]],
  ["v1.0.4", "47551320a1f6f612d5151dc742839f095f82a004", HISTORICAL_BODY, false, [
    ["autoform-kit-1.0.4.apk", "524e2e3553d80f2e99954279e8935aee62b87dd3d12200db6cf0127362bad750", 68773320, APK_CONTENT_TYPE],
    [UPDATE_NAME, "61798acbbdb4853e7f253283b1dd38357ecdbc7dbc09fc95abd043a7916a903f", 336, JSON_CONTENT_TYPE],
  ]],
  ["v1.0.5", "47551320a1f6f612d5151dc742839f095f82a004", HISTORICAL_BODY, false, [
    ["autoform-kit-1.0.5.apk", "2584795beeacd722e422a3447a83182f9cf286c45a0eb00e83b56fd0d2127b9f", 68773320, APK_CONTENT_TYPE],
    [UPDATE_NAME, "8926601d9409a789be6d277ab105b1de24851645ef7d8e8b28d640f6979b23e3", 336, JSON_CONTENT_TYPE],
  ]],
  ["v1.0.6", "47551320a1f6f612d5151dc742839f095f82a004", HISTORICAL_BODY, false, [
    ["autoform-kit-1.0.6.apk", "fe629606358003fd74e8fdc40f46a18f2d754007024484f17154e88ae0683363", 68773320, APK_CONTENT_TYPE],
    [UPDATE_NAME, "7ec97ee1fc92eff6de119cd2f5556aa22598308c3bcd8eeb475382f104a6518e", 336, JSON_CONTENT_TYPE],
  ]],
  ["v1.0.8-beta.1", "8ef5466162731b8e47b67e7194f68dabde313a74", `${RELEASE_BODY}\n`, true, [
    ["autoform-kit-1.0.8-beta.1.apk", "c60cf352aa7008ed345c3dba8ce161c47f7a8b83cf92015726049332c2aac040", 68773320, APK_CONTENT_TYPE],
    [MANIFEST_NAME, "ce6b9c0499fca9ddcad2e014a2e93d8fb901656ad751f46ec1e657cb578a2b4c", 3584, JSON_CONTENT_TYPE],
    [UPDATE_NAME, "f28f490126f7cdb6bbe190b3ffc3a2b96788d0c6460b6200ce23513e04ed2379", 330, JSON_CONTENT_TYPE],
  ]],
  ["v1.0.8", "1124b1ace568f5081b992ac9e7bd41cc366cf319", "Panel-driven workflow fixes, stable draft restoration, flexible scanner rules, and direct creation of configured previous steps.\n", false, [
    ["autoform-kit-1.0.8.apk", "04fedba4564dbc3e0e813bd0bd5a1ee7a8f95201bf03cba2732511b9679cf071", 68773320, APK_CONTENT_TYPE],
    [MANIFEST_NAME, "1da8207b6ca8fc2e5b1df55103f85b5c8e156fadc2276cccaaffe9785cbf9bb2", 3563, JSON_CONTENT_TYPE],
    [UPDATE_NAME, "871d7875221e0fefde024e9243a9da29ae83bdbaccf33fea4f187cb4283bd8ca", 353, JSON_CONTENT_TYPE],
  ]],
  ["v1.0.9", "144566d51f407c8d9d287a5a77bbb2a1f9c07a5c", "Restore Panel-controlled material submission recovery and improve handling of explicitly rejected submissions.\n", false, [
    ["autoform-kit-1.0.9.apk", "ec2ca35e77d240848642f998a735b4dea692628a7204413cf54a3a7f88d15809", 68773320, APK_CONTENT_TYPE],
    [MANIFEST_NAME, "85401d6a84269d80c48013329e05ed8a65833d28cb8f41d9a88cf141009f8578", 3558, JSON_CONTENT_TYPE],
    [UPDATE_NAME, "910df113f20f0bade072c67d41b1683e148619477e32b4088371957073606e9c", 335, JSON_CONTENT_TYPE],
  ]],
  ["v1.0.10", "8ffd1d3299b92e4cbdce456fe4d80900bf0fb7f6", "Restore automatic update prompts after Panel startup synchronization. Also allow bounded image re-upload, clear legacy upload-only locks, and keep the active Panel usable while a new pair is staged.\n", false, [
    ["autoform-kit-1.0.10.apk", "966411e27f04d1e146db24cda70ef07b8aa6aff3b98440e0f711fe8dcb0702bd", 68773320, APK_CONTENT_TYPE],
    [MANIFEST_NAME, "a9b20ba770857bc7393f5f537ea5a9a18d570a28c480b82122ccaff0e5b8bb1d", 3562, JSON_CONTENT_TYPE],
    [UPDATE_NAME, "173abce53ae9ffae9c3bd61e5b2b612e3411c66384adf26b85ba64ddfe53e470", 425, JSON_CONTENT_TYPE],
  ]],
  ["v1.0.11", "c5a917726ef8aff6c2f8a8e7da8082b2a2ee88d0", "Restores bounded material removal and retry after a parsed inventory rejection, and keeps interrupted final submissions operator-retryable.\n", false, [
    ["autoform-kit-1.0.11.apk", "c46142ea876f76b7d53efbe79beb253d89642f2fe41f4a58616f3ff66d9d00e9", 68773320, APK_CONTENT_TYPE],
    [MANIFEST_NAME, "f187290a918f08657973fec711c884de7db116bc622ee819e1c99d03e143a930", 3563, JSON_CONTENT_TYPE],
    [UPDATE_NAME, "0a50894a50ae174d0876962413c2f0f74c83cdbec146102607389e657038957d", 366, JSON_CONTENT_TYPE],
  ]],
  ["v1.0.12", "394a460692f2ac8d4005d357beeaaffac87e89ea", "修复新设备连接面板、打印状态与打印确认列表；恢复缺料重试兼容；已配置设备默认隐藏面板连接及诊断日志，连按五次 English 可临时显示。\n", false, [
    ["autoform-kit-1.0.12.apk", "ff4eaad3e7db6464f7e0d1311b394747233ef84b2788e5951eddbf2adc563df4", 68773320, APK_CONTENT_TYPE],
    [MANIFEST_NAME, "66aea2471097839282f879b0b0ebfe902b0c58d0082251fb558c5addb4c440ca", 3563, JSON_CONTENT_TYPE],
    [UPDATE_NAME, "fde220ea25517006d9c025c05565b849c3ed7d59123318c67cdb2a582baa932b", 416, JSON_CONTENT_TYPE],
  ]],
  ["v1.0.13", "c1678fd2b34fc261986deb04541065855105ae58", "Fix Panel synchronization blocked by an obsolete local draft, and add a hidden redacted support report with double-confirmed local-only recovery.\n", false, [
    ["autoform-kit-1.0.13.apk", "b4a3f2c16f760441697d672d22c46c2e1d2f9134fdcf8e11bf29d4d987e7c337", 68789704, APK_CONTENT_TYPE],
    [MANIFEST_NAME, "8f491023eca69b2abe5cc5c7a08b5961676bbdaa8babe1db1ad7e9be95e51a3b", 3563, JSON_CONTENT_TYPE],
    [UPDATE_NAME, "b161b4c9aa7ca6d58fa658bff26a0bdd00ac97d2d34368fed183f2b239f1f2b0", 372, JSON_CONTENT_TYPE],
  ]],
  ["v1.0.14", "2c46d5f9213cde0c5884daaa9d3d25ad5746e072", "Panel-driven independent-entry result presets, persistent draft selection, localized input hints, and safe Panel-upgrade compatibility fixes.\n", false, [
    ["autoform-kit-1.0.14.apk", "29471dc28fef681e205c2245972e905af86be01d0b087bbe7ab7ea95f4042268", 68789704, APK_CONTENT_TYPE],
    [MANIFEST_NAME, "354cdacf3510e3c635227f82348e095741596c63a9fbc27e46c4d7a5a12f0e52", 3563, JSON_CONTENT_TYPE],
    [UPDATE_NAME, "67bddac731460551c01525ee35218a63955d0d5914bfdf9f1998c4c347d22896", 368, JSON_CONTENT_TYPE],
  ]],
  ["v1.0.15", "a41cc696c7a49412a0b5fe374c2c1bb6a1822114", "Keep active uploads running when the screen is locked; normal screens may now follow the device display timeout.\n", false, [
    ["autoform-kit-1.0.15.apk", "555a5974c33e1207fab7f51417f28984f1791267499047f2e89a9eba5e157586", 68806088, APK_CONTENT_TYPE],
    [MANIFEST_NAME, "73fef0227005996d9332faa128cffc5c671f29f9ed2e15b2232c6c47f4f9e728", 3563, JSON_CONTENT_TYPE],
    [UPDATE_NAME, "e860108ef195e6a4cd058a0355704a46cd63d724a469c6dbf1506da84dcf1b1e", 339, JSON_CONTENT_TYPE],
  ]],
  ["v1.0.16", "0394dd4233afd69cf704ab7d9e497b63f67ca889", PREVIOUS_RELEASE_BODY_BYTES.toString("utf8"), false, [
    ["autoform-kit-1.0.16.apk", "fb6272ea47607030854ec93afc95713f39f07ad66df85ddad326a3b2bc31d75d", 68806088, APK_CONTENT_TYPE],
    [MANIFEST_NAME, "b2568be4d9cf13c23f22b355fc3eb7994db5aa3a78b52111cd3155bdcff40513", 3563, JSON_CONTENT_TYPE],
    [UPDATE_NAME, "782879c4578e066e30a1770dec74482580ae45d2d1a9357c732224df246e2eda", 375, JSON_CONTENT_TYPE],
  ]],
];

let sideEffectStarted = false;
let remoteBetaKnown = false;
let auditDirectory = "";
let captureSequence = 0;

function fail(message = "beta publication validation failed") {
  const partial = sideEffectStarted || remoteBetaKnown
    ? " remote may now contain a beta draft, tag, or partial change; do not delete it; "
      + "re-run only so the publisher can classify the exact checkpoint;"
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

function pathIsWithin(parent, candidate) {
  const relative = path.relative(parent, candidate);
  return relative === "" || (relative !== ".." && !relative.startsWith(`..${path.sep}`)
    && !path.isAbsolute(relative));
}

function stableReadRegular(filename, { privateMode = false, maximum = MAX_FILE_BYTES } = {}) {
  const requestedAbsolute = path.resolve(filename);
  const before = fs.lstatSync(requestedAbsolute, { bigint: true });
  const expectedUid = typeof process.geteuid === "function" ? BigInt(process.geteuid()) : before.uid;
  if (!before.isFile() || before.isSymbolicLink() || before.nlink !== 1n
      || before.uid !== expectedUid || before.size <= 0n || before.size > BigInt(maximum)
      || (privateMode && (before.mode & 0o7777n) !== 0o600n)) fail();
  const absolute = fs.realpathSync.native(requestedAbsolute);
  const canonicalBefore = fs.lstatSync(absolute, { bigint: true });
  if (!sameStat(before, canonicalBefore) || !canonicalBefore.isFile()
      || canonicalBefore.isSymbolicLink()) fail("input path identity changed during canonicalization");
  const descriptor = fs.openSync(absolute, constants.O_RDONLY | (constants.O_NOFOLLOW || 0));
  try {
    const opened = fs.fstatSync(descriptor, { bigint: true });
    if (!sameStat(canonicalBefore, opened)) fail();
    const bytes = fs.readFileSync(descriptor);
    const afterDescriptor = fs.fstatSync(descriptor, { bigint: true });
    const afterCanonical = fs.lstatSync(absolute, { bigint: true });
    const afterRequested = fs.lstatSync(requestedAbsolute, { bigint: true });
    if (!sameStat(opened, afterDescriptor) || !sameStat(afterDescriptor, afterCanonical)
        || !sameStat(afterCanonical, afterRequested)
        || bytes.length !== Number(opened.size)) fail();
    return { absolute, bytes, sha256: digest(bytes), size: bytes.length, stat: afterCanonical };
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
  + "public prerelease. An explicit Release ID may resume one exact unpublished draft. "
  + `The existing fixed channel must be absent; ${PREVIOUS_CANDIDATE_TAG} is verified as `
  + "the exact signed predecessor and all existing public history is preserved.\n");
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
      || value.previousApk.packageName !== PACKAGE_NAME
      || value.previousApk.versionCode !== PREVIOUS_VERSION_CODE
      || value.previousApk.versionName !== PREVIOUS_VERSION_NAME
      || !sha256Value(value.previousApk.sha256)
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

function preservedReleaseProjection(release) {
  return canonical({
    assets: release.assets.map((asset) => ({
      contentType: asset.contentType,
      digest: asset.digest,
      label: asset.label,
      name: asset.name,
      size: asset.size,
    })).sort((left, right) => left.name.localeCompare(right.name)),
    body: release.body,
    draft: release.draft,
    immutable: release.immutable,
    name: release.name,
    prerelease: release.prerelease,
    tagName: release.tagName,
    targetCommitish: release.targetCommitish,
  });
}

function preservedReleaseSpecProjection(spec) {
  const [tagName, targetCommitish, body, prerelease, assets] = spec;
  return canonical({
    assets: assets.map(([name, sha256, size, contentType]) => ({
      contentType,
      digest: `sha256:${sha256}`,
      label: "",
      name,
      size,
    })).sort((left, right) => left.name.localeCompare(right.name)),
    body,
    draft: false,
    immutable: false,
    name: `autoform-kit ${tagName.slice(1)}`,
    prerelease,
    tagName,
    targetCommitish,
  });
}

function assertExactPublicRefEnvelope(state, sourceCommit, { betaRefExpected = false } = {}) {
  const defaultBranch = state.branches.find(
    (branch) => branch.name === state.repository.defaultBranch,
  );
  const head = state.remoteRefs.refs.find((entry) => entry.ref === "HEAD");
  if (!defaultBranch || defaultBranch.commitSha !== sourceCommit
      || state.remoteRefs.symbolicHead !== `refs/heads/${state.repository.defaultBranch}`
      || head?.oid !== sourceCommit) fail("default branch is not the candidate source");

  const branchRefs = new Map(state.branches.map(
    (branch) => [`refs/heads/${branch.name}`, branch.commitSha],
  ));
  const tagRefs = new Map(state.tags.map((tag) => [`refs/tags/${tag.name}`, tag.commitSha]));
  if (branchRefs.size !== state.branches.length || tagRefs.size !== state.tags.length) fail();
  if (betaRefExpected !== tagRefs.has(`refs/tags/${CHANNEL_TAG}`)) {
    fail("beta ref presence is not exact");
  }

  const seenBranches = new Set();
  const seenTags = new Set();
  for (const entry of state.remoteRefs.refs) {
    if (entry.ref === "HEAD") continue;
    if (branchRefs.has(entry.ref)) {
      if (entry.oid !== branchRefs.get(entry.ref)) fail("branch API and transport ref disagree");
      seenBranches.add(entry.ref);
      continue;
    }
    const directTag = entry.ref.endsWith("^{}") ? entry.ref.slice(0, -3) : entry.ref;
    if (tagRefs.has(directTag)) {
      if (entry.ref.endsWith("^{}") && entry.oid !== tagRefs.get(directTag)) {
        fail("peeled tag and tag API disagree");
      }
      seenTags.add(directTag);
      continue;
    }
    if (/^refs\/pull\/[1-9][0-9]*\/(?:head|merge)$/u.test(entry.ref)) continue;
    fail("unexpected branch, tag, or remote ref exists");
  }
  if (seenBranches.size !== branchRefs.size || seenTags.size !== tagRefs.size) {
    fail("branch or tag transport ref is missing");
  }
  for (const [tagRef, commitSha] of tagRefs) {
    const direct = state.remoteRefs.refs.find((entry) => entry.ref === tagRef);
    if (!direct || remoteTagCommit(state, tagRef.slice("refs/tags/".length)) !== commitSha) {
      fail("tag API and transport ref disagree");
    }
  }
}

function assertHistoryAndLatestState(state, sourceCommit, options = {}) {
  assertExactPublicRefEnvelope(state, sourceCommit, options);
  const expected = PRESERVED_RELEASE_SPECS.map(preservedReleaseSpecProjection)
    .sort((left, right) => left.tagName.localeCompare(right.tagName));
  const observed = state.releases.filter((release) => release.tagName !== CHANNEL_TAG)
    .map(preservedReleaseProjection)
    .sort((left, right) => left.tagName.localeCompare(right.tagName));
  if (canonicalJson(observed) !== canonicalJson(expected)) {
    fail("preserved public Release allowlist changed");
  }

  const expectedTags = new Map(expected.map(
    (release) => [release.tagName, release.targetCommitish],
  ));
  for (const [tagName, commitSha] of expectedTags) {
    const apiTag = state.tags.find((tag) => tag.name === tagName);
    if (!apiTag || apiTag.commitSha !== commitSha
        || remoteTagCommit(state, tagName) !== commitSha) {
      fail("preserved public tag allowlist changed");
    }
  }
  const allowedTags = new Set([
    ...expectedTags.keys(),
    ...(options.betaRefExpected ? [CHANNEL_TAG] : []),
  ]);
  if (state.tags.length !== allowedTags.size
      || state.tags.some((tag) => !allowedTags.has(tag.name))) {
    fail("unexpected public tag exists");
  }

  const latest = state.releases.find((release) => release.tagName === PREVIOUS_CANDIDATE_TAG);
  if (!latest || state.latest.kind !== "present"
      || canonicalJson(latest) !== canonicalJson(state.latest.release)) {
    fail(`${PREVIOUS_CANDIDATE_TAG} is not the exact stable latest Release`);
  }
  const historicalCommit = expectedTags.get(HISTORICAL_TAGS[0]);
  for (const ancestor of [historicalCommit, expectedTags.get(PREVIOUS_CANDIDATE_TAG)]) {
    if (!ancestor || run("git", ["merge-base", "--is-ancestor", ancestor, sourceCommit],
      { allowFailure: true }).status !== 0) {
      fail("preserved release history is not an ancestor of the candidate source");
    }
  }
  return { historicalCommit };
}

function captureReachableObjectClosure(state, sourceCommit, wordlists) {
  const roots = [...new Set([
    sourceCommit,
    ...state.branches.map((branch) => branch.commitSha),
    ...state.tags.map((tag) => tag.commitSha),
    ...state.remoteRefs.refs.map((entry) => entry.oid),
  ])].sort();
  if (roots.length === 0) fail();
  for (const entry of state.remoteRefs.refs) {
    if (run("git", ["cat-file", "-e", `${entry.oid}^{object}`],
      { allowFailure: true }).status === 0) continue;
    let fetchRef = entry.ref;
    if (fetchRef === "HEAD") fetchRef = state.remoteRefs.symbolicHead;
    if (fetchRef.endsWith("^{}")) fetchRef = fetchRef.slice(0, -3);
    run("git", ["fetch", "--no-tags", "--quiet", "origin", fetchRef]);
  }
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

function stateWithoutBetaLine(state) {
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
  if (canonicalJson(stateWithoutBetaLine(after)) !== canonicalJson(before)) {
    fail("latest, history, repository, branches, or unrelated refs changed");
  }
}

function previousStableExpectedAssets() {
  return new Set([PREVIOUS_APK_NAME, UPDATE_NAME, MANIFEST_NAME]);
}

function assertPreviousPublicAudit(value, apkSha256, updateSha256, notesSha256) {
  if (!exactKeys(value, ["apk", "policySha256", "releaseMetadata", "scannerSha256",
    "sourceTree", "thirdPartyProvenance", "worktree"])
      || !sha256Value(value.scannerSha256) || !sha256Value(value.policySha256)
      || !exactKeys(value.sourceTree, ["gitTreeOid", "inputSha256", "reportSha256"])
      || !oidValue(value.sourceTree.gitTreeOid)
      || !sha256Value(value.sourceTree.inputSha256)
      || !sha256Value(value.sourceTree.reportSha256)
      || !exactKeys(value.worktree, ["inputSha256", "reportSha256"])
      || !sha256Value(value.worktree.inputSha256)
      || !sha256Value(value.worktree.reportSha256)
      || !exactKeys(value.apk, ["inputSha256", "reportSha256", "zipEntryManifestSha256"])
      || value.apk.inputSha256 !== apkSha256
      || !sha256Value(value.apk.reportSha256)
      || !sha256Value(value.apk.zipEntryManifestSha256)
      || !exactKeys(value.releaseMetadata, ["notes", "update"])) {
    fail("previous stable public audit shape mismatch");
  }
  const expectedMetadata = { notes: notesSha256, update: updateSha256 };
  for (const [name, inputSha256] of Object.entries(expectedMetadata)) {
    const binding = value.releaseMetadata[name];
    if (!exactKeys(binding, ["inputSha256", "reportSha256"])
        || binding.inputSha256 !== inputSha256 || !sha256Value(binding.reportSha256)) {
      fail("previous stable public audit binding mismatch");
    }
  }
  const provenance = value.thirdPartyProvenance;
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
      || !sha256Value(provenance.sourceReportSha256)) {
    fail("previous stable provenance shape mismatch");
  }
  for (const key of ["apkMatchedDexStringCount", "compiledOutputCount",
    "declaredDexStringCount", "dexSourceArtifactCount", "dexSourceEntryCount",
    "matchedEntryCount", "mergedSourceCount", "sourceArtifactCount", "sourceEntryCount",
    "sourceMatchedDexStringCount"]) {
    positiveInteger(provenance[key]);
  }
  if (provenance.sourceArtifactCount !== provenance.sourceEntryCount
      || provenance.declaredDexStringCount !== provenance.sourceMatchedDexStringCount
      || provenance.declaredDexStringCount !== provenance.apkMatchedDexStringCount
      || provenance.mergedSourceCount !== 1) {
    fail("previous stable provenance binding mismatch");
  }
}

function assertPreviousStableEnvelope(release) {
  const expectedAssets = previousStableExpectedAssets();
  if (!release || release.tagName !== PREVIOUS_CANDIDATE_TAG
      || !oidValue(release.targetCommitish)
      || release.name !== PREVIOUS_RELEASE_TITLE
      || release.body !== PREVIOUS_RELEASE_BODY_BYTES.toString("utf8")
      || release.draft !== false || release.prerelease !== false
      || release.immutable !== false || release.assets.length !== expectedAssets.size
      || release.assets.some((asset) => !expectedAssets.has(asset.name))) {
    fail("previous stable Release envelope mismatch");
  }
  return release;
}

function downloadPreviousStableAssets(slug, release, wordlists, aapt, apksigner,
  expectedPrevious) {
  const downloaded = new Map();
  for (const asset of release.assets) {
    const result = run("gh", ["api", `repos/${slug}/releases/assets/${asset.id}`,
      "-H", "Accept: application/octet-stream"], { binary: true });
    if (!Buffer.isBuffer(result.stdout) || result.stdout.length !== asset.size
        || digest(result.stdout) !== asset.digest.slice("sha256:".length)) {
      fail("downloaded previous stable asset mismatch");
    }
    const input = stableWriteAudit(`previous-stable-${asset.name}`, result.stdout);
    runScanner(asset.name === PREVIOUS_APK_NAME ? "apk" : "file", input.absolute, wordlists);
    downloaded.set(asset.name, input);
  }
  const apk = downloaded.get(PREVIOUS_APK_NAME);
  const update = stableJson(downloaded.get(UPDATE_NAME).absolute);
  const manifest = stableJson(downloaded.get(MANIFEST_NAME).absolute);
  const expectedIdentity = {
    packageName: expectedPrevious.packageName,
    signerSha256: expectedPrevious.signerSha256,
    versionCode: expectedPrevious.versionCode,
    versionName: expectedPrevious.versionName,
  };
  if (!apk || apk.sha256 !== expectedPrevious.sha256
      || canonicalJson(apkIdentity(apk, aapt, apksigner)) !== canonicalJson(expectedIdentity)) {
    fail("previous stable APK identity mismatch");
  }
  if (!exactKeys(update.value,
    ["apkAsset", "notes", "packageName", "sha256", "versionCode", "versionName"])
      || update.value.apkAsset !== PREVIOUS_APK_NAME
      || update.value.notes !== PREVIOUS_RELEASE_BODY
      || update.value.packageName !== PACKAGE_NAME
      || update.value.sha256 !== apk.sha256
      || update.value.versionCode !== PREVIOUS_VERSION_CODE
      || update.value.versionName !== PREVIOUS_VERSION_NAME) {
    fail("previous stable update manifest mismatch");
  }
  const value = manifest.value;
  if (!exactKeys(value, ["app", "artifacts", "previousApk", "publicAudit", "schemaVersion",
    "source", "tag"]) || value.schemaVersion !== 2 || value.tag !== PREVIOUS_CANDIDATE_TAG
      || !exactKeys(value.source, ["branch", "commit", "workingTreeClean"])
      || value.source.branch !== "main" || value.source.workingTreeClean !== true
      || value.source.commit !== release.targetCommitish
      || !exactKeys(value.app, ["packageName", "signerSha256", "versionCode", "versionName"])
      || canonicalJson(value.app) !== canonicalJson(expectedIdentity)
      || !exactKeys(value.previousApk,
        ["packageName", "sha256", "signerSha256", "versionCode", "versionName"])
      || value.previousApk.packageName !== PACKAGE_NAME
      || value.previousApk.versionCode !== PREVIOUS_PREDECESSOR_CODE
      || value.previousApk.versionName !== PREVIOUS_PREDECESSOR_NAME
      || value.previousApk.signerSha256 !== expectedIdentity.signerSha256
      || !sha256Value(value.previousApk.sha256)
      || !exactKeys(value.artifacts, ["apk", "notes", "update"])) {
    fail("previous stable candidate manifest mismatch");
  }
  const expectedArtifacts = {
    apk: [PREVIOUS_APK_NAME, apk.sha256],
    notes: [NOTES_NAME, digest(PREVIOUS_RELEASE_BODY_BYTES)],
    update: [UPDATE_NAME, update.sha256],
  };
  for (const [key, [filename, sha256]] of Object.entries(expectedArtifacts)) {
    if (!exactKeys(value.artifacts[key], ["file", "sha256"])
        || value.artifacts[key].file !== filename || value.artifacts[key].sha256 !== sha256) {
      fail("previous stable candidate artifact mismatch");
    }
  }
  assertPreviousPublicAudit(
    value.publicAudit, apk.sha256, update.sha256, digest(PREVIOUS_RELEASE_BODY_BYTES),
  );
  return { apk, manifest, update, sourceCommit: value.source.commit };
}

function verifyPreviousStable(slug, state, candidate, previousApk, wordlists, aapt, apksigner) {
  const releases = state.releases.filter(
    (release) => release.tagName === PREVIOUS_CANDIDATE_TAG,
  );
  if (releases.length !== 1) fail("previous stable Release is not unique");
  const listed = assertPreviousStableEnvelope(releases[0]);
  assertExactTag(slug, PREVIOUS_CANDIDATE_TAG, listed.targetCommitish, state);
  const direct = readBetaReleaseById(slug, listed.id);
  const tagged = readReleaseByTag(slug, PREVIOUS_CANDIDATE_TAG);
  if (canonicalJson(listed) !== canonicalJson(direct)
      || canonicalJson(direct) !== canonicalJson(tagged)) {
    fail("previous stable Release reads disagree");
  }
  const downloaded = downloadPreviousStableAssets(
    slug, listed, wordlists, aapt, apksigner, candidate.value.previousApk,
  );
  if (downloaded.apk.sha256 !== previousApk.sha256
      || downloaded.sourceCommit !== listed.targetCommitish
      || run("git", ["merge-base", "--is-ancestor", downloaded.sourceCommit,
        candidate.value.source.commit], { allowFailure: true }).status !== 0) {
    fail("previous stable is not the exact candidate predecessor");
  }
  return { release: listed, ...downloaded };
}

function expectedReleaseAssets(candidate) {
  return new Map([
    [APK_NAME, candidate.apk],
    [UPDATE_NAME, candidate.update],
    [MANIFEST_NAME, candidate.manifest],
  ]);
}

function assertExactTag(slug, tagName, sourceCommit, state) {
  const apiTag = state.tags.find((tag) => tag.name === tagName);
  if (!apiTag || apiTag.commitSha !== sourceCommit
      || remoteTagCommit(state, tagName) !== sourceCommit) fail("release tag target mismatch");
  const response = ghJson(["api", `repos/${slug}/git/ref/tags/${tagName}`]);
  if (response.value?.ref !== `refs/tags/${tagName}`
      || response.value?.object?.type !== "commit"
      || response.value?.object?.sha !== sourceCommit) fail("release tag is not an exact commit ref");
}

function assertBetaTag(slug, sourceCommit, state) {
  assertExactTag(slug, CHANNEL_TAG, sourceCommit, state);
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

function readReleaseByTag(slug, tagName) {
  const response = ghJson(["api", `repos/${slug}/releases/tags/${tagName}`]);
  return releaseProjection(response.value);
}

function readBetaReleaseByTag(slug) {
  return readReleaseByTag(slug, CHANNEL_TAG);
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

function classifyBetaState(state, candidate, resumeDraftId) {
  const channelReleases = state.releases.filter((release) => release.tagName === CHANNEL_TAG);
  if (channelReleases.length > 1) fail("beta Release identity is ambiguous");
  const channelRelease = channelReleases[0] || null;
  const betaCommit = remoteTagCommit(state, CHANNEL_TAG);
  const betaTagPresent = betaCommit !== "";
  let phase = "";

  if (!channelRelease && !betaTagPresent) {
    phase = "initial";
  } else if (channelRelease
      && channelRelease.name === RELEASE_TITLE
      && channelRelease.targetCommitish === candidate.value.source.commit
      && channelRelease.prerelease === true) {
    if (channelRelease.draft === true && !betaTagPresent) phase = "draft";
    else if (channelRelease.draft === false && betaTagPresent
        && betaCommit === candidate.value.source.commit) phase = "complete";
  }

  if (!phase) fail("public beta state is not a recognized fresh-channel checkpoint");
  if (resumeDraftId !== undefined) {
    if (!["draft", "complete"].includes(phase) || channelRelease.id !== resumeDraftId) {
      fail("resume draft does not match the exact beta checkpoint");
    }
  } else if (phase === "draft") {
    fail(`an exact beta draft exists; resume with --resume-draft ${channelRelease.id}`);
  }
  return {
    betaRefExpected: betaTagPresent,
    candidateRelease: channelRelease,
    phase,
  };
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
  const rotation = classifyBetaState(state, candidate, resumeDraftId);
  assertHistoryAndLatestState(state, candidate.value.source.commit, {
    betaRefExpected: rotation.betaRefExpected,
  });
  if (rotation.phase === "initial") assertBetaAbsent(slug, state);
  verifyPreviousStable(slug, state, candidate, previousApk, wordlists, aapt, apksigner);
  if (["draft", "complete"].includes(rotation.phase)) {
    verifyRemoteBeta(
      slug, state, candidate, rotation.phase === "draft",
      rotation.candidateRelease.id, wordlists, aapt, apksigner,
    );
  }
  const closure = captureReachableObjectClosure(
    state, candidate.value.source.commit, wordlists,
  );
  const evidence = canonical({ closure, rotation: { phase: rotation.phase }, state });
  if (expectedEvidence && canonicalJson(evidence) !== canonicalJson(expectedEvidence)) {
    fail("public state changed between preflights");
  }
  return evidence;
}

function githubJsonWrite(args, value) {
  const input = Buffer.from(`${JSON.stringify(value)}\n`, "utf8");
  const result = run("gh", ["api", ...args, "--input", "-"], { input });
  try {
    return JSON.parse(result.stdout);
  } catch {
    fail("GitHub write response is invalid");
  }
}

function assertPublicationCheckpoint(evidence, expectedPhase, preservedState, closure, latest) {
  if (evidence.rotation?.phase !== expectedPhase) {
    fail(`beta publication did not reach ${expectedPhase}`);
  }
  assertPreserved(preservedState, evidence.state);
  if (canonicalJson(evidence.closure) !== canonicalJson(closure)
      || canonicalJson(evidence.state.latest) !== canonicalJson(latest)) {
    fail("beta publication changed source closure or latest");
  }
}

function exactDraftId(state) {
  const drafts = state.releases.filter((release) => release.tagName === CHANNEL_TAG
    && release.name === RELEASE_TITLE && release.draft === true && release.prerelease === true);
  if (drafts.length !== 1) fail("created beta draft is not unique");
  return drafts[0].id;
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
      || wordlists.some((wordlist) => pathIsWithin(ROOT, wordlist.absolute))) {
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
  const preservedState = stateWithoutBetaLine(before.state);
  const baselineClosure = before.closure;
  const baselineLatest = before.state.latest;
  let checkpoint = before;
  let phase = checkpoint.rotation.phase;

  if (phase === "complete") {
    info("the exact beta.1 Release is already published; verified without remote writes");
    fs.rmSync(auditDirectory, { recursive: true, force: false });
    auditDirectory = "";
    return;
  }

  let draftId = args.resumeDraftId;
  if (phase === "initial") {
    sideEffectStarted = true;
    info("creating the fresh fixed beta channel as a non-latest draft");
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
    draftId = exactDraftId(capturePublicState(slug, wordlists));
    checkpoint = preflight(
      candidate, previousApk, wordlists, slug, aapt, apksigner, draftId,
    );
    assertPublicationCheckpoint(checkpoint, "draft",
      preservedState, baselineClosure, baselineLatest);
    phase = checkpoint.rotation.phase;
  } else if (phase === "draft") {
    info(`resuming exact existing beta draft Release ${draftId}`);
  }
  if (phase !== "draft" || !Number.isSafeInteger(draftId)) {
    fail("beta publication did not reach an exact draft");
  }

  const draftState = checkpoint.state;
  const draft = verifyRemoteBeta(
    slug, draftState, candidate, true, draftId, wordlists, aapt, apksigner,
  );

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
  assertHistoryAndLatestState(boundaryState, candidate.value.source.commit, {
    betaRefExpected: false,
  });
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
  verifyPreviousStable(
    slug, boundaryState, candidate, previousApk, wordlists, aapt, apksigner,
  );
  if (canonicalJson(boundaryDraft) !== canonicalJson(draft)
      || canonicalJson(boundaryState.latest) !== canonicalJson(before.state.latest)) fail();
  assertCandidateInputsStable(candidate, previousApk, wordlists);

  sideEffectStarted = true;
  info("making the twice-verified beta draft public without changing latest");
  const patchedRelease = releaseProjection(githubJsonWrite([
    `repos/${slug}/releases/${draft.id}`, "--method", "PATCH",
  ], JSON.parse(patch.toString("utf8"))));
  assertBetaReleaseEnvelope(patchedRelease, candidate, false);
  if (patchedRelease.id !== draft.id) fail("beta publication response changed Release identity");

  assertCandidateInputsStable(candidate, previousApk, wordlists);
  const finalState = capturePublicState(slug, wordlists);
  assertHistoryAndLatestState(
    finalState, candidate.value.source.commit, {
      betaRefExpected: true,
    },
  );
  assertPreserved(preservedState, finalState);
  verifyPreviousStable(slug, finalState, candidate, previousApk, wordlists, aapt, apksigner);
  verifyRemoteBeta(
    slug, finalState, candidate, false, draft.id, wordlists, aapt, apksigner,
  );
  const finalClosure = captureReachableObjectClosure(
    finalState, candidate.value.source.commit, wordlists,
  );
  if (canonicalJson(finalClosure) !== canonicalJson(baselineClosure)
      || canonicalJson(finalState.latest) !== canonicalJson(baselineLatest)) fail();
  info("published verified beta.1 without changing stable latest or preserved history");
  fs.rmSync(auditDirectory, { recursive: true, force: false });
  auditDirectory = "";
}

try {
  main();
} catch (error) {
  process.stderr.write(`${error instanceof Error ? error.message : "publish-beta-release failed"}\n`);
  process.exitCode = 1;
}
