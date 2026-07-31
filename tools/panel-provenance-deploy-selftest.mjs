#!/usr/bin/env node

import assert from "node:assert/strict";
import { mkdirSync, mkdtempSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";

import {
  hasExactVersionMetadataBinding,
  preparePanelProvenanceDeploy,
  runPanelProvenanceDeploy
} from "./deploy-panel-with-provenance.mjs";

const SOURCE_COMMIT = "a".repeat(40);
const root = mkdtempSync(join(tmpdir(), "autoform-panel-deploy-selftest-"));
const panelDir = join(root, "panel");
const configPath = join(panelDir, "wrangler.local.toml");
const wranglerBin = join(panelDir, "node_modules", "wrangler", "bin", "wrangler.js");
mkdirSync(join(panelDir, "node_modules", "wrangler", "bin"), { recursive: true });
writeFileSync(wranglerBin, "#!/usr/bin/env node\n", "utf8");

function gitRunner({ dirty = false, head = SOURCE_COMMIT } = {}) {
  return (args) => {
    if (args[0] === "status") {
      return { status: 0, stdout: dirty ? " M panel/src/worker.js\n" : "", stderr: "" };
    }
    if (args[0] === "rev-parse") {
      return { status: 0, stdout: `${head}\n`, stderr: "" };
    }
    return { status: 1, stdout: "", stderr: "unexpected" };
  };
}

try {
  const validConfig = [
    'name = "generic-panel"',
    "[version_metadata]",
    'binding = "CF_VERSION_METADATA"',
    "[vars]",
    'PRIVATE_MARKER = "must-never-be-printed"'
  ].join("\n");
  assert.equal(hasExactVersionMetadataBinding(validConfig), true);
  assert.equal(hasExactVersionMetadataBinding(
    '[version_metadata]\nbinding = "WRONG_BINDING"\n'), false);

  writeFileSync(configPath, validConfig, "utf8");
  assert.throws(() => preparePanelProvenanceDeploy({
    repoRoot: root,
    configPath,
    wranglerBin,
    gitRunner: gitRunner({ dirty: true })
  }), /worktree must be completely clean/u);

  writeFileSync(configPath, 'name = "generic-panel"\n', "utf8");
  assert.throws(() => preparePanelProvenanceDeploy({
    repoRoot: root,
    configPath,
    wranglerBin,
    gitRunner: gitRunner()
  }), (error) => {
    assert.match(error.message, /exact CF_VERSION_METADATA binding/u);
    assert.equal(error.message.includes("must-never-be-printed"), false);
    return true;
  });

  writeFileSync(configPath, validConfig, "utf8");
  for (const argv of [
    ["--tag", `autoform-source-${"b".repeat(40)}`],
    ["--config", "other.toml"],
    ["--no-strict"]
  ]) {
    assert.throws(() => runPanelProvenanceDeploy({
      argv,
      repoRoot: root,
      configPath,
      wranglerBin,
      gitRunner: gitRunner(),
      spawnImpl() {
        throw new Error("must not spawn");
      }
    }), /accepts no arguments/u);
  }

  let observed;
  const result = runPanelProvenanceDeploy({
    argv: [],
    repoRoot: root,
    configPath,
    wranglerBin,
    gitRunner: gitRunner(),
    spawnImpl(command, args, options) {
      observed = { command, args, options };
      return {
        status: 0,
        stdout: Buffer.from("private URL and vars must stay captured"),
        stderr: Buffer.alloc(0)
      };
    }
  });
  assert.deepEqual(result, { ok: true });
  assert.equal(observed.command, process.execPath);
  assert.deepEqual(observed.args, [
    wranglerBin,
    "deploy",
    "--config",
    configPath,
    "--strict",
    "--tag",
    `autoform-source-${SOURCE_COMMIT}`
  ]);
  assert.deepEqual(observed.options.stdio, ["inherit", "pipe", "pipe"]);
  assert.equal(observed.options.env.WRANGLER_LOG, "error");
  assert.equal(observed.options.maxBuffer, 1024 * 1024);

  assert.throws(() => runPanelProvenanceDeploy({
    argv: [],
    repoRoot: root,
    configPath,
    wranglerBin,
    gitRunner: gitRunner(),
    spawnImpl() {
      return {
        status: 7,
        stdout: Buffer.from("https://private-panel.test.invalid"),
        stderr: Buffer.from("PRIVATE_MARKER")
      };
    }
  }), (error) => {
    assert.match(error.message, /exit code 7/u);
    assert.equal(error.message.includes("private-panel"), false);
    assert.equal(error.message.includes("PRIVATE_MARKER"), false);
    return true;
  });
} finally {
  rmSync(root, { recursive: true, force: true });
}

process.stdout.write("Panel provenance deploy self-test: passed\n");
