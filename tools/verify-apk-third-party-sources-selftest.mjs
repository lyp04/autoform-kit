#!/usr/bin/env node

import assert from "node:assert/strict";
import crypto from "node:crypto";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import process from "node:process";
import { spawnSync } from "node:child_process";
import { fileURLToPath } from "node:url";

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");
const verifier = path.join(root, "tools", "verify-apk-third-party-sources.mjs");
const temporary = fs.mkdtempSync(path.join(os.tmpdir(), "apk-source-provenance-selftest-"));

function sha256(value) {
  return crypto.createHash("sha256").update(value).digest("hex");
}

function canonicalize(value) {
  if (Array.isArray(value)) return value.map(canonicalize);
  if (value && typeof value === "object") {
    return Object.fromEntries(Object.keys(value).sort().map(
      (key) => [key, canonicalize(value[key])]));
  }
  return value;
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

function storedZip(entries) {
  const localParts = [];
  const centralParts = [];
  let localOffset = 0;
  for (const entry of entries) {
    const name = Buffer.from(entry.name, "utf8");
    const content = Buffer.isBuffer(entry.content)
      ? entry.content
      : Buffer.from(entry.content, "utf8");
    const checksum = crc32(content);
    const local = Buffer.alloc(30);
    local.writeUInt32LE(0x04034b50, 0);
    local.writeUInt16LE(20, 4);
    local.writeUInt16LE(0x800, 6);
    local.writeUInt32LE(checksum, 14);
    local.writeUInt32LE(content.length, 18);
    local.writeUInt32LE(content.length, 22);
    local.writeUInt16LE(name.length, 26);
    localParts.push(local, name, content);
    const central = Buffer.alloc(46);
    central.writeUInt32LE(0x02014b50, 0);
    central.writeUInt16LE(20, 4);
    central.writeUInt16LE(20, 6);
    central.writeUInt16LE(0x800, 8);
    central.writeUInt32LE(checksum, 16);
    central.writeUInt32LE(content.length, 20);
    central.writeUInt32LE(content.length, 24);
    central.writeUInt16LE(name.length, 28);
    central.writeUInt32LE(localOffset, 42);
    centralParts.push(central, name);
    localOffset += local.length + name.length + content.length;
  }
  const centralDirectory = Buffer.concat(centralParts);
  const end = Buffer.alloc(22);
  end.writeUInt32LE(0x06054b50, 0);
  end.writeUInt16LE(entries.length, 8);
  end.writeUInt16LE(entries.length, 10);
  end.writeUInt32LE(centralDirectory.length, 12);
  end.writeUInt32LE(localOffset, 16);
  return Buffer.concat([...localParts, centralDirectory, end]);
}

function syntheticClass(strings) {
  const constants = strings.map((value) => {
    const bytes = Buffer.from(value, "utf8");
    assert.ok(bytes.length <= 0xffff);
    const header = Buffer.alloc(3);
    header[0] = 1;
    header.writeUInt16BE(bytes.length, 1);
    return Buffer.concat([header, bytes]);
  });
  const header = Buffer.alloc(10);
  header.writeUInt32BE(0xcafebabe, 0);
  header.writeUInt16BE(61, 6);
  header.writeUInt16BE(strings.length + 1, 8);
  return Buffer.concat([header, ...constants]);
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

function execute(apk, policy, buildDir, gradleHome) {
  return spawnSync(process.execPath, [
    verifier,
    "--apk", apk,
    "--policy", policy,
    "--build-dir", buildDir,
    "--gradle-user-home", gradleHome,
  ], {
    cwd: root,
    encoding: "utf8",
    stdio: ["ignore", "pipe", "pipe"],
  });
}

try {
  const gradleHome = path.join(temporary, "gradle");
  const buildDir = path.join(temporary, "build");
  const sourceDirectory = path.join(
    gradleHome,
    "caches/modules-2/files-2.1/androidx.example/example/1.0.0/hash",
  );
  const mergedPath = path.join(
    buildDir,
    "intermediates/merged_art_profile/release/mergeReleaseArtProfile/baseline-prof.txt",
  );
  const compiledProfilePath = path.join(
    buildDir,
    "intermediates/binary_art_profile/release/compileReleaseArtProfile/baseline.prof",
  );
  const compiledMetadataPath = path.join(
    buildDir,
    "intermediates/binary_art_profile_metadata/release/compileReleaseArtProfile/baseline.profm",
  );
  fs.mkdirSync(sourceDirectory, { recursive: true });
  fs.mkdirSync(path.dirname(mergedPath), { recursive: true });
  fs.mkdirSync(path.dirname(compiledProfilePath), { recursive: true });
  fs.mkdirSync(path.dirname(compiledMetadataPath), { recursive: true });

  const sourceText = Buffer.from("Landroidx/example/Example;\n", "utf8");
  const compiledProfile = Buffer.from("synthetic compiled profile", "utf8");
  const compiledMetadata = Buffer.from("synthetic compiled metadata", "utf8");
  const trustedDexString = "reviewed public dependency constant";
  const classEntry = syntheticClass(["Example", trustedDexString]);
  const classesJar = storedZip([{
    name: "androidx/example/Proof.class",
    content: classEntry,
  }]);
  const aar = storedZip([
    { name: "baseline-prof.txt", content: sourceText },
    { name: "classes.jar", content: classesJar },
  ]);
  const aarPath = path.join(sourceDirectory, "example-1.0.0.aar");
  const appDescriptor = "Lcom/autoformkit/app/Fixture;";
  const dex = syntheticDexStrings([appDescriptor, trustedDexString], [0]);
  const apk = storedZip([
    { name: "assets/dexopt/baseline.prof", content: compiledProfile },
    { name: "assets/dexopt/baseline.profm", content: compiledMetadata },
    { name: "classes.dex", content: dex },
  ]);
  const apkPath = path.join(temporary, "fixture.apk");
  const policyPath = path.join(temporary, "policy.json");
  const basePolicy = {
    appNamespaceDescriptorPrefixes: ["Lcom/autoformkit/app/"],
    components: [
      {
        id: "fixture-profile-source",
        sourceArtifactSha256: sha256(sourceText),
        runtimeProfileSources: {
          mergedTextSha256: sha256(sourceText),
          artifacts: [{
            coordinate: "androidx.example:example:1.0.0",
            artifactSha256: sha256(aar),
            entryPath: "baseline-prof.txt",
            entrySha256: sha256(sourceText),
          }],
        },
      },
      {
        id: "fixture-dex-source",
        coordinate: "androidx.example:example:1.0.0",
        sourceArtifactSha256: sha256(aar),
        dexStringSources: [{
          path: "classes.jar!/androidx/example/Proof.class",
          sha256: sha256(classEntry),
          stringSha256: [sha256(Buffer.from(trustedDexString, "utf8"))],
        }],
      },
    ],
    profiles: [{
      id: "fixture-release",
      buildType: "release",
      dexStringComponents: ["fixture-dex-source"],
      entries: [
        {
          path: "assets/dexopt/baseline.prof",
          sha256: sha256(compiledProfile),
          component: "fixture-profile-source",
          kind: "runtime-profile",
        },
        {
          path: "assets/dexopt/baseline.profm",
          sha256: sha256(compiledMetadata),
          component: "fixture-profile-source",
          kind: "runtime-profile",
        },
      ],
    }],
  };
  const writePolicy = (policy = basePolicy) => {
    fs.writeFileSync(policyPath, JSON.stringify(policy), "utf8");
  };
  const writeApk = (dexBytes = dex, profileBytes = compiledProfile) => {
    fs.writeFileSync(apkPath, storedZip([
      { name: "assets/dexopt/baseline.prof", content: profileBytes },
      { name: "assets/dexopt/baseline.profm", content: compiledMetadata },
      { name: "classes.dex", content: dexBytes },
    ]));
  };
  fs.writeFileSync(aarPath, aar);
  fs.writeFileSync(mergedPath, sourceText);
  fs.writeFileSync(compiledProfilePath, compiledProfile);
  fs.writeFileSync(compiledMetadataPath, compiledMetadata);
  fs.writeFileSync(apkPath, apk);
  writePolicy();

  const passed = execute(apkPath, policyPath, buildDir, gradleHome);
  assert.equal(passed.status, 0);
  const report = JSON.parse(passed.stdout);
  assert.equal(report.passed, true);
  const { reportSha256, ...reportBase } = report;
  assert.equal(reportSha256, sha256(JSON.stringify(canonicalize(reportBase))));
  assert.equal(report.sourceArtifactCount, 1);
  assert.equal(report.sourceEntryCount, 1);
  assert.equal(report.compiledOutputCount, 2);
  assert.equal(report.dexSourceArtifactCount, 1);
  assert.equal(report.dexSourceEntryCount, 1);
  assert.equal(report.declaredDexStringCount, 1);
  assert.equal(report.sourceMatchedDexStringCount, 1);
  assert.equal(report.apkMatchedDexStringCount, 1);

  fs.writeFileSync(aarPath, Buffer.concat([aar, Buffer.from("tampered")]));
  assert.notEqual(execute(apkPath, policyPath, buildDir, gradleHome).status, 0,
    "a changed source AAR must fail closed");
  fs.writeFileSync(aarPath, aar);

  const changedClassEntry = syntheticClass(["Example", `${trustedDexString} changed`]);
  const changedAar = storedZip([
    { name: "baseline-prof.txt", content: sourceText },
    {
      name: "classes.jar",
      content: storedZip([{
        name: "androidx/example/Proof.class",
        content: changedClassEntry,
      }]),
    },
  ]);
  const changedClassPolicy = structuredClone(basePolicy);
  changedClassPolicy.components[0].runtimeProfileSources.artifacts[0].artifactSha256 =
    sha256(changedAar);
  changedClassPolicy.components[1].sourceArtifactSha256 = sha256(changedAar);
  fs.writeFileSync(aarPath, changedAar);
  writePolicy(changedClassPolicy);
  assert.notEqual(execute(apkPath, policyPath, buildDir, gradleHome).status, 0,
    "a changed nested class entry must fail closed");
  fs.writeFileSync(aarPath, aar);
  writePolicy();

  const changedStringPolicy = structuredClone(basePolicy);
  changedStringPolicy.components[1].dexStringSources[0].stringSha256 = [
    sha256(Buffer.from("absent public dependency constant", "utf8")),
  ];
  writePolicy(changedStringPolicy);
  assert.notEqual(execute(apkPath, policyPath, buildDir, gradleHome).status, 0,
    "a declared string absent from the source class must fail closed");
  writePolicy();

  fs.writeFileSync(mergedPath, Buffer.from("changed merged profile"));
  assert.notEqual(execute(apkPath, policyPath, buildDir, gradleHome).status, 0,
    "a changed merged source must fail closed");
  fs.writeFileSync(mergedPath, sourceText);

  fs.writeFileSync(compiledProfilePath, Buffer.from("changed compiled profile"));
  assert.notEqual(execute(apkPath, policyPath, buildDir, gradleHome).status, 0,
    "a changed compiled output must fail closed");
  fs.writeFileSync(compiledProfilePath, compiledProfile);

  writeApk(dex, Buffer.from("changed APK entry"));
  assert.notEqual(execute(apkPath, policyPath, buildDir, gradleHome).status, 0,
    "a changed APK runtime-profile entry must fail closed");
  writeApk();

  const changedDex = syntheticDexStrings([
    appDescriptor,
    `${trustedDexString} changed`,
  ], [0]);
  writeApk(changedDex);
  assert.notEqual(execute(apkPath, policyPath, buildDir, gradleHome).status, 0,
    "a changed APK DEX string must fail closed");
  writeApk();

  const duplicatedDex = syntheticDexStrings([
    appDescriptor,
    trustedDexString,
    trustedDexString,
  ], [0]);
  writeApk(duplicatedDex);
  assert.notEqual(execute(apkPath, policyPath, buildDir, gradleHome).status, 0,
    "a duplicated APK DEX string must fail closed");
  writeApk();

  const changedPolicy = structuredClone(basePolicy);
  changedPolicy.profiles[0].dexStringComponents = [];
  writePolicy(changedPolicy);
  assert.notEqual(execute(apkPath, policyPath, buildDir, gradleHome).status, 0,
    "a changed component-selection policy must fail closed");
  writePolicy();

  process.stdout.write("APK third-party source verifier self-test: passed\n");
} finally {
  fs.rmSync(temporary, { recursive: true, force: true });
}
