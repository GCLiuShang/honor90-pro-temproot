package com.ghostlock.ksuapp;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.View;
import android.view.animation.LinearInterpolator;

/** Small breathing dot used next to the status title. */
public class PulseView extends View {

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final ValueAnimator anim;
    private float phase;
    private boolean active;

    public PulseView(Context c, int color) {
        super(c);
        paint.setColor(color);
        anim = ValueAnimator.ofFloat(0f, 1f);
        anim.setDuration(1400);
        anim.setRepeatCount(ValueAnimator.INFINITE);
        anim.setInterpolator(new LinearInterpolator());
        anim.addUpdateListener(a -> {
            phase = (float) a.getAnimatedValue();
            invalidate();
        });
    }

    public void setActive(boolean a) {
        active = a;
        if (a) {
            if (!anim.isStarted()) anim.start();
        } else {
            anim.cancel();
            phase = 0f;
        }
        invalidate();
    }

    public void setColor(int c) {
        paint.setColor(c);
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float cx = getWidth() / 2f, cy = getHeight() / 2f, r = Math.min(cx, cy);
        if (active) {
            paint.setAlpha((int) (90 * (1f - phase)));
            canvas.drawCircle(cx, cy, r * (0.45f + 0.55f * phase), paint);
        }
        paint.setAlpha(255);
        canvas.drawCircle(cx, cy, r * 0.42f, paint);
    }
}
