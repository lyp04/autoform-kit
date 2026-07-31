#!/usr/bin/env node

import assert from "node:assert/strict";

import {
  validatePanelRuntimeContract,
  verificationSummary,
  verifyLivePanelProvenance
} from "./verify-panel-runtime-provenance.mjs";

const SOURCE_COMMIT = "a".repeat(40);
const OTHER_COMMIT = "b".repeat(40);
const PANEL_URL = "https://panel.test.invalid";
const READ_KEY = "sample-private-read-key";
const RUNTIME = Object.freeze({
  version: 1,
  provenance: "cloudflare_version_tag",
  workerVersionId: "01234567-89ab-cdef-0123-456789abcdef",
  sourceCommit: SOURCE_COMMIT,
  versionCreatedAt: "2030-04-05T06:07:08.123Z"
});

function jsonResponse(body, init = {}) {
  return new Response(JSON.stringify(body), {
    status: init.status || 200,
    headers: {
      "Content-Type": "application/json; charset=utf-8",
      ...(init.headers || {})
    }
  });
}

let observedRequest;
const success = await verifyLivePanelProvenance({
  panelUrl: PANEL_URL,
  expectedCommit: SOURCE_COMMIT,
  readKey: READ_KEY,
  fetchImpl: async (url, options) => {
    observedRequest = { url, options };
    return jsonResponse({
      catalogVersion: 7,
      backendApiBase: "https://business-value.test.invalid",
      panelRuntime: RUNTIME
    });
  }
});
assert.deepEqual(success, verificationSummary(RUNTIME));
assert.equal(observedRequest.url, `${PANEL_URL}/api/runtime-provenance`);
assert.equal(observedRequest.options.method, "GET");
assert.equal(observedRequest.options.redirect, "error");
assert.equal(observedRequest.options.headers.Authorization, `Bearer ${READ_KEY}`);
assert.equal(JSON.stringify(success).includes(PANEL_URL), false);
assert.equal(JSON.stringify(success).includes(READ_KEY), false);
assert.equal(JSON.stringify(success).includes("business-value.test.invalid"), false);
assert.deepEqual(validatePanelRuntimeContract(RUNTIME), RUNTIME);

await assert.rejects(() => verifyLivePanelProvenance({
  panelUrl: PANEL_URL,
  expectedCommit: OTHER_COMMIT,
  readKey: READ_KEY,
  fetchImpl: async () => jsonResponse({ panelRuntime: RUNTIME })
}), /source tag does not match the expected commit/u);

await assert.rejects(() => verifyLivePanelProvenance({
  panelUrl: PANEL_URL,
  expectedCommit: SOURCE_COMMIT,
  fetchImpl: async () => jsonResponse({
    panelRuntime: { version: 0, provenance: "unavailable" }
  })
}), /provenance is unavailable or malformed/u);

assert.throws(() => validatePanelRuntimeContract({
  ...RUNTIME,
  unexpected: "must-fail-closed"
}), /provenance is unavailable or malformed/u);

await assert.rejects(() => verifyLivePanelProvenance({
  panelUrl: PANEL_URL,
  expectedCommit: SOURCE_COMMIT,
  readKey: READ_KEY,
  fetchImpl: async () => {
    throw new Error(`unsafe details ${PANEL_URL} ${READ_KEY}`);
  }
}), (error) => {
  assert.equal(error.message, "live Panel provenance request failed");
  assert.equal(error.message.includes(PANEL_URL), false);
  assert.equal(error.message.includes(READ_KEY), false);
  return true;
});

await assert.rejects(() => verifyLivePanelProvenance({
  panelUrl: PANEL_URL,
  expectedCommit: SOURCE_COMMIT,
  readKey: READ_KEY,
  fetchImpl: async () => ({
    status: 200,
    headers: {
      get() {
        throw new Error(`unsafe details ${PANEL_URL} ${READ_KEY}`);
      }
    }
  })
}), (error) => {
  assert.equal(error.message, "live Panel provenance response headers are invalid");
  assert.equal(error.message.includes(PANEL_URL), false);
  assert.equal(error.message.includes(READ_KEY), false);
  return true;
});

await assert.rejects(() => verifyLivePanelProvenance({
  panelUrl: PANEL_URL,
  expectedCommit: SOURCE_COMMIT,
  fetchImpl: async () => new Response(
    JSON.stringify({ panelRuntime: RUNTIME }), {
      status: 401,
      headers: { "Content-Type": "application/json" }
    })
}), /returned HTTP 401/u);

const slowStartedAt = Date.now();
await assert.rejects(() => verifyLivePanelProvenance({
  panelUrl: PANEL_URL,
  expectedCommit: SOURCE_COMMIT,
  timeoutMs: 100,
  fetchImpl: async (url, options) => new Response(new ReadableStream({
    start(controller) {
      options.signal.addEventListener("abort", () => {
        controller.error(new Error(`unsafe details ${url} ${READ_KEY}`));
      }, { once: true });
    }
  }), {
    status: 200,
    headers: { "Content-Type": "application/json" }
  })
}), /provenance response could not be read/u);
assert.ok(Date.now() - slowStartedAt < 1000);

let oversizedCancelled = false;
let oversizedReads = 0;
await assert.rejects(() => verifyLivePanelProvenance({
  panelUrl: PANEL_URL,
  expectedCommit: SOURCE_COMMIT,
  fetchImpl: async () => new Response(new ReadableStream({
    pull(controller) {
      oversizedReads++;
      controller.enqueue(new Uint8Array(600 * 1024));
    },
    cancel() {
      oversizedCancelled = true;
    }
  }, { highWaterMark: 0 }), {
    status: 200,
    headers: { "Content-Type": "application/json" }
  })
}), /provenance response is too large/u);
assert.equal(oversizedReads, 2);
assert.equal(oversizedCancelled, true);

process.stdout.write("Panel runtime provenance self-test: passed\n");
