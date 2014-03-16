package ru0xdc.externalgps;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.location.Location;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Message;
import android.support.v4.content.LocalBroadcastManager;
import android.util.Log;

import javax.annotation.concurrent.GuardedBy;

import ru0xdc.externalgps.usb.SerialLineConfiguration;
import ru0xdc.externalgps.usb.UsbServiceThread;
import ru0xdc.externalgps.usb.UsbUtils;

public class UsbGpsConverter {
    private static final boolean DBG = BuildConfig.DEBUG & true;
    static final String TAG = UsbGpsConverter.class.getSimpleName();

    public static final String ACTION_USB_DEVICE_ATTACHED =
            UsbGpsProviderService.class.getName() + ".ACTION_USB_DEVICE_ATTACHED";

    public static final int RECONNECT_TIMEOUT_MS = 2000;

    // Constants that indicate the current connection state
    public static enum TransportState {
        IDLE,
        CONNECTING,
        CONNECTED,
        WAITING,
        RECONNECTING
    }

    public interface Callbacks {
        public void onStateConnected();
        public void onDisconnected();

        public void onLocationUnknown();
        public void onLocationReceived(Location location);
        public void onFirstLocationReceived(Location location);

        public void onAutoconfStarted();
        public void onAutobaoudCompleted(int newBaudrate);
        public void onAutobaoudFailed();
    }

    final String ACTION_USB_PERMISSION = UsbGpsConverter.class.getName() + ".USB_PERMISSION";

    private final Context mContext;
    private final UsbManager mUsbManager;
    private final Callbacks mCallbacks;

    @GuardedBy("this")
    private final SerialLineConfiguration mSerialLineConfiguration;
    @GuardedBy("this")
    private final DataLoggerConfiguration mDataLoggerConfiguration;

    private UsbServiceThread mServiceThread;
    @GuardedBy("this")
    private volatile UsbDevice mUsbDevice;

    private final HandlerThread mHandlerThread;
    private final Handler mHandler;

    public UsbGpsConverter(Context serviceContext, Callbacks callbacks) {
        mContext = serviceContext;
        mUsbManager = (UsbManager) mContext.getSystemService(Context.USB_SERVICE);
        mSerialLineConfiguration = new SerialLineConfiguration();
        mDataLoggerConfiguration = new DataLoggerConfiguration();

        if (mUsbManager == null) throw new IllegalStateException("USB not available");

        mHandlerThread = new HandlerThread(UsbGpsConverter.class.getSimpleName());
        mHandlerThread.start();
        mHandler = new Handler(mHandlerThread.getLooper());

        mCallbacks = callbacks;
    }

    public void setSerialLineConfiguration(final SerialLineConfiguration conf) {
        synchronized (this) {
            mSerialLineConfiguration.set(conf);
        }
    }

    public void setDataLoggerConfiguration(final DataLoggerConfiguration conf) {
        mHandler.post(new Runnable() {
            @Override
            public void run() {
                synchronized (UsbGpsConverter.this) {
                    mDataLoggerConfiguration.set(conf);
                }
                if (mServiceThread != null) {
                    // mUsbReceiver.mServiceThread.refreshDataLoggerCofiguration();
                }
            }
        });
    }

    public void start() {
        IntentFilter f = new IntentFilter();
        f.addAction(ACTION_USB_DEVICE_ATTACHED);
        f.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
        f.addAction(ACTION_USB_PERMISSION);

        mContext.registerReceiver(mUsbStateListener, f, null, mHandler);
        try {
            final UsbDevice d = UsbUtils.findSupportedDevices(mUsbManager).get(0);
            requestPermission(d);
        }catch (IndexOutOfBoundsException ignore) { }
    }

    public void stop() {
        mContext.unregisterReceiver(mUsbStateListener);

        mHandler.post(new Runnable() {
            @Override
            public void run() {
                if (mServiceThread != null) {
                    mServiceThread.cancel();
                    mServiceThread = null;
                }
                mHandlerThread.quit();
            }
        });
    }

    private void requestPermission(UsbDevice d) {
        if (DBG) {
            Log.d(TAG, "requestPermission() device=" + d.toString() + " name=" + d.getDeviceName());
        }
        final PendingIntent premissionIntent = PendingIntent.getBroadcast(mContext,
                0, new Intent(ACTION_USB_PERMISSION), 0);
        mUsbManager.requestPermission(d, premissionIntent);
    }

    private UsbServiceThread.Callbacks mUsbServiceThreadCallbacks = new UsbServiceThread.Callbacks() {

        @Override
        public Context getContext() {
            return mContext;
        }

        @Override
        public UsbDevice getUsbDevice() {
            synchronized(UsbGpsConverter.this) {
                return mUsbDevice;
            }
        }

        @Override
        public SerialLineConfiguration getSerialLineConfiguration() {
            synchronized (UsbGpsConverter.this) {
                return mSerialLineConfiguration;
            }
        }

        @Override
        public void onStateConnected() {
            mCallbacks.onStateConnected();
        }

        @Override
        public void onDisconnected() {
            mCallbacks.onDisconnected();
        }

        @Override
        public void onLocationUnknown() {
            mCallbacks.onLocationUnknown();
        }

        @Override
        public void onLocationReceived(Location location) {
            mCallbacks.onLocationReceived(location);
        }

        @Override
        public void onFirstLocationReceived(Location location) {
            mCallbacks.onFirstLocationReceived(location);
        }

        @Override
        public void onAutoconfStarted() {
            mCallbacks.onAutoconfStarted();
        }

        @Override
        public void onAutobaoudCompleted(int newBaudrate) {
            mCallbacks.onAutobaoudCompleted(newBaudrate);
        }

        @Override
        public void onAutobaoudFailed() {
            mCallbacks.onAutobaoudFailed();
        }
    };

    private final BroadcastReceiver mUsbStateListener = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            UsbDevice device;
            String action = intent.getAction();
            Log.v(TAG, "Received intent " + action);

            if (ACTION_USB_DEVICE_ATTACHED.equals(action)) {
                device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
                onUsbDeviceAttached(device);
            }else if (UsbManager.ACTION_USB_DEVICE_DETACHED.equals(action)) {
                device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
                onUsbDeviceDetached(device);
            }else if (ACTION_USB_PERMISSION.equals(action)) {
                boolean granted;
                device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
                granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false);
                if (granted) {
                    onUsbPermissionGranted(device);
                }
            }else {
                Log.e(TAG, "Unknown action " + action);
            }
        }

        void onUsbDeviceAttached(final UsbDevice device) {
            mHandler.post(new Runnable() {
                @Override
                public void run() {
                    if (DBG) Log.d(TAG, "onUsbDeviceAttached() device=" + device.toString());
                    if (mUsbDevice != null) {
                        if (DBG) Log.d(TAG, "onUsbDeviceAttached() skipped");
                        return;
                    }
                    if (UsbUtils.probeDevice(mUsbManager, device) != null) {
                        requestPermission(device);
                    }
                }
            });
        }

        void onUsbDeviceDetached(final UsbDevice device) {
            mHandler.post(new Runnable() {
                @Override
                public void run() {
                    if (DBG) Log.d(TAG, "onUsbDeviceDetached() device=" + device.toString());
                    synchronized(UsbGpsConverter.this) {
                        if (mUsbDevice == null) {
                            if (DBG) Log.v(TAG, "onUsbDeviceDetached() skipped");
                            return;
                        }

                        if (mUsbDevice.equals(device)) {
                            mServiceThread.cancel();
                            mServiceThread = null;
                            mUsbDevice = null;
                        }
                    }
                }
            });
        }

        void onUsbPermissionGranted(final UsbDevice device) {
            mHandler.post(new Runnable() {
                @Override
                public void run() {
                    if (DBG) Log.d(TAG, "onUsbPermissionGranted() device=" + device.toString());
                    synchronized (UsbGpsConverter.this) {
                        if (mUsbDevice == null) {
                            mUsbDevice = device;
                            mServiceThread = new UsbServiceThread(mUsbServiceThreadCallbacks);
                            mServiceThread.start();
                        }
                    }
                }
            });
        }
    };
}
