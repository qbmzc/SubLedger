package com.subledger.mobile;

import android.app.AlertDialog;
import android.graphics.Color;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.AdapterView;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
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
        LinearLayout root = screen("扣款记录",
                action("＋ 补记", colors.hero, Color.WHITE, this::addPayment));

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
            filterRow.addView(label("只看：" + filterName, 14, colors.ink, true),
                    new LinearLayout.LayoutParams(0, -2, 1f));
            filterRow.addView(action("查看全部", colors.line, colors.green, () -> {
                subscriptionFilter = null;
                walletFilter = null;
                render();
            }), new LinearLayout.LayoutParams(-2, dp(40)));
            root.addView(filterRow);
            gap(root, 10);
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
        hero.setBackground(shape(colors.hero, 24));
        hero.setPadding(dp(20), dp(16), dp(20), dp(16));
        hero.addView(label(today.getYear() + " 年已支出", 14, colors.heroText, true));
        gap(hero, 4);
        hero.addView(label(money(rates.base, thisYear), 26, Color.WHITE, true));
        gap(hero, 8);
        String footer = "全部 " + visible.size() + " 笔 · 合计 " + money(rates.base, all) + " · 只计你承担的部分";
        if (unconverted > 0) {
            footer += "\n" + unconverted + " 笔缺少汇率，未计入";
        }
        hero.addView(label(footer, 12, colors.heroText, false));
        root.addView(hero, new LinearLayout.LayoutParams(-1, -2));

        if (visible.isEmpty()) {
            gap(root, 12);
            LinearLayout empty = panel(colors.card, 18);
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
                section(root, current.getYear() + " 年 " + current.getMonthValue() + " 月 · "
                        + money(rates.base, monthTotal(visible, current)));
                group = panel(colors.card, 18);
                root.addView(group);
            } else {
                gap(group, 12);
            }
            group.addView(paymentRow(payment), new LinearLayout.LayoutParams(-1, -2));
        }
        if (count < visible.size()) {
            gap(root, 12);
            root.addView(action("显示更多", colors.line, colors.green, () -> {
                shown += PAGE;
                render();
            }), new LinearLayout.LayoutParams(-1, dp(46)));
        }
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
        container.setBackground(ripple(colors.card, 12));
        container.setFocusable(true);
        container.setOnClickListener(view -> details(payment));
        LinearLayout.LayoutParams iconParams = new LinearLayout.LayoutParams(dp(32), dp(32));
        iconParams.rightMargin = dp(12);
        container.addView(iconView(ledger.subscription(payment.subscriptionId), payment.title, 32), iconParams);
        LinearLayout item = column();
        container.addView(item, new LinearLayout.LayoutParams(0, -2, 1f));
        LinearLayout top = row();
        String day = String.format(Locale.ROOT, "%02d-%02d", payment.date.getMonthValue(), payment.date.getDayOfMonth());
        top.addView(label(day + "  " + payment.title, 15, colors.ink, true), new LinearLayout.LayoutParams(0, -2, 1f));
        BigDecimal mine = payment.mine(rates);
        top.addView(label(mine == null ? money(payment.costCurrency, payment.cost) : money(rates.base, mine),
                15, colors.green, true));
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
        StringBuilder text = new StringBuilder();
        text.append(payment.date).append(payment.auto ? " · 自动记录" : " · 手动记录");
        text.append("\n账单 ").append(money(payment.currency, payment.amount));
        text.append("\n实际成本 ").append(money(payment.costCurrency, payment.cost));
        if (payment.splitWays > 1) {
            text.append("（").append(payment.splitWays).append(" 人分摊）");
        }
        BigDecimal mine = payment.mine(rates);
        if (mine != null) {
            text.append("\n计入支出 ").append(money(rates.base, mine));
        }
        if (payment.walletId != null) {
            Wallet wallet = ledger.wallet(payment.walletId);
            text.append("\n").append(wallet == null ? "从已删除的余额账户扣除" : "从「" + wallet.name + "」扣除");
        }
        LinearLayout body = column();
        body.setPadding(dp(24), dp(8), dp(24), dp(4));
        body.addView(label(text.toString(), 15, colors.ink, false));
        gap(body, 14);
        body.addView(label("收据与截图", 13, colors.muted, true));
        gap(body, 6);
        body.addView(new AttachmentStrip(payment.id), new LinearLayout.LayoutParams(-1, -2));
        new AlertDialog.Builder(this).setTitle(payment.title).setView(body)
                .setPositiveButton("关闭", null)
                .setNeutralButton("删除", (dialog, button) -> confirmDelete(payment))
                .show();
    }

    private void confirmDelete(Payment payment) {
        Wallet wallet = payment.walletId == null ? null : ledger.wallet(payment.walletId);
        AlertDialog.Builder dialog = new AlertDialog.Builder(this).setTitle("删除扣款记录")
                .setMessage(payment.date + " " + payment.title + " " + money(payment.currency, payment.amount)
                        + (attachmentCounts.containsKey(payment.id) ? "\n附件会一并删除。" : ""))
                .setNegativeButton("取消", null);
        if (wallet != null) {
            dialog.setPositiveButton("删除并退回余额", (d, b) -> delete(payment, true))
                    .setNeutralButton("仅删除", (d, b) -> delete(payment, false));
        } else {
            dialog.setPositiveButton("删除", (d, b) -> delete(payment, false));
        }
        dialog.show();
    }

    private void delete(Payment payment, boolean refund) {
        if (commit(() -> ledgerStore().load().removePayment(payment.id, refund))) {
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
        LinearLayout fields = column();
        fields.setPadding(dp(22), dp(8), dp(22), dp(14));
        ScrollView scroll = new ScrollView(this);
        scroll.addView(fields);
        Spinner subscription = options(names, selected);
        addField(fields, "订阅", subscription);
        LocalDate[] date = {LocalDate.now()};
        TextView dateButton = action(date[0].toString(), colors.paper, colors.green, null);
        dateButton.setOnClickListener(view -> chooseDate(date[0], picked -> {
            date[0] = picked;
            dateButton.setText(picked.toString());
        }));
        addField(fields, "扣款日期", dateButton);
        EditText amount = input("账单金额", choices.get(selected).amount.toPlainString(),
                InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL, 20);
        addField(fields, "账单金额", amount);
        TextView billCurrency = label("", 12, colors.muted, false);
        fields.addView(billCurrency);
        LinearLayout costRow = row();
        EditText cost = input("留空则自动计算", "",
                InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL, 20);
        costRow.addView(cost, new LinearLayout.LayoutParams(0, -2, 1f));
        EditText costCode = input("币种", rates.base,
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS, 3);
        LinearLayout.LayoutParams codeParams = new LinearLayout.LayoutParams(dp(84), -2);
        codeParams.leftMargin = dp(8);
        costRow.addView(costCode, codeParams);
        addField(fields, "实际花费（选填）", costRow);
        fields.addView(label("补记不会改动余额账户。留空时按订阅的实际成本或当前汇率计算。", 12, colors.muted, false));
        subscription.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                amount.setText(choices.get(position).amount.toPlainString());
                billCurrency.setText("币种 " + choices.get(position).currency);
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) { }
        });

        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("补记扣款")
                .setView(scroll).setNegativeButton("取消", null).setPositiveButton("保存", null).create();
        dialog.show();
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
            Subscription entry = choices.get(subscription.getSelectedItemPosition());
            BigDecimal bill = parseAmount(fieldValue(amount));
            if (bill == null) {
                amount.setError("请输入有效金额");
                return;
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
                        return;
                    }
                }
                payment = new Payment(UUID.randomUUID().toString(), entry.id, entry.title, date[0],
                        bill, entry.currency, costValue, costCurrency, entry.splitWays, null, false);
            } catch (IllegalArgumentException ex) {
                toast("请检查金额的小数位和币种");
                return;
            }
            if (commit(() -> ledgerStore().load().addPayment(payment))) {
                dialog.dismiss();
                render();
            }
        });
    }
}
