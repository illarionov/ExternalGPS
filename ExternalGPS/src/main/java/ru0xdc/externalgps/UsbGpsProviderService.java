/*
 * Copyright (C) 2010, 2011, 2012 Herbert von Broeuschmeul
 * Copyright (C) 2010, 2011, 2012 BluetoothGPS4Droid Project
 * Copyright (C) 2011, 2012 UsbGPS4Droid Project
 *
 * This file is part of UsbGPS4Droid.
 *
 * UsbGPS4Droid is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * UsbGPS4Droid is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *  along with UsbGPS4Droid. If not, see <http://www.gnu.org/licenses/>.
 */

/**
 *
 */
package ru0xdc.externalgps;

import android.app.Service;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.IBinder;
import android.preference.PreferenceManager;
import android.util.Log;

import ru0xdc.externalgps.usb.SerialLineConfiguration;


/**
 * TODO
 * A Service used to replace Android internal GPS with a bluetooth GPS and/or write GPS NMEA data in a File.
 *
 * @author Herbert von Broeuschmeul, Alexey Illarionov
 *
 */
public class UsbGpsProviderService extends Service {

    @SuppressWarnings("unused")
    private static final boolean DBG = BuildConfig.DEBUG & true;
    static final String TAG = UsbGpsProviderService.class.getSimpleName();

	public static final String ACTION_START_GPS_PROVIDER = UsbGpsProviderService.class.getName() + ".action.ACTION_START_GPS_PROVIDER";
	public static final String ACTION_STOP_GPS_PROVIDER = UsbGpsProviderService.class.getName() + ".action.ACTION_STOP_GPS_PROVIDER";
	public static final String ACTION_CONFIGURE_SIRF_GPS = UsbGpsProviderService.class.getName() + ".action.ACTION_CONFIGURE_SIRF_GPS";

	public static final String PREF_START_GPS_PROVIDER = "startGps";
	public static final String PREF_GPS_LOCATION_PROVIDER = "gpsLocationProviderKey";
	public static final String PREF_REPLACE_STD_GPS = "replaceStdGps";
	public static final String PREF_FORCE_ENABLE_PROVIDER = "forceEnableProvider";
	public static final String PREF_MOCK_GPS_NAME = "mockGpsName";
	public static final String PREF_CONNECTION_RETRIES = "connectionRetries";
	public static final String PREF_LOG_RAW_DATA_SCREEN = "logRawDataScreen";
	public static final String PREF_LOG_RAW_DATA = "logRawData";
	public static final String PREF_RAW_DATA_LOG_FORMAT = "rawDataLogFormat";
	public static final String PREF_TRACK_FILE_DIR = "trackFileDirectory";
	public static final String PREF_TRACK_FILE_PREFIX = "trackFilePrefix";
	public static final String PREF_USB_SERIAL_SETTINGS = "usbSerialSettings";
	public static final String PREF_USB_SERIAL_BAUDRATE = "usbSerialBaudrate";
	public static final String PREF_USB_SERIAL_DATA_BITS = "usbSerialDataBits";
	public static final String PREF_USB_SERIAL_PARITY = "usbSerialParity";
	public static final String PREF_USB_SERIAL_STOP_BITS = "usbSerialStopBits";
	public static final String PREF_USB_SERIAL_LAST_KNOWN_AUTO_BAUDRATE = "usbSerialLastKnownAutoBaudrate";
	public static final String PREF_ABOUT = "about";

	public static final String PREF_SIRF_GPS = "sirfGps";
	public static final String PREF_SIRF_ENABLE_GGA = "enableGGA";
	public static final String PREF_SIRF_ENABLE_RMC = "enableRMC";
	public static final String PREF_SIRF_ENABLE_GLL = "enableGLL";
	public static final String PREF_SIRF_ENABLE_VTG = "enableVTG";
	public static final String PREF_SIRF_ENABLE_GSA = "enableGSA";
	public static final String PREF_SIRF_ENABLE_GSV = "enableGSV";
	public static final String PREF_SIRF_ENABLE_ZDA = "enableZDA";
	public static final String PREF_SIRF_ENABLE_SBAS = "enableSBAS";
	public static final String PREF_SIRF_ENABLE_NMEA = "enableNMEA";
	public static final String PREF_SIRF_ENABLE_STATIC_NAVIGATION = "enableStaticNavigation";

	private Notificator mNotificator;
	private UsbGpsConverter mConverter;

	@Override
	public void onCreate() {
		super.onCreate();
		mConverter = new UsbGpsConverter(this);
		mNotificator = new Notificator(this);
	}

	@Override
	public int onStartCommand(Intent intent, int flags, int startId) {

        if (intent == null) {
            Log.v(TAG, "UsbGpsProviderService restarted");
            processStartGpsProvider();
        }else {
            final String action = intent.getAction();
            if (action.equals(ACTION_START_GPS_PROVIDER)) processStartGpsProvider();
            else if(action.equals(ACTION_STOP_GPS_PROVIDER)) processStopGpsProvider();
            else if(action.equals(ACTION_CONFIGURE_SIRF_GPS)) processConfigureSirfGps(intent.getExtras());
            else Log.e(TAG, "onStartCommand(): unknown action " + action);
        }
        return START_STICKY;
	}

    @Override
    public IBinder onBind(Intent arg0) {
        return null;
    }

    @Override
    public void onDestroy() {
        stop();
        mNotificator = null;
        mConverter = null;
    }


    public boolean isServiceStarted() {
        return mConverter.isActive();
    }


    private void processStartGpsProvider() {
        final SharedPreferences prefs;
        final String providerName;
        final MockLocationProvider provider;
        final boolean replaceInternalGps;
        final SerialLineConfiguration usbSerialLineConf;
        final DataLoggerConfiguration dataLoggerConf;

        if (isServiceStarted()) return;

        prefs = PreferenceManager.getDefaultSharedPreferences(this);
        providerName = prefs.getString(PREF_MOCK_GPS_NAME,
                MockLocationProvider.DEFAULT_NAME);
        replaceInternalGps = prefs.getBoolean(PREF_REPLACE_STD_GPS, false);

        usbSerialLineConf = SettingsFragment.UsbSerialSettings.readConf(prefs);

        dataLoggerConf = SettingsFragment.DataLoggerSettings.readConf(prefs);

        provider = new MockLocationProvider(providerName);
        provider.replaceInternalGps(replaceInternalGps);

        mConverter.setLocationProvider(provider);
        mConverter.setDataLoggerConfiguration(dataLoggerConf);
        mConverter.setSerialLineConfiguration(usbSerialLineConf);
        mConverter.start();

        startForeground(Notificator.FOREGROUND_NOTIFICATION_ID,
                mNotificator.createForegroundNotification());
        mNotificator.onServiceStarted();
    }

    private void processStopGpsProvider() {
        stop();
        stopSelf();
    }

    private void processConfigureSirfGps(Bundle extras) {
        if (!isServiceStarted()) return;
        //mGpsManager.enableSirfConfig(extras);
    }

    private void stop() {
        stopForeground(true);

        if (isServiceStarted()) {
            mConverter.stop();
            mNotificator.onServiceStopped();
        }
    }
}
