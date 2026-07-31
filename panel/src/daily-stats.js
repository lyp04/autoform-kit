const DAILY_STATS_KEYS = new Set(["scope", "groups"]);
const DAILY_STATS_GROUP_KEYS = new Set([
  "id", "label", "labelI18n", "uiColor", "resultKeys"
]);

export const DAILY_STATS_MAX_GROUPS = 16;
export const DAILY_STATS_MAX_RESULT_KEYS = 128;
export const DAILY_STATS_MAX_ID_LENGTH = 128;
export const DAILY_STATS_MAX_LABEL_LENGTH = 160;
export const DAILY_STATS_MAX_RESULT_KEY_LENGTH = 256;

function isPlainObject(value) {
  return value !== null && typeof value === "object" && !Array.isArray(value);
}

function allowOnly(value, allowed, path, errors) {
  if (!isPlainObject(value)) return;
  for (const key of Object.keys(value)) {
    if (!allowed.has(key)) errors.push(`${path}.${key} is unsupported`);
  }
}

function validateBoundedText(value, path, maxLength, errors) {
  if (typeof value !== "string" || !value.trim()) {
    errors.push(`${path} must be a non-empty string`);
    return "";
  }
  if (value !== value.trim()) errors.push(`${path} must not have surrounding whitespace`);
  if (value.length > maxLength) {
    errors.push(`${path} must contain at most ${maxLength} characters`);
  }
  return value.trim();
}

function validateLabelI18n(value, path, errors) {
  if (value === undefined) return;
  if (!isPlainObject(value)) {
    errors.push(`${path} must be an object`);
    return;
  }
  const allowed = new Set(["en", "es"]);
  allowOnly(value, allowed, path, errors);
  for (const locale of ["en", "es"]) {
    if (Object.prototype.hasOwnProperty.call(value, locale)) {
      validateBoundedText(value[locale], `${path}.${locale}`,
        DAILY_STATS_MAX_LABEL_LENGTH, errors);
    }
  }
}

function visibleResultKeys(profiles) {
  const declared = new Set();
  for (const profile of Array.isArray(profiles) ? profiles : []) {
    if (profile?.pickerVisible !== true || !isPlainObject(profile?.gradeMap)) continue;
    for (const key of Object.keys(profile.gradeMap)) declared.add(key);
  }
  return declared;
}

/**
 * Validates the optional App-facing catalog-wide result summary.
 *
 * Result keys stay opaque: a group includes only the exact keys explicitly selected by the Panel,
 * and every selected key must exist on at least one explicitly picker-visible profile. This keeps
 * the App from inferring a cross-profile business meaning from labels, values, or profile order.
 */
export function validateDailyStats(value, profiles) {
  if (value === undefined) return [];
  const errors = [];
  const root = "dailyStats";
  if (!isPlainObject(value)) return [`${root} must be an object`];
  allowOnly(value, DAILY_STATS_KEYS, root, errors);
  if (value.scope !== "all_profiles") {
    errors.push(`${root}.scope must equal all_profiles`);
  }
  if (!Array.isArray(value.groups)) {
    errors.push(`${root}.groups must be an array`);
    return errors;
  }
  if (value.groups.length === 0) errors.push(`${root}.groups must not be empty`);
  if (value.groups.length > DAILY_STATS_MAX_GROUPS) {
    errors.push(`${root}.groups must contain at most ${DAILY_STATS_MAX_GROUPS} items`);
  }

  const declared = visibleResultKeys(profiles);
  const ids = new Set();
  const assignedResultKeys = new Map();
  value.groups.forEach((group, groupIndex) => {
    const path = `${root}.groups[${groupIndex}]`;
    if (!isPlainObject(group)) {
      errors.push(`${path} must be an object`);
      return;
    }
    allowOnly(group, DAILY_STATS_GROUP_KEYS, path, errors);
    const id = validateBoundedText(group.id, `${path}.id`,
      DAILY_STATS_MAX_ID_LENGTH, errors);
    if (id) {
      if (ids.has(id)) errors.push(`${path}.id must be unique`);
      ids.add(id);
    }
    validateBoundedText(group.label, `${path}.label`,
      DAILY_STATS_MAX_LABEL_LENGTH, errors);
    validateLabelI18n(group.labelI18n, `${path}.labelI18n`, errors);
    if (typeof group.uiColor !== "string"
        || !/^#[0-9A-Fa-f]{6}$/.test(group.uiColor)) {
      errors.push(`${path}.uiColor must be a six-digit #RRGGBB color`);
    }
    if (!Array.isArray(group.resultKeys)) {
      errors.push(`${path}.resultKeys must be an array`);
      return;
    }
    if (group.resultKeys.length === 0) {
      errors.push(`${path}.resultKeys must not be empty`);
    }
    if (group.resultKeys.length > DAILY_STATS_MAX_RESULT_KEYS) {
      errors.push(`${path}.resultKeys must contain at most ${DAILY_STATS_MAX_RESULT_KEYS} items`);
    }
    const groupResultKeys = new Set();
    group.resultKeys.forEach((rawKey, keyIndex) => {
      const keyPath = `${path}.resultKeys[${keyIndex}]`;
      const key = validateBoundedText(rawKey, keyPath,
        DAILY_STATS_MAX_RESULT_KEY_LENGTH, errors);
      if (!key) return;
      if (groupResultKeys.has(key)) errors.push(`${keyPath} must be unique within its group`);
      groupResultKeys.add(key);
      if (assignedResultKeys.has(key) && assignedResultKeys.get(key) !== groupIndex) {
        errors.push(`${keyPath} must not appear in more than one group`);
      }
      if (!assignedResultKeys.has(key)) assignedResultKeys.set(key, groupIndex);
      if (!declared.has(key)) {
        errors.push(`${keyPath} must be declared by at least one pickerVisible profile gradeMap`);
      }
    });
  });
  return errors;
}
