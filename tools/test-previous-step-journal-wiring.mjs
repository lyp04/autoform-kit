#!/usr/bin/env node

import assert from "node:assert/strict";
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");
const main = fs.readFileSync(path.join(root,
  "app/src/com/autoformkit/app/MainActivity.java"), "utf8");
const journal = fs.readFileSync(path.join(root,
  "app/src/com/autoformkit/app/PreviousStepSubmissionAttempt.java"), "utf8");

function methodSlice(source, startMarker, endMarker) {
  const start = source.indexOf(startMarker);
  const end = source.indexOf(endMarker, start + startMarker.length);
  assert.ok(start >= 0, `missing ${startMarker}`);
  assert.ok(end > start, `missing end ${endMarker}`);
  return source.slice(start, end);
}

assert.match(journal, /connectionNamespace\.matches\("\[0-9a-f\]\{20\}"\)/);
for (const field of [
  "catalogVersion", "profileId", "draftSemanticsSha256", "unitSequence", "serial",
  "unitSnapshotSha256", "recipeChainSha256", "recipeCount", "completedRecipeCount",
  "dynamicResolvedSemanticsSha256", "recipe", "payloadSha256", "attemptNumber",
  "operationId"
]) {
  assert.ok(journal.includes(`\"${field}\"`), `journal must persist ${field}`);
}
assert.match(journal,
  /requested\.attemptNumber != retained\.key\.attemptNumber \+ 1/);
assert.match(journal, /restoredState == State\.POSTING/);
assert.match(journal, /State\.UNCERTAIN/);

const post = methodSlice(main, "private void submitAutoStepPayload(",
  "private JSONArray checkDuplicate(");
const preparedWrite = post.indexOf("writePreviousStepSubmissionAttempt(prepared)");
const postingTransition = post.indexOf("prepared.beginPosting(key)");
const postingWrite = post.indexOf("writePreviousStepSubmissionAttempt(posting)");
const networkPost = post.indexOf("api.postEndpointJsonExact(");
assert.ok(preparedWrite >= 0 && postingTransition > preparedWrite
  && postingWrite > postingTransition && networkPost > postingWrite,
"PREPARED and POSTING must be durably written before exact network bytes");
assert.ok(!post.includes("api.postEndpointJson("),
  "previous-step recipe POST must not reserialize a JSONObject in the transport");
assert.match(post, /AlternateSubmissionAttempt\.payloadSha256\(exactRequestBody\)/);
assert.match(post,
  /policy\.recipeResponseDisposition\(response, api\.endpoints\.response\)/);
assert.match(post,
  /RecipeResponseDisposition\.ALREADY_EXISTS_ACKNOWLEDGED/);
assert.match(post,
  /RecipeResponseDisposition\.RETRYABLE_NOT_WRITTEN/);
assert.doesNotMatch(post, /policy\.isAlreadyExistsResponse\(/,
  "legacy substring matches must not acknowledge a previous-step POST");
assert.doesNotMatch(post, /policy\.isRetryableResponse\(/,
  "legacy substring matches must not authorize a previous-step retry");
assert.doesNotMatch(post, /response\.toString\(\)/,
  "side-effect classifiers must never inspect the whole response envelope");
assert.match(post, /posting\.markUncertain\(key\)/);
assert.match(post, /throw new PreviousStepSubmissionOutcomeUncertainException\(\)/);
assert.match(post,
  /int effectiveRecipeMaxAttempts = policy\.hasRecipeRetryableNotWrittenRules\(\)\s*\? workflow\.previousStepRecipeMaxAttempts : 1;/,
  "recipe retries must collapse to one attempt without independent not-written evidence");
assert.match(post,
  /attempt <= effectiveRecipeMaxAttempts/);
assert.doesNotMatch(post, /attemptsInThisCall/,
  "restoring a rejected recipe must not grant another retry window");

const retryClassifier = methodSlice(main,
  "static boolean isTransientApiNetworkError(Throwable exc)",
  "static boolean isDnsResolveError(Throwable exc)");
assert.match(retryClassifier,
  /PreviousStepSubmissionOutcomeUncertainException/);
assert.match(retryClassifier, /PreviousStepLookupUnclassifiedException/);
assert.ok(retryClassifier.indexOf("PreviousStepSubmissionOutcomeUncertainException")
  < retryClassifier.indexOf("TransientHttpException"),
"no-replay exception must be excluded before transient classification");
assert.ok(retryClassifier.indexOf("PreviousStepLookupUnclassifiedException")
  < retryClassifier.indexOf("String message = current.getMessage()"),
"unclassified lookup must be excluded before timeout/connection message matching");

const submitBatch = methodSlice(main, "private void submitBatch()",
  "private void showSubmitLoading(");
assert.match(submitBatch, /blockingPreviousStepSubmissionAttempt\(\)/);
assert.match(submitBatch, /showPreviousStepSubmissionBlock/);

const ensure = methodSlice(main, "private void ensurePreviousSteps(",
  "private void alignSnCaseToPreviousSteps(");
assert.ok(!ensure.includes("clearPreviousStepSubmissionAttempt()"),
  "verification GET must not retire the recipe receipt before final submission");
assert.match(ensure, /validateVerifiedPreviousStepSubmissionAttempt/);
const continuationAt = ensure.indexOf("retained.requiresRecipeContinuation()");
const initialLookupAt = ensure.indexOf("previousStepsResponse(api, unit, expectedDraftBinding)");
assert.ok(continuationAt >= 0 && initialLookupAt > continuationAt,
  "an incomplete durable recipe prefix must resume before a successful lookup can short-circuit it");
assert.ok(ensure.indexOf("runPreviousStepRecipesAndVerify(", continuationAt)
    < initialLookupAt,
  "incomplete recipe continuation must run and verify before the ordinary existence lookup");
const lookupClassifierAt = ensure.indexOf("requireConfiguredPreviousStepMissing(api, body)");
const autoCreateGateAt = ensure.indexOf("canAutoCreatePreviousSteps(unit, workflow)");
const ordinaryRecipeAt = ensure.indexOf("runPreviousStepRecipesAndVerify(", autoCreateGateAt);
assert.ok(lookupClassifierAt > initialLookupAt
    && autoCreateGateAt > lookupClassifierAt
    && ordinaryRecipeAt > autoCreateGateAt,
  "an ordinary recipe POST must be unreachable until the existence response is classified missing");
assert.equal((ensure.match(/profileWorkflow\(\)/g) || []).length, 0,
  "lookup, correction, case policy and create decision must share one workflow snapshot");
const capabilityGateAt = ensure.indexOf(
  "requirePreviousStepSideEffectCapability(api, workflow)");
assert.ok(capabilityGateAt >= 0 && capabilityGateAt < initialLookupAt,
  "manual and submit entry points must pass the side-effect capability gate before lookup");
assert.ok(!ensure.includes("canAutoCreatePreviousSteps(unit)"),
  "auto-create must not re-read the activity workflow");

const alignCase = methodSlice(main, "private void alignSnCaseToPreviousSteps(",
  "private String firstPreviousStepSn(");
assert.doesNotMatch(alignCase, /profileWorkflow\(\)/,
  "case alignment must use the captured workflow");
assert.ok(alignCase.indexOf("requireMainDraftRemoteBinding(")
    < alignCase.indexOf("unit.sn = stored"),
  "case alignment must revalidate the draft binding immediately before mutating SN");

const correction = methodSlice(main, "private boolean tryCorrectSnFromPreviousSteps(",
  "private boolean tryCorrectScannedSnFromPreviousSteps(");
assert.doesNotMatch(correction, /profileWorkflow\(\)/,
  "identifier correction must use the captured workflow");
assert.match(correction, /requireConfiguredPreviousStepMissing\(api, body\)/,
  "each non-success correction lookup must also be classified missing");

const correctionApply = methodSlice(main, "private void applySnCorrection(",
  "private boolean applySnCorrectionByPolicy(");
assert.ok(correctionApply.indexOf("requireMainDraftRemoteBinding(")
    < correctionApply.indexOf("unit.sn = candidate"),
  "identifier correction must revalidate the draft binding before mutating SN or saving");
assert.doesNotMatch(correctionApply, /profileWorkflow\(\)/,
  "identifier correction mutation must use the captured workflow");

const scanLookup = methodSlice(main, "private void checkScannedUnitPreviousSteps(",
  "private synchronized int recordScanPrecheckMissing(");
assert.equal((scanLookup.match(/profileWorkflow\(\)/g) || []).length, 1,
  "scan lookup and policy actions must share one workflow snapshot");
assert.ok(scanLookup.indexOf("requireConfiguredPreviousStepMissing(api, body)")
    < scanLookup.indexOf("recordScanPrecheckMissing(sn, workflow)"),
  "scan precheck must not classify an unknown business failure as missing");
assert.ok(scanLookup.indexOf("!workflow.operationalPoliciesExplicit")
    < scanLookup.indexOf("appendUnitLog(unit, t(\"checking_steps\"))"),
  "scan precheck must reject an unreviewed workflow before logging or local policy actions");
assert.ok(scanLookup.indexOf("requirePreviousStepLookupCapability(api, workflow)")
    < scanLookup.indexOf("previousStepsResponse("),
  "scan lookup must require the reviewed workflow and Panel-owned missing classifier");

const fastCorrection = methodSlice(main,
  "private boolean tryCorrectScannedSnFromPreviousSteps(",
  "private void applySnCorrection(");
assert.match(fastCorrection, /List<java\.util\.concurrent\.Future<PreviousStepSafetyRules\.CandidateOutcome>>/);
assert.match(fastCorrection, /PreviousStepSafetyRules\.awaitFirstFoundInPanelOrder\(/);
assert.doesNotMatch(fastCorrection, /CompletionService|ExecutorCompletionService|completion\.poll/,
  "concurrent correction results must never be decided in completion order");
assert.doesNotMatch(fastCorrection, /CandidateLookupTimeoutException/,
  "scan correction must let timeout escape instead of converting it to missing");
assert.doesNotMatch(fastCorrection, /sn_correction_fast_timeout/,
  "scan correction must not return a timeout as an all-missing result");

const recipeRunner = methodSlice(main,
  "private void runConfiguredPreviousStepRecipes(",
  "private List<String> uploadPreviousStepSource(");
assert.match(recipeRunner,
  /MainDraftSnapshotRules\.Binding expectedDraftBinding,\s*ProfileWorkflow workflow/,
  "recipe execution must receive the workflow snapshot used by verification");
assert.doesNotMatch(recipeRunner, /ProfileWorkflow workflow = profileWorkflow\(\)/,
  "recipe execution must not replace its captured workflow with the activity global");
assert.match(recipeRunner,
  /uploadPreviousStepSource\(\s*api, workflow, unit, sourceKey/);
assert.match(recipeRunner,
  /uploadPreviousStepSource\(\s*api, workflow, unit, binding\.source/);
const liveSnapshotAt = recipeRunner.indexOf("dynamicResolvedSnapshots.put(");
const liveDigestAt = recipeRunner.indexOf("dynamicResolvedSemanticsSha256 =");
const liveMismatchAt = recipeRunner.indexOf(
  "Dynamic previous-step semantics changed during journal recovery");
const firstUploadLoopAt = recipeRunner.indexOf("uploadedBySource =");
assert.ok(liveSnapshotAt >= 0 && liveDigestAt > liveSnapshotAt
  && liveMismatchAt > liveDigestAt && firstUploadLoopAt > liveMismatchAt,
"restored dynamic semantics must match before any upload or remaining recipe POST");
assert.match(recipeRunner, /\.put\("templateData"/);
assert.ok(recipeRunner.indexOf("requirePreviousStepAttemptMatchesPlan(")
    < firstUploadLoopAt,
  "the persisted current recipe identity must match the active plan before any upload");

const verifyRunner = methodSlice(main,
  "private JSONObject runPreviousStepRecipesAndVerify(",
  "private void alignSnCaseToPreviousSteps(");
assert.match(verifyRunner,
  /runConfiguredPreviousStepRecipes\(\s*api, unit, expectedDraftBinding, workflow\)/,
  "verification and recipe execution must share one workflow snapshot");
const sourceUpload = methodSlice(main,
  "private List<String> uploadPreviousStepSource(",
  "private List<String> previousStepSourcePaths(");
assert.match(sourceUpload, /Api api, ProfileWorkflow workflow, UnitRecord unit/);
assert.match(sourceUpload, /workflow\.workflowArtifactUploadName\(/);
assert.doesNotMatch(sourceUpload, /profileWorkflow\(\)/,
  "upload filenames must not come from a later global profile");

const complete = methodSlice(main, "private void completeMainSubmission(",
  "private void runWithSubmissionNetworkRetry(");
assert.match(complete, /ProfileWorkflow workflow/);
assert.doesNotMatch(complete, /profileWorkflow\(\)/,
  "terminal journal handling must retain the submitted workflow snapshot");
assert.ok(complete.indexOf("writeMainSubmissionAttempt(completed)")
  < complete.indexOf("clearPreviousStepSubmissionAttemptForResolvedChain"),
"previous-step receipt may clear only after final acknowledgement is durable");
assert.ok(complete.indexOf("persistExactPreviousStepTerminal(")
  < complete.indexOf("clearPreviousStepSubmissionAttemptForResolvedChain"),
"terminal main-unit state must be persisted before previous-step receipt cleanup");

const submitUnit = methodSlice(main, "private void submitUnit(",
  "private void ensurePreviousSteps(");
for (const terminalStatus of ["already_submitted", "duplicate_skipped"]) {
  const statusAt = submitUnit.indexOf(`unit.status = \"${terminalStatus}\"`);
  const durableAt = submitUnit.indexOf("persistExactPreviousStepTerminal(", statusAt);
  const cleanupAt = submitUnit.indexOf(
    "clearPreviousStepSubmissionAttemptForResolvedChain", statusAt);
  assert.ok(statusAt >= 0 && durableAt > statusAt && cleanupAt > durableAt,
    `${terminalStatus} must be durable before receipt cleanup`);
}
const terminalPersist = methodSlice(main,
  "private boolean persistExactPreviousStepTerminal(",
  "private void runConfiguredPreviousStepRecipes(");
assert.match(terminalPersist, /ProfileWorkflow workflow/);
assert.doesNotMatch(terminalPersist, /profileWorkflow\(\)/,
  "terminal persistence and read-back must share one workflow snapshot");
assert.ok(terminalPersist.indexOf("saveDraft(true)")
    < terminalPersist.indexOf("exactStoredPreviousStepTerminalUnit("),
  "terminal status must be durably written and read back before receipt cleanup");

const blockingPrevious = methodSlice(main,
  "blockingPreviousStepSubmissionAttempt()",
  "private void showPreviousStepSubmissionBlock(");
assert.match(blockingPrevious, /exactStoredPreviousStepTerminalUnit\(/);
assert.ok(blockingPrevious.indexOf("persistExactPreviousStepTerminal(")
    < blockingPrevious.indexOf("clearPreviousStepSubmissionAttemptForResolvedChain("),
  "active terminal recovery must make the terminal state durable before clearing its receipt");

const saveDraft = methodSlice(main, "private boolean saveDraft(boolean durable)",
  "// --- Manual queue backup");
assert.match(saveDraft, /previousStepReceiptPresent\s*=\s*\n?\s*hasStoredPreviousStepSubmissionAttempt\(\)/);
assert.match(saveDraft,
  /retainTerminalForRemoteRecovery\s*=\s*\n?\s*\(previousStepReceiptPresent \|\| uploadBarrierPresent\) && !units\.isEmpty\(\)/);

const pendingTarget = methodSlice(main,
  "private PendingFormOperationRules.Target preparePendingMainFormTarget(",
  "private void restorePendingMainFormTarget(");
assert.match(pendingTarget, /hasStoredPreviousStepSubmissionAttempt\(\)/);
const details = methodSlice(main, "private void showUnitDetails(",
  "private void renderDetailsPhotos(");
assert.match(details, /blockDraftMutationForPreviousStepJournal\(\)/);
const detailPhotos = methodSlice(main, "private void renderDetailsPhotos(",
  "private void addPhotoViewButton(");
assert.match(detailPhotos, /blockDraftMutationForPreviousStepJournal\(\)/);
const clearPrevious = methodSlice(main,
  "private boolean clearPreviousStepSubmissionAttemptForResolvedChain(",
  "private UnitRecord previousStepUnitFromStoredDraftItem(");
assert.match(clearPrevious, /!stored\.attempt\.requiresRecipeContinuation\(\)/);
const previousStepPost = methodSlice(main,
  "private void submitAutoStepPayload(",
  "private JSONArray checkDuplicate(");
assert.match(previousStepPost, /int completedRecipeCount, ProfileWorkflow workflow/);
assert.doesNotMatch(previousStepPost, /profileWorkflow\(\)/,
  "recipe POST retry policy must come from the captured workflow");
assert.match(previousStepPost,
  /for \(int attempt = firstAttempt;\s*attempt <= effectiveRecipeMaxAttempts;/,
  "recipeMaxAttempts must remain a persistent total across restored invocations");
assert.doesNotMatch(previousStepPost, /attemptsInThisCall/,
  "a restored recipe must not receive a fresh retry window");
assert.match(main, /hasStoredPreviousStepSubmissionAttempt\(\).*showPreviousStepSubmissionBlock/s);
assert.match(main, /prefs\.contains\(previousStepSubmissionAttemptPreferenceKey\(\)\)/);
assert.match(main, /Draft clear blocked by remote safety journal/);

const verifiedJournal = methodSlice(main,
  "private PreviousStepSubmissionAttempt validateVerifiedPreviousStepSubmissionAttempt(",
  "/** Capture the exact receipt identity");
assert.match(verifiedJournal, /ProfileWorkflow workflow/);
assert.doesNotMatch(verifiedJournal, /profileWorkflow\(\)/,
  "verified receipt validation must use the lookup workflow snapshot");

const resolvedChain = methodSlice(main,
  "private PreviousStepSubmissionAttempt.ChainIdentity\n            previousStepSubmissionChainForResolvedUnit(",
  "/** Call only after the unit's terminal outcome");
assert.match(resolvedChain, /ProfileWorkflow workflow/);
assert.doesNotMatch(resolvedChain, /profileWorkflow\(\)/,
  "resolved terminal chain identity must use the submitted workflow snapshot");

const storedUnit = methodSlice(main,
  "private UnitRecord previousStepUnitFromStoredDraftItem(",
  "/** Read-back proof for a terminal unit");
assert.match(storedUnit, /JSONObject item, ProfileWorkflow workflow/);
assert.doesNotMatch(storedUnit, /profileWorkflow\(\)/,
  "stored terminal reconstruction must use its recovery workflow snapshot");

const manualCheck = methodSlice(main, "private void checkPreviousStepsForBatch()",
  "private void checkScannedUnitPreviousSteps(");
assert.match(manualCheck, /final ProfileWorkflow workflow = profileWorkflow\(\)/);
assert.match(manualCheck,
  /ensurePreviousSteps\(\s*api, unit, expectedDraftBinding, workflow\)/,
  "manual check must enter the same internally gated side-effect path");

console.log("previous-step journal wiring self-test: pass");
