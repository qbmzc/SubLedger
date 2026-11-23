package com.subledger.mobile;

import android.app.Dialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.drawable.ColorDrawable;
import android.os.Build;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.Arrays;
import java.util.function.BooleanSupplier;

/**
 * Modal bottom sheet that rests at a few heights ("detents"), e.g. half and full screen. It follows
 * the finger, hands the gesture over to its scrolling content and back, rubber-bands past the top,
 * settles with a spring that keeps the fling velocity, and closes when pulled down. On wide windows
 * it is capped at {@link #MAX_WIDTH_DP} and centred.
 */
final class Sheet {
    private static final int MAX_WIDTH_DP = 640;
    private static final int TOP_GAP_DP = 10;
    private static final int OVERSCROLL_DP = 96;
    private static final int RUBBER_DP = 56;
    /** Detents closer than this are merged. */
    private static final int MIN_STEP_DP = 56;

    private final LedgerActivity activity;
    private final LedgerActivity.Palette colors;
    private final Dialog dialog;
    private final Frame frame;
    private final View scrim;
    private final Panel panel;
    private final LinearLayout header;
    private final FrameLayout leadingSlot, trailingSlot;
    private final TextView titleView;
    private final View divider;
    private final ScrollView scroll;
    /** The column callers fill. */
    final LinearLayout body;

    private float[] fractions = {0.55f, 1f};
    private int[] detents = {0};
    private int detent;
    private boolean startExpanded, shown, closing, expandOnLayout;
    /** Visible height in px, and the finger-driven height before rubber-banding. */
    private float height, raw;
    /** Tallest the sheet gets: its content, or the room below the status bar. */
    private int tallest;
    private int insetTop, insetBottom, insetLeft, insetRight, keyboard;
    private final Motion.Spring spring = new Motion.Spring(0.86f, 0.46f);
    private BooleanSupplier unsaved;
    private Runnable dismissed;
    private boolean titleOnScroll;

    Sheet(LedgerActivity activity, String title) {
        this.activity = activity;
        colors = activity.colors;
        dialog = new Dialog(activity, R.style.SheetOverlay) {
            @Override
            public void onBackPressed() {
                close(0);
            }
        };
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        frame = new Frame(activity);
        scrim = new View(activity);
        scrim.setBackgroundColor(colors.scrim);
        scrim.setAlpha(0f);
        scrim.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        scrim.setOnClickListener(view -> close(0));
        frame.addView(scrim);

        panel = new Panel(activity);
        panel.setOrientation(LinearLayout.VERTICAL);
        int radius = activity.dp(28);
        panel.setBackground(new Squircle(colors.paper, radius, radius, 0, 0));
        frame.addView(panel);

        FrameLayout grip = new FrameLayout(activity);
        View handle = new View(activity);
        handle.setBackground(new Squircle((colors.muted & 0x00FFFFFF) | 0x66000000, activity.dp(3)));
        grip.addView(handle, new FrameLayout.LayoutParams(activity.dp(36), activity.dp(5), Gravity.CENTER));
        grip.setContentDescription("调整面板高度");
        grip.setOnClickListener(view -> settle(detent == detents.length - 1 ? 0 : detents.length - 1, 0, true));
        panel.addView(grip, new LinearLayout.LayoutParams(-1, activity.dp(20)));

        header = new LinearLayout(activity);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(activity.dp(8), 0, activity.dp(8), activity.dp(4));
        leadingSlot = new FrameLayout(activity);
        leadingSlot.setMinimumWidth(activity.dp(80));
        header.addView(leadingSlot, new LinearLayout.LayoutParams(-2, -2));
        titleView = activity.label(title == null ? "" : title, 17, colors.ink, true);
        titleView.setGravity(Gravity.CENTER);
        titleView.setSingleLine(true);
        titleView.setEllipsize(TextUtils.TruncateAt.END);
        header.addView(titleView, new LinearLayout.LayoutParams(0, -2, 1f));
        trailingSlot = new FrameLayout(activity);
        trailingSlot.setMinimumWidth(activity.dp(80));
        header.addView(trailingSlot, new LinearLayout.LayoutParams(-2, -2));
        panel.addView(header, new LinearLayout.LayoutParams(-1, -2));
        divider = new View(activity);
        divider.setBackgroundColor(colors.line);
        divider.setAlpha(0f);
        panel.addView(divider, new LinearLayout.LayoutParams(-1, Math.max(1, activity.dp(1) / 2)));

        scroll = new ScrollView(activity);
        scroll.setNestedScrollingEnabled(true);
        scroll.setClipToPadding(false);
        scroll.setOnScrollChangeListener((view, x, y, oldX, oldY) -> {
            divider.setAlpha(Math.min(1f, y / (float) activity.dp(8)));
            if (titleOnScroll) {
                titleView.setAlpha(Math.max(0f, Math.min(1f, (y - activity.dp(36)) / (float) activity.dp(24))));
            }
        });
        body = activity.column();
        body.setPadding(activity.dp(20), activity.dp(6), activity.dp(20), activity.dp(28));
        scroll.addView(body);
        panel.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1f));
        if (title != null) {
            dialog.setTitle(title);
        }
    }

    /** Heights as fractions of the room below the status bar, e.g. {@code 0.5f, 1f}. Short content uses fewer. */
    Sheet detents(float... values) {
        fractions = values.clone();
        Arrays.sort(fractions);
        return this;
    }

    /** For bodies that start with their own large title: the header title fades in once it scrolls away. */
    Sheet titleOnScroll() {
        titleOnScroll = true;
        titleView.setAlpha(0f);
        return this;
    }

    /** Opens at the tallest detent instead of the lowest. */
    Sheet expanded() {
        startExpanded = true;
        return this;
    }

    /** Text button at the start of the header, e.g. 取消. */
    Sheet leading(String label, Runnable action) {
        leadingSlot.removeAllViews();
        leadingSlot.addView(textButton(label, false, view -> action.run()),
                new FrameLayout.LayoutParams(-2, -2, Gravity.START | Gravity.CENTER_VERTICAL));
        return this;
    }

    /** Emphasised button at the end of the header, e.g. 保存. The sheet closes when {@code action} returns true. */
    Sheet trailing(String label, BooleanSupplier action) {
        trailingSlot.removeAllViews();
        trailingSlot.addView(textButton(label, true, view -> {
                    if (!closing && action.getAsBoolean()) {
                        dismiss();
                    }
                }),
                new FrameLayout.LayoutParams(-2, -2, Gravity.END | Gravity.CENTER_VERTICAL));
        return this;
    }

    /** Asked before the sheet is pulled down, tapped away or backed out of; true asks to confirm. */
    Sheet unsaved(BooleanSupplier check) {
        unsaved = check;
        return this;
    }

    Sheet onDismiss(Runnable action) {
        dismissed = action;
        return this;
    }

    boolean isShowing() {
        return dialog.isShowing() && !closing;
    }

    Sheet show() {
        if (trailingSlot.getChildCount() == 0) {
            trailingSlot.addView(closeButton(),
                    new FrameLayout.LayoutParams(-2, -2, Gravity.END | Gravity.CENTER_VERTICAL));
        }
        dialog.setContentView(frame, new ViewGroup.LayoutParams(-1, -1));
        Window window = dialog.getWindow();
        window.setLayout(-1, -1);
        window.setFormat(PixelFormat.TRANSLUCENT);
        window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        window.setWindowAnimations(0);
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
                | WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);
        activity.edgeToEdge(window, colors.paper);
        if (Build.VERSION.SDK_INT >= 28 && titleView.getText().length() > 0) {
            frame.setAccessibilityPaneTitle(titleView.getText());
        }
        frame.setOnApplyWindowInsetsListener((view, insets) -> {
            int[] bars = LedgerActivity.bars(insets);
            insetLeft = bars[0];
            insetTop = bars[1];
            insetRight = bars[2];
            insetBottom = bars[3];
            if (bars[4] > 0 && keyboard == 0) {
                // iOS-style: the keyboard opens the sheet fully, so the focused field stays visible.
                expandOnLayout = true;
            }
            keyboard = bars[4];
            panel.setPadding(0, 0, 0, (keyboard > 0 ? 0 : insetBottom) + activity.dp(OVERSCROLL_DP));
            frame.requestLayout();
            return LedgerActivity.consumed(insets);
        });
        dialog.setOnDismissListener(closed -> {
            spring.cancel();
            if (dismissed != null) {
                dismissed.run();
            }
        });
        panel.setPadding(0, 0, 0, activity.insetBottom + activity.dp(OVERSCROLL_DP));
        dialog.show();
        return this;
    }

    /** Closes like a pull-down: asks first when there are unsaved changes. For a 取消 button. */
    void cancel() {
        close(0);
    }

    /** Closes without asking, e.g. after saving. */
    void dismiss() {
        animateOut(0);
    }

    /** A close request from the user: confirms first when there are unsaved changes. */
    private void close(float velocity) {
        if (closing) {
            return;
        }
        if (unsaved != null && unsaved.getAsBoolean()) {
            settle(detent, velocity, false);
            activity.alert().setTitle("放弃未保存的修改？")
                    .setNegativeButton("继续编辑", null)
                    .setPositiveButton("放弃", (confirm, button) -> animateOut(0))
                    .show();
            return;
        }
        animateOut(velocity);
    }

    private void animateOut(float velocity) {
        if (closing || !dialog.isShowing()) {
            return;
        }
        closing = true;
        spring.cancel();
        InputMethodManager keyboards = (InputMethodManager) activity.getSystemService(Context.INPUT_METHOD_SERVICE);
        if (keyboards != null) {
            keyboards.hideSoftInputFromWindow(frame.getWindowToken(), 0);
        }
        new Motion.Spring(0.999f, 0.34f).start(height, 0, Math.min(velocity, 0), this::apply, () -> {
            if (dialog.isShowing()) {
                try {
                    dialog.dismiss();
                } catch (IllegalArgumentException ex) {
                    // The activity's window is already gone.
                }
            }
        });
    }

    private TextView textButton(String label, boolean strong, View.OnClickListener click) {
        TextView button = activity.label(label, 16, colors.green, strong);
        button.setGravity(Gravity.CENTER);
        button.setMinHeight(activity.dp(44));
        button.setPadding(activity.dp(12), 0, activity.dp(12), 0);
        button.setBackground(activity.ripple(Color.TRANSPARENT, 22));
        button.setFocusable(true);
        button.setOnClickListener(click);
        return button;
    }

    private View closeButton() {
        FrameLayout target = new FrameLayout(activity);
        target.setMinimumWidth(activity.dp(44));
        target.setMinimumHeight(activity.dp(44));
        ImageView icon = new ImageView(activity);
        icon.setImageDrawable(new Glyph(Glyph.CLOSE, colors.muted, activity.dp(18)));
        icon.setScaleType(ImageView.ScaleType.CENTER);
        icon.setBackground(activity.ripple(colors.line, 16));
        icon.setContentDescription("关闭");
        icon.setOnClickListener(view -> close(0));
        Motion.pressable(icon, 0.9f);
        target.addView(icon, new FrameLayout.LayoutParams(activity.dp(32), activity.dp(32), Gravity.CENTER));
        return target;
    }

    // --- Geometry -----------------------------------------------------------------------------

    private void computeDetents(int room) {
        boolean atTop = detent == detents.length - 1;
        int[] list = new int[fractions.length];
        int count = 0;
        int step = activity.dp(MIN_STEP_DP);
        for (int i = fractions.length - 1; i >= 0; i--) {
            int value = Math.min(Math.round(fractions[i] * room), tallest);
            if (count == 0 || list[count - 1] - value >= step) {
                list[count++] = value;
            }
        }
        int[] sorted = new int[Math.max(1, count)];
        for (int i = 0; i < count; i++) {
            sorted[i] = list[count - 1 - i];
        }
        detents = sorted;
        detent = atTop ? detents.length - 1 : Math.min(detent, detents.length - 1);
    }

    private void apply(float value) {
        height = value;
        panel.setTranslationY(tallest + activity.dp(OVERSCROLL_DP) - value);
        float lowest = Math.max(1, detents[0]);
        scrim.setAlpha(Math.max(0f, Math.min(1f, value / lowest)));
    }

    private float resist(float value) {
        float top = detents[detents.length - 1];
        if (value <= top) {
            return Math.max(0, value);
        }
        float limit = activity.dp(RUBBER_DP);
        float over = value - top;
        return top + (1 - 1 / (over * 0.55f / limit + 1)) * limit;
    }

    private void beginDrag() {
        spring.cancel();
        raw = height;
    }

    private void dragBy(float delta) {
        raw = Math.max(0, raw + delta);
        apply(resist(raw));
    }

    /** Picks a detent from where the sheet would coast to, or closes it. Velocity is px/s, positive upward. */
    private void release(float velocity) {
        float projected = height + velocity * 0.16f;
        int lowest = detents[0];
        if (projected < lowest * 0.5f || (velocity < -activity.dp(1600) && height < lowest)) {
            close(velocity);
            return;
        }
        int best = 0;
        for (int i = 1; i < detents.length; i++) {
            if (Math.abs(detents[i] - projected) < Math.abs(detents[best] - projected)) {
                best = i;
            }
        }
        settle(best, velocity, true);
    }

    private void settle(int index, float velocity, boolean feedback) {
        index = Math.max(0, Math.min(detents.length - 1, index));
        if (feedback && index != detent) {
            Motion.tick(panel);
        }
        detent = index;
        spring.start(height, detents[index], velocity, this::apply, null);
    }

    /** Full-window container: the scrim, and the panel anchored to the bottom (above the keyboard). */
    private final class Frame extends ViewGroup {
        Frame(Context context) {
            super(context);
        }

        @Override
        protected void onMeasure(int widthSpec, int heightSpec) {
            int width = MeasureSpec.getSize(widthSpec);
            int fullHeight = MeasureSpec.getSize(heightSpec);
            setMeasuredDimension(width, fullHeight);
            scrim.measure(exactly(width), exactly(fullHeight));
            int room = Math.max(0, fullHeight - keyboard - insetTop - activity.dp(TOP_GAP_DP));
            int panelWidth = Math.max(0, Math.min(width - insetLeft - insetRight, activity.dp(MAX_WIDTH_DP)));
            int overscroll = activity.dp(OVERSCROLL_DP);
            panel.measure(exactly(panelWidth), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
            tallest = Math.min(panel.getMeasuredHeight() - overscroll, room);
            panel.measure(exactly(panelWidth), exactly(tallest + overscroll));
            computeDetents(room);
        }

        @Override
        protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
            int width = right - left;
            int base = bottom - top - keyboard;
            scrim.layout(0, 0, width, bottom - top);
            int panelWidth = panel.getMeasuredWidth();
            int panelLeft = insetLeft + (width - insetLeft - insetRight - panelWidth) / 2;
            panel.layout(panelLeft, base - panel.getMeasuredHeight(), panelLeft + panelWidth, base);
            if (!shown) {
                shown = true;
                apply(0);
                settle(startExpanded ? detents.length - 1 : 0, 0, false);
                return;
            }
            apply(height);
            if (closing || panel.dragging || panel.nested) {
                return;
            }
            if (expandOnLayout) {
                expandOnLayout = false;
                settle(detents.length - 1, 0, false);
            } else if (spring.running() ? spring.target() != detents[detent] : height != detents[detent]) {
                // Content or room changed: glide to the detent's new height.
                settle(detent, 0, false);
            }
        }

        private int exactly(int size) {
            return MeasureSpec.makeMeasureSpec(size, MeasureSpec.EXACTLY);
        }
    }

    /** The sheet itself: handles drags on the header and hand-offs with the scrolling content. */
    private final class Panel extends LinearLayout {
        private final int slop;
        private float downX, downY, lastY;
        private boolean fromHeader, scrollingContent;
        boolean dragging, nested;
        private VelocityTracker tracker;

        Panel(Context context) {
            super(context);
            slop = ViewConfiguration.get(context).getScaledTouchSlop();
        }

        private float top() {
            return detents[detents.length - 1];
        }

        private void track(MotionEvent event, boolean reset) {
            if (tracker == null) {
                tracker = VelocityTracker.obtain();
            }
            if (reset) {
                tracker.clear();
            }
            // Screen coordinates: the panel itself moves under the finger.
            MotionEvent copy = MotionEvent.obtain(event);
            copy.setLocation(event.getRawX(), event.getRawY());
            tracker.addMovement(copy);
            copy.recycle();
        }

        private void down(MotionEvent event) {
            downX = event.getRawX();
            downY = event.getRawY();
            lastY = downY;
            fromHeader = event.getY() < scroll.getTop();
            scrollingContent = false;
            dragging = false;
            track(event, true);
        }

        private void startDragging(MotionEvent event) {
            beginDrag();
            dragging = true;
            lastY = event.getRawY();
        }

        @Override
        public boolean onInterceptTouchEvent(MotionEvent event) {
            if (closing) {
                return true;
            }
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    down(event);
                    if (spring.running()) {
                        // Catch the sheet while it is still moving.
                        startDragging(event);
                        return true;
                    }
                    return false;
                case MotionEvent.ACTION_MOVE:
                    track(event, false);
                    float dx = event.getRawX() - downX;
                    float dy = event.getRawY() - downY;
                    if (Math.abs(dy) > slop && Math.abs(dy) > Math.abs(dx)) {
                        boolean down = dy > 0;
                        if (fromHeader || (down && !scroll.canScrollVertically(-1))
                                || (!down && height < top() - 1)) {
                            startDragging(event);
                            return true;
                        }
                    }
                    return false;
                default:
                    return false;
            }
        }

        @Override
        public boolean onTouchEvent(MotionEvent event) {
            if (closing) {
                return true;
            }
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    // Taps on the sheet's background must not reach the scrim.
                    down(event);
                    if (spring.running()) {
                        startDragging(event);
                    }
                    return true;
                case MotionEvent.ACTION_MOVE:
                    track(event, false);
                    if (!dragging && Math.abs(event.getRawY() - downY) > slop) {
                        startDragging(event);
                    }
                    if (dragging) {
                        float delta = lastY - event.getRawY();
                        lastY = event.getRawY();
                        drag(delta);
                    }
                    return true;
                case MotionEvent.ACTION_UP:
                    track(event, false);
                    if (dragging) {
                        dragging = false;
                        tracker.computeCurrentVelocity(1000);
                        float velocity = -tracker.getYVelocity();
                        if (scrollingContent) {
                            scroll.fling(Math.round(velocity));
                            settle(detents.length - 1, 0, false);
                        } else {
                            release(velocity);
                        }
                    }
                    return true;
                case MotionEvent.ACTION_CANCEL:
                    if (dragging) {
                        dragging = false;
                        release(0);
                    }
                    return true;
                default:
                    return true;
            }
        }

        /** {@code delta} > 0 when the finger moves up. Once fully open, the rest scrolls the content. */
        private void drag(float delta) {
            float top = top();
            if (delta > 0 && !fromHeader && scroll.canScrollVertically(1) && raw + delta > top) {
                float rest = raw + delta - Math.max(raw, top);
                if (raw < top) {
                    dragBy(top - raw);
                }
                scroll.scrollBy(0, Math.round(rest));
                scrollingContent = true;
                return;
            }
            scrollingContent = false;
            dragBy(delta);
        }

        // Nested scrolling: the content scrolled to its top keeps pulling the sheet down, and back.

        @Override
        public boolean onStartNestedScroll(View child, View target, int axes) {
            return !closing && (axes & View.SCROLL_AXIS_VERTICAL) != 0;
        }

        @Override
        public void onNestedScrollAccepted(View child, View target, int axes) {
            super.onNestedScrollAccepted(child, target, axes);
            nested = false;
        }

        @Override
        public void onNestedPreScroll(View target, int dx, int dy, int[] consumed) {
            if (!nested) {
                return;
            }
            float rest = detents[detent];
            if (dy > 0 && raw < rest) {
                float take = Math.min(dy, rest - raw);
                dragBy(take);
                consumed[1] = Math.round(take);
            } else if (dy < 0 && raw > rest) {
                float take = Math.max(dy, rest - raw);
                dragBy(take);
                consumed[1] = Math.round(take);
            }
        }

        @Override
        public void onNestedScroll(View target, int dxConsumed, int dyConsumed, int dxUnconsumed, int dyUnconsumed) {
            if (dyUnconsumed == 0 || closing) {
                return;
            }
            if (!nested) {
                nested = true;
                beginDrag();
            }
            dragBy(dyUnconsumed);
        }

        @Override
        public boolean onNestedPreFling(View target, float velocityX, float velocityY) {
            if (!nested) {
                return false;
            }
            nested = false;
            release(velocityY);
            return true;
        }

        @Override
        public void onStopNestedScroll(View child) {
            super.onStopNestedScroll(child);
            if (nested) {
                nested = false;
                release(0);
            }
        }
    }
}
