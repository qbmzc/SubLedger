package com.subledger.mobile;

import android.content.Intent;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
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
        TextView create = action("新建", colors.hero, colors.onHero, () -> editWallet(null));
        create.setBackground(ripple(colors.hero, 22));
        create.setCompoundDrawablesRelativeWithIntrinsicBounds(new Glyph(Glyph.PLUS, colors.onHero, dp(18)), null, null, null);
        create.setCompoundDrawablePadding(dp(4));
        LinearLayout root = screen("余额账户", create, 1100);
        root.addView(label("把礼品卡、预付余额记成账户。订阅选择从账户扣款后，续费时自动扣除，"
                + "余额不够下次扣款时会提醒。充值时填写实际花费，就能算出真实成本。", 14, colors.muted, false));
        gap(root, 16);
        List<View> cards = new ArrayList<>();
        for (Wallet wallet : ledger.wallets) {
            cards.add(walletCard(wallet));
        }
        // Phones: one column; tablets and desktops: as many as fit.
        grid(root, cards, columns(contentWidthDp(1100), 340, 16), 16);
        if (cards.isEmpty()) {
            LinearLayout empty = panel(colors.card, 24);
            empty.addView(label("还没有余额账户", 17, colors.ink, true));
            gap(empty, 4);
            empty.addView(label("点右上角「新建」，记下礼品卡或预付余额。", 14, colors.muted, false));
            root.addView(empty);
        }
    }

    private View walletCard(Wallet wallet) {
        Ledger.Coverage cover = ledger.coverage(wallet, today);
        LinearLayout card = panel(colors.card, 26);
        card.addView(label(wallet.name, 17, colors.ink, true));
        gap(card, 2);
        card.addView(amount(money(wallet.currency, wallet.balance), 28,
                wallet.balance.signum() < 0 ? colors.amber : colors.ink));
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
        TextView statusPill = label(status, 13, statusColor, true);
        statusPill.setBackground(shape(cover.insufficient() ? colors.lime : colors.paper, 12));
        statusPill.setPadding(dp(12), dp(8), dp(12), dp(8));
        gap(card, 4);
        card.addView(statusPill, new LinearLayout.LayoutParams(-1, -2));

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
            gap(card, 12);
            card.addView(label("最近扣除", 13, colors.ink, true));
            for (int i = deductions.size() - 1; i >= Math.max(0, deductions.size() - 3); i--) {
                Payment payment = deductions.get(i);
                card.addView(label(payment.date + "  " + payment.title + "  −"
                        + money(payment.currency, payment.amount), 13, colors.muted, false));
            }
        }

        List<TopUp> topUps = ledger.topUpsOf(wallet.id);
        if (!topUps.isEmpty()) {
            gap(card, 12);
            card.addView(label("充值记录 · 点按可上传发票", 13, colors.ink, true));
            for (int i = topUps.size() - 1; i >= Math.max(0, topUps.size() - 3); i--) {
                card.addView(topUpRow(topUps.get(i)), new LinearLayout.LayoutParams(-1, -2));
            }
            if (topUps.size() > 3) {
                card.addView(action("全部 " + topUps.size() + " 次充值", colors.card, colors.green,
                        () -> allTopUps(wallet)), new LinearLayout.LayoutParams(-2, dp(40)));
            }
        }

        // Buttons sit at the bottom, so cards in a grid row line up.
        View spacer = new View(this);
        card.addView(spacer, new LinearLayout.LayoutParams(1, 0, 1f));
        gap(card, 14);
        LinearLayout buttons = row();
        buttons.addView(action("充值", colors.hero, colors.onHero, () -> topUp(wallet)),
                new LinearLayout.LayoutParams(0, dp(46), 1f));
        LinearLayout.LayoutParams editParams = new LinearLayout.LayoutParams(0, dp(46), 1f);
        editParams.setMarginStart(dp(8));
        buttons.addView(action("编辑", colors.line, colors.green, () -> editWallet(wallet)), editParams);
        if (!deductions.isEmpty()) {
            LinearLayout.LayoutParams historyParams = new LinearLayout.LayoutParams(0, dp(46), 1f);
            historyParams.setMarginStart(dp(8));
            buttons.addView(action("扣款记录", colors.line, colors.green, () -> startActivity(
                    new Intent(this, HistoryActivity.class).putExtra(HistoryActivity.WALLET, wallet.id))),
                    historyParams);
        }
        card.addView(buttons);
        return card;
    }

    private View topUpRow(TopUp topUp) {
        TextView row = label(topUpLine(topUp), 13, colors.muted, false);
        row.setPadding(dp(8), dp(8), dp(8), dp(8));
        tappable(row, colors.card, 10, () -> topUpDetails(topUp));
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
        Runnable[] handlers = new Runnable[topUps.size()];
        for (int i = 0; i < topUps.size(); i++) {
            TopUp topUp = topUps.get(topUps.size() - 1 - i);
            lines[i] = topUpLine(topUp);
            handlers[i] = () -> topUpDetails(topUp);
        }
        menu(wallet.name + " · 充值记录", lines, handlers, -1);
    }

    /** A top-up with its invoices; deleting takes the amount back off the balance. */
    private void topUpDetails(TopUp topUp) {
        Sheet sheet = sheet("充值记录").detents(0.55f, 1f);
        LinearLayout body = sheet.body;
        body.addView(amount("+" + money(topUp.currency, topUp.amount), 28, colors.ink));
        gap(body, 12);
        LinearLayout facts = group();
        addRow(facts, factRow("日期", topUp.date.toString(), colors.ink));
        if (topUp.paid != null) {
            addRow(facts, factRow("实付", money(topUp.paidCurrency, topUp.paid), colors.ink));
        }
        body.addView(facts);
        section(body, "发票与截图");
        body.addView(new AttachmentStrip(topUp.id), new LinearLayout.LayoutParams(-1, -2));
        gap(body, 24);
        body.addView(action("删除充值记录", colors.card, colors.amber, () -> confirmDeleteTopUp(topUp, sheet)),
                new LinearLayout.LayoutParams(-1, dp(50)));
        sheet.show();
    }

    private void confirmDeleteTopUp(TopUp topUp, Sheet details) {
        alert().setTitle("删除充值记录")
                .setMessage("余额将减少 " + money(topUp.currency, topUp.amount)
                        + "，附件会一并删除。账户的单位成本保持不变，如需调整请编辑账户。")
                .setNegativeButton("取消", null)
                .setPositiveButton("删除", (dialog, button) -> {
                    if (commit(() -> ledgerStore().load().removeTopUp(topUp.id))) {
                        details.dismiss();
                        render();
                    }
                }).show();
    }

    @Override
    void onLedgerChanged() {
        render();
    }

    private void editWallet(Wallet wallet) {
        Sheet sheet = sheet(wallet == null ? "新建余额账户" : "编辑余额账户").detents(0.62f, 1f).expanded();
        LinearLayout fields = sheet.body;
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
        LinearLayout.LayoutParams codeParams = new LinearLayout.LayoutParams(dp(96), -2);
        codeParams.setMarginStart(dp(8));
        costRow.addView(costCode, codeParams);
        addField(fields, wallet == null ? "购买这些余额实际花了（选填）" : "每 1 单位余额的实际成本（选填）", costRow);
        hint(fields, wallet == null
                ? "例如花 CNY 650 买了 USD 100 礼品卡，就填 650 和 CNY。"
                : "例如 1 USD 实际花了 6.5 元，就填 6.5 和 CNY。");
        if (wallet != null) {
            gap(fields, 24);
            fields.addView(action("删除余额账户", colors.card, colors.amber, () -> confirmDelete(wallet, sheet)),
                    new LinearLayout.LayoutParams(-1, dp(50)));
        }

        String initial = fieldValue(name) + "\u0001" + fieldValue(currency) + "\u0001" + fieldValue(balance)
                + "\u0001" + fieldValue(cost) + "\u0001" + fieldValue(costCode);
        sheet.unsaved(() -> !(fieldValue(name) + "\u0001" + fieldValue(currency) + "\u0001" + fieldValue(balance)
                + "\u0001" + fieldValue(cost) + "\u0001" + fieldValue(costCode)).equals(initial));
        sheet.leading("取消", sheet::cancel);
        sheet.trailing("保存", () -> {
            if (fieldValue(name).isEmpty()) {
                name.setError("请填写账户名称");
                return false;
            }
            String text = fieldValue(balance).replace(',', '.');
            BigDecimal amount = parseAmount(text.startsWith("-") ? text.substring(1) : text);
            if (amount == null) {
                balance.setError("请输入有效金额");
                return false;
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
                    return false;
                }
                costCurrency = fieldValue(costCode).toUpperCase(Locale.ROOT);
                if (wallet == null && amount.signum() <= 0) {
                    balance.setError("填写实际花费时，余额需大于 0");
                    return false;
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
                return false;
            }
            if (!commit(() -> {
                Ledger next = ledgerStore().load().upsertWallet(updated);
                return first == null ? next : next.topUp(first);
            })) {
                return false;
            }
            render();
            return true;
        });
        sheet.show();
    }

    private void topUp(Wallet wallet) {
        Sheet sheet = sheet("充值 · " + wallet.name).detents(0.62f, 1f).expanded();
        LinearLayout fields = sheet.body;
        EditText added = input("充入的 " + wallet.currency + " 金额", "",
                InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL, 20);
        addField(fields, "充值金额（" + wallet.currency + "）", added);
        LocalDate[] date = {LocalDate.now()};
        TextView dateButton = pickerField(date[0].toString());
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
        LinearLayout.LayoutParams codeParams = new LinearLayout.LayoutParams(dp(96), -2);
        codeParams.setMarginStart(dp(8));
        costRow.addView(paidCode, codeParams);
        addField(fields, "实际花了（选填）", costRow);
        hint(fields, "填写后，账户的单位成本按新旧余额加权平均更新。");
        sheet.unsaved(() -> !fieldValue(added).isEmpty() || !fieldValue(paid).isEmpty());
        sheet.leading("取消", sheet::cancel);
        sheet.trailing("充值", () -> {
            BigDecimal amount = parseAmount(fieldValue(added));
            if (amount == null || amount.signum() == 0) {
                added.setError("请输入大于 0 的金额");
                return false;
            }
            BigDecimal cost = null;
            if (!fieldValue(paid).isEmpty()) {
                cost = parseAmount(fieldValue(paid));
                if (cost == null || cost.signum() == 0) {
                    paid.setError("请输入大于 0 的金额，或留空");
                    return false;
                }
            }
            TopUp record;
            try {
                record = new TopUp(UUID.randomUUID().toString(), wallet.id, date[0], amount, wallet.currency,
                        cost, cost == null ? null : fieldValue(paidCode).toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException ex) {
                toast("请检查币种和金额的小数位");
                return false;
            }
            if (!commit(() -> ledgerStore().load().topUp(record))) {
                return false;
            }
            toast("已充值 " + money(wallet.currency, amount) + "，可在这里上传发票");
            render();
            topUpDetails(record);
            return true;
        });
        sheet.show();
    }

    private void confirmDelete(Wallet wallet, Sheet editor) {
        alert().setTitle("删除余额账户")
                .setMessage("确定删除“" + wallet.name + "”？相关订阅将不再自动扣款，"
                        + "充值记录和发票会一并删除，已有扣款记录会保留。")
                .setNegativeButton("取消", null)
                .setPositiveButton("删除", (dialog, button) -> {
                    if (commit(() -> ledgerStore().load().removeWallet(wallet.id))) {
                        editor.dismiss();
                        render();
                    }
                }).show();
    }
}
