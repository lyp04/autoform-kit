#!/usr/bin/env node

/**
 * Fail-closed source verification for generated third-party APK entries.
 *
 * This verifier deliberately emits counts and public hashes only. It resolves
 * the exact public AARs named by apk-third-party-components.json from the local
 * Gradle module cache, verifies runtime-profile inputs and declared class-file
 * constants, then binds AGP outputs and the selected DEX strings to the APK.
 */

import crypto from "node:crypto";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import process from "node:process";
import { fileURLToPath } from "node:url";
import { dexStringRecords, parseZipEntries } from "./public-surface-audit.mjs";

const here = path.dirname(fileURLToPath(import.meta.url));
const root = path.resolve(here, "..");

function sha256(value) {
  return crypto.createHash("sha256").update(value).digest("hex");
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

function regularFile(filename) {
  const stat = fs.lstatSync(filename);
  if (!stat.isFile() || stat.isSymbolicLink()) throw new Error("not a regular file");
  return fs.readFileSync(filename);
}

function parseArgs(argv) {
  const options = {
    apk: null,
    policy: path.join(here, "apk-third-party-components.json"),
    buildDir: path.join(root, "app", "build"),
    gradleUserHome: process.env.GRADLE_USER_HOME
      ? path.resolve(process.env.GRADLE_USER_HOME)
      : path.join(os.homedir(), ".gradle"),
  };
  for (let index = 0; index < argv.length; index += 1) {
    const argument = argv[index];
    const value = argv[index + 1];
    if (!["--apk", "--policy", "--build-dir", "--gradle-user-home"].includes(argument)
        || value === undefined || value.startsWith("--")) {
      throw new Error("invalid arguments");
    }
    if (argument === "--apk") options.apk = path.resolve(value);
    else if (argument === "--policy") options.policy = path.resolve(value);
    else if (argument === "--build-dir") options.buildDir = path.resolve(value);
    else options.gradleUserHome = path.resolve(value);
    index += 1;
  }
  if (options.apk === null) throw new Error("--apk is required");
  return options;
}

function zipEntry(bytes, entryPath) {
  const matches = parseZipEntries(bytes).records.filter((record) =>
    !record.directory && record.name === entryPath);
  if (matches.length !== 1) throw new Error("ZIP entry did not resolve exactly once");
  return matches[0].content;
}

function walkFiles(directory) {
  const output = [];
  for (const entry of fs.readdirSync(directory, { withFileTypes: true })) {
    const filename = path.join(directory, entry.name);
    if (entry.isSymbolicLink()) continue;
    if (entry.isDirectory()) output.push(...walkFiles(filename));
    else if (entry.isFile()) output.push(filename);
  }
  return output;
}

function coordinateDirectory(gradleUserHome, coordinate) {
  const parts = coordinate.split(":");
  if (parts.length !== 3 || parts.some((part) =>
    !/^[A-Za-z0-9][A-Za-z0-9._-]*$/.test(part))) {
    throw new Error("invalid runtime-profile source coordinate");
  }
  return path.join(
    gradleUserHome,
    "caches",
    "modules-2",
    "files-2.1",
    parts[0],
    parts[1],
    parts[2],
  );
}

function exactSourceAar(gradleUserHome, source) {
  const directory = coordinateDirectory(gradleUserHome, source.coordinate);
  const matches = walkFiles(directory).filter((filename) =>
    filename.endsWith(".aar") && sha256(regularFile(filename)) === source.artifactSha256);
  if (matches.length !== 1) throw new Error("exact source AAR was absent or ambiguous");
  return regularFile(matches[0]);
}

function requireRange(buffer, offset, length) {
  if (!Number.isSafeInteger(offset) || !Number.isSafeInteger(length)
      || offset < 0 || length < 0 || offset + length > buffer.length) {
    throw new Error("structured source entry is out of range");
  }
}

function decodeModifiedUtf8(buffer) {
  const codeUnits = [];
  let cursor = 0;
  while (cursor < buffer.length) {
    const first = buffer[cursor++];
    if (first >= 0x01 && first <= 0x7f) {
      codeUnits.push(first);
      continue;
    }
    if (first >= 0xc0 && first <= 0xdf) {
      if (cursor >= buffer.length) throw new Error("truncated class MUTF-8 string");
      const second = buffer[cursor++];
      if ((second & 0xc0) !== 0x80) throw new Error("invalid class MUTF-8 continuation");
      const value = ((first & 0x1f) << 6) | (second & 0x3f);
      if (value !== 0 && value < 0x80) throw new Error("overlong class MUTF-8 sequence");
      codeUnits.push(value);
      continue;
    }
    if (first >= 0xe0 && first <= 0xef) {
      if (cursor + 1 >= buffer.length) throw new Error("truncated class MUTF-8 string");
      const second = buffer[cursor++];
      const third = buffer[cursor++];
      if ((second & 0xc0) !== 0x80 || (third & 0xc0) !== 0x80) {
        throw new Error("invalid class MUTF-8 continuation");
      }
      const value = ((first & 0x0f) << 12) | ((second & 0x3f) << 6) | (third & 0x3f);
      if (value < 0x800) throw new Error("overlong class MUTF-8 sequence");
      codeUnits.push(value);
      continue;
    }
    throw new Error("invalid class MUTF-8 leading byte");
  }
  let text = "";
  for (let offset = 0; offset < codeUnits.length; offset += 8192) {
    text += String.fromCharCode(...codeUnits.slice(offset, offset + 8192));
  }
  return text;
}

function classUtf8Strings(buffer) {
  requireRange(buffer, 0, 10);
  if (buffer.readUInt32BE(0) !== 0xcafebabe) throw new Error("invalid class file magic");
  const constantPoolCount = buffer.readUInt16BE(8);
  if (constantPoolCount < 1) throw new Error("invalid class constant pool");
  const strings = [];
  let cursor = 10;
  for (let index = 1; index < constantPoolCount; index += 1) {
    requireRange(buffer, cursor, 1);
    const tag = buffer[cursor++];
    if (tag === 1) {
      requireRange(buffer, cursor, 2);
      const length = buffer.readUInt16BE(cursor);
      cursor += 2;
      requireRange(buffer, cursor, length);
      strings.push(decodeModifiedUtf8(buffer.subarray(cursor, cursor + length)));
      cursor += length;
    } else if (tag === 3 || tag === 4) {
      requireRange(buffer, cursor, 4);
      cursor += 4;
    } else if (tag === 5 || tag === 6) {
      requireRange(buffer, cursor, 8);
      cursor += 8;
      index += 1;
      if (index >= constantPoolCount) throw new Error("invalid wide class constant");
    } else if ([7, 8, 16, 19, 20].includes(tag)) {
      requireRange(buffer, cursor, 2);
      cursor += 2;
    } else if ([9, 10, 11, 12, 17, 18].includes(tag)) {
      requireRange(buffer, cursor, 4);
      cursor += 4;
    } else if (tag === 15) {
      requireRange(buffer, cursor, 3);
      cursor += 3;
    } else {
      throw new Error("unsupported class constant tag");
    }
  }
  return strings;
}

function nestedArchiveEntry(archive, sourcePath) {
  const parts = sourcePath.split("!/");
  if (parts.length < 2 || parts.some((part) => part.length === 0)) {
    throw new Error("invalid nested source path");
  }
  let bytes = archive;
  for (const part of parts) bytes = zipEntry(bytes, part);
  return bytes;
}

function isApplicationDescriptor(value, prefixes) {
  const descriptor = value.replace(/^\[+/, "");
  return prefixes.some((prefix) => descriptor.startsWith(prefix));
}

function titleCase(value) {
  if (!/^[a-z][A-Za-z0-9]*$/.test(value)) throw new Error("invalid build type");
  return `${value[0].toUpperCase()}${value.slice(1)}`;
}

function compiledProfilePath(buildDir, buildType, basename) {
  const task = `compile${titleCase(buildType)}ArtProfile`;
  if (basename === "baseline.prof") {
    return path.join(buildDir, "intermediates", "binary_art_profile", buildType, task, basename);
  }
  if (basename === "baseline.profm") {
    return path.join(
      buildDir,
      "intermediates",
      "binary_art_profile_metadata",
      buildType,
      task,
      basename,
    );
  }
  throw new Error("unsupported runtime-profile output");
}

function verify(options) {
  const policyBytes = regularFile(options.policy);
  const policy = JSON.parse(policyBytes.toString("utf8"));
  const apkBytes = regularFile(options.apk);
  const apkRecords = new Map(parseZipEntries(apkBytes).records
    .filter((record) => !record.directory)
    .map((record) => [record.name, record.content]));
  const components = new Map(policy.components.map((component) => [component.id, component]));
  const sourceComponents = policy.components.filter(
    (component) => component.runtimeProfileSources !== undefined);
  if (sourceComponents.length === 0) throw new Error("no runtime-profile source policy");

  let sourceArtifactCount = 0;
  let sourceEntryCount = 0;
  for (const component of sourceComponents) {
    const sourcePolicy = component.runtimeProfileSources;
    if (sourcePolicy.mergedTextSha256 !== component.sourceArtifactSha256) {
      throw new Error("runtime-profile merged source binding disagreed");
    }
    for (const source of sourcePolicy.artifacts) {
      const aar = exactSourceAar(options.gradleUserHome, source);
      const embedded = zipEntry(aar, source.entryPath);
      if (sha256(embedded) !== source.entrySha256) {
        throw new Error("runtime-profile source entry hash disagreed");
      }
      sourceArtifactCount += 1;
      sourceEntryCount += 1;
    }
  }

  const matchingProfiles = policy.profiles.filter((profile) => {
    const runtimeEntries = profile.entries.filter((entry) => entry.kind === "runtime-profile");
    return runtimeEntries.length > 0 && runtimeEntries.every((entry) =>
      apkRecords.has(entry.path) && sha256(apkRecords.get(entry.path)) === entry.sha256);
  });
  if (matchingProfiles.length !== 1) {
    throw new Error("runtime-profile output profile was absent or ambiguous");
  }
  const profile = matchingProfiles[0];
  const runtimeEntries = profile.entries.filter((entry) => entry.kind === "runtime-profile");
  const usedComponents = new Set(runtimeEntries.map((entry) => entry.component));
  if ([...usedComponents].some((id) =>
    components.get(id)?.runtimeProfileSources === undefined)) {
    throw new Error("runtime-profile output lacked source provenance");
  }

  const mergeTask = `merge${titleCase(profile.buildType)}ArtProfile`;
  const merged = regularFile(path.join(
    options.buildDir,
    "intermediates",
    "merged_art_profile",
    profile.buildType,
    mergeTask,
    "baseline-prof.txt",
  ));
  const expectedMergedHashes = new Set([...usedComponents].map(
    (id) => components.get(id).runtimeProfileSources.mergedTextSha256));
  if (expectedMergedHashes.size !== 1 || !expectedMergedHashes.has(sha256(merged))) {
    throw new Error("merged runtime-profile source hash disagreed");
  }

  for (const entry of runtimeEntries) {
    const compiled = regularFile(compiledProfilePath(
      options.buildDir,
      profile.buildType,
      path.posix.basename(entry.path),
    ));
    if (sha256(compiled) !== entry.sha256 || !compiled.equals(apkRecords.get(entry.path))) {
      throw new Error("compiled runtime-profile output disagreed with APK");
    }
  }

  const selectedDexComponentIds = new Set(profile.dexStringComponents ?? []);
  const dexSourceComponents = policy.components.filter(
    (component) => component.dexStringSources !== undefined);
  if (selectedDexComponentIds.size === 0
      || dexSourceComponents.length !== selectedDexComponentIds.size
      || dexSourceComponents.some((component) => !selectedDexComponentIds.has(component.id))) {
    throw new Error("DEX source components were not selected exactly by the profile");
  }
  const declaredDexStringSha256 = new Set();
  let dexSourceArtifactCount = 0;
  let dexSourceEntryCount = 0;
  let sourceMatchedDexStringCount = 0;
  for (const component of dexSourceComponents) {
    const aar = exactSourceAar(options.gradleUserHome, {
      coordinate: component.coordinate,
      artifactSha256: component.sourceArtifactSha256,
    });
    dexSourceArtifactCount += 1;
    for (const source of component.dexStringSources) {
      const classEntry = nestedArchiveEntry(aar, source.path);
      if (sha256(classEntry) !== source.sha256) {
        throw new Error("DEX source class entry hash disagreed");
      }
      const classStringSha256 = new Set(classUtf8Strings(classEntry).map(
        (value) => sha256(Buffer.from(value, "utf8"))));
      for (const digest of source.stringSha256) {
        if (!/^[0-9a-f]{64}$/.test(digest)
            || declaredDexStringSha256.has(digest)
            || !classStringSha256.has(digest)) {
          throw new Error("declared DEX string was absent or duplicated in public class source");
        }
        declaredDexStringSha256.add(digest);
        sourceMatchedDexStringCount += 1;
      }
      dexSourceEntryCount += 1;
    }
  }

  const apkDexRecords = [...apkRecords.entries()]
    .filter(([entryPath]) => /^classes(?:\d+)?\.dex$/.test(entryPath))
    .flatMap(([, bytes]) => dexStringRecords(bytes));
  const appDescriptorPrefixes = policy.appNamespaceDescriptorPrefixes ?? [];
  let apkMatchedDexStringCount = 0;
  for (const digest of declaredDexStringSha256) {
    const matches = apkDexRecords.filter((record) => record.sha256 === digest);
    if (matches.length !== 1 || matches.some((record) =>
      isApplicationDescriptor(record.text, appDescriptorPrefixes))) {
      throw new Error("declared DEX string was absent, duplicated, or entered application namespace");
    }
    apkMatchedDexStringCount += 1;
  }

  const base = {
    schemaVersion: 1,
    passed: true,
    verifierSha256: sha256(regularFile(fileURLToPath(import.meta.url))),
    policySha256: sha256(policyBytes),
    apkSha256: sha256(apkBytes),
    profileId: profile.id,
    sourceArtifactCount,
    sourceEntryCount,
    mergedSourceCount: expectedMergedHashes.size,
    compiledOutputCount: runtimeEntries.length,
    dexSourceArtifactCount,
    dexSourceEntryCount,
    declaredDexStringCount: declaredDexStringSha256.size,
    sourceMatchedDexStringCount,
    apkMatchedDexStringCount,
  };
  return { ...base, reportSha256: sha256(canonicalJson(base)) };
}

try {
  const result = verify(parseArgs(process.argv.slice(2)));
  process.stdout.write(`${JSON.stringify(result)}\n`);
} catch {
  process.stderr.write("APK third-party source verification could not complete; no values were emitted\n");
  process.exitCode = 1;
}
