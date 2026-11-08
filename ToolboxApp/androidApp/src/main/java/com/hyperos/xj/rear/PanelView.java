package com.hyperos.xj.rear;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Movie;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.view.Choreographer;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.View;
import java.io.File;
import java.io.FileInputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 背屏面板：自定义背景之上，绿色液体按电量高度从底部填充，液面为持续流动的 sin 波浪并随手机倾斜而倾斜，
 * 液体内气泡上升；中央为闪电 + 大号电量百分比（淡入 + 缩放入场），底部一行实时数据。上滑退出保留。
 *
 * 动画由 Choreographer 自驱动（外部每 2s 调 update 只刷新数值），故波形/气泡/入场不依赖外部刷新频率。
 */
public class PanelView extends View implements SensorEventListener {

    private static final int LIQUID_COLOR = 0xFF00E030;   // 视频取样的荧光绿
    private static final int BOLT_COLOR = 0xFFFFC933;
    private static final float WAVE_AMP = 8f;             // 波浪振幅（976 设计基准）
    private static final float TILT_GAIN = 6f;            // 倾斜对液面的增益（设计基准）
    private static final float WAVE_SPEED = (float) (Math.PI * 1.5); // rad/s，约 0.67s 一个周期
    private static final float BUBBLE_RISE = 0.12f;       // 气泡上升速度（液体高度比例 / 秒）
    private static final int BUBBLE_COUNT = 6;
    private static final int[] BUBBLE_RADIUS = {6, 4, 3, 4, 5, 3};  // 设计基准半径
    private static final float[] BUBBLE_X = {0.20f, 0.40f, 0.60f, 0.80f, 0.30f, 0.70f};
    private static final long ENTRANCE_MS = 480L;
    // GIF 帧缓冲最长边上限，避免超大 GIF 占满内存。
    private static final float MAX_MOVIE_SIDE = 1600f;
    // 闪电多边形：归一化坐标，按目标矩形缩放。
    private static final float[] BOLT_NX = {0.55f, 0.12f, 0.42f, 0.30f, 0.88f, 0.55f};
    private static final float[] BOLT_NY = {0.00f, 0.55f, 0.55f, 1.00f, 0.42f, 0.42f};

    private final Paint bgPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Paint liquidPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint crestPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bubblePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bubbleShinePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bottomShadowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint boltPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private final Path liquidPath = new Path();
    private final Path crestPath = new Path();
    private final Path boltPath = new Path();
    private final RectF bgRect = new RectF();
    private final float[] wave = new float[240];
    private final float[] bubblePos = new float[BUBBLE_COUNT];
    private final GestureDetector gesture;

    private final Typeface bold = Typeface.create("sans-serif", Typeface.BOLD);
    private final Typeface medium = Typeface.create("sans-serif-medium", Typeface.NORMAL);

    private SensorManager sensorManager;
    private Sensor accelerometer;
    private Choreographer.FrameCallback frameCallback;
    private long waveStartNanos = 0L;
    private long lastFrameNanos = 0L;
    private long entranceStartNanos = 0L;
    private int frameSkip = 0;
    private float waveOffset = 0f;
    private float tiltX = 0f;
    private float tiltY = 0f;
    private float entranceAlpha = 1f;
    private float entranceScale = 1f;
    private int lastWaveWidth = -1;
    private float lastWaveOffset = Float.NaN;
    private int lastShadowHeight = -1;

    private ChargeState state = new ChargeState();
    private float fontScale = 1f;
    private int accent = 0xFF38BDF8;
    private String[] fields = {"temp", "soc", "volt", "cur"};
    private int bgColor = Color.BLACK;
    private Bitmap bgBitmap;
    private Movie bgMovie;
    private Bitmap movieFrame;
    private Canvas movieCanvas;
    private float movieShrink = 1f;
    private long movieStartNanos = 0L;
    private long movieElapsedMs = 0L;
    private String bgLoadedPath = null;
    private long bgLoadedStamp = 0L;
    private Runnable onSwipeUp;

    public PanelView(Context context) {
        super(context);
        setWillNotDraw(false);
        setBackgroundColor(Color.BLACK);
        setLayerType(View.LAYER_TYPE_HARDWARE, null);

        liquidPaint.setStyle(Paint.Style.FILL);
        liquidPaint.setDither(true);
        liquidPaint.setColor(LIQUID_COLOR);
        liquidPaint.setAlpha(0xC0);   // 半透明，让用户自定义背景透出

        crestPaint.setStyle(Paint.Style.STROKE);
        crestPaint.setStrokeWidth(2f);
        crestPaint.setStrokeCap(Paint.Cap.ROUND);
        crestPaint.setStrokeJoin(Paint.Join.ROUND);

        bubblePaint.setStyle(Paint.Style.FILL);
        bubblePaint.setColor(0x66EFFFFF);
        bubbleShinePaint.setStyle(Paint.Style.FILL);
        bubbleShinePaint.setColor(0x9EFFFFFF);

        bottomShadowPaint.setStyle(Paint.Style.FILL);

        textPaint.setStyle(Paint.Style.FILL);
        textPaint.setTextAlign(Paint.Align.CENTER);
        textPaint.setTypeface(bold);

        boltPaint.setStyle(Paint.Style.FILL);
        boltPaint.setColor(BOLT_COLOR);

        for (int i = 0; i < BUBBLE_COUNT; i++) {
            bubblePos[i] = (float) Math.random();
        }

        gesture = new GestureDetector(context, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onScroll(MotionEvent e1, MotionEvent e2, float dx, float dy) {
                if (onSwipeUp == null || e1 == null || e2 == null) return false;
                // 以按下点为基准累计：竖直上滑超阈值且明显大于横向位移才算退出手势。
                float up = e1.getY() - e2.getY();
                float side = Math.abs(e1.getX() - e2.getX());
                float minUp = Math.max(80f, getHeight() * 0.12f);
                if (up > minUp && up > side * 1.5f) {
                    onSwipeUp.run();
                    return true;
                }
                return false;
            }
        });

        try {
            sensorManager = (SensorManager) context.getSystemService(Context.SENSOR_SERVICE);
            if (sensorManager != null) {
                accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
            }
        } catch (Throwable ignored) {
        }
    }

    public void setOnSwipeUpListener(Runnable listener) {
        onSwipeUp = listener;
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        gesture.onTouchEvent(event);
        return true;
    }

    public void update(ChargeState next, RearConfig cfg) {
        state = next;
        fontScale = Math.max(0.8f, Math.min(1.4f, cfg.fontScale));
        accent = parseColor(cfg.accent, 0xFF38BDF8);
        bgColor = parseColor(cfg.bgColor, Color.BLACK);
        setBackgroundColor(bgColor);
        fields = cfg.fields.split(",");
        loadBackground(cfg.bgImagePath);
        invalidate();
    }

    private static int parseColor(String value, int fallback) {
        try {
            return Color.parseColor(value);
        } catch (Throwable t) {
            return fallback;
        }
    }

    private void loadBackground(String path) {
        String target = path == null ? "" : path.trim();
        File file = target.isEmpty() ? null : new File(target);
        long stamp = file != null ? file.lastModified() : 0L;
        if (target.equals(bgLoadedPath) && stamp == bgLoadedStamp) return;
        releaseBackground();
        bgLoadedPath = target;
        bgLoadedStamp = stamp;
        if (file == null) return;
        if (isGifFile(target)) {
            // Movie 在硬件加速层上可能不渲染：逐帧写入软件 Bitmap 走位图通路，其余绘制仍留在硬件层。
            try {
                bgMovie = Movie.decodeFile(target);
            } catch (Throwable t) {
                bgMovie = null;
            }
            if (bgMovie != null) {
                try {
                    float shrink = Math.min(1f, MAX_MOVIE_SIDE / Math.max(bgMovie.width(), bgMovie.height()));
                    int bw = Math.max(1, Math.round(bgMovie.width() * shrink));
                    int bh = Math.max(1, Math.round(bgMovie.height() * shrink));
                    movieFrame = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888);
                    movieCanvas = new Canvas(movieFrame);
                    movieShrink = shrink;
                } catch (Throwable t) {
                    bgMovie = null;
                }
            }
        } else {
            try {
                bgBitmap = BitmapFactory.decodeFile(target);
            } catch (Throwable t) {
                bgBitmap = null;
            }
        }
    }

    private static boolean isGifFile(String path) {
        FileInputStream in = null;
        try {
            in = new FileInputStream(path);
            byte[] head = new byte[6];
            int read = 0;
            while (read < head.length) {
                int n = in.read(head, read, head.length - read);
                if (n < 0) break;
                read += n;
            }
            return read == head.length
                    && head[0] == 'G' && head[1] == 'I' && head[2] == 'F' && head[3] == '8'
                    && (head[4] == '7' || head[4] == '9') && head[5] == 'a';
        } catch (Throwable t) {
            return false;
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    private void releaseBackground() {
        if (bgBitmap != null) {
            bgBitmap.recycle();
            bgBitmap = null;
        }
        bgMovie = null;
        movieCanvas = null;
        if (movieFrame != null) {
            movieFrame.recycle();
            movieFrame = null;
        }
        movieShrink = 1f;
        movieStartNanos = 0L;
        movieElapsedMs = 0L;
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (sensorManager != null && accelerometer != null) {
            try {
                sensorManager.registerListener(this, accelerometer, SensorManager.SENSOR_DELAY_UI);
            } catch (Throwable ignored) {
            }
        }
        startAnimation();
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        if (sensorManager != null) {
            try {
                sensorManager.unregisterListener(this);
            } catch (Throwable ignored) {
            }
        }
        stopAnimation();
    }

    private void startAnimation() {
        if (frameCallback != null) return;
        waveStartNanos = 0L;
        lastFrameNanos = 0L;
        entranceStartNanos = 0L;
        entranceAlpha = 0f;
        entranceScale = 0.85f;
        frameCallback = new Choreographer.FrameCallback() {
            @Override
            public void doFrame(long frameTimeNanos) {
                long elapsed = lastFrameNanos == 0L ? 0L : frameTimeNanos - lastFrameNanos;
                lastFrameNanos = frameTimeNanos;
                if (waveStartNanos == 0L) waveStartNanos = frameTimeNanos;
                waveOffset = (frameTimeNanos - waveStartNanos) / 1_000_000_000f * WAVE_SPEED;

                float dt = Math.min(0.1f, elapsed / 1_000_000_000f);
                for (int i = 0; i < BUBBLE_COUNT; i++) {
                    bubblePos[i] -= BUBBLE_RISE * dt;
                    if (bubblePos[i] < 0f) bubblePos[i] = 1f;
                }

                if (entranceStartNanos == 0L) entranceStartNanos = frameTimeNanos;
                float p = (frameTimeNanos - entranceStartNanos) / (ENTRANCE_MS * 1_000_000f);
                if (p > 1f) p = 1f;
                entranceAlpha = p;
                entranceScale = 0.85f + 0.15f * p;

                if (bgMovie != null) {
                    if (movieStartNanos == 0L) movieStartNanos = frameTimeNanos;
                    movieElapsedMs = (frameTimeNanos - movieStartNanos) / 1_000_000L;
                }

                // 波浪很慢，隔帧重绘（约 30fps）即可保持顺滑，同时降低背屏常亮的功耗。
                if ((frameSkip++ & 1) == 0) postInvalidateOnAnimation();

                if (frameCallback != null) {
                    Choreographer.getInstance().postFrameCallback(this);
                }
            }
        };
        Choreographer.getInstance().postFrameCallback(frameCallback);
    }

    private void stopAnimation() {
        if (frameCallback != null) {
            Choreographer.getInstance().removeFrameCallback(frameCallback);
            frameCallback = null;
        }
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        if (event.sensor == null || event.sensor.getType() != Sensor.TYPE_ACCELEROMETER) return;
        if (event.values == null || event.values.length < 2) return;
        tiltX = tiltX * 0.95f + event.values[0] * 0.05f;
        tiltY = tiltY * 0.95f + event.values[1] * 0.05f;
        tiltX = Math.max(-2f, Math.min(2f, tiltX));
        tiltY = Math.max(-2f, Math.min(2f, tiltY));
        // 重绘统一交给 Choreographer，避免传感器高频回调叠加过度绘制。
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int width = getWidth();
        int height = getHeight();
        if (width <= 0 || height <= 0) return;
        float scale = width / 976f;

        canvas.drawColor(bgColor);
        Bitmap frame = movieFrame;
        if (bgMovie != null && frame != null && !frame.isRecycled()) {
            int duration = bgMovie.duration();
            if (duration <= 0) duration = 1000;
            bgMovie.setTime((int) (movieElapsedMs % duration));
            frame.eraseColor(Color.TRANSPARENT);
            movieCanvas.save();
            if (movieShrink < 1f) movieCanvas.scale(movieShrink, movieShrink);
            bgMovie.draw(movieCanvas, 0f, 0f, bgPaint);
            movieCanvas.restore();
            float bw = frame.getWidth();
            float bh = frame.getHeight();
            float fit = Math.max(width / bw, height / bh);
            float dw = bw * fit;
            float dh = bh * fit;
            bgRect.set((width - dw) / 2f, (height - dh) / 2f,
                    (width + dw) / 2f, (height + dh) / 2f);
            canvas.drawBitmap(frame, null, bgRect, bgPaint);
        } else if (bgBitmap != null && !bgBitmap.isRecycled()) {
            float bw = bgBitmap.getWidth();
            float bh = bgBitmap.getHeight();
            float fit = Math.max(width / bw, height / bh);
            float dw = bw * fit;
            float dh = bh * fit;
            bgRect.set((width - dw) / 2f, (height - dh) / 2f,
                    (width + dw) / 2f, (height + dh) / 2f);
            canvas.drawBitmap(bgBitmap, null, bgRect, bgPaint);
        }

        float level = Math.max(0, Math.min(100, state.level)) / 100f;
        float fillHeight = height * level;
        float waveY = height - fillHeight;
        float amp = WAVE_AMP * scale;
        float leftTilt = tiltX * TILT_GAIN * scale;
        float rightTilt = -tiltX * TILT_GAIN * scale;

        if (fillHeight > 0.5f) {
            updateWave(width, amp);
            buildLiquidPath(width, height, waveY, leftTilt, rightTilt);
            canvas.drawPath(liquidPath, liquidPaint);

            if (lastShadowHeight != height) {
                bottomShadowPaint.setShader(new LinearGradient(
                        0f, height - 48f * scale, 0f, height,
                        new int[]{0x00000000, 0x33000000, 0x59000000},
                        new float[]{0f, 0.6f, 1f}, Shader.TileMode.CLAMP));
                lastShadowHeight = height;
            }
            canvas.save();
            canvas.clipPath(liquidPath);
            canvas.drawRect(0f, height - 48f * scale, width, height, bottomShadowPaint);
            canvas.restore();

            buildCrestPath(width, waveY, leftTilt, rightTilt);
            float crestWidth = Math.max(3f, width * 0.005f);
            crestPaint.setColor(0x59FFFFFF);
            crestPaint.setStrokeWidth(crestWidth * 2.6f);
            canvas.drawPath(crestPath, crestPaint);
            crestPaint.setColor(Color.WHITE);
            crestPaint.setStrokeWidth(crestWidth);
            canvas.drawPath(crestPath, crestPaint);

            if (fillHeight > 12f * scale) {
                drawBubbles(canvas, width, waveY, fillHeight, scale);
            }
        }

        float contentLeft = 296f * scale;
        float contentWidth = 680f * scale;
        float centerX = contentLeft + contentWidth / 2f;
        float centerY = height / 2f;

        drawCenter(canvas, centerX, centerY, scale);
        drawDataRow(canvas, centerX, height, contentWidth, scale);
    }

    private void updateWave(int width, float amp) {
        if (lastWaveWidth == width && lastWaveOffset == waveOffset) return;
        int count = pointCount(width);
        for (int i = 0; i < count; i++) {
            float x = (float) i / (count - 1) * width;
            wave[i] = (float) Math.sin(x / width * 4 * Math.PI + waveOffset) * amp;
        }
        lastWaveWidth = width;
        lastWaveOffset = waveOffset;
    }

    private static int pointCount(int width) {
        return Math.max(2, Math.min(width / 6, 240));
    }

    private void buildLiquidPath(int width, int height, float waveY, float leftTilt, float rightTilt) {
        int count = pointCount(width);
        liquidPath.reset();
        liquidPath.moveTo(0f, waveY + wave[0] + leftTilt);
        for (int i = 1; i < count; i++) {
            float x = (float) i / (count - 1) * width;
            float tilt = leftTilt + (rightTilt - leftTilt) * (x / width);
            liquidPath.lineTo(x, waveY + wave[i] + tilt);
        }
        liquidPath.lineTo(width, height);
        liquidPath.lineTo(0f, height);
        liquidPath.close();
    }

    private void buildCrestPath(int width, float waveY, float leftTilt, float rightTilt) {
        int count = pointCount(width);
        crestPath.reset();
        crestPath.moveTo(0f, waveY + wave[0] + leftTilt);
        for (int i = 1; i < count; i++) {
            float x = (float) i / (count - 1) * width;
            float tilt = leftTilt + (rightTilt - leftTilt) * (x / width);
            crestPath.lineTo(x, waveY + wave[i] + tilt);
        }
    }

    private void drawBubbles(Canvas canvas, int width, float waveY, float fillHeight, float scale) {
        float driftX = -tiltX * 5f * scale;
        float driftY = tiltY * 2f * scale;
        for (int i = 0; i < BUBBLE_COUNT; i++) {
            float r = BUBBLE_RADIUS[i] * scale;
            float cx = BUBBLE_X[i] * width + driftX * (0.5f + 0.25f * (i % 3));
            float cy = waveY + fillHeight * bubblePos[i] + driftY;
            canvas.drawCircle(cx, cy, r, bubblePaint);
            canvas.drawCircle(cx - r * 0.3f, cy - r * 0.3f, r * 0.36f, bubbleShinePaint);
        }
    }

    private void drawCenter(Canvas canvas, float centerX, float centerY, float scale) {
        float size = 96f * scale * fontScale;
        textPaint.setTypeface(bold);
        textPaint.setTextSize(size);
        textPaint.setColor(Color.WHITE);
        textPaint.setShadowLayer(14f * scale, 0f, 3f * scale, 0xCC000000);
        String pct = state.level + "%";
        float pctWidth = textPaint.measureText(pct);

        boolean bolt = state.charging;
        float boltW = bolt ? size * 0.60f : 0f;
        float boltH = size * 1.02f;
        float gap = bolt ? size * 0.16f : 0f;
        float groupWidth = boltW + gap + pctWidth;
        float left = centerX - groupWidth / 2f;

        int alpha = Math.max(0, Math.min(255, (int) (255 * entranceAlpha)));
        canvas.save();
        canvas.scale(entranceScale, entranceScale, centerX, centerY);

        if (bolt) {
            buildBoltPath(left, centerY - boltH / 2f, boltW, boltH);
            boltPaint.setAlpha(alpha);
            canvas.drawPath(boltPath, boltPaint);
        }

        textPaint.setAlpha(alpha);
        canvas.drawText(pct, left + boltW + gap + pctWidth / 2f, centerY + size * 0.34f, textPaint);

        canvas.restore();
        textPaint.setShadowLayer(0f, 0f, 0f, 0);
    }

    private void buildBoltPath(float x, float y, float w, float h) {
        boltPath.reset();
        for (int i = 0; i < BOLT_NX.length; i++) {
            float px = x + BOLT_NX[i] * w;
            float py = y + BOLT_NY[i] * h;
            if (i == 0) boltPath.moveTo(px, py);
            else boltPath.lineTo(px, py);
        }
        boltPath.close();
    }

    private void drawDataRow(Canvas canvas, float centerX, int height, float contentWidth, float scale) {
        List<String> values = buildDataValues();
        if (values.isEmpty()) return;
        textPaint.setTypeface(medium);
        float size = 24f * scale * fontScale;
        float gap = size * 0.55f;   // 竖线两侧间距
        float total = measureRow(values, size, gap);
        float maxWidth = contentWidth * 0.94f;
        if (total > maxWidth) {
            size *= maxWidth / total;
            gap = size * 0.55f;
            total = measureRow(values, size, gap);
        }
        float y = height - 58f * scale;
        float cursor = centerX - total / 2f;
        textPaint.setTextSize(size);
        textPaint.setShadowLayer(6f * scale, 0f, 2f * scale, 0x99000000);
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) {
                textPaint.setColor(0xA6FFFFFF);
                float sw = textPaint.measureText("|");
                canvas.drawText("|", cursor + gap + sw / 2f, y, textPaint);
                cursor += gap + sw + gap;
            }
            textPaint.setColor(0xF0FFFFFF);
            float valueWidth = textPaint.measureText(values.get(i));
            canvas.drawText(values.get(i), cursor + valueWidth / 2f, y, textPaint);
            cursor += valueWidth;
        }
        textPaint.setShadowLayer(0f, 0f, 0f, 0);
    }

    private float measureRow(List<String> values, float size, float gap) {
        textPaint.setTextSize(size);
        float total = 0f;
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) total += gap + textPaint.measureText("|") + gap;
            total += textPaint.measureText(values.get(i));
        }
        return total;
    }

    /** 取 fields 前 4 项的值（含单位）。 */
    private List<String> buildDataValues() {
        List<String> values = new ArrayList<>();
        for (String id : fields) {
            if (values.size() >= 4) break;
            String value = fieldValue(id.trim());
            if (value != null) values.add(value);
        }
        return values;
    }

    private String fieldValue(String id) {
        switch (id) {
            case "temp":
                return String.format(Locale.US, "%.1f℃", state.tempC);
            case "soc":
                return state.level + "%";
            case "volt":
                return String.format(Locale.US, "%.2fV", state.voltageV);
            case "cur":
                return String.format(Locale.US, "%.2fA", state.currentA);
            case "power":
                return String.format(Locale.US, "%.1fW", state.powerW);
            case "status":
                return state.statusText;
            case "design":
                return state.designMah > 0 ? state.designMah + "mAh" : "--";
            case "full":
                return state.fullMah > 0 ? state.fullMah + "mAh" : "--";
            case "health":
                return state.health > 0 ? state.health + "%" : "--";
            case "cycle":
                return state.cycles > 0 ? state.cycles + "次" : "--";
            case "fake":
                return state.fakeCycles >= 0 ? state.fakeCycles + "次" : "--";
            case "proto":
                return state.proto.isEmpty() ? "--" : state.proto;
            case "rm":
                return state.rmMah > 0 ? state.rmMah + "mAh" : "--";
            case "soh":
                return state.soh > 0 ? state.soh + "%" : "--";
            default:
                return null;
        }
    }

}
