#!/usr/bin/env node

import assert from "node:assert/strict";
import { spawnSync } from "node:child_process";
import crypto from "node:crypto";
import {
  after,
  before,
  test,
} from "node:test";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { fileURLToPath } from "node:url";

import {
  canonicalJson,
  createHistoricalInventory,
  digest,
  HISTORICAL_BODY,
  HISTORICAL_PUBLICATION_MODE,
  HISTORICAL_TAGS,
  PRIVATE_HISTORY_AUDIT_KIND,
  PRIVATE_HISTORY_ATTESTATION_KIND,
  privateHistoryAttestationIdentity,
  privateHistoryAuditIdentity,
  privateWordlistSetIdentity,
} from "./historical-release-contract.mjs";
import {
  githubPublicMetadataBinding,
  normalizeGithubBranchesAndTags,
  normalizeGithubReleases,
  normalizeRemoteRefs,
  normalizedAuditBytes,
} from "./normalize-github-releases-for-audit.mjs";

const HERE = path.dirname(fileURLToPath(import.meta.url));
const SOURCE_ROOT = path.resolve(HERE, "..");
const OWNER = "fixture-owner";
const REPOSITORY = "autoform-kit";
const SLUG = `${OWNER}/${REPOSITORY}`;
const PACKAGE_NAME = "com.example.autoform";
const SIGNER_SHA256 = "f".repeat(64);
const SOURCE_REPORT_SHA256 = digest(Buffer.from("fixture source report", "utf8"));
const PRIVATE_MARKER = "never-present-fixture-secret";
const CREATED_AT_KEY = ["created", "at"].join("_");
const UPDATED_AT_KEY = ["updated", "at"].join("_");
const ALL_TAGS = [...HISTORICAL_TAGS, "v1.0.7"];

let harness;

function clone(value) {
  return JSON.parse(JSON.stringify(value));
}

function hashFile(filename) {
  return digest(fs.readFileSync(filename));
}

function writePrivate(filename, bytes) {
  fs.writeFileSync(filename, bytes, { mode: 0o600 });
  fs.chmodSync(filename, 0o600);
}

function command(commandName, args, options = {}) {
  const result = spawnSync(commandName, args, {
    encoding: "utf8",
    maxBuffer: 64 * 1024 * 1024,
    ...options,
  });
  if (result.status !== 0) {
    throw new Error(`fixture command failed: ${commandName} ${args.join(" ")}\n${result.stderr}`);
  }
  return result.stdout.trim();
}

function commandBytes(commandName, args, options = {}) {
  const result = spawnSync(commandName, args, {
    encoding: null,
    maxBuffer: 64 * 1024 * 1024,
    ...options,
  });
  if (result.status !== 0 || !Buffer.isBuffer(result.stdout)) {
    throw new Error("fixture binary command failed");
  }
  return result.stdout;
}

function githubUser(login = OWNER, id = 101) {
  return {
    avatar_url: `https://avatars.githubusercontent.com/u/${id}?v=4`,
    events_url: `https://api.github.com/users/${login}/events{/privacy}`,
    followers_url: `https://api.github.com/users/${login}/followers`,
    following_url: `https://api.github.com/users/${login}/following{/other_user}`,
    gists_url: `https://api.github.com/users/${login}/gists{/gist_id}`,
    gravatar_id: "",
    html_url: `https://github.com/${login}`,
    id,
    login,
    node_id: `U_fixture_${id}`,
    organizations_url: `https://api.github.com/users/${login}/orgs`,
    received_events_url: `https://api.github.com/users/${login}/received_events`,
    repos_url: `https://api.github.com/users/${login}/repos`,
    site_admin: false,
    starred_url: `https://api.github.com/users/${login}/starred{/owner}{/repo}`,
    subscriptions_url: `https://api.github.com/users/${login}/subscriptions`,
    type: "User",
    url: `https://api.github.com/users/${login}`,
    user_view_type: "public",
  };
}

function githubAsset({ bytes, contentType, id, name, tag }) {
  return {
    browser_download_url: `https://github.com/${SLUG}/releases/download/${tag}/${name}`,
    content_type: contentType,
    [CREATED_AT_KEY]: "2026-07-30T01:02:03Z",
    digest: `sha256:${digest(bytes)}`,
    download_count: 0,
    id,
    label: null,
    name,
    node_id: `RA_fixture_${id}`,
    size: bytes.length,
    state: "uploaded",
    [UPDATED_AT_KEY]: "2026-07-30T01:02:04Z",
    uploader: githubUser(),
    url: `https://api.github.com/repos/${SLUG}/releases/assets/${id}`,
  };
}

function githubRelease({ assets, body, draft, id, name, tag, target }) {
  return {
    assets,
    assets_url: `https://api.github.com/repos/${SLUG}/releases/${id}/assets`,
    author: githubUser(),
    body,
    [CREATED_AT_KEY]: "2026-07-30T01:02:03Z",
    draft,
    html_url: `https://github.com/${SLUG}/releases/tag/${tag}`,
    id,
    immutable: false,
    name,
    node_id: `R_fixture_${id}`,
    prerelease: false,
    published_at: draft ? null : "2026-07-30T01:02:05Z",
    tag_name: tag,
    tarball_url: `https://api.github.com/repos/${SLUG}/tarball/${tag}`,
    target_commitish: target,
    [UPDATED_AT_KEY]: "2026-07-30T01:02:05Z",
    upload_url: `https://uploads.github.com/repos/${SLUG}/releases/${id}/assets{?name,label}`,
    url: `https://api.github.com/repos/${SLUG}/releases/${id}`,
    zipball_url: `https://api.github.com/repos/${SLUG}/zipball/${tag}`,
  };
}

function fakeScannerSource() {
  return `#!/usr/bin/env node
import crypto from "node:crypto";
import fs from "node:fs";
import { fileURLToPath } from "node:url";

if (process.env.FAKE_TEST_MODE === "scanner-failure") process.exit(7);
const args = process.argv.slice(2);
const own = fileURLToPath(import.meta.url);
const sha = (bytes) => crypto.createHash("sha256").update(bytes).digest("hex");
const canonical = (value) => {
  if (Array.isArray(value)) return value.map(canonical);
  if (value !== null && typeof value === "object") {
    return Object.fromEntries(Object.keys(value).sort().map((key) => [key, canonical(value[key])]));
  }
  return value;
};
const canonicalJson = (value) => JSON.stringify(canonical(value));
let inputSha = "0".repeat(64);
let inputBytes = Buffer.alloc(0);
let inputPath = "";
for (const flag of ["--file", "--apk"]) {
  const index = args.indexOf(flag);
  if (index >= 0) {
    inputPath = args[index + 1];
    inputBytes = fs.readFileSync(inputPath);
    inputSha = sha(inputBytes);
  }
}
if (inputPath.endsWith("-api-refs.json")
    && (fs.statSync(inputPath).mode & 0o777) !== 0o600) process.exit(8);
const wordlists = [];
for (let index = 0; index < args.length; index += 1) {
  if (args[index] === "--private-wordlist") wordlists.push(args[index + 1]);
}
const terms = wordlists.flatMap((filename) => JSON.parse(fs.readFileSync(filename, "utf8")));
const findings = terms.filter((term) => inputBytes.includes(Buffer.from(term, "utf8")))
  .map(() => ({ kind: "private-wordlist", location: "redacted" }));
const base = {
  schemaVersion: 1,
  scannerSha256: sha(fs.readFileSync(own)),
  input: { sha256: inputSha },
  privatePolicy: { applied: true, wordlistCount: wordlists.length },
  summary: { passed: findings.length === 0, findingCount: findings.length },
  findings,
};
const report = { ...base, reportSha256: sha(Buffer.from(canonicalJson(base), "utf8")) };
process.stdout.write(canonicalJson(report) + "\\n");
if (findings.length) process.exitCode = 1;
`;
}

function fakeSourceVerifierSource() {
  return `#!/usr/bin/env node
import crypto from "node:crypto";
import fs from "node:fs";
import { fileURLToPath } from "node:url";

const args = process.argv.slice(2);
const value = (flag) => args[args.indexOf(flag) + 1];
const sha = (bytes) => crypto.createHash("sha256").update(bytes).digest("hex");
const own = fileURLToPath(import.meta.url);
process.stdout.write(JSON.stringify({
  passed: true,
  apkSha256: sha(fs.readFileSync(value("--apk"))),
  policySha256: sha(fs.readFileSync(value("--policy"))),
  profileId: process.env.FAKE_PROFILE_ID,
  verifierSha256: sha(fs.readFileSync(own)),
  reportSha256: process.env.FAKE_SOURCE_REPORT_SHA256,
}) + "\\n");
`;
}

function fakeAaptSource() {
  return `#!/usr/bin/env node
const filename = process.argv.at(-1) || "";
const match = /autoform-kit-(1\\.0\\.([0-6]))\\.apk$/u.exec(filename);
if (!match) process.exit(2);
process.stdout.write("package: name='${PACKAGE_NAME}' versionCode='" + (Number(match[2]) + 1)
  + "' versionName='" + match[1] + "'\\n");
`;
}

function fakeApksignerSource() {
  return `#!/usr/bin/env node
process.stdout.write("Signer #1 certificate SHA-256 digest: ${SIGNER_SHA256}\\n");
`;
}

function fakeGitSource() {
  return `#!/usr/bin/env node
import fs from "node:fs";
import { spawnSync } from "node:child_process";

const args = process.argv.slice(2);
if (args[0] === "ls-remote") {
  const state = JSON.parse(fs.readFileSync(process.env.FAKE_GH_STATE, "utf8"));
  const lines = [];
  if (args.length === 2 && args[1] === "origin") {
    state.remoteRefReads += 1;
    fs.writeFileSync(process.env.FAKE_GH_STATE, JSON.stringify(state), { mode: 0o600 });
    lines.push(state.commit + "\\tHEAD");
    lines.push(state.commit + "\\trefs/heads/main");
    for (const tag of state.allTags) {
      lines.push(state.tagCommits[tag] + "\\trefs/tags/" + tag);
    }
    for (const [ref, originalOid] of Object.entries(state.pullRefs)) {
      const mutatePull = (state.mode === "pull-ref-second-capture-mutation"
          && state.remoteRefReads >= 2)
        || (state.mode === "pull-ref-final-mutation" && state.remoteRefReads >= 3);
      const oid = mutatePull && ref === "refs/pull/1/head"
        ? "d".repeat(40) : originalOid;
      lines.push(oid + "\\t" + ref);
    }
  } else {
    for (const selector of args.slice(2)) {
      if (!selector.startsWith("refs/tags/") || selector.endsWith("^{}")) continue;
      const tag = selector.slice("refs/tags/".length);
      if (state.tagCommits[tag]) lines.push(state.tagCommits[tag] + "\\t" + selector);
    }
  }
  if (lines.length) process.stdout.write(lines.join("\\n") + "\\n");
  process.exit(0);
}
const delegated = [...args];
if (delegated[0] === "-C" && delegated.includes("fetch")) {
  const remoteIndex = delegated.findIndex((item) =>
    item.startsWith("https://github.com/") || item.startsWith("git@github.com:"));
  if (remoteIndex >= 0) delegated[remoteIndex] = process.env.FAKE_SOURCE_REPOSITORY;
}
const result = spawnSync(process.env.REAL_GIT, delegated, { stdio: "inherit" });
process.exit(result.status === null ? 127 : result.status);
`;
}

function fakeGhSource() {
  return `#!/usr/bin/env node
import fs from "node:fs";
import path from "node:path";

const args = process.argv.slice(2);
const stateFile = process.env.FAKE_GH_STATE;
const logFile = process.env.FAKE_GH_LOG;
const load = () => JSON.parse(fs.readFileSync(stateFile, "utf8"));
const save = (state) => fs.writeFileSync(stateFile, JSON.stringify(state), { mode: 0o600 });
const json = (value) => process.stdout.write(JSON.stringify(value) + "\\n");
const notFound = () => {
  process.stderr.write("HTTP 404: Not Found\\n");
  process.exit(1);
};
const option = (name) => {
  const index = args.indexOf(name);
  return index < 0 ? "" : args[index + 1];
};
const logSideEffect = (kind) => {
  fs.appendFileSync(logFile, JSON.stringify({ kind, args }) + "\\n");
};
if (args[0] === "--version") {
  process.stdout.write("gh version fixture\\n");
  process.exit(0);
}
if (args[0] === "auth" && args[1] === "status") process.exit(0);
let state = load();
if (args[0] === "api") {
  const endpoint = [...args].reverse().find((item) => item.startsWith("repos/"));
  if (option("--method") === "PATCH") {
    const releasePrefix = "repos/" + state.slug + "/releases/";
    if (!endpoint || !endpoint.startsWith(releasePrefix)) notFound();
    const id = Number(endpoint.slice(releasePrefix.length));
    const release = state.releases.find((item) => item.id === id);
    if (!release) notFound();
    const input = JSON.parse(fs.readFileSync(option("--input"), "utf8"));
    if (input.make_latest !== "false") {
      process.stderr.write("make_latest was not explicitly false\\n");
      process.exit(4);
    }
    logSideEffect("patch");
    release.body = input.body;
    release.draft = input.draft;
    release.name = input.name;
    release.prerelease = input.prerelease;
    release.tag_name = input.tag_name;
    release.target_commitish = input.target_commitish;
    if (!release.draft) release.published_at = "2026-07-30T01:03:05Z";
    if (state.mode === "preexisting-release-final-mutation") {
      const preexisting = state.releases.find((item) => item.tag_name === "v0.9.9");
      if (preexisting) preexisting.name = "changed generic historical title";
    }
    save(state);
    json(release);
    process.exit(0);
  }
  if (endpoint === "repos/" + state.slug) {
    state.repoReads += 1;
    save(state);
    const repository = JSON.parse(JSON.stringify(state.repository));
    if (state.mode === "remote-second-capture-mutation" && state.repoReads >= 2) {
      repository.description = "Generic framework metadata changed";
    }
    json(repository);
    process.exit(0);
  }
  if (endpoint === "repos/" + state.slug + "/releases/latest") {
    if (!state.latestTag) notFound();
    const release = state.releases.find((item) => item.tag_name === state.latestTag);
    if (!release) notFound();
    json(release);
    process.exit(0);
  }
  if (endpoint === "repos/" + state.slug + "/branches?per_page=100") {
    state.branchReads += 1;
    save(state);
    let contexts = [];
    if (state.mode === "sensitive-status-context") {
      contexts = [state.privateMarker];
    } else if (state.mode === "status-context-second-capture-mutation"
        && state.branchReads >= 2) {
      contexts = ["changed-ci-context"];
    }
    const branch = {
      commit: {
        sha: state.commit,
        url: "https://api.github.com/repos/" + state.slug + "/commits/" + state.commit,
      },
      name: "main",
      protected: false,
      protection: {
        enabled: false,
        required_status_checks: {
          checks: contexts.map((context) => ({ app_id: 7, context })),
          contexts,
          enforcement_level: "off",
        },
      },
      protection_url: "https://api.github.com/repos/" + state.slug
        + "/branches/main/protection",
    };
    json([branch]);
    process.exit(0);
  }
  if (endpoint === "repos/" + state.slug + "/tags?per_page=100") {
    json([state.allTags.map((tag, index) => ({
      commit: {
        sha: state.tagCommits[tag],
        url: "https://api.github.com/repos/" + state.slug + "/commits/"
          + state.tagCommits[tag],
      },
      name: tag,
      node_id: "T_fixture_" + (index + 1),
      tarball_url: "https://api.github.com/repos/" + state.slug
        + "/tarball/refs/tags/" + tag,
      zipball_url: "https://api.github.com/repos/" + state.slug
        + "/zipball/refs/tags/" + tag,
    }))]);
    process.exit(0);
  }
  if (endpoint === "repos/" + state.slug + "/releases?per_page=100") {
    json([state.releases]);
    process.exit(0);
  }
  const tagPrefix = "repos/" + state.slug + "/releases/tags/";
  if (endpoint && endpoint.startsWith(tagPrefix)) {
    const tag = endpoint.slice(tagPrefix.length);
    const release = state.releases.find((item) => item.tag_name === tag);
    if (!release) notFound();
    json(release);
    process.exit(0);
  }
  const assetPrefix = "repos/" + state.slug + "/releases/assets/";
  if (endpoint && endpoint.startsWith(assetPrefix)) {
    const id = endpoint.slice(assetPrefix.length);
    const encoded = state.assetBytes[id];
    if (!encoded) notFound();
    let bytes = Buffer.from(encoded, "base64");
    if (state.mode === "download-asset-tamper") bytes = Buffer.concat([bytes, Buffer.from("x")]);
    process.stdout.write(bytes);
    process.exit(0);
  }
  process.stderr.write("unsupported fake gh api request\\n");
  process.exit(2);
}
if (args[0] === "release" && args[1] === "create") {
  logSideEffect("create");
  const firstOption = args.findIndex((item, index) => index >= 3 && item.startsWith("--"));
  const uploadPaths = args.slice(3, firstOption);
  const draft = JSON.parse(JSON.stringify(state.draftRelease));
  draft.name = option("--title");
  draft.body = fs.readFileSync(option("--notes-file"), "utf8");
  draft.target_commitish = option("--target");
  if (state.mode === "draft-sensitive-metadata") {
    draft.assets[0].label = state.privateMarker;
  }
  for (const asset of draft.assets) {
    const upload = uploadPaths.find((filename) => path.basename(filename) === asset.name);
    if (!upload) {
      process.stderr.write("missing fixture upload\\n");
      process.exit(3);
    }
    state.assetBytes[String(asset.id)] = fs.readFileSync(upload).toString("base64");
  }
  state.releases.push(draft);
  if (state.mode === "bad-latest-after-create") state.latestTag = draft.tag_name;
  save(state);
  process.stdout.write(draft.html_url + "\\n");
  process.exit(0);
}
process.stderr.write("unsupported fake gh command\\n");
process.exit(2);
`;
}

function makeExecutable(filename, source) {
  fs.writeFileSync(filename, source, { mode: 0o755 });
  fs.chmodSync(filename, 0o755);
}

function createHarness() {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), "autoform-historical-publisher-test-"));
  const repository = path.join(root, "repository");
  const tools = path.join(repository, "tools");
  const fakeBin = path.join(root, "fake-bin");
  fs.mkdirSync(tools, { recursive: true });
  fs.mkdirSync(fakeBin, { recursive: true });
  for (const name of [
    "publish-historical-release.mjs",
    "historical-release-contract.mjs",
    "normalize-github-releases-for-audit.mjs",
  ]) {
    fs.copyFileSync(path.join(SOURCE_ROOT, "tools", name), path.join(tools, name));
  }
  makeExecutable(path.join(tools, "public-surface-audit.mjs"), fakeScannerSource());
  makeExecutable(path.join(tools, "verify-apk-third-party-sources.mjs"),
    fakeSourceVerifierSource());
  fs.writeFileSync(path.join(tools, "apk-third-party-components.json"),
    `${JSON.stringify({ fixture: true })}\n`);
  fs.writeFileSync(path.join(tools, "android-runtime-dependencies.lock.json"),
    `${JSON.stringify({ fixture: true })}\n`);
  makeExecutable(path.join(fakeBin, "git"), fakeGitSource());
  makeExecutable(path.join(fakeBin, "gh"), fakeGhSource());
  makeExecutable(path.join(fakeBin, "aapt"), fakeAaptSource());
  makeExecutable(path.join(fakeBin, "apksigner"), fakeApksignerSource());

  const whichGit = command("which", ["git"]);
  command(whichGit, ["init", "-b", "main", repository]);
  command(whichGit, ["config", "user.email", "fixture@example.invalid"], { cwd: repository });
  command(whichGit, ["config", "user.name", "Fixture"], { cwd: repository });
  command(whichGit, ["add", "."], { cwd: repository });
  command(whichGit, ["commit", "-m", "fixture source"], { cwd: repository });
  for (const tag of ALL_TAGS) command(whichGit, ["tag", tag], { cwd: repository });
  const committed = command(whichGit, ["rev-parse", "HEAD"], { cwd: repository });
  command(whichGit, ["update-ref", "refs/pull/1/head", committed], { cwd: repository });
  command(whichGit, ["update-ref", "refs/pull/1/merge", committed], { cwd: repository });
  command(whichGit, ["remote", "add", "origin", `https://github.com/${SLUG}.git`],
    { cwd: repository });
  const commit = command(whichGit, ["rev-parse", "HEAD"], { cwd: repository });
  const tree = command(whichGit, ["rev-parse", "HEAD^{tree}"], { cwd: repository });
  const dirtyBlob = command(whichGit, ["hash-object", "-w", "--stdin"], {
    cwd: repository,
    input: PRIVATE_MARKER,
  });
  const dirtyTree = command(whichGit, ["mktree"], {
    cwd: repository,
    input: `100644 blob ${dirtyBlob}\tdirty-pull-fixture.txt\n`,
  });
  const dirtyPullCommit = command(whichGit,
    ["commit-tree", dirtyTree, "-p", commit, "-m", "fixture pull history"], {
      cwd: repository,
    });
  const wordlist = path.join(root, "private-wordlist.json");
  writePrivate(wordlist, Buffer.from(`${JSON.stringify([PRIVATE_MARKER])}\n`));
  return {
    aapt: path.join(fakeBin, "aapt"),
    apksigner: path.join(fakeBin, "apksigner"),
    commit,
    dirtyPullCommit,
    fakeBin,
    profileId: "release-v1.0.0",
    publisher: path.join(tools, "publish-historical-release.mjs"),
    realGit: whichGit,
    repository,
    root,
    tree,
    wordlist,
  };
}

function createInventory(titleForTag = (tag) => `autoform-kit ${tag.slice(1)}`) {
  const sourceReleases = HISTORICAL_TAGS.map((tag, sequence) => {
    const apkName = `autoform-kit-${tag.slice(1)}.apk`;
    const title = titleForTag(tag);
    return {
      assets: [
        {
          name: apkName,
          sha256: digest(Buffer.from(`original apk ${tag}`, "utf8")),
          size: 100 + sequence,
        },
        {
          name: "update.json",
          sha256: digest(Buffer.from(`original update ${tag}`, "utf8")),
          size: 40 + sequence,
        },
      ].sort((left, right) => left.name.localeCompare(right.name)),
      draft: false,
      prerelease: false,
      tagName: tag,
      titleLength: [...title].length,
      titleSha256: digest(Buffer.from(title, "utf8")),
    };
  });
  const sourceInventory = {
    schemaVersion: 1,
    kind: "public-rewrite-inventory",
    tagCount: 7,
    releaseCount: 7,
    assetCount: 14,
    stableIdentitySha256: digest(Buffer.from("stable identity", "utf8")),
    inventorySha256: digest(Buffer.from("source inventory", "utf8")),
    sourceBindings: {
      gitTagRefListingSha256: digest(Buffer.from("source tag refs", "utf8")),
      releaseMetadataSha256: digest(Buffer.from("source release metadata", "utf8")),
    },
    releases: sourceReleases,
  };
  const apkIdentities = HISTORICAL_TAGS.map((tag, sequence) => ({
    tag,
    packageName: PACKAGE_NAME,
    versionCode: sequence + 1,
    versionName: tag.slice(1),
    signerSha256: SIGNER_SHA256,
  }));
  const updateIdentities = HISTORICAL_TAGS.map((tag, sequence) => ({
    tag,
    packageName: PACKAGE_NAME,
    versionCode: sequence + 1,
    versionName: tag.slice(1),
    apkAsset: `autoform-kit-${tag.slice(1)}.apk`,
    apkSha256: sourceReleases[sequence].assets.find((asset) => asset.name.endsWith(".apk"))
      .sha256,
    notesLength: 10,
    notesSha256: digest(Buffer.from(`original notes ${tag}`, "utf8")),
  }));
  return createHistoricalInventory({
    sourceFileSha256: digest(Buffer.from("source inventory file", "utf8")),
    sourceInventory,
    apkIdentities,
    updateIdentities,
  });
}

function createCandidateDirectory(parent, targetSequence = 0,
  titleForTag = (tag) => `autoform-kit ${tag.slice(1)}`) {
  const inventory = createInventory(titleForTag);
  const inventoryPath = path.join(parent, "historical-inventory.json");
  const inventoryBytes = Buffer.from(`${canonicalJson(inventory)}\n`, "utf8");
  writePrivate(inventoryPath, inventoryBytes);
  const inventoryFileSha256 = digest(inventoryBytes);
  const scanner = path.join(harness.repository, "tools", "public-surface-audit.mjs");
  const policy = path.join(harness.repository, "tools", "apk-third-party-components.json");
  const runtimeLock = path.join(
    harness.repository, "tools", "android-runtime-dependencies.lock.json",
  );
  const sourceVerifier = path.join(
    harness.repository, "tools", "verify-apk-third-party-sources.mjs",
  );
  const candidates = [];
  let previousRebuiltCandidate = null;
  for (let sequence = 0; sequence <= targetSequence; sequence += 1) {
    const tag = HISTORICAL_TAGS[sequence];
    const versionName = tag.slice(1);
    const original = inventory.releases[sequence];
    const directory = path.join(parent, "release-candidates", tag);
    fs.mkdirSync(directory, { recursive: true });
    const apkPath = path.join(directory, original.originalApk.assetName);
    const notesPath = path.join(directory, "release-notes.txt");
    const updatePath = path.join(directory, "update.json");
    const manifestPath = path.join(directory, "candidate-manifest.json");
    const apkBytes = Buffer.from(`sanitized rebuilt fixture apk bytes ${tag}`, "utf8");
    const apkSha256 = digest(apkBytes);
    const notesBytes = Buffer.from(HISTORICAL_BODY, "utf8");
    const notesSha256 = digest(notesBytes);
    const updateBytes = Buffer.from(`${canonicalJson({
      apkAsset: original.originalApk.assetName,
      notes: HISTORICAL_BODY,
      packageName: PACKAGE_NAME,
      sha256: apkSha256,
      versionCode: sequence + 1,
      versionName,
    })}\n`, "utf8");
    const updateSha256 = digest(updateBytes);
    writePrivate(apkPath, apkBytes);
    writePrivate(notesPath, notesBytes);
    writePrivate(updatePath, updateBytes);
    const title = titleForTag(tag);
    const candidate = {
      schemaVersion: 4,
      publicationMode: HISTORICAL_PUBLICATION_MODE,
      tag,
      source: { branch: "main", commit: harness.commit, workingTreeClean: true },
      app: {
        packageName: PACKAGE_NAME,
        signerSha256: SIGNER_SHA256,
        versionCode: sequence + 1,
        versionName,
      },
      artifacts: {
        apk: { file: original.originalApk.assetName, sha256: apkSha256 },
        notes: { file: "release-notes.txt", sha256: notesSha256 },
        update: { file: "update.json", sha256: updateSha256 },
      },
      historicalRelease: {
        inventory: {
          fileSha256: inventoryFileSha256,
          inventorySha256: inventory.inventorySha256,
          selectedReleaseIdentitySha256: original.releaseIdentitySha256,
        },
        publication: {
          assets: [
            { file: original.originalApk.assetName, name: original.originalApk.assetName,
              sha256: apkSha256, size: apkBytes.length },
            { file: "update.json", name: "update.json", sha256: updateSha256,
              size: updateBytes.length },
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
        previousRebuiltCandidate,
      },
      publicAudit: {
        apk: { inputSha256: apkSha256,
          reportSha256: digest(Buffer.from(`apk report ${tag}`, "utf8")),
          zipEntryManifestSha256: digest(Buffer.from(`apk entries ${tag}`, "utf8")) },
        policySha256: hashFile(policy),
        releaseMetadata: {
          notes: { inputSha256: notesSha256,
            reportSha256: digest(Buffer.from(`notes report ${tag}`, "utf8")) },
          update: { inputSha256: updateSha256,
            reportSha256: digest(Buffer.from(`update report ${tag}`, "utf8")) },
        },
        scannerSha256: hashFile(scanner),
        sourceTree: { gitTreeOid: harness.tree,
          inputSha256: digest(Buffer.from(`source tree ${tag}`, "utf8")),
          reportSha256: digest(Buffer.from(`source tree report ${tag}`, "utf8")) },
        thirdPartyProvenance: {
          apkMatchedDexStringCount: 1,
          applicationDexStrict: true,
          compiledOutputCount: 1,
          declaredDexStringCount: 1,
          dexSourceArtifactCount: 1,
          dexSourceEntryCount: 1,
          manifestFile: "tools/apk-third-party-components.json",
          manifestSha256: hashFile(policy),
          matchedEntryCount: 1,
          mergedSourceCount: 1,
          profileId: `release-${tag}`,
          runtimeLockFile: "tools/android-runtime-dependencies.lock.json",
          runtimeLockSha256: hashFile(runtimeLock),
          sourceArtifactCount: 1,
          sourceEntryCount: 1,
          sourceMatchedDexStringCount: 1,
          sourceReportSha256: SOURCE_REPORT_SHA256,
          sourceVerifierFile: "tools/verify-apk-third-party-sources.mjs",
          sourceVerifierSha256: hashFile(sourceVerifier),
        },
        worktree: { inputSha256: digest(Buffer.from(`worktree ${tag}`, "utf8")),
          reportSha256: digest(Buffer.from(`worktree report ${tag}`, "utf8")) },
      },
    };
    const manifestBytes = Buffer.from(`${canonicalJson(candidate)}\n`, "utf8");
    writePrivate(manifestPath, manifestBytes);
    previousRebuiltCandidate = {
      apkSha256,
      candidateManifestSha256: digest(manifestBytes),
      packageName: PACKAGE_NAME,
      signerSha256: SIGNER_SHA256,
      tag,
      versionCode: sequence + 1,
      versionName,
    };
    candidates.push({
      apkBytes, apkPath, candidate, directory, manifestPath, notesPath, updateBytes, updatePath,
    });
  }
  const selected = candidates[targetSequence];
  return {
    ...selected,
    candidates,
    inventory,
    inventoryFileSha256,
    inventoryPath,
  };
}

function stableRelease() {
  const tag = "v1.0.7";
  const apk = Buffer.from("stable apk fixture", "utf8");
  const update = Buffer.from("stable update fixture", "utf8");
  return githubRelease({
    id: 707,
    tag,
    target: harness.commit,
    name: "autoform-kit 1.0.7",
    body: "Generic stable release notes",
    draft: false,
    assets: [
      githubAsset({
        bytes: apk,
        contentType: "application/vnd.android.package-archive",
        id: 1701,
        name: "autoform-kit-1.0.7.apk",
        tag,
      }),
      githubAsset({
        bytes: update,
        contentType: "application/json",
        id: 1702,
        name: "update.json",
        tag,
      }),
    ],
  });
}

function genericPreexistingRelease() {
  const tag = "v0.9.9";
  return githubRelease({
    id: 99,
    tag,
    target: harness.commit,
    name: "autoform-kit 0.9.9",
    body: "Generic pre-existing release notes",
    draft: false,
    assets: [githubAsset({
      bytes: Buffer.from("generic old fixture", "utf8"),
      contentType: "application/octet-stream",
      id: 199,
      name: "generic-0.9.9.bin",
      tag,
    })],
  });
}

function ghState(candidateFixture, { latest, mode }) {
  const { candidate } = candidateFixture;
  const allTags = mode === "preexisting-release-final-mutation"
    ? [...ALL_TAGS, "v0.9.9"] : ALL_TAGS;
  const tagCommits = Object.fromEntries(allTags.map((tag) => [tag, harness.commit]));
  if (mode === "target-tag-mismatch") tagCommits[candidate.tag] = "d".repeat(40);
  const releases = latest === "stable" ? [stableRelease()] : [];
  const assetBytes = {};
  for (const [sequence, rebuilt] of candidateFixture.candidates.entries()) {
    if (sequence >= candidate.lineage.sequence) break;
    const previousAssets = rebuilt.candidate.historicalRelease.publication.assets.map(
      (asset, assetIndex) => {
        const bytes = fs.readFileSync(path.join(rebuilt.directory, asset.file));
        const id = 400 + sequence * 10 + assetIndex;
        assetBytes[String(id)] = bytes.toString("base64");
        return githubAsset({
          bytes,
          contentType: asset.name.endsWith(".apk")
            ? "application/vnd.android.package-archive" : "application/json",
          id,
          name: asset.name,
          tag: rebuilt.candidate.tag,
        });
      },
    );
    releases.push(githubRelease({
      assets: previousAssets,
      body: HISTORICAL_BODY,
      draft: rebuilt.candidate.historicalRelease.publication.draft,
      id: 4000 + sequence,
      name: rebuilt.candidate.historicalRelease.publication.title,
      tag: rebuilt.candidate.tag,
      target: harness.commit,
    }));
  }
  if (mode === "prefix-apk-metadata-tamper" && candidate.lineage.sequence > 0) {
    const first = releases.find((release) => release.tag_name === "v1.0.0");
    const apk = first?.assets.find((asset) => asset.name.endsWith(".apk"));
    if (apk) apk.digest = `sha256:${digest(Buffer.from("tampered prefix apk", "utf8"))}`;
  }
  if (mode === "prefix-update-download-tamper" && candidate.lineage.sequence > 0) {
    const first = releases.find((release) => release.tag_name === "v1.0.0");
    const update = first?.assets.find((asset) => asset.name === "update.json");
    if (update) assetBytes[String(update.id)] = Buffer.from("tampered").toString("base64");
  }
  if (mode === "preexisting-release-final-mutation") {
    releases.push(genericPreexistingRelease());
  }
  const assets = candidate.historicalRelease.publication.assets.map((asset, index) =>
    githubAsset({
      bytes: fs.readFileSync(path.join(candidateFixture.directory, asset.file)),
      contentType: asset.name.endsWith(".apk")
        ? "application/vnd.android.package-archive" : "application/json",
      id: 801 + index,
      name: asset.name,
      tag: candidate.tag,
    }));
  return {
    allTags,
    assetBytes,
    branchReads: 0,
    commit: harness.commit,
    draftRelease: githubRelease({
      assets,
      body: candidate.historicalRelease.publication.body,
      draft: true,
      id: 301,
      name: candidate.historicalRelease.publication.title,
      tag: candidate.tag,
      target: harness.commit,
    }),
    latestTag: latest === "stable" ? "v1.0.7" : null,
    mode,
    privateMarker: PRIVATE_MARKER,
    pullRefs: {
      "refs/pull/1/head": harness.commit,
      "refs/pull/1/merge": harness.commit,
    },
    repoReads: 0,
    remoteRefReads: 0,
    releases,
    repository: {
      default_branch: "main",
      description: "Generic form framework",
      full_name: SLUG,
      homepage: "https://example.invalid/autoform-kit",
      id: 901,
      node_id: "R_fixture_901",
      private: false,
      topics: ["android", "forms"],
      visibility: "public",
    },
    slug: SLUG,
    tagCommits,
  };
}

function branchApiFixture(state) {
  return [{
    commit: {
      sha: state.commit,
      url: `https://api.github.com/repos/${state.slug}/commits/${state.commit}`,
    },
    name: "main",
    protected: false,
    protection: {
      enabled: false,
      required_status_checks: { checks: [], contexts: [], enforcement_level: "off" },
    },
    protection_url: `https://api.github.com/repos/${state.slug}/branches/main/protection`,
  }];
}

function tagApiFixture(state) {
  return state.allTags.map((tag, index) => ({
    commit: {
      sha: state.tagCommits[tag],
      url: `https://api.github.com/repos/${state.slug}/commits/${state.tagCommits[tag]}`,
    },
    name: tag,
    node_id: `T_fixture_${index + 1}`,
    tarball_url: `https://api.github.com/repos/${state.slug}/tarball/refs/tags/${tag}`,
    zipball_url: `https://api.github.com/repos/${state.slug}/zipball/refs/tags/${tag}`,
  }));
}

function remoteRefBytes(state) {
  const lines = [
    `${state.commit}\tHEAD`,
    `${state.commit}\trefs/heads/main`,
    ...state.allTags.map((tag) => `${state.tagCommits[tag]}\trefs/tags/${tag}`),
    ...Object.entries(state.pullRefs).map(([ref, oid]) => `${oid}\t${ref}`),
  ];
  return Buffer.from(`${lines.sort().join("\n")}\n`, "utf8");
}

function reachableClosureFixture(remoteRefs) {
  const roots = [...new Set([
    ...remoteRefs.branches.map((branch) => branch.commitSha),
    ...remoteRefs.tags.flatMap((tag) => [tag.objectSha, tag.commitSha]),
    ...remoteRefs.pullRefs.map((pullRef) => pullRef.commitSha),
  ])].sort();
  const lines = command(harness.realGit, ["rev-list", "--objects", ...roots], {
    cwd: harness.repository,
  }).split("\n").filter(Boolean);
  const locations = new Map();
  for (const line of lines) {
    const separator = line.indexOf(" ");
    const oid = separator < 0 ? line : line.slice(0, separator);
    if (!locations.has(oid)) locations.set(oid, new Set());
    if (separator >= 0 && line.length > separator + 1) {
      locations.get(oid).add(line.slice(separator + 1));
    }
  }
  const objectIds = [...locations.keys()].sort();
  const checked = command(harness.realGit,
    ["cat-file", "--batch-check=%(objectname) %(objecttype) %(objectsize)"], {
      cwd: harness.repository,
      input: `${objectIds.join("\n")}\n`,
    }).split("\n").filter(Boolean);
  assert.equal(checked.length, objectIds.length);
  const closure = checked.map((line, index) => {
    const match = /^([a-f0-9]{40}) (blob|commit|tag|tree) ([0-9]+)$/u.exec(line);
    assert.ok(match);
    assert.equal(match[1], objectIds[index]);
    const content = commandBytes(harness.realGit, ["cat-file", match[2], match[1]], {
      cwd: harness.repository,
    });
    assert.equal(content.length, Number(match[3]));
    return {
      contentSha256: digest(content),
      locations: [...locations.get(match[1])].sort(),
      sha: match[1],
      size: Number(match[3]),
      type: match[2],
    };
  });
  return {
    reachableObjectClosureSha256: digest(Buffer.from(canonicalJson(closure), "utf8")),
    reachableObjectCount: closure.length,
  };
}

function createPrivateHistoryAttestation(root, state, mode, excludedReleaseTag) {
  const apiRefs = normalizeGithubBranchesAndTags(
    branchApiFixture(state), tagApiFixture(state), SLUG, state.repository,
  );
  const releases = normalizeGithubReleases([state.releases], SLUG, state.repository);
  const remoteRefs = normalizeRemoteRefs(remoteRefBytes(state), SLUG, state.repository);
  const apiRefsBytes = normalizedAuditBytes(apiRefs);
  const releasesBytes = normalizedAuditBytes(releases);
  const remoteRefsBytes = normalizedAuditBytes(remoteRefs);
  const bindings = {
    metadataBindingSha256: githubPublicMetadataBinding({
      pullRefIdentitySha256: remoteRefs.pullRefIdentitySha256,
      refApiSnapshotSha256: apiRefs.apiSnapshotSha256,
      refIdentitySha256: remoteRefs.refIdentitySha256,
      remoteRefsRawSnapshotSha256: remoteRefs.rawSnapshotSha256,
      releaseApiSnapshotSha256: releases.apiSnapshotSha256,
      repositoryBindingSha256: releases.repositoryBindingSha256,
    }),
    pullRefIdentitySha256: remoteRefs.pullRefIdentitySha256,
    refApiInputSha256: digest(apiRefsBytes),
    refApiSnapshotSha256: apiRefs.apiSnapshotSha256,
    refIdentitySha256: remoteRefs.refIdentitySha256,
    releaseApiInputSha256: digest(releasesBytes),
    releaseApiSnapshotSha256: releases.apiSnapshotSha256,
    remoteRefsInputSha256: digest(remoteRefsBytes),
    remoteRefsRawSnapshotSha256: remoteRefs.rawSnapshotSha256,
    repositoryBindingSha256: releases.repositoryBindingSha256,
  };
  const closure = reachableClosureFixture(remoteRefs);
  if (mode === "history-audit-forged-closure") {
    closure.reachableObjectClosureSha256 = digest(Buffer.from("forged closure", "utf8"));
  }
  const wordlistStat = fs.statSync(harness.wordlist);
  const auditReport = {
    auditMode: mode === "history-audit-final-mode" ? "final" : "historical-incremental",
    auditorSha256: digest(Buffer.from("fixture full-history auditor", "utf8")),
    bindings,
    excludedReleaseTag: mode === "history-audit-wrong-excluded-tag"
      ? HISTORICAL_TAGS.find((tag) => tag !== excludedReleaseTag)
      : excludedReleaseTag,
    findingCount: 0,
    findings: [],
    kind: PRIVATE_HISTORY_AUDIT_KIND,
    matchCount: 0,
    matches: [],
    ok: mode !== "history-audit-not-ready",
    privateWordlistSetSha256: privateWordlistSetIdentity([{
      sha256: hashFile(harness.wordlist),
      size: wordlistStat.size,
    }]),
    problemCount: mode === "history-audit-not-ready" ? 1 : 0,
    problemPaths: mode === "history-audit-not-ready" ? ["fixture.problem"] : [],
    reachableObjectClosureSha256: closure.reachableObjectClosureSha256,
    reachableObjectCount: closure.reachableObjectCount,
    reportSha256: "",
    scannerSha256: hashFile(path.join(harness.repository, "tools", "public-surface-audit.mjs")),
    version: 1,
    wordlistCount: 1,
  };
  auditReport.reportSha256 = privateHistoryAuditIdentity(auditReport);
  const attestation = {
    auditReport,
    auditReportFileSha256: digest(Buffer.from(`${canonicalJson(auditReport)}\n`, "utf8")),
    kind: PRIVATE_HISTORY_ATTESTATION_KIND,
    releaseReady: mode !== "history-audit-not-ready",
    reportSha256: "",
    schemaVersion: 1,
  };
  attestation.reportSha256 = privateHistoryAttestationIdentity(attestation);
  const filename = path.join(root, "private-history-audit.json");
  const bytes = Buffer.from(`${canonicalJson(attestation)}\n`, "utf8");
  writePrivate(filename, bytes);
  return { fileSha256: digest(bytes), filename };
}

function readLog(filename) {
  const text = fs.readFileSync(filename, "utf8").trim();
  return text ? text.split("\n").map((line) => JSON.parse(line)) : [];
}

function runScenario({
  historyAuditHashOverride,
  inventoryHashOverride,
  latest = "absent",
  mode = "normal",
  mutateCandidate,
  mutateHistoryAudit,
  sequence = 0,
  titleForTag,
} = {}) {
  const root = fs.mkdtempSync(path.join(harness.root, "scenario-"));
  const fixture = createCandidateDirectory(root, sequence, titleForTag);
  if (mutateCandidate) {
    mutateCandidate(fixture.candidate);
    writePrivate(fixture.manifestPath,
      Buffer.from(`${canonicalJson(fixture.candidate)}\n`, "utf8"));
  }
  const statePath = path.join(root, "gh-state.json");
  const logPath = path.join(root, "gh-side-effects.jsonl");
  const state = ghState(fixture, { latest, mode });
  const dirtyPull = mode === "dirty-pull-handfilled-audit";
  const genericTag = mode === "preexisting-release-final-mutation";
  if (dirtyPull) {
    state.pullRefs["refs/pull/1/head"] = harness.dirtyPullCommit;
    command(harness.realGit,
      ["update-ref", "refs/pull/1/head", harness.dirtyPullCommit], {
        cwd: harness.repository,
      });
  }
  if (genericTag) {
    command(harness.realGit, ["tag", "v0.9.9", harness.commit], { cwd: harness.repository });
  }
  let historyAudit;
  let result;
  try {
    const historyAuditState = clone(state);
    if (mode === "target-tag-mismatch") {
      historyAuditState.tagCommits[fixture.candidate.tag] = harness.commit;
    }
    historyAudit = createPrivateHistoryAttestation(
      root, historyAuditState, mode, fixture.candidate.tag,
    );
    if (mutateHistoryAudit) mutateHistoryAudit(historyAudit, root);
    writePrivate(statePath, Buffer.from(JSON.stringify(state)));
    writePrivate(logPath, Buffer.from(""));
    result = spawnSync(process.execPath, [harness.publisher,
      "--candidate", fixture.manifestPath,
      "--inventory", fixture.inventoryPath,
      "--inventory-file-sha256", inventoryHashOverride || fixture.inventoryFileSha256,
      "--private-history-audit", historyAudit.filename,
      "--private-history-audit-file-sha256",
      historyAuditHashOverride || historyAudit.fileSha256,
      "--private-wordlist", harness.wordlist,
    ], {
      cwd: harness.repository,
      encoding: "utf8",
      maxBuffer: 64 * 1024 * 1024,
      env: {
        ...process.env,
        AAPT: harness.aapt,
        APKSIGNER: harness.apksigner,
        FAKE_GH_LOG: logPath,
        FAKE_GH_STATE: statePath,
        FAKE_PROFILE_ID: fixture.candidate.publicAudit.thirdPartyProvenance.profileId,
        FAKE_SOURCE_REPORT_SHA256: SOURCE_REPORT_SHA256,
        FAKE_SOURCE_REPOSITORY: harness.repository,
        FAKE_TEST_MODE: mode,
        PATH: `${harness.fakeBin}${path.delimiter}${process.env.PATH}`,
        REAL_GIT: harness.realGit,
      },
    });
  } finally {
    if (dirtyPull) {
      command(harness.realGit, ["update-ref", "refs/pull/1/head", harness.commit], {
        cwd: harness.repository,
      });
    }
    if (genericTag) {
      command(harness.realGit, ["tag", "-d", "v0.9.9"], { cwd: harness.repository });
    }
  }
  return {
    ...fixture,
    log: readLog(logPath),
    result,
    state: JSON.parse(fs.readFileSync(statePath, "utf8")),
  };
}

function assertSucceeded(run) {
  assert.equal(run.result.status, 0, `${run.result.stdout}\n${run.result.stderr}`);
  assert.ok(run.result.stdout.includes(
    `published and verified ${run.candidate.tag} without changing latest`,
  ));
  const published = run.state.releases.find(
    (release) => release.tag_name === run.candidate.tag,
  );
  assert.ok(published);
  assert.equal(published.draft, false);
}

function assertPreflightRejected(run) {
  assert.notEqual(run.result.status, 0, run.result.stdout);
  assert.deepEqual(run.log, []);
}

before(() => {
  harness = createHarness();
});

after(() => {
  if (harness?.root) fs.rmSync(harness.root, { recursive: true, force: true });
});

test("publishes a historical Release while latest is absent", () => {
  const run = runScenario();
  assertSucceeded(run);
  assert.equal(run.state.latestTag, null);
});

test("publishes a historical Release while the existing v1.0.7 latest remains exact", () => {
  const run = runScenario({ latest: "stable" });
  assertSucceeded(run);
  assert.equal(run.state.latestTag, "v1.0.7");
  assert.equal(run.state.releases.find((release) => release.tag_name === "v1.0.7").draft,
    false);
});

test("publishes v1.0.6 only after recursively binding and live-verifying the exact prefix", () => {
  const run = runScenario({ sequence: 6 });
  assertSucceeded(run);
  for (const tag of HISTORICAL_TAGS) {
    assert.ok(run.state.releases.some((release) => release.tag_name === tag));
  }
  assert.equal(run.state.latestTag, null);
});

test("an earlier rebuilt APK metadata digest mismatch is rejected before create/PATCH", () => {
  assertPreflightRejected(runScenario({ mode: "prefix-apk-metadata-tamper", sequence: 6 }));
});

test("an earlier rebuilt update with wrong live bytes is rejected before create/PATCH", () => {
  assertPreflightRejected(runScenario({ mode: "prefix-update-download-tamper", sequence: 6 }));
});

test("create is forced through a verified non-latest draft and omits candidate-manifest", () => {
  const run = runScenario();
  assertSucceeded(run);
  assert.deepEqual(run.log.map((entry) => entry.kind), ["create", "patch"]);
  const create = run.log[0].args;
  for (const flag of ["--verify-tag", "--draft", "--latest=false"]) {
    assert.ok(create.includes(flag), `missing ${flag}`);
  }
  assert.equal(create.includes("--latest"), false);
  assert.equal(create.includes("--clobber"), false);
  const firstOption = create.findIndex((item, index) => index >= 3 && item.startsWith("--"));
  assert.deepEqual(create.slice(3, firstOption).map((filename) => path.basename(filename)), [
    path.basename(run.apkPath),
    path.basename(run.updatePath),
  ]);
  assert.equal(create.some((item) => path.basename(item) === "candidate-manifest.json"), false);
  assert.equal(create[create.indexOf("--title") + 1],
    run.candidate.historicalRelease.publication.title);
  assert.deepEqual(run.log[1].args.slice(0, 3), ["api", "--method", "PATCH"]);
  assert.ok(run.log[1].args.some((item) => /repos\/.+\/releases\/301$/u.test(item)));
  assert.ok(run.log[1].args.includes("--input"));
});

test("Unicode historical title reaches the create argument without jq or byte-length drift", () => {
  const run = runScenario({ titleForTag: (tag) => `通用表单 🚀 ${tag.slice(1)}` });
  assertSucceeded(run);
  const create = run.log.find((entry) => entry.kind === "create").args;
  assert.equal(create[create.indexOf("--title") + 1], `通用表单 🚀 1.0.0`);
});

test("an unpinned inventory is rejected before create/edit", () => {
  assertPreflightRejected(runScenario({ inventoryHashOverride: "0".repeat(64) }));
});

test("an unpinned private full-history audit is rejected before create/PATCH", () => {
  assertPreflightRejected(runScenario({ historyAuditHashOverride: "0".repeat(64) }));
});

test("private full-history audit rejects a non-0600 file before create/PATCH", () => {
  assertPreflightRejected(runScenario({
    mutateHistoryAudit(historyAudit) {
      fs.chmodSync(historyAudit.filename, 0o640);
    },
  }));
});

test("private full-history audit rejects a hard-linked file before create/PATCH", () => {
  assertPreflightRejected(runScenario({
    mutateHistoryAudit(historyAudit, root) {
      fs.linkSync(historyAudit.filename, path.join(root, "history-audit-hardlink.json"));
    },
  }));
});

test("a non-ready private full-history audit is rejected before create/PATCH", () => {
  assertPreflightRejected(runScenario({ mode: "history-audit-not-ready" }));
});

test("a standard-final history audit cannot replace historical-incremental evidence", () => {
  assertPreflightRejected(runScenario({ mode: "history-audit-final-mode" }));
});

test("an incremental audit for another excluded tag cannot be reused", () => {
  assertPreflightRejected(runScenario({ mode: "history-audit-wrong-excluded-tag" }));
});

test("an incremental audit missing its excluded tag binding is rejected", () => {
  assertPreflightRejected(runScenario({
    mutateHistoryAudit(historyAudit) {
      const value = JSON.parse(fs.readFileSync(historyAudit.filename, "utf8"));
      delete value.auditReport.excludedReleaseTag;
      value.auditReport.reportSha256 = privateHistoryAuditIdentity(value.auditReport);
      value.auditReportFileSha256 = digest(Buffer.from(
        `${canonicalJson(value.auditReport)}\n`, "utf8",
      ));
      value.reportSha256 = privateHistoryAttestationIdentity(value);
      const bytes = Buffer.from(`${canonicalJson(value)}\n`, "utf8");
      writePrivate(historyAudit.filename, bytes);
      historyAudit.fileSha256 = digest(bytes);
    },
  }));
});

test("a self-rebound but forged reachable closure is rejected before create/PATCH", () => {
  assertPreflightRejected(runScenario({ mode: "history-audit-forged-closure" }));
});

test("a dirty pull-ref closure cannot be bypassed by hand-filled passing booleans", () => {
  const run = runScenario({ mode: "dirty-pull-handfilled-audit" });
  assertPreflightRejected(run);
  assert.equal(run.result.stdout.includes(PRIVATE_MARKER), false);
  assert.equal(run.result.stderr.includes(PRIVATE_MARKER), false);
});

test("the wrong publication mode is rejected before create/edit", () => {
  assertPreflightRejected(runScenario({
    mutateCandidate(candidate) {
      candidate.publicationMode = "stable";
    },
  }));
});

test("private inventory copies cannot be reintroduced into a schema-4 candidate", () => {
  assertPreflightRejected(runScenario({
    mutateCandidate(candidate) {
      candidate.historicalRelease.inventory.sourceInventory = {};
      candidate.historicalRelease.original = {};
      candidate.lineage.originalApk = {};
    },
  }));
});

test("candidate rejection never echoes rejected private content", () => {
  const run = runScenario({
    mutateCandidate(candidate) {
      candidate.historicalRelease.publication.body = PRIVATE_MARKER;
    },
  });
  assertPreflightRejected(run);
  assert.equal(run.result.stdout.includes(PRIVATE_MARKER), false);
  assert.equal(run.result.stderr.includes(PRIVATE_MARKER), false);
});

test("ambiguous historical lineage is rejected before create/edit", () => {
  assertPreflightRejected(runScenario({
    mutateCandidate(candidate) {
      candidate.lineage.kind = "historical-upgrade-rebuild";
    },
  }));
});

test("a target tag that does not peel to the candidate commit is rejected before create/edit", () => {
  assertPreflightRejected(runScenario({ mode: "target-tag-mismatch" }));
});

test("a remote repository mutation in the second capture is rejected before create/edit", () => {
  assertPreflightRejected(runScenario({ mode: "remote-second-capture-mutation" }));
});

test("a fresh scanner failure is rejected before create/edit", () => {
  assertPreflightRejected(runScenario({ mode: "scanner-failure" }));
});

test("a sensitive required status context is scanned and rejected before create/edit", () => {
  assertPreflightRejected(runScenario({ mode: "sensitive-status-context" }));
});

test("a status context mutation between the two captures is rejected before create/edit", () => {
  assertPreflightRejected(runScenario({ mode: "status-context-second-capture-mutation" }));
});

test("a pull ref mutation between the two captures is rejected before create/PATCH", () => {
  assertPreflightRejected(runScenario({ mode: "pull-ref-second-capture-mutation" }));
});

test("a pull ref mutation in the final capture is rejected after the explicit PATCH", () => {
  const run = runScenario({ mode: "pull-ref-final-mutation" });
  assert.notEqual(run.result.status, 0);
  assert.deepEqual(run.log.map((entry) => entry.kind), ["create", "patch"]);
  assert.match(run.result.stderr, /remote Release may now contain a draft or partial change/u);
});

test("a fake that promotes the historical draft to latest is rejected after create", () => {
  const run = runScenario({ mode: "bad-latest-after-create" });
  assert.notEqual(run.result.status, 0);
  assert.deepEqual(run.log.map((entry) => entry.kind), ["create"]);
  assert.match(run.result.stderr, /remote Release may now contain a draft or partial change/u);
  assert.equal(run.state.latestTag, "v1.0.0");
});

test("tampered downloaded asset bytes are rejected after create and before edit", () => {
  const run = runScenario({ mode: "download-asset-tamper" });
  assert.notEqual(run.result.status, 0);
  assert.deepEqual(run.log.map((entry) => entry.kind), ["create"]);
  assert.match(run.result.stderr, /remote Release may now contain a draft or partial change/u);
});

test("sensitive draft metadata is scanned and rejected before edit", () => {
  const run = runScenario({ mode: "draft-sensitive-metadata" });
  assert.notEqual(run.result.status, 0);
  assert.deepEqual(run.log.map((entry) => entry.kind), ["create"]);
  assert.match(run.result.stderr, /remote Release may now contain a draft or partial change/u);
});

test("a concurrent mutation of a pre-existing Release fails the final binding", () => {
  const run = runScenario({ mode: "preexisting-release-final-mutation" });
  assert.notEqual(run.result.status, 0);
  assert.deepEqual(run.log.map((entry) => entry.kind), ["create", "patch"]);
  assert.match(run.result.stderr, /remote Release may now contain a draft or partial change/u);
});
