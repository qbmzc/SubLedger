package com.subledger.mobile;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.util.LayoutDirection;

/** Small line icons drawn in code on a 24-unit grid, so they take any palette colour. */
final class Glyph extends Drawable {
    static final int BACK = 0, CLOSE = 1, DOWN = 2, FORWARD = 3, PLUS = 4;

    private final int kind;
    private final int size;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();

    /** @param size intrinsic size in px */
    Glyph(int kind, int color, int size) {
        this.kind = kind;
        this.size = size;
        paint.setColor(color);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeJoin(Paint.Join.ROUND);
    }

    @Override
    public void draw(Canvas canvas) {
        Rect bounds = getBounds();
        float side = Math.min(bounds.width(), bounds.height());
        float unit = side / 24f;
        canvas.save();
        canvas.translate(bounds.exactCenterX() - side / 2, bounds.exactCenterY() - side / 2);
        if (isAutoMirrored() && getLayoutDirection() == LayoutDirection.RTL) {
            canvas.scale(-1, 1, side / 2, side / 2);
        }
        canvas.scale(unit, unit);
        path.reset();
        switch (kind) {
            case BACK:
                line(15, 5, 8, 12, 15, 19);
                break;
            case FORWARD:
                line(9, 5, 16, 12, 9, 19);
                break;
            case DOWN:
                line(6, 9, 12, 15, 18, 9);
                break;
            case CLOSE:
                line(7, 7, 17, 17);
                line(17, 7, 7, 17);
                break;
            default:
                line(12, 5, 12, 19);
                line(5, 12, 19, 12);
                break;
        }
        // In grid units: the canvas is already scaled.
        paint.setStrokeWidth(2.2f);
        canvas.drawPath(path, paint);
        canvas.restore();
    }

    private void line(float... points) {
        path.moveTo(points[0], points[1]);
        for (int i = 2; i < points.length; i += 2) {
            path.lineTo(points[i], points[i + 1]);
        }
    }

    @Override
    public boolean isAutoMirrored() {
        return kind == BACK || kind == FORWARD;
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
        paint.setAlpha(alpha);
        invalidateSelf();
    }

    @Override
    public void setColorFilter(ColorFilter filter) {
        paint.setColorFilter(filter);
        invalidateSelf();
    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }
}
