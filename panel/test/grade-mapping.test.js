import test from "node:test";
import assert from "node:assert/strict";

import { templateToProfile } from "../src/convert.js";
import { validateFormProfile } from "../src/profile.js";

function templateWithGrades(options) {
  return {
    id: 2352,
    name: "T2352",
    process_id: 4,
    sku: "RV_T2352111",
    warehouse_id: 6,
    field_list: [
      {
        field: "grade-field",
        type: "retread_result",
        option_list: options
      }
    ]
  };
}

function option(name, value) {
  return { name, value, num: 1 };
}

test("converter drops an N-class option instead of shifting it into A", () => {
  const profile = templateToProfile(templateWithGrades([
    option("T2352黑色N类", "RV_T2352111"),
    option("T2352黑色A类-九五成新", "RV_T2352111-F1"),
    option("T2352黑色B类-九成新", "RV_T2352111-F0"),
    option("T2352黑色C类-五成新", "RV_T2352111-F")
  ]));

  assert.equal(profile.gradeMap.A.value.sku, "RV_T2352111-F1");
  assert.equal(profile.gradeMap.B.value.sku, "RV_T2352111-F0");
  assert.equal(profile.gradeMap.C.value.sku, "RV_T2352111-F");
});

test("explicit A/B/C labels win over backend option order", () => {
  const profile = templateToProfile(templateWithGrades([
    option("Class C", "C-SKU"),
    option("Class N", "N-SKU"),
    option("Class A", "A-SKU"),
    option("Class B", "B-SKU")
  ]));

  assert.equal(profile.gradeMap.A.value.sku, "A-SKU");
  assert.equal(profile.gradeMap.B.value.sku, "B-SKU");
  assert.equal(profile.gradeMap.C.value.sku, "C-SKU");
});

test("an N SKU is excluded even when its label does not say N or 全新", () => {
  const profile = templateToProfile(templateWithGrades([
    option("新品档", "RV_T2352111-N"),
    option("A类", "RV_T2352111-F1"),
    option("B类", "RV_T2352111-F0"),
    option("C类", "RV_T2352111-F")
  ]));

  assert.equal(profile.gradeMap.A.value.sku, "RV_T2352111-F1");
});

test("legacy freshness-only labels still use their listed order", () => {
  const profile = templateToProfile(templateWithGrades([
    option("全新机", "N-SKU"),
    option("九五成新", "A-SKU"),
    option("九成新", "B-SKU"),
    option("五成新", "C-SKU")
  ]));

  assert.deepEqual(
    Object.fromEntries(Object.entries(profile.gradeMap).map(([grade, item]) => [grade, item.value.sku])),
    { A: "A-SKU", B: "B-SKU", C: "C-SKU" }
  );
});

test("pure defective templates remain submittable through the A slot", () => {
  const profile = templateToProfile(templateWithGrades([option("不良品", "")]));
  assert.equal(profile.gradeMap.A.value.name, "不良品");
});

test("validator rejects N options and explicit grade/key mismatches", () => {
  const base = {
    id: "bad-map",
    displayName: "Bad map",
    searchText: "Bad map",
    gradeMap: {
      A: {
        field: "grade-field",
        label: "T2352 N类-全新机",
        value: { sku: "N-SKU", name: "T2352 N类-全新机", num: 1 }
      },
      B: {
        field: "grade-field",
        label: "T2352 C类-五成新",
        value: { sku: "C-SKU", name: "T2352 C类-五成新", num: 1 }
      }
    }
  };

  assert.deepEqual(validateFormProfile(base), [
    "gradeMap.A points to an N/brand-new option",
    "gradeMap.B points to an explicit C option"
  ]);
});

test("validator rejects an N SKU even when the label is ambiguous", () => {
  const errors = validateFormProfile({
    id: "n-sku",
    displayName: "N SKU",
    searchText: "N SKU",
    gradeMap: {
      A: { field: "grade", label: "新品档", value: { sku: "RV_T2352111-N", name: "新品档" } }
    }
  });
  assert.deepEqual(errors, ["gradeMap.A points to an N/brand-new option"]);
});

test("validator accepts unlabeled legacy and single-SKU profiles", () => {
  const errors = validateFormProfile({
    id: "legacy",
    displayName: "Legacy",
    searchText: "Legacy",
    gradeMap: {
      A: { field: "grade", label: "Omni C20九五成新", value: { sku: "F1", name: "Omni C20九五成新" } },
      B: { field: "grade", label: "M14 PLUS", value: { sku: "M14", name: "M14 PLUS" } }
    }
  });

  assert.deepEqual(errors, []);
});
