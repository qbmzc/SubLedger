package com.subledger.mobile;

import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Color;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/** Balance accounts: prepaid balances that subscriptions are paid from. */
public final class WalletsActivity extends LedgerActivity {
    private Ledger ledger;
    private LocalDate today;

    @Override
    protected void onResume() {
        super.onResume();
        render();
    }

    private void render() {
        today = LocalDate.now();
        ledger = ledgerStore().load();
        LinearLayout root = screen("余额账户",
                action("＋ 新建", colors.hero, Color.WHITE, () -> editWallet(null)));
        LinearLayout intro = panel(colors.card, 18);
        intro.addView(label("把礼品卡、预付余额记成账户。订阅选择从账户扣款后，续费时自动扣除，"
                + "余额不够下次扣款时会提醒。充值时填写实际花费，就能算出真实成本。", 13, colors.muted, false));
        root.addView(intro);
        for (Wallet wallet : ledger.wallets) {
            gap(root, 12);
            root.addView(walletCard(wallet), new LinearLayout.LayoutParams(-1, -2));
        }
    }

    private View walletCard(Wallet wallet) {
        Ledger.Coverage cover = ledger.coverage(wallet, today);
        LinearLayout card = panel(colors.card, 18);
        LinearLayout top = row();
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.addView(label(wallet.name, 17, colors.ink, true), new LinearLayout.LayoutParams(0, -2, 1f));
        top.addView(label(money(wallet.currency, wallet.balance), 17,
                wallet.balance.signum() < 0 ? colors.amber : colors.green, true));
        card.addView(top);
        gap(card, 4);
        card.addView(label(wallet.unitCost == null ? "未记录实际成本（充值时可填写）"
                : "每 1 " + wallet.currency + " 实际成本约 " + wallet.costCurrency + " "
                + wallet.unitCost.round(new MathContext(4, RoundingMode.HALF_UP)).stripTrailingZeros().toPlainString(),
                13, colors.muted, false));
        gap(card, 6);
        String status;
        int statusColor = colors.green;
        if (cover.linked == 0) {
            status = "还没有订阅从这个账户扣款";
            statusColor = colors.muted;
        } else if (cover.next == null) {
            status = "暂无待扣款";
        } else if (cover.insufficient()) {
            status = "余额不够 " + cover.next + " 的 " + money(wallet.currency, cover.nextAmount) + " 扣款";
            statusColor = colors.amber;
        } else if (cover.beyondHorizon) {
            status = "足够支付未来 " + Ledger.COVERAGE_YEARS + " 年的扣款";
        } else {
            status = "可支付 " + cover.payable + " 次，用到 " + cover.paidUntil;
        }
        card.addView(label(status, 14, statusColor, true));

        List<String> names = new ArrayList<>();
        for (Subscription entry : ledger.subscriptions) {
            if (wallet.id.equals(entry.walletId)) {
                names.add(entry.title + (entry.mode == Subscription.Mode.ENDED ? "（已停止）" : ""));
            }
        }
        if (!names.isEmpty()) {
            gap(card, 4);
            card.addView(label("扣款订阅：" + String.join("、", names), 13, colors.muted, false));
        }
        List<Payment> deductions = new ArrayList<>();
        for (Payment payment : ledger.payments) {
            if (wallet.id.equals(payment.walletId)) {
                deductions.add(payment);
            }
        }
        if (!deductions.isEmpty()) {
            gap(card, 8);
            card.addView(label("最近扣除", 13, colors.ink, true));
            for (int i = deductions.size() - 1; i >= Math.max(0, deductions.size() - 3); i--) {
                Payment payment = deductions.get(i);
                card.addView(label(payment.date + "  " + payment.title + "  −"
                        + money(payment.currency, payment.amount), 13, colors.muted, false));
            }
        }

        List<TopUp> topUps = ledger.topUpsOf(wallet.id);
        if (!topUps.isEmpty()) {
            gap(card, 8);
            card.addView(label("充值记录 · 点按可上传发票", 13, colors.ink, true));
            for (int i = topUps.size() - 1; i >= Math.max(0, topUps.size() - 3); i--) {
                card.addView(topUpRow(topUps.get(i)), new LinearLayout.LayoutParams(-1, -2));
            }
            if (topUps.size() > 3) {
                card.addView(action("全部 " + topUps.size() + " 次充值", colors.card, colors.green,
                        () -> allTopUps(wallet)), new LinearLayout.LayoutParams(-2, dp(40)));
            }
        }

        gap(card, 12);
        LinearLayout buttons = row();
        buttons.addView(action("充值", colors.hero, Color.WHITE, () -> topUp(wallet)),
                new LinearLayout.LayoutParams(0, dp(44), 1f));
        LinearLayout.LayoutParams editParams = new LinearLayout.LayoutParams(0, dp(44), 1f);
        editParams.leftMargin = dp(8);
        buttons.addView(action("编辑", colors.line, colors.green, () -> editWallet(wallet)), editParams);
        if (!deductions.isEmpty()) {
            LinearLayout.LayoutParams historyParams = new LinearLayout.LayoutParams(0, dp(44), 1f);
            historyParams.leftMargin = dp(8);
            buttons.addView(action("扣款记录", colors.line, colors.green, () -> startActivity(
                    new Intent(this, HistoryActivity.class).putExtra(HistoryActivity.WALLET, wallet.id))),
                    historyParams);
        }
        card.addView(buttons);
        return card;
    }

    private View topUpRow(TopUp topUp) {
        TextView row = label(topUpLine(topUp), 13, colors.muted, false);
        row.setBackground(ripple(colors.card, 8));
        row.setPadding(0, dp(6), 0, dp(6));
        row.setFocusable(true);
        row.setOnClickListener(view -> topUpDetails(topUp));
        return row;
    }

    private String topUpLine(TopUp topUp) {
        String line = topUp.date + "  +" + money(topUp.currency, topUp.amount);
        if (topUp.paid != null) {
            line += "  实付 " + money(topUp.paidCurrency, topUp.paid);
        }
        Integer files = ledger.attachmentCounts().get(topUp.id);
        return files == null ? line : line + "  · " + files + " 个附件";
    }

    private void allTopUps(Wallet wallet) {
        List<TopUp> topUps = ledger.topUpsOf(wallet.id);
        String[] lines = new String[topUps.size()];
        for (int i = 0; i < topUps.size(); i++) {
            lines[i] = topUpLine(topUps.get(topUps.size() - 1 - i));
        }
        new AlertDialog.Builder(this).setTitle(wallet.name + " · 充值记录")
                .setItems(lines, (dialog, which) -> topUpDetails(topUps.get(topUps.size() - 1 - which)))
                .setNegativeButton("关闭", null).show();
    }

    /** A top-up with its invoices; deleting takes the amount back off the balance. */
    private void topUpDetails(TopUp topUp) {
        StringBuilder text = new StringBuilder(topUp.date.toString())
                .append("\n充值 ").append(money(topUp.currency, topUp.amount));
        if (topUp.paid != null) {
            text.append("\n实付 ").append(money(topUp.paidCurrency, topUp.paid));
        }
        LinearLayout body = column();
        body.setPadding(dp(24), dp(8), dp(24), dp(4));
        body.addView(label(text.toString(), 15, colors.ink, false));
        gap(body, 14);
        body.addView(label("发票与截图", 13, colors.muted, true));
        gap(body, 6);
        body.addView(new AttachmentStrip(topUp.id), new LinearLayout.LayoutParams(-1, -2));
        new AlertDialog.Builder(this).setTitle("充值记录").setView(body)
                .setPositiveButton("关闭", null)
                .setNeutralButton("删除", (dialog, button) -> confirmDeleteTopUp(topUp))
                .show();
    }

    private void confirmDeleteTopUp(TopUp topUp) {
        new AlertDialog.Builder(this).setTitle("删除充值记录")
                .setMessage("余额将减少 " + money(topUp.currency, topUp.amount)
                        + "，附件会一并删除。账户的单位成本保持不变，如需调整请编辑账户。")
                .setNegativeButton("取消", null)
                .setPositiveButton("删除", (dialog, button) -> {
                    if (commit(() -> ledgerStore().load().removeTopUp(topUp.id))) {
                        render();
                    }
                }).show();
    }

    @Override
    void onLedgerChanged() {
        render();
    }

    private void editWallet(Wallet wallet) {
        LinearLayout fields = column();
        fields.setPadding(dp(22), dp(8), dp(22), dp(14));
        ScrollView scroll = new ScrollView(this);
        scroll.addView(fields);
        EditText name = input("例如 美区 Apple ID", wallet == null ? "" : wallet.name,
                InputType.TYPE_CLASS_TEXT, 40);
        addField(fields, "账户名称", name);
        EditText currency = input("三字母币种代码，例如 USD", wallet == null ? "USD" : wallet.currency,
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS, 3);
        currency.setEnabled(wallet == null);
        addField(fields, wallet == null ? "币种" : "币种（创建后不可更改）", currency);
        EditText balance = input("当前余额", wallet == null ? "" : wallet.balance.toPlainString(),
                InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL
                        | InputType.TYPE_NUMBER_FLAG_SIGNED, 20);
        addField(fields, wallet == null ? "当前余额" : "余额（直接调整）", balance);

        String base = rateStore().load().base;
        LinearLayout costRow = row();
        EditText cost = input(wallet == null ? "例如 650" : "留空表示未知",
                wallet == null || wallet.unitCost == null ? "" : wallet.unitCost.toPlainString(),
                InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL, 20);
        costRow.addView(cost, new LinearLayout.LayoutParams(0, -2, 1f));
        EditText costCode = input("币种", wallet == null || wallet.costCurrency == null ? base : wallet.costCurrency,
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS, 3);
        LinearLayout.LayoutParams codeParams = new LinearLayout.LayoutParams(dp(84), -2);
        codeParams.leftMargin = dp(8);
        costRow.addView(costCode, codeParams);
        addField(fields, wallet == null ? "购买这些余额实际花了（选填）" : "每 1 单位余额的实际成本（选填）", costRow);
        fields.addView(label(wallet == null
                ? "例如花 CNY 650 买了 USD 100 礼品卡，就填 650 和 CNY。"
                : "例如 1 USD 实际花了 6.5 元，就填 6.5 和 CNY。", 12, colors.muted, false));

        AlertDialog.Builder builder = new AlertDialog.Builder(this)
                .setTitle(wallet == null ? "新建余额账户" : "编辑余额账户")
                .setView(scroll).setNegativeButton("取消", null).setPositiveButton("保存", null);
        if (wallet != null) {
            builder.setNeutralButton("删除", (dialog, button) -> confirmDelete(wallet));
        }
        AlertDialog dialog = builder.create();
        dialog.show();
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
            if (fieldValue(name).isEmpty()) {
                name.setError("请填写账户名称");
                return;
            }
            String text = fieldValue(balance).replace(',', '.');
            BigDecimal amount = parseAmount(text.startsWith("-") ? text.substring(1) : text);
            if (amount == null) {
                balance.setError("请输入有效金额");
                return;
            }
            if (text.startsWith("-")) {
                amount = amount.negate();
            }
            BigDecimal costValue = null;
            String costCurrency = null;
            if (!fieldValue(cost).isEmpty()) {
                costValue = parseAmount(fieldValue(cost));
                if (costValue == null || costValue.signum() == 0) {
                    cost.setError("请输入大于 0 的金额，或留空");
                    return;
                }
                costCurrency = fieldValue(costCode).toUpperCase(Locale.ROOT);
                if (wallet == null && amount.signum() <= 0) {
                    balance.setError("填写实际花费时，余额需大于 0");
                    return;
                }
            }
            // A new account's starting balance is recorded as its first top-up, so it can hold an invoice.
            boolean initialTopUp = wallet == null && amount.signum() > 0;
            Wallet updated;
            TopUp first;
            try {
                updated = new Wallet(wallet == null ? UUID.randomUUID().toString() : wallet.id,
                        fieldValue(name), wallet == null ? fieldValue(currency) : wallet.currency,
                        initialTopUp ? BigDecimal.ZERO : amount,
                        initialTopUp ? null : costValue, initialTopUp ? null : costCurrency);
                first = initialTopUp ? new TopUp(UUID.randomUUID().toString(), updated.id, LocalDate.now(),
                        amount, updated.currency, costValue, costCurrency) : null;
            } catch (IllegalArgumentException ex) {
                toast("请检查币种和金额的小数位");
                return;
            }
            if (commit(() -> {
                Ledger next = ledgerStore().load().upsertWallet(updated);
                return first == null ? next : next.topUp(first);
            })) {
                dialog.dismiss();
                render();
            }
        });
    }

    private void topUp(Wallet wallet) {
        LinearLayout fields = column();
        fields.setPadding(dp(22), dp(8), dp(22), dp(14));
        EditText added = input("充入的 " + wallet.currency + " 金额", "",
                InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL, 20);
        addField(fields, "充值金额（" + wallet.currency + "）", added);
        LocalDate[] date = {LocalDate.now()};
        TextView dateButton = action(date[0].toString(), colors.paper, colors.green, null);
        dateButton.setOnClickListener(view -> chooseDate(date[0], picked -> {
            date[0] = picked;
            dateButton.setText(picked.toString());
        }));
        addField(fields, "充值日期", dateButton);
        LinearLayout costRow = row();
        EditText paid = input("留空表示不计成本", "",
                InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL, 20);
        costRow.addView(paid, new LinearLayout.LayoutParams(0, -2, 1f));
        EditText paidCode = input("币种", wallet.costCurrency != null ? wallet.costCurrency : rateStore().load().base,
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS, 3);
        LinearLayout.LayoutParams codeParams = new LinearLayout.LayoutParams(dp(84), -2);
        codeParams.leftMargin = dp(8);
        costRow.addView(paidCode, codeParams);
        addField(fields, "实际花了（选填）", costRow);
        fields.addView(label("填写后，账户的单位成本按新旧余额加权平均更新。", 12, colors.muted, false));
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("充值 · " + wallet.name)
                .setView(fields).setNegativeButton("取消", null).setPositiveButton("充值", null).create();
        dialog.show();
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
            BigDecimal amount = parseAmount(fieldValue(added));
            if (amount == null || amount.signum() == 0) {
                added.setError("请输入大于 0 的金额");
                return;
            }
            BigDecimal cost = null;
            if (!fieldValue(paid).isEmpty()) {
                cost = parseAmount(fieldValue(paid));
                if (cost == null || cost.signum() == 0) {
                    paid.setError("请输入大于 0 的金额，或留空");
                    return;
                }
            }
            TopUp record;
            try {
                record = new TopUp(UUID.randomUUID().toString(), wallet.id, date[0], amount, wallet.currency,
                        cost, cost == null ? null : fieldValue(paidCode).toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException ex) {
                toast("请检查币种和金额的小数位");
                return;
            }
            if (commit(() -> ledgerStore().load().topUp(record))) {
                dialog.dismiss();
                toast("已充值 " + money(wallet.currency, amount) + "，可在这里上传发票");
                render();
                topUpDetails(record);
            }
        });
    }

    private void confirmDelete(Wallet wallet) {
        new AlertDialog.Builder(this).setTitle("删除余额账户")
                .setMessage("确定删除“" + wallet.name + "”？相关订阅将不再自动扣款，"
                        + "充值记录和发票会一并删除，已有扣款记录会保留。")
                .setNegativeButton("取消", null)
                .setPositiveButton("删除", (dialog, button) -> {
                    if (commit(() -> ledgerStore().load().removeWallet(wallet.id))) {
                        render();
                    }
                }).show();
    }
}
