package ru0xdc.externalgps;

import static junit.framework.Assert.assertTrue;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.location.Location;
import android.location.LocationManager;
import android.os.Bundle;
import android.os.ConditionVariable;
import android.support.v4.content.LocalBroadcastManager;
import android.util.Log;

import ru0xdc.externalgps.usb.AutobaudTask;
import ru0xdc.externalgps.usb.SerialLineConfiguration;
import ru0xdc.externalgps.usb.UsbSerialController;
import ru0xdc.externalgps.usb.UsbSerialController.UsbControllerException;
import ru0xdc.externalgps.usb.UsbSerialController.UsbSerialInputStream;
import ru0xdc.externalgps.usb.UsbSerialController.UsbSerialOutputStream;
import ru0xdc.externalgps.usb.UsbUtils;
import ru0xdc.externalgps.MockLocationProvider.Status;

import proguard.annotation.Keep;

import java.io.IOException;
import java.io.OutputStream;

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
    final UsbReceiver mUsbReceiver;
    private final LocalBroadcastManager mBroadcastManager;
    private MockLocationProvider mLocationProvider;


    public UsbGpsConverter(Context serviceContext) {
        this(serviceContext, new MockLocationProvider());
    }

    public UsbGpsConverter(Context serviceContext, MockLocationProvider provider) {
        mContext = serviceContext;
        mLocationProvider = provider;
        mUsbReceiver = new UsbReceiver();
        mBroadcastManager = LocalBroadcastManager.getInstance(mContext);
    }

    public void start() {
        final LocationManager lm = (LocationManager)mContext.getSystemService(Context.LOCATION_SERVICE);
        mLocationProvider.attach(lm);
        mUsbReceiver.start();
    }

    public void stop() {
        mUsbReceiver.stop();
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
        mUsbReceiver.setSerialLineConfigutation(conf);
    }

    public SerialLineConfiguration getSerialLineConfiguration() {
        return mUsbReceiver.getSerialLineConfiguration();
    }

    public void setDataLoggerConfiguration(DataLoggerConfiguration conf) {
        mUsbReceiver.setDataLoggerConfiguration(conf);
    }

    private class UsbReceiver {

        final String ACTION_USB_PERMISSION = UsbReceiver.class.getName() + ".USB_PERMISSION";

        final Object mLock = new Object();

        @GuardedBy("UsbReceiver.this.mLock")
        private final SerialLineConfiguration mSerialLineConfiguration;

        @GuardedBy("UsbReceiver.this.mLock")
        private final DataLoggerConfiguration mDataLoggerConfiguration;

        private UsbManager mUsbManager;

        final ConditionVariable mIsUsbDeviceReadyCondvar;

        @GuardedBy("UsbReceiver.this.mLock")
        private volatile UsbServiceThread mServiceThread;

        public UsbReceiver() {
            this.mUsbManager = (UsbManager) mContext.getSystemService(Context.USB_SERVICE);

            mSerialLineConfiguration = new SerialLineConfiguration();
            mDataLoggerConfiguration = new DataLoggerConfiguration();
            mIsUsbDeviceReadyCondvar = new ConditionVariable(false);

            if (mUsbManager == null) throw new IllegalStateException("USB not available");
        }

        public void setSerialLineConfigutation(final SerialLineConfiguration conf) {
            synchronized(UsbReceiver.this.mLock) {
                this.mSerialLineConfiguration.set(conf);
            }
        }

        public SerialLineConfiguration getSerialLineConfiguration() {
            synchronized(UsbReceiver.this.mLock) {
                return new SerialLineConfiguration(mSerialLineConfiguration);
            }
        }

        public void setDataLoggerConfiguration(DataLoggerConfiguration conf) {
            synchronized(UsbReceiver.this.mLock) {
                mDataLoggerConfiguration.set(conf);
                if (mUsbReceiver.mServiceThread != null) {
                    mUsbReceiver.mServiceThread.refreshDataLoggerCofiguration();
                }
            }
        }

        @SuppressWarnings("unused")
        public DataLoggerConfiguration getDataLoggerConfiguration() {
            synchronized(UsbReceiver.this.mLock) {
                return new DataLoggerConfiguration(mDataLoggerConfiguration);
            }
        }

        public void start() {
            final IntentFilter f;
            f = new IntentFilter();
            f.addAction(ACTION_USB_DEVICE_ATTACHED);
            f.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
            f.addAction(ACTION_USB_PERMISSION);

            mContext.registerReceiver(mUsbStateListener, f);

            synchronized(UsbReceiver.this.mLock) {
                mServiceThread = new UsbServiceThread();
                mServiceThread.start();
            }

            try {
                final UsbDevice d = UsbUtils.findSupportedDevices(mUsbManager).get(0);
                requestPermission(d);
            }catch (IndexOutOfBoundsException ignore) { }

        }

        public void stop() {
            mContext.unregisterReceiver(mUsbStateListener);
            synchronized(UsbReceiver.this.mLock) {
                mServiceThread.cancel();
                mServiceThread = null;
                mIsUsbDeviceReadyCondvar.open();
            }
        }

        @SuppressWarnings("unused")
        public boolean isDeviceReady() {
            return mIsUsbDeviceReadyCondvar.block(1);
        }

        @SuppressWarnings("unused")
        public void waitDevice() {
            mIsUsbDeviceReadyCondvar.block();
        }

        @SuppressWarnings("unused")
        public void write(byte[] buffer, int offset, int count) throws IOException {
            synchronized(UsbReceiver.this.mLock) {
                if (mServiceThread == null) throw new IOException("not connected");
                mServiceThread.write(buffer, offset, count);
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
            synchronized(UsbReceiver.this.mLock) {
                if (mServiceThread == null) return;
                UsbSerialController controller = mServiceThread.getController();
                if (controller == null) return;
                if (!device.equals(controller.getDevice())) return;

                mServiceThread.setController(null);
            }
        }

        void onUsbPermissionGranted(UsbDevice device) {
            if (DBG) Log.d(TAG, "onUsbPermissionGranted() device=" + device.toString());
            synchronized(UsbReceiver.this.mLock) {
                if (mServiceThread == null) return;
                UsbSerialController controller = mServiceThread.getController();
                if (controller != null) return;
                controller = UsbUtils.probeDevice(mUsbManager, device);
                if (controller == null) return;

                mServiceThread.setController(controller);
            }
        }

        private class UsbServiceThread extends Thread {

            /* mObject is used by native code, do not remove or rename */
            @Keep
            protected volatile long mObject;

            @GuardedBy("UsbReceiver.this.mLock")
            private volatile UsbSerialInputStream mInputStream;

            @GuardedBy("UsbReceiver.this.mLock")
            private volatile UsbSerialOutputStream mOutputStream;

            @GuardedBy("this")
            private volatile TransportState mConnectionState;

            @GuardedBy("UsbReceiver.this.mLock")
            private volatile boolean cancelRequested;

            @GuardedBy("UsbReceiver.this.mLock")
            private volatile boolean mFirstValidLocationReceived;

            private final ConditionVariable mIsControllerSet;

            @GuardedBy("UsbReceiver.this.mLock")
            private volatile UsbSerialController mUsbController;

            @GuardedBy("UsbReceiver.this.mLock")
            private volatile AutobaudTask mAutobaudThread;

            private final Location mReportedLocation = new Location("");
            private final Bundle mReportedLocationBundle = new Bundle(1);

            public UsbServiceThread() {
                mInputStream = null;
                mOutputStream = null;
                mConnectionState = TransportState.IDLE;
                cancelRequested = false;
                mUsbController = null;
                mAutobaudThread = null;
                mIsControllerSet = new ConditionVariable(false);
                mFirstValidLocationReceived = false;
                native_create();
            }

            public void setController(UsbSerialController controller) {
                if (DBG) assertTrue(Thread.holdsLock(mLock));
                if (mUsbController != null) {
                    mIsControllerSet.close();
                    mUsbController.detach();
                }
                mUsbController = controller;
                mFirstValidLocationReceived = false;
                if (controller != null) mIsControllerSet.open();
            }

            public UsbSerialController getController() {
                if (DBG) assertTrue(Thread.holdsLock(mLock));
                return mUsbController;
            }

            public void cancel() {
                if (DBG) assertTrue(Thread.holdsLock(mLock));
                cancelRequested = true;
                setController(null);
            }

            /**
             * Write to the connected OutStream.
             * @param buffer  The bytes to write
             */
            public void write(byte[] buffer, int offset, int count) throws IOException {
                OutputStream os;
                synchronized(UsbReceiver.this.mLock) {
                    if (mConnectionState != TransportState.CONNECTED) {
                        Log.e(TAG, "write() error: not connected");
                        return;
                    }
                    os = mOutputStream;
                }
                os.write(buffer, offset, count);
            }

            public StatsNative getStats() {
                StatsNative dst = new StatsNative();
                synchronized (dst) {
                    native_get_stats(dst);
                }
                return dst;
            }

            @Override
            public void run() {
                Log.i(TAG, "BEGIN UsbToLocalSocket-USB");
                setName("UsbToLocalSocket-USB");
                try {
                    setState(TransportState.CONNECTING);
                    while (true) {
                        throwIfCancelRequested();
                        connectLoop();

                        setState(TransportState.CONNECTED);
                        mBroadcastManager.sendBroadcast(new Intent(ACTION_USB_ATTACHED));
                        mBroadcastManager.sendBroadcast(new Intent(ACTION_AUTOCONF_STARTED));

                        startInitBaudrate();
                        native_read_loop(mInputStream, mOutputStream);
                        throwIfCancelRequested();

                        synchronized(this) {
                            setState(TransportState.RECONNECTING);
                            mBroadcastManager.sendBroadcast(new Intent(ACTION_USB_DETACHED));
                            wait(RECONNECT_TIMEOUT_MS);
                        }
                    }
                }catch(CancelRequestedException cre) {
                }catch (InterruptedException ie) {
                    if (DBG) Log.v(TAG, "interrupted");
                }finally {
                    synchronized(UsbReceiver.this.mLock) {
                        if (mAutobaudThread != null) {
                            mAutobaudThread.interrupt();
                        }
                    }
                }
            }

            @Override
            protected void finalize() throws Throwable {
                native_destroy();
                super.finalize();
            }

            // Called from native code
            @Keep
            synchronized void reportLocation(
                    long time,
                    double latitude,
                    double longitude,
                    double altitude,
                    float accuracy,
                    float bearing,
                    float speed,
                    int satellites,
                    boolean isValid,
                    boolean hasAccuracy,
                    boolean hasAltitude,
                    boolean hasBearing,
                    boolean hasSpeed
                    ) {

                try {
                    if (!isValid) {
                        if (DBG) Log.v(TAG, "loc: null");
                        mLocationProvider.setLocation(null);
                        return;
                    }

                    mReportedLocation.reset();
                    mReportedLocation.setTime(time);
                    mReportedLocation.setLatitude(latitude);
                    mReportedLocation.setLongitude(longitude);
                    if (hasAltitude) {
                        mReportedLocation.setAltitude(altitude);
                    }
                    if (hasAccuracy) {
                        mReportedLocation.setAccuracy(accuracy);
                    }
                    if (hasBearing) {
                        mReportedLocation.setBearing(bearing);
                    }
                    if (hasSpeed) {
                        mReportedLocation.setSpeed(speed);
                    }

                    if (satellites > 0) {
                        mReportedLocationBundle.putInt("satellites", satellites);
                        mReportedLocation.setExtras(mReportedLocationBundle);
                    }

                    mLocationProvider.setLocation(mReportedLocation);

                    if (!mFirstValidLocationReceived) {
                        mFirstValidLocationReceived = true;
                        mBroadcastManager.sendBroadcast(new Intent(ACTION_VALID_LOCATION_RECEIVED));
                    }

                    if (DBG) Log.v(TAG, "loc: " + mReportedLocation);
                } catch(Exception e) {
                    e.printStackTrace();
                }
            }

            // Called from native code
            @Keep
            void onGpsMessageReceived(java.nio.ByteBuffer buf, int start, int size, int type) {
                if (DBG) Log.v(TAG, "msg " + type + " start/size: " + start + " " + size);
                synchronized(UsbReceiver.this.mLock) {
                    if (mAutobaudThread != null) {
                        mAutobaudThread.onGpsMessageReceived(buf, start, size, type);
                    }
                }
            }

            void refreshDataLoggerCofiguration() {
                synchronized(UsbReceiver.this.mLock) {
                    refreshDataLoggerCofiguration(mDataLoggerConfiguration);
                }
            }

            void refreshDataLoggerCofiguration(DataLoggerConfiguration conf) {
                native_datalogger_configure(
                        conf.isEnabled(),
                        conf.getFormat().getNativeCode(),
                        conf.getStorageDir(),
                        conf.getFilePrefix());
            }

            private synchronized void setState(TransportState state) {
                TransportState oldState = mConnectionState;
                mConnectionState = state;
                if (DBG) Log.d(TAG, "setState() " + oldState + " -> " + state);

                if (mConnectionState == TransportState.CONNECTED) {
                    mIsUsbDeviceReadyCondvar.open();
                    mLocationProvider.setDeviceStatus(Status.TEMPORARILY_UNAVAILABLE);
                } else {
                    mIsUsbDeviceReadyCondvar.close();
                    mLocationProvider.setDeviceStatus(Status.OUT_OF_SERVICE);
                }
            }

            private void throwIfCancelRequested() throws CancelRequestedException {
                synchronized(UsbReceiver.this.mLock) {
                    if (cancelRequested) throw new CancelRequestedException();
                }
            }

            private void startInitBaudrate() {
                synchronized(UsbReceiver.this.mLock) {
                    if (mUsbController.getSerialLineConfiguration().isAutoBaudrateDetectionEnabled()) {
                        refreshDataLoggerCofiguration(new DataLoggerConfiguration().setEnabled(false));
                        mAutobaudThread = new AutobaudTask(mContext, mUsbController, mAutobaudThreadCallbacks);
                        mAutobaudThread.setName("AutobaudThread");
                        mAutobaudThread.start();
                    }else {
                        mAutobaudThreadCallbacks.onAutobaudCompleted(true, mSerialLineConfiguration.getBaudrate());
                    }
                }
            }

            private final AutobaudTask.Callbacks mAutobaudThreadCallbacks = new AutobaudTask.Callbacks() {
                @Override
                public void onAutobaudCompleted(boolean isSuccessful, int baudrate) {
                    synchronized(UsbReceiver.this.mLock) {
                        if (DBG) Log.v(TAG, "onAutobaudCompleted() " +
                                (isSuccessful ? "successful" : "failed") + " " + baudrate);
                        mAutobaudThread = null;
                        native_msg_rcvd_cb(false);
                        mBroadcastManager.sendBroadcast(new Intent(ACTION_AUTOCONF_STOPPED));
                        if (!isSuccessful) {
                            // XXX: report error
                            UsbServiceThread.this.cancel();
                        }else {
                            mDataLoggerConfiguration.createStorageDir();
                            refreshDataLoggerCofiguration();
                            native_datalogger_start();
                        }
                    }
                }
            };

            private void connect() throws UsbControllerException, CancelRequestedException {

                mIsControllerSet.block();

                synchronized(UsbReceiver.this.mLock) {
                    throwIfCancelRequested();
                    if (mUsbController == null) throw new UsbControllerException("");

                    if (DBG) Log.v(TAG, "attach(). "+ mUsbController.getSerialLineConfiguration().toString());
                    mUsbController.setSerialLineConfiguration(mSerialLineConfiguration);
                    mUsbController.attach();
                    mInputStream = mUsbController.getInputStream();
                    mOutputStream = mUsbController.getOutputStream();
                }
                return;
            }

            private void connectLoop() throws CancelRequestedException {

                if (DBG) Log.v(TAG, "connectLoop()");

                while(true) {
                    try {
                        connect();
                        return;
                    }catch (UsbControllerException e) {
                        synchronized(this) {
                            throwIfCancelRequested();
                            setState(TransportState.RECONNECTING);
                            try {
                                wait(RECONNECT_TIMEOUT_MS);
                            } catch(InterruptedException ie) {
                                throwIfCancelRequested();
                            }
                        }
                    }
                }
            }

            private class CancelRequestedException extends Exception {
                private static final long serialVersionUID = 1L;
            }

            private native void native_create();
            private native void native_read_loop(UsbSerialInputStream inputStream, UsbSerialOutputStream outputStream);
            private native void native_destroy();
            private native void native_get_stats(StatsNative dst);
            private native synchronized void native_msg_rcvd_cb(boolean activate);

            // TODO: notify user on errors
            native void native_datalogger_configure(boolean enabled, int format, String tracksDir, String filePrefix);
            private native void native_datalogger_start();
            private native void native_datalogger_stop();
        }

    }

    static {
        System.loadLibrary("usbconverter");
    }


}
