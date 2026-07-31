package com.autoformkit.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;

public class AlternateEntryDraftStateTest {
    private static String hex(char value, int length) {
        char[] out = new char[length];
        Arrays.fill(out, value);
        return new String(out);
    }

    private static AlternateEntryDraftState sample() {
        LinkedHashMap<String, Boolean> toggles = new LinkedHashMap<>();
        toggles.put("sample-option", true);
        return AlternateEntryDraftState.create(hex('a', 64), hex('b', 20), hex('c', 64),
            hex('d', 64), "sample-entry", "sample-source", "sample-return", "SAMPLE-001",
            SnScanRules.SOURCE_BARCODE,
            Arrays.asList("/private/sample-1.jpg", "/private/sample-2.jpg"), toggles);
    }

    @Test
    public void strictRoundTripPreservesExactBoundDraft() {
        AlternateEntryDraftState parsed = AlternateEntryDraftState.parse(
            sample().toJson().toString());

        assertEquals("SAMPLE-001", parsed.serial);
        assertEquals(SnScanRules.SOURCE_BARCODE, parsed.serialSource);
        assertEquals(2, parsed.photos.size());
        assertEquals(Boolean.TRUE, parsed.toggles.get("sample-option"));
        assertTrue(parsed.matches(hex('a', 64), hex('b', 20), hex('c', 64),
            hex('d', 64), "sample-entry"));
        assertFalse(parsed.matches(hex('a', 64), hex('b', 20), hex('e', 64),
            hex('d', 64), "sample-entry"));
    }

    @Test
    public void accountFingerprintIsStableAndDoesNotExposeAccount() {
        String fingerprint = AlternateEntryDraftState.accountFingerprint("operator@example.test");
        assertEquals(64, fingerprint.length());
        assertEquals(fingerprint,
            AlternateEntryDraftState.accountFingerprint("operator@example.test"));
        assertFalse(fingerprint.contains("operator"));
    }

    @Test
    public void emptyDraftDuplicatePathsAndMalformedHashesAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> AlternateEntryDraftState.create(
            hex('a', 64), hex('b', 20), hex('c', 64), hex('d', 64),
            "entry", "source", "", "", SnScanRules.SOURCE_ENTERED,
            Arrays.asList(), new LinkedHashMap<>()));
        assertThrows(IllegalArgumentException.class, () -> AlternateEntryDraftState.create(
            hex('a', 64), hex('b', 20), hex('c', 64), hex('d', 64),
            "entry", "source", "", "SN", SnScanRules.SOURCE_ENTERED,
            Arrays.asList("/a", "/a"),
            new LinkedHashMap<>()));
        assertThrows(IllegalArgumentException.class, () -> AlternateEntryDraftState.create(
            "not-a-hash", hex('b', 20), hex('c', 64), hex('d', 64),
            "entry", "source", "", "SN", SnScanRules.SOURCE_ENTERED,
            Arrays.asList(), new LinkedHashMap<>()));
        assertThrows(IllegalArgumentException.class, () -> AlternateEntryDraftState.create(
            hex('a', 64), hex('b', 20), hex('c', 64), hex('d', 64),
            "entry", "source", "", "SN", "camera",
            Arrays.asList(), new LinkedHashMap<>()));
    }

    @Test
    public void unknownMissingAndWrongTypedFieldsAreRejected() throws Exception {
        JSONObject unexpected = sample().toJson();
        unexpected.put("unexpected", true);
        assertThrows(IllegalArgumentException.class,
            () -> AlternateEntryDraftState.parse(unexpected.toString()));

        JSONObject missing = sample().toJson();
        missing.remove("bindingFingerprint");
        assertThrows(IllegalArgumentException.class,
            () -> AlternateEntryDraftState.parse(missing.toString()));

        JSONObject missingSource = sample().toJson();
        missingSource.remove("serialSource");
        assertThrows(IllegalArgumentException.class,
            () -> AlternateEntryDraftState.parse(missingSource.toString()));

        JSONObject wrongType = sample().toJson();
        wrongType.put("serial", 123);
        assertThrows(IllegalArgumentException.class,
            () -> AlternateEntryDraftState.parse(wrongType.toString()));

        JSONObject unknownSource = sample().toJson();
        unknownSource.put("serialSource", "camera");
        assertThrows(IllegalArgumentException.class,
            () -> AlternateEntryDraftState.parse(unknownSource.toString()));
    }

    @Test
    public void legacyUnlimitedPhotoCountIsNotTruncatedByDraftStorage() {
        ArrayList<String> photos = new ArrayList<>();
        for (int i = 0; i < 125; i++) photos.add("/private/sample-" + i + ".jpg");
        AlternateEntryDraftState draft = AlternateEntryDraftState.create(
            hex('a', 64), hex('b', 20), hex('c', 64), hex('d', 64),
            "entry", "source", "", "SN", SnScanRules.SOURCE_ENTERED,
            photos, new LinkedHashMap<>());

        AlternateEntryDraftState parsed = AlternateEntryDraftState.parse(
            draft.toJson().toString());
        assertEquals(125, parsed.photos.size());
        assertEquals("/private/sample-124.jpg", parsed.photos.get(124));
    }

    @Test
    public void sourceFingerprintBindsPhotoOrderMetadataScanSourceAndToggles() {
        AlternateEntryDraftState draft = sample();
        ArrayList<AlternateEntryDraftState.PhotoEvidence> evidence = new ArrayList<>();
        evidence.add(AlternateEntryDraftState.PhotoEvidence.of(
            "/private/sample-1.jpg", 101L, 1001L));
        evidence.add(AlternateEntryDraftState.PhotoEvidence.of(
            "/private/sample-2.jpg", 202L, 2002L));
        String exact = draft.sourceSnapshotSha256(evidence);
        assertEquals(64, exact.length());
        assertEquals(exact, draft.sourceSnapshotSha256(evidence));

        ArrayList<AlternateEntryDraftState.PhotoEvidence> changedMetadata =
            new ArrayList<>(evidence);
        changedMetadata.set(1, AlternateEntryDraftState.PhotoEvidence.of(
            "/private/sample-2.jpg", 203L, 2002L));
        assertFalse(exact.equals(draft.sourceSnapshotSha256(changedMetadata)));

        LinkedHashMap<String, Boolean> changedToggle = new LinkedHashMap<>();
        changedToggle.put("sample-option", false);
        AlternateEntryDraftState toggled = AlternateEntryDraftState.create(
            draft.accountFingerprint, draft.connectionNamespace, draft.bindingFingerprint,
            draft.backendFingerprint, draft.entryId, draft.sourceProfileId,
            draft.returnProfileId, draft.serial, draft.serialSource, draft.photos,
            changedToggle);
        assertFalse(exact.equals(toggled.sourceSnapshotSha256(evidence)));

        AlternateEntryDraftState entered = AlternateEntryDraftState.create(
            draft.accountFingerprint, draft.connectionNamespace, draft.bindingFingerprint,
            draft.backendFingerprint, draft.entryId, draft.sourceProfileId,
            draft.returnProfileId, draft.serial, SnScanRules.SOURCE_ENTERED, draft.photos,
            draft.toggles);
        assertFalse(exact.equals(entered.sourceSnapshotSha256(evidence)));
    }

    @Test
    public void sourceFingerprintRejectsMissingOrReorderedPhotoEvidence() {
        AlternateEntryDraftState draft = sample();
        assertThrows(IllegalArgumentException.class,
            () -> draft.sourceSnapshotSha256(Collections.singletonList(
                AlternateEntryDraftState.PhotoEvidence.of(
                    "/private/sample-1.jpg", 101L, 1001L))));
        assertThrows(IllegalArgumentException.class,
            () -> draft.sourceSnapshotSha256(Arrays.asList(
                AlternateEntryDraftState.PhotoEvidence.of(
                    "/private/sample-2.jpg", 202L, 2002L),
                AlternateEntryDraftState.PhotoEvidence.of(
                    "/private/sample-1.jpg", 101L, 1001L))));
    }
}
