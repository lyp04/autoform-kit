export const GRADES = Object.freeze(["A", "B", "C"]);
export const PHOTO_ORDERS = Object.freeze(["fronts_then_backs", "front_back_per_unit"]);

const BRAND_NEW_GRADE_RE = /全新|(?:^|[^a-z])n\s*[-_ ]?\s*(?:类|級|级|class|grade)|(?:class|grade|类|級|级)\s*[-_ ]?\s*n(?:$|[^a-z])/i;
const BRAND_NEW_SKU_RE = /(?:^|[-_])n$/i;
const EXPLICIT_GRADE_RE = {
  A: /(?:^|[^a-z])a\s*[-_ ]?\s*(?:类|級|级|class|grade)|(?:class|grade|类|級|级)\s*[-_ ]?\s*a(?:$|[^a-z])/i,
  B: /(?:^|[^a-z])b\s*[-_ ]?\s*(?:类|級|级|class|grade)|(?:class|grade|类|級|级)\s*[-_ ]?\s*b(?:$|[^a-z])/i,
  C: /(?:^|[^a-z])c\s*[-_ ]?\s*(?:类|級|级|class|grade)|(?:class|grade|类|級|级)\s*[-_ ]?\s*c(?:$|[^a-z])/i
};

function submittedGradeText(item) {
  return `${item?.label || ""} ${item?.value?.name || ""}`;
}

function explicitSubmittedGrade(item) {
  const text = submittedGradeText(item);
  return GRADES.find((grade) => EXPLICIT_GRADE_RE[grade].test(text)) || "";
}

export function normalizeSn(value) {
  return String(value || "")
    .trim()
    .replace(/\s+/g, "")
    .toUpperCase();
}

export function normalizeGrade(value, fallback = "A") {
  const grade = String(value || "").trim().toUpperCase();
  if (GRADES.includes(grade)) {
    return grade;
  }
  const fallbackGrade = String(fallback || "").trim().toUpperCase();
  return GRADES.includes(fallbackGrade) ? fallbackGrade : "A";
}

export function normalizePhotoOrder(value, fallback = "fronts_then_backs") {
  const order = String(value || "").trim();
  if (PHOTO_ORDERS.includes(order)) {
    return order;
  }
  return PHOTO_ORDERS.includes(fallback) ? fallback : "fronts_then_backs";
}

export function validateFormProfile(profile) {
  const errors = [];
  if (!profile || typeof profile !== "object") {
    return ["profile must be an object"];
  }
  requireString(profile.id, "id", errors);
  requireString(profile.displayName, "displayName", errors);
  requireString(profile.searchText, "searchText", errors);
  if (profile.defaultPhotoOrder) {
    validateOneOf(profile.defaultPhotoOrder, PHOTO_ORDERS, "defaultPhotoOrder", errors);
  }
  validatePreviousStepConfig(profile.previousStepTemplates, "previousStepTemplates", errors, true);
  if (profile.autoCreatePreviousSteps !== undefined) {
    const cfg = profile.autoCreatePreviousSteps;
    if (!isPlainObject(cfg)) {
      errors.push("autoCreatePreviousSteps must be an object");
    } else {
      if (typeof cfg.enabled !== "boolean") errors.push("autoCreatePreviousSteps.enabled must be a boolean");
      if (cfg.grades !== undefined) {
        if (!Array.isArray(cfg.grades)) {
          errors.push("autoCreatePreviousSteps.grades must be an array");
        } else {
          cfg.grades.forEach((grade, i) =>
            validateOneOf(grade, GRADES, `autoCreatePreviousSteps.grades[${i}]`, errors));
        }
      }
      if (cfg.enabled === true && (!Array.isArray(cfg.grades) || cfg.grades.length === 0)) {
        errors.push("autoCreatePreviousSteps.grades must contain at least one grade when enabled");
      }
      for (const key of ["step1TemplateId", "step2TemplateId"]) {
        if (Object.prototype.hasOwnProperty.call(cfg, key)) {
          errors.push(`autoCreatePreviousSteps.${key} belongs in previousStepTemplates`);
        }
      }
      // Template identity and trigger policy are separate responsibilities. Automatic creation
      // requires the mapping-only module; autoCreatePreviousSteps never carries template ids.
      if (cfg.enabled === true && profile.previousStepTemplates === undefined) {
        validatePreviousStepConfig(undefined, "previousStepTemplates", errors, false);
      }
    }
  }
  if (profile.gradeMap) {
    for (const grade of Object.keys(profile.gradeMap)) {
      validateOneOf(grade, GRADES, `gradeMap.${grade}`, errors);
      const item = profile.gradeMap[grade];
      requireString(item?.field, `gradeMap.${grade}.field`, errors);
      if (!("value" in item)) {
        errors.push(`gradeMap.${grade}.value is required`);
      }
      // A/B/C are the only operator-selectable grades. Publishing N/全新 under one of those keys is
      // data loss, not a harmless label issue, so reject it before any device can sync the catalog.
      const text = submittedGradeText(item);
      const sku = String(item?.value?.sku || "").trim();
      if (BRAND_NEW_GRADE_RE.test(text) || BRAND_NEW_SKU_RE.test(sku)) {
        errors.push(`gradeMap.${grade} points to an N/brand-new option`);
      }
      const explicit = explicitSubmittedGrade(item);
      if (explicit && explicit !== grade) {
        errors.push(`gradeMap.${grade} points to an explicit ${explicit} option`);
      }
    }
  }
  // Optional multi-language sibling maps (titleI18n/nameI18n/labelI18n). A MISSING map is always
  // fine (zh stays the source of truth); only a malformed one fails publish. Lenient by design.
  if (profile.photoSlots !== undefined) {
    (Array.isArray(profile.photoSlots) ? profile.photoSlots : []).forEach((s, i) => {
      validateI18n(s, "titleI18n", `photoSlots[${i}].titleI18n`, errors);
    });
  }
  if (profile.optionalSlots !== undefined) {
    (Array.isArray(profile.optionalSlots) ? profile.optionalSlots : []).forEach((s, i) => {
      validateI18n(s, "titleI18n", `optionalSlots[${i}].titleI18n`, errors);
    });
  }
  if (profile.materials !== undefined) {
    (Array.isArray(profile.materials) ? profile.materials : []).forEach((m, i) => {
      validateI18n(m, "nameI18n", `materials[${i}].nameI18n`, errors);
    });
  }
  if (profile.snPlugins !== undefined) {
    (Array.isArray(profile.snPlugins) ? profile.snPlugins : []).forEach((p, i) => {
      validateI18n(p, "labelI18n", `snPlugins[${i}].labelI18n`, errors);
    });
  }
  // choiceFields carry a fixed submitted value; nothing else re-checks them before the app echoes them
  // into the payload, so this is the load-bearing gate: field id present, kind valid, value shape matches
  // kind, every submitted value is a real option, and a required choice actually selected something.
  if (profile.choiceFields !== undefined) {
    if (!Array.isArray(profile.choiceFields)) {
      errors.push("choiceFields must be an array");
    } else {
      profile.choiceFields.forEach((c, i) => {
        const tag = `choiceFields[${i}]`;
        requireString(c?.field, `${tag}.field`, errors);
        const optionValues = new Set((Array.isArray(c?.options) ? c.options : []).map((o) => o && o.value));
        if (c?.kind === "multi") {
          if (!Array.isArray(c?.value)) {
            errors.push(`${tag}.value must be an array for a multi choice`);
          } else {
            for (const v of c.value) {
              if (!optionValues.has(v)) errors.push(`${tag}.value "${v}" is not one of its options`);
            }
            if (c?.required && c.value.length === 0) errors.push(`${tag} is required but nothing is selected`);
          }
        } else if (c?.kind === "single") {
          if (typeof c?.value !== "string") {
            errors.push(`${tag}.value must be a string for a single choice`);
          } else if (c.value === "") {
            if (c?.required) errors.push(`${tag} is required but nothing is selected`);
          } else if (!optionValues.has(c.value)) {
            errors.push(`${tag}.value "${c.value}" is not one of its options`);
          }
        } else {
          errors.push(`${tag}.kind must be "single" or "multi"`);
        }
      });
    }
  }
  return errors;
}

export function validateProfileReadyForSubmit(profile) {
  const errors = validateFormProfile(profile);
  if (!profile?.template?.id) {
    errors.push("template.id is required before submission");
  }
  if (!profile?.template?.warehouseId) {
    errors.push("template.warehouseId is required before submission");
  }
  requireString(profile?.template?.sku, "template.sku", errors);
  requireString(profile?.snFields?.primary || "sn", "snFields.primary", errors);
  if (profile?.requiresSecondSn) {
    requireString(profile?.snFields?.secondary, "snFields.secondary", errors);
  }
  const uploadFields = Array.isArray(profile?.uploadFields) ? profile.uploadFields : [];
  if (!uploadFields.length && !profile?.imageFields?.front) {
    errors.push("uploadFields or imageFields.front is required before submission");
  }
  for (const field of uploadFields) {
    requireString(field?.field, "uploadFields[].field", errors);
    const sources = Array.isArray(field?.sources) ? field.sources : [];
    if (!sources.length) {
      errors.push(`uploadFields.${field?.field || "unknown"}.sources is required`);
    }
  }
  if (!profile?.gradeMap || Object.keys(profile.gradeMap).length === 0) {
    errors.push("gradeMap is required before submission");
  }
  return errors;
}

function requireString(value, path, errors) {
  if (typeof value !== "string" || value.trim() === "") {
    errors.push(`${path} is required`);
  }
}

function isPlainObject(value) {
  return value !== null && typeof value === "object" && !Array.isArray(value);
}

function validatePreviousStepConfig(value, path, errors, optional) {
  if (value === undefined && optional) return;
  if (!isPlainObject(value)) {
    errors.push(`${path} must be an object`);
    return;
  }
  for (const key of ["step1TemplateId", "step2TemplateId"]) {
    if (!Number.isInteger(value[key]) || value[key] <= 0) {
      errors.push(`${path}.${key} must be a positive integer`);
    }
  }
}

/**
 * AI edits operate on the whole profile and can otherwise silently drop, invent, or alter app-only
 * modules that do not correspond to backend form fields. Keep these safety-sensitive modules
 * exactly as they were; the operator changes them through the structured editor or advanced JSON.
 */
export function preserveRuntimeProfileConfig(next, current) {
  if (!isPlainObject(next) || !isPlainObject(current)) return next;
  for (const key of ["previousStepTemplates", "autoCreatePreviousSteps", "gradeASpecialHandling"]) {
    if (Object.prototype.hasOwnProperty.call(current, key)) {
      next[key] = JSON.parse(JSON.stringify(current[key]));
    } else {
      delete next[key];
    }
  }
  return next;
}

// Lenient validator for an optional {en,es} sibling map. undefined/null -> OK (zh-only is valid).
// Otherwise it must be a plain object whose only keys are "en"/"es", each mapping to a string.
function validateI18n(obj, key, path, errors) {
  const val = obj ? obj[key] : undefined;
  if (val === undefined || val === null) return;
  if (typeof val !== "object" || Array.isArray(val)) {
    errors.push(`${path} must be an object with en/es string values`);
    return;
  }
  for (const [k, v] of Object.entries(val)) {
    if (k !== "en" && k !== "es") {
      errors.push(`${path}.${k} is not a supported language (only en/es)`);
    } else if (typeof v !== "string") {
      errors.push(`${path}.${k} must be a string`);
    }
  }
}

function validateOneOf(value, allowed, path, errors) {
  if (!allowed.includes(value)) {
    errors.push(`${path} must be one of: ${allowed.join(", ")}`);
  }
}
