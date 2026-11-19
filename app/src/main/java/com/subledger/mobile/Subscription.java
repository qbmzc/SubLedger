package com.subledger.mobile;

import java.math.BigDecimal;
import java.math.MathContext;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Currency;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/** Immutable subscription. Build and copy instances with {@link Builder}; the constructor validates. */
final class Subscription {
    static final int MAX_PRICES = 50;
    static final int MAX_SPLIT = 99;
    static final BigDecimal MAX_AMOUNT = new BigDecimal("999999999");

    enum Cycle {
        WEEKLY("每周"), MONTHLY("每月"), QUARTERLY("每季"), YEARLY("每年");

        final String label;

        Cycle(String label) {
            this.label = label;
        }

        int months() {
            switch (this) {
                case MONTHLY: return 1;
                case QUARTERLY: return 3;
                case YEARLY: return 12;
                default: throw new IllegalStateException("Weekly cycle uses days");
            }
        }
    }

    enum Mode {
        AUTO("自动续费"), MANUAL("手动续费"), ENDED("已停止");

        final String label;

        Mode(String label) {
            this.label = label;
        }
    }

    /** A previous price, which applied until {@link #until}. */
    static final class PricePoint {
        final LocalDate until;
        final BigDecimal amount;
        final String currency;

        PricePoint(LocalDate until, BigDecimal amount, String currency) {
            this.until = until;
            this.currency = validMoney(amount, currency, false);
            this.amount = amount;
        }
    }

    final String id, title, currency, payment, note, category;
    final BigDecimal amount;
    final LocalDate anchor;
    final Cycle cycle;
    final Mode mode;
    /** Days before a renewal to remind, -1 when off. */
    final int reminderDays;
    /**
     * Optional real cost per billing period, e.g. what a prepaid gift-card balance actually cost.
     * Both are null when not set.
     */
    final BigDecimal cost;
    final String costCurrency;
    /** Free trial: {@link #anchor} is the day the trial ends and the first charge happens. */
    final boolean trial;
    /** People sharing the bill, including the user. Statistics count 1/splitWays of the cost. */
    final int splitWays;
    /** Balance account the bill is paid from, or null. */
    final String walletId;
    /** Earlier prices, oldest first. */
    final List<PricePoint> prices;
    /**
     * Auto-renewal charges up to and including this date are in the payment history.
     * Null until tracking starts; see {@link Ledger#recordDue}.
     */
    final LocalDate recordedThrough;

    private Subscription(Builder b) {
        UUID.fromString(b.id);
        if (b.walletId != null) {
            UUID.fromString(b.walletId);
        }
        if (b.title.trim().isEmpty() || b.title.length() > 100 || b.payment.length() > 120
                || b.note.length() > 500 || b.category.length() > 30
                || b.anchor.getYear() < 1900 || b.anchor.getYear() > 9998
                || b.reminderDays < -1 || b.reminderDays > 90
                || b.splitWays < 1 || b.splitWays > MAX_SPLIT || b.prices.size() > MAX_PRICES) {
            throw new IllegalArgumentException("订阅字段超出范围");
        }
        if ((b.cost == null) != (b.costCurrency == null)) {
            throw new IllegalArgumentException("实际成本需要同时填写金额和币种");
        }
        currency = validMoney(b.amount, b.currency, false);
        costCurrency = b.cost == null ? null : validMoney(b.cost, b.costCurrency, false);
        id = b.id;
        title = b.title.trim();
        amount = b.amount;
        anchor = b.anchor;
        cycle = b.cycle;
        mode = b.mode;
        reminderDays = b.reminderDays;
        payment = b.payment.trim();
        note = b.note.trim();
        category = b.category.trim();
        cost = b.cost;
        trial = b.trial;
        splitWays = b.splitWays;
        walletId = b.walletId;
        prices = Collections.unmodifiableList(new ArrayList<>(b.prices));
        recordedThrough = b.recordedThrough;
    }

    /** Mutable field bag for creating and copying subscriptions. */
    static final class Builder {
        String id = UUID.randomUUID().toString();
        String title = "", currency = "CNY", payment = "", note = "", category = "";
        BigDecimal amount = BigDecimal.ZERO;
        LocalDate anchor = LocalDate.now();
        Cycle cycle = Cycle.MONTHLY;
        Mode mode = Mode.AUTO;
        int reminderDays = -1;
        BigDecimal cost;
        String costCurrency;
        boolean trial;
        int splitWays = 1;
        String walletId;
        List<PricePoint> prices = new ArrayList<>();
        LocalDate recordedThrough;

        Subscription build() {
            return new Subscription(this);
        }
    }

    Builder toBuilder() {
        Builder b = new Builder();
        b.id = id;
        b.title = title;
        b.currency = currency;
        b.payment = payment;
        b.note = note;
        b.category = category;
        b.amount = amount;
        b.anchor = anchor;
        b.cycle = cycle;
        b.mode = mode;
        b.reminderDays = reminderDays;
        b.cost = cost;
        b.costCurrency = costCurrency;
        b.trial = trial;
        b.splitWays = splitWays;
        b.walletId = walletId;
        b.prices = new ArrayList<>(prices);
        b.recordedThrough = recordedThrough;
        return b;
    }

    /**
     * Returns the normalized currency code, or throws if the amount does not fit it.
     * Negative amounts are only allowed when {@code signed}.
     */
    static String validMoney(BigDecimal amount, String currency, boolean signed) {
        String code = Currency.getInstance(currency.trim().toUpperCase(Locale.ROOT)).getCurrencyCode();
        if ((!signed && amount.signum() < 0) || amount.scale() < 0
                || amount.scale() > Math.max(0, Currency.getInstance(code).getDefaultFractionDigits())
                || amount.abs().compareTo(MAX_AMOUNT) > 0) {
            throw new IllegalArgumentException("金额或小数位与币种不符");
        }
        return code;
    }

    boolean hasCost() {
        return cost != null;
    }

    LocalDate occurrence(long index) {
        if (cycle == Cycle.WEEKLY) {
            return anchor.plusWeeks(index);
        }
        return anchor.plusMonths(index * cycle.months());
    }

    long indexOnOrAfter(LocalDate date) {
        if (!anchor.isBefore(date)) {
            return 0;
        }
        long index = cycle == Cycle.WEEKLY
                ? ChronoUnit.DAYS.between(anchor, date) / 7
                : ChronoUnit.MONTHS.between(YearMonth.from(anchor), YearMonth.from(date)) / cycle.months();
        return occurrence(index).isBefore(date) ? index + 1 : index;
    }

    LocalDate nextDate(LocalDate today) {
        return mode == Mode.AUTO ? occurrence(indexOnOrAfter(today)) : anchor;
    }

    /** Monthly estimate of the bill, in {@link #currency}. */
    BigDecimal monthlyEstimate() {
        return perMonth(amount);
    }

    /** Converts a per-period value into a monthly estimate for this cycle. */
    BigDecimal perMonth(BigDecimal value) {
        switch (cycle) {
            case WEEKLY: return value.multiply(BigDecimal.valueOf(52)).divide(BigDecimal.valueOf(12), MathContext.DECIMAL128);
            case QUARTERLY: return value.divide(BigDecimal.valueOf(3), MathContext.DECIMAL128);
            case YEARLY: return value.divide(BigDecimal.valueOf(12), MathContext.DECIMAL128);
            default: return value;
        }
    }

    /** The user's part of {@code value} when the bill is shared. */
    BigDecimal myShare(BigDecimal value) {
        return splitWays == 1 ? value : value.divide(BigDecimal.valueOf(splitWays), MathContext.DECIMAL128);
    }

    /** Copy with a new anchor date, keeping every other field. */
    Subscription withAnchor(LocalDate date) {
        Builder b = toBuilder();
        b.anchor = date;
        return b.build();
    }
}
