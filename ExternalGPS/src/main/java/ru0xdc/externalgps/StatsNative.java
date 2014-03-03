package ru0xdc.externalgps;

import proguard.annotation.Keep;
import proguard.annotation.KeepClassMemberNames;
import proguard.annotation.KeepName;

@KeepName
public class StatsNative {

    private long mStartTs;

    private long mReceivedBytes;
    private long mReceivedJunk;
    private long mLastReceivedByteTs;

    private long mNmeaLastMsgTs;
    private long mNmeaTotal;
    private long mNmeaGga;
    private long mNmeaRmc;
    private long mNmeaGll;
    private long mNmeaGst;
    private long mNmeaGsa;
    private long mNmeaVtg;
    private long mNmeaZda;
    private long mNmeaGsv;
    private long mNmeaPubx;
    private long mNmeaOther;

    private long mSirfLastMsgTs;
    private long mSirfTotal;
    private long mSirfMid41;

    private long mUbloxLastMsgTs;
    private long mUbloxTotal;

    public StatsNative() {
    }

    public StatsNative(final StatsNative src) {
        set(src);
    }

    public long getStartTs() {
        return mStartTs;
    }

    public long getReceivedBytes() {
        return mReceivedBytes;
    }

    public long getReceivedJunk() {
        return mReceivedJunk;
    }

    public long getLastReceivedByteTs() {
        return mLastReceivedByteTs;
    }

    public long getLastValidMsgTs() {
        return Math.max(Math.max(mUbloxLastMsgTs, mSirfLastMsgTs), mNmeaLastMsgTs);
    }

    public long getValidMsgCount() {
        return mNmeaTotal + mSirfTotal + mUbloxTotal;
    }

    public synchronized void set(final StatsNative src) {
        setStats(src.mStartTs, src.mLastReceivedByteTs, src.mReceivedBytes, src.mReceivedJunk);
        setNmeaStats(mNmeaLastMsgTs, mNmeaTotal, mNmeaGga, mNmeaRmc, mNmeaGll, mNmeaGst,
                mNmeaGsa, mNmeaVtg, mNmeaZda, mNmeaGsv, mNmeaPubx, mNmeaOther);
        setSirfStats(mSirfLastMsgTs, mSirfTotal, mSirfMid41);
        setUbloxStats(mUbloxLastMsgTs, mUbloxTotal);
    }

    // used by native code
    @Keep
    void setStats(long startTs, long lastByteTs, long rcvdBytes, long rcvdJunk) {
        mStartTs = startTs;
        mLastReceivedByteTs = lastByteTs;
        mReceivedBytes = rcvdBytes;
        mReceivedJunk = rcvdJunk;
    }

    // used by native code
    @Keep
    void setNmeaStats(long lastMsgTs, long total, long gga,
            long rmc, long gll, long gst, long gsa, long vtg, long zda, long gsv,
            long pubx, long other) {
        mNmeaLastMsgTs = lastMsgTs;
        mNmeaTotal = total;
        mNmeaRmc = rmc;
        mNmeaGll = gll;
        mNmeaGst = gst;
        mNmeaGsa = gsa;
        mNmeaVtg = vtg;
        mNmeaZda = zda;
        mNmeaGsv = gsv;
        mNmeaPubx = pubx;
        mNmeaOther = other;
    }

    // used by native code
    @Keep
    void setSirfStats(long lastMsgTs, long total, long mid41) {
        mSirfLastMsgTs = lastMsgTs;
        mSirfTotal = total;
        mSirfMid41 = mid41;
    }

    // used by native code
    @Keep
    void setUbloxStats(long lastMsgTs, long total) {
        mUbloxLastMsgTs = lastMsgTs;
        mUbloxTotal = total;
    }
}
