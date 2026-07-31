#!/usr/bin/env node

import { pathToFileURL } from "node:url";

import {
  panelRuntimeFromVersionMetadata,
  validPanelSourceCommit
} from "../panel/src/panel-runtime.js";

const PANEL_RUNTIME_KEYS = Object.freeze([
  "provenance",
  "sourceCommit",
  "version",
  "versionCreatedAt",
  "workerVersionId"
]);
const DEFAULT_TIMEOUT_MS = 15_000;
const MAX_RESPONSE_BYTES = 1024 * 1024;

function isPlainObject(value) {
  if (value === null || typeof value !== "object" || Array.isArray(value)) return false;
  const prototype = Object.getPrototypeOf(value);
  return prototype === Object.prototype || prototype === null;
}

function hasExactKeys(value, expected) {
  if (!isPlainObject(value)) return false;
  const actual = Object.keys(value).sort();
  const wanted = [...expected].sort();
  return actual.length === wanted.length
    && actual.every((key, index) => key === wanted[index]);
}

function provenanceUrl(panelUrl) {
  let parsed;
  try {
    parsed = new URL(panelUrl);
  } catch {
    throw new Error("Panel URL is invalid");
  }
  if (parsed.protocol !== "https:" || parsed.username || parsed.password
      || parsed.search || parsed.hash || (parsed.pathname !== "/" && parsed.pathname !== "")) {
    throw new Error("Panel URL must be one HTTPS origin without credentials, path, query, or fragment");
  }
  parsed.pathname = "/api/runtime-provenance";
  return parsed.href;
}

function validateReadKey(value) {
  if (value === undefined || value === "") return "";
  if (typeof value !== "string" || value.length > 4096 || /[\u0000-\u001f\u007f]/u.test(value)) {
    throw new Error("Panel read key is invalid");
  }
  return value;
}

export function validatePanelRuntimeContract(value) {
  if (!hasExactKeys(value, PANEL_RUNTIME_KEYS)
      || value.version !== 1 || value.provenance !== "cloudflare_version_tag") {
    throw new Error("live Panel runtime provenance is unavailable or malformed");
  }
  const normalized = panelRuntimeFromVersionMetadata({
    id: value.workerVersionId,
    tag: `autoform-source-${value.sourceCommit}`,
    timestamp: value.versionCreatedAt
  });
  if (normalized.version !== 1
      || normalized.workerVersionId !== value.workerVersionId
      || normalized.sourceCommit !== value.sourceCommit
      || normalized.versionCreatedAt !== value.versionCreatedAt) {
    throw new Error("live Panel runtime provenance is unavailable or malformed");
  }
  return normalized;
}

export function verificationSummary(runtime) {
  return {
    ok: true,
    panelRuntimeVersion: runtime.version,
    provenance: runtime.provenance,
    tagMatchesExpected: true,
    sourceCommit: runtime.sourceCommit,
    workerVersionId: runtime.workerVersionId,
    versionCreatedAt: runtime.versionCreatedAt
  };
}

async function readBoundedUtf8Body(response) {
  let reader;
  try {
    reader = response?.body?.getReader?.();
  } catch {
    throw new Error("live Panel provenance response body is invalid");
  }
  if (!reader || typeof reader.read !== "function") {
    throw new Error("live Panel provenance response body is invalid");
  }
  const chunks = [];
  let total = 0;
  try {
    while (true) {
      let part;
      try {
        part = await reader.read();
      } catch {
        throw new Error("live Panel provenance response could not be read");
      }
      if (!part || typeof part.done !== "boolean") {
        throw new Error("live Panel provenance response body is invalid");
      }
      if (part.done) break;
      if (!(part.value instanceof Uint8Array)) {
        throw new Error("live Panel provenance response body is invalid");
      }
      total += part.value.byteLength;
      if (total > MAX_RESPONSE_BYTES) {
        try {
          await reader.cancel();
        } catch {}
        throw new Error("live Panel provenance response is too large");
      }
      chunks.push(part.value);
    }
  } finally {
    try {
      reader.releaseLock();
    } catch {}
  }
  const bytes = new Uint8Array(total);
  let offset = 0;
  for (const chunk of chunks) {
    bytes.set(chunk, offset);
    offset += chunk.byteLength;
  }
  try {
    return new TextDecoder("utf-8", { fatal: true }).decode(bytes);
  } catch {
    throw new Error("live Panel provenance response is not valid UTF-8");
  }
}

async function requestAndVerify({
  target,
  headers,
  fetchImpl,
  signal,
  expectedCommit
}) {
  let response;
  try {
    response = await fetchImpl(target, {
      method: "GET",
      headers,
      redirect: "error",
      signal
    });
  } catch {
    throw new Error("live Panel provenance request failed");
  }
  let status;
  try {
    status = response?.status;
  } catch {
    throw new Error("live Panel provenance response is invalid");
  }
  if (typeof status !== "number" || !Number.isInteger(status)) {
    throw new Error("live Panel provenance response is invalid");
  }
  if (status !== 200) {
    throw new Error(`live Panel provenance request returned HTTP ${status}`);
  }
  let contentType;
  let contentLength;
  try {
    contentType = response.headers?.get?.("Content-Type") || "";
    contentLength = response.headers?.get?.("Content-Length");
  } catch {
    throw new Error("live Panel provenance response headers are invalid");
  }
  if (typeof contentType !== "string"
      || !/^application\/json(?:;|$)/iu.test(contentType.trim())) {
    throw new Error("live Panel provenance response is not JSON");
  }
  if (contentLength !== null && contentLength !== undefined && contentLength !== "") {
    if (typeof contentLength !== "string" || !/^\d+$/u.test(contentLength)) {
      throw new Error("live Panel provenance response headers are invalid");
    }
    if (Number(contentLength) > MAX_RESPONSE_BYTES) {
      throw new Error("live Panel provenance response is too large");
    }
  }
  const text = await readBoundedUtf8Body(response);
  let body;
  try {
    body = JSON.parse(text);
  } catch {
    throw new Error("live Panel provenance response is not valid JSON");
  }
  if (!isPlainObject(body)) {
    throw new Error("live Panel provenance response is not a JSON object");
  }
  const runtime = validatePanelRuntimeContract(body.panelRuntime);
  if (runtime.sourceCommit !== expectedCommit) {
    throw new Error("live Panel source tag does not match the expected commit");
  }
  return verificationSummary(runtime);
}

export async function verifyLivePanelProvenance({
  panelUrl,
  expectedCommit,
  readKey = "",
  fetchImpl = globalThis.fetch,
  timeoutMs = DEFAULT_TIMEOUT_MS
}) {
  if (!validPanelSourceCommit(expectedCommit)) {
    throw new Error("expected commit must be one lowercase full 40-character Git commit");
  }
  if (!Number.isInteger(timeoutMs) || timeoutMs < 100 || timeoutMs > 60_000) {
    throw new Error("timeout must be an integer from 100 to 60000 milliseconds");
  }
  if (typeof fetchImpl !== "function") throw new Error("fetch implementation is unavailable");
  const target = provenanceUrl(panelUrl);
  const key = validateReadKey(readKey);
  const headers = { Accept: "application/json", "Cache-Control": "no-store" };
  if (key) headers.Authorization = `Bearer ${key}`;
  const controller = new AbortController();
  const timeout = setTimeout(() => controller.abort(), timeoutMs);
  try {
    return await requestAndVerify({
      target,
      headers,
      fetchImpl,
      signal: controller.signal,
      expectedCommit
    });
  } finally {
    clearTimeout(timeout);
  }
}

function parseArgs(argv) {
  const args = {};
  for (let index = 0; index < argv.length; index++) {
    const token = argv[index];
    if (!["--url", "--expected-commit", "--timeout-ms"].includes(token)) {
      throw new Error("usage: verify-panel-runtime-provenance --url <https-origin> --expected-commit <full-commit> [--timeout-ms <milliseconds>]");
    }
    if (Object.prototype.hasOwnProperty.call(args, token)) {
      throw new Error(`duplicate ${token}`);
    }
    const value = argv[++index];
    if (value === undefined || value.startsWith("--")) throw new Error(`missing value for ${token}`);
    args[token] = value;
  }
  return args;
}

async function main(argv, env = process.env) {
  const args = parseArgs(argv);
  const panelUrl = args["--url"] || env.AUTOFORM_PANEL_URL;
  const expectedCommit = args["--expected-commit"];
  if (!panelUrl || !expectedCommit) {
    throw new Error("both --url and --expected-commit are required");
  }
  const timeoutMs = args["--timeout-ms"] === undefined
    ? DEFAULT_TIMEOUT_MS : Number(args["--timeout-ms"]);
  const result = await verifyLivePanelProvenance({
    panelUrl,
    expectedCommit,
    readKey: env.AUTOFORM_PANEL_READ_KEY || "",
    timeoutMs
  });
  process.stdout.write(`${JSON.stringify(result)}\n`);
}

if (import.meta.url === pathToFileURL(process.argv[1] || "").href) {
  try {
    await main(process.argv.slice(2));
  } catch (error) {
    process.stderr.write(`Panel runtime provenance verification failed: ${error.message}\n`);
    process.exitCode = 1;
  }
}
