#!/usr/bin/env node

import assert from "node:assert/strict";
import { spawnSync } from "node:child_process";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import test from "node:test";
import { fileURLToPath } from "node:url";

import {
  githubPublicMetadataBinding,
  normalizeGithubBranchesAndTags,
  normalizeGithubReleases,
  normalizeRemoteRefs,
} from "./normalize-github-releases-for-audit.mjs";
import { runAudit } from "./public-surface-audit.mjs";

const HERE = path.dirname(fileURLToPath(import.meta.url));
const NORMALIZER = path.join(HERE, "normalize-github-releases-for-audit.mjs");
const OWNER = "fictional-private-selftest-marker";
const THIRD_PARTY = "fictional-third-party-private-marker";
const REPO = "autoform-kit";
const SLUG = `${OWNER}/${REPO}`;
const SHA = "a".repeat(40);
const CREATED_AT_KEY = ["created", "at"].join("_");
const UPDATED_AT_KEY = ["updated", "at"].join("_");

function clone(value) {
  return JSON.parse(JSON.stringify(value));
}

function user(login = OWNER, id = 101) {
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

function repository(overrides = {}) {
  return {
    default_branch: "main",
    description: "Generic form framework",
    full_name: SLUG,
    homepage: "https://example.invalid/autoform-kit",
    id: 901,
    node_id: "R_fixture_901",
    private: false,
    topics: ["android", "forms"],
    visibility: "public",
    ...overrides,
  };
}

function release(overrides = {}) {
  const tag = overrides.tag_name ?? "v1.0.0";
  const id = overrides.id ?? 301;
  const assetName = overrides.assets?.[0]?.name ?? "autoform-kit-1.0.0.apk";
  const baseAsset = {
    browser_download_url: `https://github.com/${SLUG}/releases/download/${tag}/${assetName}`,
    content_type: "application/vnd.android.package-archive",
    [CREATED_AT_KEY]: "2026-07-30T01:02:03Z",
    digest: `sha256:${"b".repeat(64)}`,
    download_count: 7,
    id: 401,
    label: "Android package",
    name: assetName,
    node_id: "RA_fixture_401",
    size: 12345,
    state: "uploaded",
    [UPDATED_AT_KEY]: "2026-07-30T01:02:04Z",
    uploader: user(),
    url: `https://api.github.com/repos/${SLUG}/releases/assets/401`,
  };
  const assets = overrides.assets
    ? overrides.assets.map((asset) => ({ ...baseAsset, ...asset }))
    : [baseAsset];
  return {
    assets,
    assets_url: `https://api.github.com/repos/${SLUG}/releases/${id}/assets`,
    author: user(),
    body: "Generic release notes",
    [CREATED_AT_KEY]: "2026-07-30T01:02:03Z",
    draft: false,
    html_url: `https://github.com/${SLUG}/releases/tag/${tag}`,
    id,
    immutable: false,
    name: "autoform-kit 1.0.0",
    node_id: `R_fixture_${id}`,
    prerelease: false,
    published_at: "2026-07-30T01:02:05Z",
    tag_name: tag,
    tarball_url: `https://api.github.com/repos/${SLUG}/tarball/${tag}`,
    target_commitish: "main",
    [UPDATED_AT_KEY]: "2026-07-30T01:02:05Z",
    upload_url: `https://uploads.github.com/repos/${SLUG}/releases/${id}/assets{?name,label}`,
    url: `https://api.github.com/repos/${SLUG}/releases/${id}`,
    zipball_url: `https://api.github.com/repos/${SLUG}/zipball/${tag}`,
    ...overrides,
    assets,
  };
}

function normalize(oneRelease = release(), repo = repository()) {
  return normalizeGithubReleases([[oneRelease]], SLUG, repo);
}

function normalizeRefs(raw, repo = repository()) {
  return normalizeRemoteRefs(raw, SLUG, repo);
}

function scanWithTerm(value, term = OWNER) {
  const temporary = fs.mkdtempSync(path.join(os.tmpdir(), "autoform-normalizer-test-"));
  const input = path.join(temporary, "input.json");
  const wordlist = path.join(temporary, "wordlist.json");
  try {
    fs.writeFileSync(input, `${JSON.stringify(value)}\n`, { mode: 0o600 });
    fs.writeFileSync(wordlist, `${JSON.stringify([term])}\n`, { mode: 0o600 });
    return runAudit({ mode: "file", input, privateWordlists: [wordlist] });
  } finally {
    fs.rmSync(temporary, { recursive: true, force: true });
  }
}

const scanWithOwner = (value) => scanWithTerm(value, OWNER);

function reboundReleaseTag(value, tag) {
  value.tag_name = tag;
  value.html_url = `https://github.com/${SLUG}/releases/tag/${tag}`;
  value.tarball_url = `https://api.github.com/repos/${SLUG}/tarball/${tag}`;
  value.zipball_url = `https://api.github.com/repos/${SLUG}/zipball/${tag}`;
  for (const asset of value.assets) {
    asset.browser_download_url =
      `https://github.com/${SLUG}/releases/download/${tag}/${asset.name}`;
  }
}

test("owner in validated repository, actor, and URL fields is not scanned", () => {
  const output = normalize();
  assert.equal(JSON.stringify(output).includes(OWNER), false);
  assert.equal(scanWithOwner(output).summary.passed, true);
});

for (const [field, mutate] of [
  ["release tag", (item) => reboundReleaseTag(item, `v-${OWNER}`)],
  ["target commitish", (item) => { item.target_commitish = OWNER; }],
  ["release name", (item) => { item.name = OWNER; }],
  ["release body", (item) => { item.body = OWNER; }],
  ["asset name", (item) => {
    item.assets[0].name = `${OWNER}.apk`;
    item.assets[0].browser_download_url =
      `https://github.com/${SLUG}/releases/download/${item.tag_name}/${OWNER}.apk`;
  }],
  ["asset label", (item) => { item.assets[0].label = OWNER; }],
  ["asset content type", (item) => { item.assets[0].content_type = `application/${OWNER}`; }],
]) {
  test(`user-controlled ${field} remains in the sensitive scan`, () => {
    const item = release();
    mutate(item);
    const output = normalize(item);
    assert.equal(scanWithOwner(output).summary.passed, false);
  });
}

for (const [field, value] of [
  ["description", OWNER],
  ["homepage", `https://example.invalid/${OWNER}`],
  ["topics", [OWNER]],
  ["default_branch", OWNER],
]) {
  test(`repository ${field} remains in the sensitive scan`, () => {
    const output = normalize(release(), repository({ [field]: value }));
    assert.equal(scanWithOwner(output).summary.passed, false);
  });
}

for (const [label, mutate] of [
  ["release API URL", (item) => { item.url = "https://example.invalid/release"; }],
  ["release assets URL", (item) => { item.assets_url += "?unexpected=1"; }],
  ["upload URL", (item) => { item.upload_url = item.upload_url.replace("uploads", "api"); }],
  ["release HTML URL", (item) => { item.html_url += "#fragment"; }],
  ["tarball URL", (item) => { item.tarball_url += "/wrong"; }],
  ["zipball URL", (item) => { item.zipball_url = item.zipball_url.replace("zipball", "tarball"); }],
  ["asset API URL", (item) => { item.assets[0].url = item.assets[0].url.replace("401", "402"); }],
  ["asset download URL", (item) => { item.assets[0].browser_download_url += "?token=x"; }],
]) {
  test(`tampered ${label} fails closed`, () => {
    const item = release();
    mutate(item);
    assert.throws(() => normalize(item));
  });
}

test("unknown release, asset, user, and reaction keys fail closed", () => {
  for (const mutate of [
    (item) => { item.future_release_field = "value"; },
    (item) => { item.assets[0].future_asset_field = "value"; },
    (item) => { item.author.future_user_field = "value"; },
    (item) => {
      item.reactions = {
        "+1": 0, "-1": 0, confused: 0, eyes: 0, heart: 0, hooray: 0, laugh: 0,
        rocket: 0, total_count: 0,
        url: `https://api.github.com/repos/${SLUG}/releases/301/reactions`,
        future_reaction_field: 0,
      };
    },
  ]) {
    const item = release();
    mutate(item);
    assert.throws(() => normalize(item));
  }
});

test("repository identity is exact and stable-binding changes with id or node id", () => {
  const first = normalize();
  const second = normalize(release(), repository({ id: 902 }));
  const third = normalize(release(), repository({ node_id: "R_fixture_902" }));
  assert.notEqual(first.repositoryBindingSha256, second.repositoryBindingSha256);
  assert.notEqual(first.repositoryBindingSha256, third.repositoryBindingSha256);
  for (const invalid of [
    repository({ full_name: `${OWNER}/wrong` }),
    repository({ visibility: "private" }),
    repository({ private: true }),
    { ...repository(), future_repository_selection_field: "value" },
  ]) assert.throws(() => normalize(release(), invalid));
});

test("repository API user content stays envelope-bound without changing repository identity", () => {
  const first = normalize();
  const changed = normalize(release(), repository({ description: "Changed generic description" }));
  assert.equal(first.repositoryBindingSha256, changed.repositoryBindingSha256);
  assert.notEqual(JSON.stringify(first), JSON.stringify(changed));
});

test("release and asset ordering is deterministic", () => {
  const first = release({ id: 301 });
  const second = release({ id: 302, tag_name: "v1.0.1" });
  reboundReleaseTag(second, "v1.0.1");
  second.url = `https://api.github.com/repos/${SLUG}/releases/302`;
  second.assets_url = `${second.url}/assets`;
  second.upload_url = `https://uploads.github.com/repos/${SLUG}/releases/302/assets{?name,label}`;
  second.node_id = "R_fixture_302";
  second.assets[0].id = 402;
  second.assets[0].node_id = "RA_fixture_402";
  second.assets[0].url = `https://api.github.com/repos/${SLUG}/releases/assets/402`;
  const forward = normalizeGithubReleases([[first], [second]], SLUG, repository());
  const reverse = normalizeGithubReleases([[second, first]], SLUG, repository());
  assert.deepEqual(forward, reverse);
});

test("only documented volatile reactions and download counts are removed from raw binding", () => {
  const first = release();
  first.reactions = {
    "+1": 1, "-1": 0, confused: 0, eyes: 0, heart: 0, hooray: 0, laugh: 0,
    rocket: 0, total_count: 1,
    url: `https://api.github.com/repos/${SLUG}/releases/301/reactions`,
  };
  const second = clone(first);
  second.assets[0].download_count = 999;
  second.reactions["+1"] = 0;
  second.reactions.heart = 2;
  second.reactions.total_count = 2;
  assert.equal(normalize(first).apiSnapshotSha256, normalize(second).apiSnapshotSha256);
  second.assets[0].size += 1;
  assert.notEqual(normalize(first).apiSnapshotSha256, normalize(second).apiSnapshotSha256);
});

test("repository-owner actor identity stays out of scan text but remains raw-bound", () => {
  const first = normalize();
  const changed = release();
  changed.author = user(OWNER, 102);
  const second = normalize(changed);
  assert.notEqual(first.apiSnapshotSha256, second.apiSnapshotSha256);
  assert.equal(JSON.stringify(second).includes(OWNER), false);
});

test("third-party Release author and asset uploader remain in the sensitive scan", () => {
  for (const [field, login] of [
    ["author", THIRD_PARTY],
    ["uploader", THIRD_PARTY],
    ["author", OWNER.toUpperCase()],
    ["uploader", OWNER.toUpperCase()],
  ]) {
    const changed = release();
    if (field === "author") changed.author = user(login, 102);
    else changed.assets[0].uploader = user(login, 102);
    const projected = field === "author"
      ? normalize(changed).releases[0].author
      : normalize(changed).releases[0].assets[0].uploader;
    assert.deepEqual(projected, { login, type: "User" });
    assert.equal(scanWithTerm(normalize(changed), login).summary.passed, false);
  }
});

test("combined public metadata binding includes the refs API snapshot", () => {
  const base = {
    pullRefIdentitySha256: "1".repeat(64),
    refApiSnapshotSha256: "2".repeat(64),
    refIdentitySha256: "3".repeat(64),
    remoteRefsRawSnapshotSha256: "4".repeat(64),
    releaseApiSnapshotSha256: "5".repeat(64),
    repositoryBindingSha256: "6".repeat(64),
  };
  const first = githubPublicMetadataBinding(base);
  const second = githubPublicMetadataBinding({
    ...base, refApiSnapshotSha256: "7".repeat(64),
  });
  assert.notEqual(first, second);
  assert.throws(() => githubPublicMetadataBinding({
    refIdentitySha256: base.refIdentitySha256,
    remoteRefsRawSnapshotSha256: base.remoteRefsRawSnapshotSha256,
    releaseApiSnapshotSha256: base.releaseApiSnapshotSha256,
    repositoryBindingSha256: base.repositoryBindingSha256,
  }));
});

test("combined public metadata binding includes the exact pull-ref identity", () => {
  const base = {
    pullRefIdentitySha256: "1".repeat(64),
    refApiSnapshotSha256: "2".repeat(64),
    refIdentitySha256: "3".repeat(64),
    remoteRefsRawSnapshotSha256: "4".repeat(64),
    releaseApiSnapshotSha256: "5".repeat(64),
    repositoryBindingSha256: "6".repeat(64),
  };
  assert.notEqual(githubPublicMetadataBinding(base), githubPublicMetadataBinding({
    ...base, pullRefIdentitySha256: "7".repeat(64),
  }));
});

test("remote refs and strict GitHub branch/tag APIs share one identity digest", () => {
  const branch = {
    commit: { sha: SHA, url: `https://api.github.com/repos/${SLUG}/commits/${SHA}` },
    name: "main",
    protected: false,
    protection: {
      enabled: false,
      required_status_checks: { checks: [], contexts: [], enforcement_level: "off" },
    },
    protection_url: `https://api.github.com/repos/${SLUG}/branches/main/protection`,
  };
  const tag = {
    commit: { sha: SHA, url: `https://api.github.com/repos/${SLUG}/commits/${SHA}` },
    name: "v1.0.0",
    node_id: "T_fixture_1",
    tarball_url: `https://api.github.com/repos/${SLUG}/tarball/refs/tags/v1.0.0`,
    zipball_url: `https://api.github.com/repos/${SLUG}/zipball/refs/tags/v1.0.0`,
  };
  const api = normalizeGithubBranchesAndTags([branch], [tag], SLUG, repository());
  const remote = normalizeRefs(
    `${SHA}\tHEAD\n${SHA}\trefs/heads/main\n${SHA}\trefs/tags/v1.0.0\n`);
  assert.equal(api.refIdentitySha256, remote.refIdentitySha256);
  assert.equal(JSON.stringify(api).includes(OWNER), false);
  const namedRefs = normalizeRefs(
    `${SHA}\tHEAD\n${SHA}\trefs/heads/main\n`
      + `${SHA}\trefs/heads/${OWNER}\n${SHA}\trefs/tags/v-${OWNER}\n`);
  assert.equal(scanWithOwner(namedRefs).summary.passed, false);
  const contextBranch = clone(branch);
  contextBranch.protection.required_status_checks.contexts = [OWNER];
  contextBranch.protection.required_status_checks.checks = [{ app_id: 1, context: OWNER }];
  const contextEnvelope = normalizeGithubBranchesAndTags(
    [contextBranch], [tag], SLUG, repository());
  assert.deepEqual(contextEnvelope.branches[0].requiredStatusContexts, [OWNER]);
  assert.deepEqual(contextEnvelope.branches[0].requiredStatusChecks, [OWNER]);
  assert.equal(scanWithOwner(contextEnvelope).summary.passed, false);

  const branchNameMarker = clone(branch);
  branchNameMarker.name = OWNER;
  branchNameMarker.protection_url =
    `https://api.github.com/repos/${SLUG}/branches/${OWNER}/protection`;
  assert.equal(scanWithOwner(normalizeGithubBranchesAndTags(
    [branchNameMarker], [tag], SLUG, repository())).summary.passed, false);

  const tagNameMarker = clone(tag);
  tagNameMarker.name = `v-${OWNER}`;
  tagNameMarker.tarball_url =
    `https://api.github.com/repos/${SLUG}/tarball/refs/tags/v-${OWNER}`;
  tagNameMarker.zipball_url =
    `https://api.github.com/repos/${SLUG}/zipball/refs/tags/v-${OWNER}`;
  assert.equal(scanWithOwner(normalizeGithubBranchesAndTags(
    [branch], [tagNameMarker], SLUG, repository())).summary.passed, false);
  assert.throws(() => normalizeGithubBranchesAndTags(
    [{ ...branch, unknown: true }], [tag], SLUG, repository()));
  assert.throws(() => normalizeGithubBranchesAndTags(
    [{ commit: branch.commit, name: "main", protected: true }], [tag], SLUG,
    repository()));
  assert.throws(() => normalizeGithubBranchesAndTags(
    [{ ...branch, protection: { ...branch.protection, enabled: true } }], [tag], SLUG,
    repository()));
  assert.throws(() => normalizeGithubBranchesAndTags(
    [branch], [{ ...tag, tarball_url: "https://example.invalid/archive" }], SLUG,
    repository()));

  const annotated = normalizeRefs(
    `${SHA}\tHEAD\n${SHA}\trefs/heads/main\n${"b".repeat(40)}\trefs/tags/v1.0.0\n`
      + `${SHA}\trefs/tags/v1.0.0^{}\n`);
  assert.equal(annotated.refIdentitySha256, remote.refIdentitySha256);
  assert.notEqual(annotated.rawSnapshotSha256, remote.rawSnapshotSha256);
});

test("remote refs bind pull request head and merge tips without PR API text", () => {
  const otherSha = "b".repeat(40);
  const first = normalizeRefs(
    `${SHA}\tHEAD\n${SHA}\trefs/heads/main\n`
      + `${SHA}\trefs/pull/7/head\n${otherSha}\trefs/pull/7/merge\n`);
  assert.equal(first.pullRefCount, 2);
  assert.deepEqual(first.pullRefs, [
    { commitSha: SHA, name: "7/head" },
    { commitSha: otherSha, name: "7/merge" },
  ]);
  const changed = normalizeRefs(
    `${SHA}\tHEAD\n${SHA}\trefs/heads/main\n`
      + `${otherSha}\trefs/pull/7/head\n${otherSha}\trefs/pull/7/merge\n`);
  assert.notEqual(first.pullRefIdentitySha256, changed.pullRefIdentitySha256);
  assert.notEqual(first.rawSnapshotSha256, changed.rawSnapshotSha256);
  for (const invalid of [
    `${SHA}\trefs/heads/main\n`,
    `${SHA}\tHEAD\n${otherSha}\tHEAD\n${SHA}\trefs/heads/main\n`,
    `${otherSha}\tHEAD\n${SHA}\trefs/heads/main\n`,
    `${SHA}\tHEAD\n${SHA}\trefs/heads/main\n${SHA}\trefs/notes/private\n`,
    `${SHA}\tHEAD\n${SHA}\trefs/heads/main\n${SHA}\trefs/pull/0/head\n`,
    `${SHA}\tHEAD\n${SHA}\trefs/heads/main\n${SHA}\trefs/pull/07/head\n`,
    `${SHA}\tHEAD\n${SHA}\trefs/heads/main\n${SHA}\trefs/pull/7/body\n`,
    `${SHA}\tHEAD\n${SHA}\trefs/heads/main\n${SHA}\trefs/pull/7/head^{}\n`,
  ]) assert.throws(() => normalizeRefs(invalid));
});

test("CLI failures do not echo owner or tampered values", () => {
  const temporary = fs.mkdtempSync(path.join(os.tmpdir(), "autoform-normalizer-cli-"));
  const input = path.join(temporary, "releases.json");
  const repo = path.join(temporary, "repository.json");
  try {
    const item = release();
    item.url = `https://${OWNER}@api.github.com/private-value`;
    fs.writeFileSync(input, JSON.stringify([[item]]), { mode: 0o600 });
    fs.writeFileSync(repo, JSON.stringify(repository()), { mode: 0o600 });
    const result = spawnSync(process.execPath, [NORMALIZER,
      "--repo", SLUG, "--input", input, "--repository-input", repo], {
      encoding: "utf8",
    });
    assert.notEqual(result.status, 0);
    assert.equal(`${result.stdout}${result.stderr}`.includes(OWNER), false);
    assert.equal(`${result.stdout}${result.stderr}`.includes("private-value"), false);
  } finally {
    fs.rmSync(temporary, { recursive: true, force: true });
  }
});

test("CLI strictly normalizes a complete branches/tags API input set", () => {
  const temporary = fs.mkdtempSync(path.join(os.tmpdir(), "autoform-refs-api-cli-"));
  const branchesInput = path.join(temporary, "branches.json");
  const tagsInput = path.join(temporary, "tags.json");
  const repositoryInput = path.join(temporary, "repository.json");
  const branch = {
    commit: { sha: SHA, url: `https://api.github.com/repos/${SLUG}/commits/${SHA}` },
    name: "main",
    protected: false,
    protection: {
      enabled: false,
      required_status_checks: { checks: [], contexts: [], enforcement_level: "off" },
    },
    protection_url: `https://api.github.com/repos/${SLUG}/branches/main/protection`,
  };
  const tag = {
    commit: { sha: SHA, url: `https://api.github.com/repos/${SLUG}/commits/${SHA}` },
    name: "v1.0.0",
    node_id: "T_fixture_1",
    tarball_url: `https://api.github.com/repos/${SLUG}/tarball/refs/tags/v1.0.0`,
    zipball_url: `https://api.github.com/repos/${SLUG}/zipball/refs/tags/v1.0.0`,
  };
  try {
    fs.writeFileSync(branchesInput, JSON.stringify([[branch]]), { mode: 0o600 });
    fs.writeFileSync(tagsInput, JSON.stringify([[tag]]), { mode: 0o600 });
    fs.writeFileSync(repositoryInput, JSON.stringify(repository()), { mode: 0o600 });
    const complete = spawnSync(process.execPath, [NORMALIZER,
      "--branches-input", branchesInput,
      "--tags-input", tagsInput,
      "--repo", SLUG,
      "--repository-input", repositoryInput,
    ], { encoding: "utf8" });
    assert.equal(complete.status, 0, complete.stderr);
    const output = JSON.parse(complete.stdout);
    assert.equal(output.kind, "github-refs-sensitive-audit-input");
    assert.equal(output.refIdentitySha256, normalizeGithubBranchesAndTags(
      [[branch]], [[tag]], SLUG, repository(),
    ).refIdentitySha256);

    const partial = spawnSync(process.execPath, [NORMALIZER,
      "--branches-input", branchesInput,
      "--repo", SLUG,
      "--repository-input", repositoryInput,
    ], { encoding: "utf8" });
    assert.notEqual(partial.status, 0);
    const unknown = spawnSync(process.execPath, [NORMALIZER,
      "--branches-input", branchesInput,
      "--tags-input", tagsInput,
      "--repo", SLUG,
      "--repository-input", repositoryInput,
      "--unexpected-private-value", OWNER,
    ], { encoding: "utf8" });
    assert.notEqual(unknown.status, 0);
    assert.equal(`${unknown.stdout}${unknown.stderr}`.includes(OWNER), false);
  } finally {
    fs.rmSync(temporary, { recursive: true, force: true });
  }
});

test("CLI strictly groups and stably reads complete transport refs with repository metadata", () => {
  const temporary = fs.mkdtempSync(path.join(os.tmpdir(), "autoform-remote-refs-cli-"));
  const input = path.join(temporary, "remote-refs.txt");
  const repositoryInput = path.join(temporary, "repository.json");
  const linked = path.join(temporary, "linked-refs.txt");
  try {
    fs.writeFileSync(input,
      `${SHA}\tHEAD\n${SHA}\trefs/heads/main\n${SHA}\trefs/pull/1/head\n`,
      { mode: 0o600 });
    fs.writeFileSync(repositoryInput, JSON.stringify(repository()), { mode: 0o600 });
    const complete = spawnSync(process.execPath, [NORMALIZER,
      "--remote-refs-input", input,
      "--repo", SLUG,
      "--repository-input", repositoryInput,
    ], { encoding: "utf8" });
    assert.equal(complete.status, 0, complete.stderr);
    const output = JSON.parse(complete.stdout);
    assert.equal(output.headOid, SHA);
    assert.equal(output.pullRefCount, 1);
    assert.match(output.repositoryBindingSha256, /^[a-f0-9]{64}$/u);

    const partial = spawnSync(process.execPath, [NORMALIZER,
      "--remote-refs-input", input,
    ], { encoding: "utf8" });
    assert.notEqual(partial.status, 0);

    fs.symlinkSync(input, linked);
    const symlinked = spawnSync(process.execPath, [NORMALIZER,
      "--remote-refs-input", linked,
      "--repo", SLUG,
      "--repository-input", repositoryInput,
    ], { encoding: "utf8" });
    assert.notEqual(symlinked.status, 0);
  } finally {
    fs.rmSync(temporary, { recursive: true, force: true });
  }
});
