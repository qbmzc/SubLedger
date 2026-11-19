package com.subledger.mobile;

import android.app.Activity;
import android.app.DatePickerDialog;
import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.text.InputFilter;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.AutoCompleteTextView;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
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

/** Shared palette, view helpers and exchange-rate refresh for the app's screens. */
abstract class LedgerActivity extends Activity {
    /** Colours for one appearance. Roles, not hues: {@code hero} is the strong surface behind white text. */
    static final class Palette {
        final int paper, card, line, ink, muted, green, lime, amber, hero, heroText;

        private Palette(int paper, int card, int line, int ink, int muted, int green, int lime, int amber,
                        int hero, int heroText) {
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
        }

        static final Palette LIGHT = new Palette(0xFFF5F5EE, 0xFFFFFFFA, 0xFFE2E8DA, 0xFF202D25,
                0xFF526357, 0xFF254436, 0xFFE7EFBB, 0xFF844B1D, 0xFF254436, 0xFFE7EFBB);
        static final Palette DARK = new Palette(0xFF121714, 0xFF1B211D, 0xFF2C352F, 0xFFE3EAE4,
                0xFFA2B2A7, 0xFF9DD3B0, 0xFF34402A, 0xFFE0A46E, 0xFF24483A, 0xFFD9E6B0);

        static Palette of(Context context) {
            int mode = context.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
            return mode == Configuration.UI_MODE_NIGHT_YES ? DARK : LIGHT;
        }
    }

    /** Follows the system dark theme; the activity is recreated when it changes. */
    Palette colors = Palette.LIGHT;

    static final int PICK_ATTACHMENTS = 40;
    /** Owner whose attachments are being picked; survives activity recreation. */
    private String pendingOwner;
    private AttachmentStrip pendingStrip;
    private final List<AttachmentStrip> strips = new ArrayList<>();

    @Override
    protected void onCreate(Bundle state) {
        colors = Palette.of(this);
        super.onCreate(state);
        if (state != null) {
            pendingOwner = state.getString("pendingOwner");
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle state) {
        super.onSaveInstanceState(state);
        state.putString("pendingOwner", pendingOwner);
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Attachments may have been deleted in the viewer; refresh strips still on screen.
        strips.removeIf(strip -> !strip.isAttachedToWindow());
        for (AttachmentStrip strip : strips) {
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
     * Round icon for a subscription, or a badge from {@code title} when the subscription was
     * deleted ({@code entry} null). Decorative: the title is always shown next to it.
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
                TextView add = action("＋\n添加", colors.line, colors.green, () -> pickAttachments(ownerId, this));
                add.setContentDescription("添加截图或发票");
                tiles.addView(add, tileParams());
            }
        }

        private LinearLayout.LayoutParams tileParams() {
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(dp(72), dp(72));
            params.rightMargin = dp(8);
            return params;
        }

        private View tile(Attachment attachment) {
            View tile;
            Bitmap preview = attachment.isPdf() ? null : AttachmentStore.thumbnail(LedgerActivity.this, attachment, dp(72));
            if (preview != null) {
                ImageView image = new ImageView(LedgerActivity.this);
                image.setImageBitmap(preview);
                image.setScaleType(ImageView.ScaleType.CENTER_CROP);
                image.setBackground(shape(colors.line, 12));
                image.setClipToOutline(true);
                tile = image;
            } else {
                TextView text = pill(attachment.isPdf() ? "PDF" : "无法预览", colors.lime, colors.green);
                text.setTextSize(12);
                tile = text;
            }
            tile.setContentDescription(attachment.name);
            tile.setFocusable(true);
            tile.setOnClickListener(view -> startActivity(new Intent(LedgerActivity.this, AttachmentActivity.class)
                    .putExtra(AttachmentActivity.ID, attachment.id)));
            return tile;
        }
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
        new DatePickerDialog(this,
                (view, year, month, day) -> selection.select(LocalDate.of(year, month + 1, day)),
                date.getYear(), date.getMonthValue() - 1, date.getDayOfMonth()).show();
    }

    private ScrollView screenScroll;

    /**
     * Replaces the content with a scrolling page that has a back button and a title, keeping the
     * scroll position across re-renders. Returns the column to fill.
     */
    LinearLayout screen(String title, View headerAction) {
        int scrollY = screenScroll == null ? 0 : screenScroll.getScrollY();
        screenScroll = new ScrollView(this);
        screenScroll.setFillViewport(true);
        screenScroll.setBackgroundColor(colors.paper);
        LinearLayout root = column();
        root.setPadding(dp(20), dp(12), dp(20), dp(28));
        screenScroll.addView(root);
        setContentView(screenScroll);
        LinearLayout header = row();
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.addView(action("‹ 返回", colors.card, colors.green, this::finish),
                new LinearLayout.LayoutParams(dp(76), dp(44)));
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(0, -2, 1f);
        titleParams.leftMargin = dp(12);
        header.addView(label(title, 23, colors.ink, true), titleParams);
        if (headerAction != null) {
            header.addView(headerAction, new LinearLayout.LayoutParams(-2, dp(44)));
        }
        root.addView(header);
        gap(root, 18);
        ScrollView scroll = screenScroll;
        scroll.post(() -> scroll.scrollTo(0, scrollY));
        return root;
    }

    void section(LinearLayout root, String title) {
        gap(root, 22);
        root.addView(label(title, 18, colors.ink, true));
        gap(root, 10);
    }

    static String fieldValue(EditText field) {
        return field.getText().toString().trim();
    }

    void addField(LinearLayout parent, String title, View field) {
        gap(parent, 10);
        if (field.getId() == View.NO_ID) {
            field.setId(View.generateViewId());
        }
        TextView caption = label(title, 13, colors.muted, true);
        caption.setLabelFor(field.getId());
        parent.addView(caption);
        gap(parent, 4);
        parent.addView(field, new LinearLayout.LayoutParams(-1, -2));
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
        field.setThreshold(1);
        field.setOnClickListener(view -> field.showDropDown());
        return field;
    }

    CheckBox check(String text, boolean checked) {
        CheckBox box = new CheckBox(this);
        box.setText(text);
        box.setTextColor(colors.ink);
        box.setTextSize(14);
        box.setChecked(checked);
        box.setButtonTintList(ColorStateList.valueOf(colors.green));
        box.setMinHeight(dp(48));
        return box;
    }

    private void styleInput(EditText field, String hint, String value, int type, int maxLength) {
        field.setTextColor(colors.ink);
        field.setHintTextColor(colors.muted);
        field.setBackground(shape(colors.card, 12));
        field.setMinHeight(dp(48));
        field.setPadding(dp(12), dp(8), dp(12), dp(8));
        field.setTextSize(15);
        field.setInputType(type);
        field.setFilters(new InputFilter[]{new InputFilter.LengthFilter(maxLength)});
        field.setHint(hint);
        field.setText(value);
    }

    Spinner options(String[] items, int selected) {
        Spinner spinner = new Spinner(this, Spinner.MODE_DROPDOWN);
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, items);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinner.setAdapter(adapter);
        spinner.setSelection(selected);
        spinner.setMinimumHeight(dp(48));
        spinner.setPadding(dp(10), 0, dp(8), 0);
        return spinner;
    }

    TextView label(String value, int size, int color, boolean bold) {
        TextView text = new TextView(this);
        text.setText(value);
        text.setTextSize(size);
        text.setTextColor(color);
        if (bold) {
            text.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        }
        return text;
    }

    TextView pill(String value, int background, int foreground) {
        TextView text = label(value, 14, foreground, true);
        text.setBackground(shape(background, 13));
        text.setGravity(Gravity.CENTER);
        return text;
    }

    TextView action(String value, int background, int foreground, Runnable work) {
        android.widget.Button button = new android.widget.Button(this);
        button.setText(value);
        button.setAllCaps(false);
        button.setTextColor(foreground);
        button.setTextSize(14);
        button.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        button.setBackground(ripple(background, 13));
        button.setStateListAnimator(null);
        button.setMinHeight(dp(46));
        button.setMinWidth(0);
        button.setPadding(dp(6), dp(5), dp(6), dp(5));
        if (work != null) {
            button.setOnClickListener(view -> work.run());
        }
        return button;
    }

    LinearLayout panel(int background, int radius) {
        LinearLayout layout = column();
        layout.setBackground(shape(background, radius));
        layout.setPadding(dp(15), dp(13), dp(15), dp(13));
        return layout;
    }

    GradientDrawable shape(int color, int radius) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(radius));
        return drawable;
    }

    RippleDrawable ripple(int color, int radius) {
        return new RippleDrawable(ColorStateList.valueOf(0x263F624D), shape(color, radius), null);
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
