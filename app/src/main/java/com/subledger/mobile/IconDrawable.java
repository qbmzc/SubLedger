package com.subledger.mobile;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;

/** Round subscription icon: the picked picture, or a text badge from {@link Icons}. */
final class IconDrawable extends Drawable {
    private final Icons.Badge badge;
    private final Bitmap picture;
    private final int size;
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint image = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);

    /**
     * @param picture icon picture, or null for the badge
     * @param ringColor thin outline so dark or light badges stay visible on any surface
     */
    IconDrawable(Icons.Badge badge, Bitmap picture, int size, int ringColor, float ringWidth) {
        this.badge = badge;
        this.picture = picture;
        this.size = size;
        fill.setColor(0xFF000000 | badge.color);
        text.setColor(0xFF000000 | badge.textColor());
        text.setTypeface(Typeface.DEFAULT_BOLD);
        text.setTextAlign(Paint.Align.CENTER);
        ring.setStyle(Paint.Style.STROKE);
        ring.setColor(ringColor);
        ring.setStrokeWidth(ringWidth);
        if (picture != null) {
            image.setShader(new BitmapShader(picture, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP));
        }
    }

    /** Icon for a subscription, or for a deleted one's title when {@code entry} is null. */
    static IconDrawable of(Context context, Subscription entry, String title, int size, int ringColor) {
        Icons.Badge badge = entry == null ? Icons.badge(title, null, null) : Icons.badge(entry);
        Bitmap picture = entry == null ? null : AttachmentStore.icon(context, entry.iconImage);
        return new IconDrawable(badge, picture, size, ringColor, context.getResources().getDisplayMetrics().density);
    }

    @Override
    protected void onBoundsChange(Rect bounds) {
        if (picture != null) {
            // Centre-crop the picture to the circle.
            float scale = Math.min(bounds.width(), bounds.height()) / (float) Math.min(picture.getWidth(), picture.getHeight());
            Matrix matrix = new Matrix();
            matrix.setScale(scale, scale);
            matrix.postTranslate(bounds.exactCenterX() - picture.getWidth() * scale / 2,
                    bounds.exactCenterY() - picture.getHeight() * scale / 2);
            image.getShader().setLocalMatrix(matrix);
        }
    }

    @Override
    public void draw(Canvas canvas) {
        Rect bounds = getBounds();
        float radius = Math.min(bounds.width(), bounds.height()) / 2f;
        float cx = bounds.exactCenterX();
        float cy = bounds.exactCenterY();
        if (picture != null) {
            canvas.drawCircle(cx, cy, radius, image);
        } else {
            canvas.drawCircle(cx, cy, radius, fill);
            // Start at about 40% of the diameter and shrink until the text fits inside the circle.
            float textSize = radius * 0.84f;
            text.setTextSize(textSize);
            while (textSize > 6 && text.measureText(badge.text) > radius * 1.5f) {
                textSize *= 0.9f;
                text.setTextSize(textSize);
            }
            Paint.FontMetrics metrics = text.getFontMetrics();
            canvas.drawText(badge.text, cx, cy - (metrics.ascent + metrics.descent) / 2, text);
        }
        float inset = ring.getStrokeWidth() / 2;
        canvas.drawCircle(cx, cy, radius - inset, ring);
    }

    @Override
    public int getIntrinsicWidth() {
        return size;
    }

    @Override
    public int getIntrinsicHeight() {
        return size;
    }

    @Override
    public void setAlpha(int alpha) {
        fill.setAlpha(alpha);
        text.setAlpha(alpha);
        image.setAlpha(alpha);
    }

    @Override
    public void setColorFilter(ColorFilter filter) {
        fill.setColorFilter(filter);
        text.setColorFilter(filter);
        image.setColorFilter(filter);
    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }
}
