package com.twitter.distributedlog;

import java.io.IOException;
import java.util.List;


public class BKUnPartitionedSyncLogWriter extends BKUnPartitionedLogWriterBase implements LogWriter {

    public BKUnPartitionedSyncLogWriter(DistributedLogConfiguration conf, BKDistributedLogManager bkdlm) {
        super(conf, bkdlm);
    }
    /**
     * Write log records to the stream.
     *
     * @param record operation
     */
    @Override
    public void write(LogRecord record) throws IOException {
        getLedgerWriter(conf.getUnpartitionedStreamName(), record.getTransactionId(), false).write(record);
    }

    /**
     * Write edits logs operation to the stream.
     *
     * @param records list of records
     */
    @Override
    public int writeBulk(List<LogRecord> records) throws IOException {
        return getLedgerWriter(conf.getUnpartitionedStreamName(),
                records.get(0).getTransactionId(), false).writeBulk(records);
    }

    /**
     * Flushes all the data up to this point,
     * adds the end of stream marker and marks the stream
     * as read-only in the metadata. No appends to the
     * stream will be allowed after this point
     */
    @Override
    public void markEndOfStream() throws IOException {
        getLedgerWriter(conf.getUnpartitionedStreamName(),
            DistributedLogConstants.MAX_TXID, true).markEndOfStream();
        closeAndComplete();
    }

    /**
     * Close the stream without necessarily flushing immediately.
     * This may be called if the stream is in error such as after a
     * previous write or close threw an exception.
     */
    @Override
    public void abort() throws IOException {
        super.abort();
    }
}
