// form-preview.js — schema-driven, interactive mockup of the entry form the app renders.
//
// One pure entry point: renderFormPreview(mountEl, profile). It reads a FormProfile (the same object
// the panel edits / publishes) and paints a phone-frame mockup that looks like what the app shows the
// operator — so an admin can SEE what a form will look like before publishing, without guessing from
// JSON. It is intentionally GENERIC (works for any product type):
//   - SN fields are modeled as PLUGINS (snPlugins) — each is an independent scan field with its own
//     label / required flag / search icon / scan icon. Synthesized from snFields+requiresSecondSn when
//     a profile doesn't declare them, so old profiles render too. Forms without a base-station / color
//     box simply don't get those plugins.
//   - 翻新结果 (result) is a radio built from gradeMap PLUS an optional 不良品 (defective) branch.
//     ABC grades and 不良品 are MUTUALLY EXCLUSIVE and drive DIFFERENT photo upload sets:
//       • pick an A/B/C grade  -> the standard photoSlots (外观图片 / 配件放置图片 …)
//       • pick 不良品          -> the defective photoSlots (不良品照片)
//   - Everything else (配件多选 / 非新机原因 / 操作内容 / 物料 / 上传按钮 / 电量) is driven by the
//     existing schema arrays, so the mockup stays faithful across product lines.
//
// No dependencies, no build step. Styles are injected once under a `.fp-*` namespace so they never
// collide with the panel's own CSS.

const FP_STYLE_ID = "fp-style";

function injectStyles() {
  if (document.getElementById(FP_STYLE_ID)) return;
  const css = `
  .fp-phone{ --fp-blue:#1989fa; --fp-ink:#1a1a1a; --fp-muted:#969799; --fp-line:#ebedf0; --fp-red:#ee0a24;
    width:340px; margin:0 auto; background:#fff; border:1px solid #e6e8eb; border-radius:22px; overflow:hidden;
    box-shadow:0 10px 34px rgba(15,23,42,.16); font:14px/1.5 -apple-system,system-ui,"PingFang SC","Microsoft YaHei",sans-serif; color:var(--fp-ink); }
  .fp-status{ height:30px; display:flex; align-items:center; justify-content:space-between; padding:0 16px; font-size:12.5px; font-weight:600; color:#0b0b0b; }
  .fp-status .fp-dots{ display:flex; gap:5px; align-items:center; opacity:.85; }
  .fp-nav{ height:44px; display:flex; align-items:center; padding:0 8px; border-bottom:1px solid var(--fp-line); position:relative; }
  .fp-nav .fp-back{ font-size:24px; line-height:1; color:#222; width:40px; text-align:center; }
  .fp-nav .fp-title{ position:absolute; left:0; right:0; text-align:center; font-size:17px; font-weight:600; pointer-events:none; }
  .fp-body{ max-height:560px; overflow-y:auto; padding:6px 16px 12px; -webkit-overflow-scrolling:touch; }
  .fp-formtitle{ font-size:16px; font-weight:700; margin:12px 2px 4px; line-height:1.4; }
  .fp-field{ padding:12px 2px; border-bottom:1px solid var(--fp-line); }
  .fp-lab{ font-size:15px; font-weight:600; margin-bottom:9px; display:flex; align-items:center; gap:2px; flex-wrap:wrap; }
  .fp-lab .fp-star{ color:var(--fp-red); font-weight:700; margin-right:1px; }
  .fp-lab .fp-multi{ color:var(--fp-muted); font-weight:500; font-size:13px; }
  .fp-lab .fp-reset{ margin-left:auto; color:var(--fp-blue); font-weight:500; font-size:14px; cursor:pointer; }
  .fp-snrow{ display:flex; align-items:center; gap:10px; }
  .fp-snrow input{ flex:1; border:0; outline:0; font-size:15px; padding:4px 0; background:transparent; color:var(--fp-ink); }
  .fp-snrow input::placeholder{ color:#c8c9cc; }
  .fp-icon{ width:26px; height:26px; flex:0 0 auto; color:var(--fp-blue); cursor:pointer; }
  .fp-search{ display:flex; align-items:center; gap:8px; background:#f7f8fa; border-radius:8px; padding:8px 12px; margin-bottom:12px; }
  .fp-search svg{ width:16px; height:16px; color:var(--fp-muted); flex:0 0 auto; }
  .fp-search input{ border:0; background:transparent; outline:0; flex:1; font-size:14px; color:var(--fp-ink); }
  .fp-search input::placeholder{ color:#c8c9cc; }
  .fp-selrow{ display:flex; align-items:center; margin:4px 0 6px; }
  .fp-selrow .fp-selnote{ margin-left:auto; display:flex; align-items:center; gap:8px; color:var(--fp-muted); font-size:14px; }
  .fp-opt{ display:flex; align-items:center; gap:12px; padding:11px 0; cursor:pointer; }
  .fp-opt .fp-txt{ flex:1; font-size:15px; }
  .fp-qty{ display:flex; align-items:center; gap:12px; flex:0 0 auto; }
  .fp-qty .fp-qn{ color:var(--fp-blue); font-weight:600; font-size:14px; }
  .fp-qty .fp-icon{ width:23px; height:23px; }
  .fp-box{ width:21px; height:21px; flex:0 0 auto; border:1.5px solid #c8c9cc; border-radius:5px; position:relative; transition:.12s; }
  .fp-opt.on .fp-box{ background:var(--fp-blue); border-color:var(--fp-blue); }
  .fp-opt.on .fp-box:after{ content:""; position:absolute; left:6.5px; top:3px; width:5px; height:9px; border:solid #fff; border-width:0 2px 2px 0; transform:rotate(45deg); }
  .fp-radio{ width:22px; height:22px; flex:0 0 auto; border:1.5px solid #c8c9cc; border-radius:50%; position:relative; transition:.12s; }
  .fp-opt.on .fp-radio{ border-color:var(--fp-blue); }
  .fp-opt.on .fp-radio:after{ content:""; position:absolute; inset:4px; border-radius:50%; background:var(--fp-blue); }
  .fp-switch{ width:44px; height:25px; border-radius:999px; background:#e4e7ed; position:relative; flex:0 0 auto; cursor:pointer; transition:.15s; }
  .fp-switch.on{ background:var(--fp-blue); }
  .fp-switch:before{ content:""; position:absolute; width:21px; height:21px; border-radius:50%; background:#fff; top:2px; left:2px; transition:.15s; box-shadow:0 1px 3px rgba(0,0,0,.2); }
  .fp-switch.on:before{ transform:translateX(19px); }
  .fp-uploads .fp-uplab{ font-size:15px; font-weight:600; margin:14px 2px 10px; display:flex; gap:2px; }
  .fp-uploads .fp-uplab .fp-star{ color:var(--fp-red); margin-right:1px; }
  .fp-upbtn{ width:86px; height:86px; border:1px solid #dcdfe6; border-radius:8px; display:flex; flex-direction:column; align-items:center; justify-content:center; gap:7px; color:var(--fp-blue); cursor:pointer; }
  .fp-upbtn svg{ width:30px; height:30px; }
  .fp-upbtn span{ font-size:13px; }
  .fp-empty{ color:var(--fp-muted); font-size:13px; padding:10px 2px; }
  .fp-foot{ display:flex; gap:12px; padding:12px 16px; border-top:1px solid var(--fp-line); background:#fff; }
  .fp-foot button{ border:0; border-radius:8px; padding:12px 0; font-size:16px; font-weight:600; cursor:pointer; }
  .fp-foot .fp-back2{ background:#f2f3f5; color:#323233; flex:0 0 96px; }
  .fp-foot .fp-submit{ background:var(--fp-blue); color:#fff; flex:1; }
  .fp-hint{ color:var(--fp-muted); font-size:12px; margin:2px 2px 0; }
  `;
  const el = document.createElement("style");
  el.id = FP_STYLE_ID;
  el.textContent = css;
  document.head.appendChild(el);
}

/* ---------- tiny svg icons matching the app ---------- */
const ICON_SEARCH = `<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.1" stroke-linecap="round"><circle cx="11" cy="11" r="7"/><line x1="16.5" y1="16.5" x2="21" y2="21"/></svg>`;
// barcode-scan frame (corner brackets + center line), like the app's ScanIconButton
const ICON_SCAN = `<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M3 8V5a2 2 0 0 1 2-2h3"/><path d="M21 8V5a2 2 0 0 0-2-2h-3"/><path d="M3 16v3a2 2 0 0 0 2 2h3"/><path d="M21 16v3a2 2 0 0 1-2 2h-3"/><line x1="3.5" y1="12" x2="20.5" y2="12"/></svg>`;
const ICON_UPLOAD = `<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round"><path d="M12 15V4"/><path d="m8 8 4-4 4 4"/><path d="M4 14v4a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2v-4"/></svg>`;
const ICON_MINUS = `<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8"><circle cx="12" cy="12" r="9"/><line x1="8" y1="12" x2="16" y2="12"/></svg>`;
const ICON_PLUS = `<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8"><circle cx="12" cy="12" r="9"/><line x1="8" y1="12" x2="16" y2="12"/><line x1="12" y1="8" x2="12" y2="16"/></svg>`;
const ICON_SIGNAL = `<svg viewBox="0 0 24 24" width="15" height="11" fill="currentColor"><rect x="1" y="7" width="3" height="5" rx="1"/><rect x="6" y="5" width="3" height="7" rx="1"/><rect x="11" y="3" width="3" height="9" rx="1"/><rect x="16" y="1" width="3" height="11" rx="1"/></svg>`;
const ICON_BATT = `<svg viewBox="0 0 26 13" width="22" height="11" fill="none"><rect x="1" y="1" width="20" height="11" rx="3" stroke="currentColor"/><rect x="3" y="3" width="14" height="7" rx="1.5" fill="currentColor"/><rect x="22.5" y="4" width="2" height="5" rx="1" fill="currentColor"/></svg>`;

/* ---------- schema derivations (the "universal" layer) ---------- */

// Truthy iff the profile carries a non-empty gradeMap — same signal the app keys off.
function isGradedProfile(p) {
  return !!(p && p.gradeMap && Object.keys(p.gradeMap).length);
}

// SN scan fields as PLUGINS. Honors an explicit profile.snPlugins; otherwise synthesizes the standard
// set from snFields/requiresSecondSn so legacy profiles still render. A form with no base-station SN
// (requiresSecondSn=false, no snFields.secondary) simply won't get that plugin — which is the point.
export function deriveSnPlugins(p) {
  if (Array.isArray(p && p.snPlugins) && p.snPlugins.length) {
    return p.snPlugins.map((s) => ({
      key: s.key || s.field || "sn",
      label: s.label || "SN",
      field: s.field || "",
      required: !!s.required,
      search: s.search !== false,
      scan: s.scan !== false,
      placeholder: s.placeholder || "请输入"
    }));
  }
  const sn = (p && p.snFields) || {};
  const out = [{ key: "primary", label: "SN", field: sn.primary || "sn", required: true, search: true, scan: true, placeholder: "请输入" }];
  if ((p && p.requiresSecondSn) || sn.secondary) {
    out.push({ key: "secondary", label: "基站SN", field: sn.secondary || "", required: false, search: false, scan: true, placeholder: "请输入" });
  }
  if (sn.package) {
    out.push({ key: "package", label: "彩盒 SN", field: sn.package, required: false, search: true, scan: true, placeholder: "请输入" });
  }
  // 装箱号 ONLY if the schema actually carries a carton field — never fabricate one. Its real backend
  // submit-field id has to come from the template (the converter currently doesn't capture it), so a
  // form that genuinely collects 装箱号 must declare snFields.carton or an explicit snPlugins entry.
  if (sn.carton) {
    out.push({ key: "carton", label: "装箱号", field: sn.carton, required: false, search: false, scan: false, placeholder: "请输入" });
  }
  return out;
}

// 翻新结果 options = the form's good grades (A/B/C order) PLUS 不良品 when the template offers it
// (profile.defective). It's ONE form: the good grades and 不良品 are mutually-exclusive picks on the
// same 翻新结果 field, and the pick drives which photos show. Data-driven — 不良品 appears only if the
// real template carries it, never synthesized.
export function deriveResultOptions(p) {
  const opts = [];
  const gm = (p && p.gradeMap) || {};
  for (const g of ["A", "B", "C"]) {
    if (gm[g]) opts.push({ kind: "grade", grade: g, label: gm[g].label || (gm[g].value && gm[g].value.name) || g });
  }
  if (p && p.defective) opts.push({ kind: "defective", label: p.defective.label || "不良品" });
  return opts;
}

// The 不良品-branch photo boxes (profile.defective.photoSlots). Empty when the form has no 不良品 result.
export function defectivePhotoSlots(p) {
  const arr = p && p.defective && Array.isArray(p.defective.photoSlots) ? p.defective.photoSlots : [];
  return arr.map(normSlot);
}
export function hasDefectiveBranch(p) {
  return !!(p && p.defective);
}

// Keep the app-facing snFields/requiresSecondSn in sync with the editable snPlugins list, so a profile
// whose SN plugins were edited in the panel still submits correctly in the app (which reads snFields).
export function syncSnFields(p) {
  if (!p || !Array.isArray(p.snPlugins)) return;
  const sn = p.snFields || (p.snFields = {});
  const byKey = (k) => p.snPlugins.find((s) => s.key === k);
  const primary = byKey("primary") || p.snPlugins[0];
  sn.primary = (primary && primary.field) || sn.primary || "sn";
  const secondary = byKey("secondary");
  if (secondary && secondary.field) { sn.secondary = secondary.field; p.requiresSecondSn = true; }
  else { sn.secondary = ""; p.requiresSecondSn = false; }
  const pkg = byKey("package");
  if (pkg && pkg.field) sn.package = pkg.field; else delete sn.package;
  const carton = byKey("carton");
  if (carton && carton.field) sn.carton = carton.field; else delete sn.carton;
}

// Normalize a slot-ish object to { field, title, minPhotos, maxPhotos, required }.
function normSlot(s) {
  return {
    field: s.field || s.kind || "",
    title: s.title || s.field || "上传照片",
    minPhotos: s.minPhotos == null ? 1 : s.minPhotos,
    maxPhotos: s.maxPhotos == null ? 10 : s.maxPhotos,
    required: s.required !== false
  };
}

// Photo slots for the ABC (graded) branch. Supports v2 photoSlots AND legacy uploadFields/imageFields.
export function gradePhotoSlots(p) {
  if (Array.isArray(p && p.photoSlots)) return p.photoSlots.map(normSlot);
  if (Array.isArray(p && p.uploadFields) && p.uploadFields.length) return p.uploadFields.map(normSlot);
  if (p && p.imageFields && (p.imageFields.front || p.imageFields.back)) {
    const out = [];
    if (p.imageFields.front) out.push(normSlot({ field: "front", title: "请上传正面照片" }));
    if (p.imageFields.back) out.push(normSlot({ field: "back", title: "请上传背面照片" }));
    return out;
  }
  return [];
}

// Optional, always-available extras (e.g. the battery photo).
// Off unless the profile opts in (profile.optionalSlots) — kept generic, no hardcoded product fields.
export function optionalSlots(p) {
  if (Array.isArray(p && p.optionalSlots)) {
    return p.optionalSlots.map((s) => ({ ...normSlot(s), required: false }));
  }
  return [];
}

// A multi-select group { title, multi, items:[label...] } from conditionalFields / operationFields.
function fieldGroups(arr) {
  return (Array.isArray(arr) ? arr : [])
    .filter((f) => f && Array.isArray(f.value) && f.value.length)
    .map((f) => ({ title: f.title || "请选择", items: f.value.slice() }));
}

/* ---------- render ---------- */

export function renderFormPreview(mount, profile, opts = {}) {
  injectStyles();
  const p = profile || {};
  // Theme color follows profile.uiColor (panel-editable via the 设置 color picker), falling back to the
  // official app blue when unset/invalid — so the mockup shows the actual color the operator will get.
  const accent = /^#[0-9a-fA-F]{6}$/.test(String(p.uiColor || "")) ? p.uiColor : "#1989fa";

  const snPlugins = deriveSnPlugins(p);
  const results = deriveResultOptions(p);
  const photoSlots = gradePhotoSlots(p);   // good-branch photos (外观/配件)
  const defSlots = defectivePhotoSlots(p); // 不良品-branch photos (empty unless the template has a 不良品 result)
  const extraSlots = optionalSlots(p);
  const matGroups = (p.materialGroups || []).filter((g) => ((g.materials || []).length || (g._allMaterials || []).length));

  // Local interaction state. `edit()` flows a preview edit back into the profile/JSON (index.html passes
  // opts.onEdit). When onEdit is absent the preview is a read-only mockup (old behavior).
  const state = { result: opts.result || null, checks: {}, onlySel: {} };
  const edit = () => { if (opts.onEdit) opts.onEdit(); };
  // Editable 多选 over a field's string `value` (非新机原因 / 操作内容): shows the FULL option list
  // (materialized once into field.allValues), each checked iff still in `value`; toggling edits `value`,
  // which is exactly what the app submits (data[field]=value). So the checkboxes control real submission.
  function editableStrList(fieldObj, defaultTitle, id) {
    if (!Array.isArray(fieldObj.allValues)) fieldObj.allValues = (fieldObj.value || []).slice();
    const sel = new Set(fieldObj.value || []);
    return multiSelect({
      id, title: fieldObj.title || defaultTitle, required: true, searchPlaceholder: "搜索",
      items: fieldObj.allValues.map((v) => ({ label: v, checked: sel.has(v), key: v })),
      editable: !!opts.onEdit,
      onToggle: (v, on) => {
        const cur = new Set(fieldObj.value || []);
        if (on) cur.add(v); else cur.delete(v);
        fieldObj.value = fieldObj.allValues.filter((x) => cur.has(x)); // keep source order
        edit();
      },
      state
    });
  }

  // Editable generic choice (choiceFields): single→radio, multi→checkbox. The submitted `value`
  // (a string for single, an array of option values for multi) is EXACTLY what the app sends
  // (data[field]=value), so these controls drive real submission. options are {value,label} — we
  // show the label but store/submit the value.
  function editableChoice(fieldObj, id) {
    const optList = Array.isArray(fieldObj.options) ? fieldObj.options : [];
    if (fieldObj.kind === "multi") {
      const sel = new Set(Array.isArray(fieldObj.value) ? fieldObj.value : []);
      return multiSelect({
        id, title: fieldObj.title || "请选择", required: !!fieldObj.required, searchPlaceholder: "搜索",
        items: optList.map((o) => ({ label: o.label || o.value, checked: sel.has(o.value), key: o.value })),
        editable: !!opts.onEdit,
        onToggle: (val, on) => {
          const cur = new Set(Array.isArray(fieldObj.value) ? fieldObj.value : []);
          if (on) cur.add(val); else cur.delete(val);
          fieldObj.value = optList.map((o) => o.value).filter((v) => cur.has(v)); // keep source order
          edit();
        },
        state
      });
    }
    // single → radio: exactly one selected; picking sets fieldObj.value to that option value.
    const wrap = document.createElement("div");
    wrap.className = "fp-field";
    wrap.appendChild(h(`<div class="fp-lab">${fieldObj.required ? '<span class="fp-star">*</span>' : ""}${esc(fieldObj.title || "请选择")}<span class="fp-multi">[单选]</span></div>`));
    const paint = () => {
      [...wrap.querySelectorAll(".fp-opt")].forEach((el) => el.remove());
      optList.forEach((o) => {
        const on = fieldObj.value === o.value;
        const opt = h(`<div class="fp-opt ${on ? "on" : ""}"><div class="fp-radio"></div><div class="fp-txt">${esc(o.label || o.value)}</div></div>`);
        if (opts.onEdit) opt.onclick = () => { fieldObj.value = o.value; paint(); edit(); };
        wrap.appendChild(opt);
      });
    };
    paint();
    return wrap;
  }

  const root = document.createElement("div");
  root.className = "fp-phone";
  if (accent) root.style.setProperty("--fp-blue", accent);

  // status + nav bar
  root.appendChild(h(`
    <div class="fp-status"><span>3:40</span><span class="fp-dots">${ICON_SIGNAL}<span style="display:inline-flex">${ICON_BATT}</span></span></div>
    <div class="fp-nav"><div class="fp-back">‹</div><div class="fp-title">表单录入</div></div>
  `));

  const body = document.createElement("div");
  body.className = "fp-body";
  root.appendChild(body);

  // form title
  body.appendChild(h(`<div class="fp-formtitle">${esc(p.displayName || p.model || p.id || "（未命名表单）")}</div>`));

  // SN plugin fields
  snPlugins.forEach((sp) => {
    const f = document.createElement("div");
    f.className = "fp-field";
    f.appendChild(h(`<div class="fp-lab">${sp.required ? '<span class="fp-star">*</span>' : ""}${esc(sp.label)}</div>`));
    const row = document.createElement("div");
    row.className = "fp-snrow";
    row.appendChild(h(`<input placeholder="${esc(sp.placeholder || "请输入")}" />`));
    if (sp.search) row.appendChild(iconBtn(ICON_SEARCH));
    if (sp.scan) row.appendChild(iconBtn(ICON_SCAN));
    f.appendChild(row);
    body.appendChild(f);
  });

  // 更换或补充配件 (materials) — editable: checkbox = included in the submitted list, qty = defaultQty.
  // The full list lives in g._allMaterials (display); g.materials is the included subset the app submits.
  // Kept in its own section so picking 不良品 hides it too (不良品 不换料 — 换料只属于好成色流程).
  const matSection = document.createElement("div");
  body.appendChild(matSection);
  matGroups.forEach((g, gi) => {
    if (!Array.isArray(g._allMaterials)) g._allMaterials = (g.materials || []).slice();
    const selCodes = new Set((g.materials || []).map((m) => m.code));
    matSection.appendChild(multiSelect({
      id: "mat" + gi,
      title: g.title || "更换或补充配件",
      searchPlaceholder: "搜索部件",
      items: g._allMaterials.map((m) => ({ label: m.name || m.code, qty: m.defaultQty || 1, checked: selCodes.has(m.code), key: m.code })),
      withQty: true,
      preCheckAll: g.selectAll !== false,
      editable: !!opts.onEdit,
      onToggle: (code, on) => {
        if (on) { if (!(g.materials || []).some((m) => m.code === code)) { const full = g._allMaterials.find((m) => m.code === code); if (full) (g.materials = g.materials || []).push(full); } }
        else { g.materials = (g.materials || []).filter((m) => m.code !== code); }
        edit();
      },
      onQty: (code, n) => { const m = g._allMaterials.find((x) => x.code === code); if (m) m.defaultQty = n; const m2 = (g.materials || []).find((x) => x.code === code); if (m2) m2.defaultQty = n; edit(); },
      state
    }));
  });

  // Photos for the GOOD grades only (外观/配件). 不良品 录入 lives in its OWN 不良品表单 now — picking
  // 不良品 here does NOT show 不良品 photos; it hides the good-grade section and shows a redirect note.
  const uploadWrap = document.createElement("div");
  uploadWrap.className = "fp-uploads";
  function renderUploads() {
    uploadWrap.innerHTML = "";
    photoSlots.forEach((s) => uploadWrap.appendChild(uploadSlot(s)));
    (p.operationFields || []).filter((f) => Array.isArray(f.value)).forEach((f, gi) => uploadWrap.appendChild(editableStrList(f, "操作内容", "op" + gi)));
    extraSlots.forEach((s) => uploadWrap.appendChild(uploadSlot(s)));
  }

  // *翻新结果 (result radio) — good grades PLUS 不良品 (when the template offers it). Picking a grade
  // shows the good-grade section; picking 不良品 hides it and shows the 去不良品表单 redirect.
  if (results.length) {
    const resWrap = document.createElement("div");
    resWrap.className = "fp-field";
    resWrap.appendChild(h(`<div class="fp-lab"><span class="fp-star">*</span>结果<span class="fp-reset" data-reset>重置</span></div>`));
    const paint = () => {
      [...resWrap.querySelectorAll(".fp-opt")].forEach((el) => el.remove());
      results.forEach((r) => {
        const key = r.kind === "defective" ? "__def__" : "grade:" + r.grade;
        const opt = h(`<div class="fp-opt ${state.result === key ? "on" : ""}"><div class="fp-radio"></div><div class="fp-txt">${esc(r.label)}</div></div>`);
        opt.onclick = () => { state.result = key; paint(); applyResultView(); };
        resWrap.appendChild(opt);
      });
    };
    paint();
    const rst = resWrap.querySelector("[data-reset]");
    if (rst) rst.onclick = (e) => { e.stopPropagation(); state.result = null; paint(); applyResultView(); };
    body.appendChild(resWrap);
  }

  // 不良品 redirect note — 不良品 是独立表单，选它就别在这张表里填了。
  const defRedirect = h(`<div style="margin:10px 0;padding:10px 12px;background:#FFF7E6;border:1px solid #FFD591;border-radius:8px;color:#AD6800;font-size:13px;line-height:1.5">已选「不良品」——请转到「不良品表单」进行编辑（不良品照片、拆箱/外观/功能检测项都在独立的不良品表单里填）。</div>`);
  defRedirect.style.display = "none";
  body.appendChild(defRedirect);

  // All result-dependent fields in ONE section so 不良品 hides them together (非新机原因 / choiceFields / 照片).
  const lowerSection = document.createElement("div");
  // 非新机原因 — re-rendered per selected grade when the profile carries perGrade (A 类=使用痕迹子集,
  // B/C=全部). Editing a grade edits THAT grade's list (perGrade[g]); no perGrade falls back to flat value.
  const reasonsWrap = document.createElement("div");
  lowerSection.appendChild(reasonsWrap);
  function currentGrade() { return (state.result && state.result.indexOf("grade:") === 0) ? state.result.slice(6) : null; }
  function renderReasons() {
    reasonsWrap.innerHTML = "";
    const g = currentGrade();
    (p.conditionalFields || []).filter((f) => Array.isArray(f.value)).forEach((f, gi) => {
      const usePer = f.perGrade && g && Array.isArray(f.perGrade[g]);
      const selected = usePer ? f.perGrade[g] : (f.value || []);
      const title = (f.title || "非新机原因") + (usePer ? `（${g} 类）` : (f.perGrade ? "（选等级后按档显示）" : ""));
      reasonsWrap.appendChild(multiSelect({
        id: "rs" + gi, title, required: true, searchPlaceholder: "搜索",
        items: (f.value || []).map((v) => ({ label: v, checked: selected.indexOf(v) >= 0, key: v })),
        editable: !!opts.onEdit,
        onToggle: (v, on) => {
          const cur = new Set(usePer ? f.perGrade[g] : (f.value || []));
          if (on) cur.add(v); else cur.delete(v);
          const next = (f.value || []).filter((x) => cur.has(x)); // keep master order
          if (usePer) f.perGrade[g] = next; else f.value = next;
          edit();
        },
        state
      }));
    });
  }
  (p.choiceFields || []).forEach((f, gi) => lowerSection.appendChild(editableChoice(f, "cf" + gi)));
  lowerSection.appendChild(uploadWrap);
  body.appendChild(lowerSection);

  function applyResultView() {
    const isDef = state.result === "__def__";
    matSection.style.display = isDef ? "none" : "";
    lowerSection.style.display = isDef ? "none" : "";
    defRedirect.style.display = isDef ? "block" : "none";
    if (!isDef) { renderReasons(); renderUploads(); }
  }
  applyResultView();

  // footer
  root.appendChild(h(`<div class="fp-foot"><button class="fp-back2">返回</button><button class="fp-submit">提交</button></div>`));

  mount.innerHTML = "";
  mount.appendChild(root);
  return root;
}

/* ---------- small builders ---------- */

function h(html) {
  const t = document.createElement("template");
  t.innerHTML = html.trim();
  return t.content.firstChild;
}
function esc(s) {
  return String(s == null ? "" : s).replace(/[&<>"]/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;" }[c]));
}
function iconBtn(svg) {
  const b = document.createElement("span");
  b.className = "fp-icon";
  b.innerHTML = svg;
  return b;
}
function uploadSlot(s) {
  const w = document.createElement("div");
  w.appendChild(h(`<div class="fp-uplab">${s.required ? '<span class="fp-star">*</span>' : ""}${esc(s.title)}</div>`));
  w.appendChild(h(`<div class="fp-upbtn">${ICON_UPLOAD}<span>上传</span></div>`));
  return w;
}

// A faithful 多选 group: title + [多选] + search box + 全选 row with 已选 switch + checkbox list.
// `withQty`: checked rows grow a quantity stepper (X N ⊖ ⊕), like the company app's materials list.
// `items` may be strings or { label, qty } objects.
// `editable`: when true the control EDITS the profile — checkbox toggles / qty steppers call onToggle/
// onQty(key, …), and each item's initial `checked` comes from the data (not preCheckAll). This turns the
// preview into a real editor whose changes flow into the JSON (index.html passes callbacks that mutate
// DRAFT). Non-editable keeps the old mockup behavior. `items` may be strings or { label, qty, checked, key }.
function multiSelect({ id, title, required, searchPlaceholder, items, withQty, preCheckAll, editable, onToggle, onQty, state }) {
  const data = (items || []).map((it) => (typeof it === "string"
    ? { label: it, qty: 1, checked: false, key: it }
    : { label: it.label, qty: it.qty == null ? 1 : it.qty, checked: !!it.checked, key: it.key == null ? it.label : it.key }));
  // Index ALL state by the stable `key` (submit value / material code), NEVER the display label —
  // two options can share a label but carry different keys (重名选项), and label-keyed state would
  // silently merge them (预览显示 与 实际提交 不一致). label is used only for display + search.
  const wrap = document.createElement("div");
  wrap.className = "fp-field";
  wrap.appendChild(h(`<div class="fp-lab">${required ? '<span class="fp-star">*</span>' : ""}${esc(title)}<span class="fp-multi">[多选]</span></div>`));

  const search = h(`<div class="fp-search">${ICON_SEARCH}<input placeholder="${esc(searchPlaceholder || "搜索")}" /></div>`);
  wrap.appendChild(search);

  const selrow = h(`<div class="fp-selrow"></div>`);
  const allOpt = h(`<div class="fp-opt" style="padding:6px 0"><div class="fp-box"></div><div class="fp-txt">全选</div></div>`);
  const note = h(`<div class="fp-selnote"><span class="fp-switch"></span><span>已选</span></div>`);
  selrow.appendChild(allOpt);
  selrow.appendChild(note);
  wrap.appendChild(selrow);

  const list = document.createElement("div");
  wrap.appendChild(list);

  // In editable mode the initial checked state comes from the data; otherwise preCheckAll (mockup).
  // We rebuild checks each render (editable) so it always mirrors the profile after external JSON edits.
  const checks = {};
  if (editable) data.forEach((d) => { checks[d.key] = d.checked; });
  else {
    state.checks[id] = state.checks[id] || {};
    Object.assign(checks, state.checks[id]);
    if (preCheckAll && !Object.keys(checks).length) data.forEach((d) => { checks[d.key] = true; });
    state.checks[id] = checks;
  }
  const qtys = {};
  data.forEach((d) => { qtys[d.key] = d.qty; });
  let query = "";
  let onlySel = false;

  function draw() {
    list.innerHTML = "";
    const visible = data.filter((d) => (!query || d.label.toLowerCase().includes(query)) && (!onlySel || checks[d.key]));
    if (!visible.length) list.appendChild(h(`<div class="fp-empty">无匹配项</div>`));
    visible.forEach((d) => {
      const on = !!checks[d.key];
      const row = h(`<div class="fp-opt ${on ? "on" : ""}"><div class="fp-box"></div><div class="fp-txt">${esc(d.label)}</div></div>`);
      if (withQty && on) {
        const st = h(`<div class="fp-qty"><span class="fp-qn">X ${qtys[d.key] || 1}</span></div>`);
        st.onclick = (e) => e.stopPropagation(); // tapping the stepper must not toggle the checkbox
        const minus = iconBtn(ICON_MINUS), plus = iconBtn(ICON_PLUS);
        minus.onclick = (e) => { e.stopPropagation(); qtys[d.key] = Math.max(1, (qtys[d.key] || 1) - 1); if (editable && onQty) onQty(d.key, qtys[d.key]); draw(); };
        plus.onclick = (e) => { e.stopPropagation(); qtys[d.key] = (qtys[d.key] || 1) + 1; if (editable && onQty) onQty(d.key, qtys[d.key]); draw(); };
        st.appendChild(minus); st.appendChild(plus);
        row.appendChild(st);
      }
      row.onclick = () => { checks[d.key] = !checks[d.key]; if (editable && onToggle) onToggle(d.key, checks[d.key]); draw(); };
      list.appendChild(row);
    });
    allOpt.classList.toggle("on", data.length && data.every((d) => checks[d.key]));
  }

  allOpt.onclick = () => {
    const allOn = data.every((d) => checks[d.key]);
    data.forEach((d) => { const nv = !allOn; if (checks[d.key] !== nv) { checks[d.key] = nv; if (editable && onToggle) onToggle(d.key, nv); } });
    draw();
  };
  note.querySelector(".fp-switch").onclick = function () {
    onlySel = !onlySel; this.classList.toggle("on", onlySel); draw();
  };
  search.querySelector("input").oninput = function () { query = this.value.trim().toLowerCase(); draw(); };

  draw();
  return wrap;
}
