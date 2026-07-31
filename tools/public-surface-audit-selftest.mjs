#!/usr/bin/env node

import assert from "node:assert/strict";
import crypto from "node:crypto";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import process from "node:process";
import { spawnSync } from "node:child_process";
import { fileURLToPath } from "node:url";
import zlib from "node:zlib";
import {
  dexCodeItemHeaderRanges,
  dexStringIdOffsetRanges,
  dexStringRecords,
  dexTypeDescriptors,
  partitionDexStrings,
  runAudit,
  selectApkThirdPartyProfile,
} from "./public-surface-audit.mjs";

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");
const scanner = path.join(root, "tools/public-surface-audit.mjs");
const temporary = fs.mkdtempSync(path.join(os.tmpdir(), "public-surface-audit-selftest-"));

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

function sha256(value) {
  return crypto.createHash("sha256").update(value).digest("hex");
}

function execute(program, args, cwd) {
  const result = spawnSync(program, args, {
    cwd,
    encoding: "utf8",
    stdio: ["ignore", "pipe", "pipe"],
  });
  if (result.error) throw result.error;
  return result;
}

function git(repo, args) {
  const result = execute("git", args, repo);
  assert.equal(result.status, 0, "temporary Git operation failed");
  return result.stdout;
}

function audit(args, cli = false) {
  if (cli) {
    const result = execute(process.execPath, [scanner, ...args], root);
    let report = null;
    if (result.stdout.trim().length > 0) report = JSON.parse(result.stdout);
    return { ...result, report };
  }
  const report = runAudit(parseAuditOptions(args));
  return {
    status: report.summary.passed ? 0 : 1,
    signal: null,
    stdout: `${canonicalJson(report)}\n`,
    stderr: "",
    report,
  };
}

function parseAuditOptions(args) {
  const options = { repo: root, privateWordlists: [] };
  for (let index = 0; index < args.length; index += 1) {
    const argument = args[index];
    if (argument === "--worktree") options.mode = "worktree";
    else if (argument === "--git-tree") {
      options.mode = "git-tree";
      options.input = args[++index];
    } else if (argument === "--apk") {
      options.mode = "apk";
      options.input = args[++index];
    } else if (argument === "--file") {
      options.mode = "file";
      options.input = args[++index];
    } else if (argument === "--repo") options.repo = args[++index];
    else if (argument === "--private-wordlist") options.privateWordlists.push(args[++index]);
    else throw new Error("unsupported self-test argument");
  }
  return options;
}

function assertReportIntegrity(report) {
  assert.match(report.scannerSha256, /^[a-f0-9]{64}$/);
  assert.match(report.input.sha256, /^[a-f0-9]{64}$/);
  assert.match(report.reportSha256, /^[a-f0-9]{64}$/);
  assert.equal(report.scannerSha256, sha256(fs.readFileSync(scanner)));
  const unsigned = { ...report };
  delete unsigned.reportSha256;
  assert.equal(report.reportSha256, sha256(canonicalJson(unsigned)));
}

function findingRules(report) {
  return new Set(report.findings.map((finding) => finding.ruleId));
}

function writeStoredOrDeflatedZip(filename, entries, archiveComment = "") {
  const localParts = [];
  const centralParts = [];
  let localOffset = 0;
  for (const entry of entries) {
    const name = Buffer.from(entry.name, "utf8");
    const content = Buffer.isBuffer(entry.content) ? entry.content : Buffer.from(entry.content, "utf8");
    const method = entry.deflate ? 8 : 0;
    const compressed = entry.deflate ? zlib.deflateRawSync(content) : content;
    const checksum = crc32(content);

    const local = Buffer.alloc(30);
    local.writeUInt32LE(0x04034b50, 0);
    local.writeUInt16LE(20, 4);
    local.writeUInt16LE(0x800, 6);
    local.writeUInt16LE(method, 8);
    local.writeUInt32LE(checksum, 14);
    local.writeUInt32LE(compressed.length, 18);
    local.writeUInt32LE(content.length, 22);
    local.writeUInt16LE(name.length, 26);
    localParts.push(local, name, compressed);

    const central = Buffer.alloc(46);
    central.writeUInt32LE(0x02014b50, 0);
    central.writeUInt16LE(20, 4);
    central.writeUInt16LE(20, 6);
    central.writeUInt16LE(0x800, 8);
    central.writeUInt16LE(method, 10);
    central.writeUInt32LE(checksum, 16);
    central.writeUInt32LE(compressed.length, 20);
    central.writeUInt32LE(content.length, 24);
    central.writeUInt16LE(name.length, 28);
    central.writeUInt32LE(localOffset, 42);
    centralParts.push(central, name);
    localOffset += local.length + name.length + compressed.length;
  }

  const centralDirectory = Buffer.concat(centralParts);
  const end = Buffer.alloc(22);
  const comment = Buffer.from(archiveComment, "utf8");
  end.writeUInt32LE(0x06054b50, 0);
  end.writeUInt16LE(entries.length, 8);
  end.writeUInt16LE(entries.length, 10);
  end.writeUInt32LE(centralDirectory.length, 12);
  end.writeUInt32LE(localOffset, 16);
  end.writeUInt16LE(comment.length, 20);
  fs.writeFileSync(filename, Buffer.concat([...localParts, centralDirectory, end, comment]));
}

function syntheticDex(descriptor) {
  return syntheticDexStrings([descriptor], [0]);
}

function syntheticDexStrings(strings, typeStringIndexes) {
  const encoded = strings.map((value) => {
    const bytes = Buffer.from(value, "utf8");
    assert.ok(value.length < 0x80 && bytes.length < 0x80);
    return Buffer.concat([Buffer.from([value.length]), bytes, Buffer.from([0])]);
  });
  const stringIdsOffset = 112;
  const typeIdsOffset = stringIdsOffset + strings.length * 4;
  const dataOffset = typeIdsOffset + typeStringIndexes.length * 4;
  const output = Buffer.alloc(dataOffset + encoded.reduce((sum, value) => sum + value.length, 0));
  Buffer.from("dex\n035\0", "latin1").copy(output, 0);
  output.writeUInt32LE(output.length, 32);
  output.writeUInt32LE(112, 36);
  output.writeUInt32LE(0x12345678, 40);
  output.writeUInt32LE(strings.length, 56);
  output.writeUInt32LE(stringIdsOffset, 60);
  output.writeUInt32LE(typeStringIndexes.length, 64);
  output.writeUInt32LE(typeIdsOffset, 68);
  let cursor = dataOffset;
  encoded.forEach((value, index) => {
    output.writeUInt32LE(cursor, stringIdsOffset + index * 4);
    value.copy(output, cursor);
    cursor += value.length;
  });
  typeStringIndexes.forEach((value, index) => {
    output.writeUInt32LE(value, typeIdsOffset + index * 4);
  });
  return output;
}

function encodeUleb128(value) {
  const bytes = [];
  let remaining = value >>> 0;
  do {
    let byte = remaining & 0x7f;
    remaining >>>= 7;
    if (remaining !== 0) byte |= 0x80;
    bytes.push(byte);
  } while (remaining !== 0);
  return Buffer.from(bytes);
}

function align4(value) {
  return (value + 3) & ~3;
}

function syntheticExecutableDex({ descriptor, debugInfoOffset = 0, byteArrayPayload = null }) {
  const strings = [descriptor, "run", "V"];
  const encodedStrings = strings.map((value) => {
    const bytes = Buffer.from(value, "utf8");
    assert.ok(value.length < 0x80 && bytes.length < 0x80);
    return Buffer.concat([Buffer.from([value.length]), bytes, Buffer.from([0])]);
  });
  const stringIdsOffset = 112;
  const typeIdsOffset = align4(stringIdsOffset + strings.length * 4);
  const protoIdsOffset = align4(typeIdsOffset + 2 * 4);
  const methodIdsOffset = align4(protoIdsOffset + 12);
  const classDefsOffset = align4(methodIdsOffset + 8);
  const dataOffset = align4(classDefsOffset + 32);
  const stringOffsets = [];
  let cursor = dataOffset;
  for (const encoded of encodedStrings) {
    stringOffsets.push(cursor);
    cursor += encoded.length;
  }
  const classDataOffset = cursor;
  let codeOffset = align4(classDataOffset + 8);
  let classData;
  for (;;) {
    classData = Buffer.concat([
      Buffer.from([0, 0, 1, 0, 0, 1]),
      encodeUleb128(codeOffset),
    ]);
    const nextCodeOffset = align4(classDataOffset + classData.length);
    if (nextCodeOffset === codeOffset) break;
    codeOffset = nextCodeOffset;
  }

  let instructions;
  if (byteArrayPayload === null) {
    instructions = Buffer.from([0x0e, 0x00]);
  } else {
    assert.equal(byteArrayPayload.length, 3);
    instructions = Buffer.alloc(20);
    instructions.writeUInt16LE(0x0026, 0); // fill-array-data v0, payload at +4 code units
    instructions.writeInt32LE(4, 2);
    instructions.writeUInt16LE(0x000e, 6); // return-void
    instructions.writeUInt16LE(0x0300, 8); // fill-array-data-payload
    instructions.writeUInt16LE(1, 10); // element width
    instructions.writeUInt32LE(byteArrayPayload.length, 12);
    byteArrayPayload.copy(instructions, 16);
  }
  const codeEnd = codeOffset + 16 + instructions.length;
  const mapOffset = align4(codeEnd);
  const mapEntryCount = 10 + (debugInfoOffset === 0 ? 0 : 1);
  const debugSectionOffset = debugInfoOffset === 0
    ? 0 : align4(mapOffset + 4 + mapEntryCount * 12);
  if (debugInfoOffset !== 0) assert.ok(debugInfoOffset > debugSectionOffset + 3);
  const mapEntries = [
    { type: 0x0000, size: 1, offset: 0 },
    { type: 0x0001, size: strings.length, offset: stringIdsOffset },
    { type: 0x0002, size: 2, offset: typeIdsOffset },
    { type: 0x0003, size: 1, offset: protoIdsOffset },
    { type: 0x0005, size: 1, offset: methodIdsOffset },
    { type: 0x0006, size: 1, offset: classDefsOffset },
    { type: 0x2002, size: strings.length, offset: dataOffset },
    { type: 0x2000, size: 1, offset: classDataOffset },
    { type: 0x2001, size: 1, offset: codeOffset },
    { type: 0x1000, size: 1, offset: mapOffset },
    ...(debugInfoOffset === 0 ? []
      : [{ type: 0x2003, size: 2, offset: debugSectionOffset }]),
  ].sort((left, right) => left.offset - right.offset);
  const mapBytes = Buffer.alloc(4 + mapEntries.length * 12);
  mapBytes.writeUInt32LE(mapEntries.length, 0);
  mapEntries.forEach((entry, index) => {
    const offset = 4 + index * 12;
    mapBytes.writeUInt16LE(entry.type, offset);
    mapBytes.writeUInt32LE(entry.size, offset + 4);
    mapBytes.writeUInt32LE(entry.offset, offset + 8);
  });
  const outputLength = Math.max(mapOffset + mapBytes.length,
    debugInfoOffset === 0 ? 0 : debugInfoOffset + 3);
  const output = Buffer.alloc(outputLength);
  Buffer.from("dex\n035\0", "latin1").copy(output, 0);
  output.writeUInt32LE(output.length, 32);
  output.writeUInt32LE(112, 36);
  output.writeUInt32LE(0x12345678, 40);
  output.writeUInt32LE(mapOffset, 52);
  output.writeUInt32LE(strings.length, 56);
  output.writeUInt32LE(stringIdsOffset, 60);
  output.writeUInt32LE(2, 64);
  output.writeUInt32LE(typeIdsOffset, 68);
  output.writeUInt32LE(1, 72);
  output.writeUInt32LE(protoIdsOffset, 76);
  output.writeUInt32LE(1, 88);
  output.writeUInt32LE(methodIdsOffset, 92);
  output.writeUInt32LE(1, 96);
  output.writeUInt32LE(classDefsOffset, 100);
  output.writeUInt32LE(output.length - dataOffset, 104);
  output.writeUInt32LE(dataOffset, 108);
  stringOffsets.forEach((offset, index) => output.writeUInt32LE(offset, stringIdsOffset + index * 4));
  output.writeUInt32LE(0, typeIdsOffset);
  output.writeUInt32LE(2, typeIdsOffset + 4);
  output.writeUInt32LE(2, protoIdsOffset);
  output.writeUInt32LE(1, protoIdsOffset + 4);
  output.writeUInt16LE(0, methodIdsOffset);
  output.writeUInt16LE(0, methodIdsOffset + 2);
  output.writeUInt32LE(1, methodIdsOffset + 4);
  output.writeUInt32LE(0, classDefsOffset);
  output.writeUInt32LE(1, classDefsOffset + 4);
  output.writeUInt32LE(0xffffffff, classDefsOffset + 8);
  output.writeUInt32LE(0xffffffff, classDefsOffset + 16);
  output.writeUInt32LE(classDataOffset, classDefsOffset + 24);
  encodedStrings.forEach((encoded, index) => encoded.copy(output, stringOffsets[index]));
  classData.copy(output, classDataOffset);
  output.writeUInt16LE(1, codeOffset);
  output.writeUInt32LE(debugInfoOffset, codeOffset + 8);
  output.writeUInt32LE(instructions.length / 2, codeOffset + 12);
  instructions.copy(output, codeOffset + 16);
  mapBytes.copy(output, mapOffset);
  if (debugInfoOffset !== 0) {
    Buffer.from([1, 0, 0]).copy(output, debugSectionOffset);
    Buffer.from([1, 0, 0]).copy(output, debugInfoOffset);
  }
  return output;
}

function syntheticDexWithStringDataOffset(descriptor, stringDataOffset) {
  const encoded = Buffer.concat([
    Buffer.from([descriptor.length]), Buffer.from(descriptor, "utf8"), Buffer.from([0]),
  ]);
  assert.ok(descriptor.length < 0x80 && Buffer.byteLength(descriptor, "utf8") === descriptor.length);
  const stringIdsOffset = 112;
  const typeIdsOffset = 116;
  const dataOffset = 120;
  assert.ok(stringDataOffset >= dataOffset);
  const output = Buffer.alloc(stringDataOffset + encoded.length);
  Buffer.from("dex\n035\0", "latin1").copy(output, 0);
  output.writeUInt32LE(output.length, 32);
  output.writeUInt32LE(112, 36);
  output.writeUInt32LE(0x12345678, 40);
  output.writeUInt32LE(1, 56);
  output.writeUInt32LE(stringIdsOffset, 60);
  output.writeUInt32LE(1, 64);
  output.writeUInt32LE(typeIdsOffset, 68);
  output.writeUInt32LE(output.length - dataOffset, 104);
  output.writeUInt32LE(dataOffset, 108);
  output.writeUInt32LE(stringDataOffset, stringIdsOffset);
  output.writeUInt32LE(0, typeIdsOffset);
  encoded.copy(output, stringDataOffset);
  return output;
}

function syntheticDexWithOverlappingCodeHeader(shortTokenBytes) {
  assert.equal(shortTokenBytes.length, 3);
  const strings = ["Lcom/example/OverlappingCodeFixture;", "first", "second", "V"];
  const encodedStrings = strings.map((value) => Buffer.concat([
    Buffer.from([value.length]), Buffer.from(value, "utf8"), Buffer.from([0]),
  ]));
  const stringIdsOffset = 112;
  const typeIdsOffset = align4(stringIdsOffset + strings.length * 4);
  const protoIdsOffset = align4(typeIdsOffset + 8);
  const methodIdsOffset = align4(protoIdsOffset + 12);
  const classDefsOffset = align4(methodIdsOffset + 16);
  const dataOffset = align4(classDefsOffset + 32);
  const stringOffsets = [];
  let cursor = dataOffset;
  for (const encoded of encodedStrings) {
    stringOffsets.push(cursor);
    cursor += encoded.length;
  }
  const classDataOffset = cursor;
  let codeOffset = align4(classDataOffset + 16);
  let classData;
  for (;;) {
    const overlappingCodeOffset = codeOffset + 16;
    classData = Buffer.concat([
      Buffer.from([0, 0, 2, 0]),
      Buffer.from([0, 1]), encodeUleb128(codeOffset),
      Buffer.from([1, 1]), encodeUleb128(overlappingCodeOffset),
    ]);
    const nextCodeOffset = align4(classDataOffset + classData.length);
    if (nextCodeOffset === codeOffset) break;
    codeOffset = nextCodeOffset;
  }
  const overlappingCodeOffset = codeOffset + 16;
  const firstCodeEnd = codeOffset + 16 + 32;
  const mapOffset = align4(firstCodeEnd);
  const mapEntries = [
    { type: 0x0000, size: 1, offset: 0 },
    { type: 0x0001, size: strings.length, offset: stringIdsOffset },
    { type: 0x0002, size: 2, offset: typeIdsOffset },
    { type: 0x0003, size: 1, offset: protoIdsOffset },
    { type: 0x0005, size: 2, offset: methodIdsOffset },
    { type: 0x0006, size: 1, offset: classDefsOffset },
    { type: 0x2002, size: strings.length, offset: dataOffset },
    { type: 0x2000, size: 1, offset: classDataOffset },
    { type: 0x2001, size: 2, offset: codeOffset },
    { type: 0x1000, size: 1, offset: mapOffset },
  ];
  const mapBytes = Buffer.alloc(4 + mapEntries.length * 12);
  mapBytes.writeUInt32LE(mapEntries.length, 0);
  mapEntries.forEach((entry, index) => {
    const offset = 4 + index * 12;
    mapBytes.writeUInt16LE(entry.type, offset);
    mapBytes.writeUInt32LE(entry.size, offset + 4);
    mapBytes.writeUInt32LE(entry.offset, offset + 8);
  });
  const output = Buffer.alloc(mapOffset + mapBytes.length);
  Buffer.from("dex\n035\0", "latin1").copy(output, 0);
  output.writeUInt32LE(output.length, 32);
  output.writeUInt32LE(112, 36);
  output.writeUInt32LE(0x12345678, 40);
  output.writeUInt32LE(mapOffset, 52);
  output.writeUInt32LE(strings.length, 56);
  output.writeUInt32LE(stringIdsOffset, 60);
  output.writeUInt32LE(2, 64);
  output.writeUInt32LE(typeIdsOffset, 68);
  output.writeUInt32LE(1, 72);
  output.writeUInt32LE(protoIdsOffset, 76);
  output.writeUInt32LE(2, 88);
  output.writeUInt32LE(methodIdsOffset, 92);
  output.writeUInt32LE(1, 96);
  output.writeUInt32LE(classDefsOffset, 100);
  output.writeUInt32LE(output.length - dataOffset, 104);
  output.writeUInt32LE(dataOffset, 108);
  stringOffsets.forEach((offset, index) => output.writeUInt32LE(offset, stringIdsOffset + index * 4));
  output.writeUInt32LE(0, typeIdsOffset);
  output.writeUInt32LE(3, typeIdsOffset + 4);
  output.writeUInt32LE(3, protoIdsOffset);
  output.writeUInt32LE(1, protoIdsOffset + 4);
  for (let index = 0; index < 2; index += 1) {
    output.writeUInt16LE(0, methodIdsOffset + index * 8);
    output.writeUInt16LE(0, methodIdsOffset + index * 8 + 2);
    output.writeUInt32LE(index + 1, methodIdsOffset + index * 8 + 4);
  }
  output.writeUInt32LE(0, classDefsOffset);
  output.writeUInt32LE(1, classDefsOffset + 4);
  output.writeUInt32LE(0xffffffff, classDefsOffset + 8);
  output.writeUInt32LE(0xffffffff, classDefsOffset + 16);
  output.writeUInt32LE(classDataOffset, classDefsOffset + 24);
  encodedStrings.forEach((encoded, index) => encoded.copy(output, stringOffsets[index]));
  classData.copy(output, classDataOffset);
  output.writeUInt16LE(1, codeOffset);
  output.writeUInt32LE(16, codeOffset + 12);
  shortTokenBytes.copy(output, overlappingCodeOffset);
  mapBytes.copy(output, mapOffset);
  return output;
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

try {
  const publicThirdPartyPolicy = JSON.parse(
    fs.readFileSync(path.join(root, "tools/apk-third-party-components.json"), "utf8"));
  const releaseProfile = publicThirdPartyPolicy.profiles.find(
    (profile) => profile.buildType === "release");
  assert.ok(releaseProfile);
  assert.ok(!releaseProfile.entries.some((entry) => entry.path === "classes.dex"),
    "the mixed first-party release DEX must never become a trusted whole-entry");
  assert.ok(releaseProfile.entries
    .filter((entry) => entry.kind === "runtime-profile")
    .every((entry) => publicThirdPartyPolicy.components.some((component) =>
      component.id === entry.component
        && component.runtimeProfileSources?.mergedTextSha256 === component.sourceArtifactSha256
        && component.runtimeProfileSources.artifacts.length > 0)));

  const ignoreRepo = path.join(temporary, "ignore-repo");
  fs.mkdirSync(ignoreRepo);
  git(ignoreRepo, ["init", "--quiet"]);
  fs.copyFileSync(path.join(root, ".gitignore"), path.join(ignoreRepo, ".gitignore"));
  const assertIgnored = (relativePath, expected) => {
    const result = execute("git", ["check-ignore", "--no-index", "--quiet", "--", relativePath], ignoreRepo);
    assert.equal(result.status, expected ? 0 : 1,
      `${relativePath} must ${expected ? "be" : "not be"} ignored by the public repository policy`);
  };
  for (const relativePath of [
    "form-profiles.json",
    "nested/export/form-profiles.json",
    "nested/panel-settings.json",
    "app/assets/manifest.json",
    "app/src/release/assets/manifest.json",
    "nested/catalog/manifest.json",
    "nested/catalog/v42/manifest.json",
    "nested/catalog-exports/v42/manifest.json",
    "nested/private-catalog/v42/manifest.json",
  ]) assertIgnored(relativePath, true);
  for (const relativePath of [
    "app/assets/form-profiles.seed.json",
    "fixtures/form-profiles.example.json",
    "fixtures/panel-settings.sample.json",
    "fixtures/manifest.json",
    "fixtures/catalog/manifest.example.json",
    "docs/manifest.json",
    "vendor/example-dependency/manifest.json",
  ]) assertIgnored(relativePath, false);

  const repo = path.join(temporary, "repo");
  fs.mkdirSync(repo);
  git(repo, ["init", "--quiet"]);
  git(repo, ["config", "user.name", "Audit Self Test"]);
  git(repo, ["config", "user.email", "audit@example.com"]);
  fs.writeFileSync(path.join(repo, "README.md"), "# Generic public framework\n", "utf8");
  git(repo, ["add", "README.md"]);
  git(repo, ["commit", "--quiet", "-m", "safe fixture"]);

  const safeTree = audit(["--git-tree", "HEAD", "--repo", repo], true);
  assert.equal(safeTree.status, 0);
  assert.equal(safeTree.report.summary.passed, true);
  assert.equal(safeTree.report.input.mode, "git-tree");
  assert.match(safeTree.report.input.gitTreeOid, /^[a-f0-9]{40,64}$/);
  assertReportIntegrity(safeTree.report);

  const safeTreeAgain = audit(["--git-tree", "HEAD", "--repo", repo]);
  assert.equal(safeTreeAgain.stdout, safeTree.stdout, "the same exact tree must produce the same report");

  const safeWorktree = audit(["--worktree", "--repo", repo]);
  assert.equal(safeWorktree.status, 0);
  assert.equal(safeWorktree.report.summary.passed, true);
  assertReportIntegrity(safeWorktree.report);

  const placeholderConfig = path.join(repo, "settings.example.json");
  fs.writeFileSync(placeholderConfig, JSON.stringify({
    apiKey: "${API_KEY}",
    endpoint: "https://api.example.com/v1",
    password: "password",
  }), "utf8");
  const placeholderConfigAudit = audit(["--worktree", "--repo", repo]);
  assert.equal(placeholderConfigAudit.status, 0, "external-value placeholders must remain public-safe");
  fs.unlinkSync(placeholderConfig);

  const genericInfrastructure = path.join(repo, "wrangler.example.toml");
  fs.writeFileSync(genericInfrastructure,
    "# password: the operator enters it in the browser\nremote = 'git@github.com:example/repo'\n",
    "utf8");
  const genericInfrastructureAudit = audit(["--worktree", "--repo", repo]);
  assert.equal(genericInfrastructureAudit.status, 0,
    "schema prose and the standard GitHub SSH principal are not credentials or personal email");
  fs.unlinkSync(genericInfrastructure);

  const pairingDocsDir = path.join(repo, "docs");
  const pairingProtocolDoc = path.join(pairingDocsDir, "app-pairing.md");
  fs.mkdirSync(pairingDocsDir);
  fs.writeFileSync(pairingProtocolDoc, [
    "# Generic pairing protocol",
    "origin = \"https://panel.example.invalid\"",
    "ticket = \"<ISSUED_PAIRING_TICKET>\"",
    "accessKey = \"${PANEL_ACCESS_KEY}\"",
    "intent://pair/v1?ticket=${PAIRING_TICKET}",
    "",
  ].join("\n"), "utf8");
  const safePairingProtocol = audit(["--worktree", "--repo", repo]);
  assert.equal(safePairingProtocol.status, 0,
    "the pairing protocol must allow example.invalid endpoints and explicit placeholders");

  const syntheticPairingCredential = [
    "fictional", "pairing", process.pid, "credential",
  ].join("-");
  const syntheticPairingEndpoint = "https://192.0.2.123";
  fs.writeFileSync(pairingProtocolDoc, [
    "# Unsafe pairing protocol fixture",
    `origin = \"${syntheticPairingEndpoint}\"`,
    `ticket = \"${syntheticPairingCredential}\"`,
    `accessKey = \"${syntheticPairingCredential}\"`,
    `intent://pair/v1?ticket=${syntheticPairingCredential}`,
    "",
  ].join("\n"), "utf8");
  const unsafePairingProtocol = audit(["--worktree", "--repo", repo]);
  assert.equal(unsafePairingProtocol.status, 1,
    "the pairing protocol path must reject non-example endpoints and literal credentials");
  assert.ok(unsafePairingProtocol.report.findings.some((finding) =>
    finding.entry === "docs/app-pairing.md"
      && finding.ruleId === "non-public-endpoint-in-example-surface"));
  assert.ok(unsafePairingProtocol.report.findings.some((finding) =>
    finding.entry === "docs/app-pairing.md"
      && finding.ruleId === "credential-assignment"));
  assert.ok(!unsafePairingProtocol.stdout.includes(syntheticPairingCredential));
  assert.ok(!unsafePairingProtocol.stdout.includes(syntheticPairingEndpoint));
  fs.unlinkSync(pairingProtocolDoc);
  fs.rmdirSync(pairingDocsDir);

  const publicFixturePaths = [
    "fixtures/form-profiles.example.json",
    "fixtures/panel-settings.sample.json",
    "fixtures/manifest.json",
    "fixtures/catalog/manifest.example.json",
    "app/assets/form-profiles.seed.json",
    "vendor/example-dependency/manifest.json",
  ];
  for (const relativePath of publicFixturePaths) {
    const filename = path.join(repo, relativePath);
    fs.mkdirSync(path.dirname(filename), { recursive: true });
    fs.writeFileSync(filename, "{}\n", "utf8");
  }
  const publicFixturesAudit = audit(["--worktree", "--repo", repo]);
  assert.equal(publicFixturesAudit.status, 0,
    "explicit public examples, generic fixtures, and the seed asset must remain publishable");
  for (const relativePath of publicFixturePaths) fs.unlinkSync(path.join(repo, relativePath));
  fs.rmSync(path.join(repo, "fixtures"), { recursive: true });
  fs.rmSync(path.join(repo, "vendor"), { recursive: true });

  const privateCatalogExportPaths = [
    "exports/form-profiles.json",
    "fixtures/panel-settings.json",
    "catalog/manifest.json",
    "backups/private-catalog/v42/manifest.json",
    "app/assets/manifest.json",
  ];
  for (const relativePath of privateCatalogExportPaths) {
    const filename = path.join(repo, relativePath);
    fs.mkdirSync(path.dirname(filename), { recursive: true });
    fs.writeFileSync(filename, "{}\n", "utf8");
  }
  const privateCatalogExportsAudit = audit(["--worktree", "--repo", repo]);
  assert.equal(privateCatalogExportsAudit.status, 1,
    "private catalog export paths must fail even when their contents are fictional");
  for (const relativePath of privateCatalogExportPaths) {
    assert.ok(privateCatalogExportsAudit.report.findings.some((finding) =>
      finding.entry === relativePath && finding.ruleId === "deployment-specific-path"),
    `${relativePath} must be rejected as a deployment-specific path`);
    fs.unlinkSync(path.join(repo, relativePath));
  }
  for (const relativePath of ["exports", "fixtures", "catalog", "backups"]) {
    fs.rmSync(path.join(repo, relativePath), { recursive: true });
  }
  const treeUnaffectedByPrivateExports = audit(["--git-tree", "HEAD", "--repo", repo]);
  assert.equal(treeUnaffectedByPrivateExports.status, 0,
    "private files in a dirty worktree must not affect an exact Git-tree audit");

  const syntheticAssignedSecret = ["fictional", "assigned", process.pid, "value"].join("-");
  fs.writeFileSync(placeholderConfig, JSON.stringify({ apiKey: syntheticAssignedSecret }), "utf8");
  const assignedSecretFinding = audit(["--worktree", "--repo", repo]);
  assert.equal(assignedSecretFinding.status, 1);
  assert.ok(findingRules(assignedSecretFinding.report).has("credential-assignment"));
  assert.ok(!assignedSecretFinding.stdout.includes(syntheticAssignedSecret));
  fs.unlinkSync(placeholderConfig);

  fs.writeFileSync(path.join(repo, "wrangler.toml"), "name = \"generic-example\"\n", "utf8");
  const deploymentPath = audit(["--worktree", "--repo", repo], true);
  assert.equal(deploymentPath.status, 1);
  assert.equal(deploymentPath.report.summary.passed, false);
  assert.ok(findingRules(deploymentPath.report).has("deployment-specific-path"));
  const unchangedTree = audit(["--git-tree", "HEAD", "--repo", repo]);
  assert.equal(unchangedTree.status, 0, "a dirty worktree must not change exact Git-tree input");
  assert.equal(unchangedTree.report.input.sha256, safeTree.report.input.sha256);
  fs.unlinkSync(path.join(repo, "wrangler.toml"));

  const marker = ["fictional", "private", process.pid, "marker"].join("-");
  const wordlist = path.join(temporary, "external-private-terms.txt");
  fs.writeFileSync(wordlist, `${marker}\n`, "utf8");
  const deploymentTokenWordlist = path.join(temporary, "external-deployment-terms.json");
  fs.writeFileSync(deploymentTokenWordlist,
    JSON.stringify([{ term: marker, matchMode: "deployment-token" }]), "utf8");
  fs.writeFileSync(path.join(repo, "notes.txt"), `contains ${marker}\n`, "utf8");
  const privateFinding = audit([
    "--worktree", "--repo", repo, "--private-wordlist", wordlist,
  ]);
  assert.equal(privateFinding.status, 1);
  assert.ok(findingRules(privateFinding.report).has("private-wordlist-term"));
  assert.equal(privateFinding.report.privatePolicy.applied, true);
  assert.equal(privateFinding.report.privatePolicy.termCount, 1);
  assert.ok(!privateFinding.stdout.includes(marker), "matched private terms must never be emitted");
  assert.ok(!privateFinding.stdout.includes(wordlist), "private wordlist paths must never be emitted");
  assert.ok(!privateFinding.stdout.includes(path.basename(wordlist)));
  fs.unlinkSync(path.join(repo, "notes.txt"));

  const releaseNotes = path.join(temporary, "release-notes.txt");
  fs.writeFileSync(releaseNotes, "Fictional public release notes\n", "utf8");
  const safeReleaseNotes = audit(["--file", releaseNotes, "--private-wordlist", wordlist], true);
  assert.equal(safeReleaseNotes.status, 0);
  assert.equal(safeReleaseNotes.report.input.mode, "file");
  assert.equal(safeReleaseNotes.report.input.selection, "exact-regular-file");
  assert.equal(safeReleaseNotes.report.input.entryCount, 1);
  assert.equal(safeReleaseNotes.report.input.sha256, sha256(fs.readFileSync(releaseNotes)));
  assert.ok(!safeReleaseNotes.stdout.includes(releaseNotes), "file input paths must not enter reports");
  assertReportIntegrity(safeReleaseNotes.report);

  fs.writeFileSync(releaseNotes, `contains ${marker}\n`, "utf8");
  const privateReleaseNotes = audit(["--file", releaseNotes, "--private-wordlist", wordlist]);
  assert.equal(privateReleaseNotes.status, 1);
  assert.ok(findingRules(privateReleaseNotes.report).has("private-wordlist-term"));
  assert.ok(!privateReleaseNotes.stdout.includes(marker));

  const multilineMarker = `${marker}\nsecond-private-line`;
  const jsonWordlist = path.join(temporary, "external-private-terms.json");
  fs.writeFileSync(jsonWordlist, JSON.stringify([multilineMarker]), "utf8");
  fs.writeFileSync(path.join(repo, "multiline.txt"), `before ${multilineMarker} after\n`, "utf8");
  const multilinePrivateFinding = audit([
    "--worktree", "--repo", repo, "--private-wordlist", jsonWordlist,
  ]);
  assert.equal(multilinePrivateFinding.status, 1);
  assert.ok(findingRules(multilinePrivateFinding.report).has("private-wordlist-term"));
  assert.ok(!multilinePrivateFinding.stdout.includes(marker));
  fs.unlinkSync(path.join(repo, "multiline.txt"));

  const privateFilename = `${marker}.txt`;
  fs.writeFileSync(path.join(repo, privateFilename), "generic fixture\n", "utf8");
  const privatePathFinding = audit([
    "--worktree", "--repo", repo, "--private-wordlist", wordlist,
  ]);
  assert.equal(privatePathFinding.status, 1);
  assert.ok(findingRules(privatePathFinding.report).has("sensitive-entry-name"));
  assert.ok(privatePathFinding.report.findings.some((finding) =>
    finding.entry.startsWith("[redacted-entry-")));
  assert.ok(!privatePathFinding.stdout.includes(marker), "sensitive entry names must be redacted");
  fs.unlinkSync(path.join(repo, privateFilename));

  const inRepositoryWordlist = path.join(repo, "private-terms.txt");
  fs.writeFileSync(inRepositoryWordlist, `${marker}\n`, "utf8");
  const rejectedInRepositoryWordlist = audit([
    "--worktree", "--repo", repo, "--private-wordlist", inRepositoryWordlist,
  ], true);
  assert.equal(rejectedInRepositoryWordlist.status, 2);
  assert.equal(rejectedInRepositoryWordlist.stdout, "");
  assert.ok(!rejectedInRepositoryWordlist.stderr.includes(inRepositoryWordlist));
  assert.ok(!rejectedInRepositoryWordlist.stderr.includes(marker));
  fs.unlinkSync(inRepositoryWordlist);

  const unknownRepositoryAsset = path.join(repo, "app/assets/internal-settings.json");
  fs.mkdirSync(path.dirname(unknownRepositoryAsset), { recursive: true });
  fs.writeFileSync(unknownRepositoryAsset, "{}\n", "utf8");
  const repositoryAssetFinding = audit(["--worktree", "--repo", repo]);
  assert.equal(repositoryAssetFinding.status, 1);
  assert.ok(findingRules(repositoryAssetFinding.report).has("unknown-first-party-asset"));
  fs.unlinkSync(unknownRepositoryAsset);

  const syntheticToken = ["AK", "IA", "A".repeat(16)].join("");
  fs.writeFileSync(path.join(repo, "token.txt"), `${syntheticToken}\n`, "utf8");
  const tokenFinding = audit(["--worktree", "--repo", repo]);
  assert.equal(tokenFinding.status, 1,
    `token fixture scan failed unexpectedly: signal=${tokenFinding.signal ?? "none"}`);
  assert.ok(findingRules(tokenFinding.report).has("credential-token"));
  assert.ok(!tokenFinding.stdout.includes(syntheticToken), "credential values must never be emitted");
  fs.unlinkSync(path.join(repo, "token.txt"));

  const syntheticEmail = ["operator", "@", "internal", ".production"].join("");
  const syntheticEndpoint = ["https://api", ".internal", ".production/v1"].join("");
  fs.writeFileSync(path.join(repo, "contact.txt"), `${syntheticEmail}\n`, "utf8");
  fs.writeFileSync(
    path.join(repo, "adapter.example.json"),
    JSON.stringify({ endpoint: syntheticEndpoint }),
    "utf8",
  );
  const piiAndEndpoint = audit(["--worktree", "--repo", repo]);
  assert.equal(piiAndEndpoint.status, 1);
  assert.ok(findingRules(piiAndEndpoint.report).has("pii-email"));
  assert.ok(findingRules(piiAndEndpoint.report).has("non-public-endpoint-in-example-surface"));
  assert.ok(!piiAndEndpoint.stdout.includes(syntheticEmail));
  assert.ok(!piiAndEndpoint.stdout.includes(syntheticEndpoint));
  fs.unlinkSync(path.join(repo, "contact.txt"));
  fs.unlinkSync(path.join(repo, "adapter.example.json"));

  const safeApk = path.join(temporary, "safe.apk");
  writeStoredOrDeflatedZip(safeApk, [
    {
      name: "AndroidManifest.xml",
      content: "<manifest package=\"com.example.framework\"/>",
    },
    {
      name: "assets/form-profiles.seed.json",
      content: JSON.stringify({ endpoint: "https://api.example.com/forms", profiles: [] }),
      deflate: true,
    },
    {
      name: "assets/update-config.json",
      content: JSON.stringify({ manifestUrl: "https://downloads.example.org/app.json" }),
    },
  ]);
  const safeApkAudit = audit(["--apk", safeApk]);
  assert.equal(safeApkAudit.status, 0);
  assert.equal(safeApkAudit.report.input.mode, "apk");
  assert.equal(safeApkAudit.report.input.entryCount, 3);
  assert.match(safeApkAudit.report.input.zipEntryManifestSha256, /^[a-f0-9]{64}$/);
  assertReportIntegrity(safeApkAudit.report);
  assert.ok(!safeApkAudit.stdout.includes(safeApk), "input paths must not enter the report");

  const safeApkAgain = audit(["--apk", safeApk]);
  assert.equal(safeApkAgain.stdout, safeApkAudit.stdout, "the same exact APK must be deterministic");

  // Android binary XML/resources can carry UTF-16LE strings inside an otherwise non-text entry.
  // Lock both byte alignments so an embedded string cannot evade the external private vocabulary
  // merely because the scanner does not treat the complete compiled entry as ordinary text.
  const oddAlignedUtf16Apk = path.join(temporary, "odd-aligned-utf16.apk");
  writeStoredOrDeflatedZip(oddAlignedUtf16Apk, [{
    name: "AndroidManifest.xml",
    content: Buffer.concat([Buffer.from([0x7f]), Buffer.from(marker, "utf16le")]),
  }]);
  const oddAlignedUtf16Finding = audit([
    "--apk", oddAlignedUtf16Apk, "--private-wordlist", deploymentTokenWordlist,
  ]);
  assert.equal(oddAlignedUtf16Finding.status, 1);
  assert.ok(findingRules(oddAlignedUtf16Finding.report).has("private-wordlist-term"));
  assert.ok(!oddAlignedUtf16Finding.stdout.includes(marker));

  const shortDeploymentTerm = "q70";
  const shortDeploymentWordlist = path.join(temporary, "external-short-deployment-terms.json");
  fs.writeFileSync(shortDeploymentWordlist, JSON.stringify([{
    term: shortDeploymentTerm,
    matchMode: "deployment-token",
  }]), "utf8");
  const shortTermBytes = Buffer.from(shortDeploymentTerm, "ascii");
  const numericDebugInfoOffset = shortTermBytes.readUIntLE(0, shortTermBytes.length);
  const numericHeaderDex = syntheticExecutableDex({
    descriptor: "Lcom/example/NumericHeaderFixture;",
    debugInfoOffset: numericDebugInfoOffset,
  });
  const numericHeaderRanges = dexCodeItemHeaderRanges(numericHeaderDex);
  assert.equal(numericHeaderRanges.length, 1);
  assert.equal(
    numericHeaderDex.subarray(numericHeaderRanges[0].start + 8,
      numericHeaderRanges[0].start + 11).equals(shortTermBytes),
    true,
    "the fixture must contain the short token only as numeric debug_info_off bytes",
  );
  const numericHeaderApk = path.join(temporary, "numeric-header-collision.apk");
  writeStoredOrDeflatedZip(numericHeaderApk, [{ name: "classes.dex", content: numericHeaderDex }]);
  const numericHeaderAudit = audit([
    "--apk", numericHeaderApk, "--private-wordlist", shortDeploymentWordlist,
  ]);
  assert.ok(!findingRules(numericHeaderAudit.report).has("private-wordlist-term"),
    "a three-byte token made solely from code_item numeric-header bytes is not a value leak");

  const overlappingCodeHeaderDex = syntheticDexWithOverlappingCodeHeader(shortTermBytes);
  assert.equal(dexStringIdOffsetRanges(overlappingCodeHeaderDex).length, 4);
  assert.throws(() => dexCodeItemHeaderRanges(overlappingCodeHeaderDex));
  const overlappingCodeHeaderApk = path.join(temporary, "overlapping-code-header.apk");
  writeStoredOrDeflatedZip(overlappingCodeHeaderApk,
    [{ name: "classes.dex", content: overlappingCodeHeaderDex }]);
  const overlappingCodeHeaderAudit = audit([
    "--apk", overlappingCodeHeaderApk, "--private-wordlist", shortDeploymentWordlist,
  ]);
  assert.ok(findingRules(overlappingCodeHeaderAudit.report).has("private-wordlist-term"),
    "a forged code_item header inside another method's instructions must fail closed");

  const numericStringOffsetDex = syntheticDexWithStringDataOffset(
    "Lcom/example/NumericStringOffsetFixture;", numericDebugInfoOffset);
  const numericStringOffsetRanges = dexStringIdOffsetRanges(numericStringOffsetDex);
  assert.deepEqual(numericStringOffsetRanges, [{ start: 112, end: 116 }]);
  assert.equal(numericStringOffsetDex.subarray(112, 115).equals(shortTermBytes), true,
    "the fixture must contain the short token only as numeric string_data offset bytes");
  const numericStringOffsetApk = path.join(temporary, "numeric-string-offset-collision.apk");
  writeStoredOrDeflatedZip(numericStringOffsetApk,
    [{ name: "classes.dex", content: numericStringOffsetDex }]);
  const numericStringOffsetAudit = audit([
    "--apk", numericStringOffsetApk, "--private-wordlist", shortDeploymentWordlist,
  ]);
  assert.ok(!findingRules(numericStringOffsetAudit.report).has("private-wordlist-term"),
    "a three-byte token made solely from a verified string_ids uint32 offset is not a value leak");

  const overlappingStringTableDex = Buffer.from(numericStringOffsetDex);
  overlappingStringTableDex.fill(0, 112, 116);
  overlappingStringTableDex.writeUInt32LE(120, 60);
  overlappingStringTableDex.writeUInt32LE(numericDebugInfoOffset, 120);
  assert.equal(dexStringRecords(overlappingStringTableDex).length, 1,
    "the adversarial offset must still point to a parseable string_data_item");
  assert.throws(() => dexStringIdOffsetRanges(overlappingStringTableDex));
  const overlappingStringTableApk = path.join(temporary, "overlapping-string-table.apk");
  writeStoredOrDeflatedZip(overlappingStringTableApk,
    [{ name: "classes.dex", content: overlappingStringTableDex }]);
  const overlappingStringTableAudit = audit([
    "--apk", overlappingStringTableApk, "--private-wordlist", shortDeploymentWordlist,
  ]);
  assert.ok(findingRules(overlappingStringTableAudit.report).has("private-wordlist-term"),
    "string_ids_off equal to data_off must disable numeric-table exclusion and fail closed");

  const malformedStringOffsetDex = Buffer.from(numericStringOffsetDex);
  malformedStringOffsetDex[115] = 1;
  assert.throws(() => dexStringIdOffsetRanges(malformedStringOffsetDex));
  const malformedStringOffsetApk = path.join(temporary, "malformed-string-offset.apk");
  writeStoredOrDeflatedZip(malformedStringOffsetApk,
    [{ name: "classes.dex", content: malformedStringOffsetDex }]);
  const malformedStringOffsetAudit = audit([
    "--apk", malformedStringOffsetApk, "--private-wordlist", shortDeploymentWordlist,
  ]);
  assert.ok(findingRules(malformedStringOffsetAudit.report).has("private-wordlist-term"),
    "a malformed string_data offset must disable numeric-table exclusion and fail closed");

  const shortStringDex = syntheticDexStrings([
    "Lcom/example/ShortStringFixture;", shortDeploymentTerm,
  ], [0]);
  const shortStringApk = path.join(temporary, "short-string.apk");
  writeStoredOrDeflatedZip(shortStringApk, [{ name: "classes.dex", content: shortStringDex }]);
  const shortStringAudit = audit([
    "--apk", shortStringApk, "--private-wordlist", shortDeploymentWordlist,
  ]);
  assert.ok(findingRules(shortStringAudit.report).has("private-wordlist-term"),
    "a real untrusted DEX string must remain blocked");

  const shortApplicationDex = syntheticDex(`Lcom/autoformkit/app/${shortDeploymentTerm};`);
  const shortApplicationApk = path.join(temporary, "short-application-string.apk");
  writeStoredOrDeflatedZip(shortApplicationApk,
    [{ name: "classes.dex", content: shortApplicationDex }]);
  const shortApplicationAudit = audit([
    "--apk", shortApplicationApk, "--private-wordlist", shortDeploymentWordlist,
  ]);
  assert.ok(findingRules(shortApplicationAudit.report).has("private-wordlist-term"),
    "application descriptors and strings must remain strictly scanned");

  const shortPayloadDex = syntheticExecutableDex({
    descriptor: "Lcom/example/ByteArrayFixture;",
    byteArrayPayload: shortTermBytes,
  });
  const shortPayloadApk = path.join(temporary, "short-byte-array-payload.apk");
  writeStoredOrDeflatedZip(shortPayloadApk, [{ name: "classes.dex", content: shortPayloadDex }]);
  const shortPayloadAudit = audit([
    "--apk", shortPayloadApk, "--private-wordlist", shortDeploymentWordlist,
  ]);
  assert.ok(findingRules(shortPayloadAudit.report).has("private-wordlist-term"),
    "an explicit compiled fill-array-data payload must remain blocked");

  const shortPathApk = path.join(temporary, "short-entry-path.apk");
  writeStoredOrDeflatedZip(shortPathApk,
    [{ name: `assets/${shortDeploymentTerm}.bin`, content: shortDeploymentTerm }]);
  const shortPathAudit = audit([
    "--apk", shortPathApk, "--private-wordlist", shortDeploymentWordlist,
  ]);
  assert.ok(findingRules(shortPathAudit.report).has("private-wordlist-term"),
    "APK asset contents must retain the short-token policy");
  assert.ok(findingRules(shortPathAudit.report).has("sensitive-entry-name"),
    "APK entry paths must retain the short-token policy");

  const malformedNumericHeaderDex = Buffer.from(numericHeaderDex);
  const numericClassDefsOffset = malformedNumericHeaderDex.readUInt32LE(100);
  malformedNumericHeaderDex.writeUInt32LE(
    malformedNumericHeaderDex.length + 4, numericClassDefsOffset + 24);
  assert.throws(() => dexCodeItemHeaderRanges(malformedNumericHeaderDex));
  const malformedNumericHeaderApk = path.join(temporary, "malformed-numeric-header.apk");
  writeStoredOrDeflatedZip(malformedNumericHeaderApk,
    [{ name: "classes.dex", content: malformedNumericHeaderDex }]);
  const malformedNumericHeaderAudit = audit([
    "--apk", malformedNumericHeaderApk, "--private-wordlist", shortDeploymentWordlist,
  ]);
  assert.ok(findingRules(malformedNumericHeaderAudit.report).has("private-wordlist-term"),
    "a DEX structure parse failure must fall back to the original conservative scan");

  const dependencyDex = syntheticDex("Lcom/example/Dependency;");
  const applicationDex = syntheticDex("Lcom/autoformkit/app/Injected;");
  assert.deepEqual(dexTypeDescriptors(dependencyDex), ["Lcom/example/Dependency;"]);
  assert.deepEqual(dexTypeDescriptors(applicationDex), ["Lcom/autoformkit/app/Injected;"]);
  const modelContent = Buffer.from("synthetic public dependency fixture\n", "utf8");
  const provenanceEntry = (entryPath, content, ordinal) => ({
    path: entryPath,
    originalPath: entryPath,
    pathUtf8: true,
    content,
    ordinal,
  });
  const provenancePolicy = {
    protectedEntryPrefixes: ["assets/example-dependency/"],
    protectedEntryBasenames: [],
    appNamespaceDescriptorPrefixes: ["Lcom/autoformkit/app/"],
    dexStringSources: new Map([
      [sha256(Buffer.from("selected public constant")), { component: "selected-component" }],
      [sha256(Buffer.from("unselected public constant")), { component: "other-component" }],
    ]),
    profiles: [
      {
        id: "safe-fixture",
        dexStringComponents: ["selected-component"],
        entries: [
          { path: "classes.dex", sha256: sha256(dependencyDex), kind: "dex" },
          { path: "assets/example-dependency/model.bin", sha256: sha256(modelContent), kind: "model" },
        ],
      },
      {
        id: "rejected-app-dex-fixture",
        entries: [
          { path: "classes.dex", sha256: sha256(applicationDex), kind: "dex" },
          { path: "assets/example-dependency/model.bin", sha256: sha256(modelContent), kind: "model" },
        ],
      },
    ],
  };
  const exactProvenance = selectApkThirdPartyProfile({ entries: [
    provenanceEntry("classes.dex", dependencyDex, 0),
    provenanceEntry("assets/example-dependency/model.bin", modelContent, 1),
  ] }, provenancePolicy, []);
  assert.equal(exactProvenance.profile.id, "safe-fixture");
  assert.equal(exactProvenance.findings.length, 0);
  assert.equal(exactProvenance.trustedEntries.size, 2);
  assert.deepEqual(
    [...exactProvenance.trustedDexStringSha256],
    [sha256(Buffer.from("selected public constant"))],
    "only DEX strings from components selected by the exact profile may be trusted",
  );

  const changedSamePath = selectApkThirdPartyProfile({ entries: [
    provenanceEntry("classes.dex", dependencyDex, 0),
    provenanceEntry("assets/example-dependency/model.bin", Buffer.from("changed"), 1),
  ] }, provenancePolicy, []);
  assert.equal(changedSamePath.profile, null, "the same path with different bytes is never trusted");
  assert.equal(changedSamePath.trustedDexStringSha256.size, 0,
    "an unmatched profile cannot activate source-string provenance");
  assert.ok(changedSamePath.findings.some(
    (finding) => finding.ruleId === "third-party-provenance-profile-unmatched"));

  const extraProtectedEntry = selectApkThirdPartyProfile({ entries: [
    provenanceEntry("classes.dex", dependencyDex, 0),
    provenanceEntry("assets/example-dependency/model.bin", modelContent, 1),
    provenanceEntry("assets/example-dependency/extra.bin", Buffer.from("extra"), 2),
  ] }, provenancePolicy, []);
  assert.equal(extraProtectedEntry.profile.id, "safe-fixture");
  assert.ok(extraProtectedEntry.findings.some(
    (finding) => finding.ruleId === "third-party-provenance-entry-unlisted"));

  const applicationDexRejected = selectApkThirdPartyProfile({ entries: [
    provenanceEntry("classes.dex", applicationDex, 0),
    provenanceEntry("assets/example-dependency/model.bin", modelContent, 1),
  ] }, provenancePolicy, []);
  assert.equal(applicationDexRejected.profile.id, "rejected-app-dex-fixture");
  assert.ok(applicationDexRejected.findings.some(
    (finding) => finding.ruleId === "application-code-in-third-party-dex"));
  assert.ok(!applicationDexRejected.trustedEntries.has("classes.dex"),
    "a dex containing application descriptors must remain strictly scanned");

  const exactTrustedString = `reviewed upstream ${marker} constant`;
  const changedTrustedString = exactTrustedString.replace("reviewed", "reviewXd");
  const mixedFirstPartyDex = syntheticDexStrings([
    "Lcom/autoformkit/app/ContainsPrivateData;",
    "[Lcom/autoformkit/app/ContainsPrivateData;",
    exactTrustedString,
    changedTrustedString,
    syntheticToken,
    syntheticEmail,
  ], [0, 1]);
  const exactTrustedDigest = sha256(Buffer.from(exactTrustedString));
  const applicationDescriptorDigest = sha256(
    Buffer.from("Lcom/autoformkit/app/ContainsPrivateData;"));
  const applicationArrayDescriptorDigest = sha256(
    Buffer.from("[Lcom/autoformkit/app/ContainsPrivateData;"));
  const unusedTrustedDigest = sha256(Buffer.from("absent reviewed constant"));
  const partitioned = partitionDexStrings(
    mixedFirstPartyDex,
    new Set([
      exactTrustedDigest,
      applicationDescriptorDigest,
      applicationArrayDescriptorDigest,
      unusedTrustedDigest,
    ]),
    ["Lcom/autoformkit/app/"],
  );
  assert.equal(partitioned.matchedTrustedStringCount, 1,
    "only a present exact full-string digest may count as matched");
  assert.ok(!partitioned.untrustedText.includes(exactTrustedString));
  assert.ok(partitioned.untrustedText.includes(changedTrustedString),
    "changing one byte must leave the logical string strictly scanned");
  assert.ok(partitioned.untrustedText.includes("Lcom/autoformkit/app/ContainsPrivateData;"),
    "application descriptors cannot be hidden by a provenance digest");
  assert.ok(partitioned.untrustedText.includes("[Lcom/autoformkit/app/ContainsPrivateData;"),
    "array-wrapped application descriptors cannot be hidden by a provenance digest");
  assert.ok(partitioned.untrustedText.includes(syntheticToken));
  assert.ok(partitioned.untrustedText.includes(syntheticEmail));
  assert.equal(partitioned.masked.includes(Buffer.from(exactTrustedString)), false);

  const mixedFirstPartyApk = path.join(temporary, "mixed-first-party.apk");
  writeStoredOrDeflatedZip(mixedFirstPartyApk, [{
    name: "classes.dex",
    content: mixedFirstPartyDex,
  }]);
  const mixedFirstPartyFinding = audit([
    "--apk", mixedFirstPartyApk, "--private-wordlist", deploymentTokenWordlist,
  ]);
  assert.equal(mixedFirstPartyFinding.status, 1);
  assert.ok(findingRules(mixedFirstPartyFinding.report).has("private-wordlist-term"));
  assert.ok(findingRules(mixedFirstPartyFinding.report).has("credential-token"));
  assert.ok(findingRules(mixedFirstPartyFinding.report).has("pii-email"));

  const nonStringCredentialDex = Buffer.concat([
    syntheticDex("Lcom/autoformkit/app/CompiledByteArray;"),
    Buffer.from(syntheticToken),
  ]);
  nonStringCredentialDex.writeUInt32LE(nonStringCredentialDex.length, 32);
  const nonStringCredentialApk = path.join(temporary, "non-string-credential.apk");
  writeStoredOrDeflatedZip(nonStringCredentialApk, [{
    name: "classes.dex",
    content: nonStringCredentialDex,
  }]);
  const nonStringCredentialFinding = audit(["--apk", nonStringCredentialApk]);
  assert.equal(nonStringCredentialFinding.status, 1);
  assert.ok(findingRules(nonStringCredentialFinding.report).has("credential-token"),
    "a token compiled into non-string DEX bytes must remain detectable");

  const unknownAssetApk = path.join(temporary, "unknown-asset.apk");
  writeStoredOrDeflatedZip(unknownAssetApk, [
    { name: "assets/form-profiles.seed.json", content: "{}" },
    { name: "assets/internal-settings.json", content: "{}" },
  ]);
  const unknownAsset = audit(["--apk", unknownAssetApk]);
  assert.equal(unknownAsset.status, 1);
  assert.ok(findingRules(unknownAsset.report).has("unknown-first-party-asset"));

  const privateCatalogManifestApk = path.join(temporary, "private-catalog-manifest.apk");
  writeStoredOrDeflatedZip(privateCatalogManifestApk, [
    { name: "assets/form-profiles.seed.json", content: "{}" },
    { name: "assets/manifest.json", content: "{}" },
  ]);
  const privateCatalogManifest = audit(["--apk", privateCatalogManifestApk]);
  assert.equal(privateCatalogManifest.status, 1);
  assert.ok(privateCatalogManifest.report.findings.some((finding) =>
    finding.entry === "assets/manifest.json" && finding.ruleId === "deployment-specific-path"),
  "the packaged private catalog manifest must fail by path regardless of fictional content");

  const endpointApk = path.join(temporary, "endpoint.apk");
  writeStoredOrDeflatedZip(endpointApk, [
    { name: "assets/form-profiles.seed.json", content: "{}" },
    {
      name: "assets/update-config.json",
      content: JSON.stringify({ manifestUrl: syntheticEndpoint }),
    },
  ]);
  const endpointInApk = audit(["--apk", endpointApk]);
  assert.equal(endpointInApk.status, 1);
  assert.ok(findingRules(endpointInApk.report).has("non-public-endpoint-in-example-surface"));
  assert.ok(!endpointInApk.stdout.includes(syntheticEndpoint));

  const unsafePathApk = path.join(temporary, "unsafe-path.apk");
  writeStoredOrDeflatedZip(unsafePathApk, [{ name: "../outside.txt", content: "generic" }]);
  const unsafePath = audit(["--apk", unsafePathApk]);
  assert.equal(unsafePath.status, 1);
  assert.ok(findingRules(unsafePath.report).has("unsafe-apk-entry-path"));

  const tokenApk = path.join(temporary, "token.apk");
  writeStoredOrDeflatedZip(tokenApk, [{ name: "classes.dex", content: syntheticToken }]);
  const tokenInApk = audit(["--apk", tokenApk]);
  assert.equal(tokenInApk.status, 1);
  assert.ok(findingRules(tokenInApk.report).has("credential-token"));
  assert.ok(!tokenInApk.stdout.includes(syntheticToken));

  const metadataTokenApk = path.join(temporary, "metadata-token.apk");
  writeStoredOrDeflatedZip(
    metadataTokenApk,
    [{ name: "AndroidManifest.xml", content: "<manifest/>" }],
    syntheticToken,
  );
  const tokenInMetadata = audit(["--apk", metadataTokenApk]);
  assert.equal(Boolean(tokenInMetadata["status"]), true);
  assert.ok(tokenInMetadata.report.findings.some((finding) =>
    finding.ruleId === "credential-token" && finding.entry === "[apk-container-metadata]"));
  assert.ok(!tokenInMetadata.stdout.includes(syntheticToken));

  const malformed = path.join(temporary, "malformed.apk");
  fs.writeFileSync(malformed, "not a ZIP", "utf8");
  const malformedResult = audit(["--apk", malformed], true);
  assert.equal(malformedResult.status, 2);
  assert.equal(malformedResult.stdout, "");
  assert.equal(malformedResult.stderr, "public surface audit could not complete; no values were emitted\n");

  const changedReadme = "# Generic public framework, changed\n";
  fs.writeFileSync(path.join(repo, "README.md"), changedReadme, "utf8");
  const changedWorktree = audit(["--worktree", "--repo", repo]);
  assert.equal(changedWorktree.status, 0);
  assert.notEqual(changedWorktree.report.input.sha256, safeWorktree.report.input.sha256);

  process.stdout.write("public surface audit self-test: passed\n");
} finally {
  fs.rmSync(temporary, { recursive: true, force: true });
}
