package ru0xdc.externalgps;

import android.location.Criteria;
import android.location.Location;
import android.location.LocationManager;
import android.location.LocationProvider;
import android.os.Handler;
import android.os.Message;
import android.os.SystemClock;
import android.text.TextUtils;

public class MockLocationProvider {


    public static final String DEFAULT_NAME = "external";

    /**
     * External device status
     *
     */
    public static enum Status {

        /**
         * The device is not connected.
         * @see LocationProvider#OUT_OF_SERVICE
         */
        OUT_OF_SERVICE(LocationProvider.OUT_OF_SERVICE),

        /**
         * The device is connected but the location is not known
         * @see LocationProvider#TEMPORARILY_UNAVAILABLE
         */
        TEMPORARILY_UNAVAILABLE(LocationProvider.TEMPORARILY_UNAVAILABLE),

        /**
         * The device is connected and location is known
         */
        AVAILABLE(LocationProvider.AVAILABLE);

        private final int  mLocationProviderStatus;

        private Status(int locationProviderStatus) {
            mLocationProviderStatus = locationProviderStatus;
        }

        public int getLocationProviderStatus() {
            return mLocationProviderStatus;
        }
    }

    private static final int MESSAGE_NEW_LOCATION = 0;

    private String mName;

    private String mAttachedName;

    private boolean mReplaceInternalGpsOnStart;

    private boolean mAttached;

    private LocationManager mLocationManager;

    private Status mDeviceStatus;

    final private Location mLastKnownLocation;

    @SuppressWarnings("unused")
    private boolean mHasLastKnownLocation;

    private volatile Handler mHandler;

    public MockLocationProvider() {
        this(DEFAULT_NAME);
    }

    public MockLocationProvider(String name) {
        if (TextUtils.isEmpty(name)) throw new IllegalArgumentException();
        mName = name;
        mAttached = false;
        mReplaceInternalGpsOnStart = false;
        mLastKnownLocation = new Location(mName);
        mHasLastKnownLocation = false;
        mDeviceStatus = Status.OUT_OF_SERVICE;
    }

    public static boolean isProviderNameValid(String name) {

        if (TextUtils.isEmpty(name)) return false;

        if (LocationManager.GPS_PROVIDER.equals(name)
                || LocationManager.NETWORK_PROVIDER.equals(name)
                || LocationManager.PASSIVE_PROVIDER.equals(name)
                ) {
            return false;
        }

        return true;
    }

    /**
     * Creates a mock location provider and adds it to the set of active providers.
     * @param context
     * @throws SecurityException  if the ACCESS_MOCK_LOCATION permission is not present or the
     *  Settings.Secure.ALLOW_MOCK_LOCATION system setting is not enabled
     * @throws IllegalArgumentException if a provider with the given name already exists
     */
    public void attach(LocationManager lm) {
        if (mAttached) return;

        mLocationManager = lm;

        mAttachedName = mReplaceInternalGpsOnStart ? LocationManager.GPS_PROVIDER : mName;

        try {
            mLocationManager.removeTestProvider(mAttachedName);
        }catch (IllegalArgumentException ignore) {
        }catch (NullPointerException npe) {
            npe.printStackTrace(); // WTF???
        }

        mLocationManager.addTestProvider(mAttachedName,
                /* requiresNetwork */ false,
                /* requiresSatellite */ true,
                /* requiresCell */    false,
                /* hasMonetaryCost */ false,
                /* supportsAltitude */ true,
                /* supportsSpeed */    true,
                /* supportsBearing */  true,
                /* powerRequirement */ Criteria.POWER_MEDIUM,
                /* accuracy */ Criteria.ACCURACY_FINE);

        mAttached = true;

        synchronized (this) {
            mHandler = new Handler(mHandlerCallback);
        }

        mLocationManager.setTestProviderEnabled(mAttachedName, true);

        if (mDeviceStatus != Status.OUT_OF_SERVICE) {
            mLocationManager.setTestProviderStatus(mAttachedName,
                    mDeviceStatus.getLocationProviderStatus(),
                    null,
                    SystemClock.elapsedRealtime());
        }
    }

    /**
     * Removes the mock location provider from LocationManager
     */
    public void detach() {
        if (!mAttached) return;

        mLocationManager.setTestProviderEnabled(mAttachedName, false);
        mLocationManager.removeTestProvider(mAttachedName);
        mAttached = false;
        mAttachedName = null;
        mLocationManager = null;

        synchronized (this) {
            mHandler = null;
        }
    }

    /**
     * Returns true if the provider is attached
     */
    public boolean isAttached() {
        return mAttached;
    }

    /**
     * Set the name of this provider.
     * @throws IllegalArgumentException if name of provider is not valid
     * @throws IllegalStateException if the provider is attached
     */
    public void setName(String name) throws IllegalArgumentException, IllegalStateException {

        if (!isProviderNameValid(name)) throw new IllegalArgumentException();

        if (mAttached) throw new IllegalStateException();

        mName = name;
    }

    /**
     * Returns the name of this provider.
     */
    public String getName() {
        return mName;
    }

    /**
     * Replace internal GPS
     * @param replace
     * @throws SecurityException if the ACCESS_MOCK_LOCATION permission is not present or
     *         the Settings.Secure.ALLOW_MOCK_LOCATION} system setting is not enabled
     */
    public void replaceInternalGps(boolean replace) throws SecurityException, IllegalStateException {
        mReplaceInternalGpsOnStart = replace;
        if (mAttached) {
            LocationManager lm = mLocationManager;
            detach();
            attach(lm);
        }
    }

    /**
     * Returns external device status
     */
    public Status getDeviceStatus() {
        return mDeviceStatus;
    }

    /**
     * Set external device status
     * @param status {@link Status#OUT_OF_SERVICE}, {@link Status#TEMPORARILY_UNAVAILABLE}, {@link Status#AVAILABLE}
     * @see LocationManager#setTestProviderStatus(String, int, android.os.Bundle, long)
     */
    public void setDeviceStatus(Status status) {
        if (mDeviceStatus == status) return;
        mDeviceStatus = status;

        if (mAttached) {
            mLocationManager.setTestProviderStatus(mAttachedName,
                    mDeviceStatus.getLocationProviderStatus(),
                    null,
                    SystemClock.elapsedRealtime());
        }
    }

    /**
     *
     * @see LocationManager#setTestProviderLocation(String, Location)
     */
    public synchronized void setLocation(Location location) throws IllegalArgumentException {
        if (mHandler != null) {
            mHandler.obtainMessage(MESSAGE_NEW_LOCATION, location).sendToTarget();
        }
    }

    private void setNewLocation(final Location l) {

        if (l == null) {
            mHasLastKnownLocation = false;
            if (mDeviceStatus == Status.AVAILABLE) {
                setDeviceStatus(Status.TEMPORARILY_UNAVAILABLE);
            }
        }else {
            mHasLastKnownLocation = true;
            mLastKnownLocation.set(l);
            mLastKnownLocation.setProvider(mAttachedName);
            if (mDeviceStatus == Status.TEMPORARILY_UNAVAILABLE) {
                setDeviceStatus(Status.AVAILABLE);
            }
            if (mAttached) {
                mLocationManager.setTestProviderLocation(mAttachedName, mLastKnownLocation);
            }
        }
    }


    private final Handler.Callback mHandlerCallback = new Handler.Callback() {

        @Override
        public boolean handleMessage(Message msg) {
            switch (msg.what) {
                case MESSAGE_NEW_LOCATION:
                    setNewLocation((Location)msg.obj);
                    return true;
            }
            return false;
        }

    };

}
