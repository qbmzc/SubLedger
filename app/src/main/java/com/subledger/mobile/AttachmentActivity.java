package com.subledger.mobile;

import android.app.AlertDialog;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.pdf.PdfRenderer;
import android.os.ParcelFileDescriptor;
import android.widget.ImageView;
import android.widget.LinearLayout;

import java.io.File;
import java.io.IOException;
import java.util.Locale;

/** Full-screen viewer for one attachment: images scale to the screen width, PDFs show their first pages. */
public final class AttachmentActivity extends LedgerActivity {
    static final String ID = "attachment";
    private static final int MAX_PDF_PAGES = 5;

    @Override
    protected void onResume() {
        super.onResume();
        render();
    }

    private void render() {
        Attachment attachment = ledgerStore().load().attachment(getIntent().getStringExtra(ID));
        if (attachment == null) {
            finish();
            return;
        }
        LinearLayout root = screen("附件", action("删除", colors.line, colors.amber, () -> confirmDelete(attachment)));
        root.addView(label(attachment.name, 16, colors.ink, true));
        gap(root, 2);
        root.addView(label(attachment.added + " 添加 · " + size(attachment.size)
                + (attachment.isPdf() ? " · PDF" : ""), 12, colors.muted, false));
        gap(root, 12);
        File file = AttachmentStore.file(this, attachment);
        int width = getResources().getDisplayMetrics().widthPixels - dp(40);
        if (!file.isFile()) {
            root.addView(label("文件缺失。可能是从不含附件的 JSON 备份导入的。", 14, colors.muted, false));
        } else if (attachment.isPdf()) {
            renderPdf(root, file, width);
        } else {
            Bitmap bitmap = AttachmentStore.decode(file, Math.min(width, 1440));
            if (bitmap == null) {
                root.addView(label("无法打开这张图片", 14, colors.muted, false));
            } else {
                root.addView(image(bitmap), new LinearLayout.LayoutParams(-1, -2));
            }
        }
    }

    private void renderPdf(LinearLayout root, File file, int width) {
        int pageWidth = Math.min(width, 1080);
        try (ParcelFileDescriptor descriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
             PdfRenderer renderer = new PdfRenderer(descriptor)) {
            int pages = Math.min(renderer.getPageCount(), MAX_PDF_PAGES);
            for (int i = 0; i < pages; i++) {
                try (PdfRenderer.Page page = renderer.openPage(i)) {
                    int height = Math.max(1, Math.round(pageWidth * page.getHeight() / (float) page.getWidth()));
                    Bitmap bitmap = Bitmap.createBitmap(pageWidth, height, Bitmap.Config.ARGB_8888);
                    bitmap.eraseColor(Color.WHITE);
                    page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
                    if (i > 0) {
                        gap(root, 10);
                    }
                    root.addView(image(bitmap), new LinearLayout.LayoutParams(-1, -2));
                }
            }
            if (renderer.getPageCount() > pages) {
                gap(root, 10);
                root.addView(label("共 " + renderer.getPageCount() + " 页，仅显示前 " + pages + " 页",
                        12, colors.muted, false));
            }
        } catch (IOException | RuntimeException ex) {
            root.addView(label("无法打开这个 PDF", 14, colors.muted, false));
        }
    }

    private ImageView image(Bitmap bitmap) {
        ImageView image = new ImageView(this);
        image.setImageBitmap(bitmap);
        image.setAdjustViewBounds(true);
        image.setScaleType(ImageView.ScaleType.FIT_CENTER);
        image.setBackground(shape(colors.card, 12));
        image.setClipToOutline(true);
        return image;
    }

    private void confirmDelete(Attachment attachment) {
        new AlertDialog.Builder(this).setTitle("删除附件")
                .setMessage("确定删除“" + attachment.name + "”？")
                .setNegativeButton("取消", null)
                .setPositiveButton("删除", (dialog, button) -> {
                    if (commit(() -> ledgerStore().load().removeAttachment(attachment.id))) {
                        finish();
                    }
                }).show();
    }

    private static String size(long bytes) {
        return bytes >= 1024 * 1024
                ? String.format(Locale.ROOT, "%.1f MB", bytes / (1024f * 1024f))
                : Math.max(1, bytes / 1024) + " KB";
    }
}
