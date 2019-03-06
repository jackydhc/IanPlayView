package com.ian.ianplayer.timebar;

import android.annotation.TargetApi;
import android.content.Context;
import android.content.res.Resources;
import android.content.res.TypedArray;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Point;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.Bundle;
import android.support.annotation.Nullable;
import android.util.AttributeSet;
import android.util.DisplayMetrics;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewParent;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction;

import com.google.android.exoplayer2.C;
import com.google.android.exoplayer2.ui.DefaultTimeBar;
import com.google.android.exoplayer2.util.Assertions;
import com.google.android.exoplayer2.util.Util;
import com.ian.ianplayer.R;

import java.util.Formatter;
import java.util.Locale;

/**
 * 播放控制View中的进度
 * <p>
 * Created by ian on 2017/6/2.
 */
public class VideoTimeBar extends View implements TimeBar {

    /**
     * The threshold in dps above the bar at which touch events trigger fine scrub mode.
     */
    private static final int FINE_SCRUB_Y_THRESHOLD = -50;
    /**
     * The ratio by which times are reduced in fine scrub mode.
     */
    private static final int FINE_SCRUB_RATIO = 3;
    /**
     * The time after which the scrubbing listener is notified that scrubbing has stopped after
     * performing an incremental scrub using key input.
     */
    private static final long STOP_SCRUBBING_TIMEOUT_MS = 1000;

    private static final int DEFAULT_INCREMENT_COUNT = 20;
    private static final int DEFAULT_BAR_HEIGHT = 4;
    private static final int DEFAULT_TOUCH_TARGET_HEIGHT = 26;
    private static final int DEFAULT_PLAYED_COLOR = 0xFFFFFFFF;
    private static final int DEFAULT_SCRUBBER_ENABLED_SIZE = 12;
    private static final int DEFAULT_SCRUBBER_DISABLED_SIZE = 0;
    private static final int DEFAULT_SCRUBBER_DRAGGED_SIZE = 16;
    /**
     * Bar radius
     */
    private static final int DEFAULT_BAR_RADIUS = 2;

    private final Rect seekBounds;
    private final Rect progressBar;
    private final Rect bufferedBar;
    private final Rect scrubberBar;
    private final Paint playedPaint;
    private final Paint scrubberPaint;
    private final Paint bufferedPaint;
    private final Paint unplayedPaint;
    private final int barHeight;
    private final int touchTargetHeight;
    private final int scrubberEnabledSize;
    private final int scrubberDisabledSize;
    private final int scrubberDraggedSize;
    private final int scrubberPadding;
    private final int fineScrubYThreshold;
    private final StringBuilder formatBuilder;
    private final Formatter formatter;
    private final Runnable stopScrubbingRunnable;

    private int scrubberSize;
    private TimeBar.OnScrubListener listener;
    private int keyCountIncrement;
    private long keyTimeIncrement;
    private int lastCoarseScrubXPosition;
    private int[] locationOnScreen;
    private Point touchPosition;

    private boolean scrubbing;
    private long scrubPosition;
    private long duration;
    private long position;
    private long bufferedPosition;

    private RectF mTempRect = new RectF();
    private int defaultBarRadius;
    private int playedStart = -1;
    private int playedEnd = -1;

    /**
     * Creates a new time bar.
     */
    public VideoTimeBar(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        seekBounds = new Rect();
        progressBar = new Rect();
        bufferedBar = new Rect();
        scrubberBar = new Rect();
        playedPaint = new Paint();
        scrubberPaint = new Paint();
        bufferedPaint = new Paint();
        unplayedPaint = new Paint();

        // Calculate the dimensions and paints for drawn elements.
        Resources res = context.getResources();
        DisplayMetrics displayMetrics = res.getDisplayMetrics();
        fineScrubYThreshold = dpToPx(displayMetrics, FINE_SCRUB_Y_THRESHOLD);
        int defaultBarHeight = dpToPx(displayMetrics, DEFAULT_BAR_HEIGHT);
        int defaultTouchTargetHeight = dpToPx(displayMetrics, DEFAULT_TOUCH_TARGET_HEIGHT);
        int defaultScrubberEnabledSize = dpToPx(displayMetrics, DEFAULT_SCRUBBER_ENABLED_SIZE);
        int defaultScrubberDisabledSize = dpToPx(displayMetrics, DEFAULT_SCRUBBER_DISABLED_SIZE);
        int defaultScrubberDraggedSize = dpToPx(displayMetrics, DEFAULT_SCRUBBER_DRAGGED_SIZE);
        defaultBarRadius = dpToPx(displayMetrics, DEFAULT_BAR_RADIUS);
        if (attrs != null) {
            TypedArray a = context.getTheme()
                    .obtainStyledAttributes(attrs, R.styleable.ply_ui_TimeBar, 0, 0);
            try {
                barHeight = a.getDimensionPixelSize(R.styleable.ply_ui_TimeBar_ply_ui_bar_height, defaultBarHeight);
                touchTargetHeight = a.getDimensionPixelSize(R.styleable.ply_ui_TimeBar_ply_ui_touch_target_height,
                                                            defaultTouchTargetHeight);
                scrubberEnabledSize = a.getDimensionPixelSize(R.styleable.ply_ui_TimeBar_ply_ui_scrubber_enabled_size,
                                                              defaultScrubberEnabledSize);
                scrubberDisabledSize = a.getDimensionPixelSize(R.styleable.ply_ui_TimeBar_ply_ui_scrubber_disabled_size,
                                                               defaultScrubberDisabledSize);
                scrubberDraggedSize = a.getDimensionPixelSize(R.styleable.ply_ui_TimeBar_ply_ui_scrubber_dragged_size,
                                                              defaultScrubberDraggedSize);
                int playedColor = a.getInt(R.styleable.ply_ui_TimeBar_ply_ui_played_color, DEFAULT_PLAYED_COLOR);
                int scrubberColor = a.getInt(R.styleable.ply_ui_TimeBar_ply_ui_scrubber_color,
                                             getDefaultScrubberColor(playedColor));
                int bufferedColor = a.getInt(R.styleable.ply_ui_TimeBar_ply_ui_buffered_color,
                                             getDefaultBufferedColor(playedColor));
                int unplayedColor = a.getInt(R.styleable.ply_ui_TimeBar_ply_ui_unplayed_color,
                                             getDefaultUnplayedColor(playedColor));

                playedStart = a.getInt(R.styleable.ply_ui_TimeBar_ply_ui_played_start, -1);
                playedEnd = a.getInt(R.styleable.ply_ui_TimeBar_ply_ui_played_end, -1);
                playedPaint.setColor(playedColor);
                scrubberPaint.setColor(scrubberColor);
                bufferedPaint.setColor(bufferedColor);
                unplayedPaint.setColor(unplayedColor);
            } finally {
                a.recycle();
            }
        } else {
            barHeight = defaultBarHeight;
            touchTargetHeight = defaultTouchTargetHeight;
            scrubberEnabledSize = defaultScrubberEnabledSize;
            scrubberDisabledSize = defaultScrubberDisabledSize;
            scrubberDraggedSize = defaultScrubberDraggedSize;
            playedPaint.setColor(DEFAULT_PLAYED_COLOR);
            scrubberPaint.setColor(getDefaultScrubberColor(DEFAULT_PLAYED_COLOR));
            bufferedPaint.setColor(getDefaultBufferedColor(DEFAULT_PLAYED_COLOR));
            unplayedPaint.setColor(getDefaultUnplayedColor(DEFAULT_PLAYED_COLOR));
        }
        formatBuilder = new StringBuilder();
        formatter = new Formatter(formatBuilder, Locale.getDefault());
        //        stopScrubbingRunnable = () -> stopScrubbing(false);
        stopScrubbingRunnable = new Runnable() {
            @Override public void run() {
                stopScrubbing(false);
            }
        };
        scrubberSize = scrubberEnabledSize;
        scrubberPadding = (Math.max(scrubberDisabledSize, Math.max(scrubberEnabledSize, scrubberDraggedSize)) + 1) / 2;
        duration = C.TIME_UNSET;
        keyTimeIncrement = C.TIME_UNSET;
        keyCountIncrement = DEFAULT_INCREMENT_COUNT;
        setFocusable(true);
        maybeSetImportantForAccessibilityV16();
    }

    @TargetApi(16) private void maybeSetImportantForAccessibilityV16() {
        if (getImportantForAccessibility() == View.IMPORTANT_FOR_ACCESSIBILITY_AUTO) {
            setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
        }
    }

    @TargetApi(16) @Override public boolean performAccessibilityAction(int action, Bundle args) {
        if (super.performAccessibilityAction(action, args)) {
            return true;
        }
        if (duration <= 0) {
            return false;
        }
        if (action == AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD) {
            if (scrubIncrementally(-getPositionIncrement())) {
                stopScrubbing(false);
            }
        } else if (action == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD) {
            if (scrubIncrementally(getPositionIncrement())) {
                stopScrubbing(false);
            }
        } else {
            return false;
        }
        sendAccessibilityEvent(AccessibilityEvent.TYPE_VIEW_SELECTED);
        return true;
    }

    @TargetApi(21) @Override public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
        super.onInitializeAccessibilityNodeInfo(info);
        info.setClassName(DefaultTimeBar.class.getCanonicalName());
        info.setContentDescription(getProgressText());
        if (duration <= 0) {
            return;
        }
        if (Util.SDK_INT >= 21) {
            info.addAction(AccessibilityAction.ACTION_SCROLL_FORWARD);
            info.addAction(AccessibilityAction.ACTION_SCROLL_BACKWARD);
        } else if (Util.SDK_INT >= 16) {
            info.addAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD);
            info.addAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD);
        }
    }

    private static int dpToPx(DisplayMetrics displayMetrics, int dps) {
        return (int) (dps * displayMetrics.density + 0.5f);
    }

    private static int getDefaultScrubberColor(int playedColor) {
        return 0xFF000000 | playedColor;
    }

    private static int getDefaultUnplayedColor(int playedColor) {
        return 0x33000000 | (playedColor & 0x00FFFFFF);
    }

    private static int getDefaultBufferedColor(int playedColor) {
        return 0xCC000000 | (playedColor & 0x00FFFFFF);
    }

    @Override public void setListener(OnScrubListener listener) {
        this.listener = listener;
    }

    @Override public void setKeyTimeIncrement(long time) {
        Assertions.checkArgument(time > 0);
        keyCountIncrement = C.INDEX_UNSET;
        keyTimeIncrement = time;
    }

    @Override public void setKeyCountIncrement(int count) {
        Assertions.checkArgument(count > 0);
        keyCountIncrement = count;
        keyTimeIncrement = C.TIME_UNSET;
    }

    @Override public void setPosition(long position) {
        this.position = position;
        setContentDescription(getProgressText());
    }

    @Override public void setBufferedPosition(long bufferedPosition) {
        this.bufferedPosition = bufferedPosition;
    }

    @Override public void setDuration(long duration) {
        this.duration = duration;
        if (scrubbing && duration == C.TIME_UNSET) {
            stopScrubbing(true);
        } else {
            updateScrubberState();
        }
    }

    @Override public void setAdBreakTimesMs(@Nullable long[] adBreakTimesMs, int adBreakCount) {
        // nothing
    }

    @Override public void setEnabled(boolean enabled) {
        super.setEnabled(enabled);
        updateScrubberState();
        if (scrubbing && !enabled) {
            stopScrubbing(true);
        }
    }

    @Override protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int heightMode = MeasureSpec.getMode(heightMeasureSpec);
        int heightSize = MeasureSpec.getSize(heightMeasureSpec);
        int height = heightMode == MeasureSpec.UNSPECIFIED ? touchTargetHeight
                : heightMode == MeasureSpec.EXACTLY ? heightSize : Math.min(touchTargetHeight, heightSize);
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), height);
    }

    @Override protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        int width = right - left;
        int height = bottom - top;
        int barY = (height - touchTargetHeight) / 2;
        int seekLeft = getPaddingLeft();
        int seekRight = width - getPaddingRight();
        int progressY = barY + (touchTargetHeight - barHeight) / 2;
        seekBounds.set(seekLeft, barY, seekRight, barY + touchTargetHeight);
        progressBar.set(seekBounds.left + scrubberPadding, progressY, seekBounds.right - scrubberPadding,
                        progressY + barHeight);
        update();
    }

    @Override protected void onDraw(Canvas canvas) {
        canvas.save();
        drawTimeBar(canvas);
        drawPlayhead(canvas);
        canvas.restore();
    }

    private void drawTimeBar(Canvas canvas) {
        int progressBarHeight = progressBar.height();
        int barTop = progressBar.centerY() - progressBarHeight / 2;
        int barBottom = barTop + progressBarHeight;
        mTempRect.setEmpty();
        mTempRect.set(progressBar.left, barTop, progressBar.right, barBottom);
        canvas.drawRoundRect(mTempRect, defaultBarRadius, defaultBarRadius, unplayedPaint);

        int bufferedLeft = bufferedBar.left;
        int bufferedRight = bufferedBar.right;
        bufferedLeft = Math.max(bufferedLeft, scrubberBar.right);
        if (bufferedRight > bufferedLeft) {
            mTempRect.setEmpty();
            mTempRect.set(bufferedLeft, barTop, bufferedRight, barBottom);
            canvas.drawRoundRect(mTempRect, defaultBarRadius, defaultBarRadius, bufferedPaint);
        }

        if (scrubberBar.width() > 0) {
            mTempRect.setEmpty();
            mTempRect.set(scrubberBar.left, barTop, scrubberBar.right, barBottom);
            if (playedStart != -1 && playedEnd != -1) {
                LinearGradient playedShader =
                        new LinearGradient(scrubberBar.left, barTop / 2, scrubberBar.right, barBottom / 2, playedStart,
                                           playedEnd, Shader.TileMode.CLAMP);
                playedPaint.setShader(playedShader);
            }
            canvas.drawRoundRect(mTempRect, defaultBarRadius, defaultBarRadius, playedPaint);
        }
    }

    private void drawPlayhead(Canvas canvas) {
        if (duration <= 0) {
            return;
        }
        int playheadRadius = scrubberSize / 2;
        int playheadCenter = Util.constrainValue(scrubberBar.right, scrubberBar.left, progressBar.right);
        canvas.drawCircle(playheadCenter, scrubberBar.centerY(), playheadRadius, scrubberPaint);
    }

    @Override public boolean onTouchEvent(MotionEvent event) {
        if (!isEnabled() || duration <= 0) {
            return false;
        }
        Point touchPosition = resolveRelativeTouchPosition(event);
        int x = touchPosition.x;
        int y = touchPosition.y;
        switch (event.getAction()) {
            case MotionEvent.ACTION_DOWN:
                if (isInSeekBar(x, y)) {
                    startScrubbing();
                    positionScrubber(x);
                    scrubPosition = getScrubberPosition();
                    update();
                    invalidate();
                    return true;
                }
                break;
            case MotionEvent.ACTION_MOVE:
                if (scrubbing) {
                    if (y < fineScrubYThreshold) {
                        int relativeX = x - lastCoarseScrubXPosition;
                        positionScrubber(lastCoarseScrubXPosition + relativeX / FINE_SCRUB_RATIO);
                    } else {
                        lastCoarseScrubXPosition = x;
                        positionScrubber(x);
                    }
                    scrubPosition = getScrubberPosition();
                    if (listener != null) {
                        listener.onScrubMove(this, scrubPosition);
                    }
                    update();
                    invalidate();
                    return true;
                }
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (scrubbing) {
                    stopScrubbing(event.getAction() == MotionEvent.ACTION_CANCEL);
                    return true;
                }
                break;
            default:
                // Do nothing.
        }
        return false;
    }

    @Override public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (isEnabled()) {
            long positionIncrement = getPositionIncrement();
            switch (keyCode) {
                case KeyEvent.KEYCODE_DPAD_LEFT:
                    positionIncrement = -positionIncrement;
                    // Fall through.
                case KeyEvent.KEYCODE_DPAD_RIGHT:
                    if (scrubIncrementally(positionIncrement)) {
                        removeCallbacks(stopScrubbingRunnable);
                        postDelayed(stopScrubbingRunnable, STOP_SCRUBBING_TIMEOUT_MS);
                        return true;
                    }
                    break;
                case KeyEvent.KEYCODE_DPAD_CENTER:
                case KeyEvent.KEYCODE_ENTER:
                    if (scrubbing) {
                        removeCallbacks(stopScrubbingRunnable);
                        stopScrubbingRunnable.run();
                        return true;
                    }
                    break;
                default:
                    // Do nothing.
            }
        }
        return super.onKeyDown(keyCode, event);
    }

    private void startScrubbing() {
        scrubbing = true;
        updateScrubberState();
        ViewParent parent = getParent();
        if (parent != null) {
            parent.requestDisallowInterceptTouchEvent(true);
        }
        if (listener != null) {
            listener.onScrubStart(this);
        }
    }

    private void stopScrubbing(boolean canceled) {
        scrubbing = false;
        ViewParent parent = getParent();
        if (parent != null) {
            parent.requestDisallowInterceptTouchEvent(false);
        }
        updateScrubberState();
        invalidate();
        if (listener != null) {
            listener.onScrubStop(this, getScrubberPosition(), canceled);
        }
    }

    private void updateScrubberState() {
        scrubberSize = scrubbing ? scrubberDraggedSize
                : (isEnabled() && duration >= 0 ? scrubberEnabledSize : scrubberDisabledSize);
    }

    private void update() {
        bufferedBar.set(progressBar);
        scrubberBar.set(progressBar);
        long newScrubberTime = scrubbing ? scrubPosition : position;
        if (duration > 0) {
            int bufferedPixelWidth = (int) ((progressBar.width() * bufferedPosition) / duration);
            bufferedBar.right = Math.min(progressBar.left + bufferedPixelWidth, progressBar.right);
            int scrubberPixelPosition = (int) ((progressBar.width() * newScrubberTime) / duration);
            scrubberBar.right = Math.min(progressBar.left + scrubberPixelPosition, progressBar.right);
        } else {
            bufferedBar.right = progressBar.left;
            scrubberBar.right = progressBar.left;
        }
        invalidate(seekBounds);
    }

    private void positionScrubber(float xPosition) {
        scrubberBar.right = Util.constrainValue((int) xPosition, progressBar.left, progressBar.right);
    }

    private Point resolveRelativeTouchPosition(MotionEvent motionEvent) {
        if (locationOnScreen == null) {
            locationOnScreen = new int[2];
            touchPosition = new Point();
        }
        getLocationOnScreen(locationOnScreen);
        touchPosition.set(((int) motionEvent.getRawX()) - locationOnScreen[0],
                          ((int) motionEvent.getRawY()) - locationOnScreen[1]);
        return touchPosition;
    }

    private long getScrubberPosition() {
        if (progressBar.width() <= 0 || duration == C.TIME_UNSET) {
            return 0;
        }
        return (scrubberBar.width() * duration) / progressBar.width();
    }

    private boolean isInSeekBar(float x, float y) {
        return seekBounds.contains((int) x, (int) y);
    }

    private String getProgressText() {
        return Util.getStringForTime(formatBuilder, formatter, position);
    }

    private long getPositionIncrement() {
        return keyTimeIncrement == C.TIME_UNSET ? (duration == C.TIME_UNSET ? 0 : (duration / keyCountIncrement))
                : keyTimeIncrement;
    }

    /**
     * Incrementally scrubs the position by {@code positionChange}.
     *
     * @param positionChange The change in the scrubber position, in milliseconds. May be negative.
     * @return Returns whether the scrubber position changed.
     */
    private boolean scrubIncrementally(long positionChange) {
        if (duration <= 0) {
            return false;
        }
        long scrubberPosition = getScrubberPosition();
        scrubPosition = Util.constrainValue(scrubberPosition + positionChange, 0, duration);
        if (scrubPosition == scrubberPosition) {
            return false;
        }
        if (!scrubbing) {
            startScrubbing();
        }
        if (listener != null) {
            listener.onScrubMove(this, scrubPosition);
        }
        update();
        return true;
    }
}
