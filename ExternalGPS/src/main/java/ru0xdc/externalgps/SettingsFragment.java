/*
 * Copyright (C) 2010, 2011, 2012 Herbert von Broeuschmeul
 * Copyright (C) 2010, 2011, 2012 BluetoothGPS4Droid Project
 * Copyright (C) 2011, 2012 UsbGPS4Droid Project
 * Copyright (C) 2013 Alexey Illarionov
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

package ru0xdc.externalgps;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.SharedPreferences.OnSharedPreferenceChangeListener;
import android.content.res.Resources;
import android.os.Bundle;
import android.preference.EditTextPreference;
import android.preference.ListPreference;
import android.preference.Preference;
import android.preference.PreferenceFragment;
import android.preference.PreferenceGroup;
import android.preference.PreferenceManager;
import android.preference.PreferenceScreen;
import android.preference.SwitchPreference;
import android.text.TextUtils;
import android.widget.BaseAdapter;

import ru0xdc.externalgps.usb.SerialLineConfiguration;
import ru0xdc.externalgps.usb.SerialLineConfiguration.Parity;
import ru0xdc.externalgps.usb.SerialLineConfiguration.StopBits;
import ru0xdc.externalgps.DataLoggerConfiguration.Format;

/**
 * A SettingsFragment Class used to configure, start and stop the NMEA tracker service.
 *
 * @author Herbert von Broeuschmeul
 *
 */
public class SettingsFragment extends PreferenceFragment {

    @SuppressWarnings("unused")
    private static final boolean DBG = BuildConfig.DEBUG & false;
    @SuppressWarnings("unused")
    private static final String TAG = SettingsFragment.class.getSimpleName();

    public interface Callbacks {

        void displayAboutDialog();

        void startGpsProviderService();

        void stopGpsProviderService();

        void setSirfFeature(String key, boolean enabled);
    }

    private static final Callbacks sDummyCallbacks = new Callbacks() {
        @Override public void displayAboutDialog() {}
        @Override public void startGpsProviderService() {}
        @Override public void stopGpsProviderService() {}
        @Override public void setSirfFeature(String key, boolean enabled) {} ;
    };

    private Callbacks mCallbacks = sDummyCallbacks;


    private EditTextPreference mMockGpsNamePreference, mConnectionRetriesPreference;
    private PreferenceScreen mGpsLocationProviderPreference;

    private UsbSerialSettings mUsbSerialSettings;
    private DataLoggerSettings mDataLoggerSettings;

    @Override
    public void onAttach(Activity activity) {
        // TODO Auto-generated method stub
        super.onAttach(activity);

        if (!(activity instanceof Callbacks)) {
            throw new IllegalStateException("Activity must implement fragment's callbacks.");
        }

        mCallbacks = (Callbacks)activity;
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        addPreferencesFromResource(R.xml.pref);

        final PreferenceScreen rootScreen = getPreferenceScreen();
        mUsbSerialSettings = new UsbSerialSettings(rootScreen);
        mDataLoggerSettings = new DataLoggerSettings(rootScreen);
        mMockGpsNamePreference = (EditTextPreference)findPreference(UsbGpsProviderService.PREF_MOCK_GPS_NAME);
        mConnectionRetriesPreference = (EditTextPreference)findPreference(UsbGpsProviderService.PREF_CONNECTION_RETRIES);
        mGpsLocationProviderPreference = (PreferenceScreen)findPreference(UsbGpsProviderService.PREF_GPS_LOCATION_PROVIDER);

        Preference pref = findPreference(UsbGpsProviderService.PREF_ABOUT);
        pref.setOnPreferenceClickListener(new Preference.OnPreferenceClickListener() {
            @Override
            public boolean onPreferenceClick(Preference preference) {
                mCallbacks.displayAboutDialog();
                return true;
            }
        });
    }

    @Override
    public void onStart() {
        super.onStart();
        getPreferenceManager()
            .getSharedPreferences()
            .registerOnSharedPreferenceChangeListener(mOnSharedPreferenceChangeListener);
    }

    @Override
    public void onResume() {
        super.onResume();
        updateDevicePreferenceList();
    }

    @Override
    public void onStop() {
        super.onStop();
        getPreferenceManager()
            .getSharedPreferences()
            .unregisterOnSharedPreferenceChangeListener(mOnSharedPreferenceChangeListener);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        mUsbSerialSettings = null;
        mDataLoggerSettings = null;
        mMockGpsNamePreference = null;
        mConnectionRetriesPreference = null;
        mGpsLocationProviderPreference = null;
    }

    @Override
    public void onDetach() {
        super.onDetach();
        mCallbacks = sDummyCallbacks;
    }

    private void updateDevicePreferenceList(){
        final SharedPreferences sharedPref = getPreferenceManager().getSharedPreferences();
        final Resources resources = getResources();
        final String mockProviderName;

        mUsbSerialSettings.updateSummaries();
        mDataLoggerSettings.updateSummaries();

        mockProviderName = mMockGpsNamePreference.getText();
        mMockGpsNamePreference.setSummary(mockProviderName);

        int connRetries;

        try {
            connRetries = Integer.valueOf(mConnectionRetriesPreference.getText());
        }catch (NumberFormatException nfe) {
            nfe.printStackTrace();
            connRetries = Integer.valueOf(getString(R.string.defaultConnectionRetries));
        }

        mConnectionRetriesPreference.setSummary(resources.getQuantityString(
                R.plurals.pref_connection_retries_summary, connRetries, connRetries));

        if (sharedPref.getBoolean(UsbGpsProviderService.PREF_REPLACE_STD_GPS, true)){
            mGpsLocationProviderPreference.setSummary(R.string.pref_gps_location_provider_summary);
        } else {
            String s = getString(R.string.pref_mock_gps_name_summary, mockProviderName);
            mGpsLocationProviderPreference.setSummary(s);
        }
    }

    private final OnSharedPreferenceChangeListener mOnSharedPreferenceChangeListener = new OnSharedPreferenceChangeListener() {
        @Override
        public void onSharedPreferenceChanged(SharedPreferences sharedPreferences, String key) {
            if (mUsbSerialSettings.isSerialSettingsPref(key)) {
                mUsbSerialSettings.updateSummaries();
                ((BaseAdapter)getPreferenceScreen().getRootAdapter()).notifyDataSetChanged();
            } else if (mDataLoggerSettings.isDataLoggerSettingsPref(key)) {
                mDataLoggerSettings.updateSummaries();
                ((BaseAdapter)getPreferenceScreen().getRootAdapter()).notifyDataSetChanged();
            } else if (UsbGpsProviderService.PREF_START_GPS_PROVIDER.equals(key)) {
                boolean val = sharedPreferences.getBoolean(key, false);
                if (val) {
                    mCallbacks.startGpsProviderService();
                } else {
                    mCallbacks.stopGpsProviderService();
                }
            }
            updateDevicePreferenceList();
        }
    };

    public static class UsbSerialSettings {

        public static final String PREF_USB_SERIAL_AUTO_BAUDRATE_VALUE = "auto";

        private final PreferenceScreen mSettingsPref;
        private final ListPreference mBaudratePref, mDataBitsPref, mParityPref, mStopBitsPref;

        public UsbSerialSettings(PreferenceGroup rootScreen) {
            mSettingsPref = (PreferenceScreen)rootScreen.findPreference(UsbGpsProviderService.PREF_USB_SERIAL_SETTINGS);

            mBaudratePref = (ListPreference)mSettingsPref.findPreference(UsbGpsProviderService.PREF_USB_SERIAL_BAUDRATE);
            mDataBitsPref = (ListPreference)mSettingsPref.findPreference(UsbGpsProviderService.PREF_USB_SERIAL_DATA_BITS);
            mParityPref = (ListPreference)mSettingsPref.findPreference(UsbGpsProviderService.PREF_USB_SERIAL_PARITY);
            mStopBitsPref = (ListPreference)mSettingsPref.findPreference(UsbGpsProviderService.PREF_USB_SERIAL_STOP_BITS);
        }


        public boolean isSerialSettingsPref(String key) {
            return (UsbGpsProviderService.PREF_USB_SERIAL_BAUDRATE.equals(key)
                    || UsbGpsProviderService.PREF_USB_SERIAL_DATA_BITS.equals(key)
                    || UsbGpsProviderService.PREF_USB_SERIAL_PARITY.equals(key)
                    || UsbGpsProviderService.PREF_USB_SERIAL_STOP_BITS.equals(key)
                    );
        }

        public void updateSummaries() {
            final SerialLineConfiguration serialConf;
            serialConf = readConf(mSettingsPref.getSharedPreferences());

            mSettingsPref.setSummary(serialConf.toString(mSettingsPref.getContext().getResources()));
            mBaudratePref.setSummary(mBaudratePref.getEntry());
            mDataBitsPref.setSummary(mDataBitsPref.getEntry());
            mParityPref.setSummary(mParityPref.getEntry());
            mStopBitsPref.setSummary(mStopBitsPref.getEntry());
        }

        public static SerialLineConfiguration readConf(SharedPreferences prefs) {
            final SerialLineConfiguration serialConf;
            final String baudrate, dataBits, parity, stopBits;

            serialConf = new SerialLineConfiguration();

            baudrate = prefs.getString(UsbGpsProviderService.PREF_USB_SERIAL_BAUDRATE, null);
            dataBits = prefs.getString(UsbGpsProviderService.PREF_USB_SERIAL_DATA_BITS, null);
            parity = prefs.getString(UsbGpsProviderService.PREF_USB_SERIAL_PARITY, null);
            stopBits = prefs.getString(UsbGpsProviderService.PREF_USB_SERIAL_STOP_BITS, null);

            if (baudrate != null) {
                if (PREF_USB_SERIAL_AUTO_BAUDRATE_VALUE.equals(baudrate)) {
                    serialConf.setAutoBaudrateDetection(true);
                }else {
                    serialConf.setBaudrate(Integer.valueOf(baudrate), false);
                }
            }

            if (dataBits != null) {
                serialConf.setDataBits(Integer.valueOf(dataBits));
            }


            if (parity != null) {
                serialConf.setParity(Parity.valueOfChar(parity.charAt(0)));
            }

            if (stopBits != null) {
                serialConf.setStopBits(StopBits.valueOfString(stopBits));
            }

            return serialConf;
        }

    }

    public static class DataLoggerSettings {

        public static final String PREF_LOG_FORMAT_VALUE_RAW = "raw";
        public static final String PREF_LOG_FORMAT_VALUE_NMEA = "nmea";

        private final PreferenceScreen mSettingsPref;
        private final SwitchPreference mEnableLogPref;
        private final ListPreference mRawLogFormatPref;
        //private final EditTextPreference mTrackfileDirectoryPref;
        //private final EditTextPreference mTrackfilePrefixPref;

        public DataLoggerSettings(PreferenceGroup rootScreen) {
            mSettingsPref = (PreferenceScreen)rootScreen.findPreference(UsbGpsProviderService.PREF_LOG_RAW_DATA_SCREEN);

            mEnableLogPref = (SwitchPreference)mSettingsPref.findPreference(UsbGpsProviderService.PREF_LOG_RAW_DATA);
            mRawLogFormatPref = (ListPreference)mSettingsPref.findPreference(UsbGpsProviderService.PREF_RAW_DATA_LOG_FORMAT);
            //mTrackfileDirectoryPref = (EditTextPreference)mSettingsPref.findPreference(UsbGpsProviderService.PREF_TRACK_FILE_DIR);
            //mTrackfilePrefixPref = (EditTextPreference)mSettingsPref.findPreference(UsbGpsProviderService.PREF_TRACK_FILE_PREFIX);
        }

        public boolean isDataLoggerSettingsPref(String key) {
            return (UsbGpsProviderService.PREF_LOG_RAW_DATA.equals(key)
                    || UsbGpsProviderService.PREF_RAW_DATA_LOG_FORMAT.equals(key)
                    || UsbGpsProviderService.PREF_TRACK_FILE_DIR.equals(key)
                    || UsbGpsProviderService.PREF_TRACK_FILE_PREFIX.equals(key)
                    );
        }

        public void updateSummaries() {
            final boolean enabled;
            final String format;
            int summaryResId;

            mRawLogFormatPref.setSummary(mRawLogFormatPref.getEntry());

            enabled = mEnableLogPref.isChecked();
            format = mRawLogFormatPref.getValue();

            if (!enabled) {
                summaryResId = R.string.pref_recording_is_turned_off;
            }else {
                if (PREF_LOG_FORMAT_VALUE_RAW.equals(format)) {
                    summaryResId = R.string.pref_recording_raw;
                }else if (PREF_LOG_FORMAT_VALUE_NMEA.equals(format)) {
                    summaryResId = R.string.pref_recording_nmea;
                }else {
                    throw new IllegalStateException();
                }
            }
            mSettingsPref.setSummary(summaryResId);

        }

        public static DataLoggerConfiguration readConf(SharedPreferences prefs) {
            final DataLoggerConfiguration mConf;

            final String format, storageDir, filePrefix;

            mConf = new DataLoggerConfiguration();

            if (prefs.contains(UsbGpsProviderService.PREF_LOG_RAW_DATA)) {
                final boolean enabled;
                enabled = prefs.getBoolean(UsbGpsProviderService.PREF_LOG_RAW_DATA, true);
                mConf.setEnabled(enabled);
            }

            format = prefs.getString(UsbGpsProviderService.PREF_RAW_DATA_LOG_FORMAT, null);
            if (format != null) mConf.setFormat(Format.valueOfPrefsEntry(format));

            storageDir = prefs.getString(UsbGpsProviderService.PREF_TRACK_FILE_DIR, null);
            if (!TextUtils.isEmpty(storageDir)) mConf.setStorageDir(storageDir);

            filePrefix = prefs.getString(UsbGpsProviderService.PREF_TRACK_FILE_PREFIX, null);
            if (filePrefix != null) mConf.setFilePrefix(filePrefix);

            return mConf;
        }

        public static void setDefaultValues(Context context, boolean force) {
            final SharedPreferences prefs;
            final DataLoggerConfiguration defaultConf;

            prefs = PreferenceManager.getDefaultSharedPreferences(context);
            if (prefs.contains(UsbGpsProviderService.PREF_LOG_RAW_DATA) && !force) {
                return;
            }

            defaultConf = new DataLoggerConfiguration();

            prefs.edit()
                .putBoolean(UsbGpsProviderService.PREF_LOG_RAW_DATA, defaultConf.isEnabled())
                .putString(UsbGpsProviderService.PREF_RAW_DATA_LOG_FORMAT, defaultConf.getFormat().getPrefsEntryValue())
                .putString(UsbGpsProviderService.PREF_TRACK_FILE_DIR, defaultConf.getStorageDir())
                .putString(UsbGpsProviderService.PREF_TRACK_FILE_PREFIX, defaultConf.getFilePrefix())
                .apply();

        }

    }

}
