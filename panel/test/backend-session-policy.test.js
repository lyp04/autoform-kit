import test from "node:test";
import assert from "node:assert/strict";

import { verifyToken } from "../src/backend.js";

test("worker accepts only explicitly configured non-success session proof codes", async () => {
  const previousFetch = globalThis.fetch;
  const seenFingerprints = [];
  globalThis.fetch = async (_url, options) => {
    seenFingerprints.push(options.headers["X-Browser-Fingerprint"]);
    return new Response(JSON.stringify({
      code: "SESSION_PROOF",
      message: "verification performed from a different client"
    }), { status: 200, headers: { "Content-Type": "application/json" } });
  };

  const token = "sample-token-with-enough-length";
  try {
    await assert.rejects(
      verifyToken({ BACKEND_API_BASE: "https://backend.example.com/api" }, token, "sample-fp"),
      /backend token is not valid/);

    const result = await verifyToken({
      BACKEND_API_BASE: "https://backend.example.com/api",
      BACKEND_SESSION_PROOF_CODES: '["SESSION_PROOF"]'
    }, token, "sample-fp");
    assert.equal(result.sessionProof, true);
    assert.deepEqual(seenFingerprints, ["sample-fp", "sample-fp"]);
  } finally {
    globalThis.fetch = previousFetch;
  }
});
