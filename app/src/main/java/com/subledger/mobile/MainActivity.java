package com.subledger.mobile;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.drawable.ClipDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.InsetDrawable;
import android.graphics.drawable.LayerDrawable;
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
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
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
import java.time.format.DateTimeFormatter;
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
import java.util.function.Supplier;

public final class MainActivity extends LedgerActivity {
    private static final int SAVE_BACKUP = 10;
    private static final int OPEN_BACKUP = 11;
    private static final int NOTIFICATIONS = 12;
    private static final int PICK_ICON = 13;
    private static final int MAX_BACKUP_BYTES = 8 * 1024 * 1024;
    private static final String[] FILTERS = {"进行中", "近 30 天", "已停止"};
    private static final String[] CATEGORIES = {"AI", "影音", "音乐", "云存储", "工具", "游戏", "学习", "办公", "其他"};
    private static final DateTimeFormatter TODAY = DateTimeFormatter.ofPattern("M月d日 EEEE", Locale.CHINA);
    /** Width of the overview column on expanded windows, dp. */
    private static final int SIDE_DP = 380;
    private int filter;
    private String query = "";
    private Page page;
    private LinearLayout summary, entries;
    private TextView listCount;
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
        checkForUpdate(false);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus && pendingUpdate != null) {
            Updater.Release release = pendingUpdate;
            pendingUpdate = null;
            showUpdate(release);
        }
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

    /** Two panes side by side on expanded (desktop-size) windows. */
    private boolean twoPane() {
        return expanded();
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

        int scrollY = page == null ? 0 : page.scroll.getScrollY();
        boolean twoPane = twoPane();
        page = new Page("订阅账本", false, !twoPane, twoPane ? 1200 : 840, twoPane ? SIDE_DP : 0);
        page.action("统计", false, () -> open(StatsActivity.class));
        page.action("设置", false, this::settings);
        page.fab("添加订阅", () -> editor(null, false));
        LinearLayout list = page.content;
        list.setFocusableInTouchMode(true);
        LinearLayout overview = twoPane ? page.side : list;
        TextView date = label(TODAY.format(today), 13, colors.muted, true);
        if (twoPane) {
            overview.addView(date);
            gap(overview, 12);
        } else {
            // Above the large title, like iOS.
            list.addView(date, 0);
        }

        summary = column();
        overview.addView(summary);
        renderSummary();
        if (twoPane) {
            gap(overview, 16);
            overview.addView(shortcuts());
        } else {
            gap(list, 8);
        }

        LinearLayout section = row();
        section.setGravity(Gravity.BOTTOM);
        section.addView(heading(label("我的订阅", 22, colors.ink, true)), new LinearLayout.LayoutParams(-2, -2));
        listCount = label("", 14, colors.muted, false);
        LinearLayout.LayoutParams countParams = new LinearLayout.LayoutParams(0, -2, 1f);
        countParams.setMarginStart(dp(8));
        countParams.bottomMargin = dp(2);
        section.addView(listCount, countParams);
        if (!twoPane) {
            gap(list, 18);
        }
        list.addView(section);
        gap(list, 12);
        EditText search = input("搜索名称、分类、付款渠道或备注", query, InputType.TYPE_CLASS_TEXT, 120);
        search.setSingleLine(true);
        search.setContentDescription("搜索订阅");
        list.addView(search, new LinearLayout.LayoutParams(-1, -2));
        gap(list, 10);
        list.addView(segmented(FILTERS, filter, index -> {
            filter = index;
            updateEntries();
            entries.setAlpha(0.35f);
            entries.animate().alpha(1f).setDuration(200).setInterpolator(Motion.EMPHASIZED).start();
        }), new LinearLayout.LayoutParams(-1, dp(42)));
        gap(list, 14);
        entries = column();
        list.addView(entries);
        updateEntries();
        search.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence text, int start, int count, int after) { }
            public void onTextChanged(CharSequence text, int start, int before, int count) {
                query = text.toString();
                updateEntries();
            }
            public void afterTextChanged(Editable text) { }
        });
        page.show(scrollY);
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
        tappable(hero, colors.hero, 28, () -> open(StatsActivity.class));
        hero.setPadding(dp(22), dp(20), dp(22), dp(20));
        LinearLayout top = row();
        top.addView(label("每月预计支出", 14, colors.heroText, true), new LinearLayout.LayoutParams(0, -2, 1f));
        top.addView(label("统计 ›", 13, colors.heroText, false));
        hero.addView(top);
        gap(hero, 8);
        if (totals.isEmpty()) {
            hero.addView(amount("—", 34, colors.onHero));
        }
        boolean first = true;
        for (Map.Entry<String, BigDecimal> total : totals.entrySet()) {
            hero.addView(amount(money(total.getKey(), total.getValue()), first ? 34 : 24, colors.onHero));
            first = false;
        }
        String converted = convertedSummary();
        if (converted != null) {
            gap(hero, 6);
            TextView line = label(converted, 14, colors.onHero, false);
            line.setAlpha(0.9f);
            hero.addView(line);
        }
        gap(hero, 18);
        LinearLayout figures = row();
        figures.addView(figure(active, "进行中"), new LinearLayout.LayoutParams(0, -2, 1f));
        figures.addView(figure(upcoming, "30 天内续费"), new LinearLayout.LayoutParams(0, -2, 1f));
        if (trials > 0) {
            figures.addView(figure(trials, "试用中"), new LinearLayout.LayoutParams(0, -2, 1f));
        }
        hero.addView(figures);
        String description = "每月预计支出，" + active + " 项进行中，" + upcoming + " 项 30 天内续费"
                + (trials > 0 ? "，" + trials + " 项试用中" : "") + "。查看统计";
        hero.setContentDescription(description);

        boolean beside = medium() && !twoPane() && !ledger.wallets.isEmpty();
        if (beside) {
            // Tablet: overview and balances side by side.
            LinearLayout pair = row();
            pair.addView(hero, new LinearLayout.LayoutParams(0, -1, 1.25f));
            LinearLayout.LayoutParams walletParams = new LinearLayout.LayoutParams(0, -1, 1f);
            walletParams.setMarginStart(dp(12));
            pair.addView(walletCard(), walletParams);
            summary.addView(pair, new LinearLayout.LayoutParams(-1, -2));
            return;
        }
        summary.addView(hero, new LinearLayout.LayoutParams(-1, -2));
        if (!ledger.wallets.isEmpty()) {
            gap(summary, 12);
            summary.addView(walletCard(), new LinearLayout.LayoutParams(-1, -2));
        }
    }

    private View figure(long value, String caption) {
        LinearLayout figure = column();
        figure.addView(amount(Long.toString(value), 22, colors.onHero));
        figure.addView(label(caption, 12, colors.heroText, false));
        return figure;
    }

    /** One row per balance account; accounts that cannot pay the next charge are highlighted. */
    private View walletCard() {
        boolean warning = false;
        LinearLayout card = column();
        card.setPadding(dp(18), dp(14), dp(18), dp(14));
        LinearLayout header = row();
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.addView(label("余额账户", 15, colors.ink, true), new LinearLayout.LayoutParams(0, -2, 1f));
        ImageView more = new ImageView(this);
        more.setImageDrawable(new Glyph(Glyph.FORWARD, colors.muted, dp(18)));
        header.addView(more, new LinearLayout.LayoutParams(dp(18), dp(18)));
        card.addView(header);
        for (Wallet wallet : ledger.wallets) {
            Ledger.Coverage cover = coverage.get(wallet.id);
            gap(card, 10);
            LinearLayout line = row();
            line.addView(label(wallet.name, 15, colors.ink, false), new LinearLayout.LayoutParams(0, -2, 1f));
            line.addView(amount(money(wallet.currency, wallet.balance), 15,
                    wallet.balance.signum() < 0 || cover.insufficient() ? colors.amber : colors.green));
            card.addView(line);
            String status = null;
            if (cover.insufficient()) {
                warning = true;
                status = "不够 " + cover.next + " 的扣款";
            } else if (cover.paidUntil != null && !cover.beyondHorizon) {
                status = "可用到 " + cover.paidUntil;
            }
            if (status != null) {
                card.addView(label(status, 12, cover.insufficient() ? colors.amber : colors.muted, cover.insufficient()));
            }
        }
        tappable(card, warning ? colors.lime : colors.card, 24, () -> open(WalletsActivity.class));
        return card;
    }

    /** The overview pane's links on expanded windows, so the settings menu is not the only way in. */
    private View shortcuts() {
        LinearLayout group = group();
        String[] names = {"余额账户", "扣款记录", "支出统计与汇率", "按月导出账单与发票"};
        Runnable[] actions = {() -> open(WalletsActivity.class), () -> open(HistoryActivity.class),
                () -> open(StatsActivity.class), this::chooseMonthToExport};
        for (int i = 0; i < names.length; i++) {
            Runnable action = actions[i];
            LinearLayout line = row();
            line.setGravity(Gravity.CENTER_VERTICAL);
            line.setMinimumHeight(dp(52));
            line.setPadding(dp(18), 0, dp(14), 0);
            line.addView(label(names[i], 15, colors.ink, false), new LinearLayout.LayoutParams(0, -2, 1f));
            ImageView chevron = new ImageView(this);
            chevron.setImageDrawable(new Glyph(Glyph.FORWARD, colors.muted, dp(18)));
            line.addView(chevron, new LinearLayout.LayoutParams(dp(18), dp(18)));
            line.setBackground(ripple(android.graphics.Color.TRANSPARENT, 0));
            line.setFocusable(true);
            line.setOnClickListener(view -> action.run());
            addRow(group, line);
        }
        return group;
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

    /** List width in dp, for choosing the number of card columns. */
    private int listWidthDp() {
        if (twoPane()) {
            float density = getResources().getDisplayMetrics().density;
            int insets = Math.round((insetLeft + insetRight) / density);
            return Math.min(1200, widthDp() - insets - SIDE_DP - 16 - margin());
        }
        return contentWidthDp(840);
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
        listCount.setText(visible.isEmpty() ? "" : visible.size() + " 项");
        List<View> cards = new ArrayList<>();
        for (Subscription entry : visible) {
            cards.add(subscriptionCard(entry));
        }
        grid(entries, cards, columns(listWidthDp(), 300, 12), 12);
        if (visible.isEmpty()) {
            LinearLayout empty = panel(colors.card, 24);
            if (ledger.subscriptions.isEmpty()) {
                empty.addView(label("添加第一份订阅", 18, colors.ink, true));
                gap(empty, 5);
                empty.addView(label("记录下一次续费日期、扣款金额和付款渠道。", 14, colors.muted, false));
                gap(empty, 12);
                empty.addView(action("使用 Claude 模板", colors.lime, colors.green, () -> editor(null, true)),
                        new LinearLayout.LayoutParams(-1, dp(48)));
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
        LinearLayout card = panel(colors.card, 24);
        tappable(card, colors.card, 24, () -> details(entry));
        card.setOnLongClickListener(view -> {
            quickActions(entry);
            return true;
        });
        card.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams iconParams = new LinearLayout.LayoutParams(dp(46), dp(46));
        iconParams.setMarginEnd(dp(14));
        card.addView(iconView(entry, entry.title, 46), iconParams);
        LinearLayout content = column();
        card.addView(content, new LinearLayout.LayoutParams(0, -2, 1f));
        LinearLayout top = row();
        top.setGravity(Gravity.TOP);
        TextView name = label(entry.title, 17, colors.ink, true);
        LinearLayout.LayoutParams nameParams = new LinearLayout.LayoutParams(0, -2, 1f);
        nameParams.setMarginEnd(dp(10));
        top.addView(name, nameParams);
        TextView price = amount(money(entry.currency, entry.amount), 16, colors.green);
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
            gap(content, 8);
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
            TextView when = label(line, 13, urgent ? colors.amber : colors.green, true);
            content.addView(when);
        }
        return card;
    }

    /** Long-press shortcuts on a subscription card. */
    private void quickActions(Subscription entry) {
        List<String> items = new ArrayList<>();
        List<Runnable> handlers = new ArrayList<>();
        items.add("编辑");
        handlers.add(() -> editor(entry, false));
        if (entry.mode == Subscription.Mode.MANUAL) {
            items.add("记录已续费");
            handlers.add(() -> confirmManualRenewal(entry));
        }
        items.add("扣款记录");
        handlers.add(() -> openHistory(entry));
        items.add("删除");
        handlers.add(() -> confirmDelete(entry));
        menu(entry.title, items.toArray(new String[0]), handlers.toArray(new Runnable[0]), items.size() - 1);
    }

    private void openHistory(Subscription entry) {
        startActivity(new Intent(this, HistoryActivity.class).putExtra(HistoryActivity.SUBSCRIPTION, entry.id));
    }

    private void details(Subscription entry) {
        Sheet sheet = sheet(entry.title).detents(0.62f, 1f).titleOnScroll();
        sheet.trailing("编辑", () -> {
            editor(entry, false);
            return true;
        });
        LinearLayout body = sheet.body;

        LinearLayout top = row();
        top.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams iconParams = new LinearLayout.LayoutParams(dp(60), dp(60));
        iconParams.setMarginEnd(dp(16));
        top.addView(iconView(entry, entry.title, 60), iconParams);
        LinearLayout titles = column();
        titles.addView(heading(label(entry.title, 22, colors.ink, true)));
        gap(titles, 2);
        titles.addView(label(money(entry.currency, entry.amount) + " · " + entry.cycle.label + " · "
                + entry.mode.label, 14, colors.muted, false));
        top.addView(titles, new LinearLayout.LayoutParams(0, -2, 1f));
        body.addView(top);

        if (entry.mode != Subscription.Mode.ENDED) {
            gap(body, 16);
            LocalDate next = entry.nextDate(today);
            long days = ChronoUnit.DAYS.between(today, next);
            LinearLayout when = panel(colors.lime, 22);
            when.addView(label(entry.trial ? "试用至" : "下次续费", 13, colors.green, true));
            gap(when, 2);
            when.addView(amount(next.toString(), 24, colors.ink));
            String relative = days < 0 ? "已过期" : days == 0 ? "就是今天" : days + " 天后";
            when.addView(label(relative + (entry.trial ? "，之后开始扣费" : "") + " · "
                    + reminderLabel(entry.reminderDays, entry.trial), 13, colors.muted, false));
            body.addView(when);
        }

        gap(body, 12);
        LinearLayout buttons = row();
        if (entry.mode == Subscription.Mode.MANUAL) {
            buttons.addView(action("记录已续费", colors.hero, colors.onHero, () -> {
                sheet.dismiss();
                confirmManualRenewal(entry);
            }), new LinearLayout.LayoutParams(0, dp(48), 1f));
        }
        LinearLayout.LayoutParams historyParams = new LinearLayout.LayoutParams(0, dp(48), 1f);
        if (buttons.getChildCount() > 0) {
            historyParams.setMarginStart(dp(10));
        }
        buttons.addView(action("扣款记录", colors.card, colors.green, () -> {
            sheet.dismiss();
            openHistory(entry);
        }), historyParams);
        body.addView(buttons);

        LinearLayout facts = group();
        if (!entry.category.isEmpty()) {
            addRow(facts, factRow("分类", entry.category, colors.ink));
        }
        Ledger.Money real = ledger.realCost(entry);
        if (entry.hasCost()) {
            addRow(facts, factRow("每期实际成本", money(real.currency, real.amount), colors.ink));
        } else if (!real.currency.equals(entry.currency)) {
            addRow(facts, factRow("每期实际成本", "约 " + money(real.currency, real.amount) + "（按余额账户成本）", colors.ink));
        }
        if (entry.splitWays > 1) {
            addRow(facts, factRow("分摊", entry.splitWays + " 人，我承担约 "
                    + money(real.currency, entry.myShare(real.amount)) + " / 期", colors.ink));
        }
        Wallet wallet = ledger.payingWallet(entry);
        if (wallet != null) {
            boolean short_ = walletShort(entry);
            addRow(facts, factRow("扣款账户", wallet.name + "，余额 " + money(wallet.currency, wallet.balance)
                    + (short_ ? "，不够下次扣款" : ""), short_ ? colors.amber : colors.ink));
        } else if (entry.walletId != null) {
            addRow(facts, factRow("扣款账户", "币种与订阅不同，未自动扣款", colors.amber));
        }
        if (!entry.payment.isEmpty()) {
            addRow(facts, factRow("付款渠道", entry.payment, colors.ink));
        }
        if (facts.getChildCount() > 0) {
            gap(body, 12);
            body.addView(facts);
        }
        if (!entry.prices.isEmpty()) {
            section(body, "价格变动");
            LinearLayout prices = group();
            for (Subscription.PricePoint price : entry.prices) {
                addRow(prices, factRow(price.until + " 前", money(price.currency, price.amount), colors.muted));
            }
            addRow(prices, factRow("现在", money(entry.currency, entry.amount), colors.ink));
            body.addView(prices);
        }
        if (!entry.note.isEmpty()) {
            section(body, "备注");
            LinearLayout note = panel(colors.card, 22);
            note.addView(label(entry.note, 15, colors.ink, false));
            body.addView(note);
        }
        section(body, "每期账单与发票");
        body.addView(label("点 ＋ 上传这一期的发票；点按缩略图查看，长按可删除或移到其他账期。", 12, colors.muted, false));
        gap(body, 10);
        LinearLayout periods = column();
        body.addView(periods);
        renderPeriods(periods, entry.id);
        section(body, "订阅资料");
        body.addView(label("不属于某一期的截图，例如套餐页面。", 12, colors.muted, false));
        gap(body, 10);
        body.addView(new AttachmentStrip(entry.id), new LinearLayout.LayoutParams(-1, -2));
        sheet.show();
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
            LinearLayout box = panel(colors.lime, 22);
            box.addView(label(due + " 这一期还没有扣款记录", 15, colors.ink, true));
            gap(box, 2);
            box.addView(label("补记后即可上传这一期的发票。补记不会改动余额账户。", 12, colors.muted, false));
            gap(box, 10);
            box.addView(action("补记 " + money(entry.currency, entry.amount), colors.card, colors.green,
                    () -> backfillPeriod(entry.id, due, periods)), new LinearLayout.LayoutParams(-1, dp(46)));
            periods.addView(box);
            gap(periods, 10);
        }
        for (int i = 0; i < Math.min(PERIODS_SHOWN, history.size()); i++) {
            Payment payment = history.get(i);
            LinearLayout card = panel(colors.card, 22);
            LinearLayout heading = row();
            heading.addView(label(payment.date.toString(), 15, colors.ink, true), new LinearLayout.LayoutParams(0, -2, 1f));
            heading.addView(amount(money(payment.currency, payment.amount), 15, colors.green));
            card.addView(heading);
            if (!payment.auto) {
                card.addView(label("手动记录", 12, colors.muted, false));
            }
            gap(card, 10);
            card.addView(new AttachmentStrip(payment.id), new LinearLayout.LayoutParams(-1, -2));
            periods.addView(card);
            gap(periods, 10);
        }
        if (history.size() > PERIODS_SHOWN) {
            periods.addView(action("查看全部 " + history.size() + " 期", colors.line, colors.green,
                    () -> openHistory(entry)), new LinearLayout.LayoutParams(-1, dp(46)));
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
        alert().setTitle("记录已续费").setMessage(message)
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
        Sheet sheet = sheet(entry == null ? "添加订阅" : "编辑订阅").detents(0.62f, 1f).expanded();
        LinearLayout fields = sheet.body;

        EditText name = input("例如 Claude Pro", entry == null
                ? claudeTemplate ? "Claude Pro" : "" : entry.title,
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES, 100);
        IconChoice icon = new IconChoice(entry);
        ImageView iconPreview = new ImageView(this);
        iconPreview.setContentDescription("更换图标");
        Runnable refreshIcon = () -> iconPreview.setImageDrawable(icon.drawable(fieldValue(name), 52));
        iconPreview.setOnClickListener(view -> iconDialog(icon, fieldValue(name), refreshIcon));
        Motion.pressable(iconPreview, 0.92f);
        LinearLayout nameRow = row();
        nameRow.setGravity(Gravity.CENTER_VERTICAL);
        nameRow.addView(iconPreview, new LinearLayout.LayoutParams(dp(52), dp(52)));
        LinearLayout.LayoutParams nameParams = new LinearLayout.LayoutParams(0, -2, 1f);
        nameParams.setMarginStart(dp(12));
        name.setContentDescription("订阅名称");
        nameRow.addView(name, nameParams);
        addField(fields, "订阅名称", nameRow);
        hint(fields, "点按图标可换成图片，或修改文字和颜色。");
        refreshIcon.run();
        name.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence text, int start, int count, int after) { }
            public void onTextChanged(CharSequence text, int start, int before, int count) {
                refreshIcon.run();
            }
            public void afterTextChanged(Editable text) { }
        });

        LinearLayout priceRow = row();
        EditText amount = input("账单金额", entry == null ? "" : entry.amount.toPlainString(),
                InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL, 20);
        amount.setContentDescription("单次金额");
        priceRow.addView(amount, new LinearLayout.LayoutParams(0, -2, 1f));
        EditText currency = input("币种", entry == null ? claudeTemplate ? "USD" : "CNY" : entry.currency,
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS, 3);
        currency.setContentDescription("币种，三字母代码，例如 CNY、USD、JPY");
        LinearLayout.LayoutParams currencyParams = new LinearLayout.LayoutParams(dp(96), -2);
        currencyParams.setMarginStart(dp(8));
        priceRow.addView(currency, currencyParams);
        addField(fields, "单次金额与币种", priceRow);

        Segmented cycle = segmented(labels(Subscription.Cycle.values()),
                entry == null ? Subscription.Cycle.MONTHLY.ordinal() : entry.cycle.ordinal(), null);
        addField(fields, "账单周期", cycle);
        cycle.getLayoutParams().height = dp(42);
        Segmented mode = segmented(labels(Subscription.Mode.values()),
                entry == null ? Subscription.Mode.AUTO.ordinal() : entry.mode.ordinal(), null);
        addField(fields, "续费状态", mode);
        mode.getLayoutParams().height = dp(42);

        LocalDate[] selectedDate = {entry == null ? templateDate(claudeTemplate) : entry.anchor};
        TextView date = pickerField(selectedDate[0].toString());
        date.setOnClickListener(view -> chooseDate(selectedDate[0], selected -> {
            selectedDate[0] = selected;
            date.setText(selected.toString());
        }));
        addField(fields, "账单续费日期", date);
        hint(fields, "自动续费按此日期推算后续账期。");
        CompoundButton trial = check("免费试用中", entry != null && entry.trial);
        fields.addView(trial, new LinearLayout.LayoutParams(-1, -2));
        TextView trialHint = label("试用时，上面的日期填试用结束、开始扣费的日子。", 12, colors.muted, false);
        trialHint.setPadding(dp(4), 0, dp(4), 0);
        fields.addView(trialHint);

        EditText days = input("0–90，留空关闭提醒", entry == null ? "3"
                : entry.reminderDays < 0 ? "" : Integer.toString(entry.reminderDays),
                InputType.TYPE_CLASS_NUMBER, 2);
        addField(fields, "提前提醒天数", days);

        // Optional details, folded away until wanted (or already filled in).
        LinearLayout more = column();
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
        addField(more, "分类", category);

        EditText payment = input("例如美区 Apple ID 礼品卡余额", entry == null
                ? claudeTemplate ? "美区 Apple ID 礼品卡余额" : "" : entry.payment,
                InputType.TYPE_CLASS_TEXT, 120);
        addField(more, "付款渠道", payment);

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
            addField(more, "从余额账户扣款", walletChoice);
            hint(more, "续费时自动从该账户扣除，账户币种需与订阅一致。");
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
        LinearLayout.LayoutParams costCodeParams = new LinearLayout.LayoutParams(dp(96), -2);
        costCodeParams.setMarginStart(dp(8));
        costRow.addView(costCode, costCodeParams);
        addField(more, "每期实际成本", costRow);
        hint(more, "每期真实花了多少钱。留空时，从余额账户扣款的按账户成本计算，其余按当前汇率。");

        EditText split = input("1 表示不分摊", entry == null ? "1" : Integer.toString(entry.splitWays),
                InputType.TYPE_CLASS_NUMBER, 2);
        addField(more, "分摊人数（含自己）", split);
        hint(more, "统计只计入你承担的部分。");

        EditText note = input("例如扣款账号的备注", entry == null ? "" : entry.note,
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE, 500);
        addField(more, "备注", note);

        boolean filled = entry != null && (!entry.category.isEmpty() || !entry.payment.isEmpty()
                || entry.walletId != null || entry.hasCost() || entry.splitWays > 1 || !entry.note.isEmpty());
        gap(fields, 16);
        TextView disclosure = label("", 15, colors.green, true);
        disclosure.setGravity(Gravity.CENTER_VERTICAL);
        disclosure.setMinHeight(dp(52));
        disclosure.setPadding(dp(16), 0, dp(16), 0);
        tappable(disclosure, colors.card, 16, () -> { });
        Runnable[] toggle = new Runnable[1];
        toggle[0] = () -> {
            boolean open = more.getVisibility() != View.VISIBLE;
            more.setVisibility(open ? View.VISIBLE : View.GONE);
            disclosure.setText(open ? "收起选填项" : "更多：分类、付款渠道、余额账户、实际成本、分摊、备注");
            if (open) {
                more.setAlpha(0f);
                more.animate().alpha(1f).setDuration(220).setInterpolator(Motion.EMPHASIZED).start();
            }
        };
        more.setVisibility(filled || claudeTemplate ? View.GONE : View.VISIBLE);
        toggle[0].run();
        disclosure.setOnClickListener(view -> toggle[0].run());
        fields.addView(disclosure, new LinearLayout.LayoutParams(-1, -2));
        fields.addView(more);

        if (entry != null) {
            gap(fields, 24);
            fields.addView(action("删除订阅", colors.card, colors.amber, () -> confirmDelete(entry, sheet)),
                    new LinearLayout.LayoutParams(-1, dp(50)));
        }

        Supplier<String> state = () -> String.join("\u0001", fieldValue(name), fieldValue(amount),
                fieldValue(currency), Integer.toString(cycle.selected()), Integer.toString(mode.selected()),
                selectedDate[0].toString(), Boolean.toString(trial.isChecked()), fieldValue(days),
                fieldValue(category), fieldValue(payment), Integer.toString(walletChoice.getSelectedItemPosition()),
                fieldValue(costAmount), fieldValue(costCode), fieldValue(split), fieldValue(note),
                String.valueOf(icon.text), String.valueOf(icon.color), String.valueOf(icon.image));
        String initial = state.get();
        sheet.unsaved(() -> !state.get().equals(initial));
        sheet.leading("取消", sheet::cancel);
        // A picture picked in this editor but not saved is deleted.
        sheet.onDismiss(icon::discardPicked);
        sheet.trailing("保存", () -> {
            String title = fieldValue(name);
            if (title.isEmpty()) {
                name.setError("请填写订阅名称");
                return false;
            }
            BigDecimal value = parseAmount(fieldValue(amount));
            if (value == null) {
                amount.setError("请输入有效金额，例如 20 或 19.99");
                return false;
            }
            String code = fieldValue(currency).toUpperCase(Locale.ROOT);
            if (!validCurrency(code)) {
                currency.setError("请输入有效币种，例如 CNY、USD 或 JPY");
                return false;
            }
            BigDecimal costValue = null;
            String costCurrency = fieldValue(costCode).toUpperCase(Locale.ROOT);
            if (!fieldValue(costAmount).isEmpty()) {
                costValue = parseAmount(fieldValue(costAmount));
                if (costValue == null) {
                    revealMore(more, toggle[0]);
                    costAmount.setError("请输入有效金额，或留空");
                    return false;
                }
                if (!validCurrency(costCurrency)) {
                    revealMore(more, toggle[0]);
                    costCode.setError("请输入有效币种，例如 CNY");
                    return false;
                }
            }
            int ways;
            try {
                ways = Integer.parseInt(fieldValue(split));
            } catch (NumberFormatException ex) {
                ways = 0;
            }
            if (ways < 1 || ways > Subscription.MAX_SPLIT) {
                revealMore(more, toggle[0]);
                split.setError("请输入 1–99");
                return false;
            }
            Wallet wallet = walletChoice.getSelectedItemPosition() == 0 ? null
                    : ledger.wallets.get(walletChoice.getSelectedItemPosition() - 1);
            if (wallet != null && !wallet.currency.equals(code)) {
                toast("「" + wallet.name + "」是 " + wallet.currency + " 账户，与订阅币种 " + code + " 不一致");
                return false;
            }
            Subscription.Builder b = entry == null ? new Subscription.Builder() : entry.toBuilder();
            b.title = title;
            b.amount = value;
            b.currency = code;
            b.cycle = Subscription.Cycle.values()[cycle.selected()];
            b.mode = Subscription.Mode.values()[mode.selected()];
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
                return false;
            }
            if (icon.image != null && icon.image.equals(icon.picked)) {
                if (!AttachmentStore.moveIcon(this, icon.image)) {
                    toast("无法保存图标图片，请重新选择");
                    return false;
                }
                icon.picked = null;
            }
            boolean firstEntry = ledger.subscriptions.isEmpty();
            boolean resetReminder = entry != null && (entry.reminderDays != updated.reminderDays
                    || entry.mode != updated.mode || entry.cycle != updated.cycle
                    || entry.trial != updated.trial || !entry.anchor.equals(updated.anchor));
            if (!save(updated, resetReminder)) {
                return false;
            }
            if (firstEntry && updated.mode != Subscription.Mode.ENDED
                    && updated.reminderDays >= 0 && Build.VERSION.SDK_INT >= 33
                    && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, NOTIFICATIONS);
            }
            return true;
        });
        sheet.show();
    }

    /** Unfolds the optional fields so a validation error on one of them is visible. */
    private static void revealMore(LinearLayout more, Runnable toggle) {
        if (more.getVisibility() != View.VISIBLE) {
            toggle.run();
        }
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
        Sheet sheet = sheet("图标").detents(0.7f, 1f);
        LinearLayout body = sheet.body;
        ImageView preview = new ImageView(this);
        preview.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        LinearLayout.LayoutParams previewParams = new LinearLayout.LayoutParams(dp(84), dp(84));
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
            preview.setImageDrawable(icon.drawable(title, 84));
            pictureRow.removeAllViews();
            pictureRow.addView(action(icon.image == null ? "从相册选图" : "换一张图片", colors.card, colors.green,
                    () -> pickIcon(icon, update[0])), new LinearLayout.LayoutParams(0, dp(48), 1f));
            if (icon.image != null) {
                LinearLayout.LayoutParams removeParams = new LinearLayout.LayoutParams(0, dp(48), 1f);
                removeParams.setMarginStart(dp(8));
                pictureRow.addView(action("移除图片", colors.card, colors.amber, () -> {
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

        gap(body, 16);
        body.addView(pictureRow);
        hint(body, "设置图片后显示图片，移除后显示下面的文字图标。");
        addField(body, "图标文字（最多 " + Icons.MAX_TEXT + " 个字符）", text);
        gap(body, 12);
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
        sheet.leading("恢复默认", () -> {
            icon.text = null;
            icon.color = null;
            icon.removeImage();
            changed.run();
            sheet.dismiss();
        });
        sheet.trailing("完成", () -> true);
        sheet.show();
    }

    private interface ColorSelection {
        void select(Integer color);
    }

    /** Colour tiles in rows of six, starting with "自动"; the selected one is outlined. */
    private void renderSwatches(LinearLayout grid, IconChoice icon, ColorSelection selection) {
        grid.removeAllViews();
        int perRow = 6;
        LinearLayout line = null;
        int total = Icons.SWATCHES.length + 1;
        for (int i = -1; i < Icons.SWATCHES.length; i++) {
            if ((i + 1) % perRow == 0) {
                line = row();
                grid.addView(line, new LinearLayout.LayoutParams(-1, dp(48)));
            }
            Integer color = i < 0 ? null : Integer.valueOf(Icons.SWATCHES[i]);
            boolean selected = color == null ? icon.color == null : color.equals(icon.color);
            Squircle dot = new Squircle(color == null ? colors.card : 0xFF000000 | color, dp(12))
                    .stroke(selected ? colors.green : colors.line, selected ? dp(3) : dp(1));
            TextView swatch = new TextView(this);
            swatch.setBackground(dot);
            swatch.setGravity(Gravity.CENTER);
            if (color == null) {
                swatch.setText("自动");
                swatch.setTextSize(11);
                swatch.setTextColor(colors.ink);
            } else if (selected) {
                // The ring alone vanishes on swatches close to the accent; the check always reads.
                int mark = 0xFF000000 | new Icons.Badge("", color).textColor();
                swatch.setForeground(new InsetDrawable(new Glyph(Glyph.CHECK, mark, dp(20)), dp(9)));
            }
            swatch.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            // Equal cells keep six tiles per row on narrow sheets; the whole cell is the tap target.
            LinearLayout cell = row();
            cell.setGravity(Gravity.CENTER);
            cell.setContentDescription((color == null ? "自动颜色" : Icons.formatColor(color))
                    + (selected ? "，已选择" : ""));
            cell.setOnClickListener(view -> selection.select(color));
            Motion.pressable(cell, 0.9f);
            cell.addView(swatch, new LinearLayout.LayoutParams(dp(38), dp(38)));
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
        confirmDelete(entry, null);
    }

    /** Asks before deleting; {@code editor} is the open editor sheet to close afterwards, or null. */
    private void confirmDelete(Subscription entry, Sheet editor) {
        alert().setTitle("删除订阅")
                .setMessage("确定删除“" + entry.title + "”？订阅的附件会一并删除，已有的扣款记录会保留。")
                .setNegativeButton("取消", null)
                .setPositiveButton("删除", (dialog, button) -> {
                    RenewalReminder.clearReceipt(this, entry.id);
                    if (commit(() -> ledgerStore().load().removeSubscription(entry.id))) {
                        if (editor != null) {
                            editor.dismiss();
                        }
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
        List<String> items = new ArrayList<>();
        List<Runnable> handlers = new ArrayList<>();
        items.add(RenewalReminder.enabled(this) ? "管理系统通知" : "开启续费提醒");
        handlers.add(() -> startActivity(new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName())));
        if (Palette.dynamicAvailable()) {
            boolean dynamic = Palette.dynamicEnabled(this);
            items.add(dynamic ? "配色：跟随壁纸（点按改用应用绿色）" : "配色：应用绿色（点按改为跟随壁纸）");
            handlers.add(() -> {
                Palette.setDynamic(this, !dynamic);
                RenewalReminder.refresh(this);
                recreate();
            });
        }
        items.add("检查更新（当前版本 " + Updater.installedVersion(this) + "）");
        handlers.add(() -> checkForUpdate(true));
        boolean auto = Updater.autoEnabled(this);
        items.add(auto ? "自动检查更新：开（点按关闭）" : "自动检查更新：关（点按开启）");
        handlers.add(() -> {
            Updater.setAuto(this, !auto);
            toast(auto ? "已关闭自动检查更新" : "已开启自动检查更新，每天最多检查一次");
        });
        String[] rest = {"余额账户", "扣款记录", "支出统计与汇率", "按月导出账单与发票", "导出备份（含附件）",
                "从备份合并", "使用 Claude 模板"};
        Runnable[] restHandlers = {
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
        java.util.Collections.addAll(items, rest);
        java.util.Collections.addAll(handlers, restHandlers);
        menu("设置", items.toArray(new String[0]), handlers.toArray(new Runnable[0]), -1);
    }

    // --- Updates ----------------------------------------------------------------------------

    /** A release found while a sheet or dialog had focus; shown once the list is in front again. */
    private Updater.Release pendingUpdate;

    /** Looks for a newer release: at most daily and quietly from onResume, or on demand. */
    private void checkForUpdate(boolean manual) {
        if (!manual && !Updater.due(this)) {
            return;
        }
        if (manual) {
            toast("正在检查更新…");
        }
        if (!Updater.begin()) {
            return;
        }
        Context app = getApplicationContext();
        new Thread(() -> {
            Updater.Release release = null;
            String error = null;
            try {
                release = Updater.latest();
                Updater.markChecked(app);
            } catch (IOException ex) {
                error = networkError(ex);
            } finally {
                Updater.done();
            }
            Updater.Release found = release;
            String failure = error;
            runOnUiThread(() -> updateChecked(found, failure, manual));
        }).start();
    }

    private void updateChecked(Updater.Release release, String error, boolean manual) {
        if (isFinishing() || isDestroyed()) {
            return;
        }
        if (error != null) {
            if (manual) {
                toast("检查更新失败：" + error);
            }
            return;
        }
        String current = Updater.installedVersion(this);
        if (release == null || !Updater.newer(release.version, current)) {
            if (manual) {
                toast("已是最新版本 " + current);
            }
            return;
        }
        if (!manual && release.tag.equals(Updater.skipped(this))) {
            return;
        }
        // Wait for open sheets and dialogs to close rather than stacking on top of them.
        if (hasWindowFocus()) {
            showUpdate(release);
        } else {
            pendingUpdate = release;
        }
    }

    private void showUpdate(Updater.Release release) {
        Sheet sheet = sheet("软件更新").detents(0.6f, 1f);
        LinearLayout body = sheet.body;
        body.addView(heading(label("订阅账本 " + release.version, 24, colors.ink, true)));
        gap(body, 4);
        body.addView(label("当前版本 " + Updater.installedVersion(this)
                + (release.size > 0 ? " · 大小 " + fileSize(release.size) : ""), 14, colors.muted, false));
        String notes = Updater.plainNotes(release.notes);
        if (!notes.isEmpty()) {
            gap(body, 16);
            LinearLayout card = panel(colors.card, 22);
            card.addView(label("更新内容", 13, colors.green, true));
            gap(card, 6);
            TextView text = label(notes, 15, colors.ink, false);
            text.setLineSpacing(dp(4), 1f);
            card.addView(text);
            body.addView(card);
        }
        gap(body, 18);
        ProgressBar bar = progressBar();
        bar.setVisibility(View.GONE);
        LinearLayout.LayoutParams barParams = new LinearLayout.LayoutParams(-1, dp(6));
        barParams.bottomMargin = dp(14);
        body.addView(bar, barParams);
        TextView install = action("下载并安装", colors.hero, colors.onHero, null);
        install.setOnClickListener(view -> downloadUpdate(release, sheet, install, bar));
        body.addView(install, new LinearLayout.LayoutParams(-1, dp(52)));
        gap(body, 10);
        body.addView(action("跳过这个版本", colors.card, colors.green, () -> {
            Updater.skip(this, release.tag);
            sheet.dismiss();
        }), new LinearLayout.LayoutParams(-1, dp(52)));
        gap(body, 8);
        body.addView(label("跳过后不再自动提醒这个版本，仍可在设置里手动检查。安装前 Android 会请你确认。",
                13, colors.muted, false));
        sheet.show();
    }

    private void downloadUpdate(Updater.Release release, Sheet sheet, TextView button, ProgressBar bar) {
        if (!getPackageManager().canRequestPackageInstalls()) {
            alert().setTitle("允许安装更新")
                    .setMessage("需要在系统设置里允许「订阅账本」安装应用。开启后返回这里，再点一次「下载并安装」。")
                    .setPositiveButton("去设置", (dialog, which) -> startActivity(new Intent(
                            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                            android.net.Uri.parse("package:" + getPackageName()))))
                    .setNegativeButton("取消", null)
                    .show();
            return;
        }
        if (!Updater.begin()) {
            toast("正在检查更新，请稍后再试");
            return;
        }
        button.setEnabled(false);
        button.setAlpha(0.6f);
        button.setText("正在下载…");
        bar.setProgress(0);
        bar.setVisibility(View.VISIBLE);
        Context app = getApplicationContext();
        new Thread(() -> {
            String error = null;
            try {
                Updater.install(app, release, (done, total) -> {
                    if (total > 0) {
                        int permille = (int) (done * 1000 / total);
                        runOnUiThread(() -> bar.setProgress(permille));
                    }
                });
            } catch (IOException ex) {
                error = networkError(ex);
            } catch (RuntimeException ex) {
                // PackageInstaller refused the session (e.g. a SecurityException).
                error = "无法开始安装";
            } finally {
                Updater.done();
            }
            String failure = error;
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) {
                    return;
                }
                if (failure == null) {
                    // Android shows its confirm screen next; UpdateStatus reports the outcome.
                    sheet.dismiss();
                    return;
                }
                button.setEnabled(true);
                button.setAlpha(1f);
                button.setText("重试");
                bar.setVisibility(View.GONE);
                toast("更新失败：" + failure);
            });
        }).start();
    }

    private ProgressBar progressBar() {
        ProgressBar bar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        bar.setIndeterminate(false);
        bar.setMax(1000);
        LayerDrawable track = new LayerDrawable(new Drawable[]{shape(colors.line, 3),
                new ClipDrawable(shape(colors.green, 3), Gravity.START, ClipDrawable.HORIZONTAL)});
        track.setId(0, android.R.id.background);
        track.setId(1, android.R.id.progress);
        bar.setProgressDrawable(track);
        return bar;
    }

    /** Our own IOExceptions carry Chinese messages; the platform's network ones do not. */
    private static String networkError(IOException ex) {
        return ex.getClass() == IOException.class && ex.getMessage() != null
                ? ex.getMessage() : "网络不可用，请稍后再试";
    }

    private static String fileSize(long bytes) {
        return bytes < 1024 * 1024
                ? Math.max(1, Math.round(bytes / 1024.0)) + " KB"
                : String.format(Locale.ROOT, "%.1f MB", bytes / (1024.0 * 1024.0));
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
        alert().setTitle("合并备份")
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
