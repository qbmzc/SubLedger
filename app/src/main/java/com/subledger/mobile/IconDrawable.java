package com.subledger.mobile;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;

/**
 * Subscription icon as a continuous-corner tile, like an app icon: the picked picture, or a text
 * badge from {@link Icons}.
 */
final class IconDrawable extends Drawable {
    /** Corner radius as a share of the side. */
    private static final float CORNER = 0.27f;
    private final Icons.Badge badge;
    private final Bitmap picture;
    private final int size;
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint image = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Path tile = new Path();
    private final Path outline = new Path();

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
        float side = Math.min(bounds.width(), bounds.height());
        RectF square = new RectF(bounds.exactCenterX() - side / 2, bounds.exactCenterY() - side / 2,
                bounds.exactCenterX() + side / 2, bounds.exactCenterY() + side / 2);
        float radius = side * CORNER;
        tile.reset();
        Squircle.addTo(tile, square, new float[]{radius, radius, radius, radius}, Squircle.SMOOTHING);
        float inset = ring.getStrokeWidth() / 2;
        square.inset(inset, inset);
        float inner = Math.max(0, radius - inset);
        outline.reset();
        Squircle.addTo(outline, square, new float[]{inner, inner, inner, inner}, Squircle.SMOOTHING);
        if (picture != null) {
            // Centre-crop the picture to the tile.
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
        float side = Math.min(bounds.width(), bounds.height());
        float cx = bounds.exactCenterX();
        float cy = bounds.exactCenterY();
        if (picture != null) {
            canvas.drawPath(tile, image);
        } else {
            canvas.drawPath(tile, fill);
            // Start at about 42% of the side and shrink until the text fits inside the tile.
            float textSize = side * 0.42f;
            text.setTextSize(textSize);
            while (textSize > 6 && text.measureText(badge.text) > side * 0.74f) {
                textSize *= 0.9f;
                text.setTextSize(textSize);
            }
            Paint.FontMetrics metrics = text.getFontMetrics();
            canvas.drawText(badge.text, cx, cy - (metrics.ascent + metrics.descent) / 2, text);
        }
        canvas.drawPath(outline, ring);
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
