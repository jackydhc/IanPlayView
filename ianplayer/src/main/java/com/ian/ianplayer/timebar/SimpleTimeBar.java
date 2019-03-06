package com.ian.ianplayer.timebar;

import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Shader;
import android.support.annotation.Nullable;
import android.util.AttributeSet;
import android.view.View;

import com.ian.ianplayer.R;


/**
 * 视频播放时的进度，和播放控制View互斥出现
 * <p>
 * Created by ian on 2017/6/2.
 */
public class SimpleTimeBar extends View {
    private static final int DEFAULT_PLAYED_COLOR = 0xFFFFFFFF;

    private final Rect playedRect;
    private final Rect bufferedRect;
    private final Rect durationRect;

    private final Paint playedPaint;
    private final Paint bufferedPaint;
    private final Paint unplayedPaint;

    private long playedPosition;
    private long bufferedPosition;
    private long duration;

    private int playedStart = -1;
    private int playedEnd = -1;

    public SimpleTimeBar(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        playedRect = new Rect();
        bufferedRect = new Rect();
        durationRect = new Rect();

        playedPaint = new Paint();
        bufferedPaint = new Paint();
        unplayedPaint = new Paint();

        initAttrs(context, attrs);
    }

    private void initAttrs(Context context, AttributeSet attrs) {
        if (attrs != null) {
            TypedArray a = context.getTheme()
                    .obtainStyledAttributes(attrs, R.styleable.ply_ui_TimeBar, 0, 0);
            try {
                int playedColor = a.getInt(R.styleable.ply_ui_TimeBar_ply_ui_played_color, DEFAULT_PLAYED_COLOR);
                int unplayedColor = a.getInt(R.styleable.ply_ui_TimeBar_ply_ui_unplayed_color,
                                             getDefaultUnplayedColor(playedColor));
                int bufferedColor = a.getInt(R.styleable.ply_ui_TimeBar_ply_ui_buffered_color,
                                             getDefaultBufferedColor(playedColor));
                playedStart = a.getInt(R.styleable.ply_ui_TimeBar_ply_ui_played_start, -1);
                playedEnd = a.getInt(R.styleable.ply_ui_TimeBar_ply_ui_played_end, -1);
                playedPaint.setColor(playedColor);
                unplayedPaint.setColor(unplayedColor);
                bufferedPaint.setColor(bufferedColor);
            } finally {
                a.recycle();
            }
        } else {
            playedPaint.setColor(DEFAULT_PLAYED_COLOR);
            unplayedPaint.setColor(getDefaultUnplayedColor(DEFAULT_PLAYED_COLOR));
            bufferedPaint.setColor(getDefaultBufferedColor(DEFAULT_PLAYED_COLOR));
        }
    }

    private static int getDefaultUnplayedColor(int playedColor) {
        return 0x33000000 | (playedColor & 0x00FFFFFF);
    }

    private static int getDefaultBufferedColor(int playedColor) {
        return 0xCC000000 | (playedColor & 0x00FFFFFF);
    }

    @Override protected void onDraw(Canvas canvas) {
        canvas.save();
        drawTimeBar(canvas);
        canvas.restore();
    }

    private void drawTimeBar(Canvas canvas) {
        int top = 0;
        int bottom = getHeight();
        int left = getLeft();
        int right = getRight();
        durationRect.set(left, top, right, bottom);
        canvas.drawRect(durationRect, unplayedPaint);
        if (duration <= 0) {
            return;
        }
        // played
        int width = getWidth();
        int playedRight = left + (int) (playedPosition * width / duration);
        playedRect.set(left, top, playedRight, bottom);
        if (playedStart != -1 && playedEnd != -1) {
            LinearGradient playedShader = new LinearGradient(playedRect.left, playedRect.height() / 2, playedRect.right,
                                                             playedRect.height() / 2, playedStart, playedEnd,
                                                             Shader.TileMode.CLAMP);
            playedPaint.setShader(playedShader);
        }
        canvas.drawRect(playedRect, playedPaint);
        // buffered
        int bufferedRight = left + (int) (bufferedPosition * width / duration);
        bufferedRect.set(playedRight, top, bufferedRight, bottom);
        canvas.drawRect(bufferedRect, bufferedPaint);
    }

    public void updateTimeBar(long duration, long position, long bufferedPosition) {
        this.duration = duration;
        this.playedPosition = position;
        this.bufferedPosition = bufferedPosition;
        invalidate();
    }
}
