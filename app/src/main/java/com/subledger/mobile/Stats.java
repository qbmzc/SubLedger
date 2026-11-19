package com.subledger.mobile;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.SortedSet;
import java.util.TreeSet;

/**
 * Spending statistics in the base currency. Estimates cover non-ended subscriptions at their
 * real cost (actual cost, wallet unit cost, or today's rate) and the user's share of split bills.
 * Spent totals come from the payment history, whose costs were fixed when recorded.
 */
final class Stats {
    static final int MONTHS_AHEAD = 12;

    static final class Share {
        final String label;
        /** The user's monthly estimate in the base currency. */
        final BigDecimal monthly;
        final int count;
        /** The subscription for per-item rows, null for grouped rows. */
        final Subscription entry;

        Share(String label, BigDecimal monthly, int count, Subscription entry) {
            this.label = label;
            this.monthly = monthly;
            this.count = count;
            this.entry = entry;
        }
    }

    static final class Charge {
        final Subscription entry;
        final LocalDate date;
        /** The user's share in the base currency. */
        final BigDecimal amount;

        Charge(Subscription entry, LocalDate date, BigDecimal amount) {
            this.entry = entry;
            this.date = date;
            this.amount = amount;
        }
    }

    static final class Month {
        final YearMonth month;
        final BigDecimal total;
        final List<Charge> charges;

        Month(YearMonth month, BigDecimal total, List<Charge> charges) {
            this.month = month;
            this.total = total;
            this.charges = Collections.unmodifiableList(charges);
        }
    }

    final String base;
    /** Non-ended subscriptions, and how many of them could be converted. */
    final int active, counted;
    final BigDecimal monthly, yearly, upcoming;
    final List<Share> items, byCategory, byPayment, byCycle;
    final List<Month> months;
    /** Currencies of non-ended subscriptions that have no rate. */
    final SortedSet<String> missing;
    /** The user's share of recorded payments this calendar year and in the last 12 months. */
    final BigDecimal spentThisYear, spentLastYear;
    /** Recorded payments, and those left out of the spent totals for lack of a rate. */
    final int paymentCount, unconvertedPayments;

    Stats(Ledger ledger, Rates rates, LocalDate today) {
        base = rates.base;
        SortedSet<String> missingCodes = new TreeSet<>();
        List<Share> perItem = new ArrayList<>();
        Map<String, BigDecimal[]> categories = new LinkedHashMap<>();
        Map<String, BigDecimal[]> payments = new LinkedHashMap<>();
        Map<String, BigDecimal[]> cycles = new LinkedHashMap<>();
        YearMonth first = YearMonth.from(today);
        LocalDate end = first.plusMonths(MONTHS_AHEAD).atDay(1);
        List<List<Charge>> buckets = new ArrayList<>();
        for (int i = 0; i < MONTHS_AHEAD; i++) {
            buckets.add(new ArrayList<>());
        }

        int activeCount = 0;
        BigDecimal monthlyTotal = BigDecimal.ZERO;
        for (Subscription entry : ledger.subscriptions) {
            if (entry.mode == Subscription.Mode.ENDED) {
                continue;
            }
            activeCount++;
            Ledger.Money real = ledger.realCost(entry);
            BigDecimal perPeriod = rates.toBase(real.amount, real.currency);
            if (perPeriod == null) {
                missingCodes.add(real.currency);
                continue;
            }
            perPeriod = entry.myShare(perPeriod);
            BigDecimal perMonth = entry.perMonth(perPeriod);
            monthlyTotal = monthlyTotal.add(perMonth);
            perItem.add(new Share(entry.title, perMonth, 1, entry));
            add(categories, entry.category.isEmpty() ? "未分类" : entry.category, perMonth);
            add(payments, entry.payment.isEmpty() ? "未填写付款渠道" : entry.payment, perMonth);
            add(cycles, entry.cycle.label, perMonth);

            // Charges already recorded today are not upcoming. Manual renewals are projected
            // along the same cycle as an estimate.
            LocalDate from = entry.mode == Subscription.Mode.AUTO && entry.recordedThrough != null
                    && !entry.recordedThrough.isBefore(today) ? entry.recordedThrough.plusDays(1) : today;
            for (long index = entry.indexOnOrAfter(from); ; index++) {
                LocalDate date = entry.occurrence(index);
                if (!date.isBefore(end)) {
                    break;
                }
                int bucket = (int) ChronoUnit.MONTHS.between(first, YearMonth.from(date));
                buckets.get(bucket).add(new Charge(entry, date, perPeriod));
            }
        }

        Comparator<Share> largestFirst = Comparator.comparing((Share share) -> share.monthly).reversed()
                .thenComparing(share -> share.label, String.CASE_INSENSITIVE_ORDER);
        perItem.sort(largestFirst);
        List<Share> cycleShares = new ArrayList<>();
        for (Subscription.Cycle cycle : Subscription.Cycle.values()) {
            BigDecimal[] total = cycles.get(cycle.label);
            if (total != null) {
                cycleShares.add(new Share(cycle.label, total[0], total[1].intValue(), null));
            }
        }

        List<Month> projected = new ArrayList<>();
        BigDecimal upcomingTotal = BigDecimal.ZERO;
        for (int i = 0; i < MONTHS_AHEAD; i++) {
            List<Charge> charges = buckets.get(i);
            charges.sort(Comparator.comparing((Charge charge) -> charge.date)
                    .thenComparing(charge -> charge.entry.title, String.CASE_INSENSITIVE_ORDER));
            BigDecimal total = BigDecimal.ZERO;
            for (Charge charge : charges) {
                total = total.add(charge.amount);
            }
            upcomingTotal = upcomingTotal.add(total);
            projected.add(new Month(first.plusMonths(i), total, charges));
        }

        BigDecimal thisYear = BigDecimal.ZERO;
        BigDecimal lastYear = BigDecimal.ZERO;
        LocalDate yearAgo = today.minusYears(1);
        int unconverted = 0;
        for (Payment payment : ledger.payments) {
            boolean inYear = payment.date.getYear() == today.getYear();
            boolean inLastTwelveMonths = payment.date.isAfter(yearAgo);
            if (payment.date.isAfter(today) || !inYear && !inLastTwelveMonths) {
                continue;
            }
            BigDecimal mine = payment.mine(rates);
            if (mine == null) {
                unconverted++;
                continue;
            }
            if (inYear) {
                thisYear = thisYear.add(mine);
            }
            if (inLastTwelveMonths) {
                lastYear = lastYear.add(mine);
            }
        }

        active = activeCount;
        counted = perItem.size();
        monthly = monthlyTotal;
        yearly = monthlyTotal.multiply(BigDecimal.valueOf(12));
        upcoming = upcomingTotal;
        items = Collections.unmodifiableList(perItem);
        byCategory = Collections.unmodifiableList(sorted(categories, largestFirst));
        byPayment = Collections.unmodifiableList(sorted(payments, largestFirst));
        byCycle = Collections.unmodifiableList(cycleShares);
        months = Collections.unmodifiableList(projected);
        missing = Collections.unmodifiableSortedSet(missingCodes);
        spentThisYear = thisYear;
        spentLastYear = lastYear;
        paymentCount = ledger.payments.size();
        unconvertedPayments = unconverted;
    }

    /** Accumulates {total, count} per group. */
    private static void add(Map<String, BigDecimal[]> groups, String key, BigDecimal value) {
        BigDecimal[] total = groups.get(key);
        if (total == null) {
            groups.put(key, new BigDecimal[]{value, BigDecimal.ONE});
        } else {
            total[0] = total[0].add(value);
            total[1] = total[1].add(BigDecimal.ONE);
        }
    }

    private static List<Share> sorted(Map<String, BigDecimal[]> groups, Comparator<Share> order) {
        List<Share> shares = new ArrayList<>();
        for (Map.Entry<String, BigDecimal[]> group : groups.entrySet()) {
            shares.add(new Share(group.getKey(), group.getValue()[0], group.getValue()[1].intValue(), null));
        }
        shares.sort(order);
        return shares;
    }

    /** True when the estimate converts any currency other than the base. */
    static boolean converts(Ledger ledger, String base) {
        for (Subscription entry : ledger.subscriptions) {
            if (entry.mode != Subscription.Mode.ENDED && !ledger.realCost(entry).currency.equals(base)) {
                return true;
            }
        }
        return false;
    }
}
