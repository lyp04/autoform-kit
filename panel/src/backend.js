// Minimal backend client for the admin Worker. Talks to a configurable backend API
// (set BACKEND_API_BASE) using Bearer-token auth. Only verifyToken is used by the Worker today:
// the browser (index.html) performs login / captcha / template reads directly against the backend,
// because a backend's login endpoint may be network/IP-gated to operators' own machines (not the
// Cloudflare egress IP), while token-authenticated reads work from anywhere. The remaining exports
// mirror those browser-side calls for reuse and testing.

function apiHeaders(token, fingerprint) {
  const headers = { Accept: "application/json, text/plain, */*" };
  if (token) headers.Authorization = `Bearer ${token}`;
  if (fingerprint) headers["X-Browser-Fingerprint"] = String(fingerprint);
  return headers;
}

function apiBase(env) {
  return (env.BACKEND_API_BASE || "https://backend.example.com/api").replace(/\/+$/, "");
}

async function apiJson(base, path, { method = "GET", query, body, token, fingerprint } = {}) {
  const url = new URL(path.replace(/^\/+/, ""), `${base}/`);
  if (query) {
    for (const [k, v] of Object.entries(query)) {
      if (v !== undefined && v !== null && v !== "") url.searchParams.set(k, String(v));
    }
  }
  const headers = apiHeaders(token, fingerprint);
  let payload;
  if (body) {
    payload = new URLSearchParams(body).toString();
    headers["Content-Type"] = "application/x-www-form-urlencoded; charset=utf-8";
  }
  const res = await fetch(url, { method, headers, body: payload });
  const text = await res.text();
  let json = null;
  try {
    json = text ? JSON.parse(text) : null;
  } catch {
    json = null;
  }
  return { status: res.status, body: json, text };
}

function isSuccess(body) {
  if (!body || typeof body !== "object") return false;
  if (Object.prototype.hasOwnProperty.call(body, "code")) return Number(body.code) === 200;
  return Object.prototype.hasOwnProperty.call(body, "data");
}

function apiData(body) {
  if (!body || typeof body !== "object") return undefined;
  if (body.data !== undefined && body.data !== null) return body.data;
  return Object.prototype.hasOwnProperty.call(body, "code") ? undefined : body;
}

function apiError(result) {
  const b = result.body;
  return (b && (b.message || b.msg || b.error)) || result.text || `HTTP ${result.status}`;
}

export async function getCaptcha(env) {
  const base = apiBase(env);
  const r = await apiJson(base, "/account/getCaptcha");
  if (!isSuccess(r.body)) throw new Error(`getCaptcha failed: ${apiError(r)}`);
  const data = apiData(r.body) || {};
  return { client: data.client || "", captcha: data.captcha || "" };
}

function describe(step, r) {
  const code = r.body && (r.body.code ?? r.body.status);
  return `${step} [http=${r.status} code=${code}]: ${apiError(r)} :: ${String(r.text || "").slice(0, 200)}`;
}

export async function login(env, { account, password, captcha, client }) {
  const base = apiBase(env);
  const form = { account, password, captcha, client };
  const verify = await apiJson(base, "/account/userLoginVerify", { method: "POST", body: form });
  console.log("backend userLoginVerify", verify.status, String(verify.text || "").slice(0, 200));
  if (!isSuccess(verify.body)) throw new Error(describe("userLoginVerify", verify));
  const login = await apiJson(base, "/account/adminLogin", { method: "POST", body: form });
  console.log("backend adminLogin", login.status, String(login.text || "").slice(0, 300));
  if (!isSuccess(login.body)) throw new Error(describe("adminLogin", login));
  const data = apiData(login.body) || {};
  const token =
    data?.token?.access_token || data.access_token || (typeof data.token === "string" ? data.token : "");
  if (!token) throw new Error("login succeeded but no token returned");
  const userName = data.userName || data.name || data.realName || account;
  return { token, userName };
}

function configuredSessionProofCodes(env) {
  const raw = String(env.BACKEND_SESSION_PROOF_CODES || "").trim();
  if (!raw) return new Set();
  let values;
  try {
    const parsed = JSON.parse(raw);
    values = Array.isArray(parsed) ? parsed : [parsed];
  } catch {
    values = raw.split(",");
  }
  return new Set(values.map((value) => String(value).trim()).filter(Boolean));
}

/** Confirm a token came from a real backend login before letting it publish.
 *
 *  A backend may return a non-success business code when the browser-created session is probed from
 *  the Worker. Deployers can list codes that still prove a freshly authenticated token in the
 *  BACKEND_SESSION_PROOF_CODES secret (JSON array or comma-separated string). The generic kit ships
 *  no backend-specific code. */
export async function verifyToken(env, token, fingerprint) {
  if (!token || String(token).trim().length < 20) throw new Error("backend token is not valid");
  const base = apiBase(env);
  const r = await apiJson(base, "/users/userInfo", { token, fingerprint });
  if (isSuccess(r.body)) {
    const data = apiData(r.body) || {};
    return { userName: data.userName || data.name || "", raw: data };
  }
  const proofCodes = configuredSessionProofCodes(env);
  if (r.body && proofCodes.has(String(r.body.code).trim())) {
    return { userName: "", raw: {}, sessionProof: true };
  }
  throw new Error("backend token is not valid");
}

export async function listTemplates(env, { token, fingerprint, keyword = "" }) {
  const base = apiBase(env);
  // Some backends 500 if we send pageSize or status filters; only page + keyword are kept for
  // portability. A keyword search returns all matches anyway.
  const r = await apiJson(base, "/retread/myTemplateListPage", {
    token,
    fingerprint,
    query: { page: 1, keyword }
  });
  if (!isSuccess(r.body)) throw new Error(`listTemplates failed: ${apiError(r)}`);
  const data = apiData(r.body);
  const items = Array.isArray(data) ? data : Array.isArray(data?.list) ? data.list : [];
  return items.map((t) => ({
    id: t.id ?? t.template_id ?? t.templateId,
    name: t.name || t.template_name || "",
    sku: t.sku || "",
    step: t.process_id ?? t.processId ?? t.step
  }));
}

export async function templateDetail(env, { token, fingerprint, id }) {
  const base = apiBase(env);
  const r = await apiJson(base, "/retread/templateDetail", {
    token, fingerprint, query: { id }
  });
  if (!isSuccess(r.body)) throw new Error(`templateDetail ${id} failed: ${apiError(r)}`);
  return apiData(r.body);
}
