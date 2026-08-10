#!/usr/bin/env node

import assert from "node:assert/strict";
import { createHash } from "node:crypto";
import { chmod, mkdtemp, rename, rm, symlink, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import test from "node:test";

import {
  currentUpgradeReportPass,
  parseDeploymentAuthority,
  parseGitHubCatalogTree,
  parseR2AuthoritySnapshot,
  parseWorkerAuthorityBinding,
  readPrivateFileSnapshot,
  validWranglerPackageBinding,
  validatePanelRuntimeContract,
  verifyR2AuthorityWithRunner
} from "./verify-private-release-evidence.mjs";

const MAX_PRIVATE_FILE_BYTES = 16 * 1024 * 1024;
const WORKER_VERSION_ID = "11111111-2222-3333-4444-555555555555";

function sha256(bytes) {
  return createHash("sha256").update(bytes).digest("hex");
}

test("Wrangler package binding accepts npm's equivalent relative bin forms", () => {
  const locked = {
    version: "4.113.0",
    integrity: `sha512-${"x".repeat(86)}`,
    bin: { wrangler: "bin/wrangler.js" }
  };
  assert.equal(validWranglerPackageBinding({
    name: "wrangler",
    version: "4.113.0",
    bin: { wrangler: "./bin/wrangler.js" }
  }, locked), true);
  assert.equal(validWranglerPackageBinding({
    name: "wrangler",
    version: "4.113.0",
    bin: { wrangler: "../bin/wrangler.js" }
  }, locked), false);
  assert.equal(validWranglerPackageBinding({
    name: "wrangler",
    version: "4.112.0",
    bin: { wrangler: "bin/wrangler.js" }
  }, locked), false);
});

test("current-upgrade evidence is exact, byte-bound, and fail closed", () => {
  const expected = {
    sourceCommit: "a".repeat(40),
    candidateManifestSha256: "b".repeat(64),
    apkSha256: "c".repeat(64),
    previousApkSha256: "d".repeat(64),
    panelConfigSha256: "e".repeat(64),
    panelCatalogSha256: "f".repeat(64),
    catalogVersion: 55
  };
  const report = {
    schemaVersion: 1,
    kind: "autoform-current-upgrade-release-evidence-v1",
    releaseReady: true,
    bindings: { ...expected },
    checks: {
      automaticUpdateProtocolVerified: true,
      currentPanelCatalogSmokeVerified: true,
      freshInstallVerified: true,
      legacyMatrixWaiverApproved: true,
      productionMutationAvoided: true,
      signedCurrentUpgradeVerified: true
    }
  };
  assert.equal(currentUpgradeReportPass(report, expected), true);
  assert.equal(currentUpgradeReportPass({
    ...report,
    checks: { ...report.checks, signedCurrentUpgradeVerified: false }
  }, expected), false);
  assert.equal(currentUpgradeReportPass({
    ...report,
    bindings: { ...report.bindings, apkSha256: "0".repeat(64) }
  }, expected), false);
  assert.equal(currentUpgradeReportPass({ ...report, extra: true }, expected), false);
});

async function privateFile(path, bytes) {
  await writeFile(path, bytes, { mode: 0o600 });
  await chmod(path, 0o600);
}

async function rejectsSnapshot(path, options = {}) {
  await assert.rejects(
    readPrivateFileSnapshot(path, "fixture", options),
    /stable bounded regular file with mode 0600/u
  );
}

test("private evidence reads one stable descriptor-bound snapshot", async () => {
  const root = await mkdtemp(join(tmpdir(), "autoform-private-evidence-selftest-"));
  try {
    const baseline = join(root, "baseline.json");
    await privateFile(baseline, Buffer.from('{"fixture":true}\n', "utf8"));
    const read = await readPrivateFileSnapshot(baseline, "fixture");
    assert.deepEqual(read.bytes, Buffer.from('{"fixture":true}\n', "utf8"));
    assert.match(read.sha256, /^[0-9a-f]{64}$/u);

    const looseMode = join(root, "loose-mode.json");
    await privateFile(looseMode, Buffer.from("{}\n", "utf8"));
    await chmod(looseMode, 0o640);
    await rejectsSnapshot(looseMode);

    const specialMode = join(root, "special-mode.json");
    await privateFile(specialMode, Buffer.from("{}\n", "utf8"));
    await chmod(specialMode, 0o1600);
    await rejectsSnapshot(specialMode);

    const empty = join(root, "empty.json");
    await privateFile(empty, Buffer.alloc(0));
    await rejectsSnapshot(empty);

    const oversized = join(root, "oversized.json");
    await privateFile(oversized, Buffer.alloc(MAX_PRIVATE_FILE_BYTES + 1, 0x20));
    await rejectsSnapshot(oversized);

    const linked = join(root, "linked.json");
    await symlink(baseline, linked);
    await rejectsSnapshot(linked);

    const replaced = join(root, "replaced.json");
    const replacement = join(root, "replacement.json");
    const displaced = join(root, "displaced.json");
    await privateFile(replaced, Buffer.from('{"fixture":"before"}\n', "utf8"));
    await privateFile(replacement, Buffer.from('{"fixture":"after"}\n', "utf8"));
    await rejectsSnapshot(replaced, {
      afterOpen: async () => {
        await rename(replaced, displaced);
        await rename(replacement, replaced);
      }
    });

    const mutated = join(root, "mutated.json");
    await privateFile(mutated, Buffer.from('{"fixture":"first"}\n', "utf8"));
    await rejectsSnapshot(mutated, {
      afterOpen: async () => {
        await writeFile(mutated, Buffer.from('{"fixture":"other"}\n', "utf8"));
      }
    });
  } finally {
    await rm(root, { recursive: true, force: true });
  }
});

test("deployment evidence discriminates GitHub and R2 authority", () => {
  const common = {
    panelBase: "https://panel.example.invalid/",
    catalogReadKey: "x".repeat(32)
  };
  assert.deepEqual(parseDeploymentAuthority({
    schemaVersion: 1,
    catalogRepository: "sample/private-catalog",
    catalogCommit: "a".repeat(40),
    ...common
  }).authority, {
    type: "github",
    repository: "sample/private-catalog",
    commit: "a".repeat(40)
  });
  assert.equal(parseDeploymentAuthority({
    schemaVersion: 1,
    catalogRepository: "sample/private-catalog",
    catalogCommit: "a".repeat(40),
    ...common
  }).legacyAuthorityInput, true);
  assert.deepEqual(parseDeploymentAuthority({
    schemaVersion: 2,
    catalogAuthority: {
      type: "github",
      accountId: "c".repeat(32),
      workerName: "sample-panel",
      repository: "sample/private-catalog",
      branch: "main",
      commit: "b".repeat(40)
    },
    ...common
  }).authority.type, "github");
  assert.deepEqual(parseDeploymentAuthority({
    schemaVersion: 2,
    catalogAuthority: {
      type: "r2",
      accountId: "c".repeat(32),
      workerName: "sample-panel",
      bucket: "sample-private-catalog",
      jurisdiction: null
    },
    ...common
  }).authority.type, "r2");
  assert.throws(() => parseDeploymentAuthority({
    schemaVersion: 2,
    catalogAuthority: {
      type: "r2",
      accountId: "C".repeat(32),
      workerName: "sample-panel",
      bucket: "sample-private-catalog",
      jurisdiction: null
    },
    ...common
  }), /R2 catalog authority is invalid/u);
  assert.throws(() => parseDeploymentAuthority({
    schemaVersion: 2,
    catalogAuthority: {
      type: "r2",
      accountId: "c".repeat(32),
      workerName: "sample-panel",
      bucket: "sample-private-catalog",
      jurisdiction: null,
      extra: true
    },
    ...common
  }), /R2 catalog authority is invalid/u);
});

test("GitHub tree binds required files and explicit optional settings presence", () => {
  const tree = (entries) => Buffer.from(JSON.stringify({
    sha: "f".repeat(40),
    url: "https://api.example.invalid/tree",
    truncated: false,
    tree: entries
  }), "utf8");
  const required = [
    { path: "form-profiles.json", type: "blob", sha: "a".repeat(40) },
    { path: "manifest.json", type: "blob", sha: "b".repeat(40) }
  ];
  assert.deepEqual(parseGitHubCatalogTree(tree(required)), {
    catalogBlobOid: "a".repeat(40),
    manifestBlobOid: "b".repeat(40),
    panelSettingsPresent: false,
    panelSettingsBlobOid: ""
  });
  assert.equal(parseGitHubCatalogTree(tree([
    ...required,
    { path: "panel-settings.json", type: "blob", sha: "c".repeat(40) }
  ])).panelSettingsPresent, true);
  assert.throws(
    () => parseGitHubCatalogTree(tree([required[0]])),
    /missing required files/u
  );
  assert.throws(
    () => parseGitHubCatalogTree(Buffer.from(JSON.stringify({
      truncated: true,
      tree: required
    }), "utf8")),
    /tree response is invalid/u
  );
});

test("Panel runtime and Worker authority bindings are exact and exclusive", () => {
  const runtime = {
    provenance: "cloudflare_version_tag",
    sourceCommit: "d".repeat(40),
    version: 1,
    versionCreatedAt: "2030-01-02T03:04:05.123Z",
    workerVersionId: WORKER_VERSION_ID
  };
  assert.deepEqual(validatePanelRuntimeContract(runtime), runtime);
  assert.throws(
    () => validatePanelRuntimeContract({ ...runtime, sourceCommit: "d".repeat(64) }),
    /unavailable or malformed/u
  );
  assert.throws(
    () => validatePanelRuntimeContract({ ...runtime, versionCreatedAt: "2030-02-30T00:00:00Z" }),
    /unavailable or malformed/u
  );

  const githubAuthority = {
    type: "github",
    accountId: "c".repeat(32),
    workerName: "sample-panel",
    repository: "sample/private-catalog",
    branch: "main",
    commit: "e".repeat(40)
  };
  const workerBytes = (bindings) => Buffer.from(JSON.stringify({
    id: WORKER_VERSION_ID,
    resources: { bindings }
  }), "utf8");
  assert.match(parseWorkerAuthorityBinding(githubAuthority, WORKER_VERSION_ID,
    workerBytes([{ name: "GITHUB_REPO", type: "plain_text", text: githubAuthority.repository }]),
    "main"),
  /^[0-9a-f]{64}$/u);
  assert.match(parseWorkerAuthorityBinding(
    githubAuthority,
    WORKER_VERSION_ID,
    workerBytes([
      { name: "GITHUB_REPO", type: "plain_text", text: githubAuthority.repository },
      { name: "GITHUB_BRANCH", type: "plain_text", text: "main" }
    ])
  ), /^[0-9a-f]{64}$/u);
  assert.throws(() => parseWorkerAuthorityBinding(
    githubAuthority,
    WORKER_VERSION_ID,
    workerBytes([
      { name: "GITHUB_REPO", type: "plain_text", text: githubAuthority.repository },
      { name: "CATALOG_R2", type: "r2_bucket", bucket_name: "unexpected" }
    ])
  ), /not bound to the declared GitHub authority/u);
  assert.throws(() => parseWorkerAuthorityBinding(
    githubAuthority,
    WORKER_VERSION_ID,
    workerBytes([
      { name: "GITHUB_REPO", type: "plain_text", text: githubAuthority.repository },
      { name: "GITHUB_BRANCH", type: "plain_text", text: "different" }
    ])
  ), /not bound to the declared GitHub authority/u);
  assert.throws(() => parseWorkerAuthorityBinding(
    githubAuthority,
    WORKER_VERSION_ID,
    workerBytes([
      { name: "GITHUB_REPO", type: "plain_text", text: githubAuthority.repository },
      { name: "GITHUB_REPO", type: "secret_text", text: githubAuthority.repository }
    ])
  ), /not bound to the declared GitHub authority/u);
});

function r2Fixture({ panelSettings = null } = {}) {
  const catalogText = `${JSON.stringify({
    schemaVersion: 2,
    version: 45,
    profiles: []
  }, null, 2)}\n`;
  const manifestText = `${JSON.stringify({
    schemaVersion: 2,
    version: 45,
    sha256: sha256(Buffer.from(catalogText, "utf8")),
    profilesUrl: "https://panel.example.invalid/catalog/form-profiles.json",
    minAppVersionCode: 1,
    updatedAt: "2030-01-01T00:00:00.000Z",
    notes: "fixture"
  }, null, 2)}\n`;
  const stateBytes = Buffer.from(`${JSON.stringify({
    schemaVersion: 1,
    parentStateSha256: null,
    files: {
      "form-profiles.json": catalogText,
      "manifest.json": manifestText,
      "panel-settings.json": panelSettings
    }
  }, null, 2)}\n`, "utf8");
  const stateSha256 = sha256(stateBytes);
  const pointerBytes = Buffer.from(`${JSON.stringify({
    schemaVersion: 1,
    snapshotKey: `catalog-snapshots-v1/${stateSha256}.json`,
    stateSha256,
    catalogVersion: 45
  }, null, 2)}\n`, "utf8");
  return { pointerBytes, stateBytes, catalogText, manifestText };
}

test("R2 authority binds exact current pointer, snapshot and optional settings", () => {
  const absent = r2Fixture();
  const parsedAbsent = parseR2AuthoritySnapshot(absent.pointerBytes, absent.stateBytes);
  assert.equal(parsedAbsent.catalogBytes.toString("utf8"), absent.catalogText);
  assert.equal(parsedAbsent.manifestBytes.toString("utf8"), absent.manifestText);
  assert.equal(parsedAbsent.panelSettingsBytes, null);

  const settingsText = '{"schemaVersion":1,"settings":{}}\n';
  const present = r2Fixture({ panelSettings: settingsText });
  const parsedPresent = parseR2AuthoritySnapshot(present.pointerBytes, present.stateBytes);
  assert.equal(parsedPresent.panelSettingsBytes.toString("utf8"), settingsText);
});

test("R2 authority rejects pointer, state and embedded-file drift", () => {
  const fixture = r2Fixture();
  const changedState = Buffer.from(fixture.stateBytes);
  changedState[changedState.length - 2] = 0x20;
  assert.throws(
    () => parseR2AuthoritySnapshot(fixture.pointerBytes, changedState),
    /snapshot bytes do not match/u
  );

  const pointer = JSON.parse(fixture.pointerBytes.toString("utf8"));
  pointer.snapshotKey = `catalog-snapshots-v1/${"0".repeat(64)}.json`;
  assert.throws(
    () => parseR2AuthoritySnapshot(
      Buffer.from(`${JSON.stringify(pointer)}\n`, "utf8"), fixture.stateBytes),
    /pointer contract is invalid/u
  );

  const state = JSON.parse(fixture.stateBytes.toString("utf8"));
  state.files["manifest.json"] = state.files["manifest.json"].replace(
    /"version": 45/u, '"version": 44');
  const stateBytes = Buffer.from(`${JSON.stringify(state, null, 2)}\n`, "utf8");
  const stateSha256 = sha256(stateBytes);
  const reboundPointer = Buffer.from(`${JSON.stringify({
    schemaVersion: 1,
    snapshotKey: `catalog-snapshots-v1/${stateSha256}.json`,
    stateSha256,
    catalogVersion: 45
  })}\n`, "utf8");
  assert.throws(
    () => parseR2AuthoritySnapshot(reboundPointer, stateBytes),
    /catalog files do not match/u
  );
});

function fixtureR2Runner(fixture, {
  publicDevUrl = false,
  customDomain = false,
  pointerDrift = false,
  workerBucket = "sample-private-catalog",
  workerBindingDrift = false,
  privateSurfaceDrift = false
} = {}) {
  const calls = [];
  let pointerReads = 0;
  let workerReads = 0;
  let domainReads = 0;
  const runner = (args) => {
    calls.push([...args]);
    const command = args.join(" ");
    if (command.startsWith("versions view ")) {
      workerReads += 1;
      return Buffer.from(`${JSON.stringify({
        id: WORKER_VERSION_ID,
        resources: {
          bindings: [{
            name: "CATALOG_R2",
            type: "r2_bucket",
            bucket_name: workerBindingDrift && workerReads > 1
              ? "different-private-catalog"
              : workerBucket,
            jurisdiction: "eu"
          }]
        }
      })}\n`, "utf8");
    }
    if (command.startsWith("r2 bucket info ")) {
      return Buffer.from('{"name":"sample-private-catalog"}\n', "utf8");
    }
    if (command.startsWith("r2 bucket dev-url get ")) {
      return Buffer.from(publicDevUrl
        ? "Public access is enabled at 'https://public.example.invalid'.\n"
        : "Public access via the r2.dev URL is disabled.\n", "utf8");
    }
    if (command.startsWith("r2 bucket domain list ")) {
      domainReads += 1;
      return Buffer.from(customDomain || (privateSurfaceDrift && domainReads > 1)
        ? "example.invalid is connected to this bucket.\n"
        : "There are no custom domains connected to this bucket.\n", "utf8");
    }
    if (command.includes("/catalog-current-v1.json")) {
      pointerReads += 1;
      return pointerDrift && pointerReads > 1
        ? Buffer.concat([fixture.pointerBytes, Buffer.from(" ", "utf8")])
        : fixture.pointerBytes;
    }
    if (command.includes("/catalog-snapshots-v1/")) return fixture.stateBytes;
    throw new Error(`unexpected fixture command: ${command}`);
  };
  return { runner, calls };
}

test("R2 live verifier checks privacy and re-reads the exact current pointer", async () => {
  const fixture = r2Fixture();
  const authority = {
    type: "r2",
    accountId: "c".repeat(32),
    workerName: "sample-panel",
    bucket: "sample-private-catalog",
    jurisdiction: "eu"
  };
  const stable = fixtureR2Runner(fixture);
  const verified = await verifyR2AuthorityWithRunner(
    authority, WORKER_VERSION_ID, stable.runner);
  assert.equal(verified.catalogBytes.toString("utf8"), fixture.catalogText);
  assert.equal(verified.authorityRevision, JSON.parse(
    fixture.pointerBytes.toString("utf8")).stateSha256);
  await verified.assertStillCurrent();
  assert.equal(stable.calls.length, 11);
  const stateSha256 = JSON.parse(fixture.pointerBytes.toString("utf8")).stateSha256;
  assert.deepEqual(stable.calls, [
    ["versions", "view", WORKER_VERSION_ID, "--name", "sample-panel", "--json"],
    ["r2", "bucket", "info", "sample-private-catalog", "--json", "--jurisdiction", "eu"],
    ["r2", "bucket", "dev-url", "get", "sample-private-catalog", "--jurisdiction", "eu"],
    ["r2", "bucket", "domain", "list", "sample-private-catalog", "--jurisdiction", "eu"],
    ["r2", "object", "get", "sample-private-catalog/catalog-current-v1.json",
      "--remote", "--pipe", "--jurisdiction", "eu"],
    ["r2", "object", "get",
      `sample-private-catalog/catalog-snapshots-v1/${stateSha256}.json`,
      "--remote", "--pipe", "--jurisdiction", "eu"],
    ["versions", "view", WORKER_VERSION_ID, "--name", "sample-panel", "--json"],
    ["r2", "bucket", "info", "sample-private-catalog", "--json", "--jurisdiction", "eu"],
    ["r2", "bucket", "dev-url", "get", "sample-private-catalog", "--jurisdiction", "eu"],
    ["r2", "bucket", "domain", "list", "sample-private-catalog", "--jurisdiction", "eu"],
    ["r2", "object", "get", "sample-private-catalog/catalog-current-v1.json",
      "--remote", "--pipe", "--jurisdiction", "eu"]
  ]);
  assert.ok(stable.calls.filter((args) => args[0] === "r2")
    .every((args) => args.includes("--jurisdiction") && args.includes("eu")));
  assert.ok(stable.calls.filter((args) => args[0] === "r2" && args[1] === "object")
    .every((args) => args.includes("--remote") && args.includes("--pipe")));

  await assert.rejects(
    verifyR2AuthorityWithRunner(authority, WORKER_VERSION_ID,
      fixtureR2Runner(fixture, { publicDevUrl: true }).runner),
    /must not expose a public dev URL/u
  );
  await assert.rejects(
    verifyR2AuthorityWithRunner(authority, WORKER_VERSION_ID,
      fixtureR2Runner(fixture, { customDomain: true }).runner),
    /must not expose a public dev URL/u
  );
  await assert.rejects(
    verifyR2AuthorityWithRunner(authority, WORKER_VERSION_ID,
      fixtureR2Runner(fixture, { workerBucket: "different-private-catalog" }).runner),
    /not bound to the declared R2 authority/u
  );

  const bindingDrift = await verifyR2AuthorityWithRunner(
    authority,
    WORKER_VERSION_ID,
    fixtureR2Runner(fixture, { workerBindingDrift: true }).runner
  );
  await assert.rejects(
    bindingDrift.assertStillCurrent(),
    /not bound to the declared R2 authority/u
  );

  const surfaceDrift = await verifyR2AuthorityWithRunner(
    authority,
    WORKER_VERSION_ID,
    fixtureR2Runner(fixture, { privateSurfaceDrift: true }).runner
  );
  await assert.rejects(
    surfaceDrift.assertStillCurrent(),
    /must not expose a public dev URL/u
  );

  const drifting = fixtureR2Runner(fixture, { pointerDrift: true });
  const drifted = await verifyR2AuthorityWithRunner(
    authority, WORKER_VERSION_ID, drifting.runner);
  await assert.rejects(
    drifted.assertStillCurrent(),
    /current pointer changed during verification/u
  );
});
