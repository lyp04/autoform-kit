package com.autoformkit.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Test;

import java.util.Arrays;
import java.util.LinkedHashMap;

public class AlternateEntryContinuationProofTest {
    private static String hex(char value, int length) {
        char[] out = new char[length];
        Arrays.fill(out, value);
        return new String(out);
    }

    private static AlternateEntryDraftState draft(String serial,
                                                  LinkedHashMap<String, Boolean> toggles) {
        return AlternateEntryDraftState.create(hex('a', 64), hex('b', 20), hex('c', 64),
            hex('d', 64), "sample-entry", "sample-source", "sample-return", serial,
            SnScanRules.SOURCE_ENTERED, Arrays.asList("/private/reserved-photo.jpg"), toggles);
    }

    @Test
    public void strictRoundTripBindsExactPairBindingAndLogicalDraft() {
        LinkedHashMap<String, Boolean> toggles = new LinkedHashMap<>();
        toggles.put("sample-option", true);
        AlternateEntryDraftState draft = draft("SAMPLE-001", toggles);
        AlternateEntryContinuationProof proof = AlternateEntryContinuationProof.create(
            hex('1', 32), hex('b', 20), 7, hex('2', 64), hex('c', 64),
            draft.continuationStateSha256());

        AlternateEntryContinuationProof parsed = AlternateEntryContinuationProof.parse(
            proof.toJson().toString());
        assertEquals(hex('1', 32), parsed.token);
        assertTrue(parsed.matches(hex('b', 20), 7, hex('2', 64), hex('c', 64),
            draft.continuationStateSha256()));
        assertFalse(parsed.matches(hex('b', 20), 8, hex('2', 64), hex('c', 64),
            draft.continuationStateSha256()));
        assertFalse(parsed.matches(hex('b', 20), 7, hex('3', 64), hex('c', 64),
            draft.continuationStateSha256()));
    }

    @Test
    public void restoredProofCannotBeBorrowedByAnyLaterFieldOrToggleChange() {
        LinkedHashMap<String, Boolean> toggles = new LinkedHashMap<>();
        toggles.put("sample-option", true);
        AlternateEntryDraftState before = draft("SAMPLE-001", toggles);
        AlternateEntryContinuationProof proof = AlternateEntryContinuationProof.create(
            hex('1', 32), hex('b', 20), 7, hex('2', 64), hex('c', 64),
            before.continuationStateSha256());

        AlternateEntryDraftState changedSerial = draft("SAMPLE-002", toggles);
        LinkedHashMap<String, Boolean> changedToggles = new LinkedHashMap<>();
        changedToggles.put("sample-option", false);
        AlternateEntryDraftState changedToggle = draft("SAMPLE-001", changedToggles);
        assertFalse(proof.matches(hex('b', 20), 7, hex('2', 64), hex('c', 64),
            changedSerial.continuationStateSha256()));
        assertFalse(proof.matches(hex('b', 20), 7, hex('2', 64), hex('c', 64),
            changedToggle.continuationStateSha256()));
    }

    @Test
    public void malformedMissingAndUnknownFieldsFailClosed() throws Exception {
        LinkedHashMap<String, Boolean> toggles = new LinkedHashMap<>();
        AlternateEntryContinuationProof proof = AlternateEntryContinuationProof.create(
            hex('1', 32), hex('b', 20), 7, hex('2', 64), hex('c', 64),
            draft("SAMPLE-001", toggles).continuationStateSha256());

        JSONObject missing = proof.toJson();
        missing.remove("stateSha256");
        assertThrows(IllegalArgumentException.class,
            () -> AlternateEntryContinuationProof.parse(missing.toString()));
        JSONObject unknown = proof.toJson();
        unknown.put("pageOpen", true);
        assertThrows(IllegalArgumentException.class,
            () -> AlternateEntryContinuationProof.parse(unknown.toString()));
        assertThrows(IllegalArgumentException.class,
            () -> AlternateEntryContinuationProof.create("page-open", hex('b', 20), 7,
                hex('2', 64), hex('c', 64), hex('4', 64)));
    }
}
