package com.subledger.mobile;

import android.content.Context;
import android.graphics.Typeface;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Segmented control: equal segments on a tonal track, with a raised thumb that springs to the
 * chosen segment. Replaces drop-downs for short fixed choices.
 */
final class Segmented extends FrameLayout {
    interface Listener {
        void select(int index);
    }

    private final LedgerActivity.Palette colors;
    private final View thumb;
    private final LinearLayout segments;
    private final Motion.Spring spring = new Motion.Spring(0.78f, 0.36f);
    private final Listener listener;
    private final String[] items;
    private int selected;

    Segmented(Context context, LedgerActivity.Palette colors, String[] items, int selected, Listener listener) {
        super(context);
        this.colors = colors;
        this.items = items;
        this.listener = listener;
        this.selected = Math.max(0, Math.min(items.length - 1, selected));
        int inset = dp(3);
        setBackground(new Squircle(colors.line, dp(13)));
        setPadding(inset, inset, inset, inset);
        thumb = new View(context);
        thumb.setBackground(new Squircle(colors.raised, dp(10)));
        thumb.setElevation(colors.dark ? 0 : dp(1));
        thumb.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        addView(thumb, new LayoutParams(0, LayoutParams.MATCH_PARENT));
        segments = new LinearLayout(context);
        // A ViewGroup draws its children in Z order, so the elevated thumb would cover the
        // labels; lift the labels above it (no outline, so they cast no shadow).
        segments.setOutlineProvider(null);
        segments.setTranslationZ(dp(2));
        addView(segments, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        for (int i = 0; i < items.length; i++) {
            final int index = i;
            TextView segment = new TextView(context);
            segment.setText(items[i]);
            segment.setGravity(Gravity.CENTER);
            segment.setTextSize(14);
            segment.setSingleLine(true);
            segment.setEllipsize(TextUtils.TruncateAt.END);
            segment.setPadding(dp(4), 0, dp(4), 0);
            segment.setOnClickListener(view -> select(index, true));
            segments.addView(segment, new LinearLayout.LayoutParams(0, LayoutParams.MATCH_PARENT, 1f));
        }
        style();
    }

    int selected() {
        return selected;
    }

    /** Selects a segment; {@code notify} also reports it to the listener. */
    void select(int index, boolean notify) {
        if (index == selected) {
            return;
        }
        selected = index;
        style();
        Motion.tick(this);
        spring.start(thumb.getTranslationX(), offset(index), 0, thumb::setTranslationX, null);
        if (notify && listener != null) {
            listener.select(index);
        }
    }

    private void style() {
        for (int i = 0; i < segments.getChildCount(); i++) {
            TextView segment = (TextView) segments.getChildAt(i);
            boolean on = i == selected;
            segment.setTextColor(on ? colors.ink : colors.muted);
            segment.setTypeface(on ? LedgerActivity.BOLD : Typeface.DEFAULT);
            segment.setSelected(on);
            segment.setContentDescription(items[i] + (on ? "，已选择" : ""));
        }
    }

    private int segmentWidth() {
        int inner = getMeasuredWidth() - getPaddingLeft() - getPaddingRight();
        return items.length == 0 ? 0 : inner / items.length;
    }

    private float offset(int index) {
        int position = getLayoutDirection() == LAYOUT_DIRECTION_RTL ? items.length - 1 - index : index;
        return position * segmentWidth();
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        super.onMeasure(widthSpec, heightSpec);
        int height = getMeasuredHeight() - getPaddingTop() - getPaddingBottom();
        thumb.measure(MeasureSpec.makeMeasureSpec(segmentWidth(), MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(Math.max(0, height), MeasureSpec.EXACTLY));
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        super.onLayout(changed, left, top, right, bottom);
        thumb.layout(getPaddingLeft(), getPaddingTop(), getPaddingLeft() + thumb.getMeasuredWidth(),
                getPaddingTop() + thumb.getMeasuredHeight());
        if (!spring.running()) {
            thumb.setTranslationX(offset(selected));
        }
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
