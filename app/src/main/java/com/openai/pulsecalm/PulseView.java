package com.openai.pulsecalm;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.util.AttributeSet;
import android.view.View;

public class PulseView extends View {
    private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private boolean active = false;
    private int bpm = 0;
    private long started = 0L;

    public PulseView(Context context, AttributeSet attrs) {
        super(context, attrs);
        ring.setStyle(Paint.Style.STROKE);
        ring.setStrokeWidth(dp(3));
        ring.setColor(0xFFBFE7DE);
        fill.setStyle(Paint.Style.FILL);
        fill.setColor(0xFFE7F7F3);
        text.setTextAlign(Paint.Align.CENTER);
        text.setColor(0xFF111827);
        text.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
    }

    public void setActive(boolean active) {
        this.active = active;
        if (active) started = System.currentTimeMillis();
        invalidate();
    }

    public void setBpm(int bpm) {
        this.bpm = bpm;
        invalidate();
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float cx = getWidth()/2f, cy = getHeight()/2f;
        float base = Math.min(getWidth(), getHeight()) * 0.31f;
        float pulse = 0f;
        if (active) {
            double phase = (System.currentTimeMillis() - started) / 1000.0 * Math.PI * 2.0 * Math.max(0.8, bpm > 0 ? bpm/60.0 : 1.15);
            pulse = (float)((Math.sin(phase) + 1.0) * 0.5 * dp(8));
            postInvalidateDelayed(16);
        }
        canvas.drawCircle(cx, cy, base + dp(22) + pulse, ring);
        canvas.drawCircle(cx, cy, base + pulse*0.35f, fill);

        text.setTextSize(sp(44));
        canvas.drawText(bpm > 0 ? String.valueOf(bpm) : "♥", cx, cy + dp(5), text);
        text.setTextSize(sp(14));
        text.setColor(0xFF667085);
        canvas.drawText(bpm > 0 ? "BPM" : "손가락을 올려주세요", cx, cy + dp(35), text);
        text.setColor(0xFF111827);
    }

    private float dp(float v) { return v * getResources().getDisplayMetrics().density; }
    private float sp(float v) { return v * getResources().getDisplayMetrics().scaledDensity; }
}
