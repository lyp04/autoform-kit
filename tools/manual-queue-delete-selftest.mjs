#!/usr/bin/env node

import fs from "node:fs";
import path from "node:path";
import process from "node:process";

const root = path.resolve(path.dirname(new URL(import.meta.url).pathname), "..");
const read = (name) => fs.readFileSync(path.join(root, name), "utf8");
const transaction = read(
  "app/src/com/autoformkit/app/ManualQueueDeleteTransaction.java");
const storage = read(
  "app/src/com/autoformkit/app/ManualQueueDeleteStorage.java");
const transactionTest = read(
  "app/test/com/autoformkit/app/ManualQueueDeleteTransactionTest.java");
const storageTest = read(
  "app/test/com/autoformkit/app/ManualQueueDeleteStorageTest.java");
const main = read("app/src/com/autoformkit/app/MainActivity.java");

const body = (source, start, end) => {
  const from = source.indexOf(start);
  const to = source.indexOf(end, from + start.length);
  return from >= 0 && to > from ? source.slice(from, to) : "";
};

const reconcile = body(main, "private String reconcileManualQueueCopies(",
  "private void addLegacyQueueTempCandidate(");
const mirror = body(main, "private boolean mirrorManualQueueCopies(",
  "private RollbackMirrorRules.Candidate manualQueueFileCandidate(");
const queueDelete = body(main, "private boolean deleteQueueSnapshot()",
  "/** Any current-connection queue artifact");
const queueEvidence = body(main,
  "private boolean manualQueueRecoveryEvidencePresent()",
  "private void restoreQueueSnapshot()");
const migration = body(main, "private boolean migrateLegacyQueueBackupFile()",
  "private boolean writeQueueBackupFileAtomic(");
const promotion = body(main, "private PanelPairCacheCoordinator.Promotion",
  "private boolean maybeInstallBoundPanelSnapshotAtSafeBoundary()");
const onCreate = body(main, "protected void onCreate(", "@Override\n    protected void onSave");
const showSettings = body(main, "private void showSettingsPage()",
  "private void showFormPage()");

const checks = new Map([
  ["transaction snapshots every base/bak/tmp and preference representation",
    transaction.includes("SCOPED_FILE_BASE")
      && transaction.includes("SCOPED_FILE_BAK")
      && transaction.includes("SCOPED_FILE_TMP")
      && transaction.includes("GLOBAL_FILE_BASE")
      && transaction.includes("GLOBAL_FILE_BAK")
      && transaction.includes("GLOBAL_FILE_TMP")
      && transaction.includes("SCOPED_PREF")
      && transaction.includes("GLOBAL_PREF")],
  ["prepared receipt and committed tombstone are separate durable slots",
    transaction.includes("DELETE_RECEIPT")
      && transaction.includes("DELETE_TOMBSTONE")
      && transaction.includes("PREPARED")
      && transaction.includes("COMMITTED")],
  ["all delete and both recovery mutation windows have exhaustive crash tests",
    transactionTest.includes(
      "crashAfterEveryDeleteMutationConvergesToExactRestoreOrFullDelete")
      && transactionTest.includes(
        "rollbackRecoveryItselfIsRestartIdempotentAtEveryMutation")
      && transactionTest.includes(
        "committedRecoveryItselfIsRestartIdempotentAtEveryMutation")],
  ["A/B takeover preserves foreign global bytes in prepared and committed recovery",
    transactionTest.includes(
      "preparedARecoveryAfterBTakeoverRestoresAWithoutTouchingB")
      && transactionTest.includes(
        "committedARecoveryAfterBTakeoverDeletesOnlyA")
      && transactionTest.includes(
        "deletingAWithBGlobalOwnerPreservesEveryBByte")],
  ["adapter maps physical bak/tmp without AtomicFile normalization",
    storage.includes("new File(scopedBase.getPath() + \".bak\")")
      && storage.includes("new File(scopedBase.getPath() + \".tmp\")")
      && storageTest.includes(
        "readsBaseBakAndTmpWithoutNormalizingAnyPhysicalCopy")],
  ["receipt and tombstone paths are independently connection scoped",
    storage.includes("manual-queue-delete-v1")
      && storage.includes("connectionNamespace + \"-\" + logicalKeyHashPrefix")
      && storageTest.includes("receiptAndTombstoneUseOnlyAtomicBackend")],
  ["SharedPreferences false commits still read back and throw",
    storageTest.includes(
      "preferencePutCommitFalseStillReadsBackThenThrows")
      && storageTest.includes(
        "preferenceRemoveCommitFalseStillReadsBackThenThrows")
      && storage.includes("SharedPreferences put commit/readback failed")
      && storage.includes("SharedPreferences remove commit/readback failed")],
  ["exact-write crash remainder is explicit promotion evidence",
    transaction.includes("auxiliaryRecoveryEvidencePresent")
      && storage.includes("EXACT_WRITE_SUFFIX")
      && storage.includes("auxiliaryRecoveryEvidencePresent")
      && storageTest.includes(
        "exactWriteCrashRemainderCannotBypassDeleteOrPromotionGate")],
  ["Main has one adapter helper for the current connection",
    main.includes("ManualQueueDeleteStorage")
      && main.includes("manualQueueDeleteStorage(")],
  ["every reconciliation starts delete-transaction recovery",
    reconcile.includes("recoverManualQueueDeleteTransaction")],
  ["every new mirror clears a committed tombstone before its first write",
    mirror.includes("clearManualQueueDeleteTombstoneForSave")
      && mirror.indexOf("clearManualQueueDeleteTombstoneForSave")
        < mirror.indexOf("writeQueueBackupFileAtomic")],
  ["explicit delete delegates to the crash-safe transaction",
    queueDelete.includes("ManualQueueDeleteTransaction.delete")
      && !queueDelete.includes("AtomicCacheFile.delete(")
      && !queueDelete.includes("prefs.edit().remove(")],
  ["upgrade evidence delegates to tombstone-aware transaction rules",
    queueEvidence.includes("ManualQueueDeleteTransaction.blocksPanelPromotion")],
  ["legacy migration recovers before reconciliation",
    migration.includes("recoverManualQueueDeleteTransaction")
      && migration.indexOf("recoverManualQueueDeleteTransaction")
        < migration.indexOf("reconcileManualQueueCopies")],
  ["candidate promotion rechecks queue evidence under HANDOFF_LOCK",
    /synchronized \(UpdateInstallRules\.HANDOFF_LOCK\)[\s\S]*manualQueueRecoveryEvidencePresent\(\)[\s\S]*PanelPairCacheCoordinator\.promoteCandidates/.test(
      promotion)],
  ["onCreate resolves queue transaction before loading the active Panel pair",
    onCreate.includes("recoverManualQueueDeleteTransaction")
      && onCreate.indexOf("recoverManualQueueDeleteTransaction")
        < onCreate.indexOf("PanelPairCacheCoordinator.loadActivePair")],
  ["Settings resolves queue transaction before reconcile and promotion",
    showSettings.includes("recoverManualQueueDeleteTransaction")
      && showSettings.indexOf("recoverManualQueueDeleteTransaction")
        < showSettings.indexOf("reconcileManualQueueCopies")
      && showSettings.indexOf("recoverManualQueueDeleteTransaction")
        < showSettings.indexOf("maybeInstallBoundPanelSnapshotAtSafeBoundary")],
]);

const failed = [...checks].filter(([, ok]) => !ok).map(([name]) => name);
if (failed.length) {
  for (const name of failed) process.stderr.write(`FAIL ${name}\n`);
  process.exit(1);
}
process.stdout.write(
  `manual queue delete self-test: ${checks.size}/${checks.size} passed\n`);
