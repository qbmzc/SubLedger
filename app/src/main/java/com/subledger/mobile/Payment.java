package com.subledger.mobile;

import java.math.BigDecimal;
import java.math.MathContext;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.UUID;

/**
 * One recorded charge. The real cost is fixed when the payment is recorded, so later rate
 * changes do not rewrite history.
 */
final class Payment {
    final String id, subscriptionId, title, currency, costCurrency;
    /** Balance account the amount was deducted from, or null. */
    final String walletId;
    final LocalDate date;
    /** The bill, in the subscription's currency. */
    final BigDecimal amount;
    /** What the charge really cost, in {@link #costCurrency}. */
    final BigDecimal cost;
    final int splitWays;
    /** True when recorded automatically for an auto-renewal. */
    final boolean auto;

    Payment(String id, String subscriptionId, String title, LocalDate date, BigDecimal amount, String currency,
            BigDecimal cost, String costCurrency, int splitWays, String walletId, boolean auto) {
        UUID.fromString(id);
        UUID.fromString(subscriptionId);
        if (walletId != null) {
            UUID.fromString(walletId);
        }
        if (title.trim().isEmpty() || title.length() > 100 || splitWays < 1
                || splitWays > Subscription.MAX_SPLIT || date.getYear() < 1900 || date.getYear() > 9998) {
            throw new IllegalArgumentException("扣款记录字段超出范围");
        }
        this.currency = Subscription.validMoney(amount, currency, false);
        this.costCurrency = Subscription.validMoney(cost, costCurrency, false);
        this.id = id;
        this.subscriptionId = subscriptionId;
        this.title = title.trim();
        this.date = date;
        this.amount = amount;
        this.cost = cost;
        this.splitWays = splitWays;
        this.walletId = walletId;
        this.auto = auto;
    }

    /** Stable id for the charge of one renewal, so re-recording or merging backups never duplicates it. */
    static String idFor(String subscriptionId, LocalDate renewal) {
        return UUID.nameUUIDFromBytes((subscriptionId + "/" + renewal).getBytes(StandardCharsets.UTF_8)).toString();
    }

    /** The user's share of the real cost in the base currency, or null when no rate is known. */
    BigDecimal mine(Rates rates) {
        BigDecimal full = rates.toBase(cost, costCurrency);
        return full == null ? null : full.divide(BigDecimal.valueOf(splitWays), MathContext.DECIMAL128);
    }
}
