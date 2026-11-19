package com.subledger.mobile;

import java.time.LocalDate;
import java.util.UUID;

/** An image or PDF kept with a subscription, top-up or payment. The file lives in app storage. */
final class Attachment {
    static final String JPEG = "image/jpeg";
    static final String PDF = "application/pdf";
    static final long MAX_BYTES = 25L * 1024 * 1024;
    /** File names accepted from backups; anything else is ignored to prevent path traversal. */
    static final String FILE_PATTERN = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\.(jpg|pdf)";

    final String id, ownerId, mime, name;
    final LocalDate added;
    final long size;

    Attachment(String id, String ownerId, String mime, String name, LocalDate added, long size) {
        UUID.fromString(id);
        UUID.fromString(ownerId);
        if (!JPEG.equals(mime) && !PDF.equals(mime)) {
            throw new IllegalArgumentException("只支持图片和 PDF");
        }
        if (size < 0 || size > MAX_BYTES || added.getYear() < 1900 || added.getYear() > 9998) {
            throw new IllegalArgumentException("附件超出范围");
        }
        String trimmed = name.trim();
        this.id = id.toLowerCase(java.util.Locale.ROOT);
        this.ownerId = ownerId;
        this.mime = mime;
        this.name = trimmed.isEmpty() ? "附件" : trimmed.length() > 120 ? trimmed.substring(0, 120) : trimmed;
        this.added = added;
        this.size = size;
    }

    boolean isPdf() {
        return PDF.equals(mime);
    }

    String fileName() {
        return id + (isPdf() ? ".pdf" : ".jpg");
    }
}
