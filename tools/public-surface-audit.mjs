#!/usr/bin/env node

/**
 * Deterministic, value-redacting audit for public release surfaces.
 *
 * The scanner deliberately reports rule identifiers and locations only. It never
 * emits matched text, surrounding snippets, private-wordlist paths, or private
 * terms. One and only one input mode is required:
 *
 *   node tools/public-surface-audit.mjs --git-tree HEAD [--repo DIR]
 *   node tools/public-surface-audit.mjs --worktree [--repo DIR]
 *   node tools/public-surface-audit.mjs --apk FILE
 *   node tools/public-surface-audit.mjs --file FILE
 *
 * Repeat --private-wordlist FILE to add private terms. A .json file is either an array of strings
 * or strict { term, matchMode } entries; other files are newline-delimited literals with empty
 * lines and # comments ignored. The list stays external; neither its path, terms, nor a reversible
 * identifier is written to the report.
 */

import crypto from "node:crypto";
import fs from "node:fs";
import path from "node:path";
import process from "node:process";
import { spawnSync } from "node:child_process";
import { fileURLToPath } from "node:url";
import zlib from "node:zlib";

const SCHEMA_VERSION = 1;
const MAX_SCAN_BYTES = 128 * 1024 * 1024;
const MAX_APK_TOTAL_UNCOMPRESSED_BYTES = 512 * 1024 * 1024;
const MAX_COMMAND_BUFFER = 512 * 1024 * 1024;
const MAX_PRIVATE_WORDLIST_BYTES = 8 * 1024 * 1024;
const MAX_PRIVATE_TERMS = 20_000;
const SCANNER_FILENAME = fileURLToPath(import.meta.url);
const SCANNER_ROOT = path.resolve(path.dirname(SCANNER_FILENAME), "..");
const APK_THIRD_PARTY_POLICY_FILE = path.join(
  path.dirname(SCANNER_FILENAME), "apk-third-party-components.json",
);

const POLICY = Object.freeze({
  version: 1,
  allowedRepositoryAssets: [
    "app/assets/form-profiles.seed.json",
    "app/assets/update-config.json",
    "app/src/debug/assets/update-config.json",
    "panel/public/backend-client.js",
    "panel/public/form-preview.js",
    "panel/public/index.html",
  ],
  allowedApkAssets: [
    "assets/form-profiles.seed.json",
    "assets/update-config.json",
  ],
  strictProtocolDocuments: [
    "docs/app-pairing.md",
  ],
  safeEndpointHosts: [
    "example.com",
    "example.org",
    "example.net",
    "localhost",
    "127.0.0.1",
    "::1",
    "github.com",
    "api.github.com",
    "raw.githubusercontent.com",
    "objects.githubusercontent.com",
  ],
});

const CONFIG_EXTENSIONS = new Set([
  ".cfg", ".conf", ".env", ".gradle", ".ini", ".json", ".properties",
  ".toml", ".xml", ".yaml", ".yml",
]);

const HIGH_CONFIDENCE_PATTERNS = Object.freeze([
  ["private-key-material", /-----BEGIN (?:RSA |EC |OPENSSH |DSA )?PRIVATE KEY-----/g],
  ["credential-token", /\b(?:AKIA|ASIA)[A-Z0-9]{16}\b/g],
  ["credential-token", /\bAIza[0-9A-Za-z_-]{35}\b/g],
  ["credential-token", /\bgh(?:p|o|u|s|r)_[A-Za-z0-9]{30,255}\b/g],
  ["credential-token", /\bgithub_pat_[A-Za-z0-9_]{40,255}\b/g],
  ["credential-token", /\b(?:glpat|gldt|glrt)-[A-Za-z0-9_-]{20,255}\b/g],
  ["credential-token", /\bnpm_[A-Za-z0-9]{36,255}\b/g],
  ["credential-token", /\bxox[baprs]-[A-Za-z0-9-]{10,255}\b/g],
  ["credential-token", /\bsk_(?:live|test)_[A-Za-z0-9]{16,255}\b/g],
  ["credential-token", /\beyJ[A-Za-z0-9_-]{8,}\.[A-Za-z0-9_-]{8,}\.[A-Za-z0-9_-]{8,}\b/g],
  ["basic-auth-url", /\b(?:https?|wss?|postgres(?:ql)?|mysql):\/\/[^\s\/@:]{1,128}:[^\s\/@]{1,128}@/gi],
]);

const EMAIL_PATTERN = /\b[A-Z0-9._%+-]{1,64}@[A-Z0-9.-]{1,190}\.[A-Z]{2,63}\b/gi;
const E164_PATTERN = /(?:^|[^\d])\+[1-9]\d{7,14}(?!\d)/g;
const CN_MOBILE_PATTERN = /(?:^|[^\d])1[3-9]\d{9}(?!\d)/g;
const CN_ID_PATTERN = /(?:^|[^\d])\d{6}(?:19|20)\d{2}(?:0[1-9]|1[0-2])(?:0[1-9]|[12]\d|3[01])\d{3}[0-9Xx](?!\d)/g;
const MAC_PATTERN = /\b(?:[0-9A-F]{2}[:-]){5}[0-9A-F]{2}\b/gi;
const IPV4_PATTERN = /\b(?:\d{1,3}\.){3}\d{1,3}\b/g;
const URL_PATTERN = /\b(?:https?|wss?):\/\/[^\s"'<>]+/gi;
const SECRET_ASSIGNMENT_PATTERN = /(?:^|[,{\s])["']?(?:api[-_.]?key|client[-_.]?secret|access[-_.]?token|auth[-_.]?token|password|passwd|private[-_.]?key|webhook[-_.]?url)["']?\s*[:=]\s*(?:"([^"\r\n]*)"|'([^'\r\n]*)'|([^\s,;}]+))/gim;
const PAIRING_PROTOCOL_CREDENTIAL_ASSIGNMENT_PATTERN = /(?:^|[,{\s])["']?(?:access[-_.]?key|api[-_.]?key|auth[-_.]?token|backend[-_.]?token|catalog[-_.]?(?:read[-_.]?)?key|client[-_.]?secret|pair(?:ing)?[-_.]?ticket|password|passwd|private[-_.]?key|ticket)["']?\s*[:=]\s*(?:"([^"\r\n]*)"|'([^'\r\n]*)'|([^\s,;}]+))/gim;
const PAIRING_PROTOCOL_QUERY_CREDENTIAL_PATTERN = /[?&](?:access[-_.]?key|auth[-_.]?token|backend[-_.]?token|catalog[-_.]?(?:read[-_.]?)?key|pair(?:ing)?[-_.]?ticket|ticket)=([^&#\s"'<>`]+)/gi;
const ENDPOINT_ASSIGNMENT_PATTERN = /(?:^|[,{\s])["']?(?:base[-_.]?(?:url|uri)|url|uri|endpoint|origin|host)["']?\s*[:=]\s*(?:"([^"\r\n]*)"|'([^'\r\n]*)'|([^\s,;}]+))/gim;

function sha256(data) {
  return crypto.createHash("sha256").update(data).digest("hex");
}

function canonicalize(value) {
  if (Array.isArray(value)) return value.map(canonicalize);
  if (value && typeof value === "object") {
    const output = {};
    for (const key of Object.keys(value).sort()) output[key] = canonicalize(value[key]);
    return output;
  }
  return value;
}

function canonicalJson(value) {
  return JSON.stringify(canonicalize(value));
}

function exactKeys(value, expected) {
  return value && typeof value === "object" && !Array.isArray(value)
    && JSON.stringify(Object.keys(value).sort()) === JSON.stringify([...expected].sort());
}

function loadApkThirdPartyPolicy() {
  const bytes = fs.readFileSync(APK_THIRD_PARTY_POLICY_FILE);
  const parsed = JSON.parse(bytes.toString("utf8"));
  if (!exactKeys(parsed, [
    "schemaVersion", "appNamespaceDescriptorPrefixes", "protectedEntryPrefixes",
    "protectedEntryBasenames", "buildBindings", "components", "profiles",
  ]) || parsed.schemaVersion !== 1
      || !Array.isArray(parsed.appNamespaceDescriptorPrefixes)
      || parsed.appNamespaceDescriptorPrefixes.length === 0
      || parsed.appNamespaceDescriptorPrefixes.some((value) =>
        typeof value !== "string" || !/^L(?:[A-Za-z_$][A-Za-z0-9_$]*\/)+$/.test(value))
      || !Array.isArray(parsed.protectedEntryPrefixes)
      || parsed.protectedEntryPrefixes.some((value) =>
        typeof value !== "string" || value.length === 0 || !value.endsWith("/")
          || isUnsafeArchivePath(value.slice(0, -1)))
      || !Array.isArray(parsed.protectedEntryBasenames)
      || parsed.protectedEntryBasenames.some((value) =>
        typeof value !== "string" || value.length === 0 || value.includes("/") || value.includes("\\"))
      || !exactKeys(parsed.buildBindings, ["androidGradlePlugin", "files"])
      || typeof parsed.buildBindings.androidGradlePlugin !== "string"
      || !Array.isArray(parsed.buildBindings.files)
      || parsed.buildBindings.files.length === 0
      || !Array.isArray(parsed.components) || parsed.components.length === 0
      || !Array.isArray(parsed.profiles) || parsed.profiles.length === 0) {
    throw new Error("invalid APK third-party provenance policy");
  }

  const bindingPaths = new Set();
  for (const binding of parsed.buildBindings.files) {
    if (!exactKeys(binding, ["path", "sha256"]) || typeof binding.path !== "string"
        || isUnsafeArchivePath(binding.path) || !/^[0-9a-f]{64}$/.test(binding.sha256)
        || bindingPaths.has(binding.path)) {
      throw new Error("invalid APK provenance build binding");
    }
    bindingPaths.add(binding.path);
    const filename = path.resolve(SCANNER_ROOT, binding.path);
    if (!inside(SCANNER_ROOT, filename)) throw new Error("APK provenance binding escaped source root");
    const stat = fs.lstatSync(filename);
    if (!stat.isFile() || stat.isSymbolicLink() || sha256(fs.readFileSync(filename)) !== binding.sha256) {
      throw new Error("APK provenance build binding does not match source");
    }
  }

  const components = new Map();
  const dexStringSources = new Map();
  for (const component of parsed.components) {
    const componentKeys = [
      "id", "coordinate", "version", "license", "sourceArtifactSha256", "provenance",
      ...(component.dexStringSources === undefined ? [] : ["dexStringSources"]),
      ...(component.runtimeProfileSources === undefined ? [] : ["runtimeProfileSources"]),
    ];
    if (!exactKeys(component, componentKeys)
      || ![component.id, component.coordinate, component.version, component.license,
      component.provenance].every((value) => typeof value === "string" && value.length > 0)
      || !/^[a-z0-9][a-z0-9._-]*$/.test(component.id)
      || !/^[0-9a-f]{64}$/.test(component.sourceArtifactSha256)
      || components.has(component.id)) {
      throw new Error("invalid APK provenance component");
    }
    if (component.dexStringSources !== undefined) {
      if (!Array.isArray(component.dexStringSources) || component.dexStringSources.length === 0) {
        throw new Error("invalid APK provenance DEX string sources");
      }
      const sourcePaths = new Set();
      for (const source of component.dexStringSources) {
        if (!exactKeys(source, ["path", "sha256", "stringSha256"])
            || typeof source.path !== "string" || source.path.length === 0
            || source.path.startsWith("/") || source.path.includes("\\")
            || source.path.split("!/").some((part) => isUnsafeArchivePath(part))
            || !/^[0-9a-f]{64}$/.test(source.sha256)
            || !Array.isArray(source.stringSha256) || source.stringSha256.length === 0
            || source.stringSha256.some((value) => !/^[0-9a-f]{64}$/.test(value))
            || new Set(source.stringSha256).size !== source.stringSha256.length
            || sourcePaths.has(source.path)) {
          throw new Error("invalid APK provenance DEX string source");
        }
        sourcePaths.add(source.path);
        for (const digest of source.stringSha256) {
          if (dexStringSources.has(digest)) {
            throw new Error("duplicate APK provenance DEX string digest");
          }
          dexStringSources.set(digest, {
            component: component.id,
            sourcePath: source.path,
            sourceSha256: source.sha256,
          });
        }
      }
    }
    if (component.runtimeProfileSources !== undefined) {
      const source = component.runtimeProfileSources;
      if (!exactKeys(source, ["mergedTextSha256", "artifacts"])
          || source.mergedTextSha256 !== component.sourceArtifactSha256
          || !Array.isArray(source.artifacts) || source.artifacts.length === 0) {
        throw new Error("invalid APK runtime-profile sources");
      }
      const coordinates = new Set();
      for (const artifact of source.artifacts) {
        if (!exactKeys(artifact, [
          "coordinate", "artifactSha256", "entryPath", "entrySha256",
        ]) || typeof artifact.coordinate !== "string" || artifact.coordinate.length === 0
            || !/^[0-9a-f]{64}$/.test(artifact.artifactSha256)
            || artifact.entryPath !== "baseline-prof.txt"
            || !/^[0-9a-f]{64}$/.test(artifact.entrySha256)
            || coordinates.has(artifact.coordinate)) {
          throw new Error("invalid APK runtime-profile source artifact");
        }
        coordinates.add(artifact.coordinate);
      }
    }
    components.set(component.id, component);
  }

  const profileIds = new Set();
  for (const profile of parsed.profiles) {
    const profileKeys = [
      "id", "buildType", "generatedBy", "entries",
      ...(profile.dexStringComponents === undefined ? [] : ["dexStringComponents"]),
    ];
    if (!exactKeys(profile, profileKeys)
        || !/^[a-z0-9][a-z0-9._-]*$/.test(profile.id)
        || typeof profile.buildType !== "string" || profile.buildType.length === 0
        || typeof profile.generatedBy !== "string" || profile.generatedBy.length === 0
        || !Array.isArray(profile.entries) || profile.entries.length === 0
        || profileIds.has(profile.id)) {
      throw new Error("invalid APK provenance profile");
    }
    profileIds.add(profile.id);
    const paths = new Set();
    for (const entry of profile.entries) {
      if (!exactKeys(entry, ["path", "sha256", "component", "kind"])
          || typeof entry.path !== "string" || isUnsafeArchivePath(entry.path)
          || !/^[0-9a-f]{64}$/.test(entry.sha256)
          || !components.has(entry.component)
          || !["audio", "compiled-resource", "dex", "model", "native-library",
            "runtime-profile"].includes(entry.kind)
          || paths.has(entry.path)) {
        throw new Error("invalid APK provenance entry");
      }
      if ((entry.kind === "dex") !== /^classes(?:\d+)?\.dex$/.test(entry.path)) {
        throw new Error("APK provenance dex kind/path mismatch");
      }
      if (entry.kind === "runtime-profile"
          && components.get(entry.component).runtimeProfileSources === undefined) {
        throw new Error("APK runtime profile is missing exact source provenance");
      }
      paths.add(entry.path);
    }
    if (profile.dexStringComponents !== undefined
        && (!Array.isArray(profile.dexStringComponents)
          || profile.dexStringComponents.length === 0
          || new Set(profile.dexStringComponents).size !== profile.dexStringComponents.length
          || profile.dexStringComponents.some((id) =>
            typeof id !== "string" || !components.has(id)
              || components.get(id).dexStringSources === undefined))) {
      throw new Error("invalid APK provenance DEX string component selection");
    }
  }

  const manifestSha256 = sha256(bytes);
  return {
    ...parsed,
    components,
    dexStringSources,
    manifestSha256,
    policySha256: sha256(canonicalJson({
      publicSurfacePolicy: POLICY,
      apkThirdPartyManifestSha256: manifestSha256,
    })),
  };
}

function command(commandName, args, options = {}) {
  const result = spawnSync(commandName, args, {
    cwd: options.cwd,
    encoding: options.encoding ?? null,
    maxBuffer: options.maxBuffer ?? MAX_COMMAND_BUFFER,
    stdio: ["ignore", "pipe", "pipe"],
  });
  if (result.error) throw new Error(`${commandName} could not be executed`);
  if (result.status !== 0) throw new Error(`${commandName} exited unsuccessfully`);
  return result.stdout;
}

function parseArgs(argv) {
  const options = {
    mode: null,
    repo: process.cwd(),
    privateWordlists: [],
    pretty: false,
  };
  const requireValue = (flag, index) => {
    if (index + 1 >= argv.length || argv[index + 1].startsWith("--")) {
      throw new Error(`${flag} requires a value`);
    }
    return argv[index + 1];
  };

  for (let index = 0; index < argv.length; index += 1) {
    const argument = argv[index];
    if (argument === "--worktree") {
      selectMode(options, "worktree", null);
    } else if (argument === "--git-tree") {
      const value = requireValue(argument, index);
      selectMode(options, "git-tree", value);
      index += 1;
    } else if (argument === "--apk") {
      const value = requireValue(argument, index);
      selectMode(options, "apk", value);
      index += 1;
    } else if (argument === "--file") {
      const value = requireValue(argument, index);
      selectMode(options, "file", value);
      index += 1;
    } else if (argument === "--repo") {
      options.repo = path.resolve(requireValue(argument, index));
      index += 1;
    } else if (argument === "--private-wordlist") {
      options.privateWordlists.push(path.resolve(requireValue(argument, index)));
      index += 1;
    } else if (argument === "--pretty") {
      options.pretty = true;
    } else if (argument === "--help" || argument === "-h") {
      options.help = true;
    } else {
      throw new Error("unknown argument");
    }
  }
  if (!options.help && options.mode === null) throw new Error("one input mode is required");
  return options;
}

function selectMode(options, mode, value) {
  if (options.mode !== null) throw new Error("input modes are mutually exclusive");
  options.mode = mode;
  options.input = value;
}

function usage() {
  return [
    "Usage:",
    "  public-surface-audit.mjs --git-tree REV [--repo DIR] [options]",
    "  public-surface-audit.mjs --worktree [--repo DIR] [options]",
    "  public-surface-audit.mjs --apk FILE [options]",
    "  public-surface-audit.mjs --file FILE [options]",
    "",
    "Options:",
    "  --private-wordlist FILE  Add private terms: line list or JSON string/strict-mode entries",
    "                           (repeatable)",
    "  --pretty                 Pretty-print the JSON report",
    "  --help                   Show this help",
  ].join("\n");
}

function readPrivateTerms(files) {
  const terms = [];
  for (const filename of files) {
    const stat = fs.statSync(filename);
    if (!stat.isFile() || stat.size > MAX_PRIVATE_WORDLIST_BYTES) {
      throw new Error("private wordlist is not a supported regular file");
    }
    const data = fs.readFileSync(filename, "utf8");
    let values;
    if (path.extname(filename).toLowerCase() === ".json") {
      const parsed = JSON.parse(data);
      if (!Array.isArray(parsed) || parsed.some((value) =>
        typeof value !== "string" && (!value || typeof value !== "object"
          || typeof value.term !== "string"
          || !["literal", "identifier", "deployment-token"].includes(value.matchMode)
          || Object.keys(value).some((key) => !["term", "matchMode"].includes(key))))) {
        throw new Error("private JSON wordlist has an unsupported shape");
      }
      values = parsed;
    } else {
      values = data.split(/\r?\n/).map((line) => line.trim())
        .filter((term) => term.length > 0 && !term.startsWith("#"));
    }
    for (const rawValue of values) {
      const term = (typeof rawValue === "string" ? rawValue : rawValue.term).trim();
      const matchMode = typeof rawValue === "string" ? "literal" : rawValue.matchMode;
      if (term.length === 0) continue;
      if (term.length < 3) throw new Error("private wordlist terms must be at least three characters");
      if (term.length > 512) throw new Error("private wordlist terms must not exceed 512 characters");
      terms.push({ term: term.toLowerCase(), matchMode });
      if (terms.length > MAX_PRIVATE_TERMS) throw new Error("too many private wordlist terms");
    }
  }
  const unique = new Map();
  for (const spec of terms) unique.set(`${spec.matchMode}\0${spec.term}`, spec);
  return [...unique.values()];
}

function decodedPath(raw) {
  const value = raw.toString("utf8");
  return {
    value,
    validUtf8: Buffer.from(value, "utf8").equals(raw),
  };
}

function normalizeEntryPath(value) {
  return value.replaceAll("\\", "/").replace(/^\.\//, "");
}

function makeEntry({ rawPath, displayPath, content, kind, mode, size, ordinal, pathUtf8 = true,
  objectId = null }) {
  return {
    rawPath: rawPath ?? Buffer.from(displayPath, "utf8"),
    path: normalizeEntryPath(displayPath),
    originalPath: displayPath,
    content,
    kind,
    mode: mode ?? null,
    size: size ?? content?.length ?? 0,
    ordinal,
    pathUtf8,
    objectId,
  };
}

function loadGitTree(repo, revision) {
  if (revision.startsWith("-") || revision.includes("\0")) throw new Error("invalid Git revision");
  const treeOid = command("git", ["rev-parse", "--verify", `${revision}^{tree}`], {
    cwd: repo,
    encoding: "utf8",
  }).trim();
  // Resolve once, then enumerate that immutable tree object. Reusing a symbolic ref here would
  // let a concurrent ref update make the reported tree OID disagree with the scanned entries.
  const listing = command("git", ["ls-tree", "-r", "-z", "--full-tree", treeOid], { cwd: repo });
  const records = splitNul(listing);
  const entries = [];

  for (const record of records) {
    const tab = record.indexOf(0x09);
    if (tab < 0) throw new Error("invalid Git tree record");
    const metadata = record.subarray(0, tab).toString("ascii").split(" ");
    if (metadata.length !== 3) throw new Error("invalid Git tree metadata");
    const [mode, type, oid] = metadata;
    const rawPath = record.subarray(tab + 1);
    const decoded = decodedPath(rawPath);
    let content = null;
    let size = 0;
    if (type === "blob") {
      content = command("git", ["cat-file", "blob", oid], { cwd: repo });
      size = content.length;
    }
    entries.push(makeEntry({
      rawPath,
      displayPath: decoded.value,
      content,
      kind: type === "blob" ? (mode === "120000" ? "symlink" : "file") : "gitlink",
      mode,
      size,
      ordinal: entries.length,
      pathUtf8: decoded.validUtf8,
      objectId: oid,
    }));
  }
  return {
    entries,
    identity: {
      selection: "exact-git-tree",
      gitTreeOid: treeOid,
      sha256: manifestSha("git-tree", entries),
    },
  };
}

function splitNul(buffer) {
  const output = [];
  let start = 0;
  for (let index = 0; index < buffer.length; index += 1) {
    if (buffer[index] === 0) {
      if (index > start) output.push(buffer.subarray(start, index));
      start = index + 1;
    }
  }
  if (start !== buffer.length) throw new Error("unterminated NUL-delimited record");
  return output;
}

function loadWorktree(repo) {
  const listing = command("git", ["ls-files", "-z", "--cached", "--others", "--exclude-standard"], {
    cwd: repo,
  });
  const paths = splitNul(listing);
  const entries = [];
  for (const rawPath of paths) {
    const decoded = decodedPath(rawPath);
    if (!decoded.validUtf8) {
      entries.push(makeEntry({
        rawPath,
        displayPath: "[invalid-utf8-path]",
        content: null,
        kind: "unreadable",
        size: 0,
        ordinal: entries.length,
        pathUtf8: false,
      }));
      continue;
    }
    const absolute = path.resolve(repo, decoded.value);
    if (!inside(repo, absolute)) throw new Error("worktree path escaped repository");
    let stat;
    try {
      stat = fs.lstatSync(absolute);
    } catch (error) {
      if (error?.code === "ENOENT") continue;
      throw error;
    }
    let content = null;
    let kind = "unsupported";
    if (stat.isFile()) {
      kind = "file";
      content = fs.readFileSync(absolute);
    } else if (stat.isSymbolicLink()) {
      kind = "symlink";
      content = fs.readlinkSync(absolute, { encoding: "buffer" });
    } else if (stat.isDirectory()) {
      kind = "gitlink";
    }
    entries.push(makeEntry({
      rawPath,
      displayPath: decoded.value,
      content,
      kind,
      mode: (stat.mode & 0o7777).toString(8),
      size: content?.length ?? stat.size,
      ordinal: entries.length,
    }));
  }
  entries.sort((left, right) => Buffer.compare(left.rawPath, right.rawPath));
  entries.forEach((entry, index) => { entry.ordinal = index; });
  return {
    entries,
    identity: {
      selection: "tracked-and-untracked-nonignored-present-files",
      sha256: manifestSha("worktree", entries),
    },
  };
}

function inside(parent, child) {
  const relative = path.relative(path.resolve(parent), path.resolve(child));
  return relative === "" || (!relative.startsWith(`..${path.sep}`) && relative !== "..");
}

function manifestSha(mode, entries) {
  const hash = crypto.createHash("sha256");
  hash.update(`${mode}\0`);
  for (const entry of entries) {
    hash.update(entry.rawPath);
    hash.update("\0");
    hash.update(entry.kind);
    hash.update("\0");
    hash.update(entry.mode ?? "");
    hash.update("\0");
    hash.update(String(entry.size));
    hash.update("\0");
    hash.update(entry.content === null ? "unavailable" : sha256(entry.content));
    hash.update("\0");
    hash.update(entry.objectId ?? "");
    hash.update("\0");
  }
  return hash.digest("hex");
}

function loadApk(filename) {
  const archive = fs.readFileSync(filename);
  const parsed = parseZipEntries(archive);
  const entries = parsed.records.map((record, ordinal) => makeEntry({
    rawPath: record.rawName,
    displayPath: record.name,
    content: record.content,
    kind: record.directory ? "directory" : "apk-entry",
    mode: null,
    size: record.uncompressedSize,
    ordinal,
    pathUtf8: record.pathUtf8,
  }));
  return {
    entries,
    archive,
    containerMetadata: parsed.metadata,
    identity: {
      selection: "exact-apk-container-and-zip-entries",
      sha256: sha256(archive),
      zipEntryManifestSha256: manifestSha("apk-entries", entries),
    },
  };
}

function loadFile(filename) {
  const absolute = path.resolve(filename);
  const stat = fs.lstatSync(absolute);
  if (!stat.isFile() || stat.isSymbolicLink()) {
    throw new Error("file input must be a regular non-symlink file");
  }
  const content = fs.readFileSync(absolute);
  const basename = path.basename(absolute);
  const rawPath = Buffer.from(basename, "utf8");
  const entry = makeEntry({
    rawPath,
    displayPath: basename,
    content,
    kind: "file",
    mode: (stat.mode & 0o7777).toString(8),
    size: content.length,
    ordinal: 0,
  });
  return {
    entries: [entry],
    identity: {
      selection: "exact-regular-file",
      sha256: sha256(content),
    },
  };
}

export function parseZipEntries(archive) {
  const eocdOffset = findEocd(archive);
  const disk = archive.readUInt16LE(eocdOffset + 4);
  const centralDisk = archive.readUInt16LE(eocdOffset + 6);
  const entriesOnDisk = archive.readUInt16LE(eocdOffset + 8);
  const entryCount = archive.readUInt16LE(eocdOffset + 10);
  const centralSize = archive.readUInt32LE(eocdOffset + 12);
  const centralOffset = archive.readUInt32LE(eocdOffset + 16);
  const commentLength = archive.readUInt16LE(eocdOffset + 20);
  if (eocdOffset + 22 + commentLength !== archive.length) throw new Error("invalid ZIP end record");
  if (disk !== 0 || centralDisk !== 0 || entriesOnDisk !== entryCount) {
    throw new Error("multi-disk ZIP archives are unsupported");
  }
  if (entryCount === 0xffff || centralSize === 0xffffffff || centralOffset === 0xffffffff) {
    throw new Error("ZIP64 APK archives are unsupported");
  }
  if (centralOffset + centralSize > eocdOffset) throw new Error("invalid ZIP central directory range");

  const records = [];
  const metadataParts = [];
  const localRanges = [];
  const names = new Set();
  let totalUncompressed = 0;
  let maximumDataEnd = 0;
  let offset = centralOffset;
  for (let index = 0; index < entryCount; index += 1) {
    requireRange(archive, offset, 46);
    if (archive.readUInt32LE(offset) !== 0x02014b50) throw new Error("invalid ZIP central entry");
    const flags = archive.readUInt16LE(offset + 8);
    const method = archive.readUInt16LE(offset + 10);
    const expectedCrc = archive.readUInt32LE(offset + 16);
    const compressedSize = archive.readUInt32LE(offset + 20);
    const uncompressedSize = archive.readUInt32LE(offset + 24);
    const nameLength = archive.readUInt16LE(offset + 28);
    const extraLength = archive.readUInt16LE(offset + 30);
    const entryCommentLength = archive.readUInt16LE(offset + 32);
    const startDisk = archive.readUInt16LE(offset + 34);
    const localOffset = archive.readUInt32LE(offset + 42);
    const fullLength = 46 + nameLength + extraLength + entryCommentLength;
    requireRange(archive, offset, fullLength);
    if (startDisk !== 0) throw new Error("multi-disk ZIP entry is unsupported");
    if (flags & 0x1) throw new Error("encrypted APK entry is unsupported");
    if (compressedSize === 0xffffffff || uncompressedSize === 0xffffffff || localOffset === 0xffffffff) {
      throw new Error("ZIP64 APK entry is unsupported");
    }
    const rawName = archive.subarray(offset + 46, offset + 46 + nameLength);
    const centralExtra = archive.subarray(offset + 46 + nameLength,
      offset + 46 + nameLength + extraLength);
    const centralComment = archive.subarray(offset + 46 + nameLength + extraLength,
      offset + fullLength);
    if (centralExtra.length > 0) metadataParts.push(centralExtra);
    if (centralComment.length > 0) metadataParts.push(centralComment);
    const decoded = decodedPath(rawName);
    const name = decoded.value;
    if (names.has(name)) throw new Error("duplicate APK entry name");
    names.add(name);
    if (name.includes("\0")) throw new Error("APK entry contains NUL");
    totalUncompressed += uncompressedSize;
    if (uncompressedSize > MAX_SCAN_BYTES || totalUncompressed > MAX_APK_TOTAL_UNCOMPRESSED_BYTES) {
      throw new Error("APK uncompressed content exceeds scanner safety limit");
    }

    requireRange(archive, localOffset, 30);
    if (archive.readUInt32LE(localOffset) !== 0x04034b50) throw new Error("invalid ZIP local entry");
    const localNameLength = archive.readUInt16LE(localOffset + 26);
    const localExtraLength = archive.readUInt16LE(localOffset + 28);
    const localFlags = archive.readUInt16LE(localOffset + 6);
    const localMethod = archive.readUInt16LE(localOffset + 8);
    const dataOffset = localOffset + 30 + localNameLength + localExtraLength;
    requireRange(archive, dataOffset, compressedSize);
    if (dataOffset + compressedSize > centralOffset) throw new Error("ZIP entry overlaps central directory");
    const localName = archive.subarray(localOffset + 30, localOffset + 30 + localNameLength);
    if (!localName.equals(rawName) || localMethod !== method || localFlags !== flags) {
      throw new Error("ZIP local and central entry disagree");
    }
    const localExtra = archive.subarray(localOffset + 30 + localNameLength, dataOffset);
    if (localExtra.length > 0) metadataParts.push(localExtra);
    maximumDataEnd = Math.max(maximumDataEnd, dataOffset + compressedSize);
    localRanges.push([localOffset, dataOffset + compressedSize]);
    const compressed = archive.subarray(dataOffset, dataOffset + compressedSize);
    let content;
    if (method === 0) content = Buffer.from(compressed);
    else if (method === 8) content = zlib.inflateRawSync(compressed, { maxOutputLength: MAX_SCAN_BYTES });
    else throw new Error("unsupported APK compression method");
    if (content.length !== uncompressedSize) throw new Error("APK entry size mismatch");
    if (crc32(content) !== expectedCrc) throw new Error("APK entry CRC mismatch");

    records.push({
      rawName,
      name,
      content,
      uncompressedSize,
      directory: name.endsWith("/"),
      pathUtf8: decoded.validUtf8,
    });
    offset += fullLength;
  }
  if (offset !== centralOffset + centralSize) throw new Error("ZIP central directory size mismatch");
  localRanges.sort((left, right) => left[0] - right[0]);
  for (let index = 1; index < localRanges.length; index += 1) {
    if (localRanges[index][0] < localRanges[index - 1][1]) throw new Error("overlapping ZIP entries");
  }
  if (maximumDataEnd < centralOffset) metadataParts.push(archive.subarray(maximumDataEnd, centralOffset));
  if (commentLength > 0) metadataParts.push(archive.subarray(eocdOffset + 22));
  return { records, metadata: Buffer.concat(metadataParts) };
}

function findEocd(archive) {
  const minimum = Math.max(0, archive.length - 22 - 0xffff);
  for (let offset = archive.length - 22; offset >= minimum; offset -= 1) {
    if (archive.readUInt32LE(offset) === 0x06054b50
        && offset + 22 + archive.readUInt16LE(offset + 20) === archive.length) return offset;
  }
  throw new Error("ZIP end record not found");
}

function requireRange(buffer, offset, length) {
  if (!Number.isSafeInteger(offset) || !Number.isSafeInteger(length)
      || offset < 0 || length < 0 || offset + length > buffer.length) {
    throw new Error("ZIP structure is out of range");
  }
}

let crcTable = null;
function crc32(buffer) {
  if (crcTable === null) {
    crcTable = Array.from({ length: 256 }, (_, value) => {
      let current = value;
      for (let bit = 0; bit < 8; bit += 1) {
        current = (current & 1) ? (0xedb88320 ^ (current >>> 1)) : (current >>> 1);
      }
      return current >>> 0;
    });
  }
  let crc = 0xffffffff;
  for (const byte of buffer) crc = crcTable[(crc ^ byte) & 0xff] ^ (crc >>> 8);
  return (crc ^ 0xffffffff) >>> 0;
}

export function selectApkThirdPartyProfile(surface, policy, privateTerms) {
  const byPath = new Map(surface.entries.map((entry) => [entry.path, entry]));
  const hasProtectedSurface = surface.entries.some((entry) =>
    policy.protectedEntryPrefixes.some((prefix) => entry.path.startsWith(prefix))
      || policy.protectedEntryBasenames.includes(path.posix.basename(entry.path)));
  if (!hasProtectedSurface) {
    return {
      profile: null,
      trustedEntries: new Map(),
      trustedDexStringSha256: new Set(),
      findings: [],
    };
  }
  const exactProfiles = policy.profiles.filter((profile) => profile.entries.every((expected) => {
    const actual = byPath.get(expected.path);
    return actual !== undefined && actual.content !== null
      && sha256(actual.content) === expected.sha256;
  }));
  const findings = [];
  const add = (entry, ruleId) => findings.push({
    ruleId,
    entry: entry ? safeEntryLabel(entry, privateTerms) : "[apk-third-party-provenance]",
    entryOrdinal: entry?.ordinal ?? -1,
  });
  if (exactProfiles.length !== 1) {
    add(null, exactProfiles.length === 0
      ? "third-party-provenance-profile-unmatched"
      : "third-party-provenance-profile-ambiguous");
    const trustedEntries = new Map();
    for (const profile of policy.profiles) {
      for (const expected of profile.entries) {
        const actual = byPath.get(expected.path);
        if (actual !== undefined && actual.content !== null
            && sha256(actual.content) === expected.sha256) {
          trustedEntries.set(expected.path, expected);
        }
      }
    }
    for (const [entryPath, expected] of trustedEntries) {
      if (expected.kind !== "dex") continue;
      const actual = byPath.get(entryPath);
      try {
        if (dexTypeDescriptors(actual.content).some((descriptor) =>
          isApplicationDescriptor(descriptor, policy.appNamespaceDescriptorPrefixes))) {
          add(actual, "application-code-in-third-party-dex");
          trustedEntries.delete(entryPath);
        }
      } catch {
        add(actual, "third-party-provenance-dex-invalid");
        trustedEntries.delete(entryPath);
      }
    }
    for (const entry of surface.entries) {
      const protectedEntry = policy.protectedEntryPrefixes.some((prefix) => entry.path.startsWith(prefix))
        || policy.protectedEntryBasenames.includes(path.posix.basename(entry.path));
      if (protectedEntry && !trustedEntries.has(entry.path)) {
        add(entry, "third-party-provenance-entry-unlisted");
      }
    }
    return {
      profile: null,
      trustedEntries,
      trustedDexStringSha256: new Set(),
      findings,
    };
  }

  const profile = exactProfiles[0];
  const trustedEntries = new Map(profile.entries.map((entry) => [entry.path, entry]));
  const selectedDexStringComponents = new Set(profile.dexStringComponents ?? []);
  const trustedDexStringSha256 = new Set(
    [...(policy.dexStringSources?.entries() ?? [])]
      .filter(([, source]) => selectedDexStringComponents.has(source.component))
      .map(([digest]) => digest),
  );
  for (const entry of surface.entries) {
    const protectedEntry = policy.protectedEntryPrefixes.some((prefix) => entry.path.startsWith(prefix))
      || policy.protectedEntryBasenames.includes(path.posix.basename(entry.path));
    if (protectedEntry && !trustedEntries.has(entry.path)) {
      add(entry, "third-party-provenance-entry-unlisted");
    }
  }
  for (const expected of profile.entries) {
    if (expected.kind !== "dex") continue;
    const actual = byPath.get(expected.path);
    let descriptors;
    try {
      descriptors = dexTypeDescriptors(actual.content);
    } catch {
      add(actual, "third-party-provenance-dex-invalid");
      trustedEntries.delete(expected.path);
      continue;
    }
    if (descriptors.some((descriptor) =>
      isApplicationDescriptor(descriptor, policy.appNamespaceDescriptorPrefixes))) {
      add(actual, "application-code-in-third-party-dex");
      trustedEntries.delete(expected.path);
    }
  }
  return { profile, trustedEntries, trustedDexStringSha256, findings };
}

export function dexTypeDescriptors(buffer) {
  const records = dexStringRecords(buffer);
  const stringCount = buffer.readUInt32LE(56);
  const typeCount = buffer.readUInt32LE(64);
  const typeOffset = buffer.readUInt32LE(68);
  const descriptorIndexes = new Set();
  for (let index = 0; index < typeCount; index += 1) {
    const stringIndex = buffer.readUInt32LE(typeOffset + index * 4);
    if (stringIndex >= stringCount) throw new Error("DEX type string index out of range");
    descriptorIndexes.add(stringIndex);
  }
  const descriptors = [...descriptorIndexes]
    .map((stringIndex) => records[stringIndex].text)
    .filter((descriptor) => /^(?:\[)*(?:[VZBSCIJFD]|L[^;]+;)$/.test(descriptor));
  if (descriptors.length === 0) throw new Error("DEX contained no type descriptors");
  return descriptors;
}

export function dexStringRecords(buffer) {
  if (!Buffer.isBuffer(buffer) || buffer.length < 112
      || !/^dex\n0(?:35|37|38|39|40|41)\0$/.test(buffer.subarray(0, 8).toString("latin1"))
      || buffer.readUInt32LE(32) !== buffer.length
      || buffer.readUInt32LE(36) !== 112
      || buffer.readUInt32LE(40) !== 0x12345678) {
    throw new Error("unsupported DEX header");
  }
  const stringCount = buffer.readUInt32LE(56);
  const stringOffset = buffer.readUInt32LE(60);
  const typeCount = buffer.readUInt32LE(64);
  const typeOffset = buffer.readUInt32LE(68);
  if (stringCount > 2_000_000 || typeCount > 2_000_000
      || stringOffset + stringCount * 4 > buffer.length
      || typeOffset + typeCount * 4 > buffer.length) {
    throw new Error("DEX table out of range");
  }
  const records = [];
  for (let stringIndex = 0; stringIndex < stringCount; stringIndex += 1) {
    const dataOffset = buffer.readUInt32LE(stringOffset + stringIndex * 4);
    if (dataOffset >= buffer.length) throw new Error("DEX string data out of range");
    const length = readUnsignedLeb128(buffer, dataOffset);
    let cursor = length.end;
    const rawStart = cursor;
    const codeUnits = [];
    while (cursor < buffer.length && buffer[cursor] !== 0) {
      const first = buffer[cursor++];
      if (first >= 0x01 && first <= 0x7f) {
        codeUnits.push(first);
        continue;
      }
      if (first >= 0xc0 && first <= 0xdf) {
        if (cursor >= buffer.length) throw new Error("truncated DEX MUTF-8 string");
        const second = buffer[cursor++];
        if ((second & 0xc0) !== 0x80) throw new Error("invalid DEX MUTF-8 continuation");
        const value = ((first & 0x1f) << 6) | (second & 0x3f);
        if (value !== 0 && value < 0x80) throw new Error("overlong DEX MUTF-8 sequence");
        codeUnits.push(value);
        continue;
      }
      if (first >= 0xe0 && first <= 0xef) {
        if (cursor + 1 >= buffer.length) throw new Error("truncated DEX MUTF-8 string");
        const second = buffer[cursor++];
        const third = buffer[cursor++];
        if ((second & 0xc0) !== 0x80 || (third & 0xc0) !== 0x80) {
          throw new Error("invalid DEX MUTF-8 continuation");
        }
        const value = ((first & 0x0f) << 12) | ((second & 0x3f) << 6) | (third & 0x3f);
        if (value < 0x800) throw new Error("overlong DEX MUTF-8 sequence");
        codeUnits.push(value);
        continue;
      }
      throw new Error("invalid DEX MUTF-8 leading byte");
    }
    if (cursor >= buffer.length || buffer[cursor] !== 0) throw new Error("unterminated DEX string");
    if (codeUnits.length !== length.value) throw new Error("DEX string UTF-16 length mismatch");
    let text = "";
    for (let offset = 0; offset < codeUnits.length; offset += 8192) {
      text += String.fromCharCode(...codeUnits.slice(offset, offset + 8192));
    }
    records.push({
      text,
      rawStart,
      rawEnd: cursor,
      sha256: sha256(Buffer.from(text, "utf8")),
    });
  }
  return records;
}

function readUnsignedLeb128(buffer, offset) {
  let value = 0;
  let shift = 0;
  let cursor = offset;
  for (let count = 0; count < 5; count += 1) {
    if (cursor >= buffer.length) throw new Error("truncated DEX ULEB128");
    const byte = buffer[cursor++];
    value |= (byte & 0x7f) << shift;
    if ((byte & 0x80) === 0) {
      if (!Number.isSafeInteger(value) || value < 0) throw new Error("invalid DEX ULEB128");
      return { value, end: cursor };
    }
    shift += 7;
  }
  throw new Error("oversized DEX ULEB128");
}

function readDexUleb128(buffer, state, limit = buffer.length) {
  if (state.offset >= limit) throw new Error("DEX ULEB128 escaped its data item");
  const decoded = readUnsignedLeb128(buffer, state.offset);
  if (decoded.end > limit) throw new Error("DEX ULEB128 escaped its data item");
  state.offset = decoded.end;
  return decoded.value;
}

function readDexSleb128(buffer, state, limit = buffer.length) {
  let value = 0;
  let shift = 0;
  let byte = 0;
  for (let count = 0; count < 5; count += 1) {
    if (state.offset >= limit) throw new Error("DEX SLEB128 escaped its data item");
    byte = buffer[state.offset++];
    value |= (byte & 0x7f) << shift;
    shift += 7;
    if ((byte & 0x80) === 0) {
      if (shift < 32 && (byte & 0x40) !== 0) value |= (~0 << shift);
      return value;
    }
  }
  throw new Error("oversized DEX SLEB128");
}

function dexFixedLayout(buffer) {
  // This also verifies the DEX header, string_ids bounds, and every referenced string_data_item.
  dexStringRecords(buffer);
  const dataSize = buffer.readUInt32LE(104);
  const dataOffset = buffer.readUInt32LE(108);
  const dataEnd = dataOffset + dataSize;
  if (dataSize === 0 || dataOffset < 112 || (dataOffset & 3) !== 0
      || dataEnd > buffer.length) {
    throw new Error("DEX data section out of range");
  }
  const specifications = [
    { type: 0x0001, name: "string_ids", countAt: 56, offsetAt: 60, width: 4 },
    { type: 0x0002, name: "type_ids", countAt: 64, offsetAt: 68, width: 4 },
    { type: 0x0003, name: "proto_ids", countAt: 72, offsetAt: 76, width: 12 },
    { type: 0x0004, name: "field_ids", countAt: 80, offsetAt: 84, width: 8 },
    { type: 0x0005, name: "method_ids", countAt: 88, offsetAt: 92, width: 8 },
    { type: 0x0006, name: "class_defs", countAt: 96, offsetAt: 100, width: 32 },
  ];
  const tables = [];
  for (const specification of specifications) {
    const count = buffer.readUInt32LE(specification.countAt);
    const offset = buffer.readUInt32LE(specification.offsetAt);
    if (count > 2_000_000 || (count === 0 && offset !== 0)) {
      throw new Error("DEX fixed table declaration is invalid");
    }
    if (count === 0) {
      tables.push({ ...specification, count, offset, end: offset });
      continue;
    }
    const byteLength = count * specification.width;
    const end = offset + byteLength;
    if (offset < 112 || (offset & 3) !== 0 || end > dataOffset || end > buffer.length) {
      throw new Error("DEX fixed table overlaps the data section");
    }
    tables.push({ ...specification, count, offset, end });
  }
  let previousEnd = 112;
  for (const table of tables.filter((candidate) => candidate.count > 0)) {
    if (table.offset < previousEnd) {
      throw new Error("DEX fixed tables overlap or are out of order");
    }
    previousEnd = table.end;
  }
  return {
    dataSize,
    dataOffset,
    dataEnd,
    tables,
    table(type) {
      return tables.find((table) => table.type === type);
    },
  };
}

function dexMapLayout(buffer, layout) {
  const mapOffset = buffer.readUInt32LE(52);
  if ((mapOffset & 3) !== 0 || mapOffset < layout.dataOffset
      || mapOffset + 4 > layout.dataEnd) {
    throw new Error("DEX map_list out of range");
  }
  const count = buffer.readUInt32LE(mapOffset);
  if (count === 0 || count > 4096 || mapOffset + 4 + count * 12 > layout.dataEnd) {
    throw new Error("DEX map_list size out of range");
  }
  const items = [];
  const types = new Set();
  for (let index = 0; index < count; index += 1) {
    const itemOffset = mapOffset + 4 + index * 12;
    const type = buffer.readUInt16LE(itemOffset);
    const unused = buffer.readUInt16LE(itemOffset + 2);
    const size = buffer.readUInt32LE(itemOffset + 4);
    const offset = buffer.readUInt32LE(itemOffset + 8);
    if (unused !== 0 || size === 0 || offset >= buffer.length || types.has(type)
        || (index > 0 && offset <= items[index - 1].offset)) {
      throw new Error("DEX map_list item is invalid");
    }
    types.add(type);
    items.push({ type, size, offset });
  }
  const header = items.find((item) => item.type === 0x0000);
  const self = items.find((item) => item.type === 0x1000);
  if (!header || header.size !== 1 || header.offset !== 0
      || !self || self.size !== 1 || self.offset !== mapOffset) {
    throw new Error("DEX map_list does not bind the header and itself");
  }
  for (const table of layout.tables.filter((candidate) => candidate.count > 0)) {
    const item = items.find((candidate) => candidate.type === table.type);
    if (!item || item.size !== table.count || item.offset !== table.offset) {
      throw new Error("DEX map_list does not bind a fixed table");
    }
  }
  return {
    items,
    item(type) {
      return items.find((item) => item.type === type) ?? null;
    },
    region(type) {
      const index = items.findIndex((item) => item.type === type);
      if (index < 0) return null;
      return {
        start: items[index].offset,
        end: index + 1 < items.length ? items[index + 1].offset : layout.dataEnd,
        count: items[index].size,
      };
    },
  };
}

function parseDexCodeItem(buffer, codeOffset, layout, typeCount) {
  if ((codeOffset & 3) !== 0 || codeOffset < layout.dataOffset
      || codeOffset + 16 > layout.dataEnd) {
    throw new Error("DEX code item out of range");
  }
  const registersSize = buffer.readUInt16LE(codeOffset);
  const insSize = buffer.readUInt16LE(codeOffset + 2);
  const triesSize = buffer.readUInt16LE(codeOffset + 6);
  const debugInfoOffset = buffer.readUInt32LE(codeOffset + 8);
  const instructionCount = buffer.readUInt32LE(codeOffset + 12);
  if (insSize > registersSize || (debugInfoOffset !== 0
        && (debugInfoOffset < layout.dataOffset || debugInfoOffset >= layout.dataEnd))
      || instructionCount > Math.floor((layout.dataEnd - codeOffset - 16) / 2)) {
    throw new Error("DEX code item header out of range");
  }
  const instructionEnd = codeOffset + 16 + instructionCount * 2;
  if (triesSize === 0) {
    return { start: codeOffset, end: instructionEnd, debugInfoOffset };
  }
  let cursor = instructionEnd;
  if ((instructionCount & 1) !== 0) {
    if (cursor + 2 > layout.dataEnd || buffer.readUInt16LE(cursor) !== 0) {
      throw new Error("DEX code item padding is invalid");
    }
    cursor += 2;
  }
  if (triesSize > Math.floor((layout.dataEnd - cursor) / 8)) {
    throw new Error("DEX code item tries are out of range");
  }
  const handlerOffsets = [];
  for (let index = 0; index < triesSize; index += 1) {
    const tryOffset = cursor + index * 8;
    const startAddress = buffer.readUInt32LE(tryOffset);
    const instructionLength = buffer.readUInt16LE(tryOffset + 4);
    const handlerOffset = buffer.readUInt16LE(tryOffset + 6);
    if (instructionLength === 0 || startAddress >= instructionCount
        || instructionLength > instructionCount - startAddress) {
      throw new Error("DEX try_item is out of range");
    }
    handlerOffsets.push(handlerOffset);
  }
  cursor += triesSize * 8;
  const handlersStart = cursor;
  const state = { offset: cursor };
  const handlerCount = readDexUleb128(buffer, state, layout.dataEnd);
  if (handlerCount === 0 || handlerCount > triesSize) {
    throw new Error("DEX catch handler count is invalid");
  }
  const parsedHandlerOffsets = new Set();
  for (let handlerIndex = 0; handlerIndex < handlerCount; handlerIndex += 1) {
    parsedHandlerOffsets.add(state.offset - handlersStart);
    const pairCount = readDexSleb128(buffer, state, layout.dataEnd);
    if (pairCount === -0x80000000 || Math.abs(pairCount) > 2_000_000) {
      throw new Error("DEX catch handler size is invalid");
    }
    for (let pairIndex = 0; pairIndex < Math.abs(pairCount); pairIndex += 1) {
      const typeIndex = readDexUleb128(buffer, state, layout.dataEnd);
      const address = readDexUleb128(buffer, state, layout.dataEnd);
      if (typeIndex >= typeCount || address >= instructionCount) {
        throw new Error("DEX catch handler pair is out of range");
      }
    }
    if (pairCount <= 0) {
      const catchAllAddress = readDexUleb128(buffer, state, layout.dataEnd);
      if (catchAllAddress >= instructionCount) {
        throw new Error("DEX catch-all address is out of range");
      }
    }
  }
  if (handlerOffsets.some((offset) => !parsedHandlerOffsets.has(offset))) {
    throw new Error("DEX try_item references an unknown handler");
  }
  return { start: codeOffset, end: state.offset, debugInfoOffset };
}

export function dexCodeItemHeaderRanges(buffer) {
  // A short ASCII token can occur accidentally in numeric DEX metadata. Only headers reached
  // through the class_def -> class_data -> encoded_method structure are eligible for this narrow
  // exclusion. Any malformed or unsupported structure throws, and callers retain the original
  // conservative whole-container scan.
  const layout = dexFixedLayout(buffer);
  const fieldCount = layout.table(0x0004).count;
  const methodCount = layout.table(0x0005).count;
  const classCount = layout.table(0x0006).count;
  const classOffset = layout.table(0x0006).offset;
  const typeCount = layout.table(0x0002).count;

  const classDataItems = [];
  const codeItems = [];
  const codeOffsets = new Set();
  const classDataOffsets = new Set();
  for (let classIndex = 0; classIndex < classCount; classIndex += 1) {
    const classDataOffset = buffer.readUInt32LE(classOffset + classIndex * 32 + 24);
    if (classDataOffset === 0) continue;
    if (classDataOffset < layout.dataOffset || classDataOffset >= layout.dataEnd
        || classDataOffsets.has(classDataOffset)) {
      throw new Error("DEX class data out of range");
    }
    classDataOffsets.add(classDataOffset);
    const state = { offset: classDataOffset };
    const staticFieldCount = readDexUleb128(buffer, state, layout.dataEnd);
    const instanceFieldCount = readDexUleb128(buffer, state, layout.dataEnd);
    const directMethodCount = readDexUleb128(buffer, state, layout.dataEnd);
    const virtualMethodCount = readDexUleb128(buffer, state, layout.dataEnd);
    if (staticFieldCount > fieldCount || instanceFieldCount > fieldCount
        || staticFieldCount + instanceFieldCount > fieldCount
        || directMethodCount > methodCount || virtualMethodCount > methodCount
        || directMethodCount + virtualMethodCount > methodCount) {
      throw new Error("DEX class member count out of range");
    }

    let fieldIndex = 0;
    for (let index = 0; index < staticFieldCount; index += 1) {
      fieldIndex += readDexUleb128(buffer, state, layout.dataEnd);
      readDexUleb128(buffer, state, layout.dataEnd);
      if (fieldIndex >= fieldCount) throw new Error("DEX field index out of range");
    }
    fieldIndex = 0;
    for (let index = 0; index < instanceFieldCount; index += 1) {
      fieldIndex += readDexUleb128(buffer, state, layout.dataEnd);
      readDexUleb128(buffer, state, layout.dataEnd);
      if (fieldIndex >= fieldCount) throw new Error("DEX field index out of range");
    }

    for (const encodedMethodCount of [directMethodCount, virtualMethodCount]) {
      let methodIndex = 0;
      for (let index = 0; index < encodedMethodCount; index += 1) {
        methodIndex += readDexUleb128(buffer, state, layout.dataEnd);
        readDexUleb128(buffer, state, layout.dataEnd);
        const codeOffset = readDexUleb128(buffer, state, layout.dataEnd);
        if (methodIndex >= methodCount) throw new Error("DEX method index out of range");
        if (codeOffset === 0) continue;
        if (codeOffsets.has(codeOffset)) throw new Error("duplicate DEX code item offset");
        codeOffsets.add(codeOffset);
        codeItems.push(parseDexCodeItem(buffer, codeOffset, layout, typeCount));
      }
    }
    classDataItems.push({ start: classDataOffset, end: state.offset });
  }
  if (codeItems.length === 0) return [];

  const map = dexMapLayout(buffer, layout);
  const classDataRegion = map.region(0x2000);
  const codeRegion = map.region(0x2001);
  const codeMapItem = map.item(0x2001);
  if (!classDataRegion || classDataRegion.count !== classDataItems.length
      || !codeRegion || !codeMapItem || codeMapItem.size !== codeItems.length) {
    throw new Error("DEX map_list does not bind class/code data items");
  }
  const sortedClassData = classDataItems.sort((left, right) => left.start - right.start);
  const sortedCodeItems = codeItems.sort((left, right) => left.start - right.start);
  if (sortedClassData[0]?.start !== classDataRegion.start
      || sortedCodeItems[0]?.start !== codeRegion.start) {
    throw new Error("DEX data item map starts are inconsistent");
  }
  for (const [items, region] of [
    [sortedClassData, classDataRegion],
    [sortedCodeItems, codeRegion],
  ]) {
    for (let index = 0; index < items.length; index += 1) {
      if (items[index].start < region.start || items[index].end > region.end
          || (index > 0 && items[index].start < items[index - 1].end)) {
        throw new Error("DEX data items overlap or escape their map region");
      }
    }
  }
  const debugRegion = map.region(0x2003);
  for (const codeItem of sortedCodeItems) {
    if (codeItem.debugInfoOffset !== 0 && (!debugRegion
        || codeItem.debugInfoOffset < debugRegion.start
        || codeItem.debugInfoOffset >= debugRegion.end)) {
      throw new Error("DEX debug_info_off escaped the mapped debug section");
    }
  }
  return sortedCodeItems.map((item) => ({ start: item.start, end: item.start + 16 }));
}

export function dexStringIdOffsetRanges(buffer) {
  // Validate the complete table against successfully decoded string_data_item records before
  // treating its uint32 offsets as numeric metadata. A single inconsistency rejects the whole
  // table; callers then keep the original conservative raw-container scan.
  const records = dexStringRecords(buffer);
  const layout = dexFixedLayout(buffer);
  const stringTable = layout.table(0x0001);
  const stringCount = stringTable.count;
  const stringOffset = stringTable.offset;
  if (records.length !== stringCount || stringTable.end > layout.dataOffset) {
    throw new Error("DEX string table structure out of range");
  }

  const dataItems = [];
  const seenDataOffsets = new Set();
  const ranges = [];
  for (let stringIndex = 0; stringIndex < stringCount; stringIndex += 1) {
    const tableFieldOffset = stringOffset + stringIndex * 4;
    const stringDataOffset = buffer.readUInt32LE(tableFieldOffset);
    const record = records[stringIndex];
    if (stringDataOffset < layout.dataOffset || stringDataOffset >= layout.dataEnd
        || seenDataOffsets.has(stringDataOffset)) {
      throw new Error("DEX string data offset is inconsistent");
    }
    const length = readUnsignedLeb128(buffer, stringDataOffset);
    if (length.end !== record.rawStart || record.rawEnd < record.rawStart
        || record.rawEnd >= layout.dataEnd || buffer[record.rawEnd] !== 0) {
      throw new Error("DEX string data item is inconsistent");
    }
    seenDataOffsets.add(stringDataOffset);
    dataItems.push({ start: stringDataOffset, end: record.rawEnd + 1 });
    ranges.push({ start: tableFieldOffset, end: tableFieldOffset + 4 });
  }
  dataItems.sort((left, right) => left.start - right.start);
  for (let index = 1; index < dataItems.length; index += 1) {
    if (dataItems[index].start < dataItems[index - 1].end) {
      throw new Error("DEX string data items overlap");
    }
  }
  return ranges;
}

function auditSurface(surface, mode, privateTerms, apkThirdPartyPolicy) {
  const findings = [];
  let matchedDexStringCount = 0;
  const add = (entry, ruleId, location = null) => {
    findings.push({
      ruleId,
      entry: safeEntryLabel(entry, privateTerms),
      entryOrdinal: entry.ordinal,
      ...(location === null ? {} : { line: location }),
    });
  };

  const thirdParty = mode === "apk"
    ? selectApkThirdPartyProfile(surface, apkThirdPartyPolicy, privateTerms)
    : {
      profile: null,
      trustedEntries: new Map(),
      trustedDexStringSha256: new Set(),
      findings: [],
    };
  findings.push(...thirdParty.findings);

  for (const entry of surface.entries) {
    const trustedThirdParty = thirdParty.trustedEntries.has(entry.path);
    auditPath(entry, mode, privateTerms, add, trustedThirdParty);
    if (entry.kind === "gitlink") {
      add(entry, "unscanned-git-submodule");
      continue;
    }
    if (entry.kind === "unsupported" || entry.kind === "directory" || entry.kind === "unreadable") {
      if (entry.kind !== "directory" && entry.content === null) add(entry, "unscanned-entry-type");
      continue;
    }
    if (entry.content === null) {
      add(entry, "unscanned-entry-content");
      continue;
    }
    if (entry.content.length > MAX_SCAN_BYTES) {
      add(entry, "unscanned-entry-size");
      continue;
    }
    if (trustedThirdParty) continue;
    if (mode === "apk" && /^classes(?:\d+)?\.dex$/.test(entry.path)) {
      matchedDexStringCount += auditDexContent(
        entry,
        privateTerms,
        add,
        thirdParty.trustedDexStringSha256,
        apkThirdPartyPolicy.appNamespaceDescriptorPrefixes,
      );
    } else {
      auditContent(entry, privateTerms, add);
    }
  }

  if (mode === "apk" && surface.containerMetadata?.length > 0) {
    const metadataEntry = makeEntry({
      displayPath: "[apk-container-metadata]",
      content: surface.containerMetadata,
      kind: "apk-metadata",
      ordinal: surface.entries.length,
    });
    if (metadataEntry.content.length > MAX_SCAN_BYTES) add(metadataEntry, "unscanned-entry-size");
    else auditContent(metadataEntry, privateTerms, add);
  }

  findings.sort((left, right) => left.entryOrdinal - right.entryOrdinal
    || left.ruleId.localeCompare(right.ruleId)
    || (left.line ?? 0) - (right.line ?? 0));
  return {
    findings: deduplicateFindings(findings),
    thirdPartyProfile: thirdParty.profile,
    trustedThirdPartyEntryCount: thirdParty.trustedEntries.size,
    declaredDexStringCount: thirdParty.trustedDexStringSha256.size,
    matchedDexStringCount,
  };
}

function auditPath(entry, mode, privateTerms, add, trustedThirdParty = false) {
  const value = entry.path;
  const originalValue = entry.originalPath;
  const lower = value.toLowerCase();
  if (!entry.pathUtf8 || /[\u0000-\u001f\u007f]/.test(originalValue)) add(entry, "unsafe-path-encoding");
  if (mode === "apk" && isUnsafeArchivePath(originalValue)) add(entry, "unsafe-apk-entry-path");
  if (isDeploymentSpecificPath(lower)) add(entry, "deployment-specific-path");
  if (mode === "apk") {
    if (!trustedThirdParty && ((lower.startsWith("assets/") && !POLICY.allowedApkAssets.includes(lower))
        || /^res\/raw(?:-[^/]+)?\//.test(lower))) {
      add(entry, "unknown-first-party-asset");
    }
  } else {
    const repositoryAssetRoot = lower.startsWith("app/assets/")
      || /^app\/src\/[^/]+\/assets\//.test(lower)
      || /^app\/res\/raw(?:-[^/]+)?\//.test(lower)
      || /^app\/src\/[^/]+\/res\/raw(?:-[^/]+)?\//.test(lower)
      || lower.startsWith("panel/public/");
    if (repositoryAssetRoot && !POLICY.allowedRepositoryAssets.includes(lower)) {
      add(entry, "unknown-first-party-asset");
    }
  }
  if (entry.kind === "symlink" && entry.content !== null) {
    const target = entry.content.toString("utf8");
    if (!Buffer.from(target, "utf8").equals(entry.content)
        || path.isAbsolute(target) || target.split(/[\\/]+/).includes("..")) {
      add(entry, "unsafe-symlink-target");
    }
  }
  if (pathContainsSensitiveValue(originalValue, privateTerms)) add(entry, "sensitive-entry-name");
}

function isUnsafeArchivePath(value) {
  if (value.length === 0 || value.startsWith("/") || value.startsWith("\\")) return true;
  if (/^[A-Za-z]:[\\/]/.test(value) || value.includes("\\")) return true;
  const parts = value.endsWith("/") ? value.slice(0, -1).split("/") : value.split("/");
  return parts.some((part) => part === "." || part === ".." || part === "");
}

function isDeploymentSpecificPath(lower) {
  const parts = lower.split("/");
  const basename = parts.at(-1) ?? "";
  const exampleLike = /(?:^|[._-])(?:example|sample|template)(?:[._-]|$)/.test(basename);
  if (isPrivateCatalogExportPath(lower, parts, basename)) return true;
  if ((basename === ".env" || basename.startsWith(".env.")) && !exampleLike) return true;
  if ([".netrc", ".npmrc", ".pypirc"].includes(basename) && !exampleLike) return true;
  if (basename === "wrangler.toml" || /^wrangler\.(?:prod|production|staging)\.toml$/.test(basename)) return true;
  if (["backend-adapter.json", "notification-adapter.json", "form-catalog-config.json"].includes(basename)) {
    return true;
  }
  if (/(?:^|[._-])(?:prod|production|staging)(?:[._-]|$)/.test(basename) && !exampleLike
      && /\.(?:cfg|conf|env|ini|json|properties|toml|ya?ml)$/.test(basename)) return true;
  if ([
    "google-services.json", "googleservice-info.plist", "local.properties",
    "keystore.properties", "terraform.tfstate", "terraform.tfstate.backup",
  ].includes(basename)) return true;
  if (/\.(?:jks|keystore|p12|pfx|pkcs12|key|pem)$/.test(basename)) return true;
  if (/^(?:service[-_.]?account|firebase[-_.]?admin|credentials?)(?:[._-].*)?\.json$/.test(basename)) return true;
  if (parts.includes(".ssh") || parts.includes(".aws") || parts.includes("secrets")) return true;
  if (parts.some((part) => /^(?:prod|production|staging)$/.test(part))
      && parts.some((part) => /^(?:deploy|deployment|deployments|environment|environments)$/.test(part))) {
    return true;
  }
  return false;
}

function isPrivateCatalogExportPath(lower, parts, basename) {
  if (basename === "form-profiles.json" || basename === "panel-settings.json") return true;
  if (basename !== "manifest.json") return false;
  if (lower === "assets/manifest.json" || lower === "app/assets/manifest.json"
      || /^app\/src\/[^/]+\/assets\/manifest\.json$/.test(lower)) return true;
  return parts.slice(0, -1).some((part) =>
    /^(?:catalogs?|catalog[-_.]?exports?|private[-_.]?catalogs?|panel[-_.]?catalogs?)$/.test(part));
}

function auditContent(entry, privateTerms, add) {
  const views = contentViews(entry.content);
  for (const { text, textual, privateOnly = false } of views) {
    auditTextView(entry, text, textual, privateTerms, add);
    // Binary Android XML and resources are decoded at both UTF-16LE alignments below. Those views
    // exist only to make exact external private terms unskippable; running generic PII/token
    // heuristics over arbitrary decoded binary would manufacture false positives.
    if (privateOnly) continue;
    auditGenericTextView(entry, text, textual, add);
  }

  const primary = views[0]?.text ?? "";
  if (isConfigLike(entry.path)) {
    forEachMatch(SECRET_ASSIGNMENT_PATTERN, primary, (match) => {
      if (isCommentOnlyMatch(primary, match.index)) return;
      const candidate = match[1] ?? match[2] ?? match[3] ?? "";
      if (!isPlaceholderValue(candidate)) add(entry, "credential-assignment", lineAt(primary, match.index));
    });
  }
  if (isStrictProtocolDocument(entry.path)) {
    forEachMatch(PAIRING_PROTOCOL_CREDENTIAL_ASSIGNMENT_PATTERN, primary, (match) => {
      if (isCommentOnlyMatch(primary, match.index)) return;
      const candidate = match[1] ?? match[2] ?? match[3] ?? "";
      if (!isPlaceholderValue(candidate)) add(entry, "credential-assignment", lineAt(primary, match.index));
    });
    forEachMatch(PAIRING_PROTOCOL_QUERY_CREDENTIAL_PATTERN, primary, (match) => {
      if (!isPlaceholderValue(match[1] ?? "")) {
        add(entry, "credential-assignment", lineAt(primary, match.index));
      }
    });
  }
  if (requiresExampleEndpoints(entry.path)) {
    forEachMatch(ENDPOINT_ASSIGNMENT_PATTERN, primary, (match) => {
      const host = endpointHost(match[1] ?? match[2] ?? match[3] ?? "");
      if (host !== null && !isSafeEndpointHost(host)) {
        add(entry, "non-public-endpoint-in-example-surface", lineAt(primary, match.index));
      }
    });
    forEachMatch(URL_PATTERN, primary, (match) => {
      const host = safeUrlHost(match[0]);
      if (host !== null && !isSafeEndpointHost(host)) {
        add(entry, "non-public-endpoint-in-example-surface", lineAt(primary, match.index));
      }
    });
  }
}

function auditDexContent(entry, privateTerms, add, trustedStringSha256, appDescriptorPrefixes) {
  let partition;
  try {
    partition = partitionDexStrings(entry.content, trustedStringSha256, appDescriptorPrefixes);
  } catch {
    add(entry, "unscanned-dex-content");
    auditContent(entry, privateTerms, add);
    return 0;
  }

  // DEX is structured binary. Generic PII patterns run only over actual MUTF-8
  // string-table values, never arbitrary instruction/header bytes that can
  // accidentally resemble an email address. Every non-provenanced string,
  // including every application-namespace descriptor, remains in this view.
  auditTextView(entry, partition.untrustedText, false, privateTerms, add);
  auditGenericTextView(entry, partition.untrustedText, false, add);

  // Keep exact private vocabulary unskippable in non-string DEX data (for
  // example a compiled byte array). Only the exact byte ranges of individually
  // provenanced strings are masked; the DEX container is never exempted.
  const maskedViews = contentViews(partition.masked);
  const shortDeploymentTerms = privateTerms.filter(isShortAsciiDeploymentTerm);
  const remainingPrivateTerms = privateTerms.filter((term) => !isShortAsciiDeploymentTerm(term));
  auditPrivateViews(entry, maskedViews, remainingPrivateTerms, add);
  if (shortDeploymentTerms.length > 0) {
    let numericMetadataRanges = null;
    try {
      numericMetadataRanges = [
        ...dexCodeItemHeaderRanges(entry.content),
        ...dexStringIdOffsetRanges(entry.content),
      ];
    } catch {
      // Fail closed: an unverified DEX layout receives the original whole-container scan, with
      // no numeric-metadata exclusion at all.
    }
    if (numericMetadataRanges === null) {
      auditPrivateViews(entry, maskedViews, shortDeploymentTerms, add);
    } else {
      auditShortDeploymentTermsInDexRaw(
        entry, partition.masked, shortDeploymentTerms, numericMetadataRanges, add);
    }
  }
  auditHighConfidenceViews(entry, maskedViews, add);
  return partition.matchedTrustedStringCount;
}

export function partitionDexStrings(buffer, trustedStringSha256, appDescriptorPrefixes) {
  const records = dexStringRecords(buffer);
  const masked = Buffer.from(buffer);
  const untrustedStrings = [];
  let matchedTrustedStringCount = 0;
  for (const record of records) {
    const applicationDescriptor = isApplicationDescriptor(record.text, appDescriptorPrefixes);
    if (trustedStringSha256.has(record.sha256) && !applicationDescriptor) {
      masked.fill(0, record.rawStart, record.rawEnd);
      matchedTrustedStringCount += 1;
    } else {
      untrustedStrings.push(record.text);
    }
  }
  return {
    masked,
    untrustedText: untrustedStrings.join("\0"),
    matchedTrustedStringCount,
  };
}

function isApplicationDescriptor(value, prefixes) {
  const descriptor = value.replace(/^\[+/, "");
  return prefixes.some((prefix) => descriptor.startsWith(prefix));
}

function auditPrivateViews(entry, views, privateTerms, add) {
  for (const { text, textual } of views) {
    auditTextView(entry, text, textual, privateTerms, add);
  }
}

function isShortAsciiDeploymentTerm(spec) {
  return spec.matchMode === "deployment-token" && spec.term.length <= 3
    && /^[\x00-\x7f]+$/.test(spec.term);
}

function asciiLower(value) {
  return value >= 0x41 && value <= 0x5a ? value + 0x20 : value;
}

function asciiAlphaNumeric(value) {
  const lower = asciiLower(value);
  return (lower >= 0x61 && lower <= 0x7a) || (lower >= 0x30 && lower <= 0x39);
}

function isAsciiHexCharacter(value) {
  if (value === undefined) return false;
  const lower = asciiLower(value.charCodeAt(0));
  return (lower >= 0x61 && lower <= 0x66) || (lower >= 0x30 && lower <= 0x39);
}

function containedByCompleteHashHexDigest(text, start, end) {
  let digestStart = start;
  while (digestStart > 0 && isAsciiHexCharacter(text[digestStart - 1])) digestStart -= 1;
  let digestEnd = end;
  while (digestEnd < text.length && isAsciiHexCharacter(text[digestEnd])) digestEnd += 1;
  const digestLength = digestEnd - digestStart;
  return digestLength === 40 || digestLength === 64;
}

function containedByDexNumericMetadata(start, end, ranges) {
  return ranges.some((range) => start >= range.start && end <= range.end);
}

function auditShortDeploymentTermsInDexRaw(entry, buffer, terms, numericMetadataRanges, add) {
  for (const spec of terms) {
    const expected = Buffer.from(spec.term, "ascii");
    for (let offset = 0; offset + expected.length <= buffer.length; offset += 1) {
      let matched = true;
      for (let index = 0; index < expected.length; index += 1) {
        if (asciiLower(buffer[offset + index]) !== expected[index]) {
          matched = false;
          break;
        }
      }
      if (!matched
          || (offset > 0 && asciiAlphaNumeric(buffer[offset - 1]))
          || (offset + expected.length < buffer.length
            && asciiAlphaNumeric(buffer[offset + expected.length]))) continue;
      if (!containedByDexNumericMetadata(
        offset, offset + expected.length, numericMetadataRanges)) {
        add(entry, "private-wordlist-term");
      }
    }

    // Preserve the scanner's two UTF-16LE alignments for compiled resources and explicit byte
    // arrays. A match is ignored only when its complete encoded byte range is inside one proven
    // numeric code_item header or one verified string_ids offset field; cross-boundary and payload
    // matches remain findings.
    for (const alignment of [0, 1]) {
      const available = buffer.length - alignment;
      const unitCount = Math.floor(available / 2);
      for (let unit = 0; unit + expected.length <= unitCount; unit += 1) {
        const byteOffset = alignment + unit * 2;
        let matched = true;
        for (let index = 0; index < expected.length; index += 1) {
          const codeUnit = buffer.readUInt16LE(byteOffset + index * 2);
          if (codeUnit > 0x7f || asciiLower(codeUnit) !== expected[index]) {
            matched = false;
            break;
          }
        }
        const previous = unit > 0 ? buffer.readUInt16LE(byteOffset - 2) : -1;
        const nextOffset = byteOffset + expected.length * 2;
        const next = unit + expected.length < unitCount
          ? buffer.readUInt16LE(nextOffset) : -1;
        if (!matched || asciiAlphaNumeric(previous) || asciiAlphaNumeric(next)) continue;
        if (!containedByDexNumericMetadata(byteOffset, nextOffset, numericMetadataRanges)) {
          add(entry, "private-wordlist-term");
        }
      }
    }
  }
}

function auditHighConfidenceViews(entry, views, add) {
  for (const { text, textual } of views) {
    for (const [ruleId, pattern] of HIGH_CONFIDENCE_PATTERNS) {
      forEachMatch(pattern, text, (match) =>
        add(entry, ruleId, textual ? lineAt(text, match.index) : null));
    }
  }
}

function auditTextView(entry, text, textual, privateTerms, add) {
  const lower = text.toLowerCase();
  for (const spec of privateTerms) {
    forEachPrivateMatchInLower(spec, lower, (offset) => {
      add(entry, "private-wordlist-term", textual ? lineAt(text, offset) : null);
    });
  }
}

function auditGenericTextView(entry, text, textual, add) {
  for (const [ruleId, pattern] of HIGH_CONFIDENCE_PATTERNS) {
    forEachMatch(pattern, text, (match) =>
      add(entry, ruleId, textual ? lineAt(text, match.index) : null));
  }
  forEachMatch(EMAIL_PATTERN, text, (match) => {
    if (isNonPersonalInfrastructureEmail(match[0])) return;
    const host = match[0].slice(match[0].lastIndexOf("@") + 1).toLowerCase();
    if (!isExampleHost(host)) add(entry, "pii-email", textual ? lineAt(text, match.index) : null);
  });
  forEachMatch(E164_PATTERN, text, (match) =>
    add(entry, "pii-phone", textual ? lineAt(text, match.index) : null));
  forEachMatch(CN_MOBILE_PATTERN, text, (match) => {
    const numberStart = match.index + match[0].length - 11;
    const numberEnd = match.index + match[0].length;
    // Decimal runs occur by chance in Git SHA-1 and SHA-256 object IDs. Exempt only an entire,
    // bounded 40- or 64-character hex token; near-length tokens remain findings.
    if (!containedByCompleteHashHexDigest(text, numberStart, numberEnd)) {
      add(entry, "pii-phone", textual ? lineAt(text, match.index) : null);
    }
  });
  forEachMatch(CN_ID_PATTERN, text, (match) =>
    add(entry, "pii-government-id", textual ? lineAt(text, match.index) : null));
  forEachMatch(MAC_PATTERN, text, (match) =>
    add(entry, "pii-device-address", textual ? lineAt(text, match.index) : null));
  forEachMatch(IPV4_PATTERN, text, (match) => {
    if (isValidIpv4(match[0]) && !isDocumentationIpv4(match[0])) {
      add(entry, "network-address", textual ? lineAt(text, match.index) : null);
    }
  });
}

function contentViews(buffer) {
  const views = [];
  const utf8 = buffer.toString("utf8");
  const utf8RoundTrips = Buffer.from(utf8, "utf8").equals(buffer);
  const textual = utf8RoundTrips && isProbablyText(utf8);
  views.push({ text: utf8, textual });
  if (!utf8RoundTrips) views.push({ text: buffer.toString("latin1"), textual: false });
  for (const offset of [0, 1]) {
    const byteLength = buffer.length - offset;
    const alignedLength = byteLength - (byteLength % 2);
    if (alignedLength < 8) continue;
    const utf16 = buffer.subarray(offset, offset + alignedLength).toString("utf16le");
    const plausibleText = isProbablyText(utf16);
    views.push({ text: utf16, textual: false, privateOnly: !plausibleText });
  }
  return views;
}

function isProbablyText(value) {
  if (value.length === 0) return true;
  let acceptable = 0;
  const sample = value.slice(0, 65536);
  for (const character of sample) {
    const code = character.codePointAt(0);
    if (code === 9 || code === 10 || code === 13 || code >= 32) acceptable += 1;
  }
  return acceptable / sample.length >= 0.9 && !sample.includes("\0");
}

function forEachMatch(pattern, text, callback) {
  pattern.lastIndex = 0;
  let match;
  while ((match = pattern.exec(text)) !== null) {
    callback(match);
    if (match[0].length === 0) pattern.lastIndex += 1;
  }
  pattern.lastIndex = 0;
}

function forEachPrivateMatch(spec, text, callback) {
  const lower = text.toLowerCase();
  forEachPrivateMatchInLower(spec, lower, callback);
}

function forEachPrivateMatchInLower(spec, lower, callback) {
  if (spec.matchMode === "literal") {
    let offset = 0;
    while ((offset = lower.indexOf(spec.term, offset)) >= 0) {
      callback(offset);
      offset += Math.max(1, spec.term.length);
    }
    return;
  }
  const escaped = spec.term.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
  const word = spec.matchMode === "identifier" ? "a-z0-9_" : "a-z0-9";
  const pattern = new RegExp(`(^|[^${word}])${escaped}(?=$|[^${word}])`, "gi");
  forEachMatch(pattern, lower, (match) => callback(
    match.index + (match[1] ? match[1].length : 0)));
}

function privateTermMatches(value, spec) {
  let matched = false;
  forEachPrivateMatch(spec, value, () => { matched = true; });
  return matched;
}

function lineAt(text, offset) {
  let line = 1;
  for (let index = 0; index < offset; index += 1) if (text.charCodeAt(index) === 10) line += 1;
  return line;
}

function isConfigLike(filename) {
  const lower = filename.toLowerCase();
  if (path.basename(lower).startsWith(".env")) return true;
  return CONFIG_EXTENSIONS.has(path.extname(lower));
}

function isStrictProtocolDocument(filename) {
  return POLICY.strictProtocolDocuments.includes(filename.toLowerCase());
}

function requiresExampleEndpoints(filename) {
  const lower = filename.toLowerCase();
  const basename = path.posix.basename(lower);
  return isStrictProtocolDocument(lower)
    || lower.startsWith("app/assets/")
    || lower.startsWith("assets/")
    || /(?:^|[._-])(?:example|sample|seed|template)(?:[._-]|$)/.test(basename);
}

function safeUrlHost(value) {
  try {
    return new URL(value).hostname.toLowerCase().replace(/^\[|\]$/g, "");
  } catch {
    return null;
  }
}

function endpointHost(value) {
  const candidate = value.trim().replace(/[)\]]+$/, "");
  if (candidate.length === 0 || candidate.startsWith("/")
      || candidate.startsWith("${") || candidate.startsWith("{{") || candidate.startsWith("<")) {
    return null;
  }
  const parsed = safeUrlHost(candidate.includes("://") ? candidate : `https://${candidate}`);
  return parsed;
}

function isSafeEndpointHost(host) {
  return POLICY.safeEndpointHosts.some((allowed) => host === allowed || host.endsWith(`.${allowed}`))
    || host.endsWith(".example") || host.endsWith(".invalid") || host.endsWith(".test")
    || host === "invalid" || host === "test" || host.endsWith(".localhost");
}

function isExampleHost(host) {
  return ["example.com", "example.org", "example.net"].some(
    (allowed) => host === allowed || host.endsWith(`.${allowed}`),
  ) || host.endsWith(".example") || host.endsWith(".invalid") || host.endsWith(".test")
    || host === "invalid" || host === "test" || host === "localhost" || host.endsWith(".localhost")
    || host === "users.noreply.github.com";
}

function isNonPersonalInfrastructureEmail(value) {
  const lower = value.toLowerCase();
  return lower === "git@github.com" || lower === "noreply@github.com";
}

function isCommentOnlyMatch(text, offset) {
  const lineStart = text.lastIndexOf("\n", offset - 1) + 1;
  const prefix = text.slice(lineStart, offset).trimStart();
  return prefix.startsWith("#") || prefix.startsWith("//");
}

function isValidIpv4(value) {
  const octets = value.split(".");
  return octets.length === 4 && octets.every((octet) => /^\d{1,3}$/.test(octet)
    && Number(octet) >= 0 && Number(octet) <= 255);
}

function isDocumentationIpv4(value) {
  const [first, second] = value.split(".").map(Number);
  return first === 0 || first === 127
    || (first === 192 && second === 0 && value.startsWith("192.0.2."))
    || (first === 198 && second === 51 && value.startsWith("198.51.100."))
    || (first === 203 && second === 0 && value.startsWith("203.0.113."));
}

function isPlaceholderValue(value) {
  const lower = value.toLowerCase();
  if (lower.length === 0) return true;
  if (/^(?:change[-_]?me|dummy|example|fake|none|null|password|placeholder|redacted|replace[-_]?me|test|unset|your[-_].*)$/.test(lower)) {
    return true;
  }
  if (/^(?:\$\{[^}]+}|\{\{[^}]+}}|<[^>]+>|__[^_]+__)$/.test(value)) return true;
  const host = safeUrlHost(value);
  return host !== null && isSafeEndpointHost(host);
}

function pathContainsSensitiveValue(value, privateTerms) {
  const lower = value.toLowerCase();
  if (privateTerms.some((spec) => privateTermMatches(lower, spec))) return true;
  if (HIGH_CONFIDENCE_PATTERNS.some(([, pattern]) =>
    new RegExp(pattern.source, pattern.flags.replaceAll("g", "")).test(value))) return true;
  const email = new RegExp(EMAIL_PATTERN.source, EMAIL_PATTERN.flags.replaceAll("g", "")).exec(value);
  if (email !== null && !isExampleHost(email[0].slice(email[0].lastIndexOf("@") + 1).toLowerCase())) {
    return true;
  }
  if ([E164_PATTERN, CN_MOBILE_PATTERN, CN_ID_PATTERN, MAC_PATTERN].some((pattern) =>
    new RegExp(pattern.source, pattern.flags.replaceAll("g", "")).test(value))) return true;
  const ipv4 = new RegExp(IPV4_PATTERN.source, IPV4_PATTERN.flags.replaceAll("g", "")).exec(value);
  return ipv4 !== null && isValidIpv4(ipv4[0]) && !isDocumentationIpv4(ipv4[0]);
}

function safeEntryLabel(entry, privateTerms) {
  if (!entry.pathUtf8 || pathContainsSensitiveValue(entry.path, privateTerms)
      || /[\u0000-\u001f\u007f]/.test(entry.path)) {
    return `[redacted-entry-${entry.ordinal}]`;
  }
  return entry.path;
}

function deduplicateFindings(findings) {
  const seen = new Set();
  return findings.filter((finding) => {
    const key = canonicalJson(finding);
    if (seen.has(key)) return false;
    seen.add(key);
    return true;
  });
}

function buildReport({
  mode, surface, findings, privateWordlistCount, privateTermCount,
  apkThirdPartyPolicy, thirdPartyProfile, trustedThirdPartyEntryCount,
  declaredDexStringCount, matchedDexStringCount,
}) {
  const scannerBytes = fs.readFileSync(SCANNER_FILENAME);
  const base = {
    schemaVersion: SCHEMA_VERSION,
    scannerSha256: sha256(scannerBytes),
    policySha256: apkThirdPartyPolicy.policySha256,
    input: {
      mode,
      ...surface.identity,
      entryCount: surface.entries.length,
    },
    privatePolicy: {
      applied: privateWordlistCount > 0,
      wordlistCount: privateWordlistCount,
      termCount: privateTermCount,
    },
    thirdPartyPolicy: {
      manifestSha256: apkThirdPartyPolicy.manifestSha256,
      applied: mode === "apk",
      profileId: thirdPartyProfile?.id ?? null,
      matchedEntryCount: trustedThirdPartyEntryCount,
      declaredDexStringCount,
      matchedDexStringCount,
      applicationDexPolicy: "all application descriptors and non-provenanced DEX strings remain strictly scanned",
    },
    summary: {
      passed: findings.length === 0,
      findingCount: findings.length,
    },
    findings,
  };
  return { ...base, reportSha256: sha256(canonicalJson(base)) };
}

export function runAudit(options) {
  const privateWordlists = options.privateWordlists ?? [];
  const resolvedRepo = path.resolve(options.repo ?? process.cwd());
  if (options.mode === "git-tree" || options.mode === "worktree") {
    for (const filename of privateWordlists) {
      if (inside(resolvedRepo, path.resolve(filename))) {
        throw new Error("private wordlists must stay outside the audited repository");
      }
    }
  }
  const privateTerms = readPrivateTerms(privateWordlists);
  const apkThirdPartyPolicy = loadApkThirdPartyPolicy();
  let surface;
  if (options.mode === "git-tree") surface = loadGitTree(resolvedRepo, options.input);
  else if (options.mode === "worktree") surface = loadWorktree(resolvedRepo);
  else if (options.mode === "apk") surface = loadApk(path.resolve(options.input));
  else if (options.mode === "file") surface = loadFile(path.resolve(options.input));
  else throw new Error("unknown audit mode");
  const audit = auditSurface(surface, options.mode, privateTerms, apkThirdPartyPolicy);
  return buildReport({
    mode: options.mode,
    surface,
    findings: audit.findings,
    privateWordlistCount: privateWordlists.length,
    privateTermCount: privateTerms.length,
    apkThirdPartyPolicy,
    thirdPartyProfile: audit.thirdPartyProfile,
    trustedThirdPartyEntryCount: audit.trustedThirdPartyEntryCount,
    declaredDexStringCount: audit.declaredDexStringCount,
    matchedDexStringCount: audit.matchedDexStringCount,
  });
}

function main() {
  try {
    const options = parseArgs(process.argv.slice(2));
    if (options.help) {
      process.stdout.write(`${usage()}\n`);
      return;
    }
    const report = runAudit(options);
    process.stdout.write(`${options.pretty ? JSON.stringify(canonicalize(report), null, 2) : canonicalJson(report)}\n`);
    if (!report.summary.passed) process.exitCode = 1;
  } catch {
    // Operational errors intentionally omit paths, command output, and exception text.
    process.stderr.write("public surface audit could not complete; no values were emitted\n");
    process.exitCode = 2;
  }
}

const isMain = process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url);
if (isMain) main();
