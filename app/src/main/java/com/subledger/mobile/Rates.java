package com.subledger.mobile;

import java.math.BigDecimal;
import java.math.MathContext;
import java.util.Collections;
import java.util.Currency;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * Exchange rates relative to one base currency. Every rate is "base units per 1 unit of the
 * foreign currency", e.g. base CNY, USD -> 7.2. Manual rates override fetched ones.
 */
final class Rates {
    static final long STALE_AFTER_MILLIS = 12L * 60 * 60 * 1000;
    private static final BigDecimal MAX_RATE = new BigDecimal("1000000000");

    final String base;
    final Map<String, BigDecimal> fetched, manual;
    /** When rates were last downloaded on this device, 0 if never. */
    final long fetchedAt;
    /** Publication time reported by the rate source, 0 if unknown. */
    final long asOf;

    Rates(String base, Map<String, BigDecimal> fetched, Map<String, BigDecimal> manual,
          long fetchedAt, long asOf) {
        this.base = code(base);
        this.fetched = clean(fetched, this.base);
        this.manual = clean(manual, this.base);
        this.fetchedAt = fetchedAt;
        this.asOf = asOf;
    }

    static Rates empty(String base) {
        return new Rates(base, Collections.emptyMap(), Collections.emptyMap(), 0, 0);
    }

    static String defaultBase() {
        try {
            Currency local = Currency.getInstance(Locale.getDefault());
            if (local != null) {
                return local.getCurrencyCode();
            }
        } catch (IllegalArgumentException ignored) {
            // Locales without a country have no currency.
        }
        return "CNY";
    }

    static String code(String value) {
        return Currency.getInstance(value.trim().toUpperCase(Locale.ROOT)).getCurrencyCode();
    }

    static boolean validRate(BigDecimal rate) {
        return rate != null && rate.signum() > 0 && rate.compareTo(MAX_RATE) <= 0;
    }

    /** Base units for one unit of {@code currency}, or null when no rate is known. */
    BigDecimal perUnit(String currency) {
        if (base.equals(currency)) {
            return BigDecimal.ONE;
        }
        BigDecimal rate = manual.get(currency);
        return rate != null ? rate : fetched.get(currency);
    }

    boolean isManual(String currency) {
        return !base.equals(currency) && manual.containsKey(currency);
    }

    BigDecimal toBase(BigDecimal amount, String currency) {
        BigDecimal rate = perUnit(currency);
        return rate == null ? null : amount.multiply(rate, MathContext.DECIMAL128);
    }

    boolean stale(long now) {
        return fetchedAt > 0 && (now - fetchedAt > STALE_AFTER_MILLIS || now < fetchedAt);
    }

    Rates withFetched(Map<String, BigDecimal> rates, long fetchedAt, long asOf) {
        return new Rates(base, rates, manual, fetchedAt, asOf);
    }

    /** Sets a manual rate, or removes it when {@code rate} is null. */
    Rates withManual(String currency, BigDecimal rate) {
        Map<String, BigDecimal> changed = new TreeMap<>(manual);
        if (rate == null) {
            changed.remove(currency);
        } else {
            if (!validRate(rate)) {
                throw new IllegalArgumentException("汇率超出范围");
            }
            changed.put(currency, rate);
        }
        return new Rates(base, fetched, changed, fetchedAt, asOf);
    }

    /**
     * Switches the base currency, re-expressing known rates through the new base when possible.
     * Rates that cannot be re-expressed are dropped.
     */
    Rates withBase(String newBase) {
        String target = code(newBase);
        if (target.equals(base)) {
            return this;
        }
        BigDecimal pivot = perUnit(target);
        if (pivot == null) {
            return new Rates(target, Collections.emptyMap(), Collections.emptyMap(), fetchedAt, 0);
        }
        Map<String, BigDecimal> newFetched = rebase(fetched, pivot);
        // The old base becomes an ordinary foreign currency. Keep it manual if the pivot was manual.
        BigDecimal oldBase = BigDecimal.ONE.divide(pivot, MathContext.DECIMAL64);
        Map<String, BigDecimal> newManual = rebase(manual, pivot);
        (isManual(target) ? newManual : newFetched).put(base, oldBase);
        return new Rates(target, newFetched, newManual, fetchedAt, asOf);
    }

    private static Map<String, BigDecimal> rebase(Map<String, BigDecimal> rates, BigDecimal pivot) {
        Map<String, BigDecimal> result = new TreeMap<>();
        for (Map.Entry<String, BigDecimal> entry : rates.entrySet()) {
            result.put(entry.getKey(), entry.getValue().divide(pivot, MathContext.DECIMAL64));
        }
        return result;
    }

    private static Map<String, BigDecimal> clean(Map<String, BigDecimal> rates, String base) {
        Map<String, BigDecimal> result = new TreeMap<>();
        for (Map.Entry<String, BigDecimal> entry : rates.entrySet()) {
            String currency;
            try {
                currency = code(entry.getKey());
            } catch (IllegalArgumentException ex) {
                continue;
            }
            if (!currency.equals(base) && validRate(entry.getValue())) {
                result.put(currency, entry.getValue());
            }
        }
        return Collections.unmodifiableMap(result);
    }
}
