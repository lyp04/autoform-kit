#!/usr/bin/env node

import assert from "node:assert/strict";
import fs from "node:fs";
import path from "node:path";

const root = path.resolve(path.dirname(new URL(import.meta.url).pathname), "..");
const read = (name) => fs.readFileSync(path.join(root, name), "utf8");

const main = read("app/src/com/autoformkit/app/MainActivity.java");
const catalog = read("app/src/com/autoformkit/app/FormCatalog.java");
const operation = read("app/src/com/autoformkit/app/OperationBindingRules.java");
const pending = read("app/src/com/autoformkit/app/PendingFormOperationRules.java");
const operationTest = read("app/test/com/autoformkit/app/OperationBindingRulesTest.java");
const pendingTest = read("app/test/com/autoformkit/app/PendingFormOperationRulesTest.java");

function methodBody(source, signature, nextSignature) {
  const start = source.indexOf(signature);
  assert.notEqual(start, -1, `missing ${signature}`);
  const end = nextSignature ? source.indexOf(nextSignature, start + signature.length) : source.length;
  assert.notEqual(end, -1, `missing boundary after ${signature}`);
  return source.slice(start, end);
}

function assertBalancedJava(source, label) {
  const stack = [];
  let state = "code";
  for (let i = 0; i < source.length; i += 1) {
    const c = source[i];
    const n = source[i + 1];
    if (state === "line") {
      if (c === "\n") state = "code";
      continue;
    }
    if (state === "block") {
      if (c === "*" && n === "/") { state = "code"; i += 1; }
      continue;
    }
    if (state === "string" || state === "char") {
      if (c === "\\") { i += 1; continue; }
      if ((state === "string" && c === '"') || (state === "char" && c === "'")) state = "code";
      continue;
    }
    if (c === "/" && n === "/") { state = "line"; i += 1; continue; }
    if (c === "/" && n === "*") { state = "block"; i += 1; continue; }
    if (c === '"') { state = "string"; continue; }
    if (c === "'") { state = "char"; continue; }
    if ("({[".includes(c)) stack.push(c);
    if (")}]".includes(c)) {
      const open = stack.pop();
      const expected = { ")": "(", "}": "{", "]": "[" }[c];
      assert.equal(open, expected, `${label}: unbalanced ${c} at ${i}`);
    }
  }
  assert.equal(state === "code" || state === "line", true, `${label}: unterminated literal/comment`);
  assert.deepEqual(stack, [], `${label}: unclosed delimiter`);
}

// Pure rules must stay deployment-neutral and serialize no raw session material.
assert.doesNotMatch(operation + pending, /import android\./);
assert.match(operation, /sessionFingerprint\(webFingerprint, token\)/);
assert.doesNotMatch(operation, /\.put\("token"/);
assert.match(pending, /value\.length\(\) != 15/);
assert.match(pending, /operationId\.equals\(operationBinding\.nonce\)/);

// Endpoint reads are pair/session scoped. The unscoped key remains write-only for rollback.
assert.doesNotMatch(main, /getString\("recognizeTextUrl"/);
assert.match(main, /putString\("recognizeTextUrl", value\)/);
assert.match(main, /OperationBindingRules\.parseBoundValue/);

// Every compound nonce-map access is explicitly synchronized.
const begin = methodBody(main, "private OperationBindingRules.Binding beginBoundOperation", "private boolean boundOperationMatches");
const match = methodBody(main, "private boolean boundOperationMatches", "private void requireBoundOperation");
assert.match(begin, /synchronized \(activeOperationNonces\)[\s\S]*activeOperationNonces\.put/);
assert.match(match, /synchronized \(activeOperationNonces\)[\s\S]*activeOperationNonces\.get/);

// New targets are durable before launch and old index/path mirrors are never restored.
const persist = methodBody(main, "private boolean persistPendingMainFormTarget", "private PendingFormOperationRules.Target preparePendingMainFormTarget");
const prepare = methodBody(main, "private PendingFormOperationRules.Target preparePendingMainFormTarget", "private void restorePendingMainFormTarget");
assert.match(persist, /PENDING_MAIN_FORM_OPERATION_KEY[\s\S]*\.commit\(\)/);
assert.match(prepare, /saveDraft\(true\)[\s\S]*PendingFormOperationRules\.create[\s\S]*persistPendingMainFormTarget/);
assert.doesNotMatch(main, /draftForPhotoResult|draftForUnitSequence/);
assert.doesNotMatch(main, /get(?:Int|String)\(PENDING_(?:PHOTO|OCR_PHOTO|RESCAN)/);
// A result that cannot prove the exact persisted operation must leave the evidence locked; role
// alone is not an operation identity and must never be used as a cleanup fallback.
assert.doesNotMatch(main, /abandonReadablePendingOcrResult/);
assert.match(main, /unitBySequence\(target\.unitSequence\)/);
assert.match(main, /target\.outputPath\.equals\(path\)/);

// Profile/Panel replacement is locked for any pending bytes or active OCR/user-info worker.
assert.match(main, /getAll\(\)\.containsKey\(PENDING_MAIN_FORM_OPERATION_KEY\)/);
assert.match(main, /if \(!changing && profileSelectionReady && !restoringDraft\) return;/);
assert.match(main, /safeToInstallBoundPanelSnapshot[\s\S]*mainFormBoundWorkerActive\(\)[\s\S]*hasPendingMainFormOperation\(\)/);

// The tracked seed gets a stable local-only identity and empty-session round trips stay exact.
assert.match(catalog, /loadBundledPreviewSnapshot[\s\S]*semanticSha256\(root\)/);
assert.match(catalog, /settings\.optBoolean\("sampleCatalog", false\)/);
assert.match(main, /savedToken\(\)\.isEmpty\(\) && !localSamplePreviewEnabled\(\)/);
assert.match(main, /AppConfig\.panelBase\(this\)\.isEmpty\(\)[\s\S]*activePanelPairSha256\.matches/);
assert.match(operationTest, /emptySessionIsStillAnExactSessionIdentityForLocalPreview/);
assert.match(pendingTest, /localPreviewCameraTargetRoundTripsWithExactEmptySession/);

// OCR terminal/stale paths include exact target cleanup; chooser system dismiss cannot strand it.
const recognize = methodBody(main, "private void recognizeSnFromPhoto(boolean baseSn, File photoFile, boolean autoCapture,", "private void ensureOcrUrlThenRecognize(boolean baseSn, File photoFile)");
assert.match(recognize, /if \(!ensureOcrConfigured\(ocrWorkflow, adapterSnapshot\)\)[\s\S]*clearPendingTargetAfterOcr/);
assert.match(recognize, /RemoteSideEffectSafetyRules\.executeOcr\([\s\S]*apiSnapshot\.recognizeText\(/);
assert.ok((recognize.match(/clearPendingTargetAfterOcr\(pendingTarget\)/g) || []).length >= 7);
const chooser = methodBody(main, "private void showOcrCandidates", "private void applyRecognizedSn(boolean baseSn, String candidate)");
assert.match(chooser, /setOnCancelListener[\s\S]*clearPendingTargetAfterOcr/);
assert.match(chooser, /setOnDismissListener[\s\S]*clearPendingTargetAfterOcr/);

for (const [label, source] of [
  ["MainActivity", main], ["FormCatalog", catalog],
  ["OperationBindingRules", operation], ["PendingFormOperationRules", pending],
]) assertBalancedJava(source, label);

console.log("auth/camera binding static self-test: OK");
