package com.autoformkit.app;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.LinkedHashSet;

public class UpdateInstallRulesTest {
    private static final String MANIFEST =
        "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
    private static final String APK =
        "abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789";
    private static final String SIGNERS =
        "1111111111111111111111111111111111111111111111111111111111111111";
    private static final String PAIR =
        "2222222222222222222222222222222222222222222222222222222222222222";

    @Test
    public void sourceBindingChangesWithPanelRevisionOrReleaseSource() {
        UpdateInstallRules.SourceBinding a = source(7, "stable");
        assertTrue(a.sameAs(source(7, "stable")));
        assertFalse(a.sameAs(source(8, "stable")));
        assertFalse(a.sameAs(source(7, "beta")));
        assertFalse(a.sameAs(UpdateInstallRules.SourceBinding.capture(
            "0123456789abcdefabcd", 7, MANIFEST, "stable", "example-owner",
            "example-repo", "update.json", "stable")));
    }

    @Test
    public void pendingMetadataBindsEveryInstallProperty() {
        UpdateInstallRules.SourceBinding source = source(7, "stable");
        String name = UpdateInstallRules.localApkName(21, APK);
        UpdateInstallRules.PendingMetadata pending = new UpdateInstallRules.PendingMetadata(
            name, MANIFEST, APK, "example.app", 21, "2.1", source.sha256, SIGNERS,
            99L, 123L);

        assertTrue(pending.matchesSource(source));
        assertTrue(pending.matchesValidated(
            name, MANIFEST, APK, "example.app", 21, "2.1", SIGNERS, 99L, 123L));
        assertFalse(pending.matchesValidated(
            name, MANIFEST, APK, "example.app", 22, "2.1", SIGNERS, 99L, 123L));
        assertFalse(pending.matchesValidated(
            name, MANIFEST, APK, "example.app", 21, "2.1", MANIFEST, 99L, 123L));
    }

    @Test
    public void localFileNamesCannotEscapeThePrivateUpdateDirectory() {
        assertTrue(UpdateInstallRules.isSafeApkName("update-21-abcdef.apk"));
        assertFalse(UpdateInstallRules.isSafeApkName("../update.apk"));
        assertFalse(UpdateInstallRules.isSafeApkName("folder/update.apk"));
        assertFalse(UpdateInstallRules.isSafeApkName("update.zip"));
    }

    @Test
    public void signerContinuityRequiresAnInstalledSignerInCandidateHistory() {
        LinkedHashSet<String> installed = new LinkedHashSet<>(Arrays.asList(SIGNERS));
        LinkedHashSet<String> rotatedHistory = new LinkedHashSet<>(Arrays.asList(
            MANIFEST, SIGNERS));
        assertTrue(UpdateInstallRules.hasSignerContinuity(installed, rotatedHistory));
        assertFalse(UpdateInstallRules.hasSignerContinuity(installed,
            new LinkedHashSet<>(Arrays.asList(MANIFEST))));
        installed.add(APK);
        assertFalse(UpdateInstallRules.hasSignerContinuity(installed, rotatedHistory));
        LinkedHashSet<String> exactMultiple = new LinkedHashSet<>(installed);
        assertTrue(UpdateInstallRules.hasSignerContinuity(installed, exactMultiple));
        exactMultiple.add(MANIFEST);
        assertFalse(UpdateInstallRules.hasSignerContinuity(installed, exactMultiple));
    }

    @Test
    public void handoffTokenIsBoundToTheCompletePendingIdentityAndSource() {
        UpdateInstallRules.SourceBinding source = source(7, "stable");
        String name = UpdateInstallRules.localApkName(21, APK);
        UpdateInstallRules.PendingMetadata pending = new UpdateInstallRules.PendingMetadata(
            name, MANIFEST, APK, "example.app", 21, "2.1", source.sha256, SIGNERS,
            99L, 123L);
        String token =
            "3333333333333333333333333333333333333333333333333333333333333333";
        String binding = UpdateInstallRules.handoffBindingSha256(token, source, pending);

        assertTrue(UpdateInstallRules.isValidHandoffToken(token));
        assertTrue(UpdateInstallRules.digestEquals(binding,
            UpdateInstallRules.handoffBindingSha256(token, source, pending)));
        assertNotEquals(binding, UpdateInstallRules.handoffBindingSha256(
            "4444444444444444444444444444444444444444444444444444444444444444",
            source, pending));

        UpdateInstallRules.PendingMetadata changedVersion =
            new UpdateInstallRules.PendingMetadata(
                UpdateInstallRules.localApkName(22, APK), MANIFEST, APK,
                "example.app", 22, "2.2", source.sha256, SIGNERS, 99L, 123L);
        assertNotEquals(binding, UpdateInstallRules.handoffBindingSha256(
            token, source, changedVersion));

        UpdateInstallRules.SourceBinding changedSource = source(8, "stable");
        UpdateInstallRules.PendingMetadata rebound =
            new UpdateInstallRules.PendingMetadata(
                name, MANIFEST, APK, "example.app", 21, "2.1",
                changedSource.sha256, SIGNERS, 99L, 123L);
        assertNotEquals(binding, UpdateInstallRules.handoffBindingSha256(
            token, changedSource, rebound));
    }

    @Test
    public void handoffTokenRejectsShortMixedCaseOrNonHexValues() {
        assertFalse(UpdateInstallRules.isValidHandoffToken("1234"));
        assertFalse(UpdateInstallRules.isValidHandoffToken(
            "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"));
        assertFalse(UpdateInstallRules.isValidHandoffToken(
            "gggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggg"));
    }

    private static UpdateInstallRules.SourceBinding source(int version, String channel) {
        return UpdateInstallRules.SourceBinding.capture(
            "0123456789abcdefabcd", version, PAIR, channel, "example-owner",
            "example-repo", "update.json", channel);
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsNonCanonicalPanelConnection() {
        UpdateInstallRules.SourceBinding.capture("panel-connection", 7, PAIR, "stable",
            "owner", "repo", "update.json", "stable");
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsUnknownChannel() {
        UpdateInstallRules.SourceBinding.capture("0123456789abcdefabcd", 7, PAIR, "nightly",
            "owner", "repo", "update.json", "stable");
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsOwnerPathInjection() {
        UpdateInstallRules.SourceBinding.capture("0123456789abcdefabcd", 7, PAIR, "stable",
            "owner/other", "repo", "update.json", "stable");
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsRepoTraversal() {
        UpdateInstallRules.SourceBinding.capture("0123456789abcdefabcd", 7, PAIR, "stable",
            "owner", "repo..other", "update.json", "stable");
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsManifestPath() {
        UpdateInstallRules.SourceBinding.capture("0123456789abcdefabcd", 7, PAIR, "stable",
            "owner", "repo", "nested/update.json", "stable");
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsReleaseTagPath() {
        UpdateInstallRules.SourceBinding.capture("0123456789abcdefabcd", 7, PAIR, "stable",
            "owner", "repo", "update.json", "refs/tags/stable");
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsManifestControlCharacters() {
        UpdateInstallRules.SourceBinding.capture("0123456789abcdefabcd", 7, PAIR, "stable",
            "owner", "repo", "update\n.json", "stable");
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsOverlongReleaseTag() {
        char[] chars = new char[121];
        Arrays.fill(chars, 'v');
        UpdateInstallRules.SourceBinding.capture("0123456789abcdefabcd", 7, PAIR, "stable",
            "owner", "repo", "update.json", new String(chars));
    }
}
