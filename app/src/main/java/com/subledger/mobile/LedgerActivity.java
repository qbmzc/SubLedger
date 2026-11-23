package com.subledger.mobile;

import android.annotation.TargetApi;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.DatePickerDialog;
import android.app.Dialog;
import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.InsetDrawable;
import android.graphics.drawable.LayerDrawable;
import android.graphics.drawable.RippleDrawable;
import android.graphics.drawable.StateListDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.text.InputFilter;
import android.text.InputType;
import android.text.TextUtils;
import android.transition.ChangeBounds;
import android.transition.TransitionManager;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.widget.ArrayAdapter;
import android.widget.AutoCompleteTextView;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Currency;
import java.util.List;
import java.util.function.Supplier;

/** Shared palette, view helpers, page scaffold and exchange-rate refresh for the app's screens. */
abstract class LedgerActivity extends Activity {
    /**
     * Colours for one appearance. Roles, not hues: {@code hero} is the strong surface behind
     * {@code onHero} text, {@code heroText} secondary text on it, {@code green} the accent for text
     * and marks, {@code lime} a soft accent background, {@code raised} a surface lifted above
     * {@code line} (the selected segment), {@code scrim} the dimming behind sheets.
     */
    static final class Palette {
        final int paper, card, line, ink, muted, green, lime, amber, hero, heroText, raised;
        final int onHero = 0xFFFFFFFF;
        final int ripple, scrim;
        final boolean dark;
        /** True when the colours come from the wallpaper (Android 12+). */
        final boolean dynamic;

        private Palette(boolean dark, boolean dynamic, int paper, int card, int line, int ink, int muted,
                        int green, int lime, int amber, int hero, int heroText, int raised) {
            this.dark = dark;
            this.dynamic = dynamic;
            this.paper = paper;
            this.card = card;
            this.line = line;
            this.ink = ink;
            this.muted = muted;
            this.green = green;
            this.lime = lime;
            this.amber = amber;
            this.hero = hero;
            this.heroText = heroText;
            this.raised = raised;
            ripple = (ink & 0x00FFFFFF) | 0x1F000000;
            scrim = dark ? 0x99000000 : 0x52000000;
        }

        /** The app's own green, used before Android 12 or when wallpaper colours are turned off. */
        static final Palette LIGHT = new Palette(false, false, 0xFFF3F4EC, 0xFFFFFFFA, 0xFFE2E8DA,
                0xFF202D25, 0xFF4E6054, 0xFF254436, 0xFFE7EFBB, 0xFF844B1D, 0xFF254436, 0xFFE7EFBB,
                0xFFFFFFFF);
        static final Palette DARK = new Palette(true, false, 0xFF101512, 0xFF1B211D, 0xFF2C352F,
                0xFFE3EAE4, 0xFFA2B2A7, 0xFF9DD3B0, 0xFF34402A, 0xFFE0A46E, 0xFF24483A, 0xFFD9E6B0,
                0xFF3B463F);

        private static final String PREFS = "appearance";
        private static final String DYNAMIC = "dynamic";

        static Palette of(Context context) {
            int mode = context.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
            boolean dark = mode == Configuration.UI_MODE_NIGHT_YES;
            if (dynamicAvailable() && dynamicEnabled(context)) {
                return fromWallpaper(context, dark);
            }
            return dark ? DARK : LIGHT;
        }

        static boolean dynamicAvailable() {
            return Build.VERSION.SDK_INT >= 31;
        }

        static boolean dynamicEnabled(Context context) {
            return context.getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean(DYNAMIC, true);
        }

        static void setDynamic(Context context, boolean enabled) {
            SharedPreferences prefs = context.getSharedPreferences(PREFS, MODE_PRIVATE);
            prefs.edit().putBoolean(DYNAMIC, enabled).apply();
        }

        /**
         * Material You roles from the system's tonal palettes. Shade n of a palette is tone
         * 100 − n/10 (system_accent1_600 is tone 40); tones in between are mixed.
         */
        @TargetApi(31)
        private static Palette fromWallpaper(Context c, boolean dark) {
            if (dark) {
                return new Palette(true, true,
                        mix(c.getColor(android.R.color.system_neutral1_900), c.getColor(android.R.color.system_neutral1_1000), 0.45f),
                        mix(c.getColor(android.R.color.system_neutral1_900), c.getColor(android.R.color.system_neutral1_800), 0.35f),
                        c.getColor(android.R.color.system_neutral2_800),
                        c.getColor(android.R.color.system_neutral1_100),
                        c.getColor(android.R.color.system_neutral2_200),
                        c.getColor(android.R.color.system_accent1_200),
                        c.getColor(android.R.color.system_accent2_700),
                        0xFFE0A46E,
                        c.getColor(android.R.color.system_accent1_700),
                        c.getColor(android.R.color.system_accent1_100),
                        c.getColor(android.R.color.system_neutral2_700));
            }
            return new Palette(false, true,
                    mix(c.getColor(android.R.color.system_neutral1_50), c.getColor(android.R.color.system_neutral1_10), 0.3f),
                    c.getColor(android.R.color.system_neutral1_10),
                    c.getColor(android.R.color.system_neutral2_100),
                    c.getColor(android.R.color.system_neutral1_900),
                    c.getColor(android.R.color.system_neutral2_700),
                    c.getColor(android.R.color.system_accent1_600),
                    c.getColor(android.R.color.system_accent2_100),
                    0xFF844B1D,
                    c.getColor(android.R.color.system_accent1_600),
                    c.getColor(android.R.color.system_accent1_100),
                    c.getColor(android.R.color.system_neutral1_0));
        }

        static int mix(int from, int to, float amount) {
            int r = Math.round(Color.red(from) + (Color.red(to) - Color.red(from)) * amount);
            int g = Math.round(Color.green(from) + (Color.green(to) - Color.green(from)) * amount);
            int b = Math.round(Color.blue(from) + (Color.blue(to) - Color.blue(from)) * amount);
            return 0xFF000000 | (r << 16) | (g << 8) | b;
        }
    }

    /** Follows the system dark theme and wallpaper; the activity is recreated when they change. */
    Palette colors = Palette.LIGHT;

    static final int PICK_ATTACHMENTS = 40;
    static final int EXPORT_MONTH = 41;
    /** Month being exported while the save dialog is open; survives activity recreation. */
    private String pendingExport;
    /** Owner whose attachments are being picked; survives activity recreation. */
    private String pendingOwner;
    private AttachmentStrip pendingStrip;
    private final List<AttachmentStrip> strips = new ArrayList<>();
    /** System bars (and keyboard) around the window, px; updated as they change. */
    int insetLeft, insetTop, insetRight, insetBottom;

    @Override
    protected void onCreate(Bundle state) {
        colors = Palette.of(this);
        if (Palette.dynamicAvailable() && !colors.dynamic) {
            // Dialogs, pickers and the text cursor take the accent from the theme.
            getTheme().applyStyle(R.style.BrandColors, true);
        }
        super.onCreate(state);
        getWindow().setBackgroundDrawable(new ColorDrawable(colors.paper));
        edgeToEdge(getWindow(), colors.paper);
        if (state != null) {
            pendingOwner = state.getString("pendingOwner");
            pendingExport = state.getString("pendingExport");
        }
    }

    /** Lets the window draw behind the status and navigation bars; views pad themselves with the insets. */
    void edgeToEdge(Window window, int surface) {
        window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
        window.setStatusBarColor(Color.TRANSPARENT);
        // Android 10+ keeps 3-button navigation legible by itself; earlier versions get a veil.
        window.setNavigationBarColor(Build.VERSION.SDK_INT >= 29 ? Color.TRANSPARENT
                : (surface & 0x00FFFFFF) | 0xE6000000);
        int flags = View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION;
        if (!colors.dark) {
            flags |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
        }
        window.getDecorView().setSystemUiVisibility(flags);
    }

    /** Left, top, right and bottom system-bar insets, then the keyboard height (0 when hidden). */
    static int[] bars(WindowInsets insets) {
        if (Build.VERSION.SDK_INT >= 30) {
            android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars()
                    | WindowInsets.Type.displayCutout());
            int ime = insets.getInsets(WindowInsets.Type.ime()).bottom;
            return new int[]{bars.left, bars.top, bars.right, bars.bottom, ime > bars.bottom ? ime : 0};
        }
        int stable = insets.getStableInsetBottom();
        int system = insets.getSystemWindowInsetBottom();
        return new int[]{insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(),
                insets.getSystemWindowInsetRight(), Math.min(stable, system), system > stable ? system : 0};
    }

    static WindowInsets consumed(WindowInsets insets) {
        return Build.VERSION.SDK_INT >= 30 ? WindowInsets.CONSUMED : insets.consumeSystemWindowInsets();
    }

    @Override
    protected void onSaveInstanceState(Bundle state) {
        super.onSaveInstanceState(state);
        state.putString("pendingOwner", pendingOwner);
        state.putString("pendingExport", pendingExport);
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Attachments may have been deleted or moved in the viewer.
        refreshStrips();
    }

    /** Rebuilds every attachment strip still on screen, e.g. after a file moved between records. */
    void refreshStrips() {
        strips.removeIf(strip -> !strip.isAttachedToWindow());
        for (AttachmentStrip strip : new ArrayList<>(strips)) {
            strip.rebuild();
        }
    }

    /** Called on the UI thread after a change saved in the background, e.g. added attachments. */
    void onLedgerChanged() {
    }


    static String money(String code, BigDecimal value) {
        int digits = Math.max(0, Currency.getInstance(code).getDefaultFractionDigits());
        return code + " " + value.setScale(digits, RoundingMode.HALF_UP).toPlainString();
    }

    static String percent(BigDecimal part, BigDecimal whole) {
        if (whole.signum() == 0) {
            return "—";
        }
        BigDecimal value = part.multiply(BigDecimal.valueOf(100)).divide(whole, 0, RoundingMode.HALF_UP);
        return value.signum() == 0 && part.signum() > 0 ? "<1%" : value.toPlainString() + "%";
    }

    private RateStore rateStore;

    RateStore rateStore() {
        if (rateStore == null) {
            rateStore = new RateStore(this);
        }
        return rateStore;
    }

    /**
     * Downloads rates on a background thread. {@code after} runs on the UI thread once new rates
     * are stored. Quiet downloads report nothing on failure.
     */
    void downloadRates(boolean quiet, Runnable after) {
        RateStore store = rateStore();
        if (!quiet) {
            toast("正在更新汇率…");
        }
        new Thread(() -> {
            boolean updated;
            try {
                updated = store.download();
            } catch (Exception ex) {
                updated = false;
                if (!quiet) {
                    runOnUiThread(() -> toast("汇率更新失败，请检查网络后重试"));
                }
                return;
            }
            boolean changed = updated;
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed() || !changed) {
                    return;
                }
                if (!quiet) {
                    toast("汇率已更新");
                }
                after.run();
            });
        }, "rate-download").start();
    }

    /**
     * Refreshes rates in the background once they are older than {@link Rates#STALE_AFTER_MILLIS}.
     * Nothing is downloaded until the user has fetched rates at least once.
     */
    void refreshRatesIfStale(Ledger ledger, Runnable after) {
        Rates rates = rateStore().load();
        if (rates.stale(System.currentTimeMillis()) && Stats.converts(ledger, rates.base)) {
            downloadRates(true, after);
        }
    }

    private LedgerStore ledgerStore;

    LedgerStore ledgerStore() {
        if (ledgerStore == null) {
            ledgerStore = new LedgerStore(this);
        }
        return ledgerStore;
    }

    /**
     * Applies a change to the stored ledger, then refreshes reminders and the widget.
     * Shows the error and returns false when the change is rejected or cannot be saved.
     */
    boolean commit(Supplier<Ledger> change) {
        Ledger next;
        try {
            next = change.get();
            ledgerStore().save(next);
        } catch (IllegalArgumentException | IllegalStateException ex) {
            toast(ex.getMessage());
            return false;
        }
        AttachmentStore.prune(this, next);
        RenewalReminder.refresh(this);
        return true;
    }

    /**
     * Subscription icon (continuous-corner tile), or a badge from {@code title} when the
     * subscription was deleted ({@code entry} null). Decorative: the title is always shown next to it.
     */
    ImageView iconView(Subscription entry, String title, int size) {
        ImageView view = new ImageView(this);
        view.setImageDrawable(IconDrawable.of(this, entry, title, dp(size), colors.line));
        view.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        return view;
    }

    /** Horizontal row of attachment thumbnails for one owner, ending with an add button. */
    final class AttachmentStrip extends HorizontalScrollView {
        private final String ownerId;
        private final LinearLayout tiles;

        AttachmentStrip(String ownerId) {
            super(LedgerActivity.this);
            this.ownerId = ownerId;
            tiles = row();
            addView(tiles);
            setHorizontalScrollBarEnabled(false);
            setClipToPadding(false);
            strips.add(this);
            rebuild();
        }

        void rebuild() {
            tiles.removeAllViews();
            List<Attachment> list = ledgerStore().load().attachmentsOf(ownerId);
            for (Attachment attachment : list) {
                tiles.addView(tile(attachment), tileParams());
            }
            if (list.size() < Ledger.MAX_ATTACHMENTS_PER_OWNER) {
                TextView add = action("添加", colors.line, colors.green, () -> pickAttachments(ownerId, this));
                add.setCompoundDrawablesRelativeWithIntrinsicBounds(null,
                        new Glyph(Glyph.PLUS, colors.green, dp(22)), null, null);
                add.setTextSize(12);
                add.setContentDescription("添加截图或发票");
                tiles.addView(add, tileParams());
            }
        }

        private LinearLayout.LayoutParams tileParams() {
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(dp(76), dp(76));
            params.setMarginEnd(dp(8));
            return params;
        }

        private View tile(Attachment attachment) {
            View tile;
            Bitmap preview = attachment.isPdf() ? null : AttachmentStore.thumbnail(LedgerActivity.this, attachment, dp(76));
            if (preview != null) {
                ImageView image = new ImageView(LedgerActivity.this);
                image.setImageBitmap(preview);
                image.setScaleType(ImageView.ScaleType.CENTER_CROP);
                image.setBackground(shape(colors.line, 16));
                image.setForeground(ripple(Color.TRANSPARENT, 16));
                image.setClipToOutline(true);
                tile = image;
            } else {
                TextView text = pill(attachment.isPdf() ? "PDF" : "无法预览", colors.lime, colors.green);
                text.setTextSize(12);
                text.setBackground(ripple(colors.lime, 16));
                tile = text;
            }
            tile.setContentDescription(attachment.name);
            tile.setFocusable(true);
            tile.setOnClickListener(view -> openAttachment(attachment));
            tile.setOnLongClickListener(view -> {
                attachmentMenu(attachment);
                return true;
            });
            Motion.pressable(tile, 0.94f);
            return tile;
        }
    }

    void openAttachment(Attachment attachment) {
        startActivity(new Intent(this, AttachmentActivity.class).putExtra(AttachmentActivity.ID, attachment.id));
    }

    /** Long-press menu of an attachment tile. */
    private void attachmentMenu(Attachment attachment) {
        boolean movable = !moveTargets(ledgerStore().load(), attachment).isEmpty();
        List<String> items = new ArrayList<>();
        List<Runnable> handlers = new ArrayList<>();
        items.add("查看");
        handlers.add(() -> openAttachment(attachment));
        if (movable) {
            items.add("移到其他账期…");
            handlers.add(() -> moveAttachment(attachment, this::attachmentsChanged));
        }
        items.add("删除");
        handlers.add(() -> deleteAttachment(attachment, this::attachmentsChanged));
        menu(attachment.name, items.toArray(new String[0]), handlers.toArray(new Runnable[0]), items.size() - 1);
    }

    private void attachmentsChanged() {
        refreshStrips();
        onLedgerChanged();
    }

    /**
     * Records a subscription's or payment's file can move to: the subscription itself ("订阅资料")
     * and each of its billing periods. Empty for top-up files.
     */
    List<String[]> moveTargets(Ledger ledger, Attachment attachment) {
        List<String[]> targets = new ArrayList<>();
        Payment ownerPayment = ledger.payment(attachment.ownerId);
        String subscriptionId = ownerPayment != null ? ownerPayment.subscriptionId
                : ledger.subscription(attachment.ownerId) != null ? attachment.ownerId : null;
        if (subscriptionId == null) {
            return targets;
        }
        if (ledger.subscription(subscriptionId) != null && !subscriptionId.equals(attachment.ownerId)) {
            targets.add(new String[]{"订阅资料（不属于某一期）", subscriptionId});
        }
        for (Payment payment : ledger.paymentsOf(subscriptionId)) {
            if (!payment.id.equals(attachment.ownerId)) {
                targets.add(new String[]{payment.date + " 这一期 · " + money(payment.currency, payment.amount), payment.id});
            }
        }
        return targets;
    }

    void moveAttachment(Attachment attachment, Runnable after) {
        List<String[]> targets = moveTargets(ledgerStore().load(), attachment);
        String[] labels = new String[targets.size()];
        Runnable[] handlers = new Runnable[targets.size()];
        for (int i = 0; i < labels.length; i++) {
            labels[i] = targets.get(i)[0];
            String owner = targets.get(i)[1];
            String label = labels[i];
            handlers[i] = () -> {
                if (commit(() -> ledgerStore().load().moveAttachment(attachment.id, owner))) {
                    toast("已移到 " + label);
                    after.run();
                }
            };
        }
        menu("移到", labels, handlers, -1);
    }

    void deleteAttachment(Attachment attachment, Runnable after) {
        alert().setTitle("删除附件")
                .setMessage("确定删除“" + attachment.name + "”？")
                .setNegativeButton("取消", null)
                .setPositiveButton("删除", (dialog, button) -> {
                    if (commit(() -> ledgerStore().load().removeAttachment(attachment.id))) {
                        after.run();
                    }
                }).show();
    }

    /** Lets the user pick a month with records, then exports its statement. */
    void chooseMonthToExport() {
        Ledger ledger = ledgerStore().load();
        List<java.time.YearMonth> months = MonthlyReport.months(ledger);
        if (months.isEmpty()) {
            toast("还没有扣款或充值记录");
            return;
        }
        Rates rates = rateStore().load();
        String[] labels = new String[months.size()];
        Runnable[] handlers = new Runnable[months.size()];
        for (int i = 0; i < labels.length; i++) {
            java.time.YearMonth month = months.get(i);
            MonthlyReport report = new MonthlyReport(ledger, rates, month);
            labels[i] = month.getYear() + " 年 " + month.getMonthValue() + " 月 · "
                    + report.records() + " 笔记录 · " + report.files.size() + " 个附件";
            handlers[i] = () -> exportMonth(month);
        }
        menu("按月导出账单与发票", labels, handlers, -1);
    }

    /** Asks where to save the month's statement ZIP; see {@link MonthlyReport}. */
    void exportMonth(java.time.YearMonth month) {
        pendingExport = month.toString();
        startActivityForResult(new Intent(Intent.ACTION_CREATE_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE).setType("application/zip")
                .putExtra(Intent.EXTRA_TITLE, "账单-" + month + ".zip"), EXPORT_MONTH);
    }

    private void finishExport(Uri uri) {
        java.time.YearMonth month = java.time.YearMonth.parse(pendingExport);
        MonthlyReport report = new MonthlyReport(ledgerStore().load(), rateStore().load(), month);
        java.io.File folder = AttachmentStore.folder(this);
        new Thread(() -> {
            try (java.io.OutputStream stream = getContentResolver().openOutputStream(uri, "wt")) {
                if (stream == null) {
                    throw new IOException("无法打开文件");
                }
                int files = report.write(stream, folder);
                runOnUiThread(() -> toast("已导出 " + month.getYear() + " 年 " + month.getMonthValue() + " 月："
                        + report.records() + " 笔记录、" + files + " 个附件"));
            } catch (IOException | RuntimeException ex) {
                runOnUiThread(() -> toast("导出失败，请检查文件位置和可用空间"));
            }
        }, "month-export").start();
    }

    /** Opens the system picker for images and PDFs to attach to {@code ownerId}. */
    void pickAttachments(String ownerId, AttachmentStrip strip) {
        pendingOwner = ownerId;
        pendingStrip = strip;
        startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType("*/*")
                .putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"image/*", Attachment.PDF})
                .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true), PICK_ATTACHMENTS);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == EXPORT_MONTH) {
            if (resultCode == RESULT_OK && data != null && data.getData() != null && pendingExport != null) {
                finishExport(data.getData());
            }
            pendingExport = null;
            return;
        }
        if (requestCode != PICK_ATTACHMENTS) {
            return;
        }
        String owner = pendingOwner;
        AttachmentStrip strip = pendingStrip;
        pendingOwner = null;
        pendingStrip = null;
        if (resultCode != RESULT_OK || data == null || owner == null) {
            return;
        }
        List<Uri> uris = new ArrayList<>();
        ClipData clip = data.getClipData();
        for (int i = 0; clip != null && i < clip.getItemCount(); i++) {
            uris.add(clip.getItemAt(i).getUri());
        }
        if (uris.isEmpty() && data.getData() != null) {
            uris.add(data.getData());
        }
        toast("正在添加附件…");
        new Thread(() -> {
            List<Attachment> added = new ArrayList<>();
            for (Uri uri : uris) {
                try {
                    added.add(AttachmentStore.copyIn(this, uri, owner));
                } catch (IOException ex) {
                    // Counted below; other files are still added.
                }
            }
            int failed = uris.size() - added.size();
            runOnUiThread(() -> finishAttaching(added, failed, strip));
        }, "attachment-import").start();
    }

    private void finishAttaching(List<Attachment> added, int failed, AttachmentStrip strip) {
        if (added.isEmpty()) {
            toast("无法读取所选文件，只支持图片和 PDF（25 MB 以内）");
            return;
        }
        AttachmentStore.moveIn(this, added);
        if (!commit(() -> ledgerStore().load().addAttachments(added))) {
            AttachmentStore.prune(this, ledgerStore().load());
            return;
        }
        toast("已添加 " + added.size() + " 个附件" + (failed > 0 ? "，" + failed + " 个无法读取" : ""));
        if (strip != null && strip.isAttachedToWindow()) {
            strip.rebuild();
        }
        if (!isFinishing() && !isDestroyed()) {
            onLedgerChanged();
        }
    }

    /** Parses a non-negative decimal typed by the user (comma allowed), or returns null. */
    static BigDecimal parseAmount(String text) {
        String value = text.trim().replace(',', '.');
        return value.matches("[0-9]+(\\.[0-9]+)?") ? new BigDecimal(value) : null;
    }

    interface DateSelection {
        void select(LocalDate date);
    }

    void chooseDate(LocalDate date, DateSelection selection) {
        DatePickerDialog dialog = new DatePickerDialog(this,
                (view, year, month, day) -> selection.select(LocalDate.of(year, month + 1, day)),
                date.getYear(), date.getMonthValue() - 1, date.getDayOfMonth());
        roundWindow(dialog);
        dialog.show();
    }

    /** AlertDialog builder whose dialogs use the palette's surface and continuous corners. */
    AlertDialog.Builder alert() {
        return new AlertDialog.Builder(this) {
            @Override
            public AlertDialog create() {
                AlertDialog dialog = super.create();
                roundWindow(dialog);
                return dialog;
            }
        };
    }

    private void roundWindow(Dialog dialog) {
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new InsetDrawable(new Squircle(colors.card, dp(28)), dp(16)));
            window.getDecorView().setClipToOutline(true);
        }
    }

    /** A new bottom sheet; fill {@link Sheet#body}, then call {@link Sheet#show()}. */
    Sheet sheet(String title) {
        return new Sheet(this, title);
    }

    /**
     * Action sheet: one row per item; choosing a row closes the sheet and runs its handler.
     * {@code destructive} is the index drawn as a warning, or −1.
     */
    Sheet menu(String title, String[] items, Runnable[] handlers, int destructive) {
        Sheet sheet = sheet(title).detents(0.5f, 1f);
        LinearLayout group = group();
        for (int i = 0; i < items.length; i++) {
            Runnable handler = handlers[i];
            TextView row = label(items[i], 16, i == destructive ? colors.amber : colors.ink, false);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setMinHeight(dp(52));
            row.setPadding(dp(18), dp(10), dp(18), dp(10));
            row.setBackground(ripple(Color.TRANSPARENT, 0));
            row.setFocusable(true);
            row.setOnClickListener(view -> {
                sheet.dismiss();
                handler.run();
            });
            addRow(group, row);
        }
        sheet.body.addView(group);
        return sheet.show();
    }

    /** Card whose rows are separated by hairlines, like an iOS grouped table. */
    LinearLayout group() {
        LinearLayout group = column();
        group.setBackground(shape(colors.card, 22));
        group.setClipToOutline(true);
        return group;
    }

    void addRow(LinearLayout group, View row) {
        if (group.getChildCount() > 0) {
            View hairline = new View(this);
            hairline.setBackgroundColor(colors.line);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, Math.max(1, dp(1) / 2));
            params.setMarginStart(dp(18));
            group.addView(hairline, params);
        }
        group.addView(row, new LinearLayout.LayoutParams(-1, -2));
    }

    /** Label on the start, value on the end: one line of facts in a {@link #group()}. */
    View factRow(String name, String value, int valueColor) {
        LinearLayout line = row();
        line.setGravity(Gravity.CENTER_VERTICAL);
        line.setMinimumHeight(dp(48));
        line.setPadding(dp(18), dp(10), dp(18), dp(10));
        line.addView(label(name, 15, colors.muted, false), new LinearLayout.LayoutParams(-2, -2));
        TextView text = label(value, 15, valueColor, false);
        text.setGravity(Gravity.END);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, -2, 1f);
        params.setMarginStart(dp(16));
        line.addView(text, params);
        return line;
    }

    // --- Pages and adaptive layout ----------------------------------------------------------

    /** Window width in dp: below 600 is compact (phone), 600–839 medium, 840 and up expanded. */
    int widthDp() {
        return getResources().getConfiguration().screenWidthDp;
    }

    boolean medium() {
        return widthDp() >= 600;
    }

    boolean expanded() {
        return widthDp() >= 840;
    }

    /** Side margin of the content, dp. */
    int margin() {
        return expanded() ? 32 : medium() ? 24 : 16;
    }

    /** Width available to a page's content, dp. */
    int contentWidthDp(int maxWidthDp) {
        float density = getResources().getDisplayMetrics().density;
        int insets = Math.round((insetLeft + insetRight) / density);
        return Math.min(widthDp() - insets - 2 * margin(), maxWidthDp);
    }

    /** Columns of at least {@code minCellDp} that fit in {@code availableDp}. */
    static int columns(int availableDp, int minCellDp, int gapDp) {
        return Math.max(1, (availableDp + gapDp) / (minCellDp + gapDp));
    }

    /** Lays {@code cells} out in rows of {@code columns} equal cells; cells in a row share its height. */
    void grid(LinearLayout parent, List<View> cells, int columns, int gapDp) {
        for (int start = 0; start < cells.size(); start += columns) {
            if (start > 0) {
                gap(parent, gapDp);
            }
            LinearLayout line = row();
            for (int i = 0; i < columns; i++) {
                int index = start + i;
                View cell = index < cells.size() ? cells.get(index) : new View(this);
                LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, -1, 1f);
                if (i > 0) {
                    params.setMarginStart(dp(gapDp));
                }
                line.addView(cell, params);
            }
            parent.addView(line, new LinearLayout.LayoutParams(-1, -2));
        }
    }

    /** Column that never grows wider than {@code maxWidth} px plus its padding; centre it in its parent. */
    static final class Bounded extends LinearLayout {
        private final int maxWidth;

        Bounded(Context context, int maxWidth) {
            super(context);
            this.maxWidth = maxWidth;
            setOrientation(VERTICAL);
        }

        @Override
        protected void onMeasure(int widthSpec, int heightSpec) {
            int cap = maxWidth + getPaddingLeft() + getPaddingRight();
            if (MeasureSpec.getMode(widthSpec) != MeasureSpec.UNSPECIFIED && MeasureSpec.getSize(widthSpec) > cap) {
                widthSpec = MeasureSpec.makeMeasureSpec(cap, MeasureSpec.EXACTLY);
            }
            super.onMeasure(widthSpec, heightSpec);
        }
    }

    /**
     * A screen drawn edge to edge: content scrolls under a pinned bar. With a large title the bar
     * starts clear and shows a compact title once the large one scrolls away; with a side pane
     * (expanded windows) the bar keeps its title and two columns scroll on their own.
     */
    final class Page {
        static final int BAR_DP = 60;
        final FrameLayout root;
        final ScrollView scroll;
        final LinearLayout content;
        final LinearLayout actions;
        final FrameLayout bar;
        /** Second column on the start side, or null. */
        final ScrollView sideScroll;
        final LinearLayout side;
        private final int sideWidth;
        private final View hairline;
        private final TextView compactTitle;
        private final Drawable barFill;
        private TextView largeTitle;
        private TextView fab;
        private String fabLabel;
        private boolean fabCollapsed, compactShown;
        private int lastScroll;

        Page(String title, boolean back, boolean large, int maxWidthDp, int sideWidthDp) {
            root = new FrameLayout(LedgerActivity.this);
            root.setBackgroundColor(colors.paper);
            sideWidth = dp(sideWidthDp);

            scroll = new ScrollView(LedgerActivity.this);
            scroll.setFillViewport(true);
            scroll.setClipToPadding(false);
            scroll.setId(View.generateViewId());
            FrameLayout center = new FrameLayout(LedgerActivity.this);
            content = new Bounded(LedgerActivity.this, dp(maxWidthDp));
            center.addView(content, new FrameLayout.LayoutParams(-1, -2, Gravity.CENTER_HORIZONTAL));
            scroll.addView(center, new FrameLayout.LayoutParams(-1, -2));
            FrameLayout.LayoutParams scrollParams = new FrameLayout.LayoutParams(-1, -1);
            scrollParams.setMarginStart(sideWidth);
            root.addView(scroll, scrollParams);

            if (sideWidthDp > 0) {
                sideScroll = new ScrollView(LedgerActivity.this);
                sideScroll.setClipToPadding(false);
                side = column();
                sideScroll.addView(side);
                root.addView(sideScroll, new FrameLayout.LayoutParams(sideWidth, -1, Gravity.START));
                sideScroll.setOnScrollChangeListener((view, x, y, oldX, oldY) -> lift());
            } else {
                sideScroll = null;
                side = null;
            }

            bar = new FrameLayout(LedgerActivity.this);
            barFill = new ColorDrawable(colors.paper);
            barFill.setAlpha(0);
            bar.setBackground(barFill);
            hairline = new View(LedgerActivity.this);
            hairline.setBackgroundColor(colors.line);
            hairline.setAlpha(0f);
            bar.addView(hairline, new FrameLayout.LayoutParams(-1, Math.max(1, dp(1) / 2), Gravity.BOTTOM));
            if (back) {
                ImageView button = new ImageView(LedgerActivity.this);
                button.setImageDrawable(new Glyph(Glyph.BACK, colors.ink, dp(22)));
                button.setScaleType(ImageView.ScaleType.CENTER);
                button.setBackground(ripple(colors.card, 22));
                button.setContentDescription("返回");
                button.setOnClickListener(view -> finish());
                Motion.pressable(button, 0.9f);
                bar.addView(button, new FrameLayout.LayoutParams(dp(44), dp(44), Gravity.START | Gravity.CENTER_VERTICAL));
            }
            compactTitle = label(title, 17, colors.ink, true);
            compactTitle.setSingleLine(true);
            compactTitle.setEllipsize(TextUtils.TruncateAt.END);
            compactTitle.setGravity(Gravity.CENTER);
            FrameLayout.LayoutParams titleParams = new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER);
            if (sideWidthDp > 0) {
                // Desktop: the title sits at the start, over the side pane.
                titleParams.gravity = Gravity.START | Gravity.CENTER_VERTICAL;
                titleParams.setMarginStart(dp(back ? 56 : 8));
                compactTitle.setTextSize(22);
            } else {
                titleParams.leftMargin = dp(112);
                titleParams.rightMargin = dp(112);
            }
            bar.addView(compactTitle, titleParams);
            compactTitle.setAlpha(large && sideWidthDp == 0 ? 0f : 1f);
            compactShown = compactTitle.getAlpha() > 0;
            actions = row();
            actions.setGravity(Gravity.CENTER_VERTICAL);
            bar.addView(actions, new FrameLayout.LayoutParams(-2, -2, Gravity.END | Gravity.CENTER_VERTICAL));
            root.addView(bar, new FrameLayout.LayoutParams(-1, -2, Gravity.TOP));

            if (large && sideWidthDp == 0) {
                largeTitle = heading(label(title, 32, colors.ink, true));
                content.addView(largeTitle);
                gap(content, 14);
            }
            scroll.setOnScrollChangeListener((view, x, y, oldX, oldY) -> {
                lift();
                updateFab(y);
            });
            root.setOnApplyWindowInsetsListener((view, insets) -> {
                int[] bars = bars(insets);
                insetLeft = bars[0];
                insetTop = bars[1];
                insetRight = bars[2];
                insetBottom = Math.max(bars[3], bars[4]);
                applyInsets();
                return consumed(insets);
            });
            applyInsets();
        }

        /** Pill-shaped bar button, e.g. 统计 or ＋ 新建. */
        TextView action(String label, boolean strong, Runnable work) {
            TextView button = LedgerActivity.this.action(label, strong ? colors.hero : colors.card,
                    strong ? colors.onHero : colors.green, work);
            button.setBackground(ripple(strong ? colors.hero : colors.card, 22));
            button.setPadding(dp(16), 0, dp(16), 0);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-2, dp(40));
            params.setMarginStart(dp(8));
            actions.addView(button, params);
            return button;
        }

        /** Extended floating button at the bottom end; it shrinks to its icon while scrolling down. */
        TextView fab(String label, Runnable work) {
            fabLabel = label;
            fab = LedgerActivity.this.action(label, colors.hero, colors.onHero, work);
            fab.setBackground(ripple(colors.hero, 20));
            fab.setCompoundDrawablesRelativeWithIntrinsicBounds(new Glyph(Glyph.PLUS, colors.onHero, dp(24)), null, null, null);
            fab.setCompoundDrawablePadding(dp(8));
            fab.setPadding(dp(16), 0, dp(20), 0);
            fab.setTextSize(16);
            fab.setElevation(dp(6));
            fab.setContentDescription(label);
            Motion.pressable(fab, 0.94f);
            root.addView(fab, new FrameLayout.LayoutParams(-2, dp(56), Gravity.BOTTOM | Gravity.END));
            applyInsets();
            return fab;
        }

        private void updateFab(int y) {
            if (fab == null) {
                return;
            }
            boolean collapse = y > lastScroll + dp(6) && y > dp(48);
            boolean expand = y < lastScroll - dp(6) || y < dp(24);
            if (Math.abs(y - lastScroll) > dp(6) || y < dp(24)) {
                lastScroll = y;
            }
            if ((collapse && !fabCollapsed) || (expand && fabCollapsed)) {
                fabCollapsed = collapse;
                TransitionManager.beginDelayedTransition(root, new ChangeBounds()
                        .setDuration(260).setInterpolator(Motion.EMPHASIZED).addTarget(fab));
                fab.setText(fabCollapsed ? "" : fabLabel);
                fab.setCompoundDrawablePadding(fabCollapsed ? 0 : dp(8));
                fab.setPadding(dp(16), 0, fabCollapsed ? dp(16) : dp(20), 0);
            }
        }

        /** The bar turns opaque, with a hairline, once anything has scrolled under it. */
        private void lift() {
            int y = Math.max(scroll.getScrollY(), sideScroll == null ? 0 : sideScroll.getScrollY());
            float amount = Math.min(1f, y / (float) dp(12));
            barFill.setAlpha(Math.round(255 * amount));
            hairline.setAlpha(amount);
            if (largeTitle != null) {
                boolean show = largeTitle.getBottom() > 0 && y > largeTitle.getBottom() - bar.getHeight();
                if (show != compactShown) {
                    compactShown = show;
                    compactTitle.animate().alpha(show ? 1f : 0f).setDuration(160).start();
                }
            }
        }

        void applyInsets() {
            int edge = dp(margin());
            int barHeight = insetTop + dp(BAR_DP);
            bar.getLayoutParams().height = barHeight;
            bar.setPadding(insetLeft + dp(12), insetTop, insetRight + dp(12), 0);
            int bottom = insetBottom + dp(fab == null ? 32 : 104);
            if (sideScroll != null) {
                sideScroll.getLayoutParams().width = sideWidth + insetLeft;
                side.setPadding(insetLeft + edge, barHeight + dp(4), dp(8), bottom);
                ((FrameLayout.LayoutParams) scroll.getLayoutParams()).setMarginStart(sideWidth + insetLeft);
                content.setPadding(dp(16), barHeight + dp(4), edge + insetRight, bottom);
            } else {
                content.setPadding(edge + insetLeft, barHeight + dp(4), edge + insetRight, bottom);
            }
            if (fab != null) {
                FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) fab.getLayoutParams();
                params.setMargins(0, 0, dp(16) + insetRight, dp(16) + insetBottom);
                params.setMarginEnd(dp(16) + insetRight);
                fab.setLayoutParams(params);
            }
            root.requestLayout();
        }

        /** Shows the page, keeping the scroll position of the page it replaces. */
        void show(int scrollY) {
            setContentView(root);
            root.requestApplyInsets();
            ScrollView view = scroll;
            view.post(() -> {
                view.scrollTo(0, scrollY);
                lastScroll = view.getScrollY();
                lift();
            });
        }
    }

    private ScrollView screenScroll;

    /**
     * Replaces the content with a sub-page that has a back button and a large title, keeping the
     * scroll position across re-renders. Returns the column to fill.
     */
    LinearLayout screen(String title, View headerAction) {
        return screen(title, headerAction, 840);
    }

    LinearLayout screen(String title, View headerAction, int maxWidthDp) {
        return page(title, headerAction, maxWidthDp).content;
    }

    Page page(String title, View headerAction, int maxWidthDp) {
        int scrollY = screenScroll == null ? 0 : screenScroll.getScrollY();
        Page page = new Page(title, true, true, maxWidthDp, 0);
        if (headerAction != null) {
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-2, dp(40));
            headerAction.setPadding(dp(16), 0, dp(16), 0);
            page.actions.addView(headerAction, params);
        }
        screenScroll = page.scroll;
        page.show(scrollY);
        return page;
    }

    void section(LinearLayout root, String title) {
        if (root.getChildCount() > 0) {
            gap(root, 26);
        }
        root.addView(heading(label(title, 20, colors.ink, true)));
        gap(root, 10);
    }

    // --- Controls -------------------------------------------------------------------------

    static String fieldValue(EditText field) {
        return field.getText().toString().trim();
    }

    void addField(LinearLayout parent, String title, View field) {
        gap(parent, 12);
        if (field.getId() == View.NO_ID) {
            field.setId(View.generateViewId());
        }
        TextView caption = label(title, 13, colors.muted, true);
        caption.setLabelFor(field.getId());
        caption.setPadding(dp(4), 0, dp(4), 0);
        parent.addView(caption);
        gap(parent, 6);
        parent.addView(field, new LinearLayout.LayoutParams(-1, -2));
    }

    /** Small explanatory text under a field. */
    void hint(LinearLayout parent, String text) {
        gap(parent, 4);
        TextView hint = label(text, 12, colors.muted, false);
        hint.setPadding(dp(4), 0, dp(4), 0);
        parent.addView(hint);
    }

    EditText input(String hint, String value, int type, int maxLength) {
        EditText field = new EditText(this);
        styleInput(field, hint, value, type, maxLength);
        return field;
    }

    /** Text input that offers {@code suggestions} in a drop-down. */
    AutoCompleteTextView suggestInput(String hint, String value, List<String> suggestions, int maxLength) {
        AutoCompleteTextView field = new AutoCompleteTextView(this);
        styleInput(field, hint, value, InputType.TYPE_CLASS_TEXT, maxLength);
        field.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_dropdown_item_1line, suggestions));
        field.setDropDownBackgroundDrawable(new Squircle(colors.card, dp(16)));
        field.setThreshold(1);
        field.setOnClickListener(view -> field.showDropDown());
        return field;
    }

    /** On/off switch with its label on the start, like a settings row. */
    CompoundButton check(String text, boolean checked) {
        Switch toggle = new Switch(this);
        toggle.setText(text);
        toggle.setTextColor(colors.ink);
        toggle.setTextSize(15);
        toggle.setChecked(checked);
        toggle.setMinHeight(dp(52));
        toggle.setSwitchPadding(dp(16));
        int inset = dp(4);
        StateListDrawable track = new StateListDrawable();
        track.addState(new int[]{android.R.attr.state_checked},
                new Squircle(colors.green, dp(16)).padding(inset, 0, inset, 0));
        track.addState(new int[0], new Squircle(colors.line, dp(16)).stroke(colors.muted, dp(2))
                .padding(inset, 0, inset, 0));
        track.setEnterFadeDuration(150);
        track.setExitFadeDuration(150);
        StateListDrawable thumb = new StateListDrawable();
        thumb.addState(new int[]{android.R.attr.state_checked}, knob(colors.card));
        thumb.addState(new int[0], knob(colors.muted));
        thumb.setEnterFadeDuration(150);
        thumb.setExitFadeDuration(150);
        toggle.setTrackDrawable(track);
        toggle.setThumbDrawable(thumb);
        toggle.setTrackTintList(null);
        toggle.setThumbTintList(null);
        toggle.setBackground(null);
        return toggle;
    }

    private Drawable knob(int color) {
        GradientDrawable dot = new GradientDrawable();
        dot.setShape(GradientDrawable.OVAL);
        dot.setColor(color);
        dot.setSize(dp(24), dp(24));
        return new InsetDrawable(dot, 0, dp(4), 0, dp(4));
    }

    /** Segmented control for a few fixed choices; see {@link Segmented}. */
    Segmented segmented(String[] items, int selected, Segmented.Listener listener) {
        return new Segmented(this, colors, items, selected, listener);
    }

    /** Field-styled button that opens a picker, e.g. a date. */
    TextView pickerField(String value) {
        TextView field = label(value, 16, colors.ink, false);
        field.setGravity(Gravity.CENTER_VERTICAL);
        field.setMinHeight(dp(52));
        field.setPadding(dp(14), 0, dp(14), 0);
        LayerDrawable background = new LayerDrawable(new Drawable[]{
                ripple(colors.card, 14), new Glyph(Glyph.DOWN, colors.muted, dp(20))});
        background.setLayerGravity(1, Gravity.END | Gravity.CENTER_VERTICAL);
        background.setLayerSize(1, dp(20), dp(20));
        background.setLayerInsetEnd(1, dp(14));
        field.setBackground(background);
        field.setFocusable(true);
        field.setClickable(true);
        return field;
    }

    private void styleInput(EditText field, String hint, String value, int type, int maxLength) {
        field.setTextColor(colors.ink);
        field.setHintTextColor(colors.muted);
        StateListDrawable background = new StateListDrawable();
        background.addState(new int[]{android.R.attr.state_focused},
                new Squircle(colors.card, dp(14)).stroke(colors.green, dp(2)));
        background.addState(new int[0], new Squircle(colors.card, dp(14)));
        background.setEnterFadeDuration(120);
        background.setExitFadeDuration(120);
        field.setBackground(background);
        field.setMinHeight(dp(52));
        field.setPadding(dp(14), dp(10), dp(14), dp(10));
        field.setTextSize(16);
        field.setInputType(type);
        field.setFilters(new InputFilter[]{new InputFilter.LengthFilter(maxLength)});
        field.setHint(hint);
        field.setText(value);
    }

    Spinner options(String[] items, int selected) {
        Spinner spinner = new Spinner(this, Spinner.MODE_DROPDOWN);
        ArrayAdapter<String> adapter = new ArrayAdapter<String>(this,
                android.R.layout.simple_spinner_item, items) {
            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                TextView view = (TextView) super.getView(position, convertView, parent);
                view.setTextColor(colors.ink);
                view.setTextSize(16);
                return view;
            }

            @Override
            public View getDropDownView(int position, View convertView, ViewGroup parent) {
                TextView view = (TextView) super.getDropDownView(position, convertView, parent);
                view.setTextColor(colors.ink);
                view.setBackground(ripple(Color.TRANSPARENT, 0));
                return view;
            }
        };
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinner.setAdapter(adapter);
        spinner.setSelection(selected);
        LayerDrawable background = new LayerDrawable(new Drawable[]{
                ripple(colors.card, 14), new Glyph(Glyph.DOWN, colors.muted, dp(20))});
        background.setLayerGravity(1, Gravity.END | Gravity.CENTER_VERTICAL);
        background.setLayerSize(1, dp(20), dp(20));
        background.setLayerInsetEnd(1, dp(14));
        spinner.setBackground(background);
        spinner.setPopupBackgroundDrawable(new Squircle(colors.card, dp(16)));
        spinner.setMinimumHeight(dp(52));
        spinner.setPadding(dp(4), 0, dp(40), 0);
        return spinner;
    }

    static final Typeface BOLD = Typeface.DEFAULT_BOLD;

    /** Marks a title for screen readers' heading navigation (Android 9+). */
    static TextView heading(TextView text) {
        if (Build.VERSION.SDK_INT >= 28) {
            text.setAccessibilityHeading(true);
        }
        return text;
    }

    TextView label(String value, int size, int color, boolean bold) {
        TextView text = new TextView(this);
        text.setText(value);
        text.setTextSize(size);
        text.setTextColor(color);
        if (bold) {
            text.setTypeface(BOLD);
        }
        return text;
    }

    /** Money in tabular figures, so amounts line up. */
    TextView amount(String value, int size, int color) {
        TextView text = label(value, size, color, true);
        text.setFontFeatureSettings("tnum");
        return text;
    }

    TextView pill(String value, int background, int foreground) {
        TextView text = label(value, 14, foreground, true);
        text.setBackground(shape(background, 14));
        text.setGravity(Gravity.CENTER);
        return text;
    }

    TextView action(String value, int background, int foreground, Runnable work) {
        android.widget.Button button = new android.widget.Button(this);
        button.setText(value);
        button.setAllCaps(false);
        button.setTextColor(foreground);
        button.setTextSize(15);
        button.setTypeface(BOLD);
        button.setBackground(ripple(background, 16));
        Motion.pressable(button, 0.96f);
        button.setMinHeight(dp(46));
        button.setMinWidth(0);
        button.setMinimumWidth(0);
        button.setPadding(dp(10), dp(5), dp(10), dp(5));
        if (work != null) {
            button.setOnClickListener(view -> work.run());
        }
        return button;
    }

    LinearLayout panel(int background, int radius) {
        LinearLayout layout = column();
        layout.setBackground(shape(background, radius));
        layout.setPadding(dp(18), dp(16), dp(18), dp(16));
        return layout;
    }

    /** Makes a card tappable: ripple in its shape, press-to-shrink and a click handler. */
    void tappable(View view, int background, int radius, Runnable work) {
        view.setBackground(ripple(background, radius));
        view.setFocusable(true);
        view.setOnClickListener(clicked -> work.run());
        Motion.pressable(view, 0.98f);
    }

    /** Continuous-corner fill; {@code radius} in dp. */
    Squircle shape(int color, int radius) {
        return new Squircle(color, dp(radius));
    }

    /** Ripple confined to a continuous-corner shape; {@code color} may be transparent. */
    RippleDrawable ripple(int color, int radius) {
        return new RippleDrawable(ColorStateList.valueOf(colors.ripple), shape(color, radius),
                shape(0xFFFFFFFF, radius));
    }

    LinearLayout column() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setLayoutParams(new LinearLayout.LayoutParams(-1, -2));
        return layout;
    }

    LinearLayout row() {
        return new LinearLayout(this);
    }

    void gap(LinearLayout parent, int height) {
        parent.addView(new View(this), new ViewGroup.LayoutParams(1, dp(height)));
    }

    int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    void toast(String text) {
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show();
    }
}
