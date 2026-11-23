package com.subledger.mobile;

import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.AdapterView;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/** Recorded charges, newest first, optionally limited to one subscription or wallet. */
public final class HistoryActivity extends LedgerActivity {
    static final String SUBSCRIPTION = "subscription";
    static final String WALLET = "wallet";
    private static final int PAGE = 150;
    private String subscriptionFilter, walletFilter;
    private int shown = PAGE;
    private Ledger ledger;
    private Rates rates;
    private Map<String, Integer> attachmentCounts;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        subscriptionFilter = getIntent().getStringExtra(SUBSCRIPTION);
        walletFilter = getIntent().getStringExtra(WALLET);
    }

    @Override
    protected void onResume() {
        super.onResume();
        render();
    }

    private void render() {
        LocalDate today = LocalDate.now();
        ledger = ledgerStore().load();
        rates = rateStore().load();
        attachmentCounts = ledger.attachmentCounts();
        TextView backfill = action("补记", colors.hero, colors.onHero, this::addPayment);
        backfill.setBackground(ripple(colors.hero, 22));
        backfill.setCompoundDrawablesRelativeWithIntrinsicBounds(new Glyph(Glyph.PLUS, colors.onHero, dp(18)), null, null, null);
        backfill.setCompoundDrawablePadding(dp(4));
        LinearLayout root = screen("扣款记录", backfill, 760);

        List<Payment> visible = new ArrayList<>();
        for (Payment payment : ledger.payments) {
            if ((subscriptionFilter == null || subscriptionFilter.equals(payment.subscriptionId))
                    && (walletFilter == null || walletFilter.equals(payment.walletId))) {
                visible.add(payment);
            }
        }
        String filterName = filterName();
        if (filterName != null) {
            LinearLayout filterRow = row();
            filterRow.setGravity(Gravity.CENTER_VERTICAL);
            filterRow.setBackground(shape(colors.lime, 18));
            filterRow.setPadding(dp(16), dp(6), dp(6), dp(6));
            filterRow.addView(label("只看：" + filterName, 14, colors.ink, true),
                    new LinearLayout.LayoutParams(0, -2, 1f));
            TextView all = action("查看全部", colors.card, colors.green, () -> {
                subscriptionFilter = null;
                walletFilter = null;
                render();
            });
            all.setPadding(dp(14), 0, dp(14), 0);
            filterRow.addView(all, new LinearLayout.LayoutParams(-2, dp(40)));
            root.addView(filterRow);
            gap(root, 12);
        }

        BigDecimal thisYear = BigDecimal.ZERO;
        BigDecimal all = BigDecimal.ZERO;
        int unconverted = 0;
        for (Payment payment : visible) {
            BigDecimal mine = payment.mine(rates);
            if (mine == null) {
                unconverted++;
                continue;
            }
            all = all.add(mine);
            if (payment.date.getYear() == today.getYear()) {
                thisYear = thisYear.add(mine);
            }
        }
        LinearLayout hero = column();
        hero.setBackground(shape(colors.hero, 28));
        hero.setPadding(dp(22), dp(18), dp(22), dp(18));
        hero.addView(label(today.getYear() + " 年已支出", 14, colors.heroText, true));
        gap(hero, 4);
        hero.addView(amount(money(rates.base, thisYear), 30, colors.onHero));
        gap(hero, 8);
        String footer = "全部 " + visible.size() + " 笔 · 合计 " + money(rates.base, all) + " · 只计你承担的部分";
        if (unconverted > 0) {
            footer += "\n" + unconverted + " 笔缺少汇率，未计入";
        }
        hero.addView(label(footer, 12, colors.heroText, false));
        root.addView(hero, new LinearLayout.LayoutParams(-1, -2));

        if (visible.isEmpty()) {
            gap(root, 12);
            LinearLayout empty = panel(colors.card, 24);
            empty.addView(label("还没有扣款记录", 16, colors.ink, true));
            gap(empty, 4);
            empty.addView(label("自动续费到期时会自动记录；手动续费点「记录已续费」时记录。"
                    + "更早的扣款可以点「补记」。", 13, colors.muted, false));
            root.addView(empty);
            return;
        }

        YearMonth month = null;
        LinearLayout group = null;
        int count = 0;
        for (int i = visible.size() - 1; i >= 0 && count < shown; i--, count++) {
            Payment payment = visible.get(i);
            YearMonth current = YearMonth.from(payment.date);
            if (!current.equals(month)) {
                month = current;
                monthHeader(root, current, current.getYear() + " 年 " + current.getMonthValue() + " 月 · "
                        + money(rates.base, monthTotal(visible, current)));
                group = group();
                root.addView(group);
            }
            addRow(group, paymentRow(payment));
        }
        if (count < visible.size()) {
            gap(root, 12);
            root.addView(action("显示更多", colors.line, colors.green, () -> {
                shown += PAGE;
                render();
            }), new LinearLayout.LayoutParams(-1, dp(46)));
        }
    }

    /** Month title with an export button for that month's statement and invoices. */
    private void monthHeader(LinearLayout root, YearMonth month, String title) {
        gap(root, 22);
        LinearLayout header = row();
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.addView(heading(label(title, 17, colors.ink, true)), new LinearLayout.LayoutParams(0, -2, 1f));
        TextView export = action("导出", colors.card, colors.green, () -> exportMonth(month));
        export.setBackground(ripple(colors.card, 20));
        export.setContentDescription("导出 " + month.getYear() + " 年 " + month.getMonthValue() + " 月账单与发票");
        header.addView(export, new LinearLayout.LayoutParams(dp(68), dp(38)));
        root.addView(header);
        gap(root, 8);
    }

    private String filterName() {
        if (subscriptionFilter != null) {
            Subscription entry = ledger.subscription(subscriptionFilter);
            if (entry != null) {
                return entry.title;
            }
            for (Payment payment : ledger.payments) {
                if (payment.subscriptionId.equals(subscriptionFilter)) {
                    return payment.title;
                }
            }
            return "已删除的订阅";
        }
        if (walletFilter != null) {
            Wallet wallet = ledger.wallet(walletFilter);
            return wallet == null ? "已删除的余额账户" : wallet.name;
        }
        return null;
    }

    private BigDecimal monthTotal(List<Payment> payments, YearMonth month) {
        BigDecimal total = BigDecimal.ZERO;
        for (Payment payment : payments) {
            BigDecimal mine = YearMonth.from(payment.date).equals(month) ? payment.mine(rates) : null;
            if (mine != null) {
                total = total.add(mine);
            }
        }
        return total;
    }

    private View paymentRow(Payment payment) {
        LinearLayout container = row();
        container.setGravity(Gravity.CENTER_VERTICAL);
        container.setPadding(dp(16), dp(12), dp(16), dp(12));
        container.setBackground(ripple(android.graphics.Color.TRANSPARENT, 0));
        container.setFocusable(true);
        container.setOnClickListener(view -> details(payment));
        LinearLayout.LayoutParams iconParams = new LinearLayout.LayoutParams(dp(38), dp(38));
        iconParams.setMarginEnd(dp(12));
        container.addView(iconView(ledger.subscription(payment.subscriptionId), payment.title, 38), iconParams);
        LinearLayout item = column();
        container.addView(item, new LinearLayout.LayoutParams(0, -2, 1f));
        LinearLayout top = row();
        String day = String.format(Locale.ROOT, "%02d-%02d", payment.date.getMonthValue(), payment.date.getDayOfMonth());
        top.addView(label(day + "  " + payment.title, 15, colors.ink, true), new LinearLayout.LayoutParams(0, -2, 1f));
        BigDecimal mine = payment.mine(rates);
        top.addView(amount(mine == null ? money(payment.costCurrency, payment.cost) : money(rates.base, mine),
                15, colors.green));
        item.addView(top);
        List<String> parts = new ArrayList<>();
        parts.add("账单 " + money(payment.currency, payment.amount));
        if (!payment.costCurrency.equals(payment.currency) || payment.cost.compareTo(payment.amount) != 0) {
            parts.add("实际 " + money(payment.costCurrency, payment.cost));
        }
        if (payment.splitWays > 1) {
            parts.add(payment.splitWays + " 人分摊");
        }
        if (payment.walletId != null) {
            Wallet wallet = ledger.wallet(payment.walletId);
            parts.add(wallet == null ? "余额扣款" : "从「" + wallet.name + "」扣除");
        }
        parts.add(payment.auto ? "自动记录" : "手动记录");
        Integer files = attachmentCounts.get(payment.id);
        if (files != null) {
            parts.add(files + " 个附件");
        }
        item.addView(label(String.join(" · ", parts), 12, colors.muted, false));
        return container;
    }

    @Override
    void onLedgerChanged() {
        render();
    }

    /** Payment details with its receipts; deleting is offered from here. */
    private void details(Payment payment) {
        Sheet sheet = sheet(payment.title).detents(0.6f, 1f);
        LinearLayout body = sheet.body;
        LinearLayout top = row();
        top.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams iconParams = new LinearLayout.LayoutParams(dp(52), dp(52));
        iconParams.setMarginEnd(dp(14));
        top.addView(iconView(ledger.subscription(payment.subscriptionId), payment.title, 52), iconParams);
        LinearLayout titles = column();
        titles.addView(amount(money(payment.currency, payment.amount), 24, colors.ink));
        titles.addView(label(payment.date + (payment.auto ? " · 自动记录" : " · 手动记录"), 13, colors.muted, false));
        top.addView(titles, new LinearLayout.LayoutParams(0, -2, 1f));
        body.addView(top);
        gap(body, 14);
        LinearLayout facts = group();
        String cost = money(payment.costCurrency, payment.cost);
        if (payment.splitWays > 1) {
            cost += "（" + payment.splitWays + " 人分摊）";
        }
        addRow(facts, factRow("实际成本", cost, colors.ink));
        BigDecimal mine = payment.mine(rates);
        if (mine != null) {
            addRow(facts, factRow("计入支出", money(rates.base, mine), colors.green));
        }
        if (payment.walletId != null) {
            Wallet wallet = ledger.wallet(payment.walletId);
            addRow(facts, factRow("扣款账户", wallet == null ? "已删除的余额账户" : wallet.name, colors.ink));
        }
        body.addView(facts);
        section(body, "收据与截图");
        body.addView(new AttachmentStrip(payment.id), new LinearLayout.LayoutParams(-1, -2));
        gap(body, 24);
        body.addView(action("删除扣款记录", colors.card, colors.amber, () -> confirmDelete(payment, sheet)),
                new LinearLayout.LayoutParams(-1, dp(50)));
        sheet.show();
    }

    private void confirmDelete(Payment payment, Sheet details) {
        Wallet wallet = payment.walletId == null ? null : ledger.wallet(payment.walletId);
        android.app.AlertDialog.Builder dialog = alert().setTitle("删除扣款记录")
                .setMessage(payment.date + " " + payment.title + " " + money(payment.currency, payment.amount)
                        + (attachmentCounts.containsKey(payment.id) ? "\n附件会一并删除。" : ""))
                .setNegativeButton("取消", null);
        if (wallet != null) {
            dialog.setPositiveButton("删除并退回余额", (d, b) -> delete(payment, true, details))
                    .setNeutralButton("仅删除", (d, b) -> delete(payment, false, details));
        } else {
            dialog.setPositiveButton("删除", (d, b) -> delete(payment, false, details));
        }
        dialog.show();
    }

    private void delete(Payment payment, boolean refund, Sheet details) {
        if (commit(() -> ledgerStore().load().removePayment(payment.id, refund))) {
            details.dismiss();
            render();
        }
    }

    /** Backfills a charge by hand. It does not change any wallet balance. */
    private void addPayment() {
        List<Subscription> choices = new ArrayList<>(ledger.subscriptions);
        if (choices.isEmpty()) {
            toast("请先添加订阅");
            return;
        }
        int selected = 0;
        String[] names = new String[choices.size()];
        for (int i = 0; i < choices.size(); i++) {
            names[i] = choices.get(i).title;
            if (choices.get(i).id.equals(subscriptionFilter)) {
                selected = i;
            }
        }
        Sheet sheet = sheet("补记扣款").detents(0.62f, 1f).expanded();
        LinearLayout fields = sheet.body;
        Spinner subscription = options(names, selected);
        addField(fields, "订阅", subscription);
        LocalDate[] date = {LocalDate.now()};
        TextView dateButton = pickerField(date[0].toString());
        dateButton.setOnClickListener(view -> chooseDate(date[0], picked -> {
            date[0] = picked;
            dateButton.setText(picked.toString());
        }));
        addField(fields, "扣款日期", dateButton);
        EditText amount = input("账单金额", choices.get(selected).amount.toPlainString(),
                InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL, 20);
        addField(fields, "账单金额", amount);
        TextView billCurrency = label("", 12, colors.muted, false);
        billCurrency.setPadding(dp(4), dp(4), dp(4), 0);
        fields.addView(billCurrency);
        LinearLayout costRow = row();
        EditText cost = input("留空则自动计算", "",
                InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL, 20);
        costRow.addView(cost, new LinearLayout.LayoutParams(0, -2, 1f));
        EditText costCode = input("币种", rates.base,
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS, 3);
        LinearLayout.LayoutParams codeParams = new LinearLayout.LayoutParams(dp(96), -2);
        codeParams.setMarginStart(dp(8));
        costRow.addView(costCode, codeParams);
        addField(fields, "实际花费（选填）", costRow);
        hint(fields, "补记不会改动余额账户。留空时按订阅的实际成本或当前汇率计算。");
        subscription.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                amount.setText(choices.get(position).amount.toPlainString());
                billCurrency.setText("币种 " + choices.get(position).currency);
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) { }
        });

        sheet.leading("取消", sheet::cancel);
        sheet.trailing("保存", () -> {
            Subscription entry = choices.get(subscription.getSelectedItemPosition());
            BigDecimal bill = parseAmount(fieldValue(amount));
            if (bill == null) {
                amount.setError("请输入有效金额");
                return false;
            }
            Payment payment;
            try {
                BigDecimal costValue;
                String costCurrency;
                if (fieldValue(cost).isEmpty()) {
                    Subscription.Builder priced = entry.toBuilder();
                    priced.amount = bill;
                    Ledger.Money real = ledger.costForManualPayment(priced.build(), rates);
                    costValue = real.amount;
                    costCurrency = real.currency;
                } else {
                    costValue = parseAmount(fieldValue(cost));
                    costCurrency = fieldValue(costCode).toUpperCase(Locale.ROOT);
                    if (costValue == null) {
                        cost.setError("请输入有效金额，或留空");
                        return false;
                    }
                }
                payment = new Payment(UUID.randomUUID().toString(), entry.id, entry.title, date[0],
                        bill, entry.currency, costValue, costCurrency, entry.splitWays, null, false);
            } catch (IllegalArgumentException ex) {
                toast("请检查金额的小数位和币种");
                return false;
            }
            if (!commit(() -> ledgerStore().load().addPayment(payment))) {
                return false;
            }
            render();
            return true;
        });
        sheet.show();
    }
}
