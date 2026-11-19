package com.subledger.mobile;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.Currency;
import java.util.UUID;

/**
 * A prepaid balance that subscriptions are paid from, such as an App Store gift-card balance.
 * Optionally remembers what one unit of balance really cost, so charges get a real cost.
 */
final class Wallet {
    final String id, name, currency;
    /** May go negative when charges exceed the balance. */
    final BigDecimal balance;
    /** Real cost of one unit of balance in {@link #costCurrency}; both null when unknown. */
    final BigDecimal unitCost;
    final String costCurrency;

    Wallet(String id, String name, String currency, BigDecimal balance, BigDecimal unitCost, String costCurrency) {
        UUID.fromString(id);
        if (name.trim().isEmpty() || name.length() > 40) {
            throw new IllegalArgumentException("余额账户名称需为 1–40 个字符");
        }
        if ((unitCost == null) != (costCurrency == null)
                || (unitCost != null && !Rates.validRate(unitCost))) {
            throw new IllegalArgumentException("单位成本无效");
        }
        this.currency = Subscription.validMoney(balance, currency, true);
        this.costCurrency = costCurrency == null ? null : Rates.code(costCurrency);
        this.id = id;
        this.name = name.trim();
        this.balance = balance;
        this.unitCost = unitCost;
    }

    Wallet withBalance(BigDecimal value) {
        return new Wallet(id, name, currency, value, unitCost, costCurrency);
    }

    /**
     * Adds {@code added} to the balance. When {@code paid} is given (what the top-up really cost,
     * in {@code paidCurrency}), the unit cost becomes the weighted average of the old balance and
     * the new top-up.
     */
    Wallet topUp(BigDecimal added, BigDecimal paid, String paidCurrency) {
        if (added.signum() <= 0) {
            throw new IllegalArgumentException("充值金额需大于 0");
        }
        BigDecimal total = balance.add(added);
        if (paid == null) {
            return new Wallet(id, name, currency, total, unitCost, costCurrency);
        }
        String paidCode = Rates.code(paidCurrency);
        BigDecimal unit;
        if (unitCost != null && paidCode.equals(costCurrency) && balance.signum() > 0) {
            unit = balance.multiply(unitCost).add(paid).divide(total, MathContext.DECIMAL64);
        } else {
            unit = paid.divide(added, MathContext.DECIMAL64);
        }
        return new Wallet(id, name, currency, total, unit, paidCode);
    }

    /** Real cost of {@code amount} of balance, rounded to the cost currency, or null if unknown. */
    BigDecimal costOf(BigDecimal amount) {
        if (unitCost == null) {
            return null;
        }
        int digits = Math.max(0, Currency.getInstance(costCurrency).getDefaultFractionDigits());
        return amount.multiply(unitCost).setScale(digits, RoundingMode.HALF_UP);
    }
}
