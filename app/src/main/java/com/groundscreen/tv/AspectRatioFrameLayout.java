package com.groundscreen.tv;

import android.content.Context;
import android.util.AttributeSet;
import android.widget.FrameLayout;

/** Fits its content to the largest box with the given aspect ratio (letterboxing). */
public final class AspectRatioFrameLayout extends FrameLayout {

    private float ratio = 16f / 9f;

    public AspectRatioFrameLayout(Context context) {
        super(context);
    }

    public AspectRatioFrameLayout(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public void setAspectRatio(float r) {
        if (r > 0 && Math.abs(r - ratio) > 0.001f) {
            ratio = r;
            requestLayout();
        }
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int w = MeasureSpec.getSize(widthMeasureSpec);
        int h = MeasureSpec.getSize(heightMeasureSpec);
        if (w > 0 && h > 0) {
            if ((float) w / h > ratio) {
                w = Math.round(h * ratio);
            } else {
                h = Math.round(w / ratio);
            }
        }
        super.onMeasure(MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY));
    }
}
