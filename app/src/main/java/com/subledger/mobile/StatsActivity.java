package com.subledger.mobile;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.os.Bundle;
import android.text.InputType;
import android.text.method.LinkMovementMethod;
import android.text.util.Linkify;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.TreeSet;

public final class StatsActivity extends LedgerActivity {
    private static final int COLLAPSED_ROWS = 8;
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.ROOT);
    private LinearLayout root, left, right;
    private Ledger ledger;
    private Rates rates;
    private Stats stats;
    private LocalDate today;
    private boolean allItems, allPayments;
    /** The chart grows in once per visit, not on every refresh. */
    private boolean chartShown;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        if (state != null) {
            allItems = state.getBoolean("allItems");
            allPayments = state.getBoolean("allPayments");
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        render();
        refreshRatesIfStale(ledger, this::render);
    }

    @Override
    protected void onSaveInstanceState(Bundle state) {
        super.onSaveInstanceState(state);
        state.putBoolean("allItems", allItems);
        state.putBoolean("allPayments", allPayments);
    }

    private void render() {
        today = LocalDate.now();
        ledger = ledgerStore().load();
        rates = rateStore().load();
        stats = new Stats(ledger, rates, today);
        root = screen("支出统计", null, 1100);
        // Medium and wider: totals and the chart on the start side, the breakdowns beside them.
        left = root;
        right = root;
        if (medium()) {
            LinearLayout pair = row();
            left = column();
            right = column();
            pair.addView(left, new LinearLayout.LayoutParams(0, -2, 1f));
            LinearLayout.LayoutParams rightParams = new LinearLayout.LayoutParams(0, -2, 1f);
            rightParams.setMarginStart(dp(20));
            pair.addView(right, rightParams);
            root.addView(pair);
        }

        if (!stats.missing.isEmpty()) {
            renderMissingRates();
            gap(left, 14);
        }
        if (stats.active == 0) {
            LinearLayout empty = panel(colors.card, 24);
            empty.addView(label("还没有进行中的订阅", 17, colors.ink, true));
            gap(empty, 4);
            empty.addView(label("添加订阅后，这里会显示月度、年度支出和未来扣款。", 14, colors.muted, false));
            left.addView(empty);
        } else if (stats.counted > 0) {
            renderTotals();
            section(left, "未来 12 个月扣款");
            renderMonths();
            section(right, "按订阅");
            renderShares(right, stats.items, allItems, () -> {
                allItems = true;
                render();
            });
            section(right, "按分类");
            renderShares(right, stats.byCategory, true, null);
            section(right, "按付款渠道");
            renderShares(right, stats.byPayment, allPayments, () -> {
                allPayments = true;
                render();
            });
            section(left, "按账期");
            renderShares(left, stats.byCycle, true, null);
        }
        section(left, "汇率");
        renderRates();
        gap(root, 16);
        root.addView(label("预计支出只计进行中的订阅和你承担的部分。月均按账期折算：周付按每年 52 周，"
                + "季付除以 3，年付除以 12；年度为月均 × 12。未来扣款按续费日期逐笔推算，手动续费按账期估计。"
                + "每笔的成本依次取：填写的实际成本、余额账户的实际成本、按上方当前汇率折算。"
                + "已支出来自扣款记录，成本在记录时就已固定，不随汇率变化。", 12, colors.muted, false));
    }

    private void renderMissingRates() {
        LinearLayout notice = panel(colors.lime, 24);
        int skipped = stats.active - stats.counted;
        notice.addView(label("缺少 " + String.join("、", stats.missing) + " 汇率", 16, colors.ink, true));
        gap(notice, 4);
        notice.addView(label(skipped + " 项订阅暂未计入统计。可在线获取汇率，或手动填写。", 14, colors.ink, false));
        gap(notice, 10);
        LinearLayout buttons = row();
        buttons.addView(action("在线获取汇率", colors.hero, colors.onHero, () -> downloadRates(false, this::render)),
                new LinearLayout.LayoutParams(0, dp(48), 1f));
        LinearLayout.LayoutParams manualParams = new LinearLayout.LayoutParams(0, dp(48), 1f);
        manualParams.setMarginStart(dp(8));
        buttons.addView(action("手动填写", colors.card, colors.green, () -> editRate(stats.missing.first())),
                manualParams);
        notice.addView(buttons);
        left.addView(notice);
    }

    private void renderTotals() {
        LinearLayout hero = column();
        hero.setPadding(dp(22), dp(20), dp(22), dp(20));
        hero.addView(label("每月预计支出", 14, colors.heroText, true));
        gap(hero, 6);
        hero.addView(amount(money(stats.base, stats.monthly), 34, colors.onHero));
        gap(hero, 16);
        LinearLayout tiles = row();
        tiles.addView(tile("每年预计", money(stats.base, stats.yearly)),
                new LinearLayout.LayoutParams(0, -2, 1f));
        tiles.addView(tile("未来 12 个月扣款", money(stats.base, stats.upcoming)),
                new LinearLayout.LayoutParams(0, -2, 1f));
        hero.addView(tiles);
        gap(hero, 12);
        if (stats.paymentCount > 0) {
            gap(hero, 12);
            LinearLayout spent = row();
            spent.addView(tile(today.getYear() + " 年已支出", money(stats.base, stats.spentThisYear)),
                    new LinearLayout.LayoutParams(0, -2, 1f));
            spent.addView(tile("近 12 个月已支出", money(stats.base, stats.spentLastYear)),
                    new LinearLayout.LayoutParams(0, -2, 1f));
            hero.addView(spent);
        }
        gap(hero, 12);
        String footer = "按 " + stats.base + " 折算 · 已计入 " + stats.counted + " / " + stats.active + " 项进行中订阅";
        if (stats.unconvertedPayments > 0) {
            footer += " · " + stats.unconvertedPayments + " 笔扣款缺少汇率";
        }
        hero.addView(label(footer + " · 扣款记录 ›", 13, colors.heroText, false));
        tappable(hero, colors.hero, 28, () -> startActivity(new android.content.Intent(this, HistoryActivity.class)));
        left.addView(hero, new LinearLayout.LayoutParams(-1, -2));
    }

    private View tile(String title, String value) {
        LinearLayout tile = column();
        tile.addView(label(title, 13, colors.heroText, false));
        gap(tile, 2);
        tile.addView(amount(value, 18, colors.onHero));
        return tile;
    }

    private void renderMonths() {
        LinearLayout card = panel(colors.card, 24);
        Stats.Month peak = stats.months.get(0);
        for (Stats.Month month : stats.months) {
            if (month.total.compareTo(peak.total) > 0) {
                peak = month;
            }
        }
        if (peak.total.signum() == 0) {
            card.addView(label("未来 12 个月没有预计扣款", 14, colors.muted, false));
            left.addView(card);
            return;
        }
        card.addView(label("最高 " + monthName(peak) + "：" + money(stats.base, peak.total), 14, colors.ink, true));
        gap(card, 10);
        MonthBars chart = new MonthBars(this, colors, stats.months, this::showMonth, !chartShown);
        chartShown = true;
        StringBuilder description = new StringBuilder("未来 12 个月扣款柱状图。");
        for (Stats.Month month : stats.months) {
            description.append(monthName(month)).append(' ')
                    .append(money(stats.base, month.total)).append("；");
        }
        chart.setContentDescription(description);
        card.addView(chart, new LinearLayout.LayoutParams(-1, dp(190)));
        gap(card, 8);
        card.addView(label("点按柱形查看当月明细。本月只计今天及以后的扣款。", 12, colors.muted, false));
        left.addView(card);
    }

    private String monthName(Stats.Month month) {
        return month.month.getYear() + " 年 " + month.month.getMonthValue() + " 月";
    }

    private void showMonth(Stats.Month month) {
        Sheet sheet = sheet(monthName(month)).detents(0.5f, 1f);
        LinearLayout body = sheet.body;
        body.addView(amount(money(stats.base, month.total), 28, colors.ink));
        body.addView(label(month.charges.size() + " 笔预计扣款", 13, colors.muted, false));
        gap(body, 14);
        if (month.charges.isEmpty()) {
            body.addView(label("这个月没有预计扣款", 15, colors.muted, false));
            sheet.show();
            return;
        }
        LinearLayout group = group();
        for (Stats.Charge charge : month.charges) {
            StringBuilder detail = new StringBuilder(charge.date.getMonthValue() + " 月 "
                    + charge.date.getDayOfMonth() + " 日");
            if (!charge.entry.currency.equals(stats.base) || charge.entry.splitWays > 1
                    || !ledger.realCost(charge.entry).currency.equals(charge.entry.currency)) {
                detail.append(" · 账单 ").append(money(charge.entry.currency, charge.entry.amount));
                if (charge.entry.splitWays > 1) {
                    detail.append("，").append(charge.entry.splitWays).append(" 人分摊");
                }
            }
            if (charge.entry.trial && charge.date.equals(charge.entry.anchor)) {
                detail.append(" · 试用结束");
            }
            if (charge.entry.mode == Subscription.Mode.MANUAL) {
                detail.append(" · 手动");
            }
            LinearLayout line = row();
            line.setGravity(Gravity.CENTER_VERTICAL);
            line.setPadding(dp(16), dp(12), dp(16), dp(12));
            LinearLayout.LayoutParams iconParams = new LinearLayout.LayoutParams(dp(36), dp(36));
            iconParams.setMarginEnd(dp(12));
            line.addView(iconView(charge.entry, charge.entry.title, 36), iconParams);
            LinearLayout text = column();
            text.addView(label(charge.entry.title, 15, colors.ink, true));
            text.addView(label(detail.toString(), 12, colors.muted, false));
            line.addView(text, new LinearLayout.LayoutParams(0, -2, 1f));
            TextView value = amount(money(stats.base, charge.amount), 15, colors.green);
            LinearLayout.LayoutParams valueParams = new LinearLayout.LayoutParams(-2, -2);
            valueParams.setMarginStart(dp(8));
            line.addView(value, valueParams);
            addRow(group, line);
        }
        body.addView(group);
        sheet.show();
    }

    private void renderShares(LinearLayout parent, List<Stats.Share> shares, boolean showAll, Runnable expand) {
        LinearLayout card = panel(colors.card, 24);
        BigDecimal max = BigDecimal.ZERO;
        for (Stats.Share share : shares) {
            max = max.max(share.monthly);
        }
        int shown = showAll ? shares.size() : Math.min(COLLAPSED_ROWS, shares.size());
        for (int i = 0; i < shown; i++) {
            if (i > 0) {
                gap(card, 14);
            }
            card.addView(shareRow(shares.get(i), max));
        }
        if (shown < shares.size()) {
            gap(card, 12);
            card.addView(action("展开全部 " + shares.size() + " 项", colors.line, colors.green, expand),
                    new LinearLayout.LayoutParams(-1, dp(44)));
        }
        parent.addView(card);
    }

    private View shareRow(Stats.Share share, BigDecimal max) {
        LinearLayout item = column();
        LinearLayout top = row();
        TextView name = label(share.label, 15, colors.ink, true);
        name.setSingleLine(true);
        name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams nameParams = new LinearLayout.LayoutParams(0, -2, 1f);
        nameParams.setMarginEnd(dp(10));
        top.addView(name, nameParams);
        top.addView(amount(money(stats.base, share.monthly) + " / 月", 14, colors.ink));
        item.addView(top);
        gap(item, 6);

        // Bar length is relative to the largest row; the share of the total is printed below.
        LinearLayout track = row();
        track.setBackground(shape(colors.line, 4));
        float fraction = max.signum() == 0 ? 0f
                : share.monthly.divide(max, MathContext.DECIMAL32).floatValue();
        View bar = new View(this);
        bar.setBackground(shape(colors.green, 4));
        track.addView(bar, new LinearLayout.LayoutParams(0, -1, Math.max(fraction, 0.01f)));
        track.addView(new View(this), new LinearLayout.LayoutParams(0, -1, Math.max(1f - fraction, 0f)));
        item.addView(track, new LinearLayout.LayoutParams(-1, dp(8)));
        gap(item, 5);

        String detail;
        if (share.entry != null) {
            detail = money(share.entry.currency, share.entry.amount) + " · " + share.entry.cycle.label;
            Ledger.Money real = ledger.realCost(share.entry);
            if (share.entry.hasCost() || !real.currency.equals(share.entry.currency)) {
                detail += " · 实际 " + money(real.currency, real.amount);
            }
            if (share.entry.splitWays > 1) {
                detail += " · " + share.entry.splitWays + " 人分摊";
            }
            if (share.entry.mode == Subscription.Mode.MANUAL) {
                detail += " · 手动续费";
            }
        } else {
            detail = share.count + " 项";
        }
        item.addView(label(detail + " · 占 " + percent(share.monthly, stats.monthly), 12, colors.muted, false));
        String description = share.label + "，每月 " + money(stats.base, share.monthly)
                + "，" + detail + "，占 " + percent(share.monthly, stats.monthly);
        if (share.entry == null) {
            item.setContentDescription(description);
            return item;
        }
        LinearLayout withIcon = row();
        withIcon.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams iconParams = new LinearLayout.LayoutParams(dp(36), dp(36));
        iconParams.setMarginEnd(dp(12));
        withIcon.addView(iconView(share.entry, share.entry.title, 36), iconParams);
        withIcon.addView(item, new LinearLayout.LayoutParams(0, -2, 1f));
        withIcon.setContentDescription(description);
        return withIcon;
    }

    private void renderRates() {
        LinearLayout card = panel(colors.card, 24);
        LinearLayout baseRow = row();
        baseRow.setGravity(Gravity.CENTER_VERTICAL);
        baseRow.addView(label("本位币 " + rates.base, 16, colors.ink, true), new LinearLayout.LayoutParams(0, -2, 1f));
        baseRow.addView(action("更换", colors.line, colors.green, this::changeBase),
                new LinearLayout.LayoutParams(dp(66), dp(44)));
        card.addView(baseRow);
        gap(card, 4);
        String status;
        if (rates.fetchedAt == 0) {
            status = "尚未获取在线汇率。获取后，每 12 小时打开应用时自动更新。";
        } else {
            status = "在线汇率更新于 " + stamp(rates.fetchedAt);
            if (rates.asOf > 0) {
                status += "，数据日期 " + stamp(rates.asOf);
            }
        }
        card.addView(label(status, 13, colors.muted, false));
        gap(card, 10);
        card.addView(action("在线更新汇率", colors.hero, colors.onHero, () -> downloadRates(false, this::render)),
                new LinearLayout.LayoutParams(-1, dp(48)));

        TreeSet<String> used = new TreeSet<>();
        // Rates are needed for the currencies costs are actually counted in.
        for (Subscription entry : ledger.subscriptions) {
            used.add(ledger.realCost(entry).currency);
        }
        for (Payment payment : ledger.payments) {
            used.add(payment.costCurrency);
        }
        used.remove(rates.base);
        if (!used.isEmpty()) {
            gap(card, 12);
            card.addView(label("点按币种可手动填写汇率", 12, colors.muted, false));
        }
        for (String code : used) {
            gap(card, 6);
            card.addView(rateRow(code), new LinearLayout.LayoutParams(-1, -2));
        }

        gap(card, 12);
        TextView source = label("", 12, colors.muted, false);
        source.setAutoLinkMask(Linkify.WEB_URLS);
        source.setText("Rates By Exchange Rate API · " + RateStore.SOURCE_URL);
        source.setLinkTextColor(colors.green);
        source.setMovementMethod(LinkMovementMethod.getInstance());
        card.addView(source);
        left.addView(card);
    }

    private View rateRow(String code) {
        LinearLayout line = row();
        line.setGravity(Gravity.CENTER_VERTICAL);
        line.setBackground(ripple(colors.paper, 14));
        line.setPadding(dp(14), dp(10), dp(14), dp(10));
        line.setMinimumHeight(dp(48));
        line.setFocusable(true);
        line.setOnClickListener(view -> editRate(code));
        BigDecimal rate = rates.perUnit(code);
        String text = rate == null ? code + " 缺少汇率" : "1 " + code + " = " + plain(rate) + " " + rates.base;
        line.addView(label(text, 15, colors.ink, true), new LinearLayout.LayoutParams(0, -2, 1f));
        String tag = rate == null ? "填写" : rates.isManual(code) ? "手动" : "在线";
        TextView badge = pill(tag, rate == null ? colors.lime : colors.line, colors.green);
        badge.setTextSize(12);
        badge.setPadding(dp(10), dp(4), dp(10), dp(4));
        line.addView(badge);
        return line;
    }

    private void editRate(String code) {
        BigDecimal online = rates.fetched.get(code);
        BigDecimal manual = rates.manual.get(code);
        Sheet sheet = sheet(code + " 汇率").detents(0.5f, 1f);
        LinearLayout fields = sheet.body;
        EditText field = input(online == null ? "例如 7.2" : plain(online),
                manual == null ? "" : plain(manual),
                InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL, 20);
        addField(fields, "1 " + code + " = ? " + rates.base, field);
        hint(fields, online == null ? "暂无在线汇率。" : "在线汇率 " + plain(online) + "，留空则使用在线汇率。");
        sheet.leading("取消", sheet::cancel);
        sheet.trailing("保存", () -> {
            String value = fieldValue(field).replace(',', '.');
            BigDecimal rate = null;
            if (!value.isEmpty()) {
                try {
                    rate = new BigDecimal(value);
                } catch (NumberFormatException ex) {
                    rate = BigDecimal.ZERO;
                }
                if (!Rates.validRate(rate)) {
                    field.setError("请输入大于 0 的汇率");
                    return false;
                }
            }
            BigDecimal chosen = rate;
            try {
                rateStore().update(current -> current.withManual(code, chosen));
            } catch (IllegalArgumentException | IllegalStateException ex) {
                toast(ex.getMessage());
                return false;
            }
            render();
            return true;
        });
        sheet.show();
    }

    private void changeBase() {
        Sheet sheet = sheet("更换本位币").detents(0.5f, 1f);
        LinearLayout fields = sheet.body;
        EditText field = input("三字母币种代码，例如 CNY、USD", rates.base,
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS, 3);
        addField(fields, "本位币", field);
        hint(fields, "统计金额都会折算成本位币。已有汇率会按新本位币换算。");
        sheet.leading("取消", sheet::cancel);
        sheet.trailing("保存", () -> {
            String code;
            try {
                code = Rates.code(fieldValue(field));
            } catch (IllegalArgumentException ex) {
                field.setError("请输入有效币种，例如 CNY、USD 或 JPY");
                return false;
            }
            Rates updated;
            try {
                updated = rateStore().update(current -> current.withBase(code));
            } catch (IllegalStateException ex) {
                toast(ex.getMessage());
                return false;
            }
            render();
            if (updated.fetchedAt > 0) {
                downloadRates(false, this::render);
            }
            return true;
        });
        sheet.show();
    }

    private static String plain(BigDecimal rate) {
        return rate.round(new MathContext(6, RoundingMode.HALF_UP)).stripTrailingZeros().toPlainString();
    }

    private static String stamp(long millis) {
        return Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).format(STAMP);
    }

    /** Twelve vertical bars, one per month; tapping a bar reports that month. */
    private static final class MonthBars extends View {
        private final Palette colors;
        interface OnPick {
            void pick(Stats.Month month);
        }

        private final List<Stats.Month> months;
        private final OnPick listener;
        private final Paint barPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint axisPaint = new Paint();
        private final Paint labelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint valuePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();
        private final RectF rect = new RectF();
        private final float density;
        private int selected = -1;
        /** 0→1 while the bars grow in. */
        private float growth = 1f;

        MonthBars(Context context, Palette colors, List<Stats.Month> months, OnPick listener, boolean animate) {
            super(context);
            this.colors = colors;
            if (animate) {
                growth = 0f;
                ValueAnimator grow = ValueAnimator.ofFloat(0f, 1f);
                grow.setDuration(700);
                grow.setStartDelay(120);
                grow.setInterpolator(Motion.EMPHASIZED);
                grow.addUpdateListener(animation -> {
                    growth = (float) animation.getAnimatedValue();
                    invalidate();
                });
                grow.start();
            }
            this.months = months;
            this.listener = listener;
            density = context.getResources().getDisplayMetrics().density;
            float scaled = context.getResources().getDisplayMetrics().scaledDensity;
            barPaint.setColor(colors.green);
            axisPaint.setColor(colors.line);
            axisPaint.setStrokeWidth(density);
            labelPaint.setColor(colors.muted);
            labelPaint.setTextSize(11 * scaled);
            labelPaint.setTextAlign(Paint.Align.CENTER);
            valuePaint.setColor(colors.ink);
            valuePaint.setTextSize(11 * scaled);
            valuePaint.setFakeBoldText(true);
            setFocusable(true);
            setClickable(true);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float width = getWidth();
            float bottom = getHeight() - 22 * density;
            float top = 20 * density;
            float slot = width / months.size();
            float barWidth = Math.min(slot * 0.58f, 28 * density);
            float radius = Math.min(6 * density, barWidth / 2);
            BigDecimal max = BigDecimal.ZERO;
            int peak = 0;
            for (int i = 0; i < months.size(); i++) {
                if (months.get(i).total.compareTo(max) > 0) {
                    max = months.get(i).total;
                    peak = i;
                }
            }
            canvas.drawLine(0, bottom, width, bottom, axisPaint);
            for (int i = 0; i < months.size(); i++) {
                Stats.Month month = months.get(i);
                float center = slot * i + slot / 2;
                canvas.drawText(i == 0 ? "本月" : month.month.getMonthValue() + "月", center,
                        getHeight() - 5 * density, labelPaint);
                if (month.total.signum() == 0 || max.signum() == 0) {
                    continue;
                }
                // Later bars start a little later, so the row grows from the start like a wave.
                float stagger = Math.max(0f, Math.min(1f, growth * 1.6f - i * 0.05f));
                float height = month.total.divide(max, MathContext.DECIMAL32).floatValue() * (bottom - top) * stagger;
                if (height <= 0) {
                    continue;
                }
                height = Math.max(height, 2 * density);
                rect.set(center - barWidth / 2, bottom - height, center + barWidth / 2, bottom);
                path.reset();
                // Rounded data end, square at the baseline.
                path.addRoundRect(rect, new float[]{radius, radius, radius, radius, 0, 0, 0, 0},
                        Path.Direction.CW);
                barPaint.setAlpha(selected < 0 || selected == i ? 255 : 110);
                canvas.drawPath(path, barPaint);
                if (i == (selected < 0 ? peak : selected) && growth >= 1f) {
                    String value = month.total.setScale(0, RoundingMode.HALF_UP).toPlainString();
                    float half = valuePaint.measureText(value) / 2;
                    float x = Math.max(half, Math.min(width - half, center));
                    valuePaint.setTextAlign(Paint.Align.CENTER);
                    canvas.drawText(value, x, bottom - height - 6 * density, valuePaint);
                }
            }
        }

        @Override
        public boolean onTouchEvent(MotionEvent event) {
            if (event.getAction() == MotionEvent.ACTION_UP) {
                int index = (int) (event.getX() / (getWidth() / (float) months.size()));
                selected = Math.max(0, Math.min(months.size() - 1, index));
                invalidate();
                performClick();
                listener.pick(months.get(selected));
                return true;
            }
            return event.getAction() == MotionEvent.ACTION_DOWN || super.onTouchEvent(event);
        }

        @Override
        public boolean performClick() {
            return super.performClick();
        }
    }
}
