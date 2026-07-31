#!/usr/bin/env node

import fs from "node:fs";
import path from "node:path";
import process from "node:process";

const root = path.resolve(path.dirname(new URL(import.meta.url).pathname), "..");
const read = (name) => fs.readFileSync(path.join(root, name), "utf8");
const main = read("app/src/com/autoformkit/app/MainActivity.java");
const backendAdapter = read("app/src/com/autoformkit/app/BackendAdapter.java");
const remoteSideEffectSafety = read(
  "app/src/com/autoformkit/app/RemoteSideEffectSafetyRules.java");
const methodBody = (start, end) => {
  const from = main.indexOf(start);
  const to = main.indexOf(end, from + start.length);
  return from >= 0 && to > from ? main.slice(from, to) : "";
};
const alternateSubmitMethod = methodBody(
  "private void submitAlternateEntry()", "private JSONObject resolveAlternateEntryDynamicOverrides(");
const alternateOcrMethod = methodBody(
  "private void recognizeAlternateEntrySerialFromPhoto(", "private void showAlternateEntryOcrCandidates(");
const printCaptureMethod = methodBody(
  "private PrintRemoteContext capturePrintRemoteContext(",
  "private synchronized boolean printRemoteBindingStillCurrent(");
const latestPrintJobMethod = main.match(
  /private PrintJobLookup latestPrintJobForSn\([\s\S]*?\n    \}\n/,
)?.[0] ?? "";
const printBinding = read("app/src/com/autoformkit/app/PrintRemoteBinding.java");
const reprintAttempt = read("app/src/com/autoformkit/app/PrintReprintAttempt.java");
const update = read("app/src/com/autoformkit/app/UpdateManager.java");
const updateProvider = read("app/src/com/autoformkit/app/UpdateApkProvider.java");
const updateRules = read("app/src/com/autoformkit/app/UpdateInstallRules.java");
const panelPairCoordinator = read(
  "app/src/com/autoformkit/app/PanelPairCacheCoordinator.java");
const debugGradle = read("app/build.gradle");
const debugManifest = read("app/src/debug/AndroidManifest.xml");
const mainManifest = read("app/AndroidManifest.xml");

const checks = new Map([
  ["alternate-entry gate requires reviewed source and target plus submit adapter",
    /operationalPoliciesExplicit\(sourceProfile\)/.test(remoteSideEffectSafety)
    && /operationalPoliciesExplicit\(targetProfile\)/.test(remoteSideEffectSafety)
    && /missingForSubmit\(false, false, false, false\)/.test(remoteSideEffectSafety)],
  ["alternate-entry UI preflight and immutable bind share the fail-closed gate",
    /preflightAlternateEntry\([\s\S]{0,900}alternateEntryCapabilityErrors/.test(main)
    && /bindAlternateEntry\([\s\S]{0,900}alternateEntryCapabilityErrors/.test(main)],
  ["alternate submit gate precedes every auth GET upload and POST",
    alternateSubmitMethod.includes("alternateEntryCapabilityErrors")
    && alternateSubmitMethod.indexOf("alternateEntryCapabilityErrors")
      < alternateSubmitMethod.indexOf("checkAuthBoundNow(api, token)")
    && alternateSubmitMethod.indexOf("alternateEntryCapabilityErrors")
      < alternateSubmitMethod.indexOf("uploadAlternateEntryImages(")
    && alternateSubmitMethod.indexOf("alternateEntryCapabilityErrors")
      < alternateSubmitMethod.indexOf("postEndpointJsonExact(")],
  ["alternate OCR adds its own gate before multipart POST",
    alternateOcrMethod.includes("alternateEntryOcrCapabilityErrors")
    && alternateOcrMethod.indexOf("alternateEntryOcrCapabilityErrors")
      < alternateOcrMethod.indexOf("recognizeText(recognizeTextUrl")],
  ["print UI capture and live guards require reviewed full capability",
    /printingConfiguredForProfile\(\)[\s\S]{0,240}printingCapabilityErrors/.test(main)
    && printCaptureMethod.includes("printingCapabilityErrors")
    && /printRemoteBindingStillCurrent\([\s\S]{0,420}printingCapabilityErrors/.test(main)
    && /missingForSubmit\(false, false, false, true\)/.test(remoteSideEffectSafety)],
  ["empty print type allowlist and retry key fail closed",
    /boolean accepts\(JSONObject job\) \{\s*return !acceptedTypeValues\.isEmpty\(\)/.test(backendAdapter)
    && /JSONObject retryPayload\(long id\) \{\s*if \(retryIdField\.isEmpty\(\)\)/.test(backendAdapter)],
  ["print binding captures panel pair", /panelPairSha256/.test(printBinding)],
  ["print binding captures backend semantics", /backendSemanticsSha256/.test(printBinding)],
  ["print binding uses exact token plus web fingerprint", /print-session\/v1/.test(printBinding)
    && /webFingerprint/.test(printBinding) && /requiredOpaque\(token/.test(printBinding)],
  ["print connection is canonical", /\[0-9a-f\]\{20\}/.test(printBinding)],
  ["cloud GET has pre/post guards", /latestPrintJobForSnBound\(\s*context, previousJobId, request\.serial, "print-job"/.test(main)
    && /phase \+ " GET"/.test(main) && /phase \+ " response"/.test(main)],
  ["print job list proves success before parsing any job", latestPrintJobMethod.includes("api.isSuccess(body)")
    && latestPrintJobMethod.includes("throw new IOException(api.apiErrorMessage(body))")
    && latestPrintJobMethod.indexOf("api.isSuccess(body)")
      < latestPrintJobMethod.indexOf("printing.jobs(body)")],
  ["manual POST has pre/post guards", /"manual reprint POST"/.test(main)
    && /"manual reprint response"/.test(main)],
  ["inline POST uses the same exact-bound sender", /executeBoundReprint\(\s*context, target, "inline reprint POST",/.test(main)
    && /"inline reprint response"/.test(main)
    && /confirmPrintInline\(\s*PrintRemoteContext context/.test(main)],
  ["only configured reprint success is terminal", /boolean configuredSuccess = context\.api\.isSuccess\(response\)/.test(main)
    && /responseDisposition\(configuredSuccess\)/.test(main)
    && /unclassified non-success response/.test(main)
    && /configuredSuccess[\s\S]{0,160}\? ResponseDisposition\.CONFIRMED_SUCCESS[\s\S]{0,80}: ResponseDisposition\.UNCERTAIN/.test(reprintAttempt)],
  ["durable reprint journal records posting and uncertain", /POSTING\("posting"\)/.test(reprintAttempt)
    && /UNCERTAIN\("uncertain"\)/.test(reprintAttempt)
    && /recoverPosting/.test(reprintAttempt)
    && /recoverReprintAttemptsAfterProcessDeath\(\)/.test(main)],
  ["same remote job remains blocked after session or policy change", /sameRemoteTarget/.test(printBinding)
    && /store\.blocking\(target\)/.test(main)
    && /duplicate unresolved print target/.test(reprintAttempt)],
  ["unresolved reprint pins profile Panel and hot catalog boundaries", /hasStoredOrUnreadableReprintAttempt\(\)/.test(main)
    && /changing && \(submitting[\s\S]{0,260}hasStoredOrUnreadableReprintAttempt\(\)/.test(main)
    && /safeToInstallBoundPanelSnapshot\(\)[\s\S]{0,360}hasStoredOrUnreadableReprintAttempt\(\)/.test(main)
    && /connectionChanged[\s\S]{0,260}hasStoredOrUnreadableReprintAttempt\(\)/.test(main)],
  ["reprint POST is journaled before exact bytes are sent", /PrintReprintAttempt\.Attempt attempt = beginReprintAttempt\(target, exactPayload\);[\s\S]{0,900}postStarted = true;[\s\S]{0,200}retryPrintExact\(exactPayload\)/.test(main)],
  ["failed terminal journal cleanup restores the blocking in-memory record", /failed removal must therefore restore[\s\S]{0,320}putString\(REPRINT_ATTEMPTS_KEY, store\.serialize\(\)\)\.apply\(\)/.test(main)],
  ["exact successful printed status is the only automatic uncertain resolution", /resolveConfirmedPrintedReprint\(/.test(main)
    && /responseSuccess = context\.api\.isSuccess\(response\)/.test(main)
    && /printed = context\.api\.endpoints\.printing\.isPrinted\(job\)/.test(main)
    && /serialMatches\([\s\S]{0,100}job, observedTarget\.serial/.test(main)
    && /confirmedPrintedResolution\([\s\S]{0,120}observedTarget, responseSuccess, printed/.test(main)],
  ["manual auto and deferred status reads share exact convergence", /latestPrintJobForSnBound\(\s*context, target\.jobId, target\.serial, "manual reprint status"/.test(main)
    && /latestPrintJobForSnBound\(\s*context, 0L, sn, "inline print-job"/.test(main)
    && /latestPrintJobForSnBound\(\s*context, 0L, unit\.sn, "deferred print-job"/.test(main)],
  ["uncertain manual result is shown even after context change", /Outcome-uncertain is a safety warning/.test(main)
    && /if \(!activityAlive\(\)\) return;[\s\S]{0,260}toast\(fmsg\)/.test(main)],
  ["print guards enforce exact job and serial", /target\.identifies\(expectedJobId, expectedSerial\)/.test(main)
    && /latestPrintJobForSn\(context\.api, request\.serial\)/.test(main)],
  ["print UI callback checks captured binding", /printRemoteBindingStillCurrent\(context\)/.test(main)],
  ["ambiguous inline POST is not retried", /reprintOutcomeUncertain/.test(main)
    && /inline_reprint_uncertain/.test(main)],
  ["update source binds exact Panel pair", /catalogVersion/.test(updateRules)
    && /panelPairSha256/.test(updateRules)
    && /PanelPairCacheCoordinator\.loadActivePairIfNoCandidates\(\s*context, expectedConnection\)/.test(update)
    && /CandidatePolicy\.REQUIRE_NONE/.test(panelPairCoordinator)
    && /panelPair\.pairSha256/.test(update)
    && /\.put\("panelPairSha256", pending\.source\.panelPairSha256\)/.test(update)
    && /sourceJson\.getString\("panelPairSha256"\)/.test(update)],
  ["update source requires one coherent compatible config+catalog pair",
    /parsePair\(String configText, String catalogText/.test(panelPairCoordinator)
    && /configVersion != catalogVersion/.test(panelPairCoordinator)
    && /AppConfig\.isBoundToConnection/.test(panelPairCoordinator)
    && /MainDraftSnapshotRules\.panelPairSha256/.test(panelPairCoordinator)],
  ["pending install stores complete metadata", [
    "manifestSha256", "apkSha256", "packageName", "versionCode", "sourceSha256",
    "signerSetSha256", "apkLength", "apkLastModified",
  ].every((field) => update.includes(`.put("${field}"`))],
  ["resume rehashes and reparses APK", /validatePendingApk\(pending, apk\)/.test(update)
    && /getPackageArchiveInfo/.test(update)],
  ["resume checks signing continuity", /hasSignerContinuity/.test(update)],
  ["legacy path-only pending is rejected", /rejectLegacyPendingPath/.test(update)
    && !/requestInstall\(apk\)/.test(update)],
  ["install UI rechecks source and inode facts", /pendingSourceStillCurrent\(pending\)/.test(update)
    && /apk\.lastModified\(\) != pending\.metadata\.apkLastModified/.test(update)],
  ["installer URI carries a pending-bound random handoff", /uriForFile\(activity, apk, handoffToken\)/.test(update)
    && /handoffBindingSha256/.test(updateRules)
    && /PREF_HANDOFF_IDENTITY/.test(update)
    && /PREF_HANDOFF_IDENTITY/.test(updateProvider)],
  ["provider validates the exact descriptor it returns", /ParcelFileDescriptor\.open/.test(updateProvider)
    && /validateProviderHandoff\([\s\S]{0,160}descriptor\)/.test(updateProvider)
    && /Os\.pread\(fd/.test(update)
    && /getPackageArchiveInfo/.test(update)
    && /hasSignerContinuity/.test(update)
    && /matchesValidated/.test(update)
    && /sourceBindingStillCurrent/.test(update)],
  ["provider keeps API 23 timestamp fallback behind an API 27 exact branch",
    /Build\.VERSION\.SDK_INT >= Build\.VERSION_CODES\.O_MR1[\s\S]{0,160}statModifiedMillisApi27/.test(update)
    && /return stat\.st_mtime == expectedMillis \/ 1000L/.test(update)
    && /@TargetApi\(Build\.VERSION_CODES\.O_MR1\)[\s\S]{0,120}statModifiedMillisApi27/.test(update)],
  ["provider fallback retains inode size read-only and digest guards",
    /before\.st_dev != after\.st_dev/.test(update)
    && /before\.st_ino != after\.st_ino/.test(update)
    && /before\.st_size != after\.st_size/.test(update)
    && /after\.st_mode & 0222/.test(update)
    && /sameStatModified\(before, after\)/.test(update)
    && /pending\.metadata\.apkSha256, actualSha256/.test(update)],
  ["provider activates repeat-open capability only after exact-FD validation", /authorize\(uri, true\)/.test(updateProvider)
    && /validateProviderHandoff[\s\S]{0,2400}remove\(UpdateManager\.PREF_HANDOFF_BINDING\)/.test(updateProvider)
    && /putString\(UpdateManager\.PREF_HANDOFF_OPENED_BINDING/.test(updateProvider)
    && /every descriptor is still[\s\S]{0,100}independently pinned/.test(updateProvider)],
  ["installer launch keeps durable pending state for cancel or restart", /currentVersionCode\(\) >= pending\.metadata\.versionCode/.test(update)
    && /showPendingInstallRetry\(pending\)/.test(update)
    && /suspendIssuedHandoff\(pending\)/.test(update)
    && /activity\.startActivity\(intent\);\s*\} catch/.test(update)
    && !/activity\.startActivity\(intent\);[\s\S]{0,180}clearPendingPreferences/.test(update)],
  ["initial and redirected update URLs require HTTPS", /URL current = requireHttps\(new URL\(url\)\)/.test(update)
    && /current = requireHttps\(new URL\(current, location\)\)/.test(update)],
  ["debug auto-update and update provider remain disabled", /debug\s*\{[\s\S]*AUTO_UPDATE_ENABLED[^\n]*false/.test(debugGradle)
    && /android:name="\.UpdateApkProvider"[\s\S]{0,80}tools:node="remove"/.test(debugManifest)],
  ["camera capture handlers are visible on Android 11+", /<queries>[\s\S]*android\.media\.action\.IMAGE_CAPTURE[\s\S]*<\/queries>/.test(mainManifest)],
]);

const failed = [...checks].filter(([, ok]) => !ok).map(([name]) => name);
if (failed.length) {
  for (const name of failed) process.stderr.write(`FAIL ${name}\n`);
  process.exit(1);
}
process.stdout.write(`print/update binding self-test: ${checks.size}/${checks.size} passed\n`);
