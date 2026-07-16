// Admin Worker: backend-gated panel for authoring form profiles and publishing them to the catalog
// repo, plus the app-facing /catalog/* endpoints. Static SPA is served from ./public via the
// ASSETS binding. Secrets (GITHUB_TOKEN) live only here; the backend token stays in the panel and is
// sent as a Bearer on each call (the panel also sends a stable X-Fp fingerprint per session).

// Relative import of the shared profile validator (a plain ESM module with no deps) so the panel
// bundles standalone with `npx wrangler`, without an npm workspace install.
import { preserveRuntimeProfileConfig, validateFormProfile } from "./profile.js";
// Only verifyToken is used now: the browser talks to the backend directly for login/captcha/template
// reads (the CF egress IP may be blocked for the login endpoint), so the Worker no longer proxies those.
import { verifyToken } from "./backend.js";
import { templateToProfile } from "./convert.js";
import { aiRefineProfile, allowedRefs } from "./ai.js";
import { translateProfileTitles } from "./translate.js";
import { publishCatalog, readProfiles, readCatalogFile } from "./catalog.js";

const json = (data, status = 200) =>
  new Response(JSON.stringify(data), { status, headers: { "Content-Type": "application/json; charset=utf-8" } });

function auth(request) {
  const header = request.headers.get("Authorization") || "";
  const token = header.startsWith("Bearer ") ? header.slice(7).trim() : "";
  const fingerprint = request.headers.get("X-Fp") || "";
  return { token, fingerprint };
}

function validSessionInvalidCodes(value) {
  return Array.isArray(value) && value.every((item) =>
    (typeof item === "string" && item.trim() !== "")
    || (typeof item === "number" && Number.isFinite(item)));
}

function validSessionInvalidMessagePatterns(value) {
  return Array.isArray(value)
    && value.every((item) => typeof item === "string" && item.trim() !== "");
}

/** Publish-readiness check. Grade is optional (a profile may have no gradeMap); photos may be
 *  expressed as photoSlots (v2) or legacy uploadFields. validateFormProfile already tolerates a
 *  missing gradeMap, so we add only the submit-time requirements here. */
function validateForPublish(profile) {
  const errors = validateFormProfile(profile);
  if (!profile?.id) errors.push("id is required");
  if (!profile?.template?.id) errors.push("template.id is required");
  if (!profile?.template?.warehouseId) errors.push("template.warehouseId is required");
  if (!profile?.template?.sku) errors.push("template.sku is required");
  if (!(profile?.snFields?.primary || "sn")) errors.push("snFields.primary is required");
  const slots = Array.isArray(profile?.photoSlots) ? profile.photoSlots : [];
  const uploads = Array.isArray(profile?.uploadFields) ? profile.uploadFields : [];
  if (!slots.length && !uploads.length) errors.push("photoSlots or uploadFields is required");
  for (const s of slots) if (!s?.field) errors.push("photoSlots[].field is required");
  if (profile?.graded && (!profile?.gradeMap || !Object.keys(profile.gradeMap).length)) {
    errors.push("graded profile requires a non-empty gradeMap");
  }
  return errors;
}

/** Build the LLM user message: real template fields (the only legal ids) + draft + instructions. */
function aiUserPrompt(template, draft, instructions) {
  const fields = (template?.field_list || []).map((f) => ({
    field: f.field,
    type: f.type,
    title: f.title || f.en_title,
    required: !!f.required,
    conditional: f.visible === false,
    maxCount: f.count,
    options: (f.option_list || []).map((o) => ({ value: o.value, name: o.name || o.en_name }))
  }));
  return [
    "【后端真实模板字段（只能使用这里出现的 field 和 option value）】",
    JSON.stringify(fields, null, 2),
    "",
    "【当前草稿 profile】",
    JSON.stringify(draft, null, 2),
    "",
    "【指令】",
    instructions || "把草稿整理成可用的 v2 profile。",
    "",
    "只输出更新后的完整 profile JSON。"
  ].join("\n");
}

async function handleApi(request, env, url) {
  const path = url.pathname;

  // ---- Catalog read: app uses the read-key; panel browser uses its logged-in backend token.
  // 没有任一有效凭证 → 401(不对公网匿名开放,堵掉目录被人直接爬走的洞)。----
  if (path === "/api/profiles" && request.method === "GET") {
    if (!catalogReadAuthorized(request, env)) {
      const a = auth(request);
      let ok = false;
      if (a.token) { try { await verifyToken(env, a.token, a.fingerprint); ok = true; } catch {} }
      if (!ok) return json({ error: "unauthorized" }, 401);
    }
    return json(await readProfiles(env));
  }

  // ---- Everything below requires a logged-in backend user. The BROWSER logs in directly against the
  // backend (its login endpoint may be IP-gated to the operator's own network, but not the Cloudflare
  // egress IP) and sends the resulting backend token here. The Worker verifies it via the backend's
  // userInfo, which works from the CF egress IP — only the login endpoint is IP-gated, token reads are
  // not. So a valid backend login is the single gate; no separate panel password. ----
  const { token, fingerprint } = auth(request);
  if (!token) return json({ error: "请先登录" }, 401);
  let backendUser;
  try {
    backendUser = await verifyToken(env, token, fingerprint);
  } catch {
    return json({ error: "登录已失效，请重新登录" }, 401);
  }

  if (path === "/api/me" && request.method === "GET") {
    return json(backendUser);
  }

  // Pure transform: a raw backend template (fetched browser-side, direct from the backend) -> draft profile.
  if (path === "/api/convert" && request.method === "POST") {
    const b = await request.json();
    if (!b.template) return json({ error: "template required" }, 400);
    try {
      return json({ template: b.template, draft: templateToProfile(b.template) });
    } catch (e) {
      return json({ error: String(e && e.message ? e.message : e) }, 400);
    }
  }

  if (path === "/api/ai/draft" && request.method === "POST") {
    const b = await request.json();
    const template = b.template || null; // the browser fetches templateDetail directly and passes it inline
    const draft = b.draft || (template ? templateToProfile(template) : null);
    if (!draft) return json({ error: "需要 draft 或 template" }, 400);
    const refined = await aiRefineProfile({
      baseUrl: env.AI_BASE_URL,
      apiKey: env.AI_API_KEY,
      model: env.AI_MODEL,
      user: aiUserPrompt(template || {}, draft, b.instructions),
      allowed: allowedRefs(template, draft)
    });
    const profile = preserveRuntimeProfileConfig(refined.profile, draft);
    return json({ profile, violations: refined.violations });
  }

  if (path === "/api/publish" && request.method === "POST") {
    const b = await request.json();
    const incoming = Array.isArray(b.profiles) ? b.profiles : [];
    if (!incoming.length) return json({ error: "no profiles to publish" }, 400);
    // Best-effort auto-translate of zh UI labels -> {en,es} sibling maps, BEFORE validation/upsert.
    // Gated by the AI key + an opt-out (translate:false). Fill-only-when-absent unless retranslate.
    // A translate failure never blocks publish — it just surfaces a warning and stays zh-only.
    const warnings = [];
    if (env.AI_API_KEY && b.translate !== false) {
      const cfg = { baseUrl: env.AI_BASE_URL, apiKey: env.AI_API_KEY, model: env.AI_MODEL };
      const opts = { retranslate: b.retranslate === true };
      let anyFailed = false;
      for (const p of incoming) {
        try {
          const r = await translateProfileTitles(p, cfg, opts);
          if (r.failed) anyFailed = true;
        } catch {
          anyFailed = true; // defensive: translateProfileTitles shouldn't throw, but never block publish
        }
      }
      if (anyFailed) warnings.push("翻译失败，仅中文");
    }
    const problems = [];
    incoming.forEach((p, i) => {
      const errs = validateForPublish(p);
      if (errs.length) problems.push({ index: i, id: p?.id, errors: errs });
    });
    if (problems.length) return json({ error: "validation failed", problems }, 422);
    // Default: upsert by id — publishing one profile must NOT wipe the others. With replace:true, write
    // EXACTLY the given set (the only way to REMOVE a profile from the catalog).
    let finalProfiles;
    if (b.replace) {
      finalProfiles = incoming;
    } else {
      const current = await readProfiles(env);
      const byId = new Map(current.profiles.map((p) => [p.id, p]));
      for (const p of incoming) byId.set(p.id, p);
      finalProfiles = [...byId.values()];
    }
    const result = await publishCatalog(env, finalProfiles, {
      publicUrl: env.PUBLIC_URL || url.origin,
      notes: b.notes || "",
      settings: b.settings // optional global settings; merged over previous by publishCatalog
    });
    return json({ ok: true, ...result, total: finalProfiles.length, ...(warnings.length ? { warnings } : {}) });
  }

  // Global panel settings (e.g. notify webhook), stored alongside profiles in the same catalog file.
  // Optionally reorders profiles: `profiles` may be a full array or a list of ids giving the new order.
  if (path === "/api/settings" && request.method === "POST") {
    const b = await request.json();
    const current = await readProfiles(env);
    let finalProfiles = current.profiles;
    if (Array.isArray(b.profiles) && b.profiles.length) {
      if (typeof b.profiles[0] === "string") {
        // id-order: reorder existing profiles by the given id list; unknown ids ignored, missing appended.
        const byId = new Map(current.profiles.map((p) => [p.id, p]));
        const ordered = [];
        for (const id of b.profiles) {
          if (byId.has(id)) {
            ordered.push(byId.get(id));
            byId.delete(id);
          }
        }
        finalProfiles = [...ordered, ...byId.values()];
      } else {
        // full array of profile objects -> the new set verbatim.
        finalProfiles = b.profiles;
      }
    }
    const settings = {};
    if (typeof b.notifyWebhook === "string") settings.notifyWebhook = b.notifyWebhook;
    if (typeof b.backendApiBase === "string") settings.backendApiBase = b.backendApiBase;
    if (typeof b.brand === "string") settings.brand = b.brand;
    if (typeof b.updateOwner === "string") settings.updateOwner = b.updateOwner;
    if (typeof b.updateRepo === "string") settings.updateRepo = b.updateRepo;
    if (typeof b.webOrigin === "string") settings.webOrigin = b.webOrigin;
    if (typeof b.webReferer === "string") settings.webReferer = b.webReferer;
    if (b.endpoints && typeof b.endpoints === "object" && !Array.isArray(b.endpoints)) {
      settings.endpoints = b.endpoints;
    }
    if (Object.prototype.hasOwnProperty.call(b, "sessionInvalidCodes")) {
      if (!validSessionInvalidCodes(b.sessionInvalidCodes)) {
        return json({ error: "sessionInvalidCodes must be an array of non-empty strings or numbers" }, 400);
      }
      settings.sessionInvalidCodes = b.sessionInvalidCodes;
    }
    if (Object.prototype.hasOwnProperty.call(b, "sessionInvalidMessagePatterns")) {
      if (!validSessionInvalidMessagePatterns(b.sessionInvalidMessagePatterns)) {
        return json({ error: "sessionInvalidMessagePatterns must be an array of non-empty strings" }, 400);
      }
      settings.sessionInvalidMessagePatterns = b.sessionInvalidMessagePatterns;
    }
    settings.updatedAt = new Date().toISOString();
    const result = await publishCatalog(env, finalProfiles, { publicUrl: env.PUBLIC_URL || url.origin, settings });
    return json({ ok: true, version: result.version });
  }

  return json({ error: "not found" }, 404);
}

function catalogTimingSafeEqual(a, b) {
  a = String(a || ""); b = String(b || "");
  if (a.length !== b.length) return false;
  let diff = 0;
  for (let i = 0; i < a.length; i++) diff |= a.charCodeAt(i) ^ b.charCodeAt(i);
  return diff === 0;
}

// 目录读取鉴权:配置了 CATALOG_READ_KEY 就要求带匹配的 Bearer(app 侧);没配置则开放(本地 dev / 未启用)。
function catalogReadAuthorized(request, env) {
  if (!env.CATALOG_READ_KEY) return true;
  return catalogTimingSafeEqual(auth(request).token, env.CATALOG_READ_KEY);
}

async function handleCatalog(request, env, path) {
  if (!catalogReadAuthorized(request, env)) return json({ error: "unauthorized" }, 401);
  const which = path.endsWith("/manifest") ? "manifest" : "form-profiles.json";
  const text = await readCatalogFile(env, which);
  if (text == null) return json({ error: "catalog not initialized" }, 404);
  return new Response(text, { headers: { "Content-Type": "application/json; charset=utf-8" } });
}

export default {
  async fetch(request, env) {
    const url = new URL(request.url);
    const path = url.pathname;
    try {
      // App-facing backend config. Same read-key gate as /catalog/* (open when CATALOG_READ_KEY unset).
      // Intercepted here (before the /api/ routing) so it uses catalogReadAuthorized, NOT the login gate.
      if (path === "/api/config" && request.method === "GET") {
        if (!catalogReadAuthorized(request, env)) return json({ error: "unauthorized" }, 401);
        const { settings } = await readProfiles(env);
        return json({
          backendApiBase: settings.backendApiBase || env.BACKEND_API_BASE || "",
          notifyWebhook: settings.notifyWebhook || "",
          brand: settings.brand || "",
          updateOwner: settings.updateOwner || "",
          updateRepo: settings.updateRepo || "",
          webOrigin: settings.webOrigin || "",
          webReferer: settings.webReferer || "",
          endpoints: settings.endpoints || {},
          sessionInvalidCodes: Array.isArray(settings.sessionInvalidCodes)
            ? settings.sessionInvalidCodes : [],
          sessionInvalidMessagePatterns: Array.isArray(settings.sessionInvalidMessagePatterns)
            ? settings.sessionInvalidMessagePatterns : [],
          updatedAt: settings.updatedAt || ""
        });
      }
      if (path.startsWith("/api/")) return await handleApi(request, env, url);
      if (path.startsWith("/catalog/")) return await handleCatalog(request, env, path);
      return env.ASSETS.fetch(request); // static SPA
    } catch (err) {
      return json({ error: String(err && err.message ? err.message : err) }, 500);
    }
  }
};
