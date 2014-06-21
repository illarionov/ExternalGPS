package ru0xdc.externalgps.usb;

import android.content.Context;
import android.content.SharedPreferences;
import android.preference.PreferenceManager;
import android.util.Log;

import ru0xdc.externalgps.BuildConfig;
import ru0xdc.externalgps.R;
import ru0xdc.externalgps.UsbGpsProviderService;

import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;

public class AutobaudTask extends Thread {

    // Debugging
    private static final String TAG = AutobaudTask.class.getSimpleName();
    private static final boolean DBG = BuildConfig.DEBUG & true;

    public static final int WAIT_MSG_TIMEOUT_MS = 3000;
    public static final int MIN_VALID_MSG_CNT = 2;

    private static AutobaudTask.Callbacks sDummyCallbacks = new Callbacks() {

        @Override
        public SerialLineConfiguration getSerialLineConfiguration() { return new SerialLineConfiguration();}

        @Override
        public void setSerialLineConfiguration(SerialLineConfiguration conf) {}

        @Override
        public void onAutobaudCompleted(int baudrate) {}
        @Override
        public void onAutobaudFailed() {}
    };

    private final SharedPreferences mSharedPrefs;
    private final int[] mDefaultBaudrateProbeList;
    private AutobaudTask.Callbacks mCallbacks;

    private final AtomicInteger mReceivedMsgCnt = new AtomicInteger();

    public static interface Callbacks {
        SerialLineConfiguration getSerialLineConfiguration();
        void setSerialLineConfiguration(SerialLineConfiguration conf);

        void onAutobaudCompleted(int baudrate);
        void onAutobaudFailed();
    };

    public AutobaudTask(Context ctx, AutobaudTask.Callbacks callbacks) {
        mDefaultBaudrateProbeList = ctx.getResources().getIntArray(R.array.usb_serial_auto_baudrate_probe_list);
        mSharedPrefs = PreferenceManager.getDefaultSharedPreferences(ctx);
        mCallbacks = callbacks != null ? callbacks : sDummyCallbacks;
    }

    @Override
    public void run() {
        final int[] bauds;
        boolean baudFound;
        int validBaudrate;
        final SerialLineConfiguration serialConf;

        bauds = getBaudrateProbeList();
        baudFound = false;
        validBaudrate = 0;
        serialConf = mCallbacks.getSerialLineConfiguration();
        serialConf.setAutoBaudrateDetection(false);
        try {
            synchronized (this) {
                for (int baud: bauds) {
                    serialConf.setBaudrate(baud);
                    if (DBG) Log.v(TAG, "Trying " + serialConf);
                    mCallbacks.setSerialLineConfiguration(serialConf);
                    mReceivedMsgCnt.set(0);
                    wait(WAIT_MSG_TIMEOUT_MS);
                    if (mReceivedMsgCnt.get() >= MIN_VALID_MSG_CNT) {
                        baudFound = true;
                        validBaudrate = baud;
                        break;
                    }
                }
            }
        } catch (InterruptedException e) {
            Log.i(TAG, "Interrupted: " + e.toString());
        }finally {
            if (baudFound) {
                saveLastKnownBaudrate(validBaudrate);
                mCallbacks.onAutobaudCompleted(validBaudrate);
            }else {
                mCallbacks.onAutobaudFailed();
            }
        }
    }

    public synchronized void onGpsMessageReceived(java.nio.ByteBuffer buf, int start, int size, int type) {
        if (mReceivedMsgCnt.addAndGet(1) >= MIN_VALID_MSG_CNT) {
            notifyAll();
        }
    }

    private int getLastKnownBaudrate() {
        return mSharedPrefs.getInt(UsbGpsProviderService.PREF_USB_SERIAL_LAST_KNOWN_AUTO_BAUDRATE,
                -1);
    }

    private void saveLastKnownBaudrate(int baudrate) {
        mSharedPrefs
            .edit()
            .putInt(UsbGpsProviderService.PREF_USB_SERIAL_LAST_KNOWN_AUTO_BAUDRATE,
                    baudrate)
            .commit();
    }

    private int[] getBaudrateProbeList() {
        final int lastKnownBaudrate;
        final int[] bauds;
        int i;

        lastKnownBaudrate = getLastKnownBaudrate();
        if (lastKnownBaudrate < 0) {
            return Arrays.copyOf(mDefaultBaudrateProbeList, mDefaultBaudrateProbeList.length);
        }

        bauds = new int[mDefaultBaudrateProbeList.length];

        bauds[0] = lastKnownBaudrate;
        i = 1;
        for (int baud: mDefaultBaudrateProbeList) {
            if (lastKnownBaudrate != baud) bauds[i++] = baud;
        }

        return Arrays.copyOf(bauds, i);
    }

}
