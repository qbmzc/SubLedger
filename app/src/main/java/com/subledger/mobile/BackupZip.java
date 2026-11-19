package com.subledger.mobile;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * ZIP backup: {@code ledger.json} plus {@code attachments/<id>.jpg|pdf} (attachments and icon pictures). Reading only accepts
 * those exact names, so a crafted archive cannot write outside the staging directory.
 */
final class BackupZip {
    static final String LEDGER = "ledger.json";
    static final String FOLDER = "attachments/";
    static final long MAX_LEDGER_BYTES = 8L * 1024 * 1024;
    static final long MAX_TOTAL_BYTES = 1024L * 1024 * 1024;

    private BackupZip() {
    }

    /** True when the stream starts like a ZIP file. Reads the first four bytes. */
    static boolean isZip(byte[] head) {
        return head.length >= 4 && head[0] == 'P' && head[1] == 'K' && head[2] == 3 && head[3] == 4;
    }

    /** Writes the ledger JSON and each named file that exists in {@code folder}. */
    static void write(OutputStream out, String ledgerJson, File folder, Collection<String> fileNames)
            throws IOException {
        ZipOutputStream zip = new ZipOutputStream(out);
        zip.putNextEntry(new ZipEntry(LEDGER));
        zip.write(ledgerJson.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
        byte[] buffer = new byte[64 * 1024];
        for (String name : fileNames) {
            File file = new File(folder, name);
            if (!name.matches(Attachment.FILE_PATTERN) || !file.isFile()) {
                continue;
            }
            zip.putNextEntry(new ZipEntry(FOLDER + name));
            try (InputStream in = new FileInputStream(file)) {
                int count;
                while ((count = in.read(buffer)) != -1) {
                    zip.write(buffer, 0, count);
                }
            }
            zip.closeEntry();
        }
        zip.finish();
        zip.flush();
    }

    /**
     * Extracts attachment files into {@code staging} and returns the ledger JSON.
     * Unknown entries are skipped; oversized entries abort the import.
     */
    static String read(InputStream in, File staging) throws IOException {
        if (!staging.isDirectory() && !staging.mkdirs()) {
            throw new IOException("无法创建临时目录");
        }
        String ledger = null;
        long total = 0;
        byte[] buffer = new byte[64 * 1024];
        try (ZipInputStream zip = new ZipInputStream(in)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                String name = entry.getName();
                boolean isLedger = LEDGER.equals(name);
                boolean isFile = name.startsWith(FOLDER)
                        && name.substring(FOLDER.length()).matches(Attachment.FILE_PATTERN);
                if (entry.isDirectory() || !isLedger && !isFile) {
                    continue;
                }
                long limit = isLedger ? MAX_LEDGER_BYTES : Attachment.MAX_BYTES;
                File target = isLedger ? null : new File(staging, name.substring(FOLDER.length()));
                ByteArrayOutputStream json = isLedger ? new ByteArrayOutputStream() : null;
                long size = 0;
                try (OutputStream out = isLedger ? json : new FileOutputStream(target)) {
                    int count;
                    while ((count = zip.read(buffer)) != -1) {
                        size += count;
                        total += count;
                        if (size > limit || total > MAX_TOTAL_BYTES) {
                            throw new IOException("备份文件过大");
                        }
                        out.write(buffer, 0, count);
                    }
                }
                if (isLedger) {
                    ledger = new String(json.toByteArray(), StandardCharsets.UTF_8);
                }
            }
        }
        if (ledger == null) {
            throw new IOException("备份中缺少 " + LEDGER);
        }
        return ledger;
    }
}
