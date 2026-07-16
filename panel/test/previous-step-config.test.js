import test from "node:test";
import assert from "node:assert/strict";

import { preserveRuntimeProfileConfig, validateFormProfile } from "../src/profile.js";
import { templateToProfile } from "../src/convert.js";

function baseProfile(extra = {}) {
  return { id: "t2277", displayName: "L60", searchText: "T2277", ...extra };
}

test("explicit previous-step template ids must both be positive integers", () => {
  assert.deepEqual(validateFormProfile(baseProfile({
    previousStepTemplates: { step1TemplateId: 32015, step2TemplateId: 32016 }
  })), []);

  assert.deepEqual(validateFormProfile(baseProfile({
    previousStepTemplates: { step1TemplateId: 32015, step2TemplateId: 0 }
  })), ["previousStepTemplates.step2TemplateId must be a positive integer"]);
});

test("enabled automatic previous-step creation cannot publish without exact ids", () => {
  assert.deepEqual(validateFormProfile(baseProfile({
    autoCreatePreviousSteps: { enabled: true, grades: ["A"] }
  })), [
    "autoCreatePreviousSteps.step1TemplateId must be a positive integer",
    "autoCreatePreviousSteps.step2TemplateId must be a positive integer"
  ]);

  assert.deepEqual(validateFormProfile(baseProfile({
    autoCreatePreviousSteps: { enabled: false }
  })), []);

  assert.deepEqual(validateFormProfile(baseProfile({
    previousStepTemplates: { step1TemplateId: 32015, step2TemplateId: 32016 },
    autoCreatePreviousSteps: { enabled: true, grades: ["A"] }
  })), []);
});

test("AI and template conversion preserve runtime-only modules", () => {
  const current = baseProfile({
    previousStepTemplates: { step1TemplateId: 32015, step2TemplateId: 32016 },
    autoCreatePreviousSteps: {
      enabled: true, grades: ["A"], step1TemplateId: 32015, step2TemplateId: 32016
    },
    gradeASpecialHandling: true
  });
  const refined = preserveRuntimeProfileConfig({
    id: "t2277", displayName: "L60 updated", searchText: "T2277",
    previousStepTemplates: { step1TemplateId: 1, step2TemplateId: 2 },
    gradeASpecialHandling: false
  }, current);
  assert.deepEqual(refined.previousStepTemplates, current.previousStepTemplates);
  assert.deepEqual(refined.autoCreatePreviousSteps, current.autoCreatePreviousSteps);
  assert.equal(refined.gradeASpecialHandling, true);

  const noRuntimeConfig = preserveRuntimeProfileConfig({
    id: "plain", displayName: "Plain", searchText: "Plain",
    autoCreatePreviousSteps: { enabled: true, step1TemplateId: 1, step2TemplateId: 2 }
  }, baseProfile());
  assert.equal("autoCreatePreviousSteps" in noRuntimeConfig, false);

  const converted = templateToProfile({
    id: 32017, name: "T2277", process_id: 4, sku: "RV_T2277111", warehouse_id: 6,
    field_list: []
  }, current);
  assert.deepEqual(converted.previousStepTemplates, current.previousStepTemplates);
});
