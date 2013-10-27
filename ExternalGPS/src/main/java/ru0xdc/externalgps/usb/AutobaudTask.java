package ru0xdc.externalgps.usb;

import android.content.Context;
import android.content.SharedPreferences;
import android.preference.PreferenceManager;
import android.util.Log;

import ru0xdc.externalgps.BuildConfig;
import ru0xdc.externalgps.R;
import ru0xdc.externalgps.UsbGpsProviderService;

import java.util.Arrays;

import javax.annotation.concurrent.GuardedBy;

public class AutobaudTask extends Thread {

    // Debugging
    private static final String TAG = AutobaudTask.class.getSimpleName();
    private static final boolean DBG = BuildConfig.DEBUG & true;

    public static final int WAIT_MSG_TIMEOUT_MS = 3000;
    public static final int MIN_VALID_MSG_CNT = 2;

    private static AutobaudTask.Callbacks sDummyCallbacks = new Callbacks() {
        @Override
        public void onAutobaudCompleted(boolean isSuccessful, int baudrate) {}
    };

    private final UsbSerialController mUsbController;
    private final SharedPreferences mSharedPrefs;
    private final int[] mDefaultBaudrateProbeList;
    private AutobaudTask.Callbacks mCallbacks;

    @GuardedBy("this")
    private volatile int mReceivedMsgCnt;


    public static interface Callbacks {
        void onAutobaudCompleted(boolean isSuccessful, int baudrate);
    };


    public AutobaudTask(Context ctx, UsbSerialController usbController, AutobaudTask.Callbacks callbacks) {
        mDefaultBaudrateProbeList = ctx.getResources().getIntArray(R.array.usb_serial_auto_baudrate_probe_list);
        mUsbController = usbController;
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
        serialConf = mUsbController.getSerialLineConfiguration();
        serialConf.setAutoBaudrateDetection(false);
        try {
            synchronized (this) {
                for (int baud: bauds) {
                    serialConf.setBaudrate(baud);
                    if (DBG) Log.v(TAG, "Trying " + serialConf);
                    mUsbController.setSerialLineConfiguration(serialConf);
                    mReceivedMsgCnt = 0;
                    wait(WAIT_MSG_TIMEOUT_MS);
                    if (mReceivedMsgCnt >= MIN_VALID_MSG_CNT) {
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
                setLastKnownBaudrate(validBaudrate);
            }
            mCallbacks.onAutobaudCompleted(baudFound, validBaudrate);
        }
    }

    public synchronized void onGpsMessageReceived(java.nio.ByteBuffer buf, int start, int size, int type) {
        mReceivedMsgCnt += 1;
        if (mReceivedMsgCnt >= MIN_VALID_MSG_CNT) {
            notifyAll();
        }
    }

    private int getLastKnownBaudrate() {
        return mSharedPrefs.getInt(UsbGpsProviderService.PREF_USB_SERIAL_LAST_KNOWN_AUTO_BAUDRATE,
                -1);
    }

    private void setLastKnownBaudrate(int baudrate) {
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