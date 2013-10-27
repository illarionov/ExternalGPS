package ru0xdc.externalgps.usb;

import android.content.res.Resources;
import android.text.TextUtils;

import ru0xdc.externalgps.R;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.annotation.Nullable;

/**
 * Serial port settings: speed, data bits, parity, stop bits
 *
 */
public class SerialLineConfiguration {

    public static final int DEFAULT_BAUDRATE = 38400;
    public static final boolean DEFAULT_AUTODETECT_BAUDRATE = false;
    public static final int DEFAULT_DATA_BITS = 8;
    public static final Parity DEFAULT_PARITY = Parity.NONE;
    public static final StopBits DEFAULT_STOP_BITS = StopBits.STOP_BITS_1;

    public static enum Parity {

        NONE((byte)0),

        ODD((byte)1),

        EVEN((byte)2),

        MARK((byte)3),

        SPACE((byte)4)

        ;

        private final byte mPstnCode;

        private  Parity(byte pstnCode) {
            mPstnCode = pstnCode;
        }

        /**
         * Returns parity as character
         * @return 'N' - none, 'O' - odd, 'E' - even, 'M' - mark, 'S' - space
         */
        public char getCharVal() {
            return name().charAt(0);
        }

        /**
         * Returns parity code according to USB CDC PSTN120 Table 17 Line Coding Structure
         */
        public byte getPstnCode() {
            return mPstnCode;
        }

        public static Parity valueOfChar(char charVal) {
            for (Parity p: values()) {
                if (p.getCharVal() == charVal) return p;
            }
            throw new IllegalArgumentException();
        }

        public static Parity valueOfPstnCode(int code) {
            for (Parity p: values()) {
                if (p.mPstnCode == code) return p;
            }
            throw new IllegalArgumentException();
        }
    }

    public static enum StopBits {
        /**
         * ! stop bit
         */
        STOP_BITS_1((byte)0, "1"),

        /**
         * 1.5 stop bits
         */
        STOP_BITS_1_5((byte)1, "1.5"),

        /**
         * 2 stop bits
         */
        STOP_BITS_2((byte)2, "2")

        ;

        private final byte mPstnCode;

        private final String mStringVal;

        private StopBits(byte pstnCode, String stringVal) {
            mPstnCode = pstnCode;
            mStringVal = stringVal;
        }

        /**
         * Returns stop bits code according to USB CDC PSTN120 Table 17 Line Coding Structure
         */
        public byte getPstnCode() {
            return mPstnCode;
        }

        /**
         * Returns string representation of value
         * @return "1", "1.5", or "2"
         */
        public String getStringVal() {
            return mStringVal;
        }

        public static StopBits valueOfString(String val) {
            for (StopBits sb: values()) {
                if (sb.mStringVal.equals(val)) return sb;
            }
            throw new IllegalArgumentException();
        }

        public static StopBits valueOfPstnCode(int code) {
            for (StopBits sb: values()) {
                if (sb.mPstnCode == code) return sb;
            }
            throw new IllegalArgumentException();
        }
    };

    private static final Pattern sLineCoddingPattern = Pattern.compile(
            "(?:(\\d+)\\s*(?:\\/|\\s)\\s*)?(5|6|7|8|16)\\s*[-\\/]?\\s*(N|O|E|M|S)\\s*[-\\/]?\\s*(1|1\\.5|2)$");

    private int mBaudrate;

    private int mDataBits;

    private Parity mParity;

    private StopBits mStopBits;

    private boolean mAutodetectBaudrate;

    public SerialLineConfiguration() {
        mBaudrate = DEFAULT_BAUDRATE;
        mDataBits = DEFAULT_DATA_BITS;
        mParity = DEFAULT_PARITY;
        mStopBits = DEFAULT_STOP_BITS;
        mAutodetectBaudrate = false;
    }

    public SerialLineConfiguration(final SerialLineConfiguration src) {
        this();
        set(src);
    }

    /**
     * @return data transfer rate
     */
    public int getBaudrate() {
        return mBaudrate;
    }

    /**
     * @return whether the baudrate should be determined automatically
     */
    public boolean isAutoBaudrateDetectionEnabled() {
        return mAutodetectBaudrate;
    }

    /**
     * @return the number of bits per character. May be  5, 6, 7, 8, or 16
     */
    public int getDataBits() {
        return mDataBits;
    }

    /**
     * @return the type of bit parity
     */
    public Parity getParity() {
        return mParity;
    }

    /**
     * @return the number of stop bits
     */
    public StopBits getStopBits() {
        return mStopBits;
    }

    /**
     * Sets the data transfer rate
     * @param baudrate Data transfer rate: 300 - 230400
     * @return this
     * @throws IllegalArgumentException
     */
    public SerialLineConfiguration setBaudrate(int baudrate) throws IllegalArgumentException {
        if (mBaudrate < 300 || mBaudrate > 3000000) {
            throw new IllegalArgumentException();
        }

        mBaudrate = baudrate;
        return this;
    }

    public SerialLineConfiguration setBaudrate(int baudrate, boolean autodetect) throws IllegalArgumentException {
        if (!autodetect) {
            setBaudrate(baudrate);
        }
        mAutodetectBaudrate = autodetect;
        return this;
    }

    /**
     * @param enable whether the baudrate should be determined automatically
     * @return this
     */
    public SerialLineConfiguration setAutoBaudrateDetection(boolean enable) {
        mAutodetectBaudrate = enable;
        return this;
    }

    /**
     * Sets the number of bits per character
     * @param dataBits The number of data bits. May be 5, 6, 7, 8, or 16
     * @return this
     * @throws IllegalArgumentException
     */
    public SerialLineConfiguration setDataBits(int dataBits) throws IllegalArgumentException {
        switch (dataBits) {
            case 5:
            case 6:
            case 7:
            case 8:
            case 16:
                break;
            default:
                throw new IllegalArgumentException("Wrong data bits");
        }
        mDataBits = dataBits;
        return this;
    }

    /**
     * Sets the type of bit parity
     * @param parity Data parity.
     * @return this
     * @throws IllegalArgumentException
     */
    public SerialLineConfiguration setParity(Parity parity) throws IllegalArgumentException {
        mParity = parity;
        return this;
    }

    public SerialLineConfiguration set(final SerialLineConfiguration src) {
        mBaudrate = src.mBaudrate;
        mDataBits = src.mDataBits;
        mParity = src.mParity;
        mStopBits = src.mStopBits;
        mAutodetectBaudrate = src.mAutodetectBaudrate;
        return this;
    }

    /**
     * Sets the number of stop bits
     * @param stopBits The number of stop bits.
     * @return this
     * @throws IllegalArgumentException
     */
    public SerialLineConfiguration setStopBits(StopBits stopBits) throws IllegalArgumentException  {
        mStopBits = stopBits;
        return this;
    }

    public SerialLineConfiguration setLineCoding(CharSequence coding)
            throws IllegalArgumentException {
        final Matcher m;

        m = sLineCoddingPattern.matcher(coding);

        if (m.matches()) {
            final String baudrate = m.group(1);
            if (!TextUtils.isEmpty(baudrate)) {
                setBaudrate(Integer.valueOf(baudrate));
            }
            setDataBits(Integer.valueOf(m.group(2)));
            setParity(Parity.valueOfChar(m.group(3).charAt(3)));
            setStopBits(StopBits.valueOfString(m.group(4)));
        }else {
            setBaudrate(Integer.valueOf(coding.toString()));
        }

        return this;
    }


	@Override
    public boolean equals(Object o) {
	    if (this == o) return true;
	    if (!(o instanceof SerialLineConfiguration)) return false;
	    final SerialLineConfiguration lhs = (SerialLineConfiguration)o;
	    return ((mBaudrate == lhs.mBaudrate)
	            && (mAutodetectBaudrate == lhs.mAutodetectBaudrate)
	            && (mDataBits == lhs.mDataBits)
	            && (mParity == lhs.mParity)
	            && (mStopBits == lhs.mStopBits));
    }

    @Override
    public int hashCode() {
        int result = 237;
        result = 31 * result + mBaudrate;
        result = 31 * result + (mAutodetectBaudrate ? 1 : 0);
        result = 31 * result + mDataBits;
        result = 31 * result + mParity.getPstnCode();
        result = 31 * result + mStopBits.getPstnCode();
        return result;
    }

    public String toString(@Nullable Resources r) {
        final String baudrate;
        if (mAutodetectBaudrate) {
            baudrate = r != null ? r.getStringArray(R.array.pref_usb_serial_baudrate_entries)[0] : "auto";
        }else {
            baudrate = String.valueOf(mBaudrate);
        }
        return String.format(Locale.US,
                "%s/%d-%c-%s", baudrate, mDataBits, mParity.getCharVal(), mStopBits.getStringVal());
    }

    @Override
    public String toString() {
        return toString(null);
    }

}