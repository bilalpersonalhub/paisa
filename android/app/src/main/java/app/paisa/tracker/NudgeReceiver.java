package app.paisa.tracker;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;

/**
 * The evening reminder. An alarm wakes this receiver once a day; it posts a notification
 * only if the page has not reported an entry dated today. It also runs after a restart,
 * because Android forgets alarms when the phone reboots.
 */
public class NudgeReceiver extends BroadcastReceiver {
    private static final String ACTION_NUDGE = "app.paisa.tracker.NUDGE";
    private static final String CHANNEL = "reminders";

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent == null ? null : intent.getAction();
        if (Intent.ACTION_BOOT_COMPLETED.equals(action)) {
            schedule(context);
            return;
        }
        if (!ACTION_NUDGE.equals(action)) {
            return;
        }
        SharedPreferences p = context.getSharedPreferences(MainActivity.PREFS, Context.MODE_PRIVATE);
        if (!p.getBoolean("nudgeOn", false)) {
            return;
        }
        String today = new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date());
        if (today.equals(p.getString("lastLogged", ""))) {
            return;
        }
        notifyNow(context);
    }

    private static PendingIntent alarmIntent(Context context) {
        Intent i = new Intent(context, NudgeReceiver.class).setAction(ACTION_NUDGE);
        return PendingIntent.getBroadcast(context, 0, i,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    /** Sets or cancels the daily alarm to match the saved setting. Safe to call any time. */
    static void schedule(Context context) {
        SharedPreferences p = context.getSharedPreferences(MainActivity.PREFS, Context.MODE_PRIVATE);
        AlarmManager am = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (am == null) {
            return;
        }
        PendingIntent pi = alarmIntent(context);
        am.cancel(pi);
        if (!p.getBoolean("nudgeOn", false)) {
            return;
        }
        Calendar when = Calendar.getInstance();
        when.set(Calendar.HOUR_OF_DAY, p.getInt("nudgeHour", 21));
        when.set(Calendar.MINUTE, 0);
        when.set(Calendar.SECOND, 0);
        when.set(Calendar.MILLISECOND, 0);
        if (when.getTimeInMillis() <= System.currentTimeMillis()) {
            when.add(Calendar.DAY_OF_YEAR, 1);
        }
        // Inexact on purpose: it needs no special permission and a few minutes either way is fine.
        am.setInexactRepeating(AlarmManager.RTC_WAKEUP, when.getTimeInMillis(), AlarmManager.INTERVAL_DAY, pi);
    }

    private static void notifyNow(Context context) {
        NotificationManager nm = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) {
            return;
        }
        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(new NotificationChannel(CHANNEL, "Reminders",
                    NotificationManager.IMPORTANCE_DEFAULT));
            b = new Notification.Builder(context, CHANNEL);
        } else {
            b = new Notification.Builder(context);
        }
        Intent open = new Intent(context, MainActivity.class)
                .setAction(MainActivity.ACTION_ADD_EXPENSE)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent tap = PendingIntent.getActivity(context, 1, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        b.setSmallIcon(R.drawable.ic_stat)
                .setContentTitle("Nothing logged today")
                .setContentText("Tap to add today's spending.")
                .setContentIntent(tap)
                .setAutoCancel(true);
        try {
            nm.notify(1, b.build());
        } catch (SecurityException ignored) {
            // Notifications were not allowed; there is nothing useful to do here.
        }
    }
}
