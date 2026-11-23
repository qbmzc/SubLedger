package com.subledger.mobile;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.os.Build;

/**
 * Filled shape with continuous-curvature ("squircle") corners: each corner eases from the straight
 * edge into a circular arc instead of meeting it abruptly, like Apple's rounded rectangles. The
 * geometry follows Figma's corner smoothing; {@link #SMOOTHING} of 0.6 matches iOS.
 */
final class Squircle extends Drawable {
    static final float SMOOTHING = 0.6f;

    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float[] radii;
    private final Path path = new Path();
    private final Path strokePath = new Path();
    private final RectF rect = new RectF();
    private Rect padding;
    private int alpha = 255;
    private int fillColor;

    /** Same radius (px) on every corner. */
    Squircle(int color, float radius) {
        this(color, radius, radius, radius, radius);
    }

    /** Radii in px, clockwise from the top-left corner. */
    Squircle(int color, float topLeft, float topRight, float bottomRight, float bottomLeft) {
        radii = new float[]{topLeft, topRight, bottomRight, bottomLeft};
        fillColor = color;
        fill.setColor(color);
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setColor(0);
    }

    /** Adds an inner outline, e.g. a focus ring. */
    Squircle stroke(int color, float width) {
        stroke.setColor(color);
        stroke.setStrokeWidth(width);
        rebuild(getBounds());
        return this;
    }

    /** Padding reported to the view (px), e.g. so a {@code Switch} thumb keeps clear of the track ends. */
    Squircle padding(int left, int top, int right, int bottom) {
        padding = new Rect(left, top, right, bottom);
        return this;
    }

    void setColor(int color) {
        fillColor = color;
        fill.setColor(color);
        fill.setAlpha(scaled(color));
        invalidateSelf();
    }

    int getColor() {
        return fillColor;
    }

    private int scaled(int color) {
        return (color >>> 24) * alpha / 255;
    }

    @Override
    protected void onBoundsChange(Rect bounds) {
        rebuild(bounds);
    }

    private void rebuild(Rect bounds) {
        path.reset();
        rect.set(bounds);
        addTo(path, rect, radii, SMOOTHING);
        strokePath.reset();
        float width = stroke.getStrokeWidth();
        if (width > 0) {
            float half = width / 2f;
            rect.inset(half, half);
            addTo(strokePath, rect, new float[]{
                    Math.max(0, radii[0] - half), Math.max(0, radii[1] - half),
                    Math.max(0, radii[2] - half), Math.max(0, radii[3] - half)}, SMOOTHING);
        }
    }

    @Override
    public void draw(Canvas canvas) {
        if (fill.getAlpha() > 0) {
            canvas.drawPath(path, fill);
        }
        if (stroke.getStrokeWidth() > 0 && stroke.getAlpha() > 0) {
            canvas.drawPath(strokePath, stroke);
        }
    }

    @Override
    public void getOutline(Outline outline) {
        Rect bounds = getBounds();
        if (bounds.isEmpty()) {
            return;
        }
        boolean uniform = radii[0] == radii[1] && radii[1] == radii[2] && radii[2] == radii[3];
        if (Build.VERSION.SDK_INT >= 33) {
            // Path outlines can clip children from Android 13 on.
            outline.setPath(path);
        } else if (uniform) {
            // Close enough for shadows and clipping on older versions.
            outline.setRoundRect(bounds, Math.min(radii[0], Math.min(bounds.width(), bounds.height()) / 2f));
        } else if (Build.VERSION.SDK_INT >= 30) {
            outline.setPath(path);
        } else {
            outline.setRect(bounds);
        }
        outline.setAlpha(getAlpha() / 255f);
    }

    @Override
    public boolean getPadding(Rect out) {
        if (padding == null) {
            return super.getPadding(out);
        }
        out.set(padding);
        return true;
    }

    @Override
    public void setAlpha(int value) {
        alpha = value;
        fill.setAlpha(scaled(fillColor));
        invalidateSelf();
    }

    @Override
    public int getAlpha() {
        return alpha;
    }

    @Override
    public void setColorFilter(ColorFilter filter) {
        fill.setColorFilter(filter);
        stroke.setColorFilter(filter);
        invalidateSelf();
    }

    /** Always translucent: the corners are see-through, so ripples are masked to the shape. */
    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }

    /**
     * Adds a closed squircle outline for {@code bounds} to {@code path}.
     *
     * @param radii px, clockwise from the top-left; a corner's radius and smoothing shrink to fit
     *              half the shorter side, so a large radius gives a pill
     */
    static void addTo(Path path, RectF bounds, float[] radii, float smoothing) {
        float budget = Math.min(bounds.width(), bounds.height()) / 2f;
        if (budget <= 0) {
            return;
        }
        Corner topLeft = Corner.of(radii[0], smoothing, budget);
        path.moveTo(bounds.left + (topLeft == null ? 0 : topLeft.p), bounds.top);
        corner(path, Corner.of(radii[1], smoothing, budget), bounds.right, bounds.top, 1, 0, 0, 1);
        corner(path, Corner.of(radii[2], smoothing, budget), bounds.right, bounds.bottom, 0, 1, -1, 0);
        corner(path, Corner.of(radii[3], smoothing, budget), bounds.left, bounds.bottom, -1, 0, 0, -1);
        corner(path, topLeft, bounds.left, bounds.top, 0, -1, 1, 0);
        path.close();
    }

    /**
     * One corner at ({@code kx}, {@code ky}), reached travelling along (ux, uy) and left along (vx, vy).
     * Points are written as (s, t): s along the incoming edge from where the curve starts, t along
     * the outgoing edge.
     */
    private static void corner(Path path, Corner c, float kx, float ky, float ux, float uy, float vx, float vy) {
        if (c == null) {
            path.lineTo(kx, ky);
            return;
        }
        float sx = kx - c.p * ux;
        float sy = ky - c.p * uy;
        path.lineTo(sx, sy);
        float[] q = c.points;
        for (int i = 0; i < q.length; i += 6) {
            path.cubicTo(sx + q[i] * ux + q[i + 1] * vx, sy + q[i] * uy + q[i + 1] * vy,
                    sx + q[i + 2] * ux + q[i + 3] * vx, sy + q[i + 2] * uy + q[i + 3] * vy,
                    sx + q[i + 4] * ux + q[i + 5] * vx, sy + q[i + 4] * uy + q[i + 5] * vy);
        }
    }

    /** Control points of one smoothed corner: an easing curve, a circular arc, and the mirrored easing curve. */
    private static final class Corner {
        final float p;
        final float[] points;

        private Corner(float p, float[] points) {
            this.p = p;
            this.points = points;
        }

        static Corner of(float radius, float smoothing, float budget) {
            float r = Math.min(radius, budget);
            if (r <= 0) {
                return null;
            }
            float s = smoothing;
            float p = (1 + s) * r;
            if (p > budget) {
                s = Math.max(0f, Math.min(s, budget / r - 1));
                p = Math.min(p, budget);
            }
            double arc = 90 * (1 - s);
            double arcLength = Math.sin(Math.toRadians(arc / 2)) * r * Math.sqrt(2);
            double alpha = (90 - arc) / 2;
            double p3ToP4 = r * Math.tan(Math.toRadians(alpha / 2));
            double beta = 45 * s;
            double c = p3ToP4 * Math.cos(Math.toRadians(beta));
            double d = c * Math.tan(Math.toRadians(beta));
            double b = (p - arcLength - c - d) / 3;
            double a = 2 * b;
            // The arc as one cubic: handles of 4/3·tan(θ/4)·r along the tangents.
            double k = 4.0 / 3.0 * Math.tan(Math.toRadians(arc) / 4) * r;
            double sin = Math.sin(Math.toRadians(alpha));
            double cos = Math.cos(Math.toRadians(alpha));
            double s3 = a + b + c;
            double t3 = d;
            double s4 = s3 + arcLength;
            double t4 = t3 + arcLength;
            return new Corner(p, new float[]{
                    (float) a, 0, (float) (a + b), 0, (float) s3, (float) t3,
                    (float) (s3 + k * cos), (float) (t3 + k * sin),
                    (float) (s4 - k * sin), (float) (t4 - k * cos), (float) s4, (float) t4,
                    (float) (s4 + d), (float) (t4 + c), (float) (s4 + d), (float) (t4 + b + c), p, p});
        }
    }
}
