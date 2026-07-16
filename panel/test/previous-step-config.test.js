import test from "node:test";
import assert from "node:assert/strict";

import { preserveRuntimeProfileConfig, validateFormProfile } from "../src/profile.js";
import { templateToProfile } from "../src/convert.js";

function baseProfile(extra = {}) {
  return { id: "sample-form", displayName: "Sample Form", searchText: "SAMPLE", ...extra };
}

test("explicit previous-step template ids must both be positive integers", () => {
  assert.deepEqual(validateFormProfile(baseProfile({
    previousStepTemplates: { step1TemplateId: 2101, step2TemplateId: 2102 }
  })), []);

  assert.deepEqual(validateFormProfile(baseProfile({
    previousStepTemplates: { step1TemplateId: 2101, step2TemplateId: 0 }
  })), ["previousStepTemplates.step2TemplateId must be a positive integer"]);
});

test("enabled automatic previous-step creation requires the separate exact-id mapping", () => {
  assert.deepEqual(validateFormProfile(baseProfile({
    autoCreatePreviousSteps: { enabled: true, grades: ["A"] }
  })), ["previousStepTemplates must be an object"]);

  assert.deepEqual(validateFormProfile(baseProfile({
    autoCreatePreviousSteps: { enabled: false }
  })), []);

  assert.deepEqual(validateFormProfile(baseProfile({
    previousStepTemplates: { step1TemplateId: 2101, step2TemplateId: 2102 },
    autoCreatePreviousSteps: {
      grades: ["A"]
    }
  })), ["autoCreatePreviousSteps.enabled must be a boolean"]);

  assert.deepEqual(validateFormProfile(baseProfile({
    previousStepTemplates: { step1TemplateId: 2101, step2TemplateId: 2102 },
    autoCreatePreviousSteps: {
      enabled: true, grades: ["A"], step1TemplateId: 2101, step2TemplateId: 2102
    }
  })), [
    "autoCreatePreviousSteps.step1TemplateId belongs in previousStepTemplates",
    "autoCreatePreviousSteps.step2TemplateId belongs in previousStepTemplates"
  ]);

  assert.deepEqual(validateFormProfile(baseProfile({
    previousStepTemplates: { step1TemplateId: 2101, step2TemplateId: 2102 },
    autoCreatePreviousSteps: { enabled: true, grades: [] }
  })), ["autoCreatePreviousSteps.grades must contain at least one grade when enabled"]);

  assert.deepEqual(validateFormProfile(baseProfile({
    previousStepTemplates: { step1TemplateId: 2101, step2TemplateId: 2102 },
    autoCreatePreviousSteps: { enabled: true, grades: ["A"] }
  })), []);
});

test("AI and template conversion preserve runtime-only modules", () => {
  const current = baseProfile({
    previousStepTemplates: { step1TemplateId: 2101, step2TemplateId: 2102 },
    autoCreatePreviousSteps: { enabled: true, grades: ["A"] },
    gradeASpecialHandling: true
  });
  const refined = preserveRuntimeProfileConfig({
    id: "sample-form", displayName: "Sample Form updated", searchText: "SAMPLE",
    previousStepTemplates: { step1TemplateId: 1, step2TemplateId: 2 },
    gradeASpecialHandling: false
  }, current);
  assert.deepEqual(refined.previousStepTemplates, current.previousStepTemplates);
  assert.deepEqual(refined.autoCreatePreviousSteps, current.autoCreatePreviousSteps);
  assert.equal(refined.gradeASpecialHandling, true);

  const noRuntimeConfig = preserveRuntimeProfileConfig({
    id: "plain", displayName: "Plain", searchText: "Plain",
    autoCreatePreviousSteps: { enabled: true, grades: ["A"] }
  }, baseProfile());
  assert.equal("autoCreatePreviousSteps" in noRuntimeConfig, false);

  const converted = templateToProfile({
    id: 2104, name: "Sample Form", process_id: 4, sku: "SAMPLE_SKU", warehouse_id: 1,
    field_list: []
  }, current);
  assert.deepEqual(converted.previousStepTemplates, current.previousStepTemplates);
  assert.deepEqual(converted.autoCreatePreviousSteps, current.autoCreatePreviousSteps);
  assert.equal(converted.gradeASpecialHandling, true);
});
