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

package ru0xdc.externalgps;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.app.DialogFragment;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager.NameNotFoundException;
import android.os.Bundle;
import android.preference.PreferenceActivity;
import android.text.method.LinkMovementMethod;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.TextView;

/**
 * A PreferenceActivity Class used to configure, start and stop the NMEA tracker service.
 *
 * @author Herbert von Broeuschmeul
 *
 */
public class SettingsActivity extends PreferenceActivity implements SettingsFragment.Callbacks {

    private static final boolean DBG = BuildConfig.DEBUG & true;
    private static final String TAG = SettingsActivity.class.getSimpleName();

	/** Called when the activity is first created. */
    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        if (savedInstanceState == null) {
            UsbControlActivity.InitApp(this);
            getFragmentManager().beginTransaction().replace(android.R.id.content,
                    new SettingsFragment()).commit();

        }
   }

	@Override
    public void displayAboutDialog(){
	    (new AboutDialogFragment()).show(getFragmentManager(), null);
	}

	@Override
    public void startGpsProviderService() {
	    final Intent intent = new Intent(this, UsbGpsProviderService.class);
	    intent.setAction(UsbGpsProviderService.ACTION_START_GPS_PROVIDER);
	    startService(intent);
	}

	@Override
    public void stopGpsProviderService() {
        final Intent intent = new Intent(this, UsbGpsProviderService.class);
        intent.setAction(UsbGpsProviderService.ACTION_STOP_GPS_PROVIDER);
        startService(intent);
    }

    @Override
    public void setSirfFeature(String key, boolean enabled) {
        final Intent intent = new Intent(this, UsbGpsProviderService.class);
        intent.setAction(UsbGpsProviderService.ACTION_CONFIGURE_SIRF_GPS);
        intent.putExtra(key, enabled);
        startService(intent);
    }

	public static class AboutDialogFragment extends DialogFragment {

	    @Override
        public Dialog onCreateDialog(Bundle savedInstanceState) {
	        final AlertDialog.Builder builder;
	        final LayoutInflater inflater;
	        final View messageView;
	        final Activity activity;
	        TextView textView;

	        activity = getActivity();
	        inflater = activity.getLayoutInflater();

	        messageView = inflater.inflate(R.layout.about, null, false);
	        // we need this to enable html links
            textView = (TextView) messageView.findViewById(R.id.about_license);
	        textView.setMovementMethod(LinkMovementMethod.getInstance());
	        // When linking text, force to always use default color. This works
	        // around a pressed color state bug.
	        final int defaultColor = textView.getTextColors().getDefaultColor();
	        textView.setTextColor(defaultColor);
	        ((TextView) messageView.findViewById(R.id.about_sources)).setTextColor(defaultColor);

	        try {
	            final PackageInfo pi = activity.getPackageManager().getPackageInfo(activity.getPackageName(), 0);
	            ((TextView) messageView.findViewById(R.id.about_version)).setText(pi.versionName);
	        } catch (NameNotFoundException nnfe) {
	            nnfe.printStackTrace();
	        }

	        builder = new AlertDialog.Builder(activity);
	        builder
	            .setTitle(R.string.about_title)
	            .setIcon(R.drawable.ic_launcher)
	            .setView(messageView);

	        return builder.create();
	    }

	}

}
