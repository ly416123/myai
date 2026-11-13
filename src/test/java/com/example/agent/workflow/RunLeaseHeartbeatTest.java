package com.example.agent.workflow;

import org.junit.jupiter.api.Test;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RunLeaseHeartbeatTest {
    @Test
    void leaseExpiryIsDetectedEvenWhenStoreRenewalCallIsBlocked() throws Exception {
        CountDownLatch renewEntered = new CountDownLatch(1);
        CountDownLatch allowRenew = new CountDownLatch(1);
        RunStore store = mock(RunStore.class);
        when(store.renew(any(), any(), any())).thenAnswer(invocation -> {
            renewEntered.countDown();
            try {
                allowRenew.await();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return false;
            }
            return true;
        });
        LeaseToken lease = new LeaseToken("heartbeat-blocked", "owner", 1);
        CountDownLatch lostSignal = new CountDownLatch(1);
        RunLeasePolicy policy = new RunLeasePolicy(Duration.ofMillis(180), Duration.ofMillis(30));

        try (var heartbeat = new RunLeaseHeartbeat(store, lease, policy, lostSignal::countDown)) {
            try {
                assertTrue(renewEntered.await(1, TimeUnit.SECONDS));
                assertTrue(lostSignal.await(1, TimeUnit.SECONDS),
                        "local expiry watchdog must not wait behind a blocked store call");
                assertTrue(heartbeat.isLost());
            } finally {
                allowRenew.countDown();
            }
        }
    }

    @Test
    void stopsOnceTheLeaseHasBeenFencedByAnotherOwner() throws Exception {
        InMemoryRunStore store = new InMemoryRunStore();
        store.save(RunRecord.create("heartbeat-fenced", "task", Duration.ofMinutes(1)));
        Instant now = Instant.now();
        LeaseToken stale = store.claim("heartbeat-fenced", "old-owner", now, Duration.ofMillis(150)).orElseThrow();
        LeaseToken current = store.claim("heartbeat-fenced", "new-owner", now.plusSeconds(1),
                Duration.ofSeconds(1)).orElseThrow();
        CountDownLatch lostSignal = new CountDownLatch(1);
        AtomicInteger callbacks = new AtomicInteger();

        try (RunLeaseHeartbeat heartbeat = new RunLeaseHeartbeat(store, stale,
                new RunLeasePolicy(Duration.ofMillis(150), Duration.ofMillis(20)), () -> {
                    callbacks.incrementAndGet();
                    lostSignal.countDown();
                })) {
            assertTrue(lostSignal.await(1, TimeUnit.SECONDS));
            Thread.sleep(80);
            assertTrue(heartbeat.isLost());
        }

        assertEquals(1, callbacks.get(), "lease loss callback must run once");
        assertFalse(store.renew(stale, Instant.now(), Duration.ofSeconds(1)));
        assertTrue(store.renew(current, Instant.now(), Duration.ofSeconds(1)));
    }
}
