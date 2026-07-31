#!/usr/bin/env node

// This prevents accidental dirty/manual-tag deployments. It does not cryptographically bind the
// uploaded bundle bytes to Git; signed bundle/build attestation remains an external release gate.

import { spawnSync } from "node:child_process";
import { lstatSync, readFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath, pathToFileURL } from "node:url";

const SOURCE_COMMIT_PATTERN = /^[0-9a-f]{40}$/u;
const MAX_CONFIG_BYTES = 1024 * 1024;
const MAX_WRANGLER_OUTPUT_BYTES = 1024 * 1024;
const DEFAULT_REPO_ROOT = dirname(dirname(fileURLToPath(import.meta.url)));

function regularFile(path, label) {
  try {
    const stat = lstatSync(path);
    if (!stat.isFile() || stat.isSymbolicLink()) throw new Error("invalid");
    return stat;
  } catch {
    throw new Error(`${label} is missing or is not a regular file`);
  }
}

export function hasExactVersionMetadataBinding(text) {
  if (typeof text !== "string") return false;
  let inVersionMetadata = false;
  let sectionCount = 0;
  let bindingCount = 0;
  for (const rawLine of text.split(/\r?\n/u)) {
    const line = rawLine.trim();
    if (!line || line.startsWith("#")) continue;
    const section = line.match(/^\[([A-Za-z0-9_.-]+)\]\s*(?:#.*)?$/u);
    if (section) {
      inVersionMetadata = section[1] === "version_metadata";
      if (inVersionMetadata) sectionCount++;
      continue;
    }
    if (inVersionMetadata
        && /^binding\s*=\s*["']CF_VERSION_METADATA["']\s*(?:#.*)?$/u.test(line)) {
      bindingCount++;
    }
  }
  return sectionCount === 1 && bindingCount === 1;
}

function defaultGitRunner(args, cwd) {
  return spawnSync("git", args, {
    cwd,
    encoding: "utf8",
    stdio: ["ignore", "pipe", "pipe"],
    maxBuffer: 1024 * 1024
  });
}

function gitOutput(result, label) {
  if (!result || result.error || result.status !== 0 || typeof result.stdout !== "string") {
    throw new Error(`${label} could not be verified`);
  }
  return result.stdout;
}

export function preparePanelProvenanceDeploy({
  repoRoot = DEFAULT_REPO_ROOT,
  gitRunner = defaultGitRunner,
  configPath = join(repoRoot, "panel", "wrangler.local.toml"),
  wranglerBin = join(repoRoot, "panel", "node_modules", "wrangler", "bin", "wrangler.js")
} = {}) {
  const dirty = gitOutput(gitRunner(
    ["status", "--porcelain=v1", "--untracked-files=all"], repoRoot),
  "Git worktree");
  if (dirty !== "") {
    throw new Error("Git worktree must be completely clean before Panel deployment");
  }
  const head = gitOutput(gitRunner(
    ["rev-parse", "--verify", "HEAD^{commit}"], repoRoot),
  "Git HEAD").trim();
  if (!SOURCE_COMMIT_PATTERN.test(head)) {
    throw new Error("Git HEAD must be one exact lowercase 40-character commit");
  }

  const configStat = regularFile(configPath, "private Wrangler config");
  if (configStat.size <= 0 || configStat.size > MAX_CONFIG_BYTES) {
    throw new Error("private Wrangler config has an invalid size");
  }
  let configText;
  try {
    configText = readFileSync(configPath, "utf8");
  } catch {
    throw new Error("private Wrangler config could not be read");
  }
  if (!hasExactVersionMetadataBinding(configText)) {
    throw new Error("private Wrangler config must contain the exact CF_VERSION_METADATA binding");
  }
  regularFile(wranglerBin, "local Wrangler executable");

  return Object.freeze({
    repoRoot,
    panelDir: join(repoRoot, "panel"),
    configPath,
    wranglerBin,
    sourceCommit: head,
    sourceTag: `autoform-source-${head}`
  });
}

export function runPanelProvenanceDeploy({
  argv = process.argv.slice(2),
  repoRoot = DEFAULT_REPO_ROOT,
  gitRunner = defaultGitRunner,
  spawnImpl = spawnSync,
  configPath = join(repoRoot, "panel", "wrangler.local.toml"),
  wranglerBin = join(repoRoot, "panel", "node_modules", "wrangler", "bin", "wrangler.js")
} = {}) {
  if (!Array.isArray(argv) || argv.length !== 0) {
    throw new Error("Panel deploy accepts no arguments; config, strict mode, and source tag are fixed");
  }
  const plan = preparePanelProvenanceDeploy({
    repoRoot,
    gitRunner,
    configPath,
    wranglerBin
  });
  const args = [
    plan.wranglerBin,
    "deploy",
    "--config",
    plan.configPath,
    "--strict",
    "--tag",
    plan.sourceTag
  ];
  const result = spawnImpl(process.execPath, args, {
    cwd: plan.panelDir,
    env: { ...process.env, WRANGLER_LOG: "error" },
    // Keep normal stdin confirmation available, but never echo deployment URLs, bindings,
    // private variables, or provider diagnostics from Wrangler.
    stdio: ["inherit", "pipe", "pipe"],
    maxBuffer: MAX_WRANGLER_OUTPUT_BYTES
  });
  if (!result || result.error || result.status !== 0) {
    const exitCode = Number.isInteger(result?.status) ? result.status : "unknown";
    throw new Error(
      `local Wrangler failed with exit code ${exitCode}; inspect local Wrangler/Cloudflare status directly`);
  }
  return { ok: true };
}

if (import.meta.url === pathToFileURL(process.argv[1] || "").href) {
  try {
    runPanelProvenanceDeploy();
    process.stdout.write("Panel deployment completed through the clean source-tag gate.\n");
  } catch (error) {
    process.stderr.write(`Panel deployment blocked: ${error.message}\n`);
    process.exitCode = 1;
  }
}
