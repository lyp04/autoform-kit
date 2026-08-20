#!/usr/bin/env node

import assert from "node:assert/strict";
import crypto from "node:crypto";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import process from "node:process";
import { spawnSync } from "node:child_process";
import { fileURLToPath } from "node:url";

const HERE = path.dirname(fileURLToPath(import.meta.url));
const PUBLISHER = path.join(HERE, "publish-beta-release.mjs");

function sha256(value) {
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

function write(filename, value, mode = 0o600) {
  fs.mkdirSync(path.dirname(filename), { recursive: true });
  fs.writeFileSync(filename, value, { mode });
  fs.chmodSync(filename, mode);
}

assert.equal(fs.lstatSync(PUBLISHER).isFile(), true);
const help = spawnSync(process.execPath, [PUBLISHER, "--help"], { encoding: "utf8" });
assert.equal(help.status, 0);
assert.equal(help.stdout.includes("\n+"), false);

const SOURCE = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
const TREE = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";
const PREVIOUS_SOURCE = "1111111111111111111111111111111111111111";
const PREVIOUS_TREE = "2222222222222222222222222222222222222222";
const EXTRA_BRANCH_SOURCE = "3333333333333333333333333333333333333333";
const PULL_HEAD_SOURCE = "4444444444444444444444444444444444444444";
const PULL_MERGE_SOURCE = "5555555555555555555555555555555555555555";
const SIGNER = "cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc";
const BODY = "Public beta build of the autoform-kit framework. "
  + "No site-specific configuration is included.";
const PREVIOUS_BODY = "Panel controls camera, gallery, or file selection for each photo location; "
  + "includes same-Panel draft upgrades and Panel notification/print controls.";
const HISTORY_BODY = "Sanitized historical rebuild from the public autoform-kit framework. "
  + "No site-specific configuration is included.";
const PRIVATE_EMAIL = ["private.person", "example.edu"].join("@");
const PUBLIC_ACTOR = ["ly", "p04"].join("");
const PUBLIC_NOREPLY = ["57296300+", PUBLIC_ACTOR, "@users.", "noreply.github.com"].join("");

const fakeScanner = String.raw`#!/usr/bin/env node
import crypto from "node:crypto";
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";
const self = fileURLToPath(import.meta.url);
const root = path.resolve(path.dirname(self), "..");
const state = JSON.parse(fs.readFileSync(process.env.BETA_FIXTURE_STATE, "utf8"));
const sha = (v) => crypto.createHash("sha256").update(v).digest("hex");
const canonical = (v) => Array.isArray(v) ? v.map(canonical) : v && typeof v === "object"
  ? Object.fromEntries(Object.keys(v).sort().map((k) => [k, canonical(v[k])])) : v;
const args = process.argv.slice(2);
let mode = ""; let input = ""; const wordlists = [];
for (let i = 0; i < args.length; i += 1) {
  if (["--git-tree", "--apk", "--file"].includes(args[i])) { mode = args[i].slice(2); input = args[++i]; }
  else if (args[i] === "--worktree") mode = "worktree";
  else if (args[i] === "--private-wordlist") wordlists.push(args[++i]);
  else if (args[i] === "--repo") i += 1;
  else process.exit(2);
}
let identity;
if (mode === "git-tree") identity = { mode, gitTreeOid: state.tree, sha256: sha("tree-input") };
else if (mode === "worktree") identity = { mode, sha256: sha("worktree-input") };
else {
  const bytes = fs.readFileSync(input);
  identity = { mode, sha256: sha(bytes) };
  if (mode === "apk") identity.zipEntryManifestSha256 = sha(Buffer.concat([Buffer.from("zip:"), bytes]));
}
if (mode === "file") {
  const bytes = fs.readFileSync(input);
  const terms = wordlists.flatMap((filename) => fs.readFileSync(filename, "utf8")
    .split(/\r?\n/u).filter(Boolean));
  if (terms.some((term) => bytes.includes(Buffer.from(term, "utf8")))) {
    process.stderr.write("fixture private term found\n"); process.exit(1);
  }
}
identity.entryCount = 1;
const policyBytes = fs.readFileSync(path.join(root, "tools", "apk-third-party-components.json"));
const base = {
  schemaVersion: 1,
  scannerSha256: sha(fs.readFileSync(self)),
  policySha256: sha("fixture-public-policy"),
  input: identity,
  privatePolicy: { applied: true, wordlistCount: 1, termCount: 1 },
  thirdPartyPolicy: {
    manifestSha256: sha(policyBytes), applied: mode === "apk", profileId: mode === "apk" ? "fixture" : null,
    matchedEntryCount: mode === "apk" ? 1 : 0, declaredDexStringCount: mode === "apk" ? 1 : 0,
    matchedDexStringCount: mode === "apk" ? 1 : 0,
    applicationDexPolicy: "fixture strict application DEX policy",
  },
  summary: { passed: true, findingCount: 0 }, findings: [],
};
const report = { ...base, reportSha256: sha(JSON.stringify(canonical(base))) };
process.stdout.write(JSON.stringify(canonical(report)) + "\n");
`;

const fakeVerifier = String.raw`#!/usr/bin/env node
import crypto from "node:crypto";
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";
const self = fileURLToPath(import.meta.url);
const sha = (v) => crypto.createHash("sha256").update(v).digest("hex");
const canonical = (v) => Array.isArray(v) ? v.map(canonical) : v && typeof v === "object"
  ? Object.fromEntries(Object.keys(v).sort().map((k) => [k, canonical(v[k])])) : v;
const args = process.argv.slice(2); const value = (key) => args[args.indexOf(key) + 1];
const base = {
  schemaVersion: 1, passed: true, verifierSha256: sha(fs.readFileSync(self)),
  policySha256: sha(fs.readFileSync(value("--policy"))), apkSha256: sha(fs.readFileSync(value("--apk"))),
  profileId: "fixture", sourceArtifactCount: 1, sourceEntryCount: 1, mergedSourceCount: 1,
  compiledOutputCount: 1, dexSourceArtifactCount: 1, dexSourceEntryCount: 1,
  declaredDexStringCount: 1, sourceMatchedDexStringCount: 1, apkMatchedDexStringCount: 1,
};
process.stdout.write(JSON.stringify({ ...base, reportSha256: sha(JSON.stringify(canonical(base))) }) + "\n");
`;

const fakeGit = String.raw`#!/usr/bin/env node
import fs from "node:fs";
const state = JSON.parse(fs.readFileSync(process.env.BETA_FIXTURE_STATE, "utf8"));
const save = () => fs.writeFileSync(process.env.BETA_FIXTURE_STATE, JSON.stringify(state));
const a = process.argv.slice(2);
const blob = "eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee";
const identity = state.commitMetadataLeak ? state.privateEmail : state.publicNoreply;
const objects = new Map([
  [state.source, { type: "commit", bytes: Buffer.from("tree " + state.tree + "\\nauthor " + state.publicActor + " <" + identity + "> 0 +0000\\ncommitter " + state.publicActor + " <" + identity + "> 0 +0000\\n\\nfixture source\\n") }],
  [state.tree, { type: "tree", bytes: Buffer.from("fixture tree bytes\\n") }],
  [state.previousSource, { type: "commit", bytes: Buffer.from("tree " + state.previousTree + "\\nauthor " + state.publicActor + " <" + state.publicNoreply + "> 0 +0000\\ncommitter " + state.publicActor + " <" + state.publicNoreply + "> 0 +0000\\n\\nfixture previous source\\n") }],
  [state.previousTree, { type: "tree", bytes: Buffer.from("fixture previous tree bytes\\n") }],
  [state.extraBranchSource, { type: "commit", bytes: Buffer.from("tree " + state.previousTree + "\\nauthor fixture <fixture@users.noreply.github.com> 0 +0000\\ncommitter fixture <fixture@users.noreply.github.com> 0 +0000\\n\\nfixture branch\\n") }],
  [state.pullHeadSource, { type: "commit", bytes: Buffer.from("tree " + state.previousTree + "\\nauthor fixture <fixture@users.noreply.github.com> 0 +0000\\ncommitter fixture <fixture@users.noreply.github.com> 0 +0000\\n\\nfixture pull head\\n") }],
  [state.pullMergeSource, { type: "commit", bytes: Buffer.from("tree " + state.previousTree + "\\nauthor fixture <fixture@users.noreply.github.com> 0 +0000\\ncommitter fixture <fixture@users.noreply.github.com> 0 +0000\\n\\nfixture pull merge\\n") }],
  [blob, { type: "blob", bytes: Buffer.from("fixture public blob\\n") }],
]);
if (a[0] === "rev-parse" && a[1] === "HEAD") process.stdout.write(state.source + "\n");
else if (a[0] === "rev-parse" && a[1].endsWith("^{tree}")) process.stdout.write(state.tree + "\n");
else if (a[0] === "branch" && a[1] === "--show-current") process.stdout.write("main\n");
else if (a[0] === "status" || a[0] === "diff") process.exit(0);
else if (a[0] === "merge-base" && a[1] === "--is-ancestor") process.exit(0);
else if (a[0] === "rev-list" && a[1] === "--objects") {
  const roots = a.slice(2);
  state.revListRoots = roots; save();
  process.stdout.write([...new Set([...roots, state.tree, state.previousTree])].join("\n")
    + "\n" + blob + " public.txt\n");
} else if (a[0] === "cat-file" && a[1] === "-e") process.exit(0);
else if (a[0] === "cat-file" && a[1] === "commit") process.stdout.write(objects.get(a[2]).bytes);
else if (a[0] === "cat-file" && a[1].startsWith("--batch-check=")) {
  const ids = fs.readFileSync(0, "utf8").trim().split("\n").filter(Boolean);
  for (const id of ids) { const object = objects.get(id); process.stdout.write(id + " " + object.type + " " + object.bytes.length + "\n"); }
} else if (a[0] === "cat-file" && ["blob", "commit", "tag", "tree"].includes(a[1])) {
  process.stdout.write(objects.get(a[2]).bytes);
}
else if (a[0] === "remote" && a[1] === "get-url") process.stdout.write("https://github.com/example/autoform-kit.git\n");
else if (a[0] === "ls-remote") {
  const lines = ["ref: refs/heads/main\tHEAD", state.source + "\tHEAD", state.source + "\trefs/heads/main"];
  lines.push((state.branchRefDrift ? state.source : state.extraBranchSource)
    + "\trefs/heads/codex/fixture-branch");
  for (let i = 0; i < state.preservedReleases.length; i += 1) {
    const release = state.preservedReleases[i];
    lines.push(((state.historyTagDrift && state.releaseReads >= 2 && i === 0)
      || (state.boundaryTagDrift && state.releaseReads >= 5
        && release.tag_name === "v1.0.16")
      ? "dddddddddddddddddddddddddddddddddddddddd" : release.target_commitish)
      + "\trefs/tags/" + release.tag_name);
  }
  if (state.betaRef) lines.push((state.betaWrongTarget
    || (state.boundaryTagDrift && state.releaseReads >= 5)
    ? "dddddddddddddddddddddddddddddddddddddddd" : state.betaRef) + "\trefs/tags/beta");
  if (state.extraTag) lines.push(state.source + "\trefs/tags/extra");
  lines.push(state.pullHeadSource + "\trefs/pull/7/head");
  lines.push(state.pullMergeSource + "\trefs/pull/7/merge");
  if (state.boundaryExtraPull && state.releaseReads >= 5) {
    lines.push(state.source + "\trefs/pull/8/head");
  }
  if (state.extraOtherRef) lines.push(state.source + "\trefs/notes/audit");
  if (state.invalidPullRef) lines.push(state.source + "\trefs/pull/not-a-number/head");
  process.stdout.write(lines.join("\n") + "\n");
} else process.exit(7);
`;

const fakeAapt = String.raw`#!/usr/bin/env node
import fs from "node:fs";
const state = JSON.parse(fs.readFileSync(process.env.BETA_FIXTURE_STATE, "utf8"));
const file = process.argv.at(-1);
if (file.includes("previous.apk") || file.includes("1.0.16.apk")) {
  process.stdout.write("package: name='com.autoformkit.app' versionCode='20' versionName='1.0.16'\n");
} else if (state.apkIdentityDrift) {
  process.stdout.write("package: name='example.invalid' versionCode='21' versionName='1.0.17-beta.1'\n");
} else {
  process.stdout.write("package: name='com.autoformkit.app' versionCode='21' versionName='1.0.17-beta.1'\n");
}
`;

const fakeApksigner = `#!/usr/bin/env node
const fs = require("node:fs");
const state = JSON.parse(fs.readFileSync(process.env.BETA_FIXTURE_STATE, "utf8"));
const file = process.argv.at(-1);
const candidate = file.includes("1.0.17-beta.1");
const predecessor = file.includes("previous.apk") || file.includes("1.0.16.apk");
const signer = (state.signerDrift && candidate) || (state.previousSignerDrift && predecessor)
  ? "${"d".repeat(64)}" : "${SIGNER}";
process.stdout.write("Signer #1 certificate SHA-256 digest: " + signer + "\\n");
`;

const fakeGh = String.raw`#!/usr/bin/env node
import crypto from "node:crypto";
import fs from "node:fs";
const stateFile = process.env.BETA_FIXTURE_STATE;
const state = JSON.parse(fs.readFileSync(stateFile, "utf8"));
const REPOSITORY_CREATED_AT_KEY = ["created", "at"].join("_");
const save = () => fs.writeFileSync(stateFile, JSON.stringify(state));
const sha = (v) => crypto.createHash("sha256").update(v).digest("hex");
const asset = (id, name, bytes) => ({ id, node_id: "A_" + id, name, label: null, state: "uploaded",
  size: bytes.length, content_type: name.endsWith(".apk") ? "application/vnd.android.package-archive" : "application/json",
  digest: "sha256:" + sha(bytes), url: "https://api.github.com/repos/example/autoform-kit/releases/assets/" + id,
  browser_download_url: "https://github.com/example/autoform-kit/releases/download/beta/" + name });
const history = () => state.preservedReleases.map((release, index) => {
  const copy = structuredClone(release);
  if (state.historyDrift && state.releaseReads >= 2 && index === 0) copy.body = "drift";
  return copy;
});
const releases = () => {
  const historical = history();
  if (state.duplicateHistoricalRelease) historical.push({ ...historical[0], id: 777 });
  return [...historical, ...(state.beta ? [state.beta] : [])];
};
const a = process.argv.slice(2);
if (a[0] === "auth") process.exit(0);
if (a[0] === "release" && a[1] === "create") {
  state.writes += 1; state.commands.push(a);
  const target = a[a.indexOf("--target") + 1]; const title = a[a.indexOf("--title") + 1];
  const notes = fs.readFileSync(a[a.indexOf("--notes-file") + 1], "utf8");
  const files = a.slice(3, a.indexOf("--repo"));
  state.beta = { id: 1000, node_id: "R_beta_next", tag_name: "beta", target_commitish: target,
    name: title, body: notes, draft: true, prerelease: true, immutable: false,
    assets: files.map((file, i) => { const bytes = fs.readFileSync(file); const out = asset(1001 + i, file.split("/").at(-1), bytes); out.bytes = bytes.toString("base64"); return out; }) };
  if (state.assetDrift) state.beta.assets[0].digest = "sha256:" + "d".repeat(64);
  if (state.extraAsset) { const bytes = Buffer.from("extra"); const out = asset(999, "extra.json", bytes); out.bytes = bytes.toString("base64"); state.beta.assets.push(out); }
  if (state.releaseFlagDrift) state.beta.prerelease = false;
  save();
  if (state.createFails) { process.stderr.write("fixture create failure\n"); process.exit(1); }
  process.stdout.write("https://github.com/example/autoform-kit/releases/tag/beta\n"); process.exit(0);
}
if (a[0] !== "api") process.exit(8);
const endpoint = a[1];
if (endpoint === "repos/example/autoform-kit") {
  const repository = { id: 1, node_id: "R_repo", full_name: "example/autoform-kit", private: false,
    visibility: "public", default_branch: "main", description: "Public form framework",
    homepage: null, topics: ["forms"] };
  repository[REPOSITORY_CREATED_AT_KEY] = "2026-01-01T00:00:00Z";
  process.stdout.write(JSON.stringify(repository));
} else if (endpoint.includes("/branches?")) {
  const branches = [
    { name: "main", commit: { sha: state.source }, protected: true },
    { name: "codex/fixture-branch", commit: { sha: state.extraBranchSource }, protected: false },
  ];
  process.stdout.write(JSON.stringify([branches]));
} else if (endpoint.includes("/tags?")) {
  const tags = state.preservedReleases.map((release, index) => ({ name: release.tag_name,
    commit: { sha: (state.historyTagDrift && state.releaseReads >= 2 && index === 0)
      || (state.boundaryTagDrift && state.releaseReads >= 5
        && release.tag_name === "v1.0.16")
      ? "d".repeat(40) : release.target_commitish } }));
  if (state.extraTag) tags.push({ name: "extra", commit: { sha: state.source } });
  if (state.betaRef) tags.push({ name: "beta", commit: { sha:
    state.betaWrongTarget || (state.boundaryTagDrift && state.releaseReads >= 5)
      ? "d".repeat(40) : state.betaRef } });
  process.stdout.write(JSON.stringify([tags]));
} else if (endpoint.includes("/releases?")) {
  state.releaseReads += 1;
  if (state.boundaryReleaseDrift && state.beta && state.releaseReads >= 5) {
    state.beta.name = "concurrent draft mutation";
  }
  save(); process.stdout.write(JSON.stringify([releases()]));
} else if (endpoint.endsWith("/releases/latest")) {
  state.latestReads += 1; save();
  if (state.stealLatest && state.beta) { process.stdout.write(JSON.stringify(state.beta)); process.exit(0); }
  const latest = history().find((release) => release.tag_name === "v1.0.16");
  if (state.latestDrift && state.latestReads >= 2) latest.name = "drift";
  if (state.latestHistoricalMetadataDrift) latest.name = "drift";
  if (state.latestHistoricalAssetDrift) latest.assets[0].digest = "sha256:" + "d".repeat(64);
  process.stdout.write(JSON.stringify(latest)); process.exit(0);
} else if (/\/releases\/tags\/(?:beta|v1\.0\.16)$/.test(endpoint)) {
  const tag = endpoint.split("/").at(-1);
  const found = tag === "beta" ? state.beta
    : state.preservedReleases.find((release) => release.tag_name === tag);
  if (!found || found.draft) { process.stderr.write("HTTP 404 Not Found\n"); process.exit(1); }
  process.stdout.write(JSON.stringify(found));
} else if (/\/git\/ref\/tags\/(?:beta|v1\.0\.16)$/.test(endpoint)) {
  const tag = endpoint.split("/").at(-1);
  const value = tag === "beta" ? state.betaRef
    : state.preservedReleases.find((release) => release.tag_name === tag)?.target_commitish;
  if (!value) { process.stderr.write("HTTP 404 Not Found\n"); process.exit(1); }
  process.stdout.write(JSON.stringify({ ref: "refs/tags/" + tag, object: { type: "commit", sha:
    tag === "beta" && (state.betaWrongTarget
      || (state.boundaryTagDrift && state.releaseReads >= 5))
      ? "d".repeat(40) : value } }));
} else if (/\/releases\/assets\/[0-9]+$/.test(endpoint)) {
  const id = Number(endpoint.split("/").at(-1));
  const found = [...state.preservedReleases.flatMap((release) => release.assets),
    ...(state.beta?.assets || [])]
    .find((v) => v.id === id);
  if (!found) process.exit(9);
  const bytes = Buffer.from(found.bytes, "base64");
  state.assetDownloadReads += 1; save();
  const drift = (found.name === "autoform-kit-1.0.16.apk" && state.previousAssetBytesDrift)
    || (id === 1001 && (state.assetBytesDrift
      || (state.boundaryAssetBytesDrift && state.releaseReads >= 5)));
  process.stdout.write(drift ? Buffer.concat([bytes, Buffer.from("drift")]) : bytes);
} else if (/\/releases\/[0-9]+$/.test(endpoint) && !a.includes("PATCH")) {
  const id = Number(endpoint.split("/").at(-1));
  const found = id === 1000 ? state.beta
    : state.preservedReleases.find((release) => release.id === id);
  if (!found) { process.stderr.write("HTTP 404 Not Found\n"); process.exit(1); }
  state.releaseIdReads += 1; save(); process.stdout.write(JSON.stringify(found));
} else if (endpoint.endsWith("/releases/1000") && a.includes("PATCH")) {
  state.writes += 1; state.commands.push(a); const body = JSON.parse(fs.readFileSync(0, "utf8"));
  state.patchBody = body; save();
  if (state.patchFails) { process.stderr.write("fixture patch failure\n"); process.exit(1); }
  if (body.make_latest !== "false" || body.draft !== false || body.prerelease !== true) process.exit(9);
  state.beta = { ...state.beta, draft: false, prerelease: true, body: body.body, name: body.name,
    tag_name: body.tag_name, target_commitish: body.target_commitish }; save();
  state.betaRef = body.target_commitish; save();
  if (state.finalFlagDrift) { state.beta.prerelease = false; save(); }
  if (state.patchAppliedThenFails) {
    process.stderr.write("fixture applied patch response failure\n"); process.exit(1);
  }
  process.stdout.write(JSON.stringify(state.beta));
} else process.exit(10);
`;

function scannerReport(root, stateFile, mode, input) {
  const args = [path.join(root, "tools", "public-surface-audit.mjs")];
  if (mode === "git-tree") args.push("--git-tree", SOURCE, "--repo", root);
  else if (mode === "worktree") args.push("--worktree", "--repo", root);
  else args.push(`--${mode}`, input);
  args.push("--private-wordlist", path.join(root, "private", "wordlist.txt"));
  const result = spawnSync(process.execPath, args, {
    cwd: root, encoding: "utf8", env: { ...process.env, BETA_FIXTURE_STATE: stateFile },
  });
  assert.equal(result.status, 0, result.stderr);
  return JSON.parse(result.stdout);
}

function verifierReport(root, apk) {
  const result = spawnSync(process.execPath, [path.join(root, "tools", "verify-apk-third-party-sources.mjs"),
    "--apk", apk, "--policy", path.join(root, "tools", "apk-third-party-components.json"),
    "--build-dir", path.join(root, "app", "build"), "--gradle-user-home", path.join(root, "gradle")],
  { cwd: root, encoding: "utf8" });
  assert.equal(result.status, 0, result.stderr);
  return JSON.parse(result.stdout);
}

function installFixtureAllowlist(root, specs) {
  const filename = path.join(root, "tools", "publish-beta-release.mjs");
  const source = fs.readFileSync(filename, "utf8");
  const updated = source.replace(
    /const PRESERVED_RELEASE_SPECS = \[[\s\S]*?\n\];\n\nlet sideEffectStarted/u,
    `const PRESERVED_RELEASE_SPECS = ${JSON.stringify(specs, null, 2)};\n\nlet sideEffectStarted`,
  );
  assert.notEqual(updated, source, "fixture release allowlist replacement must be exact");
  write(filename, updated, 0o755);
}

function fixture({ phase = "initial", tagExists = false, existingDraft = false,
  historyDrift = false, historyTagDrift = false,
  latestDrift = false, latestHistoricalMetadataDrift = false,
  latestHistoricalAssetDrift = false, duplicateHistoricalRelease = false,
  createFails = false, assetDrift = false,
  betaWrongTarget = false, apkIdentityDrift = false, signerDrift = false,
  previousSignerDrift = false, previousReleaseDrift = false,
  previousTargetDrift = false, previousAssetMetadataDrift = false,
  previousAssetBytesDrift = false, previousManifestDrift = false,
  previousAuditDrift = false, previousUpdateDrift = false,
  sourceDrift = false, updateBytesDrift = false, releaseFlagDrift = false,
  extraAsset = false, stealLatest = false, assetBytesDrift = false,
  patchFails = false, patchAppliedThenFails = false, finalFlagDrift = false,
  manifestCodeDrift = false,
  commitMetadataLeak = false, branchRefDrift = false, extraTag = false,
  invalidPullRef = false, extraOtherRef = false,
  boundaryReleaseDrift = false, boundaryAssetBytesDrift = false,
  boundaryTagDrift = false, boundaryExtraPull = false } = {}) {
  if (existingDraft) phase = "draft";
  assert.equal(["initial", "draft", "complete"].includes(phase), true);
  const root = fs.mkdtempSync(path.join(os.tmpdir(), "beta-publisher-selftest."));
  const tools = path.join(root, "tools"); const bin = path.join(root, "bin");
  fs.mkdirSync(tools, { recursive: true }); fs.mkdirSync(bin, { recursive: true });
  fs.copyFileSync(PUBLISHER, path.join(tools, "publish-beta-release.mjs"));
  fs.copyFileSync(path.join(HERE, "historical-release-contract.mjs"),
    path.join(tools, "historical-release-contract.mjs"));
  write(path.join(tools, "public-surface-audit.mjs"), fakeScanner, 0o755);
  write(path.join(tools, "verify-apk-third-party-sources.mjs"), fakeVerifier, 0o755);
  write(path.join(tools, "apk-third-party-components.json"), "fixture-policy\n", 0o644);
  write(path.join(tools, "android-runtime-dependencies.lock.json"), "fixture-lock\n", 0o644);
  write(path.join(bin, "git"), fakeGit, 0o755); write(path.join(bin, "gh"), fakeGh, 0o755);
  write(path.join(bin, "aapt"), fakeAapt, 0o755);
  write(path.join(bin, "apksigner"), fakeApksigner, 0o755);
  write(path.join(root, "private", "wordlist.txt"),
    `private-fixture-term\n${PRIVATE_EMAIL}\n`, 0o600);
  const stateFile = path.join(root, "private", "state.json");
  write(stateFile, JSON.stringify({ source: SOURCE, tree: TREE,
    previousSource: PREVIOUS_SOURCE, previousTree: PREVIOUS_TREE,
    extraBranchSource: EXTRA_BRANCH_SOURCE, pullHeadSource: PULL_HEAD_SOURCE,
    pullMergeSource: PULL_MERGE_SOURCE, preservedReleases: [],
    beta: null, betaRef: null,
    historyDrift, historyTagDrift, latestDrift, latestHistoricalMetadataDrift,
    latestHistoricalAssetDrift, duplicateHistoricalRelease,
    createFails, assetDrift, betaWrongTarget,
    apkIdentityDrift, signerDrift, previousSignerDrift, previousAssetBytesDrift,
    sourceDrift, releaseFlagDrift, extraAsset, stealLatest,
    assetBytesDrift, patchFails, patchAppliedThenFails, finalFlagDrift,
    commitMetadataLeak, privateEmail: PRIVATE_EMAIL,
    publicActor: PUBLIC_ACTOR, publicNoreply: PUBLIC_NOREPLY,
    branchRefDrift, extraTag, invalidPullRef, extraOtherRef, boundaryReleaseDrift,
    boundaryAssetBytesDrift,
    boundaryTagDrift, boundaryExtraPull,
    releaseReads: 0, releaseIdReads: 0, latestReads: 0, assetDownloadReads: 0,
    writes: 0, commands: [] }), 0o600);
  const candidateDir = path.join(root, "dist", "release-candidates", "v1.0.17-beta.1");
  const apk = path.join(candidateDir, "autoform-kit-1.0.17-beta.1.apk");
  const previous = path.join(root, "private", "previous.apk");
  const notes = path.join(candidateDir, "release-notes.txt");
  const update = path.join(candidateDir, "update.json");
  write(apk, "fixture-beta-1.0.17-apk\n", 0o644);
  write(previous, "fixture-stable-1.0.16-apk\n", 0o600);
  write(notes, `${BODY}\n`, 0o644);
  const apkSha = sha256(fs.readFileSync(apk));
  write(update, `${JSON.stringify({ packageName: "com.autoformkit.app", versionCode: 21,
    versionName: "1.0.17-beta.1", apkAsset: "autoform-kit-1.0.17-beta.1.apk",
    sha256: apkSha, notes: BODY }, null, 2)}\n`, 0o644);
  const tree = scannerReport(root, stateFile, "git-tree", SOURCE);
  const worktree = scannerReport(root, stateFile, "worktree", "");
  const apkAudit = scannerReport(root, stateFile, "apk", apk);
  const updateAudit = scannerReport(root, stateFile, "file", update);
  const notesAudit = scannerReport(root, stateFile, "file", notes);
  const source = verifierReport(root, apk);
  const manifest = {
    schemaVersion: 2, tag: "v1.0.17-beta.1",
    source: { branch: "main", commit: SOURCE, workingTreeClean: true },
    app: { packageName: "com.autoformkit.app", versionCode: manifestCodeDrift ? 22 : 21,
      versionName: "1.0.17-beta.1", signerSha256: SIGNER },
    previousApk: { packageName: "com.autoformkit.app", versionCode: 20,
      versionName: "1.0.16",
      signerSha256: SIGNER, sha256: sha256(fs.readFileSync(previous)) },
    artifacts: {
      apk: { file: path.basename(apk), sha256: apkSha },
      update: { file: "update.json", sha256: sha256(fs.readFileSync(update)) },
      notes: { file: "release-notes.txt", sha256: sha256(fs.readFileSync(notes)) },
    },
    publicAudit: {
      scannerSha256: tree.scannerSha256, policySha256: tree.policySha256,
      sourceTree: { gitTreeOid: TREE, inputSha256: tree.input.sha256,
        reportSha256: tree.reportSha256 },
      worktree: { inputSha256: worktree.input.sha256, reportSha256: worktree.reportSha256 },
      apk: { inputSha256: apkAudit.input.sha256, reportSha256: apkAudit.reportSha256,
        zipEntryManifestSha256: apkAudit.input.zipEntryManifestSha256 },
      releaseMetadata: {
        update: { inputSha256: updateAudit.input.sha256, reportSha256: updateAudit.reportSha256 },
        notes: { inputSha256: notesAudit.input.sha256, reportSha256: notesAudit.reportSha256 },
      },
      thirdPartyProvenance: {
        manifestFile: "tools/apk-third-party-components.json",
        manifestSha256: source.policySha256, runtimeLockFile: "tools/android-runtime-dependencies.lock.json",
        runtimeLockSha256: sha256(fs.readFileSync(path.join(tools, "android-runtime-dependencies.lock.json"))),
        sourceVerifierFile: "tools/verify-apk-third-party-sources.mjs",
        sourceVerifierSha256: source.verifierSha256, sourceReportSha256: source.reportSha256,
        profileId: "fixture", matchedEntryCount: 1, applicationDexStrict: true,
        sourceArtifactCount: 1, sourceEntryCount: 1, mergedSourceCount: 1,
        compiledOutputCount: 1, dexSourceArtifactCount: 1, dexSourceEntryCount: 1,
        declaredDexStringCount: 1, sourceMatchedDexStringCount: 1, apkMatchedDexStringCount: 1,
      },
    },
  };
  const manifestPath = path.join(candidateDir, "candidate-manifest.json");
  write(manifestPath, `${JSON.stringify(manifest, null, 2)}\n`, 0o644);
  if (updateBytesDrift) fs.appendFileSync(update, "drift\n");

  const releaseAsset = (id, name, bytes, tag = "beta") => ({
    id, node_id: `A_${id}`, name, label: tag === "beta" ? null : "",
    state: "uploaded", size: bytes.length,
    content_type: name.endsWith(".apk")
      ? "application/vnd.android.package-archive" : "application/json",
    digest: `sha256:${sha256(bytes)}`,
    url: `https://api.github.com/repos/example/autoform-kit/releases/assets/${id}`,
    browser_download_url: `https://github.com/example/autoform-kit/releases/download/${tag}/${name}`,
    bytes: bytes.toString("base64"),
  });
  const previousApkBytes = fs.readFileSync(previous);
  const previousApkSha = sha256(previousApkBytes);
  const previousUpdateValue = {
    packageName: "com.autoformkit.app", versionCode: previousUpdateDrift ? 19 : 20,
    versionName: "1.0.16", apkAsset: "autoform-kit-1.0.16.apk",
    sha256: previousApkSha, notes: PREVIOUS_BODY,
  };
  const previousUpdateBytes = Buffer.from(`${JSON.stringify(previousUpdateValue, null, 2)}\n`);
  const previousAudit = structuredClone(manifest.publicAudit);
  previousAudit.sourceTree = {
    gitTreeOid: PREVIOUS_TREE,
    inputSha256: sha256("previous-tree-input"),
    reportSha256: sha256("previous-tree-report"),
  };
  previousAudit.worktree = {
    inputSha256: sha256("previous-worktree-input"),
    reportSha256: sha256("previous-worktree-report"),
  };
  previousAudit.apk = {
    inputSha256: previousApkSha,
    reportSha256: sha256("previous-apk-report"),
    zipEntryManifestSha256: sha256("previous-apk-entries"),
  };
  previousAudit.releaseMetadata = {
    update: {
      inputSha256: sha256(previousUpdateBytes),
      reportSha256: sha256("previous-update-report"),
    },
    notes: {
      inputSha256: sha256(Buffer.from(`${PREVIOUS_BODY}\n`)),
      reportSha256: sha256("previous-notes-report"),
    },
  };
  if (previousAuditDrift) previousAudit.unexpected = true;
  const previousManifest = {
    schemaVersion: 2, tag: previousManifestDrift ? "v1.0.15" : "v1.0.16",
    source: { branch: "main", commit: PREVIOUS_SOURCE, workingTreeClean: true },
    app: { packageName: "com.autoformkit.app", versionCode: 20,
      versionName: "1.0.16", signerSha256: SIGNER },
    previousApk: { packageName: "com.autoformkit.app", versionCode: 19,
      versionName: "1.0.15", signerSha256: SIGNER,
      sha256: sha256("fixture-stable-1.0.15-apk") },
    artifacts: {
      apk: { file: "autoform-kit-1.0.16.apk", sha256: previousApkSha },
      update: { file: "update.json", sha256: sha256(previousUpdateBytes) },
      notes: { file: "release-notes.txt", sha256: sha256(Buffer.from(`${PREVIOUS_BODY}\n`)) },
    },
    publicAudit: previousAudit,
  };
  const previousManifestBytes = Buffer.from(`${JSON.stringify(previousManifest, null, 2)}\n`);
  const state = JSON.parse(fs.readFileSync(stateFile, "utf8"));
  const historicalApk = Buffer.from("fixture-historical-apk\n");
  const historicalUpdate = Buffer.from("fixture-historical-update\n");
  const archivedBetaApk = Buffer.from("fixture-archived-beta-apk\n");
  const archivedBetaUpdate = Buffer.from("fixture-archived-beta-update\n");
  const archivedBetaManifest = Buffer.from("fixture-archived-beta-manifest\n");
  const stable15Apk = Buffer.from("fixture-stable-1.0.15-apk");
  const stable15Update = Buffer.from("fixture-stable-1.0.15-update\n");
  const stable15Manifest = Buffer.from("fixture-stable-1.0.15-manifest\n");
  state.preservedReleases = [
    {
      id: 100, node_id: "R_historical", tag_name: "v1.0.0",
      target_commitish: PREVIOUS_SOURCE, name: "autoform-kit 1.0.0", body: HISTORY_BODY,
      draft: false, prerelease: false, immutable: false,
      assets: [
        releaseAsset(101, "autoform-kit-1.0.0.apk", historicalApk, "v1.0.0"),
        releaseAsset(102, "update.json", historicalUpdate, "v1.0.0"),
      ],
    },
    {
      id: 800, node_id: "R_archived_beta", tag_name: "v1.0.8-beta.1",
      target_commitish: PREVIOUS_SOURCE, name: "autoform-kit 1.0.8-beta.1",
      body: `${BODY}\n`, draft: false, prerelease: true, immutable: false,
      assets: [
        releaseAsset(801, "autoform-kit-1.0.8-beta.1.apk", archivedBetaApk, "v1.0.8-beta.1"),
        releaseAsset(802, "candidate-manifest.json", archivedBetaManifest, "v1.0.8-beta.1"),
        releaseAsset(803, "update.json", archivedBetaUpdate, "v1.0.8-beta.1"),
      ],
    },
    {
      id: 915, node_id: "R_stable_15", tag_name: "v1.0.15",
      target_commitish: PREVIOUS_SOURCE, name: "autoform-kit 1.0.15",
      body: "fixture stable 1.0.15\n", draft: false, prerelease: false, immutable: false,
      assets: [
        releaseAsset(9151, "autoform-kit-1.0.15.apk", stable15Apk, "v1.0.15"),
        releaseAsset(9152, "candidate-manifest.json", stable15Manifest, "v1.0.15"),
        releaseAsset(9153, "update.json", stable15Update, "v1.0.15"),
      ],
    },
    {
      id: 916, node_id: "R_stable_16", tag_name: "v1.0.16",
      target_commitish: PREVIOUS_SOURCE, name: "autoform-kit 1.0.16",
      body: `${PREVIOUS_BODY}\n`, draft: false, prerelease: false, immutable: false,
      assets: [
        releaseAsset(9161, "autoform-kit-1.0.16.apk", previousApkBytes, "v1.0.16"),
        releaseAsset(9162, "candidate-manifest.json", previousManifestBytes, "v1.0.16"),
        releaseAsset(9163, "update.json", previousUpdateBytes, "v1.0.16"),
      ],
    },
  ];
  const fixtureSpecs = state.preservedReleases.map((release) => [
    release.tag_name,
    release.target_commitish,
    release.body,
    release.prerelease,
    release.assets.map((asset) => [
      asset.name, asset.digest.slice("sha256:".length), asset.size, asset.content_type,
    ]),
  ]);
  installFixtureAllowlist(root, fixtureSpecs);
  const previousStable = state.preservedReleases.find(
    (release) => release.tag_name === "v1.0.16",
  );
  if (previousTargetDrift) previousStable.target_commitish = "d".repeat(40);
  if (previousReleaseDrift) previousStable.name = "drift";
  if (previousAssetMetadataDrift) {
    previousStable.assets[0].digest = `sha256:${"d".repeat(64)}`;
  }
  state.betaRef = phase === "complete" ? SOURCE : tagExists ? "d".repeat(40) : null;
  if (["draft", "complete"].includes(phase)) {
    const candidateFiles = [apk, update, manifestPath];
    state.beta = {
      id: 1000, node_id: "R_beta_next", tag_name: "beta", target_commitish: SOURCE,
      name: "autoform-kit 1.0.17-beta.1", body: `${BODY}\n`,
      draft: phase === "draft", prerelease: !releaseFlagDrift, immutable: false,
      assets: candidateFiles.map((filename, index) => releaseAsset(
        1001 + index, path.basename(filename), fs.readFileSync(filename), "beta",
      )),
    };
    if (assetDrift) state.beta.assets[0].digest = `sha256:${"d".repeat(64)}`;
    if (extraAsset) state.beta.assets.push({
      ...releaseAsset(999, "extra.json", Buffer.from("extra")),
    });
  }
  if (sourceDrift) {
    state.source = "dddddddddddddddddddddddddddddddddddddddd";
  }
  fs.writeFileSync(stateFile, JSON.stringify(state));
  fs.chmodSync(stateFile, 0o600);
  return { root, stateFile, manifestPath, previous };
}

let scenarioCount = 0;

function scenario(options) {
  const f = fixture(options);
  const baseEnv = { ...process.env,
    PATH: `${path.join(f.root, "bin")}:${process.env.PATH}`,
    BETA_FIXTURE_STATE: f.stateFile,
    AAPT: path.join(f.root, "bin", "aapt"), APKSIGNER: path.join(f.root, "bin", "apksigner"),
    GRADLE_USER_HOME: path.join(f.root, "gradle") };
  return {
    execute({ confirmSingleWriter = true, resumeDraftId } = {}) {
      const env = { ...baseEnv };
      if (confirmSingleWriter) {
        env.AUTOFORM_BETA_SINGLE_WRITER_WINDOW = "EXCLUSIVE_BETA_RELEASE_WRITER_CONFIRMED";
      } else {
        delete env.AUTOFORM_BETA_SINGLE_WRITER_WINDOW;
      }
      const publisherArgs = [path.join(f.root, "tools", "publish-beta-release.mjs"),
        "--candidate", f.manifestPath, "--previous-apk", f.previous,
        "--private-wordlist", path.join(f.root, "private", "wordlist.txt")];
      if (resumeDraftId !== undefined) {
        publisherArgs.push("--resume-draft", String(resumeDraftId));
      }
      const result = spawnSync(process.execPath, publisherArgs, {
        cwd: f.root, encoding: "utf8", env,
      });
      return { result, state: JSON.parse(fs.readFileSync(f.stateFile, "utf8")) };
    },
    mutateState(mutator) {
      const state = JSON.parse(fs.readFileSync(f.stateFile, "utf8"));
      mutator(state);
      write(f.stateFile, JSON.stringify(state), 0o600);
    },
    remove() {
      fs.rmSync(f.root, { recursive: true, force: true });
    },
  };
}

function withScenario(options, callback) {
  scenarioCount += 1;
  const current = scenario(options);
  try {
    return callback(current);
  } finally {
    current.remove();
  }
}

function runScenario(options, executionOptions = {}) {
  return withScenario(options, (current) => current.execute(executionOptions));
}

function writeKinds(state) {
  return state.commands.map((command) => {
    if (command[0] === "release" && command[1] === "create") return "create";
    if (command[0] === "api" && command.includes("PATCH")) return "patch";
    return "unexpected";
  });
}

const happy = runScenario();
assert.equal(happy.result.status, 0, happy.result.stderr);
assert.equal(happy.state.writes, 2);
assert.equal(happy.state.beta.draft, false);
assert.equal(happy.state.beta.prerelease, true);
assert.equal(happy.state.beta.tag_name, "beta");
assert.equal(happy.state.betaRef, SOURCE);
const archivedBeta = happy.state.preservedReleases.find(
  (release) => release.tag_name === "v1.0.8-beta.1",
);
assert.equal(archivedBeta.name, "autoform-kit 1.0.8-beta.1");
assert.equal(archivedBeta.target_commitish, PREVIOUS_SOURCE);
assert.equal(happy.state.commands[0].includes("--latest=false"), true);
assert.equal(happy.state.commands[0].includes("--draft"), true);
assert.equal(happy.state.commands[0].includes("--prerelease"), true);
assert.equal(happy.state.patchBody.make_latest, "false");
assert.equal(happy.state.assetDownloadReads >= 20, true);
assert.equal(happy.state.releaseIdReads >= 6, true);
assert.equal(happy.state.revListRoots.includes(EXTRA_BRANCH_SOURCE), true);
assert.equal(happy.state.revListRoots.includes(PULL_HEAD_SOURCE), true);
assert.equal(happy.state.revListRoots.includes(PULL_MERGE_SOURCE), true);
assert.equal(fs.readFileSync(PUBLISHER, "utf8").includes("publish-release.sh"), false);

withScenario({ createFails: true }, (current) => {
  const failed = current.execute();
  assert.equal(failed.result.status, 1);
  assert.equal(failed.state.writes, 1);
  assert.deepEqual(writeKinds(failed.state), ["create"]);
  assert.equal(failed.state.beta.draft, true);
  assert.equal(failed.state.beta.prerelease, true);
  assert.equal(failed.state.betaRef, null);
  assert.match(failed.result.stderr, /remote may now contain a beta draft/u);
  assert.match(failed.result.stderr, /required local or GitHub check failed/u);

  const recovered = current.execute({ resumeDraftId: 1000 });
  assert.equal(recovered.result.status, 0, recovered.result.stderr);
  assert.equal(recovered.state.writes, 2);
  assert.deepEqual(writeKinds(recovered.state), ["create", "patch"]);
  assert.equal(recovered.state.beta.draft, false);
  assert.equal(recovered.state.beta.prerelease, true);
  assert.equal(recovered.state.betaRef, SOURCE);
});

const resumed = runScenario({ phase: "draft" }, { resumeDraftId: 1000 });
assert.equal(resumed.result.status, 0, resumed.result.stderr);
assert.equal(resumed.state.writes, 1);
assert.equal(resumed.state.commands.some((command) => command[0] === "release"), false);
assert.equal(resumed.state.beta.draft, false);
assert.equal(resumed.state.beta.prerelease, true);

const existingDraftWithoutResume = runScenario({ phase: "draft" });
assert.equal(existingDraftWithoutResume.result.status, 1);
assert.equal(existingDraftWithoutResume.state.writes, 0);
assert.match(existingDraftWithoutResume.result.stderr, /--resume-draft 1000/u);

const wrongResumeId = runScenario({ phase: "draft" }, { resumeDraftId: 999 });
assert.equal(wrongResumeId.result.status, 1);
assert.equal(wrongResumeId.state.writes, 0);
assert.deepEqual(writeKinds(wrongResumeId.state), []);
assert.match(wrongResumeId.result.stderr, /resume draft does not match the exact beta checkpoint/u);

withScenario({ phase: "draft" }, (current) => {
  current.mutateState((state) => { state.beta.body = "malformed resumed draft\n"; });
  const malformed = current.execute({ resumeDraftId: 1000 });
  assert.equal(malformed.result.status, 1);
  assert.equal(malformed.state.writes, 0);
  assert.deepEqual(writeKinds(malformed.state), []);
  assert.equal(malformed.state.beta.draft, true);
  assert.match(malformed.result.stderr, /beta Release envelope mismatch/u);
});

const completed = runScenario({ phase: "complete" });
assert.equal(completed.result.status, 0, completed.result.stderr);
assert.equal(completed.state.writes, 0);
assert.equal(completed.state.betaRef, SOURCE);

const missingSingleWriter = runScenario({}, { confirmSingleWriter: false });
assert.equal(missingSingleWriter.result.status, 1);
assert.equal(missingSingleWriter.state.writes, 0);
assert.match(missingSingleWriter.result.stderr, /single-writer release window/u);

for (const [options, expectedError] of [
  [{ assetDrift: true }, /beta Release asset closure mismatch/u],
  [{ assetBytesDrift: true }, /downloaded beta asset mismatch/u],
  [{ extraAsset: true }, /beta Release envelope mismatch/u],
  [{ releaseFlagDrift: true }, /created beta draft is not unique/u],
  [{ stealLatest: true }, /v1\.0\.16 is not the exact stable latest Release/u],
]) {
  const rejected = runScenario(options);
  assert.equal(rejected.result.status, 1, JSON.stringify(options));
  assert.equal(rejected.state.writes, 1, JSON.stringify(options));
  assert.deepEqual(writeKinds(rejected.state), ["create"], JSON.stringify(options));
  assert.equal(rejected.state.betaRef, null, JSON.stringify(options));
  assert.match(rejected.result.stderr, expectedError);
}

for (const options of [
  { branchRefDrift: true }, { invalidPullRef: true },
]) {
  const rejected = runScenario(options);
  assert.equal(rejected.result.status, 1);
  assert.equal(rejected.state.writes, 0);
}

const predecessorDrift = runScenario({ previousAssetBytesDrift: true });
assert.equal(predecessorDrift.result.status, 1);
assert.equal(predecessorDrift.state.writes, 0);

const historyDrift = runScenario({ historyDrift: true });
assert.equal(historyDrift.result.status, 1);
assert.equal(historyDrift.state.writes, 0);

const latestDrift = runScenario({ latestDrift: true });
assert.equal(latestDrift.result.status, 1);
assert.equal(latestDrift.state.writes, 0);

const candidateIdentityDrift = runScenario({ manifestCodeDrift: true });
assert.equal(candidateIdentityDrift.result.status, 1);
assert.equal(candidateIdentityDrift.state.writes, 0);

withScenario({ patchFails: true }, (current) => {
  const failed = current.execute();
  assert.equal(failed.result.status, 1);
  assert.equal(failed.state.writes, 2);
  assert.deepEqual(writeKinds(failed.state), ["create", "patch"]);
  assert.equal(failed.state.beta.draft, true);
  assert.equal(failed.state.betaRef, null);
  assert.match(failed.result.stderr, /remote may now contain a beta draft/u);
  assert.match(failed.result.stderr, /required local or GitHub check failed/u);

  current.mutateState((state) => { state.patchFails = false; });
  const recovered = current.execute({ resumeDraftId: 1000 });
  assert.equal(recovered.result.status, 0, recovered.result.stderr);
  assert.equal(recovered.state.writes, 3);
  assert.deepEqual(writeKinds(recovered.state), ["create", "patch", "patch"]);
  assert.equal(recovered.state.beta.draft, false);
  assert.equal(recovered.state.beta.prerelease, true);
  assert.equal(recovered.state.betaRef, SOURCE);
});

withScenario({ patchAppliedThenFails: true }, (current) => {
  const responseLost = current.execute();
  assert.equal(responseLost.result.status, 1);
  assert.equal(responseLost.state.writes, 2);
  assert.deepEqual(writeKinds(responseLost.state), ["create", "patch"]);
  assert.equal(responseLost.state.beta.draft, false);
  assert.equal(responseLost.state.beta.prerelease, true);
  assert.equal(responseLost.state.betaRef, SOURCE);
  assert.match(responseLost.result.stderr, /remote may now contain a beta draft/u);
  assert.match(responseLost.result.stderr, /required local or GitHub check failed/u);

  const recovered = current.execute({ resumeDraftId: 1000 });
  assert.equal(recovered.result.status, 0, recovered.result.stderr);
  assert.equal(recovered.state.writes, 2);
  assert.deepEqual(writeKinds(recovered.state), ["create", "patch"]);
  assert.equal(recovered.state.beta.draft, false);
  assert.equal(recovered.state.beta.prerelease, true);
  assert.equal(recovered.state.betaRef, SOURCE);
});

withScenario({ finalFlagDrift: true }, (current) => {
  const rejected = current.execute();
  assert.equal(rejected.result.status, 1);
  assert.equal(rejected.state.writes, 2);
  assert.deepEqual(writeKinds(rejected.state), ["create", "patch"]);
  assert.equal(rejected.state.beta.draft, false);
  assert.equal(rejected.state.beta.prerelease, false);
  assert.equal(rejected.state.betaRef, SOURCE);
  assert.match(rejected.result.stderr, /beta Release envelope mismatch/u);

  const retry = current.execute({ resumeDraftId: 1000 });
  assert.equal(retry.result.status, 1);
  assert.equal(retry.state.writes, 2);
  assert.deepEqual(writeKinds(retry.state), ["create", "patch"]);
});

for (const [options, writes] of [
  [{ boundaryReleaseDrift: true }, 1],
  [{ boundaryExtraPull: true }, 1],
  [{ boundaryAssetBytesDrift: true }, 1],
  [{ boundaryTagDrift: true }, 1],
]) {
  const rejected = runScenario(options);
  assert.equal(rejected.result.status, 1, JSON.stringify(options));
  assert.equal(rejected.state.writes, writes, JSON.stringify(options));
  assert.deepEqual(writeKinds(rejected.state), ["create"], JSON.stringify(options));
  assert.equal(rejected.state.beta.draft, true, JSON.stringify(options));
  assert.equal(rejected.state.betaRef, null, JSON.stringify(options));
  assert.match(rejected.result.stderr, /remote may now contain a beta draft/u);
}

process.stdout.write(`${JSON.stringify({
  ok: true, happyPath: true, preflightZeroWrite: true,
  exactStablePredecessorVerified: true, archivedBetaPreserved: true,
  checkpointRecoveryVerified: true, createFailureRecoveryVerified: true,
  patchFailureRecoveryVerified: true, patchResponseLossRecoveryVerified: true,
  completedCheckpointNoWrite: true,
  wrongResumeIdRejected: true, malformedResumedDraftRejected: true,
  draftMetadataDriftRejected: true, draftAssetBytesDriftRejected: true,
  draftExtraAssetRejected: true, draftReleaseFlagDriftRejected: true,
  draftLatestHijackRejected: true, finalFlagDriftRejected: true,
  historyDriftRejected: true, latestDriftRejected: true, invalidRefRejected: true,
  candidateIdentityRejected: true, exactRefClosureEnforced: true,
  multipleStableReleasesAccepted: true, branchesAndPullRefsReachable: true,
  singleWriterWindowEnforced: true, publicationBoundaryRaceRejected: true,
  boundaryAssetBytesDriftRejected: true, boundaryTagDriftRejected: true,
  stableLatestPreserved: true, stableLatestDriftRejected: true,
  scenarioCount,
})}\n`);
