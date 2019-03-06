package com.ian.ianplayer;

import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.util.AttributeSet;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.SurfaceView;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;

import com.google.android.exoplayer2.C;
import com.google.android.exoplayer2.ExoPlaybackException;
import com.google.android.exoplayer2.ExoPlayer;
import com.google.android.exoplayer2.PlaybackParameters;
import com.google.android.exoplayer2.SimpleExoPlayer;
import com.google.android.exoplayer2.Timeline;
import com.google.android.exoplayer2.metadata.Metadata;
import com.google.android.exoplayer2.metadata.id3.ApicFrame;
import com.google.android.exoplayer2.source.TrackGroupArray;
import com.google.android.exoplayer2.trackselection.TrackSelection;
import com.google.android.exoplayer2.trackselection.TrackSelectionArray;
import com.google.android.exoplayer2.ui.AspectRatioFrameLayout;
import com.google.android.exoplayer2.util.Assertions;
import com.ian.ianplayer.timebar.SimpleTimeBar;

/**
 * 视频播放
 * <p>
 * Created by ian on 2017/6/3.
 */
public class IANVideoView extends FrameLayout implements IanVideoControlsView.VisibilityListener {
    private static final String TAG = "KRVideoView";

    private static final float DEFAULT_RATIO = 1.785F;
    private static final int SURFACE_TYPE_NONE = 0;
    private static final int SURFACE_TYPE_SURFACE_VIEW = 1;
    private static final int SURFACE_TYPE_TEXTURE_VIEW = 2;

    /**
     * 非播放控制分发
     */
    public interface ActionDispatcher {
        /**
         * 后退分发
         *
         * @param exit true可进行真正的退出，退出全屏状态时为false
         */
        void onBack(boolean exit);

        /**
         * 分享分发
         */
        void onShare();

        /**
         * 继续播放分发
         */
        void onForcePlay();

        /**
         * Toolbar显示分发
         */
        void onToolbarShow(boolean show);

        /**
         * 重播分发
         */
        void onReplay();
    }

    private final AspectRatioFrameLayout contentFrame;
    private final View shutterView;
    private final View surfaceView;
    private final ImageView artworkView;
    private final IanVideoControlsView controller;
    private final ComponentListener componentListener;
    private ActionDispatcher actionDispatcher;
    private final SimpleTimeBar simpleTimeBar;
    private final View loadingView;
    private final View endView;
    private final View tipsView;
    private final View shareView;
    private final View replayView;
    private final View forcePlayView;

    private SimpleExoPlayer player;
    private boolean useController;
    private boolean useArtwork;
    private Bitmap defaultArtwork;
    private int controllerShowTimeoutMs;
    private boolean controllerHideOnTouch;
    private boolean isAttachedToWindow;
    private float viewRatio;

    //    private final Runnable updateProgressAction = this::updateProgress;
    private final Runnable updateProgressAction = new Runnable() {
        @Override public void run() {
            updateProgress();
        }
    };

    public IANVideoView(@NonNull Context context) {
        this(context, null);
    }

    public IANVideoView(@NonNull Context context, @Nullable AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public IANVideoView(@NonNull Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        if (isInEditMode()) {
            contentFrame = null;
            shutterView = null;
            surfaceView = null;
            artworkView = null;
            controller = null;
            componentListener = null;
            simpleTimeBar = null;
            loadingView = null;
            endView = null;
            tipsView = null;
            shareView = null;
            replayView = null;
            forcePlayView = null;
            ImageView logo = new ImageView(context, attrs);
            logo.setImageResource(R.mipmap.ic_launcher);
            addView(logo);
            return;
        }

        int playerLayoutId = R.layout.ply_ui_video_view;
        boolean useArtwork = true;
        int defaultArtworkId = 0;
        boolean useController = true;
        int surfaceType = SURFACE_TYPE_SURFACE_VIEW;
        int resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT;
        int controllerShowTimeoutMs = IanVideoControlsView.DEFAULT_SHOW_TIMEOUT_MS;
        boolean controllerHideOnTouch = true;
        if (attrs != null) {
            TypedArray a = context.getTheme()
                    .obtainStyledAttributes(attrs, R.styleable.ply_ui_PlayerView, 0, 0);
            try {
                playerLayoutId = a.getResourceId(R.styleable.ply_ui_PlayerView_ply_ui_player_layout_id, playerLayoutId);
                useArtwork = a.getBoolean(R.styleable.ply_ui_PlayerView_ply_ui_use_artwork, useArtwork);
                defaultArtworkId =
                        a.getResourceId(R.styleable.ply_ui_PlayerView_ply_ui_default_artwork, defaultArtworkId);
                useController = a.getBoolean(R.styleable.ply_ui_PlayerView_ply_ui_use_controller, useController);
                surfaceType = a.getInt(R.styleable.ply_ui_PlayerView_ply_ui_surface_type, surfaceType);
                resizeMode = a.getInt(R.styleable.ply_ui_PlayerView_ply_ui_resize_mode, resizeMode);
                controllerShowTimeoutMs =
                        a.getInt(R.styleable.ply_ui_PlayerView_ply_ui_show_timeout, controllerShowTimeoutMs);
                controllerHideOnTouch =
                        a.getBoolean(R.styleable.ply_ui_PlayerView_ply_ui_hide_on_touch, controllerHideOnTouch);
                viewRatio = a.getFloat(R.styleable.ply_ui_PlayerView_ply_ui_ratio, DEFAULT_RATIO);
                checkRatio();
            } finally {
                a.recycle();
            }
        }

        LayoutInflater.from(context)
                .inflate(playerLayoutId, this);
        componentListener = new ComponentListener();
        setDescendantFocusability(FOCUS_AFTER_DESCENDANTS);

        // Content frame.
        contentFrame = (AspectRatioFrameLayout) findViewById(R.id.ply_ui_exo_content_frame);
        if (contentFrame != null) {
            setResizeModeRaw(contentFrame, resizeMode);
        }

        // Shutter view.
        shutterView = findViewById(R.id.ply_ui_exo_shutter);

        // Create a surface view and insert it into the content frame, if there is one.
        if (contentFrame != null && surfaceType != SURFACE_TYPE_NONE) {
            ViewGroup.LayoutParams params = new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                                                                       ViewGroup.LayoutParams.MATCH_PARENT);
            surfaceView =
                    surfaceType == SURFACE_TYPE_TEXTURE_VIEW ? new TextureView(context) : new SurfaceView(context);
            surfaceView.setLayoutParams(params);
            contentFrame.addView(surfaceView, 0);
        } else {
            surfaceView = null;
        }

        // Artwork view.
        artworkView = (ImageView) findViewById(R.id.ply_ui_exo_artwork);
        this.useArtwork = useArtwork && artworkView != null;
        if (defaultArtworkId != 0) {
            defaultArtwork = BitmapFactory.decodeResource(context.getResources(), defaultArtworkId);
        }

        // Playback control view.
        View controllerPlaceholder = findViewById(R.id.ply_ui_exo_controller_placeholder);
        if (controllerPlaceholder != null) {
            // Note: rewindMs and fastForwardMs are passed via attrs, so we don't need to make explicit
            // calls to set them.
            this.controller = new IanVideoControlsView(context, attrs);
            controller.setLayoutParams(controllerPlaceholder.getLayoutParams());
            ViewGroup parent = ((ViewGroup) controllerPlaceholder.getParent());
            int controllerIndex = parent.indexOfChild(controllerPlaceholder);
            parent.removeView(controllerPlaceholder);
            parent.addView(controller, controllerIndex);
        } else {
            this.controller = null;
        }
        this.controllerShowTimeoutMs = controller != null ? controllerShowTimeoutMs : 0;
        this.controllerHideOnTouch = controllerHideOnTouch;
        this.useController = useController && controller != null;

        hideController();

        setControllerVisibilityListener(this);
        simpleTimeBar = (SimpleTimeBar) findViewById(R.id.ply_ui_simple_progress);
        loadingView = findViewById(R.id.ply_ui_loading);
        endView = findViewById(R.id.ply_ui_end);
        tipsView = findViewById(R.id.ply_ui_tips);
        shareView = findViewById(R.id.ply_ui_exo_share);
        if (shareView != null) {
            shareView.setOnClickListener(componentListener);
        }
        replayView = findViewById(R.id.ply_ui_exo_replay);
        if (replayView != null) {
            replayView.setOnClickListener(componentListener);
        }
        forcePlayView = findViewById(R.id.ply_ui_exo_force_play);
        if (forcePlayView != null) {
            forcePlayView.setOnClickListener(componentListener);
        }
    }

    /**
     * Switches the view targeted by a given {@link SimpleExoPlayer}.
     *
     * @param player The player whose target view is being switched.
     * @param oldPlayerView The old view to detach from the player.
     * @param newPlayerView The new view to attach to the player.
     */
    public static void switchTargetView(@NonNull SimpleExoPlayer player, @Nullable IANVideoView oldPlayerView,
            @Nullable IANVideoView newPlayerView) {
        if (oldPlayerView == newPlayerView) {
            return;
        }
        // We attach the new view before detaching the old one because this ordering allows the player
        // to swap directly from one surface to another, without transitioning through a state where no
        // surface is attached. This is significantly more efficient and achieves a more seamless
        // transition when using platform provided video decoders.
        if (newPlayerView != null) {
            newPlayerView.setPlayer(player);
        }
        if (oldPlayerView != null) {
            oldPlayerView.setPlayer(null);
        }
    }

    /**
     * Returns the player currently set on this view, or null if no player is set.
     */
    public SimpleExoPlayer getPlayer() {
        return player;
    }

    /**
     * Set the {@link SimpleExoPlayer} to use. The {@link SimpleExoPlayer#setTextOutput} and
     * {@link SimpleExoPlayer#setVideoListener} method of the player will be called and previous
     * assignments are overridden.
     * <p>
     * To transition a {@link SimpleExoPlayer} from targeting one view to another, it's recommended to
     * use {@link #switchTargetView(SimpleExoPlayer, IANVideoView, IANVideoView)} rather
     * than this method. If you do wish to use this method directly, be sure to attach the player to
     * the new view <em>before</em> calling {@code setPlayer(null)} to detach it from the old one.
     * This ordering is significantly more efficient and may allow for more seamless transitions.
     *
     * @param player The {@link SimpleExoPlayer} to use.
     */
    public void setPlayer(SimpleExoPlayer player) {
        if (this.player == player) {
            return;
        }
        if (this.player != null) {
            this.player.removeListener(componentListener);
            this.player.clearVideoListener(componentListener);
            if (surfaceView instanceof TextureView) {
                this.player.clearVideoTextureView((TextureView) surfaceView);
            } else if (surfaceView instanceof SurfaceView) {
                this.player.clearVideoSurfaceView((SurfaceView) surfaceView);
            }
        }
        this.player = player;
        if (useController) {
            controller.setPlayer(player);
        }
        if (shutterView != null) {
            shutterView.setVisibility(VISIBLE);
        }
        if (player != null) {
            if (surfaceView instanceof TextureView) {
                player.setVideoTextureView((TextureView) surfaceView);
            } else if (surfaceView instanceof SurfaceView) {
                player.setVideoSurfaceView((SurfaceView) surfaceView);
            }
            player.setVideoListener(componentListener);
            player.addListener(componentListener);
            maybeShowController(false);
            updateForCurrentTrackSelections();
        } else {
            hideController();
            hideArtwork();
        }
    }

    /**
     * Sets the resize mode.
     *
     * @param resizeMode The resize mode.
     */
    public void setResizeMode(@AspectRatioFrameLayout.ResizeMode int resizeMode) {
        Assertions.checkState(contentFrame != null);
        contentFrame.setResizeMode(resizeMode);
    }

    /**
     * Returns whether artwork is displayed if present in the media.
     */
    public boolean getUseArtwork() {
        return useArtwork;
    }

    /**
     * Sets whether artwork is displayed if present in the media.
     *
     * @param useArtwork Whether artwork is displayed.
     */
    public void setUseArtwork(boolean useArtwork) {
        Assertions.checkState(!useArtwork || artworkView != null);
        if (this.useArtwork != useArtwork) {
            this.useArtwork = useArtwork;
            updateForCurrentTrackSelections();
        }
    }

    /**
     * Returns the default artwork to display.
     */
    public Bitmap getDefaultArtwork() {
        return defaultArtwork;
    }

    /**
     * Sets the default artwork to display if {@code useArtwork} is {@code true} and no artwork is
     * present in the media.
     *
     * @param defaultArtwork the default artwork to display.
     */
    public void setDefaultArtwork(Bitmap defaultArtwork) {
        if (this.defaultArtwork != defaultArtwork) {
            this.defaultArtwork = defaultArtwork;
            updateForCurrentTrackSelections();
        }
    }

    /**
     * Returns whether the playback controls can be shown.
     */
    public boolean getUseController() {
        return useController;
    }

    /**
     * Sets whether the playback controls can be shown. If set to {@code false} the playback controls
     * are never visible and are disconnected from the player.
     *
     * @param useController Whether the playback controls can be shown.
     */
    public void setUseController(boolean useController) {
        Assertions.checkState(!useController || controller != null);
        if (this.useController == useController) {
            return;
        }
        this.useController = useController;
        if (useController) {
            controller.setPlayer(player);
        } else if (controller != null) {
            controller.hide();
            controller.setPlayer(null);
        }
    }

    /**
     * Called to process media key events. Any {@link KeyEvent} can be passed but only media key
     * events will be handled. Does nothing if playback controls are disabled.
     *
     * @param event A key event.
     * @return Whether the key event was handled.
     */
    public boolean dispatchMediaKeyEvent(KeyEvent event) {
        return useController && controller.dispatchMediaKeyEvent(event);
    }

    /**
     * Shows the playback controls. Does nothing if playback controls are disabled.
     */
    public void showController() {
        if (useController) {
            maybeShowController(true);
        }
    }

    /**
     * Hides the playback controls. Does nothing if playback controls are disabled.
     */
    public void hideController() {
        if (controller != null) {
            controller.hide();
        }
    }

    /**
     * Returns the playback controls timeout. The playback controls are automatically hidden after
     * this duration of time has elapsed without user input and with playback or buffering in
     * progress.
     *
     * @return The timeout in milliseconds. A non-positive value will cause the controller to remain
     * visible indefinitely.
     */
    public int getControllerShowTimeoutMs() {
        return controllerShowTimeoutMs;
    }

    /**
     * Sets the playback controls timeout. The playback controls are automatically hidden after this
     * duration of time has elapsed without user input and with playback or buffering in progress.
     *
     * @param controllerShowTimeoutMs The timeout in milliseconds. A non-positive value will cause
     * the controller to remain visible indefinitely.
     */
    public void setControllerShowTimeoutMs(int controllerShowTimeoutMs) {
        Assertions.checkState(controller != null);
        this.controllerShowTimeoutMs = controllerShowTimeoutMs;
    }

    /**
     * Returns whether the playback controls are hidden by touch events.
     */
    public boolean getControllerHideOnTouch() {
        return controllerHideOnTouch;
    }

    /**
     * Sets whether the playback controls are hidden by touch events.
     *
     * @param controllerHideOnTouch Whether the playback controls are hidden by touch events.
     */
    public void setControllerHideOnTouch(boolean controllerHideOnTouch) {
        Assertions.checkState(controller != null);
        this.controllerHideOnTouch = controllerHideOnTouch;
    }

    /**
     * Set the {@link IanVideoControlsView.VisibilityListener}.
     *
     * @param listener The listener to be notified about visibility changes.
     */
    public void setControllerVisibilityListener(IanVideoControlsView.VisibilityListener listener) {
        Assertions.checkState(controller != null);
        controller.setVisibilityListener(listener);
    }

    /**
     * Sets the {@link IanVideoControlsView.ControlDispatcher}.
     *
     * @param controlDispatcher The {@link IanVideoControlsView.ControlDispatcher}, or null to use
     * {@link IanVideoControlsView#DEFAULT_CONTROL_DISPATCHER}.
     */
    public void setControlDispatcher(IanVideoControlsView.ControlDispatcher controlDispatcher) {
        Assertions.checkState(controller != null);
        controller.setControlDispatcher(controlDispatcher);
    }

    /**
     * Sets the rewind increment in milliseconds.
     *
     * @param rewindMs The rewind increment in milliseconds.
     */
    public void setRewindIncrementMs(int rewindMs) {
        Assertions.checkState(controller != null);
        controller.setRewindIncrementMs(rewindMs);
    }

    /**
     * Sets the fast forward increment in milliseconds.
     *
     * @param fastForwardMs The fast forward increment in milliseconds.
     */
    public void setFastForwardIncrementMs(int fastForwardMs) {
        Assertions.checkState(controller != null);
        controller.setFastForwardIncrementMs(fastForwardMs);
    }

    /**
     * Gets the view onto which video is rendered. This is either a {@link SurfaceView} (default)
     * or a {@link TextureView} if the {@code use_texture_view} view attribute has been set to true.
     *
     * @return Either a {@link SurfaceView} or a {@link TextureView}.
     */
    public View getVideoSurfaceView() {
        return surfaceView;
    }

    @Override public boolean onTouchEvent(MotionEvent ev) {
        if (!useController || player == null || ev.getActionMasked() != MotionEvent.ACTION_DOWN || extraViewVisible()) {
            return false;
        }
        if (!controller.isVisible()) {
            maybeShowController(true);
        } else if (controllerHideOnTouch) {
            controller.hide();
        }
        return true;
    }

    private boolean extraViewVisible() {
        return (loadingView != null && loadingView.getVisibility() == VISIBLE) || (endView != null
                && endView.getVisibility() == VISIBLE) || (tipsView != null && tipsView.getVisibility() == VISIBLE);
    }

    @Override public boolean onTrackballEvent(MotionEvent ev) {
        if (!useController || player == null) {
            return false;
        }
        maybeShowController(true);
        return true;
    }

    private void maybeShowController(boolean isForced) {
        if (!useController || player == null) {
            return;
        }
        int playbackState = player.getPlaybackState();
        boolean showIndefinitely = playbackState == ExoPlayer.STATE_IDLE
                || playbackState == ExoPlayer.STATE_ENDED
                || !player.getPlayWhenReady();
        boolean wasShowingIndefinitely = controller.isVisible() && controller.getShowTimeoutMs() <= 0;
        controller.setShowTimeoutMs(showIndefinitely ? 0 : controllerShowTimeoutMs);
        if (isForced || showIndefinitely || wasShowingIndefinitely) {
            controller.show();
        }
    }

    private void updateForCurrentTrackSelections() {
        if (player == null) {
            return;
        }
        TrackSelectionArray selections = player.getCurrentTrackSelections();
        for (int i = 0; i < selections.length; i++) {
            if (player.getRendererType(i) == C.TRACK_TYPE_VIDEO && selections.get(i) != null) {
                // Video enabled so artwork must be hidden. If the shutter is closed, it will be opened in
                // onRenderedFirstFrame().
                hideArtwork();
                return;
            }
        }
        // Video disabled so the shutter must be closed.
        if (shutterView != null) {
            shutterView.setVisibility(VISIBLE);
        }
        // Display artwork if enabled and available, else hide it.
        if (useArtwork) {
            for (int i = 0; i < selections.length; i++) {
                TrackSelection selection = selections.get(i);
                if (selection != null) {
                    for (int j = 0; j < selection.length(); j++) {
                        Metadata metadata = selection.getFormat(j).metadata;
                        if (metadata != null && setArtworkFromMetadata(metadata)) {
                            return;
                        }
                    }
                }
            }
            if (setArtworkFromBitmap(defaultArtwork)) {
                return;
            }
        }
        // Artwork disabled or unavailable.
        hideArtwork();
    }

    private boolean setArtworkFromMetadata(Metadata metadata) {
        for (int i = 0; i < metadata.length(); i++) {
            Metadata.Entry metadataEntry = metadata.get(i);
            if (metadataEntry instanceof ApicFrame) {
                byte[] bitmapData = ((ApicFrame) metadataEntry).pictureData;
                Bitmap bitmap = BitmapFactory.decodeByteArray(bitmapData, 0, bitmapData.length);
                return setArtworkFromBitmap(bitmap);
            }
        }
        return false;
    }

    private boolean setArtworkFromBitmap(Bitmap bitmap) {
        if (bitmap != null) {
            int bitmapWidth = bitmap.getWidth();
            int bitmapHeight = bitmap.getHeight();
            if (bitmapWidth > 0 && bitmapHeight > 0) {
                if (contentFrame != null) {
                    contentFrame.setAspectRatio((float) bitmapWidth / bitmapHeight);
                }
                artworkView.setImageBitmap(bitmap);
                artworkView.setVisibility(VISIBLE);
                return true;
            }
        }
        return false;
    }

    private void hideArtwork() {
        if (artworkView != null) {
            artworkView.setImageResource(android.R.color.transparent); // Clears any bitmap reference.
            artworkView.setVisibility(INVISIBLE);
        }
    }

    private static void setResizeModeRaw(AspectRatioFrameLayout aspectRatioFrame, int resizeMode) {
        aspectRatioFrame.setResizeMode(resizeMode);
    }

    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        isAttachedToWindow = true;
    }

    @Override protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        isAttachedToWindow = false;
        removeCallbacks(updateProgressAction);
    }

    @Override public void onVisibilityChange(int visibility) {
        if (simpleTimeBar == null) {
            return;
        }

        boolean visible = visibility == VISIBLE;
        if (visible) {
            simpleTimeBar.setVisibility(GONE);
            removeCallbacks(updateProgressAction);
        } else {
            simpleTimeBar.setVisibility(VISIBLE);
            updateProgress();
        }
    }

    private void updateProgress() {
        if (!isAttachedToWindow) {
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

        if (simpleTimeBar != null) {
            simpleTimeBar.updateTimeBar(duration, position, bufferedPosition);
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
     * 播放方向监听
     */
    public void setOrientationListener(IanVideoControlsView.OrientationListener orientationListener) {
        if (orientationListener != null && controller != null) {
            controller.setOrientationListener(orientationListener);
        }
    }

    /**
     * 视屏标题
     */
    public void setVideoTitle(String title) {
        if (controller != null) {
            controller.setVideoTitle(title);
        }
    }

    /**
     * 设置控制分发实现
     */
    public void setActionDispatcher(ActionDispatcher dispatcher) {
        if (dispatcher != null && controller != null) {
            actionDispatcher = dispatcher;
            controller.setActionDispatcher(dispatcher);
        }
    }

    public void performBackAction() {
        if (controller != null) {
            controller.performBackAction();
        }
    }

    private void checkRatio() {
        if (Math.abs(viewRatio - 0) < 0.000001f) {
            throw new IllegalArgumentException("Ratio cannot be zero!");
        }
    }

    @Override protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);
        if (controller != null && !controller.isPortrait()) {
            return;
        }

        int width = MeasureSpec.getSize(widthMeasureSpec);
        int height = (int) (width / viewRatio);
        setMeasuredDimension(width, height);

        widthMeasureSpec = MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY);
        heightMeasureSpec = MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY);
        measureChildren(widthMeasureSpec, heightMeasureSpec);
    }

    private final class ComponentListener implements SimpleExoPlayer.VideoListener, ExoPlayer.EventListener, OnClickListener {

        // SimpleExoPlayer.VideoListener implementation

        @Override public void onVideoSizeChanged(int width, int height, int unappliedRotationDegrees,
                float pixelWidthHeightRatio) {
            if (contentFrame != null) {
                float aspectRatio = height == 0 ? 1 : (width * pixelWidthHeightRatio) / height;
                contentFrame.setAspectRatio(aspectRatio);
            }
        }

        @Override public void onRenderedFirstFrame() {
            if (shutterView != null) {
                shutterView.setVisibility(INVISIBLE);
            }
        }

        @Override public void onTracksChanged(TrackGroupArray tracks, TrackSelectionArray selections) {
            updateForCurrentTrackSelections();
        }

        // ExoPlayer.EventListener implementation

        @Override public void onLoadingChanged(boolean isLoading) {
            // Do nothing.
        }

        @Override public void onPlayerStateChanged(boolean playWhenReady, int playbackState) {
            maybeShowController(false);
            updateProgress();
            changeControlViewByState(playbackState);
        }

        @Override public void onRepeatModeChanged(int repeatMode) {

        }

        @Override public void onPlayerError(ExoPlaybackException e) {
            // Do nothing.
        }

        @Override public void onPositionDiscontinuity() {
            updateProgress();
        }

        @Override public void onPlaybackParametersChanged(PlaybackParameters playbackParameters) {
            // Do nothing.
        }

        @Override public void onTimelineChanged(Timeline timeline, Object manifest) {
            updateProgress();
        }

        @Override public void onClick(View view) {
            if (player != null) {
                if (shareView == view) {
                    if (actionDispatcher != null) {
                        actionDispatcher.onShare();
                    }
                } else if (replayView == view) {
                    if (actionDispatcher != null) {
                        actionDispatcher.onReplay();
                    }
                } else if (forcePlayView == view) {
                    if (actionDispatcher != null) {
                        actionDispatcher.onForcePlay();
                    }
                }
            }
        }
    }

    private void changeControlViewByState(int playbackState) {
        if (playbackState == ExoPlayer.STATE_IDLE) {
            if (player != null && player.getPlayWhenReady()) {
                updateLoadingView(true);
            } else {
                updateLoadingView(false);
            }
        } else if (playbackState == ExoPlayer.STATE_BUFFERING) {
            updateLoadingView(true);
        } else if (playbackState == ExoPlayer.STATE_READY) {
            updateLoadingView(false);
        } else if (playbackState == ExoPlayer.STATE_ENDED) {
            // share and replay
            showEnd();
        }
    }

    public void updateLoadingView(boolean show) {
        hideTipsView();
        hideEndView();
        if (show) {
            showLoadingView();
            hideController();
            hideSimpleTimeBar();
        } else {
            hideLoadingView();
            showSimpleTimeBar();
        }

        if (actionDispatcher != null && controller != null && !controller.isPortrait()) {
            actionDispatcher.onToolbarShow(show);
        }
    }

    public void showEnd() {
        if (controller != null) {
            controller.handleBackAction();
        }
        hideController();
        hideLoadingView();
        hideTipsView();
        showEndView();
        hideSimpleTimeBar();
    }

    public void showTips() {
        if (player != null) {
            player.setPlayWhenReady(false);
        }

        hideController();
        hideLoadingView();
        showTipsView();
        hideEndView();
        hideSimpleTimeBar();

        if (actionDispatcher != null) {
            actionDispatcher.onToolbarShow(true);
        }
    }

    private void showSimpleTimeBar() {
        if (simpleTimeBar != null && controller != null && !controller.isVisible()) {
            simpleTimeBar.setVisibility(VISIBLE);
        }
    }

    public void hideSimpleTimeBar() {
        if (simpleTimeBar != null) {
            simpleTimeBar.setVisibility(GONE);
        }
    }

    private void showLoadingView() {
        if (loadingView != null) {
            loadingView.setVisibility(VISIBLE);
        }
    }

    private void hideLoadingView() {
        if (loadingView != null) {
            loadingView.setVisibility(GONE);
        }
    }

    private void showTipsView() {
        if (tipsView != null) {
            tipsView.setVisibility(VISIBLE);
        }
    }

    private void hideTipsView() {
        if (tipsView != null) {
            tipsView.setVisibility(GONE);
        }
    }

    private void showEndView() {
        if (endView != null) {
            endView.setVisibility(VISIBLE);
        }
    }

    private void hideEndView() {
        if (endView != null) {
            endView.setVisibility(GONE);
        }
    }
}
