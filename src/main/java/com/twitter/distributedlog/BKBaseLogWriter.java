package com.twitter.distributedlog;

import com.google.common.annotations.VisibleForTesting;
import com.twitter.distributedlog.util.Pair;
import org.apache.bookkeeper.client.BKException;
import org.apache.bookkeeper.proto.BookkeeperInternalCallbacks;
import org.apache.zookeeper.Watcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Collection;
import java.util.Map;
import java.util.concurrent.CountDownLatch;

public abstract class BKBaseLogWriter {
    static final Logger LOG = LoggerFactory.getLogger(BKBaseLogWriter.class);

    private final BKDistributedLogManager bkDistributedLogManager;
    private final long retentionPeriodInMillis;
    // Used by tests
    private Long minTimestampToKeepOverride = null;
    private boolean closed = false;
    private boolean forceRolling = false;
    private boolean forceRecovery = false;
    private LogTruncationTask lastTruncationAttempt = null;
    private Watcher sessionExpireWatcher = null;

    public BKBaseLogWriter(DistributedLogConfiguration conf, BKDistributedLogManager bkdlm) {
        this.bkDistributedLogManager = bkdlm;
        this.retentionPeriodInMillis = (long) (conf.getRetentionPeriodHours()) * 3600 * 1000;
        LOG.info("Retention Period {}", retentionPeriodInMillis);
    }

    abstract protected BKLogPartitionWriteHandler getCachedPartitionHandler(String streamIdentifier);

    abstract protected void cachePartitionHandler(String streamIdentifier, BKLogPartitionWriteHandler ledgerHandler);

    abstract protected BKLogPartitionWriteHandler removeCachedPartitionHandler(String streamIdentifier);

    abstract protected Collection<BKLogPartitionWriteHandler> getCachedPartitionHandlers();

    abstract protected BKPerStreamLogWriter getCachedLogWriter(String streamIdentifier);

    abstract protected void cacheLogWriter(String streamIdentifier, BKPerStreamLogWriter logWriter);

    abstract protected BKPerStreamLogWriter removeCachedLogWriter(String streamIdentifier);

    abstract protected Collection<BKPerStreamLogWriter> getCachedLogWriters();


    /**
     * Close the journal.
     *
     * @throws java.io.IOException if the log stream can't be closed,
     */
    public void close() throws IOException {
        closed = true;
        try {
            waitForTruncation();
            for (BKPerStreamLogWriter writer : getCachedLogWriters()) {
                try {
                    writer.close();
                } catch (IOException ioe) {
                    LOG.error("Failed to close per stream writer : ", ioe);
                }
            }
            for (BKLogPartitionWriteHandler partitionWriteHandler : getCachedPartitionHandlers()) {
                try {
                    partitionWriteHandler.close();
                } catch (IOException ioe) {
                    LOG.error("Failed to close writer handler : ", ioe);
                }
            }
        } finally {
            bkDistributedLogManager.unregister(sessionExpireWatcher);
        }
    }

    synchronized protected BKLogPartitionWriteHandler getWriteLedgerHandler(PartitionId partition, boolean recover) throws IOException {
        return getWriteLedgerHandler(partition.toString(), recover);
    }

    synchronized protected BKLogPartitionWriteHandler getWriteLedgerHandler(String streamIdentifier, boolean recover) throws IOException {
        BKLogPartitionWriteHandler ledgerManager = getCachedPartitionHandler(streamIdentifier);
        if (null == ledgerManager) {
            ledgerManager = bkDistributedLogManager.createWriteLedgerHandler(streamIdentifier);
            cachePartitionHandler(streamIdentifier, ledgerManager);
        }
        if (recover) {
            ledgerManager.recoverIncompleteLogSegments();
        }
        return ledgerManager;
    }

    synchronized protected BKPerStreamLogWriter getLedgerWriter(PartitionId partition, long startTxId) throws IOException {
        return getLedgerWriter(partition.toString(), startTxId);
    }

    synchronized protected BKPerStreamLogWriter getLedgerWriter(String streamIdentifier, long startTxId) throws IOException {
        BKPerStreamLogWriter ledgerWriter = getCachedLogWriter(streamIdentifier);
        long numFlushes = 0;
        boolean shouldCheckForTruncation = false;

        // Handle the case where the last call to write actually caused an error in the partition
        //
        if ((null != ledgerWriter) && (ledgerWriter.isStreamInError() || forceRecovery)) {
            // Close the ledger writer so that we will recover and start a new log segment
            numFlushes = ledgerWriter.getNumFlushes();
            ledgerWriter.close();
            ledgerWriter = null;
            removeCachedLogWriter(streamIdentifier);

            // This is strictly not necessary - but its safe nevertheless
            BKLogPartitionWriteHandler ledgerManager = removeCachedPartitionHandler(streamIdentifier);
            if (null != ledgerManager) {
                ledgerManager.close();
            }
        }


        if (null == ledgerWriter) {
            ledgerWriter = getWriteLedgerHandler(streamIdentifier, true).startLogSegment(startTxId);
            ledgerWriter.setNumFlushes(numFlushes);
            cacheLogWriter(streamIdentifier, ledgerWriter);
            shouldCheckForTruncation = true;
        }

        BKLogPartitionWriteHandler ledgerManager = getWriteLedgerHandler(streamIdentifier, false);
        if (ledgerManager.shouldStartNewSegment() || forceRolling) {
            Pair<Long, DLSN> lastPoint = ledgerWriter.closeToFinalize();
            numFlushes = ledgerWriter.getNumFlushes();
            ledgerManager.completeAndCloseLogSegment(lastPoint.getFirst(), lastPoint.getLast().getEntryId(), lastPoint.getLast().getSlotId());
            ledgerWriter = ledgerManager.startLogSegment(startTxId);
            ledgerWriter.setNumFlushes(numFlushes);
            cacheLogWriter(streamIdentifier, ledgerWriter);
            shouldCheckForTruncation = true;
        }

        if (shouldCheckForTruncation) {
            boolean truncationEnabled = false;

            long minTimestampToKeep = 0;
            long sanityCheckThreshold = 0;

            if (retentionPeriodInMillis > 0) {
                minTimestampToKeep = Utils.nowInMillis() - retentionPeriodInMillis;
                sanityCheckThreshold = Utils.nowInMillis() - 2 * retentionPeriodInMillis;
                truncationEnabled = true;
            }

            if (null != minTimestampToKeepOverride) {
                minTimestampToKeep = minTimestampToKeepOverride;
                truncationEnabled = true;
            }

            // skip scheduling if there is task that's already running
            //
            if (truncationEnabled && ((lastTruncationAttempt == null) || lastTruncationAttempt.isDone())) {
                LogTruncationTask truncationTask = new LogTruncationTask(ledgerManager,
                        minTimestampToKeep,
                        sanityCheckThreshold);
                if (bkDistributedLogManager.scheduleTask(truncationTask)) {
                    lastTruncationAttempt = truncationTask;
                }
            }
        }

        return ledgerWriter;
    }

    protected void checkClosedOrInError(String operation) throws AlreadyClosedException {
        if (closed) {
            LOG.error("Executing " + operation + " on already closed Log Writer");
            throw new AlreadyClosedException("Executing " + operation + " on already closed Log Writer");
        }
    }

    static class LogTruncationTask implements Runnable, BookkeeperInternalCallbacks.GenericCallback<Void> {
        private final BKLogPartitionWriteHandler ledgerManager;
        private final long minTimestampToKeep;
        private final long sanityCheckThreshold;
        private volatile boolean done = false;
        private volatile boolean running = false;
        private final CountDownLatch latch = new CountDownLatch(1);

        LogTruncationTask(BKLogPartitionWriteHandler ledgerManager, long minTimestampToKeep, long sanityCheckThreshold) {
            this.ledgerManager = ledgerManager;
            this.minTimestampToKeep = minTimestampToKeep;
            this.sanityCheckThreshold = sanityCheckThreshold;
        }

        boolean isDone() {
            return done;
        }

        @Override
        public void run() {
            if (LOG.isTraceEnabled()) {
                LOG.trace("Issue purge request to purge logs older than {} for {}.", minTimestampToKeep, ledgerManager.getFullyQualifiedName());
            }
            running = true;
            ledgerManager.purgeLogsOlderThanTimestamp(minTimestampToKeep, sanityCheckThreshold, this);
        }

        public void waitForCompletion() throws InterruptedException {
            if (running) {
                latch.await();
            }
        }

        @Override
        public void operationComplete(int rc, Void result) {
            if (BKException.Code.OK != rc) {
                LOG.warn("Log Truncation Failed with exception : ", BKException.create(rc));
            }
            done = true;
            running = false;
            latch.countDown();
        }

        @Override
        public String toString() {
            return String.format("LogTruncationTask (%s : minTimestampToKeep=%d, sanityCheckThreshold=%d, running=%s, done=%s)",
                    ledgerManager.getFullyQualifiedName(), minTimestampToKeep, sanityCheckThreshold, running, done);
        }
    }

    /**
     * All data that has been written to the stream so far will be flushed.
     * New data can be still written to the stream while flush is ongoing.
     */
    public long setReadyToFlush() throws IOException {
        checkClosedOrInError("setReadyToFlush");
        long highestTransactionId = 0;
        for (BKPerStreamLogWriter writer : getCachedLogWriters()) {
            highestTransactionId = Math.max(highestTransactionId, writer.setReadyToFlush());
        }
        return highestTransactionId;
    }

    /**
     * Flush and sync all data that is ready to be flush
     * {@link #setReadyToFlush()} into underlying persistent store.
     * <p/>
     * This API is optional as the writer implements a policy for automatically syncing
     * the log records in the buffer. The buffered edits can be flushed when the buffer
     * becomes full or a certain period of time is elapsed.
     */
    public long flushAndSync() throws IOException {
        checkClosedOrInError("flushAndSync");
        long highestTransactionId = 0;
        Collection<BKPerStreamLogWriter> writerSet = getCachedLogWriters();
        for (BKPerStreamLogWriter writer : writerSet) {
            writer.flushAndSyncPhaseOne();
        }
        for (BKPerStreamLogWriter writer : writerSet) {
            highestTransactionId = Math.max(highestTransactionId, writer.flushAndSyncPhaseTwo());
        }
        return highestTransactionId;
    }

    public long flushAndSync(boolean parallel, boolean waitForVisibility) throws IOException {
        checkClosedOrInError("flushAndSync");

        LOG.info("FlushAndSync Started");

        long highestTransactionId = 0;
        long totalFlushes = 0;
        int minTransmitSize = Integer.MAX_VALUE;
        int maxTransmitSize = Integer.MIN_VALUE;
        long totalAddConfirmed = 0;

        Collection<BKPerStreamLogWriter> writerSet = getCachedLogWriters();

        if (parallel || !waitForVisibility) {
            for(BKPerStreamLogWriter writer : writerSet) {
                highestTransactionId = Math.max(highestTransactionId, writer.flushAndSyncPhaseOne());
            }
        }

        for(BKPerStreamLogWriter writer : writerSet) {
            if (waitForVisibility) {
                if (parallel) {
                    highestTransactionId = Math.max(highestTransactionId, writer.flushAndSyncPhaseTwo());
                } else {
                    highestTransactionId = Math.max(highestTransactionId, writer.flushAndSync());
                }
            }
            totalFlushes += writer.getNumFlushes();
            minTransmitSize = Math.min(minTransmitSize, writer.getAverageTransmitSize());
            maxTransmitSize = Math.max(maxTransmitSize, writer.getAverageTransmitSize());
            totalAddConfirmed += writer.getLastAddConfirmed();
        }

        if (writerSet.size() > 0) {
            LOG.info("FlushAndSync Completed with {} flushes and add Confirmed {}", totalFlushes, totalAddConfirmed);
            LOG.info("Transmission Size Min {} Max {}", minTransmitSize, maxTransmitSize);
        } else {
            LOG.info("FlushAndSync Completed - Nothing to Flush");
        }
        return highestTransactionId;
    }

    @VisibleForTesting
    public synchronized void setForceRolling(boolean forceRolling) {
        this.forceRolling = forceRolling;
    }

    @VisibleForTesting
    public synchronized void overRideMinTimeStampToKeep(Long minTimestampToKeepOverride) {
        this.minTimestampToKeepOverride = minTimestampToKeepOverride;
    }

    protected synchronized void waitForTruncation() {
        try {
            if (null != lastTruncationAttempt) {
                lastTruncationAttempt.waitForCompletion();
            }
        } catch (Exception exc) {
            LOG.info("Wait For truncation failed", exc);
        }
    }

    @VisibleForTesting
    public synchronized void setForceRecovery(boolean forceRecovery) {
        this.forceRecovery = forceRecovery;
    }

}
