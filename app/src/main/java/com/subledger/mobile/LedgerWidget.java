package com.subledger.mobile;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.view.View;
import android.widget.RemoteViews;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/** Home-screen widget with the monthly estimate and the next three renewals. */
public final class LedgerWidget extends AppWidgetProvider {
    private static final int[] LINES = {R.id.widget_line1, R.id.widget_line2, R.id.widget_line3};

    @Override
    public void onUpdate(Context context, AppWidgetManager manager, int[] ids) {
        // Records due charges and redraws the widget through update().
        RenewalReminder.refresh(context);
    }

    static void update(Context context, Ledger ledger) {
        AppWidgetManager manager = AppWidgetManager.getInstance(context);
        int[] ids = manager.getAppWidgetIds(new ComponentName(context, LedgerWidget.class));
        if (ids.length == 0) {
            return;
        }
        LocalDate today = LocalDate.now();
        Stats stats = new Stats(ledger, new RateStore(context).load(), today);
        RemoteViews views = new RemoteViews(context.getPackageName(), R.layout.widget);
        views.setTextViewText(R.id.widget_title, "订阅账本 · " + stats.active + " 项进行中");
        views.setTextViewText(R.id.widget_total, stats.counted == 0 ? "每月 —"
                : "每月约 " + LedgerActivity.money(stats.base, stats.monthly));

        List<Subscription> upcoming = new ArrayList<>();
        for (Subscription entry : ledger.subscriptions) {
            if (entry.mode != Subscription.Mode.ENDED && !entry.nextDate(today).isBefore(today)) {
                upcoming.add(entry);
            }
        }
        upcoming.sort(Comparator.comparing((Subscription entry) -> entry.nextDate(today))
                .thenComparing(entry -> entry.title, String.CASE_INSENSITIVE_ORDER));
        for (int i = 0; i < LINES.length; i++) {
            if (i < upcoming.size()) {
                Subscription entry = upcoming.get(i);
                LocalDate next = entry.nextDate(today);
                String line = String.format(Locale.ROOT, "%02d-%02d  ", next.getMonthValue(), next.getDayOfMonth())
                        + entry.title + "  " + LedgerActivity.money(entry.currency, entry.amount)
                        + (entry.trial ? "  试用结束" : "");
                views.setTextViewText(LINES[i], line);
                views.setViewVisibility(LINES[i], View.VISIBLE);
            } else {
                views.setViewVisibility(LINES[i], View.GONE);
            }
        }
        views.setTextViewText(R.id.widget_empty, "近期没有续费");
        views.setViewVisibility(R.id.widget_empty, upcoming.isEmpty() ? View.VISIBLE : View.GONE);
        views.setOnClickPendingIntent(R.id.widget_root, PendingIntent.getActivity(context, 1,
                new Intent(context, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE));
        manager.updateAppWidget(ids, views);
    }
}
