package com.autoformkit.app.report;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

public class FailureQueueTest {
    @Rule public final TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void transportFailureConsumesEventBeforePostAndCannotReplayIt() throws Exception {
        TestFileStorage storage = storage();
        FailureQueue queue = new FailureQueue(storage);
        assertEquals(true, queue.enqueue(event("11111111", 100L)));
        AtomicInteger postCalls = new AtomicInteger();

        FailureReporter.AttemptOutcome first = FailureReporter.attemptNext(
            queue, () -> true, queued -> {
                postCalls.incrementAndGet();
                return FailureReporter.PostDisposition.STOP;
            });
        FailureReporter.AttemptOutcome second = FailureReporter.attemptNext(
            queue, () -> true, queued -> {
                postCalls.incrementAndGet();
                return FailureReporter.PostDisposition.CONTINUE;
            });

        assertEquals(FailureReporter.AttemptOutcome.POST_STOP, first);
        assertEquals(FailureReporter.AttemptOutcome.EMPTY, second);
        assertEquals(1, postCalls.get());
        assertEquals(0, queue.size());
    }

    @Test
    public void atomicRemovalFailureMakesZeroPostCallsAndLeavesEventEligible() throws Exception {
        TestFileStorage storage = storage();
        FailureQueue queue = new FailureQueue(storage);
        assertEquals(true, queue.enqueue(event("22222222", 200L)));
        storage.failReplacements = true;
        AtomicInteger postCalls = new AtomicInteger();

        FailureReporter.AttemptOutcome failed = FailureReporter.attemptNext(
            queue, () -> true, queued -> {
                postCalls.incrementAndGet();
                return FailureReporter.PostDisposition.CONTINUE;
            });

        assertEquals(FailureReporter.AttemptOutcome.DEQUEUE_FAILED, failed);
        assertEquals(0, postCalls.get());
        assertEquals(1, queue.size());

        storage.failReplacements = false;
        FailureReporter.attemptNext(queue, () -> true, queued -> {
            postCalls.incrementAndGet();
            return FailureReporter.PostDisposition.CONTINUE;
        });
        assertEquals(1, postCalls.get());
        assertEquals(0, queue.size());
    }

    @Test
    public void unverifiableRemovalMakesZeroPostCallsEvenIfBytesWereReplaced() throws Exception {
        TestFileStorage storage = storage();
        FailureQueue queue = new FailureQueue(storage);
        assertEquals(true, queue.enqueue(event("33333333", 300L)));
        storage.corruptVerificationAfterNextReplacement = true;
        AtomicInteger postCalls = new AtomicInteger();

        FailureReporter.AttemptOutcome outcome = FailureReporter.attemptNext(
            queue, () -> true, queued -> {
                postCalls.incrementAndGet();
                return FailureReporter.PostDisposition.CONTINUE;
            });

        assertEquals(FailureReporter.AttemptOutcome.DEQUEUE_FAILED, outcome);
        assertEquals(0, postCalls.get());
    }

    @Test
    public void malformedPhysicalRowIsCleanedWithoutDeletingNextValidEvent() throws Exception {
        TestFileStorage storage = storage();
        FailureEvent first = event("44444444", 400L);
        FailureEvent second = event("55555555", 500L);
        String firstRow = first.toJson().toString();
        String secondRow = second.toJson().toString();
        storage.seed(("{broken-json\n" + firstRow + "\n" + secondRow + "\n")
            .getBytes(StandardCharsets.UTF_8));
        FailureQueue queue = new FailureQueue(storage);

        FailureQueue.DequeueResult cleaned = queue.dequeueForAttempt();
        assertEquals(FailureQueue.DequeueKind.MALFORMED_REMOVED, cleaned.kind);
        assertArrayEquals((firstRow + "\n" + secondRow + "\n")
            .getBytes(StandardCharsets.UTF_8), storage.read());

        FailureQueue.DequeueResult dequeued = queue.dequeueForAttempt();
        assertEquals(FailureQueue.DequeueKind.EVENT, dequeued.kind);
        assertNotNull(dequeued.event);
        assertEquals("44444444", dequeued.event.fingerprint);
        assertArrayEquals((secondRow + "\n").getBytes(StandardCharsets.UTF_8), storage.read());
    }

    @Test
    public void generationChangeAfterDequeueDropsWithoutPostingAcrossPair() throws Exception {
        TestFileStorage storage = storage();
        FailureQueue queue = new FailureQueue(storage);
        assertEquals(true, queue.enqueue(event("66666666", 600L)));
        AtomicLong generation = new AtomicLong(7L);
        AtomicInteger checks = new AtomicInteger();
        AtomicInteger postCalls = new AtomicInteger();

        FailureReporter.AttemptOutcome outcome = FailureReporter.attemptNext(
            queue,
            () -> {
                if (checks.incrementAndGet() == 2) generation.set(8L);
                return generation.get() == 7L;
            },
            queued -> {
                postCalls.incrementAndGet();
                return FailureReporter.PostDisposition.CONTINUE;
            });

        assertEquals(FailureReporter.AttemptOutcome.GENERATION_CHANGED, outcome);
        assertEquals(2, checks.get());
        assertEquals(0, postCalls.get());
        assertEquals(0, queue.size());
    }

    @Test
    public void staleSameConnectionPairFlushCannotDequeueTheNewPairQueue() throws Exception {
        TestFileStorage storage = storage();
        FailureQueue queue = new FailureQueue(storage);
        assertEquals(true, queue.enqueue(event("77777777", 700L)));
        AtomicLong pairGeneration = new AtomicLong(11L);
        AtomicInteger checks = new AtomicInteger();

        // The pair changes after the old row is durably removed but before its POST. Losing this
        // disposable old event is intentional; sending it through either endpoint is forbidden.
        FailureReporter.AttemptOutcome oldAttempt = FailureReporter.attemptNext(
            queue,
            () -> {
                if (checks.incrementAndGet() == 2) pairGeneration.set(12L);
                return pairGeneration.get() == 11L;
            },
            queued -> {
                throw new AssertionError("old pair event must not post");
            });
        assertEquals(FailureReporter.AttemptOutcome.GENERATION_CHANGED, oldAttempt);

        // A new-pair event arrives. A delayed old flush must stop before dequeue and therefore
        // cannot clear or send the new partition.
        assertEquals(true, queue.enqueue(event("88888888", 800L)));
        AtomicInteger postCalls = new AtomicInteger();
        FailureReporter.AttemptOutcome staleFlush = FailureReporter.attemptNext(
            queue, () -> pairGeneration.get() == 11L, queued -> {
                postCalls.incrementAndGet();
                return FailureReporter.PostDisposition.CONTINUE;
            });
        assertEquals(FailureReporter.AttemptOutcome.GENERATION_CHANGED, staleFlush);
        assertEquals(0, postCalls.get());
        assertEquals(1, queue.size());

        FailureReporter.AttemptOutcome currentFlush = FailureReporter.attemptNext(
            queue, () -> pairGeneration.get() == 12L, queued -> {
                assertEquals("88888888", queued.fingerprint);
                postCalls.incrementAndGet();
                return FailureReporter.PostDisposition.CONTINUE;
            });
        assertEquals(FailureReporter.AttemptOutcome.POST_CONTINUE, currentFlush);
        assertEquals(1, postCalls.get());
        assertEquals(0, queue.size());
    }

    private TestFileStorage storage() throws IOException {
        return new TestFileStorage(new File(temporaryFolder.newFolder(), "queue.jsonl"));
    }

    private static FailureEvent event(String fingerprint, long timestampMs) {
        LinkedHashMap<String, String> context = new LinkedHashMap<>();
        context.put("android_sdk", "35");
        context.put("app_version", "1.0.7-test");
        context.put("git_head", "abcdef0");
        context.put("net_active", "wifi");
        context.put("net_validated", "true");
        context.put("net_captive", "false");
        context.put("net_internet", "true");
        context.put("net_not_metered", "true");
        context.put("net_vpn", "false");
        return new FailureEvent("submit", "io_exception", "submit_unit", context,
            timestampMs, fingerprint);
    }

    /** JVM file storage with the same write/sync/atomic-replace contract as AndroidAtomicStorage. */
    private static final class TestFileStorage implements FailureQueue.Storage {
        private final File file;
        boolean failReplacements;
        boolean corruptVerificationAfterNextReplacement;
        private boolean corruptNextRead;

        TestFileStorage(File file) {
            this.file = file;
        }

        void seed(byte[] bytes) throws IOException {
            Files.write(file.toPath(), bytes);
        }

        @Override public boolean hasRecoverableCopy() {
            return file.isFile();
        }

        @Override public byte[] read() throws IOException {
            byte[] value = Files.readAllBytes(file.toPath());
            if (!corruptNextRead) return value;
            corruptNextRead = false;
            byte[] corrupted = new byte[value.length + 1];
            System.arraycopy(value, 0, corrupted, 0, value.length);
            corrupted[corrupted.length - 1] = 'x';
            return corrupted;
        }

        @Override public void replaceAndSync(byte[] bytes) throws IOException {
            if (failReplacements) throw new IOException("injected replacement failure");
            File temp = new File(file.getParentFile(), file.getName() + ".new");
            try (FileOutputStream output = new FileOutputStream(temp, false)) {
                output.write(bytes);
                output.flush();
                output.getFD().sync();
            }
            try {
                Files.move(temp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
            if (corruptVerificationAfterNextReplacement) {
                corruptVerificationAfterNextReplacement = false;
                corruptNextRead = true;
            }
        }
    }
}
