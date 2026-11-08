package com.hyperos.xj.rear;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.view.View;
import java.util.Locale;

public class PanelView extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint arc = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint liquid = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF ringRect = new RectF();
    private final Path clip = new Path();
    private final Typeface light = Typeface.create("sans-serif-light", Typeface.NORMAL);
    private final Typeface medium = Typeface.create("sans-serif-medium", Typeface.NORMAL);
    private ChargeState state = new ChargeState();
    private float fontScale = 1f;
    private int accent = 0xFF38BDF8;
    private String[] cornerFields = {"temp", "soc", "volt", "cur"};

    public PanelView(Context context) {
        super(context);
        setWillNotDraw(false);
        setBackgroundColor(Color.BLACK);
        track.setStyle(Paint.Style.STROKE);
        track.setStrokeCap(Paint.Cap.ROUND);
        arc.setStyle(Paint.Style.STROKE);
        arc.setStrokeCap(Paint.Cap.ROUND);
        liquid.setStyle(Paint.Style.FILL);
    }

    public void update(ChargeState next, RearConfig cfg) {
        state = next;
        fontScale = Math.max(0.8f, Math.min(1.4f, cfg.fontScale));
        accent = parseColor(cfg.accent);
        cornerFields = cfg.fields.split(",");
        invalidate();
    }

    private static int parseColor(String value) {
        try {
            return Color.parseColor(value);
        } catch (Throwable t) {
            return 0xFF38BDF8;
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int width = getWidth();
        int height = getHeight();
        if (width <= 0 || height <= 0) return;
        canvas.drawColor(Color.BLACK);

        float scale = width / 976f;
        float contentLeft = 296f * scale;
        float contentWidth = 680f * scale;
        float centerX = contentLeft + contentWidth / 2f;
        float centerY = height / 2f;
        float radius = Math.min(contentWidth, height) * 0.33f;
        float stroke = Math.max(6f, 10f * scale);
        float level = Math.max(0, Math.min(100, state.level));

        track.setColor(0x33FFFFFF);
        track.setStrokeWidth(stroke);
        ringRect.set(centerX - radius, centerY - radius, centerX + radius, centerY + radius);
        canvas.drawCircle(centerX, centerY, radius, track);

        float liquidTop = centerY + radius - 2f * radius * level / 100f;
        liquid.setColor(withAlpha(accent, 0.18f));
        clip.reset();
        clip.addCircle(centerX, centerY, radius, Path.Direction.CW);
        canvas.save();
        canvas.clipPath(clip);
        canvas.drawRect(centerX - radius, liquidTop, centerX + radius, centerY + radius, liquid);
        canvas.restore();

        arc.setColor(accent);
        arc.setStrokeWidth(stroke);
        canvas.drawArc(ringRect, -90f, 360f * level / 100f, false, arc);

        text.setTypeface(light);
        text.setTextAlign(Paint.Align.CENTER);
        text.setColor(Color.WHITE);
        text.setTextSize(88f * scale * fontScale);
        canvas.drawText(String.format(Locale.US, "%.1f", state.powerW), centerX, centerY + 20f * scale, text);
        text.setTypeface(medium);
        text.setTextSize(26f * scale);
        text.setColor(0x99FFFFFF);
        canvas.drawText("W", centerX, centerY + 58f * scale, text);

        text.setTypeface(medium);
        text.setTextAlign(Paint.Align.CENTER);
        text.setTextSize(18f * scale);
        text.setColor(0xB3FFFFFF);
        canvas.drawText("实时功率", centerX, centerY - radius - 24f * scale, text);

        drawPill(canvas, centerX, 42f * scale, state.statusText, scale);

        float pad = 28f * scale;
        float[] xs = {contentLeft + pad, contentLeft + contentWidth - pad,
                contentLeft + pad, contentLeft + contentWidth - pad};
        float[] ys = {118f * scale, 118f * scale, height - 118f * scale, height - 118f * scale};
        Paint.Align[] aligns = {Paint.Align.LEFT, Paint.Align.RIGHT, Paint.Align.LEFT, Paint.Align.RIGHT};
        for (int i = 0; i < cornerFields.length && i < 4; i++) {
            drawField(canvas, cornerFields[i], xs[i], ys[i], aligns[i], scale);
        }

        text.setTypeface(medium);
        text.setTextAlign(Paint.Align.CENTER);
        text.setTextSize(20f * scale);
        text.setColor(0xCCFFFFFF);
        String footer = state.stageText;
        if (state.etaMin > 0) footer += " · 预计 " + state.etaMin + " 分钟";
        canvas.drawText(footer, centerX, height - 38f * scale, text);

        paint.setColor(0x14FFFFFF);
        paint.setStrokeWidth(Math.max(1f, scale));
        canvas.drawLine(contentLeft, 24f * scale, contentLeft, height - 24f * scale, paint);
    }

    private void drawPill(Canvas canvas, float centerX, float top, String label, float scale) {
        if (label == null || label.isEmpty()) return;
        text.setTypeface(medium);
        text.setTextSize(18f * scale);
        text.setTextAlign(Paint.Align.CENTER);
        float textWidth = text.measureText(label);
        float padH = 22f * scale;
        float pillHeight = 38f * scale;
        RectF rect = new RectF(
                centerX - textWidth / 2f - padH,
                top - pillHeight / 2f,
                centerX + textWidth / 2f + padH,
                top + pillHeight / 2f);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0xF0000000);
        canvas.drawRoundRect(rect, pillHeight / 2f, pillHeight / 2f, paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(Math.max(1f, 1.5f * scale));
        paint.setColor(withAlpha(accent, 0.7f));
        canvas.drawRoundRect(rect, pillHeight / 2f, pillHeight / 2f, paint);
        paint.setStyle(Paint.Style.FILL);
        text.setColor(accent);
        canvas.drawText(label, centerX, top + 6f * scale, text);
    }

    private void drawField(Canvas canvas, String id, float x, float y,
                           Paint.Align align, float scale) {
        String label;
        String value;
        switch (id) {
            case "temp":
                label = "温度"; value = String.format(Locale.US, "%.1f℃", state.tempC); break;
            case "soc":
                label = "电量"; value = state.level + "%"; break;
            case "volt":
                label = "电压"; value = String.format(Locale.US, "%.2fV", state.voltageV); break;
            case "cur":
                label = "电流"; value = String.format(Locale.US, "%.2fA", Math.abs(state.currentA)); break;
            case "power":
                label = "功率"; value = String.format(Locale.US, "%.1fW", Math.abs(state.powerW)); break;
            case "status":
                label = "状态"; value = state.statusText; break;
            case "design":
                label = "设计容量"; value = state.designMah > 0 ? state.designMah + "mAh" : "--"; break;
            case "full":
                label = "满充容量"; value = state.fullMah > 0 ? state.fullMah + "mAh" : "--"; break;
            case "health":
                label = "健康度"; value = state.health > 0 ? state.health + "%" : "--"; break;
            case "cycle":
                label = "循环"; value = state.cycles > 0 ? state.cycles + "次" : "--"; break;
            case "fake":
                label = "伪装"; value = state.fakeCycles >= 0 ? state.fakeCycles + "次" : "--"; break;
            case "proto":
                label = "协议"; value = state.proto.isEmpty() ? "--" : state.proto; break;
            case "rm":
                label = "剩余容量"; value = state.rmMah > 0 ? state.rmMah + "mAh" : "--"; break;
            case "soh":
                label = "电池寿命"; value = state.soh > 0 ? state.soh + "%" : "--"; break;
            default:
                return;
        }
        drawMetric(canvas, label, value, x, y, align, scale);
    }

    private void drawMetric(Canvas canvas, String label, String value, float x, float y,
                            Paint.Align align, float scale) {
        text.setTypeface(medium);
        text.setTextAlign(align);
        text.setTextSize(17f * scale);
        text.setColor(0x8CFFFFFF);
        canvas.drawText(label, x, y, text);
        text.setTypeface(light);
        text.setTextSize(42f * scale * fontScale);
        text.setColor(Color.WHITE);
        canvas.drawText(value, x, y + 46f * scale, text);
    }

    private static int withAlpha(int color, float alpha) {
        int a = Math.max(0, Math.min(255, (int) (255 * alpha)));
        return (color & 0x00FFFFFF) | (a << 24);
    }
}
