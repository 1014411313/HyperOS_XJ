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
import java.util.Random;

/**
 * 背屏面板：自定义背景之上叠加高透明水体——背景经逐行水平位移（drawBitmapMesh）模拟折射，
 * 水体本身只叠极淡的中性吸收色（垂直渐变，alpha 0.04→0.30，几乎不改变背景色相），水面为断续高光细线，
 * 水下浅层叠微弱焦散（不规则极短亮弧随波缓慢漂移），水下为不填充的上升气泡（只画边缘折射弧 + 左上高光点）；
 * 中央为闪电 + 大号电量百分比，
 * 底部一行实时数据。上滑退出保留。
 *
 * 动画由 Choreographer 自驱动（外部每 2s 调 update 只刷新数值），故波形/气泡/入场不依赖外部刷新频率。
 *
 * 视觉参数与 App 预览 RearScreen.kt / LiquidStyle 同源，两侧必须同步修改。
 */
public class PanelView extends View implements SensorEventListener {

    // —— 折射网格与强度（与 LiquidStyle 同源）——
    private static final int MESH_COLS = 12;                 // 网格列数
    private static final int MESH_ROWS = 40;                 // 网格行数
    private static final float REFRACT_AMP = 24f;            // 水平位移幅度（真机反馈 36 仍过强，再降 1/3）
    private static final float REFRACT_KY = 0.026f;          // 相位随 y 的变化率（rad / 设计 px）
    // —— 水体吸收色（中性海蓝灰 0x14202A：RGB 比例约 1:1.6:2.1，远低于原深绿 1:7.7:3.4，几乎不改变背景色相）——
    private static final int ABSORB_TOP_COLOR = 0x0A14202A;    // alpha 0.04
    private static final int ABSORB_BOTTOM_COLOR = 0x4D14202A; // alpha 0.30
    // —— 水面断续高光 ——
    private static final int HIGHLIGHT_COUNT = 56;
    private static final float HIGHLIGHT_MIN_LEN = 8f;       // 设计基准 px
    private static final float HIGHLIGHT_MAX_LEN = 34f;
    private static final float HIGHLIGHT_MIN_ALPHA = 0.45f;
    private static final float HIGHLIGHT_MAX_ALPHA = 1.0f;
    private static final float HIGHLIGHT_SKIP = 0.25f;       // 不绘制的段比例
    private static final float HIGHLIGHT_JITTER = 1.5f;      // y 抖动 ±（设计基准 px）
    // —— 微弱焦散：水下浅层的不规则极短亮弧（固定 seed 布局，随波缓慢漂移）——
    private static final int CAUSTIC_COUNT = 40;
    private static final float CAUSTIC_BAND = 0.12f;         // 焦散带厚度 / 屏幕高（与液高取小）
    private static final float CAUSTIC_MIN_LEN = 5f;         // 设计基准 px
    private static final float CAUSTIC_MAX_LEN = 18f;
    private static final float CAUSTIC_MIN_ALPHA = 0.05f;
    private static final float CAUSTIC_MAX_ALPHA = 0.18f;
    private static final float CAUSTIC_MIN_WIDTH = 1.0f;     // 设计基准 px
    private static final float CAUSTIC_MAX_WIDTH = 2.0f;
    private static final float CAUSTIC_DRIFT_MAX = 10f;      // 漂移振幅（设计基准 px）
    private static final float CAUSTIC_DRIFT_LOOP_MS = 9333f; // 漂移相位循环周期（预览同值）
    // —— 水面高对比边缘段：极短、几乎不透明的强亮线 ——
    private static final int EDGE_COUNT = 8;
    private static final float EDGE_MIN_LEN = 4f;            // 设计基准 px
    private static final float EDGE_MAX_LEN = 10f;
    private static final float EDGE_MIN_ALPHA = 0.95f;
    private static final float EDGE_MAX_ALPHA = 1.0f;
    // —— 气泡（不填充：边缘弧 + 左上高光点）——
    private static final float BUBBLE_RING_ALPHA = 0.55f;
    private static final float BUBBLE_RING_WIDTH = 2.2f;     // 设计基准 px
    private static final float BUBBLE_SHINE_ALPHA = 0.7f;
    private static final float BUBBLE_SHINE_R = 0.28f;       // 高光点半径 / 气泡半径

    private static final int BOLT_COLOR = 0xFFFFC933;
    private static final float WAVE_AMP = 8f;                // 液面波浪振幅（976 设计基准）
    private static final float TILT_GAIN = 6f;               // 倾斜对液面的增益（设计基准）
    private static final float WAVE_SPEED = (float) (Math.PI * 1.5); // rad/s，约 0.67s 一个周期
    private static final float BUBBLE_RISE = 0.12f;          // 气泡上升速度（液体高度比例 / 秒）
    private static final int BUBBLE_COUNT = 6;
    private static final int[] BUBBLE_RADIUS = {13, 9, 7, 9, 11, 7};  // 设计基准半径
    private static final float[] BUBBLE_X = {0.20f, 0.40f, 0.60f, 0.80f, 0.30f, 0.70f};
    // GIF 帧缓冲最长边上限，避免超大 GIF 占满内存。
    private static final float MAX_MOVIE_SIDE = 1600f;
    // 闪电多边形：归一化坐标，按目标矩形缩放。
    private static final float[] BOLT_NX = {0.55f, 0.12f, 0.42f, 0.30f, 0.88f, 0.55f};
    private static final float[] BOLT_NY = {0.00f, 0.55f, 0.55f, 1.00f, 0.42f, 0.42f};

    private final Paint bgPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Paint liquidPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint crestPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint causticPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bubblePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bubbleShinePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bottomShadowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint boltPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private final Path liquidPath = new Path();
    private final Path boltPath = new Path();
    private final Path caPath = new Path();
    private final RectF coverRect = new RectF();
    private final float[] wave = new float[240];
    private final float[] mesh = new float[(MESH_COLS + 1) * (MESH_ROWS + 1) * 2];
    private final float[] hlLen = new float[HIGHLIGHT_COUNT];
    private final float[] hlAlpha = new float[HIGHLIGHT_COUNT];
    private final float[] hlJitter = new float[HIGHLIGHT_COUNT];
    private final boolean[] hlSkip = new boolean[HIGHLIGHT_COUNT];
    private final float[] caLen = new float[CAUSTIC_COUNT];
    private final float[] caAlpha = new float[CAUSTIC_COUNT];
    private final float[] caWidth = new float[CAUSTIC_COUNT];
    private final float[] caBx = new float[CAUSTIC_COUNT];
    private final float[] caBt = new float[CAUSTIC_COUNT];
    private final float[] caBow = new float[CAUSTIC_COUNT];
    private final float[] caSpeedK = new float[CAUSTIC_COUNT];
    private final float[] caPhase = new float[CAUSTIC_COUNT];
    private final float[] egLen = new float[EDGE_COUNT];
    private final float[] egAlpha = new float[EDGE_COUNT];
    private final float[] egBx = new float[EDGE_COUNT];
    private final float[] egJitter = new float[EDGE_COUNT];
    private final float[] bubblePos = new float[BUBBLE_COUNT];
    private final GestureDetector gesture;

    private final Typeface bold = Typeface.create("sans-serif", Typeface.BOLD);
    // 衬线数字比无衬线更有设计感（Noto Serif）；CJK/℃ 缺字形时由系统回落。
    private final Typeface serif = Typeface.create("serif", Typeface.NORMAL);

    private SensorManager sensorManager;
    private Sensor accelerometer;
    private Choreographer.FrameCallback frameCallback;
    private long waveStartNanos = 0L;
    private long lastFrameNanos = 0L;
    private int frameSkip = 0;
    private float waveOffset = 0f;
    private float caustPhaseMs = 0f;
    private float tiltX = 0f;
    private float tiltY = 0f;
    private int lastWaveWidth = -1;
    private float lastWaveOffset = Float.NaN;
    private int lastShadowHeight = -1;
    private int lastGradWaveY = Integer.MIN_VALUE;
    private int lastGradHeight = -1;

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
    // 四边外扩位移余量的背景位图：折射位移最大 ±amp 时网格仍采样在位图内，边缘不露底。
    private Bitmap bgCover;
    private Canvas bgCoverCanvas;
    private final RectF coverDstRect = new RectF();
    private boolean coverDirty = true;
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

        crestPaint.setStyle(Paint.Style.STROKE);
        crestPaint.setStrokeWidth(1.2f);
        crestPaint.setStrokeCap(Paint.Cap.ROUND);
        crestPaint.setStrokeJoin(Paint.Join.ROUND);

        bubblePaint.setStyle(Paint.Style.STROKE);
        bubblePaint.setColor(0x59EFFFFF);
        bubbleShinePaint.setStyle(Paint.Style.FILL);
        bubbleShinePaint.setColor(0xB3FFFFFF);

        causticPaint.setStyle(Paint.Style.STROKE);
        causticPaint.setStrokeCap(Paint.Cap.ROUND);
        causticPaint.setStrokeJoin(Paint.Join.ROUND);

        bottomShadowPaint.setStyle(Paint.Style.FILL);

        textPaint.setStyle(Paint.Style.FILL);
        textPaint.setTextAlign(Paint.Align.CENTER);
        textPaint.setTypeface(bold);

        boltPaint.setStyle(Paint.Style.FILL);
        boltPaint.setColor(BOLT_COLOR);

        // 高光段的长度/透明度/抖动/是否跳过一次生成，固定 seed，避免每帧重算导致闪烁。
        Random rnd = new Random(20261109L);
        for (int i = 0; i < HIGHLIGHT_COUNT; i++) {
            hlLen[i] = HIGHLIGHT_MIN_LEN + rnd.nextFloat() * (HIGHLIGHT_MAX_LEN - HIGHLIGHT_MIN_LEN);
            hlAlpha[i] = HIGHLIGHT_MIN_ALPHA + rnd.nextFloat() * (HIGHLIGHT_MAX_ALPHA - HIGHLIGHT_MIN_ALPHA);
            hlJitter[i] = (rnd.nextFloat() * 2f - 1f) * HIGHLIGHT_JITTER;
            hlSkip[i] = rnd.nextFloat() < HIGHLIGHT_SKIP;
        }

        // 焦散与边缘段同样一次性生成：固定 seed 保证布局稳定，只有相位随时间走。
        Random caRnd = new Random(20261110L);
        for (int i = 0; i < CAUSTIC_COUNT; i++) {
            caLen[i] = CAUSTIC_MIN_LEN + caRnd.nextFloat() * (CAUSTIC_MAX_LEN - CAUSTIC_MIN_LEN);
            caAlpha[i] = CAUSTIC_MIN_ALPHA + caRnd.nextFloat() * (CAUSTIC_MAX_ALPHA - CAUSTIC_MIN_ALPHA);
            caWidth[i] = CAUSTIC_MIN_WIDTH + caRnd.nextFloat() * (CAUSTIC_MAX_WIDTH - CAUSTIC_MIN_WIDTH);
            caBx[i] = caRnd.nextFloat();
            // 深度取 u^1.5：焦散聚焦多发生在紧贴水面的浅层。
            caBt[i] = (float) Math.pow(caRnd.nextFloat(), 1.5);
            caBow[i] = (caRnd.nextFloat() * 2f - 1f) * 0.30f;   // 相对长度的弯曲比例
            caSpeedK[i] = caRnd.nextFloat() < 0.4f ? 2f : 1f;   // 漂移频率取基频整数倍，漂移循环无缝
            caPhase[i] = caRnd.nextFloat() * 2f * (float) Math.PI;
        }
        Random egRnd = new Random(20261111L);
        for (int i = 0; i < EDGE_COUNT; i++) {
            egBx[i] = 0.03f + egRnd.nextFloat() * 0.94f;
            egLen[i] = EDGE_MIN_LEN + egRnd.nextFloat() * (EDGE_MAX_LEN - EDGE_MIN_LEN);
            egAlpha[i] = EDGE_MIN_ALPHA + egRnd.nextFloat() * (EDGE_MAX_ALPHA - EDGE_MIN_ALPHA);
            egJitter[i] = (egRnd.nextFloat() * 2f - 1f) * HIGHLIGHT_JITTER;
        }

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
        coverDirty = true;
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
        if (bgCover != null) {
            bgCover.recycle();
            bgCover = null;
        }
        bgCoverCanvas = null;
        coverRect.setEmpty();
        coverDirty = true;
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
        frameCallback = new Choreographer.FrameCallback() {
            @Override
            public void doFrame(long frameTimeNanos) {
                long elapsed = lastFrameNanos == 0L ? 0L : frameTimeNanos - lastFrameNanos;
                lastFrameNanos = frameTimeNanos;
                if (waveStartNanos == 0L) waveStartNanos = frameTimeNanos;
                waveOffset = (frameTimeNanos - waveStartNanos) / 1_000_000_000f * WAVE_SPEED;
                // 焦散漂移用独立的慢相位（频率慢于波形），取模循环避免长时间运行累加漂移。
                caustPhaseMs = (frameTimeNanos - waveStartNanos) / 1_000_000f % CAUSTIC_DRIFT_LOOP_MS;

                float dt = Math.min(0.1f, elapsed / 1_000_000_000f);
                for (int i = 0; i < BUBBLE_COUNT; i++) {
                    bubblePos[i] -= BUBBLE_RISE * dt;
                    if (bubblePos[i] < 0f) bubblePos[i] = 1f;
                }

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

        float level = Math.max(0, Math.min(100, state.level)) / 100f;
        float fillHeight = height * level;
        float waveY = height - fillHeight;
        float amp = WAVE_AMP * scale;
        float leftTilt = tiltX * TILT_GAIN * scale;
        float rightTilt = -tiltX * TILT_GAIN * scale;

        // 背景：逐行水平位移（折射）。水面（waveY）以上偏移为 0，故此处整幅网格即可。
        boolean hasBg = (bgMovie != null && movieFrame != null && !movieFrame.isRecycled())
                || (bgBitmap != null && !bgBitmap.isRecycled());
        if (hasBg) {
            if (prepareCover(width, height)) {
                updateMesh(width, height, waveY);
                canvas.drawBitmapMesh(bgCover, MESH_COLS, MESH_ROWS, mesh, 0, null, 0, bgPaint);
            }
        }

        if (fillHeight > 0.5f) {
            updateWave(width, amp);
            buildLiquidPath(width, height, waveY, leftTilt, rightTilt);

            // 水体吸收色：只在液体区域内叠极淡的垂直渐变，最深处 alpha ≤ 0.30，保持背景透出。
            ensureAbsorbGradient(waveY, height);
            canvas.save();
            canvas.clipPath(liquidPath);
            canvas.drawRect(0f, 0f, width, height, liquidPaint);

            if (lastShadowHeight != height) {
                bottomShadowPaint.setShader(new LinearGradient(
                        0f, height - 48f * scale, 0f, height,
                        new int[]{0x00000000, 0x33000000, 0x59000000},
                        new float[]{0f, 0.6f, 1f}, Shader.TileMode.CLAMP));
                lastShadowHeight = height;
            }
            canvas.drawRect(0f, height - 48f * scale, width, height, bottomShadowPaint);
            drawCaustics(canvas, width, height, waveY, fillHeight, amp, leftTilt, rightTilt, scale);
            canvas.restore();

            drawHighlights(canvas, width, waveY, amp, leftTilt, rightTilt, scale);
            drawEdgeHighlights(canvas, width, waveY, amp, leftTilt, rightTilt, scale);

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

    /**
     * 把背景（静态位图或当前 GIF 帧）按 cover 裁剪铺进四边外扩 margin 的 bgCover 位图
     * （margin = 位移幅度 + 4px）：网格位移最大 ±amp 时仍采样在位图内，自定义背景不露画布黑底。
     * coverRect 记录外扩矩形（view 坐标，含负 left/top），供 updateMesh 映射顶点。
     * 返回是否已就绪。静态位图仅首次/尺寸变化时重绘。
     */
    private boolean prepareCover(int width, int height) {
        int margin = (int) Math.ceil(REFRACT_AMP * (width / 976f)) + 4;
        int bmWidth = width + 2 * margin;
        int bmHeight = height + 2 * margin;
        if (bgCover == null || bgCover.getWidth() != bmWidth || bgCover.getHeight() != bmHeight) {
            if (bgCover != null) bgCover.recycle();
            try {
                bgCover = Bitmap.createBitmap(bmWidth, bmHeight, Bitmap.Config.ARGB_8888);
            } catch (Throwable t) {
                bgCover = null;
                return false;
            }
            bgCoverCanvas = new Canvas(bgCover);
            coverRect.set(-margin, -margin, width + margin, height + margin);
            coverDirty = true;
        }
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
            drawCoverBitmap(frame, bmWidth, bmHeight);
            coverDirty = true;   // GIF 帧每帧都在变，下一帧仍需重绘
        } else if (bgBitmap != null && !bgBitmap.isRecycled()) {
            if (coverDirty) {
                drawCoverBitmap(bgBitmap, bmWidth, bmHeight);
                coverDirty = false;
            }
        } else {
            return false;
        }
        return true;
    }

    private void drawCoverBitmap(Bitmap src, int width, int height) {
        float bw = src.getWidth();
        float bh = src.getHeight();
        if (bw <= 0f || bh <= 0f) return;
        float fit = Math.max(width / bw, height / bh);
        float dw = bw * fit;
        float dh = bh * fit;
        float left = (width - dw) / 2f;
        float top = (height - dh) / 2f;
        coverDstRect.set(left, top, left + dw, top + dh);
        bgCover.eraseColor(Color.TRANSPARENT);
        bgCoverCanvas.drawBitmap(src, null, coverDstRect, bgPaint);
    }

    /**
     * 生成折射网格顶点：仅 x 方向位移，水面以上为 0，以下 sin(y*ky+phase)*amp*ramp。
     * 顶点按外扩矩形 coverRect 映射而非 view 坐标：bgCover 比 view 四边各大 margin，
     * 位移最大 ±amp 时采样仍在位图内，不露底；左右各 8% 宽度内位移按 smoothstep 渐入，避免边缘硬切。
     * 顶点坐标即 view 坐标（coverRect.left/top 为负）；波形相位与水位判断也用同一坐标，与波浪同源。
     * 顶点按行优先（x 变化最快）排列，与 drawBitmapMesh 约定一致。phase 与波浪同源（waveOffset）。
     */
    private void updateMesh(int width, int height, float waveY) {
        float ampPx = REFRACT_AMP * (width / 976f);
        float kyPx = REFRACT_KY / (width / 976f);   // rad / 设备 px（换算自设计基准）
        float span = Math.max(1f, height - waveY);
        float fadeHalf = width * 0.08f;
        int idx = 0;
        for (int j = 0; j <= MESH_ROWS; j++) {
            float y = coverRect.top + coverRect.height() * j / MESH_ROWS;
            float dx = 0f;
            if (y > waveY) {
                // 幅度随深度“缓慢”增大：t^1.5 而非线性，浅处更弱、深处渐强
                float t = Math.min(1f, (y - waveY) / span);
                float ramp = t * (float) Math.sqrt(t);
                dx = (float) Math.sin(y * kyPx + waveOffset) * ampPx * ramp;
            }
            for (int i = 0; i <= MESH_COLS; i++) {
                float x = coverRect.left + coverRect.width() * i / MESH_COLS;
                mesh[idx++] = x + dx * edgeFade(x, width, fadeHalf);
                mesh[idx++] = y;
            }
        }
    }

    /** smoothstep：t 截到 [0,1] 后两端平滑，用于边缘位移渐入。 */
    private static float smooth01(float t) {
        t = Math.max(0f, Math.min(1f, t));
        return t * t * (3f - 2f * t);
    }

    /** 左右各 fadeHalf 宽度内位移从 0 平滑升到 1。 */
    private static float edgeFade(float x, float width, float fadeHalf) {
        return smooth01(x / fadeHalf) * smooth01((width - x) / fadeHalf);
    }

    private void ensureAbsorbGradient(float waveY, int height) {
        int wy = (int) waveY;
        if (wy == lastGradWaveY && height == lastGradHeight) return;
        liquidPaint.setShader(new LinearGradient(0f, waveY, 0f, height,
                ABSORB_TOP_COLOR, ABSORB_BOTTOM_COLOR, Shader.TileMode.CLAMP));
        lastGradWaveY = wy;
        lastGradHeight = height;
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

    private float surfaceY(float x, int width, float waveY, float amp, float leftTilt, float rightTilt) {
        float tilt = leftTilt + (rightTilt - leftTilt) * (x / width);
        return waveY + (float) Math.sin(x / width * 4 * Math.PI + waveOffset) * amp + tilt;
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

    /** 水面高光：沿波面画多段短线段，长度/透明度/抖动固定 seed 生成，明暗不均且断续。 */
    private void drawHighlights(Canvas canvas, int width, float waveY, float amp,
                               float leftTilt, float rightTilt, float scale) {
        crestPaint.setStrokeWidth(Math.max(2.4f, width * 0.0032f));
        for (int i = 0; i < HIGHLIGHT_COUNT; i++) {
            if (hlSkip[i]) continue;
            float cx = (i + 0.5f) / HIGHLIGHT_COUNT * width;
            float half = hlLen[i] * scale * 0.5f;
            float jitter = hlJitter[i] * scale;
            float x0 = cx - half;
            float x1 = cx + half;
            float y0 = surfaceY(x0, width, waveY, amp, leftTilt, rightTilt) + jitter;
            float y1 = surfaceY(x1, width, waveY, amp, leftTilt, rightTilt) + jitter;
            int a = Math.round(hlAlpha[i] * 255f);
            crestPaint.setColor((a << 24) | 0x00FFFFFF);
            canvas.drawLine(x0, y0, x1, y1, crestPaint);
        }
    }

    /** 水下焦散：浅层不规则极短亮弧。布局固定 seed，仅漂移相位随时间走，不重排、不闪。 */
    private void drawCaustics(Canvas canvas, int width, int height, float waveY, float fillHeight,
                              float amp, float leftTilt, float rightTilt, float scale) {
        float band = Math.min(fillHeight, height * CAUSTIC_BAND);
        float driftAng = 2f * (float) Math.PI * caustPhaseMs / CAUSTIC_DRIFT_LOOP_MS;
        for (int i = 0; i < CAUSTIC_COUNT; i++) {
            float drift = (float) Math.sin(driftAng * caSpeedK[i] + caPhase[i]) * CAUSTIC_DRIFT_MAX * scale;
            float x = caBx[i] * width + drift;
            float half = caLen[i] * scale * 0.5f;
            float y = surfaceY(x, width, waveY, amp, leftTilt, rightTilt) + band * caBt[i];
            caPath.reset();
            caPath.moveTo(x - half, y);
            caPath.quadTo(x, y + caBow[i] * caLen[i] * scale, x + half, y);
            causticPaint.setStrokeWidth(Math.max(1f, caWidth[i] * scale));
            int a = Math.round(caAlpha[i] * 255f);
            causticPaint.setColor((a << 24) | 0x00FFFFFF);
            canvas.drawPath(caPath, causticPaint);
        }
    }

    /** 水面高对比边缘段：极短、几乎不透明的强亮线，复用高光画笔与波面基准。 */
    private void drawEdgeHighlights(Canvas canvas, int width, float waveY, float amp,
                                    float leftTilt, float rightTilt, float scale) {
        for (int i = 0; i < EDGE_COUNT; i++) {
            float cx = egBx[i] * width;
            float half = egLen[i] * scale * 0.5f;
            float jitter = egJitter[i] * scale;
            float x0 = cx - half;
            float x1 = cx + half;
            float y0 = surfaceY(x0, width, waveY, amp, leftTilt, rightTilt) + jitter;
            float y1 = surfaceY(x1, width, waveY, amp, leftTilt, rightTilt) + jitter;
            int a = Math.round(egAlpha[i] * 255f);
            crestPaint.setColor((a << 24) | 0x00FFFFFF);
            canvas.drawLine(x0, y0, x1, y1, crestPaint);
        }
    }

    /** 气泡不填充：只画边缘折射弧 + 左上高光点。 */
    private void drawBubbles(Canvas canvas, int width, float waveY, float fillHeight, float scale) {
        float driftX = -tiltX * 5f * scale;
        float driftY = tiltY * 2f * scale;
        bubblePaint.setStrokeWidth(Math.max(1f, BUBBLE_RING_WIDTH * scale));
        for (int i = 0; i < BUBBLE_COUNT; i++) {
            float r = BUBBLE_RADIUS[i] * scale;
            float cx = BUBBLE_X[i] * width + driftX * (0.5f + 0.25f * (i % 3));
            float cy = waveY + fillHeight * bubblePos[i] + driftY;
            canvas.drawCircle(cx, cy, r, bubblePaint);
            canvas.drawCircle(cx - r * 0.30f, cy - r * 0.30f, r * BUBBLE_SHINE_R, bubbleShinePaint);
        }
    }

    private void drawCenter(Canvas canvas, float centerX, float centerY, float scale) {
        float size = 104f * scale * fontScale;
        textPaint.setTypeface(serif);
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

        // 闪电+百分比整组随水纹相位极弱漂移：幅度约为字号的 1/70，只添浮动动感不伤可读；
        // 组内整体平移，拆开会与 groupWidth 排版错位。
        float dy = (float) Math.sin(centerX * 0.018f + waveOffset) * 1.2f * 1.2f * scale;
        float dx = (float) Math.cos(centerX * 0.014f + waveOffset) * 0.5f * 1.2f * scale;
        canvas.save();
        canvas.translate(dx, dy);
        if (bolt) {
            buildBoltPath(left, centerY - boltH / 2f, boltW, boltH);
            boltPaint.setAlpha(255);
            canvas.drawPath(boltPath, boltPaint);
        }

        textPaint.setAlpha(255);
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
        textPaint.setTypeface(serif);
        float size = 27f * scale * fontScale;
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
        // 整行随水纹相位极弱漂移，幅度比中心组弱：小字可读性优先。
        float dy = (float) Math.sin(centerX * 0.018f + waveOffset) * 1.2f * 0.8f * scale;
        float dx = (float) Math.cos(centerX * 0.014f + waveOffset) * 0.5f * 0.8f * scale;
        canvas.save();
        canvas.translate(dx, dy);
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
        canvas.restore();
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
