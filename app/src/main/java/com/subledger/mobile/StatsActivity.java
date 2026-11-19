package com.subledger.mobile;

import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.drawable.GradientDrawable;
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
    private LinearLayout root;
    private Ledger ledger;
    private Rates rates;
    private Stats stats;
    private LocalDate today;
    private boolean allItems, allPayments;

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
        root = screen("支出统计", null);

        if (!stats.missing.isEmpty()) {
            renderMissingRates();
            gap(root, 14);
        }
        if (stats.active == 0) {
            LinearLayout empty = panel(colors.card, 18);
            empty.addView(label("还没有进行中的订阅", 17, colors.ink, true));
            gap(empty, 4);
            empty.addView(label("添加订阅后，这里会显示月度、年度支出和未来扣款。", 14, colors.muted, false));
            root.addView(empty);
        } else if (stats.counted > 0) {
            renderTotals();
            section("未来 12 个月扣款");
            renderMonths();
            section("按订阅");
            renderShares(stats.items, allItems, () -> {
                allItems = true;
                render();
            });
            section("按分类");
            renderShares(stats.byCategory, true, null);
            section("按付款渠道");
            renderShares(stats.byPayment, allPayments, () -> {
                allPayments = true;
                render();
            });
            section("按账期");
            renderShares(stats.byCycle, true, null);
        }
        section("汇率");
        renderRates();
        gap(root, 16);
        root.addView(label("预计支出只计进行中的订阅和你承担的部分。月均按账期折算：周付按每年 52 周，"
                + "季付除以 3，年付除以 12；年度为月均 × 12。未来扣款按续费日期逐笔推算，手动续费按账期估计。"
                + "每笔的成本依次取：填写的实际成本、余额账户的实际成本、按上方当前汇率折算。"
                + "已支出来自扣款记录，成本在记录时就已固定，不随汇率变化。", 12, colors.muted, false));
    }

    private void section(String title) {
        section(root, title);
    }

    private void renderMissingRates() {
        LinearLayout notice = panel(colors.lime, 18);
        int skipped = stats.active - stats.counted;
        notice.addView(label("缺少 " + String.join("、", stats.missing) + " 汇率", 16, colors.ink, true));
        gap(notice, 4);
        notice.addView(label(skipped + " 项订阅暂未计入统计。可在线获取汇率，或手动填写。", 14, colors.ink, false));
        gap(notice, 10);
        LinearLayout buttons = row();
        buttons.addView(action("在线获取汇率", colors.hero, Color.WHITE, () -> downloadRates(false, this::render)),
                new LinearLayout.LayoutParams(0, dp(46), 1f));
        LinearLayout.LayoutParams manualParams = new LinearLayout.LayoutParams(0, dp(46), 1f);
        manualParams.leftMargin = dp(8);
        buttons.addView(action("手动填写", colors.card, colors.green, () -> editRate(stats.missing.first())),
                manualParams);
        notice.addView(buttons);
        root.addView(notice);
    }

    private void renderTotals() {
        LinearLayout hero = column();
        hero.setBackground(shape(colors.hero, 24));
        hero.setPadding(dp(20), dp(18), dp(20), dp(18));
        hero.addView(label("每月预计支出", 15, colors.heroText, true));
        gap(hero, 6);
        hero.addView(label(money(stats.base, stats.monthly), 30, Color.WHITE, true));
        gap(hero, 14);
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
        hero.setBackground(ripple(colors.hero, 24));
        hero.setFocusable(true);
        hero.setOnClickListener(view -> startActivity(new android.content.Intent(this, HistoryActivity.class)));
        root.addView(hero, new LinearLayout.LayoutParams(-1, -2));
    }

    private View tile(String title, String value) {
        LinearLayout tile = column();
        tile.addView(label(title, 13, colors.heroText, false));
        gap(tile, 2);
        tile.addView(label(value, 17, Color.WHITE, true));
        return tile;
    }

    private void renderMonths() {
        LinearLayout card = panel(colors.card, 18);
        Stats.Month peak = stats.months.get(0);
        for (Stats.Month month : stats.months) {
            if (month.total.compareTo(peak.total) > 0) {
                peak = month;
            }
        }
        if (peak.total.signum() == 0) {
            card.addView(label("未来 12 个月没有预计扣款", 14, colors.muted, false));
            root.addView(card);
            return;
        }
        card.addView(label("最高 " + monthName(peak) + "：" + money(stats.base, peak.total), 14, colors.ink, true));
        gap(card, 10);
        MonthBars chart = new MonthBars(this, colors, stats.months, this::showMonth);
        StringBuilder description = new StringBuilder("未来 12 个月扣款柱状图。");
        for (Stats.Month month : stats.months) {
            description.append(monthName(month)).append(' ')
                    .append(money(stats.base, month.total)).append("；");
        }
        chart.setContentDescription(description);
        card.addView(chart, new LinearLayout.LayoutParams(-1, dp(190)));
        gap(card, 8);
        card.addView(label("点按柱形查看当月明细。本月只计今天及以后的扣款。", 12, colors.muted, false));
        root.addView(card);
    }

    private String monthName(Stats.Month month) {
        return month.month.getYear() + " 年 " + month.month.getMonthValue() + " 月";
    }

    private void showMonth(Stats.Month month) {
        StringBuilder lines = new StringBuilder();
        for (Stats.Charge charge : month.charges) {
            lines.append(charge.date.getMonthValue()).append('-')
                    .append(String.format(Locale.ROOT, "%02d", charge.date.getDayOfMonth()))
                    .append("  ").append(charge.entry.title).append("  ")
                    .append(money(stats.base, charge.amount));
            if (!charge.entry.currency.equals(stats.base) || charge.entry.splitWays > 1
                    || !ledger.realCost(charge.entry).currency.equals(charge.entry.currency)) {
                lines.append("（账单 ").append(money(charge.entry.currency, charge.entry.amount));
                if (charge.entry.splitWays > 1) {
                    lines.append("，").append(charge.entry.splitWays).append(" 人分摊");
                }
                lines.append("）");
            }
            if (charge.entry.trial && charge.date.equals(charge.entry.anchor)) {
                lines.append(" · 试用结束");
            }
            if (charge.entry.mode == Subscription.Mode.MANUAL) {
                lines.append(" · 手动");
            }
            lines.append('\n');
        }
        new AlertDialog.Builder(this)
                .setTitle(monthName(month) + " · " + money(stats.base, month.total))
                .setMessage(month.charges.isEmpty() ? "这个月没有预计扣款" : lines.toString().trim())
                .setPositiveButton("关闭", null).show();
    }

    private void renderShares(List<Stats.Share> shares, boolean showAll, Runnable expand) {
        LinearLayout card = panel(colors.card, 18);
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
        root.addView(card);
    }

    private View shareRow(Stats.Share share, BigDecimal max) {
        LinearLayout item = column();
        LinearLayout top = row();
        TextView name = label(share.label, 15, colors.ink, true);
        name.setSingleLine(true);
        name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams nameParams = new LinearLayout.LayoutParams(0, -2, 1f);
        nameParams.rightMargin = dp(10);
        top.addView(name, nameParams);
        top.addView(label(money(stats.base, share.monthly) + " / 月", 14, colors.ink, true));
        item.addView(top);
        gap(item, 6);

        // Bar length is relative to the largest row; the share of the total is printed below.
        LinearLayout track = row();
        track.setBackground(shape(colors.line, 3));
        float fraction = max.signum() == 0 ? 0f
                : share.monthly.divide(max, MathContext.DECIMAL32).floatValue();
        View bar = new View(this);
        GradientDrawable fill = new GradientDrawable();
        fill.setColor(colors.green);
        float radius = dp(3);
        fill.setCornerRadii(new float[]{0, 0, radius, radius, radius, radius, 0, 0});
        bar.setBackground(fill);
        track.addView(bar, new LinearLayout.LayoutParams(0, -1, Math.max(fraction, 0.01f)));
        track.addView(new View(this), new LinearLayout.LayoutParams(0, -1, Math.max(1f - fraction, 0f)));
        item.addView(track, new LinearLayout.LayoutParams(-1, dp(6)));
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
        item.setContentDescription(share.label + "，每月 " + money(stats.base, share.monthly)
                + "，" + detail + "，占 " + percent(share.monthly, stats.monthly));
        return item;
    }

    private void renderRates() {
        LinearLayout card = panel(colors.card, 18);
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
        card.addView(action("在线更新汇率", colors.hero, Color.WHITE, () -> downloadRates(false, this::render)),
                new LinearLayout.LayoutParams(-1, dp(46)));

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
        root.addView(card);
    }

    private View rateRow(String code) {
        LinearLayout line = row();
        line.setGravity(Gravity.CENTER_VERTICAL);
        line.setBackground(ripple(colors.paper, 12));
        line.setPadding(dp(12), dp(10), dp(12), dp(10));
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
        LinearLayout fields = column();
        fields.setPadding(dp(22), dp(8), dp(22), dp(4));
        EditText field = input(online == null ? "例如 7.2" : plain(online),
                manual == null ? "" : plain(manual),
                InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL, 20);
        addField(fields, "1 " + code + " = ? " + rates.base, field);
        gap(fields, 6);
        fields.addView(label(online == null ? "暂无在线汇率。" : "在线汇率 " + plain(online) + "，留空则使用在线汇率。",
                12, colors.muted, false));
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle(code + " 汇率")
                .setView(fields).setNegativeButton("取消", null).setPositiveButton("保存", null).create();
        dialog.show();
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
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
                    return;
                }
            }
            BigDecimal chosen = rate;
            try {
                rateStore().update(current -> current.withManual(code, chosen));
            } catch (IllegalArgumentException | IllegalStateException ex) {
                toast(ex.getMessage());
                return;
            }
            dialog.dismiss();
            render();
        });
    }

    private void changeBase() {
        LinearLayout fields = column();
        fields.setPadding(dp(22), dp(8), dp(22), dp(4));
        EditText field = input("三字母币种代码，例如 CNY、USD", rates.base,
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS, 3);
        addField(fields, "本位币", field);
        gap(fields, 6);
        fields.addView(label("统计金额都会折算成本位币。已有汇率会按新本位币换算。", 12, colors.muted, false));
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("更换本位币")
                .setView(fields).setNegativeButton("取消", null).setPositiveButton("保存", null).create();
        dialog.show();
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
            String code;
            try {
                code = Rates.code(fieldValue(field));
            } catch (IllegalArgumentException ex) {
                field.setError("请输入有效币种，例如 CNY、USD 或 JPY");
                return;
            }
            Rates updated;
            try {
                updated = rateStore().update(current -> current.withBase(code));
            } catch (IllegalStateException ex) {
                toast(ex.getMessage());
                return;
            }
            dialog.dismiss();
            render();
            if (updated.fetchedAt > 0) {
                downloadRates(false, this::render);
            }
        });
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

        MonthBars(Context context, Palette colors, List<Stats.Month> months, OnPick listener) {
            super(context);
            this.colors = colors;
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
            float barWidth = Math.min(slot * 0.56f, 26 * density);
            float radius = Math.min(4 * density, barWidth / 2);
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
                float height = month.total.divide(max, MathContext.DECIMAL32).floatValue() * (bottom - top);
                height = Math.max(height, 2 * density);
                rect.set(center - barWidth / 2, bottom - height, center + barWidth / 2, bottom);
                path.reset();
                // Rounded data end, square at the baseline.
                path.addRoundRect(rect, new float[]{radius, radius, radius, radius, 0, 0, 0, 0},
                        Path.Direction.CW);
                barPaint.setAlpha(selected < 0 || selected == i ? 255 : 110);
                canvas.drawPath(path, barPaint);
                if (i == (selected < 0 ? peak : selected)) {
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
