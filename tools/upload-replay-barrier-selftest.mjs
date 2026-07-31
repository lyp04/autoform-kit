#!/usr/bin/env node

import assert from "node:assert/strict";
import fs from "node:fs";
import path from "node:path";
import process from "node:process";
import { fileURLToPath } from "node:url";

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");
const read = (name) => fs.readFileSync(path.join(root, name), "utf8");
const main = read("app/src/com/autoformkit/app/MainActivity.java");
const barrier = read("app/src/com/autoformkit/app/UploadReplayBarrier.java");
const remoteGate = read("app/src/com/autoformkit/app/RemoteSideEffectGate.java");
const update = read("app/src/com/autoformkit/app/UpdateManager.java");
const updateProvider = read("app/src/com/autoformkit/app/UpdateApkProvider.java");

function slice(source, startMarker, endMarker) {
  const start = source.indexOf(startMarker);
  const end = source.indexOf(endMarker, start + startMarker.length);
  assert.ok(start >= 0, `missing ${startMarker}`);
  assert.ok(end > start, `missing end ${endMarker}`);
  return source.slice(start, end);
}

const begin = slice(main, "private boolean beginUploadReplayBarrier(",
  "/** Clear only the exact in-memory operation");
assert.match(begin, /synchronized \(UpdateInstallRules\.HANDOFF_LOCK\)/,
  "barrier allocation must be atomic across main and alternate workers");
assert.ok(begin.indexOf("UpdateManager.installerHandoffActive(this)")
    < begin.indexOf("UploadReplayBarrier.prepare("),
  "an active installer capability must win atomically over a new upload");
assert.ok(begin.indexOf("UploadReplayBarrier.prepare(")
    < begin.indexOf("writeUploadReplayBarrier(barrier)"),
  "an occupied or corrupt slot must be rejected before any write");

const clear = slice(main, "private boolean clearUploadReplayBarrier(",
  "private boolean uploadReplayBarrierMatches(");
assert.match(clear, /synchronized \(UpdateInstallRules\.HANDOFF_LOCK\)/);
assert.ok(clear.indexOf("restored.barrier.matches(expected)")
    < clear.indexOf(`remove(UPLOAD_REPLAY_BARRIER_KEY)`),
  "only the exact in-memory operation may retire the durable barrier");
assert.ok(clear.indexOf("uploadReplayBarrierClearFailure = retained")
    < clear.indexOf(`remove(UPLOAD_REPLAY_BARRIER_KEY)`),
  "an ambiguous preference removal must remain process-local fail-closed");
assert.match(clear, /prefs\.contains\(UPLOAD_REPLAY_BARRIER_KEY\)/,
  "barrier removal must be synchronously verified");
assert.match(clear, /restoreUploadReplayBarrierAfterFailedRemoval\(retainedJson\)/,
  "a failed removal must restore the exact blocking bytes to the in-process map");

const blockingUpload = slice(main, "private UploadReplayBarrier.RestoreResult blockingUploadReplayBarrier(",
  "private void showUploadReplayBarrierBlock(");
assert.match(blockingUpload, /retireExactlyCompletedUploadReplayBarrier\(restored\)/,
  "restart recovery must use the exact linked COMPLETED receipt before retiring a barrier");
assert.match(blockingUpload, /UploadReplayRecoveryRules\.completedMain/);
assert.match(blockingUpload, /UploadReplayRecoveryRules\.completedAlternate/);

const mainStart = slice(main, "private void beginActiveMainUploadBarrier(",
  "private void finishActiveMainUploadBarrier(");
assert.ok(mainStart.indexOf("saveDraft(true)")
    < mainStart.indexOf("beginUploadReplayBarrier(identity)"),
  "the exact source draft must be durable before the upload barrier");
assert.ok(mainStart.indexOf("beginUploadReplayBarrier(identity)")
    < mainStart.indexOf("networkRetryGate.markUploadStarted()"),
  "whole-unit replay must close only after the durable barrier exists");
assert.match(mainStart, /uploadReplayBarrierMatches\(context\.startedIdentity\)/,
  "every later upload in the same sequence must revalidate the durable slot");

const captureMain = slice(main, "private ActiveMainUploadBarrier captureMainUploadBarrier(",
  "private void beginActiveMainUploadBarrier(");
assert.ok(captureMain.indexOf("blockingUploadReplayBarrier()")
    < captureMain.indexOf("return new ActiveMainUploadBarrier("),
  "an occupied slot must stop a later unit before its read-only or POST action starts");

const finishMain = slice(main, "private void finishActiveMainUploadBarrier(",
  "private void runWithMainUploadBarrier(");
assert.match(finishMain, /UploadReplayBarrierRetirementException/);
assert.match(finishMain, /throw new UploadReplayBarrierRetirementException\(\)/,
  "barrier retirement failure must stay distinct from an acknowledged new submission");
assert.ok(finishMain.indexOf("clearUploadReplayBarrier(context.startedIdentity)")
    < finishMain.indexOf("clearMainSubmissionAttempt()"),
  "the completed main receipt must outlive the upload barrier");

const postMain = slice(main, "private JournaledSubmissionResponse postMainSubmissionOnce(",
  "private void confirmMainSubmissionRejected(");
assert.match(postMain, /uploadContext\.operationId/,
  "main upload and POST receipt must share one operation id");

const uploadWrapper = slice(main, "private String uploadImageWithReplayBarrier(",
  "private void recordDnsAffected(");
assert.ok(uploadWrapper.indexOf("beginActiveMainUploadBarrier(context)")
    < uploadWrapper.indexOf("api.uploadImage(file, uploadName)"),
  "main uploads must persist the barrier before opening the upload socket");

const retry = slice(main, "private void runWithSubmissionNetworkRetry(",
  "private String uploadImageWithReplayBarrier(");
assert.match(retry, /!context\.networkRetryGate\.canRetryWholeUnit\(\)/,
  "a transient response loss after upload start must not replay the unit");

const manualPrevious = slice(main, "private void checkPreviousStepsForBatch()",
  "private void checkScannedUnitPreviousSteps(");
assert.match(manualPrevious, /runWithMainUploadBarrier\(/,
  "manual previous-step creation must share the main upload barrier");
assert.match(manualPrevious, /if \(hasStoredUploadReplayBarrier\(\)\) break;/,
  "a locked unit must stop the rest of a manual batch");

const submitBatch = slice(main, "private void submitBatch()",
  "private void showSubmitLoading(");
assert.match(submitBatch, /RemoteSideEffectGate\.tryAcquireWorker\(this\)/,
  "the full main batch must hold an installer-exclusion worker lease");
assert.match(submitBatch, /submitWorkerLease\.close\(\)/);
assert.match(submitBatch, /if \(hasStoredUploadReplayBarrier\(\)\)[\s\S]{0,900}break;/,
  "any upload-started failure must stop before a later batch unit");
assert.ok(submitBatch.indexOf("exc instanceof SubmissionAcknowledgedRecoveryException")
    < submitBatch.indexOf("exc instanceof SubmissionJournalLockedException"),
  "a successful unit whose lock cleanup failed must not be downgraded to failed");
assert.ok(submitBatch.indexOf("exc instanceof UploadReplayBarrierRetirementException")
    < submitBatch.indexOf("exc instanceof SubmissionAcknowledgedRecoveryException"),
  "barrier retirement must preserve duplicate terminal classifications");
assert.match(submitBatch, /boolean newlySubmitted = "success"\.equals\(unit\.status\)/);

const alternate = slice(main, "private void submitAlternateEntry()",
  "private JSONObject resolveAlternateEntryDynamicOverrides(");
const alternateBegin = alternate.indexOf("beginUploadReplayBarrier(uploadIdentity)");
const alternateExecutor = alternate.indexOf("uploadAlternateEntryImages(");
const alternatePost = alternate.indexOf("postEndpointJsonExact(");
const alternateClear = alternate.indexOf("clearUploadReplayBarrier(uploadIdentity)");
assert.ok(alternateBegin >= 0 && alternateExecutor > alternateBegin
    && alternatePost > alternateExecutor && alternateClear > alternatePost,
  "alternate uploads must be locked before their executor and clear only after success");
assert.ok(alternate.indexOf("alternateOperationId")
    < alternate.indexOf("AlternateSubmissionAttempt.Key attemptKey"));
assert.match(alternate, /sourceSnapshotSha256,[\s\S]{0,180}alternateOperationId\)/,
  "alternate upload and POST receipt must share one operation id");
assert.ok(alternateClear < alternate.indexOf(
    "finalizeCompletedAlternateSubmission(finalCompletedAttempt)"),
  "the alternate draft and photos must outlive barrier retirement");
assert.match(alternate, /RemoteSideEffectGate\.tryAcquireWorker\(this\)/,
  "alternate remote work must hold an installer-exclusion worker lease");
assert.match(alternate, /alternateWorkerLease\.close\(\)/);
assert.match(alternate, /if \(!beginUploadReplayBarrier\(uploadIdentity\)\)/,
  "every valid alternate entry has at least one Panel-required photo and must create a barrier");
assert.match(alternate, /if \(!clearUploadReplayBarrier\(uploadIdentity\)\)/,
  "every valid alternate upload must retire its exact barrier after a completed receipt");

const alternateExit = slice(main, "private void exitAlternateEntryPage()",
  "private void applyTypedAlternateEntrySerial(");
assert.ok(alternateExit.indexOf("blockingUploadReplayBarrier()")
    < alternateExit.indexOf("alternateEntryToggleStates.clear()"),
  "an unresolved upload must block alternate-entry mutation and profile switching");
const blockedAttemptAt = alternateExit.indexOf("if (blockingAttempt != null)");
const normalMutationAt = alternateExit.indexOf("boolean keepBoundPendingData");
assert.ok(blockedAttemptAt >= 0 && normalMutationAt > blockedAttemptAt);
assert.ok(!alternateExit.slice(blockedAttemptAt, normalMutationAt)
    .includes("selectVisibleProfile("),
  "an unresolved alternate POST must retain its exact active profile");

const directUploads = [...main.matchAll(/api\.uploadImage\(/g)].map((match) => match.index);
assert.equal(directUploads.length, 2,
  "new upload call sites must not bypass the replay barrier review");
assert.ok(directUploads.some((index) => index >= main.indexOf("private String uploadImageWithReplayBarrier(")
  && index < main.indexOf("private void recordDnsAffected(")));
assert.ok(directUploads.some((index) => index >= main.indexOf("private String uploadCompressedAlternateEntryPhoto(")
  && index < main.indexOf("private File prepareAlternateEntryUpload(")));

for (const marker of [
  "changing && (submitting", "private void submitBatch()",
  "private void restoreQueueSnapshot()", "private void savePanelConnection("
]) {
  const at = main.indexOf(marker);
  assert.ok(at >= 0 && main.slice(at, at + 1800).includes("UploadReplayBarrier"),
    `${marker} must preserve or block an unresolved upload`);
}

const requestInstall = slice(update, "private void requestInstall(PendingInstall pending)",
  "private void launchInstaller(");
assert.match(requestInstall, /remoteSideEffectBlockingStatePresent\(activity\)/,
  "an old/new APK handoff must preserve all five remote-operation slots and active workers");
const issueHandoff = slice(update, "private String issueHandoffToken(",
  "private boolean handoffWasIssued(");
assert.match(issueHandoff, /synchronized \(UpdateInstallRules\.HANDOFF_LOCK\)/);
assert.ok(issueHandoff.indexOf("remoteSideEffectBlockingStatePresent(activity)")
    < issueHandoff.indexOf("putString(PREF_HANDOFF_BINDING"),
  "installer capability issuance must atomically lose to any remote side-effect blocker");
assert.match(updateProvider, /authorize\(Uri uri, boolean allowConsumed\)[\s\S]{0,300}remoteSideEffectBlockingStatePresent/,
  "the provider must reject an installer URI after any durable slot or worker appears");
assert.match(updateProvider, /UpdateManager\.validateProviderHandoff\([\s\S]{0,3000}remoteSideEffectBlockingStatePresent/,
  "descriptor activation must recheck all remote side-effect blockers under the handoff lock");

for (const key of [
  "UPLOAD_REPLAY_BARRIER_KEY", "REPRINT_ATTEMPTS_KEY",
  "MAIN_SUBMISSION_ATTEMPT_PREFIX", "PREVIOUS_STEP_SUBMISSION_ATTEMPT_PREFIX",
  "ALTERNATE_SUBMISSION_ATTEMPT_PREFIX"
]) {
  assert.ok(remoteGate.includes(key), `unified remote gate must include ${key}`);
}
assert.match(remoteGate, /activeWorkerCount > 0/);
assert.match(remoteGate, /UpdateManager\.installerHandoffActive\(context\)/,
  "worker acquisition and installer capability issuance must share one atomic arbitration lock");

assert.ok(barrier.includes("restored STARTED record remains blocking after process restart"));
assert.match(barrier, /INVALID_STORAGE_TYPE/);
assert.match(barrier, /requireExactKeys\(root, ROOT_KEYS\)/);
for (const forbidden of ["\"url\"", "\"serial\"", "\"token\"", "\"payload\""]) {
  assert.ok(!barrier.toLowerCase().includes(forbidden),
    `durable barrier schema must not store ${forbidden}`);
}

for (const key of ["upload_result_uncertain_title", "upload_result_uncertain_detail"]) {
  assert.equal((main.match(new RegExp(`case "${key}"`, "g")) || []).length, 3,
    `${key} must be translated in all supported languages`);
}

process.stdout.write("upload replay barrier self-test: passed\n");
