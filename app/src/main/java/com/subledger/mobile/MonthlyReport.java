package com.subledger.mobile;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Currency;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * A month's statement for reimbursement or bookkeeping: a ZIP with a CSV of that month's payments
 * and top-ups, plus their invoices under readable names such as
 * {@code 发票/2026-11-12 Claude Pro USD 20.pdf}. Not a backup; it cannot be imported.
 */
final class MonthlyReport {
    static final String INVOICES = "发票/";

    final YearMonth month;
    final List<Payment> payments = new ArrayList<>();
    final List<TopUp> topUps = new ArrayList<>();
    /** Exported file name (inside the ZIP) for each attachment, in row order. */
    final Map<Attachment, String> files = new LinkedHashMap<>();
    /** The user's share of this month's payments in the base currency, and how many lack a rate. */
    final BigDecimal total;
    final int unconverted;
    private final Ledger ledger;
    private final Rates rates;

    MonthlyReport(Ledger ledger, Rates rates, YearMonth month) {
        this.ledger = ledger;
        this.rates = rates;
        this.month = month;
        for (Payment payment : ledger.payments) {
            if (YearMonth.from(payment.date).equals(month)) {
                payments.add(payment);
            }
        }
        for (TopUp topUp : ledger.topUps) {
            if (YearMonth.from(topUp.date).equals(month)) {
                topUps.add(topUp);
            }
        }
        Set<String> used = new HashSet<>();
        BigDecimal sum = BigDecimal.ZERO;
        int missing = 0;
        for (Payment payment : payments) {
            BigDecimal mine = payment.mine(rates);
            if (mine == null) {
                missing++;
            } else {
                sum = sum.add(mine);
            }
            name(payment.id, payment.date + " " + payment.title + " "
                    + payment.currency + " " + payment.amount.toPlainString(), used);
        }
        for (TopUp topUp : topUps) {
            Wallet wallet = ledger.wallet(topUp.walletId);
            name(topUp.id, topUp.date + " 充值 " + (wallet == null ? "" : wallet.name + " ")
                    + topUp.currency + " " + topUp.amount.toPlainString(), used);
        }
        total = sum;
        unconverted = missing;
    }

    /** Months that have payments or top-ups, newest first. */
    static List<YearMonth> months(Ledger ledger) {
        TreeSet<YearMonth> months = new TreeSet<>();
        for (Payment payment : ledger.payments) {
            months.add(YearMonth.from(payment.date));
        }
        for (TopUp topUp : ledger.topUps) {
            months.add(YearMonth.from(topUp.date));
        }
        return new ArrayList<>(months.descendingSet());
    }

    int records() {
        return payments.size() + topUps.size();
    }

    String zipName() {
        return "账单-" + month + ".zip";
    }

    private void name(String ownerId, String base, Set<String> used) {
        String stem = safe(base);
        for (Attachment attachment : ledger.attachmentsOf(ownerId)) {
            String extension = attachment.isPdf() ? ".pdf" : ".jpg";
            String name = stem + extension;
            for (int i = 2; !used.add(name.toLowerCase(Locale.ROOT)); i++) {
                name = stem + " (" + i + ")" + extension;
            }
            files.put(attachment, name);
        }
    }

    /** Replaces characters that are not allowed in file names on common systems. */
    static String safe(String name) {
        String cleaned = name.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_").trim();
        return cleaned.length() > 80 ? cleaned.substring(0, 80).trim() : cleaned;
    }

    /** UTF-8 CSV with a byte order mark so Excel shows Chinese text correctly. */
    String csv() {
        StringBuilder out = new StringBuilder("\uFEFF");
        row(out, "日期", "类型", "名称", "账单金额", "账单币种", "实际成本", "成本币种", "分摊人数",
                "我承担（" + rates.base + "）", "余额账户", "记录方式", "附件");
        for (Payment payment : payments) {
            BigDecimal mine = payment.mine(rates);
            Wallet wallet = payment.walletId == null ? null : ledger.wallet(payment.walletId);
            row(out, payment.date.toString(), "扣款", payment.title, payment.amount.toPlainString(), payment.currency,
                    payment.cost.toPlainString(), payment.costCurrency, Integer.toString(payment.splitWays),
                    mine == null ? "缺少汇率" : plain(rates.base, mine),
                    wallet == null ? "" : wallet.name, payment.auto ? "自动" : "手动", filesOf(payment.id));
        }
        for (TopUp topUp : topUps) {
            Wallet wallet = ledger.wallet(topUp.walletId);
            row(out, topUp.date.toString(), "充值", wallet == null ? "" : wallet.name,
                    topUp.amount.toPlainString(), topUp.currency,
                    topUp.paid == null ? "" : topUp.paid.toPlainString(),
                    topUp.paidCurrency == null ? "" : topUp.paidCurrency, "", "", wallet == null ? "" : wallet.name,
                    "", filesOf(topUp.id));
        }
        row(out, "合计", "", "扣款 " + payments.size() + " 笔" + (unconverted > 0 ? "，" + unconverted + " 笔缺少汇率未计入" : ""),
                "", "", "", "", "", plain(rates.base, total), "", "", "");
        return out.toString();
    }

    private String filesOf(String ownerId) {
        List<String> names = new ArrayList<>();
        for (Map.Entry<Attachment, String> file : files.entrySet()) {
            if (file.getKey().ownerId.equals(ownerId)) {
                names.add(file.getValue());
            }
        }
        return String.join("; ", names);
    }

    private static void row(StringBuilder out, String... cells) {
        for (int i = 0; i < cells.length; i++) {
            if (i > 0) {
                out.append(',');
            }
            out.append(cell(cells[i]));
        }
        out.append("\r\n");
    }

    /** Quotes when needed and defuses spreadsheet formulas in user-entered text. */
    static String cell(String value) {
        String text = value;
        if (!text.isEmpty() && "=+-@\t\r".indexOf(text.charAt(0)) >= 0 && !text.matches("-?[0-9]+(\\.[0-9]+)?")) {
            text = "'" + text;
        }
        if (text.indexOf(',') >= 0 || text.indexOf('"') >= 0 || text.indexOf('\n') >= 0 || text.indexOf('\r') >= 0) {
            text = "\"" + text.replace("\"", "\"\"") + "\"";
        }
        return text;
    }

    /** Amount rounded to the currency's usual decimals, without the code. */
    private static String plain(String code, BigDecimal value) {
        int digits = Math.max(0, Currency.getInstance(code).getDefaultFractionDigits());
        return value.setScale(digits, RoundingMode.HALF_UP).toPlainString();
    }

    /** Writes the ZIP. Attachments whose file is missing from {@code folder} are skipped. Returns files written. */
    int write(OutputStream out, File folder) throws IOException {
        ZipOutputStream zip = new ZipOutputStream(out);
        zip.putNextEntry(new ZipEntry("账单-" + month + ".csv"));
        zip.write(csv().getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
        int written = 0;
        byte[] buffer = new byte[64 * 1024];
        for (Map.Entry<Attachment, String> file : files.entrySet()) {
            File source = new File(folder, file.getKey().fileName());
            if (!source.isFile()) {
                continue;
            }
            zip.putNextEntry(new ZipEntry(INVOICES + file.getValue()));
            try (InputStream in = new FileInputStream(source)) {
                int count;
                while ((count = in.read(buffer)) != -1) {
                    zip.write(buffer, 0, count);
                }
            }
            zip.closeEntry();
            written++;
        }
        zip.finish();
        zip.flush();
        return written;
    }
}
