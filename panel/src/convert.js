// Generalized backend-template -> FormProfile converter for the admin panel.
//
// Unlike an older profile-discovery script (which assumes exactly two uploads
// appearance/accessories and always builds an A/B/C gradeMap), this emits:
//   - photoSlots: ONE box per backend upload field (N boxes), each multi-photo capable (schema v2).
//   - grade: only when the template actually has a retread_result field; otherwise graded:false
//     and gradeMap is omitted.
// The produced object is a draft the admin then edits in the panel before publishing.

function optionValues(field) {
  return (field.option_list || []).map((o) => o.value);
}

// 非新机原因 usage-mark reasons ("配件有使用痕迹"/"包材有使用痕迹") — the only ones a high grade (A/near-new)
// should report. Lower grades (B/C) report every reason.
const USAGE_MARK_RE = /使用痕迹/;
// Per-grade 非新机原因 (DATA-DRIVEN, panel-editable): A 类只留「使用痕迹」类原因、B/C 全留。This is the
// modular replacement for the app's old hardcoded A-class filter — future manually-added templates
// get the split automatically, and each grade's list is editable in the panel.
function buildNonNewPerGrade(field) {
  const all = optionValues(field);
  const aClass = all.filter((v) => USAGE_MARK_RE.test(v));
  return { A: aClass.length ? aClass : all, B: all, C: all };
}

const DEFECTIVE_RE = /不良|报废|報廢|次品|残次|defective|scrap|reject/i;
// 全新机 = brand-new tier. Backends are inconsistent here: some call it 全新机, while others only
// say N类 / Class N. It must never occupy the app's A slot — doing so shifts A→B and B→C and makes an
// operator's A selection submit the N option. Keep this deliberately stricter than a bare /N/: model
// names and SKUs routinely contain that letter, so N is a grade only when paired with a grade marker.
const BRAND_NEW_RE = /全新|(?:^|[^a-z])n\s*[-_ ]?\s*(?:类|級|级|class|grade)|(?:class|grade|类|級|级)\s*[-_ ]?\s*n(?:$|[^a-z])/i;
const BRAND_NEW_SKU_RE = /(?:^|[-_])n$/i;
const EXPLICIT_GRADE_RE = {
  A: /(?:^|[^a-z])a\s*[-_ ]?\s*(?:类|級|级|class|grade)|(?:class|grade|类|級|级)\s*[-_ ]?\s*a(?:$|[^a-z])/i,
  B: /(?:^|[^a-z])b\s*[-_ ]?\s*(?:类|級|级|class|grade)|(?:class|grade|类|級|级)\s*[-_ ]?\s*b(?:$|[^a-z])/i,
  C: /(?:^|[^a-z])c\s*[-_ ]?\s*(?:类|級|级|class|grade)|(?:class|grade|类|級|级)\s*[-_ ]?\s*c(?:$|[^a-z])/i
};
function optionText(o) {
  return `${o.name || ""} ${o.en_name || ""}`;
}
function explicitGrade(o) {
  const text = optionText(o);
  return Object.keys(EXPLICIT_GRADE_RE).find((grade) => EXPLICIT_GRADE_RE[grade].test(text)) || "";
}
function isDefectiveText(s) {
  return DEFECTIVE_RE.test(String(s || ""));
}
function isDefectiveOption(o) {
  return DEFECTIVE_RE.test(optionText(o));
}
function isBrandNewOption(o) {
  return BRAND_NEW_RE.test(optionText(o)) || BRAND_NEW_SKU_RE.test(String(o.value || "").trim());
}
// 判定条件:一个模版的翻新结果选项若「全是不良品」(没有任何良品档),它就是【不良品表单】。这类表单的
// 「不良品照片」是它的主图、必须保留;良品表单才要把这张跨分支的「不良品照片」挡在外面。
function isDefectiveOnlyTemplate(retread) {
  const opts = ((retread && retread.option_list) || []).filter((o) => o && o.value != null);
  return opts.length > 0 && opts.every((o) => isDefectiveOption(o));
}

// 良品表单 and 不良品表单 are SEPARATE templates. For a good form, drop both N/全新 and defective
// options. Prefer explicit A类/B类/C类 labels over backend order; use position only for legacy labels
// such as 九五成新/九成新/五成新 that carry no class letter. A pure defective template remains a
// single-A form so it can use the same gradeMap[grade].value submission path.
function buildGradeMap(retread) {
  const opts = (retread.option_list || []).filter((o) => o && o.value != null);
  if (!opts.length) return {};
  const keys = ["A", "B", "C"];
  const defectiveOnly = opts.every((o) => isDefectiveOption(o));
  const chosen = defectiveOnly
    ? opts
    : opts.filter((o) => !isDefectiveOption(o) && !isBrandNewOption(o));
  const map = {};
  const put = (grade, o) => {
    map[grade] = {
      field: retread.field,
      label: o.name || o.en_name || grade,
      value: { sku: o.value, name: o.name || o.en_name || o.value, num: o.num || 1 }
    };
  };

  // Explicit labels win even when the backend reorders its options.
  chosen.forEach((o) => {
    const grade = explicitGrade(o);
    if (grade && !map[grade]) put(grade, o);
  });

  // Positional fallback is only for options with no explicit grade marker. Never recycle a duplicate
  // explicit B option into an empty C slot.
  const unlabeled = chosen.filter((o) => !explicitGrade(o));
  const openKeys = keys.filter((grade) => !map[grade]);
  unlabeled.slice(0, openKeys.length).forEach((o, i) => {
    put(openKeys[i], o);
  });
  return map;
}

function isMaterialField(f) {
  return f.type === "part" || f.parent_type === "part" || f.type_name === "parts";
}

function slug(template) {
  const base = String(template.name || template.sku || template.id || "profile")
    .toLowerCase()
    .replace(/[^a-z0-9]+/g, "-")
    .replace(/(^-|-$)/g, "");
  return base || `template-${template.id}`;
}

export function templateToProfile(template, seed = {}) {
  if (!template || !Array.isArray(template.field_list)) {
    throw new Error("template must include field_list");
  }
  const fields = template.field_list;
  const uploads = fields.filter((f) => f.type === "upload");
  const retread = fields.find((f) => f.type === "retread_result");
  const station = fields.find((f) => /base station/i.test(f.en_title || "") && /sn/i.test(f.en_title || ""));
  const nonNew = fields.find((f) => /non-new|非新机/i.test(`${f.en_title || ""} ${f.title || ""}`));
  const operation = fields.find((f) => /operation items|操作内容/i.test(`${f.en_title || ""} ${f.title || ""}`));
  const parts = fields.filter(isMaterialField);
  const graded = Boolean(retread && Array.isArray(retread.option_list) && retread.option_list.length);

  // A good form's photo slots = its required uploads MINUS any 不良品-titled one (那属于独立的不良品表单).
  const allSlots = uploads
    .filter((u) => u.required)
    .map((u) => ({
      field: u.field,
      title: u.title || u.en_title || u.field,
      minPhotos: 1,
      maxPhotos: u.count || 10,
      required: true,
      conditional: u.visible === false
    }));

  // 不良品照片 归属看表单类型:【不良品表单】里它就是主图、保留;【良品表单】里它属于独立的不良品表单、挡掉。
  // (良品表单绝不内嵌不良品分支;而不良品表单若把主图也丢了,操作员就没地方传报废照片了——这正是之前的漏洞。)
  const photoSlots = isDefectiveOnlyTemplate(retread)
    ? allSlots
    : allSlots.filter((s) => !isDefectiveText(s.title));

  // Optional (non-required) uploads — e.g. 机器电量 — kept as optionalSlots (shown under any result).
  const optionalSlots = uploads
    .filter((u) => !u.required)
    .map((u) => ({ field: u.field, title: u.title || u.en_title || u.field, minPhotos: 0, maxPhotos: u.count || 10, required: false }));

  // Top scan/input fields as PLUGINS, straight from the template — captures 机器SN / 基站SN / 彩盒SN /
  // 装箱号 with their REAL field ids and type-driven icons (parent_type "inputText" marks these). A
  // number field like 装箱号 gets no icons; sn-type gets 搜索🔍+扫码⊡; a scan field gets 扫码⊡ only.
  const allSnFields = fields
    .filter((f) => f.parent_type === "inputText")
    .map((f) => {
      const scanType = f.type === "scan" || f.type_name === "input_scan";
      const snType = f.type === "sn";
      return {
        key: f.field === "sn" ? "primary" : (f.field === "packageSn" ? "package" : (scanType ? "secondary" : (f.type === "number" ? "carton" : f.field))),
        label: f.title || f.en_title || f.field,
        field: f.field,
        required: !!f.required,
        search: snType,
        scan: snType || scanType,
        placeholder: f.placeholder || "请输入"
      };
    });
  // 机器SN(primary) + 基站SN(secondary) are ON by default. 彩盒SN/装箱号/其它 go to snPluginsHidden —
  // present but OFF, so the panel shows a 显示 switch to bring them back if a later requirement needs them.
  const snPlugins = allSnFields.filter((s) => s.key === "primary" || s.key === "secondary");
  const snPluginsHidden = allSnFields.filter((s) => s.key !== "primary" && s.key !== "secondary");

  // Generic choice module: EVERY remaining radio/checkbox the template offers — everything we don't
  // already model as 非新机原因(conditionalFields) / 操作内容(operationFields) / 物料(materialGroups) /
  // 翻新结果(gradeMap, type=retread_result). Without this the app silently DROPS these fields; here each
  // becomes an editable entry with a fixed submitted `value` the panel can change (default: every option
  // for checkbox, first option for radio). `options` carries labels so the panel can render real choices.
  // Exclude every field already modeled elsewhere (incl. retread) so a field is never double-submitted.
  const capturedChoiceIds = new Set([nonNew && nonNew.field, operation && operation.field, retread && retread.field].filter(Boolean));
  const CHOICE_PASS_RE = /通过|passed|\bpass\b|合格|正常/i;
  // 「不通过」含「通过」子串——必须排除否定项，否则会把负向选项当成默认「通过」。
  const CHOICE_FAIL_RE = /不通过|未通过|不合格|不正常|异常|失败|\bfail/i;
  const choiceFields = fields
    .filter((f) => (f.type === "radio" || f.type === "checkbox") && !isMaterialField(f) && !capturedChoiceIds.has(f.field))
    .map((f) => {
      // Keep options+value aligned and NEVER emit a null/empty submit value — mirror the label guard
      // onto the value (drop options with no real value, else `undefined`/`null` leaks into the payload).
      const options = (f.option_list || [])
        .filter((o) => o.value !== undefined && o.value !== null && o.value !== "")
        .map((o) => ({ value: o.value, label: o.name || o.en_name || o.value }));
      const multi = f.type === "checkbox";
      const hidden = f.visible === false;
      // The default is a SAFE NEUTRAL, not a fabricated assertion the admin has to remember to undo:
      //  - multi (状态/原因/判定 类) → [] 选空。「默认全选」只属于 operationFields/materialGroups 那种动作/物料清单。
      //  - 隐藏(条件)字段 → 一律置空：非触发分支不该携值，否则会往缺陷库塞与主判定矛盾的假数据。
      //  - single → 优先「通过/合格/正常」这类中性项，否则退回第一项（恢复 profile-discovery 的启发式）。
      let value;
      if (multi) value = [];
      else if (hidden) value = "";
      else {
        const pick = options.find((o) => {
          const t = `${o.label} ${o.value}`;
          return CHOICE_PASS_RE.test(t) && !CHOICE_FAIL_RE.test(t);
        }) || options[0];
        value = pick ? pick.value : "";
      }
      return {
        field: f.field,
        title: f.title || f.en_title || f.field,
        kind: multi ? "multi" : "single",
        options,
        value,
        required: !!f.required,
        visible: !hidden
      };
    });

  const profile = {
    id: seed.id || slug(template),
    brand: seed.brand || "",
    model: seed.model || template.name || "",
    color: seed.color || "",
    displayName: seed.displayName || template.name || String(template.id),
    searchText: seed.searchText || template.name || "",
    requiresSecondSn: station ? true : Boolean(seed.requiresSecondSn),
    graded,
    discovery: { status: "api_confirmed", source: "/retread/templateDetail", templateId: template.id },
    template: { id: template.id, step: template.process_id, sku: template.sku, warehouseId: template.warehouse_id },
    snFields: {
      primary: "sn",
      secondary: station ? station.field : seed.snFields?.secondary || "",
      package: "packageSn"
    },
    snPlugins,
    snPluginsHidden,
    photoSlots,
    optionalSlots,
    conditionalFields: nonNew
      ? [
          {
            field: nonNew.field,
            title: nonNew.title || nonNew.en_title,
            type: "checkbox",
            value: optionValues(nonNew),
            // Per-grade submitted reasons: A 类只报「使用痕迹」类、B/C 全报。app 按 unit.grade 读 perGrade。
            perGrade: buildNonNewPerGrade(nonNew),
            // nonNew is visible+required in the template (always shown, any result) → applies to all.
            appliesToGrades: []
          }
        ]
      : [],
    operationFields: operation
      ? [{ field: operation.field, title: operation.title || operation.en_title, type: "checkbox", value: optionValues(operation) }]
      : [],
    choiceFields,
    materialGroups: parts.map((p) => ({
      field: p.field,
      title: p.title || p.en_title,
      selectAll: true,
      materials: (p.option_list || []).map((o) => ({
        code: o.value,
        name: o.name || o.en_name || o.value,
        aliases: [o.en_name].filter(Boolean),
        defaultQty: 1
      }))
    })),
    uiColor: seed.uiColor || "#0F766E"
  };
  if (graded) profile.gradeMap = buildGradeMap(retread);
  return profile;
}
