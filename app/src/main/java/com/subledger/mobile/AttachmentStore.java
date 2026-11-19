package com.subledger.mobile;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.media.ExifInterface;
import android.net.Uri;
import android.provider.OpenableColumns;
import android.util.LruCache;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * Attachment files and subscription icon pictures in app-private storage ({@code files/attachments}). Picked documents are first
 * written to {@code cache/incoming} on a background thread and moved in on the UI thread right
 * before the ledger is saved, so {@link #prune} never deletes a file that is still being added.
 */
final class AttachmentStore {
    private static final int MAX_EDGE = 2400;
    static final int ICON_EDGE = 256;
    private static final LruCache<String, Bitmap> THUMBNAILS = new LruCache<String, Bitmap>(8 * 1024 * 1024) {
        @Override
        protected int sizeOf(String key, Bitmap value) {
            return value.getByteCount();
        }
    };

    private AttachmentStore() {
    }

    static File folder(Context context) {
        File folder = new File(context.getFilesDir(), "attachments");
        folder.mkdirs();
        return folder;
    }

    static File file(Context context, Attachment attachment) {
        return new File(folder(context), attachment.fileName());
    }

    private static File incoming(Context context) {
        File folder = new File(context.getCacheDir(), "incoming");
        folder.mkdirs();
        return folder;
    }

    /**
     * Copies a picked image or PDF into the incoming folder. Images are re-encoded as JPEG with the
     * longest edge at most {@value #MAX_EDGE} px. Blocking; call off the main thread.
     */
    static Attachment copyIn(Context context, Uri uri, String ownerId) throws IOException {
        ContentResolver resolver = context.getContentResolver();
        String name = displayName(resolver, uri);
        String type = resolver.getType(uri);
        boolean pdf = Attachment.PDF.equals(type) || name.toLowerCase(Locale.ROOT).endsWith(".pdf");
        String id = UUID.randomUUID().toString();
        File target = new File(incoming(context), id + (pdf ? ".pdf" : ".jpg"));
        try {
            if (pdf) {
                copyPdf(resolver, uri, target);
            } else {
                copyImage(resolver, uri, target);
            }
            return new Attachment(id, ownerId, pdf ? Attachment.PDF : Attachment.JPEG, name,
                    LocalDate.now(), target.length());
        } catch (IOException | RuntimeException ex) {
            target.delete();
            throw ex instanceof IOException ? (IOException) ex : new IOException(ex);
        }
    }

    private static void copyPdf(ContentResolver resolver, Uri uri, File target) throws IOException {
        try (InputStream in = resolver.openInputStream(uri); OutputStream out = new FileOutputStream(target)) {
            if (in == null) {
                throw new IOException("无法打开文件");
            }
            byte[] buffer = new byte[64 * 1024];
            long total = 0;
            int count;
            boolean first = true;
            while ((count = in.read(buffer)) != -1) {
                if (first && (count < 4 || buffer[0] != '%' || buffer[1] != 'P' || buffer[2] != 'D' || buffer[3] != 'F')) {
                    throw new IOException("不是有效的 PDF");
                }
                first = false;
                total += count;
                if (total > Attachment.MAX_BYTES) {
                    throw new IOException("文件超过 25 MB");
                }
                out.write(buffer, 0, count);
            }
        }
    }

    private static void copyImage(ContentResolver resolver, Uri uri, File target) throws IOException {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        try (InputStream in = resolver.openInputStream(uri)) {
            BitmapFactory.decodeStream(in, null, bounds);
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            throw new IOException("不是图片");
        }
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = sampleSize(Math.max(bounds.outWidth, bounds.outHeight), MAX_EDGE);
        Bitmap bitmap;
        try (InputStream in = resolver.openInputStream(uri)) {
            bitmap = BitmapFactory.decodeStream(in, null, options);
        }
        if (bitmap == null) {
            throw new IOException("无法读取图片");
        }
        int rotation = 0;
        try (InputStream in = resolver.openInputStream(uri)) {
            if (in != null) {
                int orientation = new ExifInterface(in).getAttributeInt(ExifInterface.TAG_ORIENTATION,
                        ExifInterface.ORIENTATION_NORMAL);
                rotation = orientation == ExifInterface.ORIENTATION_ROTATE_90 ? 90
                        : orientation == ExifInterface.ORIENTATION_ROTATE_180 ? 180
                        : orientation == ExifInterface.ORIENTATION_ROTATE_270 ? 270 : 0;
            }
        } catch (IOException ignored) {
            // Images without EXIF data are stored as decoded.
        }
        float scale = Math.min(1f, MAX_EDGE / (float) Math.max(bitmap.getWidth(), bitmap.getHeight()));
        if (scale < 1f || rotation != 0) {
            Matrix matrix = new Matrix();
            matrix.postScale(scale, scale);
            matrix.postRotate(rotation);
            Bitmap scaled = Bitmap.createBitmap(bitmap, 0, 0, bitmap.getWidth(), bitmap.getHeight(), matrix, true);
            if (scaled != bitmap) {
                bitmap.recycle();
                bitmap = scaled;
            }
        }
        try (OutputStream out = new FileOutputStream(target)) {
            if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 85, out)) {
                throw new IOException("无法保存图片");
            }
        } finally {
            bitmap.recycle();
        }
        if (target.length() > Attachment.MAX_BYTES) {
            throw new IOException("文件超过 25 MB");
        }
    }

    /**
     * Copies a picked image into the incoming folder as a square {@value #ICON_EDGE} px JPEG,
     * cropped around the centre. Returns the icon id. Blocking; call off the main thread.
     */
    static String copyIcon(Context context, Uri uri) throws IOException {
        ContentResolver resolver = context.getContentResolver();
        String id = UUID.randomUUID().toString();
        File target = incomingIcon(context, id);
        try {
            copyImage(resolver, uri, target);
            Bitmap full = BitmapFactory.decodeFile(target.getPath());
            if (full == null) {
                throw new IOException("无法读取图片");
            }
            int edge = Math.min(full.getWidth(), full.getHeight());
            Bitmap square = Bitmap.createBitmap(full, (full.getWidth() - edge) / 2, (full.getHeight() - edge) / 2,
                    edge, edge);
            Bitmap icon = Bitmap.createScaledBitmap(square, Math.min(edge, ICON_EDGE), Math.min(edge, ICON_EDGE), true);
            try (OutputStream out = new FileOutputStream(target)) {
                if (!icon.compress(Bitmap.CompressFormat.JPEG, 90, out)) {
                    throw new IOException("无法保存图片");
                }
            }
            return id;
        } catch (IOException | RuntimeException ex) {
            target.delete();
            throw ex instanceof IOException ? (IOException) ex : new IOException(ex);
        }
    }

    private static File incomingIcon(Context context, String id) {
        return new File(incoming(context), id + ".jpg");
    }

    /** Moves a picked icon into storage; call right before saving the subscription that uses it. */
    static boolean moveIcon(Context context, String id) {
        File target = new File(folder(context), id + ".jpg");
        return target.isFile() || incomingIcon(context, id).renameTo(target);
    }

    /** Deletes a picked icon that was not saved. */
    static void discardIcon(Context context, String id) {
        incomingIcon(context, id).delete();
    }

    /**
     * The icon picture for a subscription: a stored one, or a just-picked one still in the
     * incoming folder. Null when there is none or it cannot be read.
     */
    static Bitmap icon(Context context, String id) {
        if (id == null) {
            return null;
        }
        String name = id + ".jpg";
        Bitmap cached = THUMBNAILS.get(name);
        if (cached != null) {
            return cached;
        }
        File file = new File(folder(context), name);
        if (!file.isFile()) {
            file = incomingIcon(context, id);
        }
        Bitmap bitmap = BitmapFactory.decodeFile(file.getPath());
        if (bitmap != null) {
            THUMBNAILS.put(name, bitmap);
        }
        return bitmap;
    }

    /** Moves freshly copied files from the incoming folder into attachment storage. */
    static boolean moveIn(Context context, List<Attachment> attachments) {
        boolean all = true;
        for (Attachment attachment : attachments) {
            File source = new File(incoming(context), attachment.fileName());
            all &= source.renameTo(file(context, attachment));
        }
        return all;
    }

    /** Moves files extracted from a backup into attachment storage, replacing same-named files. */
    static void moveFrom(Context context, File staging) {
        File[] files = staging.listFiles();
        for (int i = 0; files != null && i < files.length; i++) {
            File target = new File(folder(context), files[i].getName());
            target.delete();
            THUMBNAILS.remove(files[i].getName());
            files[i].renameTo(target);
        }
    }

    static void deleteTree(File folder) {
        File[] files = folder.listFiles();
        for (int i = 0; files != null && i < files.length; i++) {
            if (files[i].isDirectory()) {
                deleteTree(files[i]);
            } else {
                files[i].delete();
            }
        }
        folder.delete();
    }

    /** Deletes stored files that no attachment or icon in {@code ledger} refers to. */
    static void prune(Context context, Ledger ledger) {
        Set<String> keep = ledger.fileNames();
        File[] files = folder(context).listFiles();
        for (int i = 0; files != null && i < files.length; i++) {
            if (files[i].isFile() && !keep.contains(files[i].getName())) {
                files[i].delete();
                THUMBNAILS.remove(files[i].getName());
            }
        }
    }

    /** Square-ish preview for an image attachment, or null when it cannot be read. */
    static Bitmap thumbnail(Context context, Attachment attachment, int size) {
        Bitmap cached = THUMBNAILS.get(attachment.fileName());
        if (cached != null) {
            return cached;
        }
        Bitmap bitmap = decode(file(context, attachment), size);
        if (bitmap != null) {
            THUMBNAILS.put(attachment.fileName(), bitmap);
        }
        return bitmap;
    }

    /** Decodes an image so its shorter edge is at least {@code minEdge} px when possible. */
    static Bitmap decode(File file, int minEdge) {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(file.getPath(), bounds);
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            return null;
        }
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = sampleSize(Math.min(bounds.outWidth, bounds.outHeight), minEdge);
        return BitmapFactory.decodeFile(file.getPath(), options);
    }

    /** Largest power of two that keeps {@code edge / sample >= target}. */
    private static int sampleSize(int edge, int target) {
        int sample = 1;
        while (edge / (sample * 2) >= target) {
            sample *= 2;
        }
        return sample;
    }

    private static String displayName(ContentResolver resolver, Uri uri) {
        try (Cursor cursor = resolver.query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst() && !cursor.isNull(0)) {
                return cursor.getString(0);
            }
        } catch (RuntimeException ignored) {
            // Some providers do not support queries; fall back to a generic name.
        }
        return "附件";
    }
}
