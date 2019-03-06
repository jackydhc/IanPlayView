package com.ian.ianplayer;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.content.pm.ActivityInfo;
import android.content.res.TypedArray;
import android.os.SystemClock;
import android.support.annotation.IntDef;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.util.AttributeSet;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.OrientationEventListener;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import com.google.android.exoplayer2.C;
import com.google.android.exoplayer2.ExoPlaybackException;
import com.google.android.exoplayer2.ExoPlayer;
import com.google.android.exoplayer2.PlaybackParameters;
import com.google.android.exoplayer2.Timeline;
import com.google.android.exoplayer2.source.TrackGroupArray;
import com.google.android.exoplayer2.trackselection.TrackSelectionArray;
import com.google.android.exoplayer2.util.Util;
import com.ian.ianplayer.timebar.TimeBar;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.Formatter;
import java.util.Locale;

/**
 * 视频控制
 * <p>
 * Created by ian on 2017/6/3.
 */
public class IanVideoControlsView extends FrameLayout {
    private static final String TAG = "KRVideoControlsView";

    public static final int SENSOR_UNKNOWN = -1;
    public static final int SENSOR_PORTRAIT = SENSOR_UNKNOWN + 1;
    public static final int SENSOR_LANDSCAPE = SENSOR_PORTRAIT + 1;
    public static final int SENSOR_REVERSE_LANDSCAPE = SENSOR_LANDSCAPE + 1;

    @IntDef({ SENSOR_UNKNOWN, SENSOR_PORTRAIT, SENSOR_LANDSCAPE, SENSOR_REVERSE_LANDSCAPE })
    @Retention(RetentionPolicy.SOURCE)
    public @interface SensorOrientationType {

    }

    public interface OrientationListener {
        void onOrientationChange(@SensorOrientationType int orientation);
    }

    /**
     * Listener to be notified about changes of the visibility of the UI control.
     */
    public interface VisibilityListener {

        /**
         * Called when the visibility changes.
         *
         * @param visibility The new visibility. Either {@link View#VISIBLE} or {@link View#GONE}.
         */
        void onVisibilityChange(int visibility);

    }

    /**
     * Dispatches operations to the player.
     * <p>
     * Implementations may choose to suppress (e.g. prevent playback from resuming if audio focus is
     * denied) or modify (e.g. change the seek position to prevent a user from seeking past a
     * non-skippable advert) operations.
     */
    public interface ControlDispatcher {

        /**
         * Dispatches a {@link ExoPlayer#setPlayWhenReady(boolean)} operation.
         *
         * @param player The player to which the operation should be dispatched.
         * @param playWhenReady Whether playback should proceed when ready.
         * @return True if the operation was dispatched. False if suppressed.
         */
        boolean dispatchSetPlayWhenReady(ExoPlayer player, boolean playWhenReady);

        /**
         * Dispatches a {@link ExoPlayer#seekTo(int, long)} operation.
         *
         * @param player The player to which the operation should be dispatched.
         * @param windowIndex The index of the window.
         * @param positionMs The seek position in the specified window, or {@link C#TIME_UNSET} to seek
         * to the window's default position.
         * @return True if the operation was dispatched. False if suppressed.
         */
        boolean dispatchSeekTo(ExoPlayer player, int windowIndex, long positionMs);

    }

    /**
     * Default {@link ControlDispatcher} that dispatches operations to the player without
     * modification.
     */
    public static final ControlDispatcher DEFAULT_CONTROL_DISPATCHER = new ControlDispatcher() {

        @Override public boolean dispatchSetPlayWhenReady(ExoPlayer player, boolean playWhenReady) {
            player.setPlayWhenReady(playWhenReady);
            return true;
        }

        @Override public boolean dispatchSeekTo(ExoPlayer player, int windowIndex, long positionMs) {
            player.seekTo(windowIndex, positionMs);
            return true;
        }

    };

    public static final int DEFAULT_FAST_FORWARD_MS = 15000;
    public static final int DEFAULT_REWIND_MS = 5000;
    public static final int DEFAULT_SHOW_TIMEOUT_MS = 5000;
    private static final long MAX_POSITION_FOR_SEEK_TO_PREVIOUS = 3000;

    private final ComponentListener componentListener;
    private final View previousButton;
    private final View nextButton;
    private final View playButton;
    private final View pauseButton;
    private final View fastForwardButton;
    private final View rewindButton;
    private final TextView durationView;
    private final TextView positionView;
    private final TimeBar timeBar;
    private final StringBuilder formatBuilder;
    private final Formatter formatter;
    private final Timeline.Period period;
    private final Timeline.Window window;
    private final ImageView fullButton;
    private final View extraLeftSpace;
    private final View extraMiddleSpace;
    private final View extraRightSpace;
    private final View extraBottomSpace;
    private final View extraTopSpace;
    private final TextView titleView;
    private final View backView;

    private ExoPlayer player;
    private ControlDispatcher controlDispatcher;
    private IANVideoView.ActionDispatcher actionDispatcher;
    private VisibilityListener visibilityListener;
    private OrientationListener orientationListener;
    private OrientationEventListener orientationEventListener;

    private boolean isAttachedToWindow;
    private boolean scrubbing;
    private int rewindMs;
    private int fastForwardMs;
    private int showTimeoutMs;
    private long hideAtMs;
    private int lastScreenOrientation = SENSOR_UNKNOWN;
    /**
     * 默认竖屏
     */
    private boolean portrait = true;
    /**
     * 仅横屏转向
     */
    private boolean orientationOnlyLandscape;

    //    private final Runnable updateProgressAction = this::updateProgress;
    private final Runnable updateProgressAction = new Runnable() {
        @Override public void run() {
            updateProgress();
        }
    };

    //    private final Runnable hideAction = this::hide;
    private final Runnable hideAction = new Runnable() {
        @Override public void run() {
            hide();
        }
    };


    public IanVideoControlsView(@NonNull Context context) {
        this(context, null);
    }

    public IanVideoControlsView(@NonNull Context context, @Nullable AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public IanVideoControlsView(@NonNull Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        int controllerLayoutId = R.layout.ply_ui_video_control_view;
        rewindMs = DEFAULT_REWIND_MS;
        fastForwardMs = DEFAULT_FAST_FORWARD_MS;
        showTimeoutMs = DEFAULT_SHOW_TIMEOUT_MS;
        if (attrs != null) {
            TypedArray a = context.getTheme()
                    .obtainStyledAttributes(attrs, R.styleable.ply_ui_ControlView, 0, 0);
            try {
                rewindMs = a.getInt(R.styleable.ply_ui_ControlView_ply_ui_rewind_increment, rewindMs);
                fastForwardMs = a.getInt(R.styleable.ply_ui_ControlView_ply_ui_fastforward_increment, fastForwardMs);
                showTimeoutMs = a.getInt(R.styleable.ply_ui_ControlView_ply_ui_show_timeout, showTimeoutMs);
                orientationOnlyLandscape =
                        a.getBoolean(R.styleable.ply_ui_ControlView_ply_ui_orientation_only_landscape,
                                     orientationOnlyLandscape);
                controllerLayoutId =
                        a.getResourceId(R.styleable.ply_ui_ControlView_ply_ui_controller_layout_id, controllerLayoutId);
            } finally {
                a.recycle();
            }
        }

        period = new Timeline.Period();
        window = new Timeline.Window();
        formatBuilder = new StringBuilder();
        formatter = new Formatter(formatBuilder, Locale.getDefault());
        componentListener = new ComponentListener();
        controlDispatcher = DEFAULT_CONTROL_DISPATCHER;

        LayoutInflater.from(context)
                .inflate(controllerLayoutId, this);
        setDescendantFocusability(FOCUS_AFTER_DESCENDANTS);

        durationView = (TextView) findViewById(R.id.ply_ui_exo_duration);
        positionView = (TextView) findViewById(R.id.ply_ui_exo_position);
        timeBar = (TimeBar) findViewById(R.id.ply_ui_exo_progress);
        if (timeBar != null) {
            timeBar.setListener(componentListener);
        }
        playButton = findViewById(R.id.ply_ui_exo_play);
        if (playButton != null) {
            playButton.setOnClickListener(componentListener);
        }
        pauseButton = findViewById(R.id.ply_ui_exo_pause);
        if (pauseButton != null) {
            pauseButton.setOnClickListener(componentListener);
        }
        previousButton = findViewById(R.id.ply_ui_exo_prev);
        if (previousButton != null) {
            previousButton.setOnClickListener(componentListener);
        }
        nextButton = findViewById(R.id.ply_ui_exo_next);
        if (nextButton != null) {
            nextButton.setOnClickListener(componentListener);
        }
        rewindButton = findViewById(R.id.ply_ui_exo_rew);
        if (rewindButton != null) {
            rewindButton.setOnClickListener(componentListener);
        }
        fastForwardButton = findViewById(R.id.ply_ui_exo_ffwd);
        if (fastForwardButton != null) {
            fastForwardButton.setOnClickListener(componentListener);
        }

        fullButton = (ImageView) findViewById(R.id.exo_full);
        if (fullButton != null) {
            fullButton.setOnClickListener(componentListener);
        }

        extraLeftSpace = findViewById(R.id.extra_left);
        extraMiddleSpace = findViewById(R.id.extra_middle);
        extraRightSpace = findViewById(R.id.extra_right);
        extraBottomSpace = findViewById(R.id.extra_bottom);
        extraTopSpace = findViewById(R.id.extra_top);
        titleView = (TextView) findViewById(R.id.title);
        backView = findViewById(R.id.back);
        if (backView != null) {
            backView.setOnClickListener(componentListener);
        }

        initOrientationEventListener();
    }

    private void initOrientationEventListener() {
        orientationEventListener = new OrientationEventListener(getContext()) {
            @Override public void onOrientationChanged(int orientation) {
                if (orientation == OrientationEventListener.ORIENTATION_UNKNOWN) {
                    // 手机平放时，检测不到有效的角度
                    changeOrientation(SENSOR_UNKNOWN);
                    return;
                }

                if (portrait && orientationOnlyLandscape) {
                    // 竖屏且开启仅横屏转向
                    return;
                }

                if (((orientation >= 0) && (orientation <= 45)) || (orientation > 315)) {
                    if (orientationOnlyLandscape) {
                        return;
                    }
                    orientation = SENSOR_PORTRAIT;
                } else if ((orientation > 45) && (orientation <= 135)) {
                    orientation = SENSOR_REVERSE_LANDSCAPE;
                } else if ((orientation > 135) && (orientation <= 225)) {
                    if (orientationOnlyLandscape) {
                        return;
                    }
                    orientation = SENSOR_PORTRAIT;
                } else if ((orientation > 225) && (orientation <= 315)) {
                    orientation = SENSOR_LANDSCAPE;
                } else {
                    if (orientationOnlyLandscape) {
                        return;
                    }
                    orientation = SENSOR_PORTRAIT;
                }

                if (lastScreenOrientation == orientation) {
                    // 方向没有变化
                    return;
                }
                lastScreenOrientation = orientation;
                changeOrientation(orientation);
            }
        };
        orientationEventListener.enable();
    }

    private void changeOrientation(@SensorOrientationType int orientation) {
        if (orientationListener == null) {
            return;
        }

        if (orientation != SENSOR_UNKNOWN) {
            orientationListener.onOrientationChange(orientation);
        }

        Context context = getContext();
        Activity activity;
        if (!(context instanceof Activity)) {
            return;
        }

        activity = (Activity) context;
        switch (orientation) {
            case SENSOR_PORTRAIT:
                setPortrait(true);
                activity.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
                break;
            case SENSOR_LANDSCAPE:
                setPortrait(false);
                activity.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
                break;
            case SENSOR_REVERSE_LANDSCAPE:
                setPortrait(false);
                activity.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE);
                break;
            case SENSOR_UNKNOWN:
            default:
                break;
        }
    }

    public void setPortrait(boolean portrait) {
        this.portrait = portrait;
        showPortraitOrLandscape();
    }

    public boolean isPortrait() {
        return portrait;
    }

    private void showPortraitOrLandscape() {
        fullButton.setActivated(!portrait);
        extraLeftSpace.setVisibility(portrait ? GONE : VISIBLE);
        extraMiddleSpace.setVisibility(portrait ? GONE : VISIBLE);
        extraRightSpace.setVisibility(portrait ? GONE : VISIBLE);
        extraBottomSpace.setVisibility(portrait ? GONE : VISIBLE);
        extraTopSpace.setVisibility(portrait ? GONE : VISIBLE);
        titleView.setVisibility(portrait ? GONE : VISIBLE);
        backView.setVisibility(portrait ? GONE : VISIBLE);
    }

    public void setOrientationListener(OrientationListener orientationListener) {
        this.orientationListener = orientationListener;
    }

    public void setVideoTitle(String title) {
        if (titleView != null) {
            titleView.setText(title);
        }
    }

    /**
     * Returns the player currently being controlled by this view, or null if no player is set.
     */
    public ExoPlayer getPlayer() {
        return player;
    }

    /**
     * Sets the {@link ExoPlayer} to control.
     *
     * @param player The {@code ExoPlayer} to control.
     */
    public void setPlayer(ExoPlayer player) {
        if (this.player == player) {
            return;
        }
        if (this.player != null) {
            this.player.removeListener(componentListener);
        }
        this.player = player;
        if (player != null) {
            player.addListener(componentListener);
        }
        updateAll();
    }

    private void updateAll() {
        updatePlayPauseButton();
        updateNavigation();
        updateProgress();
    }

    private void updatePlayPauseButton() {
        if (!isVisible() || !isAttachedToWindow) {
            return;
        }
        boolean requestPlayPauseFocus = false;
        boolean playing = player != null && player.getPlayWhenReady();
        if (playButton != null) {
            requestPlayPauseFocus |= playing && playButton.isFocused();
            playButton.setVisibility(playing ? GONE : VISIBLE);
        }
        if (pauseButton != null) {
            requestPlayPauseFocus |= !playing && pauseButton.isFocused();
            pauseButton.setVisibility(!playing ? GONE : VISIBLE);
        }
        if (requestPlayPauseFocus) {
            requestPlayPauseFocus();
        }
    }

    private void requestPlayPauseFocus() {
        boolean playing = player != null && player.getPlayWhenReady();
        if (!playing && playButton != null) {
            playButton.requestFocus();
        } else if (playing && pauseButton != null) {
            pauseButton.requestFocus();
        }
    }

    private void updateNavigation() {
        if (!isVisible() || !isAttachedToWindow) {
            return;
        }
        Timeline timeline = player != null ? player.getCurrentTimeline() : null;
        boolean haveNonEmptyTimeline = timeline != null && !timeline.isEmpty();
        boolean isSeekable = false;
        boolean enablePrevious = false;
        boolean enableNext = false;
        if (haveNonEmptyTimeline) {
            int windowIndex = player.getCurrentWindowIndex();
            timeline.getWindow(windowIndex, window);
            isSeekable = window.isSeekable;
            enablePrevious = windowIndex > 0 || isSeekable || !window.isDynamic;
            enableNext = (windowIndex < timeline.getWindowCount() - 1) || window.isDynamic;
            if (player.isPlayingAd()) {
                // Always hide player controls during ads.
                hide();
            }
        }
        setButtonEnabled(enablePrevious, previousButton);
        setButtonEnabled(enableNext, nextButton);
        setButtonEnabled(fastForwardMs > 0 && isSeekable, fastForwardButton);
        setButtonEnabled(rewindMs > 0 && isSeekable, rewindButton);
        if (timeBar != null) {
            timeBar.setEnabled(isSeekable);
        }
    }

    private void setButtonEnabled(boolean enabled, View view) {
        if (view == null) {
            return;
        }
        view.setEnabled(enabled);
        view.setAlpha(enabled ? 1f : 0.3f);
        view.setVisibility(VISIBLE);
    }

    private void updateProgress() {
        if (!isVisible() || !isAttachedToWindow) {
            return;
        }

        long position = 0;
        long bufferedPosition = 0;
        long duration = 0;
        if (player != null) {
            position = player.getCurrentPosition();
            bufferedPosition = player.getBufferedPosition();
            duration = player.getDuration();
        }
        if (durationView != null) {
            durationView.setText(Util.getStringForTime(formatBuilder, formatter, duration));
        }
        if (positionView != null && !scrubbing) {
            positionView.setText(Util.getStringForTime(formatBuilder, formatter, position));
        }
        if (timeBar != null) {
            timeBar.setPosition(position);
            timeBar.setBufferedPosition(bufferedPosition);
            timeBar.setDuration(duration);
        }

        // Cancel any pending updates and schedule a new one if necessary.
        removeCallbacks(updateProgressAction);
        int playbackState = player == null ? ExoPlayer.STATE_IDLE : player.getPlaybackState();
        if (playbackState != ExoPlayer.STATE_IDLE && playbackState != ExoPlayer.STATE_ENDED) {
            long delayMs;
            if (player.getPlayWhenReady() && playbackState == ExoPlayer.STATE_READY) {
                delayMs = 1000 - (position % 1000);
                if (delayMs < 200) {
                    delayMs += 1000;
                }
            } else {
                delayMs = 1000;
            }
            postDelayed(updateProgressAction, delayMs);
        }
    }

    /**
     * Returns whether the controller is currently visible.
     */
    public boolean isVisible() {
        return getVisibility() == VISIBLE;
    }

    /**
     * Sets the {@link VisibilityListener}.
     *
     * @param listener The listener to be notified about visibility changes.
     */
    public void setVisibilityListener(VisibilityListener listener) {
        this.visibilityListener = listener;
    }

    /**
     * Sets the {@link ControlDispatcher}.
     *
     * @param controlDispatcher The {@link ControlDispatcher}, or null to use
     * {@link #DEFAULT_CONTROL_DISPATCHER}.
     */
    public void setControlDispatcher(ControlDispatcher controlDispatcher) {
        this.controlDispatcher = controlDispatcher == null ? DEFAULT_CONTROL_DISPATCHER : controlDispatcher;
    }

    /**
     * Sets the rewind increment in milliseconds.
     *
     * @param rewindMs The rewind increment in milliseconds. A non-positive value will cause the
     * rewind button to be disabled.
     */
    public void setRewindIncrementMs(int rewindMs) {
        this.rewindMs = rewindMs;
        updateNavigation();
    }

    /**
     * Sets the fast forward increment in milliseconds.
     *
     * @param fastForwardMs The fast forward increment in milliseconds. A non-positive value will
     * cause the fast forward button to be disabled.
     */
    public void setFastForwardIncrementMs(int fastForwardMs) {
        this.fastForwardMs = fastForwardMs;
        updateNavigation();
    }

    /**
     * Returns the playback controls timeout. The playback controls are automatically hidden after
     * this duration of time has elapsed without user input.
     *
     * @return The duration in milliseconds. A non-positive value indicates that the controls will
     * remain visible indefinitely.
     */
    public int getShowTimeoutMs() {
        return showTimeoutMs;
    }

    /**
     * Sets the playback controls timeout. The playback controls are automatically hidden after this
     * duration of time has elapsed without user input.
     *
     * @param showTimeoutMs The duration in milliseconds. A non-positive value will cause the controls
     * to remain visible indefinitely.
     */
    public void setShowTimeoutMs(int showTimeoutMs) {
        this.showTimeoutMs = showTimeoutMs;
    }

    /**
     * Shows the playback controls. If {@link #getShowTimeoutMs()} is positive then the controls will
     * be automatically hidden after this duration of time has elapsed without user input.
     */
    public void show() {
        if (!isVisible()) {
            setVisibility(VISIBLE);
            if (visibilityListener != null) {
                visibilityListener.onVisibilityChange(getVisibility());
            }
            updateAll();
            requestPlayPauseFocus();
        }
        // Call hideAfterTimeout even if already visible to reset the timeout.
        hideAfterTimeout();
    }

    private void hideAfterTimeout() {
        removeCallbacks(hideAction);
        if (showTimeoutMs > 0) {
            hideAtMs = SystemClock.uptimeMillis() + showTimeoutMs;
            if (isAttachedToWindow) {
                postDelayed(hideAction, showTimeoutMs);
            }
        } else {
            hideAtMs = C.TIME_UNSET;
        }
    }

    /**
     * Hides the controller.
     */
    public void hide() {
        if (isVisible()) {
            setVisibility(GONE);
            if (visibilityListener != null) {
                visibilityListener.onVisibilityChange(getVisibility());
            }
            removeCallbacks(updateProgressAction);
            removeCallbacks(hideAction);
            hideAtMs = C.TIME_UNSET;
        }
    }

    private void previous() {
        Timeline timeline = player.getCurrentTimeline();
        if (timeline.isEmpty()) {
            return;
        }
        int windowIndex = player.getCurrentWindowIndex();
        timeline.getWindow(windowIndex, window);
        if (windowIndex > 0 && (player.getCurrentPosition() <= MAX_POSITION_FOR_SEEK_TO_PREVIOUS || (window.isDynamic
                && !window.isSeekable))) {
            seekTo(windowIndex - 1, C.TIME_UNSET);
        } else {
            seekTo(0);
        }
    }

    private void next() {
        Timeline timeline = player.getCurrentTimeline();
        if (timeline.isEmpty()) {
            return;
        }
        int windowIndex = player.getCurrentWindowIndex();
        if (windowIndex < timeline.getWindowCount() - 1) {
            seekTo(windowIndex + 1, C.TIME_UNSET);
        } else if (timeline.getWindow(windowIndex, window, false).isDynamic) {
            seekTo(windowIndex, C.TIME_UNSET);
        }
    }

    private void rewind() {
        if (rewindMs <= 0) {
            return;
        }
        seekTo(Math.max(player.getCurrentPosition() - rewindMs, 0));
    }

    private void fastForward() {
        if (fastForwardMs <= 0) {
            return;
        }
        seekTo(Math.min(player.getCurrentPosition() + fastForwardMs, player.getDuration()));
    }

    private void seekTo(long positionMs) {
        seekTo(player.getCurrentWindowIndex(), positionMs);
    }

    private void seekTo(int windowIndex, long positionMs) {
        boolean dispatched = controlDispatcher.dispatchSeekTo(player, windowIndex, positionMs);
        if (!dispatched) {
            // The seek wasn't dispatched. If the progress bar was dragged by the user to perform the
            // seek then it'll now be in the wrong position. Trigger a progress update to snap it back.
            updateProgress();
        }
    }

    private void seekToTimebarPosition(long timebarPositionMs) {
        seekTo(timebarPositionMs);
    }

    @Override public void onAttachedToWindow() {
        super.onAttachedToWindow();
        isAttachedToWindow = true;
        if (hideAtMs != C.TIME_UNSET) {
            long delayMs = hideAtMs - SystemClock.uptimeMillis();
            if (delayMs <= 0) {
                hide();
            } else {
                postDelayed(hideAction, delayMs);
            }
        }
        updateAll();
    }

    @Override public void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        isAttachedToWindow = false;
        removeCallbacks(updateProgressAction);
        removeCallbacks(hideAction);
        orientationEventListener.disable();
    }

    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        boolean handled = dispatchMediaKeyEvent(event) || super.dispatchKeyEvent(event);
        if (handled) {
            show();
        }
        return handled;
    }

    /**
     * Called to process media key events. Any {@link KeyEvent} can be passed but only media key
     * events will be handled.
     *
     * @param event A key event.
     * @return Whether the key event was handled.
     */
    public boolean dispatchMediaKeyEvent(KeyEvent event) {
        int keyCode = event.getKeyCode();
        if (player == null || !isHandledMediaKey(keyCode)) {
            return false;
        }
        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            switch (keyCode) {
                case KeyEvent.KEYCODE_MEDIA_FAST_FORWARD:
                    fastForward();
                    break;
                case KeyEvent.KEYCODE_MEDIA_REWIND:
                    rewind();
                    break;
                case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
                    controlDispatcher.dispatchSetPlayWhenReady(player, !player.getPlayWhenReady());
                    break;
                case KeyEvent.KEYCODE_MEDIA_PLAY:
                    controlDispatcher.dispatchSetPlayWhenReady(player, true);
                    break;
                case KeyEvent.KEYCODE_MEDIA_PAUSE:
                    controlDispatcher.dispatchSetPlayWhenReady(player, false);
                    break;
                case KeyEvent.KEYCODE_MEDIA_NEXT:
                    next();
                    break;
                case KeyEvent.KEYCODE_MEDIA_PREVIOUS:
                    previous();
                    break;
                default:
                    break;
            }
        }
        show();
        return true;
    }

    @SuppressLint("InlinedApi") private static boolean isHandledMediaKey(int keyCode) {
        return keyCode == KeyEvent.KEYCODE_MEDIA_FAST_FORWARD
                || keyCode == KeyEvent.KEYCODE_MEDIA_REWIND
                || keyCode == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
                || keyCode == KeyEvent.KEYCODE_MEDIA_PLAY
                || keyCode == KeyEvent.KEYCODE_MEDIA_PAUSE
                || keyCode == KeyEvent.KEYCODE_MEDIA_NEXT
                || keyCode == KeyEvent.KEYCODE_MEDIA_PREVIOUS;
    }

    private final class ComponentListener implements ExoPlayer.EventListener, TimeBar.OnScrubListener, OnClickListener {

        @Override public void onScrubStart(TimeBar timeBar) {
            removeCallbacks(hideAction);
            scrubbing = true;
        }

        @Override public void onScrubMove(TimeBar timeBar, long position) {
            if (positionView != null) {
                positionView.setText(Util.getStringForTime(formatBuilder, formatter, position));
            }
        }

        @Override public void onScrubStop(TimeBar timeBar, long position, boolean canceled) {
            scrubbing = false;
            if (!canceled && player != null) {
                seekToTimebarPosition(position);
            }
            hideAfterTimeout();
        }

        @Override public void onPlayerStateChanged(boolean playWhenReady, int playbackState) {
            updatePlayPauseButton();
            updateProgress();
        }

        @Override public void onRepeatModeChanged(int repeatMode) {

        }

        @Override public void onPositionDiscontinuity() {
            updateNavigation();
            updateProgress();
        }

        @Override public void onPlaybackParametersChanged(PlaybackParameters playbackParameters) {
            // Do nothing.
        }

        @Override public void onTimelineChanged(Timeline timeline, Object manifest) {
            updateNavigation();
            updateProgress();
        }

        @Override public void onLoadingChanged(boolean isLoading) {
            // Do nothing.
        }

        @Override public void onTracksChanged(TrackGroupArray tracks, TrackSelectionArray selections) {
            // Do nothing.
        }

        @Override public void onPlayerError(ExoPlaybackException error) {
            // Do nothing.
        }

        @Override public void onClick(View view) {
            if (player != null) {
                if (nextButton == view) {
                    next();
                } else if (previousButton == view) {
                    previous();
                } else if (fastForwardButton == view) {
                    fastForward();
                } else if (rewindButton == view) {
                    rewind();
                } else if (playButton == view) {
                    controlDispatcher.dispatchSetPlayWhenReady(player, true);
                } else if (pauseButton == view) {
                    controlDispatcher.dispatchSetPlayWhenReady(player, false);
                } else if (fullButton == view) {
                    boolean activated = fullButton.isActivated();
                    changeOrientation(!activated ? SENSOR_LANDSCAPE : SENSOR_PORTRAIT);
                    fullButton.setActivated(!activated);
                } else if (backView == view) {
                    if (actionDispatcher != null) {
                        actionDispatcher.onBack(handleBackAction());
                    }
                }
            }
            hideAfterTimeout();
        }
    }

    public void setActionDispatcher(IANVideoView.ActionDispatcher dispatcher) {
        this.actionDispatcher = dispatcher;
    }

    protected boolean handleBackAction() {
        if (fullButton != null && !portrait) {
            fullButton.performClick();
            setPortrait(true);
            return false;
        }
        return true;
    }

    public void performBackAction() {
        if (backView != null && actionDispatcher != null) {
            backView.performClick();
        }
    }
}
