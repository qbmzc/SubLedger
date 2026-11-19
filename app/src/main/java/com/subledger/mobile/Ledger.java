package com.subledger.mobile;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Currency;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Immutable snapshot of everything the user keeps: subscriptions, balance accounts and payment
 * history. All bookkeeping rules live here as pure functions returning a new snapshot.
 */
final class Ledger {
    static final int MAX_SUBSCRIPTIONS = 2000;
    static final int MAX_WALLETS = 100;
    static final int MAX_PAYMENTS = 5000;
    static final int MAX_TOP_UPS = 5000;
    static final int MAX_ATTACHMENTS = 2000;
    static final int MAX_ATTACHMENTS_PER_OWNER = 20;
    /** How far ahead {@link #coverage} simulates charges. */
    static final int COVERAGE_YEARS = 3;

    final List<Subscription> subscriptions;
    final List<Wallet> wallets;
    /** Oldest first. */
    final List<Payment> payments;
    /** Oldest first. Top-ups of deleted wallets are dropped. */
    final List<TopUp> topUps;
    /** Attachments whose owner (subscription, top-up or payment) no longer exists are dropped. */
    final List<Attachment> attachments;

    Ledger(List<Subscription> subscriptions, List<Wallet> wallets, List<Payment> payments,
           List<TopUp> topUps, List<Attachment> attachments) {
        if (subscriptions.size() > MAX_SUBSCRIPTIONS) {
            throw new IllegalArgumentException("最多保存 " + MAX_SUBSCRIPTIONS + " 条订阅");
        }
        if (wallets.size() > MAX_WALLETS) {
            throw new IllegalArgumentException("最多保存 " + MAX_WALLETS + " 个余额账户");
        }
        List<Payment> sorted = new ArrayList<>(payments);
        sorted.sort(Comparator.comparing((Payment payment) -> payment.date));
        // Keep the newest records when the history grows past the cap.
        if (sorted.size() > MAX_PAYMENTS) {
            sorted = new ArrayList<>(sorted.subList(sorted.size() - MAX_PAYMENTS, sorted.size()));
        }
        this.subscriptions = Collections.unmodifiableList(new ArrayList<>(subscriptions));
        this.wallets = Collections.unmodifiableList(new ArrayList<>(wallets));
        this.payments = Collections.unmodifiableList(sorted);

        Set<String> walletIds = new HashSet<>();
        for (Wallet wallet : wallets) {
            walletIds.add(wallet.id);
        }
        List<TopUp> keptTopUps = new ArrayList<>();
        for (TopUp topUp : topUps) {
            if (walletIds.contains(topUp.walletId)) {
                keptTopUps.add(topUp);
            }
        }
        keptTopUps.sort(Comparator.comparing((TopUp topUp) -> topUp.date));
        if (keptTopUps.size() > MAX_TOP_UPS) {
            keptTopUps = new ArrayList<>(keptTopUps.subList(keptTopUps.size() - MAX_TOP_UPS, keptTopUps.size()));
        }
        this.topUps = Collections.unmodifiableList(keptTopUps);

        Set<String> owners = new HashSet<>();
        for (Subscription entry : subscriptions) {
            owners.add(entry.id);
        }
        for (Payment payment : sorted) {
            owners.add(payment.id);
        }
        for (TopUp topUp : keptTopUps) {
            owners.add(topUp.id);
        }
        List<Attachment> keptAttachments = new ArrayList<>();
        for (Attachment attachment : attachments) {
            if (owners.contains(attachment.ownerId)) {
                keptAttachments.add(attachment);
            }
        }
        if (keptAttachments.size() > MAX_ATTACHMENTS) {
            throw new IllegalArgumentException("最多保存 " + MAX_ATTACHMENTS + " 个附件");
        }
        this.attachments = Collections.unmodifiableList(keptAttachments);
    }

    /** Copy with new core lists, keeping top-ups and attachments (orphans are dropped). */
    private Ledger with(List<Subscription> subscriptions, List<Wallet> wallets, List<Payment> payments) {
        return new Ledger(subscriptions, wallets, payments, topUps, attachments);
    }

    /** Records a top-up: adds it to the wallet balance and updates the wallet's unit cost. */
    Ledger topUp(TopUp record) {
        Wallet wallet = wallet(record.walletId);
        if (wallet == null || !wallet.currency.equals(record.currency)) {
            throw new IllegalArgumentException("余额账户不存在或币种不符");
        }
        Map<String, Wallet> walletsById = walletMap();
        walletsById.put(wallet.id, wallet.topUp(record.amount, record.paid, record.paidCurrency));
        List<TopUp> records = new ArrayList<>(topUps);
        records.add(record);
        return new Ledger(subscriptions, new ArrayList<>(walletsById.values()), payments, records, attachments);
    }

    /**
     * Removes a top-up record and takes its amount back off the balance. The unit cost is left
     * unchanged because later charges make the original average impossible to undo exactly.
     */
    Ledger removeTopUp(String id) {
        Map<String, Wallet> walletsById = walletMap();
        List<TopUp> records = new ArrayList<>();
        for (TopUp topUp : topUps) {
            Wallet wallet = walletsById.get(topUp.walletId);
            if (topUp.id.equals(id) && wallet != null) {
                walletsById.put(wallet.id, wallet.withBalance(wallet.balance.subtract(topUp.amount)));
            } else {
                records.add(topUp);
            }
        }
        return new Ledger(subscriptions, new ArrayList<>(walletsById.values()), payments, records, attachments);
    }

    List<TopUp> topUpsOf(String walletId) {
        List<TopUp> result = new ArrayList<>();
        for (TopUp topUp : topUps) {
            if (topUp.walletId.equals(walletId)) {
                result.add(topUp);
            }
        }
        return result;
    }

    Ledger addAttachments(List<Attachment> added) {
        List<Attachment> list = new ArrayList<>(attachments);
        Map<String, Integer> counts = attachmentCounts();
        for (Attachment attachment : added) {
            if (counts.merge(attachment.ownerId, 1, Integer::sum) > MAX_ATTACHMENTS_PER_OWNER) {
                throw new IllegalArgumentException("每条记录最多 " + MAX_ATTACHMENTS_PER_OWNER + " 个附件");
            }
            list.add(attachment);
        }
        return new Ledger(subscriptions, wallets, payments, topUps, list);
    }

    /**
     * Keeps only the attachments and icon pictures whose file name {@code present} accepts, e.g.
     * those whose file came with a backup. Subscriptions with a missing picture fall back to a badge.
     */
    Ledger keepFiles(Predicate<String> present) {
        List<Attachment> list = new ArrayList<>(attachments);
        list.removeIf(attachment -> !present.test(attachment.fileName()));
        List<Subscription> entries = new ArrayList<>();
        for (Subscription entry : subscriptions) {
            if (entry.iconFile() != null && !present.test(entry.iconFile())) {
                Subscription.Builder b = entry.toBuilder();
                b.iconImage = null;
                entry = b.build();
            }
            entries.add(entry);
        }
        return new Ledger(entries, wallets, payments, topUps, list);
    }

    /** Names of every stored file the ledger refers to: attachments and icon pictures. */
    Set<String> fileNames() {
        Set<String> names = new LinkedHashSet<>();
        for (Attachment attachment : attachments) {
            names.add(attachment.fileName());
        }
        for (Subscription entry : subscriptions) {
            if (entry.iconFile() != null) {
                names.add(entry.iconFile());
            }
        }
        return names;
    }

    Ledger removeAttachment(String id) {
        List<Attachment> list = new ArrayList<>(attachments);
        list.removeIf(attachment -> attachment.id.equals(id));
        return new Ledger(subscriptions, wallets, payments, topUps, list);
    }

    Attachment attachment(String id) {
        for (Attachment attachment : attachments) {
            if (attachment.id.equals(id)) {
                return attachment;
            }
        }
        return null;
    }

    List<Attachment> attachmentsOf(String ownerId) {
        List<Attachment> result = new ArrayList<>();
        for (Attachment attachment : attachments) {
            if (attachment.ownerId.equals(ownerId)) {
                result.add(attachment);
            }
        }
        return result;
    }

    Map<String, Integer> attachmentCounts() {
        Map<String, Integer> counts = new HashMap<>();
        for (Attachment attachment : attachments) {
            counts.merge(attachment.ownerId, 1, Integer::sum);
        }
        return counts;
    }

    static Ledger empty() {
        return new Ledger(Collections.emptyList(), Collections.emptyList(), Collections.emptyList(),
                Collections.emptyList(), Collections.emptyList());
    }

    Subscription subscription(String id) {
        for (Subscription entry : subscriptions) {
            if (entry.id.equals(id)) {
                return entry;
            }
        }
        return null;
    }

    Wallet wallet(String id) {
        if (id == null) {
            return null;
        }
        for (Wallet wallet : wallets) {
            if (wallet.id.equals(id)) {
                return wallet;
            }
        }
        return null;
    }

    /** The wallet that pays {@code entry}, only when its currency matches the bill. */
    Wallet payingWallet(Subscription entry) {
        Wallet wallet = wallet(entry.walletId);
        return wallet != null && wallet.currency.equals(entry.currency) ? wallet : null;
    }

    /** An amount with its currency. */
    static final class Money {
        final BigDecimal amount;
        final String currency;

        Money(BigDecimal amount, String currency) {
            this.amount = amount;
            this.currency = currency;
        }
    }

    /**
     * What one billing period of {@code entry} really costs, before splitting: the explicit actual
     * cost, else the paying wallet's unit cost, else the bill itself.
     */
    Money realCost(Subscription entry) {
        if (entry.hasCost()) {
            return new Money(entry.cost, entry.costCurrency);
        }
        Wallet wallet = payingWallet(entry);
        if (wallet != null && wallet.unitCost != null) {
            return new Money(wallet.costOf(entry.amount), wallet.costCurrency);
        }
        return new Money(entry.amount, entry.currency);
    }

    /** Real cost of one charge, converted to the base currency now when possible, so it stays fixed. */
    private Money frozenCost(Subscription entry, Rates rates) {
        Money real = realCost(entry);
        BigDecimal converted = rates.toBase(real.amount, real.currency);
        if (real.currency.equals(rates.base) || converted == null) {
            return real;
        }
        int digits = Math.max(0, Currency.getInstance(rates.base).getDefaultFractionDigits());
        return new Money(converted.setScale(digits, RoundingMode.HALF_UP), rates.base);
    }

    /**
     * Records auto-renewal charges that fell due after {@code recordedThrough}, up to today,
     * deducting them from the paying wallet. Subscriptions that are not tracked yet start tracking
     * today without backfilling. Returns {@code this} when nothing changed.
     */
    Ledger recordDue(Rates rates, LocalDate today) {
        Map<String, Wallet> walletsById = walletMap();
        Set<String> known = paymentIds();
        List<Subscription> updated = new ArrayList<>();
        List<Payment> added = new ArrayList<>();
        boolean changed = false;
        for (Subscription entry : subscriptions) {
            if (entry.mode != Subscription.Mode.AUTO
                    || (entry.recordedThrough != null && !entry.recordedThrough.isBefore(today))) {
                updated.add(entry);
                continue;
            }
            Subscription.Builder next = entry.toBuilder();
            if (entry.recordedThrough != null) {
                LocalDate from = entry.recordedThrough.plusDays(1);
                for (long index = entry.indexOnOrAfter(from); ; index++) {
                    LocalDate date = entry.occurrence(index);
                    if (date.isAfter(today)) {
                        break;
                    }
                    if (known.add(Payment.idFor(entry.id, date))) {
                        added.add(charge(entry, date, date, walletsById, rates, true));
                    }
                }
            }
            // The first charge ends a free trial.
            next.trial = entry.trial && entry.anchor.isAfter(today);
            next.recordedThrough = today;
            updated.add(next.build());
            changed = true;
        }
        if (!changed) {
            return this;
        }
        List<Payment> history = new ArrayList<>(payments);
        history.addAll(added);
        return with(updated, new ArrayList<>(walletsById.values()), history);
    }

    /**
     * Records the pending renewal of a manual subscription: adds the payment, deducts the wallet,
     * ends any trial and moves the renewal date one cycle forward.
     */
    Ledger recordManualRenewal(String subscriptionId, Rates rates, LocalDate today) {
        Subscription entry = subscription(subscriptionId);
        if (entry == null) {
            return this;
        }
        Map<String, Wallet> walletsById = walletMap();
        List<Payment> history = new ArrayList<>(payments);
        if (!paymentIds().contains(Payment.idFor(entry.id, entry.anchor))) {
            LocalDate paidOn = entry.anchor.isAfter(today) ? today : entry.anchor;
            history.add(charge(entry, entry.anchor, paidOn, walletsById, rates, false));
        }
        Subscription.Builder next = entry.toBuilder();
        next.anchor = entry.occurrence(1);
        next.trial = false;
        return with(replace(subscriptions, next.build()), new ArrayList<>(walletsById.values()), history);
    }

    /** Builds the payment for one renewal and deducts it from the paying wallet in {@code walletsById}. */
    private Payment charge(Subscription entry, LocalDate renewal, LocalDate paidOn,
                           Map<String, Wallet> walletsById, Rates rates, boolean auto) {
        Money cost = frozenCost(entry, rates);
        Wallet wallet = payingWallet(entry);
        if (wallet != null) {
            Wallet current = walletsById.get(wallet.id);
            walletsById.put(wallet.id, current.withBalance(current.balance.subtract(entry.amount)));
        }
        return new Payment(Payment.idFor(entry.id, renewal), entry.id, entry.title, paidOn,
                entry.amount, entry.currency, cost.amount, cost.currency, entry.splitWays,
                wallet == null ? null : wallet.id, auto);
    }

    /** Adds a payment entered by hand. It does not touch any wallet. */
    Ledger addPayment(Payment payment) {
        List<Payment> history = new ArrayList<>(payments);
        history.removeIf(existing -> existing.id.equals(payment.id));
        history.add(payment);
        return with(subscriptions, wallets, history);
    }

    /** Fixed real cost for a hand-entered charge of {@code entry}. */
    Money costForManualPayment(Subscription entry, Rates rates) {
        return frozenCost(entry, rates);
    }

    /** Removes a payment, optionally returning its amount to the wallet it was deducted from. */
    Ledger removePayment(String id, boolean refund) {
        Map<String, Wallet> walletsById = walletMap();
        List<Payment> history = new ArrayList<>();
        for (Payment payment : payments) {
            if (!payment.id.equals(id)) {
                history.add(payment);
                continue;
            }
            Wallet wallet = payment.walletId == null ? null : walletsById.get(payment.walletId);
            if (refund && wallet != null && wallet.currency.equals(payment.currency)) {
                walletsById.put(wallet.id, wallet.withBalance(wallet.balance.add(payment.amount)));
            }
        }
        return with(subscriptions, new ArrayList<>(walletsById.values()), history);
    }

    /**
     * Adds or replaces a subscription. New subscriptions start auto-recording from yesterday, so a
     * renewal dated today is still recorded.
     */
    Ledger upsert(Subscription entry, LocalDate today) {
        Subscription existing = subscription(entry.id);
        Subscription.Builder next = entry.toBuilder();
        // Start (or restart) auto-recording from yesterday; never backfill older charges.
        boolean startsTracking = existing == null || existing.mode != Subscription.Mode.AUTO
                || existing.recordedThrough == null;
        next.recordedThrough = startsTracking ? today.minusDays(1) : existing.recordedThrough;
        if (existing == null) {
            List<Subscription> list = new ArrayList<>(subscriptions);
            list.add(next.build());
            return with(list, wallets, payments);
        }
        return with(replace(subscriptions, next.build()), wallets, payments);
    }

    /** Removes a subscription. Its payment history stays. */
    Ledger removeSubscription(String id) {
        List<Subscription> list = new ArrayList<>(subscriptions);
        list.removeIf(entry -> entry.id.equals(id));
        return with(list, wallets, payments);
    }

    Ledger upsertWallet(Wallet wallet) {
        Map<String, Wallet> walletsById = walletMap();
        walletsById.put(wallet.id, wallet);
        return with(subscriptions, new ArrayList<>(walletsById.values()), payments);
    }

    /** Removes a wallet and unlinks the subscriptions paid from it. */
    Ledger removeWallet(String id) {
        List<Subscription> list = new ArrayList<>();
        for (Subscription entry : subscriptions) {
            if (id.equals(entry.walletId)) {
                Subscription.Builder next = entry.toBuilder();
                next.walletId = null;
                list.add(next.build());
            } else {
                list.add(entry);
            }
        }
        List<Wallet> remaining = new ArrayList<>(wallets);
        remaining.removeIf(wallet -> wallet.id.equals(id));
        return with(list, remaining, payments);
    }

    /** Merges a backup: records with the same id are replaced by the backup's version. */
    Ledger merge(Ledger incoming) {
        return new Ledger(mergeById(subscriptions, incoming.subscriptions, entry -> entry.id),
                mergeById(wallets, incoming.wallets, wallet -> wallet.id),
                mergeById(payments, incoming.payments, payment -> payment.id),
                mergeById(topUps, incoming.topUps, topUp -> topUp.id),
                mergeById(attachments, incoming.attachments, attachment -> attachment.id));
    }

    /** Upcoming charges a wallet can pay, simulated from today. */
    static final class Coverage {
        /** Subscriptions paid from the wallet that are still running. */
        final int linked;
        /** Charges the current balance can pay in a row. */
        final int payable;
        /** Next charge, or null when nothing is scheduled. */
        final LocalDate next;
        final BigDecimal nextAmount;
        /** Date of the last payable charge, or null when the next one cannot be paid. */
        final LocalDate paidUntil;
        /** True when every charge within {@link #COVERAGE_YEARS} can be paid. */
        final boolean beyondHorizon;

        Coverage(int linked, int payable, LocalDate next, BigDecimal nextAmount, LocalDate paidUntil,
                 boolean beyondHorizon) {
            this.linked = linked;
            this.payable = payable;
            this.next = next;
            this.nextAmount = nextAmount;
            this.paidUntil = paidUntil;
            this.beyondHorizon = beyondHorizon;
        }

        boolean insufficient() {
            return next != null && payable == 0;
        }
    }

    Coverage coverage(Wallet wallet, LocalDate today) {
        LocalDate horizon = today.plusYears(COVERAGE_YEARS);
        List<Money> charges = new ArrayList<>();
        List<LocalDate> dates = new ArrayList<>();
        int linked = 0;
        for (Subscription entry : subscriptions) {
            Wallet paying = payingWallet(entry);
            if (entry.mode == Subscription.Mode.ENDED || paying == null || !paying.id.equals(wallet.id)) {
                continue;
            }
            linked++;
            long index;
            if (entry.mode == Subscription.Mode.MANUAL) {
                index = 0;
            } else {
                LocalDate from = entry.recordedThrough != null && !entry.recordedThrough.isBefore(today)
                        ? entry.recordedThrough.plusDays(1) : today;
                index = entry.indexOnOrAfter(from);
            }
            for (; ; index++) {
                LocalDate date = entry.occurrence(index);
                if (date.isAfter(horizon) || dates.size() > 5000) {
                    break;
                }
                dates.add(date);
                charges.add(new Money(entry.amount, entry.currency));
            }
        }
        if (dates.isEmpty()) {
            return new Coverage(linked, 0, null, null, null, false);
        }
        List<Integer> order = new ArrayList<>();
        for (int i = 0; i < dates.size(); i++) {
            order.add(i);
        }
        order.sort(Comparator.comparing(dates::get));
        BigDecimal left = wallet.balance;
        int payable = 0;
        LocalDate paidUntil = null;
        for (int i : order) {
            BigDecimal amount = charges.get(i).amount;
            if (left.compareTo(amount) < 0) {
                break;
            }
            left = left.subtract(amount);
            payable++;
            paidUntil = dates.get(i);
        }
        int first = order.get(0);
        return new Coverage(linked, payable, dates.get(first), charges.get(first).amount,
                paidUntil, payable == order.size());
    }

    private Map<String, Wallet> walletMap() {
        Map<String, Wallet> map = new LinkedHashMap<>();
        for (Wallet wallet : wallets) {
            map.put(wallet.id, wallet);
        }
        return map;
    }

    private Set<String> paymentIds() {
        Set<String> ids = new HashSet<>();
        for (Payment payment : payments) {
            ids.add(payment.id);
        }
        return ids;
    }

    private static List<Subscription> replace(List<Subscription> list, Subscription entry) {
        List<Subscription> result = new ArrayList<>();
        for (Subscription existing : list) {
            result.add(existing.id.equals(entry.id) ? entry : existing);
        }
        return result;
    }

    private interface Key<T> {
        String of(T value);
    }

    private static <T> List<T> mergeById(List<T> current, List<T> incoming, Key<T> key) {
        Map<String, T> byId = new LinkedHashMap<>();
        for (T value : current) {
            byId.put(key.of(value), value);
        }
        for (T value : incoming) {
            byId.put(key.of(value), value);
        }
        return new ArrayList<>(byId.values());
    }
}
