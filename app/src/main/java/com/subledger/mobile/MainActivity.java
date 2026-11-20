package com.subledger.mobile;

import android.Manifest;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.AutoCompleteTextView;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PushbackInputStream;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Currency;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

public final class MainActivity extends LedgerActivity {
    private static final int SAVE_BACKUP = 10;
    private static final int OPEN_BACKUP = 11;
    private static final int NOTIFICATIONS = 12;
    private static final int PICK_ICON = 13;
    private static final int MAX_BACKUP_BYTES = 8 * 1024 * 1024;
    private static final String[] FILTERS = {"进行中", "近 30 天", "已停止"};
    private static final String[] CATEGORIES = {"AI", "影音", "音乐", "云存储", "工具", "游戏", "学习", "办公", "其他"};
    private int filter;
    private String query = "";
    private LinearLayout root, summary, entries, filterButtons;
    private Ledger ledger;
    private Map<String, Ledger.Coverage> coverage = new HashMap<>();
    private LocalDate today;
    private long revision = -1;
    private Map<String, Integer> attachmentCounts = new HashMap<>();

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
                | WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);
        if (state != null) {
            filter = state.getInt("filter");
            query = state.getString("query", "");
        }
        render();
    }

    @Override
    protected void onResume() {
        super.onResume();
        LocalDate now = LocalDate.now();
        if (!now.equals(today) || ledgerStore().recordDue(rateStore().load(), now)
                || ledgerStore().revision() != revision) {
            render();
        } else {
            // Rates may have changed on the statistics screen.
            renderSummary();
        }
        refreshRatesIfStale(ledger, this::renderSummary);
        RenewalReminder.refresh(this);
    }

    @Override
    protected void onSaveInstanceState(Bundle state) {
        super.onSaveInstanceState(state);
        state.putInt("filter", filter);
        state.putString("query", query);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode == NOTIFICATIONS) {
            RenewalReminder.refresh(this);
            toast(RenewalReminder.enabled(this) ? "续费提醒已开启" : "可在系统设置开启通知");
        }
    }

    private void render() {
        today = LocalDate.now();
        ledgerStore().recordDue(rateStore().load(), today);
        ledger = ledgerStore().load();
        revision = ledgerStore().revision();
        // Per subscription: its own files plus the invoices of all its billing periods.
        Map<String, Integer> perOwner = ledger.attachmentCounts();
        attachmentCounts = new HashMap<>();
        for (Subscription entry : ledger.subscriptions) {
            int files = perOwner.getOrDefault(entry.id, 0);
            for (Payment payment : ledger.paymentsOf(entry.id)) {
                files += perOwner.getOrDefault(payment.id, 0);
            }
            if (files > 0) {
                attachmentCounts.put(entry.id, files);
            }
        }
        coverage = new HashMap<>();
        for (Wallet wallet : ledger.wallets) {
            coverage.put(wallet.id, ledger.coverage(wallet, today));
        }

        ScrollView mainScroll = new ScrollView(this);
        mainScroll.setFillViewport(true);
        mainScroll.setBackgroundColor(colors.paper);
        mainScroll.setClipToPadding(false);
        root = column();
        root.setPadding(dp(20), dp(12), dp(20), dp(28));
        root.setFocusableInTouchMode(true);
        mainScroll.addView(root);
        setContentView(mainScroll);

        LinearLayout header = row();
        header.setGravity(Gravity.CENTER_VERTICAL);
        TextView logo = pill("账", colors.lime, colors.green);
        logo.setTextSize(17);
        header.addView(logo, new LinearLayout.LayoutParams(dp(42), dp(42)));
        TextView heading = label("订阅账本", 23, colors.ink, true);
        LinearLayout.LayoutParams headingParams = new LinearLayout.LayoutParams(0, -2, 1f);
        headingParams.leftMargin = dp(10);
        header.addView(heading, headingParams);
        header.addView(action("统计", colors.card, colors.green, () -> open(StatsActivity.class)),
                new LinearLayout.LayoutParams(dp(66), dp(44)));
        LinearLayout.LayoutParams settingsParams = new LinearLayout.LayoutParams(dp(66), dp(44));
        settingsParams.leftMargin = dp(6);
        header.addView(action("设置", colors.card, colors.green, this::settings), settingsParams);
        root.addView(header);
        gap(root, 20);

        summary = column();
        root.addView(summary);
        renderSummary();
        gap(root, 22);

        LinearLayout section = row();
        section.setGravity(Gravity.CENTER_VERTICAL);
        section.addView(label("我的订阅", 22, colors.ink, true), new LinearLayout.LayoutParams(0, -2, 1f));
        section.addView(action("＋ 添加订阅", colors.hero, Color.WHITE, () -> editor(null, false)),
                new LinearLayout.LayoutParams(dp(112), dp(46)));
        root.addView(section);
        gap(root, 12);
        EditText search = input("搜索名称、分类、付款渠道或备注", query, InputType.TYPE_CLASS_TEXT, 120);
        search.setSingleLine(true);
        search.setContentDescription("搜索订阅");
        root.addView(search, new LinearLayout.LayoutParams(-1, -2));
        gap(root, 10);
        filterButtons = row();
        root.addView(filterButtons);
        gap(root, 12);
        entries = column();
        root.addView(entries);
        updateFilters();
        updateEntries();
        search.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence text, int start, int count, int after) { }
            public void onTextChanged(CharSequence text, int start, int before, int count) {
                query = text.toString();
                updateEntries();
            }
            public void afterTextChanged(Editable text) { }
        });
    }

    private void renderSummary() {
        long active = 0;
        long upcoming = 0;
        long trials = 0;
        Map<String, BigDecimal> totals = new TreeMap<>();
        for (Subscription entry : ledger.subscriptions) {
            if (entry.mode == Subscription.Mode.ENDED) {
                continue;
            }
            active++;
            if (entry.trial) {
                trials++;
            }
            if (withinThirtyDays(entry.nextDate(today))) {
                upcoming++;
            }
            totals.merge(entry.currency, entry.myShare(entry.monthlyEstimate()), BigDecimal::add);
        }

        summary.removeAllViews();
        LinearLayout hero = column();
        hero.setBackground(ripple(colors.hero, 24));
        hero.setOnClickListener(view -> open(StatsActivity.class));
        hero.setFocusable(true);
        hero.setPadding(dp(20), dp(18), dp(20), dp(18));
        hero.addView(label("每月预计支出", 15, colors.heroText, true));
        gap(hero, 8);
        if (totals.isEmpty()) {
            hero.addView(label("—", 32, Color.WHITE, true));
        }
        for (Map.Entry<String, BigDecimal> total : totals.entrySet()) {
            hero.addView(label(money(total.getKey(), total.getValue()), 27, Color.WHITE, true));
        }
        String converted = convertedSummary();
        if (converted != null) {
            gap(hero, 6);
            hero.addView(label(converted, 14, Color.WHITE, false));
        }
        gap(hero, 13);
        String footer = active + " 项进行中  ·  " + upcoming + " 项近 30 天续费";
        if (trials > 0) {
            footer += "  ·  " + trials + " 项试用中";
        }
        hero.addView(label(footer + "  ·  查看统计 ›", 13, colors.heroText, false));
        summary.addView(hero, new LinearLayout.LayoutParams(-1, -2));

        if (!ledger.wallets.isEmpty()) {
            gap(summary, 10);
            summary.addView(walletStrip(), new LinearLayout.LayoutParams(-1, -2));
        }
    }

    /** One line per balance account; accounts that cannot pay the next charge are highlighted. */
    private View walletStrip() {
        boolean warning = false;
        LinearLayout strip = column();
        strip.setPadding(dp(15), dp(12), dp(15), dp(12));
        strip.setFocusable(true);
        strip.setOnClickListener(view -> open(WalletsActivity.class));
        for (Wallet wallet : ledger.wallets) {
            Ledger.Coverage cover = coverage.get(wallet.id);
            String line = wallet.name + "  " + money(wallet.currency, wallet.balance);
            if (cover.insufficient()) {
                warning = true;
                line += "  ·  不够 " + cover.next + " 的扣款";
            } else if (cover.paidUntil != null && !cover.beyondHorizon) {
                line += "  ·  可用到 " + cover.paidUntil;
            }
            strip.addView(label(line, 14, cover.insufficient() ? colors.amber : colors.ink, cover.insufficient()));
        }
        strip.addView(label("余额账户 ›", 12, colors.muted, false));
        strip.setBackground(ripple(warning ? colors.lime : colors.card, 18));
        return strip;
    }

    /** One line with the base-currency total, or null when there is nothing to convert. */
    private String convertedSummary() {
        Rates rates = rateStore().load();
        Stats stats = new Stats(ledger, rates, today);
        if (stats.active == 0) {
            return null;
        }
        if (stats.counted == 0) {
            return "缺少 " + String.join("、", stats.missing) + " 汇率，点此在统计页获取";
        }
        String line = Stats.converts(ledger, rates.base)
                ? "折合约 " + money(stats.base, stats.monthly) + " / 月 · "
                        + money(stats.base, stats.yearly) + " / 年"
                : "全年约 " + money(stats.base, stats.yearly);
        if (!stats.missing.isEmpty()) {
            line += "（未含 " + String.join("、", stats.missing) + "）";
        }
        return line;
    }

    @Override
    void onLedgerChanged() {
        render();
    }

    private void open(Class<?> screen) {
        startActivity(new Intent(this, screen));
    }

    private void updateFilters() {
        filterButtons.removeAllViews();
        for (int i = 0; i < FILTERS.length; i++) {
            final int index = i;
            TextView button = action(FILTERS[i], i == filter ? colors.lime : colors.card, colors.green, () -> {
                filter = index;
                updateFilters();
                updateEntries();
            });
            button.setSelected(i == filter);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, dp(44), 1f);
            if (i > 0) {
                params.leftMargin = dp(6);
            }
            filterButtons.addView(button, params);
        }
    }

    private void updateEntries() {
        entries.removeAllViews();
        String needle = query.trim().toLowerCase(Locale.ROOT);
        List<Subscription> visible = new ArrayList<>();
        for (Subscription entry : ledger.subscriptions) {
            boolean ended = entry.mode == Subscription.Mode.ENDED;
            boolean matchesStatus = filter == 2 ? ended : !ended;
            String searchable = (entry.title + " " + entry.category + " " + entry.payment + " " + entry.note)
                    .toLowerCase(Locale.ROOT);
            if (matchesStatus && searchable.contains(needle)
                    && (filter != 1 || withinThirtyDays(entry.nextDate(today)))) {
                visible.add(entry);
            }
        }
        visible.sort(Comparator.comparing((Subscription entry) -> entry.nextDate(today))
                .thenComparing(entry -> entry.title, String.CASE_INSENSITIVE_ORDER));
        for (Subscription entry : visible) {
            entries.addView(subscriptionCard(entry), new LinearLayout.LayoutParams(-1, -2));
            gap(entries, 9);
        }
        if (visible.isEmpty()) {
            LinearLayout empty = panel(colors.card, 18);
            if (ledger.subscriptions.isEmpty()) {
                empty.addView(label("添加第一份订阅", 18, colors.ink, true));
                gap(empty, 5);
                empty.addView(label("记录下一次续费日期、扣款金额和付款渠道。", 14, colors.muted, false));
                gap(empty, 9);
                empty.addView(action("使用 Claude 模板", colors.line, colors.green, () -> editor(null, true)),
                        new LinearLayout.LayoutParams(-1, dp(46)));
            } else {
                String message = !needle.isEmpty() ? "没有匹配的订阅"
                        : filter == 1 ? "近 30 天没有待续费订阅" : "这个分类暂时没有订阅";
                empty.addView(label(message, 15, colors.muted, false));
            }
            entries.addView(empty);
        }
    }

    private boolean walletShort(Subscription entry) {
        Wallet wallet = ledger.payingWallet(entry);
        return wallet != null && entry.mode != Subscription.Mode.ENDED && coverage.get(wallet.id).insufficient();
    }

    private View subscriptionCard(Subscription entry) {
        LinearLayout card = panel(colors.card, 18);
        card.setBackground(ripple(colors.card, 18));
        card.setOnClickListener(view -> details(entry));
        card.setFocusable(true);
        card.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams iconParams = new LinearLayout.LayoutParams(dp(40), dp(40));
        iconParams.rightMargin = dp(12);
        card.addView(iconView(entry, entry.title, 40), iconParams);
        LinearLayout content = column();
        card.addView(content, new LinearLayout.LayoutParams(0, -2, 1f));
        LinearLayout top = row();
        top.setGravity(Gravity.TOP);
        TextView name = label(entry.title, 17, colors.ink, true);
        LinearLayout.LayoutParams nameParams = new LinearLayout.LayoutParams(0, -2, 1f);
        nameParams.rightMargin = dp(10);
        top.addView(name, nameParams);
        TextView price = label(money(entry.currency, entry.amount), 16, colors.green, true);
        price.setGravity(Gravity.END);
        top.addView(price);
        content.addView(top);
        gap(content, 4);
        List<String> parts = new ArrayList<>();
        parts.add(entry.cycle.label);
        parts.add(entry.mode.label);
        if (!entry.category.isEmpty()) {
            parts.add(entry.category);
        }
        if (entry.hasCost()) {
            parts.add("实际 " + money(entry.costCurrency, entry.cost));
        }
        if (entry.splitWays > 1) {
            parts.add(entry.splitWays + " 人分摊");
        }
        Integer files = attachmentCounts.get(entry.id);
        if (files != null) {
            parts.add(files + " 个附件");
        }
        Wallet wallet = ledger.payingWallet(entry);
        if (wallet != null) {
            parts.add(wallet.name);
        } else if (!entry.payment.isEmpty()) {
            parts.add(entry.payment);
        }
        content.addView(label(String.join(" · ", parts), 13, colors.muted, false));
        if (entry.mode != Subscription.Mode.ENDED) {
            gap(content, 7);
            LocalDate next = entry.nextDate(today);
            long days = ChronoUnit.DAYS.between(today, next);
            String relative = days < 0 ? "已过期" : days == 0 ? "今天" : days + " 天后";
            String line = entry.trial
                    ? "试用至 " + next + "  ·  " + relative + "开始扣费"
                    : next + "  ·  " + (days == 0 ? "今天续费" : relative);
            if (walletShort(entry)) {
                line += "  ·  余额不足";
            }
            boolean urgent = days <= 7 || walletShort(entry);
            content.addView(label(line, 13, urgent ? colors.amber : colors.green, true));
        }
        return card;
    }

    private void details(Subscription entry) {
        StringBuilder text = new StringBuilder(money(entry.currency, entry.amount))
                .append(" · ").append(entry.cycle.label).append(" · ").append(entry.mode.label);
        if (entry.mode != Subscription.Mode.ENDED) {
            LocalDate next = entry.nextDate(today);
            text.append(entry.trial ? "\n试用至 " + next + "，之后开始扣费" : "\n下次续费 " + next);
            text.append("\n").append(reminderLabel(entry.reminderDays, entry.trial));
        }
        if (!entry.category.isEmpty()) {
            text.append("\n分类 ").append(entry.category);
        }
        Ledger.Money real = ledger.realCost(entry);
        if (entry.hasCost()) {
            text.append("\n每期实际成本 ").append(money(real.currency, real.amount));
        } else if (!real.currency.equals(entry.currency)) {
            text.append("\n每期实际成本约 ").append(money(real.currency, real.amount)).append("（按余额账户成本）");
        }
        if (entry.splitWays > 1) {
            text.append("\n").append(entry.splitWays).append(" 人分摊，我承担约 ")
                    .append(money(real.currency, entry.myShare(real.amount))).append(" / 期");
        }
        Wallet wallet = ledger.payingWallet(entry);
        if (wallet != null) {
            text.append("\n从「").append(wallet.name).append("」扣款，余额 ")
                    .append(money(wallet.currency, wallet.balance));
            if (walletShort(entry)) {
                text.append("，不够下次扣款");
            }
        } else if (entry.walletId != null) {
            text.append("\n所选余额账户币种与订阅不同，未自动扣款");
        }
        if (!entry.payment.isEmpty()) {
            text.append("\n付款渠道 ").append(entry.payment);
        }
        if (!entry.prices.isEmpty()) {
            text.append("\n\n价格变动");
            for (Subscription.PricePoint price : entry.prices) {
                text.append("\n").append(price.until).append(" 前 ").append(money(price.currency, price.amount));
            }
            text.append("\n现在 ").append(money(entry.currency, entry.amount));
        }
        if (!entry.note.isEmpty()) {
            text.append("\n\n").append(entry.note);
        }
        LinearLayout body = column();
        body.setPadding(dp(24), dp(8), dp(24), dp(4));
        body.addView(label(text.toString(), 15, colors.ink, false));
        gap(body, 14);
        gap(body, 4);
        body.addView(label("每期账单与发票", 16, colors.ink, true));
        body.addView(label("点 ＋ 上传这一期的发票；点按缩略图查看，长按可删除或移到其他账期。", 12, colors.muted, false));
        gap(body, 8);
        LinearLayout periods = column();
        body.addView(periods);
        renderPeriods(periods, entry.id);
        gap(body, 14);
        body.addView(label("订阅资料", 16, colors.ink, true));
        body.addView(label("不属于某一期的截图，例如套餐页面。", 12, colors.muted, false));
        gap(body, 6);
        body.addView(new AttachmentStrip(entry.id), new LinearLayout.LayoutParams(-1, -2));
        ScrollView scroll = new ScrollView(this);
        scroll.addView(body);
        AlertDialog.Builder dialog = new AlertDialog.Builder(this).setTitle(entry.title)
                .setIcon(IconDrawable.of(this, entry, entry.title, dp(32), colors.line))
                .setView(scroll)
                .setPositiveButton("编辑", (whichDialog, button) -> editor(entry, false))
                .setNegativeButton("关闭", null);
        if (entry.mode == Subscription.Mode.MANUAL) {
            dialog.setNeutralButton("记录已续费", (whichDialog, button) -> confirmManualRenewal(entry));
        } else {
            dialog.setNeutralButton("扣款记录", (whichDialog, button) -> startActivity(
                    new Intent(this, HistoryActivity.class).putExtra(HistoryActivity.SUBSCRIPTION, entry.id)));
        }
        dialog.show();
    }

    private static final int PERIODS_SHOWN = 6;

    /**
     * The latest billing periods of a subscription, each with its own invoices, newest first.
     * When the running period has no payment yet, offers to record it so its invoice has a home.
     */
    private void renderPeriods(LinearLayout periods, String subscriptionId) {
        periods.removeAllViews();
        Ledger current = ledgerStore().load();
        Subscription entry = current.subscription(subscriptionId);
        if (entry == null) {
            return;
        }
        LocalDate now = LocalDate.now();
        List<Payment> history = current.paymentsOf(entry.id);
        LocalDate due = entry.lastDue(now);
        boolean missing = due != null && (history.isEmpty() || history.get(0).date.isBefore(due));
        if (missing) {
            LinearLayout box = panel(colors.lime, 14);
            box.addView(label(due + " 这一期还没有扣款记录", 14, colors.ink, true));
            gap(box, 2);
            box.addView(label("补记后即可上传这一期的发票。补记不会改动余额账户。", 12, colors.muted, false));
            gap(box, 8);
            box.addView(action("补记 " + money(entry.currency, entry.amount), colors.card, colors.green,
                    () -> backfillPeriod(entry.id, due, periods)), new LinearLayout.LayoutParams(-1, dp(44)));
            periods.addView(box);
            gap(periods, 10);
        }
        for (int i = 0; i < Math.min(PERIODS_SHOWN, history.size()); i++) {
            Payment payment = history.get(i);
            periods.addView(label(payment.date + " · " + money(payment.currency, payment.amount)
                    + (payment.auto ? "" : " · 手动记录"), 14, colors.ink, true));
            gap(periods, 4);
            periods.addView(new AttachmentStrip(payment.id), new LinearLayout.LayoutParams(-1, -2));
            gap(periods, 10);
        }
        if (history.size() > PERIODS_SHOWN) {
            periods.addView(action("查看全部 " + history.size() + " 期", colors.line, colors.green,
                    () -> startActivity(new Intent(this, HistoryActivity.class)
                            .putExtra(HistoryActivity.SUBSCRIPTION, entry.id))),
                    new LinearLayout.LayoutParams(-1, dp(44)));
        } else if (history.isEmpty() && !missing) {
            periods.addView(label("还没有扣款记录。续费后会出现在这里，届时可上传那一期的发票。",
                    13, colors.muted, false));
        }
    }

    private void backfillPeriod(String subscriptionId, LocalDate due, LinearLayout periods) {
        if (commit(() -> {
            Ledger current = ledgerStore().load();
            Subscription entry = current.subscription(subscriptionId);
            if (entry == null) {
                throw new IllegalStateException("订阅已删除");
            }
            Ledger.Money cost = current.costForManualPayment(entry, rateStore().load());
            return current.addPayment(new Payment(Payment.idFor(entry.id, due), entry.id, entry.title, due,
                    entry.amount, entry.currency, cost.amount, cost.currency, entry.splitWays, null, false));
        })) {
            renderPeriods(periods, subscriptionId);
            render();
        }
    }

    private void confirmManualRenewal(Subscription entry) {
        Wallet wallet = ledger.payingWallet(entry);
        String message = "将记录一笔 " + money(entry.currency, entry.amount) + " 的扣款"
                + (wallet == null ? "" : "，并从「" + wallet.name + "」扣除")
                + "。\n下次续费日将更新为 " + entry.occurrence(1) + "。";
        new AlertDialog.Builder(this).setTitle("记录已续费").setMessage(message)
                .setNegativeButton("取消", null)
                .setPositiveButton("确定", (confirm, choice) -> {
                    RenewalReminder.clearReceipt(this, entry.id);
                    if (commit(() -> ledgerStore().load()
                            .recordManualRenewal(entry.id, rateStore().load(), LocalDate.now()))) {
                        render();
                    }
                }).show();
    }

    private void editor(Subscription entry, boolean claudeTemplate) {
        LinearLayout fields = column();
        fields.setPadding(dp(22), dp(8), dp(22), dp(14));
        ScrollView scroll = new ScrollView(this);
        scroll.addView(fields);

        EditText name = input("例如 Claude Pro", entry == null
                ? claudeTemplate ? "Claude Pro" : "" : entry.title,
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES, 100);
        addField(fields, "订阅名称", name);
        IconChoice icon = new IconChoice(entry);
        ImageView iconPreview = new ImageView(this);
        iconPreview.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        Runnable refreshIcon = () -> iconPreview.setImageDrawable(icon.drawable(fieldValue(name), 48));
        LinearLayout iconRow = row();
        iconRow.setGravity(Gravity.CENTER_VERTICAL);
        iconRow.addView(iconPreview, new LinearLayout.LayoutParams(dp(48), dp(48)));
        LinearLayout.LayoutParams changeParams = new LinearLayout.LayoutParams(0, dp(46), 1f);
        changeParams.leftMargin = dp(12);
        iconRow.addView(action("更换图标", colors.line, colors.green,
                () -> iconDialog(icon, fieldValue(name), refreshIcon)), changeParams);
        addField(fields, "图标", iconRow);
        refreshIcon.run();
        name.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence text, int start, int count, int after) { }
            public void onTextChanged(CharSequence text, int start, int before, int count) {
                refreshIcon.run();
            }
            public void afterTextChanged(Editable text) { }
        });
        EditText amount = input("填写账单金额", entry == null ? "" : entry.amount.toPlainString(),
                InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL, 20);
        addField(fields, "单次金额", amount);
        EditText currency = input("三字母币种代码，例如 CNY、USD、JPY",
                entry == null ? claudeTemplate ? "USD" : "CNY" : entry.currency,
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS, 3);
        addField(fields, "币种", currency);
        Spinner cycle = options(labels(Subscription.Cycle.values()),
                entry == null ? Subscription.Cycle.MONTHLY.ordinal() : entry.cycle.ordinal());
        addField(fields, "账单周期", cycle);
        Spinner mode = options(labels(Subscription.Mode.values()),
                entry == null ? Subscription.Mode.AUTO.ordinal() : entry.mode.ordinal());
        addField(fields, "续费状态", mode);
        LocalDate[] selectedDate = {entry == null ? templateDate(claudeTemplate) : entry.anchor};
        TextView date = action(selectedDate[0].toString(), colors.paper, colors.green, null);
        date.setOnClickListener(view -> chooseDate(selectedDate[0], selected -> {
            selectedDate[0] = selected;
            date.setText(selected.toString());
        }));
        addField(fields, "账单续费日期", date);
        fields.addView(label("自动续费按此日期推算后续账期。", 12, colors.muted, false));
        CheckBox trial = check("免费试用中：上面的日期填试用结束、开始扣费的日子", entry != null && entry.trial);
        fields.addView(trial, new LinearLayout.LayoutParams(-1, -2));

        EditText days = input("0–90，留空关闭提醒", entry == null ? "3"
                : entry.reminderDays < 0 ? "" : Integer.toString(entry.reminderDays),
                InputType.TYPE_CLASS_NUMBER, 2);
        addField(fields, "提前提醒天数", days);

        Set<String> categoryOptions = new LinkedHashSet<>();
        for (Subscription existing : ledger.subscriptions) {
            if (!existing.category.isEmpty()) {
                categoryOptions.add(existing.category);
            }
        }
        java.util.Collections.addAll(categoryOptions, CATEGORIES);
        AutoCompleteTextView category = suggestInput("例如 AI、影音、云存储",
                entry == null ? claudeTemplate ? "AI" : "" : entry.category,
                new ArrayList<>(categoryOptions), 30);
        addField(fields, "分类（选填）", category);

        EditText payment = input("例如美区 Apple ID 礼品卡余额", entry == null
                ? claudeTemplate ? "美区 Apple ID 礼品卡余额" : "" : entry.payment,
                InputType.TYPE_CLASS_TEXT, 120);
        addField(fields, "付款渠道（选填）", payment);

        String[] walletLabels = new String[ledger.wallets.size() + 1];
        walletLabels[0] = "不从余额账户扣款";
        int walletIndex = 0;
        for (int i = 0; i < ledger.wallets.size(); i++) {
            Wallet wallet = ledger.wallets.get(i);
            walletLabels[i + 1] = wallet.name + "（" + money(wallet.currency, wallet.balance) + "）";
            if (entry != null && wallet.id.equals(entry.walletId)) {
                walletIndex = i + 1;
            }
        }
        Spinner walletChoice = options(walletLabels, walletIndex);
        if (!ledger.wallets.isEmpty()) {
            addField(fields, "从余额账户扣款（选填）", walletChoice);
            fields.addView(label("续费时自动从该账户扣除，账户币种需与订阅一致。", 12, colors.muted, false));
        }

        LinearLayout costRow = row();
        EditText costAmount = input("留空则自动计算", entry == null || !entry.hasCost()
                ? "" : entry.cost.toPlainString(),
                InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL, 20);
        costAmount.setContentDescription("每期实际成本金额");
        costRow.addView(costAmount, new LinearLayout.LayoutParams(0, -2, 1f));
        EditText costCode = input("币种", entry != null && entry.hasCost()
                ? entry.costCurrency : rateStore().load().base,
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS, 3);
        costCode.setContentDescription("每期实际成本币种");
        LinearLayout.LayoutParams costCodeParams = new LinearLayout.LayoutParams(dp(84), -2);
        costCodeParams.leftMargin = dp(8);
        costRow.addView(costCode, costCodeParams);
        addField(fields, "每期实际成本（选填）", costRow);
        fields.addView(label("每期真实花了多少钱。留空时，从余额账户扣款的按账户成本计算，其余按当前汇率。",
                12, colors.muted, false));

        EditText split = input("1 表示不分摊", entry == null ? "1" : Integer.toString(entry.splitWays),
                InputType.TYPE_CLASS_NUMBER, 2);
        addField(fields, "分摊人数（含自己）", split);
        fields.addView(label("统计只计入你承担的部分。", 12, colors.muted, false));

        EditText note = input("例如扣款账号的备注", entry == null ? "" : entry.note,
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE, 500);
        addField(fields, "备注（选填）", note);

        AlertDialog.Builder builder = new AlertDialog.Builder(this)
                .setTitle(entry == null ? "添加订阅" : "编辑订阅")
                .setView(scroll).setNegativeButton("取消", null)
                .setPositiveButton("保存", null);
        if (entry != null) {
            builder.setNeutralButton("删除", (dialog, button) -> confirmDelete(entry));
        }
        AlertDialog dialog = builder.create();
        // A picture picked in this editor but not saved is deleted.
        dialog.setOnDismissListener(closed -> icon.discardPicked());
        dialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
                | WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);
        dialog.show();
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
            String title = fieldValue(name);
            if (title.isEmpty()) {
                name.setError("请填写订阅名称");
                return;
            }
            BigDecimal value = parseAmount(fieldValue(amount));
            if (value == null) {
                amount.setError("请输入有效金额，例如 20 或 19.99");
                return;
            }
            String code = fieldValue(currency).toUpperCase(Locale.ROOT);
            if (!validCurrency(code)) {
                currency.setError("请输入有效币种，例如 CNY、USD 或 JPY");
                return;
            }
            BigDecimal costValue = null;
            String costCurrency = fieldValue(costCode).toUpperCase(Locale.ROOT);
            if (!fieldValue(costAmount).isEmpty()) {
                costValue = parseAmount(fieldValue(costAmount));
                if (costValue == null) {
                    costAmount.setError("请输入有效金额，或留空");
                    return;
                }
                if (!validCurrency(costCurrency)) {
                    costCode.setError("请输入有效币种，例如 CNY");
                    return;
                }
            }
            int ways;
            try {
                ways = Integer.parseInt(fieldValue(split));
            } catch (NumberFormatException ex) {
                ways = 0;
            }
            if (ways < 1 || ways > Subscription.MAX_SPLIT) {
                split.setError("请输入 1–99");
                return;
            }
            Wallet wallet = walletChoice.getSelectedItemPosition() == 0 ? null
                    : ledger.wallets.get(walletChoice.getSelectedItemPosition() - 1);
            if (wallet != null && !wallet.currency.equals(code)) {
                toast("「" + wallet.name + "」是 " + wallet.currency + " 账户，与订阅币种 " + code + " 不一致");
                return;
            }
            Subscription.Builder b = entry == null ? new Subscription.Builder() : entry.toBuilder();
            b.title = title;
            b.amount = value;
            b.currency = code;
            b.cycle = Subscription.Cycle.values()[cycle.getSelectedItemPosition()];
            b.mode = Subscription.Mode.values()[mode.getSelectedItemPosition()];
            b.anchor = selectedDate[0];
            if (entry != null && entry.mode == Subscription.Mode.AUTO
                    && b.mode == Subscription.Mode.MANUAL && b.anchor.equals(entry.anchor)) {
                b.anchor = entry.nextDate(LocalDate.now());
            }
            b.trial = trial.isChecked();
            String reminder = fieldValue(days);
            try {
                b.reminderDays = reminder.isEmpty() ? -1 : Integer.parseInt(reminder);
            } catch (NumberFormatException ex) {
                b.reminderDays = 99;
            }
            b.category = fieldValue(category);
            b.payment = fieldValue(payment);
            b.walletId = wallet == null ? null : wallet.id;
            b.cost = costValue;
            b.costCurrency = costValue == null ? null : costCurrency;
            b.splitWays = ways;
            b.note = fieldValue(note);
            b.iconText = icon.text;
            b.iconColor = icon.color;
            b.iconImage = icon.image;
            if (entry != null && (entry.amount.compareTo(value) != 0 || !entry.currency.equals(code))) {
                b.prices.add(new Subscription.PricePoint(LocalDate.now(), entry.amount, entry.currency));
                while (b.prices.size() > Subscription.MAX_PRICES) {
                    b.prices.remove(0);
                }
            }
            Subscription updated;
            try {
                updated = b.build();
            } catch (IllegalArgumentException ex) {
                toast("请检查金额和实际成本的小数位、日期和提醒天数（0–90）");
                return;
            }
            if (icon.image != null && icon.image.equals(icon.picked)) {
                if (!AttachmentStore.moveIcon(this, icon.image)) {
                    toast("无法保存图标图片，请重新选择");
                    return;
                }
                icon.picked = null;
            }
            boolean first = ledger.subscriptions.isEmpty();
            boolean resetReminder = entry != null && (entry.reminderDays != updated.reminderDays
                    || entry.mode != updated.mode || entry.cycle != updated.cycle
                    || entry.trial != updated.trial || !entry.anchor.equals(updated.anchor));
            if (save(updated, resetReminder)) {
                dialog.dismiss();
                if (first && updated.mode != Subscription.Mode.ENDED
                        && updated.reminderDays >= 0 && Build.VERSION.SDK_INT >= 33
                        && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                        != PackageManager.PERMISSION_GRANTED) {
                    requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, NOTIFICATIONS);
                }
            }
        });
    }

    /** Icon settings being edited in the subscription editor. */
    private final class IconChoice {
        String text;
        Integer color;
        String image;
        /** Picture picked in this editor and still in the incoming folder. */
        String picked;

        IconChoice(Subscription entry) {
            if (entry != null) {
                text = entry.iconText;
                color = entry.iconColor;
                image = entry.iconImage;
            }
        }

        IconDrawable drawable(String title, int size) {
            return new IconDrawable(Icons.badge(title.isEmpty() ? "?" : title, text, color),
                    AttachmentStore.icon(MainActivity.this, image), dp(size), colors.line, dp(1));
        }

        void setImage(String id) {
            discardPicked();
            picked = id;
            image = id;
        }

        void removeImage() {
            discardPicked();
            image = null;
        }

        void discardPicked() {
            if (picked != null) {
                if (picked.equals(image)) {
                    image = null;
                }
                AttachmentStore.discardIcon(MainActivity.this, picked);
                picked = null;
            }
        }
    }

    /** Edits {@code icon} in place; {@code changed} refreshes the editor's preview. */
    private void iconDialog(IconChoice icon, String title, Runnable changed) {
        LinearLayout body = column();
        body.setPadding(dp(22), dp(8), dp(22), dp(8));
        ImageView preview = new ImageView(this);
        preview.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        LinearLayout.LayoutParams previewParams = new LinearLayout.LayoutParams(dp(72), dp(72));
        previewParams.gravity = Gravity.CENTER_HORIZONTAL;
        body.addView(preview, previewParams);
        LinearLayout pictureRow = row();
        LinearLayout swatches = column();
        Icons.Preset preset = Icons.preset(title);
        String autoText = preset != null ? preset.text : Icons.initial(title.isEmpty() ? "?" : title);
        EditText text = input("留空使用「" + autoText + "」", icon.text == null ? "" : icon.text,
                InputType.TYPE_CLASS_TEXT, Icons.MAX_TEXT);
        EditText hex = input("例如 #D97757", icon.color == null ? "" : Icons.formatColor(icon.color),
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS, 7);

        Runnable[] update = new Runnable[1];
        update[0] = () -> {
            preview.setImageDrawable(icon.drawable(title, 72));
            pictureRow.removeAllViews();
            pictureRow.addView(action(icon.image == null ? "从相册选图" : "换一张图片", colors.line, colors.green,
                    () -> pickIcon(icon, update[0])), new LinearLayout.LayoutParams(0, dp(46), 1f));
            if (icon.image != null) {
                LinearLayout.LayoutParams removeParams = new LinearLayout.LayoutParams(0, dp(46), 1f);
                removeParams.leftMargin = dp(8);
                pictureRow.addView(action("移除图片", colors.line, colors.amber, () -> {
                    icon.removeImage();
                    update[0].run();
                }), removeParams);
            }
            renderSwatches(swatches, icon, color -> {
                icon.color = color;
                hex.setText(color == null ? "" : Icons.formatColor(color));
                update[0].run();
            });
            changed.run();
        };

        gap(body, 12);
        body.addView(pictureRow);
        body.addView(label("设置图片后显示图片，移除后显示下面的文字图标。", 12, colors.muted, false));
        addField(body, "图标文字（最多 " + Icons.MAX_TEXT + " 个字符）", text);
        gap(body, 10);
        body.addView(label("颜色", 13, colors.muted, true));
        gap(body, 6);
        body.addView(swatches);
        addField(body, "自定义颜色（选填）", hex);
        text.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence value, int start, int count, int after) { }
            public void onTextChanged(CharSequence value, int start, int before, int count) {
                icon.text = Icons.validText(value.toString());
                update[0].run();
            }
            public void afterTextChanged(Editable value) { }
        });
        hex.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence value, int start, int count, int after) { }
            public void onTextChanged(CharSequence value, int start, int before, int count) {
                Integer color = Icons.parseColor(value.toString());
                if (color != null && !color.equals(icon.color)) {
                    icon.color = color;
                    update[0].run();
                }
            }
            public void afterTextChanged(Editable value) { }
        });
        update[0].run();

        ScrollView scroll = new ScrollView(this);
        scroll.addView(body);
        new AlertDialog.Builder(this).setTitle("图标").setView(scroll)
                .setPositiveButton("完成", null)
                .setNeutralButton("恢复默认", (dialog, button) -> {
                    icon.text = null;
                    icon.color = null;
                    icon.removeImage();
                    changed.run();
                }).show();
    }

    private interface ColorSelection {
        void select(Integer color);
    }

    /** Colour circles in rows of six, starting with "自动"; the selected one is outlined. */
    private void renderSwatches(LinearLayout grid, IconChoice icon, ColorSelection selection) {
        grid.removeAllViews();
        int perRow = 6;
        LinearLayout line = null;
        int total = Icons.SWATCHES.length + 1;
        for (int i = -1; i < Icons.SWATCHES.length; i++) {
            if ((i + 1) % perRow == 0) {
                line = row();
                grid.addView(line, new LinearLayout.LayoutParams(-1, dp(44)));
            }
            Integer color = i < 0 ? null : Integer.valueOf(Icons.SWATCHES[i]);
            boolean selected = color == null ? icon.color == null : color.equals(icon.color);
            GradientDrawable dot = new GradientDrawable();
            dot.setShape(GradientDrawable.OVAL);
            dot.setColor(color == null ? colors.card : 0xFF000000 | color);
            dot.setStroke(selected ? dp(3) : dp(1), selected ? colors.green : colors.line);
            TextView swatch = new TextView(this);
            swatch.setBackground(dot);
            swatch.setGravity(Gravity.CENTER);
            if (color == null) {
                swatch.setText("自动");
                swatch.setTextSize(11);
                swatch.setTextColor(colors.ink);
            }
            swatch.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            // Equal cells keep six circles per row on narrow dialogs; the whole cell is the tap target.
            LinearLayout cell = row();
            cell.setGravity(Gravity.CENTER);
            cell.setContentDescription((color == null ? "自动颜色" : Icons.formatColor(color))
                    + (selected ? "，已选择" : ""));
            cell.setOnClickListener(view -> selection.select(color));
            cell.addView(swatch, new LinearLayout.LayoutParams(dp(36), dp(36)));
            line.addView(cell, new LinearLayout.LayoutParams(0, -1, 1f));
        }
        for (int i = total % perRow; i > 0 && i < perRow; i++) {
            line.addView(new View(this), new LinearLayout.LayoutParams(0, -1, 1f));
        }
    }

    /** Icon whose picture is being picked, and what to refresh afterwards; null when idle. */
    private IconChoice pickingIcon;
    private Runnable pickedIcon;

    private void pickIcon(IconChoice icon, Runnable after) {
        pickingIcon = icon;
        pickedIcon = after;
        startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE).setType("image/*"), PICK_ICON);
    }

    private void finishPickingIcon(android.net.Uri uri) {
        IconChoice icon = pickingIcon;
        Runnable after = pickedIcon;
        pickingIcon = null;
        pickedIcon = null;
        if (icon == null) {
            // The editor was closed (e.g. the activity was recreated) while picking.
            toast("请重新打开编辑并选择图片");
            return;
        }
        new Thread(() -> {
            String id;
            try {
                id = AttachmentStore.copyIcon(this, uri);
            } catch (IOException ex) {
                id = null;
            }
            String picked = id;
            runOnUiThread(() -> {
                if (picked == null) {
                    toast("无法读取这张图片");
                } else if (isFinishing() || isDestroyed()) {
                    AttachmentStore.discardIcon(this, picked);
                } else {
                    icon.setImage(picked);
                    after.run();
                }
            });
        }, "icon-import").start();
    }

    private static boolean validCurrency(String code) {
        try {
            Currency.getInstance(code);
            return true;
        } catch (IllegalArgumentException ex) {
            return false;
        }
    }

    private void confirmDelete(Subscription entry) {
        new AlertDialog.Builder(this).setTitle("删除订阅")
                .setMessage("确定删除“" + entry.title + "”？订阅的附件会一并删除，已有的扣款记录会保留。")
                .setNegativeButton("取消", null)
                .setPositiveButton("删除", (dialog, button) -> {
                    RenewalReminder.clearReceipt(this, entry.id);
                    if (commit(() -> ledgerStore().load().removeSubscription(entry.id))) {
                        render();
                    }
                }).show();
    }

    private boolean save(Subscription entry, boolean resetReminder) {
        if (resetReminder) {
            RenewalReminder.clearReceipt(this, entry.id);
        }
        if (!commit(() -> ledgerStore().load().upsert(entry, LocalDate.now()))) {
            return false;
        }
        filter = entry.mode == Subscription.Mode.ENDED ? 2 : 0;
        query = "";
        render();
        return true;
    }

    private void settings() {
        String notificationAction = RenewalReminder.enabled(this) ? "管理系统通知" : "开启续费提醒";
        String[] actions = {notificationAction, "余额账户", "扣款记录", "支出统计与汇率",
                "按月导出账单与发票", "导出备份（含附件）", "从备份合并", "使用 Claude 模板"};
        Runnable[] handlers = {
                () -> startActivity(new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                        .putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName())),
                () -> open(WalletsActivity.class),
                () -> open(HistoryActivity.class),
                () -> open(StatsActivity.class),
                this::chooseMonthToExport,
                () -> startActivityForResult(new Intent(Intent.ACTION_CREATE_DOCUMENT)
                        .addCategory(Intent.CATEGORY_OPENABLE).setType("application/zip")
                        .putExtra(Intent.EXTRA_TITLE, "subscriptions-" + LocalDate.now() + ".zip"), SAVE_BACKUP),
                () -> startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT)
                        .addCategory(Intent.CATEGORY_OPENABLE).setType("*/*"), OPEN_BACKUP),
                () -> editor(null, true),
        };
        new AlertDialog.Builder(this).setTitle("设置")
                .setItems(actions, (dialog, selected) -> handlers[selected].run())
                .setNegativeButton("关闭", null).show();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null || data.getData() == null) {
            if (requestCode == PICK_ICON) {
                pickingIcon = null;
                pickedIcon = null;
            }
            return;
        }
        android.net.Uri uri = data.getData();
        if (requestCode == PICK_ICON) {
            finishPickingIcon(uri);
        } else if (requestCode == SAVE_BACKUP) {
            Ledger snapshot = ledgerStore().load();
            File folder = AttachmentStore.folder(this);
            new Thread(() -> {
                try (OutputStream stream = getContentResolver().openOutputStream(uri, "wt")) {
                    if (stream == null) {
                        throw new IOException("无法打开备份文件");
                    }
                    BackupZip.write(stream, LedgerStore.encode(snapshot), folder, snapshot.fileNames());
                    runOnUiThread(() -> toast("备份已导出（含 " + snapshot.attachments.size() + " 个附件）"));
                } catch (Exception ex) {
                    runOnUiThread(() -> toast("导出失败，请检查文件位置和可用空间"));
                }
            }, "backup-export").start();
        } else if (requestCode == OPEN_BACKUP) {
            File staging = new File(getCacheDir(), "import-" + java.util.UUID.randomUUID());
            File folder = AttachmentStore.folder(this);
            new Thread(() -> {
                try (PushbackInputStream stream = new PushbackInputStream(
                        openOrThrow(uri), 4)) {
                    byte[] head = new byte[4];
                    int read = stream.read(head);
                    if (read > 0) {
                        stream.unread(head, 0, read);
                    }
                    String json = BackupZip.isZip(head) ? BackupZip.read(stream, staging) : readJson(stream);
                    // Keep attachments and icon pictures only when their file came with the backup or is already here.
                    Ledger imported = LedgerStore.decode(json).keepFiles(name ->
                            new File(staging, name).isFile() || new File(folder, name).isFile());
                    runOnUiThread(() -> confirmImport(imported, staging));
                } catch (Exception ex) {
                    AttachmentStore.deleteTree(staging);
                    runOnUiThread(() -> toast("导入失败，请选择有效的订阅账本备份（.zip 或 .json）"));
                }
            }, "backup-import").start();
        }
    }

    private InputStream openOrThrow(android.net.Uri uri) throws IOException {
        InputStream stream = getContentResolver().openInputStream(uri);
        if (stream == null) {
            throw new IOException("无法打开备份文件");
        }
        return stream;
    }

    private static String readJson(InputStream stream) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int count;
        while ((count = stream.read(buffer)) != -1) {
            if (bytes.size() + count > MAX_BACKUP_BYTES) {
                throw new IOException("备份文件过大");
            }
            bytes.write(buffer, 0, count);
        }
        return bytes.toString("UTF-8");
    }

    private void confirmImport(Ledger imported, File staging) {
        if (isFinishing() || isDestroyed()) {
            AttachmentStore.deleteTree(staging);
            return;
        }
        String counts = imported.subscriptions.size() + " 条订阅";
        if (!imported.wallets.isEmpty()) {
            counts += "、" + imported.wallets.size() + " 个余额账户";
        }
        if (!imported.payments.isEmpty()) {
            counts += "、" + imported.payments.size() + " 条扣款记录";
        }
        if (!imported.attachments.isEmpty()) {
            counts += "、" + imported.attachments.size() + " 个附件";
        }
        new AlertDialog.Builder(this).setTitle("合并备份")
                .setMessage("备份中有 " + counts + "。同一条记录将以备份中的内容为准。")
                .setNegativeButton("取消", null)
                .setPositiveButton("合并", (dialog, button) -> {
                    AttachmentStore.moveFrom(this, staging);
                    if (commit(() -> ledgerStore().load().merge(imported))) {
                        render();
                        toast("备份已合并");
                    }
                })
                .setOnDismissListener(dialog -> AttachmentStore.deleteTree(staging))
                .show();
    }

    private LocalDate templateDate(boolean claudeTemplate) {
        LocalDate knownRenewal = LocalDate.of(2026, 12, 12);
        return claudeTemplate && !knownRenewal.isBefore(today) ? knownRenewal : today;
    }

    private boolean withinThirtyDays(LocalDate date) {
        return !date.isBefore(today) && !date.isAfter(today.plusDays(30));
    }

    private static String reminderLabel(int days, boolean trial) {
        String event = trial ? "试用结束" : "续费";
        return days < 0 ? "提醒已关闭" : days == 0 ? event + "当天提醒" : event + "前 " + days + " 天提醒";
    }

    private static String[] labels(Subscription.Cycle[] cycles) {
        String[] names = new String[cycles.length];
        for (int i = 0; i < cycles.length; i++) {
            names[i] = cycles[i].label;
        }
        return names;
    }

    private static String[] labels(Subscription.Mode[] modes) {
        String[] names = new String[modes.length];
        for (int i = 0; i < modes.length; i++) {
            names[i] = modes[i].label;
        }
        return names;
    }
}
