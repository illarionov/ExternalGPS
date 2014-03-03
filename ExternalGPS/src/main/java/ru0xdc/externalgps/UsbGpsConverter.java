package ru0xdc.externalgps;

import static junit.framework.Assert.assertTrue;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.location.LocationManager;
import android.os.ConditionVariable;
import android.support.v4.content.LocalBroadcastManager;
import android.util.Log;

import ru0xdc.externalgps.usb.SerialLineConfiguration;
import ru0xdc.externalgps.usb.UsbSerialController;
import ru0xdc.externalgps.usb.UsbServiceThread;
import ru0xdc.externalgps.usb.UsbUtils;

import java.io.IOException;

import javax.annotation.concurrent.GuardedBy;

public class UsbGpsConverter {

    private static final boolean DBG = BuildConfig.DEBUG & true;
    static final String TAG = UsbGpsConverter.class.getSimpleName();

    public final static String ACTION_USB_ATTACHED =
            UsbGpsConverter.class.getName() + ".ACTION_USB_ATTACHED";
    public final static String ACTION_USB_DETACHED =
            UsbGpsConverter.class.getName() + ".ACTION_USB_DETACHED";

    public final static String ACTION_AUTOCONF_STARTED =
            UsbGpsConverter.class.getName() + ".ACTION_AUTOCONF_STARTED";
    public final static String ACTION_AUTOCONF_STOPPED =
            UsbGpsConverter.class.getName() + ".ACTION_AUTOCONF_STOPPED";

    public final static String ACTION_VALID_GPS_MESSAGE_RECEIVED =
            UsbGpsConverter.class.getName() + ".ACTION_VALID_GPS_MESSAGE_RECEIVED";
    public final static String ACTION_VALID_LOCATION_RECEIVED =
            UsbGpsConverter.class.getName() + ".ACTION_VALID_LOCATION_RECEIVED";

    public final static String EXTRA_DATA =
            UsbGpsConverter.class.getName() + ".EXTRA_DATA";

    final String ACTION_USB_PERMISSION = UsbGpsConverter.class.getName() + ".USB_PERMISSION";

    final Object mLock = new Object();

    @GuardedBy("mLock")
    private final SerialLineConfiguration mSerialLineConfiguration;

    @GuardedBy("mLock")
    private final DataLoggerConfiguration mDataLoggerConfiguration;

    private UsbManager mUsbManager;

    @GuardedBy("UsbReceiver.this.mLock")
    private volatile UsbServiceThread mServiceThread;


    // Constants that indicate the current connection state
    public static enum TransportState {
        IDLE,
        CONNECTING,
        CONNECTED,
        WAITING,
        RECONNECTING
    };

    public static final String ACTION_USB_DEVICE_ATTACHED =
            UsbGpsConverter.class.getName() + ".ACTION_USB_DEVICE_ATTACHED";

    public static final int RECONNECT_TIMEOUT_MS = 2000;

    private final Context mContext;
    private final LocalBroadcastManager mBroadcastManager;
    private MockLocationProvider mLocationProvider;


    public UsbGpsConverter(Context serviceContext) {
        this(serviceContext, new MockLocationProvider());
    }

    public UsbGpsConverter(Context serviceContext, MockLocationProvider provider) {
        mContext = serviceContext;
        mLocationProvider = provider;
        mBroadcastManager = LocalBroadcastManager.getInstance(mContext);

        this.mUsbManager = (UsbManager) mContext.getSystemService(Context.USB_SERVICE);
        mSerialLineConfiguration = new SerialLineConfiguration();
        mDataLoggerConfiguration = new DataLoggerConfiguration();

        if (mUsbManager == null) throw new IllegalStateException("USB not available");
    }

    public void start() {
        final LocationManager lm = (LocationManager)mContext.getSystemService(Context.LOCATION_SERVICE);
        mLocationProvider.attach(lm);
        final IntentFilter f;
        f = new IntentFilter();
        f.addAction(ACTION_USB_DEVICE_ATTACHED);
        f.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
        f.addAction(ACTION_USB_PERMISSION);

        mContext.registerReceiver(mUsbStateListener, f);

        try {
            final UsbDevice d = UsbUtils.findSupportedDevices(mUsbManager).get(0);
            requestPermission(d);
        }catch (IndexOutOfBoundsException ignore) { }
    }

    public void stop() {
        mContext.unregisterReceiver(mUsbStateListener);
        synchronized(mLock) {
            mServiceThread.cancel();
            mServiceThread = null;
        }
        mLocationProvider.detach();
    }

    public boolean isActive() {
        return mLocationProvider.isAttached();
    }

    public void setLocationProvider(MockLocationProvider provider) {
        if (provider == null) throw new NullPointerException();
        mLocationProvider = provider;
    }

    public final MockLocationProvider getLocationProvider() {
        return mLocationProvider;
    }

    public void setSerialLineConfiguration(final SerialLineConfiguration conf) {
        synchronized(mLock) {
            this.mSerialLineConfiguration.set(conf);
        }
    }

    public SerialLineConfiguration getSerialLineConfiguration() {
        synchronized(mLock) {
            return new SerialLineConfiguration(mSerialLineConfiguration);
        }
    }

    public void setDataLoggerConfiguration(DataLoggerConfiguration conf) {
        synchronized(mLock) {
            mDataLoggerConfiguration.set(conf);
            if (mServiceThread != null) {
                // mUsbReceiver.mServiceThread.refreshDataLoggerCofiguration();
            }
        }
    }

    private void requestPermission(UsbDevice d) {
        if (DBG) {
            Log.d(TAG, "requestPermission() device=" + d.toString() + " name=" + d.getDeviceName());
        }
        final PendingIntent premissionIntent = PendingIntent.getBroadcast(mContext,
                0, new Intent(ACTION_USB_PERMISSION), 0);
        mUsbManager.requestPermission(d, premissionIntent);
    }

    private final BroadcastReceiver mUsbStateListener = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            UsbDevice device;
            String action = intent.getAction();
            Log.v(TAG, "Received intent " + action);

            if (action.equals(ACTION_USB_DEVICE_ATTACHED)) {
                device = (UsbDevice) intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
                onUsbDeviceAttached(device);
            }else if (action.equals(UsbManager.ACTION_USB_DEVICE_DETACHED)) {
                device = (UsbDevice) intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
                onUsbDeviceDetached(device);
            }else if (action.equals(ACTION_USB_PERMISSION)) {
                boolean granted;
                device = (UsbDevice) intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
                granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false);
                if (granted) {
                    onUsbPermissionGranted(device);
                }
            }else {
                Log.e(TAG, "Unknown action " + action);
            }
        }
    };

    void onUsbDeviceAttached(UsbDevice device) {
        if (DBG) Log.d(TAG, "onUsbDeviceAttached() device=" + device.toString());
        if (UsbUtils.probeDevice(mUsbManager, device) != null) {
            requestPermission(device);
        }
    }

    void onUsbDeviceDetached(UsbDevice device) {
        if (DBG) Log.d(TAG, "onUsbDeviceDetached() device=" + device.toString());
        synchronized(mLock) {
            if (mServiceThread == null) return;
            UsbSerialController controller = mServiceThread.getController();
            if (controller == null) return;
            if (!device.equals(controller.getDevice())) return;

            mServiceThread.setController(null);
        }
    }

    void onUsbPermissionGranted(UsbDevice device) {
        if (DBG) Log.d(TAG, "onUsbPermissionGranted() device=" + device.toString());
        synchronized(mLock) {
            if (mServiceThread == null) return;
            UsbSerialController controller = mServiceThread.getController();
            if (controller != null) return;
            controller = UsbUtils.probeDevice(mUsbManager, device);
            if (controller == null) return;

            mServiceThread.setController(controller);
        }
    }

    static {
        System.loadLibrary("usbconverter");
    }

}
