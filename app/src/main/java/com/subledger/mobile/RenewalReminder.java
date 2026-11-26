package com.subledger.mobile;

import android.Manifest;
import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public final class RenewalReminder extends BroadcastReceiver {
    private static final String CHANNEL = "renewal";
    private static final String ACTION = "com.subledger.mobile.RENEWAL_CHECK";
    private static final LocalTime MORNING = LocalTime.of(9, 0);

    /** The manifest's system broadcasts plus this receiver's own alarm. */
    private static final Set<String> TRIGGERS = new HashSet<>(java.util.Arrays.asList(
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED, Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED, ACTION));

    @Override
    public void onReceive(Context context, Intent intent) {
        if (!TRIGGERS.contains(intent.getAction())) {
            return;
        }
        // Boot, clock changes and the scheduled check all use the same calculation.
        refresh(context);
    }

    static boolean enabled(Context context) {
        if (Build.VERSION.SDK_INT >= 33
                && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            return false;
        }
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        NotificationChannel channel = manager.getNotificationChannel(CHANNEL);
        return manager.areNotificationsEnabled()
                && (channel == null || channel.getImportance() != NotificationManager.IMPORTANCE_NONE);
    }

    /**
     * Records due auto-renewals, updates the home-screen widget, posts due reminders and schedules
     * the next check. Idempotent; called after every write and on system events.
     */
    static void refresh(Context context) {
        LedgerStore store = new LedgerStore(context);
        Ledger ledger;
        try {
            store.recordDue(new RateStore(context).load(), LocalDate.now());
            ledger = store.load();
        } catch (IllegalStateException ex) {
            return;
        }
        LedgerWidget.update(context, ledger);

        AlarmManager alarms = context.getSystemService(AlarmManager.class);
        PendingIntent check = PendingIntent.getBroadcast(context, 0,
                new Intent(context, RenewalReminder.class).setAction(ACTION),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        alarms.cancel(check);
        if (!enabled(context)) {
            return;
        }

        NotificationManager notifications = context.getSystemService(NotificationManager.class);
        NotificationChannel channel = new NotificationChannel(CHANNEL, "订阅续费提醒",
                NotificationManager.IMPORTANCE_DEFAULT);
        notifications.createNotificationChannel(channel);

        PendingIntent open = PendingIntent.getActivity(context, 0,
                new Intent(context, MainActivity.class)
                        .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        SharedPreferences receipts = context.getSharedPreferences("reminders", Context.MODE_PRIVATE);
        SharedPreferences.Editor changes = receipts.edit();
        Set<String> retained = new HashSet<>();
        ZonedDateTime now = ZonedDateTime.now();
        LocalDate today = now.toLocalDate();
        ZoneId zone = now.getZone();
        long earliest = Long.MAX_VALUE;

        Map<String, Ledger.Coverage> coverage = new HashMap<>();
        for (Wallet wallet : ledger.wallets) {
            coverage.put(wallet.id, ledger.coverage(wallet, today));
        }
        for (Subscription entry : ledger.subscriptions) {
            if (entry.mode == Subscription.Mode.ENDED || entry.reminderDays < 0) {
                continue;
            }
            retained.add(entry.id);
            long index = entry.mode == Subscription.Mode.AUTO ? entry.indexOnOrAfter(today) : 0;
            LocalDate renewal = entry.occurrence(index);
            if (renewal.isBefore(today)) {
                continue;
            }

            // If several weekly notice windows overlap, remind for the closest renewal first.
            String marker = renewal + "/" + entry.reminderDays;
            ZonedDateTime noticeAt = renewal.minusDays(entry.reminderDays).atTime(MORNING).atZone(zone);
            if (!noticeAt.isAfter(now) && !marker.equals(receipts.getString(entry.id, ""))) {
                String details = renewal + (entry.trial ? " 开始扣费 · " : " · ")
                        + entry.currency + " " + entry.amount.toPlainString();
                if (entry.trial) {
                    details += "，不想继续请在此前取消";
                }
                Wallet wallet = ledger.payingWallet(entry);
                if (wallet != null && coverage.get(wallet.id).insufficient()) {
                    details += " · 「" + wallet.name + "」余额不足";
                }
                Notification reminder = new Notification.Builder(context, CHANNEL)
                        .setSmallIcon(R.drawable.ic_notification)
                        .setContentTitle(entry.title + (entry.trial ? " 试用即将结束" : " 即将续费"))
                        .setStyle(new Notification.BigTextStyle().bigText(details))
                        .setContentText(details)
                        .setVisibility(Notification.VISIBILITY_PRIVATE)
                        .setAutoCancel(true)
                        .setContentIntent(open)
                        .build();
                notifications.notify(entry.id, 0, reminder);
                changes.putString(entry.id, marker);
            }

            if (!noticeAt.isAfter(now)) {
                if (entry.mode != Subscription.Mode.AUTO) {
                    continue;
                }
                ZonedDateTime nextNotice = entry.occurrence(index + 1)
                        .minusDays(entry.reminderDays).atTime(MORNING).atZone(zone);
                ZonedDateTime afterRenewal = renewal.plusDays(1).atTime(MORNING).atZone(zone);
                noticeAt = nextNotice.isAfter(afterRenewal) ? nextNotice : afterRenewal;
            }
            earliest = Math.min(earliest, noticeAt.toInstant().toEpochMilli());
        }
        for (String oldId : receipts.getAll().keySet()) {
            if (!retained.contains(oldId)) {
                changes.remove(oldId);
                notifications.cancel(oldId, 0);
            }
        }
        changes.apply();
        if (earliest != Long.MAX_VALUE) {
            alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, earliest, check);
        }
    }

    static void clearReceipt(Context context, String id) {
        context.getSharedPreferences("reminders", Context.MODE_PRIVATE).edit().remove(id).apply();
        context.getSystemService(NotificationManager.class).cancel(id, 0);
    }
}
