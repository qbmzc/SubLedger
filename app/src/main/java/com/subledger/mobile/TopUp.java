package com.subledger.mobile;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/** One top-up of a balance account, with what it really cost when known. */
final class TopUp {
    final String id, walletId, currency;
    final LocalDate date;
    /** Amount added, in the wallet's currency. */
    final BigDecimal amount;
    /** What the top-up really cost, in {@link #paidCurrency}; both null when unknown. */
    final BigDecimal paid;
    final String paidCurrency;

    TopUp(String id, String walletId, LocalDate date, BigDecimal amount, String currency,
          BigDecimal paid, String paidCurrency) {
        UUID.fromString(id);
        UUID.fromString(walletId);
        if (amount.signum() <= 0 || date.getYear() < 1900 || date.getYear() > 9998
                || (paid == null) != (paidCurrency == null)) {
            throw new IllegalArgumentException("充值记录字段超出范围");
        }
        this.currency = Subscription.validMoney(amount, currency, false);
        this.paidCurrency = paid == null ? null : Subscription.validMoney(paid, paidCurrency, false);
        this.id = id;
        this.walletId = walletId;
        this.date = date;
        this.amount = amount;
        this.paid = paid;
    }
}
