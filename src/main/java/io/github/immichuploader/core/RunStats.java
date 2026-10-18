package io.github.immichuploader.core;

/**
 * Thread-safe, single source of run statistics. All writes go through the
 * synchronized record* methods; readers use the getters or one atomic snapshot.
 */
public class RunStats {
    private int visitedFiles;
    private int scanned;
    private int imageCount;
    private int videoCount;
    private int scanIssues;
    private int resumeSkipped;
    private int skipped;
    private int stale;
    private int hashed;
    private int precheckDuplicates;
    private int uploaded;
    private int serverDuplicates;
    private int failed;
    private int precheckErrors;
    private long bytesHashed;
    private long bytesUploaded;

    private int pairsDetected;
    private int pairsSkipped;
    private int pairsLinked;
    private int pairsAlreadyLinked;
    private int pairsConflict;

    public synchronized void recordScan(
            int visitedFiles, int scanned, int imageCount, int videoCount,
            int scanIssues, int pairsDetected) {
        this.visitedFiles = visitedFiles;
        this.scanned = scanned;
        this.imageCount = imageCount;
        this.videoCount = videoCount;
        this.scanIssues = scanIssues;
        this.pairsDetected = pairsDetected;
    }

    public synchronized void recordSkip() {
        skipped++;
    }

    public synchronized void recordResumeSkip(int count) {
        resumeSkipped += Math.max(0, count);
    }

    public synchronized void recordStale() {
        recordStale(1);
    }

    public synchronized void recordStale(int count) {
        stale += Math.max(0, count);
    }

    public synchronized void recordHashed(long bytes) {
        recordHashed(1, bytes);
    }

    public synchronized void recordHashed(int count, long bytes) {
        hashed += Math.max(0, count);
        bytesHashed += Math.max(0, bytes);
    }

    public synchronized void recordPrecheckDuplicates(int count) {
        precheckDuplicates += Math.max(0, count);
    }

    public synchronized void recordPrecheckErrors(int count) {
        precheckErrors += Math.max(0, count);
    }

    public synchronized void recordUploaded(long bytes) {
        recordUploaded(1, bytes);
    }

    public synchronized void recordUploaded(int count, long bytes) {
        uploaded += Math.max(0, count);
        bytesUploaded += Math.max(0, bytes);
    }

    public synchronized void recordUploadDuplicate() {
        recordUploadDuplicates(1);
    }

    public synchronized void recordUploadDuplicates(int count) {
        serverDuplicates += Math.max(0, count);
    }

    public synchronized void recordFailure() {
        failed++;
    }

    public synchronized void recordPairSkipped() {
        recordPairSkipped(1);
    }

    public synchronized void recordPairSkipped(int count) {
        pairsSkipped += Math.max(0, count);
    }

    public synchronized void recordPairLinked() {
        recordPairLinked(1);
    }

    public synchronized void recordPairLinked(int count) {
        pairsLinked += Math.max(0, count);
    }

    public synchronized void recordPairAlreadyLinked() {
        recordPairAlreadyLinked(1);
    }

    public synchronized void recordPairAlreadyLinked(int count) {
        pairsAlreadyLinked += Math.max(0, count);
    }

    public synchronized void recordPairConflict() {
        recordPairConflict(1);
    }

    public synchronized void recordPairConflict(int count) {
        pairsConflict += Math.max(0, count);
    }

    public synchronized int duplicates() {
        return precheckDuplicates + serverDuplicates;
    }

    public synchronized int visitedFiles() {
        return visitedFiles;
    }

    public synchronized int scanned() {
        return scanned;
    }

    public synchronized int imageCount() {
        return imageCount;
    }

    public synchronized int videoCount() {
        return videoCount;
    }

    public synchronized int scanIssues() {
        return scanIssues;
    }

    public synchronized int resumeSkipped() {
        return resumeSkipped;
    }

    public synchronized int skipped() {
        return skipped;
    }

    public synchronized int stale() {
        return stale;
    }

    public synchronized int hashed() {
        return hashed;
    }

    public synchronized int precheckDuplicates() {
        return precheckDuplicates;
    }

    public synchronized int uploaded() {
        return uploaded;
    }

    public synchronized int serverDuplicates() {
        return serverDuplicates;
    }

    public synchronized int failed() {
        return failed;
    }

    public synchronized int precheckErrors() {
        return precheckErrors;
    }

    public synchronized long bytesHashed() {
        return bytesHashed;
    }

    public synchronized long bytesUploaded() {
        return bytesUploaded;
    }

    public synchronized int pairsDetected() {
        return pairsDetected;
    }

    public synchronized int pairsSkipped() {
        return pairsSkipped;
    }

    public synchronized int pairsLinked() {
        return pairsLinked;
    }

    public synchronized int pairsAlreadyLinked() {
        return pairsAlreadyLinked;
    }

    public synchronized int pairsConflict() {
        return pairsConflict;
    }

    public synchronized Snapshot snapshot() {
        return new Snapshot(
                visitedFiles, scanned, imageCount, videoCount, scanIssues,
                resumeSkipped, skipped, stale, hashed,
                precheckDuplicates, uploaded, serverDuplicates,
                failed, precheckErrors, bytesHashed, bytesUploaded,
                pairsDetected, pairsSkipped, pairsLinked, pairsAlreadyLinked, pairsConflict
        );
    }

    public record Snapshot(
            int visitedFiles, int scanned, int imageCount, int videoCount, int scanIssues,
            int resumeSkipped, int skipped, int stale, int hashed,
            int precheckDuplicates, int uploaded, int serverDuplicates,
            int failed, int precheckErrors, long bytesHashed, long bytesUploaded,
            int pairsDetected, int pairsSkipped, int pairsLinked,
            int pairsAlreadyLinked, int pairsConflict
    ) {
        public int duplicates() {
            return precheckDuplicates + serverDuplicates;
        }
    }
}
