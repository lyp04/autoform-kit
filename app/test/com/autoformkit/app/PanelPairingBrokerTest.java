package com.autoformkit.app;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Test;

public class PanelPairingBrokerTest {
    private static final long NOW = 2_000_000_000L;
    private static final String LINK = "com.autoformkit.app://pair/v1?"
        + "panel=https%3A%2F%2Fpanel.example.invalid"
        + "&ticket=EXAMPLE_PAIRING_TICKET_0123456789_ab"
        + "&expires=" + (NOW + 120L);

    @After
    public void reset() {
        PanelPairingBroker.resetForTest();
    }

    @Test
    public void coldDeliveryRequestsMainLaunchAndIsTakenOnce() throws Exception {
        PanelPairingLinkRules.Request request = request();
        assertSame(PanelPairingBroker.LaunchDecision.LAUNCH_MAIN,
            PanelPairingBroker.offer(request, false));

        assertTrue(PanelPairingBroker.mainActivityCreated());
        PanelPairingBroker.Delivery delivery = PanelPairingBroker.take();
        assertSame(request, delivery.request);
        assertFalse(delivery.invalid);
        assertNull(PanelPairingBroker.take());
    }

    @Test
    public void existingMainLetsEntryFinishWithoutReorderingTheTask() throws Exception {
        assertTrue(PanelPairingBroker.mainActivityCreated());
        assertFalse(PanelPairingBroker.mainActivityCreated());
        assertSame(PanelPairingBroker.LaunchDecision.MAIN_EXISTS,
            PanelPairingBroker.offer(request(), false));

        assertTrue(PanelPairingBroker.take().request != null);
        PanelPairingBroker.mainActivityDestroyed();
        assertSame(PanelPairingBroker.LaunchDecision.LAUNCH_MAIN,
            PanelPairingBroker.offer(request(), false));
    }

    @Test
    public void invalidDeliveryCarriesNoRawUriOrTicket() {
        assertSame(PanelPairingBroker.LaunchDecision.LAUNCH_MAIN,
            PanelPairingBroker.offer(null, true));
        PanelPairingBroker.Delivery delivery = PanelPairingBroker.take();
        assertTrue(delivery.invalid);
        assertNull(delivery.request);
    }

    @Test
    public void latestExplicitLinkReplacesAnUnseenPendingLink() throws Exception {
        PanelPairingLinkRules.Request first = request();
        PanelPairingLinkRules.Request second = request();
        PanelPairingBroker.offer(first, false);
        PanelPairingBroker.offer(second, false);

        assertSame(second, PanelPairingBroker.take().request);
    }

    @Test
    public void concurrentColdOffersElectOnlyOneLauncher() throws Exception {
        assertSame(PanelPairingBroker.LaunchDecision.LAUNCH_MAIN,
            PanelPairingBroker.offer(request(), false));
        assertSame(PanelPairingBroker.LaunchDecision.LAUNCH_PENDING,
            PanelPairingBroker.offer(request(), false));

        PanelPairingBroker.releaseLaunchReservation();
        assertSame(PanelPairingBroker.LaunchDecision.LAUNCH_MAIN,
            PanelPairingBroker.offer(request(), false));
    }

    private static PanelPairingLinkRules.Request request() throws Exception {
        return PanelPairingLinkRules.parse(LINK, "com.autoformkit.app", NOW);
    }
}
