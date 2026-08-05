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
const SIGNER = "cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc";
const BODY = "Public beta build of the autoform-kit framework. "
  + "No site-specific configuration is included.";
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
const a = process.argv.slice(2);
const blob = "eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee";
const identity = state.commitMetadataLeak ? state.privateEmail : state.publicNoreply;
const objects = new Map([
  [state.source, { type: "commit", bytes: Buffer.from("tree " + state.tree + "\\nauthor " + state.publicActor + " <" + identity + "> 0 +0000\\ncommitter " + state.publicActor + " <" + identity + "> 0 +0000\\n\\nfixture source\\n") }],
  [state.tree, { type: "tree", bytes: Buffer.from("fixture tree bytes\\n") }],
  [blob, { type: "blob", bytes: Buffer.from("fixture public blob\\n") }],
]);
if (a[0] === "rev-parse" && a[1] === "HEAD") process.stdout.write(state.source + "\n");
else if (a[0] === "rev-parse" && a[1].endsWith("^{tree}")) process.stdout.write(state.tree + "\n");
else if (a[0] === "branch" && a[1] === "--show-current") process.stdout.write("main\n");
else if (a[0] === "status" || a[0] === "diff") process.exit(0);
else if (a[0] === "merge-base" && a[1] === "--is-ancestor") process.exit(0);
else if (a[0] === "rev-list" && a[1] === "--objects") {
  process.stdout.write(state.source + "\n" + state.tree + "\n" + blob + " public.txt\n");
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
  for (let i = 0; i < 7; i += 1) lines.push((state.historyTagDrift && state.releaseReads >= 2 && i === 0
    ? "dddddddddddddddddddddddddddddddddddddddd" : state.source) + "\trefs/tags/v1.0." + i);
  const betaRefExists = state.betaTagOnly || (state.beta && (!state.beta.draft
    || state.betaWrongTarget || (state.boundaryTagDrift && state.releaseReads >= 4)));
  if (betaRefExists) lines.push((state.betaWrongTarget
    || (state.boundaryTagDrift && state.releaseReads >= 4)
    ? "dddddddddddddddddddddddddddddddddddddddd" : state.source) + "\trefs/tags/beta");
  if (state.extraBranch) lines.push(state.source + "\trefs/heads/extra");
  if (state.extraTag) lines.push(state.source + "\trefs/tags/extra");
  if (state.extraPull || (state.boundaryExtraPull && state.releaseReads >= 4)) {
    lines.push(state.source + "\trefs/pull/7/head");
  }
  if (state.extraOtherRef) lines.push(state.source + "\trefs/notes/audit");
  process.stdout.write(lines.join("\n") + "\n");
} else process.exit(7);
`;

const fakeAapt = String.raw`#!/usr/bin/env node
import fs from "node:fs";
const state = JSON.parse(fs.readFileSync(process.env.BETA_FIXTURE_STATE, "utf8"));
const file = process.argv.at(-1);
if (file.includes("previous.apk")) process.stdout.write("package: name='com.autoformkit.app' versionCode='8' versionName='1.0.7'\n");
else if (state.apkIdentityDrift) process.stdout.write("package: name='example.invalid' versionCode='9' versionName='1.0.8-beta.1'\n");
else process.stdout.write("package: name='com.autoformkit.app' versionCode='9' versionName='1.0.8-beta.1'\n");
`;

const fakeApksigner = `#!/usr/bin/env node
const fs = require("node:fs");
const state = JSON.parse(fs.readFileSync(process.env.BETA_FIXTURE_STATE, "utf8"));
const file = process.argv.at(-1);
const signer = state.signerDrift && !file.includes("previous.apk") ? "${"d".repeat(64)}" : "${SIGNER}";
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
const history = () => Array.from({ length: 7 }, (_, i) => {
  const tag = "v1.0." + i; const version = tag.slice(1);
  return { id: 100 + i, node_id: "R_" + i, tag_name: tag, target_commitish: state.source,
    name: "autoform-kit " + version, body: state.historyDrift && state.releaseReads >= 2 && i === 0 ? "drift" : ${JSON.stringify(HISTORY_BODY)},
    draft: false, prerelease: false, immutable: false,
    assets: [asset(200 + i * 2, "autoform-kit-" + version + ".apk", Buffer.from("h-apk-" + i)),
      asset(201 + i * 2, "update.json", Buffer.from("h-update-" + i))] };
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
  state.beta = { id: 900, node_id: "R_beta", tag_name: "beta", target_commitish: target,
    name: title, body: notes, draft: true, prerelease: true, immutable: false,
    assets: files.map((file, i) => { const bytes = fs.readFileSync(file); const out = asset(901 + i, file.split("/").at(-1), bytes); out.bytes = bytes.toString("base64"); return out; }) };
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
  const branches = [{ name: "main", commit: { sha: state.source }, protected: true }];
  if (state.extraBranch) branches.push({ name: "extra", commit: { sha: state.source }, protected: false });
  process.stdout.write(JSON.stringify([branches]));
} else if (endpoint.includes("/tags?")) {
  const tags = Array.from({ length: 7 }, (_, i) => ({ name: "v1.0." + i, commit: { sha:
    state.historyTagDrift && state.releaseReads >= 2 && i === 0 ? "d".repeat(40) : state.source } }));
  if (state.extraTag) tags.push({ name: "extra", commit: { sha: state.source } });
  const betaRefExists = state.betaTagOnly || (state.beta && (!state.beta.draft
    || state.betaWrongTarget || (state.boundaryTagDrift && state.releaseReads >= 4)));
  if (betaRefExists) tags.push({ name: "beta", commit: { sha:
    state.betaWrongTarget || (state.boundaryTagDrift && state.releaseReads >= 4)
      ? "d".repeat(40) : state.source } });
  process.stdout.write(JSON.stringify([tags]));
} else if (endpoint.includes("/releases?")) {
  state.releaseReads += 1;
  if (state.boundaryReleaseDrift && state.beta && state.releaseReads >= 4) {
    state.beta.name = "concurrent draft mutation";
  }
  save(); process.stdout.write(JSON.stringify([releases()]));
} else if (endpoint.endsWith("/releases/latest")) {
  state.latestReads += 1; save();
  if (state.stealLatest && state.beta) { process.stdout.write(JSON.stringify(state.beta)); process.exit(0); }
  if (state.latestDrift && state.latestReads >= 2) {
    process.stdout.write(JSON.stringify({ id: 800, node_id: "R_stable", tag_name: "v1.0.8",
      target_commitish: state.source, name: "autoform-kit 1.0.8", body: "stable", draft: false,
      prerelease: false, immutable: false, assets: [asset(801, "autoform-kit-1.0.8.apk", Buffer.from("stable"))] }));
    process.exit(0);
  }
  if (state.historicalLatest) {
    const latest = history()[0];
    if (state.latestHistoricalMetadataDrift) latest.name = "drift";
    if (state.latestHistoricalAssetDrift) latest.assets[0].digest = "sha256:" + "d".repeat(64);
    process.stdout.write(JSON.stringify(latest)); process.exit(0);
  }
  process.stderr.write("HTTP 404 Not Found\n"); process.exit(1);
} else if (endpoint.endsWith("/releases/tags/beta")) {
  if (!state.beta || state.beta.draft) { process.stderr.write("HTTP 404 Not Found\n"); process.exit(1); }
  process.stdout.write(JSON.stringify(state.beta));
} else if (endpoint.endsWith("/git/ref/tags/beta")) {
  const betaRefExists = state.betaTagOnly || (state.beta && (!state.beta.draft
    || state.betaWrongTarget || (state.boundaryTagDrift && state.releaseReads >= 4)));
  if (!betaRefExists) { process.stderr.write("HTTP 404 Not Found\n"); process.exit(1); }
  process.stdout.write(JSON.stringify({ ref: "refs/tags/beta", object: { type: "commit", sha:
    state.betaWrongTarget || (state.boundaryTagDrift && state.releaseReads >= 4)
      ? "d".repeat(40) : state.source } }));
} else if (/\/releases\/assets\/[0-9]+$/.test(endpoint)) {
  const id = Number(endpoint.split("/").at(-1)); const found = state.beta.assets.find((v) => v.id === id);
  const bytes = Buffer.from(found.bytes, "base64");
  state.assetDownloadReads += 1; save();
  const drift = id === 901 && (state.assetBytesDrift
    || (state.boundaryAssetBytesDrift && state.assetDownloadReads >= 4));
  process.stdout.write(drift ? Buffer.concat([bytes, Buffer.from("drift")]) : bytes);
} else if (/\/releases\/900$/.test(endpoint) && !a.includes("PATCH")) {
  if (!state.beta) { process.stderr.write("HTTP 404 Not Found\n"); process.exit(1); }
  state.releaseIdReads += 1; save(); process.stdout.write(JSON.stringify(state.beta));
} else if (/\/releases\/900$/.test(endpoint) && a.includes("PATCH")) {
  state.writes += 1; state.commands.push(a); const body = JSON.parse(fs.readFileSync(0, "utf8"));
  state.patchBody = body; save();
  if (state.patchFails) { process.stderr.write("fixture patch failure\n"); process.exit(1); }
  if (body.make_latest !== "false" || body.draft !== false || body.prerelease !== true) process.exit(9);
  state.beta = { ...state.beta, draft: false, prerelease: true, body: body.body, name: body.name,
    tag_name: body.tag_name, target_commitish: body.target_commitish }; save();
  if (state.finalFlagDrift) { state.beta.prerelease = false; save(); }
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

function fixture({ tagExists = false, existingDraft = false,
  historyDrift = false, historyTagDrift = false,
  historicalLatest = true, latestDrift = false, latestHistoricalMetadataDrift = false,
  latestHistoricalAssetDrift = false, duplicateHistoricalRelease = false,
  createFails = false, assetDrift = false,
  betaWrongTarget = false, apkIdentityDrift = false, signerDrift = false,
  sourceDrift = false, updateBytesDrift = false, releaseFlagDrift = false,
  extraAsset = false, stealLatest = false, assetBytesDrift = false,
  patchFails = false, finalFlagDrift = false, manifestCodeDrift = false,
  commitMetadataLeak = false, extraBranch = false, extraTag = false, extraPull = false,
  extraOtherRef = false,
  boundaryReleaseDrift = false, boundaryAssetBytesDrift = false,
  boundaryTagDrift = false, boundaryExtraPull = false } = {}) {
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
  write(stateFile, JSON.stringify({ source: SOURCE, tree: TREE, beta: null, betaTagOnly: tagExists,
    historyDrift, historyTagDrift, historicalLatest, latestDrift, latestHistoricalMetadataDrift,
    latestHistoricalAssetDrift, duplicateHistoricalRelease,
    createFails, assetDrift, betaWrongTarget,
    apkIdentityDrift, signerDrift, sourceDrift, releaseFlagDrift, extraAsset, stealLatest,
    assetBytesDrift, patchFails, finalFlagDrift, commitMetadataLeak, privateEmail: PRIVATE_EMAIL,
    publicActor: PUBLIC_ACTOR, publicNoreply: PUBLIC_NOREPLY,
    extraBranch, extraTag, extraPull, extraOtherRef, boundaryReleaseDrift,
    boundaryAssetBytesDrift,
    boundaryTagDrift, boundaryExtraPull,
    releaseReads: 0, releaseIdReads: 0, latestReads: 0, assetDownloadReads: 0,
    writes: 0, commands: [] }), 0o600);
  const candidateDir = path.join(root, "dist", "release-candidates", "v1.0.8-beta.1");
  const apk = path.join(candidateDir, "autoform-kit-1.0.8-beta.1.apk");
  const previous = path.join(root, "private", "previous.apk");
  const notes = path.join(candidateDir, "release-notes.txt");
  const update = path.join(candidateDir, "update.json");
  write(apk, "fixture-beta-apk\n", 0o644); write(previous, "fixture-previous-apk\n", 0o600);
  write(notes, `${BODY}\n`, 0o644);
  const apkSha = sha256(fs.readFileSync(apk));
  write(update, `${JSON.stringify({ packageName: "com.autoformkit.app", versionCode: 9,
    versionName: "1.0.8-beta.1", apkAsset: "autoform-kit-1.0.8-beta.1.apk",
    sha256: apkSha, notes: BODY }, null, 2)}\n`, 0o644);
  const tree = scannerReport(root, stateFile, "git-tree", SOURCE);
  const worktree = scannerReport(root, stateFile, "worktree", "");
  const apkAudit = scannerReport(root, stateFile, "apk", apk);
  const updateAudit = scannerReport(root, stateFile, "file", update);
  const notesAudit = scannerReport(root, stateFile, "file", notes);
  const source = verifierReport(root, apk);
  const manifest = {
    schemaVersion: 2, tag: "v1.0.8-beta.1",
    source: { branch: "main", commit: SOURCE, workingTreeClean: true },
    app: { packageName: "com.autoformkit.app", versionCode: manifestCodeDrift ? 10 : 9,
      versionName: "1.0.8-beta.1", signerSha256: SIGNER },
    previousApk: { packageName: "com.autoformkit.app", versionCode: 8, versionName: "1.0.7",
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
  if (existingDraft) {
    const state = JSON.parse(fs.readFileSync(stateFile, "utf8"));
    const releaseAsset = (id, filename) => {
      const bytes = fs.readFileSync(filename);
      return {
        id, node_id: `A_${id}`, name: path.basename(filename), label: null, state: "uploaded",
        size: bytes.length,
        content_type: filename.endsWith(".apk")
          ? "application/vnd.android.package-archive" : "application/json",
        digest: `sha256:${sha256(bytes)}`,
        url: `https://api.github.com/repos/example/autoform-kit/releases/assets/${id}`,
        browser_download_url: `https://github.com/example/autoform-kit/releases/download/beta/${path.basename(filename)}`,
        bytes: bytes.toString("base64"),
      };
    };
    state.beta = {
      id: 900, node_id: "R_beta", tag_name: "beta", target_commitish: SOURCE,
      name: "autoform-kit 1.0.8-beta.1", body: `${BODY}\n`, draft: true,
      prerelease: !releaseFlagDrift, immutable: false,
      assets: [releaseAsset(901, apk), releaseAsset(902, update), releaseAsset(903, manifestPath)],
    };
    if (assetDrift) state.beta.assets[0].digest = `sha256:${"d".repeat(64)}`;
    if (extraAsset) state.beta.assets.push({
      ...releaseAsset(999, update), name: "extra.json", node_id: "A_999",
    });
    fs.writeFileSync(stateFile, JSON.stringify(state));
    fs.chmodSync(stateFile, 0o600);
  }
  if (updateBytesDrift) fs.appendFileSync(update, "drift\n");
  if (sourceDrift) {
    const state = JSON.parse(fs.readFileSync(stateFile, "utf8"));
    state.source = "dddddddddddddddddddddddddddddddddddddddd";
    fs.writeFileSync(stateFile, JSON.stringify(state)); fs.chmodSync(stateFile, 0o600);
  }
  return { root, stateFile, manifestPath, previous };
}

function runScenario(options, { confirmSingleWriter = true, resumeDraftId } = {}) {
  const f = fixture(options);
  const env = { ...process.env,
    PATH: `${path.join(f.root, "bin")}:${process.env.PATH}`,
    BETA_FIXTURE_STATE: f.stateFile,
    AAPT: path.join(f.root, "bin", "aapt"), APKSIGNER: path.join(f.root, "bin", "apksigner"),
    GRADLE_USER_HOME: path.join(f.root, "gradle") };
  if (confirmSingleWriter) {
    env.AUTOFORM_BETA_SINGLE_WRITER_WINDOW = "EXCLUSIVE_BETA_RELEASE_WRITER_CONFIRMED";
  } else {
    delete env.AUTOFORM_BETA_SINGLE_WRITER_WINDOW;
  }
  const publisherArgs = [path.join(f.root, "tools", "publish-beta-release.mjs"),
    "--candidate", f.manifestPath, "--previous-apk", f.previous,
    "--private-wordlist", path.join(f.root, "private", "wordlist.txt")];
  if (resumeDraftId !== undefined) publisherArgs.push("--resume-draft", String(resumeDraftId));
  const result = spawnSync(process.execPath, publisherArgs, {
    cwd: f.root, encoding: "utf8", env,
  });
  const state = JSON.parse(fs.readFileSync(f.stateFile, "utf8"));
  fs.rmSync(f.root, { recursive: true, force: true });
  return { result, state };
}

const happy = runScenario();
assert.equal(happy.result.status, 0, happy.result.stderr);
assert.equal(happy.state.writes, 2);
assert.equal(happy.state.beta.draft, false);
assert.equal(happy.state.beta.prerelease, true);
assert.equal(happy.state.beta.tag_name, "beta");
assert.equal(happy.state.commands[0].includes("--latest=false"), true);
assert.equal(happy.state.commands[0].includes("--draft"), true);
assert.equal(happy.state.commands[0].includes("--prerelease"), true);
assert.equal(happy.state.patchBody.make_latest, "false");
assert.equal(happy.state.assetDownloadReads, 9);
assert.equal(happy.state.releaseIdReads, 3);
assert.equal(fs.readFileSync(PUBLISHER, "utf8").includes("publish-release.sh"), false);

const resumed = runScenario({ existingDraft: true }, { resumeDraftId: 900 });
assert.equal(resumed.result.status, 0, resumed.result.stderr);
assert.equal(resumed.state.writes, 1);
assert.equal(resumed.state.commands.some((command) => command[0] === "release"), false);
assert.equal(resumed.state.beta.draft, false);
assert.equal(resumed.state.beta.prerelease, true);
assert.equal(resumed.state.assetDownloadReads, 15);
assert.equal(resumed.state.releaseIdReads, 5);

const existingDraftWithoutResume = runScenario({ existingDraft: true });
assert.equal(existingDraftWithoutResume.result.status, 1);
assert.equal(existingDraftWithoutResume.state.writes, 0);

const wrongResumeIdentity = runScenario({ existingDraft: true }, { resumeDraftId: 901 });
assert.equal(wrongResumeIdentity.result.status, 1);
assert.equal(wrongResumeIdentity.state.writes, 0);

const mismatchedResumeDraft = runScenario(
  { existingDraft: true, releaseFlagDrift: true }, { resumeDraftId: 900 },
);
assert.equal(mismatchedResumeDraft.result.status, 1);
assert.equal(mismatchedResumeDraft.state.writes, 0);

const noExistingLatest = runScenario({ historicalLatest: false });
assert.equal(noExistingLatest.result.status, 0, noExistingLatest.result.stderr);
assert.equal(noExistingLatest.state.writes, 2);

const missingSingleWriter = runScenario({}, { confirmSingleWriter: false });
assert.equal(missingSingleWriter.result.status, 1);
assert.equal(missingSingleWriter.state.writes, 0);
assert.match(missingSingleWriter.result.stderr, /single-writer release window/u);

const metadataLeak = runScenario({ commitMetadataLeak: true });
assert.equal(metadataLeak.result.status, 1);
assert.equal(metadataLeak.state.writes, 0);

for (const options of [
  { extraBranch: true }, { extraTag: true }, { extraPull: true }, { extraOtherRef: true },
]) {
  const rejected = runScenario(options);
  assert.equal(rejected.result.status, 1);
  assert.equal(rejected.state.writes, 0);
}

const existingTag = runScenario({ tagExists: true });
assert.equal(existingTag.result.status, 1);
assert.equal(existingTag.state.writes, 0);

const historyDrift = runScenario({ historyDrift: true });
assert.equal(historyDrift.result.status, 1);
assert.equal(historyDrift.state.writes, 0);

const historyTagDrift = runScenario({ historyTagDrift: true });
assert.equal(historyTagDrift.result.status, 1);
assert.equal(historyTagDrift.state.writes, 0);

const latestDrift = runScenario({ latestDrift: true });
assert.equal(latestDrift.result.status, 1);
assert.equal(latestDrift.state.writes, 0);

for (const options of [
  { latestHistoricalMetadataDrift: true },
  { latestHistoricalAssetDrift: true },
  { duplicateHistoricalRelease: true },
]) {
  const rejected = runScenario(options);
  assert.equal(rejected.result.status, 1);
  assert.equal(rejected.state.writes, 0);
}

const partialCreate = runScenario({ createFails: true });
assert.equal(partialCreate.result.status, 1);
assert.equal(partialCreate.state.writes, 1);
assert.match(partialCreate.result.stderr, /remote may now contain a beta draft/u);

const assetDrift = runScenario({ assetDrift: true });
assert.equal(assetDrift.result.status, 1);
assert.equal(assetDrift.state.writes, 1);
assert.match(assetDrift.result.stderr, /remote may now contain a beta draft/u);

const betaTargetDrift = runScenario({ betaWrongTarget: true });
assert.equal(betaTargetDrift.result.status, 1);
assert.equal(betaTargetDrift.state.writes, 1);
assert.match(betaTargetDrift.result.stderr, /remote may now contain a beta draft/u);

for (const options of [
  { apkIdentityDrift: true },
  { signerDrift: true },
  { sourceDrift: true },
  { updateBytesDrift: true },
  { manifestCodeDrift: true },
]) {
  const rejected = runScenario(options);
  assert.equal(rejected.result.status, 1);
  assert.equal(rejected.state.writes, 0);
}

for (const options of [
  { releaseFlagDrift: true },
  { extraAsset: true },
  { stealLatest: true },
  { assetBytesDrift: true },
]) {
  const rejected = runScenario(options);
  assert.equal(rejected.result.status, 1);
  assert.equal(rejected.state.writes, 1);
  assert.match(rejected.result.stderr, /remote may now contain a beta draft/u);
}

for (const options of [
  { boundaryReleaseDrift: true },
  { boundaryAssetBytesDrift: true },
  { boundaryTagDrift: true },
  { boundaryExtraPull: true },
]) {
  const rejected = runScenario(options);
  assert.equal(rejected.result.status, 1);
  assert.equal(rejected.state.writes, 1);
  assert.match(rejected.result.stderr, /remote may now contain a beta draft/u);
}

const patchFailure = runScenario({ patchFails: true });
assert.equal(patchFailure.result.status, 1);
assert.equal(patchFailure.state.writes, 2);
assert.match(patchFailure.result.stderr, /remote may now contain a beta draft/u);

const finalFlagDrift = runScenario({ finalFlagDrift: true });
assert.equal(finalFlagDrift.result.status, 1);
assert.equal(finalFlagDrift.state.writes, 2);
assert.match(finalFlagDrift.result.stderr, /remote may now contain a beta draft/u);

process.stdout.write(`${JSON.stringify({
  ok: true, happyPath: true, preflightZeroWrite: true, partialFailureFrozen: true,
  historyDriftRejected: true, latestDriftRejected: true, tagDriftRejected: true,
  assetDriftRejected: true, candidateIdentityRejected: true, sourceDriftRejected: true,
  commitMetadataRejected: true, exactRefClosureEnforced: true,
  singleWriterWindowEnforced: true, publicationBoundaryRaceRejected: true,
  historicalLatestPreserved: true, historicalLatestDriftRejected: true,
  releaseFlagsRejected: true, patchFailureFrozen: true, scenarioCount: 33,
})}\n`);
