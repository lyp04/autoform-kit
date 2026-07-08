// AI layer for the admin panel. Calls a configurable OpenAI-compatible chat endpoint
// (default: OpenRouter) to turn natural-language instructions + the real backend template into a
// refined form profile, then runs an anti-hallucination gate: every field id / option value the
// model emits MUST already exist in the source template (the model maps/structures — it never
// invents the dynamic keyXXXX ids the backend controls). Pure ESM (fetch only) so it runs both in
// the Worker and in a plain Node test.

const DEFAULT_BASE = "https://openrouter.ai/api/v1";
// A common, cheap OpenAI-compatible model as a sane default. Override with the AI_MODEL env var.
const DEFAULT_MODEL = "openai/gpt-4o-mini";

export const SYSTEM_PROMPT = [
  "你是一个翻新录入 App 的表单配置编辑器。你会收到：后端的真实模板字段（含精确的 field id 和 option value）、一份当前草稿 profile、以及操作员的自然语言指令。",
  "你的任务：按指令产出【完整的】更新后 profile，输出严格的 JSON（不要任何解释、不要 markdown 代码块）。",
  "硬规则：",
  "1. 只能使用模板/草稿里已经出现过的 field id 和 option value，绝不可凭空编造 keyXXXX 之类的字段 id 或 sku/code。",
  "2. 保持 template(id/step/sku/warehouseId)、snFields、各 field id、物料 code、等级 value 原样，除非指令明确要求改。",
  "3. 新版 v2 结构：照片用 photoSlots:[{field,title,minPhotos,maxPhotos}]（每框可多图）；不分等级时省略 gradeMap 并 graded:false。",
  "4. 字段语义：required=必填；conditional=true 表示该字段是【条件显示】的——操作员选了某个翻新结果/等级或点了某按钮后才出现，默认界面上没有；maxCount=该上传框最多张数。",
  "5. 默认生成【标准翻新表单】：photoSlots 只放常规会拍的照片（外观/配件类），minPhotos 按 required（必填=1、选填=0）、maxPhotos 按 maxCount；gradeMap 只含 A/B/C（不含『全新』『不良品』）。【不要】把只在特定结果下才出现的条件照片（如『不良品照片』）或非必填附加照片（如机器电量）放进标准表单。拿不准就不放。",
  "6. 不良品和 A/B/C 各自【独立成表】，互不混。当用户要【不良品/报废表单】时：photoSlots 放不良品相关照片框，gradeMap 用 retread_result 里『不良品』那个选项（单一），id 加 -defective 后缀、displayName 体现『不良品』。",
  "7. 照片框 title 用简短易懂的中文（如『外观照片』『配件照片』），但 field id 一律原样。",
  "8. 只输出 JSON 对象。"
].join("\n");

export async function callLLM({ baseUrl, apiKey, model, system, user, temperature = 0.2 }) {
  if (!apiKey) throw new Error("AI_API_KEY is required");
  const base = (baseUrl || DEFAULT_BASE).replace(/\/+$/, "");
  const url = base + "/chat/completions";
  const body = {
    model: model || DEFAULT_MODEL,
    temperature,
    max_tokens: 8000,
    messages: [
      { role: "system", content: system || SYSTEM_PROMPT },
      { role: "user", content: user }
    ]
  };
  // OpenRouter can route a model to a slow upstream provider. Sorting by throughput picks the
  // fastest host of the SAME model weights — identical output, just a faster server. OpenRouter-only field.
  if (base.includes("openrouter.ai")) body.provider = { sort: "throughput" };
  const res = await fetch(url, {
    method: "POST",
    signal: AbortSignal.timeout(100000), // some models can run 40-70s under load; give margin
    headers: {
      Authorization: `Bearer ${apiKey}`,
      "Content-Type": "application/json",
      // OpenRouter attribution headers (harmless for other OpenAI-compatible backends).
      "HTTP-Referer": "https://github.com/your-name/your-repo",
      "X-Title": "autoform-panel"
    },
    body: JSON.stringify(body)
  });
  const text = await res.text();
  if (!res.ok) throw new Error(`LLM HTTP ${res.status}: ${text.slice(0, 500)}`);
  let data;
  try {
    data = JSON.parse(text);
  } catch {
    throw new Error(`LLM returned non-JSON envelope: ${text.slice(0, 300)}`);
  }
  const msg = data?.choices?.[0]?.message || {};
  // Some free/reasoning models leave content empty and put the answer in reasoning fields.
  const content = msg.content || msg.reasoning_content || msg.reasoning || "";
  if (!content) throw new Error(`LLM returned no content: ${text.slice(0, 300)}`);
  return content;
}

/** Tolerantly extract a JSON object from an LLM reply (handles ```json fences and stray prose). */
export function parseJsonLoose(content) {
  let s = String(content).trim();
  const fence = s.match(/```(?:json)?\s*([\s\S]*?)```/i);
  if (fence) s = fence[1].trim();
  const first = s.indexOf("{");
  const last = s.lastIndexOf("}");
  if (first >= 0 && last > first) s = s.slice(first, last + 1);
  return JSON.parse(s);
}

function addAllowed(map, field, values) {
  if (!field) return;
  if (!map.fields.has(field)) map.fields.add(field);
  if (values && values.length) {
    const set = map.options.get(field) || new Set();
    for (const v of values) set.add(String(v));
    map.options.set(field, set);
  }
}

/** Allowed field ids + option values from a raw backend template (field_list). */
export function allowedRefsFromTemplate(template) {
  const refs = { fields: new Set(), options: new Map() };
  for (const f of template?.field_list || []) {
    addAllowed(refs, f.field, (f.option_list || []).map((o) => o.value));
  }
  return refs;
}

/** Allowed field ids + option values from an existing profile (which already carries real ids). */
export function allowedRefsFromProfile(profile) {
  const refs = { fields: new Set(), options: new Map() };
  (profile.uploadFields || []).forEach((u) => addAllowed(refs, u.field));
  (profile.photoSlots || []).forEach((s) => addAllowed(refs, s.field));
  if (profile.snFields?.secondary) refs.fields.add(profile.snFields.secondary);
  Object.values(profile.gradeMap || {}).forEach((g) => addAllowed(refs, g.field, [g?.value?.sku].filter(Boolean)));
  (profile.conditionalFields || []).forEach((c) => addAllowed(refs, c.field, c.value));
  (profile.operationFields || []).forEach((o) => addAllowed(refs, o.field, Array.isArray(o.value) ? o.value : [o.value]));
  (profile.materialGroups || []).forEach((g) => addAllowed(refs, g.field, (g.materials || []).map((m) => m.code)));
  (profile.choiceFields || []).forEach((c) => addAllowed(refs, c.field, (c.options || []).map((o) => o.value)));
  return refs;
}

/**
 * Union of allowed refs from a template AND a base profile. The AI may legitimately reference any
 * field/value that exists in either — crucial when editing an already-published profile (no live
 * template), where the draft itself carries the real field ids; otherwise the gate would falsely
 * flag every field as invented.
 */
export function allowedRefs(template, profile) {
  const a = allowedRefsFromTemplate(template || {});
  const b = allowedRefsFromProfile(profile || {});
  const fields = new Set([...a.fields, ...b.fields]);
  const options = new Map(a.options);
  for (const [k, set] of b.options) {
    const cur = options.get(k) || new Set();
    for (const v of set) cur.add(v);
    options.set(k, cur);
  }
  return { fields, options };
}

/** Returns a list of references the profile uses that don't exist in `allowed` (hallucinations). */
export function checkProfileRefs(profile, allowed) {
  const bad = [];
  const checkField = (field, where) => {
    if (field && !allowed.fields.has(field)) bad.push(`${where}: 未知 field "${field}"`);
  };
  (profile.photoSlots || []).forEach((s, i) => checkField(s.field, `photoSlots[${i}]`));
  (profile.uploadFields || []).forEach((u, i) => checkField(u.field, `uploadFields[${i}]`));
  if (profile.snFields?.secondary) checkField(profile.snFields.secondary, "snFields.secondary");
  Object.entries(profile.gradeMap || {}).forEach(([g, v]) => checkField(v.field, `gradeMap.${g}`));
  (profile.conditionalFields || []).forEach((c, i) => checkField(c.field, `conditionalFields[${i}]`));
  (profile.operationFields || []).forEach((o, i) => checkField(o.field, `operationFields[${i}]`));
  (profile.materialGroups || []).forEach((g, i) => {
    checkField(g.field, `materialGroups[${i}]`);
    const set = allowed.options.get(g.field);
    (g.materials || []).forEach((m, j) => {
      if (set && set.size && !set.has(String(m.code))) bad.push(`materialGroups[${i}].materials[${j}]: 未知物料 code "${m.code}"`);
    });
  });
  (profile.choiceFields || []).forEach((c, i) => {
    checkField(c.field, `choiceFields[${i}]`);
    const set = allowed.options.get(c.field);
    const vals = Array.isArray(c.value) ? c.value : (c.value === "" || c.value == null ? [] : [c.value]);
    vals.forEach((v) => {
      if (set && set.size && !set.has(String(v))) bad.push(`choiceFields[${i}]: 未知选项值 "${v}"`);
    });
  });
  return bad;
}

/**
 * One-shot: prompt -> LLM -> parsed profile -> ref gate. `allowed` is the set of legal refs
 * (from the backend template, or from the source profile when refining an existing one).
 */
export async function aiRefineProfile({ baseUrl, apiKey, model, user, allowed }) {
  const content = await callLLM({ baseUrl, apiKey, model, user });
  const profile = parseJsonLoose(content);
  const violations = allowed ? checkProfileRefs(profile, allowed) : [];
  return { profile, violations, raw: content };
}

const TRANSLATE_SYSTEM_PROMPT = [
  "You translate short UI labels for a factory refurbishment (翻新) shop-floor data-entry app.",
  "Translate each Chinese label to English and Spanish. Terse noun phrases, no trailing punctuation,",
  "preserve any 料号/SN codes verbatim. Return ONLY JSON."
].join(" ");

/**
 * Batch-translate a list of unique zh UI labels to {en,es}. Sends ONE call (temperature 0) and
 * returns a map keyed by the ORIGINAL zh string: { "外观照片": {en, es}, ... }. On ANY failure
 * (empty input, LLM error/timeout, parse failure, wrong shape) returns {} — never throws, so the
 * caller can safely fall back to zh-only. `titles` is an array of unique zh strings.
 */
export async function translateTitles({ baseUrl, apiKey, model, titles }) {
  const uniq = [...new Set((titles || []).map((t) => String(t == null ? "" : t)).filter((t) => t.trim() !== ""))];
  if (!uniq.length) return {};
  // Index-keyed so codes/duplicates survive the round-trip and we can re-key by original string.
  const indexed = {};
  uniq.forEach((t, i) => (indexed[i] = t));
  const user = [
    "Translate each Chinese label below. Return ONLY a JSON object keyed by the SAME index,",
    'each value {"en":"...","es":"..."}. Example: {"0":{"en":"...","es":"..."}}.',
    "",
    JSON.stringify(indexed, null, 2)
  ].join("\n");
  try {
    const content = await callLLM({
      baseUrl,
      apiKey,
      model,
      system: TRANSLATE_SYSTEM_PROMPT,
      user,
      temperature: 0
    });
    const parsed = parseJsonLoose(content);
    if (!parsed || typeof parsed !== "object" || Array.isArray(parsed)) return {};
    const out = {};
    uniq.forEach((zh, i) => {
      const entry = parsed[i] ?? parsed[String(i)];
      if (!entry || typeof entry !== "object") return;
      const en = typeof entry.en === "string" ? entry.en.trim() : "";
      const es = typeof entry.es === "string" ? entry.es.trim() : "";
      const map = {};
      if (en) map.en = en;
      if (es) map.es = es;
      if (map.en || map.es) out[zh] = map;
    });
    return out;
  } catch {
    return {};
  }
}

// 把冗长的「照片上传框」标题优化成简短干净的显示名(去掉「请上传」「（最多可上传N张）」这类套话、只保留核心
// 主体),并给出同样简短的 en/es。返回 { 原串: {zh,en,es} };任何失败返回 {}(调用方保留原串,不阻塞发布)。
export async function optimizeTitles({ baseUrl, apiKey, model, titles }) {
  const uniq = [...new Set((titles || []).map((t) => String(t == null ? "" : t)).filter((t) => t.trim() !== ""))];
  if (!uniq.length) return {};
  const indexed = {};
  uniq.forEach((t, i) => (indexed[i] = t));
  const system = [
    "你在为工厂翻新(翻新)录入 App 精简「照片上传框」的显示名。把每个冗长的中文标签优化成简短干净的显示名:",
    "去掉『请上传』『（最多可上传N张）』这类套话,只保留核心主体。例:",
    "『请上传机器人和基站外观图片（最多可上传十张）』→『外观图片』;",
    "『请上传机器人和基站配件放置图片（最多可上传十张）』→『配件放置图片』。",
    "再给出同样简短的英文、西班牙文(名词短语,无结尾标点、无括注)。只返回 JSON。"
  ].join("");
  const user = [
    'Optimize + translate each label. Return ONLY a JSON object keyed by the SAME index,',
    'each value {"zh":"...","en":"...","es":"..."} where zh is the shortened Chinese display name.',
    "",
    JSON.stringify(indexed, null, 2)
  ].join("\n");
  try {
    const content = await callLLM({ baseUrl, apiKey, model, system, user, temperature: 0 });
    const parsed = parseJsonLoose(content);
    if (!parsed || typeof parsed !== "object" || Array.isArray(parsed)) return {};
    const out = {};
    uniq.forEach((orig, i) => {
      const entry = parsed[i] ?? parsed[String(i)];
      if (!entry || typeof entry !== "object") return;
      const zh = typeof entry.zh === "string" ? entry.zh.trim() : "";
      const en = typeof entry.en === "string" ? entry.en.trim() : "";
      const es = typeof entry.es === "string" ? entry.es.trim() : "";
      if (zh || en || es) out[orig] = { zh: zh || orig, en, es };
    });
    return out;
  } catch {
    return {};
  }
}
