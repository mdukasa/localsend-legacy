package io.lslegacy;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.view.View;

/**
 * A glass orb inside a slowly turning ring of glass segments: a skeuomorphic nod to the spinning
 * orb on modern LocalSend's receive screen.
 *
 * Everything with gradients or blur is drawn once into bitmaps when the size is known. Each frame only
 * rotates the ring bitmap and relights it, so it stays smooth on a 600 MHz ARMv6 without GPU drawing.
 * The highlight stays fixed at the top-left while the segments turn underneath it.
 */
public class OrbView extends View {
    private static final int SEGMENTS = 10;
    private static final float SEGMENT_SWEEP = 28f; // degrees of each segment; the rest of 36° is gap
    private static final long TURN_MS = 9000; // one full turn
    private static final long FRAME_MS = 40; // ~25 fps is plenty for a slow turn

    private final Paint bitmapPaint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
    private final Paint rotatePaint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
    private final Paint atopPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Paint shadowPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private Bitmap orb, ring, ringShadow, lighting, frame;
    private Canvas frameCanvas;
    private boolean active = true;
    private boolean running;
    private float angle;
    private long lastTick;

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (!running) return;
            long now = SystemClock.uptimeMillis();
            angle = (angle + 360f * (now - lastTick) / TURN_MS) % 360f;
            lastTick = now;
            invalidate();
            postDelayed(this, FRAME_MS);
        }
    };

    public OrbView(Context context) {
        super(context);
        init();
    }

    public OrbView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    private void init() {
        atopPaint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.SRC_ATOP));
        shadowPaint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.DST_OVER));
        if (android.os.Build.VERSION.SDK_INT >= 11) disableHardware();
    }

    @android.annotation.TargetApi(11)
    private void disableHardware() {
        // BlurMaskFilter needs a software layer; the bitmaps are cheap to draw either way.
        setLayerType(LAYER_TYPE_SOFTWARE, null);
    }

    /** Spinning and full brightness while receiving is possible; still and dimmed otherwise. */
    public void setActive(boolean active) {
        if (this.active == active) return;
        this.active = active;
        updateRunning();
        invalidate();
    }

    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        updateRunning();
    }

    @Override protected void onDetachedFromWindow() {
        stop();
        super.onDetachedFromWindow();
    }

    @Override protected void onWindowVisibilityChanged(int visibility) {
        super.onWindowVisibilityChanged(visibility);
        updateRunning();
    }

    private void updateRunning() {
        boolean shouldRun = active && getWindowVisibility() == VISIBLE && getWindowToken() != null;
        if (shouldRun && !running) {
            running = true;
            lastTick = SystemClock.uptimeMillis();
            post(tick);
        } else if (!shouldRun) {
            stop();
        }
    }

    private void stop() {
        running = false;
        removeCallbacks(tick);
    }

    @Override protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        int s = Math.min(w, h);
        if (s <= 0) return;
        orb = renderOrb(s);
        ring = renderRing(s, false);
        ringShadow = renderRing(s, true);
        lighting = renderLighting(s);
        frame = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888);
        frameCanvas = new Canvas(frame);
    }

    @Override protected void onDraw(Canvas canvas) {
        if (orb == null) return;
        int s = orb.getWidth();
        float left = (getWidth() - s) / 2f, top = (getHeight() - s) / 2f;
        float c = s / 2f;
        bitmapPaint.setAlpha(active ? 255 : 110);

        // Turn the ring, then relight it with the fixed highlight, clipped to the segments.
        frameCanvas.drawColor(0, PorterDuff.Mode.CLEAR);
        frameCanvas.save();
        frameCanvas.rotate(angle, c, c);
        frameCanvas.drawBitmap(ring, 0, 0, rotatePaint);
        frameCanvas.restore();
        frameCanvas.drawBitmap(lighting, 0, 0, atopPaint);
        // The shadow goes underneath the lit ring and always falls straight down.
        frameCanvas.save();
        frameCanvas.translate(0, s * 0.025f);
        frameCanvas.rotate(angle, c, c);
        frameCanvas.drawBitmap(ringShadow, 0, 0, shadowPaint);
        frameCanvas.restore();

        canvas.drawBitmap(frame, left, top, bitmapPaint);
        canvas.drawBitmap(orb, left, top, bitmapPaint);
    }

    // ---- one-off rendering ----

    private static Paint aa() {
        return new Paint(Paint.ANTI_ALIAS_FLAG | Paint.DITHER_FLAG);
    }

    private static Bitmap renderOrb(int s) {
        Bitmap b = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888);
        Canvas cv = new Canvas(b);
        float c = s / 2f, r = s * 0.27f;

        Paint shadow = aa();
        shadow.setColor(0x66000000);
        shadow.setMaskFilter(new BlurMaskFilter(s * 0.04f, BlurMaskFilter.Blur.NORMAL));
        cv.drawCircle(c, c + s * 0.03f, r * 1.02f, shadow);

        Paint rim = aa();
        rim.setColor(0xff0c2e1e);
        cv.drawCircle(c, c, r * 1.04f, rim);

        Paint glass = aa();
        glass.setShader(new RadialGradient(c, c + r * 0.25f, r * 1.25f,
                new int[]{0xff8af0b6, 0xff2bb06f, 0xff156c43, 0xff0a3d25},
                new float[]{0f, 0.45f, 0.85f, 1f}, Shader.TileMode.CLAMP));
        cv.drawCircle(c, c, r, glass);

        Paint glow = aa();
        glow.setShader(new RadialGradient(c, c + r, r * 0.9f, 0xaac9ffe0, 0x00c9ffe0, Shader.TileMode.CLAMP));
        cv.drawCircle(c, c, r, glow);

        // Curved gloss over the top half.
        RectF gloss = new RectF(c - r * 0.82f, c - r * 0.94f, c + r * 0.82f, c + r * 0.12f);
        Paint g = aa();
        g.setShader(new LinearGradient(0, gloss.top, 0, gloss.bottom, 0xe6ffffff, 0x0dffffff, Shader.TileMode.CLAMP));
        cv.drawOval(gloss, g);

        Paint glint = aa();
        glint.setColor(0xe6ffffff);
        cv.save();
        cv.rotate(-30, c - r * 0.45f, c - r * 0.68f);
        cv.drawOval(new RectF(c - r * 0.58f, c - r * 0.74f, c - r * 0.32f, c - r * 0.62f), glint);
        cv.restore();
        return b;
    }

    /** The ring of segments with rotation-invariant (radial) shading, or its blurred shadow. */
    private static Bitmap renderRing(int s, boolean shadowOnly) {
        Bitmap b = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888);
        Canvas cv = new Canvas(b);
        float c = s / 2f;
        float inner = s * 0.37f, outer = s * 0.47f;
        float mid = (inner + outer) / 2f, width = outer - inner;
        RectF oval = new RectF(c - mid, c - mid, c + mid, c + mid);

        Paint p = aa();
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(width);
        p.setStrokeCap(Paint.Cap.ROUND);
        if (shadowOnly) {
            p.setColor(0x80000000);
            p.setMaskFilter(new BlurMaskFilter(s * 0.025f, BlurMaskFilter.Blur.NORMAL));
        } else {
            // A tube seen from above: dark at both edges, bright just outside the middle.
            float R = outer;
            p.setShader(new RadialGradient(c, c, R,
                    new int[]{0xff0b4a2e, 0xff1f9a5d, 0xff7de8ae, 0xff2bb06f, 0xff0e5a37},
                    new float[]{inner / R, (inner + width * 0.3f) / R, (inner + width * 0.62f) / R,
                            (inner + width * 0.85f) / R, 1f},
                    Shader.TileMode.CLAMP));
        }
        float roundCapDegrees = (float) Math.toDegrees(width / 2f / mid);
        float sweep = SEGMENT_SWEEP - 2 * roundCapDegrees;
        for (int i = 0; i < SEGMENTS; i++) {
            float start = i * 360f / SEGMENTS + roundCapDegrees;
            cv.drawArc(oval, start, sweep, false, p);
        }
        if (!shadowOnly) {
            // A thin dark outline behind each segment so the glass edges read against any background.
            Paint edge = aa();
            edge.setStyle(Paint.Style.STROKE);
            edge.setStrokeCap(Paint.Cap.ROUND);
            edge.setStrokeWidth(width + s * 0.012f);
            edge.setColor(0x55000000);
            edge.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.DST_OVER));
            for (int i = 0; i < SEGMENTS; i++) {
                float start = i * 360f / SEGMENTS + roundCapDegrees;
                cv.drawArc(oval, start, sweep, false, edge);
            }
        }
        return b;
    }

    /** Fixed top-left light and bottom-right shade, applied only where the ring is. */
    private static Bitmap renderLighting(int s) {
        Bitmap b = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888);
        Canvas cv = new Canvas(b);
        Paint p = aa();
        p.setShader(new LinearGradient(s * 0.15f, s * 0.1f, s * 0.85f, s * 0.95f,
                new int[]{0x99ffffff, 0x14ffffff, 0x00000000, 0x55000000},
                new float[]{0f, 0.35f, 0.6f, 1f}, Shader.TileMode.CLAMP));
        cv.drawRect(0, 0, s, s, p);
        // A sharper specular band along the upper-left arc.
        Paint spec = aa();
        spec.setStyle(Paint.Style.STROKE);
        float c = s / 2f, r = s * 0.445f;
        spec.setStrokeWidth(s * 0.018f);
        spec.setColor(Color.argb(170, 255, 255, 255));
        spec.setStrokeCap(Paint.Cap.ROUND);
        cv.drawArc(new RectF(c - r, c - r, c + r, c + r), 200, 55, false, spec);
        return b;
    }
}
