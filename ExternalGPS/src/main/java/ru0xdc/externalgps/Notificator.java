package ru0xdc.externalgps;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.support.v4.content.LocalBroadcastManager;
import android.support.v4.app.NotificationCompat;
import android.widget.Toast;

import java.lang.reflect.InvocationTargetException;

public class Notificator {

    public static final int FOREGROUND_NOTIFICATION_ID = 1;

    private final Context mServiceContext;
    private final NotificationManager mNotificationManager;

    public Notificator(Context serviceContext) {
        mServiceContext = serviceContext;
        mNotificationManager = (NotificationManager) serviceContext.getSystemService(Context.NOTIFICATION_SERVICE);
    }

    private NotificationCompat.Builder createNotificationBuilder() {
        final PendingIntent contentIntent;

        contentIntent = PendingIntent.getActivity(mServiceContext, 0,
                new Intent(mServiceContext, SettingsActivity.class), 0);

        return new NotificationCompat.Builder(mServiceContext)
            .setContentIntent(contentIntent)
            .setOngoing(true);
    }

    public Notification createForegroundNotification() {
        final NotificationCompat.Builder builder;

        builder = createNotificationBuilder()
                .setContentTitle(mServiceContext.getText(R.string.foreground_notification_title_gps_provider_started))
                .setContentText(mServiceContext.getText(R.string.foreground_notification_text_please_plug_in_gps_receiver))
                .setSmallIcon(R.drawable.ic_stat_gps_disconnected);

        return builder.build();
    }

    public void onServiceStarted() {
        LocalBroadcastManager.getInstance(mServiceContext).registerReceiver(mBroadcastReceiver, createIntentFilter());
    }

    public void onServiceStopped() {
        LocalBroadcastManager.getInstance(mServiceContext).unregisterReceiver(mBroadcastReceiver);
    }

    private IntentFilter createIntentFilter() {
        IntentFilter f = new IntentFilter();
        f.addAction(UsbGpsProviderService.ACTION_USB_ATTACHED);
        f.addAction(UsbGpsProviderService.ACTION_USB_DETACHED);
        f.addAction(UsbGpsProviderService.ACTION_AUTOCONF_STARTED);
        f.addAction(UsbGpsProviderService.ACTION_AUTOCONF_STOPPED);
        f.addAction(UsbGpsProviderService.ACTION_VALID_GPS_MESSAGE_RECEIVED);
        f.addAction(UsbGpsProviderService.ACTION_VALID_LOCATION_RECEIVED);

        return f;
    }

    private final BroadcastReceiver mBroadcastReceiver = new BroadcastReceiver() {

        @Override
        public void onReceive(Context context, Intent intent) {
            final String action = intent.getAction();
            if (UsbGpsProviderService.ACTION_USB_ATTACHED.equals(action)) {
                onUsbAttached();
            }else if (UsbGpsProviderService.ACTION_USB_DETACHED.equals(action)) {
                onUsbDetached();
            }else if (UsbGpsProviderService.ACTION_AUTOCONF_STARTED.equals(action)) {
                onAutoconfStarted();
            }else if (UsbGpsProviderService.ACTION_AUTOCONF_STOPPED.equals(action)) {
                onAutoconfStopped();
            }else if (UsbGpsProviderService.ACTION_VALID_GPS_MESSAGE_RECEIVED.equals(action)) {
                onValidGpsMessageReceived();
            }else if (UsbGpsProviderService.ACTION_VALID_LOCATION_RECEIVED.equals(action)) {
                onValidLocationReceived();
            }else {
                throw new IllegalStateException();
            }
        }
    };

    private void onUsbAttached() {
        final NotificationCompat.Builder builder;

        builder = createNotificationBuilder()
                .setContentTitle(mServiceContext.getText(R.string.foreground_notification_title_usb_gps_attached))
                .setContentText(mServiceContext.getText(R.string.foreground_notification_text_usb_gps_attached))
                .setSmallIcon(R.drawable.ic_gps_searching);

        mNotificationManager.notify(FOREGROUND_NOTIFICATION_ID, builder.build());

        Toast.makeText(mServiceContext,
                mServiceContext.getText(R.string.msg_usb_gps_attached),
                Toast.LENGTH_SHORT).show();
    }

    private void onUsbDetached() {
        final NotificationCompat.Builder builder;

        builder = createNotificationBuilder()
                .setContentTitle(mServiceContext.getText(R.string.foreground_notification_title_usb_gps_detached))
                .setContentText(mServiceContext.getText(R.string.foreground_notification_text_lost_connection))
                .setSmallIcon(R.drawable.ic_stat_gps_disconnected)
                .setVibrate(new long[] {0, 2000} )
                ;

        mNotificationManager.notify(FOREGROUND_NOTIFICATION_ID, builder.build());

        Toast.makeText(mServiceContext,
                mServiceContext.getText(R.string.msg_lost_connection_to_usb_receiver),
                Toast.LENGTH_SHORT).show();
    }

    private void onAutoconfStarted() {
        final NotificationCompat.Builder builder;

        builder = createNotificationBuilder()
                .setContentTitle(mServiceContext.getText(R.string.foreground_notification_title_autodetect))
                .setContentText(mServiceContext.getText(R.string.foreground_notification_text_autodetect))
                .setProgress(0, 0, true)
                .setSmallIcon(R.drawable.ic_gps_searching)
                ;

        mNotificationManager.notify(FOREGROUND_NOTIFICATION_ID, builder.build());
    }

    private void onAutoconfStopped() {
        final NotificationCompat.Builder builder;

        builder = createNotificationBuilder()
                .setContentTitle(mServiceContext.getText(R.string.foreground_notification_title_running))
                .setContentText(mServiceContext.getText(R.string.foreground_notification_text_autodetect_complete))
                .setUsesChronometer(true)
                .setSmallIcon(R.drawable.ic_gps_searching)
                ;

        mNotificationManager.notify(FOREGROUND_NOTIFICATION_ID, builder.build());
    }

    private void onValidGpsMessageReceived() {
        final NotificationCompat.Builder builder;

        builder = createNotificationBuilder()
                .setContentTitle(mServiceContext.getText(R.string.foreground_notification_title_running))
                .setContentText(mServiceContext.getText(R.string.foreground_notification_text_running))
                .setUsesChronometer(true)
                .setSmallIcon(R.drawable.ic_gps_searching)
                ;

        mNotificationManager.notify(FOREGROUND_NOTIFICATION_ID, builder.build());
    }

    private void onValidLocationReceived() {
        final NotificationCompat.Builder builder;

        builder = createNotificationBuilder()
                .setContentTitle(mServiceContext.getText(R.string.foreground_notification_title_running))
                .setContentText(mServiceContext.getText(R.string.foreground_notification_text_has_known_location))
                .setUsesChronometer(true)
                .setSmallIcon(R.drawable.ic_gps_receiving)
                ;

        mNotificationManager.notify(FOREGROUND_NOTIFICATION_ID, builder.build());
    }
}
