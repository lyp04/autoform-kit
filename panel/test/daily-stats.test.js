import test from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";

import {
  DAILY_STATS_MAX_GROUPS,
  validateDailyStats
} from "../src/daily-stats.js";
import { validateFormProfile } from "../src/profile.js";
import { clientCatalog } from "../src/worker.js";

function visibleProfile(id, resultKeys) {
  return {
    id,
    pickerVisible: true,
    gradeMap: Object.fromEntries(resultKeys.map((key) => [key, {
      field: "sample-result",
      label: `Sample ${key}`,
      value: `SAMPLE_${key}`
    }]))
  };
}

function group(id, label, uiColor, resultKeys, extra = {}) {
  return { id, label, uiColor, resultKeys, ...extra };
}

function validDailyStats() {
  return {
    scope: "all_profiles",
    groups: [
      group("sample-ready-summary", "Sample ready", "#2563EB", ["sample-ready"], {
        labelI18n: { en: "Sample ready", es: "Ejemplo listo" }
      }),
      group("sample-review-summary", "Sample review", "#7C3AED", ["sample-review"])
    ]
  };
}

test("dailyStats accepts ordered fictional groups backed by visible profile result keys", () => {
  const profiles = [
    visibleProfile("sample-one", ["sample-ready"]),
    visibleProfile("sample-two", ["sample-review"])
  ];
  assert.deepEqual(validateDailyStats(validDailyStats(), profiles), []);

  const source = {
    settings: {
      brand: "Sample",
      dailyStats: validDailyStats(),
      workerOnlyFutureSecret: "must-not-leak"
    },
    profiles
  };
  const served = clientCatalog(source);
  assert.deepEqual(served.settings.dailyStats.groups.map((item) => item.id), [
    "sample-ready-summary", "sample-review-summary"
  ]);
  assert.equal("workerOnlyFutureSecret" in served.settings, false);
});

test("public sample catalog dailyStats is fictional and closes over its visible profiles", () => {
  const seed = JSON.parse(readFileSync(
    new URL("../../app/assets/form-profiles.seed.json", import.meta.url), "utf8"));
  assert.deepEqual(validateDailyStats(seed.settings.dailyStats, seed.profiles), []);
  assert.ok(seed.settings.dailyStats.groups.every((item) =>
    item.id.startsWith("sample-") && item.resultKeys.every((key) => key.startsWith("sample-"))));
});

test("dailyStats rejects unknown structure, invalid bounds, duplicate identity and bad colors", () => {
  const profiles = [visibleProfile("sample-one", ["sample-ready", "sample-review"])];
  const tooManyGroups = Array.from({ length: DAILY_STATS_MAX_GROUPS + 1 }, (_, index) =>
    group(`sample-${index}`, `Sample ${index}`, "#2563EB", [
      index === 0 ? "sample-ready" : `missing-${index}`
    ]));
  const errors = validateDailyStats({
    scope: "current_profile",
    groups: [
      group(" sample ", "", "2563EB", [], {
        labelI18n: { fr: "Exemple", en: " Example " },
        unsupported: true
      }),
      group("sample", "x".repeat(161), "#GGGGGG", ["sample-ready"])
    ],
    unsupported: true
  }, profiles);
  assert.ok(errors.includes("dailyStats.unsupported is unsupported"));
  assert.ok(errors.includes("dailyStats.scope must equal all_profiles"));
  assert.ok(errors.includes("dailyStats.groups[0].unsupported is unsupported"));
  assert.ok(errors.includes("dailyStats.groups[0].id must not have surrounding whitespace"));
  assert.ok(errors.includes("dailyStats.groups[0].label must be a non-empty string"));
  assert.ok(errors.includes("dailyStats.groups[0].labelI18n.fr is unsupported"));
  assert.ok(errors.includes("dailyStats.groups[0].labelI18n.en must not have surrounding whitespace"));
  assert.ok(errors.includes("dailyStats.groups[0].uiColor must be a six-digit #RRGGBB color"));
  assert.ok(errors.includes("dailyStats.groups[0].resultKeys must not be empty"));
  assert.ok(errors.includes("dailyStats.groups[1].id must be unique"));
  assert.ok(errors.includes("dailyStats.groups[1].label must contain at most 160 characters"));
  assert.ok(validateDailyStats({ scope: "all_profiles", groups: tooManyGroups }, profiles)
    .includes("dailyStats.groups must contain at most 16 items"));
});

test("dailyStats result keys are exact, unique and declared by pickerVisible profiles", () => {
  const profiles = [
    visibleProfile("sample-visible", ["sample-ready"]),
    { ...visibleProfile("sample-hidden", ["sample-hidden-only"]), pickerVisible: false }
  ];
  const errors = validateDailyStats({
    scope: "all_profiles",
    groups: [
      group("sample-one", "Sample one", "#2563EB",
        ["sample-ready", "sample-ready", " sample-review "]),
      group("sample-two", "Sample two", "#7C3AED",
        ["sample-ready", "sample-hidden-only"])
    ]
  }, profiles);
  assert.ok(errors.includes(
    "dailyStats.groups[0].resultKeys[1] must be unique within its group"));
  assert.ok(errors.includes(
    "dailyStats.groups[0].resultKeys[2] must not have surrounding whitespace"));
  assert.ok(errors.includes(
    "dailyStats.groups[0].resultKeys[2] must be declared by at least one pickerVisible profile gradeMap"));
  assert.ok(errors.includes(
    "dailyStats.groups[1].resultKeys[0] must not appear in more than one group"));
  assert.ok(errors.includes(
    "dailyStats.groups[1].resultKeys[1] must be declared by at least one pickerVisible profile gradeMap"));
});

test("invalid stored dailyStats is omitted from the App client catalog", () => {
  const source = {
    settings: {
      brand: "Sample",
      dailyStats: {
        scope: "all_profiles",
        groups: [group("sample", "Sample", "#2563EB", ["not-declared"])]
      }
    },
    profiles: [visibleProfile("sample-visible", ["sample-ready"])]
  };
  const served = clientCatalog(source);
  assert.equal("dailyStats" in served.settings, false);
  assert.equal(source.settings.dailyStats.groups[0].resultKeys[0], "not-declared");
});

test("Panel structured global editor wires dailyStats groups into the settings save", () => {
  const html = readFileSync(new URL("../public/index.html", import.meta.url), "utf8");
  assert.match(html, /id="dailyStatsGroups"/u);
  assert.match(html, /id="addDailyStatsGroupBtn"/u);
  assert.match(html, /id="saveDailyStatsBtn"/u);
  assert.match(html, /function buildDailyStats\(\)/u);
  assert.match(html, /scope:"all_profiles",groups/u);
  assert.match(html, /body:\{[^}]*dailyStats/u);
});

test("profile uiColor accepts only exact six-digit #RRGGBB values", () => {
  const base = {
    id: "sample-profile",
    displayName: "Sample profile",
    searchText: "sample profile"
  };
  assert.deepEqual(validateFormProfile({ ...base, uiColor: "#2563EB" }), []);
  for (const uiColor of ["2563EB", "#123", "#12345678", " #2563EB", 123456]) {
    assert.ok(validateFormProfile({ ...base, uiColor }).includes(
      "uiColor must be a six-digit #RRGGBB color"));
  }
});
