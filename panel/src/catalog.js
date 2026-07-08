// Reads/writes the private form-catalog repo via the GitHub Contents API, and serves it to the
// Android app. The catalog holds two files at the repo root:
//   form-profiles.json : { schemaVersion, version, profiles:[...] }   <- the app loads this
//   manifest.json      : { schemaVersion, version, sha256, profilesUrl, minAppVersionCode, ... }
// The app's FormCatalogManager fetches manifest.json, gates on schemaVersion/version, then
// downloads profilesUrl and SHA-256-verifies it against manifest.sha256.

export const SCHEMA_VERSION = 2; // keep in sync with FormCatalog.SUPPORTED_SCHEMA_VERSION (Android)

const PROFILES_PATH = "form-profiles.json";
const MANIFEST_PATH = "manifest.json";

function repoApi(env, path) {
  const repo = env.GITHUB_REPO; // "owner/name"
  if (!repo) throw new Error("GITHUB_REPO env is not set");
  return `https://api.github.com/repos/${repo}/contents/${path}`;
}

function ghHeaders(env) {
  if (!env.GITHUB_TOKEN) throw new Error("GITHUB_TOKEN env is not set");
  return {
    Authorization: `Bearer ${env.GITHUB_TOKEN}`,
    Accept: "application/vnd.github+json",
    "X-GitHub-Api-Version": "2022-11-28",
    "User-Agent": "autoform-panel"
  };
}

async function sha256Hex(text) {
  const bytes = new TextEncoder().encode(text);
  const digest = await crypto.subtle.digest("SHA-256", bytes);
  return [...new Uint8Array(digest)].map((b) => b.toString(16).padStart(2, "0")).join("");
}

function b64encode(text) {
  const bytes = new TextEncoder().encode(text);
  let binary = "";
  for (const b of bytes) binary += String.fromCharCode(b);
  return btoa(binary);
}

function b64decode(b64) {
  const binary = atob((b64 || "").replace(/\n/g, ""));
  const bytes = new Uint8Array(binary.length);
  for (let i = 0; i < binary.length; i++) bytes[i] = binary.charCodeAt(i);
  return new TextDecoder().decode(bytes);
}

/** Returns { text, sha } for a repo file, or { text: null, sha: null } when it doesn't exist yet. */
async function getFile(env, path) {
  const res = await fetch(repoApi(env, path), { headers: ghHeaders(env) });
  if (res.status === 404) return { text: null, sha: null };
  if (!res.ok) throw new Error(`GitHub GET ${path} failed: ${res.status} ${await res.text()}`);
  const json = await res.json();
  return { text: b64decode(json.content), sha: json.sha };
}

async function putFile(env, path, text, message, sha) {
  const body = { message, content: b64encode(text) };
  if (sha) body.sha = sha;
  const res = await fetch(repoApi(env, path), {
    method: "PUT",
    headers: { ...ghHeaders(env), "Content-Type": "application/json" },
    body: JSON.stringify(body)
  });
  if (!res.ok) throw new Error(`GitHub PUT ${path} failed: ${res.status} ${await res.text()}`);
  return res.json();
}

/** Validated profiles -> bump version, write form-profiles.json + manifest.json.
 *  `settings` (optional) is a global sibling object stored alongside `profiles` in the same file;
 *  when given it is MERGED over the previously stored settings (incoming keys win). */
export async function publishCatalog(env, profiles, { publicUrl, notes = "", minAppVersionCode = 0, settings = undefined } = {}) {
  const existingProfiles = await getFile(env, PROFILES_PATH);
  let prevVersion = 0;
  let prevSettings = undefined;
  if (existingProfiles.text) {
    try {
      const prev = JSON.parse(existingProfiles.text);
      prevVersion = Number(prev.version) || 0;
      if (prev.settings && typeof prev.settings === "object" && !Array.isArray(prev.settings)) prevSettings = prev.settings;
    } catch {
      prevVersion = 0;
    }
  }
  const version = prevVersion + 1;
  // Merge incoming settings over previous (incoming keys win); omit the field entirely if neither exists.
  let mergedSettings = undefined;
  if (settings !== undefined || prevSettings !== undefined) {
    mergedSettings = { ...(prevSettings || {}), ...(settings || {}) };
  }
  const profilesObj = mergedSettings !== undefined
    ? { schemaVersion: SCHEMA_VERSION, version, settings: mergedSettings, profiles }
    : { schemaVersion: SCHEMA_VERSION, version, profiles };
  const profilesJson = JSON.stringify(profilesObj, null, 2) + "\n";
  const sha256 = await sha256Hex(profilesJson);
  const profilesUrl = `${(publicUrl || "").replace(/\/+$/, "")}/catalog/form-profiles.json`;
  const manifest = {
    schemaVersion: SCHEMA_VERSION,
    version,
    sha256,
    profilesUrl,
    minAppVersionCode,
    updatedAt: new Date().toISOString(),
    notes
  };
  const manifestJson = JSON.stringify(manifest, null, 2) + "\n";

  await putFile(env, PROFILES_PATH, profilesJson, `catalog: publish v${version} (${profiles.length} profiles)`, existingProfiles.sha);
  const existingManifest = await getFile(env, MANIFEST_PATH);
  await putFile(env, MANIFEST_PATH, manifestJson, `catalog: manifest v${version}`, existingManifest.sha);
  return { version, sha256, profilesUrl };
}

/** Current published profiles array + global settings (empty when the catalog is uninitialized). */
export async function readProfiles(env) {
  const file = await getFile(env, PROFILES_PATH);
  if (!file.text) return { version: 0, profiles: [], settings: {} };
  const json = JSON.parse(file.text);
  const settings = json.settings && typeof json.settings === "object" && !Array.isArray(json.settings) ? json.settings : {};
  return { version: Number(json.version) || 0, profiles: Array.isArray(json.profiles) ? json.profiles : [], settings };
}

/** Raw stored file text for the app's /catalog/* fetches. */
export async function readCatalogFile(env, which) {
  const path = which === "manifest" ? MANIFEST_PATH : PROFILES_PATH;
  const file = await getFile(env, path);
  return file.text;
}
