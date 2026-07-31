import test from "node:test";
import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";

import { validateFormProfile } from "../src/profile.js";
import { templateToProfile } from "../src/convert.js";

function profileWithScanner() {
  return {
    id: "fictional-scanner-form",
    displayName: "Fictional Scanner Form",
    searchText: "FICTIONAL",
    requiresSecondSn: true,
    snFields: { primary: "example_primary", secondary: "example_secondary" },
    snPlugins: [
      {
        key: "primary", field: "example_primary", label: "Primary", scan: true,
        scanner: {
          expectedLength: 10,
          applyExpectedLengthTo: ["ocr", "barcode", "entered"],
          preferredPrefixes: ["ZX"],
          autoTextMode: "always",
          rejectNumericOnly: true,
          candidateMode: "ordered",
          candidateOrder: ["label", "prefix", "general"],
          minLength: 8,
          maxLength: 12,
          requireLetterAndDigit: true,
          rejectedSubstrings: ["IGNORE"],
          stripLabels: ["S/N"],
          labelMatchMode: "compact_optional_slash",
          candidateCharacterMode: "alphanumeric",
          applyCandidateRulesTo: ["ocr"],
          stripLabelsFrom: ["ocr"],
          caseMode: "upper",
          removeWhitespace: true,
          prompt: "Scan fictional identifier"
        }
      },
      {
        key: "secondary", field: "example_secondary", label: "Secondary", scan: true,
        scanner: {
          expectedLength: 8,
          applyExpectedLengthTo: ["ocr", "barcode"],
          autoTextMode: "fallback",
          rejectNumericOnly: false,
          candidateMode: "ranked",
          minLength: 6,
          maxLength: 16,
          requireLetterAndDigit: false,
          rejectedSubstrings: [],
          stripLabels: [],
          labelMatchMode: "literal",
          candidateCharacterMode: "identifier",
          applyCandidateRulesTo: ["ocr", "barcode", "entered"],
          stripLabelsFrom: [],
          caseMode: "upper",
          removeWhitespace: true
        }
      },
      { key: "note", field: "example_note", label: "Note", scan: false }
    ]
  };
}

test("complete fictional primary and secondary scanner policies validate", () => {
  assert.deepEqual(validateFormProfile(profileWithScanner()), []);
});

test("new explicit scan routes fail closed when their scanner policy is missing", () => {
  const profile = profileWithScanner();
  delete profile.snPlugins[0].scanner;
  delete profile.snPlugins[1].scanner;
  assert.deepEqual(validateFormProfile(profile).filter((error) => error.includes("scanner is required")), [
    "snPlugins[0].scanner is required when scan=true",
    "snPlugins[1].scanner is required when scan=true"
  ]);

  // Omitted scan is the documented legacy state: it keeps the pre-policy generic scanner while an
  // existing catalog is being migrated, rather than silently writing guessed production rules.
  profile.snPlugins[0].scan = undefined;
  profile.snPlugins[1].scan = undefined;
  assert.deepEqual(validateFormProfile(profile), []);

  profile.snPlugins[0].scan = true;
  profile.snPlugins[0].scanner = {};
  assert.ok(validateFormProfile(profile).includes(
    "snPlugins[0].scanner is required when scan=true"));
});

test("unsupported extra scanners and ambiguous candidate policies are rejected", () => {
  const profile = profileWithScanner();
  profile.snPlugins[2].scan = true;
  profile.snPlugins[2].scanner = { autoTextMode: "always" };
  profile.snPlugins[0].scanner.candidateOrder = ["label", "label", "unknown"];
  profile.snPlugins[0].scanner.stripLabels = [];
  profile.snPlugins[0].scanner.typoSetting = true;

  const errors = validateFormProfile(profile);
  assert.ok(errors.includes("snPlugins[2].scan=true is supported only for key=primary or key=secondary"));
  assert.ok(errors.includes("snPlugins[2].scanner is supported only for key=primary or key=secondary"));
  assert.ok(errors.includes("snPlugins[0].scanner.candidateOrder[1] must be unique"));
  assert.ok(errors.includes("snPlugins[0].scanner.candidateOrder[2] must be one of: label, prefix, general"));
  assert.ok(errors.includes("snPlugins[0].scanner.stripLabels must be non-empty when candidateOrder includes label"));
  assert.ok(errors.includes("snPlugins[0].scanner.typoSetting is not a supported scanner setting"));
});

test("expected-length source scope is strict and requires an expected length", () => {
  const profile = profileWithScanner();
  profile.snPlugins[0].scanner.applyExpectedLengthTo = [];
  assert.ok(validateFormProfile(profile).includes(
    "snPlugins[0].scanner.applyExpectedLengthTo must not be empty"));

  delete profile.snPlugins[0].scanner.expectedLength;
  profile.snPlugins[0].scanner.applyExpectedLengthTo = ["ocr", "ocr", "camera"];
  const errors = validateFormProfile(profile);
  assert.ok(errors.includes(
    "snPlugins[0].scanner.expectedLength is required when applyExpectedLengthTo is configured"));
  assert.ok(errors.includes(
    "snPlugins[0].scanner.applyExpectedLengthTo[1] must be unique"));
  assert.ok(errors.includes(
    "snPlugins[0].scanner.applyExpectedLengthTo[2] must be one of: ocr, barcode, entered"));
});

test("label source scope requires an explicit non-empty label list", () => {
  const profile = profileWithScanner();
  profile.snPlugins[1].scanner.stripLabelsFrom = ["barcode"];
  assert.ok(validateFormProfile(profile).includes(
    "snPlugins[1].scanner.stripLabels must be non-empty when stripLabelsFrom is non-empty"));
});

test("template conversion never advertises camera scanning for extra plugins", () => {
  const converted = templateToProfile({
    id: 42,
    name: "Fictional Template",
    sku: "EXAMPLE_SKU",
    warehouse_id: 7,
    field_list: [
      { field: "example_primary", title: "Primary", kind: "serial", required: true },
      { field: "example_extra", title: "Extra", kind: "scan", required: false }
    ]
  });
  assert.equal(converted.snPlugins[0].scan, true);
  assert.equal(converted.snPlugins[1].scan, false);
});

test("Panel exposes structured role-scanner controls instead of requiring raw JSON", async () => {
  const html = await readFile(new URL("../public/index.html", import.meta.url), "utf8");
  for (const marker of [
    "自动文字识别", "候选选择", "候选来源顺序", "候选约束应用入口", "精确长度应用入口",
    "标签匹配", "候选字符", "识别并剥离的标签", "剥离标签的入口",
    "请先配置至少一个需识别并剥离的标签",
    "拒绝纯数字", "必须同时含字母和数字", "移除空白"
  ]) {
    assert.ok(html.includes(marker), marker);
  }
});
