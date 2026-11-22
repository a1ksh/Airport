package kz.edu.airportcourse.ui;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.LinearInterpolator;

public final class TravelSkyView extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path wings = new Path();
    private final RadialGradient ocean = new RadialGradient(47, 39, 53, 0xff76d5ff, 0xff17568a, Shader.TileMode.CLAMP);
    private final ValueAnimator orbit = ValueAnimator.ofFloat(0, 1);
    private float phase = 0.16f;

    public TravelSkyView(Context context, AttributeSet attrs) {
        super(context, attrs);
        wings.moveTo(11, 0);
        wings.lineTo(8, -2);
        wings.lineTo(2, -2);
        wings.lineTo(-3, -10);
        wings.lineTo(-6, -10);
        wings.lineTo(-4, -2);
        wings.lineTo(-8, -2);
        wings.lineTo(-11, -5);
        wings.lineTo(-13, -5);
        wings.lineTo(-11, 0);
        wings.lineTo(-13, 5);
        wings.lineTo(-11, 5);
        wings.lineTo(-8, 2);
        wings.lineTo(-4, 2);
        wings.lineTo(-6, 10);
        wings.lineTo(-3, 10);
        wings.lineTo(2, 2);
        wings.lineTo(8, 2);
        wings.close();
        orbit.setDuration(9000);
        orbit.setRepeatCount(ValueAnimator.INFINITE);
        orbit.setInterpolator(new LinearInterpolator());
        orbit.addUpdateListener(value -> {
            phase = (float) value.getAnimatedValue();
            invalidate();
        });
    }

    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        updateAnimation();
    }

    @Override public void onVisibilityAggregated(boolean visible) {
        super.onVisibilityAggregated(visible);
        updateAnimation();
    }

    @Override protected void onDetachedFromWindow() {
        orbit.cancel();
        super.onDetachedFromWindow();
    }

    private void updateAnimation() {
        boolean active = isAttachedToWindow() && isShown() && getWindowVisibility() == VISIBLE && ValueAnimator.areAnimatorsEnabled();
        if (active && !orbit.isStarted()) orbit.start();
        if (!active) orbit.cancel();
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float size = Math.min(getWidth(), getHeight());
        canvas.save();
        canvas.translate((getWidth() - size) / 2, (getHeight() - size) / 2);
        canvas.scale(size / 120, size / 120);
        paint.setColor(0xff67c8fa);
        paint.setShader(ocean);
        canvas.drawCircle(60, 60, 35, paint);
        paint.setShader(null);
        paint.setColor(0x64ddf5ff);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(0.8f);
        canvas.drawOval(44, 25, 76, 95, paint);
        canvas.drawOval(27, 47, 93, 73, paint);
        canvas.drawLine(25, 60, 95, 60, paint);
        canvas.drawCircle(60, 60, 35, paint);
        paint.setStyle(Paint.Style.FILL);

        canvas.rotate(-27, 60, 60);
        paint.setColor(0xafaeeaff);
        for (int i = 0; i < 24; i++) {
            double a = i * 2 * Math.PI / 24;
            canvas.drawCircle(60 + 53 * (float) Math.cos(a), 60 + 22 * (float) Math.sin(a), 1.15f, paint);
        }
        double a = 2 * Math.PI * phase;
        canvas.translate(60 + 53 * (float) Math.cos(a), 60 + 22 * (float) Math.sin(a));
        canvas.rotate((float) Math.toDegrees(Math.atan2(22 * Math.cos(a), -53 * Math.sin(a))));
        paint.setColor(Color.WHITE);
        canvas.drawPath(wings, paint);
        canvas.restore();
    }
}
