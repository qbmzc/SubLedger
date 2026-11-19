package com.subledger.mobile;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Persists the {@link Ledger} as one JSON document. The same format is the user-facing backup.
 * Version history: 1 subscriptions only; 2 added the actual cost; 3 added categories, trials,
 * split bills, wallets, price history and payments; 4 added top-up records and attachment
 * metadata (the files travel in the ZIP backup, see {@link BackupZip}); 5 added subscription icons.
 * Older versions still decode.
 */
final class LedgerStore {
    private static final String PREFS = "ledger";
    private static final String KEY = "subscriptions";
    private static final String REVISION = "revision";
    static final int VERSION = 5;
    private final SharedPreferences prefs;

    LedgerStore(Context context) {
        prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    Ledger load() {
        String json = prefs.getString(KEY, null);
        if (json == null) {
            return Ledger.empty();
        }
        try {
            return decode(json);
        } catch (JSONException | IllegalArgumentException e) {
            throw new IllegalStateException("无法读取订阅记录", e);
        }
    }

    void save(Ledger ledger) {
        try {
            if (!prefs.edit().putString(KEY, encode(ledger))
                    .putLong(REVISION, revision() + 1).commit()) {
                throw new IllegalStateException("保存失败，请检查设备可用空间");
            }
        } catch (JSONException e) {
            throw new IllegalStateException("保存失败", e);
        }
    }

    /** Changes on every save, so screens can tell whether to re-render. */
    long revision() {
        return prefs.getLong(REVISION, 0);
    }

    /** Records auto-renewal charges due by today. Returns true when anything was saved. */
    boolean recordDue(Rates rates, LocalDate today) {
        Ledger current = load();
        Ledger updated = current.recordDue(rates, today);
        if (updated == current) {
            return false;
        }
        save(updated);
        return true;
    }

    static String encode(Ledger ledger) throws JSONException {
        JSONArray subscriptions = new JSONArray();
        for (Subscription entry : ledger.subscriptions) {
            JSONObject row = new JSONObject()
                    .put("id", entry.id)
                    .put("title", entry.title)
                    .put("amount", entry.amount.toPlainString())
                    .put("currency", entry.currency)
                    .put("date", entry.anchor.toString())
                    .put("cycle", entry.cycle.name())
                    .put("mode", entry.mode.name())
                    .put("reminderDays", entry.reminderDays)
                    .put("payment", entry.payment)
                    .put("note", entry.note)
                    .put("category", entry.category)
                    .put("trial", entry.trial)
                    .put("splitWays", entry.splitWays);
            if (entry.hasCost()) {
                row.put("cost", entry.cost.toPlainString()).put("costCurrency", entry.costCurrency);
            }
            if (entry.walletId != null) {
                row.put("walletId", entry.walletId);
            }
            if (entry.recordedThrough != null) {
                row.put("recordedThrough", entry.recordedThrough.toString());
            }
            if (entry.iconText != null) {
                row.put("iconText", entry.iconText);
            }
            if (entry.iconColor != null) {
                row.put("iconColor", Icons.formatColor(entry.iconColor));
            }
            if (entry.iconImage != null) {
                row.put("iconImage", entry.iconImage);
            }
            JSONArray prices = new JSONArray();
            for (Subscription.PricePoint price : entry.prices) {
                prices.put(new JSONObject().put("until", price.until.toString())
                        .put("amount", price.amount.toPlainString()).put("currency", price.currency));
            }
            row.put("prices", prices);
            subscriptions.put(row);
        }
        JSONArray wallets = new JSONArray();
        for (Wallet wallet : ledger.wallets) {
            JSONObject row = new JSONObject()
                    .put("id", wallet.id)
                    .put("name", wallet.name)
                    .put("currency", wallet.currency)
                    .put("balance", wallet.balance.toPlainString());
            if (wallet.unitCost != null) {
                row.put("unitCost", wallet.unitCost.toPlainString()).put("costCurrency", wallet.costCurrency);
            }
            wallets.put(row);
        }
        JSONArray payments = new JSONArray();
        for (Payment payment : ledger.payments) {
            JSONObject row = new JSONObject()
                    .put("id", payment.id)
                    .put("subscriptionId", payment.subscriptionId)
                    .put("title", payment.title)
                    .put("date", payment.date.toString())
                    .put("amount", payment.amount.toPlainString())
                    .put("currency", payment.currency)
                    .put("cost", payment.cost.toPlainString())
                    .put("costCurrency", payment.costCurrency)
                    .put("splitWays", payment.splitWays)
                    .put("auto", payment.auto);
            if (payment.walletId != null) {
                row.put("walletId", payment.walletId);
            }
            payments.put(row);
        }
        JSONArray topUps = new JSONArray();
        for (TopUp topUp : ledger.topUps) {
            JSONObject row = new JSONObject()
                    .put("id", topUp.id)
                    .put("walletId", topUp.walletId)
                    .put("date", topUp.date.toString())
                    .put("amount", topUp.amount.toPlainString())
                    .put("currency", topUp.currency);
            if (topUp.paid != null) {
                row.put("paid", topUp.paid.toPlainString()).put("paidCurrency", topUp.paidCurrency);
            }
            topUps.put(row);
        }
        JSONArray attachments = new JSONArray();
        for (Attachment attachment : ledger.attachments) {
            attachments.put(new JSONObject()
                    .put("id", attachment.id)
                    .put("ownerId", attachment.ownerId)
                    .put("mime", attachment.mime)
                    .put("name", attachment.name)
                    .put("added", attachment.added.toString())
                    .put("size", attachment.size));
        }
        return new JSONObject().put("version", VERSION).put("subscriptions", subscriptions)
                .put("wallets", wallets).put("payments", payments)
                .put("topUps", topUps).put("attachments", attachments).toString();
    }

    static Ledger decode(String json) throws JSONException {
        JSONObject root = new JSONObject(json);
        int version = root.getInt("version");
        if (version < 1 || version > VERSION) {
            throw new JSONException("备份版本不受支持");
        }
        JSONArray list = root.getJSONArray("subscriptions");
        if (list.length() > Ledger.MAX_SUBSCRIPTIONS) {
            throw new JSONException("备份中的订阅超过数量上限");
        }
        Set<String> ids = new HashSet<>();
        List<Subscription> subscriptions = new ArrayList<>();
        for (int i = 0; i < list.length(); i++) {
            JSONObject row = list.getJSONObject(i);
            Subscription.Builder b = new Subscription.Builder();
            b.id = row.getString("id");
            b.title = row.getString("title");
            b.amount = new BigDecimal(row.getString("amount"));
            b.currency = row.getString("currency");
            b.anchor = LocalDate.parse(row.getString("date"));
            b.cycle = Subscription.Cycle.valueOf(row.getString("cycle"));
            b.mode = Subscription.Mode.valueOf(row.getString("mode"));
            b.reminderDays = row.getInt("reminderDays");
            b.payment = row.getString("payment");
            b.note = row.getString("note");
            if (version >= 2 && row.has("cost")) {
                b.cost = new BigDecimal(row.getString("cost"));
                b.costCurrency = row.getString("costCurrency");
            }
            if (version >= 3) {
                b.category = row.optString("category", "");
                b.trial = row.optBoolean("trial");
                b.splitWays = row.optInt("splitWays", 1);
                b.walletId = row.has("walletId") ? row.getString("walletId") : null;
                b.recordedThrough = row.has("recordedThrough")
                        ? LocalDate.parse(row.getString("recordedThrough")) : null;
                JSONArray prices = row.optJSONArray("prices");
                for (int j = 0; prices != null && j < prices.length(); j++) {
                    JSONObject price = prices.getJSONObject(j);
                    b.prices.add(new Subscription.PricePoint(LocalDate.parse(price.getString("until")),
                            new BigDecimal(price.getString("amount")), price.getString("currency")));
                }
            }
            if (version >= 5) {
                b.iconText = row.has("iconText") ? row.getString("iconText") : null;
                if (row.has("iconColor")) {
                    b.iconColor = Icons.parseColor(row.getString("iconColor"));
                    if (b.iconColor == null) {
                        throw new JSONException("图标颜色无效");
                    }
                }
                b.iconImage = row.has("iconImage") ? row.getString("iconImage") : null;
            }
            Subscription entry = b.build();
            if (!ids.add(entry.id)) {
                throw new JSONException("备份中含有重复记录");
            }
            subscriptions.add(entry);
        }

        List<Wallet> wallets = new ArrayList<>();
        JSONArray walletRows = version >= 3 ? root.optJSONArray("wallets") : null;
        for (int i = 0; walletRows != null && i < walletRows.length(); i++) {
            JSONObject row = walletRows.getJSONObject(i);
            Wallet wallet = new Wallet(row.getString("id"), row.getString("name"), row.getString("currency"),
                    new BigDecimal(row.getString("balance")),
                    row.has("unitCost") ? new BigDecimal(row.getString("unitCost")) : null,
                    row.has("unitCost") ? row.getString("costCurrency") : null);
            if (!ids.add(wallet.id)) {
                throw new JSONException("备份中含有重复记录");
            }
            wallets.add(wallet);
        }

        List<Payment> payments = new ArrayList<>();
        JSONArray paymentRows = version >= 3 ? root.optJSONArray("payments") : null;
        if (paymentRows != null && paymentRows.length() > Ledger.MAX_PAYMENTS) {
            throw new JSONException("备份中的扣款记录超过数量上限");
        }
        for (int i = 0; paymentRows != null && i < paymentRows.length(); i++) {
            JSONObject row = paymentRows.getJSONObject(i);
            Payment payment = new Payment(row.getString("id"), row.getString("subscriptionId"),
                    row.getString("title"), LocalDate.parse(row.getString("date")),
                    new BigDecimal(row.getString("amount")), row.getString("currency"),
                    new BigDecimal(row.getString("cost")), row.getString("costCurrency"),
                    row.optInt("splitWays", 1), row.has("walletId") ? row.getString("walletId") : null,
                    row.optBoolean("auto"));
            if (!ids.add(payment.id)) {
                throw new JSONException("备份中含有重复记录");
            }
            payments.add(payment);
        }
        List<TopUp> topUps = new ArrayList<>();
        JSONArray topUpRows = version >= 4 ? root.optJSONArray("topUps") : null;
        if (topUpRows != null && topUpRows.length() > Ledger.MAX_TOP_UPS) {
            throw new JSONException("备份中的充值记录超过数量上限");
        }
        for (int i = 0; topUpRows != null && i < topUpRows.length(); i++) {
            JSONObject row = topUpRows.getJSONObject(i);
            TopUp topUp = new TopUp(row.getString("id"), row.getString("walletId"),
                    LocalDate.parse(row.getString("date")), new BigDecimal(row.getString("amount")),
                    row.getString("currency"),
                    row.has("paid") ? new BigDecimal(row.getString("paid")) : null,
                    row.has("paid") ? row.getString("paidCurrency") : null);
            if (!ids.add(topUp.id)) {
                throw new JSONException("备份中含有重复记录");
            }
            topUps.add(topUp);
        }

        List<Attachment> attachments = new ArrayList<>();
        JSONArray attachmentRows = version >= 4 ? root.optJSONArray("attachments") : null;
        if (attachmentRows != null && attachmentRows.length() > Ledger.MAX_ATTACHMENTS) {
            throw new JSONException("备份中的附件超过数量上限");
        }
        for (int i = 0; attachmentRows != null && i < attachmentRows.length(); i++) {
            JSONObject row = attachmentRows.getJSONObject(i);
            Attachment attachment = new Attachment(row.getString("id"), row.getString("ownerId"),
                    row.getString("mime"), row.getString("name"), LocalDate.parse(row.getString("added")),
                    row.getLong("size"));
            if (!ids.add(attachment.id)) {
                throw new JSONException("备份中含有重复记录");
            }
            attachments.add(attachment);
        }
        return new Ledger(subscriptions, wallets, payments, topUps, attachments);
    }
}
