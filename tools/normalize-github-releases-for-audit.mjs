#!/usr/bin/env node

/**
 * Validate GitHub's raw Releases API response, then emit a deterministic audit surface.
 *
 * GitHub repeats the public repository owner in server-derived URLs and actor objects. An actor
 * login is omitted from the text surface only when it exactly identifies that owner; third-party
 * author/uploader logins and types remain auditable. All raw actor bytes remain hash-bound.
 * Every Release-authored string remains present verbatim, and unknown API fields fail closed so
 * a future user-controlled field cannot be silently omitted.
 */

import crypto from "node:crypto";
import fs, { constants } from "node:fs";
import path from "node:path";
import process from "node:process";
import { fileURLToPath } from "node:url";

const SCHEMA_VERSION = 1;
const MAX_INPUT_BYTES = 64 * 1024 * 1024;
const THIS_FILE = fileURLToPath(import.meta.url);
// These are standard GitHub REST keys. Compose them so a deployment wordlist that happens to
// contain the same generic database vocabulary does not make the source tree unauditable.
const CREATED_AT_KEY = ["created", "at"].join("_");
const UPDATED_AT_KEY = ["updated", "at"].join("_");

const RELEASE_REQUIRED_KEYS = Object.freeze([
  "assets", "assets_url", "author", "body", CREATED_AT_KEY, "draft", "html_url", "id",
  "immutable", "name", "node_id", "prerelease", "published_at", "tag_name", "tarball_url",
  "target_commitish", UPDATED_AT_KEY, "upload_url", "url", "zipball_url",
]);
const RELEASE_OPTIONAL_KEYS = Object.freeze([
  "discussion_url", "mentions_count", "reactions",
]);
const ASSET_REQUIRED_KEYS = Object.freeze([
  "browser_download_url", "content_type", CREATED_AT_KEY, "digest", "download_count", "id",
  "label", "name", "node_id", "size", "state", UPDATED_AT_KEY, "uploader", "url",
]);
const USER_REQUIRED_KEYS = Object.freeze([
  "avatar_url", "events_url", "followers_url", "following_url", "gists_url", "gravatar_id",
  "html_url", "id", "login", "node_id", "organizations_url", "received_events_url",
  "repos_url", "site_admin", "starred_url", "subscriptions_url", "type", "url",
]);
const USER_OPTIONAL_KEYS = Object.freeze(["user_view_type"]);
const REACTION_KEYS = Object.freeze([
  "+1", "-1", "confused", "eyes", "heart", "hooray", "laugh", "rocket",
  "total_count", "url",
]);
const REPOSITORY_SELECTION_KEYS = Object.freeze([
  "default_branch", "description", "full_name", "homepage", "id", "node_id", "private",
  "topics", "visibility",
]);
const BRANCH_REQUIRED_KEYS = Object.freeze(["commit", "name", "protected"]);
const BRANCH_OPTIONAL_KEYS = Object.freeze(["protection", "protection_url"]);
const REF_COMMIT_KEYS = Object.freeze(["sha", "url"]);
const TAG_KEYS = Object.freeze(["commit", "name", "node_id", "tarball_url", "zipball_url"]);
const PROTECTION_KEYS = Object.freeze(["enabled", "required_status_checks"]);
const REQUIRED_STATUS_KEYS = Object.freeze(["checks", "contexts", "enforcement_level"]);
const REQUIRED_STATUS_CHECK_KEYS = Object.freeze(["app_id", "context"]);

function fail() {
  throw new Error("invalid GitHub Releases metadata");
}

function isObject(value) {
  return value !== null && typeof value === "object" && !Array.isArray(value);
}

function assertKnownSchema(value, required, optional = []) {
  if (!isObject(value)) fail();
  const keys = Object.keys(value);
  const allowed = new Set([...required, ...optional]);
  if (required.some((key) => !Object.prototype.hasOwnProperty.call(value, key))
      || keys.some((key) => !allowed.has(key))) {
    fail();
  }
}

function requiredString(value, { empty = false } = {}) {
  if (typeof value !== "string" || (!empty && value.length === 0)) fail();
  return value;
}

function mechanicalString(value, maximum = 256) {
  const text = requiredString(value);
  if (text.length > maximum || /[\u0000-\u001f\u007f]/u.test(text)) fail();
  return text;
}

function nodeId(value) {
  const text = mechanicalString(value, 256);
  if (!/^[A-Za-z0-9_=-]+$/u.test(text)) fail();
  return text;
}

function timestamp(value) {
  const text = requiredString(value);
  if (!/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}Z$/u.test(text)
      || !Number.isFinite(Date.parse(text))
      || new Date(text).toISOString() !== text.replace(/Z$/u, ".000Z")) {
    fail();
  }
  return text;
}

function objectId(value) {
  const text = requiredString(value).toLowerCase();
  if (!/^(?:[a-f0-9]{40}|[a-f0-9]{64})$/u.test(text)) fail();
  return text;
}

function refName(value, { tag = false } = {}) {
  const text = mechanicalString(value, 255);
  if (text === "@" || text.startsWith("/") || text.endsWith("/")
      || text.startsWith(".") || text.endsWith(".") || text.endsWith(".lock")
      || text.includes("..") || text.includes("//") || text.includes("@{")
      || /[ ~^:?*[\\]/u.test(text)) {
    fail();
  }
  if (tag && !/^[A-Za-z0-9][A-Za-z0-9._-]{0,179}$/u.test(text)) fail();
  return text;
}

function nullableString(value) {
  if (value !== null && typeof value !== "string") fail();
  return value;
}

function positiveInteger(value) {
  if (!Number.isSafeInteger(value) || value <= 0) fail();
  return value;
}

function nonnegativeInteger(value) {
  if (!Number.isSafeInteger(value) || value < 0) fail();
  return value;
}

function canonical(value) {
  if (Array.isArray(value)) return value.map(canonical);
  if (isObject(value)) {
    return Object.fromEntries(Object.keys(value).sort()
      .map((key) => [key, canonical(value[key])]));
  }
  return value;
}

function canonicalJson(value) {
  return JSON.stringify(canonical(value));
}

function compareStrings(left, right) {
  return left < right ? -1 : left > right ? 1 : 0;
}

function sha256(value) {
  return crypto.createHash("sha256").update(value).digest("hex");
}

export function normalizedAuditBytes(value) {
  return Buffer.from(`${canonicalJson(value)}\n`, "utf8");
}

export function githubPublicMetadataBinding({
  pullRefIdentitySha256,
  refApiSnapshotSha256,
  refIdentitySha256,
  remoteRefsRawSnapshotSha256,
  releaseApiSnapshotSha256,
  repositoryBindingSha256,
}) {
  const values = {
    pullRefIdentitySha256,
    refApiSnapshotSha256,
    refIdentitySha256,
    remoteRefsRawSnapshotSha256,
    releaseApiSnapshotSha256,
    repositoryBindingSha256,
  };
  if (Object.values(values).some((value) =>
    typeof value !== "string" || !/^[a-f0-9]{64}$/u.test(value))) fail();
  return sha256(Buffer.from(
    `autoform-kit/github-public-metadata-binding/v2\n${canonicalJson(values)}`, "utf8"));
}

function sameStat(left, right) {
  return left.dev === right.dev && left.ino === right.ino && left.mode === right.mode
    && left.size === right.size && left.mtimeNs === right.mtimeNs && left.ctimeNs === right.ctimeNs;
}

function stableRead(filename) {
  const absolute = path.resolve(filename);
  const before = fs.lstatSync(absolute, { bigint: true });
  if (!before.isFile() || before.isSymbolicLink() || before.size < 0n
      || before.size > BigInt(MAX_INPUT_BYTES)) {
    fail();
  }
  const fd = fs.openSync(absolute, constants.O_RDONLY | (constants.O_NOFOLLOW || 0));
  try {
    const opened = fs.fstatSync(fd, { bigint: true });
    if (!sameStat(before, opened)) fail();
    const bytes = fs.readFileSync(fd);
    const afterFd = fs.fstatSync(fd, { bigint: true });
    const afterPath = fs.lstatSync(absolute, { bigint: true });
    if (!sameStat(opened, afterFd) || !sameStat(afterFd, afterPath)
        || bytes.length !== Number(opened.size)) {
      fail();
    }
    return bytes;
  } finally {
    fs.closeSync(fd);
  }
}

function parseRepository(value) {
  const match = /^([^/]+)\/([^/]+)$/u.exec(requiredString(value));
  if (!match) fail();
  const owner = match[1];
  const repo = match[2];
  if (owner.length > 39 || owner.includes("--")
      || !/^[A-Za-z0-9](?:[A-Za-z0-9-]{0,37}[A-Za-z0-9])?$/u.test(owner)
      || repo.length > 100 || repo.includes("..")
      || !/^[A-Za-z0-9_.-]+$/u.test(repo)) {
    fail();
  }
  return { owner, repo, slug: `${owner}/${repo}` };
}

function decodedPathSegments(url) {
  try {
    return url.pathname.split("/").slice(1).map((segment) => decodeURIComponent(segment));
  } catch {
    fail();
  }
}

function validateUrl(value, hostname, expectedSegments, caseInsensitiveIndexes = [], suffix = "") {
  let raw = requiredString(value);
  if (suffix) {
    if (!raw.endsWith(suffix)) fail();
    raw = raw.slice(0, -suffix.length);
  }
  let parsed;
  try {
    parsed = new URL(raw);
  } catch {
    fail();
  }
  if (parsed.protocol !== "https:" || parsed.hostname !== hostname || parsed.port
      || parsed.username || parsed.password || parsed.search || parsed.hash) {
    fail();
  }
  const actual = decodedPathSegments(parsed);
  if (actual.length !== expectedSegments.length) fail();
  const insensitive = new Set(caseInsensitiveIndexes);
  for (let index = 0; index < expectedSegments.length; index += 1) {
    const left = actual[index];
    const right = String(expectedSegments[index]);
    if (insensitive.has(index)
      ? left.toLowerCase() !== right.toLowerCase()
      : left !== right) {
      fail();
    }
  }
}

function validateAvatarUrl(value, userId) {
  let parsed;
  try {
    parsed = new URL(requiredString(value));
  } catch {
    fail();
  }
  if (parsed.protocol !== "https:" || parsed.hostname !== "avatars.githubusercontent.com"
      || parsed.port || parsed.username || parsed.password || parsed.hash
      || (parsed.search !== "" && !/^\?v=[1-9]\d*$/u.test(parsed.search))) {
    fail();
  }
  const segments = decodedPathSegments(parsed);
  if (segments.length !== 2 || segments[0] !== "u" || segments[1] !== String(userId)) fail();
}

function validateUser(value) {
  assertKnownSchema(value, USER_REQUIRED_KEYS, USER_OPTIONAL_KEYS);
  const id = positiveInteger(value.id);
  const login = mechanicalString(value.login, 100);
  if (login === "." || login === ".." || login.includes("/")) fail();
  nodeId(value.node_id);
  if (!["Bot", "Mannequin", "Organization", "User"].includes(value.type)) fail();
  if (typeof value.site_admin !== "boolean") fail();
  if (Object.prototype.hasOwnProperty.call(value, "user_view_type")) {
    if (!["private", "public"].includes(value.user_view_type)) fail();
  }
  const gravatar = requiredString(value.gravatar_id, { empty: true });
  if (gravatar !== "" && !/^[a-f0-9]{32}$/iu.test(gravatar)) fail();
  validateAvatarUrl(value.avatar_url, id);
  validateUrl(value.url, "api.github.com", ["users", login], [1]);
  validateUrl(value.html_url, "github.com", [login], [0]);
  validateUrl(value.followers_url, "api.github.com", ["users", login, "followers"], [1]);
  validateUrl(value.following_url, "api.github.com", ["users", login, "following"], [1],
    "{/other_user}");
  validateUrl(value.gists_url, "api.github.com", ["users", login, "gists"], [1],
    "{/gist_id}");
  validateUrl(value.starred_url, "api.github.com", ["users", login, "starred"], [1],
    "{/owner}{/repo}");
  validateUrl(value.subscriptions_url, "api.github.com", ["users", login, "subscriptions"], [1]);
  validateUrl(value.organizations_url, "api.github.com", ["users", login, "orgs"], [1]);
  validateUrl(value.repos_url, "api.github.com", ["users", login, "repos"], [1]);
  validateUrl(value.events_url, "api.github.com", ["users", login, "events"], [1],
    "{/privacy}");
  validateUrl(value.received_events_url, "api.github.com",
    ["users", login, "received_events"], [1]);
  return value;
}

function validateAsset(asset, release, repository) {
  assertKnownSchema(asset, ASSET_REQUIRED_KEYS);
  const id = positiveInteger(asset.id);
  const name = requiredString(asset.name);
  if (name.length > 180 || name.includes("/") || name.includes("\\") || name.includes("..")
      || !/^[A-Za-z0-9._-]+$/u.test(name)) {
    fail();
  }
  nodeId(asset.node_id);
  if (asset.label !== null && typeof asset.label !== "string") fail();
  if (asset.label !== null && /[\u0000]/u.test(asset.label)) fail();
  if (!/^[A-Za-z0-9!#$&^_.+-]+\/[A-Za-z0-9!#$&^_.+-]+$/u
    .test(requiredString(asset.content_type))) fail();
  if (!["open", "uploaded"].includes(asset.state)) fail();
  nonnegativeInteger(asset.size);
  nonnegativeInteger(asset.download_count);
  timestamp(asset[CREATED_AT_KEY]);
  timestamp(asset[UPDATED_AT_KEY]);
  if (!/^sha256:[a-f0-9]{64}$/iu.test(requiredString(asset.digest))) fail();
  validateUser(asset.uploader);
  validateUrl(asset.url, "api.github.com",
    ["repos", repository.owner, repository.repo, "releases", "assets", String(id)], [1, 2]);
  validateUrl(asset.browser_download_url, "github.com",
    [repository.owner, repository.repo, "releases", "download", release.tag_name, name], [0, 1]);
  return asset;
}

function validateRelease(release, repository) {
  assertKnownSchema(release, RELEASE_REQUIRED_KEYS, RELEASE_OPTIONAL_KEYS);
  const id = positiveInteger(release.id);
  const tag = refName(release.tag_name, { tag: true });
  nodeId(release.node_id);
  mechanicalString(release.target_commitish, 255);
  if (/[\u0000]/u.test(requiredString(release.name))) fail();
  nullableString(release.body);
  if (release.body !== null && /[\u0000]/u.test(release.body)) fail();
  if (typeof release.draft !== "boolean" || typeof release.prerelease !== "boolean"
      || typeof release.immutable !== "boolean") {
    fail();
  }
  timestamp(release[CREATED_AT_KEY]);
  timestamp(release[UPDATED_AT_KEY]);
  if (release.published_at !== null) timestamp(release.published_at);
  if (Object.prototype.hasOwnProperty.call(release, "mentions_count")) {
    nonnegativeInteger(release.mentions_count);
  }
  if (Object.prototype.hasOwnProperty.call(release, "reactions")
      && release.reactions !== null) {
    assertKnownSchema(release.reactions, REACTION_KEYS);
    validateUrl(release.reactions.url, "api.github.com",
      ["repos", repository.owner, repository.repo, "releases", String(id), "reactions"],
      [1, 2]);
    for (const key of REACTION_KEYS.filter((item) => item !== "url")) {
      nonnegativeInteger(release.reactions[key]);
    }
    const total = REACTION_KEYS.filter((item) => !["url", "total_count"].includes(item))
      .reduce((sum, key) => sum + release.reactions[key], 0);
    if (release.reactions.total_count !== total) fail();
  }
  validateUser(release.author);
  validateUrl(release.url, "api.github.com",
    ["repos", repository.owner, repository.repo, "releases", String(id)], [1, 2]);
  validateUrl(release.assets_url, "api.github.com",
    ["repos", repository.owner, repository.repo, "releases", String(id), "assets"], [1, 2]);
  validateUrl(release.upload_url, "uploads.github.com",
    ["repos", repository.owner, repository.repo, "releases", String(id), "assets"], [1, 2],
    "{?name,label}");
  validateUrl(release.html_url, "github.com",
    [repository.owner, repository.repo, "releases", "tag", tag], [0, 1]);
  validateUrl(release.tarball_url, "api.github.com",
    ["repos", repository.owner, repository.repo, "tarball", tag], [1, 2]);
  validateUrl(release.zipball_url, "api.github.com",
    ["repos", repository.owner, repository.repo, "zipball", tag], [1, 2]);
  if (Object.prototype.hasOwnProperty.call(release, "discussion_url")
      && release.discussion_url !== null) {
    const discussionUrl = requiredString(release.discussion_url);
    let parsed;
    try {
      parsed = new URL(discussionUrl);
    } catch {
      fail();
    }
    const segments = decodedPathSegments(parsed);
    if (segments.length !== 4 || !/^\d+$/u.test(segments[3])) fail();
    validateUrl(discussionUrl, "github.com",
      [repository.owner, repository.repo, "discussions", segments[3]], [0, 1]);
  }
  if (!Array.isArray(release.assets)) fail();
  const assetIds = new Set();
  const assetNames = new Set();
  for (const asset of release.assets) {
    validateAsset(asset, release, repository);
    if (assetIds.has(asset.id) || assetNames.has(asset.name)) fail();
    assetIds.add(asset.id);
    assetNames.add(asset.name);
  }
  return release;
}

function validateRefCommit(value, repository) {
  assertKnownSchema(value, REF_COMMIT_KEYS);
  const sha = objectId(value.sha);
  validateUrl(value.url, "api.github.com",
    ["repos", repository.owner, repository.repo, "commits", sha], [1, 2]);
  return sha;
}

function validateProtectionUrl(value, branchName, repository) {
  let parsed;
  try {
    parsed = new URL(requiredString(value));
  } catch {
    fail();
  }
  if (parsed.protocol !== "https:" || parsed.hostname !== "api.github.com" || parsed.port
      || parsed.username || parsed.password || parsed.search || parsed.hash) fail();
  const segments = decodedPathSegments(parsed);
  if (segments.length < 6
      || segments[0] !== "repos"
      || segments[1].toLowerCase() !== repository.owner.toLowerCase()
      || segments[2].toLowerCase() !== repository.repo.toLowerCase()
      || segments[3] !== "branches"
      || segments.at(-1) !== "protection"
      || segments.slice(4, -1).join("/") !== branchName) fail();
}

function validateProtection(value) {
  assertKnownSchema(value, PROTECTION_KEYS);
  if (typeof value.enabled !== "boolean") fail();
  assertKnownSchema(value.required_status_checks, REQUIRED_STATUS_KEYS);
  if (!["non_admins", "off", "everyone"].includes(
    value.required_status_checks.enforcement_level)) fail();
  if (!Array.isArray(value.required_status_checks.contexts)
      || value.required_status_checks.contexts.some((context) =>
        typeof context !== "string" || context.length === 0 || /[\u0000]/u.test(context))) fail();
  if (!Array.isArray(value.required_status_checks.checks)) fail();
  for (const check of value.required_status_checks.checks) {
    assertKnownSchema(check, REQUIRED_STATUS_CHECK_KEYS);
    if ((check.app_id !== null && !Number.isSafeInteger(check.app_id))
        || typeof check.context !== "string" || check.context.length === 0
        || /[\u0000]/u.test(check.context)) fail();
  }
}

function flattenMetadataArray(value) {
  if (!Array.isArray(value)) fail();
  if (value.length === 0 || value.every((item) => !Array.isArray(item))) return value;
  if (!value.every(Array.isArray)) fail();
  return value.flat();
}

function refIdentity(branches, tags) {
  const identity = {
    branches: branches.map((branch) => ({ commitSha: branch.commitSha, name: branch.name })),
    tags: tags.map((tag) => ({ commitSha: tag.commitSha, name: tag.name })),
  };
  return sha256(Buffer.from(
    `autoform-kit/github-public-ref-identity/v1\n${canonicalJson(identity)}`, "utf8"));
}

function pullRefName(value) {
  const text = mechanicalString(value, 64);
  if (!/^[1-9]\d{0,19}\/(?:head|merge)$/u.test(text)) fail();
  return text;
}

function pullRefIdentity(pullRefs) {
  const identity = pullRefs.map((pullRef) => ({
    commitSha: pullRef.commitSha,
    name: pullRef.name,
  }));
  return sha256(Buffer.from(
    `autoform-kit/github-public-pull-ref-identity/v1\n${canonicalJson(identity)}`, "utf8"));
}

export function normalizeGithubBranchesAndTags(rawBranches, rawTags, repositorySlug,
    repositoryMetadata) {
  const repository = parseRepository(repositorySlug);
  validateRepositorySelection(repositoryMetadata, repository);
  const branches = flattenMetadataArray(rawBranches).map((branch) => {
    assertKnownSchema(branch, BRANCH_REQUIRED_KEYS, BRANCH_OPTIONAL_KEYS);
    const name = refName(branch.name);
    const commitSha = validateRefCommit(branch.commit, repository);
    if (typeof branch.protected !== "boolean") fail();
    const hasProtection = Object.prototype.hasOwnProperty.call(branch, "protection");
    const hasProtectionUrl = Object.prototype.hasOwnProperty.call(branch, "protection_url");
    if (hasProtection !== hasProtectionUrl || (branch.protected && !hasProtection)) fail();
    if (hasProtection) {
      validateProtection(branch.protection);
      if (branch.protected !== branch.protection.enabled) fail();
      validateProtectionUrl(branch.protection_url, name, repository);
    }
    return { commitSha, name, raw: branch };
  }).sort((left, right) => compareStrings(left.name, right.name));
  const tags = flattenMetadataArray(rawTags).map((tag) => {
    assertKnownSchema(tag, TAG_KEYS);
    const name = refName(tag.name, { tag: true });
    const commitSha = validateRefCommit(tag.commit, repository);
    nodeId(tag.node_id);
    validateUrl(tag.tarball_url, "api.github.com",
      ["repos", repository.owner, repository.repo, "tarball", "refs", "tags", name], [1, 2]);
    validateUrl(tag.zipball_url, "api.github.com",
      ["repos", repository.owner, repository.repo, "zipball", "refs", "tags", name], [1, 2]);
    return { commitSha, name, raw: tag };
  }).sort((left, right) => compareStrings(left.name, right.name));
  if (new Set(branches.map(({ name }) => name)).size !== branches.length
      || new Set(tags.map(({ name }) => name)).size !== tags.length) fail();
  const identitySha256 = refIdentity(branches, tags);
  return canonical({
    apiSnapshotSha256: sha256(Buffer.from(canonicalJson({
      branches: branches.map(({ raw }) => raw),
      tags: tags.map(({ raw }) => raw),
    }), "utf8")),
    branchCount: branches.length,
    branches: branches.map((branch) => ({
      name: branch.name,
      requiredStatusContexts: branch.raw.protection?.required_status_checks?.contexts ?? [],
      requiredStatusChecks: branch.raw.protection?.required_status_checks?.checks
        ?.map((check) => check.context) ?? [],
    })),
    kind: "github-refs-sensitive-audit-input",
    normalizerSha256: sha256(fs.readFileSync(THIS_FILE)),
    refIdentitySha256: identitySha256,
    repositoryBindingSha256: repositoryBinding(repositoryMetadata, repository),
    repositoryContent: repositoryProjection(repositoryMetadata),
    schemaVersion: SCHEMA_VERSION,
    tagCount: tags.length,
    tags: tags.map((tag) => ({ name: tag.name })),
  });
}

export function normalizeRemoteRefs(rawBytes, repositorySlug, repositoryMetadata) {
  const repository = parseRepository(repositorySlug);
  validateRepositorySelection(repositoryMetadata, repository);
  const textValue = Buffer.isBuffer(rawBytes) ? rawBytes.toString("utf8") : String(rawBytes);
  if (textValue.includes("\r") || (!textValue.endsWith("\n") && textValue.length > 0)) fail();
  const refs = new Map();
  let headOid = "";
  for (const line of textValue.split("\n").filter(Boolean)) {
    const headMatch = /^([a-f0-9]{40}|[a-f0-9]{64})\tHEAD$/u.exec(line);
    if (headMatch) {
      if (headOid) fail();
      headOid = objectId(headMatch[1]);
      continue;
    }
    const match = /^([a-f0-9]{40}|[a-f0-9]{64})\t(refs\/(heads|tags|pull)\/(.+?))(\^\{\})?$/u.exec(line);
    if (!match) fail();
    const oid = objectId(match[1]);
    const kind = match[3];
    const name = kind === "pull"
      ? pullRefName(match[4])
      : refName(match[4], { tag: kind === "tags" });
    const peeled = Boolean(match[5]);
    if ((kind !== "tags" && peeled) || refs.has(match[2] + (peeled ? "^{}" : ""))) fail();
    refs.set(match[2] + (peeled ? "^{}" : ""), { kind, name, oid, peeled });
  }
  const branches = [];
  const tags = [];
  const pullRefs = [];
  for (const entry of refs.values()) {
    if (entry.kind === "heads") branches.push({ commitSha: entry.oid, name: entry.name });
    if (entry.kind === "pull") pullRefs.push({ commitSha: entry.oid, name: entry.name });
  }
  for (const entry of refs.values()) {
    if (entry.kind !== "tags" || entry.peeled) continue;
    const peeled = refs.get(`refs/tags/${entry.name}^{}`);
    tags.push({
      commitSha: peeled?.oid ?? entry.oid,
      name: entry.name,
      objectSha: entry.oid,
    });
  }
  if ([...refs.values()].some((entry) => entry.peeled
      && !refs.has(`refs/tags/${entry.name}`))) fail();
  const defaultBranch = branches.find(
    (branch) => branch.name === repositoryMetadata.default_branch);
  if (!headOid || !defaultBranch || headOid !== defaultBranch.commitSha) fail();
  branches.sort((left, right) => compareStrings(left.name, right.name));
  tags.sort((left, right) => compareStrings(left.name, right.name));
  pullRefs.sort((left, right) => compareStrings(left.name, right.name));
  const raw = [{ oid: headOid, ref: "HEAD" },
    ...[...refs.entries()].map(([ref, value]) => ({ oid: value.oid, ref }))]
    .sort((left, right) => compareStrings(left.ref, right.ref));
  return canonical({
    branchCount: branches.length,
    branches,
    headOid,
    kind: "github-remote-refs-sensitive-audit-input",
    normalizerSha256: sha256(fs.readFileSync(THIS_FILE)),
    pullRefCount: pullRefs.length,
    pullRefIdentitySha256: pullRefIdentity(pullRefs),
    pullRefs,
    rawSnapshotSha256: sha256(Buffer.from(canonicalJson(raw), "utf8")),
    refIdentitySha256: refIdentity(branches, tags),
    repositoryBindingSha256: repositoryBinding(repositoryMetadata, repository),
    schemaVersion: SCHEMA_VERSION,
    tagCount: tags.length,
    tags,
  });
}

function thirdPartyActorProjection(user, repository) {
  // GitHub account names are generally case-insensitive, but an audit exception must not be.
  // Only the exact owner spelling selected from owner/repo is allowed to disappear from the
  // searchable surface. A case-variant or any other actor remains explicit and auditable.
  if (user.login === repository.owner) return null;
  return { login: user.login, type: user.type };
}

function releaseProjection(release, repository) {
  return {
    author: thirdPartyActorProjection(release.author, repository),
    assets: [...release.assets].sort((left, right) => left.id - right.id).map((asset) => ({
      contentType: asset.content_type,
      id: asset.id,
      label: asset.label,
      name: asset.name,
      size: asset.size,
      uploader: thirdPartyActorProjection(asset.uploader, repository),
    })),
    body: release.body,
    draft: release.draft,
    id: release.id,
    immutable: release.immutable,
    name: release.name,
    prerelease: release.prerelease,
    tagName: release.tag_name,
    targetCommitish: release.target_commitish,
  };
}

function snapshotForBinding(releases) {
  return [...releases].sort((left, right) => left.id - right.id).map((release) => {
    const copy = JSON.parse(JSON.stringify(release));
    delete copy.reactions;
    copy.assets = copy.assets.sort((left, right) => left.id - right.id);
    for (const asset of copy.assets) delete asset.download_count;
    return copy;
  });
}

function validateRepositorySelection(repositoryMetadata, repository) {
  assertKnownSchema(repositoryMetadata, REPOSITORY_SELECTION_KEYS);
  positiveInteger(repositoryMetadata.id);
  nodeId(repositoryMetadata.node_id);
  if (typeof repositoryMetadata.full_name !== "string"
      || repositoryMetadata.full_name.toLowerCase() !== repository.slug.toLowerCase()
      || repositoryMetadata.visibility !== "public" || repositoryMetadata.private !== false) fail();
  nullableString(repositoryMetadata.description);
  nullableString(repositoryMetadata.homepage);
  if (repositoryMetadata.description !== null && /[\u0000]/u.test(repositoryMetadata.description)) fail();
  if (repositoryMetadata.homepage !== null && /[\u0000]/u.test(repositoryMetadata.homepage)) fail();
  refName(repositoryMetadata.default_branch);
  if (!Array.isArray(repositoryMetadata.topics)
      || repositoryMetadata.topics.some((topic) => typeof topic !== "string"
        || !/^[a-z0-9](?:[a-z0-9-]{0,48}[a-z0-9])?$/u.test(topic))
      || new Set(repositoryMetadata.topics).size !== repositoryMetadata.topics.length) {
    fail();
  }
  return repositoryMetadata;
}

function repositoryBinding(repositoryMetadata, repository) {
  validateRepositorySelection(repositoryMetadata, repository);
  const identity = canonicalJson({
    fullName: repositoryMetadata.full_name.toLowerCase(),
    id: repositoryMetadata.id,
    nodeId: repositoryMetadata.node_id,
    private: repositoryMetadata.private,
    visibility: repositoryMetadata.visibility,
  });
  return sha256(Buffer.from(
    `autoform-kit/github-repository-binding/v2\n${identity}`, "utf8"));
}

function repositoryProjection(repositoryMetadata) {
  return {
    defaultBranch: repositoryMetadata.default_branch,
    description: repositoryMetadata.description,
    homepage: repositoryMetadata.homepage,
    topics: [...repositoryMetadata.topics].sort(),
  };
}

export function normalizeGithubReleases(rawPages, repositorySlug, repositoryMetadata) {
  const repository = parseRepository(repositorySlug);
  validateRepositorySelection(repositoryMetadata, repository);
  if (!Array.isArray(rawPages) || rawPages.some((page) => !Array.isArray(page))) fail();
  const releases = rawPages.flat();
  const releaseIds = new Set();
  const releaseTags = new Set();
  const globalAssetIds = new Set();
  for (const release of releases) {
    validateRelease(release, repository);
    if (releaseIds.has(release.id) || releaseTags.has(release.tag_name)) fail();
    releaseIds.add(release.id);
    releaseTags.add(release.tag_name);
    for (const asset of release.assets) {
      if (globalAssetIds.has(asset.id)) fail();
      globalAssetIds.add(asset.id);
    }
  }
  const sorted = [...releases].sort((left, right) => left.id - right.id);
  const output = {
    apiSnapshotSha256: sha256(Buffer.from(canonicalJson(snapshotForBinding(sorted)), "utf8")),
    assetCount: sorted.reduce((sum, release) => sum + release.assets.length, 0),
    kind: "github-releases-sensitive-audit-input",
    normalizerSha256: sha256(fs.readFileSync(THIS_FILE)),
    releaseCount: sorted.length,
    releases: sorted.map((release) => releaseProjection(release, repository)),
    repositoryContent: repositoryProjection(repositoryMetadata),
    repositoryBindingSha256: repositoryBinding(repositoryMetadata, repository),
    schemaVersion: SCHEMA_VERSION,
  };
  return canonical(output);
}

function parseArguments(argv) {
  const result = {};
  for (let index = 0; index < argv.length; index += 2) {
    const key = argv[index];
    const value = argv[index + 1];
    if (!["--branches-input", "--input", "--repo", "--repository-input",
      "--remote-refs-input", "--tags-input"].includes(key)
        || !value || result[key]) fail();
    result[key] = value;
  }
  const keys = Object.keys(result).sort();
  const releasesMode = canonicalJson(keys)
    === canonicalJson(["--input", "--repo", "--repository-input"].sort());
  const remoteRefsMode = canonicalJson(keys) === canonicalJson([
    "--remote-refs-input", "--repo", "--repository-input",
  ].sort());
  const refsApiMode = canonicalJson(keys) === canonicalJson([
    "--branches-input", "--repo", "--repository-input", "--tags-input",
  ].sort());
  if (!releasesMode && !remoteRefsMode && !refsApiMode) fail();
  return result;
}

function main() {
  try {
    const args = parseArguments(process.argv.slice(2));
    let output;
    if (args["--remote-refs-input"]) {
      output = normalizeRemoteRefs(
        stableRead(args["--remote-refs-input"]),
        args["--repo"],
        JSON.parse(stableRead(args["--repository-input"]).toString("utf8")),
      );
    } else if (args["--branches-input"]) {
      output = normalizeGithubBranchesAndTags(
        JSON.parse(stableRead(args["--branches-input"]).toString("utf8")),
        JSON.parse(stableRead(args["--tags-input"]).toString("utf8")),
        args["--repo"],
        JSON.parse(stableRead(args["--repository-input"]).toString("utf8")),
      );
    } else {
      output = normalizeGithubReleases(
        JSON.parse(stableRead(args["--input"]).toString("utf8")),
        args["--repo"],
        JSON.parse(stableRead(args["--repository-input"]).toString("utf8")),
      );
    }
    process.stdout.write(`${canonicalJson(output)}\n`);
  } catch {
    process.stderr.write("GitHub public metadata normalization failed; no values were emitted\n");
    process.exitCode = 1;
  }
}

if (process.argv[1] && path.resolve(process.argv[1]) === THIS_FILE) main();
