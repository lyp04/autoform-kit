#!/usr/bin/env node

import assert from "node:assert/strict";
import fs from "node:fs";
import path from "node:path";
import process from "node:process";
import { fileURLToPath } from "node:url";

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");
const read = (name) => fs.readFileSync(path.join(root, name), "utf8");
const main = read("app/src/com/autoformkit/app/MainActivity.java");
const policy = read("app/src/com/autoformkit/app/SubmissionPolicyRules.java");
const remoteGate = read("app/src/com/autoformkit/app/RemoteSideEffectGate.java");
const update = read("app/src/com/autoformkit/app/UpdateManager.java");

function slice(source, startMarker, endMarker) {
  const start = source.indexOf(startMarker);
  const end = source.indexOf(endMarker, start + startMarker.length);
  assert.ok(start >= 0, `missing ${startMarker}`);
  assert.ok(end > start, `missing end ${endMarker}`);
  return source.slice(start, end);
}

assert.match(main,
  /private static final boolean DURABLE_UPLOAD_REPLAY_BARRIER_ENABLED = false;/,
  "image uploads must remain replayable");

const begin = slice(main, "private boolean beginUploadReplayBarrier(",
  "/** Clear only the exact in-memory operation");
assert.ok(begin.indexOf("if (!DURABLE_UPLOAD_REPLAY_BARRIER_ENABLED)")
  < begin.indexOf("synchronized (UpdateInstallRules.HANDOFF_LOCK)"));
assert.match(begin, /discardDisabledUploadReplayBarrier\(\);[\s\S]{0,80}return true;/,
  "a new image upload must not create a durable production lock");

const clear = slice(main, "private boolean clearUploadReplayBarrier(",
  "private boolean uploadReplayBarrierMatches(");
assert.match(clear,
  /if \(!DURABLE_UPLOAD_REPLAY_BARRIER_ENABLED\)[\s\S]{0,180}return expected != null;/,
  "disabled upload cleanup must be idempotent");

const blocking = slice(main,
  "private UploadReplayBarrier.RestoreResult blockingUploadReplayBarrier(",
  "/** Best-effort migration for devices which were stranded by the removed upload-only lock. */");
assert.match(blocking,
  /if \(!DURABLE_UPLOAD_REPLAY_BARRIER_ENABLED\)[\s\S]{0,180}return null;/,
  "an old upload-only slot must no longer block form, Panel, or update access");

const discard = slice(main, "private void discardDisabledUploadReplayBarrier()",
  "/**\n     * A positive, durably restored POST receipt");
assert.match(discard, /remove\(UPLOAD_REPLAY_BARRIER_KEY\)\.commit\(\)/,
  "older stranded upload-only records must be removed on sight");

assert.match(policy,
  /boolean canRetryWholeUnit\(\)\s*\{\s*return true;/,
  "the bounded whole-unit network retry must allow image re-upload");

const mainPost = slice(main, "private JournaledSubmissionResponse postMainSubmissionOnce(",
  "private void confirmMainSubmissionRejected(");
for (const marker of [
  "AlternateSubmissionAttempt.prepare(",
  "writeMainSubmissionAttempt(attempt)",
  "attempt.beginPosting(key)",
  "postEndpointJsonExact("
]) {
  assert.ok(mainPost.includes(marker), `main final POST must retain ${marker}`);
}
assert.ok(mainPost.indexOf("writeMainSubmissionAttempt(attempt)")
  < mainPost.indexOf("postEndpointJsonExact("),
  "the final form POST must still be journaled before the socket opens");

const alternate = slice(main, "private void submitAlternateEntry()",
  "private JSONObject resolveAlternateEntryDynamicOverrides(");
assert.ok(alternate.indexOf("writeAlternateSubmissionAttempt(posting)")
  < alternate.indexOf("postEndpointJsonExact("),
  "independent-entry POST must still be journaled before the socket opens");
assert.match(alternate, /posting\.markUncertain\(attemptKey\)/,
  "an uncertain independent-entry POST must remain non-replayable");

for (const key of [
  "MAIN_SUBMISSION_ATTEMPT_PREFIX",
  "PREVIOUS_STEP_SUBMISSION_ATTEMPT_PREFIX",
  "ALTERNATE_SUBMISSION_ATTEMPT_PREFIX",
  "REPRINT_ATTEMPTS_KEY"
]) {
  assert.ok(remoteGate.includes(key), `remote-operation gate must retain ${key}`);
}
assert.match(remoteGate, /activeWorkerCount > 0/,
  "an in-flight image upload must still exclude an installer handoff");
assert.match(update, /remoteSideEffectBlockingStatePresent\(activity\)/,
  "App install must still wait for a currently running remote worker");

process.stdout.write("replayable image upload self-test: passed\n");
