#!/usr/bin/env node

import fs from "node:fs";
import path from "node:path";
import process from "node:process";

const root = path.resolve(path.dirname(new URL(import.meta.url).pathname), "..");
const read = (name) => fs.readFileSync(path.join(root, name), "utf8");
const app = read("app/src/com/autoformkit/app/App.java");
const config = read("app/src/com/autoformkit/app/AppConfig.java");
const catalog = read("app/src/com/autoformkit/app/FormCatalog.java");
const manager = read("app/src/com/autoformkit/app/FormCatalogManager.java");
const main = read("app/src/com/autoformkit/app/MainActivity.java");
const update = read("app/src/com/autoformkit/app/UpdateManager.java");
const coordinator = read(
  "app/src/com/autoformkit/app/PanelPairCacheCoordinator.java");
const transaction = read(
  "app/src/com/autoformkit/app/PanelPairCacheTransaction.java");
const transactionTest = read(
  "app/test/com/autoformkit/app/PanelPairCacheTransactionTest.java");
const coordinatorTest = read(
  "app/test/com/autoformkit/app/PanelPairCacheCoordinatorTest.java");
const queueDeleteTransaction = read(
  "app/src/com/autoformkit/app/ManualQueueDeleteTransaction.java");
const queueDeleteTest = read(
  "app/test/com/autoformkit/app/ManualQueueDeleteTransactionTest.java");
const catalogValidator = read(
  "app/src/com/autoformkit/app/CatalogPromotionValidator.java");
const catalogValidatorTest = read(
  "app/test/com/autoformkit/app/CatalogPromotionValidatorTest.java");

const body = (source, start, end) => {
  const from = source.indexOf(start);
  const to = source.indexOf(end, from + start.length);
  return from >= 0 && to > from ? source.slice(from, to) : "";
};

const appCreate = body(app, "public void onCreate()", "FailureReporter.init(this)");
const configRefresh = body(config, "static void refresh(", "static boolean hasUsablePayload(");
const catalogSync = body(manager, "private void sync(", "private static void notifyFinished(");
const configLoad = body(config, "static JSONObject load(", "/**\n     * Fetch");
const catalogLoad = body(catalog, "static BoundSnapshot loadBoundSnapshot(",
  "/** True only when");
const committedCleanup = body(transaction,
  "private static void acceptCommittedNew(",
  "/** Keep PREPARED");
const handleRefresh = body(main, "private void handlePanelRefreshFinished(",
  "/**\n     * A publish can expose");
const safePromotion = body(main,
  "promotePanelPairCandidatesAtSafeBoundary()",
  "private boolean maybeInstallBoundPanelSnapshotAtSafeBoundary()");
const updateLoad = body(update, "private static Config loadConfig(",
  "private UpdateInfo findUpdate(");
const queueDelete = body(main, "private boolean deleteQueueSnapshot()",
  "private void restoreQueueSnapshot()");

const checks = new Map([
  ["startup recovery precedes legacy migration",
    appCreate.indexOf("PanelPairCacheCoordinator.recover(this)") >= 0
      && appCreate.indexOf("PanelPairCacheCoordinator.recover(this)")
        < appCreate.indexOf("LegacyPanelCacheMigration.migrate(this)")],
  ["config writer rechecks and stages through coordinator",
    configRefresh.includes("PanelPairCacheCoordinator.stageConfigCandidate(")
      && !configRefresh.includes("AtomicCacheFile.write(")],
  ["catalog writer rechecks and stages through coordinator",
    catalogSync.includes("PanelPairCacheCoordinator.stageCatalogCandidate(")
      && !catalogSync.includes("FormCatalog.writeCandidateCache")],
  ["same-revision catalog skip is bound to publication digest",
    manager.includes("shouldFetchPublishedCatalog(")
      && manager.includes("AppConfig.catalogSourceSha256(existing.catalogRoot)")
      && /!remote\.equals\(bound\)/.test(manager)],
  ["both public active loaders use one candidate-aware coherent pair view",
    configLoad.includes("PanelPairCacheCoordinator.loadActivePairIfCandidatesPermit")
      && catalogLoad.includes("PanelPairCacheCoordinator.loadActivePairIfCandidatesPermit")
      && !configLoad.includes("PanelPairCacheCoordinator.loadActivePairOrNull")
      && !catalogLoad.includes("PanelPairCacheCoordinator.loadActivePairOrNull")],
  ["strictly newer valid halves preserve legacy active-cache fallback",
    coordinator.includes("newerCandidatesPermitActiveUse(")
      && /AppConfig\.catalogVersion\(config\) <= active\.version/.test(coordinator)
      && /FormCatalog\.catalogVersion\(catalog\) <= active\.version/.test(coordinator)
      && catalog.includes("loadActivePairIfCandidatesPermit(")
      && main.includes("pendingCandidatesBlockActiveUse(")
      && coordinatorTest.includes(
        "strictlyNewerValidCandidateHalvesPermitExactOldActiveFallback")
      && coordinatorTest.includes(
        "sameOlderMalformedOrCrossPanelCandidatesBlockActiveFallback")],
  ["atomic active-use views close candidate classify/load TOCTOU",
    coordinator.includes("loadActivePairIfCandidatesPermit(")
      && coordinator.includes("loadActivePairIfNoCandidates(")
      && coordinator.includes("atomicActivePairView(")
      && coordinatorTest.includes(
        "atomicViewClassifiesCandidateInsertedAfterActiveRead")
      && coordinatorTest.includes(
        "atomicViewsSeparateWorkflowAndUpdateCandidatePolicies")],
  ["coordinator serializes every pair operation with handoff lock",
    (coordinator.match(/synchronized \(UpdateInstallRules\.HANDOFF_LOCK\)/g) ?? [])
      .length >= 7],
  ["candidate write rechecks connection inside the same lock",
    /synchronized \(UpdateInstallRules\.HANDOFF_LOCK\)[\s\S]{0,500}recoverLocked\(app\)[\s\S]{0,180}requireExpectedConnection/.test(coordinator)],
  ["promotion validates complete exact-current candidate pair",
    /parsePair\(configText, catalogText, panelBase, key\)/.test(coordinator)
      && /currentConnection\(app\)\.equals\(clean\(expectedConnection\)\)/.test(coordinator)],
  ["active pair parser binds both halves and equal positive revision",
    /validConfig\(config, panelBase, key\)/.test(coordinator)
      && /validCatalog\(catalogRoot, panelBase, key\)/.test(coordinator)
      && /configVersion <= 0 \|\| configVersion != catalogVersion/.test(coordinator)
      && /validPairProof\(config, catalogRoot, panelBase, key, configVersion\)/.test(coordinator)],
  ["committed cleanup deletes both candidates before receipt",
    committedCleanup.indexOf("delete(files.candidateFirst)") >= 0
      && committedCleanup.indexOf("delete(files.candidateFirst)")
        < committedCleanup.indexOf("delete(files.candidateSecond)")
      && committedCleanup.indexOf("delete(files.candidateSecond)")
        < committedCleanup.indexOf("delete(files.receipt)")],
  ["exact COMMITTED hashes converge without mutable validator",
    /receipt\.state == State\.COMMITTED && activeNewMatchesReceipt/.test(transaction)
      && !body(transaction, "private static boolean activeNewMatchesReceipt(",
        "private static String resolveOld(").includes("validator")],
  ["nonfatal runtime mutation triggers synchronous recovery",
    /catch \(IOException \| RuntimeException failure\)/.test(transaction)
      && transactionTest.includes("runtimeFailureAfterFirstActiveMutationRunsRecovery")],
  ["after-mutation crash windows have restart tests",
    transactionTest.includes("crashAfterFirstActiveWriteRestoresExactOldPairOnRestart")
      && transactionTest.includes("crashAfterFirstCandidateDeletionStillAcceptsExactCommittedNew")
      && transactionTest.includes("crashAfterFinalReceiptDeletionLeavesOnlyExactCommittedNew")],
  ["transaction path identity stays compatible with Android 23",
    transaction.includes("file.getCanonicalPath()")
      && !transaction.includes(".toPath()")
      && transactionTest.includes("pathAliasesCannotNameTheSameTransactionFile")],
  ["coordinator validator tests wrong binding revision schema and adapter",
    coordinatorTest.includes("rejectsDifferentRevisions")
      && coordinatorTest.includes("rejectsEitherHalfFromAnotherPanelOrKey")
      && coordinatorTest.includes("rejectsUnsupportedSchemaEmptyProfilesAndCoercedVersions")
      && coordinatorTest.includes("rejectsConfigWithoutCompleteUploadAndSubmitContract")],
  ["catalog promotion validates structure references and adapter closure",
    coordinator.includes("CatalogPromotionValidator.isStructurallyValid(catalog)")
      && coordinator.includes(
        "CatalogPromotionValidator.isExecutableWithConfig(")
      && catalogValidator.includes("ProfilePickerRules")
      && catalogValidator.includes("AlternateEntryRules.resolve(")
      && catalogValidator.includes("missingForDynamicPreviousSteps(")
      && catalogValidatorTest.includes(
        "rejectsMalformedDuplicateOrUnreachablePickerProfiles")
      && catalogValidatorTest.includes(
        "previousStepPhotoAndResolverReferencesMustClose")
      && catalogValidatorTest.includes(
        "alternateTargetsPhotosPoliciesAndAdapterResolversMustClose")],
  ["refresh callback promotes on UI and retries candidate mismatch",
    handleRefresh.includes("runOnUiThread")
      && handleRefresh.includes("listenerRound != panelSyncRound")
      && handleRefresh.includes("promotePanelPairCandidatesAtSafeBoundary")
      && handleRefresh.includes("PanelPairCacheCoordinator.needsPairedRetry")],
  ["safe promotion rechecks remote and installer gates under handoff lock",
    safePromotion.includes("synchronized (UpdateInstallRules.HANDOFF_LOCK)")
      && safePromotion.includes("RemoteSideEffectGate.blockingStatePresent(this)")
      && safePromotion.includes("UpdateManager.installerHandoffActive(this)")],
  ["updater captures one coherent pair and its digest",
    updateLoad.includes("PanelPairCacheCoordinator.loadActivePairIfNoCandidates(")
      && updateLoad.includes("panelPair.pairSha256")
      && !updateLoad.includes("FormCatalog.readRawCache")],
  ["connection change discards active candidates and transaction receipt together",
    manager.includes("PanelPairCacheCoordinator.discardForConnectionChange(app)")
      && /storage\.delete\(files\.receipt\)[\s\S]{0,260}storage\.delete\(files\.activeSecond\)/.test(coordinator)],
  ["explicit queue-backup delete is receipt/tombstone crash recoverable",
    queueDelete.includes("ManualQueueDeleteTransaction.delete(")
      && queueDelete.includes("recoverManualQueueDeleteTransaction()")
      && !queueDelete.includes("AtomicCacheFile.delete(")
      && queueDeleteTransaction.includes("DELETE_RECEIPT")
      && queueDeleteTransaction.includes("DELETE_TOMBSTONE")
      && queueDeleteTest.includes(
        "crashAfterEveryDeleteMutationConvergesToExactRestoreOrFullDelete")
      && main.includes("queue_backup_delete_confirm")],
]);

const failed = [...checks].filter(([, ok]) => !ok).map(([name]) => name);
if (failed.length) {
  for (const name of failed) process.stderr.write(`FAIL ${name}\n`);
  process.exit(1);
}
process.stdout.write(`panel pair cache self-test: ${checks.size}/${checks.size} passed\n`);
