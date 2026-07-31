package com.autoformkit.app;

import java.util.Collection;
import java.util.Collections;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Pure, finite continuation lease for an unsafe staged Panel candidate.
 *
 * <p>The active config/catalog pair is immutable, so work that already belongs to that exact pair
 * may finish.  A page-open boolean (or a later observation that fields became non-empty) is not
 * proof of existing work: only unit sequence numbers that existed at the barrier, an explicit
 * alternate-entry token created by a pre-barrier mutation, exact one-shot activity reservations,
 * and workers explicitly authorized for those records are retained.</p>
 */
final class UnsafeCandidateContinuationRules {
    static final class AlternateReservation {
        final String reservationToken;
        final String resultContinuationToken;

        private AlternateReservation(String reservationToken,
                                     String resultContinuationToken) {
            this.reservationToken = clean(reservationToken)
                .toLowerCase(java.util.Locale.US);
            this.resultContinuationToken = clean(resultContinuationToken)
                .toLowerCase(java.util.Locale.US);
        }
    }

    static final class Lease {
        final String connectionNamespace;
        final int catalogVersion;
        final String panelPairSha256;
        final Set<Integer> mainUnitSequences;
        final String alternateEntryToken;
        final Set<String> alternateReservationTokens;
        final Map<String, String> alternateReservationResults;
        final boolean mainWorkerAuthorized;
        final boolean printWorkerAuthorized;
        final boolean alternateWorkerAuthorized;

        private Lease(String connectionNamespace, int catalogVersion,
                      String panelPairSha256, Collection<Integer> mainUnitSequences,
                      String alternateEntryToken,
                      Collection<AlternateReservation> alternateReservations,
                      boolean mainWorkerAuthorized, boolean printWorkerAuthorized,
                      boolean alternateWorkerAuthorized) {
            this.connectionNamespace = clean(connectionNamespace);
            this.catalogVersion = catalogVersion;
            this.panelPairSha256 = clean(panelPairSha256).toLowerCase(java.util.Locale.US);
            LinkedHashSet<Integer> sequences = new LinkedHashSet<>();
            if (mainUnitSequences != null) {
                for (Integer sequence : mainUnitSequences) {
                    if (sequence != null && sequence > 0) sequences.add(sequence);
                }
            }
            this.mainUnitSequences = Collections.unmodifiableSet(sequences);
            this.alternateEntryToken = validAlternateEntryToken(alternateEntryToken)
                ? clean(alternateEntryToken).toLowerCase(java.util.Locale.US) : "";
            LinkedHashMap<String, String> reservations = new LinkedHashMap<>();
            LinkedHashSet<String> conflicts = new LinkedHashSet<>();
            if (alternateReservations != null) {
                for (AlternateReservation reservation : alternateReservations) {
                    if (reservation == null
                            || !validAlternateEntryToken(reservation.reservationToken)
                            || !validAlternateEntryToken(
                                reservation.resultContinuationToken)) continue;
                    String token = reservation.reservationToken;
                    String existingResult = reservations.get(token);
                    if (existingResult != null
                            && !existingResult.equals(
                                reservation.resultContinuationToken)) {
                        conflicts.add(token);
                        reservations.remove(token);
                    } else if (!conflicts.contains(token)) {
                        reservations.put(token, reservation.resultContinuationToken);
                    }
                }
            }
            this.alternateReservationResults = Collections.unmodifiableMap(reservations);
            this.alternateReservationTokens = Collections.unmodifiableSet(
                new LinkedHashSet<>(reservations.keySet()));
            this.mainWorkerAuthorized = mainWorkerAuthorized;
            this.printWorkerAuthorized = printWorkerAuthorized;
            this.alternateWorkerAuthorized = alternateWorkerAuthorized;
        }
    }

    private UnsafeCandidateContinuationRules() {}

    static AlternateReservation alternateReservation(String reservationToken,
                                                     String resultContinuationToken) {
        return new AlternateReservation(reservationToken, resultContinuationToken);
    }

    static Lease capture(String connectionNamespace, int catalogVersion,
                         String panelPairSha256, Collection<Integer> mainUnitSequences,
                         String alternateEntryToken,
                         Collection<AlternateReservation> alternateReservations,
                         boolean mainWorkerActive, boolean printWorkerActive,
                         boolean alternateWorkerActive) {
        return new Lease(connectionNamespace, catalogVersion, panelPairSha256,
            mainUnitSequences, alternateEntryToken, alternateReservations, mainWorkerActive,
            printWorkerActive, alternateWorkerActive);
    }

    static boolean matches(Lease lease, String connectionNamespace, int catalogVersion,
                           String panelPairSha256) {
        return lease != null
            && lease.catalogVersion > 0
            && lease.panelPairSha256.matches("[0-9a-f]{64}")
            && lease.connectionNamespace.equals(clean(connectionNamespace))
            && lease.catalogVersion == catalogVersion
            && lease.panelPairSha256.equals(
                clean(panelPairSha256).toLowerCase(java.util.Locale.US));
    }

    static boolean hasAllowedMainUnit(Lease lease, Collection<Integer> liveUnitSequences,
                                      String connectionNamespace, int catalogVersion,
                                      String panelPairSha256) {
        if (!matches(lease, connectionNamespace, catalogVersion, panelPairSha256)
                || liveUnitSequences == null || liveUnitSequences.isEmpty()) return false;
        for (Integer sequence : liveUnitSequences) {
            if (sequence != null && lease.mainUnitSequences.contains(sequence)) return true;
        }
        return false;
    }

    static boolean permitsMainUnit(Lease lease, int unitSequence,
                                   String connectionNamespace, int catalogVersion,
                                   String panelPairSha256) {
        return unitSequence > 0
            && matches(lease, connectionNamespace, catalogVersion, panelPairSha256)
            && lease.mainUnitSequences.contains(unitSequence);
    }

    static boolean permitsAlternateEntry(Lease lease, String liveAlternateEntryToken,
                                         String connectionNamespace, int catalogVersion,
                                         String panelPairSha256) {
        return matches(lease, connectionNamespace, catalogVersion, panelPairSha256)
            && validAlternateEntryToken(liveAlternateEntryToken)
            && lease.alternateEntryToken.equals(
                clean(liveAlternateEntryToken).toLowerCase(java.util.Locale.US));
    }

    static boolean permitsAlternateReservation(Lease lease, String reservationToken,
                                                String connectionNamespace, int catalogVersion,
                                                String panelPairSha256) {
        String token = clean(reservationToken).toLowerCase(java.util.Locale.US);
        return validAlternateEntryToken(token)
            && matches(lease, connectionNamespace, catalogVersion, panelPairSha256)
            && lease.alternateReservationTokens.contains(token);
    }

    static boolean hasAllowedAlternateReservation(
            Lease lease, Collection<String> liveReservationTokens,
            String connectionNamespace, int catalogVersion, String panelPairSha256) {
        if (!matches(lease, connectionNamespace, catalogVersion, panelPairSha256)
                || liveReservationTokens == null || liveReservationTokens.isEmpty()) {
            return false;
        }
        for (String token : liveReservationTokens) {
            if (permitsAlternateReservation(lease, token, connectionNamespace,
                    catalogVersion, panelPairSha256)) return true;
        }
        return false;
    }

    static boolean permitsCurrentWork(Lease lease, Collection<Integer> liveUnitSequences,
                                      boolean mainFormOpen, boolean mainWorkerActive,
                                      boolean printWorkerActive, boolean alternateEntryActive,
                                      boolean alternateWorkerActive,
                                      String liveAlternateEntryToken,
                                      Collection<String> liveAlternateReservationTokens,
                                      String connectionNamespace, int catalogVersion,
                                      String panelPairSha256) {
        if (!matches(lease, connectionNamespace, catalogVersion, panelPairSha256)) return false;
        boolean allowedMainUnit = hasAllowedMainUnit(lease, liveUnitSequences,
            connectionNamespace, catalogVersion, panelPairSha256);
        return (mainFormOpen && allowedMainUnit)
            || (mainWorkerActive && lease.mainWorkerAuthorized)
            || (printWorkerActive && lease.printWorkerAuthorized)
            || (alternateEntryActive && permitsAlternateEntry(lease,
                liveAlternateEntryToken, connectionNamespace, catalogVersion,
                panelPairSha256))
            || hasAllowedAlternateReservation(lease, liveAlternateReservationTokens,
                connectionNamespace, catalogVersion, panelPairSha256)
            || (alternateWorkerActive && lease.alternateWorkerAuthorized);
    }

    static Lease authorizeMainWorker(Lease lease, Collection<Integer> liveUnitSequences,
                                     String connectionNamespace, int catalogVersion,
                                     String panelPairSha256) {
        if (!hasAllowedMainUnit(lease, liveUnitSequences, connectionNamespace,
                catalogVersion, panelPairSha256)) return null;
        return new Lease(lease.connectionNamespace, lease.catalogVersion,
            lease.panelPairSha256, lease.mainUnitSequences, lease.alternateEntryToken,
            copyAlternateReservations(lease),
            true, lease.printWorkerAuthorized, lease.alternateWorkerAuthorized);
    }

    static Lease authorizeAlternateWorker(Lease lease, String liveAlternateEntryToken,
                                          String connectionNamespace, int catalogVersion,
                                          String panelPairSha256) {
        if (!permitsAlternateEntry(lease, liveAlternateEntryToken,
                connectionNamespace, catalogVersion, panelPairSha256)) return null;
        return new Lease(lease.connectionNamespace, lease.catalogVersion,
            lease.panelPairSha256, lease.mainUnitSequences, lease.alternateEntryToken,
            copyAlternateReservations(lease),
            lease.mainWorkerAuthorized, lease.printWorkerAuthorized, true);
    }

    /**
     * Consumes one exact pre-barrier activity reservation and promotes only its preallocated result
     * token into the alternate-entry draft lease. A result token cannot be invented at callback
     * time, and the same reservation cannot be used twice.
     */
    static Lease consumeAlternateReservation(
            Lease lease, String reservationToken, String resultContinuationToken,
            String connectionNamespace, int catalogVersion, String panelPairSha256) {
        if (!permitsAlternateReservation(lease, reservationToken, connectionNamespace,
                catalogVersion, panelPairSha256)
                || !validAlternateEntryToken(resultContinuationToken)) return null;
        String reservation = clean(reservationToken).toLowerCase(java.util.Locale.US);
        String result = clean(resultContinuationToken).toLowerCase(java.util.Locale.US);
        if (!result.equals(lease.alternateReservationResults.get(reservation))) return null;
        if (!lease.alternateEntryToken.isEmpty()
                && !lease.alternateEntryToken.equals(result)) return null;
        List<AlternateReservation> remaining = copyAlternateReservations(lease);
        for (int index = remaining.size() - 1; index >= 0; index--) {
            if (remaining.get(index).reservationToken.equals(reservation)) {
                remaining.remove(index);
            }
        }
        return new Lease(lease.connectionNamespace, lease.catalogVersion,
            lease.panelPairSha256, lease.mainUnitSequences, result, remaining,
            lease.mainWorkerAuthorized, lease.printWorkerAuthorized,
            lease.alternateWorkerAuthorized);
    }

    private static List<AlternateReservation> copyAlternateReservations(Lease lease) {
        List<AlternateReservation> reservations = new ArrayList<>();
        if (lease == null) return reservations;
        for (Map.Entry<String, String> entry
                : lease.alternateReservationResults.entrySet()) {
            reservations.add(alternateReservation(entry.getKey(), entry.getValue()));
        }
        return reservations;
    }

    static boolean validAlternateEntryToken(String value) {
        String token = clean(value).toLowerCase(java.util.Locale.US);
        return token.matches("[0-9a-f]{32}");
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }
}
