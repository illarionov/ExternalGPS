package ru0xdc.externalgps;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.hardware.usb.UsbManager;
import android.os.Bundle;
import android.os.StrictMode;
import android.os.StrictMode.ThreadPolicy;
import android.os.StrictMode.VmPolicy;
import android.preference.PreferenceManager;

import javax.annotation.Nullable;

public class UsbControlActivity extends Activity {

    private static final boolean DBG = BuildConfig.DEBUG & true;
    private static final String TAG = UsbControlActivity.class.getSimpleName();

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        if (savedInstanceState == null) {
            InitApp(this);

            if (isUsbAttachedEvent(getIntent())) {
                if (isTaskRoot()) {
                    PreferenceManager
                    .getDefaultSharedPreferences(this)
                    .edit()
                    .putBoolean(UsbGpsProviderService.PREF_START_GPS_PROVIDER, true)
                    .commit();
                    startGpsProviderService();
                }
                notifyServiceUsbAttached(getIntent());
            }
        }
        finish();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (isUsbAttachedEvent(intent)) {
            notifyServiceUsbAttached(intent);
        }
        finish();
    }

    private boolean isUsbAttachedEvent(@Nullable final Intent intent) {
        return (intent != null)
                && (UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(intent.getAction()));
    }

    private void notifyServiceUsbAttached(Intent intent) {

        if (DBG && !isUsbAttachedEvent(intent))
            throw new IllegalArgumentException();

        final Intent proxyIntent = new Intent(UsbGpsConverter.ACTION_USB_DEVICE_ATTACHED);
        proxyIntent.putExtras(intent.getExtras());
        sendBroadcast(proxyIntent);
    }

    public void startGpsProviderService() {
        final Intent intent = new Intent(this, UsbGpsProviderService.class);
        intent.setAction(UsbGpsProviderService.ACTION_START_GPS_PROVIDER);
        startService(intent);
    }


    public static void InitApp(Context context) {
        PreferenceManager.setDefaultValues(context,
                R.xml.pref, false);
        SettingsFragment.DataLoggerSettings.setDefaultValues(context, false);

        if (DBG) {
            StrictMode.setThreadPolicy(new ThreadPolicy.Builder()
                .detectAll().penaltyLog(). penaltyFlashScreen().build());
            StrictMode.setVmPolicy(new VmPolicy.Builder().detectAll().penaltyLog().build());
        }

    }

}
