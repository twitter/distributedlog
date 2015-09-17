package com.twitter.distributedlog;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import org.apache.commons.codec.binary.Base64;
import org.apache.commons.codec.binary.Hex;

import java.nio.ByteBuffer;

public class DLSN implements Comparable<DLSN> {

    public static final byte VERSION0 = (byte) 0;
    public static final byte VERSION1 = (byte) 1;

    public static final DLSN InitialDLSN = new DLSN(1, 0 , 0);
    public static final DLSN NonInclusiveLowerBound = new DLSN(1, 0 , -1);
    public static final DLSN InvalidDLSN = new DLSN(0,-1,-1);
    static final byte CUR_VERSION = VERSION1;

    static final int VERSION0_LEN = Long.SIZE * 3 + Byte.SIZE;
    static final int VERSION1_LEN = Long.SIZE * 3 / Byte.SIZE + 1;

    private final long logSegmentSequenceNo;
    private final long entryId;
    private final long slotId;

    public DLSN(long logSegmentSequenceNo, long entryId, long slotId) {
        this.logSegmentSequenceNo = logSegmentSequenceNo;
        this.entryId = entryId;
        this.slotId = slotId;
    }

    long getLogSegmentSequenceNo() {
        return logSegmentSequenceNo;
    }

    /**
     * use {@link #getLogSegmentSequenceNo()} instead
     */
    @Deprecated
    long getLedgerSequenceNo() {
        return logSegmentSequenceNo;
    }

    long getEntryId() {
        return entryId;
    }

    long getSlotId() {
        return slotId;
    }

    @Override
    public int compareTo(DLSN that) {
        if (this.logSegmentSequenceNo != that.logSegmentSequenceNo) {
            return (this.logSegmentSequenceNo < that.logSegmentSequenceNo)? -1 : 1;
        } else if (this.entryId != that.entryId) {
            return (this.entryId < that.entryId)? -1 : 1;
        } else {
            return (this.slotId < that.slotId)? -1 : (this.slotId == that.slotId)? 0 : 1;
        }
    }

    public byte[] serializeBytes() {
        return serializeBytes(CUR_VERSION);
    }

    public byte[] serializeBytes(byte version) {
        Preconditions.checkArgument(version <= CUR_VERSION && version >= VERSION0);
        byte[] data = new byte[CUR_VERSION == version ? VERSION1_LEN : VERSION0_LEN];
        ByteBuffer bb = ByteBuffer.wrap(data);
        bb.put(version);
        bb.putLong(logSegmentSequenceNo);
        bb.putLong(entryId);
        bb.putLong(slotId);
        return data;
    }

    public String serialize() {
        return serialize(CUR_VERSION);
    }

    public String serialize(byte version) {
        return Base64.encodeBase64String(serializeBytes(version));
    }

    public static DLSN deserialize(String dlsn) {
        byte[] data = Base64.decodeBase64(dlsn);
        return deserializeBytes(data);
    }

    public static DLSN deserializeBytes(byte[] data) {
        ByteBuffer bb = ByteBuffer.wrap(data);
        byte version = bb.get();
        if (VERSION0 == version) {
            if (VERSION0_LEN != data.length) {
                throw new IllegalArgumentException("Invalid version zero DLSN " + Hex.encodeHexString(data));
            }
        } else if (VERSION1 == version) {
            if (VERSION1_LEN != data.length) {
                throw new IllegalArgumentException("Invalid version one DLSN " + Hex.encodeHexString(data));
            }
        } else {
            throw new IllegalArgumentException("Invalid DLSN : version = "
                    + version + ", " + Hex.encodeHexString(data));
        }
        return new DLSN(bb.getLong(), bb.getLong(), bb.getLong());
    }

    // Keep original version0 logic for testing.
    @VisibleForTesting
    static DLSN deserialize0(String dlsn) {
        byte[] data = Base64.decodeBase64(dlsn);
        ByteBuffer bb = ByteBuffer.wrap(data);
        byte version = bb.get();
        if (VERSION0 != version || VERSION0_LEN != data.length) {
            throw new IllegalArgumentException("Invalid DLSN " + dlsn);
        }
        return new DLSN(bb.getLong(), bb.getLong(), bb.getLong());
    }

    @Override
    public String toString() {
        return "DLSN{" +
            "logSegmentSequenceNo=" + logSegmentSequenceNo +
            ", entryId=" + entryId +
            ", slotId=" + slotId +
            '}';
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof DLSN)) return false;

        DLSN dlsn = (DLSN) o;

        if (entryId != dlsn.entryId) return false;
        if (logSegmentSequenceNo != dlsn.logSegmentSequenceNo) return false;
        if (slotId != dlsn.slotId) return false;

        return true;
    }

    @Override
    public int hashCode() {
        int result = (int) (logSegmentSequenceNo ^ (logSegmentSequenceNo >>> 32));
        result = 31 * result + (int) (entryId ^ (entryId >>> 32));
        result = 31 * result + (int) (slotId ^ (slotId >>> 32));
        return result;
    }

    /**
     * Positions to a DLSN greater than the current value - this may not
     * correspond to an actual LogRecord, its just used by the positioning logic
     * to position the reader
     *
     * @return the next DLSN
     */
    public DLSN getNextDLSN() {
        return new DLSN(logSegmentSequenceNo, entryId, slotId + 1);
    }

    /**
     * Positions to a DLSN greater than the current value - this may not
     * correspond to an actual LogRecord, its just used by the positioning logic
     * to position the reader
     *
     * @return the next DLSN
     */
    public DLSN positionOnTheNextLedger() {
        return new DLSN(logSegmentSequenceNo + 1 , 0, 0);
    }
}
