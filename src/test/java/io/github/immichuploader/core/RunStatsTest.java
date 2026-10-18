package io.github.immichuploader.core;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RunStatsTest {
    @Test
    void duplicateTotalCombinesPrecheckAndUpload() {
        RunStats stats = new RunStats();
        stats.recordPrecheckDuplicates(2);
        stats.recordUploadDuplicate();
        stats.recordUploadDuplicate();
        stats.recordUploadDuplicate();
        assertEquals(5, stats.duplicates());
    }

    @Test
    void recordsUploadAndPairEvents() {
        RunStats stats = new RunStats();
        stats.recordUploaded(10);
        stats.recordUploaded(5);
        stats.recordUploadDuplicate();
        stats.recordPairLinked();
        stats.recordPairAlreadyLinked();
        stats.recordPairConflict();

        assertEquals(2, stats.uploaded());
        assertEquals(15, stats.bytesUploaded());
        assertEquals(1, stats.serverDuplicates());
        assertEquals(1, stats.pairsLinked());
        assertEquals(1, stats.pairsAlreadyLinked());
        assertEquals(1, stats.pairsConflict());
    }

    @Test
    void concurrentUploadsAreCountedWithoutLostUpdates() throws Exception {
        RunStats stats = new RunStats();
        int threads = 8;
        int perThread = 500;
        List<Thread> workers = new ArrayList<>();
        for (int t = 0; t < threads; t++) {
            Thread worker = new Thread(() -> {
                for (int i = 0; i < perThread; i++) {
                    stats.recordUploaded(10);
                }
            });
            workers.add(worker);
            worker.start();
        }
        for (Thread worker : workers) {
            worker.join();
        }

        assertEquals(threads * perThread, stats.uploaded());
        assertEquals(threads * perThread * 10L, stats.bytesUploaded());
    }
}
